package com.android.commands.monkey.ape.tree;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * form-completion (§7.3) — pure-logic coverage of the canonical EditText widget set.
 *
 * <p>{@code GUITreeNode.isEditText()} delegates to {@link GUITreeBuilder#isEditText(String)}, so
 * {@code FormCompletion} now recognizes the AndroidX/framework EditText subclasses
 * ({@code AutoCompleteTextView}, {@code MultiAutoCompleteTextView}, {@code ExtractEditText}), not
 * only {@code android.widget.EditText}. The static predicate is a pure {@code Set.contains} lookup,
 * JVM-testable directly; the instance {@code GUITreeNode.isEditText()} needs a live node and is
 * device-validated per {@code docs/20260622_investigacao_mop.md} §7.5.
 */
public class GUITreeBuilderEditTextTest {

    @Test
    public void testAllCanonicalEditTextClassesRecognized() {
        assertTrue(GUITreeBuilder.isEditText("android.widget.EditText"));
        assertTrue(GUITreeBuilder.isEditText("android.inputmethodservice.ExtractEditText"));
        assertTrue(GUITreeBuilder.isEditText("android.widget.AutoCompleteTextView"));
        assertTrue(GUITreeBuilder.isEditText("android.widget.MultiAutoCompleteTextView"));
    }

    @Test
    public void testNonEditTextClassesRejected() {
        assertFalse(GUITreeBuilder.isEditText("android.widget.Button"));
        assertFalse(GUITreeBuilder.isEditText("android.widget.TextView"));
        assertFalse(GUITreeBuilder.isEditText(""));
    }
}
