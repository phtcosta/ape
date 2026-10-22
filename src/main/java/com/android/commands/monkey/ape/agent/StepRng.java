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
package com.android.commands.monkey.ape.agent;

import java.util.Random;

/**
 * The four per-step random streams of a run, each its own {@link Random} derived from the run seed
 * (decision-pipeline, design D-B9; run-spec INV-RUN-08).
 *
 * <p>The LLM coin {@code u_L}, the MOP coin {@code u_M}, the component coin and the fuzz toss each
 * draw from a stream no other decision reads. Mounting, unmounting or re-dosing one of them
 * therefore never shifts SATA's draws on the agent and helper streams (INV-DP-13), which is what
 * lets two cells be compared step by step.
 *
 * <p>Stream {@code k} is {@code new Random(seed ^ K)} for a fixed odd constant per stream, so the
 * streams are reproducible per seed and distinct from each other and from the agent stream
 * ({@code new Random(seed)}).
 */
public final class StepRng {

    private static final long LLM_COIN = 0x9E3779B97F4A7C15L;
    private static final long MOP_COIN = 0xC2B2AE3D27D4EB4FL;
    private static final long COMPONENT_COIN = 0x165667B19E3779F9L;
    private static final long FUZZ = 0xD6E8FEB86659FD93L;

    private final Random llmCoin;
    private final Random mopCoin;
    private final Random componentCoin;
    private final Random fuzz;

    /**
     * @param seed the run seed, the value the agent's own generator was built from
     */
    public StepRng(long seed) {
        this.llmCoin = new Random(seed ^ LLM_COIN);
        this.mopCoin = new Random(seed ^ MOP_COIN);
        this.componentCoin = new Random(seed ^ COMPONENT_COIN);
        this.fuzz = new Random(seed ^ FUZZ);
    }

    /** The next {@code u_L}, uniform in {@code [0, 1)}. */
    public double llmCoin() {
        return llmCoin.nextDouble();
    }

    /** The next {@code u_M}, uniform in {@code [0, 1)}. */
    public double mopCoin() {
        return mopCoin.nextDouble();
    }

    /** The next component-coin draw, uniform in {@code [0, 1)}. */
    public double componentCoin() {
        return componentCoin.nextDouble();
    }

    /**
     * The next fuzz toss: whether this step fuzzes, at probability {@code rate}. Draws on every
     * call, whatever the rate, so the stream's position counts steps rather than fuzzing steps.
     *
     * <p>The stream serves the toss only on a plan with {@code APE_FIXES}. Without it the toss in
     * {@code MonkeySourceApe} is {@code RandomHelper.toss(rate)}, drawn only when fuzzing is on, and
     * this stream is not read.
     *
     * @param rate the fuzzing probability
     */
    public boolean fuzzToss(double rate) {
        return fuzz.nextDouble() < rate;
    }
}
