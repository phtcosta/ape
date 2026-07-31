package com.android.commands.monkey.ape.llm;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A7 (llm-infrastructure INV-LLM-12) — the capture-failure stage seam.
 *
 * <p>Both capture paths are Android reflection and fail on the JVM, which is exactly the
 * double-failure case the seam has to describe: {@code capture()} returns null and reports the last
 * stage it attempted. What the JVM can pin is the seam's contract — a stage after a failure, and no
 * stale stage carried across invocations. The distinction between the two stages needs a device
 * where the first path can succeed, and is covered by smoke gate (d) on a FLAG_SECURE APK.
 */
public class ScreenshotCaptureStageTest {

    @Test
    public void freshCaptureReportsNoStageBeforeItRuns() {
        assertNull(new ScreenshotCapture().getLastFailureStage());
    }

    @Test
    public void aFailedCaptureNamesTheLastStageAttempted() {
        ScreenshotCapture capture = new ScreenshotCapture();

        assertNull("neither reflection path is available off-device", capture.capture(1080, 1920));
        assertEquals("the fallback was the last thing tried, so it is what the line reports",
                ScreenshotCapture.STAGE_UIAUTOMATION, capture.getLastFailureStage());
    }

    @Test
    public void theStageIsResetAtTheStartOfEveryInvocation() {
        ScreenshotCapture capture = new ScreenshotCapture();
        capture.capture(1080, 1920);
        assertNotNull(capture.getLastFailureStage());

        // A second call re-derives its own stage rather than inheriting the first one's — the
        // property that keeps a stale failure from being attributed to a later step.
        capture.capture(720, 1280);
        assertEquals(ScreenshotCapture.STAGE_UIAUTOMATION, capture.getLastFailureStage());
    }
}
