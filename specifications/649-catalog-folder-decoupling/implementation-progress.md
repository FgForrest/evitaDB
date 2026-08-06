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

## Steps 2–10 — not started

Next up is step 2 (storage-prefix discovery from `*.boot`).
