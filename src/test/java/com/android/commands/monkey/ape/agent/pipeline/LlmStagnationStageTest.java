package com.android.commands.monkey.ape.agent.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmEngine;
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
 *
 * <p><b>The trigger predicate is asserted here, on the stage that owns it.</b> It is a pure static
 * over (counter, threshold, flag), so the last group below drives it with no stage, no agent and no
 * plan — the cheapest form the invariant it pins (INV-RTR-19) has ever had.
 */
public class LlmStagnationStageTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /**
     * The threshold the stage is built with. Its midpoint, 25, is what {@link #stagnantStep} counts
     * to — stated as a plan value rather than a jar default, because the stage reads it from the
     * plan.
     */
    private static final int RESTART_THRESHOLD = 50;

    /** The engine reduced to one answer, recording the call the stage made. */
    private static class StubEngine extends LlmEngine {

        private final ModelAction answer;
        int selectCalls;
        String modeSeen;

        StubEngine(ModelAction answer) {
            // Every step of the real pipeline is replaced below, so none of the units it composes
            // is reachable and none needs to exist.
            super(null, null, null, null, null, null);
            this.answer = answer;
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

    /** The breaker consultation, scripted and counted — the stage's last conjunct. */
    private static class Gate implements BooleanSupplier {

        private final boolean allows;
        int calls;

        Gate(boolean allows) {
            this.allows = allows;
        }

        @Override
        public boolean getAsBoolean() {
            calls++;
            return allows;
        }
    }

    private static FakeStepContext stagnantStep() throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 3);
        ctx.actionBufferSize = 0;
        ctx.graphStableCounter = 25;
        return ctx;
    }

    private static LlmStagnationStage stageOver(StubEngine engine, Gate gate) {
        return new LlmStagnationStage(engine, gate, RESTART_THRESHOLD,
                new ArrayList<ModelAction>()::add);
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
                stageOver(new StubEngine(null), new Gate(false)).name());
    }

    @Test
    public void testAStepBelowTheMidpointNeverReachesTheBreaker() throws Exception {
        StubEngine engine = new StubEngine(null);
        Gate gate = new Gate(true);
        FakeStepContext ctx = stagnantStep();
        ctx.graphStableCounter = 24;

        StageResult result = stageOver(engine, gate).decide(ctx);

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("one short of the midpoint is not a standstill this hook may spend a probe on",
                0, gate.calls);
        assertEquals(0, engine.selectCalls);
    }

    @Test
    public void testADeclinedShotIsStillSpent() throws Exception {
        StubEngine engine = new StubEngine(null);
        Gate gate = new Gate(true);
        LlmStagnationStage stage = stageOver(engine, gate);
        FakeStepContext ctx = stagnantStep();

        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals("stagnation", engine.modeSeen);
        assertEquals("a decline leaves the counter alone: the restart threshold still applies",
                0, ctx.graphStableCounterResets);

        stage.decide(ctx);

        assertEquals("the episode's second step must not reach the breaker again", 1, gate.calls);
        assertEquals("and must not call the engine again", 1, engine.selectCalls);
    }

    @Test
    public void testAnOpenBreakerDeclinesWithoutSpendingTheShot() throws Exception {
        // "Spent by asking" means spent by asking the model, and the breaker sits between the
        // trigger and the flag: it is the last conjunct, and the flag burns only once every conjunct
        // has held. So a breaker-open step costs the episode nothing, and this is the one place that
        // distinction is observable — the trigger and the flag are otherwise inseparable.
        StubEngine engine = new StubEngine(new ModelAction(null, ActionType.MODEL_CLICK));
        Gate gate = new Gate(false);
        LlmStagnationStage stage = stageOver(engine, gate);
        FakeStepContext ctx = stagnantStep();

        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals(1, gate.calls);
        assertEquals("the breaker declined, so nothing was asked of the model", 0,
                engine.selectCalls);

        assertEquals("and the next step of the same episode still reaches the breaker",
                StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals(2, gate.calls);
    }

    @Test
    public void testAnAcceptedEscapeResetsTheCounterAndAlsoSpendsTheShot() throws Exception {
        ModelAction answer = new ModelAction(null, ActionType.MODEL_CLICK);
        StubEngine engine = new StubEngine(answer);
        LlmStagnationStage stage = stageOver(engine, new Gate(true));
        FakeStepContext ctx = stagnantStep();

        StageResult result = stage.decide(ctx);

        assertEquals(StageResult.Kind.SELECT, result.kind());
        assertEquals("LLM", result.decisionSource());
        assertEquals(answer.getDecisionSource().name(), result.decisionSource());
        assertEquals("the escape resets the stability counter, once", 1, ctx.graphStableCounterResets);

        stage.decide(ctx);
        assertEquals("an escape is not a new edge, so the same episode has no second shot",
                1, engine.selectCalls);
    }

    @Test
    public void testANewEdgeReArmsTheShot() throws Exception {
        StubEngine engine = new StubEngine(null);
        LlmStagnationStage stage = stageOver(engine, new Gate(true));
        FakeStepContext ctx = stagnantStep();

        stage.decide(ctx);
        assertEquals(1, engine.selectCalls);

        stage.onStateTransition(edgeOfType(StateTransitionVisitType.NEW_ACTION));
        stage.decide(ctx);

        assertEquals("exploration moved, so the next standstill is a new episode",
                2, engine.selectCalls);

        stage.onStateTransition(edgeOfType(StateTransitionVisitType.NEW_ACTION_TARGET));
        stage.decide(ctx);

        assertEquals("both new-edge forms end an episode", 3, engine.selectCalls);
    }

    @Test
    public void testAnExistingEdgeDoesNotReArmTheShot() throws Exception {
        StubEngine engine = new StubEngine(null);
        LlmStagnationStage stage = stageOver(engine, new Gate(true));
        FakeStepContext ctx = stagnantStep();

        stage.decide(ctx);
        stage.onStateTransition(edgeOfType(StateTransitionVisitType.EXISTING));
        stage.decide(ctx);

        // An edge the graph already had is what stagnation *is*; re-arming on it would give a stuck
        // episode a call per step.
        assertEquals(1, engine.selectCalls);
    }

    @Test
    public void testTheSharedPreconditionGatesTheTriggerHereToo() throws Exception {
        StubEngine engine = new StubEngine(null);
        Gate gate = new Gate(true);
        FakeStepContext ctx = stagnantStep();
        ctx.actionBufferSize = 1;

        StageResult result = stageOver(engine, gate).decide(ctx);

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("the breaker's consultation has a side effect, so a gated step must not reach"
                + " it", 0, gate.calls);
        assertEquals(0, engine.selectCalls);
    }

    // -------------------------------------------------------------------------
    // The trigger predicate itself (INV-RTR-19), pure and driven directly
    // -------------------------------------------------------------------------

    @Test
    public void stagnationTriggerFiresAtTheMidpoint() {
        assertTrue(LlmStagnationStage.stagnationMidpointReached(100, 200, false));
    }

    @Test
    public void stagnationTriggerFiresWhenTheMidpointIsJumpedOver() {
        // The retired condition was exact equality, so a counter that went from 99 to 101 without
        // ever being checked at 100 lost the episode's only chance. This is the defect >= closes.
        assertTrue(LlmStagnationStage.stagnationMidpointReached(101, 200, false));
        assertTrue(LlmStagnationStage.stagnationMidpointReached(150, 200, false));
    }

    @Test
    public void stagnationTriggerStaysSilentBelowTheMidpoint() {
        assertFalse(LlmStagnationStage.stagnationMidpointReached(99, 200, false));
        assertFalse(LlmStagnationStage.stagnationMidpointReached(0, 200, false));
    }

    @Test
    public void stagnationTriggerStaysSilentOnANegativeCounter() {
        // A negative graphStableCounter is below any midpoint.
        assertFalse(LlmStagnationStage.stagnationMidpointReached(-1, 200, false));
    }

    @Test
    public void stagnationTriggerFiresOnlyOncePerEpisode() {
        // Once the episode's shot is spent, no later value of the counter fires it again.
        assertFalse(LlmStagnationStage.stagnationMidpointReached(100, 200, true));
        assertFalse(LlmStagnationStage.stagnationMidpointReached(199, 200, true));
    }

    @Test
    public void stagnationTriggerReArmsAfterANewEdge() {
        // The stage clears the flag on the edges that reset graphStableCounter to 0; the next
        // episode's climb back to the midpoint fires again.
        assertFalse(LlmStagnationStage.stagnationMidpointReached(100, 200, true));
        assertTrue("a re-armed episode fires again",
                LlmStagnationStage.stagnationMidpointReached(100, 200, false));
    }
}
