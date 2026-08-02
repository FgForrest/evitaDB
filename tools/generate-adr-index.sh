#!/usr/bin/env bash
#
# Regenerates the decision-record table in documentation/adr/README.md from the front matter of
# the records themselves, replacing whatever sits between the ADR-INDEX:START/END markers.
# Everything outside those markers is left untouched.
#
# Doubles as a linter: any record missing a required front-matter field, or carrying a filename
# whose date disagrees with its `date:`, is reported and the script exits non-zero.
#
# Usage:  tools/generate-adr-index.sh [--check]
#           (no args)  rewrite the table in place
#           --check    report what would change and fail if the table is stale; writes nothing
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADR_DIR="${PROJECT_ROOT}/documentation/adr"
INDEX_FILE="${ADR_DIR}/README.md"

MODE="write"
if [[ "${1:-}" == "--check" ]]; then
	MODE="check"
elif [[ $# -gt 0 ]]; then
	echo "usage: $(basename "$0") [--check]" >&2
	exit 2
fi

[[ -d "$ADR_DIR" ]] || { echo "no such directory: $ADR_DIR" >&2; exit 1; }
[[ -f "$INDEX_FILE" ]] || { echo "no such file: $INDEX_FILE" >&2; exit 1; }

ADR_DIR="$ADR_DIR" INDEX_FILE="$INDEX_FILE" MODE="$MODE" python3 - <<'PYTHON'
import os
import re
import sys
from pathlib import Path

ADR_DIR = Path(os.environ["ADR_DIR"])
INDEX_FILE = Path(os.environ["INDEX_FILE"])
MODE = os.environ["MODE"]

START = "<!-- ADR-INDEX:START -->"
END = "<!-- ADR-INDEX:END -->"

REQUIRED = ("title", "date", "status", "kind")
DATED = re.compile(r"^(\d{4}-\d{2}-\d{2})-(.+)$")
# strips a trailing YAML comment ("  # note") without eating an issue reference ("#1314")
COMMENT = re.compile(r"\s+#\s.*$")

problems = []


def parse_front_matter(path):
	"""Reads the leading --- ... --- block into a dict; returns None when there is none."""
	lines = path.read_text(encoding="utf-8").splitlines()
	if not lines or lines[0].strip() != "---":
		return None
	fields = {}
	for line in lines[1:]:
		if line.strip() == "---":
			return fields
		match = re.match(r"^([A-Za-z][A-Za-z0-9_-]*):\s*(.*)$", line)
		if match:
			fields[match.group(1)] = COMMENT.sub("", match.group(2)).strip()
	return None  # unterminated front matter


def as_list(raw):
	"""[1314, 1315] -> ['1314', '1315']; empty list or blank -> []."""
	raw = (raw or "").strip()
	if raw.startswith("[") and raw.endswith("]"):
		raw = raw[1:-1]
	return [item.strip().strip("'\"") for item in raw.split(",") if item.strip()]


def collect():
	"""Finds every record: a dated .md file, or README.md inside a dated directory."""
	records = []
	for entry in sorted(ADR_DIR.iterdir()):
		if entry.is_file() and entry.suffix == ".md" and DATED.match(entry.stem):
			record, link = entry, entry.name
		elif entry.is_dir() and DATED.match(entry.name) and (entry / "README.md").is_file():
			record, link = entry / "README.md", f"{entry.name}/"
		else:
			continue

		front = parse_front_matter(record)
		if front is None:
			problems.append(f"{link}: no parseable YAML front matter")
			continue

		missing = [field for field in REQUIRED if not front.get(field)]
		if missing:
			problems.append(f"{link}: missing front-matter field(s): {', '.join(missing)}")
			continue

		stem = entry.stem if entry.is_file() else entry.name
		filename_date = DATED.match(stem).group(1)
		if filename_date != front["date"]:
			problems.append(
				f"{link}: filename date {filename_date} disagrees with front-matter date {front['date']}"
			)

		refs = [f"#{issue}" for issue in as_list(front.get("issues"))]
		refs += [f"PR #{pr}" for pr in as_list(front.get("prs"))]
		records.append({
			"date": front["date"],
			"title": front["title"].strip("\"'"),
			"kind": front["kind"],
			"status": front["status"],
			"refs": ", ".join(refs) or "—",
			"link": link,
		})

	# newest first; ties broken by title so the output is stable across runs
	records.sort(key=lambda record: (record["date"], record["title"]), reverse=True)
	return records


def render(records):
	if not records:
		return "*No decision records yet.*"
	rows = ["| Date | Record | Kind | Status | Refs |", "|------|--------|------|--------|------|"]
	rows += [
		"| {date} | [{title}]({link}) | {kind} | {status} | {refs} |".format(**record)
		for record in records
	]
	return "\n".join(rows)


records = collect()
table = render(records)
original = INDEX_FILE.read_text(encoding="utf-8")

if START not in original or END not in original:
	print(f"{INDEX_FILE.name}: missing {START} / {END} markers", file=sys.stderr)
	sys.exit(1)

head, rest = original.split(START, 1)
_, tail = rest.split(END, 1)
updated = f"{head}{START}\n\n{table}\n\n{END}{tail}"

for problem in problems:
	print(f"error: {problem}", file=sys.stderr)

# A rejected record is absent from `records`, so writing now would silently delete its row.
# Refuse to touch the file until the front matter is fixed.
if problems:
	print(
		f"{len(problems)} record(s) failed validation — index left untouched. "
		"Fix the front matter and re-run.",
		file=sys.stderr,
	)
	sys.exit(1)

if MODE == "check":
	if updated != original:
		print("documentation/adr/README.md is stale — run tools/generate-adr-index.sh", file=sys.stderr)
		sys.exit(1)
	print(f"index up to date ({len(records)} record(s))")
else:
	if updated != original:
		INDEX_FILE.write_text(updated, encoding="utf-8")
		print(f"index rewritten ({len(records)} record(s))")
	else:
		print(f"index already up to date ({len(records)} record(s))")
PYTHON
