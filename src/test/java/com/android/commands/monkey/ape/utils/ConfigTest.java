package com.android.commands.monkey.ape.utils;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for Config flag declarations.
 *
 * Verifies that default values are correct for all gh9-exploration-refactor flags
 * and the changed activityStableRestartThreshold default.
 *
 * Tests run on the JVM only; no Android device required.
 */
public class ConfigTest {

    // ---------------------------------------------------------------------------
    // gh9: activityStableRestartThreshold default changed from MAX_VALUE to 200
    // ---------------------------------------------------------------------------
    @Test
    public void testActivityStableRestartThreshold_defaultIs200() {
        assertEquals(200, Config.activityStableRestartThreshold);
    }

    // ---------------------------------------------------------------------------
    // gh9: coverageBoostWeight
    // ---------------------------------------------------------------------------
    @Test
    public void testCoverageBoostWeight_defaultIs100() {
        assertEquals(100, Config.coverageBoostWeight);
    }

    // ---------------------------------------------------------------------------
    // gh9: activityBaseBudget
    // ---------------------------------------------------------------------------
    @Test
    public void testActivityBaseBudget_defaultIs50() {
        assertEquals(50, Config.activityBaseBudget);
    }

    // ---------------------------------------------------------------------------
    // gh9: activityBudgetPerWidget
    // ---------------------------------------------------------------------------
    @Test
    public void testActivityBudgetPerWidget_defaultIs5() {
        assertEquals(5, Config.activityBudgetPerWidget);
    }

    // ---------------------------------------------------------------------------
    // gh9: mopWeightWtg
    // ---------------------------------------------------------------------------
    @Test
    public void testMopWeightWtg_defaultIs200() {
        assertEquals(200, Config.mopWeightWtg);
    }

    // ---------------------------------------------------------------------------
    // gh9: dynamicEpsilon
    // ---------------------------------------------------------------------------
    @Test
    public void testDynamicEpsilon_defaultIsTrue() {
        assertTrue(Config.dynamicEpsilon);
    }

    // ---------------------------------------------------------------------------
    // gh9: maxEpsilon
    // ---------------------------------------------------------------------------
    @Test
    public void testMaxEpsilon_defaultIs015() {
        assertEquals(0.15, Config.maxEpsilon, 1e-9);
    }

    // ---------------------------------------------------------------------------
    // gh9: minEpsilon
    // ---------------------------------------------------------------------------
    @Test
    public void testMinEpsilon_defaultIs002() {
        assertEquals(0.02, Config.minEpsilon, 1e-9);
    }

    // ---------------------------------------------------------------------------
    // gh9: heuristicInput
    // ---------------------------------------------------------------------------
    @Test
    public void testHeuristicInput_defaultIsTrue() {
        assertTrue(Config.heuristicInput);
    }

    // ---------------------------------------------------------------------------
    // gh15 A-3: componentPercentage default decoupled from mopDataPath (INV-CT-01).
    // The default is a literal 0.0 (triggering disabled) regardless of mopDataPath;
    // triggering is enabled only by an explicit ape.componentPercentage. The
    // "default 0.0 even when mopDataPath is set" path is not unit-testable here
    // (the field is static final, captured at class load with mopDataPath null in
    // the JVM env) — it is asserted by the on-device gate: a sata_mop run emits no
    // "[APE-RV] Triggering" without an explicit ape.componentPercentage.
    // ---------------------------------------------------------------------------
    @Test
    public void testComponentPercentage_defaultIsZero() {
        assertEquals(0.0, Config.componentPercentage, 1e-9);
    }

    @Test
    public void testComponentPercentage_explicitOverrideHonoured() {
        String prev = Config.get("ape.componentPercentage");
        try {
            Config.set("ape.componentPercentage", "0.10");
            assertEquals(0.10, Config.getDouble("ape.componentPercentage", 0.0), 1e-9);
        } finally {
            if (prev != null) {
                Config.set("ape.componentPercentage", prev);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // gh15 A-6: llmPercentage clamped to [0,1] at load (INV-RTR-08).
    // Only the in-range default (0.02) is asserted on the field here: the field is
    // static final, captured once at class load, so the out-of-range cases
    // (1.5 -> 1.0, -0.2 -> 0.0) cannot be re-evaluated in the same JVM. The clamp
    // expression (Math.max(0.0, Math.min(1.0, ...))) guarantees those bounds.
    // ---------------------------------------------------------------------------
    @Test
    public void testLlmPercentage_defaultInRangeUnchanged() {
        assertEquals(0.02, Config.llmPercentage, 1e-9);
    }

    // ---------------------------------------------------------------------------
    // exploration-effectiveness 1.1: per-step debug artifacts now default false
    // for throughput (INV-EXPL-17). Both flags are `static final`, captured at
    // class load, so the override direction (an ape.properties key flipping them
    // back to true) cannot be re-evaluated in the same JVM — override is
    // config-driven via Config.getBoolean at load time (same limitation noted for
    // the gh15 static-final flags above). We assert the shipped defaults here.
    // ---------------------------------------------------------------------------
    @Test
    public void testTakeScreenshotForEveryStep_defaultIsFalse() {
        assertFalse(Config.takeScreenshotForEveryStep);
    }

    @Test
    public void testSaveGUITreeToXmlEveryStep_defaultIsFalse() {
        assertFalse(Config.saveGUITreeToXmlEveryStep);
    }

    // ---------------------------------------------------------------------------
    // idle-timeout-cap 1.1: ape.maxIdleTimeoutMs parametrizes the getRootInActive
    // WindowSlow idle-wait ceiling and (÷1000) the refreshNewState break threshold.
    // The default 10000 is byte-identical to the former `1000 * 10` literal, and
    // the derived break threshold 10000/1000 == 10 matches the former `>= 10`
    // literal (INV-EXPL-23, INV-EXPL-25).
    // ---------------------------------------------------------------------------
    @Test
    public void testMaxIdleTimeoutMs_defaultIs10000() {
        assertEquals(10000L, Config.maxIdleTimeoutMs);
    }

    @Test
    public void testMaxIdleTimeoutMs_derivedBreakSecondsIs10() {
        assertEquals(10L, Config.maxIdleTimeoutMs / 1000);
    }

    // ---------------------------------------------------------------------------
    // mop-reach-strategies 1.2: mopFrontierWeight — MOP-conditioned twin of
    // frontierBoostWeight, gates MopFrontierPass. Default 0 = off (byte-identical
    // to pre-change; the widget arm does not use it). Static field captured at
    // class load, so only the default is asserted here.
    // ---------------------------------------------------------------------------
    @Test
    public void testMopFrontierWeight_defaultIsZero() {
        assertEquals(0, Config.mopFrontierWeight);
    }

    // ---------------------------------------------------------------------------
    // mop-reach-strategies 1.3: triggerMopFirst — E-mín MOP-first launch ordering
    // in selectTriggerCandidate. Default false = round-robin unchanged.
    // ---------------------------------------------------------------------------
    @Test
    public void testTriggerMopFirst_defaultIsFalse() {
        assertFalse(Config.triggerMopFirst);
    }

    // ---------------------------------------------------------------------------
    // mop-reach-strategies 1.4: llmPercentageNoSubstrate — F′ LLM-routing override
    // used when the substrate is widgetless. Default -1 sentinel = "no override"
    // (fall back to llmPercentage). Unlike llmPercentage, the -1 sentinel is exempt
    // from the [0,1] clamp; >=0 values are clamped like llmPercentage. The field is
    // static final (default asserted here); the clamp seam is tested below.
    // ---------------------------------------------------------------------------
    @Test
    public void testLlmPercentageNoSubstrate_defaultIsMinusOneSentinel() {
        assertEquals(-1.0, Config.llmPercentageNoSubstrate, 1e-9);
    }

    @Test
    public void testClampLlmPercentageNoSubstrate_sentinelStaysMinusOne() {
        assertEquals(-1.0, Config.clampLlmPercentageNoSubstrate(-1.0), 1e-9);
    }

    @Test
    public void testClampLlmPercentageNoSubstrate_anyNegativeCollapsesToSentinel() {
        // a real negative (not the -1 sentinel) is meaningless as a probability;
        // it collapses to the -1 "no override" sentinel (INV-RTR-09).
        assertEquals(-1.0, Config.clampLlmPercentageNoSubstrate(-0.2), 1e-9);
    }

    @Test
    public void testClampLlmPercentageNoSubstrate_aboveOneClampsToOne() {
        assertEquals(1.0, Config.clampLlmPercentageNoSubstrate(1.5), 1e-9);
    }

    @Test
    public void testClampLlmPercentageNoSubstrate_inRangeUnchanged() {
        assertEquals(0.5, Config.clampLlmPercentageNoSubstrate(0.5), 1e-9);
    }

    // ---------------------------------------------------------------------------
    // activity-trigger-dose 1.1: activityTriggerStagnationStep — configurable
    // firing cadence for the stagnation activity launcher. Default 50 is
    // byte-identical to the pre-change gate (graphStableRestartThreshold/2 with
    // the default threshold 100), so frozen arms are preserved (INV-CT-11). The
    // field is static final (default asserted here); the clamp seam is tested via
    // the extracted helper (a value <= 0 would fire every step at counter 0).
    // ---------------------------------------------------------------------------
    @Test
    public void testActivityTriggerStagnationStep_defaultIs50() {
        assertEquals(50, Config.activityTriggerStagnationStep);
    }

    @Test
    public void testClampActivityTriggerStagnationStep_inRangeUnchanged() {
        assertEquals(10, Config.clampActivityTriggerStagnationStep(10));
    }

    @Test
    public void testClampActivityTriggerStagnationStep_zeroClampsToDefault() {
        assertEquals(50, Config.clampActivityTriggerStagnationStep(0));
    }

    @Test
    public void testClampActivityTriggerStagnationStep_negativeClampsToDefault() {
        assertEquals(50, Config.clampActivityTriggerStagnationStep(-7));
    }

    // ---------------------------------------------------------------------------
    // activity-trigger-dose 1.1: activityTriggerMaxPerRun — per-run launch cap.
    // Default 0 = unlimited (INV-CT-12). A negative value is an operator typo and
    // clamps to 0 (unlimited) at load.
    // ---------------------------------------------------------------------------
    @Test
    public void testActivityTriggerMaxPerRun_defaultIsZero() {
        assertEquals(0, Config.activityTriggerMaxPerRun);
    }

    @Test
    public void testClampActivityTriggerMaxPerRun_positiveUnchanged() {
        assertEquals(8, Config.clampActivityTriggerMaxPerRun(8));
    }

    @Test
    public void testClampActivityTriggerMaxPerRun_zeroUnchanged() {
        assertEquals(0, Config.clampActivityTriggerMaxPerRun(0));
    }

    @Test
    public void testClampActivityTriggerMaxPerRun_negativeClampsToZero() {
        assertEquals(0, Config.clampActivityTriggerMaxPerRun(-3));
    }
}
