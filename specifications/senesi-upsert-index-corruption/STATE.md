# RESUME HERE — senesi upsert index-corruption

**Investigation phase is CLOSED.** All bug signatures are root-caused with failing reproduction
tests in place. This file previously tracked mid-investigation state (fuzz campaign progress,
in-flight hypotheses) — that content is now obsolete and has been removed to avoid misleading a
future agent; do not resurrect it from history.

## If you're fixing bugs

Read **`FIXES.md`** — it is the sole authoritative handoff for the fix phase: ground rules, 4
ordered fix items each with exact file:line defect sites + existing failing test + acceptance
criteria, and a verification matrix with expected pass/fail counts. Nothing in this file or in
`PLAN.md` overrides it.

Fix item 4 (session-concurrency guard) is DECIDED: fail-fast (see FIXES.md item 4). No open
decisions remain — the fix session can proceed through all 4 items in order.

## If you're doing further investigation

Read `PLAN.md` for the original investigation narrative and `scenarios/bug-0N-*.md` for the
per-bug deep dives (bug-04 is the root corruption doc; bug-05 was discovered en route). The
`investigations/` directory holds the FG-client-side exoneration report.
