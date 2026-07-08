# Tasks — rv-scoring-pipeline

> Ordering: this change PRESUPPOSES the archive of the five open changes (`activity-frontier`, `back-menu-pick-cap`, `foreign-activity-guard`, `tree-package-guard`, `idle-timeout-cap`). Archive them first; the deltas here are written against post-archive main.

## 1. Characterization (BEFORE any refactor)

- [ ] 1.1 Capture golden priority arrays for a set of representative `GUITree`/`State` fixtures (at least: a MOP-widget state, a WTG/frontier state, a coverage-gap state, an over-fragmented activity, a form state) by running the **current** inline `adjustActionsByGUITree()` at default flags with a fixed seed; store as test resources
- [ ] 1.2 Capture the current `[APE-STEP]` decision-source attribution for the same fixtures (default flags) as a golden, so the refactor is proven attribution-preserving
- [ ] 1.3 Add a characterization test that asserts the (yet-unrefactored) engine reproduces the goldens — this is the RED baseline the refactor must keep green

## 2. Scoring pipeline scaffolding

- [ ] 2.1 Create package `com.android.commands.monkey.ape.agent.scoring` with the `ScoringPass` interface (`name()`, `isEnabled()`, `apply(State, ModelAction[], ScoringContext)`) and `ScoringContext` (MopData, UICoverageTracker, graph/ActivityNode accessor, per-run pick counters), with P4 current-state comments (INV-ARCH-02)
- [ ] 2.2 `ScoringContext` unit test: passes read collaborators from the context; a pass holds no run-mutable field (pick counters live on the context)
- [ ] 2.3 `ScoringPipeline.fromConfig(Config, ScoringContext)` — single assembly point; constructs the six passes in fixed order, retains only `isEnabled()`==true, emits one `[APE-ARCH] passes=[...]` line (INV-ARCH-03, INV-ARCH-04)
- [ ] 2.4 Assembly matrix unit tests: flags→enabled-passes for the pure arm (empty), a coverage-only arm, and the full MOP arm; assert order and the `[APE-ARCH]` line content

## 3. Extract the six passes

> Line anchors below are **pre-sibling-drop approximations** and have shifted (the dropped `sibling-state-depriority` removed its ~60-line block + two helpers from `StatefulAgent.java`). Re-derive the exact block boundaries against the current file at extraction time — treat the `:NNNN-MMMM` references as "find this block", not literal line ranges.

- [ ] 3.1 `MopWidgetPass` — extract `StatefulAgent.java:1476-1502`; `isEnabled = ctx.getMopData() != null`; per-pass test reproduces the inline block (mop-guidance semantics unchanged)
- [ ] 3.2 `MenuGatewayPass` — extract `:1503-1517`; `isEnabled = ctx.getMopData() != null` (respects the `back-menu-pick-cap` gate via the existing `menuPickEligible` hook — semantics unchanged)
- [ ] 3.3 `WtgPass` — extract `:1520-1529`; `isEnabled = getMopData()!=null && hasWtgData() && Config.mopWeightWtg!=0`
- [ ] 3.4 `FrontierPass` — extract the post-archive `activity-frontier` frontier term (currently **interleaved inside the WTG loop**, sharing the `transitions`/`visitedTargets` state and doing a read-modify-write into the same `wtgBoost`); `isEnabled = getMopData() != null && getMopData().hasWtgData() && Config.frontierBoostWeight > 0` — **NOT** `frontierBoostWeight > 0` alone: the frontier term reads `MopData.getWtgTransitions(activity)`, so it carries the same MopData+WTG-data precondition as `WtgPass` (task 3.3). Splitting the interleaved loop into two independent passes is parity-safe because both the priority and the `wtgBoost` accumulation are additive/order-independent, but ONLY if `FrontierPass` re-guards on MopData/WTG-data (else it NPEs / changes behavior when MopData is null)
- [ ] 3.5 `CoveragePass` — extract `:1580-1602`; `isEnabled = Config.coverageBoostWeight != 0`
- [ ] 3.6 `FormCompletionPass` — extract `:1640-1660`; `isEnabled = Config.formCompletionEnabled` (**new** flag, task 5.1)
- [ ] 3.7 Rewrite `StatefulAgent.adjustActionsByGUITree()` to the upstream base-priority loop (byte-identical to `ape @ 8f51b99`) + one `pipeline.apply(state, actions, ctx)`; hold one `ScoringPipeline` field built once at construction (INV-ARCH-05)
- [ ] 3.8 Per-pass "disabled = strict no-op" tests: a disabled pass is absent from the pipeline and mutates nothing (INV-ARCH-02)

## 4. Gate the four flagless fork behaviors at their own sites

- [ ] 4.1 `modelMenuEnabled` — gate the `menuAction` in `State.getActions()` (field stays constructed/non-null; excluded from the selectable set when false); keep `State.getMenuAction()` non-null (INV-EXPL-06 untouched)
- [ ] 4.2 `leastVisitedPriorityTiebreak` — gate the priority tiebreak in `State.greedyPickLeastVisited()`; when false, ties broken by array order (upstream)
- [ ] 4.3 `treeEnhancementsEnabled` — gate the three `GUITreeBuilder` perception enhancements (WebView-prune actionable count, AndroidX actionability, ViewPager scrollable); when false, upstream perception
- [ ] 4.4 `activityBudgetEnabled` — gate `ActivityBudgetTracker` instantiation + the budget check in `SataAgent.selectNewActionNonnull()`; when false, no tracker, no check
- [ ] 4.5 `stepTelemetryEnabled` — gate emission of the `[APE-STEP]` line (the `decisionSource` provenance field is still set); when false, zero lines

## 5. Config flags and kill-switch

- [ ] 5.1 Declare the seven new flags in `Config.java` (`formCompletionEnabled`, `stepTelemetryEnabled`, `modelMenuEnabled`, `leastVisitedPriorityTiebreak`, `treeEnhancementsEnabled`, `activityBudgetEnabled` = true; `apePureMode` = false) with P4 current-state comments (INV-ARCH-07)
- [ ] 5.2 Add the RV-flag registry (single source consulted by `Config.load` forcing and the guard test) enumerating every RV-defining flag with its off/inert value
- [ ] 5.3 In `Config.load`, when `apePureMode==true`, force every registered flag to its off/inert value (booleans→false, weights→0, `activityStableRestartThreshold`→`Integer.MAX_VALUE`) and log `[APE-ARCH] apePureMode forced <key>=<value>` per key (INV-ARCH-06)
- [ ] 5.4 Kill-switch completeness guard test: every RV-defining flag is forced by `apePureMode`; a registered-but-unforced flag OR an unregistered RV flag fails the test (INV-ARCH-06)

## 6. Parity and pass unit tests

- [ ] 6.1 Assert the refactored pipeline reproduces the characterization goldens byte-identical at default flags (tasks 1.1/1.3 stay green after the refactor)
- [ ] 6.2 Attribution goldens (task 1.2) reproduced after the refactor
- [ ] 6.3 Parity integration test (`apePureMode=true`): (i) empty pipeline / priorities equal the upstream loop, (ii) no `MODEL_MENU` in `State.getActions()`, (iii) fixed epsilon `defaultEpsilon=0.05`, (iv) legacy `StringCache` input, (v) zero `[APE-STEP]` lines (INV-ARCH-01)
- [ ] 6.4 Always-on exceptions under `apePureMode=true`: the `ApePinchOrZoomEvent` fix is active and `RandomHelper` is seeded from `-s` (INV-ARCH-01)
- [ ] 6.5 Per-gate scenario tests: each of the six behavior flags off ⇒ its gated behavior absent, all other behavior unchanged (scoring-pipeline / exploration / action-selection / ui-tree / form-completion / activity-budget delta scenarios)

## 7. Verification

- [ ] 7.1 Full suite: `mvn test` (0 failures/errors; the existing 538 stay green, new pass/parity tests added)
- [ ] 7.2 `openspec validate rv-scoring-pipeline --strict`
- [ ] 7.3 [skill: superpowers:verification-before-completion] confirm the characterization goldens and the parity suite both pass before claiming behavior-preservation
- [ ] 7.4 Startup-line check: a normal (default-flags) run emits one `[APE-ARCH] passes=[...]` line listing the enabled passes in order; an `apePureMode=true` run emits `[APE-ARCH] passes=[]` plus the `apePureMode forced` lines
- [ ] 7.5 At sync/archive time: scope the global invariants this delta references but does not restate — `INV-EXPL-06` (unchanged; verify still literally true), `INV-SEL-01`/`INV-SEL-02` (tiebreak now flag-gated), `INV-SEL-04` (exactly-one `[APE-STEP]` now scoped to `stepTelemetryEnabled=true`), and `INV-TREE-02`/`INV-TREE-03`/`INV-TREE-11`/`INV-TREE-12` (tree enhancements now flag-gated) — following the repo pattern used for `INV-EXPL-05` in `activity-frontier`
- [ ] 7.6 Device smoke (rebuilt jar) on `cryptoapp` for the `ape_pure`, `sata`, and a MOP arm: `ape_pure` emits `[APE-ARCH] passes=[]`, no `[APE-STEP]`, no `MODEL_MENU`; `sata` emits the coverage/form passes; the MOP arm emits the full ordered set — confirming the single binary composes each arm by properties alone
