package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.agent.scoring.ScoringContext;
import com.android.commands.monkey.ape.agent.scoring.ScoringPipeline;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.Model;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateActionDiffer;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.RandomHelper;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * rearch-01 task group 1 — the investigation spike that establishes, empirically, whether
 * {@code SataAgent.selectNewActionNonnull()} can be driven on the plain JVM, and at what price.
 *
 * <p>This class is temporary by design (tasks.md 1.1: "refined into group 2"): its findings are
 * transferred to {@code OracleScaffold}'s javadoc as the harness's honesty ledger (design D7) and
 * this file is deleted. Nothing here is a golden; the spike answers four questions and no more.
 *
 * <h2>1.1 — the ladder runs on the JVM</h2>
 * Confirmed. An Unsafe-allocated agent with the ledger below wired reaches a returned action for
 * the {@code aperv} profile. Two constraints found on the way:
 * <ul>
 *   <li><b>1.1-a</b> — the ladder cannot be entered <i>reflectively</i>. {@code getDeclaredMethod}
 *       runs {@code getDeclaredMethods0}, which resolves every declared signature of the class, and
 *       {@code SataAgent.onActivityBlocked(ComponentName)} makes that fail with
 *       {@code NoClassDefFoundError: android/content/ComponentName}. A compiled call from a
 *       subclass resolves only the invoked signature, so {@code OracleSataAgent} is not merely the
 *       RNG seam — it is the only entry point.</li>
 *   <li><b>1.1-b</b> — {@code MonkeySourceApe} is unavailable, as
 *       {@code MonkeySourceApeForeignGuardTest:19-21} documents, and stubbing it back is not a way
 *       out: {@code android.app.IUiAutomationConnection} → {@code android.os.RemoteException} →
 *       {@code android.os.Build$VERSION} did not converge after three stubs, and the third would
 *       require inventing an SDK level the production code branches on. The field {@code ape}
 *       therefore stays null, which is what makes 1.4-a below a real boundary.</li>
 * </ul>
 *
 * <h2>1.2 — the injection ledger (design D7)</h2>
 * {@code Unsafe.allocateInstance} skips every field initializer, so an inline-initialized field is
 * as absent as a constructor-assigned one. What the ladder reads, and where it comes from:
 * <table border="1">
 *   <caption>fields the driver must supply</caption>
 *   <tr><th>Field</th><th>Owner</th><th>Why the ladder touches it</th></tr>
 *   <tr><td>{@code newState}</td><td>StatefulAgent</td><td>the state under selection; every rung</td></tr>
 *   <tr><td>{@code newGUITree}</td><td>StatefulAgent</td><td>passed to the LLM hooks; null for non-LLM presets</td></tr>
 *   <tr><td>{@code timestamp}</td><td>ApeAgent</td><td>{@code getTimestamp()}, form-completion probes</td></tr>
 *   <tr><td>{@code model}</td><td>StatefulAgent</td><td>{@code getGraph()} — the global-action scan, the launcher's visited set, path search</td></tr>
 *   <tr><td>{@code _mopData}</td><td>StatefulAgent</td><td>preset axis; launcher and component-trigger gates</td></tr>
 *   <tr><td>{@code _llmRouter}</td><td>StatefulAgent</td><td>preset axis; the three LLM hooks</td></tr>
 *   <tr><td>{@code _coverageTracker}</td><td>StatefulAgent</td><td>{@code computeDynamicEpsilon()} (Config.dynamicEpsilon defaults true)</td></tr>
 *   <tr><td>{@code _budgetTracker}</td><td>StatefulAgent</td><td>the first block dereferences it unconditionally — {@code Config.activityBudgetEnabled} defaults <b>true</b>, so null is not the "budget off" wiring, it is a crash</td></tr>
 *   <tr><td>{@code scoringContext}, {@code scoringPipeline}</td><td>StatefulAgent</td><td>not read by the ladder itself; wired because the profile mirrors the constructor</td></tr>
 *   <tr><td>{@code actionBuffer}</td><td>StatefulAgent</td><td>{@code actionBufferSize()} in all three LLM preconditions, {@code selectNewActionFromBuffer()}</td></tr>
 *   <tr><td>{@code _actionHistory}</td><td>StatefulAgent</td><td>argument of {@code selectAction}</td></tr>
 *   <tr><td>{@code actionDiffer}</td><td>SataAgent</td><td>{@code getGreedyActions} → the EARLY_STAGE rungs</td></tr>
 *   <tr><td>{@code backMenuPicks}</td><td>SataAgent</td><td>the back/menu pick cap in EPSILON_GREEDY and the capped filter</td></tr>
 *   <tr><td>{@code mopTargetPicks}</td><td>SataAgent</td><td>{@code pickCappedMopTarget}, reached from EARLY_STAGE <i>and</i> EPSILON_GREEDY</td></tr>
 *   <tr><td>{@code actionCounters} (int[])</td><td>SataAgent</td><td>{@code logEvent} on every selected rung; {@code printCounters()} in the logging preamble</td></tr>
 *   <tr><td>{@code epsilon}</td><td>SataAgent</td><td>{@code computeDynamicEpsilon()} floor</td></tr>
 *   <tr><td>{@code backToActivity}</td><td>SataAgent</td><td>null keeps {@code selectNewActionBackToActivity()} out of {@code AndroidDevice} (see 1.3)</td></tr>
 *   <tr><td>{@code _isNewState}, {@code graphStableCounter}, {@code stagnationHookFired}</td><td>StatefulAgent</td><td>the LLM hooks' agent-side arguments and episode state</td></tr>
 * </table>
 *
 * <p><b>1.2-b</b> — two properties of the <i>actions</i> are preconditions the driver must also
 * supply, because the oracle enters below {@code adjustActionsByGUITree()}: {@code valid} (the
 * {@code Action} default is false, and {@code ENABLED_VALID} excludes it) and a positive
 * {@code priority} ({@code State.countActionPriority} throws {@code IllegalStateException} on any
 * included action with priority &le; 0). Declaring both in the script is also what keeps the
 * goldens independent of every scoring weight, as the spec requires.
 *
 * <h2>1.3 — which rungs execute (design D6)</h2>
 * <ul>
 *   <li>{@code selectNewActionFromBuffer} — safe (an empty buffer returns null;
 *       {@code enableXPathAction} defaults false).</li>
 *   <li>{@code selectNewActionBackToActivity} — safe <b>only</b> while {@code backToActivity} is
 *       null; it is the guard that returns before {@code AndroidDevice.getFocusedStack()}
 *       ({@code SataAgent.java:1267-1270}). A scenario that sets the field leaves the boundary.</li>
 *   <li>{@code selectNewActionEarlyStageForward} — safe, and it is what a fresh state actually
 *       selects: the spike's 3-widget state returns a {@code ROULETTE_EARLY} pick. Its draw comes
 *       from {@code RandomHelper}, not the agent stream ({@code getRandom()} calls = 0).</li>
 *   <li>{@code selectNewActionForTrivialActivity} — safe; an empty graph has fewer activity nodes
 *       than {@code trivialActivityRankThreshold}, so it returns an empty set.</li>
 *   <li>{@code selectNewActionEarlyStageBackwardGreedy} — safe on an empty graph.</li>
 *   <li>{@code selectNewActionEpsilonGreedyRandomly} — <b>blocked at its coin flip</b>, see 1.4-a.
 *       Everything below the flip is safe: forcing the roulette leg yields a
 *       {@code ROULETTE_GREEDY} pick that consumes exactly one draw from the overridden
 *       {@code getRandom()}.</li>
 *   <li>{@code handleNullAction} — reachable; draws from the overridable {@code getRandom()}.</li>
 * </ul>
 * <p>Reaching the chain below EARLY_STAGE requires <i>saturated</i>, not merely visited, actions:
 * {@code isSaturated()} is visit-based for targetless actions and {@code resolvedSaturation >= 1.0}
 * for targeted ones ({@code ModelAction.java:154-159}).
 *
 * <h2>1.4 — the second RNG stream</h2>
 * <b>Finding 1.4-a (contradicts design D5 and task 1.4).</b> The {@code getRandom()} override does
 * <i>not</i> reach the epsilon-greedy draw. {@code SataAgent.egreedy()} reads
 * {@code ape.getRandom()} <b>directly</b> ({@code SataAgent.java:1330}), bypassing the overridable
 * {@code ApeAgent.getRandom()} ({@code ApeAgent.java:322-323}) that every other draw in the ladder
 * goes through. With the prescribed wiring — null {@code ape}, per D5 — the ladder dies with
 * {@code NullPointerException: ... because "this.ape" is null} the first time the chain falls past
 * EARLY_STAGE. Pinned by {@link #spike14_egreedyBypassesTheGetRandomOverride()}.
 *
 * <p>What the override <i>does</i> pin: the epsilon-greedy roulette ({@code SataAgent.java:681}),
 * the component-trigger draw ({@code :548}) and {@code handleNullAction}
 * ({@code StatefulAgent.java:1695,1706,1711}). {@code RandomHelper.seed()} pins the EARLY_STAGE
 * roulette. So the seam is one line wide — and that one line gates both epsilon-greedy legs,
 * including {@code State.greedyPickLeastVisited}, which is the seam AC7 names as the highest-
 * leverage unguarded surface of stage 3.
 */
public class OracleSpikeTest {

    // ---- reflection scaffolding (generalized from PipelineParityTest:56-116) -------------

    @SuppressWarnings("unchecked")
    static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    /** Sets a field declared anywhere in {@code obj}'s class hierarchy. */
    static void setField(Object obj, String name, Object value) throws Exception {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    static Object getField(Object obj, String name) throws Exception {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // Finding 1.1-a: the ladder CANNOT be invoked reflectively. `Class.getDeclaredMethod` runs
    // `getDeclaredMethods0`, which resolves every declared signature of the class, and SataAgent
    // declares `onActivityBlocked(ComponentName)` — so any reflective lookup on the class dies with
    // NoClassDefFoundError: android/content/ComponentName before reaching the ladder. A compiled
    // call from a subclass has no such problem (only the invoked signature is resolved), which is
    // why the harness reaches `selectNewActionNonnull()` through OracleSataAgent and not reflection.

    // ---- synthetic fixtures --------------------------------------------------------------

    /** A local {@link Name} whose xpath is its identity — the StateTest/LlmRouterDeadPairTest idiom. */
    static Name testName(final String xpath) {
        return new Name() {
            @Override public Namer getNamer() { return null; }
            @Override public Name getLocalName() { return this; }
            @Override public boolean refinesTo(Name other) { return false; }
            @Override public String toXPath() { return xpath; }
            @Override public void appendXPathLocalProperties(StringBuilder sb) { sb.append(xpath); }
            @Override public void toXPath(StringBuilder sb) { sb.append(xpath); }
            @Override public int compareTo(Name o) { return xpath.compareTo(o.toXPath()); }
            @Override public String toString() { return xpath; }
        };
    }

    static State syntheticState(String activity, int widgetCount) throws Exception {
        return syntheticState(activity, widgetCount, false);
    }

    /** Marks an action visited the way the model does: a first-visit timestamp and a visit count. */
    static void markVisited(ModelAction action) throws Exception {
        setField(action, "firstVisitTimestamp", 1);
        Field vc = Class.forName("com.android.commands.monkey.ape.model.GraphElement")
                .getDeclaredField("visitedCount");
        vc.setAccessible(true);
        vc.setInt(action, 1);
    }

    /**
     * A State with {@code widgetCount} targeted MODEL_CLICK actions plus BACK and MENU. With
     * {@code allVisited}, every action carries a visit, which is what drives the ladder past the
     * EARLY_STAGE rungs (they select unvisited actions) down into EPSILON_GREEDY.
     */
    static State syntheticState(String activity, int widgetCount, boolean allVisited)
            throws Exception {
        StateKey sk = allocate(StateKey.class);
        setField(sk, "activity", activity);
        Name[] widgets = new Name[widgetCount];
        for (int i = 0; i < widgetCount; i++) {
            widgets[i] = testName("//*[@resource-id='w" + i + "']");
        }
        setField(sk, "widgets", widgets);

        State state = allocate(State.class);
        setField(state, "stateKey", sk);

        // Finding 1.2-b: the oracle enters BELOW adjustActionsByGUITree() (resolveNewAction,
        // StatefulAgent.java:1475-1478), so nothing has assigned priorities or validity. Both are
        // preconditions of the ladder, not incidental: ENABLED_VALID excludes valid=false (the
        // Action default), and State.countActionPriority throws IllegalStateException on any
        // included action whose priority is <= 0. The script therefore declares them, which is
        // also what keeps the goldens independent of every scoring weight.
        List<ModelAction> actions = new ArrayList<>();
        for (Name widget : widgets) {
            ModelAction click = new ModelAction(state, ActionType.MODEL_CLICK);
            setField(click, "target", widget);
            click.setValid(true);
            click.setPriority(8);
            actions.add(click);
        }
        ModelAction back = new ModelAction(state, ActionType.MODEL_BACK);
        ModelAction menu = new ModelAction(state, ActionType.MODEL_MENU);
        back.setValid(true);
        back.setPriority(8);
        menu.setValid(true);
        menu.setPriority(8);
        actions.add(back);
        if (Config.modelMenuEnabled) {
            actions.add(menu);
        }
        setField(state, "backAction", back);
        setField(state, "menuAction", menu);
        setField(state, "actions", actions.toArray(new ModelAction[0]));
        if (allVisited) {
            for (ModelAction a : actions) {
                markVisited(a);
                // isSaturated() is visit-based for targetless actions but resolution-based for
                // targeted ones (ModelAction.java:154-159), so EARLY_STAGE keeps offering a
                // visited MODEL_CLICK until its resolvedSaturation reaches 1.0.
                setField(a, "resolvedSaturation", 1.0F);
            }
            markVisited(menu);
            setField(menu, "resolvedSaturation", 1.0F);
        }
        return state;
    }

    static ScoringContext ctxFor(final MopData mopData, final UICoverageTracker tracker,
                                         final Graph graph) {
        return new ScoringContext() {
            @Override public MopData getMopData() { return mopData; }
            @Override public UICoverageTracker getCoverageTracker() { return tracker; }
            @Override public Graph getGraph() { return graph; }
            @Override public int getTimestamp() { return 1; }
            @Override public boolean menuPickEligible(String activity) { return true; }
        };
    }

    /**
     * The `aperv` preset profile: no MopData, no LlmRouter. Every field set here is one the
     * ladder reads and the Unsafe allocation left null/zero — this list IS the deliverable of
     * task 1.2.
     */
    /** Wires an already-allocated agent (or subclass) into the `aperv` preset profile. */
    static void wireAperv(SataAgent agent, State newState, long seed) throws Exception {
        Graph graph = new Graph();
        Model model = new Model(graph);
        UICoverageTracker tracker = new UICoverageTracker();

        // --- ApeAgent / StatefulAgent identity and clock
        setField(agent, "timestamp", 1);
        setField(agent, "model", model);
        setField(agent, "newState", newState);
        setField(agent, "newGUITree", null);

        // --- collaborators the constructor builds (final fields)
        setField(agent, "_mopData", null);
        setField(agent, "_coverageTracker", tracker);
        // Config.activityBudgetEnabled defaults true, so the constructor builds a real tracker and
        // the ladder's first block dereferences it unconditionally (SataAgent.java:468).
        setField(agent, "_budgetTracker", new ActivityBudgetTracker(
                Config.activityBaseBudget, Config.activityBudgetPerWidget));
        setField(agent, "_llmRouter", null);
        ScoringContext ctx = ctxFor(null, tracker, graph);
        setField(agent, "scoringContext", ctx);
        setField(agent, "scoringPipeline", ScoringPipeline.fromConfig(null, ctx));

        // --- fields with inline initializers that Unsafe.allocateInstance skipped
        setField(agent, "actionBuffer", new LinkedList<>());
        setField(agent, "_actionHistory", new ArrayList<>());
        setField(agent, "backMenuPicks", new HashMap<String, Integer>());
        setField(agent, "mopTargetPicks", new HashMap<String, Integer>());
        setField(agent, "actionCounters", new int[10]); // SataEventType.values().length
        setField(agent, "actionDiffer", new StateActionDiffer());

        // --- episode state the ladder reads
        setField(agent, "_isNewState", false);
        setField(agent, "graphStableCounter", 0);
        setField(agent, "stagnationHookFired", false);
        setField(agent, "backToActivity", null);
        setField(agent, "epsilon", 0.5d);

        RandomHelper.seed(seed);
    }

    /**
     * The harness subclass D5 prescribes: {@code getRandom()} overridden with a per-run seeded
     * stream, no other override. Allocated through Unsafe like its parent, so the constructor
     * (which needs a {@code MonkeySourceApe}) never runs. {@link #ladder()} is the compiled
     * entry point into the protected ladder — see finding 1.1-a on why reflection is not one.
     */
    public static class SpikeSataAgent extends SataAgent {
        Random pinned;
        int getRandomCalls;
        /**
         * SPIKE ONLY (task 1.3 mapping). Bypasses {@code egreedy()}, whose {@code ape.getRandom()}
         * read at {@code SataAgent.java:1330} NPEs with a null {@code ape}. Set to map what lies
         * BELOW that line; it shadows a production decision and must never exist in the harness.
         */
        Boolean egreedyOverride;
        int egreedyCalls;

        public SpikeSataAgent() { super(null, null); } // never invoked — Unsafe allocation

        @Override protected Random getRandom() {
            getRandomCalls++;
            return pinned;
        }

        @Override protected boolean egreedy() {
            egreedyCalls++;
            return egreedyOverride != null ? egreedyOverride : super.egreedy();
        }

        Action ladder() { return selectNewActionNonnull(); }
    }

    static SpikeSataAgent apervAgent(State newState, long seed) throws Exception {
        SpikeSataAgent agent = allocate(SpikeSataAgent.class);
        wireAperv(agent, newState, seed);
        agent.pinned = new Random(seed);
        return agent;
    }

    // ---- 1.1 — drive the ladder once ------------------------------------------------------

    @Test
    public void spike11_ladderReturnsAnActionOnASyntheticState() throws Exception {
        State state = syntheticState("com.example.MainActivity", 3);
        SpikeSataAgent agent = apervAgent(state, 42L);
        agent.egreedyOverride = Boolean.TRUE; // least-visited leg; see finding 1.4-a

        Action selected = agent.ladder();

        assertNotNull("the ladder returned an action on the JVM", selected);
        System.out.println("[SPIKE 1.1] selected = " + selected
                + " type=" + ((ModelAction) selected).getType()
                + " source=" + ((ModelAction) selected).getDecisionSource()
                + " channel=" + ((ModelAction) selected).getPickChannel());
        System.out.println("[SPIKE 1.4] getRandom() override calls = " + agent.getRandomCalls
                + ", egreedy() calls = " + agent.egreedyCalls);
    }

    /** The roulette leg (egreedy false) — the one that consumes the overridable getRandom(). */
    @Test
    public void spike13_rouletteLegIsReachable() throws Exception {
        State state = syntheticState("com.example.MainActivity", 3, true);
        SpikeSataAgent agent = apervAgent(state, 42L);
        agent.egreedyOverride = Boolean.FALSE;

        Action selected = agent.ladder();

        assertNotNull(selected);
        System.out.println("[SPIKE 1.3] roulette leg selected = " + selected
                + " channel=" + ((ModelAction) selected).getPickChannel()
                + " getRandom() calls = " + agent.getRandomCalls);
    }

    /**
     * Finding 1.4-a, after its resolution. As found, this scenario threw
     * {@code NullPointerException: ... because "this.ape" is null} inside {@code egreedy()}:
     * the override did not intercept the epsilon-greedy draw, contradicting design D5 and task
     * 1.4. Task 1.6 routed that draw through {@code getRandom()}, so the rung now runs with a
     * null {@code ape}. The permanent guard on the seam is {@link EgreedySeamTest}; this test
     * stays only until the spike is folded into {@code OracleScaffold}.
     */
    @Test
    public void spike14_egreedyReachesTheOverrideAfterTheSeamLanded() throws Exception {
        State state = syntheticState("com.example.MainActivity", 3, true);
        SpikeSataAgent agent = apervAgent(state, 42L); // egreedyOverride left null → real egreedy()

        Action selected = agent.ladder();

        assertNotNull(selected);
        assertEquals("the production coin flip ran", 1, agent.egreedyCalls);
        assertTrue("and drew from the overridden stream", agent.getRandomCalls >= 1);
    }
}
