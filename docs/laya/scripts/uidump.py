"""Parse a uiautomator XML dump into a compact screen description + candidate actions."""
import re
import xml.etree.ElementTree as ET

INPUT_CLASSES = ("EditText", "AutoCompleteTextView", "SearchView")


def _short(cls):
    return cls.rsplit(".", 1)[-1]


def _rid(n):
    r = n.get("resource-id", "")
    return r.split("/", 1)[1] if "/" in r else r


def _desc_text(n):
    """First non-empty text/content-desc among descendants (anonymous clickables inherit it)."""
    for d in n.iter():
        t = (d.get("text") or d.get("content-desc") or "").strip()
        if t:
            return t
    return ""


def _label(n):
    t = (n.get("text") or "").strip() or (n.get("content-desc") or "").strip()
    if not t:
        t = _desc_text(n)
    return t[:40]


def _bounds(n):
    m = re.findall(r"\d+", n.get("bounds", "0,0,0,0"))
    return tuple(map(int, m)) if len(m) == 4 else (0, 0, 0, 0)


def parse(path, pkg=None):
    root = ET.parse(path).getroot()
    nodes = list(root.iter("node"))
    if pkg is None:
        pkgs = [n.get("package") for n in nodes if n.get("package")]
        pkg = max(set(pkgs), key=pkgs.count) if pkgs else ""
    actions, texts = [], []
    for n in nodes:
        if n.get("package") != pkg or n.get("enabled") == "false":
            continue
        cls = _short(n.get("class", ""))
        rid = _rid(n)
        lab = _label(n)
        is_input = any(c in cls for c in INPUT_CLASSES)
        clickable = n.get("clickable") == "true" or n.get("checkable") == "true"
        own = (n.get("text") or "").strip()
        if own and not clickable and not is_input:
            texts.append(own[:60])
        base = {"cls": cls, "rid": rid, "label": lab, "bounds": _bounds(n),
                "password": n.get("password") == "true", "checked": n.get("checked") == "true"}
        if is_input:
            actions.append({**base, "kind": "type"})
        elif clickable:
            actions.append({**base, "kind": "click"})
        if n.get("long-clickable") == "true":
            actions.append({**base, "kind": "long_click"})
        if n.get("scrollable") == "true":
            actions.append({**base, "kind": "scroll"})
    # top-to-bottom, left-to-right
    actions.sort(key=lambda a: (a["bounds"][1], a["bounds"][0]))
    return {"package": pkg, "actions": actions, "texts": texts}


def describe(a):
    who = f"{a['cls']} '{a['label']}'" if a["label"] else f"{a['cls']} id={a['rid'] or '?'}"
    if a["kind"] == "type":
        extra = " (password)" if a["password"] else ""
        return f"type text into {who}{extra}"
    if a["kind"] == "click" and a["cls"] in ("CheckBox", "Switch", "ToggleButton", "RadioButton"):
        return f"toggle {who} ({'on' if a['checked'] else 'off'})"
    return f"{a['kind'].replace('_', ' ')} {who}"


def key_for(a, used):
    base = re.sub(r"[^a-z0-9]+", "_", (a["label"] or a["rid"] or a["cls"]).lower()).strip("_")[:20] or "w"
    k = f"{a['kind']}_{base}"
    i, kk = 2, k
    while kk in used:
        kk, i = f"{k}_{i}", i + 1
    used.add(kk)
    return kk
