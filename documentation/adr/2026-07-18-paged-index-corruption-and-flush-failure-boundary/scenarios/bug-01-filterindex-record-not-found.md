# Bug 01 — FilterIndex "Sanity check - record not found!" on bulk PRODUCT re-publish

> **ROOT-CAUSED & REPRODUCED IN ISOLATION (2026-07-14).** The cause is the stale leaf-page twin
> corruption documented in `bug-04-stale-leaf-page-twin.md`: the persisted PAGED `InvertedIndex`
> page list references a stale leaf page alongside its successor, the loader assembles both without
> a cross-page monotonicity check, and B+ descent over the resulting non-monotonic separators routes
> equality probes into a leaf that does not hold the key while a shadowed leaf does — the removal
> sanity check then throws this signature. On the pure prefix-twin geometry reads still succeed
> (why single-op probes passed, §Isolation below); the miss needs DIVERGED twins, i.e. live churn
> after the twin froze — exactly the incident's bulk re-publish.
>
> Distilled failing reproduction (no production dataset needed):
> `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/attribute/StaleLeafPageTwinReproductionTest.java`
> (`shouldSurviveFilterRemovalOverTwinCorruptedTree` — fails with exactly
> `Sanity check - record not found!`).

## Signature
```
io.evitadb.exception.EvitaInvalidUsageException
INVALID_ARGUMENT: <hash>:<hash>:117: Sanity check - record not found!
```
Server throw site: `Assert.isTrue(...)` inside
**`FilterIndex.removeRecordFromHistogramAndValueIndex`** —
`evita_engine/src/main/java/io/evitadb/index/attribute/FilterIndex.java:1398`.

```java
final Serializable normalizedValue = this.normalizer.apply(value);
isTrue(
    this.invertedIndex.getRecordsEqualTo(normalizedValue).contains(recordId),   // <-- false
    "Sanity check - record not found!"
);
```

## Where it fires
A value→record removal (from a `Remove reference` cascade or an attribute-value change) cannot find
`recordId` in the `InvertedIndex` bucket for `normalizedValue`. i.e. the value was indexed under a
key that the live removal path re-derives differently, OR the record was already dropped.

## Evidence — incident client stacktrace (production re-publish job)
```
Failed to finalize entity change EntityChange(idCatalogEntity=18368, entityType=PRODUCT) in catalog
<catalog> due to: INVALID_ARGUMENT: <hash>:<hash>:117: Sanity check - record not found!
io.evitadb.exception.EvitaInvalidUsageException: INVALID_ARGUMENT: ...: Sanity check - record not found!
	at io.evitadb.driver.EvitaClient.transformStatusRuntimeException(EvitaClient.java:312)
	at io.evitadb.driver.EvitaClient.transformException(EvitaClient.java:261)
	at io.evitadb.driver.EvitaClientSession.executeWithBlockingEvitaSessionService(EvitaClientSession.java:2364)
	at io.evitadb.driver.EvitaClientSession.lambda$upsertEntity$67(EvitaClientSession.java:1520)
	at io.evitadb.driver.EvitaClientSession.executeInTransactionIfPossible(EvitaClientSession.java:2835)
	at io.evitadb.driver.EvitaClientSession.upsertEntity(EvitaClientSession.java:1518)
	at com.fg.eshop.evita.publishing.EvitaIncrementalIndexJob.reconcileReferencesToSamePageRemovals(EvitaIncrementalIndexJob.java:800)
```
Failing entities also included PRODUCT `33786` and `33808` (their full mutation payloads are in the
incident report). All three: reference removals + attribute upserts + `assignmentValidity` ref-attr
upserts; `33786`/`33808` additionally reorder `relatedProducts` (remove-all + reinsert with new
negative internal ids). `18368` has **no** reorder, so the culprit is common to all three.

> The server-side throw stack has NOT been captured yet (Bug 01 not reproduced in isolation). It is
> expected to resemble Bug 02's stack but on the **removal** side:
> `... FilterIndex.removeRecordFromHistogramAndValueIndex` ← `FilterIndex.removeRecord(Delta)` ←
> `AttributeIndex.removeFilterAttribute` ← reduced-index removal ← `ReferenceIndexMutator`. Capture it
> per PLAN.md §3.4 once the fuzzer reproduces it.

## Trigger (production catalog, from the incident)
Failing entities: PRODUCT **18368, 33786, 33808** (and more), re-published by
`com.fg.eshop.evita.publishing.EvitaIncrementalIndexJob.reconcileReferencesToSamePageRemovals`. Each
failing entity mutation mixes: reference removals (`stocks`,`tags`,`relatedProducts`,
`bonusVisibilities`,`stockVisibilities`), a `relatedProducts` reorder (remove-all + reinsert with
new internal ids), entity-attribute upserts (`urlInactive`,`changed`,`published`,`relatedFiles`,
`productOrdering`), and reference-attribute upserts (`assignmentValidity`). The full mutation for
18368/33786/33808 is captured verbatim in the incident report (see PLAN.md §1).

## Isolation status — NOT yet reproduced in isolation
Every single-op probe on a warm-up-built value **commits cleanly** (from pristine, one op per fresh
txn): remove `stocks`/`quantityOnStock` (BigDecimal), change `assignmentValidity` (DateTimeRange[]),
change `changed` (OffsetDateTime), change `productOrdering` (String sort), change `name` (String).
⇒ **combination/volume-dependent.** Reproduction requires the full mutation shape or the bulk
(500/txn) fuzzer. See PLAN.md §5 for the fuzz+bisect procedure that must minimize this.

## Root-cause hypotheses (ranked)
1. **Warm-up bulk-load index construction ≠ live remove path** for some attribute/value
   (normalization / scale / NFD / collation). Fires *during apply*, before any rollback ⇒ savepoints
   NOT implicated for the standalone case. (Primary.)
2. **Savepoint rollback damage:** a *prior* entity in the same batch failed → savepoint rollback →
   corrupts the shared reference/attribute `InvertedIndex` → this entity's removal misses. Only the
   skip-on-fail bulk harness can confirm (entity fails in-batch but replays clean alone from
   pristine ⇒ this branch). (Secondary — the user's explicit fear.)

## Fix acceptance
- A minimal test reproduces `record not found` on the same code path, and fails before the fix.
- After the fix, replaying the captured failing op-log against the production catalog **from pristine** commits clean
  (no `record not found`), and the fuzzer no longer surfaces this signature.

## JDWP capture recipe (fill in once reproduced)
At the throw (exception BP on `EvitaInvalidUsageException`), walk to the `FilterIndex` frame and
record: `value`, `recordId`, `normalizedValue`; whether `recordId` is present under the RAW value vs
`normalizedValue`; the attribute name + reference (from the caller frame); the entity scope/locale.
See PLAN.md §3.4.
