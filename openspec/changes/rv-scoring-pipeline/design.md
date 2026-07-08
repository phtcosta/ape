# Design — rv-scoring-pipeline

## Context

The RV scoring path is five `if`-blocks stacked inline in `StatefulAgent.adjustActionsByGUITree()` (mop-fairtest lines 1476–1660) plus a sixth, FormCompletion. Each block reads the base's `_mopData`, `_coverageTracker`, `getGraph()`, and per-run pick counters directly. Their on/off state is a scatter of `Config` weights (`mopDataPath`, `mopWeightWtg`, `frontierBoostWeight`, `coverageBoostWeight`, `mopWeightOpenMenu`) — and two RV behaviors (`[APE-STEP]` telemetry, FormCompletion) have no flag at all. Four more fork additions (`menuAction` in `State`, the `greedyPickLeastVisited` tiebreak, the `GUITreeBuilder` perception enhancements, the `ActivityBudgetTracker`) are always-on with no flag.

The MOP fair-test needs a single binary whose behavior is a pure function of `ape.properties`, plus a verifiable claim that with every RV lever off the agent selects like upstream APE. That is what this refactor delivers. It is behavior-preserving at default: the passes reproduce their inline blocks and every new flag defaults to current behavior.

Building blocks already present:
- The six inline scoring blocks and their exact ordering (`StatefulAgent.java:1476-1660`); the upstream base loop that precedes them (`:1418-1476`).
- Existing per-pass disable knobs for four of the six passes: `mopDataPath==null` (MopWidget, MenuGateway), `mopWeightWtg==0` (Wtg), `frontierBoostWeight==0` (Frontier, added by `activity-frontier`), `coverageBoostWeight==0` (Coverage). Only FormCompletion lacks a flag.
- The pass-order contracts already asserted elsewhere: `mop-guidance` INV-MOP-05 (base → unvisited → transition → MOP → WTG → coverage), `ui-coverage` (coverage pass after WTG), `form-completion` (form pass after coverage). The refactor preserves this order exactly, so those contracts stay true — the passes still run in that order, now invoked from a pipeline.

## Goals / Non-Goals

**Goals:** extract the seven inline blocks into composable, individually gate-able passes assembled at one point; give the two flagless RV behaviors (`[APE-STEP]`, FormCompletion) and the four always-on fork additions a parity flag each; add `apePureMode` as a single kill-switch; make "APE-pure ≈ upstream selection" a testable invariant; change **nothing** at default flags.

**Non-Goals:** `MopFrontierPass` and any MOP-reach lever (change #2); any default-behavior change; the `tool.py` mapping / arm variants (change #3); re-specifying the passes' scoring math (owned by their originating specs).

## Decisions

1. **Pass interface is minimal (P1).** `ScoringPass { String name(); boolean isEnabled(); void apply(State, ModelAction[], ScoringContext) }`. `isEnabled()` is decided once in the constructor from `Config` (not re-evaluated per call), so an enabled pipeline is a fixed list and a disabled pass is a strict no-op — no priority mutation, no log line (INV-ARCH-02). No lifecycle, no ordering metadata on the pass itself: order is the assembly's responsibility (decision 3).

2. **`ScoringContext` bundles exactly what the inline blocks read today** — `MopData`, `UICoverageTracker`, a graph/`ActivityNode` accessor, and the mutable per-run pick counters (e.g. MOP-target and BACK/MENU pick counts) that the blocks increment. It is constructed once by `StatefulAgent` and passed to every `apply`. Mutable state that must persist across steps (pick counters) lives on the context (or on the agent, reached through it), never on the stateless pass — so a pass carries no run state and stays unit-testable with a stub context. The context is the seam that lets the passes move out of `StatefulAgent` without each pass reaching back into it.

3. **Pipeline order = the exact order of the pre-refactor inline blocks.** `MopWidgetPass` → `MenuGatewayPass` → `WtgPass` → `FrontierPass` → `CoveragePass` → `FormCompletionPass` (INV-ARCH-03). This is the order `mop-guidance`/`ui-coverage`/`form-completion` already specify; preserving it is what keeps those contracts and the characterization goldens intact. `MopFrontierPass` (change #2) will insert after `FrontierPass`; its slot is reserved by documentation only, not created here.

4. **Assembly is a single pure-ish function `ScoringPipeline.fromConfig(Config)`** returning the ordered list of **enabled** passes. It is the only place that maps flags→passes, so the flags→passes matrix is testable in isolation and the startup log `[APE-ARCH] passes=[MopWidget, Wtg, Coverage, ...]` reflects exactly the enabled set (INV-ARCH-04). `adjustActionsByGUITree()` holds one `ScoringPipeline` field, built once.

5. **`adjustActionsByGUITree()` reverts to the upstream body + one for-loop.** The method becomes the upstream base-priority loop (byte-identical to `ape @ 8f51b99`) followed by `for (ScoringPass p : pipeline) p.apply(state, actions, ctx)` (INV-ARCH-05). Nothing else RV-specific remains in the method body — every RV term is a pass.

6. **Four passes reuse their existing weight knobs for `isEnabled()`; only FormCompletion gets a new boolean.** `MopWidgetPass.isEnabled = mopData != null`; `MenuGatewayPass.isEnabled = mopData != null`; `WtgPass.isEnabled = mopData != null && hasWtgData() && mopWeightWtg != 0`; `FrontierPass.isEnabled = mopData != null && hasWtgData() && frontierBoostWeight > 0` (the frontier term reads `MopData.getWtgTransitions` — it shares WtgPass's MopData/WTG-data precondition, not `frontierBoostWeight > 0` alone; the two are one interleaved loop today and the split is parity-safe only with this guard); `CoveragePass.isEnabled = coverageBoostWeight != 0`; `FormCompletionPass.isEnabled = Config.formCompletionEnabled` (**new** — the only pass with no pre-existing off switch). This avoids inventing redundant booleans (P1); the kill-switch (decision 8) zeroes the weights, which turns those four off through their existing knobs.

7. **The four flagless fork additions are gated at their own site, not in the pipeline** — they are not scoring passes:
   - `modelMenuEnabled` gates the fork's `menuAction` at the **selection surface**: when false, `State.getActions()` SHALL NOT include the `menuAction` and the agent SHALL never select `MODEL_MENU`. The field itself stays constructed and non-null, so `INV-EXPL-06`/`INV-MODEL-01`-style "non-null menuAction field" contracts are untouched; only the action's presence in the selectable set is gated. This is the smallest edit that reproduces upstream APE (which has no options-menu action) while keeping the field's lifecycle intact.
   - `leastVisitedPriorityTiebreak` gates the tiebreak in `State.greedyPickLeastVisited()`: when false, ties among equal-`visitedCount` actions are broken by array order (upstream behavior), so no RV priority boost can leak into the greedy path.
   - `treeEnhancementsEnabled` gates the three `GUITreeBuilder` perception fixes together (WebView-prune actionable-count fix, AndroidX actionability, ViewPager scrollable). When false, the builder inherits upstream perception (including the upstream WebView over-prune). One flag, because the three are one conceptual change ("what the agent sees") and the arm never wants a subset.
   - `activityBudgetEnabled` gates the `ActivityBudgetTracker` instantiation and the budget check in `SataAgent.selectNewActionNonnull()`. When false, no tracker is built and the budget check is skipped (upstream has no activity budget).

8. **`apePureMode` forces flags at `Config.load`, not at each read site.** After the normal property load, when `apePureMode==true`, `load` overwrites every RV-defining flag to its off/inert value: booleans→`false`, weight ints→`0`, and RV-activated thresholds→their upstream-inert value (notably `activityStableRestartThreshold`→`Integer.MAX_VALUE`, the upstream disabled value). Each forced key is logged (`[APE-ARCH] apePureMode forced <key>=<value>`). Because the forcing is centralized in `load` and the fields are `public static final` resolved once, no read site needs to know about `apePureMode`. INV-ARCH-06 makes the **completeness** of this list testable: a registry of RV-defining flags is the single source both `load` and the guard test consult, so a new RV flag that is not registered fails the guard.

9. **Parity has two documented always-on exceptions (INV-ARCH-01).** The `ApePinchOrZoomEvent` array-sizing/emit fix stays on under `apePureMode` — a crash (`NullPointerException` on a malformed points array) is not a selection behavior, and reproducing the upstream crash would only reduce the pure arm's step budget without changing what it *chooses*. Seed handling (`RandomHelper.seed(-s)`) stays on — it is reproducibility infrastructure and is arm-neutral (both arms benefit equally). Every other RV behavior is off under `apePureMode`.

## Pass roster

| Pass | Origin (mop-fairtest lines) | `isEnabled()` gate | Owning spec (scoring semantics) |
|---|---|---|---|
| `MopWidgetPass` | `StatefulAgent.java:1476-1502` | `mopData != null` | mop-guidance (MopScorer — Priority Boost) |
| `MenuGatewayPass` | `1503-1517` | `mopData != null` | mop-guidance (OPTIONSMENU-Aware Menu Boost) |
| `WtgPass` | `1520-1529` | `mopData != null && hasWtgData() && mopWeightWtg != 0` | mop-guidance (WTG Scoring Pass) |
| `FrontierPass` | `1530-1563` (pre-drop approx) | `mopData != null && hasWtgData() && frontierBoostWeight > 0` | wtg-navigation (WTG Frontier Boost — post-archive `activity-frontier`) |
| `CoveragePass` | `1580-1602` | `coverageBoostWeight != 0` | ui-coverage (Coverage Boost — Per-Action) |
| `FormCompletionPass` | `1640-1660` | `Config.formCompletionEnabled` (**new**) | form-completion (Form-completion boost pass) |

`MopFrontierPass` (strategy B) is **not** created here — it is change #2, and will insert between `FrontierPass` and `CoveragePass`.

## API sketch

```java
package com.android.commands.monkey.ape.agent.scoring;

public interface ScoringPass {
    String name();
    boolean isEnabled();                                  // decided in ctor from Config
    void apply(State state, ModelAction[] actions, ScoringContext ctx);
}

public final class ScoringPipeline {
    public static ScoringPipeline fromConfig(Config cfg, ScoringContext ctx) { ... } // single assembly point
    // logs [APE-ARCH] passes=[...] once at build; holds only enabled passes
    public void apply(State state, ModelAction[] actions, ScoringContext ctx) {
        for (ScoringPass p : passes) p.apply(state, actions, ctx);
    }
}
```

`StatefulAgent.adjustActionsByGUITree(State, ModelAction[])`:
```
// upstream base-priority loop (ape @ 8f51b99, byte-identical) — INV-ARCH-05
for (ModelAction a : actions) { ...upstream priority assignment... }
// single RV addition:
pipeline.apply(state, actions, scoringContext);
```

## Parity mapping (apePureMode=true ⇒ upstream selection)

| RV behavior | Off value forced by apePureMode | Observable parity check |
|---|---|---|
| MopWidget/Menu/Wtg/Frontier/Coverage passes | weights → 0 / `mopDataPath` unset | pipeline is empty; goldens equal upstream priorities |
| FormCompletionPass | `formCompletionEnabled=false` | no deterministic fill; `checkInput` keeps the `inputRate` toss |
| `[APE-STEP]` telemetry | `stepTelemetryEnabled=false` | zero `[APE-STEP]` lines in the trace |
| fork `menuAction` | `modelMenuEnabled=false` | `getActions()` has no `MODEL_MENU`; no MENU key issued |
| greedy tiebreak | `leastVisitedPriorityTiebreak=false` | equal-visit ties resolved by array order |
| tree perception | `treeEnhancementsEnabled=false` | upstream WebView prune / ViewPager / actionability |
| activity budget | `activityBudgetEnabled=false` | no `ActivityBudgetTracker`; no budget check |
| dynamic epsilon | `dynamicEpsilon=false` | fixed epsilon `defaultEpsilon=0.05` |
| typed/heuristic input | `heuristicInput=false`, `fuzzInputTyped=false` | legacy `StringCache` input generation |
| guards / caps | `foreignActivityGuard`/`treePackageGuard`/`backMenuPickCap`/`mopTargetPickCap` off / idle ceiling upstream | upstream event generation and selection |
| **exception:** ApePinchOrZoom fix | **stays on** | crash fix, not a selection behavior (INV-ARCH-01) |
| **exception:** seed handling | **stays on** | reproducibility, arm-neutral (INV-ARCH-01) |

## Testing strategy

| Layer | What | How |
|---|---|---|
| Characterization (BEFORE refactor) | golden priority arrays for representative GUITree fixtures at current default flags | capture with a fixed seed; assert the refactored pipeline reproduces byte-identical |
| Assembly | flags→passes matrix; `[APE-ARCH] passes=[...]` line content | `ScoringPipeline.fromConfig` unit tests over flag combinations |
| Pass units | each pass reproduces its inline block; disabled pass is a strict no-op | per-pass tests with a stub `ScoringContext` |
| Parity (apePureMode=true) | (i) priorities equal upstream loop, (ii) no `MODEL_MENU` in `getActions()`, (iii) fixed epsilon 0.05, (iv) legacy `StringCache` input, (v) zero `[APE-STEP]` lines | integration test with `apePureMode=true` vs an upstream reference |
| Kill-switch completeness | every RV-defining flag is forced off by `apePureMode` | guard test over the RV-flag registry (fails if a registered flag is not forced, or a new RV flag is unregistered) — INV-ARCH-06 |
| Regression | existing 538 tests green | `mvn test` |

## Risks / Trade-offs

- **[Characterization goldens are brittle to unrelated priority changes]** → they are captured at pinned default flags and a fixed seed; any diff is either a real behavior change (must be justified) or a golden refresh with rationale. This is the point — the goldens are the tripwire.
- **[`modelMenuEnabled` gating at `getActions()` vs. removing the field]** → keeping the field constructed (non-null) and gating only its presence in the selectable set preserves `INV-EXPL-06`/`INV-MODEL-01` and is the smaller edit; the cost is that the field exists-but-unused under the pure arm, which is acceptable (it is inert).
- **[Kill-switch list drifts as new RV flags land]** → INV-ARCH-06 + the guard test convert drift into a red test; the registry is the single source of truth for both `load` forcing and the test.
- **[Pipeline reorders behavior specified inline elsewhere]** → it does not: order is preserved (INV-ARCH-03) and the originating specs' pass-order contracts (INV-MOP-05, ui-coverage, form-completion) remain literally true because the passes still run in that order.

## Migration / archive ordering

Archive the five implemented-but-open changes first (`activity-frontier`, `back-menu-pick-cap`, `foreign-activity-guard`, `tree-package-guard`, `idle-timeout-cap`). This change's deltas are written against post-archive main: `FrontierPass` presupposes `activity-frontier`'s frontier boost is in `wtg-navigation`; `MenuGatewayPass` presupposes `back-menu-pick-cap`'s gate. No delta here re-modifies a requirement those six modify (`ActionType Classification`, `OPTIONSMENU-Aware Menu Boost`), so there is no requirement-modification collision.

## Open Questions

- Exact per-run pick-counter set the `ScoringContext` must carry is fixed by the inline blocks at implementation time (MOP-target and BACK/MENU counts are the known two); the context surface is finalized against the code, not guessed here.
