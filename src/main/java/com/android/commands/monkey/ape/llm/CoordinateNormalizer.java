package com.android.commands.monkey.ape.llm;

/**
 * Converts between device pixels and Qwen3-VL's normalized coordinate space.
 *
 * Qwen3-VL reads and writes coordinates in the [0, 1000) range for both x and y
 * axes, independent of the actual device resolution. {@link #normalize} converts
 * a model answer to device pixels; {@link #toNormalized} converts a device pixel
 * to the coordinate the prompt shows. Both clamp, and they are the only
 * implementation of either conversion in the jar. Within one decision both are
 * called with the same {width, height}, which makes a coordinate copied from the
 * prompt map back within two pixels of the pixel it was computed from, toward
 * the origin, for dimensions below 2000 px (INV-LLM-13).
 *
 * Reference: https://github.com/QwenLM/Qwen3-VL/issues/1486
 */
public final class CoordinateNormalizer {

    /** The largest coordinate of the answer space on either axis: the space is [0, 1000). */
    public static final int MAX_NORMALIZED = 999;

    // Static utility class — no instances needed
    private CoordinateNormalizer() {}

    /**
     * Convert Qwen3-VL normalized coordinates to device pixels.
     *
     * Formula:
     *   pixelX = (int)((qwenX / 1000.0) * deviceWidth)
     *   pixelY = (int)((qwenY / 1000.0) * deviceHeight)
     *
     * Both axes are clamped to [0, dimension - 1] so the result is always a valid
     * tap target on the screen.
     *
     * @param qwenX       x coordinate in [0, 1000) from the model
     * @param qwenY       y coordinate in [0, 1000) from the model
     * @param deviceWidth device width in pixels
     * @param deviceHeight device height in pixels
     * @return int[]{pixelX, pixelY} clamped to device bounds
     */
    public static int[] normalize(int qwenX, int qwenY, int deviceWidth, int deviceHeight) {
        int pixelX = (int) ((qwenX / 1000.0) * deviceWidth);
        int pixelY = (int) ((qwenY / 1000.0) * deviceHeight);

        // Clamp to device bounds so we never produce an off-screen coordinate
        pixelX = Math.max(0, Math.min(pixelX, deviceWidth - 1));
        pixelY = Math.max(0, Math.min(pixelY, deviceHeight - 1));

        return new int[]{pixelX, pixelY};
    }

    /**
     * Convert a device pixel to Qwen3-VL normalized coordinates (the inverse of
     * {@link #normalize}, up to truncation).
     *
     * Formula:
     *   normX = (int)(pixelX * 1000.0 / width)
     *   normY = (int)(pixelY * 1000.0 / height)
     *
     * Both axes are clamped to [0, 999], so a pixel outside the display maps to
     * the nearest edge value instead of leaving the model's answer space.
     *
     * @param pixelX x coordinate in device pixels
     * @param pixelY y coordinate in device pixels
     * @param width  device width in pixels, positive
     * @param height device height in pixels, positive
     * @return int[]{normX, normY} in [0, 999]
     */
    public static int[] toNormalized(int pixelX, int pixelY, int width, int height) {
        int normX = (int) (pixelX * 1000.0 / width);
        int normY = (int) (pixelY * 1000.0 / height);

        normX = Math.max(0, Math.min(normX, MAX_NORMALIZED));
        normY = Math.max(0, Math.min(normY, MAX_NORMALIZED));

        return new int[]{normX, normY};
    }
}
