package com.android.commands.monkey.ape.utils;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * mop-data-load-oom: memory safety of {@link MopData#load}. Exercises the sized read, the pre-read
 * budget guard (via the package-visible {@code load(path, pkg, main, budgetBytes)} seam so the test
 * controls the budget deterministically), and the reject-and-status contract. A real
 * {@code OutOfMemoryError} is not safely inducible on the JVM suite, so INV-MOP-26's reject/null/
 * {@code status=rejected} contract is exercised through the deterministic too-large path, which
 * shares it; the single outer catch is verified structurally in review.
 */
public class MopDataLoadTest {

    /** Writes JSON to a UTF-8 temp file and returns its absolute path. */
    private static String writeTempJson(String json) throws Exception {
        File f = File.createTempFile("mopdata-oom", ".json");
        f.deleteOnExit();
        try (OutputStreamWriter w =
                new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(json);
        }
        return f.getAbsolutePath();
    }

    /** Captures stdout (the [APE] Logger sink) while running the supplied action. */
    private static String capture(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, "UTF-8"));
            action.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(original);
        }
        return buffer.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /** 2.1: the sized single-allocation read decodes UTF-8 correctly (multi-KB fixture). */
    @Test
    public void readFileExactAllocation() throws Exception {
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            filler.append("com.example.activity.Screen").append(i).append(',');
        }
        // Non-ASCII bytes (é, 界) verify the byte[]→String UTF-8 decode over a multi-KB payload.
        String main = "Café界." + filler;
        String json = "{\"complete\":true,\"package\":\"com.example.oom\",\"mainActivity\":\""
                + main + "\"}";
        String path = writeTempJson(json);
        assertTrue("fixture should exceed the old 8KB char buffer", new File(path).length() > 8192);

        MopData d = MopData.load(path, null, null, Long.MAX_VALUE);
        assertNotNull(d);
        assertEquals("com.example.oom", d.getPackageName());
        assertEquals(main, d.getMainActivity());
    }

    /** 2.2: a file over the parse budget is rejected pre-read with exactly one too-large line. */
    @Test
    public void oversizedFileRejectedTooLarge() throws Exception {
        String json = "{\"complete\":true,\"package\":\"com.example.big\"}";
        String path = writeTempJson(json);
        long size = new File(path).length();
        // Budget so tight that budget/FACTOR < size, forcing the guard to fire.
        long tinyBudget = 6; // budget/6 == 1 < size

        String[] out = new String[1];
        MopData[] result = new MopData[1];
        out[0] = capture(() -> result[0] = MopData.load(path, null, null, tinyBudget));

        assertNull("oversized file must not load", result[0]);
        assertEquals("exactly one too-large line", 1,
                countOccurrences(out[0], "status=rejected reason=too-large"));
        assertTrue("size reported", out[0].contains("size=" + size));
        assertTrue("budget reported", out[0].contains("budget=" + tinyBudget));
        assertFalse("file must not be parsed", out[0].contains("status=loaded"));
    }

    /**
     * 6.1 / INV-MOP-29: a file whose true byte size sits below the heap-derived byte budget is NOT
     * falsely rejected. Unlike {@link #normalFileLoadsWithinBudget} (which uses {@code Long.MAX_VALUE}
     * and never approaches the boundary), this pins the exact byte boundary with a finite budget:
     * {@code budget = size * FACTOR}, so {@code budget / FACTOR == size} and the strict {@code >}
     * reject does not fire. Both operands are bytes; a decimal-MB (10^6) conversion of either side
     * would move this boundary and flip the result, so this is the guard that locks the byte-unit
     * consistency of the comparison against future refactors.
     */
    @Test
    public void fileBelowByteBudgetNotFalselyRejected() throws Exception {
        String json = "{\"complete\":true,\"package\":\"com.example.boundary\"}";
        String path = writeTempJson(json);
        long size = new File(path).length();
        // PARSE_FOOTPRINT_FACTOR == 6; budget/FACTOR == size exactly → the tightest non-rejecting budget.
        long boundaryBudget = size * 6;

        String[] out = new String[1];
        MopData[] result = new MopData[1];
        out[0] = capture(() -> result[0] = MopData.load(path, null, null, boundaryBudget));

        assertNotNull("file at the byte-budget boundary must load, not be rejected", result[0]);
        assertFalse("must not be rejected too-large", out[0].contains("reason=too-large"));
        assertTrue("must parse to loaded", out[0].contains("status=loaded"));
    }

    /** 2.3: a normal file with a generous budget loads exactly as before (regression). */
    @Test
    public void normalFileLoadsWithinBudget() throws Exception {
        String json = "{\"complete\":true,\"package\":\"com.example.normal\"}";
        String path = writeTempJson(json);

        String[] out = new String[1];
        MopData[] result = new MopData[1];
        out[0] = capture(() -> result[0] = MopData.load(path, null, null, Long.MAX_VALUE));

        assertNotNull(result[0]);
        assertEquals("com.example.normal", result[0].getPackageName());
        assertTrue(out[0].contains("status=loaded"));
    }

    /** The public 3-arg entry keeps working (computes the budget from the runtime heap). */
    @Test
    public void publicEntryStillLoadsNormalFile() throws Exception {
        String json = "{\"complete\":true,\"package\":\"com.example.pub\"}";
        MopData d = MopData.load(writeTempJson(json), null, null);
        assertNotNull(d);
        assertEquals("com.example.pub", d.getPackageName());
    }
}
