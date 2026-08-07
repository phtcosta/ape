package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.agent.pipeline.DecisionPipeline;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.RandomHelper;

import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * rearch-01 task 2.4 — the scaffold's own tests. They pin the three things a golden silently
 * depends on: that a screen materializes into the action set the ladder expects, that a registered
 * state supports the driver's graph bookkeeping (finding 2.1-b), and that each preset wires the
 * field combination design D2 declares.
 *
 * <p>These are harness-integrity tests, not goldens. If one fails, no golden captured afterwards
 * means anything — which is why they run in the same suite.
 */
public class OracleScaffoldTest {

    private static final long SEED = 42L;

    /** The three stages an LLM preset's plan assembles, and a non-LLM preset's plan does not. */
    private static final List<String> LLM_STAGE_NAMES = java.util.Arrays.asList(
            DecisionPipeline.Candidate.LLM_NEW_STATE.stageName(),
            DecisionPipeline.Candidate.LLM_STAGNATION.stageName(),
            DecisionPipeline.Candidate.LLM_RANDOM.stageName());

    private static ScenarioScript oneScreenScript(int widgetCount) {
        ScenarioScript.Widget[] widgets = new ScenarioScript.Widget[widgetCount];
        for (int i = 0; i < widgetCount; i++) {
            widgets[i] = ScenarioScript.widget("//*[@resource-id='w" + i + "']");
        }
        return ScenarioScript.named("scaffold-fixture", SEED)
                .screens(ScenarioScript.screen("main", "com.example.MainActivity", widgets))
                .steps(ScenarioScript.step(false, 0))
                .build();
    }

    private static State newStateOf(OracleSataAgent agent) throws Exception {
        return (State) OracleScaffold.getField(agent, "newState");
    }

    // ---- synthetic states ------------------------------------------------------------------

    @Test
    public void screenMaterializesIntoOneClickPerWidgetPlusNavigation() throws Exception {
        State state = OracleScaffold.buildState(oneScreenScript(3).getEntryScreen());

        assertEquals("one MODEL_CLICK per declared widget", 3, state.targetedActions().size());
        for (ModelAction action : state.targetedActions()) {
            assertEquals(ActionType.MODEL_CLICK, action.getType());
        }
        assertNotNull(state.getBackAction());
        assertNotNull(state.getMenuAction());
        // MODEL_MENU joins the selectable set only under the fork's gate (State.java:58-73).
        int expected = Config.modelMenuEnabled ? 5 : 4;
        assertEquals(expected, state.getActions().size());
        assertEquals("com.example.MainActivity", state.getActivity());
    }

    @Test
    public void actionPreconditionsComeFromTheScript() throws Exception {
        ScenarioScript script = ScenarioScript.named("preconditions", SEED)
                .screens(ScenarioScript.screen("main", "com.example.MainActivity",
                        ScenarioScript.widget("//*[@resource-id='fresh']"),
                        ScenarioScript.widget("//*[@resource-id='done']", 3, true, 1.0F)))
                .steps(ScenarioScript.step(false, 0))
                .build();

        State state = OracleScaffold.buildState(script.getEntryScreen());
        List<ModelAction> targeted = state.targetedActions();
        ModelAction fresh = targeted.get(0);
        ModelAction done = targeted.get(1);

        // Finding 1.2-b: both are preconditions of the ladder, not incidental defaults.
        assertTrue("valid is declared, since ENABLED_VALID excludes the Action default",
                fresh.isValid());
        assertEquals(ScenarioScript.DEFAULT_PRIORITY, fresh.getPriority());
        assertEquals(3, done.getPriority());

        assertFalse(fresh.isVisited());
        assertTrue(done.isVisited());
        // Saturation, not visits, is what lets the chain descend past the EARLY_STAGE rungs for a
        // targeted action (ModelAction.java:154-159).
        assertFalse(fresh.isSaturated());
        assertTrue(done.isSaturated());
    }

    @Test
    public void registeredStateSupportsTheDriverBookkeeping() throws Exception {
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Preset.APERV, oneScreenScript(3), null);
        Graph graph = agent.getGraph();
        State state = newStateOf(agent);

        // Both calls are what design D7 has the driver replay between steps. Without the
        // registration of finding 2.1-b the first NPEs on a missing ActivityNode and the second
        // throws Graph's "action should be added" sanity check.
        graph.markVisited(state, 2);
        graph.markVisited(state.targetedActions().get(0), 2);

        assertTrue(state.isVisited());
        assertTrue(state.targetedActions().get(0).isVisited());
        assertEquals(1, graph.getActivityNodes().length);
        assertEquals("com.example.MainActivity", graph.getActivityNodes()[0].activity);
    }

    // ---- determinism -----------------------------------------------------------------------

    @Test
    public void bothRngStreamsAreSeededFromTheDeclaredSeed() throws Exception {
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Preset.APERV, oneScreenScript(3), null);

        // The static stream, which the EARLY_STAGE roulette draws from...
        assertEquals(new Random(SEED).nextInt(), RandomHelper.nextInt());
        // ...and the agent-side stream, which epsilon-greedy and handleNullAction draw from.
        assertEquals(new Random(SEED).nextInt(), agent.pinned.nextInt());
    }

    // ---- preset injection profiles (design D2) ----------------------------------------------

    @Test
    public void eachPresetWiresItsFieldCombination() throws Exception {
        for (OracleScaffold.Preset preset : OracleScaffold.Preset.values()) {
            ScenarioScript script = oneScreenScript(3);
            ScriptedLlm llm = preset.hasLlm() ? new ScriptedLlm(script) : null;
            OracleSataAgent agent = OracleScaffold.newAgent(preset, script, llm);

            MopData mopData = (MopData) OracleScaffold.getField(agent, "_mopData");
            if (preset.hasMopData()) {
                assertNotNull(preset + " wires MopData", mopData);
                assertEquals(preset + " loads the committed fixture",
                        "br.unb.cic.cryptoapp", mopData.getPackageName());
            } else {
                assertNull(preset + " leaves MopData null", mopData);
            }
            // The LLM axis is a roster fact rather than a field: the run's units reach the ladder as
            // the three stages the plan assembled, so a preset without the axis is one whose
            // pipeline has no LLM stage at all (INV-DP-03). A field read would ask a weaker
            // question — a wired collaborator no stage consults changes nothing.
            DecisionPipeline pipeline =
                    (DecisionPipeline) OracleScaffold.getField(agent, "decisionPipeline");
            for (String llmStage : LLM_STAGE_NAMES) {
                assertEquals(preset + " assembles " + llmStage + " iff it declares the LLM axis",
                        preset.hasLlm(), pipeline.stageNames().contains(llmStage));
            }

            // Common to every profile: what the ladder dereferences unconditionally.
            assertNotNull(preset + " wires the budget tracker",
                    OracleScaffold.getField(agent, "_budgetTracker"));
            assertNotNull(preset + " wires the scoring pipeline",
                    OracleScaffold.getField(agent, "scoringPipeline"));
            assertNotNull(preset + " wires the action buffer",
                    OracleScaffold.getField(agent, "actionBuffer"));
            assertNotNull(preset + " wires the SATA event counters",
                    OracleScaffold.getField(agent, "actionCounters"));
            assertNull(preset + " leaves the GUITree null (finding 2.1-a)",
                    OracleScaffold.getField(agent, "newGUITree"));
            assertNull(preset + " leaves ape null — the whole reason the RNG seam exists",
                    OracleScaffold.getField(agent, "ape"));
        }
    }

    @Test
    public void presetAndScriptedLlmArgumentMustAgree() throws Exception {
        ScenarioScript script = oneScreenScript(3);
        try {
            OracleScaffold.newAgent(OracleScaffold.Preset.APERV, script, new ScriptedLlm(script));
            fail("a scripted LLM supplied to a non-LLM preset must fail loudly");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("APERV"));
        }
        try {
            OracleScaffold.newAgent(OracleScaffold.Preset.LLM, script, null);
            fail("an LLM preset without a scripted LLM must fail loudly");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("LLM"));
        }
    }

    // ---- integration: the wired agent drives the real ladder ---------------------------------

    @Test
    public void theWiredAgentDrivesTheLadder() throws Exception {
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Preset.APERV, oneScreenScript(3), null);

        Action selected = agent.ladder();

        assertNotNull("the ladder returned an action on the plain JVM", selected);
        assertTrue(selected instanceof ModelAction);
    }
}
