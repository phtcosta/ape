package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.AndroidDevice;
import com.android.commands.monkey.ape.tree.GUITree;

/**
 * Everything the pipeline does to turn a live screen into the string a request can carry.
 *
 * <p>Three things happen here and they fail differently, which is why they stay three calls rather
 * than one. Determining the device's dimensions cannot fail — it falls back until it has a number.
 * Capturing can, and when it does the reason is worth attributing (a secure window is the LLM arm's
 * dominant failure mode). Encoding can too, for unrelated reasons. The engine sequences them and
 * names each failure, so a null capture and a null encode never collapse into one cause.
 *
 * <p><b>What this unit does not own is the trace.</b> The failing stage is readable here
 * ({@link #getLastFailureStage()}) and reported by {@link LlmTelemetry}: attribution is one
 * concern, emission another, and keeping them apart is what lets the whole failure path be
 * exercised without a Logger.
 */
public final class ScreenshotStep {

    // The dimensions assumed when neither the display nor the tree yields a usable bound. They are
    // a 1080p portrait phone, which is what the corpus overwhelmingly is; the coordinate mapper
    // scales the model's normalized answer against whatever comes back here, so a wrong guess
    // misplaces taps rather than failing the step.
    private static final int DEFAULT_WIDTH = 1080;
    private static final int DEFAULT_HEIGHT = 1920;

    private final ScreenshotCapture capture;
    private final ImageProcessor imageProcessor;

    public ScreenshotStep() {
        this.capture = new ScreenshotCapture();
        this.imageProcessor = new ImageProcessor();
    }

    /**
     * The display's pixel dimensions, by three sources in descending authority.
     *
     * <p>The display bounds are asked first and the tree's root node second, because a tree can be
     * stale in a way the display cannot; the constants are the last resort. A source that throws or
     * reports a non-positive edge is passed over rather than trusted, so a partially-usable answer
     * still contributes the axis it does have.
     *
     * @param tree the current tree, consulted only if the display refuses; may be null
     * @return {@code {width, height}}, never null and always positive
     */
    public int[] deviceDimensions(GUITree tree) {
        int deviceWidth = DEFAULT_WIDTH;
        int deviceHeight = DEFAULT_HEIGHT;
        try {
            Rect displayBounds = AndroidDevice.getDisplayBounds();
            if (displayBounds.right > 0)  deviceWidth  = displayBounds.right;
            if (displayBounds.bottom > 0) deviceHeight = displayBounds.bottom;
        } catch (Exception ignored) {
            // Fallback: try GUITree root node bounds
            try {
                if (tree != null && tree.getRootNode() != null) {
                    Rect rootBounds = tree.getRootNode().getBoundsInScreen();
                    if (rootBounds.right > 0)  deviceWidth  = rootBounds.right;
                    if (rootBounds.bottom > 0) deviceHeight = rootBounds.bottom;
                }
            } catch (Exception ignored2) { /* use defaults */ }
        }
        return new int[]{deviceWidth, deviceHeight};
    }

    /**
     * Grab the screen.
     *
     * @param width the display width {@link #deviceDimensions} reported
     * @param height the display height it reported
     * @return the PNG bytes, or {@code null} when the screen could not be read (INV-LLM-02), in
     *         which case {@link #getLastFailureStage()} names the path that failed last
     */
    public byte[] capture(int width, int height) {
        return capture.capture(width, height);
    }

    /**
     * Resize the capture and base64-encode it for the request body.
     *
     * @param pngBytes the capture
     * @return the encoded image, or {@code null} when processing failed
     */
    public String encode(byte[] pngBytes) {
        return imageProcessor.processScreenshot(pngBytes);
    }

    /**
     * Which capture path failed last, for the diagnostic the failure emits.
     *
     * <p>Valid only between a null {@link #capture} and the next invocation of it — the seam is
     * reset at the start of every capture, so a stale stage can never be attributed to a later call
     * (the same discipline as {@code SglangClient.getLastErrorCause}, INV-LLM-08).
     *
     * @return {@code surface_control}, {@code uiautomation}, or {@code null} when nothing failed
     */
    public String getLastFailureStage() {
        return capture.getLastFailureStage();
    }
}
