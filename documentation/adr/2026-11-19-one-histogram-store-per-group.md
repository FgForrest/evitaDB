---
title: One histogram store per group
date: 2026-11-19
updated: 2026-11-19 15:30
status: accepted
kind: fix
issues: [1924]
prs: [2001]
areas: [evita_engine/src/main/java/io/evitadb/index,
  evita_engine/src/main/java/io/evitadb/core/collection,
  evita_engine/src/main/java/io/evitadb/core/query/extraResult,
  evita_engine/src/main/java/io/evitadb/core/transaction, evita_store/evita_store_server]
supersedes: []
superseded-by: []
relates: [2026-11-11-reference-histogram-net-change-per-run,
  2026-11-03-release-catalog-upgrade-from-surviving-index-parts,
  2026-10-20-single-owner-partitions-as-registry-rows, 2026-04-23-bucketed-histogram-indexing,
  2026-05-06-reference-histogram-statistics, 2026-08-10-catalog-and-collection-statistics]
---

# Keep one histogram store per reference group and scope, independent of reduced indexes

A grouped reference writes its histogram contributions once into a histogram-only `GroupHistogramEntityIndex`,
addressed by reference name, scope and group primary key. Reduced group indexes supply the references used to
resolve boundary entities, but hold no histogram copies. The released protocol-6 upgrade removes the old grouped
histograms, leaving them empty, and publishes protocol 9 once. Committed WAL replay reports and skips missing
withdrawal values during catalog loading and live trunk incorporation. Session writes use the configured policy.

## Why

A group's histogram belongs to the group, but a reference that allows duplicates splits the group's reduced indexes
by representative attribute values, one index per distinct tuple. Those partitions can appear, disappear or move
independently of the histogram. Correctness must survive that movement without copying every existing contribution
to every new partition. The reference-summary reader also needs the whole group when choosing the entity at a
histogram boundary.

### Previous state

Grouped writes replicated contributions into every reduced index the group-type index listed. A later index
started empty; an abandoned index retained stale copies. Withdrawal could fail because a later index lacked the
value, or consume a same-valued sibling's unit. Reading one reduced index per group hid contributions and boundary
entities held by another index. The per-reference deferred-run protocol fixed mutation ordering, but could not
make replication correct when a representative tuple changed the set of destinations.

## Options considered

### One histogram-only store per reference, group and scope (chosen)

- **Pros:** one destination independent of representative-key movement; existing histogram keys, counters and
  paged storage parts apply; one histogram source per group with the whole group as its boundary context.
- **Cons:** a concrete index kind and lifecycle to maintain, plus separate candidate resolution for boundaries.

### Partition the histogram across the type index (declined)

- **Pros:** reuses an existing index family and avoids a separate store object.
- **Rejected because:** representative-rank movement has a silent seam that would need its own contribution move;
  each histogram read requires a K-way merge, and range histograms require a global sweep even to fix this defect.
  Revisit only with explicit partition-movement and range-aggregation contracts that justify that recurring cost.

### Put the group into the histogram storage key (declined)

- **Pros:** direct group addressing inside the existing type index, without a separate entity index.
- **Rejected because:** the group enters the histogram persistence-key contract and its root, cardinality and leaf
  addressing. A separate store expresses that identity in the existing entity-index key instead. Revisit with an
  intentional redesign of histogram storage keys, rather than making this fix carry that format change.

### Keep the histogram in one designated reduced group index (declined)

- **Pros:** no new index kind, statistics value or gRPC enum value. A reference without duplicates already has
  exactly one `REFERENCED_GROUP_ENTITY` index per group, at the empty representative tuple, and the split-group
  defect never affected the histogram 2026.2 kept there.
- **Rejected because:** the designated key would name two different objects. A reference that allows duplicates
  must declare a representative attribute, so none of its reduced group indexes has the empty tuple; for it the
  designated index would be a row-less index of a row kind. Designating one of its real partitions instead ties the
  histogram to an index that appears, disappears and moves with representative values, which is the drift this
  record removes. A row-less `REFERENCED_GROUP_ENTITY` index needs an exception in every class that walks or accepts
  reduced indexes as row partitions (attribute, price and reference fan-out, cardinality, filtering, query
  planning), or stays unadvertised and is missed by them. A kind outside the reduced-index hierarchy makes the
  exclusion structural: `ReducedGroupEntityIndex` refuses any other kind and the store's row mutators refuse. The
  upgrade cleanup, which removes every histogram key from a `REFERENCED_GROUP_ENTITY` manifest, and the reload
  guard, which refuses one, would also have to depend on the reference's cardinality. Revisit only if reduced group
  indexes become one per group whatever the representative values.

### Publish 6 → 7 → 8, or accept a cleaned protocol-7 development catalog (declined)

- **Pros:** a small separate cleanup step, and possible reuse of development catalogs.
- **Rejected because:** two publications violate the release's single-publication upgrade boundary; a refusal in
  the second step leaves an intermediate publication. Making 8 current also lets foreign development protocol 8
  take the equality fast path without conversion. Protocol 7 does not identify one compatible representation:
  older snapshots contain owner-sort and partition-object shapes that histogram cleanup cannot repair.
  Both 7 and 8 are development formats to recreate; neither is a supported release migration source.

### Fill stores from entity bodies during upgrade (declined)

- **Pros:** complete grouped histograms immediately, avoiding missing-value refusals until a rebuild.
- **Rejected because:** evaluating conditions, cross-collection inputs, locales and defaults needs loaded-catalog
  machinery and a catalog-sized body scan. The migration works from surviving index parts; legacy repair does not
  belong in ordinary loading. Cleanup is bounded by the old histogram footprint. General rebuild belongs to #409.

### Refuse an unpublished WAL tail, or rely on an upgrade instruction alone (declined)

- **Rejected because:** tail refusal needs real pre-upgrade WAL inspection: the WAL service is not initialized at
  format verification, so its ordinary probe cannot establish an empty tail there. It also rejects tails unrelated
  to histograms. An instruction alone leaves a replayed withdrawal able to mark the catalog `CORRUPTED` after
  upgrade publication. Reporting lets the committed tail replay and preserves the configured session policy.
  Filling grouped stores would not cure drift in ungrouped histograms either.

### Thread a startup-only flag through the WAL replay call chain (declined)

- **Rejected because** eight signatures carried the flag to one check, and the write is already committed to the
  WAL by the time any replay could refuse it. Refusal can prevent catalog loading or exhaust the live
  incorporation retries. Revisit only if replay can still reject a write.

## Decision

Use one `REFERENCED_GROUP_HISTOGRAM` index for each admitted group contribution's reference, group and scope.
Switch the local writer, cross-entity writer and grouped reader together. Contributions with no assigned group
use the `REFERENCED_ENTITY_TYPE` histogram destination, including under a grouped schema; discovery and query
coverage of those contributions remain separate concerns described below.

Fold grouped cleanup into `Migration_2026_3#upgradeFromStorageProtocolVersion_6_to_9`. Plan refusals before
writes, remove every histogram key of `REFERENCED_GROUP_ENTITY` and `REFERENCED_GROUP_ENTITY_TYPE` manifests,
including obsolete schema definitions and localized keys, and remove roots, cardinalities and listed bucket/range
pages. Preserve ungrouped histograms. Publish the result as protocol 9 in one bootstrap publication. A protocol-6
input containing a group store, or an affected manifest with legacy inline bitmap carriers, is refused.

The session-less entity mutation entry point creates its root collector with `REPORT` for committed WAL replay,
both during catalog loading and during live trunk incorporation. The session mutation entry point creates its
collector with the engine's configured policy, which defaults to `REFUSE`. Nested implicit and reflected
mutations share their root collector. `ReferenceIndexMutator#refuseOrReportMissingHistogramValue` uses the
effective policy to log and skip a missing withdrawal value or throw while the session can still roll back.

### Boundary pick: merge producing indexes and stop at the first owned candidate

For a reference-attribute bound, the exhaustive path performs these steps:

1. Read the bound's filter records in each context index and map them through that same index to referenced PKs.
2. Read the histogram's counted owners, narrowed by the baseline, at the first nonempty raw lookup.
3. Enumerate every mapped candidate in each producing index, retain its owned subset, and union the owned subsets
   and unrestricted candidates separately.
4. Intersect with the context's scope and select the owned minimum; if the whole context has no owned candidate,
   select the unrestricted minimum. Carry the unrestricted pick as an alternative when it differs, so an
   unloadable primary pick can fall back at materialization.

Without a sorter and with an owner filter, `ReferenceHistogramAccumulator#pickLowestOwnedBoundaryPk` replaces
subset construction and the candidate unions with a merge of producing-index iterators. A primitive-array heap
orders unsigned referenced PKs, then original context positions. Scope is checked before ownership, each check
uses only the producing index, and a failed producer does not suppress another producer of the same PK. The first
scoped PK supplies the unrestricted alternative; the first owned scoped candidate ends the merge. Only exhaustion
permits the global fallback. Equal primary and alternative become a single pick; an empty scope yields NONE.
All raw lookups, mappings and producer heads are prepared before selection can stop.

With a sorter, retain the exhaustive steps. Sorting an owned subset need not preserve its order within the full
candidate set: a limited segment or seeded shuffle depends on the set it sorts. The no-owner-filter path also
retains its exhaustive implementation. Referenced-entity-attribute boundary resolution is a separate path.

- **Candidate-by-index walk (declined). Rejected because:** walking the scoped union and testing every candidate
  against every context slot creates candidate-count × index-count membership probes. On 200 disjoint producing
  indexes with five candidates each and no owned candidate, it made 200,000 producing-set probes for the same
  1,000 owner lookups. The producing merge makes zero such probes and retains the exact ordered owner-call prefix.
- **Scan each index until its own first success (declined). Rejected because:** an early index with many unowned
  candidates can spend far more owner lookups before a later index's low-PK owned candidate is considered. Global
  unsigned order reaches that witness first and preserves the minimum/alternative rule across the whole context.
- **Use early exit with every sorter (declined). Rejected because:** subset-sensitive order invalidates the
  equivalence between the first owned item of the full sort and the first item of the owned-subset sort.

For N context slots, B nonempty mapped producers, M mapped producing pairs and Q reached owner predicates, selection
takes O(N + P log(B+1) + Q) operations for P popped pairs, at most M, counting scope/owner probes separately from
their bitmap internals and excluding shared mapping work. Complete misses check only scoped producing pairs;
empty/tiny scopes can still pop all M. Three arrays are allocated per bound; producer iterators retain mapped
backing storage simultaneously, including after exhaustion. Dense overlap adds heap work compared with exhaustive
enumeration. This is an owner-lookup and construction reduction, not a universal runtime/memory claim.

## Key technical details

The index classes and local mutators below live under `evita_engine/src/main/java/io/evitadb/index/`;
query, collection and transaction entry points live under `evita_engine/src/main/java/io/evitadb/core/`.
Migration entry points live in `evita_store/evita_store_server/.../store/catalog/`.

- `GroupHistogramEntityIndex#keyOf` builds `(REFERENCED_GROUP_HISTOGRAM, scope,
  RepresentativeReferenceKey(referenceName, groupPk))`, with no representative tuple. It is not a reference
  partition and is never advertised as a reduced index. The group-type index discovers groups and lists their
  reduced indexes; the final add still asserts that it tracks the group, or stored values would be unreadable.
- Stores hold histograms only: entity/language membership, attributes, hierarchy, facets and prices stay empty.
  Row mutators refuse. Reload validates the actual membership and manifest before construction; substituting empty
  bitmaps would hide illegal persisted rows. Reduced-group and grouped-type reloads refuse legacy histogram keys;
  grouped-type histogram insertion also refuses rather than retaining residue.
- Histogram writes reuse `EntityIndex#dirty`. **An independent flag is rejected because** ownerless membership
  keeps `previouslyPersisted` false: a store created and emptied before its first flush still needs a manifest
  while the header names it. The cost is a repeated manifest and no-op removal of the unwritten membership part
  on a dirty flush. `notifyFlushed` captures baselines without clearing dirty; re-collection can emit them again.
- **No manifest UID bump:** the manifest gains no field, and `EntityIndexTypeSerializer` persists enum names,
  not ordinals. Protocol 9 prevents an older engine from treating the new kind as its own representation.
  The enum name is permanent on disk and in statistics; gRPC reports `INDEX_TYPE_REFERENCED_GROUP_HISTOGRAM = 6`.
- Creation requires an admitted value; withdrawal looks up without creating, then registers an existing store
  for modification. An empty histogram leaves its map; `GroupHistogramEntityIndex#isEmpty` follows that map.
  Local `applyChanges` sweeps accessed empty indexes. `EntityCollection#applyIndexMutations` uses a per-dispatch
  `DispatchMaintainer` whose lazy touched-store set is swept after dispatch. **A shared set is rejected because**
  concurrent sessions share the collection maintainer and one dispatch could clear another's touched keys.
- Commit copies preserve the activity holder and histogram counters; rollback restores index maps and contents.
  Footprint removal follows the histogram component's roots, leaf streams and cardinalities. Statistics browse
  exposes the kind with zero entity membership; heap accounting charges the store and its histogram component.
- Whole-entity removal keeps presence-only withdrawal without condition evaluation: all references of the owner
  leave together, and parent removal can precede reference removal. This differs from asserted withdrawal of an
  individual reference whose condition held.

## Verification

The replay policy run on 2026-11-19 passed **76 / 0 / 0 / 0** across six classes. In
`MissingHistogramValueBehaviorTest`, the same missing value is refused through a session and reported through
the replay entry point. Live trunk incorporation reports under the refusing default and makes the committed
write visible. Deferred startup reports under both configured policies, and the following session write uses
the configured policy. Switching either entry point to the other policy made the same-value test fail.

These are completed implementation runs on 2026-11-16 and 2026-11-17, checked against the shipped tests and
archived results; no performance run was made. Counts are tests / failures / errors / skips.

- Storage/migration sweep: **1,161 / 0 / 0 / 1** across 59 XML suites (1,160 passed), including released-fixture
  upgrade suites: `Release2026_2IndexUpgradeTest` 9 and `Release2026_2UpgradeDefectsTest` 128.
  `GroupHistogramEntityIndexTest` **31 / 0 / 0 / 0** pins keys, row refusals, counters, transactional copies,
  first-empty-flush manifests, cardinality-only writes and complete paged/localized footprint reclamation.
  `EntityIndexRoundTripTest` **7 / 0 / 0 / 0** includes the histogram-only store's round trip.
- `Migration_2026_3_GroupedHistogramCleanupTest` **6 / 0 / 0 / 0** proves a complete hand-built protocol-6 image,
  exactly one publication at 9, both cleaned manifest kinds, localized/stale keys, bucket/range leaves,
  cardinalities, ungrouped retention, refusal before writes, reload guards, rebuild parity and an unpublished
  committed WAL tail. Startup replay reports and reaches the committed version under `REFUSE`, then the next
  session write refuses.
  `StorageProtocolRefusalTest` **3 / 0 / 0 / 0** includes unchanged-file refusals of protocols 7 and 8.
- Independent cleanup counterfactuals kept protocol 9: disabling cleanup gave **6 / 1 / 2 / 0**, including the
  expected manifest assertion; disabling either reload guard gave **6 / 1 / 0 / 0**, at its own load-refusal cell.
  Skipping only leaf-page removal gave **6 / 1 / 0 / 0**, at the exact footprint check. Restored follow-up suite:
  **408 / 0 / 0 / 0** over 14 classes, including `MissingHistogramValueBehaviorTest` 31 (32 after adding
  the missing-group-store cell, now driven with `REPORT`) and
  `ReferenceHistogramLocaleSetFunctionalTest` 72. Compatibility audit: 9 configurers, 190 registrations,
  111 backward-compatible readers, zero unparsed registrations and zero findings; no manifest UID change.
- The hand-built image above uses today's serializers. `Release2026_2GroupedHistogramUpgradeTest` **4 / 0 / 0 / 0**
  upgrades the release fixture `groupedHistogram`, which the released 2026.2.18 engine writes during the build. Its
  image lists grouped keys, roots and cardinalities in both the reduced group and the group-type manifest; the
  release writes the group-type copy only from its cross-entity reevaluation, which a hand-built image cannot show.
  After the upgrade both manifest kinds are free of grouped histograms in one publication, the ungrouped histogram
  answers exactly as the release did, the catalog reloads without a refusal and no group store exists. A write
  withdrawing the missing grouped value is refused with the exact diagnostic; the same write succeeds on a catalog
  replaced from source. Disabling the cleanup failed two of the four cells (surviving keys; the reload refusal).
- `GroupHistogramAcrossReducedIndexesFunctionalTest` **54 / 0 / 0 / 0** covers split groups and mutation parity.
  Boundary/merge gate on this branch after merging dev: **287 / 0 / 0 / 1**, including
  `GroupHistogramStoreLifecycleFunctionalTest` 29,
  `GroupHistogramBoundaryAcrossReducedIndexesFunctionalTest` 16,
  `GroupHistogramBoundaryContributingOwnersFunctionalTest` 56 and `GroupHistogramSiblingBoundaryFetchFunctionalTest`
  26. These pin cross-path writes, empty-store sweeps, rollback/recreation, scope movement, whole-group boundaries,
  sorter ties, unloadable picks and shared fetches. Staged-removal cancellation is separately exercised by
  `WarmUpSavepointDataStoreChangesTest`; deterministic dispatch isolation by `DispatchMaintainerIsolationTest`.
- The merge gate includes `ReferenceAttributeBoundaryPickTest` 19, `ReferenceAttributeBoundaryDifferentialTest` 4,
  `ReferenceAttributeBoundarySorterTest` 11, `ReferenceAttributeBoundaryQueryTest` 5 and
  `ReferenceAttributeBoundaryCallCountTest` 7. The differential oracle checks **3,600** pick/alternative pairs:
  four seeds × 180 real-index contexts of two to ten producing indexes × five bound values, with zero differences.
  The provenance, unsigned-heap order and scope-before-alternative mutants each fail their designated witness, and
  a sift that always takes the left child fails the differential sweep (all four seeds) and both heap-order cells
  (nine interleaved indexes; a late owned candidate among 200 indexes). Immediate/late/miss spy counts stay
  **1 / 3 / 9**; the all-miss 200-index fixture checks **1,000** owner lookups and **zero** producing-set probes,
  and the owners are read once per pick, also when mapping or scope leaves no candidate.
- The single skip in the sweeps is the existing tie-dependent assumption in
  `ReferenceSummaryHistogramBoundaryResolutionTest.shouldPickMinMarketShareCandidateAndHonorSorterOnTies`;
  the generated fixture has no required minimum tie. It is not a new disabled test.

Count-only query fixture: **100,000 owners, 1,000 brands, 10 groups, three reduced indexes per group, 10 values,
two decoys per bound**. Each cell passed its model and two identical runs had identical counters:

| Owner selection | No-sorter merge owner checks | Exhaustive PRIMARY_KEY_DESC owner checks |
|---|---:|---:|
| ALL_OWNERS | 20 | 2,940 |
| RESTRICTED | 260 | 5,760 |
| FALLBACK | 190 | 2,940 |

The sorter column is the retained exhaustive path on the same fixture; it is not a changed-sorter speedup.
**No timing or heap-allocation measurement was made.** Operation counts do not establish latency or production
impact.

Full functional suite on this branch after merging dev and the boundary-pick test round (2026-11-17):
**39,464 / 0 / 0 / 70**. The run before that round had one error, a gRPC session handshake timeout in
`EvitaSessionServiceWarmupCatalogTest` under load, which passed **2 / 0 / 0 / 0** when rerun alone. A whole-reactor
`test-compile` with the `full` profile, including the long-running, documentation and performance test modules,
succeeded after the dev merge. After adding the release-written fixture, the storage, upgrade and group-histogram
classes ran again with every release fixture regenerated by the release engines: **2,876 / 0 / 0 / 45**.

## Consequences & open follow-ups

- Grouped histograms are empty after upgrade until rebuilt or populated by later admitted writes. Ordinary
  withdrawals expecting old contributions can refuse until rebuild; REPORT skips missing values without repairing
  the absent contributions. Preserve a pre-upgrade backup for downgrade and rebuild from source for completeness.
- Committed WAL replay reports missing withdrawal values for grouped and ungrouped histograms during both catalog
  loading and trunk incorporation. Replayed references can add their final values while untouched contributions
  remain absent. Logs record the skipped withdrawals. The session policy still controls whether a write rolls back.
- Every development catalog stamped 7 or 8 is refused and must be recreated. Header-based format dispatch remains;
  historical development images with a protocol-6 header under a protocol-7 bootstrap were described previously.
  Their continued existence is unproven; this change does not add original-bootstrap inspection.
- Cleanup retains reduced indexes left empty after removing histograms. It does not synthesize stores or erase
  their non-histogram identity; ordinary lifecycle sweeps handle subsequently accessed empty indexes.
- `EntityIndexLocalMutationExecutor#canMutationChangeHistogramValues` still withdraws on every reference attribute
  change of a duplicate-allowing grouped reference, including an unrelated attribute. Narrowing that conservative
  rule is deferred; no issue is filed without a measurement.
- `EntityCollection#evaluateHistogramConditionState` still sets and clears the shared maintainer's `session` field.
  Two concurrent pre-passes can overwrite it. This pre-existing race is suspected from the code, not demonstrated
  by the dispatch-isolation tests; a per-dispatch maintainer does not fix the pre-pass.
- The unmanaged-group reader gap is pre-existing and suspected from the code: `computeForReference` selects
  grouped reading only for a managed group type, while writers route by the assigned group. An unmanaged group
  can therefore write a group store while its reader takes the ungrouped path. No repair or reproduced test is
  claimed here; align reader/writer routing under a separate design.
- A reference without an assigned group has a valid ungrouped destination, but grouped reading discovers real
  groups through the group-type index. Cross-entity discovery of such owners under a grouped schema is also
  outside this fix. Destination routing alone does not prove discovery or query coverage.
- Presence remains per `(value, owner)`, not per reference. Two same-valued references of one owner share a counter;
  one sibling can mask another's missing unit. `SimpleHistogramIndex` and `AttributeCardinalityIndex` retain that
  format. One group store prevents cross-partition replication drift but adds no per-reference attribution.
- The merge prepares every producer and can retain more mapped backing storage than exhaustive per-index
  processing. Measure fan-out, scope sparsity, overlap, latency and allocation before extending the optimization
  or claiming a general speedup. Sorter paths deliberately retain exhaustive subset construction.

## Related work

- [Net change per run](2026-11-11-reference-histogram-net-change-per-run.md) supplies withdrawal/final-add ordering;
  this record fixes its open split-group storage item without replacing that protocol.
- [Released catalog upgrade](2026-11-03-release-catalog-upgrade-from-surviving-index-parts.md) supplies preflight
  before publication and the surviving-parts boundary; grouped cleanup composes with those rewrites.
- [Single-owner partitions](2026-10-20-single-owner-partitions-as-registry-rows.md) supplies the one-publication
  migration invariant. Protocol 9 and explicit development-format refusals update its protocol-7 choice.
- [Conditional histogram indexing](2026-04-23-bucketed-histogram-indexing.md) introduced the histogram family;
  this record relocates grouped storage while retaining its counters and expression triggers.
- [Reference histogram statistics](2026-05-06-reference-histogram-statistics.md) defines the query contract;
  grouped computation reads one store with the entire group's reduced indexes as boundary context.
- [Catalog and collection statistics](2026-08-10-catalog-and-collection-statistics/) defines index-kind reporting
  and heap ownership; the histogram-only kind participates in both surfaces.

## Timeline

- **2026-11-12** — one publication, protocol 9 and startup-only missing-value reporting accepted by the owner.
- **2026-11-16** — migration cleanup/refusal and replay fix-round verification completed; producing-index merge
  verified after rejecting the candidate-by-index walk.
- **2026-11-17** — release-written upgrade fixture added; pull request #2001 opened. Times in `updated` use
  Europe/Prague.
- **2026-11-19** — moved missing-value policy selection to the mutation entry points. Every committed WAL replay
  reports while session writes use the configured policy. Pull request #2001 merged; the record carries the merge
  date.
