package com.android.commands.monkey.ape.llm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The screen-to-string step: its dimensions, and its two distinguishable failures.
 *
 * <p>Capture and encoding cannot succeed off a device, which is what makes their failure paths the
 * interesting half here: the engine has to tell a screen it could not read from an image it could
 * not process, and both arrive as null. The seam is what separates them.
 */
public class ScreenshotStepTest {

    // deviceDimensions() has no test here, and the reason is a property of the method rather than
    // an omission. Its first source is AndroidDevice.getDisplayBounds(), which reaches an Android
    // reflection surface the JVM suite does not carry: the call raises NoClassDefFoundError, an
    // Error, which the historical catch (Exception) around the tree fallback does not cover and
    // which therefore leaves the method. That is unchanged from the block this unit extracted — on
    // a device the classes exist and the fallback chain runs — so the honest record is that the
    // dimension probe is device-only, not that it is untested by oversight.

    @Test
    public void aFreshStepHasNoFailureStageToReport() {
        // The seam reports a failure, not the absence of a capture: nothing has been attempted, so
        // there is no stage to name and the engine has nothing to attribute.
        assertNull(new ScreenshotStep().getLastFailureStage());
    }

    @Test
    public void aFailedCaptureNamesTheStageThatFailedLast() {
        ScreenshotStep step = new ScreenshotStep();

        assertNull("no screen off a device", step.capture(1080, 1920));
        assertEquals("the UiAutomation fallback is the last path tried",
                "uiautomation", step.getLastFailureStage());
    }

    @Test
    public void aNullCaptureAndANullEncodeAreDistinguishable() {
        // Both steps return null and the engine reports them as different causes — cause=screenshot
        // carries the failing stage, cause=image does not exist as a stage at all. Passing a
        // capture that never happened through the encoder is the shape the engine must not take.
        ScreenshotStep step = new ScreenshotStep();

        assertNull(step.encode(null));
        assertNull("encoding failure leaves no capture stage behind", step.getLastFailureStage());
    }
}
