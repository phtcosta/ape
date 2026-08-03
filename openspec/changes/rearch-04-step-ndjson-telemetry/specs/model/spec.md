# model Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`, over the one `model` requirement whose entire subject is deleted by this change.

`action-history.log` is one of the seven legacy outputs this stage removes (report Sec. 6.6): task 7.2 deletes `StatefulAgent.saveActionHistory()` and `Model.saveActionHistory` outright. `Tolerant Action-History Persistence` normatizes that method — its per-record guard, its skip-and-warn policy, its `total=/skipped=` summary line, and the byte-identity of the file it produces. With the method gone there is nothing left for the requirement to constrain, and its scenarios become unverifiable: no artifact is written, so no artifact can be byte-identical to anything.

Nothing is silently dropped. The tolerance the requirement bought — a stale `ActionRecord` must not abort teardown — was a property of writing that specific file inside `tearDown`. The teardown-isolation guarantee that actually protects the run (`exploration` INV-EXPL-16/29: every teardown step runs inside `safeStep`, one failing step skips nothing after it) is untouched by this change and is what carries the property forward for every remaining step. The replay-log *reader* path is likewise untouched: `ApeRRFormatter.readActions`/`parseRect` and everything `ReplayAgent` needs survive (design D-7) — what ends is the tool's own production of those logs, not its ability to consume externally supplied ones.

## REMOVED Requirements

### Requirement: Tolerant Action-History Persistence

**Reason**: Its subject is deleted. `Model.saveActionHistory` and the `StatefulAgent.saveActionHistory()` teardown step that called it are removed with the rest of the legacy file outputs (design D-7, task 7.2), so `action-history.log` is never produced. Every clause of the requirement — the per-record resolve guard, the skipped-record counter, the `[APE-RV] total=<records> skipped=<failures>` summary, the byte-identity of the produced file, and the `IOException` swallow — describes behavior of a method that no longer exists; a requirement stated over a deleted mechanism cannot be satisfied or falsified. Deleted completely, with no shim and no retained writer (P3).

**Substitute recorded**: (a) teardown robustness is carried by `exploration` INV-EXPL-16/29, the `safeStep` isolation that already guaranteed a throwing step could not skip the steps after it — the property the per-record guard was a local instance of; (b) the per-step decision and action record that `action-history.log` duplicated now lives in the step record itself, escaped by construction and closed at N+1 (`event-sink` INV-SNK-03/08), where a descriptor that fails to resolve is not a partial-file hazard because the record is already serialized; (c) `ReplayAgent` keeps its input format — the reader path (`ApeRRFormatter.readActions`/`parseRect`) is explicitly preserved, so replay of an externally supplied log is unaffected by the writer's deletion.
