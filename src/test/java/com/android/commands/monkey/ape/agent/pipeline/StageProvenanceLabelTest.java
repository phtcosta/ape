package com.android.commands.monkey.ape.agent.pipeline;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import org.junit.Test;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.runtime.Presets;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * INV-DP-04's last clause, over the whole roster: a {@code select(...)} label is the step's owner,
 * and it agrees with the owner of the provenance stamped on a {@code ModelAction} (design D-B7).
 *
 * <p>The table holds one expectation per {@link DecisionPipeline.Candidate}, and its coverage of
 * the enum is itself a test: a new candidate has no entry and the suite goes red until someone
 * states its owner. The stages walked are the ones {@link DecisionPipeline#fromSpec} assembles from
 * the {@code as_mop_llm} cell, which mounts all nine.
 *
 * <p>The owners are constants now: {@code SataChain} is {@code sata} whatever rung answered and
 * whatever boost the action carries, and {@code MopLauncher}'s {@code ActivityTriggerAction} is not
 * a {@code ModelAction}, which is why its label has no stamp to agree with.
 */
public class StageProvenanceLabelTest {

    /** The owner each candidate's stage labels a selection with, or null when it never selects. */
    private static final Map<DecisionPipeline.Candidate, String> EXPECTATIONS;

    static {
        Map<DecisionPipeline.Candidate, String> table =
                new EnumMap<>(DecisionPipeline.Candidate.class);
        table.put(DecisionPipeline.Candidate.BUDGET, "budget");
        table.put(DecisionPipeline.Candidate.RESTART, "restart");
        table.put(DecisionPipeline.Candidate.LLM_NEW_STATE, "llm");
        table.put(DecisionPipeline.Candidate.LLM_STAGNATION, "llm");
        table.put(DecisionPipeline.Candidate.LLM_COIN, "llm");
        table.put(DecisionPipeline.Candidate.MOP_LAUNCHER, "launcher");
        table.put(DecisionPipeline.Candidate.COMPONENT_TRIGGER, null);
        table.put(DecisionPipeline.Candidate.MOP_COIN, "mop");
        table.put(DecisionPipeline.Candidate.SATA_CHAIN, "sata");
        EXPECTATIONS = table;
    }

    /**
     * The {@code as_mop_llm} cell, with the rates at their extremes so the coins are decided
     * rather than sampled and a launcher cadence of one.
     */
    static RunSpec fullRosterPlan() {
        return TestRunSpecs.spec(
                "ape.preset", Presets.AS_MOP_LLM,
                "ape.staticDataPath", TestRunSpecs.MOP_PATH,
                "ape.llmUrl", PipelineFixture.LLM_URL,
                "ape.activityTriggerStagnationStep", "1",
                "ape.componentPercentage", "1.0",
                "ape.graphStableRestartThreshold", "10",
                "ape.llmPercentage", "1.0",
                "ape.mopCoinPercentage", "1.0");
    }

    /**
     * The agent every stage of that roster is bound to, answering the budget's producer and the
     * chain with two distinct actions, with a restart due.
     */
    private static class LabelledAgent extends PipelineFixture.FakeAgent {

        private final ModelAction trivial;
        private final ModelAction fromTheBuffer;

        LabelledAgent(PipelineFixture.StubLlm llm, ModelAction trivial, ModelAction fromTheBuffer) {
            super(llm, fromTheBuffer);
            this.trivial = trivial;
            this.fromTheBuffer = fromTheBuffer;
            this.restartDue = true;
        }

        @Override
        public ModelAction selectNewActionForTrivialActivity() {
            return trivial;
        }

        @Override
        public ModelAction budgetNavigation() {
            return trivial;
        }

        @Override
        public ModelAction selectNewActionFromBuffer() {
            return fromTheBuffer;
        }
    }

    /**
     * One step every stage of the full roster is entitled to decide: three actions, the first an
     * untried MOP target, newly entered, the stability counter at the midpoint of a threshold of
     * ten, and an exhausted budget.
     */
    static FakeStepContext stepEveryStageMayDecide() throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(PipelineFixture.ACTIVITY, 3);
        ModelAction target = ctx.newState.getActions().get(0);
        target.setValid(true);
        target.setMopWeight(500);
        ctx.isNewState = true;
        ctx.graphStableCounter = 5;
        ctx.actionBufferSize = 0;
        ctx.timestamp = 7;
        ctx.mopData = PipelineFixture.census();
        ctx.staticModel = PipelineFixture.staticCensus();
        ctx.graph = PipelineFixture.emptyGraph();
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(0, 0);
        tracker.registerActivity(PipelineFixture.ACTIVITY, 0);
        ctx.budgetTracker = tracker;
        return ctx;
    }

    private static ModelAction action() {
        return new ModelAction(null, ActionType.MODEL_CLICK);
    }

    @Test
    public void everyCandidateStatesWhatItsLabelIs() {
        assertEquals(EnumSet.allOf(DecisionPipeline.Candidate.class), EXPECTATIONS.keySet());
    }

    @Test
    public void everyAssembledStageLabelsItsSelectionWithItsOwner() throws Exception {
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullRosterPlan(),
                new LabelledAgent(new PipelineFixture.StubLlm(true, action()), action(), action()));
        assertEquals("the plan must assemble every candidate for this walk to cover them",
                DecisionPipeline.Candidate.values().length, pipeline.size());

        for (DecisionStage stage : pipeline.stages()) {
            String expected = EXPECTATIONS.get(candidateOf(stage));
            FakeStepContext ctx = stepEveryStageMayDecide();
            pipeline.openStep(ctx);
            StageResult result = stage.decide(ctx);

            if (expected == null) {
                assertEquals(stage.name() + " must never end a step (INV-DP-05)",
                        StageResult.Kind.SIDE_EFFECT, result.kind());
                continue;
            }
            assertEquals(stage.name() + " was expected to decide this step",
                    StageResult.Kind.SELECT, result.kind());
            assertEquals(stage.name() + " changed the owner it reports", expected,
                    result.decisionSource());
            Action selected = result.action();
            if (selected instanceof ModelAction
                    && candidateOf(stage) != DecisionPipeline.Candidate.SATA_CHAIN) {
                assertEquals(stage.name() + " labelled its action with an owner it was not stamped"
                                + " with (INV-DP-04)",
                        ((ModelAction) selected).getDecisionSource().owner(),
                        result.decisionSource());
            }
        }
    }

    @Test
    public void theLaunchersActionIsNotAModelAction() throws Exception {
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullRosterPlan(),
                new LabelledAgent(new PipelineFixture.StubLlm(true, action()), action(), action()));
        FakeStepContext ctx = stepEveryStageMayDecide();
        pipeline.openStep(ctx);

        StageResult result = stageNamed(pipeline, DecisionPipeline.Candidate.MOP_LAUNCHER)
                .decide(ctx);

        assertFalse("the launcher's constant label has no stamp to agree with",
                result.action() instanceof ModelAction);
        assertEquals("launcher", result.decisionSource());
    }

    @Test
    public void theGuardsStampTheirOwnChannel() throws Exception {
        ModelAction trivial = action();
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullRosterPlan(),
                new LabelledAgent(new PipelineFixture.StubLlm(true, action()), trivial, action()));
        FakeStepContext ctx = stepEveryStageMayDecide();
        pipeline.openStep(ctx);

        stageNamed(pipeline, DecisionPipeline.Candidate.BUDGET).decide(ctx);
        StageResult restart = stageNamed(pipeline, DecisionPipeline.Candidate.RESTART).decide(ctx);

        assertEquals(ModelAction.DecisionSource.Budget, trivial.getDecisionSource());
        assertEquals(ModelAction.PickChannel.BUDGET, trivial.getPickChannel());
        ModelAction start = (ModelAction) restart.action();
        assertEquals(ModelAction.DecisionSource.RESTART, start.getDecisionSource());
        assertEquals(ModelAction.PickChannel.RESTART, start.getPickChannel());
    }

    @Test
    public void theChainsOwnerIsSataWhateverTheStamp() throws Exception {
        for (ModelAction.DecisionSource stamped : EnumSet.of(ModelAction.DecisionSource.SATA,
                ModelAction.DecisionSource.MOP)) {
            ModelAction fromTheChain = action();
            fromTheChain.setDecisionSource(stamped);
            DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullRosterPlan(),
                    new LabelledAgent(new PipelineFixture.StubLlm(true, action()), action(),
                            fromTheChain));

            StageResult result = stageNamed(pipeline, DecisionPipeline.Candidate.SATA_CHAIN)
                    .decide(stepEveryStageMayDecide());

            assertEquals("dec.src is the executed owner, no longer the largest boost (D-B7)",
                    "sata", result.decisionSource());
        }
    }

    /** The assembled stage of {@code candidate}, by the name both it and the candidate report. */
    private static DecisionStage stageNamed(DecisionPipeline pipeline,
            DecisionPipeline.Candidate candidate) {
        for (DecisionStage stage : pipeline.stages()) {
            if (stage.name().equals(candidate.stageName())) {
                return stage;
            }
        }
        throw new AssertionError("no assembled stage named " + candidate.stageName());
    }

    /** The candidate a stage's {@code name()} identifies. */
    private static DecisionPipeline.Candidate candidateOf(DecisionStage stage) {
        for (DecisionPipeline.Candidate candidate : DecisionPipeline.Candidate.values()) {
            if (candidate.stageName().equals(stage.name())) {
                return candidate;
            }
        }
        throw new AssertionError("assembled stage " + stage.name() + " is no declared candidate");
    }
}
