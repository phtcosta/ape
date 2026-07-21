# Delta: exploration — llm-tap-display-bounds

## Purpose

`llm-tap-injection` made the `MODEL_LLM_TAP` rect injectable, but its device gate measured the
bounds-source debt it had flagged: 6/7 thumbkey taps still dropped because `generateClickEventAt`
validates every action against the app window's root-node bounds (`getVisibleBounds()`), while the
tap coordinate is decided from a full-display screenshot. The off-tree elements the tap exists for
(IME keyboards, other windows, surfaces under insets) are inside the display but outside the app
root node — the guard was rejecting the action's own purpose (~50% of the cmpm v2 arm's taps died
on this branch).

This delta changes the validity domain of a coordinate tap to the **physical display bounds**
(`AndroidDevice.getDisplayBounds()`, the screenshot's coordinate space). Node actions keep the
root-node domain unchanged. The clipping decision lives in `LlmTapAction.clipToDisplay` — the same
JVM-testable seam as `toInjectableRect()` — and `MonkeySourceApe` hands the result to an
explicit-bounds overload of `generateClickEventAt`, keeping a single guard body and a single drop
log line.

## Invariants

- **INV-EXPL-30** (amended wording, manual sync into main spec `## Invariants`): `MODEL_LLM_TAP`
  dispatch SHALL construct a non-degenerate rect `(x, y, x+1, y+1)` for the decided pixel
  `(x, y)`, and its validity domain SHALL be the physical display bounds
  (`AndroidDevice.getDisplayBounds()`), not the app root-node visible bounds. A tap whose pixel
  lies inside the display SHALL enqueue a touch down/up pair; a tap outside it SHALL be dropped
  with the `[APE-RV] off-screen action dropped` log line and no event. Node actions keep the
  root-node visible-bounds domain.

## MODIFIED Requirements

### Requirement: Off-Screen Action Handling

When `generateClickEventAt` finds that the target node's bounds do not intersect the visible screen (`getVisibleBounds` returns null), it SHALL NOT emit any touch event and SHALL log one `[APE-RV] off-screen action dropped: <action>` line. It SHALL NOT substitute the display bounds and click the screen center: that behavior executed an unrelated click while the model credited the original action, creating false edges (260 occurrences measured across 17/1513 baseline runs). The invalid-bounds branch (`!bounds.contains(p)`) keeps its no-event behavior and gains the same log line. Visit/coverage crediting of the dropped action is unchanged in this change (the markVisited-before-event-generation reorder is a separate, deferred item); the log line makes the wasted-step frequency measurable.

For coordinate-carrying actions without a resolved node (`MODEL_LLM_TAP`), the rect handed to
`generateClickEventAt` MUST be the minimal non-degenerate rect anchored at the decided pixel —
`(x, y, x+1, y+1)` — never the zero-area `(x, y, x, y)`: an empty rect is unconditionally dropped by
both guard branches, which silently disables the action (cmpm finding A1: 16,625/16,625 taps
dropped). The validity domain of a coordinate tap is the **physical display bounds**
(`AndroidDevice.getDisplayBounds()`), not the app root-node visible bounds: the coordinate is
decided from a full-display screenshot, and its legitimate targets include cross-window elements
(IME keyboards, dialogs) that lie outside the app root node (measured: 6/7 thumbkey gate taps and
~50% of cmpm v2 taps dropped on the root-node domain). A coordinate outside the display produces
the log line and no event; node actions keep the root-node visible-bounds domain unchanged.

#### Scenario: off-screen node produces no event
- **WHEN** a MODEL_CLICK resolves to a node whose bounds do not intersect the visible screen
- **THEN** no touch event SHALL be enqueued
- **AND** one `[APE-RV] off-screen action dropped` line SHALL be emitted
- **AND** no click SHALL be delivered to the screen center

#### Scenario: on-screen node unchanged
- **WHEN** the node's bounds intersect the visible screen
- **THEN** click generation SHALL be identical to the previous implementation

#### Scenario: in-bounds coordinate tap is not dropped
- **WHEN** a `MODEL_LLM_TAP` carrying pixel `(540, 1158)` is dispatched and the display bounds are
  `(0, 0, 1080, 1920)`
- **THEN** the rect handed to the guard SHALL be `(540, 1158, 541, 1159)`
- **AND** a touch down/up pair SHALL be enqueued at that point
- **AND** no `[APE-RV] off-screen action dropped` line SHALL be emitted

#### Scenario: coordinate tap below the app window but inside the display injects
- **WHEN** the app root-node bounds are `(0, 0, 1080, 1400)`, the display bounds are
  `(0, 0, 1080, 1920)`, and a `MODEL_LLM_TAP` carrying pixel `(424, 1618)` is dispatched (the
  IME band that the llm-tap-injection gate measured as dropped)
- **THEN** a touch down/up pair SHALL be enqueued at `(424, 1618)`
- **AND** no `[APE-RV] off-screen action dropped` line SHALL be emitted

#### Scenario: out-of-display coordinate tap is dropped
- **WHEN** a `MODEL_LLM_TAP` carrying pixel `(1080, 500)` is dispatched and the display bounds are
  `(0, 0, 1080, 1920)` (x equals the exclusive right edge)
- **THEN** no touch event SHALL be enqueued
- **AND** one `[APE-RV] off-screen action dropped` line SHALL be emitted

### Requirement: LLM Tap Injectable Rect

`LlmTapAction` SHALL expose its dispatch geometry as the minimal non-degenerate rect anchored at the
decided pixel: `toInjectableRect()` returns `new Rect(pixelX, pixelY, pixelX + 1, pixelY + 1)`. The
`MODEL_LLM_TAP` case in `MonkeySourceApe` SHALL obtain the rect exclusively through this method (a
single construction site, unit-testable on the JVM), and the resulting click point (rect center,
truncated to int) SHALL equal the decided pixel `(pixelX, pixelY)`.

`LlmTapAction` SHALL also own the guard-bounds decision: `clipToDisplay(Rect displayBounds)`
returns the intersection of the display bounds with `toInjectableRect()` when the pixel lies inside
the display, and `null` when the pixel is outside it or `displayBounds` is `null`. The
`MODEL_LLM_TAP` dispatch SHALL pass this result as the explicit guard bounds of
`generateClickEventAt` (never the root-node `getVisibleBounds()` result), so that the guard body
and the drop log line remain single-sourced for node and coordinate actions alike.

#### Scenario: injectable rect is non-empty and centered on the pixel
- **WHEN** `LlmTapAction` carries `pixelX=853, pixelY=1657` and `toInjectableRect()` is called
- **THEN** the returned rect SHALL be `(853, 1657, 854, 1658)`
- **AND** `rect.isEmpty()` SHALL be `false`
- **AND** `(int) rect.exactCenterX()` SHALL equal `853` and `(int) rect.exactCenterY()` SHALL
  equal `1657`

#### Scenario: injectable rect passes the visibility guard for an interior pixel
- **WHEN** the visible bounds are `(0, 0, 1080, 1920)` and the rect is `(853, 1657, 854, 1658)`
- **THEN** `visibleBounds.intersect(rect)` SHALL yield the non-empty rect `(853, 1657, 854, 1658)`
- **AND** `contains(853, 1657)` on the intersection SHALL be `true`

#### Scenario: clipToDisplay accepts an in-display pixel outside the app window
- **WHEN** `LlmTapAction` carries `pixelX=424, pixelY=1618` and `clipToDisplay(new Rect(0, 0,
  1080, 1920))` is called
- **THEN** the returned rect SHALL be the non-empty `(424, 1618, 425, 1619)`
- **AND** `contains(424, 1618)` on it SHALL be `true`

#### Scenario: clipToDisplay rejects an out-of-display pixel
- **WHEN** `LlmTapAction` carries `pixelX=1080, pixelY=500` and `clipToDisplay(new Rect(0, 0,
  1080, 1920))` is called
- **THEN** the result SHALL be `null`

#### Scenario: clipToDisplay tolerates a null display
- **WHEN** `clipToDisplay(null)` is called
- **THEN** the result SHALL be `null` (the tap is dropped, never NPEs)
