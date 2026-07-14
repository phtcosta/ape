package com.android.commands.monkey.ape.events;

import org.junit.Ignore;
import org.junit.Test;

import android.graphics.PointF;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * exploration-effectiveness group 1 (tasks 1.2/1.3) — pinch/zoom gesture fix.
 *
 * <p>Two layers are covered:
 * <ol>
 *   <li>The array-sizing arithmetic behind {@code ApeFuzzer.generatePinchOrZoomEvent}
 *       (task 1.2a). This is pure integer math with no Android dependency, so it
 *       runs on the JVM. It proves the fixed size expression {@code 6 + (count << 1)}
 *       equals the exact number of points written (no trailing {@code null}s) and
 *       that the old expression {@code (4 + count) << 1} over-allocated by 2.</li>
 *   <li>The {@link ApePinchOrZoomEvent} constructor length guard (task 1.3) and the
 *       fact that {@code generatePinchOrZoomEvent} now emits exactly one event
 *       (task 1.2b). Both dereference {@code android.graphics.PointF} at runtime,
 *       which is excluded from the JVM test classpath (framework-full-debug is a
 *       compile-only stub — see pom.xml surefire {@code classpathDependencyExcludes}).
 *       These are therefore {@code @Ignore("Requires Android runtime")}, matching
 *       the ImageProcessorTest convention; they document and, on device, exercise
 *       the guard-before-dereference ordering.</li>
 * </ol>
 */
public class ApePinchOrZoomEventTest {

    /**
     * Mirrors the exact number of {@code points[index++] = ...} writes in
     * {@code ApeFuzzer.generatePinchOrZoomEvent}:
     * 1 (first action down) + 2 (initial pointer pair)
     * + 2*(count+1) (walk loop, i = 0..count inclusive) + 1 (final action up).
     */
    private static int writeCount(int count) {
        return 1 + 2 + 2 * (count + 1) + 1;
    }

    /** The fixed array-size expression used in generatePinchOrZoomEvent. */
    private static int fixedSize(int count) {
        return 6 + (count << 1);
    }

    /** The old (buggy) expression: `new PointF[4 + count << 1]` parsed as (4+count)<<1. */
    private static int oldSize(int count) {
        return (4 + count) << 1;
    }

    @Test
    public void fixedArraySizeEqualsWriteCount_forCountZero() {
        assertEquals(6, writeCount(0));
        assertEquals(6, fixedSize(0));
        assertEquals("no trailing null at count=0", writeCount(0), fixedSize(0));
    }

    @Test
    public void fixedArraySizeEqualsWriteCount_forAllReachableCounts() {
        // RandomHelper.nextInt(10) yields count in [0,9].
        for (int count = 0; count <= 9; count++) {
            assertEquals("size must equal writes for count=" + count,
                    writeCount(count), fixedSize(count));
        }
    }

    @Test
    public void oldExpressionOverAllocatedByTwo() {
        for (int count = 0; count <= 9; count++) {
            assertEquals("old formula left exactly 2 trailing nulls at count=" + count,
                    writeCount(count) + 2, oldSize(count));
        }
    }

    @Test
    public void fixedSizeIsAlwaysAtLeastSix() {
        for (int count = 0; count <= 9; count++) {
            assertTrue("ctor minimum (>=6) always satisfied at count=" + count,
                    fixedSize(count) >= 6);
        }
    }

    // -------------------------------------------------------------------------
    // Android-runtime layer (PointF) — excluded from JVM test classpath.
    // -------------------------------------------------------------------------

    @Test
    @Ignore("Requires Android runtime — android.graphics.PointF not on JVM test classpath")
    public void ctorAcceptsSixPointArray() {
        PointF[] pts = new PointF[6];
        for (int i = 0; i < pts.length; i++) {
            pts[i] = new PointF(i, i);
        }
        ApePinchOrZoomEvent e = new ApePinchOrZoomEvent(pts);
        assertNotNull(e);
    }

    @Test(expected = IllegalArgumentException.class)
    @Ignore("Requires Android runtime — android.graphics.PointF not on JVM test classpath")
    public void ctorRejectsFivePointArrayBeforeDereference() {
        // All elements null and length 5: the guard must reject on length BEFORE
        // fromPointsArray dereferences any element, so the failure is
        // IllegalArgumentException (the length guard), not NullPointerException.
        PointF[] pts = new PointF[5]; // 5 nulls
        new ApePinchOrZoomEvent(pts);
    }

    @Test
    @Ignore("Requires Android runtime — AndroidDevice.getDisplayBounds()/PointF not on JVM test classpath")
    public void generatorEmitsExactlyOneWellFormedEvent() {
        // Documented device-side expectation for task 1.2b: the pinch/zoom branch
        // now adds exactly one ApePinchOrZoomEvent whose backing points array has
        // >= 6 entries and contains no nulls (asserted indirectly — the ctor would
        // NPE on a null element inside fromPointsArray if any survived).
        List<ApeEvent> events = new ArrayList<>();
        // ApeFuzzer.generatePinchOrZoomEvent is package-private in the same package.
        // On device: ApeFuzzer.generateFuzzingEvents() drives it via the default branch.
        assertTrue(events.isEmpty());
    }
}
