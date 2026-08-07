package android.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal JVM test stub for {@code android.util.Log}, following the {@code android.graphics.Rect}
 * precedent: the surefire classpath excludes the system-scope Android jars (see pom.xml
 * {@code classpathDependencyExcludes}), so production code touching {@code Log} cannot run on the
 * JVM at all — the reference fails to resolve rather than misbehaving.
 *
 * <p>It records what was written instead of discarding it, which is what lets the heartbeat's
 * <em>guard</em> be tested: that a step writes exactly one line, that the flag suppresses it, and
 * that its tag and payload are the ones the counterpart repository matches on. <b>It does not and
 * cannot stand in for logcat itself</b> — whether the line survives the device's tag allowlist,
 * the shared main buffer and chatty throttling is answered by a captured run (INV-SNK-14, task
 * 9.1b), not here.
 */
public final class Log {

    /** One recorded write, in the order it happened. */
    public static final class Entry {
        public final String tag;
        public final String message;

        Entry(String tag, String message) {
            this.tag = tag;
            this.message = message;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<Entry>();

    /** When set, every write throws it — the device-side failure the sink's guard swallows. */
    private static RuntimeException failure;

    private Log() {
    }

    public static int i(String tag, String message) {
        return record(tag, message);
    }

    public static int w(String tag, String message) {
        return record(tag, message);
    }

    public static int w(String tag, String message, Throwable cause) {
        return record(tag, message);
    }

    public static int e(String tag, String message) {
        return record(tag, message);
    }

    private static int record(String tag, String message) {
        if (failure != null) {
            throw failure;
        }
        ENTRIES.add(new Entry(tag, message));
        return message != null ? message.length() : 0;
    }

    /** Everything written since the last {@link #reset()}, in order. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** Makes every subsequent write throw, until {@link #reset()}. */
    public static void failWith(RuntimeException toThrow) {
        failure = toThrow;
    }

    /** Clears the recorded writes and any injected failure; a test that writes SHOULD call this. */
    public static void reset() {
        ENTRIES.clear();
        failure = null;
    }
}
