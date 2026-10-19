package com.android.commands.monkey.ape.model;

import java.util.List;

/**
 * The counterfactual of one pick with one or more scorer fields zeroed (event-sink {@code dec.cfc},
 * design D-B14.18): would the pick have selected another action had a scorer contributed nothing?
 *
 * <p>Every scorer steers SATA through {@code setPriority} increments and records what it added in
 * its own {@link ModelAction} field, so subtracting a field reconstructs the priority the action
 * would carry without that scorer. The replay consumes no draw: a roulette counterfactual reuses
 * the factual draw index, rescaled to the counterfactual total, so the contrast isolates the
 * weights and never the randomness. Pure: no RNG, no {@code resolveAction}, no cache (INV-SNK-16).
 *
 * <p>Interpretation boundary, part of the contract: this is 1-step myopic. It establishes the
 * divergence point — did this scorer change <em>this</em> pick — and says nothing about the
 * cumulative trajectory, which only an arm-level contrast can measure.
 */
public final class ScorerCounterfactual {

    /** The scorer fields of a {@link ModelAction}, under their {@code dec.cfc} names. */
    public enum Scorer {
        MOP("mop"),
        MOP_FRONTIER("mopf"),
        WTG("wtg"),
        NAV_FRONTIER("navf"),
        COVERAGE("cov"),
        MENU("menu"),
        FORM("form");

        private final String wire;

        Scorer(String wire) {
            this.wire = wire;
        }

        /** The key in {@code dec.cfc}. */
        public String wire() {
            return wire;
        }

        /** What this scorer added to {@code action}'s priority in the current scoring pass. */
        public int of(ModelAction action) {
            switch (this) {
            case MOP:
                return action.getMopBoost();
            case MOP_FRONTIER:
                return action.getMopFrontierBoost();
            case WTG:
                return action.getWtgBoost();
            case NAV_FRONTIER:
                return action.getNavFrontierBoost();
            case COVERAGE:
                return action.getCoverageBoost();
            case MENU:
                return action.getMenuBoost();
            case FORM:
                return action.getFormBoost();
            default:
                throw new AssertionError(this);
            }
        }
    }

    private ScorerCounterfactual() {
    }

    /**
     * The action's priority with the given scorers' contributions removed, clamped at 0: a negative
     * weight is not a meaningful roulette segment.
     */
    public static int priorityWithout(ModelAction action, Scorer... zeroed) {
        int priority = action.getPriority();
        for (Scorer scorer : zeroed) {
            priority -= scorer.of(action);
        }
        return Math.max(0, priority);
    }

    /**
     * Replay a roulette pick over the same candidates with the given scorers zeroed, consuming no
     * draw.
     *
     * <p>The factual pick consumed {@code drawIndex} out of {@code factualTotal} weight. The same
     * random point expressed as a fraction of the counterfactual total, {@code drawIndex/factualTotal
     * × cfTotal}, is where the counterfactual roulette lands. The walk mirrors
     * {@code State.pickAction}: candidates in order, first segment whose accumulated weight passes
     * the index.
     *
     * @return the counterfactual pick, or null when nothing can be picked without those scorers
     */
    public static ModelAction replayRoulette(List<ModelAction> candidates, int drawIndex,
            int factualTotal, Scorer... zeroed) {
        if (candidates == null || candidates.isEmpty() || factualTotal <= 0 || drawIndex < 0) {
            return null;
        }
        long cfTotal = 0;
        for (ModelAction candidate : candidates) {
            cfTotal += priorityWithout(candidate, zeroed);
        }
        if (cfTotal <= 0) {
            return null;
        }
        long cfIndex = (long) drawIndex * cfTotal / factualTotal;
        long accumulated = 0;
        for (ModelAction candidate : candidates) {
            accumulated += priorityWithout(candidate, zeroed);
            if (accumulated > cfIndex) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }
}
