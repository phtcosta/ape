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

import com.android.commands.monkey.ape.Subsequence;

/**
 * X4, the choice among the paths to a trivial activity (action-selection, design D-B12).
 *
 * <p>{@code ApeTrivialPathPick} is upstream APE: {@code RandomHelper.randomPick}, one helper draw
 * when there is more than one path. {@code MopTrivialPathPick} takes the first path with the
 * strictly largest end-state MOP density and falls back to {@code randomPick} when the densities
 * are all equal. The {@code SataAgent} constructor mounts the MOP variant iff {@code MOP} is in the
 * plan.
 */
public interface TrivialPathPick {

    /**
     * Picks the path to follow.
     *
     * @param paths the candidate paths; never empty
     * @return the chosen path, one of {@code paths}
     */
    Subsequence pick(List<Subsequence> paths);
}
