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

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a value as one line of JSON.
 *
 * <p>One record is one line, by construction rather than by convention: every control character —
 * the newline first among them — leaves here as an escape sequence, so no value a device can
 * produce can split a record in two. That is the whole point. The trace is captured as the
 * process's stdout and read back line by line, and a record that wrapped would be a record that
 * silently became two.
 *
 * <p>This is deliberately hand-rolled rather than {@code org.json}. The jar dexes against Android's
 * stub classes, where {@code org.json} exists but its behavior at build time and at run time are
 * not the same object; and a serializer with no dependency is one fewer thing that can differ
 * between the JVM the tests run on and the Dalvik VM the trace is produced on.
 *
 * <p>{@code RUN_START} is the first user, and this class is the seed of the stage-4 NDJSON sink:
 * that stage grows the writer, not the format, so a {@code RUN_START} line captured today parses
 * unchanged afterwards.
 *
 * <p>The value grammar is small on purpose: strings, numbers, booleans, null, {@code Map} objects
 * and {@code Collection} arrays, nested freely. Anything else raises rather than falling back to
 * {@code toString()} — a silent {@code toString()} is how a record acquires a field that looks
 * like data and is not.
 */
public final class Json {

    private Json() {
    }

    /**
     * Renders a map as a JSON object, with the members in the map's own iteration order.
     *
     * @param members the object's members; iteration order is the emitted field order, so pass an
     *        ordered map when the field order matters
     * @return the object as a single line of JSON, with no trailing newline
     * @throws IllegalArgumentException if a value is outside the supported grammar
     */
    public static String object(Map<String, ?> members) {
        StringBuilder out = new StringBuilder();
        appendObject(out, members);
        return out.toString();
    }

    private static void appendObject(StringBuilder out, Map<?, ?> members) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> member : members.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendString(out, String.valueOf(member.getKey()));
            out.append(':');
            appendValue(out, member.getValue());
        }
        out.append('}');
    }

    private static void appendValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            appendString(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Number) {
            appendNumber(out, (Number) value);
        } else if (value instanceof Map) {
            appendObject(out, (Map<?, ?>) value);
        } else if (value instanceof Collection) {
            out.append('[');
            boolean first = true;
            for (Object element : (Collection<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                appendValue(out, element);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException(
                    "no JSON rendering for " + value.getClass().getName() + ": " + value);
        }
    }

    /**
     * Appends a number, refusing the three doubles that have no JSON spelling. Emitting a bare
     * {@code NaN} would produce a line that looks like a record and does not parse as one, which
     * is worse than failing where the value was formed.
     */
    private static void appendNumber(StringBuilder out, Number value) {
        double magnitude = value.doubleValue();
        if (Double.isNaN(magnitude) || Double.isInfinite(magnitude)) {
            throw new IllegalArgumentException("JSON has no representation for " + value);
        }
        out.append(value.toString());
    }

    private static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        // Every remaining control character, including the ones a widget's text can
                        // carry off a device (NUL, the ASCII separators) and which have no short
                        // escape of their own.
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
    }
}
