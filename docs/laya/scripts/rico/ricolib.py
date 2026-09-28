"""Rico filtered_traces parsing: traces, gestures, APE-like candidates, screen signatures.

Layout (verified on the archive): filtered_traces/<pkg>/trace_<n>/{gestures.json, view_hierarchies/<ui>.json,
screenshots/<ui>.jpg}. gestures.json maps a UI number (string) to a list of [x, y] points normalized to
[0, 1]; the gesture recorded under UI i is the one performed ON UI i, leading to the next UI (next number
in sorted order). macOS '._*.json' AppleDouble files are junk and skipped.
"""
import json
import os
import re
from pathlib import Path

ROOT = Path("/home/pedro/tmp/laya/data/rico/filtered_traces")
SCREEN_W, SCREEN_H = 1440, 2560  # Rico device: Nexus 6P class (1440x2560); VH bounds are in these pixels
SWIPE_THRESH = 0.03  # normalized displacement first->last point above which a gesture is a swipe

EDIT = "android.widget.EditText"
CHECK = ("android.widget.CompoundButton",)  # CheckBox, Switch, RadioButton, ToggleButton
SCROLLERS = ("android.widget.AbsListView", "android.widget.ScrollView", "android.widget.HorizontalScrollView",
             "android.support.v7.widget.RecyclerView", "android.support.v4.view.ViewPager",
             "android.webkit.WebView")


def list_traces(root=ROOT):
    for pkg in sorted(os.listdir(root)):
        pd = root / pkg
        if not pd.is_dir():
            continue
        for t in sorted(os.listdir(pd)):
            if t.startswith("trace_"):
                yield pkg, t, pd / t


def load_trace(tdir):
    """Return (sorted ui ids with VH, gestures dict id->points)."""
    try:
        g = json.load(open(tdir / "gestures.json"))
    except Exception:
        g = {}
    vdir = tdir / "view_hierarchies"
    ids = []
    if vdir.is_dir():
        for f in os.listdir(vdir):
            if f.endswith(".json") and not f.startswith("._"):
                try:
                    ids.append(int(f[:-5]))
                except ValueError:
                    pass
    ids.sort()
    return ids, {int(k): v for k, v in g.items()}


def load_vh(tdir, ui):
    try:
        return json.load(open(tdir / "view_hierarchies" / f"{ui}.json"))
    except Exception:
        return None


def ancestors(n):
    return [n.get("class") or ""] + list(n.get("ancestors") or [])


def is_a(n, names):
    a = ancestors(n)
    return any(x in a for x in names) if isinstance(names, tuple) else names in a


def label_of(n):
    """(label, source) with source in text/desc/id/none."""
    t = n.get("text")
    if isinstance(t, str) and t.strip():
        return t.strip(), "text"
    cd = n.get("content-desc")
    if isinstance(cd, list):
        cd = next((c for c in cd if isinstance(c, str) and c.strip()), None)
    if isinstance(cd, str) and cd.strip():
        return cd.strip(), "desc"
    rid = n.get("resource-id")
    if isinstance(rid, str) and rid:
        return rid.split("/")[-1], "id"
    return "", "none"


def subtree_text(n, limit=3):
    out = []

    def w(x):
        if len(out) >= limit or not isinstance(x, dict):
            return
        lab, src = label_of(x)
        if src in ("text", "desc"):
            out.append(lab)
        for c in x.get("children") or []:
            w(c)
    w(n)
    return out


def visible_nodes(vh):
    """DFS over visible, on-screen nodes; yields (node, depth, order)."""
    root = (vh or {}).get("activity", {}).get("root")
    out = []

    def w(n, d):
        if not isinstance(n, dict):
            return
        if n.get("visibility", "visible") != "visible" or n.get("visible-to-user") is False:
            return
        b = n.get("bounds") or [0, 0, 0, 0]
        if b[2] <= b[0] or b[3] <= b[1] or b[2] <= 0 or b[3] <= 0 or b[0] >= SCREEN_W or b[1] >= SCREEN_H:
            # zero-area containers may still hold visible children (rare); keep walking
            for c in n.get("children") or []:
                w(c, d + 1)
            return
        out.append((n, d, len(out)))
        for c in n.get("children") or []:
            w(c, d + 1)
    if root:
        w(root, 0)
    return out


def kind_of(n):
    if is_a(n, EDIT):
        return "type"
    if is_a(n, CHECK) or n.get("checkable"):
        return "check"
    if n.get("clickable"):
        return "tap"
    if n.get("long-clickable"):
        return "longtap"
    if n.get("scrollable-vertical") or n.get("scrollable-horizontal"):
        return "scroll"
    return None


def candidates(vh):
    """APE-like actionable nodes: visible, enabled, clickable/long-clickable/checkable/editable/scrollable."""
    cands = []
    for n, d, o in visible_nodes(vh):
        if n.get("enabled") is False:
            continue
        k = kind_of(n)
        if is_a(n, "android.widget.AdapterView"):
            # a clickable ListView/GridView dispatches to its rows: expose each visible row (APE-like)
            if n.get("clickable") or n.get("long-clickable"):
                for ch in n.get("children") or []:
                    if isinstance(ch, dict) and ch.get("visibility", "visible") == "visible" \
                            and ch.get("visible-to-user") is not False and not ch.get("clickable"):
                        cb = ch.get("bounds") or [0, 0, 0, 0]
                        if cb[2] > cb[0] and cb[3] > cb[1]:
                            st = subtree_text(ch, 2)
                            cands.append({"node": ch, "kind": "tap", "bounds": cb,
                                          "area": (cb[2] - cb[0]) * (cb[3] - cb[1]),
                                          "label": " / ".join(st), "src": "child" if st else "none",
                                          "cls": "ListItem", "rid": (ch.get("resource-id") or "").split("/")[-1],
                                          "order": o, "depth": d + 1})
            k = "scroll" if (n.get("scrollable-vertical") or n.get("scrollable-horizontal")) else None
        if k is None:
            continue
        b = n["bounds"]
        lab, src = label_of(n)
        if src in ("none", "id") and k in ("tap", "longtap", "check"):
            st = subtree_text(n, 2)  # APE/uiautomator-style: a clickable container borrows child text
            if st:
                lab, src = " / ".join(st), "child"
        cands.append({"node": n, "kind": k, "bounds": b, "area": (b[2] - b[0]) * (b[3] - b[1]),
                      "label": lab, "src": src, "cls": (n.get("class") or "").rsplit(".", 1)[-1],
                      "rid": (n.get("resource-id") or "").split("/")[-1], "order": o, "depth": d})
    return cands


def gesture_type(pts):
    if not pts:
        return "none"
    if len(pts) == 1:
        return "tap"
    dx, dy = pts[-1][0] - pts[0][0], pts[-1][1] - pts[0][1]
    if (dx * dx + dy * dy) ** .5 < SWIPE_THRESH:
        return "tap"  # multi-point jitter (press held while mouse moved a few pixels)
    if abs(dx) > abs(dy):
        return "swipe_right" if dx > 0 else "swipe_left"
    return "swipe_down" if dy > 0 else "swipe_up"


def to_px(pt):
    return pt[0] * SCREEN_W, pt[1] * SCREEN_H


def contains(b, x, y):
    return b[0] <= x <= b[2] and b[1] <= y <= b[3]


def hit(cands, x, y, kinds=None):
    """Smallest-area candidate containing (x, y) (the innermost actionable widget)."""
    best = None
    for c in cands:
        if kinds and c["kind"] not in kinds:
            continue
        if contains(c["bounds"], x, y) and (best is None or c["area"] <= best["area"]):
            best = c
    return best


def signature(vh, level="struct"):
    """Screen abstraction. 'act' = activity only; 'struct' = activity + sorted multiset of (class,
    resource-id) of visible nodes (APE's text-free TypeNamer-like level); 'text' adds visible texts."""
    act = (vh or {}).get("activity_name") or "?"
    if level == "act":
        return act
    parts = []
    for n, d, o in visible_nodes(vh):
        p = (n.get("class") or "") + "#" + (n.get("resource-id") or "")
        if level == "text":
            lab, src = label_of(n)
            if src == "text":
                p += "=" + lab[:30]
        parts.append(p)
    # multiset -> set: list items that repeat (N rows) should not make N distinct screens
    return act + "|" + "|".join(sorted(set(parts)))


def action_key(c):
    return f"{c['kind']}:{c['cls']}:{c['rid']}:{c['label'][:30]}"


def screen_text(vh, limit=40):
    out = []
    for n, d, o in visible_nodes(vh):
        lab, src = label_of(n)
        if src in ("text", "desc") and lab not in out:
            out.append(lab[:60])
        if len(out) >= limit:
            break
    return out


VERB = {"tap": "tap", "longtap": "long-press", "check": "toggle", "type": "type into", "scroll": "scroll"}


def render(c):
    """APE-like option text: tap Button 'OK' (id=ok_btn)."""
    lab = re.sub(r"\s+", " ", c["label"])[:50]
    s = f"{VERB[c['kind']]} {c['cls'] or 'View'}"
    if lab and c["src"] != "id":
        s += f" '{lab}'"
    if c["rid"]:
        s += f" (id={c['rid']})"
    return s


def h(s):
    import hashlib
    return hashlib.blake2b(s.encode(), digest_size=6).hexdigest()
