package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.NoopSink;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * The static provider (guidance-groups "Static Data Root and Split Providers", wtg-navigation
 * "MopData — WTG Parsing (Pass 3)", static-analysis-entrypoints item 7): the WTG view, the widget
 * metadata, package and main activity, and the components with their intent filters, the reduced
 * {@code data} member included — built by {@link StaticArtifactLoader} with no MOP fact in it.
 */
public class StaticGuiModelTest {

    private static final String MAIN = "br.unb.cic.cryptoapp.MainActivity";
    private static final String CIPHER = "br.unb.cic.cryptoapp.cipher.CipherActivity";
    private static final String MDA = "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity";

    /** The loader reads the plan for the package check; a MOP plan is in effect here. */
    @Before
    public void installPlan() {
        TestRunSpecs.installMop();
    }

    @After
    public void clearPlan() {
        RunContext.resetForTest();
    }

    private static StaticGuiModel withWtg(Map<String, List<StaticGuiModel.WtgTransition>> wtg) {
        return new StaticGuiModel(null, null, wtg, null, null, null, null, null);
    }

    /** A format-2 artifact carrying {@code members} beside the mandatory envelope. */
    private static StaticArtifactLoader.Loaded load(String members) throws Exception {
        File f = File.createTempFile("staticgui", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write("{\"formatVersion\":2,\"package\":\"p\",\"mainActivity\":\"p.M\","
                    + "\"targets\":0,\"handlers\":{}" + members + "}");
        }
        StaticArtifactLoader.Loaded loaded = StaticArtifactLoader.load(f.getAbsolutePath(), null,
                null, false, new NoopSink());
        assertNotNull("artifact must load", loaded);
        return loaded;
    }

    // ---- the WTG view (wtg-navigation, INV-WTG-04/05) ------------------------

    /**
     * Scenario "Menu-sourced transition keyed by base activity": the OptionsMenu-sourced edges are
     * keyed under the base activity, so the consumer queries by base activity, and the suffixed key
     * answers nothing.
     */
    @Test
    public void testClickTransitionsKeyedByBaseActivity() {
        Map<String, List<StaticGuiModel.WtgTransition>> wtg = new HashMap<>();
        wtg.put(MAIN, Arrays.asList(
                new StaticGuiModel.WtgTransition("menu_item_cipher", CIPHER),
                new StaticGuiModel.WtgTransition("menu_item_message_digest", MDA)));
        StaticGuiModel model = withWtg(wtg);

        assertTrue(model.hasWtgData());
        List<StaticGuiModel.WtgTransition> result = model.wtgTransitions(MAIN);
        assertEquals(2, result.size());
        assertEquals("menu_item_cipher", result.get(0).widgetName);
        assertEquals(CIPHER, result.get(0).targetActivity);
        assertEquals("menu_item_message_digest", result.get(1).widgetName);
        assertEquals(MDA, result.get(1).targetActivity);
        assertTrue("the suffixed key is not used (INV-WTG-04)",
                model.wtgTransitions(MAIN + "#OptionsMenu").isEmpty());
        assertTrue(model.wtgTransitions("com.example.Unknown").isEmpty());
    }

    /** Scenario "No transitions section": no WTG data, an empty list for any activity. */
    @Test
    public void testNoTransitionsIsGracefullyEmpty() {
        assertFalse(StaticGuiModel.EMPTY.hasWtgData());
        assertTrue(StaticGuiModel.EMPTY.wtgTransitions("com.example.AnyActivity").isEmpty());
        assertFalse(withWtg(new HashMap<String, List<StaticGuiModel.WtgTransition>>())
                .hasWtgData());
    }

    /**
     * A WTG edge carries the two fields its consumers read, and nothing else: the producer's widget
     * class was parsed and exposed once and no decision ever consulted it.
     */
    @Test
    public void testWtgTransitionCarriesOnlyTheConsumedPair() {
        StaticGuiModel.WtgTransition t =
                new StaticGuiModel.WtgTransition("btn_encrypt", "com.example.EncryptActivity");
        assertEquals("btn_encrypt", t.widgetName);
        assertEquals("com.example.EncryptActivity", t.targetActivity);
        assertEquals(2, StaticGuiModel.WtgTransition.class.getFields().length);
    }

    /** The wire {@code wtg} map is transcribed as it stands (INV-WTG-01/04: host-side keying). */
    @Test
    public void testWireWtgIsTranscribed() throws Exception {
        StaticGuiModel model = load(",\"wtg\":{\"p.M\":[{\"widget\":\"go\",\"target\":\"p.D\"},"
                + "{\"widget\":\"more\",\"target\":\"p.E\"}]}").staticModel;
        assertTrue(model.hasWtgData());
        assertEquals(2, model.wtgTransitions("p.M").size());
        assertEquals("p.E", model.wtgTransitions("p.M").get(1).targetActivity);
    }

    // ---- widget metadata, package, main activity -----------------------------

    @Test
    public void testWidgetMetadataPackageAndMainActivity() throws Exception {
        StaticGuiModel model = load(",\"widgets\":{\"p.M\":{"
                + "\"pwd\":{\"inputType\":\"textPassword\",\"hint\":\"Your password\"},"
                + "\"spin\":{\"entries\":[\"a\",\"b\"],\"spinnerMode\":\"dropdown\","
                + "\"mop\":{\"click\":\"transitive\"}}}}").staticModel;
        assertEquals("p", model.packageName());
        assertEquals("p.M", model.mainActivity());

        StaticGuiModel.WidgetMeta pwd = model.widget("p.M", "pwd");
        assertEquals("pwd", pwd.idName);
        assertEquals("textPassword", pwd.inputType);
        assertEquals("Your password", pwd.hint);
        assertTrue(pwd.entries.isEmpty());
        assertNull(pwd.prompt);

        StaticGuiModel.WidgetMeta spin = model.widget("p.M", "spin");
        assertEquals(Arrays.asList("a", "b"), spin.entries);
        assertEquals("dropdown", spin.spinnerMode);
        assertNull("no such widget", model.widget("p.M", "missing"));
        assertNull("no such activity", model.widget("p.X", "pwd"));
    }

    @Test
    public void testWidgetMetaIsImmutable() {
        List<String> entries = new ArrayList<>(Collections.singletonList("a"));
        StaticGuiModel.WidgetMeta meta =
                new StaticGuiModel.WidgetMeta("w", null, null, entries, null, null, null, null);
        entries.add("b");
        assertEquals("a copy, not a view of the caller's list", 1, meta.entries.size());
        try {
            meta.entries.add("c");
            fail("entries is read-only");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    // ---- components and the intent filter's data member (D-B13, D-B14.11) ---

    /**
     * Scenario "a receiver's intent filter keeps its reduced data": the projected {@code data} is
     * parsed list by list, and the lists the derive omits read as empty.
     */
    @Test
    public void testIntentFilterDataIsParsed() throws Exception {
        StaticGuiModel model = load(",\"components\":{\"receivers\":[{"
                + "\"className\":\"com.x.DeepReceiver\",\"reachesMop\":false,"
                + "\"intentFilters\":[{\"actions\":[\"android.intent.action.VIEW\"],"
                + "\"categories\":[],\"data\":{\"schemes\":[\"myapp\"],\"hosts\":[\"detail\"]}}]}],"
                + "\"services\":[{\"className\":\"com.x.Svc\",\"intentFilters\":[{"
                + "\"actions\":[\"com.x.SYNC\"],\"categories\":[],\"data\":{"
                + "\"schemes\":[\"content\",\"file\"],\"paths\":[\"/a\"],\"pathPrefixes\":[\"/b\"],"
                + "\"mimeTypes\":[\"image/png\"]}}]}]}").staticModel;

        ComponentInfo.ReceiverInfo receiver = model.receivers().get(0);
        assertEquals("served whatever its reachesMop", "com.x.DeepReceiver", receiver.className);
        ComponentInfo.IntentFilter.Data data = receiver.intentFilters.get(0).data;
        assertNotNull(data);
        assertEquals(Collections.singletonList("myapp"), data.schemes);
        assertEquals(Collections.singletonList("detail"), data.hosts);
        assertTrue("an omitted list reads as empty", data.paths.isEmpty());
        assertTrue(data.pathPrefixes.isEmpty());
        assertTrue(data.mimeTypes.isEmpty());

        ComponentInfo.IntentFilter.Data svc = model.services().get(0).intentFilters.get(0).data;
        assertEquals(Arrays.asList("content", "file"), svc.schemes);
        assertTrue(svc.hosts.isEmpty());
        assertEquals(Collections.singletonList("/a"), svc.paths);
        assertEquals(Collections.singletonList("/b"), svc.pathPrefixes);
        assertEquals(Collections.singletonList("image/png"), svc.mimeTypes);
    }

    /** A filter without a {@code data} member has no data: the member is additive (format 2). */
    @Test
    public void testAbsentDataMeansNoData() throws Exception {
        StaticGuiModel model = load(",\"components\":{\"receivers\":[{\"className\":\"p.R\","
                + "\"intentFilters\":[{\"actions\":[\"a\"],\"categories\":[]}]}]}").staticModel;
        assertNull(model.receivers().get(0).intentFilters.get(0).data);
    }

    /**
     * The real cryptoapp fixture: the components are served, none carries a filter, and the WTG
     * view and the widget metadata come from the same parse as the MOP data beside them.
     */
    @Test
    public void testCryptoappFixtureStaticSurface() throws Exception {
        java.net.URL url = StaticGuiModelTest.class.getResource("/cryptoapp.apk.mop.json");
        StaticArtifactLoader.Loaded loaded = StaticArtifactLoader.load(
                new File(url.toURI()).getAbsolutePath(), null, null, false, new NoopSink());
        StaticGuiModel model = loaded.staticModel;
        assertEquals("br.unb.cic.cryptoapp", model.packageName());
        assertEquals(MAIN, model.mainActivity());
        assertTrue(model.hasComponents());
        assertTrue(model.hasWtgData());
        assertFalse("MOP's gateway set is recomputed over this WTG view",
                model.wtgTransitions(MAIN).isEmpty());
        assertTrue(loaded.mopData.activityHasMopOptionsMenu(MAIN));
        assertEquals("textPersonName", model.widget(MDA, "editTextMessageDigest").inputType);
    }

    /** Nothing in the static model is a MOP fact: no flag, no distance, no reachesMop field. */
    @Test
    public void testTheStaticModelCarriesNoMopFact() {
        for (Class<?> type : new Class<?>[] {StaticGuiModel.class, StaticGuiModel.WidgetMeta.class,
                ComponentInfo.class}) {
            for (java.lang.reflect.Field f : type.getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                assertFalse(type.getSimpleName() + "." + f.getName(),
                        name.contains("mop") || name.contains("target") || name.contains("dist"));
            }
        }
    }
}
