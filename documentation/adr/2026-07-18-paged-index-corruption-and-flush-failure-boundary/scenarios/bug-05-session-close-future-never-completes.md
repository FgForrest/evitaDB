# Bug 05 — Session close future never completes when the warm-up flush throws → Evita.close() hangs forever

> Found 2026-07-14 by the writer-side reproduction harness (see bug-04). Reproduced twice,
> jstack-verified. Same "missed completion path" family as bug-03 (a future nobody completes),
> but on the session-close path instead of the transaction pipeline.

## Signature
`Evita.close()` (or catalog suspension) never returns. No exception anywhere — the JVM just hangs
(observed 11+ minutes before being killed). Root exception that triggered it is only visible in the
flush thread's stack at hang time, never propagated.

## Mechanism
1. A WARM_UP session's close-time flush throws a `RuntimeException` — first observed:
   `ArrayIndexOutOfBoundsException` at `SortIndex.getSortedRecordValues:606` via
   `OwnerSortIndex.storagePartSortedValues` during `popTrappedUpdates`, reached from
   `EntityCollection.createFlushFuture:1621` (the index was previously corrupted by the bug-04
   concurrent-session race; ANY flush-time throw works).
2. The session's close future is then NEVER completed — not normally, **not exceptionally**.
3. `SessionRegistry.closeAllActiveSessionsAndSuspend` (`SessionRegistry.java:213-226`) does
   `CompletableFuture.allOf(...).join()`; its `.exceptionally(ex -> null)` guard only helps futures
   that COMPLETE exceptionally — it cannot help a future that never completes at all.
4. `Evita.closeInternal` (`Evita.java:1846-1854`) waits on that → hangs forever.

## Reproduction
`StaleLeafPageTwinWriterReproductionTest` (evita_functional_tests,
`.../api/functional/storage/`): the two concurrency tests
(`shouldSurviveConcurrentUpsertsOnSingleWarmUpSession`,
`shouldSurviveSplitAimedOverlappingUpsertsOnSingleWarmUpSession`) corrupt the index, and the
tearDown then hits this hang — bounded in the harness by `@Timeout(..., SEPARATE_THREAD)` on
tearDown, visible in the surefire report as
`Suppressed: TimeoutException: tearDown() timed out after 90 seconds`.

## Fix acceptance
- Any exception thrown from the close-time flush completes the session close future exceptionally
  (and surfaces the original exception), so `closeAllActiveSessionsAndSuspend`/`Evita.close()`
  terminate in bounded time.
- The two harness tests' tearDown no longer needs the timeout workaround to terminate.

## Relation to other bugs
- Triggered here BY bug-04's corruption, but is an independent defect: any flush-time throw during
  session close produces an unbounded hang instead of a failed close.
- Family resemblance to bug-03: both are "a future/record nobody completes on the failure path";
  fixing one does not fix the other (different components).
