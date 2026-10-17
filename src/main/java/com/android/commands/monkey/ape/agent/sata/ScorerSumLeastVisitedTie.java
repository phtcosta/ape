/*
 * Copyright 2020 Advanced Software Technologies Lab at ETH Zurich, Switzerland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.commands.monkey.ape.agent.sata;

import java.util.List;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.ScorerCounterfactual.Scorer;

/**
 * X1 rule R2 (action-selection, design D-B4, D-B12): among the least-visited actions, the one whose
 * scorer sum is strictly the largest; on equal sums the first in array order.
 *
 * <p>The scorer sum is the sum of the fields the mounted scoring passes wrote ({@code mop},
 * {@code mopf}, {@code wtg}, {@code navf}, {@code cov}, {@code menu}, {@code form}). Priority is not
 * read: it carries APE's base priority per action type, which differs between actions no scorer
 * touched. With zero scorers mounted every sum is 0, so the pick is upstream APE's first of the
 * array. Draws nothing; stateless, so one instance serves every cell.
 */
public final class ScorerSumLeastVisitedTie implements LeastVisitedTie {

    @Override
    public ModelAction pick(List<ModelAction> leastVisited) {
        return pickWithout(leastVisited);
    }

    /**
     * R2 with the given scorers' fields left out of every sum: the factual pick when none is given,
     * and the X1 counterfactual of {@code dec.cfc} otherwise. Deterministic and pure.
     *
     * @return the chosen action, or null when {@code leastVisited} is empty
     */
    public ModelAction pickWithout(List<ModelAction> leastVisited, Scorer... zeroed) {
        ModelAction best = null;
        long bestSum = Long.MIN_VALUE;
        for (ModelAction action : leastVisited) {
            long sum = scorerSum(action, zeroed);
            if (best == null || sum > bestSum) {
                best = action;
                bestSum = sum;
            }
        }
        return best;
    }

    /** The sum of {@code action}'s scorer fields, without the {@code zeroed} ones. */
    public static long scorerSum(ModelAction action, Scorer... zeroed) {
        long sum = 0;
        outer:
        for (Scorer scorer : Scorer.values()) {
            for (Scorer z : zeroed) {
                if (z == scorer) {
                    continue outer;
                }
            }
            sum += scorer.of(action);
        }
        return sum;
    }
}
