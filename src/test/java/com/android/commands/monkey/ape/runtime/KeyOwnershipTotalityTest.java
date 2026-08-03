package com.android.commands.monkey.ape.runtime;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.android.commands.monkey.ape.utils.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The claim that key ownership is total, checked against the tree rather than against the day it
 * was written.
 *
 * <p>Two guards, and they fail for different reasons. The <b>totality scan</b> walks
 * {@code src/main/java} for {@code "ape.*"} string literals and fails when one of them is neither
 * owned nor retired — so adding a key to the jar without giving it an owner breaks the build
 * instead of producing an unknown-key abort in the field. The <b>default drift guard</b> reflects
 * over {@code Config}'s public static fields and asserts each declared default against the value
 * the jar actually computes, so the two files cannot drift apart silently.
 *
 * <p>The scan is how {@code ape.baseNaming} and {@code ape.nopActionThrottle} were found: both are
 * read in production, and neither appears in any design document of this re-architecture.
 */
public class KeyOwnershipTotalityTest {

    private static final Pattern KEY_LITERAL = Pattern.compile("\"(ape\\.[A-Za-z0-9_]+)\"");

    /**
     * A floor on the scan's yield. Without it a broken walk would find nothing and the test would
     * pass by vacuity — the failure mode that makes a guard worse than no guard.
     */
    private static final int MINIMUM_KEYS_IN_TREE = 100;

    @Test
    public void everyKeyInTheSourceTreeIsOwnedOrRetired() throws IOException {
        Set<String> found = scanSourceTree();
        assertTrue("the scan found only " + found.size() + " ape.* literals in src/main/java; "
                + "expected at least " + MINIMUM_KEYS_IN_TREE + " — the walk is broken",
                found.size() >= MINIMUM_KEYS_IN_TREE);

        List<String> unowned = new ArrayList<>();
        for (String key : found) {
            if (!KeyOwnership.isKnown(key) && !KeyOwnership.isRetired(key)) {
                unowned.add(key);
            }
        }
        Collections.sort(unowned);
        assertEquals("keys read by the jar that no owner declares: " + unowned,
                Collections.emptyList(), unowned);
    }

    @Test
    public void noKeyIsBothOwnedAndRetired() {
        List<String> both = new ArrayList<>();
        for (String key : KeyOwnership.retiredKeys()) {
            if (KeyOwnership.isKnown(key)) {
                both.add(key);
            }
        }
        assertEquals("a retired key cannot also be owned: " + both,
                Collections.emptyList(), both);
    }

    @Test
    public void everyOwnedKeyHasExactlyOneOwner() {
        Set<String> base = KeyOwnership.keysOf(KeyOwnership.OwnerKind.BASE);
        Set<String> resolver = KeyOwnership.keysOf(KeyOwnership.OwnerKind.RESOLVER);
        Set<String> featureOwned = KeyOwnership.keysOf(KeyOwnership.OwnerKind.FEATURE);

        assertEquals("the three owner kinds must partition the table",
                KeyOwnership.allKeys().size(),
                base.size() + resolver.size() + featureOwned.size());

        // Every feature-owned key is claimed by exactly one Feature. The old registries could not
        // state this: they were lists of strings, so two of them naming the same key was a
        // review-time observation rather than a compile-time or test-time one.
        List<String> multiplyClaimed = new ArrayList<>();
        for (String key : featureOwned) {
            int claims = 0;
            for (Feature feature : Feature.values()) {
                if (feature.ownedKeys().contains(key)) {
                    claims++;
                }
            }
            if (claims != 1) {
                multiplyClaimed.add(key + " claimed by " + claims + " features");
            }
        }
        assertEquals(Collections.emptyList(), multiplyClaimed);

        for (String key : featureOwned) {
            Feature owner = KeyOwnership.spec(key).feature();
            assertTrue(key + " is declared under " + owner + " but " + owner + " does not own it",
                    owner.ownedKeys().contains(key));
        }
    }

    @Test
    public void everyFeatureOwnedKeyHasANeutralValueOrIsAnActivationPath() {
        for (Feature feature : Feature.values()) {
            for (String key : feature.ownedKeys()) {
                String neutral = feature.neutralValue(key);
                if (neutral == null) {
                    // Only PRESENT-activated features may lack a neutral: stating the path or the
                    // URL is what turns them on, so the key cannot be present while they are off.
                    assertEquals(key + " has no neutral value but is not an activation path",
                            Feature.Rule.PRESENT, feature.activationRule());
                    assertEquals(feature.activationKey(), key);
                } else {
                    // A neutral value must itself be a valid value of the key's declared type.
                    KeyOwnership.typeOf(key).parse(key, neutral);
                }
            }
        }
    }

    @Test
    public void declaredDefaultsMatchTheJarsOwnDefaults() throws IllegalAccessException {
        List<String> mismatches = new ArrayList<>();
        for (Field field : Config.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers()) || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String key = "ape." + field.getName();
            if (KeyOwnership.isRetired(key)) {
                continue;
            }
            if (!KeyOwnership.isKnown(key)) {
                mismatches.add(key + ": Config declares the field, the ownership table does not");
                continue;
            }
            Object jarValue = field.get(null);
            String declared = KeyOwnership.defaultOf(key);
            Object tableValue = declared == null
                    ? null : KeyOwnership.typeOf(key).parse(key, declared);
            if (jarValue == null ? tableValue != null : !jarValue.equals(tableValue)) {
                mismatches.add(key + ": Config=" + jarValue + " table=" + tableValue);
            }
        }
        Collections.sort(mismatches);
        assertEquals("the ownership table and Config disagree on defaults: " + mismatches,
                Collections.emptyList(), mismatches);
    }

    @Test
    public void theResolverOwnsExactlyPresetRunIdAndCorpusBasis() {
        assertEquals(new LinkedHashSet<>(java.util.Arrays.asList(
                        KeyOwnership.KEY_PRESET, KeyOwnership.KEY_RUN_ID,
                        KeyOwnership.KEY_CORPUS_BASIS)),
                KeyOwnership.keysOf(KeyOwnership.OwnerKind.RESOLVER));
    }

    @Test
    public void retiredKeysCarryAReasonNamingWhatReplacedThem() {
        Set<String> expected = new LinkedHashSet<>(java.util.Arrays.asList(
                "ape.apePureMode", "ape.modelFile", "ape.saveObjModel", "ape.saveDotGraph",
                "ape.saveVisGraph", "ape.enableXPathAction", "ape.mopWeightActivity",
                "ape.agentType", "ape.replayLog"));
        assertEquals(expected, KeyOwnership.retiredKeys());
        for (String key : expected) {
            String reason = KeyOwnership.retiredReason(key);
            assertNotNull(key + " has no reason", reason);
            assertFalse(key + "'s reason is too short to be a decision", reason.length() < 40);
        }
        assertNull(KeyOwnership.retiredReason("ape.defaultEpsilon"));
    }

    private static Set<String> scanSourceTree() throws IOException {
        final Set<String> keys = new LinkedHashSet<>();
        Path root = Paths.get("src", "main", "java");
        assertTrue("source tree not found at " + root.toAbsolutePath(), Files.isDirectory(root));
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                String body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher matcher = KEY_LITERAL.matcher(body);
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return keys;
    }

    @Test
    public void theScanReadsTheTreeItClaimsTo() {
        // The scan is only meaningful from the module root; a surefire working directory change
        // would make it read nothing and pass. Pin the assumption rather than inherit it.
        assertTrue(new File("src/main/java/com/android/commands/monkey/ape/utils/Config.java")
                .isFile());
    }
}
