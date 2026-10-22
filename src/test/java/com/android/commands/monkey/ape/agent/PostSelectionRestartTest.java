package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.BadStateException;
import com.android.commands.monkey.ape.agent.pipeline.DecisionPipeline;
import com.android.commands.monkey.ape.agent.pipeline.DecisionStage;
import com.android.commands.monkey.ape.agent.pipeline.MopLauncherStage;
import com.android.commands.monkey.ape.llm.PromptFixtures.TestName;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ActivityTriggerAction;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.telemetry.NdjsonSink;
import com.android.commands.monkey.ape.telemetry.RunCounter;
import com.android.commands.monkey.ape.tree.GUITreeAction;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

import android.content.ComponentName;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * decision-pipeline "Post-Selection Restart", "Post-Selection Restart Decision", "Post-Selection
 * Restart Model Effect" and "Post-Selection Restart Record"; event-sink "StepRecord Schema" (design
 * D6). Without {@code APE_FIXES} the pipeline has no Restart stage, and
 * {@code StatefulAgent.updateStateInternal} applies {@code checkRestart} after selection.
 *
 * <p>The agent is {@code Unsafe}-allocated and its device-facing steps are scripted: the new state
 * is handed in, the selection returns a prepared action, and the graph records what it is asked.
 * Everything between selection and the returned action is the production code, and the step's
 * record is written by a real {@link NdjsonSink}.
 */
public class PostSelectionRestartTest {

    private static final String ACT = "com.x.MainActivity";

    private ByteArrayOutputStream captured;
    private NdjsonSink ndjson;
    private int restartCounts;
    private int launchCounts;
    private ScriptedAgent agent;
    private RecordingGraph graph;
    private UICoverageTracker tracker;
    private MopInteractionLedger ledger;

    /** The graph calls the step makes. */
    static class RecordingGraph extends Graph {
        private static final long serialVersionUID = 1L;
        final List<ModelAction> visitedActions = new ArrayList<>();
        final List<ModelAction> weakened = new ArrayList<>();

        @Override
        public void markVisited(ModelAction action, int timestamp) {
            visitedActions.add(action);
        }

        @Override
        public void markVisited(State node, int timestamp) {
        }

        @Override
        public void weakenStateTransition(State source, ModelAction action, State target) {
            weakened.add(action);
        }
    }

    /** A SATA agent whose state building and selection are scripted. */
    static class ScriptedAgent extends SataAgent {
        State scriptedState;
        ModelAction pick;
        /** The step's selection when it is not {@link #pick}: a launch of the MOP launcher. */
        Action nonModelPick;
        boolean disableDuringSelection;
        /** How many state builds throw {@code BadStateException} before one succeeds. */
        int badStates;
        Graph graph;
        int historyCalls;

        ScriptedAgent() {
            super(null, null);
        }

        @Override
        protected void recoverCurrentState() {
        }

        @Override
        protected State buildAndValidateNewState(ComponentName topComp, AccessibilityNodeInfo info) {
            if (badStates > 0) {
                badStates--;
                throw new BadStateException("scripted bad state");
            }
            newState = scriptedState;
            return newState;
        }

        @Override
        protected void preEvolveModel() {
        }

        @Override
        protected void fillLastHistoryResult() {
        }

        @Override
        protected void saveGUI() {
        }

        @Override
        protected void updateGraph() {
        }

        @Override
        protected void checkNonDeterministicTransitions() {
        }

        @Override
        protected void adjustActionsByGUITree() {
        }

        @Override
        public Graph getGraph() {
            return graph;
        }

        @Override
        protected Action selectNewActionNonnull() {
            // What the pipeline and SATA write on the way to a decision.
            RunContext.current().sink().owner("mop", "new_state", Double.NaN, 0.2, "minActions",
                    "gate");
            RunContext.current().sink().strategy("egreedy");
            RunContext.current().sink().draw(1, 3);
            RunContext.current().sink().cfc("mop", true);
            RunContext.current().sink().bufferAbandoned("mop");
            if (disableDuringSelection) {
                disableRestart();
            }
            return nonModelPick != null ? nonModelPick : pick;
        }

        /** The root the retry builds from; the scripted state build does not read it. */
        @Override
        protected AccessibilityNodeInfo retrieveWindowNode(BadStateException e) {
            return null;
        }

        @Override
        protected void recordActionHistory(ModelAction action) {
            historyCalls++;
            super.recordActionHistory(action);
        }

        @Override
        protected Action getStartAction(ActionType actionType) {
            return new Action(actionType);
        }

        Action step(int timestamp) {
            this.timestamp = timestamp;
            return updateStateInternal(null, null);
        }
    }

    /** A model action with a resolved node and tree action, as validation leaves one. */
    static class ResolvedAction extends ModelAction {
        private final GUITreeAction treeAction;

        ResolvedAction(GUITreeNode node, GUITreeAction treeAction) {
            super(null, new TestName("/root/button"), ActionType.MODEL_CLICK);
            resolveAt(1, 0, null, node, new GUITreeNode[] {node});
            setValid(true);
            this.treeAction = treeAction;
        }

        @Override
        public GUITreeAction getResolvedGUITreeAction() {
            return treeAction;
        }
    }

    @After
    public void tearDown() {
        RunContext.resetForTest();
    }

    private void install(RunSpec spec) throws Exception {
        captured = new ByteArrayOutputStream();
        ndjson = new NdjsonSink(new PrintStream(captured, true, "UTF-8"), false);
        EventSink counting = (EventSink) Proxy.newProxyInstance(
                EventSink.class.getClassLoader(), new Class<?>[] {EventSink.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("count") && args[0] == RunCounter.RESTARTS) {
                        restartCounts++;
                    }
                    if (method.getName().equals("count") && args[0] == RunCounter.LAUNCHES) {
                        launchCounts++;
                    }
                    try {
                        return method.invoke(ndjson, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        RunContext.installForTest(spec, counting);
        agent = allocate(ScriptedAgent.class);
        graph = new RecordingGraph();
        agent.graph = graph;
        tracker = new UICoverageTracker();
        ledger = new MopInteractionLedger(1);
        set("actionBuffer", new LinkedList<StateTransition>());
        set("exploration", spec.exploration());
        set("_coverageTracker", tracker);
        set("_mopLedger", ledger);
        set("_actionHistory", new ArrayList<Object>());
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(spec, agent);
        set("decisionPipeline", pipeline);
        set("restartStageMounted", pipeline.mounts(DecisionPipeline.Candidate.RESTART));
        set("activityNames", new HashSet<String>());
        set("nextRestartThreshold", 40);
        agent.scriptedState = state(ACT);
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName("android.widget.Button");
        node.setResourceID("com.x:id/ok");
        agent.pick = new ResolvedAction(node, allocate(GUITreeAction.class));
        agent.pick.setDecisionSource(ModelAction.DecisionSource.MOP);
        agent.pick.setPickChannel(ModelAction.PickChannel.MOP_COIN);
    }

    /** A planned transition in the SATA buffer, whose edge a new episode weakens. */
    private ModelAction plannedTransition() throws Exception {
        ModelAction planned = new ModelAction(null, new TestName("/root/next"), ActionType.MODEL_CLICK);
        StateTransition t = allocate(StateTransition.class);
        setOn(t, StateTransition.class, "source", agent.scriptedState);
        setOn(t, StateTransition.class, "action", planned);
        setOn(t, StateTransition.class, "target", agent.scriptedState);
        @SuppressWarnings("unchecked")
        LinkedList<StateTransition> buffer = (LinkedList<StateTransition>) get("actionBuffer");
        buffer.add(t);
        return planned;
    }

    // --- Scenarios ---------------------------------------------------------------------------

    @Test
    public void theBaseCellHasNoRestartStage() throws Exception {
        install(TestRunSpecs.spec("ape.preset", "base"));
        assertFalse(agent.restartStageMounted());
    }

    @Test
    public void anAddonsPlanMountsTheRestartStageAndSelectionIsNeverReplaced() throws Exception {
        install(TestRunSpecs.spec("ape.addons", "true", "ape.activityBudgetEnabled", "false"));
        assertTrue(agent.restartStageMounted());

        Action executed = agent.step(41); // the periodic threshold has passed

        assertSame("the stage path restarts before selection, never after it", agent.pick, executed);
        assertEquals(0, restartCounts);
    }

    /**
     * Scenarios "a SATA pick replaced by a periodic restart is marked visited and the planned edge
     * weakened", "the replaced action is not recorded by the fork's bookkeeping", "the replaced
     * action of the MOP coin is not executed" and "post-selection restart recorded as the owner".
     */
    @Test
    public void aDuePeriodicRestartReplacesTheSelectedAction() throws Exception {
        install(TestRunSpecs.mopSpec());
        ModelAction planned = plannedTransition();

        Action executed = agent.step(41);

        assertTrue(executed.getType() == ActionType.EVENT_RESTART
                || executed.getType() == ActionType.EVENT_CLEAN_RESTART);
        assertEquals("the pick is marked visited before the restart",
                1, graph.visitedActions.size());
        assertSame(agent.pick, graph.visitedActions.get(0));
        assertEquals("the new episode weakens the planned edge", 1, graph.weakened.size());
        assertSame(planned, graph.weakened.get(0));
        assertEquals(0, agent.actionBufferSize());

        assertFalse("no ledger entry", ledger.tried(ACT, UICoverageTracker.widgetId(agent.pick)));
        assertEquals("no coverage interaction", 0, tracker.getTotalInteractions());
        assertEquals("no action-history entry", 0, agent.historyCalls);
        assertNull("the trace is reset", get("currentAction"));
        assertNull("no decision is buffered for an outcome", get("lastDecisionAction"));
        assertEquals(41, get("lastRestartStep"));

        JSONObject dec = closedRecord().getJSONObject("dec");
        assertEquals("restart", dec.getString("src"));
        assertEquals("restart", dec.getString("ch"));
        assertEquals("periodic", dec.getString("restart"));
        assertEquals(executed.toString(), dec.getString("a"));
        assertEquals(1, restartCounts);
    }

    /** The record of a replaced decision is the record of a Restart-stage step. */
    @Test
    public void theReplacedDecisionLeavesNoFieldOnTheRecord() throws Exception {
        install(TestRunSpecs.mopSpec());

        agent.step(41);

        JSONObject record = closedRecord();
        assertFalse("no outcome is attributed to the replaced action", record.has("out"));
        JSONObject dec = record.getJSONObject("dec");
        for (String member : new String[] {"pri", "mop", "mopx", "mopd", "mops", "hnd", "patched",
                "cfc", "draw", "nav", "strat", "bmc", "buf", "ret"}) {
            assertFalse("dec." + member + " of the replaced decision remains", dec.has(member));
        }
        JSONObject own = dec.getJSONObject("own");
        assertEquals("restart", own.getString("i"));
        assertEquals("the step-open coin draw stays", 0.2, own.getDouble("uM"), 0.0);
        assertFalse(own.has("trg"));
        assertFalse(own.has("g"));
        assertFalse(own.has("fb"));
    }

    /** Scenario "a disabled restart keeps the selected action". */
    @Test
    public void disablingTheRestartDuringSelectionVetoesThePeriodicRestart() throws Exception {
        install(TestRunSpecs.mopSpec());
        agent.disableDuringSelection = true;

        Action executed = agent.step(41);

        assertSame(agent.pick, executed);
        assertEquals(0, restartCounts);
        assertTrue("the kept action reaches moveForward",
                ledger.tried(ACT, UICoverageTracker.widgetId(agent.pick)));
        assertSame(agent.pick, get("currentAction"));
        assertEquals(1, agent.historyCalls);
        assertEquals("mop", closedRecord().getJSONObject("dec").getString("src"));
    }

    /** Scenario "a stability restart replaces the pick whatever the latch". */
    @Test
    public void aStabilityRestartReplacesThePickWhateverTheLatch() throws Exception {
        install(TestRunSpecs.mopSpec());
        agent.disableDuringSelection = true;
        int threshold = RunContext.current().spec().exploration().graphStableRestartThreshold();
        assertTrue(agent.onGraphStable(threshold + 1));

        Action executed = agent.step(10);

        assertNotSame(agent.pick, executed);
        assertEquals("graph", closedRecord().getJSONObject("dec").getString("restart"));
        assertFalse("the flag is lowered", (Boolean) get("restart"));
        assertEquals(10, get("lastRestartStep"));
    }

    /** Scenario "no restart is due". */
    @Test
    public void noDueRestartKeepsThePick() throws Exception {
        install(TestRunSpecs.mopSpec());

        assertSame(agent.pick, agent.step(40));
        assertEquals(0, restartCounts);
    }

    /**
     * Scenario "the BadStateException retry does not restart": the step's first state build fails,
     * the retry selects with a periodic restart due, and the retry keeps its selection, as APE's
     * does. The restart stays due, and the next step's first attempt takes it.
     */
    @Test
    public void theBadStateRetryDoesNotRestart() throws Exception {
        install(TestRunSpecs.mopSpec());
        agent.badStates = 1;
        agent.timestamp = 40; // the step opens as 41, past the periodic threshold

        Action executed = agent.updateState(null, null);

        assertSame("the retry keeps the selected action", agent.pick, executed);
        assertEquals(0, restartCounts);
        assertEquals(0, get("lastRestartStep"));
        assertSame("the kept action reaches moveForward", agent.pick, get("currentAction"));
        assertEquals(1, agent.historyCalls);

        Action next = agent.updateState(null, null);

        assertTrue("the next step restarts", next.getType() == ActionType.EVENT_RESTART
                || next.getType() == ActionType.EVENT_CLEAN_RESTART);
        assertEquals(1, restartCounts);
        assertEquals(42, get("lastRestartStep"));
    }

    /**
     * Scenario "a replaced launch frees its activity": in a plan without {@code APE_FIXES} a
     * post-selection restart replaces the launcher's selection, so the activity is not launched,
     * stays eligible and spends no budget; the same launch kept on a later step commits.
     */
    @Test
    public void aReplacedLaunchLeavesItsActivityEligible() throws Exception {
        install(TestRunSpecs.mopSpec());
        MopLauncherStage launcher = null;
        for (DecisionStage stage : ((DecisionPipeline) get("decisionPipeline")).stages()) {
            if (stage instanceof MopLauncherStage) {
                launcher = (MopLauncherStage) stage;
            }
        }
        assertNotNull("the mop plan mounts the launcher", launcher);
        ActivityTriggerAction launch = new ActivityTriggerAction("com.x", "com.x.Target", null);
        agent.nonModelPick = launch;

        setOn(launcher, MopLauncherStage.class, "selection", launch); // what its decide selected
        assertNotSame("a due restart replaces the launch", launch, agent.step(41));
        assertEquals(1, restartCounts);
        assertEquals("no launch is counted", 0, launchCounts);
        assertEquals(0, field(MopLauncherStage.class, "launchCount").get(launcher));
        assertFalse("the activity stays eligible", launchedSet(launcher).contains("com.x.Target"));

        setOn(launcher, MopLauncherStage.class, "selection", launch);
        assertSame("no restart is due right after one", launch, agent.step(42));
        assertEquals(1, launchCounts);
        assertEquals(1, field(MopLauncherStage.class, "launchCount").get(launcher));
        assertTrue("a kept launch is never eligible again (D-B15)",
                launchedSet(launcher).contains("com.x.Target"));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> launchedSet(MopLauncherStage launcher) throws Exception {
        return (Set<String>) field(MopLauncherStage.class, "launched").get(launcher);
    }

    /** Scenario "startRestart without a due restart fails". */
    @Test(expected = IllegalStateException.class)
    public void startRestartWithoutADueRestartFails() throws Exception {
        install(TestRunSpecs.mopSpec());
        agent.startRestart();
    }

    // --- Fixtures ----------------------------------------------------------------------------

    /** The step's record, closed by the teardown flush. */
    private JSONObject closedRecord() throws Exception {
        ndjson.flushPendingStep();
        List<JSONObject> steps = new ArrayList<>();
        for (String line : captured.toString("UTF-8").split("\n")) {
            if (line.startsWith("{")) {
                JSONObject record = new JSONObject(line);
                if (!record.has("type")) {
                    steps.add(record);
                }
            }
        }
        assertEquals(1, steps.size());
        JSONObject record = steps.get(0);
        // The flush marks a still-open record unresolved; the restart record carries no outcome
        // of its own, so that marker is the only out member it can have.
        if (record.has("out")) {
            assertFalse(record.getJSONObject("out").getBoolean("resolved"));
            record.remove("out");
        }
        return record;
    }

    private static State state(String activity) throws Exception {
        StateKey key = allocate(StateKey.class);
        setOn(key, StateKey.class, "activity", activity);
        setOn(key, StateKey.class, "widgets", new Name[0]);
        State state = allocate(State.class);
        setOn(state, State.class, "stateKey", key);
        setOn(state, State.class, "actions", new ModelAction[0]);
        return state;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    private static Field field(Class<?> start, String name) throws NoSuchFieldException {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // keep looking in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setOn(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        field(owner, name).set(target, value);
    }

    private void set(String name, Object value) throws Exception {
        field(ScriptedAgent.class, name).set(agent, value);
    }

    private Object get(String name) throws Exception {
        return field(ScriptedAgent.class, name).get(agent);
    }
}
