---
title: Restore a live catalog to an earlier version by composing backup, restore, activate and replace
date: 2026-09-12
updated: 2026-09-14 11:49
status: accepted
kind: feature
issues: [1553]
prs: []
areas: [evita_api/io.evitadb.api, evita_engine/io.evitadb.core.management, evita_external_api/evita_external_api_grpc]
supersedes: []
superseded-by: []
relates: [2026-08-06-catalog-folder-decoupling, 2026-08-06-time-travel-disk-budget]
---

# Restore a live catalog to an earlier version by composing backup, restore, activate and replace

`EvitaManagementContract#restoreCatalogToVersion` puts a catalog that is in service back to the state
it had at an earlier version, as one task a client can watch. It creates a point-in-time backup of the
requested version, unpacks it into a temporary catalog, loads that catalog, and swaps it into the
target name. Only the swap is client-visible, and it is the pointer swap `replaceCatalog` already
performs, so it takes the same negligible time whatever the size of the catalog.

## Why

evitaLab wants a *Restore to this version* button next to each entry of a catalog's mutation history
([evitalab#161](https://github.com/FgForrest/evitalab/issues/161)). Every piece it needs already
existed and none of them composed: a client had to create a backup, notice when the task finished,
find the produced file among the downloadable ones — there was no way to match the file back to the
request that produced it — restore it under a name that did not clash, activate the result, and
finally replace the original. Five calls, two of which the client could not correlate, and a window
in the middle where a half-finished restore sat visible under a made-up name.

The constraint that made this non-obvious is that the catalog must **keep serving throughout**. That
rules out restoring in place, and it is why the operation needs a temporary catalog and a swap rather
than a restore that overwrites.

## Options considered

### Option A — compose the four existing public operations (chosen)

Back up at the requested version, restore into `<catalogName>_restore_<8 hex>`, activate it, replace the
target with it. Every step is an operation that already exists, is already tested, and is already
reachable on its own.

- **Pros:** no new code in `evita_store`; the archive format, the folder-claim handover and the swap
  are exercised by the same code paths clients already use, so the new operation cannot drift from
  them. Failure handling is per-step and already written.
- **Cons:** the catalog is written twice — once into a ZIP, once out of it — so the operation costs
  roughly two full passes over the data and a transient archive in the export directory.

### Option B — file-level historical duplication (declined)

`DefaultCatalogPersistenceService#duplicateCatalog` already copies a catalog's files into a fresh
folder without any archive. Teaching it to copy the file set named by a *historical* bootstrap record
would produce the temporary catalog directly.

- **Pros:** roughly halves the I/O and removes the export-directory disk spike entirely.
- **Cons:** `duplicateCatalog` has **no concept of a historical version at all** — it copies whatever
  `Files.walk` finds in the live directory right now, including, by its own comment, "generations no
  retained bootstrap record points at". Resolving which bytes a given bootstrap record reaches is
  exactly the machinery `BackupTask` already carries (`getCatalogBootstrapForSpecificVersion`,
  `createCatalogOffsetIndexStoragePartService`, `copySnapshotTo`, `getServicesAndStatistics`), so this
  is not "reuse the copy and skip the zip" — it is re-implementing most of `BackupTask` inside
  `evita_store`, beside the copy that already exists.
- **Rejected because:** the ZIP round trip costs **time and disk, never availability** — the catalog
  being replaced serves throughout, and the swap is a pointer swap either way. Paying a second
  implementation of the hard part to save time nobody is waiting on is the wrong trade, and the
  "halve the I/O" framing understates what would have to be built. Revisit if the operation proves too
  slow on a production-sized catalog, or if the export directory's size limit turns out to be the
  binding constraint on a real deployment — that second trigger is the more likely of the two.

### Option C — include the write-ahead log in the archive (declined)

The backup API takes an `includingWAL` flag, and setting it would preserve the catalog's mutation
history across the restore, so the result could itself be taken back to an earlier version later.

- **Pros:** the restored catalog keeps its history; the operation becomes repeatable and, in effect,
  undoable.
- **Cons:** none that are recoverable — see below.
- **Rejected because:** it does not do what it appears to do. `BackupTask` copies **every** `.wal`
  file wholesale, including the records written *after* the version being restored, and a restore
  replays them forward from the bootstrap record to the head of the log. The result is the current
  state — precisely the state the operation exists to escape. Revisit only if the backup gains the
  ability to truncate the log at a chosen version, which is a change to the archive's contents, not a
  flag on this operation.

## Decision

**Chosen: Option A**, with the log excluded per Option C's rejection.

The deciding driver is that availability — the thing this feature is actually about — is unaffected by
the choice, so the cheaper-to-maintain option wins. Option B would become right if the doubled I/O
ever showed up as a real operational limit; the seam for it is `CatalogContract#createBackupTask`,
which already hands back an unqueued task, so swapping the first step for a direct file copy would not
disturb the rest of the sequence.

**The consequence of excluding the log is stated loudly rather than worked around**: the restored
catalog has no mutation history, so it cannot itself be restored to an earlier version afterwards. Its
history starts at the restore. This looks like an oversight to anyone reading the code — the flag is
right there — which is why it is written down in three places: the contract javadoc, the user
documentation and here.

## Key technical details

- **Entry point:** `EvitaManagement#restoreCatalogToVersion` builds a
  `SequentialTask(BackupTask, PublishRestoredCatalogTask)` under the stable task type
  `EvitaManagement.RESTORE_TO_VERSION_TASK_TYPE`, which is what a monitoring client filters on.
- **Two steps, not five, and that is forced.** The unpacking step must be told the archive's id, path
  and size at construction time, and `ExportService` chooses all three *while the backup runs*. No
  rearrangement makes a flat five-step sequence constructible; `PublishRestoredCatalogTask` builds the
  restoration steps at the moment their inputs exist.
- **The archive is read back through `ExportService#fetchFile` into the work directory**, never from a
  path computed from `FileForFetch#path(...)`. `evita_export_s3` exists — an export service is not
  necessarily a local file system, and `RestoreTask` needs a local file. The copy that looks redundant
  is what makes the operation work on an object-store deployment.
- **`replaceCatalog` tolerates a target name no catalog holds.**
  `ModifyCatalogSchemaNameMutation#verifyApplicability` requires only the *source* to exist; a target
  nothing holds is validated for uniqueness like any new name, and an occupied one needs
  `overwriteTarget` and is otherwise taken as it stands. The operator reads the target via
  `getCatalogInstance(...).orElse(null)` and null-guards each use. One uniform flow therefore covers
  replace-in-place, replace-another-catalog and create-new — **do not add branching for the three
  cases**, they are the same case.
- **The same `overwriteTarget` skips `CatalogSchema.checkCatalogNameIsAvailable`**, so a brand-new
  target name is validated by `restoreCatalogToVersion` itself. Remove that check and a name colliding
  with an existing catalog in some naming convention fails deep inside the restore instead of before
  any work starts.
- **The backup task pins the version it copies in its constructor** and gives the pin back only when
  it is executed or cancelled. `restoreCatalogToVersion` builds it before submitting the sequence, so
  it cancels it if the submission throws — a dropped task would freeze this catalog's retention floor
  for the rest of its life. Same reasoning, same shape as `Catalog#submitBackupTask`.
- **Cancellation stops at a phase boundary, never mid-mutation.** `CompletableFuture#join` ignores
  interrupts, so a cancel landing inside an engine mutation is not observed until that mutation
  returns; `PublishRestoredCatalogTask#abortIfCancelled` is the checkpoint between phases.
- **Compensation is asymmetric on purpose.** A successful run deletes the intermediate archive; a
  failed or cancelled one keeps it (it is a complete backup of the requested version, and the
  operator's cheapest manual retry) and drops the temporary catalog instead.

## Verification

`CatalogRestoreToVersionTest` (17 methods, embedded engine, time travel on) covers: restoring in place
by version and by moment, `catalogVersion` winning over `pastMoment`, restoring into a free name and
over an occupied one, the restored catalog being writable and surviving a restart, and the four
refusals — an unretained version, an unknown source, a malformed target name, and a target name
colliding in a naming convention — each asserting that nothing is left behind. The task surface is
covered too: the operation is findable by its task type, reaches 100 %, and removes its archive.

`EvitaClientReadWriteTest#shouldRestoreCatalogToVersionUnderANewNameViaDriver` and
`#shouldRestoreCatalogToVersionInPlaceViaDriver` exercise the same operation over gRPC against a real
server, including the `catalogVersion` wrapper on the wire.
`ReadOnlyEvitaTest#shouldFailToRestoreExistingCatalogToAnEarlierVersion` pins the read-only refusal.

The fixture commits one entity per transaction, so the version a restore landed on is readable
straight off the collection size — an off-by-one restore fails rather than looking plausible.

## Consequences & open follow-ups

- **The operation is irreversible in both directions.** The replaced catalog is purged with its whole
  history, and the restored one has none. Taking a full backup first was considered as an automatic
  safety net and declined: it doubles the cost of every invocation to insure against a decision the
  operator has already been warned about, and the existing `fullBackupCatalog` is one call away.
- **Writes racing the operation are lost silently.** The catalog keeps accepting writes until the
  swap, and everything committed after the selected version goes with the purged catalog. Making the
  source immutable for the duration was declined — it would cost write availability for the whole
  restore, which on a large catalog is minutes, to protect against a window the operator chose to
  open.
- **A crash between the temporary catalog being registered and the swap committing leaks it.** The
  cleanup runs in a `finally`, so it needs the JVM to survive; nothing sweeps the leftover at boot,
  because by then it is an ordinary registered catalog and the `.provisional` folder marker — the one
  mechanism boot-time cleanup does consult — has already been cleared by the registering step. The
  mitigation shipped here is legibility rather than recovery: the scratch catalog is named
  `<source>_restore_<hex>` precisely so an operator can recognise it, and the user documentation says
  to look for it after a crash. **A real fix needs a durable "this catalog is scratch" bit in
  `EngineState`**, which is a persisted Kryo format change with backward-compatible readers — out of
  proportion to this feature, and the reason it was not attempted here.
- **Two concurrent restores of the same catalog are not serialised.** Each gets its own temporary
  catalog and the second swap wins; no corruption, because `ModifyCatalogSchemaNameMutation` declares
  conflict keys for both names and the engine serialises the commits — but the first operator's
  restore is silently undone. Left alone: the engine has no per-catalog *operation* lock to hang this
  on, and the scenario requires two administrators pressing the same button at once. Untested, and
  stated here as reasoning from the conflict keys rather than as a measured result.
- **`ModifyCatalogSchemaNameMutation` was waiving a uniqueness check on a flag that does not speak to
  it.** `verifyApplicability` skipped `checkCatalogNameIsAvailable` whenever `overwriteTarget` was set.
  The reasoning behind that skip is sound but narrower than the condition expressing it: replacing an
  *existing* catalog only ever removes a name from the set - the old name of the catalog moving in -
  and a set that was unique cannot stop being unique that way, so the target's own name needs no
  re-validation. What the flag actually says is that the caller *intends* to take a target over, not
  that there is one there. `replaceCatalog` accepts an absent target and renames into it, which is a
  genuinely new name entering the set with nothing having checked it - and this feature depends on
  that path for its "create the target when the name is free" case. The check is now keyed to the
  state rather than to the intent: an occupied target skips it, a free one clears the same bar a
  rename does. Two questions that had been conflated, separated - whether an occupied target may be
  taken over is the caller's to declare, whether the resulting names are unique is not.
  The check measures the new name against every catalog *except the one being renamed away*, which is
  a second defect the first one had been masking: `checkCatalogNameIsAvailable` compares against the
  whole live set, so a rename of `myCatalog` to `my_catalog` asks whether the catalog collides with
  itself and is refused, even though the name it collides with leaves the set in the same act. The
  replace path had never reached that code before and so had never shown it; the rename path had, and
  was wrong for as long as it existed.
- **Follow-up ([#1573](https://github.com/FgForrest/evitaDB/issues/1573)): make the folder generation
  engine-global instead of per-name.** The asymmetry recorded
  below — a minted name may be retired, a client-chosen one may not — is a rule a future reader can
  get wrong, and it exists only because the counter is keyed by name. One engine-wide counter removes
  the question entirely: nothing accumulates, so nothing needs retiring, and "a token never repeats"
  stops being a promise kept by abstinence and becomes a property of a counter that only moves
  forward. It would delete `SequenceService#removeSequences`,
  `Evita#retireCatalogGenerationSequence` and the per-name `CatalogGenerationPeak` seeding. The window
  is open and cheap: `CatalogFolderId` and the `<name>_<generation>` convention landed on 2026-08-06
  and are contained in no release branch and no tag, so no installation has a generation-suffixed
  folder produced by a released build, and `generationPeaks` is a 2026.3-only field whose released
  reader (`EngineStateSerializer_2026_2`) never saw it. The costs are small: the folder suffix becomes
  sparser and shared across names — it is already an *allocation-attempt* counter rather than an
  incarnation count, so little is lost — and the boot seed becomes a global maximum. **It does not
  close the cross-restart half**, which stands exactly as recorded below. Deliberately kept out of
  this line of work: it touches the boot seed and folder classification, where a mistake makes
  catalogs unloadable, and it is a genuine fork that deserves its own record.
- **A name is not an identity, and the swap now says which catalog it meant.** The target is chosen
  when the operation is submitted and used minutes later, after a backup, an unpack and a load. In
  between the name may be dropped, dropped and recreated, or — if it was free — taken. Acting on the
  name alone destroys a catalog nobody asked about and reports success for it. The same is true at
  the other end: the scratch name is ordinary once `RestoreFolderClaim` is released, so another
  operation may drop this restore's scratch catalog and create its own under that name.
  Both ends are now closed by one mechanism. `EngineMutationPrecondition` records what a name must
  still be bound to, and `EngineTransactionManager#applyMutation` tests it **after**
  `verifyApplicability` and **before** conflict-key registration, inside `engineStateLock`. That
  placement is the point: from registration onwards the mutation's own `CatalogConflictKey`s hold
  both names until it completes, so the precondition covers exactly the interval the keys do not —
  submission to acceptance — and the two compose with no gap and no overlap.
  **The identity compared is the folder token**, not the catalog's UUID and not its health.
  `UnusableCatalog#getCatalogId` throws, and a corrupted target is precisely when getting this wrong
  costs most; the question being asked is whether the catalog was *substituted*, not whether it is
  well. A folder token answers it for a catalog in any state.
  **That required making folder generations monotonic**, which is a change to pre-existing engine
  behaviour and the part most likely to surprise. A token is `name_generation`, and the generation
  came from a counter that was *retired* once a name's last tombstone was discharged — so a catalog
  dropped, drained and recreated redrew generation 1 and reproduced a byte-identical token. An
  expectation recorded against the old catalog would have been satisfied by the new one: an ABA, and
  reachable exactly in the common case where the observed catalog was the name's first incarnation.
  The retirement is therefore gone. Most of what it protected is not worth protecting: the
  engine-scoped service holds only `CATALOG_GENERATION`, so it keeps one entry per *distinct* catalog
  name rather than one per create/drop cycle — creating and dropping the same catalog a million times
  costs one entry — and the set of names a client chooses is small and does not grow with traffic.
  **That reasoning covers names a client chooses, and this feature mints names it does not.** The
  scratch catalog is `<source>_restore_<hex>`, fresh per invocation precisely so it can never collide,
  so the set of names the process has materialised grows by one on every restore and never shrinks.
  The bound above simply does not apply to it — caught in review, after the first version of this
  record claimed it did. `PublishRestoredCatalogTask` therefore hands the scratch name's counter back
  through `Evita#retireCatalogGenerationSequence` when the task ends, on every outcome.
  **What licenses that is the absence of a holder, not the state of the disk.** Retiring a counter
  lets it restart, so the generations it handed out become drawable again; allocation burns a
  generation whose directory it cannot create, which means a number is only genuinely redrawable once
  its folder is gone. That is the wrong question to ask, and asking it gets the answer backwards — a
  folder still present is what makes a redraw harmless. The condition that matters is that nothing can
  still hold an expectation against the name. The scratch name satisfies it by construction: it is
  published to nobody, the only expectation ever recorded against it is the swap's own — consumed by
  then, or never created because the restore failed earlier — and a second restore drawing the same
  name is refused by `CatalogFolderContext#allocateFolderFor`'s reservation before it could record
  one. A client-chosen name satisfies none of this, because an operation may hold an expectation
  against it for as long as a backup, an unpack and a load take and nothing tracks that it does, so
  those counters stay for the life of the process.
  **The guarantee is bounded by the process**, deliberately. The counter is in memory and nothing
  records a durable `CatalogGenerationPeak`, so generations can repeat across a restart. That is
  sound here because an in-flight restore cannot outlive the process that started it — but it is a
  precondition on *use*, written on `EngineMutationPrecondition`: the mechanism must not be given to
  anything resumed from durable state.
  **Rejected: recording generation peaks** to close the cross-restart half too. The record, its
  serialization and `Evita#seedCatalogGenerationSequences` all already exist and only the write is
  missing, so it is smaller than it sounds — but it puts a new obligation on every operation that
  draws a generation, for a case no current caller can reach. Worth doing when a preconditioned
  operation needs to survive a restart; not before.
  **Rejected: a per-catalog incarnation UUID in engine state.** The cleanest semantics and immune to
  both ABA paths, but it changes the `EngineState` format and its serializer for a property the
  folder token already carries once the counter stops going backwards.
  **Rejected: reserving the target name** through `CatalogFolderContext#allocateFolderFor`, the
  mechanism `RestoreFolderClaim` already uses for the scratch name. It creates a provisional
  directory purely as a mutex — one the restore never writes to and has to reclaim — it holds a name
  for minutes, and it guards only *materialisation*, so it says nothing about a target that already
  exists.
- **Refusing an absent target is a behaviour change, and the intended one.** `replaceCatalog`
  tolerates a target name no catalog holds, and a restore aimed at an occupied name that has since
  been dropped could therefore still publish into the free name. It no longer does: the request was
  compare-and-replace of the catalog observed at submission, and once that catalog is gone,
  degrading to "publish into whatever name is free" is not the same operation. Documented on
  `EvitaManagementContract#restoreCatalogToVersion`.
- **Conflict keys were exact-name while applicability is convention-wide; they now agree.**
  `CatalogConflictKey` wraps one `String`, so a swap into `reports_archive` and a create of
  `reportsArchive` emitted disjoint keys and were never serialised against each other — while
  `checkCatalogNameIsAvailable` treats them as the same name. Both passed a uniqueness check that
  neither could yet see the other's result, and both committed, leaving two catalogs whose names
  collide by convention, durably and across restart.
  The race was **asymmetric**, which is why it had gone unnoticed: a create accepted first installs
  its `BEING_CREATED` placeholder synchronously, while still holding `engineStateLock`
  (`CreateCatalogMutationOperator:124`, before its future at `:131`), so a later swap fails its
  convention scan. But `ModifyCatalogSchemaNameMutationOperator` runs its transition updater zero
  times on the success path — its comment at `:243` calls this the codebase's only such exception —
  and publishes at `:493` inside its asynchronous completion. So a swap accepted first is invisible
  to a later create.
  `CatalogConflictKey#forIntroducedCatalogName` now returns the raw name plus every
  `NamingConvention` variant, and the four mutations that *introduce* a name use it: create,
  restore, duplicate (for `newCatalogName`) and rename/replace (for `newCatalogName`). This works
  despite the asymmetry, because `verifyEngineMutationIsNotInConflictWithOthers` consults the
  registered key map rather than engine state, so it does not care that the rename has published
  nothing yet.
  **The raw name is claimed alongside the variants, and that is not redundant.**
  `ClassifierUtils#validateClassifierFormat` admits names no convention reproduces —
  `Reports_Archive` generates none of itself — so a variant-only claim would stop intersecting the
  keys of every catalog-scoped mutation, which key the literal name (`AbstractAttributeSchemaMutation`
  and the four evolution/description mutations beside it). That would have traded one race for
  another.
  **A name that is given up, or merely acted on, keeps its literal key.** Only the introduced name is
  widened — the rename's source, the duplicate's source, and every single-catalog mutation are
  already unique by construction, so widening them would serialise unrelated work for no benefit.
  The transactional commit path is untouched: `DefaultIsolatedWalService` still keys the literal
  catalog name.
  **The claim is marginally wider than the uniqueness rule, not identical to it.** Uniqueness
  compares two names *within one convention* (`CatalogSchema` looks the new name up by the existing
  variant's own convention); intersecting key sets also match across conventions. No pair of names is
  believed to satisfy the second without the first, since the conventions render into mutually
  exclusive character shapes — but it is a superset, so this must not be read as licence to answer
  the uniqueness question with a key lookup.
- **A failure inside the swap discards the restored copy; the target survives.**
  `ModifyCatalogSchemaNameMutationOperator` has a point of no return - the storage handover - and a
  failure past it declares a catalog `CORRUPTED` instead of compensating, because resuming sessions
  against a folder whose stored identity no longer agrees with engine state is the worse outcome. The
  catalog it declares corrupted is `catalogNameToBeReplacedWith`, which is the **source** - the
  restore's scratch copy - not the target: `mutation.getCatalogName()` is the replacement and
  `getNewCatalogName()` the name it is taking over. So the target keeps serving its previous contents,
  the engine-state exchange having never committed, and the restore's clean-up then drops the scratch
  catalog that held the restored data.
  A restart does not adopt that data either. Names are bound to folders in engine state, that binding
  never changed, and `DefaultCatalogPersistenceService#reconcileStoredCatalogIdentity` resolves a
  folder whose stored name disagrees with its binding by rewriting the *stored* name to match - so the
  relabelled folder is renamed back to the scratch name rather than claiming the target's.
  Nothing is done about it here: it is the operator's designed behaviour and predates this feature.
  What this work adds is contract and user-documentation wording that says so. An earlier draft of that
  wording had it backwards - it named the target as the corrupted catalog and promised a restart would
  surface the restored data - which is why the direction is spelled out here rather than left to the
  reader to re-derive.
- **The intermediate archive competes with the export directory's size limit.** `purgeFiles` drops the
  oldest files whenever the directory exceeds `export.sizeLimitBytes` (1 GiB by default) and holds no
  reference count, despite the interface javadoc suggesting it spares files a reader needs. A catalog
  whose archive alone outgrows that limit cannot be restored this way. Not fixed — reference-counting
  the export directory is its own piece of work — but `PublishRestoredCatalogTask#fetchArchiveInto`
  turns the resulting `FileForFetchNotFoundException` into a message that names the cause, so the
  operator raises the limit instead of hunting a phantom.
- **The archive fetch and the swap report only their boundaries; unpacking and activation report
  fine-grained progress.** The two that report do so by different mechanisms, because the two
  progress APIs differ in kind. Activation forwards a `Progress`, which can be *subscribed* to via
  `addProgressListener`. Unpacking has no listener to offer — a `ServerTask` publishes progress only
  through its status — and it cannot be polled from this task's own thread either, since the sequence
  runs inline and that thread is inside it for the whole phase. So it is *pulled*:
  `PublishRestoredCatalogTask#getStatus` derives the unpacking's share of the band from the in-flight
  `SequentialTask` at the moment a client asks, the same trick `SequentialTask` itself uses to derive
  from its steps. The archive fetch stays flat because `IOUtils.copy` offers no progress callback, and
  the swap because it is a single commit with nothing inside it to report. The band widths
  (10/45/35/10) are an unmeasured guess at relative phase cost.
- **`SequentialTask#getStatus` was aggregating step progress with `|=` where it meant `+=`** — two
  steps at 100 % and 50 % reported 59 % instead of 75 %. Invisible while the only shape was two steps
  averaging `100 | 0`, where OR and addition agree. Fixed here because this feature made the sequence
  wider; covered by `SequentialTaskTest`.
- **REST and GraphQL do not expose it**, because they expose no backup or restore at all. Adding it
  there is a larger piece of work than this one, tracked by [#627](https://github.com/FgForrest/evitaDB/issues/627).

## Related work

- `2026-08-06-catalog-folder-decoupling` — the pointer-swap replace this operation ends with, and the
  folder-claim handover its restore step depends on.
- `2026-08-06-time-travel-disk-budget` — decides how far back a version request may reach, and why an
  unretained version is an ordinary outcome rather than a failure.

## Timeline

- **2026-09-12** — implemented
