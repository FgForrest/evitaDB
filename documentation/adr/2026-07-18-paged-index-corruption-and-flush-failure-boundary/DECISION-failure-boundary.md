# Decision: the failure boundary — SUSPEND, do not fatalize

*Owner decision (Johnny, 2026-07-17), answering `ADVISORY-VERDICT-warmup-page-baseline.md` §2.3 / §0.2.
Supersedes the verdict's "fatalize a trunk-incorporation failure for the catalog" recommendation.*

---

## The decision

**Catalog fatality is REJECTED for production.** Reader liveness is the top priority: dozens of clients read
actively from a live catalog, and killing/reloading it to recover from a *write-side* failure trades a reader
outage for a writer problem. **We would rather stop transaction processing than stop the readers.**

## Why this is not a concession on safety

The pilot fix (seam A) needs one guarantee, and one only:

> **no NEXT flush may run against the in-memory baselines left behind by a FAILED flush.**

Fatality was merely one way to enforce it (kill everything ⇒ trivially no next flush). **Suspending transaction
processing for the catalog enforces exactly the same guarantee**, because after the failure no further flush of
that catalog ever runs. A's invariant — *"still staged when the next flush begins ⇒ on disk"* — then holds
**vacuously**: there is no next flush.

Two facts make suspend-and-serve safe, both established by this investigation:

1. **The in-memory tree is always CORRECT.** The entire defect is in the *persisted* state and the *bookkeeping
   baselines*. The trunk the readers are served from is sound — there is no correctness reason to tear it down.
2. **Disk stays consistent at the last durable version — for FLUSH-stage failures only** (see the two failure
   loci below). A throw in `flushTrappedUpdates` (`Catalog.java:1868`) means `storeHeader` (`:1873`) never runs,
   so the persisted header still points at the previous version and the partially written parts are unreferenced
   garbage (verified: volatile values are forgotten, never promoted into any root, dropped at compaction).

### The two failure loci are NOT symmetric (advisory correction, accepted)

Trunk incorporation orders: **collect+stage → durable write (`flushTrappedUpdates` + `storeHeader`) → merge/publish**.
The same catch receives both, but the disk state differs:

| failure locus | disk after failure | recovery by reload |
|---|---|---|
| **pre-durability** (flush stage — I/O, disk-full) | still at **N−1**; partial bytes unreferenced | **clean** — reload lands on N−1, WAL replays forward |
| **post-durability** (merge stage — e.g. `DataStructureCorruptedException` from the post-replay validators) | **N is durable**; memory stays at N−1 | reload lands on **N**, whose bytes came from the very state whose merge validation just failed — the boot-side validators are the only net under it. If the merge failure is deterministic, the reload's WAL replay likely re-fails identically ⇒ **backup-restore / repair territory**, not "the ordinary boot path" |

**Therefore the suspend state must distinguish the two loci** and say so in the alert, so an operator knows
whether a reload is clean or lands on a suspect version. (Fatality would have hit the identical wall, so this does
not argue against suspend — it argues against calling reload "always clean".) This grows the Q6 repair tool a
second use case.

**Nothing is lost.** The transaction is already durable in the WAL before trunk incorporation ever runs
(incorporation is fed by the WAL drain, `TransactionManager.walDrainingTask`); a later reload re-applies it.

## Gate EVERY path that can reach `popTrappedUpdates` (advisory Gap 1 — verified, and it splits)

The rule is right: a suspend/poison flag that does not gate *every* collect path is theatre. Verified against the
code, the two states differ sharply:

- **ALIVE / suspend — the close-time-flush trap does NOT exist.** `Catalog.flush()` (the collect path) opens with
  `Assert.isPremiseValid(getCatalogState() == CatalogState.WARMING_UP, "Cannot flush catalog in transactional
  mode…")`, so an ALIVE catalog **cannot** reach it. `terminateInternally` gates its collection flush on
  `warmingUpState` (`Catalog.java:2460-2473`); `TransactionManager.close()` closes the pipeline and fails pending
  commit records but never flushes; `EntityCollection.terminate()` only closes its persistence service. ⇒ For an
  ALIVE catalog the **only** route to `popTrappedUpdates` is trunk incorporation
  (`Catalog.flush(catalogVersion, lastProcessedTransaction)`), so suspending trunk incorporation is **sufficient**
  — a graceful shutdown cannot betray it.
  *(The advisory's premise — "the round-4 shutdown flushed the replayed state" — traced back to a mis-attribution
  in our own notes: the v333 bootstrap record was written by the replay's own `storeHeader`, not by a
  shutdown flush.)*
- **WARM_UP / poison — the trap is REAL.** `terminateInternally` **does** flush every collection when warming up
  (`Catalog.java:2464-2470`), so a mid-session lifecycle flush failure would be followed by a close-time flush
  against poisoned baselines. The poison must make that flush **refuse/skip (and log)** — never a second attempt.
  Poison must cover all four warm-up collect paths: session close, lifecycle ops (`:2236`, `:2286`, `:2430`),
  goLive (`MakeCatalogAliveMutationOperator:105`) and **terminate** (`:2464`).

## What actually changes (a DELETION plus real semantics work)

Today's behaviour on a trunk-incorporation flush/merge failure is `TransactionManager.java:1302` catch →
`retryTransactionProcessing:542` → reschedule the WAL drain — an **unbounded in-place retry** reusing the same
live instances and the surviving staged set. Note this *already* de-facto stops transaction progress while readers
keep working (the known trunk-incorporation spin) — it is just implemented as an invisible livelock that ALSO
retries against poisoned baselines. So:

- **Remove the retry** for flush/merge failures — this is the corruption vector (§2.1 of the verdict) and, for a
  deterministic failure, the livelock. The verdict's own finding stands: the retry loop has **no good case**.
- **Suspend transaction processing** for the affected catalog: explicit state + loud ERROR + metric/observability
  event (never a silent spin).
- **Keep the in-memory catalog serving reads**, unchanged.
- **Recovery = operator-chosen reload/restart**, at a convenient moment (registry, dirty flags and forgotten
  streams are all rebuilt from disk truth via `fromPersistedPages` — the already-trusted crash path, validated by
  the clean 273-tx production WAL replay). **Caveat:** clean only for a *pre-durability* failure; see the two
  failure loci above.

### The deletion alone is not enough (advisory Gap 3 — accepted in full)

1. **Scope the suspend positionally, not by exception type.** `TransactionManager.java:1302` is a blanket
   `RuntimeException` catch, and `retryTransactionProcessing` legitimately serves overload cases
   (`RejectedExecutionException`, publisher re-creation) that must keep their bounded retry. Baselines are only
   poisoned **once `popTrappedUpdates` has begun draining**; a failure before any collect (mutation replay into
   the layer, conflict handling) leaves them untouched. **The suspend boundary belongs at "collect began", not at
   "any exception".**
2. **Commit-future semantics must be crisp.** Transactions durable in the WAL but never incorporated leave clients
   parked on the visibility stage of `CommitProgress`. Under suspend they must complete **exceptionally (or with
   an explicit accepted-not-visible outcome) in bounded time — never hang**, per the same doctrine as the
   watchdog / CSAE work. New commits need a clean rejection surface at acceptance. **Precedent to copy:**
   `TransactionManager.close()` already does exactly this —
   `pendingCommitProgressRegistry.failAllPending("the transaction manager is being closed")` — so suspend should
   fail-all-pending with its own descriptive reason.
3. **WAL retention must be asserted, not assumed.** Suspension pins `lastFinalized` at N−1, which *should* hold
   WAL purge back — recovery depends on those entries surviving. Pin it with a test rather than trusting it.

### Honest operational note

For **transient** failures (a momentary disk-full), today's behaviour is silent self-recovery — with the
corruption risk documented in the verdict §2.1. Suspend converts those into **operator incidents**. That is the
right trade for a database, but it must be said out loud so the first production suspend is not misread as a
regression. The shadow-swap future option below is the correct long-term answer to it.

## Unchanged by this decision

- **Warm-up poison (verdict §1.2) still applies.** Bulk load has no readers to protect, and continuing after a
  failed warm-up flush *already* loses data silently (`popTrappedUpdates` is destructive and advances every
  baseline before the write). Independent standing defect; fix regardless.
- **Option B** (collapse reclaim from `pendingLivePageSequences()`) — adopt, closes L2 ⇒ L3 dissolves.
- **Option G strong form** in `PageStreamRegistry.collectChangedPages` — adopt; would have made this whole class
  of bug a loud flush-time failure.
- **`discardStaged()` → DELETE.** Same conclusion as the fatality route, for the same reason: after a failed
  flush there is no next flush to consume the staged set, and recovery rebuilds the registry from disk. Resuming
  transaction processing *without* a reload is not feasible anyway (the popped storage parts are already gone —
  verdict §1.2), so the abort-half is not needed.
- **Q6 repair tool** — still needed; catalogs corrupted pre-fix will keep failing boot.

## Future option (not now)

Zero-outage recovery: background shadow-load of the catalog from disk + WAL, then an atomic swap — readers stay on
the old instance until the new one is ready. Turns "reload" into a hot-swap with no reader impact. Only worth
building if suspend-and-alert proves too blunt in practice.

## Consequence for the pilot's javadoc (7 clones + 1 stray)

The javadoc currently asserts *"a flush failure is fatal"* — false today, and it will stay false under this
decision (a flush failure is fatal **to the catalog's write path**, not to the process). It must be reworded to
the guarantee actually enforced: **a failed flush suspends further flushes of that catalog (warm-up: poisons the
collection), so no later flush ever diffs against the baselines it left behind.** Suspend and poison are the same
invariant in two dresses — worth saying so in the text.

Sites: the 7 `publishPreviousFlush()` javadocs, **plus** the merge-side comment the rollout copied family-wide
(`InvertedIndex.java` `createCopyWithMergedTransactionalMemory`: *"a flush failure is fatal — restart rebuilds a
clean registry"*).

## Test-matrix deltas (vs. the verdict §5)

- **T3** GREEN condition becomes *"catalog **suspends**, readers keep serving, no corruption after an operator
  reload"* — not "catalog dies". Still must be observed RED with the pilot and passing pre-pilot.
- **T9 (new, Gap 1)** — close-flush gating, both dresses: a **poisoned warm-up collection** → `terminate()` →
  assert **no bytes written** (this is the real trap); and an ALIVE **suspended** catalog → shutdown → assert no
  collect ran (a guard-rail pinning the `WARMING_UP` assert that makes it structurally impossible today).
- **T10 (new, Gap 3.2)** — visibility-stage commit futures under suspend complete **exceptionally in bounded
  time**, never hang.
- **T11 (new, Gap 3.3)** — WAL purge is held back while suspended (`lastFinalized` pinned at N−1).
- **T12 (new, Gap 3.1)** — a pre-collect failure (e.g. `RejectedExecutionException`) still retries and does **not**
  suspend.

---

## Advisory reply (2026-07-17) — SIGNED OFF, with the gate placement corrected

**The decision is approved from the advisory side and supersedes the verdict's §2.3 recommendation.** The
reduction is correct: seam A needs *"no next flush after a failed flush"*, and suspend enforces it as completely
as fatality did, at a cost profile that matches the stated priority (readers first). Every acceptance above
(two loci, Gap 3 in full, T9–T12) matches the audit. Point-by-point on the contested items:

### Gap 1 / ALIVE — retraction confirmed, mis-attribution owned

Re-verified in code: the no-arg collect path asserts `WARMING_UP`
(`core/catalog/Catalog.java:1796-1799` — note the file moved under `core/catalog/`), and `terminateInternally`
flushes collections **only** under `warmingUpState` (`:2460-2473`). An ALIVE graceful shutdown cannot collect;
the T9 guard-rail pinning that assert is the right residue. The advisory's shutdown-flush premise came from a
mis-read of the round-4 replay notes (the v333 bootstrap record was the replay's own `storeHeader`) — the
correction is accepted as written. The WARM_UP half of the trap stands exactly as the document now states.

### One refinement that changes WHERE the gate goes (verified, please fold into implementation)

The claim *"for an ALIVE catalog the only route to `popTrappedUpdates` is trunk incorporation
`Catalog.flush(catalogVersion, lastProcessedTransaction)`"* is right about the umbrella but incomplete about the
routes under it. The **schema-op branches collect too**: `removeEntitySchema` calls
`collectionToRemove.flush(catalogVersion)` (`:2263`) / `collectionToRemove.flush()` (`:2266`), and the replace
path calls `entityCollectionToBeReplacedWith.flush(...)` / `otherCollection.flush(...)` (`:2401`, `:2424`) —
each landing on a trapped-changes pop (`EntityCollection.java:1890`, `:1621/:1639`). In ALIVE these execute
*while incorporating WAL schema mutations*, i.e. inside trunk incorporation but **not** through the catalog-wide
flush overload. Consequences:

1. **The suspend gate must sit at the WAL-drain / `processTransactions` entry** (covering everything trunk
   incorporation can reach), **not** inside the `Catalog.flush(long, TransactionMutation)` overload — a gate
   placed only there would miss the schema-op collect sites.
2. The drain-entry gate must also swallow **new** drain triggers (a freshly appended WAL tx schedules
   `walDrainingTask` independently of the retry path) — rejection at commit-acceptance alone does not stop
   txs already in flight between stages from waking the drain.
3. The Gap 3.1 *"collect began"* latch is **catalog-level**, not per-collection: the catalog-wide flush maps over
   all collections, and the baselines are poisoned from the first collection popped.
4. Cheap defence in depth per the unreachable-states-must-throw doctrine: an `Assert.isPremiseValid(!suspended)`
   inside the flush overload (and the warm-up poison check at the `WarmUpDataStoreMemoryBuffer.popTrappedChanges`
   choke point, which covers all four listed warm-up paths *plus* the warm-up schema-op flushes at `:2263-2266`
   in one place).

### Two small additions

- **Alert content for the post-durability locus**: include the version pair (disk at N, serving N−1) in the
  suspend state/ERROR — it is the single fact the operator needs to pick between "reload" and "restore/repair".
- **T12 gains a twin**: a schema-op collect failure during ALIVE trunk incorporation (e.g. injected failure in
  `EntityCollection.flush(catalogVersion)` at `:2263`) must suspend exactly like a catalog-wide flush failure —
  this pins refinement (1) so the gate can never quietly migrate into the flush overload.

---

## Owner-side check of the advisory reply (2026-07-17) — refinement premise REFUTED, conclusion ADOPTED

The reply's refinement was re-verified against the code before implementation. **Its premise does not hold; its
conclusion does, for a different reason. Both halves matter, so both are recorded.**

### REFUTED: the schema-op branches do NOT collect in ALIVE

Both cited sites are gated on the **absence** of a transaction, and the ambient `transaction` is
`Transaction.getTransaction()` (`Catalog.java:871-875`):

- `removeEntitySchema` → `if (transaction == null && collectionToRemove != null)` (`:2258`) wraps *both*
  `collectionToRemove.flush(catalogVersion)` (`:2263`) and `collectionToRemove.flush()` (`:2266`).
- the replace path → `if (!transactionOpen)` (`:2398`) wraps `entityCollectionToBeReplacedWith.flush()` (`:2401`)
  and `otherCollection.flush()` (`:2424`) — and additionally asserts `catalogVersion == 0L` ("Catalog version is
  expected to be `0`!"), which independently proves it is warm-up-only (an ALIVE catalog is version ≥ 1; it would
  throw, not silently collect).

And trunk incorporation **replays inside a transaction**: `processTransactions` does
`lastTransaction = createTransaction(...)` (`TransactionManager.java:1249`) then `replayMutationsOnCatalog(...)`,
whose body is `Transaction.executeInTransactionIfProvided(transaction, …)` (`:1648`). So while ALIVE incorporation
applies a WAL **schema** mutation, `transaction != null` ⇒ both branches are skipped.

⇒ **"For an ALIVE catalog the only route to `popTrappedUpdates` is the trunk-incorporation flush overload" stands.**
Consequently the reply's **T12 twin is unwritable** (a schema-op collect failure during ALIVE incorporation cannot
occur) — re-scope it to pin the `transaction == null` gate itself, or drop it.

### ADOPTED anyway: gate at the WAL-drain entry, for the reply's *other* reasons

The recommended placement is still correct, on grounds independent of the refuted premise:

- **New drain triggers.** A freshly appended WAL transaction schedules `walDrainingTask` independently of the
  retry path, so the gate must prevent the drain from **starting** — rejecting at commit-acceptance alone is not
  enough. This is the real argument for the drain-entry placement.
- **The latch is catalog-level, not per-collection** (the catalog-wide flush maps over all collections; baselines
  are poisoned from the first collection popped).
- **Defence in depth** (unreachable-states-must-throw): `Assert.isPremiseValid(!suspended)` inside the flush
  overload, so the gate cannot quietly migrate.

### ADOPTED and IMPROVED: the warm-up choke point (the reply found paths this document missed)

The same sites the ALIVE analysis rules out (`:2263`, `:2266`, `:2401`, `:2424`) **are genuine WARM_UP collect
paths** — this document's earlier list of four was incomplete. The reply's suggestion supersedes the enumeration:
**put the poison check at `WarmUpDataStoreMemoryBuffer.popTrappedChanges`**, the single choke point through which
*every* warm-up collect passes (session close, lifecycle `:2236`/`:2286`/`:2430`, goLive, terminate `:2464`, and
the schema-op flushes above). One check, no path list to keep in sync.

### Also adopted

- **Alert content**: include the version pair (disk at N, serving N−1) in the suspend state/ERROR — the single
  fact that tells an operator whether to reload or restore/repair.
