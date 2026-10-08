# Delta: UI Tree (llm-coordinate-single-base)

## Purpose

The rv-android instrumenter change gh121 (`b7abcd6a`, device smoke `c054038a`), run with `--stamp-handlers`, makes the instrumented app write each widget's handler class into the extras of its accessibility node: the binary class name of the click handler under `rvsec.click`, and of the long-click handler under `rvsec.longClick` (gh121 `instrumentation` spec `:27`, INV-INS-178). On a Compose node the stamp is the class of the app's handler lambda: the `onClick`/`onLongClick` field (or a toggleable's `onValueChange`, under `rvsec.click` only) of the modifier node the semantics action's lambda holds, and the action lambda's own class only when no modifier node is reached (INV-INS-178, which lives in the unarchived gh121 change's delta). The MOP lookup of `mop-guidance` ("Handler Stamp Lookup", design D17) uses this stamp to judge a widget by the handler that actually runs instead of by its resource id, which 58.6 % of clicked nodes lack.

`GUITree` is a detached snapshot, so the stamp has to be copied when the node is built, like every other property the explorer reads. This delta adds that copy (design D16). It is not a feature and has no plan key: on an APK instrumented without `--stamp-handlers` both fields are null on every node, and every MOP lookup takes the id key, as before. Nothing else of the extras bundle is read. The main spec's Data Contracts list of attributes read per node gains the two extras; deltas do not sync Data Contracts, so that line is updated by hand at archive (task 9.2).

## Invariants

- **INV-TREE-14**: Every `GUITreeNode` built from an accessibility node SHALL carry, as `handlerClick` and `handlerLongClick`, the string values of that node's extras `rvsec.click` and `rvsec.longClick`, or null when the bundle, the key or a string value is missing, or when reading the extras throws. No other extra SHALL be read. The two fields SHALL NOT enter the node's naming attributes or its structural hash (INV-TREE-06), so the abstraction of a stamped APK is the same as that of its unstamped build.

## ADDED Requirements

### Requirement: Handler Stamp Capture

`GUITreeBuilder.fillNode` (`tree/GUITreeBuilder.java:582-607`) SHALL read `AccessibilityNodeInfo.getExtras()` and copy two strings into the `GUITreeNode` it fills:

- `rvsec.click` → `handlerClick`, served by `GUITreeNode.getHandlerClick()`;
- `rvsec.longClick` → `handlerLongClick`, served by `GUITreeNode.getHandlerLongClick()`.

Each value is the binary class name gh121 wrote (`Class.getName()` form, e.g. `br.unb.cic.cryptoapp.cipher.CipherActivity$1`), copied unchanged. A field SHALL be null when `getExtras()` returns null, when the key is absent, or when the value under the key is not a string. An exception thrown while reading the extras SHALL leave both fields null and SHALL NOT propagate out of `fillNode` (design, Error Handling). The read uses `getExtras()` and `BaseBundle.getString`, both present in the framework stub `framework/classes-full-debug.jar` (checked with `javap`, design D16).

The fields sit beside `isPassword` (`GUITreeNode.java:64`, INV-TREE-09) and are carried wherever the node's other captured properties are. `fillNode` SHALL read no other key of the bundle. The capture is unconditional: no plan key enables or disables it, and it runs on every arm. The run reports how many captured nodes carried at least one stamp (`RUN_END` counter `stampNodes`, `event-sink`, design D23).

The capture is not reachable in the JVM suite, because surefire excludes the framework stubs (`pom.xml:160-163`). Its scenarios are written as an `@Ignore`d Android-runtime test beside `GUITreeBuilderPasswordTest`, and the device smoke on a stamped `cryptoapp` is their gate (tasks 11.3 and 17.2).

#### Scenario: stamped View node
- **WHEN** a `GUITreeNode` is built from an `AccessibilityNodeInfo` whose extras hold `rvsec.click = "com.x.Foo$1"` and `rvsec.longClick = "com.x.Foo$2"`
- **THEN** `node.getHandlerClick()` SHALL return `"com.x.Foo$1"`
- **AND** `node.getHandlerLongClick()` SHALL return `"com.x.Foo$2"`

#### Scenario: unstamped node
- **WHEN** a node is built from an `AccessibilityNodeInfo` whose `getExtras()` returns an empty bundle, or a bundle without `rvsec.click` and `rvsec.longClick` (an APK instrumented without `--stamp-handlers`)
- **THEN** `node.getHandlerClick()` and `node.getHandlerLongClick()` SHALL both return null
- **AND** every other captured property of the node SHALL be the same as without this requirement

#### Scenario: non-string value
- **WHEN** the extras hold `rvsec.click` as an integer and `rvsec.longClick` as a string `"com.x.Foo$3"`
- **THEN** `node.getHandlerClick()` SHALL return null
- **AND** `node.getHandlerLongClick()` SHALL return `"com.x.Foo$3"`
- **AND WHEN** reading the extras throws
- **THEN** both fields SHALL be null and the tree SHALL still be built

#### Scenario: Compose node with only rvsec.click
- **WHEN** a Compose node with no resource id carries extras holding only `rvsec.click = "com.x.ui.ScreenKt$Body$1$1"`, the class of its app handler lambda
- **THEN** `node.getHandlerClick()` SHALL return `"com.x.ui.ScreenKt$Body$1$1"`
- **AND** `node.getHandlerLongClick()` SHALL return null
- **AND** the node's resource id SHALL stay empty: the stamp does not stand in for an id anywhere in the tree
