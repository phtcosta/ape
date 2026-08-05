package com.android.commands.monkey;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * INV-CT-14's second clause: the launch result is recorded and never acted upon, so the launcher's
 * behaviour with the recording present is identical to its behaviour without it.
 *
 * <p>This is asserted over the source tree because it cannot be asserted from behaviour here.
 * {@code MonkeySourceApe} cannot be class-loaded off-device (its {@code UiAutomation} field pulls
 * in {@code android.app.IUiAutomationConnection}, absent from the JVM test classpath), and
 * {@code AndroidDevice.startActivity} reaches the platform through reflection on
 * {@code IActivityManager}. Running the two variants and diffing them is a device experiment, and
 * the only device execution this stage gets measures coverage, not this.
 *
 * <p>What can be asserted, and is: the value has no reader that could act on it. Two scans, because
 * they fail for different reasons. The first is containment — {@code LaunchResult} exists in the
 * producer and at the one dispatch site and nowhere else, so no stage, pass or agent can consult
 * it, which is what makes "no candidate re-selection, cursor or budget adjustment" structural
 * rather than merely intended (the budget accounting of INV-CT-12 lives in {@code MopLauncherStage},
 * which cannot see the type). The second is inertness at that one site: the local is read by the
 * pre-existing dispatch warning and by the sink call, and by nothing else.
 *
 * <p>The harness half of the invariant ("in the jar or in the harness") is not in this repository
 * and is not claimed here.
 */
public class ActivityLaunchRecordingIsInertTest {

    /** A floor on the walk's yield: below this the scan is broken, not clean. */
    private static final int MINIMUM_FILES_SCANNED = 100;

    private static final String PRODUCER =
            "src/main/java/com/android/commands/monkey/ape/AndroidDevice.java";
    private static final String DISPATCH_SITE =
            "src/main/java/com/android/commands/monkey/MonkeySourceApe.java";

    @Test
    public void theLaunchResultTypeReachesNoDecisionMakingCode() throws IOException {
        List<String> files = filesMentioning("LaunchResult");
        assertEquals("LaunchResult must exist only where it is produced and where it is recorded; "
                + "a third file means some component can now branch on the platform's answer, "
                + "which INV-CT-14 forbids. Found: " + files,
                Arrays.asList(DISPATCH_SITE, PRODUCER), files);
    }

    @Test
    public void theRecordedLaunchIsReadByTheWarningAndTheSinkAndByNothingElse() throws IOException {
        String body = dispatchMethodBody();

        // Yield floor: an extraction that silently produced the wrong text would satisfy every
        // absence assertion below by vacuity, which is worse than having no guard.
        assertTrue("the extracted body must contain the dispatch: " + body,
                body.contains("AndroidDevice.startActivity(intent)"));
        assertTrue("the extracted body must contain the recording: " + body,
                body.contains("componentLaunch(launch.code, launch.error)"));

        assertEquals("exactly one dispatch: a second startActivity call in this method would be a "
                + "re-dispatch, and a re-dispatch driven by the result is the thing INV-CT-14 "
                + "names first", 1, count(body, "AndroidDevice.startActivity("));
        // The three sites are named verbatim and then the count closes the set: with exactly four
        // occurrences of the local, these three reads are all of them, so the only branch the
        // result reaches is the dispatch warning that predates the recording. (The method's other
        // branch selects the deep-link intent and never mentions the launch.)
        assertTrue("the declaration site moved: " + body,
                body.contains("AndroidDevice.LaunchResult launch = AndroidDevice.startActivity(intent);"));
        assertTrue("the only branch on the result must be the pre-existing dispatch warning: " + body,
                body.contains("if (!launch.dispatched())"));
        assertEquals("the launch local is read exactly four times -- its declaration, the "
                + "pre-existing dispatched() warning, and the two fields handed to the sink. A "
                + "fifth read is a new consumer of the platform's answer and must be justified "
                + "against INV-CT-14 rather than absorbed: " + body,
                4, count(body, "launch"));

        for (String loop : new String[] {"for (", "while (", "do {"}) {
            assertFalse("no retry loop may exist in the dispatch method: " + loop,
                    body.contains(loop));
        }
        assertFalse("no early exit: the method falls off its end, so the recording is reached on "
                + "every path the dispatch takes", body.contains("return"));
    }

    @Test
    public void theScanReadsTheTreeItClaimsTo() {
        assertTrue("the scan is only meaningful from the module root",
                Paths.get(PRODUCER).toFile().isFile() && Paths.get(DISPATCH_SITE).toFile().isFile());
    }

    /**
     * The body of {@code generateActivityTriggerEvent}, comments removed, brace-matched from its
     * signature. Comments are removed because a comment that discusses the launch result is legal
     * -- the guard is about what the method does.
     */
    private static String dispatchMethodBody() throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(DISPATCH_SITE)),
                StandardCharsets.UTF_8);
        String stripped = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        int start = stripped.indexOf("private void generateActivityTriggerEvent(");
        assertTrue("dispatch method not found -- it was renamed or removed", start >= 0);
        int open = stripped.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return stripped.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced braces from the dispatch method signature");
    }

    /** Every {@code src/main} file whose text mentions {@code token}, comments included. */
    private static List<String> filesMentioning(final String token) throws IOException {
        final List<String> hits = new ArrayList<>();
        final int[] filesSeen = {0};
        Path root = Paths.get("src", "main", "java");
        assertTrue("source tree not found at " + root.toAbsolutePath(), Files.isDirectory(root));
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                filesSeen[0]++;
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (text.contains(token)) {
                    hits.add(file.toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue("the walk visited only " + filesSeen[0] + " files; expected at least "
                + MINIMUM_FILES_SCANNED + " -- the scan is broken",
                filesSeen[0] >= MINIMUM_FILES_SCANNED);
        Collections.sort(hits);
        return hits;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
