package com.android.commands.monkey.ape.agent.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * INV-DP-12 asserted over the tree: the decision path takes its parameters by injection, not by
 * reaching for a global at the moment it decides.
 *
 * <h2>Why a grep for {@code Config.} is not this guard</h2>
 *
 * <p>{@code Config}'s fields are {@code public static final}, so a read of one takes <b>two</b>
 * source forms — qualified ({@code Config.backMenuPickCap}) or bare, behind
 * {@code import static ...Config.backMenuPickCap}. Eleven files in {@code src/main} import the
 * second way, and in {@code StatefulAgent} it was the majority form: 16 of its 26 reads. A pattern
 * that only matches {@code Config\.} passes straight over those, and it also matches the
 * {@code import static} lines themselves, which are declarations rather than reads. That is why
 * the census this sweep started from was of the wrong quantity in both directions.
 *
 * <p>There is a <b>third</b> form the same pattern cannot see at all: a plan value fetched at a
 * decision site through the ambient {@code RunContext.current()}. Two of the scoring path's eight
 * weights reached the passes that way, behind a perfectly clean {@code Config} grep. It is the same
 * defect — a decision reading global state instead of receiving it — only routed differently.
 *
 * <p>So this guard resolves each file's static imports first and then looks for their bare use,
 * strips comments before matching so that prose about the invariant cannot trip it, and asserts the
 * ambient form separately.
 *
 * <h2>What it does not cover, deliberately</h2>
 *
 * <ul>
 *   <li>{@code RunContext.current()} in {@code SataAgent} and {@code StatefulAgent}. Both call it
 *       legitimately — in their constructors, which is the assembly moment the parameters arrive
 *       at, and to fetch the LLM collaborators the plan built. Telling an assembly read from a
 *       decision-time one is a control-flow question, and a source scan cannot answer it. What the
 *       scan can and does say is that the units constructed <em>by</em> injection reach for nothing.
 *   <li>A parameter reached through a helper in a file outside the scopes below. No source scan
 *       sees through a call.
 * </ul>
 *
 * <p>Each scope is resolved and asserted non-empty before it is searched, so a guarded file that
 * moves fails this test loudly instead of leaving it passing over nothing.
 */
public class StaticConfigReadGuardTest {

    private static final Path SRC = Paths.get("src", "main", "java");
    private static final String APE = "com/android/commands/monkey/ape";

    /** The packages whose units exist only as injected collaborators. */
    private static final String[] INJECTED_PACKAGES = {
        APE + "/agent/pipeline", APE + "/agent/scoring", APE + "/llm",
    };

    /**
     * {@code MopScorer} is guarded by name because it lives in {@code ape/utils}, outside every
     * package above — the omission that let the task's original wording exempt it by accident.
     */
    private static final String MOP_SCORER = APE + "/utils/MopScorer.java";

    /** The three classes the sweep of tasks 6.1-6.3 emptied. */
    private static final String[] SWEPT_CLASSES = {
        APE + "/agent/SataAgent.java", APE + "/agent/StatefulAgent.java", APE + "/model/State.java",
    };

    /**
     * The one read design D9 accepts: it gates which actions a {@code State} is <em>built</em> with,
     * during model construction, from a value the plan freezes at load. Pinned at exactly one
     * occurrence so the residue cannot grow a sibling.
     */
    private static final String ALLOWED_RESIDUE = "modelMenuEnabled";

    private static final Pattern STATIC_IMPORT =
            Pattern.compile("import\\s+static\\s+com\\.android\\.commands\\.monkey\\.ape\\.utils"
                    + "\\.Config\\.(\\w+)\\s*;");
    private static final Pattern WILDCARD_STATIC_IMPORT =
            Pattern.compile("import\\s+static\\s+com\\.android\\.commands\\.monkey\\.ape\\.utils"
                    + "\\.Config\\.\\*\\s*;");
    private static final Pattern QUALIFIED = Pattern.compile("\\bConfig\\.(\\w+)");
    private static final Pattern AMBIENT = Pattern.compile("RunContext\\s*\\.\\s*current\\s*\\(\\s*\\)");

    @Test
    public void theDecisionPathReadsConfigNowhereButTheOneAcceptedResidue() throws IOException {
        List<String> offences = new ArrayList<>();
        List<Path> guarded = guardedFiles();
        for (Path file : guarded) {
            String code = codeOf(file);
            for (String field : configReads(code)) {
                if (field.equals(ALLOWED_RESIDUE)) {
                    continue;
                }
                offences.add(SRC.relativize(file) + ": " + field);
            }
        }
        Collections.sort(offences);
        assertEquals("the decision path reads Config at " + offences.size() + " site(s); every "
                + "behavioural parameter of a decision SHALL arrive by injection at assembly "
                + "(INV-DP-12). Both forms count: a qualified Config.<field> and a bare identifier "
                + "behind an import static.", Collections.emptyList(), offences);
    }

    /**
     * The one way a bare read could hide from {@link #STATIC_IMPORT}: a wildcard static import binds
     * every {@code Config} field without naming one, so there would be no identifier for the sweep
     * above to look for and every bare read behind it would pass through invisibly. No guarded file
     * imports that way today, which is what makes the sweep's "either form" claim true rather than
     * merely untested — so the absence is asserted instead of relied on.
     */
    @Test
    public void noGuardedFileWildcardStaticImportsConfig() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : guardedFiles()) {
            if (WILDCARD_STATIC_IMPORT.matcher(codeOf(file)).find()) {
                offences.add(SRC.relativize(file).toString());
            }
        }
        Collections.sort(offences);
        assertEquals("a wildcard static import of Config binds every field without naming one, so "
                + "the bare-identifier sweep has nothing to search for and INV-DP-12's second form "
                + "stops being checked in that file", Collections.emptyList(), offences);
    }

    @Test
    public void theAcceptedResidueIsExactlyOneOccurrence() throws IOException {
        int occurrences = 0;
        for (Path file : guardedFiles()) {
            for (String field : configReads(codeOf(file))) {
                if (field.equals(ALLOWED_RESIDUE)) {
                    occurrences++;
                }
            }
        }
        assertEquals(ALLOWED_RESIDUE + " is design D9's single accepted residue; it is pinned at "
                + "one occurrence so a second read cannot arrive under cover of the first",
                1, occurrences);
    }

    /**
     * Task 6.3 made the least-visited tiebreak a parameter. A residual static read anywhere in
     * {@code src/main} would keep the old behaviour while the injected value travelled unused —
     * and nothing else would report differently, because this seam changes which action is picked,
     * not which stage picks it.
     */
    @Test
    public void nothingInTheJarStillReadsTheLeastVisitedTiebreakStatically() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : javaFilesUnder(SRC)) {
            if (file.endsWith("Config.java") || file.endsWith("KeyOwnership.java")
                    || file.endsWith("Feature.java") || file.endsWith("Presets.java")) {
                continue; // the declaration, its ownership table, and the plans that state it
            }
            if (configReads(codeOf(file)).contains("leastVisitedPriorityTiebreak")) {
                offences.add(SRC.relativize(file).toString());
            }
        }
        Collections.sort(offences);
        assertEquals("leastVisitedPriorityTiebreak travels as an argument from the SataChain call "
                + "site; a static read would silently override it", Collections.emptyList(),
                offences);
    }

    @Test
    public void theInjectedUnitsNeverReachForTheAmbientRunContext() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : injectedUnitFiles()) {
            Matcher matcher = AMBIENT.matcher(codeOf(file));
            int hits = 0;
            while (matcher.find()) {
                hits++;
            }
            if (hits > 0) {
                offences.add(SRC.relativize(file) + " (" + hits + ")");
            }
        }
        Collections.sort(offences);
        assertEquals("a stage, a scoring pass, an LLM unit and MopScorer are all constructed with "
                + "what they need; reaching RunContext.current() from one of them is a plan read at "
                + "decision time by another route, which INV-DP-12 forbids just as much",
                Collections.emptyList(), offences);
    }

    // ---------------------------------------------------------------------------------------
    // Scope resolution — every scope is asserted non-empty, so a moved file fails loudly
    // ---------------------------------------------------------------------------------------

    private static List<Path> guardedFiles() throws IOException {
        List<Path> files = new ArrayList<>(injectedUnitFiles());
        for (String named : SWEPT_CLASSES) {
            files.add(requireFile(named));
        }
        return files;
    }

    private static List<Path> injectedUnitFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String pkg : INJECTED_PACKAGES) {
            Path dir = SRC.resolve(pkg);
            assertTrue("guarded package not found at " + dir.toAbsolutePath()
                    + " — it moved, and this guard would otherwise pass over nothing",
                    Files.isDirectory(dir));
            List<Path> inPackage = javaFilesUnder(dir);
            assertTrue("guarded package " + pkg + " holds no .java file", !inPackage.isEmpty());
            files.addAll(inPackage);
        }
        files.add(requireFile(MOP_SCORER));
        return files;
    }

    private static Path requireFile(String relative) {
        Path file = SRC.resolve(relative);
        assertTrue("guarded file not found at " + file.toAbsolutePath()
                + " — it moved, and this guard would otherwise pass over nothing",
                Files.isRegularFile(file));
        return file;
    }

    private static List<Path> javaFilesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Matching — comments stripped first, both read forms counted
    // ---------------------------------------------------------------------------------------

    /** The file's source with block and line comments removed, so prose cannot trip the guard. */
    private static String codeOf(Path file) throws IOException {
        String body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return body.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    /**
     * Every {@code Config} read in this code, <b>one entry per occurrence</b>, in both forms:
     * qualified, and bare behind a static import.
     *
     * <p>Occurrences rather than distinct field names, because the residue pin is a claim about how
     * many times {@code modelMenuEnabled} is read, and a set would let a second read of the same
     * field hide behind the first — which is exactly what an earlier draft of this guard did until
     * a probe drifted a duplicate in and it stayed green.
     *
     * <p>Import lines are excluded before matching. An import is a declaration, not a read: the
     * sweep found a static import of {@code graphStableRestartThreshold} in {@code SataAgent} whose
     * only occurrence was the import line itself, and counting those as reads is the other half of
     * why the census this sweep started from was wrong.
     */
    private static List<String> configReads(String code) {
        List<String> reads = new ArrayList<>();
        Set<String> staticallyImported = new LinkedHashSet<>();
        Matcher imports = STATIC_IMPORT.matcher(code);
        while (imports.find()) {
            staticallyImported.add(imports.group(1));
        }

        String withoutImports = code.replaceAll("(?m)^\\s*import\\s[^;]*;", "");
        Matcher qualified = QUALIFIED.matcher(withoutImports);
        while (qualified.find()) {
            reads.add(qualified.group(1));
        }
        for (String field : staticallyImported) {
            Matcher bare = Pattern.compile("(?<![\\w.])" + Pattern.quote(field) + "\\b")
                    .matcher(withoutImports);
            while (bare.find()) {
                reads.add(field);
            }
        }
        return reads;
    }
}
