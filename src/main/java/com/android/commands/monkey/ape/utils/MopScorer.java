package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.runtime.MopScoring;
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
 *   nearest unretired target  → the context's level weight for d (distance mode, before the
 *                               tie split {@link #splitTies})
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
     * containment order. {@code index > 0} with a positive weight is a containment hit. The
     * retirement trigger counts the targets of the same lookup, so what gives an action its weight
     * is what retires.
     */
    public static final class Scored {

        /** The action's MOP weight before any tie split (the undivided weight); 0 when nothing is known. */
        public final int weight;
        /**
         * The position of the weight's lookup in {@link #containmentNodes} order: the first lookup
         * holding the best result (ties go to the earlier node) — under {@code flag} the largest
         * flag weight, 0 (the action's own node) when every lookup weighs 0; under
         * {@code distance} the smallest unretired distance, which a lookup holds even when it is
         * too far to weigh, 0 when no lookup has an unretired pair. -1 when the action has no
         * resolved node.
         */
        public final int index;
        /** The weight's lookup; {@link MopData.Lookup#NONE} when {@code index} is -1. */
        public final MopData.Lookup lookup;
        /**
         * Under {@code distance}, the smallest unretired distance over the containment lookups,
         * which the weight's lookup holds ({@code dec.mopd}, design D23); {@link Integer#MAX_VALUE}
         * when no lookup has an unretired pair, and always under {@code flag}, which reads no
         * distance.
         */
        public final int distance;

        Scored(int weight, int index, MopData.Lookup lookup) {
            this(weight, index, lookup, Integer.MAX_VALUE);
        }

        Scored(int weight, int index, MopData.Lookup lookup, int distance) {
            this.weight = weight;
            this.index = index;
            this.lookup = lookup;
            this.distance = distance;
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
        if (mop == null || action == null || action.getResolvedNode() == null) {
            return Scored.NOTHING;
        }
        return weigh(mop, state.getActivity(), action);
    }

    /**
     * {@link #weigh(MopContext, State, ModelAction)} for an action of {@code activity}: the state
     * contributes only its activity. For the MOP shortcut's ranking, which knows the activity and
     * not the state (design D21).
     *
     * @param mop the run's MOP context; null (no artifact) weighs 0
     */
    public static Scored weigh(MopContext mop, String activity, ModelAction action) {
        if (mop == null || action == null) {
            return Scored.NOTHING;
        }
        GUITreeNode node = action.getResolvedNode();
        if (node == null) {
            return Scored.NOTHING;
        }
        return best(mop, containmentLookups(mop.data(), activity, node, eventTypeOf(action)));
    }

    /**
     * The best of an action's lookups under the context's mode — the single place the scoring mode
     * decides a weight.
     *
     * <ul>
     *   <li>{@code flag}: each lookup weighs {@link #flagWeight}; the largest weight wins, ties
     *       going to the earlier lookup. On an unstamped tree every lookup is the id key of its node,
     *       so the result is the E6 mark; with the jar's weights (direct above transitive) the
     *       largest weight is the strongest flag.
     *   <li>{@code distance}: each lookup holds the smallest distance over its pairs whose target
     *       the context's retirement has not retired ({@link MopRetirement#nearest}); the lookup
     *       holding the smallest one wins, ties going to the earlier lookup, and the weight is that
     *       distance's level ({@link #distanceWeight}). Flags are not read.
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
            case DISTANCE: {
                MopRetirement retirement = mop.retirement();
                int bestIndex = 0;
                int bestDistance = Integer.MAX_VALUE;
                for (int i = 0; i < lookups.size(); i++) {
                    int d = MopRetirement.nearest(retirement, lookups.get(i).dist,
                            Integer.MAX_VALUE);
                    if (d < bestDistance) {
                        bestDistance = d;
                        bestIndex = i;
                    }
                }
                return new Scored(distanceWeight(mop, bestDistance), bestIndex,
                        lookups.get(bestIndex), bestDistance);
            }
            default:
                throw new IllegalStateException("unknown MOP scoring mode " + mop.scoring());
        }
    }

    /**
     * The {@code distance} weight of a nearest unretired target at distance {@code d} (design D18):
     * {@code weightD1} for {@code d <= 1}, {@code weightD2} for {@code d = 2}, {@code weightD3} for
     * {@code d = 3}, 0 from {@code d = 4} on and for {@link Integer#MAX_VALUE} (no pair). The one
     * mapping from a distance to a weight; the tie split's levels are read off it.
     */
    public static int distanceWeight(MopContext mop, int d) {
        if (d <= 1) {
            return mop.weightD1();
        }
        if (d == 2) {
            return mop.weightD2();
        }
        if (d == 3) {
            return mop.weightD3();
        }
        return 0;
    }

    /**
     * The tie split of the {@code distance} mode (design D18, mop-guidance "MopScorer — Priority
     * Boost"): given the undivided weights of a state's scored actions, the boosts they receive.
     *
     * <p>Ties are by weight level: the tied actions are those whose undivided weight equals the
     * state's best level, the largest positive weight. When there are {@code n > 1} of them, each
     * receives {@code max(W / n, W_next)}, with {@code W} that weight, integer division, and
     * {@code W_next} the weight of the next level ({@code weightD2} for the {@code d <= 1} level,
     * {@code weightD3} for {@code d = 2}, 0 for {@code d = 3}). Every other action keeps its
     * undivided weight. Under {@code flag} nothing is split. The one tie split; {@code
     * MopWidgetPass} applies it.
     *
     * @return a new array, index-aligned with {@code weights}
     */
    public static int[] splitTies(MopContext mop, int[] weights) {
        int[] boosts = weights.clone();
        if (mop.scoring() != MopScoring.DISTANCE) {
            return boosts;
        }
        int best = 0;
        for (int w : weights) {
            best = Math.max(best, w);
        }
        if (best <= 0) {
            return boosts;
        }
        int tied = 0;
        for (int w : weights) {
            if (w == best) {
                tied++;
            }
        }
        if (tied <= 1) {
            return boosts;
        }
        int share = Math.max(best / tied, nextLevelWeight(mop, best));
        for (int k = 0; k < boosts.length; k++) {
            if (weights[k] == best) {
                boosts[k] = share;
            }
        }
        return boosts;
    }

    /**
     * The weight of the level after the one weighing {@code levelWeight}, read off
     * {@link #distanceWeight}: the levels are {@code d <= 1}, {@code d = 2} and {@code d = 3}, and
     * the level after {@code d = 3} weighs 0.
     */
    private static int nextLevelWeight(MopContext mop, int levelWeight) {
        for (int d = 1; d <= 3; d++) {
            if (distanceWeight(mop, d) == levelWeight) {
                return distanceWeight(mop, d + 1);
            }
        }
        return 0;
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
        List<StaticGuiModel.WtgTransition> transitions = data.getWtgTransitions(activity);
        for (StaticGuiModel.WtgTransition t : transitions) {
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
     * containment; design D17) counts toward density under the context's mode: under
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
                // A positive weight under the active mode (design D17), from the one mode hook.
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
        } catch (RuntimeException e) {
            return null;
        }
    }
}
