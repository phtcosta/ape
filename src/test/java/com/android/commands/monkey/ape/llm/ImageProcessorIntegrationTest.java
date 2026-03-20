package com.android.commands.monkey.ape.llm;

import org.junit.Ignore;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.*;

/**
 * Integration tests for ImageProcessor using real cryptoapp PNG fixtures.
 *
 * Two categories of tests:
 *
 * 1. Tests that DO run on the JVM (no Android runtime needed):
 *    - Load PNG bytes from classpath fixtures (verifies fixture presence and readability)
 *    - calculateResizedDimensions() with actual screenshot dimensions (1080x1920)
 *
 * 2. Tests marked @Ignore that require Android runtime:
 *    - processScreenshot() uses android.graphics.BitmapFactory which needs native libskia
 *
 * Fixture dimensions are 1080x1920 (portrait phone screenshots).
 * Expected resize: longest edge 1920 > 1000 → scale = 1000/1920 → 563x1000.
 */
public class ImageProcessorIntegrationTest {

    // Cryptoapp fixture PNG files
    private static final String[] FIXTURE_PNGS = {
            "001.png", "004.png", "010.png", "015.png", "020.png"
    };

    // Known screenshot dimensions for the cryptoapp fixtures
    private static final int SCREENSHOT_WIDTH  = 1080;
    private static final int SCREENSHOT_HEIGHT = 1920;

    // -------------------------------------------------------------------------
    // PNG bytes loading — no Android required
    // -------------------------------------------------------------------------

    @Test
    public void allFixturePngs_loadableFromClasspath() throws Exception {
        for (String filename : FIXTURE_PNGS) {
            InputStream is = getClass().getResourceAsStream("/fixtures/cryptoapp/" + filename);
            assertNotNull("PNG fixture must be loadable: " + filename, is);
            is.close();
        }
    }

    @Test
    public void allFixturePngs_nonEmpty() throws Exception {
        for (String filename : FIXTURE_PNGS) {
            InputStream is = getClass().getResourceAsStream("/fixtures/cryptoapp/" + filename);
            assertNotNull("Fixture must exist: " + filename, is);

            byte[] bytes = readAllBytes(is);
            assertTrue("PNG fixture must be non-empty: " + filename, bytes.length > 0);
        }
    }

    @Test
    public void allFixturePngs_validPngMagicBytes() throws Exception {
        // PNG magic bytes: 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
        byte[] pngMagic = {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        for (String filename : FIXTURE_PNGS) {
            InputStream is = getClass().getResourceAsStream("/fixtures/cryptoapp/" + filename);
            assertNotNull("Fixture must exist: " + filename, is);

            byte[] bytes = readAllBytes(is);
            assertTrue("PNG must have at least 8 bytes: " + filename, bytes.length >= 8);

            for (int i = 0; i < pngMagic.length; i++) {
                assertEquals("PNG magic byte " + i + " mismatch in " + filename,
                        pngMagic[i], bytes[i]);
            }
        }
    }

    @Test
    public void fixture001_png_largeEnoughToBeScreenshot() throws Exception {
        // Real screenshots are typically > 50KB
        InputStream is = getClass().getResourceAsStream("/fixtures/cryptoapp/001.png");
        assertNotNull(is);
        byte[] bytes = readAllBytes(is);
        assertTrue("001.png must be a non-trivial screenshot (> 1000 bytes)", bytes.length > 1000);
    }

    // -------------------------------------------------------------------------
    // calculateResizedDimensions — pure Java, fully testable with fixture dimensions
    // -------------------------------------------------------------------------

    @Test
    public void calculateResizedDimensions_screenshotDimensions_resizesCorrectly() {
        // 1080x1920 portrait screenshot: longest edge is 1920 > 1000
        // scale = 1000 / 1920 = 0.52083...
        // newWidth  = round(1080 * 0.52083) = round(562.5) = 563
        // newHeight = round(1920 * 0.52083) = round(1000.0) = 1000
        int[] dims = ImageProcessor.calculateResizedDimensions(
                SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, 1000);

        assertEquals("resized height must be 1000 (longest edge capped)", 1000, dims[1]);
        assertEquals("resized width must be 563 (proportional to 1080/1920)", 563, dims[0]);
    }

    @Test
    public void calculateResizedDimensions_screenshotDimensions_aspectRatioPreserved() {
        int[] dims = ImageProcessor.calculateResizedDimensions(
                SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, 1000);

        // Aspect ratio: 1080/1920 = 0.5625
        // After resize: dims[0]/dims[1] ≈ 0.5625
        double originalRatio = (double) SCREENSHOT_WIDTH / SCREENSHOT_HEIGHT;
        double resizedRatio  = (double) dims[0] / dims[1];

        // Allow 1% tolerance due to rounding
        assertEquals("aspect ratio must be preserved within 1%",
                originalRatio, resizedRatio, 0.01);
    }

    @Test
    public void calculateResizedDimensions_screenshotDimensions_longestEdgeIs1000() {
        int[] dims = ImageProcessor.calculateResizedDimensions(
                SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, 1000);

        int longestEdge = Math.max(dims[0], dims[1]);
        assertEquals("longest edge of resized image must be exactly 1000", 1000, longestEdge);
    }

    @Test
    public void calculateResizedDimensions_screenshotDimensions_noUpscaling() {
        int[] dims = ImageProcessor.calculateResizedDimensions(
                SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, 1000);

        assertTrue("resized width must be <= original width",  dims[0] <= SCREENSHOT_WIDTH);
        assertTrue("resized height must be <= original height", dims[1] <= SCREENSHOT_HEIGHT);
    }

    @Test
    public void calculateResizedDimensions_screenshotDimensions_positiveResult() {
        int[] dims = ImageProcessor.calculateResizedDimensions(
                SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, 1000);

        assertTrue("resized width must be > 0",  dims[0] > 0);
        assertTrue("resized height must be > 0", dims[1] > 0);
    }

    @Test
    public void calculateResizedDimensions_resultsAreInt2Array() {
        int[] dims = ImageProcessor.calculateResizedDimensions(
                SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, 1000);

        assertNotNull("result must not be null", dims);
        assertEquals("result must be int[2]", 2, dims.length);
    }

    // -------------------------------------------------------------------------
    // processScreenshot — requires Android BitmapFactory, marked @Ignore
    // -------------------------------------------------------------------------

    @Test
    @Ignore("Requires Android runtime - BitmapFactory needs native libskia libraries")
    public void processScreenshot_fixture001_returnsNonNullBase64() throws Exception {
        InputStream is = getClass().getResourceAsStream("/fixtures/cryptoapp/001.png");
        assertNotNull(is);
        byte[] pngBytes = readAllBytes(is);
        assertTrue("001.png must be non-empty", pngBytes.length > 0);

        ImageProcessor processor = new ImageProcessor();
        String base64 = processor.processScreenshot(pngBytes, true);

        assertNotNull("processScreenshot must return non-null for 001.png", base64);
        assertFalse("base64 result must not be empty", base64.isEmpty());
        assertTrue("result must be valid base64", base64.matches("[A-Za-z0-9+/]+=*"));
    }

    @Test
    @Ignore("Requires Android runtime - BitmapFactory needs native libskia libraries")
    public void processScreenshot_allFixtures_returnNonNull() throws Exception {
        ImageProcessor processor = new ImageProcessor();

        for (String filename : FIXTURE_PNGS) {
            InputStream is = getClass().getResourceAsStream("/fixtures/cryptoapp/" + filename);
            assertNotNull(is);
            byte[] pngBytes = readAllBytes(is);

            String base64 = processor.processScreenshot(pngBytes, true);
            assertNotNull("processScreenshot must return non-null for " + filename, base64);
            assertFalse("base64 must not be empty for " + filename, base64.isEmpty());
        }
    }

    @Test
    @Ignore("Requires Android runtime - BitmapFactory needs native libskia libraries")
    public void processScreenshot_fixture001_longestEdgeAtMost1000() throws Exception {
        // After processing a 1080x1920 screenshot, longest edge should be 1000
        InputStream is = getClass().getResourceAsStream("/fixtures/cryptoapp/001.png");
        assertNotNull(is);
        byte[] pngBytes = readAllBytes(is);

        ImageProcessor processor = new ImageProcessor();
        String base64 = processor.processScreenshot(pngBytes, true);
        assertNotNull(base64);

        // Decode and check dimensions (requires Android API)
        byte[] jpegBytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
        android.graphics.Bitmap decoded =
                android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        assertNotNull("decoded JPEG must not be null", decoded);

        int longestEdge = Math.max(decoded.getWidth(), decoded.getHeight());
        assertTrue("longest edge must be <= 1000px after processing, was " + longestEdge,
                longestEdge <= 1000);
    }

    @Test
    @Ignore("Requires Android runtime - BitmapFactory needs native libskia libraries")
    public void processScreenshot_nullInput_returnsNull() {
        ImageProcessor processor = new ImageProcessor();
        assertNull("null input must return null", processor.processScreenshot(null, true));
    }

    @Test
    @Ignore("Requires Android runtime - BitmapFactory needs native libskia libraries")
    public void processScreenshot_emptyInput_returnsNull() {
        ImageProcessor processor = new ImageProcessor();
        assertNull("empty byte array must return null",
                processor.processScreenshot(new byte[0], true));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        is.close();
        return buf.toByteArray();
    }
}
