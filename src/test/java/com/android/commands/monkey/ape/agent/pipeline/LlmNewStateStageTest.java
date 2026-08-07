package com.android.commands.monkey.ape.agent.pipeline;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmEngine;
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
 * <p>The order of the three guards is the part worth pinning. The shared precondition runs first,
 * the stage's own condition second, and the circuit breaker last — and only the breaker has a side
 * effect, advancing an OPEN&rarr;HALF_OPEN probe and latching a log line once per open episode. A
 * step either of the first two already declined must therefore not reach it, or a run would spend
 * probes on steps that were never the LLM's to decide.
 *
 * <p><b>The condition is the whole trigger now.</b> The predicate this stage inherited also tested
 * that new-state routing was enabled; that is the condition under which the stage is assembled at
 * all (INV-DP-03), so it is asserted on the roster in {@link DecisionPipelineFromSpecTest} and not
 * here. What is left inside the stage is {@code ctx.isNewState()}, and that is what these tests
 * drive.
 */
public class LlmNewStateStageTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /** The engine reduced to one answer, recording the call the stage made. */
    private static class StubEngine extends LlmEngine {

        private final ModelAction answer;
        int selectCalls;
        String modeSeen;
        GUITree treeSeen;

        StubEngine(ModelAction answer) {
            // Every step of the real pipeline is replaced below, so none of the units it composes
            // is reachable and none needs to exist.
            super(null, null, null, null, null, null);
            this.answer = answer;
        }

        @Override
        public ModelAction selectAction(GUITree tree, State state, List<ModelAction> actions,
                MopData mopData, List<ApePromptBuilder.ActionHistoryEntry> history, String mode) {
            selectCalls++;
            modeSeen = mode;
            treeSeen = tree;
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

    private static FakeStepContext routableStep() throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 3);
        ctx.isNewState = true;
        ctx.actionBufferSize = 0;
        ctx.timestamp = 7;
        return ctx;
    }

    private static LlmNewStateStage stageOver(StubEngine engine, Gate gate,
            List<ModelAction> resolved) {
        return new LlmNewStateStage(engine, gate, resolved::add);
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        assertEquals(DecisionPipeline.Candidate.LLM_NEW_STATE.stageName(),
                stageOver(new StubEngine(null), new Gate(false), new java.util.ArrayList<>())
                        .name());
    }

    @Test
    public void testAnAcceptedAnswerDecidesTheStepAsLlm() throws Exception {
        ModelAction answer = new ModelAction(null, ActionType.MODEL_CLICK);
        StubEngine engine = new StubEngine(answer);
        List<ModelAction> resolved = new java.util.ArrayList<>();
        FakeStepContext ctx = routableStep();

        StageResult result = stageOver(engine, new Gate(true), resolved).decide(ctx);

        assertEquals(StageResult.Kind.SELECT, result.kind());
        assertSame(answer, result.action());
        assertEquals("LLM", result.decisionSource());
        assertEquals("the label is the provenance the stage stamped (INV-DP-04)",
                answer.getDecisionSource().name(), result.decisionSource());
        assertEquals(ModelAction.PickChannel.LLM, answer.getPickChannel());
        assertEquals("a matched widget action is already resolved and must not be re-resolved",
                0, resolved.size());
        assertEquals("new-state", engine.modeSeen);
    }

    @Test
    public void testASynthesizedTapIsResolvedBeforeItIsSelected() throws Exception {
        LlmTapAction tap = new LlmTapAction(null, 600, 900, false);
        StubEngine engine = new StubEngine(tap);
        List<ModelAction> resolved = new java.util.ArrayList<>();

        StageResult result =
                stageOver(engine, new Gate(true), resolved).decide(routableStep());

        assertEquals(StageResult.Kind.SELECT, result.kind());
        // Off-tree, so it never passed the per-state resolution pass; dispatching it unresolved is
        // the NPE the guard exists to prevent (llm-coordinate-tap D7).
        assertEquals(1, resolved.size());
        assertSame(tap, resolved.get(0));
    }

    @Test
    public void testADecliningModelPassesRatherThanFailing() throws Exception {
        StubEngine engine = new StubEngine(null);

        StageResult result = stageOver(engine, new Gate(true), new java.util.ArrayList<>())
                .decide(routableStep());

        // Every engine failure — no answer, no match, banned pair, transport — is this one path
        // (INV-DP-11): the remainder of the pipeline decides the step.
        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals(1, engine.selectCalls);
    }

    @Test
    public void testAnOpenBreakerDoesNotCallTheModel() throws Exception {
        StubEngine engine = new StubEngine(new ModelAction(null, ActionType.MODEL_CLICK));
        Gate gate = new Gate(false);

        StageResult result =
                stageOver(engine, gate, new java.util.ArrayList<>()).decide(routableStep());

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("the breaker is consulted once and its answer ends the step here",
                1, gate.calls);
        assertEquals(0, engine.selectCalls);
    }

    @Test
    public void testAnAlreadySeenStateDeclinesBeforeConsultingTheBreaker() throws Exception {
        StubEngine engine = new StubEngine(new ModelAction(null, ActionType.MODEL_CLICK));
        Gate gate = new Gate(true);
        FakeStepContext ctx = routableStep();
        ctx.isNewState = false;

        StageResult result = stageOver(engine, gate, new java.util.ArrayList<>()).decide(ctx);

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("this hook exists for first arrivals, and a revisit is not one: reaching the"
                + " breaker would spend a probe on a step this hook was never entitled to",
                0, gate.calls);
        assertEquals(0, engine.selectCalls);
    }

    @Test
    public void testANonEmptyBufferDeclinesBeforeConsultingTheBreaker() throws Exception {
        StubEngine engine = new StubEngine(new ModelAction(null, ActionType.MODEL_CLICK));
        Gate gate = new Gate(true);
        FakeStepContext ctx = routableStep();
        ctx.actionBufferSize = 1;

        StageResult result = stageOver(engine, gate, new java.util.ArrayList<>()).decide(ctx);

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("mid-sequence steps never reach the breaker: consulting it would spend a"
                + " circuit-breaker probe on a step the LLM was never offered",
                0, gate.calls);
    }

    @Test
    public void testATooSmallScreenDeclinesBeforeConsultingTheBreaker() throws Exception {
        StubEngine engine = new StubEngine(new ModelAction(null, ActionType.MODEL_CLICK));
        Gate gate = new Gate(true);
        FakeStepContext ctx = routableStep();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 2);

        StageResult result = stageOver(engine, gate, new java.util.ArrayList<>()).decide(ctx);

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("the threshold is strictly more than two actions", 0, gate.calls);
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
