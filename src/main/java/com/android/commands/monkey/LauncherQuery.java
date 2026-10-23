package com.android.commands.monkey;

import java.util.ArrayList;
import java.util.List;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.ResolveInfo;
import android.os.RemoteException;
import android.os.UserHandle;

/**
 * Resolves the launcher components of the app under test: the activities the package manager resolves for
 * {@link Intent#ACTION_MAIN} with each main category, restricted to the packages the package filter admits.
 * Startup ({@code Monkey.getMainApps}) and the relaunches of {@link MonkeySourceApe} (exploration INV-EXPL-33)
 * both call it, so the two read one definition.
 */
final class LauncherQuery {

    private LauncherQuery() {
    }

    /**
     * Queries the package manager for the {@link Intent#ACTION_MAIN} activities of each category and keeps those
     * whose package the package filter admits.
     *
     * <p>Categories are queried in list order and their results concatenated, so a component resolved under two
     * categories appears twice.
     *
     * @param pm the package manager
     * @param categories the main categories; an empty category adds none to its intent
     * @return the admitted launcher components, in the package manager's order per category; empty, never null,
     *         when nothing matches
     */
    static List<ComponentName> query(IPackageManager pm, List<String> categories) {
        List<ComponentName> launchers = new ArrayList<ComponentName>();
        try {
            for (String category : categories) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                if (category.length() > 0) {
                    intent.addCategory(category);
                }
                List<ResolveInfo> activities = ApeAPIAdapter.queryIntentActivities(pm, intent, UserHandle.myUserId());
                if (activities == null) {
                    continue;
                }
                for (ResolveInfo r : activities) {
                    String packageName = r.activityInfo.applicationInfo.packageName;
                    if (MonkeyUtils.getPackageFilter().checkEnteringPackage(packageName)) {
                        launchers.add(new ComponentName(packageName, r.activityInfo.name));
                    }
                }
            }
        } catch (RemoteException e) {
            // ApeAPIAdapter.invoke exits the process on a binder failure; the declared exception reads as no match.
            return new ArrayList<ComponentName>();
        }
        return launchers;
    }
}
