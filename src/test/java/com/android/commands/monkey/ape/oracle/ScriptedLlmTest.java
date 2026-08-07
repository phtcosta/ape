package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.Config;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * rearch-01 task 3.3 — the scripted LLM's own tests. They pin the three things an LLM-preset golden
 * rests on: that a verdict is deterministic, that the real pipeline is unreachable, and that a
 * scenario which lies about what the agent will do fails instead of drifting.
 *
 * <p><b>What is no longer asserted here, and where it went.</b> The stub used to honor the
 * agent-side conjuncts itself — a scripted stagnation route yielded to a burnt shot, a scripted
 * new-state route yielded to a revisit — because it stood in front of them. Those conjuncts now live
 * inside the stages that own them, so the stub sits behind them and cannot observe them at all; they
 * are pinned in {@code LlmStagnationStageTest} and {@code LlmNewStateStageTest}, on the objects that
 * evaluate them. What is left here is the verdict and the bookkeeping, which is all this class ever
 * really owned.
 */
public class ScriptedLlmTest {

    private static final long SEED = 42L;
    private static final String W0 = "//*[@resource-id='w0']";
    private static final String W1 = "//*[@resource-id='w1']";

    /** One screen, three widgets, the middle one already visited. */
    private static ScenarioScript scriptWith(ScenarioScript.Step... steps) {
        return ScenarioScript.named("llm-fixture", SEED)
                .screens(ScenarioScript.screen("main", "com.example.MainActivity",
                        ScenarioScript.widget(W0),
                        ScenarioScript.widget(W1, ScenarioScript.DEFAULT_PRIORITY, true, 0.0F),
                        ScenarioScript.widget("//*[@resource-id='w2']")))
                .steps(steps)
                .build();
    }

    private static List<ModelAction> offeredActions(ScenarioScript script) throws Exception {
        State state = OracleScaffold.buildState(script.getEntryScreen());
        return state.getActions();
    }

    /**
     * Runs one hook the way its stage would: consult the gate, and call the engine only if it
     * answered true. The stage's own conjunct is not modelled here — by the time the gate is reached
     * it has already held, which is exactly what makes the gate the seam.
     */
    private static ModelAction route(ScriptedLlm llm, ScenarioScript script, String hook)
            throws Exception {
        llm.beginStep(0);
        if (!llm.gateFor(hook).getAsBoolean()) {
            return null;
        }
        return llm.engine().selectAction(null, null, offeredActions(script), null, null, hook);
    }

    // ---- verdicts --------------------------------------------------------------------------

    @Test
    public void acceptReturnsTheDeterministicMemberOfTheOfferedActions() throws Exception {
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false,
                        ScriptedLlm.FIRST_UNVISITED_TARGETED)));

        ScriptedLlm llm = new ScriptedLlm(script);
        List<ModelAction> actions = offeredActions(script);
        llm.beginStep(0);
        assertTrue(llm.gateFor(ScriptedLlm.NEW_STATE).getAsBoolean());
        ModelAction first =
                llm.engine().selectAction(null, null, actions, null, null, "new-state");

        assertNotNull(first);
        assertEquals(W0, first.getTarget().toXPath());
        assertEquals(ScriptedLlm.Provenance.ACCEPTED, llm.getProvenance());
        // Same offered list, same answer — the selector is a function of the list, not of a draw.
        llm.beginStep(0);
        llm.gateFor(ScriptedLlm.NEW_STATE).getAsBoolean();
        assertSame(first, llm.engine().selectAction(null, null, actions, null, null, "new-state"));
    }

    @Test
    public void anXPathSelectorNamesTheActionExactly() throws Exception {
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false, ScriptedLlm.XPATH_PREFIX + W1)));

        ModelAction picked =
                route(new ScriptedLlm(script), script, ScriptedLlm.NEW_STATE);

        assertNotNull(picked);
        // W1 is the visited widget: an xpath selector overrides the unvisited-first convention.
        assertEquals(W1, picked.getTarget().toXPath());
    }

    @Test
    public void declineAndTimeoutFallThroughAndDifferOnlyInProvenance() throws Exception {
        ScenarioScript declineScript = scriptWith(
                ScenarioScript.step(true, 0, ScenarioScript.decline(true, false, false)));
        ScriptedLlm declining = new ScriptedLlm(declineScript);
        assertNull(route(declining, declineScript, ScriptedLlm.NEW_STATE));
        assertEquals(ScriptedLlm.Provenance.DECLINED, declining.getProvenance());

        ScenarioScript timeoutScript = scriptWith(
                ScenarioScript.step(true, 0, ScenarioScript.timeout(true, false, false)));
        ScriptedLlm timing = new ScriptedLlm(timeoutScript);
        assertNull(route(timing, timeoutScript, ScriptedLlm.NEW_STATE));
        assertEquals(ScriptedLlm.Provenance.TIMEOUT, timing.getProvenance());
    }

    @Test
    public void aGateAnswersOnlyForTheHookItWasHandedTo() throws Exception {
        // The verdict is per-hook, and the gate is what carries which hook is asking. A hook-blind
        // seam could not express a step whose script routes one of the three and not the others.
        ScenarioScript script = scriptWith(ScenarioScript.step(false, 50,
                ScenarioScript.decline(false, true, false)));
        ScriptedLlm llm = new ScriptedLlm(script);
        llm.beginStep(0);

        assertTrue(llm.gateFor(ScriptedLlm.STAGNATION).getAsBoolean());
        assertFalse(llm.gateFor(ScriptedLlm.NEW_STATE).getAsBoolean());
        assertFalse(llm.gateFor(ScriptedLlm.RANDOM).getAsBoolean());
    }

    // ---- INV-ORA-03: the real pipeline never runs ---------------------------------------------

    @Test
    public void theRealPipelineNeverRuns() throws Exception {
        // Jar default: no server is configured, and none is needed — the stub replaces the
        // pipeline outright rather than short-circuiting inside it.
        assertNull("the harness runs on jar defaults, with no LLM endpoint", Config.llmUrl);

        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false, ScriptedLlm.FIRST_TARGETED)));
        ScriptedLlm llm = new ScriptedLlm(script);
        assertNotNull(route(llm, script, ScriptedLlm.NEW_STATE));

        // The scripted engine overrides selectAction outright, so it composes none of the units the
        // real one calls. Every one of them being null is what makes the guarantee structural: there
        // is no screenshot to take, no socket to open, no breaker to advance and no clock to read,
        // whatever a future edit to the pipeline adds (INV-ORA-03).
        LlmEngine engine = llm.engine();
        for (Field field : LlmEngine.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            assertNull("the scripted engine holds no " + field.getName(), field.get(engine));
        }
    }

    // ---- task 3.2: the script's claims are checked --------------------------------------------

    @Test
    public void scriptExhaustionFailsLoudly() {
        ScenarioScript script = scriptWith(ScenarioScript.step(false, 0));
        ScriptedLlm llm = new ScriptedLlm(script);
        try {
            llm.beginStep(1);
            fail("stepping past the last scripted step must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("exhausted"));
        }
    }

    @Test
    public void aStepThatReachedNoGateAtAllFailsLoudly() {
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false, ScriptedLlm.FIRST_TARGETED)));
        ScriptedLlm llm = new ScriptedLlm(script);

        llm.beginStep(0);
        // No hook reached its gate — what happens when the shared precondition (a non-empty action
        // buffer, or a state with too few actions) short-circuits ahead of all three at once. That
        // is the authoring bug this check exists for, and it is the only one it can still see.
        try {
            llm.finishStep();
            fail("a scripted step where no hook was consulted must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("no hook was consulted"));
            assertTrue(expected.getMessage().contains("step 0"));
        }
    }

    @Test
    public void aDeclaredHookWhoseStageConditionNeverHeldDoesNotFailTheStep() throws Exception {
        // The criterion is the block, not the hook. This step scripts the stagnation hook while the
        // probabilistic one is the only one whose stage-side condition holds; stagnation therefore
        // never reaches its gate. That is not a scenario bug — before the trigger predicates moved
        // into the stages, such a hook did not route either, because the stub's own predicates ANDed
        // with the same agent-side arguments and answered false without complaint.
        ScenarioScript script = scriptWith(ScenarioScript.step(false, 50,
                ScenarioScript.decline(false, true, false)));
        ScriptedLlm llm = new ScriptedLlm(script);

        llm.beginStep(0);
        assertFalse(llm.gateFor(ScriptedLlm.RANDOM).getAsBoolean());
        llm.finishStep();
    }

    @Test
    public void anAcceptedHookExemptsTheHooksBelowIt() throws Exception {
        // Both hooks are scripted, but the pipeline returns on the first accept, so the stagnation
        // hook is correctly never reached — that is preemption, not an unconsumed entry.
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 50,
                ScenarioScript.accept(true, true, false, ScriptedLlm.FIRST_TARGETED)));
        ScriptedLlm llm = new ScriptedLlm(script);

        assertNotNull(route(llm, script, ScriptedLlm.NEW_STATE));
        llm.finishStep();
    }

    @Test
    public void anUnmatchedSelectorFailsLoudly() throws Exception {
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false,
                        ScriptedLlm.XPATH_PREFIX + "//*[@resource-id='absent']")));
        try {
            route(new ScriptedLlm(script), script, ScriptedLlm.NEW_STATE);
            fail("a selector matching nothing is a scenario bug and must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("matches none"));
        }
    }

    @Test
    public void anUnscriptedStepThatRoutesFailsLoudly() throws Exception {
        // The mirror of the check above: a step with no LLM entry that nonetheless reaches the
        // engine means the agent routed where the scenario said it would not.
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0));
        ScriptedLlm llm = new ScriptedLlm(script);
        llm.beginStep(0);
        try {
            llm.engine().selectAction(null, null, offeredActions(script), null, null, "new-state");
            fail("an unscripted step the agent routed must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("declares no LLM entry"));
        }
    }

    // ---- the stub plugged into the real pipeline ----------------------------------------------

    @Test
    public void theLadderReturnsTheScriptedPickAttributedToTheLlm() throws Exception {
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false, ScriptedLlm.XPATH_PREFIX + W1)));
        ScriptedLlm llm = new ScriptedLlm(script);
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Preset.LLM, script, llm);
        OracleScaffold.setField(agent, "_isNewState", true);
        llm.beginStep(0);

        Action selected = agent.ladder();
        llm.finishStep();

        assertTrue(selected instanceof ModelAction);
        ModelAction picked = (ModelAction) selected;
        assertEquals(W1, picked.getTarget().toXPath());
        // The accepting LLM stage (LlmGate.accept) stamps the provenance the golden records.
        assertEquals(ModelAction.DecisionSource.LLM, picked.getDecisionSource());
        assertEquals(ModelAction.PickChannel.LLM, picked.getPickChannel());
        assertEquals(ScriptedLlm.Provenance.ACCEPTED, llm.getProvenance());
    }
}
