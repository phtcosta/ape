# Tasks: webview-prune-crash

## 1. Fix

- [x] 1.1 Make `GUITreeNode.clearChildren` exception-safe: keep the in-memory prune (`childCount = 0`, `children = null`) first and the existing exhaustive removal loop (`while (childNodes.getLength() > 0) domNode.removeChild(childNodes.item(0))`) verbatim; wrap the DOM loop in `catch (DOMException e)` — placed OUTSIDE the loop — that logs `[APE-RV] clearChildren DOM prune aborted: <msg>` (WARN) and returns normally
- [x] 1.2 Update the method's comment to describe the current contract (unconditional in-memory prune, best-effort DOM prune — P4, no history)

## 2. Tests (extend `GUITreeWebViewPruneTest`)

- [x] 2.1 Keep/confirm the existing INV-TREE-10 case green: normal `clearChildren` on a node with 10 DOM children leaves 0
- [x] 2.2 Add INV-TREE-12 case: fault-injecting `org.w3c.dom.Element` stub whose `removeChild` throws `DOMException` on the 3rd call — assert `clearChildren` returns normally, `getChildCount() == 0`, and the abort line is logged once
- [x] 2.3 Run `mvn test -Dtest=GUITreeWebViewPruneTest`

## 3. Verification

- [x] 3.1 Full suite: `mvn test` (0 failures/errors)
- [x] 3.2 `openspec validate webview-prune-crash --strict`
- [x] 3.3 Device smoke (with rebuilt jar, alongside the other changes of this cycle): standalone run on one known-crashing APK (e.g. `com.manimarank.spell4wiki_21.apk` from the dataset) — grep the trace for `DOMException` (expected 0) and for `clearChildren DOM prune aborted` (allowed)
