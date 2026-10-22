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
package com.android.commands.monkey.ape.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cell presets, resident in the jar instead of in a Python dictionary.
 *
 * <p>A cell is a set of behaviour groups (guidance-groups), and its preset is the union of the
 * blocks of those groups: {@code base} states no block, {@code mop} the MOP block, {@code as}
 * ADDONS and STATIC, and {@code as_mop}, {@code as_llm} and {@code as_mop_llm} add MOP, LLM or
 * both. Explicit keys override the vector, and the merged result passes exactly the same
 * validation as a plan written out key by key. A dose or an ablation is a named override on a
 * cell, never a seventh preset.
 *
 * <p><b>Provenance.</b> The blocks reproduce Study 03's campaign E6 (design D-B3): the ADDONS block
 * is the addon configuration of E6's APE-RV arms, the STATIC block the frontier at 200 with typed
 * input and component triggering at 0.03 (D-B13), the MOP block arm 3's MOP configuration
 * ({@code mopd_on_llm_off}) plus {@code p_M = 1.0}, and the LLM block arm 4's sampling
 * configuration ({@code mopd_on_llm_90}) with {@code p_L = 0.9}.
 *
 * <p><b>What a preset deliberately omits.</b>
 * <ul>
 * <li><b>Deployment-specific values</b> — {@code ape.staticDataPath}, {@code ape.llmUrl} and
 *     {@code ape.variant} name a file, a server and a harness label that belong to the machine,
 *     not to the cell, so they must come explicitly. That is a fail-fast, not a fallback:
 *     {@code ape.preset=as} without {@code ape.staticDataPath} aborts, because the preset states
 *     {@code ape.static=true} while the data it reads is absent.</li>
 * <li><b>The agent type</b> — {@code --ape} is a command-line value and {@code ape.agentType} is
 *     retired as a file key; a cell's agent is {@code sata}, the default.</li>
 * <li><b>{@code ape.mopWeightOpenMenu}</b> — a preset key counts as explicit, so stating it in the
 *     MOP block would abort the {@code mop} cell, which has no {@code MODEL_MENU}. Its jar default
 *     (250) mounts the gateway wherever the menu action exists (D-B14.1).</li>
 * </ul>
 */
public final class Presets {

    /** The cell with no group: the jar defaults, BASE alone. */
    public static final String BASE = "base";
    /** BASE plus MOP. */
    public static final String MOP = "mop";
    /** BASE plus ADDONS and STATIC: the full tool without specifications. */
    public static final String AS = "as";
    /** {@link #AS} plus MOP. */
    public static final String AS_MOP = "as_mop";
    /** {@link #AS} plus LLM. */
    public static final String AS_LLM = "as_llm";
    /** All four groups. */
    public static final String AS_MOP_LLM = "as_mop_llm";

    private static final List<String> NAMES = Collections.unmodifiableList(
            Arrays.asList(BASE, MOP, AS, AS_MOP, AS_LLM, AS_MOP_LLM));

    private Presets() {
    }

    /** The valid preset names, for the unknown-preset diagnostic. */
    public static List<String> names() {
        return NAMES;
    }

    /**
     * The preset's base key vector, as a fresh mutable map the caller overlays overrides onto.
     *
     * @throws RunSpecException with {@link RunSpecException.Reason#UNKNOWN_PRESET} for any other name
     */
    public static Map<String, String> resolve(String name) {
        Map<String, String> vector = new LinkedHashMap<>();
        if (BASE.equals(name)) {
            return vector;
        }
        if (MOP.equals(name)) {
            vector.putAll(mopBlock());
            return vector;
        }
        if (AS.equals(name) || AS_MOP.equals(name) || AS_LLM.equals(name)
                || AS_MOP_LLM.equals(name)) {
            vector.putAll(addonsBlock());
            vector.putAll(staticBlock());
            if (AS_MOP.equals(name) || AS_MOP_LLM.equals(name)) {
                vector.putAll(mopBlock());
            }
            if (AS_LLM.equals(name) || AS_MOP_LLM.equals(name)) {
                vector.putAll(llmBlock());
            }
            return vector;
        }
        throw new RunSpecException(RunSpecException.Reason.UNKNOWN_PRESET, KeyOwnership.KEY_PRESET,
                "unknown preset '" + name + "'; valid names are " + NAMES);
    }

    /** The ADDONS block: the addon keys of E6's APE-RV arms (design D-B3). */
    private static Map<String, String> addonsBlock() {
        Map<String, String> block = new LinkedHashMap<>();
        block.put("ape.addons", "true");
        block.put("ape.backMenuPickCap", "3");
        block.put("ape.foreignActivityGuard", "true");
        block.put("ape.treePackageGuard", "true");
        block.put("ape.dynamicEpsilon", "true");
        block.put("ape.heuristicInput", "true");
        block.put("ape.formCompletionEnabled", "true");
        block.put("ape.modelMenuEnabled", "true");
        block.put("ape.treeEnhancementsEnabled", "true");
        block.put("ape.activityBudgetEnabled", "true");
        block.put("ape.activityStableRestartThreshold", "200");
        block.put("ape.coverageBoostWeight", "100");
        block.put("ape.apeFixes", "true");
        return block;
    }

    /** The STATIC block: the WTG frontier, typed input and component triggering (D-B3, D-B13). */
    private static Map<String, String> staticBlock() {
        Map<String, String> block = new LinkedHashMap<>();
        block.put("ape.static", "true");
        block.put("ape.frontierBoostWeight", "200");
        block.put("ape.fuzzInputTyped", "true");
        block.put("ape.componentPercentage", "0.03");
        return block;
    }

    /** The MOP block: E6 arm 3's distance guidance, plus the MOP coin at {@code p_M = 1.0}. */
    private static Map<String, String> mopBlock() {
        Map<String, String> block = new LinkedHashMap<>();
        block.put("ape.mop", "true");
        block.put("ape.mopScoring", "distance");
        block.put("ape.mopWeightWtg", "200");
        block.put("ape.mopFrontierWeight", "200");
        block.put("ape.activityTriggerEnabled", "true");
        block.put("ape.mopActivitySourceComponents", "true");
        block.put("ape.mopCoinPercentage", "1.0");
        return block;
    }

    /** The LLM block: E6 arm 4's sampling configuration, at {@code p_L = 0.9}; no server URL. */
    private static Map<String, String> llmBlock() {
        Map<String, String> block = new LinkedHashMap<>();
        block.put("ape.llm", "true");
        block.put("ape.llmOnNewState", "true");
        block.put("ape.llmOnStagnation", "true");
        block.put("ape.llmPercentage", "0.9");
        block.put("ape.llmPromptVariant", "v13");
        block.put("ape.llmTemperature", "0.0");
        block.put("ape.llmTopP", "1.0");
        block.put("ape.llmTopK", "-1");
        block.put("ape.llmSnapTolerancePx", "150");
        block.put("ape.llmModel", "Qwen/Qwen3-VL-4B-Instruct-FP8");
        block.put("ape.llmTimeoutMs", "15000");
        return block;
    }
}
