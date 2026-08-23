---
title: Publish the previous flush's page baseline before collecting; fail fast on stale twins and suspend the catalog rather than retrying a failed flush
date: 2026-07-18
updated: 2026-08-23 15:40
status: accepted
kind: fix
issues: []
prs: [1293, 1284]
areas: [evita_engine/index, evita_engine/store, evita_engine/core/transaction, evita_engine/core/session]
supersedes: []
superseded-by: []
relates: [2026-07-10-more-optimized-data-structures, 2026-07-27-write-path-performance-tuning, 2026-07-10-atomic-entity-mutation-partial-rollback]
---

# Paged-index corruption on warm-up flush, and the failure boundary for a failed flush

A production catalog ("senesi") failed to reload after a bulk re-index: a cold load reassembled a
survivor leaf followed by its stale sibling. The root cause was a page baseline that stayed empty for
the whole warm-up, so every freed-page reclaim and root-rewrite decision was taken against a baseline
that did not describe what disk actually held. The fix publishes the previous flush's staged baseline
before collecting the next one, across all seven paged indexes — plus a decision about what the engine
should *do* when a flush fails at all, which turned out to be the more consequential half.

## Why

The investigation started from five distinct failure signatures on one production dataset:
`FilterIndex` record-not-found, a sort-tree "key already present", a commit-progress hang, the stale
leaf-page twin itself, and a session-close future that never completed. They were assumed related and
mostly were — four of the five trace back to the same paged-persistence seam introduced by the
granular storage-parts work.

The mechanism: **a warm-up (bulk re-index) flush never reaches a transactional commit-merge**, and the
commit-merge is what published `PageStreamRegistry`'s live-page baseline. So during a whole re-index
the published baseline stayed empty. A leaf merge then left the dropped page *both* unremoved on disk
*and* still listed on a PAGED root that the flush skipped as "unchanged" — and a later cold load
reassembled the survivor leaf followed by its stale sibling.

Sitting underneath was a second, more dangerous problem that the first one exposed. On a failed
trunk-incorporation flush the engine caught the exception and **retried in place, unboundedly**,
reusing the same live instances and the surviving staged set. `popTrappedUpdates` is destructive and
advances every baseline *before* the write, so a retry diffs against baselines a failed flush left
behind. For a deterministic failure this is also a livelock — one that already de-facto stopped
transaction progress while readers kept working, just invisibly.

### Previous state

`PageStreamRegistry`'s baseline was published only by the transactional commit-merge. A failed flush
was followed by an unbounded in-place retry (`TransactionManager` catch → `retryTransactionProcessing`
→ reschedule the WAL drain). JavaDoc across seven `publishPreviousFlush()` sites asserted that "a
flush failure is fatal" — which was not true then and is not true now. A warm-up flush failure
silently lost the popped storage parts and continued.

## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-07-17 | **Suspend the catalog's transaction processing on a failed flush — do not fatalize it** (owner decision, Johnny) | Reader liveness is the priority: dozens of clients read from a live catalog, and killing it to recover from a *write-side* failure trades a reader outage for a writer problem. Suspend enforces the identical invariant — "no next flush may run against the baselines a failed flush left behind" — vacuously, because there is no next flush | `DECISION-failure-boundary.md` |
| 2026-07-17 | Scope the suspend **positionally — at "collect began" — not by exception type** | The catch site is a blanket `RuntimeException` and legitimately also serves overload cases (`RejectedExecutionException`, publisher re-creation) that must keep their bounded retry. Baselines are only poisoned once `popTrappedUpdates` has begun draining | same |
| 2026-07-17 | **Delete the retry** for flush/merge failures | It is the corruption vector *and*, for a deterministic failure, the livelock. The advisory's finding stands: the retry loop has no good case | same |
| 2026-07-17 | Parked commit futures must complete **exceptionally in bounded time, never hang** | Transactions durable in the WAL but never incorporated leave clients parked on the visibility stage. Precedent copied directly: `TransactionManager.close()` already fails all pending with a descriptive reason | same |
| 2026-07-18 | **Publish the previous flush's staged page baseline before collecting the next** — the core fix | Makes the freed-page reclaim and the root-rewrite decision be taken against what disk actually holds, rather than against an empty baseline | commit `4e2db0270` |
| 2026-07-18 | Apply it to **all seven paged indexes at once**, not just the one that failed | The defect is in the shared paged-persistence contract, not in any one index. `InvertedIndex`, `RangeIndex`, `ChainIndex`, `OwnerUniqueIndex`, `GlobalUniqueIndex`, `PriceListAndCurrencyPriceSuperIndex`, `ReferenceTypeCardinalityIndex` (`OwnerSortIndex` is covered via its owned `InvertedIndex`) | same |
| 2026-07-18 | On load, **detect and fail fast — do not repair** | Reversal of the earlier heal-on-load approach; see *Rejected outright* | same |
| 2026-07-18 | PAGED→SINGLE collapse reclaims from the **staged-or-published** set, not the published set | The published set lags a flush behind — this is the `forgetPageStream()` throw during async commit-flush | same |
| 2026-07-18 | **Poison the warm-up buffer** on a failed warm-up flush: refuse every later collect | Warm-up has no readers to protect, and `terminateInternally` *does* flush every collection when warming up — so a mid-session failure would otherwise be followed by a close-time flush against poisoned baselines | `DECISION-failure-boundary.md`, `ADVISORY-VERDICT-warmup-page-baseline.md` |
| 2026-07-18 | Add a **stale-baseline tripwire** in `PageStreamRegistry`: cross-check the collected page list against the freed/fresh signals and fail fast on disagreement | Would have turned this entire bug class into a loud flush-time failure. Its diagnostic is built inside a lazy supplier, so a healthy flush pays nothing | commit `4e2db0270` |
| 2026-07-16 | **Fail fast on concurrent session access** rather than serialising it | A second thread entering a live session is a client bug, not a condition to paper over. Thread-identity based, so same-thread re-entrancy still works; only business methods are guarded, housekeeping (`isActive`, `isInactiveAndIdle`) stays unguarded | new `ConcurrentSessionAccessException` |

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| **Fatalize the catalog on a trunk-incorporation flush failure** (the advisory's own recommendation) | It kills readers to recover from a write-side problem. Two facts make suspend-and-serve safe instead: the **in-memory tree is always correct** — the defect is entirely in persisted state and bookkeeping baselines — and for a pre-durability failure the disk stays consistent at the last durable version, because `storeHeader` never runs and the partial bytes are unreferenced garbage | Never, for production. Reader liveness outranks writer recovery convenience |
| **Heal the stale twin on load** (drop a strict-prefix twin with a WARN) | Superseded by preventing it at flush time. A load-side heal repairs the symptom while leaving the flush free to keep producing it, and silently repairing persisted-state corruption removes the signal that something is wrong. The load path now reports loudly and does not repair | The prevention is ever proven insufficient — but then the flush contract is what needs fixing |
| **`discardStaged()` — an abort-half for the staged set** | After a failed flush there is no next flush to consume the staged set, and recovery rebuilds the registry from disk truth. Resuming transaction processing without a reload is not feasible anyway: the popped storage parts are already gone | Resume-without-reload becomes feasible |
| **Treating reload after any failure as "clean"** | It is clean only for a **pre-durability** (flush-stage) failure. A **post-durability** (merge-stage) failure leaves version N durable on disk while memory stays at N−1, so a reload lands on a version whose bytes came from the state whose merge validation just failed — and a deterministic failure will likely re-fail identically on replay. That is backup-restore territory. The suspend state must therefore distinguish the two loci and say which in the alert | — |
| **Silent self-recovery for transient failures** (today's behaviour) | It is genuinely nicer for a momentary disk-full, and suspend converts those into operator incidents. Taken anyway: that is the right trade for a database — but it is recorded out loud so the first production suspend is not misread as a regression | The shadow-swap option below is built |

## Key technical details

- **The invariant the whole design rests on:** *no next flush may run against the in-memory baselines
  left behind by a failed flush.* Suspend (ALIVE) and poison (WARM_UP) are the same invariant in two
  dresses.
- **For an ALIVE catalog, suspending trunk incorporation is sufficient.** `Catalog.flush()` asserts
  `WARMING_UP` on entry, `terminateInternally` gates its collection flush on warm-up state, and
  `TransactionManager.close()` never flushes — so trunk incorporation is the only route to
  `popTrappedUpdates`. A graceful shutdown cannot betray the suspend. **For WARM_UP the trap is real**:
  `terminateInternally` does flush, so poison must cover all four warm-up collect paths — session
  close, lifecycle ops, `goLive`, and terminate.
- **Nothing is lost by suspending.** The transaction is durable in the WAL before trunk incorporation
  runs, so a later reload re-applies it.
- **WAL retention is load-bearing and pinned by a test**, not assumed: suspension pins `lastFinalized`
  at N−1, which must hold WAL purge back or recovery loses the entries it depends on.
- **The `publishPreviousFlush()` JavaDoc was wrong before and after** and was reworded family-wide
  (7 clones plus a merge-side comment the rollout had copied into `InvertedIndex`): a flush failure is
  fatal to the catalog's *write path*, not to the process.
- Load-time overlap detectors carry an **error-path-only** diagnostic: the ordered page list, both
  offending page sequences, each leaf's key range and count, and a raw containment fact.

## Verification

Shipped across **two PRs**. PR #1284 (`warmup-alloc-and-tx-hardening`, merged 2026-07-16) carried the
four fix-session items — the WAL read path, the flush-throw close-future completion, the load-side twin
guard and the session-concurrency fail-fast — together with the B+ tree dirty-scope validation.
PR #1293 (merged 2026-07-18) then carried the core baseline fix and the suspend boundary, in two
commits: the fix and a review-feedback pass.

Per-index warm-up-flush merge reproductions, a transactional merge-flush-failure suspend test, a
mock-level suspension test, and tree-level overlap-diagnostic assertions. The bug-specific
reproductions written during the investigation are all present in the tree:
`StaleLeafPageTwinReproductionTest`, `StaleLeafPageTwinWriterReproductionTest`,
`WarmUpFlushFailureCloseTest` (the close-future hang: 88 s → 3.7 s), and
`TransactionManagerBoundedWaitTest`.

One test-fixture correction worth keeping, because it nearly inverted a conclusion: a WAL test
failure looked like reader misalignment but the fixture's transactions were **content-byte counts
filled with literal bytes `0,1,2,…`**, never serialized mutations — so once the constructor stopped
dry-reading, the code faithfully deserialized garbage (`class ID: 11` = content byte `0x0D` minus 2,
byte-exact). The supplier change **exposed** the bad fixture; it did not cause it.

The FG client library was independently investigated and **exonerated** — the corruption is
server-side.

## Consequences & open follow-ups

- **Transient flush failures are now operator incidents.** Previously they self-recovered silently,
  with the corruption risk that motivated all of this. This is deliberate and must not be misread as a
  regression.
- **A repair tool is still needed** for catalogs corrupted before this fix — they will keep failing
  boot. The two failure loci give it a second use case: a post-durability failure leaves a suspect
  durable version that a reload will land on.
- **Zero-outage recovery (shadow-swap) is the recorded long-term answer** and was deliberately not
  built: background shadow-load of the catalog from disk plus WAL, then an atomic swap so readers stay
  on the old instance until the new one is ready. Worth building only if suspend-and-alert proves too
  blunt in practice.
- The load-side heal implemented during the fix session (`resolveHealedPageIndices`) is **not in the
  tree** — it was superseded by prevention at flush time, as recorded above.

## Related work

- **`2026-07-10-more-optimized-data-structures`** — introduced the granular paged persistence whose
  baseline contract this record fixes. The bug is a consequence of that design, not of its
  implementation quality: the contract simply had no warm-up story.
- **`2026-07-27-write-path-performance-tuning`** — the same transactional-memory and paged-storage seam,
  approached from performance. Its B+ tree dirty-scope rework (`dd193f25d`, "fail fast on corrupt
  transactional data structures via dirty-scope validation") is the same fail-fast doctrine applied one
  layer down.

## Supporting material

- `scenarios/` — the five bug reproductions that opened the investigation, each with its own deep dive.
  `bug-04-stale-leaf-page-twin.md` is the root corruption document.
- `ADVISORY-VERDICT-warmup-page-baseline.md` — the independent verdict this work was built on, including
  the fatalize recommendation the owner decision overrode.
- `DECISION-failure-boundary.md` — the owner decision itself, with the reasoning for suspend over
  fatality and the two-failure-loci correction.
- `TIER_A_ADVISORY.md`, `TIER_BC_ADVISORY.md`, `TIER_BC_REVIEW.md` — the advisories behind extending the
  fix to the other paged index restore paths.
- `investigations/` — the client-library exoneration report.
- `tx-flush-forgetPageStream-reproduction.md` — the PAGED→SINGLE collapse reproduction.

Not carried over: the investigation's mid-flight state (`STATE.md`, `PLAN.md`, `FIXES.md`,
`FIXES-PROGRESS.md`, `ROUND2-FINDINGS.md`, `HYPOTHESIS-*`, `BRIEF-*`, `HANDOFF-*`, `IMPLEMENTATION-PLAN.md`)
— all superseded by this record and by what shipped — and the raw `oplog.txt` dumps.

## Timeline

- **2026-07-12 → 07-16** — five failure signatures reproduced and root-caused on the senesi dataset;
  the client library investigated and exonerated
- **2026-07-16** — the four fix-session items merge as PR #1284, together with B+ tree dirty-scope
  validation
- **2026-07-17** — advisory verdict delivered recommending catalog fatality; owner decision overrides it
  with suspend-and-serve
- **2026-07-18** — the core baseline fix implemented across all seven paged indexes, with the suspend
  boundary; merged as PR #1293
- **2026-07-31** — the three working folders consolidated into this record; the load-side heal confirmed
  superseded by flush-time prevention
