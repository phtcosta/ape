package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.Config;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * rearch-01 task 3.3 — the scripted router's own tests. They pin the three things an LLM-preset
 * golden rests on: that a verdict is deterministic, that the agent-side contracts still bind, and
 * that a scenario which lies about what the agent will do fails instead of drifting.
 */
public class ScriptedLlmRouterTest {

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

    /** Runs the step the way the ladder would: predicate, then the call it gates. */
    private static ModelAction routeNewState(ScriptedLlmRouter router, ScenarioScript script,
                                             boolean isNewState) throws Exception {
        router.beginStep(0);
        if (!router.shouldRouteNewState(isNewState)) {
            return null;
        }
        return router.selectAction(null, null, offeredActions(script), null, null, "new-state", 1);
    }

    // ---- verdicts --------------------------------------------------------------------------

    @Test
    public void acceptReturnsTheDeterministicMemberOfTheOfferedActions() throws Exception {
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false,
                        ScriptedLlmRouter.FIRST_UNVISITED_TARGETED)));

        ScriptedLlmRouter router = new ScriptedLlmRouter(script);
        List<ModelAction> actions = offeredActions(script);
        router.beginStep(0);
        assertTrue(router.shouldRouteNewState(true));
        ModelAction first = router.selectAction(null, null, actions, null, null, "new-state", 1);

        assertNotNull(first);
        assertEquals(W0, first.getTarget().toXPath());
        assertEquals(ScriptedLlmRouter.Provenance.ACCEPTED, router.getProvenance());
        // Same offered list, same answer — the selector is a function of the list, not of a draw.
        router.beginStep(0);
        router.shouldRouteNewState(true);
        assertSame(first, router.selectAction(null, null, actions, null, null, "new-state", 1));
    }

    @Test
    public void anXPathSelectorNamesTheActionExactly() throws Exception {
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false, ScriptedLlmRouter.XPATH_PREFIX + W1)));

        ModelAction picked = routeNewState(new ScriptedLlmRouter(script), script, true);

        assertNotNull(picked);
        // W1 is the visited widget: an xpath selector overrides the unvisited-first convention.
        assertEquals(W1, picked.getTarget().toXPath());
    }

    @Test
    public void declineAndTimeoutFallThroughAndDifferOnlyInProvenance() throws Exception {
        ScenarioScript declineScript = scriptWith(
                ScenarioScript.step(true, 0, ScenarioScript.decline(true, false, false)));
        ScriptedLlmRouter declining = new ScriptedLlmRouter(declineScript);
        assertNull(routeNewState(declining, declineScript, true));
        assertEquals(ScriptedLlmRouter.Provenance.DECLINED, declining.getProvenance());

        ScenarioScript timeoutScript = scriptWith(
                ScenarioScript.step(true, 0, ScenarioScript.timeout(true, false, false)));
        ScriptedLlmRouter timing = new ScriptedLlmRouter(timeoutScript);
        assertNull(routeNewState(timing, timeoutScript, true));
        assertEquals(ScriptedLlmRouter.Provenance.TIMEOUT, timing.getProvenance());
    }

    // ---- the agent-side contracts still bind -------------------------------------------------

    @Test
    public void firedThisEpisodeSuppressesAScriptedStagnationRoute() throws Exception {
        ScenarioScript script = scriptWith(ScenarioScript.step(false, 50,
                ScenarioScript.accept(false, true, false, ScriptedLlmRouter.FIRST_TARGETED)));
        ScriptedLlmRouter router = new ScriptedLlmRouter(script);
        router.beginStep(0);

        assertTrue("armed episode routes", router.shouldRouteStagnation(50, false));
        assertTrue("a burnt shot does not, whatever the script says",
                !router.shouldRouteStagnation(50, true));
        // The predicate ran, so the script's claim came true; nothing to fail on.
        router.finishStep();
    }

    @Test
    public void isNewStateFalseSuppressesAScriptedNewStateRoute() throws Exception {
        ScenarioScript script = scriptWith(ScenarioScript.step(false, 0,
                ScenarioScript.accept(true, false, false, ScriptedLlmRouter.FIRST_TARGETED)));
        ScriptedLlmRouter router = new ScriptedLlmRouter(script);

        assertNull(routeNewState(router, script, false));
        assertEquals(ScriptedLlmRouter.Provenance.NOT_ROUTED, router.getProvenance());
        router.finishStep();
    }

    // ---- INV-ORA-03: the real pipeline never runs ---------------------------------------------

    @Test
    public void theRealPipelineNeverRuns() throws Exception {
        // Jar default: no server is configured, and none is needed — the stub replaces the
        // pipeline outright rather than short-circuiting inside it.
        assertNull("the harness runs on jar defaults, with no LLM endpoint", Config.llmUrl);

        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false, ScriptedLlmRouter.FIRST_TARGETED)));
        ScriptedLlmRouter router = new ScriptedLlmRouter(script);
        routeNewState(router, script, true);

        // The real selectAction increments totalCalls on its first line; 0 proves it never ran,
        // so no screenshot, no HTTP, and no breaker transition was recorded.
        assertEquals(0, router.getCallCount());
        assertEquals(0, router.getFailureCount());
        assertEquals(0, router.getBreakerTrips());
    }

    // ---- task 3.2: the script's claims are checked --------------------------------------------

    @Test
    public void scriptExhaustionFailsLoudly() {
        ScenarioScript script = scriptWith(ScenarioScript.step(false, 0));
        ScriptedLlmRouter router = new ScriptedLlmRouter(script);
        try {
            router.beginStep(1);
            fail("stepping past the last scripted step must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("exhausted"));
        }
    }

    @Test
    public void unconsumedMandatoryEntryFailsLoudlyNamingTheHook() {
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false, ScriptedLlmRouter.FIRST_TARGETED)));
        ScriptedLlmRouter router = new ScriptedLlmRouter(script);

        router.beginStep(0);
        // The agent never consults the hook — what happens when the ladder's precondition
        // (a non-empty action buffer, or a state with too few actions) short-circuits ahead of it.
        try {
            router.finishStep();
            fail("a scripted consultation the agent never performed must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("new-state"));
            assertTrue(expected.getMessage().contains("step 0"));
        }
    }

    @Test
    public void anAcceptedHookExemptsTheHooksBelowIt() throws Exception {
        // Both hooks are scripted, but the ladder returns on the first accept, so the stagnation
        // hook is correctly never reached — that is preemption, not an unconsumed entry.
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 50,
                ScenarioScript.accept(true, true, false, ScriptedLlmRouter.FIRST_TARGETED)));
        ScriptedLlmRouter router = new ScriptedLlmRouter(script);

        assertNotNull(routeNewState(router, script, true));
        router.finishStep();
    }

    @Test
    public void anUnmatchedSelectorFailsLoudly() throws Exception {
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false,
                        ScriptedLlmRouter.XPATH_PREFIX + "//*[@resource-id='absent']")));
        try {
            routeNewState(new ScriptedLlmRouter(script), script, true);
            fail("a selector matching nothing is a scenario bug and must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("matches none"));
        }
    }

    // ---- the stub plugged into the real ladder ------------------------------------------------

    @Test
    public void theLadderReturnsTheScriptedPickAttributedToTheLlm() throws Exception {
        ScenarioScript script = scriptWith(ScenarioScript.step(true, 0,
                ScenarioScript.accept(true, false, false, ScriptedLlmRouter.XPATH_PREFIX + W1)));
        ScriptedLlmRouter router = new ScriptedLlmRouter(script);
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Preset.LLM, script, router);
        OracleScaffold.setField(agent, "_isNewState", true);
        router.beginStep(0);

        Action selected = agent.ladder();
        router.finishStep();

        assertTrue(selected instanceof ModelAction);
        ModelAction picked = (ModelAction) selected;
        assertEquals(W1, picked.getTarget().toXPath());
        // The accepting LLM stage (LlmGate.accept) stamps the provenance the golden records.
        assertEquals(ModelAction.DecisionSource.LLM, picked.getDecisionSource());
        assertEquals(ModelAction.PickChannel.LLM, picked.getPickChannel());
        assertEquals(ScriptedLlmRouter.Provenance.ACCEPTED, router.getProvenance());
    }
}
