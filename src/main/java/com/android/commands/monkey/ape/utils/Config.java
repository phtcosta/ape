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

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public class Config {

    static private final Properties configurations;
    // DO NOT move this static block as it should be executed first.
    static {
        configurations = new Properties(System.getProperties());
        loadConfiguration("/data/local/tmp/ape.properties");
        loadConfiguration("/sdcard/ape.properties");
    }

    /**
     * Readonly. A field is here only while {@code src/main} reads it; a key the code reads through
     * the plan ({@code RunContext.current().spec()}) has no field here (design D-B24).
     */
    public static final int restartThresholdMax = Config.getInteger("ape.restartThresholdMax", 300);
    public static final int restartThresholdMin = Config.getInteger("ape.restartThresholdMin", 100);
    public static final double inputRate = Config.getDouble("ape.inputRate", 0.8D);

    public static final String activityManagerType = Config.get("ape.activityManagerType", "state");

    public static final boolean enableReplacingNamelet = Config.getBoolean("ape.enableReplacingNamelet", false);
    public static final int actionRefinmentThreshold = Config.getInteger("ape.actionRefinmentThreshold", 3);
    public static final int maxInitialNamesPerStateThreshold = Config.getInteger("ape.maxInitialNamesPerStateThreshold", 20);
    public static final boolean actionRefinementFirst = Config.getBoolean("ape.actionRefinementFirst", true);

    public static final boolean alwaysIgnoreWebView = Config.getBoolean("ape.alwaysIgnoreWebView", false); // false;
    public static final boolean alwaysIgnoreWebViewAction = Config.getBoolean("ape.alwaysIgnoreWebViewAction", false); // false;
    public static final int ignoreWebViewThreshold = Config.getInteger("ape.ignoreWebViewThreshold", 64);
    public static final boolean excludeEmptyChild = Config.getBoolean("ape.excludeEmptyChild", true);
    public static final boolean excludeInvisibleNode = Config.getBoolean("ape.excludeInvisibleNode", true);

    public static final boolean patchGUITree = Config.getBoolean("ape.patchGUITree", true);
    public static final boolean computeImageText = Config.getBoolean("ape.computeImageText", true);

    public static final int flushImagesThreshold = Config.getInteger("ape.flushImagesThreshold", 10);
    public static final int imageWriterCount = Config.getInteger("ape.imageWriterCount", 3);
    public static final long defaultGUIThrottle = Config.getLong("ape.defaultGUIThrottle", 200L);
    // idle-timeout-cap: global timeout (ms) of the getRootInActiveWindowSlow idle wait
    // and (÷1000) the refreshNewState "window stuck animating" break threshold. Default
    // 10000 == the former `1000 * 10` literal; 10000/1000 == 10 == the former `>= 10`.
    public static final long maxIdleTimeoutMs = Config.getLong("ape.maxIdleTimeoutMs", 10000L);
    public static final long swipeDuration = Config.getLong("ape.swipeDuration", 200);
    public static final long refectchInfoWaitingInterval = Config.getLong("ape.refectchInfoWaitingInterval", 50);
    public static final int refectchInfoCount = Config.getInteger("ape.refectchInfoCount", 4);

    public static final boolean ignoreEmpty = Config.getBoolean("ape.ignoreEmpty", true);
    public static final boolean ignoreOutOfBounds = Config.getBoolean("ape.ignoreOutOfBounds", true);

    public static final boolean useAncestorNamer = Config.getBoolean("ape.useAncestorNamer", true);

    public static final int truncateTextLength = Config.getInteger("ape.truncateTextLength", 8);
    public static final int maxStringListSize = Config.getInteger("ape.maxStringListSize", 2000);
    public static final double randomFormattedStringProp = Config.getDouble("ape.randomFormattedStringProp", 0.5);

    public static final int maxStatesPerActivity = Config.getInteger("ape.maxStatesPerActivity", 10);
    public static final int maxGUITreesPerState = Config.getInteger("ape.maxGUITreesPerState", 20);

    public static final int trivialActivityStateThreshold = Config.getInteger("ape.trivialActivityStateThreshold", 5);
    public static final int trivialActivityVisitThreshold = Config.getInteger("ape.trivialActivityVisitThreshold", 16);

    public static final int trivialStateActionThreshold = Config.getInteger("ape.trivialStateActionThreshold", 5);
    public static final int trivialStateWidgetThreshold = Config.getInteger("ape.trivialStateWidgetThreshold", 5);

    public static final boolean usePatchNamer = Config.getBoolean("ape.usePatchNamer", true);

    // gh15 A-4: max live per-state entries in UICoverageTracker before eviction.
    // Evicted entries are folded into the per-Activity rollup, so coverage is not
    // lost — this only bounds memory (INV-COV-05).
    public static final int coverageMaxStates = Config.getInteger("ape.coverageMaxStates", 2000);

    private static void loadConfiguration(String fileName) {
        File configFile = new File(fileName);
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                configurations.load(fis);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Fail to load the configuration file at " + configFile);
            }
        }
    }

    public static Object set(String key, String value) {
        return configurations.setProperty(key, value);
    }

    public static Object setBoolean(String key, boolean value) {
        return configurations.setProperty(key, Boolean.toString(value));
    }

    public static Object setDouble(String key, double value) {
        return configurations.setProperty(key, Double.toString(value));
    }

    public static String get(String key) {
        return configurations.getProperty(key);
    }

    public static String get(String key, String defaultValue) {
        String value = get(key);
        if (value != null) {
            return value;
        }
        configurations.put(key, defaultValue);
        return defaultValue;
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        if (value != null) {
            return Boolean.valueOf(value);
        }
        configurations.put(key, defaultValue);
        return defaultValue;
    }

    public static int getInteger(String key, int defaultValue) {
        String value = get(key);
        if (value != null) {
            try {
                return Integer.valueOf(value);
            } catch (NumberFormatException e) {
            }
        }
        configurations.put(key, defaultValue);
        return defaultValue;
    }

    public static long getLong(String key, long defaultValue) {
        String value = get(key);
        if (value != null) {
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException e) {
            }
        }
        configurations.put(key, defaultValue);
        return defaultValue;
    }

    public static double getDouble(String key, double defaultValue) {
        String value = get(key);
        if (value != null) {
            try {
                return Double.valueOf(value);
            } catch (NumberFormatException e) {
            }
        }
        configurations.put(key, defaultValue);
        return defaultValue;
    }

    public static void printConfigurations() {
        Logger.println("Configurations:");
        int maxLength = Integer.MIN_VALUE;
        List<String> keys = new ArrayList<>(configurations.size());
        for (Object key : configurations.keySet()) {
            int length = key.toString().length();
            if (length > maxLength) {
                maxLength = length;
            }
            keys.add(key.toString());
        }
        Collections.sort(keys);
        String formatter = String.format(" %%%ds: %%s", maxLength);
        for (String key : keys) {
            Logger.format(formatter, key, configurations.get(key));
        }
    }

}
