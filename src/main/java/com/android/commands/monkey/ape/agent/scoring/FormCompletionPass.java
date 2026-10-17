package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.agent.FormCompletion;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.Logger;

/**
 * rv-scoring-pipeline: the form-completion boost, extracted verbatim from the inline block in
 * {@code StatefulAgent.adjustActionsByGUITree()}. When the state carries ≥1 unfilled EditText, raises
 * the priority of every unfilled field and of a single submit candidate so the form is filled then
 * submitted. Scoring semantics owned by {@code form-completion}. The ADDONS member
 * {@code FORM_COMPLETION}: the one pass gated by a boolean rather than by a weight, since form
 * completion has no natural "zero" to switch it off with.
 * No-op (no boost, no log) on states with no unfilled EditText (INV-FORM-01).
 */
public final class FormCompletionPass implements ScoringPass {

    private final boolean enabled;

    public FormCompletionPass(ScoringContext ctx, ScoringParams params) {
        this.enabled = params.formCompletionEnabled();
    }

    @Override
    public String name() {
        return "FormCompletionPass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void apply(State state, ModelAction[] actions, ScoringContext ctx) {
        int timestamp = ctx.getTimestamp();
        if (FormCompletion.hasUnfilledEditText(state, timestamp)) {
            ModelAction submit = FormCompletion.selectSubmitCandidate(state, timestamp);
            int fields = 0;
            for (ModelAction action : actions) {
                // Rule R1: the term is computed whole and written once, never read back. The submit
                // candidate may itself be an unfilled EditText, and then it carries both parts.
                int term = 0;
                if (FormCompletion.isUnfilledEditText(action, timestamp)) {
                    term += FormCompletion.W_FILL;
                    fields++;
                }
                if (action == submit) {
                    term += FormCompletion.W_SUBMIT;
                }
                if (term > 0) {
                    action.setPriority(action.getPriority() + term);
                    action.setFormBoost(term);
                }
            }
            String submitId = "none";
            if (submit != null) {
                submitId = submit.getTarget() != null ? submit.getTarget().toString() : submit.toString();
            }
            Logger.iformat("[APE-RV] FORM boost: state=%s#%s, fields=%d, submit=%s",
                    state.getActivity(), state.getStateKey(), fields, submitId);
        }
    }
}
