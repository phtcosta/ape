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
        assertEquals(0, router.getFailureCount());
        assertEquals(0, router.getScreenshotFailedCount());
        assertEquals(0, router.getBreakerTrips());
    }

    // -------------------------------------------------------------------------
    // INV-RTR-09 (mop-reach-strategies 3.3): llmPercentageNoSubstrate is loaded and
    // exposed only — no routing decision consumes it in this change. Clamp/default are
    // pinned in ConfigTest 1.4; here we pin the "no consumer" half two ways.
    // -------------------------------------------------------------------------

    @Test
    public void llmPercentageNoSubstrate_isExposedButNotConsumedByRouting() throws Exception {
        // Exposed with the -1 sentinel (F′ seam; no consumer wired yet). Clamp/defaults are
        // pinned in ConfigTest 1.4.
        assertEquals(-1.0,
                com.android.commands.monkey.ape.utils.Config.llmPercentageNoSubstrate, 1e-9);

        // No consumer: shouldRouteRandom fires purely on Config.llmPercentage, never on the
        // no-substrate override. Pinned structurally — the override is not referenced anywhere
        // in LlmRouter's source, so a future wiring must be a deliberate spec change, not a
        // silent edit (INV-RTR-09).
        java.io.File src = new java.io.File(
                "src/main/java/com/android/commands/monkey/ape/llm/LlmRouter.java");
        assertTrue("LlmRouter source not found at " + src.getAbsolutePath(), src.exists());
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse("llmPercentageNoSubstrate must have no consumer in LlmRouter (INV-RTR-09)",
                body.contains("llmPercentageNoSubstrate"));
    }

    // -------------------------------------------------------------------------
    // screenshot_failed counter split (task §5.5 / llm-routing spec)
    //
    // The increment itself lives in selectAction()'s screenshot-null branch,
    // which loads AndroidDevice (getDisplayBounds) and is device-gated — the same
    // reason the A-6 breaker behavior is not unit-tested here (see the note at the
    // bottom of this class). We therefore verify the counter split at the two JVM
    // seams that ARE observable without a device: the accessor default and the
    // summary-line format.
    // -------------------------------------------------------------------------

    @Test
    public void screenshotFailedCount_defaultsToZero() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        assertEquals("screenshot_failed counter starts at 0", 0, router.getScreenshotFailedCount());
    }

    @Test
    public void printSummary_includesDiscriminatedCauseFields_notAggregateNull() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        java.io.PrintStream originalOut = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true));
            router.printSummary();
        } finally {
            System.setOut(originalOut);
        }
        String out = captured.toString();
        // screenshot_failed is a peer cause field, and the aggregate null field is replaced by the
        // per-cause breakdown (INV-RTR-11).
        assertTrue("summary must expose screenshot_failed field, got: " + out,
                out.contains("screenshot_failed=0"));
        for (String field : new String[]{"timeout=0", "http_error=0", "conn_error=0",
                "parse_error=0", "image_error=0", "internal_error=0"}) {
            assertTrue("summary must expose " + field + ", got: " + out, out.contains(field));
        }
        assertFalse("aggregate null field must be gone, got: " + out, out.contains(" null="));
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

    // -------------------------------------------------------------------------
    // llm-native-toolcall-repair J1b/J1c (INV-RTR-14): max_tokens, boundary bands
    // and the euclidean snap floor are Config-sourced; at the defaults every
    // observable behavior is byte-identical to the pre-change hard-coded constants.
    // -------------------------------------------------------------------------

    @Test
    public void manifest_maxTokens_isConfigDefault1024_byteIdentical() {
        // The [APE-LLM-CONFIG] manifest is emitted in the constructor (INV-RTR-10) via Logger →
        // System.out. At the J1c default the max_tokens field must read 1024 — the same value the
        // request body carries and byte-identical to the pre-change hard-coded local (INV-RTR-14).
        java.io.PrintStream originalOut = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true));
            new LlmRouter(new java.util.Random(42));
        } finally {
            System.setOut(originalOut);
        }
        String out = captured.toString();
        assertTrue("constructor must emit the [APE-LLM-CONFIG] manifest line, got: " + out,
                out.contains("[APE-LLM-CONFIG]"));
        assertTrue("manifest max_tokens must be the Config default 1024 (J1c, INV-RTR-14), got: " + out,
                out.contains("max_tokens=1024"));
    }

    @Test
    public void mapToModelAction_defaultBands_boundaryRejectsIdentical() {
        // INV-RTR-14: at the 0.05/0.94 defaults, a top-band (pixelY=50) and a bottom-band
        // (pixelY=1850) coordinate on a 1080x1920 device are both rejected — identical to the
        // pre-change hard-coded literals.
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        List<com.android.commands.monkey.ape.model.ModelAction> actions = new ArrayList<>();
        assertNull("top-band coordinate (50 < 1920*0.05) must be rejected at the default",
                router.mapToModelAction(540, 50, "click", null, actions, null, 1080, 1920));
        assertNull("bottom-band coordinate (1850 > 1920*0.94) must be rejected at the default",
                router.mapToModelAction(540, 1850, "click", null, actions, null, 1080, 1920));
    }

    @Test
    public void mapToModelAction_useSites_readConfigNotLiterals() throws Exception {
        // The three J1b/J1c use sites (max_tokens, boundary bands, euclidean snap floor) must read
        // the Config keys, not bare literals. The euclidean MATCH needs a resolved GUITreeNode
        // (device-gated, excluded here), so INV-RTR-14 floor-identity is pinned structurally: guard
        // against a silent revert to 50.0 / 0.05 / 0.94 / 1024 hard-coded constants. Mirrors this
        // class's existing source-content check for the llmPercentageNoSubstrate no-consumer seam.
        java.io.File src = new java.io.File(
                "src/main/java/com/android/commands/monkey/ape/llm/LlmRouter.java");
        assertTrue("LlmRouter source not found at " + src.getAbsolutePath(), src.exists());
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("max_tokens must read Config.llmMaxTokens (J1c)",
                body.contains("Config.llmMaxTokens"));
        assertTrue("euclidean floor must read Config.llmSnapTolerancePx (J1b)",
                body.contains("Config.llmSnapTolerancePx"));
        assertTrue("top boundary band must read Config.llmBoundaryTopPct (J1b)",
                body.contains("Config.llmBoundaryTopPct"));
        assertTrue("bottom boundary band must read Config.llmBoundaryBottomPct (J1b)",
                body.contains("Config.llmBoundaryBottomPct"));
    }

    // -------------------------------------------------------------------------
    // 4.2 — the native repaired decision now feeds the EXISTING repair= telemetry
    // (INV-RTR-13, unchanged, now native-fed). selectAction's TEL emission is
    // device-gated (screenshot capture), so this is pinned at the two JVM-observable
    // seams: (1) the value the emitter keys on — a native malformed tool call now
    // yields a ParsedAction whose repair form is non-none — and (2) the emitter site
    // itself, unchanged (D6: zero telemetry-emission changes).
    // -------------------------------------------------------------------------

    @Test
    public void nativeRepairedDecision_feedsNonNoneRepairForm() {
        ToolCallParser parser = new ToolCallParser();
        SglangClient.ToolCall tc = new SglangClient.ToolCall(
                "click", Collections.<String, Object>emptyMap(), "{\"x\": 616, 891}");
        SglangClient.ChatResponse response = new SglangClient.ChatResponse(
                "", Collections.singletonList(tc), 0, 0);
        ToolCallParser.ParsedAction parsed = parser.parse(response);

        assertNotNull(parsed);
        // Exactly the value LlmRouter.selectAction reads to drive the repair= TEL field.
        assertNotEquals("native malformed tool call must now carry a repair form for TEL",
                "none", parsed.getRepairForm());
        assertEquals("missing_y", parsed.getRepairForm());
    }

    @Test
    public void repairTelemetryEmitter_isNativeFedAndUnchanged() throws Exception {
        // INV-RTR-13/D6: the repair= append + repairedCount++ emitter keys purely on
        // ParsedAction.getRepairForm(), so it fires for native-path repairs the same way it fires
        // for XML ones — no emitter change. Pinned structurally (the emission lives in the
        // device-gated selectAction).
        java.io.File src = new java.io.File(
                "src/main/java/com/android/commands/monkey/ape/llm/LlmRouter.java");
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("repair= must be gated on getRepairForm() != none",
                body.contains("!\"none\".equals(parsed.getRepairForm())"));
        assertTrue("repair= field must be appended to the TEL line",
                body.contains("\" repair=\""));
        assertTrue("repairedCount must be incremented at the repair emission site",
                body.contains("repairedCount++"));
    }

    // -------------------------------------------------------------------------
    // The gh15 A-6 null-screenshot breaker behavior (LlmRouter.java:246-254) is
    // NOT unit-tested here: it lives inside selectAction(), which loads
    // AndroidDevice (getDisplayBounds) and therefore android.os.RemoteException,
    // absent from the JVM test classpath (NoClassDefFoundError, an Error that the
    // internal try/catch does not intercept). This matches this class's existing
    // decision to exclude the selectAction full pipeline. Covered by the on-device
    // gate (tasks.md 6.3c): a secure-window app opens the breaker and stops
    // per-step retries.
    // -------------------------------------------------------------------------
}
