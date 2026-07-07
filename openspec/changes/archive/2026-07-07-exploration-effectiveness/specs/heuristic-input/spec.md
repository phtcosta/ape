## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: StringCache Empty-Cache Behavior

`StringCache.nextString()` SHALL check for an empty cache **before** drawing a random index, and SHALL return `RandomHelper.nextFormattedString()` when the cache is empty. The cache is populated from `/sdcard/ape.strings` (not pushed by the aperv deployment) and from text observed on screen during the run, so on a text-sparse screen — typically a login form, exactly where input matters most — the cache is genuinely empty and the previous order (`nextInt(size)` first) threw `IllegalArgumentException` on the GENERIC input path.

#### Scenario: empty cache returns a fallback string
- **WHEN** `nextString()` is called with an empty cache
- **THEN** it SHALL return a non-null formatted random string
- **AND** no exception SHALL be thrown

#### Scenario: populated cache unchanged
- **WHEN** the cache holds at least one string
- **THEN** `nextString()` SHALL return one of the cached strings, as before

## Invariants

- **INV-INP-05**: Keyword matching in category detection SHALL compare whole tokens, never raw substrings.
- **INV-INP-06**: `StringCache.nextString()` SHALL never throw; an empty cache yields a formatted random string.
