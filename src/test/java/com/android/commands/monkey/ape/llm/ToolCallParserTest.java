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
        String fixed = ToolCallParser.fixMalformedJson(malformed).json;
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
        String fixed = ToolCallParser.fixMalformedJson(malformed).json;
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
        String fixed = ToolCallParser.fixMalformedJson(malformed).json;
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

    // ===========================================================================
    // llm-toolcall-parse-recovery: quoted-collapsed-XY fix, last-resort int scan,
    // and repair-form labels (INV-LLM-09). Bodies are taken verbatim from the real
    // cmp_llm_20260721 smoke corpus / the reference-parser catalogue.
    // ===========================================================================

    // --- quoted-collapsed-XY: both coordinates in one string under "x" ---------

    // Open quote, no closing quote — the exact 7/7 form dropped at the smoke gate.
    @Test
    public void testFix_quotedXY_openQuote() {
        String content = "{\"name\":\"click\",\"arguments\":{\"x\": \"500, 527}}";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(500, action.getX());
        assertEquals(527, action.getY());
        assertEquals("quoted_xy", action.getRepairForm());
    }

    // Closing quote present — this is VALID JSON (string value), never throws, so
    // only the regex can recover it (int-scan is unreachable for this variant).
    @Test
    public void testFix_quotedXY_closedQuote() {
        String content = "{\"name\":\"click\",\"arguments\":{\"x\": \"820, 590\"}}";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(820, action.getX());
        assertEquals(590, action.getY());
        assertEquals("quoted_xy", action.getRepairForm());
    }

    // No-space form, observed verbatim in traffic.
    @Test
    public void testFix_quotedXY_noSpace() {
        String content = "{\"name\":\"click\",\"arguments\":{\"x\":\"820,590}}";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(820, action.getX());
        assertEquals(590, action.getY());
        assertEquals("quoted_xy", action.getRepairForm());
    }

    // The quoted fix must NOT fire on a lone quoted integer — the coords parse
    // cleanly as string-coerced ints, so the label is `none`.
    @Test
    public void testFix_quotedSingleInts_notQuotedXY() {
        String content = "{\"name\":\"click\",\"arguments\":{\"x\": \"500\", \"y\": \"527\"}}";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(500, action.getX());
        assertEquals(527, action.getY());
        assertEquals("none", action.getRepairForm());
    }

    // --- no regression: bare / array forms keep their existing labels ----------

    @Test
    public void testFix_bareMissingY_stillMissingY() {
        String content = "{\"name\":\"click\",\"arguments\":{\"x\": 932, 71}}";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(932, action.getX());
        assertEquals(71, action.getY());
        assertEquals("missing_y", action.getRepairForm());
    }

    @Test
    public void testFix_arrayCoords_labelArrayXY() {
        String content = "{\"name\":\"click\",\"arguments\":{\"x\":[540,399]}}";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(540, action.getX());
        assertEquals(399, action.getY());
        assertEquals("array_xy", action.getRepairForm());
    }

    // --- last-resort int scan: unparseable-after-fix, tap action, ≥2 ints ------

    // Equals-sign body (rv-agent P4, vLLM backend) — verified to throw `Missing value`.
    @Test
    public void testLastResort_intScan() {
        String content = "{\"name\":\"click\",\"arguments\":{\"x\": = 265, \"y\": 687}}";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(265, action.getX());
        assertEquals(687, action.getY());
        assertEquals("int_scan", action.getRepairForm());
    }

    // Double-colon body (vision doc 012) — verified to throw `Expected a ',' or '}'`.
    @Test
    public void testLastResort_doubleColon() {
        String content = "{\"name\":\"click\",\"arguments\":{\"x\":\": 541, \"y\": 562}}";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(541, action.getX());
        assertEquals(562, action.getY());
        assertEquals("int_scan", action.getRepairForm());
    }

    // Trailing-quote body (rv-agent P0b) — x-first, so first-two-ints map to (x,y).
    @Test
    public void testLastResort_trailingQuote() {
        String content = "{\"name\":\"click\",\"arguments\":{\"x\": 200\", \"y\": 473}}";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(200, action.getX());
        assertEquals(473, action.getY());
        assertEquals("int_scan", action.getRepairForm());
    }

    // Gate boundary: the same unparseable body naming `scroll` (not a tap action)
    // must NOT be int-scanned — parse() returns null → cause=parse → SATA fallback.
    @Test
    public void testLastResort_gateExcludesUnadvertisedAction() {
        String content = "{\"name\":\"scroll\",\"arguments\":{\"x\": = 265, \"y\": 687}}";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNull(action);
    }

    // --- clean parses carry label `none` (fidelity: no repair happened) --------

    @Test
    public void testNative_formNone() {
        Map<String, Object> args = new HashMap<>();
        args.put("x", 540.0);
        args.put("y", 399.0);
        ToolCallParser.ParsedAction action = parser.parse(responseWithToolCall("click", args));

        assertNotNull(action);
        assertEquals("none", action.getRepairForm());
    }

    @Test
    public void testCleanInlineJson_formNone() {
        String content = "{\"name\": \"click\", \"arguments\": {\"x\": 100, \"y\": 200}}";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("none", action.getRepairForm());
    }

    @Test
    public void testCleanXmlTypeText_formNone() {
        String content = "<tool_call>{\"name\": \"type_text\", \"arguments\": {\"x\": 300, \"y\": 500, \"text\": \"hello\"}}</tool_call>";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("type_text", action.getActionType());
        assertEquals("none", action.getRepairForm());
    }

    @Test
    public void testCleanLongClick_formNone() {
        String content = "<function_call>{\"name\": \"long_click\", \"arguments\": {\"x\": 450, \"y\": 600}}</function_call>";
        ToolCallParser.ParsedAction action = parser.parse(responseWithContent(content));

        assertNotNull(action);
        assertEquals("long_click", action.getActionType());
        assertEquals("none", action.getRepairForm());
    }
}
