package com.android.commands.monkey.ape.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.Test;

import com.android.commands.monkey.ape.model.Model.ActionRecord;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeNode;

/**
 * rearch-06 group 3 (V11), task 3.4: an {@link ActionRecord} is a snapshot of primitives and
 * strings and holds no model object at all (INV-MODEL-18).
 *
 * <p>Before this change the record held the {@code Action} and its {@code GUITreeAction}, and
 * through the latter a whole {@code GUITree} — one full GUI tree pinned per executed step for the
 * rest of the run. The first test below is the invariant itself, stated over the class's declared
 * fields rather than over one constructed instance: a future field of a reference type fails it
 * whether or not any test happens to populate that field.
 *
 * <p>{@code GUITree} is {@code Unsafe}-allocated (its constructor needs {@code ComponentName},
 * which surefire excludes from the runtime classpath) and its {@code id} is set reflectively, there
 * being no setter; {@code GUITreeNode(null)} and {@code State} follow the techniques already used
 * by {@code CoordinateMapperMappingTest} and {@code RebuildCountTest}.
 */
public class ActionHistorySnapshotTest {

    @Test
    public void theRecordDeclaresNothingThatCouldRetainAModelObject() {
        for (Field field : ActionRecord.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            assertTrue("ActionRecord." + field.getName() + " is a " + type.getName()
                    + "; the record may hold primitives and strings only (INV-MODEL-18)",
                    type.isPrimitive() || type == String.class);
        }
    }

    @Test
    public void theRecordIsNoLongerSerializable() {
        // Nothing serializes a Model since rearch-02 deleted saveGraph/sataModel.obj, and the
        // record's Serializable contract was only ever there to ride along with it (P3).
        assertFalse(Serializable.class.isAssignableFrom(ActionRecord.class));
    }

    @Test
    public void appendingATargetedModelActionCapturesTheSnapshotFields() throws Exception {
        Model model = new Model((Graph) null, null);
        GUITree tree = treeWith(42, 7);
        GUITreeNode node = new GUITreeNode(null);
        State state = stateWithGraphId("S1");
        ModelAction action = new ModelAction(state, new TestName("//Button[@text=\"OK\"]"),
                ActionType.MODEL_CLICK);
        action.resolveAt(3, 250, tree, node, new GUITreeNode[] { node });

        model.appendToActionHistory(1000L, action, 3);

        ActionRecord record = model.getActionHistory().get(0);
        assertEquals(1000L, record.clockTimestamp);
        assertEquals(3, record.agentTimestamp);
        assertEquals("MODEL_CLICK", record.actionType);
        assertEquals("S1", record.stateId);
        assertEquals("//Button[@text=\"OK\"]", record.targetXPath);
        assertEquals(42, record.treeId);
        assertEquals(7, record.treeTimestamp);
        assertEquals(250, record.throttle);
    }

    @Test
    public void aTargetlessModelActionRecordsANullTargetButKeepsItsStateAndTree() throws Exception {
        Model model = new Model((Graph) null, null);
        GUITree tree = treeWith(9, 4);
        ModelAction back = new ModelAction(stateWithGraphId("S2"), ActionType.MODEL_BACK);
        back.resolveAt(5, 100, tree, null, null);

        model.appendToActionHistory(2000L, back, 5);

        ActionRecord record = model.getActionHistory().get(0);
        assertEquals("MODEL_BACK", record.actionType);
        assertEquals("S2", record.stateId);
        assertNull(record.targetXPath);
        assertEquals(9, record.treeId);
        assertEquals(4, record.treeTimestamp);
    }

    @Test
    public void aNonModelActionRecordsNoStateNoTargetAndNoTree() {
        Model model = new Model((Graph) null, null);
        Action fuzz = new Action(ActionType.FUZZ);
        fuzz.setThrottle(11);

        model.appendToActionHistory(3000L, fuzz, 6);

        ActionRecord record = model.getActionHistory().get(0);
        assertEquals("FUZZ", record.actionType);
        assertNull(record.stateId);
        assertNull(record.targetXPath);
        // -1 says "this action carried no resolved GUI action", which a real tree id never does.
        assertEquals(-1, record.treeId);
        assertEquals(-1, record.treeTimestamp);
        assertEquals("the action's own throttle stands in when there is no GUITreeAction",
                11, record.throttle);
    }

    @Test
    public void aCrashRecordAppendsWithoutResolvedObjects() {
        // ApeAgent.appCrashed appends a CrashAction whose resolved GUI action is null — the case
        // that made the old teardown re-resolution throw. Appending must simply record it.
        Model model = new Model((Graph) null, null);
        Action crash = new CrashAction(new Crash("p", 1, "short", "long", 4000L, "trace"));

        model.appendToActionHistory(4000L, crash, 8);

        ActionRecord record = model.getActionHistory().get(0);
        assertEquals("PHANTOM_CRASH", record.actionType);
        assertNull(record.stateId);
        assertEquals(-1, record.treeId);
    }

    // --- fixtures -------------------------------------------------------------------------

    /** A Name whose only job is to carry an XPath, as five other test files already define it. */
    private static final class TestName implements Name {
        private final String xpath;

        TestName(String xpath) { this.xpath = xpath; }

        public Namer getNamer() { return null; }
        public Name getLocalName() { return this; }
        public boolean refinesTo(Name other) { return this.equals(other); }
        public String toXPath() { return xpath; }
        public void appendXPathLocalProperties(StringBuilder sb) { }
        public void toXPath(StringBuilder sb) { sb.append(xpath); }
        public int compareTo(Name other) { return xpath.compareTo(other.toXPath()); }
    }

    private static GUITree treeWith(int id, int timestamp) throws Exception {
        GUITree tree = allocate(GUITree.class);
        Field idField = GUITree.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.setInt(tree, id);
        tree.setTimestamp(timestamp);
        return tree;
    }

    private static State stateWithGraphId(String id) throws Exception {
        State state = allocate(State.class);
        state.setGraphId(id);
        return state;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }
}
