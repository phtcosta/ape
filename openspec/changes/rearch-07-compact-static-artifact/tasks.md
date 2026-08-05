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
- [ ] 3.4a Surface the launch result on the dispatch path (INV-CT-14). **The mechanism already landed — verify, do not rebuild.** `AndroidDevice.startActivity` returns a `LaunchResult` carrying the platform's `START_*` code (`:515-571`) and `MonkeySourceApe:980` hands it to the sink as `componentLaunch(launch.code, launch.error)`; the delta's "SHALL surface" reads as future work only because it was written before `rearch-04`. What is genuinely owed is the second sentence: recording only — no retry, no re-dispatch, no cursor or budget effect (INV-CT-12's "returned actions" accounting unchanged) — and **the test asserting the launcher behaves identically with the recording present and absent, which does not exist**. `LaunchResult` is referenced by exactly one test file (`NdjsonSinkTest`), and nothing tests INV-CT-14's no-effect clause
- [ ] 3.5 JVM unit tests on the compact fixture: every scenario of the mop-guidance delta (fixture load, legacy-JSON rejection, per-event fallback decoding, flag-selected sets, absent metadata, unknown-key tolerance, strict-match reasons)
- [ ] 3.6 Run `/sdd-test-run MopDataTest`

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

- [ ] 4.1 Assemble the gate's input set: the cryptoapp pair already generated by 3.1 (`cryptoapp.apk.gh60-fresh.json` → `cryptoapp.apk.mop.json`), plus one synthetic **full-JSON** fragment per relocated rule the fixture cannot exercise, each derived through the real generator so the artifact side is never hand-written: flagged-widget-with-empty-`idName` (INV-DRV-02 activity marking + `droppedFlaggedNoId`), `$$ExternalSyntheticLambda` recovery **including the negative case** (INV-DRV-01), DIALOG re-keying with host promotion (INV-DRV-03), an A′ union that differs from the widget-derived set (INV-DRV-06 — the fixture's two sets are equal, so it is blind to this), and the three `deepLinkUri` null cases (INV-DRV-07). Record which rule each fragment exercises; that table is what task 4.4 archives
  - The `mopActivitiesAugmented` synthetic of task 3.3's note is already written and already discriminates the two sets — reuse it rather than authoring a second one.
- [ ] 4.2 Write `MopArtifactEquivalenceTest` over the 4.1 set, running in the **ordinary `mvn test` suite** — no `-Dmop.corpusDir`, no external directory, no skip-when-unset branch (the property and its hard-fail-on-unset clause die with the corpus scope; a gate that can be silently skipped is the failure mode this group exists to avoid, and a fixture-scoped gate has nothing to skip). Old parser on the full JSON vs new parser on the derived artifact: assert identical widget flag maps (per-event + aggregate), metadata, both activity sets (flag off/on), gateway sets (flag off/on), WTG views, trigger/provider tuples, **per-activity `deepLinkUri` including null cases** (activities are excluded from the trigger-tuple pool, so nothing else compares them), `package`/`mainActivity`
  - **The WTG comparison MUST be set-based, not list-based**, and this is not a preference: the jar keeps
    exact-duplicate `(widget, target)` edges and the derivation removes them, so cryptoapp alone diverges
    17 vs 16 — on the very first case the gate reaches. A list comparison would report that as a
    derivation bug. The licence for it is the multiplicity audit `gh96` 7.3 asked for, now done: every WTG
    consumer is first-match (`MopScorer.scoreWtg:117`, `StatefulAgent.frontierBoost:1199`,
    `matchesQualifyingTarget:128`) or set-accumulating (`FrontierPass:58`, `MopFrontierPass:62`,
    `qualifyingMopTargets:115`), so multiplicity cannot reach a decision. Do **not** compare `stats` —
    they are counters under INV-DRV-04 and `wtgEdges` legitimately differs across the cut.
- [ ] 4.3 Run the gate; investigate and fix every divergence **in the generator** (the old parser is the oracle — never adjust the oracle) until green on every member of the 4.1 set. The rule that a case exercised by nothing fails the gate is unchanged in force and changed in subject: it no longer asks how many corpus apps trigger a rule, it asks that every relocated rule have a member of the input set that triggers it — a synthetic that derives to an artifact where the rule did not fire is not coverage, and must be corrected rather than counted
- [ ] 4.4 Record the gate result in the change directory for the archive trail: the input-set table of 4.1 (fragment → rule → what fired), the pass summary, and — explicitly, so the archive does not read as if a corpus gate had run — the two breadth facts this group now leans on instead: `gh96`'s recorded 345-app derivation and its totals, and the deferral of real-application variety to `gh97`'s campaign

## 5. Jar cutover (BREAKING — lands only with Group 6)

- [ ] 5.1 Delete the full-JSON parse machinery from `MopData.java`: `parseReachability`/`parseWindows`/`parseWidget`/`parseListener`/`deriveWidgetMopFlags`/`parseTransitions`/`parseComponents`/`parseIntentFilters`/`parseDataSpec`/`rekeyDialogsToHost`/`augmentActivitiesFromSources`/`precomputeMopOptionsMenus`/`computeHandlerJoinDiagnostics`, the `Window`/`Listener`/`Transition`/`TransitionEvent`/`ReachabilityClass`/`ReachabilityMethod` POJOs, and the test-only getters (`getReachability`, `getWindows`, `getWindow`, `getTransitions`, `isWidgetlessSubstrate`)
- [ ] 5.2 Delete the memory-safety machinery (design D5, V19): `PARSE_FOOTPRINT_FACTOR`, `PARSE_BUDGET_BYTES`, the budget-parameter test seam, `reason=too-large`, and the outer `catch (OutOfMemoryError)`; grep-verify zero `OutOfMemoryError` catches remain in the repo
- [ ] 5.3 Delete `WtgTransition.widgetClass`, the `DataSpec`/`readPermission`/`writePermission`/**`exported`** surfaces from `ComponentInfo`, and `Widget.id`/`text`/`type`/`listeners`. `ComponentInfo` gains `deepLinkUri` (read from the wire) and `MopLauncherStage.buildDeepLinkUri` is deleted with the structure it walked — its call site at `:117` passes `candidate.deepLinkUri` instead. Dropping `exported` changes every `ComponentInfo` subclass constructor and the `ActivityFrontierTest` fixtures that pass it positionally. This is the one place outside `MopData`/`ComponentInfo` that changes; everything else compiles clean because the query API is unchanged by construction
- [ ] 5.3a Migrate the deep-link assertions of `ActivityFrontierTest` ("Lever B", 6 assertions over `buildDeepLinkUri`) to the Python generator suite (task 2.7) — they pin INV-DRV-07 now, on the side that computes it. Assert what remains on the jar side instead: a `ComponentInfo` carrying `deepLinkUri` dispatches `ACTION_VIEW`, one carrying null dispatches the explicit component. **Deleting these assertions instead of migrating them is the wrong fix** — they are the only thing standing between a schema omission and a silently degraded activity frontier
- [ ] 5.4 Delete `MopArtifactEquivalenceTest`, the synthetic full-JSON fragments of task 4.1 and the old-format test resources (`cryptoapp.apk.gh60*.json` fixtures move to test-only history; the compact fixture is now the only loader fixture); update the D7 vocabulary-boundary javadoc on `MopData` (boundary now: generator host-side). The synthetics go with the oracle for the same reason it does — they are full-JSON documents, and a full-JSON document in the test tree after this group is an input no shipped code path can read. Their *rules* do not go with them: each one's permanent home is the named test `gh96` task 2.7 owns, which is where the substitution table of 4.4 points
- [ ] 5.5 Update `CLAUDE.md` (MopData naming note, `mopDataPath` artifact description) and run `/sdd-doc-code src/main/java/com/android/commands/monkey/ape/utils/MopData.java`
- [ ] 5.6 Run `/sdd-test-run ape` (full `mvn test` — 145-test suite adjusted for the removed seams)

## 6. Python push switch (BREAKING — lands only with Group 5)

- [ ] 6.1 `tool.py`: add `_derive_mop_artifact(task)` (digest-checked cache at `<apk_name>.mop.json`, atomic write, `DerivationError` → `RVToolExecutionError`); delete `_compact_static_analysis_json` and its enrichment helper and their fallback-to-source push
- [ ] 6.2 `tool.py` step 1c: replace the warn-and-continue branch with the raise (INV-APERV-05); push to `/data/local/tmp/mop-artifact.json`; update the `ape.mopDataPath` value in `_push_properties`
- [ ] 6.3 Update `test_aperv_tool.py`: absent-JSON raises, derivation-failure raises, cache hit/stale behavior, device path and properties line, non-MOP arms untouched, no-full-JSON-push assertion (INV-APERV-06)
- [ ] 6.4 Run `/rv-test-run aperv-tool` (pytest for the module — rv-android's skills are `rv-*`, not `sdd-*`)
- [ ] 6.5 **Cross-repo OpenSpec instrument**: rv-android's `openspec/specs/aperv/spec.md` MUST NOT be edited by hand (that repo's CLAUDE.md forbids it). Counterpart opened 2026-08-03: **`rvsec#96`**, change `gh96-mop-artifact-derivation`, carrying this stage's delta — the push path switches from the full JSON to the derived artifact, `_compact_static_analysis_json` and its INV-APV-20..25/31/32 requirements retire, and absent-input becomes a raised error — and let that repo's archive/sync apply it

## 7. Coordinated deploy (design D8)

- [ ] 7.1 Land the ape commit (Groups 3+5) and the rv-android commit (Groups 2+6) together; `mvn install -Drvsec_home=…` refreshes the module-local `ape-rv.jar`
- [ ] 7.2 **Delegated to `gh97-rearch-ab-gate` tasks 6.2–6.5** (owner decision 2026-08-05: the APE-RV side executes once, there). That change builds the jar from this worktree, pushes the rv-android commits *before* the image build (its 6.3 — the image's stage-4 layer clones `PAMunb/rvsec` at build time, so unpushed work is silently absent), builds `phtcosta/rvandroid:0.9.3-rearch` as a **new tag** rather than rebuilding `0.9.3` in place, and records both image IDs. Nothing is owed here beyond confirming, when this change closes, that `gh97` 6.1's precondition ("stages `rearch-03`…`rearch-07` complete") is truthfully satisfiable — that gate is not advisory and this is the change it gates on
- [ ] 7.3 Skew drill — **split by what can actually observe each half**, since the bench run does not happen. (a) *Old full JSON meets the new jar* is a JVM fact and is asserted at JVM level: `status=rejected reason=version-mismatch` from `MopData.load` (task 3.5's rejection scenario) plus `StatefulAgent` aborting with `StopTestingException` on a null return (INV-MOP-22). (b) *MOP arm with the full JSON absent* is a host fact and is asserted in pytest: `RVToolExecutionError` raised before any device interaction (task 6.3's absent-JSON case). What neither can attest is that the **deployed** pair behaves this way, and that is the half `gh97`'s pre-flight now carries (`gh97` task 7.2a) — a `build.sha`/`MOP_DATA` mismatch there is the gh71 failure mode caught before the campaign spends 24 hours. Record here that the drill was discharged in three places rather than one bench session, because "both loud, no silent SATA run" is the property, and it is now proven by three different observers rather than one

## 8. Verification

- [ ] 8.1 **Delegated to `gh97-rearch-ab-gate` tasks 7.1–7.4**, whose smoke is the only device execution this stage gets (owner decision 2026-08-05). The delegation is only honest if that smoke *checks what this task was going to check*, so it is not a pointer but a dependency: `gh97` 7.2a — added by this decision — asserts `MOP_DATA status=loaded` with `formatVersion=1` and a non-empty `sourceDigest` on every MOP arm, and a MOP boost actually firing. Note what changes and what does not: the application is no longer cryptoapp but the smoke subset of `subset40`, which is **better** evidence (real applications, three arms) and **worse** in one specific way — the named-widget assertions (`btn_cipher_encrypt`, `buttonGenerateHash`, the MainActivity menu gateway) have no subject there. Those keep their subject on the fixture instead, in task 3.5, where they are assertions about the loader rather than about a run
- [ ] 8.2 Artifact-size and load-time deltas — **rescoped to what is measurable without a paired device run**. The size half is already a measured host fact and needs no device: the cryptoapp artifact is 4,126 bytes against a 69,977-byte source (5.9 %, task 3.1), and `gh96`'s 345-app derivation is the population version of the same claim (task 7.5 there). The load-time half had a pre-change device trace as its comparator and that comparator does not exist: the E3 leg-A logcats carry **no** `[APE-MOP-DATA]` line at all (measured by `gh97` task 3.5), so there is no pre-change load record to difference against, and inventing one from a post-hoc bench run would compare two different jars on two different days. Report the size reduction as the measured claim, and record the load-time delta as **not measured**, with this reason — do not leave the box implying a measurement that no available artifact can support
- [ ] 8.3 Run `/rv-qa-lint-fix aperv-tool` (rv-android) and `/sdd-qa-lint-fix ape` (this repo)
- [ ] 8.4 Run `/sdd-verify ape`
- [ ] 8.5 Invoke `/sdd-code-reviewer` via Skill tool
- [ ] 8.6 Run `/sdd-docs-sync ape` (CLAUDE.md + spec cross-references current)
