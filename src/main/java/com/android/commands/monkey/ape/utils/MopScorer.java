package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Maps MOP reachability to integer priority boosts for action scoring.
 *
 * <p>Every weight arrives from the caller (INV-ARCH-11): the widget weights through the run's
 * {@link MopContext}, the gateway and WTG weights as parameters read off the run's
 * {@code ScoringParams}. This class resolves nodes and transitions; it does not decide what a
 * match is worth:
 *   direct (flag mode)        → the context's direct weight
 *   transitive only           → the context's transitive weight
 *   OPTIONSMENU gateway       → the caller's open-menu weight
 *   no match                  → 0 (discriminative-only; no activity-level fallback)
 *
 * <p>Widget reachability is read only through {@link MopData#lookup} (INV-MOP-36), over the
 * containment node walk ({@link #containmentNodes}).
 */
public class MopScorer {

    /**
     * What the MOP-widget pass learns about one action: its weight, and the containment lookup the
     * weight comes from (the <em>weight's lookup</em>, design D19), with its position in
     * containment order. {@code index > 0} with a positive weight is a containment hit.
     */
    public static final class Scored {

        /** The action's MOP weight before any tie split; 0 when nothing is known. */
        public final int weight;
        /**
         * The position of the weight's lookup in {@link #containmentNodes} order: the first lookup
         * holding the best result (ties go to the earlier node); 0, the action's own node, when the
         * weight is 0; -1 when the action has no resolved node.
         */
        public final int index;
        /** The weight's lookup; {@link MopData.Lookup#NONE} when {@code index} is -1. */
        public final MopData.Lookup lookup;

        Scored(int weight, int index, MopData.Lookup lookup) {
            this.weight = weight;
            this.index = index;
            this.lookup = lookup;
        }

        static final Scored NOTHING = new Scored(0, -1, MopData.Lookup.NONE);
    }

    /**
     * Priority boost for one valid, target-requiring action (mop-guidance "MopScorer — Priority
     * Boost", INV-MOP-02): {@link #weigh}'s weight.
     *
     * @param mop the run's MOP context; null (no artifact) scores 0
     */
    public static int score(MopContext mop, State state, ModelAction action) {
        return weigh(mop, state, action).weight;
    }

    /**
     * The action's weight and the lookup it comes from: {@link MopData#lookup} over the action's
     * containment nodes ({@link #containmentLookups}) for its event ({@link #eventTypeOf}), the best
     * result winning under the context's mode ({@link #best}). The scorer applies no activity-level
     * fallback: a resolved-but-unflagged widget, a listed stamp whose record is {@code none}, and
     * {@code NONE} everywhere all weigh 0, even on a MOP-bearing activity.
     *
     * @param mop the run's MOP context; null (no artifact) weighs 0
     */
    public static Scored weigh(MopContext mop, State state, ModelAction action) {
        if (mop == null || action == null) {
            return Scored.NOTHING;
        }
        GUITreeNode node = action.getResolvedNode();
        if (node == null) {
            return Scored.NOTHING;
        }
        return best(mop, containmentLookups(mop.data(), state.getActivity(), node,
                eventTypeOf(action)));
    }

    /**
     * The best of an action's lookups under the context's mode — the single place the scoring mode
     * decides a weight.
     *
     * <ul>
     *   <li>{@code flag}: each lookup weighs {@link #flagWeight}; the largest weight wins, ties
     *       going to the earlier lookup. That is exactly the maximum the containment loop took over
     *       its ids before the lookup existed, so an unstamped tree scores as E6 did; with the jar's
     *       weights (direct above transitive) the largest weight is the strongest flag.
     *   <li>{@code distance}: tasks 13.3/13.4.
     * </ul>
     */
    static Scored best(MopContext mop, List<MopData.Lookup> lookups) {
        if (lookups.isEmpty()) {
            return Scored.NOTHING;
        }
        switch (mop.scoring()) {
            case FLAG: {
                int bestIndex = 0;
                int bestWeight = flagWeight(mop, lookups.get(0));
                for (int i = 1; i < lookups.size(); i++) {
                    int w = flagWeight(mop, lookups.get(i));
                    if (w > bestWeight) {
                        bestWeight = w;
                        bestIndex = i;
                    }
                }
                return new Scored(bestWeight, bestIndex, lookups.get(bestIndex));
            }
            case DISTANCE:
                // Task 13.4 (design D18): the smallest unretired d over the lookups' pairs, its
                // level weight, ties going to the earlier lookup.
                throw new UnsupportedOperationException(
                        "task 13.4: the distance weight of the nearest unretired target (D18)");
            default:
                throw new IllegalStateException("unknown MOP scoring mode " + mop.scoring());
        }
    }

    /**
     * The {@code flag} weight of one lookup: {@code weightDirect} for a direct flag, else
     * {@code weightTransitive} for a transitive one, else 0.
     */
    static int flagWeight(MopContext mop, MopData.Lookup lookup) {
        if (lookup.direct) {
            return mop.weightDirect();
        }
        if (lookup.transitive) {
            return mop.weightTransitive();
        }
        return 0;
    }

    /**
     * The containment node walk (INV-MOP-23 as amended, design D17): the node itself, then its
     * ancestors up to two levels ({@code getParent}), then its descendants down to two levels (each
     * child followed by that child's children). MOP resolution looks up every node of it
     * ({@link #containmentLookups}); typed-input resolution reads the id key of every node of it
     * ({@code ApeAgent.generateInputText}). Both walk this list, so they cannot diverge in which
     * nodes they visit or in what order. Pure: no mutation, no logging.
     */
    public static List<GUITreeNode> containmentNodes(GUITreeNode node) {
        List<GUITreeNode> nodes = new ArrayList<>();
        nodes.add(node);
        // Ancestors, up to 2 levels up.
        GUITreeNode ancestor = node.getParent();
        for (int depth = 1; depth <= 2 && ancestor != null; depth++) {
            nodes.add(ancestor);
            ancestor = ancestor.getParent();
        }
        // Descendants, up to 2 levels down (each child, then its children).
        Iterator<GUITreeNode> children = node.getChildren();
        while (children.hasNext()) {
            GUITreeNode child = children.next();
            nodes.add(child);
            Iterator<GUITreeNode> grandchildren = child.getChildren();
            while (grandchildren.hasNext()) {
                nodes.add(grandchildren.next());
            }
        }
        return nodes;
    }

    /**
     * {@link MopData#lookup} of {@code eventType} on every node of {@link #containmentNodes}, in
     * that order: the containment lookups the scoring pass, the shortcut, the marker and the
     * retirement trigger choose among. Each node is looked up with its own stamp and its own id.
     */
    public static List<MopData.Lookup> containmentLookups(MopData data, String activity,
            GUITreeNode node, String eventType) {
        List<GUITreeNode> nodes = containmentNodes(node);
        List<MopData.Lookup> lookups = new ArrayList<>(nodes.size());
        for (GUITreeNode n : nodes) {
            lookups.add(data.lookup(activity, n, eventType));
        }
        return lookups;
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
     * resolution); otherwise {@code 1 + count}, where {@code count} counts the actions whose
     * resolved node's <em>own</em> lookup ({@link MopData#lookup} on the node alone, no
     * containment, as before; design D17) counts toward density under the context's mode: under
     * {@code flag}, a direct or transitive flag for the action's own event type; under
     * {@code distance}, a positive weight.
     *
     * <p>The {@code +1} floor lets an A′-rescued activity — MOP-bearing via component/lambda
     * reachability, carrying no widget-level flag — rank above a non-MOP activity ({@code 1 > 0})
     * in the SATA navigation tiebreaks, while any widget-flagged state still ranks strictly above
     * an activity-floor-only state ({@code 1 + count > 1} for {@code count ≥ 1}). It is a
     * cross-state ranking signal only; {@link #score} stays widget-discriminative (no per-candidate
     * activity fallback). {@code timestamp} selects the GUITree the actions were resolved against.
     * Pure: no mutation, no logging.
     *
     * @param mop the run's MOP context (returns 0 if null)
     */
    public static int stateMopDensity(State state, MopContext mop, int timestamp) {
        if (mop == null) {
            return 0;
        }
        MopData data = mop.data();
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
            if (countsTowardDensity(mop, data.lookup(activity, node, eventTypeOf(action)))) {
                count++;
            }
        }
        return 1 + count;
    }

    /** Whether an action's own-node lookup counts toward {@link #stateMopDensity}. */
    private static boolean countsTowardDensity(MopContext mop, MopData.Lookup own) {
        switch (mop.scoring()) {
            case FLAG:
                // Unchanged under flag: the flags decide, whatever the weights.
                return own.direct || own.transitive;
            case DISTANCE:
                // A positive weight under the active mode (design D17); the weight is best's,
                // whose distance case is task 13.4.
                return best(mop, Collections.singletonList(own)).weight > 0;
            default:
                throw new IllegalStateException("unknown MOP scoring mode " + mop.scoring());
        }
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
