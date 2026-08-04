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
 * <p>There are two entry points onto one format. {@link #object(Map)} renders a value tree and is
 * what the run-level records use, where a map per line is nothing; {@link Buf} writes field by
 * field into a buffer that outlives the record, which is what a record per step needs. They are
 * the same renderer — {@code Buf} calls the same {@code appendString}/{@code appendNumber} — and a
 * test asserts that the same tree through either one is the same line, so "grows the writer, not
 * the format" stays true rather than merely intended.
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
        refuseNonFinite(value, value.doubleValue());
        out.append(value.toString());
    }

    /**
     * Refuses the three doubles JSON cannot spell. The value and the magnitude are separate
     * arguments because they are not always the same object: on the boxed path the {@link Number}
     * is what the message should name and its {@code doubleValue()} is what the test needs, while a
     * primitive {@code double} arrives as both.
     */
    private static void refuseNonFinite(Object value, double magnitude) {
        if (Double.isNaN(magnitude) || Double.isInfinite(magnitude)) {
            throw new IllegalArgumentException("JSON has no representation for " + value);
        }
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
                case '\u2028':
                case '\u2029':
                    // Legal JSON unescaped, and still a line break for every reader in the
                    // JavaScript family. A record carrying one raw would parse everywhere and
                    // split in exactly the tools that read it line by line, which is the failure
                    // this class exists to make impossible rather than rare.
                    out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
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

    /**
     * One record under construction, written field by field into a buffer that survives it.
     *
     * <p>{@link Json#object(Map)} builds a value tree per call: the map, the boxed numbers and the
     * collections holding them are all allocated to be discarded a line later. That is the right
     * shape for the handful of run-level records and the wrong one for a record per step across a
     * whole run, which is what this class is for — {@link #reset()} returns the same
     * {@link StringBuilder} to empty, so a step at steady state costs the characters of its own
     * line and nothing else.
     *
     * <p>Nesting is the caller's obligation: {@code beginObject}/{@code endObject} and
     * {@code beginArray}/{@code endArray} must balance, and inside an object every value must be
     * preceded by its {@link #name(String)}. Unbalanced use is a programming error, caught by the
     * tests rather than by a runtime check on the step path — a guard there would cost every step
     * to protect against a defect that cannot reach production without failing a test first.
     * Commas are not the caller's obligation: the buffer knows whether the current level already
     * holds a member, which is the one piece of bookkeeping a hand-written record is likely to get
     * wrong.
     *
     * <p>Every writing method returns the buffer, so one record is one chained expression and the
     * shape of the call site is the shape of the line it produces.
     */
    public static final class Buf {

        private final StringBuilder out = new StringBuilder(512);

        /** Whether the current nesting level already holds a member, hence needs a separator. */
        private boolean afterMember;

        /** Returns the buffer to empty, ready for the next record. */
        public Buf reset() {
            out.setLength(0);
            afterMember = false;
            return this;
        }

        /**
         * Opens an object, wherever an object may stand: the start of a record, the value after a
         * {@link #name(String)}, or an element of an array — which is why it emits a separator
         * first, exactly as any other value does.
         */
        public Buf beginObject() {
            comma();
            out.append('{');
            afterMember = false;
            return this;
        }

        /** Closes the innermost object, which thereby becomes a member of whatever encloses it. */
        public Buf endObject() {
            out.append('}');
            afterMember = true;
            return this;
        }

        /** Opens an array, in any of the positions {@link #beginObject()} may be opened in. */
        public Buf beginArray() {
            comma();
            out.append('[');
            afterMember = false;
            return this;
        }

        /** Closes the innermost array, which thereby becomes a member of whatever encloses it. */
        public Buf endArray() {
            out.append(']');
            afterMember = true;
            return this;
        }

        /** Opens a member of the enclosing object. The key is escaped like any other string. */
        public Buf name(String key) {
            comma();
            appendString(out, key);
            out.append(':');
            afterMember = false;
            return this;
        }

        /** Appends a string value, or JSON {@code null} for a null reference. */
        public Buf value(String value) {
            comma();
            if (value == null) {
                out.append("null");
            } else {
                appendString(out, value);
            }
            afterMember = true;
            return this;
        }

        public Buf value(long value) {
            comma();
            out.append(value);
            afterMember = true;
            return this;
        }

        /**
         * Appends a floating-point value.
         *
         * @throws IllegalArgumentException for {@code NaN} and the two infinities
         */
        public Buf value(double value) {
            refuseNonFinite(value, value);
            comma();
            out.append(value);
            afterMember = true;
            return this;
        }

        public Buf value(boolean value) {
            comma();
            out.append(value);
            afterMember = true;
            return this;
        }

        /**
         * The completed record as one line, with no trailing newline: the record terminator is the
         * single newline the sink writes around it, and this string never contains one of its own.
         */
        public String toLine() {
            return out.toString();
        }

        private void comma() {
            if (afterMember) {
                out.append(',');
            }
        }
    }
}
