package com.android.commands.monkey.ape.agent.pipeline;

import java.util.Arrays;

import org.junit.Test;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActivityTriggerAction;
import com.android.commands.monkey.ape.model.ModelAction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

/**
 * LLM fallback is the rest of the pipeline, and nothing else (INV-DP-11).
 *
 * <p>The stage tests already pin that each LLM stage answers {@code Continue} when the model
 * declines or its trigger says no. What they cannot show, one stage at a time, is the property the
 * word "fallback" actually names: that the step is still decided, by whatever the plan put after
 * the LLM stages, carrying that stage's own attribution. That is asserted here over an assembled
 * pipeline, because "the remainder" is a fact about the roster rather than about any one stage.
 *
 * <p><b>Why the remainder is asserted twice, on two plans.</b> Under the {@code llm} arm the
 * remainder is the SATA chain, so the fallback is attributed {@code SATA}. Add the MOP substrate and
 * turn the activity trigger on and the launcher sits between the LLM stages and the chain, so the
 * same declined step is decided by the launcher and attributed {@code Component}. A test that only
 * ever saw {@code SATA} would pass just as well against a hardcoded chain — the second plan is what
 * makes "the configured remainder" mean something.
 *
 * <p><b>Decline and timeout are one case here, deliberately.</b> At the stage seam a timeout is a
 * null answer, exactly like a decline, a no-match or a banned pair; the difference between them is
 * provenance, recorded a layer up and pinned by the {@code llm} golden, whose steps 1 and 2 are a
 * decline and a timeout falling through to {@code SATA}. Writing two identical stage-level tests
 * would assert the same code path twice under two names.
 *
 * <p><b>Breaker denial arrives as a declined trigger.</b> The breaker is the last conjunct of every
 * hook, so from below it a breaker-open step is indistinguishable from a step whose own condition
 * failed — which is the whole point of INV-DP-11 having one path. The breaker's own contract, that
 * its OPEN line is logged once per open episode, is asserted where the latch lives, in
 * {@code LlmClientTest}.
 */
public class LlmStructuralFallbackTest {

    @Test
    public void anEngineThatAnswersNothingLeavesTheStepToTheChain() throws Exception {
        PipelineFixture.StubLlm llm = new PipelineFixture.StubLlm(true, null);
        ModelAction fromTheChain = PipelineFixture.chainAction();
        PipelineFixture.FakeAgent agent = new PipelineFixture.FakeAgent(llm, fromTheChain);
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(PipelineFixture.llmPlan(), agent);

        Action decided = pipeline.decide(PipelineFixture.routableStep());

        assertSame("the remainder decides the step, and nothing retries", fromTheChain, decided);
        assertEquals("all three routing modes asked and all three were answered with nothing",
                3, llm.selectCalls);
        assertEquals(1, agent.chainCalls);
        assertEquals(ModelAction.DecisionSource.SATA, fromTheChain.getDecisionSource());
        assertNotEquals("a step the LLM did not decide must not be attributed to it",
                ModelAction.DecisionSource.LLM, fromTheChain.getDecisionSource());
    }

    @Test
    public void aDeniedTriggerLeavesTheStepToTheChainWithoutConsultingTheModel() throws Exception {
        // What a breaker-open step looks like from a stage: the last conjunct says no. So does an
        // already-seen state, a spent stagnation shot, or a coin that came up short — one path, by
        // construction. The plan puts the other three conjuncts out of the way, so the breaker is
        // the only thing declining here.
        PipelineFixture.StubLlm llm =
                new PipelineFixture.StubLlm(false, PipelineFixture.chainAction());
        ModelAction fromTheChain = PipelineFixture.chainAction();
        PipelineFixture.FakeAgent agent = new PipelineFixture.FakeAgent(llm, fromTheChain);
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(PipelineFixture.llmPlan(), agent);

        Action decided = pipeline.decide(PipelineFixture.routableStep());

        assertSame(fromTheChain, decided);
        assertEquals("a denied trigger must not cost a round trip", 0, llm.selectCalls);
        assertEquals(ModelAction.DecisionSource.SATA, fromTheChain.getDecisionSource());
    }

    @Test
    public void theRemainderIsWhateverThePlanPutAfterTheLlmStages() throws Exception {
        PipelineFixture.StubLlm llm = new PipelineFixture.StubLlm(true, null);
        PipelineFixture.FakeAgent agent =
                new PipelineFixture.FakeAgent(llm, PipelineFixture.chainAction());
        DecisionPipeline pipeline =
                DecisionPipeline.fromSpec(PipelineFixture.llmWithLauncherPlan(1), agent);
        FakeStepContext ctx = PipelineFixture.routableStep();
        ctx.mopData = PipelineFixture.census();
        ctx.graph = PipelineFixture.emptyGraph();

        Action decided = pipeline.decide(ctx);

        assertEquals(Arrays.asList("Budget", "LlmNewState", "LlmStagnation", "LlmRandom",
                "MopLauncher", "SataChain"), pipeline.stageNames());
        assertEquals("the declined step falls through to the launcher, not past it to the chain",
                ActivityTriggerAction.class, decided.getClass());
        assertEquals(PipelineFixture.TRIGGER_TARGET,
                ((ActivityTriggerAction) decided).getClassName());
        assertEquals("the chain was never reached", 0, agent.chainCalls);
    }
}
