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

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes {@code RUN_START}: one line that says what this run is.
 *
 * <p>The bar this line has to clear is that it alone reconstructs the arm — the agent, the active
 * mechanisms and every parameter that is not a jar default — without opening the harness's source
 * in another repository. Anything less and a trace is only interpretable next to the code that
 * produced it, which is exactly the position that made a whole campaign unreadable: a stale jar
 * shipped, its MOP boost fired zero times across 147,153 evaluations, and nothing in 2,028 tasks'
 * worth of output said which jar had run.
 *
 * <p><b>Nothing reads this line.</b> No Java component, no Python component, no validation
 * anywhere, at any level (owner decision D1). It is provenance written for a human or a script
 * reading the traces afterwards, and giving it a reader would turn a record into a protocol.
 *
 * <p>Defaults are left out of {@code params} because they are recoverable from {@code build.sha} —
 * the jar the line names is the jar that holds them. What is emitted is what someone chose, plus
 * the activation key of every active mechanism so that reading the line never requires knowing
 * which defaults happen to be on.
 */
public final class RunSpecEcho {

    /**
     * The record's format version. It is not decoration: this study's own traces already carry
     * incompatible {@code [APE-STEP]} schemas across campaigns — the calibration corpus has no
     * {@code mop_frontier} or {@code pick_channel} field and realises {@code mop=} over
     * {@code {0,300}} where the decisive campaign realises {@code {0,500}} — and neither trace
     * says which it is. This field is what makes the next such comparison fail loudly.
     */
    public static final int FORMAT_VERSION = 1;

    /** The record's discriminator, which stage 4's other record types are read against. */
    public static final String RECORD_TYPE = "RUN_START";

    private RunSpecEcho() {
    }

    /**
     * Emits the run's {@code RUN_START} record.
     *
     * @param context the established run context; the plan comes from it, and so does the run id,
     *        which is the run's identity rather than the plan's
     * @param out the stream the trace is captured from
     */
    public static void emit(RunContext context, PrintStream out) {
        RunSpec spec = context.spec();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", RECORD_TYPE);
        record.put("v", FORMAT_VERSION);
        record.put("run_id", context.runId());
        // The run's time origin, taken from the context rather than read again here: the step
        // records' `t` is relative to that same value, so publishing a second reading of the clock
        // would put the trace's own timeline a few milliseconds off its declared base.
        record.put("t0", context.t0());
        record.put("seed", spec.seed());
        record.put("agent", spec.agentType());
        record.put("preset", spec.presetName());
        record.put("features", featureNames(spec));
        record.put("params", params(spec));
        record.put("inert", new ArrayList<String>(spec.inertKeys()));
        if (spec.corpusBasis() != null) {
            // Omitted rather than defaulted when the harness supplied none: an invented basis
            // would be a claim about which application list the run was drawn from, and that
            // claim is the one thing this field exists to stop being re-derived.
            record.put("corpus_basis", spec.corpusBasis());
        }
        record.put("digest", spec.digest());
        record.put("props_digest", spec.propertiesDigest());
        Map<String, Object> build = new LinkedHashMap<>();
        build.put("sha", BuildInfo.GIT_SHA);
        build.put("time", BuildInfo.JAR_BUILT);
        record.put("build", build);

        // One print of one string: a println of parts could interleave with another thread's
        // output and split the record.
        out.print(Json.object(record) + "\n");
        out.flush();
    }

    private static List<String> featureNames(RunSpec spec) {
        List<String> names = new ArrayList<>();
        for (Feature feature : spec.features()) {
            names.add(feature.name());
        }
        Collections.sort(names);
        return names;
    }

    /**
     * Every parameter someone chose, plus the activation key of every active feature.
     *
     * <p>Sorted by key rather than left in declaration order, so two runs of the same arm produce
     * byte-identical field order and a later key added to the registry does not reshuffle the
     * line. Values carry their declared type — a weight emits as a number and a gate as a boolean
     * — so a reader parses the record instead of re-parsing property text out of it.
     */
    private static Map<String, Object> params(RunSpec spec) {
        Map<String, Object> chosen = new TreeMap<>();
        for (Map.Entry<String, String> entry : spec.effectiveValues().entrySet()) {
            String key = entry.getKey();
            if (isDefault(key, entry.getValue()) && !isActiveActivationKey(spec, key)) {
                continue;
            }
            chosen.put(key, KeyOwnership.typeOf(key).parse(key, entry.getValue()));
        }
        return chosen;
    }

    /** A key with no jar default has no default to be at, so any value it holds was chosen. */
    private static boolean isDefault(String key, String value) {
        String jarDefault = KeyOwnership.defaultOf(key);
        return jarDefault != null && KeyOwnership.typeOf(key).sameValue(key, value, jarDefault);
    }

    private static boolean isActiveActivationKey(RunSpec spec, String key) {
        for (Feature feature : spec.features()) {
            if (feature.activationKey().equals(key)) {
                return true;
            }
        }
        return false;
    }
}
