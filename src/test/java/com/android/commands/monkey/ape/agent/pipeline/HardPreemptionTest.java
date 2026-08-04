package com.android.commands.monkey.ape.agent.pipeline;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

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
     * Every stage assembled: the budget gate, the three LLM modes, the launcher at the cadence the
     * caller names, a component coin that always comes up, and the terminal chain.
     */
    private static RunSpec fullPlan(int cadence) {
        return PipelineFixture.preset(Presets.LLM,
                "ape.llmUrl", PipelineFixture.LLM_URL,
                "ape.mopDataPath", TestRunSpecs.MOP_PATH,
                "ape.activityTriggerEnabled", "true",
                "ape.activityTriggerStagnationStep", Integer.toString(cadence),
                "ape.componentPercentage", "1.0");
    }

    /** The step all four mechanisms qualify for: routable, budgeted, with a census and a graph. */
    private static FakeStepContext contendedStep() throws Exception {
        FakeStepContext ctx = PipelineFixture.routableStep();
        ctx.mopData = PipelineFixture.census();
        ctx.graph = PipelineFixture.emptyGraph();
        ctx.random = new Random(7L); // the component coin's stream; at rate 1.0 any draw fires
        return ctx;
    }

    private static final List<String> WHOLE_ROSTER = Arrays.asList(
            "Budget", "LlmNewState", "LlmStagnation", "LlmRandom", "MopLauncher",
            "ComponentTrigger", "SataChain");

    @Test
    public void aStepEveryMechanismQualifiesForIsDecidedByTheLlm() throws Exception {
        ModelAction answer = new ModelAction(null, ActionType.MODEL_CLICK);
        PipelineFixture.StubRouter router = new PipelineFixture.StubRouter(true, answer);
        PipelineFixture.FakeAgent agent =
                new PipelineFixture.FakeAgent(router, PipelineFixture.chainAction());
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullPlan(1), agent);

        Action decided = pipeline.decide(contendedStep());

        assertEquals("all four contenders are in the roster", WHOLE_ROSTER, pipeline.stageNames());
        assertSame("the first stage to select decides the step", answer, decided);
        assertEquals(ModelAction.DecisionSource.LLM, answer.getDecisionSource());
        assertEquals(ModelAction.PickChannel.LLM, answer.getPickChannel());
        assertEquals("the two LLM stages below the one that accepted were never asked either",
                1, router.selectCalls);
        assertEquals("no component was triggered", 0, agent.triggerCalls);
        assertEquals("no SATA rung was evaluated", 0, agent.chainCalls);
    }

    @Test
    public void theLauncherWouldHaveFiredOnThatStep() throws Exception {
        // The same plan, the same step, the model declining: the launcher reaches its firing point
        // and launches. So its absence from the step above is preemption, not an idle launcher.
        PipelineFixture.StubRouter router = new PipelineFixture.StubRouter(true, null);
        PipelineFixture.FakeAgent agent =
                new PipelineFixture.FakeAgent(router, PipelineFixture.chainAction());
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
        PipelineFixture.StubRouter router = new PipelineFixture.StubRouter(true, null);
        ModelAction fromTheChain = PipelineFixture.chainAction();
        PipelineFixture.FakeAgent agent = new PipelineFixture.FakeAgent(router, fromTheChain);
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(fullPlan(2), agent);

        Action decided = pipeline.decide(contendedStep());

        assertEquals("the coin came up and the trigger was dispatched", 1, agent.triggerCalls);
        assertEquals("at the round-robin's first target", 0, agent.lastTriggeredTarget);
        assertSame("and the step was decided below it, by the chain", fromTheChain, decided);
        assertEquals(1, agent.chainCalls);
    }
}
