"""Derive the format-2 MOP test fixtures with rv-android's generator (task 10.1).

A `.mop.json` is never edited by hand: its `source.digest` names the analyser
document it was derived from. This script derives each fixture from the
`.apk.json` kept beside it, through the gh122 library calls `tool.py` uses
(`digest_of_file`, `derive`, `serialize_canonical`), and writes the canonical bytes.

Usage (run from the ape repository root, with -I -B so nothing is cached here):

    python3 -I -B openspec/changes/llm-coordinate-single-base/evidence/derive_fixtures.py \
        <rv-android root>

Pairs (source -> artifact):
    test-apks/cryptoapp.apk.json -> test-apks/cryptoapp.apk.mop.json
                                 -> src/test/resources/cryptoapp.apk.mop.json
    src/test/resources/synthetic-activity-selection.apk.json
                                 -> src/test/resources/synthetic-activity-selection.mop.json
"""

import os
import sys

PAIRS = (
    ("test-apks/cryptoapp.apk.json",
     ("test-apks/cryptoapp.apk.mop.json", "src/test/resources/cryptoapp.apk.mop.json")),
    ("src/test/resources/synthetic-activity-selection.apk.json",
     ("src/test/resources/synthetic-activity-selection.mop.json",)),
)


def main(rv_android_root):
    sys.path.insert(0, os.path.join(rv_android_root, "modules", "aperv-tool", "src"))
    from aperv_tool.tools.aperv.derive_mop_artifact import (  # noqa: E402
        GENERATOR_ID, derive, digest_of_file, serialize_canonical)
    import json

    for source, artifacts in PAIRS:
        with open(source, "rb") as handle:
            raw = handle.read()
        digest = digest_of_file(source)
        payload = serialize_canonical(
            derive(json.loads(raw), source_file=os.path.basename(source), source_digest=digest))
        for artifact in artifacts:
            with open(artifact, "wb") as handle:
                handle.write(payload)
            print(f"{artifact} <- {source} {digest} {GENERATOR_ID} {len(payload)} bytes")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
