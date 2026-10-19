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
package com.android.commands.monkey.ape.agent.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.StaticGuiModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The coin, the pool guard, and the cursor.
 *
 * <p>What this stage does is dispatch, and what it must get right is <em>when</em>. The coin is
 * {@code u_C}, drawn at step open from its own stream ({@link StepRouting#uC()}, INV-DP-13), so the
 * tests state it on the routing; the pool is the static model's manifest components, read without
 * any MOP data (design D-B13).
 *
 * <p>The second thing it must get right is that it never decides the step: the trigger is an effect
 * the pipeline records and passes over, and a {@code Select} here would cost the step its GUI action
 * (INV-DP-05).
 */
public class ComponentTriggerStageTest {

    /** Records which targets were fired, in order. */
    private static class Dispatches {

        private final List<Integer> fired = new ArrayList<>();

        void fire(int target) {
            fired.add(target);
        }
    }

    /** A static model carrying {@code receivers} receivers and no other component. */
    private static StaticGuiModel censusWith(int receivers) {
        List<ComponentInfo.ReceiverInfo> pool = new ArrayList<>();
        for (int i = 0; i < receivers; i++) {
            pool.add(new ComponentInfo.ReceiverInfo("com.example.app.R" + i, false,
                    Collections.<ComponentInfo.IntentFilter>emptyList(), null));
        }
        return new StaticGuiModel(null, null, null, null, pool, null, null, null);
    }

    /** A step on {@code staticModel}, the coin already drawn as {@code uC}. */
    private static FakeStepContext stepOver(StaticGuiModel staticModel, StepRouting routing,
            double uC) {
        FakeStepContext ctx = new FakeStepContext();
        ctx.staticModel = staticModel;
        routing.open(Double.NaN, Double.NaN, uC);
        return ctx;
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        assertEquals(DecisionPipeline.Candidate.COMPONENT_TRIGGER.stageName(),
                new ComponentTriggerStage(0.5d, new StepRouting(false, false, true), () -> 1,
                        target -> { }).name());
    }

    @Test
    public void testAWinningCoinFiresAndTheStepGoesOn() throws Exception {
        Dispatches dispatches = new Dispatches();
        StepRouting routing = new StepRouting(false, false, true);
        ComponentTriggerStage stage =
                new ComponentTriggerStage(0.5d, routing, () -> 3, dispatches::fire);

        StageResult result = stage.decide(stepOver(censusWith(3), routing, 0.4d));

        assertEquals("the trigger acts, it does not decide (INV-DP-05)",
                StageResult.Kind.SIDE_EFFECT, result.kind());
        assertTrue(result.description(), result.description().contains("target 0 of 3"));
        assertEquals(Arrays.asList(0), dispatches.fired);
    }

    @Test
    public void testALosingCoinPassesWithoutFiring() throws Exception {
        Dispatches dispatches = new Dispatches();
        StepRouting routing = new StepRouting(false, false, true);
        ComponentTriggerStage stage =
                new ComponentTriggerStage(0.5d, routing, () -> 3, dispatches::fire);

        // The coin is at the rate, and the comparison is strict: 0.5 is not below 0.5.
        StageResult result = stage.decide(stepOver(censusWith(3), routing, 0.5d));

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertTrue(dispatches.fired.isEmpty());
    }

    @Test
    public void testAnEmptyPoolPassesOnAWinningCoin() throws Exception {
        Dispatches dispatches = new Dispatches();
        StepRouting routing = new StepRouting(false, false, true);
        ComponentTriggerStage stage =
                new ComponentTriggerStage(0.5d, routing, () -> 3, dispatches::fire);

        StageResult result = stage.decide(stepOver(censusWith(0), routing, 0.1d));

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertTrue(dispatches.fired.isEmpty());
    }

    @Test
    public void testThePoolIsReadFromTheStaticModelWithoutMopData() throws Exception {
        Dispatches dispatches = new Dispatches();
        StepRouting routing = new StepRouting(false, false, true);
        ComponentTriggerStage stage =
                new ComponentTriggerStage(1.0d, routing, () -> 1, dispatches::fire);
        FakeStepContext ctx = stepOver(censusWith(1), routing, 0d);
        ctx.mopContext = null;
        ctx.mopData = null;

        StageResult result = stage.decide(ctx);

        assertEquals("the as cell has no MopData, and the trigger still fires (design D-B13)",
                StageResult.Kind.SIDE_EFFECT, result.kind());
        assertEquals(Arrays.asList(0), dispatches.fired);
    }

    @Test
    public void testTheCursorWalksTheTargetsRoundRobinAndWraps() throws Exception {
        Dispatches dispatches = new Dispatches();
        StepRouting routing = new StepRouting(false, false, true);
        ComponentTriggerStage stage =
                new ComponentTriggerStage(1.0d, routing, () -> 2, dispatches::fire);
        StaticGuiModel data = censusWith(2);

        stage.decide(stepOver(data, routing, 0d));
        stage.decide(stepOver(data, routing, 0d));
        stage.decide(stepOver(data, routing, 0d));

        assertEquals("the cursor persists across firings and wraps (INV-DP-07)",
                Arrays.asList(0, 1, 0), dispatches.fired);
    }

    @Test
    public void testAFiringWithNoTriggerableTargetLeavesTheCursorWhereItWas() throws Exception {
        Dispatches dispatches = new Dispatches();
        // Components are declared, but every one of them was filtered out — so the guard passes,
        // the coin is won, and there is still nothing to fire.
        final int[] targets = {0};
        StepRouting routing = new StepRouting(false, false, true);
        ComponentTriggerStage stage =
                new ComponentTriggerStage(1.0d, routing, () -> targets[0], dispatches::fire);
        StaticGuiModel data = censusWith(1);

        StageResult empty = stage.decide(stepOver(data, routing, 0d));
        targets[0] = 2;
        stage.decide(stepOver(data, routing, 0d));

        assertEquals("a winning coin is a side effect whatever the pool yields",
                StageResult.Kind.SIDE_EFFECT, empty.kind());
        assertEquals("no triggerable component target", empty.description());
        assertEquals("the cursor indexes targets, and there were none to index",
                Arrays.asList(0), dispatches.fired);
    }
}
