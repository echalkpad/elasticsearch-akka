package org.jai.search.client.impl;

import static com.google.common.collect.Maps.newHashMap;
import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.common.settings.ImmutableSettings.Builder.EMPTY_SETTINGS;
import static org.elasticsearch.node.NodeBuilder.nodeBuilder;

import org.jai.search.client.SearchClientService;
import org.jai.search.model.ElasticSearchReservedWords;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.network.NetworkUtils;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.node.Node;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Map;

import javax.annotation.PostConstruct;

@Service(value = "searchClientService")
public class SearchClientServiceMockImpl implements SearchClientService
{
    private final Map<String, Node> nodes = newHashMap();

    private final Map<String, Client> clients = newHashMap();

    private Settings defaultSettings = ImmutableSettings
            .settingsBuilder()
            .put(ElasticSearchReservedWords.CLUSTER_NAME.getText(), "test-cluster-" + NetworkUtils.getLocalAddress().getHostName())
            // data dir, other node dir for lock etc will still be created
            .put(ElasticSearchReservedWords.PATH_DATA.getText(),
                    new File(System.getProperty("java.io.tmpdir") + "/esintegrationtest/data").getAbsolutePath())
            .put(ElasticSearchReservedWords.PATH_WORK.getText(),
                    new File(System.getProperty("java.io.tmpdir") + "/esintegrationtest/work").getAbsolutePath())
            .put(ElasticSearchReservedWords.PATH_LOG.getText(),
                    new File(System.getProperty("java.io.tmpdir") + "/esintegrationtest/log").getAbsolutePath())
            .put(ElasticSearchReservedWords.PATH_CONF.getText(), new File("config").getAbsolutePath())
            // will not survive restart
            // TODO: memory store type cause out of memory in eclipse on low config machine
            // Check how to set memory setting and allocations in memory store type.
            .put("index.store.type", "memory").build();

    @PostConstruct
    public void createNodes() throws Exception
    {
        final Settings settings = settingsBuilder().put(ElasticSearchReservedWords.NUMBER_OF_SHARDS.getText(), 3)
                .put(ElasticSearchReservedWords.NUMBER_OF_REPLICAS.getText(), 1)
                // .put(ElasticSearchReservedWords.INDEX_MAPPER_DYNAMIC.getText(), false)
                .build();
        startNode("server1", settings);
        startNode("server2", settings);
    }

    // @PreDestroy
    public void closeNodes()
    {
        getClient().close();
        closeAllNodes();
    }

    @Override
    public Client getClient()
    {
        return client("server1");
    }

    // @Override
    @Override
    public void addNewNode(final String name)
    {
        buildNode(name);
        startNode(name);
    }

    // @Override
    @Override
    public void removeNode(final String nodeName)
    {
        closeNode(nodeName);
    }

    public void putDefaultSettings(final Settings.Builder settings)
    {
        putDefaultSettings(settings.build());
    }

    public void putDefaultSettings(final Settings settings)
    {
        defaultSettings = ImmutableSettings.settingsBuilder().put(defaultSettings).put(settings).build();
    }

    public Node startNode(final String id)
    {
        return buildNode(id).start();
    }

    public Node startNode(final String id, final Settings.Builder settings)
    {
        return startNode(id, settings.build());
    }

    public Node startNode(final String id, final Settings settings)
    {
        return buildNode(id, settings).start();
    }

    public Node buildNode(final String id)
    {
        return buildNode(id, EMPTY_SETTINGS);
    }

    public Node buildNode(final String id, final Settings.Builder settings)
    {
        return buildNode(id, settings.build());
    }

    public Node buildNode(final String id, final Settings settings)
    {
        final String settingsSource = getClass().getName().replace('.', '/') + ".yml";
        Settings finalSettings = settingsBuilder().loadFromClasspath(settingsSource).put(defaultSettings).put(settings).put("name", id)
                .build();
        if (finalSettings.get("gateway.type") == null)
        {
            // default to non gateway
            finalSettings = settingsBuilder().put(finalSettings).put("gateway.type", "none").build();
        }
        if (finalSettings.get("cluster.routing.schedule") != null)
        {
            // decrease the routing schedule so new nodes will be added quickly
            finalSettings = settingsBuilder().put(finalSettings).put("cluster.routing.schedule", "50ms").build();
        }
        final Node node = nodeBuilder().settings(finalSettings).build();
        nodes.put(id, node);
        clients.put(id, node.client());
        return node;
    }

    public void closeNode(final String id)
    {
        final Client client = clients.remove(id);
        if (client != null)
        {
            client.close();
        }
        final Node node = nodes.remove(id);
        if (node != null)
        {
            node.close();
        }
    }

    public Node node(final String id)
    {
        return nodes.get(id);
    }

    public Client client(final String id)
    {
        return clients.get(id);
    }

    public void closeAllNodes()
    {
        for (final Client client : clients.values())
        {
            client.close();
        }
        clients.clear();
        for (final Node node : nodes.values())
        {
            node.close();
        }
        nodes.clear();
    }
}
