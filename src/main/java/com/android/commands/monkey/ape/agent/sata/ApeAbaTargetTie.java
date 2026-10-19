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
 * X3, APE default (action-selection, design D-B12): on a tie between two ABA targets the incumbent
 * stays, as in upstream APE. Draws nothing.
 */
public final class ApeAbaTargetTie implements AbaTargetTie, NavVariant {

    @Override
    public boolean replaces(State incumbent, State candidate) {
        return false;
    }

    @Override
    public String variant() {
        return "ape";
    }

    @Override
    public boolean decidedByOwnRule() {
        return false;
    }
}
