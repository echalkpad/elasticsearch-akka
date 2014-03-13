package org.jai.search.actors;

import static akka.actor.SupervisorStrategy.escalate;
import static akka.actor.SupervisorStrategy.restart;
import static akka.actor.SupervisorStrategy.stop;

import org.jai.search.data.SampleDataGeneratorService;
import org.jai.search.exception.DataGenerationException;
import org.jai.search.exception.DocumentGenerationException;
import org.jai.search.exception.DocumentTypeIndexingException;
import org.jai.search.exception.IndexDataException;
import org.jai.search.index.IndexProductDataService;

import akka.actor.ActorInitializationException;
import akka.actor.ActorKilledException;
import akka.actor.ActorRef;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.actor.SupervisorStrategy.Directive;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Function;
import akka.routing.FromConfig;
import scala.concurrent.duration.Duration;

public class SetupDocumentTypeWorkerActor extends UntypedActor
{
    final LoggingAdapter LOG = Logging.getLogger(getContext().system(), this);

    private final ActorRef dataGeneratorWorkerRouter;

    private final ActorRef documentGeneratorWorkerRouter;

    private final ActorRef indexDataWorkerRouter;

    private int totalDocumentsToIndex = 0;

    private int totalDocumentsToIndexDone = 0;

    private int totalDocumentTypesHandling = 0;

    public SetupDocumentTypeWorkerActor(final SampleDataGeneratorService sampleDataGeneratorService,
            final IndexProductDataService indexProductDataService)
    {
        dataGeneratorWorkerRouter = getContext().actorOf(Props.create(DataGeneratorWorkerActor.class, sampleDataGeneratorService),
                "dataGeneratorWorker");
        documentGeneratorWorkerRouter = getContext().actorOf(
                Props.create(DocumentGeneratorWorkerActor.class, sampleDataGeneratorService).withRouter(new FromConfig())
                        .withDispatcher("documentGenerateAndIndexWorkerActorDispatcher"), "documentGeneratorWorker");
        indexDataWorkerRouter = getContext().actorOf(
                Props.create(IndexProductDataWorkerActor.class, indexProductDataService).withRouter(new FromConfig())
                        .withDispatcher("documentGenerateAndIndexWorkerActorDispatcher"), "indexProductDataWorker");
    }

    private static SupervisorStrategy strategy = new OneForOneStrategy(10, Duration.create("1 minute"),
            new Function<Throwable, Directive>()
            {
                @Override
                public Directive apply(final Throwable t)
                {
                    if (t instanceof DataGenerationException)
                    {
                        return restart();
                    }
                    else if (t instanceof DocumentGenerationException)
                    {
                        return restart();
                    }
                    else if (t instanceof IndexDataException)
                    {
                        return restart();
                    }
                    else if (t instanceof ActorInitializationException)
                    {
                        return stop();
                    }
                    else if (t instanceof ActorKilledException)
                    {
                        return stop();
                    }
                    else if (t instanceof Exception)
                    {
                        return restart();
                    }
                    else
                    {
                        return escalate();
                    }
                }
            });

    @Override
    public SupervisorStrategy supervisorStrategy()
    {
        return strategy;
    }

    @Override
    public void onReceive(final Object message)
    {
        // LOG.debug("Worker Actor message for SetupIndexWorkerActor is:" + message);
        try
        {
            // message from master actor
            if (message instanceof IndexDocumentTypeMessageVO)
            {
                handleDocumentTypeForDataGeneration(message);
            }
            // message from data generator, document generator and indexer
            else if (message instanceof IndexDocumentVO)
            {
                generateDocumentAndIndexDocument(message);
            }
            else if (message instanceof Exception)
            {
                handleExceptionInChildActors(message);
            }
            else
            {
                unhandled(message);
            }
        }
        catch (final Exception e)
        {
            // TODO: check if need to handle it differently
            final DocumentTypeIndexingException documentTypeIndexingException = new DocumentTypeIndexingException(e);
            LOG.error("Error occured while indexing document type: {}", message);
            // TODO: check failures needs to be handled differently.
            totalDocumentTypesHandling--;
            sendMessageToParent(documentTypeIndexingException);
        }
    }

    private void handleExceptionInChildActors(final Object message)
    {
        final Exception ex = (Exception) message;
        if (ex instanceof DataGenerationException)
        {
            // issue in generating data itself for a document type. As each worker only handling one document type, tell parent that it is
            // done.
            // TODO: check proper handling
            totalDocumentTypesHandling--;
            updateStateAndResetIfAllDone();
        }
        else if (ex instanceof DocumentGenerationException)
        {
            // TODO: not handling failure separately , change it.
            totalDocumentsToIndex--;
            updateStateAndResetIfAllDone();
        }
        else if (ex instanceof IndexDataException)
        {
            // TODO: not handling failure separately , change it.
            totalDocumentsToIndexDone++;
            updateStateAndResetIfAllDone();
        }
        else
        {
            unhandled(message);
        }
    }

    private void generateDocumentAndIndexDocument(final Object message)
    {
        final IndexDocumentVO indexDocumentVO = (IndexDocumentVO) message;
        // Indexing not done, process the data further.
        if (!indexDocumentVO.isIndexDone())
        {
            // Document not generated yet
            if (indexDocumentVO.getProduct() == null && indexDocumentVO.getProductProperty() == null && indexDocumentVO.getProductGroup() == null)
            {
                documentGeneratorWorkerRouter.tell(indexDocumentVO, getSelf());
                totalDocumentsToIndex++;
                // TODO: implement supervisor strategy for failing stuff.
            }
            // Document generated, index it.
            else
            {
                indexDataWorkerRouter.tell(indexDocumentVO, getSelf());
            }
        }
        else
        {
            totalDocumentsToIndexDone++;
            updateStateAndResetIfAllDone();
        }
    }

    private void handleDocumentTypeForDataGeneration(final Object message)
    {
        final IndexDocumentTypeMessageVO indexDocumentTypeMessageVO = (IndexDocumentTypeMessageVO) message;
        dataGeneratorWorkerRouter.tell(indexDocumentTypeMessageVO, getSelf());
        totalDocumentTypesHandling++;
    }

    private void updateStateAndResetIfAllDone()
    {
        LOG.debug("Total indexing stats for document type are: totalProductsToIndex: {}, totalProductsToIndexDone: {}", new Object[] {
                totalDocumentsToIndex, totalDocumentsToIndexDone });
        if (totalDocumentsToIndex == totalDocumentsToIndexDone)
        {
            LOG.debug("All products indexing done for total document types {} sending message {} to parent!", new Object[] {
                    totalDocumentTypesHandling, IndexingMessage.DOCUMENTTYPE_DONE });
            // Find parent actor in the hierarchy.
            // akka://SearchIndexingSystem/user/setupIndexMasterActor/setupIndexWorkerActor/$a
            // Send the document type done for all the handling types, for now total products done means all types done, change it.
            for (int i = 0; i < totalDocumentTypesHandling; i++)
            {
                sendMessageToParent(IndexingMessage.DOCUMENTTYPE_DONE);
            }
            totalDocumentsToIndex = 0;
            totalDocumentsToIndexDone = 0;
            totalDocumentTypesHandling = 0;
            stopTheActor();
        }
    }

    private void stopTheActor()
    {
        // Stop the actors
        //TODO: check if message order processing etc. allow this right way.
//        getContext().stop(getSelf());
    }

    private void sendMessageToParent(final Object message)
    {
        getContext().actorSelection("../../").tell(message, null);
    }
}