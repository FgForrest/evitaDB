# Bug 02 — Sort value-tree "Key is already present" over a twin-corrupted shared tree

**Status: ROOT-CAUSED (revised 2026-07-14) & distilled to a deterministic unit reproduction.
Savepoints exonerated for THIS failure (it fires on a freshly loaded pristine catalog).**

> **REVISION (2026-07-14): the earlier "OffsetDateTime→Instant offset-collapse" root-cause narrative
> below is WRONG.** The bucket cursor (`SortIndex.InvertedIndexValueCursor.next()` →
> `bucket.getValue()`) performs NO normalization, and the shared tree's bucket keys already ARE
> `Instant`s. Live JDWP re-capture proved the real cause: the loaded shared `InvertedIndex` contains
> a **duplicated 128-bucket run** — a stale persisted leaf page (seq 29) alongside its superseding
> page (seq 30, identical 128-bucket prefix + 62 more keys). `getValueTree()` walks the buckets in
> physical order and the second copy of a twin key collides in the `CumulativeWeightBPlusTree`.
> **See `bug-04-stale-leaf-page-twin.md` for the root corruption and full evidence.**
>
> Distilled failing reproduction (no senesi needed):
> `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/attribute/StaleLeafPageTwinReproductionTest.java`
> (`shouldSurviveSortIndexMaintenanceOverTwinCorruptedTree` — fails with this bug's exact signature).

## Signature
```
io.evitadb.exception.GenericEvitaInternalError
INTERNAL: <hash>:<hash>:84: Key is already present in the tree!
```
Assertion throw site: `CumulativeWeightBPlusTree.insert`
(`evita_common/src/main/java/io/evitadb/dataType/bPlusTree/CumulativeWeightBPlusTree.java:185-188`):
```java
final int pos = leafInsertionIndex(leaf, key);
Assert.isPremiseValid(
    pos >= leaf.count || compare(leaf.keys[pos], key) != 0,   // <-- fails: key already present
    "Key is already present in the tree!"
);
```

## Client-side stacktrace (EvitaClient)
```
io.evitadb.exception.GenericEvitaInternalError: INTERNAL: <hash>:<hash>:84: Key is already present in the tree!
	at io.evitadb.driver.EvitaClient.transformStatusRuntimeException(...)
	at io.evitadb.driver.EvitaClientSession.upsertEntity(...)
Caused by: io.grpc.StatusRuntimeException: INTERNAL: ...: Key is already present in the tree!
```

## Full SERVER stacktrace (captured live via JDWP at the throw)
```
#0  io.evitadb.utils.Assert.isPremiseValid                                     (Assert.java:90)
#1  io.evitadb.dataType.bPlusTree.CumulativeWeightBPlusTree.insert             (CumulativeWeightBPlusTree.java:185)
#2  io.evitadb.index.attribute.SortIndexChanges.getValueTree                   (SortIndexChanges.java:320)
#3  io.evitadb.index.attribute.SortIndexChanges.prepare                        (SortIndexChanges.java:281)
#4  io.evitadb.index.attribute.SortIndex.removeRecordInternal                  (SortIndex.java:1173)
#5  io.evitadb.index.attribute.SortIndex.removeRecord                          (SortIndex.java:578)
#6  io.evitadb.index.attribute.AttributeIndex.removeSortAttribute              (AttributeIndex.java:1049)
#7  io.evitadb.index.EntityIndex.removeSortAttribute                           (EntityIndex.java:631)
#8  io.evitadb.index.AbstractReducedEntityIndex.delegateRemoveSortAttribute    (AbstractReducedEntityIndex.java:420)
#9  io.evitadb.index.ReducedEntityIndex.removeSortAttribute                    (ReducedEntityIndex.java:341)
#10 io.evitadb.index.EntityIndex.removeAttribute                               (EntityIndex.java:756)
#11 io.evitadb.index.mutation.local.AttributeIndexMutator.executeAttributeRemoval        (AttributeIndexMutator.java:322)
#12 io.evitadb.index.mutation.local.ReferenceIndexMutator.lambda$removeAllEntityLevelAttributes$20  (ReferenceIndexMutator.java:2689)
...  (stream forEach plumbing) ...
#28 io.evitadb.index.mutation.local.ReferenceIndexMutator.removeAllEntityLevelAttributes  (ReferenceIndexMutator.java:2688)
#29 io.evitadb.index.mutation.local.ReferenceIndexMutator.removeAllExistingData           (ReferenceIndexMutator.java:2549)
#30 io.evitadb.index.mutation.local.ReferenceIndexMutator.referenceRemovalPerComponent    (ReferenceIndexMutator.java:979)
#31 io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.updateReferenceOnRemoval (EntityIndexLocalMutationExecutor.java:2839)
#32 io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.updateReferences      (EntityIndexLocalMutationExecutor.java:2557)
#33 io.evitadb.index.mutation.local.handler.ReferenceMutationFanOut.apply                  (ReferenceMutationFanOut.java:71)
#34 io.evitadb.index.mutation.local.handler.RemoveReferenceMutationHandler.apply           (RemoveReferenceMutationHandler.java:58)
#36 io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.dispatchViaRegistry   (EntityIndexLocalMutationExecutor.java:644)
#38 io.evitadb.core.collection.LocalMutationExecutorCollector.execute                      (LocalMutationExecutorCollector.java:316)
#39 io.evitadb.core.collection.EntityCollection.applyMutations                             (EntityCollection.java:2548)
```

## Confirmed root cause — OffsetDateTime→Instant offset-collapse
Live JDWP evidence at the throw (thread `Evita-request-3`, entity `1340209`):

| Probe | Value |
|---|---|
| `CumulativeWeightBPlusTree.insert` `key` | `java.time.Instant` = `2026-07-13T11:52:31.256666037Z` |
| `SortIndexChanges.getValueTree` `value` (from `sortIndex.valueCursor().next()`) | same `Instant` |
| `SortIndexChanges.valueComparator` | `java.util.Comparators$NaturalOrderComparator` |
| removal entry `value` (frame #6, `ReferenceAttributeIndex`) | `java.time.OffsetDateTime` |
| attribute (`AttributeIndexMutator.executeAttributeRemoval` frame) | `attributeName = "published"` (GlobalAttributeSchema, OffsetDateTime, **sortable**) |
| reduced index reference (`referenceSchema`) | `name = "categories"` (referencedEntityType `Category`) |
| shared `InvertedIndex.plainType` (bucket value type) | **`class java.time.OffsetDateTime`** |
| shared `InvertedIndex.comparator` | `NaturalOrderComparator` |
| leaf at failure | `count=32`, `pos=30`, `compare(leaf.keys[30], key)==0` |

**Mechanism:** the shared `InvertedIndex` backing the reduced-index `SortIndex` for `published` stores
**raw `OffsetDateTime`** bucket keys ordered by `NaturalOrderComparator`. `OffsetDateTime.compareTo`
orders by instant **then by local-date-time**, so two `published` values with the **same instant but
different zone offset** are ordered as **distinct** and occupy **two separate buckets**. When
`SortIndexChanges.getValueTree()` (SortIndexChanges.java:312-324) lazily builds the Instant-keyed
`CumulativeWeightBPlusTree` it iterates those buckets and normalizes each to `Instant`
(`OffsetDateTime.toInstant()`); the two distinct buckets **collapse to one `Instant` key** →
`insert` finds the key already present → assertion fails.

This is a **structural** normalization mismatch (raw-OffsetDateTime bucket ordering vs Instant tree
key), independent of the warm-up path — any sort index that holds two same-instant/different-offset
OffsetDateTime values fails on its first `getValueTree()` build.

## Reproduction — deterministic single-entity (from pristine)
`SenesiUpsertFuzzer` isolation mode replays exactly one entity from pristine using per-entity
`(seed,pk)` determinism:
```bash
# from PLAN.md §3: server booted from pristine data_snapshot_pristine
java ... io.evitadb.spike.SenesiUpsertFuzzer localhost 5555 senesi 1 500 1 /tmp/iso.txt 1340209
# -> batch 0: ok=0 perEntityFail=1   FAIL GenericEvitaInternalError: Key is already present in the tree!
```
Op sequence applied to PRODUCT `1340209` (op-log `scenarios/bug-02-oplog.txt`):
```
rmRef(parameterValues:503663);
chgAttr(productType=<new String>);
rmRef(categories:142816);         <-- this removal triggers removeAllEntityLevelAttributes -> the crash
chgRefAttr(groups:1341663/assignmentValidity=[[2000-07-24T00:00:00Z,2011-07-24T00:00:00Z]]);
```
The trigger is **removing reference `categories:142816`** (any reference whose reduced index holds two
same-instant/different-offset `published` values will do). It reproduces **alone in a fresh
transaction**, before any rollback — **savepoints are not involved.**

## Synthetic-minimal repro (for the TDD test)
Schema: one entity `T` with a **sortable** `OffsetDateTime` attribute `ts`, one indexed reference `r`.
Data: two `T` entities, both referencing the same `r` target, with `ts` values equal in instant but
different offset, e.g. `2020-01-01T12:00:00+02:00` and `2020-01-01T11:00:00+01:00` (both instant
`2020-01-01T10:00:00Z`). Then remove reference `r` from one entity (or upsert so a reduced-index
`getValueTree()` builds). Expect: `Key is already present in the tree!` pre-fix. The unit test can
target `SortIndexChanges.getValueTree()` / `CumulativeWeightBPlusTree.insert` directly.

## Fix acceptance
- Pre-fix: the synthetic test throws `Key is already present in the tree!` on the sort value-tree build.
- Post-fix: two same-instant/different-offset OffsetDateTime values coexist in one sort index; the
  value-tree build merges them (single Instant key, weight 2) or the buckets are keyed consistently
  (normalize OffsetDateTime→Instant at bucket-key level too). `SenesiUpsertFuzzer` from pristine no
  longer surfaces this signature.
- Candidate fix areas: `InvertedIndex`/`SortIndex` normalization consistency (store/compare buckets in
  the SAME normalized space the value-tree keys use), or `getValueTree()` merging equal-normalized
  values into one weighted key instead of asserting uniqueness.
