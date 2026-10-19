package com.android.commands.monkey.ape.utils;

import java.util.Random;

import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.RunContext;

/**
 * gh13 T1.3: type-aware input generation for EditText fuzzing, the STATIC member
 * {@code TYPED_FUZZ} (heuristic-input INV-INP-08/09).
 *
 * Pure Java (no Android dependencies) so it is unit-testable in plain JVM — unlike
 * {@code ApeFuzzer}, whose static initializer references {@code android.view.KeyEvent}.
 * Callers (see {@code ApeAgent.generateInputText}) read the widget's {@code inputType}/{@code hint}
 * from {@link StaticGuiModel} and delegate here; it reads no MOP data. With {@code TYPED_FUZZ}
 * absent from the plan ({@code ape.fuzzInputTyped=false}, or {@code STATIC} off) it falls back to
 * the legacy random-string generator.
 */
public final class TypedInputGenerator {

    private static final char[] LOWER = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final char[] DIGITS = "0123456789".toCharArray();
    private static final char[] SYMBOLS = "!@#$%&*?".toCharArray();

    private TypedInputGenerator() {}

    /**
     * Generate a domain-correct random string for the given Android {@code inputType}
     * (INV-MOP-16). Falls back to hint-based heuristics when {@code inputType} is empty, and to
     * a legacy random string for anything unrecognized. When {@code TYPED_FUZZ} is not in the plan
     * the typed path is bypassed entirely (rollback knob).
     */
    public static String generateForType(String inputType, String hint, Random rnd) {
        if (!RunContext.current().spec().has(Feature.TYPED_FUZZ)) {
            return legacyString(rnd);
        }
        String it = inputType == null ? "" : inputType;
        if (it.contains("Password")) {
            return password(rnd);
        }
        switch (it) {
            case "number":
                return Integer.toString(rnd.nextInt(1_000_000));
            case "numberSigned":
                return Integer.toString(rnd.nextInt(2_000_000) - 1_000_000);
            case "numberDecimal":
                return rnd.nextInt(100_000) + "." + (10 + rnd.nextInt(90));
            case "phone":
                return phone(rnd);
            case "textEmailAddress":
                return email(rnd);
            case "textUri":
                return "https://example.com/" + letters(rnd, LOWER, 8);
            case "date":
                return isoDate(rnd);
            case "time":
                return isoTime(rnd);
            case "datetime":
                return isoDate(rnd) + "T" + isoTime(rnd);
            default:
                break;
        }
        if (it.isEmpty() && hint != null && !hint.isEmpty()) {
            String h = hint.toLowerCase();
            if (h.contains("email")) {
                return email(rnd);
            }
            if (h.contains("senha") || h.contains("password")) {
                return password(rnd);
            }
            if (hint.matches("\\d+")) {
                return Integer.toString(rnd.nextInt(1_000_000));
            }
        }
        return legacyString(rnd);
    }

    private static String password(Random rnd) {
        int len = 8 + rnd.nextInt(5); // 8..12
        char[] out = new char[len];
        out[0] = LOWER[rnd.nextInt(LOWER.length)];
        out[1] = DIGITS[rnd.nextInt(DIGITS.length)];
        out[2] = SYMBOLS[rnd.nextInt(SYMBOLS.length)];
        for (int i = 3; i < len; i++) {
            int bucket = rnd.nextInt(4);
            char[] pool = bucket == 0 ? LOWER : bucket == 1 ? UPPER : bucket == 2 ? DIGITS : SYMBOLS;
            out[i] = pool[rnd.nextInt(pool.length)];
        }
        for (int i = len - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            char t = out[i]; out[i] = out[j]; out[j] = t;
        }
        return new String(out);
    }

    private static String phone(Random rnd) {
        return String.format("+55 11 9%04d-%04d", rnd.nextInt(10000), rnd.nextInt(10000));
    }

    private static String email(Random rnd) {
        return letters(rnd, LOWER, 4 + rnd.nextInt(7)) + "@example.com";
    }

    private static String isoDate(Random rnd) {
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - rnd.nextInt(11);
        int month = 1 + rnd.nextInt(12);
        int day = 1 + rnd.nextInt(28);
        return String.format("%04d-%02d-%02d", year, month, day);
    }

    private static String isoTime(Random rnd) {
        return String.format("%02d:%02d:%02d", rnd.nextInt(24), rnd.nextInt(60), rnd.nextInt(60));
    }

    private static String letters(Random rnd, char[] pool, int len) {
        char[] out = new char[len];
        for (int i = 0; i < len; i++) {
            out[i] = pool[rnd.nextInt(pool.length)];
        }
        return new String(out);
    }

    /** Legacy fallback shape: lowercase letters, length 6..12. */
    private static String legacyString(Random rnd) {
        return letters(rnd, LOWER, 6 + rnd.nextInt(7));
    }
}
