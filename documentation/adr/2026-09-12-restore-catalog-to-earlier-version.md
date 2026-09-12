---
title: Restore a live catalog to an earlier version by composing backup, restore, activate and replace
date: 2026-09-12
updated: 2026-09-12 07:10
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
  `ModifyCatalogSchemaNameMutation#verifyApplicability` requires only the *source* to exist and, with
  `overwriteTarget`, skips every target check; the operator reads the target via
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
- **The intermediate archive competes with the export directory's size limit.** `purgeFiles` drops the
  oldest files whenever the directory exceeds `export.sizeLimitBytes` (1 GiB by default) and holds no
  reference count, despite the interface javadoc suggesting it spares files a reader needs. A catalog
  whose archive alone outgrows that limit cannot be restored this way. Not fixed — reference-counting
  the export directory is its own piece of work — but `PublishRestoredCatalogTask#fetchArchiveLocally`
  turns the resulting `FileForFetchNotFoundException` into a message that names the cause, so the
  operator raises the limit instead of hunting a phantom.
- **Unpacking progress is not forwarded.** The client sees the archive-fetch, unpack, activate and
  swap boundaries, and fine-grained progress only during the activation — which is the phase that
  actually takes minutes. `ServerTask` has no progress-listener API to subscribe to for the rest;
  `Progress#addProgressListener` is what makes the activation phase reportable.
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
