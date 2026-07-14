## Purpose

APE-RV's `ApeAgent.checkInput()` generates text for `EditText` widgets using `StringCache.nextString()`, which produces random dates, floats, or integers (e.g., "2019-03-15", "3.14159", "42"). For fields requiring specific input types — email addresses, passwords, URLs, phone numbers — this random text fails validation and prevents the agent from triggering code paths behind form submission. Experiment data from rvsmart showed that contextual text input yields +16pp method coverage on sqliteviewer (file path input) and +14pp on hashpass (password input).

The `InputValueGenerator` replaces random text with category-appropriate values. Categories are detected from the widget's `resourceId`, `contentDescription`, and `isPassword()` flag — all available at runtime via `GUITreeNode` without additional instrumentation. Each category maps to a rotating list of predefined values (e.g., `test@example.com` for email fields). When no category is detected, the generator falls back to `StringCache.nextString()` to preserve existing behavior.

The generator is stateless except for a per-widget rotation counter that cycles through the value list on repeated visits. This ensures different values are tried across visits to the same field.

## Data Contracts

### Input
- `node: GUITreeNode` — the EditText widget node (source: `ModelAction.getResolvedNode()`)
  - `node.getResourceID(): String` — resource identifier (may be null)
  - `node.getContentDesc(): String` — accessibility content description (may be null)
  - `node.isPassword(): boolean` — whether the field is a password input

### Output
- `text: String` — generated input text appropriate for the widget type (consumer: `GUITreeNode.setInputText()`)

### Side-Effects
- **[Memory]**: Rotation counters stored per widget ID (grows with distinct widgets encountered).

### Error
- None. `generateForNode()` SHALL NOT return null — it falls back to `StringCache.nextString()` for unrecognized fields.

## Invariants

- **INV-INP-01**: `InputValueGenerator.generateForNode(node)` SHALL NOT return null for any non-null `GUITreeNode`.
- **INV-INP-02**: When `Config.heuristicInput` is `false`, `ApeAgent.checkInput()` SHALL use `StringCache.nextString()` — identical to the behavior before this change.
- **INV-INP-03**: Category detection SHALL be case-insensitive when matching keywords in resourceId and contentDescription.
- **INV-INP-05**: Keyword matching in category detection SHALL compare whole tokens, never raw substrings.
- **INV-INP-06**: `StringCache.nextString()` SHALL never throw; an empty cache yields a formatted random string.
- **INV-INP-04**: `ApeAgent.checkInput()` SHALL bypass the `RandomHelper.toss(ape.inputRate)` gate (deterministic fill) ONLY when the form-completion context holds for the current state. Outside that context the toss gate SHALL be retained. This invariant aligns with INV-FORM-03 of the `form-completion` capability.

## Requirements

### Requirement: InputValueGenerator — Category Detection

`InputValueGenerator.detectCategory(GUITreeNode node)` SHALL detect the input category using the following priority order:

1. `node.isPassword()` returns `true` → `PASSWORD`
2. `node.getResourceID()` has a token matching "email" → `EMAIL`
3. `node.getResourceID()` has a token matching "password" or "passwd" → `PASSWORD`
4. `node.getResourceID()` has a token matching "phone" or "tel" → `PHONE`
5. `node.getResourceID()` has a token matching "url" or "website" or "uri" → `URL`
6. `node.getResourceID()` has a token matching "number" or "amount" or "quantity" or "price" or "count" → `NUMBER`
7. `node.getResourceID()` has a token matching "search" → `SEARCH`
8. `node.getContentDesc()` is checked with the same token/keyword set as steps 2-7
9. Fallback: `GENERIC`

Keyword comparison SHALL be token-based, not raw substring: the identifier is split on separators (`_`, `-`, `.`, `/`, `:`, space) and on camelCase boundaries, lowercased, and each token is compared for equality with the keyword. Raw `contains` matching produced systematic mislabels — "account" matched "count" → NUMBER, "security" matched "uri" → URL, "hotel" matched "tel" → PHONE — feeding numerically- or URL-shaped values into login/signup fields, whose server-side validation then blocked the screens behind them.

#### Scenario: Password field detected by isPassword
- **WHEN** `detectCategory(node)` is called and `node.isPassword()` returns `true`
- **THEN** the result SHALL be `PASSWORD`

#### Scenario: Email field detected by resourceId
- **WHEN** `node.getResourceID()` is `"com.example:id/input_email"` and `node.isPassword()` is `false`
- **THEN** the result SHALL be `EMAIL`

#### Scenario: URL field detected by contentDescription
- **WHEN** `node.getResourceID()` is null and `node.getContentDesc()` is `"Enter website URL"`
- **THEN** the result SHALL be `URL`

#### Scenario: account field is not NUMBER
- **WHEN** `node.getResourceID()` is `"com.example:id/account_name"`
- **THEN** the result SHALL NOT be `NUMBER` (token "account" ≠ keyword "count")

#### Scenario: security field is not URL
- **WHEN** `node.getResourceID()` is `"com.example:id/security_answer"`
- **THEN** the result SHALL NOT be `URL` (token "security" ≠ keyword "uri")

#### Scenario: camelCase identifier tokenized
- **WHEN** `node.getResourceID()` is `"com.example:id/userEmailField"`
- **THEN** the result SHALL be `EMAIL` (camelCase split yields token "email")

#### Scenario: Generic fallback
- **WHEN** no keywords match in resourceId or contentDescription and `isPassword()` is `false`
- **THEN** the result SHALL be `GENERIC`

### Requirement: InputValueGenerator — Value Generation

`InputValueGenerator.generateForNode(GUITreeNode node)` SHALL detect the category and return the next value from the category's predefined list. Values rotate cyclically per widget (tracked by widget ID).

| Category | Values |
|----------|--------|
| EMAIL | `test@example.com`, `user@test.org`, `a@b.c` |
| PASSWORD | `Test1234!`, `Password123`, `Aa1!aaaa` |
| NUMBER | `42`, `0`, `999` |
| PHONE | `+5561999990000`, `123456789` |
| URL | `https://example.com`, `http://test.org` |
| SEARCH | `test`, `crypto`, `settings` |
| GENERIC | delegates to `StringCache.nextString()` |

#### Scenario: First visit to email field
- **WHEN** `generateForNode(emailNode)` is called for the first time on a widget with category `EMAIL`
- **THEN** the result SHALL be `"test@example.com"`

#### Scenario: Second visit to same email field
- **WHEN** `generateForNode(emailNode)` is called a second time on the same widget
- **THEN** the result SHALL be `"user@test.org"`

#### Scenario: Generic field
- **WHEN** the category is `GENERIC`
- **THEN** the result SHALL be the return value of `StringCache.nextString()`

### Requirement: ApeAgent.checkInput() Integration

`ApeAgent.checkInput()` SHALL generate text for an `EditText` node whose `getInputText()` is `null` using `generateInputText(node)` (which selects `InputValueGenerator.generateForNode(node)` when `Config.heuristicInput` is `true`, else `StringCache.nextString()`).

`checkInput()` SHALL gate whether a node is filled on the form-completion context (defined by the `form-completion` capability: the current state carries at least one resolved, valid `EditText` action whose node holds no text — `getInputText() == null` and `getText()` null or empty). The context SHALL be evaluated against the state reflecting the current screen at `checkInput` time (`currentState` — `newState` has already been nulled by `moveForward()` in the pipeline; INV-FORM-07):

- When the form-completion context holds for the current state, `checkInput()` SHALL fill the selected unfilled `EditText` node deterministically — it SHALL set the node's input text without evaluating the `RandomHelper.toss(ape.inputRate)` gate.
- When the form-completion context does NOT hold, `checkInput()` SHALL retain the legacy probabilistic behavior — it SHALL set the node's input text only when `RandomHelper.toss(ape.inputRate)` succeeds.

In both cases the text source is unchanged (`generateInputText(node)`), and a node that already holds text (`getInputText() != null` or non-empty `getText()`) SHALL NOT be re-filled. `Config.heuristicInput` continues to select the generator within `generateInputText()` and is independent of the form-completion gate.

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

### Requirement: Config Flag for Heuristic Input

`Config.java` SHALL declare the following flag:

| Flag | Property Key | Type | Default | Description |
|------|-------------|------|---------|-------------|
| `heuristicInput` | `ape.heuristicInput` | boolean | true | Enable context-aware text input (false = random StringCache) |

#### Scenario: Config flag loaded
- **WHEN** `ape.properties` contains `ape.heuristicInput=false`
- **THEN** `Config.heuristicInput` SHALL be `false`

#### Scenario: Default value
- **WHEN** `ape.heuristicInput` is not set in properties
- **THEN** `Config.heuristicInput` SHALL be `true`

---

### Requirement: StringCache Empty-Cache Behavior

`StringCache.nextString()` SHALL check for an empty cache **before** drawing a random index, and SHALL return `RandomHelper.nextFormattedString()` when the cache is empty. The cache is populated from `/sdcard/ape.strings` (not pushed by the aperv deployment) and from text observed on screen during the run, so on a text-sparse screen — typically a login form, exactly where input matters most — the cache is genuinely empty and the previous order (`nextInt(size)` first) threw `IllegalArgumentException` on the GENERIC input path.

#### Scenario: empty cache returns a fallback string
- **WHEN** `nextString()` is called with an empty cache
- **THEN** it SHALL return a non-null formatted random string
- **AND** no exception SHALL be thrown

#### Scenario: populated cache unchanged
- **WHEN** the cache holds at least one string
- **THEN** `nextString()` SHALL return one of the cached strings, as before
