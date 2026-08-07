package com.android.commands.monkey.ape.utils;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * INV-INP-07 / INV-RUN-08: input-string selection draws from the run's seeded stream.
 *
 * <p>{@code nextString()} used to pick its index with {@code ThreadLocalRandom}, which cannot be
 * seeded — so two runs launched with the same {@code -s} still typed different text, and input
 * generation sat outside the reproducibility the rest of the agent had. It was the last such
 * source. The first test below is the property that closes it; it would have passed vacuously
 * against the old code only if the cache were empty, which is why the cache is populated first.
 *
 * <p>INV-INP-06 is asserted alongside it: an empty cache is the common case on a text-sparse
 * screen (a login form, exactly where input matters most), and it must yield a generated string
 * rather than the {@code nextInt(0)} {@code IllegalArgumentException} of the pre-check ordering.
 */
public class StringCacheSeededTest {

    private List<String> savedCache;

    /**
     * {@code StringCache}'s list is process-wide static state, so each case saves and restores it.
     * Without this, a cache populated here would change what every later test in the JVM observes.
     */
    @Before
    public void saveCache() {
        savedCache = new ArrayList<>(StringCache.stringList);
        StringCache.stringList.clear();
    }

    @After
    public void restoreCache() {
        StringCache.stringList.clear();
        StringCache.stringList.addAll(savedCache);
    }

    private static List<String> drawSequence(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(StringCache.nextString());
        }
        return out;
    }

    @Test
    public void sameSeedYieldsTheSameStringSequenceFromAPopulatedCache() {
        StringCache.stringList.addAll(java.util.Arrays.asList(
                "alpha", "bravo", "charlie", "delta", "echo", "foxtrot"));

        RandomHelper.seed(2026);
        List<String> first = drawSequence(30);
        RandomHelper.seed(2026);
        List<String> second = drawSequence(30);

        assertEquals(first, second);
        // The cache must actually have been consulted; otherwise this asserts only that
        // nextFormattedString is seeded, which RandomHelperSeedTest already covers.
        assertTrue("at least one draw must come from the cache: " + first,
                first.stream().anyMatch(StringCache.stringList::contains));
    }

    @Test
    public void differentSeedsDivergeOverThatSameCache() {
        StringCache.stringList.addAll(java.util.Arrays.asList(
                "alpha", "bravo", "charlie", "delta", "echo", "foxtrot"));

        RandomHelper.seed(2026);
        List<String> a = drawSequence(30);
        RandomHelper.seed(2027);
        List<String> b = drawSequence(30);

        assertFalse("independent seeds should not produce identical sequences", a.equals(b));
    }

    @Test
    public void anEmptyCacheNeverThrowsAndStillYieldsAString() {
        assertTrue("precondition: the cache is empty", StringCache.stringList.isEmpty());

        RandomHelper.seed(7);
        for (int i = 0; i < 100; i++) {
            String s = StringCache.nextString();
            assertNotNull(s);
            assertFalse("an empty cache must still generate text", s.isEmpty());
        }
    }

    @Test
    public void theEmptyCachePathIsAlsoSeeded() {
        RandomHelper.seed(99);
        List<String> first = drawSequence(20);
        RandomHelper.seed(99);
        List<String> second = drawSequence(20);

        assertEquals(first, second);
    }

    @Test
    public void theCacheCapIsTheConfiguredSizeAloneWithNoFileContribution() {
        // There is no /sdcard/ape.strings reader, so nothing can raise the cap above the
        // configured value the way the deleted static initializer's file contents did.
        assertEquals(Config.maxStringListSize, StringCache.maxStringListSize);
    }
}
