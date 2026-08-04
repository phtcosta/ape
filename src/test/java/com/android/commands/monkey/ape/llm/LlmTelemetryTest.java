package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the run counts and what it says it counted.
 *
 * <p>The per-decision {@code [APE-LLM-TEL]} line is reachable from a JVM test because emission is
 * separate from orchestration: the line is a pure function of the verdict handed in, and nothing
 * on the way to it loads {@code AndroidDevice}. That is what lets the tests below pin the fields an
 * offline join reads, rather than leaving them to the device smoke.
 */
public class LlmTelemetryTest {

    private static final String URL = "http://localhost:9999/v1";

    private static RunSpec.LlmParams llmParams() {
        return TestRunSpecs.spec("ape.llmUrl", URL).llm();
    }

    /** A telemetry whose breaker has never tripped, which is what a healthy run reports. */
    private static LlmTelemetry telemetry() {
        return new LlmTelemetry(llmParams(), () -> 0);
    }

    private static void setCounter(LlmTelemetry telemetry, String field, int value) throws Exception {
        Field f = LlmTelemetry.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(telemetry, value);
    }

    /** Runs {@code body} with stdout captured, and returns what the trace received. */
    private static String trace(Runnable body) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(captured, true));
            body.run();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    private static String captureSummary(LlmTelemetry telemetry) {
        return trace(telemetry::printSummary);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static SglangClient.ChatResponse response(String model) {
        return new SglangClient.ChatResponse("some content", Collections.emptyList(), 120, 30, model);
    }

    private static ToolCallParser.ParsedAction parsed(String actionType, String text,
                                                      String repairForm) {
        return new ToolCallParser.ParsedAction(actionType, 500, 499, text, null, repairForm);
    }

    private static CoordinateMapper.Nearest nearest() {
        return new CoordinateMapper.Nearest("Button", 12.0, 7);
    }

    // -------------------------------------------------------------------------
    // The summary's decisions denominator
    // -------------------------------------------------------------------------

    @Test
    public void summaryExposesLlmTapField() {
        String out = captureSummary(telemetry());
        assertTrue("summary must expose the llm_tap field, got: " + out, out.contains("llm_tap=0"));
        // Adjacent counters remain present and separate.
        assertTrue(out.contains("matched=0"));
        assertTrue(out.contains("no_match=0"));
        // A run that abandoned nothing still names all seven causes, each at zero: an offline reader
        // joins on the field being present, so an absent field and a zero one are different facts.
        // screenshot_failed is a peer cause here, not a subset of another (INV-RTR-11).
        for (String field : new String[]{"screenshot_failed=0", "timeout=0", "http_error=0",
                "conn_error=0", "parse_error=0", "image_error=0", "internal_error=0"}) {
            assertTrue("summary must expose " + field + ", got: " + out, out.contains(field));
        }
        assertFalse("the per-cause breakdown replaced the aggregate null field, got: " + out,
                out.contains(" null="));
    }

    @Test
    public void llmTapCounterAccessorDefaultsToZero() {
        assertEquals(0, telemetry().getLlmTapCount());
    }

    @Test
    public void llmTapJoinsDecisionsDenominator() throws Exception {
        LlmTelemetry telemetry = telemetry();
        // 1 widget match + 1 off-tree tap, nothing else. decisions = 1 + 1 + 0 + 0 = 2.
        // The ratio numerator stays matchedCount (1), so 1/2 = 50.0%. If llm_tap were omitted from
        // the denominator, the ratio would read 100.0% (1/1) — this pins the stability guarantee.
        setCounter(telemetry, "matchedCount", 1);
        setCounter(telemetry, "llmTapCount", 1);
        String out = captureSummary(telemetry);
        assertTrue("summary must report llm_tap=1, got: " + out, out.contains("llm_tap=1"));
        // The Decision ratio is "matched/decisions". decisions counts llm_tap, so it is 1/2, not
        // 1/1. (%d is locale-independent; the %.1f percentage is not, so we assert on the ratio
        // fraction only.) This pins that an off-tree tap stays inside the denominator.
        assertTrue("decisions denominator must include llm_tap (1/2), got: " + out, out.contains("(1/2)"));
        assertFalse("llm_tap must NOT be excluded from the denominator (would read 1/1), got: " + out,
                out.contains("(1/1)"));
    }

    // -------------------------------------------------------------------------
    // The seven causes partition the abandoned attempts (INV-RTR-11)
    // -------------------------------------------------------------------------

    /**
     * Each abandoned attempt increments exactly one cause counter, none is a subset of another, and
     * their sum is the failure total the decisions denominator uses.
     */
    @Test
    public void theSevenCausesPartitionTheAbandonedAttempts() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> {
            telemetry.screenshotFailed(1, "com.example.MainActivity", "surface_control");
            telemetry.imageFailed(2);
            telemetry.transportFailed(3, "timeout");
            telemetry.transportFailed(4, "http_500");
            telemetry.transportFailed(5, "connection");
            telemetry.parseFailed(6);
            telemetry.internalError(7, "boom");
            telemetry.printSummary();
        });

        assertEquals("seven abandoned attempts, one counter each", 7, telemetry.getFailureCount());
        assertTrue(out, out.contains("screenshot_failed=1"));
        assertTrue(out, out.contains("image_error=1"));
        assertTrue(out, out.contains("timeout=1"));
        assertTrue(out, out.contains("http_error=1"));
        assertTrue(out, out.contains("conn_error=1"));
        assertTrue(out, out.contains("parse_error=1"));
        assertTrue(out, out.contains("internal_error=1"));
        // No abandoned attempt is a decision: the seven are the denominator's failure half, and
        // none of them touched matched/llm_tap/no_match.
        assertTrue(out, out.contains("matched=0"));
        assertTrue(out, out.contains("no_match=0"));
        assertTrue("the seven are the whole denominator here, got: " + out, out.contains("(0/7)"));
    }

    /**
     * An absent cause is a connection failure, not an eighth kind. The client resets its error seam
     * per call and returns null when it has nothing to discriminate; dropping such an attempt from
     * the partition would leave the denominator short of an attempt that was really made.
     */
    @Test
    public void anUnattributedTransportFailureIsCountedAsAConnectionFailure() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> {
            telemetry.transportFailed(1, null);
            telemetry.printSummary();
        });

        assertEquals(1, telemetry.getFailureCount());
        assertTrue(out, out.contains("conn_error=1"));
        assertTrue("the error line names the cause it counted, got: " + out,
                out.contains("[APE-LLM-ERROR] step=1 cause=connection"));
    }

    /** Every HTTP status shares one counter — the status itself stays on the line. */
    @Test
    public void everyHttpStatusLandsOnTheHttpCounter() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> {
            telemetry.transportFailed(1, "http_500");
            telemetry.transportFailed(2, "http_404");
            telemetry.printSummary();
        });

        assertTrue(out, out.contains("http_error=2"));
        assertTrue(out, out.contains("cause=http_500"));
        assertTrue(out, out.contains("cause=http_404"));
    }

    /**
     * An unextractable tool call is counted as a parse failure, the same label an unparseable
     * envelope carries. The two are different sites with different diagnostics and one counter,
     * because both mean the model's answer could not be turned into an action.
     */
    @Test
    public void theTwoParseFailuresShareOneCounterAndKeepTheirOwnDiagnostics() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> {
            telemetry.transportFailed(1, "parse");
            telemetry.parseFailed(2);
            telemetry.printSummary();
        });

        assertTrue(out, out.contains("parse_error=2"));
        assertTrue(out, out.contains("detail=null response from SGLang"));
        assertTrue(out, out.contains("detail=no tool call extracted"));
    }

    /**
     * A capture failure names the activity, which is what makes per-app {@code FLAG_SECURE}
     * degradation countable offline — the whole reason the branch stopped being silent.
     */
    @Test
    public void aCaptureFailureNamesTheActivityAndTheFailingStage() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> telemetry.screenshotFailed(9, "com.example.Secure", "uiautomation"));

        assertEquals(1, telemetry.getScreenshotFailedCount());
        assertTrue(out, out.contains("[APE-LLM-ERROR] step=9 cause=screenshot"
                + " activity=com.example.Secure detail=uiautomation"));
    }

    /** A capture that named no stage still produces an attributable line. */
    @Test
    public void aCaptureFailureWithoutAStageStillReportsOne() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> telemetry.screenshotFailed(9, "com.example.Main", null));

        assertTrue(out, out.contains("detail=unknown"));
    }

    // -------------------------------------------------------------------------
    // The acknowledgement latch (INV-RTR-12)
    // -------------------------------------------------------------------------

    /**
     * The manifest records the model the plan asked for; the ACK records what answered. It is worth
     * exactly one line per run — a per-response ACK would say the same thing hundreds of times and
     * an absent one would leave a server/plan mismatch undetectable.
     */
    @Test
    public void theServerModelIsAcknowledgedOncePerRun() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> {
            telemetry.acknowledge(response("Qwen/Qwen3-VL-4B-Instruct"));
            telemetry.acknowledge(response("Qwen/Qwen3-VL-4B-Instruct"));
            telemetry.acknowledge(response("something-else-entirely"));
        });

        assertEquals("three successful responses acknowledge once", 1,
                countOccurrences(out, "[APE-LLM-CONFIG-ACK]"));
        assertTrue(out, out.contains("server_model=Qwen/Qwen3-VL-4B-Instruct"));
    }

    /** A response whose envelope names no model still acknowledges, saying so. */
    @Test
    public void aResponseWithoutAServerModelAcknowledgesItAsUnknown() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> telemetry.acknowledge(response(null)));

        assertTrue(out, out.contains("[APE-LLM-CONFIG-ACK] server_model=unknown"));
    }

    // -------------------------------------------------------------------------
    // The per-decision line
    // -------------------------------------------------------------------------

    @Test
    public void aMatchedDecisionCarriesTheFieldsTheOfflineJoinReads() {
        LlmTelemetry telemetry = telemetry();
        telemetry.countAttempt();
        String out = trace(() -> telemetry.decision(42, "new-state", parsed("click", null, "none"),
                540, 958, "matched", null, "Button", nearest(), "com.example.MainActivity",
                response("m"), 1234L));

        assertTrue(out, out.contains("[APE-LLM-TEL] step=42"));
        assertTrue("the variant stamps every decision, got: " + out,
                out.contains(" variant=ape_current"));
        assertTrue("call= is the attempt ordinal, got: " + out, out.contains(" call=1"));
        assertTrue(out, out.contains(" mode=new-state"));
        assertTrue(out, out.contains(" action=click"));
        assertTrue("the model's own coordinate is kept beside the pixel one, got: " + out,
                out.contains(" qwen=(500,499) pixel=(540,958)"));
        assertTrue(out, out.contains(" result=matched"));
        assertTrue(out, out.contains(" matched_class=Button"));
        assertTrue(out, out.contains(" nearest_class=Button"));
        assertTrue(out, out.contains(" widgets=7"));
        assertTrue(out, out.contains(" activity=com.example.MainActivity"));
        assertTrue(out, out.contains(" tokens_in=120 tokens_out=30 time_ms=1234"));
        assertFalse("a selecting outcome carries no reason, got: " + out, out.contains(" reason="));
        assertFalse("nor a repair, got: " + out, out.contains(" repair="));
        assertEquals(1, telemetry.getMatchedCount());
    }

    /** Typed text rides the line quoted, so an offline reader can tell it from an empty field. */
    @Test
    public void typedTextIsQuotedOnTheLine() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> telemetry.decision(1, "random",
                parsed("type_text", "hello world", "none"), 540, 958, "matched", null, "EditText",
                nearest(), "com.example.MainActivity", response("m"), 10L));

        assertTrue(out, out.contains(" text=\"hello world\""));
    }

    /**
     * A banned answer is counted twice on purpose: under {@code no_match}, because that is the path
     * it left through, and under {@code dead_pair}, because bucket D is the ban's falsification gate
     * and must be readable from the summary line without joining the per-decision stream.
     */
    @Test
    public void aBannedDecisionIsCountedUnderBothNoMatchAndDeadPair() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> {
            telemetry.decision(1, "new-state", parsed("click", null, "none"), 540, 958,
                    "no_match", "dead_pair", "none", nearest(), "com.example.MainActivity",
                    response("m"), 10L);
            telemetry.printSummary();
        });

        assertTrue(out, out.contains(" result=no_match reason=dead_pair"));
        assertEquals(1, telemetry.getNoMatchCount());
        assertTrue("the overlay is counted, got: " + out, out.contains("no_match=1"));
        assertTrue(out, out.contains("dead_pair=1"));
        // The overlay does not enter the denominator a second time: one decision, one slot.
        assertTrue("a banned decision is one decision, got: " + out, out.contains("(0/1)"));
    }

    /** The other two no_match mechanisms keep their own reasons. */
    @Test
    public void anUnmatchedAnswerNamesWhichMechanismRefusedIt() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> {
            telemetry.decision(1, "random", parsed("click", null, "none"), 0, 0,
                    "no_match", "degenerate", "none", nearest(), "a", response("m"), 10L);
            telemetry.decision(2, "random", parsed("click", null, "none"), 540, 20,
                    "no_match", "boundary", "none", nearest(), "a", response("m"), 10L);
            telemetry.printSummary();
        });

        assertTrue(out, out.contains(" reason=degenerate"));
        assertTrue(out, out.contains(" reason=boundary"));
        assertTrue("neither is a ban, got: " + out, out.contains("dead_pair=0"));
        assertEquals(2, telemetry.getNoMatchCount());
    }

    /**
     * The repair overlay is a property of the line: it is counted only when the line carries
     * {@code repair=}, and it never joins the decisions denominator (INV-RTR-13), so the base×v2
     * tool-call fidelity contrast stays readable from the summary alone.
     */
    @Test
    public void theRepairOverlayIsCountedOnlyWhenTheLineCarriesIt() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> {
            telemetry.decision(1, "random", parsed("click", null, "none"), 540, 958,
                    "matched", null, "Button", nearest(), "a", response("m"), 10L);
            telemetry.decision(2, "random", parsed("click", null, "array_xy"), 540, 958,
                    "matched", null, "Button", nearest(), "a", response("m"), 10L);
            telemetry.printSummary();
        });

        assertEquals("one of the two lines carries a repair", 1,
                countOccurrences(out, " repair="));
        assertTrue(out, out.contains(" repair=array_xy"));
        assertTrue(out, out.contains("repaired=1"));
        // Both decisions matched; the overlay changed no outcome count.
        assertTrue(out, out.contains("matched=2"));
        assertTrue("two decisions, not three, got: " + out, out.contains("(2/2)"));
    }

    /** An off-tree tap is its own outcome, counted apart from matched and no_match. */
    @Test
    public void anOffTreeTapIsItsOwnOutcome() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> {
            telemetry.decision(1, "stagnation", parsed("click", null, "none"), 540, 958,
                    "llm_tap", null, "none", nearest(), "a", response("m"), 10L);
            telemetry.printSummary();
        });

        assertTrue(out, out.contains(" result=llm_tap"));
        assertEquals(1, telemetry.getLlmTapCount());
        assertEquals(0, telemetry.getMatchedCount());
        assertEquals(0, telemetry.getNoMatchCount());
    }

    /** Tokens and latency accumulate across decisions; attempts count separately. */
    @Test
    public void tokensAndLatencyAccumulateAcrossDecisions() {
        LlmTelemetry telemetry = telemetry();
        telemetry.countAttempt();
        telemetry.countAttempt();
        String out = trace(() -> {
            telemetry.decision(1, "random", parsed("click", null, "none"), 540, 958,
                    "matched", null, "Button", nearest(), "a", response("m"), 100L);
            telemetry.decision(2, "random", parsed("click", null, "none"), 540, 958,
                    "matched", null, "Button", nearest(), "a", response("m"), 250L);
            telemetry.printSummary();
        });

        assertEquals(2, telemetry.getCallCount());
        assertTrue(out, out.contains("calls=2"));
        assertTrue(out, out.contains("tokens_in=240"));
        assertTrue(out, out.contains("tokens_out=60"));
        assertTrue(out, out.contains("time_ms=350"));
    }

    // -------------------------------------------------------------------------
    // The breaker's trips are the client's, reported here
    // -------------------------------------------------------------------------

    /**
     * The summary reports the run's trip count by asking for it, not by mirroring it. The
     * mirrored form would refresh a field at each failure site; since only a failure raises
     * the count, the value is the same, and asking removes a second place for it to live.
     */
    @Test
    public void theSummaryReportsTheBreakersOwnTripCount() {
        LlmTelemetry telemetry = new LlmTelemetry(llmParams(), () -> 7);
        String out = captureSummary(telemetry);

        assertTrue(out, out.contains("breaker_trips=7"));
    }

    /** The prompt is recorded without the image, which is reconstructible and would dwarf the trace. */
    @Test
    public void thePromptIsRecordedWithoutTheImage() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> telemetry.logPrompt(new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT)
                .build(null, null, null, null, "AAAABBBBCCCC", null)));

        assertTrue("the system message is recorded, got: " + out,
                out.contains("[APE-LLM-PROMPT] system="));
        assertTrue("and so is the user text, got: " + out,
                out.contains("[APE-LLM-PROMPT] user_text="));
        assertFalse("the base64 image is not, got: " + out, out.contains("AAAABBBBCCCC"));
    }
}
