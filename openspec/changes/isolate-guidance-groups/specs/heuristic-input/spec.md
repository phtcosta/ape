# Delta: Heuristic Input (isolate-guidance-groups)

## Purpose

`isolate-guidance-groups` mounts APE-RV's behaviour groups by plan declaration (design D-B1, D-B6). The text an input action enters came from three generators chosen by two different mechanisms: artifact-typed text read `MopData` under the plan's `fuzzInputTyped`, and the heuristic generator read `Config.heuristicInput` (`agent/ApeAgent.java:236-252`) outside the plan. The "unguided" arm therefore consumed static-analysis data through typed input (plan §2.1), and a plan that switched the ADDONS group off would have left the heuristic generator on. Which generator produced a step's text was not recorded.

After this change the heuristic generator is the ADDONS member `Feature.HEURISTIC_INPUT`, read from the plan; typed text is the STATIC member `Feature.TYPED_FUZZ`, read from `StaticGuiModel`; and the origin of the text is recorded in the step record as `dec.inp` (`event-sink`).

## Invariants

- **INV-INP-02** (amended by `isolate-guidance-groups`): When `Feature.HEURISTIC_INPUT` is not in the plan and typed input does not apply to the node, `ApeAgent.generateInputText()` SHALL use `StringCache.nextString()`.
- **INV-INP-08**: The input generators SHALL be selected from the resolved plan only: `RunContext.current().spec().has(Feature.TYPED_FUZZ)` and `has(Feature.HEURISTIC_INPUT)`. No input path SHALL read `Config.heuristicInput`, and `Config` SHALL NOT declare it.
- **INV-INP-09**: Typed input SHALL read widget `inputType`/`hint` from `StaticGuiModel` only, never from `MopData`, and SHALL apply only when `Feature.TYPED_FUZZ` is in the plan.
- **INV-INP-10**: Every step on which `ApeAgent.checkInput()` sets a node's input text SHALL record the text's origin as `dec.inp`, one of `typed`, `heuristic`, `random`, `form`, `llm`, through `EventSink.input(...)`; a step that sets no text SHALL carry no `dec.inp`. Recording SHALL draw no RNG and SHALL NOT generate a second text.

## MODIFIED Requirements

### Requirement: ApeAgent.checkInput() Integration

`ApeAgent.checkInput()` SHALL generate text for an `EditText` node whose `getInputText()` is `null` using `generateInputText(node)`, which selects the generator from the plan, in this order:

1. **typed** — when `Feature.TYPED_FUZZ` (STATIC, key `ape.fuzzInputTyped`) is in the plan and `StaticGuiModel` holds a non-empty `inputType` or `hint` for the node or one of its containment nodes (`MopScorer.containmentNodes`, node first): `TypedInputGenerator.generateForType(inputType, hint, …)`;
2. **heuristic** — otherwise, when `Feature.HEURISTIC_INPUT` (ADDONS, key `ape.heuristicInput`) is in the plan: `InputValueGenerator.generateForNode(node)`;
3. **random** — otherwise: `StringCache.nextString()`.

Without the static-analysis artifact, or with the STATIC group off, the first step never applies; with the ADDONS group off, the second never applies. In the `base` cell every generated text is therefore `StringCache.nextString()`.

`checkInput()` SHALL gate whether a node is filled on the form-completion context (defined by the `form-completion` capability: `Feature.FORM_COMPLETION` is in the plan and the current state carries at least one resolved, valid `EditText` action whose node holds no text — `getInputText() == null` and `getText()` null or empty). The context SHALL be evaluated against the state reflecting the current screen at `checkInput` time (`currentState`; INV-FORM-07):

- When the form-completion context holds for the current state, `checkInput()` SHALL fill the selected unfilled `EditText` node deterministically — it SHALL set the node's input text without evaluating the `RandomHelper.toss(ape.inputRate)` gate.
- When the form-completion context does NOT hold, `checkInput()` SHALL retain the legacy probabilistic behavior — it SHALL set the node's input text only when `RandomHelper.toss(ape.inputRate)` succeeds.
- An LLM decision on an input-capable widget fills deterministically, as before, with the same generator.

In every case the text source is `generateInputText(node)`, and a node that already holds text (`getInputText() != null` or non-empty `getText()`) SHALL NOT be re-filled. The generator choice is independent of the form-completion gate.

When `checkInput()` sets a node's input text, it SHALL record the origin in the step record's `dec.inp` (`event-sink`, INV-INP-10), by the first of these that holds:
- `llm` — the fill is an LLM decision's text entry;
- `form` — the fill bypassed the toss because the form-completion context holds;
- otherwise the generator that produced the text: `typed`, `heuristic` or `random`.

#### Scenario: Heuristic input enabled
- **WHEN** `Feature.HEURISTIC_INPUT` is in the plan (cell `as`, or `ape.addons=true` with `ape.heuristicInput` unset), typed input does not apply to the node, and an EditText action is selected with `getInputText() == null` outside a form context, and the `inputRate` toss succeeds
- **THEN** `InputValueGenerator.generateForNode(node)` SHALL be called to generate the input text
- **AND** the step record SHALL carry `dec.inp:"heuristic"`

#### Scenario: Heuristic input disabled
- **WHEN** `Feature.HEURISTIC_INPUT` is not in the plan (cell `base`, or `ape.heuristicInput=false`) and an unfilled EditText action is filled outside a form context
- **THEN** `StringCache.nextString()` SHALL be used as the text source (upstream behavior)
- **AND** the step record SHALL carry `dec.inp:"random"`

#### Scenario: Deterministic fill in form-completion context
- **WHEN** `Feature.FORM_COMPLETION` is in the plan, the form-completion context holds for the current state, `ape.inputRate` is `0.8`, and an unfilled `EditText` action is selected
- **THEN** `checkInput()` SHALL set the node's input text with probability 1.0 (the `RandomHelper.toss(ape.inputRate)` gate SHALL NOT be evaluated)
- **AND** the text SHALL come from `generateInputText(node)`
- **AND** the step record SHALL carry `dec.inp:"form"`

#### Scenario: Legacy probabilistic fill outside form-completion context
- **WHEN** the form-completion context does NOT hold and an unfilled `EditText` action is selected with `ape.inputRate == 0.8`
- **THEN** `checkInput()` SHALL set the node's input text only when `RandomHelper.toss(0.8)` succeeds (legacy behavior)
- **AND** when the toss fails the step record SHALL carry no `dec.inp`

#### Scenario: Already-filled node is not re-filled
- **WHEN** the selected `EditText` node already has `getInputText() != null`
- **THEN** `checkInput()` SHALL NOT change the node's input text, regardless of the form-completion context
- **AND** the step record SHALL carry no `dec.inp`

#### Scenario: Typed input reads the static provider
- **WHEN** `Feature.TYPED_FUZZ` is in the plan, `StaticGuiModel` holds `inputType="textEmailAddress"` for the node's resource id, and the toss succeeds outside a form context
- **THEN** the text SHALL come from `TypedInputGenerator.generateForType("textEmailAddress", …)`
- **AND** no `MopData` method SHALL be called to obtain it
- **AND** the step record SHALL carry `dec.inp:"typed"`

#### Scenario: Typed input off without the STATIC group
- **WHEN** the plan is cell `mop` (artifact loaded, STATIC off) and the artifact holds `inputType="number"` for the selected EditText
- **THEN** typed input SHALL NOT apply, and the text SHALL come from `StringCache.nextString()` (ADDONS is off too)

### Requirement: Config Flag for Heuristic Input

The heuristic generator SHALL be the plan feature `Feature.HEURISTIC_INPUT`, a member of the ADDONS group, activated by the plan key `ape.heuristicInput` (rule `TRUE`: active when the key is `true`; jar default `true`) and requiring `Feature.ADDONS` (`ape.addons`). `Config` SHALL NOT declare `heuristicInput`, and no input path SHALL read it outside the plan (INV-INP-08). The resolution rules are the plan's (`run-spec`, `guidance-groups`):

| `ape.addons` | `ape.heuristicInput` | `HEURISTIC_INPUT` in the plan |
|---|---|---|
| `false` (default) | unset | no (member left out silently: its group is off) |
| `false` | `true` (explicit) | resolution aborts with `missing_dependency` |
| `true` | unset | yes |
| `true` | `false` | no |

The ADDONS block of the cell presets sets `heuristicInput=true`, so it is in the plan of `as`, `as_mop`, `as_llm` and `as_mop_llm`, and absent from `base` and `mop`.

#### Scenario: Config flag loaded
- **WHEN** `ape.properties` contains `ape.addons=true` and `ape.heuristicInput=false`
- **THEN** the resolved plan SHALL NOT contain `Feature.HEURISTIC_INPUT`
- **AND** `generateInputText()` SHALL NOT call `InputValueGenerator`

#### Scenario: Default value
- **WHEN** `ape.heuristicInput` is not set in properties and `ape.addons=true`
- **THEN** the resolved plan SHALL contain `Feature.HEURISTIC_INPUT`
- **AND** with `ape.addons` unset as well, the resolved plan SHALL NOT contain it

#### Scenario: explicit key without its group aborts
- **WHEN** `ape.properties` contains `ape.heuristicInput=true` and no `ape.addons`
- **THEN** resolution SHALL abort with `[APE-RUNSPEC-ABORT] reason=missing_dependency key=ape.heuristicInput`
