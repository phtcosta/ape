package com.android.commands.monkey.ape.llm;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for CoordinateNormalizer.
 *
 * Verifies that Qwen3-VL [0,1000) normalized coordinates are converted correctly
 * to device pixel coordinates and that out-of-range inputs are clamped.
 *
 * Tests run on the JVM only; no Android device required.
 */
public class CoordinateNormalizerTest {

    // ---------------------------------------------------------------------------
    // Test 1: Center of 1080x1920 display
    //   normalize(500, 500, 1080, 1920)
    //   pixelX = (int)(500/1000.0 * 1080) = (int)(540.0) = 540
    //   pixelY = (int)(500/1000.0 * 1920) = (int)(960.0) = 960
    // ---------------------------------------------------------------------------
    @Test
    public void testCenter_1080x1920() {
        int[] result = CoordinateNormalizer.normalize(500, 500, 1080, 1920);
        assertEquals(540, result[0]);
        assertEquals(960, result[1]);
    }

    // ---------------------------------------------------------------------------
    // Test 2: Edge clamping — negative coordinates → [0, 0]
    // ---------------------------------------------------------------------------
    @Test
    public void testEdgeClamping_negative() {
        int[] result = CoordinateNormalizer.normalize(-10, -10, 1080, 1920);
        assertEquals(0, result[0]);
        assertEquals(0, result[1]);
    }

    // ---------------------------------------------------------------------------
    // Test 3: Edge clamping — over-1000 input → clamped to [deviceWidth-1, deviceHeight-1]
    //   normalize(1050, 1050, 1080, 1920)
    //   raw pixelX = (int)(1050/1000.0 * 1080) = (int)(1134.0) = 1134 → clamped to 1079
    //   raw pixelY = (int)(1050/1000.0 * 1920) = (int)(2016.0) = 2016 → clamped to 1919
    // ---------------------------------------------------------------------------
    @Test
    public void testEdgeClamping_over1000() {
        int[] result = CoordinateNormalizer.normalize(1050, 1050, 1080, 1920);
        assertEquals(1079, result[0]);
        assertEquals(1919, result[1]);
    }

    // ---------------------------------------------------------------------------
    // Test 4: Zero coordinates → [0, 0]
    // ---------------------------------------------------------------------------
    @Test
    public void testZeroCoordinates() {
        int[] result = CoordinateNormalizer.normalize(0, 0, 1080, 1920);
        assertEquals(0, result[0]);
        assertEquals(0, result[1]);
    }

    // ---------------------------------------------------------------------------
    // Test 5: Different device dimensions — 720x1280
    //   normalize(500, 500, 720, 1280)
    //   pixelX = (int)(500/1000.0 * 720) = (int)(360.0) = 360
    //   pixelY = (int)(500/1000.0 * 1280) = (int)(640.0) = 640
    // ---------------------------------------------------------------------------
    @Test
    public void testDifferentDeviceDimensions_720x1280() {
        int[] result = CoordinateNormalizer.normalize(500, 500, 720, 1280);
        assertEquals(360, result[0]);
        assertEquals(640, result[1]);
    }

    // ---------------------------------------------------------------------------
    // Test 6: Result array always has exactly 2 elements
    // ---------------------------------------------------------------------------
    @Test
    public void testResultArrayLength() {
        int[] result = CoordinateNormalizer.normalize(250, 750, 1080, 1920);
        assertEquals(2, result.length);
    }

    // ---------------------------------------------------------------------------
    // Test 7: Maximum valid input (999, 999) stays within device bounds
    // ---------------------------------------------------------------------------
    @Test
    public void testMaxValidInput() {
        int[] result = CoordinateNormalizer.normalize(999, 999, 1080, 1920);
        // pixelX = (int)(999/1000.0 * 1080) = (int)(1078.92) = 1078 — within [0, 1079]
        // pixelY = (int)(999/1000.0 * 1920) = (int)(1918.08) = 1918 — within [0, 1919]
        assertTrue("pixelX must be within device width", result[0] >= 0 && result[0] < 1080);
        assertTrue("pixelY must be within device height", result[1] >= 0 && result[1] < 1920);
    }
}
