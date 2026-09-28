"""Probe 1: one screen, all checkpoints, a few instruction/state variants, order permutations."""
import json
import sys
import time

from laya import Router

import uidump

XML = sys.argv[1]
scr = uidump.parse(XML)
used = set()
criteria = {}
for a in scr["actions"]:
    criteria[uidump.key_for(a, used)] = uidump.describe(a)
criteria["back"] = "press the Android back button"

print("TEXTS:", scr["texts"])
print("OPTIONS:", json.dumps(criteria, indent=1))

INSTR = {
    "explore": "You are testing an Android app. Which action should be performed next to make progress and reach new screens?",
    "security": "You are testing an Android app to trigger security-sensitive operations (cryptography, storage, network). Which action next?",
}
STATES = {
    "text_only": " | ".join(scr["texts"]),
    "dict": {"app": scr["package"], "screen_text": scr["texts"], "history": []},
    "after_type": {"app": scr["package"], "screen_text": scr["texts"],
                   "history": ["typed '1111' into the EditText -> same screen"]},
}

router = Router(device="cuda")
for model in ("english", "typed-decisions", "multilingual"):
    for iname, instr in INSTR.items():
        for sname, state in STATES.items():
            for order in ("fwd", "rev"):
                items = list(criteria.items())
                if order == "rev":
                    items.reverse()
                q = {"next": {"type": "choice", "instructions": instr, "criteria": dict(items)}}
                t = time.perf_counter()
                r = router.predict(state, q, model=model)
                ms = (time.perf_counter() - t) * 1000
                a = r["answers"]["next"]
                probs = sorted(a["probabilities"].items(), key=lambda kv: -kv[1])
                top = ", ".join(f"{k}={p:.2f}" for k, p in probs[:4])
                print(f"{model:16s} {iname:8s} {sname:10s} {order}  {ms:6.0f}ms  pick={a['choice']:<22s} conf={a.get('answer_confidence', 0):.2f} | {top}")
