/*
 * Copyright 2026 University of Brasília — RVSEC Research Infrastructure
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
package com.android.commands.monkey.ape.utils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;

/**
 * Tracks per-state widget-level UI coverage.
 *
 * <p>Maintains a registry of all interactable widgets per {@link State} and records which
 * have been interacted with. The coverage gap metric (fraction of untested widgets, 0.0-1.0)
 * feeds into {@code StatefulAgent.adjustActionsByGUITree()} as a per-action priority boost.
 *
 * <p>Widget identification incorporates the action type for ALL actions:
 * {@code Name.toXPath() + "|" + ActionType.name()} for targeted actions and
 * {@code ActionType.name()} for non-targeted actions (BACK, MENU). Including the type
 * for targeted actions keeps distinct action types on the same target widget separate
 * (INV-COV-06).
 *
 * <p>State identification uses the {@link State} object directly as the map key,
 * leveraging the existing {@code equals()}/{@code hashCode()} contract via {@code StateKey}.
 *
 * <p>{@code stateData} is a bounded access-ordered map: once it exceeds
 * {@code Config.coverageMaxStates} live entries, the least-recently-used state is evicted
 * and its interaction counts are folded into a per-Activity rollup, so eviction bounds
 * memory without zeroing already-counted coverage (INV-COV-05). Per-state tracking stays
 * fragment-level for steering; per-Activity reporting aggregates the fragments via
 * {@link #getActivityCoverageGap(String)}.
 */
public class UICoverageTracker {

    /** Live per-state data: registered widget IDs and their interaction counts (bounded, LRU). */
    private final Map<State, Map<String, Integer>> stateData =
            new LinkedHashMap<State, Map<String, Integer>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<State, Map<String, Integer>> eldest) {
                    if (size() > Config.coverageMaxStates) {
                        foldIntoRollup(eldest.getKey(), eldest.getValue());
                        return true;
                    }
                    return false;
                }
            };

    /** Per-Activity coverage folded in from evicted states (Activity → widgetId → count). */
    private final Map<String, Map<String, Integer>> activityRollup = new HashMap<>();

    /** Total unique widgets registered across all live states (telemetry). */
    private int totalElements;

    /** Total interactions recorded across all states (telemetry). */
    private int totalInteractions;

    /**
     * Registers all actions of a state as trackable widgets.
     *
     * <p>Widget ID for each action is:
     * <ul>
     *   <li>For targeted actions ({@code requireTarget() == true}): {@code action.getTarget().toXPath()}</li>
     *   <li>For non-targeted actions (BACK, MENU): {@code action.getType().name()}</li>
     * </ul>
     *
     * <p>Registration is idempotent — re-registering the same state replaces the widget set.
     *
     * @param state   the current State object; no-op if null
     * @param actions list of ModelActions for the state; may be empty
     */
    public void registerScreenElements(State state, List<ModelAction> actions) {
        if (state == null) {
            return;
        }
        if (actions == null) {
            actions = java.util.Collections.emptyList();
        }

        // Build new widget set, preserving interaction counts for widgets that
        // were already tracked (supports re-registration without losing data
        // for widgets that remain).
        Map<String, Integer> oldData = stateData.get(state);
        Map<String, Integer> newData = new HashMap<>();
        Set<String> seen = new HashSet<>();

        for (ModelAction action : actions) {
            if (action == null) {
                continue;
            }
            String widgetId = widgetId(action);
            if (widgetId == null || widgetId.isEmpty()) {
                continue;
            }
            if (seen.add(widgetId)) {
                // Carry over existing interaction count if present
                int existingCount = (oldData != null && oldData.containsKey(widgetId))
                        ? oldData.get(widgetId) : 0;
                newData.put(widgetId, existingCount);
            }
        }

        // Update totalElements: subtract old count, add new count
        if (oldData != null) {
            totalElements -= oldData.size();
        }
        totalElements += newData.size();

        stateData.put(state, newData);
    }

    /**
     * Records an interaction for the widget corresponding to the given action in the given state.
     *
     * @param state  the current State; no-op if null
     * @param action the action that was executed; no-op if null
     */
    public void recordInteraction(State state, ModelAction action) {
        if (state == null || action == null) {
            return;
        }
        String widgetId = widgetId(action);
        if (widgetId == null || widgetId.isEmpty()) {
            return;
        }
        Map<String, Integer> data = stateData.get(state);
        if (data == null) {
            // State not registered — create minimal entry
            data = new HashMap<>();
            data.put(widgetId, 1);
            stateData.put(state, data);
            totalElements++;
            totalInteractions++;
            return;
        }
        Integer current = data.get(widgetId);
        if (current == null) {
            // Widget not registered but state is — add it
            data.put(widgetId, 1);
            totalElements++;
        } else {
            data.put(widgetId, current + 1);
        }
        totalInteractions++;
    }

    /**
     * Returns the fraction of registered widgets that have NOT been interacted with at least once.
     *
     * <p>Formula: {@code 1.0 - (interactedCount / totalRegistered)}.
     *
     * @param state the state to query; returns 1.0 for unknown/null states
     * @return coverage gap in [0.0, 1.0]
     */
    public float getCoverageGap(State state) {
        if (state == null) {
            return 1.0f;
        }
        Map<String, Integer> data = stateData.get(state);
        if (data == null || data.isEmpty()) {
            return 1.0f;
        }
        int total = data.size();
        int interacted = 0;
        for (int count : data.values()) {
            if (count > 0) {
                interacted++;
            }
        }
        return 1.0f - ((float) interacted / total);
    }

    /**
     * Returns the number of times a specific widget has been interacted with in the given state.
     *
     * @param state    the state to query
     * @param widgetId the widget identifier
     * @return interaction count, or 0 for unknown state/widget combinations
     */
    public int getInteractionCount(State state, String widgetId) {
        if (state == null || widgetId == null) {
            return 0;
        }
        Map<String, Integer> data = stateData.get(state);
        if (data == null) {
            return 0;
        }
        Integer count = data.get(widgetId);
        return count != null ? count : 0;
    }

    /**
     * Derives the widget ID from a ModelAction.
     *
     * @param action a non-null ModelAction
     * @return non-null, non-empty widget ID string
     */
    public static String widgetId(ModelAction action) {
        if (action == null) {
            return "";
        }
        if (action.requireTarget() && action.getTarget() != null) {
            // Include the action type so distinct types on the same target widget
            // (e.g. SCROLL_DOWN vs SCROLL_UP, CLICK vs LONG_CLICK) stay distinct (INV-COV-06).
            return action.getTarget().toXPath() + "|" + action.getType().name();
        }
        return action.getType().name();
    }

    /** Fold an evicted state's interaction counts into its Activity's rollup (INV-COV-05). */
    private void foldIntoRollup(State state, Map<String, Integer> data) {
        if (state == null || data == null) {
            return;
        }
        String activity = state.getActivity();
        if (activity == null) {
            return;
        }
        Map<String, Integer> agg = activityRollup.get(activity);
        if (agg == null) {
            agg = new HashMap<>();
            activityRollup.put(activity, agg);
        }
        for (Map.Entry<String, Integer> e : data.entrySet()) {
            mergeMax(agg, e.getKey(), e.getValue());
        }
        // The evicted widgets are no longer live; keep the live-element telemetry honest.
        totalElements -= data.size();
    }

    private static void mergeMax(Map<String, Integer> map, String key, Integer value) {
        int v = value != null ? value : 0;
        Integer prev = map.get(key);
        map.put(key, Math.max(prev != null ? prev : 0, v));
    }

    /**
     * Reported coverage gap for an Activity, aggregating all of its fragments — both the
     * live {@code stateData} entries and the evicted ones folded into the rollup — so that
     * NamingFactory refinement (one Activity → many {@code StateKey}s) does not inflate the
     * reported gap. Returns 1.0 for an unknown/null Activity with no recorded widgets.
     */
    public float getActivityCoverageGap(String activity) {
        if (activity == null) {
            return 1.0f;
        }
        Map<String, Integer> agg = new HashMap<>();
        Map<String, Integer> rolled = activityRollup.get(activity);
        if (rolled != null) {
            agg.putAll(rolled);
        }
        for (Map.Entry<State, Map<String, Integer>> se : stateData.entrySet()) {
            State s = se.getKey();
            if (s != null && activity.equals(s.getActivity())) {
                for (Map.Entry<String, Integer> e : se.getValue().entrySet()) {
                    mergeMax(agg, e.getKey(), e.getValue());
                }
            }
        }
        if (agg.isEmpty()) {
            return 1.0f;
        }
        int interacted = 0;
        for (int count : agg.values()) {
            if (count > 0) {
                interacted++;
            }
        }
        return 1.0f - ((float) interacted / agg.size());
    }

    /** Returns total unique widgets registered across all states (telemetry). */
    public int getTotalElements() {
        return totalElements;
    }

    /** Returns total interactions recorded across all states (telemetry). */
    public int getTotalInteractions() {
        return totalInteractions;
    }
}
