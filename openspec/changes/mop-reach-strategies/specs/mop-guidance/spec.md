# mop-guidance — delta: mop-reach-strategies

## Purpose

Widen the activity-level MOP predicate to its real substrate (A′), recover widget MOP flags dropped by the exact-match handler join on D8-desugared lambdas (FIX 2), surface the join outcome on the load line (FIX 3), expose the widgetless-substrate classifier (F′ seam), and fix the too-large load reject unit bug (G-2). A′ is the axis separating the `sata_mop_widget` and `sata_mop_activity` experiment arms: today `activityHasMop` is true for only 17.8% of apps (widget-derived only). A′ widens it from the already-parsed component/reachability data — and, per the cryptoapp evidence (2026-07-08), the robust source is the `reachability[]` method-level flags, not `components.activities[].reachesTarget` (which shares the producer's lambda call-graph gap and false-negatives lambda-triggered activities). The widened predicate feeds every existing consumer (`scoreWtg`, the OPTIONSMENU gateway, `stateMopDensity`) for free.

## ADDED Requirements

### Requirement: MopData — Activity-Level MOP Source from Components (A′)

`MopData.load` SHALL support an alternative source for the `mopActivities` set (which backs `activityHasMop(activity)`), gated by `Config.mopActivitySourceComponents` (default `false`):

- When `Config.mopActivitySourceComponents == false` (default), `mopActivities` SHALL be populated exactly as today — from the base activities of MOP-flagged widgets (plus the existing Pass-2/DIALOG-rekey contributions). Behaviour SHALL be byte-identical to the pre-change loader.
- When `Config.mopActivitySourceComponents == true`, `mopActivities` SHALL be the **union** of THREE sources: (1) the widget-derived source (today); (2) the base class name of every `components.activities[]` entry whose JSON `reachesTarget` field is `true`; and (3) the class name of every `reachability[]` entry whose `componentType == "activity"` that has **at least one method with `reachesTarget == true`**. The union SHALL be additive: an activity present via any source SHALL remain present regardless of the others; no widget-derived entry is ever removed.

  **Source (3) is required, not redundant with (2)** (evidence: `test-apks/cryptoapp.apk.json`, device-verified 2026-07-08). `components.activities[].reachesTarget` is computed from the activity's entry points through the producer's call graph, which does NOT traverse D8-desugared lambda/callback edges; so an activity whose UI genuinely triggers a MOP API through a lambda handler is a **false negative** at the component level. In cryptoapp all 4 activities report `components.activities[].reachesTarget=false`, yet `reachability[]` correctly marks `CryptographyActivity` with 13 `reachesTarget=true` methods (`executeOperation`→`Cipher`…), `CipherActivity` 2, `MessageDigestActivity` 1. Source (3) — "the activity's class contains MOP-reaching code" — is the only source that is immune to the lambda call-graph gap and is therefore the robust substrate for `activityHasMop`. Sources (1) and (2) are kept in the union for recall on apps where they do fire.

The read of the JSON `reachesTarget` fields (on `components.activities[]` and on `reachability[].methods[]`) is confined to the JSON-parsing boundary (`Target` vocabulary on the JSON side); the values populate `mopActivities` (`MOP` vocabulary on the Java side), preserving the `MopData` javadoc naming boundary (gh13 D7). The scorer arithmetic SHALL NOT change — this requirement widens only the extent of the `activityHasMop` predicate.

`Config.mopActivitySourceComponents` SHALL be declared in `Config.java` and loaded via `ape.mopActivitySourceComponents`, default `false`, and SHALL be registered in the `apePureMode` RV-flag registry (INV-ARCH-06 of `scoring-pipeline`), forced to `false` when `apePureMode=true`.

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

---

### Requirement: MopData — Widget MOP Flag Recovery for Desugared-Lambda Handlers (FIX 2)

When the exact handler join in `MopData.deriveWidgetMopFlags` misses for a listener whose `handler` class matches the D8 synthetic-lambda pattern `<ENCLOSING$$ExternalSyntheticLambda<digits>: ...>`, `MopData` SHALL fall back to the **enclosing class** `ENCLOSING`: if `ENCLOSING` has any `reachability[]` method named `lambda$…` (a javac-desugared lambda body) with `reachesTarget==true`, the listener SHALL be treated as `transitiveMop` (and `directMop` when that lambda method's `directlyReachesTarget==true`). The exact-match path SHALL be unchanged; recovery only adds flags the exact match would have missed. The recovery SHALL be a pure consumer-side read of data already present in the same JSON (no producer/GATOR re-run) and SHALL be active only when MOP data is loaded (`mopDataPath` set), so the standalone `aperv` default and `apePureMode` are unaffected.

Context: today the join is an exact signature match (`bySignature.get(l.handler)`), which silently drops any handler whose signature does not string-match a reachability method. The dominant miss is the **D8-desugared lambda listener**: a widget's runtime `onClick` handler is the synthetic wrapper class `<X$$ExternalSyntheticLambdaN: void onClick(...)>`, whose reachability entry carries `reachesTarget=false` because the producer call graph does not traverse the wrapper→`lambda$…` delegation, while the underlying `<X: void lambda$…$K$X(...)>` method (which the wrapper calls) IS marked `reachesTarget=true`. On `cryptoapp.apk.json` this drops the Execute button — the single widget that triggers all crypto — so only 2 of 7 handlers join (`flagged=2`).

- **INV-MOP-30**: a widget whose only listener handler is a D8 synthetic-lambda wrapper (`X$$ExternalSyntheticLambdaN`) SHALL be flagged `transitiveMop` iff its enclosing class `X` has a `lambda$…` reachability method with `reachesTarget==true`; recovery SHALL NOT flag a widget whose enclosing class has no reaching lambda method (no blanket enclosing-class over-flag beyond the lambda-method evidence).

#### Scenario: desugared-lambda handler recovered
- **WHEN** a widget's only listener handler is `<com.x.CryptoActivity$$ExternalSyntheticLambda0: void onClick(android.view.View)>` (reachability `reachesTarget=false`) AND `com.x.CryptoActivity` has a reachability method `<com.x.CryptoActivity: void lambda$setup$0$CryptoActivity(android.view.View)>` with `reachesTarget=true`
- **THEN** after load the widget SHALL be `transitiveMop==true` and its base activity SHALL be in `mopActivities`

#### Scenario: exact match still wins unchanged
- **WHEN** a widget handler signature exactly matches a `reachesTarget=true` reachability method
- **THEN** the widget SHALL be flagged exactly as before (recovery path not consulted)

#### Scenario: no reaching lambda → not recovered
- **WHEN** a widget's handler is `<com.x.Plain$$ExternalSyntheticLambda0: void onClick(...)>` AND `com.x.Plain` has no `lambda$…` method with `reachesTarget=true`
- **THEN** the widget SHALL NOT be flagged by the recovery path

---

### Requirement: MopData — Handler-Join Diagnostics on the Load Line (FIX 3)

To make a silent widget→MOP join collapse observable, the `[APE-MOP-DATA] status=loaded …` line emitted by `MopData.load` SHALL additionally report the join outcome: the count of distinct widget listener handlers that did **not** join a `reachesTarget` method by exact match (`handlersUnmatched`), how many of those were D8 synthetic-lambda handlers (`syntheticLambda`), and how many of those were recovered by the FIX-2 enclosing-class fallback (`recovered`). These fields are diagnostic only and SHALL NOT alter any scoring, routing, or load outcome.

- **INV-MOP-31**: the `handlersUnmatched`/`syntheticLambda`/`recovered` fields SHALL be pure counters over the parse; their presence or values SHALL NOT change `mopActivities`, widget flags, or the loaded/rejected decision.

#### Scenario: diagnostics surface a lambda-gapped join
- **WHEN** a JSON has widget handlers of which some are unmatched D8 synthetic lambdas and FIX 2 recovers a subset
- **THEN** the `status=loaded` line SHALL include `handlersUnmatched=<n> syntheticLambda=<m> recovered=<k>` with `k ≤ m ≤ n`

---

### Requirement: MopData — Widgetless-Substrate Classifier (F′ seam)

`MopData.isWidgetlessSubstrate()` SHALL return `true` when the sum of `getWindows().get(i).getWidgets().size()` over all parsed windows is `0`, and `false` otherwise. This identifies apps (Compose-pure, GL, games — 65/219 in the corpus) for which no widget/WTG/frontier steering substrate exists. The predicate is a pure read over already-parsed data; it has **no consumer** in this change (round-2 adaptive LLM routing will read it). `LlmRouter` and all routing behaviour SHALL be unchanged.

- **INV-MOP-28**: `isWidgetlessSubstrate()` SHALL be a pure function of the parsed `windows[].widgets` counts and SHALL NOT alter any scoring, routing, or load outcome.

#### Scenario: zero-widget substrate detected
- **WHEN** the JSON has windows but every window's `widgets` list is empty
- **THEN** `isWidgetlessSubstrate()` SHALL return `true`

#### Scenario: any widget present
- **WHEN** at least one window carries one or more widgets
- **THEN** `isWidgetlessSubstrate()` SHALL return `false`

#### Scenario: complete-but-empty JSON
- **WHEN** the JSON is `complete:true` with an empty `windows[]`
- **THEN** `isWidgetlessSubstrate()` SHALL return `true` (empty sum is 0) and no exception SHALL be thrown

## MODIFIED Requirements

### Requirement: Load memory safety

`MopData.readFile` SHALL allocate the read buffer once, sized from `File.length()`, and decode in a single `new String(bytes, UTF_8)` — it SHALL NOT grow a `StringBuilder` incrementally over the file.

Before reading, `MopData.load` SHALL reject the file when its size times a parse-footprint factor (code constant, sized for the org.json DOM) exceeds a budget derived from the process's maximum heap (`Runtime.getRuntime().maxMemory()`). The comparison SHALL be computed without multiplication overflow (e.g. `fileSize > budget / factor`) **and with both operands in the same binary unit (bytes)**. The file size operand SHALL be `File.length()` in bytes and the budget operand SHALL be derived from `maxMemory()` in bytes; neither operand SHALL be converted through decimal-MB (10^6) while the other uses binary units (2^20). This unit consistency is required so that a file whose true byte size is below the heap-derived budget — e.g. a 48.3 MiB JSON reported colloquially as "50.6 MB" (decimal) — is NOT falsely rejected as too-large (G-2; recovers 3/657 previously-aborted runs, e.g. redreader). A static max-heap budget — rather than a live free-plus-unallocated reading — makes the reject decision a pure function of file size for a given device config, so a borderline file cannot flip pass/reject across runs with GC state. When the budget is exceeded, `load` SHALL emit `[APE-MOP-DATA] status=rejected reason=too-large size=<bytes> budget=<bytes>` (both fields in bytes, the same unit as the comparison) and return null without reading the file.

If `OutOfMemoryError` is nonetheless thrown anywhere in the load body — read, sentinel check, `JSONObject` construction, typed parsing, or `MopData` construction — a single outer catch SHALL contain it: `load` releases its local references, emits `[APE-MOP-DATA] status=rejected reason=oom`, and returns null. The Error SHALL NOT propagate (INV-MOP-26). The null return flows into the existing `requireMopArm` contract: with `ape.mopDataPath` set, the run fails fast via `StopTestingException` (INV-MOP-22). This is a deterministic, diagnosable fail-fast, not a graceful stop — the throw occurs at agent-construction time, so it propagates to Monkey's generic `catch (Throwable)` ("Internal error", exit 1) rather than the graceful `getNextEvent` stop path; the status line emitted first is what makes the run excludable/annotatable by analysis pipelines.

- **INV-MOP-26**: `MopData.load` SHALL NOT propagate `OutOfMemoryError` to its caller, from any phase of the load body; every failure path emits exactly one `[APE-MOP-DATA] status=rejected` line and returns null. (`IOException`/`JSONException` are already contained by the existing inner catches per INV-MOP-01; INV-MOP-26 does not widen coverage to all throwables.)
- **INV-MOP-29**: The too-large pre-read comparison SHALL express the file-size and budget operands in the same binary unit (bytes); a file whose byte size is below the heap-derived budget SHALL NOT be rejected as too-large, and the `size=`/`budget=` fields on the reject line SHALL report the same unit used in the comparison.

#### Scenario: oversized file rejected before read
- **WHEN** the JSON at `ape.mopDataPath` is 50 MB and the available heap budget is below the parse footprint for 50 MB
- **THEN** `load` SHALL return null without reading the file
- **AND** exactly one `[APE-MOP-DATA] status=rejected reason=too-large` line SHALL be emitted
- **AND** the subsequent `requireMopArm` SHALL throw `StopTestingException` (INV-MOP-22)

#### Scenario: file below budget not falsely rejected (G-2)
- **WHEN** the JSON's true size is 48.3 MiB and the heap-derived budget (both operands in bytes) exceeds `48.3 MiB × factor`
- **THEN** `load` SHALL NOT emit `status=rejected reason=too-large`
- **AND** `load` SHALL proceed to read and parse the file

#### Scenario: OOM during parse is contained
- **WHEN** the budget check passes but any phase of the load body (`JSONObject` construction, typed parsing, or `MopData` construction) exhausts the heap
- **THEN** the single outer catch SHALL contain the `OutOfMemoryError` and `load` SHALL return null
- **AND** emit `[APE-MOP-DATA] status=rejected reason=oom`

#### Scenario: normal file unaffected
- **WHEN** the JSON is 2 MB and the budget check passes
- **THEN** `load` SHALL parse and return `MopData` exactly as before, emitting `status=loaded`
