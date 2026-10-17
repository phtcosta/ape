package com.android.commands.monkey.ape.agent.scoring;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ActivityNode;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.tree.GUITreeNode;

/**
 * Screens for the pass tests that run {@code apply}: actions resolved at timestamp 1 on nodes with
 * a resource id, a state over them, and a graph whose visited activities a test names. Built by
 * allocation and field injection, as the other JVM scoring tests build theirs, because a real
 * {@code State} needs a GUI tree and a naming the JVM suite does not have.
 */
final class PassFixtures {

    private PassFixtures() {
    }

    static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return allocate.invoke(theUnsafe.get(null), type);
    }

    static void set(Object target, Class<?> owner, String field, Object value) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** A valid {@code type} action resolved at timestamp 1 on an enabled node of that class and id. */
    static ModelAction action(ActionType type, String className, String shortId)
            throws Exception {
        ModelAction a = new ModelAction(null, type);
        a.setValid(true);
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName(className);
        node.setEnabled(true);
        if (shortId != null) {
            node.setResourceID("com.x:id/" + shortId);
        }
        set(a, ModelAction.class, "resolvedNode", node);
        set(a, ModelAction.class, "resolvedNodes", new GUITreeNode[] {node}); // read by toString
        Field ts = ModelAction.class.getDeclaredField("resovledTimestamp");
        ts.setAccessible(true);
        ts.setInt(a, 1);
        return a;
    }

    static ModelAction button(String shortId) throws Exception {
        return action(ActionType.MODEL_CLICK, "android.widget.Button", shortId);
    }

    static ModelAction editText(String shortId) throws Exception {
        return action(ActionType.MODEL_CLICK, "android.widget.EditText", shortId);
    }

    /** A non-target action, such as the fork's {@code MODEL_MENU}. */
    static ModelAction nonTarget(ActionType type) {
        return new ModelAction(null, type);
    }

    static State state(String activity, ModelAction... actions) throws Exception {
        StateKey key = (StateKey) allocate(StateKey.class);
        set(key, StateKey.class, "activity", activity);
        set(key, StateKey.class, "widgets", new Name[0]); // the passes' log lines print the key
        State state = (State) allocate(State.class);
        set(state, State.class, "stateKey", key);
        set(state, State.class, "actions", actions);
        return state;
    }

    /** A graph in which exactly {@code visited} have an activity node. */
    static Graph graphVisiting(String... visited) {
        final Set<String> nodes = new HashSet<>(Arrays.asList(visited));
        return new Graph() {
            @Override
            public ActivityNode getActivityNode(String activity) {
                return nodes.contains(activity) ? new ActivityNode(activity) : null;
            }
        };
    }

    /** The seven scorer fields and the priority of an action, for whole-action comparison. */
    static int[] fieldsOf(ModelAction a) {
        return new int[] {a.getPriority(), a.getMopBoost(), a.getMopWeight(), a.getMenuBoost(),
                a.getWtgBoost(), a.getNavFrontierBoost(), a.getMopFrontierBoost(),
                a.getCoverageBoost(), a.getFormBoost()};
    }
}
