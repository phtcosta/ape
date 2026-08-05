package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.telemetry.NdjsonSink;
import com.android.commands.monkey.ape.telemetry.RunCounters;

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

    /**
     * The {@code counters.llm} block of the {@code RUN_END} record these totals produce.
     *
     * <p>The counters are asserted through the record rather than off the value object, for the
     * same reason the sub-events are: what a run owes an offline reader is a field in the trace
     * under the name that reader looks for, and the value object alone cannot say whether that
     * name was written.
     */
    private JSONObject llmCounters(LlmTelemetry telemetry) throws Exception {
        sink.runEnd("timeout", null, telemetry.counters());
        for (String line : new String(captured.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
            JSONObject record = new JSONObject(line);
            if ("RUN_END".equals(record.optString("type"))) {
                return record.getJSONObject("counters").getJSONObject("llm");
            }
        }
        throw new AssertionError("no RUN_END record was written");
    }

    /**
     * The decisions denominator, derived here exactly as a consumer derives it: the three completed
     * outcomes plus the seven abandoned causes. No ratio is stored in the record, so this is the
     * only place it exists.
     */
    private static int decisions(RunCounters counters) {
        return counters.matched + counters.llmTap + counters.noMatch + counters.failures();
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
    // The counter block of RUN_END, and its decisions denominator
    // -------------------------------------------------------------------------

    @Test
    public void everyCounterIsNamedInTheRecordEvenAtZero() throws Exception {
        JSONObject llm = llmCounters(telemetry());

        // A run that abandoned nothing still names all seventeen counters, each at zero: an offline
        // reader joins on the field being present, so an absent field and a zero one are different
        // facts — which is why this block is exempt from the record's default-omission rule.
        for (String field : new String[]{"calls", "tok_in", "tok_out", "ms", "matched", "llm_tap",
                "no_match", "dead_pair", "repaired", "timeout", "http_error", "conn_error",
                "parse_error", "image_error", "internal_error", "screenshot_failed",
                "breaker_trips"}) {
            assertTrue("the counter block must name " + field + ", got: " + llm, llm.has(field));
            assertEquals(field + " must be zero on a run that did nothing", 0, llm.getInt(field));
        }
        assertEquals("seventeen counters and nothing else", 17, llm.length());
        // screenshot_failed is a peer cause, not a subset of an aggregate (INV-RTR-11), and the
        // per-cause breakdown is what replaced a single null count — no such field survives.
        assertFalse(llm.has("null"));
    }

    @Test
    public void llmTapJoinsDecisionsDenominator() throws Exception {
        LlmTelemetry telemetry = telemetry();
        // 1 widget match + 1 off-tree tap, nothing else. decisions = 1 + 1 + 0 + 0 = 2. If llm_tap
        // were omitted from the denominator it would read 1, and the match rate a consumer computes
        // would read 100% instead of 50% — this pins the stability of that denominator.
        setCounter(telemetry, "matchedCount", 1);
        setCounter(telemetry, "llmTapCount", 1);

        assertEquals(1, llmCounters(telemetry).getInt("llm_tap"));
        assertEquals("an off-tree tap stays inside the denominator", 2,
                decisions(telemetry.counters()));
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
        JSONObject llm = llmCounters(telemetry);

        assertEquals("seven abandoned attempts, one counter each", 7,
                telemetry.counters().failures());
        for (String cause : new String[]{"screenshot_failed", "image_error", "timeout",
                "http_error", "conn_error", "parse_error", "internal_error"}) {
            assertEquals(cause + " must have fired exactly once, got: " + llm, 1,
                    llm.getInt(cause));
        }
        // No abandoned attempt is a decision: the seven are the denominator's failure half, and
        // none of them touched matched/llm_tap/no_match.
        assertEquals(0, llm.getInt("matched"));
        assertEquals(0, llm.getInt("no_match"));
        assertEquals("the seven are the whole denominator here", 7, decisions(telemetry.counters()));

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

        assertEquals(1, telemetry.counters().failures());
        assertEquals(1, llmCounters(telemetry).getInt("conn_error"));
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

        assertEquals(2, llmCounters(telemetry).getInt("http_error"));
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

        assertEquals(2, llmCounters(telemetry).getInt("parse_error"));
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

        assertEquals(1, telemetry.counters().screenshotFailed);
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
        assertEquals(1, telemetry.counters().matched);
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
        JSONObject llm = llmCounters(telemetry);

        JSONObject event = subEvents(record).get(0);
        assertEquals("no_match", event.getString("result"));
        assertEquals("dead_pair", event.getString("reason"));
        assertEquals(1, llm.getInt("no_match"));
        assertEquals("the overlay is counted beside the outcome", 1, llm.getInt("dead_pair"));
        // The overlay does not enter the denominator a second time: one decision, one slot.
        assertEquals("a banned decision is one decision", 1, decisions(telemetry.counters()));
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
        assertEquals("neither is a ban", 0, llmCounters(telemetry).getInt("dead_pair"));
        assertEquals(2, telemetry.counters().noMatch);
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
        JSONObject llm = llmCounters(telemetry);

        assertFalse("a clean parse carries no repair", subEvents(record).get(0).has("repair"));
        assertEquals("array_xy", subEvents(record).get(1).getString("repair"));
        assertEquals(1, llm.getInt("repaired"));
        // Both decisions matched; the overlay changed no outcome count.
        assertEquals(2, llm.getInt("matched"));
        assertEquals("two decisions, not three", 2, decisions(telemetry.counters()));
    }

    /** An off-tree tap is its own outcome, counted apart from matched and no_match. */
    @Test
    public void anOffTreeTapIsItsOwnOutcome() throws Exception {
        LlmTelemetry telemetry = telemetry();
        JSONObject record = stepAround(() -> telemetry.decision("stagnation",
                parsed("click", null, "none"), 540, 958, "llm_tap", null, "none", nearest(),
                response("m"), 10L));

        assertEquals("llm_tap", subEvents(record).get(0).getString("result"));
        assertEquals(1, telemetry.counters().llmTap);
        assertEquals(0, telemetry.counters().matched);
        assertEquals(0, telemetry.counters().noMatch);
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
        JSONObject llm = llmCounters(telemetry);

        assertEquals(2, llm.getInt("calls"));
        assertEquals(240, llm.getInt("tok_in"));
        assertEquals(60, llm.getInt("tok_out"));
        assertEquals(350L, llm.getLong("ms"));
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
     * The counters report the run's trip count by asking for it, not by mirroring it. The mirrored
     * form would refresh a field at each failure site; since only a failure raises the count, the
     * value is the same, and asking removes a second place for it to live.
     */
    @Test
    public void theCountersReportTheBreakersOwnTripCount() throws Exception {
        LlmTelemetry telemetry = new LlmTelemetry(() -> 7, sink, true);

        assertEquals(7, llmCounters(telemetry).getInt("breaker_trips"));
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
