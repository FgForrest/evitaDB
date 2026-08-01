# ADR template

**First, check the work clears the bar** (`.claude/rules/adr.md`): a genuine fork, reasoning that
outlives the change, and nothing cheaper that carries it. All three, or write a commit message and a
code comment instead. Opening this template is not the decision — the bar is, and it defaults to no.

Then copy this file to `documentation/adr/YYYY-MM-DD-<slug>.md` and fill it in. Delete every
*italic guidance line* and every section marked **omit-if-empty** that you have nothing to
put in — an ADR full of "N/A" trains the next reader to skip sections.

Full conventions (when to write one, which date to use, how to retire the plan it replaces):
`.claude/rules/adr.md`.

**Two shapes.** Use the *point-decision* shape (below) by default — one fork in the road, one
file. Use the *campaign* shape (at the bottom) only when a single line of work produced many
decisions over weeks and its evidence must be kept; then it becomes a directory.

---

## Point-decision shape — `documentation/adr/YYYY-MM-DD-<slug>.md`

```markdown
---
title: <one line, the decision itself — not the ticket title>
date: 2026-07-24            # accepted; normally the merge date of the implementing PR
updated: 2026-07-24 16:20   # last substantive edit of this record (local time)
status: accepted            # accepted | partially-implemented | proposed | superseded | rejected | reverted
kind: fix                   # feature | optimization | fix | refactor | infrastructure | process
issues: [1314]              # GitHub issue numbers, [] if none
prs: [1315]                 # GitHub PR numbers, [] if not merged yet
areas: [evita_store/evita_traffic_engine]   # module or package paths the change lives in
supersedes: []              # ADR ids — the filename without .md
superseded-by: []
relates: [traffic-recording-on-demand-export]   # an ADR id, or a not-yet-converted folder slug
---

# <Title — the decision, stated as a decision>

*One paragraph: what was done, in plain terms, for someone who has never seen the issue.*

## Why

*The driving forces. What hurt, who noticed, what it cost. Name the constraint that made this
non-obvious — if there was no constraint, this probably didn't need an ADR.*

### Previous state

**omit-if-empty** — *required whenever something existing changed. How it worked before, and
why that was acceptable until it wasn't. This is the section future readers need most when they
find code that looks odd: it explains what the odd-looking code replaced.*

## Options considered

*Two or more, each with honest pros and cons. If there genuinely was only one viable path, say so
in one sentence and why — do not invent alternatives to fill the section.*

### Option A — <name> (chosen)

*One paragraph of what it is.*

- **Pros:** …
- **Cons:** …

### Option B — <name> (declined)

- **Pros:** …
- **Cons:** …
- **Rejected because:** *the specific reason this one lost — required. Stated next to the option
  so it is findable without reading the Decision prose. "Worse" is not a reason; name the driver
  it failed on.*

## Decision

**Chosen: Option A.** *Why this one won, in terms of the drivers above — not a restatement of the
pros. Include what would have to change for the other option to win; that is the trigger for a
future ADR that supersedes this one.*

## Key technical details

*Deliberately shallow — enough to find the code and not break it, never a re-description of the
implementation. Prefer pointers over prose:*

- *Entry points: `path/to/Class.java` — what it now does.*
- *Invariants a future change must preserve (ordering, thread-safety, bounds, on-disk format).*
- *Anything counter-intuitive that will look like a bug to someone who wasn't here.*

## Verification

*How we know it works. Name the tests, quote the numbers. Before/after for optimizations.*

## Consequences & open follow-ups

**omit-if-empty** — *What this enables, what it costs, and what was knowingly left undone. Be
specific enough that a follow-up is actionable: file, symptom, why it was deferred.*

## Related work

**omit-if-empty** — *Links to sibling ADRs and why they are siblings (shared code area, same
issue, one enabled the other). Bare links are useless; one clause of context each.*

## Timeline

*Dates only, unless two entries share a day and their order matters — then add the time
(`2026-07-24 15:49`), same format as `updated:`.*

- **2026-07-22** — problem reported / investigation started
- **2026-07-24** — implemented, PR #1315 merged
```

---

## Campaign shape — `documentation/adr/YYYY-MM-DD-<slug>/README.md`

Same front matter, plus `date:` = the date the campaign concluded. Use this only when both hold:
the work produced **several independent decisions**, and **evidence worth keeping** (measurements
that cannot be regenerated, reproduction scenarios, advisory verdicts). Supporting files live
beside the README; the README is the ADR and must stand alone without them.

Replace *Options considered* / *Decision* with one table, and give each row its own short section
below only if it needs one:

```markdown
## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-07-22 | Cache collation keys rather than store them | Cache is reversible; storing touches the persisted Kryo format | `reports/2026-07-22-warmup-upsert-and-collation.md` |
| 2026-07-24 | Prune clean subtrees; do **not** invert the trunk merge bottom-up | No parent links, no shared substitution seam | `reports/2026-07-24-trunk-merge-and-index-carry.md` |

## Rejected outright

*Options killed early — one row each. **The reason is the whole point of this section**: it is what
stops the next agent from re-proposing them, and a row without one is worse than no row, because it
reads as an unexplained veto. Name the concrete blocker (a missing seam, a format constraint, a
measurement that came back flat), not a preference. If the rejection was conditional, say what
would have to change for it to be worth revisiting.*

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Invert the trunk merge bottom-up | No parent links on the nodes and no shared substitution seam — it is not a tuning change but a redesign of the merge contract | Nodes ever gain parent links for another reason |
| Store collation keys in the sort index | Correctness-critical and touches the persisted Kryo format; the cache got most of the win reversibly | Cache sizing is exhausted and collation still shows on the profile |

## Supporting material

*One line per kept file saying what question it answers. If a file has no such line, it should
not have been kept.*
```

All other sections (*Why*, *Key technical details*, *Verification*, *Consequences & open
follow-ups*, *Related work*, *Timeline*) are unchanged and still required.
