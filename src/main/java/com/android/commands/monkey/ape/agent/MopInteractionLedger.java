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

import java.util.HashMap;
import java.util.Map;

/**
 * The executions of the run, counted per activity and per {@code xpath|ActionType} key
 * (mop-guidance "MopInteractionLedger — Executions per Activity and Key", design D-B10, D-B17).
 *
 * <p>Three MOP mechanisms read it: the MOP coin, which is eligible for an action while its key's
 * count in its activity is below the budget k ({@link #belowBudget}); target retirement under
 * {@code distance}, which counts targets only on a key's first execution ({@link #tried} read
 * before {@link #record}); and the MOP widget pass, which boosts only keys not yet tried. Keeping one
 * record for all three means they cannot disagree about what was executed. The ledger exists iff
 * {@code MOP} is mounted, and it is mode-independent.
 *
 * <p>Pure bookkeeping: no RNG, no model reference, no resolution. A count is never decreased.
 */
public final class MopInteractionLedger {

    private final int budget;
    private final Map<String, Map<String, Integer>> counts = new HashMap<>();

    /**
     * @param budget the MOP coin's budget k, {@code ape.mopCoinLedgerMaxInteractions} (at least 1
     *        after resolution; 1 is the first-interaction rule of D-B10)
     */
    public MopInteractionLedger(int budget) {
        this.budget = budget;
    }

    /**
     * The number of executions of the action named by {@code key} in {@code activity}.
     *
     * @param activity the activity the action belongs to
     * @param key the action's {@code xpath|ActionType} key
     */
    public int count(String activity, String key) {
        Map<String, Integer> keys = counts.get(activity);
        Integer n = keys == null ? null : keys.get(key);
        return n == null ? 0 : n;
    }

    /**
     * Whether the action named by {@code key} has been executed in {@code activity} at least once.
     *
     * @param activity the activity the action belongs to
     * @param key the action's {@code xpath|ActionType} key
     */
    public boolean tried(String activity, String key) {
        return count(activity, key) > 0;
    }

    /**
     * Whether the key's count in {@code activity} is below the budget k, which keeps the action
     * eligible for the MOP coin.
     *
     * @param activity the activity the action belongs to
     * @param key the action's {@code xpath|ActionType} key
     */
    public boolean belowBudget(String activity, String key) {
        return count(activity, key) < budget;
    }

    /**
     * Records one execution: the key's count in {@code activity} grows by one.
     *
     * @param activity the activity the action belongs to
     * @param key the action's {@code xpath|ActionType} key
     */
    public void record(String activity, String key) {
        counts.computeIfAbsent(activity, a -> new HashMap<>()).merge(key, 1, Integer::sum);
    }
}
