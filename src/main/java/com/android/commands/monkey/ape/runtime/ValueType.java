/*
 * Copyright 2020 Advanced Software Technologies Lab at ETH Zurich, Switzerland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.commands.monkey.ape.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The declared type of an {@code ape.*} key, and the only place a property string becomes a value.
 *
 * <p>Two rules here are behavior changes rather than restatements, and both close holes the old
 * loader had. A numeric key whose value does not parse <em>aborts</em>, where {@code Config}'s
 * getters swallowed the {@code NumberFormatException} and returned the default. And a boolean must
 * be literally {@code true} or {@code false} (case-insensitive), where {@code Boolean.valueOf}
 * mapped every other string — including {@code ture} — to {@code false} with no error path at all.
 *
 * <p>{@link #sameValue} compares by parsed value, not by text, so the neutral-value check treats
 * {@code 0} and {@code 0.0} as the same number and {@code TRUE} as {@code true}.
 *
 * <p>{@link #MOP_SCORING} is the one string type whose value set is closed
 * (llm-coordinate-single-base, design D18): a value other than exactly {@code flag} or
 * {@code distance} aborts like a non-boolean value of a boolean key, where a plain string would
 * have let a misspelt mode through to whatever reads it.
 *
 * <p>{@link #INT_LIST} is the type of {@code ape.mopWeights}, the {@code distance} ladder: 1 to
 * {@value #INT_LIST_MAX} non-negative {@code int}s separated by commas. The bounds belong to the
 * type, so a value outside them aborts as {@code invalid_type} rather than being clamped.
 */
public enum ValueType {
    BOOLEAN,
    INT,
    LONG,
    DOUBLE,
    STRING,
    /** {@code ape.mopScoring}: a string matched exactly against {@link MopScoring}'s wire names. */
    MOP_SCORING,
    /** {@code ape.mopWeights}: 1 to {@value #INT_LIST_MAX} comma-separated non-negative integers. */
    INT_LIST;

    /** The most elements an {@link #INT_LIST} value may have. */
    public static final int INT_LIST_MAX = 10;

    /**
     * Parses {@code raw} as this type.
     *
     * @return a {@link Boolean}, {@link Integer}, {@link Long}, {@link Double}, {@link String}, or
     *         for {@link #INT_LIST} an unmodifiable {@code List<Integer>}
     * @throws RunSpecException with {@link RunSpecException.Reason#INVALID_TYPE} when it does not parse
     */
    public Object parse(String key, String raw) {
        switch (this) {
            case BOOLEAN: {
                String v = raw.trim().toLowerCase(Locale.ROOT);
                if ("true".equals(v)) {
                    return Boolean.TRUE;
                }
                if ("false".equals(v)) {
                    return Boolean.FALSE;
                }
                throw invalid(key, raw, "expected true or false");
            }
            case INT:
                try {
                    return Integer.valueOf(raw.trim());
                } catch (NumberFormatException e) {
                    throw invalid(key, raw, "expected an integer");
                }
            case LONG:
                try {
                    return Long.valueOf(raw.trim());
                } catch (NumberFormatException e) {
                    throw invalid(key, raw, "expected a long");
                }
            case DOUBLE:
                try {
                    return Double.valueOf(raw.trim());
                } catch (NumberFormatException e) {
                    throw invalid(key, raw, "expected a number");
                }
            case MOP_SCORING:
                // Exactly as written: no trimming and no case folding, so the value the record
                // echoes is the value the file states (run-spec "Total Fail-Fast Validation").
                if (MopScoring.fromWire(raw) == null) {
                    throw invalid(key, raw, "expected one of " + MopScoring.validSet());
                }
                return raw;
            case INT_LIST:
                return parseIntList(key, raw);
            default:
                return raw;
        }
    }

    /** True when both strings parse to the same value of this type. */
    public boolean sameValue(String key, String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return parse(key, a).equals(parse(key, b));
    }

    /**
     * The canonical rendering of a parsed value, used by the plan digest so that {@code 0} and
     * {@code 0.0} — the same number written two ways — cannot produce two digests.
     */
    public String canonical(String key, String raw) {
        if (raw == null) {
            return "";
        }
        Object value = parse(key, raw);
        if (this == INT_LIST) {
            StringBuilder joined = new StringBuilder();
            for (Object element : (List<?>) value) {
                joined.append(joined.length() == 0 ? "" : ",").append(element);
            }
            return joined.toString();
        }
        return String.valueOf(value);
    }

    /**
     * Splits on {@code ,} keeping empty elements, so {@code 500,,300} and a trailing comma fail,
     * and parses each element with the {@link #INT} rule.
     */
    private static List<Integer> parseIntList(String key, String raw) {
        String expectation = "expected 1 to " + INT_LIST_MAX
                + " comma-separated non-negative integers";
        if (raw.trim().isEmpty()) {
            throw invalid(key, raw, expectation);
        }
        String[] elements = raw.split(",", -1);
        if (elements.length > INT_LIST_MAX) {
            throw invalid(key, raw, expectation);
        }
        List<Integer> list = new ArrayList<>(elements.length);
        for (String element : elements) {
            int value;
            try {
                value = (Integer) INT.parse(key, element);
            } catch (RunSpecException e) {
                throw invalid(key, raw, expectation);
            }
            if (value < 0) {
                throw invalid(key, raw, expectation);
            }
            list.add(value);
        }
        return Collections.unmodifiableList(list);
    }

    private static RunSpecException invalid(String key, String raw, String expectation) {
        return new RunSpecException(RunSpecException.Reason.INVALID_TYPE, key,
                "value '" + raw + "' is not valid: " + expectation);
    }
}
