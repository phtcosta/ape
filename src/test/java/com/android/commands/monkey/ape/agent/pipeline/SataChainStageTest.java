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
import java.util.List;

import org.junit.Test;

import com.android.commands.monkey.ape.BadStateException;
import com.android.commands.monkey.ape.agent.SataAgent.SataEventType;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The order of the seven rungs, and what happens when none of them answers.
 *
 * <p>The order is the whole content of this stage — the strategies themselves live on the agent —
 * so it is asserted rung by rung: each test lets exactly one rung answer and checks that it decided
 * the step under its own event label. A table that transposed two entries would still decide every
 * step, with a different policy, which is why "it returned something" is not the assertion.
 *
 * <p>The seven rungs are stubs here. That is the point of the collaborator seam: the chain's order
 * is assertable without an agent, a graph or a device, none of which the JVM suite has.
 */
public class SataChainStageTest {

    /** The rung labels, in the order the chain walks them. */
    private static final SataEventType[] EXPECTED_ORDER = {
            SataEventType.USE_BUFFER,
            SataEventType.TRIVIAL_ACTIVITY,
            SataEventType.EARLY_STAGE,
            SataEventType.TRIVIAL_ACTIVITY,
            SataEventType.EARLY_STAGE,
            SataEventType.EPSILON_GREEDY,
            SataEventType.NULL,
    };

    /** What the agent's log-and-attribute call was told, in order. */
    private static class Log {

        private final List<SataEventType> events = new ArrayList<>();
        private final List<Action> actions = new ArrayList<>();

        void record(Action action, SataEventType event) {
            actions.add(action);
            events.add(event);
        }
    }

    private static ModelAction action() {
        return new ModelAction(null, ActionType.MODEL_CLICK);
    }

    /**
     * A chain whose rung {@code answering} returns {@code answer} and whose other rungs decline.
     * {@code asked} records which rungs were consulted, so a test can assert what was <em>not</em>
     * reached as well as what decided.
     */
    private static SataChainStage chainAnsweringAt(int answering, ModelAction answer,
            List<Integer> asked, Log log) {
        List<java.util.function.Supplier<ModelAction>> rungs = new ArrayList<>();
        for (int i = 0; i < EXPECTED_ORDER.length; i++) {
            final int index = i;
            rungs.add(() -> {
                asked.add(index);
                return index == answering ? answer : null;
            });
        }
        return new SataChainStage(log::record, rungs.get(0), rungs.get(1), rungs.get(2),
                rungs.get(3), rungs.get(4), rungs.get(5), rungs.get(6));
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        assertEquals(DecisionPipeline.Candidate.SATA_CHAIN.stageName(),
                chainAnsweringAt(0, action(), new ArrayList<>(), new Log()).name());
    }

    @Test
    public void testEachRungDecidesUnderItsOwnLabelAndInItsOwnPosition() {
        for (int rung = 0; rung < EXPECTED_ORDER.length; rung++) {
            ModelAction answer = action();
            List<Integer> asked = new ArrayList<>();
            Log log = new Log();

            StageResult result = chainAnsweringAt(rung, answer, asked, log).decide(null);

            assertEquals(StageResult.Kind.SELECT, result.kind());
            assertSame("rung " + rung + " decided the step", answer, result.action());
            assertEquals("rung " + rung + "'s label", Arrays.asList(EXPECTED_ORDER[rung]),
                    log.events);
            List<Integer> upToAndIncluding = new ArrayList<>();
            for (int i = 0; i <= rung; i++) {
                upToAndIncluding.add(i);
            }
            assertEquals("the chain stops at the rung that answers, in order",
                    upToAndIncluding, asked);
        }
    }

    @Test
    public void testAnExhaustedChainThrows() {
        List<Integer> asked = new ArrayList<>();
        // -1: no rung answers, which the agent's own last rung never does — it selects or throws.
        SataChainStage stage = chainAnsweringAt(-1, null, asked, new Log());

        try {
            stage.decide(null);
            fail("the terminal stage must never pass (INV-DP-06)");
        } catch (BadStateException expected) {
            assertEquals("No available action on the current state", expected.getMessage());
        }
        assertEquals("every rung was tried first", 7, asked.size());
    }

    @Test
    public void testTheLabelIsReadAfterTheAttributionWriteNotBefore() {
        ModelAction answer = action();
        answer.setDecisionSource(ModelAction.DecisionSource.MOP);
        Log log = new Log();
        // The agent's logActionSelected attributes the action as it logs it; a stage that captured
        // the label first would report the stale source telemetry will not print (INV-DP-04).
        SataChainStage stage = chainAnsweringAt(0, answer, new ArrayList<>(),
                new Log() {
                    @Override
                    void record(Action action, SataEventType event) {
                        ((ModelAction) action).setDecisionSource(ModelAction.DecisionSource.SATA);
                        log.record(action, event);
                    }
                });

        StageResult result = stage.decide(null);

        assertEquals(ModelAction.DecisionSource.SATA.name(), result.decisionSource());
        assertTrue("the log ran", log.events.size() == 1);
    }
}
