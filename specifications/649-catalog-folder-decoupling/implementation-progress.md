# Implementation progress

Tracks §6 of `README.md`. Update as steps land; delete together with the plan when the ADR is written.

---

## Step 0 — open items answered ✅

See `step-0-findings.md`. Two answers changed later steps:

* **In-flight queries already complete today** — the drain is `executeWhenMethodIsNotRunning`, not
  `terminate()`. §3.7's table overstates the current damage; the real win is *session survival* plus
  the postpone window collapsing. It also surfaced a 5-second drain bound that constrains how the
  §3.7 tests must be written.
* **`terminate()` is non-idempotent and forbidden in replay** — so `replayCompletionState` (decision
  11) must be a pure re-derivation, and termination of the replaced catalog has to be driven by the
  tombstone drain rather than the completion lambda.
* **No test asserts `catalogId` stability across restore** — step 8 adds assertions rather than
  repairing any.

## Step 1 — folder indirection seam ✅ (verified)

**The seam is `CatalogFolderResolver`** (`evita_engine/.../core/engine/CatalogFolderResolver.java`), a
single-method interface answering `folderFor(catalogName)`. Step 1 wires
`CatalogFolderResolver.identity(storageDirectory)` — the historical `storageDirectory.resolve(name)`
mapping — so behaviour is unchanged. Step 4 replaces that one implementation with the engine-state
lookup and every call site follows automatically.

**Two rules the wiring enforces, and why:**

1. **The engine resolves; nothing below it re-resolves.** `Evita` owns the resolver and hands a
   concrete `Path` down through the factory SPI into the persistence service. Passing a *resolver*
   downward was rejected — a component that can re-resolve will eventually do so after a rename and
   silently read the wrong folder. Passing a resolved path makes the snapshot explicit.
2. **Every folder derivation is now visible at a call site.** The five static bootstrap readers in
   `DefaultCatalogPersistenceService` used to bury `storageDirectory.resolve(catalogName)` inside the
   helper; they now take the path as a parameter. Diagnostic/test callers still compute it from the
   name, but they do so *in the open*, which is what makes the remaining assumptions greppable.

### What changed

| area | change |
|---|---|
| new | `CatalogFolderResolver` + `identity(...)` factory |
| `Evita` | owns `catalogFolderResolver`; 3 placeholder sites use it |
| 8 operators | `Path storageDirectory` field → `CatalogFolderResolver folderResolver` |
| `EngineTransactionManager` | constructs operators with the resolver |
| `DefaultUpgradeExecutor` | gained the resolver; resolves per catalog |
| `CatalogPersistenceServiceFactory` | all four methods take `Path catalogStoragePath` |
| `Catalog` | 3 factory calls resolve through `evita.getCatalogFolderResolver()` |
| `DefaultCatalogPersistenceService` | 4 ctors, `runStorageProtocolUpgrade`, 5 static readers take the path |
| `RestoreTask` | holds the target folder instead of deriving it |
| `EvitaManagement` | resolves before building the restore task |

### Deliberately left alone

`pathForCatalog` still exists and is still called from exactly **two** places, both of which are
*allocation* rather than *resolution*:

* `DefaultCatalogPersistenceService#replaceWith` — computes the target folder for the rename dance.
  Step 7 deletes this method's body outright, so converting it now would be wasted work.
* `duplicateCatalog` — allocates the target folder. Step 5 replaces this with the real allocator
  (burn-and-skip + `.provisional`).

Both are named here so they are not mistaken for missed sites. Everything that *resolves an existing
catalog's folder* now goes through the resolver.

## Step 1b — the engine stopped naming directories ✅ (option B)

Step 1 put a `Path`-valued resolver in the engine. Review flagged that `evita_store_server` is meant to
own filesystem layout, and that the seam — while not the *source* of the leak — would freeze it into a
new SPI contract at the one moment it was cheap to remove.

**The evidence both ways.** The seam did not create the leak: at HEAD the engine already built catalog
paths at 13 sites, 19 files under `evita_engine/src/main` imported `java.nio.file.Path`, and
`UnusableCatalog` carried a folder `Path`. But the engine barely *used* those paths — three genuine
filesystem operations in the whole module, everything else pass-through or message text. Cheap to move.

**What was adopted.** `EngineState` binds a catalog to an opaque `CatalogFolderId`; only the storage
layer knows a token denotes a directory. The full proposal, the architectural review that corrected it,
and the settled answers are in `folder-identity-layering-proposal.md`.

**The invariant, and how to check it holds:** exactly one place outside the storage layer's internals
turns a token into a path — `DefaultEnginePersistenceService#pathOf`, which is `private`. If a second
appears, the boundary has eroded.

**What the engine may still hold.** The rule is *not* "no `Path` in the engine" — it is "no path
**derived from a catalog's identity**". The configured storage root, a backup archive handed to a
restore, an export target: all legitimate. This is stated in `CatalogFolderId`'s JavaDoc because the
next contributor reading a summary of this work will otherwise "fix" one of them.

### What changed beyond step 1

| area | change |
|---|---|
| new | `CatalogFolderId` — validated token; rejects blank, `/`, `\`, `..` at construction |
| new | `CatalogFolderOperations` — `exists` / `drop` / `size`, on `EnginePersistenceService` |
| new | `CatalogFolderContext` — resolver + operations + storage root; builds placeholders |
| `CatalogFolderResolver` | `folderIdFor`; `identity()` no longer takes a `Path` |
| `UnusableCatalog` | token + root + ops handle; both filesystem calls delegated |
| 3 exceptions | token + storage root instead of a resolved path |
| storage SPI | four methods take `CatalogFolderId`; the join happens inside the store |
| GraphQL / REST | `catalogStoragePath` removed outright — breaking change |

### Two departures from the reviewed proposal

* **`CatalogFolderContext` is new.** `UnusableCatalog` needs three collaborators and 13 sites build it;
  threading three fields through every operator was noise. The proposal left *how* the handle arrives
  open ("supplied by the operators"). Bundling also gives one type to inspect when asking what layout
  knowledge the engine retains.
* **`allocateCatalogFolder` was not added**, though §5.4 lists it. It has no caller until step 5's
  burn-and-skip allocator. Adding it now would be unused scaffolding of exactly the kind removed from
  `RestoreTask` in the same session.

### One reversal worth knowing about

`storageOptions` was removed from the restore path earlier in step 1 as provably dead — it was, while
the engine passed a resolved `Path` down. Option B makes the *store* perform the join, so
`restoreCatalogTo` carries `StorageOptions` again, now genuinely used. Neither decision was wrong; the
architecture change moved the join and the parameter followed it.

### `CatalogFolderResolver.identity(...)` — resolved by option B

Under B the identity mapping is `CatalogFolderId::new`: it holds nothing and resolves nothing. **Step 4
deletes it outright** — a pure deletion, with no deprecation window to argue about and no migration
target needed, because there is no state or behaviour left to migrate off.

`@Deprecated` was considered and rejected while the method still carried a `Path`: it has never
shipped, and all its uses were correct, so the annotation would have added build warnings against
legitimate code. The born-deprecated precedent in `.claude/rules/deprecation-policy.md` covers the Kryo
`*Serializer_20XX_Y` shims, which are discouraged-but-necessary and live for years — a different state
wearing the same annotation.

It has **one** production caller (`Evita.java:431` — the line step 4 replaces) and **14** test callers.
Nothing keeps it alive after step 4: the engine state has its own persistence service and serializer
ladder, so no bootstrap path needs to resolve a catalog folder before the name-to-folder map is
readable.

**Step 4 deletes it.** The 14 operator-test call sites build the mapping inline or move to a fixture in
`evita_functional_tests`. Deciding this now matters because the alternative is silent: removing the
single production caller does *not* remove the method, and if it lingers to serve the tests, a static
factory stays on an exported package (`module-info.java:41`) purely for test convenience — which is
exactly how the pre-#649 assumption gets wired back in by someone who was not here.

**Not `@Deprecated`.** The annotation gives *existing* callers a migration window; this has never
shipped, and all 15 current uses are correct today, so it would only add 15 build warnings against
legitimate code. The born-deprecated precedent in `.claude/rules/deprecation-policy.md` covers the Kryo
`*Serializer_20XX_Y` shims, which are discouraged-but-necessary and live for years — a different state
wearing the same annotation. The JavaDoc instead names the removal point and forbids new production use.

This holds only while the whole line of work merges as one PR. If steps 1–3 merge and 2026.2 cuts before
step 4, `identity` ships on an exported package and `@Deprecated(since = "2026.2")` becomes correct.

### Verification

* Full reactor `mvn install -DskipTests` — `BUILD SUCCESS`.
* Full `unitAndFunctional` suite — **20886 tests**, 2 failures + 3 errors, of which exactly **one was
  a real regression** (below). The other four were re-run in isolation and all pass:
  `CdcCallbackDispatcherTest`, `EvitaClientReadWriteTest`, `SharedRgeiSoakTest` → 81 tests, 0
  failures. The timings show why they fail under the full suite and not alone — `SharedRgeiSoakTest`
  20.7 s isolated vs 258.5 s in-suite (12×), `EvitaClientReadWriteTest` 45.1 s vs 273.9 s (6×): CPU
  starvation across parallel forks, hitting a 100 s commit wait and a 15 s gRPC deadline
  respectively. `ExportS3ServiceTest` needs Docker, which this machine does not have.
* One equivalence worth recording, because it was the only substitution that could have been
  silently wrong: the five static bootstrap readers previously derived their folder from
  `bootstrapStorageSettings`, and now receive `catalogStoragePath`, which comes from
  `storageSettings`. `StorageSettings#modifyForBootstrapFile` rebuilds `StorageOptions` altering
  only `outputBufferSize`, `computeCRC32` and `compress` — `storageDirectory` is carried through
  unchanged, so the two resolve identically.

### The one real regression, and what it changed

`EngineTransactionManagerForwardReplayTest` mocks `Evita` and stubbed only `getConfiguration()`, so
the new `getCatalogFolderResolver()` returned `null` and every operator captured a null resolver —
surfacing as an NPE deep inside an unrelated operator during WAL replay, far from the cause.

Fixed by stubbing the mock, **and** by making `EngineTransactionManager` reject a null resolver at
construction. The second half matters more than the first: operators capture the resolver at
construction but only dereference it when a mutation runs, so without the check any future wiring
gap reappears as a delayed NPE at boot-time replay rather than at the point the wiring is wrong.

### A suite failure that was not ours, and how it was proven

Two converter classes (`RemoveParentMutationConverterTest`, `SetEntityScopeMutationConverterTest`)
failed with `NoClassDefFoundError` on `SerializableCreator` — a class in `evita_api`, untouched by
this work. Cause: an Eclipse JDT language server had the project imported via m2e and deleted
`evita_api/target/classes` while the reactor test JVM had that directory on its classpath. Maven's
own log shows one `compiler:compile @ evita_api` and zero "Changes detected" lines, so Maven did not
do it; a second annotation from the same module (`ReflectedReference$InheritableBoolean`) failed the
same way. All four converter classes pass in isolation — 9 tests, 0 failures.

Worth carrying forward: the *first* attribution of these failures to CPU starvation was wrong. The
starvation pattern is real for `SharedRgeiSoakTest` and `EvitaClientReadWriteTest`, but it was doing
duty as a catch-all. A shifting classpath and a starved CPU both produce "passes alone, fails in
suite", and only the deletion timestamps separated them.

## What must survive into the eventual ADR

Per `.claude/rules/adr.md` this campaign gets **one** record at the end, and the commit that adds it
deletes this folder. The reasoning that exists *only here* and would die with it:

* `folder-identity-layering-proposal.md` §10 — why Option B (opaque token) beat Option A (resolver
  seam), and why the token's textual form must stay unconstrained. Someone will re-propose
  `(name, generation)` otherwise.
* The finding that the seam did not *create* the engine's path leak (13 pre-existing construction
  sites) but would have *institutionalised* it into a new SPI contract.

## Step 2 — done

The storage prefix is now read from the folder's own `*.boot` file rather than derived from the catalog
name. `DefaultCatalogPersistenceService#discoverStoragePrefix` is the single point of truth; the service
holds the result in a `storagePrefix` field set by all three constructors, and the six static bootstrap
readers discover it themselves from the `catalogStoragePath` they already receive, so no signature changed.

Discovery lives in the storage layer on purpose. Step 1's boundary rule forbids the engine from knowing
how files inside a folder are named, so supplying the prefix from outside would have re-opened the seam
step 1 closed.

The `newCatalogName` parameter threaded through `prepareBootstrap` → `writeCatalogBootstrap` →
`getOrCreateNewBootstrapTempWriteHandle` → `createBootstrapTempWriteHandle` was always a storage prefix
wearing the wrong name, and is renamed accordingly. One root fed it `catalogHeader.catalogName()` — the
header's idea of the catalog name deciding a *file* name; it now feeds from the discovered prefix.

### Ordering constraint this creates: step 5 must not land before step 7

`replaceWith`'s renaming block and the static `getFileNameWithCatalogRename` still compare and build file
names from the catalog name, not the prefix. That is correct for as long as the two are equal, which holds
throughout steps 2–4, and step 7 deletes both. But step 5 is what first makes a prefix diverge from a
name, and on that day these two sites stop matching the files they are meant to match — silently, by
renaming nothing rather than by failing. **If step 7 is going to be deferred past step 5, these two sites
must be converted to `this.storagePrefix` first.** Tracked as a checkbox in README §7, which is the
list to re-read before the branch merges.

### Not behaviour-preserving, deliberately

`getCatalogDataStoreFileNamePattern` now quotes both the prefix and the suffix. Spec §6 calls steps 2–3
behaviour-preserving and this one line is not: it narrows what the pattern matches. It is a correctness
fix that step 2 makes load-bearing, and it is called out in the commit message rather than riding along
silently.

`ObsoleteFileMaintainer` needed no change at all despite being listed in §6 — it filters by suffix
(`name.endsWith(CATALOG_FILE_SUFFIX)`) and never derives a name from the catalog.

### Verification

`DefaultCatalogPersistenceServiceTest` grew a `StoragePrefixDiscovery` nested class covering the
decoupling itself (a folder whose bootstrap no longer carries the catalog name still loads), the
corruption guard (files but no bootstrap file throws rather than falling back), and the regex-wildcard
case. 16 tests in that class, plus 94 across the eight storage/WAL classes — all green.

## Step 3 — backup/restore normalisation ✅ (commit `ac7900df3`)

Backup archives now carry the canonical suffix-free shape by construction, and restore stopped needing
to be told what prefix the archive was written with.

### What changed

A new package-private `CatalogFileNaming.canonicalizeTo(fileName, targetPrefix)` in
`io.evitadb.store.catalog.task` is the single point at which an archive entry's name is decided.
`BackupTask`, `FullBackupTask` and `RestoreTask` all route through it.

### Improved on the spec, deliberately

§6 proposed giving `RestoreTask` a `*.boot` discovery *fallback* for the source prefix. The rewrite
instead became prefix-independent outright: both index parsers scan digits backwards from the suffix,
so the incoming name is decomposed by its suffix and trailing index alone. That removed the tree's last
piece of length-based filename arithmetic (`RestoreTask:89` used to slice by the top-level directory's
length, which mis-slices for an archive taken from a renamed catalog) and made the discovery fallback
unnecessary rather than merely correct.

### Verification

New `CatalogFileNamingTest` (4 tests), plus a full clean `unitAndFunctional` suite: **20893 tests, 1
failure, 1 error**, both environmental — `CdcCallbackDispatcherTest` (flaky by construction, see below)
and `ExportS3ServiceTest` (no Docker).

## Step 4 — engine state schema + sequences ✅

`EngineState` now carries the catalog-to-folder mapping, and the engine has the sequence machinery that
folder generations will be drawn from. Nothing allocates a suffixed folder yet — that is step 5 — so
every binding this step produces is still identity-shaped and behaviour is unchanged.

### What changed

**Three new records** in `io.evitadb.spi.store.engine.model` — `CatalogFolderBinding` (name → folder),
`RetiredFolder` (tombstone, carrying the catalog name so the drain never parses it back out of the
token) and `CatalogGenerationPeak`. They become three new components on `EngineState`, each a sorted
array validated by the compact constructor. Bindings and peaks order by catalog name; tombstones order
by folder token, because one catalog can have several folders awaiting deletion.

**A new serializer rung.** `EngineState.serialVersionUID` moved to `3948172605583194127L`;
`EngineStateSerializer_2026_2` reads the previous shape and is registered under
`7261559824913670482L`. That UID shipped in v2026.2.0–v2026.2.3, verified with
`git show <tag>:…/EngineState.java`, so the rung is mandatory rather than a precaution — an in-place
extension would have made those installations unbootable.

**The reader synthesises identity bindings, not empty ones.** Under the old format a catalog's folder
*was* its name, so that is the faithful translation of what the absent map meant. It is written down
exactly once, in the eight-argument `EngineState` convenience constructor, which all three
backward-compatible rungs and `createNewEngineState` delegate to. The consequence is that a lookup is
total from the first moment a state exists, which is what let the resolver be made strict.

**Two questions, two methods.** `CatalogFolderResolver` now answers `@Nullable boundFolderIdFor`, and
`CatalogFolderContext` splits the policy: `folderIdFor` throws on an unbound name, `folderIdForBinding`
returns the existing binding or, for a catalog the engine state does not know yet, the folder it is to
occupy. **`folderIdForBinding` is the single seam step 5's allocator drops into** — burning a
generation, guarding with `Files.exists`, writing the `.provisional` marker — which is why the decision
lives there rather than at each call site.

Making the resolver strict *first*, before looking for callers, is what surfaced all five sites asking
that second question. Three came out of a targeted run and two out of reading every
`createUnusableCatalog` site afterwards:

| site | what is unbound |
|---|---|
| `CreateCatalogMutationOperator` | the `BEING_CREATED` placeholder, staged in the transition phase |
| `Catalog.java:596` | the persistence service of a brand-new catalog, in the work phase |
| `DuplicateCatalogMutationOperator` | the duplicate's target name |
| `EvitaManagement:312` | the folder `RestoreTask` unpacks a backup into |
| `RestoreCatalogSchemaMutationOperator:86` | restore and auto-discovery only — see below |

That last row is the interesting one: the operator serves three paths, and the third —
flapping recovery, `MISSING` → `INACTIVE` — starts from a catalog the state *does* know and must land
back in the folder it left. `folderIdForBinding` covers both in one call precisely because it returns
an existing binding when there is one.

The create path is worth noting because the two sites are ordered: the transition phase establishes the
binding, and the work phase then reads it back rather than deciding again. Under step 5 that is exactly
what is wanted — the folder is allocated once, before anything is written into it.

**Sequences.** `SequenceType.CATALOG_GENERATION`, `SequenceService#removeSequences(catalog)` (the maps
were append-only, so create/drop churn leaked an entry per name forever), and an engine-scoped
`SequenceService` on `Evita` seeded from the persisted peaks.

### Constructor reordering in `Evita`

The boot stubs at `Evita:512–527` resolve their folder through the resolver, which now reads the engine
state — so the persisted snapshot is published with an empty catalog map *before* the stub loop and
immediately superseded once the stubs exist. Only the name-to-folder mapping is read in between. The
one observable this changes is `emitEvitaStatistics`'s `engineState.get() != null` guard, and both of
its schedulers are wired later in the constructor than the full publish, so nothing can observe the
intermediate state.

### A latent bug this made load-bearing

`DefaultEnginePersistenceService:237` (the storage-protocol migration) rebuilt the engine state by
enumerating its fields, and had silently dropped `missingCatalogs` ever since that bucket was added.
Harmless while the dropped bucket was reconstructible; not harmless for folder bindings, which cannot
be reconstructed from anything once lost. Rewritten to carry everything forward through
`EngineState.builder(existing)`.

### Deliberately not in step 4

- **Tombstone staging API** (`withRetiredFolder` / `withoutRetiredFolder`). When a folder enters and
  leaves the list is decided by step 5's boot classification; writing the API now means guessing that
  shape and reshaping it later. The *format* field is here, which is the part that is expensive to add
  late.
- **An explicit-token `withCatalog(catalog, folderId)` overload.** Step 4's single-argument form
  preserves an existing binding and creates an identity one for a name it has never seen, which is
  true today. Step 5 adds the overload and wires it at the creation sites; that is also the moment the
  identity default in `ExpandedEngineState#bindingsIncluding` stops being correct and must go.
- **The disk-scan half of the generation seed.** `max(persisted peak, highest <name>_N on disk)` — the
  scan arrives with allocation in step 5. Until then no suffixed folder exists to find, so seeding from
  the peaks alone is complete, and `seedCatalogGenerationSequences` says so.
- **Sequence retirement wiring.** The removal API exists and is tested; what triggers it is the
  tombstone drain, which does not exist yet.

### Settled: the generation peaks stay, but they are hygiene rather than the liveness guarantee

Johnny's answer to the open question: the peak is a *fuse*. A rename that fails mid-flight can leave a
folder the filesystem then refuses to clear — or reports as cleared while still refusing to recreate the
name. Retrying the same rename draws the same number, hits the same name, and fails identically forever.

That corrected the question I had been asking. My analysis tested a *safety* property — can the disk scan
see the folder — when the concern is *liveness*: can the operation ever succeed again. The two come apart
exactly here, because `Files.exists` answers `false` both for "absent" and for "cannot determine"
(it reports `AccessDeniedException` as absence, on POSIX as much as on Windows). So the scan can report a
name free that creation will reject, and "the scan sees it" is not the reliable term I treated it as.

**But the peak is still not what keeps the operation live**, for two independent reasons:

1. Allocation has to treat a failed directory creation as "burn this number, draw the next" regardless —
   precisely because the existence pre-check cannot be trusted, the create call is itself the decision
   point. With that loop, Johnny's scenario costs one wasted probe per restart and then completes, peak or
   no peak. The permanent wedge needs an allocator that draws once and gives up, which is the real defect.
2. `EngineStateSerializer_2026_2` substitutes `NO_GENERATION_PEAKS` for every legacy payload, so the first
   boot of every upgraded installation has zero peaks *by construction*. Anything that depended on peaks
   for liveness would ship a guaranteed-vulnerable window in the field.

Decision: **keep them**, on the narrower justification — a peak stops a known-bad name from being drawn
again after every restart, and it survives a hard kill where no cleanup path ran to leave a tombstone. The
scan covers the disjoint case (a folder created by an attempt that died before persisting anything).
Neither term subsumes the other; `CatalogGenerationPeak`'s JavaDoc now says exactly that, where it
previously called the scan the primary authority.

### Verification

`EngineStateTest` grew a `CatalogFolderBindings` nested class (6 tests: strict-vs-absent lookup,
identity synthesis over active ∪ inactive ∪ missing, rebinding an already-bound name — which the shared
`insertRecordIntoOrderedArray` helper would *not* do, since it inserts only when absent — ordering, and
survival across a builder copy). `EngineStateSerializerTest` gained the round trip and the
old-bytes-to-identity-bindings test. New `SequenceServiceTest` (4 tests) covers fast-forward-only
seeding and the removal API.

## Step 5 — preparation landed, classification not started

### Gate 1 discharged, and it was narrower than the gate claimed

`replaceWith`'s rename block now matches on `this.storagePrefix` at all three sites (the `listFiles`
filter and the two `getCatalog*FileName` comparisons). Verified by `EvitaReplacementFunctionalTest`
(1 test, `-P longRunning` — the module's `skipTests` sits in the plugin `<configuration>`, so
`-DskipTests=false` does *not* override it and silently reports a vacuous pass) plus `EvitaTest` and
`DefaultCatalogPersistenceServiceTest`, 106 green.

The gate also named `getFileNameWithCatalogRename`; it needs no change, and there are two independent
copies (duplicate path, restore path). Both write into a folder they just created, so naming those
files after the target catalog *chooses* the prefix rather than assuming it. Recorded in the gate itself.

### Three spec defects found while reading step 5's inputs

1. **§3.2's allocation pseudocode had the exact bug the new §7 gate warns about** — `do { … } while
   (Files.exists(candidate))` skips *visible* folders and then creates unguarded. Replaced with
   `Files.createDirectory` as an atomic test-and-set under a bounded retry, which also removes the
   check-then-act race the original had.
2. **§3.2 and §5.3 contradicted each other on adoption.** §3.2: allocate generation 1 and rename
   `products` → `products_1`. §5.3: keep the bare name, counter not advanced. Reconciled toward §3.2
   (§5.3's version also left a *referenced* folder permanently suffix-free); §5.3's justification for the
   `Files.exists` guard went with it, since `createDirectory` handles the collision atomically.
3. **§3.5's `.provisional` removal was ordered only as "last step"**, which leaves `referenced` ∧
   `provisional` reachable — a create whose binding committed with the marker removal still pending. The
   table is first-match, so that folder matches `referenced` and gets **loaded while incomplete**. Pinned
   the removal ahead of the binding commit, which makes the overlap unreachable and turns a crash in the
   window into an `unclaimed` folder: litter, warned about, not deleted.

### Landed: classification and allocation, both as pure units

`CatalogFolderState` / `CatalogFolderClassification` / `CatalogFolderClassifier` and
`CatalogFolderAllocator`, all in `io.evitadb.store.engine`, plus `PROVISIONAL_FLAG` and
`CATALOG_NAME_FLAG` beside the existing `RESTORE_FLAG` on the `CatalogPersistenceService` SPI.

Both are deliberately **pure and free-standing** rather than methods on the persistence service.
`computeCatalogInventoryDivergence` is `private static` and reachable only through a constructor, which
is what made the mandated test-first order impossible; a function of (storage directory, engine state)
is testable directly. The allocator takes an `IntSupplier` rather than the engine's `SequenceService`,
which keeps the storage layer free of a dependency on engine internals.

Decisions worth keeping:

- **Evaluation order is not the table's declaration order.** `REFERENCED` is checked first — a bound
  folder loads whatever else it holds, because the binding is the authority (Invariant A) and deleting a
  bound folder is data loss by definition. Then the two rows carrying positive evidence of our ownership.
  Then *no bootstrap file* → `JUNK`, checked **before** the suffix: both rows warn-and-leave, so the
  choice is purely about the advice, and telling someone to rename a bootstrap-less folder for adoption
  would be wrong. Only then does the suffix split `FOREIGN` from `UNCLAIMED`.
- **An unreadable directory classifies as `UNCLAIMED`.** "Cannot determine" resolves to the
  non-destructive row rather than to a guess, and boot does not fail over one bad folder.
- **`FOREIGN` carries a null catalog name on purpose.** The name must come from the bootstrap header at
  adoption time; taking it from the directory name is the hole that lets an import shadow a live catalog.
- **The classifier never calls `Files.exists`.** One `newDirectoryStream` pass answers both questions it
  has, so a permissions change cannot be silently misread as absence.
- **`allocate` writes `.provisional` itself**, so "everything we allocate is marked from the instant it
  exists" is a single-place guarantee rather than a convention each caller has to remember.

### Wiring the classifier into boot changes two behaviours

`computeCatalogInventoryDivergence` now looks a registered catalog up through its **binding** instead of
assuming the folder carries its name, and puts every unreferenced folder through the classifier instead
of registering it. Two consequences that are not obvious from the diff:

1. **An unreferenced folder with a `_<digits>` suffix is no longer adopted.** It classifies as
   `UNCLAIMED` and is warned about. That is the suffix-free discovery rule working as designed, but it
   does mean a catalog legitimately named `orders_2024`, appearing on disk while the engine was down,
   is now reported rather than registered. The warning names the fix (rename it without the suffix).
   The window is narrow because `createNewEngineState` — the no-engine-state-at-all path — still takes
   every directory verbatim, so booting fresh over existing data adopts everything as before.
2. **A folder holding no `*.boot` is no longer registered as a catalog.** Previously *every* unknown
   directory became one, so a stray folder turned into a catalog the engine claimed to own. It is now
   `JUNK`: warned about, left alone.

Four existing tests failed on this, all for the same reason: their fixtures stand a discovered catalog up
as a **bare empty directory**, which is the old "every directory is a catalog" assumption written into a
test. They now create a folder holding a bootstrap file, via a named helper that says why. The `d`
fixtures stay bare on purpose — a *reappeared* catalog is found through its binding, never through
discovery, and leaving them bare keeps that distinction visible.

Fixing a fixture silently deletes the coverage of the rule that broke it, so two tests were added at the
divergence level asserting the new behaviour directly: a bootstrap-less folder and a suffixed folder are
each neither adopted **nor removed**, contents intact.

`createNewEngineState` was deliberately left alone. It still lists directories verbatim and registers
them as **active** (divergence registers as *inactive* — a pre-existing inconsistency). Routing it
through the classifier would change the upgrade path for installations whose engine state is absent,
which wants its own step and its own tests.

### Landed: the drain, scoped to `PROVISIONAL` only

`CatalogFolderCleaner` removes folders an operation abandoned, and boot now classifies **once** and uses the
verdicts twice — the drain is a side effect and had to stay out of `computeCatalogInventoryDivergence`,
whose whole contract is that it is a pure value.

**`RETIRED` is deliberately not drained yet.** It is equally expendable, but deleting a tombstoned folder
without also dropping its tombstone from the engine state leaks that tombstone *permanently*: the folder is
gone, so the classifier never reports it again, and the entry accumulates in persisted state on every drop
and replace for the life of the installation. Dropping it needs the engine mutation path, which does not
exist at the point boot classification runs — so both halves land together with the operators that produce
tombstones. `PROVISIONAL` needs none of that: unreferenced, untracked, its removal updates nothing.

The drain consumes `CatalogFolderState#isDeletable()` rather than re-deriving the policy in its own switch,
guarded twice — a static initialiser refusing a non-deletable state in `DRAINED_STATES`, and a premise check
on the last line before the delete. The parametrised test walks all six enum values and asserts a
non-deletable folder is never removed, which pins the coupling in a way per-row tests cannot.

### `FileUtils.deleteDirectory` followed symbolic links — fixed, in its own commit

It branched on `it.toFile().isDirectory()`, and `File#isDirectory()` **resolves links**. So a symlink to a
directory was recursed into and its *contents* deleted, outside the tree the caller pointed at entirely.

The behaviour is old — `git log -L` puts that line at `63c659c86` (2023-03-24), and #649 never touched
`FileUtils.java`. It surfaced now only because the drain is the first code to point a recursive delete at a
folder chosen by *classification* rather than by a caller who already knows what it is.

**Johnny's ruling: symlinks are not expected in the data folder — so the helper should enforce that rather
than assume it.** An unexpected link is an anomaly, and letting an anomaly steer a recursive delete outside
its target is the worst available response to one. Landed as a separate commit, ahead of the drain, because
it changes deletion semantics for catalog drop, catalog replace and the `2025_1` migration — blast radius
with nothing to do with #649.

The implementation is now `Files.walkFileTree` with no `FOLLOW_LINKS`, which hands a link to `visitFile` so
deleting unlinks it and never touches the target. The existence guard uses `LinkOption.NOFOLLOW_LINKS` too,
so a dangling link is still cleaned up rather than silently skipped. `CatalogFolderCleaner` therefore just
calls the shared helper — its private link-safe walk was duplication once the shared one was safe.

**Verified by counterfactual, not by assumption.** Adding `FileVisitOption.FOLLOW_LINKS` back makes the
containment test fail on exactly the right assertion — *"Data outside the storage directory must never be
touched"* — proving both that the guard works and that the hazard was real rather than theoretical.

The first attempt at that counterfactual **appeared to pass, and was worthless**: swapping in
`FileUtils.deleteDirectory` directly failed to compile (it throws `UnexpectedIOException`, a
`RuntimeException`, so the surrounding `catch (IOException)` became unreachable), so the test ran against the
previously installed jar. A green counterfactual means nothing until the build that produced it is checked —
`-q` on the install step is what hid it.

### Verification

Full suite: **20937 tests, 1 failure, 1 error, 37 skipped** in the functional module, against 20909 / 1
failure / 4 errors at step 4. The two survivors are the known pre-existing ones — `CdcCallbackDispatcherTest`
(JVM-global thread count, still unruled-on) and `ExportS3ServiceTest` (no Docker). Step 4's other three
errors were `EvitaSessionServiceFunctionalTest` gRPC timeouts under machine load and are gone.

The +28 count delta was reconciled per container rather than assumed: 26 are the new tests (15 classifier,
9 allocator, 2 divergence), and the remaining 2 are `Forced resolution overrides (DebugMode …)` 6→7 and
`Sort index first-touch cost …` appearing with 1 — both sort-index performance tests unrelated to this
work, reflecting how loaded the machine was during the step 4 run.

After the drain and the `FileUtils` fix: **20951 tests, 2 failures, 1 error, 39 skipped**. The delta is
exactly +14 (12 cleaner, 2 `FileUtils`) and +2 skips (the parametrised rows declining the two deletable
states). The extra failure is `SortIndexRankScalingTest#shouldNotScaleFirstTouchWithDistinctValueCount`,
which is **environmental**: it asserts a wall-clock *ratio* (measured 26.9x against a 3.0x limit), passed in
the immediately preceding full suite at 83.06 s, and passes in isolation in **5.83 s** against 53.80 s under
suite load. The `SortIndex` changes this branch carries are `422c3bda0` / `596154931`, both older than
today's work and green in the previous suite.

That makes **two** load-sensitive timing tests on this branch — this one and `CdcCallbackDispatcherTest`.
Neither has been ruled on; both will keep producing red suites on a busy machine.

### Still open in step 5

The classifier **is** wired into boot (see above); the allocator is not — no production path calls it
yet, so nothing writes `.provisional` and nothing allocates a suffixed folder.

Remaining: the allocator used by the create/restore/duplicate paths with `clearProvisionalMarker`
sequenced ahead of the binding commit — those two must land **together**, since writing markers without
clearing them leaves every fresh folder wearing one; the tombstone drain, for which the classifier
already reports `PROVISIONAL` and `RETIRED` but boot only logs them; foreign/legacy adoption with the
boot-time rename; and the `.catalogname` marker.

`CatalogFolderState#isDeletable` has no consumer until the drain lands — that is the flag the drain is
built around, and it is deliberately the only thing standing between a classification and an `rm`.

### The three §0 open items do not gate step 5

Despite the step-0 line saying they gate steps 5 and 7, all three land later: `terminate()` drain is
§3.7/step 9, `catalogId` stability across backup/restore is §3.1.1/step 8, and `doReplaceCatalogInternal`
idempotency is §5.1/step 7. Deferred rather than answered now.

## Steps 6–10 — not started

### Found before starting step 2: the prefix goes into a regex unescaped

`CatalogPersistenceService#getCatalogDataStoreFileNamePattern` (:161) builds
`Pattern.compile(catalogName + "_(\\d+)" + CATALOG_FILE_SUFFIX)` with no `Pattern.quote` — and there
is no `Pattern.quote` call anywhere in `evita_store` or `evita_engine`. Catalog names legally contain
`.` (`ClassifierUtils.SUPPORTED_FORMAT_PATTERN` allows `[\p{Alnum}_.\-~]`), so `my.catalog` yields a
pattern whose `.` matches any character: `myXcatalog_1.cat` matches too.

Harmless today only because a catalog owns its folder exclusively, so nothing is present to
false-match. **Step 2 removes that protection** — the prefix starts coming from disk rather than from
a validated name, and after step 7 a folder legitimately holds files under both an old and a new
prefix. Quote the prefix when threading it through, and cover it with a test using a dotted catalog
name.

### Step 2 is far smaller than the raw call-site count suggests

A first count said ~100 sites. That over-states it, because **the WAL half is already abstracted**.
`DefaultCatalogPersistenceService` holds `IntFunction<String> walFileNameProvider` (:280), built in
exactly three places — the three public constructors (:1194, :1389, :1531) — each as
`index -> getWalFileName(catalogName, index)`. Everything downstream consumes the *provider*, never
the catalog name: `AbstractMutationLog` (20 sites), `LogFileRecordReference` (10), the WAL suppliers
(12). So WAL naming becomes prefix-derived by changing **three lambdas**, and roughly 40 apparent
sites need no edit at all.

What genuinely needs threading is the bootstrap/data-file half inside
`DefaultCatalogPersistenceService`: `getCatalogBootstrapFileName` (~14) and
`getCatalogDataStoreFileName` / `…Pattern` (~10). Two of those are already step 7's territory rather
than step 2's — `replaceWith`'s renaming block (:2414–2417) and the private static
`getFileNameWithCatalogRename` (:1145) exist only to rewrite file names during a physical replace,
which step 7 deletes. Prefer following the `walFileNameProvider` precedent for the rest: a provider
resolved once per service instance beats threading a prefix string through every call site.
