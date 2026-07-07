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
}
