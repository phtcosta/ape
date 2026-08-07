package com.android.commands.monkey.ape.oracle;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/**
 * rearch-01 task 4.1 — one selection step of a golden: what {@code selectNewActionNonnull()}
 * returned and which mechanism returned it (design D4).
 *
 * <p><b>Absent, never null.</b> A field that does not apply to a step is left out of the line
 * entirely rather than written as {@code null}: {@code pickChannel} and {@code decisionSource} for
 * a return that is not a {@code ModelAction}, {@code target} for a targetless action, {@code llm}
 * for a preset with no LLM. The distinction is not cosmetic — it is what keeps an {@code aperv}
 * golden textually free of the LLM axis, so a diff shows only what the preset actually has.
 *
 * <p><b>Why the line is built by hand instead of through {@code JSONObject.toString()}.</b>
 * {@code org.json}'s {@code JSONObject} is {@code HashMap}-backed, so its key order is whatever the
 * hash gives it and is not part of any contract. Emitting through it would let a golden churn on a
 * JVM or library change that altered nothing about the decisions — a diff that means nothing, in
 * the one artifact whose entire value is that a diff means something. So {@link #toJsonLine} writes
 * the keys in the fixed order of {@link #FIELDS}, escaping through {@code JSONObject.quote}, and
 * {@link #parse} reads back through {@code new JSONObject(line)}. Writer and reader are still the
 * same serializer, which is what the spec asks; the round-trip test is what proves it.
 */
public final class DecisionRecord {

    /**
     * The key order of a record line, and the order {@code GoldenFile} reports divergences in.
     * {@code step} leads because it is the coordinate every other field is read against.
     */
    static final String[] FIELDS =
            {"step", "actionType", "target", "decisionSource", "pickChannel", "llm"};

    private final int step;
    private final String actionType;
    private final String target;
    private final String decisionSource;
    private final String pickChannel;
    private final String llm;

    /**
     * @param step           the selection step, monotonically increasing from the scenario's first
     * @param actionType     the {@code ActionType} name; {@code EVENT_TRIGGER_ACTIVITY} for a
     *                       launcher step, whose candidate class goes in {@code target}
     * @param target         the action's {@code Name.toXPath()}; null for a targetless action
     * @param decisionSource the returned action's {@code DecisionSource} name; null when the return
     *                       was not a {@code ModelAction}
     * @param pickChannel    the returned action's {@code PickChannel} label
     *                       ({@code ModelAction.PickChannel.getLabel()}, e.g.
     *                       {@code short_circuit_unvisited}); null on the same condition
     * @param llm            {@code ScriptedLlm.Provenance.getLabel()} —
     *                       {@code accepted | declined | timeout | not_routed}; null for a preset
     *                       with no LLM
     */
    public DecisionRecord(int step, String actionType, String target, String decisionSource,
                          String pickChannel, String llm) {
        this.step = step;
        this.actionType = actionType;
        this.target = target;
        this.decisionSource = decisionSource;
        this.pickChannel = pickChannel;
        this.llm = llm;
    }

    public int getStep() { return step; }
    public String getActionType() { return actionType; }
    public String getTarget() { return target; }
    public String getDecisionSource() { return decisionSource; }
    public String getPickChannel() { return pickChannel; }
    public String getLlm() { return llm; }

    /**
     * The value of {@code field} as the comparator reports it, or null when the field is absent
     * from this record. Reading fields by name is what lets the comparator name the first divergent
     * one without a branch per field.
     */
    String value(String field) {
        switch (field) {
            case "step": return Integer.toString(step);
            case "actionType": return actionType;
            case "target": return target;
            case "decisionSource": return decisionSource;
            case "pickChannel": return pickChannel;
            case "llm": return llm;
            default: throw new IllegalArgumentException("unknown DecisionRecord field: " + field);
        }
    }

    /** One physical line of the golden: the fixed key order, absent fields omitted. */
    public String toJsonLine() {
        StringBuilder line = new StringBuilder("{\"step\":").append(step);
        for (String field : FIELDS) {
            if (!"step".equals(field)) {
                appendIfPresent(line, field, value(field));
            }
        }
        return line.append('}').toString();
    }

    private static void appendIfPresent(StringBuilder line, String key, String value) {
        if (value != null) {
            line.append(',').append(JSONObject.quote(key)).append(':').append(JSONObject.quote(value));
        }
    }

    /**
     * Reads back a line written by {@link #toJsonLine}. {@code step} and {@code actionType} are
     * mandatory: a line missing either is a corrupt golden, and it fails here naming the line
     * rather than becoming a silently empty record that then compares against something.
     *
     * <p>The {@code JSONException} is caught rather than declared because the two org.json
     * implementations on this project's classpaths disagree about it — the dalvik stub compiled
     * against makes it checked, the real jar the tests run on makes it a
     * {@code RuntimeException} — so declaring it would push a compile-time-only obligation onto
     * every capture test.
     */
    public static DecisionRecord parse(String line) {
        try {
            JSONObject json = new JSONObject(line);
            return new DecisionRecord(
                    json.getInt("step"),
                    json.getString("actionType"),
                    optional(json, "target"),
                    optional(json, "decisionSource"),
                    optional(json, "pickChannel"),
                    optional(json, "llm"));
        } catch (JSONException malformed) {
            throw new IllegalStateException("malformed golden record: " + line, malformed);
        }
    }

    private static String optional(JSONObject json, String key) throws JSONException {
        return json.has(key) ? json.getString(key) : null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DecisionRecord)) {
            return false;
        }
        DecisionRecord that = (DecisionRecord) other;
        return step == that.step
                && Objects.equals(actionType, that.actionType)
                && Objects.equals(target, that.target)
                && Objects.equals(decisionSource, that.decisionSource)
                && Objects.equals(pickChannel, that.pickChannel)
                && Objects.equals(llm, that.llm);
    }

    @Override
    public int hashCode() {
        return Objects.hash(step, actionType, target, decisionSource, pickChannel, llm);
    }

    /** The golden line itself — the form a failure message is most useful in. */
    @Override
    public String toString() {
        return toJsonLine();
    }
}
