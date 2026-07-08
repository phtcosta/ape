package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopScorer;

/**
 * rv-scoring-pipeline: the gh13 OPTIONSMENU gateway boost, extracted verbatim from the inline block
 * in {@code StatefulAgent.adjustActionsByGUITree()} — make the agent open the menu that leads to MOP.
 * Scoring semantics owned by {@code mop-guidance} (OPTIONSMENU-Aware Menu Boost). Enabled whenever MOP
 * data is present ({@code mopData != null}); the back-menu-pick-cap gate is applied per-apply through
 * {@link ScoringContext#menuPickEligible} (base agent: always eligible; SataAgent: cap check).
 */
public final class MenuGatewayPass implements ScoringPass {

    private final boolean enabled;

    public MenuGatewayPass(ScoringContext ctx) {
        this.enabled = ctx.getMopData() != null;
    }

    @Override
    public String name() {
        return "MenuGatewayPass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void apply(State state, ModelAction[] actions, ScoringContext ctx) {
        String activity = state.getActivity();
        // back-menu-pick-cap: gate the +menuBoost by menuPickEligible so a MENU whose (activity,
        // MODEL_MENU) key is capped stops re-dominating the roulette. No setPriority/setMenuBoost
        // when the gate is closed.
        int menuBoost = ctx.menuPickEligible(activity) ? MopScorer.scoreOpenMenu(activity, ctx.getMopData()) : 0;
        if (menuBoost > 0) {
            for (ModelAction action : actions) {
                if (action.getType() == ActionType.MODEL_MENU) {
                    action.setPriority(action.getPriority() + menuBoost);
                    action.setMenuBoost(menuBoost);
                    Logger.iformat("[APE-RV] menu boost: state=%s#%s, +%d on MODEL_MENU",
                            activity, state.getStateKey(), menuBoost);
                }
            }
        }
    }
}
