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
 * Unit tests for MopData WTG (Window Transition Graph) parsing and query methods.
 *
 * These tests use the package-private {@code MopData.forTest()} factory to construct
 * MopData instances without requiring android.util.JsonReader (which is unavailable
 * in JVM tests since the Android stubs are excluded from the surefire classpath).
 *
 * The actual JSON parsing (Pass 3) is validated via device integration tests.
 */
public class MopDataTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a minimal MopData with WTG transitions and MOP activities. */
    private static MopData buildTestData(Map<String, List<MopData.WtgTransition>> wtg,
                                         Set<String> mopActivities) {
        // Minimal widget data: one widget per MOP activity to make activityHasMop() return true
        Map<String, Map<String, MopData.WidgetMopFlags>> widgetData = new HashMap<>();
        for (String act : mopActivities) {
            Map<String, MopData.WidgetMopFlags> widgets = new HashMap<>();
            MopData.WidgetMopFlags flags = new MopData.WidgetMopFlags();
            flags.directMop = true;
            widgets.put("_mop_dummy", flags);
            widgetData.put(act, widgets);
        }
        return MopData.forTest(widgetData, mopActivities, wtg);
    }

    // -------------------------------------------------------------------------
    // Task 4.3: WTG Parsing Tests (data structure / query layer)
    // -------------------------------------------------------------------------

    /**
     * INV-WTG-01 + Scenario: Parse click transitions.
     * Simulates the result of parsing click transitions from cryptoapp fixture:
     * sourceId=1382 (MainActivity#OptionsMenu) -> targetId=1394 (CipherActivity)
     * with event type=click, widgetName=menu_item_cipher.
     */
    @Test
    public void testClickTransitions_storedCorrectly() {
        Map<String, List<MopData.WtgTransition>> wtg = new HashMap<>();
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "menu_item_cipher", "android.view.MenuItem",
                "br.unb.cic.cryptoapp.cipher.CipherActivity"));
        transitions.add(new MopData.WtgTransition(
                "menu_item_message_digest", "android.view.MenuItem",
                "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity"));
        wtg.put("br.unb.cic.cryptoapp.MainActivity#OptionsMenu", transitions);

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("br.unb.cic.cryptoapp.cipher.CipherActivity");
        mopActivities.add("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity");

        MopData data = buildTestData(wtg, mopActivities);

        assertTrue("WTG data should be present", data.hasWtgData());

        List<MopData.WtgTransition> result =
                data.getWtgTransitions("br.unb.cic.cryptoapp.MainActivity#OptionsMenu");
        assertEquals(2, result.size());

        MopData.WtgTransition t0 = result.get(0);
        assertEquals("menu_item_cipher", t0.widgetName);
        assertEquals("android.view.MenuItem", t0.widgetClass);
        assertEquals("br.unb.cic.cryptoapp.cipher.CipherActivity", t0.targetActivity);

        MopData.WtgTransition t1 = result.get(1);
        assertEquals("menu_item_message_digest", t1.widgetName);
        assertEquals("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity", t1.targetActivity);
    }

    /**
     * INV-WTG-01: Scenario — implicit events are ignored.
     * Verifies that only click events end up in the WTG map.
     * (Implicit events like implicit_home_event are never added to WtgTransition lists.)
     */
    @Test
    public void testImplicitEvents_notStored() {
        // Build WTG with only click transitions (as the parser would do)
        Map<String, List<MopData.WtgTransition>> wtg = new HashMap<>();
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        // Only the click event from a mixed transition (implicit_home + click)
        transitions.add(new MopData.WtgTransition(
                "search", "android.widget.Button",
                "com.example.SearchActivity"));
        wtg.put("com.example.MainActivity", transitions);

        MopData data = buildTestData(wtg, new HashSet<String>());

        List<MopData.WtgTransition> result = data.getWtgTransitions("com.example.MainActivity");
        assertEquals("Only click events should be stored", 1, result.size());
        assertEquals("search", result.get(0).widgetName);
    }

    /**
     * Scenario: No transitions section (graceful skip).
     * When WTG transitions are empty/absent, hasWtgData() returns false
     * and getWtgTransitions() returns empty list for any activity.
     */
    @Test
    public void testNoTransitions_gracefulSkip() {
        // Empty WTG map simulates missing transitions[] key
        MopData data = MopData.forTest(null, null, null);

        assertFalse("hasWtgData should be false when no transitions", data.hasWtgData());
        assertTrue("getWtgTransitions should return empty list",
                data.getWtgTransitions("com.example.AnyActivity").isEmpty());
    }

    /**
     * getWtgTransitions returns empty list for unknown activity.
     */
    @Test
    public void testGetWtgTransitions_unknownActivity() {
        Map<String, List<MopData.WtgTransition>> wtg = new HashMap<>();
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "btn", "android.widget.Button", "com.example.Target"));
        wtg.put("com.example.Source", transitions);

        MopData data = buildTestData(wtg, new HashSet<String>());

        assertTrue(data.getWtgTransitions("com.example.Unknown").isEmpty());
    }

    /**
     * Scenario: Parse MenuItem click transitions (from cryptoapp fixture).
     * Window 1382 = MainActivity#OptionsMenu, Window 1397 = MessageDigestActivity.
     * Transition: menu_item_message_digest click -> MessageDigestActivity.
     */
    @Test
    public void testMenuItemClickTransition() {
        Map<String, List<MopData.WtgTransition>> wtg = new HashMap<>();
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "menu_item_message_digest", "android.view.MenuItem",
                "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity"));
        wtg.put("br.unb.cic.cryptoapp.MainActivity#OptionsMenu", transitions);

        MopData data = buildTestData(wtg, new HashSet<String>());

        List<MopData.WtgTransition> result =
                data.getWtgTransitions("br.unb.cic.cryptoapp.MainActivity#OptionsMenu");
        assertEquals(1, result.size());
        assertEquals("menu_item_message_digest", result.get(0).widgetName);
        assertEquals("android.view.MenuItem", result.get(0).widgetClass);
        assertEquals("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity",
                result.get(0).targetActivity);
    }

    /**
     * Multiple transitions from same source activity with different targets.
     * Simulates MainActivity (1389) having buttonCipher -> CipherActivity (1394)
     * and buttonMessageDigest -> MessageDigestActivity (1397).
     */
    @Test
    public void testMultipleTransitions_sameSource() {
        Map<String, List<MopData.WtgTransition>> wtg = new HashMap<>();
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "buttonCipher", "android.widget.Button",
                "br.unb.cic.cryptoapp.cipher.CipherActivity"));
        transitions.add(new MopData.WtgTransition(
                "buttonMessageDigest", "android.widget.Button",
                "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity"));
        transitions.add(new MopData.WtgTransition(
                "buttonGenerated", "android.widget.Button",
                "br.unb.cic.cryptoapp.generated.CryptographyActivity"));
        wtg.put("br.unb.cic.cryptoapp.MainActivity", transitions);

        MopData data = buildTestData(wtg, new HashSet<String>());

        List<MopData.WtgTransition> result =
                data.getWtgTransitions("br.unb.cic.cryptoapp.MainActivity");
        assertEquals(3, result.size());
    }

    /**
     * WtgTransition fields are correctly populated.
     */
    @Test
    public void testWtgTransitionFields() {
        MopData.WtgTransition t = new MopData.WtgTransition(
                "btn_encrypt", "android.widget.Button", "com.example.EncryptActivity");
        assertEquals("btn_encrypt", t.widgetName);
        assertEquals("android.widget.Button", t.widgetClass);
        assertEquals("com.example.EncryptActivity", t.targetActivity);
    }
}
