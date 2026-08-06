# Decoupling the on-disk folder from the catalog name (#649)

**Status:** analysis / design proposal — no code written yet.
**Issue:** https://github.com/FgForrest/evitaDB/issues/649
**Branch:** `649-cannot-replace-catalog-through-grpc`

---

## 1. What the code does today

### 1.1 Identity chain

The catalog name is the primary key of *everything* on disk:

| Artifact | Derived from | Site |
|---|---|---|
| catalog folder | `storageDirectory.resolve(catalogName)` | `DefaultCatalogPersistenceService#pathForCatalog` (:507) |
| bootstrap file | `catalogName + ".boot"` | `CatalogPersistenceService#getCatalogBootstrapFileName` (:141) |
| catalog data file | `catalogName + "_" + idx + ".catalog"` | `#getCatalogDataStoreFileName` (:149) |
| WAL files | `catalogName + "_" + idx + ".wal"` | `#getWalFileName` (:237) |
| persisted header | `CatalogHeader#catalogName` | written by `writeCatalogHeader` |
| persisted schema | `CatalogSchemaStoragePart` | name + all naming-convention variants |

Entity collection files are the sole exception — `camelCase(entityType)-<entityTypePrimaryKey>_<idx>.collection`
already carries no catalog name. That is the precedent this whole proposal generalises.

### 1.2 How `replaceWith` works now

`DefaultCatalogPersistenceService#replaceWith` (:2322–2451) performs, in order:

1. append schema storage part with the new name, write a new catalog header, record a bootstrap record
2. `this.close()` — release all file handles
3. rename `A.boot` → `B.boot`, `A_0.catalog` → `B_0.catalog` **inside** the source folder
4. rename target folder `B` → `B_renamed`
5. rename source folder `A` → `B`
6. `FileUtils.deleteDirectory(B_renamed)`
7. on any `RuntimeException` after step 4: rename `B_renamed` back to `B` and rethrow

### 1.3 Why it is unreliable

* **Steps 3–6 are five non-atomic filesystem mutations with no durable record of how far they got.**
  A crash between 4 and 5 leaves *no* folder named `B` — the catalog vanishes. A crash between
  5 and 6 leaves `B` and `B_renamed`, and the next boot auto-discovers `B_renamed` as a catalog
  (`computeCatalogInventoryDivergence`, `DefaultEnginePersistenceService.java:937` — every directory
  name is a catalog name).
* **The rollback path is itself failure-prone.** Step 7 is an `Assert.isPremiseValid` around another
  rename; its own error message admits `"the original catalog will not be available as well"`.
* **Windows.** Directory renames and recursive deletes fail with `AccessDeniedException` whenever
  *any* handle inside is open — including handles held by an antivirus scanner or the search indexer,
  which the JVM cannot close. Both step 4/5 (rename) and step 6 (delete) are exposed. This is the
  reported symptom.
* **The contract already concedes defeat.** `EvitaContract#replaceCatalog` (:465–466, :486–487) documents:
  *"the state of `catalogNameToBeReplacedWith` is however unknown and should be treated as damaged."*
* **The engine cannot recover.** `ModifyCatalogSchemaNameMutationOperator`'s class JavaDoc states that
  forward-replay is deliberately *not* implemented because the deep on-disk rename cannot be re-run;
  `replayCompletionState` returns `Optional.empty()` and the transaction manager wedges loudly on a
  crash mid-operation.

### 1.4 What already exists and helps

The architecture has moved on considerably since #649 was filed, and most of the machinery this
redesign needs is already in place:

* **`EngineState`** (`spi/store/engine/model/EngineState.java`) — a versioned, WAL-backed, immutable
  snapshot of engine topology (`activeCatalogs` / `inactiveCatalogs` / `readOnlyCatalogs` /
  `missingCatalogs`), with a builder, a legacy-shape convenience constructor (:111–130) and a
  serializer version ladder (`EngineStateSerializer_2025_6`, `_2026_1`).
* **Engine mutations + operators** (`core/transaction/engine/operators/`) — every topology change is
  already a WAL-recorded mutation with a transition phase, a work phase and a completion phase.
* **`UpgradeCatalogFormatMutationOperator`** — the reference implementation of "install an
  `UnusableCatalog` placeholder, do risky work, restore the reference", including the
  `withInFlightPlaceholder` trick that preserves the persisted bucket across a crash.
* **`verifyCatalogNameMatches(..., OnDifferentCatalogName.ADAPT, ...)`** (:3633) — already rewrites the
  persisted header **and** schema name when the folder disagrees with the stored name. This is exactly
  the self-healing reconcile the new design needs; only its *authority* has to change.
* **`RESTORE_FLAG` (`.restored`)** — an established marker-file convention, written last by
  `RestoreTask` / `duplicateCatalog` and consumed-and-deleted on first load.
* **`walFileNameProvider`** — WAL file naming is already behind an `IntFunction<String>` seam
  (`AbstractMutationLog.java:237`), so it is not hard-wired to the catalog name.
* **`CatalogInventoryDivergence`** — boot-time reconciliation of engine state against disk is already
  a pure value drained through the regular mutation path.

---

## 2. Assessment of the seven proposed points

| # | Proposal | Verdict |
|---|---|---|
| 1 | generation PK from `SequenceService` | **Does not work as stated.** See 2.1. |
| 2 | folder names postfixed with generation | **Yes**, with a correction on what the generation is *for*. See 2.2. |
| 3 | adopt hand-copied folders | **Yes**, and it gets *more* robust. See 5.3. |
| 4 | copy contents into a new generation folder | **Unnecessary for rename and replace.** See 2.3. |
| 5 | atomic switch, delete old, crash recovery | **Yes** — and it collapses to something simpler. See 5.4. |
| 6 | on error delete the new generation, keep the old | **Yes**, and becomes trivial. |
| 7 | old catalog stays queryable until the switch | **Free**, not a feature to build. See 2.3. |

### 2.1 The generation sequence: engine-scoped `SequenceService`

**Decided: an engine-level `SequenceService` instance, keyed per catalog.** This reverses the first
draft of this document, which argued for a plain `long` in `EngineState` advanced inside the engine
mutation. Two of Johnny's points defeat that:

**(1) Burn-on-failure is the whole point, and the transactional counter cannot do it.**
A counter advanced *inside* the engine mutation only becomes durable when the mutation commits — so a
**failed** operation does not burn its number, and the retry reuses it. That is exactly wrong here. If
attempt #1 allocated `products_3`, created the folder, and then failed with the folder still on disk
(Windows delete-pending is the canonical case: the directory entry survives until the last handle
closes, and `CreateFile` against it fails with `ACCESS_DENIED`), the retry must not go anywhere near 3.
`SequenceService`'s in-memory `AtomicInteger` burns a number per *attempt*, which is precisely the
semantics wanted — its "monotonic, gaps expected" contract is a feature here, not the liability the
first draft called it.

**(2) The sentinel-key objection evaporates once the sequence is per catalog.**
`SequenceKey` is `(catalog, sequenceType, entityType)`. With a per-catalog generation the first field is
a genuine catalog name — no `"#engine"` sentinel, no key-space collision hazard. The class is being used
exactly as designed.

What the first draft *was* right about is that in-memory burning alone is not enough across a restart:
if the JVM dies after handing out 3 but before the peak is persisted, the next boot would re-seed low
and hand out 3 again, colliding with the orphan folder. `SequenceService` already has the answer built
in — `getOrCreateSequence(catalog, type, initialValue)` fast-forwards to `initialValue` and never goes
back. So seed it at boot with:

```
initialValue = max(persisted peak in EngineState, highest <name>_N suffix found on disk)
```

Both terms are needed, and the disk scan is safe *in this role*. The first draft's warning — "do not
derive the generation from `max(<name>_N)` on disk" — was about using the scan as the **authority**,
where a reclaimed number gets reused. As a **floor** combined with a persisted peak it is strictly
safer than either term alone: it catches exactly the crash-before-persist window, and it catches an
adopted foreign folder that arrived carrying a high suffix.

Keep the `Files.exists` guard in allocation anyway (§3.2). It costs one syscall and closes the residual
window where an orphan exists that neither term saw.

**API additions required:**

* `SequenceType.CATALOG_GENERATION`
* an engine-scoped `SequenceService` instance owned by `Evita` (the existing one is
  `Catalog.java:293`, per catalog, and stays untouched)
* **a removal method on `SequenceService`** — it has none today; `intSequences` / `longSequences` are
  `ConcurrentHashMap`s that only ever grow. Without it, create/drop cycles leak an entry per catalog
  name forever (§2.1.1).
* persisted per-catalog peaks in `EngineState`, written by the same mutation that allocates

#### 2.1.1 Retiring a catalog's sequence

Dropping the sequence entry the instant a catalog is dropped is wrong: if the catalog's folder is
tombstoned but the delete has not drained (the Windows case again), a recreated catalog of the same
name would restart at 1 and could walk back onto surviving litter.

**Rule: retire a catalog's sequence entry — from memory and from the persisted peaks — only once no
folder carrying that catalog-name prefix remains on disk and no tombstone references one.** In practice
that means the tombstone drain is what triggers retirement, so it happens on the boot after the last
folder actually goes away. Until then the peak is cheap to keep (one `long` per name).

This keeps the map bounded across create/drop churn without ever reusing a live number.

### 2.2 What the generation is actually for

Not "which copy of the data is newer". Its single job is: **a newly allocated folder must never
collide with a predecessor the OS has not let us reclaim yet.** That is the Windows delete-lock
problem, restated. Once folder deletion is decoupled from correctness, a folder we failed to delete
is merely litter, and the generation guarantees the litter never blocks the next operation.

### 2.3 The copy is unnecessary — and would be a regression

`replaceCatalog(A, B)` **consumes** A. `ModifyCatalogSchemaNameMutationOperator#doReplaceCatalogInternal`
calls `withoutCatalog(catalogNameToBeReplacedWith)` and `catalogToBeReplaced.terminate()`; the contract
(`EvitaContract` :462–463) says *"the original contents of `catalogNameToBeReplaced` will be purged
entirely"*.
`renameCatalog` likewise consumes the old name.

So there is no second live consumer of A's bytes after the operation. Once the folder is opaque and
`EngineState` owns the name→folder mapping, **the folder does not have to move at all**: catalog name
B simply starts pointing at A's existing folder, and B's former folder becomes litter.

Cost comparison for a replace of an *N*-byte catalog:

| | today | proposal 4 (copy) | pointer-only |
|---|---|---|---|
| bytes written | ~0 (renames) | **N** | ~0 |
| peak disk | N + N_target | **2N + N_target** | N + N_target |
| filesystem mutations before commit | 5 | thousands | **0** |

Copying would turn an O(1) operation into an O(data) one and double peak disk usage on exactly the
large catalogs where replacement matters most. It buys one thing — a pristine byte-for-byte source
if the operation fails — which the pointer-only design gets for free by never touching the source
folder before the commit.

Point 7 (old catalog readable until the switch) then needs no work: nothing on disk changes until the
engine-state commit, so **both** catalogs remain fully readable, not just the old one.

`duplicateCatalog` remains a genuine copy and is untouched by this.

#### Should `rename` still copy, to stay readable during the operation? (Johnny's follow-up)

The concern is right; the copy does not serve it. Pointer-only rename gives **strictly better** read
availability than a copy-based one, because there is no "during" to stay readable *for*:

* the same in-memory `Catalog` instance, the same open file handles and the same offset indexes serve
  reads before and after — only the key it is registered under in `EngineState` changes;
* the in-folder header/schema name rewrite is deferred to *after* the commit (Invariant B), so even
  that never sits between a reader and the data — and it is an append to an append-only offset index,
  which does not block readers in any case;
* the whole operation is a single engine-state commit: microseconds, not the minutes an O(data) copy
  of a large catalog would take.

A copy-based rename would instead be *worse* on this axis: reads stay available during the copy, but
the copy takes minutes on a large catalog, doubles peak disk for that whole window, and still ends in
the same instantaneous switch. Availability during a long operation only matters when the operation is
long — and it is only long because of the copy.

There is one thing a copy-based rename *would* give that pointer-only does not: catalog `A` surviving
under its old name after the operation. But that is `duplicateCatalog`, not `renameCatalog` —
`EvitaContract#renameCatalog` specifies the old name ceases to exist.

What I suspect actually motivates the question is the cosmetic drift — after a pointer-only rename,
catalog `orders` lives in a folder still called `products_7`. §3.4 addresses that with a *deferred*
folder rename at next boot (handles closed, so the rename is safe) plus a `.catalogname` marker file,
which is cheaper than an O(data) copy and cannot fail destructively.

**If you still want copy-on-rename after that, say so and I will spec it** — it is a coherent choice
and it is your call; I would just be recording an O(data) cost for an availability guarantee the
cheaper path already meets.

### 2.4 The blast radius is identical either way

This is the decisive argument. Roughly 30 non-test sites derive a path from a catalog name, ~12 of them
`this.storageDirectory.resolve(catalogName)` inside `core/transaction/engine/operators/*` building
`UnusableCatalog` placeholder paths (`CreateCatalogMutationOperator`, `RemoveCatalogSchemaMutationOperator`,
`MakeCatalogAliveMutationOperator`, `MarkCatalogMissingMutationOperator`, `RestoreCatalogSchemaMutationOperator`,
`SetCatalogStateMutationOperator`, `UpgradeCatalogFormatMutationOperator`, `DuplicateCatalogMutationOperator`),
plus `Evita.java:488/497/1500`, `computeCatalogInventoryDivergence`, `RestoreTask.java:146` and
`DefaultCatalogPersistenceService.java:4316`.

Every one of them becomes "ask the engine state for the folder" the moment `folder != name` — which is
true under *both* designs. Proposal 4 therefore does not buy a smaller change; it only adds the copy on top.

---

## 3. Recommended model

### 3.1 Three identities, explicitly separated

| identity | authority | mutable? | visible to |
|---|---|---|---|
| **catalog name** (`products`) | `EngineState` | yes — rename/replace | clients |
| **catalog id** (`UUID`) | header inside the folder | no — survives rename *and* replace | clients (CDC, caches) |
| **storage folder** (`products_7`) | `EngineState` mapping | reassigned, never rewritten in place | operators only |

**Invariant A — `EngineState` is the sole authority for `name → folder`.** Nothing on disk outside the
engine bootstrap may be consulted to answer "which folder is catalog `products`?".

**Invariant B — a folder's *contents* are never rewritten to effect a rename.** The persisted header /
schema name inside a folder is a denormalised cache, reconciled *after* the switch. It is never on the
critical path.

**Invariant C — the engine-state commit is the only commit point.** Everything before it is read-only;
everything after it is idempotent cleanup that may be retried on any later boot.

#### Where `catalogId` comes from, and when it changes

There are exactly three assignment sites, and only one of them mints a value:

| site | assignment | when |
|---|---|---|
| `Catalog.java:601` | `UUID.randomUUID()` | **the only place a new id is minted** — brand-new catalog ctor |
| `Catalog.java:685` | `catalogHeader.catalogId()` | load-from-disk ctor — adopts whatever the header holds |
| `Catalog.java:771` | `previousCatalogVersion.catalogId` | copy ctor — every commit, go-live, replace |

Everything else — `storeHeader`, compaction (:2109, :2177), `replaceWith` (:2362),
`verifyCatalogNameMatches`'s `ADAPT` branch (:3666), the migrations (`Migration_2025_6`,
`Migration_2026_2`) — **propagates** `catalogHeader.catalogId()` forward unchanged. So:

* **rename** — preserved (copy ctor).
* **replace** — preserved, and specifically the **source's** id wins: `replaceWith` writes
  `catalogHeader.catalogId()` taken from the catalog *providing* the data. Name `B` therefore acquires
  `A`'s id. Under the pointer-only model this happens for free, because the folder retained is A's and
  its header is never rewritten.
* **go-live, compaction, every transaction** — preserved.
* **restore from backup / `duplicateCatalog`** — preserved **today**, because both copy the header bytes
  verbatim and the `ADAPT` path re-propagates `catalogHeader.catalogId()`. So a duplicated catalog is a
  second live catalog carrying the *same* id as its source, and a backup restored alongside its original
  yields two catalogs indistinguishable by id — while clients use `catalogId` precisely to decide
  whether their cached view is still valid. **Decided: this is a defect and is fixed as part of this
  work** (§3.1.1).

#### 3.1.1 Minting a fresh `catalogId` on restore and duplicate

`duplicateCatalog` and `restoreCatalog` produce a **new lineage**: the copy's version stream diverges
from the source's at the moment of the copy, so any client cache keyed on the source's id is already
stale with respect to it. Carrying the id across is therefore not just ambiguous, it is wrong.

**Rule: mint a fresh `UUID` whenever a catalog is materialised from copied bytes** — `duplicateCatalog`
and every restore. The natural site is the existing `ADAPT` branch of `verifyCatalogNameMatches`
(`:3666`), which already rewrites the header on first load of a `RESTORE_FLAG`-marked folder and already
carries `catalogId` through; it becomes `UUID.randomUUID()` there instead of
`catalogHeader.catalogId()`. That covers both paths at once, because both set `RESTORE_FLAG`.

The one case worth a conscious decision is **restore-in-place after a disaster** — dropping catalog `X`
and restoring `X` from backup. The rule mints a new id there too, forcing clients to invalidate. That
is the correct outcome: the restored catalog *is* a different dataset (it lost everything committed
after the backup point), and silently reusing the id would let a client serve stale data it believes is
current.

**To verify before implementing:** whether any test or client path asserts id stability across a
backup/restore round trip — `EvitaBackwardCompatibilityTest` is the likely place.

A fresh id is therefore minted by `createCatalog`, `duplicateCatalog` and restore; and by nothing else.
Rename, replace, go-live, compaction and every transaction preserve it.

### 3.2 Folder naming

```
<storageDirectory>/<catalogName>_<generation>/
```

`<catalogName>` is the name the catalog had **when the folder was allocated** — cosmetic, never
authoritative, never rewritten in place. `<generation>` is **per catalog, starting at 1**, drawn from
the engine-scoped `SequenceService` (§2.1).

Allocation burns a number per *attempt*, so a failed operation never leaves its number available to
the retry:

```java
int generation;
Path candidate;
do {
    generation = sequence.incrementAndGet();
    candidate  = storageDirectory.resolve(catalogName + '_' + generation);
} while (Files.exists(candidate));
```

**Legacy folders migrate on first boot.** A bare `products` folder — whether left by an older evitaDB
version or hand-copied in by an operator — is treated identically: adopt it, allocate generation 1,
rename the folder to `products_1`, record `products → products_1`. One code path for both, which means
the upgrade path is exercised by every foreign-folder import test and vice versa. If the rename fails,
the mapping simply records the folder under its bare name and the migration retries on the next boot —
nothing depends on it having succeeded.

### 3.3 File names inside the folder: discover, do not construct

The bootstrap file is the entry point — it must be found before anything inside the folder can be read,
so its name cannot come from state stored inside the folder. Today it is constructed as
`catalogName + ".boot"`, which breaks the instant `folder != name`.

**Resolve it by globbing for the single `*.boot` file in the folder**, and take everything before the
suffix as the folder's immutable *storage prefix*. `getCatalogDataStoreFileName`,
`getCatalogDataStoreFileNamePattern`, `getWalFileName` and `walFileNameProvider` are then all fed the
storage prefix instead of the catalog name. Collection files already need nothing.

This deliberately avoids bumping `STORAGE_PROTOCOL_VERSION` to rename in-folder files to literals
(`catalog.boot` / `catalog_0.catalog`): that would drag in a full `Migration_2026_X` for existing
installations and buy nothing that discovery does not already give. Existing catalogs are readable
unchanged; a catalog renamed `products → orders` keeps files named `products*.catalog` forever, which
is cosmetically odd but functionally irrelevant, and is exactly the same trade-off already accepted
for `entityTypePrimaryKey` in collection file names.

### 3.4 Human operability — deferred cosmetic rename

Folder names stop being reliable labels: after `renameCatalog(products → orders)` the data still sits
in `products_3`. Three options, shown as what `ls -a` prints. Start state — catalog `products` in
generation 3, then renamed to `orders`:

```
BEFORE the rename                    storage/
                                     └── products_3/
                                         ├── products.boot
                                         ├── products_0.catalog
                                         ├── products_0.wal
                                         └── Product-1_0.collection
```

**Option A — deferred boot rename + `.catalogname` marker (recommended)**

```
immediately after the rename         storage/                  after the next restart
                                     └── products_3/           storage/
                                         ├── .catalogname      └── orders_4/
                                         │     └─ "orders"         ├── .catalogname → "orders"
                                         ├── products.boot         ├── products.boot
                                         └── …                     └── …
```

The folder is renamed at **boot, before the catalog is opened** — every handle is closed, so this is as
safe as a filesystem rename gets. A rename immediately after the commit would be the opposite: the
catalog is open at that instant, so on Windows it fails exactly as often as the dance this redesign
removes. Generation 4 is burned by the attempt (§2.1). In-folder *file* names still say `products` —
that is cosmetic only (§3.3).

**Option B — marker only, folder never renamed**

```
forever                              storage/
                                     └── products_3/
                                         ├── .catalogname → "orders"
                                         └── …
```

`cat storage/*/.catalogname` answers "which folder is which" in one command, and unlike a rename it
cannot fail on a lock. But `ls` alone stays misleading indefinitely.

**Option C — neither; the API and the startup log are the only mapping**

```
forever                              storage/
                                     └── products_3/
                                         └── …            (nothing on disk says "orders")
```

Least code. Someone doing disaster recovery from a bare storage directory, with no running server, has
no way to tell which folder holds which catalog.

**Recommendation: A** — it is B plus a rename that is free when it works and harmless when it does not,
and it reuses exactly the boot-time rename already needed for legacy-folder migration (§3.2). Expose
the mapping in the management API and the startup log line under all three.

### 3.5 Folder lifecycle states

**Discovery is restricted to suffix-free folder names** (Johnny's refinement). A folder named exactly
`<catalogName>` with no `_<digits>` suffix is the documented shape for hand-placing a catalog; every
folder evitaDB itself allocates carries a suffix. That removes the ambiguity entirely — we never have
to guess whether `products_7` is our litter or someone's import.

Every directory under `storageDirectory` is then exactly one of:

| state | discriminator | boot action |
|---|---|---|
| **referenced** | present in `EngineState`'s name→folder map | load |
| **provisional** | `.provisional` marker present, unreferenced | delete — an in-flight op died |
| **retired** | listed in `EngineState.retiredFolders[]` | delete; on failure retry next boot |
| **foreign** | unreferenced, **no suffix**, has a `*.boot` | adopt (§5.3) |
| **unclaimed** | unreferenced, suffixed, no marker/tombstone | **warn and leave alone** |
| **junk** | unreferenced, no `*.boot` at all | warn, leave alone |

The **unclaimed** row is the safety net and must not be collapsed into "delete". If an operator copies
`products_7` in from another instance — which the suffix-free rule tells them not to do, but which they
will do anyway — deleting it is unrecoverable data loss. Positive evidence of ownership (a tombstone we
wrote, or a `.provisional` marker we wrote) is required before anything is destroyed. The warning
should name the fix: *"rename it to `products` to have it adopted"*.

`retiredFolders[]` is a new WAL-backed tombstone list in `EngineState`. It is what makes deletion
non-blocking: the switch commits, the delete is attempted, and a Windows lock merely postpones the
delete to the next boot instead of failing the operation.

`.provisional` is written **first** by any operation that materialises a new folder (create, restore,
duplicate) and removed as the last step. It is the same idea as the existing `RESTORE_FLAG` but with the
opposite polarity — `RESTORE_FLAG` says *"complete, adapt the name on load"*, `.provisional` says
*"incomplete, do not trust"*. Both are needed; they answer different questions. The two together are
what let the **unclaimed** row stay non-destructive without leaking litter: everything we create is
marked from the instant it exists.

### 3.6 Backups must emit the suffix-free shape

A backup zip is the main way a catalog folder travels between instances, so its top-level directory
entry and its file names must be the canonical, suffix-free, storage-prefix-free shape. Current state:

* **`BackupTask` is already correct**, and by construction rather than accident — it regenerates every
  entry name from `this.catalogName` (the *logical* name) and normalises file indexes to `0`:
  `getCatalogDataStoreFileName(this.catalogName, 0)` (:201), `getEntityCollectionDataStoreFileName(..., 0)`
  (:342), `getCatalogBootstrapFileName(this.catalogName)` (:406), top entry `this.catalogName + "/"`.
  **One exception:** `backupWAL` uses `walFile.getFileName()` verbatim (:382), which would leak the
  storage prefix. Normalise it to `getWalFileName(this.catalogName, idx)`.
* **`FullBackupTask` needs work** — it copies `catalogStoragePath.relativize(file)` verbatim (:212), so
  every `.boot` / `.catalog` / `.wal` entry would carry the storage prefix.

`RestoreTask#getFileNameWithCatalogRename` strips `directoryName` and re-derives names from the target
catalog name, which keeps working unchanged once both backup tasks emit the canonical shape. Belt and
braces: have it fall back to `*.boot` discovery rather than assuming the prefix equals `directoryName`,
so an older zip taken from a renamed catalog still restores.

---

### 3.7 Reader availability — the acceptance criterion

Johnny's stated requirement: **simultaneous readers must not be interrupted from querying a renamed or
replaced catalog, and there must be integration tests proving it.** This is the criterion the design is
accepted against, so it is worth being precise about what is achievable and what is not.

Today readers *are* interrupted, deliberately: `doReplaceCatalogInternal` calls
`closeAllActiveSessionsAndSuspend(SuspendOperation.POSTPONE)` on the prevailing catalog and
`SuspendOperation.REJECT` on the one being replaced — active sessions are force-closed, and the
suspension spans the entire folder-rename dance.

Under the pointer-only model the guarantee becomes:

| reader | today | target |
|---|---|---|
| in-flight query on the **source** catalog | force-closed | **completes** — same `Catalog` instance, same handles |
| open session on the **source** catalog | force-closed, re-opened | **survives**, now serving the new name |
| new session arriving at the commit instant | POSTPONE for the whole operation | POSTPONE for **one WAL append** |
| in-flight query on the **replaced** (destroyed) catalog | force-closed | **drains**, then the instance terminates |
| open session on the **replaced** catalog | REJECT | REJECT — unavoidable, its data is gone |

The last row is the honest limit: sessions bound to the catalog whose data is being purged cannot be
kept alive. What *can* improve is that their in-flight queries finish against the old instance before
`terminate()` runs, instead of being yanked. **To verify:** whether `Catalog#terminate()` drains or
yanks, and whether a grace period is needed.

Note this criterion argues **against** copy-based rename rather than for it. The postpone window is
bounded by the duration of the operation; pointer-only makes that one WAL append, while a copy makes it
the time to write N bytes — and a copy taken *without* suspending writes is not a consistent copy, so
the suspension cannot simply be moved outside it.

**Tests (these are the deliverable, not a nice-to-have):**

* concurrent reader pool querying catalog `A` throughout a `renameCatalog(A → B)` — assert **zero**
  failed or aborted queries, and that readers continue against `B` afterwards
* concurrent reader pool on the **source** of a `replaceCatalog(A → B)` — assert zero failures
* concurrent reader pool on the **target** (destroyed) side — assert failures are confined to a
  well-defined exception, arrive only after the commit, and that queries already in flight completed
* the same three under an induced folder-delete failure, proving the tombstone path does not surface to
  readers at all

---

## 4. Operation walkthroughs

### 4.1 `renameCatalog(A → B)`

1. **read-only phase** — validate `B` is free; suspend *writes* to A (reads may continue).
2. **commit** — one engine mutation: `catalogs[B] = catalogs[A].folder`, drop `catalogs[A]`, bump
   in-memory `CatalogSchema` name + variants. Written to the engine WAL, engine state version bumps.
3. **after commit, all best-effort** — persist the new name into the folder's header + schema part
   (an append + bootstrap record, the existing `ADAPT` code path); cosmetic folder rename.

No folder is created, none is deleted, nothing is copied. A crash before step 2 is a no-op; a crash
after step 2 leaves a folder whose stored name lags, which the next load reconciles.

### 4.2 `replaceCatalog(A → B)` (A becomes the new B)

1. **read-only phase** — resolve `folderA`, `folderB`; suspend writes to both. Both stay readable.
2. **commit** — one engine mutation: `catalogs[B] = folderA`, drop `catalogs[A]`, append `folderB` to
   `retiredFolders[]`. `catalogId` is whatever `folderA`'s header already says — preserved for free,
   with none of today's `header.version()+1` header rewriting.
3. **after commit** — terminate the old B catalog instance, close its handles, delete `folderB`
   (retry on next boot if the OS refuses), reconcile the stored name in `folderA`, cosmetic rename.

The contract clause *"the state of `catalogNameToBeReplacedWith` is unknown and should be treated as
damaged"* can be **strengthened to "both catalogs are untouched"** for every failure before step 2 —
which is every failure that is not a crash of the engine-state commit itself.

### 4.3 `duplicateCatalog(A → B)` / `restoreCatalog(→ B)`

Unchanged in substance — these are genuine copies. Only the target folder allocation changes
(`allocateFolder(B)` instead of `pathForCatalog(B)`), plus writing `.provisional` first and clearing it
last, before the engine mutation that registers `B → folder`.

### 4.4 `dropCatalog(B)`

Commit removes `catalogs[B]` and appends its folder to `retiredFolders[]`. Deletion becomes
best-effort cleanup instead of part of the operation — which alone fixes a second class of Windows
failure (drop-then-recreate on a locked folder).

### 4.5 Crash matrix

| crash point | on-disk residue | recovery |
|---|---|---|
| before commit | none | nothing to do — old world intact |
| during engine-WAL append | partial engine WAL record | existing engine WAL recovery discards it |
| after commit, before old folder deleted | retired folder still present | tombstone drains on next boot |
| after commit, before name reconcile | folder's stored name lags | `ADAPT` on load, engine state wins |
| during duplicate/restore | half-written folder with `.provisional` | deleted on next boot |

Note the asymmetry this buys: recovery has **exactly one** durable fact to consult — the engine state —
instead of having to infer how far a five-step rename got.

---

## 5. Consequences and open items

### 5.1 Forward replay becomes implementable

`ModifyCatalogSchemaNameMutationOperator` currently cannot implement `replayCompletionState` because
`Catalog#replace` performs an unrepeatable deep on-disk rename. Under this design the completion state
is a pure pointer swap and is idempotent, so replay becomes possible — turning a wedged engine into a
clean recovery. **This is arguably the largest single win of the redesign and should be a named
deliverable.** Before claiming it, the remaining completion-phase side effects must be checked: the
`SessionRegistry` swap, `notifyCatalogPresentInLiveView()` and `catalogToBeReplaced.terminate()` are
all in-memory and there are no sessions at replay time, but that needs verifying rather than assuming.

### 5.2 `verifyCatalogNameMatches` changes authority, not shape

Today it compares the stored header name against `this.catalogName`, which was derived from the folder
name, and either throws (`THROW_EXCEPTION`) or rewrites the folder to match (`ADAPT`). Under the new
model the incoming name comes from `EngineState` instead of the folder, and `ADAPT` becomes the *normal*
path rather than the restore-only one. `THROW_EXCEPTION` loses its purpose — a folder holding a
different name is no longer evidence of a mistake. The `RESTORE_FLAG` delete-on-load side effect needs
re-examining once `ADAPT` is unconditional; it may collapse into the `.provisional` scheme or remain
solely as the "this came from outside, regenerate naming variants" signal.

### 5.3 Adopting a hand-copied folder (point 3) gets *more* robust

`computeCatalogInventoryDivergence` changes from "every directory name is a catalog name" to "classify
each unreferenced directory per §3.5; for a **foreign** one (suffix-free, has a `*.boot`), read the
catalog name from its header and register `name → thisFolder`".

Reading the name from the header rather than trusting the directory name matters even under the
suffix-free rule: it is what lets adoption **detect** a mismatch. If the folder is called `products` but
its header says `orders`, or if `orders` is already registered, the folder is reported as a conflict and
left alone rather than silently shadowing a live catalog — a hole that exists today, where the directory
name is taken as gospel.

The freshly adopted folder keeps its existing (suffix-free) directory name; the counter is not advanced
by adoption, which is why the `Files.exists` guard in allocation (§3.2) is required — otherwise a later
allocation could pick a name an adopted folder already occupies.

### 5.4 Client-visible version discontinuity

After a replace, catalog B's version stream jumps to A's lineage. This is already true today
(`newCatalogVersion = catalogHeader.version() + 1` is computed from the *source* header), so it is not
a regression — but it is worth stating explicitly in the ADR since the mechanism changes.

### 5.5 Work list

**Sequences**
- [ ] `SequenceType.CATALOG_GENERATION`
- [ ] engine-scoped `SequenceService` instance owned by `Evita` (per-catalog one at `Catalog.java:293`
      is untouched)
- [ ] **`SequenceService` removal API** — none exists today; needed for §2.1.1 retirement
- [ ] boot seeding: `max(persisted peak, highest <name>_N on disk)` via `getOrCreateSequence`'s
      fast-forward; retirement driven by the tombstone drain

**Engine state / persistence**
- [ ] `EngineState`: add name→folder map, `retiredFolders[]`, per-catalog generation peaks; new
      `EngineStateSerializer_2026_X` + registration
- [ ] first-boot migration: bare folder → adopt, generation 1, rename to `<name>_1` (same code path as
      foreign-folder adoption); retried on later boots if the rename fails
- [ ] `ExpandedEngineState` + `Builder`: folder accessor, `withCatalog(name, folder)`, tombstone staging
- [ ] folder allocation + `.provisional` marker helpers
- [ ] `computeCatalogInventoryDivergence` → six-way classification (§3.5); adoption restricted to
      suffix-free folders; name read from the header; **unclaimed** folders warned about, never deleted

**Catalog persistence service**
- [ ] `pathForCatalog(name, dir)` → `folderFor(name)` resolved from engine state; ~14 call sites in
      `DefaultCatalogPersistenceService`
- [ ] storage-prefix discovery from `*.boot`; feed it to `getCatalogDataStoreFileName`,
      `getCatalogDataStoreFileNamePattern`, `getWalFileName`, `walFileNameProvider`, compaction
      (`:3438`, `:3510`) and `ObsoleteFileMaintainer`
- [ ] `replaceWith` → reduced to schema/header reconcile; the file+folder rename dance (:2379–2450) is deleted
- [ ] `verifyCatalogNameMatches` authority flip; `RESTORE_FLAG` semantics revisited
- [ ] `duplicateCatalog` / `RestoreTask`: allocate folder, write `.provisional`
- [ ] **mint a fresh `catalogId`** in the `ADAPT` branch (`:3666`) so restore and duplicate no longer
      inherit the source's id (§3.1.1); check `EvitaBackwardCompatibilityTest` for id-stability asserts
- [ ] `BackupTask#backupWAL` (:382) — the one entry still using `walFile.getFileName()` verbatim;
      normalise to `getWalFileName(this.catalogName, idx)`. The rest of `BackupTask` is already canonical
- [ ] `FullBackupTask` (:212) — copies `relativize(file)` verbatim; normalise `.boot`/`.catalog`/`.wal`
      entry names to the logical catalog name
- [ ] `RestoreTask#getFileNameWithCatalogRename` — fall back to `*.boot` discovery instead of assuming
      the in-zip prefix equals `directoryName`, so zips taken from renamed catalogs still restore
- [ ] `.catalogname` marker written/refreshed post-commit; deferred boot-time cosmetic folder rename

**Engine operators**
- [ ] ~12 `storageDirectory.resolve(catalogName)` sites → engine-state lookup
- [ ] `ModifyCatalogSchemaNameMutationOperator`: read-only phase / single commit / post-commit cleanup;
      implement `replayCompletionState`
- [ ] `RemoveCatalogSchemaMutationOperator`: delete → tombstone
- [ ] boot-time tombstone drain + provisional sweep
- [ ] `Evita.java:488/497/1500`

**Tests**
- [ ] **concurrent-reader integration tests per §3.7** — the acceptance criterion, four scenarios
- [ ] `EvitaReplacementFunctionalTest` — extend beyond "replace under heavy load" to crash-injection at
      each point of the §4.5 matrix
- [ ] `DefaultCatalogPersistenceServiceTest` — folder-name assumptions (`:355` writes `RESTORE_FLAG` into
      a renamed folder path)
- [ ] legacy-layout boot test: bare folder `products` → adopted, renamed to `products_1`
- [ ] foreign-folder adoption: suffix-free adopted; suffixed left alone with a warning; header/name
      mismatch and already-taken-name reported as conflicts
- [ ] generation burn-on-failure: a failed allocation must not hand the same number to the retry, and a
      restart must not walk back onto a surviving orphan folder
- [ ] sequence retirement: create/drop churn does not grow the sequence map once tombstones drain
- [ ] `catalogId` freshness: duplicate and restore each yield an id distinct from the source's

### 5.6 Rejected outright

**Copy folder contents into the new generation (proposal 4).**
*Rejected because* it turns an O(1) operation into O(data) writes with 2× peak disk, buying only a
pristine source — which never touching the source before the commit already provides. Revisit if a
case appears where source and target must both stay live *after* the operation.

**Derive the generation from `max(<name>_N)` found on disk, as the authority.**
*Rejected because* numbers get reused once a folder is reclaimed, so a crash remnant can collide with
a fresh allocation — the exact failure the generation exists to prevent. Note the scan **is** used, but
only as a boot-time *floor* alongside the persisted peak (§2.1), where it is strictly safety-adding.

**A plain `long` generation counter advanced inside the engine mutation.**
*Rejected because* it only burns a number when the operation commits, so a failed attempt hands the same
generation back to the retry — which then collides with the folder the failed attempt left behind. That
is the single case the generation exists for. `SequenceService` burns per attempt, which is why it wins
here despite its non-transactional semantics.

**Fixed in-folder file names (`catalog.boot`, `catalog_0.catalog`) via a storage-protocol bump.**
*Rejected because* it needs a `Migration_2026_X` over every existing installation, while `*.boot`
discovery achieves the same decoupling at zero migration cost. Revisit if a protocol bump is happening
anyway for an unrelated reason — the literal names are marginally tidier.

**Keep folder = name; use copy-then-swap only on Windows.**
*Rejected because* it means two code paths for one operation, with the harder path exercised least.
The failure mode is not actually Windows-specific — it is "multi-step filesystem mutation with no
durable record of progress"; Windows only makes it frequent.

**Opaque UUID folder names.**
*Rejected because* maximum decoupling costs an operator the ability to identify a folder by eye and
makes disaster recovery from a bare storage directory considerably harder. `<name>_<gen>` keeps that
affordance at no correctness cost, since the name part is never trusted.

### 5.7 Decisions — settled

| # | Decision | Where |
|---|---|---|
| 1 | Discovery restricted to **suffix-free** folder names, **plus** `.provisional` markers — both | §3.5 |
| 2 | Unclaimed suffixed folders: **warn, never delete** | §3.5 |
| 3 | Backups emit the canonical suffix-free shape | §3.6 |
| 4 | `replaceCatalog` is **pointer-only**, no copy | §2.3 |
| 5 | `renameCatalog` is **pointer-only**, subject to the §3.7 reader tests passing | §2.3, §3.7 |
| 6 | Generation from an **engine-scoped `SequenceService`**, per catalog, starting at 1 | §2.1 |
| 7 | Sequence retirement gated on the tombstone drain, not on the drop | §2.1.1 |
| 8 | Cosmetic naming: **deferred boot rename + `.catalogname` marker** (option A) | §3.4 |
| 9 | Legacy bare folders **migrate on first boot** to `<name>_1`, same path as adoption | §3.2 |
| 10 | Tombstones dropped after the first successful delete | §4.4 |
| 11 | `replayCompletionState` is **in scope** for #649 | §5.1 |
| 12 | Fresh `catalogId` minted on restore and duplicate — **folded in** | §3.1.1 |

Decision 5 is conditional by Johnny's own criterion: pointer-only rename is accepted *because* it keeps
readers uninterrupted, so the §3.7 tests are what ratify it. If they cannot be made to pass, revisit —
though note a copy-based rename would widen the postpone window rather than narrow it.

**Open items that block nothing but need answering during implementation:**

* Does `Catalog#terminate()` drain in-flight queries or yank them? (§3.7, last row of the table)
* Does any test assert `catalogId` stability across a backup/restore round trip? (§3.1.1)
* Do the completion-phase side effects of `doReplaceCatalogInternal` — the `SessionRegistry` swap,
  `notifyCatalogPresentInLiveView()`, `terminate()` — have anything non-idempotent left once the disk
  work is gone? (§5.1; decision 11 depends on the answer being "no")

---

## 6. Implementation order

Sequenced so the tree compiles and the suite stays green at every step, and so the risky parts land
last with their safety net already in place. Steps 0–3 are behaviour-preserving.

**Step 0 — answer the three open items above.** All three are reads, no edits. They gate steps 5 and 7.

**Step 1 — introduce the folder indirection with the identity mapping still trivial.**
Replace `pathForCatalog(name, dir)` with a `folderFor(name)` seam resolved through the engine state, and
route all ~30 derivation sites through it — but have the mapping return `name → name` for everything.
Nothing changes on disk or in behaviour; the suite must stay green. **This is the largest mechanical
step and it is the one that de-risks all the others**, because after it no code assumes `folder == name`.

**Step 2 — storage-prefix discovery.** Glob for the single `*.boot`; feed the discovered prefix to
`getCatalogDataStoreFileName`, `getCatalogDataStoreFileNamePattern`, `getWalFileName`,
`walFileNameProvider`, compaction (`:3438`, `:3510`) and `ObsoleteFileMaintainer`. Still no behaviour
change — the prefix equals the catalog name for every existing folder.

**Step 3 — backup/restore normalisation.** `BackupTask#backupWAL` (:382), `FullBackupTask` (:212),
`RestoreTask` prefix fallback. Independently testable and independently valuable.

**Step 4 — `EngineState` schema + sequences.** Name→folder map, `retiredFolders[]`, per-catalog peaks,
new serializer in the ladder; `SequenceType.CATALOG_GENERATION`, engine-scoped `SequenceService`, its
removal API, boot seeding and retirement. Now the mapping can hold a real folder name.

**Step 5 — folder lifecycle.** Allocation with burn-and-skip, `.provisional` markers, six-way boot
classification, tombstone drain, legacy/foreign adoption and the boot-time rename, `.catalogname`
marker. **Write the boot-classification tests before the classification code** — this is the step where
a bug deletes user data.

**Step 6 — rewire the operators.** The ~12 `storageDirectory.resolve(catalogName)` sites,
`RemoveCatalogSchemaMutationOperator` → tombstone, `Evita.java:488/497/1500`.

**Step 7 — the payload: pointer-only rename and replace.** Rewrite `ModifyCatalogSchemaNameMutationOperator`
into read-only phase / single commit / post-commit cleanup; gut `replaceWith` (:2379–2450); flip
`verifyCatalogNameMatches` authority; implement `replayCompletionState`. Everything it needs now exists.

**Step 8 — fresh `catalogId` on restore/duplicate** (§3.1.1). Deliberately last: it is the only
client-visible behaviour change, so it should not be in flight while the rest is being debugged.

**Step 9 — the §3.7 concurrent-reader tests and the §4.5 crash-injection matrix.** These ratify
decision 5. Strengthen the `EvitaContract#replaceCatalog` JavaDoc once they pass.

**Step 10 — ADR.** Move this document to `documentation/adr/YYYY-MM-DD-catalog-folder-decoupling.md`
(date = merge date of the implementing PR), delete `specifications/649-catalog-folder-decoupling/` in
the same commit, regenerate the index with `tools/generate-adr-index.sh`. The copy-vs-pointer fork and
the plain-counter-vs-`SequenceService` fork are what make this clear the ADR bar; both rejection reasons
in §5.6 must survive into the record.

## 7. Gates that must be checked before the work is called done

These are ordering hazards discovered during implementation, not part of any single step. Each fails
*silently* if missed — which is why they are a checklist rather than a note.

- [ ] **Step 5 must not land before step 7, or these two sites must be converted first.**
  `DefaultCatalogPersistenceService#replaceWith`'s renaming block and the private static
  `getFileNameWithCatalogRename` still build and compare file names from the **catalog name**, not
  from the storage prefix discovered in step 2. That is correct only while the two are equal, which
  holds through step 4. Step 5 is what first makes a prefix diverge from a name, and step 7 deletes
  both sites outright — so the hazard exists only in the window between them. If that window is ever
  going to be open, convert both to `this.storagePrefix` before step 5 merges. The failure mode is
  the dangerous kind: `replaceWith` would match nothing and rename nothing, reporting success.

- [ ] **Re-check this list before the branch merges**, not only before each step. A step reordering
  is exactly the event that makes a dormant gate live.
