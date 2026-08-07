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
 */
public enum ValueType {
    BOOLEAN,
    INT,
    LONG,
    DOUBLE,
    STRING;

    /**
     * Parses {@code raw} as this type.
     *
     * @return a {@link Boolean}, {@link Integer}, {@link Long}, {@link Double} or {@link String}
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
        return raw == null ? "" : String.valueOf(parse(key, raw));
    }

    private static RunSpecException invalid(String key, String raw, String expectation) {
        return new RunSpecException(RunSpecException.Reason.INVALID_TYPE, key,
                "value '" + raw + "' is not valid: " + expectation);
    }
}
