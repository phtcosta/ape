package com.android.commands.monkey.ape.llm;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for ToolCallParser — exercises all three parse paths and the
 * Qwen3-VL malformed-JSON fixup logic.
 *
 * Tests run on the JVM only; no Android device or SGLang server required.
 */
public class ToolCallParserTest {

    private ToolCallParser parser;

    @Before
    public void setUp() {
        parser = new ToolCallParser();
    }

    // ---------------------------------------------------------------------------
    // Helper to build a ChatResponse with only text content (no native tool calls)
    // ---------------------------------------------------------------------------

    private SglangClient.ChatResponse responseWithContent(String content) {
        return new SglangClient.ChatResponse(
                content,
                Collections.<SglangClient.ToolCall>emptyList(),
                0, 0);
    }

    // Helper to build a ChatResponse with a native tool call
    private SglangClient.ChatResponse responseWithToolCall(String name, Map<String, Object> args) {
        SglangClient.ToolCall tc = new SglangClient.ToolCall(name, args);
        return new SglangClient.ChatResponse(
                "",
                Collections.singletonList(tc),
                0, 0);
    }

    // ---------------------------------------------------------------------------
    // Test 1: Native tool_calls list — click action
    // ---------------------------------------------------------------------------
    @Test
    public void testNativeToolCall_click() {
        Map<String, Object> args = new HashMap<>();
        args.put("x", 540.0);
        args.put("y", 399.0);

        SglangClient.ChatResponse response = responseWithToolCall("click", args);
        ToolCallParser.ParsedAction action = parser.parse(response);

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(540, action.getX());
        assertEquals(399, action.getY());
    }

    // ---------------------------------------------------------------------------
    // Test 2: XML tag format — <tool_call>...</tool_call>
    // ---------------------------------------------------------------------------
    @Test
    public void testXmlTagFormat_click() {
        String content = "<tool_call>{\"name\": \"click\", \"arguments\": {\"x\": 540, \"y\": 399}}</tool_call>";
        SglangClient.ChatResponse response = responseWithContent(content);
        ToolCallParser.ParsedAction action = parser.parse(response);

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(540, action.getX());
        assertEquals(399, action.getY());
    }

    // ---------------------------------------------------------------------------
    // Test 3: XML tag format — <function_call> variant
    // ---------------------------------------------------------------------------
    @Test
    public void testXmlTagFormat_functionCallVariant() {
        String content = "<function_call>{\"name\": \"long_click\", \"arguments\": {\"x\": 200, \"y\": 300}}</function_call>";
        SglangClient.ChatResponse response = responseWithContent(content);
        ToolCallParser.ParsedAction action = parser.parse(response);

        assertNotNull(action);
        assertEquals("long_click", action.getActionType());
        assertEquals(200, action.getX());
        assertEquals(300, action.getY());
    }

    // ---------------------------------------------------------------------------
    // Test 4: Inline JSON format (no XML tags, JSON embedded in text)
    // ---------------------------------------------------------------------------
    @Test
    public void testInlineJsonFormat_click() {
        String content = "I will click the login button. " +
                "{\"name\": \"click\", \"arguments\": {\"x\": 100, \"y\": 200}}";
        SglangClient.ChatResponse response = responseWithContent(content);
        ToolCallParser.ParsedAction action = parser.parse(response);

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(100, action.getX());
        assertEquals(200, action.getY());
    }

    // ---------------------------------------------------------------------------
    // Test 5: Qwen3-VL malformed JSON — missing "y" key: {"x": 540, 399}
    // ---------------------------------------------------------------------------
    @Test
    public void testFixMalformedJson_missingYKey() {
        String malformed = "{\"name\": \"click\", \"arguments\": {\"x\": 540, 399}}";
        String fixed = ToolCallParser.fixMalformedJson(malformed);
        // After fix the string should contain "y": 399
        assertTrue("fixed JSON should contain \"y\": 399", fixed.contains("\"y\": 399"));
    }

    @Test
    public void testParse_missingYKey_viaContent() {
        String content = "{\"name\": \"click\", \"arguments\": {\"x\": 540, 399}}";
        SglangClient.ChatResponse response = responseWithContent(content);
        ToolCallParser.ParsedAction action = parser.parse(response);

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(540, action.getX());
        assertEquals(399, action.getY());
    }

    // ---------------------------------------------------------------------------
    // Test 6: Qwen3-VL malformed JSON — array format: {"x": [540, 399]}
    // ---------------------------------------------------------------------------
    @Test
    public void testFixMalformedJson_arrayCoords() {
        String malformed = "{\"name\": \"click\", \"arguments\": {\"x\": [540, 399]}}";
        String fixed = ToolCallParser.fixMalformedJson(malformed);
        assertTrue("fixed JSON should contain \"x\": 540", fixed.contains("\"x\": 540"));
        assertTrue("fixed JSON should contain \"y\": 399", fixed.contains("\"y\": 399"));
    }

    @Test
    public void testParse_arrayCoords_viaContent() {
        String content = "{\"name\": \"click\", \"arguments\": {\"x\": [540, 399]}}";
        SglangClient.ChatResponse response = responseWithContent(content);
        ToolCallParser.ParsedAction action = parser.parse(response);

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(540, action.getX());
        assertEquals(399, action.getY());
    }

    // ---------------------------------------------------------------------------
    // Test 7: Qwen3-VL malformed JSON — missing leading zero: ": .91"
    // ---------------------------------------------------------------------------
    @Test
    public void testFixMalformedJson_missingLeadingZero() {
        String malformed = "{\"confidence\": .91}";
        String fixed = ToolCallParser.fixMalformedJson(malformed);
        assertTrue("fixed JSON should contain 0.91", fixed.contains("0.91"));
    }

    // ---------------------------------------------------------------------------
    // Test 8: type_text extraction with text field
    // ---------------------------------------------------------------------------
    @Test
    public void testTypeText_xmlFormat() {
        String content = "<tool_call>{\"name\": \"type_text\", \"arguments\": {\"x\": 300, \"y\": 500, \"text\": \"hello\"}}</tool_call>";
        SglangClient.ChatResponse response = responseWithContent(content);
        ToolCallParser.ParsedAction action = parser.parse(response);

        assertNotNull(action);
        assertEquals("type_text", action.getActionType());
        assertEquals(300, action.getX());
        assertEquals(500, action.getY());
        assertEquals("hello", action.getText());
    }

    // ---------------------------------------------------------------------------
    // Test 9: long_click extraction
    // ---------------------------------------------------------------------------
    @Test
    public void testLongClick_nativeToolCall() {
        Map<String, Object> args = new HashMap<>();
        args.put("x", 750.0);
        args.put("y", 960.0);

        SglangClient.ChatResponse response = responseWithToolCall("long_click", args);
        ToolCallParser.ParsedAction action = parser.parse(response);

        assertNotNull(action);
        assertEquals("long_click", action.getActionType());
        assertEquals(750, action.getX());
        assertEquals(960, action.getY());
    }

    // ---------------------------------------------------------------------------
    // Test 10: back action — no coordinates
    // ---------------------------------------------------------------------------
    @Test
    public void testBackAction_noCoordinates() {
        String content = "<tool_call>{\"name\": \"back\", \"arguments\": {}}</tool_call>";
        SglangClient.ChatResponse response = responseWithContent(content);
        ToolCallParser.ParsedAction action = parser.parse(response);

        assertNotNull(action);
        assertEquals("back", action.getActionType());
        // Coordinates default to 0 when absent
        assertEquals(0, action.getX());
        assertEquals(0, action.getY());
    }

    // ---------------------------------------------------------------------------
    // Test 11: All-fail — garbage input returns null
    // ---------------------------------------------------------------------------
    @Test
    public void testGarbageInput_returnsNull() {
        String content = "This is just a plain text response with no tool calls at all.";
        SglangClient.ChatResponse response = responseWithContent(content);
        ToolCallParser.ParsedAction action = parser.parse(response);

        assertNull(action);
    }

    // ---------------------------------------------------------------------------
    // Test 12: null response returns null
    // ---------------------------------------------------------------------------
    @Test
    public void testNullResponse_returnsNull() {
        ToolCallParser.ParsedAction action = parser.parse(null);
        assertNull(action);
    }

    // ---------------------------------------------------------------------------
    // Comma-separated string coordinate fix (Qwen3.5-4B with qwen3_coder)
    // ---------------------------------------------------------------------------

    @Test
    public void testFixMalformedJson_commaSeparatedString() {
        String input = "{\"name\": \"click\", \"arguments\": {\"x\": \"498, 549\"}}";
        String fixed = ToolCallParser.fixMalformedJson(input);
        assertTrue("x must be numeric after fix", fixed.contains("\"x\": 498"));
        assertTrue("y must be present after fix", fixed.contains("\"y\": 549"));
        assertFalse("quoted string value must be removed", fixed.contains("\"498, 549\""));
    }

    @Test
    public void testFixMalformedJson_commaSeparatedStringWithSpaces() {
        String input = "{\"name\": \"click\", \"arguments\": {\"x\": \" 498 , 549 \"}}";
        String fixed = ToolCallParser.fixMalformedJson(input);
        assertTrue("x must be numeric after fix", fixed.contains("\"x\": 498"));
        assertTrue("y must be present after fix", fixed.contains("\"y\": 549"));
    }

    @Test
    public void testFixMalformedJson_commaSeparatedStringPreservesOtherFixes() {
        // Existing fixes still work: missing y key
        String input1 = "{\"name\": \"click\", \"arguments\": {\"x\": 540, 399}}";
        String fixed1 = ToolCallParser.fixMalformedJson(input1);
        assertTrue(fixed1.contains("\"y\": 399"));

        // Existing fixes still work: array format
        String input2 = "{\"name\": \"click\", \"arguments\": {\"x\": [352, 782]}}";
        String fixed2 = ToolCallParser.fixMalformedJson(input2);
        assertTrue(fixed2.contains("\"y\": 782"));
    }

    @Test
    public void testParse_inlineJsonWithCommaSeparatedStringCoords() {
        // End-to-end: inline JSON with string coords → parsed correctly
        String content = "{\"name\": \"click\", \"arguments\": {\"x\": \"300, 700\"}}";
        SglangClient.ChatResponse response = new SglangClient.ChatResponse(
                content, Collections.<SglangClient.ToolCall>emptyList(), 0, 0);

        ToolCallParser.ParsedAction action = parser.parse(response);

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(300, action.getX());
        assertEquals(700, action.getY());
    }
}
