#!/usr/bin/env python3
"""Curation analysis of the v13 "UI elements:" list (APE-RV LLM arm, jar e93dea86).

QUESTION
  The v13 user text (ApePromptBuilder.buildRvsmartV13UserText) lists ONE LINE PER
  target-requiring ModelAction (MODEL_CLICK, MODEL_LONG_CLICK, MODEL_SCROLL_*), as
  `  N. Class "text" @(x,y) [MOP] <metadata>`. The tools are click / long_click / type_text / back;
  scroll is not a tool, and CoordinateMapper restricts click to MODEL_CLICK. Should the list be
  curated: (1) drop scroll-only entries, (2) one line per widget, (3) mark long-click-only widgets,
  (4) drop containers whose children are listed, (5) drop the `N.` prefixes?

INPUTS (same as coordinate_counterfactual.py, from which parsing/alignment is copied so this file is
self-contained): plain APE `.trace` files; NDJSON step records (`{"s"...`) carry `dec`, `llm[]`
(`tool`, `qwen`, `px`, `result`, `reason`, `tok:[in,out]`, `sys`, `user`) and `out`; after every
`Sata Strategy: buffer size` the state's action listing (State.printActions) gives, per action, its
type and resolved-node pixel bounds, in `state.getActions()` order (the order of the prompt list).

DEFINITIONS
  * aligned call: prompt entries line up 1:1 with the listing's target-requiring actions (same count,
    same simple class, one integer root base reproducing every coordinate). Only aligned calls count.
  * entry = one prompt line = one target-requiring ModelAction. Its widget = its resolved-node bounds
    (WIDGET IDENTITY BY BOUNDS: two distinct nodes with identical bounds merge). Entries with no
    resolved node (`@(0,0)`, bounds None) are category `nonode`, each its own "widget".
  * widget action set = types of all entries sharing the bounds. Widget category:
      clickable      : set contains MODEL_CLICK
      longclick_only : contains MODEL_LONG_CLICK, not MODEL_CLICK
      scroll_only    : only MODEL_SCROLL_* types
  * duplicate line: an entry whose widget already has an earlier entry (k lines for a widget -> k-1
    duplicates); "one line per widget" removes exactly these. identical-text duplicate: the line text
    after the `N. ` prefix equals an earlier line's text (what the model literally sees repeated).
  * container entry: its bounds strictly contain (contain and differ from) another listed entry's
    bounds.
  * copied answer: tool in {click,long_click,type_text} and qwen equals an entry coordinate.
    Intended entry: among entries with that coordinate, the first whose type fits the tool
    (click->MODEL_CLICK, long_click->MODEL_LONG_CLICK, type_text->input class), else the first.
    Categories of a copy are those of the intended entry's widget.
    history = qwen equals a `Recent:` coordinate only; visual = neither.
  * exposure baselines (C): expected copies if the model picked (a) a uniform random LINE, (b) a
    uniform random WIDGET, summed over the same copied calls. ratio = observed / expected.
  * position (C): index of the FIRST entry carrying the copied coordinate, binned by decile of
    (i-1)/n. Its uniform-line expectation maps every line j to the first index with j's
    coordinate, so duplicate-induced earliness is in the baseline.
  * acting call: in a step with dec.src == LLM, the last call with result matched/llm_tap; its
    executed action is dec.a (type + bounds). relation of executed vs intended widget: same / child
    (executed strictly inside intended) / parent / other / llm_tap / back.
  * new_state: out.new_state of the same step record; rate over records with a resolved out
    (out present and out.resolved != false).
  * chars: "UI block" = `UI elements:\n` + entry lines (each incl. its newline). Token estimate:
    OLS of tok_in on len(sys)+len(user) over portrait calls (fixed image size -> constant image
    tokens in the intercept). slope = text tokens per char (APPROXIMATION: a uniform rate; digits are
    single tokens in Qwen, so the digit-heavy `N. ` prefix is under-estimated).

CAVEATS: call-weighted (every call pays its tokens; repeated calls on one state count again);
observational -- over/under-selection says what the model chose under the current list, not what it
would do under a curated one.

RUN
  python3 curation_analysis.py          # E6 arm 4 (default)
  python3 curation_analysis.py e5b      # E5b v13_A
"""

import collections
import glob
import json
import multiprocessing
import os
import re
import sys

E6_ROOT = ("/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/"
           "rvsec-study03-replication-package/data/raw/e6-campaign")
E6_GLOB = "e6_0?/e6_0?/*/*__aperv:e6_mop_on_llm_90.trace"
E5B_ROOT = ("/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/"
            "rvsec/rv-android/data/results")
E5B_GLOB = "e5bm1_*/e5bm1_*/*/*e5b_m1_v13_A.trace"
# overwritten on 2026-10-05 by a new run (see coordinate_counterfactual.py)
EXCLUDE_APKS = {("/e6-campaign/", "com.ds.avare_404.apk")}

INPUT_CLASSES = {"android.widget.EditText", "android.widget.AutoCompleteTextView",
                 "android.widget.SearchView", "androidx.appcompat.widget.SearchView"}
TYPES = ("MODEL_SCROLL_TOP_DOWN", "MODEL_SCROLL_BOTTOM_UP", "MODEL_SCROLL_LEFT_RIGHT",
         "MODEL_SCROLL_RIGHT_LEFT", "MODEL_LONG_CLICK", "MODEL_CLICK", "MODEL_BACK", "MODEL_MENU",
         "MODEL_LLM_TAP")
TARGET_TYPES = {"MODEL_CLICK", "MODEL_LONG_CLICK", "MODEL_SCROLL_TOP_DOWN",
                "MODEL_SCROLL_BOTTOM_UP", "MODEL_SCROLL_LEFT_RIGHT", "MODEL_SCROLL_RIGHT_LEFT"}
CATS = ("clickable", "longclick_only", "scroll_only", "nonode")

RE_STEP = re.compile(r"^\[APE\] >>>>>>>> SATA begin step \[(\d+)\]")
RE_COUNTER = re.compile(r"^\[APE\]\s+\d+\s\s[A-Z_]+\s*$")
RE_ACTION = re.compile(r"^\[APE\]\s+(\d+) (\S*?)@(" + "|".join(TYPES) + r")(.*)$")
RE_TAIL = re.compile(
    r"^(.*?)\[P=(-?\d+)\]\[T=(-?\d+)\]\[((?:, (?:INVALID|DISABLED|UNVISITED))*)\]"
    r"(?:\[S=[^\]]*\]\[RN=(\d+)\]\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\]\[(.*)\])?")
RE_DEC = re.compile(r"^(\S*?)@(" + "|".join(TYPES) + r")(.*)$")
RE_ENTRY = re.compile(r"(?m)^  (\d+)\. (\S+)")
RE_COORD = re.compile(r" @\((\d+),(\d+)\)")
RE_ATTR = re.compile(r"(?:^|(?<=;))(class|resource-id)=([^;]*);")
RE_SEG = re.compile(r"(?:^|(?<=[;/]))class=")


# ----------------------------------------------------------------------------- parsing (copied)
def jdiv2(a):
    return a // 2 if a >= 0 else -((-a) // 2)


def base_range(c, v, hi=4000):
    if c <= 0:
        return (1, hi) if v == 0 else None
    if v >= 999:
        lo, up = 1, (c * 1000) // 999
    elif v == 0:
        lo, up = c * 1000 + 1, hi
    else:
        lo, up = (c * 1000) // (v + 1) + 1, (c * 1000) // v
    lo, up = max(lo, 1), min(up, hi)
    return (lo, up) if lo <= up else None


class Act:
    __slots__ = ("type", "name", "valid", "bounds", "cls", "target")

    def __init__(self, typ, name, valid, bounds):
        self.type, self.name, self.valid, self.bounds = typ, name, valid, bounds
        self.target = typ in TARGET_TYPES
        segs = [m.start() for m in RE_SEG.finditer(name)] if name else []
        seg = name[segs[-1]:] if segs else (name or "")
        self.cls = dict(RE_ATTR.findall(seg)).get("class", "")

    @property
    def simple(self):
        return self.cls[self.cls.rfind(".") + 1:] if self.cls else "View"

    @property
    def is_input(self):
        return self.bounds is not None and self.cls in INPUT_CLASSES


def parse_action_rest(typ, rest):
    m = RE_TAIL.match(rest)
    if not m:
        return Act(typ, rest, True, None)
    bounds = tuple(int(m.group(i)) for i in range(6, 10)) if m.group(5) is not None else None
    return Act(typ, m.group(1), "INVALID" not in m.group(4), bounds)


def parse_prompt(user):
    """(entries [(cls, x, y, line_text_without_prefix, line_chars_incl_nl, prefix_chars)],
    block_chars, history coords)."""
    head, _, hist = user.partition("\nRecent:")
    if "UI elements:" not in head:
        return None, 0, set()
    block = head.split("UI elements:", 1)[1]
    starts = list(RE_ENTRY.finditer(block))
    entries = []
    for i, m in enumerate(starts):
        if int(m.group(1)) != i + 1:
            return None, 0, set()
        end = starts[i + 1].start() if i + 1 < len(starts) else len(block)
        c = RE_COORD.search(block, m.end(), end)
        if not c:
            return None, 0, set()
        seg = block[m.start():end]
        line = seg.split("\n", 1)[0]
        prefix = len(m.group(1)) + 2           # "N. "
        body = line[2 + prefix:]
        entries.append((m.group(2), int(c.group(1)), int(c.group(2)), body, len(line) + 1, prefix))
    block_chars = len("UI elements:\n") + sum(e[4] for e in entries)
    history = {(int(a), int(b)) for a, b in re.findall(r"@\((\d+),(\d+)\)", hist)}
    return entries, block_chars, history


def infer_base(entries, targets):
    wlo, whi, hlo, hhi = 1, 4000, 1, 4000
    for e, a in zip(entries, targets):
        x, y = e[1], e[2]
        if a.bounds is None:
            if (x, y) != (0, 0):
                return None
            continue
        l, t, r, b = a.bounds
        rx, ry = base_range(jdiv2(l + r), x), base_range(jdiv2(t + b), y)
        if rx is None or ry is None:
            return None
        wlo, whi = max(wlo, rx[0]), min(whi, rx[1])
        hlo, hhi = max(hlo, ry[0]), min(hhi, ry[1])
        if wlo > whi or hlo > hhi:
            return None
    return wlo, whi, hlo, hhi


def strictly_contains(a, b):
    return a != b and a[0] <= b[0] and a[1] <= b[1] and a[2] >= b[2] and a[3] >= b[3]


def relation(ex, ib):
    if ex is None or ib is None:
        return "other"
    if ex == ib:
        return "same"
    if strictly_contains(ib, ex):
        return "child"
    if strictly_contains(ex, ib):
        return "parent"
    return "other"


def tclass(t):
    if t.startswith("MODEL_SCROLL"):
        return "SCROLL"
    return {"MODEL_CLICK": "CLICK", "MODEL_LONG_CLICK": "LONG_CLICK"}.get(t, "other")


# ----------------------------------------------------------------------------- per file
def analyse_list(entries, targets):
    """Per-entry annotations for one aligned prompt."""
    n = len(entries)
    wtypes = collections.defaultdict(set)
    for a in targets:
        if a.bounds is not None:
            wtypes[a.bounds].add(tclass(a.type))
    bset = [a.bounds for a in targets if a.bounds is not None]
    uniq_b = set(bset)
    cat, dup, idup, cont = [], [], [], []
    seen_b, seen_txt = set(), set()
    first_of_coord = {}
    for i, (e, a) in enumerate(zip(entries, targets)):
        b = a.bounds
        if b is None:
            cat.append("nonode")
            dup.append(False)
            cont.append(False)
        else:
            ts = wtypes[b]
            cat.append("clickable" if "CLICK" in ts else
                       "longclick_only" if "LONG_CLICK" in ts else
                       "scroll_only" if ts == {"SCROLL"} else "other")
            dup.append(b in seen_b)
            seen_b.add(b)
            cont.append(any(strictly_contains(b, o) for o in uniq_b))
        idup.append(e[3] in seen_txt)
        seen_txt.add(e[3])
        first_of_coord.setdefault((e[1], e[2]), i)
    n_widgets = len(uniq_b) + sum(1 for a in targets if a.bounds is None)
    return dict(n=n, cat=cat, dup=dup, idup=idup, cont=cont, n_widgets=n_widgets,
                first=[first_of_coord[(e[1], e[2])] for e in entries], wtypes=wtypes)


def decile(i, n):
    return min(9, (10 * i) // n)


def process(path):
    apk = os.path.basename(os.path.dirname(path))
    C = collections.Counter()
    F = collections.Counter()        # float accumulators
    listings = collections.defaultdict(list)
    step, collecting, records = 0, None, []
    with open(path, "rt", errors="replace") as fh:
        for line in fh:
            if line.startswith('{"s"'):
                collecting = None
                try:
                    records.append(json.loads(line))
                except ValueError:
                    C[("parse", "bad_json")] += 1
                continue
            if line.startswith("[APE] >>>>>>>> SATA begin"):
                m = RE_STEP.match(line)
                if m:
                    step = int(m.group(1))
                collecting = None
                continue
            if line.startswith("[APE] Sata Strategy: buffer size"):
                collecting = []
                listings[step].append(collecting)
                continue
            if collecting is not None:
                m = RE_ACTION.match(line)
                if m:
                    collecting.append(parse_action_rest(m.group(3), m.group(4)))
                elif not RE_COUNTER.match(line):
                    collecting = None
    C[("files",)] += 1

    for r in records:
        s = r["s"]
        dec = r.get("dec") or {}
        out = r.get("out")
        resolved = isinstance(out, dict) and out.get("resolved", True) is not False
        ns = bool(resolved and out.get("new_state"))
        src = dec.get("src", "none")
        C[("step_src", src, "resolved" if resolved else "unresolved")] += 1
        if resolved:
            C[("step_src_ns", src)] += ns
        calls = [c for c in (r.get("llm") or []) if "qwen" in c]
        acting = None
        if src == "LLM":
            for c in calls:
                if c.get("result") in ("matched", "llm_tap"):
                    acting = c
        ex = None
        if acting is not None:
            dm = RE_DEC.match(dec.get("a", ""))
            if dm:
                ex = parse_action_rest(dm.group(2), dm.group(3))
        for c in calls:
            C[("calls",)] += 1
            entries, block_chars, history = parse_prompt(c.get("user", ""))
            aligned = None
            if entries is not None:
                for acts in reversed(listings.get(s, [])):
                    targets = [a for a in acts if a.target]
                    if len(targets) != len(entries):
                        continue
                    if any(e[0] != (a.simple if a.bounds is not None else "View")
                           for e, a in zip(entries, targets)):
                        continue
                    if infer_base(entries, targets) is None:
                        continue
                    aligned = targets
                    break
            if aligned is None:
                C[("align", "no")] += 1
                continue
            C[("align", "yes")] += 1
            targets = aligned
            L = analyse_list(entries, targets)
            n = L["n"]
            tool = c.get("tool")
            qx, qy = c["qwen"]
            px = c.get("px") or [0, 0]
            portrait = True
            # ---------- A. composition
            C[("A", "prompts")] += 1
            C[("A", "entries")] += n
            C[("A", "empty")] += n == 0
            C[("A", "widgets")] += L["n_widgets"]
            for a in targets:
                C[("A", "type", tclass(a.type))] += 1
            for i in range(n):
                C[("A", "cat", L["cat"][i])] += 1
                C[("A", "dup")] += L["dup"][i]
                C[("A", "idup")] += L["idup"][i]
                if L["cont"][i]:
                    C[("A", "cont", L["cat"][i])] += 1
            for b, ts in L["wtypes"].items():
                k = ("clickable" if "CLICK" in ts else "longclick_only" if "LONG_CLICK" in ts
                     else "scroll_only" if ts == {"SCROLL"} else "other")
                C[("A", "wcat", k)] += 1
            C[("A", "prompts_with_scroll_only")] += "scroll_only" in L["cat"]
            C[("A", "prompts_with_dup")] += any(L["dup"])
            C[("A", "prompts_with_cont")] += any(L["cont"])
            C[("A", "prompts_with_lco")] += "longclick_only" in L["cat"]
            C[("A", "nbin", min(n, 60) // 10 * 10 if n < 60 else 60)] += 1
            # ---------- B. chars / tokens
            user = c.get("user", "")
            sysc = c.get("sys", "")
            total = len(user) + len(sysc)
            F["user"] += len(user)
            F["sys"] += len(sysc)
            F["block"] += block_chars
            rm_scroll = sum(e[4] for e, k in zip(entries, L["cat"]) if k == "scroll_only")
            rm_dup = sum(e[4] for e, d in zip(entries, L["dup"]) if d)
            rm_union = sum(e[4] for e, k, d in zip(entries, L["cat"], L["dup"])
                           if d or k == "scroll_only")
            rm_cont = sum(e[4] for e, k in zip(entries, L["cont"]) if k)
            rm_prefix = sum(e[5] for e in entries)
            for k, v in (("rm_scroll", rm_scroll), ("rm_dup", rm_dup), ("rm_union", rm_union),
                         ("rm_cont", rm_cont), ("rm_prefix", rm_prefix)):
                F[k] += v
            tok = c.get("tok")
            dispw = None
            if tok and len(tok) == 2:
                # portrait test: qwen->px with display 1080 wide
                portrait = int(qx / 1000.0 * 1080) == px[0] or px[0] == 1079
                if portrait:
                    F["n_tok"] += 1
                    F["x"] += total
                    F["y"] += tok[0]
                    F["xx"] += total * total
                    F["xy"] += total * tok[0]
                    F["yy"] += tok[0] * tok[0]
                F["tok_in_all"] += tok[0]
                F["tok_out_all"] += tok[1]
                F["n_tok_all"] += 1
            # ---------- answer kind
            ans = (qx, qy)
            listed = [(e[1], e[2]) for e in entries]
            if tool in ("click", "long_click", "type_text"):
                kind = "copied" if ans in listed else ("history" if ans in history else "visual")
            else:
                kind = "back" if tool == "back" else "none"
            C[("kind", tool, kind)] += 1
            res = c.get("result", "?") + ("/" + c["reason"] if c.get("reason") else "")
            is_act = c is acting
            group = kind
            ib = None
            if kind == "copied":
                cand = [i for i, e in enumerate(entries) if (e[1], e[2]) == ans]

                def fits(a):
                    if tool == "click":
                        return a.type == "MODEL_CLICK"
                    if tool == "long_click":
                        return a.type == "MODEL_LONG_CLICK"
                    return a.is_input
                ii = next((i for i in cand if fits(targets[i])), cand[0])
                ia = targets[ii]
                ib = ia.bounds
                k = L["cat"][ii]
                cont = L["cont"][ii]
                dupw = L["dup"][ii] or (ib is not None and sum(1 for a in targets if a.bounds == ib) > 1)
                group = "copied:" + k
                # ---------- C. exposure
                C[("C", "n", tool)] += 1
                C[("C", "obs", tool, "cat", k)] += 1
                C[("C", "obs", tool, "cont")] += cont
                C[("C", "obs", tool, "dupw")] += dupw
                C[("C", "obs", tool, "fits")] += fits(ia)
                for kk in CATS:
                    F[("C", "expL", tool, kk)] += L["cat"].count(kk) / n
                F[("C", "expL", tool, "cont")] += sum(L["cont"]) / n
                # widget with >1 line: share of lines belonging to such widgets
                bc = collections.Counter(a.bounds for a in targets if a.bounds is not None)
                F[("C", "expL", tool, "dupw")] += sum(1 for a in targets
                                                      if a.bounds is not None and bc[a.bounds] > 1) / n
                nw = L["n_widgets"]
                wc = collections.Counter()
                seenb = set()
                for a, kk, ct in zip(targets, L["cat"], L["cont"]):
                    if a.bounds is None:
                        wc[kk] += 1
                        continue
                    if a.bounds in seenb:
                        continue
                    seenb.add(a.bounds)
                    wc[kk] += 1
                    wc["cont"] += ct
                    wc["dupw"] += bc[a.bounds] > 1
                for kk in CATS + ("cont", "dupw"):
                    F[("C", "expW", tool, kk)] += wc[kk] / nw
                # position
                fi = L["first"][ii]
                C[("C", "pos", decile(fi, n))] += 1
                C[("C", "pos_first")] += fi == 0
                for j in range(n):
                    F[("C", "pos_exp", decile(L["first"][j], n))] += 1.0 / n
                F[("C", "pos_first_exp")] += sum(1 for j in range(n) if L["first"][j] == 0) / n
                C[("C", "pos_n")] += 1
                if cont:
                    group += "+cont"
            C[("D", tool, group, res)] += 1
            if kind == "copied" and L["cont"][ii]:
                C[("F", tool, L["cat"][ii], "res", res)] += 1
            if is_act:
                C[("D_act", tool, group)] += 1
                if resolved:
                    C[("D_res", tool, group)] += 1
                    C[("D_ns", tool, group)] += ns
                if ex is not None:
                    et = tclass(ex.type) if ex.type in TARGET_TYPES else ex.type
                    C[("D_exec", tool, group, et)] += 1
                    if kind == "copied":
                        if ex.type == "MODEL_LLM_TAP":
                            rel = "llm_tap"
                        elif ex.type == "MODEL_BACK":
                            rel = "back"
                        else:
                            rel = relation(ex.bounds, ib)
                        C[("D_rel", tool, group, rel)] += 1
                        if resolved:
                            C[("D_rel_res", tool, group, rel)] += 1
                            C[("D_rel_ns", tool, group, rel)] += ns
    return C, F


# ----------------------------------------------------------------------------- report
def pct(a, b):
    return f"{100.0 * a / b:5.1f}%" if b else "  n/a"


def main():
    args = sys.argv[1:]
    if args and args[0] == "e5b":
        root, pattern = E5B_ROOT, E5B_GLOB
    elif len(args) >= 2:
        root, pattern = args[0], args[1]
    else:
        root, pattern = E6_ROOT, E6_GLOB
    files = sorted(glob.glob(os.path.join(root, pattern)))
    excluded = [f for f in files for frag, apk in EXCLUDE_APKS
                if frag in f and os.path.basename(os.path.dirname(f)) == apk]
    files = [f for f in files if f not in excluded]
    if excluded:
        print("excluded (overwritten in place):", [os.path.relpath(f, root) for f in excluded])
    C, F = collections.Counter(), collections.Counter()
    with multiprocessing.Pool(min(32, os.cpu_count() or 1)) as pool:
        for c, f in pool.imap(process, files, chunksize=1):
            C.update(c)
            F.update(f)
    report(root, pattern, C, F)


def report(root, pattern, C, F):
    P = print
    P(f"# inputs: {root}/{pattern}")
    P(f"files: {C[('files',)]}; LLM calls with an answer: {C[('calls',)]}; aligned: "
      f"{C[('align', 'yes')]}; not aligned: {C[('align', 'no')]}")
    np_ = C[("A", "prompts")]
    ne = C[("A", "entries")]

    P("\n## A. List composition (aligned calls, call-weighted)")
    P(f"prompts {np_}; entries {ne} (mean {ne / np_:.2f}/prompt); empty lists {C[('A', 'empty')]}; "
      f"distinct widgets {C[('A', 'widgets')]} (mean {C[('A', 'widgets')] / np_:.2f}/prompt)")
    P("list length distribution (bins of 10, 60=60+):",
      {k[2]: v for k, v in sorted(C.items(), key=str) if k[:2] == ("A", "nbin")})
    P("entries by action type:", ", ".join(
        f"{t} {C[('A', 'type', t)]} ({pct(C[('A', 'type', t)], ne)})"
        for t in ("CLICK", "LONG_CLICK", "SCROLL", "other")))
    P("entries by widget category:", ", ".join(
        f"{k} {C[('A', 'cat', k)]} ({pct(C[('A', 'cat', k)], ne)})" for k in CATS + ("other",)))
    nwb = sum(v for k, v in C.items() if k[:2] == ("A", "wcat"))
    P("widgets (with node) by category:", ", ".join(
        f"{k} {C[('A', 'wcat', k)]} ({pct(C[('A', 'wcat', k)], nwb)})"
        for k in ("clickable", "longclick_only", "scroll_only", "other")))
    P(f"duplicate lines (same bounds as an earlier entry): {C[('A', 'dup')]} ({pct(C[('A', 'dup')], ne)}); "
      f"one-line-per-widget list = {ne - C[('A', 'dup')]} lines")
    P(f"identical-text duplicate lines (same text after the prefix): {C[('A', 'idup')]} "
      f"({pct(C[('A', 'idup')], ne)})")
    ncont = sum(v for k, v in C.items() if k[:2] == ("A", "cont"))
    P(f"container entries: {ncont} ({pct(ncont, ne)}) by category: " + ", ".join(
        f"{k} {C[('A', 'cont', k)]}" for k in ("clickable", "longclick_only", "scroll_only", "other")))
    for k, lab in (("prompts_with_scroll_only", "a scroll-only entry"), ("prompts_with_dup", "a duplicate"),
                   ("prompts_with_cont", "a container"), ("prompts_with_lco", "a long-click-only entry")):
        P(f"prompts with {lab}: {C[('A', k)]} ({pct(C[('A', k)], np_)})")

    P("\n## B. Character / token cost (aligned calls)")
    tot = F["user"] + F["sys"]
    P(f"mean chars: system {F['sys'] / np_:.0f}, user {F['user'] / np_:.0f}, UI block "
      f"{F['block'] / np_:.0f} ({pct(F['block'], F['user'])} of user, {pct(F['block'], tot)} of sys+user)")
    for k, lab in (("rm_scroll", "(1) scroll-only lines"), ("rm_dup", "(2) duplicate lines (1/widget)"),
                   ("rm_union", "(1)+(2) together"), ("rm_cont", "(4) container lines (upper bound)"),
                   ("rm_prefix", "(5) 'N. ' prefixes")):
        P(f"  {lab:34} mean {F[k] / np_:7.1f} chars = {pct(F[k], F['block'])} of block, "
          f"{pct(F[k], tot)} of sys+user")
    n = F["n_tok"]
    if n > 1:
        sx, sy, sxx, sxy, syy = F["x"], F["y"], F["xx"], F["xy"], F["yy"]
        slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
        icpt = (sy - slope * sx) / n
        r2 = (n * sxy - sx * sy) ** 2 / ((n * sxx - sx * sx) * (n * syy - sy * sy))
        mtok = sy / n
        mtxt = slope * sx / n
        P(f"tokens (portrait calls n={int(n)}): mean tok_in {mtok:.0f}; OLS tok_in = {icpt:.0f} + "
          f"{slope:.4f}*chars (R^2 {r2:.3f}; {1 / slope:.2f} chars/token)")
        P(f"  => est. non-text (image + chat template) {icpt:.0f} tok ({pct(icpt, mtok)}); "
          f"text {mtxt:.0f} tok ({pct(mtxt, mtok)})")
        for k, lab in (("rm_scroll", "(1)"), ("rm_dup", "(2)"), ("rm_union", "(1)+(2)"),
                       ("rm_cont", "(4)"), ("rm_prefix", "(5)")):
            t = slope * F[k] / np_
            P(f"  {lab:8} est. {t:6.1f} tok/call = {pct(t, mtok)} of tok_in, {pct(t, mtxt)} of text tokens")
    P(f"all calls: mean tok_in {F['tok_in_all'] / max(F['n_tok_all'], 1):.0f}, "
      f"mean tok_out {F['tok_out_all'] / max(F['n_tok_all'], 1):.1f}")

    P("\n## C. Model choice vs exposure (copied answers)")
    P("answer kinds by tool:", {k[1:]: v for k, v in sorted(C.items(), key=str) if k[0] == "kind"})
    for tool in ("click", "long_click", "type_text"):
        nc = C[("C", "n", tool)]
        if not nc:
            continue
        P(f"  tool={tool}: copied {nc}; intended entry fits the tool: {C[('C', 'obs', tool, 'fits')]} "
          f"({pct(C[('C', 'obs', tool, 'fits')], nc)})")
        P("    category          observed   share   exp(line)  ratio   exp(widget)  ratio")
        for kk in CATS + ("cont", "dupw"):
            o = C[("C", "obs", tool, "cat", kk)] if kk in CATS else C[("C", "obs", tool, kk)]
            el, ew = F[("C", "expL", tool, kk)], F[("C", "expW", tool, kk)]
            P(f"    {kk:16} {o:9} {pct(o, nc)} {el:10.0f} {o / el if el else 0:6.2f} {ew:11.0f} "
              f"{o / ew if ew else 0:6.2f}")
    pn = C[("C", "pos_n")]
    P(f"  position (first entry with the copied coordinate), n={pn}: decile observed / expected(line)")
    P("    " + "  ".join(f"d{d}:{C[('C', 'pos', d)]}/{F[('C', 'pos_exp', d)]:.0f}" for d in range(10)))
    P(f"    first entry: observed {C[('C', 'pos_first')]} ({pct(C[('C', 'pos_first')], pn)}), "
      f"expected {F[('C', 'pos_first_exp')]:.0f} ({pct(F[('C', 'pos_first_exp')], pn)})")

    P("\n## D. Results and outcomes by what was answered (all tools)")
    groups = sorted({(k[1], k[2]) for k in C if k[0] == "D"}, key=lambda x: (str(x[0]), x[1]))
    for tool, g in groups:
        rows = {k[3]: v for k, v in C.items() if k[0] == "D" and k[1] == tool and k[2] == g}
        tn = sum(rows.values())
        if tn < 20:
            continue
        act = C[("D_act", tool, g)]
        rs, ns = C[("D_res", tool, g)], C[("D_ns", tool, g)]
        P(f"  {str(tool):10} {g:32} calls {tn:6}  " + ", ".join(
            f"{r} {pct(v, tn).strip()}" for r, v in sorted(rows.items(), key=lambda x: -x[1])))
        ex = {k[3]: v for k, v in C.items() if k[0] == "D_exec" and k[1] == tool and k[2] == g}
        rel = {k[3]: v for k, v in C.items() if k[0] == "D_rel" and k[1] == tool and k[2] == g}
        P(f"  {'':10} {'':32} executed {act} (new_state {ns}/{rs} = {pct(ns, rs).strip()}); "
          f"exec type {dict(sorted(ex.items()))}" + (f"; vs intended {dict(sorted(rel.items()))}" if rel else ""))
        for rk in sorted(rel):
            rr, rn = C[("D_rel_res", tool, g, rk)], C[("D_rel_ns", tool, g, rk)]
            if rr >= 20:
                P(f"  {'':10} {'':32}   {rk:8} new_state {rn}/{rr} = {pct(rn, rr).strip()}")
    P("  baseline steps by dec.src (resolved out): " + ", ".join(
        f"{k[1]} {C[('step_src_ns', k[1])]}/{v} = {pct(C[('step_src_ns', k[1])], v).strip()}"
        for k, v in sorted(C.items(), key=str) if k[0] == "step_src" and k[2] == "resolved"))

    P("\n## F. Copied container entries: recorded result by tool / category")
    for k, v in sorted(((k, v) for k, v in C.items() if k[0] == "F"), key=lambda x: (str(x[0][1]), x[0][2], -x[1])):
        P(f"  {k[1]:10} {k[2]:15} {k[4]:22} {v}")


if __name__ == "__main__":
    main()
