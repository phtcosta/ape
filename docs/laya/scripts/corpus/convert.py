"""Convert APE-format text traces (rv-android results) into per-step decision records.

Each SATA step in these traces prints the full candidate list of the current state (priority,
visited flag, class, resource-id, bounds, text), the selected action with its strategy, and on the
next step the edge Source -> Action -> Target. We emit one JSON line per step:

  {app, arm, trace, step, state, activity, candidates:[{aid,type,cls,rid,text,bounds,pri,unvisited,
   mop}], chosen, strategy, target, effect, act_change, new_state, activity_mop}

MOP flags come from the <apk>.json static analysis beside the trace (widget idName -> handler
methods -> reachesMop / directlyReachesMop). Only APE-format arms are parsed (aperv*, ape).
"""
import json
import os
import re
import sys
from collections import defaultdict

BEGIN = re.compile(r"SATA begin step \[(\d+)\]")
CAND = re.compile(r"^\[APE\]\s+(\d+) (g\d+a\d+)\[[^\]]*\]\[\d+\]@(MODEL_[A-Z_]+)(.*)$")
SELECT = re.compile(r"Select action (g\d+a\d+)\[.*? by strategy (\w+)")
NEWSTATE = re.compile(r"^\[APE\] New   state: (g\d+s\d+)\[[^\]]*\]\[\d+\](\S+?)@(-?\d+)@Naming\[(\d+)\]")
SRC = re.compile(r"^\[APE\]\s+Source: (g\d+s\d+)")
ACT = re.compile(r"^\[APE\]\s+Action: (g\d+a\d+)")
TGT = re.compile(r"^\[APE\]\s+Target: (g\d+s\d+)\[[^\]]*\]\[\d+\](\S+?)@(-?\d+)@Naming")
ATTR = re.compile(r"(class|resource-id)=([^;]*);")
TAIL = re.compile(r"\[P=(-?\d+)\]\[T=\d+\]\[([^\]]*)\].*?\[(\d+),(\d+),(\d+),(\d+)\]\[(.*)\]\s*$")
LLM_SEL = re.compile(r"LLM|llm")


def mop_index(path):
    try:
        d = json.load(open(path))
    except Exception:
        return None
    meth = {}
    act_mop = defaultdict(bool)
    for c in d.get("reachability", []):
        for m in c.get("methods", []):
            meth[m["signature"]] = (m.get("reachesMop", False), m.get("directlyReachesMop", False))
            if m.get("reachesMop") and c.get("isActivity"):
                act_mop[c["className"]] = True
    wid = defaultdict(lambda: [False, False])  # idName -> [transitive, direct]
    hints = {}

    def mark(name, handler):
        r, dr = meth.get(handler, (False, False))
        wid[name][0] |= r
        wid[name][1] |= dr

    for w in d.get("windows", []):
        for x in w.get("widgets", []):
            if x.get("idName"):
                if x.get("hint") or x.get("inputType"):
                    hints[x["idName"]] = (x.get("hint", ""), x.get("inputType", ""))
                for l in x.get("listeners", []):
                    mark(x["idName"], l.get("handler", ""))
    for t in d.get("transitions", []):
        for e in t.get("events", []):
            if e.get("widgetName"):
                mark(e["widgetName"], e.get("handler", ""))
    return {"wid": {k: v for k, v in wid.items() if v[0] or v[1]}, "act": dict(act_mop), "hints": hints}


def parse_cand(m):
    idx, aid, typ, rest = m.groups()
    c = {"aid": aid, "type": typ}
    attrs = dict(ATTR.findall(rest))
    c["cls"] = attrs.get("class", "").rsplit(".", 1)[-1]
    rid = attrs.get("resource-id", "")
    c["rid"] = rid.split("/", 1)[1] if "/" in rid else rid
    t = TAIL.search(rest)
    if t:
        c["pri"] = int(t.group(1))
        c["unvisited"] = "UNVISITED" in t.group(2)
        c["bounds"] = [int(t.group(i)) for i in range(3, 7)]
        c["text"] = t.group(7)[:60]
    else:  # BACK / MENU lines
        p = re.search(r"\[P=(-?\d+)\]", rest)
        c["pri"] = int(p.group(1)) if p else 0
        c["unvisited"] = "UNVISITED" in rest
        c["text"] = ""
    return c


def convert(trace, out):
    base = os.path.basename(trace)
    app, _, rest = base.partition("__")
    arm = rest.rsplit("__", 1)[-1].removesuffix(".trace")
    mi = mop_index(os.path.join(os.path.dirname(trace), app + ".json"))
    steps, cur = [], None
    edges = {}  # (src_state, action) -> target (state_key, activity)
    state_key = {}
    seen = set()
    pending_src = pending_act = None
    llm_prompts = 0
    with open(trace, errors="replace") as f:
        for line in f:
            if "APE-LLM-PROMPT] user_text" in line:
                llm_prompts += 1
            m = BEGIN.search(line)
            if m:
                cur = {"step": int(m.group(1)), "cands": [], "chosen": None, "strategy": None, "state": None}
                steps.append(cur)
                continue
            if cur is None:
                continue
            m = CAND.match(line)
            if m:
                cur["cands"].append(parse_cand(m))
                continue
            m = SELECT.search(line)
            if m:
                cur["chosen"], cur["strategy"] = m.group(1), m.group(2)
                continue
            m = NEWSTATE.match(line)
            if m:
                sid, act, h, nm = m.groups()
                cur["state"] = sid
                state_key[sid] = (f"{act}@{h}", act)
                continue
            m = SRC.match(line)
            if m:
                pending_src = m.group(1)
                continue
            m = ACT.match(line)
            if m:
                pending_act = m.group(1)
                continue
            m = TGT.match(line)
            if m and pending_src and pending_act:
                # the edge is logged at the start of step n+1 for the action chosen in step n
                prev = steps[-2] if len(steps) >= 2 else None
                if prev and prev["state"] == pending_src and prev["chosen"] == pending_act:
                    prev["edge"] = (f"{m.group(2)}@{m.group(3)}", m.group(2))
                pending_src = pending_act = None
    n = 0
    for s in steps:
        if not s["cands"] or not s["chosen"] or not s["state"] or s["state"] not in state_key:
            continue
        skey, activity = state_key[s["state"]]
        tgt = s.get("edge")
        rec = {"app": app, "arm": arm, "trace": base, "step": s["step"], "state": skey, "activity": activity,
               "chosen": s["chosen"], "strategy": s["strategy"], "candidates": s["cands"]}
        if mi:
            rec["activity_mop"] = mi["act"].get(activity, False)
            for c in s["cands"]:
                f2 = mi["wid"].get(c.get("rid", ""))
                c["mop"] = "direct" if f2 and f2[1] else ("trans" if f2 else "")
                h = mi["hints"].get(c.get("rid", ""))
                if h:
                    c["hint"], c["inputType"] = h
        if tgt:
            rec["target"], tact = tgt
            rec["effect"] = tgt[0] != skey
            rec["act_change"] = tact != activity
            rec["new_state"] = tgt[0] not in seen
        seen.add(skey)
        out.write(json.dumps(rec) + "\n")
        n += 1
    return n, llm_prompts


if __name__ == "__main__":
    lst, dst = sys.argv[1], sys.argv[2]
    tot = 0
    with open(dst, "w") as out:
        for i, tr in enumerate(open(lst).read().split()):
            if "__aperv" not in tr and not tr.endswith("__ape.trace"):
                continue
            try:
                n, _ = convert(tr, out)
                tot += n
            except Exception as e:
                print("ERR", tr, e, file=sys.stderr)
            if i % 500 == 0:
                print(i, tot, file=sys.stderr, flush=True)
    print("records", tot, file=sys.stderr)
