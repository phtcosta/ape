package com.android.commands.monkey.ape.agent.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The pipeline loop's semantics, with stub stages.
 *
 * <p>Stubs rather than real stages on purpose: what is under test here is the <em>arbitration</em> —
 * hard preemption, side effects that do not decide, and the refusal to fall off the end of the roster
 * — and a stub is the only way to assert that a later stage was never consulted. The stages
 * themselves arrive one at a time in the extraction group, each with the goldens as its gate; if the
 * loop's own properties were only tested through real stages, a preemption bug and a predicate bug
 * would look the same.
 */
public class DecisionPipelineTest {

    /** A stage with a scripted answer that records whether it was asked and what it was told. */
    private static final class StubStage implements DecisionStage {

        private final String name;
        private final StageResult answer;
        private int decideCalls;
        private final List<StateTransition> edges = new ArrayList<>();

        StubStage(String name, StageResult answer) {
            this.name = name;
            this.answer = answer;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public StageResult decide(StepContext ctx) {
            decideCalls++;
            return answer;
        }

        @Override
        public void onStateTransition(StateTransition edge) {
            edges.add(edge);
        }

        boolean wasAsked() {
            return decideCalls > 0;
        }
    }

    private static ModelAction action() {
        return new ModelAction(null, ActionType.MODEL_CLICK);
    }

    private static StubStage passes(String name) {
        return new StubStage(name, StageResult.continueChain());
    }

    private static StubStage selects(String name, Action selected, String source) {
        return new StubStage(name, StageResult.select(selected, source));
    }

    private static StubStage acts(String name, String description) {
        return new StubStage(name, StageResult.sideEffect(description));
    }

    /**
     * Builds a pipeline while capturing what it echoed. The echo is emitted by construction, so
     * observing it means capturing around the constructor.
     */
    private static String buildCapturingEcho(List<DecisionStage> stages,
            List<DecisionPipeline> out) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true));
            out.add(new DecisionPipeline(stages));
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    private static DecisionPipeline pipelineOf(DecisionStage... stages) {
        List<DecisionPipeline> built = new ArrayList<>();
        buildCapturingEcho(Arrays.asList(stages), built);
        return built.get(0);
    }

    // --- Hard preemption (INV-DP-02) ---------------------------------------------------------

    @Test
    public void theFirstSelectDecidesTheStep() {
        ModelAction picked = action();
        StubStage first = passes("Budget");
        StubStage decider = selects("LlmNewState", picked, "LLM");
        StubStage later = selects("SataChain", action(), "SATA");

        Action decided = pipelineOf(first, decider, later).decide(null);

        assertSame(picked, decided);
        assertTrue("a stage before the decider is still consulted", first.wasAsked());
        // The whole point of hard preemption: the later stage's predicate, its counters and any RNG
        // draw it would have made do not happen on this step.
        assertFalse("no stage after the first SELECT may be evaluated", later.wasAsked());
    }

    @Test
    public void everyStageIsAskedWhenNoneSelectsUntilTheLast() {
        StubStage budget = passes("Budget");
        StubStage llm = passes("LlmNewState");
        StubStage terminal = selects("SataChain", action(), "SATA");

        pipelineOf(budget, llm, terminal).decide(null);

        assertTrue(budget.wasAsked());
        assertTrue(llm.wasAsked());
        assertTrue(terminal.wasAsked());
    }

    // --- Side effects (INV-DP-05) ------------------------------------------------------------

    @Test
    public void aSideEffectDoesNotEndTheStep() {
        ModelAction picked = action();
        StubStage trigger = acts("ComponentTrigger", "broadcast dispatched");
        StubStage terminal = selects("SataChain", picked, "SATA");

        DecisionPipeline pipeline = pipelineOf(trigger, terminal);
        Action decided = pipeline.decide(null);

        assertSame("the side effect stage acts, the chain still decides", picked, decided);
        assertTrue(terminal.wasAsked());
        assertEquals(Arrays.asList("ComponentTrigger: broadcast dispatched"),
                pipeline.lastStepSideEffects());
    }

    @Test
    public void sideEffectsAreScopedToTheStepThatProducedThem() {
        DecisionPipeline pipeline = pipelineOf(acts("ComponentTrigger", "broadcast dispatched"),
                selects("SataChain", action(), "SATA"));

        pipeline.decide(null);
        assertEquals(1, pipeline.lastStepSideEffects().size());

        // A record that accumulated across steps would attribute the previous step's broadcast to
        // this one, which is exactly the confusion the per-step record exists to prevent.
        pipeline.decide(null);
        assertEquals(1, pipeline.lastStepSideEffects().size());
    }

    @Test
    public void aStepWithNoSideEffectRecordsNone() {
        DecisionPipeline pipeline = pipelineOf(selects("SataChain", action(), "SATA"));

        pipeline.decide(null);

        assertTrue(pipeline.lastStepSideEffects().isEmpty());
    }

    // --- The roster never runs out (INV-DP-06) -----------------------------------------------

    @Test
    public void aRosterWhoseLastStagePassesFailsLoudly() {
        StubStage terminal = passes("SataChain");

        try {
            pipelineOf(passes("Budget"), terminal).decide(null);
            fail("the terminal stage selects or throws; falling off the roster is a wiring bug");
        } catch (IllegalStateException expected) {
            assertTrue("the message must name the roster that decided nothing, got: "
                    + expected.getMessage(), expected.getMessage().contains("SataChain"));
        }
    }

    @Test
    public void anEmptyRosterFailsLoudlyRatherThanReturningNothing() {
        try {
            pipelineOf().decide(null);
            fail("a pipeline with no terminal stage cannot decide a step");
        } catch (IllegalStateException expected) {
            assertEquals(0, pipelineOf().size());
        }
    }

    // --- The assembly echo (INV-DP-01) -------------------------------------------------------

    @Test
    public void assemblyEchoesTheRosterInOrder() {
        List<DecisionPipeline> built = new ArrayList<>();
        String echoed = buildCapturingEcho(
                Arrays.asList(passes("Budget"), acts("ComponentTrigger", "x"),
                        selects("SataChain", action(), "SATA")),
                built);

        assertTrue("the roster must be echoed once at assembly, got: " + echoed,
                echoed.contains("[APE-ARCH] stages=[Budget, ComponentTrigger, SataChain]"));
        assertEquals(Arrays.asList("Budget", "ComponentTrigger", "SataChain"),
                built.get(0).stageNames());
        assertEquals(3, built.get(0).size());
    }

    @Test
    public void theEchoOfAnEmptyRosterIsAnEmptyList() {
        List<DecisionPipeline> built = new ArrayList<>();
        String echoed = buildCapturingEcho(new ArrayList<DecisionStage>(), built);

        assertTrue("got: " + echoed, echoed.contains("[APE-ARCH] stages=[]"));
    }

    // --- Transition forwarding (INV-DP-07) --------------------------------------------------

    @Test
    public void aVisitedEdgeReachesEveryStageOnce() {
        StubStage budget = passes("Budget");
        StubStage stagnation = passes("LlmStagnation");

        pipelineOf(budget, stagnation).onStateTransition(null);

        // Once, not once per stage that happens to want it: a flag re-armed twice per edge would
        // give the stagnation episode a second shot it never had before.
        assertEquals(1, budget.edges.size());
        assertEquals(1, stagnation.edges.size());
    }

    // --- Feature absent = candidate absent (INV-DP-03) --------------------------------------

    @Test
    public void aPlanWithNoMopAndNoLlmAssemblesTheMinimalPipeline() {
        // The bare plan is the aperv arm: the activity budget is on by jar default
        // (Config.activityBudgetEnabled), so the minimal roster is two stages, not one.
        RunSpec aperv = TestRunSpecs.spec();

        assertEquals(
                Arrays.asList(DecisionPipeline.Candidate.BUDGET,
                        DecisionPipeline.Candidate.SATA_CHAIN),
                DecisionPipeline.assembledCandidates(aperv));
    }

    @Test
    public void aPlanWithTheBudgetOffAssemblesOnlyTheTerminalStage() {
        RunSpec noBudget = TestRunSpecs.spec("ape.activityBudgetEnabled", "false");

        assertEquals(Arrays.asList(DecisionPipeline.Candidate.SATA_CHAIN),
                DecisionPipeline.assembledCandidates(noBudget));
    }

    @Test
    public void aFullPlanAssemblesEveryCandidateInTheFixedOrder() {
        RunSpec full = TestRunSpecs.spec(
                "ape.activityBudgetEnabled", "true",
                "ape.mopDataPath", TestRunSpecs.MOP_PATH,
                "ape.activityTriggerEnabled", "true",
                "ape.componentPercentage", "0.1",
                "ape.llmUrl", "http://10.0.2.2:30000/v1",
                "ape.llmOnNewState", "true",
                "ape.llmOnStagnation", "true",
                "ape.llmPercentage", "0.3");

        assertEquals(Arrays.asList(DecisionPipeline.Candidate.values()),
                DecisionPipeline.assembledCandidates(full));
    }

    @Test
    public void theTerminalCandidateIsAssembledByEveryPlan() {
        // Not a gate to forget: SataChain has no feature, because a plan that could omit the fallback
        // would be a plan that cannot decide a step.
        assertEquals(null, DecisionPipeline.Candidate.SATA_CHAIN.gate());
        assertTrue(DecisionPipeline.Candidate.SATA_CHAIN.assembledFor(TestRunSpecs.spec()));
    }

    @Test
    public void candidateNamesAreTheStageNames() {
        assertEquals("Budget", DecisionPipeline.Candidate.BUDGET.stageName());
        assertEquals("LlmNewState", DecisionPipeline.Candidate.LLM_NEW_STATE.stageName());
        assertEquals("LlmStagnation", DecisionPipeline.Candidate.LLM_STAGNATION.stageName());
        assertEquals("LlmRandom", DecisionPipeline.Candidate.LLM_RANDOM.stageName());
        assertEquals("MopLauncher", DecisionPipeline.Candidate.MOP_LAUNCHER.stageName());
        assertEquals("ComponentTrigger", DecisionPipeline.Candidate.COMPONENT_TRIGGER.stageName());
        assertEquals("SataChain", DecisionPipeline.Candidate.SATA_CHAIN.stageName());
    }
}
