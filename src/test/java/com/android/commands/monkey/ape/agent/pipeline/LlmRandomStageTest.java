package com.android.commands.monkey.ape.agent.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.MopData;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The rate-driven hook, and the draw order that makes a seeded run reproducible.
 *
 * <p>The assertions that matter most here are about <em>when</em> the coin is drawn rather than what
 * it says. A step the shared precondition declined must not reach it: a draw taken on such a step
 * would advance the stream by one and shift every later decision of the run, which is the failure
 * mode INV-DP-10 exists to name and the goldens exist to catch. Those tests count draws on the
 * stream itself, because "was the coin flipped" is a fact about the generator, not about the answer.
 *
 * <p><b>Both halves of the coin are now the stage's own.</b> The rate arrives from the plan and the
 * stream arrives from the agent, so the rate group below drives the real draw at a stated rate
 * instead of asserting against a jar default. The stream is {@code ape.getRandom()} in production —
 * not {@code RunContext.rng()} — and which one it is is a parity constraint, asserted at assembly.
 */
public class LlmRandomStageTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /** The rate the shipped LLM arms state, and the one the rate group is written against. */
    private static final double SHIPPED_RATE = 0.02;

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

    /** The agent's stream, counting the draws the stage took from it. */
    private static class CountingRandom extends Random {

        private static final long serialVersionUID = 1L;

        int draws;

        CountingRandom(long seed) {
            super(seed);
        }

        @Override
        public double nextDouble() {
            draws++;
            return super.nextDouble();
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

    private static FakeStepContext routableStep() throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 3);
        ctx.actionBufferSize = 0;
        return ctx;
    }

    /** A stage whose coin always comes up, so a test can assert everything but the rate. */
    private static LlmRandomStage certainStageOver(StubEngine engine, Gate gate) {
        return stageOver(engine, gate, 1.0, new CountingRandom(42L));
    }

    private static LlmRandomStage stageOver(StubEngine engine, Gate gate, double percentage,
            Random random) {
        return new LlmRandomStage(engine, gate, percentage, random,
                new ArrayList<ModelAction>()::add);
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        assertEquals(DecisionPipeline.Candidate.LLM_RANDOM.stageName(),
                certainStageOver(new StubEngine(null), new Gate(false)).name());
    }

    @Test
    public void testAWonCoinDecidesTheStepAsLlm() throws Exception {
        ModelAction answer = new ModelAction(null, ActionType.MODEL_CLICK);
        StubEngine engine = new StubEngine(answer);

        StageResult result =
                certainStageOver(engine, new Gate(true)).decide(routableStep());

        assertEquals(StageResult.Kind.SELECT, result.kind());
        assertEquals("LLM", result.decisionSource());
        assertEquals(answer.getDecisionSource().name(), result.decisionSource());
        assertEquals("random", engine.modeSeen);
    }

    @Test
    public void testALostCoinPassesWithoutReachingTheBreaker() throws Exception {
        StubEngine engine = new StubEngine(new ModelAction(null, ActionType.MODEL_CLICK));
        Gate gate = new Gate(true);
        // A zero rate cannot be reached by any draw, so the coin is lost whatever the stream says.
        // Assembly never builds such a stage; here it is the shortest way to state "the coin lost".
        StageResult result =
                stageOver(engine, gate, 0.0, new CountingRandom(42L)).decide(routableStep());

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("the coin is ahead of the breaker, so a lost one costs no probe", 0, gate.calls);
        assertEquals(0, engine.selectCalls);
    }

    @Test
    public void testAnOpenBreakerPassesAfterTheCoinWasDrawn() throws Exception {
        // The order is the assertion: the coin is drawn, then the breaker is asked. Swapping them
        // would leave the stream un-advanced on a breaker-open step and move every later draw.
        StubEngine engine = new StubEngine(new ModelAction(null, ActionType.MODEL_CLICK));
        Gate gate = new Gate(false);
        CountingRandom random = new CountingRandom(42L);

        StageResult result = stageOver(engine, gate, 1.0, random).decide(routableStep());

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals(1, random.draws);
        assertEquals(1, gate.calls);
        assertEquals(0, engine.selectCalls);
    }

    @Test
    public void testADecliningModelPassesRatherThanFailing() throws Exception {
        StubEngine engine = new StubEngine(null);

        assertEquals(StageResult.Kind.CONTINUE,
                certainStageOver(engine, new Gate(true)).decide(routableStep()).kind());
        assertEquals(1, engine.selectCalls);
    }

    @Test
    public void testAGatedStepNeverDrawsTheCoin() throws Exception {
        StubEngine engine = new StubEngine(new ModelAction(null, ActionType.MODEL_CLICK));
        CountingRandom random = new CountingRandom(42L);
        FakeStepContext ctx = routableStep();
        ctx.actionBufferSize = 1;

        StageResult result = stageOver(engine, new Gate(true), 1.0, random).decide(ctx);

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("a draw on a gated step advances the seeded stream and moves every later"
                + " decision of the run (INV-DP-10)", 0, random.draws);
    }

    @Test
    public void testATooSmallScreenNeverDrawsTheCoin() throws Exception {
        StubEngine engine = new StubEngine(new ModelAction(null, ActionType.MODEL_CLICK));
        CountingRandom random = new CountingRandom(42L);
        FakeStepContext ctx = routableStep();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 2);

        assertEquals(StageResult.Kind.CONTINUE,
                stageOver(engine, new Gate(true), 1.0, random).decide(ctx).kind());
        assertEquals(0, random.draws);
    }

    // -------------------------------------------------------------------------
    // The coin itself: the plan's rate, drawn from the injected stream
    // -------------------------------------------------------------------------

    /** How many of {@code steps} consecutive steps the stage let through to the engine. */
    private static int firingsOver(int steps, double percentage, long seed) throws Exception {
        StubEngine engine = new StubEngine(null);
        LlmRandomStage stage = stageOver(engine, new Gate(true), percentage, new Random(seed));
        FakeStepContext ctx = routableStep();
        for (int i = 0; i < steps; i++) {
            stage.decide(ctx);
        }
        return engine.selectCalls;
    }

    @Test
    public void theCoinFiresAtAboutThePlansRate() throws Exception {
        // At 2% roughly 20 of 1000 steps route. The band is wide because this asserts that the rate
        // reaches the draw at all, not that java.util.Random is uniform.
        int firings = firingsOver(1000, SHIPPED_RATE, 42L);
        assertTrue("expected about 2% of 1000 steps to route, got " + firings,
                firings >= 5 && firings <= 50);
    }

    @Test
    public void aHigherRateFiresMoreOften() throws Exception {
        // The rate is a plan value now, so "which rate the stage was built with" is a thing a test
        // can vary. Without this, a stage that ignored its rate and drew against a constant would
        // still pass the band above.
        assertTrue("a tenfold rate must route far more often",
                firingsOver(1000, 0.2, 42L) > firingsOver(1000, SHIPPED_RATE, 42L) * 3);
    }

    @Test
    public void theSameSeedProducesTheSameSequence() throws Exception {
        boolean[] first = sequenceOver(50, 12345L);
        boolean[] second = sequenceOver(50, 12345L);

        assertArrayEquals("the same stream must produce the same routing decisions", first, second);
    }

    @Test
    public void differentSeedsProduceDifferentSequences() throws Exception {
        assertFalse("different streams must not agree on every one of fifty steps",
                java.util.Arrays.equals(sequenceOver(50, 111L), sequenceOver(50, 999L)));
    }

    /** Whether each of {@code steps} consecutive steps routed, at the shipped rate. */
    private static boolean[] sequenceOver(int steps, long seed) throws Exception {
        StubEngine engine = new StubEngine(null);
        LlmRandomStage stage =
                stageOver(engine, new Gate(true), SHIPPED_RATE, new Random(seed));
        FakeStepContext ctx = routableStep();
        boolean[] routed = new boolean[steps];
        int before = 0;
        for (int i = 0; i < steps; i++) {
            stage.decide(ctx);
            routed[i] = engine.selectCalls > before;
            before = engine.selectCalls;
        }
        return routed;
    }
}
