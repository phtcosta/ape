package com.android.commands.monkey.ape;

import java.util.Set;

/**
 * tree-package-guard — the pure, Android-free decision for whether the (topComp, tree) pair
 * captured in {@code MonkeySourceApe.generateEvents} is a foreign-tree mismatch that should be
 * re-fetched. Extracted (like {@link ForeignActivityGuard}) so it is JVM-testable: the
 * enclosing {@code MonkeySourceApe} cannot be class-loaded off-device.
 *
 * The whitelist is shared with {@link ForeignActivityGuard#SYSTEM_INTERACTION_PACKAGES} — a
 * package the foreign guard would not BACK out of is also not treated as a tree mismatch here.
 */
public final class TreePackageGuard {

    private TreePackageGuard() {
    }

    /**
     * Returns true (re-fetch) only when the tree is owned by a different, non-whitelisted
     * package than the top activity — i.e. all of: {@code treePkg != null},
     * {@code !treePkg.equals(topPkg)}, and {@code !systemWhitelist.contains(treePkg)}.
     * Returns false (proceed to model) for matching packages, a null {@code treePkg}
     * (uncheckable), or a whitelisted tree owner. Pure, no I/O.
     */
    public static boolean shouldRefetch(String topPkg, String treePkg, Set<String> systemWhitelist) {
        if (treePkg == null || treePkg.equals(topPkg)) {
            return false;
        }
        return !systemWhitelist.contains(treePkg);
    }
}
