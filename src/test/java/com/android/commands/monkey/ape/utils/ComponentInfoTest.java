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
 * <p>The loader splits each entry: the trigger surface goes to {@link StaticGuiModel} as a
 * {@link ComponentInfo}, and the MOP fact, {@code reachesMop}, goes to {@link MopData} by class
 * name. The manifest surface the artifact drops — {@code exported},
 * an activity's intent-filter {@code <data>} block, the providers' granular read/write permissions
 * — has no test here because it has no field. The reduced {@code data} of a receiver's or service's
 * filter is {@code StaticGuiModelTest}'s.
 */
public class ComponentInfoTest {

    private static String writeArtifact(String components) throws Exception {
        File f = File.createTempFile("compinfo", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write("{\"formatVersion\":2,\"targets\":0,\"handlers\":{},"
                    + "\"package\":\"p\",\"mainActivity\":\"p.M\","
                    + "\"components\":" + components + "}");
        }
        return f.getAbsolutePath();
    }

    private static StaticArtifactLoader.Loaded load(String components) throws Exception {
        StaticArtifactLoader.Loaded d = StaticArtifactLoader.load(writeArtifact(components),
                null, null, false, new NoopSink());
        assertNotNull("artifact must load", d);
        return d;
    }

    @Test
    public void testReceiverFieldsCaptured() throws Exception {
        StaticArtifactLoader.Loaded d = load("{\"receivers\":[{\"className\":\"p.R\","
                + "\"isMain\":false,\"reachesMop\":true,\"hasTargetMethods\":true,"
                + "\"intentFilters\":[{\"actions\":[\"a1\"],\"categories\":[]}]}]}");
        ComponentInfo.ReceiverInfo r = d.staticModel.receivers().get(0);
        assertEquals("p.R", r.className);
        assertEquals("receiver", r.componentType);
        assertFalse(r.isMain);
        assertTrue("the MOP fact is MopData's", d.mopData.reachesMop("p.R"));
        assertEquals(Arrays.asList("a1"), r.getActions());
    }

    @Test
    public void testIntentFilterPreservesCategoriesAndActions() throws Exception {
        StaticArtifactLoader.Loaded d = load("{\"receivers\":[{\"className\":\"p.R\","
                + "\"reachesMop\":true,"
                + "\"intentFilters\":[{\"actions\":[\"android.intent.action.MAIN\"],"
                + "\"categories\":[\"android.intent.category.LAUNCHER\"]}]}]}");
        ComponentInfo.ReceiverInfo r = d.staticModel.receivers().get(0);
        assertEquals(1, r.intentFilters.size());
        assertEquals("android.intent.action.MAIN", r.intentFilters.get(0).actions.get(0));
        assertEquals("android.intent.category.LAUNCHER", r.intentFilters.get(0).categories.get(0));
    }

    @Test
    public void testProviderAuthoritiesCaptured() throws Exception {
        StaticArtifactLoader.Loaded d = load("{\"providers\":[{\"className\":\"p.Prov\","
                + "\"authorities\":\"p.auth\",\"reachesMop\":true}]}");
        ComponentInfo.ProviderInfo p = d.staticModel.providers().get(0);
        assertEquals("p.Prov", p.className);
        assertEquals("p.auth", p.authorities);
        assertEquals("provider", p.componentType);
        assertTrue(d.mopData.reachesMop("p.Prov"));
    }

    @Test
    public void testReachesMopReadFromTheWireNotHardcoded() throws Exception {
        StaticArtifactLoader.Loaded d =
                load("{\"receivers\":[{\"className\":\"p.R\",\"reachesMop\":false}]}");
        assertFalse("reachesMop read from the artifact, not hardcoded",
                d.mopData.reachesMop("p.R"));
        assertEquals("the component itself is served whatever its reachesMop",
                1, d.staticModel.receivers().size());
    }

    @Test
    public void testComponentTypeDerivedFromTheWireDictKey() throws Exception {
        StaticArtifactLoader.Loaded d = load("{\"activities\":[{\"className\":\"p.A\","
                + "\"reachesMop\":true}],\"receivers\":[{\"className\":\"p.R\","
                + "\"reachesMop\":true}]}");
        assertEquals("activity", d.staticModel.activities().get(0).componentType);
        assertEquals("receiver", d.staticModel.receivers().get(0).componentType);
    }

    @Test
    public void testPermissionGateCapturedAndNullWhenAbsent() throws Exception {
        StaticArtifactLoader.Loaded d = load("{\"activities\":["
                + "{\"className\":\"p.Guarded\",\"reachesMop\":true,\"permission\":\"p.PERM_X\"},"
                + "{\"className\":\"p.Open\",\"reachesMop\":true}]}");
        ComponentInfo.ActivityInfo guarded = d.staticModel.activities().get(0);
        ComponentInfo.ActivityInfo open = d.staticModel.activities().get(1);
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
                "p.R", false, Arrays.asList(f1, f2));
        assertEquals(Arrays.asList("a1", "a2", "a3"), r.getActions());
        assertEquals(Arrays.asList("c1", "c2", "c3"), r.getCategories());
    }

    /** The real cryptoapp artifact: four activities and the androidx-startup provider. */
    @Test
    public void testRealArtifactComponentSurface() throws Exception {
        java.net.URL url = ComponentInfoTest.class.getResource("/cryptoapp.apk.mop.json");
        assertNotNull("compact fixture on classpath", url);
        StaticArtifactLoader.Loaded loaded = StaticArtifactLoader.load(
                new File(url.toURI()).getAbsolutePath(), null, null, false, new NoopSink());
        assertNotNull(loaded);
        StaticGuiModel d = loaded.staticModel;
        assertEquals(4, d.activities().size());
        assertTrue(d.receivers().isEmpty());
        assertTrue(d.services().isEmpty());

        ComponentInfo.ActivityInfo main = null;
        for (ComponentInfo.ActivityInfo a : d.activities()) {
            if (a.isMain) { main = a; break; }
        }
        assertNotNull("cryptoapp has a main activity", main);
        assertNull("cryptoapp declares no android:permission on its launcher", main.permission);

        ComponentInfo.ProviderInfo prov = d.providers().get(0);
        assertTrue(prov.authorities.contains("androidx-startup"));
        assertNull(prov.permission);
    }
}
