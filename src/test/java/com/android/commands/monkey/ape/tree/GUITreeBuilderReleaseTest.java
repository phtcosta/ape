package com.android.commands.monkey.ape.tree;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Naming;

/**
 * rearch-06 group 2 (V12): {@link GUITreeBuilder#release(GUITree)} must leave no memoized entry
 * behind for a tree the model has released — in all three static caches, under every naming, and
 * whether or not the tree still has a current naming.
 *
 * <h2>Why the fixtures are blank instances</h2>
 * A real {@link GUITree} cannot be built on the JVM: its constructor takes a {@link GUITreeNode},
 * which holds an {@code AccessibilityNodeInfo} that is not on the test classpath — the same wall
 * {@code GUITreeContainsTest} hit, and the one {@code rearch-01} recorded as finding 2.1-a. It does
 * not need to be built here. The caches key on {@code GUITree} and {@code Naming} by identity
 * (neither class overrides {@code equals}/{@code hashCode}), and the sweep reads nothing from
 * either — so an {@code Unsafe}-allocated instance is a faithful stand-in for a cache key, which is
 * the only role the fixtures play. {@code Unsafe.allocateInstance} is the established technique in
 * this test tree ({@code OracleScaffold:299-303}).
 *
 * <p>Every fixture tree here keeps {@code currentNaming == null}, which serves two purposes: it is
 * the case task 2.2 requires the sweep to run <em>before</em> (the early return), and it keeps
 * {@link Naming#release} — which would touch the uninitialized internals of a blank {@code Naming}
 * — out of the path. With no current naming, every naming in the maps is a non-current one, so the
 * "entries under a non-current naming are also removed" clause is covered a fortiori.
 *
 * <p>The caches are process-global statics, so each test clears them on both sides.
 */
public class GUITreeBuilderReleaseTest {

    private Naming namingA;
    private Naming namingB;
    private GUITree released;
    private GUITree survivor;

    @Before
    public void setUp() throws Exception {
        clearCaches();
        namingA = blank(Naming.class);
        namingB = blank(Naming.class);
        released = blank(GUITree.class);
        survivor = blank(GUITree.class);
    }

    @After
    public void tearDown() {
        clearCaches();
    }

    @Test
    public void releaseDropsTheTreeFromAllThreeCaches() throws Exception {
        cacheAll(namingA, released);
        cacheAll(namingA, survivor);

        GUITreeBuilder.release(released);

        assertFalse("state-key cache still holds the released tree",
                GUITreeBuilder.namingToGUITreeCache.get(namingA).containsKey(released));
        assertFalse("per-tree nodes cache still holds the released tree",
                GUITreeBuilder.namingToGUITreeNodesCache.get(namingA).containsKey(released));
        assertFalse("per-node name cache still holds the released tree",
                GUITreeBuilder.namingToGUITreeNodeCache.get(namingA).containsKey(released));
    }

    @Test
    public void releaseDropsTheTreeUnderEveryNaming() throws Exception {
        // Refinement probes reach getStateKey/getNodeName with candidate namings, so a tree
        // accumulates entries under namings that are not its own. Sweeping one naming left those.
        cacheAll(namingA, released);
        cacheAll(namingB, released);

        GUITreeBuilder.release(released);

        assertFalse(GUITreeBuilder.namingToGUITreeCache.get(namingA).containsKey(released));
        assertFalse(GUITreeBuilder.namingToGUITreeCache.get(namingB).containsKey(released));
        assertFalse(GUITreeBuilder.namingToGUITreeNodesCache.get(namingA).containsKey(released));
        assertFalse(GUITreeBuilder.namingToGUITreeNodesCache.get(namingB).containsKey(released));
        assertFalse(GUITreeBuilder.namingToGUITreeNodeCache.get(namingA).containsKey(released));
        assertFalse(GUITreeBuilder.namingToGUITreeNodeCache.get(namingB).containsKey(released));
    }

    @Test
    public void releaseSweepsATreeThatHasNoCurrentNaming() throws Exception {
        // The sweep runs before the `currentNaming == null` early return: a tree with no current
        // naming can still have been probed under others, and returning early skipped it entirely.
        cacheAll(namingA, released);

        GUITreeBuilder.release(released);

        assertFalse(GUITreeBuilder.namingToGUITreeCache.get(namingA).containsKey(released));
        assertFalse(GUITreeBuilder.namingToGUITreeNodesCache.get(namingA).containsKey(released));
        assertFalse(GUITreeBuilder.namingToGUITreeNodeCache.get(namingA).containsKey(released));
    }

    @Test
    public void releaseLeavesOtherTreesUntouched() throws Exception {
        cacheAll(namingA, released);
        cacheAll(namingA, survivor);
        cacheAll(namingB, survivor);

        GUITreeBuilder.release(released);

        assertTrue(GUITreeBuilder.namingToGUITreeCache.get(namingA).containsKey(survivor));
        assertTrue(GUITreeBuilder.namingToGUITreeNodesCache.get(namingA).containsKey(survivor));
        assertTrue(GUITreeBuilder.namingToGUITreeNodeCache.get(namingA).containsKey(survivor));
        assertTrue(GUITreeBuilder.namingToGUITreeNodeCache.get(namingB).containsKey(survivor));
    }

    @Test
    public void perNodeCacheIsKeyedByTreeSoAnEntryCanBeFound() throws Exception {
        // The re-keying is what makes the sweep possible at all: keyed directly by node, an entry
        // had no way back to the tree that produced it. Pin the shape the sweep depends on.
        Map<GUITreeNode, Name> names = new HashMap<>();
        names.put(null, null); // a node stands in as a key only; nodes are not JVM-constructable
        addToMapMap(GUITreeBuilder.namingToGUITreeNodeCache, namingA, released, names);

        assertNotNull(GUITreeBuilder.namingToGUITreeNodeCache.get(namingA).get(released));

        GUITreeBuilder.release(released);

        assertFalse(GUITreeBuilder.namingToGUITreeNodeCache.get(namingA).containsKey(released));
    }

    // --- fixtures -------------------------------------------------------------------------

    /** Registers {@code tree} under {@code naming} in all three caches. */
    private void cacheAll(Naming naming, GUITree tree) throws Exception {
        addToMapMap(GUITreeBuilder.namingToGUITreeCache, naming, tree, blank(StateKey.class));
        addToMapMap(GUITreeBuilder.namingToGUITreeNodesCache, naming, tree, new Object[0]);
        addToMapMap(GUITreeBuilder.namingToGUITreeNodeCache, naming, tree,
                new HashMap<GUITreeNode, Name>());
    }

    private static <K, K2, V> void addToMapMap(Map<K, Map<K2, V>> mapMap, K key, K2 key2, V value) {
        Map<K2, V> values = mapMap.get(key);
        if (values == null) {
            values = new HashMap<>();
            mapMap.put(key, values);
        }
        values.put(key2, value);
    }

    private static void clearCaches() {
        GUITreeBuilder.namingToGUITreeCache.clear();
        GUITreeBuilder.namingToGUITreeNodesCache.clear();
        GUITreeBuilder.namingToGUITreeNodeCache.clear();
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
