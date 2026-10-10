#!/usr/bin/env python3
"""Task 8.2 trace check for llm-coordinate-single-base Part A, on this jar's traces.

RUN (from this directory)
  python3 -I smoke_trace_check.py . smoke_cryptoapp_20261008.trace.gz > smoke_trace_check.out

Reuses the parsing, the arithmetic and display_of of evidence/coordinate_counterfactual.py and
replaces only its alignment: on this jar's traces the v13 list has one line per (target Name,
resolved bounds) group, in first-appearance order, and no ordinal (design D10, Risks).
"""
import collections
import gzip
import importlib.util
import json
import re
import statistics
import sys

EVID = sys.argv[1]
TRACE = sys.argv[2]
spec = importlib.util.spec_from_file_location("cf", EVID + "/coordinate_counterfactual.py")
cf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cf)

RE_LINE = re.compile(r"^  (\S+)(.*?)(?: @\((\d+),(\d+)\))?(?: \[[A-Z]+\])?\s*$")
RE_HIST = re.compile(r"^- (\w+)(?: @\((-?\d+),(-?\d+)\))?(?: (?!→)(\S+))?(.*?)(?: → (.*))?$")
RE_FULLCLS = re.compile(r"\b[a-z]\w*\.[a-z]\w*\.[A-Za-z]\w*")


def visible_centre_rect_intersect(b, w, h):
    """ApePromptBuilder.visibleCentre: Rect.intersect's edge test (zero-area inside keeps a point)."""
    if not (b[0] < w and 0 < b[2] and b[1] < h and 0 < b[3]):
        return None
    l, t, r, bt = max(b[0], 0), max(b[1], 0), min(b[2], w), min(b[3], h)
    return (l + r) >> 1, (t + bt) >> 1


def parse_list(user):
    head, _, _ = user.partition("\nRecent:")
    if "UI elements:" not in head:
        return None
    block = head.split("UI elements:", 1)[1].split("\n")[1:]
    lines = []
    for ln in block:
        if not ln.strip():
            break
        lines.append(ln)
    return lines


def parse_hist(user):
    _, sep, hist = user.partition("\nRecent:\n")
    if not sep:
        return []
    out = []
    for ln in hist.split("\n"):
        if not ln.startswith("- "):
            break
        out.append(ln)
    return out


def groups_of(acts):
    order, by = [], {}
    for a in acts:
        if not a.target:
            continue
        key = (a.name, a.bounds) if a.bounds is not None else ("#unresolved", id(a))
        if key not in by:
            by[key] = []
            order.append(key)
        by[key].append(a)
    return [(k, by[k]) for k in order]


def main():
    C = collections.Counter()
    listings = collections.defaultdict(list)
    records = []
    step, collecting = 0, None
    run_end = None
    with (gzip.open if TRACE.endswith(".gz") else open)(TRACE, "rt", errors="replace") as fh:
        for line in fh:
            if line.startswith('{"s"'):
                collecting = None
                records.append(json.loads(line))
                continue
            if line.startswith('{"type":"RUN_END"'):
                run_end = json.loads(line)
                continue
            if line.startswith("[APE] >>>>>>>> SATA begin"):
                m = cf.RE_STEP.match(line)
                if m:
                    step = int(m.group(1))
                collecting = None
                continue
            if line.startswith("[APE] Sata Strategy: buffer size"):
                collecting = []
                listings[step].append(collecting)
                continue
            if collecting is not None:
                m = cf.RE_ACTION.match(line)
                if m:
                    collecting.append(cf.parse_action_rest(m.group(3), m.group(4)))
                elif not cf.RE_COUNTER.match(line):
                    collecting = None

    bad = collections.defaultdict(list)
    copy_dy = collections.defaultdict(list)
    st_of = {r["s"]: r.get("st") for r in records}
    rec_of = {r["s"]: r for r in records}
    steps_sorted = sorted(st_of)
    offtree = collections.defaultdict(list)

    # model-action steps in order: the history's source (D12)
    # A model action the restart check replaced closes without `out` and leaves no history entry
    # (design D12, review WR-01): the Logger line of the start action follows its selection.
    restarted = set()
    cur = None
    with (gzip.open if TRACE.endswith(".gz") else open)(TRACE, "rt", errors="replace") as fh:
        for line in fh:
            m = cf.RE_STEP.match(line)
            if m:
                cur = int(m.group(1))
            elif "for start action" in line and cur is not None:
                restarted.add(cur)
    model_steps = []
    for r in records:
        dm = cf.RE_DEC.match((r.get("dec") or {}).get("a", ""))
        if not dm:
            continue
        if r.get("out") is None and r["s"] in restarted:
            C["model_steps_replaced_by_restart"] += 1
            continue
        model_steps.append(r["s"])

    common_disp = None
    for r in records:
        for c in r.get("llm") or []:
            if "qwen" in c and cf.display_of(c):
                common_disp = cf.display_of(c)
                break
        if common_disp:
            break

    for r in records:
        s, st = r["s"], r.get("st")
        dec = r.get("dec") or {}
        C["steps"] += 1
        if dec.get("src") == "LLM":
            C["steps_llm_decision"] += 1
        for c in r.get("llm") or []:
            C["calls"] += 1
            res = c.get("result")
            C["result:" + str(res) + ("/" + c["reason"] if c.get("reason") else "")] += 1
            disp = cf.display_of(c) if "qwen" in c else None
            if "qwen" in c and disp is None:
                C["display_unresolved"] += 1
            dw, dh = disp or common_disp
            C["display:%dx%d" % (dw, dh)] += 1
            user = c.get("user", "")
            sysm = c.get("sys", "")
            if "unvisited" in sysm.lower():
                bad["d14_unvisited"].append(s)

            # ---------------- list (a), D10, D11
            lines = parse_list(user)
            ls = listings.get(s, [])
            aligned = None
            if lines is not None:
                for acts in reversed(ls):
                    gs = groups_of(acts)
                    if len(gs) != len(lines):
                        continue
                    ok = True
                    for ln, (key, members) in zip(lines, gs):
                        m = RE_LINE.match(ln)
                        a0 = members[0]
                        if not m or m.group(1) != (a0.simple if a0.bounds is not None else "View"):
                            ok = False
                            break
                    if ok:
                        aligned = (acts, gs)
                        break
            if aligned is None:
                C["align_no"] += 1
                bad["align_no"].append((s, c.get("call")))
                continue
            C["align_yes"] += 1
            acts, gs = aligned
            listed = {}
            names_by_bounds = collections.defaultdict(set)
            for ln, (key, members) in zip(lines, gs):
                C["list_lines"] += 1
                if re.match(r"^\s*\d", ln):
                    bad["d10_ordinal"].append((s, ln))
                m = RE_LINE.match(ln)
                coord = (int(m.group(3)), int(m.group(4))) if m.group(3) else None
                a0 = members[0]
                if len(members) > 1:
                    C["list_lines_collapsed"] += 1
                if a0.bounds is None:
                    C["list_unresolved"] += 1
                    if "@(" in ln:
                        bad["unresolved_with_coord"].append((s, ln))
                    continue
                names_by_bounds[a0.bounds].add(a0.name)
                vis = visible_centre_rect_intersect(a0.bounds, dw, dh)
                if vis is None:
                    C["list_offscreen"] += 1
                    if coord is not None:
                        bad["offscreen_with_coord"].append((s, ln, a0.bounds))
                    continue
                exp = (cf.to_normalized(vis[0], dw), cf.to_normalized(vis[1], dh))
                if coord == exp:
                    C["list_coord_ok"] += 1
                else:
                    bad["list_coord_mismatch"].append((s, ln, a0.bounds, exp))
                if a0.bounds[3] - a0.bounds[1] == 0 or a0.bounds[2] - a0.bounds[0] == 0:
                    C["list_zero_area_inside"] += 1
                listed.setdefault(coord, []).append((a0, vis))
            # D10: identical bounds, distinct Names keep a line each
            for b, names in names_by_bounds.items():
                if len(names) > 1:
                    C["list_same_bounds_distinct_names"] += len(names)
            # D10: no repeated (Name, bounds) pair among lines (by construction of the alignment)
            keys = [k for k, _ in gs if k[0] != "#unresolved"]
            if len(keys) != len(set(keys)):
                bad["d10_repeat"].append(s)

            # ---------------- answers (b), (c)
            if "qwen" in c:
                qx, qy = c["qwen"]
                px, py = c["px"]
                tool = c.get("tool")
                if (qx, qy) in listed and tool in ("click", "long_click", "type_text"):
                    C["copied"] += 1
                    a0, vis = listed[(qx, qy)][0]
                    band = "%d-%d" % (vis[1] // 300 * 300, vis[1] // 300 * 300 + 299)
                    copy_dy[band].append(py - vis[1])
                    copy_dy["ALL"].append(py - vis[1])
                    if not cf.contains(a0.bounds, px, py):
                        bad["copy_outside_widget"].append((s, (qx, qy), (px, py), a0.bounds))
                if res == "no_match" and c.get("reason") == "boundary" and tool in ("click", "long_click"):
                    inspace = 0 <= qx <= 999 and 0 <= qy <= 999
                    C["boundary_click"] += 1
                    if inspace and py >= 63:
                        bad["boundary_unexpected"].append((s, (qx, qy), (px, py)))
                if res == "llm_tap":
                    for (ox, oy) in offtree[st]:
                        if abs(ox - px) <= 2 and abs(oy - py) <= 2:
                            C["offtree_within_2px_of_earlier"] += 1
                            break
                    offtree[st].append((px, py))
                    if py > 1686:
                        C["offtree_below_1686"] += 1

            # ---------------- history (d), (e), D9, D13
            hist = parse_hist(user)
            prior = [j for j in model_steps if j < s]
            src = prior[-len(hist):] if hist else []
            if len(src) != len(hist):
                bad["hist_source_short"].append((s, len(hist), len(src)))
            for ln, j in zip(hist, src):
                C["hist_entries"] += 1
                m = RE_HIST.match(ln)
                if not m:
                    bad["hist_unparsed"].append((s, ln))
                    continue
                if m.group(2) is not None:
                    hx, hy = int(m.group(2)), int(m.group(3))
                    if not (0 <= hx <= 999 and 0 <= hy <= 999):
                        bad["hist_coord_out_of_space"].append((s, ln))
                    if (hx, hy) == (0, 0):
                        bad["hist_zero"].append((s, ln))
                if RE_FULLCLS.search(m.group(4) or ""):
                    bad["hist_full_class"].append((s, ln))
                result = m.group(6)
                nxt = [k for k in steps_sorted if k > j]
                if not nxt:
                    continue
                n1 = nxt[0]
                prev = [k for k in steps_sorted if k < j]
                stj, stn = st_of[j], st_of[n1]
                stp = st_of[prev[-1]] if prev else None
                # the step's own recorded transition (out.target) when resolved; the next record's
                # st can differ after a refinement in the same update, which runs after the fill
                tgt = (rec_of[j].get("out") or {}).get("target")
                if tgt is not None:
                    if tgt != stn:
                        C["hist_target_differs_from_next_st"] += 1
                    stn = tgt
                else:
                    C["hist_no_out_target"] += 1
                if stn == stj:
                    exp = "same"
                elif stp is not None and stn == stp:
                    exp = "previous screen"
                else:
                    exp = "new screen"
                if result == exp:
                    C["hist_result_ok"] += 1
                else:
                    bad["hist_result_mismatch"].append((s, ln, j, (stp, stj, stn), exp))

        # ground truth for executed decisions
    print("RUN_END:", json.dumps(run_end.get("counters", {}).get("llm", {})) if run_end else None)
    for k in sorted(C):
        print("%-40s %d" % (k, C[k]))
    calls = C["calls"]
    dp = C["result:no_match/dead_pair"]
    print("dead_pair share: %d/%d = %.1f%%" % (dp, calls, 100.0 * dp / calls if calls else 0))
    print("LLM-decided executed steps: %d/%d = %.1f%%" % (
        C["steps_llm_decision"], C["steps"], 100.0 * C["steps_llm_decision"] / C["steps"]))
    print("copy tap y - visible-centre y, median by band:")
    for band in sorted(copy_dy, key=lambda b: (b == "ALL", int(b.split("-")[0]) if b != "ALL" else 0)):
        v = copy_dy[band]
        print("  %-10s n=%-4d median=%s min=%d max=%d" % (band, len(v), statistics.median(v), min(v), max(v)))
    for k, v in sorted(bad.items()):
        print("BAD %s: %d" % (k, len(v)))
        for x in v[:6]:
            print("    ", x)


main()
