package com.android.commands.monkey.ape.model;

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
    public void tapIsNotEqualToMenuAction() {
        // ModelAction.equals guards getClass(), so an LlmTapAction is never equal to a
        // MODEL_MENU action even though both are targetless in the same state.
        LlmTapAction tap = new LlmTapAction(null, 600, 900, false);
        ModelAction menu = new ModelAction(null, ActionType.MODEL_MENU);
        assertNotEquals(tap, menu);
        assertNotEquals(menu, tap);
    }
}
