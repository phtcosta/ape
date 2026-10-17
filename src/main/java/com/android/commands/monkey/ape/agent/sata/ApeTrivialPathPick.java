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
import com.android.commands.monkey.ape.utils.RandomHelper;

/**
 * X4, APE default (action-selection, design D-B12): the path to a trivial activity is
 * {@link RandomHelper#randomPick}, as in upstream APE ({@code SataAgent} {@code 8f51b99c}
 * {@code :755-757}). One helper-stream draw when there is more than one path; a single path is
 * still handed to {@code randomPick}, which takes it without drawing.
 */
public final class ApeTrivialPathPick implements TrivialPathPick, NavVariant {

    @Override
    public Subsequence pick(List<Subsequence> paths) {
        return RandomHelper.randomPick(paths);
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
