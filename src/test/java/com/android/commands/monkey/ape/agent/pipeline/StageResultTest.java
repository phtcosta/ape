package com.android.commands.monkey.ape.agent.pipeline;

import org.junit.Test;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * The sum type's totality and its accessor contract (INV-DP-04).
 *
 * <p>What is worth testing here is not that a getter returns what a factory was handed — it is the
 * two properties the type exists to buy. First, that the three variants are distinguishable, because
 * the {@code null} this replaces was not: one value meant "no trivial activity", "the LLM declined"
 * and "this rung found nothing" at three rungs of the same ladder. Second, that reading the wrong
 * variant fails at the misuse instead of handing back a {@code null} for something downstream to
 * dereference — the reason the accessors throw.
 */
public class StageResultTest {

    private static ModelAction action() {
        return new ModelAction(null, ActionType.MODEL_CLICK);
    }

    @Test
    public void selectCarriesItsActionAndSource() {
        ModelAction picked = action();
        picked.setDecisionSource(ModelAction.DecisionSource.LLM);

        StageResult result = StageResult.select(picked, ModelAction.DecisionSource.LLM.name());

        assertEquals(StageResult.Kind.SELECT, result.kind());
        assertSame(picked, result.action());
        assertEquals("LLM", result.decisionSource());
        // INV-DP-04's equality clause: the label is the provenance stamped on the action, not a
        // second vocabulary. Per-stage assertions (task 7.2) pin it at every production site.
        assertEquals(picked.getDecisionSource().name(), result.decisionSource());
    }

    @Test
    public void continueIsTheOneSharedValue() {
        StageResult first = StageResult.continueChain();
        StageResult second = StageResult.continueChain();

        assertEquals(StageResult.Kind.CONTINUE, first.kind());
        // A singleton, so "I pass" allocates nothing on a path walked once per stage per step.
        assertSame(first, second);
    }

    @Test
    public void sideEffectCarriesWhatItDid() {
        StageResult result = StageResult.sideEffect("broadcast to com.example/.Receiver");

        assertEquals(StageResult.Kind.SIDE_EFFECT, result.kind());
        assertEquals("broadcast to com.example/.Receiver", result.description());
    }

    @Test
    public void actionOnContinueFailsLoudly() {
        try {
            StageResult.continueChain().action();
            fail("reading the action of a CONTINUE must throw, not return null");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void decisionSourceOnSideEffectFailsLoudly() {
        try {
            StageResult.sideEffect("dispatched").decisionSource();
            fail("a SIDE_EFFECT decides nothing, so it has no decision source");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void descriptionOnSelectFailsLoudly() {
        try {
            StageResult.select(action(), "SATA").description();
            fail("a SELECT is not a side effect and carries no description");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void selectRejectsAMissingAction() {
        try {
            StageResult.select(null, "SATA");
            fail("a SELECT without an action is not a decision");
        } catch (NullPointerException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void selectRejectsAMissingSource() {
        try {
            StageResult.select(action(), null);
            fail("a SELECT without an attribution cannot be recorded by step telemetry");
        } catch (NullPointerException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void sideEffectRejectsAMissingDescription() {
        try {
            StageResult.sideEffect(null);
            fail("an unrecorded side effect is indistinguishable from none");
        } catch (NullPointerException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void selectAcceptsANonModelAction() {
        // The launcher selects an EVENT_TRIGGER_ACTIVITY action, whose source is derived rather than
        // stamped on a ModelAction; the type must not assume every selection is a model action.
        Action nonModel = new Action(ActionType.EVENT_TRIGGER_ACTIVITY);

        StageResult result = StageResult.select(nonModel, "Component");

        assertSame(nonModel, result.action());
        assertEquals("Component", result.decisionSource());
    }
}
