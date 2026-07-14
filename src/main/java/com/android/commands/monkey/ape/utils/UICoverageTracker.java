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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.android.commands.monkey.ape.model.ActionType;
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

    /**
     * Cumulative activity-scoped interaction membership (Activity → set of interacted widget IDs).
     * Written on every {@link #recordInteraction} regardless of the per-state branch, never
     * evicted, so it is a superset of {@code activityRollup} and drives the coverage boost's
     * novelty test across NamingFactory fragments and state eviction (INV-COV-09).
     */
    private final Map<String, Set<String>> activityInteracted = new HashMap<>();

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
        // Activity-scoped cumulative membership (coverage-boost-activity-scope): record before the
        // per-state branches so first-touch interactions on not-yet-registered states are captured
        // too. Never evicted → survives NamingFactory fragmentation and LRU eviction (INV-COV-09).
        String activity = state.getActivity();
        if (activity != null) {
            Set<String> interacted = activityInteracted.get(activity);
            if (interacted == null) {
                interacted = new HashSet<>();
                activityInteracted.put(activity, interacted);
            }
            interacted.add(widgetId);
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

    /**
     * Whether {@code widgetId} has been interacted with at least once anywhere in {@code activity}.
     * O(1) {@link Set} membership over the cumulative {@link #activityInteracted} map — no
     * {@code stateData} scan, no rollup consultation. Monotonic within a run: true from the first
     * {@code recordInteraction} of the widget in any fragment of the Activity onward, unaffected by
     * state eviction (INV-COV-09). Null activity/widget → false. Drives the coverage boost's
     * novelty test so a fragment split does not re-arm the boost for already-exercised widgets.
     */
    public boolean hasActivityInteraction(String activity, String widgetId) {
        if (activity == null || widgetId == null) {
            return false;
        }
        Set<String> interacted = activityInteracted.get(activity);
        return interacted != null && interacted.contains(widgetId);
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

    /**
     * Emits one read-only {@code [APE-RV] UICOV} line per tracked state, so per-screen
     * exploration completeness — invisible in the trace during a run — becomes observable
     * at teardown. Each line reports the per-state widget coverage already held in
     * {@code stateData} plus a {@code mopReach} flag supplied by the caller.
     *
     * <p>The dump is strictly read-only: it iterates {@code stateData} without get/put, so it
     * neither reorders the access-ordered map nor triggers LRU eviction, and it mutates no
     * count (INV-COV-07). {@code getTotalElements()}/{@code getTotalInteractions()} are
     * invariant across the call.
     *
     * @param mopReach predicate over each tracked {@link State}; printed as {@code mopReach=1|0}.
     *                 Supplied by the caller because the tracker holds no {@code MopData}
     *                 reference. May be null (treated as always-false).
     */
    public void dump(Predicate<State> mopReach) {
        for (Map.Entry<State, Map<String, Integer>> entry : stateData.entrySet()) {
            State state = entry.getKey();
            if (state == null) {
                continue;
            }
            Logger.format("%s", formatCoverageLine(state, entry.getValue(), mopReach));
        }
        dumpActivities();
    }

    /**
     * Emits one read-only {@code [APE-RV] UICOV-ACT} line per Activity, aggregating its naming
     * fragments the way the per-Activity reporting requirement defines: the union of the
     * Activity's fragments' widget keys, each widget's interaction count taken as the max across
     * live fragments and the eviction rollup ({@code mergeMax}). Per-state {@code UICOV} lines
     * overstate the true per-screen gap under refinement (the same physical widget re-registers as
     * untested in every sibling fragment); this line is the honest per-screen number.
     *
     * <p>Single pass over {@code stateData} (O(S)), seeded from {@code activityRollup} so
     * evicted-only Activities still appear. Lines are emitted in lexicographic activity-name order
     * for deterministic output. Strictly read-only (INV-COV-07): iterates {@code entrySet} without
     * get/put, so the access-ordered {@code stateData} is neither reordered nor evicted, and no
     * count is mutated.
     */
    private void dumpActivities() {
        Map<String, Map<String, Integer>> byActivity = new HashMap<>();
        Map<String, Integer> liveFragments = new HashMap<>();
        // Seed from the rollup first so Activities with no live fragment still emit a line.
        for (Map.Entry<String, Map<String, Integer>> re : activityRollup.entrySet()) {
            byActivity.put(re.getKey(), new HashMap<>(re.getValue()));
            liveFragments.put(re.getKey(), 0);
        }
        // Merge live fragments (single pass; entrySet iteration does not reorder the LRU map).
        for (Map.Entry<State, Map<String, Integer>> se : stateData.entrySet()) {
            State s = se.getKey();
            if (s == null) {
                continue;
            }
            String activity = s.getActivity();
            if (activity == null) {
                continue;
            }
            Map<String, Integer> agg = byActivity.get(activity);
            if (agg == null) {
                agg = new HashMap<>();
                byActivity.put(activity, agg);
            }
            for (Map.Entry<String, Integer> e : se.getValue().entrySet()) {
                mergeMax(agg, e.getKey(), e.getValue());
            }
            Integer live = liveFragments.get(activity);
            liveFragments.put(activity, (live != null ? live : 0) + 1);
        }
        List<String> activities = new ArrayList<>(byActivity.keySet());
        Collections.sort(activities);
        for (String activity : activities) {
            Logger.format("%s", formatActivityLine(activity, byActivity.get(activity),
                    liveFragments.getOrDefault(activity, 0)));
        }
    }

    /**
     * Builds the {@code [APE-RV] UICOV-ACT} line for one Activity. Package-visible and pure (no I/O,
     * no mutation), mirroring {@link #formatCoverageLine} so the format is host-testable without an
     * Android runtime. {@code discovered}/{@code interacted}/{@code gap}/{@code byType} follow the
     * per-state conventions over the aggregated map; {@code liveStates} is the number of live
     * (non-evicted) fragments grouped into this line (0 for rollup-only Activities).
     */
    String formatActivityLine(String activity, Map<String, Integer> merged, int liveFragments) {
        int discovered = (merged != null) ? merged.size() : 0;
        int interacted = 0;
        Map<String, int[]> byType = new HashMap<>(); // type -> [interacted, discovered]
        if (merged != null) {
            for (Map.Entry<String, Integer> e : merged.entrySet()) {
                boolean hit = e.getValue() != null && e.getValue() > 0;
                if (hit) {
                    interacted++;
                }
                String type = typeSegment(e.getKey());
                int[] agg = byType.get(type);
                if (agg == null) {
                    agg = new int[2];
                    byType.put(type, agg);
                }
                agg[1]++;
                if (hit) {
                    agg[0]++;
                }
            }
        }
        float gap = discovered == 0 ? 1.0f : 1.0f - ((float) interacted / discovered);
        return String.format(Locale.ROOT,
                "[APE-RV] UICOV-ACT activity=%s discovered=%d interacted=%d gap=%.1f byType=%s liveStates=%d",
                activity, discovered, interacted, gap, formatByType(byType), liveFragments);
    }

    /**
     * Builds the {@code [APE-RV] UICOV} line for one state. Package-visible and pure (no I/O,
     * no mutation) so the format is host-testable without an Android runtime.
     *
     * <p>{@code discovered} (W) is the count of registered widgets, {@code interacted} (D) the
     * count of those with a positive interaction count, {@code gap} is {@code 1 - D/W}
     * ({@code 1.0} when {@code W == 0}). {@code byType} breaks D/W down by action type — the
     * {@code TYPE} segment of each {@code "<xpath>|<TYPE>"} / {@code "<TYPE>"} key (the
     * {@link #widgetId(ModelAction)} convention) — ordered by {@link ActionType} declaration
     * order. The gap is formatted with {@code Locale.ROOT} so it always parses as {@code 0.7}
     * (never a locale-specific {@code 0,7}); the raw integer W/D carry full precision.
     */
    String formatCoverageLine(State state, Map<String, Integer> data, Predicate<State> mopReach) {
        int discovered = (data != null) ? data.size() : 0;
        int interacted = 0;
        Map<String, int[]> byType = new HashMap<>(); // type -> [interacted, discovered]
        if (data != null) {
            for (Map.Entry<String, Integer> e : data.entrySet()) {
                boolean hit = e.getValue() != null && e.getValue() > 0;
                if (hit) {
                    interacted++;
                }
                String type = typeSegment(e.getKey());
                int[] agg = byType.get(type);
                if (agg == null) {
                    agg = new int[2];
                    byType.put(type, agg);
                }
                agg[1]++;
                if (hit) {
                    agg[0]++;
                }
            }
        }
        float gap = discovered == 0 ? 1.0f : 1.0f - ((float) interacted / discovered);
        int mopReachFlag = (mopReach != null && mopReach.test(state)) ? 1 : 0;
        return String.format(Locale.ROOT,
                "[APE-RV] UICOV state=%s discovered=%d interacted=%d gap=%.1f byType=%s mopReach=%d",
                state.getStateKey(), discovered, interacted, gap, formatByType(byType), mopReachFlag);
    }

    /** The {@code TYPE} segment of a widget key: substring after the last {@code |}, or the whole key. */
    private static String typeSegment(String widgetId) {
        int bar = widgetId.lastIndexOf('|');
        return bar >= 0 ? widgetId.substring(bar + 1) : widgetId;
    }

    /** Joins the per-action-type {@code TYPE:interacted/discovered} pairs in ActionType declaration order. */
    private static String formatByType(Map<String, int[]> byType) {
        List<String> keys = new ArrayList<>(byType.keySet());
        keys.sort((a, b) -> Integer.compare(actionTypeOrdinal(a), actionTypeOrdinal(b)));
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            int[] agg = byType.get(k);
            sb.append(k).append(':').append(agg[0]).append('/').append(agg[1]);
        }
        return sb.toString();
    }

    /** Declaration-order rank of an action-type name; unknown segments sort last. */
    private static int actionTypeOrdinal(String name) {
        try {
            return ActionType.valueOf(name).ordinal();
        } catch (IllegalArgumentException e) {
            return Integer.MAX_VALUE;
        }
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
