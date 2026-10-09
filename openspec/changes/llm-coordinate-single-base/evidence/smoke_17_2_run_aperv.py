#!/usr/bin/env python3
"""Run rv-experiment with aperv's jar pointed at a local APE-RV build (no write into rv-android).

usage (from a directory outside rv-android, with rv-android's venv):
  PYTHONDONTWRITEBYTECODE=1 <rv-android>/.venv/bin/python smoke_17_2_run_aperv.py run \
      --tools aperv:mopd_on_llm_off:mop_on_llm_off --apks-dir <dir with foo.apk + foo.apk.json> \
      --output-dir <fresh dir> --skip-monitors --skip-instrument --skip-static \
      --timeouts 300 --repetitions 1 --no-window --logcat-diagnostics
The aperv tool resolves ape-rv.jar beside tool.py first, and that copy predates format 2 and
ape.mopScoring; there is no override, so the resolver is replaced in-process before main().
"""
import os
import sys

JAR = os.environ.get(
    "APERV_LOCAL_JAR",
    "/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/target/ape-rv.jar")
if not os.path.isfile(JAR):
    sys.exit("jar not found: " + JAR)

from aperv_tool.tools.aperv import tool as aperv_tool_module  # noqa: E402

aperv_tool_module.ApeRVTool._resolve_jar_path = lambda self: JAR
print("[run_aperv] ape-rv.jar ->", JAR, flush=True)

from rv_experiment.__main__ import main  # noqa: E402

sys.argv = ["rv-experiment", *sys.argv[1:]]
sys.exit(main())
