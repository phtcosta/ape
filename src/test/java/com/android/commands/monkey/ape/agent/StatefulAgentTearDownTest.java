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

import static org.junit.Assert.assertTrue;

/**
 * INV-EXPL-29 (refinement-crash-recovery, task 1.1): {@link StatefulAgent#tearDown()} isolates its
 * steps — a step that throws costs only its own artifact, never the later steps.
 *
 * <p>The agent is built with the {@code sun.misc.Unsafe} idiom (cf. {@code
 * BasePriorityCharacterizationTest}): no constructor runs, so every collaborator field is null and
 * the steps after the injected failure fail on their own. That is the point — each attempt is
 * observable as its own {@code tearDown step failed: <label>} line, which only the step-isolated
 * teardown can emit.
 */
public class StatefulAgentTearDownTest {

    /** A StatefulAgent whose 4th teardown step (saveActionHistory) throws. */
    public static class ThrowingAgent extends StatefulAgent {
        /** Never invoked — the instance is Unsafe-allocated; declared only to satisfy javac. */
        public ThrowingAgent() {
            super(null, null);
        }

        @Override
        protected void saveActionHistory() {
            throw new IllegalStateException("Cannot find widget");
        }

        @Override
        protected void saveGraph() {
            // no-op: keeps the pre-failure steps quiet so the assertions read unambiguously
        }

        @Override
        public void onBadState(int lastBadStateCount, int badStateCounter) {
        }

        @Override
        public String getLoggerName() {
            return "throwing";
        }

        @Override
        public void onActivityBlocked(ComponentName blockedActivity) {
        }

        @Override
        public boolean onGraphStable(int counter) {
            return false;
        }

        @Override
        public boolean onStateStable(int counter) {
            return false;
        }

        @Override
        public void onBufferLoss(State actual, State expected) {
        }

        @Override
        public void onRefillBuffer(Subsequence path) {
        }

        @Override
        protected Action selectNewActionNonnull() {
            return null;
        }

        @Override
        public boolean onVoidGUITree(int counter) {
            return false;
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
    public void testFailingStepDoesNotSkipLaterSteps() throws Exception {
        ThrowingAgent agent = allocateInstance(ThrowingAgent.class);

        agent.tearDown(); // must not throw

        String log = captured.toString();
        assertTrue("the failing step must be reported by label: " + log,
                log.contains("tearDown step failed: saveActionHistory"));
        assertTrue("the failure must carry a stack trace: " + log,
                log.contains("IllegalStateException") && log.contains("Cannot find widget"));
        assertTrue("a step after the failing one must still be attempted: " + log,
                log.contains("tearDown step failed: modelCounters"));
    }
}
