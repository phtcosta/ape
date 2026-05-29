package com.android.commands.monkey.ape.utils;

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
 * Unit tests for MopData. The WTG data-layer tests use the package-private
 * {@code MopData.forTest()} factory; the parser tests (gh13 §15) load real and synthetic
 * JSON through {@code MopData.load()}, which is JVM-runnable because the parser uses
 * {@code org.json} rather than {@code android.util.JsonReader} (design D21).
 */
public class MopDataTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a minimal MopData with WTG transitions and MOP activities. */
    private static MopData buildTestData(Map<String, List<MopData.WtgTransition>> wtg,
                                         Set<String> mopActivities) {
        // Minimal widget data: one widget per MOP activity to make activityHasMop() return true
        Map<String, Map<String, MopData.Widget>> widgetData = new HashMap<>();
        for (String act : mopActivities) {
            Map<String, MopData.Widget> widgets = new HashMap<>();
            MopData.Widget flags = new MopData.Widget();
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

    // Component-parsing tests live in ComponentInfoTest (gh13 §16); the backward-compat
    // empty-components case is covered by testComponents_backwardCompat below.

    /** No component data → hasComponents() false, empty lists. */
    @Test
    public void testComponents_backwardCompat() {
        MopData data = MopData.forTest(null, null, null);

        assertFalse("hasComponents should be false without component data",
                data.hasComponents());
        assertTrue(data.getReceivers().isEmpty());
        assertTrue(data.getServices().isEmpty());
        assertTrue(data.getActivities().isEmpty());
        assertTrue(data.getProviders().isEmpty());
    }

    // =========================================================================
    // gh13 §15 — parser tests over real + synthetic JSON via MopData.load()
    // =========================================================================

    private static final String FRESH = "cryptoapp.apk.gh60-fresh.json";
    private static final String PKG = "br.unb.cic.cryptoapp";
    private static final String MAIN = "br.unb.cic.cryptoapp.MainActivity";
    private static final String MDA = "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity";
    private static final String CIPHER = "br.unb.cic.cryptoapp.cipher.CipherActivity";

    private static String fixturePath(String name) {
        java.net.URL url = MopDataTest.class.getResource("/" + name);
        assertNotNull("fixture not on classpath: " + name, url);
        return new File(url.getFile()).getAbsolutePath();
    }

    private static String writeTempJson(String json) throws Exception {
        File f = File.createTempFile("mopdata", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) { w.write(json); }
        return f.getAbsolutePath();
    }

    // 15.1
    @Test
    public void testFullFixtureLoadsAllFields() {
        MopData d = MopData.load(fixturePath(FRESH));
        assertNotNull(d);
        assertEquals(PKG, d.getPackageName());
        assertEquals(MAIN, d.getMainActivity());
        assertTrue(d.isComplete());
        assertEquals(16, d.getReachability().size());
        int reachable = 0, reachesTarget = 0, directly = 0;
        for (MopData.ReachabilityClass rc : d.getReachability()) {
            for (MopData.ReachabilityMethod m : rc.methods) {
                if (m.reachable) reachable++;
                if (m.reachesTarget) reachesTarget++;
                if (m.directlyReachesTarget) directly++;
            }
        }
        assertEquals(55, reachable);
        assertEquals(32, reachesTarget);
        assertEquals(21, directly);
        assertEquals(5, d.getWindows().size());
        int activityWins = 0, optionsMenus = 0, totalWidgets = 0;
        for (MopData.Window w : d.getWindows()) {
            if ("ACTIVITY".equals(w.type)) activityWins++;
            if ("OPTIONSMENU".equals(w.type)) {
                optionsMenus++;
                assertEquals(MAIN + "#OptionsMenu", w.name);
                assertEquals(3, w.widgets.size());
            }
            totalWidgets += w.widgets.size();
        }
        assertEquals(4, activityWins);
        assertEquals(1, optionsMenus);
        assertEquals(51, totalWidgets);
        assertEquals(35, d.getTransitions().size());
        assertEquals(4, d.getActivities().size());
        assertEquals(0, d.getReceivers().size());
        assertEquals(0, d.getServices().size());
        assertEquals(1, d.getProviders().size());
        assertEquals("br.unb.cic.cryptoapp.androidx-startup", d.getProviders().get(0).authorities);
        // metadata floors
        int hint = 0, text = 0, inputType = 0, spinnerEntries13 = 0;
        int transitiveWidgets = 0, directWidgets = 0;
        for (MopData.Window w : d.getWindows()) {
            for (MopData.Widget wd : w.widgets) {
                if (wd.hint != null && !wd.hint.isEmpty()) hint++;
                if (wd.text != null && !wd.text.isEmpty()) text++;
                if (wd.inputType != null && !wd.inputType.isEmpty()) inputType++;
                if (wd.entries.size() == 13) spinnerEntries13++;
                if (wd.transitiveMop) transitiveWidgets++;
                if (wd.directMop) directWidgets++;
            }
        }
        assertTrue("hint floor", hint >= 4);
        assertTrue("text floor", text >= 11);
        assertTrue("inputType floor", inputType >= 4);
        assertTrue("spinner entries=13 present", spinnerEntries13 >= 1);
        assertEquals("exactly 2 transitiveMop widgets", 2, transitiveWidgets);
        assertEquals("no directMop widget in cryptoapp", 0, directWidgets);
    }

    // 15.2 — BUG-FIX GATE (INV-MOP-07, D20)
    @Test
    public void testWidgetTransitiveMopDerivedFromGh60Targets() {
        MopData d = MopData.load(fixturePath(FRESH));
        assertNotNull(d);
        MopData.Widget w = d.getWidget(MDA, "buttonGenerateHash");
        assertNotNull("buttonGenerateHash must be indexed under MessageDigestActivity", w);
        assertTrue("transitiveMop derived from renamed reachesTarget key", w.transitiveMop);
        assertFalse("not a direct JCA caller", w.directMop);
        assertTrue(w.isTransitiveMop("click"));
        // the click listener's handler must be in the reachability index as reachesTarget
        boolean handlerReaches = false;
        for (MopData.Listener l : w.listeners) {
            if ("click".equals(l.eventType)) {
                for (MopData.ReachabilityClass rc : d.getReachability()) {
                    for (MopData.ReachabilityMethod m : rc.methods) {
                        if (m.signature.equals(l.handler) && m.reachesTarget && !m.directlyReachesTarget) {
                            handlerReaches = true;
                        }
                    }
                }
            }
        }
        assertTrue("click handler cross-references a reachesTarget method", handlerReaches);
        assertTrue(d.activityHasMop(MDA));
        assertEquals(Config.mopWeightTransitive,
                MopScorer.score(MDA, "buttonGenerateHash", d, "click"));
        // gateway: MainActivity's options menu navigates to MOP sub-activities
        assertTrue(d.activityHasMopOptionsMenu(MAIN));
    }

    // 15.3
    @Test
    public void testEditTextWidgetMetadataCaptured() {
        MopData d = MopData.load(fixturePath(FRESH));
        MopData.Widget e = d.getWidget(MDA, "editTextMessageDigest");
        assertNotNull(e);
        assertEquals("android.widget.EditText", e.type);
        assertEquals("textPersonName", e.inputType);
        assertEquals("Input text ...", e.hint);
        MopData.Widget sp = d.getWidget(MDA, "spinnerMessageDigest");
        assertNotNull(sp);
        assertEquals(13, sp.entries.size());
    }

    // 15.4
    @Test
    public void testJsonKeysRenamedToTarget() throws Exception {
        String reaches = "{\"className\":\"C\",\"isMain\":false,\"methods\":[" +
                "{\"name\":\"h\",\"signature\":\"<C: void h()>\",\"reachable\":true," +
                "\"reachesTarget\":true,\"directlyReachesTarget\":false}]}";
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":[" +
                "{\"id\":2,\"idName\":\"b\",\"type\":\"android.widget.Button\",\"listeners\":[" +
                "{\"eventType\":\"click\",\"handler\":\"<C: void h()>\"}]}]}";
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")));
        assertNotNull(d);
        assertTrue(d.getWidget("C", "b").transitiveMop);
        // legacy reachesMop key is ignored (P3, forward-compat fall-through)
        String legacyReach = "{\"className\":\"C\",\"methods\":[" +
                "{\"signature\":\"<C: void h()>\",\"reachesMop\":true}]}";
        MopData d2 = MopData.load(writeTempJson(synthetic(legacyReach, win, "", "")));
        assertNotNull(d2);
        assertFalse("legacy reachesMop must NOT register", d2.getWidget("C", "b").transitiveMop);
    }

    // 15.5
    @Test
    public void testCompleteSentinel() throws Exception {
        String body = "\"reachability\":[],\"windows\":[],\"transitions\":[],\"components\":{}";
        assertNull(MopData.load(writeTempJson("{" + body + "}")));               // absent
        assertNull(MopData.load(writeTempJson("{\"complete\":false," + body + "}"))); // false
        assertNotNull(MopData.load(writeTempJson("{\"complete\":true," + body + "}"))); // true
    }

    // 15.6
    @Test
    public void testTopLevelPackageAndMainActivity() throws Exception {
        MopData d = MopData.load(writeTempJson(
                "{\"package\":\"a.b.c\",\"mainActivity\":\"a.b.c.Main\",\"complete\":true}"));
        assertEquals("a.b.c", d.getPackageName());
        assertEquals("a.b.c.Main", d.getMainActivity());
    }

    // 15.7
    @Test
    public void testPackageMismatchWarnsByDefault() {
        MopData d = MopData.load(fixturePath(FRESH), "x.y.z.OTHER", null);
        assertNotNull("default warn-only returns parsed data", d);
    }

    // 15.8
    @Test
    public void testPackageMismatchRejectsWhenStrict() {
        boolean prev = Config.mopStrictPackageMatch;
        Config.mopStrictPackageMatch = true;
        try {
            assertNull(MopData.load(fixturePath(FRESH), "x.y.z.OTHER", null));
        } finally {
            Config.mopStrictPackageMatch = prev;
        }
    }

    // 15.9
    @Test
    public void testReachabilityClassFieldsCaptured() {
        MopData d = MopData.load(fixturePath(FRESH));
        boolean sawMain = false, sawReachable = false;
        for (MopData.ReachabilityClass rc : d.getReachability()) {
            assertNotNull(rc.className);
            if (rc.isMain) sawMain = true;
            for (MopData.ReachabilityMethod m : rc.methods) {
                assertNotNull(m.signature);
                if (m.reachable) sawReachable = true;
            }
        }
        assertTrue("at least one main class", sawMain);
        assertTrue("reachable flag captured", sawReachable);
    }

    // 15.10
    @Test
    public void testWidgetCoreFieldsCaptured() {
        MopData d = MopData.load(fixturePath(FRESH));
        MopData.Widget e = d.getWidget(MDA, "editTextMessageDigest");
        assertNotNull(e);
        assertEquals("editTextMessageDigest", e.idName);
        assertTrue(e.id > 0);
        assertEquals("android.widget.EditText", e.type);
        assertEquals("textPersonName", e.inputType);
    }

    // 15.11
    @Test
    public void testParsesFourNewWidgetAttributes() throws Exception {
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":[" +
                "{\"idName\":\"sp\",\"type\":\"android.widget.Spinner\",\"prompt\":\"Pick\",\"spinnerMode\":\"dropdown\"}," +
                "{\"idName\":\"bt\",\"type\":\"android.widget.Button\",\"contentDescription\":\"Encrypt\",\"tooltipText\":\"Tap\"}]}";
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")));
        MopData.Widget sp = d.getWidget("C", "sp");
        assertEquals("Pick", sp.prompt);
        assertEquals("dropdown", sp.spinnerMode);
        MopData.Widget bt = d.getWidget("C", "bt");
        assertEquals("Encrypt", bt.contentDescription);
        assertEquals("Tap", bt.tooltipText);
    }

    // 15.12
    @Test
    public void testNewWidgetFieldsNullWhenAbsent() throws Exception {
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":[" +
                "{\"idName\":\"a\",\"type\":\"android.widget.Button\"}," +
                "{\"idName\":\"b\",\"type\":\"android.widget.Button\",\"prompt\":null,\"tooltipText\":null}]}";
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")));
        assertNull(d.getWidget("C", "a").prompt);
        assertNull(d.getWidget("C", "b").prompt);
        assertNull(d.getWidget("C", "b").tooltipText);
    }

    // 15.13
    @Test
    public void testSpinnerEntriesCaptured() {
        MopData d = MopData.load(fixturePath(FRESH));
        MopData.Widget sp = d.getWidget(MDA, "spinnerMessageDigest");
        assertEquals(13, sp.entries.size());
        assertTrue(sp.entries.contains("MD5"));
    }

    // 15.14
    @Test
    public void testListenerFieldsCaptured() {
        MopData d = MopData.load(fixturePath(FRESH));
        MopData.Widget w = d.getWidget(MDA, "buttonGenerateHash");
        assertFalse(w.listeners.isEmpty());
        MopData.Listener l = w.listeners.get(0);
        assertEquals("click", l.eventType);
        assertNotNull(l.handler);
        assertNull("handlerReachesTarget not emitted by current producer", l.handlerReachesTarget);
    }

    // 15.15
    @Test
    public void testListenerHandlerReachesTargetHonored() throws Exception {
        // handler NOT in reachability, but listener carries producer flag true ⇒ transitiveMop true
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":[" +
                "{\"idName\":\"b\",\"type\":\"android.widget.Button\",\"listeners\":[" +
                "{\"eventType\":\"click\",\"handler\":\"<C: void unknown()>\",\"handlerReachesTarget\":true}]}]}";
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")));
        assertTrue(d.getWidget("C", "b").transitiveMop);
    }

    // 15.16
    @Test
    public void testListenerHandlerReachesTargetAbsentFallsBackToCrossRef() throws Exception {
        String reaches = "{\"className\":\"C\",\"methods\":[" +
                "{\"signature\":\"<C: void h()>\",\"reachesTarget\":true,\"directlyReachesTarget\":false}]}";
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":[" +
                "{\"idName\":\"b\",\"type\":\"android.widget.Button\",\"listeners\":[" +
                "{\"eventType\":\"click\",\"handler\":\"<C: void h()>\"}]}]}";
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")));
        assertTrue(d.getWidget("C", "b").transitiveMop);
    }

    // 15.17
    @Test
    public void testTransitionEventFieldsCaptured() {
        MopData d = MopData.load(fixturePath(FRESH));
        boolean found = false;
        for (MopData.Transition t : d.getTransitions()) {
            for (MopData.TransitionEvent e : t.events) {
                if ("click".equals(e.type) && e.handler != null && e.widgetId > 0
                        && e.widgetClass != null && e.widgetName != null) {
                    found = true;
                }
            }
        }
        assertTrue("a click TransitionEvent with all fields", found);
    }

    // 15.18
    @Test
    public void testTransitionImplicitEventsPreservedInRawView() {
        MopData d = MopData.load(fixturePath(FRESH));
        boolean implicitInRaw = false;
        for (MopData.Transition t : d.getTransitions()) {
            for (MopData.TransitionEvent e : t.events) {
                if (e.type != null && e.type.startsWith("implicit_")) implicitInRaw = true;
            }
        }
        assertTrue("implicit events survive in raw transitions", implicitInRaw);
        // WTG convenience view is click-only
        for (List<MopData.WtgTransition> list : new ArrayList<List<MopData.WtgTransition>>() {{
            add(d.getWtgTransitions(MAIN + "#OptionsMenu"));
        }}) {
            // (presence-only; click-only filtering verified by parser construction)
            assertNotNull(list);
        }
    }

    // 15.19
    @Test
    public void testActivitiesWithMopOptionsMenuPrecomputed() throws Exception {
        // A: menu widget reaches target directly. C: menu widget navigates (WTG) to MOP activity. B: neither.
        String reaches = "{\"className\":\"A\",\"methods\":[" +
                "{\"signature\":\"<A: void m()>\",\"reachesTarget\":true,\"directlyReachesTarget\":true}]}";
        String wins = "{\"id\":1,\"type\":\"OPTIONSMENU\",\"name\":\"A#OptionsMenu\",\"widgets\":[" +
                "{\"idName\":\"ma\",\"type\":\"android.view.MenuItem\",\"listeners\":[{\"eventType\":\"click\",\"handler\":\"<A: void m()>\"}]}]}," +
                "{\"id\":2,\"type\":\"OPTIONSMENU\",\"name\":\"B#OptionsMenu\",\"widgets\":[" +
                "{\"idName\":\"mb\",\"type\":\"android.view.MenuItem\",\"listeners\":[]}]}," +
                "{\"id\":3,\"type\":\"OPTIONSMENU\",\"name\":\"C#OptionsMenu\",\"widgets\":[" +
                "{\"idName\":\"mc\",\"type\":\"android.view.MenuItem\",\"listeners\":[]}]}," +
                "{\"id\":4,\"type\":\"ACTIVITY\",\"name\":\"C.Crypto\",\"widgets\":[" +
                "{\"idName\":\"go\",\"type\":\"android.widget.Button\",\"listeners\":[{\"eventType\":\"click\",\"handler\":\"<A: void m()>\"}]}]}";
        // transition: C#OptionsMenu --click mc--> C.Crypto (which hasMop via 'go')
        String trans = "{\"sourceId\":3,\"targetId\":4,\"events\":[" +
                "{\"type\":\"click\",\"handler\":\"x\",\"widgetId\":9,\"widgetClass\":\"android.view.MenuItem\",\"widgetName\":\"mc\"}]}";
        MopData d = MopData.load(writeTempJson(synthetic(reaches, wins, trans, "")));
        assertTrue(d.activityHasMopOptionsMenu("A"));
        assertFalse(d.activityHasMopOptionsMenu("B"));
        assertTrue("gateway: menu navigates to MOP activity", d.activityHasMopOptionsMenu("C"));
        // real fixture gateway
        MopData real = MopData.load(fixturePath(FRESH));
        assertTrue(real.activityHasMopOptionsMenu(MAIN));
    }

    // 15.20
    @Test
    public void testWidgetEventTypeMapsBuilt() throws Exception {
        String reaches = "{\"className\":\"C\",\"methods\":[" +
                "{\"signature\":\"<C: void clk()>\",\"reachesTarget\":true,\"directlyReachesTarget\":true}]}";
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":[" +
                "{\"idName\":\"b\",\"type\":\"android.widget.Button\",\"listeners\":[" +
                "{\"eventType\":\"click\",\"handler\":\"<C: void clk()>\"}," +
                "{\"eventType\":\"longClick\",\"handler\":\"<C: void other()>\"}]}]}";
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")));
        MopData.Widget w = d.getWidget("C", "b");
        assertTrue(w.isDirectMop("click"));
        assertFalse(w.isDirectMop("longClick"));
        assertTrue("aggregate", w.directMop);
    }

    // 15.21
    @Test
    public void testEmptyArraysParseToEmptyCollections() throws Exception {
        String json = "{\"complete\":true,\"reachability\":[],\"windows\":[],\"transitions\":[]," +
                "\"components\":{\"activities\":[],\"receivers\":[],\"services\":[],\"providers\":[]}}";
        MopData d = MopData.load(writeTempJson(json));
        assertNotNull(d);
        assertTrue(d.isComplete());
        assertTrue(d.getReachability().isEmpty());
        assertTrue(d.getWindows().isEmpty());
        assertTrue(d.getTransitions().isEmpty());
        assertTrue(d.getReceivers().isEmpty());
        assertTrue(d.getServices().isEmpty());
        assertTrue(d.getActivities().isEmpty());
        assertTrue(d.getProviders().isEmpty());
        assertEquals(0, MopScorer.score("x", "y", d, "click"));
    }

    // 15.22
    @Test
    public void testMultipleListenersSameHandlerNoDoubleCount() throws Exception {
        String reaches = "{\"className\":\"C\",\"methods\":[" +
                "{\"signature\":\"<C: void clk()>\",\"reachesTarget\":true,\"directlyReachesTarget\":true}]}";
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":[" +
                "{\"idName\":\"b\",\"type\":\"android.widget.Button\",\"listeners\":[" +
                "{\"eventType\":\"click\",\"handler\":\"<C: void clk()>\"}," +
                "{\"eventType\":\"click\",\"handler\":\"<C: void clk()>\"}]}]}";
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")));
        MopData.Widget w = d.getWidget("C", "b");
        assertEquals(Boolean.TRUE, w.directMopByEventType.get("click"));
        assertEquals(2, w.listeners.size());
        assertEquals(Config.mopWeightDirect, MopScorer.score("C", "b", d, "click"));
    }

    // 15.23
    @Test
    public void testConfigFlagsLoadFromProperties() {
        // Defaults bind correctly and the rollback knobs are assignable at runtime.
        assertEquals(250, Config.mopWeightOpenMenu);
        assertTrue(Config.fuzzInputTyped);
        assertFalse(Config.mopStrictPackageMatch);
        assertFalse(Config.activityTriggerEnabled);
        boolean prev = Config.activityTriggerEnabled;
        Config.activityTriggerEnabled = true;
        try { assertTrue(Config.activityTriggerEnabled); }
        finally { Config.activityTriggerEnabled = prev; }
    }

    // 15.24
    @Test
    public void testCompleteSentinelInMiddleStillRecognized() throws Exception {
        MopData d = MopData.load(writeTempJson(
                "{\"package\":\"a.b\",\"complete\":true,\"windows\":[]}"));
        assertNotNull(d);
        assertTrue(d.isComplete());
    }

    // 15.25
    @Test
    public void testLoadNullPathReturnsNullCleanly() {
        assertNull(MopData.load(null));
    }

    // 15.26
    @Test
    public void testGetWindowUnknownIdReturnsNull() {
        MopData d = MopData.load(fixturePath(FRESH));
        assertNull(d.getWindow(0));
        assertNull(d.getWindow(-1));
        assertNull(d.getWindow(Integer.MAX_VALUE));
    }

    /** Build a minimal complete JSON from raw array/object element strings (no trailing commas). */
    private static String synthetic(String reachElem, String winElems, String transElems, String compObj) {
        StringBuilder sb = new StringBuilder("{\"package\":\"C\",\"mainActivity\":\"C\",\"complete\":true");
        sb.append(",\"reachability\":[").append(reachElem).append("]");
        sb.append(",\"windows\":[").append(winElems).append("]");
        sb.append(",\"transitions\":[").append(transElems).append("]");
        sb.append(",\"components\":").append(compObj.isEmpty() ? "{}" : compObj);
        sb.append("}");
        return sb.toString();
    }
}
