---
title: A warm-up schema change refused by validation raises the unpublishable barrier, so the catalog deactivates and recovers by reload rather than by an undo
date: 2026-09-08
updated: 2026-11-19 21:41
status: accepted
kind: fix
issues: [1466, 2014, 2032, 2033]
prs: [1520, 2015, 2037]
areas: [evita_engine/src/main/java/io/evitadb/core/session, evita_engine/src/main/java/io/evitadb/core/catalog, evita_engine/src/main/java/io/evitadb/core/transaction/engine, evita_api/src/main/java/io/evitadb/api]
supersedes: []
superseded-by: []
relates: [2026-07-18-paged-index-corruption-and-flush-failure-boundary, 2026-08-26-warm-up-per-entity-mutation-atomicity, 2026-09-06-go-live-session-drain, 2026-11-16-warm-up-trigger-build-refusal-raises-the-barrier]
---

# A warm-up schema change refused by validation raises the unpublishable barrier

When catalog-schema validation refuses a schema change in a `WARMING_UP` catalog, the catalog now raises the
unpublishable barrier that `2026-07-18-paged-index-corruption-and-flush-failure-boundary` introduced for failed
flushes. Every route that would write a bootstrap record refuses from that moment on, the catalog is handed over
for deactivation, and activating it again loads the last state it published. Nothing is undone in memory, because
nothing can be.

**The barrier alone is not the whole fix, and the first implementation of this decision was wrong about that.**
Validation runs when a session closes, so the barrier is raised at the close — and anything that publishes
*earlier in that same session* gets there first. Two such publications exist, and each needed a gate of its own
ahead of the barrier: the warm-up flush that collection-level DDL runs inline, and go-live.

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
deactivation; every publication route already consults the barrier before writing, and
`Catalog#terminateInternally` already skips its flush when it is set. The consulting is what costs nothing to
extend — the option's weakness is *when* the barrier gets raised, not which routes read it.

- **Pros:** for every publication *after* it is raised, complete by construction rather than by enumeration — a
  route added later is covered without being found. Reuses a mechanism that already exists, is already tested, and
  whose operator story (deactivate, then activate again) is already a documented API. Recovery lands on a state
  that is consistent by construction, because it was published by a session that completed.
- **Cons:** everything written since the last publication is discarded, and the client has to call
  `EvitaContract#activateCatalog`. Makes deactivation a routine path, which raises the stakes on #1497.
  **And its completeness is temporal, not total:** it says nothing about publications that happen before it is
  raised, which is the whole of the mid-session problem below. This was missed in the first implementation and
  found by adversarial review — see *Verification*.

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
- **But one part of it was necessary anyway, and declining the option as a whole hid that.** Option B's
  "defer the three opportunistic mid-session publications" is not an alternative to the barrier — it addresses a
  case the barrier cannot reach at all, because those publications happen before the barrier exists. It is
  implemented here, in the narrower form described under *Key technical details*. A rejected option is rejected
  as a whole design; that is not licence to drop the problems it was the only option to name.

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
- **Not to be confused with `flushMidSessionIfSchemaValid`**, which is also a validity check that is allowed to
  fail without refusing. The difference is what it gates: that one gates a **publication**, which is always safe
  to postpone, while this option gates the **mutation**, which is not — postponing the mutation changes what the
  caller's next read sees.

## Decision

**Chosen: Option A, plus the one piece of Option B that answers a different question.** The drivers were
completeness and provable recovery. Reload is the only recovery whose result can be argued about at all, so
Option A wins the second outright; Option B lost it, because its "repair and continue" story assumed an undo that
does not exist.

On completeness the honest statement is narrower than the first version of this record claimed. The barrier makes
every publication *downstream of the refusal* refuse, without those routes having to be enumerated. It does
nothing for publications upstream of it, and two exist. Those are handled by two explicit gates, and those gates
**are** an enumeration — a route added ahead of the barrier in future will need finding, exactly the fragility
Option B was rejected for. The mitigation is that both gates sit at chokepoints (see below), not that the problem
is gone.

Two properties of the chosen mechanism are deliberate. The barrier is raised through
`Catalog#markUnpublishableDueToInvalidSchema` rather than `markUnpublishable`, because the latter's message is
written for a failed flush and reads as an engine fault, while this is a rejected user change with a named cause;
the seam for exactly this was already documented on `recordUnpublishableCause`. And nothing is undone in memory:
the catalog keeps serving until the deactivation lands, and the state it lands on comes from disk.

For the option to be revisited, warm-up would have to gain an undo that spans entity collections — which is what
`2026-08-26-warm-up-per-entity-mutation-atomicity` deliberately did not build, and whose journal explicitly
forbids partial replay.

## Key technical details

- **Among validation refusals, only the close-time and the go-live ones raise the barrier.** Before this change
  `CatalogSchema#validate()` had exactly one engine call site — `EvitaSession#validateCatalogSchema`, reached from
  `closeInternal` — which is why the hard stop is limited to post-exchange failures: the pre-flight refusals
  (`verifyEntitySchemaMutationsApplicable`, `verifyNoAcceleratorAddedToNonEmptyCollection`, the mutations' own
  asserts) are untouched and keep refusing cleanly, so an ordinary schema typo those checks catch never costs a
  reload; one that only the trigger build at the end of a schema change catches does, in warm-up
  (`2026-11-16-warm-up-trigger-build-refusal-raises-the-barrier`). This change
  adds two more call sites, and **neither of them may be turned into a refusal**:
  - `Catalog#isSchemaValid()`, behind `Catalog#flushMidSessionIfSchemaValid()`. The three warm-up DDL flushes
    (`createEntitySchema`, `removeEntitySchema`, `doReplaceEntityCollectionInternal`) publish inline while the
    session is still open. They now publish only when the schema validates, and **skip silently otherwise** — they
    must not refuse, because a schema that does not validate mid-session is not an error. Skipping loses nothing:
    the changes stay in the data store buffer and the session close publishes them, after validating.
  - `MakeCatalogAliveMutationOperator`, ahead of its flush. This one *does* raise the barrier, because go-live is
    the end of warm-up and there is no later close to defer to. It sits in the operator rather than in
    `EvitaSession#goLiveAndCloseWithProgress` because all three go-live entry points funnel through the operator,
    and because the session-driven path terminates its session through `executeTerminationSteps` rather than
    through `closeInternal` — so the close-time validation never runs for the session that calls it.
- **`validate()` refuses in two vocabularies, and only one of them is a `SchemaAlteringException`.** A rule it
  evaluates throws that type; a getter it *reaches* on a schema it cannot resolve simply refuses to answer, with a
  plain `EvitaInvalidUsageException` — `ReflectedReferenceSchema#isIndexedInScope` throwing "the reflected
  reference is not available", which `EntitySchema#validate` walks straight into. This is not a hypothesis:
  catching only the narrow type in `Catalog#isSchemaValid` turned a legitimate intermediate state into a failed
  DDL operation and cost **17 tests** in the full suite. All three sites — `isSchemaValid`, the warm-up close and
  the go-live operator — therefore catch `EvitaInvalidUsageException`, and
  `Catalog#markUnpublishableDueToInvalidSchema` takes that type. `EvitaInternalError` is a sibling of that class,
  not a subtype, so an engine bug uncovered by the walk still surfaces.
  **Only the `isSchemaValid` half of that is demonstrated.** Whether a schema can still be in the getter-refusing
  shape at the *close*, where the barrier is raised, was not shown: every attempt to construct one met a
  schema-altering refusal first, and narrowing the close-time catch back leaves the whole class green. The wide
  catch is kept at the two barrier sites anyway, because the outcomes are asymmetric — a bare refusal slipping
  past a narrow catch publishes exactly the state the barrier exists to stop, while catching one that did not need
  catching costs a deactivation of a catalog whose schema was already exchanged and whose operation already
  failed, which is what this design does everywhere else.
- **Only the warm-up branch.** `validateCatalogSchema` is also called transactionally, where it marks the
  transaction rollback-only and the state unwinds properly. Nothing changed there.
- **A family of shutdown defects was fixed in passing**, all pre-existing and all made routine by this change,
  since a client that reacts to the refusal by shutting down now has a lifecycle mutation in flight while the
  engine closes. They share one shape: `Evita#closeCatalogs` clears the engine state *before* draining the
  mutations still in flight, so every in-flight lifecycle mutation fails at its completion updater, and whatever
  that updater was going to do is skipped.
  - `EngineTransactionManager#close()` joined the pending engine mutations and let their *failure* propagate,
    skipping `enginePersistenceService::close` — so the engine's folder lock stayed held and the next start died
    with `FolderAlreadyUsedException` against a process that had already exited. The wait is now completion-only.
  - `updateEngineStateAfterEngineMutation` dereferenced the cleared state. It now refuses with
    `InstanceTerminatedException`, ahead of the write-ahead log append, so **the mutation leaves no engine-level
    trace** — no WAL entry and no engine bootstrap record, which is what makes reporting it as failed accurate.
    It is *not* a claim that the mutation had no effect: the operator's work phase ran first and may already have
    published at the catalog level, `Catalog#goLive()`'s ALIVE bootstrap record being the clear case. Those
    effects are durable and stay durable, and the catalog reloads from its own bootstrap record whatever the
    engine recorded. The first version of this record said "nothing of the refused mutation is durable", which is
    the wider claim and false; the adversarial review caught it.
  - **Three operators stranded a real `Catalog` on that same path**, which is the defect the second bullet's fix
    made visible rather than fixed. Deactivation, creation and removal each hold the only reference to a real
    catalog while the engine state holds an `UnusableCatalog` placeholder — and `UnusableCatalog#terminate()` only
    flips a flag, so the shutdown pass that walked the engine state released nothing. A completion updater that
    threw then skipped the real catalog's `terminate()` entirely, leaving *its* folder lock held: the same
    `FolderAlreadyUsedException`, one level down from the one the first bullet fixed.
    `SetCatalogStateMutationOperator` (both branches), `CreateCatalogMutationOperator` and
    `RemoveCatalogSchemaMutationOperator` now release the catalog whether or not the state update lands. The
    removal path deliberately releases the handles **without** wiping the folder — the removal did not commit, the
    engine state still names that folder, and deleting a file the published record still reaches is the one act
    that damages a catalog for real (`.claude/rules/durability-model.md`).
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
- **The conflict key can refuse the deactivation itself, so `scheduleDeactivation` now retries it.** The catalog
  raises the barrier from inside `Catalog#flush`'s failure handler, which the go-live operator calls while its own
  lifecycle mutation is still finalizing — so the deactivation the barrier schedules races the very mutation that
  triggered it and is refused. It was previously logged and dropped, which left a catalog that refused everything
  and could only be cleared by a restart: safe, but flatly contradicting the recovery this record promises. Five
  attempts, 200 ms apart, wait out a key whose holder is already finalizing. Exhausting them keeps the old
  behaviour and the old message, because the safety property never depended on the deactivation landing. The
  returned `Progress` is now observed too — a deactivation accepted and then failing while being applied used to
  leave the catalog refusing with nothing in the log to say why.

### Termination racing a running warm-up call (#2014)

Termination is a publisher upstream of the session close too. `Catalog#terminateInternally` decided
`WARMING_UP && isPublishable()`, flushed every collection and stored the header, and nothing serialized that with a
warm-up call. Engine shutdown reaches it with a call still running whenever the session drain gives up after its 5 s
budget - `Evita#closeInternal` terminates the catalogs anyway - and published changes that only the session close,
which had not run, would have validated, possibly while the call was still making them. Measured before the change: a
schema evolved by an `upsertEntity` still running, and one evolved by a call starting after termination's decision,
both reached the disk.

- **The rule: termination publishes a warm-up catalog only when no call of its read-write session is running.**
  `Catalog#warmUpCallLock` is held by the outermost business invocation of a read-write session bound to a
  `WARMING_UP` instance (`EvitaSessionProxy`), including the forced close a drain defers to that thread, and taken by
  termination with a no-wait `tryLock()` before its decision and until the collections are released. When the lock is
  not free, termination publishes nothing and logs a WARN naming the catalog. It also sets the unpublishable cause
  directly, without scheduling a deactivation because the instance is being terminated anyway, so the running call's
  own close cannot flush the half-terminated instance once the call returns. A termination that holds the lock
  publishes as before, an idle registered writer's work included, and sets the same cause just before it releases the
  lock: a call queued behind it resumes before `terminate()` closes the persistence service, on released collections,
  and a collection it creates would otherwise publish a header naming that collection alone.
- **The engine executor must never take that lock.** An explicit warm-up schema change runs there while the session
  thread holds the lock and waits for it, and the lock is owned by a thread.
- **Declined: re-reading the schema version before the header write.** It only narrows the window, and it misses a
  call that was already running when termination decided: that call's changes predate the decision, so the version
  does not move before the header write (argued from the code, not run as a counterfactual).
- **Declined: never publishing while a session is registered.** Every shutdown that finds an idle healthy writer would
  lose its unflushed work.
- **Declined: reading the proxy's `insideInvocation` from `Evita#closeCatalogs`.** That is a check-then-act with no
  fence. `Evita#closeAllSessions` stops at the first registry whose drain throws, so the sessions of every later
  registry stay usable, and one of them can start a call right after the read.
- **Declined: taking the lock in the catalog's write paths.** Entity writes and schema changes enter through many
  methods on two threads, and the hop from the session thread to the engine executor turns a second level of locking
  into a deadlock.
- **A flush in progress is the other half (#2033), and termination does not publish beside one.** The flush a warm-up
  session close starts runs on the transaction executor after the proxy has released the lock - and the close a drain
  runs on its own thread for an idle writer takes no lock at all - so a termination that found the lock free could
  still meet that flush with some collections written and others not. Its own flush then found nothing trapped in the
  unwritten ones, because the flush in progress had already popped them, and it stored a header naming indexes whose
  parts only the held-back write would have stored. Measured before the change: the published state did not load
  (`Entity index with PK `1` was unexpectedly not found in the persistent storage!`). Read from the code rather than
  run: a restart answers that by marking the catalog `CORRUPTED`. It is the one write-side hazard of the durability
  model - a bootstrap record published from an in-memory state the files cannot back.
  `Catalog#warmUpFlushState` makes the two exclusive in one atomic word: the bits below `WARM_UP_TERMINATION_BEGUN`
  count the warm-up flushes in progress, and that bit says termination has begun. `Catalog#flush()` counts itself in
  by compare-and-set before it pops anything, and is refused once the bit is set - with `InstanceTerminatedException`,
  or with `CatalogUnpublishableException` caused by it when termination has already recorded its cause and the flush's
  first barrier check meets that. It counts itself out exactly once: still under the lock below at the end of a
  publication that succeeded; after the barrier stands when it failed, a synchronous failure after its pop included;
  and on completion when it was cancelled - in the last two even when raising the barrier throws, as scheduling its
  deactivation does on a shut-down scheduler. Counting out on completion alone was not enough: the dependents of the
  flush future run before the one registered first, so the session close - and a termination right behind it - would
  still have found a flush that had already published. A session close whose flush cannot even be submitted - a full
  or shut-down transaction pool - settles the flush exceptionally, so the flush's own failure handler raises the
  barrier over the changes it popped and counts it out. Termination sets the bit and reads the count in one step; when
  a flush is in progress it records the barrier cause and publishes nothing, with a WARN saying that the flushing
  session's unpublished work is dropped. `Catalog#warmUpPublicationLock` covers a flush's last step from its check
  that the bit is clear to the end of its header write, and termination takes it to set the bit, so a flush publishes
  either completely before termination begins or not at all, and the storage is never closed in the middle of a
  bootstrap write. The outcome is the last published state, which a reload recovers completely. Termination still
  releases every collection, clears the map - in a `finally`, so a publication that fails cannot skip it - and closes
  the storage. The flush's writes that still run fail on the released collections and complete its future
  exceptionally, and a session close waiting on it completes with it; writes an executor never runs are another
  matter, see the consequences.
- **A count, not a flag.** Warm-up admits one session at a time, so two flushes are not in progress at once today;
  counting keeps the guard independent of that admission rule instead of adding a refusal for a second flush.
- **Declined for now: a bounded wait for the flush in progress.** It would keep the flushing session's work, but the
  flush it waits for is by construction one the 5 s drain has already given up on, so the wait spends shutdown time on
  exactly the slowest flushes - and it still needs the refusal as its fallback when the bound runs out. The refusal
  loses only unpublished work, and reload recovers the rest completely. Worth revisiting if losing the last session of
  a slow idle writer at shutdown becomes a reported problem; the wait would then sit in
  `Catalog#beginWarmUpTermination`, bounded, falling back to the refusal.
- **Termination publishes the catalog index too (#2032).** Termination stored the header without the catalog's own
  trapped changes, so the catalog index - which holds the values of globally unique attributes, and answers lookups of
  them even within a collection - was published behind the collections: an inserted entity was not found by its unique
  value and the value could be stored on another entity, while a removed entity kept its value reserved. Both warm-up
  publishers now end in `Catalog#publishWarmUpState`, which writes the catalog's trapped changes and then stores the
  header. Declined: copying the missing write into termination - two hand-maintained copies of the publication recipe
  are how the gap arose.

## Verification

The full functional suite runs green: **23,717 tests, 0 failures**, the only error being `ExportS3ServiceTest`,
which needs a Docker environment this workstation does not provide.

`WarmUpRefusedSchemaPersistenceTest` (new, 14 tests) proves the guarantee on **three independent validation
rules** — the attribute filter-accelerator rule, the managed-reference rule and the reflected-reference rule — so
it is pinned to the warm-up write path rather than to one validator. Each refusal is asserted on its message, not
merely on the exception type, and each is paired with a control that pushes the *same* schema element through legitimately and finds it after a reopen,
so an empty assertion cannot pass because the reopened schema was read wrongly.

**Every refusal additionally asserts that the barrier was raised**, by waiting for the deactivation it schedules
to be reported on the system change stream (`CatalogUnpublishableBarrierAssertions#assertRefusalRaisesTheBarrier`,
extracted from `WarmUpRefusedSchemaPersistenceTest` into its own class once `AttributeFilterAcceleratorRefusalTest`
needed it too — see *Timeline*). Without that, each of these tests would also pass against a refusal thrown by a
*pre-flight* check — which leaves the catalog untouched and makes every assertion about the reopened schema hold
trivially. The distinction is one edit away from mattering: `SetAttributeSchemaAcceleratedMutation` deliberately
does not run the accelerator's own applicability check, and the sibling `CreateAttributeSchemaMutation` does.
`Recovery#shouldDeactivateTheCatalogAndHandBackTheLastPublishedStateOnActivation` covers the whole story: an
entity written by a session that closed successfully survives the recovery, and one written by the refused session
does not.

`AttributeFilterAcceleratorRefusalTest#shouldRefuseAnAcceleratorDeclaredOnAnAttributeWithNoFilterIndex` was a
characterisation asserting the wrong outcome deliberately; it now asserts `Set.of()` and, like every case above,
asserts the barrier explicitly rather than relying on the exception type alone.

`InterlinkedCollectionRemoval` covers the **removal** direction, which reaches the same validation from the
opposite side: an additive change declares something the catalog cannot satisfy, a removal takes away what an
existing declaration relied on. It is the case an operator meets by accident — dropping one half of a circular
product/category link, where the product owns the reference and the category reflects it — and it carries one
hazard the additive cases do not, because a removal parks the collection's data file for retirement. The tests
therefore assert the catalog folder as well as the schema: a refused removal must leave every data file the
published bootstrap record still names, and the one-session control must see both retirements released. **The
control is the part that matters most**, because without it every refusal above would also be satisfied by an
engine that simply refused to drop a referenced collection at all: dropping *both* sides inside one session
succeeds, in either order, and neither order validates halfway through. That is the same tolerance
`flushMidSessionIfSchemaValid` grants a reflected reference declared before its target, and it makes "both sides
of a circular link must be dropped in the same session" the operator-facing rule.

**One of these tests is calibrated and four are not, and the comments at the site say which.** Measured by
disabling the `isSchemaValid()` guard: only
`shouldNotPublishTheHalfTornPairWhenTheSameSessionThenDefinesAnotherCollection` fails (the reopened catalog then
holds `[CATEGORY, BRAND]` — the removal published and the product collection is gone from the disk). The
removal-only refusals pass either way, because a mid-session flush on that path is a no-op for a second,
independent reason: `Catalog#flush` derives `changeOccurred` from the catalog schema version, and `updateSchema`
exchanges that version only *after* `removeEntitySchema` has returned, so at the moment of the flush no version
has moved and the removed collection is already out of the map that would report one. The guard bites only once
something else in the session supplies a change.

`MidSessionPublication` covers the two cases the barrier cannot reach, and both were written as failing tests
before the gates existed: a session that applies the offending mutation and *then* defines another collection
(the refused reference was on disk — `expected: <false> but was: <true>`), and one that calls `goLiveAndClose`
(no exception was raised at all, and the catalog went ALIVE carrying the invalid schema).

**Both were found by adversarial review after this decision had already been implemented and its tests were
green.** The first implementation had five tests passing and a full suite of 23,717 green, and was wrong: it
asserted the barrier's completeness without testing anything upstream of the barrier. The review that found it was
asked specifically to attack completeness — "is there any route that writes a bootstrap record in WARMING_UP that
does not consult the barrier?" — which is the question the record itself should have provoked.

**Counterfactual:** with the single `markUnpublishableDueToInvalidSchema` call removed and everything else
unchanged, all four of the close-time guards fail — the two repros (`expected: <[]> but was: <[SUBSTRING_SEARCH]>` and
`expected: <false> but was: <true>`), the characterisation flip, and the recovery test (`stayed at WARMING_UP
instead of reaching INACTIVE`) — while the other 21 tests in the two classes still pass. The folder-lock fix has
its own counterfactual: before it, the reopen in every repro died with `FolderAlreadyUsedException`.

The barrier assertion has one of its own, and it isolates cleanly. With only the `scheduleDeactivation()` call
removed — so the barrier is still raised and still stops every publication — exactly the five tests that assert
it fail, all on `Catalog 'testCatalog' was never reported as having settled into 'INACTIVE'`, while the two
controls pass. That is the assertion carrying its own weight rather than riding on the persistence assertions
beside it.

`CatalogWarmUpTerminationTest` (4 tests) covers termination with the writer still registered, reading every published
state from a fresh engine over a copy of the storage. Before the change both running-call tests failed
(`expected: <false> but was: <true>` on the published reference) and both idle-writer controls passed. With the proxy
taking no lock, both fail again. Without the cause set on a skipped publication, the first fails on "a termination that
skipped its publication must leave the instance unable to publish"; without the cause set before a publishing
termination releases the lock, the idle-writer control fails on its own `isPublishable()` assertion. The gap that cause
closes - a queued call resuming between the unlock and the close of the persistence service - has no deterministic
reproduction.

`CatalogWarmUpTerminationBesideFlushTest` (10 tests) drives the flush a warm-up close starts - `Catalog#flush()`
executed on an executor that holds its collection writes back, the one seam there is, because the close dispatches to
the engine's own transaction executor - and, where the termination itself must be held, a spy that pauses it once it
has begun. One test per transition, each failing on its own counterfactual:

- termination meeting a flush with one collection written: before the change, and again with termination blind to the
  flush in progress, the published state does not load (`Entity index with PK `1` was unexpectedly not found`). The
  test also asserts that every collection is released, the flush future and the writer's close complete, and the WARN
  names the dropped work;
- a flush finishing after termination began: with neither the termination-bit check nor the barrier re-check in its
  last step the flush publishes (`ended with: null`) - either check alone refuses it, because termination records the
  barrier when it finds a flush in progress; with termination not recording the cause under the lock, the flush's
  refusal schedules a deactivation and the reloaded catalog is `INACTIVE`;
- a flush starting after termination began: without the compare-and-set refusal, `nothing was thrown`;
- a header write under way: the test waits, within the positive bound, until the termination is queued on the
  publication lock the held header write owns; with termination taking no lock it finishes without ever queuing
  (`the termination must wait for a header write under way` fails);
- a termination right behind a flush that published - run from a dependent of the flush future, as the session close
  registers one: with the flush counted out on completion only, the termination still counted it and warned that its
  work was dropped;
- a real session close waiting on its flush - the engine restarted with two transaction workers, both held, so the
  close queues the writes of its flush and returns waiting: the termination publishes nothing, and once the workers
  are released the close completes exceptionally; with termination blind to the flush in progress the published state
  does not load;
- a session close whose flush cannot be submitted - the same pool held and its backlog full: before the change the
  catalog stayed publishable (`expected: <false> but was: <true>`) over the changes the stranded flush had popped;
- raising the barrier when the scheduler is shut down - the state of an embedded engine whose close has shut its
  service executor down - so scheduling the deactivation throws after the cause is recorded: a flush failing in its
  last step, with a termination registered behind it the way the session close's completion is, and a cancelled
  flush followed by a termination. Before the count-out moved into a `finally` in the failure handler and in the
  cancellation backstop, both terminations still found the flush in progress and warned that its work was dropped;
- a flush finishing before termination, the control, publishes the write whole.

`CatalogWarmUpTerminationCatalogIndexTest` (6 tests) covers the catalog index. Before the shared publication tail, both
idle-writer tests failed - an inserted product missed by both lookups of its globally unique value (`expected:
<[PRODUCT:2]> but was: <[]>`) with a duplicate accepted, and a removed product's value still mapped and refused to
another entity - while both closed-writer controls passed. Two more cases inject a failure to pop trapped changes: a
flush whose collection fails after the catalog index was popped left the catalog publishable (`expected: <false> but
was: <true>`), and a termination whose publication failed left the collections in the catalog's map (`expected: <true>
but was: <false>`).

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
- **`.claude/rules/durability-model.md`'s "In `WARM_UP` every flush publishes" is no longer unconditionally
  true.** A warm-up DDL flush is skipped when the schema does not currently validate. The rule's *point* survives
  untouched — a flush that does run still publishes, and there is still no deferred checkpoint in warm-up — but
  the sentence now carries a qualifier saying which flushes run, because anyone reasoning "warm-up flushed,
  therefore it is durable" needs to know about the gate.
- **The two gates ahead of the barrier are an enumeration, and enumerations rot.** A future publication route
  added upstream of the session close will not be covered by anything here. The chokepoints were chosen to make
  that unlikely — the operator is the single funnel for go-live, and `flushMidSessionIfSchemaValid` is the single
  helper the three DDL flushes now share — but this is the part of the design a later change can silently break.
- **On the removal path the schema gate is not the only thing holding, and the second mechanism is accidental.**
  `Catalog#flush`'s `changeOccurred` check makes the mid-session flush a no-op immediately after
  `Catalog#removeEntitySchema`, because the catalog schema version is exchanged only after it returns. That is a
  belt beside the braces today, but it is not a designed guarantee, and reordering the exchange would remove it
  without any test noticing — only
  `InterlinkedCollectionRemoval#shouldNotPublishTheHalfTornPairWhenTheSameSessionThenDefinesAnotherCollection`
  covers the schema gate itself on this path.
- **What the termination lock does not cover (#2014).** Termination never validates, so an idle registered writer's
  unvalidated schema is still published. And `Evita#closeAllSessions` leaves every registry after the first failing
  drain undrained, so their sessions stay usable while their catalogs terminate. A session close's flush that is
  already running is covered since #2033 - see *Termination racing a running warm-up call*.
- **What the warm-up publication guard does not cover.** Each read from the code, none reproduced:
  - *Rename racing termination.* A warm-up rename replaces the bootstrap record through
    `CatalogPersistenceService#replaceWith`, and nothing serializes that with a termination closing the same service.
  - *Go-live's ALIVE bootstrap write.* `Catalog#goLive()` writes it after the go-live flush has completed, outside the
    publication lock, so a shutdown overlapping a go-live that has already flushed is not serialized with it.
  - *A compaction finishing after teardown.* A collection write that compacts registers its new storage service with
    the catalog's service even when termination has closed that service meanwhile, and nothing closes the late one.
  - *Two overlapping direct `Catalog#flush()` calls.* The count tolerates them, but a flush's last step does not look
    for another flush, so one can publish while the other's popped changes are still unwritten. Not reachable through
    sessions: warm-up admits one session at a time, and its mid-session flushes are joined.
  - *Writes an executor never runs.* A forced executor shutdown - `shutdownNow` after the 60 s grace - discards queued
    collection writes, leaving their flush, and a session close waiting on it, pending.
  - *Progress reported under the publication lock.* The last step reports the catalog's written trapped changes to the
    flush's progress consumer while it holds the lock, so a consumer that blocks holds termination up with it.
  - *Cancellation visible before the barrier.* The backstop for a cancelled flush is a completion dependent, so the
    flush's remaining writes and last step can still run - and publish - before the barrier stands. Nothing cancels a
    warm-up flush today.
  - *A transaction pool of one worker (inherited).* A warm-up schema change joins collection writes it submits to the
    pool its own operator occupies, and engine shutdown queues catalog termination on that same pool, so with one
    worker both starve.

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
- **2026-09-08** — adversarial review found two publications upstream of the barrier that the implementation did
  not cover; both reproduced, both gated, this record corrected
- **2026-09-08** — PR #1520 opened; Copilot and `claude[bot]` review found a stale `@link` parameter type on
  `Catalog#markUnpublishable`'s javadoc (fixed) and that
  `AttributeFilterAcceleratorRefusalTest#shouldRefuseAnAcceleratorDeclaredOnAnAttributeWithNoFilterIndex` checked
  disk state without asserting the barrier itself — exactly the gap this record's own *Verification* section
  already named as "one edit away from mattering". The barrier-assertion machinery was extracted from
  `WarmUpRefusedSchemaPersistenceTest` into `CatalogUnpublishableBarrierAssertions` so both test classes share it,
  and the flagged test now asserts the barrier too. Full functional suite re-run green after both fixes.
- **2026-09-10** — the removal direction was found uncovered while answering a question about
  `Catalog#flushMidSessionIfSchemaValid`: measured, dropping one half of a circular product/category link
  deactivates the catalog, and dropping both halves inside one session succeeds in either order. Neither shape
  had a test. `InterlinkedCollectionRemoval` added (5 tests), including the folder assertions for the parked
  data-file retirements and the calibration note recording which of them the `isSchemaValid()` guard actually
  holds up.
- **2026-11-18** — termination found publishing while a warm-up call was still running (#2014). A lock shared by the
  warm-up writer's calls and termination was chosen over re-reading the schema version and over never publishing while
  a session is registered, and termination does not wait for the call
- **2026-11-19** — termination found publishing beside a session close's flush already in progress (#2033), and
  publishing the collections without the catalog index (#2032). The flush and the termination were made exclusive
  through one atomic word plus a lock around the header write, the refusal was chosen over a bounded wait, and both
  publishers were given one publication tail. A review of the change then found a flush failing after its pop and a
  flush the session close could not submit leaving the barrier down, and a failed termination publication skipping
  the release of the collection map - each fixed with a test - and the uncovered paths listed in the consequences
