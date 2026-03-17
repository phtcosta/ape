package com.android.commands.monkey.ape.llm;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for LlmCircuitBreaker.
 *
 * Verifies CLOSED → OPEN → HALF_OPEN → CLOSED state transitions and that
 * shouldAttempt() blocks calls correctly when the breaker is OPEN.
 *
 * Tests run on the JVM only; no Android device or SGLang server required.
 */
public class LlmCircuitBreakerTest {

    // ---------------------------------------------------------------------------
    // Test 1: CLOSED → OPEN after reaching the failure threshold (3 failures)
    // ---------------------------------------------------------------------------
    @Test
    public void testClosedToOpen_afterThreeFailures() {
        LlmCircuitBreaker cb = new LlmCircuitBreaker(3, 60_000L);

        assertEquals("CLOSED", cb.getStateName());

        cb.recordFailure();
        assertEquals("CLOSED", cb.getStateName());

        cb.recordFailure();
        assertEquals("CLOSED", cb.getStateName());

        cb.recordFailure();  // third failure — should trip to OPEN
        assertEquals("OPEN", cb.getStateName());
    }

    // ---------------------------------------------------------------------------
    // Test 2: shouldAttempt returns false when OPEN (within recovery window)
    // ---------------------------------------------------------------------------
    @Test
    public void testShouldAttempt_falseWhenOpen() {
        // Very long open duration so the window does not expire during the test
        LlmCircuitBreaker cb = new LlmCircuitBreaker(1, 600_000L);

        cb.recordFailure();  // trips immediately (threshold = 1)
        assertEquals("OPEN", cb.getStateName());

        assertFalse("shouldAttempt must return false while OPEN", cb.shouldAttempt());
    }

    // ---------------------------------------------------------------------------
    // Test 3: OPEN → HALF_OPEN after the recovery timeout elapses
    //         Uses a 1 ms timeout so the test does not need a long sleep.
    // ---------------------------------------------------------------------------
    @Test
    public void testOpenToHalfOpen_afterTimeout() throws InterruptedException {
        LlmCircuitBreaker cb = new LlmCircuitBreaker(1, 50L);  // 50 ms timeout

        cb.recordFailure();
        assertEquals("OPEN", cb.getStateName());
        assertFalse("shouldAttempt must be false immediately after trip", cb.shouldAttempt());

        // Wait for the recovery window to expire
        Thread.sleep(100L);

        // shouldAttempt() should transition to HALF_OPEN and return true
        assertTrue("shouldAttempt must return true after timeout", cb.shouldAttempt());
        assertEquals("HALF_OPEN", cb.getStateName());
    }

    // ---------------------------------------------------------------------------
    // Test 4: HALF_OPEN → CLOSED on success
    // ---------------------------------------------------------------------------
    @Test
    public void testHalfOpenToClosedOnSuccess() throws InterruptedException {
        LlmCircuitBreaker cb = new LlmCircuitBreaker(1, 50L);

        cb.recordFailure();
        Thread.sleep(100L);
        cb.shouldAttempt();  // transitions to HALF_OPEN

        assertEquals("HALF_OPEN", cb.getStateName());

        cb.recordSuccess();
        assertEquals("CLOSED", cb.getStateName());
        assertEquals(0, cb.getFailureCount());
    }

    // ---------------------------------------------------------------------------
    // Test 5: HALF_OPEN → OPEN on failure (re-trips with fresh timestamp)
    // ---------------------------------------------------------------------------
    @Test
    public void testHalfOpenToOpenOnFailure() throws InterruptedException {
        LlmCircuitBreaker cb = new LlmCircuitBreaker(1, 50L);

        cb.recordFailure();   // first trip
        Thread.sleep(100L);
        cb.shouldAttempt();   // → HALF_OPEN

        assertEquals("HALF_OPEN", cb.getStateName());
        int tripsBefore = cb.getTripCount();

        cb.recordFailure();   // fails the probe — should re-trip
        assertEquals("OPEN", cb.getStateName());
        assertEquals(tripsBefore + 1, cb.getTripCount());
    }

    // ---------------------------------------------------------------------------
    // Test 6: recordSuccess resets failure count and closes the breaker from any state
    // ---------------------------------------------------------------------------
    @Test
    public void testRecordSuccess_resetsFromAnyState() {
        LlmCircuitBreaker cb = new LlmCircuitBreaker(3, 60_000L);

        // Accumulate two failures (below threshold — still CLOSED)
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(2, cb.getFailureCount());
        assertEquals("CLOSED", cb.getStateName());

        cb.recordSuccess();
        assertEquals(0, cb.getFailureCount());
        assertEquals("CLOSED", cb.getStateName());

        // Now trip to OPEN and then call recordSuccess directly
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();  // trips to OPEN
        assertEquals("OPEN", cb.getStateName());

        cb.recordSuccess();
        assertEquals("CLOSED", cb.getStateName());
        assertEquals(0, cb.getFailureCount());
    }

    // ---------------------------------------------------------------------------
    // Test 7: shouldAttempt returns true when CLOSED (normal operation)
    // ---------------------------------------------------------------------------
    @Test
    public void testShouldAttempt_trueWhenClosed() {
        LlmCircuitBreaker cb = new LlmCircuitBreaker();
        assertTrue("shouldAttempt must return true when CLOSED", cb.shouldAttempt());
    }

    // ---------------------------------------------------------------------------
    // Test 8: tripCount increments only when transitioning to OPEN
    // ---------------------------------------------------------------------------
    @Test
    public void testTripCount_incrementsOnTrip() {
        LlmCircuitBreaker cb = new LlmCircuitBreaker(2, 60_000L);

        assertEquals(0, cb.getTripCount());

        cb.recordFailure();
        cb.recordFailure();  // trips to OPEN
        assertEquals(1, cb.getTripCount());

        // Success closes it; two more failures trip again
        cb.recordSuccess();
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(2, cb.getTripCount());
    }
}
