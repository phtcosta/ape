package com.android.commands.monkey.ape.agent;

import android.content.ComponentName;

import com.android.commands.monkey.ape.Subsequence;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.State;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * A10 (ui-coverage INV-COV-10): the coverage dump is emitted before the first teardown step that
 * writes output — {@code saveActionHistory}, the only remaining {@code /sdcard} writer.
 *
 * <p>That boundary is the mechanism, not a preference. Across 800 calibration runs the dump was the
 * last instruction of the whole teardown, behind the {@code /sdcard} writes, and 338 of them
 * (42.3%) lost it — 330 cut mid-write. Emitting ahead of the writes recovers 333. The assertion
 * below is exactly that ordering; it is deliberately not "the dump runs first", which the chain
 * does not do (it lands third, after two steps that write nothing).
 *
 * <p>Built with the {@code sun.misc.Unsafe} idiom of {@link StatefulAgentTearDownTest}: no
 * constructor runs, the steps needing collaborators fail into their own isolated {@code safeStep},
 * and the two steps under test print markers of their own.
 */
public class StatefulAgentCoverageDumpOrderTest {

    private static final String DUMP_MARKER = "[TEST] coverage dump";
    private static final String SAVE_MARKER = "[TEST] action-history write";

    /** A StatefulAgent whose first output write is observable, without a dump of its own. */
    public static class NoDumpAgent extends StatefulAgent {
        /** Never invoked — the instance is Unsafe-allocated; declared only to satisfy javac. */
        public NoDumpAgent() {
            super(null, null);
        }

        @Override
        protected void saveActionHistory() {
            System.out.println(SAVE_MARKER);
        }

        @Override
        public void onBadState(int lastBadStateCount, int badStateCounter) { }

        @Override
        public String getLoggerName() {
            return "no-dump";
        }

        @Override
        public void onActivityBlocked(ComponentName blockedActivity) { }

        @Override
        public boolean onGraphStable(int counter) {
            return false;
        }

        @Override
        public boolean onStateStable(int counter) {
            return false;
        }

        @Override
        public void onBufferLoss(State actual, State expected) { }

        @Override
        public void onRefillBuffer(Subsequence path) { }

        @Override
        protected Action selectNewActionNonnull() {
            return null;
        }

        @Override
        public boolean onVoidGUITree(int counter) {
            return false;
        }
    }

    /** The SataAgent case: the subclass supplies the dump, as it alone holds the MOP predicate. */
    public static class DumpingAgent extends NoDumpAgent {
        @Override
        protected void dumpCoverage() {
            System.out.println(DUMP_MARKER);
        }
    }

    private final PrintStream realOut = System.out;
    private ByteArrayOutputStream captured;

    @Before
    public void captureStdout() {
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
    }

    @After
    public void restoreStdout() {
        System.setOut(realOut);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocateInstance(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    @Test
    public void dumpPrecedesTheFirstTeardownWrite() throws Exception {
        DumpingAgent agent = allocateInstance(DumpingAgent.class);

        agent.tearDown();

        String log = captured.toString();
        int dumpAt = log.indexOf(DUMP_MARKER);
        int saveAt = log.indexOf(SAVE_MARKER);
        assertTrue("the dump must be emitted: " + log, dumpAt >= 0);
        assertTrue("the first teardown write must be reached: " + log, saveAt >= 0);
        assertTrue("the dump must precede the /sdcard write that swallows lossy runs: " + log,
                dumpAt < saveAt);
    }

    @Test
    public void aSubclassThatDoesNotOverrideEmitsNothingAndTheChainCompletes() throws Exception {
        NoDumpAgent agent = allocateInstance(NoDumpAgent.class);

        agent.tearDown();   // must not throw

        String log = captured.toString();
        assertFalse(log.contains(DUMP_MARKER));
        assertTrue("the default step is a no-op, not a failure: " + log,
                !log.contains("tearDown step failed: coverageDump"));
        assertTrue("and the chain still reaches its later steps: " + log,
                log.contains(SAVE_MARKER));
    }
}
