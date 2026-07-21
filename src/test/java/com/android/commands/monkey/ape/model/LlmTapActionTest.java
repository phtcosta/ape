package com.android.commands.monkey.ape.model;

import android.graphics.Rect;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link LlmTapAction} (llm-coordinate-tap, D2/D3). The constructor passes the
 * state to {@code super} without dereferencing it, so a null state is sufficient for these
 * field-level tests — the established {@link ModelActionTest} convention. Two taps sharing the
 * same (null) state exercise the inherited identity ({@code state} + {@code target=null} + type)
 * and the {@code getClass()} isolation from {@code MODEL_MENU}.
 */
public class LlmTapActionTest {

    @Test
    public void constructsTargetless() {
        LlmTapAction tap = new LlmTapAction(null, 600, 900, false);
        assertNull("off-tree tap carries a coordinate, not a widget target", tap.getTarget());
        assertEquals(ActionType.MODEL_LLM_TAP, tap.getType());
        assertFalse(tap.requireTarget());
    }

    @Test
    public void accessorsReturnCoordinateAndFlag() {
        LlmTapAction tap = new LlmTapAction(null, 600, 900, false);
        assertEquals(600, tap.getPixelX());
        assertEquals(900, tap.getPixelY());
        assertFalse(tap.isLongClick());

        LlmTapAction longTap = new LlmTapAction(null, 123, 456, true);
        assertEquals(123, longTap.getPixelX());
        assertEquals(456, longTap.getPixelY());
        assertTrue(longTap.isLongClick());
    }

    @Test
    public void tapsAtDifferentCoordinatesAreNotEqual() {
        // INV-MODEL-13: the coordinate is part of identity. Were it not, both taps would be one
        // action key, and a differing destination between them would reach the CEGAR refinement as
        // non-determinism of a single action — a phantom no naming can resolve (D3).
        LlmTapAction a = new LlmTapAction(null, 600, 900, false);
        LlmTapAction b = new LlmTapAction(null, 100, 200, false);
        assertNotEquals("taps at different coordinates are different actions", a, b);
    }

    @Test
    public void tapsDifferingOnlyInLongClickAreNotEqual() {
        LlmTapAction click = new LlmTapAction(null, 600, 900, false);
        LlmTapAction longClick = new LlmTapAction(null, 600, 900, true);
        assertNotEquals("a long press is not the same action as a tap", click, longClick);
    }

    @Test
    public void tapsWithIdenticalPayloadAreEqual() {
        // Repeating the identical tap must reuse the same action key, so its edge strengthens
        // instead of accumulating duplicates.
        LlmTapAction a = new LlmTapAction(null, 600, 900, false);
        LlmTapAction b = new LlmTapAction(null, 600, 900, false);
        assertEquals("same state + same payload is the same action", a, b);
        assertEquals("equal actions must share hashCode", a.hashCode(), b.hashCode());
    }

    @Test
    public void toInjectableRectIsMinimalNonEmptyAtPixel() {
        // INV-EXPL-30: a zero-area Rect(x,y,x,y) is unconditionally dropped by the INV-EXPL-19
        // guard (empty rects fail both Rect.intersect-as-nonempty and Rect.contains), which made
        // every campaign tap a no-op (cmpm forensics A1). The injectable rect is the minimal
        // non-degenerate rect whose truncated center is the decided pixel.
        LlmTapAction tap = new LlmTapAction(null, 853, 1657, false);
        Rect r = tap.toInjectableRect();
        assertEquals(853, r.left);
        assertEquals(1657, r.top);
        assertEquals(854, r.right);
        assertEquals(1658, r.bottom);
        assertFalse("injectable rect must not be empty", r.isEmpty());
        assertEquals(853, (int) r.exactCenterX());
        assertEquals(1657, (int) r.exactCenterY());
    }

    @Test
    public void injectableRectSurvivesVisibilityGuardForInteriorPixel() {
        // Replicates generateClickEventAt's guard arithmetic (MonkeySourceApe): the visible-bounds
        // intersection must be non-empty and must contain the click point.
        LlmTapAction tap = new LlmTapAction(null, 540, 1158, false);
        Rect visible = new Rect(0, 0, 1080, 1920);
        assertTrue("interior pixel must intersect the visible bounds", visible.intersect(tap.toInjectableRect()));
        assertFalse("intersection must be non-empty", visible.isEmpty());
        assertTrue("click point must lie inside the intersection", visible.contains(540, 1158));
    }

    @Test
    public void injectableRectAtExclusiveRightEdgeFailsGuard() {
        // x == visible.right is outside the (exclusive) right edge: the off-screen drop must be
        // preserved for genuinely out-of-bounds coordinates.
        LlmTapAction tap = new LlmTapAction(null, 1080, 500, false);
        Rect visible = new Rect(0, 0, 1080, 1920);
        assertFalse("edge pixel must keep failing the visibility guard", visible.intersect(tap.toInjectableRect()));
    }

    @Test
    public void clipToDisplayAcceptsInDisplayPixelOutsideAppWindow() {
        // INV-EXPL-30 (amended): the validity domain of a coordinate tap is the physical display
        // bounds, not the app root-node bounds. (424,1618) is the exact IME-band coordinate the
        // llm-tap-injection gate measured as dropped (6/7 thumbkey taps) — below the app window but
        // inside the 1080×1920 display, it must now clip to a non-empty rect containing the pixel.
        LlmTapAction tap = new LlmTapAction(null, 424, 1618, false);
        Rect clipped = tap.clipToDisplay(new Rect(0, 0, 1080, 1920));
        assertNotNull("in-display IME-band pixel must not be dropped", clipped);
        assertEquals(424, clipped.left);
        assertEquals(1618, clipped.top);
        assertEquals(425, clipped.right);
        assertEquals(1619, clipped.bottom);
        assertFalse("clipped rect must not be empty", clipped.isEmpty());
        assertTrue("clipped rect must contain the decided pixel", clipped.contains(424, 1618));
    }

    @Test
    public void clipToDisplayAcceptsInteriorPixel() {
        LlmTapAction tap = new LlmTapAction(null, 540, 957, false);
        Rect clipped = tap.clipToDisplay(new Rect(0, 0, 1080, 1920));
        assertNotNull("interior pixel must not be dropped", clipped);
        assertTrue("clipped rect must contain the decided pixel", clipped.contains(540, 957));
    }

    @Test
    public void clipToDisplayRejectsOutOfDisplayPixel() {
        // x == display.right is outside the (exclusive) right edge: a genuinely out-of-display
        // coordinate must still be dropped (null).
        LlmTapAction tap = new LlmTapAction(null, 1080, 500, false);
        assertNull("out-of-display pixel must be dropped", tap.clipToDisplay(new Rect(0, 0, 1080, 1920)));
    }

    @Test
    public void clipToDisplayToleratesNullDisplay() {
        // getDisplayBounds() can fail before the display is ready: a null display must drop the tap,
        // never NPE.
        LlmTapAction tap = new LlmTapAction(null, 540, 957, false);
        assertNull("null display bounds must drop the tap without NPE", tap.clipToDisplay(null));
    }

    @Test
    public void clipToDisplayDoesNotMutateDisplayBounds() {
        // Android Rect.intersect mutates its receiver: clipToDisplay must defensively copy the
        // display rect so the caller's bounds are not shrunk to the 1×1 tap rect.
        LlmTapAction tap = new LlmTapAction(null, 540, 957, false);
        Rect display = new Rect(0, 0, 1080, 1920);
        tap.clipToDisplay(display);
        assertEquals(0, display.left);
        assertEquals(0, display.top);
        assertEquals(1080, display.right);
        assertEquals(1920, display.bottom);
    }

    @Test
    public void tapIsNotEqualToMenuAction() {
        // ModelAction.equals guards getClass(), so an LlmTapAction is never equal to a
        // MODEL_MENU action even though both are targetless in the same state.
        LlmTapAction tap = new LlmTapAction(null, 600, 900, false);
        ModelAction menu = new ModelAction(null, ActionType.MODEL_MENU);
        assertNotEquals(tap, menu);
        assertNotEquals(menu, tap);
    }
}
