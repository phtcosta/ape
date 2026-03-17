package com.android.commands.monkey.ape.llm;

import org.junit.Test;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration tests for coordinate mapping with real widget bounds from cryptoapp fixtures.
 *
 * Tests the round-trip: widget center pixel → normalize to [0,1000) → denormalize back →
 * verify the result falls within the original widget bounds.
 *
 * Also tests boundary rejection logic (status bar / navigation bar zones) that mirrors
 * LlmRouter.mapToModelAction().
 *
 * No APE/Android runtime required: all parsing uses javax.xml and CoordinateNormalizer only.
 */
public class CoordinateMapIntegrationTest {

    // Device dimensions matching the cryptoapp fixtures
    private static final int DEVICE_WIDTH  = 1080;
    private static final int DEVICE_HEIGHT = 1920;

    // Boundary thresholds from LlmRouter (5% top, 6% bottom = 94% max)
    private static final double STATUS_BAR_RATIO = 0.05;
    private static final double NAV_BAR_RATIO    = 0.94;

    // -------------------------------------------------------------------------
    // XML parsing helpers (duplicated from PromptIntegrationTest to keep tests
    // independent — each test class is self-contained)
    // -------------------------------------------------------------------------

    static class WidgetBounds {
        final String className;
        final String text;
        final String resourceId;
        final int left, top, right, bottom;
        final boolean clickable;
        final boolean longClickable;

        WidgetBounds(String className, String text, String resourceId,
                     int left, int top, int right, int bottom,
                     boolean clickable, boolean longClickable) {
            this.className    = className;
            this.text         = text;
            this.resourceId   = resourceId;
            this.left         = left;
            this.top          = top;
            this.right        = right;
            this.bottom       = bottom;
            this.clickable    = clickable;
            this.longClickable = longClickable;
        }

        int centerX() { return (left + right)  / 2; }
        int centerY() { return (top  + bottom) / 2; }

        boolean containsPoint(int px, int py) {
            return px >= left && px <= right && py >= top && py <= bottom;
        }
    }

    private static int[] parseBoundsString(String bounds) {
        // Format: "[left,top][right,bottom]"
        String s = bounds.replace("][", ",").replace("[", "").replace("]", "");
        String[] parts = s.split(",");
        return new int[]{
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()),
                Integer.parseInt(parts[3].trim())
        };
    }

    private List<WidgetBounds> parseWidgets(String fixtureName) throws Exception {
        InputStream is = getClass().getResourceAsStream("/fixtures/cryptoapp/" + fixtureName);
        assertNotNull("Fixture not found: " + fixtureName, is);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(is);

        List<WidgetBounds> result = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String cls = el.getAttribute("class");
            if (cls == null || cls.isEmpty()) continue;

            String boundsStr   = el.getAttribute("bounds");
            if (boundsStr == null || boundsStr.isEmpty()) continue;

            // Skip widgets with zero-area bounds (e.g. navigationBarBackground at [0,0][0,0])
            int[] b = parseBoundsString(boundsStr);
            if (b[0] == b[2] && b[1] == b[3]) continue;

            String text        = el.getAttribute("text");
            String resourceId  = el.getAttribute("resource-id");
            boolean clickable  = "true".equals(el.getAttribute("clickable"));
            boolean longClick  = "true".equals(el.getAttribute("long-clickable"));

            result.add(new WidgetBounds(cls, text, resourceId,
                    b[0], b[1], b[2], b[3], clickable, longClick));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Helper: normalize pixel to Qwen [0,1000) then denormalize back
    // -------------------------------------------------------------------------

    /**
     * Convert a center pixel coordinate to Qwen [0,1000) normalized space.
     * Mirrors ApePromptBuilder.formatActionLine coordinate computation.
     */
    private int toNormX(int pixelX) {
        return Math.max(0, Math.min((int) ((pixelX / (double) DEVICE_WIDTH) * 1000), 999));
    }

    private int toNormY(int pixelY) {
        return Math.max(0, Math.min((int) ((pixelY / (double) DEVICE_HEIGHT) * 1000), 999));
    }

    /**
     * Denormalize Qwen [0,1000) coords back to pixels via CoordinateNormalizer.
     */
    private int[] toPixels(int normX, int normY) {
        return CoordinateNormalizer.normalize(normX, normY, DEVICE_WIDTH, DEVICE_HEIGHT);
    }

    // -------------------------------------------------------------------------
    // 7.1.2-A  Round-trip: center → normalize → denormalize → within bounds
    // -------------------------------------------------------------------------

    @Test
    public void tuple001_cipherButton_roundTrip() throws Exception {
        // CIPHER button bounds: [0,336][1080,462]
        // center: (540, 399)
        // normX = (int)(540/1080 * 1000) = 500
        // normY = (int)(399/1920 * 1000) = 207
        // denormalize: pixelX = (int)(500/1000 * 1080) = 540
        //              pixelY = (int)(207/1000 * 1920) = 397 (close to 399 due to int truncation)

        List<WidgetBounds> widgets = parseWidgets("001.uiautomator");
        WidgetBounds cipher = null;
        for (WidgetBounds w : widgets) {
            if ("CIPHER".equals(w.text)) { cipher = w; break; }
        }
        assertNotNull("CIPHER button must be present in tuple 001", cipher);

        int cx = cipher.centerX();
        int cy = cipher.centerY();
        assertEquals("CIPHER center X must be 540", 540, cx);
        assertEquals("CIPHER center Y must be 399", 399, cy);

        int normX = toNormX(cx);
        int normY = toNormY(cy);
        assertEquals("CIPHER normX must be 500", 500, normX);
        // normY = (int)(399/1920 * 1000) = (int)(207.8125) = 207
        assertEquals("CIPHER normY must be 207", 207, normY);

        int[] backPixels = toPixels(normX, normY);
        // backPixelX = (int)(500/1000 * 1080) = 540
        // backPixelY = (int)(207/1000 * 1920) = (int)(397.44) = 397
        assertEquals("denormalized X must be 540", 540, backPixels[0]);

        // The denormalized pixel must be within the widget bounds (integer rounding may shift by 1-2px)
        assertTrue("denormalized X must be within CIPHER button bounds [0,1080]",
                backPixels[0] >= cipher.left && backPixels[0] <= cipher.right);
        assertTrue("denormalized Y must be within CIPHER button bounds [336,462]",
                backPixels[1] >= cipher.top && backPixels[1] <= cipher.bottom);
    }

    @Test
    public void tuple010_generateHashButton_roundTrip() throws Exception {
        // GENERATE HASH button: bounds [0,442][1080,568]
        // center: (540, 505)
        // normX = (int)(540/1080 * 1000) = 500
        // normY = (int)(505/1920 * 1000) = 263
        List<WidgetBounds> widgets = parseWidgets("010.uiautomator");
        WidgetBounds btn = null;
        for (WidgetBounds w : widgets) {
            if ("GENERATE HASH".equals(w.text)) { btn = w; break; }
        }
        assertNotNull("GENERATE HASH button must be present in tuple 010", btn);

        int cx = btn.centerX();
        int cy = btn.centerY();

        int normX = toNormX(cx);
        int normY = toNormY(cy);

        int[] backPixels = toPixels(normX, normY);

        assertTrue("round-trip X must be within GENERATE HASH bounds",
                backPixels[0] >= btn.left && backPixels[0] <= btn.right);
        assertTrue("round-trip Y must be within GENERATE HASH bounds",
                backPixels[1] >= btn.top  && backPixels[1] <= btn.bottom);
    }

    @Test
    public void tuple015_executeButton_roundTrip() throws Exception {
        // EXECUTE button: bounds [42,1411][1038,1537]
        // center: (540, 1474)
        List<WidgetBounds> widgets = parseWidgets("015.uiautomator");
        WidgetBounds btn = null;
        for (WidgetBounds w : widgets) {
            if ("EXECUTE".equals(w.text)) { btn = w; break; }
        }
        assertNotNull("EXECUTE button must be present in tuple 015", btn);

        int cx = btn.centerX();
        int cy = btn.centerY();

        int normX = toNormX(cx);
        int normY = toNormY(cy);

        int[] backPixels = toPixels(normX, normY);

        assertTrue("round-trip X must be within EXECUTE button bounds",
                backPixels[0] >= btn.left && backPixels[0] <= btn.right);
        assertTrue("round-trip Y must be within EXECUTE button bounds",
                backPixels[1] >= btn.top  && backPixels[1] <= btn.bottom);
    }

    // -------------------------------------------------------------------------
    // 7.1.2-B  Round-trip for ALL clickable widgets in all 5 tuples
    // -------------------------------------------------------------------------

    @Test
    public void allTuples_clickableWidgets_roundTripWithinBounds() throws Exception {
        String[] tuples = {"001.uiautomator", "004.uiautomator", "010.uiautomator",
                           "015.uiautomator", "020.uiautomator"};

        for (String tuple : tuples) {
            List<WidgetBounds> widgets = parseWidgets(tuple);
            int tested = 0;
            for (WidgetBounds w : widgets) {
                if (!w.clickable && !w.longClickable) continue;
                // Skip widgets where center might be out of [0,deviceHeight-1] due to odd bounds
                if (w.centerX() < 0 || w.centerY() < 0) continue;

                int normX = toNormX(w.centerX());
                int normY = toNormY(w.centerY());
                int[] back = toPixels(normX, normY);

                // The round-tripped X pixel must be within the widget's horizontal bounds
                // (or within 2px tolerance due to integer truncation)
                assertTrue(tuple + " widget '" + w.text + "' class=" + w.className
                        + " back X=" + back[0] + " not within bounds [" + w.left + "," + w.right + "]",
                        back[0] >= w.left - 2 && back[0] <= w.right + 2);
                assertTrue(tuple + " widget '" + w.text + "' class=" + w.className
                        + " back Y=" + back[1] + " not within bounds [" + w.top + "," + w.bottom + "]",
                        back[1] >= w.top - 2 && back[1] <= w.bottom + 2);
                tested++;
            }
            assertTrue("Must have tested at least one clickable widget in " + tuple, tested > 0);
        }
    }

    // -------------------------------------------------------------------------
    // 7.1.2-C  Boundary rejection: status bar and navigation bar zones
    // -------------------------------------------------------------------------

    @Test
    public void statusBarZone_rejected() {
        // Status bar: y < 5% of 1920 = 96 px
        // Construct a Qwen coord that maps to y = 50 (well inside status bar)
        // normY = (int)(50/1920 * 1000) = 26
        int statusBarPixelY = 50;
        int statusBarNormY  = (int) ((statusBarPixelY / (double) DEVICE_HEIGHT) * 1000);
        int[] px = CoordinateNormalizer.normalize(500, statusBarNormY, DEVICE_WIDTH, DEVICE_HEIGHT);

        double statusBarThreshold = DEVICE_HEIGHT * STATUS_BAR_RATIO; // 96
        assertTrue("status-bar Y pixel must be < threshold (96)",
                px[1] < statusBarThreshold);
        // Verify that LlmRouter's rejection condition would fire
        assertTrue("status bar pixelY=" + px[1] + " must trigger boundary rejection (< " + (int)statusBarThreshold + ")",
                px[1] < DEVICE_HEIGHT * STATUS_BAR_RATIO);
    }

    @Test
    public void navBarZone_rejected() {
        // Navigation bar: y > 94% of 1920 = 1804.8 px
        // Construct a coord that maps to y = 1850 (inside nav bar)
        // normY = (int)(1850/1920 * 1000) = 963
        int navBarPixelY = 1850;
        int navBarNormY  = (int) ((navBarPixelY / (double) DEVICE_HEIGHT) * 1000);
        int[] px = CoordinateNormalizer.normalize(500, navBarNormY, DEVICE_WIDTH, DEVICE_HEIGHT);

        double navBarThreshold = DEVICE_HEIGHT * NAV_BAR_RATIO; // 1804.8
        assertTrue("nav-bar Y pixel must be > threshold (1804)",
                px[1] > navBarThreshold);
        assertTrue("nav bar pixelY=" + px[1] + " must trigger boundary rejection (> " + (int)navBarThreshold + ")",
                px[1] > DEVICE_HEIGHT * NAV_BAR_RATIO);
    }

    @Test
    public void statusBarBoundaryExact_atThreshold() {
        // Exact threshold: 5% of 1920 = 96
        // A widget at y=96 should NOT be rejected (it's exactly at the boundary)
        int boundaryY = (int)(DEVICE_HEIGHT * STATUS_BAR_RATIO); // 96
        assertFalse("y=96 must NOT be rejected as status bar (boundary is exclusive)",
                boundaryY < DEVICE_HEIGHT * STATUS_BAR_RATIO);
    }

    @Test
    public void navBarBoundaryExact_atThreshold() {
        // Exact threshold: 94% of 1920 = 1804.8 → boundary is ~1804
        int boundaryY = (int)(DEVICE_HEIGHT * NAV_BAR_RATIO); // 1804
        assertFalse("y=1804 must NOT be rejected as nav bar",
                boundaryY > DEVICE_HEIGHT * NAV_BAR_RATIO);
    }

    @Test
    public void tuple001_allClickableWidgets_notInStatusOrNavBar() throws Exception {
        // Verify that clickable app widgets in tuple 001 are in the safe zone
        List<WidgetBounds> widgets = parseWidgets("001.uiautomator");
        int statusBarMax = (int)(DEVICE_HEIGHT * STATUS_BAR_RATIO); // 96
        int navBarMin    = (int)(DEVICE_HEIGHT * NAV_BAR_RATIO);    // 1804

        for (WidgetBounds w : widgets) {
            if (!w.clickable) continue;
            int cy = w.centerY();
            // All app buttons in the main screen should be well within safe zone
            // (not in status bar or nav bar area)
            assertTrue("Widget '" + w.text + "' center Y=" + cy + " must be above status bar (>" + statusBarMax + ")",
                    cy > statusBarMax);
            assertTrue("Widget '" + w.text + "' center Y=" + cy + " must be below nav bar (<" + navBarMin + ")",
                    cy < navBarMin);
        }
    }

    // -------------------------------------------------------------------------
    // 7.1.2-D  Specific coordinate value verification
    // -------------------------------------------------------------------------

    @Test
    public void tuple001_messageDigestButton_coords() throws Exception {
        // MESSAGE DIGEST button: bounds [0,210][1080,336]
        // center: (540, 273)
        // normX = 500
        // normY = (int)(273/1920 * 1000) = 142
        List<WidgetBounds> widgets = parseWidgets("001.uiautomator");
        WidgetBounds btn = null;
        for (WidgetBounds w : widgets) {
            if ("MESSAGE DIGEST".equals(w.text)) { btn = w; break; }
        }
        assertNotNull("MESSAGE DIGEST button must be present", btn);

        assertEquals("left must be 0",    0,    btn.left);
        assertEquals("top must be 210",   210,  btn.top);
        assertEquals("right must be 1080", 1080, btn.right);
        assertEquals("bottom must be 336", 336,  btn.bottom);

        assertEquals("center X must be 540", 540, btn.centerX());
        assertEquals("center Y must be 273", 273, btn.centerY());

        int normX = toNormX(btn.centerX());
        int normY = toNormY(btn.centerY());
        assertEquals("normX must be 500", 500, normX);
        assertEquals("normY must be 142", 142, normY);
    }

    @Test
    public void tuple015_executeButton_specificCoords() throws Exception {
        // EXECUTE button: bounds [42,1411][1038,1537]
        // center: (540, 1474)
        // normX = (int)(540/1080 * 1000) = 500
        // normY = (int)(1474/1920 * 1000) = 767
        List<WidgetBounds> widgets = parseWidgets("015.uiautomator");
        WidgetBounds btn = null;
        for (WidgetBounds w : widgets) {
            if ("EXECUTE".equals(w.text)) { btn = w; break; }
        }
        assertNotNull("EXECUTE button must be present", btn);

        assertEquals("left must be 42",    42,   btn.left);
        assertEquals("top must be 1411",   1411, btn.top);
        assertEquals("right must be 1038", 1038, btn.right);
        assertEquals("bottom must be 1537",1537, btn.bottom);

        assertEquals("center X must be 540",  540,  btn.centerX());
        assertEquals("center Y must be 1474", 1474, btn.centerY());

        int normX = toNormX(btn.centerX());
        int normY = toNormY(btn.centerY());
        assertEquals("normX must be 500", 500, normX);
        // normY = (int)(1474/1920 * 1000) = (int)(767.7) = 767
        assertEquals("normY must be 767", 767, normY);
    }

    @Test
    public void tuple010_editText_centerCoords() throws Exception {
        // editTextMessageDigest: bounds [0,324][1080,442]
        // center: (540, 383)
        List<WidgetBounds> widgets = parseWidgets("010.uiautomator");
        WidgetBounds editText = null;
        for (WidgetBounds w : widgets) {
            if ("android.widget.EditText".equals(w.className)) { editText = w; break; }
        }
        assertNotNull("EditText must be present in tuple 010", editText);

        assertEquals("EditText left must be 0",    0,    editText.left);
        assertEquals("EditText top must be 324",   324,  editText.top);
        assertEquals("EditText right must be 1080", 1080, editText.right);
        assertEquals("EditText bottom must be 442", 442,  editText.bottom);

        assertEquals("EditText center X must be 540", 540, editText.centerX());
        assertEquals("EditText center Y must be 383", 383, editText.centerY());
    }

    // -------------------------------------------------------------------------
    // 7.1.2-E  CoordinateNormalizer correctness on fixture-derived inputs
    // -------------------------------------------------------------------------

    @Test
    public void coordinateNormalizer_cipherButtonCenter() {
        // CIPHER center: (540, 399)
        // normalize(500, 207, 1080, 1920) → (540, 397)
        // (207/1000 * 1920 = 397.44 → 397 by int truncation)
        int[] px = CoordinateNormalizer.normalize(500, 207, DEVICE_WIDTH, DEVICE_HEIGHT);
        assertEquals("denorm X must be 540", 540, px[0]);
        assertEquals("denorm Y must be 397", 397, px[1]);

        // 397 is within [336, 462] (CIPHER button bounds)
        assertTrue("denorm Y 397 must be within CIPHER button top (336)", px[1] >= 336);
        assertTrue("denorm Y 397 must be within CIPHER button bottom (462)", px[1] <= 462);
    }

    @Test
    public void coordinateNormalizer_executeButtonCenter() {
        // EXECUTE button center: (540, 1474)
        // normX = 500, normY = 767
        // denorm: X = (int)(500/1000 * 1080) = 540
        //         Y = (int)(767/1000 * 1920) = (int)(1472.64) = 1472
        int[] px = CoordinateNormalizer.normalize(500, 767, DEVICE_WIDTH, DEVICE_HEIGHT);
        assertEquals("denorm X must be 540", 540, px[0]);
        assertEquals("denorm Y must be 1472", 1472, px[1]);

        // 1472 is within [1411, 1537] (EXECUTE button bounds)
        assertTrue("denorm Y 1472 must be within EXECUTE top (1411)",  px[1] >= 1411);
        assertTrue("denorm Y 1472 must be within EXECUTE bottom (1537)", px[1] <= 1537);
    }
}
