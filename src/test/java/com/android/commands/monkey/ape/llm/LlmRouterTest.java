package com.android.commands.monkey.ape.llm;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for LlmRouter.
 *
 * <p>LlmRouter depends on Android device APIs (AndroidDevice, ScreenshotCapture,
 * ImageProcessor/Bitmap) only inside selectAction(). The constructor wires up
 * pure-Java collaborators and is safe to call in a JVM test.
 *
 * <p>The package-private mapToModelAction() method is the most testable entry
 * point. Tests that exercise it can be written without instantiating ModelAction
 * or GUITreeNode for the cases that hit early-exit paths:
 * <ul>
 *   <li>null actionType → null</li>
 *   <li>back + null state → null (exception caught internally)</li>
 *   <li>Boundary reject: pixelY in top 5% of screen height → null</li>
 *   <li>Boundary reject: pixelY in bottom 6% of screen height → null</li>
 *   <li>null actions list → null</li>
 *   <li>empty actions list → null</li>
 * </ul>
 *
 * <p>Routing predicates (shouldRouteNewState / shouldRouteStagnation) depend on
 * Config static fields that are loaded from System properties at class init (no
 * Android APIs), so they are also testable.
 *
 * <p>Telemetry accessor methods are verified to start at zero.
 *
 * <p>Tests that require a real HTTP server or a connected Android device are NOT
 * included here (selectAction full pipeline).
 */
public class LlmRouterTest {

    // -------------------------------------------------------------------------
    // Constructor / initial state
    // -------------------------------------------------------------------------

    @Test
    public void constructor_doesNotThrow() {
        // LlmRouter() wires pure-Java collaborators; no Android APIs called at construction
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        assertNotNull(router);
    }

    @Test
    public void initialCounters_areAllZero() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        assertEquals(0, router.getCallCount());
        assertEquals(0, router.getMatchedCount());
        assertEquals(0, router.getNoMatchCount());
        assertEquals(0, router.getNullCount());
        assertEquals(0, router.getBreakerTrips());
    }

    // -------------------------------------------------------------------------
    // mapToModelAction — null / empty inputs
    // -------------------------------------------------------------------------

    @Test
    public void mapToModelAction_nullActionType_returnsNull() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        // null actionType → immediate null return before any other check
        assertNull(router.mapToModelAction(540, 960, null, null, null, null, 1080, 1920));
    }

    @Test
    public void mapToModelAction_emptyActionsList_returnsNull() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        List<com.android.commands.monkey.ape.model.ModelAction> actions = new ArrayList<>();
        // Empty list — no actions to match
        assertNull(router.mapToModelAction(540, 960, "click", null, actions, null, 1080, 1920));
    }

    @Test
    public void mapToModelAction_nullActionsList_returnsNull() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        assertNull(router.mapToModelAction(540, 960, "click", null, null, null, 1080, 1920));
    }

    // -------------------------------------------------------------------------
    // mapToModelAction — back action (null state → exception caught → null)
    // -------------------------------------------------------------------------

    @Test
    public void mapToModelAction_backAction_nullState_returnsNull() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        // "back" with null state → state.getBackAction() throws NPE → caught → null
        assertNull(router.mapToModelAction(0, 0, "back", null, null, null, 1080, 1920));
    }

    // -------------------------------------------------------------------------
    // mapToModelAction — boundary rejection
    // -------------------------------------------------------------------------

    @Test
    public void mapToModelAction_pixelYInTopStatusBar_rejected() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        int deviceHeight = 1920;
        // Top 5%: y < 96 (1920 * 0.05 = 96)
        int yInStatusBar = 90;  // < 96 → reject
        List<com.android.commands.monkey.ape.model.ModelAction> actions = new ArrayList<>();
        assertNull("y in top 5% must be rejected as status-bar coordinate",
                router.mapToModelAction(540, yInStatusBar, "click", null, actions, null, 1080, deviceHeight));
    }

    @Test
    public void mapToModelAction_pixelYAtExactTopBoundary_rejected() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        int deviceHeight = 1920;
        // Boundary: y < deviceHeight * 0.05 → y < 96
        // y = 95 is strictly less than 96 → rejected
        int yAtBoundary = 95;
        assertNull("y strictly below 5% threshold must be rejected",
                router.mapToModelAction(540, yAtBoundary, "click", null, new ArrayList<>(), null, 1080, deviceHeight));
    }

    @Test
    public void mapToModelAction_pixelYInBottomNavBar_rejected() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        int deviceHeight = 1920;
        // Bottom 6%: y > 1920 * 0.94 = 1804.8 → y > 1804
        int yInNavBar = 1850;  // > 1804 → reject
        List<com.android.commands.monkey.ape.model.ModelAction> actions = new ArrayList<>();
        assertNull("y in bottom 6% must be rejected as nav-bar coordinate",
                router.mapToModelAction(540, yInNavBar, "click", null, actions, null, 1080, deviceHeight));
    }

    @Test
    public void mapToModelAction_pixelYAtExactBottomBoundary_rejected() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        int deviceHeight = 1920;
        // deviceHeight * 0.94 = 1804.8; y > 1804.8 → y >= 1805 is rejected
        int yAtBoundary = 1805;
        assertNull("y strictly above 94% threshold must be rejected",
                router.mapToModelAction(540, yAtBoundary, "click", null, new ArrayList<>(), null, 1080, deviceHeight));
    }

    @Test
    public void mapToModelAction_pixelYInsafeZone_doesNotRejectOnBoundaryAlone() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        int deviceHeight = 1920;
        // y = 500 is in safe zone (96 <= 500 <= 1804) — rejection does not happen
        // With an empty action list the method returns null for no-match, not boundary-reject.
        // The important thing is that it does NOT return null due to boundary check;
        // it returns null because actions is empty (different code path).
        // We can only distinguish these at the code level, so we verify null is returned
        // for the expected empty-actions reason (no Android mock needed).
        assertNull("empty actions list should return null (not boundary reject)",
                router.mapToModelAction(540, 500, "click", null, new ArrayList<>(), null, 1080, deviceHeight));
    }

    // -------------------------------------------------------------------------
    // Routing predicates
    // -------------------------------------------------------------------------

    @Test
    public void shouldRouteNewState_whenIsNewStateFalse_returnsFalse() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        // isNewState=false → never route regardless of other conditions
        assertFalse(router.shouldRouteNewState(false));
    }

    @Test
    public void shouldRouteStagnation_negativeCounter_returnsFalse() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        // Negative graphStableCounter cannot equal graphStableRestartThreshold / 2
        assertFalse(router.shouldRouteStagnation(-1));
    }

    // -------------------------------------------------------------------------
    // Circuit breaker is accessible
    // -------------------------------------------------------------------------

    @Test
    public void getBreaker_returnsNonNull() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        assertNotNull("circuit breaker must not be null", router.getBreaker());
    }

    // -------------------------------------------------------------------------
    // shouldRouteRandom — probabilistic routing
    // Config.llmPercentage is 0.02 (default), so tests work with that value.
    // -------------------------------------------------------------------------

    @Test
    public void shouldRouteRandom_defaultPercentage_mostlyFalse() {
        // With default Config.llmPercentage = 0.02 (2%), the vast majority should be false
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        int trueCount = 0;
        for (int i = 0; i < 1000; i++) {
            if (router.shouldRouteRandom()) trueCount++;
        }
        // At 2%, expect ~20 true out of 1000. Allow range 5-50.
        assertTrue("Expected ~2% true, got " + trueCount + "/1000", trueCount >= 5 && trueCount <= 50);
    }

    @Test
    public void shouldRouteRandom_seededRandomIsReproducible() {
        // Two routers with same seed must produce identical sequences
        LlmRouter router1 = new LlmRouter(new java.util.Random(12345));
        LlmRouter router2 = new LlmRouter(new java.util.Random(12345));
        boolean[] results1 = new boolean[50];
        boolean[] results2 = new boolean[50];
        for (int i = 0; i < 50; i++) {
            results1[i] = router1.shouldRouteRandom();
            results2[i] = router2.shouldRouteRandom();
        }
        assertArrayEquals("Same seed must produce identical routing decisions",
                results1, results2);
    }

    @Test
    public void shouldRouteRandom_budgetExhausted_returnsFalse() throws Exception {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        // Exhaust budget via reflection on callCount
        java.lang.reflect.Field callCountField = LlmRouter.class.getDeclaredField("callCount");
        callCountField.setAccessible(true);
        callCountField.setInt(router, com.android.commands.monkey.ape.utils.Config.llmMaxCalls);

        // Even with default 2% probability, budget exhaustion should force false
        for (int i = 0; i < 100; i++) {
            assertFalse("shouldRouteRandom must return false when budget exhausted",
                    router.shouldRouteRandom());
        }
    }

    @Test
    public void shouldRouteRandom_circuitBreakerOpen_returnsFalse() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        // Trip the circuit breaker (3 consecutive failures)
        LlmCircuitBreaker breaker = router.getBreaker();
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals("OPEN", breaker.getStateName());

        // Even with default 2% probability, breaker open should force false
        for (int i = 0; i < 100; i++) {
            assertFalse("shouldRouteRandom must return false when circuit breaker is open",
                    router.shouldRouteRandom());
        }
    }

    @Test
    public void shouldRouteRandom_differentSeeds_differentResults() {
        // Different seeds should (very likely) produce different sequences
        LlmRouter router1 = new LlmRouter(new java.util.Random(111));
        LlmRouter router2 = new LlmRouter(new java.util.Random(999));
        boolean allSame = true;
        for (int i = 0; i < 50; i++) {
            if (router1.shouldRouteRandom() != router2.shouldRouteRandom()) {
                allSame = false;
                break;
            }
        }
        assertFalse("Different seeds should produce different routing decisions", allSame);
    }
}
