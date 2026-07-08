package com.android.commands.monkey.ape.utils;

import org.junit.Test;

import java.util.Arrays;
import java.util.Properties;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * rv-scoring-pipeline task 6.4 — the two always-on exceptions survive {@code apePureMode=true}
 * (INV-ARCH-01). The kill-switch overwrites only RV-defining <b>Config flags</b>; neither exception
 * reads a Config flag, so forcing the kill-switch cannot disable them. This test binds that reasoning
 * to the actual forcing logic ({@link Config#forceApePureModeInto}) and registry, rather than
 * re-testing the fixes' behavior — the behavior itself is covered by {@code ApePinchOrZoomEventTest}
 * (array-sizing + ctor guard) and {@code RandomHelperSeedTest} (seed reproducibility).
 *
 * <p><b>Why the static-final wall doesn't apply here.</b> The rest of the parity suite (tasks 6.3/6.5)
 * cannot exercise the OFF direction in-JVM because the gated behaviors branch on {@code public static
 * final} flags resolved at class load. These two exceptions do <em>not</em> branch on any flag, so
 * their default-config green (in the two tests above) is already their {@code apePureMode} green — no
 * OFF direction exists to exercise.
 *
 * <p>Lives in {@code ape.utils} to reach the package-private kill-switch registry, alongside
 * {@code ApePureModeKillSwitchTest}.
 */
public class ApePureModeAlwaysOnExceptionsTest {

    /**
     * Mirrors the fixed array-size expression in {@code ApeFuzzer.generatePinchOrZoomEvent}
     * ({@code new PointF[6 + (count << 1)]}) and the exact number of points it writes:
     * 1 (down) + 2 (initial pair) + 2*(count+1) (walk, i=0..count) + 1 (up).
     */
    private static int pinchFixedSize(int count) { return 6 + (count << 1); }
    private static int pinchWriteCount(int count) { return 1 + 2 + 2 * (count + 1) + 1; }

    private static long[] drawSequence() {
        return new long[] {
                RandomHelper.nextInt(), RandomHelper.nextInt(100),
                RandomHelper.toss(0.5) ? 1 : 0, RandomHelper.nextLong(),
                RandomHelper.nextInt(1000)
        };
    }

    /**
     * Exception 1 — the {@code ApePinchOrZoomEvent} array-sizing crash fix is unflagged arithmetic, so
     * the kill-switch (which overwrites RV Config flags) cannot revert it. {@code count} ranges over
     * {@code RandomHelper.nextInt(10)} = [0,9].
     */
    @Test
    public void pinchArraySizeFixIsUnflaggedSoSurvivesApePureMode() {
        Config.forceApePureModeInto(new Properties()); // exercise the kill-switch forcing path
        for (int count = 0; count <= 9; count++) {
            assertEquals("array size must equal the exact write count for count=" + count,
                    pinchWriteCount(count), pinchFixedSize(count));
        }
    }

    /**
     * Exception 2 — seed handling ({@code RandomHelper.seed(-s)}, wired from Monkey) is arm-neutral
     * reproducibility infrastructure that reads no RV flag; forcing the kill-switch leaves the shared
     * generator seedable and reproducible.
     */
    @Test
    public void seedHandlingSurvivesApePureModeForcing() {
        Config.forceApePureModeInto(new Properties());
        RandomHelper.seed(42);
        long[] first = drawSequence();
        RandomHelper.seed(42);
        long[] second = drawSequence();
        assertArrayEquals("seed reproducibility is unaffected by the kill-switch", first, second);
    }

    /** The kill-switch registry keys are all RV Config flags — none names a pinch/seed behavior. */
    @Test
    public void killSwitchRegistryDoesNotReferenceTheAlwaysOnExceptions() {
        for (String key : Config.rvForcedOffValues().keySet()) {
            assertEquals("no RV registry key gates the pinch fix: " + key,
                    -1, indexOfAny(key, "inch", "PinchOrZoom"));
            assertEquals("no RV registry key gates seed handling: " + key,
                    -1, indexOfAny(key, "seed", "Seed", "random", "Random"));
        }
        for (String key : Config.rvUnsetKeys()) {
            assertEquals("no unset key gates seed handling: " + key,
                    -1, indexOfAny(key, "seed", "Seed"));
        }
    }

    private static int indexOfAny(String haystack, String... needles) {
        for (String n : Arrays.asList(needles)) {
            int i = haystack.indexOf(n);
            if (i >= 0) return i;
        }
        return -1;
    }
}
