---
title: A backup that cannot fit the export storage stops at the limit and names the setting to raise
date: 2026-11-05
updated: 2026-11-05 01:40
status: accepted
kind: fix
issues: [1881]
prs: []
areas: [evita_store/evita_store_server, evita_engine/io.evitadb.spi.export, evita_export, evita_api/exception]
supersedes: []
superseded-by: []
relates: [2026-09-12-restore-catalog-to-earlier-version]
---

# A backup that cannot fit the export storage stops at the limit and names the setting to raise

Every backup, restore to an earlier version included, now writes through a cap taken from the export handle. Crossing it
stops the backup, publishes nothing, leaves older exports in place, and fails the task with
`ExportFileTooLargeException`, whose public message names the limit, the property, its environment variable and the
catalog's size on disk. Migrations, JFR recordings and traffic recordings stay uncapped.

## Why

`export.<backend>.sizeLimitBytes` caps the export storage, and a purge running every five minutes drops the oldest files
until the total fits. A backup larger than the limit was therefore created, reported as finished, and deleted.

### Previous state

The purge deletes oldest-first and the newest file goes last, so an over-limit file wiped **every** older export and
then itself (3 older files plus a 1,500 B file against a 1,000 B limit left an empty list). On the file system an
unfinished file already counts, so older exports vanished while the backup was still being written. A client that
downloaded before the next purge tick got the file; anyone slower got a dangling descriptor. evitaLab showed a finished
task, because nothing had failed.

Failed writes were a second, older hole. `ZipOutputStream#close` skips `out.close()` when `finish()` throws, so the
handle stayed open, the future never completed and `deleteFile` could not see the file. The partial archive kept
counting against the limit until it expired (seven days by default) and evicted exports that had done nothing wrong.

## Options considered

### Option A - cap the backup's stream, with the allowance reported by the export handle (chosen)

`SizeCappedOutputStream` sits between the zip's buffer and the handle's stream. `ExportFileHandle#sizeLimit` reports
what this export may occupy, `ExportFileHandle#discard` abandons an unpublished file.

- **Pros:** stops as soon as the outcome is known, protects older exports, needs no estimate. Other exports are
  untouched because only the two backup tasks wrap their stream.
- **Cons:** the backup still pays for the bytes written up to the limit.

### Option B - check the final size after writing, as the issue suggested (declined)

- **Pros:** the smallest change.
- **Cons:** the whole archive is written first, holding the version pin and the folder hold throughout.
- **Rejected because:** an unfinished file counts on the file system, so older exports can already be gone by the time
  the check runs.

### Option C - refuse up front on an estimate (declined)

- **Pros:** no bytes written, an immediate answer.
- **Rejected because:** compressed size is unknowable up front and on-disk size is no bound in either direction: an
  empty catalog occupied 644 B and its full backup 708 B, while a catalog with time travel occupied 360,854 B and
  produced far smaller archives. An estimate either refuses backups that fit or admits ones that do not. Worth
  revisiting only if a reliable bound existed.

### Option D - cap inside the export service's stream, opted into through a `storeFile` variant (declined)

- **Pros:** self-cleaning.
- **Cons:** a second entry point and a second counter in each backend.
- **Rejected because:** the service knows neither the catalog nor its size, so the message needs re-wrapping in the task
  anyway, and `discard` is needed regardless to close the failed-write hole.

### Option E - cap every `storeFile` (declined)

- **Rejected because:** `Migration_2026_1#backupWalFiles` rethrows, so a cap could abort an upgrade. JFR and traffic
  recordings have no client waiting for a message.

### Option F - a fixed reserve for the sidecar, 4 KiB (declined)

- **Pros:** the simplest arithmetic, and it can live on `ExportService`.
- **Rejected because:** it refuses backups that fit: limit 10,000 B, archive 7,955 B, sidecar 187 B, total 8,142 B, cap
  5,904 B. The allowance is per export for this reason, which is why it lives on the handle: only the handle knows its
  own name, description and origin. The purge counts sidecars, so ignoring them (reserve 0) would admit files it then
  deletes.

### Option G - exempt restore to an earlier version (declined after being chosen first)

The first decision was to exempt it, because a restore cannot succeed without its archive and an over-limit one worked
(10 of 10 runs, with no purge landing in the window).

- **Rejected because:** the archive counts while being written and stays listed until the swap finishes, so one purge in
  that window wipes every older export, on the path where an operator most needs them. A failed run keeps the archive as
  the manual retry, and the next purge would remove it. The feature had never shipped, so capping it regresses nobody.
  Writing the archive to the work directory would avoid both costs and is the option to revisit if the export limit
  becomes the binding constraint for restores, but it drops the deliberate "a failed run keeps the archive" and needs
  new code.

### Rejected details of the chosen option

- **Relying on the thrown exception reaching the task.** `ObservableOutput#flush` runs the stream's flush through
  `IOUtils.close(KryoException::new, ...)`, which replaces the exception. With a cap crossing there, the client read
  `Task failed for unknown reasons.` The cap remembers its refusal and the task asks it.
- **Throwing on every write after the first crossing.** Try-with-resources would raise `Self-suppression not permitted`
  from rethrowing the same instance. After the first throw the cap swallows.
- **Keeping an over-limit file for a grace period.** The operator's limit would be violated, and the client still loses
  the file later.
- **`discard` also deleting published files.** Tidier, but it moves the tested cancellation cleanup and needs a service
  reference in every handle.

## Decision

**Chosen: Option A.** The two things the issue asks for, a clear error and a kept limit, are both decided at the moment
the limit is crossed, and the task is the only place that knows the catalog and can say what to raise. The issue's
"backup is created and then deleted" is not a working use being taken away: it was a race against a five-minute timer.

## Key technical details

- `SizeCappedOutputStream` (`io.evitadb.store.catalog.task`): both tasks build it through `forBackup`, which measures
  the catalog folder only when the cap trips and drops that sentence if measuring fails.
- **A tripped cap never closes the handle.** That is what keeps the file unpublished, and also why both tasks call
  `checkFailure` after the zip closes: `fileForFetchFuture().get()` would otherwise wait forever if a layer swallowed
  the refusal. No such layer exists today, so those two call sites are covered by reading, not by a test.
- **Order in the failure path:** `discard` first, because `getNow` on a cancelled future throws and would mask the
  primary. `discard` returns `false` once the handle is closed, which then falls back to deleting the published file.
- **A cancellation against a refusal:** a cancellation that unwinds the backup is reported as cancellation:
  `rethrowFailure` steps aside when the caught exception is cancellation-driven, by the same
  `AbstractServerTask#isCancellationDriven` that decides it afterwards, which walks only the cause chain. A cap that had
  already tripped is reported as the refusal even if a cancel lands before the rethrow, because the backup did fail for
  that reason. The future is cancelled either way.
- **File-system allowance:** `limit - metadataBytes`, computed from this export's own descriptor with the longest size
  and timestamp a sidecar can hold, encoded the way `writeFileMetadata` writes it. An archive within about 40 B of the
  limit is therefore refused although it would have survived. That is deliberate: the timestamp's fractional digits are
  not known until the file closes, and admitting a file the purge then deletes is the worse error. S3 has no sidecars
  and allows the whole limit.
- **The failure path reads the future by state,** not with `getNow`: an upload that failed completes it exceptionally,
  and `getNow` would replace the primary failure with a `CompletionException` the client sees as "unknown reasons".
- **A handle belongs to the thread writing it.** `close` and `discard` deliberately do not guard against racing each
  other; `cancel()` never touches the handle.
- The environment variable in the message is derived the way `EvitaServer#transformEnvironmentVariable` derives it.

## Verification

Real catalogs and real export services, with S3 over `adobe/s3mock`. Each guard named below was removed and its test
watched go red for the stated reason.

- `BackupTaskSizeLimitTest` (9) drives both tasks: crossing inside the record copy, inside a data file copy and inside
  `ZipOutputStream#close`, a close fit surviving a purge with at most 64 B of the limit unused, and an older export
  surviving a refusal. Without the rethrow, a crossing inside the record copy reads `Task failed for unknown reasons.` A
  cancel landing after the cap tripped still reports the refusal.
- `BackupTaskS3SizeLimitTest` (7): both tasks refused with the S3 property, no object, no temp file; both upload when
  admitted. Without the wait, the admitted full backup fails with `File for fetch should be generated in close method`.
  An upload the storage really refuses, on a bucket removed under the service, is reported rather than masked.
- `BackupTaskCancellationTest` (4): an interrupt, and a cancelled future whose exception wraps it, stay what they were
  while the cap trips during `close`. Without the guard the status reads as a too-large failure.
- `ExportFileServiceTest$SizeLimit` (3): a file of exactly the allowance survives a purge, also with multi-byte
  metadata. A fixed 4 KiB reserve leaves 883 B unused and fails; counting characters instead of bytes loses the file to
  the purge.
- `SizeCappedOutputStreamTest` (3), `SizeCappedOutputStreamFactoryTest` (2), `ExportFileTooLargeExceptionTest` (2).
- `BackupTaskExportCleanupTest` (3), `ExportFileServiceTest$Discard` and `ExportS3ServiceTest$Discard` (2 each): a
  failed write leaves no zip and no sidecar, on both backends.
- `CatalogRestoreToVersionExportLimitTest` (2): a refused restore shows the exact message through the sequence, the
  target serves all its brands and accepts a write, no scratch catalog, no archive, the retention floor released; after
  raising the limit and restarting, the same restore succeeds.

## Consequences & open follow-ups

- **"Admitted implies kept" holds in isolation only.** Two concurrent exports that each fit can still evict older
  exports or each other, and an unfinished file counts on the file system. Reference-counting the export directory is
  its own piece of work.
- **S3 cancelled-backup upload race.** After an interrupt the zip closes normally and the upload starts, `getNow(null)`
  is null, nothing is deleted, and a truncated archive may appear later. Read from code, not run.
- **Embedded newlines break sidecar round-tripping** (`FileForFetch#fromLines`). The reserve counts those bytes
  correctly; the parsing defect predates this work.
- **Traffic tasks' orphan on a failed write.** `TrafficRecordingExportTask` and `TrafficRecorderTask` have the same hole
  `discard` closes for backups. A one-line fix each, left out because this work was scoped to backups.
- **A closed handle whose metadata write failed** never completes its future and is not cleaned up
  (`ExportFileService#WriteMetadataOnCloseOutputStream#close`).
- **JFR and traffic exports still vanish silently over the limit**, and `JfrRecorderTask` returns `getNow(null)` before
  the stream closes.
- **Migration safety backups can be purged silently at startup.**

## Related work

- [2026-09-12-restore-catalog-to-earlier-version](2026-09-12-restore-catalog-to-earlier-version.md): its intermediate
  archive is a backup and is capped by this decision, which replaces that record's "cannot be restored this way" hazard.

## Timeline

- **2026-11-04** - issue studied, probes run, design reviewed twice, restore first exempted and then included
- **2026-11-05** - implemented
