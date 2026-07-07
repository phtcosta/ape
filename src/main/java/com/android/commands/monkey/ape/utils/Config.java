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
     * Readonly
     */
    public static final boolean takeScreenshot = Config.getBoolean("ape.takeScreenshot", true);
    public static final boolean takeScreenshotForNewState = Config.getBoolean("ape.takeScreenshotForNewState", false);
    public static final boolean takeScreenshotForEveryStep = Config.getBoolean("ape.takeScreenshotForEveryStep", false);
    public static final boolean saveGUITreeToXmlEveryStep = Config.getBoolean("ape.saveGUITreeToXmlEveryStep", false);

    public static final int throttleForUnvisitedAction = Config.getInteger("ape.throttleForUnvisitedAction", 200);
    public static final int throttleForActivityTransition = Config.getInteger("ape.throttleForActivityTransition", 500);
    public static final int baseThrottle = Config.getInteger("ape.baseThrottle", 0);
    public static final int maxThrottle = Config.getInteger("ape.maxThrottle", 5000);
    public static final int graphStableRestartThreshold = Config.getInteger("ape.graphStableRestartThreshold", 100);
    public static final int activityStableRestartThreshold = Config.getInteger("ape.activityStableRestartThreshold", 200);
    public static final int stateStableRestartThreshold = Config.getInteger("ape.stateStableRestartThreshold", 50);
    public static final int maxExtraPriorityAliasedActions = Config.getInteger("ape.maxExtraPriorityAliasedActions", 5);

    public static final boolean saveDotGraph = Config.getBoolean("ape.saveDotGraph", false);
    public static final boolean saveObjModel = Config.getBoolean("ape.saveObjModel", true);
    public static final boolean saveVisGraph = Config.getBoolean("ape.saveVisGraph", true);

    public static final boolean enableXPathAction = Config.getBoolean("ape.enableXPathAction", false);
    public static final boolean evolveModel = Config.getBoolean("ape.evolveModel", true);
    public static final boolean saveStates = Config.getBoolean("ape.saveStates", true);
    public static final int fuzzingActivityVisitThreshold = Config.getInteger("ape.fuzzingActivityVisitThreshold", 10);

    public static final boolean checkRestart = Config.getBoolean("ape.checkRestart", true);
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

    public static final double defaultEpsilon = Config.getDouble("ape.defaultEpsilon", 0.05D); // 0.05D;

    public static final boolean fillTransitionsByHistory = Config.getBoolean("ape.fillTransitionsByHistory", true); // true;
    public static final boolean fallbackToGraphTransition = Config.getBoolean("ape.fallbackToGraphTransition", true);
    public static final int trivialActivityRankThreshold = Config.getInteger("ape.trivialActivityRankThreshold", 3);
    public static final boolean useActionDiffer = Config.getBoolean("ape.useActionDiffer", true);
    public static final boolean doBackToTrivialActivity = Config.getBoolean("ape.doBackToTrivialActivity", false);

    public static final int flushImagesThreshold = Config.getInteger("ape.flushImagesThreshold", 10);
    public static final int imageWriterCount = Config.getInteger("ape.imageWriterCount", 3);
    public static final long defaultGUIThrottle = Config.getLong("ape.defaultGUIThrottle", 200L);
    // idle-timeout-cap: global timeout (ms) of the getRootInActiveWindowSlow idle wait
    // and (÷1000) the refreshNewState "window stuck animating" break threshold. Default
    // 10000 == the former `1000 * 10` literal; 10000/1000 == 10 == the former `>= 10`.
    public static final long maxIdleTimeoutMs = Config.getLong("ape.maxIdleTimeoutMs", 10000L);
    public static final long swipeDuration = Config.getLong("ape.swipeDuration", 200);
    public static final double fuzzingRate = Config.getDouble("ape.fuzzingRate", 0.02D);
    public static final long refectchInfoWaitingInterval = Config.getLong("ape.refectchInfoWaitingInterval", 50);
    public static final int refectchInfoCount = Config.getInteger("ape.refectchInfoCount", 4);
    public static final boolean doFuzzing = Config.getBoolean("ape.doFuzzing", true);

    public static final boolean ignoreEmpty = Config.getBoolean("ape.ignoreEmpty", true);
    public static final boolean ignoreOutOfBounds = Config.getBoolean("ape.ignoreOutOfBounds", true);

    public static final boolean useAncestorNamer = Config.getBoolean("ape.useAncestorNamer", true);

    public static final int truncateTextLength = Config.getInteger("ape.truncateTextLength", 8);
    public static final int maxStringPieceLength = Config.getInteger("ape.maxStringPieceLength", 32);
    public static final int maxStringListSize = Config.getInteger("ape.maxStringListSize", 2000);
    public static final double randomFormattedStringProp = Config.getDouble("ape.randomFormattedStringProp", 0.5);

    public static final int maxStatesPerActivity = Config.getInteger("ape.maxStatesPerActivity", 10);
    public static final int maxGUITreesPerState = Config.getInteger("ape.maxGUITreesPerState", 20);

    public static final int trivialActivityStateThreshold = Config.getInteger("ape.trivialActivityStateThreshold", 5);
    public static final int trivialActivityVisitThreshold = Config.getInteger("ape.trivialActivityVisitThreshold", 16);

    public static final int trivialStateActionThreshold = Config.getInteger("ape.trivialStateActionThreshold", 5);
    public static final int trivialStateWidgetThreshold = Config.getInteger("ape.trivialStateWidgetThreshold", 5);

    public static final boolean usePatchNamer = Config.getBoolean("ape.usePatchNamer", true);

    // Path to static analysis JSON on device (null = MOP scoring disabled).
    // Loaded once at class init from ape.properties; not updated if file changes at runtime.
    // Note: Config.get(key) returns null when key is absent — safe for Hashtable.
    public static final String mopDataPath = Config.get("ape.mopDataPath");

    // MOP scoring weights — configurable via ape.properties.
    // Controls how strongly MOP reachability boosts action priority relative to SATA base (~32-50).
    // Defaults chosen so MOP strongly dominates exploration toward monitored operations.
    public static final int mopWeightDirect = Config.getInteger("ape.mopWeightDirect", 500);
    public static final int mopWeightTransitive = Config.getInteger("ape.mopWeightTransitive", 300);

    // gh13: OPTIONSMENU-aware menu-open boost (T1.2). Default 250 — between
    // mopWeightWtg (200) and mopWeightTransitive (300).
    // Non-final: the rollback knobs below are toggled by unit tests at runtime.
    public static int mopWeightOpenMenu = Config.getInteger("ape.mopWeightOpenMenu", 250);
    // gh13: type-aware input fuzzing (T1.3). false ⇒ legacy random-string generator (rollback knob).
    public static boolean fuzzInputTyped = Config.getBoolean("ape.fuzzInputTyped", true);
    // gh13: package/mainActivity sanity check (T1.7). true ⇒ mismatch rejects MopData.load (CI gate).
    public static boolean mopStrictPackageMatch = Config.getBoolean("ape.mopStrictPackageMatch", false);
    // gh13: activity component triggering (T1.4). Default OFF — gh11 sandwichroulette -45pp evidence.
    public static boolean activityTriggerEnabled = Config.getBoolean("ape.activityTriggerEnabled", false);
    // mop-target-revisit-cap: max deterministic MOP short-circuit picks per
    // (widget XPath, action type, activity) key per run. <= 0 = unlimited (restores the
    // uncapped mop-discriminative-boost behavior). The boost still participates in the
    // priority roulette once the deterministic override is exhausted (INV-SEL-MOP-04/05).
    public static final int mopTargetPickCap = Config.getInteger("ape.mopTargetPickCap", 3);

    // mop-fairtest: back-menu-pick-cap — bounds discretionary MODEL_BACK/MODEL_MENU picks per
    // (activity, type) across NamingFactory-minted sibling states. <= 0 = unlimited (restores the
    // pre-cap behavior). Navigation-essential BACK sites (backToActivity/backtrack/handleNullAction)
    // are never capped; only the discretionary EARLY_STAGE + epsilon-greedy picks (INV-SEL-NAV-01..05).
    public static final int backMenuPickCap = Config.getInteger("ape.backMenuPickCap", 3);

    // LLM integration — configurable via ape.properties.
    // llmUrl=null disables LLM calls entirely (safe default when no LLM server is present).
    public static final String llmUrl = Config.get("ape.llmUrl");
    public static final boolean llmOnNewState = Config.getBoolean("ape.llmOnNewState", true);
    public static final boolean llmOnStagnation = Config.getBoolean("ape.llmOnStagnation", true);
    public static final String llmModel = Config.get("ape.llmModel", "default");
    public static final double llmTemperature = Config.getDouble("ape.llmTemperature", 0.3);
    public static final double llmTopP = Config.getDouble("ape.llmTopP", 0.6);
    public static final int llmTopK = Config.getInteger("ape.llmTopK", 50);
    public static final int llmTimeoutMs = Config.getInteger("ape.llmTimeoutMs", 15000);
    // Clamped to [0,1]: a value > 1 would make shouldRouteRandom() always fire
    // (random.nextDouble() < 1.5 is always true); a negative value is meaningless.
    public static final double llmPercentage =
            Math.max(0.0, Math.min(1.0, Config.getDouble("ape.llmPercentage", 0.02)));
    public static final String llmPromptVariant = Config.get("ape.llmPromptVariant", "ape_current");

    // gh9-exploration-refactor: UI coverage, activity budget, WTG, dynamic epsilon, heuristic input.
    public static final int coverageBoostWeight = Config.getInteger("ape.coverageBoostWeight", 100);
    // gh15 A-4: max live per-state entries in UICoverageTracker before eviction.
    // Evicted entries are folded into the per-Activity rollup, so coverage is not
    // lost — this only bounds memory (INV-COV-05).
    public static final int coverageMaxStates = Config.getInteger("ape.coverageMaxStates", 2000);
    public static final int activityBaseBudget = Config.getInteger("ape.activityBaseBudget", 50);
    public static final int activityBudgetPerWidget = Config.getInteger("ape.activityBudgetPerWidget", 5);
    public static final int mopWeightWtg = Config.getInteger("ape.mopWeightWtg", 200);
    public static final boolean dynamicEpsilon = Config.getBoolean("ape.dynamicEpsilon", true);
    public static final double maxEpsilon = Config.getDouble("ape.maxEpsilon", 0.15);
    public static final double minEpsilon = Config.getDouble("ape.minEpsilon", 0.02);
    public static final boolean heuristicInput = Config.getBoolean("ape.heuristicInput", true);

    // gh11: component triggering (broadcasts, services, activities, content providers)
    // Probability per step of triggering a component (0.0 = disabled, 0.05 = 5%).
    // Default 0.0 (disabled) regardless of mopDataPath: triggering is enabled only by an
    // explicit ape.componentPercentage, keeping the MOP scorer and component triggering
    // independent experiment variables (INV-CT-01).
    public static final double componentPercentage = Config.getDouble("ape.componentPercentage", 0.0);

    // mop-fairtest: foreign-activity-guard — deflect screens whose top activity is a
    // foreign package (launcher/installer leak) with one raw BACK instead of modeling
    // them. Default true (guard active); false restores the pre-guard modeling path.
    public static final boolean foreignActivityGuard = Config.getBoolean("ape.foreignActivityGuard", true);

    // mop-fairtest: tree-package-guard — when topComp is in-package but the accessibility
    // tree is owned by another package (relaunch race: AM reports MainActivity while the
    // launcher tree is still painted), refetch within the existing loop instead of modeling
    // the mismatched pair; fail-open on exhaustion. Default true; false restores old path.
    public static final boolean treePackageGuard = Config.getBoolean("ape.treePackageGuard", true);

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
