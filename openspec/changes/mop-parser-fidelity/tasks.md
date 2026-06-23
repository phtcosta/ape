# Tasks — mop-parser-fidelity

Scope: consumer-side parser fix in `MopData` (`src/main/java/com/android/commands/monkey/ape/utils/MopData.java`) + unit tests in `MopDataTest` / `MopScorerTest`. No producer change, no new config flags. Implements `design.md`; satisfies the MODIFIED requirements in `specs/mop-guidance` and `specs/wtg-navigation`.

## 1. Widget-map fidelity (mop-guidance)

- [ ] 1.1 Add private `static int mopRank(Widget w)` to `MopData` returning `2` if `directMop`, `1` if `transitiveMop`, else `0` (Decision D1).
- [ ] 1.2 Add a private `int droppedFlaggedNoId` accumulator field on `MopData` (reset per `load`).
- [ ] 1.3 Rewrite the widget-store loop in `parseWindows` (`MopData.java:313-320`): keep `mopActivities.add(activity)` for flagged widgets; for `idName == null || idName.isEmpty()` do NOT store, and increment `droppedFlaggedNoId` when the widget is flagged (INV-MOP-10); for non-empty `idName` store only when no resident exists or `mopRank(incoming) > mopRank(resident)` (INV-MOP-09, strongest-flag-wins, order-independent).
- [ ] 1.4 Emit one summary line at the end of `load()` when `droppedFlaggedNoId > 0`: `[APE-RV] MopData: dropped <N> flagged widgets with no resource id` (P4: state-of-now wording).
- [ ] 1.5 Add unit tests in `MopDataTest` (synthetic JSON through the real parser, per gh13 §15 pattern): (a) duplicate `shortId` flagged-then-unflagged → `getWidget` returns flagged; (b) duplicate `shortId` unflagged-then-flagged → `getWidget` returns flagged (order-independent); (c) empty-`idName` flagged widget → no `""` entry, `droppedFlaggedNoId` incremented, `activityHasMop` still true; (d) no-collision JSON → map identical to current behavior (regression).
- [ ] 1.6 Run `/sdd-test-run MopDataTest`

## 2. WTG base-activity keying (wtg-navigation, sub-fix W)

- [ ] 2.1 In the WTG convenience-view construction (`MopData.java:460-478`): key `wtgTransitions` by `baseActivity(source.name)` (currently `source.name`) and construct each `WtgTransition` with `targetActivity = baseActivity(target.name)` (currently `target.name`) (INV-WTG-04, Decision D3).
- [ ] 2.2 Add unit tests: (a) `MainActivity#OptionsMenu`-sourced click edge is returned by `getWtgTransitions("…MainActivity")` and the `#OptionsMenu` key returns empty (`MopDataTest`); (b) a `#`-suffixed target reduces to its base so `MopScorer.scoreWtg` finds `activityHasMop(base)` and returns `mopWeightWtg` (`MopScorerTest`).
- [ ] 2.3 Run `/sdd-test-run MopScorerTest`

## 3. Integration & Verification

- [ ] 3.1 `mvn package` builds `target/ape-rv.jar` cleanly (d8 dex step succeeds; system-scope deps not bundled).
- [ ] 3.2 Run `/sdd-qa-lint-fix ape`
- [ ] 3.3 Run `/sdd-verify ape`
- [ ] 3.4 Invoke `/sdd-code-reviewer` via Skill tool (focus: `MopData.parseWindows` retention logic, base-activity keying, no behavior change on the no-collision path).
- [ ] 3.5 `openspec validate mop-parser-fidelity --strict` passes.

> Out of scope here: the 19-APK fair-test re-run that measures end-to-end MOP gain (`docs/20260622_investigacao_mop.md` §7.5) — it validates the combined #0+#1+#2 stack, not this change in isolation.
