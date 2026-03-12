package com.android.commands.monkey.ape.utils;

/**
 * Maps MOP reachability flags to integer priority boosts for action scoring.
 *
 * Scale (mirrors rvsmart):
 *   directMop              → +500
 *   transitiveMop (only)   → +300
 *   activityHasMop (no widget match) → +100
 *   no match               →    0
 */
public class MopScorer {

    /**
     * Returns the priority boost for an action targeting the given widget.
     *
     * @param activity activity class name
     * @param shortId  short resource ID (e.g. "btn_encrypt")
     * @param data     loaded MOP data (must be non-null)
     * @return integer boost to add to existing action priority
     */
    public static int score(String activity, String shortId, MopData data) {
        MopData.WidgetMopFlags f = data.getWidget(activity, shortId);
        if (f != null) {
            if (f.directMop) {
                return 500;
            }
            if (f.transitiveMop) {
                return 300;
            }
            // Widget found but no MOP flags — no boost (not the activity-level fallback case)
            return 0;
        }
        if (data.activityHasMop(activity)) {
            return 100;
        }
        return 0;
    }
}
