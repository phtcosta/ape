package com.android.commands.monkey.ape.model;

import java.util.List;


/**
 * A3 per-step counterfactual (action-selection INV-SEL-08/09): what each MOP-sensitive pick site
 * would have selected with the MOP boosts zeroed on every candidate, the candidate set and all
 * other boosts unchanged.
 *
 * <p>Every method here is pure and draws nothing from the seeded RNG stream. That is the hard
 * constraint (INV-EXPL-14): the selected-action sequence of a seeded run must be identical whether
 * or not the counterfactual is computed, so a counterfactual roulette can never call
 * {@code nextInt} of its own. It replays the factual pick's draw instead, as a fraction of total
 * weight — the common-random-numbers contrast, "same random point, different weights". The two
 * short-circuit sites made no draw at all, so their counterfactuals are deterministic stand-ins for
 * the channel they pre-empted.
 *
 * <p>Interpretation boundary, part of the contract: this is 1-step myopic. It establishes the
 * divergence point — did the MOP boost change <em>this</em> pick — and says nothing about the
 * cumulative trajectory, which only an arm-level contrast can measure.
 */
public final class MopCounterfactual {

    private MopCounterfactual() {
    }

    /**
     * The action's priority with its MOP contributions removed. Both MOP passes steer through
     * {@code setPriority} increments and record what they added, so subtracting the two boost
     * fields reconstructs the priority the action would carry in a MOP-free world. Clamped at 0:
     * a negative weight is not a meaningful roulette segment.
     */
    public static int priorityWithoutMopWeights(ModelAction action) {
        int priority = action.getPriority() - action.getMopBoost() - action.getMopFrontierBoost();
        return Math.max(0, priority);
    }

    /**
     * Replay a roulette pick over the same candidates with MOP weights zeroed, consuming no draw.
     *
     * <p>The factual pick consumed {@code drawIndex} out of {@code factualTotal} weight. The same
     * random point expressed as a fraction of the counterfactual total, {@code drawIndex/factualTotal
     * × cfTotal}, is where the counterfactual roulette lands — so the contrast isolates the weights
     * and never the randomness. The walk mirrors {@code State.pickAction}: candidates in order,
     * first segment whose accumulated weight passes the index.
     *
     * @return the counterfactual pick, or null when nothing can be picked without MOP weight
     */
    public static ModelAction replayRoulette(List<ModelAction> candidates, int drawIndex,
                                             int factualTotal) {
        if (candidates == null || candidates.isEmpty() || factualTotal <= 0 || drawIndex < 0) {
            return null;
        }
        long cfTotal = 0;
        for (ModelAction candidate : candidates) {
            cfTotal += priorityWithoutMopWeights(candidate);
        }
        if (cfTotal <= 0) {
            return null;
        }
        long cfIndex = (long) drawIndex * cfTotal / factualTotal;
        long accumulated = 0;
        for (ModelAction candidate : candidates) {
            accumulated += priorityWithoutMopWeights(candidate);
            if (accumulated > cfIndex) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /**
     * The least-visited pick with MOP weights zeroed — the counterfactual of the unvisited-MOP
     * short-circuit, which pre-empts exactly this branch of the epsilon-greedy fall-through. Same
     * rule as {@code State.greedyPickLeastVisited}: fewer visits always wins, a tie is broken by
     * higher priority when {@code priorityTiebreak} is on, otherwise by candidate order. The flag
     * is an argument for the same reason it is one there — a counterfactual that read the gate off
     * a static while the factual pick took it as a parameter could disagree with the branch it is
     * supposed to be the counterfactual of.
     */
    public static ModelAction leastVisitedWithoutMopWeights(List<ModelAction> candidates,
            boolean priorityTiebreak) {
        if (candidates == null) {
            return null;
        }
        ModelAction best = null;
        int minVisits = Integer.MAX_VALUE;
        int maxPriority = Integer.MIN_VALUE;
        for (ModelAction candidate : candidates) {
            int priority = priorityWithoutMopWeights(candidate);
            if (State.beatsLeastVisited(candidate.getVisitedCount(), priority, minVisits, maxPriority,
                    priorityTiebreak)) {
                minVisits = candidate.getVisitedCount();
                maxPriority = priority;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * The largest-weight candidate with MOP weights zeroed — the counterfactual of the 0-step
     * short-circuit, whose fall-through is a priority roulette that made no draw in the factual
     * step. Drawing one here would perturb the seeded stream, so the roulette's modal outcome
     * stands in for it: the candidate the MOP-free weighting favours most, ties by candidate order.
     * This is a deterministic stand-in for a probabilistic channel, and it is why {@code cf_changed}
     * from this site reads as "the boost moved the pick away from the strongest MOP-free candidate".
     */
    public static ModelAction highestPriorityWithoutMopWeights(List<ModelAction> candidates) {
        if (candidates == null) {
            return null;
        }
        ModelAction best = null;
        int bestPriority = Integer.MIN_VALUE;
        for (ModelAction candidate : candidates) {
            int priority = priorityWithoutMopWeights(candidate);
            if (priority > bestPriority) {
                bestPriority = priority;
                best = candidate;
            }
        }
        return best;
    }
}
