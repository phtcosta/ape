package com.android.commands.monkey.ape.agent.pipeline;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

import org.junit.Test;

import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.model.StateTransitionVisitType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * The one hop no golden can see: the agent handing a visited edge to its stages.
 *
 * <p><b>Why this test has to exist.</b> The parity oracle catches a wrong decision, not a hook that
 * stopped being called. {@code OracleScaffold} allocates its agent through {@code Unsafe}, so
 * {@code StatefulAgent}'s {@code graph.addListener(this)} never runs and the harness's agent is not a
 * {@code GraphListener} at all — nothing in the oracle ever calls
 * {@link SataAgent#onVisitStateTransition}. Episode-scoped state is also the kind a golden cannot
 * span: {@code LlmStagnation}'s single shot is re-armed only by an edge, several steps after the step
 * that burned it. So a regression that deleted the forwarding, or that forwarded before the agent's
 * own counter bookkeeping, would leave all four presets byte-identical and the gate at 14/14.
 *
 * <p><b>What "exactly once per visited edge" decomposes into</b>, since it is a claim about the
 * listener graph and not about one method body. {@code Graph} fans each edge to its listeners at one
 * call site ({@code fireStateTransitionEvents}); the agent registers itself as a listener at one call
 * site ({@code StatefulAgent}'s constructor) and is built once per run ({@code ApeAgent.createAgent},
 * reached once from {@code MonkeySourceApe}); the agent forwards to the pipeline once, which is the
 * hop asserted below; and the pipeline forwards to each stage once, which
 * {@code DecisionPipelineTest.aVisitedEdgeReachesEveryStageOnce} already pins. The first two are
 * single-call-site facts about code no stage owns; the last two are what this change moved, and they
 * are the two under test — here and there.
 *
 * <p><b>Why the order is asserted through a counter and not by inspection.</b> INV-DP-07 fixes the
 * order — the agent's stability bookkeeping first, then the stages — and the two are observably
 * distinguishable only through {@code graphStableCounter}, which the super call writes and which the
 * recording stage reads at hook time. On an {@code EXISTING} edge the counter is incremented, so a
 * stage running after the super sees the new value and one running before sees the old: the same three
 * edges record {@code [1, 2, 0]} in the correct order and {@code [0, 1, 2]} in the wrong one.
 */
public class TransitionForwardingTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /**
     * A stage that decides nothing and only records what the transition hook brought it.
     *
     * <p>It captures the agent's counter as it stood when the hook ran, which is the only way a stage
     * can observe its own position relative to the super call: the hook receives the edge and nothing
     * else.
     */
    private static class RecordingStage implements DecisionStage {

        private final IntSupplier graphStableCounter;

        final List<StateTransition> edges = new ArrayList<>();
        final List<Integer> countersSeen = new ArrayList<>();

        RecordingStage(IntSupplier graphStableCounter) {
            this.graphStableCounter = graphStableCounter;
        }

        @Override
        public String name() {
            return "Recording";
        }

        @Override
        public StageResult decide(StepContext ctx) {
            throw new UnsupportedOperationException("this stage exists for the transition hook only");
        }

        @Override
        public void onStateTransition(StateTransition edge) {
            edges.add(edge);
            countersSeen.add(graphStableCounter.getAsInt());
        }
    }

    /**
     * An agent whose only assembled state is a pipeline of one recording stage.
     *
     * <p>Allocated rather than constructed for the reason {@code OracleScaffold} states: the real
     * constructor wants a {@code MonkeySourceApe} and a device. {@code onVisitStateTransition} is safe
     * on an allocated agent because it writes four {@code int} fields and reads only the edge — which
     * is also why this ordering can be pinned with no model, no graph and no plan.
     */
    private static class Fixture {

        final SataAgent agent;
        final RecordingStage stage;

        Fixture() throws Exception {
            this.agent = (SataAgent) allocate(SataAgent.class);
            this.stage = new RecordingStage(agent::graphStableCounter);
            setField(agent, "decisionPipeline",
                    new DecisionPipeline(Collections.<DecisionStage>singletonList(stage)));
        }
    }

    /**
     * An edge of {@code type} whose source and target are the same state, so the two derived
     * predicates the super call evaluates ({@code isCircle}, {@code isSameActivity}) hold without a
     * second state having to be built. Neither is what this test asserts on.
     */
    private static StateTransition edgeOfType(StateTransitionVisitType type) throws Exception {
        State state = FakeStepContext.stateWith(ACTIVITY, 0);
        ModelAction action = new ModelAction(state, ActionType.MODEL_CLICK);
        StateTransition edge = new StateTransition(state, action, state);
        FakeStepContext.setField(edge, "type", type);
        return edge;
    }

    private static Object allocate(Class<?> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), clazz);
    }

    /** Sets a field declared anywhere in the object's hierarchy — {@code decisionPipeline} is final. */
    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    public void everyVisitedEdgeReachesTheStagesExactlyOnceAndUnaltered() throws Exception {
        Fixture f = new Fixture();
        StateTransition first = edgeOfType(StateTransitionVisitType.EXISTING);
        StateTransition second = edgeOfType(StateTransitionVisitType.NEW_ACTION);

        f.agent.onVisitStateTransition(first);
        f.agent.onVisitStateTransition(second);

        // Once per edge, in the order visited: an edge delivered twice would give the stagnation
        // episode a second shot it never had, and a dropped one would strand the flag burned.
        assertEquals(2, f.stage.edges.size());
        // Identity, not equality: StateTransition.equals compares source/action/target and ignores
        // the visit type, so an edge rebuilt on the way through could compare equal while re-arming
        // the stagnation flag on the wrong type.
        assertSame(first, f.stage.edges.get(0));
        assertSame(second, f.stage.edges.get(1));
    }

    @Test
    public void theAgentsCountersAreUpdatedBeforeTheStagesSeeTheEdge() throws Exception {
        Fixture f = new Fixture();

        f.agent.onVisitStateTransition(edgeOfType(StateTransitionVisitType.EXISTING));
        f.agent.onVisitStateTransition(edgeOfType(StateTransitionVisitType.EXISTING));
        f.agent.onVisitStateTransition(edgeOfType(StateTransitionVisitType.NEW_ACTION));

        // The counter a stage sees is the one the super call has already written: 0->1, 1->2, then
        // reset to 0 by the new edge. Forwarding first would record what it was, [0, 1, 2].
        assertEquals(Arrays.asList(1, 2, 0), f.stage.countersSeen);
        assertEquals(0, f.agent.graphStableCounter());
    }
}
