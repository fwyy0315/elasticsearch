/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.time;

import org.apache.logging.log4j.LogManager;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.joda.time.DateTimeZone;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.common.time.DateUtilsRounding.getMonthOfYear;
import static org.elasticsearch.common.time.DateUtilsRounding.getTotalMillisByYearMonth;
import static org.elasticsearch.common.time.DateUtilsRounding.getYear;
import static org.elasticsearch.common.time.DateUtilsRounding.utcMillisAtStartOfYear;

public class DateUtils {
    public static DateTimeZone zoneIdToDateTimeZone(ZoneId zoneId) {
        if (zoneId == null) {
            return null;
        }
        if (zoneId instanceof ZoneOffset) {
            // the id for zoneoffset is not ISO compatible, so cannot be read by ZoneId.of
            return DateTimeZone.forOffsetMillis(((ZoneOffset)zoneId).getTotalSeconds() * 1000);
        }
        return DateTimeZone.forID(zoneId.getId());
    }

    private static final DeprecationLogger deprecationLogger = new DeprecationLogger(LogManager.getLogger(DateFormatters.class));
    // pkg private for tests
    static final Map<String, String> DEPRECATED_SHORT_TIMEZONES;
    public static final Set<String> DEPRECATED_SHORT_TZ_IDS;
    static {
        Map<String, String> tzs = new HashMap<>();
        tzs.put("EST", "-05:00"); // eastern time without daylight savings
        tzs.put("HST", "-10:00");
        tzs.put("MST", "-07:00");
        tzs.put("ROC", "Asia/Taipei");
        tzs.put("Eire", "Europe/London");
        DEPRECATED_SHORT_TIMEZONES = Collections.unmodifiableMap(tzs);
        DEPRECATED_SHORT_TZ_IDS = tzs.keySet();
    }

    public static ZoneId dateTimeZoneToZoneId(DateTimeZone timeZone) {
        if (timeZone == null) {
            return null;
        }
        if (DateTimeZone.UTC.equals(timeZone)) {
            return ZoneOffset.UTC;
        }

        return of(timeZone.getID());
    }

    public static ZoneId of(String zoneId) {
        String deprecatedId = DEPRECATED_SHORT_TIMEZONES.get(zoneId);
        if (deprecatedId != null) {
            deprecationLogger.deprecatedAndMaybeLog("timezone",
                "Use of short timezone id " + zoneId + " is deprecated. Use " + deprecatedId + " instead");
            return ZoneId.of(deprecatedId);
        }
        return ZoneId.of(zoneId).normalized();
    }

    private static final Instant MAX_NANOSECOND_INSTANT = Instant.parse("2262-04-11T23:47:16.854775807Z");

    /**
     * convert a java time instant to a long value which is stored in lucene
     * the long value resembles the nanoseconds since the epoch
     *
     * @param instant the instant to convert
     * @return        the nano seconds and seconds as a single long
     */
    public static long toLong(Instant instant) {
        if (instant.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("date[" + instant + "] is before the epoch in 1970 and cannot be " +
                "stored in nanosecond resolution");
        }
        if (instant.isAfter(MAX_NANOSECOND_INSTANT)) {
            throw new IllegalArgumentException("date[" + instant + "] is after 2262-04-11T23:47:16.854775807 and cannot be " +
                "stored in nanosecond resolution");
        }
        return instant.getEpochSecond() * 1_000_000_000 + instant.getNano();
    }

    /**
     * convert a long value to a java time instant
     * the long value resembles the nanoseconds since the epoch
     *
     * @param nanoSecondsSinceEpoch the nanoseconds since the epoch
     * @return                      the instant resembling the specified date
     */
    public static Instant toInstant(long nanoSecondsSinceEpoch) {
        if (nanoSecondsSinceEpoch < 0) {
            throw new IllegalArgumentException("nanoseconds are [" + nanoSecondsSinceEpoch + "] are before the epoch in 1970 and cannot " +
                "be processed in nanosecond resolution");
        }
        if (nanoSecondsSinceEpoch == 0) {
            return Instant.EPOCH;
        }

        long seconds = nanoSecondsSinceEpoch / 1_000_000_000;
        long nanos = nanoSecondsSinceEpoch % 1_000_000_000;
        return Instant.ofEpochSecond(seconds, nanos);
    }

    /**
     * Convert a nanosecond timestamp in milliseconds
     *
     * @param nanoSecondsSinceEpoch the nanoseconds since the epoch
     * @return                      the milliseconds since the epoch
     */
    public static long toMilliSeconds(long nanoSecondsSinceEpoch) {
        if (nanoSecondsSinceEpoch < 0) {
            throw new IllegalArgumentException("nanoseconds are [" + nanoSecondsSinceEpoch + "] are before the epoch in 1970 and will " +
                "be converted to milliseconds");
        }

        if (nanoSecondsSinceEpoch == 0) {
            return 0;
        }

        return nanoSecondsSinceEpoch / 1_000_000;
    }

    /**
     * Rounds the given utc milliseconds sicne the epoch down to the next unit millis
     *
     * Note: This does not check for correctness of the result, as this only works with units smaller or equal than a day
     *       In order to ensure the performane of this methods, there are no guards or checks in it
     *
     * @param utcMillis   the milliseconds since the epoch
     * @param unitMillis  the unit to round to
     * @return            the rounded milliseconds since the epoch
     */
    public static long roundFloor(long utcMillis, final long unitMillis) {
        if (utcMillis >= 0) {
            return utcMillis - utcMillis % unitMillis;
        } else {
            utcMillis += 1;
            return utcMillis - utcMillis % unitMillis - unitMillis;
        }
    }

    /**
     * Round down to the beginning of the quarter of the year of the specified time
     * @param utcMillis the milliseconds since the epoch
     * @return The milliseconds since the epoch rounded down to the quarter of the year
     */
    public static long roundQuarterOfYear(final long utcMillis) {
        int year = getYear(utcMillis);
        int month = getMonthOfYear(utcMillis, year);
        int firstMonthOfQuarter = (((month-1) / 3) * 3) + 1;
        return DateUtils.of(year, firstMonthOfQuarter);
    }

    /**
     * Round down to the beginning of the month of the year of the specified time
     * @param utcMillis the milliseconds since the epoch
     * @return The milliseconds since the epoch rounded down to the month of the year
     */
    public static long roundMonthOfYear(final long utcMillis) {
        int year = getYear(utcMillis);
        int month = getMonthOfYear(utcMillis, year);
        return DateUtils.of(year, month);
    }

    /**
     * Round down to the beginning of the year of the specified time
     * @param utcMillis the milliseconds since the epoch
     * @return The milliseconds since the epoch rounded down to the beginning of the year
     */
    public static long roundYear(final long utcMillis) {
        int year = getYear(utcMillis);
        return utcMillisAtStartOfYear(year);
    }

    /**
     * Round down to the beginning of the week based on week year of the specified time
     * @param utcMillis the milliseconds since the epoch
     * @return The milliseconds since the epoch rounded down to the beginning of the week based on week year
     */
    public static long roundWeekOfWeekYear(final long utcMillis) {
        return roundFloor(utcMillis + 3 * 86400 * 1000L, 604800000) - 3 * 86400 * 1000L;
    }

    /**
     * Return the first day of the month
     * @param year  the year to return
     * @param month the month to return, ranging from 1-12
     * @return the milliseconds since the epoch of the first day of the month in the year
     */
    private static long of(final int year, final int month) {
        long millis = utcMillisAtStartOfYear(year);
        millis += getTotalMillisByYearMonth(year, month);
        return millis;
    }
}
