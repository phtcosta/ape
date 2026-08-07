package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.BadStateException;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActivityNode;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.Model;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * rearch-01 task 5.2 — the driver's own tests. They pin the three properties every golden rests on
 * before a single golden exists: that a run is reproducible (INV-ORA-02), that the record shape is
 * the one the format expects, and that crossing the capture boundary fails loudly rather than
 * quietly producing a shorter sequence.
 *
 * <p>No test here writes or reads a golden file. A driver validated against a golden it produced
 * itself would be circular, which is the failure stage 1 exists to prevent; the first goldens are
 * captured in group 6, by tests that treat this driver as settled.
 */
public class OracleDriverTest {

    private static final long SEED = 42L;
    private static final String ACTIVITY = "com.example.MainActivity";
    private static final String DETAIL_ACTIVITY = "com.example.DetailActivity";
    private static final String W0 = "//*[@resource-id='w0']";
    private static final String W1 = "//*[@resource-id='w1']";
    private static final String D0 = "//*[@resource-id='d0']";

    /** One screen, three unvisited widgets, and as many steps as asked for. */
    private static ScenarioScript singleScreen(int steps) {
        ScenarioScript.Step[] scripted = new ScenarioScript.Step[steps];
        for (int i = 0; i < steps; i++) {
            scripted[i] = ScenarioScript.step(i == 0, 0);
        }
        return ScenarioScript.named("driver-fixture", SEED)
                .screens(ScenarioScript.screen("main", ACTIVITY,
                        ScenarioScript.widget(W0),
                        ScenarioScript.widget(W1),
                        ScenarioScript.widget("//*[@resource-id='w2']")))
                .steps(scripted)
                .build();
    }

    private static Graph graphOf(OracleSataAgent agent) throws Exception {
        return ((Model) OracleScaffold.getField(agent, "model")).getGraph();
    }

    // ---- the run's shape ---------------------------------------------------------------------

    @Test
    public void aRunRecordsOneOrderedRecordPerScriptedStep() throws Exception {
        ScenarioScript script = singleScreen(4);
        OracleSataAgent agent = OracleScaffold.newAgent(OracleScaffold.Preset.APERV, script, null);

        List<DecisionRecord> records = OracleDriver.run(agent, script);

        assertEquals(4, records.size());
        for (int i = 0; i < records.size(); i++) {
            DecisionRecord record = records.get(i);
            assertEquals("step indices are the scenario's, in order", i, record.getStep());
            assertNotNull("every record names the type of what was selected", record.getActionType());
        }
    }

    @Test
    public void aRevisitedScreenIsTheSameStateAndKeepsItsMarks() throws Exception {
        // Finding 5.1-b: the driver caches the State per screen because rebuilding it would mint a
        // second StateKey for one screen (the harness's Names compare by identity), and the agent
        // would then meet an explored screen as if it were new.
        ScenarioScript script = singleScreen(4);
        OracleSataAgent agent = OracleScaffold.newAgent(OracleScaffold.Preset.APERV, script, null);

        OracleDriver.run(agent, script);

        Graph graph = graphOf(agent);
        @SuppressWarnings("unchecked")
        Map<StateKey, State> keyToState =
                (Map<StateKey, State>) OracleScaffold.getField(graph, "keyToState");
        assertEquals("four steps on one screen register exactly one state", 1, keyToState.size());
        State state = keyToState.values().iterator().next();
        assertEquals("each step marks the state visited once", 4, state.getVisitedCount());
        ActivityNode node = graph.getActivityNode(ACTIVITY);
        assertNotNull("the activity node is what makes markVisited(State) safe", node);
        assertEquals(4, node.getVisitedCount());
    }

    @Test
    public void theTransitionTableMovesTheRunToTheNextScreen() throws Exception {
        // The LLM preset is what makes the selection of each step known in advance, so the
        // transition under test is the driver's and not a rung's.
        ScenarioScript script = ScenarioScript.named("two-screens", SEED)
                .screens(
                        ScenarioScript.screen("main", ACTIVITY,
                                ScenarioScript.widget(W0), ScenarioScript.widget(W1)),
                        ScenarioScript.screen("detail", DETAIL_ACTIVITY,
                                ScenarioScript.widget(D0),
                                ScenarioScript.widget("//*[@resource-id='d1']")))
                .transition("main", W0, "detail")
                .steps(
                        ScenarioScript.step(true, 0, ScenarioScript.accept(true, false, false,
                                ScriptedLlm.XPATH_PREFIX + W0)),
                        ScenarioScript.step(true, 0, ScenarioScript.accept(true, false, false,
                                ScriptedLlm.XPATH_PREFIX + D0)))
                .build();
        ScriptedLlm llm = new ScriptedLlm(script);
        OracleSataAgent agent = OracleScaffold.newAgent(OracleScaffold.Preset.LLM, script, llm);

        List<DecisionRecord> records = OracleDriver.run(agent, script, llm);

        assertEquals(W0, records.get(0).getTarget());
        // Only a driver that consulted the transition table could offer D0 to the second step: the
        // selector names an xpath the entry screen does not have.
        assertEquals(D0, records.get(1).getTarget());
        assertEquals(DETAIL_ACTIVITY,
                ((State) OracleScaffold.getField(agent, "newState")).getActivity());
    }

    // ---- the record's shape -------------------------------------------------------------------

    @Test
    public void theLlmFieldIsAbsentWithoutAScriptedLlmAndAlwaysPresentWithOne() throws Exception {
        ScenarioScript apervScript = singleScreen(2);
        OracleSataAgent aperv =
                OracleScaffold.newAgent(OracleScaffold.Preset.APERV, apervScript, null);
        for (DecisionRecord record : OracleDriver.run(aperv, apervScript)) {
            assertNull("a preset with no LLM cannot claim not_routed either", record.getLlm());
        }

        // Same scenario, a scripted LLM, and no step that routes: the field is present and says so.
        ScenarioScript llmScript = singleScreen(2);
        ScriptedLlm scriptedLlm = new ScriptedLlm(llmScript);
        OracleSataAgent llm =
                OracleScaffold.newAgent(OracleScaffold.Preset.LLM, llmScript, scriptedLlm);
        for (DecisionRecord record : OracleDriver.run(llm, llmScript, scriptedLlm)) {
            assertEquals(ScriptedLlm.Provenance.NOT_ROUTED.getLabel(), record.getLlm());
        }
    }

    @Test
    public void aModelActionReturnCarriesBothProvenanceFields() throws Exception {
        ScenarioScript script = singleScreen(3);
        OracleSataAgent agent = OracleScaffold.newAgent(OracleScaffold.Preset.APERV, script, null);

        for (DecisionRecord record : OracleDriver.run(agent, script)) {
            // ModelAction.java:91-92 initializes both, and logActionSelected rewrites the source on
            // every branch that does not stamp its own: absence is the launcher's signature, never a
            // "not set yet". These scenarios never fire the launcher (no MopData).
            assertNotNull(record.getDecisionSource());
            assertNotNull("the golden carries the channel's label, not the enum name",
                    record.getPickChannel());
            assertEquals(record.getPickChannel(), record.getPickChannel().toLowerCase());
        }
    }

    // ---- INV-ORA-02: determinism ---------------------------------------------------------------

    @Test
    public void doubleCaptureWithTheSameSeedIsIdentical() throws Exception {
        // Each newAgent re-seeds both streams from the scenario's seed, which is what makes the
        // second run a repetition rather than a continuation.
        ScenarioScript script = singleScreen(6);

        List<DecisionRecord> first = OracleDriver.run(
                OracleScaffold.newAgent(OracleScaffold.Preset.APERV, script, null), script);
        List<DecisionRecord> second = OracleDriver.run(
                OracleScaffold.newAgent(OracleScaffold.Preset.APERV, script, null), script);

        assertEquals("harness nondeterminism is a test failure, not a mystery diff", first, second);
    }

    // ---- the boundary fails loudly (design D6) -------------------------------------------------

    @Test
    public void aDeviceOnlyBranchFailsLoudlyAndIsNotSwallowed() throws Exception {
        // backToActivity is the scenario-level control the scaffold's ledger names: while it is
        // null, selectNewActionBackToActivity returns at SataAgent.java:1267-1269; set, the very
        // next line calls AndroidDevice.getFocusedStack() and leaves the JVM. This is what a
        // scenario that wanders outside the capture boundary looks like, and it must be a redesign
        // signal rather than a swallowed step.
        ScenarioScript script = singleScreen(1);
        OracleSataAgent agent = OracleScaffold.newAgent(OracleScaffold.Preset.APERV, script, null);
        OracleScaffold.setField(agent, "backToActivity", new ActivityNode(ACTIVITY));

        try {
            OracleDriver.run(agent, script);
            fail("a scenario reaching a device-only branch must fail, not produce a record");
        } catch (Throwable boundary) {
            assertTrue("the failure names the android class that does not load off device: "
                            + boundary, describes(boundary, "android"));
            assertTrue("and it is the class-loading failure the spec names, not something the"
                            + " driver invented: " + boundary,
                    boundary instanceof NoClassDefFoundError);
        }
    }

    @Test
    public void badStateExceptionFromTheLadderIsNotSwallowed() throws Exception {
        // Finding 5.2-a: the ladder's own BadStateException site cannot be reached by a scenario in
        // this harness. It lives in handleNullAction (StatefulAgent.java:1695-1701), whose
        // validatedActionFilter calls validateNewAction, which dereferences the null `ape` — so a
        // state with nothing selectable produces a NullPointerException from the filter long before
        // the throw. (The scaffold's rung list calls handleNullAction "reachable"; what is reachable
        // is its first expression, not its body. Recorded in the ledger.) What task 5.2 asks about
        // is the driver's half of the contract — that an exception from the ladder aborts the run —
        // so the ladder here is a stand-in that throws, wired with exactly the fields the driver
        // reads before it calls in.
        ScenarioScript script = singleScreen(3);
        Graph graph = new Graph();
        State state = OracleScaffold.buildState(script.getEntryScreen());
        OracleScaffold.registerInGraph(graph, state);

        ExplodingAgent agent = OracleScaffold.allocate(ExplodingAgent.class);
        OracleScaffold.setField(agent, "model", new Model(graph));
        OracleScaffold.setField(agent, "newState", state);
        OracleScaffold.setField(agent, "timestamp", 1);

        try {
            OracleDriver.run(agent, script);
            fail("a ladder that found no available action must abort the run");
        } catch (BadStateException expected) {
            assertEquals("No available action on the current state", expected.getMessage());
        }
        assertEquals("the run aborted on the first step, it did not continue with the rest",
                1, agent.ladderCalls);
    }

    /** True when {@code failure} or any of its causes names {@code fragment}. */
    private static boolean describes(Throwable failure, String fragment) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** A ladder that always throws — the stand-in of {@link #badStateExceptionFromTheLadderIsNotSwallowed}. */
    static class ExplodingAgent extends OracleSataAgent {

        int ladderCalls;

        @Override
        Action ladder() {
            ladderCalls++;
            throw new BadStateException("No available action on the current state");
        }
    }
}
