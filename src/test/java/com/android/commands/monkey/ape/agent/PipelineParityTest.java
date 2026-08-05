package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.agent.scoring.ScoringContext;
import com.android.commands.monkey.ape.agent.scoring.ScoringParams;
import com.android.commands.monkey.ape.agent.scoring.ScoringPipeline;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;

/**
 * rv-scoring-pipeline group 6 (tasks 6.1, 6.2, 6.3(i)) — parity of the refactored pipeline against the
 * characterization goldens, exercised end-to-end through {@link StatefulAgent#adjustActionsByGUITree()}
 * at the JVM-feasible granularity (the {@code sun.misc.Unsafe} reflection idiom, cf.
 * {@code BasePriorityCharacterizationTest}/{@code UICoverageTrackerTest}).
 *
 * <p><b>6.1 — base golden reproduced with the MOP arm assembled.</b> The base-priority golden
 * {@code [(1<<3)+5 unvisited, (1<<3) visited]} is pinned by {@code BasePriorityCharacterizationTest}
 * (task 1.1) for the sata arm (no MopData → pipeline {@code [CoveragePass, FormCompletionPass]}). This
 * test adds the composition guarantee: with MopData present the full MOP arm assembles
 * ({@code MopWidgetPass, MenuGatewayPass, CoveragePass, FormCompletionPass}) yet reproduces the same
 * base golden byte-identical on non-target actions, because every MOP pass is inert on an action whose
 * {@code requireTarget()} is false. Composing the extracted passes does not perturb base scoring.
 *
 * <p><b>6.2 — attribution reproduced end-to-end (MenuGatewayPass).</b> {@link MenuGatewayPass} is the
 * one MOP attribution path that runs off-device (it matches on {@code ActionType.MODEL_MENU} without a
 * live {@code GUITreeNode}): it delegates to {@code MopScorer.scoreOpenMenu} and writes the boost to
 * {@code menuBoost} and {@code priority}. The target-node passes ({@code MopWidget}/{@code Wtg}/
 * {@code Frontier}/{@code Coverage}) write their provenance on a resolved {@code GUITreeNode}, so their
 * end-to-end attribution is device-validated (task 7.6); their scoring math is already locked by the
 * seam suite ({@code MopScorerTest}, {@code ActivityFrontierTest}, {@code UICoverageTrackerTest}) and
 * their provenance fields by {@code ModelActionTest} (tasks 1.2/1.3).
 */
public class PipelineParityTest {

    // getActionBasePriority(non-target) == 1, so base priority == 1 << 3 == 8 (cf. the golden pinned
    // by BasePriorityCharacterizationTest for MODEL_BACK/MODEL_MENU).
    private static final int NON_TARGET_BASE = 8;
    private static final int UNVISITED_BONUS = 5;

    // ---- reflection scaffolding ---------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    /** Sets a field declared anywhere in {@code obj}'s class hierarchy. */
    private static void setField(Object obj, String name, Object value) throws Exception {
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

    /** A State whose activity/getStateKey resolve (needed by the MOP passes' log lines). */
    private static State stateWith(String activity, ModelAction... actions) throws Exception {
        StateKey sk = allocate(StateKey.class);
        setField(sk, "activity", activity);
        setField(sk, "widgets", new Name[0]); // toString reads widgets.length; naming stays null
        State state = allocate(State.class);
        setField(state, "stateKey", sk);
        setField(state, "actions", actions);
        return state;
    }

    /**
     * The jar-default MOP arm, stated as values — the literals {@code ScoringParamsDefaultsTest}
     * pins against a default plan.
     *
     * <p>Spelling them out is what makes the boost assertions below mean anything. An expectation
     * fetched from the same source the production code reads at runtime compares a value to itself
     * and holds under any value, so the number has to enter this file from somewhere the pipeline
     * cannot reach. Whether these literals are still the jar's defaults is a different question,
     * and it has its own test.
     */
    private static final int MENU_GATEWAY_BOOST = 250;

    private static ScoringParams defaultMopArm() {
        return new ScoringParams(500, 300, MENU_GATEWAY_BOOST, 200, 200, 0, 100, true);
    }

    /** A ScoringContext wired the way {@code StatefulAgent}'s constructor wires it. */
    private static ScoringContext ctxFor(final MopData mopData, final UICoverageTracker tracker) {
        return new ScoringContext() {
            @Override public MopData getMopData() { return mopData; }
            @Override public UICoverageTracker getCoverageTracker() { return tracker; }
            @Override public Graph getGraph() { return null; }
            @Override public int getTimestamp() { return 1; }
            @Override public boolean menuPickEligible(String activity) { return true; }
        };
    }

    /** A SataAgent wired with the given MopData, exactly as the constructor wires the pipeline. */
    private static SataAgent bareAgent(State newState, final MopData mopData) throws Exception {
        final SataAgent agent = allocate(SataAgent.class);
        setField(agent, "newState", newState);
        setField(agent, "timestamp", 1);
        final UICoverageTracker tracker = new UICoverageTracker();
        setField(agent, "_coverageTracker", tracker);
        ScoringContext ctx = ctxFor(mopData, tracker);
        setField(agent, "scoringContext", ctx);
        setField(agent, "scoringPipeline", ScoringPipeline.fromParams(defaultMopArm(), ctx, new NoopSink()));
        return agent;
    }

    private static ModelAction nonTargetAction(ActionType type, boolean visited) throws Exception {
        ModelAction a = new ModelAction(null, type);
        if (visited) {
            setField(a, "firstVisitTimestamp", 1); // isUnvisited() == false → no +5 bonus
        }
        return a;
    }

    /** A MopData with no widget/WTG data but a fixed OPTIONSMENU gateway set (private-final field). */
    private static MopData mopWithMenuGateway(String activity) throws Exception {
        MopData mop = MopData.forTest(null, null, null);
        setField(mop, "activitiesWithMopOptionsMenu", new HashSet<>(Collections.singletonList(activity)));
        return mop;
    }

    /** An empty pipeline via the package-private ctor: the roster an arm that scores nothing gets. */
    private static ScoringPipeline emptyPipeline() throws Exception {
        java.lang.reflect.Constructor<ScoringPipeline> ctor =
                ScoringPipeline.class.getDeclaredConstructor(java.util.List.class);
        ctor.setAccessible(true);
        return ctor.newInstance(Collections.emptyList());
    }

    // ---- 6.1: MOP arm assembles but reproduces the base golden on non-targets ----

    @Test
    public void mopArmAssemblesFourPassesWithoutWtgData() throws Exception {
        // MopData present but no widget/WTG data: WtgPass/FrontierPass gate off (hasWtgData()==false),
        // the always-on MOP-widget/menu passes plus coverage/form remain.
        ScoringContext ctx = ctxFor(MopData.forTest(null, null, null), new UICoverageTracker());
        assertEquals(Arrays.asList(
                        "MopWidgetPass", "MenuGatewayPass", "CoveragePass", "FormCompletionPass"),
                ScoringPipeline.fromParams(defaultMopArm(), ctx, new NoopSink()).passNames());
    }

    @Test
    public void mopArmReproducesBaseGoldenOnNonTargetActions() throws Exception {
        // Every MOP pass is inert on a non-target action (requireTarget()==false), and the non-gateway
        // MopData gives scoreOpenMenu==0, so the four assembled passes leave the base golden intact.
        ModelAction backUnvisited = nonTargetAction(ActionType.MODEL_BACK, false);
        ModelAction backVisited = nonTargetAction(ActionType.MODEL_BACK, true);
        SataAgent agent = bareAgent(stateWith("com.x.Main", backUnvisited, backVisited),
                MopData.forTest(null, null, null));

        agent.adjustActionsByGUITree();

        assertEquals("unvisited: (1<<3)+5, MOP passes inert",
                NON_TARGET_BASE + UNVISITED_BONUS, backUnvisited.getPriority());
        assertEquals("visited: (1<<3), MOP passes inert",
                NON_TARGET_BASE, backVisited.getPriority());
        assertEquals("no MOP-widget boost attributed to a non-target action", 0, backUnvisited.getMopBoost());
    }

    // ---- 6.2: MenuGatewayPass reproduces the inline block's attribution end-to-end ----

    @Test
    public void menuGatewayPassAttributesOpenMenuBoostToModelMenu() throws Exception {
        String activity = "com.x.Main";
        ModelAction menu = nonTargetAction(ActionType.MODEL_MENU, true); // visited → base only (8)
        SataAgent agent = bareAgent(stateWith(activity, menu), mopWithMenuGateway(activity));

        agent.adjustActionsByGUITree();

        assertEquals("the gateway boost is attributed to menuBoost",
                MENU_GATEWAY_BOOST, menu.getMenuBoost());
        assertEquals("priority == base(1<<3) + menu-gateway boost",
                NON_TARGET_BASE + MENU_GATEWAY_BOOST, menu.getPriority());
    }

    @Test
    public void menuGatewayPassIsInertWhenActivityIsNotAGateway() throws Exception {
        String activity = "com.x.Main";
        ModelAction menu = nonTargetAction(ActionType.MODEL_MENU, true);
        SataAgent agent = bareAgent(stateWith(activity, menu), MopData.forTest(null, null, null));

        agent.adjustActionsByGUITree();

        assertEquals("no gateway → no menu boost", 0, menu.getMenuBoost());
        assertEquals("priority stays at base", NON_TARGET_BASE, menu.getPriority());
    }

    // ---- 6.3(i): empty pipeline (pure-arm outcome) yields upstream base priorities ----

    @Test
    public void emptyPipelineYieldsUpstreamBasePriorities() throws Exception {
        // An arm that scores nothing states no MOP data path and no weights, and its pipeline is
        // empty: adjustActionsByGUITree == the base loop plus a no-op apply. That the assembly
        // reaches the empty roster from such params is ScoringPipelineTest's assertion; what this
        // one adds is that an empty pipeline leaves the upstream base priorities exactly as the
        // loop set them, which is the scoring-pipeline scenario "empty pipeline yields upstream
        // priorities". The pipeline is installed directly because this test is about what apply()
        // does with no passes, not about how the roster got empty.
        ModelAction backUnvisited = nonTargetAction(ActionType.MODEL_BACK, false);
        ModelAction backVisited = nonTargetAction(ActionType.MODEL_BACK, true);
        SataAgent agent = bareAgent(stateWith("com.x.Main", backUnvisited, backVisited),
                MopData.forTest(null, null, null));
        setField(agent, "scoringPipeline", emptyPipeline());

        agent.adjustActionsByGUITree();

        assertEquals("unvisited: (1<<3)+5, empty pipeline adds nothing",
                NON_TARGET_BASE + UNVISITED_BONUS, backUnvisited.getPriority());
        assertEquals("visited: (1<<3), empty pipeline adds nothing",
                NON_TARGET_BASE, backVisited.getPriority());
    }
}
