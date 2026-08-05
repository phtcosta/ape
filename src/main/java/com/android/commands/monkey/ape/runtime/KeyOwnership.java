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
package com.android.commands.monkey.ape.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which part of the plan owns each {@code ape.*} key, with the key's declared type and jar default.
 *
 * <p>Ownership is <b>total and exclusive</b>: every {@code ape.*} key the jar reads is owned by
 * exactly one of the base exploration parameters, one {@link Feature}, or the resolver itself. A
 * key that is owned by nobody is not a permissive default any more — it is an unknown key, and it
 * aborts the run. {@code KeyOwnershipTotalityTest} scans the source tree for {@code "ape.*"}
 * literals and fails when one of them is missing from this table, which is what keeps the claim
 * true as the tree changes rather than as of the day it was written.
 *
 * <p>The table also carries every key's jar default, and a second test asserts each one against
 * the corresponding {@code Config} static field by reflection. The two files must agree; the test
 * is what makes that an enforced property instead of a convention.
 *
 * <p><b>Retired keys</b> are the ones whose mechanism this re-architecture deletes. They are not
 * merely unknown: each carries a reason naming what replaced it, so an operator whose properties
 * file still sets one reads a decision rather than a typo report. {@code ape.agentType} and
 * {@code ape.replayLog} are retired <em>as file keys only</em> — they remain valid on the command
 * line, and the whole point of retiring the file form is that a stray {@code /sdcard/ape.properties}
 * used to be able to swap the agent of a scientific run.
 */
public final class KeyOwnership {

    /** Where a key's value lands in the resolved plan. */
    public enum OwnerKind {
        /** {@code ExplorationParams} — always present, no feature gates it. */
        BASE,
        /** A {@link Feature}: its activation key or one of its sub-parameters. */
        FEATURE,
        /** The resolver itself: plan selection and run provenance, not behavior. */
        RESOLVER
    }

    /** One key's declaration: who owns it, how it parses, and what the jar does without it. */
    public static final class KeySpec {
        private final String key;
        private final OwnerKind ownerKind;
        private final Feature feature;
        private final ValueType type;
        private final String defaultValue;

        private KeySpec(String key, OwnerKind ownerKind, Feature feature, ValueType type,
                String defaultValue) {
            this.key = key;
            this.ownerKind = ownerKind;
            this.feature = feature;
            this.type = type;
            this.defaultValue = defaultValue;
        }

        public String key() {
            return key;
        }

        public OwnerKind ownerKind() {
            return ownerKind;
        }

        /** The owning feature, or null when the owner is the base params or the resolver. */
        public Feature feature() {
            return feature;
        }

        public ValueType type() {
            return type;
        }

        /** The jar default in property-text form, or null when the key has no default. */
        public String defaultValue() {
            return defaultValue;
        }

        @Override
        public String toString() {
            return key + " [" + ownerKind + (feature == null ? "" : ":" + feature) + "]";
        }
    }

    private static final Map<String, KeySpec> SPECS = new LinkedHashMap<>();
    private static final Map<String, String> RETIRED = new LinkedHashMap<>();

    /** {@code ape.preset}: selects a jar-resident preset; absent means an explicit plan. */
    public static final String KEY_PRESET = "ape.preset";
    /** {@code ape.runId}: host-supplied run identity; absent means the jar generates one. */
    public static final String KEY_RUN_ID = "ape.runId";
    /**
     * {@code ape.corpusBasis}: the identifier and hash of the application list this run was drawn
     * from. Echoed and read by nothing. It exists because this study has counted its analysis
     * basis as 163, 181 and 219 applications in different documents, and one hash per run ends
     * the re-derivation.
     */
    public static final String KEY_CORPUS_BASIS = "ape.corpusBasis";

    static {
        // --- Base exploration parameters: always present, no feature gates them. --------------
        // Telemetry is always on and owned by no feature (event-sink INV-SNK-07), so its two
        // levers are base keys: they alter what the trace carries, never what the run decides.
        base("ape.llmPromptDump", ValueType.BOOLEAN, "true");
        base("ape.telemetryHeartbeat", ValueType.BOOLEAN, "true");

        base("ape.actionRefinementFirst", ValueType.BOOLEAN, "true");
        base("ape.actionRefinmentThreshold", ValueType.INT, "3");
        base("ape.activityManagerType", ValueType.STRING, "state");
        base("ape.activityStableRestartThreshold", ValueType.INT, "200");
        base("ape.alwaysIgnoreWebView", ValueType.BOOLEAN, "false");
        base("ape.alwaysIgnoreWebViewAction", ValueType.BOOLEAN, "false");
        base("ape.backMenuPickCap", ValueType.INT, "3");
        // Read at NamingFactory:953 with the caller's current naming as the fallback, so the jar
        // has no single default for it — hence a null default rather than an invented one.
        base("ape.baseNaming", ValueType.STRING, null);
        base("ape.baseThrottle", ValueType.INT, "0");
        base("ape.checkRestart", ValueType.BOOLEAN, "true");
        base("ape.computeImageText", ValueType.BOOLEAN, "true");
        base("ape.defaultEpsilon", ValueType.DOUBLE, "0.05");
        base("ape.defaultGUIThrottle", ValueType.LONG, "200");
        base("ape.doBackToTrivialActivity", ValueType.BOOLEAN, "false");
        base("ape.enableReplacingNamelet", ValueType.BOOLEAN, "false");
        base("ape.evolveModel", ValueType.BOOLEAN, "true");
        base("ape.excludeEmptyChild", ValueType.BOOLEAN, "true");
        base("ape.excludeInvisibleNode", ValueType.BOOLEAN, "true");
        base("ape.fallbackToGraphTransition", ValueType.BOOLEAN, "true");
        base("ape.fillTransitionsByHistory", ValueType.BOOLEAN, "true");
        base("ape.flushImagesThreshold", ValueType.INT, "10");
        base("ape.graphStableRestartThreshold", ValueType.INT, "100");
        base("ape.ignoreEmpty", ValueType.BOOLEAN, "true");
        base("ape.ignoreOutOfBounds", ValueType.BOOLEAN, "true");
        base("ape.ignoreWebViewThreshold", ValueType.INT, "64");
        base("ape.imageWriterCount", ValueType.INT, "3");
        base("ape.inputRate", ValueType.DOUBLE, "0.8");
        base("ape.maxExtraPriorityAliasedActions", ValueType.INT, "5");
        base("ape.maxGUITreesPerState", ValueType.INT, "20");
        base("ape.maxIdleTimeoutMs", ValueType.LONG, "10000");
        base("ape.maxInitialNamesPerStateThreshold", ValueType.INT, "20");
        base("ape.maxStatesPerActivity", ValueType.INT, "10");
        base("ape.maxStringListSize", ValueType.INT, "2000");
        base("ape.maxStringPieceLength", ValueType.INT, "32");
        base("ape.maxThrottle", ValueType.INT, "5000");
        // Read at Action.java:48 to throttle the NOP action; declared in no artifact before this
        // table, and found by the totality scan rather than by the design.
        base("ape.nopActionThrottle", ValueType.INT, "1000");
        base("ape.patchGUITree", ValueType.BOOLEAN, "true");
        base("ape.randomFormattedStringProp", ValueType.DOUBLE, "0.5");
        base("ape.refectchInfoCount", ValueType.INT, "4");
        base("ape.refectchInfoWaitingInterval", ValueType.LONG, "50");
        base("ape.restartThresholdMax", ValueType.INT, "300");
        base("ape.restartThresholdMin", ValueType.INT, "100");
        base("ape.saveGUITreeToXmlEveryStep", ValueType.BOOLEAN, "false");
        base("ape.stateStableRestartThreshold", ValueType.INT, "50");
        base("ape.swipeDuration", ValueType.LONG, "200");
        base("ape.takeScreenshot", ValueType.BOOLEAN, "true");
        base("ape.takeScreenshotForEveryStep", ValueType.BOOLEAN, "false");
        base("ape.takeScreenshotForNewState", ValueType.BOOLEAN, "false");
        base("ape.throttleForActivityTransition", ValueType.INT, "500");
        base("ape.throttleForUnvisitedAction", ValueType.INT, "200");
        base("ape.trivialActivityRankThreshold", ValueType.INT, "3");
        base("ape.trivialActivityStateThreshold", ValueType.INT, "5");
        base("ape.trivialActivityVisitThreshold", ValueType.INT, "16");
        base("ape.trivialStateActionThreshold", ValueType.INT, "5");
        base("ape.trivialStateWidgetThreshold", ValueType.INT, "5");
        base("ape.truncateTextLength", ValueType.INT, "8");
        base("ape.useActionDiffer", ValueType.BOOLEAN, "true");
        base("ape.useAncestorNamer", ValueType.BOOLEAN, "true");
        base("ape.usePatchNamer", ValueType.BOOLEAN, "true");

        // --- Feature-owned keys. ---------------------------------------------------------------
        feature(Feature.MOP, "ape.mopDataPath", ValueType.STRING, null);
        feature(Feature.MOP, "ape.mopWeightDirect", ValueType.INT, "500");
        feature(Feature.MOP, "ape.mopWeightTransitive", ValueType.INT, "300");
        feature(Feature.MOP, "ape.mopTargetPickCap", ValueType.INT, "3");
        feature(Feature.MOP, "ape.mopStrictPackageMatch", ValueType.BOOLEAN, "false");

        feature(Feature.WTG, "ape.mopWeightWtg", ValueType.INT, "200");
        feature(Feature.MENU_GATEWAY, "ape.mopWeightOpenMenu", ValueType.INT, "250");
        feature(Feature.FRONTIER, "ape.frontierBoostWeight", ValueType.INT, "200");
        feature(Feature.MOP_FRONTIER, "ape.mopFrontierWeight", ValueType.INT, "0");

        feature(Feature.ACTIVITY_TRIGGER, "ape.activityTriggerEnabled", ValueType.BOOLEAN, "true");
        feature(Feature.ACTIVITY_TRIGGER, "ape.activityTriggerStagnationStep", ValueType.INT, "50");
        feature(Feature.ACTIVITY_TRIGGER, "ape.activityTriggerMaxPerRun", ValueType.INT, "0");

        feature(Feature.COMPONENT_TRIGGER, "ape.componentPercentage", ValueType.DOUBLE, "0.0");
        feature(Feature.MOP_ACTIVITY_SOURCE, "ape.mopActivitySourceComponents", ValueType.BOOLEAN, "false");

        feature(Feature.LLM, "ape.llmUrl", ValueType.STRING, null);
        feature(Feature.LLM, "ape.llmModel", ValueType.STRING, "default");
        feature(Feature.LLM, "ape.llmTemperature", ValueType.DOUBLE, "0.3");
        feature(Feature.LLM, "ape.llmTopP", ValueType.DOUBLE, "0.6");
        feature(Feature.LLM, "ape.llmTopK", ValueType.INT, "50");
        feature(Feature.LLM, "ape.llmTimeoutMs", ValueType.INT, "15000");
        feature(Feature.LLM, "ape.llmPromptVariant", ValueType.STRING, "ape_current");
        feature(Feature.LLM, "ape.llmMaxTokens", ValueType.INT, "1024");
        feature(Feature.LLM, "ape.llmSnapTolerancePx", ValueType.INT, "50");
        feature(Feature.LLM, "ape.llmBoundaryTopPct", ValueType.DOUBLE, "0.05");
        feature(Feature.LLM, "ape.llmBoundaryBottomPct", ValueType.DOUBLE, "0.94");

        feature(Feature.LLM_NEW_STATE, "ape.llmOnNewState", ValueType.BOOLEAN, "true");
        feature(Feature.LLM_STAGNATION, "ape.llmOnStagnation", ValueType.BOOLEAN, "true");
        feature(Feature.LLM_RANDOM, "ape.llmPercentage", ValueType.DOUBLE, "0.02");
        feature(Feature.LLM_RANDOM, "ape.llmPercentageNoSubstrate", ValueType.DOUBLE, "-1.0");

        feature(Feature.MODEL_MENU, "ape.modelMenuEnabled", ValueType.BOOLEAN, "true");
        feature(Feature.FORM_COMPLETION, "ape.formCompletionEnabled", ValueType.BOOLEAN, "true");
        feature(Feature.LEAST_VISITED_TIEBREAK, "ape.leastVisitedPriorityTiebreak", ValueType.BOOLEAN, "true");
        feature(Feature.TREE_ENHANCEMENTS, "ape.treeEnhancementsEnabled", ValueType.BOOLEAN, "true");

        feature(Feature.ACTIVITY_BUDGET, "ape.activityBudgetEnabled", ValueType.BOOLEAN, "true");
        feature(Feature.ACTIVITY_BUDGET, "ape.activityBaseBudget", ValueType.INT, "50");
        feature(Feature.ACTIVITY_BUDGET, "ape.activityBudgetPerWidget", ValueType.INT, "5");

        feature(Feature.DYNAMIC_EPSILON, "ape.dynamicEpsilon", ValueType.BOOLEAN, "true");
        feature(Feature.DYNAMIC_EPSILON, "ape.maxEpsilon", ValueType.DOUBLE, "0.15");
        feature(Feature.DYNAMIC_EPSILON, "ape.minEpsilon", ValueType.DOUBLE, "0.02");

        feature(Feature.HEURISTIC_INPUT, "ape.heuristicInput", ValueType.BOOLEAN, "true");
        feature(Feature.TYPED_FUZZ, "ape.fuzzInputTyped", ValueType.BOOLEAN, "true");
        feature(Feature.FOREIGN_ACTIVITY_GUARD, "ape.foreignActivityGuard", ValueType.BOOLEAN, "true");
        feature(Feature.TREE_PACKAGE_GUARD, "ape.treePackageGuard", ValueType.BOOLEAN, "true");

        feature(Feature.COVERAGE_BOOST, "ape.coverageBoostWeight", ValueType.INT, "100");
        feature(Feature.COVERAGE_BOOST, "ape.coverageMaxStates", ValueType.INT, "2000");

        feature(Feature.FUZZING, "ape.doFuzzing", ValueType.BOOLEAN, "true");
        feature(Feature.FUZZING, "ape.fuzzingRate", ValueType.DOUBLE, "0.02");
        feature(Feature.FUZZING, "ape.fuzzingActivityVisitThreshold", ValueType.INT, "10");

        // --- Resolver-owned: plan selection and provenance, never behavior. --------------------
        resolver(KEY_PRESET);
        resolver(KEY_RUN_ID);
        resolver(KEY_CORPUS_BASIS);

        // --- Retired: the mechanism is gone, and the message says what replaced it. ------------
        RETIRED.put("ape.apePureMode",
                "purity is structural: a feature absent from the plan does not exist, so there is "
                        + "no kill-switch to set (owner decision D3). Express an APE-baseline arm "
                        + "by stating each RV flag at its off value.");
        RETIRED.put("ape.modelFile",
                "the model persistence protocol is removed; there is no resume path. Retry after "
                        + "a failed run belongs to the harness, not to the explorer.");
        RETIRED.put("ape.saveObjModel",
                "the model serializer is removed; sataModel.obj is not produced.");
        RETIRED.put("ape.saveDotGraph",
                "the graph writers are removed; sataGraph.dot is not produced.");
        RETIRED.put("ape.saveVisGraph",
                "the graph writers are removed; sataGraph.vis.js is not produced.");
        RETIRED.put("ape.saveStates",
                "the per-state dump was written only from inside the deleted saveGraph step; "
                        + "step-<timestamp>-<id>.txt is not produced. State detail belongs to the "
                        + "stage-4 trace, not to a /sdcard file nothing reads back.");
        RETIRED.put("ape.enableXPathAction",
                "the /sdcard XPath action-injection channel is removed (owner decision D6); the "
                        + "jar reads no behavioral input from /sdcard other than ape.properties.");
        RETIRED.put("ape.mopWeightActivity",
                "dead since mop-fairtest: the weight it named was deleted from the scorer. Use "
                        + "ape.mopWeightOpenMenu, or the frontier weights, to steer toward "
                        + "activities.");
        RETIRED.put("ape.agentType",
                "the agent type is a command-line value (--ape sata|random|replay). Accepting it "
                        + "from a file let a stray /sdcard/ape.properties change the agent of a "
                        + "scientific run without a trace.");
        RETIRED.put("ape.replayLog",
                "the replay log is a command-line value (--ape-replay <log>), for the same reason "
                        + "as ape.agentType.");
    }

    private KeyOwnership() {
    }

    private static void base(String key, ValueType type, String defaultValue) {
        put(new KeySpec(key, OwnerKind.BASE, null, type, defaultValue));
    }

    private static void feature(Feature owner, String key, ValueType type, String defaultValue) {
        put(new KeySpec(key, OwnerKind.FEATURE, owner, type, defaultValue));
    }

    private static void resolver(String key) {
        put(new KeySpec(key, OwnerKind.RESOLVER, null, ValueType.STRING, null));
    }

    private static void put(KeySpec spec) {
        KeySpec previous = SPECS.put(spec.key(), spec);
        if (previous != null) {
            throw new IllegalStateException("key declared twice: " + spec.key());
        }
    }

    /** True when the key has an owner — i.e. when the plan can express it. */
    public static boolean isKnown(String key) {
        return SPECS.containsKey(key);
    }

    public static KeySpec spec(String key) {
        return SPECS.get(key);
    }

    /** The declared type, or null when the key is unknown. */
    public static ValueType typeOf(String key) {
        KeySpec spec = SPECS.get(key);
        return spec == null ? null : spec.type();
    }

    /** The jar default in property-text form, or null when there is none. */
    public static String defaultOf(String key) {
        KeySpec spec = SPECS.get(key);
        return spec == null ? null : spec.defaultValue();
    }

    /** Every owned key, in declaration order. */
    public static Set<String> allKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(SPECS.keySet()));
    }

    public static Set<String> keysOf(OwnerKind kind) {
        Set<String> out = new LinkedHashSet<>();
        for (KeySpec spec : SPECS.values()) {
            if (spec.ownerKind() == kind) {
                out.add(spec.key());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** True when the key names a mechanism this re-architecture deleted. */
    public static boolean isRetired(String key) {
        return RETIRED.containsKey(key);
    }

    /** Why the key is retired and what replaced it, or null when it is not retired. */
    public static String retiredReason(String key) {
        return RETIRED.get(key);
    }

    public static Set<String> retiredKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(RETIRED.keySet()));
    }
}
