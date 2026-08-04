package com.android.commands.monkey.ape.agent.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmRouter;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.model.StateTransitionVisitType;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.MopData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The stagnation episode: one shot, spent by asking, re-armed only by movement.
 *
 * <p>Three rules interlock here and each is a place the relocation could have gone quietly wrong. The
 * shot burns before the engine is called, so a declining model still spends it. An accepted escape
 * resets the stability counter but does <em>not</em> re-arm the shot, because no new edge has been
 * observed and it is therefore still the same episode — the difference between "we moved" and "we
 * asked for a way to move". And a new edge re-arms without any stage deciding anything, through the
 * transition hook, which is the only channel that reaches this state.
 */
public class LlmStagnationStageTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /** A router whose stagnation trigger is scripted and whose answer is fixed. */
    private static class StubRouter extends LlmRouter {

        private final boolean triggers;
        private final ModelAction answer;
        int selectCalls;
        Integer counterSeen;
        Boolean firedSeen;
        String modeSeen;

        StubRouter(boolean triggers, ModelAction answer) {
            super(new Random(42L));
            this.triggers = triggers;
            this.answer = answer;
        }

        @Override
        public boolean shouldRouteStagnation(int graphStableCounter, boolean firedThisEpisode) {
            counterSeen = graphStableCounter;
            firedSeen = firedThisEpisode;
            // The real predicate's own shape: at or past the midpoint, with the shot still armed.
            return triggers && !firedThisEpisode;
        }

        @Override
        public ModelAction selectAction(GUITree tree, State state, List<ModelAction> actions,
                MopData mopData, List<ApePromptBuilder.ActionHistoryEntry> history, String mode,
                int timestamp) {
            selectCalls++;
            modeSeen = mode;
            return answer;
        }
    }

    private static FakeStepContext stagnantStep() throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 3);
        ctx.actionBufferSize = 0;
        ctx.graphStableCounter = 25;
        return ctx;
    }

    private static LlmStagnationStage stageOver(StubRouter router) {
        return new LlmStagnationStage(router, new ArrayList<ModelAction>()::add);
    }

    private static StateTransition edgeOfType(StateTransitionVisitType type) throws Exception {
        StateTransition edge = (StateTransition) allocate(StateTransition.class);
        FakeStepContext.setField(edge, "type", type);
        return edge;
    }

    private static Object allocate(Class<?> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), clazz);
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        assertEquals(DecisionPipeline.Candidate.LLM_STAGNATION.stageName(),
                stageOver(new StubRouter(false, null)).name());
    }

    @Test
    public void testTheTriggerSeesTheCounterAndTheArmedFlag() throws Exception {
        StubRouter router = new StubRouter(false, null);
        FakeStepContext ctx = stagnantStep();

        stageOver(router).decide(ctx);

        assertEquals(Integer.valueOf(25), router.counterSeen);
        assertEquals("a fresh episode starts armed", Boolean.FALSE, router.firedSeen);
    }

    @Test
    public void testADeclinedShotIsStillSpent() throws Exception {
        StubRouter router = new StubRouter(true, null);
        LlmStagnationStage stage = stageOver(router);
        FakeStepContext ctx = stagnantStep();

        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals("stagnation", router.modeSeen);
        assertEquals("a decline leaves the counter alone: the restart threshold still applies",
                0, ctx.graphStableCounterResets);

        stage.decide(ctx);

        assertEquals("the episode's second step must not ask again", Boolean.TRUE, router.firedSeen);
        assertEquals("and must not call the engine again", 1, router.selectCalls);
    }

    @Test
    public void testAnAcceptedEscapeResetsTheCounterAndAlsoSpendsTheShot() throws Exception {
        ModelAction answer = new ModelAction(null, ActionType.MODEL_CLICK);
        StubRouter router = new StubRouter(true, answer);
        LlmStagnationStage stage = stageOver(router);
        FakeStepContext ctx = stagnantStep();

        StageResult result = stage.decide(ctx);

        assertEquals(StageResult.Kind.SELECT, result.kind());
        assertEquals("LLM", result.decisionSource());
        assertEquals(answer.getDecisionSource().name(), result.decisionSource());
        assertEquals("the escape resets the stability counter, once", 1, ctx.graphStableCounterResets);

        stage.decide(ctx);
        assertEquals("an escape is not a new edge, so the same episode has no second shot",
                1, router.selectCalls);
    }

    @Test
    public void testANewEdgeReArmsTheShot() throws Exception {
        StubRouter router = new StubRouter(true, null);
        LlmStagnationStage stage = stageOver(router);
        FakeStepContext ctx = stagnantStep();

        stage.decide(ctx);
        assertEquals(1, router.selectCalls);

        stage.onStateTransition(edgeOfType(StateTransitionVisitType.NEW_ACTION));
        stage.decide(ctx);

        assertEquals("exploration moved, so the next standstill is a new episode",
                2, router.selectCalls);

        stage.onStateTransition(edgeOfType(StateTransitionVisitType.NEW_ACTION_TARGET));
        stage.decide(ctx);

        assertEquals("both new-edge forms end an episode", 3, router.selectCalls);
    }

    @Test
    public void testAnExistingEdgeDoesNotReArmTheShot() throws Exception {
        StubRouter router = new StubRouter(true, null);
        LlmStagnationStage stage = stageOver(router);
        FakeStepContext ctx = stagnantStep();

        stage.decide(ctx);
        stage.onStateTransition(edgeOfType(StateTransitionVisitType.EXISTING));
        stage.decide(ctx);

        // An edge the graph already had is what stagnation *is*; re-arming on it would give a stuck
        // episode a call per step.
        assertEquals(1, router.selectCalls);
    }

    @Test
    public void testTheSharedPreconditionGatesTheTriggerHereToo() throws Exception {
        StubRouter router = new StubRouter(true, null);
        FakeStepContext ctx = stagnantStep();
        ctx.actionBufferSize = 1;

        StageResult result = stageOver(router).decide(ctx);

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertFalse("the trigger consults the circuit breaker, so a gated step must not reach it",
                router.firedSeen != null);
        assertTrue(router.selectCalls == 0);
    }
}
