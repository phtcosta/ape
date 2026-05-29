package com.android.commands.monkey.ape.events;

import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.TypedInputGenerator;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * gh13 §18 — TypedInputGenerator.generateForType (T1.3) type-aware input generation.
 */
public class ApeFuzzerInputTypeTest {

    private final Random rnd = new Random(42);

    @Test // 18.1
    public void testPasswordInputTypeProducesMixedClass() {
        String out = TypedInputGenerator.generateForType("textPassword", "", rnd);
        assertTrue("length 8..12", out.length() >= 8 && out.length() <= 12);
        assertTrue("has letter", out.matches(".*[a-zA-Z].*"));
        assertTrue("has digit", out.matches(".*[0-9].*"));
        assertTrue("has symbol", out.matches(".*[!@#$%&*?].*"));
    }

    @Test // 18.2
    public void testNumberInputTypeProducesDigits() {
        assertTrue(TypedInputGenerator.generateForType("number", "", rnd).matches("^-?\\d+$"));
    }

    @Test // 18.3
    public void testPhoneInputTypeMatchesTemplate() {
        assertTrue(TypedInputGenerator.generateForType("phone", "", rnd)
                .matches("^\\+55 11 9\\d{4}-\\d{4}$"));
    }

    @Test // 18.4
    public void testEmailInputTypeContainsAt() {
        assertTrue(TypedInputGenerator.generateForType("textEmailAddress", "", rnd)
                .matches("^[a-z]+@example\\.com$"));
    }

    @Test // 18.5
    public void testHintBasedFallbackDetectsEmail() {
        assertTrue(TypedInputGenerator.generateForType("", "Your email", rnd)
                .matches("^[a-z]+@example\\.com$"));
    }

    @Test // 18.6
    public void testUnknownInputTypeFallsBackToLegacy() {
        String out = TypedInputGenerator.generateForType("weird_unknown", "", rnd);
        assertFalse(out.isEmpty());
        assertFalse("not password", out.matches(".*[0-9].*") && out.matches(".*[!@#$%&*?].*"));
        assertFalse("not number", out.matches("^-?\\d+$"));
        assertFalse("not email", out.contains("@"));
    }

    @Test // 18.7 — rollback guard (INV-MOP-16)
    public void testFuzzInputTypedFlagBypassesTypedPath() {
        boolean prev = Config.fuzzInputTyped;
        Config.fuzzInputTyped = false;
        try {
            String out = TypedInputGenerator.generateForType("textPassword", "Your password", rnd);
            assertFalse("must NOT be password shape when disabled",
                    out.matches(".*[0-9].*") && out.matches(".*[!@#$%&*?].*"));
            assertTrue("legacy lowercase shape", out.matches("^[a-z]+$"));
        } finally {
            Config.fuzzInputTyped = prev;
        }
    }

    @Test // 18.8
    public void testDateInputTypeProducesIso8601() {
        String out = TypedInputGenerator.generateForType("date", "", rnd);
        assertTrue(out.matches("^\\d{4}-\\d{2}-\\d{2}$"));
        int year = Integer.parseInt(out.substring(0, 4));
        int cur = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        assertTrue("year in [cur-10, cur]", year >= cur - 10 && year <= cur);
    }

    @Test // 18.9
    public void testTimeAndDatetimeInputTypesProduceIso8601() {
        assertTrue(TypedInputGenerator.generateForType("time", "", rnd)
                .matches("^\\d{2}:\\d{2}(:\\d{2})?$"));
        assertTrue(TypedInputGenerator.generateForType("datetime", "", rnd)
                .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?$"));
    }

    @Test // 18.10
    public void testUriInputTypeMatchesShape() {
        assertTrue(TypedInputGenerator.generateForType("textUri", "", rnd)
                .matches("^https://example\\.com/[a-z]{8}$"));
    }
}
