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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The MOP scoring mode, {@code ape.mopScoring} (llm-coordinate-single-base, design D18), and the
 * keys each mode reads.
 *
 * <p>{@code flag} is the E6 yes/no mark and the default; {@code distance} weighs an action by its
 * nearest unretired target. The two modes read disjoint key sets (run-spec INV-RUN-09): a key of
 * the set the selected mode does not read is accepted at any value of its type, parameterizes
 * nothing, and is listed in {@code RUN_START.inert} when the plan states it. Every other
 * {@code MOP} key, the mode key among them, is read in both modes.
 *
 * <p>This enum is the single source of the closed value set: {@link ValueType#MOP_SCORING} parses
 * against {@link #wireName()}, and {@code RunSpec} derives the mode-inert keys from
 * {@link #unreadKeys()}.
 */
public enum MopScoring {

    /** The E6 mark: {@code ape.mopWeightDirect} for a direct flag, the transitive weight else. */
    FLAG("ape.mopWeightDirect", "ape.mopWeightTransitive"),

    /** The weight of the nearest unretired target, with retirement and the ordered launcher. */
    DISTANCE("ape.mopWeightD1", "ape.mopWeightD2", "ape.mopWeightD3", "ape.mopRetireAfter",
            "ape.mopLauncherDmax");

    /** The key that selects the mode. */
    public static final String KEY = "ape.mopScoring";

    private final Set<String> modeKeys;

    MopScoring(String... modeKeys) {
        this.modeKeys = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(modeKeys)));
    }

    /** The value as the properties file and {@code RUN_START} spell it: {@code flag}, {@code distance}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The {@code MOP} keys only this mode reads. */
    public Set<String> modeKeys() {
        return modeKeys;
    }

    /** The {@code MOP} keys the other mode reads and this one does not (INV-RUN-09). */
    public Set<String> unreadKeys() {
        Set<String> unread = new LinkedHashSet<>();
        for (MopScoring other : values()) {
            if (other != this) {
                unread.addAll(other.modeKeys);
            }
        }
        return Collections.unmodifiableSet(unread);
    }

    /** The mode spelled exactly {@code raw}, or null when no mode is spelled so. */
    public static MopScoring fromWire(String raw) {
        for (MopScoring mode : values()) {
            if (mode.wireName().equals(raw)) {
                return mode;
            }
        }
        return null;
    }

    /** The valid set as the abort diagnostic names it: {@code {flag, distance}}. */
    public static String validSet() {
        List<String> names = new ArrayList<>();
        for (MopScoring mode : values()) {
            names.add(mode.wireName());
        }
        return "{" + String.join(", ", names) + "}";
    }
}
