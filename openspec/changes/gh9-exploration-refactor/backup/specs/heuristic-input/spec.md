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

## ADDED Requirements

### Requirement: InputValueGenerator — Category Detection

`InputValueGenerator.detectCategory(GUITreeNode node)` SHALL detect the input category using the following priority order:

1. `node.isPassword()` returns `true` → `PASSWORD`
2. `node.getResourceID()` contains (case-insensitive) "email" → `EMAIL`
3. `node.getResourceID()` contains "password" or "passwd" → `PASSWORD`
4. `node.getResourceID()` contains "phone" or "tel" → `PHONE`
5. `node.getResourceID()` contains "url" or "website" or "uri" → `URL`
6. `node.getResourceID()` contains "number" or "amount" or "quantity" or "price" or "count" → `NUMBER`
7. `node.getResourceID()` contains "search" → `SEARCH`
8. `node.getContentDesc()` is checked with the same keyword set as steps 2-7
9. Fallback: `GENERIC`

#### Scenario: Password field detected by isPassword
- **WHEN** `detectCategory(node)` is called and `node.isPassword()` returns `true`
- **THEN** the result SHALL be `PASSWORD`

#### Scenario: Email field detected by resourceId
- **WHEN** `node.getResourceID()` is `"com.example:id/input_email"` and `node.isPassword()` is `false`
- **THEN** the result SHALL be `EMAIL`

#### Scenario: URL field detected by contentDescription
- **WHEN** `node.getResourceID()` is null and `node.getContentDesc()` is `"Enter website URL"`
- **THEN** the result SHALL be `URL`

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

`ApeAgent.checkInput()` SHALL use `InputValueGenerator.generateForNode(node)` when `Config.heuristicInput` is `true`. When `Config.heuristicInput` is `false`, it SHALL use `StringCache.nextString()` (existing behavior).

#### Scenario: Heuristic input enabled
- **WHEN** `Config.heuristicInput` is `true` and an EditText action is selected
- **THEN** `InputValueGenerator.generateForNode(node)` SHALL be called to generate the input text

#### Scenario: Heuristic input disabled
- **WHEN** `Config.heuristicInput` is `false`
- **THEN** `StringCache.nextString()` SHALL be used (identical to pre-change behavior)

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
