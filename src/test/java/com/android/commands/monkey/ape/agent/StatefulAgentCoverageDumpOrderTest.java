package com.android.commands.monkey.ape.agent;

import android.content.ComponentName;

import com.android.commands.monkey.ape.Subsequence;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionCounters;
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
 * produces output — {@code actionCounters}, now that no teardown step writes a file at all.
 *
 * <p>That boundary is the mechanism, not a preference. Across 800 calibration runs the dump was the
 * last instruction of the whole teardown, behind the {@code /sdcard} writes, and 338 of them
 * (42.3%) lost it — 330 cut mid-write. Emitting ahead of the writes recovers 333. The assertion
 * below is exactly that ordering; it is deliberately not "the dump runs first", which the chain
 * does not do (it lands third, after two steps that write nothing).
 *
 * <p><b>The boundary has moved twice and the property has not.</b> It was the model serialization
 * (deleted by {@code rearch-02-runspec}), then the action-history save (deleted here), and is now
 * the first free-text dump. {@code flushPendingStep} precedes the coverage dump and is deliberately
 * not the boundary: it writes one already-serialized {@code StepRecord} and is loss-bounding by the
 * same logic that puts the dump early, so ordering the two against each other would protect nothing.
 *
 * <p>Built with the {@code sun.misc.Unsafe} idiom of {@link StatefulAgentTearDownTest}: no
 * constructor runs, the steps needing collaborators fail into their own isolated {@code safeStep},
 * and the two steps under test print markers of their own. The {@code actionCounters} marker is
 * injected as a counters object whose {@code print()} is the marker, because that step is a field
 * call rather than an overridable method — the alternative, keying on its {@code safeStep} failure
 * line, would assert the ordering of an error rather than of the output the requirement is about.
 */
public class StatefulAgentCoverageDumpOrderTest {

    private static final String DUMP_MARKER = "[TEST] coverage dump";
    private static final String COUNTERS_MARKER = "[TEST] action-counters dump";

    /** The first output-producing teardown step, made observable without changing the chain. */
    public static class MarkingCounters extends ActionCounters {
        private static final long serialVersionUID = 1L;

        @Override
        public void print() {
            System.out.println(COUNTERS_MARKER);
        }
    }

    /** A StatefulAgent with no dump of its own; its first output step is made observable below. */
    public static class NoDumpAgent extends StatefulAgent {
        /** Never invoked — the instance is Unsafe-allocated; declared only to satisfy javac. */
        public NoDumpAgent() {
            super(null, null);
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

    /** Unsafe-allocated instances skip field initializers, so the counters must be installed. */
    private static void installMarkingCounters(StatefulAgent agent) throws Exception {
        Field f = StatefulAgent.class.getDeclaredField("actionCounters");
        f.setAccessible(true);
        f.set(agent, new MarkingCounters());
    }

    @Test
    public void dumpPrecedesTheFirstTeardownStepThatProducesOutput() throws Exception {
        DumpingAgent agent = allocateInstance(DumpingAgent.class);
        installMarkingCounters(agent);

        agent.tearDown();

        String log = captured.toString();
        int dumpAt = log.indexOf(DUMP_MARKER);
        int countersAt = log.indexOf(COUNTERS_MARKER);
        assertTrue("the dump must be emitted: " + log, dumpAt >= 0);
        assertTrue("the first output-producing step must be reached: " + log, countersAt >= 0);
        assertTrue("the dump must precede the first step whose output a lossy run swallows: " + log,
                dumpAt < countersAt);
    }

    @Test
    public void aSubclassThatDoesNotOverrideEmitsNothingAndTheChainCompletes() throws Exception {
        NoDumpAgent agent = allocateInstance(NoDumpAgent.class);
        installMarkingCounters(agent);

        agent.tearDown();   // must not throw

        String log = captured.toString();
        assertFalse(log.contains(DUMP_MARKER));
        assertTrue("the default step is a no-op, not a failure: " + log,
                !log.contains("tearDown step failed: coverageDump"));
        assertTrue("and the chain still reaches its later steps: " + log,
                log.contains(COUNTERS_MARKER));
    }
}
