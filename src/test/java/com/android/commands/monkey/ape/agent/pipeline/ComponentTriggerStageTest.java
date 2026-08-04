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
import java.util.Random;

import org.junit.Test;

import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.MopData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The coin, the guard, and the cursor.
 *
 * <p>What this stage does is dispatch, and what it must get right is <em>when</em>. The draw is the
 * delicate part: it comes from the run's seeded stream, shared with every later decision, so a coin
 * flipped on a step that should not have flipped one shifts the whole rest of the run (INV-DP-10).
 * That is asserted here by counting draws against a stream whose values the test dictates, rather
 * than by observing an outcome that a wrongly-ordered draw could still produce by chance.
 *
 * <p>The second thing it must get right is that it never decides the step: the trigger is an effect
 * the pipeline records and passes over, and a {@code Select} here would cost the step its GUI action
 * (INV-DP-05).
 */
public class ComponentTriggerStageTest {

    /** A stream that hands out the values the test names, counting what was taken. */
    private static class ScriptedRandom extends Random {

        private static final long serialVersionUID = 1L;

        private final double[] values;
        private int draws;

        ScriptedRandom(double... values) {
            this.values = values;
        }

        @Override
        public double nextDouble() {
            return values[draws++];
        }
    }

    /** Records which targets were fired, in order. */
    private static class Dispatches {

        private final List<Integer> fired = new ArrayList<>();

        void fire(int target) {
            fired.add(target);
        }
    }

    /**
     * MopData carrying a component list, or none. Allocated rather than parsed: the loader wants a
     * JSON artifact, and {@code hasComponents()} reads exactly the four lists set below.
     */
    private static MopData censusWith(int receivers) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        MopData data = (MopData) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), MopData.class);
        List<ComponentInfo.ReceiverInfo> pool = new ArrayList<>();
        for (int i = 0; i < receivers; i++) {
            pool.add(new ComponentInfo.ReceiverInfo("com.example.app.R" + i, false, true,
                    Collections.<ComponentInfo.IntentFilter>emptyList(), true,
                    Collections.<String>emptyList(), null));
        }
        FakeStepContext.setField(data, "receivers", pool);
        FakeStepContext.setField(data, "services", new ArrayList<ComponentInfo.ServiceInfo>());
        FakeStepContext.setField(data, "activities", new ArrayList<ComponentInfo.ActivityInfo>());
        FakeStepContext.setField(data, "providers", new ArrayList<ComponentInfo.ProviderInfo>());
        return data;
    }

    private static FakeStepContext stepOver(MopData data, Random random) {
        FakeStepContext ctx = new FakeStepContext();
        ctx.mopData = data;
        ctx.random = random;
        return ctx;
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        assertEquals(DecisionPipeline.Candidate.COMPONENT_TRIGGER.stageName(),
                new ComponentTriggerStage(0.5d, () -> 1, target -> { }).name());
    }

    @Test
    public void testAWinningCoinFiresAndTheStepGoesOn() throws Exception {
        Dispatches dispatches = new Dispatches();
        ComponentTriggerStage stage =
                new ComponentTriggerStage(0.5d, () -> 3, dispatches::fire);

        StageResult result = stage.decide(stepOver(censusWith(3), new ScriptedRandom(0.4d)));

        assertEquals("the trigger acts, it does not decide (INV-DP-05)",
                StageResult.Kind.SIDE_EFFECT, result.kind());
        assertTrue(result.description(), result.description().contains("target 0 of 3"));
        assertEquals(Arrays.asList(0), dispatches.fired);
    }

    @Test
    public void testALosingCoinPassesWithoutFiring() throws Exception {
        Dispatches dispatches = new Dispatches();
        ComponentTriggerStage stage =
                new ComponentTriggerStage(0.5d, () -> 3, dispatches::fire);

        // The draw is at the rate, and the comparison is strict: 0.5 is not below 0.5.
        StageResult result = stage.decide(stepOver(censusWith(3), new ScriptedRandom(0.5d)));

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertTrue(dispatches.fired.isEmpty());
    }

    @Test
    public void testAnEmptyCensusConsumesNoDraw() throws Exception {
        ScriptedRandom random = new ScriptedRandom(0.1d);
        ComponentTriggerStage stage = new ComponentTriggerStage(0.5d, () -> 3, target -> { });

        StageResult result = stage.decide(stepOver(censusWith(0), random));

        // The original conjunction short-circuited before the coin, and the seeded stream is shared
        // with every later decision: a draw taken here would move the whole rest of the run.
        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("the data guard runs before the coin (INV-DP-10)", 0, random.draws);
    }

    @Test
    public void testTheCursorWalksTheTargetsRoundRobinAndWraps() throws Exception {
        Dispatches dispatches = new Dispatches();
        ComponentTriggerStage stage =
                new ComponentTriggerStage(1.0d, () -> 2, dispatches::fire);
        FakeStepContext ctx = stepOver(censusWith(2), new ScriptedRandom(0d, 0d, 0d));

        stage.decide(ctx);
        stage.decide(ctx);
        stage.decide(ctx);

        assertEquals("the cursor persists across firings and wraps (INV-DP-07)",
                Arrays.asList(0, 1, 0), dispatches.fired);
    }

    @Test
    public void testAFiringWithNoTriggerableTargetLeavesTheCursorWhereItWas() throws Exception {
        Dispatches dispatches = new Dispatches();
        // Components are declared, but every one of them was filtered out as unreachable — so the
        // guard passes, the coin is spent, and there is still nothing to fire.
        final int[] targets = {0};
        ComponentTriggerStage stage =
                new ComponentTriggerStage(1.0d, () -> targets[0], dispatches::fire);
        FakeStepContext ctx = stepOver(censusWith(1), new ScriptedRandom(0d, 0d));

        StageResult empty = stage.decide(ctx);
        targets[0] = 2;
        stage.decide(ctx);

        assertEquals("a winning coin is a side effect whatever the pool yields",
                StageResult.Kind.SIDE_EFFECT, empty.kind());
        assertEquals("no triggerable component target", empty.description());
        assertEquals("the cursor indexes targets, and there were none to index",
                Arrays.asList(0), dispatches.fired);
    }
}
