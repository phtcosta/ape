package android.os;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal JVM test stub for {@code android.os.Bundle}, following the {@code android.graphics.Rect}
 * and {@code android.util.Log} precedent: the surefire classpath excludes the system-scope Android
 * jars (see pom.xml {@code classpathDependencyExcludes}), so production code touching
 * {@code Bundle} cannot run on the JVM at all.
 *
 * <p>It reproduces the AOSP {@code BaseBundle} semantics for exactly the members the handler-stamp
 * capture uses ({@code GUITreeBuilder.copyHandlerStamps}, ui-tree INV-TREE-14):
 * {@code getString} returns the value under the key when it is a {@code String}, and null when the
 * key is absent or the value has another type (AOSP catches the {@code ClassCastException} and
 * logs a type warning, which this stub does not reproduce). {@link #failOnRead} stands in for a
 * read that throws on the device, such as an unparcel failure; it is a test hook, not AOSP.
 *
 * <p><b>It does not stand in for {@code AccessibilityNodeInfo.getExtras()}</b>: whether a stamped
 * app's extras reach the explorer is answered by the device smoke (tasks 17.2), not here.
 */
public final class Bundle {

    private final Map<String, Object> map = new HashMap<String, Object>();

    /** When set, every read throws it. */
    private RuntimeException failure;

    public Bundle() {
    }

    public void putString(String key, String value) {
        map.put(key, value);
    }

    public void putInt(String key, int value) {
        map.put(key, Integer.valueOf(value));
    }

    public String getString(String key) {
        if (failure != null) {
            throw failure;
        }
        Object o = map.get(key);
        try {
            return (String) o;
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** Test hook: every later read of this bundle throws {@code failure}. */
    public void failOnRead(RuntimeException failure) {
        this.failure = failure;
    }
}
