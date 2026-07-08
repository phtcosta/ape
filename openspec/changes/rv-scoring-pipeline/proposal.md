# rv-scoring-pipeline

## Why

APE-RV is a 100%-additive fork of upstream APE (`github.com/tianxiaogu/ape` @ `8f51b99`): 131 files byte-identical, 10 modified, 17 added, 0 removed. But the RV extensions are wired into the base by accretion, not by structure, and that has three consequences that block the MOP fair-test experiment (design doc `docs/20260708_arquitetura_separacao_aperv.md`, §0):

- The bulk of the RV behavior lives inline in `StatefulAgent.adjustActionsByGUITree()` (lines 1476–1660): six stacked scoring blocks — MOP-widget, menu-gateway, WTG, frontier, coverage, sibling-penalty — plus a seventh, form-completion (1640–1660). They are a wall of `if`s, not composable units, and their on/off state is spread across a dozen `Config` weights. "Restore the original SataAgent" is the wrong lever — the SataAgent barely changed (+117 guarded lines); the divergence is in the base.
- Two RV behaviors have **no flag at all**: the `[APE-STEP]` per-step telemetry (`StatefulAgent.java:1360-1378`) and FormCompletion (`StatefulAgent.java:1640-1660`, fires on every unfilled `EditText` — the strongest single deviation from upstream selection). An "APE-pure" arm today cannot be expressed at all.
- Four fork-only behaviors are silently always-on with no flag: the unconditional `menuAction` on every `State` (`State.java:65`), the priority tiebreaker in `greedyPickLeastVisited`, the GUITree perception enhancements (WebView-prune fix, AndroidX actionability, ViewPager scrollable) in `GUITreeBuilder`, and the `ActivityBudgetTracker`. None can be turned off, so the experiment cannot hold a true upstream-APE baseline.

The experiment (`ape_pure` vs `sata` vs the MOP arms) needs a single binary whose behavior is fully determined by `ape.properties`, with a verifiable **parity** contract: with all RV levers off, action selection is equivalent to upstream APE. That is a structural refactor plus a set of parity flags — this change.

## What Changes

Structural refactor of the RV scoring path into a plugin pipeline, plus the missing parity flags. **No default behavior changes** — every new flag defaults to the value that reproduces current aperv behavior, and every extracted pass reproduces its inline block byte-for-byte at current flag values.

- **New capability `scoring-pipeline`**: a `ScoringPass { name(); isEnabled(); apply(state, actions, ctx) }` interface and a `ScoringContext` (bundling `MopData`, `UICoverageTracker`, graph/`ActivityNode` access, and the per-run pick counters the inline blocks read today), in a new package `com.android.commands.monkey.ape.agent.scoring`. The six inline scoring blocks plus FormCompletion become seven passes — `MopWidgetPass`, `MenuGatewayPass`, `WtgPass`, `FrontierPass`, `CoveragePass`, `SiblingPenaltyPass`, `FormCompletionPass` — assembled in a single point `ScoringPipeline.fromConfig(Config)`, logged once at startup as `[APE-ARCH] passes=[...]`. `adjustActionsByGUITree()` reverts to the upstream loop (1418–1476) followed by one `for (ScoringPass p : pipeline) p.apply(...)`.
- **Parity flags (new, defaults preserve current behavior)**: `formCompletionEnabled=true` (gates FormCompletionPass), `stepTelemetryEnabled=true` (gates the `[APE-STEP]` line + per-action timing), `modelMenuEnabled=true` (gates the fork's `menuAction` in `State.getActions()`), `leastVisitedPriorityTiebreak=true` (gates the priority tiebreak in `greedyPickLeastVisited`), `treeEnhancementsEnabled=true` (gates the three `GUITreeBuilder` perception enhancements), `activityBudgetEnabled=true` (gates the `ActivityBudgetTracker` instantiation + budget check).
- **`apePureMode=false` kill-switch**: when true, `Config.load` forces **every** RV-defining flag to its off/inert value (booleans→false, weight ints→0, RV-activated thresholds→their upstream-inert value) and logs each forced key. This is defense-in-depth: the `ape_pure` arm does not depend on the experiment harness enumerating ~18 flags, and every future RV flag is obligated to register in the kill-switch (a testable invariant, INV-ARCH-06).
- **Parity invariant INV-ARCH-01**: with `apePureMode=true`, action selection is equivalent to upstream APE. Two documented, always-on exceptions: the `ApePinchOrZoomEvent` crash fix (a crash is not "behavior") and seed handling (reproducibility infrastructure, arm-neutral).

## Impact

- **Affected specs**: new capability `scoring-pipeline` (ADDED); surgical MODIFIED to `exploration` (MODEL_MENU gate), `action-selection` (greedyPickLeastVisited tiebreak gate + `[APE-STEP]` telemetry gate), `ui-tree` (ViewPager + WebView-prune enhancement gates), `form-completion` (FormCompletionPass gate), `activity-budget` (budget-check gate).
- **Affected code**: new `com.android.commands.monkey.ape.agent.scoring` package (interface + context + 7 passes + `ScoringPipeline`); `StatefulAgent.adjustActionsByGUITree()` reduced to upstream loop + pipeline for-loop; `Config.java` (7 new flags + the `apePureMode` forcing block in `load`); `State.getActions()`, `State.greedyPickLeastVisited()`, `GUITreeBuilder`, `ActivityBudgetTracker` call site, and the `[APE-STEP]` emitter each gain a single flag gate.
- **Behavior**: none at default. `apePureMode=true` yields upstream-APE-equivalent selection.
- **Tests**: characterization goldens captured **before** the refactor (current priorities at current flags), reproduced byte-identical after; parity suite for `apePureMode=true`; kill-switch registration-completeness guard; flags→passes assembly matrix; existing suite (538) stays green.

## Dependencies and ordering

- **PRESUPPOSES the archive of the six open changes** `activity-frontier`, `back-menu-pick-cap`, `sibling-state-depriority`, `foreign-activity-guard`, `tree-package-guard`, `idle-timeout-cap` (all implemented in code, pending archive). This change's `FrontierPass` extracts the frontier boost that `activity-frontier` adds to the WTG pass, `SiblingPenaltyPass` extracts the penalty that `sibling-state-depriority` adds, and `MenuGatewayPass` wraps the gateway boost that `back-menu-pick-cap` gates. The pipeline is a structural re-home of behavior those changes specify; it does not re-specify them. Its deltas are written against the **post-archive** main. Archive the six first, then this change.
- Per design doc §6, this is change #1 of three; `mop-reach-strategies` (change #2) depends on it (its new `MopFrontierPass` is authored as a pipeline pass); `aperv-arm-variants` (change #3, rv-android) proceeds in parallel once this change fixes the property names.

## Non-Goals

- **`MopFrontierPass`** (strategy B) and the other MOP-reach levers (A′, E-min, F′ seams) — those belong to `mop-reach-strategies` (change #2), not here.
- **Any default-behavior change** — this change is behavior-preserving at default flags by construction; all measured deltas are deferred to the arm variants.
- **The `tool.py` `APERV_PROPERTY_MAPPING` and frozen arm variants** — those are `aperv-arm-variants` (change #3, rv-android repo).
- **Re-specifying the seven passes' scoring semantics** — their contracts remain owned by `mop-guidance`, `ui-coverage`, `form-completion`, and the archived `activity-frontier`/`sibling-state-depriority`/`back-menu-pick-cap` specs. This change adds only the structural (pipeline) contract and the parity gates.
