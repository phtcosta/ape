package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.runtime.Feature;
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
 * Unit tests for MopData, in two layers. The WTG data-layer tests build a model directly through
 * the {@code MopData.forTest()} factory and exercise the query API without going near a file; the
 * loader tests read real and synthetic compact artifacts through {@code MopData.load}, which is
 * JVM-runnable because the reader uses {@code org.json} rather than
 * {@code android.util.JsonReader} (design D21).
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
    private static MopData buildTestData(Map<String, List<StaticGuiModel.WtgTransition>> wtg,
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
        Map<String, List<StaticGuiModel.WtgTransition>> wtg = new HashMap<>();
        List<StaticGuiModel.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new StaticGuiModel.WtgTransition(
                "menu_item_cipher",
                "br.unb.cic.cryptoapp.cipher.CipherActivity"));
        transitions.add(new StaticGuiModel.WtgTransition(
                "menu_item_message_digest",
                "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity"));
        wtg.put("br.unb.cic.cryptoapp.MainActivity", transitions);

        Set<String> mopActivities = new HashSet<>();
        mopActivities.add("br.unb.cic.cryptoapp.cipher.CipherActivity");
        mopActivities.add("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity");

        MopData data = buildTestData(wtg, mopActivities);

        assertTrue("WTG data should be present", data.hasWtgData());

        List<StaticGuiModel.WtgTransition> result =
                data.getWtgTransitions("br.unb.cic.cryptoapp.MainActivity");
        assertEquals(2, result.size());
        assertTrue("suffixed key no longer used (INV-WTG-04)",
                data.getWtgTransitions("br.unb.cic.cryptoapp.MainActivity#OptionsMenu").isEmpty());

        StaticGuiModel.WtgTransition t0 = result.get(0);
        assertEquals("menu_item_cipher", t0.widgetName);
        assertEquals("br.unb.cic.cryptoapp.cipher.CipherActivity", t0.targetActivity);

        StaticGuiModel.WtgTransition t1 = result.get(1);
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
        Map<String, List<StaticGuiModel.WtgTransition>> wtg = new HashMap<>();
        List<StaticGuiModel.WtgTransition> transitions = new ArrayList<>();
        // Only the click event from a mixed transition (implicit_home + click)
        transitions.add(new StaticGuiModel.WtgTransition(
                "search",
                "com.example.SearchActivity"));
        wtg.put("com.example.MainActivity", transitions);

        MopData data = buildTestData(wtg, new HashSet<String>());

        List<StaticGuiModel.WtgTransition> result = data.getWtgTransitions("com.example.MainActivity");
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
        Map<String, List<StaticGuiModel.WtgTransition>> wtg = new HashMap<>();
        List<StaticGuiModel.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new StaticGuiModel.WtgTransition(
                "btn",
                "com.example.Target"));
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
        Map<String, List<StaticGuiModel.WtgTransition>> wtg = new HashMap<>();
        List<StaticGuiModel.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new StaticGuiModel.WtgTransition(
                "menu_item_message_digest",
                "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity"));
        wtg.put("br.unb.cic.cryptoapp.MainActivity", transitions);

        MopData data = buildTestData(wtg, new HashSet<String>());

        List<StaticGuiModel.WtgTransition> result =
                data.getWtgTransitions("br.unb.cic.cryptoapp.MainActivity");
        assertEquals(1, result.size());
        assertEquals("menu_item_message_digest", result.get(0).widgetName);
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
        Map<String, List<StaticGuiModel.WtgTransition>> wtg = new HashMap<>();
        List<StaticGuiModel.WtgTransition> transitions = new ArrayList<>();
        transitions.add(new StaticGuiModel.WtgTransition(
                "buttonCipher",
                "br.unb.cic.cryptoapp.cipher.CipherActivity"));
        transitions.add(new StaticGuiModel.WtgTransition(
                "buttonMessageDigest",
                "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity"));
        transitions.add(new StaticGuiModel.WtgTransition(
                "buttonGenerated",
                "br.unb.cic.cryptoapp.generated.CryptographyActivity"));
        wtg.put("br.unb.cic.cryptoapp.MainActivity", transitions);

        MopData data = buildTestData(wtg, new HashSet<String>());

        List<StaticGuiModel.WtgTransition> result =
                data.getWtgTransitions("br.unb.cic.cryptoapp.MainActivity");
        assertEquals(3, result.size());
    }

    /**
     * A WTG edge carries the two fields its consumers read, and nothing else.
     *
     * <p>The producer's widget class was a third field until the cutover: it was parsed, stored and
     * exposed, and no scoring, routing or frontier decision ever consulted it. It is asserted here
     * as an absence — a two-argument constructor — rather than left to be noticed, because the
     * cheapest way for a dropped field to come back is for nothing to have said it was dropped.
     */
    @Test
    public void testWtgTransitionCarriesOnlyTheConsumedPair() {
        StaticGuiModel.WtgTransition t = new StaticGuiModel.WtgTransition(
                "btn_encrypt", "com.example.EncryptActivity");
        assertEquals("btn_encrypt", t.widgetName);
        assertEquals("com.example.EncryptActivity", t.targetActivity);
        assertEquals("the edge carries exactly widgetName and targetActivity",
                2, StaticGuiModel.WtgTransition.class.getFields().length);
    }

    // Component-decoding tests live in ComponentInfoTest; the backward-compat
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
    // Loader tests over the derived artifact via MopData.load
    // =========================================================================

    /**
     * A real full static-analysis JSON on the classpath, the September (gh60) cryptoapp document.
     * Nothing loads it: the rejection scenario needs a real full JSON to be the document the loader
     * refuses (INV-MOP-34). It is no longer the compact fixture's source; that is {@link #SOURCE}.
     */
    private static final String FRESH = "cryptoapp.apk.gh60-fresh.json";
    /**
     * The gh120 static-analysis document the format-2 compact fixture was derived from (task 10.1).
     * It sits in {@code test-apks/}, not on the classpath, and is read relative to the working
     * directory, which surefire leaves at {@code ${basedir}} (as {@code GoldenFile} does). The
     * provenance test digests it to check the chain source bytes → generator → wire → record.
     */
    private static final String SOURCE = "test-apks/cryptoapp.apk.json";
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

    // -------------------------------------------------------------------------
    // Task 5.3/5.4 — [APE-MOP-DATA] load status line (INV-MOP-21)
    // -------------------------------------------------------------------------

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

    private static JSONObject onlyLoadRecord(String path, boolean activitySourceComponents)
            throws Exception {
        return onlyLoadRecord(path, null, null, activitySourceComponents);
    }

    private static JSONObject onlyLoadRecord(String path, String pkg, String main,
            boolean activitySourceComponents) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        MopData.load(path, pkg, main, activitySourceComponents,
                new NdjsonSink(new PrintStream(buffer, true, "UTF-8")));
        List<JSONObject> records = recordsIn(buffer);
        assertEquals("exactly one MOP_DATA record per load", 1, records.size());
        assertEquals("MOP_DATA", records.get(0).getString("type"));
        return records.get(0);
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
        JSONObject record = onlyLoadRecord(fixturePath(COMPACT), false);
        assertEquals("loaded", record.getString("status"));
        assertEquals(2, record.getInt("formatVersion"));
        assertEquals("sha256:" + sha256Hex(new File(SOURCE).getAbsolutePath()),
                record.getString("sourceDigest"));
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
        // gh120 links the D8 wrapper to its lambda body, so the exact join flags executeButton and
        // the class recovery has nothing left to do: no synthetic lambda, nothing recovered.
        assertEquals(4, record.getInt("handlersUnmatched"));
        assertEquals(0, record.getInt("syntheticLambda"));
        assertEquals(0, record.getInt("recovered"));
        assertEquals(3, record.getInt("mopActivities"));
        // Vacuously 0 on this fixture: cryptoapp's augmented set equals its widget-derived one, so
        // nothing here can distinguish the wire-set reading from the retired applied-augmentation
        // one. The synthetic that does is task 3.5's.
        assertEquals(0, record.getInt("mopActsAugmented"));
        assertFalse("the flat transition list is not reinstated at stage 7 either",
                record.has("transitions"));

        // The three format-2 counts (design D23): 23 direct targets then 4 boundary ones, the two
        // handler classes, and no malformed pair from a conforming generator.
        assertEquals(27, record.getInt("targets"));
        assertEquals(2, record.getInt("handlers"));
        assertEquals(0, record.getInt("droppedPairs"));
    }

    /**
     * The synthetic fixture is derived too (task 10.1): its digest is the one of the synthetic
     * analyser document beside it on the classpath, so the chain holds for both fixtures.
     */
    @Test
    public void testSyntheticFixtureIsDerivedFromItsAnalyserDocument() throws Exception {
        JSONObject record = onlyLoadRecord(fixturePath(SELECTION), false);
        assertEquals("loaded", record.getString("status"));
        assertEquals("sha256:" + sha256Hex(fixturePath("synthetic-activity-selection.apk.json")),
                record.getString("sourceDigest"));
        assertEquals(1, record.getInt("targets"));
        assertEquals(1, record.getInt("handlers"));
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

    // 15.23
    @Test
    public void testPlanControlledFlagsBindTheirJarDefaults() {
        // The four parameters below are resolved into the plan, not held in a mutable static. A
        // MOP arm that states none of them inherits the jar defaults; a different value is a
        // different plan, which is why there is no assignment to observe here.
        RunSpec spec = TestRunSpecs.installMop("ape.addons", "true", "ape.static", "true");
        assertEquals(250, spec.mop().weightOpenMenu());
        assertTrue(spec.staticData().fuzzInputTyped());
        assertFalse(spec.staticData().strictPackageMatch());
        // activity-frontier: default true (it gates the stagnation launcher).
        assertTrue(spec.has(Feature.MOP_LAUNCHER));
    }

    /**
     * eventType normalization (INV-MOP-08), as a unit and end to end across the wire.
     *
     * <p>The end-to-end half used to run through the full-JSON parser's listener derivation and now
     * runs through the artifact, which is where the question moved rather than where it went away:
     * the generator writes whatever token the producer used, so a wire key in snake_case must still
     * answer a camelCase query. The key is deliberately one no other assertion in this file uses,
     * and the widget carries no second entry, so a loader that skipped normalization on ingest
     * would leave the map holding a key nothing can reach — and {@code longClick} would fall back
     * to a false aggregate rather than to this entry.
     */
    @Test
    public void testEventTypeNormalizationSnakeCamelEqual() throws Exception {
        // snake_case and camelCase of the same event collapse to one canonical token
        assertEquals(MopData.normalizeEventType("longClick"),
                MopData.normalizeEventType("long_click"));
        assertEquals(MopData.normalizeEventType("itemSelected"),
                MopData.normalizeEventType("item_selected"));
        assertEquals("click", MopData.normalizeEventType("click"));
        assertNull(MopData.normalizeEventType(null));

        String path = writeTempJson(compactArtifact(",\"widgets\":{\"p.A\":{\"b\":{\"mop\":"
                + "{\"item_selected\":\"direct\"}}}}"));
        MopData.Widget w = MopData.load(path, null, null, false, new NoopSink())
                .getWidget("p.A", "b");
        assertTrue("snake_case wire key matches camelCase query", w.isDirectMop("itemSelected"));
        assertTrue("snake_case wire key matches snake_case query", w.isDirectMop("item_selected"));
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
     * — {@code A} and {@code H} in both, {@code B} only in the augmented set — plus a gateway
     * {@code G} whose one WTG edge lands on {@code B} and a gateway {@code H} that qualifies on its
     * own flagged menu widget. It is derived by the rv-android generator from
     * {@code synthetic-activity-selection.apk.json} (task 10.1), which is why {@code H} sits in
     * {@code mopActivities}: an OPTIONSMENU window with a flagged widget flags its activity.
     *
     * <p>It is a checked-in resource rather than a temp file because two things need it and only
     * one of them is here: task 4.1's equivalence gate reuses it as its INV-DRV-06 member, and a
     * synthetic that is written and deleted inside a test method has to be written twice.
     */
    private static final String SELECTION = "synthetic-activity-selection.mop.json";

    /**
     * A format-2 artifact carrying the two members format 2 makes mandatory — 27 targets, as in the
     * cryptoapp fixture, and an empty handler table — plus whatever top-level members the caller
     * appends. A caller that states {@code targets} or {@code handlers} itself uses
     * {@link #formatTwoEnvelope}, since a JSON object admits one value per key.
     */
    private static String compactArtifact(String members) {
        return formatTwoEnvelope(",\"targets\":27,\"handlers\":{}" + members);
    }

    /** The bare format-2 envelope: version, package and main activity, nothing else. */
    private static String formatTwoEnvelope(String members) {
        return "{\"formatVersion\":2,\"package\":\"p\",\"mainActivity\":\"p.A\"" + members + "}";
    }

    /** The pairs as a readable string, so a mismatch shows both sides: {@code [[16,3],[17,3]]}. */
    private static String pairs(int[][] pairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pairs.length; i++) {
            sb.append(i == 0 ? "" : ",").append(Arrays.toString(pairs[i]).replace(" ", ""));
        }
        return sb.append("]").toString();
    }

    private static MopData loadFixture(String name, boolean activitySourceComponents) {
        return MopData.load(fixturePath(name), null, null, activitySourceComponents,
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
     * The flag set is three widgets. The third, {@code executeButton}, has a
     * {@code $$ExternalSyntheticLambda0} wrapper as its handler; under gh120 the producer lists that
     * wrapper in {@code reachability[]} as reaching, linked to its own
     * {@code lambda$setupExecuteButton$0} body, so the exact join flags it and the class recovery
     * is not consulted (rv-android INV-DRV-09; {@code MOP_DATA.recovered} is 0). An artifact
     * asserting two is asserting that the wrapper's join failed in the generator.
     */
    @Test
    public void testCompactFixtureFlagsExactlyTheThreeMopWidgets() throws Exception {
        MopData d = loadFixture(COMPACT, false);
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

    // --- Scenario: Real cryptoapp fixture loads every typed field (format 2) ---

    /**
     * The format-2 members of the cryptoapp fixture, as the scenario states them: 27 targets, the
     * three flagged widgets' {@code click} pairs in wire order — none for {@code btn_cipher_encrypt},
     * whose nearest targets lie at {@code d = 4}, beyond the generator's {@code d <= 3} cut (design
     * D15 as amended 2026-10-09) — and no pair on any other event: only a click reads an aggregate
     * of pairs, and only on a widget with no {@code click} key.
     */
    @Test
    public void testCompactFixtureTargetsAndWidgetPairs() {
        MopData d = loadFixture(COMPACT, false);
        assertEquals("23 direct targets, then 4 boundary targets", 27, d.targetCount());

        assertEquals("[[22,2]]", pairs(d.getWidget(MDA, "buttonGenerateHash").dist("click")));
        assertEquals("d = 4 is beyond the cut", 0,
                d.getWidget(CIPHER, "btn_cipher_encrypt").dist("click").length);
        assertEquals("[[16,3],[17,3],[18,3]]",
                pairs(d.getWidget(CRYPTO, "executeButton").dist("click")));

        assertEquals("a long-click reads its own key alone", 0,
                d.getWidget(CRYPTO, "executeButton").dist("longClick").length);
        assertEquals("an unflagged widget carries no pair", 0,
                d.getWidget(MDA, "editTextMessageDigest").dist("click").length);
    }

    /**
     * The handler table lists exactly the two classes of the fixture that declare
     * {@code onClick(android.view.View)}, each {@code click}-transitive with its widget's pairs (none
     * for {@code CipherActivity$1}, whose targets lie at {@code d = 4}).
     * "Exactly" is asserted through the census, which counts the table, and through the wire, which
     * names its keys; a class the table does not list answers null.
     */
    @Test
    public void testCompactFixtureHandlerTable() throws Exception {
        MopData d = loadFixture(COMPACT, false);
        String cipherListener = "br.unb.cic.cryptoapp.cipher.CipherActivity$1";
        String executeLambda =
                "br.unb.cic.cryptoapp.generated.CryptographyActivity$$ExternalSyntheticLambda0";

        JSONObject wire = new JSONObject(readText(fixturePath(COMPACT))).getJSONObject("handlers");
        Set<String> wireClasses = new HashSet<>();
        for (Iterator<String> it = wire.keys(); it.hasNext(); ) {
            wireClasses.add(it.next());
        }
        assertEquals(new HashSet<>(Arrays.asList(cipherListener, executeLambda)), wireClasses);
        assertEquals(2, onlyLoadRecord(fixturePath(COMPACT), false).getInt("handlers"));

        String[][] expected = {{cipherListener, CIPHER, "btn_cipher_encrypt"},
                {executeLambda, CRYPTO, "executeButton"}};
        for (String[] row : expected) {
            MopData.Handler h = d.getHandler(row[0]);
            assertNotNull("listed handler class " + row[0], h);
            assertEquals(row[0], h.className);
            assertTrue(h.isTransitiveMop("click"));
            assertFalse(h.isDirectMop("click"));
            assertEquals("the handler's pairs are its widget's",
                    pairs(d.getWidget(row[1], row[2]).dist("click")), pairs(h.dist("click")));
        }
        assertNull("an unlisted class falls back to the id key",
                d.getHandler("androidx.appcompat.app.AppCompatViewInflater$DeclaredOnClickListener"));
        assertNull(d.getHandler(null));
    }

    /**
     * The per-activity pairs the launcher reads (design D20), in wire order. {@code MainActivity}
     * has none: its only reaching method is its constructor, which {@code activityDist} leaves out
     * (design D15).
     */
    @Test
    public void testCompactFixtureActivityDistances() {
        MopData d = loadFixture(COMPACT, false);
        assertEquals("[[22,2]]", pairs(d.activityDist(MDA)));
        assertEquals("[[0,2],[1,2]]", pairs(d.activityDist(CIPHER)));
        assertEquals("[[12,0],[13,0],[14,0]]", pairs(d.activityDist(CRYPTO)));
        assertEquals(0, d.activityDist(MAIN).length);
        assertEquals(0, d.activityDist(null).length);
    }

    /**
     * With {@code mopActivitySourceComponents=true} MainActivity stays out: the A′ source 3 leaves
     * out a class's own {@code <init>}/{@code <clinit>}, so on this fixture the augmented set
     * equals the widget-derived one (design D15).
     */
    @Test
    public void testCompactFixtureAugmentedSetLeavesMainActivityOut() {
        MopData d = loadFixture(COMPACT, true);
        assertFalse(d.activityHasMop(MAIN));
        assertEquals(new HashSet<>(Arrays.asList(MDA, CIPHER, CRYPTO)), d.getMopActivities());
    }

    // --- Scenarios: format-2 distance members --------------------------------

    /**
     * Scenario "Per-event-type reachability maps built", the pairs: per-event pairs in wire order.
     * The {@code mop} map's fallback does not extend to {@code dist}: a long-click reads its own key
     * alone, and the click aggregate is read only for a widget with no {@code click} key. Event
     * keys are normalized on ingest like the flags are.
     */
    @Test
    public void testPerEventPairsKeepWireOrderAndHaveNoFallback() throws Exception {
        String path = writeTempJson(compactArtifact(",\"widgets\":{\"p.A\":{"
                + "\"b\":{\"mop\":{\"click\":\"both\",\"scroll\":\"none\"},"
                + "\"dist\":{\"click\":[[2,0],[5,1]]}},"
                + "\"c\":{\"mop\":{\"long_click\":\"transitive\"},"
                + "\"dist\":{\"long_click\":[[7,2]]}}}}"));
        MopData d = MopData.load(path, null, null, false, new NoopSink());
        MopData.Widget b = d.getWidget("p.A", "b");
        assertEquals("[[2,0],[5,1]]", pairs(b.dist("click")));
        assertEquals(0, b.dist("scroll").length);
        assertEquals("the flag falls back to the aggregate; the pairs do not",
                0, b.dist("longClick").length);
        assertTrue(b.isDirectMop("longClick"));
        assertEquals("snake_case wire key answers a camelCase query",
                "[[7,2]]", pairs(d.getWidget("p.A", "c").dist("longClick")));
        assertEquals(0, b.dist(null).length);
    }

    /**
     * The widget keeps its click aggregate (design D17 as amended 2026-10-09): the merge by the
     * minimum per target of the pairs of every event but {@code longclick}, {@code itemlongclick},
     * {@code implicitcreatecontextmenu} and {@code scroll}, in wire order. {@code dist(event)} keeps
     * its meaning: an event's own key alone.
     */
    @Test
    public void testWidgetKeepsItsClickAggregate() throws Exception {
        String path = writeTempJson(compactArtifact(",\"widgets\":{\"p.A\":{"
                + "\"t\":{\"mop\":{\"drag\":\"transitive\",\"select\":\"transitive\","
                + "\"long_click\":\"transitive\",\"scroll\":\"transitive\"},"
                + "\"dist\":{\"drag\":[[9,2],[4,3]],\"select\":[[9,1]],\"long_click\":[[3,0]],"
                + "\"scroll\":[[2,0]],\"item_long_click\":[[5,0]],"
                + "\"implicit_create_context_menu\":[[6,0]]}},"
                + "\"n\":{\"mop\":{\"longclick\":\"direct\"}}}}"));
        MopData d = MopData.load(path, null, null, false, new NoopSink());
        MopData.Widget t = d.getWidget("p.A", "t");
        assertEquals("[[9,1],[4,3]]", pairs(t.clickAggregate()));
        assertEquals("dist(click) keeps its meaning", 0, t.dist("click").length);
        assertEquals("[[3,0]]", pairs(t.dist("longClick")));
        assertEquals("a widget with no click-firing pair has an empty aggregate",
                0, d.getWidget("p.A", "n").clickAggregate().length);
    }

    /**
     * Two wire {@code dist} keys that normalize onto one event merge by the minimum per target and
     * keep the wire's order (distance, then index), as {@code orInto} merges the flags. A
     * conforming generator normalizes its keys and never emits this; the loader must still not
     * lose a pair to it.
     */
    @Test
    public void testDistKeysCollapsingOntoOneEventMergeByMinimumPerTarget() throws Exception {
        String path = writeTempJson(compactArtifact(",\"widgets\":{\"p.A\":{\"b\":{\"dist\":"
                + "{\"long_click\":[[3,1],[5,2]],\"longClick\":[[3,0],[6,2]]}}}}"));
        MopData d = MopData.load(path, null, null, false, new NoopSink());
        assertEquals("[[3,0],[5,2],[6,2]]", pairs(d.getWidget("p.A", "b").dist("longclick")));
    }

    /**
     * The handler table's event keys are normalized and its flags decoded as the widgets' are. A
     * listed class with {@code mop} {@code none} and no {@code dist} is kept: it states that the
     * handler reaches no target (design D15, D17).
     */
    @Test
    public void testHandlerRecordsDecodeLikeWidgetsAndKeepTheNoneClass() throws Exception {
        String path = writeTempJson(formatTwoEnvelope(",\"targets\":4,\"handlers\":{"
                + "\"p.Compose$1\":{\"mop\":{\"click\":\"transitive\",\"longclick\":\"transitive\"},"
                + "\"dist\":{\"click\":[[0,1]],\"long_click\":[[3,2]]}},"
                + "\"p.Quiet\":{\"mop\":{\"click\":\"none\"}}}"));
        MopData d = MopData.load(path, null, null, false, new NoopSink());

        MopData.Handler compose = d.getHandler("p.Compose$1");
        assertTrue(compose.isTransitiveMop("click"));
        assertTrue(compose.isTransitiveMop("longClick"));
        assertEquals("[[0,1]]", pairs(compose.dist("click")));
        assertEquals("[[3,2]]", pairs(compose.dist("longClick")));

        MopData.Handler quiet = d.getHandler("p.Quiet");
        assertNotNull("a class that reaches nothing is still listed", quiet);
        assertFalse(quiet.isTransitiveMop("click"));
        assertFalse(quiet.isDirectMop("click"));
        assertEquals(0, quiet.dist("click").length);
    }

    /** The scenario's malformed pairs, verbatim: the index 27 is outside [0, 27), -1 below it. */
    @Test
    public void testMalformedDistancePairDroppedAndCounted() throws Exception {
        String path = writeTempJson(compactArtifact(",\"widgets\":{\"p.A\":{\"b\":{"
                + "\"mop\":{\"click\":\"transitive\"},"
                + "\"dist\":{\"click\":[[16,3],[27,1],[-1,2]]}}}}"));
        JSONObject record = onlyLoadRecord(path, false);
        assertEquals("loaded", record.getString("status"));
        assertEquals(2, record.getInt("droppedPairs"));
        assertEquals("[[16,3]]", pairs(MopData.load(path, null, null, false, new NoopSink())
                .getWidget("p.A", "b").dist("click")));
    }

    /**
     * Every malformed shape the loader requirement names — not a two-integer array, an index outside
     * {@code [0, targets)}, a negative distance — is dropped and counted at all three sites that
     * carry pairs: a widget's {@code dist}, {@code activityDist} and a handler's {@code dist}.
     */
    @Test
    public void testMalformedPairsAreCountedAtEverySite() throws Exception {
        String path = writeTempJson(formatTwoEnvelope(",\"targets\":5"
                + ",\"widgets\":{\"p.A\":{\"b\":{\"dist\":{\"click\":[[1,1],[2],[3,1,1]]}}}}"
                + ",\"activityDist\":{\"p.A\":[[0,0],[1,\"2\"],[2.5,1],[4,-1]]}"
                + ",\"handlers\":{\"p.L\":{\"dist\":{\"click\":[[4,1],[5,1],\"x\",null]}}}"));
        JSONObject record = onlyLoadRecord(path, false);
        assertEquals("loaded", record.getString("status"));
        assertEquals("2 on the widget, 3 on the activity, 3 on the handler",
                8, record.getInt("droppedPairs"));

        MopData d = MopData.load(path, null, null, false, new NoopSink());
        assertEquals("[[1,1]]", pairs(d.getWidget("p.A", "b").dist("click")));
        assertEquals("[[0,0]]", pairs(d.activityDist("p.A")));
        assertEquals("[[4,1]]", pairs(d.getHandler("p.L").dist("click")));
    }

    /**
     * {@code targets} and {@code handlers} are always emitted by the generator, so their absence,
     * or a {@code targets} that is not an integer, is a generator↔jar skew and a
     * {@code parse-error}, not an empty table.
     */
    @Test
    public void testFormatTwoArtifactMissingAFormatTwoMemberIsRejected() throws Exception {
        String[] skewed = {
                formatTwoEnvelope(",\"handlers\":{}"),
                formatTwoEnvelope(",\"targets\":27"),
                formatTwoEnvelope(",\"targets\":\"27\",\"handlers\":{}"),
                formatTwoEnvelope(",\"targets\":27.5,\"handlers\":{}"),
        };
        for (String artifact : skewed) {
            String path = writeTempJson(artifact);
            assertNull(artifact, MopData.load(path, null, null, false, new NoopSink()));
            JSONObject record = onlyLoadRecord(path, false);
            assertEquals(artifact, "rejected", record.getString("status"));
            assertEquals(artifact, "parse-error", record.getString("reason"));
        }
    }

    /**
     * An artifact with no target (a pre-gh120 document or an empty GATOR result derives to it)
     * loads and runs without distance data; the census says so with {@code targets: 0}.
     */
    @Test
    public void testArtifactWithNoTargetLoadsWithoutDistanceData() throws Exception {
        String path = writeTempJson(formatTwoEnvelope(",\"targets\":0,\"handlers\":{}"
                + ",\"widgets\":{\"p.A\":{\"b\":{\"mop\":{\"click\":\"transitive\"}}}}"
                + ",\"mopActivities\":[\"p.A\"]"));
        MopData d = MopData.load(path, null, null, false, new NoopSink());
        assertNotNull(d);
        assertEquals(0, d.targetCount());
        assertTrue("the flags still load", d.getWidget("p.A", "b").isTransitiveMop("click"));
        assertEquals(0, d.getWidget("p.A", "b").dist("click").length);

        JSONObject record = onlyLoadRecord(path, false);
        assertEquals("loaded", record.getString("status"));
        assertEquals(0, record.getInt("targets"));
        assertEquals(0, record.getInt("handlers"));
        assertEquals(0, record.getInt("droppedPairs"));
    }

    /** The gh51-D5 timeout bucket in format 2: every member present and empty, and it loads. */
    @Test
    public void testCompleteButEmptyFormatTwoArtifactParsesCleanly() throws Exception {
        String path = writeTempJson(formatTwoEnvelope(",\"targets\":0,\"handlers\":{}"
                + ",\"widgets\":{},\"wtg\":{},\"optionsMenus\":[],\"activityDist\":{}"
                + ",\"mopActivities\":[],\"mopActivitiesAugmented\":[]"
                + ",\"components\":{\"activities\":[],\"providers\":[],\"receivers\":[],"
                + "\"services\":[]}"));
        MopData d = MopData.load(path, null, null, false, new NoopSink());
        assertNotNull(d);
        assertTrue(d.getReceivers().isEmpty());
        assertTrue(d.getServices().isEmpty());
        assertTrue(d.getActivities().isEmpty());
        assertTrue(d.getProviders().isEmpty());
        assertTrue(d.getMopActivities().isEmpty());
        assertTrue(d.getWtgTransitions("p.A").isEmpty());
        assertFalse(d.hasWtgData());
        assertEquals(0, d.activityDist("p.A").length);
        assertNull(d.getHandler("p.L"));
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
        MopData d = loadFixture(COMPACT, false);
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
        MopData d = loadFixture(COMPACT, false);

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
        MopData.Widget w = loadFixture(COMPACT, false).getWidget(CIPHER, "btn_cipher_encrypt");
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
        assertNull(MopData.load(fixturePath(FRESH), null, null, false, new NoopSink()));
        JSONObject record = onlyLoadRecord(fixturePath(FRESH), false);
        assertEquals("rejected", record.getString("status"));
        assertEquals("version-mismatch", record.getString("reason"));
        assertFalse("a load that never reached an artifact has no digest to report",
                record.has("sourceDigest"));
    }

    /**
     * A format-1 compact artifact is the same rejection (INV-MOP-34): the jar holds no reader for
     * format 1, so even one that carries every format-2 member is refused by the gate before any
     * key is read.
     */
    @Test
    public void testFormatOneCompactArtifactIsRejectedAsVersionMismatch() throws Exception {
        String path = writeTempJson("{\"formatVersion\":1,\"package\":\"p\",\"mainActivity\":\"p.A\""
                + ",\"targets\":0,\"handlers\":{}"
                + ",\"widgets\":{\"p.A\":{\"b\":{\"mop\":{\"click\":\"transitive\"}}}}"
                + ",\"mopActivities\":[\"p.A\"]}");
        assertNull(MopData.load(path, null, null, false, new NoopSink()));
        JSONObject record = onlyLoadRecord(path, false);
        assertEquals("rejected", record.getString("status"));
        assertEquals("version-mismatch", record.getString("reason"));
        assertEquals("a rejected version is not one the jar vouches for",
                0, record.getInt("formatVersion"));
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
        MopData.Widget w = MopData.load(path, null, null, false, new NoopSink())
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
        MopData d = MopData.load(path, null, null, false, new NoopSink());

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
        MopData d = loadFixture(SELECTION, false);
        // H is in the widget-derived set because the generator derives it: an OPTIONSMENU window
        // holding a flagged widget makes its activity flagged.
        assertEquals(new HashSet<>(Arrays.asList("A", "H")), d.getMopActivities());
        assertTrue(d.activityHasMop("A"));
        assertTrue(d.activityHasMop("H"));
        assertFalse("B is augmented-only", d.activityHasMop("B"));
        assertFalse("G's only edge lands on B, which this selection excludes",
                d.activityHasMopOptionsMenu("G"));
        assertTrue("H qualifies on condition 1 under either selection",
                d.activityHasMopOptionsMenu("H"));
    }

    /** Flag on: the augmented set, and the same gateway opens. */
    @Test
    public void testFlagOnSelectsTheAugmentedSetAndOpensTheGateway() {
        MopData d = loadFixture(SELECTION, true);
        assertEquals(new HashSet<>(Arrays.asList("A", "B", "H")), d.getMopActivities());
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
        JSONObject off = onlyLoadRecord(fixturePath(SELECTION), false);
        JSONObject on = onlyLoadRecord(fixturePath(SELECTION), true);
        assertEquals(1, off.getInt("mopActsAugmented"));
        assertEquals("availability does not vary with the flag", 1, on.getInt("mopActsAugmented"));
        assertEquals("mopActivities is the selected set", 2, off.getInt("mopActivities"));
        assertEquals(3, on.getInt("mopActivities"));
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
        MopData d = MopData.load(path, null, null, false, new NoopSink());
        assertEquals("myapp://detail/x", activityNamed(d, "p.X").deepLinkUri);
        assertNull("absent on the wire means the explicit-component intent",
                activityNamed(d, "p.Y").deepLinkUri);

        for (ComponentInfo.ActivityInfo a : loadFixture(COMPACT, false).getActivities()) {
            assertNull("cryptoapp declares no deep link", a.deepLinkUri);
            assertTrue("no intent-filter data block exists on the wire to walk",
                    a.intentFilters.isEmpty());
        }
    }

    // --- Scenario: Unknown keys in a v1 artifact are ignored -----------------

    /**
     * Tolerance here is the absence of a check, not an affordance: rejecting unknown keys would
     * mean writing code. Under the coordinated cut the generator and the jar ship together, so an
     * unknown key in a format-2 artifact is a skew signal to read in the trace, not a version to
     * accommodate — and the fields around it must still decode. The test name keeps "V1" because
     * the spec's scenario header does (the archive matches on it).
     */
    @Test
    public void testUnknownKeysInAV1ArtifactAreIgnored() throws Exception {
        String path = writeTempJson(formatTwoEnvelope(",\"futureBlock\":{\"whatever\":[1,2,3]}"
                + ",\"targets\":3,\"mopActivities\":[\"A\"]"
                + ",\"widgets\":{\"p.A\":{\"b\":{\"hint\":\"h\",\"futureWidgetKey\":7,"
                + "\"mop\":{\"click\":\"transitive\"},\"dist\":{\"click\":[[2,1]]}}}}"
                + ",\"handlers\":{\"p.L\":{\"futureHandlerKey\":true,"
                + "\"mop\":{\"click\":\"transitive\"},\"dist\":{\"click\":[[1,0]]}}}"));
        MopData d = MopData.load(path, null, null, false, new NoopSink());
        assertNotNull(d);
        assertEquals("h", d.getWidget("p.A", "b").hint);
        assertTrue(d.getWidget("p.A", "b").isTransitiveMop("click"));
        assertEquals("[[2,1]]", pairs(d.getWidget("p.A", "b").dist("click")));
        assertTrue(d.activityHasMop("A"));
        assertTrue(d.getHandler("p.L").isTransitiveMop("click"));
        assertEquals("[[1,0]]", pairs(d.getHandler("p.L").dist("click")));
    }

    // --- Scenarios: package / mainActivity sanity check ----------------------

    @Test
    public void testCompactPackageMismatchWarnsByDefault() {
        assertNotNull("default is warn-only, and the artifact is still served",
                MopData.load(fixturePath(COMPACT), "x.y.z.OTHER", null, false,
                        new NoopSink()));
    }

    @Test
    public void testCompactPackageMismatchRejectsWhenStrict() throws Exception {
        TestRunSpecs.installMop("ape.mopStrictPackageMatch", "true");
        assertNull(MopData.load(fixturePath(COMPACT), "x.y.z.OTHER", null, false,
                new NoopSink()));
        JSONObject record = onlyLoadRecord(fixturePath(COMPACT), "x.y.z.OTHER", null, false);
        assertEquals("rejected", record.getString("status"));
        assertEquals("package-mismatch", record.getString("reason"));
    }

    /** Null expected values bypass the comparison entirely, strict mode or not. */
    @Test
    public void testCompactNullExpectedValuesBypassTheCheck() {
        TestRunSpecs.installMop("ape.mopStrictPackageMatch", "true");
        assertNotNull(loadFixture(COMPACT, false));
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
        assertEquals("file-missing", onlyLoadRecord("/nonexistent/artifact.json", false)
                .getString("reason"));
        assertEquals("parse-error", onlyLoadRecord(writeTempJson("{ not json "), false)
                .getString("reason"));
        assertEquals("version-mismatch", onlyLoadRecord(
                writeTempJson("{\"formatVersion\":1,\"package\":\"p\"}"), false).getString("reason"));
        assertEquals("version-mismatch", onlyLoadRecord(
                writeTempJson("{\"formatVersion\":3,\"package\":\"p\"}"), false).getString("reason"));
        assertEquals("an artifact with no version at all is the same rejection",
                "version-mismatch",
                onlyLoadRecord(writeTempJson("{\"package\":\"p\"}"), false)
                        .getString("reason"));
    }

    /**
     * The version gate replaced the {@code "complete": true} sentinel rather than joining it: the
     * generator derives only from complete analyses, so a versioned artifact is complete by
     * construction and demanding the sentinel too would reject every artifact ever produced.
     */
    @Test
    public void testCompactArtifactNeedsNoCompletenessSentinel() throws Exception {
        assertNotNull(MopData.load(writeTempJson(compactArtifact("")), null, null, false,
                new NoopSink()));
    }

    /** Path unset ⇒ MOP disabled: no load, no record, no reject (exploration stays SATA). */
    @Test
    public void testCompactNullPathLoadsAndRecordsNothing() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        assertNull(MopData.load(null, null, null, false,
                new NdjsonSink(new PrintStream(buffer, true, "UTF-8"))));
        assertTrue(recordsIn(buffer).isEmpty());
    }
}
