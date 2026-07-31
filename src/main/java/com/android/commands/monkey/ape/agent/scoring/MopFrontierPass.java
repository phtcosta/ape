package com.android.commands.monkey.ape.agent.scoring;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopData;

/**
 * mop-reach-strategies Lever B (INV-MFP-01/02/03). Frontier boost toward unvisited MOP activities —
 * the strictly narrower sibling of {@link FrontierPass}. Rewards a target-requiring, valid, resolved
 * action whose matched WTG transition targets an activity that is BOTH unvisited
 * ({@code Graph.getActivityNode == null}) AND MOP-bearing ({@code MopData.activityHasMop == true}).
 * The generic frontier pass requires only unvisited; this one adds the MOP condition, so it is
 * independent of and additive to it.
 *
 * <p>The boost is {@code Config.mopFrontierWeight} (default {@code 0} → pass disabled, byte-identical
 * to absent). It is applied as a {@code setPriority} increment — the steering mechanism, since the
 * boost fields are telemetry-only and never enter {@code getPriority()} — and accumulated into its own
 * {@code mopFrontierBoost} field by read-modify-write, so a priority stacked with the WTG-MOP and
 * generic-frontier boosts stays decomposable by mechanism (INV-ARCH-10).
 *
 * <p>The MOP∩unvisited predicate ({@link #qualifyingMopTargets}) and the per-action write
 * ({@link #boostAction}) are both pure and unit-tested; what remains device-gated in {@link #apply}
 * is the resolved-node/graph plumbing between them, the same boundary as {@link FrontierPass#apply}.
 */
public final class MopFrontierPass implements ScoringPass {

    private final boolean enabled;

    public MopFrontierPass(ScoringContext ctx) {
        MopData mopData = ctx.getMopData();
        this.enabled = Config.mopFrontierWeight > 0 && mopData != null && mopData.hasWtgData();
    }

    @Override
    public String name() {
        return "MopFrontierPass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void apply(State state, ModelAction[] actions, ScoringContext ctx) {
        MopData mopData = ctx.getMopData();
        int timestamp = ctx.getTimestamp();
        String activity = state.getActivity();
        List<MopData.WtgTransition> transitions = mopData.getWtgTransitions(activity);
        // A transition target is visited when the graph already has its activity node.
        Set<String> visitedTargets = new HashSet<>();
        for (MopData.WtgTransition t : transitions) {
            if (ctx.getGraph().getActivityNode(t.targetActivity) != null) {
                visitedTargets.add(t.targetActivity);
            }
        }
        Set<String> qualifying = qualifyingMopTargets(transitions, mopData, visitedTargets);
        if (qualifying.isEmpty()) {
            return;
        }
        int weight = Config.mopFrontierWeight;
        int boostedCount = 0;
        for (ModelAction action : actions) {
            if (!action.requireTarget() || !action.isValid()) continue;
            if (!action.isResolvedAt(timestamp)) continue;
            GUITreeNode node = action.getResolvedNode();
            if (node == null) continue;
            String shortId = MopData.extractShortId(node.getResourceID());
            if (shortId == null || shortId.isEmpty()) continue;
            if (matchesQualifyingTarget(shortId, transitions, qualifying)) {
                boostAction(action, weight);
                boostedCount++;
            }
        }
        if (boostedCount > 0) {
            Logger.iformat("[APE-RV] MopFrontier boost: state=%s#%s, boosted=%d, weight=%d",
                    activity, state.getStateKey(), boostedCount, weight);
        }
    }

    /**
     * Apply this pass's contribution to one qualifying action. The {@code setPriority} increment is
     * what steers — {@code mopFrontierBoost} is never read by {@code getPriority()} — and the boost
     * field is the telemetry record of this pass's own contribution, accumulated read-modify-write
     * so several qualifying transitions on one action sum (INV-MFP-02).
     *
     * <p>It never writes {@code wtgBoost}. That is the whole of A6: {@code WtgPass} and the generic
     * {@code FrontierPass} write {@code wtgBoost}, and while this pass joined them there,
     * {@code decision_source=WTG} conflated a MOP mechanism with generic WTG navigation and a
     * stacked priority could not be decomposed by mechanism (INV-ARCH-10). Pure, so the disjointness
     * is unit-testable without a live graph.
     */
    static void boostAction(ModelAction action, int weight) {
        action.setPriority(action.getPriority() + weight);
        action.setMopFrontierBoost(action.getMopFrontierBoost() + weight);
    }

    /**
     * The MOP-frontier predicate (INV-MFP-01): the transition targets that are BOTH MOP-bearing
     * ({@code mopData.activityHasMop}) AND not in {@code visitedTargets}. Pure — no graph, no action —
     * so it is JVM-unit-testable; {@link #apply} supplies the visited set from the live graph.
     */
    static Set<String> qualifyingMopTargets(List<MopData.WtgTransition> transitions,
            MopData mopData, Set<String> visitedTargets) {
        Set<String> qualifying = new HashSet<>();
        for (MopData.WtgTransition t : transitions) {
            String target = t.targetActivity;
            if (mopData.activityHasMop(target) && !visitedTargets.contains(target)) {
                qualifying.add(target);
            }
        }
        return qualifying;
    }

    /** True when the widget's short id matches a WTG transition to a qualifying target. */
    private static boolean matchesQualifyingTarget(String shortId,
            List<MopData.WtgTransition> transitions, Set<String> qualifyingTargets) {
        for (MopData.WtgTransition t : transitions) {
            if (shortId.equals(t.widgetName) && qualifyingTargets.contains(t.targetActivity)) {
                return true;
            }
        }
        return false;
    }
}
