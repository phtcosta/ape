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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The first interactions of the run, per activity and per {@code xpath|ActionType} key
 * (mop-guidance, design D-B10).
 *
 * <p>Two MOP mechanisms read it: the MOP coin, which is eligible only for an action whose key has
 * no first interaction in its activity yet, and target retirement under {@code distance}, which
 * counts a target once per first interaction. Keeping one record for both means the coin and
 * retirement cannot disagree about what "first" means. The ledger exists iff {@code MOP} is
 * mounted, and it is mode-independent.
 *
 * <p>Pure bookkeeping: no RNG, no model reference, no resolution. An entry is never removed.
 */
public final class MopInteractionLedger {

    private final Map<String, Set<String>> tried = new HashMap<>();

    /**
     * Whether the action named by {@code key} has had its first interaction in {@code activity}.
     *
     * @param activity the activity the action belongs to
     * @param key the action's {@code xpath|ActionType} key
     */
    public boolean tried(String activity, String key) {
        Set<String> keys = tried.get(activity);
        return keys != null && keys.contains(key);
    }

    /**
     * Records a first interaction. Recording a key twice is harmless: the first is what counts.
     *
     * @param activity the activity the action belongs to
     * @param key the action's {@code xpath|ActionType} key
     */
    public void record(String activity, String key) {
        tried.computeIfAbsent(activity, a -> new HashSet<>()).add(key);
    }
}
