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

/**
 * What {@code dec.nav} reads off a mounted X3 or X4 strategy (event-sink, design D-B14.8).
 *
 * <p>Every X3/X4 implementation is one: {@code c} is the mounted variant, and {@code alt} is read
 * off that variant's own last decision, so no unmounted variant is ever evaluated to compute it.
 * Pure: answering consumes no draw and resolves nothing.
 */
public interface NavVariant {

    /** {@code ape} for the APE default, {@code mop} for the MOP variant: {@code dec.nav.c}. */
    String variant();

    /**
     * Whether the last decision was made by the MOP variant's own rule ({@code dec.nav.alt}): X3
     * replaced the incumbent by density, X4 chose the path by the strictly largest density without a
     * draw. Always false for an APE default.
     */
    boolean decidedByOwnRule();
}
