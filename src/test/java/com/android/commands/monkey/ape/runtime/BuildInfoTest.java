package com.android.commands.monkey.ape.runtime;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The build provenance stamp, checked for the one thing a unit test can check about it: that Maven
 * actually filled it in.
 *
 * <p>These assertions deliberately stop short of the value. Pinning the current commit's sha would
 * make the test fail on the very next commit — a provenance guard that breaks whenever provenance
 * changes is the opposite of one. What is asserted instead is that the constants carry a filtered
 * value rather than an unsubstituted {@code ${...}} placeholder, and that the value has the shape
 * its source promises.
 *
 * <p>{@code unknown} is accepted for both, and is not a failure: a tree built without a {@code .git}
 * directory is a supported case, and the suite has to pass there too.
 *
 * <p>The constants are compile-time constants, so what these assertions read is the value javac
 * inlined from the generated {@code BuildInfo} — that is, the value this build filtered. The
 * assertions that a unit test cannot make — that the constant survives {@code d8} into
 * {@code classes.dex}, and that the jar gained no resource on the way — are made against the
 * packaged jar by task 2.3 of this change.
 */
public class BuildInfoTest {

    /** Seven to forty lowercase hex digits: git's abbreviated form through the full sha. */
    private static final String SHA_SHAPE = "[0-9a-f]{7,40}";

    /** The UTC instant format pinned in the pom's dateFormat. */
    private static final String BUILT_SHAPE = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z";

    private static final String UNKNOWN = "unknown";

    @Test
    public void gitShaIsFilledIn() {
        String sha = BuildInfo.GIT_SHA;
        assertNotNull("GIT_SHA is null — the template was not filtered", sha);
        assertFalse("GIT_SHA still holds an unsubstituted placeholder: " + sha, sha.contains("${"));
        assertTrue("GIT_SHA is neither a short sha nor the sentinel: " + sha,
                UNKNOWN.equals(sha) || sha.matches(SHA_SHAPE));
    }

    @Test
    public void jarBuiltIsFilledIn() {
        String built = BuildInfo.JAR_BUILT;
        assertNotNull("JAR_BUILT is null — the template was not filtered", built);
        assertFalse("JAR_BUILT still holds an unsubstituted placeholder: " + built,
                built.contains("${"));
        assertTrue("JAR_BUILT is neither a UTC instant nor the sentinel: " + built,
                UNKNOWN.equals(built) || built.matches(BUILT_SHAPE));
    }
}
