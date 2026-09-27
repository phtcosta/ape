## Purpose

This delta extends one repair rule of `ToolCallParser`, as part of `llm-opaque-screen`. The router's degenerate answers — a tap parsed as `(0,0)`, or with one coordinate at `0` — are not points the model chose: the parser defaults a coordinate it cannot read to `0`. One cause is fixable in the parser. The pre-parse fixes can turn a malformed answer into valid JSON with the coordinates in the wrong place (`{"x": {"x": 288, 587}}` → `{"x": {"x": 288, "y": 587}}`), and the last-resort integer scan, which would recover them, ran only when the JSON did not parse. It now also runs when a tap action parses without a readable `x` or `y`. The change applies on every LLM arm; it is rare (2 of about 20 000 calls in the first 400 E5c traces) and turns a discarded answer into the tap the model gave.


The same delta corrects the screenshot's orientation. `ScreenshotCapture` asked `SurfaceControl` for the display's content with rotation `0`; on a display turned to landscape that returns the framebuffer in its natural orientation, cropped to the landscape rectangle, so the model saw every landscape screen turned 90° and partly cut off while the coordinates were read against the landscape frame. The capture now passes the display's current rotation. In portrait the call is unchanged. It applies on every LLM arm.

## MODIFIED Requirements

### Requirement: ToolCallParser — 3-Level Fallback Parser

`ToolCallParser.parse(ChatResponse response)` SHALL extract a tool call from the LLM response using a 3-level fallback strategy:

1. **Native format**: Check `response.getToolCalls()` for tool calls extracted from the envelope by `SglangClient`
2. **XML tag format**: Search response text for `<tool_call>JSON</tool_call>` or `<function_call>JSON</function_call>` tags (Qwen3-VL generates this ~50% of the time)
3. **Inline JSON format**: Find the first balanced JSON object containing both `"name"` and `"arguments"` keys

**Level 1 SHALL run the same repair pipeline as Levels 2-3 (INV-LLM-10).** When the tool call carries a raw arguments form (`getRawArguments() != null`), Level 1 SHALL rebuild the XML-path intermediate — `{"name": <quoted name>, "arguments": <raw>}`, with the name embedded via `JSONObject.quote` — and parse it through the shared `parseJsonString`, so the pre-parse fixes, the last-resort integer extraction, and the repair-form labeling (INV-LLM-09) apply identically to native tool calls. When the raw form is null or the shared pipeline yields no action, Level 1 SHALL fall back to constructing the action from the pre-parsed arguments map with repair-form label `none` — the pre-delta behavior, preserved verbatim so no input that parses today is lost.

Before parsing JSON at any level, the parser SHALL apply Qwen3-VL malformed JSON fixes:
- Quoted-collapsed-XY: `{"x": "540, 399}` or `{"x": "540, 399"}` → `{"x": 540, "y": 399}` (both coordinates collapsed into one string under `"x"`, opening quote always present, closing quote optional). This fix SHALL run before the missing-"y"-key fix, because the leading quote otherwise defeats that pattern and leaves an unterminated string for `org.json`.
- Missing "y" key: `{"x": 540, 399}` → `{"x": 540, "y": 399}`
- Array format: `{"x": [540, 399]}` → `{"x": 540, "y": 399}`
- Missing leading zero: `": .91` → `": 0.91`
- Truncated JSON: add missing closing braces

When, after all fixes, `org.json` still cannot parse an object that names a tap action (`click`, `long_click`), the parser SHALL apply a last-resort recovery: extract the first two standalone integers (1–4 digit runs) appearing in the `arguments` region and use them as `(x, y)`. This recovers coordinate malformations not covered by a specific fix pattern without depending on the exact malformed form. It SHALL be attempted when the regex fixes fail to yield a parseable object, and SHALL itself return null (never throw) if no gated action name or fewer than two integers are present.

The same last-resort recovery SHALL also be attempted when the fixes **do** yield a parseable object that names a tap action but whose `arguments` lack a readable integer `x` or `y` — the key is absent, or its value is neither a number nor a string `Integer.parseInt` accepts. A fix can turn a malformation into valid JSON with the coordinates in the wrong place: `{"x": {"x": 288, 587}}` becomes `{"x": {"x": 288, "y": 587}}` under the missing-"y" fix, and without this rule `x` (an object) and `y` (absent) both fell to the default `0`, a `(0,0)` answer the router discards as `degenerate`. The scan runs on the response's original arguments text; when it recovers two integers the `ParsedAction` carries them and the repair-form label `int_scan`, and when it does not, the action built from the parsed object is returned unchanged, with its default `0` coordinates and its original label. Actions other than `click`/`long_click`, and tap actions whose `x` and `y` are both readable, are not affected. The gate is restricted to the two tap actions because they are the only ones whose recovery is a complete, correctly-executable action: `scroll` is not in the advertised toolset and has no router dispatch (it would execute as a tap — a wrong gesture), a `type_text` without its unrecoverable `text` is a wasted step, and `back` has no coordinate semantics.

JSON parsing SHALL use `new JSONObject(fixedJson)` and field extraction via `obj.optString("name")`, `obj.optInt("x")`, etc.

The returned `ParsedAction` SHALL contain `actionType` (String — one of "click", "long_click", "scroll", "type_text", "back"), `x` and `y` (int, in [0,1000) normalized Qwen3-VL space), optional `text` (String, for type_text actions), and a repair-form label (per INV-LLM-09) naming the fix a successful parse required, or `none`.

#### Scenario: Native tool call format

- **WHEN** `parse(response)` is called and `response.getToolCalls()` contains a tool call with `name="click"` and raw arguments `{"x": 540, "y": 399}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=540`, `y=399`
- **AND** its repair-form label SHALL be `none`

#### Scenario: Native missing-y string repaired (the dominant degenerate form)

- **WHEN** `response.getToolCalls()` contains `name="click"` with raw arguments `{"x": 616, 891}` (unparseable — its arguments map is empty)
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=616`, `y=891` and repair-form label `missing_y`
- **AND** the parse SHALL NOT collapse to `(0,0)`

#### Scenario: Native quoted-collapsed-XY repaired despite a valid arguments map

- **WHEN** `response.getToolCalls()` contains `name="click"` with raw arguments `{"x": "540, 399"}` (valid JSON — the map holds the string under `x`)
- **THEN** a `ParsedAction` SHALL be returned with `x=540`, `y=399` and repair-form label `quoted_xy`
- **AND** NOT `(0,0)` from `Integer.parseInt` failing on the map value

#### Scenario: Native array coordinates labeled

- **WHEN** `response.getToolCalls()` contains `name="click"` whose envelope arguments were the object `{"x": [540, 399]}`
- **THEN** a `ParsedAction` SHALL be returned with `x=540`, `y=399` and repair-form label `array_xy`

#### Scenario: Native unrecoverable tap falls to the integer scan

- **WHEN** `response.getToolCalls()` contains `name="click"` with raw arguments `{"x": = 265, "y": 687}` (unparseable after every regex fix)
- **THEN** a `ParsedAction` SHALL be returned with `x=265`, `y=687` and repair-form label `int_scan`

#### Scenario: Native fallback preserves pre-delta behavior

- **WHEN** `response.getToolCalls()` contains `name="back"` with raw arguments that neither parse nor qualify for the integer scan (gate admits only tap actions)
- **THEN** Level 1 SHALL fall back to the arguments-map construction and return a `ParsedAction` with `actionType="back"` and repair-form label `none`
- **AND** no exception SHALL propagate

#### Scenario: Native tool call without raw form uses the map path

- **WHEN** `response.getToolCalls()` contains a `ToolCall` constructed without raw arguments (`getRawArguments() == null`) carrying map `{x=540, y=399}`
- **THEN** a `ParsedAction` SHALL be returned with `x=540`, `y=399` and repair-form label `none` (pre-delta path, unchanged)

#### Scenario: XML tag format fallback

- **WHEN** `response.getToolCalls()` is empty
- **AND** `response.getContent()` contains `<tool_call>{"name": "click", "arguments": {"x": 540, "y": 399}}</tool_call>`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=540`, `y=399`
- **AND** its repair-form label SHALL be `none`

#### Scenario: Malformed JSON with missing y key

- **WHEN** the response contains `{"name": "click", "arguments": {"x": 540, 399}}`
- **THEN** the parser SHALL fix the JSON to `{"name": "click", "arguments": {"x": 540, "y": 399}}`
- **AND** return a valid `ParsedAction` with `x=540`, `y=399` and repair-form label `missing_y`

#### Scenario: Quoted-collapsed-XY, closing quote absent

- **WHEN** `response.getContent()` contains `<tool_call>{"name": "click", "arguments": {"x": "500, 527}}</tool_call>` (opening quote, no closing quote)
- **THEN** the parser SHALL fix the value to `{"x": 500, "y": 527}` before `org.json` sees it
- **AND** return a `ParsedAction` with `actionType="click"`, `x=500`, `y=527` and repair-form label `quoted_xy`
- **AND** no exception SHALL propagate and `parse()` SHALL NOT return null

#### Scenario: Quoted-collapsed-XY, closing quote present

- **WHEN** the response contains `{"name": "click", "arguments": {"x": "820, 590"}}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=820`, `y=590` and repair-form label `quoted_xy`

#### Scenario: Bare collapsed coordinates unaffected by the quoted fix

- **WHEN** the response contains `{"name": "click", "arguments": {"x": 932, 71}}` (bare, no quotes)
- **THEN** the quoted-collapsed-XY fix SHALL NOT alter the string
- **AND** the missing-"y"-key fix SHALL produce a `ParsedAction` with `x=932`, `y=71` and repair-form label `missing_y`

#### Scenario: Last-resort integer extraction

- **WHEN** the response contains `{"name": "click", "arguments": {"x": = 265, "y": 687}}` (equals-sign malformation — unparseable by `org.json` after every regex fix)
- **THEN** the parser SHALL return a `ParsedAction` with `actionType="click"`, `x=265`, `y=687` and repair-form label `int_scan`

#### Scenario: Last-resort integer extraction on a parseable object with misplaced coordinates

- **WHEN** the response contains `{"name": "click", "arguments": {"x": {"x": 288, 587} }}` (the missing-"y" fix yields valid JSON whose `x` is an object and whose `y` is absent)
- **THEN** the parser SHALL return a `ParsedAction` with `actionType="click"`, `x=288`, `y=587` and repair-form label `int_scan`
- **AND** the parse SHALL NOT collapse to `(0,0)`

#### Scenario: Tap without any coordinate keeps its defaults

- **WHEN** the response contains `{"name": "click", "arguments": {}}`
- **THEN** the last-resort scan SHALL find no integers and the parser SHALL return a `ParsedAction` with `actionType="click"`, `x=0`, `y=0` and repair-form label `none`, as before

#### Scenario: Last-resort gate excludes non-tap actions

- **WHEN** the response contains an unparseable `{"name": "scroll", "arguments": ...}` object whose `arguments` region holds two legible integers
- **THEN** `null` SHALL be returned (the gate admits only `click`/`long_click`)
- **AND** no exception SHALL propagate

#### Scenario: type_text action

- **WHEN** the response contains `{"name": "type_text", "arguments": {"x": 300, "y": 500, "text": "user@example.com"}}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="type_text"`, `x=300`, `y=500`, `text="user@example.com"` and repair-form label `none`

#### Scenario: long_click action

- **WHEN** the response contains `{"name": "long_click", "arguments": {"x": 450, "y": 600}}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="long_click"`, `x=450`, `y=600` and repair-form label `none`

#### Scenario: All levels fail

- **WHEN** the response contains no parseable tool call at any level and no known action name for last-resort extraction
- **THEN** `null` SHALL be returned
- **AND** no exception SHALL propagate

### Requirement: ScreenshotCapture — SurfaceControl Screenshot

`ScreenshotCapture.capture(int width, int height)` SHALL capture a screenshot of the device display and return it as a PNG byte array. The primary capture method SHALL use `android.view.SurfaceControl.screenshot(Rect, int, int, int)` via reflection (hidden API, available from `app_process` context). If reflection fails, a fallback to `UiAutomation.takeScreenshot()` SHALL be attempted.

The primary method SHALL pass as its rotation argument the display's current rotation (`Surface.ROTATION_0` … `ROTATION_270`, read through `AndroidDevice.getRotation()` from the same real default `Display` whose size is the frame — not from `IWindowManager.getRotation()`, which API 30 no longer has), so the image shows the screen in the orientation it is displayed in, matching the `width`×`height` frame `Display.getSize()` reports for that orientation. A rotation outside `0…3` (the window manager could not be asked) SHALL be replaced by `0`. With rotation `0` — every portrait screen — the call SHALL be the one made before this requirement.

#### Scenario: Successful capture via SurfaceControl

- **WHEN** `capture(1080, 1920)` is called on an Android device with API 28+
- **AND** `SurfaceControl.screenshot()` is accessible via reflection
- **THEN** a non-null byte array containing valid PNG data SHALL be returned
- **AND** the PNG dimensions SHALL match the requested width and height

#### Scenario: Landscape display captured as shown

- **WHEN** the display is at `ROTATION_90` and `capture(1794, 1080)` is called with the frame `Display.getSize()` reports
- **THEN** `SurfaceControl.screenshot` SHALL be called with rotation `1`
- **AND** the image SHALL show the screen upright, as `screencap` shows it, not turned 90° and cropped

#### Scenario: Unknown rotation falls back to zero

- **WHEN** `AndroidDevice.getRotation()` returns `-1`
- **THEN** the rotation argument SHALL be `0`

#### Scenario: SurfaceControl reflection fails

- **WHEN** `SurfaceControl.screenshot()` is not accessible (e.g., API restriction)
- **THEN** the UiAutomation fallback SHALL be attempted
- **AND** if both methods fail, `null` SHALL be returned
- **AND** no exception SHALL propagate
