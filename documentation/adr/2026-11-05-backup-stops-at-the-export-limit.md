---
title: An export that cannot fit the export storage stops at the limit and names the setting to raise
date: 2026-11-05
updated: 2026-11-05 12:20
status: accepted
kind: fix
issues: [1881]
prs: [1885]
areas: [evita_store/evita_store_server, evita_store/evita_store_key_value, evita_engine/io.evitadb.spi.export, evita_engine/io.evitadb.core.traffic, evita_export, evita_api/exception, evita_external_api/evita_external_api_observability]
supersedes: []
superseded-by: []
relates: [2026-09-12-restore-catalog-to-earlier-version]
---

# An export that cannot fit the export storage stops at the limit and names the setting to raise

Every backup, restore to an earlier version included, every JFR recording and every traffic recording now writes through
a cap taken from the export handle. Crossing it stops the export, publishes nothing, and fails the task with
`ExportFileTooLargeException`, whose public message names the limit, the property, its environment variable and, for a
backup, the catalog's size on disk. Migrations stay uncapped. Only a body that finished may publish at all, so a failed
export can no longer reach the export list.

## Why

`export.<backend>.sizeLimitBytes` caps the export storage, and a purge running every five minutes drops the oldest files
until the total fits. A backup larger than the limit was therefore created, reported as finished, and deleted. JFR and
traffic recordings vanished the same way.

### Previous state

The purge deletes oldest-first and the newest file goes last, so an over-limit file wiped **every** older export and
then itself (3 older files plus a 1,500 B file against a 1,000 B limit left an empty list). On the file system an
unfinished file already counts, so older exports vanished while the backup was still being written. A client that
downloaded before the next purge tick got the file; anyone slower got a dangling descriptor. evitaLab showed a finished
task, because nothing had failed.

Failed writes were a second, older hole. `ZipOutputStream#close` skips `out.close()` when `finish()` throws, so the
handle stayed open, the future never completed and `deleteFile` could not see the file. The partial archive kept
counting against the limit until it expired (seven days by default) and evicted exports that had done nothing wrong.

On S3 a failed body was worse. The zip still closed normally, closing started the upload, and the failure path found
nothing to delete yet. On `adobe/s3mock`, a cancel at the first write left one object in the bucket and one listed
export: a valid zip missing most of its entries.

## Options considered

### Option A - cap the export's stream, with the allowance reported by the export handle (chosen)

`SizeCappedOutputStream` sits between the zip's buffer and the handle's stream. `ExportFileHandle#sizeLimit` reports
what this export may occupy, `ExportFileHandle#discard` abandons an unpublished file.

- **Pros:** stops as soon as the outcome is known, so the file never grows past the limit and a purge cannot wipe every
  older export to make room for it. Needs no estimate. Migrations are untouched because only the two backup tasks, the
  JFR task and the two traffic tasks wrap their stream.
- **Cons:** the export still pays for the bytes written up to the limit.

### Option B - check the final size after writing, as the issue suggested (declined)

- **Pros:** the smallest change.
- **Cons:** the whole archive is written first, holding the version pin and the folder hold throughout.
- **Rejected because:** the file keeps growing past the limit while it still counts on the file system, so a purge
  during the write wipes every older export before the check runs.

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

### Option E - cap migrations as well (declined)

- **Rejected because:** `Migration_2026_1#backupWalFiles` rethrows, so a cap could abort an upgrade.

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

### Option H - leave JFR and traffic recordings uncapped (declined after being chosen first)

The first decision kept this work to backups, reasoning that a recording has no client waiting for a message.

- **Rejected because:** recordings were lost the same silent way and wiped older exports just the same, and their task
  status is exactly where a client reads the message. The cost was moving the cap and its cleanup from `evita_store` to
  `evita_engine`, which the traffic tasks and the JFR task can reach.

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
- **A nested catch that discards before the zip closes.** Four more copies of the same lines, and noise in the
  suppressed list. `SizeCappedOutputStream#markFinished` is one line per task and fails safe: a body that forgets it
  never publishes. Its cost is that a forgotten call makes the task's wait hang, which any happy-path test shows at
  once.
- **Keeping what fits of a recording.** A traffic recording could end cleanly at the limit with the sessions so far. Not
  built, because the allowance would have to reserve room for a metadata entry and a central directory that grow with
  the chunks, and a backup's outcome was the one asked for. Worth revisiting if the lost traffic turns out to matter
  more than the consistency.
- **Escaping line breaks in sidecars.** Keeps them, but changes the format, can misread a backslash in an existing
  sidecar, and does not help S3, where a line break in user metadata failed the upload with a 400 on `adobe/s3mock`. One
  space serves both backends.
- **Recognising `ClosedByInterruptException` as a cancel.** `Files.copy` looks interruptible, but
  `FileSystemProvider#newInputStream` marks its channel uninterruptible on JDK 21, so no task ever sees that exception.
  A test written first showed it; there was nothing to guard.

## Decision

**Chosen: Option A.** The two things the issue asks for, a clear error and a kept limit, are both decided at the moment
the limit is crossed, and the task is the only place that knows what it was writing and can say what to raise. The
issue's "backup is created and then deleted" is not a working use being taken away: it was a race against a five-minute
timer.

## Key technical details

- `SizeCappedOutputStream` (`io.evitadb.spi.export.model`): tasks build it through `of`, backups through
  `BackupExportCap#forBackup`, which stays in `evita_store` because it measures the catalog folder, only when the cap
  trips, and drops that sentence if measuring fails.
- **Only a finished body publishes.** Closing a handle publishes it, on S3 by starting the upload, and a failed body
  still closes its zip. So `close` reaches the handle only after the body's last statement called `markFinished`, no
  refusal is remembered and no delegate write or flush failed. A finished body alone proves nothing: a zip writes the end
  of its archive in the buffer's final flush, and `FilterOutputStream#close` closes the stream even when that flush
  failed. Everything else stays open for the failure path's `discard`.
- **A tripped cap never closes the handle.** That is also why the tasks call `checkFailure` after the zip closes:
  `fileForFetchFuture().get()` would otherwise wait forever if a layer swallowed the refusal. `TrafficRecorderTask`'s
  sink is such a layer. For the other tasks none exists today, so those call sites are covered by reading, not by a
  test.
- **Order in the failure path:** `discard` first, because `getNow` on a cancelled future throws and would mask the
  primary. `discard` returns `false` once the handle is closed, which then falls back to deleting the published file.
- **A cancellation against a refusal:** a cancellation that unwinds the backup is reported as cancellation:
  `rethrowFailure` steps aside when the caught exception is cancellation-driven, by the same
  `AbstractServerTask#isCancellationDriven` that decides it afterwards, which walks only the cause chain. A cap that had
  already tripped is reported as the refusal even if a cancel lands before the rethrow, because the backup did fail for
  that reason. The future is cancelled either way. During a record copy the interrupt arrives through
  `WriteOnlyFileHandle#checkAndExecute`, which keeps it as the cause so that this check can see it.
- **File-system allowance:** `limit - metadataBytes`, computed from this export's own descriptor with the longest size
  and timestamp a sidecar can hold, encoded the way `writeFileMetadata` writes it. An archive within about 40 B of the
  limit is therefore refused although it would have survived. That is deliberate: the timestamp's fractional digits are
  not known until the file closes, and admitting a file the purge then deletes is the worse error. S3 has no sidecars
  and allows the whole limit. `FileForFetch#singleLine` keeps every descriptor field on one line, which is what the
  positional sidecar and S3's user metadata both need.
- **`ExportFileCleanup#abandon` reads the future by state,** not with `getNow`: an upload that failed completes it
  exceptionally, and `getNow` would replace the primary failure with a `CompletionException` the client sees as "unknown
  reasons". Its published-file branch is reached only when a handle's own close failed after publishing, or an upload
  finished while a cancel landed in the task's wait.
- **A handle belongs to the thread writing it.** `close` and `discard` deliberately do not guard against racing each
  other; `cancel()` never touches the handle. `TrafficRecorderTask` is the one caller with two threads: the ring
  buffer's thread writes and the task's thread reads the verdict, which is why the refusal and the delegate failure are
  volatile. Its wait on the latch is the one place where the interrupt itself unwinds the task, so a cancel in the
  instant after the cap tripped and before the latch released reads as a cancel, not as the refusal. The client asked
  for that, and nothing is kept.
- **`TrafficRecorderTask` waits for its file.** `getNow` was null while S3 uploaded, and the cleanup then took a healthy
  export for a leaked one. The wait has the same one-hour bound as `TrafficRecordingExportTask`.
- **A failed sidecar write removes both files** and fails the future, so nothing waits forever and nothing counts
  against the limit (`ExportFileService#WriteMetadataOnCloseOutputStream#close`).
- **An S3 upload has no deadline of its own.** The HTTP client's connect, write and read timeouts
  (`requestTimeoutInMillis`) end a stalled connection, and the tasks' wait stays cancellable.
- The environment variable in the message is derived the way `EvitaServer#transformEnvironmentVariable` derives it.

## Verification

Real catalogs and real export services, with S3 over `adobe/s3mock`. Each guard named below was removed, or its test
written before the fix, and the test watched go red for the stated reason.

- `BackupTaskSizeLimitTest` (9) drives both backup tasks: crossing inside the record copy, inside a data file copy and
  inside `ZipOutputStream#close`, a close fit surviving a purge with at most 64 B of the limit unused, and an older
  export surviving a refusal. Without the rethrow, a crossing inside the record copy reads `Task failed for unknown
  reasons.` A cancel landing after the cap tripped still reports the refusal.
- `BackupTaskS3SizeLimitTest` (9): both tasks refused with the S3 property, no object, no temp file; both upload when
  admitted. Without the wait, the admitted full backup fails with `File for fetch should be generated in close method`.
  An upload the storage really refuses, on a bucket removed under the service, is reported rather than masked. A cancel
  at the first write leaves no object and no listed export; without `markFinished` there was one of each.
- `BackupTaskCancellationTest` (5): an interrupt, and a cancelled future whose exception wraps it, stay what they were
  while the cap trips during `close`. Without the guard the status reads as a too-large failure.
- `BackupTaskCancelDuringCopyTest` (2): a cancel during a record copy reads `Task was cancelled.`; without the cause it
  read `Writing mem table due to interrupt!` with an ERROR and a stack trace in the log. A cancel during a full backup's
  file copy reads the same.
- `ExportFileServiceTest` (40): a file of exactly the allowance survives a purge, also with multi-byte metadata and
  after a restart with line breaks in its name and description, where it used to drop off the list. A fixed 4 KiB
  reserve leaves 883 B unused and fails; counting characters instead of bytes loses the file to the purge. A failed
  sidecar write fails the future and leaves neither file.
- `ExportS3ServiceTest` (38): a 1 MiB upload through a slow local relay takes over 5 s against a 3 s timeout and
  completes; with `.orTimeout` it fails with a `TimeoutException`. A line break in a description uploads; it used to get
  a 400.
- `TrafficRecordingExportTaskTest` (5), `EvitaOnDemandTrafficRecordingTest` (7, one disabled before this work),
  `TrafficRecorderTaskExportCleanupTest` (7): an export and a real recording over the limit fail with the exact message
  while an older export survives; before, both finished and published. A failed zip close leaves no file. A result
  arriving after the sink closed is returned and kept; before, it was null and the cleanup tried to delete it. A failed
  upload reports its own message.
- `JfrRecorderTaskTest` (4): after `stop` the result is the published file, also on real S3; before, it was always null.
  Over the limit the task fails with the exact message and nothing stays behind.
- `WriteOnlyFileHandleTest` (37), `BootstrapWriteOnlyFileHandleTest` (1): an interrupted `close` throws its real error;
  before, `IllegalMonitorStateException` replaced it.
- `SizeCappedOutputStreamTest` (15): a finished body whose final flush fails leaves the handle open; it used to close
  it, which on S3 could upload an archive missing its end. Read from code for S3, not run against it.
- `BackupExportCapTest` (2), `ExportFileTooLargeExceptionTest` (5), `FileForFetchTest` (21).
- `BackupTaskExportCleanupTest` (4), `ExportFileServiceTest$Discard` and `ExportS3ServiceTest$Discard` (2 each): a
  failed write leaves no zip and no sidecar, on both backends, and a failing delete does not replace the failure.
- `CatalogRestoreToVersionExportLimitTest` (2): a refused restore shows the exact message through the sequence, the
  target serves all its brands and accepts a write, no scratch catalog, no archive, the retention floor released; after
  raising the limit and restarting, the same restore succeeds.

## Consequences & open follow-ups

- **"Admitted implies kept" and "older exports survive a refusal" hold in isolation only.** An unfinished file counts on
  the file system, so a purge tick during a long write can evict older exports before the backup is refused, and two
  concurrent exports that each fit can evict older exports or each other. The refusal itself removes nothing else.
  Reference-counting the export directory is its own piece of work.
- **A cancel while a task waits for its S3 upload leaves a complete archive behind.** The failure path finds the upload
  still running and deletes nothing, so the file is listed later while the task reads as cancelled. It is whole, and it
  expires like any other. Read from code, not run.
- **A cancel during a JFR recording's copy does not stop it.** `Files.copy` reads through an uninterruptible channel and
  the task has no checkpoint after it, so the complete recording is published while the task reads as cancelled. Seen in
  a test run. `TrafficRecordingExportTask` has no checkpoint either; read from code, not run.
- **A traffic recording corrupted by anything other than the cap still finishes with a null result and no error.**
  `ExportSessionSink#compressToFinalDestination` swallows the exception. The file is removed.
- **The same dropped cause remains at `OffsetIndex:2842` and in `BootstrapWriteOnlyFileHandle#checkAndExecute`.**
  Neither is on a path a cancel normally lands on, and no test covers them.
- **The environment variable in the message cannot work under a Turkish or Azeri default locale.**
  `EvitaServer#transformEnvironmentVariable` upper-cases with the default locale, so every override with an `i` is
  ignored. Left as an edge case; the YAML setting and the system property still work.
- **`stopRecording` does not join a ring buffer callback already in flight.** With ambient recording, `onClose` runs on
  the task's thread while such a callback may still be writing the zip. If it marks the recording corrupted between the
  task's two reads, the archive is published and then deleted again, the one other way into `abandon`'s published-file
  branch. The hazard predates this work. Read from code, not run.
- **A failed start can strand the recording flag.** `TrafficRecordingEngine#startRecording` sets it before the recorder
  initializes, so an I/O failure there makes later starts report `SingletonTaskAlreadyRunningException`. Read from code,
  not run.
- **Migration safety backups can be purged silently at startup.** A WARN naming the file and the limit would be cheap.

## Related work

- [2026-09-12-restore-catalog-to-earlier-version](2026-09-12-restore-catalog-to-earlier-version.md): its intermediate
  archive is a backup and is capped by this decision, which replaces that record's "cannot be restored this way" hazard.

## Timeline

- **2026-11-04** - issue studied, probes run, design reviewed twice, restore first exempted and then included
- **2026-11-05** - implemented; JFR and traffic recordings capped, and the export defects this work uncovered fixed, in
  the same pull request
