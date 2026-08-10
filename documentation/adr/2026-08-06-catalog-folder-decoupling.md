---
title: Bind catalogs to opaque folder tokens, and make rename and replace a pointer swap
date: 2026-08-06
updated: 2026-08-10 18:45
status: partially-implemented
kind: refactor
issues: [649]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/core/engine, evita_engine/src/main/java/io/evitadb/spi/store/engine, evita_engine/src/main/java/io/evitadb/core/transaction/engine/operators, evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog, evita_store/evita_store_server/src/main/java/io/evitadb/store/engine]
supersedes: []
superseded-by: []
relates: []
---

# Bind catalogs to opaque folder tokens, and make rename and replace a pointer swap

A catalog's directory used to *be* its name: `storageDirectory.resolve(catalogName)`, in some thirty
places. Renaming or replacing a catalog therefore meant renaming files and moving directories, in a
five-step sequence with no durable record of how far it had got. A catalog is now bound to an opaque
`CatalogFolderId` recorded in the engine state, the folder's name is cosmetic, and renaming or
replacing a catalog is a single engine-state commit that moves nothing on disk.

## Why

`replaceCatalog(A, B)` failed intermittently, and when it failed it left the operator to infer the
state of two catalogs from whatever was on disk. The contract said as much: *"the state of
`catalogNameToBeReplacedWith` is unknown and should be treated as damaged"*. Over gRPC the failure was
common enough to be reported as a bug (#649).

The cause was not Windows, although Windows made it frequent. It was that the operation was a
multi-step filesystem mutation with no durable record of progress: rename every file inside the
source folder onto the target's name, move the target folder aside, move the source folder onto the
target's path, delete the folder that was moved aside — and on any failure, try to undo all of it.
A reader holding a directory open makes step three fail; the compensating rename can fail too, and
then there is no correct state to return to.

The deeper problem is that **a directory is not a good place to store a name**. Anything that renames
a catalog has to rename its directory, which forces a filesystem mutation into an operation that is
otherwise pure bookkeeping, and it forces that mutation to happen while the catalog is open.

### Previous state

`DefaultCatalogPersistenceService#pathForCatalog(name, storageDirectory)` was the single expression
of the identity `folder == name`, and roughly thirty call sites joined a catalog name onto the
storage root directly. Files *inside* a folder were named after the catalog too
(`products.boot`, `products_0.catalog`), so a rename had to rename those as well. Boot-time discovery
treated every directory under the storage root as a catalog, which meant a folder an operator copied
in became a catalog the engine claimed to own — and a folder evitaDB had abandoned was
indistinguishable from one someone had placed deliberately.

## The central fork — copy or pointer

Everything else follows from this one. Both options remove the multi-step rename; they differ in what
replaces it.

### Option A — pointer-only, folder bound by an opaque token (chosen)

The engine state carries a `name → folder` map. Renaming a catalog rewrites that map. Replacing one
points the target's name at the source's folder and tombstones the folder the target used to occupy.
No folder is created, moved, copied or deleted as part of the operation; the only disk work is
rewriting the catalog name stored *inside* the folder, and deleting the superseded folder afterwards,
which is allowed to fail.

- **Pros:** O(1) regardless of catalog size. One durable fact to consult during recovery instead of
  inferring how far a five-step sequence got. The suspension window readers pay for shrinks from "as
  long as it takes to move N bytes" to a single WAL append. Folder deletion stops being part of the
  operation, which also fixes drop-then-recreate on a locked folder.
- **Cons:** A folder's name goes stale the moment its catalog is renamed, so `ls` alone stops
  answering "which folder is which". Every site that derived a path from a name has to be converted,
  and the conversion is invisible when incomplete — a missed site keeps compiling and keeps working
  until a folder and its catalog's name diverge.

### Option B — copy the folder contents into a new generation (declined)

Keep the folder-per-operation model but make it a copy: write the catalog into a freshly allocated
folder, then swap the pointer.

- **Pros:** The source folder is provably untouched at every instant, and the new folder is complete
  before anything points at it.
- **Cons:** Turns an O(1) operation into O(data) writes with 2× peak disk.
- **Rejected because:** the only thing it buys over Option A is a pristine source, which never
  touching the source before the commit already provides. It also *widens* the window readers are
  suspended for rather than narrowing it — a copy taken without suspending writes is not a consistent
  copy, so the suspension cannot simply be moved outside it. Revisit if a case appears where the
  source and the target must both stay live *after* the operation.

**Chosen: Option A.** The driver was the acceptance criterion — simultaneous readers must not be
interrupted — and Option B fails it in the direction that matters. Option A's cost, stale folder
names, is a human-legibility problem with a cheap mitigation (a `.catalogname` marker inside each
folder), whereas Option B's cost is paid in disk and latency by every operation forever. Option B
would win if a future requirement made the source and the copy both live afterwards; that is a
different operation from replacement, and it would supersede this record.

## Decisions taken

| Decision | Why | Where |
|---|---|---|
| Folder names are `<catalogName>_<generation>`, never opaque UUIDs | Keeps an operator able to identify a folder by eye during disaster recovery, at no correctness cost, because the name part is never trusted | `CatalogFolderAllocator` |
| Generations come from an engine-scoped `SequenceService`, burned **per attempt** | A number burned only on success hands the same generation back to the retry, which then collides with the folder the failed attempt left behind — the single case the generation exists for | `Evita#catalogGenerationSequences` |
| Boot seeds the counters from the persisted peaks **and** a disk scan | Neither subsumes the other: a peak knows a number burned against a name a scan cannot see, a scan sees a folder no peak knows about. **Only the scan is live today** — see the follow-up below | `Evita#seedCatalogGenerationSequences` |
| Boot classification is a six-way, first-match table over unreferenced folders | The rows must be disjoint and ordered, because the alternative is a chain of heuristics that each look reasonable and together delete user data | `CatalogFolderClassifier#classifyOne` |
| Only **suffix-free** folders are adoptable; suffixed unreferenced ones are reported and never touched | Every folder evitaDB allocates carries a suffix, so a suffixed folder no catalog claims is either our litter or someone's import — and deleting the latter is unrecoverable | `CatalogFolderClassifier`, §3.5 of the plan |
| Nothing is deleted without **positive evidence of our ownership** — a `.provisional` marker we wrote or a tombstone we recorded | Absence of a reference is not evidence of abandonment | `CatalogFolderCleaner#DRAINED_STATES` |
| `.provisional` is cleared **before** the engine-state commit that binds the folder | The reverse order makes `referenced ∧ provisional` reachable, and that folder matches *referenced* first and is loaded despite declaring itself incomplete | `CatalogFolderContext#completeFolder` |
| A tombstone is staged in the **same commit** that unbinds a folder, and the delete follows it | Written afterwards it does not survive the crash it exists for; written before it authorises deleting a folder that is still live if the commit never happens | `ExpandedEngineState.Builder#withRetiredFolder` |
| Tombstones are discharged by the **next engine mutation**, whichever it is | The delete happens after its own commit, so the operator that performed it has no further commit to record the success in — and a folder that is gone is never classified again, so nothing else would ever drop the entry | `EngineTransactionManager#updateEngineStateAfterEngineMutation` |
| File names inside a folder are **discovered** from the single `*.boot`, never constructed from the catalog name | This is what lets the files stay put during a rename, and it cost no storage-format migration | `DefaultCatalogPersistenceService#discoverStoragePrefix` |
| `verifyCatalogNameMatches` became `reconcileStoredCatalogIdentity` and adapts unconditionally | Once the name comes from the engine state rather than the folder, a header naming a different catalog is the ordinary trace of a rename whose header rewrite did not land — refusing would report a loadable catalog as corrupted | `DefaultCatalogPersistenceService` |
| A rename **consumes no catalog version** — `replaceWith` keeps an ALIVE catalog's version instead of bumping it | `verifyIntegrity` asserts that the WAL's last written version equals the bootstrap's, i.e. that every version in the line was produced by a transaction. A rename moves no data and appends nothing to the WAL, so consuming a version for it is the only thing violating that invariant. Writing header, schema part and bootstrap record at an already-flushed version is what the load path has always done | `DefaultCatalogPersistenceService#replaceWith`, `TransactionManager#notifyCatalogPresentInLiveView` |
| `replaceWith` discards any owed checkpoint before it closes the service it is handing over | The bootstrap file is read back **by position** — the last record wins, whatever version it names — so a checkpoint owed by an earlier round publishes a pointer to the pre-rename root *after* the rename's own, and the service reopened at the end of the method loads the catalog under its previous name. Discarding is right on the merits: the rename's `recordBootstrap` flushes the index and forces the pending syncs, which is all the checkpoint owed | `DefaultCatalogPersistenceService#replaceWith` |
| A fresh `catalogId` is minted whenever a catalog is materialised from copied bytes | A duplicate is a new lineage; carrying the source's id leaves the two indistinguishable by the field clients use to decide whether a cached view still holds | same method, driven by `.restored` |
| Backups emit the canonical suffix-free, prefix-free shape | A backup zip is how a catalog travels between instances, so it must not carry one instance's folder generation into another | `BackupTask`, `FullBackupTask` |

## Rejected outright

| Option | Rejected because | Revisit if |
|---|---|---|
| Derive the generation from `max(<name>_N)` found on disk, as the authority | Numbers get reused once a folder is reclaimed, so a crash remnant can collide with a fresh allocation — the exact failure the generation exists to prevent. The scan *is* used, but only as a boot-time floor alongside the persisted peak, where it is strictly safety-adding | never as the authority; the floor use stays |
| A plain `long` generation counter advanced inside the engine mutation | It only burns a number when the operation commits, so a failed attempt hands the same generation to the retry, which collides with the folder that attempt left behind | never — this is the case the generation exists for |
| Pre-check folder availability with `Files.exists` before allocating | `Files.exists` reports an `AccessDeniedException` as absence, so the guard calls a name free that creation then rejects. `Files.createDirectory` is the atomic test-and-set and must be the decision point | never |
| Fixed in-folder file names (`catalog.boot`, `catalog_0.catalog`) via a storage-protocol bump | Needs a `Migration_2026_X` over every existing installation, while `*.boot` discovery achieves the same decoupling at zero migration cost | a protocol bump happens anyway for an unrelated reason — the literal names are marginally tidier |
| Keep folder = name; use copy-then-swap only on Windows | Two code paths for one operation, with the harder path exercised least. The failure mode is not Windows-specific — it is "multi-step filesystem mutation with no durable record of progress"; Windows only makes it frequent | never |
| Opaque UUID folder names | Maximum decoupling costs an operator the ability to identify a folder by eye and makes disaster recovery from a bare storage directory considerably harder | never |
| Relax `verifyIntegrity` to `bootstrap >= WAL` so a rename's extra version passes | That is exactly the shape the WAL-provider defect produced — empty WAL at 0, bootstrap at 3 — so it would re-mask the silent transaction loss that fix had just made visible. The assertion is the only thing that catches it | never; the assertion is load-bearing |
| Append a WAL transaction for the rename, so the two counters agree by construction | Disproportionate to a one-restart window, and it puts a non-transactional engine operation into the catalog's transaction stream, where every consumer would then have to recognise and skip it | only if a rename ever has to be replayable as part of the catalog's transaction history |
| Drop the `previousLivingCatalog == livingCatalog` identity escape as redundant once the version comparison is `<=` | Unverified and wrong: `<` → `<=` only ever *admits* more, but deleting the escape *rejects* a same-instance republication at a version below the previous one, which the previous form allowed. Keeping both disjuncts makes the change a pure widening | never — the two disjuncts cover different cases |
| Settle the owed checkpoint inside `writeCatalogBootstrap`, the seam that already covers every bootstrap writer structurally | Two concrete blockers. Settling unconditionally there reports a checkpoint completion on every warm-up flush, which the cadence gauge deliberately excludes so a bulk load cannot fill it with samples no checkpoint produced; and clearing the prepared record *without* settling makes `checkpointIfOwed` fail its own premise that a record exists whenever a checkpoint is owed — trading a wrong pointer for a thrown error on the next close | the gauge stops depending on `noteCheckpointCompleted`, or the premise becomes "owed implies prepared *or* already superseded" |
| Treat a **suffixed** folder carrying a `.catalogname` marker as adoptable, to close the window between an adoption's rename and its binding | It would equally make the orphan folder a replacement leaves behind adoptable, resurrecting a replaced catalog. The suffix rule exists precisely to stop that | never — close that window with a rebind mutation instead |

## Key technical details

- **`EngineState` is the sole authority on where a catalog lives.** `CatalogFolderBinding[]`,
  `RetiredFolder[]` and `CatalogGenerationPeak[]` are all persisted through `EngineStateSerializer`.
  Nothing may derive a folder from a name; `CatalogFolderContext#folderIdFor` throws on an unbound
  name rather than falling back to identity, which is what makes a missed conversion site fail loudly
  instead of silently writing to the wrong directory.
- **`CatalogFolderId` is the boundary type.** It validates at construction that a token is a single
  path segment, and the join onto the storage root happens in exactly one private method in
  `DefaultEnginePersistenceService`. The public `CatalogContract` deliberately does not expose it —
  which is why `duplicateTo` moved off that contract onto `Catalog` when it needed a token.
- **Three builder entry points, deliberately distinct.** `withCatalog(catalog)` re-stages a name that
  must already be bound and throws otherwise; `withCatalog(catalog, folderId)` registers a name for
  the first time and leaves an existing binding untouched; `withCatalogBoundTo(catalog, folderId)` is
  the only one that repoints a bound name, and it exists solely for the rename/replace swap. Collapse
  them and a create with a stale token silently relocates a live catalog.
- **`replaceWith` no longer takes a target path**, and must not be given one again. It writes the new
  name into the header and schema, records a bootstrap under the folder's *existing* storage prefix,
  and returns a service addressing the same directory. Passing the incoming catalog name to
  `recordBootstrap` would address a `<newName>.boot` that does not exist.
- **The delete of a superseded folder must follow `terminate()`**, never precede it. Deleting a
  directory whose handles are still open is exactly the failure this work removes.
- **Deleting a catalog's storage is no longer something a catalog instance can do.** Both routes that
  offered it — `CatalogContract#terminateAndDelete` and the `CatalogPersistenceService#closeAndDelete`
  it delegated to — were removed once removal became a tombstone plus a folder-context wipe, rather
  than left in place unused. A folder belongs to the engine state, and a second delete route reachable
  from an object that only knows its own directory is how a caller ends up wiping a folder the engine
  still references. Re-adding one needs this decision reversed, not merely a caller.
- **`holdsNoCatalogData` must exclude every marker this project writes.** A marker it misses reads as
  data and turns a freshly allocated folder into an occupied one.
- **A folder is validated before adoption touches it.** Adoption renames the folder and only then dispatches
  the mutation that validates the catalog name, so a folder whose bare name is not a legal catalog name — or is
  already taken by a registered catalog living elsewhere — must be rejected *before* the rename. Otherwise boot
  reconciliation fails after the operator's import has been moved.
- **`folderIdForBinding` prefers a live binding, then a reservation, then a dead binding.** The existence test in
  the first branch exists for restoring a backup over a catalog in the missing bucket: such a catalog keeps its
  binding by design, so a plain bound-before-reserved order would hand back the folder that vanished rather than
  the one the backup was written into. ⚠ **It returns the right token but that token does not survive the
  commit** — `bindingsIncluding` keeps an existing binding untouched, so the restore still binds the vanished
  folder. See the open follow-up below; the ordering here is necessary but not sufficient.
- **A header's WAL file-name provider must never be trusted.** `CatalogHeaderSerializer` fabricates it from the
  catalog name it stored, and since a rename rewrites that name while leaving the files on their prefix, the two
  diverge permanently. Every site that takes `catalogHeader.walFileReference()` rebases it onto the provider
  built from the discovered prefix. Skip the rebase and the WAL of a renamed catalog is addressed under a name
  no file carries — which reports empty rather than missing, so the failure is silent data loss, not an error.
- **Adoption's rename is best-effort and is not retried.** A folder that could not be renamed binds
  under its bare name and works identically; once bound it classifies as `REFERENCED`, which is
  matched before the foreign row, so adoption never revisits it. The plan's claim that the migration
  "retries on the next boot" was wrong, and the retry it imagined is the boot-time rename of
  *referenced* folders, which is not implemented (see below).

## Verification

- Full functional suite across the work — **20 968 tests**, **20 983** after the version fix — green
  apart from the environmental non-passes below, each of which reproduces on unrelated commits and
  none of which is caused by this work. `ExportS3ServiceTest` needs a Docker environment, and two
  wall-clock waits (`SharedRgeiSoakTest`, a two-minute Awaitility condition in
  `SystemGraphQLSubscriptionsFunctionalTest`) time out under fork contention and pass in isolation.
  One run also exhausted the shared JVM's heap, taking two dataset fixtures with it.

  `SharedRgeiSoakTest.shouldSurviveSeed[5]` was pinned down properly while verifying the version fix,
  since it failed four consecutive full-suite runs and had been green in an earlier session. It is
  **pre-existing and unrelated to any of this work**: it fails identically with all three version-fix
  hunks reverted, and with the new tests `@Disabled` so they add no load. In isolation all 13 seeds
  pass in 22.9 s against 315.2 s under the suite (13.8×), the run's surefire dumpstream is JVM
  `[gc,alloc] Retried waiting for GCLocker too often` allocation stalls in exactly the failure window,
  and the failure is a 100 s commit watchdog with no evitaDB-level exception preceding it. Do not
  read a green suite as a precondition for this test — read the four-configuration table instead.
- **`CatalogPointerSwapReaderAvailabilityTest`** is the acceptance criterion for the reader guarantee:
  a pool of readers spans a rename and a replace, every failure must be in the invalid-usage family,
  and across a replace the readers must observe the collection size on *both* sides of the swap and
  nothing in between. The pool is held until each reader completes an iteration that provably began
  after the operation returned, so a pool that finished early cannot pass vacuously.
- **`EngineTransactionManagerForwardReplayTest`** proves a crashed catalog removal now replays instead
  of wedging the engine — reaching the post-crash version at all is the assertion, since a wedge
  leaves the bootstrap one version behind.
- **`EvitaTest`** pins the shape: a rename leaves the data in place, a replace repoints at the source
  folder and removes the superseded one, a duplicate lands in an allocated folder with its provisional
  marker cleared, and repeated replaces do not grow `retiredFolders`.
- **`EvitaTest#shouldNotLoseACatalogVersionToARename` / `…ToAReplace`** are the end-to-end proof for the
  version accounting: commit, rename or replace, assert the version did **not** move, restart, read the
  data back, commit again, assert it advanced, restart again. Both assert the version directly rather
  than inferring it from a successful boot, so a regression names the defect instead of merely failing
  to open. Calibrated in both directions — see the two error strings in `0e1a13142`. They also turn out
  to be the only coverage of the fourth WAL-provider site fixed in `5c1dc4919`, which shipped without
  any: reaching it needs a commit *after* the rename plus a restart.
- **`DefaultCatalogPersistenceServiceTest#shouldNotReportInvertedVersionBlockWhenTwoBootstrapRecordsShareAVersion`**
  covers the version-block clamp, which two operations now make reachable — a rename and the identity
  reconciliation at load both *materialise* a version rather than producing one, so two bootstrap records
  can share one. Calibrated: reverting the `Math.min` in the descending branch fails it with
  `Block 3..2 starts after it ends (FROM_NEWEST_TO_OLDEST)`.
- The three rename/replace tests above are also what caught the **checkpoint-ordering defect** recorded in
  *Decisions taken*, which arrived from `dev` rather than from this work and is latent there too: `dev`'s
  `replaceWith` has the identical publish-then-close-then-reopen shape, and escapes only because its own
  rename tests go live and rename with no ALIVE transaction in between, so no round ever defers a
  checkpoint. Reaching the defect needs a commit *before* the rename — which these tests do, because they
  are about the WAL.
- **`CatalogFolderClassifierTest`, `CatalogFolderCleanerTest`, `CatalogFolderAllocatorTest`** cover
  the classification table, the drain and generation burn-and-skip as pure units, written before the
  code they test — this is the step where a bug deletes user data.

## Consequences & open follow-ups

- **Forward replay of rename/replace is still not implemented.** The old blocker (an unrepeatable deep
  on-disk rename) is gone, and the disk work is now idempotent. What blocks it is narrower: the
  completion phase stages a *live catalog instance*, and at replay time every catalog is still a
  placeholder, so `stageCatalog` would file the name in the wrong bucket. It needs a builder path that
  rebinds and re-buckets without a live instance. `ModifyCatalogSchemaNameMutationOperator` wedges the
  engine meanwhile, as before.
- **The boot-time cosmetic rename does not exist.** §3.4 option A was accepted as *marker plus deferred
  rename*; only the `.catalogname` marker shipped. A folder therefore keeps the name it was allocated
  under forever, and `cat storage/*/.catalogname` is what answers "which folder is which". The same
  missing pass is what would retry a failed adoption rename.
- **Adoption cannot detect a folder/header name mismatch.** A folder named `products` whose header says
  `orders` is adopted under the folder's name rather than reported as a conflict. Reading the name from
  the header needs an open offset index, which boot classification has no way to obtain.
  `AdoptableCatalogFolder` carries name and token separately precisely so that lifting this needs no
  shape change.
- **No fault-injection seam for a failed folder delete.** The §3.7 scenario "prove the tombstone path
  never surfaces to readers under an induced delete failure" is not covered; the failure path itself is
  covered where it lives, in `CatalogFolderCleanerTest`.
- **A crash between an adoption's rename and its binding** leaves a renamed, unreferenced folder, which
  classifies as unclaimed: reported, never touched, recoverable by renaming it back to a suffix-free
  name. This is the same window `completeFolder` opens for create and restore. Closing it needs a
  rebind engine mutation.
- **The persisted generation peaks are never written.** `EngineState.generationPeaks()` is read at boot, carried
  through the builder and serialized, but no production path constructs a `CatalogGenerationPeak`. So the
  two-term boot seed runs on one term: the disk scan. That covers the ordinary case, and leaves uncovered the
  one the peaks exist for — a generation burned against a name the filesystem then reports as absent (an
  `AccessDeniedException` reads as absence), which is drawn again after a restart. Recording the peak belongs in
  the engine-state commit of whichever operation drew the number.
- **A bootstrap-less start registers only adoptable folders**, so a storage root full of allocated
  `<name>_<gen>` folders comes up with no catalogs until an operator renames them suffix-free. Reading the real
  name out of each folder's `.catalogname` marker would recover them automatically and is the obvious next step;
  it was not taken because two folders can legitimately claim one name — the survivor of a replace and the
  orphan it superseded — and nothing in that path can currently tell them apart.
- **Tombstone coverage stops short at both ends.** Nothing proves end-to-end that a persisted tombstone is acted
  on at boot: the replay test defers to the drain, and the accumulation test covers only the in-run discharge.
  `EngineState.withRetiredFolder` / `withoutRetiredFolders` have no direct unit tests either, so the backwards
  walk that makes a multi-entry drain correct is held in place by a comment alone.
- **Restore reservations are keyed by catalog name only.** Two restores of the same catalog name overlapping in
  time would have the second allocation overwrite the first's reservation, and the first could then register —
  and clear the provisional marker of — the second's still-incomplete folder. Making a reservation task-scoped,
  or refusing a concurrent restore before allocating, is the fix; neither is in place.
- **The silent default-bootstrap path** at `getLastCatalogBootstrapWithAutomaticUpgrade` turns an empty
  folder into `CatalogBootstrap(0, 0, now, null)` and surfaces much later as "no schema found, the data
  are probably corrupted". It is what turned a one-line defect in this work into a lost session, and is
  worth tightening now that folders are allocated separately from being written.
- **A catalog in the MISSING bucket keeps a binding nothing can overwrite.** `bindingsIncluding` returns the
  binding array unchanged when the name is already bound, and the bucket entry is cleared only by
  `withRestoredFromMissing` — create and replace call neither. So restoring a backup over a MISSING catalog
  binds the folder that vanished and leaves the restored data unreferenced; creating a catalog under a MISSING
  name loses it at the next restart; replacing one puts the name in `activeCatalogs` and `missingCatalogs` at
  once and wedges the next boot. This matters more than it looks, because `isAdoptableCatalogName` refuses any
  folder whose bare name belongs to a registered catalog — missing ones included — so restore is the only
  recovery route there is.

  **Fixed in `f04f33109`**, after this record was first written. `withRestoredFromMissing` became
  `withCatalogNoLongerMissing` and now drops the binding along with the bucket entry, so the `withCatalog` that
  follows establishes the new one; create and replace clear the bucket too. The three-way builder split — the
  reason the fix was originally deferred — is left intact, because making the binding *absent* tells the truth
  about a catalog whose folder is gone and needs no new way to overwrite a live binding.
- **Renaming an ALIVE catalog that had committed a transaction broke the next boot — now fixed**, in
  `0e1a13142`, after this record was first written. The defect predated the work (`replaceWith`'s
  `version() + 1` and `verifyIntegrity`'s WAL assertion were byte-identical at the branch point) and was
  invisible until the WAL provider was fixed, because the WAL being looked for did not exist and so could not
  disagree — the transactions were dropped in silence instead. The broken window was exactly one restart: the
  first commit after a rename put both counters back in step.

  The blocking question at the time was whether `OffsetIndex` accepts a write at an *already-flushed* version.
  **It does**, on four independent grounds: `getNonFlushedEntriesToPromote` asserts `>=` rather than `>`;
  `flush` guards its advance with `<`, so an equal-version flush is a no-op and never a regression;
  `Roots.append` supersedes the tail entry when `addVersions[0] == tail`, with a comment naming this case; and
  decisively, `reconcileStoredCatalogIdentity` already performs the identical header + schema part +
  `flush(catalogVersion)` + bootstrap sequence at an unbumped version on the load path, in production.

  Two things the fix surfaced that the reasoning above had not. `TransactionManager#notifyCatalogPresentInLiveView`
  asserted a *strictly* advancing version and a rename builds a **new** instance at the same version, so the
  rename died on the assertion rather than on the boot — found by the first test run, after three review passes
  and a full reading of the blast radius had missed it. And `getCatalogVersions`' descending branch lacked the
  `Math.min` clamp its ascending sibling has, inverting a block to `(3, 2)` whenever two bootstrap records share
  a version — **pre-existing and reachable without the rename at all**, since the identity reconciliation
  already appends a record at an unbumped version on every load under a different name.
- **Client-visible:** a duplicated or restored catalog now has an id distinct from its source, including
  restore-in-place after a disaster. That is the intended outcome — the restored catalog lost everything
  committed after the backup point, so reusing the id would let a client keep serving what it believes
  is current.

## Timeline

- **2026-08-05** — investigation and design; the plan and its seven proposed points assessed
- **2026-08-06** — steps 1–9 implemented across ten commits on `649-cannot-replace-catalog-through-grpc`
