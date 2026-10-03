# Delta: LLM Infrastructure (llm-coordinate-single-base)

## Purpose

`CoordinateNormalizer` today converts in one direction only: from the model's [0,1000) answer to a device pixel. The opposite conversion, from a widget's pixel centre to the [0,1000) value the prompt shows, was written inline four times. Three copies are in `ApePromptBuilder` (the shared line formatter, the `v13` builder, the `v17` builder) and one is in `StatefulAgent.recordActionHistory`. The copies differ in arithmetic order and, more importantly, in the divisor they were handed: the prompt builder used the active window's root node, the history used the display. That disagreement is the defect `llm-coordinate-single-base` repairs (see the `llm-prompt` delta).

This delta gives the forward conversion one home, next to its inverse, so the two directions are defined together and can be tested against each other. The inverse is unchanged. The forward method is pure integer arithmetic with no Android dependency, like the inverse, so its contract is checkable in a plain JVM unit test.

## Invariants

- **INV-LLM-13**: For every display dimension `d` with `0 < d < 2000` and every pixel `p` with `0 <= p < d`, the round trip `normalize(toNormalized(p))` on one axis SHALL return a pixel `q` with `p - 2 <= q <= p`. Both directions truncate, so the loss is always toward the origin and below `d/1000 + 1` px. This bound is what lets a coordinate copied from the prompt land inside the widget it names.

## MODIFIED Requirements

### Requirement: CoordinateNormalizer — Qwen Coordinates to Device Pixels

`CoordinateNormalizer.normalize(int qwenX, int qwenY, int deviceWidth, int deviceHeight)` SHALL convert coordinates from Qwen3-VL's normalized [0, 1000) space to device pixel coordinates using the formula:

```
pixelX = clamp((int)((qwenX / 1000.0) * deviceWidth), 0, deviceWidth - 1)
pixelY = clamp((int)((qwenY / 1000.0) * deviceHeight), 0, deviceHeight - 1)
```

The method SHALL return an `int[2]` array containing `[pixelX, pixelY]`.

`CoordinateNormalizer.toNormalized(int pixelX, int pixelY, int width, int height)` SHALL be the forward conversion, from a device pixel to Qwen3-VL's [0, 1000) space:

```
normX = clamp((int)(pixelX * 1000.0 / width),  0, 999)
normY = clamp((int)(pixelY * 1000.0 / height), 0, 999)
```

It SHALL return an `int[2]` array containing `[normX, normY]`. It is the only implementation of this conversion in the jar. Every [0, 1000) coordinate shown to the model is computed by it (`llm-prompt` INV-PRM-06), and no other class SHALL carry its own copy of the arithmetic. The clamp maps a pixel outside the display, such as a widget centre behind the navigation bar, to the nearest edge value instead of producing 1000 or more. `width` and `height` SHALL be positive; the caller passes the dimensions `ScreenshotStep.deviceDimensions` returned, which are positive by contract.

The two methods are inverses up to truncation (INV-LLM-13). They SHALL be called with the same `{width, height}` within one decision, because that pair is what makes the model's answer and the prompt's coordinates the same space.

Reference: Qwen3-VL coordinate convention — https://github.com/QwenLM/Qwen3-VL/issues/1486

#### Scenario: Center of 1080x1920 display

- **WHEN** `normalize(500, 500, 1080, 1920)` is called
- **THEN** the returned array SHALL be `[540, 960]`

#### Scenario: Edge clamping

- **WHEN** `normalize(1050, -10, 1080, 1920)` is called
- **THEN** `pixelX` SHALL be clamped to `1079` (deviceWidth - 1)
- **AND** `pixelY` SHALL be clamped to `0`

#### Scenario: forward conversion on the campaign display
- **WHEN** `toNormalized(540, 178, 1080, 1794)` is called
- **THEN** the returned array SHALL be `[500, 99]`

#### Scenario: forward conversion clamps a pixel past the display edge
- **WHEN** `toNormalized(1080, 1857, 1080, 1794)` is called
- **THEN** the returned array SHALL be `[999, 999]`

#### Scenario: round trip stays within two pixels toward the origin
- **WHEN** for every `p` in `[0, 1794)` the value `toNormalized(0, p, 1080, 1794)[1]` is passed back through `normalize(0, n, 1080, 1794)[1]`
- **THEN** every result `q` SHALL satisfy `p - 2 <= q <= p`

#### Scenario: Zero coordinates

- **WHEN** `normalize(0, 0, 1080, 1920)` is called
- **THEN** the returned array SHALL be `[0, 0]`

