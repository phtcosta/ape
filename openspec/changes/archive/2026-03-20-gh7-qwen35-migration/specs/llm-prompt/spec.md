## Purpose

Delta spec for `llm-prompt` to add device dimension context to the system message. When the LLM receives a raw screenshot (1080x1920, no resize), the system message SHALL include the actual screen dimensions so the VLM can ground its coordinate predictions in the correct spatial frame. Coordinates remain in [0, 1000) normalized space regardless of image mode — the dimensions are informational context, not a change to the coordinate convention.

---

## MODIFIED Requirements

### Requirement: System Message

`ApePromptBuilder` SHALL generate a system message with role `"system"`. The system message SHALL be compact (~130 tokens). The system message SHALL include the device screen dimensions (e.g., `"Screen: 1080x1920 pixels."`) as contextual information for the VLM's spatial reasoning.

The `build()` method SHALL accept `deviceWidth` and `deviceHeight` parameters (already computed from GUITree root node bounds) and include them in the system message header line. The coordinate space for tools remains [0, 1000) normalized regardless of the image mode.

The content of the system message:
```
You are an Android UI testing agent exploring an app.
Screen: <deviceWidth>x<deviceHeight> pixels.
DIALOG: If permission/error dialog visible, dismiss it first (click Allow/OK).
PRIORITY: [DM]/[M] elements > unvisited (v:0) > visited.
AVOID: status bar (top), navigation bar (bottom).
RULES: Don't click same position twice. Use type_text for input fields with valid data (email: user@example.com, password: Test1234!, domain: example.com, search: relevant term).
Tools (coordinates in [0,1000) normalized space):
  click(x, y) -- tap element
  long_click(x, y) -- long press element
  type_text(x, y, text) -- type into field
  back() -- press back
Respond with one JSON: {"name": "<action>", "arguments": {<args>}}
```

**Dynamic tool schema**: The `type_text` tool SHALL be included in the system message only when the current widget list contains at least one input-capable widget (EditText, SearchView, AutoCompleteTextView). When no input widgets are present, `type_text` is omitted from the tool list.

#### Scenario: System message includes screen dimensions

- **WHEN** `build()` is called with a GUITree whose root node has bounds (0, 0, 1080, 1920)
- **THEN** the system message SHALL contain the line `"Screen: 1080x1920 pixels."`
- **AND** the system message SHALL state `"coordinates in [0,1000) normalized space"` for tool descriptions

#### Scenario: System message with non-standard resolution

- **WHEN** `build()` is called with a GUITree whose root node has bounds (0, 0, 1440, 2560)
- **THEN** the system message SHALL contain `"Screen: 1440x2560 pixels."`
- **AND** tool coordinates SHALL remain [0, 1000) normalized

#### Scenario: Dynamic tool schema -- type_text included

- **WHEN** `build()` is called and the actions list contains at least one action targeting an EditText, SearchView, or AutoCompleteTextView
- **THEN** the system message SHALL include `type_text(x, y, text)` in the tool list

#### Scenario: Dynamic tool schema -- type_text omitted

- **WHEN** `build()` is called and no action in the list targets an input-capable widget
- **THEN** `type_text` SHALL NOT appear in the tool list
- **AND** the system message SHALL list only `click`, `long_click`, and `back`
