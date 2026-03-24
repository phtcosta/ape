/*
 * Copyright 2026 University of Brasilia - RVSEC Research Infrastructure
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
package com.android.commands.monkey.ape.utils;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.android.commands.monkey.ape.tree.GUITreeNode;

/**
 * Generates context-aware text input for EditText widgets based on field type
 * detection. Categories are detected from the widget's resourceId,
 * contentDescription, and isPassword() flag. Each category maps to a rotating
 * list of predefined values.
 *
 * <p>This class has ZERO Android runtime dependencies — all category detection
 * uses plain Java strings extracted from GUITreeNode.
 *
 * <p>Phase: gh9-exploration-refactor, Group 5.
 */
public class InputValueGenerator {

    /**
     * Input field categories detected from widget metadata.
     */
    public enum InputCategory {
        EMAIL, PASSWORD, NUMBER, PHONE, URL, SEARCH, GENERIC
    }

    private static final String[] EMAIL_VALUES = {
        "test@example.com", "user@test.org", "a@b.c"
    };

    private static final String[] PASSWORD_VALUES = {
        "Test1234!", "Password123", "Aa1!aaaa"
    };

    private static final String[] NUMBER_VALUES = {
        "42", "0", "999"
    };

    private static final String[] PHONE_VALUES = {
        "+5561999990000", "123456789"
    };

    private static final String[] URL_VALUES = {
        "https://example.com", "http://test.org"
    };

    private static final String[] SEARCH_VALUES = {
        "test", "crypto", "settings"
    };

    /** Per-widget rotation counters, keyed by widget identifier. */
    private final Map<String, Integer> rotationCounters = new HashMap<>();

    /**
     * Detects the input category for a GUITreeNode.
     *
     * @param node the EditText widget node (must not be null)
     * @return the detected InputCategory (never null)
     */
    public InputCategory detectCategory(GUITreeNode node) {
        return detectCategory(
                node.isPassword(),
                node.getResourceID(),
                node.getContentDesc());
    }

    /**
     * Detects the input category from extracted widget properties.
     * This method has no Android dependencies and is directly testable.
     *
     * <p>Priority order (from spec):
     * <ol>
     *   <li>isPassword flag</li>
     *   <li>resourceId keywords (email, password/passwd, phone/tel, url/website/uri,
     *       number/amount/quantity/price/count, search)</li>
     *   <li>contentDesc keywords (same set)</li>
     *   <li>GENERIC fallback</li>
     * </ol>
     *
     * @param isPassword whether the field is a password input
     * @param resourceId resource identifier (may be null)
     * @param contentDesc accessibility content description (may be null)
     * @return the detected InputCategory (never null)
     */
    public InputCategory detectCategory(boolean isPassword, String resourceId, String contentDesc) {
        // 1. isPassword flag has highest priority
        if (isPassword) {
            return InputCategory.PASSWORD;
        }

        // 2. Check resourceId keywords
        if (resourceId != null) {
            InputCategory fromResource = matchKeywords(resourceId);
            if (fromResource != InputCategory.GENERIC) {
                return fromResource;
            }
        }

        // 3. Check contentDesc keywords
        if (contentDesc != null) {
            InputCategory fromDesc = matchKeywords(contentDesc);
            if (fromDesc != InputCategory.GENERIC) {
                return fromDesc;
            }
        }

        // 4. Fallback
        return InputCategory.GENERIC;
    }

    /**
     * Matches a text against category keywords (case-insensitive).
     * Keyword priority follows spec order: email > password > phone > url > number > search.
     */
    private InputCategory matchKeywords(String text) {
        String lower = text.toLowerCase(Locale.US);

        if (lower.contains("email")) {
            return InputCategory.EMAIL;
        }
        if (lower.contains("password") || lower.contains("passwd")) {
            return InputCategory.PASSWORD;
        }
        if (lower.contains("phone") || lower.contains("tel")) {
            return InputCategory.PHONE;
        }
        if (lower.contains("url") || lower.contains("website") || lower.contains("uri")) {
            return InputCategory.URL;
        }
        if (lower.contains("number") || lower.contains("amount")
                || lower.contains("quantity") || lower.contains("price")
                || lower.contains("count")) {
            return InputCategory.NUMBER;
        }
        if (lower.contains("search")) {
            return InputCategory.SEARCH;
        }

        return InputCategory.GENERIC;
    }

    /**
     * Generates a context-appropriate input value for the given EditText node.
     * Values rotate cyclically per widget on repeated visits.
     *
     * @param node the EditText widget node (must not be null)
     * @return a non-null input string
     */
    public String generateForNode(GUITreeNode node) {
        return generate(
                node.isPassword(),
                node.getResourceID(),
                node.getContentDesc(),
                getWidgetId(node));
    }

    /**
     * Generates a context-appropriate input value from extracted widget properties.
     * This method has no Android dependencies and is directly testable.
     *
     * @param isPassword whether the field is a password input
     * @param resourceId resource identifier (may be null)
     * @param contentDesc content description (may be null)
     * @param widgetId unique widget identifier for rotation tracking
     * @return a non-null input string
     */
    public String generate(boolean isPassword, String resourceId, String contentDesc, String widgetId) {
        InputCategory category = detectCategory(isPassword, resourceId, contentDesc);
        return generateForCategory(category, widgetId);
    }

    /**
     * Returns the next value for the given category, rotating per widget.
     */
    private String generateForCategory(InputCategory category, String widgetId) {
        String[] values = getValuesForCategory(category);
        if (values == null) {
            // GENERIC: delegate to StringCache
            return StringCache.nextString();
        }

        int index = rotationCounters.getOrDefault(widgetId, 0);
        String result = values[index % values.length];
        rotationCounters.put(widgetId, index + 1);
        return result;
    }

    /**
     * Returns the predefined value array for a category, or null for GENERIC.
     */
    private static String[] getValuesForCategory(InputCategory category) {
        switch (category) {
            case EMAIL:    return EMAIL_VALUES;
            case PASSWORD: return PASSWORD_VALUES;
            case NUMBER:   return NUMBER_VALUES;
            case PHONE:    return PHONE_VALUES;
            case URL:      return URL_VALUES;
            case SEARCH:   return SEARCH_VALUES;
            case GENERIC:  return null;
            default:       return null;
        }
    }

    /**
     * Derives a stable widget identifier from a GUITreeNode.
     * Uses resourceId when available, falls back to contentDesc, then "unknown".
     */
    private static String getWidgetId(GUITreeNode node) {
        String rid = node.getResourceID();
        if (rid != null && !rid.isEmpty()) {
            return rid;
        }
        String desc = node.getContentDesc();
        if (desc != null && !desc.isEmpty()) {
            return desc;
        }
        return "unknown";
    }
}
