package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;

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
 * <p>{@link MopScorer#stateMopDensity} is exercised only for its data-null and
 * {@code activityHasMop}-false early-outs (INV-MOP-24), which return before any widget
 * resolution. The flagged-vs-total widget count needs actions resolved to live
 * {@code GUITreeNode}s (via {@code resolveAt}/{@code getResolvedNode}), which require an
 * Android runtime; that path is device-validated per {@code docs/20260622_investigacao_mop.md}
 * §7.5, mirroring how {@code FormCompletionTest} gates its node-dependent scenarios.
 */
public class MopScorerTest {

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
     * Scenario: Widget leads to MOP activity -> returns Config.mopWeightWtg.
     * "settings" click in MainActivity leads to SettingsActivity which has MOP.
     */
    @Test
    public void testScoreWtg_widgetLeadsToMopActivity() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "settings", "android.view.MenuItem", "com.example.SettingsActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("com.example.SettingsActivity");

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        int score = MopScorer.scoreWtg("com.example.MainActivity", "settings", data);
        assertEquals("Widget leading to MOP activity should get mopWeightWtg boost",
                Config.mopWeightWtg, score);
    }

    /**
     * Scenario: Widget leads to non-MOP activity -> returns 0.
     */
    @Test
    public void testScoreWtg_widgetLeadsToNonMopActivity() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "about", "android.widget.Button", "com.example.AboutActivity"));

        Set<String> mopActivities = new HashSet<>();
        // AboutActivity is NOT in mopActivities

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        int score = MopScorer.scoreWtg("com.example.MainActivity", "about", data);
        assertEquals("Widget leading to non-MOP activity should return 0", 0, score);
    }

    /**
     * Scenario: No WTG match for widget -> returns 0.
     */
    @Test
    public void testScoreWtg_noMatchForWidget() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "settings", "android.view.MenuItem", "com.example.SettingsActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("com.example.SettingsActivity");

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        int score = MopScorer.scoreWtg("com.example.MainActivity", "unknown_widget", data);
        assertEquals("Widget with no WTG match should return 0", 0, score);
    }

    /**
     * INV-WTG-02: scoreWtg returns 0 when MopData is null.
     */
    @Test
    public void testScoreWtg_nullData() {
        int score = MopScorer.scoreWtg("com.example.MainActivity", "settings", null);
        assertEquals("Null MopData should return 0", 0, score);
    }

    /**
     * INV-WTG-02: scoreWtg returns 0 when WTG data is absent (no transitions loaded).
     */
    @Test
    public void testScoreWtg_noWtgData() {
        // MopData with no WTG transitions
        MopData data = MopData.forTest(null, null, null);

        int score = MopScorer.scoreWtg("com.example.MainActivity", "settings", data);
        assertEquals("No WTG data should return 0", 0, score);
    }

    /**
     * scoreWtg returns 0 when activity is null.
     */
    @Test
    public void testScoreWtg_nullActivity() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "settings", "android.view.MenuItem", "com.example.SettingsActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("com.example.SettingsActivity");

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        int score = MopScorer.scoreWtg(null, "settings", data);
        assertEquals(0, score);
    }

    /**
     * scoreWtg returns 0 when shortId is null or empty.
     */
    @Test
    public void testScoreWtg_nullOrEmptyShortId() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "settings", "android.view.MenuItem", "com.example.SettingsActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("com.example.SettingsActivity");

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        assertEquals(0, MopScorer.scoreWtg("com.example.MainActivity", null, data));
        assertEquals(0, MopScorer.scoreWtg("com.example.MainActivity", "", data));
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
                "menu_item_cipher", "android.view.MenuItem",
                "br.unb.cic.cryptoapp.cipher.CipherActivity"));
        transitions.add(new MopData.WtgTransition(
                "menu_item_message_digest", "android.view.MenuItem",
                "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("br.unb.cic.cryptoapp.cipher.CipherActivity");
        // MessageDigestActivity NOT in mopActivities

        MopData data = buildData(
                "br.unb.cic.cryptoapp.MainActivity", transitions, mopActivities);

        assertEquals(Config.mopWeightWtg,
                MopScorer.scoreWtg("br.unb.cic.cryptoapp.MainActivity",
                        "menu_item_cipher", data));
        assertEquals(0,
                MopScorer.scoreWtg("br.unb.cic.cryptoapp.MainActivity",
                        "menu_item_message_digest", data));
    }

    /**
     * scoreWtg returns 0 when querying a different source activity.
     */
    @Test
    public void testScoreWtg_wrongSourceActivity() {
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "settings", "android.view.MenuItem", "com.example.SettingsActivity"));

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("com.example.SettingsActivity");

        MopData data = buildData("com.example.MainActivity", transitions, mopActivities);

        // Query from a different activity — no transitions registered there
        int score = MopScorer.scoreWtg("com.example.OtherActivity", "settings", data);
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

    /** Activity C with an OPTIONSMENU whose item's click handler directly reaches target. */
    private static MopData loadMenuMopFixture() throws Exception {
        String json = "{\"package\":\"p\",\"mainActivity\":\"p.C\",\"complete\":true," +
                "\"reachability\":[{\"className\":\"C\",\"methods\":[" +
                "{\"signature\":\"<C: void m()>\",\"reachesTarget\":true,\"directlyReachesTarget\":true}]}]," +
                "\"windows\":[{\"id\":1,\"type\":\"OPTIONSMENU\",\"name\":\"C#OptionsMenu\",\"widgets\":[" +
                "{\"idName\":\"mi\",\"type\":\"android.view.MenuItem\",\"listeners\":[" +
                "{\"eventType\":\"click\",\"handler\":\"<C: void m()>\"}]}]}]," +
                "\"transitions\":[],\"components\":{}}";
        return MopData.load(writeTempJson(json), null, null);
    }

    /** Widget b with click→MOP-direct and longClick→not (per-event-type maps). */
    private static MopData loadEventTypeFixture() throws Exception {
        String json = "{\"package\":\"p\",\"mainActivity\":\"p.C\",\"complete\":true," +
                "\"reachability\":[{\"className\":\"C\",\"methods\":[" +
                "{\"signature\":\"<C: void clk()>\",\"reachesTarget\":true,\"directlyReachesTarget\":true}]}]," +
                "\"windows\":[{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":[" +
                "{\"idName\":\"b\",\"type\":\"android.widget.Button\",\"listeners\":[" +
                "{\"eventType\":\"click\",\"handler\":\"<C: void clk()>\"}," +
                "{\"eventType\":\"longClick\",\"handler\":\"<C: void none()>\"}]}]}]," +
                "\"transitions\":[],\"components\":{}}";
        return MopData.load(writeTempJson(json), null, null);
    }

    @Test // 17.1
    public void testScoreOpenMenuBoostsWhenOptionsMenuHasMopWidget() throws Exception {
        MopData d = loadMenuMopFixture();
        assertEquals(Config.mopWeightOpenMenu, MopScorer.scoreOpenMenu("C", d));
    }

    @Test // 17.2
    public void testScoreOpenMenuZeroWhenActivityHasNoMopOptionsMenu() throws Exception {
        MopData d = loadMenuMopFixture();
        assertEquals(0, MopScorer.scoreOpenMenu("NoSuchActivity", d));
    }

    @Test // 17.3
    public void testScoreEventTypeAwareMatchesClick() throws Exception {
        MopData d = loadEventTypeFixture();
        assertEquals(Config.mopWeightDirect, MopScorer.score("C", "b", d, "click"));
        // longClick is unflagged on widget b; with the activity-level fallback removed the
        // event-type-specific miss scores 0 (discriminative-only).
        assertEquals(0, MopScorer.score("C", "b", d, "longClick"));
    }

    @Test // 17.4
    public void testScoreEventTypeNullFallsBackToAggregate() throws Exception {
        MopData d = loadEventTypeFixture();
        assertEquals(Config.mopWeightDirect, MopScorer.score("C", "b", d, null));
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
        assertEquals(0, MopScorer.score("a", "b", null, "click"));
        assertEquals(0, MopScorer.scoreOpenMenu("a", null));
        assertEquals(0, MopScorer.scoreWtg("a", "b", null));
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

        assertEquals(0, MopScorer.score("A", "plain", data, null));
        assertEquals(0, MopScorer.score("A", "absent", data, null));
        assertEquals(Config.mopWeightDirect, MopScorer.score("A", "flagged", data, null));
        assertEquals(0, MopScorer.score("B", "plain", data, null));
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

        assertEquals(Config.mopWeightDirect, MopScorer.score("A", "card", data, null));
        assertEquals(0, MopScorer.score("A", "inner", data, null));
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
    // mop-parser-fidelity (#0) 2.3(b): parser → scorer integration.
    // A "#"-suffixed WTG target window reduces to its base activity, so scoreWtg
    // finds activityHasMop(base) and returns mopWeightWtg (INV-WTG-04).
    // -------------------------------------------------------------------------

    @Test
    public void testScoreWtg_suffixedTargetReducedToBase() throws Exception {
        String reaches = "{\"className\":\"Tgt\",\"methods\":["
                + "{\"signature\":\"<Tgt: void enc()>\",\"reachesTarget\":true,\"directlyReachesTarget\":true}]}";
        String wins = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"Src\",\"widgets\":[]},"
                + "{\"id\":2,\"type\":\"ACTIVITY\",\"name\":\"Tgt#Dialog\",\"widgets\":["
                + "{\"idName\":\"go\",\"type\":\"android.widget.Button\",\"listeners\":["
                + "{\"eventType\":\"click\",\"handler\":\"<Tgt: void enc()>\"}]}]}";
        String trans = "{\"sourceId\":1,\"targetId\":2,\"events\":["
                + "{\"type\":\"click\",\"widgetId\":9,\"widgetClass\":\"x\",\"widgetName\":\"toCrypto\"}]}";
        MopData d = MopData.load(writeTempJson(jsonDoc(reaches, wins, trans)), null, null);
        assertEquals(Config.mopWeightWtg, MopScorer.scoreWtg("Src", "toCrypto", d));
    }

    private static String jsonDoc(String reach, String wins, String trans) {
        return "{\"package\":\"P\",\"mainActivity\":\"Src\",\"complete\":true"
                + ",\"reachability\":[" + reach + "]"
                + ",\"windows\":[" + wins + "]"
                + ",\"transitions\":[" + trans + "]"
                + ",\"components\":{}}";
    }
}
