# Proposal: llm-tap-display-bounds

## Why

The `llm-tap-injection` device gate (archive `2026-07-20-llm-tap-injection/verification.md`) measured
the residual debt it had flagged: **6 of 7 LLM taps on thumbkey were still dropped**, all at
`y ≥ 1487` — the IME/keyboard band. The cause is the guard's bounds source: `generateClickEventAt`
validates against `getVisibleBounds()` = the app window's **root-node bounds**, but a
`MODEL_LLM_TAP` coordinate is decided from a **full-display screenshot** (`ScreenshotCapture` /
SurfaceControl, coordinate space `(0,0,w,h)` = `AndroidDevice.getDisplayBounds()`). Cross-window
targets — exactly the off-tree elements the tap exists for (IME keyboards, dialogs in other
windows, Compose surfaces extending under insets) — lie inside the display but outside the app
root node, so the guard rejects the tap's own purpose. In the cmpm campaign this branch accounted
for ~4,550 of the v2 arm's 9,160 drops (~50% of its taps).

## What Changes

- The validity domain of a `MODEL_LLM_TAP` coordinate becomes the **physical display bounds** (the
  screenshot's coordinate space): a tap whose pixel lies inside the display injects; outside it is
  dropped with the existing `[APE-RV] off-screen action dropped` line.
- Node actions (`MODEL_CLICK`, etc.) are untouched: their guard keeps the root-node visible bounds
  (INV-EXPL-19 for nodes is unchanged).
- Geometry stays in `LlmTapAction` (the JVM-testable seam established by `llm-tap-injection`): a
  new `clipToDisplay(Rect displayBounds)` produces the guard bounds; `MonkeySourceApe` passes them
  to a new explicit-bounds overload of `generateClickEventAt` and remains a pass-through.
- No config flags added or changed.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `exploration`: "Off-Screen Action Handling" (coordinate-case validity domain becomes display
  bounds) and "LLM Tap Injectable Rect" (adds the `clipToDisplay` contract); INV-EXPL-30 amended
  accordingly.

## Impact

- `src/main/java/com/android/commands/monkey/ape/model/LlmTapAction.java` (`clipToDisplay`).
- `src/main/java/com/android/commands/monkey/MonkeySourceApe.java` (explicit-bounds
  `generateClickEventAt` overload; `MODEL_LLM_TAP` case passes display-clipped bounds).
- Tests: `LlmTapActionTest` (clip geometry incl. the IME-band case that failed the previous gate).
  Baseline suite 651/0/19 must stay green.
- Behavior trade-off: in-display taps can now land on system UI (status/nav bar) — previously
  those were silent no-ops. Recovery is the existing `checkAppActivity`/foreign-activity
  machinery; the LLM decides from the app's screenshot, so such taps are its own (rare) choice.
- On-device gate before archive: thumbkey fresh-install + SGLang v2 — taps in the IME band
  (`y ≥ 1487`, in-display) must inject (no drop line) and act; a genuinely out-of-display
  coordinate must still drop.
