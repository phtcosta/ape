# Delta Specification: mop-guidance (rearch-02-runspec)

Only the kill-switch-registry registration clause changes: the `apePureMode` registry no longer exists (see the `scoring-pipeline` delta of this change), so `mopActivitySourceComponents` is re-grounded on the run-spec `Feature` model. Loader semantics, the three-source union, and INV-MOP-27 are unchanged.

## MODIFIED Requirements

### Requirement: MopData — Activity-Level MOP Source from Components (A′)

`MopData.load` SHALL support an alternative source for the `mopActivities` set (which backs `activityHasMop(activity)`), gated by `Config.mopActivitySourceComponents` (default `false`):

- When `Config.mopActivitySourceComponents == false` (default), `mopActivities` SHALL be populated exactly as today — from the base activities of MOP-flagged widgets (plus the existing Pass-2/DIALOG-rekey contributions). Behaviour SHALL be byte-identical to the pre-change loader.
- When `Config.mopActivitySourceComponents == true`, `mopActivities` SHALL be the **union** of THREE sources: (1) the widget-derived source (today); (2) the base class name of every `components.activities[]` entry whose JSON `reachesTarget` field is `true`; and (3) the class name of every `reachability[]` entry whose `componentType == "activity"` that has **at least one method with `reachesTarget == true`**. The union SHALL be additive: an activity present via any source SHALL remain present regardless of the others; no widget-derived entry is ever removed.

  **Source (3) is required, not redundant with (2)** (evidence: `test-apks/cryptoapp.apk.json`, device-verified 2026-07-08). `components.activities[].reachesTarget` is computed from the activity's entry points through the producer's call graph, which does NOT traverse D8-desugared lambda/callback edges; so an activity whose UI genuinely triggers a MOP API through a lambda handler is a **false negative** at the component level. In cryptoapp all 4 activities report `components.activities[].reachesTarget=false`, yet `reachability[]` correctly marks `CryptographyActivity` with 13 `reachesTarget=true` methods (`executeOperation`→`Cipher`…), `CipherActivity` 2, `MessageDigestActivity` 1. Source (3) — "the activity's class contains MOP-reaching code" — is the only source that is immune to the lambda call-graph gap and is therefore the robust substrate for `activityHasMop`. Sources (1) and (2) are kept in the union for recall on apps where they do fire.

The read of the JSON `reachesTarget` fields (on `components.activities[]` and on `reachability[].methods[]`) is confined to the JSON-parsing boundary (`Target` vocabulary on the JSON side); the values populate `mopActivities` (`MOP` vocabulary on the Java side), preserving the `MopData` javadoc naming boundary (gh13 D7). The scorer arithmetic SHALL NOT change — this requirement widens only the extent of the `activityHasMop` predicate.

`Config.mopActivitySourceComponents` SHALL be declared in `Config.java` and loaded via `ape.mopActivitySourceComponents`, default `false`. In the run-spec `Feature` model, the key activates the `MOP_ACTIVITY_SOURCE` feature, which depends on `MOP`: an explicit `true` on a plan without `ape.mopDataPath` aborts resolution as a missing dependency, and with the feature absent the widget-only source is the only one that exists (INV-RUN-05 of `run-spec` replaces the former kill-switch registration).

- **INV-MOP-27**: `activityHasMop(activity)` SHALL return `true` for an activity contributed by a component-level or reachability-level source **only** when `Config.mopActivitySourceComponents == true` AND EITHER that activity's `ComponentInfo.reachesTarget == true` OR its `reachability[]` class (`componentType=="activity"`) has ≥1 method with `reachesTarget == true`. With the flag `false`, both non-widget sources SHALL contribute nothing and the `mopActivities` set SHALL equal the pre-change widget-derived set exactly.

#### Scenario: component-level activity added under the flag
- **WHEN** `Config.mopActivitySourceComponents=true` AND a `components.activities[]` entry `com.x.CryptoActivity` has `reachesTarget=true` and carries no MOP-flagged widget
- **THEN** after load `activityHasMop("com.x.CryptoActivity")` SHALL return `true`

#### Scenario: flag off preserves widget-only source
- **WHEN** `Config.mopActivitySourceComponents=false` AND the same `com.x.CryptoActivity` has `reachesTarget=true` but no MOP-flagged widget
- **THEN** `activityHasMop("com.x.CryptoActivity")` SHALL return `false` (component-level source contributes nothing)

#### Scenario: union preserves widget-derived entries
- **WHEN** `Config.mopActivitySourceComponents=true` AND activity `com.x.A` is MOP via a flagged widget but its `ComponentInfo.reachesTarget=false`
- **THEN** `activityHasMop("com.x.A")` SHALL remain `true` (widget-derived entry not removed by the component source)

#### Scenario: non-reaching component not added
- **WHEN** `Config.mopActivitySourceComponents=true` AND `com.x.Plain` has `reachesTarget=false`, no flagged widget, and no `reachability[]` method reaching a target
- **THEN** `activityHasMop("com.x.Plain")` SHALL return `false`

#### Scenario: reachability-method source flags a lambda-gapped activity (source 3)
- **WHEN** `Config.mopActivitySourceComponents=true` AND activity `com.x.CryptoActivity` has `components.activities[].reachesTarget=false` and no MOP-flagged widget, BUT its `reachability[]` class (`componentType="activity"`) has ≥1 method with `reachesTarget=true`
- **THEN** `activityHasMop("com.x.CryptoActivity")` SHALL return `true` (source 3 — immune to the component-level lambda call-graph gap)

#### Scenario: explicit activation without MOP data aborts
- **WHEN** `ape.properties` sets `ape.mopActivitySourceComponents=true` and no `ape.mopDataPath`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `MOP_ACTIVITY_SOURCE` and `MOP`
