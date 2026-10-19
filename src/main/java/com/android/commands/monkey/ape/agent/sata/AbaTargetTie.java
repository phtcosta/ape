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

import com.android.commands.monkey.ape.model.State;

/**
 * X3, the tie between two target states of the early-stage ABA navigation (action-selection,
 * design D-B12).
 *
 * <p>{@code ApeAbaTargetTie} is upstream APE: it never replaces the incumbent on a tie and draws
 * nothing. {@code MopAbaTargetTie} replaces it when the candidate's activity is in the MOP set and
 * the incumbent's is not. The {@code SataAgent} constructor mounts the MOP variant iff {@code MOP}
 * is in the plan.
 */
public interface AbaTargetTie {

    /**
     * Whether {@code candidate} replaces {@code incumbent} as the ABA target, on a tie of the
     * ordinary APE criterion.
     *
     * @param incumbent the target chosen so far
     * @param candidate the target that tied with it
     */
    boolean replaces(State incumbent, State candidate);
}
