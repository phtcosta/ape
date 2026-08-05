package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.telemetry.NdjsonSink;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the run counts and what it records having counted.
 *
 * <p>The per-attempt sub-event is reachable from a JVM test because recording is separate from
 * orchestration: the sub-event is a pure function of the verdict handed in, and nothing on the way
 * to it loads {@code AndroidDevice}. That is what lets the tests below pin the fields an offline
 * reader consumes, rather than leaving them to the device smoke.
 *
 * <p>They are written against a real {@link NdjsonSink} over a buffer rather than a recording
 * double, because the property under test is not "the sink was called" but "the trace carries the
 * field" — and the two differ exactly where the record's omission rules live. Every sub-event
 * assertion therefore also asserts the thing the retired encoding needed a {@code step=} key for:
 * that the attempt landed on the step whose selection was running.
 */
public class LlmTelemetryTest {

    private ByteArrayOutputStream captured;
    private NdjsonSink sink;

    @Before
    public void setUp() throws Exception {
        captured = new ByteArrayOutputStream();
        sink = new NdjsonSink(new PrintStream(captured, true, "UTF-8"));
    }

    /** A telemetry whose breaker has never tripped, which is what a healthy run reports. */
    private LlmTelemetry telemetry() {
        return new LlmTelemetry(() -> 0, sink, true);
    }

    private static void setCounter(LlmTelemetry telemetry, String field, int value) throws Exception {
        Field f = LlmTelemetry.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(telemetry, value);
    }

    /** Runs {@code body} inside an open step and returns the step's record. */
    private JSONObject stepAround(Runnable body) throws Exception {
        sink.beginStep(42, 8123L, "com.example.MainActivity", true, "S1");
        body.run();
        sink.flushPendingStep();
        for (String line : new String(captured.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
            JSONObject record = new JSONObject(line);
            if (!record.has("type")) {
                return record;
            }
        }
        throw new AssertionError("no step record was written");
    }

    /** The step's LLM sub-events, in occurrence order. */
    private List<JSONObject> subEvents(JSONObject record) throws Exception {
        List<JSONObject> events = new ArrayList<>();
        JSONArray llm = record.optJSONArray("llm");
        for (int i = 0; llm != null && i < llm.length(); i++) {
            events.add(llm.getJSONObject(i));
        }
        return events;
    }

    /** Runs {@code body} with stdout captured, and returns what the trace received. */
    private static String trace(Runnable body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out, true));
            body.run();
        } finally {
            System.setOut(originalOut);
        }
        return out.toString();
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
    public void theSevenCausesPartitionTheAbandonedAttempts() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> {
            telemetry.screenshotFailed("com.example.MainActivity", "surface_control");
            telemetry.imageFailed();
            telemetry.transportFailed("timeout");
            telemetry.transportFailed("http_500");
            telemetry.transportFailed("connection");
            telemetry.parseFailed();
            telemetry.internalError("boom");
        });
        String summary = captureSummary(telemetry);

        assertEquals("seven abandoned attempts, one counter each", 7, telemetry.getFailureCount());
        assertTrue(summary, summary.contains("screenshot_failed=1"));
        assertTrue(summary, summary.contains("image_error=1"));
        assertTrue(summary, summary.contains("timeout=1"));
        assertTrue(summary, summary.contains("http_error=1"));
        assertTrue(summary, summary.contains("conn_error=1"));
        assertTrue(summary, summary.contains("parse_error=1"));
        assertTrue(summary, summary.contains("internal_error=1"));
        // No abandoned attempt is a decision: the seven are the denominator's failure half, and
        // none of them touched matched/llm_tap/no_match.
        assertTrue(summary, summary.contains("matched=0"));
        assertTrue(summary, summary.contains("no_match=0"));
        assertTrue("the seven are the whole denominator here, got: " + summary,
                summary.contains("(0/7)"));

        // Six sub-events, not seven: the capture failure is the one abandoned attempt that produces
        // none, keeping its peer counter and its free-text line instead.
        List<JSONObject> events = subEvents(record);
        assertEquals(6, events.size());
        List<String> causes = new ArrayList<>();
        for (JSONObject event : events) {
            assertEquals("error", event.getString("result"));
            causes.add(event.getString("cause"));
        }
        assertEquals(java.util.Arrays.asList("image", "timeout", "http_500", "connection", "parse",
                "internal"), causes);
    }

    /**
     * An absent cause is a connection failure, not an eighth kind. The client resets its error seam
     * per call and returns null when it has nothing to discriminate; dropping such an attempt from
     * the partition would leave the denominator short of an attempt that was really made.
     */
    @Test
    public void anUnattributedTransportFailureIsCountedAsAConnectionFailure() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> telemetry.transportFailed(null));

        assertEquals(1, telemetry.getFailureCount());
        assertTrue(captureSummary(telemetry).contains("conn_error=1"));
        assertEquals("connection", subEvents(record).get(0).getString("cause"));
    }

    /** Every HTTP status shares one counter — the status itself stays on the sub-event. */
    @Test
    public void everyHttpStatusLandsOnTheHttpCounter() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> {
            telemetry.transportFailed("http_500");
            telemetry.transportFailed("http_404");
        });

        assertTrue(captureSummary(telemetry).contains("http_error=2"));
        assertEquals("http_500", subEvents(record).get(0).getString("cause"));
        assertEquals("http_404", subEvents(record).get(1).getString("cause"));
    }

    /**
     * An unextractable tool call is counted as a parse failure, the same label an unparseable
     * envelope carries. The two are different sites with different diagnostics and one counter,
     * because both mean the model's answer could not be turned into an action.
     */
    @Test
    public void theTwoParseFailuresShareOneCounterAndKeepTheirOwnDiagnostics() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> {
            telemetry.transportFailed("parse");
            telemetry.parseFailed();
        });

        assertTrue(captureSummary(telemetry).contains("parse_error=2"));
        assertEquals("null response from SGLang", subEvents(record).get(0).getString("detail"));
        assertEquals("no tool call extracted", subEvents(record).get(1).getString("detail"));
    }

    /**
     * A capture failure names the activity, which is what makes per-app {@code FLAG_SECURE}
     * degradation countable offline — the whole reason the branch stopped being silent. It is the
     * one abandoned attempt that stays on the free-text side of the stream: it produces no
     * {@code error} sub-event, so the line is where its activity and stage have to be readable.
     */
    @Test
    public void aCaptureFailureNamesTheActivityAndTheFailingStage() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> telemetry.screenshotFailed("com.example.Secure", "uiautomation"));

        assertEquals(1, telemetry.getScreenshotFailedCount());
        assertTrue(out, out.contains("LLM screenshot capture failed, skipping LLM step"
                + " activity=com.example.Secure detail=uiautomation"));
    }

    /** A capture that named no stage still produces an attributable line. */
    @Test
    public void aCaptureFailureWithoutAStageStillReportsOne() {
        LlmTelemetry telemetry = telemetry();
        String out = trace(() -> telemetry.screenshotFailed("com.example.Main", null));

        assertTrue(out, out.contains("detail=unknown"));
    }

    @Test
    public void aCaptureFailureProducesNoErrorSubEvent() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(
                () -> trace(() -> telemetry.screenshotFailed("com.example.Secure", "uiautomation")));

        assertTrue("the capture failure keeps its counter and its line, and nothing else",
                subEvents(record).isEmpty());
    }

    // -------------------------------------------------------------------------
    // The acknowledgement latch (INV-RTR-12)
    // -------------------------------------------------------------------------

    /**
     * The plan echo records the model the run asked for; the ACK records what answered. It is worth
     * exactly one record per run — a per-response ACK would say the same thing hundreds of times and
     * an absent one would leave a server/plan mismatch undetectable.
     */
    @Test
    public void theServerModelIsAcknowledgedOncePerRun() throws Exception {
        LlmTelemetry telemetry = telemetry();
        telemetry.acknowledge(response("Qwen/Qwen3-VL-4B-Instruct"));
        telemetry.acknowledge(response("Qwen/Qwen3-VL-4B-Instruct"));
        telemetry.acknowledge(response("something-else-entirely"));

        String out = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("three successful responses acknowledge once", 1,
                countOccurrences(out, "\"LLM_ACK\""));
        assertTrue(out, out.contains("\"server_model\":\"Qwen/Qwen3-VL-4B-Instruct\""));
    }

    /** A response whose envelope names no model still acknowledges, saying so. */
    @Test
    public void aResponseWithoutAServerModelAcknowledgesItAsUnknown() throws Exception {
        telemetry().acknowledge(response(null));

        assertTrue(new String(captured.toByteArray(), StandardCharsets.UTF_8)
                .contains("\"server_model\":\"unknown\""));
    }

    // -------------------------------------------------------------------------
    // The per-attempt sub-event
    // -------------------------------------------------------------------------

    @Test
    public void aMatchedDecisionCarriesTheFieldsTheOfflineReaderConsumes() throws Exception {
        LlmTelemetry telemetry = telemetry();
        telemetry.countAttempt();
        JSONObject record = stepAround(() -> telemetry.decision("new-state",
                parsed("click", null, "none"), 540, 958, "matched", null, "Button", nearest(),
                response("m"), 1234L));

        assertEquals("the attempt is attributed by being inside its step's record, not by a key",
                42, record.getInt("s"));
        JSONObject event = subEvents(record).get(0);
        assertEquals("call is the attempt ordinal", 1, event.getInt("call"));
        assertEquals("new-state", event.getString("mode"));
        assertEquals("click", event.getString("tool"));
        assertEquals("the model's own coordinate is kept beside the pixel one",
                "[500,499]", event.getJSONArray("qwen").toString());
        assertEquals("[540,958]", event.getJSONArray("px").toString());
        assertEquals("matched", event.getString("result"));
        assertEquals("Button", event.getString("mcls"));
        assertEquals("Button", event.getString("ncls"));
        assertEquals(7, event.getInt("widgets"));
        assertEquals("[120,30]", event.getJSONArray("tok").toString());
        assertEquals(1234L, event.getLong("ms"));
        assertFalse("a selecting outcome carries no reason", event.has("reason"));
        assertFalse("nor a repair", event.has("repair"));
        assertFalse("the step is the envelope's, never the sub-event's", event.has("step"));
        assertFalse("so is the activity", event.has("activity"));
        assertFalse("and the prompt variant is run-constant, stated once by RUN_START",
                event.has("variant"));
        assertEquals(1, telemetry.getMatchedCount());
    }

    /** Typed text rides the sub-event, escaped by the serializer rather than quoted by hand. */
    @Test
    public void typedTextRidesTheSubEvent() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> telemetry.decision("random",
                parsed("type_text", "hello \"world\"\nsecond line", "none"), 540, 958, "matched",
                null, "EditText", nearest(), response("m"), 10L));

        assertEquals("hello \"world\"\nsecond line", subEvents(record).get(0).getString("text"));
    }

    /**
     * A banned answer is counted twice on purpose: under {@code no_match}, because that is the path
     * it left through, and under {@code dead_pair}, because bucket D is the ban's falsification gate
     * and must be readable from the counters without joining the per-attempt stream.
     */
    @Test
    public void aBannedDecisionIsCountedUnderBothNoMatchAndDeadPair() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> telemetry.decision("new-state",
                parsed("click", null, "none"), 540, 958, "no_match", "dead_pair", "none", nearest(),
                response("m"), 10L));
        String summary = captureSummary(telemetry);

        JSONObject event = subEvents(record).get(0);
        assertEquals("no_match", event.getString("result"));
        assertEquals("dead_pair", event.getString("reason"));
        assertEquals(1, telemetry.getNoMatchCount());
        assertTrue("the overlay is counted, got: " + summary, summary.contains("no_match=1"));
        assertTrue(summary, summary.contains("dead_pair=1"));
        // The overlay does not enter the denominator a second time: one decision, one slot.
        assertTrue("a banned decision is one decision, got: " + summary, summary.contains("(0/1)"));
    }

    /** The other two no_match mechanisms keep their own reasons. */
    @Test
    public void anUnmatchedAnswerNamesWhichMechanismRefusedIt() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> {
            telemetry.decision("random", parsed("click", null, "none"), 0, 0,
                    "no_match", "degenerate", "none", nearest(), response("m"), 10L);
            telemetry.decision("random", parsed("click", null, "none"), 540, 20,
                    "no_match", "boundary", "none", nearest(), response("m"), 10L);
        });

        assertEquals("degenerate", subEvents(record).get(0).getString("reason"));
        assertEquals("boundary", subEvents(record).get(1).getString("reason"));
        assertTrue("neither is a ban", captureSummary(telemetry).contains("dead_pair=0"));
        assertEquals(2, telemetry.getNoMatchCount());
    }

    /**
     * The repair overlay is a property of the sub-event: it is counted only when the sub-event
     * carries {@code repair}, and it never joins the decisions denominator (INV-RTR-13), so the
     * base×v2 tool-call fidelity contrast stays readable from the counters alone.
     */
    @Test
    public void theRepairOverlayIsCountedOnlyWhenTheSubEventCarriesIt() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> {
            telemetry.decision("random", parsed("click", null, "none"), 540, 958,
                    "matched", null, "Button", nearest(), response("m"), 10L);
            telemetry.decision("random", parsed("click", null, "array_xy"), 540, 958,
                    "matched", null, "Button", nearest(), response("m"), 10L);
        });
        String summary = captureSummary(telemetry);

        assertFalse("a clean parse carries no repair", subEvents(record).get(0).has("repair"));
        assertEquals("array_xy", subEvents(record).get(1).getString("repair"));
        assertTrue(summary, summary.contains("repaired=1"));
        // Both decisions matched; the overlay changed no outcome count.
        assertTrue(summary, summary.contains("matched=2"));
        assertTrue("two decisions, not three, got: " + summary, summary.contains("(2/2)"));
    }

    /** An off-tree tap is its own outcome, counted apart from matched and no_match. */
    @Test
    public void anOffTreeTapIsItsOwnOutcome() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> telemetry.decision("stagnation",
                parsed("click", null, "none"), 540, 958, "llm_tap", null, "none", nearest(),
                response("m"), 10L));

        assertEquals("llm_tap", subEvents(record).get(0).getString("result"));
        assertEquals(1, telemetry.getLlmTapCount());
        assertEquals(0, telemetry.getMatchedCount());
        assertEquals(0, telemetry.getNoMatchCount());
    }

    /** Tokens and latency accumulate across decisions; attempts count separately. */
    @Test
    public void tokensAndLatencyAccumulateAcrossDecisions() throws Exception {
        LlmTelemetry telemetry = telemetry();
        telemetry.countAttempt();
        telemetry.countAttempt();
        stepAround(() -> {
            telemetry.decision("random", parsed("click", null, "none"), 540, 958,
                    "matched", null, "Button", nearest(), response("m"), 100L);
            telemetry.decision("random", parsed("click", null, "none"), 540, 958,
                    "matched", null, "Button", nearest(), response("m"), 250L);
        });
        String summary = captureSummary(telemetry);

        assertEquals(2, telemetry.getCallCount());
        assertTrue(summary, summary.contains("calls=2"));
        assertTrue(summary, summary.contains("tokens_in=240"));
        assertTrue(summary, summary.contains("tokens_out=60"));
        assertTrue(summary, summary.contains("time_ms=350"));
    }

    /**
     * A selection retry appends to the step it retried, rather than opening a second record: one
     * step is one record whatever selection did internally (INV-SNK-03).
     */
    @Test
    public void aSelectionRetryAppendsToTheSameStep() throws Exception {
        LlmTelemetry telemetry = telemetry();
        sink.beginStep(42, 8123L, "com.example.MainActivity", true, "S1");
        telemetry.transportFailed("timeout");
        // The BadStateException retry re-runs selection without advancing the agent timestamp.
        sink.beginStep(42, 8140L, "com.example.MainActivity", true, "S1");
        telemetry.decision("new-state", parsed("click", null, "none"), 540, 958, "matched", null,
                "Button", nearest(), response("m"), 10L);
        sink.flushPendingStep();

        List<JSONObject> steps = new ArrayList<>();
        for (String line : new String(captured.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
            JSONObject record = new JSONObject(line);
            if (!record.has("type")) {
                steps.add(record);
            }
        }
        assertEquals("one step, one record", 1, steps.size());
        assertEquals("both attempts are in it", 2, subEvents(steps.get(0)).size());
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
        LlmTelemetry telemetry = new LlmTelemetry(() -> 7, sink, true);
        String out = captureSummary(telemetry);

        assertTrue(out, out.contains("breaker_trips=7"));
    }

    // -------------------------------------------------------------------------
    // The prompt and response dumps
    // -------------------------------------------------------------------------

    /** The prompt is recorded without the image, which is reconstructible and would dwarf the trace. */
    @Test
    public void thePromptRidesTheSubEventWithoutTheImage() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> {
            telemetry.logPrompt(new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT)
                    .build(null, null, null, null, "AAAABBBBCCCC", null));
            telemetry.logResponse(response("m"));
            telemetry.decision("new-state", parsed("click", null, "none"), 540, 958, "matched",
                    null, "Button", nearest(), response("m"), 10L);
        });

        JSONObject event = subEvents(record).get(0);
        assertTrue("the system message is recorded", event.getString("sys").length() > 0);
        assertTrue("and so is the user text", event.getString("user").length() > 0);
        assertEquals("some content", event.getString("resp"));
        assertEquals("0", event.getString("tool_calls"));
        assertFalse("the base64 image is not",
                new String(captured.toByteArray(), StandardCharsets.UTF_8).contains("AAAABBBBCCCC"));
    }

    /**
     * An attempt abandoned before it maps keeps the prompt that produced it — which is what staging
     * the dumps rather than passing them to the decision call buys.
     */
    @Test
    public void anAbandonedAttemptKeepsItsPrompt() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> {
            telemetry.logPrompt(new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT)
                    .build(null, null, null, null, "AAAABBBBCCCC", null));
            telemetry.transportFailed("timeout");
        });

        JSONObject event = subEvents(record).get(0);
        assertEquals("error", event.getString("result"));
        assertTrue("the prompt survives the attempt that died on it",
                event.getString("sys").length() > 0);
        assertFalse("no response exists to record", event.has("resp"));
    }

    /** With the flag off the four fields are absent, and nothing else about the run changes. */
    @Test
    public void theDumpsAreAbsentWhenTheFlagIsOff() throws Exception {
        LlmTelemetry telemetry = new LlmTelemetry(() -> 0, sink, false);
        JSONObject record = stepAround(() -> {
            telemetry.logPrompt(new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT)
                    .build(null, null, null, null, "AAAABBBBCCCC", null));
            telemetry.logResponse(response("m"));
            telemetry.decision("new-state", parsed("click", null, "none"), 540, 958, "matched",
                    null, "Button", nearest(), response("m"), 10L);
        });

        JSONObject event = subEvents(record).get(0);
        for (String field : new String[]{"sys", "user", "resp", "tool_calls"}) {
            assertFalse(field + " must be absent, not empty", event.has(field));
        }
        assertEquals("everything else is identical", "matched", event.getString("result"));
        assertEquals(EventSink.ABSENT, EventSink.ABSENT);
    }
}
