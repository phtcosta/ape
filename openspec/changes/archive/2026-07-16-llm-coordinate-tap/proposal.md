## Why

The LLM arm cannot act on UI elements that are absent from the accessibility tree. When the vision model returns a valid, in-bounds coordinate that lands on an element with no backing `AccessibilityNodeInfo` — game canvases (`SurfaceView`, OpenGL), Compose surfaces without semantics, custom-drawn keyboards and calculator grids — `LlmRouter.mapToModelAction()` finds no candidate `ModelAction` and returns null. The decision is recorded as `no_match` and discarded; SATA then picks a widget-based action instead, so the LLM's correct visual grounding is wasted and the app's dynamic surface stays unexplored.

This is measurable, not hypothetical. Across the `cmpv2s_base` traces, ~21% of all `no_match` outcomes are this off-tree case (median `nearest_dist` 294 px, up to 1526 px), concentrated in exactly the apps whose UI is custom-drawn: `com.dessalines.thumbkey` (Compose-canvas keyboard), `com.gpl.rpg.AndorsTrail` (RPG canvas), `nerdcalci` (calculator button grid), `saucenao`, `nexttraceroute`. For these apps the LLM sees the pixel and aims correctly, but APE has no way to dispatch the tap.

## What Changes

- **New `ActionType.MODEL_LLM_TAP`** — a targetless model action carrying an LLM-supplied pixel coordinate. It is positioned **outside** the `requireTarget()` ordinal range (alongside `MODEL_MENU` / `MODEL_BACK`), so it is a first-class MODEL action that resolves to raw coordinates rather than a `GUITreeNode`. This preserves the existing invariant that `requireTarget()` is an ordinal-range predicate (INV-EXPL-13).
- **`ModelAction` carries a coordinate** for `MODEL_LLM_TAP` actions — the tap target is `(pixelX, pixelY)` instead of a resolved widget node. All node-dependent accessors (`getBoundsInScreen`, target `Name`) are guarded for this type, mirroring how they already guard `MODEL_MENU` / `MODEL_BACK`.
- **`LlmRouter.mapToModelAction()` classifies the null reason.** When the failure is the off-tree case — coordinate is in-bounds (passes the boundary reject), non-degenerate (not `(0,0)`), and no widget lies within Euclidean tolerance — it constructs a `MODEL_LLM_TAP` at the LLM coordinate instead of returning null. Degenerate `(0,0)` coordinates and boundary-band (status/nav bar) coordinates continue to return null (`no_match`) unchanged.
- **Event generation** maps `MODEL_LLM_TAP` to the existing `ApeClickEvent(x, y, longClick)` raw `MonkeyTouchEvent` primitive. No new event class is introduced.
- **The resulting `StateTransition` edge is labeled** with `type=MODEL_LLM_TAP` and `DecisionSource.LLM`, making the raw tap model-visible and traceable as an LLM coordinate tap on a probable dynamic element.
- **Telemetry** gains a distinct `result=llm_tap` outcome in the `[APE-LLM-TEL]` line, separating "acted via raw coordinate" from `matched` and `no_match` so the effect remains measurable. The `llmTapCount` counter is added to the `[APE-RV] LLM Summary` line and joins the `decisions` denominator (an off-tree event previously counted as `no_match` is now counted as `llm_tap`, both inside `decisions`), keeping the `LLM Decision ratio` comparable across the change.
- **BREAKING (spec-level)**: INV-RTR-03's guarantee that the returned `ModelAction` is always a member of the input `actions` list no longer holds — an off-tree tap returns a synthesized action not present in that list. The invariant is restated to permit exactly this case.

Scope is surgical: this change targets only the off-tree grounding gap. It does not alter degenerate-coordinate handling, boundary policy, `(0,0)` emission (a base-model quality issue), or any non-LLM code path.

## Capabilities

### New Capabilities
<!-- None. The behavior lives within the existing llm-routing and model domains. -->

### Modified Capabilities
- `llm-routing`: `selectAction()` / `mapToModelAction()` MAY now return a synthesized `MODEL_LLM_TAP` action for the off-tree case instead of null; INV-RTR-03 restated; the "Coordinate-to-ModelAction Mapping", "Action Selection Pipeline", and "LLM Telemetry Logging" requirements gain the off-tree tap outcome and `result=llm_tap` telemetry.
- `model`: introduces `ActionType.MODEL_LLM_TAP` as a targetless action outside the `requireTarget()` ordinal range; `ModelAction` gains a coordinate carrier for this type; `StateTransition` edges may be keyed on a coordinate-bearing LLM tap action.

## Impact

- **Code**:
  - `ape/model/ActionType.java` — new enum value + `requireTarget()` ordinal-range invariant (INV-EXPL-13).
  - `ape/model/ModelAction.java` — coordinate-carrying targetless action; node-accessor guards; graph identity for coordinate taps.
  - `ape/llm/LlmRouter.java` — classify the off-tree null reason, construct the tap, emit `result=llm_tap` telemetry.
  - `com/android/commands/monkey/MonkeySourceApe.java` — `generateEventsForAction` branch mapping `MODEL_LLM_TAP` → `ApeClickEvent(x, y)`.
  - `ape/agent/SataAgent.java` — accept the returned tap action from the LLM hooks (new-state / stagnation / random), set `DecisionSource.LLM`, and **resolve the synthesized `LlmTapAction`** against the current state (targetless branch of `State.resolveAction`) before returning it, so the `isModelAction()==true` tap carries a non-null `GUITreeAction` when `StatefulAgent.resolveNewAction` reads it (design D7). A matched widget action from the same hook is already resolved and is not re-resolved.
- **Model graph**: off-tree taps now appear as real edges. Coverage/refinement machinery treats them as source-nodeless actions (the destination `GUITree` is still captured after the tap, as for any event). Edge identity for coordinate taps is a design decision resolved in `design.md`.
- **Experiment**: not tied to the running `cmpv2s` base/v2 run; ships in a future build. When deployed, the outcome mix shifts (`no_match` share drops by the off-tree fraction; a new `llm_tap` outcome appears) — the change is arm-invariant (same matching policy for base and v2).
- **No new dependencies.** Reuses the existing `ApeClickEvent` / `MonkeyTouchEvent` primitive and the existing `DecisionSource.LLM` label.
