# Tasks — mop-parser-fidelity

Scope: consumer-side parser fix in `MopData` (`src/main/java/com/android/commands/monkey/ape/utils/MopData.java`) + unit tests in `MopDataTest` / `MopScorerTest`. No producer change, no new config flags. Implements `design.md`; satisfies the MODIFIED requirements in `specs/mop-guidance` and `specs/wtg-navigation`.

## 1. Widget-map fidelity (mop-guidance)

- [x] 1.1 Add private `static int mopRank(Widget w)` to `MopData` returning `2` if `directMop`, `1` if `transitiveMop`, else `0` (Decision D1).
- [x] 1.2 Add a private `int droppedFlaggedNoId` accumulator field on `MopData` (reset per `load`).
- [x] 1.3 Rewrite the widget-store loop in `parseWindows` (`MopData.java:313-320`): keep `mopActivities.add(activity)` for flagged widgets; for `idName == null || idName.isEmpty()` do NOT store, and increment `droppedFlaggedNoId` when the widget is flagged (INV-MOP-20); for non-empty `idName` store only when no resident exists or `mopRank(incoming) > mopRank(resident)` (INV-MOP-19, strongest-flag-wins, order-independent).
- [x] 1.4 Emit one summary line at the end of `load()` when `droppedFlaggedNoId > 0`: `[APE-RV] MopData: dropped <N> flagged widgets with no resource id` (P4: state-of-now wording).
- [x] 1.5 Add unit tests in `MopDataTest` (synthetic JSON through the real parser, per gh13 §15 pattern): (a) duplicate `shortId` flagged-then-unflagged → `getWidget` returns flagged; (b) duplicate `shortId` unflagged-then-flagged → `getWidget` returns flagged (order-independent); (c) empty-`idName` flagged widget → no `""` entry, `droppedFlaggedNoId` incremented, `activityHasMop` still true; (d) no-collision JSON → map identical to current behavior (regression).
- [x] 1.6 Run `/sdd-test-run MopDataTest`

## 2. WTG base-activity keying (wtg-navigation, sub-fix W)

- [x] 2.1 In the WTG convenience-view construction (`MopData.java:460-478`): key `wtgTransitions` by `baseActivity(source.name)` (currently `source.name`) and construct each `WtgTransition` with `targetActivity = baseActivity(target.name)` (currently `target.name`) (INV-WTG-04, Decision D3).
- [x] 2.2 Re-point the OPTIONSMENU-gateway precompute to the base key (INV-WTG-05, Decision D3a): in `precomputeMopOptionsMenus` (`MopData.java:597`) query `wtgTransitions.get(activity)` (the base activity already computed at `:586`) instead of `wtgTransitions.get(w.name)`. Without this, D3 silently disables the gh13 menu-gateway boost.
- [x] 2.3 Add unit tests: (a) `MainActivity#OptionsMenu`-sourced click edge is returned by `getWtgTransitions("…MainActivity")` and the `#OptionsMenu` key returns empty (`MopDataTest`); (b) a `#`-suffixed target reduces to its base so `MopScorer.scoreWtg` finds `activityHasMop(base)` and returns `mopWeightWtg` (`MopScorerTest`); (c) confirm `MopDataTest.testActivitiesWithMopOptionsMenuPrecomputed` (gateway "C" via a `C#OptionsMenu`-sourced edge) still passes after 2.1+2.2 — it is the regression guard for D3a.
- [x] 2.4 Run `/sdd-test-run MopScorerTest`

## 3. Integration & Verification

- [x] 3.1 `mvn package` builds `target/ape-rv.jar` cleanly (d8 dex step succeeds; system-scope deps not bundled).
- [x] 3.2 Run `/sdd-qa-lint-fix ape` (no-op: SDD config linter=none, checkstyle not installed)
- [x] 3.3 Run `/sdd-verify ape`
- [/] 3.4 (running: code-reviewer subagent) Invoke `/sdd-code-reviewer` via Skill tool (focus: `MopData.parseWindows` retention logic, base-activity keying, no behavior change on the no-collision path).
- [x] 3.5 `openspec validate mop-parser-fidelity --strict` passes.

> Out of scope here: the 19-APK fair-test re-run that measures end-to-end MOP gain (`docs/20260622_investigacao_mop.md` §7.5) — it validates the combined #0+#1+#2 stack, not this change in isolation.

## 4. Verified additions (2026-07-02 synthesis; anchors refreshed 2026-07-05 post change-A drift)

- [x] 4.1 DIALOG re-keying (INV-MOP-25, Decision D5/D6, ~30-45 lines). Implemented as `rekeyDialogsToHost(...)` (private static, after `precomputeMopOptionsMenus`), called from `load()` as "Pass 3.5" between `parseTransitions` and Pass 4/precompute; orphan `[APE-RV]` line emitted in `load()`. Guards added beyond the spec: skip `w.id < 0` (no valid transition target); skip when `dialogClass.equals(host)` (already keyed); skip when `widgetData[dialogClass]` is null (all widgets were empty-id-dropped → host not promoted, keeps the "flagged widget in map ⟹ activityHasMop" invariant). A post-pass in `load()` placed **after `parseTransitions` (`MopData.java:209`) and before `precomputeMopOptionsMenus` (`:219`)** — the ordering is load-bearing: the pass feeds `mopActivities`, which the precompute and the change-A status line both read. Steps:
  - Build dialogWindowId→hostActivity: for each `type=="DIALOG"` window, find an incoming transition (`transitions[]`) whose `targetId` is that window; host = `baseActivity(source.name)`. First incoming edge wins (A3: multi-host dialogs enrich one host; acceptable P1).
  - **Merge from the already-parsed `widgetData[baseActivity(dialogWindow.name)]` map** (NOT the raw `Window.widgets`) so the empty-id filter from Pass 2 (`:332-339`) is inherited (A1); apply the `mopRank` collision branch (`:344-346`) per entry into `widgetData[host]`.
  - **Move, not copy — `widgetData` only**: remove the dialog-class key entry from `widgetData` after merge so `countWidgets`/`countFlagged` (change-A status line) do not double-count (A2). Do NOT touch `mopActivities` here (next bullet).
  - **Promote host to `mopActivities`** whenever a merged widget is flagged, so `activityHasMop(host)` stays consistent with `widgetData[host]` — same invariant `parseWindows:330` maintains (F3/D6).
  - **Retain the dialog-class `mopActivities` entry** that Pass 2 added at `:330` — do NOT remove it in symmetry with the `widgetData` move. WTG edges into the dialog store `targetActivity == baseActivity(dialogWindow.name)` (`:506`) and the gateway precompute's condition 2 tests `mopActivities.contains(t.targetActivity)` (`:646`); dropping the entry would silently disable menu-gateway detection for activities that open a MOP-bearing dialog (D3a footgun). Host promotion is additive, not a substitute (A6).
  - Orphan dialogs (no incoming transition) keep the dialog-class key and are counted; emit the count on a **separate `[APE-RV]` line** (e.g. `[APE-RV] MopData: <N> orphan DIALOG windows (no incoming transition)`), NOT the `[APE-MOP-DATA]` status line — that line's field set is fixed by change A / INV-MOP-21 (F2).
- [x] 4.2 `precomputeMopOptionsMenus`: activity key now via `baseActivity(w.name)`; removed the ad hoc `OPTIONS_MENU_SUFFIX` strip AND the now-unused `OPTIONS_MENU_SUFFIX` constant (`:59`) — no dead code (P1).
- [x] 4.3 Fixed the two stale widget-loop comments (dropped the "+100 fallback substrate" clause; corrected the retracted "`""` unreachable" premise per D2/F1).
  - `:327-328` — delete the "+100 fallback substrate" clause (describes a mechanism removed by mop-discriminative-boost).
  - `:333-335` — **correct the retracted premise** "the `""` bucket is unreachable by getWidget (extractShortId never yields `""`)". Per D2 (2026-07-02) `extractShortId` DOES return `""` for id-less nodes, so `""` is runtime-reachable; the reason to drop is that a single `""` bucket collapses/overwrites id-less widgets (noise), not unreachability (F1).
- [x] 4.4 `MopDataTest` — 6 tests added (a+e combined): `testDialogReKey_widgetResolvableViaHost_dialogClassRemoved`, `_collisionKeepsStrongest`, `_dialogOnlyHostPromoted`, `_orphanCountedAndNotReKeyed` (incl. F2 status-line guard), `_tripleCollision_strongestWinsBothDirections`, `_retainsDialogClassForGatewayDetection`. Original (a) dialog re-key resolvable via host activity; (b) re-key collision keeps strongest flag; (c) host promoted to `activityHasMop` when its only flags come from the dialog (F3/D6 guard); (d) orphan dialog counted and NOT resolvable under any activity key; (e) after re-key the dialog-class key is absent from the widget map (move-not-copy, A2); (f) triple-collision case (direct/transitive/unflagged same idName, shuffled insertion orders — closes the reviewer-flagged test gap). Assert the orphan count appears on an `[APE-RV]` line and the `[APE-MOP-DATA]` line is unchanged (F2 regression guard). (g) gateway-retention guard (A6): an activity whose only MOP route is a click edge into a flagged DIALOG window still appears in the OPTIONSMENU-gateway set after the re-key — proves the dialog-class `mopActivities` entry is not dropped by the move.
- [x] 4.5 `mvn test` → 408 tests, 0 failures, 0 errors, 15 skipped (Android runtime). MopDataTest: 53/53.
