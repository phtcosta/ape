package com.android.commands.monkey.ape.utils;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for ActivityBudgetTracker.
 *
 * Covers INV-BUD-01..03 and all scenarios from the activity-budget spec.
 * Tests run on the JVM only; no Android device required.
 */
public class ActivityBudgetTrackerTest {

    private static final String MAIN = "com.example.MainActivity";
    private static final String SETTINGS = "com.example.SettingsActivity";
    private static final String UNKNOWN = "com.example.UnknownActivity";

    // ---------------------------------------------------------------------------
    // INV-BUD-01: unregistered activity returns false for exhaustion
    // ---------------------------------------------------------------------------

    @Test
    public void testUnregisteredActivityNotExhausted() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        assertFalse(tracker.isBudgetExhausted(UNKNOWN));
    }

    @Test
    public void testUnregisteredActivityRemainingBudgetIsMaxValue() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        assertEquals(Integer.MAX_VALUE, tracker.getRemainingBudget(UNKNOWN));
    }

    // ---------------------------------------------------------------------------
    // INV-BUD-02: budget computed once, not recalculated on re-registration
    // ---------------------------------------------------------------------------

    @Test
    public void testRegisterActivityComputesBudget() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        tracker.registerActivity(MAIN, 15);
        // budget = 50 + 15*5 = 125
        assertEquals(125, tracker.getRemainingBudget(MAIN));
    }

    @Test
    public void testReRegisterIsIdempotent() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        tracker.registerActivity(MAIN, 15);
        // Re-register with different widget count — should be ignored
        tracker.registerActivity(MAIN, 20);
        // budget should remain 125 (50 + 15*5), not 150 (50 + 20*5)
        assertEquals(125, tracker.getRemainingBudget(MAIN));
    }

    // ---------------------------------------------------------------------------
    // Iteration counting
    // ---------------------------------------------------------------------------

    @Test
    public void testRecordIterationDecrementsRemaining() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        tracker.registerActivity(MAIN, 15); // budget = 125
        tracker.recordIteration(MAIN);
        assertEquals(124, tracker.getRemainingBudget(MAIN));
    }

    @Test
    public void testRecordIterationOnUnregisteredIsNoOp() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        // Should not throw
        tracker.recordIteration(UNKNOWN);
        assertEquals(Integer.MAX_VALUE, tracker.getRemainingBudget(UNKNOWN));
    }

    @Test
    public void testCountIterationsNotExhausted() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        tracker.registerActivity(MAIN, 15); // budget = 125
        for (int i = 0; i < 50; i++) {
            tracker.recordIteration(MAIN);
        }
        assertFalse(tracker.isBudgetExhausted(MAIN));
        assertEquals(75, tracker.getRemainingBudget(MAIN));
    }

    // ---------------------------------------------------------------------------
    // Budget exhaustion
    // ---------------------------------------------------------------------------

    @Test
    public void testBudgetExhaustedAtExactBoundary() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        tracker.registerActivity(MAIN, 15); // budget = 125
        for (int i = 0; i < 125; i++) {
            tracker.recordIteration(MAIN);
        }
        assertTrue(tracker.isBudgetExhausted(MAIN));
        assertEquals(0, tracker.getRemainingBudget(MAIN));
    }

    @Test
    public void testBudgetExhaustedBeyondBoundary() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        tracker.registerActivity(MAIN, 15); // budget = 125
        for (int i = 0; i < 130; i++) {
            tracker.recordIteration(MAIN);
        }
        assertTrue(tracker.isBudgetExhausted(MAIN));
    }

    @Test
    public void testNotExhaustedOneBelowBoundary() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        tracker.registerActivity(MAIN, 15); // budget = 125
        for (int i = 0; i < 124; i++) {
            tracker.recordIteration(MAIN);
        }
        assertFalse(tracker.isBudgetExhausted(MAIN));
        assertEquals(1, tracker.getRemainingBudget(MAIN));
    }

    // ---------------------------------------------------------------------------
    // INV-BUD-03: remaining budget non-negative (clamped to 0)
    // ---------------------------------------------------------------------------

    @Test
    public void testRemainingBudgetClampedToZero() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        tracker.registerActivity(MAIN, 15); // budget = 125
        for (int i = 0; i < 200; i++) {
            tracker.recordIteration(MAIN);
        }
        assertEquals(0, tracker.getRemainingBudget(MAIN));
    }

    // ---------------------------------------------------------------------------
    // Multiple activities are independent
    // ---------------------------------------------------------------------------

    @Test
    public void testMultipleActivitiesIndependent() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        tracker.registerActivity(MAIN, 15);     // budget = 125
        tracker.registerActivity(SETTINGS, 3);  // budget = 65

        for (int i = 0; i < 65; i++) {
            tracker.recordIteration(SETTINGS);
        }
        assertTrue(tracker.isBudgetExhausted(SETTINGS));
        assertFalse(tracker.isBudgetExhausted(MAIN));
        assertEquals(125, tracker.getRemainingBudget(MAIN));
    }

    // ---------------------------------------------------------------------------
    // Zero widgets
    // ---------------------------------------------------------------------------

    @Test
    public void testZeroWidgetsGetsBaseBudgetOnly() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(50, 5);
        tracker.registerActivity(MAIN, 0); // budget = 50
        assertEquals(50, tracker.getRemainingBudget(MAIN));
    }
}
