/*
 * Copyright 2020 Advanced Software Technologies Lab at ETH Zurich, Switzerland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.commands.monkey.ape.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.android.commands.monkey.ape.utils.Config.truncateTextLength;

public class StringCache {


    public static Map<String, String> stringDict = new HashMap<String, String>();
    static final List<String> stringList = new ArrayList<>();

    public static String cacheString(String string) {
        return cacheString(string, false);
    }

    public static String cacheString(String string, boolean addToList) {
        if (string == null) {
            throw new NullPointerException("Cannot cache null string.");
        }
        if (string.length() == 0) {
            return "";
        }
        String existing = stringDict.get(string);
        if (existing == null) {
            stringDict.put(string, string);
            existing = string;
            if (addToList) {
                if (stringList.size() < maxStringListSize) {
                    stringList.add(string);
                }
            }
        }
        return existing;
    }

    public static String cacheStringEmptyOnNull(Object o) {
        return cacheStringEmptyOnNull(o, false);
    }

    public static String cacheStringEmptyOnNull(Object o, boolean addToList) {
        if (o == null) {
            return "";
        }
        String val = o.toString();
        return cacheString(val, addToList);
    }

    /**
     * The cap on the cache, which is populated exclusively from text observed on screen during the
     * run ({@code cacheString(s, true)} call sites). There is no file-derived contribution: the jar
     * reads no behavioral input from {@code /sdcard} other than {@code ape.properties}
     * (INV-RUN-06).
     */
    static final int maxStringListSize = Config.maxStringListSize;

    public static String removeQuotes(CharSequence input) {
        if (input == null) {
            return "";
        }
        return input.toString().replaceAll("\"", "");
    }

    public static String truncateText(CharSequence input) {
        if (input == null) {
            return "";
        }
        String origin = input.toString();
        if (truncateTextLength < origin.length()) {
            return origin.substring(0, truncateTextLength);
        }
        return origin;
    }

    public static String nextString() {
        // INV-INP-06: check for an empty cache BEFORE drawing an index. On a
        // text-sparse screen (typical login form) no on-screen text has been captured
        // and the list is genuinely empty; nextInt(0) would throw
        // IllegalArgumentException.
        if (stringList.isEmpty()) {
            String string = RandomHelper.nextFormattedString();
            Logger.iformat("Use random string %s", string);
            return string;
        }

        // INV-INP-07 / INV-RUN-08: the index comes from the run's seeded stream, so the same seed
        // over the same cache contents yields the same strings. This was the last decision source
        // outside that stream.
        int i = RandomHelper.nextInt(stringList.size());
        String string = stringList.get(i);
        Logger.iformat("Select [%s] %d/%d from string list", string, i, stringList.size());

        if (RandomHelper.toss(Config.randomFormattedStringProp)) {
            string = RandomHelper.nextFormattedString();
            Logger.iformat("Use random string %s", string);
        }
        return string;
    }
}
