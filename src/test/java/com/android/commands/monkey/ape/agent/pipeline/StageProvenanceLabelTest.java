package com.android.commands.monkey.ape.agent.pipeline;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Random;

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
import static org.junit.Assert.assertNotNull;

/**
 * INV-DP-04's last clause, over the whole roster: a {@code select(...)} label says what the action
 * itself was stamped with.
 *
 * <p><b>Why over the roster and not one test per stage.</b> The per-stage form already exists — five of
 * the six selecting stages assert their own label where they are tested, written by the groups that
 * extracted them — so five more copies would restate what is already pinned and still say nothing
 * about a seventh stage added later. What this file adds is a table of one expectation per
 * {@link DecisionPipeline.Candidate} whose <em>coverage of the enum</em> is itself a test: a new
 * candidate has no entry and the suite goes red until someone states what its label is. The stages
 * walked are the ones {@link DecisionPipeline#fromSpec} really assembled from a plan carrying every
 * feature — the plan no shipped preset states — so the roster is production's, not this file's idea of
 * one.
 *
 * <p><b>Nothing in production reads a label yet, which is exactly why it needs a test.</b>
 * {@code StageResult.decisionSource()} has no caller in {@code src/main}: the {@code [APE-STEP]} line
 * reports the action's own stamped source, not the pipeline's label. So a mislabelled stage changes no
 * trace, fails no golden, and is invisible until stage 4 wires the label into the step record — at
 * which point it would arrive already wrong. Until then this file and the per-stage tests are the only
 * readers the label has.
 *
 * <p><b>The clause is not equally substantive everywhere, and the table records which is which.</b>
 * The three LLM stages and the chain read the label back off the action they just stamped
 * ({@code accepted.getDecisionSource().name()}), so equality there holds by construction and what the
 * assertion pins is the constant each one settles on. Two stages are where the clause has teeth:
 * {@code Budget} stamps the action and labels it from a separate constant, so the two are independent
 * writes that could disagree; and {@code MopLauncher}'s action is an {@code ActivityTriggerAction},
 * <em>not</em> a {@code ModelAction}, which is the only reason a constant label is legitimate there at
 * all — the clause is conditional on the action being a {@code ModelAction}. Both facts are asserted
 * below rather than left as comments, because both would fail silently.
 *
 * <p><b>And the chain's label is not a constant</b>, which is where a per-stage assertion does get it
 * wrong: {@code SataChain} stamps by the boost-attribution rule ({@code action-selection}), so the
 * source it reports is whichever mechanism's boost was largest, and {@code SataChainStageTest} pins the
 * literal {@code SATA} its fixture happens to produce. That literal keeps passing if the stage is
 * rewritten to hardcode {@code SATA} — flattening every MOP- and WTG-guided step into the baseline
 * source. The table marks the chain as following the stamp, and the test below drives it under three
 * different stamps so the label has to track them.
 */
public class StageProvenanceLabelTest {

    /** What one candidate's stage must produce on a step it is entitled to decide. */
    private static final class Expected {

        /** The constant label the stage must carry, or null when the label is not a constant. */
        private final String label;

        private final boolean selects;

        private Expected(String label, boolean selects) {
            this.label = label;
            this.selects = selects;
        }

        /** The stage selects and always reports this label. */
        static Expected label(String label) {
            return new Expected(label, true);
        }

        /** The stage selects, but its label is whatever the action was stamped with. */
        static Expected followsTheStamp() {
            return new Expected(null, true);
        }

        /** The stage never ends a step, so it produces no label to check (INV-DP-05). */
        static Expected neverSelects() {
            return new Expected(null, false);
        }
    }

    private static final Map<DecisionPipeline.Candidate, Expected> EXPECTATIONS;

    static {
        Map<DecisionPipeline.Candidate, Expected> table =
                new EnumMap<>(DecisionPipeline.Candidate.class);
        table.put(DecisionPipeline.Candidate.BUDGET,
                Expected.label(ModelAction.DecisionSource.Budget.name()));
        table.put(DecisionPipeline.Candidate.LLM_NEW_STATE,
                Expected.label(ModelAction.DecisionSource.LLM.name()));
        table.put(DecisionPipeline.Candidate.LLM_STAGNATION,
                Expected.label(ModelAction.DecisionSource.LLM.name()));
        table.put(DecisionPipeline.Candidate.LLM_RANDOM,
                Expected.label(ModelAction.DecisionSource.LLM.name()));
        table.put(DecisionPipeline.Candidate.MOP_LAUNCHER,
                Expected.label(ModelAction.DecisionSource.Component.name()));
        table.put(DecisionPipeline.Candidate.COMPONENT_TRIGGER, Expected.neverSelects());
        table.put(DecisionPipeline.Candidate.SATA_CHAIN, Expected.followsTheStamp());
        EXPECTATIONS = table;
    }

    /**
     * The plan carrying every decision feature at once.
     *
     * <p>Built explicitly because no shipped preset states one: every arm carries
     * {@code ape.activityTriggerEnabled=false} and none states {@code ape.componentPercentage}. The
     * cadence is one so the launcher's very first step is a firing point, and the rate keys are at
     * their extremes so the two coins are decided rather than sampled.
     */
    private static RunSpec fullRosterPlan() {
        return TestRunSpecs.spec(
                "ape.preset", Presets.LLM,
                "ape.llmUrl", PipelineFixture.LLM_URL,
                "ape.mopDataPath", TestRunSpecs.MOP_PATH,
                "ape.activityTriggerEnabled", "true",
                "ape.activityTriggerStagnationStep", "1",
                "ape.componentPercentage", "1.0",
                "ape.graphStableRestartThreshold", "10",
                "ape.llmPercentage", "1.0");
    }

    /**
     * The agent every stage of that roster is bound to, answering two producers with two distinct
     * actions.
     *
     * <p>They have to be distinct. The trivial-activity search is both the budget gate's producer and
     * the chain's fourth rung, so one action answering both would be stamped {@code Budget} by the gate
     * and then handed back by the chain — which would make the chain's label agree with a stamp the
     * gate wrote. The chain answers from its first rung instead, on an action no other stage touches.
     */
    private static class LabelledAgent extends PipelineFixture.FakeAgent {

        private final ModelAction trivial;
        private final ModelAction fromTheBuffer;

        LabelledAgent(PipelineFixture.StubLlm llm, ModelAction trivial, ModelAction fromTheBuffer) {
            super(llm, fromTheBuffer);
            this.trivial = trivial;
            this.fromTheBuffer = fromTheBuffer;
        }

        @Override
        public ModelAction selectNewActionForTrivialActivity() {
            return trivial;
        }

        @Override
        public ModelAction selectNewActionFromBuffer() {
            return fromTheBuffer;
        }
    }

    /**
     * One step every stage of the full roster is entitled to decide: an empty buffer over a state with
     * three actions, newly entered, its stability counter at the midpoint of a threshold of ten, and
     * an exhausted budget for the activity it is on.
     *
     * <p>Each stage is asked directly rather than through {@code decide()}, because hard preemption
     * (INV-DP-02) means a pipeline walk would let the first stage answer for all of them — the very
     * property {@link HardPreemptionTest} exists to assert.
     */
    private static FakeStepContext stepEveryStageMayDecide() throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(PipelineFixture.ACTIVITY, 3);
        ctx.isNewState = true;
        ctx.graphStableCounter = 5;
        ctx.actionBufferSize = 0;
        ctx.timestamp = 7;
        ctx.mopData = PipelineFixture.census();
        ctx.graph = PipelineFixture.emptyGraph();
        ctx.random = new Random(3L);
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
        // The point of a table over five copies of one assertion: a candidate added without an
        // expectation fails here, instead of being the one stage nothing checks.
        assertEquals(EnumSet.allOf(DecisionPipeline.Candidate.class), EXPECTATIONS.keySet());
    }

    @Test
    public void everyAssembledStageLabelsItsSelectionWithTheStampedProvenance() throws Exception {
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullRosterPlan(),
                new LabelledAgent(new PipelineFixture.StubLlm(true, action()), action(), action()));
        assertEquals("the plan must assemble every candidate for this walk to cover them",
                DecisionPipeline.Candidate.values().length, pipeline.size());

        for (DecisionStage stage : pipeline.stages()) {
            Expected expected = EXPECTATIONS.get(candidateOf(stage));
            StageResult result = stage.decide(stepEveryStageMayDecide());

            if (!expected.selects) {
                assertEquals(stage.name() + " must never end a step (INV-DP-05)",
                        StageResult.Kind.SIDE_EFFECT, result.kind());
                continue;
            }
            assertEquals(stage.name() + " was expected to decide this step",
                    StageResult.Kind.SELECT, result.kind());
            assertNotNull(stage.name() + " selected with no label", result.decisionSource());
            if (expected.label != null) {
                assertEquals(stage.name() + " changed the label it reports",
                        expected.label, result.decisionSource());
            }
            Action selected = result.action();
            if (selected instanceof ModelAction) {
                assertEquals(stage.name() + " labelled its action with a source it was not stamped"
                                + " with (INV-DP-04)",
                        ((ModelAction) selected).getDecisionSource().name(),
                        result.decisionSource());
            }
        }
    }

    @Test
    public void theLaunchersActionIsNotAModelActionWhichIsWhyItsLabelMayBeAConstant() throws Exception {
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullRosterPlan(),
                new LabelledAgent(new PipelineFixture.StubLlm(true, action()), action(), action()));

        StageResult result = stageNamed(pipeline, DecisionPipeline.Candidate.MOP_LAUNCHER)
                .decide(stepEveryStageMayDecide());

        // INV-DP-04's equality clause is conditional on the action being a ModelAction, and the
        // launcher's ActivityTriggerAction carries no provenance field to agree with. That is what
        // licenses the constant label — so if the trigger ever becomes a ModelAction, the constant
        // stops being a statement about the action and this assertion is where that surfaces.
        assertFalse("the launcher's constant label would need a stamp to agree with",
                result.action() instanceof ModelAction);
        assertEquals(ModelAction.DecisionSource.Component.name(), result.decisionSource());
    }

    @Test
    public void theBudgetGateStampsAndLabelsAsTwoWritesThatMustAgree() throws Exception {
        ModelAction trivial = action();
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullRosterPlan(),
                new LabelledAgent(new PipelineFixture.StubLlm(true, action()), trivial, action()));

        StageResult result = stageNamed(pipeline, DecisionPipeline.Candidate.BUDGET)
                .decide(stepEveryStageMayDecide());

        // Unlike the LLM stages, this one does not read its label back off the action: it stamps
        // DecisionSource.Budget and then names the constant again. Two writes of the same fact are
        // two places to get it wrong, which is why the agreement is asserted rather than assumed.
        assertEquals(ModelAction.DecisionSource.Budget, trivial.getDecisionSource());
        assertEquals(ModelAction.DecisionSource.Budget.name(), result.decisionSource());
    }

    @Test
    public void theChainsLabelFollowsTheStampRatherThanAConstant() throws Exception {
        for (ModelAction.DecisionSource stamped : EnumSet.of(ModelAction.DecisionSource.SATA,
                ModelAction.DecisionSource.MopFrontier, ModelAction.DecisionSource.WTG)) {
            ModelAction fromTheChain = action();
            fromTheChain.setDecisionSource(stamped);
            DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullRosterPlan(),
                    new LabelledAgent(new PipelineFixture.StubLlm(true, action()), action(), fromTheChain));

            StageResult result = stageNamed(pipeline, DecisionPipeline.Candidate.SATA_CHAIN)
                    .decide(stepEveryStageMayDecide());

            // The chain's source is decided by the boost-attribution rule at the pick sites, not by
            // the stage: a constant here would report SATA over every MOP- and WTG-guided step and
            // silently flatten the measurement the decision_source field exists for.
            assertEquals(stamped.name(), result.decisionSource());
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

    /** The candidate a stage's {@code name()} identifies — the roster's only link back to the table. */
    private static DecisionPipeline.Candidate candidateOf(DecisionStage stage) {
        for (DecisionPipeline.Candidate candidate : DecisionPipeline.Candidate.values()) {
            if (candidate.stageName().equals(stage.name())) {
                return candidate;
            }
        }
        throw new AssertionError("assembled stage " + stage.name() + " is no declared candidate");
    }
}
