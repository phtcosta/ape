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
package com.android.commands.monkey.ape.telemetry;

import java.util.List;
import java.util.Map;

/**
 * The sink that records nothing, and the other half of the neutrality test.
 *
 * <p>It exists for one purpose and has exactly one legitimate caller: the parity harness, which
 * runs a preset under a fixed seed with {@link NdjsonSink} and then with this, and asserts the two
 * action sequences are identical (R7, INV-SNK-07). <b>No preset selects it.</b> Telemetry is
 * always-on and identical for every experimental arm — that is the point of dissolving INV-ARCH-01
 * — so a configuration that turned recording off would be the thing this class is used to prove
 * unnecessary.
 *
 * <p>It is not a fallback for a broken sink either: {@link NdjsonSink} handles its own failures by
 * latching off in place (INV-SNK-12), so nothing ever swaps one implementation for the other
 * mid-run.
 */
public final class NoopSink implements EventSink {

    public NoopSink() {
    }

    @Override
    public void beginStep(int step, long tRelMs, String activity, boolean activityHasMop,
            String stateKey) {
    }

    @Override
    public void decision(String action, String decisionSource, String pickChannel, int priority,
            int mop, int mopFrontier, int wtg, int coverage, int menu, int form,
            String wtgSource, int patched, int cfChanged, String cfAction) {
    }

    @Override
    public void decisionNonModel(String action, String decisionSource, String pickChannel) {
    }

    @Override
    public void mopExposure(int boosted, int total) {
    }

    @Override
    public void componentLaunch(int result, String error) {
    }

    @Override
    public void llmDump(String system, String user, String response, String toolCalls) {
    }

    @Override
    public void llmCall(int call, String mode, String tool, int qwenX, int qwenY, int pixelX,
            int pixelY, String result, String reason, String repair, String matchedClass,
            String nearestClass, double nearestDistance, int widgets, int tokensIn, int tokensOut,
            long ms, String text) {
    }

    @Override
    public void llmError(String cause, String detail) {
    }

    @Override
    public void llmBreakerOpen(int trips) {
    }

    @Override
    public void outcome(boolean newState, String targetStateKey, String targetActivity,
            boolean targetActivityHasMop, boolean activityChanged) {
    }

    @Override
    public void flushPendingStep() {
    }

    @Override
    public void mopData(String status, String reason, String pkg, int windows, int widgets,
            int flagged, int droppedNoId, int wtgEdges, int handlersUnmatched, int syntheticLambda,
            int recovered, int mopActivities, int mopActsAugmented) {
    }

    @Override
    public void pipeline(List<String> stages, List<String> passes, Map<String, Boolean> candidates) {
    }

    @Override
    public void llmAck(String serverModel) {
    }
}
