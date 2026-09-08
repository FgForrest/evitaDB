---
title: A warm-up schema change refused by validation raises the unpublishable barrier, so the catalog deactivates and recovers by reload rather than by an undo
date: 2026-09-08
updated: 2026-09-08 13:10
status: accepted
kind: fix
issues: [1466]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/core/session, evita_engine/src/main/java/io/evitadb/core/catalog, evita_engine/src/main/java/io/evitadb/core/transaction/engine, evita_api/src/main/java/io/evitadb/api]
supersedes: []
superseded-by: []
relates: [2026-07-18-paged-index-corruption-and-flush-failure-boundary, 2026-08-26-warm-up-per-entity-mutation-atomicity, 2026-09-06-go-live-session-drain]
---

# A warm-up schema change refused by validation raises the unpublishable barrier

When catalog-schema validation refuses a schema change in a `WARMING_UP` catalog, the catalog now raises the
unpublishable barrier that `2026-07-18-paged-index-corruption-and-flush-failure-boundary` introduced for failed
flushes. Every route that would write a bootstrap record refuses from that moment on, the catalog is handed over
for deactivation, and activating it again loads the last state it published — the one written by the last session
that closed successfully. Nothing is undone in memory, because nothing can be.

## Why

A schema change that fails `CatalogSchema#validate()` was refused to the caller and still reached the disk, and
the reopened catalog loaded it without complaint. An operator was left holding a catalog whose schema the engine
itself would have rejected — for instance an attribute carrying a filter accelerator with no filter index behind
it, or a managed reference to an entity type that does not exist.

The constraint that made this non-obvious is that **validation cannot move earlier**. A schema is routinely built
across several steps whose intermediate states are legitimately invalid — a reflected reference declared before
the reference it reflects is the worked example, pinned by
`AttributeFilterAcceleratorRefusalTest#shouldAllowSchemaChangeWithUnresolvedReflectedReference`. Validation
therefore runs on the whole graph, once, when a session closes, and by then the change is applied.

### Previous state

`EvitaSession#closeInternal`'s warm-up branch already validated *before* it flushed, so the refused session
performed no write of its own — the code even carried a comment naming #1466 as the residual gap. What it could
not do was un-apply the change: `EntityCollection#updateSchema` had already exchanged the schema into every
collection the change reached, run the structural work that goes with it, and trapped an `EntitySchemaStoragePart`
in the data-store buffer.

So the poisoned state survived on the running catalog, and the next publisher wrote it. A counterfactual
measurement — suppressing the shutdown flush and reopening — established who those publishers were:

| bystander between the refusal and the close | shutdown flush on | shutdown flush suppressed |
|---|---|---|
| none | poison persists | clean |
| a **read-only** `queryCatalog` session | poison persists | **poison persists** |
| a data-only `updateCatalog` session | poison persists | **poison persists** |

The second row is the one that shaped the decision: `closeInternal` takes the warm-up branch for *every* session
regardless of its traits, so a session that wrote nothing at all republished the refused schema. Any fix that
enumerates publishers has to find that one, and the first draft of this work did not.

## Options considered

### Option A — raise the unpublishable barrier (chosen)

One call at the refusal site. `Catalog#markUnpublishableDueToInvalidSchema` records the cause and schedules
deactivation; every publication route already consults the barrier, and `Catalog#terminateInternally` already
skips its flush when it is set.

- **Pros:** complete by construction rather than by enumeration — a publication route added later is covered
  without being found. Reuses a mechanism that already exists, is already tested, and whose operator story
  (deactivate, then activate again) is already a documented API. Recovery lands on a state that is consistent by
  construction, because it was published by a session that completed.
- **Cons:** everything written since the last session close is discarded, and the client has to call
  `EvitaContract#activateCatalog`. Makes deactivation a routine path, which raises the stakes on #1497.

### Option B — validate at every publication route (declined)

Add an unconditional `getSchema().validate()` beside `assertPublishable()` in `Catalog#flush()`,
`terminateInternally()`, `goLive()` and `replace()`, defer the three opportunistic mid-session publications, and
stop read-only sessions from flushing in warm-up.

- **Pros:** no data loss and no downtime — the client repairs the schema and continues the import.
- **Cons:** four gates, three deferrals and a session-traits guard, all of which have to stay exhaustive.
- **Rejected because:** the repair path was never sound. The exchange's side effects have already run —
  `initRootNodes`, `usageRegistry.alignWith`, the trigger-registry rebuild — and a corrective mutation exchanges
  again on top of them rather than reversing them; nothing shows the composition equals what a clean path would
  have produced. Its safety also rested on enumerating publishers, and the enumeration was already incomplete
  once (the read-only session above). Worth revisiting only if warm-up ever gains an undo that spans collections.

### Option C — refresh the schema in place from the last bootstrap pointer (declined)

Leave the catalog running and reload only the schema from the last published record.

- **Pros:** the leanest recovery imaginable — no deactivation, no data loss, no client action.
- **Rejected because:** unsound twice over. `EntityCollection#updateSchema` puts an `EntitySchemaStoragePart`
  into the data-store buffer in a `finally`, so the invalid part is already trapped and the next flush publishes
  it regardless of what the in-memory schema says; and automatic schema evolution means entities already in
  memory may depend on the evolved schema that the refresh would drop.

### Option D — validate a candidate graph before exchanging (declined)

Build the post-mutation schemas as values, validate, and exchange only if the result validates.

- **Pros:** no poison ever, so no barrier, no loss and no reload.
- **Rejected because:** legitimately-invalid intermediate states are a supported feature (see *Why*), so the
  check would have to be allowed to fail without refusing the change — which makes it not a check. Holding the
  mutation pending until the graph validates is deferred schema application: a feature that changes the semantics
  of every schema mutation, not a fix. Worth revisiting only if the schema API ever gains an explicit
  "apply this batch atomically" boundary.

## Decision

**Chosen: Option A.** The drivers were completeness and provable recovery, and the barrier wins on both: it is
one flag that every publication route already reads, and reload is the only recovery whose result can be argued
about at all. Option B lost on the second driver rather than the first — enumeration is fragile, but the deciding
point is that its "repair and continue" story assumed an undo that does not exist.

Two properties of the chosen mechanism are deliberate. The barrier is raised through
`Catalog#markUnpublishableDueToInvalidSchema` rather than `markUnpublishable`, because the latter's message is
written for a failed flush and reads as an engine fault, while this is a rejected user change with a named cause;
the seam for exactly this was already documented on `recordUnpublishableCause`. And nothing is undone in memory:
the catalog keeps serving until the deactivation lands, and the state it lands on comes from disk.

For the option to be revisited, warm-up would have to gain an undo that spans entity collections — which is what
`2026-08-26-warm-up-per-entity-mutation-atomicity` deliberately did not build, and whose journal explicitly
forbids partial replay.

## Key technical details

- **The refusal site is the only one.** `CatalogSchema#validate()` has exactly one engine call site —
  `EvitaSession#validateCatalogSchema`, reached from `closeInternal`. The hard stop is therefore *automatically*
  limited to post-exchange failures: the pre-flight refusals (`verifyEntitySchemaMutationsApplicable`,
  `verifyNoAcceleratorAddedToNonEmptyCollection`, the mutations' own asserts) are untouched and keep refusing
  cleanly, so an ordinary schema typo never costs a reload. A future change that adds a second `validate()` call
  site must decide deliberately whether it also raises the barrier.
- **Only the warm-up branch.** `validateCatalogSchema` is also called transactionally, where it marks the
  transaction rollback-only and the state unwinds properly. Nothing changed there.
- **Two shutdown defects were fixed in passing**, both pre-existing and both made routine by this change, since
  a client that reacts to the refusal by shutting down now has a lifecycle mutation in flight while the engine
  closes:
  - `EngineTransactionManager#close()` joined the pending engine mutations and let their *failure* propagate,
    skipping `enginePersistenceService::close` — so the engine's folder lock stayed held and the next start died
    with `FolderAlreadyUsedException` against a process that had already exited. The wait is now completion-only.
  - `updateEngineStateAfterEngineMutation` dereferenced `Evita#getEngineState()`, which `Evita#closeCatalogs`
    clears *before* draining the mutations still in flight. It now refuses with `InstanceTerminatedException`,
    ahead of the write-ahead log append, so nothing of the refused mutation is durable.
- **Whether a restart finds the catalog `INACTIVE` is a race, and both outcomes are correct.** The deactivation
  is scheduled rather than run inline, so a process that shuts down promptly after the refusal may exit before it
  lands - in which case nothing is persisted and the catalog comes back loaded. When it does land first, the
  `INACTIVE` state IS persisted and the restarted engine leaves the catalog unloaded until it is activated. Both
  sides read the same bootstrap record, so the guarantee is unaffected; only the operator's next step differs.
  Nothing was added to make shutdown wait for the deactivation: `scheduleDeactivation` already treats a
  deactivation that does not complete as acceptable ("the catalog stays refusing, which is the property that
  matters"), and blocking shutdown on work that shutdown makes moot would buy predictability with availability.
  Any test that reopens after a refusal must tolerate both sides - see
  `WarmUpRefusedSchemaPersistenceTest#reopenAndReadSchema`.
- **Deactivation settles in two steps, and the second is observable only by retry.** The catalog reads `INACTIVE`
  once the engine-state update lands, but the lifecycle mutation releases its conflict key when it finalizes a
  moment later; an activation issued in between is refused with `ConflictingEngineMutationException`. That is the
  engine's "another lifecycle operation is still in flight" signal, and a client reacting instantly to the
  deactivation has to retry. `WarmUpRefusedSchemaPersistenceTest#activateOnceTheDeactivationSettles` documents it.

## Verification

The full functional suite runs green: **23,717 tests, 0 failures**, the only error being `ExportS3ServiceTest`,
which needs a Docker environment this workstation does not provide.

`WarmUpRefusedSchemaPersistenceTest` (new, 5 tests) proves the guarantee on **two independent validation rules** —
the attribute filter-accelerator rule and the managed-reference rule — so it is pinned to the warm-up write path
rather than to one validator. Each refusal is asserted on its message, not merely on the exception type, and each
is paired with a control that pushes the *same* schema element through legitimately and finds it after a reopen,
so an empty assertion cannot pass because the reopened schema was read wrongly.
`Recovery#shouldDeactivateTheCatalogAndHandBackTheLastPublishedStateOnActivation` covers the whole story: an
entity written by a session that closed successfully survives the recovery, and one written by the refused session
does not.

`AttributeFilterAcceleratorRefusalTest#shouldRefuseAnAcceleratorDeclaredOnAnAttributeWithNoFilterIndex` was a
characterisation asserting the wrong outcome deliberately; it now asserts `Set.of()`.

**Counterfactual:** with the single `markUnpublishableDueToInvalidSchema` call removed and everything else
unchanged, all four guards fail — the two repros (`expected: <[]> but was: <[SUBSTRING_SEARCH]>` and
`expected: <false> but was: <true>`), the characterisation flip, and the recovery test (`stayed at WARMING_UP
instead of reaching INACTIVE`) — while the other 21 tests in the two classes still pass. The folder-lock fix has
its own counterfactual: before it, the reopen in every repro died with `FolderAlreadyUsedException`.

## Consequences & open follow-ups

- **Catalogs already poisoned on disk are not covered, and deliberately will not be.** After a reload the version
  gate in `EvitaSession#validateCatalogSchema` skips validation, so such a catalog keeps loading; the next
  schema-changing session is what catches it. Validating at load was considered and declined — deactivating an
  operator's catalog at startup for a pre-existing condition is a worse upgrade experience than the condition.
- **The loss window is one session.** Warm-up publishes when a session closes, so a client running a single
  long import session loses that whole session's work to one invalid schema change. This is the trade the phase
  already makes, and it is now stated in `CatalogState.WARMING_UP` and in
  `documentation/user/en/deep-dive/bulk-vs-incremental-indexing.md`, both of which previously claimed the
  catalog should be discarded and rebuilt from scratch.
- **#1497 (deactivate and drop have no undo) matters more now**, because deactivation went from a rare
  consequence of a flush failure to the ordinary answer to a schema typo.

## Related work

- `2026-07-18-paged-index-corruption-and-flush-failure-boundary` — introduced the unpublishable barrier and its
  deactivation for failed and cancelled flushes. This record adds a fourth cause to it and gives that cause its
  own operator message.
- `2026-08-26-warm-up-per-entity-mutation-atomicity` — the per-entity savepoint whose journal forbids partial
  replay. It is the reason a schema-level undo is not merely unimplemented but out of reach, which is what makes
  Option B's repair path unsound.
- `2026-09-06-go-live-session-drain` — the other lifecycle path that quiesces sessions before publishing; both
  records concern what may reach the disk while a catalog changes state.

## Timeline

- **2026-09-08** — reproduction written, publishers identified by counterfactual measurement, barrier design
  chosen over the publication-route gate, implemented
