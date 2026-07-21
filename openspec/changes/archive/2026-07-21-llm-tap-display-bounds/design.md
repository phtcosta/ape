# Design: llm-tap-display-bounds

## Context

`llm-tap-injection` (archived `2026-07-20`, commit `f0bae7b`) fixed the zero-area rect so
`MODEL_LLM_TAP` can inject, but kept the guard's bounds source untouched and flagged it as a risk.
The gate then measured it: 6/7 thumbkey taps dropped via the `getVisibleBounds(nodeRect) == null`
branch, all in the IME band (`y ≥ 1487`) — inside the display, outside the app window's root-node
bounds. In cmpm, this branch consumed ~4,550 of the v2 arm's 9,160 tap drops.

Why root-node bounds are wrong for taps: the coordinate is produced from a **full-display
screenshot** (`ScreenshotCapture` → SurfaceControl; `CoordinateNormalizer` maps qwen 0–1000 space
onto display pixels). The tap's raison d'être is off-tree elements, which are frequently
**cross-window** (IME keyboard, system dialogs) — precisely the region the root-node domain
excludes. For node actions the root-node domain stays correct: their rects come from the
accessibility tree of the app window itself.

Constraints: P1 simplicity (no flags); single guard body and single drop-log line
(`[APE-RV] off-screen action dropped`) for both action families; geometry decisions stay in
`LlmTapAction` (JVM-testable — `MonkeySourceApe` cannot be instantiated in unit tests, the gap that
shipped A1); baseline suite 651/0/19 stays green.

## Goals / Non-Goals

**Goals**
1. A `MODEL_LLM_TAP` whose pixel lies inside `AndroidDevice.getDisplayBounds()` injects, even when
   the pixel is outside the app root-node bounds.
2. A pixel outside the display still drops with the existing log line.
3. Node-action guard behavior is byte-for-byte unchanged.

**Non-Goals**
- Excluding system-UI bands (status/nav bar) from the tap domain — speculative carve-outs the LLM
  rarely targets; existing `checkAppActivity`/foreign-activity machinery recovers if a tap leaves
  the app. Revisit only if a gate or campaign measures real harm.
- Changing `getVisibleBounds()` semantics for node actions (INV-EXPL-19 node domain).
- Visit-crediting of dropped actions (markVisited reorder — separate, deferred debt).
- rv-android/campaign documents.

## Mapping: Spec → Implementation → Test

| Spec item | Implementation | Test |
|---|---|---|
| MODIFIED "LLM Tap Injectable Rect" (`clipToDisplay` contract) | `LlmTapAction.clipToDisplay(Rect)` | `LlmTapActionTest` new cases: in-display interior; IME-band pixel `(424,1618)` vs display `1080×1920` (the exact gate-dropped coordinate); exclusive right edge `(1080,500)` → null; `null` display → null |
| MODIFIED "Off-Screen Action Handling" (coordinate domain = display) + amended INV-EXPL-30 | `MonkeySourceApe` `MODEL_LLM_TAP` case passes `tap.clipToDisplay(AndroidDevice.getDisplayBounds())` to the new explicit-bounds `generateClickEventAt` overload | device gate (JVM cannot instantiate `MonkeySourceApe`); overload delegation is a 2-line mechanical change reviewed by inspection |
| Node-action behavior unchanged | 3-arg `generateClickEventAt` delegates to the 4-arg overload with `getVisibleBounds(nodeRect)` | existing suite (651) green + gate scenario "on-screen node unchanged" |

## Decisions

1. **Explicit-bounds overload over branching inside the guard.** `generateClickEventAt(Rect, long,
   ClickPoint)` keeps its signature and delegates to a new `generateClickEventAt(Rect, long,
   ClickPoint, Rect bounds)` holding the existing body (null-check → drop; contains-check → drop;
   else inject). Callers choose the domain: node paths pass `getVisibleBounds(nodeRect)` (status
   quo), the tap case passes the display clip. One guard body, one log line, no
   action-type `if` inside the guard. Alternative (special-case `MODEL_LLM_TAP` inside the guard
   body) rejected: spreads action-type knowledge into event plumbing.
2. **`clipToDisplay` lives in `LlmTapAction`,** mirroring `toInjectableRect()`: `new
   Rect(displayBounds)` (defensive copy — `Rect.intersect` mutates its receiver), `intersect(
   toInjectableRect())` → clipped rect or `null`. Null-display returns null (drop, never NPE —
   `getDisplayBounds` can in principle fail before the display is ready).
3. **Display bounds fetched per dispatch, not cached.** Rotation changes display orientation
   mid-run; `AndroidDevice.getDisplayBounds()` is already a cheap per-call query used elsewhere.

## API Design

```java
// LlmTapAction
/** Guard bounds for dispatch: display∩tapRect, or null when the pixel is off-display. */
public Rect clipToDisplay(Rect displayBounds)
// pre: none (displayBounds may be null)
// post: null  ⇔ displayBounds == null || pixel outside displayBounds
//       else the non-empty rect (x, y, x+1, y+1); contains(pixelX, pixelY) == true

// MonkeySourceApe
protected void generateClickEventAt(Rect nodeRect, long waitTime, ClickPoint clickPoint)
    // delegates: generateClickEventAt(nodeRect, waitTime, clickPoint, getVisibleBounds(nodeRect))
protected void generateClickEventAt(Rect nodeRect, long waitTime, ClickPoint clickPoint, Rect bounds)
    // existing body, using the passed bounds
```

Dispatch:

```java
case MODEL_LLM_TAP:
    LlmTapAction tap = (LlmTapAction) action;
    generateClickEventAt(tap.toInjectableRect(),
            tap.isLongClick() ? LONG_CLICK_WAIT_TIME : CLICK_WAIT_TIME, ClickPoint.CENTER,
            tap.clipToDisplay(AndroidDevice.getDisplayBounds()));
    break;
```

## Error Handling

| Error | Source | Strategy | Recovery |
|---|---|---|---|
| Tap pixel outside display | LLM coordinate | `clipToDisplay` → null → existing drop + log | next step (unchanged) |
| `getDisplayBounds()` unavailable/null | display not ready | null-tolerant clip → drop + log | next step |
| Tap lands on system UI, app loses foreground | new reachable region | existing `checkAppActivity` restart / foreign-activity guard | app relaunched (pre-existing machinery) |

## Risks / Trade-offs

- [Risk] In-display taps can now open the notification shade / hit nav buttons → bounded by
  `llmPercentage` and by the LLM deciding from an app screenshot; recovery machinery already
  exists; a no-op drop was not protection, it was silent waste. Measured at the gate and in any
  cmpm re-run.
- [Risk] Rotation race: display bounds fetched at dispatch may differ from the screenshot's
  orientation at decision time → pre-existing exposure for all coordinate handling; unchanged
  scope.
- [Trade-off] The dispatch-level pairing (overload receives the right bounds) is not
  JVM-unit-testable (MonkeySourceApe); mitigated by keeping the change mechanical (2 lines) and
  gating on device.

## Testing Strategy

TDD RED→GREEN: new `LlmTapActionTest` cases for `clipToDisplay` (including the exact gate-dropped
coordinate `(424,1618)` against a `1080×1920` display, asserting non-null — RED against a missing
method). Full suite `mvn test` (651/0/19 baseline). Device gate (fresh-install + SGLang v2 on
thumbkey): (i) at least one tap with `y ≥ 1487` (in-display, below app window) injects — no drop
line — and the following step's state differs; (ii) drop lines appear only for out-of-display
coordinates; (iii) node-action behavior unchanged (no new drop pattern for MODEL_CLICK).
