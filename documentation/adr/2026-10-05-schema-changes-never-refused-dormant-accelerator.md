---
title: A schema change is never refused because the collection holds data; a filter accelerator declared over stored values stays dormant
date: 2026-10-05
updated: 2026-10-05 11:30
status: accepted
kind: fix
issues: [258, 409]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/core/collection, evita_engine/src/main/java/io/evitadb/core/catalog, evita_engine/src/main/java/io/evitadb/index/trigram, evita_engine/src/main/java/io/evitadb/index/invertedIndex, evita_engine/src/main/java/io/evitadb/index/GlobalEntityIndex.java, evita_api/src/main/java/io/evitadb/api/requestResponse/schema]
supersedes: []
superseded-by: []
relates: [2026-08-24-fulltext-search-lucene-vs-inhouse, 2026-09-08-warm-up-invalid-schema-refuses-to-publish]
---

# A schema change is never refused because the collection holds data; a filter accelerator declared over stored values stays dormant

Until now evitaDB refused to declare the substring filter accelerator (`acceleratedFor(SUBSTRING_SEARCH)`) on a
collection that already held entities. That refusal is gone. The rule it is replaced by is general and applies
to every indexing capability, fulltext's `searchable()` included. **A schema change is never refused because
the collection already holds data.** Bringing the indexes in line with a changed schema is the reindexing work
of issue #409. Until #409 exists, the engine accepts the change and leaves what is already indexed in its old
shape. For the substring accelerator, "old shape" means **dormant**:

- An attribute whose shared value tree already holds values gets no trigram index.
- `attributeContains` and `attributeEndsWith` keep scanning that attribute. The results are correct, just not
  accelerated.

The accelerator becomes active in two cases:

- **When the tree empties out.** The next value then starts a fresh tree, and the accelerator attaches to it.
- **At the next catalog load, if the tree carries value ids.** This is the case where an earlier declaration
  was withdrawn and then re-declared. The load derives the index from those ids.

## Why

The refusal blocked a legitimate schema change on every live catalog: declaring an accelerator meant a full
re-import into a new catalog and a catalog replacement. The fulltext schema work (#258, step S8b) was about to
copy the same refusal for `searchable()`.

Johnny ruled the other way while that design was being reviewed (2026-10-05). Index maintenance on a schema
change belongs to #409, and a schema change must be accepted, with the indexed data left in its old shape, as
the behaviour of this version. That makes the trigram refusal a precedent that contradicts the rule, so it had
to go first.

The constraint that made this non-obvious is that **the refusal was load-bearing**. Deleting it alone exposes
three failures it had been shielding. The design question was what to put in its place.

### Previous state

Two pieces enforced the refusal:

- `EntityCollection#verifyNoAcceleratorAddedToNonEmptyCollection` diffed the schema before and after the
  change, and threw `InvalidSchemaMutationException` when an accelerator appeared on a non-empty collection.
- `Catalog#verifyEntitySchemaMutationsApplicable` ran the same check over every collection a global-attribute
  cascade touches, before the first schema was exchanged. It existed only to make that one refusal atomic
  across collections.

Two pieces of the value-id machinery relied on the refusal:

- `InvertedIndex#enableValueIds` refuses to switch ids on for a populated tree. The tree *could* back-fill
  them, but only in memory: no leaf page is marked dirty, so the ids never reach disk, and a reload would hand
  the same values different ids. The refusal guaranteed that every attach met an empty tree.
- `TrigramIndex#rebuildAll` treated "accelerator declared, tree populated, tree carries no ids" as corruption
  and failed the catalog load.

## Options considered

### Option A: dormant accelerator over stored values (chosen)

The schema change is accepted, and `GlobalEntityIndex#obtainTrigramIndex` creates an accelerator only over an
empty tree. Over a populated tree it returns no index: no consumer is attached and no ids are switched on. The
write reaches the tree without a lifecycle sink, and the substring translators scan, because they already plan
the scan whenever the map holds no index.

`TrigramIndex#rebuildAll` treats a populated tree without ids as dormant. It logs a warning naming the attribute
and does not fail the load. A tree that kept its ids through a withdrawal is attached and derived at load,
exactly as before.

`EntityCollection` logs a warning when a schema change adds an accelerator to a non-empty collection, so an
operator learns at the moment of the change that the accelerator does not serve yet.

- **Pros:**
  - Correct by construction: no path builds an index that misses a stored value.
  - No new persistence machinery.
  - No new transaction gate on the write path, because dormancy is decided by tree emptiness alone, the same
    way in warm-up and in a transaction.
- **Cons:**
  - A declaration over a populated tree that has no ids stays dormant until the tree empties out, or until
    #409 rebuilds it. A restart does not end it.
  - The load can no longer tell a dormant tree from one that lost its ids through a defect, so that check
    became a warning.

### Option B: full activation by back-filling ids and rewriting every leaf page (declined)

Outside a transaction (at load, or in warm-up), back-fill the value ids of the populated tree and force every
leaf page of the tree to be re-emitted at the next flush, then derive the index. Inside a transaction, stay
dormant until the next load.

- **Pros:** the accelerator serves after one restart, whatever the history of the tree.
- **Cons:** it needs new "rewrite every live leaf" machinery in the shared value tree's paged persistence. The
  `InvertedIndex#detachValueIdConsumer` javadoc already names that machinery as the missing piece for the
  mirror-image drop. A defect there damages the catalog's ability to open; the loader refuses a page set
  where only some pages carry the id column.
- **Rejected because:** it is reindexing machinery, which is #409's scope, and it sits in the riskiest storage
  area for a performance gain, not a correctness gain. Revisit when #409 builds a structure rebuild that
  rewrites an index's pages anyway; activating a dormant accelerator is then one more caller of it.

### Option C: delete the refusal and nothing else (declined)

- **Pros:** a two-method deletion.
- **Cons:**
  - In a transaction, the first write after the declaration attaches the consumer to a populated tree. The
    tree's back-fill refuses to run inside a transaction, so the write fails with an internal error.
  - In warm-up, the same attach fails on `enableValueIds`' "only while the tree is still empty" premise.
  - Over a tree that kept its ids (withdraw, then re-declare), the attach succeeds but the new index posts only
    values born afterwards. Substring queries then silently return fewer entities than they should.
  - The next catalog load fails in `rebuildAll`.
- **Rejected because:** it converts a clean schema-time refusal into write failures, silent under-reporting and
  a catalog that no longer opens.

### Option D: keep the refusal (declined)

- **Pros:** the accelerator, once declared, always serves.
- **Cons:**
  - It blocks schema evolution on live catalogs; the only route left is a full re-import into a new catalog.
  - It would have to be copied for every future indexing capability, `searchable()` first.
- **Rejected because:** it contradicts the rule that index maintenance on a schema change is #409's job and is
  never solved by refusing the change. Nothing about the trigram index makes it an exception.

### Derive the index at the first warm-up write (declined, a variant of A)

When the tree already carries ids and no transaction is open, the write path could derive the index at once
instead of waiting for the next load.

- **Rejected because:** it needs a branch on `Transaction.isTransactionAvailable()` in `GlobalEntityIndex`,
  because accumulating a tree inside a transaction has not been shown to see the transaction's own layers.
  Such a branch is a new entry in `WarmUpRollbackConformanceTest`'s approved-gates list, for a speed-up that
  dormancy until the next load provides correctly anyway.

## Decision

**Chosen: Option A.** It is the only option in which no code path can build an index that misses a stored
value, and it needs no change to how anything is persisted. The price is that an accelerator declared over
stored values stays dormant longer than strictly necessary. That is the "leave the data in its old shape"
behaviour the rule accepts, and #409 is where it ends. Option B wins once #409 provides a page-rewriting
rebuild.

## Key technical details

- `GlobalEntityIndex#obtainTrigramIndex`: returns `null` (dormant) when the attribute's shared value tree
  exists and is not empty. Its callers pass the `null` straight through as the lifecycle sink.
- `TrigramIndex#rebuildAll`: a populated tree whose `carriesValueIds()` is false is skipped with a warning
  instead of failing the load.
- `EntityCollection#warnAboutAcceleratorsAddedOverStoredEntities`: replaces the refusal with a log warning. It
  consults `isEmpty()` only when an accelerator was actually added.
- `Catalog#verifyEntitySchemaMutationsApplicable` and `EntityCollection#verifySchemaMutationsApplicable` are
  deleted. They existed only to make the refusal atomic across a global-attribute cascade.
- Refusals unrelated to data stay:
  - an accelerator on a reference attribute (`AbstractAttributeSchemaMutation#verifyAcceleratorNotOnReferenceAttribute`);
  - an accelerator on an attribute with no filter index (`AttributeSchemaContract#validate`).
- **Invariant:** the value-id column is still switched on only for an empty tree. Every attach on a populated
  tree is either skipped as dormant (write path, and load without ids) or meets a tree that already carries
  ids (load).
- **Will look like a bug:** an attribute whose schema declares `SUBSTRING_SEARCH` while
  `GlobalEntityIndex#getTrigramIndex` returns `null` for it. That is dormancy, not a lost index; the load
  warning and the schema-change warning both say so.

## Verification

- `AttributeFilterAcceleratorRefusalTest`: it keeps its name because the 2026-09-08 record cites its methods.
  The populated-collection cases are inverted from "refused" to "accepted":
  - warm-up and live: the declaration is accepted, the next write succeeds, the accelerator is dormant, and
    `attributeContains` finds the values written both before and after the declaration;
  - reopening the catalog over the dormant state succeeds;
  - a declaration in the same transaction as an upsert is accepted;
  - a global-attribute cascade over a populated collection is applied to every collection;
  - a duplicating rename is accepted;
  - withdraw, write, re-declare: dormant, and after a reopen the index is derived and posts all three values.
- `TrigramIndexMaintenanceTest$Dormancy` covers four cases:
  - a populated tree without ids;
  - a tree that kept its ids;
  - dormancy ending when the tree empties;
  - dormancy inside a committed transaction.
- `TrigramIndexMaintenanceTest$Reload#shouldLeaveAPopulatedTreeWithoutIdsDormant` replaces the test that
  asserted the load failure.

## Consequences & open follow-ups

- **Fulltext follows the same rule.** `searchable()` (#258, S8b) is never refused. Withdrawing it retires the
  field id lazily, so a re-declaration starts empty; its results can be incomplete, but never phantom.
- **A dormant accelerator over a tree without ids stays dormant across restarts**, until the tree empties or
  #409 rebuilds it. The operator is told twice: at the schema change, and at every load.
- **The load-time corruption check for lost value ids is gone.** A tree that lost its ids through a defect now
  degrades to a scan with a warning, where it used to fail the load. The answers stay correct either way.
- **The residual id column after a withdrawal is now useful.** It is what lets the next load re-derive a
  re-declared accelerator.

## Related work

- [2026-08-24-fulltext-search-lucene-vs-inhouse](2026-08-24-fulltext-search-lucene-vs-inhouse/README.md):
  - its 2026-08-25 value-id row justified "attach only to an empty tree" by the refusal; the invariant still
    holds, now through dormancy;
  - its consequences named the refusal as the worked example for `searchable()`;
  - it has a 2026-10-05 row pointing here.
- [2026-09-08-warm-up-invalid-schema-refuses-to-publish](2026-09-08-warm-up-invalid-schema-refuses-to-publish.md)
  lists the two deleted preflight methods among the refusals that keep refusing cleanly before the exchange.
  They no longer exist; its reasoning about the remaining refusals is unaffected.

## Timeline

- **2026-09-02**: the refusal shipped with the substring accelerator (#1454, PR #1483).
- **2026-10-05**: during the S8b schema design review, Johnny ruled that schema changes are never refused for
  stored data; the refusal was replaced by dormancy.
