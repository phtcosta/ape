package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.telemetry.NoopSink;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Component decoding from the compact artifact's {@code components} block, and the convenience
 * helpers over it.
 *
 * <p>These tests used to assemble full static-analysis documents and drive the on-device parser.
 * The document they build now is the wire, which changes what they can and cannot ask. The manifest
 * surface the artifact drops — {@code exported}, the intent-filter {@code <data>} block, the
 * providers' granular read/write permissions — has no test here because it has no field: the
 * assertions that those omissions are deliberate live where the omission bites, on the launcher's
 * eligibility walk and in the generator's suite. What is left is exactly the trigger surface, which
 * is what this file is now about.
 */
public class ComponentInfoTest {

    private static String writeArtifact(String components) throws Exception {
        File f = File.createTempFile("compinfo", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write("{\"formatVersion\":1,\"package\":\"p\",\"mainActivity\":\"p.M\","
                    + "\"components\":" + components + "}");
        }
        return f.getAbsolutePath();
    }

    private static MopData load(String components) throws Exception {
        MopData d = MopData.load(writeArtifact(components), null, null, false, new NoopSink());
        assertNotNull("artifact must load", d);
        return d;
    }

    @Test
    public void testReceiverFieldsCaptured() throws Exception {
        MopData d = load("{\"receivers\":[{\"className\":\"p.R\",\"isMain\":false,"
                + "\"reachesMop\":true,\"hasTargetMethods\":true,"
                + "\"intentFilters\":[{\"actions\":[\"a1\"],\"categories\":[]}]}]}");
        ComponentInfo.ReceiverInfo r = d.getReceivers().get(0);
        assertEquals("p.R", r.className);
        assertEquals("receiver", r.componentType);
        assertFalse(r.isMain);
        assertTrue(r.reachesTarget);
        assertEquals(Arrays.asList("a1"), r.getActions());
    }

    /**
     * {@code hasTargetMethods} decodes to a list of the right emptiness, not to signatures.
     *
     * <p>The one surviving consumer is {@code buildTriggerTuples}' emptiness test, so the wire
     * carries a boolean and the list is rebuilt to match it. Asserting emptiness rather than
     * contents is the honest form: the signatures are host-side facts that no longer travel, and a
     * test that asserted a placeholder string would be pinning this decoder's private convention.
     */
    @Test
    public void testTargetMethodsDecodeToEmptinessOnly() throws Exception {
        MopData d = load("{\"receivers\":["
                + "{\"className\":\"p.Has\",\"reachesMop\":true,\"hasTargetMethods\":true},"
                + "{\"className\":\"p.None\",\"reachesMop\":true,\"hasTargetMethods\":false},"
                + "{\"className\":\"p.Absent\",\"reachesMop\":true}]}");
        assertFalse("hasTargetMethods true ⇒ non-empty",
                d.getReceivers().get(0).targetMethods.isEmpty());
        assertTrue("hasTargetMethods false ⇒ empty",
                d.getReceivers().get(1).targetMethods.isEmpty());
        assertTrue("absent key defaults to false ⇒ empty",
                d.getReceivers().get(2).targetMethods.isEmpty());
    }

    @Test
    public void testIntentFilterPreservesCategoriesAndActions() throws Exception {
        MopData d = load("{\"receivers\":[{\"className\":\"p.R\",\"reachesMop\":true,"
                + "\"intentFilters\":[{\"actions\":[\"android.intent.action.MAIN\"],"
                + "\"categories\":[\"android.intent.category.LAUNCHER\"]}]}]}");
        ComponentInfo.ReceiverInfo r = d.getReceivers().get(0);
        assertEquals(1, r.intentFilters.size());
        assertEquals("android.intent.action.MAIN", r.intentFilters.get(0).actions.get(0));
        assertEquals("android.intent.category.LAUNCHER", r.intentFilters.get(0).categories.get(0));
    }

    @Test
    public void testProviderAuthoritiesCaptured() throws Exception {
        MopData d = load("{\"providers\":[{\"className\":\"p.Prov\",\"authorities\":\"p.auth\","
                + "\"reachesMop\":true}]}");
        ComponentInfo.ProviderInfo p = d.getProviders().get(0);
        assertEquals("p.Prov", p.className);
        assertEquals("p.auth", p.authorities);
        assertEquals("provider", p.componentType);
    }

    @Test
    public void testReachesMopReadFromTheWireNotHardcoded() throws Exception {
        MopData d = load("{\"receivers\":[{\"className\":\"p.R\",\"reachesMop\":false}]}");
        assertFalse("reachesMop read from the artifact, not hardcoded",
                d.getReceivers().get(0).reachesTarget);
    }

    @Test
    public void testComponentTypeDerivedFromTheWireDictKey() throws Exception {
        MopData d = load("{\"activities\":[{\"className\":\"p.A\",\"reachesMop\":true}],"
                + "\"receivers\":[{\"className\":\"p.R\",\"reachesMop\":true}]}");
        assertEquals("activity", d.getActivities().get(0).componentType);
        assertEquals("receiver", d.getReceivers().get(0).componentType);
    }

    @Test
    public void testPermissionGateCapturedAndNullWhenAbsent() throws Exception {
        MopData d = load("{\"activities\":["
                + "{\"className\":\"p.Guarded\",\"reachesMop\":true,\"permission\":\"p.PERM_X\"},"
                + "{\"className\":\"p.Open\",\"reachesMop\":true}]}");
        ComponentInfo.ActivityInfo guarded = d.getActivities().get(0);
        ComponentInfo.ActivityInfo open = d.getActivities().get(1);
        assertEquals("p.PERM_X", guarded.permission);
        assertTrue(guarded.hasPermissionGate());
        assertNull("permission null when not declared", open.permission);
        assertFalse(open.hasPermissionGate());
    }

    @Test
    public void testGetActionsAndCategoriesFlattenAcrossFilters() {
        ComponentInfo.IntentFilter f1 = new ComponentInfo.IntentFilter(
                Arrays.asList("a1", "a2"), Arrays.asList("c1"));
        ComponentInfo.IntentFilter f2 = new ComponentInfo.IntentFilter(
                Arrays.asList("a3"), Arrays.asList("c2", "c3"));
        ComponentInfo.ReceiverInfo r = new ComponentInfo.ReceiverInfo(
                "p.R", false, Arrays.asList(f1, f2), true,
                java.util.Collections.<String>emptyList());
        assertEquals(Arrays.asList("a1", "a2", "a3"), r.getActions());
        assertEquals(Arrays.asList("c1", "c2", "c3"), r.getCategories());
    }

    /** The real cryptoapp artifact: four activities and the androidx-startup provider. */
    @Test
    public void testRealArtifactComponentSurface() throws Exception {
        java.net.URL url = ComponentInfoTest.class.getResource("/cryptoapp.apk.mop.json");
        assertNotNull("compact fixture on classpath", url);
        MopData d = MopData.load(new File(url.toURI()).getAbsolutePath(), null, null, false,
                new NoopSink());
        assertNotNull(d);
        assertEquals(4, d.getActivities().size());
        assertTrue(d.getReceivers().isEmpty());
        assertTrue(d.getServices().isEmpty());

        ComponentInfo.ActivityInfo main = null;
        for (ComponentInfo.ActivityInfo a : d.getActivities()) {
            if (a.isMain) { main = a; break; }
        }
        assertNotNull("cryptoapp has a main activity", main);
        assertNull("cryptoapp declares no android:permission on its launcher", main.permission);

        ComponentInfo.ProviderInfo prov = d.getProviders().get(0);
        assertTrue(prov.authorities.contains("androidx-startup"));
        assertNull(prov.permission);
    }
}
