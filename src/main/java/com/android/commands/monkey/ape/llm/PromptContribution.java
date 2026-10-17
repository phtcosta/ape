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
package com.android.commands.monkey.ape.llm;

import java.util.List;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;

/**
 * What one mounted group adds to the LLM prompt (llm-prompt, design § Interfaces).
 *
 * <p>{@code ApePromptBuilder} assembles its text from the contributions of the mounted groups —
 * MOP's markers, count, navigation section and system sentences, STATIC's widget metadata — and
 * never tests whether a group is present: an absent group has no contribution, and the prompt is
 * then the group-free wording. Every method defaults to the empty string, so a contribution states
 * only what it adds.
 */
public interface PromptContribution {

    /**
     * The text this group inserts at one extension point of a message. The builder calls it once
     * per point, and the text goes in exactly where the point is, so a point the group leaves empty
     * reads as the group-free sentence. The point keys are:
     * <ul>
     *   <li>{@code ape_current}, {@code ape_reasoning}, {@code compact_v1}, {@code v13}: the
     *       system message's priority clause, after {@code "PRIORITY: "} ({@code "3. Prioritize "}
     *       in {@code compact_v1});
     *   <li>{@code v17.step}: a reasoning step after {@code DIALOG}, with no number (the builder
     *       numbers the steps, so they stay consecutive whether or not this one is present);
     *   <li>{@code v17.elements}: the {@code ELEMENTS} step, after {@code "if no navigation"};
     *   <li>{@code v17.priority}: whole lines at the head of the {@code PRIORITY} list;
     *   <li>{@code v13.close}: the user text's closing line, after {@code "explore new UI states"};
     *   <li>{@code v17.close}: the user text's closing line, after {@code "Prioritize "}.
     * </ul>
     * {@code visual_only} has no point.
     *
     * @param point {@code "<variant>"}, or {@code "<variant>.<slot>"} where a message has more than
     *        one point
     */
    default String systemSentence(String point) {
        return "";
    }

    /**
     * The one marker this group puts on a line, such as {@code [M]}, from every action the line
     * renders: one action in the shared line formatter, all the target-requiring actions of the
     * widget in {@code v13}/{@code v17}. Ranking the actions' verdicts is the group's.
     */
    default String lineMarker(List<ModelAction> lineActions) {
        return "";
    }

    /** The detail this group appends to an action's line, such as the widget metadata. */
    default String lineDetail(ModelAction a) {
        return "";
    }

    /** The text this group appends to the visit context, such as the MOP count. */
    default String contextLine(State s, List<ModelAction> actions) {
        return "";
    }

    /**
     * The heading this group gives the {@code v17} navigation block, in place of the builder's
     * neutral {@code NAVIGATION:}, such as {@code MOP NAVIGATION:}.
     */
    default String section(State s, List<ModelAction> actions) {
        return "";
    }
}
