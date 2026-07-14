package com.android.commands.monkey.ape.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * mop-reach-strategies FIX 1/2/3: the MOP-inert bug root-caused during rv-scoring-pipeline 7.6.
 *
 * <ul>
 *   <li>A′ source-3 (INV-MOP-27): {@code mopActivities} widened from the activity's reachability
 *       method-level flags — the lambda-call-graph-gap-immune source — via the seam (static-final wall).
 *   <li>FIX 2 (INV-MOP-30): widget MOP-flag recovery for D8 {@code $$ExternalSyntheticLambda} handlers,
 *       always-on when MOP is loaded, exercised through {@link MopData#load}.
 *   <li>FIX 3 (INV-MOP-31): {@code handlersUnmatched/syntheticLambda/recovered} counters on the load line.
 * </ul>
 */
public class MopDataLambdaReachTest {

    private static String writeTempJson(String json) throws Exception {
        File f = File.createTempFile("mopdata-lambda", ".json");
        f.deleteOnExit();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(json);
        }
        return f.getAbsolutePath();
    }

    private static String capture(Runnable r) {
        PrintStream orig = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.out.flush();
            System.setOut(orig);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- FIX 1 (A′): 3-source union via the augmentActivitiesFromSources seam ----

    private static MopData.ReachabilityClass activityClass(String name, boolean hasReachingMethod) {
        MopData.ReachabilityClass rc = new MopData.ReachabilityClass();
        rc.className = name;
        rc.componentType = "activity";
        MopData.ReachabilityMethod m = new MopData.ReachabilityMethod();
        m.name = "executeOperation";
        m.signature = "<" + name + ": void executeOperation()>";
        m.reachesTarget = hasReachingMethod;
        rc.methods.add(m);
        return rc;
    }

    @Test
    public void source3_reachabilityMethodLevel_addsActivityOnlyWhenEnabled() {
        List<MopData.ReachabilityClass> reach = Arrays.asList(activityClass("com.x.Crypto", true));
        Set<String> s = new HashSet<>();
        MopData.augmentActivitiesFromSources(s, Collections.<ComponentInfo.ActivityInfo>emptyList(), reach, false);
        assertFalse("flag off = no-op", s.contains("com.x.Crypto"));
        MopData.augmentActivitiesFromSources(s, Collections.<ComponentInfo.ActivityInfo>emptyList(), reach, true);
        assertTrue("source 3 (reachability method-level) flags the activity", s.contains("com.x.Crypto"));
    }

    @Test
    public void source3_activityClassWithNoReachingMethod_notAdded() {
        List<MopData.ReachabilityClass> reach = Arrays.asList(activityClass("com.x.Plain", false));
        Set<String> s = new HashSet<>();
        MopData.augmentActivitiesFromSources(s, Collections.<ComponentInfo.ActivityInfo>emptyList(), reach, true);
        assertFalse(s.contains("com.x.Plain"));
    }

    @Test
    public void source2_componentsActivityReachesTarget_addedWhenEnabled() {
        List<ComponentInfo.ActivityInfo> acts = Arrays.asList(new ComponentInfo.ActivityInfo(
                "com.x.C2", false, true, Collections.<ComponentInfo.IntentFilter>emptyList(),
                true, Collections.<String>emptyList()));
        Set<String> s = new HashSet<>();
        MopData.augmentActivitiesFromSources(s, acts, Collections.<MopData.ReachabilityClass>emptyList(), true);
        assertTrue(s.contains("com.x.C2"));
    }

    @Test
    public void union_preservesWidgetDerivedEntry() {
        Set<String> s = new HashSet<>(Arrays.asList("com.x.WidgetOnly"));
        // A component/reachability disagreement (reachesTarget=false) must not remove a widget-derived entry.
        MopData.augmentActivitiesFromSources(s, Collections.<ComponentInfo.ActivityInfo>emptyList(),
                Arrays.asList(activityClass("com.x.WidgetOnly", false)), true);
        assertTrue(s.contains("com.x.WidgetOnly"));
    }

    // ---- FIX 2: desugared-lambda widget-flag recovery (always-on) via load() ----

    /** A window with one activity + one widget whose only listener is the given handler. */
    private static String fixture(String activity, String reachMethods, String handler) {
        return "{\"package\":\"com.x\",\"mainActivity\":\"" + activity + "\",\"complete\":true"
                + ",\"reachability\":[{\"className\":\"" + activity + "\",\"componentType\":\"activity\""
                + ",\"methods\":[" + reachMethods + "]}]"
                + ",\"windows\":[{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"" + activity + "\",\"widgets\":["
                + "{\"id\":100,\"idName\":\"btn\",\"type\":\"android.widget.Button\",\"listeners\":["
                + "{\"eventType\":\"click\",\"handler\":\"" + handler + "\"}]}]}]"
                + ",\"transitions\":[],\"components\":{}}";
    }

    private static String reachingLambda(String activity) {
        return "{\"name\":\"lambda$setup$0\",\"signature\":\"<" + activity
                + ": void lambda$setup$0(android.view.View)>\",\"reachable\":true"
                + ",\"reachesTarget\":true,\"directlyReachesTarget\":false}";
    }

    @Test
    public void fix2_desugaredLambdaHandlerRecovered() throws Exception {
        String act = "com.x.Crypto";
        String handler = "<" + act + "$$ExternalSyntheticLambda0: void onClick(android.view.View)>";
        MopData d = MopData.load(writeTempJson(fixture(act, reachingLambda(act), handler)), null, null);
        assertNotNull(d);
        assertTrue("Execute-button lambda handler recovered via enclosing-class lambda method",
                d.activityHasMop(act));
    }

    @Test
    public void fix2_noReachingLambda_notRecovered() throws Exception {
        String act = "com.x.Plain";
        // enclosing class has a lambda, but it does NOT reach a target
        String nonReaching = "{\"name\":\"lambda$setup$0\",\"signature\":\"<" + act
                + ": void lambda$setup$0(android.view.View)>\",\"reachable\":true,\"reachesTarget\":false}";
        String handler = "<" + act + "$$ExternalSyntheticLambda0: void onClick(android.view.View)>";
        MopData d = MopData.load(writeTempJson(fixture(act, nonReaching, handler)), null, null);
        assertNotNull(d);
        assertFalse(d.activityHasMop(act));
    }

    @Test
    public void fix2_exactMatchStillFlags() throws Exception {
        String act = "com.x.Direct";
        String handler = "<" + act + ": void generateHash(android.view.View)>";
        String reachMethod = "{\"name\":\"generateHash\",\"signature\":\"" + handler
                + "\",\"reachable\":true,\"reachesTarget\":true,\"directlyReachesTarget\":true}";
        MopData d = MopData.load(writeTempJson(fixture(act, reachMethod, handler)), null, null);
        assertNotNull(d);
        assertTrue("exact-match join path unchanged", d.activityHasMop(act));
    }

    // ---- FIX 3: join diagnostics on the load line ----

    @Test
    public void fix3_diagnosticsOnLoadLine() throws Exception {
        String act = "com.x.Crypto";
        String handler = "<" + act + "$$ExternalSyntheticLambda0: void onClick(android.view.View)>";
        final String path = writeTempJson(fixture(act, reachingLambda(act), handler));
        String out = capture(() -> MopData.load(path, null, null));
        assertTrue("load line carries join diagnostics: " + out,
                out.contains("handlersUnmatched=1 syntheticLambda=1 recovered=1"));
    }
}
