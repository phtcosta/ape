package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.model.ActionType;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MopScorer#scoreWtg(String, String, MopData)}.
 *
 * Uses MopData.forTest() to construct test instances without android.util.JsonReader.
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
                "br.unb.cic.cryptoapp.MainActivity#OptionsMenu", transitions, mopActivities);

        assertEquals(Config.mopWeightWtg,
                MopScorer.scoreWtg("br.unb.cic.cryptoapp.MainActivity#OptionsMenu",
                        "menu_item_cipher", data));
        assertEquals(0,
                MopScorer.scoreWtg("br.unb.cic.cryptoapp.MainActivity#OptionsMenu",
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
        return MopData.load(writeTempJson(json));
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
        return MopData.load(writeTempJson(json));
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
        assertEquals(0, MopScorer.stateMopDensity(null, null));
    }

    @Test // 17.7
    public void testEventTypeOfSpinnerDetection() {
        assertEquals("itemSelected", MopScorer.eventTypeOf(ActionType.MODEL_CLICK, "android.widget.Spinner"));
        assertEquals("click", MopScorer.eventTypeOf(ActionType.MODEL_CLICK, "android.widget.EditText"));
    }

    @Test // 17.8 — density null guard (JVM-testable; populated-State path is unchanged legacy code, covered by §22 integration)
    public void testStateMopDensityNullSafe() {
        // data==null short-circuits before touching the State (the regression-relevant guard I added).
        assertEquals(0, MopScorer.stateMopDensity(null, null));
    }
}
