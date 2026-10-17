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

import java.util.function.ToIntFunction;

import com.android.commands.monkey.ape.model.State;

/**
 * X3, MOP variant (action-selection, mop-guidance "Navigation Tie-Breaks as MOP Variants", design
 * D-B12, D-B14.18): on a tie between two ABA targets the candidate replaces the incumbent when its
 * MOP density is strictly larger. The density order ({@code MopScorer.stateMopDensity}) puts a
 * state of a MOP activity above one outside the set, and also breaks a same-activity tie. Draws
 * nothing.
 *
 * <p>Mounted by the {@code SataAgent} composition root iff {@code MOP} is in the plan, which binds
 * the density to the run's MOP context at the step the tie happens in.
 */
public final class MopAbaTargetTie implements AbaTargetTie, NavVariant {

    private final ToIntFunction<State> density;
    private boolean lastReplaced;

    /** @param density a state's MOP density, read at the step of the tie; pure */
    public MopAbaTargetTie(ToIntFunction<State> density) {
        this.density = density;
    }

    @Override
    public boolean replaces(State incumbent, State candidate) {
        lastReplaced = density.applyAsInt(candidate) > density.applyAsInt(incumbent);
        return lastReplaced;
    }

    @Override
    public String variant() {
        return "mop";
    }

    /** Whether the last {@link #replaces} call replaced the incumbent. */
    @Override
    public boolean decidedByOwnRule() {
        return lastReplaced;
    }
}
