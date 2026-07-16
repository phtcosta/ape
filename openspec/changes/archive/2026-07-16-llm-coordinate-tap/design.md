## Context

The LLM arm (`ape.llm.LlmRouter`) selects an exploration action by sending a screenshot to a vision model, parsing a coordinate, and mapping that coordinate to a `ModelAction` on the current state. Mapping is purely geometric against widget bounds taken from the `GUITree` (the `AccessibilityNodeInfo` tree). When the vision model returns a valid, in-bounds coordinate that lands on an element with **no** backing accessibility node — game canvases, `SurfaceView`, Compose surfaces without semantics, custom-drawn keyboards — no widget contains the point and none falls within Euclidean tolerance, so `mapToModelAction()` returns null. `selectAction()` records `no_match` and the LLM decision is discarded; SATA takes over.

Measured on `cmpv2s_base` traces: of all `no_match` outcomes, ~71.5% are degenerate `(0,0)` model emissions, ~7.7% are genuine boundary-band (status/nav bar) rejects, and **~20.8% are this off-tree case** (median `nearest_dist` 294 px), concentrated in `com.dessalines.thumbkey`, `com.gpl.rpg.AndorsTrail`, `nerdcalci`, and other custom-UI apps.

This change lets APE dispatch a raw coordinate tap for the off-tree case, so the LLM's correct visual grounding is acted on instead of thrown away. Two existing mechanisms make this low-risk:

- **`MODEL_MENU` / `MODEL_BACK`** — model actions that carry **no** widget target. `ActionType.requireTarget()` is an ordinal-range predicate; these sit outside the range, and every node-dependent accessor in `ModelAction` is guarded by `!requireTarget()`.
- **`ActivityTriggerAction` (EVENT_TRIGGER_ACTIVITY)** — an action **synthesized by the agent and returned directly**, not drawn from `state.getActions()`, carrying its own payload (deep-link/component). It flows through resolve → event-generation → model-update without being a widget action.

`MODEL_LLM_TAP` combines both patterns: a targetless model action (like `MODEL_MENU`) synthesized and returned by the router (like `ActivityTriggerAction`), carrying a pixel coordinate as payload.

**Where the two precedents diverge (and why a resolution step is required).** `ActivityTriggerAction extends Action` with `EVENT_TRIGGER_ACTIVITY`, so `isModelAction() == false`; it takes the **else** branch of `StatefulAgent.resolveNewAction()` (`StatefulAgent.java:1377`), which never reads a `GUITreeAction`. `MODEL_LLM_TAP.isModelAction() == true`, so an `LlmTapAction` takes the **if** branch (`StatefulAgent.java:1354-1357`), which does `newGUITreeAction = newAction.getResolvedGUITreeAction(); Utils.assertNotNull(newGUITreeAction)`. A matched widget action satisfies this because it is a member of `state.getActions()` and was already resolved by `validateAllNewActions`. The synthesized `LlmTapAction` is **not** a member of `state.getActions()` and is never resolved, so `getResolvedGUITreeAction()` is null and `assertNotNull` throws an NPE. Therefore the LLM hook MUST resolve the synthesized tap before returning it — mirroring the targetless branch of `State.resolveAction` (`State.java:388-393`), exactly as `MODEL_MENU` / `MODEL_BACK` are resolved. See D7.

## Architecture

```
LlmRouter.selectAction()
  └─ mapToModelAction(pixelX, pixelY, actionType, text, actions, state, w, h)
       ├─ "back"                         → state.getBackAction()          (unchanged)
       ├─ boundary reject (band / 0,0)   → null                           (unchanged)
       ├─ bounds-containment / euclidean → matched ModelAction            (unchanged)
       └─ in-bounds, no widget, click/long_click
                                         → new LlmTapAction(state, x, y, longClick)   ◄── NEW
  └─ result classification: matched | llm_tap | no_match(reason=degenerate|boundary)

SataAgent LLM hooks (new-state / stagnation / random)
  └─ result != null → result.setDecisionSource(LLM)
       └─ result.getType() == MODEL_LLM_TAP                                                   ◄── NEW
            → newState.resolveAction(this, result, getThrottleForNewAction(newState, result))
              (targetless branch → resolveAt(ts, throttle, tree, null, null); sets resolvedGUITreeAction)
       └─ return result   (matched widget actions are already resolved — NOT re-resolved)

MonkeySourceApe.generateEventsForActionInternal(action)
  └─ case MODEL_LLM_TAP: generateClickEventAt(point(x,y), CLICK|LONG_CLICK_WAIT_TIME)   ◄── NEW

Model update
  └─ StateTransition(source, action=LlmTapAction, targetState)            (existing machinery;
                                                                            edge labeled DecisionSource.LLM)
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `ActionType.MODEL_LLM_TAP` | New enum value, targetless model action (ordinal between `MODEL_MENU` and `MODEL_CLICK`) | — | `requireTarget()=false`, `isModelAction()=true` |
| `LlmTapAction extends ModelAction` | Carry `(pixelX, pixelY, longClick)` payload for an off-tree tap | `State`, `int x`, `int y`, `boolean longClick` | A `ModelAction` of type `MODEL_LLM_TAP`, `target=null` |
| `LlmRouter.mapToModelAction()` | On in-bounds no-widget click/long_click, construct `LlmTapAction` instead of returning null | pixel coords, actionType, actions, state | `ModelAction` (matched / tap) or null |
| `LlmRouter.selectAction()` | Classify outcome; emit `result=llm_tap` or `result=no_match reason=…` | — | telemetry line + returned action |
| `SataAgent` LLM hooks (×3) | Set `DecisionSource.LLM`; **resolve the synthesized `LlmTapAction`** against `newState` before returning it (so `resolveNewAction` sees a non-null `GUITreeAction`) | `LlmTapAction` | resolved `LlmTapAction` |
| `MonkeySourceApe.generateEventsForActionInternal()` | Dispatch `MODEL_LLM_TAP` as a raw tap at its coordinate | `LlmTapAction` | `ApeClickEvent` / `MonkeyTouchEvent` |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| model / INV-MODEL-02 (requireTarget false for MODEL_LLM_TAP) | `ActionType.requireTarget()` ordinal range unchanged; value inserted below `MODEL_CLICK` | `ActionTypeTest.llmTapIsTargetless` |
| model / INV-MODEL-03 (isModelAction true) | ordinal within `[MODEL_BACK, MODEL_SCROLL_RIGHT_LEFT]` | `ActionTypeTest.llmTapIsModelAction` |
| model / requireTarget Contract | `LlmTapAction` constructable with `target=null` | `LlmTapActionTest.constructsTargetless` |
| model / INV-MODEL-13 (coordinate-bearing identity) | `LlmTapAction.equals`/`hashCode` override adding `(pixelX, pixelY, longClick)` | `LlmTapActionTest.tapsAtDifferentCoordinatesAreNotEqual` |
| model / INV-MODEL-14 (ephemeral action is not graph-registered) | `Action.isEphemeral()`; `Graph.markVisited` early branch; `Model.resolveNonDeterministicTransitions` guard | `GraphEphemeralActionTest`, `ActionTypeTest.onlyLlmTapIsEphemeral` |
| llm-routing / INV-RTR-03 (restated) | `mapToModelAction` returns synthesized `LlmTapAction` | `LlmRouterMappingTest.offTreeReturnsTap` |
| llm-routing / Coordinate Mapping (off-tree tap) | end-of-`mapToModelAction`: no euclidean match + click/long_click → `LlmTapAction` | `LlmRouterMappingTest.offTreeClickBuildsTap` |
| llm-routing / Telemetry (`result=llm_tap`, `reason=`) | `selectAction` outcome classification | `LlmRouterTelemetryTest.llmTapLogged` |
| model / LLM Coordinate Tap Action (synthesized tap is resolved before dispatch) | `SataAgent` hook: `newState.resolveAction(this, tap, …)` when `type==MODEL_LLM_TAP` | `SataAgentLlmTapTest.synthesizedTapIsResolved` (tap flows through `resolveNewAction` with non-null `GUITreeAction`, no NPE) |
| MODEL_LLM_TAP event dispatch | `generateEventsForActionInternal` case + `generateClickEventAt` | device smoke (no unit harness for event injection) |

## Goals / Non-Goals

**Goals:**
- Dispatch a raw coordinate tap when the LLM returns a valid in-bounds coordinate that matches no widget (the off-tree case), for `click` and `long_click`.
- Make the tap model-visible: a real `StateTransition` edge labeled `type=MODEL_LLM_TAP` and `DecisionSource.LLM`.
- Keep the outcome measurable: distinct `result=llm_tap` telemetry, and a `reason=degenerate|boundary` sub-field separating the two remaining `no_match` mechanisms.

**Non-Goals:**
- Handling `type_text` off-tree — a raw coordinate has no `EditText` node to receive input; these stay `no_match`.
- Acting on degenerate `(0,0)` emissions (a base-model quality issue) or boundary-band (system UI) coordinates — both stay `no_match`.
- Grid quantization of the coordinate — `LlmTapAction` identity carries the exact `(pixelX, pixelY, longClick)` payload (D3); no bucketing.
- Making the tap explorable — it stays outside `State.actions`, so SATA can never select or re-traverse it (D8). It is an observational edge only.
- Any change to non-LLM code paths, to SATA's own selection, or to the running `cmpv2s` experiment.

## Decisions

**D1 — New `ActionType.MODEL_LLM_TAP`, placed between `MODEL_MENU` and `MODEL_CLICK`.**
The only ordinal slot that yields `isModelAction()=true` (so it labels a model edge) **and** `requireTarget()=false` (so it needs no widget node) is between `MODEL_BACK`/`MODEL_MENU` and `MODEL_CLICK`. This preserves the ordinal-range invariant (INV-MODEL-02/03, INV-EXPL-13) with zero change to the predicate bodies. *Alternative rejected*: a value in the `EVENT_*` block — that would make `isModelAction()=false`, so the tap would not be a model edge and could not carry the `DecisionSource.LLM` label the user requires.

**D2 — `LlmTapAction extends ModelAction` carrying `(pixelX, pixelY, longClick)`.**
Mirrors the established `StartAction` / `ActivityTriggerAction` / `FuzzAction` subclass-and-cast pattern. Keeps the coordinate payload off the base `ModelAction` (which is instantiated once per widget action — no bloat). `ModelAction.equals` already guards `getClass() != obj.getClass()`, so an `LlmTapAction` is never equal to a `MODEL_MENU`/widget action. *Alternative rejected*: adding `x`/`y` fields to base `ModelAction` — pollutes every action instance and risks the identity subtleties that ripple through every `Map`/`Set` keyed on `ModelAction`.

**D3 — The coordinate is part of the action's identity (`equals`/`hashCode` overridden).**
`LlmTapAction` overrides `equals`/`hashCode` to extend the inherited identity (`state` + `target=null` + type) with `(pixelX, pixelY, longClick)`. Two taps at different coordinates are therefore **different actions**.

This is a correction of a design defect found by the 6.3 device smoke, and the reason is not cosmetic. Inheriting the identity would make every tap in a state one action key, so a second tap at a *different* coordinate reaching a *different* destination would set the edge type to `NEW_ACTION_TARGET` (`Graph.addStateTransition`, `Graph.java:470-473`), which `Model.resolveNonDeterministicTransitions` (`Model.java:340-357`) reads as **non-determinism** and hands to `NamingFactory.resolveNonDeterminism` → `refine()` / `rebuild()`. APE would rebuild the model under a finer abstraction to explain a difference that is only a coordinate — a phantom no naming can ever resolve, on the core CEGAR machinery, with `evolveModel` on by default. Coordinate-bearing identity removes that at the root: each distinct tap owns its edges, so the ordinary `NEW_ACTION` path applies.

*Alternative rejected*: keeping the inherited identity and exempting the tap from refinement only (the `isBack()` precedent). It treats the symptom; and the claim "two taps at different coordinates are the same action" is simply false — every `Map`/`Set` keyed on `ModelAction` would carry that falsehood. The refinement exemption is still kept as defence-in-depth for genuine same-coordinate canvas non-determinism (D8).

**D4 — Off-tree construction goes at the end-of-function null site.**
`mapToModelAction` has several `return null` sites (`actionType==null`, `back`-exception, boundary reject, empty `actions`, and the end-of-function `bestEuclidean==null`). The two that matter for the off-tree case are the **boundary-reject** branch (early; catches both the status/nav band **and** degenerate `(0,0)`, since `(0,0)` has `pixelY=0 < 5%`) and the **end-of-function** (no bounds-containment, no euclidean match). A `click`/`long_click` coordinate that reaches the end-of-function is guaranteed in-bounds and non-degenerate, so the off-tree construction is a single edit there; the boundary/degenerate nulls are untouched. The empty-`actions` early return is **not** reachable from the LLM path: all three hooks guard `newState.getActions().size() > 2` (`SataAgent.java:385/399/411`), so `actions` is never empty when the router is invoked. `selectAction` labels `reason=degenerate` when the parsed coordinate is `(0,0)`, else `reason=boundary`, for the null case.

**D5 — Reuse `generateClickEventAt`, no new event class.**
`generateClickEventAt(Rect, waitTime, ClickPoint.CENTER)` already builds the `ApeClickEvent`/`MonkeyTouchEvent` down-wait-up sequence and clicks the rect center. The `MODEL_LLM_TAP` branch passes a zero-size `Rect(x, y, x, y)` (center = `(x, y)`) with `LONG_CLICK_WAIT_TIME` when `longClick`, else `CLICK_WAIT_TIME`. Identical dispatch path to `MODEL_CLICK`, so click semantics are consistent.

**D6 — No new guardrail.**
The LLM hooks are already rate-limited (`Config.llmPercentage`, plus the new-state / single-shot-stagnation gates) and gated by the circuit breaker. A game canvas cannot absorb unbounded consecutive taps because the router only fires probabilistically. No consecutive-tap cap is added; if runaway tapping is observed on-device, a cap is a follow-up (Open Questions).

**D7 — Resolve the synthesized tap in the LLM hook, before it is returned.**
`resolveNewAction` reads `getResolvedGUITreeAction()` + `assertNotNull` for every `isModelAction()==true` action (`StatefulAgent.java:1354-1357`), and `MODEL_LLM_TAP.isModelAction()==true`. A freshly-constructed `LlmTapAction` has `resolvedGUITreeAction==null` (it never passes through `validateAllNewActions`, being absent from `state.getActions()`), so the assertion would throw. The hook therefore calls `newState.resolveAction(this, result, getThrottleForNewAction(newState, result))` when `result.getType()==MODEL_LLM_TAP`; for a targetless action this dispatches to `resolveAt(timestamp, throttle, latestTree, null, null)` (`State.java:388-393`), setting `resolvedGUITreeAction = new GUITreeAction(tree, null, action)` — the identical resolution path `MODEL_MENU`/`MODEL_BACK` take. The guard on `getType()==MODEL_LLM_TAP` is required: a **matched widget** action returned by the same hook is already resolved and MUST NOT be re-resolved (re-resolution would re-pick its node). *Alternative rejected*: resolving inside `resolveNewAction` (a shared `StatefulAgent` method) — leaks LLM-specific knowledge into the base agent; the hook is the LLM-aware layer. *Note*: `validateResolvedAction` (`MonkeySourceApe.java:633`, throwing `default`) is **not** on the tap path — it is only called from `validateNewAction` over `state.getActions()` members, which the synthesized tap is not.

**D8 — The synthesized tap is an *ephemeral* action: dispatched and recorded, never part of the model's action inventory.**

Membership in `State.getActions()` — not the `ActionType` — is what makes an action a first-class citizen of the model. It gates five mechanisms, and the synthesized tap is outside all of them:

| Mechanism | Site | Effect on the tap |
|---|---|---|
| `unvisitedActions` + `setGraphId` + `actionCounters` | `Graph.addActions` (`Graph.java:275-284`) | never registered → no graphId, never counted |
| `setValid(true)` | `validateNewAction` (`StatefulAgent.java:1328-1340`), run only over `newState.getActions()` (`:1342-1347`) | stays **invalid** (`Action.valid` defaults false, `Action.java:65`) |
| selection | `randomlyPickAction` / priority loops over the state's actions | never selectable |
| path traversal | every SATA filter gates on `ActionFilter.ENABLED_VALID` (`SataAgent.java:87,119`) | permanently invalid ⇒ **never traversable/replayable** |
| `validateResolvedAction` (throwing `default`, `MonkeySourceApe.java:634-656`) | called only from `validateNewAction` over state members | never reached |

Two consequences follow, and they are what this decision fixes:

1. **`Graph.markVisited` must tolerate it.** Both branches (`Graph.java:576-594`) assume registration: the unvisited branch requires `unvisitedActions.remove(action)` to succeed, and the fallback requires `visitedActions.contains(action)` — an unregistered model action hits `RuntimeException: sanity check failed, action should be added`. This is exactly what the 6.3 smoke crashed on, from **both** call sites (`StatefulAgent.java:736` at selection, `Graph.addTransition:440` at edge recording). `Action.isEphemeral()` (a type predicate, sibling of the existing `isBack()`, `Action.java:117`) gates an early branch that records the visit timestamp on the action itself and returns, touching neither set.
2. **Refinement must skip it.** Guarded at the single choke point `Model.java:342`, beside the existing `isBack()` guard (`// back should be deterministic`) — `NamingFactory.resolveNonDeterminism` is reachable only through `Model.resolveNonDeterministicTransitions` (`Model.java:347`), so one guard suffices. With D3 this fires rarely (only a repeated *identical* coordinate with a differing outcome), but a canvas is genuinely non-deterministic and no naming refinement can fix it.

The tap therefore is, by design, an **observational** edge: it happens once, it is recorded for post-hoc analysis, and it never re-enters exploration. Its permanent invalidity is what enforces that — verified on-device (the smoke printed the tap as `, INVALID, UNVISITED`).

*Alternative rejected*: registering the tap in `State.actions` so the bookkeeping works naturally. It routes the tap into `validateAllNewActions` → `validateResolvedAction` → `default: throw new RuntimeException("Should not reach here")` (a second crash to patch), and — once valid — makes it selectable and traversable, so SATA would replay an LLM coordinate without the LLM. It also mutates a state's action set after the coverage tracker and naming have consumed it.

## API Design

### `LlmTapAction(State state, int pixelX, int pixelY, boolean longClick)`
- Extends `ModelAction`; `super(state, /*target*/ null, ActionType.MODEL_LLM_TAP)`.
- Preconditions: `state != null`; `(pixelX, pixelY)` in device bounds and non-degenerate (guaranteed by call site D4).
- Accessors: `getPixelX()`, `getPixelY()`, `isLongClick()`.
- Postconditions: `getType() == MODEL_LLM_TAP`, `requireTarget() == false`, `getTarget() == null`.

### `ModelAction LlmRouter.mapToModelAction(int pixelX, int pixelY, String actionType, String text, List<ModelAction> actions, State state, int deviceWidth, int deviceHeight)`
- Unchanged for `back`, boundary reject, bounds-containment, euclidean-fallback, and `type_text`.
- **Changed postcondition**: when no bounds-containment and no euclidean match is found **and** `actionType ∈ {click, long_click}`, returns `new LlmTapAction(state, pixelX, pixelY, "long_click".equals(actionType))` instead of null. For `type_text` (and any other type), returns null as before.

### `LlmRouter.selectAction(...)` outcome classification
- `match instanceof LlmTapAction` → `result=llm_tap`; increment `llmTapCount`.
- `match != null` (widget) → `result=matched` (unchanged).
- `match == null` → `result=no_match`, plus `reason=degenerate` if `parsed.getX()==0 && parsed.getY()==0`, else `reason=boundary`.

**Summary denominator (`printSummary`)**: `llmTapCount` is a completed mapping outcome, so it MUST join the `decisions` denominator: `decisions = matchedCount + llmTapCount + noMatchCount + nullCount`. This keeps the denominator stable across the change — an off-tree event that was previously counted as `no_match` is now counted as `llm_tap`, both inside `decisions` — so the `[APE-RV] LLM Decision ratio` (`matched/decisions`, widget-match rate) remains comparable to pre-change runs. The numerator stays `matchedCount` (widget matches only); `llm_tap` counts in the denominator but not the numerator, so the ratio correctly reads as "fraction of decisions that resolved to a widget."

## Data Flow

1. Vision model → `ToolCallParser.ParsedAction { actionType, x, y, text }`.
2. `CoordinateNormalizer.normalize` → device pixels `(pixelX, pixelY)`.
3. `mapToModelAction` → matched widget action | `LlmTapAction` | null.
4. Router sets nothing extra; `SataAgent` LLM hook sets `DecisionSource.LLM`, and — because the tap is `isModelAction()==true` and not a member of `state.getActions()` — **resolves it** via `newState.resolveAction(...)` (targetless branch) so `resolvedGUITreeAction` is non-null, then returns the action (D7).
5. `resolveNewAction` reads the now-resolved `GUITreeAction` (no NPE); `MonkeySourceApe.generateEventsForActionInternal` dispatches the raw tap; the destination `GUITree` is captured.
6. Model records `StateTransition(source, LlmTapAction, destinationState)` — the labeled edge.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `ClassCastException` on the event branch | `MODEL_LLM_TAP` dispatched with a non-`LlmTapAction` | Prevented by construction — only `LlmRouter` creates `MODEL_LLM_TAP`, always as `LlmTapAction` | N/A (invariant) |
| `NullPointerException` in `resolveNewAction` (`assertNotNull(newGUITreeAction)`) | Synthesized tap is `isModelAction()==true` but unresolved | Prevented by D7 — the hook resolves the tap (`newState.resolveAction`) before returning it, so `getResolvedGUITreeAction()` is non-null | N/A (invariant, covered by `SataAgentLlmTapTest`) |
| `RuntimeException: sanity check failed, action should be added` | `Graph.markVisited` on the unregistered tap (both call sites) | Prevented by D8 — `isEphemeral()` early branch records the timestamp and returns without touching `unvisitedActions`/`visitedActions` | N/A (invariant, covered by `GraphEphemeralActionTest` + smoke 6.3) |
| Spurious naming refinement / model rebuild | Tap edge typed `NEW_ACTION_TARGET` and read as non-determinism | Prevented by D3 (coordinate in identity ⇒ distinct edges) and D8 (`isEphemeral()` guard at `Model.java:342`) | N/A (invariant) |
| Tap lands on empty canvas region (no app reaction) | Vision model imprecision | Destination `GUITree` equals source; edge is a self-loop with `hittingCount++` | Normal exploration continues; SATA resumes next step |
| `type_text` off-tree | LLM emits text on a nodeless target | `mapToModelAction` returns null (`no_match`) — no text injection possible | SATA fallback (unchanged) |

## Risks / Trade-offs

- **[Off-tree tap edge has no source widget]** → The `StateTransition` records `LlmTapAction` (target=null); coverage/refinement machinery treats it as targetless (guarded by `!requireTarget()`, exactly as `MODEL_MENU`). The destination `GUITree` is still captured, so state discovery is unaffected. Accepted per the user's decision (exploration capability over model-graph purity).
- **[Raw taps on system-drawn overlays]** → Boundary reject still filters the status/nav bands, so system UI is not tapped. Off-tree taps are confined to the in-bounds content region.
- **[Graph growth]** → With the coordinate in identity (D3), each distinct off-tree coordinate adds one action key and its edge(s) to the graph. These are inert (permanently invalid ⇒ never selected, never traversed — D8) and bounded by the number of LLM taps in the run, which the router's rate limits already cap. Accepted: faithfulness over graph compactness, and it is what removes the phantom-non-determinism at the root.
- **[`MODEL_LLM_TAP` reads 0 in the `actionCounters` histogram]** → `Graph.addActions` is the only site that counts action types (`Graph.java:279`), and the tap never passes through it. The count lives in the router instead (`llmTapCount`, `[APE-RV] LLM Summary`). Accepted: the histogram's contract is "actions registered in the model", which the tap deliberately is not.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | `ActionType` predicates for `MODEL_LLM_TAP` (`requireTarget=false`, `isModelAction=true`, `isScroll=false`) | Plain enum assertions | ~3 |
| Unit | `LlmTapAction` constructs targetless, accessors; identity includes `(pixelX, pixelY, longClick)` — different coordinates are unequal, identical payloads are equal with equal `hashCode`, and `getClass` isolation from `MODEL_MENU` holds (D3) | JUnit | ~5 |
| Unit | `Action.isEphemeral()` is true only for `MODEL_LLM_TAP` (D8) | Plain enum/action assertions | ~2 |
| Unit | `Graph.markVisited` accepts an ephemeral tap without throwing and leaves `unvisitedActions`/`visitedActions` untouched; an unregistered **non**-ephemeral model action still throws (the invariant stays sharp) | `new Graph()` + `Unsafe`-allocated `State` (`RebuildCountTest` fixture) | ~3 |
| Unit | `mapToModelAction` off-tree click/long_click → `LlmTapAction`; type_text/back/boundary still null | Mocked `State`/`actions` (existing `LlmRouter` test fixtures) | ~5 |
| Unit | `selectAction` telemetry: `result=llm_tap`, `result=no_match reason=degenerate|boundary`, `llmTapCount` in summary and inside the `decisions` denominator | Capture `Logger` output | ~4 |
| Unit | Hook resolves the synthesized tap: after the LLM hook returns an `LlmTapAction`, `getResolvedGUITreeAction()` is non-null and it survives `resolveNewAction` without NPE; a matched widget action is not re-resolved | Mocked `newState`/router (existing `SataAgent` fixtures) | ~2 |
| Device | `MODEL_LLM_TAP` dispatches a real tap and produces a labeled edge on a canvas app (`thumbkey` / `AndorsTrail`) | Standalone run, inspect `.trace` for `result=llm_tap` and the edge | smoke |

## Open Questions

- Should a consecutive-`MODEL_LLM_TAP` cap be added if on-device runs show a canvas absorbing many taps without state change? Deferred to post-smoke observation (D6).
- `long_click` off-tree currently dispatches a long press at the coordinate; if the vision model rarely emits `long_click` for canvas elements, this branch may be exercised infrequently — confirm on-device before investing further.
