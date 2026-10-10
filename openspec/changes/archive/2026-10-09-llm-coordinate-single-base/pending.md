# Pending improvements: llm-coordinate-single-base

**Status at archive (author, 2026-10-09): P1 confirmed and implemented in this change; P2 and P3 not confirmed, kept as candidates; P4 confirmed, to be done in a change of its own.** Apart from P1, nothing here is implemented by this change. No requirement of a pending item is in `specs/`, and no task of one is in `tasks.md`. `openspec archive` does not read this file. Each item has to be moved back into the artifacts on purpose before it is implemented.

The split was decided by the author on 2026-10-07:

- **Defect repairs stay in the change**, including those in the action history: `@(0,0)` entries (D9), out-of-space history coordinates (D5), each entry's own outcome (D12), and the history's class and text, which follow the `exploration` spec (D13).
- **Improvements wait here.** They change what the `v13` prompt shows without repairing a defect. They are kept apart because Study 03 fixed model, engine and prompt as the bytes jar `e93dea86` produces (proposal, "Relation to Study 03"), and every additional prompt change widens the distance from that configuration.

The author can confirm each item on its own. The repairs do not depend on any of them. On 2026-10-07 the author confirmed P1 and kept P2 and P3 pending.

## P1. The `v13`/`v17` element lists show each widget once, with no ordinal (D10): CONFIRMED

**Confirmed by the author on 2026-10-07 and moved back into the change.** The author judged that a list showing the same widget twice has no reason to stay. It is still an improvement, not a repair: a repeated line is redundant, not false.

On confirmation the grouping key changed. The draft kept here grouped a line's actions by the resolved node's identity. The change groups them by the pair (target `Name`, resolved-node bounds):

- two alias nodes with one `Name` and one rectangle show one line and one `@(x,y)`, and the mapper cannot tell them apart, so identity would have kept a duplicate the model sees (up to 14,241 of the 274,830 collapsing E6 lines have `RN>1`, `evidence/dup_identity_e6.out`);
- the trace records the pair for every listed action (`State.printActions`), but not node identity, so with the pair an offline analysis rebuilds each line's actions exactly, up to a `Name.toString` collision between two namers.

Where it lives now:

- requirement "Element List of the v13 and v17 Variants" in `specs/llm-prompt/spec.md` (ADDED), with two new scenarios for alias nodes and for the same `Name` at different bounds, and the scope notes of "Widget List Generation" restored;
- design D10 (status, the key and why), goal 7, the mapping and testing tables, and the risk "[Offline analyses assume one line per action]", which now names the fix;
- tasks group 5, the 8.2 checks, and the 9.2 and 9.3 notes;
- proposal: the "What Changes" bullet, the comparability line, the capability line, "Prompt cost" and "Telemetry".

The verbatim requirement and tasks that were kept here are superseded by those artifacts and were removed from this file; the text before the key change is in `tasks.md` and `specs/llm-prompt/spec.md` at commit `a695709e`.

## P2. The history entry's identifier uses the list's full fallback, `id=<shortId>` included: NOT CONFIRMED

**Kept as a candidate at archive (author, 2026-10-09).** An improvement to the `v13` prompt; not confirmed, because each prompt change widens the distance from the configuration Study 03 calibrated.

**What it changes.** D13 makes the history follow the `exploration` spec: text, else content-description. The element list has a third step, the short resource id rendered as `id=<shortId>` (INV-PRM-05). P2 would add that step to the history too, so that a widget carries one identifier in both places. Implementation: `recordActionHistory` calls the list's identifier helper as a whole instead of its text/content-description part (design D13).

**Why it is an improvement, not a repair.** The spec has never asked for the resource id in the history. A history entry without one is not wrong; it says less.

**Size.** In E6, 19,563 text-less history entries name a widget that the same prompt's list shows only as `id=…`; in E5b, 1,382. These are lower bounds, because the scan only matches full-screen widgets still on screen (`evidence/history_fields_e6.out`, `evidence/history_fields_e5b.out`).

**Artifacts it would touch.** `exploration` delta: the `widgetText` row ("text, content-description, or `id=<shortId>`, the element list's identifier") and one scenario. `llm-prompt` "Action History": one scenario. Task 3.5: the helper called.

## P3. A `type_text` entry names the field it typed into: NOT CONFIRMED

**Kept as a candidate at archive (author, 2026-10-09),** for the same reason as P2.

**What it changes.** Today a `type_text` entry reads `- type_text @(x,y) "typed text" → result`: no class and no widget identifier, so in a form with several fields the model sees what it typed but not where. P3 would render the field's class and identifier as well. The exact form is open, for example `- type_text @(500,280) EditText "Email" ← "user@example.com" → same`.

**Why it is an improvement, not a repair.** The spec's own format for `type_text` (`llm-prompt` "Action History", scenario "type_text in history") omits the class and identifier, and the code follows it.

**Size.** `type_text` entries appear in 8.8 % of E6 prompts (15,385 entries) and 7.9 % of E5b prompts (991) (`evidence/history_fields_e6.out`, `evidence/history_fields_e5b.out`).

**Artifacts it would touch.** `llm-prompt` "Action History": the `type_text` format line and its scenario. `exploration` delta: the `widgetClass` and `widgetText` rows, which today say nothing about `type_text`. Tasks 3.3 and 3.5.

## P4. Text typed by SATA is recorded as `click`: CONFIRMED, NEEDS ITS OWN CHANGE

**Confirmed by the author at archive (2026-10-09)** as a repair to be done in a change of its own; this change does not implement it.

**Found by** the code review of task 8.6 (NT-02, 2026-10-08). Recorded here by the author's decision, not implemented.

**What happens.** `recordActionHistory` reads `node.getInputText()` to tell a `type_text` from a `click` (`StatefulAgent.recordActionHistory`). It runs inside `updateStateInternal`, but the text of a SATA-selected input action is set later, by `ApeAgent.checkInput` (`ApeAgent.java:187-204`), which `updateStateWrapper` applies to the action `updateStateInternal` returns (`:362`). At recording time the node has no text yet, so the entry reads `click EditText "Email"` while `MonkeySourceApe` types into the field after the click (`MonkeySourceApe.java:921`). The same holds for an LLM click that `fixTextEdit` turns into a text entry: its text also comes from `checkInput`. Only an LLM `type_text` answer is recorded as `type_text`, because `LlmEngine.applyTypedText` sets the text before the record (`LlmEngine.java:286`). The behavior predates this change.

**Repair or improvement.** By the rule of this change, a **repair**: the `exploration` field table defines `type_text` as an action whose `typedText` is "the text that was typed", and the history of a SATA input action says only that a click happened, omitting the text the screen now shows. It is kept out of this change because the author chose to record it, not because it is an improvement.

**Size.** Not measured. `evidence/history_fields_e6.out` counts the `type_text` entries the trace shows (15,385 in E6), which are the LLM's; the SATA input actions recorded as `click` are not separated there.

**Fix, if confirmed.** Read the typed text after `checkInput`: for example, `StatefulAgent` overrides `checkInput` as it overrides `checkRestart` for WR-01 (design D12), and when the action it returns is the one recorded at this step (last entry, empty result) and its node now carries input text, replaces that entry by a `type_text` copy with `typedText`. **Artifacts it would touch:** `exploration` delta (the recording point of `actionType`/`typedText`, one scenario for a SATA input action), design D12 or a new decision, tasks group 3.

## Not pending here, recorded elsewhere

- **The static placeholder (`hint`) as an identifier fallback.** It would change the list's identifier rule (INV-PRM-05), not only the history, and it depends on the MOP artifact being loaded. That is a separate proposal.
- **The default branch's `hint="…"` label.** `ApePromptBuilder.safeGetHint` reads `GUITreeNode.getInputText()`, the text APE is about to type, not the Android hint; `GUITreeNode` captures no hint at all. The variant the campaign uses, `v13`, does not render it. A separate defect for a separate change.
- **List curation** (entries no tool can act on): design, Open Questions.
