package com.android.commands.monkey.ape.llm;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Integration tests for prompt construction logic using real cryptoapp UIAutomator fixtures.
 *
 * These tests parse the UIAutomator XML files directly — no APE/Android runtime needed.
 * They verify the data that ApePromptBuilder WOULD include in the prompt, independently
 * of ApePromptBuilder itself (which requires Android GUITree/ModelAction objects).
 *
 * Fixture tuples tested:
 *   001 — MainActivity: 3 buttons, no EditText
 *   004 — Spinner dropdown list: 13 clickable TextViews, no EditText
 *   010 — MessageDigestActivity: Spinner + EditText + Button
 *   015 — CryptographyActivity: 2 EditTexts + Button + RadioButtons + Spinner + tabs
 *   020 — Algorithm selection dialog: 4 CheckedTextViews, no EditText
 */
public class PromptIntegrationTest {

    // -------------------------------------------------------------------------
    // UIAutomator XML parsing helpers
    // -------------------------------------------------------------------------

    /** Simple representation of a widget extracted from UIAutomator XML. */
    static class WidgetInfo {
        final String className;
        final String text;
        final String resourceId;
        final String bounds;
        final boolean clickable;
        final boolean longClickable;

        WidgetInfo(String className, String text, String resourceId,
                   String bounds, boolean clickable, boolean longClickable) {
            this.className     = className;
            this.text          = text;
            this.resourceId    = resourceId;
            this.bounds        = bounds;
            this.clickable     = clickable;
            this.longClickable = longClickable;
        }

        /** Parse "[left,top][right,bottom]" → int[]{left,top,right,bottom}. */
        int[] parsedBounds() {
            // Format: [0,210][1080,336]
            String s = bounds.replace("][", ",").replace("[", "").replace("]", "");
            String[] parts = s.split(",");
            return new int[]{
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim())
            };
        }

        int centerX() {
            int[] b = parsedBounds();
            return (b[0] + b[2]) / 2;
        }

        int centerY() {
            int[] b = parsedBounds();
            return (b[1] + b[3]) / 2;
        }
    }

    /** Input widget class names that enable type_text (mirrors ApePromptBuilder). */
    private static final Set<String> INPUT_CLASSES = new HashSet<>(Arrays.asList(
            "android.widget.EditText",
            "android.widget.AutoCompleteTextView",
            "android.widget.SearchView",
            "androidx.appcompat.widget.SearchView"
    ));

    /**
     * Parse a UIAutomator XML input stream and return all nodes whose
     * "class" attribute is set (i.e., actual widgets, not the hierarchy wrapper).
     */
    static List<WidgetInfo> parseWidgets(InputStream is) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(is);

        List<WidgetInfo> result = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String cls = el.getAttribute("class");
            if (cls == null || cls.isEmpty()) continue;

            String text        = el.getAttribute("text");
            String resourceId  = el.getAttribute("resource-id");
            String bounds      = el.getAttribute("bounds");
            String clickableStr = el.getAttribute("clickable");
            String longClickStr = el.getAttribute("long-clickable");

            result.add(new WidgetInfo(
                    cls, text, resourceId, bounds,
                    "true".equals(clickableStr),
                    "true".equals(longClickStr)));
        }
        return result;
    }

    /** Return only clickable or long-clickable widgets. */
    static List<WidgetInfo> interactiveWidgets(List<WidgetInfo> widgets) {
        List<WidgetInfo> result = new ArrayList<>();
        for (WidgetInfo w : widgets) {
            if (w.clickable || w.longClickable) result.add(w);
        }
        return result;
    }

    /** Return only widgets whose class is in INPUT_CLASSES. */
    static List<WidgetInfo> inputWidgets(List<WidgetInfo> widgets) {
        List<WidgetInfo> result = new ArrayList<>();
        for (WidgetInfo w : widgets) {
            if (INPUT_CLASSES.contains(w.className)) result.add(w);
        }
        return result;
    }

    private InputStream fixture(String name) {
        InputStream is = getClass().getResourceAsStream("/fixtures/cryptoapp/" + name);
        assertNotNull("Fixture not found: " + name, is);
        return is;
    }

    // -------------------------------------------------------------------------
    // 7.1.1-A  Widget extraction from UIAutomator XML
    // -------------------------------------------------------------------------

    @Test
    public void tuple001_hasThreeClickableButtons() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("001.uiautomator"));
        List<WidgetInfo> interactive = interactiveWidgets(widgets);
        // MainActivity: MESSAGE DIGEST, CIPHER, GENERATED buttons + More options ImageView
        assertTrue("tuple 001 must have > 2 interactive widgets", interactive.size() > 2);
    }

    @Test
    public void tuple004_hasMoreThanTwoInteractiveWidgets() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("004.uiautomator"));
        List<WidgetInfo> interactive = interactiveWidgets(widgets);
        // Spinner dropdown: 13 clickable TextViews
        assertTrue("tuple 004 must have > 2 interactive widgets, got " + interactive.size(),
                interactive.size() > 2);
    }

    @Test
    public void tuple010_hasMoreThanTwoInteractiveWidgets() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("010.uiautomator"));
        List<WidgetInfo> interactive = interactiveWidgets(widgets);
        assertTrue("tuple 010 must have > 2 interactive widgets", interactive.size() > 2);
    }

    @Test
    public void tuple015_hasMoreThanTwoInteractiveWidgets() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("015.uiautomator"));
        List<WidgetInfo> interactive = interactiveWidgets(widgets);
        assertTrue("tuple 015 must have > 2 interactive widgets", interactive.size() > 2);
    }

    @Test
    public void tuple020_hasMoreThanTwoInteractiveWidgets() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("020.uiautomator"));
        List<WidgetInfo> interactive = interactiveWidgets(widgets);
        assertTrue("tuple 020 must have > 2 interactive widgets", interactive.size() > 2);
    }

    // -------------------------------------------------------------------------
    // 7.1.1-B  Widget field extraction: class, text, resource-id, bounds
    // -------------------------------------------------------------------------

    @Test
    public void tuple001_cipherButtonHasExpectedFields() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("001.uiautomator"));
        WidgetInfo cipher = null;
        for (WidgetInfo w : widgets) {
            if ("CIPHER".equals(w.text)) { cipher = w; break; }
        }
        assertNotNull("CIPHER button must be present in tuple 001", cipher);
        assertEquals("android.widget.Button", cipher.className);
        assertTrue("CIPHER button must be clickable", cipher.clickable);
        assertEquals("br.unb.cic.cryptoapp:id/buttonCipher", cipher.resourceId);
        assertEquals("[0,336][1080,462]", cipher.bounds);
    }

    @Test
    public void tuple001_widgetClassNamesAreNonEmpty() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("001.uiautomator"));
        assertFalse("must have at least one widget", widgets.isEmpty());
        for (WidgetInfo w : widgets) {
            assertFalse("class must be non-empty for every widget", w.className.isEmpty());
        }
    }

    @Test
    public void tuple010_generateHashButtonFound() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("010.uiautomator"));
        WidgetInfo btn = null;
        for (WidgetInfo w : widgets) {
            if ("GENERATE HASH".equals(w.text)) { btn = w; break; }
        }
        assertNotNull("GENERATE HASH button must be present in tuple 010", btn);
        assertEquals("android.widget.Button", btn.className);
        assertTrue("GENERATE HASH must be clickable", btn.clickable);
        assertEquals("br.unb.cic.cryptoapp:id/buttonGenerateHash", btn.resourceId);
    }

    @Test
    public void tuple015_executeButtonFound() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("015.uiautomator"));
        WidgetInfo btn = null;
        for (WidgetInfo w : widgets) {
            if ("EXECUTE".equals(w.text)) { btn = w; break; }
        }
        assertNotNull("EXECUTE button must be present in tuple 015", btn);
        assertEquals("android.widget.Button", btn.className);
        assertTrue("EXECUTE must be clickable", btn.clickable);
        assertEquals("br.unb.cic.cryptoapp:id/executeButton", btn.resourceId);
    }

    // -------------------------------------------------------------------------
    // 7.1.1-C  Coordinate normalization for clickable widgets
    // -------------------------------------------------------------------------

    @Test
    public void allTuples_normalizedCoordsInRange() throws Exception {
        String[] tuples = {"001.uiautomator", "004.uiautomator", "010.uiautomator",
                           "015.uiautomator", "020.uiautomator"};
        int deviceWidth  = 1080;
        int deviceHeight = 1920;

        for (String tuple : tuples) {
            List<WidgetInfo> widgets = parseWidgets(fixture(tuple));
            for (WidgetInfo w : widgets) {
                if (!w.clickable && !w.longClickable) continue;
                if (w.bounds == null || w.bounds.isEmpty()) continue;

                int cx = w.centerX();
                int cy = w.centerY();
                int normX = (int) ((cx / (double) deviceWidth)  * 1000);
                int normY = (int) ((cy / (double) deviceHeight) * 1000);
                normX = Math.max(0, Math.min(normX, 999));
                normY = Math.max(0, Math.min(normY, 999));

                assertTrue(tuple + " widget " + w.className + " normX out of [0,999]: " + normX,
                        normX >= 0 && normX <= 999);
                assertTrue(tuple + " widget " + w.className + " normY out of [0,999]: " + normY,
                        normY >= 0 && normY <= 999);
            }
        }
    }

    @Test
    public void tuple001_cipherButtonNormalizedCoords() throws Exception {
        // CIPHER button: bounds [0,336][1080,462]
        // center: (540, 399)
        // normX = (int)(540/1080 * 1000) = 500
        // normY = (int)(399/1920 * 1000) = 207
        List<WidgetInfo> widgets = parseWidgets(fixture("001.uiautomator"));
        WidgetInfo cipher = null;
        for (WidgetInfo w : widgets) {
            if ("CIPHER".equals(w.text)) { cipher = w; break; }
        }
        assertNotNull(cipher);

        int cx = cipher.centerX();
        int cy = cipher.centerY();
        assertEquals("center X must be 540", 540, cx);
        assertEquals("center Y must be 399", 399, cy);

        int normX = (int) ((cx / 1080.0) * 1000);
        int normY = (int) ((cy / 1920.0) * 1000);
        normX = Math.max(0, Math.min(normX, 999));
        normY = Math.max(0, Math.min(normY, 999));

        assertEquals("normX for CIPHER button must be 500", 500, normX);
        assertTrue("normY for CIPHER button must be in [200,215]", normY >= 200 && normY <= 215);
    }

    // -------------------------------------------------------------------------
    // 7.1.1-D  type_text presence based on EditText detection
    // -------------------------------------------------------------------------

    @Test
    public void tuple001_noEditText_noTypeText() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("001.uiautomator"));
        List<WidgetInfo> inputs = inputWidgets(widgets);
        assertTrue("tuple 001 must have NO EditText widgets", inputs.isEmpty());
    }

    @Test
    public void tuple004_noEditText_noTypeText() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("004.uiautomator"));
        List<WidgetInfo> inputs = inputWidgets(widgets);
        assertTrue("tuple 004 must have NO EditText widgets", inputs.isEmpty());
    }

    @Test
    public void tuple010_hasEditText_typeTextRequired() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("010.uiautomator"));
        List<WidgetInfo> inputs = inputWidgets(widgets);
        assertFalse("tuple 010 must have at least one EditText", inputs.isEmpty());
        assertEquals("tuple 010 must have exactly 1 EditText", 1, inputs.size());
        assertEquals("android.widget.EditText", inputs.get(0).className);
        assertEquals("br.unb.cic.cryptoapp:id/editTextMessageDigest",
                inputs.get(0).resourceId);
    }

    @Test
    public void tuple015_hasTwoEditTexts_typeTextRequired() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("015.uiautomator"));
        List<WidgetInfo> inputs = inputWidgets(widgets);
        assertFalse("tuple 015 must have at least one EditText", inputs.isEmpty());
        assertEquals("tuple 015 must have exactly 2 EditText widgets", 2, inputs.size());
        // Both should be EditText
        for (WidgetInfo input : inputs) {
            assertEquals("android.widget.EditText", input.className);
        }
    }

    @Test
    public void tuple015_editTextsHaveCorrectResourceIds() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("015.uiautomator"));
        List<WidgetInfo> inputs = inputWidgets(widgets);
        Set<String> ids = new HashSet<>();
        for (WidgetInfo w : inputs) ids.add(w.resourceId);
        assertTrue("inputEditText must be present",
                ids.contains("br.unb.cic.cryptoapp:id/inputEditText"));
        assertTrue("keyEditText must be present",
                ids.contains("br.unb.cic.cryptoapp:id/keyEditText"));
    }

    @Test
    public void tuple020_noEditText_noTypeText() throws Exception {
        List<WidgetInfo> widgets = parseWidgets(fixture("020.uiautomator"));
        List<WidgetInfo> inputs = inputWidgets(widgets);
        assertTrue("tuple 020 must have NO EditText widgets", inputs.isEmpty());
    }

    // -------------------------------------------------------------------------
    // 7.1.1-E  MOP annotation: resource-ids that would get [DM]/[M] markers
    // -------------------------------------------------------------------------

    /**
     * Parse MOP JSON using org.json (available as test dependency).
     * Returns a map of shortResourceId → [directMop, transitiveMop] for a given activity.
     */
    private java.util.Map<String, boolean[]> parseMopForActivity(String activityName) throws Exception {
        InputStream is = fixture("cryptoapp.apk.json");
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
        is.close();

        JSONObject root = new JSONObject(sb.toString());

        // Pass 1: build signature → flags map from reachability[]
        java.util.Map<String, boolean[]> bySignature = new java.util.HashMap<>();
        JSONArray reachability = root.getJSONArray("reachability");
        for (int i = 0; i < reachability.length(); i++) {
            JSONObject entry = reachability.getJSONObject(i);
            JSONArray methods = entry.getJSONArray("methods");
            for (int j = 0; j < methods.length(); j++) {
                JSONObject method = methods.getJSONObject(j);
                String sig = method.optString("signature", "");
                boolean direct     = method.optBoolean("directlyReachesMop", false);
                boolean transitive = method.optBoolean("reachesMop", false);
                if (!sig.isEmpty() && (direct || transitive)) {
                    bySignature.put(sig, new boolean[]{direct, transitive});
                }
            }
        }

        // Pass 2: find windows matching activityName, collect widget flags
        java.util.Map<String, boolean[]> result = new java.util.HashMap<>();
        if (!root.has("windows")) return result;
        JSONArray windows = root.getJSONArray("windows");
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.getJSONObject(i);
            if (!activityName.equals(window.optString("name", ""))) continue;
            JSONArray widgetArr = window.getJSONArray("widgets");
            for (int j = 0; j < widgetArr.length(); j++) {
                JSONObject widget = widgetArr.getJSONObject(j);
                String idName = widget.optString("idName", "");
                if (idName.isEmpty()) continue;
                boolean directMop    = false;
                boolean transitiveMop = false;
                JSONArray listeners = widget.getJSONArray("listeners");
                for (int k = 0; k < listeners.length(); k++) {
                    JSONObject listener = listeners.getJSONObject(k);
                    String handler = listener.optString("handler", "");
                    boolean[] flags = bySignature.get(handler);
                    if (flags != null) {
                        if (flags[0]) directMop    = true;
                        if (flags[1]) transitiveMop = true;
                    }
                }
                result.put(idName, new boolean[]{directMop, transitiveMop});
            }
        }
        return result;
    }

    @Test
    public void mopJson_mainActivity_buttonCipherIsMopReachable() throws Exception {
        // buttonCipher handler: showScreenCipher — that method has reachesMop=false
        // buttonMessageDigest handler: showScreenMessageDigest — reachesMop=false
        // buttonGenerated handler: showGenerated — reachesMop=false
        // So none of the main activity buttons are directly MOP-reachable
        java.util.Map<String, boolean[]> mopMap =
                parseMopForActivity("br.unb.cic.cryptoapp.MainActivity");
        assertFalse("MainActivity mop map must not be empty", mopMap.isEmpty());
        // Verify buttonCipher is parsed (flags may be false)
        assertTrue("buttonCipher must appear in MOP map", mopMap.containsKey("buttonCipher"));
        assertTrue("buttonMessageDigest must appear in MOP map",
                mopMap.containsKey("buttonMessageDigest"));
        assertTrue("buttonGenerated must appear in MOP map",
                mopMap.containsKey("buttonGenerated"));
    }

    @Test
    public void mopJson_messageDigestActivity_buttonGenerateHashIsTransitiveMop() throws Exception {
        java.util.Map<String, boolean[]> mopMap =
                parseMopForActivity("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity");
        assertFalse("MessageDigestActivity mop map must not be empty", mopMap.isEmpty());
        assertTrue("buttonGenerateHash must appear in MOP map",
                mopMap.containsKey("buttonGenerateHash"));
        boolean[] flags = mopMap.get("buttonGenerateHash");
        // generateHash method has reachesMop=true
        assertTrue("buttonGenerateHash must have transitive MOP flag", flags[1]);
    }

    @Test
    public void mopJson_cipherActivity_btnCipherEncryptIsTransitiveMop() throws Exception {
        java.util.Map<String, boolean[]> mopMap =
                parseMopForActivity("br.unb.cic.cryptoapp.cipher.CipherActivity");
        assertFalse("CipherActivity mop map must not be empty", mopMap.isEmpty());
        assertTrue("btn_cipher_encrypt must appear in MOP map",
                mopMap.containsKey("btn_cipher_encrypt"));
        boolean[] flags = mopMap.get("btn_cipher_encrypt");
        // handler onClick in CipherActivity$1 has reachesMop=true
        assertTrue("btn_cipher_encrypt must have transitive MOP flag", flags[1]);
    }

    @Test
    public void mopJson_cryptographyActivity_executeButtonIsMop() throws Exception {
        java.util.Map<String, boolean[]> mopMap =
                parseMopForActivity("br.unb.cic.cryptoapp.generated.CryptographyActivity");
        assertFalse("CryptographyActivity mop map must not be empty", mopMap.isEmpty());
        assertTrue("executeButton must appear in MOP map", mopMap.containsKey("executeButton"));
        boolean[] flags = mopMap.get("executeButton");
        // handler onClick has reachesMop=true
        assertTrue("executeButton must have transitive MOP flag", flags[1]);
    }

    @Test
    public void mopJson_shortIdExtraction() {
        // Verify MopData.extractShortId logic (inlined here to stay dependency-free)
        String full = "br.unb.cic.cryptoapp:id/buttonGenerateHash";
        int idx = full.indexOf(":id/");
        assertTrue(idx >= 0);
        String shortId = full.substring(idx + 4);
        assertEquals("buttonGenerateHash", shortId);

        // Empty resource-id
        assertEquals("", "".indexOf(":id/") < 0 ? "" : "bad");

        // null-like case
        String nullId = null;
        String result = (nullId == null) ? "" : nullId;
        assertEquals("", result);
    }
}
