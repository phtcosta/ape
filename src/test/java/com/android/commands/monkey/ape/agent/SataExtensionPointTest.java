package com.android.commands.monkey.ape.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.After;
import org.junit.Test;

import com.android.commands.monkey.ape.ActionFilter;
import com.android.commands.monkey.ape.Subsequence;
import com.android.commands.monkey.ape.agent.sata.ApeAbaTargetTie;
import com.android.commands.monkey.ape.agent.sata.ApeTrivialPathPick;
import com.android.commands.monkey.ape.agent.sata.MopAbaTargetTie;
import com.android.commands.monkey.ape.agent.sata.MopTrivialPathPick;
import com.android.commands.monkey.ape.agent.sata.ScorerSumLeastVisitedTie;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.GraphElement;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.utils.RandomHelper;

/**
 * The SATA extension points X1, X3 and X4 (action-selection "SATA Navigation Extension Points",
 * design D-B12) and the SATA telemetry they and the roulettes write (event-sink {@code dec.strat},
 * {@code draw}, {@code cfc}, {@code nav}, {@code bmc}).
 *
 * <p>The APE defaults must reproduce upstream APE including its RNG consumption (INV-SEL-11): X1
 * and X3 draw nothing, X4 one helper-stream draw when there is more than one path. The MOP
 * variants decide by density and record {@code alt} only when their own rule decided (A2). The
 * agent-level cases allocate a {@link SataAgent} without a constructor, as the parity harness
 * does, and drive the epsilon-greedy rung directly.
 */
public class SataExtensionPointTest {

    private static final String ACT = "com.x.MainActivity";

    @After
    public void tearDown() {
        RunContext.resetForTest();
    }

    // ---- fixtures ------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** A state of {@link #ACT} holding {@code actions}, with an invalid BACK and MENU. */
    private static State state(ModelAction... actions) throws Exception {
        StateKey key = allocate(StateKey.class);
        setField(key, "activity", ACT);
        setField(key, "widgets", new Name[0]);
        State state = allocate(State.class);
        setField(state, "stateKey", key);
        setField(state, "actions", actions);
        setField(state, "backAction", new ModelAction(state, ActionType.MODEL_BACK));
        setField(state, "menuAction", new ModelAction(state, ActionType.MODEL_MENU));
        return state;
    }

    /** A valid, enabled action with a visit count; visited unless {@code unvisited}. */
    private static ModelAction action(ActionType type, int priority, int visits, boolean unvisited)
            throws Exception {
        ModelAction a = new ModelAction(null, type);
        a.setValid(true);
        a.setPriority(priority);
        Field vc = GraphElement.class.getDeclaredField("visitedCount");
        vc.setAccessible(true);
        vc.setInt(a, visits);
        if (!unvisited) {
            Field first = GraphElement.class.getDeclaredField("firstVisitTimestamp");
            first.setAccessible(true);
            first.setInt(a, 1);
        }
        return a;
    }

    private static ModelAction click(int priority, int visits) throws Exception {
        return action(ActionType.MODEL_CLICK, priority, visits, false);
    }

    /** One sink call, by method name and arguments. */
    private static final class Call {
        final String method;
        final List<Object> args;

        Call(String method, Object[] args) {
            this.method = method;
            this.args = args == null ? Collections.emptyList() : Arrays.asList(args);
        }

        @Override
        public String toString() {
            return method + args;
        }
    }

    /** Installs {@code spec} with a sink that records every call. */
    private static List<Call> installRecording(RunSpec spec) {
        List<Call> calls = new ArrayList<>();
        EventSink sink = (EventSink) Proxy.newProxyInstance(EventSink.class.getClassLoader(),
                new Class<?>[] {EventSink.class}, (proxy, method, args) -> {
                    calls.add(new Call(method.getName(), args));
                    return null;
                });
        RunContext.installForTest(spec, sink);
        return calls;
    }

    private static List<Call> named(List<Call> calls, String method) {
        List<Call> out = new ArrayList<>();
        for (Call call : calls) {
            if (call.method.equals(method)) {
                out.add(call);
            }
        }
        return out;
    }

    /** The agent the parity harness allocates: pinned agent stream, constructor never run. */
    public static class PinnedSataAgent extends SataAgent {
        Random pinned;

        public PinnedSataAgent() {
            super(null, null);
        }

        @Override
        protected Random getRandom() {
            return pinned;
        }
    }

    /**
     * An agent on {@code state} under the installed plan; {@code epsilon} 0 always takes the
     * least-visited pick, 1 always the roulette.
     */
    private static PinnedSataAgent agent(State state, double epsilon, Map<String, Integer> picks)
            throws Exception {
        PinnedSataAgent agent = allocate(PinnedSataAgent.class);
        agent.pinned = new Random(11);
        setField(agent, "newState", state);
        setField(agent, "exploration", RunContext.current().spec().exploration());
        setField(agent, "backMenuPicks", picks);
        setField(agent, "actionCounters", new int[SataAgent.SataEventType.values().length]);
        setField(agent, "epsilon", epsilon);
        setField(agent, "timestamp", 1);
        return agent;
    }

    /** The epsilon rung as the SATA chain runs it: the pick, then the chosen rung's log write. */
    private static ModelAction epsilonRung(SataAgent agent) {
        ModelAction picked = agent.selectNewActionEpsilonGreedyRandomly();
        if (picked != null) {
            agent.logActionSelected(picked, SataAgent.SataEventType.EPSILON_GREEDY);
        }
        return picked;
    }

    /** A path whose end state is {@code end}. */
    private static Subsequence pathTo(State end) throws Exception {
        State start = state();
        return new Subsequence(new StateTransition(start, new ModelAction(start, ActionType.MODEL_BACK),
                end));
    }

    // ---- 6.6.1 X1 with zero scorers equals upstream's first of the array -----------------------

    /** Upstream APE {@code 8f51b99c} {@code State.greedyPickLeastVisited}: strict {@code <}. */
    private static ModelAction upstreamLeastVisited(List<ModelAction> actions) {
        ModelAction min = null;
        int minValue = Integer.MAX_VALUE;
        for (ModelAction a : actions) {
            if (a.getVisitedCount() < minValue) {
                minValue = a.getVisitedCount();
                min = a;
            }
        }
        return min;
    }

    @Test
    public void x1WithZeroScorersEqualsUpstreamFirstOfTheArray() throws Exception {
        Random random = new Random(3);
        for (int round = 0; round < 200; round++) {
            int n = 1 + random.nextInt(8);
            ModelAction[] actions = new ModelAction[n];
            for (int i = 0; i < n; i++) {
                // Base priorities differ between actions; no scorer field is set.
                actions[i] = click(1 + random.nextInt(600), random.nextInt(3));
            }
            State state = state(actions);
            assertSame("round " + round, upstreamLeastVisited(Arrays.asList(actions)),
                    state.greedyPickLeastVisited(ActionFilter.ALL, SataAgent.LEAST_VISITED_TIE));
        }
    }

    @Test
    public void x1PrefersTheLargerScorerSumAndRemovesItInTheCounterfactual() throws Exception {
        ModelAction first = click(32, 2);
        ModelAction scored = click(132, 2);
        scored.setCoverageBoost(100);
        List<ModelAction> tied = Arrays.asList(first, scored);
        ScorerSumLeastVisitedTie x1 = new ScorerSumLeastVisitedTie();
        assertSame(scored, x1.pick(tied));
        assertSame(first, x1.pickWithout(tied,
                com.android.commands.monkey.ape.model.ScorerCounterfactual.Scorer.COVERAGE));
    }

    // ---- 6.6.2 X3 APE keeps the incumbent -------------------------------------------------------

    @Test
    public void x3ApeKeepsTheIncumbentAndDrawsNothing() throws Exception {
        RandomHelper.seed(5);
        ApeAbaTargetTie x3 = new ApeAbaTargetTie();
        assertFalse(x3.replaces(state(), state()));
        assertFalse(x3.decidedByOwnRule());
        assertEquals("ape", x3.variant());
        assertEquals("no helper draw", new Random(5).nextInt(1000), RandomHelper.nextInt(1000));
    }

    // ---- 6.6.3 X4 APE consumes exactly one helper draw with more than one path -----------------

    @Test
    public void x4ApeConsumesExactlyOneHelperDrawWithMoreThanOnePath() throws Exception {
        List<Subsequence> paths = Arrays.asList(pathTo(state()), pathTo(state()), pathTo(state()));
        Random reference = new Random(9);
        int expectedIndex = reference.nextInt(paths.size());
        int expectedNext = reference.nextInt(1000);

        RandomHelper.seed(9);
        ApeTrivialPathPick x4 = new ApeTrivialPathPick();
        assertSame(paths.get(expectedIndex), x4.pick(paths));
        assertEquals("exactly one draw consumed", expectedNext, RandomHelper.nextInt(1000));
        assertFalse(x4.decidedByOwnRule());
        assertEquals("ape", x4.variant());
    }

    @Test
    public void x4ApeWithASinglePathDrawsNothing() throws Exception {
        List<Subsequence> paths = Collections.singletonList(pathTo(state()));
        RandomHelper.seed(9);
        assertSame(paths.get(0), new ApeTrivialPathPick().pick(paths));
        assertEquals(new Random(9).nextInt(1000), RandomHelper.nextInt(1000));
    }

    // ---- 6.6.4 the MOP variants decide as specified --------------------------------------------

    @Test
    public void x3MopReplacesOnlyByAStrictlyLargerDensity() throws Exception {
        State incumbent = state();
        State denser = state();
        State equal = state();
        Map<State, Integer> density = new IdentityHashMap<>();
        density.put(incumbent, 1);
        density.put(denser, 3);
        density.put(equal, 1);
        MopAbaTargetTie x3 = new MopAbaTargetTie(density::get);
        assertEquals("mop", x3.variant());

        RandomHelper.seed(5);
        assertTrue(x3.replaces(incumbent, denser));
        assertTrue("alt: the MOP rule replaced the incumbent", x3.decidedByOwnRule());
        assertFalse(x3.replaces(incumbent, equal));
        assertFalse("alt 0: it fell back to keeping the incumbent", x3.decidedByOwnRule());
        assertEquals("no helper draw", new Random(5).nextInt(1000), RandomHelper.nextInt(1000));
    }

    @Test
    public void x4MopTakesTheFirstDensestPathWithoutDrawing() throws Exception {
        State s0 = state();
        State s1 = state();
        State s2 = state();
        Map<State, Integer> density = new IdentityHashMap<>();
        density.put(s0, 0);
        density.put(s1, 2);
        density.put(s2, 2);
        List<Subsequence> paths = Arrays.asList(pathTo(s0), pathTo(s1), pathTo(s2));
        MopTrivialPathPick x4 = new MopTrivialPathPick(density::get);

        RandomHelper.seed(9);
        assertSame(paths.get(1), x4.pick(paths));
        assertTrue(x4.decidedByOwnRule());
        assertEquals("no draw", new Random(9).nextInt(1000), RandomHelper.nextInt(1000));
    }

    @Test
    public void x4MopFallsBackToOneDrawOnEqualDensities() throws Exception {
        State s0 = state();
        State s1 = state();
        State s2 = state();
        Map<State, Integer> density = new IdentityHashMap<>();
        density.put(s0, 1);
        density.put(s1, 1);
        density.put(s2, 1);
        List<Subsequence> paths = Arrays.asList(pathTo(s0), pathTo(s1), pathTo(s2));
        Random reference = new Random(9);
        int expectedIndex = reference.nextInt(3);
        int expectedNext = reference.nextInt(1000);

        MopTrivialPathPick x4 = new MopTrivialPathPick(density::get);
        RandomHelper.seed(9);
        assertSame(paths.get(expectedIndex), x4.pick(paths));
        assertFalse(x4.decidedByOwnRule());
        assertEquals(expectedNext, RandomHelper.nextInt(1000));
    }

    @Test
    public void theCompositionRootMountsTheVariantThePlanNames() throws Exception {
        TestRunSpecs.install();
        PinnedSataAgent base = agent(state(), 0, new HashMap<>());
        assertTrue(base.abaTargetTie() instanceof ApeAbaTargetTie);
        assertTrue(base.trivialPathPick() instanceof ApeTrivialPathPick);

        TestRunSpecs.installMop();
        PinnedSataAgent mop = agent(state(), 0, new HashMap<>());
        assertTrue(mop.abaTargetTie() instanceof MopAbaTargetTie);
        assertTrue(mop.trivialPathPick() instanceof MopTrivialPathPick);
    }

    // ---- 6.6.5 the shortcut is gone ------------------------------------------------------------

    @Test
    public void theMopShortcutIsGoneFromTheEpsilonRung() throws Exception {
        List<Call> calls = installRecording(TestRunSpecs.mopSpec());
        ModelAction plain = click(32, 0);
        // An unvisited action with a MOP weight, but more visits: the retired shortcut took it.
        ModelAction target = action(ActionType.MODEL_CLICK, 532, 2, true);
        target.setMopBoost(500);
        target.setMopWeight(500);
        PinnedSataAgent agent = agent(state(plain, target), 0, new HashMap<>());

        assertSame("the least-visited pick, not the MOP target", plain, epsilonRung(agent));
        assertEquals(ModelAction.DecisionSource.SATA, plain.getDecisionSource());
        assertEquals(ModelAction.PickChannel.SATA_OTHER, plain.getPickChannel());
        assertEquals("least_visited", named(calls, "strategy").get(0).args.get(0));
    }

    // ---- telemetry at the SATA sites ------------------------------------------------------------

    @Test
    public void theLeastVisitedTieWritesX1NavAndTheScorerCounterfactual() throws Exception {
        List<Call> calls = installRecording(TestRunSpecs.spec());
        ModelAction first = click(32, 2);
        ModelAction scored = click(132, 2);
        scored.setCoverageBoost(100);
        ModelAction visited = click(52, 5);
        PinnedSataAgent agent = agent(state(first, scored, visited), 0, new HashMap<>());

        assertSame(scored, epsilonRung(agent));
        assertEquals("least_visited", named(calls, "strategy").get(0).args.get(0));
        assertEquals(Arrays.asList("X1", "ape", false, false), named(calls, "nav").get(0).args);
        assertEquals(Arrays.asList("cov", true), named(calls, "cfc").get(0).args);
        assertEquals("one cfc key: the only non-zero scorer", 1, named(calls, "cfc").size());
        assertTrue("no draw on the least-visited pick", named(calls, "draw").isEmpty());
    }

    @Test
    public void aSingleLeastVisitedActionWritesNoNavAndNoCounterfactual() throws Exception {
        List<Call> calls = installRecording(TestRunSpecs.spec());
        ModelAction least = click(32, 0);
        ModelAction scored = click(132, 2);
        scored.setCoverageBoost(100);
        PinnedSataAgent agent = agent(state(least, scored), 0, new HashMap<>());

        assertSame(least, epsilonRung(agent));
        assertTrue(named(calls, "nav").isEmpty());
        assertTrue(named(calls, "cfc").isEmpty());
    }

    @Test
    public void theEpsilonRouletteWritesItsDrawAndChannel() throws Exception {
        List<Call> calls = installRecording(TestRunSpecs.spec());
        ModelAction a = click(30, 1);
        ModelAction b = click(70, 1);
        PinnedSataAgent agent = agent(state(a, b), 1.0, new HashMap<>());

        Random reference = new Random(11);
        reference.nextDouble(); // the epsilon flip
        int index = reference.nextInt(100);

        ModelAction picked = epsilonRung(agent);
        assertSame(index < 30 ? a : b, picked);
        assertEquals(ModelAction.PickChannel.ROULETTE_GREEDY, picked.getPickChannel());
        assertEquals("eps_roulette", named(calls, "strategy").get(0).args.get(0));
        assertEquals(Arrays.asList(index, 100), named(calls, "draw").get(0).args);
        assertTrue("no scorer mounted: no cfc", named(calls, "cfc").isEmpty());
        assertTrue("no cap: no bmc", named(calls, "backMenuCapped").isEmpty());
    }

    @Test
    public void theBackMenuCapRemovalIsCountedOnThePickThatChose() throws Exception {
        List<Call> calls = installRecording(TestRunSpecs.spec("ape.addons", "true",
                "ape.backMenuPickCap", "3"));
        ModelAction click = click(30, 1);
        ModelAction back = action(ActionType.MODEL_BACK, 8, 0, false);
        Map<String, Integer> picks = new HashMap<>();
        picks.put(SataAgent.backMenuPickKey(ActionType.MODEL_BACK, ACT), 3);
        PinnedSataAgent agent = agent(state(click, back), 1.0, picks);

        assertSame("the capped BACK is out of the roulette", click, epsilonRung(agent));
        assertEquals(Collections.singletonList(1), named(calls, "backMenuCapped").get(0).args);
    }

    @Test
    public void withoutTheCapFeatureNoBmcIsWritten() throws Exception {
        List<Call> calls = installRecording(TestRunSpecs.spec());
        ModelAction click = click(30, 1);
        ModelAction back = action(ActionType.MODEL_BACK, 8, 1, false);
        Map<String, Integer> picks = new HashMap<>();
        picks.put(SataAgent.backMenuPickKey(ActionType.MODEL_BACK, ACT), 99);
        PinnedSataAgent agent = agent(state(click, back), 1.0, picks);

        assertTrue(epsilonRung(agent) != null);
        assertTrue(named(calls, "backMenuCapped").isEmpty());
    }

    @Test
    public void theCounterfactualDrawsNothing() throws Exception {
        installRecording(TestRunSpecs.spec());
        ModelAction a = click(30, 1);
        ModelAction b = click(170, 1);
        b.setCoverageBoost(100);
        PinnedSataAgent agent = agent(state(a, b), 1.0, new HashMap<>());
        RandomHelper.seed(4);

        epsilonRung(agent);
        Random reference = new Random(11);
        reference.nextDouble();
        reference.nextInt(200);
        assertEquals("the agent stream advanced by the flip and the roulette only",
                reference.nextInt(1000), agent.pinned.nextInt(1000));
        assertEquals("the helper stream untouched", new Random(4).nextInt(1000),
                RandomHelper.nextInt(1000));
    }

    @Test
    public void noRecordWithoutASelection() throws Exception {
        List<Call> calls = installRecording(TestRunSpecs.spec());
        PinnedSataAgent agent = agent(state(), 0, new HashMap<>());
        assertNull(epsilonRung(agent));
        assertTrue(calls.isEmpty());
    }
}
