# Delta: exploration — llm-tap-injection

## Purpose

`MODEL_LLM_TAP` exists to act on screen elements the accessibility tree does not expose (Compose
canvases, game surfaces, custom-drawn keyboards). The cmpm forensics
(`docs/20260720_analise_forense_cmpm_traces.md`, finding A1) proved the current dispatch never
delivers the touch: it wraps the decided coordinate in a zero-area `Rect(x, y, x, y)` and routes it
through `generateClickEventAt`, where an empty rect can neither survive `Rect.intersect` as a
non-empty intersection nor satisfy `Rect.contains` — both branches end in the INV-EXPL-19 drop.
Every one of the campaign's 16,625 tap steps was a no-op that cost an LLM call and a full step, and
at `temperature=0` the frozen screen induced repeat-tap loops.

This delta makes the tap injectable while keeping the off-screen protection: the dispatch constructs
the minimal non-degenerate rect anchored at the coordinate, so an in-bounds tap passes the existing
guard unmodified and an out-of-bounds tap is still dropped. The guard requirement is restated to
define the coordinate-action case explicitly.

## Invariants

- **INV-EXPL-19** (amended wording, manual sync into main spec `## Invariants`): No touch event
  SHALL ever be delivered to coordinates derived from bounds other than the resolved node's own
  bounds — or, for coordinate-carrying actions without a node (`MODEL_LLM_TAP`), other than the
  action's own decided coordinate.
- **INV-EXPL-30** (new): `MODEL_LLM_TAP` dispatch SHALL construct a non-degenerate rect
  `(x, y, x+1, y+1)` for the decided pixel `(x, y)`. A tap whose coordinate lies strictly inside the
  visible bounds SHALL enqueue a touch down/up pair; a tap outside them SHALL be dropped with the
  `[APE-RV] off-screen action dropped` log line and no event.

## MODIFIED Requirements

### Requirement: Off-Screen Action Handling

When `generateClickEventAt` finds that the target node's bounds do not intersect the visible screen (`getVisibleBounds` returns null), it SHALL NOT emit any touch event and SHALL log one `[APE-RV] off-screen action dropped: <action>` line. It SHALL NOT substitute the display bounds and click the screen center: that behavior executed an unrelated click while the model credited the original action, creating false edges (260 occurrences measured across 17/1513 baseline runs). The invalid-bounds branch (`!bounds.contains(p)`) keeps its no-event behavior and gains the same log line. Visit/coverage crediting of the dropped action is unchanged in this change (the markVisited-before-event-generation reorder is a separate, deferred item); the log line makes the wasted-step frequency measurable.

For coordinate-carrying actions without a resolved node (`MODEL_LLM_TAP`), the rect handed to
`generateClickEventAt` MUST be the minimal non-degenerate rect anchored at the decided pixel —
`(x, y, x+1, y+1)` — never the zero-area `(x, y, x, y)`: an empty rect is unconditionally dropped by
both guard branches, which silently disables the action (cmpm finding A1: 16,625/16,625 taps
dropped). The drop branches apply to a coordinate tap exactly as to a node action: a coordinate
outside the visible bounds produces the log line and no event.

#### Scenario: off-screen node produces no event
- **WHEN** a MODEL_CLICK resolves to a node whose bounds do not intersect the visible screen
- **THEN** no touch event SHALL be enqueued
- **AND** one `[APE-RV] off-screen action dropped` line SHALL be emitted
- **AND** no click SHALL be delivered to the screen center

#### Scenario: on-screen node unchanged
- **WHEN** the node's bounds intersect the visible screen
- **THEN** click generation SHALL be identical to the previous implementation

#### Scenario: in-bounds coordinate tap is not dropped
- **WHEN** a `MODEL_LLM_TAP` carrying pixel `(540, 1158)` is dispatched and the visible bounds are
  `(0, 0, 1080, 1920)`
- **THEN** the rect handed to the guard SHALL be `(540, 1158, 541, 1159)`
- **AND** a touch down/up pair SHALL be enqueued at that point
- **AND** no `[APE-RV] off-screen action dropped` line SHALL be emitted

#### Scenario: out-of-bounds coordinate tap is dropped
- **WHEN** a `MODEL_LLM_TAP` carrying pixel `(1080, 500)` is dispatched and the visible bounds are
  `(0, 0, 1080, 1920)` (x equals the exclusive right edge)
- **THEN** no touch event SHALL be enqueued
- **AND** one `[APE-RV] off-screen action dropped` line SHALL be emitted

## ADDED Requirements

### Requirement: LLM Tap Injectable Rect

`LlmTapAction` SHALL expose its dispatch geometry as the minimal non-degenerate rect anchored at the
decided pixel: `toInjectableRect()` returns `new Rect(pixelX, pixelY, pixelX + 1, pixelY + 1)`. The
`MODEL_LLM_TAP` case in `MonkeySourceApe` SHALL obtain the rect exclusively through this method (a
single construction site, unit-testable on the JVM), and the resulting click point (rect center,
truncated to int) SHALL equal the decided pixel `(pixelX, pixelY)`.

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
