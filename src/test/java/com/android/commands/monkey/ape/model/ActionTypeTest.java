package com.android.commands.monkey.ape.model;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ActionType} predicates around {@link ActionType#MODEL_LLM_TAP}
 * (llm-coordinate-tap, INV-MODEL-12). {@code MODEL_LLM_TAP} sits between {@code MODEL_MENU}
 * and {@code MODEL_CLICK}, so it must be a targetless model action; the widget-target
 * checks double as an ordinal-shift regression guard (the inserted value must not push any
 * widget type out of the {@code requireTarget()} range).
 */
public class ActionTypeTest {

    @Test
    public void llmTapIsTargetless() {
        assertFalse("MODEL_LLM_TAP carries a raw coordinate, not a widget",
                ActionType.MODEL_LLM_TAP.requireTarget());
    }

    @Test
    public void llmTapIsModelAction() {
        assertTrue("MODEL_LLM_TAP labels a StateTransition edge",
                ActionType.MODEL_LLM_TAP.isModelAction());
    }

    @Test
    public void llmTapIsNotScroll() {
        assertFalse(ActionType.MODEL_LLM_TAP.isScroll());
    }

    @Test
    public void llmTapSitsBetweenMenuAndClick() {
        assertTrue(ActionType.MODEL_MENU.ordinal() < ActionType.MODEL_LLM_TAP.ordinal());
        assertTrue(ActionType.MODEL_LLM_TAP.ordinal() < ActionType.MODEL_CLICK.ordinal());
    }

    @Test
    public void widgetTypesStillRequireTarget() {
        // Ordinal-shift regression guard: inserting MODEL_LLM_TAP must not push any of the six
        // widget-target types out of the requireTarget() range.
        assertTrue(ActionType.MODEL_CLICK.requireTarget());
        assertTrue(ActionType.MODEL_LONG_CLICK.requireTarget());
        assertTrue(ActionType.MODEL_SCROLL_TOP_DOWN.requireTarget());
        assertTrue(ActionType.MODEL_SCROLL_BOTTOM_UP.requireTarget());
        assertTrue(ActionType.MODEL_SCROLL_LEFT_RIGHT.requireTarget());
        assertTrue(ActionType.MODEL_SCROLL_RIGHT_LEFT.requireTarget());
    }

    @Test
    public void targetlessModelActionsRemainTargetless() {
        assertFalse(ActionType.MODEL_BACK.requireTarget());
        assertFalse(ActionType.MODEL_MENU.requireTarget());
        assertTrue(ActionType.MODEL_BACK.isModelAction());
        assertTrue(ActionType.MODEL_MENU.isModelAction());
    }

    @Test
    public void onlyLlmTapIsEphemeral() {
        // INV-MODEL-14: ephemeral names the actions synthesized per decision that are never members
        // of State.actions, so the graph never registers them. MODEL_LLM_TAP is the only one.
        assertTrue("the synthesized off-tree tap is never a State.actions member",
                new ModelAction(null, ActionType.MODEL_LLM_TAP).isEphemeral());

        // MODEL_BACK/MODEL_MENU are targetless too, but ARE members of State.actions and are
        // registered by Graph.addActions — being targetless does not make an action ephemeral.
        assertFalse(new ModelAction(null, ActionType.MODEL_BACK).isEphemeral());
        assertFalse(new ModelAction(null, ActionType.MODEL_MENU).isEphemeral());
        assertFalse(new ModelAction(null, ActionType.MODEL_CLICK).isEphemeral());
        assertFalse(new ModelAction(null, ActionType.MODEL_LONG_CLICK).isEphemeral());
    }
}
