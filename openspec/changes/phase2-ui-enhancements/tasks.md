<!-- Sequential execution — 5 files, single session, no subagent dispatch needed (< 20 file threshold).
     Groups 4 (GUITreeNode) and 2 (State) have no compile-time dependency between them, but both touch
     GUITreeNode.java — sequential is correct to avoid conflicts.
     Critical path: Group 1 (ActionType) → Group 2 (State) → Group 3 (MonkeySourceApe) → Group 4 (GUITreeNode) → Group 5 (SataAgent) → Group 6 (Verification).
     Note: getScrollType() and resetActions() are pure Java (no Android API). Unit tests could be added
     off-device, but this project has no automated test suite (CLAUDE.md). Validation is via ADB device runs. -->

## 1. ActionType Foundation

- [ ] 1.1 In `src/main/java/com/android/commands/monkey/ape/model/ActionType.java`, insert `MODEL_MENU,` between `MODEL_BACK` and `MODEL_CLICK` (one line). Add an inline comment: `// MENU key press; no widget target`.
- [ ] 1.2 Verify `requireTarget()` ordinal range: confirm that `MODEL_MENU.ordinal()` is less than `MODEL_CLICK.ordinal()` so `requireTarget()` returns `false` for `MODEL_MENU` without any code change to the predicate body.
- [ ] 1.3 Verify `isModelAction()` ordinal range: confirm `MODEL_MENU.ordinal()` is between `MODEL_BACK.ordinal()` and `MODEL_SCROLL_RIGHT_LEFT.ordinal()` so `isModelAction()` returns `true`.
- [ ] 1.4 Run `mvn compile` — must succeed with no errors before proceeding.

## 2. State — menuAction Field

- [ ] 2.1 In `src/main/java/com/android/commands/monkey/ape/model/State.java`, add the field `private ModelAction menuAction;` immediately after the `backAction` field declaration.
- [ ] 2.2 In the `State` constructor, after the line `c.add(backAction);`, add:
  ```java
  menuAction = new ModelAction(this, ActionType.MODEL_MENU);
  c.add(menuAction);
  ```
- [ ] 2.3 Add the accessor `public ModelAction getMenuAction() { return this.menuAction; }` immediately after `getBackAction()`.
- [ ] 2.4 Run `mvn compile` — must succeed.

## 3. MonkeySourceApe — Event Dispatch and Validation

- [ ] 3.1 In `src/main/java/com/android/commands/monkey/MonkeySourceApe.java`, in `generateEventsForActionInternal()`, add `case MODEL_MENU: generateKeyMenuEvent(); break;` immediately after the `case MODEL_BACK:` block.
- [ ] 3.2 In `validateResolvedAction()`, add `case MODEL_MENU: return true;` immediately after the `case MODEL_BACK: return true;` line.
- [ ] 3.3 Inspect both switch statements to confirm every `ActionType` enum constant has an explicit case (no enum value falls through to `default:`). Compile-time: Java does not enforce exhaustive switches on non-sealed enums.
- [ ] 3.4 Run `mvn compile` — must succeed.

## 4. GUITreeNode — Scroll Detection and Blocklist

- [ ] 4.1 In `src/main/java/com/android/commands/monkey/ape/tree/GUITreeNode.java`, in `getScrollType()`, add `|| className.equals("androidx.viewpager.widget.ViewPager") || className.equals("androidx.viewpager2.widget.ViewPager2")` to the horizontal branch, immediately after `|| className.equals("android.support.v4.view.ViewPager")`.
- [ ] 4.2 In `resetActions()`, add `case MODEL_MENU: throw new IllegalStateException("Cannot set " + at + " to widget.");` in the blocklist switch, immediately after the `case MODEL_BACK:` throw line.
- [ ] 4.3 Run `mvn compile` — must succeed.

## 5. SataAgent — MENU Unvisited Priority Check

- [ ] 5.1 In `src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java`, in `selectNewActionEpsilonGreedyRandomly()`, after the existing `backAction` unvisited block, add:
  ```java
  ModelAction menu = newState.getMenuAction();
  if (menu.isValid()) {
      if (menu.isUnvisited()) {
          Logger.iprintln("Select Menu because Menu action is unvisited.");
          return menu;
      }
  }
  ```
- [ ] 5.2 Run `mvn compile` — must succeed.

## 6. Verification

- [ ] 7.1 Run `mvn clean package` — must produce `target/ape-rv.jar` with no errors.
- [ ] 7.2 Validate the JAR contains a valid Dalvik DEX: `unzip -p target/ape-rv.jar classes.dex | file -` must output `Dalvik dex file version 035` (or similar).
- [ ] 7.3 ADB functional test — ViewPager2: push JAR to device and run on an app with a ViewPager2 tab layout; confirm `MODEL_SCROLL_LEFT_RIGHT` appears in logcat (`adb logcat | grep MODEL_SCROLL_LEFT_RIGHT`).
- [ ] 7.4 ADB functional test — MODEL_MENU: run with `--ape sata` on any app; confirm `Select Menu because Menu action is unvisited` appears in logcat exactly once per newly-discovered state.
- [ ] 7.5 Run `/sdd-qa-lint-fix src/main/java`
- [ ] 7.6 Run `/sdd-verify src/main/java`
- [ ] 7.7 Invoke `/sdd-code-reviewer` via Skill tool
