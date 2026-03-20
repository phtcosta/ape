package com.android.commands.monkey.ape.llm;

import org.junit.Ignore;
import org.junit.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Unit tests for ImageProcessor.
 *
 * <p>ImageProcessor has two distinct parts:
 * <ol>
 *   <li>{@link ImageProcessor#processScreenshot(byte[])} — requires Android
 *       {@code android.graphics.BitmapFactory} which in turn needs native
 *       Android libraries (libskia, etc.). These are NOT available in the JVM
 *       test environment, so the method tests are marked
 *       {@code @Ignore("Requires Android runtime")}.</li>
 *   <li>{@link ImageProcessor#calculateResizedDimensions(int, int, int)} — a
 *       pure-Java static method extracted specifically to enable JVM unit tests.
 *       All non-trivial logic is in this method; it is fully exercised here.</li>
 * </ol>
 */
public class ImageProcessorTest {

    // -------------------------------------------------------------------------
    // calculateResizedDimensions — pure Java, fully testable
    // -------------------------------------------------------------------------

    @Test
    public void calculateResizedDimensions_landscapeImage_longestEdgeCapped() {
        // 1920×1080 landscape → longest edge is 1920, max 1000 → scale = 1000/1920
        int[] dims = ImageProcessor.calculateResizedDimensions(1920, 1080, 1000);
        assertEquals("width must be capped at maxEdge", 1000, dims[0]);
        assertTrue("height must be proportionally scaled down", dims[1] < 1080);
        assertTrue("height must be > 0", dims[1] > 0);
    }

    @Test
    public void calculateResizedDimensions_portraitImage_longestEdgeCapped() {
        // 1080×1920 portrait → longest edge is 1920, max 1000
        int[] dims = ImageProcessor.calculateResizedDimensions(1080, 1920, 1000);
        assertEquals("height must be capped at maxEdge", 1000, dims[1]);
        assertTrue("width must be proportionally scaled down", dims[0] < 1080);
        assertTrue("width must be > 0", dims[0] > 0);
    }

    @Test
    public void calculateResizedDimensions_squareImageLargerThanMax_cappedToMax() {
        int[] dims = ImageProcessor.calculateResizedDimensions(2000, 2000, 1000);
        assertEquals("width must equal maxEdge for square image",  1000, dims[0]);
        assertEquals("height must equal maxEdge for square image", 1000, dims[1]);
    }

    @Test
    public void calculateResizedDimensions_imageSmallerThanMax_unchanged() {
        // 800×600 is already within 1000px on both axes → no resize
        int[] dims = ImageProcessor.calculateResizedDimensions(800, 600, 1000);
        assertEquals("width must be unchanged when already within maxEdge",  800, dims[0]);
        assertEquals("height must be unchanged when already within maxEdge", 600, dims[1]);
    }

    @Test
    public void calculateResizedDimensions_exactlyMaxEdge_unchanged() {
        int[] dims = ImageProcessor.calculateResizedDimensions(1000, 500, 1000);
        assertEquals(1000, dims[0]);
        assertEquals(500,  dims[1]);
    }

    @Test
    public void calculateResizedDimensions_aspectRatioPreserved_landscape() {
        // 2000×1000 → scale = 1000/2000 = 0.5 → 1000×500
        int[] dims = ImageProcessor.calculateResizedDimensions(2000, 1000, 1000);
        assertEquals(1000, dims[0]);
        assertEquals(500,  dims[1]);
    }

    @Test
    public void calculateResizedDimensions_aspectRatioPreserved_portrait() {
        // 500×2000 → scale = 1000/2000 = 0.5 → 250×1000
        int[] dims = ImageProcessor.calculateResizedDimensions(500, 2000, 1000);
        assertEquals(250,  dims[0]);
        assertEquals(1000, dims[1]);
    }

    @Test
    public void calculateResizedDimensions_zeroWidth_returnsOriginalDimensions() {
        // Defensive: zero dimension → return original
        int[] dims = ImageProcessor.calculateResizedDimensions(0, 1920, 1000);
        assertEquals(0,    dims[0]);
        assertEquals(1920, dims[1]);
    }

    @Test
    public void calculateResizedDimensions_zeroHeight_returnsOriginalDimensions() {
        int[] dims = ImageProcessor.calculateResizedDimensions(1080, 0, 1000);
        assertEquals(1080, dims[0]);
        assertEquals(0,    dims[1]);
    }

    @Test
    public void calculateResizedDimensions_zeroMaxEdge_returnsOriginalDimensions() {
        int[] dims = ImageProcessor.calculateResizedDimensions(1080, 1920, 0);
        assertEquals(1080, dims[0]);
        assertEquals(1920, dims[1]);
    }

    @Test
    public void calculateResizedDimensions_returnsTwoElements() {
        int[] dims = ImageProcessor.calculateResizedDimensions(1080, 1920, 1000);
        assertNotNull(dims);
        assertEquals("result must be int[2]", 2, dims.length);
    }

    // -------------------------------------------------------------------------
    // processScreenshot — requires Android Bitmap/BitmapFactory native libs
    // -------------------------------------------------------------------------

    @Test
    @Ignore("Requires Android runtime: BitmapFactory needs native libskia libraries")
    public void processScreenshot_withFixturePng_returnsValidBase64() throws Exception {
        // Load 001.png from test fixtures
        Path fixturePath = Paths.get("src/test/resources/fixtures/cryptoapp/001.png");
        byte[] pngBytes = Files.readAllBytes(fixturePath);
        assertTrue("fixture PNG must not be empty", pngBytes.length > 0);

        ImageProcessor processor = new ImageProcessor();
        String base64 = processor.processScreenshot(pngBytes, true);

        assertNotNull("processScreenshot must return non-null for a valid PNG", base64);
        assertFalse("base64 result must not be empty", base64.isEmpty());
        // Verify it is valid base64 (no whitespace, contains only base64 chars)
        assertTrue("result must be valid base64",
                base64.matches("[A-Za-z0-9+/]+=*"));
    }

    @Test
    @Ignore("Requires Android runtime: BitmapFactory needs native libskia libraries")
    public void processScreenshot_withFixturePng_longestEdgeAtMost1000px() throws Exception {
        // Verify the resize constraint: longest edge <= 1000 after processing
        Path fixturePath = Paths.get("src/test/resources/fixtures/cryptoapp/001.png");
        byte[] pngBytes = Files.readAllBytes(fixturePath);

        ImageProcessor processor = new ImageProcessor();
        String base64 = processor.processScreenshot(pngBytes, true);

        assertNotNull(base64);
        // Decode and check dimensions
        byte[] jpegBytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
        android.graphics.Bitmap decoded =
                android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        assertNotNull(decoded);
        int longestEdge = Math.max(decoded.getWidth(), decoded.getHeight());
        assertTrue("longest edge must be <= 1000px after processing, was " + longestEdge,
                longestEdge <= 1000);
    }

    @Test
    @Ignore("Requires Android runtime: BitmapFactory needs native libskia libraries")
    public void processScreenshot_withNullInput_returnsNull() {
        ImageProcessor processor = new ImageProcessor();
        assertNull("null input must produce null output",
                processor.processScreenshot(null, true));
    }

    @Test
    @Ignore("Requires Android runtime: BitmapFactory needs native libskia libraries")
    public void processScreenshot_withEmptyInput_returnsNull() {
        ImageProcessor processor = new ImageProcessor();
        assertNull("empty byte array must produce null output",
                processor.processScreenshot(new byte[0], true));
    }
}
