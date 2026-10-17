package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import org.junit.Before;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.TestMopContexts;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * {@link MopWidgetPass} over the lookup (design D17, task 12.2): the pass reads the mode and the
 * weights from the {@code MopContext} of its {@link ScoringContext}, and a boost won through a
 * stamped relative reaches the action as it did through a flagged id.
 */
public class MopWidgetPassTest {

    /**
     * {@code MopData.load} reads the MOP activity source from the plan (guidance-groups
     * INV-GRP-06), so every case runs under one: the jar defaults, whose source is the widgets.
     */
    @Before
    public void installThePlan() {
        TestRunSpecs.install();
    }

    private static final String ACT = "com.x.MainActivity";

    private static MopData artifact(String widgetsOfAct, String handlers) throws Exception {
        String json = "{\"formatVersion\":2,\"targets\":30,"
                + "\"package\":\"com.x\",\"mainActivity\":\"" + ACT + "\","
                + "\"widgets\":{\"" + ACT + "\":{" + widgetsOfAct + "}},"
                + "\"mopActivities\":[\"" + ACT + "\"],"
                + "\"handlers\":{" + handlers + "}}";
        File f = File.createTempFile("mopwidgetpass", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(json);
        }
        MopData data = MopData.load(f.getAbsolutePath(), null, null, new NoopSink());
        assertNotNull(data);
        return data;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, type);
    }

    private static void set(Object target, Class<?> owner, String field, Object value)
            throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static ModelAction resolvedClick(GUITreeNode node) throws Exception {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setValid(true);
        set(a, ModelAction.class, "resolvedNode", node);
        Field ts = ModelAction.class.getDeclaredField("resovledTimestamp");
        ts.setAccessible(true);
        ts.setInt(a, 1);
        return a;
    }

    private static State state(ModelAction... actions) throws Exception {
        StateKey key = (StateKey) allocate(StateKey.class);
        set(key, StateKey.class, "activity", ACT);
        set(key, StateKey.class, "widgets", new Name[0]); // the log line prints the key
        State state = (State) allocate(State.class);
        set(state, State.class, "stateKey", key);
        set(state, State.class, "actions", actions);
        return state;
    }

    @Test
    public void stampedParentBoostsTheChildActionWithTheContextWeights() throws Exception {
        GUITreeNode parent = new GUITreeNode(null);
        parent.setHandlerClick("com.x.Foo$1");
        GUITreeNode node = new GUITreeNode(parent);
        parent.addChild(node);
        GUITreeNode plain = new GUITreeNode(null);
        plain.setResourceID("com.x:id/plain");

        StubScoringContext ctx = new StubScoringContext();
        MopData data = artifact("\"plain\":{\"mop\":{\"click\":\"none\"}}",
                "\"com.x.Foo$1\":{\"mop\":{\"click\":\"transitive\"}}");
        ctx.mopContext = TestMopContexts.flag(data, 911, 577);

        ModelAction viaParent = resolvedClick(node);
        ModelAction unflagged = resolvedClick(plain);
        viaParent.setPriority(10);
        unflagged.setPriority(10);
        ModelAction[] actions = {viaParent, unflagged};

        // The weights are the context's.
        MopWidgetPass pass = new MopWidgetPass(ctx, new NoopSink());
        assertTrue(pass.isEnabled());
        pass.apply(state(actions), actions, ctx);

        assertEquals(577, viaParent.getMopBoost());
        assertEquals(10 + 577, viaParent.getPriority());
        assertEquals(0, unflagged.getMopBoost());
        assertEquals(10, unflagged.getPriority());
    }

    @Test
    public void disabledWithoutAContext() {
        assertFalse(new MopWidgetPass(new StubScoringContext(), new NoopSink()).isEnabled());
    }
}
