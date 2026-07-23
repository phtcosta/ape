package com.android.commands.monkey.ape.llm;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * llm-native-toolcall-repair — offline acceptance fixture (tasks.md 5.4).
 *
 * <p>Device-free proxy for the rvsec-side Fase-B re-parse acceptance
 * ({@code decompose_nomatch.py}: {@code degenerate ≈ 0}). It replays the handoff's native
 * malformation census as full SGLang response envelopes and drives each through the ACTUAL native
 * extraction path — {@link SglangClient#parseResponse} (raw-arguments preservation, INV-LLM-10) then
 * {@link ToolCallParser#parse} (Level 1 unification) — asserting that every recoverable form yields a
 * matchable coordinate rather than collapsing to {@code (0,0)} (the pre-fix {@code reason=degenerate}
 * sink), and carries the expected repair-form label.
 *
 * <p>This is the seam the group-3 unit tests exercise piecewise; here it is one census pass over the
 * whole native path, envelope-in, so the fixture mirrors what the offline re-parse will measure.
 */
public class NativeToolCallRepairAcceptanceTest {

    private static SglangClient makeClient() {
        return new SglangClient("http://localhost:9999/v1", "test", 0.3, 0.6, 50, 1024, 5000);
    }

    /** Envelope whose single tool call encodes {@code arguments} as a JSON STRING (the SGLang default). */
    private static String envelopeArgsString(String name, String argumentsJsonLiteral) {
        // argumentsJsonLiteral is embedded as a JSON string value → escape backslashes and quotes.
        String escaped = argumentsJsonLiteral.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"function\":{\"name\":\"" + name + "\","
                + "\"arguments\":\"" + escaped + "\"}}]}}]}";
    }

    /** Envelope whose single tool call encodes {@code arguments} as a JSON OBJECT. */
    private static String envelopeArgsObject(String name, String argumentsObjectJson) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"function\":{\"name\":\"" + name + "\","
                + "\"arguments\":" + argumentsObjectJson + "}}]}}]}";
    }

    private ToolCallParser.ParsedAction runNativePath(String envelope) {
        SglangClient.ChatResponse resp = makeClient().parseResponse(envelope);
        assertNotNull("parseResponse must not fail on the envelope", resp);
        assertFalse("envelope must yield a native tool call", resp.getToolCalls().isEmpty());
        return new ToolCallParser().parse(resp);
    }

    private void assertRecovered(String label, ToolCallParser.ParsedAction a,
                                 int expX, int expY, String expForm) {
        assertNotNull(label + ": parse must not return null", a);
        assertFalse(label + ": recoverable form MUST NOT collapse to (0,0)",
                a.getX() == 0 && a.getY() == 0);
        assertEquals(label + ": x", expX, a.getX());
        assertEquals(label + ": y", expY, a.getY());
        assertEquals(label + ": repair form", expForm, a.getRepairForm());
    }

    // -- The recoverable native census: zero (0,0) collapses -------------------------------------

    @Test
    public void census_missingYString_recovered() {
        // arguments string {"x": 616, 891} — invalid JSON (missing "y"): the dominant degenerate form.
        assertRecovered("missing_y (string)",
                runNativePath(envelopeArgsString("click", "{\"x\": 616, 891}")),
                616, 891, "missing_y");
    }

    @Test
    public void census_bareMissingY_recovered() {
        assertRecovered("missing_y (bare)",
                runNativePath(envelopeArgsString("click", "{\"x\": 932, 71}")),
                932, 71, "missing_y");
    }

    @Test
    public void census_quotedCollapsedXY_closingQuote_recovered() {
        // Valid JSON: the map holds the string under "x"; Integer.parseInt would fail → (0,0) pre-fix.
        assertRecovered("quoted_xy (closed)",
                runNativePath(envelopeArgsString("click", "{\"x\": \"540, 399\"}")),
                540, 399, "quoted_xy");
    }

    @Test
    public void census_quotedCollapsedXY_noClosingQuote_recovered() {
        // Unterminated string → invalid JSON → empty map; the quoted-XY fix tolerates the missing quote.
        assertRecovered("quoted_xy (open)",
                runNativePath(envelopeArgsString("click", "{\"x\": \"500, 527}")),
                500, 527, "quoted_xy");
    }

    @Test
    public void census_arrayForm_recovered() {
        // arguments object {"x":[540,399]}: SglangClient expands the map AND carries the object raw;
        // under D4 the raw path labels array_xy.
        assertRecovered("array_xy",
                runNativePath(envelopeArgsObject("click", "{\"x\": [540, 399]}")),
                540, 399, "array_xy");
    }

    @Test
    public void census_unrecoverableTap_intScan_recovered() {
        // {"x": = 265, "y": 687} defeats every regex fix; the click gate admits the last-resort scan.
        assertRecovered("int_scan",
                runNativePath(envelopeArgsString("click", "{\"x\": = 265, \"y\": 687}")),
                265, 687, "int_scan");
    }

    @Test
    public void census_longClickMissingY_recovered() {
        assertRecovered("missing_y (long_click)",
                runNativePath(envelopeArgsString("long_click", "{\"x\": 300, 450}")),
                300, 450, "missing_y");
    }

    // -- Negative controls: never-worse fallback, no spurious (0,0) tap --------------------------

    @Test
    public void census_cleanNative_noRepair_noCollapse() {
        ToolCallParser.ParsedAction a =
                runNativePath(envelopeArgsObject("click", "{\"x\": 540, \"y\": 399}"));
        assertNotNull(a);
        assertEquals(540, a.getX());
        assertEquals(399, a.getY());
        assertEquals("none", a.getRepairForm());
    }

    @Test
    public void census_unrecoverableBack_fallsBackNoCrash() {
        // back is not a tap: the int-scan gate rejects it → Level 1 falls back to the map path.
        // back carries no coordinate semantics, so (0,0) here is NOT a degenerate tap — it is the
        // correct back action; the router routes it to state.getBackAction() without coordinate matching.
        ToolCallParser.ParsedAction a =
                runNativePath(envelopeArgsString("back", "{\"bad\": =}"));
        assertNotNull(a);
        assertEquals("back", a.getActionType());
        assertEquals("none", a.getRepairForm());
    }
}
