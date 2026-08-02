# Design: rearch-07-compact-static-artifact

## Context

Stage 7 of 7 of the re-architecture selected in `docs/analise_fable-selecao.md` (rev. 3, Sec. 6.6 last row, Sec. 10 item 7). The jar currently parses the **full** static-analysis JSON on-device (`src/main/java/com/android/commands/monkey/ape/utils/MopData.java`, 1212 LOC): a whole-file `org.json` DOM parse of `reachability[]` + `windows[]` + `transitions[]` + `components{}`, guarded by a pre-read footprint budget (`PARSE_FOOTPRINT_FACTOR = 6`, `MopData.java:160-167`) and a repo-unique `catch (OutOfMemoryError)` (`MopData.java:328`, verified V19). Corpus measurement over the 134 real static-analysis JSONs of the campaign corpus confirms Ling's T7: `reachability[]` (call-graph) is **57.7 % of aggregate bytes** (up to 77 % per file); `windows[]` is 5.0 % and `transitions[]` 10.1 % (46 % in the worst case, `eu.vranckaert.worktime`, dominated by exact-duplicate edges — the same duplicates `tool.py`'s current compaction already strips). The explorer consumes none of these sections directly at runtime — they are parse-time inputs to a small set of derived projections (widget MOP flags, MOP-activity sets, WTG click view, OPTIONSMENU gateways, component trigger surface).

On the Python side, `modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py` locates the full JSON (`_find_static_analysis_file`, `:889`), minifies/dedups/enriches it (`_compact_static_analysis_json`, `:917` — a *lossless* shrink that exists purely to clear the jar's footprint guard), and pushes it to `/data/local/tmp/static_analysis.json`. When the JSON is absent it **warns and continues**, so a MOP arm silently runs without MOP guidance (`tool.py:1499-1503`, verified V21).

This change inverts the boundary: a **host-side generator** derives a compact, explorer-shaped artifact from the full JSON at preparation time; the jar's `MopData` is rewritten to consume **only** that format; the full-JSON parser — including the OOM catch and the `too-large` degradation class — is deleted; and absence of the artifact under a MOP plan becomes fail-fast on both sides (composing with rearch-02's plan validation and the existing INV-MOP-22 abort). The `Target`→`MOP` vocabulary boundary (gh13 D7) moves into the generator: the compact wire format speaks `MOP`; `*Target` keys survive only where the generator reads the full JSON.

Constraints: hard constraint 2 of `docs/20260801_prompt_rearquitetura_aperv.md` §6 (file-and-command-line boundary; blast radius stated in both repos; drift made loud), 2b (Docker rebuilds the jar from source — no committed binary as source of truth), 4b/R9 (frozen metric definitions untouched: *MOP coverage* over `directly_reaches_mop`, *unique misuse* key `(app, class, method, specification)`, app-vs-library by the `Mneut` prefix test), R5 (fail-fast), and rules R1–R4. Depends on: `rearch-02-runspec` (fail-fast plan validation frame), `rearch-04-step-ndjson-telemetry` (`MOP_DATA` trace record), `rearch-05-thin-python-arms` (same `tool.py` push path — this change lands after it).

## Consumption inventory (the ground truth for the projection)

Every production read of `MopData` in `src/main`, established by exhaustive caller audit (this table is the definition of "explorer-shaped"; anything not on it is deleted from the device artifact):

| MopData surface | Production consumers | Fields actually read |
|---|---|---|
| `getWidget(activity, shortId)` | `MopScorer.score`/`stateMopDensity`, `scoring/MopWidgetPass`, `ApeAgent.generateInputText` (typed input), `ApePromptBuilder` (MOP marker + T1.1 metadata) | `isDirectMop(evt)`/`isTransitiveMop(evt)` (per-normalized-eventType maps + aggregate fallback); `inputType`, `hint`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries` |
| `activityHasMop(activity)` | `MopScorer.scoreWtg`/`stateMopDensity`, `MopFrontierPass`, `SataAgent:347` (navigation tiebreak predicate), `StatefulAgent:245` | set membership |
| `getMopActivities()` | `SataAgent.selectTriggerCandidate` (launcher census) | the set |
| `activityHasMopOptionsMenu(activity)` | `MopScorer.scoreOpenMenu` (via `MenuGatewayPass`) | set membership |
| `hasWtgData()` / `getWtgTransitions(activity)` | `WtgPass`, `FrontierPass`, `MopFrontierPass`, `MopScorer.scoreWtg`, `StatefulAgent.frontierBoost` | `WtgTransition.widgetName`, `WtgTransition.targetActivity` — **`widgetClass` has zero production readers** |
| `getReceivers()` / `getServices()` | `StatefulAgent.buildTriggerTuples`, `dispatchTrigger`, `triggerLogLine` | `className`, `componentType` (log), `reachesTarget`, `intentFilters[].actions`, `intentFilters[].categories`, `targetMethods` (**emptiness test only**), `permission` (log via `hasPermissionGate`) |
| `getProviders()` | `buildProviderTuples`, `buildContentCommand` | `className`, `reachesTarget`, `authorities` |
| `getActivities()` | `SataAgent.selectTriggerCandidate`; `MopData.augmentActivitiesFromSources` (A′ source 2, parse-time) | `className`, `isMain`, `permission`, `reachesTarget` |
| `hasComponents()` | `SataAgent:547` gate | non-emptiness |
| `getPackageName()` / `getMainActivity()` | trigger `ComponentName` (INV-CT-04), `selectTriggerCandidate`, T1.7 strict-match | scalars |
| `getReachability()`, `getWindows()`, `getWindow(id)`, `getTransitions()`, `isWidgetlessSubstrate()`, `getDroppedFlaggedNoId()` | **tests only** (`MopDataTest`) | — parse-time inputs to the projections above; never read at exploration time |

Parse-time-only consumption (moves to the generator): `reachability[]` → widget flag cross-reference (`bySignature`), D8 synthetic-lambda recovery (FIX 2, INV-MOP-30), A′ source 3; `windows[]` → widget map, collision policy (INV-MOP-19), empty-id drop (INV-MOP-20), OPTIONSMENU precompute (INV-MOP-13), dialog re-keying (INV-MOP-25); `transitions[]` → WTG click view keyed by base activity (INV-WTG-04), dialog host resolution; `components.activities[].reachesTarget` → A′ source 2 (INV-MOP-27). Also production-unused and dropped from the wire: `IntentFilter.data` (D15 `DataSpec` — parsed-and-exposed only), `ProviderInfo.readPermission`/`writePermission`, `Widget.id`/`text`/`type`, raw `listeners[]`, `WtgTransition.widgetClass`, `targetMethods` signature list (compacted to a boolean).

## Architecture

```text
rv-android (host)                                       ape-rv.jar (device)
─────────────────                                       ───────────────────
rv-static-analysis (unchanged)
  └─► <results_dir>/<apk>.json  (full JSON — host-side
        │                        source of truth; frozen
        │                        metrics keep reading it)
        ▼
aperv-tool: derive_mop_artifact.py
  derive(full_json) → compact dict                       MopData.load(path, pkg, mainAct)
  serialize_canonical(dict) → bytes  ──cache──►            │  parses ONLY formatVersion=1
  <results_dir>/<apk>.mop.json                             │  compact artifact; rejects
        │                                                  │  anything else (version-mismatch)
        ▼                                                  ▼
tool.py execute():                                       widgets / mopActivities(+augmented) /
  MOP arm + no full JSON  → RVToolExecutionError         optionsMenus→gateways / wtg / components
  MOP arm + derivation OK → adb push                       │
  /data/local/tmp/mop-artifact.json                        ▼
  ape.mopDataPath=…/mop-artifact.json                    load failure + MOP plan → abort before
                                                         step 1 (INV-MOP-22; rearch-02 frame)
                                                         MOP_DATA trace record (rearch-04)
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `derive_mop_artifact.derive(document)` | Pure projection: full static-analysis dict → compact artifact dict (all parse-time semantics of today's `MopData.load` relocated host-side; `Target`→`MOP` renaming happens here) | full JSON `dict` | compact `dict` |
| `derive_mop_artifact.serialize_canonical(artifact)` | Canonical byte serialization (sorted keys, fixed separators, UTF-8) | compact `dict` | `bytes` (byte-identical for identical input) |
| `ApeRVTool._derive_mop_artifact(task)` (replaces `_compact_static_analysis_json`) | Cache-or-generate `<apk>.mop.json` next to the full JSON; fail loud on MOP arms | `task` | host path or raised `RVToolExecutionError` |
| `MopData.load(path, expectedPackage, expectedMainActivity)` (rewritten) | Parse the compact artifact only; unchanged public query API; version/sentinel/strict-match validation; status line / `MOP_DATA` record | device file | `MopData` or `null` |
| `MopData` query surface (unchanged) | `getWidget`, `activityHasMop`, `getMopActivities`, `activityHasMopOptionsMenu`, `getWtgTransitions`, component getters, `getPackageName`/`getMainActivity` | — | — (zero consumer edits) |
| Gateway recompute (jar, ~10 LOC) | `optionsMenus[]` records + WTG + the flag-selected MOP-activity set → `activitiesWithMopOptionsMenu` (keeps INV-MOP-13 sensitive to `mopActivitySourceComponents`) | loaded artifact | gateway set |
| `MopArtifactEquivalenceTest` (one-shot cutover gate) | Old parser (full JSON) vs new parser (derived artifact): projection identity over the `data/instrumented_apks/` corpus | corpus dir (`-Dmop.corpusDir`) | pass/fail; deleted after cutover with the old parser |

## Mapping: Spec → Implementation → Test

| Requirement / Invariant | Implementation | Test |
|---|---|---|
| Compact artifact contents (static-analysis-entrypoints: Derived compact MOP artifact) | `derive_mop_artifact.derive` | `test_derive_mop_artifact.py` (cryptoapp expectations mirroring `MopDataTest`) |
| Generator determinism (INV-DRV-05) | `serialize_canonical` | `test_derive_determinism` (same bytes in ⇒ same bytes out, twice) |
| Relocated derivation semantics INV-DRV-01..04 (ex INV-MOP-12/17/19/20/25/30, INV-WTG-04) | `derive_mop_artifact` sub-functions | per-rule unit tests + corpus equivalence gate |
| Loader consumes only the compact format; unknown/full JSON rejected (`version-mismatch`) | `MopData.load` rewrite | `MopDataTest` (rewritten on `cryptoapp.apk.mop.json` fixture) |
| Fail-fast: MOP plan + missing/unreadable/mismatched artifact aborts before step 1 (INV-MOP-22 preserved; V21 killed) | `StatefulAgent` `requireMopArm` (existing) + `tool.py` raise | jar: existing INV-MOP-22 tests extended; Python: `test_aperv_tool.py` absent-JSON raises |
| `MOP_DATA` record fields (status, reason, formatVersion, sourceDigest, counts) | status-line emission in `MopData.load` (record framing owned by rearch-04) | log-line assertion tests |
| Push path switch, cache, no warn-and-continue | `tool.py` step 1c | `test_aperv_tool.py` push/cache/error tests |
| R9 preservation (frozen metric sets) | full JSON untouched host-side; equivalence gate for mechanism sets | corpus equivalence test + grep audit that no metric pipeline reads `*.mop.json` |
| OOM path deletion (V19) | remove `catch(OutOfMemoryError)`, `PARSE_FOOTPRINT_FACTOR`, `PARSE_BUDGET_BYTES`, `reason=too-large` | grep-clean + compile; `MopDataTest` no longer references budget seam |

## Goals / Non-Goals

**Goals:**

- Device artifact contains exactly the projection the explorer consumes (~KB–low-MB; ceiling well under the 1–5 MB target — the projection excludes the 57.7 % call-graph share and the duplicate-heavy raw `transitions[]`).
- Single authority for derivation semantics, on the host, in testable pure Python; the jar parser becomes a thin typed reader.
- Behavior identity: every scoring/routing/trigger decision that today flows from the on-device parse is byte-identical when flowing from the derived artifact (corpus-gated).
- Silent no-MOP degradation (V21) eliminated on both sides; every skew direction (old jar × new artifact, new jar × old JSON, stale cache) fails loud before step 1.
- `MopData.java` shrinks from 1212 LOC to a target ≤ ~450 LOC; the only `catch(OutOfMemoryError)` in the repo is deleted.

**Non-Goals:**

- No change to the full-JSON producer (`rv-static-analysis`, rvsec-gator) or its schema.
- No change to frozen metric computation — the analysis pipeline keeps reading the full JSON and logcat only.
- No change to scoring semantics, weights, containment policy, eventType normalization on the query side, or trigger selection rules.
- No transitional dual-format support in the jar (P3: single coordinated cut, no adapters).
- No generalization of the artifact beyond aperv's needs (no speculative fields "for later").

## Decisions

### D1 — Generator lives in aperv-tool, runs lazily at execution time, cached next to the full JSON

Alternatives: (a) inside `rv-static-analysis` / the pre-processing pipeline (generate at instrumentation time); (b) inside aperv-tool at push time. Chosen: **(b) with caching**. The full JSON is produced by pre-processing into `task.results_dir`; aperv-tool already owns locating (`_find_static_analysis_file`) and shaping (`_compact_static_analysis_json`) it, and rearch-05 already touches this exact path. Placing the generator in `aperv_tool/tools/aperv/derive_mop_artifact.py` keeps the blast radius to one module in rv-android (zero changes to rv-platform/rv-experiment/rv-static-analysis) and keeps the derivation code next to the only consumer of its output contract. The derived artifact is cached as `<results_dir>/<apk_name>.mop.json` next to the full JSON — inspectable, diffable, and regenerated only when missing or when its recorded `source.digest` no longer matches the SHA-256 of the current full JSON (stale-cache skew impossible by construction).

### D2 — Wire schema v1: explorer-shaped, MOP vocabulary, explicit version

See API Design for the full schema. Shape decisions: widget map keyed `baseActivity → shortId` (the exact lookup structure `getWidget` uses today); per-widget MOP flags as **one map of normalized eventType → `none|direct|transitive|both`** — lossless w.r.t. the two Java maps, and key *presence* is semantic (drives the per-event vs aggregate fallback in `isDirectMop`/`isTransitiveMop`, so `none` entries are never omitted); aggregates are recomputed as the OR over the map (INV-MOP-17 preserved by construction). WTG as `sourceBaseActivity → [{widget, target}]` (deduplicated; `widgetClass` dropped — zero readers). Components carry only the trigger surface (see inventory); `targetMethods` compacts to `hasTargetMethods: bool` (only an emptiness test survives in `buildTriggerTuples`). The wire format speaks `MOP` (`mop`, `mopActivities`, `reachesTarget` is renamed `reachesMop` — the D7 boundary now sits in the generator, the single place that still reads `*Target` keys).

### D3 — Dual MOP-activity sets; OPTIONSMENU gateways recomputed on-device from a tiny record

`Config.mopActivitySourceComponents` (default `false`) selects between the widget-derived set and the A′ 3-source union at runtime (INV-MOP-27). Baking one choice into the artifact would turn a run flag into a generation flag (two artifacts per app — rejected). The generator emits both: `mopActivities` (widget-derived, dialog-merge included) and `mopActivitiesAugmented` (3-source union); the jar selects by flag, preserving byte-identical INV-MOP-27 semantics. Because the OPTIONSMENU gateway set depends on the *selected* set (condition 2 tests `mopActivities.contains(target)`), gateways are not shipped precomputed; the generator emits `optionsMenus: [{activity, hasFlaggedWidget}]` and the jar recomputes the gateway set with the same two-condition rule over its own WTG view (~10 LOC, pure, unit-tested). Alternative (two precomputed gateway sets) rejected as redundant data with a consistency obligation.

### D4 — Device path changes to `/data/local/tmp/mop-artifact.json`

The artifact is a different contract, so it gets a different identity on every surface: host cache `<apk>.mop.json`, device path `mop-artifact.json`, `ape.mopDataPath` value updated. Skew is loud in both directions even without the rename (old jar rejects the compact file via the absent `complete` sentinel; new jar rejects a full JSON via `version-mismatch`), but the rename additionally makes "what was pushed" unambiguous in traces and `ls` output. The full JSON is **never** pushed again; the `too-large` reject class loses its trigger and is deleted.

### D5 — Delete the OOM containment and the parse budget (V19)

`PARSE_FOOTPRINT_FACTOR`, `PARSE_BUDGET_BYTES`, the `reason=too-large` reject, and the outer `catch(OutOfMemoryError)` (INV-MOP-26/29) exist only because a 48 MB call-graph could arrive on a ~192 MB heap. The generator bounds the artifact by construction (projection of the ≤ 12.45 MB corpus worst case is orders of magnitude smaller than the heap); a pathological artifact is a generator bug caught host-side, not a device-runtime condition to survive. Per report Sec. 6.7 ("no heroic catch"), an OOM becomes what every other OOM already is: process death → task FAILED in the supervisor. The single-allocation `readFile` is retained (it is simply correct); the budget/OOM machinery is deleted, not preserved behind the smaller input.

### D6 — Fail-fast composition (rearch-02 × INV-MOP-22 × strict match)

Two validation layers, both before step 1, with distinct responsibilities: (1) **plan coherence** — rearch-02's `RunSpec` validation aborts when the MOP feature is declared without its artifact-path parameter (and, Python-side, `tool.py` raises when a MOP arm has no full JSON to derive from — the V21 warn-and-continue is deleted); (2) **artifact integrity** — `MopData.load` at agent construction rejects missing/unreadable/malformed/`version-mismatch`/incomplete artifacts with a reasoned status record, and the existing INV-MOP-22 contract (`requireMopArm` → `StopTestingException`) turns any `null` into an abort. `mopStrictPackageMatch` keeps its current semantics and default (warn-only, T1.7) against the artifact's own `package`/`mainActivity`; promoting it to always-strict is a behavior change deliberately out of scope (it becomes an ordinary plan parameter under rearch-02). Net: there is no input state under which a MOP-planned run silently explores without MOP guidance.

### D7 — Determinism as a wire property, not a best effort

`serialize_canonical`: UTF-8, keys sorted lexicographically at every object level, `separators=(",", ":")`, `ensure_ascii=false`, arrays in deterministically derived order (source first-occurrence for WTG edges and component lists; sorted for the activity sets). Identical full-JSON bytes ⇒ identical artifact bytes ⇒ stable `source.digest` chain for provenance (the artifact records the SHA-256 of the full JSON it was derived from; the `MOP_DATA` record echoes it, so a trace names its exact static-analysis input).

### D8 — Single coordinated cut; no fallback window

The wire format is BREAKING cross-repo. Rollout: one ape commit (parser rewrite + fixture) and one rv-android commit (generator + push switch) land together; the Docker image rebuilds `ape-rv.jar` from source at build time (hard constraint 2b), so a single image rebuild deploys both sides atomically — there is no state in which a fleet mixes formats within one experiment. No compatibility shim in either direction (P3): the corpus equivalence gate (run pre-cutover with both parsers in-tree) is the safety mechanism, and any post-cutover skew (e.g., a stale jar on a dev device) fails loud per D6. Alternative (jar accepts both formats for one release) rejected: it preserves the 1212-LOC parser this change exists to delete and reintroduces a silent-path risk.

### D9 — R9 preservation is by provenance first, equivalence second

The frozen metric definitions never touch the device artifact: *MOP coverage* is computed over `directly_reaches_mop` from the **full JSON** host-side; *unique misuse* and the `Mneut` prefix test are computed from logcat via the instrumented APK. The full JSON remains where it is, byte-identical, as the host-side source of truth — so the metric sets are untouched by construction, and a grep audit asserts no analysis-pipeline code reads `*.mop.json`. What the derivation must additionally preserve is the *mechanism* under study: the exact sets that steer exploration (widget flag maps incl. per-event entries, both MOP-activity sets, gateway inputs, WTG views, trigger tuples, `package`/`mainActivity`). That is the corpus equivalence gate: for every JSON of the corpus plus the cryptoapp fixture, old-parser(full) and new-parser(derive(full)) must produce identical projections.

**Corpus provenance — to be pinned before the gate runs (task 1.4).** The percentages above were measured over a 134-file working set that is *not* reproducible from this repository: `data/` here contains only `system-broadcast.json`, and no `data/instrumented_apks/` exists. The full static-analysis JSONs are produced and held by the sibling dataset repo (`rvsec-dataset/static_analysis/`, 345 `.apk.json` at the time of writing). Before the equivalence gate is run, the exact corpus path, its file count, and the command that produced the 57.7 % / 5.0 % / 10.1 % split MUST be recorded in this design — the gate is parameterized by `-Dmop.corpusDir` and is meaningless without a named, countable corpus.

## API Design

### `derive(document: dict) -> dict` (Python, pure)

Preconditions: `document` is a parsed full static-analysis JSON with `complete == true` (caller rejects otherwise). Postconditions: returns the compact artifact dict per the schema below; no mutation of `document`; every relocated rule (producer-precedence, lambda recovery, collision rank, empty-id drop, dialog re-keying, base-activity keying, dedup, A′ union) applied exactly as specified in the delta specs. Raises `DerivationError` on structural violations (missing `package`, non-dict sections) — never returns a partial artifact.

### `serialize_canonical(artifact: dict) -> bytes`

Deterministic canonical encoding (D7). Same dict ⇒ same bytes, cross-platform.

### `MopData.load(String path, String expectedPackage, String expectedMainActivity)` (rewritten)

Same signature, same null-on-failure contract (INV-MOP-01), same status-line/record discipline (INV-MOP-21), same downstream INV-MOP-22 abort. New reject reasons: `version-mismatch` (missing/unsupported `formatVersion` — this is also what any legacy full JSON now produces). Removed reject reasons: `too-large`, `oom`. Query API unchanged (see inventory); `getReachability`/`getWindows`/`getWindow`/`getTransitions`/`isWidgetlessSubstrate` are deleted with their storage (test-only readers).

### Compact artifact schema (`formatVersion: 1`)

```json
{
  "formatVersion": 1,
  "package": "br.unb.cic.cryptoapp",
  "mainActivity": "br.unb.cic.cryptoapp.MainActivity",
  "source": {
    "digest": "sha256:<hex of the full JSON bytes>",
    "file": "cryptoapp.apk.json",
    "generator": "aperv-derive/1"
  },
  "widgets": {
    "<baseActivity>": {
      "<shortId>": {
        "mop": {"click": "direct", "longclick": "transitive", "scroll": "none"},
        "inputType": "textPassword",
        "hint": "…", "prompt": "…", "spinnerMode": "…",
        "contentDescription": "…", "tooltipText": "…",
        "entries": ["…"]
      }
    }
  },
  "mopActivities": ["…"],
  "mopActivitiesAugmented": ["…"],
  "optionsMenus": [{"activity": "…", "hasFlaggedWidget": true}],
  "wtg": {
    "<sourceBaseActivity>": [{"widget": "<widgetName>", "target": "<targetBaseActivity>"}]
  },
  "components": {
    "activities": [{"className": "…", "isMain": false, "exported": true,
                     "permission": null, "reachesMop": false}],
    "receivers":  [{"className": "…", "isMain": false, "exported": true,
                     "permission": null, "reachesMop": true, "hasTargetMethods": true,
                     "intentFilters": [{"actions": ["…"], "categories": ["…"]}]}],
    "services":   [{"…": "same shape as receivers"}],
    "providers":  [{"className": "…", "isMain": false, "exported": false,
                     "permission": null, "reachesMop": true, "authorities": "…"}]
  },
  "stats": {"windows": 5, "widgetsTotal": 51, "flagged": 2, "droppedFlaggedNoId": 0,
            "orphanDialogs": 0, "handlersUnmatched": 0, "syntheticLambda": 0,
            "recovered": 0, "wtgEdges": 12, "dedupedTransitions": 0}
}
```

Notes: metadata fields and `entries` are emitted only when non-empty (absent = null); a widget appears only if it is MOP-flagged **or** carries ≥ 1 consumed metadata field (an unflagged, metadata-less widget is unreadable through any production path); `mop` map keys are pre-normalized (lowercase, separators stripped — the query side still normalizes, so INV-MOP-08 holds end-to-end); `stats` are generator-computed diagnostics echoed on the load record (the jar recomputes nothing it does not need — `droppedFlaggedNoId`, handler-join counters, and orphan-dialog counts become host facts, satisfying the observability the old load line provided).

## Data Flow

1. Pre-processing (unchanged) writes `<results_dir>/<apk>.json`.
2. `tool.py` step 1c (MOP arms only): `_derive_mop_artifact(task)` → cache hit (digest match) or `derive` + `serialize_canonical` + write `<apk>.mop.json`. Missing full JSON or `DerivationError` ⇒ `RVToolExecutionError` (task fails loud; V21 dead).
3. `adb push <apk>.mop.json /data/local/tmp/mop-artifact.json`; `ape.properties` gets `ape.mopDataPath=/data/local/tmp/mop-artifact.json`.
4. Jar bootstrap: `MopData.load` parses the artifact, validates `formatVersion`/`complete`-analogue (`formatVersion` **is** the sentinel — a generator only emits complete derivations), strict-match check, builds the query structures (gateway recompute per D3), emits the `MOP_DATA` record (`status=loaded formatVersion=1 sourceDigest=… widgets=… flagged=… wtgEdges=… mopActivities=… augmented=… components=…`).
5. Exploration reads only the query API — unchanged.
6. Analysis pipeline reads the **full** JSON + logcat — unchanged (R9).

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| Full JSON absent for a MOP arm | `tool.py` step 1c | `RVToolExecutionError` (was: warn-and-continue, V21) | fix pre-processing for the app, or run a non-MOP arm |
| `DerivationError` / malformed full JSON | `derive` | `RVToolExecutionError`; no partial artifact written | inspect full JSON; producer bug |
| Stale cache (`source.digest` mismatch) | `_derive_mop_artifact` | transparent regeneration | none needed |
| Artifact missing/unreadable on device | `MopData.load` | `status=rejected reason=file-missing` → null → `StopTestingException` (INV-MOP-22) | push path bug; task FAILED, retried by supervisor |
| `formatVersion` absent/unsupported (incl. a legacy full JSON pushed by a stale tool) | `MopData.load` | `status=rejected reason=version-mismatch` → abort | redeploy coordinated pair (D8) |
| Malformed JSON on device | `MopData.load` | `status=rejected reason=parse-error` → abort | push corruption; retry |
| Package/mainActivity mismatch | `MopData.load` | WARN; reject only under `mopStrictPackageMatch=true` (unchanged T1.7) | wrong artifact for APK |
| OOM during load | — | **no handler** (D5): process death → task FAILED | supervisor retry; a recurring one is a generator bug |

## Risks / Trade-offs

- [Derivation semantics drift from what the old parser did] → the corpus equivalence gate runs both parsers over the full pinned corpus pre-cutover (path and count fixed by task 1.4); per-rule unit tests pin each relocated invariant permanently on the Python side.
- [Coordinated cut leaves a skewed pair somewhere (dev device, stale image)] → every skew direction is a reasoned reject + pre-step-1 abort (D6); no silent path exists.
- [Dropping parsed-and-exposed surfaces (`DataSpec`, `widgetClass`, `targetMethods` list, windows/reachability getters) forecloses future consumers] → they remain in the full JSON host-side; re-adding a field is a `formatVersion` bump + generator change, cheap and loud. Accepted per P1/P3.
- [Two artifacts per app on the host (~+KBs) and a derivation step in the task path] → derivation is milliseconds on host hardware and cached; negligible against the removed on-device parse of MBs per run.
- [`stats` echoed, not recomputed — a buggy generator could misreport diagnostics] → diagnostics are observability, not behavior (INV-MOP-31/32 discipline preserved); the equivalence gate validates the behavioral sets independently of `stats`.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (Python) | `derive` rules: flags/precedence/lambda recovery, collision rank, empty-id drop, dialog re-keying, WTG keying+dedup, A′ dual sets, optionsMenus, stats; `serialize_canonical` determinism; cache/digest logic; fail-fast raise | pytest on cryptoapp + synthetic fragments | ~25 |
| Unit (JVM) | rewritten `MopData.load` on `cryptoapp.apk.mop.json` fixture: every query-API expectation currently in `MopDataTest` restated against the compact fixture; reject reasons; gateway recompute; flag-selected set | JUnit, fixture in `src/test/resources/` + `test-apks/` | ~20 (replacing the parse-machinery share of the current 1005-line `MopDataTest`) |
| Equivalence (one-shot gate) | old-parser(full) ≡ new-parser(derived) on widget maps, both activity sets, gateways, WTG views, trigger/provider tuples, scalars | JVM test over the pinned corpus directory via `-Dmop.corpusDir` (path recorded by task 1.4); deleted with the old parser after green | 1 × corpus |
| Integration (Python) | `tool.py` step 1c: derive-cache-push order, device path, properties line, error propagation, non-MOP arms untouched | pytest with mocked adb | ~6 |
| End-to-end | cryptoapp `sata_mop` on the RVSec AVD: `MOP_DATA status=loaded`, boost fires, run completes | manual device smoke | 1 |

## Open Questions

None — all shaping decisions are fixed above; the only deferred item (promoting `mopStrictPackageMatch` to always-strict) is explicitly out of scope and re-expressible as a rearch-02 plan default later.
