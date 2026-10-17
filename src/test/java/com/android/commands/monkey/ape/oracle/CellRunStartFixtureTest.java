package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.runtime.Presets;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.RunSpecEcho;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The cell {@code RUN_START} fixtures (isolate-guidance-groups 11.6):
 * {@code src/test/resources/cells/<cell>.run_start.json}, one masked {@code RUN_START} line per
 * cell, the record the jar writes first for the plan the harness pushes. {@code aperv-tool} (its
 * offline cell gate) and {@code mini-experimento} (the device cell gate) compare a run's
 * {@code RUN_START} against them on {@code preset}, {@code variant}, {@code features},
 * {@code params}, {@code inert} and {@code digest}.
 *
 * <p>Each plan is the properties file {@code aperv-tool}'s {@code _push_properties} writes for the
 * cell's variant: {@code ape.preset} and {@code ape.variant}, both the cell's name;
 * {@code ape.staticDataPath} on every cell but {@code base}; and, on the two LLM cells, the
 * {@code ape.llmUrl} of its {@code LLM_URL_DEFAULT}. A run under another URL differs from its
 * fixture in {@code params} and {@code digest} by exactly that value. The agent is {@code --ape sata}.
 *
 * <p>The volatile members — {@code run_id}, {@code t0}, {@code seed}, {@code props_digest} and
 * {@code build} — are written as {@code "<masked>"}. The record is the production
 * {@link RunSpecEcho} output, so the member order is the jar's.
 *
 * <p>Compare mode is the default and writes nothing; {@code -Dape.oracle.regenerate=true} rewrites
 * the six files, the same deliberate-capture gate the goldens use (INV-ORA-04).
 */
public class CellRunStartFixtureTest {

    private static final String ROOT = "src/test/resources/cells";

    /** {@code aperv-tool}'s {@code DEVICE_ARTIFACT_PATH}. */
    private static final String DEVICE_ARTIFACT_PATH = "/data/local/tmp/mop-artifact.json";

    /** {@code aperv-tool}'s {@code LLM_URL_DEFAULT}. */
    private static final String LLM_URL_DEFAULT = "http://10.0.2.2:30000/v1";

    private static final String MASK = "\"<masked>\"";

    @After
    public void tearDown() {
        RunContext.resetForTest();
    }

    /** The properties {@code aperv-tool} pushes for {@code cell}, in its line order. */
    static Map<String, String> pushedProperties(String cell) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("ape.preset", cell);
        entries.put("ape.variant", cell);
        if (!Presets.BASE.equals(cell)) {
            entries.put("ape.staticDataPath", DEVICE_ARTIFACT_PATH);
        }
        if (Presets.AS_LLM.equals(cell) || Presets.AS_MOP_LLM.equals(cell)) {
            entries.put("ape.llmUrl", LLM_URL_DEFAULT);
        }
        return entries;
    }

    /** The cell's {@code RUN_START} line with its volatile members masked. */
    static String maskedRunStart(String cell) throws Exception {
        RunSpec spec = RunSpec.resolve(pushedProperties(cell),
                RunSpec.CliValues.of("sata", 1L, null));
        RunContext.installForTest(spec);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RunSpecEcho.emit(RunContext.current(), new PrintStream(out, true, "UTF-8"));
        return mask(out.toString("UTF-8").trim());
    }

    /** Masks the members a run does not share with its cell. */
    static String mask(String line) {
        return line
                .replaceFirst("\"run_id\":\"[^\"]*\"", "\"run_id\":" + MASK)
                .replaceFirst("\"t0\":-?[0-9]+", "\"t0\":" + MASK)
                .replaceFirst("\"seed\":-?[0-9]+", "\"seed\":" + MASK)
                .replaceFirst("\"props_digest\":\"[^\"]*\"", "\"props_digest\":" + MASK)
                .replaceFirst("\"build\":\\{[^}]*\\}", "\"build\":" + MASK);
    }

    @Test
    public void eachCellsRunStartMatchesItsFixture() throws Exception {
        for (String cell : Presets.names()) {
            String actual = maskedRunStart(cell);
            assertTrue(cell + ": every volatile member is masked",
                    actual.contains("\"run_id\":" + MASK) && actual.contains("\"t0\":" + MASK)
                            && actual.contains("\"seed\":" + MASK)
                            && actual.contains("\"props_digest\":" + MASK)
                            && actual.contains("\"build\":" + MASK));
            Path fixture = Paths.get(ROOT, cell + ".run_start.json");
            if (GoldenFile.captureRequested()) {
                Files.createDirectories(fixture.getParent());
                Files.write(fixture, (actual + "\n").getBytes(StandardCharsets.UTF_8));
                continue;
            }
            if (!Files.exists(fixture)) {
                fail(fixture + " is missing; capture it deliberately with -D"
                        + GoldenFile.REGENERATE_PROPERTY + "=true");
            }
            assertEquals(cell + ": RUN_START moved from its fixture",
                    new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8).trim(), actual);
        }
    }
}
