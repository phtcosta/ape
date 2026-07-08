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
 * to absent). It is applied as a {@code setPriority} increment — the steering mechanism, since
 * {@code wtgBoost} is telemetry-only and never enters {@code getPriority()} — and accumulated into the
 * {@code wtgBoost} field by read-modify-write, so it stacks on top of the WTG-MOP and generic-frontier
 * boosts rather than overwriting them.
 *
 * <p>The MOP∩unvisited predicate is the pure, unit-tested novelty ({@link #qualifyingMopTargets}); the
 * per-action write in {@link #apply} runs on a resolved {@code GUITreeNode}/{@code ModelAction} and is
 * exercised on device, the same boundary as {@link FrontierPass#apply}.
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
                action.setPriority(action.getPriority() + weight);
                action.setWtgBoost(action.getWtgBoost() + weight);
                boostedCount++;
            }
        }
        if (boostedCount > 0) {
            Logger.iformat("[APE-RV] MopFrontier boost: state=%s#%s, boosted=%d, weight=%d",
                    activity, state.getStateKey(), boostedCount, weight);
        }
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
