package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.telemetry.NdjsonSink;
import org.json.JSONObject;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * mop-activity-consumers 3.1: the {@code MOP_DATA} record reports the
 * activity-level MOP substrate — {@code mopActivities=<n>} (final set size) and
 * {@code mopActsAugmented=<m>} (entries added by {@code augmentActivitiesFromSources} beyond
 * the widget-derived set; 0 with the flag off). Pure counters (INV-MOP-32).
 *
 * <p>The A′ flag is {@code static final} (read at Config class-load), so the flag-on scenario
 * flips it via {@code sun.misc.Unsafe} for the duration of the test and restores it — the same
 * seam the scorer tests use to reach otherwise runtime-only state.
 */
public class MopDataActivityCountersTest {

    private static String writeTempJson(String json) throws Exception {
        File f = File.createTempFile("mopdata-actcount", ".json");
        f.deleteOnExit();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(json);
        }
        return f.getAbsolutePath();
    }
    /** Flip the {@code static final} A′ flag past the compile-time wall (not a constant — it is
     * initialised via a method call, so the read is a runtime field access). Forces Config's
     * static initializer to run first: getting a {@code Field} does not initialize the class, so
     * a write before init would be clobbered when {@code Config.getBoolean} later runs. */
    private static void setActivitySourceFlag(boolean value) throws Exception {
        Class.forName("com.android.commands.monkey.ape.utils.Config"); // ensure static init ran
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Field field = Config.class.getDeclaredField("mopActivitySourceComponents");
        Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, field);
        long offset = (Long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, field);
        unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class)
                .invoke(unsafe, base, offset, value);
    }

    private static String reachingActivity(String name) {
        return "{\"className\":\"" + name + "\",\"componentType\":\"activity\",\"methods\":["
                + "{\"name\":\"m\",\"signature\":\"<" + name + ": void m()>\",\"reachable\":true"
                + ",\"reachesTarget\":true,\"directlyReachesTarget\":false}]}";
    }

    /** Main activity with one MOP-flagged widget (exact-match reaching handler) → widget-derived
     * set = {main}; plus two extra reachability-only activities that source 3 adds when the flag
     * is on. */
    private static String fixture() {
        String main = "com.x.Crypto";
        String handler = "<" + main + ": void generateHash(android.view.View)>";
        String reachMethod = "{\"name\":\"generateHash\",\"signature\":\"" + handler
                + "\",\"reachable\":true,\"reachesTarget\":true,\"directlyReachesTarget\":true}";
        return "{\"package\":\"com.x\",\"mainActivity\":\"" + main + "\",\"complete\":true"
                + ",\"reachability\":["
                + "{\"className\":\"" + main + "\",\"componentType\":\"activity\",\"methods\":["
                + reachMethod + "]},"
                + reachingActivity("com.x.Extra1") + ","
                + reachingActivity("com.x.Extra2") + "]"
                + ",\"windows\":[{\"id\":1,\"type\":\"ACTIVITY\",\"name\":\"" + main + "\",\"widgets\":["
                + "{\"id\":100,\"idName\":\"btn\",\"type\":\"android.widget.Button\",\"listeners\":["
                + "{\"eventType\":\"click\",\"handler\":\"" + handler + "\"}]}]}]"
                + ",\"transitions\":[],\"components\":{}}";
    }

    /** 3.1: flag off → m==0, n == widget-derived set size (1). */
    @Test
    public void flagOff_reportsZeroAugmentation() throws Exception {
        JSONObject record = loadRecord(writeTempJson(fixture()), null, null);
        assertEquals(1, record.getInt("mopActivities"));
        assertEquals(0, record.getInt("mopActsAugmented"));
    }

    /** 3.1: flag on → the two reachability-only activities are counted (m==2, n==3). */
    @Test
    public void flagOn_reportsAprimeContribution() throws Exception {
        final String path = writeTempJson(fixture());
        setActivitySourceFlag(true);
        try {
            JSONObject record = loadRecord(path, null, null);
            assertEquals(3, record.getInt("mopActivities"));
            assertEquals(2, record.getInt("mopActsAugmented"));
            assertNotNull(MopData.load(path, null, null, new NoopSink()));
        } finally {
            setActivitySourceFlag(false);
        }
    }

    /** The {@code MOP_DATA} record a load writes — the whole product, since nothing reads it back. */
    private static JSONObject loadRecord(String path, String pkg, String main) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        MopData.load(path, pkg, main, new NdjsonSink(new PrintStream(buffer, true, "UTF-8")));
        return new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim());
    }
}
