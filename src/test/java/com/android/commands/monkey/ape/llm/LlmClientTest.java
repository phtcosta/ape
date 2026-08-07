package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import com.android.commands.monkey.ape.telemetry.NdjsonSink;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The transport-and-breaker unit: what it sends, what it announces, and the one consultation.
 *
 * <p>Two separable contracts live here because one unit owns both. The wire schema is per request
 * and decided by the same predicate the prompt used (INV-LLM-11). And the breaker is consulted once
 * per decision, with its open episode recorded once — the contract that has no other observer,
 * since a stage returns Continue whether the breaker, a disabled mode or a coin refused it.
 *
 * <p>What the run was asked to send is no longer this unit's to announce: the {@code RUN_START}
 * plan echo states it once, from the same plan these fields are built from.
 */
public class LlmClientTest {

    private static final String URL = "http://localhost:9999/v1";

    private ByteArrayOutputStream sinkBuffer;
    private NdjsonSink sink;

    @Before
    public void setUp() throws Exception {
        sinkBuffer = new ByteArrayOutputStream();
        sink = new NdjsonSink(new PrintStream(sinkBuffer, true, "UTF-8"));
    }

    /** A plan carrying the LLM feature, from which the client takes every value it sends. */
    private static RunSpec.LlmParams llmParams(String... keyValues) {
        String[] withUrl = new String[keyValues.length + 2];
        withUrl[0] = "ape.llmUrl";
        withUrl[1] = URL;
        System.arraycopy(keyValues, 0, withUrl, 2, keyValues.length);
        return TestRunSpecs.spec(withUrl).llm();
    }

    /**
     * A client whose breaker episodes land in {@code sink} — a real {@link NdjsonSink} over a
     * buffer, not a recording double, because what the breaker owes the trace is a sub-event on the
     * step that was running, and only the real sink decides where a sub-event lands.
     */
    private LlmClient client(String... keyValues) {
        return new LlmClient(llmParams(keyValues), sink);
    }

    private static SglangClient sglang() {
        return new SglangClient(URL, "test-model", 0.2, 0.9, 20, 1024, 1000);
    }

    private static List<SglangClient.Message> messages() {
        return Collections.singletonList(new SglangClient.Message("user", "hello"));
    }

    private static final class TestName implements Name {
        private final String xpath;

        TestName(String xpath) { this.xpath = xpath; }

        public Namer getNamer() { return null; }
        public Name getLocalName() { return this; }
        public boolean refinesTo(Name other) { return this.equals(other); }
        public String toXPath() { return xpath; }
        public void appendXPathLocalProperties(StringBuilder sb) { }
        public void toXPath(StringBuilder sb) { sb.append(xpath); }
        public int compareTo(Name other) { return xpath.compareTo(other.toXPath()); }
    }

    private static List<ModelAction> oneActionOn(String className) {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName(className);
        node.setBoundsInScreen(new Rect(50, 300, 400, 350));
        ModelAction action = new ModelAction(null, new TestName("//" + className), ActionType.MODEL_CLICK);
        action.resolveAt(1, 0, null, node, new GUITreeNode[]{node});
        action.setValid(true);
        List<ModelAction> actions = new ArrayList<>();
        actions.add(action);
        return actions;
    }

    private static List<String> toolNames(String requestBody) throws Exception {
        JSONArray tools = new JSONObject(requestBody).getJSONArray("tools");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tools.length(); i++) {
            names.add(tools.getJSONObject(i).getJSONObject("function").getString("name"));
        }
        return names;
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // The schema is per request, not client state
    // -------------------------------------------------------------------------

    @Test
    public void requestBodyCarriesExactlyTheSuppliedSchema() throws Exception {
        SglangClient client = sglang();

        List<String> without = toolNames(client.buildRequestBody(messages(),
                LlmClient.buildToolsSchema(false, ApePromptBuilder.VARIANT_APE_CURRENT)));
        List<String> with = toolNames(client.buildRequestBody(messages(),
                LlmClient.buildToolsSchema(true, ApePromptBuilder.VARIANT_APE_CURRENT)));

        assertFalse("a screen without input fields must not advertise type_text",
                without.contains("type_text"));
        assertTrue(with.contains("type_text"));
        // Everything else is unchanged between the two schemas.
        assertTrue(without.containsAll(Arrays.asList("click", "long_click", "back")));
        assertTrue(with.containsAll(Arrays.asList("click", "long_click", "back")));
    }

    @Test
    public void consecutiveRequestsDoNotInfluenceEachOther() throws Exception {
        // Nothing is cached on the transport between invocations: the second request's schema is
        // the one it was handed, not the one the first request used.
        SglangClient client = sglang();
        client.buildRequestBody(messages(),
                LlmClient.buildToolsSchema(true, ApePromptBuilder.VARIANT_APE_CURRENT));

        assertFalse(toolNames(client.buildRequestBody(messages(),
                LlmClient.buildToolsSchema(false, ApePromptBuilder.VARIANT_APE_CURRENT)))
                .contains("type_text"));
    }

    @Test
    public void reasoningVariantAddsTheParameterToTheTargetedToolsOnly() throws Exception {
        JSONArray tools = LlmClient.buildToolsSchema(true, ApePromptBuilder.VARIANT_APE_REASONING);

        for (int i = 0; i < tools.length(); i++) {
            JSONObject fn = tools.getJSONObject(i).getJSONObject("function");
            boolean hasReasoning = fn.getJSONObject("parameters")
                    .getJSONObject("properties").has("reasoning");
            // back takes no coordinates and nothing to reason about; the other three carry it.
            assertEquals("reasoning on " + fn.getString("name"),
                    !"back".equals(fn.getString("name")), hasReasoning);
        }
    }

    // -------------------------------------------------------------------------
    // Prompt/wire coherence — one predicate decides both halves
    // -------------------------------------------------------------------------

    @Test
    public void screenWithAnInputFieldOffersTypeTextOnBothPromptAndWire() throws Exception {
        List<ModelAction> actions = oneActionOn("android.widget.EditText");
        assertTrue(ApePromptBuilder.hasInputField(actions));

        String systemMessage = new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT)
                .build(null, null, actions, null, null, null).get(0).getTextContent();
        List<String> wire = toolNames(sglang().buildRequestBody(messages(),
                LlmClient.buildToolsSchema(ApePromptBuilder.hasInputField(actions),
                        ApePromptBuilder.VARIANT_APE_CURRENT)));

        assertTrue("the system message lists type_text among its tools",
                systemMessage.contains("type_text(x, y, text)"));
        assertTrue("and so does the wire schema", wire.contains("type_text"));
    }

    @Test
    public void screenWithoutInputFieldsOffersTypeTextOnNeither() throws Exception {
        List<ModelAction> actions = oneActionOn("android.widget.Button");
        assertFalse(ApePromptBuilder.hasInputField(actions));

        String systemMessage = new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT)
                .build(null, null, actions, null, null, null).get(0).getTextContent();
        List<String> wire = toolNames(sglang().buildRequestBody(messages(),
                LlmClient.buildToolsSchema(ApePromptBuilder.hasInputField(actions),
                        ApePromptBuilder.VARIANT_APE_CURRENT)));

        // The coherence contract is about the tool *listing*: the system message's tool list and the
        // wire schema must agree on which tools exist. The ape_current variant's RULES prose still
        // carries a standing hint naming type_text ("Use type_text for input fields with valid
        // data"), which is advice conditioned on there being input fields, not a tool offer.
        assertFalse("the system message's tool list omits type_text",
                systemMessage.contains("type_text(x, y, text)"));
        assertFalse("the wire schema omits it too — the model is never offered a tool the prompt"
                + " says does not exist", wire.contains("type_text"));
    }

    // -------------------------------------------------------------------------
    // The breaker, consulted once and logged once
    // -------------------------------------------------------------------------

    /**
     * The breaker-OPEN episode is recorded once per episode, not once per declined decision, and it
     * is recorded on the step whose selection was declined.
     *
     * <p>This is the half of the LLM's structural fallback (INV-DP-11) that the decision pipeline
     * cannot see. A stage returns Continue whether its trigger was denied by the breaker, by a
     * disabled mode or by a coin, and that is by design — one path for every refusal. So the only
     * place the breaker's own contract is observable is here, in the gate that consults it.
     *
     * <p>An open episode recorded per decision would flood a run's trace: the breaker stays open
     * for a 60-second window, which at a normal step rate is dozens of declines. The latch makes
     * the sub-event mean "the breaker just opened", and re-arming it when the breaker next allows
     * an attempt is what lets a second trip say so again.
     *
     * <p>The two episodes are driven in two different steps because that is the claim the trace has
     * to support and a single-step version could not distinguish: the sub-event belongs to the step
     * of the <em>first</em> decline, which is how a reader attributes a stalled stretch of a run to
     * the breaker without a {@code step=} key to join on. The free-text line survives the
     * re-encoding and is asserted alongside — it is what an operator watching stdout reads.
     */
    @Test
    public void breakerOpenIsRecordedOncePerOpenEpisodeOnTheStepThatWasDeclined() throws Exception {
        final LlmClient client = client();
        final LlmCircuitBreaker breaker = client.getBreaker();

        String trace = trace(new Runnable() {
            @Override public void run() {
                sink.beginStep(10, 1000L, "com.example.MainActivity", true, "S1");
                trip(breaker);
                for (int i = 0; i < 5; i++) {
                    assertFalse(client.allows());
                }

                // The breaker allows again, which is what re-arms the latch — a fresh episode may
                // say so, and the same episode may not.
                breaker.recordSuccess();
                assertTrue(client.allows());

                sink.beginStep(11, 2000L, "com.example.MainActivity", true, "S1");
                trip(breaker);
                for (int i = 0; i < 5; i++) {
                    assertFalse(client.allows());
                }
                sink.flushPendingStep();
            }
        });

        List<JSONObject> steps = stepRecords();
        assertEquals("one record per step, whatever the breaker did inside it", 2, steps.size());
        assertEquals("ten declined decisions across two open episodes are two sub-events, not ten",
                Arrays.asList(1, 2), breakerTrips(steps.get(0), steps.get(1)));

        assertEquals("the free-text line keeps its own once-per-episode discipline",
                2, countOccurrences(trace, "LLM circuit breaker OPEN"));
        assertTrue("the second line reports the second trip; got: " + trace,
                trace.contains("(trips=1)") && trace.contains("(trips=2)"));
    }

    /**
     * The breaker sub-events of each record in order, as their trip counts.
     *
     * <p>Asserting the trip count rather than a bare count is what separates "recorded twice"
     * from "recorded once per episode": a latch that never re-armed would still produce two
     * sub-events if it were reset by the second {@code beginStep}, and both would say
     * {@code trips=1}.
     */
    private static List<Integer> breakerTrips(JSONObject... records) throws Exception {
        List<Integer> trips = new ArrayList<>();
        for (JSONObject record : records) {
            JSONArray llm = record.optJSONArray("llm");
            for (int i = 0; llm != null && i < llm.length(); i++) {
                JSONObject event = llm.getJSONObject(i);
                assertEquals("the only sub-event a declined step has is the breaker's",
                        "breaker_open", event.getString("result"));
                trips.add(event.getInt("trips"));
            }
        }
        return trips;
    }

    /** The step records written to the sink's buffer, in order; run-level records are skipped. */
    private List<JSONObject> stepRecords() throws Exception {
        List<JSONObject> records = new ArrayList<>();
        String written = new String(sinkBuffer.toByteArray(), StandardCharsets.UTF_8);
        for (String line : written.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            JSONObject record = new JSONObject(line);
            if (!record.has("type")) {
                records.add(record);
            }
        }
        return records;
    }

    /** Three consecutive failures, which is the default threshold. */
    private static void trip(LlmCircuitBreaker breaker) {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals("OPEN", breaker.getStateName());
    }
}
