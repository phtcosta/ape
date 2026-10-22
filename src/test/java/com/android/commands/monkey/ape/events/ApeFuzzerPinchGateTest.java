package com.android.commands.monkey.ape.events;

import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.utils.RandomHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * The fuzzing pinch under {@link Feature#APE_FIXES} (gh-tbd-base-equals-ape D9): with the member the
 * pinch branch appends one {@link ApePinchOrZoomEvent}, without it none, and in both cases it
 * consumes the same {@link RandomHelper} draws.
 *
 * <p>{@code ApeFuzzer} loads {@code android.view.KeyEvent} and {@code android.view.KeyCharacterMap}
 * in its static initializer and the pinch builds {@code android.graphics.PointF}s. The surefire
 * classpath excludes the Android jars (pom.xml {@code classpathDependencyExcludes}), so each case
 * is skipped unless those classes are present as JVM test stubs.
 */
public class ApeFuzzerPinchGateTest {

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1794;

    @Before
    public void requireAndroidClasses() {
        assumeTrue("android.graphics.PointF, android.view.KeyEvent and android.view.KeyCharacterMap "
                + "are not on the JVM test classpath",
                present("android.graphics.PointF") && present("android.view.KeyEvent")
                        && present("android.view.KeyCharacterMap"));
    }

    @After
    public void clearPlan() {
        RunContext.resetForTest();
    }

    @Test
    public void withApeFixesThePinchAppendsOneEvent() {
        TestRunSpecs.install("ape.addons", "true");
        assertTrue(RunContext.current().spec().has(Feature.APE_FIXES));
        RandomHelper.seed(7L);
        List<ApeEvent> events = new ArrayList<>();
        ApeFuzzer.generatePinchOrZoomEvent(events, WIDTH, HEIGHT);
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof ApePinchOrZoomEvent);
    }

    @Test
    public void withoutApeFixesThePinchAppendsNothing() {
        TestRunSpecs.install();
        assertFalse(RunContext.current().spec().has(Feature.APE_FIXES));
        RandomHelper.seed(7L);
        List<ApeEvent> events = new ArrayList<>();
        ApeFuzzer.generatePinchOrZoomEvent(events, WIDTH, HEIGHT);
        assertTrue(events.isEmpty());
    }

    @Test
    public void addonsWithApeFixesOffAppendsNothing() {
        TestRunSpecs.install("ape.addons", "true", "ape.apeFixes", "false");
        assertFalse(RunContext.current().spec().has(Feature.APE_FIXES));
        RandomHelper.seed(7L);
        List<ApeEvent> events = new ArrayList<>();
        ApeFuzzer.generatePinchOrZoomEvent(events, WIDTH, HEIGHT);
        assertTrue(events.isEmpty());
    }

    /** Over several seeds the stream position after the call is the same with and without the member. */
    @Test
    public void thePinchConsumesTheSameDrawsWithAndWithoutApeFixes() {
        for (long seed = 0; seed < 50; seed++) {
            TestRunSpecs.install("ape.addons", "true");
            long withFixes = nextDrawAfterPinch(seed);
            TestRunSpecs.install();
            long withoutFixes = nextDrawAfterPinch(seed);
            assertEquals("next draw after the pinch, seed " + seed, withFixes, withoutFixes);
        }
    }

    private static long nextDrawAfterPinch(long seed) {
        RandomHelper.seed(seed);
        ApeFuzzer.generatePinchOrZoomEvent(new ArrayList<ApeEvent>(), WIDTH, HEIGHT);
        return RandomHelper.getRandom().nextLong();
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, ApeFuzzerPinchGateTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
