package com.android.commands.monkey.ape.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertNotNull;

/**
 * The harness's own {@code ape.properties} output, loaded the way the device loads it.
 *
 * <p>The fixtures under {@code src/test/resources/compat/} are byte-for-byte captures of what
 * {@code ApeRVTool._push_properties} writes for each campaign arm; that directory's README carries
 * the {@code tool.py} pin they were captured from.
 *
 * <p>This lives in one place on purpose. Two test classes read these fixtures —
 * {@link RunSpecCompatTest} for the resolved plans and {@link PresetsTest} for the preset
 * equivalence — and the one thing both must not get wrong is <em>how</em> the bytes become entries:
 * raw bytes through {@link Properties}, exactly as {@code Monkey.resolveRunSpec} does it. A second
 * copy of that loader could drift into parsing a file the device never sees, and the tests would
 * still pass while proving nothing.
 */
final class CompatFixtures {

    /** Path the harness pushes the compacted static-analysis JSON to; activates {@code MOP}. */
    static final String MOP_DATA_PATH = "/data/local/tmp/static_analysis.json";

    /** The SGLang endpoint the arm dictionaries carry; activates {@code LLM}. */
    static final String LLM_URL = "http://10.0.2.2:30000/v1";

    private CompatFixtures() {
    }

    /** The fixture's entries, parsed as the bootstrap parses a real properties file. */
    static Map<String, String> entries(String name) {
        Properties parsed = new Properties();
        try (InputStream in = open(name)) {
            parsed.load(in);
        } catch (IOException e) {
            throw new RuntimeException("cannot read fixture " + name, e);
        }
        Map<String, String> entries = new LinkedHashMap<>();
        for (String key : parsed.stringPropertyNames()) {
            entries.put(key, parsed.getProperty(key));
        }
        return entries;
    }

    /** The fixture resolved into a plan, with the same CLI values every other test uses. */
    static RunSpec resolve(String name) {
        return RunSpec.resolve(entries(name), RunSpec.CliValues.of("sata", 42L, null));
    }

    /** The keys the fixture states, as the deployment wrote them. */
    static Set<String> statedKeys(String name) {
        Properties parsed = new Properties();
        try (InputStream in = open(name)) {
            parsed.load(in);
        } catch (IOException e) {
            throw new RuntimeException("cannot read fixture " + name, e);
        }
        return parsed.stringPropertyNames();
    }

    /** The fixture's raw text, for assertions about the file rather than the resolved plan. */
    static String rawText(String name) {
        try (InputStream in = open(name)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), "UTF-8");
        } catch (IOException e) {
            throw new RuntimeException("cannot read fixture " + name, e);
        }
    }

    private static InputStream open(String name) {
        InputStream in = CompatFixtures.class.getResourceAsStream("/compat/" + name);
        assertNotNull("missing fixture /compat/" + name, in);
        return in;
    }
}
