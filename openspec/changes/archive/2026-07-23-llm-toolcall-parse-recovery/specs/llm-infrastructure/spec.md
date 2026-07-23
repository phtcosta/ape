# Delta: llm-infrastructure — llm-toolcall-parse-recovery

## Purpose

`ToolCallParser` turns a Qwen3-VL response into an executable `ParsedAction`, applying a set of
pre-parse fixes for the coordinate malformations the model routinely emits (missing `"y"` key,
array coordinates, missing leading zero, truncated braces). The `cmp_llm_20260721` smoke gate
surfaced a malformation the fix set does not cover: the model collapses both coordinates into a
single **unterminated string** under `"x"` — `{"x": "500, 527}` (closing quote sometimes present,
sometimes absent). `FIX_MISSING_Y_KEY` only matches a bare digit after `"x":`, so a leading quote
defeats it; `new JSONObject(...)` then throws `Unterminated string` and `parse()` returns null,
silently dropping the decision to SATA. Across the four smoke apps this was 7/7 of the observed
`cause=parse` failures, all recoverable — the intended `(x, y)` is fully legible.

This delta adds the quoted-collapsed-XY fix (ordered before `FIX_MISSING_Y_KEY`) and a
form-independent last-resort recovery that, when a still-unparseable object names a tap action
(`click`/`long_click`), takes the first two standalone integers in the `arguments` region as
`(x, y)`. It also makes recovery
observable: the parser records **which repair, if any, a successful parse required**, so the
downstream telemetry (`llm-routing` capability) can keep raw tool-call fidelity — clean versus
repaired — measurable after the parser is hardened. This matters because the base 4B model emits
these malformations and a fine-tune is expected to emit fewer; hardening the parser must not erase
the signal that distinguishes them. No config flags are added; the never-throw / null-on-total-
failure contract of `parse()` is preserved.

## Invariants

- **INV-LLM-04** (amended): `ToolCallParser.parse()` SHALL attempt all 3 fallback levels (native
  tool calls → XML tags → inline JSON) before returning null, and SHALL never throw an exception to
  the caller. The added pre-parse fixes (quoted-collapsed-XY) and the last-resort two-integer
  extraction SHALL preserve this contract: each is best-effort, wrapped so that any failure leaves
  the prior behavior intact, and `null` is returned only when no level and no recovery yields a
  parseable action. Because the last-resort extraction executes inside the JSON-parse catch block —
  where no outer handler protects it — it SHALL be internally guarded: any failure within it
  (including malformed or over-long integer runs) SHALL result in `null`, never a propagated
  exception.

- **INV-LLM-09** (new): A successful `parse()` SHALL carry exactly one repair-form label from the
  closed vocabulary `{ none, missing_y, array_xy, quoted_xy, int_scan }`, where `none` means the
  tool call parsed with no pre-parse fix having altered the coordinate structure. The label SHALL
  be the highest-precedence coordinate-structure fix that altered the string, resolved by the
  precedence `int_scan > quoted_xy > array_xy > missing_y > none` (last-resort extraction and the
  more structurally-invasive rewrites rank higher). The cosmetic fixes (leading-zero, brace
  balancing) SHALL NOT be labeled: the leading-zero rewrite never rescues a parse (org.json's
  tokenizer already coerces `.91`-style values) and never alters a coordinate, so it carries no
  fidelity signal. A `null` return carries no label. The label SHALL be readable by the caller
  without re-parsing the response.

## MODIFIED Requirements

### Requirement: ToolCallParser — 3-Level Fallback Parser

`ToolCallParser.parse(ChatResponse response)` SHALL extract a tool call from the LLM response using a 3-level fallback strategy:

1. **Native format**: Check `response.getToolCalls()` for pre-parsed tool calls from SGLang
2. **XML tag format**: Search response text for `<tool_call>JSON</tool_call>` or `<function_call>JSON</function_call>` tags (Qwen3-VL generates this ~50% of the time)
3. **Inline JSON format**: Find the first balanced JSON object containing both `"name"` and `"arguments"` keys

Before parsing JSON at any level, the parser SHALL apply Qwen3-VL malformed JSON fixes:
- Quoted-collapsed-XY: `{"x": "540, 399}` or `{"x": "540, 399"}` → `{"x": 540, "y": 399}` (both coordinates collapsed into one string under `"x"`, opening quote always present, closing quote optional). This fix SHALL run before the missing-"y"-key fix, because the leading quote otherwise defeats that pattern and leaves an unterminated string for `org.json`.
- Missing "y" key: `{"x": 540, 399}` → `{"x": 540, "y": 399}`
- Array format: `{"x": [540, 399]}` → `{"x": 540, "y": 399}`
- Missing leading zero: `": .91` → `": 0.91`
- Truncated JSON: add missing closing braces

When, after all fixes, `org.json` still cannot parse an object that names a tap action (`click`, `long_click`), the parser SHALL apply a last-resort recovery: extract the first two standalone integers (1–4 digit runs) appearing in the `arguments` region and use them as `(x, y)`. This recovers coordinate malformations not covered by a specific fix pattern without depending on the exact malformed form. It SHALL be attempted only after the regex fixes fail to yield a parseable object, and SHALL itself return null (never throw) if no gated action name or fewer than two integers are present. The gate is restricted to the two tap actions because they are the only ones whose recovery is a complete, correctly-executable action: `scroll` is not in the advertised toolset and has no router dispatch (it would execute as a tap — a wrong gesture), a `type_text` without its unrecoverable `text` is a wasted step, and `back` has no coordinate semantics.

JSON parsing SHALL use `new JSONObject(fixedJson)` and field extraction via `obj.optString("name")`, `obj.optInt("x")`, etc.

The returned `ParsedAction` SHALL contain `actionType` (String — one of "click", "long_click", "scroll", "type_text", "back"), `x` and `y` (int, in [0,1000) normalized Qwen3-VL space), optional `text` (String, for type_text actions), and a repair-form label (per INV-LLM-09) naming the fix a successful parse required, or `none`.

#### Scenario: Native tool call format

- **WHEN** `parse(response)` is called and `response.getToolCalls()` contains a valid tool call with `name="click"` and `arguments={"x": 540, "y": 399}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=540`, `y=399`
- **AND** its repair-form label SHALL be `none`

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

- **WHEN** `response.getContent()` contains `<tool_call>{"name": "click", "arguments": {"x": "500, 527}}</tool_call>` (the exact form dropped 7/7 at the `cmp_llm_20260721` smoke gate — opening quote, no closing quote)
- **THEN** the parser SHALL fix the value to `{"x": 500, "y": 527}` before `org.json` sees it
- **AND** return a `ParsedAction` with `actionType="click"`, `x=500`, `y=527` and repair-form label `quoted_xy`
- **AND** no exception SHALL propagate and `parse()` SHALL NOT return null

#### Scenario: Quoted-collapsed-XY, closing quote present

- **WHEN** the response contains `{"name": "click", "arguments": {"x": "820, 590"}}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=820`, `y=590` and repair-form label `quoted_xy`

#### Scenario: Bare collapsed coordinates unaffected by the quoted fix

- **WHEN** the response contains `{"name": "click", "arguments": {"x": 932, 71}}` (bare, no quotes)
- **THEN** the quoted-collapsed-XY fix SHALL NOT alter the string
- **AND** the missing-"y"-key fix SHALL produce a `ParsedAction` with `x=932`, `y=71` and repair-form label `missing_y` (no regression from this delta)

#### Scenario: Last-resort integer extraction

- **WHEN** the response contains `{"name": "click", "arguments": {"x": = 265, "y": 687}}` (equals-sign malformation — documented in the reference parsers, unparseable by `org.json` after every regex fix)
- **THEN** the parser SHALL return a `ParsedAction` with `actionType="click"`, `x=265`, `y=687` and repair-form label `int_scan`

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
