---
title: Bound time travel with an absolute per-catalog byte budget, not a ratio or a generation count
date: 2026-08-06
updated: 2026-08-06 12:40
status: accepted
kind: feature
issues: [761]
prs: []
areas:
  - evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog
  - evita_store/evita_store_server/src/main/java/io/evitadb/store/wal
  - evita_api/src/main/java/io/evitadb/api/configuration
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
- `DefaultCatalogPersistenceServiceTest.TimeTravelSizeGuardTest` — four end-to-end tests against a
  real catalog forced to compact every round (`fileSizeCompactionThresholdBytes(1)`,
  `minimalActiveRecordShare`/`maxWasteActiveShare` at 0.99, `minCompactionIntervalMilliseconds(0)`,
  and `TransactionOptions.checkpointIntervalInMillis(0)`, which makes `checkpointCoordinator` null so
  every round publishes its bootstrap record).
- Each end-to-end test was calibrated by reverting the code it guards. Without the guard, a zero
  budget leaves 10 catalog data files instead of 1. Without the sweep, 9 warm-up leftovers survive
  instead of 1. Without the task being bound under an unlimited budget, 9 survive instead of 1.
- `ObsoleteFileMaintainerTest.PinnedCatalogVersions` — five tests over the retention floor, including
  the data-loss case: with the reader floor already at version 100 and a consumer pinning version 20,
  `effectivePurgeVersion(50)` must return 20. Calibrated against the unfixed code, where it returns
  50 — that is a point-in-time backup reading version 20 while the purge deletes up to 50.
- Regression: `mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional
  -Dgroups="storage | wal | transaction | session | cdc"`.

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

## Timeline

- **2026-08-06** — four pre-existing retention defects fixed while auditing the unused time-travel
  path; design settled on the issue; feature implemented and committed
