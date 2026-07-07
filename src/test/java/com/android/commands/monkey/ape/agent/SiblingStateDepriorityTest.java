package com.android.commands.monkey.ape.agent;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * sibling-state-depriority §3 — pure seams of the redundant-sibling penalty
 * ({@link StatefulAgent#shouldPenalizeSibling} decision and
 * {@link StatefulAgent#siblingPenalizedPriority} floored subtraction).
 *
 * The scoring pass in {@code adjustActionsByGUITree} needs the Android runtime (newState / Graph /
 * UICoverageTracker), so its wiring and the once-per-pass log are covered by the deferred device
 * smoke (task 4.3). Here we exercise the extracted decision/arithmetic (INV-COV-10/11/12).
 */
public class SiblingStateDepriorityTest {

    private static final int MAX = 10;   // maxStatesPerActivity
    private static final int PEN = 24;   // siblingStatePenalty

    // ---- threshold boundary (INV-COV-10) -------------------------------------

    @Test
    public void testNoPenaltyAtOrBelowThreshold() {
        // siblings == maxStates → not over-fragmented → no penalty
        assertFalse(StatefulAgent.shouldPenalizeSibling(PEN, MAX, MAX, true, 0, 0, true));
        assertFalse(StatefulAgent.shouldPenalizeSibling(PEN, MAX - 1, MAX, true, 0, 0, true));
    }

    @Test
    public void testPenaltyAboveThreshold() {
        assertTrue(StatefulAgent.shouldPenalizeSibling(PEN, MAX + 1, MAX, true, 0, 0, true));
    }

    // ---- exemption matrix (INV-COV-11) ---------------------------------------

    @Test
    public void testNovelWidgetExempt() {
        // interacted == false → activity-novel → exempt
        assertFalse(StatefulAgent.shouldPenalizeSibling(PEN, MAX + 5, MAX, true, 0, 0, false));
    }

    @Test
    public void testMopBoostExempt() {
        assertFalse(StatefulAgent.shouldPenalizeSibling(PEN, MAX + 5, MAX, true, 300, 0, true));
    }

    @Test
    public void testWtgBoostExempt() {
        // covers both the WTG-MOP boost and the activity-frontier boost (routed through wtgBoost)
        assertFalse(StatefulAgent.shouldPenalizeSibling(PEN, MAX + 5, MAX, true, 0, 200, true));
    }

    @Test
    public void testTargetlessExempt() {
        // BACK/MENU (requireTarget == false) → governed by back-menu-pick-cap, exempt here
        assertFalse(StatefulAgent.shouldPenalizeSibling(PEN, MAX + 5, MAX, false, 0, 0, true));
    }

    @Test
    public void testEligibleWhenRedundantAndUnsteered() {
        assertTrue(StatefulAgent.shouldPenalizeSibling(PEN, MAX + 5, MAX, true, 0, 0, true));
    }

    // ---- floor + disabled (INV-COV-12) ---------------------------------------

    @Test
    public void testFloorAtOne() {
        assertEquals(1, StatefulAgent.siblingPenalizedPriority(8, 24));   // 8 - 24 → floored to 1
        assertEquals(1, StatefulAgent.siblingPenalizedPriority(24, 24));  // exactly 0 → floored to 1
    }

    @Test
    public void testSubtractionAboveFloor() {
        assertEquals(76, StatefulAgent.siblingPenalizedPriority(100, 24));
    }

    @Test
    public void testDisabledPenaltyNeverPenalizes() {
        assertFalse(StatefulAgent.shouldPenalizeSibling(0, MAX + 5, MAX, true, 0, 0, true));
        assertFalse(StatefulAgent.shouldPenalizeSibling(-1, MAX + 5, MAX, true, 0, 0, true));
    }
}
