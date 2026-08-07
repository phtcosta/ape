package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The two teardown steps that belong to the trace, and where they sit in the chain.
 *
 * <p>This is the jar-side check the design's Sec. 9.7 asks for, and it is a real ordering proof
 * rather than a reading of the source: the agent's {@code tearDown()} actually runs here, on an
 * instance allocated without its constructor (the {@code sun.misc.Unsafe} idiom
 * {@link StatefulAgentTearDownTest} established), so every step in between fails on its own null
 * collaborator and says so. That is what makes the order observable — the trace interleaves the
 * flushed step record, seven {@code tearDown step failed} lines, and {@code RUN_END}, and their
 * positions are the assertion.
 *
 * <p>It also makes the INV-EXPL-29 argument concrete for the step that matters most: with every
 * intermediate step throwing, {@code RUN_END} is still written. A run whose naming dump fails still
 * says how it ended.
 */
public class StatefulAgentRunEndTest {

    private final PrintStream realOut = System.out;
    private ByteArrayOutputStream captured;

    /**
     * Captures stdout <em>before</em> the context exists, which is not incidental: the sink binds
     * the stream it writes to at construction, and the context constructs it.
     */
    @Before
    public void captureStdoutThenInstallTheContext() {
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        RunContext.resetForTest();
    }

    @After
    public void restoreStdout() {
        System.setOut(realOut);
        RunContext.resetForTest();
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocateInstance(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    /** Tears down an agent whose every collaborator is null, with one step still in flight. */
    private void tearDownWithAStepInFlight() throws Exception {
        RunContext.current().sink().beginStep(199, 8_123L, "com.foo/.Main", true, "S1");
        allocateInstance(StatefulAgentTearDownTest.ThrowingAgent.class).tearDown();
    }

    private List<String> lines() {
        List<String> lines = new ArrayList<>();
        for (String line : new String(captured.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
            lines.add(line);
        }
        return lines;
    }

    /** The index of the first line satisfying a predicate, or a failure naming what was missing. */
    private int indexOf(String what, java.util.function.Predicate<String> match) {
        List<String> lines = lines();
        for (int i = 0; i < lines.size(); i++) {
            if (match.test(lines.get(i))) {
                return i;
            }
        }
        fail("no line " + what + " in:\n" + String.join("\n", lines));
        return -1;
    }

    @Test
    public void theFlushIsTheFirstStepAndRunEndTheLast() throws Exception {
        TestRunSpecs.install();

        tearDownWithAStepInFlight();

        int flushed = indexOf("carrying the flushed step record",
                line -> line.startsWith("{") && line.contains("\"s\":199")
                        && line.contains("\"resolved\":false"));
        int firstFailure = indexOf("reporting a failed teardown step",
                line -> line.contains("tearDown step failed:"));
        int runEnd = indexOf("carrying RUN_END", line -> line.contains("\"type\":\"RUN_END\""));

        assertTrue("the in-flight record is written before anything else can be attempted —"
                + " it is the step that bounds loss", flushed < firstFailure);
        assertTrue("RUN_END is written after every other teardown step has had its chance",
                firstFailure < runEnd);

        // The last sink record, which is the claim INV-SNK-09 makes — later free-text lines from
        // other steps may follow it, and one does here.
        List<String> lines = lines();
        for (int i = runEnd + 1; i < lines.size(); i++) {
            assertFalse("RUN_END must be the last record of the trace, but line " + i + " is one: "
                    + lines.get(i), lines.get(i).startsWith("{"));
        }
    }

    @Test
    public void aFailingNamingDumpStillYieldsRunEnd() throws Exception {
        TestRunSpecs.install();

        tearDownWithAStepInFlight();

        assertTrue("the naming dump is one of the steps that failed here",
                String.join("\n", lines()).contains("tearDown step failed: namingDump"));
        assertEquals("a run whose teardown failed throughout still says how it ended",
                "unknown", runEnd().getString("reason"));
    }

    @Test
    public void theRecordedTerminationReasonReachesTheRecord() throws Exception {
        TestRunSpecs.install();
        RunContext.current().terminated(RunContext.REASON_CRASH, "java.lang.NullPointerException");

        tearDownWithAStepInFlight();

        JSONObject record = runEnd();
        assertEquals("crash", record.getString("reason"));
        assertEquals("java.lang.NullPointerException", record.getString("detail"));
        assertEquals("the flushed step is a written record and is counted as one",
                1, record.getInt("steps"));
    }

    @Test
    public void anArmWithNoLlmEndsWithoutAnLlmCounterBlock() throws Exception {
        TestRunSpecs.install();

        tearDownWithAStepInFlight();

        assertFalse("nothing asked an LLM anything, and no block claims otherwise",
                runEnd().getJSONObject("counters").has("llm"));
    }

    @Test
    public void anLlmArmEndsWithTheCountersItsRunAccumulated() throws Exception {
        // The plan carries a server URL, which is what makes the arm an LLM arm at all; the units
        // are built with it and their counters are what teardown asks for.
        TestRunSpecs.install("ape.llmUrl", "http://10.0.2.2:30000/v1");
        RunContext.current().llmTelemetry().countAttempt();
        RunContext.current().llmTelemetry().screenshotFailed("com.foo/.Secure", "uiautomation");

        tearDownWithAStepInFlight();

        JSONObject llm = runEnd().getJSONObject("counters").getJSONObject("llm");
        assertEquals("one attempt was made", 1, llm.getInt("calls"));
        assertEquals("and it died at the capture", 1, llm.getInt("screenshot_failed"));
        assertEquals(0, llm.getInt("matched"));
    }

    private JSONObject runEnd() throws Exception {
        for (String line : lines()) {
            if (line.startsWith("{") && line.contains("\"type\":\"RUN_END\"")) {
                return new JSONObject(line);
            }
        }
        throw new AssertionError("no RUN_END record in:\n" + String.join("\n", lines()));
    }
}
