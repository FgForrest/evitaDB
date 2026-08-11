---
title: Bind catalogs to opaque folder tokens, and make rename and replace a pointer swap
date: 2026-08-06
updated: 2026-08-11 20:20
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

## The second fork — what the engine holds, a path or a token

Given a pointer swap, the engine still has to name the folder somehow. The first implementation used
`java.nio.file.Path`: the engine resolved the folder and passed it down. The alternative — adopted — is
an opaque `CatalogFolderId` that only `evita_store_server` may resolve.

The layering argument alone was not decisive, because the engine *carries* folder paths without ever
*using* them; on that reading a `Path` is merely untidy. Two findings settled it:

- **The seam did not create the leak, but was about to institutionalise it.** Thirteen path-construction
  sites already existed in the engine. Keeping `Path` would have frozen that leak into a *new* SPI
  contract and a *new* `EngineState` serializer — at the single moment when changing both cost nothing.
  Timing, not tidiness, is why this was done now rather than later.
- **The token's textual form has to stay unconstrained**, which a `Path` does not express and a
  `(name, generation)` pair cannot represent at all — see *Rejected outright*.

The engine legitimately knows `storageOptions.storageDirectory()`, since that is configuration rather
than layout. Error messages therefore state both facts side by side — `storage folder 'products_3'
(storage root: '/data/evita')` — without the engine ever computing the join, which keeps the operator
ergonomics the absolute path used to provide.

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
| `getCatalogStoragePath()` is **removed** from the public API rather than left returning a blank, and `getCause()`'s `BiFunction<String, Path, …>` changes with it — an accepted breaking change, labelled and shipped in one release | Under a token binding the engine cannot honestly populate a path, and a permanently-blank field is documentation-backed misinformation — worse than an absent one, because clients keep reading it. Zero callers were found outside the API files and one test, so the break is nominal; splitting it across releases would make clients absorb two | `CatalogContract`, `EvitaContract`, `UnusableCatalogDescriptor` |
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
| Carry the binding as a `(name, generation)` pair instead of a free-form opaque token | It cannot represent the folders the design requires: an adopted foreign folder keeps its bare, suffix-free name, and a legacy folder whose boot rename fails is recorded under its bare name too. Neither has a generation, so the pair needs a sentinel — which is the token's textual form leaking back as a special case. The free-form string is the *minimum* representation that covers the design, not a preference | never — the bare-folder cases are load-bearing, not edge cases |
| Report `-1` for `sizeOnDiskInBytes` on a corrupted catalog, since the placeholder can no longer stat the folder itself | It is a *second* client-visible regression, and the system-API catalog listing includes corrupted catalogs, so it is reachable. `UnusableCatalog` is handed a store-backed `CatalogFolderOperations` instead — the same seam the tombstone drain needs anyway, so it is not transitional scaffolding | never; the handle exists regardless |
| Relax `verifyIntegrity` to `bootstrap >= WAL` so a rename's extra version passes | That is exactly the shape the WAL-provider defect produced — empty WAL at 0, bootstrap at 3 — so it would re-mask the silent transaction loss that fix had just made visible. The assertion is the only thing that catches it | never; the assertion is load-bearing |
| Append a WAL transaction for the rename, so the two counters agree by construction | Disproportionate to a one-restart window, and it puts a non-transactional engine operation into the catalog's transaction stream, where every consumer would then have to recognise and skip it | only if a rename ever has to be replayable as part of the catalog's transaction history |
| Drop the `previousLivingCatalog == livingCatalog` identity escape as redundant once the version comparison is `<=` | Unverified and wrong: `<` → `<=` only ever *admits* more, but deleting the escape *rejects* a same-instance republication at a version below the previous one, which the previous form allowed. Keeping both disjuncts makes the change a pure widening | never — the two disjuncts cover different cases |
| Settle the owed checkpoint inside `writeCatalogBootstrap`, the seam that already covers every bootstrap writer structurally | Two concrete blockers. Settling unconditionally there reports a checkpoint completion on every warm-up flush, which the cadence gauge deliberately excludes so a bulk load cannot fill it with samples no checkpoint produced; and clearing the prepared record *without* settling makes `checkpointIfOwed` fail its own premise that a record exists whenever a checkpoint is owed — trading a wrong pointer for a thrown error on the next close | the gauge stops depending on `noteCheckpointCompleted`, or the premise becomes "owed implies prepared *or* already superseded" |
| Treat a **suffixed** folder carrying a `.catalogname` marker as adoptable, to close the window between an adoption's rename and its binding | **The reason first recorded here — that it would equally make a replacement's orphan adoptable and resurrect a replaced catalog — does not hold on the normal boot path, and is corrected rather than left to be obeyed unexamined.** `classifyOne` matches `RETIRED` *before* the suffix split, and the orphan is tombstoned in the same commit that unbinds it, so it never reaches the adoption row while the engine state exists. The honest blocker is different: an operator's wholesale copy of a suffixed folder from another instance would be adopted as a live catalog. The original reason survives only on the bootstrap-less path, where no tombstone exists — and there the one-claimant rule gates it instead | if the wholesale-copy hazard is addressed; the option remains rejected, but for that reason and not the one first given. Closing the rename/binding window itself still wants a rebind mutation |

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

- Full functional suite across the work — **20 968 tests**, **20 983** after the version fix, **21 073**
  after the rebase onto `dev` `1f67194ca`, **21 138** after the rebase onto `dev` `c1229ead4` (which
  brought in #761), **21 148** after the follow-up fixes below — green apart from the environmental
  non-passes, each of which reproduces on unrelated commits and none of which is caused by this work.
  The last run is the one to quote: **21 151 tests, 0 failures, 1 error** — that error being
  `ExportS3ServiceTest`, which needs a Docker environment this machine does not have.

  **That figure is not `21 148 + 1` and the difference is the instrument, not the work.** Every count above
  it came from `-P unitAndFunctional`, whose `excludedGroups` drops `slow,flaky`; the final run used a bare
  `mvn -o test`, which applies no exclusions. The module holds exactly two `@Tag(SLOW)` tests —
  `SortIndexRankScalingTest`, and `shouldMatchAcrossMultipleLeavesAndContainers` in
  `SortIndexTreeProviderEquivalenceTest` — so `21 148 + 2 slow + 1 new = 21 151`, with nothing lost. Do not
  "reconcile" the series by assuming a test went missing; compare like with like, or the number will be
  re-derived wrongly.

  **The review-fix round is the worked example of that warning.** It added ten test methods and the suite came
  back at **21 159 tests, 0 failures, 1 error** — the same Docker-only `ExportS3ServiceTest` — which looks like
  eight new tests, not ten. It is neither: that run used `-P unitAndFunctional`, so it excludes the two slow
  tests the `21 151` run included. `21 151 − 2 slow + 10 new = 21 159`, exactly. The per-class counts confirm
  it independently (`EvitaTest` 100 → 101, `CatalogFolderCleanerTest` 12 → 13, plus 4 + 3 in the two new
  classes and 1 in `DefaultEnginePersistenceServiceTest`), which is the check worth doing: an aggregate that
  disagrees is answered by counting the classes you touched, not by arguing about the total.

  A second `/codex:review` on the finished branch found the third handover ordering recorded below, and the fix
  for it brings the suite to **21 160 tests, 0 failures, 1 error** — the same Docker-only `ExportS3ServiceTest`,
  under the same `-P unitAndFunctional`, so `21 159 + 1 new = 21 160` compares like with like. Calibrated both
  ways against the one-line counterfactual (`claim.set(reservation)` in place of the compare-and-set): pre-fix
  the run is 5 tests / 1 error, and the error is
  `RestoreFolderClaimTest#shouldReleaseAClaimPublishedAfterTheHandover` failing with the
  `ConcurrentCatalogMaterializationException` the defect predicts; post-fix it is 5 / 0, with no other test in
  the class moving either way.

  A third review round — folder lifecycle rather than the claim handover — brings the suite to **21 163 tests,
  0 failures, 2 errors**. The arithmetic is `21 160 + 3`, not `+ 2`: the mis-specified
  `shouldPreferTheLiveBindingOverAReservation` was *replaced* rather than deleted, and the recovery case it
  falsely claimed to cover gained a test of its own alongside the two `FileUtilsTest` additions. Calibrated with
  both counterfactuals applied at once — the old bound-before-reserved order and the old `Files.exists` guard —
  giving exactly 2 failures out of 31, one per mutation, zero collateral.

  **The second error is the load-sensitive GraphQL subscription flake, and isolation is what says so.**
  `SystemGraphQLSubscriptionsFunctionalTest#shouldReceiveSystemCaptureWithoutBody` blew a two-minute Awaitility
  condition in the full run; the whole class then passed in isolation, **15 tests, 0 failures, in 9.9 seconds** —
  less wall-clock for the entire class than that one assertion was given. The `FileUtils` change made this worth
  checking rather than assuming, since two thirds of `deleteDirectory`'s callers are suite teardown: the run was
  swept for unexpected delete failures and had none, every occurrence belonging to a test that intends one.

  **Reinstall `evita_engine` between the two halves of a calibration like that one.** No signature changed, so a
  stale `~/.m2` jar raises no compile error — it silently runs both halves against the same bytecode and the
  two-sided result reads as noise rather than as the tooling problem it is. `-pl evita_engine` alone is not
  enough either: on this branch it resolves a pre-branch `evita_api` and fails to compile. Install the reactor
  once with `-DskipTests`, then iterate.

  Reclaiming the generation counters brings the suite to **21 165 tests, 0 failures** attributable to the work —
  `21 163 + 2`, the two being `CatalogGenerationSequenceReclamationTest`. Calibrated one mutation at a time,
  which is the part worth recording: applying both at once proved nothing, because disabling the hook outright
  also stops the binding guard inside it from ever running, so the second mutation was masked and only one test
  failed. Run separately, each mutation killed exactly its own test — the disabled hook gives `products_1 →
  products_2`, and dropping the `boundFolderIdFor` guard gives `products_3 → products_1` — with no collateral
  either way. **A calibration whose mutations are nested is not a two-sided calibration**; check that each
  counterfactual is reachable with the others in place before reading a single failure as confirmation of both.

  That run carried three non-passes and **one of them names a catalog-duplication test**, which is close enough
  to this change to be worth the isolation run rather than the plausible explanation.
  `EvitaClientReadWriteTest#shouldDuplicateCatalogWithProgress` never reached its own body: it failed in
  parameter resolution on a gRPC `TransportException` while the shared dataset was being rebuilt, with the other
  63 methods of the class green. In isolation the class passes **64 / 64 in 51.2 s**, against **356.8 s** under
  the suite — and the single method that failed there had consumed 128.7 s by itself.
  `StaleLeafPageTwinWriterReproductionTest`, a wall-clock hung-thread assertion already recorded above as
  pre-existing, likewise passes **4 / 4 in 10.55 s**; both together are **68 tests, 0 failures, 0 errors**. The
  third is the usual Docker-only `ExportS3ServiceTest`.

  An earlier run in the same session came in at 21 144 with four additional non-passes, all of them wall-clock or
  connection-availability assertions — two `EvitaClientReadWriteTest` methods (a 100 s commit watchdog, then
  a shared dataset that could not be set up after it), `EvitaSessionServiceFunctionalTest` on a gRPC
  `UNAVAILABLE`, and `StaleLeafPageTwinWriterReproductionTest` on a hung-thread assertion. **All three
  classes passed together in isolation: 123 tests, 0 failures, 0 errors**, and none recurred in the final
  run. Isolation is the discriminator that matters here, not the plausibility of the explanation: earlier in
  this work a failure was argued to be load on exactly that reasoning and the isolated run disproved it.
- The second rebase is the run worth reading, because a clean rebase was not a working one. It produced
  three conflicts and then **failed to compile twice** — both times on code that never conflicted,
  because it was new on `dev`'s side and so had nothing to be merged against: 25 constructor sites and
  three `duplicateCatalog` calls in #761's new tests, and `getOldestRetainedCatalogVersion` in the
  persistence service itself. It then failed **one** test for a reason no compiler could see, the
  bare-name folder resolution recorded below. Earlier runs of the suite lost `EvitaClientReadWriteTest`
  methods and a GraphQL subscription test to fork contention; neither recurred in the final run, and
  both pass in isolation.

  Earlier runs also lost wall-clock waits in `SharedRgeiSoakTest` and a two-minute Awaitility condition in
  the GraphQL subscription tests, and one exhausted the shared JVM's heap, taking two dataset fixtures
  with it. `dev` has since moved `SharedRgeiSoakTest` out of the fast loop in `9031a2159`, independently
  reaching the same conclusion recorded below.

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
- **`EvitaTest#shouldReserveNothingUntilTheRestoreActuallyRuns`** is the acceptance criterion for lazy
  folder allocation: build a restoration task, never submit it, and neither a folder nor a claim may exist
  afterwards. Both halves were calibrated separately against eager allocation — the folder half reports
  `[testCatalog_restored_1]`, and with that assertion suppressed the claim half reports
  `ConcurrentCatalogMaterializationException`. What proves the *ordering* is not this test but the restore
  paths that still pass: `shouldBackupAndRestoreCatalogViaDownloadingAndUploadingFileContentsUnary` above
  all, since the claim now lands inside step 1 and the registering mutation resolves it by name in step 2.

## Consequences & open follow-ups

- **A replace makes the target's version stream jump to the source's lineage.** Client-visible, and not a
  regression — the old code computed `newCatalogVersion` from the *source* header too — but the mechanism
  changed underneath it, so it is stated here rather than left to be rediscovered from a diff.
- **`CatalogContract#terminateAndDelete` was a design constraint that no longer exists.** It was invoked
  polymorphically on `UnusableCatalog`, the catalog *without* a persistence service, so the folder-level
  delete had to reach it through an injected store-backed handle. The delete routes this decoupling made
  dead were removed instead. Recorded because the constraint is a natural thing to re-derive from the old
  shape and then design around, and there is no longer anything to design around.
- **Forward replay of rename/replace is still not implemented.** The old blocker (an unrepeatable deep
  on-disk rename) is gone, and the disk work is now idempotent. What blocks it is narrower: the
  completion phase stages a *live catalog instance*, and at replay time every catalog is still a
  placeholder, so `stageCatalog` would file the name in the wrong bucket. It needs a builder path that
  rebinds and re-buckets without a live instance.

  **It does not need a new engine mutation** — the WAL already carries `ModifyCatalogSchemaNameMutation`, and a
  durable crashed record of that type is the premise of the whole scenario. It needs one new
  `ExpandedEngineState.Builder` method and a `replayCompletionState` override on the existing operator. Recorded
  because the "new mutation type" estimate is what justified deferring this, and it is roughly an order of
  magnitude too large.

  **There is a second blocker, and it is the one that bites first.** Every existing `replayCompletionState`
  mints its placeholder through the 3-arg `createUnusableCatalog`, which resolves the folder via
  `folderIdFor(catalogName)` — and that *throws* on an unbound name while reading state still at the pre-mutation
  version. For a rename the destination name is unbound there, so replay would throw out of the
  `EngineTransactionManager` constructor and **the engine would not boot at all**. The 4-arg overload taking an
  explicit folder id already exists and is the fix; the failure mode if it is missed is a dead engine.

  **What "wedges the engine" actually costs today is worse than it sounds**, and is the argument for doing this:
  boot continues and reads serve, but every engine mutation throws — and the crashed WAL record is then
  *truncated away* against the un-advanced state. On restart the operation has silently un-happened, the stored
  header disagreeing with the binding is reconciled back in one direction, and the catalog schema version is
  bumped for good measure. A rename the WAL had committed vanishes without a trace. That is a durability
  violation of the WAL-first contract, which is the same argument that earned `RemoveCatalogSchemaMutationOperator`
  its replay.

  **A trap for whoever picks this up:** the tombstone must be gated on the destination's presence in the catalogs
  map, never on `boundFolderIdFor(dest) != null`. A MISSING destination keeps a binding by design, naming a folder
  that no longer exists — tombstoning it authorises deleting a folder a later allocation could legitimately hold.
- **The boot-time cosmetic rename does not exist.** §3.4 option A was accepted as *marker plus deferred
  rename*; only the `.catalogname` marker shipped. A folder therefore keeps the name it was allocated
  under forever, and `cat storage/*/.catalogname` is what answers "which folder is which". The same
  missing pass is what would retry a failed adoption rename.

  **A consequence not drawn when this was written: the marker's write is best-effort and its failure is
  swallowed, and nothing ever repairs it.** A folder whose marker write failed at creation has no label and never
  gets one. That is harmless while the marker is decoration — and stops being harmless the moment any of the
  follow-ups above starts *reading* it to recover a name. So a **marker-repair pass** — at boot, rewrite
  `.catalogname` for every referenced folder whose marker is missing or disagrees with the bound name — must ship
  with or before anything that trusts the marker, not after. It is much smaller than the rename pass: no move, no
  new mutation, no API surface. It is *not* a substitute for the rename pass, which is separately the only retry
  path for a failed adoption rename and the only automatic healer for the crash-between-rename-and-binding
  remnant. Note the rename pass, when it lands, shifts what the boot-time disk scan seeds — a catalog `sales`
  living in `orders_7` currently floors `orders` and would afterwards floor `sales`. That is a correction, but it
  changes allocation numbering on existing installations.
- **Adoption cannot detect a folder/header name mismatch.** A folder named `products` whose header says
  `orders` is adopted under the folder's name rather than reported as a conflict. Reading the name from
  the header needs an open offset index, which boot classification has no way to obtain.
  `AdoptableCatalogFolder` carries name and token separately precisely so that lifting this needs no
  shape change.

  **The "no cheaper name source exists" premise this implies is false, and the consequence is worse than
  "adopted under the folder's name".** The header reason stands — the bootstrap record's `fileLocation` is the
  offset-index root descriptor, so reading `CatalogHeader#catalogName` needs a whole persistence service per
  unreferenced folder, before the engine exists. But `CatalogFolderClassifier.readContents` already opens a
  directory stream over every candidate folder and inspects each entry name; it just reduces what it sees to two
  booleans. The `.catalogname` marker is one `equals` and one small `readString` away, and the `*.boot` prefix is
  already matched there and thrown away as a boolean. Their weaknesses are complementary: the prefix goes stale
  after a #649 rename, but such a folder *has* a marker; a legacy folder has no marker, but pre-#649 a rename
  moved the files too, so prefix equals catalog name there by construction.

  What actually happens today is not adoption under a wrong label — the catalog is silently and **permanently
  renamed**. Adoption's first act rewrites `.catalogname` with the folder-derived name, destroying the one
  durable record that could have detected the mismatch, and the load path then rewrites the header and the
  catalog schema to match. That is precisely the outcome `createNewEngineState`'s own comment declares
  unacceptable — the comment defends only against the suffix case, never against a hand-renamed directory. Any
  fix must therefore also stop adoption overwriting the marker before the adoption is authorised. Note the
  blast radius: this removes import-by-directory-rename, which currently works but is undocumented, so it is
  emergent behaviour rather than a contract.
- **No fault-injection seam for a failed folder delete.** The §3.7 scenario "prove the tombstone path
  never surfaces to readers under an induced delete failure" is not covered; the failure path itself is
  covered where it lives, in `CatalogFolderCleanerTest#shouldReportNothingWhenTheDeleteFails` — which makes a
  real `Files.delete` fail via POSIX permissions and pins the invariant that carries the risk: a folder that
  survived the delete must **not** appear in the removed-report. That report is what discharges the tombstone,
  so reporting a folder still on disk would strike the record while the data stays, and nothing reclassifies a
  folder afterwards to refill it. That sentence was an unbacked claim when this record was first written; the
  test was added afterwards to make it true.
- **A crash between an adoption's rename and its binding** leaves a renamed, unreferenced folder, which
  classifies as unclaimed: reported, never touched, recoverable by renaming it back to a suffix-free
  name. This is the same window `completeFolder` opens for create and restore. Closing it needs a
  rebind engine mutation.
- **The persisted generation peaks are never written.** `EngineState.generationPeaks()` is read at boot, carried
  through the builder and serialized, but no production path constructs a `CatalogGenerationPeak`. So the
  two-term boot seed runs on one term: the disk scan. That covers the ordinary case, and leaves uncovered the
  one the peaks exist for — a generation burned against a name the filesystem then reports as absent (an
  `AccessDeniedException` reads as absence), which is drawn again after a restart.

  **Kryo backward compatibility is not engaged**, which was the open question here: the serializer already writes
  and reads the peaks array, and `EngineState`'s `serialVersionUID` is not among the ids registered as
  backward-compatible, so the current format already carries the field. Writing peaks changes the *values* in a
  field the format has always had — no protocol bump, no new reader. What is missing is only a writer: there is
  no `withGenerationPeak` sibling to `withRetiredFolder`, and `ExpandedEngineState.Builder` has no peaks field at
  all (though its `build()` copies them through the base state, so persistence works the moment one is written).

  **The prescription first recorded here — "record the peak in the engine-state commit of whichever operation
  drew the number" — has a hole at exactly the case peaks exist for**, and is corrected rather than left to be
  implemented as written. It records only on the success path, and a failed attempt commits nothing: a restore
  that burns a number and then dies at `verifyApplicability` loses it. Meanwhile the success case it *does* cover
  is already covered by the disk scan, because a successful allocation leaves a visible `<name>_<gen>`. Strictly
  weaker at the same cost. Hooking the *generation supplier* instead records every draw including the failed
  ones, and covers allocation and adoption with one change; the pending peaks then fold into the next engine
  commit beside the tombstone drain that already works this way. Residue to state plainly: if a number is burned
  and no engine mutation ever follows, the pending peak dies with the process. Merge by `max`, never append —
  the peaks array is *strictly* ascending by name and a duplicate throws from the canonical constructor.
- **A bootstrap-less start registers only adoptable folders**, so a storage root full of allocated
  `<name>_<gen>` folders comes up with no catalogs until an operator renames them suffix-free. Reading the real
  name out of each folder's `.catalogname` marker would recover them automatically and is the obvious next step;
  it was not taken because two folders can legitimately claim one name — the survivor of a replace and the
  orphan it superseded — and nothing in that path can currently tell them apart.

  **That reason is accurate — I re-verified the two-claimant case holds — but it forecloses less than was
  concluded from it.** It argues against auto-*picking*, not against *reading*. Names claimed by exactly one
  folder are unambiguous and are lost today for no reason; recovering those and reporting the ambiguous ones
  introduces no guess and is strictly better than the status quo. Discriminators for the ambiguous case remain
  rejected: highest `catalogVersion` is *actively wrong*, since a replace makes the survivor inherit the source's
  lineage, which can be numerically lower than the orphan's; newest bootstrap timestamp is readable without an
  offset index but is a heuristic in the one table whose rows must stay disjoint.

  **The blocker that actually stops an implementer is a different one**, and is not the reason recorded above:
  `createNewEngineState` builds its state through the seven-argument `EngineState` convenience constructor, which
  hard-wires identity bindings and therefore *cannot express* `orders → orders_3` at all, whatever it reads off
  disk. Any fix starts by moving to the builder with explicit `catalogFolders(...)`.

  **The stated severity is also off.** "Comes up with no catalogs" is unreachable whenever engine WAL files
  survive: the bootstrap-less path sets version 1, and the drift assertion then compares a real WAL version
  against it and *refuses to boot*. The operator's actual experience is a refused boot, then deleting the WAL on
  advice, and only then a start with zero catalogs. Do not relax that assertion to make this path boot — it is
  the only detector of engine-state/WAL drift, and this record already carries one silent-data-loss defect it
  was the sole guard against.

  **Governance:** recovering names this way would narrow the *Decisions taken* row "only suffix-free folders are
  adoptable" for the bootstrap-less path. That is a recorded decision rather than an open follow-up, so it needs
  an amending record — unlike the other adoption follow-ups here, which closing merely completes.
- **Tombstone coverage stops short at the boot end.** `EngineState.withRetiredFolder` / `withoutRetiredFolders`
  now have direct unit tests (`EngineStateTest.FolderTombstones`), so the backwards walk that makes a multi-entry
  drain correct is no longer held in place by a comment alone. Calibrating that test on the *silently* wrong
  arrangement rather than the throwing one was deliberate: draining the two lowest of four entries yields the
  wrong surviving pair under a forward walk, whereas a set that merely overran the array would still pass if
  someone "fixed" the forward walk by clamping the index.

  The **boot half** used to be the one unjoined link — every step existed in isolation (classification,
  deletion, Kryo round-trip, in-run discharge) and nothing proved end-to-end that a *persisted* tombstone is
  acted on at boot. It is now joined by
  `DefaultEnginePersistenceServiceTest#shouldDrainAPersistedTombstoneAtBoot`, which needed no new seam: it
  seeds a state carrying a `RetiredFolder`, puts that folder on disk, reopens the service, and asserts both
  that the folder is gone *and* that the divergence reports it drained. Both halves are load-bearing and catch
  different reverts — dropping `RETIRED` from the cleaner's drained states leaves the folder on disk, while
  dropping the `removedFolderNames` disjunct deletes the folder but reports nothing drained, which is the
  permanent-leak shape: a folder that is gone is never classified again, so the tombstone survives in persisted
  state forever. A bootstrap file is not needed — the classifier matches `RETIRED` before the no-bootstrap row.
  Asserting on `retiredFolders()` after reopen would be wrong: the boot path deliberately never commits a state
  to drop the entry, so the array is still length 1 after a clean boot.
- **Reservations are keyed by catalog name only, and the consequence is silent data loss.** `allocateFolderFor`
  writes `reservedFolders[name] = token` (`CatalogFolderContext`), and `folderIdForBinding` reads that map by name.
  Two operations materialising the same name concurrently therefore hand the *second's* token to the *first's*
  registering mutation. Verified interleaving: A allocates `products_1`; B allocates `products_2` and silently
  overwrites A's entry; A writes its archive into `products_1`; A's operator reads `products_2` out of the map,
  calls `completeFolder` with it — clearing **B's** provisional marker while B is still writing — and commits
  `products → products_2`. A reports success while serving B's data; A's archive sits in `products_1`, unreferenced
  and still provisional, so boot classification deletes it. Both `restoreCatalog` overloads pass
  `deleteAfterRestore = true`, so the source zip is gone too.

  **The two-arg `remove(key, value)` in `completeFolder` does not guard this**, which is easy to conclude by
  reading it in isolation — it was concluded, and it is wrong. The marker clear one line above is unconditional and
  acts on whatever token the caller passed, and the remove itself *succeeds*, because A is holding B's token: the
  values match precisely because they came from the same clobbered slot. That guard is for a different hazard (a
  late-arriving failed operation evicting a live reservation).

  **The strongest variant is restore ∥ duplicate, and it decides where a fix goes.**
  `DuplicateCatalogMutationOperator` writes the same map from a path `EvitaManagement` never sees, and
  `DuplicateCatalogMutation` is client-dispatchable over gRPC — so guarding inside `EvitaManagement.restoreCatalog`
  leaves the corruption reachable. Create is immune for a reason worth copying: its transition phase binds
  synchronously, so branch 1 of `folderIdForBinding` wins and the map is never consulted. **Restore's problem is
  that it has no transition phase.**

  **The premise recorded in `CatalogFolderContext`'s own JavaDoc — "a stale reservation can never be read by
  anything except an allocation that is about to replace it" — is false.** A restore rejected at
  `verifyApplicability` never reaches `completeFolder` and leaves its entry indefinitely, and
  `folderIdForBinding` reads the map without allocating. Correct that comment when the defect is fixed.

  **Fixed by making the claim a closeable handle.** `allocateFolderFor` returns a `CatalogFolderReservation`
  instead of a bare token, refuses while one is outstanding, and every materialising path releases it on every
  exit. The two halves are separable and both are needed: the *handle* supplies the release, and *exclusivity*
  is what makes the name-keyed lookup in `folderIdForBinding` correct — with one live claim per name, "the folder
  reserved for `products`" has exactly one answer.

  Exclusivity alone would have been a trap. Retry-after-failure previously worked *only* by overwrite, so a bare
  `putIfAbsent` would make a name permanently un-materialisable after its first failed create or restore; there is
  no `finally` in either operator to release on. `close()` is that missing release, which is why this is a handle
  rather than a boolean. Modelled on `CatalogVersionPin` — release bound at acquisition, idempotent close, and an
  `AtomicBoolean` guard, because a late double-release would evict a claim a later operation legitimately holds.

  Release points differ by path, and the difference is the interesting part — as is how easy it is to get one
  wrong. **Create** closes in try-with-resources around its work-phase *lambda*, which runs inside
  `CompletableFuture.runAsync` under an explicit try/catch, plus a separate catch on its synchronous half.
  **Duplicate** releases on `whenComplete` of the future it returns, plus a catch around the synchronous
  `duplicateTo` call. **Restore** is the one whose claim must outlive the method that took it — its registering
  mutation runs in a later task step — so the release rides on the task's own future, which completes on failure
  as well as success.

  **Restore needs more than a completion hook, because cancellation does not stop a running step.**
  `SequentialTask#cancel()` is bookkeeping: it marks the steps cancelled and calls `futureResult.cancel(true)`,
  but `CompletableFuture` ignores `mayInterruptIfRunning` and `Scheduler` discards the executor's `Future`, so
  nothing can interrupt a step that is already executing. A cancel landing while the registering step runs
  therefore fires the release hook on the cancelling thread and frees the name *mid-registration* — and because
  that registration resolves its folder **by name**, a second restore that takes the name in the window can have
  the first bind its catalog to the second one's half-written folder. The window is `[step 2 passes its QUEUED
  check]` → `[the operator reads `folderIdForBinding`]`, bounded by the 300 s `engineStateLock` timeout, and it
  is reachable by any client through `CancelTask` over gRPC as well as by `Scheduler#shutdown()`.

  **Fixed by ownership transfer**, in `RestoreFolderClaim`: both the registering step and the completion hook
  call `takeClaim()`, a single `getAndSet`, so exactly one can win. Step wins → it holds the name across
  the whole registration and releases in a `finally`. Hook wins → the name is freed at once and the step refuses
  to register, which also fixes a second, *deterministic* defect that needed no race at all — a cancelled restore
  used to register its catalog anyway whenever the cancel landed after the registering step started. Exactly-once
  by construction, no lock, cancellation stays non-blocking, and no WAL-format change.

  **A third ordering was missed on the first pass, and it leaked the claim outright.** The hook can get there
  while the holder is still *empty* — the cancel lands inside `allocateFolderFor`, which draws a generation and
  creates a directory, and is by far the widest window in the handover. A `getAndSet(null)` leaves nothing behind
  to record that it happened, so the allocation published its claim afterwards, to a hook that had already fired
  and fires only once. Nothing ever releases that claim, and `allocateFolderFor` refuses a name that is still
  held: create, restore **and** duplicate on that name fail until the process restarts — exactly the permanence
  `CatalogFolderReservation` was made a closeable handle to avoid. `takeClaim()` now parks a `HANDED_OVER`
  sentinel instead of `null`, and `allocate` publishes with `compareAndSet(null, …)` and closes on the spot when
  it loses. Behaviour on that path is otherwise unchanged from the already-accepted "cancel lands after
  publication" case: the name is freed, the step goes on unpacking, and the folder keeps its provisional marker
  and is reclaimed at the next boot.

  **The generalisation is worth more than the fix:** "release on completion" is sound only where the completion
  hook can see everything it might have to release. A hook armed *before* the resource exists needs the
  publication to be a compare-and-set against a terminal marker, not a store. Restore is the only path here with
  that shape — create and duplicate both hold their reservation in a captured local, so their hook is armed
  strictly after the thing it releases exists, and adoption is boot-time with no hook at all. Anyone adding a
  fourth materialising path should check which of the two shapes it has before copying either one.

  The folder token is deliberately held in a **separate field** from the takeable claim. `allocate` is call-once
  by contract but answers idempotently, and nulling a single shared field would turn that second read into a
  failure the moment the claim changed hands.

  Rejected, each with its blocker: **guarding the hook on step-2 termination** — same correctness, worse
  liveness, since cancellation would block for up to 300 s on the gRPC thread and on every task during shutdown.
  **Making `cancel()` await termination** — the most honest semantics and contained to one construction site, but
  it needs an "execution finished" signal that does not exist (step futures are *cancelled*, not completed) and
  it makes shutdown block on every running task. **Carrying the token on `RestoreCatalogSchemaMutation`** — the
  format blockers above still hold, and it is now also *insufficient*: it would stop the mis-binding but not a
  cancelled task registering a catalog at all.

  **Only duplicate was refuted as a second racer.** Duplicate and create allocate *inside* `applyMutation`,
  after their `CatalogConflictKey(name)` is registered, so `verifyEngineMutationIsNotInConflictWithOthers`
  refuses either ordering. Restore is the only path that allocates outside an engine mutation. Worth knowing
  before anyone loosens conflict-key granularity — that change would silently widen this exposure to duplicate.

  **Still open, and deliberately:** cancelling a restore does not stop the unpacking. A cancelled multi-gigabyte
  restore runs to completion while the client is told "cancelled". That is the same family as the `Scheduler`
  cancellation defects in #1415 and wants the same fix — an interruptible step — not a patch here.

- **A reservation outranks the binding in `folderIdForBinding`, and folder existence decides nothing.**

  The original order asked "is the bound folder still on disk?" and preferred the binding when it was, on the
  reasoning that a present folder means *recovery* while an absent one means *restore*. That is a proxy for the
  real question, and it breaks in the case the whole mechanism exists for: a missing catalog keeps its binding
  deliberately, so a reappearance can be matched against it, and a folder that reappears while an explicit
  restore is mid-flight made the lookup hand back the stale contents, release the reservation, and leave the
  freshly unpacked backup to be reclaimed at the next boot — success reported to the client, backup silently
  discarded, on the disaster-recovery path.

  A reservation answers the question directly: something is materialising this name *right now*, so bind to what
  it made. `RestoreCatalogSchemaMutationOperator` documents the only three paths through the lookup — recovery
  reads a binding and **allocates nothing**, restore reads the reservation `EvitaManagement` made, adoption
  reads the one boot-time renaming left behind. A reservation therefore exists in exactly the cases where it is
  the right answer, and recovery never competes with one. The existence test was not merely misplaced, it was
  answering a question nobody needed asked; `catalogFolderExists` survives only as the operator's own check,
  where the absence is reported in the terms an operator needs.

  **The find that matters here is the test, not the bug.** `CatalogFolderContextTest` carried a green
  `shouldPreferTheLiveBindingOverAReservation` asserting precisely the broken order, with a comment explaining
  that a present folder "is a recovery rather than a restore". It could only build that scenario by calling
  `allocateFolderFor` by hand — recovery never allocates — so it was asserting a state production cannot reach,
  and it is what let the wrong rule survive review. It is replaced by
  `shouldPreferTheReservationWhenTheBoundFolderReappears`, and the recovery case it *claimed* to cover now has a
  real test of its own in `shouldUseTheBindingWhenTheFolderIsPresentAndNothingIsReserved`. When a test has to
  construct its premise through an API no production path calls, that is the signal — not the coverage it looks
  like.

- **`FileUtils.deleteDirectory` must not treat an unreadable directory as an absent one.** It guarded the walk
  with `Files.exists`, which answers FALSE both for "not there" and for "cannot tell" — it swallows the
  `IOException`. A directory whose attributes cannot be read (a Windows ACL, a POSIX parent without execute)
  was therefore reported as already gone, and callers read a normal return as proof the data is drained: the
  boot cleaner counts the folder reclaimed and a retired-folder tombstone is discharged, leaving the contents on
  disk with nothing left referring to them, and nothing that will ever classify the folder again. The walk is
  now attempted and only `NoSuchFileException` is suppressed — `walkFileTree` hands a start-path failure to
  `SimpleFileVisitor#visitFileFailed`, which rethrows, so an unreadable directory arrives as
  `AccessDeniedException` and is reported. Suppressing exactly one exception type matters more than it looks:
  two thirds of this method's callers are test-teardown paths that delete directories which routinely do not
  exist, so a wider catch would be a diffuse suite regression and a narrower one a flood of teardown failures.

  Duplicate did not start out that way, and the correction is worth recording because the wrong shape looks
  right. It originally wrapped the *result mapper* in try-with-resources, but that mapper is a `thenApply`
  continuation of the copy, and `thenApply` is skipped precisely when the upstream fails — so the release sat
  only on the success path. A second leak sat next to it: `duplicateTo` is evaluated in argument position on the
  calling thread, and `duplicateCatalog` verifies the target directory, takes a directory read hold and walks the
  whole source *before* it returns a future, so a synchronous throw escaped while there was still no future to
  hang a release on. `whenComplete` was chosen over the `ProgressingFuture` `onFailure` consumer because
  `onFailure` is reached only through the `completeExceptionally` override, so a direct `cancel()` bypasses it.

  **Rejected: `try/finally` around the whole `applyMutation` body.** This is the shape a reader reaches for
  first, and it is actively wrong rather than merely inelegant — the body *returns* a future, so a `finally`
  releases the claim before the copy has even started, reopening the corruption the claim exists to prevent.

  The asymmetry that produced the bug is the lesson: create put its risky synchronous work behind a catch and
  its real work inside a guarded lambda, and duplicate did the reverse on both counts. A leaked claim is not a
  cosmetic defect — the wedge refuses **restore** of that name too, which is the disaster-recovery path where
  picking another name is not an option, so it converts a transient I/O error into one that needs a process
  restart.

  Rejected alternatives, each with its blocker: guarding at `EvitaManagement` misses duplicate; task-scoping needs
  an id the mutation API cannot carry (the transaction id is minted inside `applyMutation`); keying the map by
  token is self-defeating, since both callers hold only a name; carrying the token on
  `RestoreCatalogSchemaMutation` is architecturally the purest form — the terminal operation would then need no
  shared state at all — but it crosses the `evita_api`/`evita_engine` boundary and changes a Kryo-serialized WAL
  record, and exclusivity buys the same correctness without touching the format; binding up front reintroduces the
  `referenced ∧ provisional` overlap the classifier requires to be unreachable.

  **The abandoned upload, and why the claim is now taken late.** The **unary** gRPC upload is the one caller that
  creates its restoration task long before it runs: it must create it on the *first* chunk, because the response
  returns a task status on every chunk and the waiting-task registry is the only thing carrying upload state from
  one chunk to the next. It is **not a stream** — every chunk is an independent request/response ended by its own
  `onCompleted`, with continuity carried by the returned `fileId` rather than by a live connection — so there is
  no disconnect to react to, and an abandoned upload is indistinguishable from a slow client between chunks. The
  *streaming* variant has no such gap; it creates and submits in `onCompleted`, all at once.

  Allocating the folder when the task was *created* therefore charged every abandoned upload a directory nobody
  would ever write into, a consumed generation number, and — once the claim became exclusive — a name that stayed
  un-restorable for the life of the process. The first two are older than this work; the claim is what made the
  mismatch visible. `restoreCatalogTo` now takes a `CatalogFolderAllocator` rather than a `CatalogFolderId`, and
  `RestoreTask` calls it from `doRestore` — so nothing is reserved until the archive is complete and the restore
  actually starts. A task that never ran holds nothing, and its release hook finds an empty claim and does
  nothing. `EvitaTest#shouldReserveNothingUntilTheRestoreActuallyRuns` builds a restoration task, never submits
  it, and asserts both halves; reverted to eager allocation it reports `[testCatalog_restored_1]` for a folder
  that should not exist, and then a `ConcurrentCatalogMaterializationException` refusing the name.

  **This moves an error surface, which will look like a bug to someone who was not here.** Two concurrent
  restores of one name used to be refused at the *API call*, as a client error on the second caller. Now both
  uploads complete and the loser's *task* fails instead. Nothing asserted the early throw, and the correctness
  guarantee is unchanged — one restore per name — but the timing and the reporting channel both changed. The
  synchronous `restoreCatalog(name, size, stream)` caller also loses its uploaded bytes on a collision now: the
  refusal happens inside `doRestore`, where the archive is already open `DELETE_ON_CLOSE`, so a re-upload is
  required. That is the deliberate side of the same placement — allocating *before* opening the archive would
  spare those bytes but strand one temp `.zip` per refusal, and there is no sweep to collect them.

  **Two adjacent defects stay open, and neither belongs to #649.** Both live in `Scheduler`:

  - `purgeFinishedAndLongWaitingTasks` drops a task waiting on a precondition with a bare `it.remove()` and never
    fails it, so its future never completes. Nothing hangs off that future any more, but it is a silent skip of
    the kind the project's defensive-design rule forbids, and it is what would otherwise have been the natural
    hook for reclaiming the orphaned temp `.zip` of an abandoned upload. That leak is still open: every upload
    path sets `deleteAfterRestore`, which opens the archive `DELETE_ON_CLOSE`, so it is removed only if the task
    *runs* — and `FileManagementService` has no age-based sweep, since `createTempFile` files are not even the
    managed kind that `close()` deletes.
  - the two thresholds in that method are crossed: `waitingThreshold` is built from
    `FINISHED_TASKS_KEEP_INTERVAL_MILLIS` (5 min) and applied to waiting tasks, while `threshold` uses
    `WAITING_TASKS_KEEP_INTERVAL_MILLIS` (10 min) for the finished-task defense period. **Any upload slower than
    five minutes loses its task mid-flight** and the next chunk fails with `Task not found for file`. Verified
    rather than inferred: a parked task really is `WAITING_FOR_PRECONDITION`, because `registerWaitingTask`
    never calls `transitionToIssued` and `simplifiedState()` keys off a null `issued`. Raised as **#1415** —
    it is a live bug of its own and did not belong in a quiet fix buried here.
- **The generation sequences are reclaimed at the tombstone drain** — this was carried here as an open
  follow-up and is now closed. `SequenceService`'s maps are append-only, so the engine-scoped
  `catalogGenerationSequences` used to retain one `SequenceKey` and one `AtomicInteger` for **every distinct
  catalog name ever materialised**, for the life of the process. Nothing behaved wrongly; it was pure
  retention, and the reclamation is likewise pure bookkeeping.

  **The drain is the only place the decision can be made, and that is the whole finding.** Dropping a catalog
  does not free its name: the folder removal is *owed* rather than done, and a tombstone is a standing order to
  delete one specific directory. Retiring a counter while such an order is outstanding lets a recreated catalog
  draw the number the order names and — if the directory happened to be gone already — bind itself to a token
  something is still under instructions to destroy. `EngineTransactionManager#retireGenerationSequences` runs
  at the commit that discharges the last of a name's tombstones, and only when that same durable state carries
  no binding for the name and nothing is materialising it.

  **The documented precondition was weakened, deliberately.** `removeSequences`' JavaDoc demanded *no folder
  carrying the name's prefix on disk* as well; engine state cannot see litter, so a check that honoured it
  would need a storage scan per commit. It is not needed: `CatalogFolderAllocator.allocate` treats a directory
  it cannot create as a number to burn and draws the next, so a counter restarting underneath litter costs
  numbers rather than data. The **accepted trade** is that a name with as many surviving litter folders as
  `MAX_ALLOCATION_ATTEMPTS` (16) now fails allocation where a monotonic counter would have stepped over them —
  it needs a filesystem that refused sixteen deletes, and it is a bounded failure rather than a silent one.

  **Options rejected.** *Keying the check on the token's textual shape rather than the tombstone's recorded
  catalog name* — a rename is a pointer swap that leaves the folder where it is, so a tombstone can read
  `orders` while its token reads `products_3`, and only a prefix check would connect the two. **Rejected
  because** it would have to parse `<name>_<generation>` inside the engine, re-introducing exactly the layout
  coupling this whole line of work removed, to close a case the trigger cannot reach anyway: the drain
  nominates `orders`, never `products`, so the old name simply keeps its counter. That is retention this does
  not reclaim, not a counter retired unsafely. Worth revisiting only if a `CatalogFolderOperations` method can
  answer "did this token come from that name's series?" without the engine parsing anything. *Wiring the
  cleanup into the drop path* — **rejected because** the precondition is not met there at all; the folder
  deletion has not happened yet when the drop commits.

  **Not reclaimed, knowingly:** a name whose materialisation failed before any binding existed (allocate →
  create fails → claim released) never produces a tombstone, so nothing ever nominates it. Repeated *failed*
  restores under generated names still leak an entry each. The second candidate trigger is
  `releaseReservation`, and it was left alone rather than guessed at.

  When `CatalogGenerationPeak`s start being written, their removal belongs at this same decision point —
  retiring the counter while leaving its peak in persisted state would resurrect it on the next boot.

- **No supported way to ask which folder a catalog is in, and tests keep guessing.** `CatalogContract` exposes
  no `getCatalogFolderId()`, so a test that drives a real engine and then wants to look at the catalog's files
  has nothing to ask and resolves `storage/<catalogName>` instead — which has not existed since allocation
  started suffixing generations. This is not hypothetical: `CatalogHistoryHorizonRecoveryTest`, written on `dev`
  against the old model and met on rebase, did exactly that. It fails *silently and late* — listing an absent
  directory yields no files rather than an error, so a polling wait burns its whole budget and the assertion
  that finally fires describes the wrong thing entirely (sixty-two seconds to report that log rotation had
  misbehaved, about a folder it had never looked at). The test now finds the folder by scanning for the highest
  generation, through `EvitaTestSupport#catalogDirectory` — a shared resolver, because that test was not the
  only one guessing: `LongRunningEvitaTransactionalFunctionalTest` opened a write-ahead log against the same
  non-existent path, and nothing caught it because the long-running module is skipped by default, so no run
  ever compiled that call, let alone executed it. Note that direct-construction tests are *not* affected and
  remain correct with a bare name, which is precisely what makes the trap hard to see — the pattern looks safe
  everywhere until it meets a live engine.

  **"Expose the token on `CatalogContract`" — which this record previously called the real fix — does not
  compile.** `CatalogContract` is in `evita_api`, `CatalogFolderId` is in `evita_engine`, and `evita_engine`
  depends on `evita_api`, not the reverse; the accessor is a module cycle as written. Moving the token down into
  `evita_api` is technically clean (it imports only `Assert`, and the serializer writes it as a bare string, so
  no stored state depends on its package) but **rejected**: it publishes a storage-layout concept into the
  client-facing API jar to serve zero production callers — every engine path that needs the token already takes
  it from `CatalogFolderContext` — and puts it one `resolve()` from every external-API handler holding a
  `CatalogContract`, which is the surface the `getCatalogStoragePath()` removal cleared. A bare `String`
  accessor is **rejected** for deleting the single chokepoint that enforces single-segment-ness and blocks
  traversal. The accessor therefore stays engine-side, where it already exists: `Evita#getCatalogFolderContext()`
  → `folderIdFor(name)`, strict and throwing on an unbound name. Note also that the `getCatalogStoragePath()`
  removal does **not** argue against exposing a token — its reason was that the engine cannot honestly populate
  a *path*, and the engine can populate a token honestly for every bound catalog.

  What remains is the **offline** case, which no accessor covers: a snapshot copy under a different storage
  root, after the engine is closed. `EvitaTestSupport#catalogDirectory` serves it by scanning for the highest
  `<name>_<digits>`, which is latently wrong twice — a catalog that outlived a rename keeps a folder named after
  its *old* name, so the scan throws; and two folders can legitimately claim one name, so the tiebreak can pick
  the dead one. Reading `.catalogname` and refusing on ambiguity is the honest replacement, with the caveat that
  the marker's write is best-effort and swallowed, so a folder can be unlabelled.
  The way this defect *presented* is recorded as a separate follow-up in
  `2026-08-06-time-travel-disk-budget`: the wait that hid it gives up silently, so the failure named the
  wrong subsystem entirely.
- **The silent default-bootstrap path** at `getLastCatalogBootstrapWithAutomaticUpgrade` turns an empty
  folder into `CatalogBootstrap(0, 0, now, null)` and surfaces much later as "no schema found, the data
  are probably corrupted". It is what turned a one-line defect in this work into a lost session, and is
  worth tightening now that folders are allocated separately from being written.

  **A `holdsNoCatalogData` guard was added on this branch, and it does not close this.** It reads as though it
  does — the branch now returns the default only for a marker-only folder and otherwise throws
  `BootstrapFileNotFound` — which is why this needs stating rather than leaving to a reader of the diff. Two
  things defeat it. The legitimate case *never reaches this method*: `createNew` builds its own
  `CatalogBootstrap(0, 0, now, null)` inline, so the "brand-new catalog" caller the guard's comment describes
  does not exist. And the throw is *unreachable*: both loading constructors run `discoverStoragePrefix` first,
  which already throws on a folder holding files but no `.boot`. So the branch has exactly one live outcome and
  it is the silent one — the guard narrowed away the correct exit, not the defect. Of the six callers, **none**
  can legitimately meet an empty folder; `verifyDirectory`'s `mkdirs()` even manufactures one when a bound
  catalog's contents have been deleted. The fix is to collapse the else-branch to a single throw, keeping
  `holdsNoCatalogData` only to choose the *message* (allocated-but-never-written vs the data is gone).
  Downstream, a null `fileLocation` makes `CatalogOffsetIndexStoragePartPersistenceService` fabricate a
  `CatalogHeader` with a freshly minted random `catalogId` — an identity invented for data that does not exist,
  which is what makes the eventual failure blame the user's data for an engine bookkeeping error.
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

  **That fix missed one path, and this record read as an all-clear for a while as a result.** `f04f33109`
  covered create, rename and restore; **duplicate** was the fourth operator over the same bucket and was not
  touched, so duplicating onto a persisted-MISSING name kept the stale binding and stranded the copied data. It
  is fixed now, by the same one-line `withCatalogNoLongerMissing` the three siblings use.

  Reaching it needs **two boots**, which is why no test caught it: `MarkCatalogMissingMutation` is emitted from
  boot reconciliation, and in that same boot the name is still served by an `UnusableCatalog(MISSING)` stub, so
  applicability correctly refuses. From the next boot the runtime map is rebuilt from the active and inactive
  buckets only, so the name reads as free.

  Nothing is destroyed by it — the orphaned copy classifies UNCLAIMED, which is warn-only, not drained. The
  escalation is worse than deletion would be: recovery is *blocked*, because `isAdoptableCatalogName` refuses
  any bare name in `registeredCatalogNames` and that set includes `missingCatalogs`, so the one name an operator
  would reach for is precisely the one that fails. Activate the duplicate instead and `verifyDirectory`'s
  `mkdirs()` recreates the vanished folder empty, which makes it "reappear" at the next boot and drives a
  restore mutation against a live stub — **the engine then refuses to start.**

  **The real lesson is the shape, not the line.** This was the *fourth* instance of one defect class, found
  four times separately. The durable fix is a cross-bucket disjointness premise in `EngineState`'s canonical
  constructor, which would have caught all four at the source. It is deliberately **not** done here: it is a
  guard rather than a fix, and it needs a sweep for legitimate transient overlap first — a builder chain may
  well pass through a state where a name sits in two buckets before the next call removes it. Rejected outright:
  refusing the duplicate in `verifyApplicability`, because restore *must* be permitted onto a missing name, so
  that would add a fourth divergent rule over the same bucket rather than removing the divergence.
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
