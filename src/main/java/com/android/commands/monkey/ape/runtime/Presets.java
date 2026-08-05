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
 * The four campaign arms, resident in the jar instead of in a Python dictionary.
 *
 * <p>Each preset is the base key vector of one campaign arm; explicit keys override it, and the
 * merged result passes exactly the same validation as a plan written out key by key. An ablation
 * is a named override on a preset, never a fifth preset.
 *
 * <p><b>Provenance.</b> The vectors are translated from the arm dictionaries of the harness at
 * {@code rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py}, read at rvsec commit
 * {@code 6dc8a0af} (sha256 {@code 660f151709b12dc311e6ddaa221d4af968b2630d235149cd22f94bada7736612}):
 * {@code aperv} from arm {@code sata}, {@code mop} from {@code sata_mop_widget}, {@code llm} from
 * {@code sata_llm} and {@code llm_mop} from {@code sata_mop_llm}. They are read from the code
 * rather than from the design document on purpose — a preset vector asserted from prose is a
 * guess with a citation.
 *
 * <p>That read predates the harness edit retiring {@code ape_pure_mode}, so the vectors were
 * translated from a source that still carried the key, minus the key. {@code PresetsTest} closes
 * the gap: it resolves each preset against a fixture captured from the harness <em>after</em> the
 * edit (rvsec {@code d8f1df0a}, {@code tool.py} sha256 {@code aba920ea…c93ae8}) and requires the
 * same plan digest. The vectors are therefore pinned against the file a device actually receives,
 * not against a translation of it.
 *
 * <p><b>What a preset deliberately omits.</b> Three kinds of value stay out:
 * <ul>
 * <li><b>Deployment-specific</b> — {@code ape.mopDataPath} and {@code ape.llmUrl} name a file and
 *     a server that belong to the machine, not to the arm, so they must come explicitly. The
 *     consequence is worth stating because it is a fail-fast, not a fallback: {@code ape.preset=llm}
 *     without an explicit {@code ape.llmUrl} aborts, because the preset states the LLM routing
 *     gates ON while the mechanism they parameterize is absent.</li>
 * <li><b>The agent type</b> — {@code --ape} is a command-line value and {@code ape.agentType} is
 *     retired as a file key. The {@code aperv} arm's agent is {@code sata}, which is already the
 *     default when {@code --ape} is absent, so a preset entry for it could only either duplicate
 *     the default or contradict the command line.</li>
 * <li><b>{@code ape.apePureMode}</b> — retired. A preset feeds the same validator as an explicit
 *     plan, so baking the key into {@code aperv} would make the preset abort on itself.</li>
 * </ul>
 */
public final class Presets {

    /** Campaign arm {@code sata}: RV exploration on, no MOP substrate, no LLM. */
    public static final String APERV = "aperv";
    /** Campaign arm {@code sata_mop_widget}: {@link #APERV} plus the four MOP scoring weights. */
    public static final String MOP = "mop";
    /** Campaign arm {@code sata_llm}: {@link #APERV} plus the LLM sampling block. */
    public static final String LLM = "llm";
    /** Campaign arm {@code sata_mop_llm}: the MOP weights and the LLM block together. */
    public static final String LLM_MOP = "llm_mop";

    private static final List<String> NAMES = Collections.unmodifiableList(
            Arrays.asList(APERV, MOP, LLM, LLM_MOP));

    private Presets() {
    }

    /** The four valid preset names, for the unknown-preset diagnostic. */
    public static List<String> names() {
        return NAMES;
    }

    /**
     * The preset's base key vector, as a fresh mutable map the caller overlays overrides onto.
     *
     * @throws RunSpecException with {@link RunSpecException.Reason#UNKNOWN_PRESET} for any other name
     */
    public static Map<String, String> resolve(String name) {
        if (APERV.equals(name)) {
            return aperv();
        }
        if (MOP.equals(name)) {
            return mop();
        }
        if (LLM.equals(name)) {
            return llm();
        }
        if (LLM_MOP.equals(name)) {
            Map<String, String> vector = mop();
            vector.putAll(llmBlock());
            return vector;
        }
        throw new RunSpecException(RunSpecException.Reason.UNKNOWN_PRESET, KeyOwnership.KEY_PRESET,
                "unknown preset '" + name + "'; valid names are " + NAMES);
    }

    /**
     * Arm {@code sata}: the seventeen arm-defining flags that survive the retirement of
     * {@code ape_pure_mode}, at the values the harness pushes, plus the campaign throttle. RV
     * exploration on; MOP, reach and LLM off.
     */
    private static Map<String, String> aperv() {
        Map<String, String> vector = new LinkedHashMap<>();
        vector.put("ape.backMenuPickCap", "3");
        vector.put("ape.foreignActivityGuard", "true");
        vector.put("ape.treePackageGuard", "true");
        vector.put("ape.dynamicEpsilon", "true");
        vector.put("ape.heuristicInput", "true");
        vector.put("ape.fuzzInputTyped", "true");
        vector.put("ape.formCompletionEnabled", "true");
        vector.put("ape.modelMenuEnabled", "true");
        vector.put("ape.leastVisitedPriorityTiebreak", "true");
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

    /** Arm {@code sata_mop_widget}: {@code aperv} plus the four MOP scoring weights. */
    private static Map<String, String> mop() {
        Map<String, String> vector = aperv();
        vector.put("ape.mopWeightDirect", "500");
        vector.put("ape.mopWeightTransitive", "300");
        vector.put("ape.mopWeightOpenMenu", "250");
        vector.put("ape.mopWeightWtg", "200");
        return vector;
    }

    /** Arm {@code sata_llm}: {@code aperv} plus the LLM sampling block. */
    private static Map<String, String> llm() {
        Map<String, String> vector = aperv();
        vector.putAll(llmBlock());
        return vector;
    }

    /** The shared LLM sampling block, minus the deployment-specific server URL. */
    private static Map<String, String> llmBlock() {
        Map<String, String> block = new LinkedHashMap<>();
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
