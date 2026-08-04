package com.android.commands.monkey.ape.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * INV-RUN-06 and INV-RUN-08, checked against the tree rather than against the day they were
 * written.
 *
 * <p>Two absences are asserted, and neither can be asserted from behavior. The first is that the
 * jar reads no behavioral input from {@code /sdcard} other than {@code ape.properties} — the
 * XPathlet overlay, the XPath action-injection channel and the string-seeding file are gone (owner
 * decision D6). The second is that no unseeded generator survives: {@code ThreadLocalRandom} once
 * backed {@code RandomHelper} and then survived in {@code StringCache.nextString()} after
 * {@code RandomHelper} was fixed, which is precisely how a re-introduction would happen again.
 *
 * <p>Both scans read comments out first, so a line that <em>discusses</em> the removed mechanism
 * stays legal — the guard is about what the jar does, not about what its comments may mention. Both
 * carry a minimum-yield floor, because a scan that silently matches nothing would pass by vacuity
 * and be worse than no guard at all.
 */
public class DeviceInputChannelAbsenceTest {

    /** A floor on the walk's yield: below this the scan is broken, not clean. */
    private static final int MINIMUM_FILES_SCANNED = 100;

    private static final Pattern SDCARD_INPUT = Pattern.compile(
            "/sdcard/ape\\.(xpath\\.actions|xpath|strings)\\b");

    private static final Pattern UNSEEDED_RANDOM = Pattern.compile("\\bThreadLocalRandom\\b");

    /** {@code Math.random()} — the second mechanism INV-RUN-08 names; unseedable like the first. */
    private static final Pattern MATH_RANDOM = Pattern.compile("\\bMath\\s*\\.\\s*random\\s*\\(");

    /** A no-argument {@code new Random()} — the third. See the test for why one is expected. */
    private static final Pattern BARE_RANDOM = Pattern.compile("\\bnew\\s+Random\\s*\\(\\s*\\)");

    /**
     * A read-back of any artifact of a previous run (INV-RUN-07). The persistence protocol wrote a
     * {@code Model} and read it with a {@code Graph} cast; group 5 deleted both halves.
     */
    private static final Pattern DESERIALIZATION = Pattern.compile(
            "\\bObjectInputStream\\b|\\breadObject\\s*\\(|\\breadGraph\\s*\\(");

    /** The one {@code /sdcard} path that is still a legal read (run-spec capability). */
    private static final Pattern ALLOWED_PROPERTIES = Pattern.compile("/sdcard/ape\\.properties\\b");

    @Test
    public void noCodePathReadsABehavioralFileFromSdcardOtherThanApeProperties() throws IOException {
        List<String> hits = scanForPattern(SDCARD_INPUT);
        assertEquals("the jar must read no behavioral /sdcard input but ape.properties "
                + "(INV-RUN-06): " + hits, Collections.emptyList(), hits);
    }

    @Test
    public void noUnseededGeneratorSurvivesInProductionCode() throws IOException {
        List<String> hits = scanForPattern(UNSEEDED_RANDOM);
        assertEquals("all run-scoped randomness derives from the seeded stream (INV-RUN-08); "
                + "ThreadLocalRandom cannot be seeded: " + hits, Collections.emptyList(), hits);
    }

    @Test
    public void noOtherUnseedableGeneratorSurvivesInProductionCode() throws IOException {
        // The other two mechanisms INV-RUN-08 names. Kept apart from the ThreadLocalRandom scan so
        // a failure says which one came back.
        List<String> hits = scanForPattern(MATH_RANDOM);
        assertEquals("Math.random() cannot be seeded, so a decision drawn from it is outside the "
                + "run's stream (INV-RUN-08): " + hits, Collections.emptyList(), hits);
    }

    @Test
    public void theOnlyUnseededRandomIsTheOneTheContextReplaces() throws IOException {
        // Asserting the true state rather than an empty set, which would be false. Exactly one bare
        // `new Random()` exists: RandomHelper's field initializer. It is unseeded, but nothing draws
        // from that instance in a real run — RunContext.initialize calls RandomHelper.seed before
        // any exploration component exists, replacing it with a seeded generator. Pinning the site
        // is what makes a *second* bare Random a failure instead of a silent second stream; an
        // empty-set assertion would have to delete a legitimate initializer to pass, and a
        // "contains at most N" weakening would stop naming which site is new.
        List<String> hits = scanForPattern(BARE_RANDOM);
        assertEquals("exactly one unseeded Random is expected, RandomHelper's field initializer, "
                + "which RunContext.initialize replaces before any component draws (INV-RUN-08); "
                + "found: " + hits, 1, hits.size());
        assertTrue("the one bare Random must be RandomHelper's, but was " + hits.get(0),
                hits.get(0).contains("RandomHelper.java"));
    }

    @Test
    public void noCodePathReadsBackAnArtifactOfAPreviousRun() throws IOException {
        // INV-RUN-07. The retired keys (ape.modelFile and the save-flags) abort in RunSpecAbortTest
        // and the deleted API is compile-enforced, but neither guards the *absence* of a new
        // deserialization path — which is what this invariant asserts and what a future "resume"
        // feature would reintroduce. Retry after failure belongs to the Python supervisor (R3).
        List<String> hits = scanForPattern(DESERIALIZATION);
        assertEquals("no artifact of a previous run may be read back (INV-RUN-07): " + hits,
                Collections.emptyList(), hits);
    }

    @Test
    public void apePropertiesIsStillReadSoTheScanIsProvedToSeeSdcardPaths() throws IOException {
        // The complement of the first assertion. Without it, a scan that stripped too much — or
        // read the wrong tree — would report "no /sdcard reads" and look like success.
        List<String> hits = scanForPattern(ALLOWED_PROPERTIES);
        assertTrue("the scan must still find the one legal /sdcard read; finding none means the "
                + "scan is broken, not that the tree is clean", hits.size() >= 1);
    }

    @Test
    public void theScanReadsTheTreeItClaimsTo() {
        assertTrue("the scan is only meaningful from the module root",
                new File("src/main/java/com/android/commands/monkey/ape/utils/Config.java").isFile());
    }

    /** Returns {@code path:line} for every match outside a comment, over {@code src/main/java}. */
    private static List<String> scanForPattern(Pattern pattern) throws IOException {
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
                String body = stripComments(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                String[] lines = body.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    Matcher m = pattern.matcher(lines[i]);
                    if (m.find()) {
                        hits.add(file + ":" + (i + 1));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue("the walk visited only " + filesSeen[0] + " files; expected at least "
                + MINIMUM_FILES_SCANNED + " — the scan is broken", filesSeen[0] >= MINIMUM_FILES_SCANNED);
        Collections.sort(hits);
        return hits;
    }

    /**
     * Blanks out {@code //} and block comments, preserving line structure so reported line numbers
     * stay usable. String literals are left intact — they are exactly what the scan looks for.
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        boolean inString = false;
        boolean inChar = false;
        while (i < source.length()) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inString || inChar) {
                out.append(c);
                if (c == '\\' && next != '\0') {
                    out.append(next);
                    i += 2;
                    continue;
                }
                if (inString && c == '"') inString = false;
                if (inChar && c == '\'') inChar = false;
                i++;
                continue;
            }
            if (c == '"') { inString = true; out.append(c); i++; continue; }
            if (c == '\'') { inChar = true; out.append(c); i++; continue; }
            if (c == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && next == '*') {
                i += 2;
                while (i < source.length() && !(source.charAt(i) == '*'
                        && i + 1 < source.length() && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') out.append('\n'); // keep line numbers honest
                    i++;
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
