# Tasks: rearch-07-compact-static-artifact

**Worktree** (decided 2026-08-03): all 7 stages are implemented in a single git worktree on branch `rearch` (`git worktree add ../ape-rearch -b rearch`), merged into `master` only after stage 7 — this stage is the last, so its merge is the one that closes the branch. Setup, teardown and the `mvn install` caveat: `docs/20260803_procedimento_worktree_rearch.md`. Group 2's host-side generator lands in rv-android, which the ape worktree does NOT cover — it needs a matching branch there (procedure doc §4).

<!-- Subagent dispatch hints:
     - Group 1 (inventory ratification) must complete first — the schema is derived from it.
     - Group 2 (generator, rv-android) and Group 3 (jar fixture + new parser) can proceed in
       parallel after Group 1; both are gated by Group 4 (equivalence) before any deletion.
       Group 4 was a 345-app corpus gate and is now fixture-scoped — see its header for what that
       costs and what already paid for the breadth it no longer measures.
     - Group 5 (jar cutover: delete old parser) and Group 6 (tool.py push switch) are the
       coordinated BREAKING pair — they land together (design D8), after Group 4 is green.
     - Critical path: 1 → 2 → 4 → 5/6 → 7 → 8.
     - Cross-repo: Groups 2, 6 edit rv-android (aperv-tool module only); Groups 3, 5 edit ape. -->

## 1. Consumption inventory ratification (read-only, gates the schema)

- [x] 1.1 Re-run the caller audit of every `MopData` accessor in `src/main` and diff it against the inventory table in `design.md`; any new consumer added since this design was written amends the schema before anything else proceeds
  - Audited 2026-08-05 over all 23 accessors. **No new field is consumed — the projection stands.** What moved is *where*: `rearch-03` lifted the launcher out of `SataAgent` into pipeline stages, so four inventory rows named a consumer that no longer exists (`SataAgent.selectTriggerCandidate`/`buildDeepLinkUri`/`:347`/`:547` → `MopLauncherStage:106-117`/`:195`, `ComponentTriggerStage:69`). `design.md` corrected.
  - **Schema amendment found and applied**: `exported` is parsed, stored and shipped on every component and read by *nothing* in either repository (25 sites in `src/main`, all writes; 13 in `src/test`, all constructor arguments). It is absent from the inventory's "fields actually read" column, and `component-triggering` forbids consulting it. Owner decided 2026-08-05 to drop it from the wire; `design.md` schema, both delta §7s and the `component-triggering` export scenario updated. Owed to `gh96`: generator + tests still emit it (`derive_mop_artifact.py:1151`).
- [x] 1.2 Confirm zero production readers of the drop list, **by grep against `src/main`, not by reading this design**: `WtgTransition.widgetClass`, `ProviderInfo.readPermission`/`writePermission`, `Widget.id`/`text`/`type`, raw `listeners`, `targetMethods` beyond `isEmpty()`, `getReachability`/`getWindows`/`getWindow`/`getTransitions`/`isWidgetlessSubstrate`, **`exported`** (added 1.1). `IntentFilter.data` was on this list and does **not** belong: `MopLauncherStage.buildDeepLinkUri` (`:195`) reads it and `:117` calls it — it is projected to `deepLinkUri` instead (INV-DRV-07). Treat that as the standing warning about this list's provenance and re-derive every remaining member first-hand
  - Re-derived first-hand 2026-08-05, member by member, and the list holds. Two members needed the warning's own discipline to clear: `WtgTransition.widgetClass` greps to three hits in `ApePromptBuilder` that are its **own** `ActionHistoryEntry.widgetClass`, an unrelated field — the `MopData` one has zero readers; and `reachesTarget` survives only for receivers/services (`StatefulAgent:1305`) and providers (`:1324`), never for an activity, whose flag `MopLauncherStage` is explicitly forbidden to consult. `targetMethods` is read exactly once, as `.isEmpty()` (`:1313`). `Widget` metadata reads are the 7 inventory fields and nothing else (`ApePromptBuilder.widgetMetadata`, `ApeAgent:245`).
- [x] 1.3 Confirm the analysis/metrics side reads only the full JSON: grep rv-android (`rv-coverage`, `rv-platform`, consolidation scripts) for static-analysis JSON readers; assert none will resolve `*.mop.json` (R9 provenance argument, design D9)
  - Verified 2026-08-05. The risk this task exists for is a glob: the artifact caches *next to* its source, so a `*.json` sweep of `results_dir` would swallow it. There is none — every resolution is an exactly-constructed filename (`pre_processor` builds `app_path + EXTENSION_STATIC_ANALYSIS`; `_find_static_analysis_file` builds `<apk_name>.json`; the rest name `tasks.json`/`results.json` literally), and `<apk_name>.mop.json` matches no constructed name. The sole `.mop.json` reference outside `aperv-tool` is `scripts/gh96_derive_corpus.py`, the gate driver `gh96` task 7.6 deletes.
- [x] 1.4 **Corpus pinned** (design "Corpus provenance"): `<workspace>/rvsec-dataset/static_analysis/`, 345 `*.apk.json`, 766 MB, verified 2026-08-03; `data/instrumented_apks/` never existed in this repo. What remains for this task is confirming the corpus is reachable from wherever the gate runs (it is a sibling repo, not vendored here — the gate must fail with a clear message when `-Dmop.corpusDir` is unset or empty, never silently pass on zero apps)
  - Reachable from this worktree 2026-08-05: `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-dataset/static_analysis/` — 345 `*.apk.json`, 766 MB, unchanged since the 2026-08-03 pin. Note the absolute prefix is `/home/pedro/...`; the ape worktree resolves under `/pedro/...`, so the two are siblings only through the shared workspace root and `-Dmop.corpusDir` must be given the `/home/pedro` path. The hard-fail-on-unset requirement is task 4.2's to implement and is untouched by this confirmation.

## 2. Host-side generator (rv-android: `modules/aperv-tool`) — DELIVERED BY `gh96`

This group was written as if the generator were unbuilt. It is built. `gh96-mop-artifact-derivation`
(rv-android, branch `rearch-counterparts`) delivered `derive_mop_artifact.py` — 1,212 lines, 67
passing tests — and its groups 1–6 cover every task below. The boxes are ticked against a rule-by-rule
reading of that code, not against `gh96`'s own checkboxes, with the delivering task named in each
note. What this group still owes is one thing `gh96` could not have known about: the `exported` drop
that group 1 found (task 2.9).


- [x] 2.1 Create `aperv_tool/tools/aperv/derive_mop_artifact.py`: `derive(document) -> dict` skeleton with `DerivationError`, preconditions (`complete == true`, non-null `package`), and the `formatVersion: 1` envelope (`package`, `mainActivity`, `source.{digest,file,generator}`)
  - `gh96` 1.1/1.2/1.6. Verified: `DerivationError:149`, `derive():204`, constants `FORMAT_VERSION=1`/`GENERATOR_ID="aperv-derive/1"`, preconditions raising on `complete` absent/false and missing `package`.
- [x] 2.2 Implement flag derivation: `bySignature` index from `reachability[]` (duplicate signatures merged by OR, not last-write), producer-precedence, D8 synthetic-lambda recovery, per-normalized-eventType `none|direct|transitive|both` encoding, and `transitive = reachesTarget OR direct` on every path so `direct` implies `transitive` (INV-DRV-01). The two axes stay independent: `direct` keeps the producer's 0-hop meaning and is **not** redefined as any-depth reach the way the retired `INV-APV-32` enrichment did (design D10)
  - `gh96` 1.4/2.1/2.2/2.5. Verified rule by rule: duplicate signatures merge through `_or_flags():452`, not last-write; producer precedence returns before the local join (`_derive_listener_flags():500`); the D8 recovery falls back to `lambda_by_class` and **returns unflagged when the enclosing class has no reaching lambda**, which is the negative case that keeps the rule from flagging every wrapper; and `transitive = reaches or direct` appears on all three tiers plus at index time, so `direct` implies `transitive` on every path. The two axes are never collapsed.
- [x] 2.3 Implement the widget map: base-activity keying, `mopRank` collision policy, empty-id drop with `stats.droppedFlaggedNoId`, metadata projection, emit-only-if-flagged-or-has-metadata rule (INV-DRV-02). **Order matters**: a flagged widget marks its base activity in the widget-derived MOP-activity set *before* the empty-id drop removes it, mirroring `MopData.java:428-444` — deriving that set from the emitted map instead is the silent-shrink bug this rule exists to prevent
  - `gh96` 2.3/2.4/2.6. The ordering rule is the one thing worth reading the code for, and it holds: `_build_widget_map():733` runs `flagged_activities.add(activity)` before the `if not widget["shortId"]: continue` drop, so an activity whose only flagged widget is id-less still enters the set while `droppedFlaggedNoId` counts the widget. Collision uses strict `>` on `_mop_rank`, so a tie keeps the first occurrence and the result is order-independent.
- [x] 2.4 Implement transitions processing: WTG click view keyed by base source activity, exact-duplicate removal, DIALOG re-keying (first incoming edge, move-not-copy, host promotion, orphan count) (INV-DRV-03)
  - `gh96` 3.1/3.2/3.6, implemented across `_rekey_dialogs():831`, `_resolve_dialog_host():904` and `_build_wtg():931`.
- [x] 2.5 Implement activity sets (`mopActivities`, fed by flagged widgets per 2.3 and by the dialog-host promotion of 2.4, + A′ `mopActivitiesAugmented`), `optionsMenus` records, component projection (`reachesMop` rename, `hasTargetMethods`, trigger-surface fields only, and the per-activity `deepLinkUri` derivation — first `ACTION_VIEW` filter with a non-empty scheme list ⇒ `scheme://host + path`, absent otherwise, INV-DRV-07), and the `stats` block (INV-DRV-04, INV-DRV-06)
  - `gh96` 3.3/3.4/3.5/3.7. `_derive_deep_link_uri():1035` matches INV-DRV-07 clause for clause — first `ACTION_VIEW` filter with a non-empty scheme list, host and path defaulting to empty, `None` otherwise. `_project_components():1076` performs the `reachesMop` rename and compacts `targetMethods` to `hasTargetMethods`.
- [x] 2.6 Implement `serialize_canonical(artifact) -> bytes` (sorted keys, fixed separators, UTF-8, deterministic array orders) (INV-DRV-05)
  - `gh96` 4.1/4.4. `serialize_canonical():179` is `sort_keys=True, separators=(",", ":"), ensure_ascii=False`, UTF-8, no trailing newline; array order is fixed by construction rather than sorted where order carries meaning.
- [x] 2.7 pytest suite — this is the **permanent** protection once the one-shot gate is deleted, so it must not be cryptoapp-shaped (cryptoapp has no synthetic lambdas, no orphan dialogs, no empty-id widgets and a trivial A′). Beyond the cryptoapp ground-truth scenario, determinism (twice-derived byte identity) and the no-`*Target*`-key / no-call-graph assertion, it SHALL carry a named test per relocated semantics, on synthetic fragments where the corpus is thin:
  - listener × reachability cross-reference with producer precedence (INV-DRV-01)
  - D8 synthetic-lambda recovery, including the negative case (`X` with no reaching `lambda$…` ⇒ not flagged)
  - DIALOG re-keying, all five coupled sub-rules incl. host promotion and the retained dialog-class entry (INV-DRV-03)
  - the A′ union with all three sources contributing distinctly (INV-DRV-06 selection surface)
  - **the flagged-empty-id activity-marking rule of INV-DRV-02** (widget dropped, activity present, `droppedFlaggedNoId` incremented)
  - `deepLinkUri`: derivation, the three null cases (no `ACTION_VIEW`, empty scheme list, no filters), and host/path defaulting (INV-DRV-07)
- [x] 2.8 Run `/rv-doc-code modules/aperv-tool/src/aperv_tool/tools/aperv/derive_mop_artifact.py` (rv-android module ⇒ `rv-*` skill)
  - `gh96` 4.5. The docstrings argue *why* at each relocated rule — the D8 tier explains what the recovery buys and why it cannot flag every wrapper; `_build_widget_map` explains what deriving the activity set from the emitted map would cost.

- [x] 2.9 **Owed to `gh96`, found by task 1.1**: stop emitting `exported`. `_project_component()` writes `"exported": entry.get("exported") is True` (`derive_mop_artifact.py:1151`) and the tests assert it; no consumer in either repository reads it, and `component-triggering` forbids one from ever existing. Needs an rv-android edit under `gh96` (owner authorization + a `plan(gh96)` commit before the `feat(gh96)` one, per the cross-repo rules). Until it lands, the group-4 gate compares a field the jar will not carry
  - Landed 2026-08-05 as `gh96` group 9 (`a297de3b` plan, `8e86c9f9` feat). `_project_component` no longer
    emits it and the projection test now asserts its absence on all three component kinds — mutation-checked,
    since the five existing `exported` occurrences were all *input* documents and nothing would have noticed
    the field returning. aperv-tool suite 282 passed, 30 skipped.

## 3. Jar-side rewrite, with the old parser retained as the equivalence oracle (ape, working tree only)

- [x] 3.1 Generate the fixtures with the Group-2 generator: `src/test/resources/cryptoapp.apk.mop.json` (from `cryptoapp.apk.gh60-fresh.json`) and `test-apks/cryptoapp.apk.mop.json` (from `test-apks/cryptoapp.apk.json`)
  - Generated 2026-08-05 with the post-2.9 generator, so neither fixture carries `exported`. The two
    inputs are byte-identical (`md5 e4f7d9af…`), so the artifacts differ only in `source.file` and its
    digest: **69,977 → 4,126 bytes, 5.9 % of the source.** Both carry `flagged=3`, `mopActivities` of
    three, 4 activities and 1 provider — the corrected ground truth, not the stale 2.
  - The generation surfaced two counter facts that the artifacts had wrong, both now fixed: `widgetsTotal`
    is **30**, not the 51 this task's author (and I, initially) got by counting raw widgets — the counter
    is defined over the widget map after the dialog merge, and the jar's own record agrees at 30. And
    `wtgEdges` is **16** against the jar's **17**, the difference being one exact-duplicate edge the
    derivation removes; see 4.2 for what that obliges the gate to do.
- [x] 3.2 Implement the compact-format parser as new code paths in `MopData`. The full-JSON parser stays in the working tree only because it is the **oracle** of the group-4 equivalence gate (tasks 4.2/4.3 — never adjust the oracle); group 5 deletes it, and groups 3+5 land in one commit (task 7.1), so no shipped state ever contains both. This is oracle scaffolding, not a fallback window (design D8): version gate (`reason=version-mismatch`, INV-MOP-34), widget/flag decoding with explicit-`none` entries, wire sets, WTG view, components, stats echo
  - Landed as `MopData.loadCompact` plus five private decoders, all additive — the full-JSON `load`
    and every helper it calls were not touched, so the oracle is still the code group 4 will compare
    against. The old parser derives; this one only transcribes (INV-MOP-35), which is why it carries
    no budget guard and no OOM catch even before group 5 deletes them from the old path.
  - Three decisions the wire forced, each recorded where it lives in the code: the `mop` map is
    **normalized on ingest** rather than trusted pre-normalized, because the query side normalizes
    and an unnormalized wire key would be present in the map yet unreachable through every accessor;
    `hasTargetMethods` is rebuilt as a list of the right *emptiness* (`StatefulAgent:1313` is the
    only reader and it calls `.isEmpty()`); and `mopActsAugmented` is recovered as the set difference
    `augmented \ widget-derived`, since the wire ships both sets whole and the pre-change record's
    number was the count the augmentation *added*.
  - Verified by running, not by reading (learning 30): a temporary probe loaded the fixture and
    printed the record and every consumed query. The record is field-for-field the handoff's measured
    oracle — `windows=5 widgets=30 flagged=3 droppedNoId=0 handlersUnmatched=5 syntheticLambda=1
    recovered=1 mopActivities=3 mopActsAugmented=0` — with `wtgEdges=16` against the jar's 17, the
    one exact-duplicate edge the derivation removes (§4.2). Queries agree with the corrected ground
    truth: 3 flagged widgets all `transitive`/not `direct`, `activityHasMop` true for the three MOP
    activities and false for `MainActivity`, 4 activities, 1 provider with its authorities, the
    spinner's 13 `entries`, and per-event fallback working (`isTransitiveMop("long_click")==true`
    from the aggregate where the wire has only `click`). A legacy full JSON returns null with
    `status=rejected reason=version-mismatch`. The probe was deleted; 3.5 writes the real assertions.
- [x] 3.2a `ComponentInfo` gains `deepLinkUri`, decoded from the wire for activities; the launcher call site (`MopLauncherStage:117`) passes `candidate.deepLinkUri` and `MonkeySourceApe`'s dispatch is untouched. Verify against the `component-triggering` delta that the restored dispatch paragraph matches what the code does — that paragraph also carries the explicit-intent rule and the pool exclusion, which the `rearch-03` rewrite of the same requirement had dropped
  - `deepLinkUri` went on the **base** `ComponentInfo`, not on `ActivityInfo`: `selectTriggerCandidate`
    returns a `ComponentInfo`, so a subclass-only field would have forced a cast at the one call site
    that reads it. It is null on every non-activity, which is what the wire says. `ActivityInfo` gained
    an 8-argument constructor; the existing 7-argument one delegates with null, so no other caller moved.
    `MopLauncherStage:117` now passes `candidate.deepLinkUri`. `buildDeepLinkUri` stays until 5.3 — it
    is the old side of 4.2's deep-link comparison, and `ActivityFrontierTest`'s six Lever B assertions
    call it directly rather than through `decide()`, so the call-site switch left them untouched.
  - The paragraph's three claims, checked against the code one by one. **Explicit-intent rule holds**:
    `setComponent(ComponentName(action.getPackageName(), className))`, the package coming from
    `MopData.getPackageName()` via the stage (INV-CT-04), with `FLAG_ACTIVITY_NEW_TASK` added inside
    `AndroidDevice.startActivity`. **Pool exclusion holds, structurally**: `buildTriggerTuples` draws
    only from `getReceivers()`+`getServices()` and `buildProviderTuples` only from `getProviders()`;
    `MopData.getActivities()` has exactly one production reader in the tree, `MopLauncherStage:106`.
  - **The deep-link clause did not hold, and the fix was to the spec.** It read "still targeted at the
    component", but `MonkeySourceApe.generateActivityTriggerEvent` sets action, data and **`setPackage`**
    on that branch and never a component. The wording is not this change's error: it traces to
    `2026-07-08-activity-frontier` design item 6 and is verbatim in the current main spec (`:171`), so
    no code has ever matched it. Corrected here, in this delta's paragraph and scenario and in the
    `mop-guidance` "Deep-link dispatch" scenario, to say `setPackage` and to name the consequence.
    Two live occurrences were left alone because they belong to stages this session may not touch:
    `rearch-03-decision-pipeline/specs/component-triggering/spec.md:39` and
    `rearch-04-step-ndjson-telemetry/…:41`. Both are earlier stages, so they archive before this one
    and this delta's MODIFIED block is what reaches the main spec last — but if either were archived
    *after* stage 7, the false clause would return.
  - Owner decision 2026-08-05: correct the artifact, not the code. Stage 7 relocates where the URI is
    computed; the group-4 gate compares derived data and could not attest a dispatch change, and the
    launcher path carries the study's strongest mechanism result. The behavioural half is **issue #17**,
    opened with the measurement that sizes it: over the pinned 345, 123 apps have a deep-linked activity
    and 173 activities carry one, but the two dispatch forms are distinguishable in only **7 apps**,
    where two activities assemble an identical URI (`content://` ×3, `file://` ×2, `geo://`, `mailto://`)
    — every one a filter whose discrimination lives in the `mimeTypes`/`pathPrefixes`/`pathPatterns`
    that `scheme + "://" + host + path` throws away. The collision is a symptom of a lossy assembly
    rule, which is INV-DRV-07's, so it travels host-side with the rule instead of being cured by it.
- [x] 3.3 Implement the on-device OPTIONSMENU-gateway recompute from `optionsMenus` + WTG + the flag-selected activity set (INV-MOP-13) and the `mopActivitySourceComponents` set selection (INV-MOP-27)
  - The selection happens once, at load, before the gateway recompute reads it, so no consumer
    downstream knows the flag exists — the launcher census, the substrate floor, the frontier target
    tests and gateway condition 2 all read one `mopActivities` field. The flag arrives through a
    package-private `loadCompact(path, pkg, main, activitySourceComponents, sink)` seam, the same
    idiom the full-JSON path uses to get past the `static final` wall on `Config`.
  - `recomputeMopOptionsMenus` is pure over its three arguments and 16 lines: condition 1 is the
    record's own `hasFlaggedWidget`, condition 2 is any click edge out of that base activity landing
    in the selected set. Same two conditions and same click-only view as the deleted precompute.
  - Verified on the fixture and on a synthetic artifact that discriminates the two sets, which
    cryptoapp cannot (its augmented set equals its widget-derived one). On the fixture,
    `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")` is now **true**, and via
    condition 2 specifically — the wire record for that activity carries `hasFlaggedWidget: false`,
    so it qualifies only through its WTG edges into the three MOP sub-activities, which is the
    scenario's stated route. On the synthetic (`mopActivities=[A]`, `mopActivitiesAugmented=[A,B]`,
    a gateway `G` whose one edge targets `B`, and a `H` with a flagged menu widget): flag off gives
    `{A}`, `activityHasMop("B")==false`, `gatewayG==false`; flag on gives `{A,B}`,
    `activityHasMop("B")==true`, `gatewayG==true`. `gatewayH` is true under both. The flip of
    `gatewayG` is the point — it is what proves condition 2 reads the *selected* set, which is the
    entire reason D3 refuses to ship the gateway set precomputed.
- [x] 3.4 Update the load status record: new success fields (`formatVersion`, `sourceDigest`, echoed stats), reject reasons reduced to `file-missing|parse-error|version-mismatch|package-mismatch` (INV-MOP-21 unchanged; INV-MOP-22 abort unchanged). **Keep `windows`** — it is on the stage-4 record and survives as `stats.windows`, so dropping it here would make the field appear at stage 4 and vanish at stage 7. Verify field-by-field against the stage-4 census that this record is a superset of it, minus `transitions` only (superseded by `wtgEdges` and deliberately never reinstated)
  - `EventSink.mopData` gained `formatVersion`, `sourceDigest` and `components`, dragging both sink
    implementations and all three emission sites. **The superset check was done against the stage-4
    census as written** (`rearch-04` `mop-guidance` spec `:41`): its eleven fields — `package`,
    `windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`,
    `syntheticLambda`, `recovered`, `mopActivities`, `mopActsAugmented` — are all present and none
    changed name. `transitions` is not lost *here*: stage 4 had already replaced it with `wtgEdges`,
    so this window ends with the field having been gone for two stages, which is the claim the task
    wanted checked and not quite the one it stated.
  - **The reject vocabulary is only half-reduced, and honestly so.** The compact path emits exactly
    the four reasons; `too-large`, `oom` and `incomplete` still exist on the full-JSON path, which
    is the group-4 oracle. They die with it in 5.1/5.2 — reducing them now would mean editing the
    oracle, which task 4.3 forbids for a much better reason than tidiness.
  - `sourceDigest` is omitted rather than emitted null on every reject, the same treatment `reason`
    and `package` already get: a load that never reached an artifact has no digest, not a null one.
    Mutation-checked — dropping the null guard fails the rejected-record test.
  - Verified by running. `testCompactLoadRecordCarriesProvenanceAndTheComponentCount` asserts the
    digest against a SHA-256 **computed in the test over the source fixture**, not against the
    string the artifact carries, so it pins the whole chain (source bytes → generator → wire →
    record) instead of reading a file back into itself; mutating the loader to echo `source.file`
    instead fails it. `components=5` (4 activities + 1 provider) mutation-checked at 0. The old
    path's decided values are pinned on the existing census test, which dies with the oracle.
  - `mopActsAugmented` is 0 on this fixture and always will be — cryptoapp's two sets are equal, so
    nothing here distinguishes the wire-set reading from the retired one. The synthetic that does is
    3.5's, and the test says so rather than implying the assertion has teeth it does not have.
  - `countOnlyIn`'s javadoc claimed the new number *recovers* the pre-change count "as a set
    difference rather than by observing a mutation". Under the owner decision that is exactly false,
    and it was the last place in the code still asserting the equivalence the REMOVED note was
    corrected to deny. Rewritten to state the difference and the flag-off 0→N consequence.
  - The `mop-guidance` REMOVED-note correction this task also owed landed in session 11's plan
    commit (`0675f67a`, delta `:254`); re-read and it says what the decision says. Suite 1179/0/19
    (1176 + the three new tests).
  - **`mopActsAugmented` keeps the wire semantics — owner decision 2026-08-05.** The number is
    `|augmented \ widget-derived|`, computed from the two wire sets and therefore **independent of
    `Config.mopActivitySourceComponents`**, not the old parser's "entries the augmentation added",
    which is 0 on every flag-off run. Three reasons, in the order they decided it. The flag is
    *already recoverable from the trace by another record*: `RunSpecEcho` emits `features` (carrying
    `MOP_ACTIVITY_SOURCE` when active) and `params` with the activation key of every live mechanism,
    so a reader recovers the applied augmentation as flag × available and loses nothing — whereas the
    old definition destroys the availability fact and nothing else carries it. Nothing consumes the
    field programmatically (`trace_ndjson.py:525` exposes `MOP_DATA` verbatim; no script in either
    repository reads this key), so the comparability at stake is human reading, not a pipeline. And
    after this stage the jar augments nothing: "what the augmentation added" stops being a fact about
    this load and becomes a fact about the wire, which is what the new number reports.
  - **Correct `mop-guidance`'s REMOVED note while implementing this.** It justifies folding INV-MOP-32
    by asserting that "counters over the load" and "counters over the wire sets" are *the same numbers*.
    They are not, on any flag-off run, under either definition — the sentence is false as written and
    must be qualified rather than left standing behind a decision it misdescribes.
  - **Old path's values for the three new fields**: `formatVersion=0` and `sourceDigest=null` are
    genuinely neutral — they are facts only a derived artifact has. `components` is **not** given a
    neutral 0: the full-JSON path has the typed component lists in hand at the emission site
    (`MopData.java:274-276`), so a 0 there would be a falsehood about something the path knows.
    Neutral where the fact is absent, true where it is present.
- [x] 3.4a Surface the launch result on the dispatch path (INV-CT-14). **The mechanism already landed — verify, do not rebuild.** `AndroidDevice.startActivity` returns a `LaunchResult` carrying the platform's `START_*` code (`:515-571`) and `MonkeySourceApe:980` hands it to the sink as `componentLaunch(launch.code, launch.error)`; the delta's "SHALL surface" reads as future work only because it was written before `rearch-04`. What is genuinely owed is the second sentence: recording only — no retry, no re-dispatch, no cursor or budget effect (INV-CT-12's "returned actions" accounting unchanged) — and **the test asserting the launcher behaves identically with the recording present and absent, which does not exist**. `LaunchResult` is referenced by exactly one test file (`NdjsonSinkTest`), and nothing tests INV-CT-14's no-effect clause
  - Verified, not rebuilt: `startActivity` returns the platform's `START_*` code (`AndroidDevice:545-573`)
    and `generateActivityTriggerEvent` hands it straight to `componentLaunch` (`MonkeySourceApe:980`).
    Not one line of production code changed for this task.
  - The owed test is `ActivityLaunchRecordingIsInertTest`, and it asserts the invariant over the
    **source tree** because it cannot be asserted from behaviour here: `MonkeySourceApe` will not
    class-load off-device (`UiAutomation` pulls in `android.app.IUiAutomationConnection`) and the
    dispatch reaches the platform by reflection on `IActivityManager`, so "run it with and without
    the recording and diff" is a device experiment, and the one device execution this stage gets
    measures coverage. The tree idiom is not improvised — `DeviceInputChannelAbsenceTest` asserts
    two absences the same way, with comment-stripping and a minimum-yield floor, both of which this
    test carries.
  - Two scans, failing for different reasons. **Containment**: `LaunchResult` occurs in the producer
    and at the one dispatch site and in no third file, so no stage, pass or agent can consult it —
    that is what makes "no candidate re-selection, no cursor or budget adjustment" structural rather
    than intended, since INV-CT-12's accounting lives in `MopLauncherStage`, which cannot see the
    type. **Inertness**: the local is read exactly four times, and all four are named verbatim
    (declaration, the pre-existing `dispatched()` warning, the two fields handed to the sink), so
    the count closes the set instead of merely bounding it; plus one dispatch, no loop, no `return`.
  - Mutation-checked, since an absence assertion is decoration until it can fail: adding a retry on
    `launch.code` fails the inertness scan, and making a stage name the type fails containment. The
    first draft of the test was itself wrong in two ways the mutations exposed — it asserted a
    single `if` in a method that legitimately has two (the deep-link intent selection has nothing to
    do with the launch), and it expected the wrong sort order. Both fixed by narrowing the claim to
    what the invariant actually says.
  - The invariant's harness half ("in the jar or in the harness") is not in this repository and this
    task does not claim it.
- [x] 3.5 JVM unit tests on the compact fixture: every scenario of the mop-guidance delta (fixture load, legacy-JSON rejection, per-event fallback decoding, flag-selected sets, absent metadata, unknown-key tolerance, strict-match reasons)
  - 18 tests in one contiguous section at the end of `MopDataTest`, plus one in
    `StatefulAgentTriggerTest`. The section boundary is not cosmetic: group 5 deletes the full-JSON
    parser and every test above that drives it, so a block is a deletion instead of an unpicking.
    The section reuses the helpers session 12 left for it (`recordsIn`, `sha256Hex`, `COMPACT`) and
    extends `onlyCompactLoadRecord` with the expected-package pair rather than cloning it.
  - **The synthetic of task 3.3's note is now a checked-in resource**
    (`src/test/resources/synthetic-activity-selection.mop.json`), because two tasks need it and only
    one of them is this one — 4.1 reuses it as its INV-DRV-06 member. It is what makes two
    assertions possible that the cryptoapp artifact cannot make at all, its two MOP-activity sets
    being equal: the **flip of gateway `G`** between flag states, which is the only evidence that
    condition 2 reads the *selected* set (and therefore the entire reason D3 refuses to ship
    gateways precomputed), and a **non-vacuous `mopActsAugmented`** — 1 under *both* flag states,
    which is the flag-independence the owner decision of 2026-08-05 defines. `H`, qualifying on its
    own flagged menu widget, is the control that must not move.
  - **"Exactly three flagged widgets" is asserted by walking the wire, not by naming three.**
    `getWidget` is the only query into the widget map, so a test that named three widgets and asked
    about those three could not tell three from thirty; the helper enumerates every `(activity,
    shortId)` key in the artifact and asks the loaded model about each. It also asserts in passing
    that every wire key is reachable through the query API, which is the whole of what this reader
    is supposed to do (INV-MOP-35). Mutation-checked by flagging every widget at ingest.
  - The MainActivity gateway is asserted **together with the wire fact that makes it interesting**:
    that record carries `hasFlaggedWidget: false`, so the only route to a true is condition 2. Read
    off the artifact in the test rather than asserted from memory — without it the test would pass
    against a loader that ignored the WTG view entirely.
  - **Task 7.3(a)'s JVM half is discharged here, and as a join rather than as two halves.**
    `StatefulAgentTriggerTest.testLegacyJsonRejectionAbortsTheMopArm` loads the legacy full JSON
    through the compact loader, asserts the null, and feeds *that* null to `requireMopArm`. The two
    ends were already asserted separately and separately they leave the drill's actual question
    open, which is about the join: does the null a rejected artifact produces reach the code that
    aborts on it? It lives in the agent package because `requireMopArm` is package-private there.
  - **The deep-link scenario is only half assertable in this suite, and the test says which half.**
    The loader half — `deepLinkUri` verbatim from the wire, null when omitted, and no intent-filter
    `data` block existing to walk — is asserted on a synthetic and on the fixture. The dispatch half
    is `MonkeySourceApe`'s and will not class-load off-device (task 3.4a established that), so 5.3a
    owns what survives on the jar side and INV-DRV-07's assembly rule is the generator's.
  - **Scenarios of this delta that are deliberately not in this file**, so the count is not read as
    a gap: the four `Config.mopDataPath Flag` scenarios (defaults 500/300, custom override, flag
    absent/set) are plan-resolution facts and are pinned by `ScoringParamsDefaultsTest`,
    `RunSpecResolveTest` and `PresetsTest`; "explicit activation without MOP data aborts" is
    `FeatureDerivationTest.theMopFamilyDependsOnMop` plus resolution. None of them reaches a loader.
  - **Mutation-checked, seven mutations, all seven caught**: selection ignoring the flag, gateway
    condition 2 deleted, explicit-`none` entries dropped at ingest, `mopActsAugmented` recomputed
    against the selected set, `deepLinkUri` not decoded, the version gate accepting anything, and
    every widget arriving flagged. One mutation first matched its anchor three times and was
    re-anchored before it ran — a mutation that silently matches nothing is indistinguishable from
    a guard that failed to fire, so the script asserts its own target count before invoking maven.
- [x] 3.6 Run `/sdd-test-run MopDataTest`
  - The `sdd-*` skills are not in this session's registry (a standing condition of this stage, not a
    failure), so the skill's own action was run directly: `mvn -o test -Dtest=MopDataTest` → **76
    passed, 0 failures**, and the full `mvn -o test` → **1201 / 0 failures / 19 skipped**, which is
    session 12's 1182 plus this task's 19 new tests.

## 4. Equivalence gate for the cutover — fixture-scoped (owner decision 2026-08-05)

This group was written around a JVM gate over the pinned 345-app corpus. **That run does not happen.**
The owner's decision of 2026-08-05 is that the only execution the APE-RV side gets is `gh97-rearch-ab-gate`'s
campaign, and `gh97` cannot stand in for this group: it runs *after* both repositories are complete
(its task 6.1 gates on exactly that), and it measures coverage outcomes, not parse equivalence. A gate
that runs after the cutover licenses a merge, not a deletion.

What survives the rescope, and it is more than it looks: **the corpus half that was expensive has already
been paid.** `gh96` 7.1/7.2 built and ran the batch derivation over all 345 producer documents — the
generator survived every one with no crash and no refusal, and its totals (flagged widgets 3,733 → 4,965)
are recorded in `gh96`'s `specs/aperv/spec.md` and were independently reproduced by `gh97` group 3 while
computing the G3 displacement. So *the generator has met real-world variety*; what was never demonstrated
is the jar-side half — old parser and new parser agreeing on the same app — and that is what this group
now delivers, over a designed case set instead of a corpus.

**State the loss rather than let the green imply otherwise**: equality over cryptoapp plus synthetics is
evidence about the cases someone thought of. A real-app shape that neither the fixture nor a synthetic
anticipates is not covered here, and its only remaining net is `gh96`'s permanent Python suite (which is
what task 2.7 already designated as the permanent protection) and `gh97`'s campaign.

- [x] 4.1 Assemble the gate's input set: the cryptoapp pair already generated by 3.1 (`cryptoapp.apk.gh60-fresh.json` → `cryptoapp.apk.mop.json`), plus one synthetic **full-JSON** fragment per relocated rule the fixture cannot exercise, each derived through the real generator so the artifact side is never hand-written: flagged-widget-with-empty-`idName` (INV-DRV-02 activity marking + `droppedFlaggedNoId`), `$$ExternalSyntheticLambda` recovery **including the negative case** (INV-DRV-01), DIALOG re-keying with host promotion (INV-DRV-03), an A′ union that differs from the widget-derived set (INV-DRV-06 — the fixture's two sets are equal, so it is blind to this), and the three `deepLinkUri` null cases (INV-DRV-07). Record which rule each fragment exercises; that table is what task 4.4 archives
  - The `mopActivitiesAugmented` synthetic of task 3.3's note is already written and already discriminates the two sets — reuse it rather than authoring a second one.
  - **The reuse instruction could not be honoured literally, and the reason is worth stating**: task
    3.3's synthetic is a *compact artifact*, hand-authored, with no full-JSON preimage — and a gate
    member is a **pair**, because the comparison is old-parser-on-the-source against
    new-reader-on-the-artifact. There is nothing for the oracle to read. What was reused is its
    design: `gate-activity-union.sa.json` reproduces the same shape (a widget-derived `p.Widget`, an
    augmented-only member, a gateway whose one edge lands on it) as a full JSON, and its artifact is
    derived rather than written. Its A′ is in fact stronger — the three sources contribute
    `p.Widget`, `p.Component` (a component-flagged manifest activity) and `p.Reach` (a reachability
    class typed `activity`) **distinctly**, which the compact synthetic's two-element sets could not
    show. The compact synthetic keeps its own job in task 3.5 and was not touched.
  - Five pairs authored, `src/test/resources/gate-*.{sa,mop}.json`, each `.mop.json` produced by
    running `derive_mop_artifact.derive()` over the `.sa.json` beside it — a read-only host command
    in rv-android, no edit to that repository. The rule-by-rule table of what each one fired is in
    `gate-report.md`; every rule fired, so no member is a case authored for a rule the derivation
    then did not exercise.
  - The authoring script derived twice and produced byte-identical artifacts. That is not a
    restatement of INV-DRV-05 — it caught a real defect first: the widget ids came from Python's
    `hash()`, which is salted per process, so re-running the script would have produced different
    documents under the same names. Fixed to an MD5-derived id before anything was checked in.
- [x] 4.2 Write `MopArtifactEquivalenceTest` over the 4.1 set, running in the **ordinary `mvn test` suite** — no `-Dmop.corpusDir`, no external directory, no skip-when-unset branch (the property and its hard-fail-on-unset clause die with the corpus scope; a gate that can be silently skipped is the failure mode this group exists to avoid, and a fixture-scoped gate has nothing to skip). Old parser on the full JSON vs new parser on the derived artifact: assert identical widget flag maps (per-event + aggregate), metadata, both activity sets (flag off/on), gateway sets (flag off/on), WTG views, trigger/provider tuples, **per-activity `deepLinkUri` including null cases** (activities are excluded from the trigger-tuple pool, so nothing else compares them), `package`/`mainActivity`
  - **The WTG comparison MUST be set-based, not list-based**, and this is not a preference: the jar keeps
    exact-duplicate `(widget, target)` edges and the derivation removes them, so cryptoapp alone diverges
    17 vs 16 — on the very first case the gate reaches. A list comparison would report that as a
    derivation bug. The licence for it is the multiplicity audit `gh96` 7.3 asked for, now done: every WTG
    consumer is first-match (`MopScorer.scoreWtg:117`, `StatefulAgent.frontierBoost:1199`,
    `matchesQualifyingTarget:128`) or set-accumulating (`FrontierPass:58`, `MopFrontierPass:62`,
    `qualifyingMopTargets:115`), so multiplicity cannot reach a decision. Do **not** compare `stats` —
    they are counters under INV-DRV-04 and `wtgEdges` legitimately differs across the cut.
  - Both clauses honoured as written: edges are compared as `TreeSet`s of `widget -> target` per source
    activity, and `stats` is compared nowhere. The 17/16 divergence duly appeared and duly did not fail.
  - Six test methods, one per dimension, each looping the six members with the member named in every
    assertion message — so a red gate says both *what* diverged and *on which case*.
  - **The widget comparison needed a key universe, and building one exposed the gate's real risk.**
    Neither side exposes its widget map, and iterating only the artifact's keys would have made the
    gate blind in exactly the direction that matters (a widget the derivation *lost*). The universe
    is therefore the cross product of every activity name either side could key under — window
    names, their base forms, and the artifact's own keys — with every short id either side carries.
    It is an over-approximation, most pairs are null on both sides, and it is exact where it counts:
    every key either map holds is in it by construction.
  - **The emit rule is the one asymmetry, and it is checked rather than waived.** A widget that is
    neither flagged nor carrying metadata is not on the wire (INV-DRV-02), so the gate asserts of
    every omission that the oracle's widget was inert. That it is *safe* to omit is not assumed
    either: `MopScorer.score` returns 0 for "null or resolved-but-unflagged" and
    `ApePromptBuilder.widgetMetadata(null)` returns the empty string — both read, not recalled.
  - Metadata compares null and `""` as equal. The producer writes `""` for an absent hint and the
    generator omits the key; distinguishing them would fail the gate on a difference INV-MOP-10
    makes unobservable, since every consumer emits a field only when non-null **and** non-empty.
  - **Tuples are fixed without being built.** `buildTriggerTuples` reads exactly `reachesTarget`,
    `intentFilters[].actions` and `targetMethods.isEmpty()` over receivers-then-services, and
    `buildProviderTuples` reads `reachesTarget` and `authorities` — read off those two methods, not
    assumed. Comparing that whole input in list order fixes their output, which is what lets the
    gate live in `ape.utils` where the package-private flag seams are, instead of in `ape.agent`
    where they are not.
  - **One visibility-only change to the oracle**: `precomputeMopOptionsMenus` went from `private` to
    package-visible, for the same reason `augmentActivitiesFromSources` already is — the load-time
    caller can only pass the set `Config.mopActivitySourceComponents` names, and the gate has to
    drive both. No value it computes changed; the alternative was transcribing the rule into the
    test, which would have compared the artifact against my copy of the oracle rather than the
    oracle. It is deleted with the parser in 5.1.
- [x] 4.3 Run the gate; investigate and fix every divergence **in the generator** (the old parser is the oracle — never adjust the oracle) until green on every member of the 4.1 set. The rule that a case exercised by nothing fails the gate is unchanged in force and changed in subject: it no longer asks how many corpus apps trigger a rule, it asks that every relocated rule have a member of the input set that triggers it — a synthetic that derives to an artifact where the rule did not fire is not coverage, and must be corrected rather than counted
  - **Green on the first run: no divergence, so no generator fix.** Suite 1207 / 0 / 19.
  - A gate that passes immediately has proved nothing until it has been made to fail, so **ten
    mutations** were applied to the derived artifacts — one per relocated rule plus the obvious
    scalars — and each was caught by the intended test. The two that matter most are the ones a
    weaker gate would have missed: the INV-DRV-01 *negative* case (flagging the wrapper whose
    enclosing class has no reaching lambda) and INV-DRV-02's ordering (unmarking the activity whose
    only flagged widget was dropped for having no id). The full table is in `gate-report.md`.
  - The "every rule needs a firing member" clause is discharged twice over: once by the derivation
    output (each fragment's artifact shows its rule's effect) and once by the mutations (each rule
    has a perturbation the gate detects, which is only possible if the comparison reaches it).
- [x] 4.4 Record the gate result in the change directory for the archive trail: the input-set table of 4.1 (fragment → rule → what fired), the pass summary, and — explicitly, so the archive does not read as if a corpus gate had run — the two breadth facts this group now leans on instead: `gh96`'s recorded 345-app derivation and its totals, and the deferral of real-application variety to `gh97`'s campaign
  - `gate-report.md` in this change directory. It is written for the reader who arrives after group 5
    has deleted the oracle, the gate and the fragments: what was compared, what each member fired,
    what the mutations proved, and — in its own section — what the green does **not** establish. The
    two breadth facts are named with their numbers (`gh96`'s 345 documents, flagged widgets
    3,733 → 4,965, reproduced by `gh97` group 3) and so is the gap neither of them closes: a
    jar-side comparison on a real application nobody wrote a synthetic for, whose only standing net
    is `gh96`'s permanent Python suite.

## 5. Jar cutover (BREAKING — lands only with Group 6)

- [x] 5.1 Delete the full-JSON parse machinery from `MopData.java`: `parseReachability`/`parseWindows`/`parseWidget`/`parseListener`/`deriveWidgetMopFlags`/`parseTransitions`/`parseComponents`/`parseIntentFilters`/`parseDataSpec`/`rekeyDialogsToHost`/`augmentActivitiesFromSources`/`precomputeMopOptionsMenus`/`computeHandlerJoinDiagnostics`, the `Window`/`Listener`/`Transition`/`TransitionEvent`/`ReachabilityClass`/`ReachabilityMethod` POJOs, and the test-only getters (`getReachability`, `getWindows`, `getWindow`, `getTransitions`, `isWidgetlessSubstrate`)
  - **`MopData.java` 1,703 → 800 lines** (53 % gone; the design's target was "≤ ~450 from 1212", a
    figure written before stages 3–6 added the compact reader, the gateway recompute and the
    provenance record to the same file — the parser share it was about is what left).
    Four contiguous cuts, located by their banner comments and each asserted to match exactly once
    before it ran: the `Loading` section (197 lines), passes 1–2 (218), the A′ augmentation plus the
    handler-join diagnostics (81), and passes 3–4 with the OPTIONSMENU precompute and the dialog
    re-key (267). `orInto` and `normalizeEventType` sat inside those ranges and are survivors, so
    the cuts are bounded on them rather than on the section ends.
  - Fields, constructor and both `forTest` overloads lost `complete`, `reachability`, `windows`,
    `windowsById` and `transitions`; the five test-only getters and `isComplete` went with them.
  - **Helpers checked one at a time against `src/main` and `src/test` before deletion**, as the task
    demanded: `readFile`, `optStringOrNull`, `stringList`, `normalizeEventType`, `orInto` and
    `reject` survive with live callers; `optBooleanOrNull`, `baseActivity`, `mopRank`,
    `countWidgets`, `countWtgEdges`, `countFlagged`, `SYNTH_LAMBDA` and
    `syntheticLambdaEnclosingClass` had none left and are gone. `syntheticLambdaEnclosingClass` was
    the one the handoff flagged as possibly having a test — it did not.
  - **`loadCompact` → `load`, both overloads.** With one loader the qualifier is lineage (P4), the
    spec's requirement is written about `MopData.load`, and `StatefulAgent:181` already called
    `load` — so the production call site did not move. The test-side helpers `loadCompactFixture`
    and `onlyCompactLoadRecord` were renamed for the same reason.
  - Class javadoc rewritten (task 5.4's D7 half): the `Target`→`MOP` boundary is described as
    living in the generator, and the `Parser` section is now a `Reader, not parser` section that
    names the one thing still derived on device — the gateway set, because D3 will not let it be
    shipped.
  - One dangling reference the deletion created and P3 requires closing: `Config`'s
    `llmPercentageNoSubstrate` comment named `MopData.isWidgetlessSubstrate`. The flag is a live
    plan key with no consumer, so it stays; the comment now describes the substrate concept and
    says plainly that the predicate a consumer would need no longer exists, because it read the
    parsed `windows[]` that the artifact does not carry.
  - Incidental: the `TAG` constant went too. It was already dead at `3b1b560b` (one occurrence, its
    own declaration) — not something this cut created, just something it stopped hiding.
- [x] 5.2 Delete the memory-safety machinery (design D5, V19): `PARSE_FOOTPRINT_FACTOR`, `PARSE_BUDGET_BYTES`, the budget-parameter test seam, `reason=too-large`, and the outer `catch (OutOfMemoryError)`; grep-verify zero `OutOfMemoryError` catches remain in the repo
  - Deleted with the `Loading` section of 5.1 — they were one contiguous block, and the budget
    guard was the first statement inside the `try` the OOM catch closed.
  - **Grep-verified, and the result needs its qualifier stated.** Three `OutOfMemoryError`
    occurrences remain in `src/main` and **none is a catch**: `MopData`'s is the sentence in the
    class javadoc explaining why this reader has no containment, and `ScreenshotCapture:60` /
    `Model:63` are pre-existing comments that likewise explain why *they* do not catch it. The
    invariant the task wanted is zero handlers, which holds; reporting "zero occurrences" would
    have been false and reporting "three occurrences" would have been alarming, so the check was
    run on the construct rather than the token.
  - `readFile`'s javadoc claimed "the `load` budget guard rejects far smaller files first". With no
    guard that sentence became a reference to something absent; rewritten to say the
    `Integer.MAX_VALUE` check is now unbacked, which is the true state.
- [x] 5.3 Delete `WtgTransition.widgetClass`, the `DataSpec`/`readPermission`/`writePermission`/**`exported`** surfaces from `ComponentInfo`, and `Widget.id`/`text`/`type`/`listeners`. `ComponentInfo` gains `deepLinkUri` (read from the wire) and `MopLauncherStage.buildDeepLinkUri` is deleted with the structure it walked — its call site at `:117` passes `candidate.deepLinkUri` instead. Dropping `exported` changes every `ComponentInfo` subclass constructor and the `ActivityFrontierTest` fixtures that pass it positionally. This is the one place outside `MopData`/`ComponentInfo` that changes; everything else compiles clean because the query API is unchanged by construction
  - **The task's last sentence is wrong, and the number is worth recording rather than glossing.**
    "Everything else compiles clean" held for the *query* API and not for the *construction* API:
    dropping a positional `boolean exported` from four subclass constructors broke **nine** test
    files, not the two the task named — `StatefulAgentTriggerTest` (13 sites), `ComponentInfoTest`,
    `ActivityFrontierTest`, `MopLauncherStageTest`, `ComponentTriggerStageTest`, `PipelineFixture`,
    and, through `WtgTransition`, `MopFrontierPassTest`, `ScoringPassGateTest` and
    `ScoringPipelineTest`. A positional boolean is invisible to the caller audit that cleared this
    field of *readers*: nothing read `exported`, and 19 sites still passed it.
  - The 19 argument drops were done by a script that splits arguments at paren depth 1 (quote- and
    nesting-aware) and removes the third **only when it is a boolean literal**, so a call passing a
    variable was left for a human. That is how `ActivityFrontierTest`'s `activity(...)` helper was
    caught rather than silently mangled — its third argument was the parameter `exported`.
  - `ComponentInfo` also lost `DataSpec`, `IntentFilter.data` and the providers'
    `readPermission`/`writePermission`. Its class javadoc now argues each absence: the `exported`
    one is the load-bearing case, because "eligibility SHALL NOT include an `exported` test" turns
    from a rule someone must remember into a property of the type.
  - `MopLauncherStage`'s eligibility javadoc said "Exported status is NOT consulted"; it now says it
    cannot be, and why the frontier would shrink if it were.
  - **`testNonExportedCensusMemberIsLaunched` lost its subject and was rewritten, not deleted.** It
    varied `exported` and checked the result did not move — impossible with no field to vary. It is
    now `testNoExportedFieldExistsForTheWalkToConsult`, which asserts over `ComponentInfo`'s public
    fields that no `exported` exists, and keeps the launch assertion. The absence claim is strictly
    stronger: the old test showed *this* walk ignoring the flag and left a future walk free to
    consult it.
- [x] 5.3a Migrate the deep-link assertions of `ActivityFrontierTest` ("Lever B", 6 assertions over `buildDeepLinkUri`) to the Python generator suite (task 2.7) — they pin INV-DRV-07 now, on the side that computes it. Assert what remains on the jar side instead: a `ComponentInfo` carrying `deepLinkUri` dispatches `ACTION_VIEW`, one carrying null dispatches the explicit component. **Deleting these assertions instead of migrating them is the wrong fix** — they are the only thing standing between a schema omission and a silently degraded activity frontier
  - **Five of the six assertions already had their permanent home; the sixth does not, and that is
    stated rather than quietly counted as migrated.** `gh96`'s `test_derive_mop_artifact.py` carries
    `test_deep_link_from_first_action_view` (scheme+host+path, and the first-`ACTION_VIEW` selection
    over a preceding `MAIN` filter and a following second `VIEW`), `_absent_without_scheme`,
    `_absent_without_action_view`, `_absent_without_filters` and `_empty_host_and_path` (`myapp://`).
    The one with no counterpart is **scheme + host, no path** (`testDeepLinkSchemeHost`,
    `https://x.com`) — the case that shows `path` defaults independently of `host`. Writing it is an
    rv-android edit to a file outside the group-6 authorization of §0, so it is **owed, not done**:
    it belongs to `gh96` task 2.7 and needs the owner's word before it lands. Nothing regresses
    meanwhile — the two flanking cases pin the assembly — but the box is not honest without this.
  - What stays on the jar side is the pair the task names, in `ActivityFrontierTest`:
    `testTriggerActionCarriesTheWireDeepLinkOrNull` (the candidate's `deepLinkUri` reaches
    `ActivityTriggerAction` **verbatim**, and null stays null, which is what `MonkeySourceApe` reads
    as "explicit component" — INV-CT-13) and `testOnlyActivitiesCanCarryADeepLink`. Verbatim is the
    load-bearing word: any rebuilding, normalizing or defaulting here would be a second
    implementation of INV-DRV-07, free to diverge from the generator's the first time either moved.
- [x] 5.4 Delete `MopArtifactEquivalenceTest`, the synthetic full-JSON fragments of task 4.1 and the old-format test resources (`cryptoapp.apk.gh60*.json` fixtures move to test-only history; the compact fixture is now the only loader fixture); update the D7 vocabulary-boundary javadoc on `MopData` (boundary now: generator host-side). The synthetics go with the oracle for the same reason it does — they are full-JSON documents, and a full-JSON document in the test tree after this group is an input no shipped code path can read. Their *rules* do not go with them: each one's permanent home is the named test `gh96` task 2.7 owns, which is where the substitution table of 4.4 points
  - Deleted: `MopArtifactEquivalenceTest`, the ten `gate-*.{sa,mop}.json` files, `MopDataLoadTest`,
    `MopDataLambdaReachTest`, `MopDataActivityCountersTest`, and `cryptoapp.apk.gh60.json`
    (a 70 KB resource nothing loaded — `GoldenFileTest` only names it in a header string).
    `gate-report.md` stays, as its own task requires.
  - **`cryptoapp.apk.gh60-fresh.json` is NOT deleted, and the task's instruction to move the
    full-JSON fixtures "to test-only history" no longer holds for it.** Task 3.5 gave it a second
    job: it is the document `testLegacyJsonRejectionAbortsTheMopArm` and
    `testLegacyFullJsonIsRejectedAsVersionMismatch` feed to the loader to prove the rejection, and
    `testCompactLoadRecordCarriesProvenanceAndTheComponentCount` digests it to check the provenance
    chain end to end. Deleting it would delete the skew drill of task 7.3(a) — the one thing that
    shows a stale push failing loud. Its constant now carries a comment saying why it is on the
    classpath, so the next reader does not remove it as leftover.
  - **`MopDataTest` was cut by what each test drives, not by the line the 3.5 block starts at.**
    Session 13's plan was "delete everything above the block"; that would have taken with it eight
    `forTest`-based query-layer tests (lines 87–256) that never touched the parser and test an API
    the cutover does not change, and two compact tests session 12 had left above the line
    (`testCompactLoadRecordCarriesProvenanceAndTheComponentCount` and its `sha256Hex` helper). Six
    anchored cuts instead of one: **1,582 → 840 lines**, with the query-layer block kept.
  - `testEventTypeNormalizationSnakeCamelEqual` kept its unit half and had its end-to-end half
    **re-anchored on the wire** rather than deleted. INV-MOP-08 did not move host-side — the
    generator writes whatever token the producer used, so a snake_case wire key must still answer a
    camelCase query. The new fixture uses `item_selected`, a key no other test in the file uses and
    the widget's only entry, so a loader that skipped normalization on ingest leaves the map holding
    an unreachable key and the query falls back to a false aggregate.
  - `testWtgTransitionFields` became `testWtgTransitionCarriesOnlyTheConsumedPair`: it asserts the
    field count is 2, so a re-added `widgetClass` fails rather than passing unnoticed.
  - **`MopScorerTest`'s two synthetic fixtures were rewritten as artifacts, not deleted.** They test
    the *scorer*, and the scorer still needs a gateway and a per-event flag map to score; what
    changed is that the fixture now states them instead of deriving them from listeners and a call
    graph. Its third old-parser test (the `#`-suffixed WTG target reducing to base) was deleted
    outright: that reduction is the generator's now, and its permanent test is `gh96`'s
    `test_wtg_click_only_deduped_base_keyed`.
  - `ComponentInfoTest` was rewritten against the wire. It gained the assertion the compact format
    made possible and the old one could not have: `hasTargetMethods` decodes to a list of the right
    *emptiness* across true/false/absent, which is exactly what `buildTriggerTuples` reads.
  - **`OracleScaffold.MOP_FIXTURE` had to move to the compact artifact, and this is the trap the
    cutover set.** The `mop` and `llm_mop` presets load it through `MopData.load`; left pointing at
    the full JSON they would have taken a null and every MOP golden would have silently become an
    APERV golden — a passing suite asserting nothing. The three goldens then failed on their
    **header's `fixture` field only**, every decision record reproducing byte-for-byte across the
    cut, which is the strongest behavioural evidence this stage has that the projection is
    unchanged: three seeded end-to-end decision traces, produced by the old parser and reproduced by
    the new reader. Headers and `goldens/README.md` updated; no decision line touched.
- [x] 5.5 Update `CLAUDE.md` (MopData naming note, `mopDataPath` artifact description) and run `/sdd-doc-code src/main/java/com/android/commands/monkey/ape/utils/MopData.java`
  - The `sdd-*` skills are not in this session's registry (the standing condition of this stage), so
    the skill's action was run directly as a documentation audit of the file: read every javadoc and
    comment against the code it sits on and correct what the cutover falsified.
  - **The audit found a false clause, which is the reason to run it rather than assume the 5.1
    rewrite left the file clean.** The class javadoc claimed "no `*Target` key reaches it" and that
    the class "translates nothing". Both are wrong on the same two names: the wire carries
    `hasTargetMethods` — the generator's own compaction of the signature list, read at `:535` — and
    `parseCompactComponents` decodes `reachesMop` **into a field named `reachesTarget`**, which is a
    rename, in the opposite direction to D7's. Corrected to name the two survivors and to state what
    genuinely left: `reachability[]`, `directlyReachesTarget` and the listener × handler
    cross-reference. `ComponentInfo` had this right already (its two field javadocs name the wire
    keys), so the defect was confined to the class that asserts the boundary.
  - Second finding, smaller: the `forTest` section header said "(package-private)" and the methods
    are `public`. They have to be — their callers are in `ape.agent` and `ape.agent.scoring`, not in
    `ape.utils`. Header corrected and the factory given a javadoc saying what it is for (query-layer
    tests that state the structures instead of authoring an artifact that derives to them) and the
    one thing it does not build (the gateway set, which only `load` recomputes).
  - `CLAUDE.md`, five edits. The naming note now says the D7 boundary **left the jar** and names the
    two `Target` survivors, matching the corrected javadoc rather than pointing at it. `mopDataPath`
    describes the compact artifact at `/data/local/tmp/mop-artifact.json` and says the full JSON is
    never pushed and is rejected if it is. The `mopStrictPackageMatch` line compares against the
    artifact, not "the JSON". The test-APK table gained the `.mop.json` row and marks the `.json` as
    the generator's input; the `Source:` block says the artifact is **derived, not copied**, names
    `derive_mop_artifact.py`, and warns that hand-editing it breaks the `source.digest` chain.
  - **The stale test count was 937 against a measured 1131** — 194 low, exactly the drift the
    handoff predicted. Its skip breakdown ("13 `@Ignore` … 6 `Assume` in `SglangLiveTest`") was
    re-derived from the surefire XMLs rather than trusted: 5+4+3+1 `@Ignore` across
    `ImageProcessorIntegrationTest`/`ImageProcessorTest`/`ApePinchOrZoomEventTest`/
    `GUITreeBuilderPasswordTest` = 13, plus `SglangLiveTest`'s 6 = 19. Correct as written, so only
    the total moved. Note for whoever reads those XMLs next: `target/surefire-reports/` is not
    cleaned by `mvn test` and still holds reports for classes group 5 deleted, so summing it gives
    1237 — the maven summary line is the number, the directory is not.
- [x] 5.6 Run `/sdd-test-run ape` (full `mvn test` — 145-test suite adjusted for the removed seams)
  - `mvn -o test` → **1131 tests, 0 failures, 19 skipped** (~8.4 s). Run twice: once at `ac1d1484`
    before touching anything, to confirm the tree was as the handoff left it, and again after 5.5's
    documentation edits. Same number both times, which is what a comment-only change owes.
  - The task's "145-test suite" is a figure from the change's authoring and was never true of this
    tree; it is not adjusted-for-the-removed-seams, it is stale by an order of magnitude.
  - **The drop from 1207 is stated as measured on both sides, not derived by subtraction.** 1207 was
    measured at task 4.3 with the equivalence gate in the tree; 1131 is measured here. The 76
    difference is the count of tests group 5 deleted with their subject (the old parser's suites and
    the one-shot gate) — a number that happens to reconcile, not the evidence for either figure.
  - This is not the last movement: `rearch-05` deletes `RunSpecCompatTest` (13 tests, 301 lines), so
    the baseline a later session should expect is 1118, and a session that finds 1131 after
    `rearch-05` lands has a merge problem rather than a lucky suite.

## 6. Python push switch (BREAKING — lands only with Group 5) — DELIVERED BY `gh96`

Like group 2, this group was written as if the switch were unbuilt, and like group 2 it is built.
`gh96-mop-artifact-derivation` (rv-android, branch `rearch-counterparts`) delivered it in its group 5,
and the boxes below are ticked against a **rule-by-rule reading of `tool.py` and its tests**, with the
delivering `gh96` task named in each note — never against `gh96`'s own checkboxes. Not one line of
rv-android was edited for this group; the §0 authorization to do so was not needed and was not used.

Verified read-only 2026-08-05 at rv-android `a683591e`.

- [x] 6.1 `tool.py`: add `_derive_mop_artifact(task)` (digest-checked cache at `<apk_name>.mop.json`, atomic write, `DerivationError` → `RVToolExecutionError`); delete `_compact_static_analysis_json` and its enrichment helper and their fallback-to-source push
  - `gh96` 5.1/5.2. `_derive_mop_artifact():675` reads the source **as bytes** and digests those bytes
    (`digest_of` = `sha256:` + hex, `derive_mop_artifact.py:160`) rather than the re-encoded parse,
    which would not round-trip; the cache hit is `_cached_artifact_digest(...) == digest` and nothing
    else — no mtime, no size. `_cached_artifact_digest():761` treats a missing, unreadable or corrupt
    artifact as a **miss returning None**, so a scratch file cannot fail a run.
  - The atomic write is `tempfile.mkstemp(dir=task.results_dir)` + `os.replace`, i.e. a rename within
    the same directory, which is the only form that is actually atomic. The `finally` unlinks the temp
    on every path where `os.replace` did not run, and sets `tmp_path = None` immediately after the
    rename so the successful path does not unlink the artifact it just created.
  - `MemoryError` is in the caught tuple alongside `DerivationError`/`OSError`/`json.JSONDecodeError`.
    That is not over-catching: the document is `json.loads`-ed whole before deriving, and a bare
    `MemoryError` would reach the supervisor without the path or tool context the caller acts on.
  - **The deletion is verified as an absence, not assumed from the task text** (learning: grep cannot
    be trusted for negative results — this was a `python3` walk of `modules/`, all `.py` and `.md`).
    `_compact_static_analysis_json`, `_index_reaches_target` and `_enrich_listener_reach`: **zero
    occurrences each**. `handlerReachesTarget`/`handlerDirectlyReachesTarget` occur three times each
    and every one is legitimate — `derive_mop_artifact.py:501-502` **reads** them as the producer's
    supplied values (the producer-precedence branch INV-DRV-01 requires), plus one test and one
    CLAUDE.md paragraph. Nothing writes them any more, which is what retiring INV-APV-32 means.
- [x] 6.2 `tool.py` step 1c: replace the warn-and-continue branch with the raise (INV-APERV-05); push to `/data/local/tmp/mop-artifact.json`; update the `ape.mopDataPath` value in `_push_properties`
  - `gh96` 5.3/5.4. Step 1c (`:1171-1200`) raises `RVToolExecutionError("MOP arm cannot arm: no static
    analysis JSON at <path>")` when `_find_static_analysis_file` returns falsy, and the expected path
    is **reconstructed for the message** from `results_dir`/`apk_name` with placeholders — so the
    error names a path even for the standalone task shape that has neither, which is the shape the
    finder returns None for without ever building one.
  - INV-APERV-05's real content is the *absence of a third branch*, and the code has none: the `if`
    either raises or falls through to `_derive_mop_artifact` + push + `mop_json_pushed = True`. There
    is no `except`, no `continue`, no default. `mop_json_pushed` is initialized `False` immediately
    above the block and is the only thing that gates the property line, so "pushed" and "declared"
    cannot disagree.
  - `_push_properties():807` writes `f"ape.mopDataPath={DEVICE_ARTIFACT_PATH}"` — **the same constant
    the push destination uses** (`derive_mop_artifact.py:70` = `/data/local/tmp/mop-artifact.json`),
    not a second literal. That is the difference between a path that cannot drift and one that is
    merely correct today.
- [x] 6.3 Update `test_aperv_tool.py`: absent-JSON raises, derivation-failure raises, cache hit/stale behavior, device path and properties line, non-MOP arms untouched, no-full-JSON-push assertion (INV-APERV-06)
  - `gh96` 5.5/5.6/6.1. Every item on this list has a test, and each was read for **what it asserts**
    rather than counted by its name (learning: when a gate is delegated, check what the receiving gate
    actually asserts). `TestDeriveMopArtifact` (`:1353`) covers the unit half — derive/write, cache
    hit *enforced by monkeypatching `derive` into an `AssertionError`* so a silent re-derivation
    fails, stale regeneration checked against a recomputed digest, corrupt-cache-is-a-miss, and
    `test_failed_derivation_leaves_no_file` asserting `os.listdir` is byte-for-byte unchanged, which
    catches an orphaned temp and not merely the absent artifact. `TestExecuteMopArtifactFlow`
    (`:1658`) covers the flow half, with `_push_file_to_device` stubbed to snapshot content at push
    time because the temp is unlinked immediately after.
  - **The two things §3 of the handoff said to check rather than assume both exist, and both hold.**
    *The INV-APERV-06 assertion* is `test_full_json_never_pushed:1777`, and it is stronger than its
    name: one push to the artifact path, its local path is the `.mop.json`, its content parses to
    `formatVersion == 1`, **no push anywhere carries the source path**, and **no push targets the old
    `/data/local/tmp/static_analysis.json`** — so it closes both the "wrong file" and the "right file,
    old destination" directions instead of only the first. *The derivation-failure test* is
    `test_mop_arm_derivation_error_raises:1816`, driving a `complete: False` document through the
    whole flow and asserting zero artifact pushes; `test_mop_arm_without_json_raises:1805` adds that
    the **properties file is never pushed either**, which is what "before launching the jar" means
    operationally. Nothing here needed writing, so the §0 authorization stays unused.
  - Numbering, since the two repositories name the same clauses differently and a future reader will
    hit this: `INV-APERV-05/06/07` of this delta are `INV-APV-45/46/47` in `gh96`'s. Same text, and
    the test comments cite the rv-android numbers.
  - Beyond the task's list, `TestMopArtifactAudit:1858` (`gh96` 6.1) enforces INV-ANA-53 — no module
    outside `aperv-tool` may read a `*.mop.json` — which is the executable form of design D9's grep
    audit that task 1.3 performed by hand. Its suffix constant is assembled from two pieces so the
    audit does not match its own source line.
- [x] 6.4 Run `/rv-test-run aperv-tool` (pytest for the module — rv-android's skills are `rv-*`, not `sdd-*`)
  - Skill run directly (the `rv-*` skills are in this session's registry but the CI contract is the
    thing that matters): `.venv/bin/pytest modules/aperv-tool/tests/ --import-mode=importlib
    -o "addopts="` → **299 passed, 32 skipped**, 18.3 s. Split: `test_aperv_tool.py` 130 passed,
    `test_derive_mop_artifact.py` 67 passed, the rest from the trace/coverage/migration files.
  - **The handoff's 282 passed / 30 skipped is stale, and not because of this group** — nothing here
    added a test. `git log` over `tests/` shows the movement belongs to the owner's concurrent `gh94`
    (`a683591e`, +301 lines in `test_clock_logcat_join.py`) and to `gh95`'s arm retirement. Recording
    the source of the drift so the next session does not read it as a group-6 side effect.
- [x] 6.5 **Cross-repo OpenSpec instrument**: rv-android's `openspec/specs/aperv/spec.md` MUST NOT be edited by hand (that repo's CLAUDE.md forbids it). Counterpart opened 2026-08-03: **`rvsec#96`**, change `gh96-mop-artifact-derivation`, carrying this stage's delta — the push path switches from the full JSON to the derived artifact, `_compact_static_analysis_json` and its INV-APV-20..25/31/32 requirements retire, and absent-input becomes a raised error — and let that repo's archive/sync apply it
  - The instrument is in place and was checked at both ends rather than taken on the change's word.
    `gh96`'s delta (`openspec/changes/gh96-mop-artifact-derivation/specs/aperv/spec.md`) carries
    `## ADDED` (INV-APV-45/46/47 + the derivation-and-caching requirement), `## MODIFIED` (the execute
    flow, step 4 = this delta's step 5) and `## REMOVED` (INV-APV-20..25/31 retired with the mechanism
    they constrain; **INV-APV-32 retired explicitly as a behaviour change**, not as dead weight —
    which is the D10 honesty that `gh96` task 6.4 came back to this side to enforce).
  - The prohibition holds: `openspec/specs/aperv/spec.md` carries **zero** occurrences of
    INV-APV-45/46/47 and still carries all 16 occurrences of the retiring ones. The main spec has not
    been hand-edited; the delta travels through that repo's archive/sync, which is the whole point of
    routing it this way.

## 7. Coordinated deploy (design D8)

- [ ] 7.1 Land the ape commit (Groups 3+5) and the rv-android commit (Groups 2+6) together; `mvn install -Drvsec_home=…` refreshes the module-local `ape-rv.jar`
- [ ] 7.2 **Delegated to `gh97-rearch-ab-gate` tasks 6.2–6.5** (owner decision 2026-08-05: the APE-RV side executes once, there). That change builds the jar from this worktree, pushes the rv-android commits *before* the image build (its 6.3 — the image's stage-4 layer clones `PAMunb/rvsec` at build time, so unpushed work is silently absent), builds `phtcosta/rvandroid:0.9.3-rearch` as a **new tag** rather than rebuilding `0.9.3` in place, and records both image IDs. Nothing is owed here beyond confirming, when this change closes, that `gh97` 6.1's precondition ("stages `rearch-03`…`rearch-07` complete") is truthfully satisfiable — that gate is not advisory and this is the change it gates on
- [x] 7.3 Skew drill — **split by what can actually observe each half**, since the bench run does not happen. (a) *Old full JSON meets the new jar* is a JVM fact and is asserted at JVM level: `status=rejected reason=version-mismatch` from `MopData.load` (task 3.5's rejection scenario) plus `StatefulAgent` aborting with `StopTestingException` on a null return (INV-MOP-22). (b) *MOP arm with the full JSON absent* is a host fact and is asserted in pytest: `RVToolExecutionError` raised before any device interaction (task 6.3's absent-JSON case). What neither can attest is that the **deployed** pair behaves this way, and that is the half `gh97`'s pre-flight now carries (`gh97` task 7.2a) — a `build.sha`/`MOP_DATA` mismatch there is the gh71 failure mode caught before the campaign spends 24 hours. Record here that the drill was discharged in three places rather than one bench session, because "both loud, no silent SATA run" is the property, and it is now proven by three different observers rather than one
  - **Discharged in three places, each read for what it asserts rather than counted by its name.**
    (a) The JVM half is a *join*, not two facts: `MopDataTest.testLegacyFullJsonIsRejectedAsVersion`
    `Mismatch:623` pins the loader end (null, `status=rejected`, `reason=version-mismatch`, and no
    `sourceDigest` on the record — a load that never reached an artifact has no digest), and
    `StatefulAgentTriggerTest.testLegacyJsonRejectionAbortsTheMopArm:201` feeds the legacy full JSON
    to `MopData.load` and hands **that** null to `requireMopArm`, expecting `StopTestingException`.
    The second is the one that matters: separately asserted, the two ends leave the drill's actual
    question — does the null a rejected artifact produces reach the code that aborts on it? — open.
    (b) The host half is `test_mop_arm_without_json_raises:1805` in rv-android, which asserts the
    raise *and* that neither the artifact nor the properties file was ever pushed, which is what
    "before any device interaction" means operationally.
    (c) The deployed half is `gh97` task 7.2a, and it was read rather than trusted: it asserts
    `MOP_DATA` present with `status=loaded`, `formatVersion=1` and a non-empty `sourceDigest` on each
    MOP arm, and — the clause that makes it a skew drill rather than a liveness check — **no**
    `status=loaded` record and no boost on the control arm `mop_off_llm_off`, which is where a MOP
    artifact reaching a non-MOP arm would surface. That task is open (`gh97` is mid-flight); this box
    records that the drill is discharged in the two places that can be discharged without a device,
    and names the third with what it will assert.
  - The property is "both loud, no silent SATA run", and the three observers do not overlap: one
    reads the jar's join, one reads the host's refusal to arm, one reads a deployed pair's trace.
    None of them could substitute for another, which is why splitting the drill was not a downgrade.

## 8. Verification

- [ ] 8.1 **Delegated to `gh97-rearch-ab-gate` tasks 7.1–7.4**, whose smoke is the only device execution this stage gets (owner decision 2026-08-05). The delegation is only honest if that smoke *checks what this task was going to check*, so it is not a pointer but a dependency: `gh97` 7.2a — added by this decision — asserts `MOP_DATA status=loaded` with `formatVersion=1` and a non-empty `sourceDigest` on every MOP arm, and a MOP boost actually firing. Note what changes and what does not: the application is no longer cryptoapp but the smoke subset of `subset40`, which is **better** evidence (real applications, three arms) and **worse** in one specific way — the named-widget assertions (`btn_cipher_encrypt`, `buttonGenerateHash`, the MainActivity menu gateway) have no subject there. Those keep their subject on the fixture instead, in task 3.5, where they are assertions about the loader rather than about a run
- [x] 8.2 Artifact-size and load-time deltas — **rescoped to what is measurable without a paired device run**. The size half is already a measured host fact and needs no device: the cryptoapp artifact is 4,126 bytes against a 69,977-byte source (5.9 %, task 3.1). ~~and `gh96`'s 345-app derivation is the population version of the same claim (task 7.5 there)~~ — **false, corrected 2026-08-05**: `gh96` 7.5 *withdrew* the corpus size measurement when 7.4 was rescoped, and records the same single-application number instead. The load-time half is **not measurable from any artifact in either repository**, and the reason this task gave for that was also wrong (see the note). Report the size reduction as the measured claim, and record the load-time delta as **not measured**, with the corrected reason — do not leave the box implying a measurement that no available artifact can support
  - **Size half — re-measured here rather than copied.** `stat` over the checked-in pair:
    `cryptoapp.apk.gh60-fresh.json` 69,977 bytes → `cryptoapp.apk.mop.json` **4,126 bytes = 5.90 %**.
    The `test-apks/` pair derives from a byte-identical source to **4,115 bytes (5.88 %)**; the 11-byte
    difference is `source.file` carrying a different name, which is the determinism property working
    rather than a discrepancy. This is a **single-application measurement** and is recorded as one.
  - **The population claim this task leaned on does not exist, in either repository.** `gh96` 7.5 is
    still open and its own text withdraws the corpus byte measurement with 7.4's rescope, offering
    this same cryptoapp number as the replacement. A search of every `gh96` artifact for a recorded
    byte total returns nothing but qualitative statements ("kilobytes each, next to a file of
    megabytes"). What the 345-app run *did* record is per-rule exercise counts and the flagged-widget
    totals (3,733 → 4,965) — evidence about variety and semantics, not about size. So the design's
    order-of-magnitude claim about where the bytes sit (57.7 % call graph) stays an order-of-magnitude
    claim, exactly as design D9's first standing caveat already insists.
  - **The load-time reason in the task text was false, and the true one is stronger.** The claim was
    that leg A has no `[APE-MOP-DATA]` line to difference against. Measured first-hand over the
    frozen E3 leg-A corpus (`experimento-e3-decisiva/results/`, 2.47 GB scanned): the **logcats**
    carry zero — but they carry zero `[APE...]` lines of *any* kind, because APE's output goes to the
    `.trace`, not to logcat. In the traces the record is present in **360 of 360** files, e.g.
    `status=loaded package=… windows=9 widgets=0 flagged=0 … mopActivities=6 mopActsAugmented=6`.
    A pre-change load record therefore exists in abundance; the previous statement measured the wrong
    artifact class and drew a conclusion the right one contradicts.
  - **Why the delta is still not measurable, for the real reason**: *neither format has ever carried
    a duration*. The legacy line's fields are the counters shown above, and the new record's field
    set is fixed by `EventSink.mopData` (`:210-213`) — status, reason, formatVersion, sourceDigest,
    package, and eleven counts. No elapsed time on either side of the cut. So this is not a missing
    comparator that a device run would supply: no run, past or future, times the load until someone
    adds a field to the record first. Recorded as **not measured**, with that as the reason.
  - Consequence worth carrying rather than burying: the load-time claim of the proposal ("a per-run
    parse cost on the emulator") remains **argued, not measured** — it rests on what was removed
    (a whole-file DOM parse of a 1.5–48 MB call-graph document) and on the size ratio above, not on
    two timings. `gh97`'s campaign will not close it either, since its traces carry the same
    duration-free record.
- [ ] 8.3 Run `/rv-qa-lint-fix aperv-tool` (rv-android) and `/sdd-qa-lint-fix ape` (this repo)
- [ ] 8.4 Run `/sdd-verify ape`
- [ ] 8.5 Invoke `/sdd-code-reviewer` via Skill tool
- [ ] 8.6 Run `/sdd-docs-sync ape` (CLAUDE.md + spec cross-references current)
