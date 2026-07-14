package com.android.commands.monkey;

import com.android.commands.monkey.ape.ForeignActivityGuard;
import com.android.commands.monkey.ape.TreePackageGuard;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * tree-package-guard §3 — JVM tests for the pure decision seam
 * {@code TreePackageGuard.shouldRefetch} and the once-per-pair log-throttle semantics.
 *
 * The guard wiring inside {@code generateEvents} (refetch {@code continue} / fail-open
 * fall-through) requires the Android runtime and is covered by the deferred device smoke
 * (task 4.3). Here we exercise only the side-effect-free decision (INV-EXPL-26) and the
 * throttle Set contract. The whitelist is shared with {@code ForeignActivityGuard}.
 */
public class MonkeySourceApeTreeGuardTest {

    private static final Set<String> WL = ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES;
    private static final String APP = "com.example.app";

    // ---- shouldRefetch matrix (INV-EXPL-26) ----------------------------------

    @Test
    public void testMatchingPackagesNoRefetch() {
        assertFalse(TreePackageGuard.shouldRefetch(APP, APP, WL));
    }

    @Test
    public void testForeignTreeRefetches() {
        assertTrue(TreePackageGuard.shouldRefetch(APP, "com.google.android.apps.nexuslauncher", WL));
    }

    @Test
    public void testWhitelistedTreeOwnerNoRefetch_packageInstaller() {
        assertFalse(TreePackageGuard.shouldRefetch(APP, "com.android.packageinstaller", WL));
    }

    @Test
    public void testWhitelistedTreeOwnerNoRefetch_aospPermissionController() {
        assertFalse(TreePackageGuard.shouldRefetch(APP, "com.android.permissioncontroller", WL));
    }

    @Test
    public void testWhitelistedTreeOwnerNoRefetch_googlePermissionController() {
        assertFalse(TreePackageGuard.shouldRefetch(APP, "com.google.android.permissioncontroller", WL));
    }

    @Test
    public void testNullTreePkgNoRefetch() {
        assertFalse(TreePackageGuard.shouldRefetch(APP, null, WL));
    }

    // ---- log-throttle Set contract (task 3.2) --------------------------------

    @Test
    public void testLogThrottlePerPair() {
        Set<String> mismatches = new HashSet<>();
        String pairA = APP + "->com.google.android.apps.nexuslauncher";
        String pairB = APP + "->com.android.systemui";
        assertTrue("first mismatch of a pair should signal a log", mismatches.add(pairA));
        assertFalse("repeat of the same pair should not log", mismatches.add(pairA));
        assertTrue("a different pair should signal again", mismatches.add(pairB));
    }
}
