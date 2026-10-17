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
     * The sentences this group adds to the system prompt of {@code variant}.
     *
     * @param variant the prompt variant ({@code ape.llmPromptVariant})
     */
    default String systemSentence(String variant) {
        return "";
    }

    /** The marker this group puts on an action's line, such as {@code [M]}. */
    default String lineMarker(ModelAction a) {
        return "";
    }

    /** The detail this group appends to an action's line, such as the widget metadata. */
    default String lineDetail(ModelAction a) {
        return "";
    }

    /** The line this group adds to the screen context, such as the MOP count. */
    default String contextLine(State s, List<ModelAction> actions) {
        return "";
    }

    /** The section this group adds to the user text, such as {@code MOP NAVIGATION}. */
    default String section(State s, List<ModelAction> actions) {
        return "";
    }
}
