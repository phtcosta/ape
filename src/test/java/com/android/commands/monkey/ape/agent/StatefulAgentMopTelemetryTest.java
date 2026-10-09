package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.llm.PromptFixtures.TestName;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.NdjsonSink;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * event-sink "StepRecord Schema" and "RUN_END As the Natural Last Record" at the agent
 * (llm-coordinate-single-base, design D23, task 16.1): the decision source writes {@code dec.mopd}
 * and {@code dec.mops} from the one {@code MopScorer.weigh} of the chosen action, {@code dec.ret}
 * reaches the same record from {@code moveForward}, and teardown hands {@code RUN_END} the stamp and
 * retirement counts.
 *
 * <p>The agent is {@code Unsafe}-allocated as in {@code StatefulAgentMopRetirementTest}, with the
 * fields the reached methods read set by hand, and the plan installed with a real
 * {@link NdjsonSink} over a buffer: the assertions read the emitted lines, which are the product.
 */
public class StatefulAgentMopTelemetryTest {

    private static final String ACT = "com.x.MainActivity";
    private static final String STAMP = "com.x.Foo$1";

    private ByteArrayOutputStream captured;
    private SataAgent agent;
    private int step;

    @After
    public void tearDown() {
        RunContext.resetForTest();
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private void install(RunSpec spec) throws Exception {
        captured = new ByteArrayOutputStream();
        RunContext.installForTest(spec, new NdjsonSink(new PrintStream(captured, true, "UTF-8")));
        agent = allocate(SataAgent.class);
        setField(agent, "_coverageTracker", new UICoverageTracker());
    }

    private void distance() throws Exception {
        install(TestRunSpecs.mopSpec("ape.mopScoring", "distance"));
    }

    private void flag() throws Exception {
        install(TestRunSpecs.mopSpec());
    }

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

    /** A format-2 artifact with the given widgets of {@link #ACT} and handlers table. */
    private static MopData artifact(String widgetsOfAct, String handlers) throws Exception {
        String json = "{\"formatVersion\":2,\"targets\":30,"
                + "\"package\":\"com.x\",\"mainActivity\":\"" + ACT + "\","
                + "\"widgets\":{\"" + ACT + "\":{" + widgetsOfAct + "}},"
                + "\"mopActivities\":[\"" + ACT + "\"],"
                + "\"handlers\":{" + handlers + "}}";
        File f = File.createTempFile("moptelemetryagent", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(json);
        }
        MopData data = MopData.load(f.getAbsolutePath(), null, null, new NoopSink());
        assertNotNull(data);
        return data;
    }

    /** The run's context, built as {@code StatefulAgent} builds it, installed on the agent. */
    private MopContext context(MopData data) throws Exception {
        MopContext mop = MopContext.fromSpec(data, RunContext.current().spec().mop());
        setField(agent, "_mopContext", mop);
        return mop;
    }

    private static State state() throws Exception {
        StateKey key = allocate(StateKey.class);
        setField(key, "activity", ACT);
        setField(key, "widgets", new Name[0]);
        State state = allocate(State.class);
        setField(state, "stateKey", key);
        setField(state, "actions", new ModelAction[0]);
        return state;
    }

    private static GUITreeNode node(String shortId, String handlerClick) {
        GUITreeNode n = new GUITreeNode(null);
        n.setClassName("android.widget.Button");
        if (shortId != null) {
            n.setResourceID("com.x:id/" + shortId);
        }
        n.setHandlerClick(handlerClick);
        return n;
    }

    /** A click resolved on {@code node}, carrying the boost the scoring pass would have given it. */
    private static ModelAction click(String xpath, GUITreeNode node, int mopBoost) {
        ModelAction action = new ModelAction(null, new TestName(xpath), ActionType.MODEL_CLICK);
        action.resolveAt(1, 0, null, node, new GUITreeNode[] {node});
        action.setValid(true);
        action.setMopBoost(mopBoost);
        return action;
    }

    /**
     * One step as {@code updateStateInternal} runs it for a model action: the record opens, the
     * decision is recorded, {@code moveForward} takes the retirement count, and the restart check applies it.
     */
    private void select(State state, ModelAction action) {
        RunContext.current().sink().beginStep(++step, step, ACT, true, "S1");
        agent.recordModelDecision(state, action);
        agent.newState = state;
        agent.newAction = action;
        agent.moveForward();
        agent.checkRestart(action);
    }

    /** The {@code dec} of the one step record {@link #select} wrote for {@code action}. */
    private JSONObject decisionOf(ModelAction action) throws Exception {
        select(state(), action);
        RunContext.current().sink().flushPendingStep();
        List<JSONObject> steps = stepRecords();
        assertEquals(1, steps.size());
        return steps.get(0).getJSONObject("dec");
    }

    private List<JSONObject> stepRecords() throws Exception {
        List<JSONObject> steps = new ArrayList<>();
        for (String line : new String(captured.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("{\"s\":")) {
                steps.add(new JSONObject(line));
            }
        }
        return steps;
    }

    // -------------------------------------------------------------------------
    // dec.mopd and dec.mops
    // -------------------------------------------------------------------------

    /** Scenario "distance-mode MOP pick carries its distance and source". */
    @Test
    public void aDistancePickThroughAStampCarriesItsDistanceAndSource() throws Exception {
        distance();
        context(artifact("", "\"" + STAMP + "\":{\"dist\":{\"click\":[[4,2],[6,5]]}}"));
        JSONObject dec = decisionOf(click("//w", node("w", STAMP), 400));
        assertEquals(400, dec.getInt("mop"));
        assertEquals(2, dec.getInt("mopd"));
        assertEquals("s", dec.getString("mops"));
    }

    /** Scenario "distance zero is written, not omitted". */
    @Test
    public void aHandlerThatIsItselfATargetWritesDistanceZero() throws Exception {
        distance();
        context(artifact("\"w\":{\"mop\":{\"click\":\"direct\"},\"dist\":{\"click\":[[3,0]]}}",
                ""));
        JSONObject dec = decisionOf(click("//w", node("w", null), 500));
        assertTrue(dec.has("mopd"));
        assertEquals(0, dec.getInt("mopd"));
        assertEquals("i", dec.getString("mops"));
    }

    /**
     * Under {@code distance} an action whose lookup holds only targets at {@code d >= 4} weighs 0:
     * no {@code mopd}, and the source is still written.
     */
    @Test
    public void aDistanceLookupTooFarToWeighCarriesTheSourceOnly() throws Exception {
        distance();
        context(artifact("\"far\":{\"dist\":{\"click\":[[5,4]]}}", ""));
        JSONObject dec = decisionOf(click("//far", node("far", null), 0));
        assertFalse(dec.has("mop"));
        assertFalse(dec.has("mopd"));
        assertEquals("i", dec.getString("mops"));
    }

    /** Scenario "a stamp that reaches nothing is recorded as the source" (under {@code flag}). */
    @Test
    public void aListedStampWithNoMopBeatsAFlaggedIdKeyAndIsRecordedAsTheSource() throws Exception {
        flag();
        context(artifact("\"btn\":{\"mop\":{\"click\":\"direct\"},\"dist\":{\"click\":[[1,0]]}}",
                "\"" + STAMP + "\":{\"mop\":{\"click\":\"none\"}}"));
        JSONObject dec = decisionOf(click("//btn", node("btn", STAMP), 0));
        assertEquals("s", dec.getString("mops"));
        assertFalse(dec.has("mop"));
        assertFalse(dec.has("mopd"));
    }

    /** Scenario "flag mode carries no distance and no retirement": {@code mops} only. */
    @Test
    public void aFlagPickCarriesTheSourceAndNoDistance() throws Exception {
        flag();
        context(artifact("\"w\":{\"mop\":{\"click\":\"direct\"},\"dist\":{\"click\":[[3,0]]}}",
                ""));
        JSONObject dec = decisionOf(click("//w", node("w", null), 500));
        assertEquals(500, dec.getInt("mop"));
        assertEquals("i", dec.getString("mops"));
        assertFalse(dec.has("mopd"));
        assertFalse(dec.has("ret"));
    }

    /** A node the artifact does not know: source {@code NONE}, neither field. */
    @Test
    public void aLookupThatFindsNothingWritesNeitherField() throws Exception {
        distance();
        context(artifact("\"w\":{\"dist\":{\"click\":[[3,0]]}}", ""));
        JSONObject dec = decisionOf(click("//other", node("other", "com.x.Unlisted$9"), 0));
        assertFalse(dec.has("mops"));
        assertFalse(dec.has("mopd"));
    }

    /** Without an artifact there is no context, and no MOP field. */
    @Test
    public void noArtifactNoMopField() throws Exception {
        distance();
        setField(agent, "_mopContext", null);
        JSONObject dec = decisionOf(click("//w", node("w", STAMP), 0));
        assertFalse(dec.has("mops"));
        assertFalse(dec.has("mopd"));
    }

    // -------------------------------------------------------------------------
    // dec.ret
    // -------------------------------------------------------------------------

    /** Scenario "retirement recorded on the step that caused it", end to end. */
    @Test
    public void theThirdFirstInteractionRetiresOnItsOwnRecord() throws Exception {
        distance();
        context(artifact("\"a\":{\"dist\":{\"click\":[[11,2],[4,2]]}},"
                + "\"b\":{\"dist\":{\"click\":[[4,2],[11,2]]}},"
                + "\"c\":{\"dist\":{\"click\":[[4,2],[11,3]]}}", ""));
        State st = state();
        select(st, click("//a", node("a", null), 400));
        select(st, click("//b", node("b", null), 400));
        select(st, click("//c", node("c", null), 400));
        // A fourth step: the targets are retired, so it weighs nothing and retires nothing.
        select(st, click("//a2", node("a", null), 0));
        RunContext.current().sink().flushPendingStep();

        List<JSONObject> steps = stepRecords();
        assertEquals(4, steps.size());
        assertFalse(steps.get(0).getJSONObject("dec").has("ret"));
        assertFalse(steps.get(1).getJSONObject("dec").has("ret"));
        assertEquals("[4,11]", steps.get(2).getJSONObject("dec").getJSONArray("ret").toString());
        JSONObject after = steps.get(3).getJSONObject("dec");
        assertFalse("a target retires once", after.has("ret"));
        assertFalse("a retired target gives no distance", after.has("mopd"));
    }

    // -------------------------------------------------------------------------
    // RUN_END counters
    // -------------------------------------------------------------------------

    /** Teardown hands RUN_END the stamped nodes the agent saw and the targets it retired. */
    @Test
    public void runEndCarriesTheAgentsStampAndRetirementCounts() throws Exception {
        distance();
        StatefulAgentTearDownTest.ThrowingAgent teardown =
                allocate(StatefulAgentTearDownTest.ThrowingAgent.class);
        MopContext mop = MopContext.fromSpec(
                artifact("\"w\":{\"dist\":{\"click\":[[7,1],[8,2]]}}", ""),
                RunContext.current().spec().mop());
        MopData.Lookup lookup = mop.data().lookup(ACT, node("w", null), "click");
        for (int i = 0; i < 3; i++) {
            mop.retirement().onFirstInteraction(lookup);
        }
        assertEquals(2, mop.retirement().retiredCount());
        setField(teardown, "_mopContext", mop);
        setField(teardown, "stampNodes", 41);

        teardown.tearDown();

        JSONObject counters = runEnd().getJSONObject("counters");
        assertEquals(41, counters.getInt("stampNodes"));
        assertEquals(2, counters.getInt("retired"));
        assertEquals(0, counters.getInt("stampHits"));
    }

    /** An agent with no artifact still writes the three counters, at zero. */
    @Test
    public void runEndWithoutMopDataWritesZeros() throws Exception {
        flag();
        allocate(StatefulAgentTearDownTest.ThrowingAgent.class).tearDown();

        JSONObject counters = runEnd().getJSONObject("counters");
        assertEquals(0, counters.getInt("stampNodes"));
        assertEquals(0, counters.getInt("stampHits"));
        assertEquals(0, counters.getInt("retired"));
    }

    // -------------------------------------------------------------------------
    // stampNodes: what one capture adds
    // -------------------------------------------------------------------------

    /** A node is counted once whether it carries one stamp or two; unstamped nodes are not. */
    @Test
    public void aCaptureCountsEveryNodeWithAtLeastOneStamp() {
        GUITreeNode root = new GUITreeNode(null);
        GUITreeNode both = child(root);
        both.setHandlerClick("com.x.A$1");
        both.setHandlerLongClick("com.x.A$2");
        GUITreeNode longOnly = child(both);
        longOnly.setHandlerLongClick("com.x.B$1");
        GUITreeNode plain = child(root);
        GUITreeNode deep = child(plain);
        deep.setHandlerClick("com.x.C$1");

        assertEquals(3, GUITreeNode.countStampedNodes(root));
        assertTrue(both.hasHandlerStamp());
        assertTrue(longOnly.hasHandlerStamp());
        assertFalse(plain.hasHandlerStamp());

        GUITreeNode bare = new GUITreeNode(null);
        child(child(bare));
        assertEquals("a tree of an unstamped APK counts zero", 0,
                GUITreeNode.countStampedNodes(bare));
    }

    private static GUITreeNode child(GUITreeNode parent) {
        GUITreeNode n = new GUITreeNode(parent);
        parent.addChild(n);
        return n;
    }

    private JSONObject runEnd() throws Exception {
        for (String line : new String(captured.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("{") && line.contains("\"type\":\"RUN_END\"")) {
                return new JSONObject(line);
            }
        }
        throw new AssertionError("no RUN_END record");
    }
}
