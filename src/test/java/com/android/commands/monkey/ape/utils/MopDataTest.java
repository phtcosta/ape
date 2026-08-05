package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.telemetry.NdjsonSink;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for MopData. The WTG data-layer tests use the package-private
 * {@code MopData.forTest()} factory; the parser tests (gh13 §15) load real and synthetic
 * JSON through {@code MopData.load(, null, null, new NoopSink())}, which is JVM-runnable because the parser uses
 * {@code org.json} rather than {@code android.util.JsonReader} (design D21).
 */
public class MopDataTest {

    /**
     * The two MOP weights this file's scoring assertions pass in. Not the jar defaults on purpose:
     * a value that exists nowhere else can only appear in a result by having travelled from the
     * argument. {@code ScoringParamsDefaultsTest} is what pins the defaults themselves.
     */
    private static final int W_DIRECT = 917;
    private static final int W_TRANSITIVE = 613;

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
    // gh13 §15 — parser tests over real + synthetic JSON via MopData.load(, null, null, new NoopSink())
    // =========================================================================

    private static final String FRESH = "cryptoapp.apk.gh60-fresh.json";
    private static final String COMPACT = "cryptoapp.apk.mop.json";
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
        MopData d = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
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
        MopData d = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
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
        assertEquals(W_TRANSITIVE,
                MopScorer.score(MDA, "buttonGenerateHash", d, "click", W_DIRECT, W_TRANSITIVE));
        // gateway: MainActivity's options menu navigates to MOP sub-activities
        assertTrue(d.activityHasMopOptionsMenu(MAIN));
    }

    // 15.3
    @Test
    public void testEditTextWidgetMetadataCaptured() {
        MopData d = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")), null, null, new NoopSink());
        assertNotNull(d);
        assertTrue(d.getWidget("C", "b").transitiveMop);
        // legacy reachesMop key is ignored (P3, forward-compat fall-through)
        String legacyReach = "{\"className\":\"C\",\"methods\":[" +
                "{\"signature\":\"<C: void h()>\",\"reachesMop\":true}]}";
        MopData d2 = MopData.load(writeTempJson(synthetic(legacyReach, win, "", "")), null, null, new NoopSink());
        assertNotNull(d2);
        assertFalse("legacy reachesMop must NOT register", d2.getWidget("C", "b").transitiveMop);
    }

    // 15.5
    @Test
    public void testCompleteSentinel() throws Exception {
        String body = "\"reachability\":[],\"windows\":[],\"transitions\":[],\"components\":{}";
        assertNull(MopData.load(writeTempJson("{" + body + "}"), null, null, new NoopSink()));               // absent
        assertNull(MopData.load(writeTempJson("{\"complete\":false," + body + "}"), null, null, new NoopSink())); // false
        assertNotNull(MopData.load(writeTempJson("{\"complete\":true," + body + "}"), null, null, new NoopSink())); // true
    }

    // 15.6
    @Test
    public void testTopLevelPackageAndMainActivity() throws Exception {
        MopData d = MopData.load(writeTempJson(
                "{\"package\":\"a.b.c\",\"mainActivity\":\"a.b.c.Main\",\"complete\":true}"), null, null, new NoopSink());
        assertEquals("a.b.c", d.getPackageName());
        assertEquals("a.b.c.Main", d.getMainActivity());
    }

    // 15.7
    @Test
    public void testPackageMismatchWarnsByDefault() {
        MopData d = MopData.load(fixturePath(FRESH), "x.y.z.OTHER", null, new NoopSink());
        assertNotNull("default warn-only returns parsed data", d);
    }

    // 15.8
    @Test
    public void testPackageMismatchRejectsWhenStrict() {
        TestRunSpecs.installMop("ape.mopStrictPackageMatch", "true");
        assertNull(MopData.load(fixturePath(FRESH), "x.y.z.OTHER", null, new NoopSink()));
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
            MopData.load(path, pkg, main, new NoopSink());
        } finally {
            System.setOut(original);
        }
        return buffer.toString();
    }

    /**
     * The {@code MOP_DATA} records a load writes, in order.
     *
     * <p>The census is asserted on the record rather than on a captured line because the record is
     * the whole product: nothing in the jar reads it back, so a field that exists in the sink's
     * arguments and not on the stream is not recorded at all.
     */
    private static List<JSONObject> loadRecords(String path, String pkg, String main)
            throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        MopData.load(path, pkg, main, new NdjsonSink(new PrintStream(buffer, true, "UTF-8")));
        return recordsIn(buffer);
    }

    /** Parse whatever NDJSON the sink wrote, in order. */
    private static List<JSONObject> recordsIn(ByteArrayOutputStream buffer) throws Exception {
        List<JSONObject> records = new ArrayList<>();
        String text = new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        if (!text.isEmpty()) {
            for (String line : text.split("\\n")) {
                records.add(new JSONObject(line));
            }
        }
        return records;
    }

    private static JSONObject onlyCompactLoadRecord(String path, boolean activitySourceComponents)
            throws Exception {
        return onlyCompactLoadRecord(path, null, null, activitySourceComponents);
    }

    private static JSONObject onlyCompactLoadRecord(String path, String pkg, String main,
            boolean activitySourceComponents) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        MopData.loadCompact(path, pkg, main, activitySourceComponents,
                new NdjsonSink(new PrintStream(buffer, true, "UTF-8")));
        List<JSONObject> records = recordsIn(buffer);
        assertEquals("exactly one MOP_DATA record per load", 1, records.size());
        assertEquals("MOP_DATA", records.get(0).getString("type"));
        return records.get(0);
    }

    private static JSONObject onlyLoadRecord(String path, String pkg, String main)
            throws Exception {
        List<JSONObject> records = loadRecords(path, pkg, main);
        assertEquals("exactly one MOP_DATA record per load", 1, records.size());
        assertEquals("MOP_DATA", records.get(0).getString("type"));
        return records.get(0);
    }
    // 5.4 — the loaded record carries the full census, exactly one record
    @Test
    public void testLoadRecordCarriesTheFullCensus() throws Exception {
        JSONObject record = onlyLoadRecord(fixturePath(FRESH), null, null);
        assertEquals("loaded", record.getString("status"));
        assertEquals(PKG, record.getString("package"));
        assertEquals(5, record.getInt("windows"));
        assertTrue(record.has("widgets"));
        // FIX 2: flagged=3 (was 2) — the Execute button's desugared-lambda handler is now recovered.
        assertEquals(3, record.getInt("flagged"));
        assertTrue(record.has("droppedNoId"));
        // FIX 3 (INV-MOP-31): join diagnostics on the record; 1 synthetic lambda recovered.
        assertEquals(5, record.getInt("handlersUnmatched"));
        assertEquals(1, record.getInt("syntheticLambda"));
        assertEquals(1, record.getInt("recovered"));
        // Neutral where the fact is absent, true where it is present: a producer document carries
        // neither a wire version nor a digest of itself, but this path holds the typed component
        // lists at the emission site, so reporting 0 for them would be a falsehood, not a blank.
        assertEquals(0, record.getInt("formatVersion"));
        assertFalse(record.has("sourceDigest"));
        assertEquals(5, record.getInt("components"));
    }

    /**
     * The record carries the click-only WTG view's edge count, not the flat transitions list.
     *
     * <p>They are different numbers over the same file — 35 transitions here against the click-only
     * edges below — and the frontier passes gate on the second. Reporting the first is what let 14
     * of the campaign's 40 applications look like they had WTG data while the whole frontier family
     * was disabled, so the field is not a rename: it is the correction.
     */
    @Test
    public void testLoadRecordCarriesWtgEdgesRatherThanTheFlatTransitionList() throws Exception {
        JSONObject record = onlyLoadRecord(fixturePath(FRESH), null, null);
        MopData data = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
        int clickOnlyEdges = 0;
        for (MopData.Window w : data.getWindows()) {
            clickOnlyEdges += data.getWtgTransitions(w.name).size();
        }
        assertEquals(clickOnlyEdges, record.getInt("wtgEdges"));
        assertFalse("the flat list is deliberately not carried forward",
                record.has("transitions"));
        assertFalse("has_wtg_data is wtgEdges > 0 by construction",
                record.has("has_wtg_data"));
    }

    /**
     * The compact record answers the two questions the full-JSON one structurally could not: which
     * wire contract was read, and which static-analysis document the artifact was derived from.
     *
     * <p>The digest is asserted against a SHA-256 computed here over the source fixture rather than
     * against the string the artifact happens to carry, so this pins the whole provenance chain —
     * source bytes → generator → wire → record. A test that read the digest back out of the same
     * file it came from would pass over an artifact derived from any document at all.
     */
    @Test
    public void testCompactLoadRecordCarriesProvenanceAndTheComponentCount() throws Exception {
        JSONObject record = onlyCompactLoadRecord(fixturePath(COMPACT), false);
        assertEquals("loaded", record.getString("status"));
        assertEquals(1, record.getInt("formatVersion"));
        assertEquals("sha256:" + sha256Hex(fixturePath(FRESH)), record.getString("sourceDigest"));
        // 4 activities + 1 provider, and no receivers or services: the count is over every list,
        // which is the granularity `hasComponents()` gates on.
        assertEquals(5, record.getInt("components"));

        // The stage-4 census survives underneath, whole: this record gains three fields across the
        // window and loses none. `windows` is here for that reason and no other — the jar parses no
        // windows now, so the number is the generator's, echoed.
        assertEquals(5, record.getInt("windows"));
        assertEquals(30, record.getInt("widgets"));
        assertEquals(3, record.getInt("flagged"));
        assertEquals(0, record.getInt("droppedNoId"));
        assertEquals(16, record.getInt("wtgEdges"));
        assertEquals(5, record.getInt("handlersUnmatched"));
        assertEquals(1, record.getInt("syntheticLambda"));
        assertEquals(1, record.getInt("recovered"));
        assertEquals(3, record.getInt("mopActivities"));
        // Vacuously 0 on this fixture: cryptoapp's augmented set equals its widget-derived one, so
        // nothing here can distinguish the wire-set reading from the retired applied-augmentation
        // one. The synthetic that does is task 3.5's.
        assertEquals(0, record.getInt("mopActsAugmented"));
        assertFalse("the flat transition list is not reinstated at stage 7 either",
                record.has("transitions"));
    }

    private static String sha256Hex(String path) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // 5.4 — rejection reasons
    @Test
    public void testRejectionRecordsIncompleteReason() throws Exception {
        JSONObject record = onlyLoadRecord(writeTempJson("{\"package\":\"a.b\"}"), null, null);
        assertEquals("rejected", record.getString("status"));
        assertEquals("incomplete", record.getString("reason"));
    }

    @Test
    public void testRejectionRecordsParseErrorReason() throws Exception {
        JSONObject record = onlyLoadRecord(writeTempJson("{ this is not valid json "), null, null);
        assertEquals("parse-error", record.getString("reason"));
    }

    @Test
    public void testRejectionRecordsFileMissingReason() throws Exception {
        JSONObject record = onlyLoadRecord("/nonexistent/path/does-not-exist.json", null, null);
        assertEquals("file-missing", record.getString("reason"));
    }

    @Test
    public void testRejectionRecordsPackageMismatchReason() throws Exception {
        TestRunSpecs.installMop("ape.mopStrictPackageMatch", "true");
        JSONObject record = onlyLoadRecord(fixturePath(FRESH), "x.y.z.OTHER", null);
        assertEquals("package-mismatch", record.getString("reason"));
    }

    // 5.3 — unset path stays silent (spec: no record required when MOP disabled)
    @Test
    public void testNullPathRecordsNothing() throws Exception {
        assertTrue(loadRecords(null, null, null).isEmpty());
    }

    // 15.9
    @Test
    public void testReachabilityClassFieldsCaptured() {
        MopData d = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
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
        MopData d = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")), null, null, new NoopSink());
        assertNull(d.getWidget("C", "a").prompt);
        assertNull(d.getWidget("C", "b").prompt);
        assertNull(d.getWidget("C", "b").tooltipText);
    }

    // 15.13
    @Test
    public void testSpinnerEntriesCaptured() {
        MopData d = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
        MopData.Widget sp = d.getWidget(MDA, "spinnerMessageDigest");
        assertEquals(13, sp.entries.size());
        assertTrue(sp.entries.contains("MD5"));
    }

    // 15.14
    @Test
    public void testListenerFieldsCaptured() {
        MopData d = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")), null, null, new NoopSink());
        assertTrue(d.getWidget("C", "b").transitiveMop);
    }

    // 15.17
    @Test
    public void testTransitionEventFieldsCaptured() {
        MopData d = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
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
        MopData d = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic(reaches, wins, trans, "")), null, null, new NoopSink());
        assertTrue(d.activityHasMopOptionsMenu("A"));
        assertFalse(d.activityHasMopOptionsMenu("B"));
        assertTrue("gateway: menu navigates to MOP activity", d.activityHasMopOptionsMenu("C"));
        // real fixture gateway
        MopData real = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(json), null, null, new NoopSink());
        assertNotNull(d);
        assertTrue(d.isComplete());
        assertTrue(d.getReachability().isEmpty());
        assertTrue(d.getWindows().isEmpty());
        assertTrue(d.getTransitions().isEmpty());
        assertTrue(d.getReceivers().isEmpty());
        assertTrue(d.getServices().isEmpty());
        assertTrue(d.getActivities().isEmpty());
        assertTrue(d.getProviders().isEmpty());
        assertEquals(0, MopScorer.score("x", "y", d, "click", W_DIRECT, W_TRANSITIVE));
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
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")), null, null, new NoopSink());
        MopData.Widget w = d.getWidget("C", "b");
        assertEquals(Boolean.TRUE, w.directMopByEventType.get("click"));
        assertEquals(2, w.listeners.size());
        assertEquals(W_DIRECT, MopScorer.score("C", "b", d, "click", W_DIRECT, W_TRANSITIVE));
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
                "{\"package\":\"a.b\",\"complete\":true,\"windows\":[]}"), null, null, new NoopSink());
        assertNotNull(d);
        assertTrue(d.isComplete());
    }

    // 15.25
    @Test
    public void testLoadNullPathReturnsNullCleanly() {
        assertNull(MopData.load(null, null, null, new NoopSink()));
    }

    // 15.26
    @Test
    public void testGetWindowUnknownIdReturnsNull() {
        MopData d = MopData.load(fixturePath(FRESH), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic(reaches, win, "", "")), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic(ENC_REACH, win, "", "")), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic(ENC_REACH, win, "", "")), null, null, new NoopSink());
        MopData.Widget w = d.getWidget("C", "submit");
        assertNotNull(w);
        assertTrue("strongest flag wins regardless of order", w.directMop);
    }

    // 1.5(c): empty idName flagged widget → not bucketed, counted, activity still flagged (INV-MOP-20).
    @Test
    public void testEmptyIdWidget_notBucketed_countedAndActivityFlagged() throws Exception {
        String win = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"C\",\"widgets\":["
                + "{\"idName\":\"\",\"type\":\"android.widget.Button\"," + ENC_LISTENER + "}]}";
        MopData d = MopData.load(writeTempJson(synthetic(ENC_REACH, win, "", "")), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic(ENC_REACH, win, "", "")), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic("", wins, trans, "")), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic("", wins, trans, "")), null, null, new NoopSink());
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
                writeTempJson(synthetic(ENC_REACH, DIALOG_HOST_WINS, DIALOG_OPEN_TRANS, "")), null, null, new NoopSink());
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
                writeTempJson(synthetic(ENC_REACH, wins, DIALOG_OPEN_TRANS, "")), null, null, new NoopSink());
        assertTrue("dialog's flagged widget wins over unflagged host resident",
                d.getWidget("Host", "shared").directMop);
    }

    // 4.4(c): a dialog-only host is promoted to activityHasMop (D6/F3).
    @Test
    public void testDialogReKey_dialogOnlyHostPromoted() throws Exception {
        MopData d = MopData.load(
                writeTempJson(synthetic(ENC_REACH, DIALOG_HOST_WINS, DIALOG_OPEN_TRANS, "")), null, null, new NoopSink());
        assertTrue("host with no flags of its own promoted via reachable dialog",
                d.activityHasMop("Host"));
    }

    // 4.4(d): orphan dialog (no incoming transition) is counted on an [APE-RV] line, stays under
    // its dialog-class key, and is NOT resolvable via any host; the [APE-MOP-DATA] line is
    // unchanged (F2 regression guard).
    @Test
    public void testDialogReKey_orphanCountedAndNotReKeyed() throws Exception {
        String json = synthetic(ENC_REACH, DIALOG_HOST_WINS, "", "");   // no transitions → orphan
        MopData d = MopData.load(writeTempJson(json), null, null, new NoopSink());
        assertNull("orphan dialog not resolvable via host", d.getWidget("Host", "btn_confirm"));
        assertNotNull("orphan dialog widget stays under dialog-class key",
                d.getWidget("android.app.AlertDialog", "btn_confirm"));

        String out = captureLoad(writeTempJson(json), null, null);
        assertTrue(out, out.contains("[APE-RV] MopData: 1 orphan DIALOG windows (no incoming transition)"));
        JSONObject record = onlyLoadRecord(writeTempJson(json), null, null);
        assertFalse("the orphan diagnostic stays on the free-text side of the stream (F2)",
                record.toString().contains("orphan"));
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
                writeTempJson(synthetic(reach, wins, DIALOG_OPEN_TRANS, "")), null, null, new NoopSink());
        assertTrue("direct dialog widget wins the triple collision", d.getWidget("Host", "x").directMop);

        // Swap strengths: host direct, dialog transitive → merge must NOT downgrade the host.
        String wins2 = "{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"Host\",\"widgets\":["
                + "{\"idName\":\"x\",\"type\":\"android.widget.Button\"," + direct + "}]},"
                + "{\"id\":2,\"type\":\"DIALOG\",\"name\":\"android.app.AlertDialog\",\"widgets\":["
                + "{\"idName\":\"x\",\"type\":\"android.widget.Button\"," + transitive + "}]}";
        MopData d2 = MopData.load(
                writeTempJson(synthetic(reach, wins2, DIALOG_OPEN_TRANS, "")), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic(ENC_REACH, wins, trans, "")), null, null, new NoopSink());
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
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")), null, null, new NoopSink());
        assertFalse(d.isWidgetlessSubstrate());
    }

    /** 3.1: a window with an empty widgets[] → widgetless substrate (0-widget fixture). */
    @Test
    public void widgetlessSubstrateTrueWhenWindowHasNoWidgets() throws Exception {
        String win = "{\"id\":1,\"name\":\"Scr\",\"widgets\":[]}";
        MopData d = MopData.load(writeTempJson(synthetic("", win, "", "")), null, null, new NoopSink());
        assertTrue(d.isWidgetlessSubstrate());
    }

    /** 3.1: no windows at all → widgetless substrate (empty windows[]). */
    @Test
    public void widgetlessSubstrateTrueWhenNoWindows() throws Exception {
        MopData d = MopData.load(writeTempJson(synthetic("", "", "", "")), null, null, new NoopSink());
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

    // =========================================================================
    // Task 3.5 — the compact artifact loader, scenario by scenario
    //
    // Everything above this line drives the full-JSON parser, which group 5 deletes. These tests
    // are kept in one contiguous block for that reason: the cutover is then a deletion of the
    // block above rather than an unpicking of interleaved assertions.
    //
    // What the block asserts is the `mop-guidance` delta's scenario list, on two inputs. The
    // cryptoapp artifact answers everything a real derivation can answer; the synthetic answers
    // the one question it structurally cannot, because cryptoapp's two MOP-activity sets are
    // equal and a fixture where two sets are equal cannot test a selection between them.
    // =========================================================================

    private static final String CRYPTO = "br.unb.cic.cryptoapp.generated.CryptographyActivity";

    /**
     * The artifact whose {@code mopActivitiesAugmented} strictly contains its {@code mopActivities}
     * — {@code A} in both, {@code B} only in the augmented set — plus a gateway {@code G} whose one
     * WTG edge lands on {@code B} and a gateway {@code H} that qualifies on its own flagged menu
     * widget.
     *
     * <p>It is a checked-in resource rather than a temp file because two things need it and only
     * one of them is here: task 4.1's equivalence gate reuses it as its INV-DRV-06 member, and a
     * synthetic that is written and deleted inside a test method has to be written twice.
     */
    private static final String SELECTION = "synthetic-activity-selection.mop.json";

    /** A v1 artifact envelope carrying whatever top-level members the caller appends. */
    private static String compactArtifact(String members) {
        return "{\"formatVersion\":1,\"package\":\"p\",\"mainActivity\":\"p.A\"" + members + "}";
    }

    private static MopData loadCompactFixture(String name, boolean activitySourceComponents) {
        return MopData.loadCompact(fixturePath(name), null, null, activitySourceComponents,
                new NoopSink());
    }

    private static String readText(String path) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Every widget the artifact carries, walked from the wire and asked of the loaded model — the
     * ids that came back MOP-flagged.
     *
     * <p>Enumerating the wire rather than the model is what makes "exactly three" a claim about the
     * whole artifact: {@code getWidget} is the only query into the widget map, so a test that named
     * three widgets and asked about those three could not tell three from thirty. It also asserts
     * in passing that every wire key is reachable through the query API, which is the whole of what
     * this reader is supposed to do (INV-MOP-35).
     */
    private static List<String> flaggedWidgetsOf(MopData data, String artifactPath)
            throws Exception {
        JSONObject widgets = new JSONObject(readText(artifactPath)).getJSONObject("widgets");
        List<String> flagged = new ArrayList<>();
        for (Iterator<String> ai = widgets.keys(); ai.hasNext(); ) {
            String activity = ai.next();
            JSONObject byId = widgets.getJSONObject(activity);
            for (Iterator<String> wi = byId.keys(); wi.hasNext(); ) {
                String shortId = wi.next();
                MopData.Widget w = data.getWidget(activity, shortId);
                assertNotNull("wire widget not reachable through getWidget: "
                        + activity + "#" + shortId, w);
                if (w.directMop || w.transitiveMop) {
                    flagged.add(shortId);
                }
            }
        }
        return flagged;
    }

    /** The wire's own {@code hasFlaggedWidget} for an activity's OPTIONSMENU record. */
    private static boolean wireMenuHasFlaggedWidget(String artifactPath, String activity)
            throws Exception {
        JSONArray records = new JSONObject(readText(artifactPath)).getJSONArray("optionsMenus");
        for (int i = 0; i < records.length(); i++) {
            if (activity.equals(records.getJSONObject(i).getString("activity"))) {
                return records.getJSONObject(i).getBoolean("hasFlaggedWidget");
            }
        }
        throw new AssertionError("no optionsMenus record for " + activity);
    }

    private static ComponentInfo.ActivityInfo activityNamed(MopData data, String className) {
        for (ComponentInfo.ActivityInfo a : data.getActivities()) {
            if (className.equals(a.className)) {
                return a;
            }
        }
        throw new AssertionError("no activity component named " + className);
    }

    // --- Scenario: Compact cryptoapp fixture loads every consumed field -------

    /**
     * The flag set is three widgets and the third is there only through the D8 synthetic-lambda
     * recovery: {@code executeButton}'s handler is a {@code $$ExternalSyntheticLambda0} wrapper
     * that no {@code reachability[]} signature matches, so it is flagged through the enclosing
     * class's reaching {@code lambda$setupExecuteButton$0} (INV-DRV-01). An artifact asserting two
     * is asserting that the recovery did not run in the generator.
     */
    @Test
    public void testCompactFixtureFlagsExactlyTheThreeMopWidgets() throws Exception {
        MopData d = loadCompactFixture(COMPACT, false);
        assertNotNull(d);
        assertEquals(PKG, d.getPackageName());
        assertEquals(MAIN, d.getMainActivity());

        List<String> flagged = flaggedWidgetsOf(d, fixturePath(COMPACT));
        assertEquals("exactly three flagged widgets in the whole artifact: " + flagged,
                3, flagged.size());
        assertTrue(flagged.contains("buttonGenerateHash"));
        assertTrue(flagged.contains("btn_cipher_encrypt"));
        assertTrue("the recovered Execute button", flagged.contains("executeButton"));

        for (String[] pair : new String[][]{{MDA, "buttonGenerateHash"},
                {CIPHER, "btn_cipher_encrypt"}, {CRYPTO, "executeButton"}}) {
            MopData.Widget w = d.getWidget(pair[0], pair[1]);
            assertTrue(pair[1] + " is transitively MOP-reachable", w.transitiveMop);
            assertFalse(pair[1] + " is not a direct JCA caller", w.directMop);
            assertTrue(w.isTransitiveMop("click"));
        }
    }

    /**
     * The three MOP activities, and the MainActivity menu gateway that reaches them.
     *
     * <p>The gateway is asserted together with the wire fact that makes it interesting: the record
     * for MainActivity carries {@code hasFlaggedWidget: false}, so the only route to
     * {@code activityHasMopOptionsMenu} being true is condition 2 — a click edge out of that
     * activity landing in the selected MOP set (INV-MOP-13). Without that second assertion the
     * test would pass just as well against a loader that ignored the WTG view entirely.
     */
    @Test
    public void testCompactFixtureActivitySetAndTheMenuGateway() throws Exception {
        MopData d = loadCompactFixture(COMPACT, false);
        assertEquals(3, d.getMopActivities().size());
        assertTrue(d.activityHasMop(MDA));
        assertTrue(d.activityHasMop(CIPHER));
        assertTrue(d.activityHasMop(CRYPTO));
        assertFalse("MainActivity holds no flagged widget", d.activityHasMop(MAIN));

        assertFalse("the wire record itself does not qualify MainActivity",
                wireMenuHasFlaggedWidget(fixturePath(COMPACT), MAIN));
        assertTrue("so the gateway can only come from its edges into the MOP sub-activities",
                d.activityHasMopOptionsMenu(MAIN));
        assertTrue(d.hasWtgData());
    }

    /** The metadata and component surface the prompt builder and the launcher read. */
    @Test
    public void testCompactFixtureMetadataAndComponentSurface() {
        MopData d = loadCompactFixture(COMPACT, false);

        MopData.Widget spinner = d.getWidget(MDA, "spinnerMessageDigest");
        assertEquals(13, spinner.entries.size());
        assertEquals("Select", spinner.entries.get(0));
        MopData.Widget editText = d.getWidget(MDA, "editTextMessageDigest");
        assertEquals("Input text ...", editText.hint);
        assertEquals("textPersonName", editText.inputType);

        assertEquals(4, d.getActivities().size());
        assertEquals(1, d.getProviders().size());
        assertEquals("br.unb.cic.cryptoapp.androidx-startup", d.getProviders().get(0).authorities);
        assertTrue(d.getReceivers().isEmpty());
        assertTrue(d.getServices().isEmpty());
        assertTrue(d.hasComponents());
        for (ComponentInfo c : d.getActivities()) {
            assertFalse(c.className + " reaches no monitored operation", c.reachesTarget);
        }
        assertFalse(d.getProviders().get(0).reachesTarget);
    }

    // --- Scenario: Absent metadata stays null and costs zero tokens ----------

    /**
     * A wire widget carrying only its {@code mop} map decodes to null metadata, not to empty
     * strings: {@code ApePromptBuilder} renders a field when it is non-null and non-empty, so a
     * null and an empty string cost the same zero tokens here but differ everywhere a value is
     * compared. Absent stays absent.
     */
    @Test
    public void testAbsentWidgetMetadataDecodesToNullAndEmpty() {
        MopData.Widget w = loadCompactFixture(COMPACT, false).getWidget(CIPHER, "btn_cipher_encrypt");
        assertNull(w.hint);
        assertNull(w.prompt);
        assertNull(w.inputType);
        assertNull(w.spinnerMode);
        assertNull(w.contentDescription);
        assertNull(w.tooltipText);
        assertTrue(w.entries.isEmpty());
        assertEquals("the wire key is the short id", "btn_cipher_encrypt", w.idName);
    }

    // --- Scenario: Legacy full static-analysis JSON is rejected --------------

    /**
     * The skew case: the pre-change full JSON meeting the post-change jar. It carries no
     * {@code formatVersion}, so the version gate names it (INV-MOP-34) and the load is null —
     * which is what {@code StatefulAgent} turns into a {@code StopTestingException} rather than a
     * silent SATA run. That composition is asserted in {@code StatefulAgentTriggerTest}, where
     * {@code requireMopArm} is reachable; this half is the loader's.
     */
    @Test
    public void testLegacyFullJsonIsRejectedAsVersionMismatch() throws Exception {
        assertNull(MopData.loadCompact(fixturePath(FRESH), null, null, false, new NoopSink()));
        JSONObject record = onlyCompactLoadRecord(fixturePath(FRESH), false);
        assertEquals("rejected", record.getString("status"));
        assertEquals("version-mismatch", record.getString("reason"));
        assertFalse("a load that never reached an artifact has no digest to report",
                record.has("sourceDigest"));
    }

    // --- Scenario: Per-event flag decoding preserves fallback semantics ------

    /**
     * An explicit {@code none} on the wire is not the same as an absent key, and the difference is
     * the whole reason the generator emits {@code none} entries at all: a present key answers for
     * its own event, an absent one falls back to the aggregate (INV-MOP-14). Dropping the
     * {@code none} entries on the wire would make {@code scroll} inherit {@code click}'s flag.
     */
    @Test
    public void testPerEventDecodingKeepsExplicitNoneAndFallsBackOnAbsentKeys() throws Exception {
        String path = writeTempJson(compactArtifact(",\"widgets\":{\"p.A\":{\"b\":{\"mop\":"
                + "{\"click\":\"both\",\"scroll\":\"none\"}}}}"));
        MopData.Widget w = MopData.loadCompact(path, null, null, false, new NoopSink())
                .getWidget("p.A", "b");

        assertTrue(w.isDirectMop("click"));
        assertTrue(w.isTransitiveMop("click"));
        assertFalse("explicit none, not the aggregate", w.isDirectMop("scroll"));
        assertFalse("explicit none, not the aggregate", w.isTransitiveMop("scroll"));
        assertTrue("absent key falls back to the aggregate", w.isDirectMop("longClick"));
        assertTrue(w.isTransitiveMop("longClick"));
        assertTrue("aggregates are the OR across the map", w.directMop);
        assertTrue(w.transitiveMop);
        assertTrue("the query side normalizes, so the ingest side must too",
                w.isDirectMop("long_click"));
    }

    /**
     * The four wire tokens decode positionally into the two bits ({@code none}=00,
     * {@code direct}=10, {@code transitive}=01, {@code both}=11).
     *
     * <p>{@code direct} is asserted even though a conforming generator never emits it — at
     * derivation time {@code direct} implies {@code transitive} (INV-DRV-01), so the reachable
     * value set is {@code none}/{@code transitive}/{@code both}. The encoding exists so the
     * decoder stays positional, and a decoder that dropped the token would be a loader that
     * rejects an input the format admits.
     */
    @Test
    public void testWireTokensDecodeToTheTwoBitsPositionally() throws Exception {
        String path = writeTempJson(compactArtifact(",\"widgets\":{\"p.A\":{"
                + "\"n\":{\"mop\":{\"click\":\"none\"}},"
                + "\"d\":{\"mop\":{\"click\":\"direct\"}},"
                + "\"t\":{\"mop\":{\"click\":\"transitive\"}},"
                + "\"b\":{\"mop\":{\"click\":\"both\"}}}}"));
        MopData d = MopData.loadCompact(path, null, null, false, new NoopSink());

        assertFalse(d.getWidget("p.A", "n").isDirectMop("click"));
        assertFalse(d.getWidget("p.A", "n").isTransitiveMop("click"));
        assertTrue(d.getWidget("p.A", "d").isDirectMop("click"));
        assertFalse(d.getWidget("p.A", "d").isTransitiveMop("click"));
        assertFalse(d.getWidget("p.A", "t").isDirectMop("click"));
        assertTrue(d.getWidget("p.A", "t").isTransitiveMop("click"));
        assertTrue(d.getWidget("p.A", "b").isDirectMop("click"));
        assertTrue(d.getWidget("p.A", "b").isTransitiveMop("click"));
    }

    // --- Scenario: Flag-selected MOP-activity set ---------------------------

    /**
     * Flag off: the widget-derived set, and the gateway that depends on it stays shut.
     *
     * <p>{@code G} is the assertion that matters. Its one WTG edge targets {@code B}, which is in
     * the augmented set only, so {@code G} qualifies as a gateway exactly when the run selected
     * the augmented set. A gateway set shipped precomputed could not have this property, which is
     * why design D3 refuses to ship one. {@code H} qualifies on its own flagged menu widget and is
     * therefore the control: it must be true under both selections.
     */
    @Test
    public void testFlagOffSelectsTheWidgetDerivedSetAndLeavesTheGatewayShut() {
        MopData d = loadCompactFixture(SELECTION, false);
        assertEquals(new HashSet<>(Arrays.asList("A")), d.getMopActivities());
        assertTrue(d.activityHasMop("A"));
        assertFalse("B is augmented-only", d.activityHasMop("B"));
        assertFalse("G's only edge lands on B, which this selection excludes",
                d.activityHasMopOptionsMenu("G"));
        assertTrue("H qualifies on condition 1 under either selection",
                d.activityHasMopOptionsMenu("H"));
    }

    /** Flag on: the augmented set, and the same gateway opens. */
    @Test
    public void testFlagOnSelectsTheAugmentedSetAndOpensTheGateway() {
        MopData d = loadCompactFixture(SELECTION, true);
        assertEquals(new HashSet<>(Arrays.asList("A", "B")), d.getMopActivities());
        assertTrue(d.activityHasMop("B"));
        assertTrue("condition 2 reads the selected set, so G flips with it",
                d.activityHasMopOptionsMenu("G"));
        assertTrue(d.activityHasMopOptionsMenu("H"));
    }

    /**
     * {@code mopActsAugmented} reports what the augmented source <em>would</em> contribute, so it
     * is the same number under both flag states; {@code mopActivities} reports the selected set,
     * so it is not. The two fields answer different questions on purpose, and this is the input
     * that can tell them apart — on the cryptoapp artifact the two wire sets are equal, so its
     * assertion of 0 is vacuous and says so.
     */
    @Test
    public void testAugmentedCountReportsAvailabilityUnderBothFlagStates() throws Exception {
        JSONObject off = onlyCompactLoadRecord(fixturePath(SELECTION), false);
        JSONObject on = onlyCompactLoadRecord(fixturePath(SELECTION), true);
        assertEquals(1, off.getInt("mopActsAugmented"));
        assertEquals("availability does not vary with the flag", 1, on.getInt("mopActsAugmented"));
        assertEquals("mopActivities is the selected set", 1, off.getInt("mopActivities"));
        assertEquals(2, on.getInt("mopActivities"));
    }

    // --- Scenario: Deep-link dispatch reads the wire field -------------------

    /**
     * The loader half of the deep-link scenario: {@code deepLinkUri} arrives verbatim on the
     * activity component, or null when the wire omits it.
     *
     * <p>The dispatch half — {@code ACTION_VIEW} plus {@code setPackage} for a non-null URI, the
     * explicit component for null — cannot be asserted in this suite: it lives in
     * {@code MonkeySourceApe}, which will not class-load off-device. Task 5.3a owns the jar-side
     * assertions that survive the cutover, and INV-DRV-07's assembly rule is the generator's.
     * What this test pins is that the string reaches {@code MopLauncherStage} at all, since the
     * structure the launcher used to walk to build it is gone.
     */
    @Test
    public void testDeepLinkUriIsReadFromTheWireAndNullWhenAbsent() throws Exception {
        String path = writeTempJson(compactArtifact(",\"components\":{\"activities\":["
                + "{\"className\":\"p.X\",\"deepLinkUri\":\"myapp://detail/x\"},"
                + "{\"className\":\"p.Y\"}]}"));
        MopData d = MopData.loadCompact(path, null, null, false, new NoopSink());
        assertEquals("myapp://detail/x", activityNamed(d, "p.X").deepLinkUri);
        assertNull("absent on the wire means the explicit-component intent",
                activityNamed(d, "p.Y").deepLinkUri);

        for (ComponentInfo.ActivityInfo a : loadCompactFixture(COMPACT, false).getActivities()) {
            assertNull("cryptoapp declares no deep link", a.deepLinkUri);
            assertTrue("no intent-filter data block exists on the wire to walk",
                    a.intentFilters.isEmpty());
        }
    }

    // --- Scenario: Unknown keys in a v1 artifact are ignored -----------------

    /**
     * Tolerance here is the absence of a check, not an affordance: rejecting unknown keys would
     * mean writing code. Under the coordinated cut the generator and the jar ship together, so an
     * unknown key in a v1 artifact is a skew signal to read in the trace, not a version to
     * accommodate — and the fields around it must still decode.
     */
    @Test
    public void testUnknownKeysInAV1ArtifactAreIgnored() throws Exception {
        String path = writeTempJson(compactArtifact(",\"futureBlock\":{\"whatever\":[1,2,3]}"
                + ",\"mopActivities\":[\"A\"]"
                + ",\"widgets\":{\"p.A\":{\"b\":{\"hint\":\"h\",\"futureWidgetKey\":7,"
                + "\"mop\":{\"click\":\"transitive\"}}}}"));
        MopData d = MopData.loadCompact(path, null, null, false, new NoopSink());
        assertNotNull(d);
        assertEquals("h", d.getWidget("p.A", "b").hint);
        assertTrue(d.getWidget("p.A", "b").isTransitiveMop("click"));
        assertTrue(d.activityHasMop("A"));
    }

    // --- Scenarios: package / mainActivity sanity check ----------------------

    @Test
    public void testCompactPackageMismatchWarnsByDefault() {
        assertNotNull("default is warn-only, and the artifact is still served",
                MopData.loadCompact(fixturePath(COMPACT), "x.y.z.OTHER", null, false,
                        new NoopSink()));
    }

    @Test
    public void testCompactPackageMismatchRejectsWhenStrict() throws Exception {
        TestRunSpecs.installMop("ape.mopStrictPackageMatch", "true");
        assertNull(MopData.loadCompact(fixturePath(COMPACT), "x.y.z.OTHER", null, false,
                new NoopSink()));
        JSONObject record = onlyCompactLoadRecord(fixturePath(COMPACT), "x.y.z.OTHER", null, false);
        assertEquals("rejected", record.getString("status"));
        assertEquals("package-mismatch", record.getString("reason"));
    }

    /** Null expected values bypass the comparison entirely, strict mode or not. */
    @Test
    public void testCompactNullExpectedValuesBypassTheCheck() {
        TestRunSpecs.installMop("ape.mopStrictPackageMatch", "true");
        assertNotNull(loadCompactFixture(COMPACT, false));
    }

    // --- Scenarios: reject vocabulary and the unset path ---------------------

    /**
     * The compact path's whole reject vocabulary, driven one reason at a time. The three reasons
     * that are missing here — {@code too-large}, {@code oom}, {@code incomplete} — are missing
     * because their mechanisms are not on this path: there is no parse budget, no OOM catch and no
     * completeness sentinel to fail. They still exist on the full-JSON path, which is group 4's
     * oracle, and they die with it in 5.1/5.2.
     */
    @Test
    public void testCompactRejectVocabulary() throws Exception {
        assertEquals("file-missing", onlyCompactLoadRecord("/nonexistent/artifact.json", false)
                .getString("reason"));
        assertEquals("parse-error", onlyCompactLoadRecord(writeTempJson("{ not json "), false)
                .getString("reason"));
        assertEquals("version-mismatch", onlyCompactLoadRecord(
                writeTempJson("{\"formatVersion\":2,\"package\":\"p\"}"), false).getString("reason"));
        assertEquals("an artifact with no version at all is the same rejection",
                "version-mismatch",
                onlyCompactLoadRecord(writeTempJson("{\"package\":\"p\"}"), false)
                        .getString("reason"));
    }

    /**
     * The version gate replaced the {@code "complete": true} sentinel rather than joining it: the
     * generator derives only from complete analyses, so a versioned artifact is complete by
     * construction and demanding the sentinel too would reject every artifact ever produced.
     */
    @Test
    public void testCompactArtifactNeedsNoCompletenessSentinel() throws Exception {
        assertNotNull(MopData.loadCompact(writeTempJson(compactArtifact("")), null, null, false,
                new NoopSink()));
    }

    /** Path unset ⇒ MOP disabled: no load, no record, no reject (exploration stays SATA). */
    @Test
    public void testCompactNullPathLoadsAndRecordsNothing() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        assertNull(MopData.loadCompact(null, null, null, false,
                new NdjsonSink(new PrintStream(buffer, true, "UTF-8"))));
        assertTrue(recordsIn(buffer).isEmpty());
    }
}
