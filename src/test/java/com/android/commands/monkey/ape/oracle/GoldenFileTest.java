package com.android.commands.monkey.ape.oracle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * rearch-01 task 4.4 — the golden format's own tests. They cover the two properties every later
 * golden silently rests on: that a decision survives the trip to disk and back unchanged, and that
 * the comparator actually notices when it does not.
 *
 * <p>No agent, no ladder, no scenario: the record lists here are written by hand. The driver that
 * turns a returned {@code Action} into a {@code DecisionRecord} is task 5.1, and pulling it in
 * early would mean these tests fail for two unrelated reasons at once.
 *
 * <p>The comparator tests deliberately assert on the <i>message</i>, not just on the failure.
 * INV-ORA-06 exists because the audience for a divergence is someone mid-migration in stage 2 or 3
 * who needs to know which step to look at; a bare {@code AssertionError} would satisfy the type
 * signature and none of the purpose.
 */
public class GoldenFileTest {

    private static final long SEED = 42L;
    private static final String FIXTURE = "cryptoapp.apk.gh60-fresh.json";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    // ---- fixtures ---------------------------------------------------------------------------

    private static GoldenFile.Header header(String fixture) {
        return new GoldenFile.Header("llm_mop", "baseline", SEED, fixture, "5f62b3f");
    }

    /**
     * Three steps spanning the format's shapes: a fully-populated SATA pick, a launcher step whose
     * return is not a {@code ModelAction} (so it carries no channel or source), and a targetless
     * action under a preset with no LLM (so it carries no target and no {@code llm}).
     */
    private static List<DecisionRecord> threeRecords() {
        return Arrays.asList(
                new DecisionRecord(0, "MODEL_CLICK", "//*[@resource-id='btn_ok']",
                        "SATA", "roulette_early", "not_routed"),
                new DecisionRecord(1, "EVENT_TRIGGER_ACTIVITY", "com.example.SettingsActivity",
                        null, null, "declined"),
                new DecisionRecord(2, "MODEL_BACK", null, "SATA", "sata_other", null));
    }

    /**
     * Writes under a temporarily-set capture flag. The property is cleared in a {@code finally} for
     * a reason that is not hygiene: surefire runs the whole suite in one JVM, so a leaked flag
     * would put every later test in capture mode — the one state INV-ORA-04 exists to keep out of
     * a default build.
     */
    private static void capture(GoldenFile golden, Path file) throws IOException {
        System.setProperty(GoldenFile.REGENERATE_PROPERTY, "true");
        try {
            golden.write(file);
        } finally {
            System.clearProperty(GoldenFile.REGENERATE_PROPERTY);
        }
    }

    private Path goldenWith(GoldenFile.Header header, List<DecisionRecord> records)
            throws IOException {
        Path file = temp.getRoot().toPath().resolve("llm_mop").resolve("baseline.ndjson");
        capture(new GoldenFile(header, records), file);
        return file;
    }

    /**
     * Returns the divergence the comparison must raise. Catching around a {@code fail()} would
     * swallow JUnit's own {@code AssertionError} and report the wrong thing, so the miss is
     * signalled with a distinct type.
     */
    private static AssertionError divergenceOf(Path golden, GoldenFile replay) throws IOException {
        try {
            GoldenFile.compare(golden, replay);
        } catch (AssertionError divergence) {
            return divergence;
        }
        throw new IllegalStateException("the comparison passed, but the replay was meant to diverge");
    }

    // ---- round trip -------------------------------------------------------------------------

    @Test
    public void roundTripPreservesEveryField() throws Exception {
        GoldenFile written = new GoldenFile(header(FIXTURE), threeRecords());
        Path file = goldenWith(header(FIXTURE), threeRecords());

        GoldenFile read = GoldenFile.read(file);

        assertEquals("llm_mop", read.getHeader().getPreset());
        assertEquals("baseline", read.getHeader().getScenario());
        assertEquals(SEED, read.getHeader().getSeed());
        assertEquals(FIXTURE, read.getHeader().getFixture());
        assertEquals("5f62b3f", read.getHeader().getCapturedAt());
        assertEquals(written.getRecords(), read.getRecords());

        // Field by field on the record that exercises every slot, since equals() would also be
        // satisfied by two records that are wrong in the same way.
        DecisionRecord first = read.getRecords().get(0);
        assertEquals(0, first.getStep());
        assertEquals("MODEL_CLICK", first.getActionType());
        assertEquals("//*[@resource-id='btn_ok']", first.getTarget());
        assertEquals("SATA", first.getDecisionSource());
        assertEquals("roulette_early", first.getPickChannel());
        assertEquals("not_routed", first.getLlm());
    }

    @Test
    public void absentFieldsAreOmittedNotNulled() throws Exception {
        Path file = goldenWith(header(null), threeRecords());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        // The launcher step's return is not a ModelAction, so it has no provenance to record...
        assertFalse(lines.get(2), lines.get(2).contains("decisionSource"));
        assertFalse(lines.get(2), lines.get(2).contains("pickChannel"));
        // ...and a preset with no fixture leaves the header's fixture out entirely.
        assertFalse(lines.get(0), lines.get(0).contains("fixture"));
        // Nothing anywhere is written as a JSON null: absent is the only way to say "not applicable".
        for (String line : lines) {
            assertFalse(line, line.contains("null"));
        }

        GoldenFile read = GoldenFile.read(file);
        assertEquals(null, read.getHeader().getFixture());
        assertEquals(null, read.getRecords().get(1).getDecisionSource());
        assertEquals(null, read.getRecords().get(2).getTarget());
        assertEquals(null, read.getRecords().get(2).getLlm());
    }

    @Test
    public void keyOrderIsFixedRatherThanHashOrder() {
        // org.json's JSONObject is HashMap-backed, so emitting through its toString() would let a
        // golden churn on a change that altered no decision. The lines are asserted literally
        // because that churn is exactly what a looser assertion would miss.
        assertEquals("{\"step\":0,\"actionType\":\"MODEL_CLICK\","
                        + "\"target\":\"//*[@resource-id='btn_ok']\",\"decisionSource\":\"SATA\","
                        + "\"pickChannel\":\"roulette_early\",\"llm\":\"not_routed\"}",
                threeRecords().get(0).toJsonLine());
        assertEquals("{\"step\":2,\"actionType\":\"MODEL_BACK\",\"decisionSource\":\"SATA\","
                        + "\"pickChannel\":\"sata_other\"}",
                threeRecords().get(2).toJsonLine());
        assertEquals("{\"kind\":\"header\",\"preset\":\"llm_mop\",\"scenario\":\"baseline\","
                        + "\"seed\":42,\"fixture\":\"" + FIXTURE + "\","
                        + "\"capturedAt\":\"5f62b3f\"}",
                header(FIXTURE).toJsonLine());
    }

    @Test
    public void eachRecordOccupiesExactlyOnePhysicalLine() throws Exception {
        // A widget name really can carry a newline in this system, so escaping is not hypothetical:
        // an unescaped one would split a record in two and shift every step index after it.
        List<DecisionRecord> records = new ArrayList<>(threeRecords());
        records.add(new DecisionRecord(3, "MODEL_CLICK", "//*[@text='line\nbreak\"quoted']",
                "MOP", "short_circuit_unvisited", "accepted"));
        Path file = goldenWith(header(FIXTURE), records);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals("one header line plus one line per record", 1 + records.size(), lines.size());
        assertEquals(records, GoldenFile.read(file).getRecords());
    }

    // ---- comparator (INV-ORA-06) --------------------------------------------------------------

    @Test
    public void identicalReplayComparesGreen() throws Exception {
        Path file = goldenWith(header(FIXTURE), threeRecords());

        GoldenFile.compare(file, new GoldenFile(header(FIXTURE), threeRecords()));
    }

    @Test
    public void changedFieldIsReportedWithPresetScenarioStepAndBothValues() throws Exception {
        Path file = goldenWith(header(FIXTURE), threeRecords());
        List<DecisionRecord> replayed = new ArrayList<>(threeRecords());
        replayed.set(1, new DecisionRecord(1, "EVENT_TRIGGER_ACTIVITY",
                "com.example.OtherActivity", null, null, "declined"));

        AssertionError divergence =
                divergenceOf(file, new GoldenFile(header(FIXTURE), replayed));

        String message = divergence.getMessage();
        assertTrue(message, message.contains("llm_mop/baseline"));
        assertTrue(message, message.contains("step 1"));
        assertTrue(message, message.contains("field 'target'"));
        assertTrue(message, message.contains("com.example.SettingsActivity"));
        assertTrue(message, message.contains("com.example.OtherActivity"));
        assertTrue("the divergent-record count locates a one-off against a systematic shift",
                message.contains("1 of 3 records diverge"));
    }

    @Test
    public void anAbsentFieldReportsAsAbsentRatherThanNull() throws Exception {
        Path file = goldenWith(header(FIXTURE), threeRecords());
        List<DecisionRecord> replayed = new ArrayList<>(threeRecords());
        replayed.set(2, new DecisionRecord(2, "MODEL_BACK", null, "SATA", "sata_other", "declined"));

        String message = divergenceOf(file, new GoldenFile(header(FIXTURE), replayed)).getMessage();

        assertTrue(message, message.contains("field 'llm' golden=absent actual='declined'"));
    }

    @Test
    public void aMissingRecordIsCaughtAtTheStepThatLostIt() throws Exception {
        Path file = goldenWith(header(FIXTURE), threeRecords());
        List<DecisionRecord> replayed = new ArrayList<>(threeRecords());
        replayed.remove(1);

        String message = divergenceOf(file, new GoldenFile(header(FIXTURE), replayed)).getMessage();

        // The drop surfaces as a `step` divergence at the golden step that lost its counterpart —
        // step 1 — not at the tail where the record count finally runs out.
        assertTrue(message, message.contains("step 1"));
        assertTrue(message, message.contains("field 'step'"));
        assertTrue(message, message.contains("2 of 3 records diverge"));
    }

    @Test
    public void aTruncatedReplayNamesTheFirstGoldenStepWithoutACounterpart() throws Exception {
        Path file = goldenWith(header(FIXTURE), threeRecords());
        List<DecisionRecord> replayed = new ArrayList<>(threeRecords());
        replayed.remove(2);

        String message = divergenceOf(file, new GoldenFile(header(FIXTURE), replayed)).getMessage();

        assertTrue(message, message.contains("ended early"));
        assertTrue(message, message.contains("step 2"));
        assertTrue(message, message.contains("1 of 3 records diverge"));
    }

    @Test
    public void anExtraRecordIsCaughtAtTheStepThatAddedIt() throws Exception {
        Path file = goldenWith(header(FIXTURE), threeRecords());
        List<DecisionRecord> replayed = new ArrayList<>(threeRecords());
        replayed.add(new DecisionRecord(3, "MODEL_MENU", null, "SATA", "sata_other", null));

        String message = divergenceOf(file, new GoldenFile(header(FIXTURE), replayed)).getMessage();

        assertTrue(message, message.contains("extra"));
        assertTrue(message, message.contains("step 3"));
        assertTrue(message, message.contains("1 of 4 records diverge"));
    }

    @Test
    public void aChangedFixtureIsAHeaderMismatchNotADecisionDivergence() throws Exception {
        Path file = goldenWith(header(FIXTURE), threeRecords());

        String message = divergenceOf(file,
                new GoldenFile(header("cryptoapp.apk.gh60.json"), threeRecords())).getMessage();

        assertTrue(message, message.contains("header mismatch"));
        assertTrue(message, message.contains("field 'fixture'"));
        assertTrue("a different input is not a changed decision, and must not read as one",
                message.contains("is a different golden"));
    }

    @Test
    public void capturedAtIsIgnoredByTheComparison() throws Exception {
        Path file = goldenWith(header(FIXTURE), threeRecords());

        // It necessarily differs between the capture and every later run, so comparing it would
        // make every golden red the moment it was committed.
        GoldenFile.compare(file, new GoldenFile(
                new GoldenFile.Header("llm_mop", "baseline", SEED, FIXTURE, "deadbee"),
                threeRecords()));
    }

    // ---- capture-mode gate (INV-ORA-04, task 4.3) ---------------------------------------------

    @Test
    public void missingGoldenFailsWithTheRegenerationInstructionsAndWritesNothing() throws Exception {
        Path absent = temp.getRoot().toPath().resolve("aperv").resolve("baseline.ndjson");

        AssertionError failure =
                divergenceOf(absent, new GoldenFile(header(null), threeRecords()));

        String message = failure.getMessage();
        assertTrue(message, message.contains("golden not found"));
        assertTrue(message, message.contains(GoldenFile.REGENERATE_PROPERTY + "=true"));
        assertTrue(message, message.contains(GoldenFile.CAPTURED_AT_PROPERTY));
        assertTrue(message, message.contains("git rev-parse HEAD"));
        assertFalse("a missing golden is never auto-captured", Files.exists(absent));
    }

    @Test
    public void defaultModeWritesNothingUnderTheGoldensTree() throws Exception {
        assertFalse("the default build must not be in capture mode", GoldenFile.captureRequested());
        Path target = GoldenFile.path("aperv", "default-mode-must-not-write-this");

        boolean refused = false;
        try {
            new GoldenFile(header(null), threeRecords()).write(target);
        } catch (IllegalStateException expected) {
            refused = true;
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains(GoldenFile.REGENERATE_PROPERTY));
        }

        assertTrue("write() must refuse outside capture mode", refused);
        assertFalse("nothing may appear under src/test/resources/goldens/", Files.exists(target));
    }

    @Test
    public void capturedAtStampsUnknownWhenNoCommitIsSupplied() {
        // Owner decision of 2026-08-03: capture never blocks on provenance. The cost is a golden
        // that records no commit, which only the reviewer of the capture diff will catch.
        assertFalse("the property must be unset for this to mean anything",
                System.getProperties().containsKey(GoldenFile.CAPTURED_AT_PROPERTY));
        assertEquals(GoldenFile.CAPTURED_AT_UNKNOWN, GoldenFile.capturedAt());

        System.setProperty(GoldenFile.CAPTURED_AT_PROPERTY, "5f62b3f");
        try {
            assertEquals("5f62b3f", GoldenFile.capturedAt());
        } finally {
            System.clearProperty(GoldenFile.CAPTURED_AT_PROPERTY);
        }
    }

    @Test
    public void goldensResolveAgainstTheSourceTree() {
        // Surefire sets no workingDirectory, so the relative path lands in the source tree rather
        // than target/test-classes — the property that lets a capture show up in `git status` and
        // a comparison read the file the capture wrote.
        assertEquals(java.nio.file.Paths.get(
                        "src/test/resources/goldens/llm_mop/preemption.ndjson"),
                GoldenFile.path("llm_mop", "preemption"));
    }
}
