package com.android.commands.monkey.ape.agent.pipeline;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ActivityTriggerAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.runtime.Presets;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Preemption is hard: the first stage that selects ends the step, and the ones below it are not
 * merely outvoted — they never run (INV-DP-02).
 *
 * <p>This is the permanent form of the preemption golden. The oracle's
 * {@code PreemptionGoldenTest} pins the same order on a device-shaped run, but it can only reach
 * three of the four contenders: no shipped preset states {@code ape.componentPercentage}, so no
 * preset assembles the component trigger and no scenario can produce a step where its coin is live.
 * Here the plan is stated rather than shipped, so all seven stages are assembled and the step
 * genuinely qualifies for all four mechanisms at once.
 *
 * <p><b>The controls are the test.</b> "No trigger fired and no rung ran" is trivially true of a
 * step where nothing else could have fired anyway — a census the launcher rejects or a coin the
 * plan never enables would produce the same passing assertions. So the same step is run twice more
 * with the model declining: once to watch the launcher fire from its firing point, once with the
 * launcher one step short of it to watch the coin spend itself and the chain still decide. What the
 * first test then asserts is a precedence, not an accident of the fixture.
 */
public class HardPreemptionTest {

    /**
     * Every stage assembled — the {@code as_mop_llm} cell: the two guards, the three LLM modes, the
     * launcher at the cadence the caller names, a component coin that always comes up, the MOP coin
     * and the terminal chain.
     */
    private static RunSpec fullPlan(int cadence) {
        return PipelineFixture.preset(Presets.AS_MOP_LLM, PipelineFixture.join(new String[] {
                "ape.llmUrl", PipelineFixture.LLM_URL,
                "ape.staticDataPath", TestRunSpecs.MOP_PATH,
                "ape.activityTriggerStagnationStep", Integer.toString(cadence),
                "ape.componentPercentage", "1.0"}, PipelineFixture.LIVE_HOOK_KEYS));
    }

    /**
     * The step all mechanisms qualify for: routable, budgeted, with a census, a graph and a
     * two-action buffer the winner must abandon.
     */
    private static FakeStepContext contendedStep() throws Exception {
        FakeStepContext ctx = PipelineFixture.routableStep();
        ctx.mopData = PipelineFixture.census();
        ctx.staticModel = PipelineFixture.staticCensus();
        ctx.graph = PipelineFixture.emptyGraph();
        ctx.actionBufferSize = 2;
        return ctx;
    }

    private static final List<String> WHOLE_ROSTER = Arrays.asList(
            "Budget", "Restart", "LlmNewState", "LlmStagnation", "LlmCoin", "MopLauncher",
            "ComponentTrigger", "MopCoin", "SataChain");

    @Test
    public void aStepEveryMechanismQualifiesForIsDecidedByTheLlm() throws Exception {
        ModelAction answer = new ModelAction(null, ActionType.MODEL_CLICK);
        PipelineFixture.StubLlm llm = new PipelineFixture.StubLlm(true, answer);
        PipelineFixture.FakeAgent agent =
                new PipelineFixture.FakeAgent(llm, PipelineFixture.chainAction());
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullPlan(1), agent);

        Action decided = pipeline.decide(contendedStep());

        assertEquals("all four contenders are in the roster", WHOLE_ROSTER, pipeline.stageNames());
        assertSame("the first stage to select decides the step", answer, decided);
        assertEquals(ModelAction.DecisionSource.LLM, answer.getDecisionSource());
        assertEquals(ModelAction.PickChannel.LLM, answer.getPickChannel());
        assertEquals("the two LLM stages below the one that accepted were never asked either",
                1, llm.selectCalls);
        assertEquals("no component was triggered", 0, agent.triggerCalls);
        assertEquals("no SATA rung was evaluated", 0, agent.chainCalls);
        assertEquals("the buffer was abandoned by the LLM, without weakening (INV-DP-15)",
                Collections.singletonList("llm"), agent.abandoned);
    }

    @Test
    public void theLauncherWouldHaveFiredOnThatStep() throws Exception {
        // The same plan, the same step, the model declining: the launcher reaches its firing point
        // and launches. So its absence from the step above is preemption, not an idle launcher.
        PipelineFixture.StubLlm llm = new PipelineFixture.StubLlm(true, null);
        PipelineFixture.FakeAgent agent =
                new PipelineFixture.FakeAgent(llm, PipelineFixture.chainAction());
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullPlan(1), agent);

        Action decided = pipeline.decide(contendedStep());

        assertEquals(ActivityTriggerAction.class, decided.getClass());
        assertEquals(PipelineFixture.TRIGGER_TARGET,
                ((ActivityTriggerAction) decided).getClassName());
        assertEquals("the launcher preempts the component trigger and the chain in its turn",
                0, agent.triggerCalls);
        assertEquals(0, agent.chainCalls);
    }

    @Test
    public void theComponentCoinWouldHaveSpentItselfOnThatStep() throws Exception {
        // The launcher one step short of its cadence, so the fall-through reaches the coin. It fires
        // and the step is still decided by the chain: a side effect acts, it never decides
        // (INV-DP-05).
        PipelineFixture.StubLlm llm = new PipelineFixture.StubLlm(true, null);
        ModelAction fromTheChain = PipelineFixture.chainAction();
        PipelineFixture.FakeAgent agent = new PipelineFixture.FakeAgent(llm, fromTheChain);
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullPlan(2), agent);

        Action decided = pipeline.decide(contendedStep());

        assertEquals("the coin came up and the trigger was dispatched", 1, agent.triggerCalls);
        assertEquals("at the round-robin's first target", 0, agent.lastTriggeredTarget);
        assertSame("and the step was decided below it, by the chain", fromTheChain, decided);
        assertEquals(1, agent.chainCalls);
    }

    /**
     * The global cadence, read within one run (INV-DP-08, design D-A16, D-B11).
     *
     * <p>The cadence counter advances at every step open, so a step an earlier stage decided still
     * counts. With a cadence of two, an LLM-taken first step and a second step that falls through
     * fire the launcher on the second step of the run.
     */
    @Test
    public void aPreemptedStepAdvancesTheLauncherCadence() throws Exception {
        ModelAction answer = new ModelAction(null, ActionType.MODEL_CLICK);
        ModelAction fromTheChain = PipelineFixture.chainAction();
        PipelineFixture.StubLlm llm = new PipelineFixture.StubLlm(true, answer);
        PipelineFixture.FakeAgent agent = new PipelineFixture.FakeAgent(llm, fromTheChain);
        DecisionPipeline pipeline =
                DecisionPipeline.fromSpec(PipelineFixture.llmWithLauncherPlan(2), agent);

        Action preempted = pipeline.decide(launcherStep());
        llm.answersFrom(null); // from here the model declines and the step falls through

        Action first = pipeline.decide(launcherStep());
        Action second = pipeline.decide(launcherStep());

        assertSame("step 1 was decided by the LLM, above the launcher", answer, preempted);
        assertEquals("step 2 reaches the cadence the preempted step helped count, and fires",
                ActivityTriggerAction.class, first.getClass());
        assertSame("step 3 is one step into the next cadence: the chain decides", fromTheChain,
                second);
    }

    /** The contended step without the component trigger, which this plan does not assemble. */
    private static FakeStepContext launcherStep() throws Exception {
        FakeStepContext ctx = PipelineFixture.routableStep();
        ctx.mopData = PipelineFixture.census();
        ctx.staticModel = PipelineFixture.staticCensus();
        ctx.graph = PipelineFixture.emptyGraph();
        return ctx;
    }
}
