package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.NdjsonSink;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A screenshot failure is not a breaker failure (design D-B18, llm-routing INV-RTR-27).
 *
 * <p>The exit itself is driven through {@link LlmEngine#captureFailed} on a real client and
 * breaker. {@code selectAction} cannot reach it off-device — {@code ScreenshotStep.deviceDimensions}
 * raises {@code NoClassDefFoundError} before the capture ({@code ScreenshotStepTest}) — so that the
 * null-capture branch takes this exit, and that the exit tells the breaker nothing, is asserted
 * over the source.
 */
public class LlmEngineScreenshotFailureTest {

    private static final String URL = "http://localhost:9999/v1";
    private static final String SOURCE =
            "src/main/java/com/android/commands/monkey/ape/llm/LlmEngine.java";

    private NdjsonSink sink;

    @Before
    public void setUp() throws Exception {
        sink = new NdjsonSink(new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));
    }

    private LlmClient client(String openMs) {
        return new LlmClient(TestRunSpecs.spec("ape.llm", "true", "ape.llmUrl", URL,
                "ape.llmBreakerFailures", "3", "ape.llmBreakerOpenMs", openMs).llm(), sink);
    }

    private static LlmEngine engine(LlmClient client, LlmTelemetry telemetry) {
        return new LlmEngine(null, null, client, null, null, telemetry);
    }

    @Test
    public void captureFailuresLeaveAClosedBreakerAlone() {
        LlmClient client = client("60000");
        client.recordFailure();
        LlmTelemetry telemetry = new LlmTelemetry(client::getTripCount, sink, false);
        LlmEngine engine = engine(client, telemetry);

        for (int i = 0; i < 5; i++) {
            assertNull(engine.captureFailed(null, "uiautomation"));
            assertEquals("screenshot", engine.lastFallback());
        }

        LlmCircuitBreaker breaker = client.getBreaker();
        assertEquals("the failure recorded before is the only one", 1, breaker.getFailureCount());
        assertEquals("CLOSED", breaker.getStateName());
        assertEquals("five capture failures past the threshold trip nothing", 0,
                breaker.getTripCount());
        assertEquals(5, telemetry.counters().screenshotFailed);
    }

    @Test
    public void aCaptureFailureDoesNotReopenAHalfOpenProbe() {
        LlmClient client = client("0");
        for (int i = 0; i < 3; i++) {
            client.recordFailure();
        }
        LlmCircuitBreaker breaker = client.getBreaker();
        assertEquals("OPEN", breaker.getStateName());
        assertTrue("an elapsed open period admits the probe", client.allows());
        assertEquals("HALF_OPEN", breaker.getStateName());

        LlmEngine engine = engine(client, new LlmTelemetry(client::getTripCount, sink, false));
        assertNull(engine.captureFailed(null, null));

        assertEquals("screenshot", engine.lastFallback());
        assertEquals("HALF_OPEN", breaker.getStateName());
        assertEquals(1, breaker.getTripCount());
        assertEquals(3, breaker.getFailureCount());
    }

    @Test
    public void theNullCaptureBranchTakesTheExitThatRecordsNoFailure() throws IOException {
        String select = body("public ModelAction selectAction(");
        int capture = select.indexOf("screenshot.capture(");
        int branch = select.indexOf("if (pngBytes == null) {", capture);
        int exit = select.indexOf("return captureFailed(state, screenshot.getLastFailureStage());",
                branch);
        int next = select.indexOf("screenshot.encode(", branch);
        assertTrue("the capture is followed by its null branch", capture >= 0 && branch > capture);
        assertTrue("the null branch returns through captureFailed", exit > branch && exit < next);
        assertFalse("nothing in the null branch tells the breaker",
                select.substring(branch, exit).contains("recordFailure"));

        String helper = body("ModelAction captureFailed(");
        assertFalse(helper.contains("recordFailure"));
        assertFalse(helper.contains("client."));
        assertTrue(helper.contains("lastFallback = FALLBACK_SCREENSHOT;"));
        assertTrue(helper.contains("telemetry.screenshotFailed("));
    }

    /** A method's body in {@link #SOURCE}, comments removed, brace-matched from its signature. */
    private static String body(String signature) throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(SOURCE)), StandardCharsets.UTF_8);
        String stripped = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        int start = stripped.indexOf(signature);
        assertTrue(signature + " not found -- it was renamed or removed", start >= 0);
        int open = stripped.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return stripped.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced braces from " + signature);
    }
}
