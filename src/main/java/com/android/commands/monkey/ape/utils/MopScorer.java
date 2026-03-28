package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;

import java.util.List;

/**
 * Maps MOP reachability flags to integer priority boosts for action scoring.
 *
 * Weights are read from Config (configurable via ape.properties):
 *   directMop              → Config.mopWeightDirect     (default 500)
 *   transitiveMop (only)   → Config.mopWeightTransitive (default 300)
 *   activityHasMop (no widget match) → Config.mopWeightActivity (default 100)
 *   no match               →    0
 */
public class MopScorer {

    /**
     * Returns the priority boost for an action targeting the given widget.
     *
     * @param activity activity class name
     * @param shortId  short resource ID (e.g. "btn_encrypt")
     * @param data     loaded MOP data (must be non-null)
     * @return integer boost to add to existing action priority
     */
    public static int score(String activity, String shortId, MopData data) {
        MopData.WidgetMopFlags f = data.getWidget(activity, shortId);
        if (f != null) {
            if (f.directMop) {
                return Config.mopWeightDirect;
            }
            if (f.transitiveMop) {
                return Config.mopWeightTransitive;
            }
            return 0;
        }
        if (data.activityHasMop(activity)) {
            return Config.mopWeightActivity;
        }
        return 0;
    }

    /**
     * Returns the WTG-based priority boost for a widget.
     *
     * The boost is applied when the widget matches a WTG transition leading to
     * a MOP-reachable activity (INV-WTG-02, INV-MOP-06).
     *
     * @param activity   current activity class name
     * @param shortId    short resource ID of the widget (e.g. "menu_item_cipher")
     * @param data       loaded MOP data, may be null
     * @return Config.mopWeightWtg if widget leads to a MOP activity, 0 otherwise
     */
    public static int scoreWtg(String activity, String shortId, MopData data) {
        if (data == null || !data.hasWtgData() || Config.mopWeightWtg == 0) {
            return 0;
        }
        if (activity == null || shortId == null || shortId.isEmpty()) {
            return 0;
        }
        List<MopData.WtgTransition> transitions = data.getWtgTransitions(activity);
        for (MopData.WtgTransition t : transitions) {
            if (shortId.equals(t.widgetName) && data.activityHasMop(t.targetActivity)) {
                return Config.mopWeightWtg;
            }
        }
        return 0;
    }

    /**
     * Counts the number of target-requiring, valid actions in a state whose activity
     * has MOP-reachable widgets. Used as a tiebreaker when selecting navigation targets
     * in ABA and trivial-activity exploration.
     *
     * For non-current states, individual widget resolution is unavailable (getResolvedNode()
     * only works for the current GUITree). The density metric counts target actions in
     * MOP-bearing activities as a proxy for MOP reachability at that state.
     *
     * @param state the state to evaluate
     * @param data  loaded MOP data (returns 0 if null)
     * @return count of target-requiring valid actions if the activity has MOP data, else 0
     */
    public static int stateMopDensity(State state, MopData data) {
        if (data == null) {
            return 0;
        }
        String activity = state.getActivity();
        if (!data.activityHasMop(activity)) {
            return 0;
        }
        int count = 0;
        for (ModelAction action : state.getActions()) {
            if (action.requireTarget() && action.isValid()) {
                count++;
            }
        }
        return count;
    }
}
