package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.telemetry.RunCounter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * decision-pipeline "Buffer Abandonment by a Preempting Owner" (P-4, INV-DP-15, task 8.9.1): an
 * owner that takes the step from a non-empty SATA buffer abandons it without weakening the planned
 * edge and records {@code dec.buf} and {@code bufAbandoned}; a real path failure still weakens
 * through {@code clearBuffer}, as in APE.
 *
 * <p>The agent is {@code Unsafe}-allocated, so no constructor runs; its graph is a recording
 * {@link Graph} whose {@code weakenStateTransition} counts the calls, and the plan is installed
 * with a sink that records the calls this mechanism makes.
 */
public class BufferAbandonTest {

    /** The weakenStateTransition calls of the recording graph, static since no initializer runs. */
    private static final List<StateTransition> WEAKENED = new ArrayList<>();

    /** The path the trivial-activity search plans, or null when it finds none (static, as above). */
    private static List<StateTransition> PLANNED;

    private final List<String> sinkCalls = new ArrayList<>();
    private RecordingAgent agent;

    /** An agent whose graph records the edges it weakens instead of looking them up. */
    static class RecordingAgent extends SataAgent {
        RecordingAgent() {
            super(null, null);
        }

        @Override
        protected Action getStartAction(ActionType actionType) {
            return new Action(actionType);
        }

        /**
         * The search's effect on the buffer when it finds a path, as {@code refillBuffer} has it:
         * {@code clearBuffer} (which weakens a non-empty buffer's next edge), then the path's edges,
         * returning its first action. The graph walk itself is not under test.
         */
        @Override
        public ModelAction selectNewActionForTrivialActivity() {
            if (PLANNED == null) {
                return null;
            }
            clearBuffer();
            buffer(this).addAll(PLANNED);
            return PLANNED.get(0).getAction();
        }

        @Override
        public Graph getGraph() {
            return new Graph() {
                @Override
                public void weakenStateTransition(State source, ModelAction action, State target) {
                    WEAKENED.add(transition(source, action, target));
                }
            };
        }
    }

    @Before
    public void setUp() throws Exception {
        WEAKENED.clear();
        PLANNED = null;
        EventSink recording = (EventSink) Proxy.newProxyInstance(
                EventSink.class.getClassLoader(), new Class<?>[] {EventSink.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("bufferAbandoned")) {
                        sinkCalls.add("buf:" + args[0]);
                    } else if (method.getName().equals("count")) {
                        sinkCalls.add("count:" + ((RunCounter) args[0]).wire());
                    }
                    return null;
                });
        RunContext.installForTest(TestRunSpecs.spec(), recording);
        agent = allocate(RecordingAgent.class);
    }

    @After
    public void tearDown() {
        RunContext.resetForTest();
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    private static void setField(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** A transition built field by field: its constructor checks the source against a live state. */
    static StateTransition transition(State source, ModelAction action, State target) {
        try {
            StateTransition t = allocate(StateTransition.class);
            setField(t, StateTransition.class, "source", source);
            setField(t, StateTransition.class, "action", action);
            setField(t, StateTransition.class, "target", target);
            return t;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    static LinkedList<StateTransition> buffer(StatefulAgent agent) {
        try {
            Field f = StatefulAgent.class.getDeclaredField("actionBuffer");
            f.setAccessible(true);
            return (LinkedList<StateTransition>) f.get(agent);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** A three-edge path to a trivial activity. */
    private static List<StateTransition> threeStepPath() {
        List<StateTransition> path = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            path.add(transition(null, new ModelAction(null, ActionType.MODEL_CLICK), null));
        }
        return path;
    }

    /** Fills the agent's buffer with a two-step planned path. */
    private LinkedList<StateTransition> twoStepPath() throws Exception {
        LinkedList<StateTransition> buffer = new LinkedList<>();
        buffer.add(transition(null, new ModelAction(null, ActionType.MODEL_CLICK), null));
        buffer.add(transition(null, new ModelAction(null, ActionType.MODEL_BACK), null));
        setField(agent, StatefulAgent.class, "actionBuffer", buffer);
        return buffer;
    }

    /** Scenario "LLM coin preempts a two-step path". */
    @Test
    public void preemptionLeavesTheEdgeWeightsUnchanged() throws Exception {
        twoStepPath();
        assertEquals(2, agent.actionBufferSize());

        agent.abandonBuffer("llm");

        assertEquals(0, agent.actionBufferSize());
        assertTrue("no planned edge is weakened (INV-DP-15)", WEAKENED.isEmpty());
        assertEquals(java.util.Arrays.asList("buf:llm", "count:bufAbandoned"), sinkCalls);
    }

    /** An empty buffer is not abandoned: no dec.buf and no count. */
    @Test
    public void anEmptyBufferRecordsNothing() throws Exception {
        setField(agent, StatefulAgent.class, "actionBuffer", new LinkedList<StateTransition>());
        agent.abandonBuffer("budget");
        assertTrue(sinkCalls.isEmpty());
    }

    /** Scenario "a failed path still weakens": clearBuffer weakens the next planned edge. */
    @Test
    public void aPathFailureStillWeakens() throws Exception {
        StateTransition next = twoStepPath().getFirst();

        agent.clearBuffer();

        assertEquals(0, agent.actionBufferSize());
        assertEquals(1, WEAKENED.size());
        assertSame(next.getAction(), WEAKENED.get(0).getAction());
        assertTrue("a path failure writes no dec.buf", sinkCalls.isEmpty());
    }

    /** D-B14.13: the restart's new episode drops the buffer without weakening and without dec.buf. */
    @Test
    public void theRestartClearsTheBufferWithoutWeakening() throws Exception {
        twoStepPath();
        setField(agent, ApeAgent.class, "restart", true);
        setField(agent, ApeAgent.class, "restartKind", ApeAgent.RESTART_STATE);
        assertTrue(agent.restartDue());

        agent.restartAction();

        assertEquals(0, agent.actionBufferSize());
        assertTrue(WEAKENED.isEmpty());
        assertFalse(sinkCalls.contains("buf:restart"));
    }

    /**
     * Scenario "the budget keeps the path it planned" (D-B20, review 14.3 CR-01): the preempted
     * buffer is abandoned without weakening, and the planned path stays buffered.
     */
    @Test
    public void theBudgetKeepsItsPathAndDoesNotWeakenThePreemptedOne() throws Exception {
        twoStepPath();
        PLANNED = threeStepPath();

        ModelAction action = agent.budgetNavigation();

        assertSame(PLANNED.get(0).getAction(), action);
        assertEquals("the planned path is what the buffer holds", PLANNED, buffer(agent));
        assertTrue("the preempted edge is not weakened (INV-DP-15)", WEAKENED.isEmpty());
        assertEquals(java.util.Arrays.asList("buf:budget", "count:bufAbandoned"), sinkCalls);
    }

    /** An empty buffer before the step: the path is kept and nothing is recorded as abandoned. */
    @Test
    public void theBudgetOverAnEmptyBufferRecordsNoAbandonment() throws Exception {
        setField(agent, StatefulAgent.class, "actionBuffer", new LinkedList<StateTransition>());
        PLANNED = threeStepPath();

        agent.budgetNavigation();

        assertEquals(PLANNED, buffer(agent));
        assertTrue(WEAKENED.isEmpty());
        assertTrue("no dec.buf without a buffer to abandon", sinkCalls.isEmpty());
    }

    /** Scenario "Budget exhausted, no trivial activity": the buffer is as it was before the step. */
    @Test
    public void noNavigationRestoresTheBuffer() throws Exception {
        LinkedList<StateTransition> before = new LinkedList<>(twoStepPath());

        assertNull(agent.budgetNavigation());

        assertEquals(before, buffer(agent));
        assertTrue(WEAKENED.isEmpty());
        assertTrue(sinkCalls.isEmpty());
    }
}
