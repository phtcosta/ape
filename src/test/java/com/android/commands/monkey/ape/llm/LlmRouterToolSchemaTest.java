package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * B6(iii) per-request tool schema (llm-infrastructure INV-LLM-11, llm-routing "LlmRouter
 * Lifecycle") — the schema travels with the request, and the tool the wire advertises is decided by
 * the same predicate that decides what the system message says exists.
 */
public class LlmRouterToolSchemaTest {

    private static SglangClient client() {
        return new SglangClient("http://localhost:9999/v1", "test-model",
                0.2, 0.9, 20, 1024, 1000);
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

    // -------------------------------------------------------------------------
    // The schema is per request, not client state
    // -------------------------------------------------------------------------

    @Test
    public void requestBodyCarriesExactlyTheSuppliedSchema() throws Exception {
        SglangClient client = client();

        List<String> without = toolNames(
                client.buildRequestBody(messages(), LlmRouter.buildToolsSchema(false)));
        List<String> with = toolNames(
                client.buildRequestBody(messages(), LlmRouter.buildToolsSchema(true)));

        assertFalse("a screen without input fields must not advertise type_text",
                without.contains("type_text"));
        assertTrue(with.contains("type_text"));
        // Everything else is unchanged between the two schemas.
        assertTrue(without.containsAll(java.util.Arrays.asList("click", "long_click", "back")));
        assertTrue(with.containsAll(java.util.Arrays.asList("click", "long_click", "back")));
    }

    @Test
    public void consecutiveRequestsDoNotInfluenceEachOther() throws Exception {
        // Nothing is cached on the client between invocations: the second request's schema is the
        // one it was handed, not the one the first request used.
        SglangClient client = client();
        client.buildRequestBody(messages(), LlmRouter.buildToolsSchema(true));

        assertFalse(toolNames(client.buildRequestBody(messages(), LlmRouter.buildToolsSchema(false)))
                .contains("type_text"));
    }

    // -------------------------------------------------------------------------
    // Prompt/wire coherence — one predicate decides both halves
    // -------------------------------------------------------------------------

    @Test
    public void screenWithAnInputFieldOffersTypeTextOnBothPromptAndWire() throws Exception {
        List<ModelAction> actions = oneActionOn("android.widget.EditText");
        assertTrue(ApePromptBuilder.hasInputField(actions));

        String systemMessage = new ApePromptBuilder()
                .build(null, null, actions, null, null, null).get(0).getTextContent();
        List<String> wire = toolNames(client().buildRequestBody(messages(),
                LlmRouter.buildToolsSchema(ApePromptBuilder.hasInputField(actions))));

        assertTrue("the system message lists type_text among its tools",
                systemMessage.contains("type_text(x, y, text)"));
        assertTrue("and so does the wire schema", wire.contains("type_text"));
    }

    @Test
    public void screenWithoutInputFieldsOffersTypeTextOnNeither() throws Exception {
        List<ModelAction> actions = oneActionOn("android.widget.Button");
        assertFalse(ApePromptBuilder.hasInputField(actions));

        String systemMessage = new ApePromptBuilder()
                .build(null, null, actions, null, null, null).get(0).getTextContent();
        List<String> wire = toolNames(client().buildRequestBody(messages(),
                LlmRouter.buildToolsSchema(ApePromptBuilder.hasInputField(actions))));

        // The coherence contract is about the tool *listing*: the system message's tool list and the
        // wire schema must agree on which tools exist. The ape_current variant's RULES prose still
        // carries a standing hint naming type_text ("Use type_text for input fields with valid
        // data"), which is advice conditioned on there being input fields, not a tool offer.
        assertFalse("the system message's tool list omits type_text",
                systemMessage.contains("type_text(x, y, text)"));
        assertFalse("the wire schema omits it too — the model is never offered a tool the prompt"
                + " says does not exist", wire.contains("type_text"));
    }
}
