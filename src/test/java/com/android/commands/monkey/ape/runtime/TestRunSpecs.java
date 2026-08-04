package com.android.commands.monkey.ape.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plans stated as values, for tests of the parameters a plan controls.
 *
 * <p>Five parameters used to be non-final {@code Config} fields whose only reason to be mutable was
 * that a test wanted to toggle them. They are now resolved into the {@link RunSpec}, so a test that
 * wants a different value states a different plan and installs it — no static mutation, no
 * save/restore pair around the assertion, and no risk that a forgotten restore leaks into the next
 * test.
 *
 * <p>Every helper here installs into {@link RunContext}, which is process-wide. A test that
 * installs SHOULD reset in an {@code @After}, because the JVM suite shares a process the way a run
 * never does.
 */
public final class TestRunSpecs {

    /**
     * The path a MOP arm states. Its <em>presence</em> is what activates {@link Feature#MOP} — the
     * resolver does not open the file — so a test can name one that does not exist.
     */
    public static final String MOP_PATH = "/data/local/tmp/static_analysis.json";

    private TestRunSpecs() {
    }

    /** Resolves a plan from the given {@code ape.*} key/value pairs, without installing it. */
    public static RunSpec spec(String... keyValues) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            entries.put(keyValues[i], keyValues[i + 1]);
        }
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", 42L, null));
    }

    /** Resolves and installs a plan; with no arguments, the jar defaults. */
    public static RunSpec install(String... keyValues) {
        RunSpec spec = spec(keyValues);
        RunContext.installForTest(spec);
        return spec;
    }

    /**
     * Resolves and installs a MOP arm: the given pairs plus {@code ape.mopDataPath}, which the
     * MOP-family keys depend on. Stating one of them without it is a resolution abort, not a plan.
     */
    public static RunSpec installMop(String... keyValues) {
        String[] withPath = new String[keyValues.length + 2];
        withPath[0] = "ape.mopDataPath";
        withPath[1] = MOP_PATH;
        System.arraycopy(keyValues, 0, withPath, 2, keyValues.length);
        return install(withPath);
    }
}
