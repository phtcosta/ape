package com.android.commands.monkey.ape.utils;

import org.junit.Test;

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
        Map<String, Map<String, MopData.WidgetMopFlags>> widgetData = new HashMap<>();
        for (String act : mopActivities) {
            Map<String, MopData.WidgetMopFlags> widgets = new HashMap<>();
            MopData.WidgetMopFlags flags = new MopData.WidgetMopFlags();
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
}
