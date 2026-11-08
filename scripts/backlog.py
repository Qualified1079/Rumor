#!/usr/bin/env python3
"""Backlog bookkeeping — the three files (CLAUDE.md index, docs/OPEN_BACKLOG.md
bodies, docs/COMPLETED_GAPS.md archive) changed together, by one tool.

  scripts/backlog.py next-id                 next free O / G (fetches origin/main first)
  scripts/backlog.py file --tier 4 --title T --body B   file a new O row (index + body)
  scripts/backlog.py close O123 --title T --notes N      close a row as the next G
  scripts/backlog.py close O123 --title T --notes N --remaining-title T2 --remaining-body B2
                                             close half a row; O123 stays open, retitled
  scripts/backlog.py counts                  regenerate the counts block in CLAUDE.md
  scripts/backlog.py check                   consistency check (also run by BacklogConsistencyTest)

Row numbers are never reused. `next-id` scans origin/main as well as the working
tree, so two instances that each fetch before filing cannot pick the same number
unless they file within the same push window — the push of the second then
conflicts on the same lines, which is the signal to re-run next-id.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INDEX = ROOT / "CLAUDE.md"
BODIES = ROOT / "docs/OPEN_BACKLOG.md"
GAPS = ROOT / "docs/COMPLETED_GAPS.md"
SCANNED = ["CLAUDE.md", "docs/OPEN_BACKLOG.md", "docs/COMPLETED_GAPS.md", "docs/DECISIONS.md", "docs/UI_BACKLOG.md"]
TIER_HEADER = re.compile(r"^#### Tier (\d)\b")
ROW = re.compile(r"^\| (O\d+)\b[^|]*\|")
GROW = re.compile(r"^\| G(\d+) \|")
COUNTS_BEGIN, COUNTS_END = "<!-- counts:begin -->", "<!-- counts:end -->"


def read(p): return p.read_text(encoding="utf-8")
def write(p, s): p.write_text(s, encoding="utf-8", newline="\n")


def tier_rows(text):
    """{O-number: (tier, line)} for rows inside the '#### Tier N' tables."""
    rows, tier = {}, None
    for line in text.split("\n"):
        h = TIER_HEADER.match(line)
        if h: tier = int(h.group(1)); continue
        if line.startswith("#### ") or line.startswith("### "): tier = None; continue
        m = ROW.match(line)
        if tier and m: rows[m.group(1)] = (tier, line)
    return rows


def all_numbers(text):
    os_ = {int(n) for n in re.findall(r"^\| O(\d+)\b", text, re.M)}
    gs = {int(n) for n in re.findall(r"^\| G(\d+) \|", text, re.M)}
    return os_, gs


def next_ids(fetch=True):
    if fetch:
        subprocess.run(["git", "fetch", "-q", "origin", "main"], cwd=ROOT, check=False)
    os_, gs = set(), set()
    for rel in SCANNED:
        texts = []
        p = ROOT / rel
        if p.exists(): texts.append(read(p))
        r = subprocess.run(["git", "show", f"origin/main:{rel}"], cwd=ROOT, capture_output=True, text=True)
        if r.returncode == 0: texts.append(r.stdout)
        for t in texts:
            o, g = all_numbers(t); os_ |= o; gs |= g
    # Numbers named anywhere in prose count as taken too (reservations, cross-refs).
    for t in (read(ROOT / rel) for rel in SCANNED if (ROOT / rel).exists()):
        os_ |= {int(n) for n in re.findall(r"\bO(\d{2,4})\b", t)}
        gs |= {int(n) for n in re.findall(r"\bG(\d{2,4})\b", t)}
    return max(os_) + 1, max(gs) + 1


def insert_index_row(text, tier, row):
    lines = text.split("\n"); cur = None; last = None
    for i, line in enumerate(lines):
        h = TIER_HEADER.match(line)
        if h: cur = int(h.group(1)); continue
        if line.startswith("####") or line.startswith("### "):
            if cur == tier and last is not None: break
            cur = None; continue
        if cur == tier and line.startswith("| "): last = i
    if last is None: sys.exit(f"no Tier {tier} table in CLAUDE.md")
    lines.insert(last + 1, row)
    return "\n".join(lines)


def replace_row(text, o, new_line):
    pat = re.compile(r"^\| %s\b[^|]*\|.*$" % o, re.M)
    if not pat.search(text): sys.exit(f"{o} not found")
    return pat.sub(lambda _: new_line if new_line is not None else "\0", text, count=1).replace("\0\n", "")


def add_gap(text, g, title, notes):
    lines = text.split("\n")
    last = max(i for i, l in enumerate(lines) if GROW.match(l))
    lines.insert(last + 1, f"| G{g} | **{title}** | {notes} |")
    text = "\n".join(lines)
    return re.sub(r"G1–G\d+, P2", f"G1–G{g}, P2", text, count=1)


def counts_block(index_text, gaps_text):
    rows = tier_rows(index_text)
    tags = [re.search(r"\*\*`\[([^]]*)\]`", line) for _, line in rows.values()]
    tags = [t.group(1) if t else "" for t in tags]
    decision = sum(1 for t in tags if "DECISION" in t or "DECIDED" in t)
    part = sum(1 for _, line in rows.values() if "`[PART]`" in line)
    struct = sum(1 for _, line in rows.values() if "`[STRUCT]`" in line)
    conventions = len(re.findall(r"^\| O\d+ \| \*\*`\[CONVENTION\]`", index_text, re.M))
    tomb = len(re.findall(r"^\| O[^|]*\| \*\*`\[(CLOSED|SUBSUMED|DROPPED|SUPERSEDED)", index_text, re.M))
    gcount = len(set(re.findall(r"^\| G(\d+) \|", gaps_text, re.M)))
    return (f"{COUNTS_BEGIN}\nGenerated by `scripts/backlog.py counts` — do not hand-edit. "
            f"{len(rows)} indexed rows ({part} `[PART]`, {struct} `[STRUCT]`, {decision} decision-tagged) · "
            f"{conventions} conventions · {tomb} tombstones · {gcount} completed G-rows.\n{COUNTS_END}")


def regen_counts():
    idx = read(INDEX)
    if COUNTS_BEGIN not in idx: sys.exit("CLAUDE.md has no counts block")
    block = counts_block(idx, read(GAPS))
    idx = re.sub(re.escape(COUNTS_BEGIN) + r".*?" + re.escape(COUNTS_END), lambda _: block, idx, flags=re.S)
    write(INDEX, idx)


def check():
    problems = []
    idx, bodies, gaps = read(INDEX), read(BODIES), read(GAPS)
    irows, brows = tier_rows(idx), tier_rows(bodies)
    for o in sorted(set(irows) - set(brows)): problems.append(f"{o} is indexed in CLAUDE.md but has no body in OPEN_BACKLOG.md")
    for o in sorted(set(brows) - set(irows)): problems.append(f"{o} has a body in OPEN_BACKLOG.md but no CLAUDE.md index row")
    for o, (_, line) in irows.items():
        if re.search(r"\*\*`\[DONE", line): problems.append(f"{o} is [DONE] but still indexed as open — close it")
        if len(line) > 320: problems.append(f"{o} index row is {len(line)} chars — the index is one line per row; the body belongs in OPEN_BACKLOG.md")
    gnums = re.findall(r"^\| G(\d+) \|", gaps, re.M)
    dup = sorted({g for g in gnums if gnums.count(g) > 1}, key=int)
    if dup: problems.append("duplicate G numbers: " + ", ".join("G" + g for g in dup))
    if COUNTS_BEGIN in idx and counts_block(idx, gaps) not in idx:
        problems.append("CLAUDE.md counts block is stale — run scripts/backlog.py counts")
    for p in problems: print(p)
    return 1 if problems else 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("next-id").add_argument("--no-fetch", action="store_true")
    f = sub.add_parser("file")
    f.add_argument("--tier", type=int, required=True); f.add_argument("--title", required=True); f.add_argument("--body", required=True)
    f.add_argument("--number", help="use this O number (default: next-id)")
    c = sub.add_parser("close")
    c.add_argument("o"); c.add_argument("--title", required=True); c.add_argument("--notes", required=True)
    c.add_argument("--remaining-title"); c.add_argument("--remaining-body")
    sub.add_parser("counts"); sub.add_parser("check")
    a = ap.parse_args()

    if a.cmd == "next-id":
        o, g = next_ids(fetch=not a.no_fetch); print(f"O{o} G{g}"); return 0
    if a.cmd == "check":
        return check()
    if a.cmd == "counts":
        regen_counts(); return 0
    if a.cmd == "file":
        o = a.number or f"O{next_ids()[0]}"
        write(INDEX, insert_index_row(read(INDEX), a.tier, f"| {o} | **{a.title}** |"))
        write(BODIES, insert_index_row(read(BODIES), a.tier, f"| {o} | **{a.title}** | {a.body} |"))
        regen_counts(); print(o); return 0
    if a.cmd == "close":
        g = next_ids()[1]
        if a.remaining_title:
            write(INDEX, replace_row(read(INDEX), a.o, f"| {a.o} | **{a.remaining_title}** |"))
            write(BODIES, replace_row(read(BODIES), a.o, f"| {a.o} | **{a.remaining_title}** | {a.remaining_body} |"))
        else:
            write(INDEX, replace_row(read(INDEX), a.o, None))
            write(BODIES, replace_row(read(BODIES), a.o, None))
        write(GAPS, add_gap(read(GAPS), g, a.title, a.notes))
        regen_counts(); print(f"closed {a.o} as G{g}"); return 0


if __name__ == "__main__":
    sys.exit(main())
