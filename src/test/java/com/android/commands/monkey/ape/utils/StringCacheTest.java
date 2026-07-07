package com.android.commands.monkey.ape.utils;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link StringCache#nextString()} (INV-INP-06).
 *
 * <p>{@code StringCache.stringList} is a package-private static list shared across the
 * JVM; each test snapshots and restores it so ordering with other test classes
 * (e.g. {@link InputValueGeneratorTest}) is irrelevant.
 */
public class StringCacheTest {

    @Test
    public void testNextString_emptyCacheReturnsNonNull() {
        // INV-INP-06: an empty cache must NOT reach nextInt(0) (which throws
        // IllegalArgumentException); it returns a formatted random string instead.
        List<String> saved = new ArrayList<>(StringCache.stringList);
        try {
            StringCache.stringList.clear();
            String s = StringCache.nextString();
            assertNotNull(s);
            assertFalse(s.isEmpty());
        } finally {
            StringCache.stringList.clear();
            StringCache.stringList.addAll(saved);
        }
    }

    @Test
    public void testNextString_populatedCacheReturnsNonNull() {
        // A non-empty cache returns either the cached value or a formatted random
        // string (the randomFormattedStringProp toss) — never null, never throws.
        List<String> saved = new ArrayList<>(StringCache.stringList);
        try {
            StringCache.stringList.clear();
            StringCache.stringList.add("only_value");
            String s = StringCache.nextString();
            assertNotNull(s);
            assertFalse(s.isEmpty());
        } finally {
            StringCache.stringList.clear();
            StringCache.stringList.addAll(saved);
        }
    }
}
