package com.android.commands.monkey.ape.llm;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for SglangClient and its inner classes.
 *
 * <p>SglangClient talks to an HTTP server; tests that require a real (or mock)
 * server are NOT included. What IS testable without a server:
 * <ul>
 *   <li>Message construction — text-only and multimodal variants.</li>
 *   <li>ContentPart factory methods — type, text, imageUrl fields.</li>
 *   <li>ChatResponse construction and accessors.</li>
 *   <li>ToolCall construction and accessors.</li>
 *   <li>buildRequestBody() — package-private, produces valid JSON with
 *       correct structure for both plain-text and multimodal messages.</li>
 *   <li>parseResponse() — package-private, parses known-good JSON strings.</li>
 *   <li>parseResponse() with missing/null fields — graceful handling.</li>
 * </ul>
 */
public class SglangClientTest {

    private static SglangClient makeClient() {
        return new SglangClient(
                "http://localhost:9999/v1",  // not reachable — only used by sendRequest()
                "test-model",
                0.5,   // temperature
                0.9,   // top_p
                40,    // top_k
                512,   // max_tokens
                5000   // timeout_ms
        );
    }

    // -------------------------------------------------------------------------
    // Message construction
    // -------------------------------------------------------------------------

    @Test
    public void message_textOnly_roleAndContent() {
        SglangClient.Message msg = new SglangClient.Message("system", "Hello, system.");
        assertEquals("system", msg.getRole());
        assertEquals("Hello, system.", msg.getTextContent());
        assertNull("text-only message must have null contentParts", msg.getContentParts());
    }

    @Test
    public void message_multimodal_roleAndParts() {
        List<SglangClient.ContentPart> parts = Arrays.asList(
                SglangClient.ContentPart.imageUrl("data:image/jpeg;base64,abc"),
                SglangClient.ContentPart.text("describe this")
        );
        SglangClient.Message msg = new SglangClient.Message("user", parts);
        assertEquals("user", msg.getRole());
        assertNull("multimodal message must have null textContent", msg.getTextContent());
        assertEquals(2, msg.getContentParts().size());
    }

    // -------------------------------------------------------------------------
    // ContentPart factory methods
    // -------------------------------------------------------------------------

    @Test
    public void contentPart_text_typeAndText() {
        SglangClient.ContentPart part = SglangClient.ContentPart.text("some text");
        assertEquals("text", part.getType());
        assertEquals("some text", part.getText());
        assertNull(part.getImageUrl());
    }

    @Test
    public void contentPart_imageUrl_typeAndUrl() {
        SglangClient.ContentPart part = SglangClient.ContentPart.imageUrl("data:image/jpeg;base64,xyz");
        assertEquals("image_url", part.getType());
        assertEquals("data:image/jpeg;base64,xyz", part.getImageUrl());
        assertNull(part.getText());
    }

    // -------------------------------------------------------------------------
    // ChatResponse and ToolCall accessors
    // -------------------------------------------------------------------------

    @Test
    public void chatResponse_accessors() {
        SglangClient.ToolCall tc = new SglangClient.ToolCall("click", Collections.singletonMap("x", (Object)500.0));
        SglangClient.ChatResponse resp = new SglangClient.ChatResponse(
                "content text",
                Collections.singletonList(tc),
                120, 30);

        assertEquals("content text", resp.getContent());
        assertEquals(1,   resp.getToolCalls().size());
        assertEquals(120, resp.getPromptTokens());
        assertEquals(30,  resp.getCompletionTokens());
    }

    @Test
    public void chatResponse_nullToolCalls_returnsEmptyList() {
        SglangClient.ChatResponse resp = new SglangClient.ChatResponse("text", null, 0, 0);
        assertNotNull(resp.getToolCalls());
        assertTrue(resp.getToolCalls().isEmpty());
    }

    @Test
    public void toolCall_accessors() {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("x", 300.0);
        args.put("y", 700.0);
        SglangClient.ToolCall tc = new SglangClient.ToolCall("click", args);
        assertEquals("click", tc.getName());
        assertEquals(300.0, tc.getArguments().get("x"));
        assertEquals(700.0, tc.getArguments().get("y"));
    }

    // -------------------------------------------------------------------------
    // buildRequestBody — package-private, pure JSON construction (no HTTP)
    // -------------------------------------------------------------------------

    @Test
    public void buildRequestBody_containsModelAndTemperature() {
        SglangClient client = makeClient();
        List<SglangClient.Message> messages = Collections.singletonList(
                new SglangClient.Message("user", "hello"));

        String body = client.buildRequestBody(messages);

        assertNotNull(body);
        assertTrue("body must contain model field",       body.contains("\"model\""));
        assertTrue("body must contain model value",       body.contains("test-model"));
        assertTrue("body must contain temperature field", body.contains("\"temperature\""));
        assertTrue("body must contain max_tokens field",  body.contains("\"max_tokens\""));
    }

    @Test
    public void buildRequestBody_plainTextMessage_contentIsString() {
        SglangClient client = makeClient();
        List<SglangClient.Message> messages = Collections.singletonList(
                new SglangClient.Message("system", "You are a test agent."));

        String body = client.buildRequestBody(messages);

        assertTrue("system role must appear in body",     body.contains("\"system\""));
        assertTrue("message text must appear in body",    body.contains("You are a test agent."));
    }

    @Test
    public void buildRequestBody_multimodalMessage_contentIsArray() {
        SglangClient client = makeClient();
        List<SglangClient.ContentPart> parts = Arrays.asList(
                SglangClient.ContentPart.imageUrl("data:image/jpeg;base64,abc"),
                SglangClient.ContentPart.text("what do you see?")
        );
        List<SglangClient.Message> messages = Collections.singletonList(
                new SglangClient.Message("user", parts));

        String body = client.buildRequestBody(messages);

        assertTrue("body must contain image_url type",   body.contains("\"image_url\""));
        assertTrue("body must contain image data",       body.contains("abc"));
        assertTrue("body must contain text part",        body.contains("what do you see?"));
    }

    // -------------------------------------------------------------------------
    // parseResponse — package-private, parses JSON strings (no HTTP)
    // -------------------------------------------------------------------------

    @Test
    public void parseResponse_standardFormat_contentExtracted() {
        SglangClient client = makeClient();
        String json = "{"
                + "\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"{\\\"name\\\":\\\"click\\\",\\\"arguments\\\":{\\\"x\\\":500,\\\"y\\\":750}}\"}}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}"
                + "}";

        SglangClient.ChatResponse resp = client.parseResponse(json);

        assertNotNull(resp);
        assertNotNull(resp.getContent());
        assertFalse("content must not be empty", resp.getContent().isEmpty());
        assertEquals(100, resp.getPromptTokens());
        assertEquals(20,  resp.getCompletionTokens());
    }

    @Test
    public void parseResponse_withToolCalls_toolCallsParsed() {
        SglangClient client = makeClient();
        String json = "{"
                + "\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":null,"
                + "\"tool_calls\":[{\"function\":{\"name\":\"click\","
                + "\"arguments\":{\"x\":400,\"y\":600}}}]}}],"
                + "\"usage\":{\"prompt_tokens\":80,\"completion_tokens\":15}"
                + "}";

        SglangClient.ChatResponse resp = client.parseResponse(json);

        assertNotNull(resp);
        assertFalse("tool_calls must be non-empty", resp.getToolCalls().isEmpty());
        assertEquals("click", resp.getToolCalls().get(0).getName());
    }

    @Test
    public void parseResponse_usageMissing_tokenCountsAreZero() {
        SglangClient client = makeClient();
        String json = "{"
                + "\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"some text\"}}]"
                + "}";

        SglangClient.ChatResponse resp = client.parseResponse(json);

        assertNotNull(resp);
        assertEquals("prompt_tokens must default to 0 when usage is absent",    0, resp.getPromptTokens());
        assertEquals("completion_tokens must default to 0 when usage is absent", 0, resp.getCompletionTokens());
    }

    @Test(expected = LlmException.class)
    public void parseResponse_emptyChoices_throwsLlmException() {
        SglangClient client = makeClient();
        String json = "{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}";
        // Empty choices array → LlmException
        client.parseResponse(json);
    }

    @Test(expected = LlmException.class)
    public void parseResponse_malformedJson_throwsLlmException() {
        SglangClient client = makeClient();
        client.parseResponse("not-json-at-all");
    }

    // -------------------------------------------------------------------------
    // llm-native-toolcall-repair (INV-LLM-10): raw-arguments preservation.
    //
    // SglangClient.parseResponse must carry the tool-call arguments in raw string
    // form on the constructed ToolCall — the string verbatim when arguments is a
    // JSON string (even when unparseable), the object's serialization when it is a
    // JSON object, null when absent. This is what lets ToolCallParser Level 1 run
    // the same repair pipeline the XML path uses.
    // -------------------------------------------------------------------------

    @Test
    public void parseResponse_malformedArgumentsString_mapEmptyRawVerbatim() {
        // Dominant Qwen3-VL degenerate form: arguments encoded as a JSON string that is
        // itself invalid JSON (missing "y" key). The map must stay empty (parse fails), but
        // the raw string must survive verbatim for downstream repair.
        SglangClient client = makeClient();
        String json = "{"
                + "\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":null,"
                + "\"tool_calls\":[{\"function\":{\"name\":\"click\","
                + "\"arguments\":\"{\\\"x\\\": 616, 891}\"}}]}}]"
                + "}";

        SglangClient.ChatResponse resp = client.parseResponse(json);

        assertNotNull(resp);
        assertFalse("tool_calls must be non-empty", resp.getToolCalls().isEmpty());
        SglangClient.ToolCall tc = resp.getToolCalls().get(0);
        assertEquals("click", tc.getName());
        assertTrue("malformed arguments string must leave the map empty",
                tc.getArguments().isEmpty());
        assertEquals("raw arguments must survive verbatim",
                "{\"x\": 616, 891}", tc.getRawArguments());
    }

    @Test
    public void parseResponse_wellFormedArgumentsString_mapPopulatedRawVerbatim() {
        SglangClient client = makeClient();
        String json = "{"
                + "\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":null,"
                + "\"tool_calls\":[{\"function\":{\"name\":\"click\","
                + "\"arguments\":\"{\\\"x\\\": 540, \\\"y\\\": 399}\"}}]}}]"
                + "}";

        SglangClient.ChatResponse resp = client.parseResponse(json);

        assertNotNull(resp);
        SglangClient.ToolCall tc = resp.getToolCalls().get(0);
        assertEquals(540.0, ((Number) tc.getArguments().get("x")).doubleValue(), 1e-9);
        assertEquals(399.0, ((Number) tc.getArguments().get("y")).doubleValue(), 1e-9);
        assertEquals("raw arguments must be the string verbatim",
                "{\"x\": 540, \"y\": 399}", tc.getRawArguments());
    }

    @Test
    public void parseResponse_argumentsObject_mapPopulatedRawIsObjectSerialization() throws Exception {
        // arguments encoded as a JSON object (not a string). Map populated as before; raw is
        // the object's JSON serialization (re-parseable to the same coordinates).
        SglangClient client = makeClient();
        String json = "{"
                + "\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":null,"
                + "\"tool_calls\":[{\"function\":{\"name\":\"click\","
                + "\"arguments\":{\"x\":400,\"y\":600}}}]}}]"
                + "}";

        SglangClient.ChatResponse resp = client.parseResponse(json);

        SglangClient.ToolCall tc = resp.getToolCalls().get(0);
        assertEquals(400.0, ((Number) tc.getArguments().get("x")).doubleValue(), 1e-9);
        assertEquals(600.0, ((Number) tc.getArguments().get("y")).doubleValue(), 1e-9);
        assertNotNull("raw arguments must be the object serialization, not null",
                tc.getRawArguments());
        org.json.JSONObject reparsed = new org.json.JSONObject(tc.getRawArguments());
        assertEquals(400, reparsed.getInt("x"));
        assertEquals(600, reparsed.getInt("y"));
    }

    @Test
    public void parseResponse_absentArguments_rawIsNull() {
        SglangClient client = makeClient();
        String json = "{"
                + "\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":null,"
                + "\"tool_calls\":[{\"function\":{\"name\":\"back\"}}]}}]"
                + "}";

        SglangClient.ChatResponse resp = client.parseResponse(json);

        SglangClient.ToolCall tc = resp.getToolCalls().get(0);
        assertEquals("back", tc.getName());
        assertNull("absent arguments must give null raw", tc.getRawArguments());
    }

    @Test
    public void toolCall_twoArgConstructor_rawIsNull() {
        SglangClient.ToolCall tc =
                new SglangClient.ToolCall("click", Collections.singletonMap("x", (Object) 540.0));
        assertNull("2-arg constructor must leave raw null", tc.getRawArguments());
    }
}
