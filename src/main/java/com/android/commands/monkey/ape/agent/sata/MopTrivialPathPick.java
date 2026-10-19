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
import java.util.function.ToIntFunction;

import com.android.commands.monkey.ape.Subsequence;
import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.RandomHelper;

/**
 * X4, MOP variant (action-selection, mop-guidance "Navigation Tie-Breaks as MOP Variants", design
 * D-B12): the first path whose end state holds the strictly largest MOP density
 * ({@code MopScorer.stateMopDensity}), without a draw; {@link RandomHelper#randomPick}, as the APE
 * default does, when every end-state density is equal.
 *
 * <p>Mounted by the {@code SataAgent} composition root iff {@code MOP} is in the plan, which binds
 * the density to the run's MOP context at the step of the pick. When the density decided, the
 * INV-MOP-33 line {@code [APE-RV] Nav MOP tiebreak} is logged; it never alters the choice.
 */
public final class MopTrivialPathPick implements TrivialPathPick, NavVariant {

    private final ToIntFunction<State> density;
    private boolean lastByDensity;

    /** @param density a state's MOP density, read at the step of the pick; pure */
    public MopTrivialPathPick(ToIntFunction<State> density) {
        this.density = density;
    }

    @Override
    public Subsequence pick(List<Subsequence> paths) {
        lastByDensity = false;
        if (paths.size() <= 1) {
            return RandomHelper.randomPick(paths);
        }
        Subsequence best = null;
        int bestDensity = 0;
        boolean allEqual = true;
        for (Subsequence path : paths) {
            int d = density.applyAsInt(path.getLastState());
            if (best == null) {
                best = path;
                bestDensity = d;
                continue;
            }
            if (d != bestDensity) {
                allEqual = false;
            }
            if (d > bestDensity) {
                best = path;
                bestDensity = d;
            }
        }
        if (allEqual) {
            return RandomHelper.randomPick(paths);
        }
        lastByDensity = true;
        Logger.iprintln(SataAgent.navMopTiebreakLog(bestDensity, paths.size(), true));
        return best;
    }

    @Override
    public String variant() {
        return "mop";
    }

    /** Whether the last {@link #pick} chose by the strictly largest density, without a draw. */
    @Override
    public boolean decidedByOwnRule() {
        return lastByDensity;
    }
}
