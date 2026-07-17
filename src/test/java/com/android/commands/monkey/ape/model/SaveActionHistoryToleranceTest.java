package com.android.commands.monkey.ape.model;

import com.android.commands.monkey.ape.model.Model.ActionRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * INV-MODEL-15 (refinement-crash-recovery, task 3.1): {@link Model#saveActionHistory} tolerates a
 * record that can no longer be resolved — it skips that record, keeps writing the rest in order,
 * and reports the loss as {@code total=/skipped=}.
 *
 * <p>The stale-descriptor failure is injected by overriding {@link ActionRecord#resolveModelAction}
 * to throw the exact exception the production path raises ({@code IllegalStateException: Cannot find
 * widget} from {@code GUITree.pickNodes}, reached via {@code Model.java:87}). Building a real stale
 * GUITree would need the Android runtime; the call site under test, and the exception crossing it,
 * are identical either way.
 */
public class SaveActionHistoryToleranceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final PrintStream realOut = System.out;
    private ByteArrayOutputStream captured;

    @Before
    public void captureStdout() {
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
    }

    @After
    public void restoreStdout() {
        System.setOut(realOut);
    }

    /** A record whose model action can no longer be bound to its tree (post-refinement). */
    private static class StaleRecord extends ActionRecord {
        StaleRecord(long clockTimestamp, int agentTimestamp, Action action) {
            super(clockTimestamp, agentTimestamp, action, null);
        }

        @Override
        public void resolveModelAction() {
            throw new IllegalStateException("Cannot find widget");
        }
    }

    /** A plain (non-model) action: written verbatim, never resolved. */
    private static ActionRecord plain(long clock, int timestamp) {
        return new ActionRecord(clock, timestamp, new Action(ActionType.EVENT_NOP), null);
    }

    private List<String> lines(File f) throws Exception {
        return new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void testStaleRecordSkippedAndSurroundingRecordsStillWrittenInOrder() throws Exception {
        File out = folder.newFile("action-history.log");
        List<ActionRecord> history = Arrays.asList(
                plain(100L, 1),
                new StaleRecord(200L, 2, new ModelAction(null, ActionType.MODEL_CLICK)),
                plain(300L, 3));

        Model.saveActionHistory(out, history); // must not throw

        List<String> written = lines(out);
        assertEquals("the two resolvable records survive; the stale one is skipped", 2, written.size());
        assertTrue("first record written first: " + written, written.get(0).startsWith("100 "));
        assertTrue("last record still written after the failure: " + written,
                written.get(1).startsWith("300 "));

        String log = captured.toString();
        assertTrue("the skip is warned with its cause: " + log, log.contains("Cannot find widget"));
        assertTrue("the loss is summarised: " + log,
                log.contains("[APE-RV] ActionHistory total=3 skipped=1"));
    }

    @Test
    public void testFullyResolvableHistoryIsUnchangedAndReportsNoSkips() throws Exception {
        File out = folder.newFile("action-history.log");
        List<ActionRecord> history = Arrays.asList(plain(100L, 1), plain(200L, 2));

        Model.saveActionHistory(out, history);

        List<String> written = lines(out);
        assertEquals(2, written.size());
        assertTrue(written.get(0).startsWith("100 "));
        assertTrue(written.get(1).startsWith("200 "));
        assertTrue("no-failure path reports skipped=0: " + captured,
                captured.toString().contains("[APE-RV] ActionHistory total=2 skipped=0"));
    }

    @Test
    public void testNullGuiActionRecordSkippedAndIterationContinues() throws Exception {
        File out = folder.newFile("action-history.log");
        // guiAction == null on a model action: resolveModelAction throws "GUI action should not be
        // null." (Model.java:81) — the production path, no override involved.
        List<ActionRecord> history = Arrays.asList(
                new ActionRecord(100L, 1, new ModelAction(null, ActionType.MODEL_CLICK), null),
                plain(200L, 2));

        Model.saveActionHistory(out, history);

        List<String> written = lines(out);
        assertEquals("iteration continues past the null-guiAction record", 1, written.size());
        assertTrue(written.get(0).startsWith("200 "));
        assertTrue(captured.toString().contains("[APE-RV] ActionHistory total=2 skipped=1"));
    }
}
