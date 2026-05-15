<!-- Single-file change (MopData.java + MopDataTest.java).
     No subagent orchestration needed.
     Critical path: 1 -> 2 -> 3.
     Gating: integration smoke (3.x) is BLOCKED until upstream gh57 archives in rv-android
     and a real post-gh57 JSON is available from the 380-APK ground-truth re-run. -->

## 1. Data model — WidgetMopFlags extension

- [ ] 1.1 In `src/main/java/com/android/commands/monkey/ape/utils/MopData.java`, extend the public inner class `WidgetMopFlags` with four nullable `String` fields: `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`. Defaults are implicit `null`. No accessors — direct field access matches the existing `directMop` / `transitiveMop` pattern.
- [ ] 1.2 `mvn compile` — confirm no callers of `WidgetMopFlags` break (none expected; the new fields are additive).

## 2. Parser — parseWidget reads new fields and recurses into items[]

- [ ] 2.1 Modify `MopData.parseWidget` signature to `parseWidget(JsonReader reader, Map<String, boolean[]> bySignature, Map<String, WidgetMopFlags> widgets, int depth)`. Update the single caller in `parseWindow` to pass `depth = 0`. At entry, if `depth > 8`, throw `IOException("items[] recursion depth exceeded")`.
- [ ] 2.2 In the `switch (field)` block of `parseWidget`, add four new cases that read `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`. Each must handle `JsonToken.NULL` via `reader.nextNull()` returning `null`, otherwise `reader.nextString()`.
- [ ] 2.3 Capture the four values into local variables and assign them onto the constructed `WidgetMopFlags` (alongside `directMop` / `transitiveMop`) at the end of the method, before `widgets.put`.
- [ ] 2.4 Add a new `case "items":` in the same `switch` block that calls `reader.beginArray()`, loops with `parseWidget(reader, bySignature, widgets, depth + 1)` for each element, and calls `reader.endArray()`. Submenu widgets land in the same `widgets` map as their parent (INV-MOP-08).

## 3. Tests

- [ ] 3.1 Add `MopDataTest.testParsesFourNewWidgetAttributes` — fixture with a Spinner widget carrying `prompt` / `spinnerMode` and a Button widget carrying `contentDescription` / `tooltipText`; assert each field on the resulting `WidgetMopFlags`.
- [ ] 3.2 Add `MopDataTest.testNewWidgetFieldsNullWhenAbsent` — fixture with widgets that omit the four fields; assert all four are `null`. Also include one widget with an explicit `null` JSON value for each field; assert it parses as `null`.
- [ ] 3.3 Add `MopDataTest.testRecursiveOptionsMenuItems` — fixture with an OPTIONSMENU window containing one top-level menu widget that has 2 children under `items[]`; assert `widgetData[activity]` contains all 3 `idName` keys.
- [ ] 3.4 Add `MopDataTest.testSubmenuHandlerReachesMop` — submenu item's listener handler is listed in `reachability[]` with `directlyReachesMop=true`; assert `getWidget(activity, submenuIdName).directMop` is `true`.
- [ ] 3.5 Add `MopDataTest.testRecursionDepthCapped` — fixture that nests `items[]` to depth 9; assert `MopData.load` returns `null`.
- [ ] 3.6 `mvn test -Dtest=MopDataTest` — all new and existing tests pass.

## 4. Integration smoke (DEFERRED until gh57 archives)

- [ ] 4.1 Wait for gh57-static-analysis-overhaul to archive in `rvsec/rv-android` (gh57 G9.12).
- [ ] 4.2 Pick one post-gh57 JSON from the ground-truth re-run output and place it at a known path on the device or under `target/test-classes/` for a local load smoke. Confirm `MopData.load` returns non-null and `widgetData` contains at least the expected `idName` keys for the corresponding APK.
- [ ] 4.3 `mvn install -DskipTests -q` then `cp target/ape-rv.jar ape.jar` so `install.py` deploys the refreshed artifact (per gh57 task 7.7).
- [ ] 4.4 Aperv smoke: `uv run rv-experiment run --tools aperv:sata_mop --apks-dir <3 APKs> --timeout 60` from `rvsec/rv-android` (gh57 task 9.5).

## 5. Verification

- [ ] 5.1 Invoke `sdd-code-reviewer` via Skill tool against the change diff.
- [ ] 5.2 `/opsx:verify` against this change.
- [ ] 5.3 `/opsx:archive` (after 4.x passes).
- [ ] 5.4 Final commit message references `closes #13`.
