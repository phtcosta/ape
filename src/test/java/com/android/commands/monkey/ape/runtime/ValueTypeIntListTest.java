package com.android.commands.monkey.ape.runtime;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ValueType#INT_LIST}, the type of {@code ape.mopWeights} (run-spec "Integer-List Value
 * Type", "Integer-List Rendering"; design D1).
 */
public class ValueTypeIntListTest {

    private static final String KEY = "ape.mopWeights";

    private static List<?> parse(String raw) {
        return (List<?>) ValueType.INT_LIST.parse(KEY, raw);
    }

    private static void assertInvalid(String raw) {
        try {
            ValueType.INT_LIST.parse(KEY, raw);
            fail("'" + raw + "' must not parse");
        } catch (RunSpecException e) {
            assertEquals(RunSpecException.Reason.INVALID_TYPE, e.getReason());
            assertEquals(KEY, e.getKey());
            assertTrue(e.getDetail(),
                    e.getDetail().contains("expected 1 to 10 comma-separated non-negative integers"));
        }
    }

    @Test
    public void parsesToTheListOfIntegers() {
        assertEquals(Arrays.asList(500, 400, 300), parse("500,400,300"));
        assertEquals(Arrays.asList(300, 500), parse("300,500"));
    }

    @Test
    public void trimsEachElementAsIntTrims() {
        assertEquals(Arrays.asList(500, 400), parse(" 500 , 400 "));
    }

    @Test
    public void acceptsTheBoundaries() {
        assertEquals(Arrays.asList(0), parse("0"));
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), parse("1,2,3,4,5,6,7,8,9,10"));
    }

    @Test
    public void theParsedListIsUnmodifiable() {
        List<?> list = parse("500,400,300");
        try {
            list.clear();
            fail("the parsed list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void rejectsABlankValue() {
        assertInvalid("");
        assertInvalid("   ");
    }

    @Test
    public void rejectsAnEmptyElement() {
        assertInvalid("500,,300");
        assertInvalid("500,400,");
        assertInvalid(",500");
    }

    @Test
    public void rejectsANonIntegerElement() {
        assertInvalid("500,4x0,300");
        assertInvalid("500,400.0");
        assertInvalid("500,99999999999");
    }

    @Test
    public void rejectsANegativeElement() {
        assertInvalid("500,-400,300");
    }

    @Test
    public void rejectsMoreThanTenElements() {
        assertInvalid("1,2,3,4,5,6,7,8,9,10,11");
    }

    @Test
    public void theCanonicalFormJoinsWithoutSpaces() {
        assertEquals("500,400", ValueType.INT_LIST.canonical(KEY, "500, 400"));
        assertEquals("500,400,300", ValueType.INT_LIST.canonical(KEY, "500,400,300"));
        assertEquals("", ValueType.INT_LIST.canonical(KEY, null));
    }

    @Test
    public void sameValueComparesTheParsedLists() {
        assertTrue(ValueType.INT_LIST.sameValue(KEY, "500, 400", "500,400"));
        assertFalse(ValueType.INT_LIST.sameValue(KEY, "500,400", "400,500"));
        assertFalse(ValueType.INT_LIST.sameValue(KEY, "500,400", "500,400,300"));
    }
}
