package com.android.commands.monkey.ape.llm;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;

import static org.junit.Assert.*;

/**
 * End-to-end live tests with a real SGLang inference server.
 *
 * Uses the REAL ApePromptBuilder format: screenshot + structured widget list
 * with [DM]/[M] markers + coordinates in [0,1000) space. Validates that the
 * LLM response coordinates fall within actual widget bounds from UIAutomator.
 *
 * Gated by {@code SGLANG_URL} environment variable.
 *
 * To run:
 *   export SGLANG_URL=http://localhost:30000/v1
 *   mvn test -Dtest=SglangLiveTest
 */
public class SglangLiveTest {

    private static String sglangUrl;
    private static final String DEFAULT_MODEL = "Qwen/Qwen3-VL-4B-Instruct";

    private static final double TEMPERATURE = 0.3;
    private static final double TOP_P       = 0.6;
    private static final int    TOP_K       = 50;
    private static final int    MAX_TOKENS  = 256;
    private static final int    TIMEOUT_MS  = 30_000;

    // Device dimensions for cryptoapp fixtures (all 1080x1920)
    private static final int DEVICE_W = 1080;
    private static final int DEVICE_H = 1920;

    @BeforeClass
    public static void readServerUrl() {
        sglangUrl = System.getenv("SGLANG_URL");
    }

    // -------------------------------------------------------------------------
    // Infrastructure
    // -------------------------------------------------------------------------

    private SglangClient buildClient() {
        String model = System.getenv("SGLANG_MODEL") != null
                ? System.getenv("SGLANG_MODEL") : DEFAULT_MODEL;
        SglangClient client = new SglangClient(sglangUrl, model,
                TEMPERATURE, TOP_P, TOP_K, MAX_TOKENS, TIMEOUT_MS, false);
        client.setTools(buildToolsSchema());
        return client;
    }

    private static org.json.JSONArray buildToolsSchema() {
        try {
            org.json.JSONArray tools = new org.json.JSONArray();
            String[] names = {"click", "long_click", "type_text", "back"};
            String[] descs = {"Tap element", "Long press", "Type into field", "Press back"};
            String[][] params = {{"x", "y"}, {"x", "y"}, {"x", "y", "text"}, {}};
            String[][] types = {{"integer","integer"}, {"integer","integer"}, {"integer","integer","string"}, {}};
            for (int i = 0; i < names.length; i++) {
                org.json.JSONObject props = new org.json.JSONObject();
                org.json.JSONArray req = new org.json.JSONArray();
                for (int j = 0; j < params[i].length; j++) {
                    props.put(params[i][j], new org.json.JSONObject().put("type", types[i][j]));
                    req.put(params[i][j]);
                }
                org.json.JSONObject fn = new org.json.JSONObject()
                        .put("name", names[i]).put("description", descs[i])
                        .put("parameters", new org.json.JSONObject()
                                .put("type", "object").put("properties", props).put("required", req));
                tools.put(new org.json.JSONObject().put("type", "function").put("function", fn));
            }
            return tools;
        } catch (Exception e) { return new org.json.JSONArray(); }
    }

    private byte[] loadFixture(String name) throws Exception {
        InputStream is = getClass().getResourceAsStream("/fixtures/cryptoapp/" + name);
        assertNotNull("Fixture not found: " + name, is);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        is.close();
        return buf.toByteArray();
    }

    /** PNG → resize max 1000px → JPEG q80 → base64 (JVM-compatible ImageProcessor) */
    private String processScreenshot(byte[] pngBytes) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        int w = img.getWidth(), h = img.getHeight();
        if (Math.max(w, h) > 1000) {
            double scale = 1000.0 / Math.max(w, h);
            int nw = (int)(w * scale), nh = (int)(h * scale);
            BufferedImage resized = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            img = resized;
        } else if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            img = rgb;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.80f);
        writer.setOutput(ImageIO.createImageOutputStream(out));
        writer.write(null, new IIOImage(img, null, null), param);
        writer.dispose();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** Parse UIAutomator XML into widget list — reuses PromptIntegrationTest infra */
    private List<PromptIntegrationTest.WidgetInfo> loadWidgets(String tupleId) throws Exception {
        InputStream is = getClass().getResourceAsStream("/fixtures/cryptoapp/" + tupleId + ".uiautomator");
        assertNotNull("UIAutomator fixture not found: " + tupleId, is);
        return PromptIntegrationTest.parseWidgets(is);
    }

    /** Qwen [0,1000) → device pixel */
    private int toPixelX(int qwenX) { return (int)((qwenX / 1000.0) * DEVICE_W); }
    private int toPixelY(int qwenY) { return (int)((qwenY / 1000.0) * DEVICE_H); }

    /** Device pixel → Qwen [0,1000) */
    private int toQwenX(int px) { return (int)((px / (double) DEVICE_W) * 1000); }
    private int toQwenY(int py) { return (int)((py / (double) DEVICE_H) * 1000); }

    /** Check if pixel coords fall within any interactive widget bounds */
    private PromptIntegrationTest.WidgetInfo findHitWidget(int pixelX, int pixelY,
            List<PromptIntegrationTest.WidgetInfo> widgets) {
        PromptIntegrationTest.WidgetInfo bestMatch = null;
        int bestArea = Integer.MAX_VALUE;
        for (PromptIntegrationTest.WidgetInfo w : widgets) {
            if (!w.clickable && !w.longClickable) continue;
            int[] b = w.parsedBounds();
            if (pixelX >= b[0] && pixelX <= b[2] && pixelY >= b[1] && pixelY <= b[3]) {
                int area = (b[2] - b[0]) * (b[3] - b[1]);
                if (area < bestArea) {
                    bestArea = area;
                    bestMatch = w;
                }
            }
        }
        return bestMatch;
    }

    // -------------------------------------------------------------------------
    // Build REAL ApePromptBuilder-format prompt from UIAutomator XML
    // -------------------------------------------------------------------------

    /**
     * Builds the same prompt format that ApePromptBuilder produces, but from
     * raw UIAutomator XML instead of APE internal objects.
     */
    private List<SglangClient.Message> buildRealPrompt(String base64Image,
            List<PromptIntegrationTest.WidgetInfo> widgets, boolean hasEditText) {

        // System message — matches ApePromptBuilder exactly
        StringBuilder sys = new StringBuilder();
        sys.append("You are an Android UI testing agent exploring an app.\n");
        sys.append("DIALOG: If permission/error dialog visible, dismiss it first (click Allow/OK).\n");
        sys.append("PRIORITY: [DM]/[M] elements > unvisited (v:0) > visited.\n");
        sys.append("AVOID: status bar (top), navigation bar (bottom).\n");
        sys.append("RULES: Don't click same position twice. Use type_text for input fields with valid data ");
        sys.append("(email: user@example.com, password: Test1234!, domain: example.com, search: relevant term).\n");
        sys.append("Tools (coordinates in [0,1000) normalized space):\n");
        sys.append("  click(x, y) — tap element\n");
        sys.append("  long_click(x, y) — long press element\n");
        if (hasEditText) {
            sys.append("  type_text(x, y, text) — type into field\n");
        }
        sys.append("  back() — press back\n");
        sys.append("Respond with one JSON: {\"name\": \"<action>\", \"arguments\": {<args>}}");

        // Widget list — same format as ApePromptBuilder
        StringBuilder text = new StringBuilder();
        text.append("Screen \"CryptoApp\":\n");

        List<PromptIntegrationTest.WidgetInfo> interactive = PromptIntegrationTest.interactiveWidgets(widgets);
        for (int i = 0; i < interactive.size(); i++) {
            PromptIntegrationTest.WidgetInfo w = interactive.get(i);
            String simpleClass = w.className;
            if (simpleClass.contains(".")) {
                simpleClass = simpleClass.substring(simpleClass.lastIndexOf('.') + 1);
            }

            int normX = toQwenX(w.centerX());
            int normY = toQwenY(w.centerY());

            text.append("[").append(i).append("] ").append(simpleClass);
            if (w.text != null && !w.text.isEmpty()) {
                String t = w.text.length() > 50 ? w.text.substring(0, 50) + "..." : w.text;
                text.append(" \"").append(t).append("\"");
            }
            text.append(" @(").append(normX).append(",").append(normY).append(")");
            text.append(" (v:0)");
            text.append("\n");
        }

        text.append("\nNEW state. 0/").append(interactive.size()).append(" MOP.");

        // Build messages
        String imageDataUrl = "data:image/jpeg;base64," + base64Image;
        List<SglangClient.ContentPart> userParts = new ArrayList<>();
        userParts.add(SglangClient.ContentPart.imageUrl(imageDataUrl));
        userParts.add(SglangClient.ContentPart.text(text.toString()));

        List<SglangClient.Message> messages = new ArrayList<>();
        messages.add(new SglangClient.Message("system", sys.toString()));
        messages.add(new SglangClient.Message("user", userParts));
        return messages;
    }

    // -------------------------------------------------------------------------
    // Core test runner — validates response against real widget bounds
    // -------------------------------------------------------------------------

    private static class LiveTestResult {
        final String actionType;
        final int qwenX, qwenY;
        final int pixelX, pixelY;
        final String text;
        final PromptIntegrationTest.WidgetInfo hitWidget;
        final long latencyMs;
        final int tokensIn, tokensOut;

        LiveTestResult(String actionType, int qwenX, int qwenY, int pixelX, int pixelY,
                String text, PromptIntegrationTest.WidgetInfo hitWidget,
                long latencyMs, int tokensIn, int tokensOut) {
            this.actionType = actionType;
            this.qwenX = qwenX; this.qwenY = qwenY;
            this.pixelX = pixelX; this.pixelY = pixelY;
            this.text = text; this.hitWidget = hitWidget;
            this.latencyMs = latencyMs;
            this.tokensIn = tokensIn; this.tokensOut = tokensOut;
        }
    }

    private LiveTestResult runRealPromptTest(String tupleId) throws Exception {
        Assume.assumeNotNull("SGLANG_URL not set", sglangUrl);

        SglangClient client = buildClient();
        ToolCallParser parser = new ToolCallParser();

        byte[] pngBytes = loadFixture(tupleId + ".png");
        String base64 = processScreenshot(pngBytes);
        List<PromptIntegrationTest.WidgetInfo> widgets = loadWidgets(tupleId);
        List<PromptIntegrationTest.WidgetInfo> inputWidgets = PromptIntegrationTest.inputWidgets(widgets);
        boolean hasEditText = !inputWidgets.isEmpty();

        List<SglangClient.Message> messages = buildRealPrompt(base64, widgets, hasEditText);

        long startMs = System.currentTimeMillis();
        SglangClient.ChatResponse response = client.chat(messages);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertNotNull("ChatResponse must not be null for " + tupleId, response);

        ToolCallParser.ParsedAction action = parser.parse(response);

        // Telemetry
        System.out.printf("[SglangLiveTest] tuple=%s latency_ms=%d tokens_in=%d tokens_out=%d%n",
                tupleId, elapsedMs, response.getPromptTokens(), response.getCompletionTokens());

        assertNotNull("ParsedAction must not be null for " + tupleId +
                " — raw: " + (response.getContent() != null ?
                    response.getContent().substring(0, Math.min(200, response.getContent().length())) : "null"),
                action);

        String actionType = action.getActionType();
        assertTrue("actionType must be valid: " + actionType,
                actionType.equals("click") || actionType.equals("long_click") ||
                actionType.equals("type_text") || actionType.equals("back"));

        int pixelX = 0, pixelY = 0;
        PromptIntegrationTest.WidgetInfo hit = null;

        if (!"back".equals(actionType)) {
            assertTrue("x in [0,999]: " + action.getX(), action.getX() >= 0 && action.getX() <= 999);
            assertTrue("y in [0,999]: " + action.getY(), action.getY() >= 0 && action.getY() <= 999);
            pixelX = toPixelX(action.getX());
            pixelY = toPixelY(action.getY());
            hit = findHitWidget(pixelX, pixelY, widgets);

            System.out.printf("  action=%s qwen=(%d,%d) pixel=(%d,%d) hit=%s%n",
                    actionType, action.getX(), action.getY(), pixelX, pixelY,
                    hit != null ? hit.className + " \"" + hit.text + "\"" : "MISS");
        } else {
            System.out.printf("  action=back%n");
        }

        return new LiveTestResult(actionType, action.getX(), action.getY(),
                pixelX, pixelY, action.getText(), hit, elapsedMs,
                response.getPromptTokens(), response.getCompletionTokens());
    }

    // -------------------------------------------------------------------------
    // 7.2.1 Live tests — real prompt, real validation
    // -------------------------------------------------------------------------

    @Test
    public void tuple001_mainActivity_hitsInteractiveWidget() throws Exception {
        LiveTestResult r = runRealPromptTest("001");
        // Must hit one of: MESSAGE DIGEST, CIPHER, GENERATED, or More options
        assertNotNull("LLM must click on an actual widget, not empty space. " +
                "pixel=(" + r.pixelX + "," + r.pixelY + ")", r.hitWidget);
        assertTrue("Must be a click action", "click".equals(r.actionType) || "long_click".equals(r.actionType));
    }

    @Test
    public void tuple004_spinnerDropdown_hitsListItem() throws Exception {
        LiveTestResult r = runRealPromptTest("004");
        assertNotNull("LLM must click on a list item. pixel=(" + r.pixelX + "," + r.pixelY + ")", r.hitWidget);
        // Should hit one of the algorithm TextViews (MD2, MD5, SHA-1, etc.)
        assertTrue("Should hit a TextView list item",
                r.hitWidget.className.contains("TextView"));
    }

    @Test
    public void tuple010_messageDigest_suggestsTypeTextOrClick() throws Exception {
        LiveTestResult r = runRealPromptTest("010");
        assertNotNull("LLM must target a widget. pixel=(" + r.pixelX + "," + r.pixelY + ")", r.hitWidget);
        // This screen has an EditText — LLM should ideally type_text, but click is also acceptable
        System.out.printf("  [010] actionType=%s widget=%s%n", r.actionType,
                r.hitWidget.className + " \"" + r.hitWidget.text + "\"");
    }

    @Test
    public void tuple015_cryptography_hitsInteractiveWidget() throws Exception {
        LiveTestResult r = runRealPromptTest("015");
        assertNotNull("LLM must target a widget. pixel=(" + r.pixelX + "," + r.pixelY + ")", r.hitWidget);
        // Complex form — any interactive widget is acceptable
        System.out.printf("  [015] actionType=%s widget=%s%n", r.actionType,
                r.hitWidget.className + " \"" + r.hitWidget.text + "\"");
    }

    @Test
    public void tuple020_algorithmDialog_hitsListItem() throws Exception {
        LiveTestResult r = runRealPromptTest("020");
        assertNotNull("LLM must click on an algorithm. pixel=(" + r.pixelX + "," + r.pixelY + ")", r.hitWidget);
    }

    @Test
    public void hitRate_atLeast3of5() throws Exception {
        Assume.assumeNotNull("SGLANG_URL not set", sglangUrl);

        String[] tuples = {"001", "004", "010", "015", "020"};
        int hits = 0;
        for (String t : tuples) {
            try {
                LiveTestResult r = runRealPromptTest(t);
                if (r.hitWidget != null) hits++;
            } catch (AssertionError e) {
                System.out.printf("  [hitRate] %s FAILED: %s%n", t, e.getMessage());
            }
        }
        System.out.printf("[SglangLiveTest] Hit rate: %d/%d%n", hits, tuples.length);
        assertTrue("Hit rate must be >= 3/5 (60%): was " + hits + "/5", hits >= 3);
    }

    // -------------------------------------------------------------------------
    // Offline tests (no server required)
    // -------------------------------------------------------------------------

    @Test
    public void buildRequestBody_withTools_isValidJson() throws Exception {
        SglangClient client = new SglangClient(
                "http://localhost:9999/v1", DEFAULT_MODEL,
                TEMPERATURE, TOP_P, TOP_K, MAX_TOKENS, TIMEOUT_MS, false);
        client.setTools(buildToolsSchema());

        byte[] pngBytes = loadFixture("001.png");
        String base64 = processScreenshot(pngBytes);
        List<PromptIntegrationTest.WidgetInfo> widgets = loadWidgets("001");
        List<SglangClient.Message> messages = buildRealPrompt(base64, widgets, false);

        String body = client.buildRequestBody(messages);
        org.json.JSONObject root = new org.json.JSONObject(body);

        assertTrue("must have 'tools' field", root.has("tools"));
        org.json.JSONArray tools = root.getJSONArray("tools");
        assertTrue("must have at least 3 tools", tools.length() >= 3);

        assertTrue("must have 'messages' field", root.has("messages"));
        org.json.JSONArray msgs = root.getJSONArray("messages");
        assertEquals("2 messages (system + user)", 2, msgs.length());

        // User message text should contain widget list
        org.json.JSONObject userMsg = msgs.getJSONObject(1);
        org.json.JSONArray parts = userMsg.getJSONArray("content");
        String userText = parts.getJSONObject(1).getString("text");
        assertTrue("user text must contain widget list", userText.contains("@("));
        assertTrue("user text must contain Screen header", userText.contains("Screen"));
    }

    @Test
    public void parseResponse_mockToolCall_extractsAction() {
        SglangClient client = new SglangClient(
                "http://localhost:9999/v1", DEFAULT_MODEL,
                TEMPERATURE, TOP_P, TOP_K, MAX_TOKENS, TIMEOUT_MS, false);

        String mock = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null," +
                "\"tool_calls\":[{\"function\":{\"name\":\"click\",\"arguments\":{\"x\":540,\"y\":399}}}]}}]," +
                "\"usage\":{\"prompt_tokens\":512,\"completion_tokens\":32}}";

        SglangClient.ChatResponse response = client.parseResponse(mock);
        ToolCallParser.ParsedAction action = new ToolCallParser().parse(response);
        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(540, action.getX());
        assertEquals(399, action.getY());
    }

    @Test
    public void parseResponse_xmlTagFormat_extractsAction() {
        SglangClient client = new SglangClient(
                "http://localhost:9999/v1", DEFAULT_MODEL,
                TEMPERATURE, TOP_P, TOP_K, MAX_TOKENS, TIMEOUT_MS, false);

        String xmlContent = "<tool_call>\n{\"name\": \"click\", \"arguments\": {\"x\": 500, \"y\": 208}}\n</tool_call>";
        String mock = "{\"choices\":[{\"message\":{\"role\":\"assistant\"," +
                "\"content\":" + org.json.JSONObject.quote(xmlContent) + "}}]," +
                "\"usage\":{\"prompt_tokens\":400,\"completion_tokens\":20}}";

        SglangClient.ChatResponse response = client.parseResponse(mock);
        ToolCallParser.ParsedAction action = new ToolCallParser().parse(response);
        assertNotNull(action);
        assertEquals("click", action.getActionType());
        assertEquals(500, action.getX());
        assertEquals(208, action.getY());
    }
}
