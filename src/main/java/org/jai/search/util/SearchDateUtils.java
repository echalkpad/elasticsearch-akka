package org.jai.search.util;

import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.joda.time.format.DateTimeFormatter;
import org.elasticsearch.common.joda.time.format.ISODateTimeFormat;

import java.util.Date;

public final class SearchDateUtils
{
    public static final String SEARCH_DATE_FORMAT_YYYY_MM_DD_T_HH_MM_SSSZZ = "yyyy-MM-dd'T'HH:mm:ssZZ";

    private static final DateTimeFormatter searchDateFormat = ISODateTimeFormat.dateTimeNoMillis();

    private SearchDateUtils()
    {
    }

    public static Date getFormattedDate(final String dateString)
    {
        return searchDateFormat.parseDateTime(dateString).toDate();
    }

    public static String formatDate(final Date date)
    {
        return searchDateFormat.print(new DateTime(date).getMillis());
    }
}
