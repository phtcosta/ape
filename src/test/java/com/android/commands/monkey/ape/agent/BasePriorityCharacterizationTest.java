package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.agent.scoring.ScoringContext;
import com.android.commands.monkey.ape.agent.scoring.ScoringParams;
import com.android.commands.monkey.ape.agent.scoring.ScoringPipeline;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

/**
 * rv-scoring-pipeline task 1.1 — characterization of the upstream base-priority loop inside
 * {@link StatefulAgent#adjustActionsByGUITree()}, captured BEFORE the pipeline refactor so it is the
 * RED baseline the extracted base loop must reproduce byte-identical (INV-ARCH-05).
 *
 * <p><b>Granularity.</b> The method is deterministic (no {@code getRandom()}), so no seed is needed.
 * Only the <b>non-target branch</b> of the base loop is JVM-feasible: the surefire classpath excludes
 * the Android framework/dalvik stubs (pom.xml {@code classpathDependencyExcludes}), so any target
 * action's {@code getResolvedNode().getBoundsInScreen()} / graph-transition path links against
 * {@code android.graphics.Rect} and cannot run off-device. The target-branch arithmetic is guaranteed
 * preserved by task 3.7 (byte-identity to {@code ape @ 8f51b99}) and validated by the device smoke
 * (task 7.6). This test pins what runs on the JVM: {@code getActionBasePriority(type) << 3} plus the
 * {@code +5} unvisited bonus, with the MOP/WTG/coverage/form passes inert (no MopData, non-target
 * actions skip every pass body).
 *
 * <p>The agent and its state are built by reflection (the {@code sun.misc.Unsafe} idiom, cf.
 * {@code StateTest}/{@code FormCompletionTest}) because the normal constructors need Android APIs.
 */
public class BasePriorityCharacterizationTest {

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

    private static State stateWith(ModelAction... actions) throws Exception {
        State state = allocate(State.class);
        setField(state, "actions", actions);
        return state;
    }

    /**
     * A SataAgent with no MopData (all MOP passes off) and a real, empty coverage tracker. The Unsafe
     * allocation skips the constructor, so the scoring pipeline is wired here the same way the
     * constructor does: a MopData-less context yields a Coverage+FormCompletion pipeline that no-ops on
     * the non-target actions this test uses, leaving the base priorities intact.
     */
    private static SataAgent bareAgent(State newState) throws Exception {
        final SataAgent agent = allocate(SataAgent.class);
        setField(agent, "newState", newState);
        setField(agent, "timestamp", 1);
        final UICoverageTracker tracker = new UICoverageTracker();
        setField(agent, "_coverageTracker", tracker);
        ScoringContext ctx = new ScoringContext() {
            @Override public MopData getMopData() { return null; }
            @Override public UICoverageTracker getCoverageTracker() { return tracker; }
            @Override public Graph getGraph() { return null; }
            @Override public int getTimestamp() { return 1; }
            @Override public boolean menuPickEligible(String activity) { return true; }
        };
        setField(agent, "scoringContext", ctx);
        // A sata arm: no MOP feature, so every MOP weight is zero and the pipeline is
        // [CoveragePass, FormCompletionPass], both inert on the non-target actions below. The plan
        // is a value here and nothing is installed process-wide — before the weights were injected
        // this line needed a run context it never established, and passed only when another test
        // class happened to leave one behind.
        setField(agent, "scoringPipeline", ScoringPipeline.fromParams(
                ScoringParams.fromSpec(TestRunSpecs.spec()), ctx));
        return agent;
    }

    private static ModelAction nonTargetAction(ActionType type, boolean visited) throws Exception {
        ModelAction a = new ModelAction(null, type);
        // isUnvisited() reads firstVisitTimestamp == -1 (the fresh default); a non-negative
        // timestamp marks the action visited, so the base loop's +5 unvisited bonus is withheld.
        if (visited) {
            setField(a, "firstVisitTimestamp", 1);
        }
        return a;
    }

    // ---- goldens ------------------------------------------------------------

    // getActionBasePriority(non-target) == 1, so base priority == 1 << 3 == 8.
    private static final int NON_TARGET_BASE = 8;
    private static final int UNVISITED_BONUS = 5;

    @Test
    public void unvisitedNonTargetActionGetsBasePlusUnvisitedBonus() throws Exception {
        ModelAction back = nonTargetAction(ActionType.MODEL_BACK, false);
        SataAgent agent = bareAgent(stateWith(back));

        agent.adjustActionsByGUITree();

        assertEquals("MODEL_BACK, unvisited: (1<<3) + 5",
                NON_TARGET_BASE + UNVISITED_BONUS, back.getPriority());
    }

    @Test
    public void visitedNonTargetActionGetsBaseOnly() throws Exception {
        ModelAction back = nonTargetAction(ActionType.MODEL_BACK, true);
        SataAgent agent = bareAgent(stateWith(back));

        agent.adjustActionsByGUITree();

        assertEquals("MODEL_BACK, visited: (1<<3), no unvisited bonus",
                NON_TARGET_BASE, back.getPriority());
    }

    @Test
    public void multipleNonTargetActionsScoredIndependently() throws Exception {
        ModelAction backUnvisited = nonTargetAction(ActionType.MODEL_BACK, false);
        ModelAction menuVisited = nonTargetAction(ActionType.MODEL_MENU, true);
        SataAgent agent = bareAgent(stateWith(backUnvisited, menuVisited));

        agent.adjustActionsByGUITree();

        assertEquals(NON_TARGET_BASE + UNVISITED_BONUS, backUnvisited.getPriority());
        assertEquals(NON_TARGET_BASE, menuVisited.getPriority());
    }
}
