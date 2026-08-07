package com.android.commands.monkey.ape.model;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.android.commands.monkey.ape.model.Model.RecoveryPoint;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeAction;
import com.android.commands.monkey.ape.tree.GUITreeNode;

/**
 * rearch-06 group 3 (V11), task 3.4: the depth-1 recovery point the {@link Model} now maintains on
 * every append must answer exactly what the backward scan over the rich history answered.
 *
 * <p>The scan is gone from {@code StatefulAgent.recoverCurrentState}, so it is reproduced here,
 * verbatim in structure, as {@link #headScan(List)} — the equivalence has to be checked against
 * something, and a paraphrase would only prove the paraphrase. Every case below asserts the two
 * agree, including on <em>which</em> pair is returned, by reference identity.
 *
 * <p>The append alphabet has exactly three letters, which is what makes the enumeration complete:
 * an action that {@code canStartApp()}, a model action, and everything else (fuzz events, crash
 * records, lifecycle events). The cases are the sequence classes named in the task — {@code
 * [model]}, {@code [model, start, fuzz]}, {@code [start, model, fuzz]}, {@code [fuzz]}, empty, and
 * a crash record — plus two that pin the "most recent wins" reading of the predicate.
 */
public class RecoveryPointEquivalenceTest {

    /** One appended action together with the GUI action it carried, i.e. one HEAD record. */
    private static final class Appended {
        final Action action;
        final GUITreeAction guiAction;

        Appended(Action action, GUITreeAction guiAction) {
            this.action = action;
            this.guiAction = guiAction;
        }
    }

    @Test
    public void emptyHistoryRecoversNothing() throws Exception {
        assertEquivalent();
    }

    @Test
    public void aSingleModelActionIsTheRecoveryPoint() throws Exception {
        assertEquivalent(modelAction());
    }

    @Test
    public void aStartActionAfterTheModelActionBlocksRecovery() throws Exception {
        assertEquivalent(modelAction(), startAction(), fuzzAction());
    }

    @Test
    public void aModelActionAfterTheStartActionRestoresRecovery() throws Exception {
        assertEquivalent(startAction(), modelAction(), fuzzAction());
    }

    @Test
    public void fuzzOnlyHistoryRecoversNothing() throws Exception {
        assertEquivalent(fuzzAction(), fuzzAction());
    }

    @Test
    public void aCrashRecordIsInvisibleToRecovery() throws Exception {
        // A crash record is a non-model action appended with a null GUI action: rule 3, a no-op,
        // exactly as the scan walked past it.
        assertEquivalent(modelAction(), crashAction());
    }

    @Test
    public void theMostRecentModelActionWins() throws Exception {
        assertEquivalent(modelAction(), modelAction(), modelAction());
    }

    @Test
    public void aStartActionAloneBlocksAnEmptyPoint() throws Exception {
        assertEquivalent(startAction());
    }

    @Test
    public void aTargetlessModelActionIsAnEligibleRecoveryPoint() throws Exception {
        // recoverCurrentState accepts any isModelAction(), targeted or not — the asymmetry with the
        // rebuild remap's requireTarget() guard that RecoveryPointRemapTest pins.
        assertEquivalent(targetlessModelAction(), fuzzAction());
    }

    /**
     * Appends the sequence to a real {@link Model} and to the reproduced HEAD scan, and asserts the
     * two agree — on whether there is a recovery point at all, and on which pair it is.
     */
    private static void assertEquivalent(Appended... sequence) throws Exception {
        Model model = new Model((Graph) null, null);
        List<Appended> history = new ArrayList<>();
        int timestamp = 0;
        for (Appended appended : sequence) {
            model.appendToActionHistory(1000L + timestamp, appended.action, timestamp);
            history.add(appended);
            timestamp++;
        }

        RecoveryPoint expected = headScan(history);
        RecoveryPoint actual = model.isRecoveryBlocked() ? null : model.getRecoveryPoint();

        if (expected == null) {
            assertNull("the HEAD scan recovers nothing here", actual);
            return;
        }
        assertNotNull("the HEAD scan recovers, the recovery point does not", actual);
        assertSame("recovered a different action than the HEAD scan", expected.modelAction, actual.modelAction);
        assertSame("recovered a different GUI action than the HEAD scan", expected.guiAction, actual.guiAction);
    }

    /**
     * The backward scan {@code recoverCurrentState} ran before this change, structure for
     * structure: walk from the end, stop with nothing on the first record that can start the app,
     * stop with the first model-action record, skip everything else.
     */
    private static RecoveryPoint headScan(List<Appended> history) {
        if (history.isEmpty()) {
            return null;
        }
        Appended record = null;
        for (int index = history.size() - 1; index >= 0; index--) {
            record = history.get(index);
            if (record.action.canStartApp()) {
                return null; // do nothing if is start
            }
            if (record.action.isModelAction()) {
                break;
            }
        }
        if (record == null || !record.action.isModelAction()) {
            return null; // no valid action
        }
        return new RecoveryPoint((ModelAction) record.action, record.guiAction);
    }

    // --- fixtures -------------------------------------------------------------------------

    private static Appended modelAction() throws Exception {
        GUITreeNode node = new GUITreeNode(null);
        ModelAction action = new ModelAction(null, ActionType.MODEL_CLICK);
        action.resolveAt(1, 50, blank(GUITree.class), node, new GUITreeNode[] { node });
        return new Appended(action, action.getResolvedGUITreeAction());
    }

    private static Appended targetlessModelAction() throws Exception {
        ModelAction action = new ModelAction(null, ActionType.MODEL_BACK);
        action.resolveAt(1, 50, blank(GUITree.class), null, null);
        return new Appended(action, action.getResolvedGUITreeAction());
    }

    private static Appended startAction() {
        return new Appended(new Action(ActionType.EVENT_START), null);
    }

    private static Appended fuzzAction() {
        return new Appended(new Action(ActionType.FUZZ), null);
    }

    private static Appended crashAction() {
        return new Appended(new CrashAction(new Crash("p", 1, "short", "long", 0L, "trace")), null);
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
