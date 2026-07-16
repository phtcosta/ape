package com.android.commands.monkey.ape.llm;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * Unit tests for the {@code llm_tap} telemetry surface of {@link LlmRouter} (llm-coordinate-tap,
 * §4.3 / llm-routing "LLM Telemetry Logging").
 *
 * <p>The per-decision {@code [APE-LLM-TEL] result=llm_tap} / {@code result=no_match reason=…} line
 * is emitted inside {@code selectAction}, which loads AndroidDevice (screenshot capture) and is
 * therefore device-gated — the same reason {@link LlmRouterTest} excludes the full pipeline. Those
 * per-decision lines are validated by the device smoke (tasks.md 6.3). Here we pin the two
 * device-free seams: the aggregate summary field, and that {@code llmTapCount} joins the
 * {@code decisions} denominator so the reported ratio stays stable across this change.
 */
public class LlmRouterTelemetryTest {

    private static void setCounter(LlmRouter router, String field, int value) throws Exception {
        Field f = LlmRouter.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(router, value);
    }

    private static String captureSummary(LlmRouter router) {
        java.io.PrintStream originalOut = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true));
            router.printSummary();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    @Test
    public void summaryExposesLlmTapField() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        String out = captureSummary(router);
        assertTrue("summary must expose the llm_tap field, got: " + out, out.contains("llm_tap=0"));
        // Adjacent counters remain present and separate.
        assertTrue(out.contains("matched=0"));
        assertTrue(out.contains("no_match=0"));
    }

    @Test
    public void llmTapCounterAccessorDefaultsToZero() {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        assertEquals(0, router.getLlmTapCount());
    }

    @Test
    public void llmTapJoinsDecisionsDenominator() throws Exception {
        LlmRouter router = new LlmRouter(new java.util.Random(42));
        // 1 widget match + 1 off-tree tap, nothing else. decisions = 1 + 1 + 0 + 0 = 2.
        // The ratio numerator stays matchedCount (1), so 1/2 = 50.0%. If llm_tap were omitted from
        // the denominator, the ratio would read 100.0% (1/1) — this pins the stability guarantee.
        setCounter(router, "matchedCount", 1);
        setCounter(router, "llmTapCount", 1);
        String out = captureSummary(router);
        assertTrue("summary must report llm_tap=1, got: " + out, out.contains("llm_tap=1"));
        // The Decision ratio is "matched/decisions". decisions counts llm_tap, so it is 1/2, not
        // 1/1. (%d is locale-independent; the %.1f percentage is not, so we assert on the ratio
        // fraction only.) This pins that an off-tree tap stays inside the denominator.
        assertTrue("decisions denominator must include llm_tap (1/2), got: " + out, out.contains("(1/2)"));
        assertFalse("llm_tap must NOT be excluded from the denominator (would read 1/1), got: " + out,
                out.contains("(1/1)"));
    }
}
