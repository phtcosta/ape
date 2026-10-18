package com.android.commands.monkey.ape.agent.pipeline;

import com.android.commands.monkey.ape.llm.LlmEngine;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.telemetry.RunCounter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The declared LLM gates and the one-call-per-step budget (task 7.9.1; llm-routing "Declared LLM
 * Gates", INV-RTR-23, INV-RTR-26; author decision A3).
 */
public class LlmGateTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    private static FakeStepContext stateOf(int actions) throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, actions);
        ctx.isNewState = true;
        ctx.graphStableCounter = 50;
        return ctx;
    }

    @Test
    public void minActionsAdmitsAtOrAboveItsMinimum() throws Exception {
        MinActionsGate gate = new MinActionsGate(3);
        assertEquals("min_actions", gate.name());
        assertFalse("two actions are fewer than three", gate.admits(stateOf(2)));
        assertTrue("three equals the former > 2", gate.admits(stateOf(3)));
        assertTrue(gate.admits(stateOf(4)));
    }

    @Test
    public void breakerGateIsOneConsultation() throws Exception {
        int[] calls = {0};
        boolean[] allows = {false};
        BreakerGate gate = new BreakerGate(() -> {
            calls[0]++;
            return allows[0];
        });
        assertEquals("breaker", gate.name());
        assertFalse(gate.admits(stateOf(5)));
        allows[0] = true;
        assertTrue(gate.admits(stateOf(5)));
        assertEquals(2, calls[0]);
    }

    @Test
    public void theChainShortCircuitsSoTheBreakerIsNotConsultedOnASmallScreen() throws Exception {
        int[] breakerCalls = {0};
        LlmCoinStageTest.StubEngine engine = new LlmCoinStageTest.StubEngine(null);
        LlmKit kit = new LlmKit(engine, () -> {
            breakerCalls[0]++;
            return true;
        }).step();
        FakeStepContext ctx = stateOf(2);

        new LlmNewStateStage(kit.call).decide(ctx);
        kit.routing.record("sata", ctx.sink());

        assertEquals(0, breakerCalls[0]);
        assertEquals("min_actions", ctx.recorder.lastOwner().g);
        assertEquals(1, ctx.recorder.count(RunCounter.GATE_MIN_ACTIONS));
        assertEquals(0, ctx.recorder.count(RunCounter.GATE_BREAKER));
    }

    @Test
    public void anOpenBreakerIsNamedAndCounted() throws Exception {
        LlmCoinStageTest.StubEngine engine = new LlmCoinStageTest.StubEngine(null);
        LlmKit kit = new LlmKit(engine, () -> false).step();
        FakeStepContext ctx = stateOf(5);

        new LlmNewStateStage(kit.call).decide(ctx);
        kit.routing.record("sata", ctx.sink());

        RecordingSink.Own own = ctx.recorder.lastOwner();
        assertEquals("llm", own.i);
        assertEquals("new_state", own.trg);
        assertEquals("breaker", own.g);
        assertEquals("gate", own.fb);
        assertEquals(1, ctx.recorder.count(RunCounter.GATE_BREAKER));
        assertEquals(0, engine.selectCalls);
    }

    @Test
    public void aGateAtZeroIsNotMounted() throws Exception {
        // The composition root leaves out a gate whose key is 0: with no MinActionsGate the
        // LLM is called on a one-action screen.
        LlmCoinStageTest.StubEngine engine = new LlmCoinStageTest.StubEngine(null);
        LlmKit kit = new LlmKit(engine,
                Collections.<LlmGate>singletonList(new BreakerGate(() -> true))).step();

        new LlmNewStateStage(kit.call).decide(stateOf(1));

        assertEquals(1, engine.selectCalls);
    }

    @Test
    public void aGateCountsOncePerStepAcrossTheLlmStages() throws Exception {
        LlmCoinStageTest.StubEngine engine = new LlmCoinStageTest.StubEngine(null);
        LlmKit kit = new LlmKit(engine, () -> true).step(0.0);
        FakeStepContext ctx = stateOf(2);

        new LlmNewStateStage(kit.call).decide(ctx);
        new LlmStagnationStage(kit.call, 100).decide(ctx);
        new LlmCoinStage(kit.call, 0.5).decide(ctx);

        assertEquals("three stages barred by one gate are one lost step", 1,
                ctx.recorder.count(RunCounter.GATE_MIN_ACTIONS));
    }

    /** D-B18: a screenshot that could not be captured spends the call and reads fb "screenshot". */
    @Test
    public void aScreenshotFailureSpendsTheCallAndRecordsItsOwnFallback() throws Exception {
        LlmCoinStageTest.StubEngine engine = new LlmCoinStageTest.StubEngine(null);
        LlmKit kit = new LlmKit(engine, () -> true).step(0.01);
        kit.fallbackReason = LlmEngine.FALLBACK_SCREENSHOT;
        FakeStepContext ctx = stateOf(5);

        assertEquals(StageResult.Kind.CONTINUE, new LlmNewStateStage(kit.call).decide(ctx).kind());
        assertEquals(StageResult.Kind.CONTINUE, new LlmCoinStage(kit.call, 0.02).decide(ctx).kind());
        kit.routing.record("sata", ctx.sink());

        assertEquals("the failed capture spent the step's one call", 1, engine.selectCalls);
        RecordingSink.Own own = ctx.recorder.lastOwner();
        assertEquals("llm", own.i);
        assertEquals("screenshot", own.fb);
    }

    @Test
    public void aFailedNewStateCallIsTheStepsOnlyLlmCall() throws Exception {
        LlmCoinStageTest.StubEngine engine = new LlmCoinStageTest.StubEngine(null);
        LlmKit kit = new LlmKit(engine, () -> true).step(0.01);
        kit.fallbackReason = "error";
        LlmStagnationStage stagnation = new LlmStagnationStage(kit.call, 100);
        FakeStepContext ctx = stateOf(5);

        assertEquals(StageResult.Kind.CONTINUE, new LlmNewStateStage(kit.call).decide(ctx).kind());
        assertEquals(StageResult.Kind.CONTINUE, stagnation.decide(ctx).kind());
        assertEquals(StageResult.Kind.CONTINUE, new LlmCoinStage(kit.call, 0.02).decide(ctx).kind());
        kit.routing.record("sata", ctx.sink());

        assertEquals("at most one LLM call per step (A3)", 1, engine.selectCalls);
        assertEquals("new-state", engine.modeSeen);
        assertEquals("a skipped coin is not a won coin", 0, ctx.recorder.count(RunCounter.COIN_LLM));
        RecordingSink.Own own = ctx.recorder.lastOwner();
        assertEquals("llm", own.i);
        assertEquals("new_state", own.trg);
        assertNull(own.g);
        assertEquals("error", own.fb);

        // The stagnation shot was not spent by the skipped step: the next step fires it.
        kit.step(0.99);
        ctx.isNewState = false;
        stagnation.decide(ctx);
        assertEquals("stagnation", engine.modeSeen);
        assertEquals(2, engine.selectCalls);
    }

    @Test
    public void aBarredStageLeavesTheCallForTheNext() throws Exception {
        // A stage whose gate barred has made no call; the next LLM stage is evaluated as usual.
        // Here the breaker refuses its first consultation only.
        boolean[] first = {true};
        ModelAction answer = new ModelAction(null, ActionType.MODEL_CLICK);
        LlmCoinStageTest.StubEngine engine = new LlmCoinStageTest.StubEngine(answer);
        LlmKit kit = new LlmKit(engine, Arrays.<LlmGate>asList(new BreakerGate(() -> {
            boolean allow = !first[0];
            first[0] = false;
            return allow;
        }))).step(0.0);
        FakeStepContext ctx = stateOf(5);

        assertEquals(StageResult.Kind.CONTINUE, new LlmNewStateStage(kit.call).decide(ctx).kind());
        StageResult coin = new LlmCoinStage(kit.call, 0.5).decide(ctx);
        kit.routing.record(coin.decisionSource(), ctx.sink());

        assertEquals(StageResult.Kind.SELECT, coin.kind());
        RecordingSink.Own own = ctx.recorder.lastOwner();
        assertEquals("llm", own.i);
        assertEquals("trg names the stage that made the call (D-B14.14)", "coin", own.trg);
        assertNull("a call was made, so no gate barred it", own.g);
        assertNull("the LLM took the step: no fallback", own.fb);
    }
}
