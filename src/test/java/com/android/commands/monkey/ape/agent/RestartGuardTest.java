package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.agent.pipeline.StageCollaborators;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
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
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * decision-pipeline "Restart Guard Stage" (design D-B7, D-B14.12, D-B14.16, task 8.9.2), agent
 * half: when {@code restartDue()} holds and what {@code restartAction()} does. The stage that
 * calls them is the pipeline's; here the agent is {@code Unsafe}-allocated and driven directly.
 */
public class RestartGuardTest {

    private final List<String> sinkCalls = new ArrayList<>();
    private RestartingAgent agent;

    /** {@code restartAction} answers {@code getStartAction} without a device. */
    static class RestartingAgent extends SataAgent {
        RestartingAgent() {
            super(null, null);
        }

        @Override
        protected Action getStartAction(ActionType actionType) {
            return new Action(actionType);
        }
    }

    @Before
    public void setUp() throws Exception {
        EventSink recording = (EventSink) Proxy.newProxyInstance(
                EventSink.class.getClassLoader(), new Class<?>[] {EventSink.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("restart")) {
                        sinkCalls.add("restart:" + args[0]);
                    } else if (method.getName().equals("count")) {
                        sinkCalls.add("count:" + ((RunCounter) args[0]).wire());
                    }
                    return null;
                });
        RunContext.installForTest(TestRunSpecs.spec(), recording);
        agent = allocate(RestartingAgent.class);
        set(StatefulAgent.class, "actionBuffer", new LinkedList<StateTransition>());
        set(StatefulAgent.class, "exploration", RunContext.current().spec().exploration());
        set(ApeAgent.class, "nextRestartThreshold", 40);
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

    private void set(Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(agent, value);
    }

    private Object get(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(agent);
    }

    /** Scenario "graph-stability restart is decided before the LLM", agent half. */
    @Test
    public void aGraphStabilityFlagMakesTheRestartDueWithItsKind() throws Exception {
        int threshold = RunContext.current().spec().exploration().graphStableRestartThreshold();
        assertFalse("the threshold must be exceeded, not reached", agent.onGraphStable(threshold));
        assertTrue(agent.onGraphStable(threshold + 1));
        agent.timestamp = 7;

        assertTrue(agent.restartDue());
        Action start = agent.restartAction();

        assertTrue(start.getType() == ActionType.EVENT_RESTART
                || start.getType() == ActionType.EVENT_CLEAN_RESTART);
        assertEquals(Arrays.asList("restart:graph", "count:restarts"), sinkCalls);
        assertFalse("the flag is lowered", (Boolean) get(ApeAgent.class, "restart"));
        assertEquals(7, get(ApeAgent.class, "lastRestartStep"));
        assertEquals("a stability restart keeps the periodic threshold",
                40, get(ApeAgent.class, "nextRestartThreshold"));
        assertFalse(agent.restartDue());
    }

    /** The state and activity checks raise the flag with their own kinds. */
    @Test
    public void theStateAndActivityChecksNameTheirKinds() throws Exception {
        assertTrue(agent.onStateStable(
                RunContext.current().spec().exploration().stateStableRestartThreshold() + 1));
        assertTrue(agent.restartDue());
        agent.restartAction();
        assertEquals("restart:state", sinkCalls.get(0));
    }

    /** Scenario "periodic restart honours the previous step's latch". */
    @Test
    public void thePeriodicRestartHonoursThePreviousStepsLatch() throws Exception {
        agent.timestamp = 41;
        agent.disableRestart(); // the previous step's selection
        assertFalse(agent.restartDue());
        assertFalse("the guard clears the latch it read", (Boolean) get(ApeAgent.class, "disableRestart"));

        agent.timestamp = 42; // this step's selection did not disable the restart
        assertTrue(agent.restartDue());
        agent.restartAction();
        assertEquals(Arrays.asList("restart:periodic", "count:restarts"), sinkCalls);
        assertEquals(42, get(ApeAgent.class, "lastRestartStep"));
        int redrawn = (Integer) get(ApeAgent.class, "nextRestartThreshold");
        assertTrue("the periodic threshold is redrawn", redrawn >= 100 && redrawn <= 300);
    }

    /** No restart is due before the periodic threshold and without a flag. */
    @Test
    public void nothingIsDueBeforeTheThreshold() throws Exception {
        agent.timestamp = 40;
        assertFalse(agent.restartDue());
        assertTrue(sinkCalls.isEmpty());
    }

    /** {@code restartAction} without a due restart is a wiring error, never a silent restart. */
    @Test(expected = IllegalStateException.class)
    public void aRestartActionWithoutADueRestartFailsLoudly() {
        agent.restartAction();
    }

    /** Scenario "no restart replaces a selected action": only the pipeline agent has the guard. */
    @Test
    public void onlyThePipelineAgentRestartsThroughTheGuard() {
        assertTrue(StageCollaborators.class.isAssignableFrom(SataAgent.class));
        assertFalse(StageCollaborators.class.isAssignableFrom(RandomAgent.class));
        assertFalse(StageCollaborators.class.isAssignableFrom(ReplayAgent.class));
    }

    /** A restart step's record names the restart owner and channel (dec.src, dec.ch). */
    @Test
    public void aRestartStepIsOwnedByRestart() {
        for (ActionType type : new ActionType[] {ActionType.EVENT_RESTART,
                ActionType.EVENT_CLEAN_RESTART}) {
            assertEquals("restart", StatefulAgent.nonModelDecisionSource(type).owner());
            assertEquals(ModelAction.PickChannel.RESTART, StatefulAgent.nonModelPickChannel(type));
        }
        assertEquals("launcher",
                StatefulAgent.nonModelDecisionSource(ActionType.EVENT_TRIGGER_ACTIVITY).owner());
        assertEquals("sata", StatefulAgent.nonModelDecisionSource(ActionType.EVENT_NOP).owner());
        assertEquals("mop", ModelAction.DecisionSource.MOP.owner());
    }
}
