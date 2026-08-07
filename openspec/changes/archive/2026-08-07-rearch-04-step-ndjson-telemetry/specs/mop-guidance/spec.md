# mop-guidance Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`, over the six `mop-guidance` requirements whose clauses
are stated in terms of the `[APE-MOP-DATA]` status line. Task 3.4 replaced that line with the
`MOP_DATA` sink record (`MopData.java:324` and `:1129`), so every one of them describes an emission
that no longer happens.

**Nothing about MOP loading, parsing, guarding or failing changes here.** The fail-fast contract
(`StopTestingException` when `ape.mopDataPath` is set and `load` returns null, INV-MOP-22), the
never-to-logcat rule (INV-MOP-21), the OOM containment and its single outer catch (INV-MOP-26), the
byte-versus-byte budget comparison (INV-MOP-29), the DIALOG re-keying and its move-not-copy policy
(INV-MOP-25), and the orphan-dialog `[APE-RV]` diagnostic are all carried forward unchanged. What
moves is the rendering the census is written in, and one field within it.

**That one field is why this delta is load-bearing rather than clerical.** "MopData — Load Status
Line and Fail-Fast" fixes the success line's field set, and the last member of that set is
`transitions=<n>`. The `event-sink` capability refuses to restore it and specifies `wtgEdges` in its
place, because the three frontier passes gate on the click-only `wtgTransitions` view while the
retired line reported `transitions.size()`, the flat list — a different structure, and one that has
been read as the gate for months. Fourteen of the decisive campaign's 40 applications report between
9 and 29 transitions with the entire frontier family disabled. Syncing this requirement unchanged
would leave the main spec mandating the field that produces the misreading, while the new capability
mandates the field that ends it: the two would be simultaneously in force and mutually incompatible.
The substitution is therefore restated at the requirement that fixed the old field set, not only at
the capability that introduces the new one.

Two requirement **names** still say "on the Load Line". They are kept as they are: a name is not a
normative clause, renaming would require RENAMED operations and would break the cross-references
that other requirements make to them by name (the DIALOG re-keying requirement cites "MopData — Load
Status Line and Fail-Fast" for its field set), and the cost of that churn is not repaid by the
accuracy gained. The bodies say what is emitted.

## MODIFIED Requirements

### Requirement: MopData — Load Status Line and Fail-Fast

`MopData.load` SHALL emit exactly one `MOP_DATA` sink record per invocation (event-sink capability), on both outcomes:

- success: `status:"loaded"` with the full load census — `package`, `windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`, `syntheticLambda`, `recovered`, `mopActivities`, `mopActsAugmented`
- failure: `status:"rejected"` with `reason` ∈ `file-missing|parse-error|incomplete|package-mismatch|too-large|oom|...` — the same reason vocabulary, unchanged

The record goes to the `.trace` stream, as the line did. It SHALL NOT be written to logcat: the rv-platform logcat parser owns that channel and foreign lines are forbidden. The sink writes to `System.out` directly rather than through `Logger` (event-sink INV-SNK-11), so the never-to-logcat property now holds for a second, structural reason as well as by discipline.

**`transitions` is deliberately not carried forward, and `wtgEdges` replaces it.** The retired line reported `transitions.size()` — the flat transition list — while the three frontier passes gate on `MopData.hasWtgData()`, which reads the click-only `wtgTransitions` view (keyed by base activity, populated only for transitions carrying a `"click"` event whose source and target windows both resolve with non-null names). These are different structures, and the difference is measured rather than theoretical: across the decisive campaign's 40 applications, 11 have zero transitions, **14 have transitions but no click event at all** — reporting between 9 and 29 while the whole frontier family is disabled — and 15 have at least one click edge. `info.metadude.android.datenspuren.schedule` reports 29 transitions with `WtgPass`/`FrontierPass`/`MopFrontierPass` all absent; `com.starry.greenstash` reports 24 with all three constructed. Restoring the old field would restore the misreading. `wtgEdges` is the number the gate actually reads, and it is emitted under the same name the stage-7 artifact uses, so the field does not change identity across the window. A separate `has_wtg_data` boolean SHALL NOT be emitted: a per-activity edge list is created only when an edge is added to it, so `hasWtgData()` is exactly `wtgEdges > 0`.

When `Config.mopDataPath` is set and `MopData.load` returns `null`, `StatefulAgent` SHALL abort the run (throw `StopTestingException`) instead of continuing as pure SATA. An operator who sets `ape.mopDataPath` has declared the run a MOP-arm run; silently executing it as `sata` mislabels the arm — the failure class that invalidated the earlier build-skew experiment round. When `Config.mopDataPath` is unset, behavior is unchanged (MOP scoring disabled, no status line required beyond the absence of a load).

#### Scenario: successful load emits counters
- **WHEN** `MopData.load` parses a complete JSON with 5 windows, 51 widgets, 12 flagged, 3 dropped for missing ids, and 35 transitions of which 7 carry a `"click"` event
- **THEN** one `MOP_DATA` record with `status:"loaded"` SHALL be emitted carrying those counters

#### Scenario: rejected load names the reason
- **WHEN** the JSON at `mopDataPath` lacks `complete=true`
- **THEN** one `MOP_DATA` record with `status:"rejected"` and `reason:"incomplete"` SHALL be emitted
- **AND** `load` SHALL return `null`

#### Scenario: fail-fast when the MOP arm cannot arm
- **WHEN** `ape.mopDataPath` is set and `MopData.load` returns `null`
- **THEN** `StatefulAgent` SHALL throw `StopTestingException` during setup
- **AND** the run SHALL NOT proceed as pure SATA

#### Scenario: unset path keeps SATA behavior
- **WHEN** `ape.mopDataPath` is not set
- **THEN** `_mopData` SHALL be `null` and exploration SHALL proceed with MOP scoring disabled, as today

---

#### Scenario: transitions present, click edges absent

- **WHEN** the JSON yields 29 transitions, none of which carries a `"click"` event
- **THEN** the `MOP_DATA` record SHALL carry `wtgEdges:0`
- **AND** it SHALL carry no `transitions` field, so the number that does not gate anything cannot be read as the number that does

---

### Requirement: MopData — Activity-Substrate Counters on the Load Line

The `status:"loaded"` `MOP_DATA` record emitted by `MopData.load` SHALL additionally report the activity-level MOP substrate: `mopActivities=<n>` — the final size of the `mopActivities` set (after Pass-2 widget derivation, DIALOG re-keying, and A′ augmentation) — and `mopActsAugmented=<m>` — the number of entries contributed by `augmentActivitiesFromSources` beyond the set as it stood before augmentation. With `Config.mopActivitySourceComponents=false`, `m` SHALL be `0`. These fields are diagnostic only and SHALL NOT alter any scoring, routing, or load outcome.

#### Scenario: flag off reports zero augmentation
- **WHEN** `Config.mopActivitySourceComponents=false` and the widget-derived set has 3 activities
- **THEN** the `status:"loaded"` record SHALL include `mopActivities:3` and `mopActsAugmented:0`

#### Scenario: flag on reports the A′ contribution
- **WHEN** `Config.mopActivitySourceComponents=true` and the A′ sources add 2 activities beyond the 3 widget-derived ones
- **THEN** the `status:"loaded"` record SHALL include `mopActivities:5` and `mopActsAugmented:2`

---

---

### Requirement: MopData — Handler-Join Diagnostics on the Load Line (FIX 3)

To make a silent widget→MOP join collapse observable, the `status:"loaded"` `MOP_DATA` record emitted by `MopData.load` SHALL additionally report the join outcome: the count of distinct widget listener handlers that did **not** join a `reachesTarget` method by exact match (`handlersUnmatched`), how many of those were D8 synthetic-lambda handlers (`syntheticLambda`), and how many of those were recovered by the FIX-2 enclosing-class fallback (`recovered`). These fields are diagnostic only and SHALL NOT alter any scoring, routing, or load outcome.

- **INV-MOP-31**: the `handlersUnmatched`/`syntheticLambda`/`recovered` fields SHALL be pure counters over the parse; their presence or values SHALL NOT change `mopActivities`, widget flags, or the loaded/rejected decision.

#### Scenario: diagnostics surface a lambda-gapped join
- **WHEN** a JSON has widget handlers of which some are unmatched D8 synthetic lambdas and FIX 2 recovers a subset
- **THEN** the `status=loaded` line SHALL include `handlersUnmatched=<n> syntheticLambda=<m> recovered=<k>` with `k ≤ m ≤ n`

---

---

### Requirement: MopData — DIALOG Window Re-Keying to Host Activity

`MopData.load` SHALL re-key DIALOG-type windows to their host activity after transitions are parsed and before the OPTIONSMENU-gateway precompute: for each window with `type=="DIALOG"`, find an incoming transition whose target is that window, take `baseActivity(source.name)` as the host, and merge the dialog's already-parsed widget entries into `widgetData[host]` using the same strongest-flag-wins collision policy (`mopRank`) as Pass 2. The dialog-class key entry SHALL be removed after a successful merge (the widgets move, they are not copied), so widget counts are not inflated. When a merged widget is MOP-flagged, `host` SHALL be added to `mopActivities` so that `activityHasMop(host)` stays consistent with the merged widget map (INV-MOP-25). DIALOG windows with no incoming transition remain keyed as-is (unreachable); their count SHALL be reported on a dedicated `[APE-RV]` diagnostic line, separate from the `MOP_DATA` record (whose field set is fixed by the "MopData — Load Status Line and Fail-Fast" requirement). The orphan count stays free text deliberately: it is a load-time diagnostic nothing joins to a step, and this change converts the census, not every line `MopData` writes.

Verified motivation: a DIALOG window's `name` is the dialog class (e.g. `android.app.AlertDialog`), which `baseActivity` leaves untouched and which never equals `newState.getActivity()` at runtime — so every widget-level MOP flag on a dialog widget was structurally unreachable for scoring (corpus estimate: ~86 flagged widgets across 5 of 169 apps). The WTG `transitions` already present in the same JSON carry the activity→dialog edges needed to recover the host, so the fix is consumer-side with no producer change.

#### Scenario: dialog widgets resolvable via host activity
- **WHEN** the JSON has window `{name: "android.app.AlertDialog", type: "DIALOG"}` with a flagged widget `btn_confirm`, and a transition whose source is `"com.example.MainActivity"` and target is that dialog window
- **THEN** `getWidget("com.example.MainActivity", "btn_confirm")` SHALL return the flagged widget

#### Scenario: collision on re-key keeps the strongest flag
- **WHEN** the host activity already holds a widget with the same `idName` and a weaker MOP rank than the dialog's widget
- **THEN** the dialog's widget SHALL win (same `mopRank` policy as Pass 2, INV-MOP-19)

#### Scenario: dialog-only host promoted to MOP activity
- **WHEN** an activity has no flagged widget of its own but a reachable DIALOG merges a flagged widget into it
- **THEN** `activityHasMop(host)` SHALL return `true` after load
- **AND** `getWidget(dialogClass, ...)` SHALL return `null` — the widgets moved to the host, and the dialog class is not a runtime activity key for the widget map

#### Scenario: re-keyed widgets moved, not copied
- **WHEN** a DIALOG window is re-keyed to its host activity
- **THEN** the dialog-class key SHALL be absent from the **widget map** after load (the widgets are moved, not duplicated)

#### Scenario: dialog-class MOP-activity entry retained for gateway detection
- **WHEN** a DIALOG window's own base activity (the dialog class) was added to `mopActivities` in Pass 2 (`:330`) and a WTG click edge targets that dialog window
- **THEN** the dialog class SHALL remain in `mopActivities` after the re-key, so the OPTIONSMENU-gateway precompute (condition 2, which tests `mopActivities.contains(targetActivity)`) still recognizes the source activity's menu as a gateway
- **AND** the move-not-copy removal SHALL apply to the widget map only, never to `mopActivities`

#### Scenario: orphan dialog left as-is
- **WHEN** a DIALOG window has no incoming transition in the JSON
- **THEN** its widgets SHALL remain under the dialog-class key (unreachable)
- **AND** the orphan count SHALL be reported on a dedicated `[APE-RV]` line, not as a `MOP_DATA` field

---

---

### Requirement: Load memory safety

`MopData.readFile` SHALL allocate the read buffer once, sized from `File.length()`, and decode in a single `new String(bytes, UTF_8)` — it SHALL NOT grow a `StringBuilder` incrementally over the file.

Before reading, `MopData.load` SHALL reject the file when its size times a parse-footprint factor (code constant, sized for the org.json DOM) exceeds a budget derived from the process's maximum heap (`Runtime.getRuntime().maxMemory()`). The comparison SHALL be computed without multiplication overflow (e.g. `fileSize > budget / factor`) **and with both operands in the same binary unit (bytes)**. The file size operand SHALL be `File.length()` in bytes and the budget operand SHALL be derived from `maxMemory()` in bytes; neither operand SHALL be converted through decimal-MB (10^6) while the other uses binary units (2^20). This unit consistency is required so that a file whose true byte size is below the heap-derived budget is NOT falsely rejected as too-large (G-2). This property already holds in the implementation (the comparison has been byte-vs-byte since `mop-data-load-oom`); the change adds only a regression-guard test pinning the byte boundary. (Note: a genuinely large JSON such as redreader's 48.3 MiB may still be rejected — that is a real heap-budget decision, `48.3 MiB × factor` vs the device `maxMemory()`, not a unit artifact.) A static max-heap budget — rather than a live free-plus-unallocated reading — makes the reject decision a pure function of file size for a given device config, so a borderline file cannot flip pass/reject across runs with GC state. When the budget is exceeded, `load` SHALL emit a `MOP_DATA` record with `status:"rejected"`, `reason:"too-large"` and the `size`/`budget` byte figures (both in bytes, the same unit as the comparison) and return null without reading the file.

If `OutOfMemoryError` is nonetheless thrown anywhere in the load body — read, sentinel check, `JSONObject` construction, typed parsing, or `MopData` construction — a single outer catch SHALL contain it: `load` releases its local references, emits a `MOP_DATA` record with `status:"rejected"` and `reason:"oom"`, and returns null. The Error SHALL NOT propagate (INV-MOP-26). The null return flows into the existing `requireMopArm` contract: with `ape.mopDataPath` set, the run fails fast via `StopTestingException` (INV-MOP-22). This is a deterministic, diagnosable fail-fast, not a graceful stop — the throw occurs at agent-construction time, so it propagates to Monkey's generic `catch (Throwable)` ("Internal error", exit 1) rather than the graceful `getNextEvent` stop path; the status record emitted first is what makes the run excludable/annotatable by analysis pipelines — and it is now emitted through the sink's failure latch (event-sink INV-SNK-12), so a sink defect cannot convert a diagnosable rejection into a silent one.

- **INV-MOP-26**: `MopData.load` SHALL NOT propagate `OutOfMemoryError` to its caller, from any phase of the load body; every failure path emits exactly one `MOP_DATA` record with `status:"rejected"` and returns null. (`IOException`/`JSONException` are already contained by the existing inner catches per INV-MOP-01; INV-MOP-26 does not widen coverage to all throwables.)
- **INV-MOP-29**: The too-large pre-read comparison SHALL express the file-size and budget operands in the same binary unit (bytes); a file whose byte size is below the heap-derived budget SHALL NOT be rejected as too-large, and the `size=`/`budget=` fields on the reject line SHALL report the same unit used in the comparison.

#### Scenario: oversized file rejected before read
- **WHEN** the JSON at `ape.mopDataPath` is 50 MB and the available heap budget is below the parse footprint for 50 MB
- **THEN** `load` SHALL return null without reading the file
- **AND** exactly one `MOP_DATA` record with `reason:"too-large"` SHALL be emitted
- **AND** the subsequent `requireMopArm` SHALL throw `StopTestingException` (INV-MOP-22)

#### Scenario: file below budget not falsely rejected (G-2)
- **WHEN** the JSON's true size is 48.3 MiB and the heap-derived budget (both operands in bytes) exceeds `48.3 MiB × factor`
- **THEN** `load` SHALL NOT emit a `status:"rejected"` record with `reason:"too-large"`
- **AND** `load` SHALL proceed to read and parse the file

#### Scenario: OOM during parse is contained
- **WHEN** the budget check passes but any phase of the load body (`JSONObject` construction, typed parsing, or `MopData` construction) exhausts the heap
- **THEN** the single outer catch SHALL contain the `OutOfMemoryError` and `load` SHALL return null
- **AND** emit a `MOP_DATA` record with `reason:"oom"`

#### Scenario: normal file unaffected
- **WHEN** the JSON is 2 MB and the budget check passes
- **THEN** `load` SHALL parse and return `MopData` exactly as before, emitting `status:"loaded"`

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

## Invariants

- **INV-MOP-01**: `MopData.load()` SHALL never throw a checked or unchecked exception to the caller. All I/O and parse errors SHALL be caught internally and result in a `null` return with a WARNING log.
- **INV-MOP-02**: MOP scoring SHALL only be applied to actions where `action.requireTarget() == true` AND `action.isValid() == true`. Non-target actions (MODEL_BACK, MODEL_MENU, FUZZ, etc.) SHALL NOT receive MOP boosts.
- **INV-MOP-03**: MOP scoring SHALL be additive (`setPriority(getPriority() + boost)`), never replacing the existing priority. The base SATA priority assignment always runs first.
- **INV-MOP-04**: When `Config.mopDataPath` is `null`, the MOP scoring pass SHALL be skipped entirely. The `sata` variant's behaviour SHALL be identical with and without `MopData.java` present in the JAR.
- **INV-MOP-05**: The WTG scoring pass SHALL execute AFTER the existing MOP scoring pass in `adjustActionsByGUITree()`. Pass order: base priority -> unvisited bonus -> state transition bonus -> MOP boost -> WTG boost -> coverage boost.
- **INV-MOP-06**: `MopScorer.scoreWtg()` SHALL return 0 when `MopData` is null, when WTG data is absent, when the widget has no matching WTG transition, or when `Config.mopWeightWtg` is 0.
- **INV-MOP-08**: `eventType` comparison in the scorer SHALL be normalization-invariant: a producer `snake_case` token and the consumer `camelCase` token for the same event SHALL compare equal.
- **INV-MOP-21**: Every `MopData.load` invocation SHALL emit exactly one `MOP_DATA` sink record, never to logcat.
- **INV-MOP-22**: A run with `ape.mopDataPath` set SHALL either have non-null `_mopData` or abort; it SHALL never run as pure SATA.
- **INV-MOP-23**: Static-widget resolution for typed input SHALL use the same containment policy as MOP boost resolution; the two paths SHALL NOT diverge in matching rules.
- **INV-MOP-24**: above the `+1` activity-substrate floor, `stateMopDensity` SHALL count only MOP-flagged resolved widgets; it SHALL never reduce to a total action count, and the floor SHALL be exactly `1` (never proportional to action count or widget count).
- **INV-MOP-32**: `mopActivities`/`mopActsAugmented` SHALL be pure counters over the load; their presence or values SHALL NOT change widget flags, the `mopActivities` set itself, or the loaded/rejected decision.
- **INV-MOP-33**: the tiebreak log SHALL be emitted only from the path-selection site and only on the decisive branch; it SHALL NOT alter which path is selected.
- **INV-MOP-19**: On a `shortId` collision within a base activity, the widget map SHALL retain the strongest MOP flag (direct > transitive > unflagged); an unflagged widget SHALL never overwrite a flagged one; the outcome is order-independent.
- **INV-MOP-20**: Widgets with an empty `idName` SHALL NOT be stored; the count of MOP-flagged widgets dropped for lacking a resource id SHALL be logged once per load.
- **INV-MOP-25**: After load, every DIALOG window reachable via a WTG transition SHALL have its widgets queryable under the host activity's key (subject to the standard collision policy) and removed from the dialog-class **widget-map** key; when a merged widget is flagged, `activityHasMop(host)` SHALL reflect it. The move-not-copy removal applies to the widget map only — the dialog class's Pass-2 `mopActivities` entry SHALL be retained so the OPTIONSMENU-gateway precompute (condition 2) still fires for activities that navigate into a MOP-bearing dialog. Orphan (unreachable) DIALOG windows are counted on a dedicated `[APE-RV]` diagnostic line, never as a `MOP_DATA` field.

---

## Data Contracts

### Input
- `Config.mopDataPath: String` — device path to static analysis JSON (null = MOP disabled)
- Static analysis JSON file at `Config.mopDataPath` — produced by rv-android static analysis component; format: `{"windows": [...], "reachability": [...]}`

### Output
- `ModelAction.priority` boosted for MOP-reachable widget actions (additive, in-memory only; not persisted)
- WARNING log entry when JSON is missing or malformed

### Side-Effects
- None beyond the in-memory priority adjustments on `ModelAction` objects
- **[Trace]**: `MOP_DATA` with `status:"rejected"`, `reason:"too-large"`, `size`, `budget` — file exceeds the parse budget (pre-read).
- **[Trace]**: `MOP_DATA` with `status:"rejected"`, `reason:"oom"` — read/parse ran out of memory despite the guard (backstop).

### Error
- No exceptions propagate from `MopData` or `MopScorer` to callers
- Never propagates `OutOfMemoryError` or `IOException` to the caller; all failures return null after emitting exactly one status line (INV-MOP-21).
