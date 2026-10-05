#!/usr/bin/env python3
"""v13 element-list composition (jar e93dea86): repeated lines by bounds versus by widget identity,
and lines by how much of their node lies on the display.

WHAT THIS MEASURES
  curation_analysis.py identifies a widget by its resolved node's bounds. The change groups list lines
  by the resolved GUITreeNode's identity. The trace does not print node identity, but it prints, per
  listed action, its target Name and its resolved-node bounds (State.printActions after
  `Sata Strategy: buffer size`). Over the aligned calls of curation_analysis.py (same alignment, same
  exclusion), this counts:
    * dup_bounds:      lines whose bounds repeat an earlier line's (curation_analysis's "duplicate");
    * dup_same_name:   of those, lines whose target Name also matches an earlier line with those bounds
                       (the same widget: identity grouping collapses them);
    * dup_other_name:  of those, lines with a different Name (distinct nodes with identical bounds, e.g.
                       a container and its single child: identity grouping keeps them);
    * dup_name:        lines whose Name repeats an earlier line's, whatever the bounds; dup_name minus
                       dup_same_name are lines that resolved, by alias sampling in State.resolveAction,
                       to a different node of the same abstract widget (kept as separate lines).

  Visibility (added 2026-10-05 with the visible-centre rule): each line's resolved-node bounds against
  the call's display, as implied by its qwen -> px pair (coordinate_counterfactual.display_of; calls
  whose display cannot be inferred are counted as `display_unknown` and not classified):
    * inside:              bounds wholly inside [0,W)x[0,H);
    * partial_centre_in:   cut by the display edge, whole centre still inside;
    * partial_centre_out:  cut by the display edge, whole centre outside (the old list clamped it);
    * offscreen:           no intersection with the display (the new list shows no coordinate).

  Added 2026-10-05 (adversarial review):
    * alias count: the listing prints `[RN=n]`, the number of nodes the action's target Name resolves
      to (ModelAction.resolvedInfo), not a node id. Node identity is recoverable from the trace only
      where RN = 1 (a Name with one node); dup_same_name lines with RN > 1 are the ones where a
      (Name, bounds) repeat may still be two distinct alias nodes with identical bounds.
    * root clip versus display clip: MonkeySourceApe taps node actions at the centre of
      node ∩ root ∩ display, the list of the change at the centre of node ∩ display. The root's right
      and bottom edges are bounded by the list base the alignment infers (its upper bound is used, so
      the count is a lower bound; the root's left and top edges are not recoverable). Counted: lines
      whose two visible centres differ, and among them those whose node misses the root entirely
      (listed with a coordinate, dropped by the dispatcher).

RUN
  python3 dup_identity.py              # E6 arm 4
  python3 dup_identity.py e5b          # E5b v13_A
Reads only; prints to stdout. Imports curation_analysis.py from this directory without writing bytecode.
"""

import collections
import glob
import json
import multiprocessing
import os
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import curation_analysis as ca  # noqa: E402
import coordinate_counterfactual as cc  # noqa: E402


def visibility(b, w, h):
    l, t, r, bt = b
    if min(r, w) <= max(l, 0) or min(bt, h) <= max(t, 0):
        return "offscreen"
    if l >= 0 and t >= 0 and r <= w and bt <= h:
        return "inside"
    cx, cy = (l + r) >> 1, (t + bt) >> 1
    return "partial_centre_in" if 0 <= cx < w and 0 <= cy < h else "partial_centre_out"


def visible_centre(b, w, h):
    l, t, r, bt = max(b[0], 0), max(b[1], 0), min(b[2], w), min(b[3], h)
    if l >= r or t >= bt:
        return None
    return (l + r) >> 1, (t + bt) >> 1


def proc(path):
    C = collections.Counter()
    listings = collections.defaultdict(list)
    step, collecting, records = 0, None, []
    rn = {}
    with open(path, "rt", errors="replace") as fh:
        for line in fh:
            if line.startswith('{"s"'):
                collecting = None
                try:
                    records.append(json.loads(line))
                except ValueError:
                    pass
                continue
            if line.startswith("[APE] >>>>>>>> SATA begin"):
                m = ca.RE_STEP.match(line)
                if m:
                    step = int(m.group(1))
                collecting = None
                continue
            if line.startswith("[APE] Sata Strategy: buffer size"):
                collecting = []
                listings[step].append(collecting)
                continue
            if collecting is not None:
                m = ca.RE_ACTION.match(line)
                if m:
                    act = ca.parse_action_rest(m.group(3), m.group(4))
                    t = ca.RE_TAIL.match(m.group(4))
                    rn[id(act)] = int(t.group(5)) if t and t.group(5) is not None else 0
                    collecting.append(act)
                elif not ca.RE_COUNTER.match(line):
                    collecting = None
    for rec in records:
        s = rec["s"]
        for call in [c for c in (rec.get("llm") or []) if "qwen" in c]:
            entries, _, _ = ca.parse_prompt(call.get("user", ""))
            if entries is None:
                continue
            aligned = None
            for acts in reversed(listings.get(s, [])):
                targets = [a for a in acts if a.target]
                if len(targets) != len(entries):
                    continue
                if any(e[0] != (a.simple if a.bounds is not None else "View")
                       for e, a in zip(entries, targets)):
                    continue
                base = ca.infer_base(entries, targets)
                if base is None:
                    continue
                aligned = targets
                break
            if aligned is None:
                continue
            C["prompts"] += 1
            C["lines"] += len(aligned)
            names_at = {}
            names = set()
            distinct_same_bounds = False
            for a in aligned:
                if a.bounds is not None:
                    if a.bounds in names_at:
                        C["dup_bounds"] += 1
                        if a.name in names_at[a.bounds]:
                            C["dup_same_name"] += 1
                            if rn.get(id(a), 0) > 1:
                                C["dup_same_name_rn_gt1"] += 1
                        else:
                            C["dup_other_name"] += 1
                            distinct_same_bounds = True
                        names_at[a.bounds].add(a.name)
                    else:
                        names_at[a.bounds] = {a.name}
                if a.name in names:
                    C["dup_name"] += 1
                names.add(a.name)
            C["prompts_with_dup_other_name"] += distinct_same_bounds
            disp = cc.display_of(call) if "px" in call else None
            if disp is None:
                C["display_unknown"] += 1
                continue
            seen_v = set()
            for a in aligned:
                if a.bounds is not None:
                    v = visibility(a.bounds, *disp)
                    C[("vis", v)] += 1
                    seen_v.add(v)
            for v in seen_v:
                C[("vis_prompt", v)] += 1
            rw, rh = min(disp[0], base[1]), min(disp[1], base[3])
            if (rw, rh) != tuple(disp):
                differs = False
                for a in aligned:
                    if a.bounds is None:
                        continue
                    v1 = visible_centre(a.bounds, *disp)
                    if v1 is None:
                        continue
                    v2 = visible_centre(a.bounds, rw, rh)
                    if v2 != v1:
                        differs = True
                        C["clip_differs_lines"] += 1
                        C["clip_root_miss_lines"] += v2 is None
                C["clip_differs_calls"] += differs
    return C


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "e5b":
        root, pattern = ca.E5B_ROOT, ca.E5B_GLOB
    else:
        root, pattern = ca.E6_ROOT, ca.E6_GLOB
    files = sorted(glob.glob(os.path.join(root, pattern)))
    files = [f for f in files if not any(r in f and a in f for r, a in ca.EXCLUDE_APKS)]
    print("# inputs:", os.path.join(root, pattern), "; files:", len(files))
    T = collections.Counter()
    with multiprocessing.Pool(min(32, os.cpu_count() or 1)) as pool:
        for C in pool.imap(proc, files):
            T += C
    n = T["lines"]
    print("aligned prompts %d; lines %d" % (T["prompts"], n))
    for k in ("dup_bounds", "dup_same_name", "dup_other_name", "dup_name"):
        print("  %-16s %7d  (%.1f%% of lines)" % (k, T[k], 100.0 * T[k] / max(n, 1)))
    print("  name repeated, different node (dup_name - dup_same_name): %d" % (T["dup_name"] - T["dup_same_name"]))
    print("  prompts with distinct nodes sharing bounds: %d (%.1f%%)" % (
        T["prompts_with_dup_other_name"], 100.0 * T["prompts_with_dup_other_name"] / max(T["prompts"], 1)))
    print("visibility of listed nodes against the call's display (display unknown for %d calls):"
          % T["display_unknown"])
    for v in ("inside", "partial_centre_in", "partial_centre_out", "offscreen"):
        print("  %-20s lines %8d (%.1f%% of lines)  prompts %7d (%.1f%%)" % (
            v, T[("vis", v)], 100.0 * T[("vis", v)] / max(n, 1),
            T[("vis_prompt", v)], 100.0 * T[("vis_prompt", v)] / max(T["prompts"], 1)))
    print("dup_same_name lines whose Name has more than one alias node (RN > 1; identity not "
          "recoverable from the trace): %d" % T["dup_same_name_rn_gt1"])
    print("root clip vs display clip (lower bound; root right/bottom only): lines with a different "
          "visible centre %d in %d calls; of them, node outside the root %d"
          % (T["clip_differs_lines"], T["clip_differs_calls"], T["clip_root_miss_lines"]))


if __name__ == "__main__":
    main()
