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

## No customer attribution in records

**A record must contain no detail that identifies a customer.** The measurement stays; the attribution
goes. This applies to the record, its supporting files, its *filenames*, and the **commit messages**
that carry it — a name scrubbed from the prose but left in `git log` has not been scrubbed.

This is the ADR-specific half of a rule that also covers user documentation and code; the scope
boundary, and the material that is deliberately exempt, are in `CLAUDE.md`. **Do not apply this
section outside `documentation/adr/`** without reading that boundary first — blog posts, research
documents and published performance comparisons name datasets deliberately and must not be scrubbed.

A record is written to be read by someone who was not there, and the customer's identity is never the
part that helps them. What helps is the workload: how many entities, how the values were shaped, what
the index did with them.

**Forbidden**: customer, brand or project names, whether standalone or embedded in an identifier
(`<client>Ordering`, `<client>UpsertFuzzer`, `article.<client>Id`, a temp catalog `<client>_<millis>`);
verbatim sample values from a customer dataset — product codes, EANs, catalog numbers, URLs, names,
anything a person could paste into a search box and hit a real record; and internal source documents
that cannot be published (cite these as "(internal, §X)", never link or quote them).

**Keep, always**: every number, ratio and distribution. Corpus size, cardinality, value-length
percentiles, alphabet size and character classes, prefix-sharing fractions, latency and speedup — none
of it identifies anyone, and stripping it would gut the record.

**How to say it instead.** Describe the workload, not the customer: "a production e-commerce catalog
(~157,000 products, 18 collections)", "a production CMS catalog (972,611 articles)", "the production
retail corpus". Where two datasets must be told apart across a record, label them by shape or by
letter — "the retail corpus" and "the CMS corpus", or "corpus A"/"corpus B" — and define the label
once. For sample data, give the *shape* and never the value: "13 digits", "two upper-case letters
followed by four digits", "GS1 country prefixes concentrated in a handful of ranges".

**Attribute and collection names from a customer schema are a judgement call.** Generic e-commerce
vocabulary (`code`, `catalogNumber`, `ean`, `product`, `category`) is fine and worth keeping, because
it tells a reader which *shape* of attribute the finding applies to. A name that carries the customer's
own identity is not — rename it to its generic equivalent and say you did.

**When you find a violation in an existing record**, say so rather than fixing it silently: the
anonymization may need a decision about which label to use, and a record whose history quietly diverges
from its content is worse than one that is openly wrong. Anonymizing history is a rewrite, and a
rewrite is the author's call, not yours.

## Plans, and the two ways they end

In-flight plans, assignments and investigation notes live in **`specifications/`**, one folder per
line of work. That folder holds *intent*, never outcome: nothing in it is evidence of what shipped,
and it must never be read as such.

**Plans are never committed.** `/specifications/` is git-ignored, so a plan is working notes on disk
and nothing more. This is mechanical rather than a matter of discipline on purpose: `git add -A` is
how plans got committed before, and a rule that a routine command silently violates is not a rule.
Folders tracked before this convention stay tracked — git-ignore does not untrack — and they leave
the tree the same way any other plan does.

**Every plan must leave the disk when its work finalizes** — merged, abandoned, or decided against.
There are exactly two exits, and no third:

1. **It becomes a record**, if the work clears the bar above. Write the ADR, then delete the plan
   folder from disk.
2. **It is deleted outright**, if the work does not. The commit message of the last change carries
   anything worth keeping.

**Because the plan is not in git, deleting it is irreversible.** Nothing can be recovered from
history afterwards, so everything of lasting value must be inside the record *before* the folder
goes — the rejected options and why they lost, measurements that cannot be regenerated, ordering
hazards a future step could trip over. Read the plan through once against the finished record and
ask what would be unrecoverable, rather than deleting on the assumption it was all captured.

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
