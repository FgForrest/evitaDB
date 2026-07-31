# Architectural Decision Records

Every feature, optimization, non-trivial fix and refactor ends with **one record in
`documentation/adr/`** saying what was done, when, why, and how it relates to the rest. The record
replaces the assignment/plan it grew out of. Template: `documentation/adr/TEMPLATE.md`. Index and
conventions summary: `documentation/adr/README.md`.

## Before starting work

Search `documentation/adr/` for the area you are about to touch and read the **Consequences & open
follow-ups** section of every hit. Prior decisions and knowingly-deferred work live there; a change
that contradicts one needs a new record that `supersedes` it, not a silent reversal.

## When a record is required — the bar

**The default is no record.** Most work does not clear this bar, and that is the intended outcome:
every low-value record dilutes the folder and trains the next reader to skim past the ones that
matter. A thin ADR is worse than none.

Write one only when **all three** hold. If you are arguing yourself into one of them, the answer is
no.

1. **There was a genuine fork.** A competent engineer might reasonably have chosen a different
   option, and it was rejected for a reason the code does not show. No fork — no decision — no
   record. "It was hard" is not a fork; "we picked B over A because A breaks X" is.
2. **The reasoning outlives the change.** It constrains or informs work *beyond the files you
   touched* — a contract others code against, an invariant a future change could silently break, an
   option someone will otherwise re-propose. If the reasoning only matters to someone already
   reading that file, it belongs **in that file**, as a comment.
3. **Nothing cheaper carries it.** A commit message and a JavaDoc comment are the first choice. Reach
   for an ADR only when the reasoning has to be findable by someone who does not yet know which file
   to open.

**Size is not the test, in either direction.** A one-line change can clear it — the collation-cache
default in `2026-07-27-write-path-performance-tuning` is a single constant carrying a measured
decision and four rejected alternatives. A two-thousand-line mechanical refactor with no fork in it
does not clear it at all.

### Never a record

Typo and formatting fixes; renames; dependency bumps; generated-file regeneration; test fixture and
benchmark-harness tweaks; single-call-site refactors; config changes whose rationale is obvious from
the value itself; documentation edits; anything a reader of the diff plus its commit message would
already understand.

### Where sub-threshold reasoning goes

It still gets written down — just not here. Put it in the commit message when it explains *why this
change*, and in a code comment or JavaDoc when it explains *why this code stays this way*. A comment
at the site beats a record in a folder for anything file-local, because the next person to touch that
line will actually see it.

### Batching

**One record per line of work, not per commit or per PR.** A campaign spanning several PRs gets one
record covering all of them. Several small related changes in one area get **one** record at the end,
if they clear the bar together — not one each. If a sub-threshold change later turns out to matter,
fold it into the next record that covers its area rather than back-filling its own.

## Where and what to name it

- **Default — one file:** `documentation/adr/YYYY-MM-DD-<slug>.md`.
- **Only when justified — a directory:** `documentation/adr/YYYY-MM-DD-<slug>/README.md` plus
  supporting files, when the work produced *several independent decisions* **and** evidence worth
  keeping. Keep no file that cannot be given a one-line reason in the record's *Supporting
  material* section.

**Which date.** The date the decision was **accepted** — normally the merge date of the
implementing PR (`git log -1 --format=%ad --date=short <merge-commit>`). Not the date the document
was written, not the day the work started. Tie-breaks: several PRs → the last one that completed
the line of work; decision accepted but not yet implemented → the date it was accepted, with
`status: proposed`; never merged → the date it was abandoned, with `status: rejected`.

`date:` never changes once set, and must match the date in the filename — the index generator
fails when they disagree. `updated:` carries date **and** time and moves with every substantive
edit.

## Front matter

Required on every record — it is what makes the folder greppable (`rg -l "status: proposed"
documentation/adr/`) and the index generated rather than hand-maintained:

```yaml
title, date, updated, status, kind, issues, prs, areas, supersedes, superseded-by, relates
```

- `status`: `accepted` | `partially-implemented` | `proposed` | `superseded` | `rejected` | `reverted`
- `kind`: `feature` | `optimization` | `fix` | `refactor` | `infrastructure` | `process`
- `areas`: module or package paths, so `rg` finds the record from a file path
- Use `[]` for empty lists rather than omitting the key.

## Content rules

Sections and their required/omit-if-empty status are defined in `documentation/adr/TEMPLATE.md`.
Beyond that:

- **Delete sections you have nothing for.** Never leave "N/A" — a record padded with empty headings
  teaches the next reader to skim past them.
- **Options considered must be honest.** Record the alternative that was genuinely on the table
  with its real advantages. Do not invent a strawman to fill the section; if there was one viable
  path, say so in a sentence.
- **Every rejection carries its reason, next to the option it rejects.** A declined option gets a
  **Rejected because** line; a campaign's *Rejected outright* table gets a filled *Rejected because*
  column. Name the concrete blocker — a missing seam, a format constraint, a flat measurement — not
  a preference, and say what would have to change for it to be worth revisiting. An unexplained
  veto is worse than no entry: the next agent re-proposes it, or worse, obeys it without knowing
  whether it still applies.
- **Key technical details stay shallow.** Entry points, invariants, and anything that will look
  like a bug to someone who wasn't here. Pointers to code, not a re-description of it — the code is
  the source of truth and the record must not rot alongside it.
- **Quote numbers in Verification.** Name the test that proves it; for optimizations give
  before/after and the conditions they were measured under.
- **Cross-link in both directions.** Adding `relates`/`supersedes` to a new record means editing the
  older one too — and bumping the older record's `status` to `superseded` plus its `superseded-by`.
- **Regenerate the index** in the same commit: `tools/generate-adr-index.sh`. Never hand-edit the
  table between the `ADR-INDEX` markers — it is overwritten. `--check` verifies it is current and
  is safe to run from CI or a hook.

## Plans, and the two ways they end

In-flight plans, assignments and investigation notes live in **`specifications/`**, one folder per
line of work. That folder holds *intent*, never outcome: nothing in it is evidence of what shipped,
and it must never be read as such.

**Every plan must leave that folder when its work finalizes** — merged, abandoned, or decided
against. There are exactly two exits, and no third:

1. **It becomes a record**, if the work clears the bar above. Delete the plan in the **same commit**
   that adds the record.
2. **It is deleted outright**, if the work does not. The commit message carries anything worth
   keeping.

**A plan left behind is worse than no plan at all.** It reads as current intent, and the next reader
cannot tell whether it shipped, half-shipped, or was dropped — they have to re-derive that from git,
which is the exact cost this whole convention exists to remove. The historical `specifications/`
folder is what that failure looks like at scale: seventeen folders, several claiming work was
"uncommitted" or "awaiting go-ahead" for code that had merged weeks earlier.

**A partially-implemented plan is usually a record, not a deletion.** When some items shipped and
others were dropped, the dropped ones carry a rejection reason that git cannot show and that someone
will otherwise re-propose — that is a genuine fork, and it clears the bar. Write down *why* they
lost, not merely that they did.

Keep only material with future value that a record cannot absorb: measurements that cannot be
regenerated, reproduction scenarios, advisory verdicts that were relied on. Never keep raw profiler
dumps, JMH JSON or logs — capture their conclusions and drop the bytes.

## Commit

`docs: record <decision> as an ADR` with `Ref: #<issue-id>`, following `.claude/rules/git-workflow.md`.
Committing the record together with the implementation is fine and preferred; the record must not
land in a later PR than the work it describes.
