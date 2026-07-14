package com.android.commands.monkey;

import com.android.commands.monkey.ape.ForeignActivityGuard;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * foreign-activity-guard §3 — JVM tests for the pure guard decision seam
 * {@code ForeignActivityGuard.shouldModel} and the once-per-package log-throttle
 * semantics.
 *
 * The decision + whitelist were extracted from {@code MonkeySourceApe} into the
 * dependency-free {@code ForeignActivityGuard} precisely so they are JVM-testable:
 * {@code MonkeySourceApe} itself cannot be class-loaded off-device (its
 * {@code UiAutomation} field pulls in {@code android.app.IUiAutomationConnection},
 * absent from the JVM test classpath). The guard wiring inside {@code generateEvents}
 * (BACK enqueue + return) requires the Android runtime and is covered by the deferred
 * device smoke (task 4.3). Here we exercise only the side-effect-free decision
 * (INV-EXPL-20/-21) and the throttle Set contract.
 */
public class MonkeySourceApeForeignGuardTest {

    private static final String APP = "com.example.app";

    // ---- shouldModel matrix (INV-EXPL-20/-21) --------------------------------

    @Test
    public void testInPackageAccepted() {
        // filterAccepts == true → modeled regardless of whitelist
        assertTrue(ForeignActivityGuard.shouldModel(APP, true, ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES));
    }

    @Test
    public void testForeignRejected() {
        assertFalse(ForeignActivityGuard.shouldModel("com.google.android.apps.nexuslauncher", false,
                ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES));
    }

    @Test
    public void testWhitelistPackageInstallerAccepted() {
        assertTrue(ForeignActivityGuard.shouldModel("com.android.packageinstaller", false,
                ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES));
    }

    @Test
    public void testWhitelistAospPermissionControllerAccepted() {
        assertTrue(ForeignActivityGuard.shouldModel("com.android.permissioncontroller", false,
                ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES));
    }

    @Test
    public void testWhitelistGooglePermissionControllerAccepted() {
        assertTrue(ForeignActivityGuard.shouldModel("com.google.android.permissioncontroller", false,
                ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES));
    }

    @Test
    public void testSystemUiNotWhitelisted() {
        assertFalse(ForeignActivityGuard.shouldModel("com.android.systemui", false,
                ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES));
    }

    @Test
    public void testNullPackageAccepted() {
        assertTrue(ForeignActivityGuard.shouldModel(null, false, ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES));
    }

    @Test
    public void testWhitelistSetContents() {
        assertTrue(ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES.contains("com.android.packageinstaller"));
        assertTrue(ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES.contains("com.android.permissioncontroller"));
        assertTrue(ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES.contains("com.google.android.permissioncontroller"));
        assertFalse(ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES.contains("com.android.systemui"));
        assertEquals(3, ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES.size());
    }

    // ---- log-throttle Set contract (task 3.2) --------------------------------

    @Test
    public void testLogThrottleFirstDeflectionSignals() {
        Set<String> deflected = new HashSet<>();
        String pkg = "com.google.android.apps.nexuslauncher";
        assertTrue("first deflection of a package should signal a log", deflected.add(pkg));
        assertFalse("repeat deflection of the same package should not log", deflected.add(pkg));
    }
}
