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
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.MopData;

import static org.junit.Assert.assertEquals;

/**
 * The rate-driven hook, and the draw order that makes a seeded run reproducible.
 *
 * <p>The assertions that matter here are about <em>when</em> the coin is consulted rather than what it
 * says. The coin lives inside the router's trigger, and a step the shared precondition declines must
 * not reach it: a draw taken on such a step would advance the stream by one and shift every later
 * decision of the run, which is the failure mode INV-DP-10 exists to name and the goldens exist to
 * catch.
 */
public class LlmRandomStageTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /** A router that counts trigger consultations, which is what the draw order is read off. */
    private static class StubRouter extends LlmRouter {

        private final boolean triggers;
        private final ModelAction answer;
        int triggerCalls;
        int selectCalls;
        String modeSeen;

        StubRouter(boolean triggers, ModelAction answer) {
            super(new Random(42L));
            this.triggers = triggers;
            this.answer = answer;
        }

        @Override
        public boolean shouldRouteRandom() {
            triggerCalls++;
            return triggers;
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

    private static FakeStepContext routableStep() throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 3);
        ctx.actionBufferSize = 0;
        return ctx;
    }

    private static LlmRandomStage stageOver(StubRouter router) {
        return new LlmRandomStage(router, new ArrayList<ModelAction>()::add);
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        assertEquals(DecisionPipeline.Candidate.LLM_RANDOM.stageName(),
                stageOver(new StubRouter(false, null)).name());
    }

    @Test
    public void testAWonCoinDecidesTheStepAsLlm() throws Exception {
        ModelAction answer = new ModelAction(null, ActionType.MODEL_CLICK);
        StubRouter router = new StubRouter(true, answer);

        StageResult result = stageOver(router).decide(routableStep());

        assertEquals(StageResult.Kind.SELECT, result.kind());
        assertEquals("LLM", result.decisionSource());
        assertEquals(answer.getDecisionSource().name(), result.decisionSource());
        assertEquals("random", router.modeSeen);
    }

    @Test
    public void testALostCoinPassesWithoutCallingTheModel() throws Exception {
        StubRouter router = new StubRouter(false, new ModelAction(null, ActionType.MODEL_CLICK));

        StageResult result = stageOver(router).decide(routableStep());

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals(1, router.triggerCalls);
        assertEquals(0, router.selectCalls);
    }

    @Test
    public void testADecliningModelPassesRatherThanFailing() throws Exception {
        StubRouter router = new StubRouter(true, null);

        assertEquals(StageResult.Kind.CONTINUE, stageOver(router).decide(routableStep()).kind());
        assertEquals(1, router.selectCalls);
    }

    @Test
    public void testAGatedStepNeverDrawsTheCoin() throws Exception {
        StubRouter router = new StubRouter(true, new ModelAction(null, ActionType.MODEL_CLICK));
        FakeStepContext ctx = routableStep();
        ctx.actionBufferSize = 1;

        StageResult result = stageOver(router).decide(ctx);

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("a draw on a gated step advances the seeded stream and moves every later"
                + " decision of the run (INV-DP-10)", 0, router.triggerCalls);
    }

    @Test
    public void testATooSmallScreenNeverDrawsTheCoin() throws Exception {
        StubRouter router = new StubRouter(true, new ModelAction(null, ActionType.MODEL_CLICK));
        FakeStepContext ctx = routableStep();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 2);

        assertEquals(StageResult.Kind.CONTINUE, stageOver(router).decide(ctx).kind());
        assertEquals(0, router.triggerCalls);
    }
}
