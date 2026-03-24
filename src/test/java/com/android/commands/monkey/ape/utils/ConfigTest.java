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
}
