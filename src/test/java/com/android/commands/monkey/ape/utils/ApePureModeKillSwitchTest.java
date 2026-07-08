package com.android.commands.monkey.ape.utils;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * rv-scoring-pipeline task 5.4 — apePureMode kill-switch completeness guard (INV-ARCH-06).
 *
 * The apePureMode flags are {@code public static final} resolved once at class load, so the OFF
 * direction cannot be exercised in-JVM by re-reading the fields (the static-final wall). Instead these
 * tests exercise the forcing LOGIC directly ({@link Config#forceApePureModeInto} on a throwaway
 * Properties) and the classification COMPLETENESS by reflecting over every {@code public static} field
 * of {@link Config}: each field must be classified as an RV flag (forced / unset / exempt) or an
 * upstream-APE flag. An unclassified field fails the test, forcing every newly added flag to be
 * deliberately placed in the kill-switch registry or the upstream allowlist (INV-ARCH-06).
 */
public class ApePureModeKillSwitchTest {

    /**
     * The upstream-APE (@8f51b99) public-static value fields — every Config field that predates the fork
     * and is not an RV behavior knob. Derived from the fork/upstream Config diff. {@code
     * activityStableRestartThreshold} is deliberately NOT here: it is upstream-named but the fork changed
     * its default, so apePureMode forces it (rvForcedOffValues) and it is classified there.
     */
    private static final Set<String> UPSTREAM_ALLOWLIST = new HashSet<>(Arrays.asList(
            "actionRefinementFirst", "actionRefinmentThreshold", "activityManagerType",
            "alwaysIgnoreWebView", "alwaysIgnoreWebViewAction", "baseThrottle", "checkRestart",
            "computeImageText", "defaultEpsilon", "defaultGUIThrottle", "doBackToTrivialActivity",
            "doFuzzing", "enableReplacingNamelet", "enableXPathAction", "evolveModel",
            "excludeEmptyChild", "excludeInvisibleNode", "fallbackToGraphTransition", "fillTransitionsByHistory",
            "flushImagesThreshold", "fuzzingActivityVisitThreshold", "fuzzingRate", "graphStableRestartThreshold",
            "ignoreEmpty", "ignoreOutOfBounds", "ignoreWebViewThreshold", "imageWriterCount",
            "inputRate", "maxExtraPriorityAliasedActions", "maxGUITreesPerState", "maxInitialNamesPerStateThreshold",
            "maxStatesPerActivity", "maxStringListSize", "maxStringPieceLength", "maxThrottle",
            "patchGUITree", "randomFormattedStringProp", "refectchInfoCount", "refectchInfoWaitingInterval",
            "restartThresholdMax", "restartThresholdMin", "saveDotGraph", "saveGUITreeToXmlEveryStep",
            "saveObjModel", "saveStates", "saveVisGraph", "stateStableRestartThreshold",
            "swipeDuration", "takeScreenshot", "takeScreenshotForEveryStep", "takeScreenshotForNewState",
            "throttleForActivityTransition", "throttleForUnvisitedAction", "trivialActivityRankThreshold",
            "trivialActivityStateThreshold", "trivialActivityVisitThreshold", "trivialStateActionThreshold",
            "trivialStateWidgetThreshold", "truncateTextLength", "useActionDiffer", "useAncestorNamer",
            "usePatchNamer"));

    // ---------------------------------------------------------------------------
    // 5.3 — forcing overwrites every registered RV flag to its off/inert value
    // ---------------------------------------------------------------------------
    @Test
    public void forceApePureMode_overwritesEveryForcedFlagAndClearsUnsetKeys() {
        Properties p = new Properties();
        // seed non-default RV values to prove forcing overwrites rather than only filling gaps
        p.setProperty("ape.formCompletionEnabled", "true");
        p.setProperty("ape.mopWeightDirect", "500");
        p.setProperty("ape.activityStableRestartThreshold", "200");
        p.setProperty("ape.mopDataPath", "/data/local/tmp/foo.json");
        p.setProperty("ape.llmUrl", "http://10.0.2.2:30000/v1");

        Config.forceApePureModeInto(p);

        for (Map.Entry<String, String> e : Config.rvForcedOffValues().entrySet()) {
            assertEquals("forced " + e.getKey(), e.getValue(), p.getProperty(e.getKey()));
        }
        for (String k : Config.rvUnsetKeys()) {
            assertNull("unset " + k, p.getProperty(k));
        }
    }

    // ---------------------------------------------------------------------------
    // 5.4a — off-values have the right shape: booleans->false, weights/caps->0,
    //         the RV-activated threshold->Integer.MAX_VALUE
    // ---------------------------------------------------------------------------
    @Test
    public void forcedOffValues_haveTheExpectedShape() {
        Map<String, String> forced = Config.rvForcedOffValues();

        for (String k : Arrays.asList("ape.formCompletionEnabled", "ape.stepTelemetryEnabled",
                "ape.modelMenuEnabled", "ape.leastVisitedPriorityTiebreak", "ape.treeEnhancementsEnabled",
                "ape.activityBudgetEnabled", "ape.dynamicEpsilon", "ape.heuristicInput", "ape.fuzzInputTyped",
                "ape.foreignActivityGuard", "ape.treePackageGuard", "ape.activityTriggerEnabled",
                "ape.triggerMopFirst",
                "ape.llmOnNewState", "ape.llmOnStagnation")) {
            assertEquals(k + " boolean off value", "false", forced.get(k));
        }
        for (String k : Arrays.asList("ape.coverageBoostWeight", "ape.frontierBoostWeight",
                "ape.mopFrontierWeight",
                "ape.mopWeightDirect", "ape.mopWeightTransitive", "ape.mopWeightOpenMenu", "ape.mopWeightWtg",
                "ape.componentPercentage", "ape.llmPercentage", "ape.backMenuPickCap", "ape.mopTargetPickCap")) {
            assertEquals(k + " weight/cap off value", "0", forced.get(k));
        }
        assertEquals("activityStableRestartThreshold -> upstream-inert MAX_VALUE",
                Integer.toString(Integer.MAX_VALUE), forced.get("ape.activityStableRestartThreshold"));
    }

    // ---------------------------------------------------------------------------
    // 5.4b — completeness: every public-static Config field is classified as RV
    //         (forced/unset/exempt) or upstream. An unclassified field fails.
    // ---------------------------------------------------------------------------
    @Test
    public void everyConfigFieldIsClassified() throws Exception {
        Set<String> forcedKeys = Config.rvForcedOffValues().keySet();       // "ape.<name>"
        Set<String> unsetKeys = new HashSet<>(Config.rvUnsetKeys());        // "ape.<name>"
        Set<String> exemptNames = Config.rvExemptReasons().keySet();        // field names

        List<String> unclassified = new ArrayList<>();
        for (Field f : Config.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (!Modifier.isPublic(mod) || !Modifier.isStatic(mod)) {
                continue; // only the public-static config flags; the private Properties store is skipped
            }
            String name = f.getName();
            String key = "ape." + name;
            boolean classified = forcedKeys.contains(key)
                    || unsetKeys.contains(key)
                    || exemptNames.contains(name)
                    || UPSTREAM_ALLOWLIST.contains(name);
            if (!classified) {
                unclassified.add(name);
            }
        }
        assertTrue("Unclassified Config field(s) — add each to the RV kill-switch registry "
                + "(rvForcedOffValues/rvUnsetKeys/rvExemptReasons) or the upstream allowlist (INV-ARCH-06): "
                + unclassified, unclassified.isEmpty());
    }

    // ---------------------------------------------------------------------------
    // 5.4b — the RV classification and the upstream allowlist are disjoint: no flag
    //         is both an RV knob and an upstream flag (guards against mis-classification)
    // ---------------------------------------------------------------------------
    @Test
    public void rvClassificationAndUpstreamAllowlistAreDisjoint() {
        Set<String> rvNames = new HashSet<>();
        for (String k : Config.rvForcedOffValues().keySet()) {
            rvNames.add(k.substring("ape.".length()));
        }
        for (String k : Config.rvUnsetKeys()) {
            rvNames.add(k.substring("ape.".length()));
        }
        rvNames.addAll(Config.rvExemptReasons().keySet());

        Set<String> overlap = new HashSet<>(rvNames);
        overlap.retainAll(UPSTREAM_ALLOWLIST);
        assertTrue("Field(s) classified as both RV and upstream: " + overlap, overlap.isEmpty());
    }
}
