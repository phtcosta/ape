# Delta Specification: ui-tree (rearch-02-runspec)

## ADDED Requirements

### Requirement: No XPathlet Overlay Input

`GUITreeBuilder` SHALL build GUI trees exclusively from the live accessibility snapshot (plus the flag-gated perception enhancements specified elsewhere in this capability). The user-configurable XPathlet overlay — the static-initializer read of `/sdcard/ape.xpath` into a `List<XPathlet>` and every use of that list — SHALL NOT exist (owner decision D6: no arm uses it, the aperv deployment never pushes the file, and an undeclared device file silently reshaping tree construction is exactly the class of unecho'd behavioral input the run-spec capability eliminates; the main specification never covered the mechanism — this requirement records its removal explicitly). Behavior is byte-identical to the only condition ever deployed: overlay absent, empty rule list.

Note: this removes only the `/sdcard` *overlay* reader. The naming lattice's own XPath machinery (`Namelet` selectors, `Name.toXPath()`) is unrelated and untouched.

#### Scenario: legacy overlay file has no effect

- **WHEN** a legacy `/sdcard/ape.xpath` file exists on the device
- **THEN** `GUITreeBuilder` class initialization SHALL NOT open it
- **AND** tree construction SHALL be identical to a device with no such file

#### Scenario: tree construction reads only the accessibility snapshot

- **WHEN** a GUI tree is built during a run
- **THEN** its structure SHALL derive solely from the `AccessibilityNodeInfo` hierarchy and the in-jar perception logic
- **AND** no filesystem input SHALL participate in tree construction
