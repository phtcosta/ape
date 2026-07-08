package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import java.util.Locale;

/**
 * Form-completion helpers (the {@code form-completion} capability). Pure, static and
 * side-effect-free: detect a state that carries unfilled {@code EditText} fields and pick a
 * single submit candidate. Shared by the form-completion boost pass ({@link StatefulAgent}) and
 * the deterministic-fill branch ({@link ApeAgent#checkInput}) so both halves read the same
 * predicate.
 *
 * <p>Deliberately minimal (P1): no form classifier, no field-dependency model, no new text
 * generator. Filling reuses the existing type-aware {@code ApeAgent.generateInputText}.
 */
public final class FormCompletion {

    private FormCompletion() {}

    /** Per-field fill boost. Positive and larger than {@link #W_SUBMIT} so fields are walked first. */
    public static final int W_FILL = 150;
    /**
     * Submit-candidate boost. Positive (INV-FORM-02) and below {@link #W_FILL}. These magnitudes
     * tune only the roulette/tiebreaker path; the short-circuit ordering is enforced by the
     * INV-FORM-06 guard. Initial values pending the §7.5 device tuning (OQ4).
     */
    public static final int W_SUBMIT = 100;

    /** Submit-like words matched case-insensitively against a clickable's visible text (OQ2). */
    private static final String[] SUBMIT_WORDS = {
            "submit", "ok", "send", "login", "sign in", "signin", "encrypt", "decrypt",
            "generate", "save", "confirm", "next", "done"
    };

    /**
     * True iff the action is a resolved, valid, target-requiring {@code EditText} whose node has
     * no text yet ({@code getInputText() == null}). Null/unresolved nodes yield false.
     */
    public static boolean isUnfilledEditText(ModelAction action, int timestamp) {
        if (action == null || !action.requireTarget() || !action.isValid()) {
            return false;
        }
        if (!action.isResolvedAt(timestamp)) {
            return false;
        }
        GUITreeNode node = action.getResolvedNode();
        // "Unfilled" requires both that APE has not typed here (getInputText()==null) AND that the
        // captured node carries no text. inputText is a per-capture annotation never re-seeded onto
        // the next capture's fresh nodes, so getInputText() alone reads unfilled forever once the
        // field is re-captured; getText() carries the typed content across captures, so the
        // predicate converges and the form context clears after the field is filled (INV-FORM-07).
        return node != null && node.isEditText()
                && node.getInputText() == null
                && (node.getText() == null || node.getText().isEmpty());
    }

    /**
     * True iff the state carries at least one unfilled {@code EditText} action — the
     * "form-completion context". Pure; reads {@code State.getActions()} without mutation.
     */
    public static boolean hasUnfilledEditText(State state, int timestamp) {
        if (state == null) {
            return false;
        }
        for (ModelAction action : state.getActions()) {
            if (isUnfilledEditText(action, timestamp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The single submit candidate (INV-FORM-04), or null. The MOP-boosted target (highest
     * {@code mopBoost > 0}) wins when present (INV-FORM-05) — it is the submit handler the run
     * already steers toward. Otherwise a minimal heuristic: a lone enabled {@code Button} click,
     * else a click whose visible text matches a submit word. Pure (no mutation); must run after
     * the MOP pass so {@code mopBoost} is populated.
     */
    public static ModelAction selectSubmitCandidate(State state, int timestamp) {
        if (state == null) {
            return null;
        }
        ModelAction mopBest = null;
        for (ModelAction action : state.getActions()) {
            if (action.getMopBoost() > 0
                    && (mopBest == null || action.getMopBoost() > mopBest.getMopBoost())) {
                mopBest = action;
            }
        }
        if (mopBest != null) {
            return mopBest;
        }
        ModelAction loneButton = null;
        int buttonCount = 0;
        ModelAction wordMatch = null;
        for (ModelAction action : state.getActions()) {
            if (action.getType() != ActionType.MODEL_CLICK || !action.isValid()
                    || !action.isResolvedAt(timestamp)) {
                continue;
            }
            GUITreeNode node = action.getResolvedNode();
            if (node == null || !node.isEnabled()) {
                continue;
            }
            String cls = node.getClassName();
            if (cls != null && cls.contains("Button")) {
                buttonCount++;
                loneButton = action;
            }
            if (wordMatch == null && matchesSubmitWord(node.getText())) {
                wordMatch = action;
            }
        }
        if (buttonCount == 1) {
            return loneButton;
        }
        return wordMatch;
    }

    private static boolean matchesSubmitWord(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : SUBMIT_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
