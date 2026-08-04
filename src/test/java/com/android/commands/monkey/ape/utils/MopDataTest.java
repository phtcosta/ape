package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintStream;
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
 * JSON through {@code MopData.load(, null, null)}, which is JVM-runnable because the parser uses
 * {@code org.json} rather than {@code android.util.JsonReader} (design D21).
 */
public class MopDataTest {

    /** The strict-package gate is a plan value, so loading needs a plan in effect. */
    @Before
    public void installMopPlan() {
        TestRunSpecs.installMop();
    }

    @After
    public void clearPlan() {
        RunContext.resetForTest();
    }


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
     * INV-WTG-01/04 + Scenario: Parse click transitions.
     * Simulates the result of parsing click transitions from the cryptoapp fixture:
     * the MainActivity#OptionsMenu-sourced edges are keyed under the base activity
     * MainActivity (INV-WTG-04), so the consumer queries by base activity.
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
        wtg.put("br.unb.cic.cryptoapp.MainActivity", transitions);

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("br.unb.cic.cryptoapp.cipher.CipherActivity");
        mopActivities.add("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity");

        MopData data = buildTestData(wtg, mopActivities);

        assertTrue("WTG data should be present", data.hasWtgData());

        List<MopData.WtgTransition> result =
                data.getWtgTransitions("br.unb.cic.cryptoapp.MainActivity");
        assertEquals(2, result.size());
        assertTrue("suffixed key no longer used (INV-WTG-04)",
                data.getWtgTransitions("br.unb.cic.cryptoapp.MainActivity#OptionsMenu").isEmpty());

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
     * Window 1382 = MainActivity#OptionsMenu reduces to base MainActivity (INV-WTG-04),
     * Window 1397 = MessageDigestActivity. Transition: menu_item_message_digest click ->
     * MessageDigestActivity, queried by the base source activity.
     */
    @Test
    public void testMenuItemClickTransition() {
        Map<String, List<MopData.WtgTransition>> wtg = new HashMap<>();
        List<MopData.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new MopData.WtgTransition(
                "menu_item_message_digest", "android.view.MenuItem",
                "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity"));
        wtg.put("br.unb.cic.cryptoapp.MainActivity", transitions);

        MopData data = buildTestData(wtg, new HashSet<String>());

        List<MopData.WtgTransition> result =
                data.getWtgTransitions("br.unb.cic.cryptoapp.MainActivity");
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
    // gh13 §15 — parser tests over real + synthetic JSON via MopData.load(, null, null)
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
        MopData d = MopData.load(fixturePath(FRESH), null, null);
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
        // FIX 2 (INV-MOP-30): 3, not the pre-fix 2 — the Execute button, whose D8 synthetic-lambda
        // handler (CryptographyActivity$$ExternalSyntheticLambda0:onClick) the exact join dropped, is
        // now recovered from CryptographyActivity's reaching lambda$setupExecuteButton$0 method.
        assertEquals("3 transitiveMop widgets (incl. the recovered Execute button)", 3, transitiveWidgets);
        assertEquals("no directMop widget in cryptoapp", 0, directWidgets);
    }

    // 15.2 — BUG-FIX GATE: transitiveMop derived from gh60 reachesTarget keys (D20)
    @Test
    public void testWidgetTransitiveMopDerivedFromGh60Targets() {
        MopData d = MopData.load(fixturePath(FRESH), null, null);
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
        MopData d = MopData.load(fixturePath(FRESH), null, null);
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
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")), null, null);
        assertNotNull(d);
        assertTrue(d.getWidget("C", "b").transitiveMop);
        // legacy reachesMop key is ignored (P3, forward-compat fall-through)
        String legacyReach = "{\"className\":\"C\",\"methods\":[" +
                "{\"signature\":\"<C: void h()>\",\"reachesMop\":true}]}";
        MopData d2 = MopData.load(writeTempJson(synthetic(legacyReach, win, "", "")), null, null);
        assertNotNull(d2);
        assertFalse("legacy reachesMop must NOT register", d2.getWidget("C", "b").transitiveMop);
    }

    // 15.5
    @Test
    public void testCompleteSentinel() throws Exception {
        String body = "\"reachability\":[],\"windows\":[],\"transitions\":[],\"components\":{}";
        assertNull(MopData.load(writeTempJson("{" + body + "}"), null, null));               // absent
        assertNull(MopData.load(writeTempJson("{\"complete\":false," + body + "}"), null, null)); // false
        assertNotNull(MopData.load(writeTempJson("{\"complete\":true," + body + "}"), null, null)); // true
    }

    // 15.6
    @Test
    public void testTopLevelPackageAndMainActivity() throws Exception {
        MopData d = MopData.load(writeTempJson(
                "{\"package\":\"a.b.c\",\"mainActivity\":\"a.b.c.Main\",\"complete\":true}"), null, null);
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
        TestRunSpecs.installMop("ape.mopStrictPackageMatch", "true");
        assertNull(MopData.load(fixturePath(FRESH), "x.y.z.OTHER", null));
    }

    // -------------------------------------------------------------------------
    // Task 5.3/5.4 — [APE-MOP-DATA] load status line (INV-MOP-21)
    // -------------------------------------------------------------------------

    /** Capture stdout (the [APE] Logger stream) while loading. */
    private static String captureLoad(String path, String pkg, String main) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer));
            MopData.load(path, pkg, main);
        } finally {
            System.setOut(original);
        }
        return buffer.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, from = 0;
        for (int i; (i = haystack.indexOf(needle, from)) >= 0; from = i + needle.length()) {
            count++;
        }
        return count;
    }

    // 5.4 — success line carries the counters, exactly one status line
    @Test
    public void testStatusLineLoadedEmitsCounters() {
        String out = captureLoad(fixturePath(FRESH), null, null);
        assertTrue(out, out.contains("[APE-MOP-DATA] status=loaded"));
        assertTrue(out, out.contains("package=" + PKG));
        assertTrue(out, out.contains("windows=5"));
        assertTrue(out, out.contains("widgets="));
        // FIX 2: flagged=3 (was 2) — the Execute button's desugared-lambda handler is now recovered.
        assertTrue(out, out.contains("flagged=3"));
        assertTrue(out, out.contains("droppedNoId="));
        assertTrue(out, out.contains("transitions=35"));
        // FIX 3 (INV-MOP-31): join diagnostics on the load line; 1 synthetic lambda recovered.
        assertTrue(out, out.contains("handlersUnmatched=5 syntheticLambda=1 recovered=1"));
        assertEquals("exactly one status line", 1, countOccurrences(out, "[APE-MOP-DATA] status="));
    }

    // 5.4 — rejection reasons
    @Test
    public void testStatusLineIncompleteReason() throws Exception {
        String out = captureLoad(writeTempJson("{\"package\":\"a.b\"}"), null, null);
        assertTrue(out, out.contains("[APE-MOP-DATA] status=rejected reason=incomplete"));
        assertEquals(1, countOccurrences(out, "[APE-MOP-DATA] status="));
    }

    @Test
    public void testStatusLineParseErrorReason() throws Exception {
        String out = captureLoad(writeTempJson("{ this is not valid json "), null, null);
        assertTrue(out, out.contains("[APE-MOP-DATA] status=rejected reason=parse-error"));
    }

    @Test
    public void testStatusLineFileMissingReason() {
        String out = captureLoad("/nonexistent/path/does-not-exist.json", null, null);
        assertTrue(out, out.contains("[APE-MOP-DATA] status=rejected reason=file-missing"));
    }

    @Test
    public void testStatusLinePackageMismatchReason() {
        TestRunSpecs.installMop("ape.mopStrictPackageMatch", "true");
        String out = captureLoad(fixturePath(FRESH), "x.y.z.OTHER", null);
        assertTrue(out, out.contains("[APE-MOP-DATA] status=rejected reason=package-mismatch"));
    }

    // 5.3 — unset path stays silent (spec: no status line required when MOP disabled)
    @Test
    public void testStatusLineNullPathEmitsNoLine() {
        String out = captureLoad(null, null, null);
        assertFalse(out, out.contains("[APE-MOP-DATA]"));
    }

    // 15.9
    @Test
    public void testReachabilityClassFieldsCaptured() {
        MopData d = MopData.load(fixturePath(FRESH), null, null);
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
        MopData d = MopData.load(fixturePath(FRESH), null, null);
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
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")), null, null);
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
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")), null, null);
        assertNull(d.getWidget("C", "a").prompt);
        assertNull(d.getWidget("C", "b").prompt);
        assertNull(d.getWidget("C", "b").tooltipText);
    }

    // 15.13
    @Test
    public void testSpinnerEntriesCaptured() {
        MopData d = MopData.load(fixturePath(FRESH), null, null);
        MopData.Widget sp = d.getWidget(MDA, "spinnerMessageDigest");
        assertEquals(13, sp.entries.size());
        assertTrue(sp.entries.contains("MD5"));
    }

    // 15.14
    @Test
    public void testListenerFieldsCaptured() {
        MopData d = MopData.load(fixturePath(FRESH), null, null);
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
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")), null, null);
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
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")), null, null);
        assertTrue(d.getWidget("C", "b").transitiveMop);
    }

    // 15.17
    @Test
    public void testTransitionEventFieldsCaptured() {
        MopData d = MopData.load(fixturePath(FRESH), null, null);
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
        MopData d = MopData.load(fixturePath(FRESH), null, null);
        boolean implicitInRaw = false;
        for (MopData.Transition t : d.getTransitions()) {
            for (MopData.TransitionEvent e : t.events) {
                if (e.type != null && e.type.startsWith("implicit_")) implicitInRaw = true;
            }
        }
        assertTrue("implicit events survive in raw transitions", implicitInRaw);
        // WTG convenience view is click-only and keyed by base activity (INV-WTG-04):
        // the menu-sourced edges land under MAIN, and the suffixed key is unused.
        assertFalse("click transitions stored under the base activity",
                d.getWtgTransitions(MAIN).isEmpty());
        assertTrue("suffixed key no longer populated",
                d.getWtgTransitions(MAIN + "#OptionsMenu").isEmpty());
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
        MopData d = MopData.load(writeTempJson(synthetic(reaches, wins, trans, "")), null, null);
        assertTrue(d.activityHasMopOptionsMenu("A"));
        assertFalse(d.activityHasMopOptionsMenu("B"));
        assertTrue("gateway: menu navigates to MOP activity", d.activityHasMopOptionsMenu("C"));
        // real fixture gateway
        MopData real = MopData.load(fixturePath(FRESH), null, null);
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
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")), null, null);
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
        MopData d = MopData.load(writeTempJson(json), null, null);
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
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")), null, null);
        MopData.Widget w = d.getWidget("C", "b");
        assertEquals(Boolean.TRUE, w.directMopByEventType.get("click"));
        assertEquals(2, w.listeners.size());
        assertEquals(Config.mopWeightDirect, MopScorer.score("C", "b", d, "click"));
    }

    // 15.23
    @Test
    public void testPlanControlledFlagsBindTheirJarDefaults() {
        // The four parameters below are resolved into the plan, not held in a mutable static. A
        // MOP arm that states none of them inherits the jar defaults; a different value is a
        // different plan, which is why there is no assignment to observe here.
        RunSpec spec = TestRunSpecs.installMop();
        assertEquals(250, spec.mop().weightOpenMenu());
        assertTrue(spec.exploration().fuzzInputTyped());
        assertFalse(spec.mop().strictPackageMatch());
        // activity-frontier: default true (it gates the stagnation launcher).
        assertTrue(spec.mop().activityTriggerEnabled());
    }

    // 15.24
    @Test
    public void testCompleteSentinelInMiddleStillRecognized() throws Exception {
        MopData d = MopData.load(writeTempJson(
                "{\"package\":\"a.b\",\"complete\":true,\"windows\":[]}"), null, null);
        assertNotNull(d);
        assertTrue(d.isComplete());
    }

    // 15.25
    @Test
    public void testLoadNullPathReturnsNullCleanly() {
        assertNull(MopData.load(null, null, null));
    }

    // 15.26
    @Test
    public void testGetWindowUnknownIdReturnsNull() {
        MopData d = MopData.load(fixturePath(FRESH), null, null);
        assertNull(d.getWindow(0));
        assertNull(d.getWindow(-1));
        assertNull(d.getWindow(Integer.MAX_VALUE));
    }

    // 15.27 — gh15 A-2 B6: eventType normalization (INV-MOP-08)
    @Test
    public void testEventTypeNormalizationSnakeCamelEqual() throws Exception {
        // snake_case and camelCase of the same event collapse to one canonical token
        assertEquals(MopData.normalizeEventType("longClick"),
                MopData.normalizeEventType("long_click"));
        assertEquals(MopData.normalizeEventType("itemSelected"),
                MopData.normalizeEventType("item_selected"));
        assertEquals("click", MopData.normalizeEventType("click"));
        assertNull(MopData.normalizeEventType(null));

        // end-to-end: the JSON listener emits snake_case; the consumer queries camelCase
        String reaches = "{\"className\":\"C\",\"methods\":[" +
                "{\"signature\":\"<C: void h()>\",\"reachesTarget\":true,\"directlyReachesTarget\":true}]}";
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":[" +
                "{\"idName\":\"b\",\"type\":\"android.widget.Button\",\"listeners\":[" +
                "{\"eventType\":\"long_click\",\"handler\":\"<C: void h()>\"}]}]}";
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")), null, null);
        MopData.Widget w = d.getWidget("C", "b");
        assertTrue("snake_case JSON matches camelCase query", w.isDirectMop("longClick"));
        assertTrue("snake_case JSON matches snake_case query", w.isDirectMop("long_click"));
    }

    /** Build a minimal complete JSON from raw array/object element strings (no trailing commas). */
    // =========================================================================
    // mop-parser-fidelity (#0) — widget-map fidelity (INV-MOP-19/20) + WTG keying
    // =========================================================================

    private static final String ENC_REACH = "{\"className\":\"C\",\"methods\":["
            + "{\"signature\":\"<C: void enc()>\",\"reachesTarget\":true,\"directlyReachesTarget\":true}]}";
    private static final String ENC_LISTENER =
            "\"listeners\":[{\"eventType\":\"click\",\"handler\":\"<C: void enc()>\"}]";

    // 1.5(a): duplicate shortId, flagged-then-unflagged → flagged retained (INV-MOP-19).
    @Test
    public void testWidgetCollision_flaggedThenUnflagged_keepsFlagged() throws Exception {
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":["
                + "{\"idName\":\"submit\",\"type\":\"android.widget.Button\"," + ENC_LISTENER + "},"
                + "{\"idName\":\"submit\",\"type\":\"android.widget.Button\",\"listeners\":[]}]}";
        MopData d = MopData.load(writeTempJson(synthetic(ENC_REACH, win, "", "")), null, null);
        MopData.Widget w = d.getWidget("C", "submit");
        assertNotNull(w);
        assertTrue("flagged widget retained on collision", w.directMop);
    }

    // 1.5(b): order-independent — unflagged-then-flagged → flagged retained (INV-MOP-19).
    @Test
    public void testWidgetCollision_unflaggedThenFlagged_keepsFlagged() throws Exception {
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":["
                + "{\"idName\":\"submit\",\"type\":\"android.widget.Button\",\"listeners\":[]},"
                + "{\"idName\":\"submit\",\"type\":\"android.widget.Button\"," + ENC_LISTENER + "}]}";
        MopData d = MopData.load(writeTempJson(synthetic(ENC_REACH, win, "", "")), null, null);
        MopData.Widget w = d.getWidget("C", "submit");
        assertNotNull(w);
        assertTrue("strongest flag wins regardless of order", w.directMop);
    }

    // 1.5(c): empty idName flagged widget → not bucketed, counted, activity still flagged (INV-MOP-20).
    @Test
    public void testEmptyIdWidget_notBucketed_countedAndActivityFlagged() throws Exception {
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":["
                + "{\"idName\":\"\",\"type\":\"android.widget.Button\"," + ENC_LISTENER + "}]}";
        MopData d = MopData.load(writeTempJson(synthetic(ENC_REACH, win, "", "")), null, null);
        assertNull("empty-id widget not stored under the \"\" key", d.getWidget("C", ""));
        assertEquals("flagged-no-id drop counted", 1, d.getDroppedFlaggedNoId());
        assertTrue("activity still flagged via mopActivities", d.activityHasMop("C"));
    }

    // 1.5(d): regression — no-collision JSON stores all distinct ids; zero drops.
    @Test
    public void testNoCollision_allWidgetsStored() throws Exception {
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":["
                + "{\"idName\":\"a\",\"type\":\"android.widget.Button\"," + ENC_LISTENER + "},"
                + "{\"idName\":\"b\",\"type\":\"android.widget.Button\",\"listeners\":[]}]}";
        MopData d = MopData.load(writeTempJson(synthetic(ENC_REACH, win, "", "")), null, null);
        assertNotNull(d.getWidget("C", "a"));
        assertNotNull(d.getWidget("C", "b"));
        assertTrue(d.getWidget("C", "a").directMop);
        assertEquals(0, d.getDroppedFlaggedNoId());
    }

    // 2.3(a): menu-sourced edge reachable by BASE activity; the suffixed key returns empty (INV-WTG-04).
    @Test
    public void testWtgKeyedByBaseActivity_menuSource() throws Exception {
        String wins = "{\"id\":1,\"type\":\"OPTIONSMENU\",\"name\":\"C#OptionsMenu\",\"widgets\":[]},"
                + "{\"id\":2,\"type\":\"ACTIVITY\",\"name\":\"Tgt\",\"widgets\":[]}";
        String trans = "{\"sourceId\":1,\"targetId\":2,\"events\":["
                + "{\"type\":\"click\",\"widgetId\":9,\"widgetClass\":\"android.view.MenuItem\",\"widgetName\":\"item\"}]}";
        MopData d = MopData.load(writeTempJson(synthetic("", wins, trans, "")), null, null);
        List<MopData.WtgTransition> base = d.getWtgTransitions("C");
        assertEquals("menu edge stored under base activity", 1, base.size());
        assertEquals("item", base.get(0).widgetName);
        assertTrue("suffixed key no longer used", d.getWtgTransitions("C#OptionsMenu").isEmpty());
    }

    // 2.3 target reduction: a "#"-suffixed target window is stored as its base activity (INV-WTG-04).
    @Test
    public void testWtgTargetReducedToBase() throws Exception {
        String wins = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"Src\",\"widgets\":[]},"
                + "{\"id\":2,\"type\":\"ACTIVITY\",\"name\":\"Tgt#Dialog\",\"widgets\":[]}";
        String trans = "{\"sourceId\":1,\"targetId\":2,\"events\":["
                + "{\"type\":\"click\",\"widgetId\":9,\"widgetClass\":\"x\",\"widgetName\":\"go\"}]}";
        MopData d = MopData.load(writeTempJson(synthetic("", wins, trans, "")), null, null);
        List<MopData.WtgTransition> list = d.getWtgTransitions("Src");
        assertEquals(1, list.size());
        assertEquals("target reduced to base activity", "Tgt", list.get(0).targetActivity);
    }

    // =========================================================================
    // mop-parser-fidelity (#0) group 4 — DIALOG re-keying to host (INV-MOP-25)
    // =========================================================================

    // Host activity opens the dialog; dialog window name is the dialog class.
    private static final String DIALOG_HOST_WINS =
            "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"Host\",\"widgets\":[]},"
            + "{\"id\":2,\"type\":\"DIALOG\",\"name\":\"android.app.AlertDialog\",\"widgets\":["
            + "{\"idName\":\"btn_confirm\",\"type\":\"android.widget.Button\"," + ENC_LISTENER + "}]}";
    private static final String DIALOG_OPEN_TRANS =
            "{\"sourceId\":1,\"targetId\":2,\"events\":["
            + "{\"type\":\"click\",\"widgetId\":9,\"widgetClass\":\"x\",\"widgetName\":\"open\"}]}";

    // 4.4(a)+(e): dialog widget resolvable via host; dialog-class key removed (move-not-copy).
    @Test
    public void testDialogReKey_widgetResolvableViaHost_dialogClassRemoved() throws Exception {
        MopData d = MopData.load(
                writeTempJson(synthetic(ENC_REACH, DIALOG_HOST_WINS, DIALOG_OPEN_TRANS, "")), null, null);
        MopData.Widget w = d.getWidget("Host", "btn_confirm");
        assertNotNull("dialog widget re-keyed under host activity", w);
        assertTrue("flag preserved through merge", w.directMop);
        assertNull("dialog-class key removed from widget map (move-not-copy, A2)",
                d.getWidget("android.app.AlertDialog", "btn_confirm"));
    }

    // 4.4(b): collision on re-key keeps the strongest flag (INV-MOP-19 applied in the merge).
    @Test
    public void testDialogReKey_collisionKeepsStrongest() throws Exception {
        String wins = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"Host\",\"widgets\":["
                + "{\"idName\":\"shared\",\"type\":\"android.widget.Button\",\"listeners\":[]}]},"
                + "{\"id\":2,\"type\":\"DIALOG\",\"name\":\"android.app.AlertDialog\",\"widgets\":["
                + "{\"idName\":\"shared\",\"type\":\"android.widget.Button\"," + ENC_LISTENER + "}]}";
        MopData d = MopData.load(
                writeTempJson(synthetic(ENC_REACH, wins, DIALOG_OPEN_TRANS, "")), null, null);
        assertTrue("dialog's flagged widget wins over unflagged host resident",
                d.getWidget("Host", "shared").directMop);
    }

    // 4.4(c): a dialog-only host is promoted to activityHasMop (D6/F3).
    @Test
    public void testDialogReKey_dialogOnlyHostPromoted() throws Exception {
        MopData d = MopData.load(
                writeTempJson(synthetic(ENC_REACH, DIALOG_HOST_WINS, DIALOG_OPEN_TRANS, "")), null, null);
        assertTrue("host with no flags of its own promoted via reachable dialog",
                d.activityHasMop("Host"));
    }

    // 4.4(d): orphan dialog (no incoming transition) is counted on an [APE-RV] line, stays under
    // its dialog-class key, and is NOT resolvable via any host; the [APE-MOP-DATA] line is
    // unchanged (F2 regression guard).
    @Test
    public void testDialogReKey_orphanCountedAndNotReKeyed() throws Exception {
        String json = synthetic(ENC_REACH, DIALOG_HOST_WINS, "", "");   // no transitions → orphan
        MopData d = MopData.load(writeTempJson(json), null, null);
        assertNull("orphan dialog not resolvable via host", d.getWidget("Host", "btn_confirm"));
        assertNotNull("orphan dialog widget stays under dialog-class key",
                d.getWidget("android.app.AlertDialog", "btn_confirm"));

        String out = captureLoad(writeTempJson(json), null, null);
        assertTrue(out, out.contains("[APE-RV] MopData: 1 orphan DIALOG windows (no incoming transition)"));
        String statusLine = null;
        for (String line : out.split("\\R")) {
            if (line.contains("[APE-MOP-DATA]")) statusLine = line;
        }
        assertNotNull("status line present", statusLine);
        assertFalse("orphan diagnostic must not leak onto the [APE-MOP-DATA] line (F2)",
                statusLine.contains("orphan"));
    }

    // 4.4(f): triple-collision (direct/transitive/unflagged, same idName) across host+dialog,
    // both merge directions — strongest flag wins and the merge never downgrades a resident.
    @Test
    public void testDialogReKey_tripleCollision_strongestWinsBothDirections() throws Exception {
        String reach = "{\"className\":\"C\",\"methods\":["
                + "{\"signature\":\"<C: void d()>\",\"reachesTarget\":true,\"directlyReachesTarget\":true},"
                + "{\"signature\":\"<C: void t()>\",\"reachesTarget\":true,\"directlyReachesTarget\":false}]}";
        String direct = "\"listeners\":[{\"eventType\":\"click\",\"handler\":\"<C: void d()>\"}]";
        String transitive = "\"listeners\":[{\"eventType\":\"click\",\"handler\":\"<C: void t()>\"}]";
        // host: unflagged x + transitive x ; dialog: direct x → direct wins on host after merge.
        String wins = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"Host\",\"widgets\":["
                + "{\"idName\":\"x\",\"type\":\"android.widget.Button\",\"listeners\":[]},"
                + "{\"idName\":\"x\",\"type\":\"android.widget.Button\"," + transitive + "}]},"
                + "{\"id\":2,\"type\":\"DIALOG\",\"name\":\"android.app.AlertDialog\",\"widgets\":["
                + "{\"idName\":\"x\",\"type\":\"android.widget.Button\"," + direct + "}]}";
        MopData d = MopData.load(
                writeTempJson(synthetic(reach, wins, DIALOG_OPEN_TRANS, "")), null, null);
        assertTrue("direct dialog widget wins the triple collision", d.getWidget("Host", "x").directMop);

        // Swap strengths: host direct, dialog transitive → merge must NOT downgrade the host.
        String wins2 = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"Host\",\"widgets\":["
                + "{\"idName\":\"x\",\"type\":\"android.widget.Button\"," + direct + "}]},"
                + "{\"id\":2,\"type\":\"DIALOG\",\"name\":\"android.app.AlertDialog\",\"widgets\":["
                + "{\"idName\":\"x\",\"type\":\"android.widget.Button\"," + transitive + "}]}";
        MopData d2 = MopData.load(
                writeTempJson(synthetic(reach, wins2, DIALOG_OPEN_TRANS, "")), null, null);
        assertTrue("merge never downgrades a stronger resident", d2.getWidget("Host", "x").directMop);
    }

    // 4.4(g): the dialog-class mopActivities entry is retained so the OPTIONSMENU-gateway
    // precompute (condition 2) still fires for an activity whose only MOP route is a click edge
    // into a flagged DIALOG (A6). If the re-key dropped that entry, this would be false.
    @Test
    public void testDialogReKey_retainsDialogClassForGatewayDetection() throws Exception {
        String wins = "{\"id\":1,\"type\":\"OPTIONSMENU\",\"name\":\"Src#OptionsMenu\",\"widgets\":["
                + "{\"idName\":\"item\",\"type\":\"android.view.MenuItem\",\"listeners\":[]}]},"
                + "{\"id\":2,\"type\":\"DIALOG\",\"name\":\"android.app.AlertDialog\",\"widgets\":["
                + "{\"idName\":\"btn\",\"type\":\"android.widget.Button\"," + ENC_LISTENER + "}]}";
        String trans = "{\"sourceId\":1,\"targetId\":2,\"events\":["
                + "{\"type\":\"click\",\"widgetId\":9,\"widgetClass\":\"android.view.MenuItem\",\"widgetName\":\"item\"}]}";
        MopData d = MopData.load(writeTempJson(synthetic(ENC_REACH, wins, trans, "")), null, null);
        assertTrue("gateway fires via retained dialog-class mopActivities entry (A6)",
                d.activityHasMopOptionsMenu("Src"));
    }

    // -------------------------------------------------------------------------
    // F′ seam: isWidgetlessSubstrate() — pure sum over windows[].widgets (INV-MOP-28)
    // -------------------------------------------------------------------------

    /** 3.1: a window carrying at least one widget → not a widgetless substrate. */
    @Test
    public void widgetlessSubstrateFalseWhenAWidgetPresent() throws Exception {
        String win = "{\"id\":1,\"name\":\"Scr\",\"widgets\":["
                + "{\"idName\":\"btn\",\"type\":\"android.widget.Button\"}]}";
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")), null, null);
        assertFalse(d.isWidgetlessSubstrate());
    }

    /** 3.1: a window with an empty widgets[] → widgetless substrate (0-widget fixture). */
    @Test
    public void widgetlessSubstrateTrueWhenWindowHasNoWidgets() throws Exception {
        String win = "{\"id\":1,\"name\":\"Scr\",\"widgets\":[]}";
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")), null, null);
        assertTrue(d.isWidgetlessSubstrate());
    }

    /** 3.1: no windows at all → widgetless substrate (empty windows[]). */
    @Test
    public void widgetlessSubstrateTrueWhenNoWindows() throws Exception {
        MopData d = MopData.load(writeTempJson(synthetic("", "", "", "")), null, null);
        assertTrue(d.isWidgetlessSubstrate());
    }

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
