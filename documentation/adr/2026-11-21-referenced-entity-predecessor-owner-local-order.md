---
title: A referenced-entity predecessor orders only its owner's references from the owner's rows and is never indexed
date: 2026-11-21
updated: 2026-11-21 17:51
status: accepted
kind: fix
issues: [1691]
prs: []
areas:
  - evita_engine/src/main/java/io/evitadb/core/query/sort/attribute/comparator
  - evita_engine/src/main/java/io/evitadb/core/query/sort/attribute/translator
  - evita_engine/src/main/java/io/evitadb/core/query/sort/reference/translator
  - evita_engine/src/main/java/io/evitadb/index/mutation/local
  - evita_api/src/main/java/io/evitadb/api/requestResponse
  - evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure
  - evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog
  - documentation/user/en/query/ordering
supersedes: []
superseded-by: []
relates:
  - 2026-11-20-reference-owner-scope
  - 2026-10-20-single-owner-partitions-as-registry-rows
  - 2026-09-23-reference-decode-narrowing-by-referenced-key
  - 2026-11-19-one-histogram-store-per-group
---

# A referenced-entity predecessor orders only its owner's references, from the owner's own rows

A `ReferencedEntityPredecessor` attribute is no longer indexed. `referenceContent(name, orderBy(attributeNatural(...)))`
ranks each owner's references by the chain that owner's own rows of the reference form, computed from all its rows
before the reference filter and the page. Ordering the owners themselves by such an attribute is refused at planning,
from the query and the schema alone. The 2026.3 storage upgrade removes the chains a 2026.2 catalog persisted.

## Why

A `ReferencedEntityPredecessor` states, for one owner, the order of the entities that owner references. It was
indexed into the owner-side reduced partitions, which group owners by the referenced entity, under the referenced
entity's key: every owner of target `T` wrote the same element `T` into one chain in `T`'s partition. Three results,
all reproduced: ordering an owner's references by the attribute returned primary-key order; the first owner leaving a
shared partition removed the shared element, so the next archive, delete or reference removal failed with
`Chain index for attribute ... not found!`; a demoted registry row lost the predecessor.

### Previous state

The write path indexed the attribute like a `Predecessor`, with the referenced primary key as the record id
(`ReferenceIndexMutator#executeWithProperPrimaryKey`). Both `referenceContent` ordering and entity-level
`referenceProperty` ordering read those partition chains. Nothing pointed out that one partition chain cannot hold
several owners' different lists.

## Options considered

### Option A — order from the owner's rows, index nothing (chosen)

The comparator builds a transient `ChainIndex` per owner from the owner's unfiltered rows of the reference and ranks
the rows by it; the write path skips the attribute.

- **Pros:** the data is already in the references record the fetch reads - no extra I/O, no index to maintain, no
  shared state between owners; inconsistent chains keep the `ChainIndex` rules.
- **Cons:** ordering owners by the attribute has no index to read and must be refused; the decode narrowing by
  referenced keys must keep every row of a reference ordered this way.

### Option B — one chain per owner inside each partition

- **Pros:** keeps an index for the ordering.
- **Cons:** a partition is keyed by the referenced entity; per-owner chains inside it multiply the chains by owners and
  still serve only the one owner the fetch already holds rows for.
- **Rejected because:** it builds an index for data the fetch already has in hand, at a write and memory cost per owner.
  Worth revisiting only if a query needs to order owners by their lists, which has no meaning today.

### Option C — reference-count the shared element and keep the partition chain

- **Pros:** fixes the `Chain index ... not found!` failures with a small change.
- **Cons:** the ordering stays wrong - one chain per target cannot represent several owners' different orders.
- **Rejected because:** it fixes the crash and leaves the wrong results.

### Owner ordering: refuse by data or by declaration

Refusing only when the partitions actually mix several owners would keep some queries working on some data.
**Rejected because** a query that works must never start failing after upserts: the refusal is decided from the
declared attribute type, before any index lookup, on every route including the empty-collection planning path.

### Legacy chains: leave or remove

Leaving the 2026.2 chains costs memory and disk and keeps an otherwise empty always-materialized partition alive.
**Removed** in the 2026.3 storage upgrade (`Migration_2026_3`), following the grouped-histogram cleanup of
`2026-11-19-one-histogram-store-per-group`; the runtime carries no legacy handling.

## Decision

**Chosen: Option A**, with the data-independent refusal and the migration. It is the only option that gives correct
results for every owner without new index structures. Option B would win only if owner ordering by an owner's own
list became a supported query.

## Key technical details

- `ReferencedEntityPredecessorComparator` implements `ReferenceComparator.OwnerReferencesAwareComparator`.
  `ReferenceComparator#sort` calls `prepareOwner(ownerReferences)` only when the link's scope admits the owner (see
  `2026-11-20-reference-owner-scope`). `canRank` = the row has a rank; a row without one goes to the next ordering,
  finally ascending referenced primary key. Rows to the same target share the target's position.
- Ranks are keyed by the **referenced and internal primary key together**: reflected rows copy the internal key of the
  row they reflect, so one owner can hold several rows with the same internal key.
- A `ReferencedEntityPredecessor` a reflected reference **inherits** still reads the forward `Predecessor` chain
  (`ReferenceOrderByVisitor`), which works in one scope only; over several scopes it must be wrapped in `inScope`.
- `AttributeIndexMutator` returns early for the attribute; `EvitaRequest#getReferenceKeyNarrowing(schema)` exempts a
  reference ordered by it, resolved against the current collection schema (enrichment included).
- The owner-ordering refusal is `ReferencePropertyTranslator#assertChildConstraintSupported`, decided from the
  declared attribute type; it also runs on the planning path without rows (`checkChildConstraintsWithoutRows`), so an
  empty collection refuses the same query. `AttributeNaturalTranslator` picks the owner-local comparator for
  `referenceContent`.
- `VirtualReferencePartitionConversion#removeLegacyReferenceChains` drops the chain entries, roots and leaf pages and
  retires an index left empty by `EntityIndex#isEmpty`; everything publishes with the upgrade's bootstrap record.

## Verification

- `ReferencedEntityPredecessorOrderingFunctionalTest` (26 tests): order across a filtered link and with a page,
  rows without a value handed to the next ordering, enrichment against a newer schema, duplicate rows, reflected
  rows sharing an internal key (`shouldRankReflectedRowsWithSharedInternalPrimaryKey`), ordering inside an open write
  transaction, shared owner lists across mutations and reload, and `shouldValidateOrderingFromSchema` - the owner
  ordering refused and the `referenceContent` ordering accepted alike on empty and populated collections.
- `ReferencedEntityPredecessorArchivingFunctionalTest` (7 tests): archiving and restoring owners whose rows target the
  same entity - the shape that failed with `Chain index ... not found!`.
- `Release2026_2UpgradeDefectsTest#shouldMoveOwnersAndRebuildTheirPartitionAfterUpgrade` and
  `#shouldRetireOwnerlessPartitionAfterUpgrade` on the generated 2026.2.18 image: the chains exist before the upgrade
  and none after, plain and reflected; the order equals a fresh catalog's (`[3, 1]`); the ownerless partition is
  retired. `Migration_2026_3_ReferencedEntityPredecessorCleanupTest` covers a paged chain next to an entity chain.
- Full functional suite on 2026-11-21 after the last code change: 39,999 tests, 0 failures, 0 errors, 70 skipped;
  whole-reactor test compile green.

## Consequences & open follow-ups

- **Breaking:** ordering owners by a `ReferencedEntityPredecessor` through `referenceProperty` (traverse, pick-first)
  is refused, on empty catalogs too. `referenceContent` ordering by it is never refused.
- **Known limit of the migration:** a catalog first written by 2025.6 stored reference chains under unqualified keys;
  where an entity-level `Predecessor` shares the name and locale of a reference `ReferencedEntityPredecessor`, both
  contributions sit in one chain. The migration keeps that chain, because separating them would mean guessing from its
  payload.
- **Suspected, not proven:** a raw schema mutation (not the builder) can mark the attribute filterable or unique; the
  early return now skips those indexes as well.
- Found along the way and filed separately: a sortable compound with a non-`Comparable` element is accepted but every
  write fails (#2049); a nested `inScope` inside `entityProperty` under an owner gate (#2050).
