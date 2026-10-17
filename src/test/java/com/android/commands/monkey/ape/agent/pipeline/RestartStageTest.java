package com.android.commands.monkey.ape.agent.pipeline;

import java.util.Arrays;

import org.junit.Test;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * The restart guard (task 7.9.5; decision-pipeline "Restart Guard Stage", INV-DP-14).
 *
 * <p>The agent's seam has a side effect: {@code restartDue()} reads and clears the
 * {@code disableRestart} latch, so the stage consults it exactly once per step, and
 * {@code restartAction()} — which throws when nothing is due, clears the buffer and writes
 * {@code dec.restart} itself — is asked only when a restart is due.
 */
public class RestartStageTest {

    /** The agent's restart seam, scripted and counted. */
    private static final class Agent {
        boolean due;
        int dueCalls;
        int actionCalls;
        final ModelAction start = new ModelAction(null, ActionType.MODEL_BACK);

        boolean restartDue() {
            dueCalls++;
            return due;
        }

        Action restartAction() {
            actionCalls++;
            if (!due) {
                throw new IllegalStateException("no restart is due");
            }
            return start;
        }
    }

    @Test
    public void theNameIsItsCandidateName() {
        Agent agent = new Agent();
        assertEquals(DecisionPipeline.Candidate.RESTART.stageName(),
                new RestartStage(agent::restartDue, agent::restartAction).name());
    }

    @Test
    public void aDueRestartSelectsTheStartActionOwnedByRestart() {
        Agent agent = new Agent();
        agent.due = true;

        StageResult result = new RestartStage(agent::restartDue, agent::restartAction)
                .decide(new FakeStepContext());

        assertEquals(StageResult.Kind.SELECT, result.kind());
        assertSame(agent.start, result.action());
        assertEquals("restart", result.decisionSource());
        assertEquals(ModelAction.DecisionSource.RESTART, agent.start.getDecisionSource());
        assertEquals("a guard step carries its own channel (D-B14.17)",
                ModelAction.PickChannel.RESTART, agent.start.getPickChannel());
        assertEquals(1, agent.dueCalls);
        assertEquals(1, agent.actionCalls);
    }

    @Test
    public void noRestartDueContinuesWithoutAskingForTheAction() {
        Agent agent = new Agent();

        StageResult result = new RestartStage(agent::restartDue, agent::restartAction)
                .decide(new FakeStepContext());

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("the latch-clearing consultation is made once per step", 1, agent.dueCalls);
        assertEquals(0, agent.actionCalls);
    }

    @Test
    public void aRestartIsDecidedBeforeTheLlm() throws Exception {
        // Scenario "graph-stability restart is decided before the LLM": a new state, the LLM
        // mounted, and a restart due: the LLM is never evaluated and dec.own.i is restart.
        Agent agent = new Agent();
        agent.due = true;
        LlmCoinStageTest.StubEngine engine = new LlmCoinStageTest.StubEngine(
                new ModelAction(null, ActionType.MODEL_CLICK));
        StepRouting routing = new StepRouting(true, false, false);
        LlmCall llm = new LlmCall(engine, Arrays.<LlmGate>asList(), routing, a -> { }, o -> { },
                () -> null);
        DecisionPipeline pipeline = new DecisionPipeline(Arrays.<DecisionStage>asList(
                new RestartStage(agent::restartDue, agent::restartAction),
                new LlmNewStateStage(llm)), routing);
        FakeStepContext ctx = PipelineFixture.routableStep();

        Action decided = pipeline.decide(ctx);

        assertSame(agent.start, decided);
        assertEquals(0, engine.selectCalls);
        assertEquals("restart", ctx.recorder.lastOwner().i);
    }
}
