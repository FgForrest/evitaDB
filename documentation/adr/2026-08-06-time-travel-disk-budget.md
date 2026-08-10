---
title: Bound time travel with an absolute per-catalog byte budget, not a ratio or a generation count
date: 2026-08-06
updated: 2026-08-10 07:08
status: accepted
kind: feature
issues: [761]
prs: [1402]
areas:
  - evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog
  - evita_store/evita_store_server/src/main/java/io/evitadb/store/wal
  - evita_api/src/main/java/io/evitadb/api/configuration
  - evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog/task
  - evita_engine/src/main/java/io/evitadb/core/session
supersedes: []
superseded-by: []
relates: []
---

# Bound time travel with an absolute per-catalog byte budget

Time travel used to keep every compacted-away data file forever. A new storage setting,
`timeTravelSizeLimitBytes` (default 1 GB, negative = unlimited, `0` = no history), caps how much disk
the retained history may occupy on top of the active data set. When the cap is exceeded, the oldest
*generations* are given up until it fits. The same change fixes a pre-existing leak in which data
files that no retained bootstrap record could reach were never reclaimed at all.

## Why

With `storage.timeTravelEnabled`, `ObsoleteFileMaintainer.purgeFile` runs the removal lambda but
skips the `delete()`. That skipped delete is the entire disk cost of the feature — and nothing
bounded it.

The trap is that the settings that *look* like they bound it do not. Operators reach for
`transaction.walFileSizeBytes` × `walFileCountKept`, because WAL rotation is what triggers the
historical-file purge. But WAL rotation is driven by **appended mutation bytes** while the disk cost
is driven by **compaction**, which fires on a single file's waste ratio and size. There is no fixed
relation between the two. A catalog that compacts often can accumulate many full data-file copies
between two WAL rotations, and an operator who has tuned WAL retention carefully still has no bound
on disk at all. That gap is what made time travel effectively unusable in production.

### Previous state

Retention had exactly one driver: `CatalogWriteAheadLog.updateFirstVersionKept`, called on WAL
rotation, which trimmed the bootstrap file and then purged the files the remaining records could not
reach. Catalogs that never rotated their WAL — and warm-up catalogs, which have no WAL at all —
never reclaimed anything.

## Options considered

The knob's *shape* was the real fork; the accounting unit and the reclamation site each had a
second, smaller one. All three are recorded here because all three will be re-proposed.

### Option A — one absolute byte budget per catalog (chosen)

`timeTravelSizeLimitBytes` states, in bytes, how much history may sit on disk on top of the active
data set.

- **Pros:** it is denominated in the unit the operator is actually constrained by (disk); it is
  checkable against a volume size without knowing anything about the data; it does not move when the
  catalog grows.
- **Cons:** the same value means very different amounts of history for a 100 MB catalog and a 100 GB
  one, so it cannot be set once for a whole fleet.

### Option B — a ratio of the active data set (declined)

`timeTravelMaxWasteShare`-style knob: keep history up to *N ×* the size of the live data.

- **Pros:** one value works across catalogs of wildly different sizes; scales automatically as a
  catalog grows.
- **Cons:** the resulting disk figure is unknowable in advance and moves on its own.
- **Rejected because:** it makes the operator reason about a quantity they do not control. The
  question being asked is "will this volume fill up", and a ratio cannot answer it — the active set
  doubling silently doubles the history budget too, which is exactly the unbounded-growth
  characteristic the setting exists to remove. Revisit if fleet-wide uniform configuration ever
  becomes a stronger requirement than a hard disk bound; the two could coexist as "whichever is
  smaller".

### Option C — a retained-generation count (declined)

Keep the *N* most recent generations.

- **Pros:** trivially predictable in units of "how far back can I go"; no size measurement needed at
  all, so no directory listing and no header reads.
- **Cons:** says nothing about disk.
- **Rejected because:** generation size varies by orders of magnitude across catalogs and over a
  single catalog's life, so a count that is safe today overflows the volume after a bulk import. It
  bounds the wrong axis. Revisit only as a *secondary* floor alongside the byte budget, never
  instead of it.

### The minimum-retention floor — considered and deliberately not added

A natural companion knob is "always keep at least *N* generations / *T* hours regardless of size".
It was rejected outright: a floor that can exceed the byte limit breaks the contract the byte limit
states, and an operator who set both would have no way to know which one is in force. The case it
was meant to protect — a budget too small to hold even one generation — is instead reported as a
warning, leaving the operator's instruction intact rather than silently overriding it.

## Decision

**Chosen: Option A**, a single absolute byte budget, defaulting to 1 GB rather than to unlimited.

The driver is that the operator's actual constraint is a volume, and only bytes answer a question
about a volume. Both alternatives are more convenient to *set* and less useful to *rely on*.

The non-obvious part of the decision is the default. Leaving it unlimited would preserve the old
behaviour for anyone upgrading — but the old behaviour is the bug. Someone switching time travel on
is opting into a feature whose cost they can now see and cap, and 1 GB is roughly ten generations of
a catalog sitting at the 100 MB compaction threshold. Anyone who genuinely wants the old semantics
sets a negative value and gets them.

For Option B to win instead, the primary complaint would have to shift from "I cannot bound my disk"
to "I cannot configure my fleet uniformly".

## Key technical details

- **Retention is measured in generations, not bootstrap records.** A generation is the tuple
  `(catalogFileIndex, {entityTypePk → collectionFileIndex})` a bootstrap record pins. Consecutive
  records routinely share one generation, so giving up a record frequently frees nothing — the
  reclaimable unit is the generation. `TimeTravelRetention.GenerationPin`.
- **The horizon search is a binary search, and that is only legal because of a monotonicity
  argument.** Every pinned file index only ever increases and entity-type primary keys come from a
  monotonic never-reused sequence, so the reachable set is always a *suffix* and the retained byte
  total is monotone non-increasing in the horizon index. Cost is one directory listing (`File.length()`
  is a stat, not a read) plus `O(log n)` catalog header reads. `TimeTravelRetention.resolveHorizon`.
- **`CatalogHeader.lastEntityCollectionPrimaryKey()` is the watermark that disambiguates a missing
  collection.** Above it the collection did not exist yet at that version (keep every file); below it
  the collection existed and was dropped (reclaim every file). Getting this backwards silently
  deletes live data.
- **The survivor rules are shared on purpose.** `TimeTravelRetention.isCatalogDataFileObsolete` /
  `isEntityCollectionFileObsolete` are called both by the guard (which *predicts* bytes) and by
  `ObsoleteFileMaintainer.reclaimFilesUnreachableFrom` (which *deletes* files). If they ever drift,
  the guard gives up history it never reclaims and the budget stops binding. Do not inline either.
- **The retention seam moved.** `CatalogWriteAheadLog` no longer owns a bootstrap-trimmer plus a
  purge callback; it reports one floor through a `LongConsumer` to
  `DefaultCatalogPersistenceService.advanceHistoryHorizon`, where the WAL-retention and size-limit
  drivers meet. It is monotone-guarded by an `AtomicLong` and serialized on `historyHorizonLock`.
- **The lock is held across the measurement too, not just the advance.** The guard reads the
  bootstrap file record by record while the competing driver may atomically replace that same file.
  Safe on POSIX rename semantics, not everywhere.
- **The retention floor outranks the budget**, and it has two independent sources because one alone is
  not enough. `activeReaderFloor` comes from `catalogConsumersLeft`, which fires only when the *last*
  reader of a version leaves and therefore only ever reports a **rising** minimum. That cannot express
  a consumer which *starts* on a version in the past — and a point-in-time backup is exactly that:
  `BackupTask`'s constructor calls `onStart.accept(bootstrapRecord.catalogVersion())`, wired through
  `registerConsumerOfCatalogInVersion`. So `SessionRegistry` now also reports arrivals, and
  `ObsoleteFileMaintainer` counts them per version in `pinnedCatalogVersions`;
  `getRetentionFloor()` is the minimum of the two. A **counter map rather than a single accumulated
  minimum** is what makes it race-free: two consumers pinning and releasing different versions each
  touch their own entry, whereas any single value can be overwritten by a competing update and
  silently drop the lower pin. A round blocked by the floor is deferred, and releasing a pin
  reschedules the guard so the deferred reclamation is not stranded until the next compaction.
- **A pin and a horizon advance are serialized on `historyHorizonLock`.** `catalogVersionPinned`
  takes it, and `advanceHistoryHorizon` samples the floor *inside* it rather than before it. Without
  both halves the floor is only a snapshot: a pin taken after the sample but before the trim is
  invisible, which is precisely the window a point-in-time backup falls into. The lock is
  uncontended unless time travel is on, so the cost lands only where the correctness is needed.
  Serialization alone is not sufficient, because `BackupTask` resolves its bootstrap record *before*
  it pins — so the task re-verifies the record is still reachable once the pin is registered and
  throws `TemporalDataNotAvailableException` if it lost the race. The pin is what makes that check
  conclusive rather than another guess.
- **`getRetentionFloor()` reports "nothing is held" as `-1`, not `0`.** Version `0` is pinnable — it
  is what a catalog goes live with and what a full backup pins before any history has been given up
  — while the departure-driven reader floor uses `0` for "no reader". Collapsing the two makes a pin
  at version `0` a silent no-op and lets the purge run unclamped over the files that consumer is
  reading. `clampToRetentionFloor` therefore tests `>= 0`, and it is the clamp for **both** drivers —
  the write-ahead log purge is frozen by a version-`0` pin exactly as the size guard is.
- **The clamp is independent of `timeTravelEnabled`, and that is load-bearing.** It used to be reached
  through `WalPurgeCallback.effectivePurgeVersion`, which is `NO_OP` when time travel is off — and
  `NO_OP` inherited an identity default, so in the **default configuration** the seam ran with no
  floor at all. That is not a time-travel concern: `trimBootstrapFile` closes the persistence services
  below the floor in *both* modes, and `getStoragePartPersistenceService` resolves a version to the
  closest service at or below it, so a service closed under a reader takes that reader's reads down
  with it. The clamp now reads `ObsoleteFileMaintainer.getRetentionFloor()` directly and
  `effectivePurgeVersion` was **removed from the interface** rather than left as a trap for the next
  person to hang a guard on.
- **The unreachable-file sweep answers to a directory hold, never to version pins.** A version pin
  protects a consumer that reaches its data *through a bootstrap record*. A full backup does not: it
  copies the catalog folder by listing the directory, so it reads files no record points at — and
  during warm-up it reads little else, because every flush rewrites the bootstrap down to a single
  record and strands the previous generation. "No retained record reaches this file" therefore does
  **not** imply "nobody is reading it". The guard for that is `CatalogDirectoryReadHold`, taken by
  `FullBackupTask` always and by `BackupTask` during warm-up. Gating the sweep on *pins* instead was
  tried and reverted in the same line of work: every session pins, so it stopped reclamation for as
  long as anything was connected — and warm-up permits one session held across a whole bulk import,
  which is exactly when the leak the sweep exists to fix accumulates. Covered by
  `shouldReclaimWarmUpLeftoversWhileAVersionIsPinned`.
- **Sessions are safe from that sweep without gating it, and the argument has two preconditions.** A
  session resolves its reads through the bootstrap record serving its version; the trim that decides
  which records are retained is clamped by that session's own pin; so every file it can reach stays
  reachable from a retained record, and the sweep deletes only files that are not. This holds only
  while (a) the clamp is mode-independent, and (b) **every** session takes a pin, read-only ones
  included. Both are recorded at their sites, because either one silently disappearing breaks the
  argument with no test failing.
- **Holds are leases, not paired void calls.** `CatalogDirectoryReadHold` is an idempotent
  `AutoCloseable` that captures the maintainer it was taken on. Acquisition and release are separated
  by a whole backup, and the pairing has failed twice already — a task that acquired in its
  constructor and was never scheduled leaked for the catalog's lifetime, and a release routed through
  a mutable catalog reference can reach a different instance than the acquisition did, drifting a
  counter that nothing reconciles. Both are closed structurally.
- **A backup pins without registering as a session.** `CatalogConsumerControl.pinCatalogVersion`
  exists precisely so the backup gets retention and nothing else. Routing it through
  `registerConsumerOfCatalogInVersion` also counted it as a read-write consumer, and since a full
  backup holds the *oldest* retained version that phantom consumer held back conflict-key release and
  offset-index purging for the whole copy.
- **A horizon request the floor refused is remembered and retried through one seam.** Only the
  write-ahead log driver needs the memory, and the asymmetry is the whole point: `removeWalFiles`
  deletes its files *before* reporting the floor they imply and then forgets them, so a request
  clamped away by a pin is gone for good — no later rotation reports it, and the bootstrap records
  pointing at those deleted log files would be retained for the life of the catalog. The budget driver
  needs nothing of the sort, because it re-derives its horizon on every run. The refusal is recorded
  whenever the clamp lowered the request **at all**, not only when it blocked it outright: a request
  that advances the horizon partway and is then dropped loses the remainder just as permanently.
  `retentionStateChanged` is the single place it is drained, reached from both events that can lower
  the floor — a pin released and the last reader of a version leaving. Three scattered retry sites
  would be the next defect of this shape.
- **A backup task pins in its constructor, so a task that is never run leaks that pin.** `Catalog`
  cancels the task if `scheduler.submit` rejects it, which routes through the task's own tear-down.
  This was harmless while a full backup pinned the newest version; now that it pins the oldest, a
  leaked pin would freeze every reclamation for the rest of the catalog's life.
- **A full backup pins the oldest retained version, not the version it is taken at.** It copies every
  file in the catalog folder, historical ones included, so it needs the whole retained window rather
  than one generation. Pinning the newest version protects nothing: the floor is a *minimum*, so
  every candidate horizon at or below it passes the clamp untouched and history is reclaimed halfway
  through the copy — leaving an archive whose bootstrap references files that were deleted before the
  data pass reached them. Reading the oldest version a moment before pinning it is safe in the
  conservative direction, because the horizon only ever rises.
- **The guard is driven by compaction, never polled** — but it is scheduled at *two* points, and both
  are needed. `DefaultCatalogPersistenceService.retireDataFile` covers every
  `removeFileWhenNotUsed` call site, and `writeCatalogBootstrap` covers every published record. The
  second is not redundant: a retired generation only becomes history once the record superseding it
  is published, and with a checkpoint coordinator that publication is deferred. Scheduling on
  retirement alone lets the guard measure while the newest published record still pins the retired
  file, which makes it count as active and the generation invisible to the budget. Plus once per
  catalog load, because an idle catalog may never compact again after the limit is lowered.
- **`historyHorizon` is set only after the trim and purge both succeed.** Setting it first makes a
  failed trim permanent — the retry arrives with the same version and the monotonicity check
  swallows it.
- **`enforceTimeTravelSizeLimit` and `computeRetainedHistoryBytes` are package-private on purpose** —
  tests drive them synchronously instead of racing the scheduler. Do not "fix" the visibility.

### The deleter × guard matrix

Every path that makes previously readable history unreadable answers to a different set of guards.
**Every defect found on this line of work is one of them failing to consult one of these** — which
is why the matrix is the artifact worth keeping, not the prose around it. A cell reading *none* must
be a decision somebody wrote down, never an omission nobody noticed. Restate it in the description
of any change that touches a deleter or a guard.

**Key it by sink, not by driver.** The first version of this matrix had one row reading
`purgeObsoleteFiles → purgeFile`, which quietly asserts that `purgeObsoleteFiles` is the way into
`purgeFile`. It is not — there are three doors, and the two that were missing are exactly the two
cells a later review found empty. Enumerate the **call sites of the thing that deletes**, then ask
each what it consults; a row named after a scheduled task will keep hiding the inline caller behind
it.

**A sink is not only a file unlink.** The second version of this matrix enumerated *file* deleters,
and that omission cost a data-integrity defect of its own: releasing an offset index's per-version
roots makes that version unreadable exactly as surely as deleting its file, and it does so *silently*
— `Roots.floorIndex` clamps a request below everything retained to the oldest root it still has, so
the read succeeds and returns a different, newer state. Anything that makes history unreadable is a
sink: a file unlink, an in-memory root purge, a persistence service close. The sinks are `purgeFile`,
`reclaimFilesUnreachableFrom`, `removeWalFiles`, `OffsetIndex.purge`, and the ad-hoc deleter in
`purgeAllObsoleteFiles`.

**1. `DefaultCatalogPersistenceService.trimBootstrapFile`** — drops bootstrap records and closes the
catalog persistence services that fall below the new floor. Runs in **both** time-travel modes.
- *Version floor:* the retention floor, via `clampToRetentionFloor`, in both modes.
- *Directory hold:* **not consulted, deliberately.** It unlinks no data file, and a consumer walking
  the folder resolves nothing through the services it closes. A full backup additionally pins the
  oldest retained version, which freezes this path anyway.

**2. `purgeFile` — door 1 of 3: `ObsoleteFileMaintainer.purgeObsoleteFiles`** — deletes a retired
data file when time travel is off; in both modes it runs the removal lambda that closes the file's
persistence service.
- *Version floor:* `lastKnownMinimalActiveVersion` only — the departure-reported floor, **not** the
  pins. See the open follow-up below; this is a documented gap, not a settled decision.
- *Directory hold:* consulted, through `runWithDirectoryExclusivity`.

**3. `purgeFile` — door 2 of 3: `removeFileWhenNotUsed` at `catalogVersion <= 0L`** — the warm-up
eager path. Deletes **inline, on the commit thread**, without ever reaching the scheduled purge.
Only the unlink is mode-dependent (`purgeFile` skips `delete()` with time travel on); the removal
lambda runs in both modes.
- *Version floor:* **none, and correctly so.** `<= 0` is a version tag, not a state test, and this
  path exists precisely for the state where no meaningful version exists yet.
- *Directory hold:* consulted. When held, the file is parked in `deferredEagerPurges` and taken by
  the next pass that gets the folder to itself. This is the one deleter with no driver of its own to
  bring it back, which is why what it defers has to be parked rather than dropped.

**4. `purgeFile` — door 3 of 3: `ObsoleteFileMaintainer.close()`** — empties the folder of every
maintained and every parked file when the catalog goes away.
- *Version floor:* **none, deliberately.** It sets the floor to `0` on purpose: the catalog is being
  discarded, so no version can still be owed anything.
- *Directory hold:* **the hold is overridden, the lock is not** — and this is the one cell where the
  distinction matters. The original comment here read *"database shuts down and there will be no
  active sessions"*, which is **false**: `Evita.closeInternal` closes catalogs *before*
  `Scheduler.shutdown` cancels the tasks queued against them, so a full backup can be holding the
  folder at exactly this moment. Honouring the **hold** would not save that backup — its catalog is
  being torn down underneath it either way — and would strand files on disk with nothing left to
  collect them. It fails loudly instead, which is the honest outcome. But it does take
  `directoryAccessLock`, because `purgeTask.close()` is `cancel(false)` and does not stop a pass that
  is already executing: without the lock the two walk the same lists side by side and a removal lambda
  runs twice, which does not no-op — `removeCatalogPersistenceServiceForVersion` resolves the closest
  service *at or below* the version it is given, so the second run closes a different, still-registered
  one. Blocking is safe here and nowhere else: `close()` holds nothing, and a pass is bounded. The
  constraint that makes that true is that nobody calls it while holding `cpsvLock`, which the removal
  lambdas take. *Revisit if:* backups ever become cancellable ahead of catalog close, in which case
  draining them first is strictly better.

**5. `ObsoleteFileMaintainer.reclaimUnreachableFiles` → `reclaimFilesUnreachableFrom`** — deletes
catalog and entity-collection files that no retained bootstrap record can reach. Time travel **on**
only; with it off nothing is ever left behind to reclaim. Single door — the WAL callback routes
through this method rather than calling the sink directly, which is what keeps it that way.
- *Version floor:* **none, by necessity.** It is reachability-keyed rather than version-keyed — see
  the section below for why that cannot be changed — and it needs no floor of its own, because its
  threshold is re-derived from the oldest record left after a trim that was already clamped.
- *Directory hold:* consulted. This is the path the hold exists for.

**6. `AbstractMutationLog.removeWalFiles`** — deletes write-ahead log files. Runs in both modes.
- *Version floor:* **none.** Pre-existing and unchanged by this work; see the open follow-up.
- *Directory hold:* **none.** Same follow-up.

**7. `DefaultCatalogPersistenceService.catalogConsumersLeft` → `OffsetIndex.purge`** — releases the
per-version roots of the shared catalog and entity-collection offset indexes. Unlinks nothing, but
makes every released version unreadable through those services. Runs in both modes, on session close.
- *Version floor:* the retention floor, via `clampToRetentionFloor`, sampled and applied under
  `historyHorizonLock`. The departure report alone cannot express what has to be kept — it says
  "everyone at or below V has left", which only ever rises, while the thing that needs protecting is
  a consumer that *arrived* on a version and is still standing on it. A backup of the **current**
  data is exactly that consumer: it reads each collection through the shared services, so without the
  clamp it produces an archive stitched from several versions and labelled with the one it started
  from.
- *Directory hold:* **none, and correctly so.** It removes nothing from the folder; a consumer
  listing the directory is unaffected by it.

**8. `DefaultCatalogPersistenceService.purgeAllObsoleteFiles`** — the startup / write-ahead-log-replay
sweep. Deletes every file in the folder that the chosen bootstrap record does not reach.
- *Version floor:* **none, deliberately.** It runs once, from replay, on a catalog being brought up,
  before any session or backup exists to hold anything; both guards would read state that is empty by
  construction.
- *Directory hold:* **none**, same reason.
- *Survivor rules:* it carries **its own copy** rather than going through `TimeTravelRetention`. The
  two agree on catalog data files; on a *dropped* collection they do not — its `orElse(false)` keeps
  the file where `TimeTravelRetention` reclaims it — so this door is the more conservative of the
  pair and the drift can only ever leave a file behind, never remove one that is needed. Unifying
  them was declined: it would make replay delete more than it does today, which is not a change worth
  making off the back of a review with no failing test behind it. *Revisit if:* replay leftovers are
  ever observed to accumulate, or the two rules gain a second disagreement.

### Acquire and release both have to survive a throw

Every guard here is taken in one method and given back in another, and both ends have failed that way
at least once. The rules the code now follows:

- **A constructor that acquires must unwind its own acquisition.** A throw leaves no object behind:
  `tearDown` is unreachable and the caller's cancel-on-rejected-submission has no task to cancel. Both
  backup tasks wrap everything after the acquisition in a `catch` that closes the hold and rethrows.
- **A tear-down that releases two things must release the second even if the first throws.** Giving
  the folder back re-drives the reclamation it deferred, which is real work that can fail — and the
  unpin sits after it. A full backup pins the *oldest* retained version, so a pin stranded that way
  does not delay one reclamation, it stops every reclamation the catalog will ever do. Hence the
  `finally`.
- **Only the intolerant end may be intolerant.** `pinCatalogVersion` throws when the catalog cannot be
  resolved, because a backup whose pin silently did not land runs unprotected for its whole life and
  its own post-pin re-verification degrades back to the race it was written to close. Session
  registration keeps its tolerance — but a tolerated skip is now *recorded*
  (`versionsWithSkippedPin`), because releasing a pin that was never taken is not a no-op: by the time
  the session closes the catalog may be back, and the release would decrement whatever pin somebody
  else holds at that version.
- **A constructor that acquires two things must unwind both, in tear-down order.** `BackupTask` takes
  the folder hold first and the version pin second, and everything after the pin can throw — the
  reachability re-verification reads the bootstrap file under the horizon lock. The unwind mirrors
  `tearDown`: close the hold, then release the pin in a `finally`, and release it through the
  `onComplete` **field** (`getAndSet(null)`), never the constructor parameter, so the unwind and the
  already-fired rejection path cannot both give the same pin back and decrement somebody else's.
- **A release that re-drives real work must not be able to fail its caller.** `retentionStateChanged`
  is reached from session close, from a pin release and from a folder hold being given back, and it
  can synchronously trim the bootstrap file. A throw there propagated out of
  `unregisterSessionConsumingCatalogInVersion` *before* the departure notification, so the reader
  floor would believe that session was still present for the rest of the catalog's life. It now logs
  instead — **and re-records the owed request first**: the debt is taken out of
  `pendingHistoryHorizonRequest` with `getAndSet(-1)` before the attempt, and `advanceHistoryHorizon`
  only puts it back when the *floor* refuses it, so a swallowed trim failure would otherwise be the
  one thing that loses it for good.

### A marker records what finished, not what started

Three places here record "this far is done", and two of them recorded it *before* doing the work.
The failure is identical and silent in both: the retry arrives with the same version, the marker
turns it into a no-op, and the round is booked as complete with its work never performed.

- `advanceHistoryHorizon` sets `historyHorizon` only after both the trim and the purge return.
- `ObsoleteWalPurgeCallback.purgeFilesUpTo` sets `lastObservedCatalogVersion` only after both the
  maintained-file purge and the unreachable sweep return. Before this, a trim that succeeded followed
  by a purge that threw left the horizon back, and the retry then passed the horizon's monotonicity
  check, no-oped the trim, and fell into the callback's *already observed* branch — recording the
  round complete with the purge never having run.
- `OffsetIndex`'s purge watermark is the deliberate exception: it records intent and the **next
  promotion** consumes it. That is what makes the clamp in matrix row 7 observable only after a
  further commit, and it is why the test for it writes one more generation.

### Nothing may throw out of a shutdown or a commit path

Four sites read a `closed` flag and then called `DelayedAsyncTask.schedule()`. Every one of them is
check-then-act, and a `close()` landing between the two throws `GenericEvitaInternalError` out of a
path that must not fail: the commit thread retiring a data file, the maintainer parking a deferred
purge, a backup's tear-down giving the folder back, and the tail of a run that has just thrown — where
it would replace the exception that actually explains the failure. All four now use `trySchedule()`,
which makes the decision **under `schedulingLock`**, the same lock `close()` tears down under.

The same shape one level up: `enforceTimeTravelSizeLimit` and `advanceHistoryHorizon` now read
`closed` while holding `historyHorizonLock`, and `close()` flips it under that lock. Closing the guard
task is `cancel(false)` and does not stop a run that has already begun, so without the fence a run in
flight at shutdown could reach the trim — swapping in a fresh bootstrap write handle that nothing will
ever close, concurrently with the teardown closing the old one, and racing the service map's own
teardown. Flipping the flag under the lock is what turns those checks from a guess into a decision;
no teardown work happens beneath it, so no lock order is created.

### A deferred pass needs somebody to bring it back

`runWithDirectoryExclusivity` can turn a pass away for two reasons, and they are reported apart
because only one of them has a driver. A **directory hold** is given back by its own release, which
reschedules. Losing the `tryLock` to a **competing deleter** is rescheduled by nothing: the round is
simply skipped while the horizon advances as if it had reclaimed, and on a catalog that goes idle
right afterwards it never happens at all. The maintained-file purge now reschedules its own task on
contention; the unreachable sweep — which has no task of its own, being driven by the size guard and
the write-ahead log purge — sets `pendingUnreachableSweep` and is carried by the next pass that gets
the folder.

**Borrowed work must not be able to fail its host.** Both the parked warm-up purges and the deferred
sweep run inside whichever pass happens to get the folder next, and one of those passes is
`removeFileWhenNotUsed` at `catalogVersion <= 0` — inline on the commit thread, under `cpsvLock`. The
sweep in particular reads the bootstrap file and a catalog header, so it has real I/O to fail at, and
an unwrapped throw would fail a flush on behalf of a completely unrelated round. Both are therefore
caught and logged where they are carried, with the asymmetry that matters: the sweep is **re-armed**
(its round still has to happen), a failed parked purge is **not** (its removal lambda has already run,
and running it again closes a service that is still registered). Neither wraps the pass the caller
actually asked for — that one still propagates, which is what lets the write-ahead log driver retry a
round that threw.

### `DelayedAsyncTask` had to stop losing wake-ups first

Everything above that "defers and gets rescheduled" — the parked warm-up purge, the sweep a hold
turned away, the guard woken by a released pin — rests on `schedule()` being reliable. It was not.
`runTask` cleared `running` in its `finally` and only then called `pause()`; a `schedule()` arriving
between the two planned nothing (a tick was still set) and recorded nothing (the task no longer
looked busy), and the `pause()` then discarded the very tick it had deferred to. On an idle catalog
the deferred work waited for an unrelated event that might never come.

The next tick is now settled while `running` is still set and under `schedulingLock`. The same change
fixes a second latent bug in that class: a run that threw skipped the re-planning entirely, leaving a
stale tick behind that made every later `schedule()` a no-op — the task stayed dead for good after one
exception. This is a shared class; the full `unitAndFunctional` suite is the check that matters.

### The hold is exclusion, not a flag

`CatalogDirectoryReadHold` reads as a counter, and a counter invites check-then-act: read zero,
then delete, with the whole window in between for a backup to start. Acquisition and every deletion
pass therefore share `directoryAccessLock`, so a hold either predates a pass entirely or waits for
it to finish.

The asymmetry in how that lock is taken is load-bearing. **Deleters `tryLock` and give up**, because
every one of them is opportunistic work that the last release reschedules — and because door 2 runs
on the commit thread underneath `cpsvLock`, which a deletion pass may itself need, so blocking there
would invert the lock order. **Acquisition blocks**, because it runs while a backup task is being
constructed, holding nothing else, and a deletion pass is bounded by the number of files it unlinks.

### The unreachable-file sweep, and why it is not an eager delete

`ObsoleteFileMaintainer.reclaimUnreachableFiles` deletes every data file that the oldest *retained*
bootstrap record cannot reach, and it runs whenever time travel is on — including under an unlimited
budget, because such files were never history and no budget is a reason to keep them.

It exists because warm-up rewrites the bootstrap file down to a **single record** on every flush
(`getOrCreateNewBootstrapTempWriteHandle` returns a fresh temp handle whenever `catalogVersion == 0`).
A bulk import compacting repeatedly with time travel on therefore strands every superseded data file
permanently: they are reachable from nothing, and the WAL purge that would normally sweep them never
fires because warm-up has no WAL.

**The obvious simplification is wrong and must not be applied.** Making
`ObsoleteFileMaintainer.purgeFile` delete eagerly at `catalogVersion <= 0L` looks equivalent and is
not: go-live's `recordBootstrap(0, …)` also rewrites the bootstrap file to a single **version-0**
record, so an ALIVE catalog's oldest retained record carries version 0 as well — and
`retireDataFile(catalogVersion - 1L, …)` passes `0` for the first transaction after go-live. Deleting
there destroys a file that the retained go-live record still pins, breaking time travel to go-live.
`<= 0` is a version tag, not a catalog-state test. The sweep is keyed off disk truth — the oldest
record actually left in the bootstrap file — precisely to sidestep this.

An earlier attempt made the bootstrap trim index-keyed instead of version-keyed, on the theory that
warm-up's version-0 records made a version-keyed trim refuse every call after the first. That premise
is false for the reason above (warm-up leaves one record, not many), and the work was reverted.

## Verification

- `TimeTravelRetentionTest` — 14 tests over the survivor rules, byte accounting and horizon search.
  `shouldProbeLogarithmically` asserts ≤ 20 header reads across 32,768 bootstrap records;
  `shouldCollapseRecordsSharingOneGeneration` covers the case that makes generations, not records,
  the unit.
- `DefaultCatalogPersistenceServiceTest.TimeTravelSizeGuardTest` — 28 end-to-end tests against a
  real catalog forced to compact every round (`fileSizeCompactionThresholdBytes(1)`,
  `minimalActiveRecordShare`/`maxWasteActiveShare` at 0.99, `minCompactionIntervalMilliseconds(0)`,
  and `TransactionOptions.checkpointIntervalInMillis(0)`, which makes `checkpointCoordinator` null so
  every round publishes its bootstrap record).
- Each end-to-end test was calibrated by reverting the code it guards. Without the guard, a zero
  budget leaves 10 catalog data files instead of 1. Without the sweep, 9 warm-up leftovers survive
  instead of 1. Without the task being bound under an unlimited budget, 9 survive instead of 1.
- `ObsoleteFileMaintainerTest.PinnedCatalogVersions` — six tests over the retention floor, including
  the data-loss case: with the reader floor already at version 100 and a consumer pinning version 20,
  the floor must report 20 rather than 100 — the difference between a point-in-time backup reading
  version 20 and a purge deleting up to 50 underneath it.
- `HistoryHorizonClampTest.shouldClampTheHorizonToTheRetentionFloorWithTimeTravelDisabled` — the
  default-configuration case. A pinned version, then log rotation reporting a floor far above it,
  then the pinned version must still resolve its persistence service. Calibrated against the code
  before the clamp moved off `WalPurgeCallback`: `Catalog version 2 not found in the catalog
  persistence service versions!` — the trim had closed and de-registered the service serving the
  reader. No other test in the suite noticed.
- `shouldReclaimWarmUpLeftoversWhileAVersionIsPinned` and
  `shouldFreezeTheFolderWhileADirectoryReadHoldIsOpen` — the two halves of the guard split, calibrated
  together by restoring the pin gate on the sweep: the first fails with 9 files instead of 1 (a single
  held version freezing reclamation entirely), the second with 1 instead of 9 (a folder walker losing
  the files underneath it).
- `shouldRetryTheRemainderOfAPartiallyClampedHorizonRequest` — a request the floor lowered *partway*
  is still owed. Calibrated by recording the refusal only on the no-progress path: the horizon
  advances to the pin and the remainder is never asked for again by anyone.
- `shouldHoldTheWholeRetainedWindowWhileAFullBackupRuns` — a full backup must pin the *oldest*
  retained version, and a zero budget must not reclaim anything while it holds it. Calibrated twice:
  reverting the pinned value fails with `expected: <0> but was: <8>` (the newest version instead of
  the oldest), and reverting the `-1` floor sentinel fails on the reclamation assertion, because
  a pin at version `0` was silently treated as "nothing is held".
- `shouldScheduleTheGuardWhenADeferredCheckpointPublishes` — the only test in the class that runs with
  a checkpoint coordinator, and the one that covers the publish-site scheduling. A 60 s
  `checkpointIntervalInMillis` makes `isCheckpointDue()` false for the whole test (the coordinator
  stamps its last-completed time at construction), so every round defers and `checkpoint()` publishes
  on demand — no wall clock, no polling. Two traps it had to dodge: the checkpoint ticker shares the
  scheduler and would publish early if drained, so only the guard's zero-delay task is run; and the
  pre-publication guard must run *through* `DelayedAsyncTask`, because a direct call leaves the task
  armed and `schedule()` coalesces into it, which looks exactly like the bug. Calibrated by removing
  the call: `zero interactions with this mock`, and **no other test in the class notices** — which is
  what made this the last unverified behaviour of the whole change.
- `shouldRefuseAPointInTimeBackupOfAlreadyReclaimedHistory` — a backup whose record was reclaimed in
  the window between resolving it and pinning it must fail with `TemporalDataNotAvailableException`
  and release **both** the pin and the folder hold it took. Calibrated in two directions: removing
  the re-verification makes it silently copy files that are no longer there; removing the
  constructor's unwind leaves `isCatalogDirectoryHeld()` true (`expected: <false> but was: <true>`).
  The second half matters more than it looks — this test resolves the version-0 go-live record, so it
  drove the leaked-hold path on every run and asserted nothing about it.
- `shouldToleratePinAndHoldReleaseAfterTheServiceClosed` — a backup tearing down against an
  already-closed catalog must not throw. Calibrated by removing the closed guards:
  `GenericEvitaInternalError: Cannot schedule task 'Time travel size guard' that has been closed.`
  That throw escapes `Scheduler.shutdown`'s bare cancellation loop, which then skips the remaining
  tasks and `executorService.shutdown()` — and the scheduler's threads are not daemons.
- `ObsoleteFileMaintainerTest.WarmUpEagerPurge` — three tests over the inline warm-up deleter, all
  with time travel **off**, which is the mode where it actually unlinks. Calibrated separately
  because they guard two different things: restoring the ungated `purgeFile` call fails only
  `shouldNotUnlinkTheWarmUpFileWhileTheFolderIsHeld` (`expected: <true> but was: <false>` — the file
  was unlinked under a live backup), while removing the drain fails the other two, which is the
  counterfactual that keeps a deferral from silently becoming a disk leak.
- `DelayedAsyncTaskTest.shouldStillRunAfterAnExecutionThrew` — a task whose run throws must still be
  schedulable afterwards. Calibrated by skipping the settle on the exception path: `Task never ran
  again after an execution threw`. Its retry loop is bounded on purpose — with the tick left behind
  no number of retries succeeds, and an unbounded loop hangs the build instead of failing it.
- **The lost-wakeup half of that fix is not covered by a test.** It is a two-statement race with no
  seam to drive, and per `.claude/rules/testing.md` that means either a `@Disabled` stress test in
  `evita_long_running_tests` or an honest statement that it is uncovered. This is the statement. What
  *is* covered is everything downstream of it — the deferred warm-up purge and the sweep a hold turns
  away both come back — so a regression would surface as those tests going intermittent rather than
  as silence.
- `HistoryHorizonClampTest.shouldClampTheOffsetIndexHistoryPurgeToTheRetentionFloor` — matrix row 7,
  the data-integrity one. Non-compacting options on purpose, so every version lands in one shared
  offset index; a version is pinned, the last other consumer departs reporting a floor far above it,
  one more generation is written (promotion is what consumes the purge watermark), and the pinned
  version must still resolve to *its own* state. Calibrated by removing the clamp: `expected: <2> but
  was: <8>` — the read silently returned the newest state under the version it asked for, which is
  precisely the archive-stitched-from-several-versions failure.
- `HistoryHorizonClampTest.shouldNotAdvanceTheHorizonOnAClosedService` — a guard run that reaches its
  advance after `close()` must give up nothing. Calibrated by removing the closed fence:
  `GenericEvitaInternalError: ObsoleteFileMaintainer is closed`, thrown after the bootstrap file had
  already been rewritten.
- `shouldUnwindTheVersionPinWhenTheBackupConstructorThrows` — a spied service throws from
  `getOldestRetainedCatalogVersion`, which runs *after* the pin lands. Both the pin and the folder
  hold must be back. Calibrated by removing the unwind: `expected: <-1> but was: <0>`, i.e. the
  catalog's retention floor frozen at the backed-up version for the rest of its life.
  `shouldRefuseAPointInTimeBackupOfAlreadyReclaimedHistory` fails the same way, which is the second
  independent witness.
- `DeferredDeletionPasses.shouldRetryTheWholeRoundAfterItThrew` — the sweep throws once, and the retry
  with the same version must actually perform it. Calibrated by moving `lastObservedCatalogVersion`
  back to the front of the method: `expected: <1> but was: <0>`.
- `DeferredDeletionPasses.shouldPerformTheDeferredSweepOnTheNextPass` — a sweep turned away while the
  folder was held is carried by the next pass rather than waiting for a second guard trigger.
  Calibrated by removing the `pendingUnreachableSweep` drain: `expected: <false> but was: <true>`.
- `DelayedAsyncTaskTest.shouldRefuseToScheduleAClosedTaskInsteadOfThrowing` — `trySchedule()` reports
  a closed task, `schedule()` still throws. Calibrated by making `trySchedule` call `assertNotClosed`.
- **Uncovered, deliberately, and each for a stated reason.** (a) The owed-request re-check in
  `advanceHistoryHorizon` guards a record-then-release interleaving with no seam between the two
  statements; the non-racy half is covered by
  `shouldRetryTheRemainderOfAPartiallyClampedHorizonRequest`. (b) The claim token in
  `drainDeferredEagerPurges` guards a concurrent close-versus-park double drain; single-threaded the
  first drain empties the queue, so the token is unobservable — the *exactly once* assertion in
  `shouldPurgeDeferredFilesWhenTheMaintainerCloses` is a regression guard for the simple path, not a
  calibration of the token. (c) `computeRetainedHistoryBytes` taking `historyHorizonLock` is a lock
  addition with no observable behaviour to assert. (d) `retentionStateChanged` swallowing and
  re-recording needs a trim that fails on transient I/O.
- Regression: `mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional
  -Dgroups="storage | wal | transaction | session | cdc"` — 3707 tests. The full `unitAndFunctional`
  suite is the gate for the `DelayedAsyncTask` change, which is engine-wide: the storage subset does
  **not** exercise it. The first attempt at that fix held `running` across the re-plan and broke three
  `DelayedAsyncTaskTest` cases, because a zero-delay task fires its next run before the flag clears —
  the subset run was green throughout.

## Consequences & open follow-ups

- **A generous budget is no longer a no-op.** The sweep runs regardless of the limit, so enabling the
  guard changes the file list even when nothing is over budget. A test asserting "the file list is
  unchanged" is testing the wrong thing; assert that `computeRetainedHistoryBytes()` is unchanged and
  that a second run reclaims nothing.
- **Peak disk usage cannot be bounded below one generation.** Compaction writes the full new copy
  before the old file may be dropped, so the transient peak is roughly *active + old file* whatever
  the limit says. Documented in `operate/configure.md`.
- **A budget too small for one generation disables time travel in practice.** Reported as a warning
  rather than overridden — see the floor discussion above.
- **`removeCatalogPersistenceServiceForVersion(0)` no-ops when `warmUpVersionCardinality` is 0.**
  Benign for compaction (the counter balances), but the final version-0 service is only released by
  `close()`. Left as-is; it predates this work and has no observed symptom.
- The knob is deliberately absent from `EngineSettings` and therefore from the gRPC management API,
  which by convention exposes capabilities rather than tuning knobs.
- **Open — the maintained-file purge does not consult the pins** (matrix row 2). It is driven by the
  departure-reported `lastKnownMinimalActiveVersion` alone, so a consumer holding a version in the
  past is invisible to it. Widening it to the full retention floor was considered and **declined for
  now**: that floor includes every open session's pin, and this path is the deleter that runs on
  every session close with time travel *off*, so honouring live pins there would retain files for as
  long as anything is connected — the same failure the directory hold was introduced to undo, moved
  to a different deleter. The exposure that remains is a point-in-time backup with time travel off,
  which is already of marginal value because retired files are unlinked eagerly in that mode. Revisit
  by measuring what the tightened floor actually retains before changing it.
- **Open — write-ahead log removal is gated by nothing at all** (matrix row 6). `removeWalFiles`
  deletes its files consulting neither the retention floor nor the directory hold, in either mode, so
  a rotation during a full backup can remove a log file mid-walk. Not fixed here because the current
  failure is loud (`Files.copy` throws and the backup fails) rather than silent, and the deferral has
  a real cost: log removal would have to be held back behind a backup that may run for minutes.
  Closing it is natural once wanted — the deletions are already queued in `pendingRemovals`, so the
  drain simply must not run while a hold is up.
- **Open — a session's version is captured before it is pinned** (`SessionRegistry`, around the
  `registerSessionConsumingCatalogInVersion` call site). `newSession.getCatalogVersion()` is read,
  and the pin lands a few statements later; a guard run in between can move the horizon past it.
  **Declined for now**, on two counts: a session captures the *newest* version and the horizon can
  never exceed the newest recorded version, so the window needs a budget tight enough to trim to the
  current generation before it is reachable at all; and the failure is loud —
  `getStoragePartPersistenceService` resolves to the closest service *at or below* the request and
  throws when the request falls below every registered version, which is exactly what a prefix trim
  leaves, so there is no silent-stale-read tier here. A real fix means capturing and pinning
  atomically at session construction, which is a wider change than this line of work. Revisit if a
  deployment ever runs a budget that tight; the symptom would be
  `Catalog version N not found in the catalog persistence service versions!` at session open.
- **Open — version pins route through the mutable by-name catalog lookup, the folder hold does not.**
  `CatalogDirectoryReadHold` captures the maintainer it was taken on, precisely because a release
  routed through a mutable reference can land on a different instance. `pinCatalogVersion` /
  `unpinCatalogVersion` still resolve the catalog by name on both sides. Ordinary transactional
  version swaps are safe (the new `Catalog` shares the same persistence service), but `replaceWith`
  builds a fresh service with a fresh maintainer, so pins do not transfer and a late release can
  decrement a *different* backup's pin at the same version number. **Declined for now** — it needs
  two backups straddling a replacement with colliding version numbers, and the replacement swaps the
  storage path out from under the captured service anyway, which is a wider pre-existing race. The
  symmetric fix is to give pins the same lease treatment the hold got. Until then, an unpaired
  release is at least logged rather than silent.
- **Decided — a last reader leaving pays for the trim it unblocks.** `catalogConsumersLeft` ends in
  `retentionStateChanged`, which can drain an owed horizon request synchronously: a bootstrap rewrite
  plus file deletion, on the session-close thread, under `historyHorizonLock`. Handing it to the
  guard task was considered and rejected — the guard exists only when a budget is configured, and the
  request being drained is the write-ahead log driver's, which has no other way back. Correctness of
  the drain outranks the latency of a session close.
- **Decided — the current backup keeps reading the shared services, clamped, rather than isolating
  itself.** The alternative to matrix row 7's clamp was to give the `pastMoment == null &&
  catalogVersion == null` path the isolated services the historical path already builds from its
  captured bootstrap record. **Rejected because** `createCatalogOffsetIndexStoragePartService` and
  `createEntityCollectionPersistenceService` each construct a `WriteOnlyFileHandle` over the file they
  open, wired to the shared `ObservableOutputKeeper` and the shared checkpoint coordinator. For the
  historical path those are files nobody is writing; for the current path they are the **live** data
  files, so isolation would put a second write handle on every one of them for the duration of every
  ordinary backup. That is a new hazard on the most-used backup path in exchange for avoiding a
  bounded, pre-existing memory cost. *Revisit if:* the offset index ever gains a genuinely read-only
  construction path, which would make isolation strictly better — it removes the backup's dependence
  on the retention floor altogether.
- **Consequence of that clamp — a long backup retains offset-index roots for its duration.** The
  clamp holds root history down to the lowest pin, so while a backup runs, every commit adds a root
  that would otherwise have been released. Bounded by the backup's duration, not by the depth of
  history — roots already released are never resurrected — and identical to the behaviour before this
  branch, when the backup registered itself as a session and held the same floor down. The new risk it
  adds is that a *leaked* pin now grows the heap as well as freezing reclamation, which is why the two
  leak paths (backup constructor, tear-down ordering) are fixed rather than deferred.
- **Open — with time travel on, every session open takes `historyHorizonLock`.** `catalogVersionPinned`
  takes it to fence the pin against a concurrent advance, and `enforceTimeTravelSizeLimit` holds it
  across a directory listing, `O(log n)` header reads and the trim. Session open therefore stalls
  behind a guard run. **Declined for now** because the fix is not obviously safe: sampling the floor
  into a local before the I/O-heavy part would shorten the hold but changes the pin-fence argument
  that matrix rows 1 and 7 both rest on. Measure the stall before touching it; the lock is uncontended
  whenever time travel is off, which is the default.
- **Follow-up — shrink the folder hold to a hardlink snapshot.** A full backup currently holds the
  folder for the whole of its copy. Hard-linking the catalog folder into a sibling directory
  (`Files.createLink`, same volume, works on POSIX and NTFS), releasing the hold, and zipping from the
  snapshot would reduce the hold from *O(backup duration)* to *O(file count)* and make the filesystem
  the reference counter. Not a precondition for anything above: a global hold is an honest contract
  for an operation that is rare and bounded.

## Timeline

- **2026-08-06** — four pre-existing retention defects fixed while auditing the unused time-travel
  path; design settled on the issue; feature implemented and committed
