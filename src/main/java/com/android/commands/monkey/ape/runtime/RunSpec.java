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

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a run is: one immutable, fully validated plan, resolved once before the first step.
 *
 * <p>Before this type, "what this run is" was spread across a hundred-odd public static fields
 * loaded from two device files with empty catch blocks, a dictionary in another repository, three
 * string registries the compiler could not check, and a command-line flag that fell back silently
 * to SATA on any value it did not recognize. Nothing validated the whole, and nothing recorded
 * what had actually been in effect.
 *
 * <p>{@link #resolve} is total: an unknown key, a retired key, a value that does not parse, a
 * feature whose dependencies are unmet, or an unrecognized agent type raises
 * {@link RunSpecException} and the process ends before it touches a device. No invalid input
 * degrades to a default, which is the one property that makes a trace's numbers attributable.
 *
 * <p>Immutability is hand-rolled — final class, final fields, private constructor, defensive
 * copies at the boundary — because this tree is Java 11 on Dalvik with no dependencies. Equality
 * is by value so tests can compare two plans directly.
 *
 * <p><b>Grouping of the parameter objects.</b> {@code MopParams}, {@code LlmParams} and
 * {@code TelemetryParams} are null exactly when their gating feature is absent, so a null is the
 * plan saying the mechanism does not exist. {@code ExplorationParams} is always present and holds
 * both the base keys and the keys of the active standalone features (menu action, form completion,
 * epsilon adaptation, the guards, and so on) — features that gate a behavior but not a subsystem,
 * and whose parameters have nowhere else to live.
 */
public final class RunSpec {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** The plan digest is the first 16 hex characters of the SHA-256 — enough to join on. */
    private static final int DIGEST_LENGTH = 16;
    /** What {@link #digestProperties} hashes in place of a properties file that was not there. */
    private static final String ABSENT_FILE = "<absent>";
    /** The properties digest of a run that read no file at all — the JVM-test case. */
    public static final String PROPERTIES_DIGEST_NONE = digestProperties(null, null);

    /** The agent types the plan can express. Anything else aborts instead of falling back. */
    public static final List<String> AGENT_TYPES =
            Collections.unmodifiableList(Arrays.asList("sata", "random", "replay"));

    /** The name reported for a plan written out key by key, with no {@code ape.preset}. */
    public static final String PRESET_EXPLICIT = "explicit";

    private final String presetName;
    private final long seed;
    private final String runId;
    private final String corpusBasis;
    private final String agentType;
    private final String replayLog;
    private final Set<Feature> features;
    private final ExplorationParams exploration;
    private final MopParams mop;
    private final LlmParams llm;
    private final TelemetryParams telemetry;
    private final Map<String, String> effective;
    private final List<String> inert;
    private final String digest;
    private final String propertiesDigest;

    private RunSpec(String presetName, long seed, String runId, String corpusBasis,
            String agentType, String replayLog, Set<Feature> features,
            ExplorationParams exploration, MopParams mop, LlmParams llm, TelemetryParams telemetry,
            Map<String, String> effective, List<String> inert, String digest,
            String propertiesDigest) {
        this.presetName = presetName;
        this.seed = seed;
        this.runId = runId;
        this.corpusBasis = corpusBasis;
        this.agentType = agentType;
        this.replayLog = replayLog;
        this.features = Collections.unmodifiableSet(features);
        this.exploration = exploration;
        this.mop = mop;
        this.llm = llm;
        this.telemetry = telemetry;
        this.effective = Collections.unmodifiableMap(effective);
        this.inert = Collections.unmodifiableList(inert);
        this.digest = digest;
        this.propertiesDigest = propertiesDigest;
    }

    // ---------------------------------------------------------------------------------------
    // Resolution
    // ---------------------------------------------------------------------------------------

    /**
     * Resolves a plan from properties entries and the command line, for a caller that read no
     * files — JVM tests, and the preset path. The properties digest is the both-files-absent
     * sentinel, which is the truthful value when nothing was read.
     */
    public static RunSpec resolve(Map<String, String> fileEntries, CliValues cli) {
        return resolve(fileEntries, cli, PROPERTIES_DIGEST_NONE);
    }

    /**
     * Resolves a plan.
     *
     * @param fileEntries the union of the two properties files' entries in load order, later file
     *        winning; file-loaded keys only, because JVM system properties are not a configuration
     *        channel for {@code ape.*} behavior
     * @param cli the command-line values: agent type, seed, replay log
     * @param propertiesDigest the digest of the raw bytes actually read, from
     *        {@link #digestProperties} — it is what proves <em>which file</em> the run saw, and it
     *        is computed where the bytes are, not re-derived from the parsed entries
     * @throws RunSpecException on every invalid input class; never returns a partially-valid plan
     */
    public static RunSpec resolve(Map<String, String> fileEntries, CliValues cli,
            String propertiesDigest) {
        Objects.requireNonNull(fileEntries, "fileEntries");
        Objects.requireNonNull(cli, "cli");

        String agentType = resolveAgentType(cli);

        // 1. Classify every key of the input before looking at a single value. Retired keys are
        //    caught here, ahead of value semantics, so ape.apePureMode=false reports the decision
        //    that retired it rather than passing as a harmless-looking false.
        for (Map.Entry<String, String> entry : fileEntries.entrySet()) {
            classify(entry.getKey());
        }

        // 2. Preset first, explicit keys on top. The merged result faces the same validation as a
        //    plan with no preset at all.
        Map<String, String> explicit = new LinkedHashMap<>();
        String presetName = PRESET_EXPLICIT;
        String requestedPreset = fileEntries.get(KeyOwnership.KEY_PRESET);
        if (requestedPreset != null) {
            presetName = requestedPreset;
            explicit.putAll(Presets.resolve(requestedPreset));
        }
        for (Map.Entry<String, String> entry : fileEntries.entrySet()) {
            if (!KeyOwnership.KEY_PRESET.equals(entry.getKey())) {
                explicit.put(entry.getKey(), entry.getValue());
            }
        }

        // 3. Parse every explicit value against its declared type, then apply the documented
        //    value-semantics clamps so everything downstream sees post-clamp values.
        Map<String, String> effective = new LinkedHashMap<>();
        for (String key : KeyOwnership.allKeys()) {
            String value = explicit.containsKey(key)
                    ? clamp(key, explicit.get(key))
                    : KeyOwnership.defaultOf(key);
            if (explicit.containsKey(key)) {
                KeyOwnership.typeOf(key).parse(key, value);
            }
            if (value != null) {
                effective.put(key, value);
            }
        }

        // 4. Derive the feature set. A feature enters the plan when its activation key says so and
        //    every dependency is already in. When it says so but a dependency is missing, the
        //    input decides the outcome: an explicit statement aborts, a jar default does not — a
        //    bare run must stay valid even though ape.activityTriggerEnabled defaults to true.
        Set<Feature> features = deriveFeatures(effective, explicit);

        // 5. The inert rule, the substitute for the old exemption registry: a key of an inactive
        //    feature is accepted only at its neutral value. You may state OFF for a mechanism that
        //    does not exist; you may not state ON.
        List<String> inert = applyInertRule(explicit, effective, features);

        Map<String, String> planValues = planValues(effective, features, inert);

        ExplorationParams exploration = new ExplorationParams(
                scope(planValues, features, ParamScope.EXPLORATION));
        MopParams mop = features.contains(Feature.MOP)
                ? new MopParams(scope(planValues, features, ParamScope.MOP)) : null;
        LlmParams llm = features.contains(Feature.LLM)
                ? new LlmParams(scope(planValues, features, ParamScope.LLM)) : null;
        TelemetryParams telemetry = features.contains(Feature.STEP_TELEMETRY)
                ? new TelemetryParams(scope(planValues, features, ParamScope.TELEMETRY)) : null;

        String digest = computeDigest(agentType, features, planValues);

        return new RunSpec(presetName, cli.seed(), fileEntries.get(KeyOwnership.KEY_RUN_ID),
                fileEntries.get(KeyOwnership.KEY_CORPUS_BASIS), agentType, cli.replayLog(),
                features, exploration, mop, llm, telemetry, planValues, inert, digest,
                propertiesDigest);
    }

    private static String resolveAgentType(CliValues cli) {
        String raw = cli.agentType();
        // A documented default is not a silent fallback: the deployment always passes --ape, and a
        // bare run is SATA because that is what the tool is.
        String type = raw == null ? "sata" : raw.trim();
        if (!AGENT_TYPES.contains(type)) {
            throw new RunSpecException(RunSpecException.Reason.UNKNOWN_AGENT_TYPE, "--ape",
                    "unknown agent type '" + type + "'; valid values are " + AGENT_TYPES);
        }
        if ("replay".equals(type) && (cli.replayLog() == null || cli.replayLog().isEmpty())) {
            throw new RunSpecException(RunSpecException.Reason.INVALID_COMBINATION, "--ape-replay",
                    "--ape replay requires --ape-replay <log>");
        }
        return type;
    }

    private static void classify(String key) {
        if (!key.startsWith("ape.")) {
            throw new RunSpecException(RunSpecException.Reason.FOREIGN_KEY, key,
                    "a properties file may only carry ape.* keys");
        }
        if (KeyOwnership.isRetired(key)) {
            throw new RunSpecException(RunSpecException.Reason.RETIRED_KEY, key,
                    KeyOwnership.retiredReason(key));
        }
        if (!KeyOwnership.isKnown(key)) {
            throw new RunSpecException(RunSpecException.Reason.UNKNOWN_KEY, key,
                    "no part of the plan owns this key");
        }
    }

    /**
     * The load-time clamps, preserved as clamps. They are documented value semantics — a cadence
     * of zero and a negative cap are meaningful mistakes with a defined reading — not typos, which
     * is why they do not abort.
     */
    private static String clamp(String key, String raw) {
        if (raw == null) {
            return null;
        }
        if ("ape.activityTriggerStagnationStep".equals(key)) {
            int value = (Integer) ValueType.INT.parse(key, raw);
            return value <= 0 ? "50" : raw;
        }
        if ("ape.activityTriggerMaxPerRun".equals(key)) {
            int value = (Integer) ValueType.INT.parse(key, raw);
            return value < 0 ? "0" : raw;
        }
        if ("ape.llmPercentage".equals(key)) {
            double value = (Double) ValueType.DOUBLE.parse(key, raw);
            return Double.toString(Math.max(0.0d, Math.min(1.0d, value)));
        }
        if ("ape.llmPercentageNoSubstrate".equals(key)) {
            double value = (Double) ValueType.DOUBLE.parse(key, raw);
            // The -1 sentinel means "no override"; any real negative collapses to it.
            return Double.toString(value < 0.0d ? -1.0d : Math.min(1.0d, value));
        }
        return raw;
    }

    private static Set<Feature> deriveFeatures(Map<String, String> effective,
            Map<String, String> explicit) {
        Set<Feature> active = EnumSet.noneOf(Feature.class);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Feature feature : Feature.values()) {
                if (active.contains(feature) || !feature.isActive(effective)) {
                    continue;
                }
                if (active.containsAll(feature.dependencies())) {
                    active.add(feature);
                    grew = true;
                }
            }
        }
        for (Feature feature : Feature.values()) {
            if (active.contains(feature) || !feature.isActive(effective)) {
                continue;
            }
            if (explicit.containsKey(feature.activationKey())) {
                Set<Feature> missing = new LinkedHashSet<>(feature.dependencies());
                missing.removeAll(active);
                throw new RunSpecException(RunSpecException.Reason.MISSING_DEPENDENCY,
                        feature.activationKey(),
                        feature + " requires " + missing + ", which the plan does not carry");
            }
        }
        return active;
    }

    private static List<String> applyInertRule(Map<String, String> explicit,
            Map<String, String> effective, Set<Feature> features) {
        List<String> inert = new ArrayList<>();
        for (String key : explicit.keySet()) {
            KeyOwnership.KeySpec spec = KeyOwnership.spec(key);
            if (spec.ownerKind() != KeyOwnership.OwnerKind.FEATURE
                    || features.contains(spec.feature())) {
                continue;
            }
            Feature owner = spec.feature();
            String neutral = owner.neutralValue(key);
            if (neutral == null) {
                // Only a path or a URL has no neutral value, and stating one is what activates its
                // feature — so reaching here means the feature is inactive for another reason.
                throw new RunSpecException(RunSpecException.Reason.MISSING_DEPENDENCY, key,
                        owner + " is not in the plan and " + key + " has no neutral value");
            }
            if (!spec.type().sameValue(key, effective.get(key), neutral)) {
                throw new RunSpecException(RunSpecException.Reason.MISSING_DEPENDENCY, key,
                        owner + " is not in the plan, so " + key + " is accepted only at its "
                                + "neutral value " + neutral + "; you may state OFF for an absent "
                                + "mechanism, not ON");
            }
            inert.add(key);
        }
        Collections.sort(inert);
        return inert;
    }

    /**
     * The effective values that are actually part of the plan: base keys, plus the keys of every
     * active feature. Keys of absent features are excluded — they parameterize nothing — and so
     * are the resolver's own keys, which select the plan rather than belong to it.
     */
    private static Map<String, String> planValues(Map<String, String> effective,
            Set<Feature> features, List<String> inert) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : effective.entrySet()) {
            KeyOwnership.KeySpec spec = KeyOwnership.spec(entry.getKey());
            if (spec.ownerKind() == KeyOwnership.OwnerKind.RESOLVER) {
                continue;
            }
            if (spec.ownerKind() == KeyOwnership.OwnerKind.FEATURE
                    && !features.contains(spec.feature())) {
                continue;
            }
            values.put(entry.getKey(), entry.getValue());
        }
        return values;
    }

    private enum ParamScope { EXPLORATION, MOP, LLM, TELEMETRY }

    private static ParamScope scopeOf(KeyOwnership.KeySpec spec) {
        if (spec.ownerKind() != KeyOwnership.OwnerKind.FEATURE) {
            return ParamScope.EXPLORATION;
        }
        Feature owner = spec.feature();
        if (owner == Feature.STEP_TELEMETRY) {
            return ParamScope.TELEMETRY;
        }
        if (owner == Feature.MOP || owner.dependencies().contains(Feature.MOP)) {
            return ParamScope.MOP;
        }
        if (owner == Feature.LLM || owner.dependencies().contains(Feature.LLM)) {
            return ParamScope.LLM;
        }
        return ParamScope.EXPLORATION;
    }

    private static Map<String, String> scope(Map<String, String> planValues, Set<Feature> features,
            ParamScope wanted) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : planValues.entrySet()) {
            if (scopeOf(KeyOwnership.spec(entry.getKey())) == wanted) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /**
     * The digest of the plan as interpreted: the agent type, the sorted feature names, and every
     * effective plan value in canonical form, sorted by key.
     *
     * <p>Three things are deliberately outside it. <b>Seed and run id</b>, so two runs of the same
     * arm share a digest — the digest identifies the arm, which is what drift auditing joins on.
     * <b>The preset name</b>, because the same plan expressed as a preset and written out key by
     * key must produce the same digest; the preset is how the plan was written, not what it is.
     * And <b>the corpus basis</b>, which is provenance about the run's place in a campaign rather
     * than a parameter of its behavior.
     *
     * <p>Values are canonicalized through their declared type, so {@code 0} and {@code 0.0} — the
     * same number written two ways — cannot produce two digests.
     */
    private static String computeDigest(String agentType, Set<Feature> features,
            Map<String, String> planValues) {
        List<String> featureNames = new ArrayList<>();
        for (Feature feature : features) {
            featureNames.add(feature.name());
        }
        Collections.sort(featureNames);

        StringBuilder canonical = new StringBuilder();
        canonical.append("agent=").append(agentType).append('\n');
        canonical.append("features=").append(String.join(",", featureNames)).append('\n');
        for (Map.Entry<String, String> entry : new TreeMap<>(planValues).entrySet()) {
            canonical.append(entry.getKey()).append('=')
                    .append(KeyOwnership.typeOf(entry.getKey())
                            .canonical(entry.getKey(), entry.getValue()))
                    .append('\n');
        }
        return sha256Hex(canonical.toString().getBytes(UTF_8)).substring(0, DIGEST_LENGTH);
    }

    /**
     * The digest of the properties input actually read: the raw bytes of
     * {@code /data/local/tmp/ape.properties} and {@code /sdcard/ape.properties}, in load order,
     * each preceded by its length so two files cannot be confused with one. A file that was not
     * there contributes a fixed sentinel, so "absent" and "empty" stay distinguishable.
     *
     * <p>This hashes bytes rather than parsed entries on purpose: it answers "which file did this
     * run see", which a digest of the entries — blind to comments, order and whitespace — cannot.
     */
    public static String digestProperties(byte[] dataLocalTmp, byte[] sdcard) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (byte[] content : new byte[][] {dataLocalTmp, sdcard}) {
                if (content == null) {
                    sha.update(ABSENT_FILE.getBytes(UTF_8));
                } else {
                    sha.update(("len=" + content.length + ":").getBytes(UTF_8));
                    sha.update(content);
                }
            }
            return toHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------------------------

    /** The preset that supplied the base vector, or {@code "explicit"} when none was named. */
    public String presetName() {
        return presetName;
    }

    public long seed() {
        return seed;
    }

    /**
     * The host-supplied {@code ape.runId}, or null when the harness supplied none — in which case
     * generating the identity belongs to {@code RunContext}, which owns the run's identity.
     */
    public String runId() {
        return runId;
    }

    /** The host-supplied {@code ape.corpusBasis}, or null. Echoed, and read by nothing. */
    public String corpusBasis() {
        return corpusBasis;
    }

    public String agentType() {
        return agentType;
    }

    /** The replay log path, non-null exactly when the agent type is {@code replay}. */
    public String replayLog() {
        return replayLog;
    }

    public Set<Feature> features() {
        return features;
    }

    public boolean has(Feature feature) {
        return features.contains(feature);
    }

    public ExplorationParams exploration() {
        return exploration;
    }

    /** Null exactly when {@link Feature#MOP} is absent from the plan. */
    public MopParams mop() {
        return mop;
    }

    /** Null exactly when {@link Feature#LLM} is absent from the plan. */
    public LlmParams llm() {
        return llm;
    }

    /** Null exactly when {@link Feature#STEP_TELEMETRY} is absent from the plan. */
    public TelemetryParams telemetry() {
        return telemetry;
    }

    /** Every effective plan value, keys of absent features excluded. */
    public Map<String, String> effectiveValues() {
        return effective;
    }

    /** The keys accepted at their neutral value for a feature the plan does not carry. */
    public List<String> inertKeys() {
        return inert;
    }

    /** Identifies the <em>arm</em>: two runs of the same effective plan share this digest. */
    public String digest() {
        return digest;
    }

    /** Identifies the <em>input</em>: which properties files, byte for byte, this run read. */
    public String propertiesDigest() {
        return propertiesDigest;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RunSpec)) {
            return false;
        }
        RunSpec that = (RunSpec) other;
        return seed == that.seed
                && Objects.equals(presetName, that.presetName)
                && Objects.equals(runId, that.runId)
                && Objects.equals(corpusBasis, that.corpusBasis)
                && Objects.equals(agentType, that.agentType)
                && Objects.equals(replayLog, that.replayLog)
                && features.equals(that.features)
                && effective.equals(that.effective)
                && inert.equals(that.inert)
                && Objects.equals(digest, that.digest)
                && Objects.equals(propertiesDigest, that.propertiesDigest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(presetName, seed, runId, corpusBasis, agentType, replayLog, features,
                effective, inert, digest, propertiesDigest);
    }

    @Override
    public String toString() {
        return "RunSpec[preset=" + presetName + " agent=" + agentType + " digest=" + digest
                + " features=" + features + "]";
    }

    // ---------------------------------------------------------------------------------------
    // Command-line values
    // ---------------------------------------------------------------------------------------

    /** The command-line half of the plan's input: what the harness passes to Monkey directly. */
    public static final class CliValues {
        private final String agentType;
        private final long seed;
        private final String replayLog;

        private CliValues(String agentType, long seed, String replayLog) {
            this.agentType = agentType;
            this.seed = seed;
            this.replayLog = replayLog;
        }

        /**
         * @param agentType the raw {@code --ape} value, or null when the flag was absent
         * @param seed the {@code -s} value. The current harness appends {@code -s} only when a
         *        seed is configured and none is, so this is the run's seed as it resolved — the
         *        plan records it faithfully rather than inventing one to make the field look full
         * @param replayLog the {@code --ape-replay} value, or null
         */
        public static CliValues of(String agentType, long seed, String replayLog) {
            return new CliValues(agentType, seed, replayLog);
        }

        public String agentType() {
            return agentType;
        }

        public long seed() {
            return seed;
        }

        public String replayLog() {
            return replayLog;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Parameter objects
    // ---------------------------------------------------------------------------------------

    /**
     * The shared shape of the four parameter objects: an immutable, scope-checked view of the
     * effective values its owner is responsible for.
     *
     * <p>The typed lookups take a key because stage 2 migrates only the read sites it is forced to
     * — the five {@code Config} fields that were non-final purely so tests could toggle them. The
     * rest of the tree still reads static finals, and stage 3 is where those read sites move and
     * grow named accessors. A lookup here is not the stringly-typed global it replaces: the key is
     * validated against this object's own scope, so asking {@code ExplorationParams} for an LLM
     * parameter is a loud failure rather than a null.
     */
    public abstract static class Params {
        private final Map<String, String> values;

        Params(Map<String, String> values) {
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        /** Every key this object holds, with its effective value. */
        public final Map<String, String> values() {
            return values;
        }

        public final boolean has(String key) {
            return values.containsKey(key);
        }

        public final String str(String key) {
            return require(key, ValueType.STRING);
        }

        public final boolean bool(String key) {
            return (Boolean) ValueType.BOOLEAN.parse(key, require(key, ValueType.BOOLEAN));
        }

        public final int integer(String key) {
            return (Integer) ValueType.INT.parse(key, require(key, ValueType.INT));
        }

        public final long lng(String key) {
            return (Long) ValueType.LONG.parse(key, require(key, ValueType.LONG));
        }

        public final double dbl(String key) {
            return (Double) ValueType.DOUBLE.parse(key, require(key, ValueType.DOUBLE));
        }

        private String require(String key, ValueType expected) {
            String value = values.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                        getClass().getSimpleName() + " does not hold " + key);
            }
            ValueType declared = KeyOwnership.typeOf(key);
            if (declared != expected) {
                throw new IllegalArgumentException(
                        key + " is declared " + declared + ", not " + expected);
            }
            return value;
        }

        @Override
        public final boolean equals(Object other) {
            return other != null && other.getClass() == getClass()
                    && ((Params) other).values.equals(values);
        }

        @Override
        public final int hashCode() {
            return getClass().hashCode() * 31 + values.hashCode();
        }

        @Override
        public final String toString() {
            return getClass().getSimpleName() + values.keySet();
        }
    }

    /** Always present: the base exploration keys plus the active standalone features' keys. */
    public static final class ExplorationParams extends Params {
        private ExplorationParams(Map<String, String> values) {
            super(values);
        }

        /** Type-aware input fuzzing; false when the key is absent. */
        public boolean fuzzInputTyped() {
            return has("ape.fuzzInputTyped") && bool("ape.fuzzInputTyped");
        }

        /** The per-action coverage boost; zero when the coverage feature is absent. */
        public int coverageBoostWeight() {
            return has("ape.coverageBoostWeight") ? integer("ape.coverageBoostWeight") : 0;
        }

        /** Form filling and submission; false when the form feature is absent. */
        public boolean formCompletionEnabled() {
            return has("ape.formCompletionEnabled") && bool("ape.formCompletionEnabled");
        }
    }

    /** Present exactly when the plan carries {@link Feature#MOP}. */
    public static final class MopParams extends Params {
        private MopParams(Map<String, String> values) {
            super(values);
        }

        public String dataPath() {
            return str("ape.mopDataPath");
        }

        /** Whether a package or mainActivity mismatch rejects the MOP data instead of warning. */
        public boolean strictPackageMatch() {
            return bool("ape.mopStrictPackageMatch");
        }

        /**
         * The direct-MOP widget boost. Owned by {@code MOP} itself, so a plan that carries this
         * object carries the key — but read defensively like its siblings, since the caller that
         * derives {@code ScoringParams} treats an absent weight and a zero weight alike.
         */
        public int weightDirect() {
            return has("ape.mopWeightDirect") ? integer("ape.mopWeightDirect") : 0;
        }

        /** The transitive-MOP widget boost; see {@link #weightDirect()} on presence. */
        public int weightTransitive() {
            return has("ape.mopWeightTransitive") ? integer("ape.mopWeightTransitive") : 0;
        }

        /** Zero when the WTG feature is absent. */
        public int weightWtg() {
            return has("ape.mopWeightWtg") ? integer("ape.mopWeightWtg") : 0;
        }

        /** The generic unvisited-activity frontier boost; zero when that feature is absent. */
        public int frontierBoostWeight() {
            return has("ape.frontierBoostWeight") ? integer("ape.frontierBoostWeight") : 0;
        }

        /** Zero when the gateway feature is absent. */
        public int weightOpenMenu() {
            return has("ape.mopWeightOpenMenu") ? integer("ape.mopWeightOpenMenu") : 0;
        }

        /**
         * The MOP-conditioned frontier boost (unvisited AND MOP-bearing), the strictly narrower
         * sibling of {@link #frontierBoostWeight()}. Zero when the MOP-frontier feature is absent,
         * which is its jar default.
         */
        public int frontierWeight() {
            return has("ape.mopFrontierWeight") ? integer("ape.mopFrontierWeight") : 0;
        }

        /** False when the launcher feature is absent. */
        public boolean activityTriggerEnabled() {
            return has("ape.activityTriggerEnabled") && bool("ape.activityTriggerEnabled");
        }

        /**
         * Selection passes between two launcher firings. Read only when the launcher feature is
         * present, which is the only condition under which a launcher exists to read it.
         */
        public int activityTriggerStagnationStep() {
            return integer("ape.activityTriggerStagnationStep");
        }

        /** Per-run launch budget; zero means unlimited (INV-CT-12). */
        public int activityTriggerMaxPerRun() {
            return integer("ape.activityTriggerMaxPerRun");
        }

        /**
         * The probability that a selection pass fires a component trigger. Read only when the
         * component-trigger feature is present — and since this key being positive is what
         * activates that feature, a plan that carries the feature always carries the key.
         */
        public double componentPercentage() {
            return dbl("ape.componentPercentage");
        }
    }

    /** Present exactly when the plan carries {@link Feature#LLM}. */
    public static final class LlmParams extends Params {
        private LlmParams(Map<String, String> values) {
            super(values);
        }

        public String url() {
            return str("ape.llmUrl");
        }

        public String model() {
            return str("ape.llmModel");
        }
    }

    /** Present exactly when the plan carries {@link Feature#STEP_TELEMETRY}. Stage 4 grows it. */
    public static final class TelemetryParams extends Params {
        private TelemetryParams(Map<String, String> values) {
            super(values);
        }
    }
}
