package com.android.commands.monkey.ape.agent;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;
import java.util.LinkedList;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.llm.PromptFixtures.TestName;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.StaticGuiModel;
import com.android.commands.monkey.ape.utils.StaticArtifactLoader;
import com.android.commands.monkey.ape.utils.MopRetirement;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * mop-guidance "Target Retirement" at the agent (llm-coordinate-single-base, design D19, task
 * 13.5): counting happens at selection, in {@code StatefulAgent.moveForward}, before the coverage
 * tracker records the selected action, once per first interaction of the action's widget in its
 * activity, against the action's weight's lookup; a non-empty result reaches the sink through
 * {@code EventSink.mopRetired} (design D23).
 *
 * <p>The agent is {@code Unsafe}-allocated as in {@code StatefulAgentActionHistoryTest}: no
 * constructor runs, and the fields {@code moveForward} reads are set by hand — the run's
 * {@code MopContext}, the coverage tracker, and the selected {@code newState}/{@code newAction}.
 * The plan is installed with a sink that records {@code mopRetired} calls.
 */
public class StatefulAgentMopRetirementTest {

    private static final String ACT = "com.x.MainActivity";
    private static final String OTHER = "com.x.OtherActivity";

    private SataAgent agent;
    private UICoverageTracker tracker;
    private final List<int[]> retiredCalls = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        EventSink recording = (EventSink) Proxy.newProxyInstance(
                EventSink.class.getClassLoader(), new Class<?>[] {EventSink.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("mopRetired")) {
                        retiredCalls.add(((int[]) args[0]).clone());
                    }
                    return null;
                });
        RunContext.installForTest(TestRunSpecs.mopSpec("ape.mopScoring", "distance"), recording);
        agent = allocate(SataAgent.class);
        tracker = new UICoverageTracker();
        setField(agent, "_coverageTracker", tracker);
    }

    @After
    public void tearDown() {
        RunContext.resetForTest();
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static MopData artifact(String widgetsOfAct) throws Exception {
        String json = "{\"formatVersion\":2,\"targets\":30,"
                + "\"package\":\"com.x\",\"mainActivity\":\"" + ACT + "\","
                + "\"widgets\":{\"" + ACT + "\":{" + widgetsOfAct + "},"
                + "\"" + OTHER + "\":{" + widgetsOfAct + "}},"
                + "\"mopActivities\":[\"" + ACT + "\"],"
                + "\"handlers\":{}}";
        File f = File.createTempFile("mopretirementagent", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(json);
        }
        MopData data = StaticArtifactLoader.load(f.getAbsolutePath(), null, null, new NoopSink()).mopData;
        assertNotNull(data);
        return data;
    }

    /** The run's context, built as {@code StatefulAgent} builds it, installed on the agent. */
    private MopContext install(MopData data) throws Exception {
        MopContext mop = MopContext.fromSpec(data, StaticGuiModel.EMPTY, RunContext.current().spec().mop());
        setField(agent, "_mopContext", mop);
        return mop;
    }

    private static State state(String activity) throws Exception {
        StateKey key = allocate(StateKey.class);
        setField(key, "activity", activity);
        setField(key, "widgets", new Name[0]);
        State state = allocate(State.class);
        setField(state, "stateKey", key);
        setField(state, "actions", new ModelAction[0]);
        return state;
    }

    private static GUITreeNode node(GUITreeNode parent, String className, String shortId) {
        GUITreeNode n = new GUITreeNode(parent);
        n.setClassName(className);
        if (shortId != null) {
            n.setResourceID("com.x:id/" + shortId);
        }
        if (parent != null) {
            parent.addChild(n);
        }
        return n;
    }

    private static ModelAction action(String xpath, ActionType type, GUITreeNode node) {
        ModelAction action = new ModelAction(null, new TestName(xpath), type);
        action.resolveAt(1, 0, null, node, new GUITreeNode[] {node});
        action.setValid(true);
        return action;
    }

    /**
     * {@code updateStateInternal}'s selection of {@code action} on {@code state} (moveForward), then
     * the restart check {@code ApeAgent.updateState} applies to the action it returns.
     */
    private Action select(State state, ModelAction action) {
        agent.newState = state;
        agent.newAction = action;
        agent.moveForward();
        return agent.checkRestart(action);
    }

    /** {@code checkRestart} replaces the selected action with {@code getStartAction}; no device. */
    static class RestartingAgent extends SataAgent {
        RestartingAgent() {
            super(null, null);
        }

        @Override
        protected Action getStartAction(ActionType actionType) {
            return new Action(actionType);
        }
    }

    // -------------------------------------------------------------------------
    // Scenarios
    // -------------------------------------------------------------------------

    /**
     * Scenarios "first interaction counts each target of the node's own lookup once" and "second
     * interaction of the same widget in the same activity does not count".
     */
    @Test
    public void theFirstInteractionCountsAndTheSecondDoesNot() throws Exception {
        MopRetirement r = install(artifact("\"w\":{\"mop\":{\"click\":\"transitive\"},"
                + "\"dist\":{\"click\":[[16,3],[17,3],[18,3]]}}")).retirement();
        State st = state(ACT);
        ModelAction click = action("//w", ActionType.MODEL_CLICK, node(null, "Button", "w"));

        select(st, click);
        assertTrue("moveForward still records the interaction",
                tracker.hasActivityInteraction(ACT, UICoverageTracker.widgetId(click)));
        assertEquals(1, count(r, 16));
        assertEquals(1, count(r, 17));
        assertEquals(1, count(r, 18));

        select(st, click);
        assertEquals("the second interaction does not count", 1, count(r, 16));
        assertTrue(retiredCalls.isEmpty());

        // Novelty is per activity: the same widget is new in another activity.
        select(state(OTHER), click);
        assertEquals(2, count(r, 16));
    }

    /** Novelty is per (widget, action type): a long-click on a clicked widget is a first one. */
    @Test
    public void aLongClickOnAClickedWidgetIsAFirstInteraction() throws Exception {
        MopRetirement r = install(artifact("\"w\":{\"mop\":{\"click\":\"transitive\","
                + "\"longclick\":\"transitive\"},"
                + "\"dist\":{\"click\":[[5,2]],\"longclick\":[[5,2]]}}")).retirement();
        State st = state(ACT);
        GUITreeNode w = node(null, "Button", "w");
        select(st, action("//w", ActionType.MODEL_CLICK, w));
        select(st, action("//w", ActionType.MODEL_LONG_CLICK, w));
        assertEquals(2, count(r, 5));
    }

    /** Scenario "a weight won through containment retires its own targets". */
    @Test
    public void aWeightWonThroughContainmentRetiresItsOwnTargets() throws Exception {
        MopRetirement r = install(artifact("\"label\":{\"mop\":{\"click\":\"transitive\"},"
                + "\"dist\":{\"click\":[[7,2]]}}")).retirement();
        GUITreeNode layout = node(null, "android.widget.LinearLayout", null);
        node(layout, "android.widget.TextView", "label");
        select(state(ACT), action("//layout", ActionType.MODEL_CLICK, layout));
        assertEquals(1, count(r, 7));
    }

    /** Scenario "a target retires at the third count": the indices reach the sink. */
    @Test
    public void aRetiringStepReportsItsTargetsToTheSink() throws Exception {
        MopRetirement r = install(artifact(
                "\"a\":{\"dist\":{\"click\":[[22,2]]}},"
                        + "\"b\":{\"dist\":{\"click\":[[22,2]]}},"
                        + "\"c\":{\"dist\":{\"click\":[[22,2],[4,3]]}}")).retirement();
        State st = state(ACT);
        select(st, action("//a", ActionType.MODEL_CLICK, node(null, "Button", "a")));
        select(st, action("//b", ActionType.MODEL_CLICK, node(null, "Button", "b")));
        assertTrue(retiredCalls.isEmpty());
        select(st, action("//c", ActionType.MODEL_CLICK, node(null, "Button", "c")));
        assertEquals(1, retiredCalls.size());
        assertArrayEquals(new int[] {22}, retiredCalls.get(0));
        assertTrue(r.retired(22));
        assertEquals(1, r.retiredCount());
        assertEquals("target 4 lies at d = 3, not at d* = 2", 0, count(r, 4));
    }

    /** When no containment lookup has an unretired pair, nothing is counted. */
    @Test
    public void nothingIsCountedWithoutAnUnretiredPair() throws Exception {
        MopContext mop = install(artifact(
                "\"a\":{\"dist\":{\"click\":[[9,1]]}},"
                        + "\"b\":{\"dist\":{\"click\":[[9,1]]}},"
                        + "\"c\":{\"dist\":{\"click\":[[9,1]]}},"
                        + "\"d\":{\"dist\":{\"click\":[[9,1]]}},"
                        + "\"plain\":{\"mop\":{\"click\":\"direct\"}}"));
        MopRetirement r = mop.retirement();
        State st = state(ACT);
        select(st, action("//p", ActionType.MODEL_CLICK, node(null, "Button", "plain")));
        for (String id : new String[] {"a", "b", "c"}) {
            select(st, action("//" + id, ActionType.MODEL_CLICK, node(null, "Button", id)));
        }
        assertTrue(r.retired(9));
        assertEquals(3, count(r, 9));
        // Target 9 is retired, so d's lookup has no unretired pair: no count.
        select(st, action("//d", ActionType.MODEL_CLICK, node(null, "Button", "d")));
        assertEquals(3, count(r, 9));
    }

    /**
     * Scenario "a first interaction with weight 0 counts nothing": the lookup's pairs are unretired
     * but all at d >= 4, so the action weighs 0 and does not retire what it did not attract.
     */
    @Test
    public void aFirstInteractionWithWeightZeroCountsNothing() throws Exception {
        MopRetirement r = install(artifact(
                "\"far\":{\"dist\":{\"click\":[[5,4],[9,6]]}}")).retirement();
        select(state(ACT), action("//far", ActionType.MODEL_CLICK, node(null, "Button", "far")));
        assertEquals(0, count(r, 5));
        assertEquals(0, count(r, 9));
        assertTrue(retiredCalls.isEmpty());
    }

    /**
     * Scenario "an action a restart replaces counts nothing": the count taken at selection is
     * discarded when {@code checkRestart} replaces the action, which then never runs.
     */
    @Test
    public void anActionARestartReplacesCountsNothing() throws Exception {
        RestartingAgent restarting = allocate(RestartingAgent.class);
        setField(restarting, "_coverageTracker", tracker);
        setField(restarting, "actionBuffer", new LinkedList<StateTransition>());
        setField(restarting, "restart", true);
        agent = restarting;
        MopRetirement r = install(artifact(
                "\"w\":{\"dist\":{\"click\":[[22,2]]}}")).retirement();
        ModelAction click = action("//w", ActionType.MODEL_CLICK, node(null, "Button", "w"));

        Action dispatched = select(state(ACT), click);
        assertNotSame("the restart replaced the selected action", click, dispatched);
        assertEquals(0, count(r, 22));
        assertTrue(retiredCalls.isEmpty());

        // The same selection that survives the restart check counts.
        assertSame(click, select(state(OTHER), click));
        assertEquals(1, count(r, 22));
    }

    /** Scenario "an off-tree LLM tap never counts". */
    @Test
    public void anOffTreeLlmTapNeverCounts() throws Exception {
        MopRetirement r = install(artifact("\"w\":{\"dist\":{\"click\":[[3,0]]}}")).retirement();
        select(state(ACT), new LlmTapAction(null, 540, 900, false));
        assertEquals(0, count(r, 3));
        assertTrue(retiredCalls.isEmpty());
    }

    /** Scenario "no counting under flag": no retirement exists, and moveForward still records. */
    @Test
    public void noRetirementUnderFlag() throws Exception {
        RunContext.installForTest(TestRunSpecs.mopSpec(), RunContext.current().sink());
        MopContext mop = install(artifact("\"w\":{\"dist\":{\"click\":[[3,0]]}}"));
        assertNull(mop.retirement());
        ModelAction click = action("//w", ActionType.MODEL_CLICK, node(null, "Button", "w"));
        select(state(ACT), click);
        assertTrue(tracker.hasActivityInteraction(ACT, UICoverageTracker.widgetId(click)));
        assertTrue(retiredCalls.isEmpty());
    }

    /** Without an artifact there is no context, and moveForward records as before. */
    @Test
    public void noArtifactNoCounting() throws Exception {
        setField(agent, "_mopContext", null);
        ModelAction click = action("//w", ActionType.MODEL_CLICK, node(null, "Button", "w"));
        select(state(ACT), click);
        assertTrue(tracker.hasActivityInteraction(ACT, UICoverageTracker.widgetId(click)));
    }

    /** A target's counter, read through the package-private accessor. */
    private static int count(MopRetirement r, int i) throws Exception {
        java.lang.reflect.Method m = MopRetirement.class.getDeclaredMethod("count", int.class);
        m.setAccessible(true);
        return (Integer) m.invoke(r, i);
    }
}
