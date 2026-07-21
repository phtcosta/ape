package com.android.commands.monkey.ape.llm;

import org.json.JSONObject;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses tool calls from LLM responses using a 3-level fallback strategy.
 *
 * Qwen3-VL with SGLang does not always use the native OpenAI tool_calls format (~50% rate).
 * This parser handles all three formats the model may produce:
 *   1. Native  — ChatResponse.toolCalls list is populated by SglangClient
 *   2. XML     — model wraps the call in <tool_call>...</tool_call> tags in its text
 *   3. JSON    — model embeds {"name": "...", "arguments": {...}} in its text
 *
 * Qwen3-VL commonly generates malformed JSON coordinates. A pre-parse fix step (ported
 * from RVAgent's _fix_malformed_json) repairs these before org.json sees the string:
 *   - {"x": "540, 399} / {"x": "540, 399"}  → {"x": 540, "y": 399}  (collapsed into one string)
 *   - {"x": 540, 399}      → {"x": 540, "y": 399}   (missing "y" key)
 *   - {"x": [540, 399]}    → {"x": 540, "y": 399}   (array format)
 *   - {"x": .91}           → {"x": 0.91}            (leading-zero float)
 * When every fix still leaves an unparseable object that names a tap action, a last-resort
 * integer scan takes the first two standalone ints in the arguments region as (x, y).
 *
 * A successful parse carries a repair-form label (none / missing_y / array_xy / quoted_xy /
 * int_scan) so downstream telemetry keeps raw tool-call fidelity measurable after hardening.
 *
 * Action types produced ("click", "long_click", "scroll", "type_text", "back") map
 * directly to Action.Type via AgentLoop conventions.
 */
public class ToolCallParser {

    // Matches <tool_call>...</tool_call> or <function_call>...</function_call>
    private static final Pattern XML_TAG_PATTERN = Pattern.compile(
            "<(?:tool_call|function_call)>(.*?)</(?:tool_call|function_call)>",
            Pattern.DOTALL);

    // Malformed JSON fixes (ported from RVAgent tool_call_parser.py _fix_malformed_json)
    // Pattern 0: "x": "352, 782  or  "x": "352, 782"  →  "x": 352, "y": 782
    //   (Qwen3-VL collapses both coordinates into one string under "x"; opening quote always
    //    present, closing quote optional). Anchored on "x": so it never touches "text" or other
    //    string values, and requires <digits>,<digits> inside the quote so it never fires on a
    //    lone quoted integer ("x": "500"). Runs before FIX_MISSING_Y_KEY: the leading quote would
    //    otherwise defeat that pattern and leave org.json an unterminated string.
    private static final Pattern FIX_QUOTED_XY =
            Pattern.compile("\"x\":\\s*\"(\\d+)\\s*,\\s*(\\d+)\"?");
    // Pattern 1: "x": 352, 782  →  "x": 352, "y": 782  (Qwen3-VL missing "y" key — most common)
    private static final Pattern FIX_MISSING_Y_KEY =
            Pattern.compile("\"x\":\\s*(\\d+),\\s*(\\d+)");
    // Pattern 2: "x": [352, 782]  →  "x": 352, "y": 782  (coordinate array format)
    private static final Pattern FIX_ARRAY_COORDS =
            Pattern.compile("\"x\":\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]");
    // Pattern 3: ": .91  →  ": 0.91  (missing leading zero)
    private static final Pattern FIX_LEADING_ZERO =
            Pattern.compile(":\\s*\\.(\\d+)");

    // Last-resort recovery gate: the still-unparseable object must name a tap action (click /
    // long_click). scroll/type_text/back are excluded — a synthesized scroll executes as a
    // wrong-gesture tap, a text-less type_text is a wasted step, and back has no coordinates.
    private static final Pattern TAP_ACTION_NAME =
            Pattern.compile("\"name\"\\s*:\\s*\"(long_click|click)\"");
    // A standalone 1–4-digit integer run: bounded so Integer.parseInt cannot overflow and long
    // non-coordinate numbers are skipped.
    private static final Pattern STANDALONE_INT =
            Pattern.compile("(?<!\\d)\\d{1,4}(?!\\d)");

    // Matches a JSON object that has both "name" and "arguments" keys
    private static final Pattern JSON_INLINE_PATTERN = Pattern.compile(
            "\\{[^{}]*\"name\"[^{}]*\"arguments\"[^{}]*(?:\\{[^{}]*\\})[^{}]*\\}|" +
            "\\{[^{}]*\"arguments\"[^{}]*(?:\\{[^{}]*\\})[^{}]*\"name\"[^{}]*\\}",
            Pattern.DOTALL);

    /**
     * Parse a tool call from the model response using native → XML → JSON fallback.
     *
     * @param response the parsed ChatResponse from SglangClient
     * @return a ParsedAction, or null if no recognisable tool call was found
     */
    public ParsedAction parse(SglangClient.ChatResponse response) {
        if (response == null) return null;

        // Level 1: native tool_calls list
        if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
            SglangClient.ToolCall tc = response.getToolCalls().get(0);
            return buildParsedAction(tc.getName(), tc.getArguments(), "none");
        }

        // Level 2: XML tag in content text
        String content = response.getContent();
        if (content != null && !content.isEmpty()) {
            ParsedAction fromXml = parseXml(content);
            if (fromXml != null) return fromXml;

            // Level 3: inline JSON in content text
            return parseJsonInline(content);
        }

        return null;
    }

    /**
     * Parse <tool_call>JSON</tool_call> or <function_call>JSON</function_call>.
     */
    private ParsedAction parseXml(String content) {
        Matcher m = XML_TAG_PATTERN.matcher(content);
        if (!m.find()) return null;

        String inner = m.group(1).trim();
        return parseJsonString(inner);
    }

    /**
     * Find and parse the first inline JSON object with "name" + "arguments" keys.
     */
    private ParsedAction parseJsonInline(String content) {
        // Try to find a balanced JSON object containing "name" and "arguments"
        int start = content.indexOf("{");
        while (start >= 0 && start < content.length()) {
            int end = findMatchingBrace(content, start);
            if (end < 0) break;
            String candidate = content.substring(start, end + 1);
            ParsedAction action = parseJsonString(candidate);
            if (action != null) return action;
            start = content.indexOf("{", start + 1);
        }
        return null;
    }

    /**
     * Find the closing brace that matches the opening brace at position 'start'.
     */
    private int findMatchingBrace(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * The result of the pre-parse fix step: the fixed JSON string plus the repair-form label
     * naming the highest-precedence coordinate-structure fix that altered the string
     * (per INV-LLM-09: one of none / missing_y / array_xy / quoted_xy). The cosmetic fixes
     * (leading-zero, brace balancing) are never labeled — they cannot rescue a parse and never
     * touch a coordinate.
     */
    static final class FixResult {
        final String json;
        final String form;

        FixResult(String json, String form) {
            this.json = json;
            this.form = form;
        }
    }

    /**
     * Fix common Qwen3-VL JSON malformations before passing to org.json.
     * Ported from RVAgent tool_call_parser.py _fix_malformed_json().
     *
     * Fixes run in order quoted_xy → array_xy → missing_y → leading_zero → brace-close. The
     * returned label is the highest-precedence coordinate-structure fix (quoted_xy > array_xy >
     * missing_y) that altered the string, or "none". At most one coordinate fix fires per string
     * in practice, so precedence just picks a deterministic label. Never throws.
     */
    static FixResult fixMalformedJson(String json) {
        String form = "none";

        // Quoted-collapsed-XY: "x": "352, 782  /  "x": "352, 782"  → "x": 352, "y": 782
        String next = FIX_QUOTED_XY.matcher(json).replaceAll("\"x\": $1, \"y\": $2");
        if (!next.equals(json)) form = "quoted_xy";
        String s = next;

        // Array coords: "x": [352, 782] → "x": 352, "y": 782
        next = FIX_ARRAY_COORDS.matcher(s).replaceAll("\"x\": $1, \"y\": $2");
        if (!next.equals(s) && "none".equals(form)) form = "array_xy";
        s = next;

        // Missing "y" key: "x": 352, 782 → "x": 352, "y": 782
        next = FIX_MISSING_Y_KEY.matcher(s).replaceAll("\"x\": $1, \"y\": $2");
        if (!next.equals(s) && "none".equals(form)) form = "missing_y";
        s = next;

        // Cosmetic (never labeled): missing leading zero ": .91 → ": 0.91
        s = FIX_LEADING_ZERO.matcher(s).replaceAll(": 0.$1");

        // Cosmetic (never labeled): add missing closing braces (truncated JSON)
        int open = 0;
        for (char c : s.toCharArray()) {
            if (c == '{') open++;
            else if (c == '}') open--;
        }
        if (open > 0) {
            StringBuilder sb = new StringBuilder(s);
            for (int i = 0; i < open; i++) sb.append('}');
            s = sb.toString();
        }
        return new FixResult(s, form);
    }

    /**
     * Last-resort recovery, run only from {@link #parseJsonString}'s catch (i.e. every regex fix
     * failed to yield a parseable object). When {@code json} names a tap action (click / long_click)
     * and the region after the "arguments" token holds ≥2 standalone 1–4-digit integers, those two
     * integers become (x, y). Returns null on any other case.
     *
     * <p>The whole body is wrapped in its own try/catch → null: it executes inside the outer catch
     * where no other handler protects INV-LLM-04 (never throw to the caller).
     */
    private ParsedAction lastResortIntScan(String json) {
        try {
            if (json == null) return null;
            Matcher name = TAP_ACTION_NAME.matcher(json);
            if (!name.find()) return null;
            String action = name.group(1);

            int argsIdx = json.indexOf("\"arguments\"");
            if (argsIdx < 0) return null;

            Matcher ints = STANDALONE_INT.matcher(json).region(argsIdx, json.length());
            if (!ints.find()) return null;
            int x = Integer.parseInt(ints.group());
            if (!ints.find()) return null;
            int y = Integer.parseInt(ints.group());

            return new ParsedAction(action, x, y, null, null, "int_scan");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse a JSON string into a ParsedAction if it contains "name" and "arguments".
     * Applies malformed-JSON fixes before parsing (handles Qwen3-VL coordinate malformations).
     */
    private ParsedAction parseJsonString(String json) {
        try {
            // Apply fixes for common Qwen3-VL output malformations before org.json sees the string
            FixResult fix = fixMalformedJson(json);
            JSONObject obj = new JSONObject(fix.json);
            if (!obj.has("name")) return null;

            String name = obj.getString("name");

            Map<String, Object> args = new java.util.LinkedHashMap<>();
            if (obj.has("arguments")) {
                Object argsRaw = obj.get("arguments");
                if (argsRaw instanceof JSONObject) {
                    JSONObject argsObj = (JSONObject) argsRaw;
                    java.util.Iterator<String> keys = argsObj.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        Object v = argsObj.get(k);
                        if (v == JSONObject.NULL) {
                            // skip null values
                        } else if (v instanceof Number) {
                            args.put(k, ((Number) v).doubleValue());
                        } else if (v instanceof Boolean) {
                            args.put(k, v);
                        } else if (v instanceof String) {
                            args.put(k, v);
                        } else {
                            args.put(k, v.toString());
                        }
                    }
                } else if (argsRaw instanceof String) {
                    // Arguments embedded as a JSON string
                    return parseJsonString("{\"name\":\"" + name + "\",\"arguments\":" +
                            new JSONObject((String) argsRaw) + "}");
                }
            }
            return buildParsedAction(name, args, fix.form);

        } catch (Exception e) {
            // Regex fixes could not yield a parseable object; try the form-independent last resort
            // before giving up. It is internally guarded and returns null on any failure (INV-LLM-04).
            return lastResortIntScan(json);
        }
    }

    /**
     * Build a ParsedAction from a parsed action name and its arguments map.
     *
     * The action name maps to the canonical set used by Action.Type:
     *   click, long_click, scroll, type_text, back, etc.
     * Coordinates from Qwen3-VL are in [0, 1000) normalized space;
     * the caller must convert to pixels via CoordinateNormalizer.
     */
    private ParsedAction buildParsedAction(String name, Map<String, Object> args, String repairForm) {
        if (name == null) return null;

        String actionType = name.toLowerCase().replace("-", "_");

        int x = getIntArg(args, "x", 0);
        int y = getIntArg(args, "y", 0);
        String text = getStringArg(args, "text");
        String direction = getStringArg(args, "direction");

        return new ParsedAction(actionType, x, y, text, direction, repairForm);
    }

    private int getIntArg(Map<String, Object> args, String key, int defaultValue) {
        if (args == null || !args.containsKey(key)) return defaultValue;
        Object val = args.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(String.valueOf(val)); } catch (Exception e) { return defaultValue; }
    }

    private String getStringArg(Map<String, Object> args, String key) {
        if (args == null || !args.containsKey(key)) return null;
        Object val = args.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    // --- Inner class ---

    /**
     * The result of parsing a tool call from the LLM response.
     *
     * Coordinates (x, y) are in Qwen3-VL normalized [0, 1000) space.
     * Use CoordinateNormalizer to convert to device pixels before execution.
     */
    public static class ParsedAction {
        private final String actionType;
        private final int x;
        private final int y;
        private final String text;
        private final String direction;
        // The repair form a successful parse required (INV-LLM-09):
        // none / missing_y / array_xy / quoted_xy / int_scan. Travels with the action so the
        // router reads it at the single telemetry build site with no stale-state seam.
        private final String repairForm;

        public ParsedAction(String actionType, int x, int y, String text, String direction,
                            String repairForm) {
            this.actionType = actionType;
            this.x = x;
            this.y = y;
            this.text = text;
            this.direction = direction;
            this.repairForm = repairForm;
        }

        public String getActionType() { return actionType; }
        public int getX() { return x; }
        public int getY() { return y; }
        public String getText() { return text; }
        public String getDirection() { return direction; }
        public String getRepairForm() { return repairForm; }

        @Override
        public String toString() {
            return "ParsedAction{type=" + actionType + ", x=" + x + ", y=" + y +
                    ", text=" + text + ", direction=" + direction + ", repair=" + repairForm + "}";
        }
    }
}
