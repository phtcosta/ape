package com.android.commands.monkey.ape.oracle;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * rearch-01 tasks 4.2/4.3 — the golden itself: a header plus one {@link DecisionRecord} per step,
 * the reader and writer that move it to and from NDJSON, and the comparator that is the acceptance
 * gate for {@code rearch-02} and {@code rearch-03} (design D4/D8).
 *
 * <h2>The asymmetry between reading and writing</h2>
 * Reading is free; writing is not. {@link #write} refuses unless {@code -Dape.oracle.regenerate=true}
 * was passed on the command line (INV-ORA-04), and {@link #read} fails with the regeneration
 * instructions when a golden is absent instead of quietly creating one (task 4.3). Together those
 * two rules are the whole of the safety property: a red comparison can never be turned green by the
 * suite itself, only by a person who decided to regenerate and then had to commit the diff.
 *
 * <h2>Where a golden lives, and why the path is relative</h2>
 * {@link #path} resolves {@code src/test/resources/goldens/<preset>/<scenario>.ndjson} against the
 * working directory, which surefire leaves at {@code ${basedir}} — so it is the <b>source tree</b>,
 * not {@code target/test-classes}. That is deliberate: a capture then shows up in {@code git status}
 * where it can be reviewed, and a comparison reads the same file the capture wrote. Loading goldens
 * through {@code getResource()} would read the build's copy instead, and a capture could disagree
 * with the comparison it is supposed to feed without either side noticing.
 *
 * <h2>What the header comparison covers</h2>
 * {@code preset}, {@code scenario}, {@code seed} and {@code fixture} are compared and a mismatch is
 * reported as its own failure, because a golden captured against a different fixture or seed is a
 * different golden — calling that a decision divergence would send the reader hunting through the
 * ladder for a difference that is in the inputs. {@code capturedAt} is deliberately <b>not</b>
 * compared: it necessarily differs between the capture and every later run.
 */
public final class GoldenFile {

    /** The only trigger for capture (write) mode — INV-ORA-04. Never set in CI. */
    public static final String REGENERATE_PROPERTY = "ape.oracle.regenerate";

    /** Optional provenance of a capture: the commit the goldens were captured at. */
    public static final String CAPTURED_AT_PROPERTY = "ape.oracle.capturedAt";

    /**
     * What {@link #capturedAt()} stamps when {@link #CAPTURED_AT_PROPERTY} is absent (owner
     * decision, 2026-08-03). Capture never blocks on provenance; the cost is accepted and stated:
     * a golden captured without the property records no commit, and the reviewer of the capture
     * diff is the only thing standing between that and an unattributable golden. The exact command
     * that supplies it belongs to the goldens README (task 8.1).
     */
    static final String CAPTURED_AT_UNKNOWN = "unknown";

    private static final String GOLDEN_ROOT = "src/test/resources/goldens";
    private static final String GOLDEN_SUFFIX = ".ndjson";
    private static final String HEADER_KIND = "header";

    /**
     * The golden's first record: which capture this is, so a comparison can tell a changed decision
     * apart from a changed input.
     */
    public static final class Header {

        /** The header fields a comparison covers — {@code capturedAt} is excluded, see the class javadoc. */
        static final String[] COMPARED_FIELDS = {"preset", "scenario", "seed", "fixture"};

        private final String preset;
        private final String scenario;
        private final long seed;
        private final String fixture;
        private final String capturedAt;

        /**
         * @param preset     the preset label, which is also the golden's directory
         * @param scenario   the scenario name, which is also the golden's file name
         * @param seed       the scenario's declared seed — it seeds both RNG streams
         * @param fixture    the MopData fixture file name, or null for a preset that loads none
         * @param capturedAt the capturing commit, normally {@link #capturedAt()}
         */
        public Header(String preset, String scenario, long seed, String fixture, String capturedAt) {
            this.preset = preset;
            this.scenario = scenario;
            this.seed = seed;
            this.fixture = fixture;
            this.capturedAt = capturedAt;
        }

        public String getPreset() { return preset; }
        public String getScenario() { return scenario; }
        public long getSeed() { return seed; }
        public String getFixture() { return fixture; }
        public String getCapturedAt() { return capturedAt; }

        String value(String field) {
            switch (field) {
                case "preset": return preset;
                case "scenario": return scenario;
                case "seed": return Long.toString(seed);
                case "fixture": return fixture;
                case "capturedAt": return capturedAt;
                default: throw new IllegalArgumentException("unknown header field: " + field);
            }
        }

        /** The header line, in the same fixed key order and for the same reason as a record's. */
        public String toJsonLine() {
            StringBuilder line = new StringBuilder("{\"kind\":").append(JSONObject.quote(HEADER_KIND))
                    .append(",\"preset\":").append(JSONObject.quote(preset))
                    .append(",\"scenario\":").append(JSONObject.quote(scenario))
                    .append(",\"seed\":").append(seed);
            if (fixture != null) {
                line.append(",\"fixture\":").append(JSONObject.quote(fixture));
            }
            return line.append(",\"capturedAt\":").append(JSONObject.quote(capturedAt))
                    .append('}').toString();
        }

        /** See {@code DecisionRecord.parse} for why the {@code JSONException} is caught here. */
        static Header parse(String line) {
            try {
                JSONObject json = new JSONObject(line);
                if (!HEADER_KIND.equals(json.optString("kind", null))) {
                    throw new IllegalStateException(
                            "the golden's first line is not a header record: " + line);
                }
                return new Header(
                        json.getString("preset"),
                        json.getString("scenario"),
                        json.getLong("seed"),
                        json.has("fixture") ? json.getString("fixture") : null,
                        json.getString("capturedAt"));
            } catch (JSONException malformed) {
                throw new IllegalStateException("malformed golden header: " + line, malformed);
            }
        }

        @Override
        public String toString() {
            return toJsonLine();
        }
    }

    private final Header header;
    private final List<DecisionRecord> records;

    public GoldenFile(Header header, List<DecisionRecord> records) {
        this.header = header;
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    public Header getHeader() { return header; }
    public List<DecisionRecord> getRecords() { return records; }

    // ---- capture-mode gate -------------------------------------------------------------------

    /** Whether the developer asked for capture mode explicitly. False in the default build and CI. */
    public static boolean captureRequested() {
        return Boolean.getBoolean(REGENERATE_PROPERTY);
    }

    /** The commit stamped into a capture's header, or {@code unknown} when none was supplied. */
    public static String capturedAt() {
        return System.getProperty(CAPTURED_AT_PROPERTY, CAPTURED_AT_UNKNOWN);
    }

    /** The golden of a preset's scenario, resolved against the source tree — see the class javadoc. */
    public static Path path(String preset, String scenario) {
        return Paths.get(GOLDEN_ROOT, preset, scenario + GOLDEN_SUFFIX);
    }

    // ---- writer ------------------------------------------------------------------------------

    /**
     * Writes the golden — header line first, then one line per record — creating the preset's
     * directory if needed.
     *
     * @throws IllegalStateException when capture mode was not requested. The refusal lives here,
     *         in the only method that can write, so INV-ORA-04 holds by construction rather than by
     *         every caller remembering to ask.
     */
    public void write(Path file) throws IOException {
        if (!captureRequested()) {
            throw new IllegalStateException("refusing to write " + file
                    + ": capture mode is off. Goldens are written only under -D"
                    + REGENERATE_PROPERTY + "=true (INV-ORA-04).");
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder content = new StringBuilder(header.toJsonLine()).append('\n');
        for (DecisionRecord record : records) {
            content.append(record.toJsonLine()).append('\n');
        }
        Files.write(file, content.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---- reader ------------------------------------------------------------------------------

    /**
     * Reads a committed golden.
     *
     * @throws AssertionError when the file does not exist — carrying the regeneration instructions
     *         (task 4.3). Compare mode never auto-captures: a missing golden is a decision for a
     *         person to make, not a gap for the suite to fill in and then report as green.
     */
    public static GoldenFile read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new AssertionError(missingGolden(file));
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("the golden is empty: " + file);
        }
        Header parsedHeader = Header.parse(lines.get(0));
        List<DecisionRecord> parsedRecords = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty()) {
                throw new IllegalStateException(
                        "blank line " + (i + 1) + " in " + file + ": one record per line, no padding");
            }
            parsedRecords.add(DecisionRecord.parse(line));
        }
        return new GoldenFile(parsedHeader, parsedRecords);
    }

    private static String missingGolden(Path file) {
        return "golden not found: " + file + System.lineSeparator()
                + "Compare mode never auto-captures (INV-ORA-04). To capture it deliberately, run:"
                + System.lineSeparator()
                + "    mvn test -D" + REGENERATE_PROPERTY + "=true -D" + CAPTURED_AT_PROPERTY
                + "=$(git rev-parse HEAD)" + System.lineSeparator()
                + "then review the working-tree diff and commit it with the decision that motivated"
                + " it (procedure: " + GOLDEN_ROOT + "/README.md).";
    }

    // ---- comparator --------------------------------------------------------------------------

    /**
     * Replays {@code actual} against the committed golden, failing at the <b>first</b> divergent
     * field with everything needed to locate it: preset, scenario, step, field name, both values,
     * and how many records diverge in total (INV-ORA-06). The count is what tells a one-off apart
     * from a systematic shift — a single divergent record reads very differently from all of them.
     *
     * @throws AssertionError on any divergence, including a missing golden
     */
    public static void compare(Path golden, GoldenFile actual) throws IOException {
        GoldenFile expected = read(golden);
        String scope = expected.header.getPreset() + "/" + expected.header.getScenario();
        compareHeaders(golden, scope, expected.header, actual.header);

        List<DecisionRecord> goldenRecords = expected.records;
        List<DecisionRecord> actualRecords = actual.records;
        int common = Math.min(goldenRecords.size(), actualRecords.size());
        int total = Math.max(goldenRecords.size(), actualRecords.size());
        int divergent = Math.abs(goldenRecords.size() - actualRecords.size());
        for (int i = 0; i < common; i++) {
            if (!goldenRecords.get(i).equals(actualRecords.get(i))) {
                divergent++;
            }
        }

        // A record dropped or inserted mid-sequence surfaces here, as a divergence of the `step`
        // field, which is why `step` leads DecisionRecord.FIELDS: the report then names the golden
        // step that lost its counterpart rather than some downstream field that merely shifted.
        for (int i = 0; i < common; i++) {
            DecisionRecord goldenRecord = goldenRecords.get(i);
            DecisionRecord actualRecord = actualRecords.get(i);
            for (String field : DecisionRecord.FIELDS) {
                String goldenValue = goldenRecord.value(field);
                String actualValue = actualRecord.value(field);
                if (!Objects.equals(goldenValue, actualValue)) {
                    throw new AssertionError("golden divergence in " + scope + " at step "
                            + goldenRecord.getStep() + ": field '" + field + "' golden="
                            + render(goldenValue) + " actual=" + render(actualValue)
                            + tally(divergent, total, golden));
                }
            }
        }

        if (actualRecords.size() < goldenRecords.size()) {
            throw new AssertionError("golden divergence in " + scope + ": the replay ended early —"
                    + " the golden's record at step " + goldenRecords.get(common).getStep()
                    + " has no counterpart (" + actualRecords.size() + " replayed records, "
                    + goldenRecords.size() + " in the golden)" + tally(divergent, total, golden));
        }
        if (actualRecords.size() > goldenRecords.size()) {
            throw new AssertionError("golden divergence in " + scope + ": the replay has an extra"
                    + " record at step " + actualRecords.get(common).getStep() + " ("
                    + actualRecords.size() + " replayed records, " + goldenRecords.size()
                    + " in the golden)" + tally(divergent, total, golden));
        }
    }

    private static void compareHeaders(Path golden, String scope, Header expected, Header actual) {
        for (String field : Header.COMPARED_FIELDS) {
            String goldenValue = expected.value(field);
            String actualValue = actual.value(field);
            if (!Objects.equals(goldenValue, actualValue)) {
                throw new AssertionError("golden header mismatch in " + scope + ": field '" + field
                        + "' golden=" + render(goldenValue) + " actual=" + render(actualValue)
                        + " — a golden captured under a different " + field + " is a different"
                        + " golden, not a decision divergence (golden " + golden + ")");
            }
        }
    }

    /** Absent fields print as {@code absent}, so they never read as the literal string "null". */
    private static String render(String value) {
        return value == null ? "absent" : "'" + value + "'";
    }

    private static String tally(int divergent, int total, Path golden) {
        return " (" + divergent + " of " + total + " records diverge; golden " + golden + ")";
    }
}
