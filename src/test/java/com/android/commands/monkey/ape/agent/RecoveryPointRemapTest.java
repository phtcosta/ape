package com.android.commands.monkey.ape.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;
import java.util.LinkedList;

import org.junit.Test;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.Model;
import com.android.commands.monkey.ape.model.Model.RecoveryPoint;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeAction;
import com.android.commands.monkey.ape.tree.GUITreeNode;

/**
 * rearch-06 group 3 (V11), task 3.4 — the contrast pair.
 *
 * <p>A naming refinement rebuilds the model, and {@code StatefulAgent.updateModel} remaps the
 * recovery point through {@code Model.update(action, guiAction)} <strong>iff the action satisfies
 * {@code requireTarget()}</strong>. That guard is the one the deleted per-record remap loop applied
 * (its {@code isModelAction() && requireTarget()} condition), while {@code recoverCurrentState}
 * accepts any model action. The asymmetry is deliberate and it is a defect: a targetless recovery
 * point ({@code MODEL_BACK}, {@code MODEL_MENU}) keeps referring to the pre-rebuild object and
 * recovers a stale one.
 *
 * <p>Remapping it unconditionally would very likely be an improvement, but it would be a behavior
 * change, and stage 6 is a memory repair sold as decision-neutral (INV-MODEL-20) with no evidence
 * able to measure the difference. So both branches are pinned here: dropping or extending the guard
 * later breaks one of these two tests, which is the point — it makes the change visible and
 * deliberate rather than silent.
 *
 * <p>The rebuilt model is a {@link Model} subclass whose {@code update} is a scripted substitution:
 * the real one walks {@code Graph}/{@code State} structures that need the Android runtime, and what
 * is under test here is whether the remap is invoked and written back, not what it computes. The
 * agent is {@code Unsafe}-allocated (the {@code StatefulAgentTearDownTest} idiom), so its fields
 * start null and {@code actionBuffer} is injected — the allocation skips field initializers.
 */
public class RecoveryPointRemapTest {

    /** A model whose rebuild remap returns a fixed substitute and counts its invocations. */
    private static final class RemapRecordingModel extends Model {

        private static final long serialVersionUID = 1L;

        final ModelAction rebuilt;
        int updateCalls;

        RemapRecordingModel(ModelAction rebuilt) {
            super((Graph) null, null);
            this.rebuilt = rebuilt;
        }

        @Override
        public ModelAction update(ModelAction action, GUITreeAction guiAction) {
            updateCalls++;
            return rebuilt;
        }
    }

    @Test
    public void aTargetedRecoveryPointIsRemappedAcrossTheRebuild() throws Exception {
        GUITreeNode node = new GUITreeNode(null);
        ModelAction click = new ModelAction(null, ActionType.MODEL_CLICK);
        click.resolveAt(1, 50, blank(GUITree.class), node, new GUITreeNode[] { node });

        ModelAction rebuiltClick = new ModelAction(null, ActionType.MODEL_CLICK);
        RemapRecordingModel model = new RemapRecordingModel(rebuiltClick);
        model.appendToActionHistory(1000L, click, 1);
        GUITreeAction guiAction = model.getRecoveryPoint().guiAction;

        StatefulAgent agent = agentOn(model);
        agent.updateModel(model);

        assertEquals("the rebuild must remap a targeted recovery point", 1, model.updateCalls);
        RecoveryPoint point = model.getRecoveryPoint();
        assertSame("the recovery point must carry the rebuilt action", rebuiltClick, point.modelAction);
        assertSame("remapping must not disturb the GUI action", guiAction, point.guiAction);

        agent.recoverCurrentState();
        assertSame("recovery must restore the non-stale object", rebuiltClick, agent.getCurrentAction());
    }

    @Test
    public void aTargetlessRecoveryPointIsNotRemappedAndStaysStale() throws Exception {
        ModelAction back = new ModelAction(null, ActionType.MODEL_BACK);
        back.resolveAt(1, 50, blank(GUITree.class), null, null);

        ModelAction rebuiltBack = new ModelAction(null, ActionType.MODEL_BACK);
        RemapRecordingModel model = new RemapRecordingModel(rebuiltBack);
        model.appendToActionHistory(1000L, back, 1);

        StatefulAgent agent = agentOn(model);
        agent.updateModel(model);

        assertEquals("requireTarget() is false, so the remap must not run at all", 0, model.updateCalls);
        assertSame("the recovery point must still hold the pre-rebuild action",
                back, model.getRecoveryPoint().modelAction);

        agent.recoverCurrentState();
        assertSame("recovery restores the pre-rebuild object, exactly as it did before this change",
                back, agent.getCurrentAction());
    }

    @Test
    public void aBlockedRecoveryPointStillRecoversNothingAfterTheRebuild() throws Exception {
        GUITreeNode node = new GUITreeNode(null);
        ModelAction click = new ModelAction(null, ActionType.MODEL_CLICK);
        click.resolveAt(1, 50, blank(GUITree.class), node, new GUITreeNode[] { node });

        RemapRecordingModel model = new RemapRecordingModel(new ModelAction(null, ActionType.MODEL_CLICK));
        model.appendToActionHistory(1000L, click, 1);
        model.appendToActionHistory(1001L, new Action(ActionType.EVENT_START), 2);

        StatefulAgent agent = agentOn(model);
        agent.updateModel(model);
        agent.recoverCurrentState();

        // The rebuild remaps the point it finds, but the block outlives it: a start action is still
        // the most recent thing appended, so there is nothing to recover.
        assertSame(null, agent.getCurrentAction());
    }

    // --- fixtures -------------------------------------------------------------------------

    private static StatefulAgent agentOn(Model model) throws Exception {
        StatefulAgent agent = blank(StatefulAgentTearDownTest.ThrowingAgent.class);
        set(agent, "model", model);
        set(agent, "actionBuffer", new LinkedList<>());
        return agent;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = StatefulAgent.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T blank(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }
}
