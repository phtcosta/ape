package com.android.commands.monkey;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The three per-step wirings of {@code MonkeySourceApe.generateEvents} that isolate-guidance-groups
 * adds: every guard BACK increments {@code RUN_END.counters.guardBacks} (event-sink), the step's
 * record receives {@code dec.rot} (llm-infrastructure INV-LLM-14), and the fuzz toss draws from
 * {@code StepRng}'s own stream on every step with {@code APE_FIXES} (decision-pipeline INV-DP-13)
 * and from the helper stream, only when fuzzing is on, without it.
 *
 * <p>Asserted over the source because {@code MonkeySourceApe} cannot be class-loaded off-device
 * (its {@code UiAutomation} field pulls in {@code android.app.IUiAutomationConnection}, absent from
 * the JVM test classpath). That the sink counts a guard BACK with no record open is pinned by
 * {@code NdjsonSinkTest.runEndCarriesTheRoutingCountersInFull}; this class pins that the call is
 * made where the BACK is sent.
 */
public class MonkeySourceApeStepWiringTest {

    private static final String SOURCE =
            "src/main/java/com/android/commands/monkey/MonkeySourceApe.java";

    @Test
    public void everyGuardBackIncrementsItsCounter() throws IOException {
        String body = generateEventsBody();
        int backs = count(body, "generateKeyBackEvent();");
        assertTrue("the foreign-activity guard's BACK must be in generateEvents: " + body, backs >= 1);
        assertEquals("each BACK generateEvents sends is a guard BACK, and each one is counted",
                backs, count(body, "sink().count(RunCounter.GUARD_BACKS);"));
        int back = body.indexOf("generateKeyBackEvent();");
        int counted = body.indexOf("sink().count(RunCounter.GUARD_BACKS);", back);
        int exit = body.indexOf("return;", back);
        assertTrue("the counter is incremented before the guard returns", counted > back && counted < exit);
    }

    @Test
    public void theStepRecordReceivesTheDisplayRotation() throws IOException {
        String body = generateEventsBody();
        assertEquals(1, count(body, "AndroidDevice.getRotation()"));
        int update = body.indexOf("mAgent.updateState(topComp, info)");
        int written = body.indexOf("sink().rotation(rotation)");
        assertTrue("dec.rot is written once updateState has opened the step's record",
                update >= 0 && written > update);
    }

    @Test
    public void theFuzzTossFollowsApeFixes() throws IOException {
        String body = generateEventsBody().replaceAll("\\s+", " ");
        assertEquals(1, count(body, "stepRng().fuzzToss("));
        assertEquals(1, count(body, "RandomHelper.toss("));
        assertTrue("with APE_FIXES the own stream is drawn first, every step; without it APE's "
                + "helper-stream toss is drawn only when fuzzing is on: " + body,
                body.contains("boolean fuzz = spec.has(Feature.APE_FIXES)"
                        + " ? RunContext.current().stepRng().fuzzToss(rate) && spec.has(Feature.FUZZING)"
                        + " : spec.has(Feature.FUZZING) && RandomHelper.toss(rate);"));
    }

    @Test
    public void theFuzzKindsReachOnlyAStepThatRanUpdateState() throws IOException {
        String body = generateEventsBody().replaceAll("\\s+", " ");
        assertEquals(1, count(body, "sink().fuzzing("));
        int update = body.indexOf("mAgent.updateState(topComp, info); updated = true;");
        int fuzzed = body.indexOf("generateEventsForAction(fuzzingAction);");
        int written = body.indexOf("if (updated) { RunContext.current().sink().fuzzing("
                + "mAgent.getTimestamp(), ApeEvents.fuzzKinds(((FuzzAction) fuzzingAction)"
                + ".getFuzzingEvents()));");
        assertTrue("updated is set where updateState returns: " + body, update >= 0);
        assertTrue("dec.fz is written after the fuzz events are queued, and only when this step "
                + "ran updateState (the null-tree recovery did not): " + body,
                fuzzed >= 0 && written > fuzzed);
        assertTrue("updated starts false on every step",
                body.contains("boolean updated = false;"));
    }

    /** The body of {@code generateEvents()}, comments removed, brace-matched from its signature. */
    private static String generateEventsBody() throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(SOURCE)), StandardCharsets.UTF_8);
        String stripped = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        int start = stripped.indexOf("protected void generateEvents()");
        assertTrue("generateEvents not found -- it was renamed or removed", start >= 0);
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
        throw new AssertionError("unbalanced braces from the generateEvents signature");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
