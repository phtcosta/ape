package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import java.util.List;

/**
 * Maps MOP reachability to integer priority boosts for action scoring.
 *
 * Weights are read from Config (configurable via ape.properties):
 *   directMop              → Config.mopWeightDirect     (default 500)
 *   transitiveMop (only)   → Config.mopWeightTransitive (default 300)
 *   activityHasMop (no widget match) → Config.mopWeightActivity (default 100)
 *   OPTIONSMENU gateway    → Config.mopWeightOpenMenu   (default 250)
 *   no match               → 0
 */
public class MopScorer {

    /** Backward-compatible 3-arg score: match-any event type. */
    public static int score(String activity, String shortId, MopData data) {
        return score(activity, shortId, data, null);
    }

    /**
     * Priority boost for an action targeting the given widget, scored against the
     * candidate's event type (T1.6). When {@code candidateEventType} is non-null and the
     * widget has a per-event-type entry for it, that flag drives the boost; otherwise the
     * aggregate flag applies (match-any fallback, INV-MOP-14).
     *
     * @param data may be null (returns 0 — null-safe, INV-MOP)
     */
    public static int score(String activity, String shortId, MopData data,
                            String candidateEventType) {
        if (data == null) {
            return 0;
        }
        MopData.Widget w = data.getWidget(activity, shortId);
        if (w != null) {
            if (w.isDirectMop(candidateEventType)) {
                return Config.mopWeightDirect;
            }
            if (w.isTransitiveMop(candidateEventType)) {
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
     * Boost for opening the options menu of the given activity (T1.2). Returns
     * {@code Config.mopWeightOpenMenu} when the activity's OPTIONSMENU is a MOP gateway
     * (INV-MOP-13), else 0. O(1) over the precomputed set.
     *
     * @param data may be null (returns 0)
     */
    public static int scoreOpenMenu(String activity, MopData data) {
        if (data == null || activity == null) {
            return 0;
        }
        return data.activityHasMopOptionsMenu(activity) ? Config.mopWeightOpenMenu : 0;
    }

    /**
     * WTG-based boost: applied when the widget matches a WTG transition leading to a
     * MOP-reachable activity (INV-WTG-02, INV-MOP-06).
     *
     * @param data may be null (returns 0)
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
     * Counts target-requiring, valid actions in a state whose activity has MOP-reachable
     * widgets. Used as a tiebreaker in ABA / trivial-activity exploration.
     *
     * @param data loaded MOP data (returns 0 if null)
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

    /**
     * Maps a {@link ModelAction} to the listener event type it represents (T1.6, INV-MOP-14).
     * Returns null for action types with no listener-event analog (triggers match-any).
     */
    public static String eventTypeOf(ModelAction action) {
        if (action == null) {
            return null;
        }
        return eventTypeOf(action.getType(), targetWidgetClass(action));
    }

    /**
     * Maps an action type (and optional target widget class for the Spinner heuristic)
     * to a listener event type:
     * MODEL_CLICK → "click" (or "itemSelected" when the target is a Spinner),
     * MODEL_LONG_CLICK → "longClick", any MODEL_SCROLL_* → "scroll", else null.
     */
    public static String eventTypeOf(ActionType type, String widgetClass) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case MODEL_CLICK:
                return (widgetClass != null && widgetClass.contains("Spinner"))
                        ? "itemSelected" : "click";
            case MODEL_LONG_CLICK:
                return "longClick";
            default:
                return type.isScroll() ? "scroll" : null;
        }
    }

    private static String targetWidgetClass(ModelAction action) {
        try {
            GUITreeNode node = action.getResolvedNode();
            return node != null ? node.getClassName() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
