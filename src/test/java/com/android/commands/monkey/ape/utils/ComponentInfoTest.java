package com.android.commands.monkey.ape.utils;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * gh13 §16 — ComponentInfo parsing and convenience-helper tests.
 */
public class ComponentInfoTest {

    private static String writeTempJson(String components) throws Exception {
        File f = File.createTempFile("compinfo", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write("{\"package\":\"p\",\"mainActivity\":\"p.M\",\"complete\":true," +
                    "\"reachability\":[],\"windows\":[],\"transitions\":[],\"components\":"
                    + components + "}");
        }
        return f.getAbsolutePath();
    }

    // 16.1
    @Test
    public void testComponentFieldsCaptured() throws Exception {
        String comp = "{\"receivers\":[{\"className\":\"p.R\",\"isMain\":false,\"exported\":true," +
                "\"reachesTarget\":true,\"targetMethods\":[\"<p.R: void onReceive()>\"]," +
                "\"intentFilters\":[{\"actions\":[\"a1\"],\"categories\":[]}]}]}";
        MopData d = MopData.load(writeTempJson(comp));
        ComponentInfo.ReceiverInfo r = d.getReceivers().get(0);
        assertEquals("p.R", r.className);
        assertEquals("receiver", r.componentType);
        assertFalse(r.isMain);
        assertTrue(r.exported);
        assertTrue(r.reachesTarget);
        assertEquals(1, r.targetMethods.size());
        assertEquals("<p.R: void onReceive()>", r.targetMethods.get(0));
    }

    // 16.2
    @Test
    public void testIntentFilterPreservesCategoriesAndActions() throws Exception {
        String comp = "{\"activities\":[{\"className\":\"p.A\",\"exported\":true,\"reachesTarget\":true," +
                "\"intentFilters\":[{\"actions\":[\"android.intent.action.MAIN\"]," +
                "\"categories\":[\"android.intent.category.LAUNCHER\"]}]}]}";
        MopData d = MopData.load(writeTempJson(comp));
        ComponentInfo.ActivityInfo a = d.getActivities().get(0);
        assertEquals(1, a.intentFilters.size());
        assertEquals("android.intent.action.MAIN", a.intentFilters.get(0).actions.get(0));
        assertEquals("android.intent.category.LAUNCHER", a.intentFilters.get(0).categories.get(0));
    }

    // 16.3
    @Test
    public void testProviderAuthoritiesCaptured() throws Exception {
        String comp = "{\"providers\":[{\"className\":\"p.Prov\",\"authorities\":\"p.auth\"," +
                "\"reachesTarget\":true}]}";
        MopData d = MopData.load(writeTempJson(comp));
        ComponentInfo.ProviderInfo p = d.getProviders().get(0);
        assertEquals("p.Prov", p.className);
        assertEquals("p.auth", p.authorities);
        assertEquals("provider", p.componentType);
    }

    // 16.4
    @Test
    public void testComponentReachesTargetReadFromJson() throws Exception {
        String comp = "{\"receivers\":[{\"className\":\"p.R\",\"reachesTarget\":false}]}";
        MopData d = MopData.load(writeTempJson(comp));
        assertFalse("reachesTarget read from JSON, not hardcoded",
                d.getReceivers().get(0).reachesTarget);
    }

    // 16.5
    @Test
    public void testGetActionsFlattensAcrossMultipleFilters() {
        ComponentInfo.IntentFilter f1 = new ComponentInfo.IntentFilter(
                Arrays.asList("a1", "a2"), Arrays.asList("c1"));
        ComponentInfo.IntentFilter f2 = new ComponentInfo.IntentFilter(
                Arrays.asList("a3"), Arrays.asList("c2", "c3"));
        ComponentInfo.ReceiverInfo r = new ComponentInfo.ReceiverInfo(
                "p.R", false, true, Arrays.asList(f1, f2), true, java.util.Collections.<String>emptyList());
        assertEquals(Arrays.asList("a1", "a2", "a3"), r.getActions());
    }

    // 16.6
    @Test
    public void testGetCategoriesFlattensAcrossMultipleFilters() {
        ComponentInfo.IntentFilter f1 = new ComponentInfo.IntentFilter(
                Arrays.asList("a1"), Arrays.asList("c1"));
        ComponentInfo.IntentFilter f2 = new ComponentInfo.IntentFilter(
                Arrays.asList("a2"), Arrays.asList("c2", "c3"));
        ComponentInfo.ActivityInfo a = new ComponentInfo.ActivityInfo(
                "p.A", false, true, Arrays.asList(f1, f2), true, java.util.Collections.<String>emptyList());
        assertEquals(Arrays.asList("c1", "c2", "c3"), a.getCategories());
    }

    // 16.7
    @Test
    public void testComponentTypeDerivedFromJsonDictKey() throws Exception {
        String comp = "{\"activities\":[{\"className\":\"p.A\",\"reachesTarget\":true}]," +
                "\"receivers\":[{\"className\":\"p.R\",\"reachesTarget\":true}]}";
        MopData d = MopData.load(writeTempJson(comp));
        assertEquals("activity", d.getActivities().get(0).componentType);
        assertEquals("receiver", d.getReceivers().get(0).componentType);
    }

    // -------------------------------------------------------------------------
    // gh60 D15 reconciliation — components carry data needed to trigger them
    // (permission gate, intent-filter <data> block, provider read/write perms).
    // -------------------------------------------------------------------------

    // 16.8 — permission gate captured; null when absent
    @Test
    public void testActivityPermissionCaptured() throws Exception {
        String comp = "{\"activities\":[" +
                "{\"className\":\"p.Guarded\",\"exported\":true,\"reachesTarget\":true," +
                "\"permission\":\"p.PERM_X\"}," +
                "{\"className\":\"p.Open\",\"exported\":true,\"reachesTarget\":true}]}";
        MopData d = MopData.load(writeTempJson(comp));
        ComponentInfo.ActivityInfo guarded = d.getActivities().get(0);
        ComponentInfo.ActivityInfo open = d.getActivities().get(1);
        assertEquals("p.PERM_X", guarded.permission);
        assertTrue(guarded.hasPermissionGate());
        assertNull("permission null when not declared", open.permission);
        assertFalse(open.hasPermissionGate());
    }

    // 16.9 — intent-filter <data> block parsed (deep-link / MIME)
    @Test
    public void testIntentFilterDataBlockParsed() throws Exception {
        String comp = "{\"activities\":[{\"className\":\"p.Deep\",\"exported\":true,\"reachesTarget\":true," +
                "\"intentFilters\":[{\"actions\":[\"android.intent.action.VIEW\"]," +
                "\"categories\":[\"android.intent.category.BROWSABLE\"]," +
                "\"data\":{\"schemes\":[\"https\",\"myapp\"],\"hosts\":[\"example.com\"]," +
                "\"ports\":[\"443\"],\"paths\":[\"/exact\"],\"pathPrefixes\":[\"/items\"]," +
                "\"pathPatterns\":[\"/x/.*\"],\"mimeTypes\":[\"image/*\"]}}]}]}";
        MopData d = MopData.load(writeTempJson(comp));
        ComponentInfo.IntentFilter f = d.getActivities().get(0).intentFilters.get(0);
        assertEquals(Arrays.asList("https", "myapp"), f.data.schemes);
        assertEquals(Arrays.asList("example.com"), f.data.hosts);
        assertEquals(Arrays.asList("443"), f.data.ports);
        assertEquals(Arrays.asList("/exact"), f.data.paths);
        assertEquals(Arrays.asList("/items"), f.data.pathPrefixes);
        assertEquals(Arrays.asList("/x/.*"), f.data.pathPatterns);
        assertEquals(Arrays.asList("image/*"), f.data.mimeTypes);
        assertTrue("filter declaring schemes+mime has data", f.hasData());
    }

    // 16.10 — missing data block defaults to empty (back-compat with pre-D15 JSON)
    @Test
    public void testIntentFilterWithoutDataBlockDefaultsEmpty() throws Exception {
        String comp = "{\"activities\":[{\"className\":\"p.A\",\"exported\":true,\"reachesTarget\":true," +
                "\"intentFilters\":[{\"actions\":[\"android.intent.action.MAIN\"],\"categories\":[]}]}]}";
        MopData d = MopData.load(writeTempJson(comp));
        ComponentInfo.IntentFilter f = d.getActivities().get(0).intentFilters.get(0);
        assertNotNull("data never null", f.data);
        assertTrue(f.data.schemes.isEmpty());
        assertTrue(f.data.mimeTypes.isEmpty());
        assertFalse("no scheme/mime ⇒ no data", f.hasData());
    }

    // 16.11 — provider granular read/write permissions captured
    @Test
    public void testProviderReadWritePermissionsCaptured() throws Exception {
        String comp = "{\"providers\":[{\"className\":\"p.Prov\",\"authorities\":\"p.auth\"," +
                "\"reachesTarget\":true,\"permission\":\"p.P\"," +
                "\"readPermission\":\"p.R\",\"writePermission\":\"p.W\"}]}";
        MopData d = MopData.load(writeTempJson(comp));
        ComponentInfo.ProviderInfo p = d.getProviders().get(0);
        assertEquals("p.P", p.permission);
        assertEquals("p.R", p.readPermission);
        assertEquals("p.W", p.writePermission);
    }

    // 16.12 — real gh60 fixture (post-D15): MainActivity has data block + null permission
    @Test
    public void testRealFixtureD15FieldsPresent() throws Exception {
        java.net.URL url = ComponentInfoTest.class.getResource("/cryptoapp.apk.gh60-fresh.json");
        assertNotNull("fixture on classpath", url);
        MopData d = MopData.load(new File(url.toURI()).getAbsolutePath());
        assertNotNull(d);
        ComponentInfo.ActivityInfo main = null;
        for (ComponentInfo.ActivityInfo a : d.getActivities()) {
            if (a.isMain) { main = a; break; }
        }
        assertNotNull("cryptoapp has a main activity", main);
        // cryptoapp declares no android:permission and no <data> on its launcher filters
        assertNull("cryptoapp main activity has no permission gate", main.permission);
        assertFalse(main.intentFilters.isEmpty());
        assertNotNull("data block present (empty) on real filter", main.intentFilters.get(0).data);
        // provider exists with the androidx-startup authority and no permission gates
        ComponentInfo.ProviderInfo prov = d.getProviders().get(0);
        assertTrue(prov.authorities.contains("androidx-startup"));
        assertNull(prov.readPermission);
        assertNull(prov.writePermission);
    }
}
