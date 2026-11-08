#!/usr/bin/env python3
"""Per-module JUnit totals from build/test-results, flagging what makes a green run
vacuous: skipped tests and classes that ran zero tests.

A zero-test class with a 1970 timestamp is Gradle's placeholder for a class that a
`--tests` filter excluded — reported separately, it is not a problem.

  scripts/junit-summary.py core node simulator
"""
import glob
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ATTRS = re.compile(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)" timestamp="([^"]*)"')

bad = False
for module in sys.argv[1:] or ["core", "node", "simulator"]:
    files = glob.glob(str(ROOT / module / "build/test-results/test/*.xml"))
    if not files:
        continue
    t = sk = f = e = 0
    empty, filtered, failed = [], 0, []
    for x in files:
        m = ATTRS.search(open(x, encoding="utf-8").read(4000))
        if not m:
            continue
        a, b, c, d = map(int, m.groups()[:4])
        t += a; sk += b; f += c; e += d
        name = Path(x).stem.removeprefix("TEST-")
        if a == 0:
            if m.group(5).startswith("1970"):
                filtered += 1
            else:
                empty.append(name)
        if c or d:
            failed.append(name)
    line = f"{module}: tests={t} skipped={sk} failures={f} errors={e}"
    if filtered:
        line += f" (filtered-out placeholders={filtered})"
    print(line)
    for n in failed:
        print(f"  FAILED  {n}")
    for n in empty:
        print(f"  ZERO-TEST CLASS  {n} — ran nothing; a green suite here proves nothing")
    if sk or empty or f or e:
        bad = True
sys.exit(1 if bad else 0)
