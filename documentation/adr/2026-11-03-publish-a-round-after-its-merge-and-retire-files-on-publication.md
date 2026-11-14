---
title: A round is published only after its merge, and a file is retired only once a record that no longer names it is published
date: 2026-11-03
updated: 2026-11-03 17:10
status: accepted
kind: fix
issues: [1709]
prs: [1710]
areas: [evita_engine/core/catalog, evita_engine/core/transaction, evita_store/evita_store_server/catalog]
supersedes: []
superseded-by: []
relates: []
---

# A round is published only after its merge, and a file is retired only once a record that no longer names it is published

Eight defects left a catalog that no longer loads (issue #1709). They had two roots, and both are fixed here: the
bootstrap record of a trunk round was published *before* the round's merge had finished its work, and a data file was
handed to the obsolete-file maintainer when the round that superseded it was *built*, not when it was *published*.
A third change, smaller but required by the first two, makes the merge recognise collections by primary key instead of
by name.

## Why

`TransactionTrunkFinalizer#commitCatalogChanges` used to flush the catalog (collection data, catalog header, bootstrap
record) and then run the merge. The merge is where a renamed collection's data is copied into a new file, where the
file of a dropped collection is retired and where the collection headers are rewritten. A record published between the
two named the state from before the merge while claiming the round, and the write-ahead log, told the round was
processed, never repaired it. A rename or a drop that was the last transaction before a shutdown therefore reloaded the
pre-rename header over a retired file.

The same ordering made retirement unsafe: a file was parked for deletion at the moment a round was built, and with a
deferred checkpoint the record that stops naming it is written much later.

### Previous state

Only warm-up retirements were parked until the superseding record was written (`05075879cd`). In `ALIVE` the maintainer
deleted a file as soon as the last reader of the previous version left, which with `checkpointIntervalInMillis` > 0 is
before any published record stops naming it. The merge matched collections by the *removed* names and by object
identity of the collection, so a swap, a chain or "rename `A`, create `A`" slipped through; rename and delete retired
at `V - 1` after a compaction retired at `V`, which the maintainer's ordered list refuses.

## Options considered

### Option A — publish after the merge, park every retirement, match by primary key (chosen)

`Catalog#flushCollections` writes the data of the round; the merge runs; `Catalog#publishHeader` stores the catalog
header and the bootstrap record from the merged catalog's collections.

- **Pros:** one place decides what a record names; the record is built from the final state; retirements need no
  special case for a rename or a drop.
- **Cons:** the merge now runs against an offset index whose catalog header of the round is not yet written, so it
  writes the collection headers itself (`DefaultCatalogPersistenceService#updateEntityCollectionHeaders`, at the round's
  version) from the headers the flush returned.

### Option B — run the rename, drop and header work before the flush (declined)

- **Pros:** the existing flush-then-publish order stays.
- **Cons:** the copy of a renamed collection snapshots the live offset index, so the data of the round must be flushed
  into the old file *before* the copy. Moving the copy ahead of the flush loses the round's changes in the new file.
- **Rejected because:** it breaks the reason the flush comes first. It would be viable only if the copy were able to
  include unflushed buffers, which the offset index does not support.

### Option C — keep the order and make the merge rewrite and re-publish (declined)

- **Pros:** the smallest diff.
- **Cons:** two bootstrap records per round, the first of which is exactly the one that is wrong; a crash between the
  two leaves it published.
- **Rejected because:** it does not remove the wrong record, it only narrows the window.

## Decision

**Chosen: Option A.** The invariant in `.claude/rules/durability-model.md` — nothing is durable until the bootstrap
record publishes it, and no file the published record reaches is deleted before the record superseding it is
published — is only enforceable if the record is built from the final state of the round. Option A is the only one
that makes that true without a second record.

## Key technical details

- `Catalog#flushCollections` → merge (`Catalog#createCopyWithMergedTransactionalMemory`) → `Catalog#publishHeader`, in
  `TransactionTrunkFinalizer#commitCatalogChanges`. The flush hands the headers it produced to the merge through
  `Catalog#flushedHeadersByPrimaryKey`; they alone know about a compaction.
- `DefaultCatalogPersistenceService#retireDataFile` parks every retirement tagged with the round's version.
  `#writeCatalogBootstrap` releases those at or below the version of the record it wrote. A checkpoint published from
  the ticker while a newer round is already parked does not release the newer round's files.
- Rename and delete retire with *last version that may read the file* `V - 1`, compaction with `V`. The release clamps
  the handed-over version to be non-decreasing, because `ObsoleteFileMaintainer` keeps its list ordered and stops
  scanning at the first file it may not purge.
- `ObsoleteFileMaintainer#removeFileWhenNotUsed` schedules a purge pass itself: a file is now handed over after its last
  reader may already have left, and nothing else would trigger the pass.
- `#close()` drops parked retirements only *after* the final checkpoint, so a clean shutdown still reclaims them.
- `#replaceWith` (catalog rename) builds its record on the newest record, written or deferred, and never compacts.
- A collection's file reference in `#replaceCollectionWith` is derived from the collection's own primary key.
  `#needsCollectionHeaderWrite` also compares the primary key.
- Counter-intuitive: with a checkpoint that never elapses, a dropped collection's file stays on disk until the engine
  closes. That is the model working, not a leak; `MixedRoundRetirementOrderTest` checks it after the restart for that
  checkpoint kind.

## Verification

Six red test classes from PR #1710 (all eight defects) are green: `DeferredCheckpointCompactionCrashRecoveryTest`,
`CatalogRelabelAfterDeferredCompactionTest`, `EntityCollectionRelabelSurvivesImmediateRestartTest`,
`MixedRoundRetirementOrderTest`, `CollectionCreationCompactionRoundTest`, `CollectionRelabelChainRoundTest`. On
`dev` at `fe15dbdcb0` before the change, 16 of 56 counted cells were red; after it, none of the 72 counted cells is.
The full `unitAndFunctional` module (32,956 tests) ran with two failures, both in `CatalogGraphQLDataSubscriptionsFunctionalTest` (an Armeria connection error and a two-minute wait); they do not touch the storage paths changed here.

## Consequences & open follow-ups

- `release_2026-2` has the same shape for most of the defects and lacks `05075879cd`. A backport has to carry this
  record's changes together with that fix.
- A collection that is compacted and renamed in the same round: `#replaceCollectionWith` reads the replaced file index from the previous round's header, so the compaction's target file is left as unreferenced dead space with a service registered until the catalog closes. Read from the code, never reproduced; no published record is damaged by it.
- The merge now writes the catalog header once and `#storeHeader` writes it again, so a round that changes data stores one extra catalog header record. Not measured.
- The suspected defects D45 and D47–D51 listed in a comment on #1709 are untouched; none was reproduced.
- `DEFERRED`-checkpoint cells of `MixedRoundRetirementOrderTest` no longer require the dropped file to disappear while
  the engine runs; they require it after the restart.

## Timeline

- **2026-10-06** — defects found while working on #1615, red tests in PR #1710
- **2026-11-03** — fix implemented on top of the tests
