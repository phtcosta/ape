package com.android.commands.monkey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.android.commands.monkey.MonkeySourceApe.NullTreeRecovery;
import com.android.commands.monkey.ape.runtime.KeyOwnership;
import com.android.commands.monkey.ape.runtime.Presets;
import com.android.commands.monkey.ape.runtime.RunSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The null-tree recovery (component-triggering "Launcher-Owned Null-Tree Recovery", INV-CT-18,
 * design D-B16): the three cells that mount {@code MOP_LAUNCHER} relaunch the app under test,
 * count {@code nullRelaunches} and queue {@code NOP}; the other three take upstream's
 * {@code stopTopActivity()} and {@code ACTIVATE}.
 *
 * <p>The choice is asserted on {@code MonkeySourceApe.NullTreeRecovery}, which loads on the JVM.
 * The branch that calls it is asserted over the source, as in {@code MonkeySourceApeStepWiringTest},
 * because {@code MonkeySourceApe} itself cannot be class-loaded off-device. The plan is passed to
 * the helper, never installed in {@code RunContext}, so nothing leaks into the next test.
 */
public class MonkeySourceApeNullTreeRecoveryTest {

    private static final String SOURCE =
            "src/main/java/com/android/commands/monkey/MonkeySourceApe.java";
    private static final String PATH = "/data/local/tmp/mop-artifact.json";
    private static final String LLM_URL = "http://10.0.2.2:30000/v1";

    private static RunSpec cell(String preset, String... harnessKeys) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(KeyOwnership.KEY_PRESET, preset);
        for (int i = 0; i < harnessKeys.length; i += 2) {
            entries.put(harnessKeys[i], harnessKeys[i + 1]);
        }
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", 42L, null));
    }

    @Test
    public void launcherCellsRelaunchTheApp() {
        assertTrue(NullTreeRecovery.relaunches(cell(Presets.MOP, "ape.staticDataPath", PATH)));
        assertTrue(NullTreeRecovery.relaunches(cell(Presets.AS_MOP, "ape.staticDataPath", PATH)));
        assertTrue(NullTreeRecovery.relaunches(cell(Presets.AS_MOP_LLM,
                "ape.staticDataPath", PATH, "ape.llmUrl", LLM_URL)));
    }

    @Test
    public void cellsWithoutTheLauncherTakeUpstreamsPath() {
        assertFalse(NullTreeRecovery.relaunches(cell(Presets.BASE)));
        assertFalse(NullTreeRecovery.relaunches(cell(Presets.AS, "ape.staticDataPath", PATH)));
        assertFalse(NullTreeRecovery.relaunches(cell(Presets.AS_LLM,
                "ape.staticDataPath", PATH, "ape.llmUrl", LLM_URL)));
    }

    @Test
    public void aMopCellWithTheLauncherSwitchedOffTakesUpstreamsPath() {
        assertFalse(NullTreeRecovery.relaunches(cell(Presets.MOP,
                "ape.staticDataPath", PATH, "ape.activityTriggerEnabled", "false")));
    }

    @Test
    public void theRelaunchBranchStopsTheAgentRestartsTheMainAppAndCounts() throws IOException {
        String body = generateEventsBody();
        int choice = body.indexOf("NullTreeRecovery.relaunches(RunContext.current().spec())");
        assertTrue("the recovery asks the plan: " + body, choice >= 0);
        int stopped = body.indexOf("mAgent.onActivityStopped();", choice);
        int restart = body.indexOf("restartPackage(getMainApp(), false, \"null tree\");", choice);
        int counted = body.indexOf("sink().count(RunCounter.NULL_RELAUNCHES);", choice);
        int upstream = body.indexOf("stopTopActivity();", choice);
        assertTrue("onActivityStopped, then the restart, then the counter, before the upstream branch",
                choice < stopped && stopped < restart && restart < counted && counted < upstream);
        assertFalse("the relaunch never draws a main app from mRandom", body.contains("randomlyPickMainApp"));
        assertEquals(1, count(body, "RunCounter.NULL_RELAUNCHES"));
        assertEquals(1, count(body, "stopTopActivity();"));
    }

    @Test
    public void theRelaunchStepQueuesNopAndUpstreamsQueuesActivate() throws IOException {
        String body = generateEventsBody();
        assertEquals(1, count(body, "action = relaunched ? Action.NOP : Action.ACTIVATE;"));
        assertEquals(1, count(body, "Action.ACTIVATE"));
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
        throw new AssertionError("unbalanced braces in generateEvents");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
