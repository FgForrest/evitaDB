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

## Steps 3–10 — not started

Next up is step 3 (backup/restore normalisation), which owns the one remaining length-based file-name
arithmetic in the tree: `RestoreTask:89`.

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
