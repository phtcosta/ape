## MODIFIED Requirements

### Requirement: ApeAgent.checkInput() Integration

`ApeAgent.checkInput()` SHALL generate text for an `EditText` node whose `getInputText()` is `null` using `generateInputText(node)` (which selects `InputValueGenerator.generateForNode(node)` when `Config.heuristicInput` is `true`, else `StringCache.nextString()`).

`checkInput()` SHALL gate whether a node is filled on the form-completion context (defined by the `form-completion` capability: the current state carries at least one resolved, valid `EditText` action whose node `getInputText() == null`):

- When the form-completion context holds for the current state, `checkInput()` SHALL fill the selected unfilled `EditText` node deterministically — it SHALL set the node's input text without evaluating the `RandomHelper.toss(ape.inputRate)` gate.
- When the form-completion context does NOT hold, `checkInput()` SHALL retain the legacy probabilistic behavior — it SHALL set the node's input text only when `RandomHelper.toss(ape.inputRate)` succeeds.

In both cases the text source is unchanged (`generateInputText(node)`), and a node that already has `getInputText() != null` SHALL NOT be re-filled. `Config.heuristicInput` continues to select the generator within `generateInputText()` and is independent of the form-completion gate.

#### Scenario: Heuristic input enabled
- **WHEN** `Config.heuristicInput` is `true` and an EditText action is selected with `getInputText() == null`
- **THEN** `InputValueGenerator.generateForNode(node)` SHALL be called to generate the input text

#### Scenario: Heuristic input disabled
- **WHEN** `Config.heuristicInput` is `false` and an unfilled EditText action is filled
- **THEN** `StringCache.nextString()` SHALL be used as the text source (identical to pre-change behavior)

#### Scenario: Deterministic fill in form-completion context
- **WHEN** the form-completion context holds for the current state, `ape.inputRate` is `0.8`, and an unfilled `EditText` action is selected
- **THEN** `checkInput()` SHALL set the node's input text with probability 1.0 (the `RandomHelper.toss(ape.inputRate)` gate SHALL NOT be evaluated)
- **AND** the text SHALL come from `generateInputText(node)`

#### Scenario: Legacy probabilistic fill outside form-completion context
- **WHEN** the form-completion context does NOT hold and an unfilled `EditText` action is selected with `ape.inputRate == 0.8`
- **THEN** `checkInput()` SHALL set the node's input text only when `RandomHelper.toss(0.8)` succeeds (legacy behavior)

#### Scenario: Already-filled node is not re-filled
- **WHEN** the selected `EditText` node already has `getInputText() != null`
- **THEN** `checkInput()` SHALL NOT change the node's input text, regardless of the form-completion context

## Invariants

- **INV-INP-04**: `ApeAgent.checkInput()` SHALL bypass the `RandomHelper.toss(ape.inputRate)` gate (deterministic fill) ONLY when the form-completion context holds for the current state. Outside that context the toss gate SHALL be retained. This invariant aligns with INV-FORM-03 of the `form-completion` capability.
