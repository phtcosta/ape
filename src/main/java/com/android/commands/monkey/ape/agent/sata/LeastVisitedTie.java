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

/**
 * X1, the tie among the least-visited actions of a state (action-selection, design D-B12).
 *
 * <p>Upstream APE takes the first of the array. The fork's variant, {@code ScorerSumLeastVisitedTie}
 * (rule R2), takes the candidate with the larger sum of its scorer fields and otherwise keeps the
 * incumbent, which with zero scorers is exactly upstream's first of the array. X1 has no MOP
 * variant and no key.
 */
public interface LeastVisitedTie {

    /**
     * Picks one action among the least-visited candidates.
     *
     * @param leastVisited the candidates that share the lowest visit count, in
     *        {@code State.getActions()} order; never empty
     * @return the chosen action, one of {@code leastVisited}
     */
    ModelAction pick(List<ModelAction> leastVisited);
}
