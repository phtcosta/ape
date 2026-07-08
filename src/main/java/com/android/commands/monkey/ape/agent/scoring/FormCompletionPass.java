package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.agent.FormCompletion;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.Logger;

/**
 * rv-scoring-pipeline: the form-completion boost, extracted verbatim from the inline block in
 * {@code StatefulAgent.adjustActionsByGUITree()}. When the state carries ≥1 unfilled EditText, raises
 * the priority of every unfilled field and of a single submit candidate so the form is filled then
 * submitted. Scoring semantics owned by {@code form-completion}. This is the only pass with a new
 * boolean gate ({@code formCompletionEnabled}) — the inline block had no off switch before this change.
 * No-op (no boost, no log) on states with no unfilled EditText (INV-FORM-01).
 */
public final class FormCompletionPass implements ScoringPass {

    private final boolean enabled;

    public FormCompletionPass(ScoringContext ctx) {
        this.enabled = Config.formCompletionEnabled;
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
            int fields = 0;
            for (ModelAction action : actions) {
                if (FormCompletion.isUnfilledEditText(action, timestamp)) {
                    action.setPriority(action.getPriority() + FormCompletion.W_FILL);
                    action.setFormBoost(FormCompletion.W_FILL);
                    fields++;
                }
            }
            ModelAction submit = FormCompletion.selectSubmitCandidate(state, timestamp);
            String submitId = "none";
            if (submit != null) {
                submit.setPriority(submit.getPriority() + FormCompletion.W_SUBMIT);
                // Accumulate: the submit candidate may itself be an unfilled EditText already boosted
                // by W_FILL above; don't clobber that provenance (the priority added both).
                submit.setFormBoost(submit.getFormBoost() + FormCompletion.W_SUBMIT);
                submitId = submit.getTarget() != null ? submit.getTarget().toString() : submit.toString();
            }
            Logger.iformat("[APE-RV] FORM boost: state=%s#%s, fields=%d, submit=%s",
                    state.getActivity(), state.getStateKey(), fields, submitId);
        }
    }
}
