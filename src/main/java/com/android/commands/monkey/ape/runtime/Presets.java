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
 * is the former {@code aperv} preset, the STATIC block the frontier at 200 with typed input and
 * component triggering at 0.03 (D-B13), the MOP block arm 3's MOP configuration
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
 *
 * <p><b>The four former arm presets</b> ({@code aperv}, {@code llm}, {@code llm_mop} and the
 * former {@code mop}) are kept until the wave-2 integration of isolate-guidance-groups deletes
 * them with their goldens; each states the group keys that make it resolve to its former plan.
 * Until then the former {@code mop} arm is reachable as {@code mop_arm}, since the cell owns the
 * name {@code mop}.
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

    /** Former campaign arm {@code sata}; deleted in wave 2. */
    public static final String APERV = "aperv";
    /** Former campaign arm {@code sata_mop_widget}; deleted in wave 2. */
    public static final String MOP_ARM = "mop_arm";
    /** Former campaign arm {@code sata_llm}; deleted in wave 2. */
    public static final String LLM = "llm";
    /** Former campaign arm {@code sata_mop_llm}; deleted in wave 2. */
    public static final String LLM_MOP = "llm_mop";

    private static final List<String> NAMES = Collections.unmodifiableList(
            Arrays.asList(BASE, MOP, AS, AS_MOP, AS_LLM, AS_MOP_LLM, APERV, MOP_ARM, LLM, LLM_MOP));

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
        if (APERV.equals(name)) {
            return aperv();
        }
        if (MOP_ARM.equals(name)) {
            return mopArm();
        }
        if (LLM.equals(name)) {
            vector = aperv();
            vector.putAll(formerLlmBlock());
            return vector;
        }
        if (LLM_MOP.equals(name)) {
            vector = mopArm();
            vector.putAll(formerLlmBlock());
            return vector;
        }
        throw new RunSpecException(RunSpecException.Reason.UNKNOWN_PRESET, KeyOwnership.KEY_PRESET,
                "unknown preset '" + name + "'; valid names are " + NAMES);
    }

    /** The ADDONS block: the former {@code aperv} preset's addon keys (design D-B3). */
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

    /**
     * Former arm {@code sata}: the addons on, no data, no LLM. Translated from the harness's arm
     * dictionary at rvsec {@code 6dc8a0af}; its {@code ape.leastVisitedPriorityTiebreak} and
     * {@code ape.fuzzInputTyped} entries are gone, the first retired and the second a STATIC
     * member that has no effect without the artifact.
     */
    private static Map<String, String> aperv() {
        Map<String, String> vector = new LinkedHashMap<>();
        vector.put("ape.addons", "true");
        vector.put("ape.backMenuPickCap", "3");
        vector.put("ape.foreignActivityGuard", "true");
        vector.put("ape.treePackageGuard", "true");
        vector.put("ape.dynamicEpsilon", "true");
        vector.put("ape.heuristicInput", "true");
        vector.put("ape.formCompletionEnabled", "true");
        vector.put("ape.modelMenuEnabled", "true");
        vector.put("ape.treeEnhancementsEnabled", "true");
        vector.put("ape.activityBudgetEnabled", "true");
        // The sentinel every baseline arm pushes: accepted as inert on a plan with no LLM.
        vector.put("ape.llmPercentageNoSubstrate", "-1");
        vector.put("ape.frontierBoostWeight", "0");
        vector.put("ape.activityTriggerEnabled", "false");
        vector.put("ape.mopActivitySourceComponents", "false");
        vector.put("ape.mopFrontierWeight", "0");
        // throttle_ms in the arm dictionary; ape.defaultGUIThrottle on the wire.
        vector.put("ape.defaultGUIThrottle", "200");
        return vector;
    }

    /**
     * Former arm {@code sata_mop_widget}: {@code aperv} plus the four flag-mode MOP weights, with
     * the STATIC group on for typed input (a root feature before the split) and the MOP coin off,
     * since the former arm had none.
     */
    private static Map<String, String> mopArm() {
        Map<String, String> vector = aperv();
        vector.put("ape.static", "true");
        vector.put("ape.fuzzInputTyped", "true");
        vector.put("ape.mop", "true");
        vector.put("ape.mopCoinPercentage", "0");
        vector.put("ape.mopWeightDirect", "500");
        vector.put("ape.mopWeightTransitive", "300");
        vector.put("ape.mopWeightOpenMenu", "250");
        vector.put("ape.mopWeightWtg", "200");
        return vector;
    }

    /** The former arms' shared LLM sampling block, minus the deployment-specific server URL. */
    private static Map<String, String> formerLlmBlock() {
        Map<String, String> block = new LinkedHashMap<>();
        block.put("ape.llm", "true");
        block.put("ape.llmOnNewState", "true");
        block.put("ape.llmOnStagnation", "true");
        block.put("ape.llmModel", "default");
        block.put("ape.llmTemperature", "0.3");
        block.put("ape.llmTopP", "0.6");
        block.put("ape.llmTopK", "50");
        block.put("ape.llmTimeoutMs", "15000");
        return block;
    }
}
