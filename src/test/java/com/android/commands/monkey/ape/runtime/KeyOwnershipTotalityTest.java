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
                    // Two kinds of key may lack a neutral: a PRESENT activation path, whose
                    // presence is what turns its feature on, and a key the feature requires once
                    // mounted (ape.llmUrl for LLM), which resolution enforces.
                    boolean activationPath = feature.activationKey().equals(key)
                            && feature.activationRule() == Feature.Rule.PRESENT;
                    assertTrue(key + " has no neutral value but is neither an activation path nor "
                            + "a required key", activationPath
                            || feature.requiredKeys().contains(key));
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

    /**
     * The LLM feature's neutral value of the status-bar band is its default, 0.035. No test compares
     * {@code Feature.own} with the table in general, so this pins the band in the table and in the
     * feature; {@code ConfigTest} pins the {@code Config} field.
     */
    @Test
    public void theTopBandsNeutralValueIsItsDefault() {
        String key = "ape.llmBoundaryTopPct";
        assertEquals(0.035, (Double) KeyOwnership.typeOf(key).parse(key, KeyOwnership.defaultOf(key)), 1e-9);
        assertEquals(0.035, (Double) KeyOwnership.typeOf(key).parse(key, Feature.LLM.neutralValue(key)), 1e-9);
    }

    /**
     * The six MOP scoring keys of llm-coordinate-single-base Part B (design D18–D20), pinned
     * against the run-spec delta's table: owner, declared type and jar default. The neutral values
     * are pinned beside the feature in {@code FeatureDerivationTest}. None of the six has a
     * {@code Config} field, so the default drift guard above cannot see them; this is their guard.
     */
    @Test
    public void theSixMopScoringKeysAreOwnedByMopWithTheDeltasTypesAndDefaults() {
        String[][] table = {
                {"ape.mopScoring", "flag"},
                {"ape.mopWeightD1", "500"},
                {"ape.mopWeightD2", "400"},
                {"ape.mopWeightD3", "300"},
                {"ape.mopRetireAfter", "3"},
                {"ape.mopLauncherDmax", "6"},
        };
        for (String[] row : table) {
            String key = row[0];
            assertTrue(key + " must be owned", KeyOwnership.isKnown(key));
            assertEquals(key + " belongs to MOP", Feature.MOP, KeyOwnership.spec(key).feature());
            assertEquals(key + "'s jar default", row[1], KeyOwnership.defaultOf(key));
            if (!"ape.mopScoring".equals(key)) {
                assertEquals(key + " is an int", ValueType.INT, KeyOwnership.typeOf(key));
            }
        }
        // The mode key is a string whose value set is closed: both members parse, as themselves.
        ValueType mode = KeyOwnership.typeOf("ape.mopScoring");
        assertEquals("flag", mode.parse("ape.mopScoring", "flag"));
        assertEquals("distance", mode.parse("ape.mopScoring", "distance"));
    }

    @Test
    public void theResolverOwnsExactlyPresetRunIdCorpusBasisAndVariant() {
        assertEquals(new LinkedHashSet<>(java.util.Arrays.asList(
                        KeyOwnership.KEY_PRESET, KeyOwnership.KEY_RUN_ID,
                        KeyOwnership.KEY_CORPUS_BASIS, KeyOwnership.KEY_VARIANT)),
                KeyOwnership.keysOf(KeyOwnership.OwnerKind.RESOLVER));
    }

    /**
     * The keys isolate-guidance-groups adds (design § Key contract v3.2), pinned against the
     * contract: owner, declared type and jar default. None has a {@code Config} field, so the
     * drift guard above cannot see them.
     */
    @Test
    public void theGroupContractKeysHaveTheirOwnersTypesAndDefaults() {
        Object[][] table = {
                {"ape.addons", Feature.ADDONS, ValueType.BOOLEAN, "false"},
                {"ape.static", Feature.STATIC, ValueType.BOOLEAN, "false"},
                {"ape.mop", Feature.MOP, ValueType.BOOLEAN, "false"},
                {"ape.llm", Feature.LLM, ValueType.BOOLEAN, "false"},
                {"ape.staticDataPath", Feature.STATIC_DATA, ValueType.STRING, null},
                {"ape.mopStrictPackageMatch", Feature.STATIC_DATA, ValueType.BOOLEAN, "false"},
                {"ape.mopCoinPercentage", Feature.MOP_COIN, ValueType.DOUBLE, "1.0"},
                {"ape.llmGateMinActions", Feature.LLM, ValueType.INT, "3"},
                {"ape.llmBreakerFailures", Feature.LLM, ValueType.INT, "3"},
                {"ape.llmBreakerOpenMs", Feature.LLM, ValueType.LONG, "60000"},
                {"ape.backMenuPickCap", Feature.BACK_MENU_CAP, ValueType.INT, "3"},
                {"ape.activityStableRestartThreshold", Feature.ACTIVITY_STABLE_RESTART,
                        ValueType.INT, "200"},
                {"ape.frontierBoostWeight", Feature.NAV_FRONTIER, ValueType.INT, "200"},
                {"ape.fuzzInputTyped", Feature.TYPED_FUZZ, ValueType.BOOLEAN, "true"},
                {"ape.componentPercentage", Feature.COMPONENT_TRIGGER, ValueType.DOUBLE, "0.0"},
                {"ape.activityTriggerEnabled", Feature.MOP_LAUNCHER, ValueType.BOOLEAN, "true"},
                {"ape.llmPercentage", Feature.LLM_COIN, ValueType.DOUBLE, "0.02"},
        };
        for (Object[] row : table) {
            String key = (String) row[0];
            assertTrue(key + " must be owned", KeyOwnership.isKnown(key));
            assertEquals(key + "'s owner", row[1], KeyOwnership.spec(key).feature());
            assertEquals(key + "'s type", row[2], KeyOwnership.typeOf(key));
            assertEquals(key + "'s jar default", row[3], KeyOwnership.defaultOf(key));
        }
        assertEquals(KeyOwnership.OwnerKind.RESOLVER,
                KeyOwnership.spec(KeyOwnership.KEY_VARIANT).ownerKind());
    }

    @Test
    public void retiredKeysCarryAReasonNamingWhatReplacedThem() {
        Set<String> expected = new LinkedHashSet<>(java.util.Arrays.asList(
                "ape.apePureMode", "ape.modelFile", "ape.saveObjModel", "ape.saveDotGraph",
                "ape.saveVisGraph", "ape.saveStates", "ape.enableXPathAction",
                "ape.mopWeightActivity", "ape.agentType", "ape.replayLog",
                "ape.llmBoundaryBottomPct", "ape.mopDataPath", "ape.leastVisitedPriorityTiebreak",
                "ape.mopTargetPickCap"));
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
