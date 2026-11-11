#!/usr/bin/env python3
"""Backlog bookkeeping. One row = one file, so a lookup reads one row and two
instances never conflict on a shared table.

  docs/backlog/INDEX.md        one line per open row, in tiers (the only table)
  docs/backlog/open/O123.md    full body of an open row
  docs/backlog/done/G45.md     a completed gap
  docs/backlog/archive/        tombstone and shipped-substrate records

  scripts/backlog.py show O123 [G45 ...]     print a row's full text, wherever it lives
  scripts/backlog.py next-id                 next free O / G (fetches origin/main first)
  scripts/backlog.py file --tier 4 --tag TODO/CODE --title T --body B   file a new O row
  scripts/backlog.py close O123 --title T --notes N      close a row as the next G
  scripts/backlog.py close O123 --title T --notes N --remaining-title T2 --remaining-body B2
                                             close half a row; O123 stays open, retitled
  scripts/backlog.py sweep [--dry-run] [--resolution R]
                                             close every index row ticked `| O123 [x] |`
  scripts/backlog.py boxes                   add `[ ]` to any open non-decision index row lacking one
  scripts/backlog.py counts                  regenerate the counts block in INDEX.md
  scripts/backlog.py check                   consistency check (BacklogConsistencyTest pins the core of it)

Row numbers are never reused. `next-id` scans origin/main as well as the working
tree, so two instances that each fetch before filing cannot pick the same number
unless they file within the same push window — the second push then collides on
the same file name, which is the signal to re-run next-id.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLAUDE = ROOT / "CLAUDE.md"
BACKLOG = ROOT / "docs/backlog"
INDEX, OPEN, DONE, ARCHIVE = BACKLOG / "INDEX.md", BACKLOG / "open", BACKLOG / "done", BACKLOG / "archive"
TABLES = [CLAUDE, ROOT / "docs/DECISIONS.md", ROOT / "docs/UI_BACKLOG.md"]
# The last two are the pre-split tables; another instance's origin/main may still have them.
ORIGIN_PATHS = ["CLAUDE.md", "docs/backlog", "docs/DECISIONS.md", "docs/UI_BACKLOG.md",
                "docs/OPEN_BACKLOG.md", "docs/COMPLETED_GAPS.md"]
# CLAUDE.md is loaded into every session; the index was moved out of it to get under this.
CLAUDE_BUDGET = 24_000
# The Read tool truncates long lines, which is how 11 KB table rows became unreadable.
MAX_LINE = 1500
TIER_HEADER = re.compile(r"^#### Tier (\d)\b")
ROW = re.compile(r"^\| (O\d+)\b[^|]*\|")
BOX = re.compile(r"^\| (O\d+) \[([ xX])\] \|")
BLOCK_START = re.compile(r"^(\d+[.)] |[-+*] |#|>|\||```|=+$|-+$)")
COUNTS_BEGIN, COUNTS_END = "<!-- counts:begin -->", "<!-- counts:end -->"
EOL = {}


def read(p):
    raw = p.read_bytes().decode("utf-8")
    EOL[p] = "\r\n" if "\r\n" in raw else "\n"
    return raw.replace("\r\n", "\n")


def write(p, s):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(s.replace("\n", EOL.get(p, "\n")).encode("utf-8"))


def tags(t): return " ".join(f"`[{x.strip()}]`" for x in t.split(","))


def wrap(text, width=160):
    """Break a one-line table cell into short lines at sentence ends. Whitespace-only
    change: Markdown joins the lines back into the same paragraph."""
    paras = []
    for para in re.split(r"(?:\s*<br\s*/?>\s*)+", text.strip()):
        lines, cur = [], ""
        for piece in re.split(r"(?<=[.;:?!]) +", " ".join(para.split())):
            while len(piece) > MAX_LINE:
                cut = piece.rfind(" ", 0, MAX_LINE)
                if cut <= 0: break
                lines.append((cur + " " + piece[:cut]).strip()); cur, piece = "", piece[cut + 1:]
            if cur and len(cur) + 1 + len(piece) > width:
                lines.append(cur); cur = piece
            else:
                cur = (cur + " " + piece).strip()
        if cur: lines.append(cur)
        merged = []
        for line in lines:
            # A wrapped line must not start like a list item, heading or quote.
            if merged and BLOCK_START.match(line): merged[-1] += " " + line
            else: merged.append(line)
        if merged: paras.append("\n".join(merged))
    return "\n\n".join(paras)


def row_file(rid, head, body, note=None):
    title = f"# {rid}" + (f" ({note})" if note else "")
    return f"{title}\n\n{head.strip()}\n\n{wrap(body)}\n"


def row_parts(p):
    """(head, body) of a row file."""
    chunks = read(p).split("\n\n", 2)
    return (chunks[1].strip() if len(chunks) > 1 else ""), (chunks[2].strip() if len(chunks) > 2 else "")


def tier_rows(text):
    """{O-number: (tier, line)} for rows inside the '#### Tier N' tables."""
    rows, tier = {}, None
    for line in text.split("\n"):
        h = TIER_HEADER.match(line)
        if h: tier = int(h.group(1)); continue
        if line.startswith("#### ") or line.startswith("### ") or line.startswith("## "): tier = None; continue
        m = ROW.match(line)
        if tier and m: rows[m.group(1)] = (tier, line)
    return rows


def file_ids(d, prefix):
    return {p.stem for p in d.glob(f"{prefix}*.md")} if d.exists() else set()


def next_ids(fetch=True):
    if fetch:
        subprocess.run(["git", "fetch", "-q", "origin", "main"], cwd=ROOT, check=False)
    texts, names = [], []
    for p in [INDEX, *TABLES, *BACKLOG.rglob("*.md")]:
        if p.exists(): texts.append(read(p)); names.append(p.stem)
    r = subprocess.run(["git", "grep", "-hE", "[OG][0-9]{1,4}", "origin/main", "--", *ORIGIN_PATHS],
                       cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    if r.returncode == 0: texts.append(r.stdout)
    r = subprocess.run(["git", "ls-tree", "-r", "--name-only", "origin/main", "--", "docs/backlog"],
                       cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    if r.returncode == 0: names += [Path(n).stem for n in r.stdout.split()]
    os_ = {int(m.group(1)) for n in names if (m := re.fullmatch(r"O(\d+)", n))}
    gs = {int(m.group(1)) for n in names if (m := re.fullmatch(r"G(\d+)", n))}
    for t in texts:
        os_ |= {int(n) for n in re.findall(r"^\| O(\d+)\b", t, re.M)}
        gs |= {int(n) for n in re.findall(r"^\| G(\d+) \|", t, re.M)}
        # Numbers named anywhere in prose count as taken too (reservations, cross-refs).
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
    if last is None: sys.exit(f"no Tier {tier} table in {INDEX.relative_to(ROOT)}")
    lines.insert(last + 1, row)
    return "\n".join(lines)


def replace_row(text, o, new_line):
    pat = re.compile(r"^\| %s\b[^|]*\|.*$" % o, re.M)
    if not pat.search(text): sys.exit(f"{o} not found in the index")
    return pat.sub(lambda _: new_line if new_line is not None else "\0", text, count=1).replace("\0\n", "")


def counts_block(index_text):
    rows = tier_rows(index_text)
    first = [re.search(r"\*\*`\[([^]]*)\]`", line) for _, line in rows.values()]
    first = [t.group(1) if t else "" for t in first]
    decision = sum(1 for t in first if "DECISION" in t or "DECIDED" in t)
    part = sum(1 for _, line in rows.values() if "`[PART]`" in line)
    struct = sum(1 for _, line in rows.values() if "`[STRUCT]`" in line)
    claude = read(CLAUDE)
    conventions = len(re.findall(r"^\| O\d+ \| \*\*`\[CONVENTION\]`", claude, re.M))
    tomb = len(re.findall(r"^\| O[^|]*\| \*\*`\[(CLOSED|SUBSUMED|DROPPED|SUPERSEDED)", claude, re.M))
    return (f"{COUNTS_BEGIN}\nGenerated by `scripts/backlog.py counts` — do not hand-edit. "
            f"{len(rows)} indexed rows ({part} `[PART]`, {struct} `[STRUCT]`, {decision} decision-tagged) · "
            f"{conventions} conventions · {tomb} tombstones · {len(file_ids(DONE, 'G'))} completed G-rows.\n{COUNTS_END}")


def regen_counts():
    idx = read(INDEX)
    if COUNTS_BEGIN not in idx: sys.exit("INDEX.md has no counts block")
    block = counts_block(idx)
    write(INDEX, re.sub(re.escape(COUNTS_BEGIN) + r".*?" + re.escape(COUNTS_END), lambda _: block, idx, flags=re.S))


def check():
    problems = []
    idx = read(INDEX)
    irows, bodies = tier_rows(idx), file_ids(OPEN, "O")
    for o in sorted(set(irows) - bodies): problems.append(f"{o} is indexed but has no docs/backlog/open/{o}.md")
    for o in sorted(bodies - set(irows)): problems.append(f"docs/backlog/open/{o}.md has no INDEX.md row")
    for o, (_, line) in irows.items():
        if re.search(r"\*\*`\[DONE", line): problems.append(f"{o} is [DONE] but still indexed as open — close it")
        if len(line) > 320: problems.append(f"{o} index row is {len(line)} chars — one line per row; the body belongs in open/{o}.md")
    for p in sorted(DONE.glob("*.md")):
        if not re.fullmatch(r"G\d+|P\d+", p.stem): problems.append(f"unexpected file in done/: {p.name}")
    for p in sorted(BACKLOG.rglob("*.md")):
        longest = max((len(l) for l in read(p).split("\n")), default=0)
        if p != INDEX and longest > MAX_LINE:
            problems.append(f"{p.relative_to(ROOT).as_posix()} has a {longest}-char line — file readers truncate it; wrap it")
    if COUNTS_BEGIN in idx and counts_block(idx) not in idx:
        problems.append("INDEX.md counts block is stale — run scripts/backlog.py counts")
    claude = read(CLAUDE)
    if tier_rows(claude): problems.append("CLAUDE.md has tier tables again — the index lives in docs/backlog/INDEX.md")
    size = len(claude.encode("utf-8"))
    if size > CLAUDE_BUDGET:
        problems.append(f"CLAUDE.md is {size} bytes, over the {CLAUDE_BUDGET} budget — move detail to a module CLAUDE.md or docs/")
    for p in problems: print(p)
    return 1 if problems else 0


def close(o, title, notes, remaining_title=None, remaining_body=None, remaining_tag="PART", g=None):
    g = g or next_ids()[1]
    if remaining_title:
        head = f"**{tags(remaining_tag)}** {remaining_title}"
        write(INDEX, replace_row(read(INDEX), o, f"| {o} [ ] | {head} |"))
        write(OPEN / f"{o}.md", row_file(o, head, remaining_body or ""))
    else:
        write(INDEX, replace_row(read(INDEX), o, None))
        (OPEN / f"{o}.md").unlink(missing_ok=True)
    write(DONE / f"G{g}.md", row_file(f"G{g}", f"**{title}**", notes))
    regen_counts(); print(f"closed {o} as G{g}: {title}")
    return g


def add_boxes(text):
    """Tick box on every open tier row except decision rows (nothing to complete)."""
    rows = tier_rows(text)
    for o, (_, line) in rows.items():
        if BOX.match(line) or re.search(r"\*\*`\[(DECISION|DECIDED)", line): continue
        text = text.replace(line, f"| {o} [ ]" + line[len(o) + 2:], 1)
    return text


def sweep(dry_run, resolution):
    import datetime
    ticked = [(o, line) for o, (_, line) in tier_rows(read(INDEX)).items()
              if (m := BOX.match(line)) and m.group(2) in "xX"]
    if not ticked: print("nothing ticked"); return 0
    g = next_ids()[1]
    stamp = f"Closed via tick-box sweep {datetime.date.today().isoformat()}"
    for o, line in ticked:
        title = re.sub(r"^\*\*`\[[^*]*\]`\*\*\s*", "", line.split("|", 2)[2].rsplit("|", 1)[0].strip())
        p = OPEN / f"{o}.md"
        body = row_parts(p)[1] if p.exists() else ""
        notes = stamp + (f": {resolution}." if resolution else ".") + (f"<br>Last row body:<br>{body}" if body else "")
        if dry_run: print(f"would close {o} as G{g}: {title}")
        else: close(o, title, notes, g=g)
        g += 1
    return 0


def show(ids):
    missing = 0
    for rid in ids:
        hits = []
        for d, label in ((OPEN, "open"), (DONE, "completed"), (ARCHIVE, "archive")):
            p = d / f"{rid}.md"
            if p.exists(): hits.append((f"{label} — {p.relative_to(ROOT).as_posix()}", read(p).strip()))
        pat = re.compile(r"^\| %s\b[^|]*\| (.*?) \| (.*) \|\s*$" % re.escape(rid), re.M)
        for p in TABLES:
            if p.exists() and (m := pat.search(read(p))):
                hits.append((p.relative_to(ROOT).as_posix(), f"# {rid}\n\n{m.group(1)}\n\n{wrap(m.group(2))}"))
        if not hits:
            m = re.search(r"^\| %s\b.*$" % re.escape(rid), read(INDEX), re.M)
            if m: hits.append(("index only — no body file", m.group(0)))
        if not hits: print(f"{rid}: not found"); missing = 1
        for where, text in hits: print(f"[{where}]\n{text}\n")
    return missing


def main():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"): stream.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("show").add_argument("ids", nargs="+")
    sub.add_parser("next-id").add_argument("--no-fetch", action="store_true")
    f = sub.add_parser("file")
    f.add_argument("--tier", type=int, required=True); f.add_argument("--title", required=True); f.add_argument("--body", required=True)
    f.add_argument("--tag", required=True, help="status tag(s) without backticks, e.g. 'TODO/CODE' or 'STRUCT,TODO/CODE'")
    f.add_argument("--number", help="use this O number (default: next-id)")
    c = sub.add_parser("close")
    c.add_argument("o"); c.add_argument("--title", required=True); c.add_argument("--notes", required=True)
    c.add_argument("--remaining-title"); c.add_argument("--remaining-body"); c.add_argument("--remaining-tag", default="PART")
    sw = sub.add_parser("sweep"); sw.add_argument("--dry-run", action="store_true"); sw.add_argument("--resolution")
    sub.add_parser("boxes"); sub.add_parser("counts"); sub.add_parser("check")
    a = ap.parse_args()

    if a.cmd == "show":
        return show(a.ids)
    if a.cmd == "next-id":
        o, g = next_ids(fetch=not a.no_fetch); print(f"O{o} G{g}"); return 0
    if a.cmd == "check":
        return check()
    if a.cmd == "counts":
        regen_counts(); return 0
    if a.cmd == "file":
        o = a.number or f"O{next_ids()[0]}"
        head = f"**{tags(a.tag)}** {a.title}"
        box = "" if re.search(r"DECISION|DECIDED", a.tag) else " [ ]"
        write(INDEX, insert_index_row(read(INDEX), a.tier, f"| {o}{box} | {head} |"))
        write(OPEN / f"{o}.md", row_file(o, head, a.body))
        regen_counts(); print(o); return 0
    if a.cmd == "close":
        close(a.o, a.title, a.notes, a.remaining_title, a.remaining_body, a.remaining_tag); return 0
    if a.cmd == "sweep":
        return sweep(a.dry_run, a.resolution)
    if a.cmd == "boxes":
        write(INDEX, add_boxes(read(INDEX))); return 0


if __name__ == "__main__":
    sys.exit(main())
