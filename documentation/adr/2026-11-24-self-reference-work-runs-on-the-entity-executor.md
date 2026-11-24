---
title: Self-reference work runs on the entity executor
date: 2026-11-24
updated: 2026-11-24 08:26
status: accepted
kind: fix
issues: [2002]
prs: [2066]
areas: [evita_engine/src/main/java/io/evitadb/core/collection,
  evita_engine/src/main/java/io/evitadb/index/mutation/local,
  evita_engine/src/main/java/io/evitadb/index/mutation/storagePart,
  evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional]
supersedes: []
superseded-by: []
relates: [2026-08-31-cross-entity-histogram-removal-pre-pass,
  2026-09-08-conditional-histogram-per-contribution-verdicts,
  2026-09-28-indexed-reference-scope-requires-entity-component,
  2026-10-08-trapped-storage-part-content-pre-images,
  2026-10-20-single-owner-partitions-as-registry-rows,
  2026-11-11-reference-histogram-net-change-per-run,
  2026-11-23-cross-entity-dispatch-reads-array-histogram-sources-from-storage]
---

# Self-reference work runs on the entity executor

When a write generates reflected-reference work for the entity it is writing, that work runs as a further batch
on the entity's existing index and storage executors. Registering a second pair for the same entity within one
root mutation is an internal error.

## Why

A self-reference insert could report success and store the reflected row while losing the primary row. An update
could leave the primary's old attribute beside the mirror's new value and make a later removal fail. The loss
survived restart in both `WARMING_UP` and `ALIVE`, even without a histogram.
[Issue #2002](https://github.com/FgForrest/evitaDB/issues/2002)

### Previous state

The collector JavaDoc expected one executor per entity and explicitly said that a second registration replaced
the first in the in-flight lookup. That relied on generated mutations never targeting an entity already in flight.
Self-reflection broke the assumption. The second pair read the entity's references from storage and finalized a
references part built from the old state, overwriting the first pair's changes.

## Options considered

### Option A — Apply the self work as a further batch on the existing pair (chosen)

- **Pros** — One pair sees the accumulated entity state and owns the write through finalization.
- **Cons** — The executors have to support another batch, including filtering repeated reference operations and
  preparing that batch's partition keys.

### Option B — Re-enter the collector with the existing pair (declined)

- **Pros** — Shares the ordinary collector entry point and uses the existing entity state.
- **Rejected because** — Re-entering `execute` replays locale changes on the reused pair.
- **Revisit if** the entry point can run a continuation without repeating root-only effects such as locale handling.

### Option C — Keep two pairs and merge their results (declined)

- **Pros** — Each batch keeps its own executor lifecycle.
- **Rejected because** — By finalization the second pair has already applied index changes and checked consistency
  against its own view of the entity. Merging references parts at that point cannot correct those effects.
- **Revisit if** the write path provides a shared in-flight view and an ordered merge covering index and storage
  effects within one atomic write.

### Option D — Refuse the self write (declined)

- **Pros** — Exposes the failure to the caller before a partial result can be stored.
- **Rejected because** — It would deny supported self-reference writes that one pair can apply correctly.
- **Revisit if** a self-targeted operation is shown to violate a defined write invariant even on a single pair.

### Option E — Change cross-entity recounts in this fix (declined)

- **Pros** — Could prevent surplus units where the local and cross-entity histogram writers overlap.
- **Rejected because** — Suppressing a recount has to preserve contributions that only the cross-entity path sees.
  The storage fix can ship independently with the measured count errors documented and tracked under #1925.
- **Revisit if** #1925 can identify the contribution already handled locally while preserving the rest of the recount.

## Decision

**Chosen: Option A.** The pair holding the root write's staged changes also applies that entity's reflected work.
`LocalMutationExecutorCollector#registerInFlightExecutor` raises `GenericEvitaInternalError` on a second registration,
before the second pair applies anything. A scheduling mistake now fails before it can overwrite that state.

## Key technical details

- **One executor pair per entity per root mutation.** The key is entity type and primary key.
  `LocalMutationExecutorCollector#applyImplicitMutations` extends the current pair for self work and uses
  `EntityCollection#applyMutations` for work targeting other entities.
- **Skip the inserts and removals the derived batch already applied.**
  `LocalMutationExecutorCollector#omitAppliedSelfReferenceChanges` matches operation and persistent reference identity
  (reference name, target and positive internal reference ID). A full removal drops its own references
  and skips self work.
- **Run scope and removal propagation at most once per executor.** See
  `ContainerizedLocalMutationExecutor#popImplicitMutations`.
- **Refresh partition keys for each further batch.** `EntityIndexLocalMutationExecutor#prepareFurtherBatch` installs
  the batch's lookahead and invalidates affected cached partition keys before either a derived batch or a self batch.

## Verification

- `SelfReflectedReferenceWriteFunctionalTest` was red on `dev` in X4 (38 cases, 38 failures). The final class,
  extended during the quality pass, passed all 42 cases with the fix (0 failures, 0 errors, 0 skipped).
  Both catalog states are covered. (Internal, X4 measurement §1 and quality ledger.)
- The instrumented 82-case class produced **833 matched write keys**. The original measurement and the X4
  `REFUSE` control agree. In both X4 policies, the same 30 writes moved from refused to accepted.

  | Policy | Accepted / refused on `dev` | Accepted / refused with fix | Refused to accepted | Newly refused |
  |---|---:|---:|---:|---:|
  | 2026.3 default (`REPORT`) | 799 / 34 | 829 / 4 | 30 | 0 |
  | Explicit `REFUSE` | 797 / 36 | 825 / 8 | 30 | 2 |

  The two refusals under `REFUSE` are the same combined size and level update, one in each catalog state.
  (Internal, X1–X3 write measurements and X4 measurement §2.)
- The full functional suite on merge `43895ae142` ran **40,290 tests**. It recorded 10 failures, 16 errors and
  70 skips. The failures and errors were attributed to the test environment and missing release fixtures,
  with **0 failures caused by the change**. (Internal, 2026-11-23 quality pass.)

## Consequences & open follow-ups

The 2026.3 decision is to ship the fix with the following wrong counts documented. The first two are tracked
under #1925; the facet shape gets its own issue once this fix is merged. The release decision
omits the `breaking change` label because the 2026.3 default newly refuses zero of the measured writes.

- **Restore of reflected references.** Restoring a self mirror adds two histogram units. The local index finish
  and scope-change dispatch each add one, so a subsequent removal can leave a unit behind. Non-self restore
  already adds two on `dev`.
- **Combined size and level update.** Under `REPORT` the reflected self write stores both changes and two
  histogram units at the new size. Removing the reference leaves one unit that survives restart. The plain
  self-reference already behaves this way on `dev`. Separate upserts avoid the measured overlap.
- **Facet on the reflected reference.** An entity references itself and another entity. With a facet and
  histogram on the reflected reference, a joint level update followed by removal stores and removes the
  rows correctly but leaves one histogram unit per owner in the measured case. The non-self control clears
  both units on both builds. **Suspected, not proven**. A delayed self recount adds the other owner's contribution
  after its local index finish. The baseline refused the self removals, so whether the fix introduced the final
  residue remains **Suspected, not proven**. It is filed as its own issue after this fix merges, from a
  re-run on the merged `dev`.
- **Existing damaged data.** The fix and the `dev` control gave identical results on all three seeded shapes,
  including a member left `ARCHIVED` by a prior restore.
- **Second-executor guard.** Current implicit mutation producers cannot reach it through the public API.
- **Trunk replay.** The self batch's consistency check sees the root's own changes through the shared executor,
  even when the root runs with `verifyConsistency=false`. See `LocalMutationExecutorCollector#applyImplicitMutations`.
  **Suspected, not proven**. This could change the replay
  outcome for concurrent writes that touch a cardinality-limited reference. Whether replay should check the
  accumulated root changes at this point remains open.
- **Archive then delete with asymmetric in-session indexing.** Archiving while the primary reference is
  unindexed in `ARCHIVED`, then re-enabling that index in the same session, can leave deletion throwing
  `Cardinality of index PK … is null` at `ReferenceTypeCardinalityIndex#removeCardinality`. The error predates
  this fix. All 8 non-self cases on unchanged `dev` hit the same message and index frames. The self case on
  `dev` fails earlier at archive. **Suspected, not proven**. The same missing-index mechanism accounts for the
  self delete. The attribution compared only the named index frames.

## Related work

- The removal pre-pass records when implicit work needs its own pre-state capture.
  [Cross-entity histogram removal pre-pass](2026-08-31-cross-entity-histogram-removal-pre-pass.md)
- The per-contribution decision extends capture and dispatch to implicit mutations.
  [Conditional histogram per-contribution verdicts](2026-09-08-conditional-histogram-per-contribution-verdicts.md)
- The indexed-scope decision describes validation and component defaults for reflected references.
  [Indexed reference scopes](2026-09-28-indexed-reference-scope-requires-entity-component.md)
- The storage rollback decision records how the in-flight registry shares trapped parts during a savepoint.
  [Trapped storage part content pre-images](2026-10-08-trapped-storage-part-content-pre-images.md)
- The partition decision describes `InFlightEntityAccessorLookup` and how other executors read staged parts.
  [Single-owner partitions as registry rows](2026-10-20-single-owner-partitions-as-registry-rows.md)
- The net-change decision defines the local deferred run and records the 2026.3 missing-value policy.
  [Reference histogram net change per run](2026-11-11-reference-histogram-net-change-per-run.md)
- The dispatch decision uses the executor to read stored histogram sources and records another #1925 overlap.
  [Array histogram dispatch](2026-11-23-cross-entity-dispatch-reads-array-histogram-sources-from-storage.md)

## Timeline

All dates are in Europe/Prague.

- **2026-11-17** — Reproduced the lost self-reference write and began measuring the executor change.
- **2026-11-18** — Verified the further-batch fix, including asymmetric archive and ranked restore cases.
- **2026-11-19** — Approved shipping the facet count error documented and tracking it separately.
- **2026-11-23** — Re-measured under the 2026.3 default, confirmed the release labeling decision and recorded
  the fix in `f6808a2684`. Merge `43895ae142` brought in `dev` for the full-suite run.
