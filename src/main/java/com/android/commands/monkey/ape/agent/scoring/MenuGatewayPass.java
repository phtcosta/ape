package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopScorer;

/**
 * The gh13 OPTIONSMENU gateway scorer, the MOP member {@code MENU_GATEWAY}: make the agent open the
 * menu that leads to MOP. Scoring semantics owned by {@code mop-guidance} (OPTIONSMENU-Aware Menu
 * Boost). Enabled iff {@code MENU_GATEWAY} is mounted (a positive {@code mopWeightOpenMenu}) and MOP
 * data is present.
 *
 * <p>Under rule R1 its term depends only on the activity's gateway status and the injected weight.
 * It does not read the ADDONS BACK/MENU pick cap (design D-B14.19): a capped MENU is already kept
 * out of selection by the cap itself, so reading it here would couple a MOP scorer to an ADDONS
 * member's state.
 */
public final class MenuGatewayPass implements ScoringPass {

    private final boolean enabled;
    private final int weightOpenMenu;

    public MenuGatewayPass(ScoringContext ctx, ScoringParams params) {
        this.weightOpenMenu = params.mopWeightOpenMenu();
        this.enabled = weightOpenMenu > 0 && ctx.getMopData() != null;
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
        int menuBoost = MopScorer.scoreOpenMenu(activity, ctx.getMopData(), weightOpenMenu);
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
