package com.android.commands.monkey.ape.agent.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.android.commands.monkey.ape.agent.StepRng;
import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.telemetry.RunCounter;
import com.android.commands.monkey.ape.tree.GUITree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The LLM coin (task 7.9.2): {@code u_L < p_L}, with {@code u_L} drawn at step open from its own
 * stream, every step — even when a gate bars or a guard takes the step (INV-DP-13, INV-RTR-24).
 */
public class LlmCoinStageTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /** The engine reduced to one answer, counting calls and recording the mode. */
    static class StubEngine extends LlmEngine {

        private final ModelAction answer;
        int selectCalls;
        String modeSeen;

        StubEngine(ModelAction answer) {
            super(null, null, null, null, null, null);
            this.answer = answer;
        }

        @Override
        public ModelAction selectAction(GUITree tree, State state, List<ModelAction> actions,
                List<ApePromptBuilder.ActionHistoryEntry> history, String mode) {
            selectCalls++;
            modeSeen = mode;
            return answer;
        }
    }

    private static FakeStepContext step(int actions) throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, actions);
        return ctx;
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        LlmKit kit = new LlmKit(new StubEngine(null), () -> true);
        assertEquals("LlmCoin", new LlmCoinStage(kit.call, 0.5).name());
        assertEquals(DecisionPipeline.Candidate.LLM_COIN.stageName(),
                new LlmCoinStage(kit.call, 0.5).name());
    }

    @Test
    public void testAWonCoinCallsInRandomModeAndCountsTheCoin() throws Exception {
        ModelAction answer = new ModelAction(null, ActionType.MODEL_CLICK);
        StubEngine engine = new StubEngine(answer);
        LlmKit kit = new LlmKit(engine, () -> true).step(0.1);
        FakeStepContext ctx = step(3);

        StageResult result = new LlmCoinStage(kit.call, 0.2).decide(ctx);

        assertEquals(StageResult.Kind.SELECT, result.kind());
        assertSame(answer, result.action());
        assertEquals("llm", result.decisionSource());
        assertEquals("the telemetry mode label of the coin stays random (D-B14.14)", "random",
                engine.modeSeen);
        assertEquals(1, ctx.recorder.count(RunCounter.COIN_LLM));
    }

    @Test
    public void testALostCoinClaimsNothingAndCallsNothing() throws Exception {
        StubEngine engine = new StubEngine(new ModelAction(null, ActionType.MODEL_CLICK));
        int[] breakerCalls = {0};
        LlmKit kit = new LlmKit(engine, () -> {
            breakerCalls[0]++;
            return true;
        }).step(0.2);
        FakeStepContext ctx = step(3);

        StageResult result = new LlmCoinStage(kit.call, 0.2).decide(ctx);

        assertEquals("the comparison is strict: 0.2 is not below 0.2", StageResult.Kind.CONTINUE,
                result.kind());
        assertEquals(0, engine.selectCalls);
        assertEquals("a lost coin reaches no gate", 0, breakerCalls[0]);
        assertEquals(0, ctx.recorder.count(RunCounter.COIN_LLM));
        assertTrue("a lost coin claims nothing (D-B14.6): the first claim is still open",
                kit.routing.claim("sata"));
    }

    @Test
    public void testAWonCoinBarredByAGateStillCountsTheCoinAndRecordsTheGate() throws Exception {
        StubEngine engine = new StubEngine(new ModelAction(null, ActionType.MODEL_CLICK));
        LlmKit kit = new LlmKit(engine, () -> true).step(0.0);
        FakeStepContext ctx = step(2);

        StageResult result = new LlmCoinStage(kit.call, 0.9).decide(ctx);
        kit.routing.record("sata", ctx.sink());

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals(0, engine.selectCalls);
        assertEquals(1, ctx.recorder.count(RunCounter.COIN_LLM));
        assertEquals(1, ctx.recorder.count(RunCounter.GATE_MIN_ACTIONS));
        RecordingSink.Own own = ctx.recorder.lastOwner();
        assertEquals("llm", own.i);
        assertEquals("coin", own.trg);
        assertEquals("min_actions", own.g);
        assertEquals("gate", own.fb);
        assertEquals(0.0, own.uL, 0.0);
    }

    @Test
    public void testTheCoinIsDrawnEveryStepEvenWhenAGateBars() throws Exception {
        // Two pipelines over the same seed: one whose gate bars every step, one whose gate
        // admits. The coin sequence they read is the stream's, step by step, whatever the gate.
        long seed = 42L;
        List<Double> barred = coinsSeen(seed, 2);
        List<Double> admitted = coinsSeen(seed, 5);
        StepRng reference = new StepRng(seed);
        List<Double> expected = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            expected.add(reference.llmCoin());
        }
        assertEquals("u_L is the llmCoin stream's next value at every step open", expected,
                barred);
        assertEquals("a gate that bars never shifts the coin (INV-DP-13)", barred, admitted);
    }

    @Test
    public void testTheCoinIsDrawnOnAStepAGuardTakes() throws Exception {
        long seed = 7L;
        StubEngine engine = new StubEngine(null);
        StepRouting routing = new StepRouting(true, false, false);
        DecisionStage guard = new DecisionStage() {
            @Override
            public String name() {
                return "Budget";
            }

            @Override
            public StageResult decide(StepContext ctx) {
                return StageResult.select(new ModelAction(null, ActionType.MODEL_BACK), "budget");
            }
        };
        DecisionPipeline pipeline = new DecisionPipeline(
                java.util.Arrays.<DecisionStage>asList(guard,
                        new LlmCoinStage(llmOver(engine, routing), 1.0)), routing);
        FakeStepContext ctx = step(3);
        ctx.stepRng = new StepRng(seed);

        pipeline.decide(ctx);
        pipeline.decide(ctx);

        StepRng reference = new StepRng(seed);
        reference.llmCoin();
        assertEquals("step N+1's u_L is the stream's next value after a guard step",
                reference.llmCoin(), ctx.recorder.lastOwner().uL, 0.0);
        assertEquals(0, engine.selectCalls);
        assertEquals("budget", ctx.recorder.lastOwner().i);
    }

    private static LlmCall llmOver(LlmEngine engine, StepRouting routing) {
        return new LlmCall(engine, Collections.<LlmGate>singletonList(new MinActionsGate(3)),
                routing, a -> { }, o -> { }, () -> null);
    }

    /** The {@code dec.own.uL} of six steps of an LLM-coin pipeline on states of {@code actions}. */
    private static List<Double> coinsSeen(long seed, int actions) throws Exception {
        StubEngine engine = new StubEngine(null);
        StepRouting routing = new StepRouting(true, false, false);
        DecisionStage terminal = new DecisionStage() {
            @Override
            public String name() {
                return "SataChain";
            }

            @Override
            public StageResult decide(StepContext ctx) {
                return StageResult.select(new ModelAction(null, ActionType.MODEL_BACK), "sata");
            }
        };
        DecisionPipeline pipeline = new DecisionPipeline(java.util.Arrays.<DecisionStage>asList(
                new LlmCoinStage(llmOver(engine, routing), 1.0), terminal), routing);
        FakeStepContext ctx = step(actions);
        ctx.stepRng = new StepRng(seed);
        List<Double> seen = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pipeline.decide(ctx);
            seen.add(ctx.recorder.lastOwner().uL);
        }
        return seen;
    }
}
