package com.android.commands.monkey.ape.agent.pipeline;

import org.junit.Test;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.State;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shared LLM precondition and the opaque predicate it reads (INV-RTR-21/22/23).
 *
 * <p>The gate is a function of three facts about the step — the buffer, the size of the action
 * list, whether the state is opaque — and one fact about the plan, whether opaque routing is on. The
 * truth table below walks all of them. The case the table exists for is the one that did not change:
 * with the flag off, every row equals the size rule the gate had before opaque routing, which is what
 * keeps a plan at {@code ape.llmPercentageNoSubstrate=-1} on its old decisions and draws.
 */
public class LlmGateTest {

    private static final String ACTIVITY = "com.example.GameActivity";

    private static final ActionType CLICK = ActionType.MODEL_CLICK;
    private static final ActionType BACK = ActionType.MODEL_BACK;
    private static final ActionType MENU = ActionType.MODEL_MENU;

    private static FakeStepContext step(int bufferSize, ActionType... types) throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateOf(ACTIVITY, types);
        ctx.actionBufferSize = bufferSize;
        return ctx;
    }

    // -------------------------------------------------------------------------
    // INV-RTR-21 — the predicate
    // -------------------------------------------------------------------------

    @Test
    public void opaqueWhenNoTargetedAction() throws Exception {
        // A game canvas: the model's state offers leaving and the menu, nothing else.
        assertTrue(LlmGate.isOpaque(FakeStepContext.stateOf(ACTIVITY, BACK, MENU)));
    }

    @Test
    public void opaqueWithMenuDisabled() throws Exception {
        // ape.modelMenuEnabled=false leaves the canvas with MODEL_BACK alone; still nothing to target.
        assertTrue(LlmGate.isOpaque(FakeStepContext.stateOf(ACTIVITY, BACK)));
    }

    @Test
    public void opaqueWithANonActionableWidget() throws Exception {
        // A tree with one widget that yields no model action abstracts to the same action list as an
        // empty tree: the predicate reads the actions, so the widget does not make the screen legible.
        State state = FakeStepContext.stateOf(ACTIVITY, BACK, MENU);
        assertTrue(LlmGate.isOpaque(state));
    }

    @Test
    public void notOpaqueWithOneWidgetAction() throws Exception {
        // A one-button dialog with ape.modelMenuEnabled=false has two actions, as a canvas does, but
        // one of them has a target — which is why the predicate is not "two actions or fewer".
        assertFalse(LlmGate.isOpaque(FakeStepContext.stateOf(ACTIVITY, CLICK, BACK)));
    }

    @Test
    public void emptyActionsNotOpaque() throws Exception {
        assertFalse(LlmGate.isOpaque(FakeStepContext.stateOf(ACTIVITY)));
    }

    // -------------------------------------------------------------------------
    // INV-RTR-22 — flag off: the size rule, on every row
    // -------------------------------------------------------------------------

    @Test
    public void featureOffIsSizeRule() throws Exception {
        FakeStepContext[] rows = {
            step(0, CLICK, CLICK, CLICK, BACK, MENU),
            step(0, CLICK, BACK, MENU),
            step(0, CLICK, BACK),
            step(0, BACK, MENU),
            step(0, BACK),
            step(0),
            step(1, CLICK, CLICK, CLICK, BACK, MENU),
            step(1, BACK, MENU),
        };
        for (FakeStepContext row : rows) {
            boolean sizeRule = row.actionBufferSize == 0 && row.newState.getActions().size() > 2;
            assertEquals("buffer=" + row.actionBufferSize
                            + " actions=" + row.newState.getActions().size(),
                    sizeRule, LlmGate.allows(row, false));
        }
    }

    @Test
    public void featureOffClosesOpaque() throws Exception {
        assertFalse(LlmGate.allows(step(0, BACK, MENU), false));
    }

    // -------------------------------------------------------------------------
    // INV-RTR-23 — flag on: the opaque clause, the buffer kept
    // -------------------------------------------------------------------------

    @Test
    public void featureOnOpensOpaque() throws Exception {
        assertTrue(LlmGate.allows(step(0, BACK, MENU), true));
        assertTrue(LlmGate.allows(step(0, BACK), true));
    }

    @Test
    public void featureOnKeepsTheSizeRuleForWidgetScreens() throws Exception {
        assertTrue(LlmGate.allows(step(0, CLICK, CLICK, CLICK, BACK, MENU), true));
        assertTrue(LlmGate.allows(step(0, CLICK, BACK, MENU), true));
    }

    @Test
    public void featureOnLeavesTrivialWidgetScreensClosed() throws Exception {
        assertFalse("a one-button dialog is not opaque and stays closed by size",
                LlmGate.allows(step(0, CLICK, BACK), true));
    }

    @Test
    public void featureOnLeavesEmptyActionsClosed() throws Exception {
        assertFalse(LlmGate.allows(step(0), true));
    }

    @Test
    public void bufferClosesOpaque() throws Exception {
        assertFalse("buffered navigation closes the gate on an opaque step as on any other",
                LlmGate.allows(step(2, BACK, MENU), true));
        assertFalse(LlmGate.allows(step(1, CLICK, CLICK, CLICK, BACK, MENU), true));
    }
}
