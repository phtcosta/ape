package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Maps MOP reachability to integer priority boosts for action scoring.
 *
 * <p>Every weight arrives as a parameter from the calling pass, which read it off the run's
 * {@code ScoringParams} (INV-ARCH-11). This class resolves widgets and transitions; it does not
 * decide what a match is worth, and it has no opinion about where the number came from:
 *   directMop              → the caller's direct weight
 *   transitiveMop (only)   → the caller's transitive weight
 *   OPTIONSMENU gateway    → the caller's open-menu weight
 *   no match               → 0 (discriminative-only; no activity-level fallback)
 */
public class MopScorer {

    /**
     * Priority boost for an action targeting the given widget, scored against the
     * candidate's event type (T1.6). When {@code candidateEventType} is non-null and the
     * widget has a per-event-type entry for it, that flag drives the boost; otherwise the
     * aggregate flag applies (match-any fallback, INV-MOP-14).
     *
     * @param data may be null (returns 0 — null-safe, INV-MOP)
     * @param weightDirect boost for a directly-MOP widget
     * @param weightTransitive boost for a widget that reaches a monitored operation transitively
     */
    public static int score(String activity, String shortId, MopData data,
                            String candidateEventType, int weightDirect, int weightTransitive) {
        if (data == null) {
            return 0;
        }
        MopData.Widget w = data.getWidget(activity, shortId);
        if (w != null) {
            if (w.isDirectMop(candidateEventType)) {
                return weightDirect;
            }
            if (w.isTransitiveMop(candidateEventType)) {
                return weightTransitive;
            }
        }
        // Discriminative-only: a null or resolved-but-unflagged widget carries no MOP
        // information and scores 0, even on a MOP-bearing activity. The removed +100
        // activity fallback shifted every candidate equally and could not re-rank them;
        // activityHasMop survives only as a predicate for WTG scoring and stateMopDensity.
        return 0;
    }

    /**
     * Ordered containment candidate short ids for a node (INV-MOP-23, design D3):
     * the node's own short id first, then ancestors (≤ 2 levels up via
     * {@code getParent}), then descendants (≤ 2 levels down: children then
     * grandchildren) — each mapped through {@link MopData#extractShortId}. This is
     * the exact set {@code StatefulAgent.mopBoostWithContainment} walks; both the MOP
     * boost pass and the typed-input widget resolution consume it so their matching
     * rules cannot diverge. Pure: no mutation, no logging.
     */
    public static List<String> containmentShortIds(GUITreeNode node) {
        List<String> ids = new ArrayList<>();
        ids.add(MopData.extractShortId(node.getResourceID()));
        // Ancestors, up to 2 levels up.
        GUITreeNode ancestor = node.getParent();
        for (int depth = 1; depth <= 2 && ancestor != null; depth++) {
            ids.add(MopData.extractShortId(ancestor.getResourceID()));
            ancestor = ancestor.getParent();
        }
        // Descendants, up to 2 levels down (children then grandchildren).
        Iterator<GUITreeNode> children = node.getChildren();
        while (children.hasNext()) {
            GUITreeNode child = children.next();
            ids.add(MopData.extractShortId(child.getResourceID()));
            Iterator<GUITreeNode> grandchildren = child.getChildren();
            while (grandchildren.hasNext()) {
                ids.add(MopData.extractShortId(grandchildren.next().getResourceID()));
            }
        }
        return ids;
    }

    /**
     * Boost for opening the options menu of the given activity (T1.2). Returns
     * {@code weightOpenMenu} when the activity's OPTIONSMENU is a MOP gateway (INV-MOP-13), else 0.
     * O(1) over the precomputed set.
     *
     * @param data may be null (returns 0)
     * @param weightOpenMenu boost for a gateway activity's menu
     */
    public static int scoreOpenMenu(String activity, MopData data, int weightOpenMenu) {
        if (data == null || activity == null) {
            return 0;
        }
        return data.activityHasMopOptionsMenu(activity) ? weightOpenMenu : 0;
    }

    /**
     * WTG-based boost: applied when the widget matches a WTG transition leading to a
     * MOP-reachable activity (INV-WTG-02, INV-MOP-06).
     *
     * @param data may be null (returns 0)
     * @param weightWtg boost for a transition into a MOP-reachable activity; 0 disables the term
     */
    public static int scoreWtg(String activity, String shortId, MopData data, int weightWtg) {
        if (data == null || !data.hasWtgData() || weightWtg == 0) {
            return 0;
        }
        if (activity == null || shortId == null || shortId.isEmpty()) {
            return 0;
        }
        List<MopData.WtgTransition> transitions = data.getWtgTransitions(activity);
        for (MopData.WtgTransition t : transitions) {
            if (shortId.equals(t.widgetName) && data.activityHasMop(t.targetActivity)) {
                return weightWtg;
            }
        }
        return 0;
    }

    /**
     * MOP-bearing-state density with an activity-substrate floor (INV-MOP-24): returns
     * {@code 0} when the state's activity is not MOP-bearing (cheap early-out, no widget
     * resolution); otherwise {@code 1 + count}, where {@code count} counts only actions whose
     * RESOLVED widget is MOP-flagged (direct- or transitive-MOP on the action's own event type),
     * resolved exactly as {@link #score} does (short id → {@code getWidget} →
     * {@code isDirectMop}/{@code isTransitiveMop}).
     *
     * <p>The {@code +1} floor lets an A′-rescued activity — MOP-bearing via component/lambda
     * reachability, carrying no widget-level flag — rank above a non-MOP activity ({@code 1 > 0})
     * in the SATA navigation tiebreaks, while any widget-flagged state still ranks strictly above
     * an activity-floor-only state ({@code 1 + count > 1} for {@code count ≥ 1}). It is a
     * cross-state ranking signal only; {@link #score} stays widget-discriminative (no per-candidate
     * activity fallback). {@code timestamp} selects the GUITree the actions were resolved against.
     * Pure: no mutation, no logging.
     *
     * @param data loaded MOP data (returns 0 if null)
     */
    public static int stateMopDensity(State state, MopData data, int timestamp) {
        if (data == null) {
            return 0;
        }
        String activity = state.getActivity();
        if (!data.activityHasMop(activity)) {
            return 0;
        }
        int count = 0;
        for (ModelAction action : state.getActions()) {
            if (!action.requireTarget() || !action.isValid()) {
                continue;
            }
            if (!action.isResolvedAt(timestamp)) {
                continue;
            }
            GUITreeNode node = action.getResolvedNode();
            if (node == null) {
                continue;
            }
            MopData.Widget w = data.getWidget(
                    activity, MopData.extractShortId(node.getResourceID()));
            if (w == null) {
                continue;
            }
            String eventType = eventTypeOf(action);
            if (w.isDirectMop(eventType) || w.isTransitiveMop(eventType)) {
                count++;
            }
        }
        return 1 + count;
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
