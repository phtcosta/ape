package com.android.commands.monkey.ape.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks per-activity iteration budgets. Each activity is allocated a budget
 * of {@code baseBudget + widgetCount * budgetPerWidget} iterations at registration
 * time. Budget is computed once and never recalculated or reset.
 */
public class ActivityBudgetTracker {

    private final int baseBudget;
    private final int budgetPerWidget;
    private final Map<String, Integer> budgets = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();

    public ActivityBudgetTracker(int baseBudget, int budgetPerWidget) {
        this.baseBudget = baseBudget;
        this.budgetPerWidget = budgetPerWidget;
    }

    /**
     * Allocates a budget for the given activity. Idempotent: if the activity
     * is already registered, the call is ignored (budget is not recalculated).
     */
    public void registerActivity(String activityName, int widgetCount) {
        if (budgets.containsKey(activityName)) {
            return;
        }
        budgets.put(activityName, baseBudget + widgetCount * budgetPerWidget);
        counts.put(activityName, 0);
    }

    /**
     * Increments the iteration counter for the specified activity.
     */
    public void recordIteration(String activityName) {
        Integer count = counts.get(activityName);
        if (count != null) {
            counts.put(activityName, count + 1);
        }
    }

    /**
     * Returns {@code true} when the iteration count equals or exceeds the
     * allocated budget. Returns {@code false} for unregistered activities.
     */
    public boolean isBudgetExhausted(String activityName) {
        Integer budget = budgets.get(activityName);
        if (budget == null) {
            return false;
        }
        Integer count = counts.get(activityName);
        return count != null && count >= budget;
    }

    /**
     * Returns the remaining budget for the activity, clamped to 0 minimum.
     * Returns {@code Integer.MAX_VALUE} for unregistered activities.
     */
    public int getRemainingBudget(String activityName) {
        Integer budget = budgets.get(activityName);
        if (budget == null) {
            return Integer.MAX_VALUE;
        }
        Integer count = counts.get(activityName);
        int remaining = budget - (count != null ? count : 0);
        return Math.max(0, remaining);
    }
}
