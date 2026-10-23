package com.android.commands.monkey;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import org.junit.Test;

import com.android.commands.monkey.MonkeySourceApe.RelaunchTargets;
import com.android.commands.monkey.ape.runtime.KeyOwnership;
import com.android.commands.monkey.ape.runtime.Presets;
import com.android.commands.monkey.ape.runtime.RunSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * The relaunch's launcher list (exploration "Launcher Resolved at Relaunch", INV-EXPL-33): with
 * {@code APE_FIXES} a relaunch picks from the launchers resolved at that moment, falling back to
 * the startup list when the query resolves none; without it the startup list is used and the query
 * is never made, as in APE.
 *
 * <p>Asserted on {@code MonkeySourceApe.RelaunchTargets}, which loads on the JVM; the components are
 * strings and the query a lambda. The plan is passed to the helper, never installed in
 * {@code RunContext}.
 */
public class RelaunchTargetsTest {

    private static final String PATH = "/data/local/tmp/mop-artifact.json";
    private static final String LLM_URL = "http://10.0.2.2:30000/v1";
    private static final List<String> STARTUP = Collections.singletonList("SplashActivity.Green");

    private static RunSpec cell(String preset, String... harnessKeys) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(KeyOwnership.KEY_PRESET, preset);
        for (int i = 0; i < harnessKeys.length; i += 2) {
            entries.put(harnessKeys[i], harnessKeys[i + 1]);
        }
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", 42L, null));
    }

    private static List<RunSpec> apeFixesCells() {
        return Arrays.asList(
                cell(Presets.AS, "ape.staticDataPath", PATH),
                cell(Presets.AS_MOP, "ape.staticDataPath", PATH),
                cell(Presets.AS_LLM, "ape.staticDataPath", PATH, "ape.llmUrl", LLM_URL),
                cell(Presets.AS_MOP_LLM, "ape.staticDataPath", PATH, "ape.llmUrl", LLM_URL));
    }

    @Test
    public void apeFixesTakesTheCurrentLauncher() {
        List<String> current = Collections.singletonList("SplashActivity.Red");
        for (RunSpec spec : apeFixesCells()) {
            assertEquals(current, RelaunchTargets.of(spec, STARTUP, () -> current));
        }
    }

    @Test
    public void emptyQueryFallsBackToStartup() {
        for (RunSpec spec : apeFixesCells()) {
            assertSame(STARTUP, RelaunchTargets.of(spec, STARTUP, Collections::emptyList));
            assertSame(STARTUP, RelaunchTargets.of(spec, STARTUP, () -> null));
        }
    }

    @Test
    public void withoutApeFixesTheQueryIsNotCalled() {
        Supplier<List<String>> never = () -> {
            fail("a cell without APE_FIXES queries the package manager after startup");
            return null;
        };
        assertSame(STARTUP, RelaunchTargets.of(cell(Presets.BASE), STARTUP, never));
        assertSame(STARTUP, RelaunchTargets.of(cell(Presets.MOP, "ape.staticDataPath", PATH), STARTUP, never));
        assertSame(STARTUP, RelaunchTargets.of(
                cell(Presets.AS, "ape.staticDataPath", PATH, "ape.apeFixes", "false"), STARTUP, never));
    }

    @Test
    public void equalListGivesTheSamePick() {
        List<String> startup = Arrays.asList("A", "B", "C");
        List<String> launchers = RelaunchTargets.of(cell(Presets.AS, "ape.staticDataPath", PATH), startup,
                () -> Arrays.asList("A", "B", "C"));
        Random before = new Random(42);
        Random after = new Random(42);
        assertEquals(startup.get(before.nextInt(startup.size())), launchers.get(after.nextInt(launchers.size())));
        assertEquals("one draw each, so the streams stay aligned", before.nextLong(), after.nextLong());
    }
}
