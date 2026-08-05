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
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import com.android.commands.monkey.ape.telemetry.NoopSink;

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

    /** A plan carrying the LLM feature, from which the client takes every value it sends. */
    private static RunSpec.LlmParams llmParams(String... keyValues) {
        String[] withUrl = new String[keyValues.length + 2];
        withUrl[0] = "ape.llmUrl";
        withUrl[1] = URL;
        System.arraycopy(keyValues, 0, withUrl, 2, keyValues.length);
        return TestRunSpecs.spec(withUrl).llm();
    }

    private static LlmClient client(String... keyValues) {
        return new LlmClient(llmParams(keyValues), new NoopSink());
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
     * The breaker-OPEN line is emitted once per open episode, not once per declined decision.
     *
     * <p>This is the half of the LLM's structural fallback (INV-DP-11) that the decision pipeline
     * cannot see. A stage returns Continue whether its trigger was denied by the breaker, by a
     * disabled mode or by a coin, and that is by design — one path for every refusal. So the only
     * place the breaker's own contract is observable is here, in the gate that consults it.
     *
     * <p>An open episode that logged per decision would flood a run's trace: the breaker stays open
     * for a 60-second window, which at a normal step rate is dozens of declines. The latch makes
     * the line mean "the breaker just opened", and re-arming it when the breaker next allows an
     * attempt is what lets a second trip say so again.
     */
    @Test
    public void breakerOpenIsLoggedOncePerOpenEpisode() {
        final LlmClient client = client();
        final LlmCircuitBreaker breaker = client.getBreaker();

        String trace = trace(new Runnable() {
            @Override public void run() {
                trip(breaker);
                for (int i = 0; i < 5; i++) {
                    assertFalse(client.allows());
                }

                // The breaker allows again, which is what re-arms the latch — a fresh episode may
                // say so, and the same episode may not.
                breaker.recordSuccess();
                assertTrue(client.allows());

                trip(breaker);
                for (int i = 0; i < 5; i++) {
                    assertFalse(client.allows());
                }
            }
        });

        assertEquals("ten declined decisions across two open episodes must log twice, not ten times",
                2, countOccurrences(trace, "LLM circuit breaker OPEN"));
        assertTrue("the second line reports the second trip; got: " + trace,
                trace.contains("(trips=1)") && trace.contains("(trips=2)"));
    }

    /** Three consecutive failures, which is the default threshold. */
    private static void trip(LlmCircuitBreaker breaker) {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals("OPEN", breaker.getStateName());
    }
}
