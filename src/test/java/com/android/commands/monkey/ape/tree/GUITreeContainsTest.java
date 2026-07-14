package com.android.commands.monkey.ape.tree;

import static org.junit.Assert.*;

import org.junit.Test;

import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;

/**
 * INV-TREE-08 (task 1.3): {@link GUITree#indexOfName(Name[], Name)} — the guard extracted from
 * {@link GUITree#contains(GUITreeNode)} — must map every absent key to {@code -1}, not only the
 * {@code insertionPoint == 0} case.
 *
 * <p>{@code Arrays.binarySearch} returns {@code -(insertionPoint) - 1} for a missing key, which is
 * {@code <= -2} when the key sorts after the first name. The old {@code index == -1} guard let
 * that fall through to {@code currentNodes[-2]} and threw {@link ArrayIndexOutOfBoundsException}.
 * The method is tested directly (rather than through {@code contains}) because {@code GUITreeNode}
 * holds an {@code android.view.accessibility.AccessibilityNodeInfo} field that is not on the JVM
 * test classpath, so it cannot be constructed or field-reflected here.
 */
public class GUITreeContainsTest {

    /** Minimal JVM-constructable {@link Name} ordered by an integer key. */
    private static final class Key implements Name {
        private final int k;
        Key(int k) { this.k = k; }
        @Override public int compareTo(Name o) { return Integer.compare(k, ((Key) o).k); }
        @Override public Namer getNamer() { return null; }
        @Override public Name getLocalName() { return this; }
        @Override public boolean refinesTo(Name other) { return false; }
        @Override public String toXPath() { return "k" + k; }
        @Override public void appendXPathLocalProperties(StringBuilder sb) { sb.append(k); }
        @Override public void toXPath(StringBuilder sb) { sb.append("k").append(k); }
    }

    @Test
    public void absentKeyAfterFirstMapsToMinusOne() {
        // binarySearch = -2 (insertion point 1); old `== -1` guard indexed [-2] and threw.
        assertEquals(-1, GUITree.indexOfName(new Name[]{new Key(1)}, new Key(5)));
    }

    @Test
    public void absentKeyBeforeFirstMapsToMinusOne() {
        // binarySearch = -1 (insertion point 0) — the case the old guard already handled.
        assertEquals(-1, GUITree.indexOfName(new Name[]{new Key(10)}, new Key(5)));
    }

    @Test
    public void absentKeyInMiddleMapsToMinusOne() {
        assertEquals(-1, GUITree.indexOfName(new Name[]{new Key(1), new Key(3), new Key(9)}, new Key(5)));
    }

    @Test
    public void presentKeyReturnsItsIndex() {
        assertEquals(1, GUITree.indexOfName(new Name[]{new Key(1), new Key(3), new Key(9)}, new Key(3)));
    }
}
