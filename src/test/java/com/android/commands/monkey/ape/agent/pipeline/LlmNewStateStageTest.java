package com.android.commands.monkey.ape.agent.pipeline;

import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmRouter;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.MopData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The new-state LLM hook: when it asks, what it does with an answer, and what it does with silence.
 *
 * <p>The order of the two guards is the part worth pinning. The shared precondition runs first and
 * the trigger predicate second, and the trigger is not side-effect-free — it consults the circuit
 * breaker, which advances an OPEN→HALF_OPEN probe and latches a log line once per open episode. A
 * step the precondition already declined must therefore not reach it, or a run would spend probes on
 * steps that were never the LLM's to decide.
 */
public class LlmNewStateStageTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /** A router whose two consulted members are scripted, and which counts what it was asked. */
    private static class StubRouter extends LlmRouter {

        private final boolean routes;
        private final ModelAction answer;
        int shouldRouteCalls;
        int selectCalls;
        Boolean isNewStateSeen;
        String modeSeen;
        GUITree treeSeen;
        int timestampSeen;

        StubRouter(boolean routes, ModelAction answer) {
            // JVM-safe, and nothing this stub reaches is wired by it (the oracle's scripted router
            // is built the same way).
            super(new Random(42L));
            this.routes = routes;
            this.answer = answer;
        }

        @Override
        public boolean shouldRouteNewState(boolean isNewState) {
            shouldRouteCalls++;
            isNewStateSeen = isNewState;
            return routes && isNewState;
        }

        @Override
        public ModelAction selectAction(GUITree tree, State state, List<ModelAction> actions,
                MopData mopData, List<ApePromptBuilder.ActionHistoryEntry> history, String mode,
                int timestamp) {
            selectCalls++;
            modeSeen = mode;
            treeSeen = tree;
            timestampSeen = timestamp;
            return answer;
        }
    }

    private static FakeStepContext routableStep() throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 3);
        ctx.isNewState = true;
        ctx.actionBufferSize = 0;
        ctx.timestamp = 7;
        return ctx;
    }

    private static LlmNewStateStage stageOver(StubRouter router, List<ModelAction> resolved) {
        return new LlmNewStateStage(router, resolved::add);
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        assertEquals(DecisionPipeline.Candidate.LLM_NEW_STATE.stageName(),
                stageOver(new StubRouter(false, null), new java.util.ArrayList<>()).name());
    }

    @Test
    public void testAnAcceptedAnswerDecidesTheStepAsLlm() throws Exception {
        ModelAction answer = new ModelAction(null, ActionType.MODEL_CLICK);
        StubRouter router = new StubRouter(true, answer);
        List<ModelAction> resolved = new java.util.ArrayList<>();
        FakeStepContext ctx = routableStep();

        StageResult result = stageOver(router, resolved).decide(ctx);

        assertEquals(StageResult.Kind.SELECT, result.kind());
        assertSame(answer, result.action());
        assertEquals("LLM", result.decisionSource());
        assertEquals("the label is the provenance the stage stamped (INV-DP-04)",
                answer.getDecisionSource().name(), result.decisionSource());
        assertEquals(ModelAction.PickChannel.LLM, answer.getPickChannel());
        assertEquals("a matched widget action is already resolved and must not be re-resolved",
                0, resolved.size());
        assertEquals("new-state", router.modeSeen);
        assertEquals(7, router.timestampSeen);
        assertEquals(Boolean.TRUE, router.isNewStateSeen);
    }

    @Test
    public void testASynthesizedTapIsResolvedBeforeItIsSelected() throws Exception {
        LlmTapAction tap = new LlmTapAction(null, 600, 900, false);
        StubRouter router = new StubRouter(true, tap);
        List<ModelAction> resolved = new java.util.ArrayList<>();

        StageResult result = stageOver(router, resolved).decide(routableStep());

        assertEquals(StageResult.Kind.SELECT, result.kind());
        // Off-tree, so it never passed the per-state resolution pass; dispatching it unresolved is
        // the NPE the guard exists to prevent (llm-coordinate-tap D7).
        assertEquals(1, resolved.size());
        assertSame(tap, resolved.get(0));
    }

    @Test
    public void testADecliningModelPassesRatherThanFailing() throws Exception {
        StubRouter router = new StubRouter(true, null);

        StageResult result = stageOver(router, new java.util.ArrayList<>()).decide(routableStep());

        // Every engine failure — no answer, no match, banned pair, transport — is this one path
        // (INV-DP-11): the remainder of the pipeline decides the step.
        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals(1, router.selectCalls);
    }

    @Test
    public void testAFalseTriggerDoesNotCallTheModel() throws Exception {
        StubRouter router = new StubRouter(false, new ModelAction(null, ActionType.MODEL_CLICK));

        StageResult result = stageOver(router, new java.util.ArrayList<>()).decide(routableStep());

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals(1, router.shouldRouteCalls);
        assertEquals(0, router.selectCalls);
    }

    @Test
    public void testANonEmptyBufferDeclinesBeforeConsultingTheTrigger() throws Exception {
        StubRouter router = new StubRouter(true, new ModelAction(null, ActionType.MODEL_CLICK));
        FakeStepContext ctx = routableStep();
        ctx.actionBufferSize = 1;

        StageResult result = stageOver(router, new java.util.ArrayList<>()).decide(ctx);

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("mid-sequence steps never reach the trigger: consulting it would spend a"
                + " circuit-breaker probe on a step the LLM was never offered",
                0, router.shouldRouteCalls);
    }

    @Test
    public void testATooSmallScreenDeclinesBeforeConsultingTheTrigger() throws Exception {
        StubRouter router = new StubRouter(true, new ModelAction(null, ActionType.MODEL_CLICK));
        FakeStepContext ctx = routableStep();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 2);

        StageResult result = stageOver(router, new java.util.ArrayList<>()).decide(ctx);

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("the threshold is strictly more than two actions", 0, router.shouldRouteCalls);
    }

    @Test
    public void testTheGateIsTheSameForEveryLlmStage() throws Exception {
        FakeStepContext ctx = routableStep();
        assertTrue(LlmGate.allows(ctx));

        ctx.actionBufferSize = 1;
        assertFalse(LlmGate.allows(ctx));

        ctx.actionBufferSize = 0;
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 3);
        assertTrue("three actions is above the threshold", LlmGate.allows(ctx));
    }
}
