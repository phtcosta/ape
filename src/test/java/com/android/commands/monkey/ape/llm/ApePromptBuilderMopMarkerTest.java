package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import org.junit.Before;
import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.GraphElement;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.MopRetirement;
import com.android.commands.monkey.ape.utils.MopScorer;
import com.android.commands.monkey.ape.utils.TestMopContexts;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static com.android.commands.monkey.ape.llm.PromptFixtures.H;
import static com.android.commands.monkey.ape.llm.PromptFixtures.W;
import static com.android.commands.monkey.ape.llm.PromptFixtures.lineWith;
import static com.android.commands.monkey.ape.llm.PromptFixtures.list;
import static org.junit.Assert.*;

/**
 * llm-prompt "MOP Marker Annotation" and "Exploration Context in Prompt" (llm-coordinate-single-base,
 * Part B, design D22, task 15.1): a line's marker is the scoring's verdict over the line's actions
 * — {@code [DM]} when one of them is direct under the active mode and has a positive MOP weight,
 * else {@code [M]} when one has a positive MOP weight, else nothing — read through the same
 * {@link MopData#lookup} with containment the scoring pass uses ({@link MopScorer#weigh}), and the
 * {@code N/M MOP.} context counts actions with the same test.
 *
 * <p>The prompts are built with a real {@link MopContext} over a format-2 artifact written to a
 * temporary file and loaded through the production {@link MopData#load}, on a {@link State} whose
 * activity is {@link #ACT}.
 */
public class ApePromptBuilderMopMarkerTest {

    /**
     * {@code MopData.load} reads the MOP activity source from the plan (guidance-groups
     * INV-GRP-06), so every case runs under one: the jar defaults, whose source is the widgets.
     */
    @Before
    public void installThePlan() {
        TestRunSpecs.install();
    }

    private static final String ACT = "com.example.MainActivity";

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /** A format-2 artifact with the given widgets of {@link #ACT} and handlers. */
    private static MopData artifact(String widgetsOfAct, String handlers) throws Exception {
        String json = "{\"formatVersion\":2,\"targets\":30,"
                + "\"package\":\"com.example\",\"mainActivity\":\"" + ACT + "\","
                + "\"widgets\":{\"" + ACT + "\":{" + widgetsOfAct + "}},"
                + "\"mopActivities\":[\"" + ACT + "\"],"
                + "\"handlers\":{" + handlers + "}}";
        File f = File.createTempFile("mopmarker", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(json);
        }
        MopData data = MopData.load(f.getAbsolutePath(), null, null, new NoopSink());
        assertNotNull("synthetic artifact must load", data);
        return data;
    }

    private static MopData artifact(String widgetsOfAct) throws Exception {
        return artifact(widgetsOfAct, "");
    }

    /** {@code "id":{"mop":{...}}}. */
    private static String flags(String id, String mop) {
        return "\"" + id + "\":{\"mop\":{" + mop + "}}";
    }

    /** {@code "id":{"mop":{"click":"transitive"},"dist":{"click":[[i,d]]}}}. */
    private static String distWidget(String id, int i, int d) {
        return "\"" + id + "\":{\"mop\":{\"click\":\"transitive\"},"
                + "\"dist\":{\"click\":[[" + i + "," + d + "]]}}";
    }

    /** The jar-default {@code flag} context (500/300). */
    private static MopContext flag(MopData data) {
        return TestMopContexts.defaults(data);
    }

    private static MopContext distance(MopData data, MopRetirement retirement) {
        return new MopContext(data, MopScoring.DISTANCE, 500, 300, 500, 400, 300, 3, 6,
                retirement);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return allocate.invoke(theUnsafe.get(null), type);
    }

    private static void set(Object target, Class<?> owner, String field, Object value)
            throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * A state of {@link #ACT} offering {@code actions}, visited {@code visits} times. Each action
     * becomes the state's own, as {@code State} builds them: the contributions read the activity
     * from the action's state.
     */
    static State state(int visits, List<ModelAction> actions) throws Exception {
        StateKey key = (StateKey) allocate(StateKey.class);
        set(key, StateKey.class, "activity", ACT);
        set(key, StateKey.class, "widgets", new Name[0]);
        State state = (State) allocate(State.class);
        set(state, State.class, "stateKey", key);
        set(state, State.class, "actions", actions.toArray(new ModelAction[0]));
        Field visited = GraphElement.class.getDeclaredField("visitedCount");
        visited.setAccessible(true);
        visited.setInt(state, visits);
        for (ModelAction action : actions) set(action, ModelAction.class, "state", state);
        return state;
    }

    private static int nextTop = 100;

    /** A node of {@code className} with {@code text}, resource id {@code shortId}, own bounds. */
    private static GUITreeNode node(GUITreeNode parent, String className, String text,
            String shortId) {
        GUITreeNode n = new GUITreeNode(parent);
        n.setClassName(className);
        if (text != null) n.setText(text);
        if (shortId != null) n.setResourceID("com.example:id/" + shortId);
        int top = nextTop;
        nextTop += 120;
        n.setBoundsInScreen(new Rect(0, top, 1080, top + 100));
        if (parent != null) parent.addChild(n);
        return n;
    }

    private static GUITreeNode button(String text, String shortId) {
        return node(null, "android.widget.Button", text, shortId);
    }

    private static ModelAction act(ActionType type, GUITreeNode node) {
        return PromptFixtures.action(new PromptFixtures.TestName("//" + node.getText()), type, node);
    }

    private static ModelAction click(GUITreeNode node) {
        return act(ActionType.MODEL_CLICK, node);
    }

    /** The MOP contribution bound to {@code mop}, as a cell with MOP mounts it; none for null. */
    static List<PromptContribution> mopContribution(MopContext mop) {
        if (mop == null) return Collections.emptyList();
        MopPromptContribution contribution = new MopPromptContribution();
        contribution.bind(mop);
        return Collections.<PromptContribution>singletonList(contribution);
    }

    private static String userText(String variant, MopContext mop, State state,
            List<ModelAction> actions) {
        List<SglangClient.ContentPart> parts = new ApePromptBuilder(variant, mopContribution(mop))
                .build(W, H, state, actions, "data", null).get(1).getContentParts();
        for (SglangClient.ContentPart part : parts) {
            if ("text".equals(part.getType())) return part.getText();
        }
        return "";
    }

    private static String userText(MopContext mop, List<ModelAction> actions) throws Exception {
        return userText(ApePromptBuilder.VARIANT_APE_CURRENT, mop, state(0, actions), actions);
    }

    private static final String[] LIST_VARIANTS = {
            ApePromptBuilder.VARIANT_APE_CURRENT, ApePromptBuilder.VARIANT_APE_REASONING,
            ApePromptBuilder.VARIANT_COMPACT_V1, ApePromptBuilder.VARIANT_V13,
            ApePromptBuilder.VARIANT_V17};

    /** The marker of the one line naming {@code text}, in every variant that lists widgets. */
    private static void assertMarker(String expected, MopContext mop, List<ModelAction> actions,
            String text) throws Exception {
        for (String variant : LIST_VARIANTS) {
            String user = userText(variant, mop, state(0, actions), actions);
            assertEquals(variant + ":\n" + user, expected, markerOf(lineWith(user, "\"" + text + "\"")));
        }
    }

    /** {@code [DM]}, {@code [M]} or "" as the line carries it. */
    private static String markerOf(String line) {
        boolean dm = line.contains(" [DM]");
        boolean m = line.contains(" [M]");
        assertFalse("both markers on one line: " + line, dm && m);
        return dm ? "[DM]" : m ? "[M]" : "";
    }

    // -------------------------------------------------------------------------
    // MOP Marker Annotation
    // -------------------------------------------------------------------------

    /** Scenario "Direct MOP widget". */
    @Test
    public void directMopWidget() throws Exception {
        MopContext mop = flag(artifact(flags("btn_encrypt", "\"click\":\"direct\"")));
        assertMarker("[DM]", mop, list(click(button("Encrypt", "btn_encrypt"))), "Encrypt");
    }

    /** Scenario "Transitive MOP widget". */
    @Test
    public void transitiveMopWidget() throws Exception {
        MopContext mop = flag(artifact(flags("btn_settings", "\"click\":\"transitive\"")));
        assertMarker("[M]", mop, list(click(button("Settings", "btn_settings"))), "Settings");
    }

    /** Scenario "widget flagged only through containment is marked". */
    @Test
    public void containmentFlaggedWidgetIsMarked() throws Exception {
        MopContext mop = flag(artifact(flags("item_row", "\"click\":\"direct\"")));
        GUITreeNode row = node(null, "android.widget.LinearLayout", null, "item_row");
        GUITreeNode label = node(row, "android.widget.TextView", "Row label", "row_label");
        ModelAction a = click(label);
        List<ModelAction> actions = list(a);
        assertEquals("the scoring pass weighs it through containment",
                mop.weightDirect(), MopScorer.score(mop, state(0, actions), a));
        assertMarker("[DM]", mop, actions, "Row label");
    }

    /** Scenario "long-click-only widget flagged only for click is not marked". */
    @Test
    public void longClickOnlyWidgetFlaggedForClickIsNotMarked() throws Exception {
        MopContext mop = flag(artifact(
                flags("btn_delete", "\"click\":\"direct\",\"longClick\":\"none\"")));
        ModelAction a = act(ActionType.MODEL_LONG_CLICK, button("Delete", "btn_delete"));
        List<ModelAction> actions = list(a);
        assertEquals("the action's MOP weight", 0, MopScorer.score(mop, state(0, actions), a));
        assertMarker("", mop, actions, "Delete");
    }

    /** Scenario "an event flagged none takes no marker from another event". */
    @Test
    public void eventFlaggedNoneTakesNoMarkerFromAnotherEvent() throws Exception {
        MopContext mop = flag(artifact(
                flags("btn_a", "\"click\":\"none\",\"longClick\":\"direct\"") + ","
                + flags("btn_b", "\"click\":\"transitive\",\"longClick\":\"direct\"")));
        List<ModelAction> actions = list(click(button("Alpha", "btn_a")),
                click(button("Beta", "btn_b")));
        String user = userText(mop, actions);
        assertEquals(user, "", markerOf(lineWith(user, "\"Alpha\"")));
        assertEquals(user, "[M]", markerOf(lineWith(user, "\"Beta\"")));
    }

    /** Scenario "a stamped handler that reaches nothing removes the marker". */
    @Test
    public void stampedHandlerReachingNothingRemovesTheMarker() throws Exception {
        MopData data = artifact(flags("btn_save", "\"click\":\"direct\""),
                "\"com.example.SaveListener\":{\"mop\":{\"click\":\"none\"}}");
        MopContext mop = flag(data);
        GUITreeNode save = button("Save", "btn_save");
        save.setHandlerClick("com.example.SaveListener");
        assertEquals(MopData.Lookup.Source.STAMP, data.lookup(ACT, save, "click").source);
        assertMarker("", mop, list(click(save)), "Save");
    }

    /** Scenario "a stamp naming an unlisted class falls back to the id key". */
    @Test
    public void unlistedStampFallsBackToTheIdKey() throws Exception {
        MopContext mop = flag(artifact(flags("btn_encrypt", "\"click\":\"direct\"")));
        GUITreeNode encrypt = button("Encrypt", "btn_encrypt");
        encrypt.setHandlerClick(
                "androidx.appcompat.app.AppCompatViewInflater$DeclaredOnClickListener");
        assertMarker("[DM]", mop, list(click(encrypt)), "Encrypt");
    }

    /** Scenario "distance mode marks by the nearest unretired target". */
    @Test
    public void distanceModeMarksByNearestUnretiredTarget() throws Exception {
        MopContext mop = distance(artifact(distWidget("w_near", 4, 0) + ","
                + distWidget("w_one", 2, 1) + "," + distWidget("w_far", 7, 5)), null);
        List<ModelAction> actions = list(click(button("Near", "w_near")),
                click(button("One", "w_one")), click(button("Far", "w_far")));
        for (String variant : LIST_VARIANTS) {
            String user = userText(variant, mop, state(0, actions), actions);
            assertEquals(variant + ":\n" + user, "[DM]", markerOf(lineWith(user, "\"Near\"")));
            assertEquals(variant + ": d = 1 weighs as d = 0 but is not direct\n" + user,
                    "[M]", markerOf(lineWith(user, "\"One\"")));
            assertEquals(variant + ":\n" + user, "", markerOf(lineWith(user, "\"Far\"")));
        }
    }

    /** Scenario "a line whose only target retired loses its marker". */
    @Test
    public void lineWhoseOnlyTargetRetiredLosesItsMarker() throws Exception {
        MopData data = artifact(distWidget("w_near", 4, 0));
        MopRetirement retirement = new MopRetirement(data.targetCount(), 3);
        MopContext mop = distance(data, retirement);
        GUITreeNode near = button("Near", "w_near");
        List<ModelAction> actions = list(click(near));
        assertMarker("[DM]", mop, actions, "Near");

        MopData.Lookup lookup = data.lookup(ACT, near, "click");
        for (int k = 0; k < 3; k++) retirement.onFirstInteraction(lookup);
        assertTrue("target 4 retired", retirement.retired(4));

        assertMarker("", mop, actions, "Near");
    }

    /** Scenario "a collapsed click and long-click line is direct if either action is". */
    @Test
    public void collapsedClickAndLongClickLineIsDirectIfEitherActionIs() throws Exception {
        for (String variant : new String[] {ApePromptBuilder.VARIANT_V13,
                ApePromptBuilder.VARIANT_V17}) {
            assertCollapsed(variant, "\"click\":\"none\",\"longClick\":\"direct\"", "[DM]");
            assertCollapsed(variant, "\"click\":\"transitive\",\"longClick\":\"none\"", "[M]");
        }
    }

    private static void assertCollapsed(String variant, String mopFlags, String expected)
            throws Exception {
        MopContext mop = flag(artifact(flags("edit_name", mopFlags)));
        GUITreeNode edit = node(null, "android.widget.EditText", "Name", "edit_name");
        Name target = new PromptFixtures.TestName("//edit_name");
        List<ModelAction> actions = list(
                PromptFixtures.action(target, ActionType.MODEL_CLICK, edit),
                PromptFixtures.action(target, ActionType.MODEL_LONG_CLICK, edit));
        String user = userText(variant, mop, state(0, actions), actions);
        assertEquals(variant + " " + mopFlags + ":\n" + user, expected,
                markerOf(lineWith(user, "\"Name\"")));
    }

    /** "an arm that zeroes the MOP weights shows no marker". */
    @Test
    public void zeroWeightsShowNoMarker() throws Exception {
        MopContext mop = TestMopContexts.flag(artifact(
                flags("btn_encrypt", "\"click\":\"direct\"") + ","
                + flags("btn_settings", "\"click\":\"transitive\"")), 0, 0);
        List<ModelAction> actions = list(click(button("Encrypt", "btn_encrypt")),
                click(button("Settings", "btn_settings")));
        assertMarker("", mop, actions, "Encrypt");
        assertMarker("", mop, actions, "Settings");
        assertTrue(userText(mop, actions).contains("NEW state. 0/2 MOP."));
    }

    /** Scenario "No MOP data": no marker anywhere, and the context without the MOP count. */
    @Test
    public void noMopContextNoMarker() throws Exception {
        List<ModelAction> actions = list(click(button("Encrypt", "btn_encrypt")));
        for (String variant : LIST_VARIANTS) {
            String user = userText(variant, null, state(0, actions), actions);
            assertFalse(variant + ":\n" + user, user.contains("[DM]") || user.contains("[M]"));
            assertFalse(variant + ":\n" + user, user.contains("MOP."));
        }
    }

    // -------------------------------------------------------------------------
    // Exploration Context in Prompt
    // -------------------------------------------------------------------------

    /** Scenario "New state with MOP data": 3 of 8 target-requiring actions are MOP. */
    @Test
    public void newStateWithMopData() throws Exception {
        MopContext mop = flag(artifact(flags("m1", "\"click\":\"direct\"") + ","
                + flags("m2", "\"click\":\"transitive\"") + ","
                + flags("m3", "\"click\":\"direct\"")));
        List<ModelAction> actions = list(
                click(button("M one", "m1")), click(button("M two", "m2")),
                click(button("M three", "m3")), click(button("P one", "p1")),
                click(button("P two", "p2")), click(button("P three", "p3")),
                click(button("P four", "p4")), click(button("P five", "p5")));
        assertTrue(userText(mop, actions).contains("NEW state. 3/8 MOP."));
    }

    /** Scenario "Revisited state without MOP". */
    @Test
    public void revisitedStateWithoutMop() throws Exception {
        List<ModelAction> actions = list(click(button("Encrypt", "btn_encrypt")));
        String user = userText(ApePromptBuilder.VARIANT_APE_CURRENT, null, state(5, actions),
                actions);
        assertTrue(user, user.contains("Visited 5x."));
        assertFalse(user, user.contains("MOP."));
    }

    /** Scenario "the count uses the per-event test", in every variant that shows the context. */
    @Test
    public void countUsesThePerEventTest() throws Exception {
        MopContext mop = flag(artifact(
                flags("btn_a", "\"click\":\"none\",\"longClick\":\"direct\"")));
        GUITreeNode a = button("Alpha", "btn_a");
        List<ModelAction> actions = list(click(a), act(ActionType.MODEL_LONG_CLICK, a),
                click(button("Plain", "btn_plain")));
        for (String variant : new String[] {ApePromptBuilder.VARIANT_APE_CURRENT,
                ApePromptBuilder.VARIANT_VISUAL_ONLY, ApePromptBuilder.VARIANT_V17}) {
            String user = userText(variant, mop, state(0, actions), actions);
            assertTrue(variant + ":\n" + user, user.contains("NEW state. 1/3 MOP."));
        }
    }

    /** Scenario "a retired target leaves the count". */
    @Test
    public void retiredTargetLeavesTheCount() throws Exception {
        MopData data = artifact(distWidget("w_near", 4, 0));
        MopRetirement retirement = new MopRetirement(data.targetCount(), 3);
        MopContext mop = distance(data, retirement);
        GUITreeNode near = button("Near", "w_near");
        List<ModelAction> actions = list(click(near), click(button("Plain", "btn_plain")));
        State revisited = state(2, actions);
        assertTrue(userText(ApePromptBuilder.VARIANT_APE_CURRENT, mop, revisited, actions)
                .contains("Visited 2x. 1/2 MOP."));

        MopData.Lookup lookup = data.lookup(ACT, near, "click");
        for (int k = 0; k < 3; k++) retirement.onFirstInteraction(lookup);

        for (String variant : new String[] {ApePromptBuilder.VARIANT_APE_CURRENT,
                ApePromptBuilder.VARIANT_VISUAL_ONLY, ApePromptBuilder.VARIANT_V17}) {
            String user = userText(variant, mop, revisited, actions);
            assertTrue(variant + ":\n" + user, user.contains("Visited 2x. 0/2 MOP."));
        }
    }

    /** "a line has a marker if and only if at least one of its actions is counted in N". */
    @Test
    public void aMarkedLineIsACountedAction() throws Exception {
        MopContext mop = flag(artifact(
                flags("edit_name", "\"click\":\"none\",\"longClick\":\"direct\"") + ","
                + flags("btn_b", "\"click\":\"transitive\"")));
        GUITreeNode edit = node(null, "android.widget.EditText", "Name", "edit_name");
        Name target = new PromptFixtures.TestName("//edit_name");
        List<ModelAction> actions = list(
                PromptFixtures.action(target, ActionType.MODEL_CLICK, edit),
                PromptFixtures.action(target, ActionType.MODEL_LONG_CLICK, edit),
                click(button("Beta", "btn_b")), click(button("Plain", "btn_plain")));
        String user = userText(ApePromptBuilder.VARIANT_V17, mop, state(0, actions), actions);
        assertEquals(user, "[DM]", markerOf(lineWith(user, "\"Name\"")));
        assertEquals(user, "[M]", markerOf(lineWith(user, "\"Beta\"")));
        assertEquals(user, "", markerOf(lineWith(user, "\"Plain\"")));
        assertTrue(user, user.contains("NEW state. 2/4 MOP."));
    }
}
