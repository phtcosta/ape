package com.android.commands.monkey.ape;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * foreign-activity-guard — the pure, Android-free decision for whether a screen may be
 * modeled, extracted so it is JVM-testable (the enclosing {@code MonkeySourceApe} cannot
 * be class-loaded off-device: its {@code UiAutomation} field pulls in
 * {@code android.app.IUiAutomationConnection}, which is absent from the JVM test classpath).
 *
 * The guard wiring (fresh top-activity fetch, BACK deflection, log throttle) stays in
 * {@code MonkeySourceApe.generateEvents}; only the side-effect-free predicate and the fixed
 * whitelist live here.
 */
public final class ForeignActivityGuard {

    private ForeignActivityGuard() {
    }

    /**
     * Packages the guard does not deflect (best-effort, guard-local — this does not grant
     * permissions or change {@code checkAppActivity}). The Google-image entry is what the
     * RVSec Google AVD actually shows for the runtime-permission UI; the AOSP entry alone
     * would never match there.
     */
    public static final Set<String> SYSTEM_INTERACTION_PACKAGES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "com.android.packageinstaller",
                    "com.android.permissioncontroller",
                    "com.google.android.permissioncontroller")));

    /**
     * Returns true when the screen may be modeled: the Monkey package filter accepts it, or
     * its package is a system-interaction whitelist entry. A null package is uncheckable and
     * treated as modelable (defer to the existing paths). Pure, no I/O.
     */
    public static boolean shouldModel(String pkg, boolean filterAccepts, Set<String> systemWhitelist) {
        if (pkg == null) {
            return true;
        }
        return filterAccepts || systemWhitelist.contains(pkg);
    }
}
