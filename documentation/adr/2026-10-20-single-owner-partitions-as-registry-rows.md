---
title: A single-owner partition of a filtering-only reference is a row of a per-reference registry, and an index object exists only while a partition has two or more owners
date: 2026-10-20
updated: 2026-10-20 12:51
status: accepted
kind: optimization
issues: [1615]
prs: [1738]
areas:
  - evita_engine/src/main/java/io/evitadb/index
  - evita_engine/src/main/java/io/evitadb/index/attribute
  - evita_engine/src/main/java/io/evitadb/index/invertedIndex
  - evita_engine/src/main/java/io/evitadb/index/mutation/local
  - evita_engine/src/main/java/io/evitadb/index/mutation/storagePart
  - evita_engine/src/main/java/io/evitadb/index/component/loader
  - evita_engine/src/main/java/io/evitadb/core/buffer
  - evita_engine/src/main/java/io/evitadb/core/collection
  - evita_engine/src/main/java/io/evitadb/core/query/filter
  - evita_engine/src/main/java/io/evitadb/core/query/indexSelection
  - evita_engine/src/main/java/io/evitadb/core/query/algebra/prefetch
  - evita_engine/src/main/java/io/evitadb/core/query/sort/reference
  - evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence
  - evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog
  - evita_store/evita_store_server/src/main/java/io/evitadb/store/index/serializer
  - evita_api/src/main/java/io/evitadb/api/statistics
  - evita_api/src/main/java/io/evitadb/api/exception
  - evita_external_api/evita_external_api_grpc/shared
  - documentation/developer/indexes
supersedes: []
superseded-by: []
relates: [2026-10-20-reference-content-filter-row-by-row, 2026-09-23-pick-first-reference-ordering-from-selection, 2026-09-17-row-scoped-reference-having-body, 2026-09-18-reference-planning-from-owner-membership, 2026-09-09-sibling-resolver-partition-cardinality, 2026-09-25-attribute-is-null-in-reference-having, 2026-10-08-trapped-storage-part-content-pre-images, 2026-09-21-cardinality-counter-normalized-keys, 2026-10-08-unique-indexes-normalize-like-filter-indexes, 2026-09-04-millisecond-temporal-precision, 2026-08-26-warm-up-per-entity-mutation-atomicity, 2026-08-10-catalog-and-collection-statistics, 2026-10-20-structure-guards-yield-to-javadoc-and-runtime-failures]
---

# A single-owner partition of a filtering-only reference is a registry row, not an index object

A reference indexed `FOR_FILTERING` used to keep one `ReducedEntityIndex` per `(scope, RepresentativeReferenceKey)` -
a *partition* - holding the owners that reference one target. Most of them hold exactly one owner. Such a
**single-owner partition is now a row** of a per-`(reference, scope)` registry, `VirtualReferencePartitionIndex`,
carried by the reference's `ReferencedTypeEntityIndex`: the row holds the partition's owner and target, its
representative values (as an id into a small dictionary), its sort values and its chain predecessors, keyed by the
partition's primary key. A partition gets an index object only while two or more owners share it: the second owner's
arrival **promotes** the row to an object under the same primary key, and a departure that leaves one owner
**demotes** it back. Every query route answers a row without an object.

Storage protocol 7 is the only one this version writes or reads. A catalog written by 2026.2 (protocol 6) is upgraded
once by `Migration_2026_3`, which now carries every 2026.3 storage change in one step from 6 to 7: the earlier re-keys,
a rewrite of the owner sort indexes 2026.2 stored in another form or order, and the conversion of single-owner
partitions into rows, published together. A catalog a development build wrote at an earlier form of protocol 7 is not
told apart and must be recreated. On the production retail corpus
this removes 317,446 index objects and 1,344 MB (19.5 %) of retained heap, answers an unnarrowed `referenceHaving` on
`Product.media` about 48× faster, and the conversion takes 24 s.

## Why

Issue #1615 measured it on a restored production retail catalog (565,534 partitions): **320,019 partitions held
exactly one owner and cost 1.22 GB** of modelled heap, against 6.25 GB of measured catalog retention. `Product.media`
LIVE spent 3,720 bytes per partition to hold one owner's primary key. The heap model had been calibrated against
measured retention first (it covers 0.888 of it, twice), so the figures are worth quoting.

The census taken before the design (see *The partition population* under *Verification*) sharpened it:

- 317,446 in-scope single-owner partitions, **1,185.8 MB**; **only 63 MB (5.3 %) of it is the owner primary key** the
  partition exists to hold.
- 422.9 MB is filter and unique data the type index already holds, keyed by partition primary key.
- **81.1 % sits in references with a sortable attribute, compound or chain** (sort 261.9 MB, chain 47.9 MB), so a
  filter-only first cut would have left most of the memory where it was. `Parameter.parameterValues` is 100 %
  single-owner and chained by `ReferencedEntityPredecessor` - chains could not be deferred.

**The schema cannot express the shape.** A partition is a singleton exactly when one owner references the target - the
parent/child shape. `Cardinality` bounds how many references *an owner* has, never how many owners point at a target,
and on this catalog it is anti-correlated with the shape: every `ZERO_OR_ONE` reference has crowded partitions
(`Product.brand`, mean 345 owners), and every reference with 100 % singletons is `ZERO_OR_MORE` or
`ZERO_OR_MORE_WITH_DUPLICATES`. Gating on the schema would have targeted exactly the wrong references.

**There was a second prize.** A bare `referenceHaving` ORed one bitmap per partition - 99-197 ms on `Product.media`,
plus 46-83 ms in `FormulaDeduplicator` over 169,103 nodes. A row is answered from the type-level structures, all rows of
a reference as one operand.

What made this hard rather than obvious:

- **About 165 sites resolved a partition and expected an object.** The ones on the write path fail silently - a missed
  partition there is a wrong answer with no error.
- **A partition's sort and chain values lived only in its object.** `ReferencedTypeEntityIndex` discarded them.
- **Promotion reads another entity inside a mutation.** The first owner may have been changed earlier in the same root
  mutation, and that state was not reliably visible.
- **The representation must survive** reload, WAL replay, backup, restore, rename, replace and copy - a reload that
  rebuilds every object is correct and silently forfeits the gain.
- **The formula cache must not widen.** Answering from type-level structures makes a cached formula depend on bitmaps
  that change on every write elsewhere in the reference.

### Previous state

Every partition of a `FOR_FILTERING` reference was a full `ReducedEntityIndex`: owner bitmap, per-locale bitmaps,
filter, unique, sort and chain indexes of its reference attributes, and empty facet, hierarchy and price shells.
`ReferencedTypeEntityIndex` held the reference's filter and unique data keyed by partition primary key for discovery,
but no sort or chain data. `ReducedIndexMembership` mapped owners to partitions (covered entries plus a residual set).
Every consumer - query translators, sorters, fetchers, the facet and histogram re-evaluation, the write path - resolved
a partition by primary key or by key and got the object or nothing. That was acceptable while nobody had measured what
one object costs against the one integer it holds.

## Decisions taken

The issue left the design open ("a direction, not a specification"). Two independently written designs agreed on most
of it and split on three forks, which the maintainer decided on 2026-09-30; the rest followed phase by phase, each plan
vetted by a scope-fenced Codex review (at most two rounds) before any code. The cleanup of 2026-10-11/12 removed the
development scaffolding and settled the migration.

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-09-30 | **Fork 1:** sort and chain values of single-owner partitions live in the registry, ordered by one in-memory `OwnerSortIndex` per sort key whose record ids are partition primary keys | page-bounded on the dominant shape; no object per partition | Fork 1 |
| 2026-09-30 | **Fork 2:** no raw copy of a row's values; promotion replays the first owner's stored row, demotion extracts the survivor's values from the object | drops derivable data; transitions are rare | Fork 2 |
| 2026-09-30 | **Fork 3:** a row stays an ordinary covered entry of `ReducedIndexMembership`; the registry's owner slot is the single authority | one discovery structure; a candidate is validated by one lookup | Fork 3 |
| 2026-09-30 | Demotion is decided on the **net** owner count at the end of the entity mutation; a drop reserves its primary key for the root mutation; only promotion is eager | every observed churn is a remove + re-insert inside one version | Transitions |
| 2026-10-01 | Sort values persist as `partition → valueId` slots plus a value dictionary; the `OwnerSortIndex` is rebuilt at load | a persisted sort index rewrites its whole column per dirty flush | Fork 1 |
| 2026-10-01 | One slot-keyed tree per reference × scope; inline tuple dictionary, no cap | ≤ 5 distinct tuples per reference on every dataset available | Rejected outright |
| 2026-10-01 | A dropped index leaves both dirty maps, and its footprint removals precede every reinstalled part | a primary key must survive retire + reinstall in one flush window | Invariant 3 |
| 2026-10-02 | Another entity's staged parts are served from the executors of the running root mutation | same-root reads were invisible, or visible only by aliasing | Partition handle |
| 2026-10-02 | No schema-change operation in this work; #409 owns rebuilding after a schema change | a schema change on a populated collection rebuilds nothing today | Indexing levels |
| 2026-10-06 | Reads: the row part of a body is a planning-time, content-keyed constant; no new formula class | invalidation cannot widen; nothing registry-keyed enters a cached tree | Read path |
| 2026-10-07 | **Hybrid write path:** a singleton is written to its row only; an object exists only at ≥ 2 owners | fewer index operations than before on every singleton write | Write path |
| 2026-10-07 | **No refusal of schema changes** between `FOR_FILTERING` and `FOR_FILTERING_AND_PARTITIONING`; a write or read that needs partitioning-only content from a row fails loudly | the change itself stays as cheap as today; only what cannot be answered fails | Indexing levels |
| 2026-10-07 | One storage protocol (8) and **one migration 6 → 8** carrying every 2026.3 storage change; 7 is refused (protocol number superseded 2026-10-19) | no new header component; one publication | Storage protocol |
| 2026-10-07 | `virtualPartitionCount` in `IndexCardinality` (Java, gRPC), counted by walking the rows | a counter would add transactional state to the write path for a statistic nobody polls | Observability |
| 2026-10-08 | Removals on a row of a reference re-indexed for partitioning proceed row-only; a leftover object is retired at 1 → 0 | only additions need content a row cannot hold | Indexing levels |
| 2026-10-11 | Protocol 7 is recognised by the **original bootstrap record**, refused before any migration runs (superseded 2026-10-19) | every migration step republishes the bootstrap record and would hide it | Storage protocol |
| 2026-10-11 | The conversion's test-only observer is **deleted**; the memory bound rests on the full-corpus measurement | no test-only seam in production code | Storage protocol |
| 2026-10-11 | The load-time correction of raw temporal owner sort values **moves into the 6 → 7 migration** | legacy-data logic lives in migrations, not on the load path | Owner sort indexes |
| 2026-10-11 | The isolation of the promotion population is a production executor flag | a test switch's `OFF` constant was carrying it | Invariant 4 |
| 2026-10-11 | The migration also re-sorts localized owner sort indexes ordered by the bare collator (A); merged buckets stay merged (B) | 2026.2 catalogs would fail to load or serve the release's order | Owner sort indexes |
| 2026-10-11 | The stale type-index primary-key bitmap of catalogs created by 2025.6 or earlier stays out of this work | pre-existing since #906; the repair is a recompute-from-data migration | Open follow-ups |
| 2026-10-11 | The index-type raise defect (objects written under `FOR_FILTERING` trusted as partitioned) stays with #409 | maintainer | Open follow-ups |
| 2026-10-12 | The owner sort index rewrite also re-sorts `Locale` elements ordered by their language tag, and `DateTimeRange` elements ordered at whole seconds; merged buckets stay merged | same failure, found by review and by a probe | Owner sort indexes |
| 2026-10-19 | Protocols 7 and 8 **merged into 7**: one migration 6 → 7, and the protocol development builds wrote is no longer refused | every catalog a development build wrote at 7 can be rebuilt | Storage protocol |
| 2026-10-19 | The bytecode guard over direct accesses to the reduced index family is **removed**; the handle's javadoc states the rule | its allow-list cleared a red run with one more line, and behaviour-neutral changes broke it | Partition handle |
| 2026-10-20 | The bytecode guard over the writers of the registry is **removed** too; the registry's javadoc states the rule | it saw direct callers only, its method lists needed upkeep, and its history holds no catch | Partition handle |

### Settled without a fork

Both designs agreed on these. They are recorded so they are not re-opened:

- **No index object per row, not even a transient one per query.** Synthesising a `FilterIndex`, `SortIndex` or
  `ChainIndex` per row, or emitting one `IndexTaggedFormula` per partition, recreates the 169,102-way fan-out the change
  removes. That includes `ReferenceBodyTransposer`'s walk over the whole family, which
  `2026-09-17-row-scoped-reference-having-body` measured at 10.9 s for 32,000 partitions.
- **Filter and unique data need nothing new.** The type index already holds them keyed by partition primary key, and
  one partition primary key is one row, so a body evaluated there is row-scoped by construction.
- **Chains are not deferred.** A row present in a chain answers position `0` for the record id the write path indexes:
  the owner's primary key for `Predecessor`, the referenced primary key for `ReferencedEntityPredecessor`. It is absent
  only without a chain entry. Answering "no chain" would be wrong, not degraded: `ReferencePredecessorComparator` maps a
  missing chain to `-2` and sorts it before a found position.
- **Block orderings (traverse, chains) consume partition handles** and stay agnostic to #1670's route rule.
- **Transitions keep the partition's primary key and `RepresentativeReferenceKey`.** These are the identity every
  persisted pointer and cached formula uses.
- **A partition's locales are derived** from the owner's state in the global index, never stored per row.

### Fork 1 — where the sort and chain values of a single-owner partition live

**Chosen: in the registry, ordered by one in-memory `OwnerSortIndex` per sort key.** Each sortable attribute, compound
and chain of the reference, with its locale, gets a slot in the registry tree. A sort slot maps a partition primary key
to a value id in that slot's dictionary of normalized values. A chain slot maps it to the predecessor primary key, and
the entry's presence is the chain's presence. Next to each sort slot the registry keeps an `OwnerSortIndex` whose
record ids are partition primary keys. It is never a `SortIndexView` and never persisted: a counting pass rebuilds it at
load from the slot, which arrives in partition order (≈ 24 ms for 170,000 rows over 77 values, measured). A query claims
value groups from it and emits them page by page. Within one value, owners follow their primary key, as the tie rule of
`2026-09-23-pick-first-reference-ordering-from-selection` requires.

- **Declined — raw paged columns per `(attribute, locale, partition)`, comparison value derived at query time, row runs
  merged with the objects' runs.**
  - **Rejected because:** every query would derive and order the comparison values of the rows it claims, where a sort
    index keeps them ordered once. On the dominant shape that is a lot of work: 77 distinct values, the largest block
    53,388 rows, one row per owner. The columns would also need their own paged persistence, where the sort index reuses
    the existing transactional one. The column design's own author named the sort index as its fallback.
  - **Revisit if** a sort key appears whose distinct values approach its row count, so the dictionary stops paying.
- **Declined — the `OwnerSortIndex` persisted under a new attribute-index type.**
  - **Rejected because:** a sort index of ≤ 256 distinct values stays SINGLE and rewrites its whole positional column on
    every dirty flush. That is ≈ 0.2-0.35 MB per transaction touching one `Product.media` sort row (estimated from the
    codec), against tens of bytes for an object's own sort index. It breaks the criterion "no family-sized write on a
    one-row change" by construction. It also needed an enum constant, a loader and root-removal arm, a
    `Migration_2025_6` arm and a `SortIndexStoragePart` subtype.
  - **Revisit if** the load-time rebuild ever shows on a profile.
- **Deferred — a persistent owner-keyed projection of each owner's winning row.** Its answer depends on the query's
  target order, and owners mixing rows and objects would still need a second merge. Same deferral as in
  `2026-09-23-pick-first-reference-ordering-from-selection`.

### Fork 2 — what promotion and demotion read

**Chosen: no raw copy of a row's values.**

- **Promotion** (a second owner joins a row) installs an index object under the row's primary key. It fills the object
  with the first owner's row, replayed from that owner's stored references part *as the root mutation sees it*. The
  replay runs through an executor that writes as the first owner, maintains no registry and uses the locales the owner
  had before the root mutation. The second owner then enters as it would enter any object, and the row is dropped.
- **Demotion** (an object left with one owner) extracts the survivor's values from the object by record id
  (`SortIndex#getValueOfRecord`, `ChainIndex#getPredecessorPrimaryKey`) into a row, then retires the object. Only the
  chain predecessor is stored beyond owner, tuple and sort value ids.

- **Declined — store every row's raw reference values** (the issue's sketch: the row is authoritative, so promotion
  reads nothing foreign).
  - **Rejected because:** the row would duplicate filter values the type index already holds keyed by partition primary
    key (422.9 MB of the census was that duplication), to speed up a rare transition. `Product.media` LIVE had 268
    two-owner partitions out of 169,102, and the churn window had no net transition at all. Storing rows only for
    single-owner partitions would fix promotion but not demotion, which needs the survivor's values regardless.
  - **Revisit if** promotion shows in write latency. It measured flat (M3 below).
- **Declined — demotion replays the survivor's stored row** (derive the row from storage).
  - **Rejected because:** a row must equal what the object held, not what the entity body says. After a schema change
    an object can hold drifted values, for example sort values left behind when an attribute stopped being sortable.
    Deriving from storage would repair the row silently while sibling objects stay drifted. The maintainer amended the
    fork on 2026-10-02 for this reason: extraction by record id copies exactly the object.
- **Declined — build the promoted object from the row** (no foreign read at all).
  - **Rejected because:** the row holds owner, tuple, sort values and chain predecessors. An object also needs the first
    owner's filter and unique entries, language records and localized compounds. So promotion must read the owner's
    storage anyway, or the row must grow by everything an object holds, which undoes the compression.

### Fork 3 — how an owner's rows are found

**Chosen: a row stays an ordinary *covered* entry of `ReducedIndexMembership`, and the registry's `PARTITION_OWNER`
slot is the single authority.** The membership is rebuilt from that slot at load (`EntityCollection#registerPartition`).
A candidate the membership names is validated by one owner-slot lookup. `ReducedIndexMembershipCompletenessTest` counts
rows and objects alike.

- **Declined — a separate `(owner, partition) → target` tree as the authority, with rows leaving the membership.**
  - **Rejected because:** it is a second owner → partition structure to keep in step on every write, and it answers
    nothing the membership plus one owner-slot lookup cannot. Its author withdrew it (≈ 5 MB less) on one condition: a
    WARM_UP rollback of a membership removal must not leave a stale entry behind. That holds, because the membership's
    map journals every put and remove (see the stale-warning hazard below). The membership deliberately tolerates stale
    positive entries, which is why it stays an accelerator and the owner slot is the authority.

### Transitions are decided on the net state of the entity mutation

**Chosen:**

- Promotion (1 → 2) is eager: a row cannot hold a second owner.
- Demotion (2 → 1) runs at the end-of-mutation sweep, where the sweep of empty indexes already ran, so it sees the net
  owner count.
- A drop (1 → 0) deletes the row at once, and the primary key is **reserved** for the rest of the root mutation, under
  `(entity type, scope, RepresentativeReferenceKey)` (`PartitionReservations`). Any owner re-entering that key in the
  same root mutation gets the same primary key, and its own row values are written as for any entering owner: no replay.
  That is what an emptied object did by keeping its key until the sweep. Deleting the row at once leaves nothing
  query-visible that means "empty".
- No owner may enter a partition while the root mutation finishes (a "finishing" premise).

The churn census decided it: every one of the 7,564 `Product.relatedProducts` removals in a 42-minute production window
was re-inserted for the same owner and target in the same version. That is 284 demote-then-promote and 258
drop-then-recreate round trips, and zero net transitions. Eager transitions would pay a storage read and a replay twice
per round trip, and could mint a new primary key for a recreated row.

- **Declined — eager demotion at the boundary the removal detects.** **Rejected because:** the round trips above.
- **Declined — a live/empty state on registry entries for a vacated identity.** **Rejected because:** every query and
  membership path would have to skip "empty" entries. A reservation scoped to the root mutation gives the same primary
  key and exposes nothing. A primary key burnt by an unclaimed reservation costs nothing, because the sequence only
  advances.

### The write path is a hybrid: rows for singletons, objects only at two owners

**Chosen** (maintainer, 2026-10-07):

- **0 → 1** mints the primary key from the collection's index sequence without an object
  (`IndexMaintainer#allocatePartitionPrimaryKey`), advertises it in the type index as before and registers the row. It
  then runs the filtering write set (reference attributes, compounds, pre-mutation locales) through
  `AttributeIndexMutator` with the row as its target. The mutator computes every value as before;
  `VirtualReferencePartitionWriter` records it in the row.
- **Writes that keep a singleton a singleton** touch the row only. Language records and facets are no-ops, because the
  owner's locales come from the global index and a filtering partition has no facets.
- **1 → 2, 2 → 1 and 1 → 0** run as above.
- **One acquisition funnel:** a lookup (object, else row by `(target, tuple)`, else reservation) for every non-insert,
  and insert = lookup else mint. Generic index creation refuses a `REFERENCED_ENTITY` key of a reference whose
  partitions are rows (`EntityCollection#assertPartitionObjectMayBeCreated`), so no path can mint a second primary key
  for a registered identity.

The writer's strict assertions (`assertNoSortValue`, `assertSortValue`, chain removal) turn any divergence between what
the mutator computed and what the row holds into a failed mutation, not a silent difference.

- **Declined — every write to a row installs a transient object, writes it and retires it at the sweep.**
  - **Rejected because:** for the same estimate (5.0 vs 5.25 agent-days) it does about 2-3× the index work of the old
    code on the removal, locale and scope paths. Every one of those populations sits on the hot path, and each is a
    place a transient object can escape as a persisted single-owner object or a leaked STM layer. Its own fallback, if
    the measurement found the cost, was the hybrid per hot arm, so it would have been paid twice.
- **Declined — the full hybrid without a foreign read at promotion.** See fork 2.

### One storage protocol, every 2026.3 storage change in one migration from 6 to 7

**Chosen** (maintainer, 2026-10-07; built in the cleanup; the protocol number settled on 2026-10-19):

- `PersistenceService.STORAGE_PROTOCOL_VERSION` is 7, every new catalog is born 7 with rows, and nothing else is read.
- `Migration_2026_3#upgradeFromStorageProtocolVersion_6_to_7` is the one step from the 2026.2 protocol. Per collection:
  1. the cardinality-counter and owner-unique re-keys of `2026-09-21-cardinality-counter-normalized-keys` and
     `2026-10-08-unique-indexes-normalize-like-filter-indexes`, as before;
  2. the owner sort index rewrite (`#rewriteLegacyOwnerSortIndexes`, next section);
  3. the conversion, `VirtualReferencePartitionConversion#convertCollection`, which writes each row through
     `VirtualReferencePartitionWriter#demoteToRow` - the very demotion the write path runs, so the two cannot drift.

  Then the catalog-level unique re-key and **one publication**: the catalog header of protocol 7 and one bootstrap
  record.
- **The conversion runs last** because it reads only the entity indexes the published collection header lists, reads
  them with the parts the earlier steps rewrote, removes the ones it converts, and its final flush writes whatever those
  steps left unflushed. The steps are *not* disjoint, as the plan first claimed: they meet on a partition object holding
  an owner unique part or an owner sort part, which an earlier step rewrites and the conversion then removes with the
  rest of the object. The later write wins, so this is harmless; the class javadoc says so.
- The conversion classifies partitions against the schema the loaded catalog will have
  (`EntityCollection#bindReflectedReferences`), because a reflected reference may inherit its indexing.
- A protocol-6 catalog holds no registry row (2026.2 kept no registry), so a row met during conversion is a premise
  failure, not something to rebuild: it would mean the catalog was stamped with a protocol it was not written under.

**The protocol development builds wrote is not told apart.** Development builds have written protocol 7 since
`85d3b2c039` (2026-09-21, #1620; no tag contains it): counters and owner unique indexes re-keyed, but owner sort indexes
in the form and order of 2026.2 and every partition an index object. This version reads such a catalog as its own - no
migration runs - and does not support it: it must be recreated (what it does is pinned by
`Migration_2026_3_OwnerSortRewriteTest.UnsupportedDevelopmentCatalog`). A catalog of the protocol 8 that builds of this
branch wrote before 2026-10-19 is refused as unknown.

- The upgrade loop behind `#verifyAndUpgradeStorageFormat` refuses a protocol no migration starts from: without it, such
  a header re-read itself forever. The refusal is an `ObsoleteStorageProtocolException` (not
  `CatalogRequiresUpgradeException`, so the engine's upgrade-and-retry does not loop on it).
- **Declined — protocol 8, refusing the 7 of development builds** (built 2026-10-11, removed 2026-10-19). Both
  constructors checked the *original* bootstrap record before any migration - every migration step republishes the
  record, so a development build interrupted between two steps had left a header of 6 under a record of 7 - and the
  format dispatch refused a header of 7. **Rejected because:** the only catalogs it protected were the ones development
  builds wrote between 2026-09-21 and this merge, and every one of them can be rebuilt (maintainer, 2026-10-19) - so
  one release needs one protocol, not a second one kept only to refuse the first. **Revisit if** a development format
  ever reaches catalogs that cannot be rebuilt: refusing by the original bootstrap record, before any migration, is the
  shape that works.

Why the protocol carries the representation:

- It is read before any collection is constructed and before the WAL is replayed, so a collection created by the WAL
  tail gets the catalog's representation.
- Backup, restore, rename, replace and copy already carry it.
- It needs no new header component, serializer or UID bump.

Why one migration: the maintainer asked for every 2026.3 storage change in one step from 6, so an upgrade publishes
once.

- **Declined — a flag in `CatalogHeader`.** **Rejected because:** it needed a backward-compatible reader of the 2026.2
  catalog header and a UID bump for a flag the cleanup would remove again, while the mandatory migration bumps the
  protocol anyway.
- **Declined — a flag in each collection header.**
  - **Rejected because:** `CreateEntitySchemaMutation` carries only a name, so a collection created in the
    uncheckpointed WAL tail has no flag to read, and a catalog-level policy is needed anyway: two persisted facts
    instead of one.
  - It would also have to ride through seven collection-header producers. `CopyingHeaderInfoSupplier` (rename, replace,
    compaction) is one of them, and its default-false is a silent trap.
- **Declined — derive the representation from the data.** **Rejected because:** during development a catalog held rows
  and objects side by side, and an empty registry persists no root.
- **Declined — convert lazily, when a partition is touched.** **Rejected because:** the dominant population never
  changes. `Product.media` and `Parameter.parameterValues` saw no reference write in the churn window, so they would
  stay objects indefinitely.
- **Declined — convert on catalog load inside the engine.** **Rejected because:** of the maintainer's rule that logic
  tied to an old on-disk shape lives in `Migration_*` steps and BWC readers, which the deprecation process removes,
  never in production classes.
- **Declined — a migration from the 7 of development builds, as a chain 6 → 7 → 8.** **Rejected because:** it ships a
  migration for a format no release wrote.

**Durability** (`.claude/rules/durability-model.md`): the migration only appends until its single publication.

- Collection data stores are flushed through `DefaultEntityCollectionPersistenceService#flush`, which neither compacts
  nor retires a file. Removals are offset-index records.
- New collection headers sit unflushed in the catalog data store until one bootstrap record publishes header 7.
- A failure anywhere before that leaves the previous bootstrap record pointing at the protocol-6 header and the old
  collection headers, so the next start reruns the migration from the same published state. No file is deleted.
- `#verifyAndUpgradeStorageFormat` owns every storage service it opens until it returns and closes them on failure
  (`81ddbf32bd`); before, each failed or refused upgrade left read handles of the catalog data file open, and every
  retry another set.

**Memory:** the conversion holds one registry and one partition at a time, and flushes a collection data store whenever
the records written since the last flush reach a threshold (10,000). The migration holds every collection data store
open at once, because the unique pre-pass of the re-key spans collections. So the unflushed records of a collection are
bounded by the threshold plus what the re-key and the sort rewrite wrote into it. The full-corpus measurement (C3 below)
is the evidence for the bound.

- **Declined — keep the conversion's observer** (callbacks for every loaded registry and partition, used only by a
  memory-gate test) **as the deprecated migration's documented test seam.** **Rejected because:** it is a test-only seam
  in production code, which the cleanup existed to remove, and the code it guards is deprecated, frozen and removed with
  the migration. The maintainer took the deletion on 2026-10-11; the lost test is L10 below. A case reading
  `CollectionConversion#intermediateFlushes` - a production value the conversion's report line prints - still proves
  that intermediate flushes happen.

**How the conversion is tested.** No release-written binaries are kept in git. The engine writes the catalog with the
references to convert indexed `FOR_FILTERING_AND_PARTITIONING`, so every partition, single-owner ones included, is an
object. At the end of the history the references become `FOR_FILTERING`; the leftover rule of the indexing-level section
below keeps the objects. The header is stamped 6 offline, as `Migration_2026_3_UniqueUpgradeRoundTripTest` does, and the
boot runs the real 6 → 7 step. Two inputs cover what that generator cannot: a collection both re-keyed and converted
(and one also sort-rewritten), with failure and retry, because the choice of which flush publishes the collection is
where a combined defect would hide; and an object holding only the content filtering-only indexing writes, the smaller
footprint 2026.2 left.

- **Declined — a test-only API to create a catalog of objects.** **Rejected because:** it is exactly the test-only
  production seam the cleanup removed.
- **Declined — hand-build the whole protocol-6 fixture's objects in test code.** **Rejected because:** it re-implements
  an object's write set (owners, filter, unique, sort and chain indexes, compounds, localized values, language records),
  so the test would trust a second copy of the logic whose output it checks.
- **Declined — binary fixtures, or a 2026.2 build.** **Rejected because:** binary fixtures are banned, and a released
  build is used only by `EvitaBackwardCompatibilityTest`.
- **Limits of the chosen input:** an object of a reference indexed for partitioning also holds the owner's entity
  attributes and prices, which a 2026.2 filtering-only object never had. And the generator depends on the leftover rule:
  if #409 changes that rule, #409 needs another generator.

### The 6 → 7 migration rewrites the owner sort indexes 2026.2 stored in another form or order

An owner sort index - a sort-only attribute, every sortable attribute compound - written by 2026.2 can differ from what
this version reads in four ways:

- raw `OffsetDateTime`, `LocalDateTime` and `LocalTime` values, where this version stores and compares their
  millisecond form (`df4a8e2606`, this branch);
- a localized `String` element ordered by the bare collator, where this version breaks the collator's ties
  (`EqualsConsistentLocalizedStringComparator`, `923188f887`, #1620, never released);
- a `Locale` element ordered by its language tag alone, which drops an ill-formed variant, where this version breaks
  that tie on `Locale#toString` (`785b0408f7`, never released);
- a `DateTimeRange` element compared at whole epoch seconds, an open bound derived from the zone offset of the other
  bound, where this version compares milliseconds and uses one constant for an open bound (`9a3e90c93d`, dev, never
  released). Two ranges opening in the same second no longer tie, and two ranges open at one end whose other bounds
  carry different offsets - validity spanning a daylight-saving change - compare the other way, tie or not.

In the localized `String` and `Locale` cases, a compound holding a pair the old order tied, ordered by its next element,
is stored in an order this version reverses; a `DateTimeRange` misorders a plain index too. Reproduced on the unchanged
code: a paged one failed to load (the bulk load refused keys that do not ascend); a pair straddling a page boundary was
refused by the cross-leaf diagnostics as a stale twin - a valid catalog reported as corrupt; an inline one loaded and
silently served the release's order, and kept serving it after later writes and reloads.

**Chosen: `Migration_2026_3#rewriteLegacyOwnerSortIndexes`, per collection, before the conversion.**

- It **selects by element type**: every owner sort index with a temporal, a localized `String`, a `Locale` or a
  `DateTimeRange` element, in every entity index the collection lists (`LegacyOwnerSortIndexCorrection#isApplicable`),
  and reads it in full.
- It **rewrites only an index out of the current form or order**: one holding a raw value, or whose persisted values do
  not ascend strictly under the current comparator, page boundaries included. An index already in the current form and
  order is read and not written.
- The rewrite (`LegacyOwnerSortIndexCorrection#rewrite`) is whole: an inline index as a new part; a paged one as new
  leaf pages above the persisted high-water (`InvertedIndex#supersedePersistedPages`), a new root, and the removal of
  every legacy leaf page (offset-index records only). The parts are emitted into the data store explicitly, never left
  to a dirty flag and a later flush. The rewrite adds its own term to the collection's publication decision, so a
  collection with nothing else to upgrade still publishes it.
- **Persisted pages are checked by the comparator 2026.2 wrote them with**
  (`LegacyOwnerSortIndexCorrection#createReleasedComparator`: the bare collator for a localized `String` element, the
  language tag for a `Locale` element, whole seconds for a `DateTimeRange` element through
  `InvertedIndex#compareAtSecondGranularity`, natural order otherwise), so a stale leaf-page twin is still refused as
  damage and a valid catalog is not.
- It runs before the conversion because the conversion removes the partitions it converts and its final flush is the
  one the collection is published by; the rows it writes are normalized by the registry either way.
- Nothing on the load path normalizes or re-sorts these values any more: `SortIndex` and
  `OwnerSortIndex#fromPersistedPages` load what is stored. The class was `LegacyTemporalSortValueCorrection` while it
  corrected temporal values on every load; it is now `LegacyOwnerSortIndexCorrection`, deprecated for removal with the
  migration.
- **One exception, dev's and permanent:** the load path re-sorts a *paged* inverted index whose every value is a
  `DateTimeRange` (`InvertedIndex#isSecondGranularityRangeOrder`, `#resortSecondGranularityBuckets`); filter indexes
  rely on it. It never covered a compound or an inline owner sort index - those are the shapes the rewrite adds
  (`7c0a1403c2`).
  Both use `InvertedIndex#compareAtSecondGranularity`, which is therefore public and **not** deprecated with the
  migration.

**Owner decision B (2026-10-11): merged buckets stay merged** - the same trade PR #1621 accepted for filter indexes
(`CollatedKeyIdentityTest.MigrationHazard`). A bucket in which 2026.2 merged two values its comparator equated - two
collation-equal spellings, two locales sharing a language tag, two ranges equal at whole seconds - stays merged, under
the value it was stored with: the bucket does not say which record holds which value. Equality on the other spelling
misses the record filed under the merged one, and updating or removing that attribute of the record fails. The remedy is
to rebuild the catalog from source data; the upgrade note on #1615 says so.

- **Declined — keep the temporal correction on the load path** (cleanup decision D3, option B).
  - **Rejected because:** it repeated on every load until a record of the index changed, which nothing guaranteed, and
    it kept legacy-data logic in `SortIndex`, `InvertedIndex` and the loaders, against the maintainer's rule
    (2026-10-05) that a repair of a shape an older release wrote belongs in a `Migration_*` step or a BWC reader. Every
    catalog a release wrote that can hold such an index passes 6 → 7, so after the migration the load path would serve
    no data; the one a development build wrote at 7 is not supported (see the storage protocol section).
- **Declined — rewrite in the migration and keep the load-time correction as a safety net.** **Rejected because:** after
  the rewrite it has nothing to catch, and it keeps the permanent cost the rule forbids.
- **Declined — check persisted pages with the current comparator** (the first shape of the rewrite). **Rejected
  because:** review found it refused a valid 2026.2 paged compound holding a collation-equal pair as damage, aborting
  the upgrade; the same then held for `Locale` and for `DateTimeRange` (a paged `[DateTimeRange, OffsetDateTime]`
  compound was refused as damaged). Pages must be checked by the order that wrote them.
- **Declined — split merged buckets from the entity bodies.** **Rejected because:** the persisted bucket does not say
  which record holds which value, so the migration would read the entity body of every record of every such attribute;
  the maintainer accepted the merge instead (decision B). **Revisit if** a merged bucket is ever reported from the
  field.

### Changing a reference between the two indexing levels is not refused; the operation a row cannot answer fails

The maintainer decided this on 2026-10-07 and refined it on 2026-10-08. On 2026-10-02 the maintainer had ruled that this
work carries no schema-change operation, because a schema change on a populated collection has no general reindex
machinery today and keeps whatever content it had; #409 owns rebuilding after a schema change. So nothing converts rows
when a reference's indexing changes.

**`FOR_FILTERING` → `FOR_FILTERING_AND_PARTITIONING`.** The rows stay rows.

- A write that would **add** to such a row what partitioning needs fails with
  `ReferencePartitionReindexRequiredException`. That covers an owner entering, an attribute, a locale, a price, and a
  facet a re-evaluation would add. The exception names entity type, reference and scope and asks for a reindex.
- A write that only **removes** content proceeds on the row alone. Every acquisition states its intent
  (`PartitionWriteKind`).
- A query that needs partition-local content an object of the partitioning level would hold is refused the same way
  (`ReferencePartitionReindexRequiredException#forQuery`): entity attributes or prices within one target, the facet
  summary, a reflected reference's own attributes. Before, such a query died with an internal "expected a reference
  partition, got null" error.
- A hierarchy option whose node partitions include a row is not offered as a partitioned plan
  (`IndexSelectionVisitor#addHierarchyIndexOption`, `d77a8f24fc`): its plan resolved indexes object-only, found none for
  the row and silently left the row's owner out. The query is answered from the global index instead.
- The check costs one constant-time lookup per acquisition, and only when the type index holds rows.

**`FOR_FILTERING_AND_PARTITIONING` → `FOR_FILTERING`.** Single-owner objects of the partitioning era stay objects. The
funnel returns them, and they read and write as before. A later 2 → 1 demotes one to a row as usual. When the last owner
leaves such an object, it is retired and its primary key reserved, so a partition is never a row and an object at once.

- **Declined — materialize on the transition** (build objects for rows on the way to partitioning, demote on the way
  back). **Rejected because:** a schema change keeps its drifted content today, so a conversion here would be the first
  reindex machinery in the engine and would have to repair drift it cannot see. That is #409's job: a drop task at
  commit, then a rebuild from the bodies with WAL catch-up.
- **Declined — refuse a schema change that would leave rows under `FOR_FILTERING_AND_PARTITIONING`** (a phase-5 plan
  item, widened by review to both directions and to prospective schemas).
  - **Rejected because:** it contradicted the 2026-10-02 decision to keep schema-change operations out of this work, and
    the maintainer chose loud failure of the operation instead. To be sound, it had to evaluate the prospective
    effective schemas of a whole mutation batch, reflected inheritance included, in both directions, before any side
    effect - a cross-collection preflight guarding a state #409 removes. It would also refuse schema changes that are
    accepted today.
- **Declined — convert a row to an object when it is touched.** **Rejected because:** it puts transient objects back on
  the write path (as the declined transient-object write path above did), for a transitional state.
- **Declined — never demote a partitioning-era object** (a persisted marker on the object). **Rejected because:** it
  adds persisted state for a transitional case #409 removes anyway.
- **Accepted consequence:** a partitioning-era object demoted to a row and later promoted again is rebuilt
  filtering-only. Its entity attributes and prices are gone if the reference goes back to partitioning. That drift goes
  to #409.

### Read path: the row part of a body is a planning-time constant, and nothing registry-keyed is cached

The maintainer took the phase-4 plan's defaults on 2026-10-06.

- **`referenceHaving` body.**
  - The body is evaluated once per query against the type-level structures. The matching partition primary keys are
    translated to owners and enter the formula tree as one content-keyed `ConstantFormula`, ORed outside
    `ReferenceBodyTransposer.transpose`.
  - No new formula class and no `CLASS_ID`. No registry or type-index transactional id reaches a cached tree, so a write
    elsewhere in the reference leaves a cached ancestor valid as long as the answer is unchanged.
  - The cacheable carriers are the ancestors (`and`, `or`, `not`, scope, user filter), which inherit the content token.
- **Exact row leaves.** Where the type index's answer differs from the per-partition answer, a row leaf replaces it:
  - `entityPrimaryKeyInSet` against the row's target, which matters for a target not yet existing;
  - `groupHaving` through the group indexes;
  - `entityLocaleEquals` as the owner's membership in the global locale bitmap of the partition's scope, evaluated at
    planning so that bitmap never enters a cached formula.
- **Resolution never touches an object for a row.**
  - By primary key, a scope- and reference-qualified overload.
  - In bulk, `ReferencePartitionSplit`, a merge of the candidates with the owner-slot cursor.
  - By key, a query-local `PartitionKeyMemo` built once per target per query.
- **Sorting.**
  - Pick-first builds one row run per scope and sort key from claimed value groups, admitting owners through the shared
    selection bitmap.
  - Block orderings get singleton providers.
  - Chains get position 0, as above.
- **Physical inspection stays physical.** The index browse and describe report index objects; a row is not an index.

- **Declined — a cacheable translation formula whose staleness reads the registry's leaf versions.** **Rejected
  because:** it needs a new `CLASS_ID` and value-id leaves the registry's long-payload tree does not mint. The
  content-keyed constant cannot widen invalidation by construction. **Revisit if** the lost reuse ever matters; C2 below
  found none.
- **Declined — a maintained `(target, tupleId) → partition` structure in the registry.** **Rejected because:** it is a
  write-side change for a lookup the per-query memo answers once per target.
- **Declined — derive a row's locales as owners ∩ the global locale bitmap inside a formula.** **Rejected because:**
  every entity gaining or losing a locale would then evict it; the price research behind #1677 found the same hazard.
- **Cost knowingly taken:** a bare all-row body is never cached itself; only its cacheable ancestors are. C2 measured
  what that costs: nothing on the hot shapes.

### Observability: rows are counted, not browsed

`IndexCardinality` gains `virtualPartitionCount` in Java and gRPC (`GrpcIndexCardinality.virtualPartitionCount` in
`GrpcStatistics.proto`). It is present, possibly 0, on the `REFERENCED_ENTITY_TYPE` entry and null elsewhere, and it is
counted by walking the owner slot with the tree's forward cursor. That walk is O(single-owner partitions), tolerates a
concurrent warm-up writer and allocates one boxed key per entry. The index summary, the browse and `omittedIndexCount`
stay counts of physical indexes, and their javadoc and proto comments say so.

- **Declined — a transactional counter maintained on the write path.** **Rejected because:** it adds STM state and a
  write per transition for a statistic that is requested explicitly and never polled. **Revisit if** it starts being
  polled.

### The partition handle, and reading another entity inside a mutation

**Handle.** `ReferencePartition` is a sealed interface. `ReducedEntityIndex` implements it directly and
`VirtualReferencePartition` is a record holding a snapshot of a row. The group family (`ReducedGroupEntityIndex`) is not
a partition, which is the gate in code. Code that needs the object unwraps the handle in an exhaustive `switch`, so the
compiler lists every such site. Neither a direct read of the reduced index family nor a write of the registry from
outside its writer is guarded by a test (see the two declined guards below): the javadoc of `ReferencePartition` and of
`VirtualReferencePartitionIndex` state the rules.

- **Declined — a wrapper object around the index** (the issue's first suggestion). **Rejected because:** it costs one
  allocation per partition per resolution on six-figure fan-outs, and it breaks identity:
  `ReferenceIndexMutator#forEachUniqueReferenceIndex` dedupes by identity and asserts `indexForRemoval ==
  indexForUpsert`.
- **Names declined:** `ReducedIndexHandle`, because a row is not an index; `RepresentativeReferenceSlice`, because
  "slice" is spent on `ReducedIndexMembership`; `ReferencedEntityPartition`, longer with the same ambiguity. "Partition"
  here is unrelated to the `FOR_FILTERING_AND_PARTITIONING` level, and the interface's javadoc says so in its first
  paragraph. *Promotion* and *demotion* (a row becoming an object when a second owner joins, and back) share their
  words with `ReducedIndexMembership`'s coverage hysteresis, which promotes a reduced index out of coverage above the
  coverage threshold of owners and demotes it back at half of it. The two are unrelated - a row holds one owner, so it
  is always a covered entry - and the interface's javadoc says so too.
- **Declined — a bytecode guard over every direct access to the reduced index family** (`ReferencePartitionAccessGuardTest`,
  built in phase 1 and removed at review on 2026-10-19). It scanned the bytecode of `evita_engine` and
  `evita_store_server` under seven rules against an allow-list of 165 sites, each with a reason, and it tracked the
  move of about 240 direct accesses behind the handle. **Rejected because:** once that move was done, it protected
  little that nothing else does, at a standing cost. An entry's reason is free text the test cannot check, so a red
  run cleared with one more line; its largest rule (80 entries) flagged any mention of the reduced index type rather
  than a lookup that misses a row; and behaviour-neutral changes - a rename, a signature, the compiler's numbering of
  synthetic classes - turned it red, so 28 commits of the branch edited the list, which grew as often as it shrank.
  A write path handed a virtual handle fails loudly (`ReferencePartitions#requireMaterialized`). A read route that
  skips the rows answers wrongly, and the virtual partitions are behind no switch, so a functional test of the route
  meets them whenever its data holds a single-owner partition. The lifetime of the by-key owner-read resolver is left
  to its javadoc and its caller's (L8). **Revisit if** a route reaching a reduced index directly ships a wrong answer
  the functional tests did not catch.
- **Declined — a bytecode guard over the writers of the registry** (`VirtualReferencePartitionWriterGuardTest`, built
  with the registry on 2026-10-02 and removed at review on 2026-10-20). It allowed only the writer, the reference,
  attribute and entity index mutators, the registry itself and the storage protocol migration to call the writer or
  one of the registry's 15 mutating methods. **Rejected because:** it had the faults of the access guard at a smaller
  size. It restricted the direct callers only, so a write reaching the registry through a helper of an allowed class
  passed, as its own javadoc said; a new mutating method of the registry was unguarded until someone added its name to
  the list; and renaming one of the six methods its sentinels named turned it red with no change in behaviour. All 7
  commits that touched it kept it in step with the code, and none caught a write. An IDE cleanup turned the lambda its
  counterfactual fixture held into a method reference, and the guard stayed green with its lambda case no longer
  tested. The registry's content is checked where it matters: `VirtualPartitionInvariants`, asserted by six suites
  after their writes, checks the rows against the index objects, the stored parts and the reduced-index membership;
  `VirtualPartitionReloadInvariantTest` requires a reload and going live to leave the registry as it was; and the
  parity tests compare every read route with a catalog of index objects. **Revisit if** a registry write from outside
  the writer ships a drift the functional tests did not catch.

**Reading another entity inside a mutation.** `LocalMutationExecutorCollector` implements
`InFlightEntityAccessorLookup`. `ContainerizedLocalMutationExecutor` asks it before reading another entity's storage
parts, so a mutation reading an entity mutated earlier in the same root mutation sees that entity's staged parts.
Before, the result depended on the part's history: read fresh from storage, the in-flight change was invisible; already
trapped in the buffer, it was visible only by aliasing. Foreign parts read from storage are cached once per root
mutation (`ForeignEntityStorageParts`). Promotion and demotion depend on it.

- **Declined — reuse `EntityStoragePartExistingDataFactory` for the foreign read.** **Rejected because:** its locale
  suppliers apply the mutating entity's locale changes to the other owner.

## Rejected outright

Smaller structural options from the phase plans, one row each.

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Three or four separate registry trees (owner, tuple, sort, chain) | one tree keyed `pack(slot, partitionPk)` needs one root part, one leaf part, one removal and one stream key instead of three or four of each; slots stay contiguous key ranges | — |
| Fold the owner slot into `ReferenceTypeCardinalityIndex`'s tree | that tree holds the reference's counts; a representation change would rewrite a structure ordinary reference mutations own, and its root re-emits an inline map on every dirty commit | — |
| `TransactionalIntToLongBPlusTree` for the registry | it has no leaf-page handles or page streams, so it cannot persist granularly | it gains paging |
| `KeyCompressor` as the tuple dictionary | its ids are minted store-side at flush; the engine sees a read-only compressor | — |
| `InvertedIndex` value ids for the tuples | `InvertedIndex#getValueById` refuses inside an open transaction, and the handle needs id → tuple there | — |
| Reuse `ReferenceTypeCardinalityIndexLeafPagePart` for registry pages | its stream id is hard-wired to (index, reference name) and would collide; storage statistics would file registry pages under the cardinality index | — |
| A cap or a paged tuple dictionary | every dataset available holds 1-5 distinct tuples per reference; representative attributes mirror an application enumeration. The cost of an unbounded one is a root rewrite per new tuple, not memory, and the user documentation (`use/schema.md`) now asks for low-cardinality representative attributes | a dataset models a per-link unique representative value |
| Cancel a dropped index's removals when its primary key is reinstalled in the same flush window | the old object's pages the new one does not overwrite, and roots of sub-indexes it lacks, would stay unreachable dead space; ordering removals first reclaims them | — |

## Key technical details

Paths are relative to `evita_engine/src/main/java/io/evitadb/` unless they name another module. The developer
documentation describes the mechanics: `documentation/developer/indexes/index-hierarchy.md` ("Reference Partitions and
the Registry of Single-Owner Partitions") and `data-structures.md` ("Registry of Single-Owner Partitions").

**Entry points**

- **The registry:** `index/VirtualReferencePartitionIndex` on `ReferencedTypeEntityIndex`, one per reference and scope,
  with slots named by `index/VirtualReferencePartitionSlot`. Its class javadoc is the reference for the tree layout, the
  tuple and value dictionaries, the load-time sort-index rebuild and the persistence.
- **The handle and resolution:** `index/ReferencePartition`, `index/VirtualReferencePartition`,
  `index/ReferencePartitions` (every resolver), `index/PartitionKeyMemo`, `index/ReferencePartitionSplit`,
  `index/IndexWriteTarget`.
- **The write path:**
  - `index/mutation/local/VirtualReferencePartitionWriter` is the registry's only writer, including `#demoteToRow`,
    shared with the conversion.
  - `ReferenceIndexMutator#promoteWhenJoiningAnotherOwner`, `#ownerEnteredVirtualPartition` and
    `#ownerLeftVirtualPartition`.
  - `EntityIndexLocalMutationExecutor#installPartitionObject`, `#retirePartitionObject` and
    `#createExecutorOfOtherOwner`.
  - `PartitionReservations` and `PartitionWriteKind`.
- **Index lifecycle:** `index/IndexMaintainer#installIndex` and `#allocatePartitionPrimaryKey`;
  `core/collection/EntityCollection#assertPartitionObjectMayBeCreated`, `#registerPartition` (membership at load) and
  `#bindReflectedReferences` (the effective schema the conversion classifies against).
- **Buffer:** `core/buffer/DataStoreChanges#removeIndex` and `#popTrappedUpdates`.
- **Cross-entity read:** `index/mutation/storagePart/InFlightEntityAccessorLookup` and `ForeignEntityStorageParts`.
- **Store:** `evita_store_server/…/store/catalog/Migration_2026_3` (`#upgradeFromStorageProtocolVersion_6_to_7`,
  `#rewriteLegacyOwnerSortIndexes`) and `VirtualReferencePartitionConversion`, with
  `index/attribute/LegacyOwnerSortIndexCorrection` on the engine side. All three are
  `@Deprecated(since = "2026.3", forRemoval = true)` and go together. The refusal of a protocol no migration starts
  from lives in `DefaultCatalogPersistenceService#verifyAndUpgradeStorageFormat`.
- **API:** `evita_api/…/api/exception/ReferencePartitionReindexRequiredException` and
  `CollectionIndexCardinality.IndexCardinality#virtualPartitionCount`.

**Invariants a future change must preserve**

1. **Identity.** A partition's primary key and `RepresentativeReferenceKey` survive 0 → 1 → 2 → 1 → 0 within a root
   mutation, reload, WAL replay, backup and restore. The persisted index sequence (`lastEntityIndexPrimaryKey`) stays at
   or above every registered row, so a key handed to a row is never minted again.
2. **Representation**, for a reference × scope indexed `FOR_FILTERING` with the `REFERENCED_ENTITY` component:
   - no index object has fewer than two owners (except the leftovers of a former partitioning indexing);
   - no row also has an object, a stored index part or a collection-header entry;
   - the membership covers every row with its owner, and covers nothing that is neither a row nor an object. The last
     clause is the defect `5f7588e478` fixed: both owners leaving an object in one root mutation in ALIVE.
3. **The buffer contract of a retired index.**
   - A dropped index leaves both dirty maps, by key and by primary key, and a savepoint rollback reinstates both.
   - `popTrappedUpdates` emits the dropped footprint's removals first (pending sub-index removals, then the manifest and
     membership removals), and the parts of the dirty indexes after them. The store applies parts in iteration order and
     a later part wins.
   - So an object installed under the primary key of one retired in the same flush window survives the flush, and
     whatever of the old footprint it does not overwrite is reclaimed.
   - `installIndex` premises: the key is absent from both maps, the primary key is not above the sequence, and the
     sequence never advances.
4. **The promotion population is isolated.** The executor that fills a promoted object
   (`EntityIndexLocalMutationExecutor#createExecutorOfOtherOwner`) writes as the first owner, acquires no index and
   writes no registry: the first owner's row still holds those values until the second owner enters and the row is
   dropped. `EntityIndexLocalMutationExecutor#isPartitionRegistryWritten` is false for that executor only, and
   `VirtualReferencePartitionWriter` applies the gate on every path to the registry. Counterfactual: drop the gate and
   all 53 promotion cases fail with "The executor writing as entity 1 into the indexes it was handed must not reach the
   index …". During development this isolation was carried by the `OFF` constant of a test-only mutation switch;
   deleting it as "a test switch" would have broken every promotion.
5. **The locale contract.**
   - Every partition an owner enters mid-mutation holds the owner's locales from **before** the root mutation, and the
     owner's own finalization moves them afterwards. The promotion population uses them too.
   - An owner's locale goes only into reduced indexes that hold the owner (`ff47c871cb`), never into the sibling
     partitions other owners hold under another representative key.
   - No owner enters a partition while the root mutation applies its executors' changes (the "finishing" premise of
     `PartitionReservations`).
6. **Registry encoding.**
   - Keys are `pack(slotId, partitionPk)`.
   - Slot ids are persisted and never derived from declaration order: constants take ids below
     `VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID` (16), declared sort and chain keys take ids from it up.
   - Partition primary keys are positive. Owner and target primary keys are any `int` and appear only in payloads.
   - Every id allocator refuses to wrap (`IdentifierOverflowException`).
7. **Sort values are normalized exactly as `SortIndex` normalizes them.** Extraction hands over already-normalized
   values, which `VirtualReferencePartitionIndex#putNormalizedSortValue` turns back into the declared type before the
   one write path; both inputs leave the same entry.
8. **No registry or type-index transactional id enters a cached formula.** A row body is a content-keyed constant.
9. **The migration is append-only until its one publication**, runs no compaction and deletes no file.
10. **Kept on purpose:** the object-creation refusal (`EntityCollection#assertPartitionObjectMayBeCreated`), as a
    production premise, and the two caching-tree debug modes of `VirtualReferenceHavingParityTest` on a representative
    subset (296 s → 28 s).

**Things that will look like bugs**

- A removal succeeds on a row whose reference was re-indexed for partitioning, while an addition to the same row fails.
- `VirtualReferencePartition` is a value copied from the registry when resolved. A query keeps reading the snapshot it
  planned against.
- A sort slot's `OwnerSortIndex` is never persisted; it is rebuilt from the slot at every load.
- The statistic counts rows by walking them: O(single-owner partitions), on purpose.
- Within one flush window a retired object's primary key can come back as a new object. The removal records the flush
  writes before the new parts are what make that safe.
- The `…Mirrored…` methods of `VirtualReferencePartitionWriter` keep the vocabulary of development, when rows were
  maintained beside objects; they record a write into the row of the partition. The predicate deciding whether the
  single-owner partitions of a reference in a scope are rows is `VirtualReferencePartitionWriter#isWrittenVirtually`
  (named `isShadowed` during development).
- The by-key owner-read resolver has no runtime check that it does not outlive a row write in the counterpart
  collection. Its one caller keeps that lifetime and says why in its javadoc, the resolver's javadoc states the
  obligation, and nothing checks a new caller (loss L8).
- No test fails when the scope fallback of `FilterByVisitor#getSuperSetFormula(Scope)` yields nothing instead of the
  global index set. The fallback runs when the index set the query is evaluated on has no index in the scope, and its
  superset cannot change an answer: a reduced plan is evaluated only when its constraint applies to every queried
  scope, so the answer is drawn from the plan's indexes, which hold no entity of that scope. Measured on 2026-10-12:
  the fallback returned a non-empty superset 233 times in `EntityByFacetFilteringFunctionalTest`,
  `EntityByFacetFilteringAndPartitioningFunctionalTest`, `FacetSummaryBackwardCompatibilityTest` and
  `InScopeReducedIndexPlanFunctionalTest`, and `FilterByVisitor#holdsIndexObjectsOnly` rejecting every set left all
  2,302 tests of those classes and of `InScopeOverAnEmptyScopeWithRegistryRowsFunctionalTest` green. The filter
  matters in the other direction only: a set holding registry rows must be skipped, or resolving its indexes throws,
  which `InScopeOverAnEmptyScopeWithRegistryRowsFunctionalTest` pins.

**Hazards for the next reader**

- **The "producer map has no warm-up journal" warning is stale.** Issue #1615 warned that
  `PersistentTransactionalProducerMap` lacks a WARM_UP journal, so a rolled-back promotion would leave an empty object
  and a restored row sharing one primary key. It is false: the map extends `PersistentTransactionalMap`, whose put and
  remove journal WARM_UP changes, and `IndexPopulation` takes part in the WARM_UP capture too. Do not add journalling on
  that warning's account; journal only state that is genuinely new. (The reservation needs none: it is discarded with
  the collector when the root mutation fails.)
- **Protocol arithmetic must name the previous release's catalog protocol.** `STORAGE_PROTOCOL_VERSION - 1` is 7, which
  the store refuses; a catalog test meaning "the previous release" must say 6 (`BackupTaskStorageProtocolTest` did
  exactly this).
- **Physical enumeration under-reports.** Anything walking `ReferencedTypeEntityIndex#forEachReferenceIndexPrimaryKey`
  and `EntityCollection#getIndexByPrimaryKeyIfExists` sees index objects only, never rows. That includes a heap probe,
  the index browse and `INDEX_SUMMARY`. Count rows with `IndexCardinality#virtualPartitionCountIfKnown()`, or walk the
  registry.
- **`SortIndex#getValueOfRecord` is O(distinct values).** Demotion uses it, once per value. A read path must use the
  registry's value ids instead.
- **A test-only switch can carry production semantics, and a "remove me" marker on an assertion can be a runtime
  premise.** The cleanup found both (invariant 4, and the owner-read resolver above). Read what a scaffold does before
  deleting it.
- **The generated datasets never hold a row.** `DataGenerator` declares all three of its references
  `indexedForFilteringAndPartitioning()`, and only a `FOR_FILTERING` reference gets rows. In the forced full suite
  25,149 of the 28,733 listed tests held no eligible reference and 672 read a row; in the hierarchy, facet and
  reference packages 1,042 of 1,175 tests were ineligible (forced read run, 2026-10-07). The conditional facet and
  bucket generational catalogs hold no eligible reference either, and until T6 gave it a `referenceHaving` check,
  `LongRunningEvitaReferencesGenerationalTest` wrote rows without ever reading one. A new read route that passes the
  functional suite has therefore proved nothing about rows: give it a case in the parity suites (T3) or a fixture with
  a `FOR_FILTERING` reference.

## Verification

### Tests that guard it

During development a second engine kept index objects next to the rows, a forced mode put the whole suite on rows, and
production probes counted work. All of it is gone. What replaced it:

- **T1, structural invariants** (`VirtualPartitionInvariants`): invariant 2, the persisted sequence covering every
  registered primary key, and a closed-world membership check.
- **T2, a body-derived oracle** (`VirtualReferencePartitionRawRowOracle`). It derives every expected partition and owner
  set from the entity bodies through the public API, paginated, requires a matching row (one owner) or object (two or
  more), rejects every extra, and checks owners, sort values with their order per sort key, and `Predecessor` chains.
  An independent key → primary-key ledger (`PartitionIdentityLedger`) catches an unnecessary reallocation the
  index-derived identity cannot. `ReferencedEntityPredecessor` chains stay out of it (#1691).
- **T3, a materialized twin in one engine** (`MaterializedPartitionTwin`). The fixture is built twice: as it is, and
  with every owner cloned at `pk + OFFSET`, so every partition has two owners and is an object reached through real
  promotions. Real page requests on the row catalog must equal slices of an independently obtained full order; each case
  asserts it selects a known singleton row; `virtualPartitionCountIfKnown()` > 0 on one side and 0 on the other proves
  the twin non-vacuous. It backs the six read-parity suites (`VirtualReferenceHavingParityTest`,
  `VirtualReferenceContentFilterParityTest` (deleted with the index route of `referenceContent`; its hand-written cases
  live in `ReferenceContentCrossScopeRowsTest`, see `2026-10-20-reference-content-filter-row-by-row`),
  `VirtualPickFirstParityTest`, `VirtualTraverseParityTest`,
  `VirtualChainParityTest`, `VirtualHierarchyParityTest`); five of the six consuming stages are proven by a
  counterfactual of their own (L7).
- **T4, self-comparison across an event** (`VirtualPartitionReloadInvariantTest`): index and query descriptions before
  and after reload, crash recovery, rename, replace, going live, duplicate, restore and compaction; a clean reload and
  close leaves every data file byte-identical; the next minted primary key is above every used one.
- **T5, failure through the public API:** a root mutation fails at its end - a mandatory reference removed, refused by
  the consistency check after every reference was written (`ReferenceCardinalityViolatedException`), or a unique value -
  and rows, objects, primary keys and membership must be as before.
- **T6, the existing suites, now on rows:** the whole functional suite; `LongRunningEvitaReferencesGenerationalTest`,
  which checks three `referenceHaving` shapes per product against a model of the rows the test wrote, every generation
  and after every reload (both counterfactuals red in generation 1); `EvitaBackwardCompatibilityTest` (below).
- **T7, persisted storage through the public statistics:** entity storage-part counts against a history-free rebuild of
  the same final bodies (`STORAGE_COMPOSITION`), and the stored index parts against `INDEX_SUMMARY`.
- **Lifecycle and contract suites:** `VirtualPartitionSingletonLifecycleTest` and
  `VirtualPartitionPromotionLifecycleTest` (on the one-engine harness `VirtualPartitionHistory`; the latter keeps
  `shouldDropAPartitionBothOwnersLeaveInOneRootMutationFromTheMembership`, the regression test of `5f7588e478`);
  `VirtualPartitionSortValueTest`; `VirtualPartitionIndexTypeChangeTest` for the indexing-level rules, both directions,
  both scopes, WARM_UP and ALIVE, reflected inheritance; `VirtualPartitionFormulaCacheTest`;
  `VirtualPartitionStatisticsTest`; `IndexInstallRetireTest` and `DataStoreChangesIndexRetirementTest`;
  `OtherOwnerReferenceRowVisibilityTest` and `LocalMutationExecutorCollectorInFlightRegistryTest`;
  `ReducedIndexMembershipCompletenessTest` (rows too, with a WARM_UP rollback case);
  `ReferenceHavingEntityLocaleFunctionalTest` (locale bodies against the entity bodies, rows and objects);
  `InScopeOverAnEmptyScopeWithRegistryRowsFunctionalTest`.
- **Migration suites:** `Migration_2026_3_VirtualPartitionsTest` (every read route equal to a catalog born with rows;
  inherited reflected indexing; every sort type and chain; an object of filtering-only content; a protocol-6 image
  holding a registry row refused; no file left open by a failed migration; nothing published by a failed publication
  under low compaction thresholds; re-key + conversion and rewrite + re-key + conversion of one collection with failure
  and retry; the WAL tail; intermediate flushes); `StorageProtocolRefusalTest` (a header of an unknown protocol refused
  promptly, the files byte-identical after the refusal); `Migration_2026_3_OwnerSortRewriteTest` and
  `LegacyOwnerSortIndexCorrectionTest`, on
  2026.2 pages hand-built by `LegacyOwnerSortIndexWriter` (temporal-only collections inline and paged, released
  collation, locale and `DateTimeRange` orders, a pair straddling a page boundary, stale twins refused, merged buckets
  left merged).

### Evidence from the development twin (not reproducible after the cleanup)

- **Twin engines.** Two engines were fed identical mutation histories, one keeping objects and one writing rows, and
  every index of both was compared at every checkpoint, including failure injection after every transition stage in STM
  and WARM_UP, churn shapes (remove + re-insert, 2 → 1 → 2 in one mutation), reflected promotion, relocation, scope
  moves, entity removal and WAL replay.
- **Forced full suite.** The serial suite ran twice, default and forced onto rows: 28,760 tests each, identical failing
  sets (12 environmental). In the forced run 1,368 tests reached a row route and 672 read rows, and after every
  committed root mutation the run asserted invariant 2.
- **Operation-count gates**, for k = 300: Θ(k) registry operations and 0 index objects for an owner of 300 single-owner
  partitions; each foreign references part read once per root mutation; at most ⌈log₂ n⌉ + d comparisons per
  representative-key resolution. M3 below is the regression guard.

### Counterfactuals, each run and watched fail

| broken | result |
|---|---|
| conversion classifies a reflected reference by its declared, not inherited, indexing | 4 archived partitions left as objects: "expected <9> but was <5>" |
| conversion publishes per collection | the crash test finds the bootstrap file's bytes changed |
| demotion's remaining owners read after the retirement (`5f7588e478` reverted) | ALIVE membership keeps covering a vanished partition |
| the hybrid flipped off | `ReducedIndexMembershipCompletenessTest` forced: 35 of 35 red |
| retiring the leftover object at 1 → 0 skipped | 8 of 22 red |
| the promotion executor's registry gate dropped | 53 of 53 promotion cases red (invariant 4) |
| the refusing branch at the end of the upgrade loop dropped (run 2026-10-19, after the merge into protocol 7) | a header of an unknown protocol passes the format check, and the open fails later on the bootstrap record with `StoredProtocolVersionNotSupportedException` instead of the refusal |
| the owner sort rewrite skips `Locale` elements | paged: "Bulk-loaded keys must be strictly ascending and distinct"; inline: "expected: <[2, 1, 4, 3, 6, 5]> but was: <[1, 2, 3, 4, 5, 6]>" |
| pages checked by today's order instead of the release's | valid pages refused: "… are not in ascending order" |
| the conversion of one reference (`variants`) skipped | `EvitaBackwardCompatibilityTest` red on 2026.2: 3,164 single-owner partitions, 0 rows |

### Reviews and gates

- **Codex.** A plan vet (GO WITH CHANGES; two of its findings became the cross-entity read and the buffer contract), a
  fenced review of every phase breakdown and implementation (at most two rounds each), and of the cleanup: the plan
  (r1 NO-GO, eleven findings all verified and folded, among them the original-bootstrap refusal and both owner
  decisions D2/D3; r2 NO-GO on the unchanged plan, four amendments folded at the round cap), then every development
  piece - the 6 → 8 migration (a service left open by a failed upgrade, fixed in `81ddbf32bd`; then GO), the temporal
  rewrite (pages checked by today's comparator; then GO), the switch removal (the hierarchy option over rows, fixed in
  `d77a8f24fc`; then GO), the collation and locale re-sort (`Locale` elements not selected and wrongly ordered by the
  released comparator, fixed in `cfe4e9c209`; then GO). The phase-5 implementation rounds found the memory-gate defect
  (`0f84debccc`) and the membership defect (`5f7588e478`).
- `/dev-tools:code-quality-pipeline` after each phase.
- **Functional suite** after the switches were removed (`d77a8f24fc`): 28,706 tests, the only failures the 12
  environmental ones (11 `EvitaServerTest` cases with hidden certificate keys, `ExportS3ServiceTest` without Docker).
- **`kryo-bwc-audit`** against `release_2026-2`: no findings. No marker left by the branch.
- **Final gates at the PR head** (`6ab95ee1a5`, the code this record ships with):
  - the full functional suite: 28,738 tests, the only failures the same 12 environmental ones;
  - the long-running suite (the eight classes exercising reference partitions, the WAL and the index generations,
    plus `EvitaBackwardCompatibilityTest`): 32 tests, all green;
  - javadoc of `evita_api`, `evita_engine` and `evita_store_server`: no warning on a line of the cleanup;
  - `/dev-tools:code-quality-pipeline` over the cleanup range `66e35edb98^..HEAD`: one defect fixed red → green (a
    successful upgrade left the services it superseded open, `6ab95ee1a5`), `isShadowed` renamed `isWrittenVirtually`,
    seven tests added;
  - Codex over the same range, which also covers the `DateTimeRange` re-sort (`7c0a1403c2`): GO, no findings.
- **`EvitaBackwardCompatibilityTest`** converts the five released demo datasets (2025.1, 2025.3, 2025.6, 2026.1, 2026.2)
  through 6 → 7 and checks, per dataset: the header on disk says 7; every filtering-only reference keeps one row per
  single-owner partition and one object per shared partition, as the bodies derive them;
  `referenceHaving(ref, entityPrimaryKeyInSet(t))` answers the owners the bodies name for up to 50 targets per reference
  and scope; a restart runs no migration and answers the same. 2026.2: 49 filtering-only reference/scope pairs, 12,400
  rows and 8,198 objects, 592 targets; 2026.1: 2,036 rows and 3,088 objects, 741 targets; 2025.1 and 2025.3 hold no
  filtering-only reference. 2025.6 is characterized, not strict (568 of 570 checked targets short; see the 2025.x
  follow-up). No demo dataset holds an owner sort index the rewrite selects: it read 0 and rewrote 0 on all five.

### Performance

All on the production retail corpus: 442,609 entities, 130,033 `Product` LIVE + 40,344 ARCHIVED, 317,446 single-owner
partitions of filtering-only references. Arms were interleaved, each run behind a load gate, on the development machine.

**The handle alone is neutral.** `ReferencePickFirstSortBenchmark`, dev `07d462ffa` against `b7650e7d9`, 3 interleaved
rounds: sorted configurations geometric mean B/A 1.003, the rig's unordered controls 0.981, no configuration moved the
same way by more than 5 % in all three rounds.

**The default path does not regress (M1).** Both arms hold index objects: dev `aa3e7b281b` against `f191934b98`:
pick-first `categories` 0.985, `media` 0.997; catalog load 0.999, heap after GC 0.995; writes too noisy to bound.

**Bulk reindex.** WARM_UP, the full corpus, one writer JVM:

| writer | load wall-clock |
|---|---|
| last release (v2026.2.20) | 1,514.8 s |
| dev | 1,321.2 s |
| this branch | 1,246.1 s |

The branch wrote index objects there. A full reindex into a catalog with rows was not measured; on capped copies the
WARM_UP rebuild was 1.018 rows/objects (254.8 → 259.5 s).

**Rows against objects (M2).** Full corpus, jar `f191934b98`, a copy born with rows and one born with objects by the
same writer, 3 interleaved rounds, result cache off:

| | rows / objects |
|---|---|
| unnarrowed `referenceHaving` on `Product.media`, 3 shapes (`attributeEquals`, `attributeIs` NULL / NOT_NULL) | **0.021** (157-227 ms → 2.6-4.5 ms) |
| pick-first `Product.media`, 10,000 seed targets (9,762 owners) | 0.32-0.34 |
| pick-first `Product.media`, 100 / 1 seeds | 0.58-0.70 / 0.82-0.995 |
| pick-first `Product.categories` (indexed for partitioning, so objects on both sides - a control) | 0.920 |
| catalog load (open) | 0.881 (29.8 → 26.2 s) |
| **retained heap after GC** | **0.805 (6,904 → 5,561 MB, −1,344 MB)** |
| disk | 0.960 (16,027 → 15,392 MB) |

- `REFERENCED_ENTITY` index objects: LIVE 503,043 → 244,004, ARCHIVED 70,663 → 12,256; 317,446 rows and 807,683
  registry entries.
- **The answers were checked, not assumed.** Route and record-count lines matched between the copies in every round;
  the `referenceHaving` counts matched an oracle computed from owner bodies (IS_NOT_NULL 53,542 owners).
- No configuration was slower by more than 5 % in every round.
- **Not measured on rows:** unnarrowed `referenceHaving` on `Category.products`; hierarchy statistics; `groupHaving`
  over many groups; traverse over `parameterValues`; pick-first cold, after a write, on deep pages and descending;
  allocation and planning share per query. "No configuration was slower by more than 5 % in every round" covers only
  the configurations above.

**Writes on rows (M3).** One operation per run on a fresh reflink copy, rows against objects. Full corpus: single shots
are bimodal, so only the direction is readable - CREATE_0_1, DEMOTE_2_1, LOCALE_GAIN and RELOCATE were faster on rows in
every round, nothing slower in every round, REMOVE_ENTITY unresolved (0.666-1.936). Capped copies (about 12 % of
products), geometric mean 0.936: PROMOTE_1_2 0.994, DEMOTE_2_1 0.942, RELOCATE 0.959, REMOVE_ENTITY 1.018, LOCALE_GAIN
1.003, DROP_1_0 0.821 and LOCALE_LOSE 0.924 (faster in every round); promotion and demotion take 25-30 ms either way.

**The three criteria left open, measured for information rather than as a gate** (2026-10-11, jar `5f7588e478`, the M2
copies; host load 2.0-2.8 during the conversion, 4.4-8.1 during C2):

- **C1 — gross cost of a row against the object it replaces** (criterion: > ~10 % of the shell): **65 B per registered
  partition** (25 B per registry entry; registry 19.6 MB in the engine's heap model) against **3,917 B per replaced
  object**, a ratio of **0.016**, also when the model's unmodelled remainder is booked on the rows. Net: model −1,166
  MB, measured −1,348 MB (6,908 → 5,561 MB after GC).
- **C2 — formula-cache reuse on the hot shapes** (criterion: lost reuse): none. Under thresholds removed, the row
  `referenceHaving` (EQUALS, IS_NOT_NULL) hit the cache once per query where objects looked up and missed; under the
  shipped thresholds neither reached it; IS_NULL reached it in neither. Pick-first hit and missed alike, except 10,000
  unordered seeds, where only rows hit. Rows/objects latency 0.015-0.033 for `referenceHaving` under every profile;
  cache on against off 0.87-1.09 in both representations.
- **C3 — the conversion of the full corpus** (the conversion alone, from a copy of objects, at `5f7588e478`):
  - **median 24.1 s** (23.4 / 24.3 / 24.1) for 317,446 partitions converted, 251,459 shared and 4,801 partitioned kept;
    568,905 partitions read, 32 registries, 123 flushes at the threshold of 10,000, at most 9,960 records (1.7 MB)
    unflushed; 28.7 MB appended;
  - **live set 274 MB** (forced full GC every 5,000 checkpoints); heap after GC at most 1,159 MB at `-Xmx24g` (an upper
    bound); **completes at `-Xmx2g`** in 23.6 s with no full GC - against 6,908 MB to run the catalog;
  - the converted copy, opened by the engine, equals the copy born with rows in every round (244,004 / 12,256 objects,
    317,446 rows, 807,683 entries, no single-owner object; heap after GC 5,559 vs 5,561 MB).
  - Not measured: the re-keys and the owner sort rewrite of a real 2026.2 catalog of this size together with the
    conversion. **The cost of the owner sort rewrite on real localized data is unmeasured**: no dataset available holds
    such an index.

### The partition population (census, 2026-09-30)

The numbers in *Why* came from two throwaway probes - a partition census walking every reference's partitions and
splitting their model heap by component, and a churn census walking the mutation history - deleted with the plan once
the numbers were recorded. The heap model was calibrated first: settle the heap, read it, sum
`EntityIndex#getHeapSizeInBytes()` over every index, close the engine, settle and read again; two runs measured
6.25 / 6.24 GB against a modelled 5.54 GB both times (0.888, never above). The aggregate cannot prove the *split*
between kinds of index; two reviews auditing the sizers to the leaves ruled out cross-partition double counting.

On catalog version 8103 of the production retail corpus:

| reference | single-owner partitions | heap | sort shape |
|---|---|---|---|
| `Product.media` LIVE | 168,847 (99.9 %) | 632.5 MB | `mediaOrder` Integer, 77 distinct values; blocks: median 44, p95 15,411, largest 53,388 (31.6 %) |
| `Product.media` ARCHIVED | 52,817 (99.6 %) | 197.9 MB | 70 values, largest block 19,171 |
| `Category.products` LIVE | 45,212 (51.5 %) | 198.3 MB | filter-only, reflected from `Product.categories` |
| `Parameter.parameterValues` LIVE | 29,272 (100 %) | 97.7 MB | `ReferencedEntityPredecessor` chain; up to 5,520 partitions per owner |
| `Product.relatedProducts` LIVE | 4,738 (35.6 %) | 17.8 MB | 19 values; representative `category`; 2,098 owners mixing rows and objects |
| `Group.media` LIVE | 2,391 (100 %) | 9.0 MB | 16 values |

- **The heap of a single-owner partition**, `Product.media` LIVE: 3,927 B. Of that, owner 208, locale bitmaps 640, sort
  1,191, filter + unique 1,232, facet / hierarchy / price shells 296, the rest 360.
- **Representative tuples:** `gallery` is representative on every `*.media` reference - one string, at most 12
  characters, 5 distinct values over 168,847 partitions.
- **Row ↔ index consistency:** every owner of a single-owner partition was fetched and matched to its partition; per
  key, "the row has a value" equalled "the index holds it" - 0 row-only, 0 index-only.
- **Churn** (versions 7380-8103, 206,009 captures, 42 minutes): `Product.relatedProducts` 7,564 inserts and 7,564
  removes, every one a remove + re-insert of the same owner and target in one version - 258 / 284 / 284 / 258 crossings
  of 0 → 1 / 1 → 2 / 2 → 1 / 1 → 0, zero net; `Product.tags`, `stocks` and `stockVisibilities` a few dozen writes, no
  crossing; `Product.media`, `Parameter.parameterValues` none.

**Rebuilding them.** Neither probe survives; this is what a re-run needs.

- **Where they lived.** `SingleOwnerPartitionCensus <storageDir> <catalog> [--entities]` and
  `ReferencePartitionChurnCensus <storageDir> <catalog>`, in package `io.evitadb.index` of `evita_performance_tests`.
  That module has no `module-info`, so the split package is legal on the class path and could read `EntityIndex`'s
  protected sub-indexes without reflection or a new API. Both opened a **copy** of the catalog and never wrote to it;
  `-Dcensus.compress=true` opened a compressed one.
- **Partition census.** Per reference × scope whose index type is not `NONE`, over the `REFERENCED_ENTITY` family only,
  it walked `ReferencedTypeEntityIndex#forEachReferenceIndexPrimaryKey` together with
  `EntityCollection#getIndexByPrimaryKeyIfExists`, counted each partition's owners, and split the model heap of every
  single-owner partition into owner bitmap, locale bitmaps, attribute index (and of it the sort and chain indexes),
  facet, hierarchy, price and the remaining shell. It also reported, per sort and chain key, the presence in
  single-owner partitions, the distinct values and the value-block distribution; the owners holding only single-owner
  partitions, only shared ones, or both; single-owner partitions per owner (p50 / p95 / max); distinct representative
  tuples, rows per tuple and distinct characters; and totals split by whether the reference has anything sortable in
  the scope.
- **`--entities`** fetched every owner of a single-owner partition (`referenceContentWithAttributes`, all locales),
  matched each row to its partition by target and representative values, and compared per key "the row has a value"
  with "the index holds it".
- **Churn census.** It took the final owner count per `(collection, reference, target)` from the reduced indexes,
  summed over scopes, then walked `getMutationsHistoryReversed` (body content) newest first: an
  `InsertReferenceMutation` means the count was one lower before it, a `RemoveReferenceMutation` one higher. It
  classified every step by the boundary it crossed and counted removes re-inserted for the same owner and target in
  one version.
- **Running them.** `mvn -o -P full -pl evita_test/evita_performance_tests -am compile -DskipTests`, then
  `java -Xmx24g -XX:+ExitOnOutOfMemoryError -cp <classpath> io.evitadb.index.SingleOwnerPartitionCensus <storageDir>
  <catalog> [--entities]`, and the same for `io.evitadb.index.ReferencePartitionChurnCensus <storageDir> <catalog>`.
  The class path is every module's `target/classes`, plus `mvn dependency:build-classpath` of `evita_store_server` and
  of `evita_export_fs` without the external-API modules (they pull in OpenTelemetry, which that class path lacks), plus
  the `hppc` jar - without it the load fails with `NoClassDefFoundError: com/carrotsearch/hppc/LongSet`, so a probe must
  report an unusable catalog rather than wait for it to load. The corpus opened in 13 s at `-Xmx24g`.

Limits to carry into any re-run:

- **Folded keys in the churn census.** A partition was identified by its target alone, so the representative partitions
  of one target fold into one count: a transition between two of them is invisible, and a row joining a target that
  already has a partition under another tuple counts as 1 → 2. It matters only where targets carry several partitions:
  `Product.relatedProducts` (1.099 partitions per target LIVE, 1.028 ARCHIVED).
- Scopes were summed, so a scope move is not a crossing; whole-entity removals carry no reference list (the window held
  none); the dominant shapes saw no write.
- **Both probes enumerated physical index objects.** A re-run that does not also count rows reports the single-owner
  population as gone (see *Hazards*).
- **The tuple bound is one convention.** "1-5 representative tuples per reference" holds on every evitaDB dataset
  available: two snapshots of the production retail corpus, a second, smaller production e-commerce deployment (two
  catalogs) and a demo e-shop (two catalogs). All share one modelling convention, so this is one convention sampled
  several times, not independent evidence.

## Consequences & open follow-ups

### What it costs, and who notices

- **A stored catalog is upgraded once,** on the first start of 2026.3, by the 6 → 7 migration. The conversion takes time
  proportional to its single-owner partitions (24 s for 317,446, C3). If the process stops, the catalog is left as it
  was and upgraded again. The upgrade note on #1615 says this.
- **Catalogs written by development builds at protocol 7 are not told apart** (since `85d3b2c039`, 2026-09-21): they
  load without a migration and are unsupported - their owner sort indexes keep the form and order of 2026.2 and their
  single-owner partitions stay index objects. Recreate them. One holding raw temporal sort values loads, serves the raw
  order and refuses a write into the index with a `ClassCastException`
  (`Migration_2026_3_OwnerSortRewriteTest.UnsupportedDevelopmentCatalog`). Catalogs of protocol 8, which builds of this
  branch wrote before 2026-10-19, are refused as an unknown protocol. No upgrade note, since no release wrote either.
- **Changing a populated reference from `FOR_FILTERING` to `FOR_FILTERING_AND_PARTITIONING` now needs a reindex** before
  writes that add to its single-owner partitions, and before queries reading partition-local content from them. Both
  fail loudly with `ReferencePartitionReindexRequiredException`. Before, such a change was accepted and the existing
  partitions silently lacked entity attributes and prices.
- **Merged buckets of 2026.2 stay merged** (decision B): an entity filed under the other collation-equal spelling,
  locale of one tag or range equal at whole seconds is missed by equality on its own value and cannot update or remove
  that attribute; rebuild the catalog from source data. The upgrade note on #1615 says so.
- **Stale language entries.** Before `ff47c871cb` an owner's locale was written into every partition of its target,
  sibling partitions of duplicate-allowing references included. The migration converts single-owner partitions but does
  not clean shared objects, and after the fix a leaked entry is no longer removed when the owner loses the locale. The
  maintainer chose an upgrade note on #1615 over a cleanup in the migration.
- **Every plain `Locale` owner sort index is read in full by the migration**; it is written only when its persisted
  order is not current, which a plain index practically never is.

### Coverage knowingly lost at the cleanup

Accepted by the maintainer on 2026-10-11:

- **L1:** the per-commit representation check across the whole suite; now only the lifecycle suites check T1, at their
  own checkpoints.
- **L2:** failure injection after every transition stage. T5 reaches only the end of a root mutation, through a
  consistency check that precedes the finalization, so a demotion is never failure-tested.
- **L3:** the operation-count gates. Their bounds are recorded above; M3 is the guard, with no standing benchmark.
- **L4:** the twin's comparison of type-level cardinalities, standalone unique mappings and session enrollment - dev's
  structures, which this work does not change.
- **L5:** the closed-world entry count of the registry. T2 is closed-world over keys, but a surplus sort-value or chain
  entry under a valid key is invisible; `ReferencedEntityPredecessor` chains stay outside T2 (#1691).
- **L6:** the per-test non-vacuity listing of the functional suite; its last numbers are above.
- **L7:** runtime evidence of which consuming stage answered rows. Five of six stages have an answer-level
  counterfactual; the exact-set lookup cannot (a row has one owner, and `attributeSetExact` sorts a single partition).
- **L8:** the runtime premise of the by-key owner-read resolver (see *Things that will look like bugs*).
- **L9:** persisted bytes per storage-part type; T7 compares counts.
- **L10:** the conversion's memory gate (one partition and one registry reachable at a time), deleted with the observer;
  C3 is the evidence, and the code it guarded is frozen and removed with the migration.
- **L11:** a `uniqueGloballyWithinLocale` lookup walking into ARCHIVED; dev's fixture declares the localized attributes
  unique in LIVE only.

Found later in the cleanup and accepted by the maintainer on 2026-10-12:

- The refusal premises of `IndexMaintainer#installIndex` and the creation refusal of partition objects
  (`EntityCollection#assertPartitionObjectMayBeCreated`) have no public path and no test. No cheaper assertion would
  cover them: the guards already are constant-time checks, and what is missing is an input that makes them fire, which
  nothing public produces. Their consequence - a partition both a row and an object - is what the lifecycle suites
  check.
- A reload does not reject a representative-tuple entry orphaned without its owner row, and the oracle looks for such
  entries only up to the highest primary key it knows. Every reader reaches a tuple through its owner row, so an orphan
  costs bytes, not answers.

### Open follow-ups, not filed

- **F1 — the prefetch premise is stricter than it needs to be after a raise.**
  `PrefetchFormulaVisitor#hasNoVirtualPartition` asserts that the **whole** registry of the reference in the scope is
  empty. After a raise to `FOR_FILTERING_AND_PARTITIONING` with surviving rows, a prefetch narrowed to the reference's
  reduced indexes fails with an internal error even when the narrowed set holds no row and the query could have been
  answered. It is loud, so it keeps the "never silently wrong" rule, but it fails answerable queries. Checking only the
  narrowed set's own partitions would fix it.
- **F2 — `IndexSelectionVisitor#resolveAllPartitionsOfNodes` is installed only when it cannot answer.** It is the
  hierarchy option's index supplier only when the node partitions include a row, which has no index object. For a
  duplicate-allowing reference it throws; for any other it silently yields nothing for the row. Nothing calls it today;
  a future consumer would silently miss rows. Making it throw would close it.
- **The index-type raise defect, deferred to #409 (maintainer, 2026-10-11).** After a raise to
  `FOR_FILTERING_AND_PARTITIONING`, an index object written under `FOR_FILTERING` holds no entity attributes, and the
  planner, deciding from the schema alone (`IndexSelectionVisitor#allIndexesArePartitioned`), offers it as a partitioned
  alternative: `hierarchyWithin` + an entity-attribute filter answered `[7]` before the raise and `[]` after it. Dev
  behaves the same. Rows are not affected (see the indexing-level section).
- **Catalogs created by 2025.6 or earlier answer `referenceHaving(ref, entityPrimaryKeyInSet(...))` with no owners -
  not filed, because no catalog of that age is in use (maintainer, 2026-10-12).** Since #906 (`ee0def7167`, 2025.7)
  the type index keeps partition primary keys in its own primary-key bitmap, but `Migration_2025_6` copies the old
  bitmap of target keys verbatim, and no later migration rewrites it. The translator ANDs its result with that stale
  superset. `EvitaBackwardCompatibilityTest` pins the loss as a characterization for 2025.1, 2025.3 and 2025.6 and goes
  red the day the bitmap is repaired. Folding the repair in was declined as too large for a fold-in: catalogs written
  on by 2025.7 or later hold a mix of target and partition keys that cannot be told apart, so the bitmap must be
  recomputed from the partitions themselves. The 6 → 7 migration is its natural home, should such a catalog appear.
- **`DateTimeRange` values 2026.2 merged below the second - documented, not repaired (maintainer, 2026-10-12).**
  `9a3e90c93d` (dev, not in 2026.2) compares `DateTimeRange` at milliseconds rather than whole seconds, with one
  constant for an open bound. Its *order* is now covered: a probe of 2026.2-shaped data through the 6 → 8 migration (at
  `cfe4e9c209`) found that a filterable `DateTimeRange` attribute, paged or inline, and a plain paged sort-only one load
  and answer correctly, thanks to dev's load-time re-sort, and the three shapes that broke - an inline sort-only index
  silently misordered, a paged `[DateTimeRange, Integer]` compound failing to open, a paged
  `[DateTimeRange, OffsetDateTime]` compound refused by the migration - are fixed by the owner sort rewrite
  (`7c0a1403c2`, above). What remains is *precision*: 2026.2 merged values that differ only below the second into one
  filter-index bucket and one range threshold, and the index data cannot tell them apart. The effects are wrong
  `attributeEquals` / `attributeInRange` answers at sub-second level, "Sanity check - record not found!" when a value
  the release merged into another's bucket is removed, and ghost points left in the range index; the same mechanism
  applies to price validity. Values written through gRPC are not affected - 2026.2's gRPC dropped fractions of a
  second - but the embedded API kept them, and so did GraphQL, which parsed bounds with `ISO_OFFSET_DATE_TIME`. The
  upgrade note on #1615 describes the effects and the repair, a re-import into a fresh catalog.
  - **Declined — rebuild the affected indexes from the entity parts in the migration**, behind a gate that scans the
    attribute and price parts for a bound with a non-zero millisecond part.
    - **Rejected because:** the probe estimated it at 200-400 production lines plus tests built from hand-made 2026.2
      parts, for values that exist only where a range bound carries fractions of a second.
    - **Revisit if** a stored catalog with such values is reported.

  Related, pre-existing and out of scope: two open ranges whose closed bound is one instant at two offsets collide on
  the cardinality key of an inline index, and a delta-encoded part then fails to load with "Sort index damaged! The
  per-value block lengths sum to …".
- **The writer's handling of object targets** (Q6): a write into an index object is handed to
  `VirtualReferencePartitionWriter` too, where it never finds a row. Remove it with its own proof - a premise that an
  object of such a reference has no row outside the promotion window.
- **The membership's residual cost.** `ReducedIndexMembership` is unchanged, and rows are ordinary covered entries. This
  work did not address the narrowed small-selection cost the 2026-09-23 record attributes to residual partitions.
- **A traverse singleton-block fast path** was deferred: no row provider adds a family scan, which a gate enforces.
- **The statistics walk boxes one `Long` per row.** `BucketCursor` has no primitive key accessor, and adding one changes
  the tree interface.
- **A per-link unique representative value** makes the tuple dictionary grow and rewrites the registry root per new
  tuple; revisit with such a dataset.
- **#1723:** once `inScope` for another scope stops setting the ordering locale of live references,
  `FilterInScopeTranslator#applyLocalesToNestedQueryComparator`, the row path's copy of that behaviour, must follow.
- **#1678's interaction with the in-flight read is unproven.** The registry hands out an executor's staged parts - the
  instances the buffer traps and a savepoint restores by content. No test found when this record was written proves
  that a nested change rolled back by a savepoint is also rolled back in what the registry serves to a later read of the
  same root mutation; `LocalMutationExecutorCollectorInFlightRegistryTest` proves only that nothing outlives a failed
  root mutation. The comment on #1678 says so.
- **Seen, not verified:** 2026.2 dropped the script and extensions of a stored `Locale` (`zh-Hans` reads back as
  `zh`), so such an owner sort index may be refused (paged) or merged (inline) by the rewrite - untested; the prefetch
  assertions of `TemporalSortValueFunctionalTest` are probably vacuous (pre-existing). And a 2026.2 owner sort index
  with a `Locale` element exists only in a catalog never restarted on 2026.2, whose `Locale` reader could not read its
  own bytes (`4cdb6aab28`), so that rewrite's reach is narrow. `facetHaving` evaluates its body `IN_PLACE`, where
  `EntityLocaleEqualsTranslator` still answers `entityLocaleEquals` on the type index with a `SkipFormula`, so it may
  skip the leaf and narrow an enclosing `or`, the way candidate discovery did before `a2b5e43c7a`.

### For #409 (schema alteration with rebuild)

- Its drop task must retire the registry's declared sort and chain keys as well as the index objects.
- Its rebuild covers rows only if it goes through the ordinary indexing path.
- Once it rebuilds every index of a reference for the reference's current indexing, the reindex failures of the
  indexing-level section disappear, and so do the accepted drift of partitioning-era content and the index-type raise
  defect above.
- It changes the leftover rule the conversion test's generator relies on, so it must bring its own generator.

### Found along the way and filed, not fixed here

- **#1678** rolled-back nested change surviving in the buffer - fixed separately by PR #1724
  (`2026-10-08-trapped-storage-part-content-pre-images`).
- **#1679:** server-side reference cardinality checks are dead.
- **#1685:** reduced indexes of duplicate-allowing references receive owner data of variants the owner does not belong
  to.
- **#1691:** `ReferencedEntityPredecessor` is indexed into target-grouped partitions. Rows reproduce the object path's
  behaviour; a demoted row lacks the shared predecessor, pinned by
  `VirtualPartitionPromotionLifecycleTest#shouldDemoteWithoutTheReferencedEntityPredecessorBothOwnersShared`, which must
  flip with the fix, and T2 leaves those chains out.
- **#1696 / #1699:** 2026.2 catalogs misbehaving after the upgrade, and range borders shared by two owners - their own
  PR, built on a single generic rebuild step in `Migration_2026_3`. This work carried a migration of targeted repairs
  for them until 2026-10-06, when its estimate had grown sixfold through review rounds that kept finding pre-existing
  engine defects (those became #1709).
- **#1708:** condition semantics of conditional facets and histograms.
- **#1709 + draft PR #1710:** compaction and collection relabelling can publish a catalog that no longer loads.
- **#1723:** the `inScope` locale quirk.
- **#1733:** conditional facet re-evaluation, 14 % of CPU and 39 % of allocations of a bulk load.
- **#1734:** a conditional facet expression of a reference added in ALIVE is lost at commit.
- **#1735:** `attributeSetExact` inside a traverse ordering over several blocks.
- **#1736:** a localized attribute of a referenced entity fetch is refused when its locale comes from the reference
  filter or `dataInLocales` (`AttributeContentTranslator#verifyAttributes` checks top-level locales only).
- **#1737:** internal keys drawn inside a transaction - partition primary keys among them - are drawn again when the
  trunk replays the WAL and on crash recovery. T4 compares crash recovery modulo the keys it re-mints.

## Related work

- **`2026-10-20-reference-content-filter-row-by-row`:** the filter of `referenceContent` is evaluated over the fetched
  owners' rows, so the fetch no longer reads a partition, a row or an index object of the reference; it removed the
  `referenceContent` consumer of the registry rows (`VirtualPartitionRows.InScopeReading`,
  `VirtualPartitionBodyEvaluator#getMatchingPartitionsPerRow`) and the parity test that compared them with index objects.
- **`2026-09-23-pick-first-reference-ordering-from-selection`** (#1614): the sort path this work depends on. The row
  pick-first run plugs into its claim-by-bitmap sorter; the chain gate it handed to #1615 is met (`a03e8d0445`); its
  localized-compound bullet was fixed first (`9434938ab8`), because promoting a partition would otherwise have changed
  ordering.
- **`2026-09-17-row-scoped-reference-having-body`:** a row body keeps its row semantics by construction, because one
  partition primary key is one row. Its O(family) transposer walk is what a per-partition tagged formula would have
  kept.
- **`2026-09-18-reference-planning-from-owner-membership`** and **`2026-09-09-sibling-resolver-partition-cardinality`:**
  the owner → partition membership that rows join as covered entries (fork 3). The issue asked which of the two would
  claim the per-partition fan-out of a bare `referenceHaving`; this work did, for rows.
- **`2026-09-25-attribute-is-null-in-reference-having`:** its deferred option C (skip single-row partitions for
  `attributeIs(NULL)`) is superseded for single-owner partitions, which are now answered from the type level.
- **`2026-10-08-trapped-storage-part-content-pre-images`** (#1678): restores trapped parts by content; the cross-entity
  read exposes those same parts to later reads of the root mutation.
- **`2026-08-26-warm-up-per-entity-mutation-atomicity`:** the savepoint bracket every transition runs inside. A failed
  root mutation discards the reservations with its collector.
- **`2026-09-21-cardinality-counter-normalized-keys`** and
  **`2026-10-08-unique-indexes-normalize-like-filter-indexes`:** the two earlier steps of `Migration_2026_3`, which this
  work extends with the owner sort rewrite and the conversion, keeping one step from 6 to 7; decision B repeats the
  former's accepted migration hazard for owner sort indexes.
- **`2026-09-04-millisecond-temporal-precision`:** this work made sort values uniform millisecond instants in every sort
  index (`df4a8e2606`), which the registry's value normalization relies on; the owner-sort half of that record's
  load-time repair now lives in the 6 → 7 migration.
- **`2026-08-10-catalog-and-collection-statistics`:** the index cardinality statistic that `virtualPartitionCount`
  joins.
- **Issues:** #1607 (multi-owner partitions stay objects, with their locale bitmaps; if they are ever derived, reuse the
  owners bitmap when every owner has the locale, never derive owners ∩ the global bitmap inside a cached formula);
  #1677 (prices never live in a filtering partition; the formula-cache constraints of this work came from that
  research); #1670 (traverse routes still disagree; the row traverse adds no rule of its own); #409 (above).

### Carried by the same PR, explained by their own commits

The PR also closes **#1693** (`150be70492`: a point-in-time backup stamps the backed-up version's protocol), and
carries pre-existing defects found along the way, each its own `fix:` commit:

- **Locales and localized compounds in reduced indexes:** `9434938ab8`, `6b27d56d92`, `d98ac22bd1`, `f9b37216ac`,
  `e0b3841458`, `ff47c871cb`, `a2b5e43c7a`.
- **`Locale` and `Currency` values:** `4cdb6aab28`, `b74dbacc4f`, `85e314600a`, `2eef0755af`.
- **Index route and prefetch route ordering the same values alike:** `7a7e502993`, `d98a21ddf3`, `6a3c164531`,
  `cd2c69683c`, `df4a8e2606`.
- **Unique lookups:** `206b81eaff`, `ba259de78f`, `3c2fca93d9`.
- **Storage and transactional trees:** `dc92c5fd4b`, `f34a3544ef`, `3a74e7a99a`.
- **Temporal input and identifier sequences:** `58916f5a1c`, `7a3cc542dc`, `fe12d1598c`.
- **Ordering and fetching of `referenceContent`:** `3257bd715c`, `4458411159`, `fc834716f6`, `172786c53a`, `3edf4a03ce`,
  `430c262b83`.
- **Concurrency:** `dab2a4d0e0`.

Two further user-facing notes are on #1615: rebuild catalogs with a localized sortable compound on a `FOR_FILTERING`
reference, and reindex range compounds stored at the range's own scale.

## Timeline

- **2026-09-19:** #1615 filed with the heap calibration and the first census.
- **2026-09-30:** census and churn census; two independent designs; forks 1-3 decided; plan vetted; phase 1 (the handle,
  the resolvers, the access guard) started.
- **2026-10-01 – 2026-10-05:** the buffer contract and the registry; the cross-entity read; demotion by extraction;
  rows maintained alongside objects, with the twin harness and the oracles.
- **2026-10-06:** the targeted-repair migration split out to #1696; the read path started.
- **2026-10-07:** read path complete; the hybrid, no refusal, the protocol as marker, one migration decided.
- **2026-10-08 – 2026-10-10:** rows written by default in a catalog born with them; the conversion; dev merged in; the
  forced and default full suites; M1-M3 on the full corpus.
- **2026-10-11:** C1-C3 measured; the cleanup - every new catalog born with rows, the twin, the switches, the probes and
  the switch holder removed, the one 6 → 8 migration with the protocol-7 refusal, the observer deleted, the temporal
  correction moved into the migration, the localized re-sort.
- **2026-10-12:** the `Locale` and `DateTimeRange` re-sorts; developer documentation; this record.
- **2026-10-19:** manual review; protocols 7 and 8 merged into 7; the access guard removed.
- **2026-10-20:** the writer guard removed.
