package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MopScorer} (scoreWtg, score, scoreOpenMenu, eventTypeOf, stateMopDensity).
 *
 * Uses MopData.forTest() to construct test instances without android.util.JsonReader.
 *
 * <p>{@link MopScorer#stateMopDensity} is exercised for its data-null and
 * {@code activityHasMop}-false early-outs (INV-MOP-24) and for the activity-substrate floor
 * plus flagged-widget count (INV-MOP-24 amended): {@code resolvedStateOnActivity} injects
 * resolved {@code GUITreeNode}s into each action via the same Unsafe seam {@code stateOnActivity}
 * uses, so the short-id → {@code getWidget} resolution runs without an Android runtime.
 */
public class MopScorerTest {

    /**
     * The weights the scorer is asked to apply. Deliberately NOT the jar defaults: a test that
     * passes the default and asserts the default cannot tell an injected weight from a static one
     * it forgot to remove. These values exist nowhere else, so an assertion below can only hold if
     * the number travelled from the argument to the result. The jar's actual defaults are pinned
     * by {@code ScoringParamsDefaultsTest}, which is where that question belongs.
     */
    private static final int W_DIRECT = 917;
    private static final int W_TRANSITIVE = 613;
    private static final int W_WTG = 419;
    private static final int W_OPEN_MENU = 271;


    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Build a MopData with WTG transitions and MOP activity markers.
     *
     * @param sourceActivity  the source activity for transitions
     * @param transitions     list of WTG transitions from that activity
     * @param mopActivities   set of activities that have MOP-reachable methods
     */
    private static MopData buildData(String sourceActivity,
                                     List<MopData.WtgTransition> transitions,
                                     Set<String> mopActivities) {
        Map<String, List<MopData.WtgTransition>> wtg = new HashMap<>();
        if (sourceActivity != null && transitions != null) {
            wtg.put(sourceActivity, transitions);
        }
        // Build minimal widget data so activityHasMop() works
        Map<String, Map<String, MopData.Widget>> widgetData = new HashMap<>();
        for (String act : mopActivities) {
            Map<String, MopData.Widget> widgets = new HashMap<>();
            MopData.Widget flags = new MopData.Widget();
            flags.directMop = true;
            widgets.put("_dummy", flags);
            widgetData.put(act, widgets);
        }
        return MopData.forTest(widgetData, mopActivities, wtg);
    }

    // -------------------------------------------------------------------------
    // Task 4.4: scoreWtg Tests
    // -------------------------------------------------------------------------

    /**
     * Scenario: Widget leads to MOP activity -> returns W_WTG.
     * "settings" click in MainActivity leads to SettingsActivity which has MOP.
     */
    @Test
    public void testScoreWtg_widgetLeadsToMopActivity() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "settings",
                "com.example.SettingsActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("com.example.SettingsActivity");

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        int score = MopScorer.scoreWtg("com.example.MainActivity", "settings", data, W_WTG);
        assertEquals("Widget leading to MOP activity should get mopWeightWtg boost",
                W_WTG, score);
    }

    /**
     * Scenario: Widget leads to non-MOP activity -> returns 0.
     */
    @Test
    public void testScoreWtg_widgetLeadsToNonMopActivity() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "about",
                "com.example.AboutActivity"));

        Set<String> mopActivities = new HashSet<>();
        // AboutActivity is NOT in mopActivities

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        int score = MopScorer.scoreWtg("com.example.MainActivity", "about", data, W_WTG);
        assertEquals("Widget leading to non-MOP activity should return 0", 0, score);
    }

    /**
     * Scenario: No WTG match for widget -> returns 0.
     */
    @Test
    public void testScoreWtg_noMatchForWidget() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "settings",
                "com.example.SettingsActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("com.example.SettingsActivity");

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        int score = MopScorer.scoreWtg("com.example.MainActivity", "unknown_widget", data, W_WTG);
        assertEquals("Widget with no WTG match should return 0", 0, score);
    }

    /**
     * INV-WTG-02: scoreWtg returns 0 when MopData is null.
     */
    @Test
    public void testScoreWtg_nullData() {
        int score = MopScorer.scoreWtg("com.example.MainActivity", "settings", null, W_WTG);
        assertEquals("Null MopData should return 0", 0, score);
    }

    /**
     * INV-WTG-02: scoreWtg returns 0 when WTG data is absent (no transitions loaded).
     */
    @Test
    public void testScoreWtg_noWtgData() {
        // MopData with no WTG transitions
        MopData data = MopData.forTest(null, null, null);

        int score = MopScorer.scoreWtg("com.example.MainActivity", "settings", data, W_WTG);
        assertEquals("No WTG data should return 0", 0, score);
    }

    /**
     * scoreWtg returns 0 when activity is null.
     */
    @Test
    public void testScoreWtg_nullActivity() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "settings",
                "com.example.SettingsActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("com.example.SettingsActivity");

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        int score = MopScorer.scoreWtg(null, "settings", data, W_WTG);
        assertEquals(0, score);
    }

    /**
     * scoreWtg returns 0 when shortId is null or empty.
     */
    @Test
    public void testScoreWtg_nullOrEmptyShortId() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "settings",
                "com.example.SettingsActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("com.example.SettingsActivity");

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        assertEquals(0, MopScorer.scoreWtg("com.example.MainActivity", null, data, W_WTG));
        assertEquals(0, MopScorer.scoreWtg("com.example.MainActivity", "", data, W_WTG));
    }

    /**
     * Scenario: Widget leads to MOP activity via MenuItem click (cryptoapp pattern).
     * menu_item_cipher from MainActivity#OptionsMenu -> CipherActivity (has MOP).
     * Per INV-WTG-04 the menu edges are keyed under the base activity, so scoreWtg is
     * queried by base activity (the runtime is on MainActivity when the menu is open).
     */
    @Test
    public void testScoreWtg_menuItemToMopActivity() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "menu_item_cipher",
                "br.unb.cic.cryptoapp.cipher.CipherActivity"));
        transitions.add(new MopData.WtgTransition(
                "menu_item_message_digest",
                "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("br.unb.cic.cryptoapp.cipher.CipherActivity");
        // MessageDigestActivity NOT in mopActivities

        MopData data = buildData(
                "br.unb.cic.cryptoapp.MainActivity", transitions, mopActivities);

        assertEquals(W_WTG,
                MopScorer.scoreWtg("br.unb.cic.cryptoapp.MainActivity",
                        "menu_item_cipher", data, W_WTG));
        assertEquals(0,
                MopScorer.scoreWtg("br.unb.cic.cryptoapp.MainActivity",
                        "menu_item_message_digest", data, W_WTG));
    }

    /**
     * scoreWtg returns 0 when querying a different source activity.
     */
    @Test
    public void testScoreWtg_wrongSourceActivity() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "settings",
                "com.example.SettingsActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("com.example.SettingsActivity");

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        // Query from a different activity — no transitions registered there
        int score = MopScorer.scoreWtg("com.example.OtherActivity", "settings", data, W_WTG);
        assertEquals(0, score);
    }

    // =========================================================================
    // gh13 §17 — scoreOpenMenu (T1.2), eventType-aware score (T1.6), eventTypeOf
    // =========================================================================

    private static String writeTempJson(String json) throws Exception {
        File f = File.createTempFile("mopscorer", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) { w.write(json); }
        return f.getAbsolutePath();
    }

    /**
     * Activity {@code C} whose OPTIONSMENU is a MOP gateway.
     *
     * <p>The fixture states the gateway rather than deriving it: the cross-reference that used to
     * turn a menu item's handler into a flag runs host-side now, so what reaches the scorer is the
     * record {@code {activity: C, hasFlaggedWidget: true}} and its recomputation (INV-MOP-13).
     * Which handler made the menu item MOP-reaching is no longer a question this suite can ask,
     * and pretending otherwise would test the generator through the scorer.
     */
    private static MopData loadMenuMopFixture() throws Exception {
        String artifact = "{\"formatVersion\":1,\"package\":\"p\",\"mainActivity\":\"p.C\","
                + "\"optionsMenus\":[{\"activity\":\"C\",\"hasFlaggedWidget\":true}]}";
        return MopData.load(writeTempJson(artifact), null, null, new NoopSink());
    }

    /** Widget b with click→MOP-direct and longClick→not (per-event-type maps). */
    private static MopData loadEventTypeFixture() throws Exception {
        String artifact = "{\"formatVersion\":1,\"package\":\"p\",\"mainActivity\":\"p.C\","
                + "\"widgets\":{\"C\":{\"b\":{\"mop\":{\"click\":\"direct\",\"longclick\":\"none\"}}}},"
                + "\"mopActivities\":[\"C\"]}";
        return MopData.load(writeTempJson(artifact), null, null, new NoopSink());
    }

    @Test // 17.1
    public void testScoreOpenMenuBoostsWhenOptionsMenuHasMopWidget() throws Exception {
        MopData d = loadMenuMopFixture();
        assertEquals(W_OPEN_MENU, MopScorer.scoreOpenMenu("C", d, W_OPEN_MENU));
    }

    @Test // 17.2
    public void testScoreOpenMenuZeroWhenActivityHasNoMopOptionsMenu() throws Exception {
        MopData d = loadMenuMopFixture();
        assertEquals(0, MopScorer.scoreOpenMenu("NoSuchActivity", d, W_OPEN_MENU));
    }

    @Test // 17.3
    public void testScoreEventTypeAwareMatchesClick() throws Exception {
        MopData d = loadEventTypeFixture();
        assertEquals(W_DIRECT, MopScorer.score("C", "b", d, "click", W_DIRECT, W_TRANSITIVE));
        // longClick is unflagged on widget b; with the activity-level fallback removed the
        // event-type-specific miss scores 0 (discriminative-only).
        assertEquals(0, MopScorer.score("C", "b", d, "longClick", W_DIRECT, W_TRANSITIVE));
    }

    @Test // 17.4
    public void testScoreEventTypeNullFallsBackToAggregate() throws Exception {
        MopData d = loadEventTypeFixture();
        assertEquals(W_DIRECT, MopScorer.score("C", "b", d, null, W_DIRECT, W_TRANSITIVE));
    }

    @Test // 17.5
    public void testEventTypeOfMapsActionTypes() {
        assertEquals("click", MopScorer.eventTypeOf(ActionType.MODEL_CLICK, null));
        assertEquals("longClick", MopScorer.eventTypeOf(ActionType.MODEL_LONG_CLICK, null));
        assertEquals("scroll", MopScorer.eventTypeOf(ActionType.MODEL_SCROLL_TOP_DOWN, null));
        assertNull(MopScorer.eventTypeOf(ActionType.MODEL_BACK, null));
        assertNull(MopScorer.eventTypeOf((ActionType) null, null));
    }

    @Test // 17.6
    public void testScoreReturnsZeroWhenMopDataNull() {
        assertEquals(0, MopScorer.score("a", "b", null, "click", W_DIRECT, W_TRANSITIVE));
        assertEquals(0, MopScorer.scoreOpenMenu("a", null, W_OPEN_MENU));
        assertEquals(0, MopScorer.scoreWtg("a", "b", null, W_WTG));
        assertEquals(0, MopScorer.stateMopDensity(null, null, 0));
    }

    @Test // 17.7
    public void testEventTypeOfSpinnerDetection() {
        assertEquals("itemSelected", MopScorer.eventTypeOf(ActionType.MODEL_CLICK, "android.widget.Spinner"));
        assertEquals("click", MopScorer.eventTypeOf(ActionType.MODEL_CLICK, "android.widget.EditText"));
    }

    @Test // 17.8 — density null guard (JVM-testable; populated-State path is unchanged legacy code, covered by §22 integration)
    public void testStateMopDensityNullSafe() {
        // data==null short-circuits before touching the State (the regression-relevant guard I added).
        assertEquals(0, MopScorer.stateMopDensity(null, null, 0));
    }

    // =========================================================================
    // Discriminative-only scoring (no activity fallback) + B3 containment foundation
    // =========================================================================

    /** Build MopData where the given activity has the listed widgets with explicit directMop flags. */
    private static MopData buildWidgetData(String activity, Map<String, Boolean> idToDirectMop) {
        Map<String, Map<String, MopData.Widget>> widgetData = new HashMap<>();
        Map<String, MopData.Widget> widgets = new HashMap<>();
        for (Map.Entry<String, Boolean> e : idToDirectMop.entrySet()) {
            MopData.Widget w = new MopData.Widget();
            w.directMop = e.getValue();
            widgets.put(e.getKey(), w);
        }
        widgetData.put(activity, widgets);
        Set<String> mopActivities = new HashSet<>();
        mopActivities.add(activity);
        return MopData.forTest(widgetData, mopActivities, null);
    }

    /**
     * Discriminative-only scoring: a resolved-but-unflagged widget and a null widget both
     * score 0 even on a MOP-bearing activity (the +100 activity fallback is removed); only
     * the flagged widget earns its boost; a non-MOP activity also yields 0.
     */
    @Test
    public void testScoreResolvedButUnflaggedScoresZero() {
        Map<String, Boolean> ids = new HashMap<>();
        ids.put("plain", false);   // resolves, no MOP flag
        ids.put("flagged", true);  // direct MOP
        MopData data = buildWidgetData("A", ids);

        assertEquals(0, MopScorer.score("A", "plain", data, null, W_DIRECT, W_TRANSITIVE));
        assertEquals(0, MopScorer.score("A", "absent", data, null, W_DIRECT, W_TRANSITIVE));
        assertEquals(W_DIRECT, MopScorer.score("A", "flagged", data, null, W_DIRECT, W_TRANSITIVE));
        assertEquals(0, MopScorer.score("B", "plain", data, null, W_DIRECT, W_TRANSITIVE));
    }

    /**
     * B3 foundation: static analysis may flag a container id while the runtime
     * resolves an inner child id. Scoring the container id recovers the direct
     * boost that the child id alone would miss — which is what the StatefulAgent
     * containment loop does by scoring {child} ∪ ancestors(≤2) ∪ descendants(≤2).
     * The full GUITreeNode traversal needs a live tree and is device-gated (6.3).
     * The unflagged child scores 0 (the activity fallback is removed).
     */
    @Test
    public void testScoreByContainerVsChildId() {
        Map<String, Boolean> ids = new HashMap<>();
        ids.put("card", true);    // container flagged by static analysis
        ids.put("inner", false);  // child the runtime resolves, unflagged
        MopData data = buildWidgetData("A", ids);

        assertEquals(W_DIRECT, MopScorer.score("A", "card", data, null, W_DIRECT, W_TRANSITIVE));
        assertEquals(0, MopScorer.score("A", "inner", data, null, W_DIRECT, W_TRANSITIVE));
    }

    // -------------------------------------------------------------------------
    // stateMopDensity (INV-MOP-24) — JVM-testable early-outs. The flagged-count
    // path needs resolved GUITreeNodes and is device-gated (see class javadoc).
    // -------------------------------------------------------------------------

    /**
     * Allocate a State (bypassing its ComponentName-requiring constructor, as FormCompletionTest
     * does) on {@code activity}, carrying {@code numActions} valid MODEL_CLICK actions. The
     * StateKey is likewise allocated and its {@code activity} field set so {@code getActivity()}
     * works without an Android runtime.
     */
    private static State stateOnActivity(String activity, int numActions) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);

        StateKey key = (StateKey) allocate.invoke(unsafe, StateKey.class);
        Field activityField = StateKey.class.getDeclaredField("activity");
        activityField.setAccessible(true);
        activityField.set(key, activity);

        State state = (State) allocate.invoke(unsafe, State.class);
        Field stateKeyField = State.class.getDeclaredField("stateKey");
        stateKeyField.setAccessible(true);
        stateKeyField.set(state, key);

        ModelAction[] actions = new ModelAction[numActions];
        for (int i = 0; i < numActions; i++) {
            actions[i] = new ModelAction(null, ActionType.MODEL_CLICK);
            actions[i].setValid(true);
        }
        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, actions);
        return state;
    }

    /** INV-MOP-24: null MopData short-circuits to 0 before touching the State. */
    @Test
    public void testStateMopDensityNullDataIsZero() throws Exception {
        State dense = stateOnActivity("com.example.MopActivity", 10);
        assertEquals(0, MopScorer.stateMopDensity(dense, null, 1));
    }

    /**
     * INV-MOP-24: a dense screen (10 valid target-requiring actions) on an activity with no
     * MOP-reachable widgets scores 0 via the {@code activityHasMop} early-out — before any
     * widget resolution, so no live GUITreeNode is needed. The flagged-vs-total count on a
     * MOP-bearing activity is device-gated (class javadoc).
     */
    @Test
    public void testStateMopDensityDenseNonMopActivityScoresZero() throws Exception {
        MopData data = buildWidgetData("com.example.MopActivity",
                Collections.singletonMap("flagged", Boolean.TRUE));
        State denseNonMop = stateOnActivity("com.example.PlainActivity", 10);
        assertEquals(0, MopScorer.stateMopDensity(denseNonMop, data, 1));
    }

    // -------------------------------------------------------------------------
    // mop-activity-consumers 1.1 — activity-substrate floor: 1 + <flagged count>.
    // These drive the flagged-count path at the JVM level by injecting resolved
    // GUITreeNodes (resolvedNode + resovledTimestamp) into each action, the same
    // Unsafe seam stateOnActivity already uses — so getResolvedNode → extractShortId
    // → getWidget runs without an Android device.
    // -------------------------------------------------------------------------

    /**
     * State on {@code activity} whose actions each resolve (at timestamp 1) to a GUITreeNode
     * carrying resource id {@code com.example:id/<shortId>}. Reuses the Unsafe allocation of
     * {@link #stateOnActivity} but additionally populates the resolve fields so the density
     * walk exercises the real short-id → widget lookup.
     */
    private static State resolvedStateOnActivity(String activity, String... shortIds) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);

        StateKey key = (StateKey) allocate.invoke(unsafe, StateKey.class);
        Field activityField = StateKey.class.getDeclaredField("activity");
        activityField.setAccessible(true);
        activityField.set(key, activity);

        State state = (State) allocate.invoke(unsafe, State.class);
        Field stateKeyField = State.class.getDeclaredField("stateKey");
        stateKeyField.setAccessible(true);
        stateKeyField.set(state, key);

        Field resolvedNodeField = ModelAction.class.getDeclaredField("resolvedNode");
        resolvedNodeField.setAccessible(true);
        Field tsField = ModelAction.class.getDeclaredField("resovledTimestamp");
        tsField.setAccessible(true);

        ModelAction[] actions = new ModelAction[shortIds.length];
        for (int i = 0; i < shortIds.length; i++) {
            ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
            a.setValid(true);
            GUITreeNode node = new GUITreeNode(null);
            node.setResourceID("com.example:id/" + shortIds[i]);
            resolvedNodeField.set(a, node);
            tsField.setInt(a, 1);
            actions[i] = a;
        }
        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, actions);
        return state;
    }

    /** Widget map for the density tests: {@code flagged} directMop, {@code plain} unflagged. */
    private static MopData densityData(String activity) {
        Map<String, Boolean> ids = new HashMap<>();
        ids.put("flagged", Boolean.TRUE);
        ids.put("plain", Boolean.FALSE);
        return buildWidgetData(activity, ids);
    }

    /** 1.1(a): A′-only activity (MOP-bearing, zero flagged widgets resolve) → floor 1. */
    @Test
    public void testStateMopDensityActivityFloorNoFlaggedWidgets() throws Exception {
        MopData data = densityData("com.example.MopActivity");
        State aPrimeOnly = resolvedStateOnActivity(
                "com.example.MopActivity", "plain", "plain", "plain");
        assertEquals(1, MopScorer.stateMopDensity(aPrimeOnly, data, 1));
    }

    /** 1.1(b): 2 flagged widgets on a MOP activity → 1 + 2 = 3. */
    @Test
    public void testStateMopDensityFloorPlusFlaggedCount() throws Exception {
        MopData data = densityData("com.example.MopActivity");
        State st = resolvedStateOnActivity(
                "com.example.MopActivity", "flagged", "plain", "flagged");
        assertEquals(3, MopScorer.stateMopDensity(st, data, 1));
    }

    /** 1.1(c): non-MOP activity → 0 via the early-out (no widget resolution), even if a
     * flagged id would resolve. */
    @Test
    public void testStateMopDensityNonMopActivityStaysZero() throws Exception {
        MopData data = densityData("com.example.MopActivity");
        State nonMop = resolvedStateOnActivity(
                "com.example.PlainActivity", "flagged", "flagged");
        assertEquals(0, MopScorer.stateMopDensity(nonMop, data, 1));
    }

    /** 1.1(d): ordering guard — widget-flagged (2) > A′-only floor (1) > non-MOP (0). */
    @Test
    public void testStateMopDensityOrderingWidgetAboveFloorAboveZero() throws Exception {
        MopData data = densityData("com.example.MopActivity");
        int widget = MopScorer.stateMopDensity(
                resolvedStateOnActivity("com.example.MopActivity", "flagged"), data, 1);
        int aPrime = MopScorer.stateMopDensity(
                resolvedStateOnActivity("com.example.MopActivity", "plain"), data, 1);
        int nonMop = MopScorer.stateMopDensity(
                resolvedStateOnActivity("com.example.PlainActivity", "flagged"), data, 1);
        assertEquals(2, widget);
        assertEquals(1, aPrime);
        assertEquals(0, nonMop);
        assertTrue(widget > aPrime && aPrime > nonMop);
    }

    // The parser → scorer integration test that closed this file drove a "#"-suffixed WTG target
    // through the old parser to show it reduced to its base activity before scoreWtg looked it up.
    // Both ends of that reduction are now the generator's (INV-DRV-03/INV-WTG-04) and its permanent
    // test is `gh96`'s test_wtg_click_only_deduped_base_keyed; on this side the wire arrives already
    // base-keyed, so what is left to assert is scoreWtg over a base-keyed edge, which every
    // forTest-built case above already does.
}
