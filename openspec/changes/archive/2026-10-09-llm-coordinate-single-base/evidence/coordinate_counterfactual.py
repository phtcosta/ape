#!/usr/bin/env python3
"""Coordinate-base counterfactual for the APE-RV LLM arm (jar e93dea86).

WHAT THIS MEASURES
  The v13 prompt lists every target-requiring action as `Class "text" @(x,y)` normalised by the
  ACTIVE WINDOW ROOT's (right, bottom); the model's answer `llm[].qwen` is turned into a pixel by the
  DISPLAY (1080x1794 on the campaign AVD): pixel = clamp((int)(q/1000.0*D), 0, D-1)
  (CoordinateNormalizer.normalize). The fix under study (openspec change
  llm-coordinate-single-base) lists coordinates with the display:
  n = clamp((int)(p*1000.0/D), 0, 999) (CoordinateNormalizer.toNormalized). A second issue is the
  CoordinateMapper boundary band: reject pixelY < H*0.05 or pixelY > H*0.94, which with H=1794
  rejects y in (1686.36, 1794), app content (the nav bar is already outside the display).

INPUTS
  Plain APE trace files (`*.trace`, or the identical `*.trace.ndjson.gz`). Each embeds
    * the NDJSON step records (lines starting with `{"s"`), with `dec`, `llm[]` (qwen, px, result,
      reason, tool, user prompt) and `out`;
    * after every `Sata Strategy: buffer size`, the state's full action list (`State.printActions`)
      with pixel bounds of each resolved node, in the same order as `state.getActions()`, which is
      the list the LLM engine receives for the prompt and the mapper.
  The listing printed during `SATA begin step [N]` belongs to step record `s == N`.

DEFINITIONS
  * aligned call: the prompt's numbered entries line up 1:1, in order, with the listing's
    target-requiring actions (same count, same simple class name, and one root base (W,H) with
    integer W,H that reproduces every listed coordinate from the widget centre; entries with no
    resolved node are listed @(0,0)).
  * list base class of a prompt (from the feasible base ranges): full (1080x1920 feasible),
    display (1080x1794 feasible, 1920 not), narrow (W < 1080: dialog/popup), short (W = 1080,
    H < 1794: keyboard-resized or bottom-sheet window), tall (W = 1080, 1794 < H < 1920: a
    full-width window ending inside the nav-bar strip), other.
  * copied answer: qwen equals an entry coordinate of the element list (before `Recent:`);
    history copy: equals a coordinate of the `Recent:` section but not of the list; visual: neither.
  * intended entry of a copied answer: among list entries carrying that coordinate, the first whose
    action type fits the tool (click->MODEL_CLICK, long_click->MODEL_LONG_CLICK, type_text->input
    class), else the first. "Intended widget" = that entry's resolved-node bounds; a hit is a
    matched action whose node has those bounds (widget identity by bounds).
  * mapper replica: CoordinateMapper.map at e93dea86 (back; boundary band; containment smallest area
    over valid target actions, click restricted to MODEL_CLICK, long_click preferring
    MODEL_LONG_CLICK then any type; type_text restricted to input classes; edge-distance snap with
    tolerance max(floor, min(w,h)/2), the floor read from each run's RUN_START (`ape.llmSnapTolerancePx`,
    150 in E6 and E5b; 50 when absent); off-tree LlmTapAction for click/long_click; fixTextEdit), then the
    dead-pair ban (5 unproductive executions of (state, Name, type) or (state, exact tap pixel);
    input-class targets exempt). Strikes are replayed from the recorded run's executed LLM
    decisions and outcomes, strictly before the step being evaluated.

SCENARIOS (copied answers; SAME-ENTRY ASSUMPTION: under the fix the model would copy the same list
entry, whose coordinate is then computed with the display base; everything else, including the ban
state, is the recorded run's)
  S0 actual pixels, bands 0.05/0.94 (the defect; replica of what ran)
  S1 display base, bands 0.05/0.94
  S2 display base, bottom band disabled, top 0.05
  S3 display base, bottom band disabled, top = status bar 63 px / 1794 (63 px measured: 210
     UIAutomator dumps show statusBarBackground [0,0][1080,63]; 420 dpi x 24 dp)
  S4 S3, with the listed coordinate taken from the centre of the node's VISIBLE part, i.e. its bounds
     intersected with the display [0,W)x[0,H) (the point MonkeySourceApe taps for a node action), and
     no coordinate at all when that intersection is empty (outcome `nocoord`: the entry cannot be
     copied). Added 2026-10-05 after the consistency review; S0-S3 are computed exactly as before.
     Section 7 (added 2026-10-05, consistency verification; earlier lines unchanged) splits S4's
     `nocoord` entries by what Rect.intersect (AOSP, and the JVM stub) does: `off_display` nodes have no
     intersection with the display; `zero_area_inside` nodes are zero-width or zero-height rects inside
     it, which Rect.intersect accepts, so the change lists them with a coordinate at the same point as
     S3 (S4 above counts them as `nocoord`). Per kind it prints the side of the display the node lies
     on with its S0 and S3 outcomes, the S3 tap pixel of the copies that snap to the intended node, and
     the S3 residual cause of the others.
     Section 8 (same date) lists, for copied answers, every changed (outcome, ban) pair from S0 to S4,
     so the effect of the change on executed actions can be weighted by per-outcome rates, such as
     the new-state rates of curation_analysis.py §D.
  Non-copied answers keep their pixel: only the band changes (S0 vs S2 vs S3 bands).

CAVEATS: counterfactuals hold the trajectory fixed (same states, same answers, same strike record);
the model's behaviour under a different prompt is not modelled beyond the same-entry assumption.

RUN
  python3 coordinate_counterfactual.py                     # E6 arm 4 (default paths)
  python3 coordinate_counterfactual.py e5b                 # E5b v13_A traces in rv-android
  python3 coordinate_counterfactual.py <ROOT> '<GLOB>'     # any traces
Output is deterministic (files sorted, results merged in file order, ties broken by key).
"""

import collections
import glob
import gzip
import json
import math
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

# com.ds.avare_404's arm-4 trace in the E6 raw directory was overwritten on 2026-10-05 (12:50-13:09)
# by a new run (run_id 20261005T...; 0 LLM answers, file still growing while this was written). The
# original Sep-30 trace is not in the directory, so the file is excluded to keep the output stable.
# This is also why settings-audit/coordinate_base.out (130,986 answers) no longer reproduces: re-run
# today it gives 130,248, the same total this script reads.
EXCLUDE_APKS = {("/e6-campaign/", "com.ds.avare_404.apk")}   # (path fragment, APK dir)

STATUS_BAR_PX = 63
DEAD_PAIR_STRIKES = 5
SNAP_FLOOR = 50
INPUT_CLASSES = {"android.widget.EditText", "android.widget.AutoCompleteTextView",
                 "android.widget.SearchView", "androidx.appcompat.widget.SearchView"}
TYPES = ("MODEL_SCROLL_TOP_DOWN", "MODEL_SCROLL_BOTTOM_UP", "MODEL_SCROLL_LEFT_RIGHT",
         "MODEL_SCROLL_RIGHT_LEFT", "MODEL_LONG_CLICK", "MODEL_CLICK", "MODEL_BACK", "MODEL_MENU",
         "MODEL_LLM_TAP")
TARGET_TYPES = {"MODEL_CLICK", "MODEL_LONG_CLICK", "MODEL_SCROLL_TOP_DOWN",
                "MODEL_SCROLL_BOTTOM_UP", "MODEL_SCROLL_LEFT_RIGHT", "MODEL_SCROLL_RIGHT_LEFT"}

RE_STEP = re.compile(r"^\[APE\] >>>>>>>> SATA begin step \[(\d+)\]")
RE_COUNTER = re.compile(r"^\[APE\]\s+\d+\s\s[A-Z_]+\s*$")
RE_ACTION = re.compile(r"^\[APE\]\s+(\d+) (\S*?)@(" + "|".join(TYPES) + r")(.*)$")
RE_TAIL = re.compile(
    r"^(.*?)\[P=(-?\d+)\]\[T=(-?\d+)\]\[((?:, (?:INVALID|DISABLED|UNVISITED))*)\]"
    r"(?:\[S=[^\]]*\]\[RN=(\d+)\]\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\]\[(.*)\])?")
RE_DEC = re.compile(r"^(\S*?)@(" + "|".join(TYPES) + r")(.*)$")
RE_TAP = re.compile(r"@\((-?\d+),(-?\d+)\)\s*$")
RE_ENTRY = re.compile(r"(?m)^  (\d+)\. (\S+)")
RE_COORD = re.compile(r" @\((\d+),(\d+)\)")
RE_ATTR = re.compile(r"(?:^|(?<=;))(class|resource-id)=([^;]*);")
RE_SEG = re.compile(r"(?:^|(?<=[;/]))class=")

SCEN = {
    "S0": (0.05, 0.94),
    "S1": (0.05, 0.94),
    "S2": (0.05, None),
    "S3": (STATUS_BAR_PX / 1794.0, None),
    "S4": (STATUS_BAR_PX / 1794.0, None),
}


# ----------------------------------------------------------------------------- arithmetic
def jdiv2(a):
    """Java int (a)/2 (truncation toward zero)."""
    return a // 2 if a >= 0 else -((-a) // 2)


def normalize(q, d):
    """CoordinateNormalizer.normalize, one axis."""
    p = int((q / 1000.0) * d)
    return max(0, min(p, d - 1))


def to_normalized(p, d):
    """Proposed CoordinateNormalizer.toNormalized, one axis."""
    return max(0, min(int(p * 1000.0 / d), 999))


def visible_centre(b, w, h):
    """Centre of bounds b intersected with [0,w)x[0,h) (Rect.intersect + centerX/Y), or None if empty."""
    l, t, r, bt = max(b[0], 0), max(b[1], 0), min(b[2], w), min(b[3], h)
    if r <= l or bt <= t:
        return None
    return (l + r) >> 1, (t + bt) >> 1


def base_range(c, v, hi=4000):
    """Integer bases b in [1,hi] with clamp((int)(c*1000/b),0,999) == v; (lo,hi) or None."""
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


# ----------------------------------------------------------------------------- parsing
class Act:
    __slots__ = ("type", "name", "valid", "bounds", "cls", "rid", "target")

    def __init__(self, typ, name, valid, bounds):
        self.type = typ
        self.name = name
        self.valid = valid
        self.bounds = bounds
        self.target = typ in TARGET_TYPES
        # A Name may be a path `ancestor-attrs;/node-attrs;`: the node is the last `class=` segment.
        segs = [m.start() for m in RE_SEG.finditer(name)] if name else []
        seg = name[segs[-1]:] if segs else (name or "")
        attrs = dict(RE_ATTR.findall(seg))
        self.cls = attrs.get("class", "")
        self.rid = attrs.get("resource-id", "")

    @property
    def simple(self):
        if not self.cls:
            return "View"
        return self.cls[self.cls.rfind(".") + 1:]

    @property
    def is_input(self):
        return self.bounds is not None and self.cls in INPUT_CLASSES


def parse_action_rest(typ, rest):
    m = RE_TAIL.match(rest)
    if not m:
        return Act(typ, rest, True, None)
    name, flags = m.group(1), m.group(4)
    valid = "INVALID" not in flags
    bounds = None
    if m.group(5) is not None:
        bounds = tuple(int(m.group(i)) for i in range(6, 10))
    return Act(typ, name, valid, bounds)


def parse_prompt(user):
    """(entries [(cls, x, y)], history coords set)."""
    head, _, hist = user.partition("\nRecent:")
    block = head.split("UI elements:", 1)[1] if "UI elements:" in head else ""
    starts = list(RE_ENTRY.finditer(block))
    entries = []
    for i, m in enumerate(starts):
        if int(m.group(1)) != i + 1:
            return None, set()
        end = starts[i + 1].start() if i + 1 < len(starts) else len(block)
        c = RE_COORD.search(block, m.end(), end)
        if not c:
            return None, set()
        entries.append((m.group(2), int(c.group(1)), int(c.group(2))))
    history = {(int(a), int(b)) for a, b in re.findall(r"@\((\d+),(\d+)\)", hist)}
    return entries, history


def infer_base(entries, targets):
    """Feasible (Wlo,Whi,Hlo,Hhi) of the root base, or None if inconsistent."""
    wlo, whi, hlo, hhi = 1, 4000, 1, 4000
    for (cls, x, y), a in zip(entries, targets):
        if a.bounds is None:
            if (x, y) != (0, 0):
                return None
            continue
        l, t, r, b = a.bounds
        rx = base_range(jdiv2(l + r), x)
        ry = base_range(jdiv2(t + b), y)
        if rx is None or ry is None:
            return None
        wlo, whi = max(wlo, rx[0]), min(whi, rx[1])
        hlo, hhi = max(hlo, ry[0]), min(hhi, ry[1])
        if wlo > whi or hlo > hhi:
            return None
    return wlo, whi, hlo, hhi


def base_class(rng, dw, dh):
    if rng is None:
        return "inconsistent"
    if dw > dh:
        return "landscape"
    wlo, whi, hlo, hhi = rng
    w_full = wlo <= dw <= whi
    if w_full and hlo <= 1920 <= hhi:
        return "full"
    if w_full and hlo <= dh <= hhi:
        return "display"
    if whi < dw:
        return "narrow"
    if w_full and hhi < dh:
        return "short"
    if w_full and dh < hlo and hhi < 1920:
        return "tall"
    return "other"


# ----------------------------------------------------------------------------- mapper replica
def contains(b, x, y):
    l, t, r, bt = b
    return l < r and t < bt and l <= x < r and t <= y < bt


def fix_text_edit(match, acts, tool):
    if tool not in ("click", "long_click"):
        return match
    if not match.is_input or match.type == "MODEL_CLICK":
        return match
    for c in acts:
        if c.type == "MODEL_CLICK" and c.valid and c.bounds == match.bounds and c.name == match.name:
            return c
    return match


def map_answer(px, py, tool, acts, w, h, top, bottom, snap=SNAP_FLOOR):
    """Replica of CoordinateMapper.map. Returns (kind, payload)."""
    if tool is None:
        return ("null", None)
    if tool == "back":
        return ("back", None)
    if py < h * top or (bottom is not None and py > h * bottom):
        return ("boundary", None)
    if not acts:
        return ("null", None)
    prefer_long = tool == "long_click"
    best, best_area = None, None
    for a in acts:
        if not a.target or not a.valid or a.bounds is None:
            continue
        if tool == "type_text" and not a.is_input:
            continue
        if tool == "click" and a.type != "MODEL_CLICK":
            continue
        if prefer_long and a.type != "MODEL_LONG_CLICK":
            continue
        if contains(a.bounds, px, py):
            area = (a.bounds[2] - a.bounds[0]) * (a.bounds[3] - a.bounds[1])
            if best is None or area < best_area:
                best, best_area = a, area
    if best is not None:
        return ("matched", fix_text_edit(best, acts, tool))
    if prefer_long:
        for a in acts:
            if not a.target or not a.valid or a.bounds is None:
                continue
            if contains(a.bounds, px, py):
                area = (a.bounds[2] - a.bounds[0]) * (a.bounds[3] - a.bounds[1])
                if best is None or area < best_area:
                    best, best_area = a, area
        if best is not None:
            return ("matched", fix_text_edit(best, acts, tool))
    best_d = None
    for a in acts:
        if not a.target or not a.valid or a.bounds is None:
            continue
        if tool == "type_text" and not a.is_input:
            continue
        if tool == "click" and a.type != "MODEL_CLICK":
            continue
        l, t, r, b = a.bounds
        dx = max(max(l - px, 0), px - r)
        dy = max(max(t - py, 0), py - b)
        d = math.hypot(dx, dy)
        tol = max(float(snap), min(r - l, b - t) / 2.0)
        if d <= tol and (best_d is None or d < best_d):
            best, best_d = a, d
    if best is not None:
        return ("matched", fix_text_edit(best, acts, tool))
    if tool in ("click", "long_click"):
        return ("llm_tap", (px, py))
    return ("null", None)


def ban_key(st, kind, payload):
    if kind == "matched":
        return (st, payload.name, payload.type)
    if kind == "llm_tap":
        return (st, "tap", payload[0], payload[1])
    return None


def verdict(kind, banned, qwen):
    if banned:
        return "no_match/dead_pair"
    if kind in ("matched", "back"):
        return "matched"
    if kind == "llm_tap":
        return "llm_tap"
    return "no_match/degenerate" if tuple(qwen) == (0, 0) else "no_match/boundary"


def display_of(call):
    """Display (W,H) implied by qwen -> px; default portrait 1080x1794."""
    qx, qy = call["qwen"]
    px, py = call["px"]
    for w, h in ((1080, 1794), (1794, 1080), (1080, 1920), (1920, 1080)):
        if normalize(qx, w) == px and normalize(qy, h) == py:
            return w, h
    return None


def wid(a):
    sid = a.rid.split("/")[-1] if a.rid else "-"
    return f"{a.simple}#{sid}"


# ----------------------------------------------------------------------------- per file
def process(path):
    apk = os.path.basename(os.path.dirname(path))
    C = collections.Counter()
    S = collections.defaultdict(set)
    W = collections.defaultdict(collections.Counter)   # widget samples
    listings = collections.defaultdict(list)
    step = 0
    collecting = None
    records = []
    snap, band0 = SNAP_FLOOR, (0.05, 0.94)
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", errors="replace") as fh:
        for line in fh:
            if line.startswith('{"type":"RUN_START"'):
                params = json.loads(line).get("params", {})
                snap = int(params.get("ape.llmSnapTolerancePx", SNAP_FLOOR))
                band0 = (float(params.get("ape.llmBoundaryTopPct", 0.05)),
                         float(params.get("ape.llmBoundaryBottomPct", 0.94)))
                C[("params", snap, band0)] += 1
                continue
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

    # --- Q4/Q5 over every listing (state = step record's st) ----------------------
    st_of = {r["s"]: r.get("st") for r in records}
    for s, ls in listings.items():
        if not ls:
            continue
        acts = ls[-1]
        key = (apk, st_of.get(s))
        S["q4_states"].add(key)
        S["q4_apks"].add(apk)
        band = [a for a in acts if a.target and a.bounds and 1686.36 < (a.bounds[1] + a.bounds[3]) / 2 < 1794]
        below = [a for a in acts if a.target and a.bounds and (a.bounds[1] + a.bounds[3]) // 2 >= 1794]
        wide = [a for a in acts if a.bounds and a.bounds[2] > 1080]
        C[("q4", "steps")] += 1
        if band:
            C[("q4", "steps_band")] += 1
            S["q4_states_band"].add(key)
            S["q4_apks_band"].add(apk)
            if any(a.valid for a in band):
                S["q4_states_band_valid"].add(key)
            for a in band:
                if a.type == "MODEL_CLICK":
                    W["q4_band_click"][wid(a)] += 1
        if below:
            C[("q4", "steps_below")] += 1
            S["q4_states_below"].add(key)
            S["q4_apks_below"].add(apk)
        if wide:
            C[("q5", "steps_listing_x>1080")] += 1
            S["q5_apks_wide"].add(apk)

    # --- per-call analysis ---------------------------------------------------------
    strikes = collections.Counter()
    for r in records:
        s, st = r["s"], r.get("st")
        calls = [c for c in (r.get("llm") or []) if "qwen" in c]
        dec = r.get("dec") or {}
        acting = None
        if dec.get("src") == "LLM":
            for c in calls:
                if c.get("result") in ("matched", "llm_tap"):
                    acting = c
        for c in calls:
            C[("calls",)] += 1
            rec = c["result"] + ("/" + c["reason"] if c.get("reason") else "")
            disp = display_of(c)
            if disp is None:
                C[("q5", "display_unresolved")] += 1
                disp = (1080, 1794)
            dw, dh = disp
            if dw > dh:
                C[("q5", "display_landscape_calls")] += 1
            qx, qy = c["qwen"]
            big = qy >= 1000 or qx >= 1000
            if c["px"][1] >= dh:
                C[("q3", "px_y>=H")] += 1
            entries, history = parse_prompt(c.get("user", ""))
            ls = listings.get(s, [])
            aligned = None
            if entries is not None:
                for acts in reversed(ls):
                    targets = [a for a in acts if a.target]
                    if len(targets) != len(entries):
                        continue
                    if any(e[0] != (a.simple if a.bounds is not None else "View")
                           for e, a in zip(entries, targets)):
                        continue
                    rng = infer_base(entries, targets)
                    if rng is None:
                        continue
                    aligned = (acts, targets, rng)
                    break
            if aligned is None:
                C[("align", "no")] += 1
                continue
            C[("align", "yes")] += 1
            acts, targets, rng = aligned
            wcls = base_class(rng, dw, dh)
            if rng[0] > 1080:
                C[("q5", "list_base_W>1080")] += 1
                S["q5_apks_listW"].add(apk)
            if dw > dh:
                S["q5_apks_landscape"].add(apk)
            C[("base", wcls)] += 1
            if wcls in ("full", "display"):
                S["base_apk_" + wcls].add(apk)
            tool = c.get("tool")
            ans = (qx, qy)
            if big:
                k0 = map_answer(c["px"][0], c["px"][1], tool, acts, dw, dh, *band0, snap)[0]
                k3 = map_answer(c["px"][0], c["px"][1], tool, acts, dw, dh, *SCEN["S3"], snap)[0]
                C[("q3_big", "y>=1000" if qy >= 1000 else "x>=1000", tool, "S0:" + k0, "S3:" + k3)] += 1
            listed = [(e[1], e[2]) for e in entries]
            kind_ans = "copied" if ans in listed else ("history" if ans in history else "visual")
            C[("kind", kind_ans)] += 1

            # 1. validation: replica on recorded pixels
            px, py = c["px"]
            kind, pay = map_answer(px, py, tool, acts, dw, dh, *band0, snap)
            k = ban_key(st, kind, pay)
            banned = k is not None and strikes[k] >= DEAD_PAIR_STRIKES
            pred = verdict(kind, banned, ans)
            C[("v_result", rec, pred)] += 1
            if c["result"] == "matched" and kind == "matched" and not banned:
                C[("v_mcls", c.get("mcls") == pay.simple)] += 1
            if c is acting:
                a_str = dec.get("a", "")
                dm = RE_DEC.match(a_str)
                if c["result"] == "llm_tap":
                    tm = RE_TAP.search(a_str)
                    ok = (kind == "llm_tap" and not banned and tm is not None
                          and (int(tm.group(1)), int(tm.group(2))) == pay)
                    C[("v_exec", "llm_tap", ok)] += 1
                elif dm and dm.group(2) != "MODEL_BACK":
                    ex = parse_action_rest(dm.group(2), dm.group(3))
                    ok = (kind == "matched" and not banned and pay.type == ex.type
                          and pay.bounds == ex.bounds and pay.name == ex.name)
                    C[("v_exec", "matched", ok)] += 1
                else:
                    C[("v_exec", "back", kind == "back")] += 1

            # 3. non-copied answers: band effect only (pixel unchanged)
            if kind_ans != "copied" and tool in ("click", "long_click", "type_text"):
                grp = kind_ans
                if ans == (0, 0):
                    C[("q3_degenerate", grp, "S0:" + kind, "S3:" + map_answer(
                        px, py, tool, acts, dw, dh, *SCEN["S3"], snap)[0])] += 1
                band = ("bottom" if py > dh * 0.94 else "top" if py < dh * 0.05 else "none")
                if band != "none":
                    C[("q3_band", grp, band)] += 1
                    for sc in ("S2", "S3"):
                        k2, p2 = map_answer(px, py, tool, acts, dw, dh, *SCEN[sc], snap)
                        kb = ban_key(st, k2, p2)
                        dp = kb is not None and strikes[kb] >= DEAD_PAIR_STRIKES
                        C[("q3_band_to", grp, band, sc, k2, "dead_pair" if dp else "live")] += 1
                        if k2 == "matched" and sc == "S2":
                            W["q3_" + band][wid(p2)] += 1
                            S["q3_apks_" + band].add(apk)
                    if band == "top":
                        C[("q3_top_split", grp, "y<63" if py < STATUS_BAR_PX else "63<=y<89.7")] += 1

            # 2. copied answers: scenarios
            if kind_ans == "copied" and tool in ("click", "long_click", "type_text"):
                cand = [(e, a) for e, a in zip(entries, targets) if (e[1], e[2]) == ans]
                def fits(a):
                    if tool == "click":
                        return a.type == "MODEL_CLICK"
                    if tool == "long_click":
                        return a.type == "MODEL_LONG_CLICK"
                    return a.is_input
                pick = next((x for x in cand if fits(x[1])), cand[0])
                ia = pick[1]
                if len({x[1].bounds for x in cand}) > 1:
                    C[("copied", "ambiguous_entry")] += 1
                if ia.bounds is None:
                    C[("copied", "intended_without_node")] += 1
                    continue
                C[("copied", "n")] += 1
                same = [a for a in acts if a.target and a.valid and a.bounds == ia.bounds]
                if tool == "click":
                    compat = any(a.type == "MODEL_CLICK" for a in same)
                elif tool == "long_click":
                    compat = bool(same)
                else:
                    compat = ia.is_input and bool(same)
                C[("copied", "tool-compatible" if compat else "tool-incompatible")] += 1
                cx, cy = jdiv2(ia.bounds[0] + ia.bounds[2]), jdiv2(ia.bounds[1] + ia.bounds[3])
                nx, ny = to_normalized(cx, dw), to_normalized(cy, dh)
                fx, fy = normalize(nx, dw), normalize(ny, dh)
                vis = visible_centre(ia.bounds, dw, dh)
                C[("s4_entry", "inside" if vis == (cx, cy) else "offscreen" if vis is None else "partial")] += 1
                hit_by = {}
                for sc, (top, bottom) in SCEN.items():
                    if sc == "S0":
                        k2, p2 = kind, pay
                        ux, uy = px, py
                    elif sc == "S4" and vis is None:
                        k2, p2 = "nocoord", None
                        ux, uy = fx, fy
                    elif sc == "S4":
                        ux = normalize(to_normalized(vis[0], dw), dw)
                        uy = normalize(to_normalized(vis[1], dh), dh)
                        k2, p2 = map_answer(ux, uy, tool, acts, dw, dh, top, bottom, snap)
                    else:
                        ux, uy = fx, fy
                        k2, p2 = map_answer(fx, fy, tool, acts, dw, dh, top, bottom, snap)
                    kb = ban_key(st, k2, p2)
                    dp = kb is not None and strikes[kb] >= DEAD_PAIR_STRIKES
                    if k2 == "matched":
                        out = "intended" if p2.bounds == ia.bounds else "other"
                    else:
                        out = k2
                    hit_by[sc] = (out, dp)
                    if sc == "S3":
                        s3_px = (out, p2, ux, uy, top)
                    C[("cf", sc, wcls, out, "dead_pair" if dp else "live")] += 1
                    C[("cf", sc, "ALL", out, "dead_pair" if dp else "live")] += 1
                    if compat:
                        C[("cf", sc, "COMPAT", out, "dead_pair" if dp else "live")] += 1
                    if sc in ("S2", "S3", "S4") and out != "intended":
                        C[("q6", sc, residual_cause(out, p2, ia, acts, tool, uy, dh, top))] += 1
                    if sc == "S1" and out == "boundary":
                        W["q2_s1_boundary"][wid(ia)] += 1
                vcls = "inside" if vis == (cx, cy) else "offscreen" if vis is None else "partial"
                C[("s4_vis", vcls, "S3:" + hit_by["S3"][0], "S4:" + hit_by["S4"][0])] += 1
                C[("s8", hit_by["S0"][0], "dead_pair" if hit_by["S0"][1] else "live",
                   hit_by["S4"][0], "dead_pair" if hit_by["S4"][1] else "live")] += 1
                if vis is None:
                    # Section 7: Rect.intersect (AOSP) accepts a zero-width or zero-height rect lying
                    # inside the display, which visible_centre() above treats as empty.
                    b = ia.bounds
                    if b[0] < dw and 0 < b[2] and b[1] < dh and 0 < b[3]:
                        okind, side = "zero_area_inside", "inside"
                    else:
                        okind = "off_display"
                        side = "+".join(s for s, c in (("below", b[1] >= dh), ("above", b[3] <= 0),
                                                       ("right", b[0] >= dw), ("left", b[2] <= 0)) if c)
                    C[("s7_kind", okind)] += 1
                    C[("s7_side", okind, side, "S0:" + hit_by["S0"][0], "S3:" + hit_by["S3"][0])] += 1
                    o3, p3, x3, y3, t3 = s3_px
                    if o3 == "intended":
                        C[("s7_snap", okind, side, "pixel (%d,%d)" % (x3, y3))] += 1
                    else:
                        C[("s7_cause", okind, residual_cause(o3, p3, ia, acts, tool, y3, dh, t3))] += 1
                for sc in ("S1", "S2", "S3", "S4"):
                    C[("trans", sc, hit_by["S0"][0], hit_by[sc][0])] += 1
                    if compat:
                        C[("delta", sc, "COMPAT", "recovered" if hit_by[sc][0] == "intended" != hit_by["S0"][0]
                           else "lost" if hit_by["S0"][0] == "intended" != hit_by[sc][0] else "same")] += 1
                    a0 = hit_by["S0"][0] == "intended"
                    a1 = hit_by[sc][0] == "intended"
                    l0 = a0 and not hit_by["S0"][1]
                    l1 = a1 and not hit_by[sc][1]
                    C[("delta", sc, "ALL", "recovered" if a1 and not a0 else "lost" if a0 and not a1
                       else "same")] += 1
                    C[("delta", sc, wcls, "recovered" if a1 and not a0 else "lost" if a0 and not a1
                       else "same")] += 1
                    C[("delta_live", sc, "recovered" if l1 and not l0 else "lost" if l0 and not l1
                       else "same")] += 1

        # outcome of this step feeds the strike record (after the step's own calls)
        if dec.get("src") == "LLM":
            out = r.get("out")
            if isinstance(out, dict) and out.get("resolved", True) is not False \
                    and not out.get("new_state"):
                a_str = dec.get("a", "")
                dm = RE_DEC.match(a_str)
                if dm and dm.group(2) == "MODEL_LLM_TAP":
                    tm = RE_TAP.search(a_str)
                    if tm:
                        strikes[(st, "tap", int(tm.group(1)), int(tm.group(2)))] += 1
                elif dm and dm.group(2) in TARGET_TYPES:
                    ex = parse_action_rest(dm.group(2), dm.group(3))
                    if not ex.is_input:
                        strikes[(st, ex.name, ex.type)] += 1
    S["apks"].add(apk)
    if not any(r.get("llm") for r in records):
        S["files_without_llm_calls"].add(os.path.relpath(path, os.path.dirname(os.path.dirname(os.path.dirname(path)))))
    return C, {k: v for k, v in S.items()}, {k: v for k, v in W.items()}


def residual_cause(out, p2, ia, acts, tool, uy, dh, top):
    if out == "nocoord":
        return "intended node entirely off-screen (S4 lists no coordinate)"
    if out == "boundary":
        return "top band (y<%.1f)" % (dh * top)
    same = [a for a in acts if a.target and a.bounds == ia.bounds]
    if not ia.valid and not any(a.valid for a in same):
        return "intended action INVALID"
    if tool == "click" and not any(a.type == "MODEL_CLICK" and a.valid for a in same):
        if ia.type.startswith("MODEL_SCROLL"):
            return "click on SCROLL_* entry (no MODEL_CLICK on node)"
        if ia.type == "MODEL_LONG_CLICK":
            return "click on LONG_CLICK-only node"
        return "click, node has no valid MODEL_CLICK (other)"
    if tool == "type_text" and not ia.is_input:
        return "type_text on non-input entry"
    if out == "other":
        b, ib = p2.bounds, ia.bounds
        if b[0] >= ib[0] and b[1] >= ib[1] and b[2] <= ib[2] and b[3] <= ib[3]:
            return "containment picked smaller child"
        return "other overlapping/near widget"
    return "other: " + out


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
    if not files:
        files = sorted(glob.glob(os.path.join(root, pattern + ".ndjson.gz")))
    excluded = [f for f in files for frag, apk in EXCLUDE_APKS
                if frag in f and os.path.basename(os.path.dirname(f)) == apk]
    files = [f for f in files if f not in excluded]
    if excluded:
        print("excluded (overwritten in place, see EXCLUDE_APKS):",
              [os.path.relpath(f, root) for f in excluded])
    C = collections.Counter()
    S = collections.defaultdict(set)
    W = collections.defaultdict(collections.Counter)
    with multiprocessing.Pool(min(32, os.cpu_count() or 1)) as pool:
        for c, s, w in pool.imap(process, files, chunksize=1):
            C.update(c)
            for k, v in s.items():
                S[k] |= v
            for k, v in w.items():
                W[k].update(v)
    report(root, pattern, C, S, W)


def report(root, pattern, C, S, W):
    P = print
    P(f"# inputs: {root}/{pattern}")
    P(f"files: {C[('files',)]}; APKs: {len(S['apks'])}; LLM calls with an answer: {C[('calls',)]}")
    if S["files_without_llm_calls"]:
        P("files with no LLM call record:", sorted(S["files_without_llm_calls"]))
    P(f"aligned (prompt list 1:1 with listing): {C[('align', 'yes')]}; not aligned: {C[('align', 'no')]}")
    P("RUN_START (snap floor, bands):", {k[1:]: v for k, v in sorted(C.items(), key=str) if k[0] == "params"})
    P("list base class of aligned calls:", {k[1]: v for k, v in sorted(C.items()) if k[0] == "base"})
    P("answer kind (aligned):", {k[1]: v for k, v in sorted(C.items()) if k[0] == "kind"})

    P("\n## 1. Mapper replica validation (recorded pixel -> replica incl. replayed dead-pair ban)")
    vr = [(k[1], k[2], v) for k, v in C.items() if k[0] == "v_result"]
    tot = sum(v for _, _, v in vr)
    agree = sum(v for a, b, v in vr if a == b)
    P(f"result/reason agreement: {agree}/{tot} ({pct(agree, tot)})")
    for a, b, v in sorted(vr, key=lambda x: (-x[2], x[0], x[1])):
        P(f"   recorded {a:22} replica {b:22} {v:7}")
    for kind in ("matched", "llm_tap", "back"):
        ok, bad = C[("v_exec", kind, True)], C[("v_exec", kind, False)]
        if ok + bad:
            P(f"executed {kind:8} same action as dec.a: {ok}/{ok + bad} ({pct(ok, ok + bad)})")
    ok, bad = C[("v_mcls", True)], C[("v_mcls", False)]
    P(f"matched calls, replica class == recorded mcls: {ok}/{ok + bad} ({pct(ok, ok + bad)})")

    P("\n## 2. Copied answers: scenarios (SAME-ENTRY ASSUMPTION for S1-S4)")
    n = C[("copied", "n")]
    P(f"copied answers with a resolved intended node: {n}; intended entry without node: "
      f"{C[('copied', 'intended_without_node')]}; coordinate shared by entries of different bounds: "
      f"{C[('copied', 'ambiguous_entry')]}")
    outs = ("intended", "other", "llm_tap", "boundary", "null", "back")
    for wcls in ("ALL", "COMPAT", "full", "display", "narrow", "short", "tall", "landscape", "other"):
        rows = {k[1:]: v for k, v in C.items() if k[0] == "cf" and k[2] == wcls}
        total = sum(v for k, v in rows.items() if k[0] == "S0")
        if not total:
            continue
        P(f"\n  window class {wcls}: n={total}   (cells: live + dead_pair-refused)")
        P("   scen " + "".join(f"{o:>22}" for o in outs))
        for sc in SCEN:
            cells = []
            for o in outs:
                lv = rows.get((sc, wcls, o, "live"), 0)
                dp = rows.get((sc, wcls, o, "dead_pair"), 0)
                cells.append(f"{lv:>7}+{dp:<6}{pct(lv + dp, total):>8}")
            P(f"   {sc:4} " + "".join(f"{c:>22}" for c in cells))
        nc = rows.get(("S4", wcls, "nocoord", "live"), 0) + rows.get(("S4", wcls, "nocoord", "dead_pair"), 0)
        P(f"   S4   nocoord (intended node entirely off-screen, no coordinate listed): {nc} {pct(nc, total)}")
        for sc in ("S1", "S2", "S3", "S4"):
            d = {k[3]: v for k, v in C.items() if k[0] == "delta" and k[1] == sc and k[2] == wcls}
            P(f"   intended-widget hits vs S0, {sc}: recovered {d.get('recovered', 0)}, lost "
              f"{d.get('lost', 0)}, net {d.get('recovered', 0) - d.get('lost', 0):+d}")
    for sc in ("S1", "S2", "S3", "S4"):
        d = {k[2]: v for k, v in C.items() if k[0] == "delta_live" and k[1] == sc}
        P(f"   ALL, live intended hits (not dead_pair) vs S0, {sc}: recovered {d.get('recovered', 0)}, "
          f"lost {d.get('lost', 0)}, net {d.get('recovered', 0) - d.get('lost', 0):+d}")
    P(f"   tool-compatible copies (intended node offers a valid action of the tool's kind): "
      f"{C[('copied', 'tool-compatible')]}; incompatible: {C[('copied', 'tool-incompatible')]}")
    P("   S4 intended entries by visibility of the node:",
      {k[1]: v for k, v in sorted(C.items()) if k[0] == "s4_entry"})
    for vcls in ("offscreen", "partial"):
        P(f"   S4 {vcls} intended entries, outcome S3 -> S4 (changed only):",
          {k[2] + " -> " + k[3]: v for k, v in sorted(C.items())
           if k[0] == "s4_vis" and k[1] == vcls and k[2][3:] != k[3][3:]})
    for sc in ("S1", "S2", "S3", "S4"):
        rows = sorted(((k[2], k[3], v) for k, v in C.items() if k[0] == "trans" and k[1] == sc and k[2] != k[3]),
                      key=lambda x: (-x[2], x[0], x[1]))
        P(f"   transitions S0 -> {sc} (changed only): " + ", ".join(f"{a}->{b} {v}" for a, b, v in rows))
    P("   S1 boundary rejects of copied answers, intended widget (top 12):",
      W["q2_s1_boundary"].most_common(12))

    P("\n## 3. Non-copied answers (history copies, visual): band effect, pixel unchanged")
    for grp in ("history", "visual"):
        for band in ("bottom", "top"):
            nb = C[("q3_band", grp, band)]
            P(f"  {grp:7} rejected by {band} band (S0): {nb}")
            for sc in ("S2", "S3"):
                d = collections.Counter()
                for k, v in C.items():
                    if k[:4] == ("q3_band_to", grp, band, sc):
                        d[(k[4], k[5])] += v
                P(f"     under {sc}: " + ", ".join(f"{a}/{b}={v}" for (a, b), v in sorted(d.items())))
        P(f"  {grp:7} top-band rejects split:",
          {k[2]: v for k, v in sorted(C.items()) if k[0] == "q3_top_split" and k[1] == grp})
    for band in ("bottom", "top"):
        P(f"  widgets hit under S2 by formerly {band}-rejected non-copied answers "
          f"({len(S['q3_apks_' + band])} APKs; top 15):", W["q3_" + band].most_common(15))
    P("  degenerate (0,0) answers (group, S0 replica, S3 replica):",
      {k[1:]: v for k, v in sorted(C.items()) if k[0] == "q3_degenerate"})
    P("  answers with qwen x or y >= 1000 (axis, tool, S0 replica, S3 replica):",
      {k[1:]: v for k, v in sorted(C.items()) if k[0] == "q3_big"})
    P(f"  answers with px y >= display H: {C[('q3', 'px_y>=H')]} (normalize clamps to H-1)")

    P("\n## 4. Target-requiring actions centred in the bottom band / behind the nav bar (all listings)")
    ns, na = len(S["q4_states"]), len(S["q4_apks"])
    P(f"  steps with a listing: {C[('q4', 'steps')]}; states: {ns}; APKs: {na}")
    P(f"  centre y in (1686.36,1794): steps {C[('q4', 'steps_band')]} "
      f"({pct(C[('q4', 'steps_band')], C[('q4', 'steps')])}), states {len(S['q4_states_band'])} "
      f"({pct(len(S['q4_states_band']), ns)}; with a valid one {len(S['q4_states_band_valid'])}), "
      f"APKs {len(S['q4_apks_band'])} ({pct(len(S['q4_apks_band']), na)})")
    P(f"  centre y >= 1794: steps {C[('q4', 'steps_below')]}, states {len(S['q4_states_below'])}, "
      f"APKs {len(S['q4_apks_below'])}")
    P("  MODEL_CLICK widgets centred in the band (step-weighted, top 15):",
      W["q4_band_click"].most_common(15))

    P("\n## 5. Landscape")
    P(f"  calls whose qwen->px implies a landscape display: {C[('q5', 'display_landscape_calls')]}; "
      f"display unresolved: {C[('q5', 'display_unresolved')]}; listing steps with a node right > 1080: "
      f"{C[('q5', 'steps_listing_x>1080')]} ({len(S['q5_apks_wide'])} APKs)")
    P(f"  aligned calls whose list base has W > 1080 (root wider than portrait): {C[('q5', 'list_base_W>1080')]} "
      f"({len(S['q5_apks_listW'])} APKs); APKs with landscape-display calls: {len(S['q5_apks_landscape'])}")
    P(f"  base APKs: full {len(S['base_apk_full'])}, display {len(S['base_apk_display'])}")

    P("\n## 6. Residual misses of copied answers (not the intended widget), by cause")
    for sc in ("S2", "S3", "S4"):
        rows = sorted(((k[2], v) for k, v in C.items() if k[0] == "q6" and k[1] == sc),
                      key=lambda x: (-x[1], x[0]))
        P(f"  {sc}: total {sum(v for _, v in rows)}")
        for cause, v in rows:
            P(f"     {v:7}  {cause}")

    P("\n## 7. S4 off-screen intended entries split by Rect.intersect (added 2026-10-05, consistency verification)")
    P("  kind (off_display: no intersection with the display; zero_area_inside: zero-width or zero-height "
      "node inside it, which Rect.intersect accepts, so the change lists it, at the same point as S3):",
      {k[1]: v for k, v in sorted(C.items()) if k[0] == "s7_kind"})
    P("  (kind, side, S0 outcome, S3 outcome):",
      {k[1:]: v for k, v in sorted(C.items()) if k[0] == "s7_side"})
    P("  S3 snaps to the intended node (kind, side, S3 tap pixel):",
      {k[1:]: v for k, v in sorted(C.items()) if k[0] == "s7_snap"})
    P("  S3 residual cause of the others (kind, cause):",
      {k[1:]: v for k, v in sorted(C.items()) if k[0] == "s7_cause"})

    P("\n## 8. Copied answers, S0 -> S4 with the ban outcome (added 2026-10-05, consistency verification)")
    P("  (S0 outcome, S0 ban, S4 outcome, S4 ban): count, changed only:")
    for k, v in sorted(((k[1:], v) for k, v in C.items() if k[0] == "s8" and k[1:3] != k[3:5]),
                       key=lambda x: (-x[1], x[0])):
        P(f"     {v:7}  {k}")


if __name__ == "__main__":
    main()
