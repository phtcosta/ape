package com.android.commands.monkey.ape.naming;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.android.commands.monkey.ape.model.ActivityNode;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.Config;

/**
 * INV-NAME-14: the {@code ape.maxGUITreesPerState} guard shared by both {@link NamingFactory}
 * refinement sites, {@link NamingFactory#tooManyGUITrees(State, ActivityNode)}, under plans with
 * and without {@code APE_FIXES}.
 *
 * <p>With the member (an ADDONS plan) the guard compares the state's GUITree count; without it (the
 * jar defaults) it compares the activity's state count, as APE does, so a state over the cap in an
 * activity with few states is not capped.
 *
 * <p>Uses {@code sun.misc.Unsafe} to allocate {@link State}s and {@link GUITree}s without their
 * Android-dependent constructors (same technique as {@code StateTest}).
 */
public class NamingFactoryTreeCapTest {

    @After
    public void resetPlan() {
        RunContext.resetForTest();
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        java.lang.reflect.Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return (T) allocate.invoke(unsafe, clazz);
    }

    /** A state whose key carries the given activity string and whose history holds {@code trees} trees. */
    private static State state(String activity, int trees) throws Exception {
        StateKey sk = allocate(StateKey.class);
        Field af = StateKey.class.getDeclaredField("activity");
        af.setAccessible(true);
        af.set(sk, activity);
        State s = allocate(State.class);
        Field skf = State.class.getDeclaredField("stateKey");
        skf.setAccessible(true);
        skf.set(s, sk);
        List<GUITree> history = new ArrayList<>();
        for (int i = 0; i < trees; i++) {
            history.add(allocate(GUITree.class));
        }
        Field th = State.class.getDeclaredField("treeHistory");
        th.setAccessible(true);
        th.set(s, history);
        return s;
    }

    /** An activity node holding the given state and {@code others} further states. */
    private static ActivityNode activityWith(State state, int others) throws Exception {
        ActivityNode an = new ActivityNode("A");
        an.addState(state);
        for (int i = 0; i < others; i++) {
            // Distinct keys so the node's state set holds each one.
            an.addState(state("A#" + i, 1));
        }
        return an;
    }

    @Test
    public void overCapStateSuppressesRefinementWithMember() throws Exception {
        TestRunSpecs.install("ape.addons", "true");
        State s = state("A", Config.maxGUITreesPerState + 1);
        ActivityNode an = activityWith(s, 2);
        assertEquals(3, an.getStates().size());
        assertTrue(NamingFactory.tooManyGUITrees(s, an));
    }

    @Test
    public void overCapStateIsNotCappedWithoutMember() throws Exception {
        TestRunSpecs.install();
        State s = state("A", Config.maxGUITreesPerState + 1);
        ActivityNode an = activityWith(s, 2);
        assertFalse("APE's guard tests the activity's 3 states", NamingFactory.tooManyGUITrees(s, an));
    }

    @Test
    public void underCapStateRefinesInBothPlans() throws Exception {
        State s = state("A", 5);
        ActivityNode an = activityWith(s, 2);
        TestRunSpecs.install("ape.addons", "true");
        assertFalse(NamingFactory.tooManyGUITrees(s, an));
        TestRunSpecs.install();
        assertFalse(NamingFactory.tooManyGUITrees(s, an));
    }
}
