# Proposal: the engine names folder *identities*, the store module names *directories*

**Status:** reviewed — Option B adopted
**Scope:** rework of step 1 of `README.md` §6, before step 2 starts
**Issue:** #649

> §§1–9 have been **revised to incorporate the architectural review in §10**, which is kept verbatim as the
> review record. The substantive changes: §4's filesystem audit was incomplete and is corrected, §5.4 is
> replaced by a consolidated SPI surface, and §6 gains two items it had missed. §9 now records the settled
> answers rather than posing questions.

---

## 1. What is being asked

Issue #649 makes `EngineState` the sole authority for the `catalog name → storage folder` binding, so that
rename and replace become a single engine-state commit instead of a five-step, non-atomic directory-renaming
dance. That design is settled and is **not** what this proposal revisits.

What is under review is narrower: **when the engine records "catalog `products` lives in folder X", what is
X?**

* **Option A — as currently built.** X is a `java.nio.file.Path`. The engine resolves it and passes it down
  through the storage SPI.
* **Option B — proposed.** X is an opaque token (`CatalogFolderId`, e.g. `products_3`). `evita_store_server`
  is the only module that knows a token denotes a directory, or how to turn one into a path.

B was put to architectural review and adopted; the review, its corrections and its refinements are in §10 and
are folded into the sections above.

---

## 2. Why this came up

evitaDB deliberately keeps filesystem layout and data-store logic inside `evita_store_server`, with
`evita_engine` abstracted from it via a ServiceLoader SPI (`evita_store_server` depends on `evita_engine`,
never the reverse). Step 1 of the #649 work introduced `CatalogFolderResolver` — a `Path`-valued interface —
into `evita_engine`. The objection raised in review: this walks that separation back.

## 3. Evidence: the leak predates the seam

Measured on `dev` **before** any #649 change:

| Observation | Count |
|---|---|
| Sites in `evita_engine` constructing a catalog folder path | 13 |
| Those sites: `Evita:488/497/1500` plus a `Path storageDirectory` field on operators | 8 operators |
| Files under `evita_engine/src/main` importing `java.nio.file.Path` | 19 |
| Engine model types carrying a folder `Path` | `UnusableCatalog.catalogStoragePath` |

So `CatalogFolderResolver` did not introduce `Path` into the engine. It collected ~30 scattered inline
`storageDirectory.resolve(catalogName)` calls behind one interface, which is strictly better than what it
replaced.

**The objection that survives this evidence is the important one:** the seam *institutionalises* the leak, and
it does so at the exact moment folder identity is being redesigned — which is the one moment removing it is
cheap.

## 4. Evidence: the engine barely uses these paths

Across the whole of `evita_engine`, a catalog folder `Path` is used for exactly three things:

1. **Pass-through** to the storage SPI or to the `UnusableCatalog` placeholder — the overwhelming majority.
2. **Error-message interpolation** — ~24 call sites inside `UnusableCatalog`, all of the form
   `cause.apply(catalogName, catalogStoragePath)`.
3. **Three genuine filesystem operations:**
   * `RestoreCatalogSchemaMutationOperator:88` — `catalogFolder.toFile().exists()`
   * `UnusableCatalog:172` — `FileUtils.deleteDirectory(...)`, the body of `terminateAndDelete()`
   * `UnusableCatalog:291` — `FileUtils.getDirectorySize(...)`, filling `CatalogStatistics.sizeOnDiskInBytes`

> **Audit correction.** An earlier revision of this section claimed there were "two, and only two". The
> `getDirectorySize` call was missed, and it is the one with client-visible output: it is reachable through
> `EvitaManagement:467` (`getCatalogStatisticsSafely`), because the system-API catalog listing includes
> corrupted catalogs. It is treated as a first-class requirement in §5.4, not an afterthought — see §6 for
> what happens to the reported value.

The engine is not *doing* filesystem work. It is carrying a value, printing it, and performing three
operations that belong in the store layer on their own merits.

This is what makes B cheap: there is very little behaviour to move, mostly a type to change. The correction
above does not weaken that — it adds one method to the surface §5.4 introduces.

## 5. Proposed design

### 5.1 The token

Declared in `io.evitadb.spi.store.engine.model`, beside `EngineState` — not in `io.evitadb.core.engine`.
`EngineState` holds it and the SPI signatures carry it, so the resolver package must stay deletable at step 4
without relocating the token.

```java
/** Opaque identity of the directory a catalog's data lives in. Carries no path semantics. */
public record CatalogFolderId(@Nonnull String id) {
    public CatalogFolderId {
        // tokens are persisted and later resolved against the storage root - a token that can
        // smuggle a traversal is a stored vulnerability, so reject it at construction
        Assert.isPremiseValid(
            !id.isBlank() && !id.contains("/") && !id.contains("\\") && !id.contains(".."),
            "Catalog folder id `" + id + "` is not a valid single directory name!"
        );
    }
}
```

A record rather than a bare `String`, so a folder id and a catalog name cannot be swapped at a call site —
they are both strings and, for legacy catalogs, briefly equal, which is exactly when a mix-up would go
unnoticed. The compact constructor is what makes the token safe to persist: it is written to engine state,
round-trips through a serializer, and is later joined onto the storage root by the store module, so validating
once at construction is cheaper than trusting every join site.

`EngineState` maps `catalogName → CatalogFolderId`. Everything §3.2 of the design specifies about the *shape*
of that token (`<catalogName>_<generation>`) becomes a private detail of `evita_store_server`.

### 5.2 The seam

`CatalogFolderResolver.folderFor(name) → Path` becomes a token lookup returning `CatalogFolderId`.

**The interim identity mapping loses its `Path` immediately.** Under B it needs no `storageDirectory`
argument at all — `CatalogFolderResolver.identity()` is simply `CatalogFolderId::new` applied to the catalog
name. The last `Path` leaves the seam on day one.

That also settles a question left open under option A. The identity factory was scaffolding whose disposal
had to be planned: deprecate it, or delete it and rework its 14 test call sites? Under B it holds nothing and
resolves nothing, so step 4 is a **pure deletion** with no deprecation and no migration target. The interface
itself very likely goes with it: once `EngineState` carries the map, the lookup is a plain engine-state read
and the resolver's only purpose — hiding the mapping behind a method — is gone.

### 5.3 The storage SPI

The `@Nonnull Path catalogStoragePath` parameters added to `CatalogPersistenceServiceFactory` in step 1
(`createNew`, `load`, `upgradeStorageProtocol`, `restoreCatalogTo`) become `@Nonnull CatalogFolderId folderId`.
`DefaultCatalogPersistenceService` resolves the token against `storageOptions.storageDirectory()` internally.

**This is where the payoff is.** Every folder-shaped rule the remaining steps introduce then lands in the
store module *by construction* rather than by discipline:

* §3.2 `<catalogName>_<generation>` naming and burn-and-skip allocation
* §3.2 legacy/foreign folder adoption and the boot-time rename
* §3.3 `*.boot` globbing for the storage prefix
* §3.5 `.provisional` markers and the six-way boot classification
* the tombstone drain

Under option A each of these has a natural pull toward the engine, because the engine is already the thing
holding paths.

### 5.4 One folder-lifecycle surface, not scattered operations

The three filesystem operations from §4 become a single small surface on the **engine-level** persistence
service (`io.evitadb.spi.store.engine.EnginePersistenceService`) — the topology-level service where steps 5–6
put the boot classification and the tombstone drain anyway. `CatalogPersistenceServiceFactory` stays a pure
"open or create a catalog by token" contract and gains nothing:

```java
CatalogFolderId allocateCatalogFolder(@Nonnull String catalogName);   // §5.5 burn-and-skip
boolean catalogFolderExists(@Nonnull CatalogFolderId folderId);       // RestoreCatalogSchemaMutationOperator:88
void dropCatalogFolder(@Nonnull CatalogFolderId folderId);            // UnusableCatalog:172, later the drain
long catalogFolderSize(@Nonnull CatalogFolderId folderId);            // UnusableCatalog:291
```

**`UnusableCatalog` is constructed with a handle to this surface**, supplied by the operators that build it.
No new wiring direction appears — those operators already reach the persistence layer today through their
`storageDirectory` field. This is the centre of the diff: it changes what the placeholder's constructor
receives, which is why §10.1's corrections had to land before implementation rather than during it.

**`terminateAndDelete()` does not move.** It is a `CatalogContract` method (`evita_api`, `CatalogContract:203`)
implemented by both `Catalog:1120` and `UnusableCatalog:171` and invoked polymorphically
(`RemoveCatalogSchemaMutationOperator:125`). The contract method stays exactly where it is; only its *body*
delegates to `dropCatalogFolder`. `UnusableCatalog` is by definition the catalog without a persistence
service, so it cannot acquire the operation any other way than by injection.

This is not transitional scaffolding: `README.md` §4.4 already plans for this delete to become a
tombstone commit plus a boot-time drain, and `dropCatalogFolder` is the same seam that work needs.

### 5.5 Folder allocation

§3.2 allocates by burning a generation per attempt and skipping any candidate that already exists:

```java
do {
    generation = sequence.incrementAndGet();
    candidate  = storageDirectory.resolve(catalogName + '_' + generation);
} while (Files.exists(candidate));
```

Under B this lives in `evita_store_server` and returns a `CatalogFolderId`; the engine commits that token to
`EngineState`. The `Files.exists` half is filesystem work and belongs there; the sequence half is drawn from
the engine-scoped `SequenceService`.

**Dependency direction verified:** `io.evitadb.core.sequence` is exported (`evita_engine/module-info.java:62`)
and `evita_store_server` already depends on `evita_engine`, so this needs no new dependency edge and does not
invert the existing one.

**Open at step 5: how the generation reaches the loop.** The store module may call the engine-scoped
`SequenceService` directly (legal, per the export above), or `allocateCatalogFolder` may take an
`IntSupplier` and let the engine own sequencing outright — which keeps the persisted per-catalog peaks in
`EngineState` unambiguously engine-side and makes the allocation loop testable without an engine service.
The second is preferable on both counts but nothing in this proposal depends on the choice, so it is
deliberately deferred to the step that writes the allocator.

### 5.6 The boundary rule, stated once

The rule this proposal establishes is **not** "no `java.nio.file.Path` in the engine". It is:

> The engine must never hold a path *derived from a catalog's identity*.

Paths that are configuration or exchange artifacts remain legitimately engine-visible:
`storageOptions.storageDirectory()` (configuration), `restoreCatalogTo`'s `pathToFile` (a backup zip handed
in from outside), and export-directory paths. This belongs in `CatalogFolderId`'s JavaDoc, because the next
contributor reading "no paths in the engine" will otherwise "fix" one of the legitimate ones.

## 6. API impact

`UnusableCatalog.getCatalogStoragePath()` is **client-visible**, which is the one place B is not free:

| Surface | Site | Current behaviour |
|---|---|---|
| GraphQL | `SystemGraphQLSchemaBuilder:171` | `getCatalogStoragePath().toString()` |
| REST | `CatalogJsonSerializer:99` | serialises the `Path` |
| gRPC | — | not exposed |
| Descriptor | `UnusableCatalogDescriptor:42` | `nonNull(String.class)`, described "Path to original catalog." |

### Decision: remove the field from the APIs outright — an accepted breaking change

The empty-string compromise was considered and **rejected**: a non-null field that is always empty is a
zombie. It keeps the field in every client's generated types and schema documentation, tells nobody why it
went blank, and defers the removal to a release where it will be more surprising, not less. If the value is
gone, the field should be gone.

**GraphQL and REST — removed.** This is a breaking change and is accepted as one.

* `UnusableCatalogDescriptor` — the `CATALOG_STORAGE_PATH` descriptor (`:42–48`), its `staticProperties`
  entry (`:63`), and the prose at `:61` that directs clients to it.
* `SystemGraphQLSchemaBuilder` — `UNUSABLE_CATALOG_STORAGE_PATH_DATA_FETCHER` (`:171`) and its registration
  (`:423–424`).
* `CatalogJsonSerializer` — the REST field (`:99`).

Verified as the complete surface: no GraphQL schema snapshot, REST OpenAPI fixture, or file under
`documentation/user/` references the field, so nothing else changes behaviour silently. gRPC never exposed it.
The issue needs the `breaking change` label per `.claude/rules/git-workflow.md`.

**The Java accessor — removed too, replaced by `getCatalogFolderId()`.**

An earlier revision of this proposal kept `UnusableCatalog#getCatalogStoragePath()` alive as
`@Deprecated(since = "2026.2")` returning an empty path, as a courtesy to embedded users. That is dropped,
for two reasons:

* **It is the same zombie the paragraph above rejects**, in Java form. An accessor that compiles and returns
  `Path.of("")` misleads more effectively than one that is gone, because the caller gets no signal at the
  point of use.
* **There is no population it would protect.** `getCatalogStoragePath()` has zero callers outside the three
  files being edited above plus one test, and it is absent from `CatalogContract` in `evita_api` — reaching
  it requires downcasting `CatalogContract` to the concrete engine class `UnusableCatalog`. Any caller doing
  that is already bound to internals that this work changes anyway.

`getCatalogFolderId()` replaces it. Embedded callers who genuinely need the on-disk directory should be
asking the storage layer, which is the point of the whole proposal.

**`getCause()` changes signature too.** `UnusableCatalog#getCause()` returns
`BiFunction<String, Path, RuntimeException>` (`UnusableCatalog:88`, public via `@Getter`); the `Path` becomes
`CatalogFolderId`. Same zero-population argument as the accessor, listed here so the inventory is complete.

**`CatalogStatistics.sizeOnDiskInBytes` for corrupted catalogs — preserved, via `catalogFolderSize`.**
`UnusableCatalog#getStatistics()` (`:291`) currently computes this with `FileUtils.getDirectorySize`, and it
reaches clients through `EvitaManagement:467`. §5.4's `catalogFolderSize` keeps the value real. The
alternative — reporting `-1` — was rejected: it is a second client-visible regression and should not happen
as a side effect of a type change. Worth noting for whoever implements it that `-1` is *already* the
convention for the three adjacent unavailable fields (`UnusableCatalog:288-290`), so falling back to it would
be locally consistent rather than novel; that makes it a tolerable retreat if the SPI method proves awkward,
not a reason to choose it up front.

**Error messages** built by the `cause` `BiFunction` carry the **folder token** instead of the path — but not
the token alone. The engine legitimately knows `storageOptions.storageDirectory()`; that is configuration,
not layout (§5.6). So the message states both facts side by side:

```
storage folder `products_3` (storage root: `/data/evita`)
```

The engine never computes the join — knowing the root is configuration, knowing the join rule is layout — yet
an operator reading a support ticket gets everything the absolute path used to give them. This removes most
of the ergonomic risk listed in §8.

## 7. What does not change

* `EngineState` remains the sole commit authority for the name→folder binding.
* Rename and replace still become a single engine-state commit with **no data copied**.
* `Catalog#catalogId` keeps its existing preserve-across-rename-and-replace semantics.
* The §3.7 concurrent-reader acceptance criterion is untouched.
* `CatalogContract` is unchanged — `terminateAndDelete()` stays on the contract (§5.4); only its body in
  `UnusableCatalog` delegates rather than calling `FileUtils` directly.
* `CatalogStatistics.sizeOnDiskInBytes` still reports a real size for corrupted catalogs (§6).
* No change to catalog on-disk format, `STORAGE_PROTOCOL_VERSION`, or the `Migration_20XX_Y` ladder.

**One format claim needs stating precisely rather than waved past.** `EngineState` *does* change shape under
this proposal — its name→folder map holds a `CatalogFolderId` rather than a path-or-name string. That is an
engine-state serialization change, and it is only free because step 4 adds a new `EngineStateSerializer` to
the ladder regardless of which option is chosen. The net cost versus option A is zero **today**; it is one
extra serializer-ladder entry if this is adopted after step 4 has landed. Nothing about the *catalog* storage
format or its migration ladder is affected either way.

Beyond that, this proposal changes **which module knows what a directory is called**. Nothing else.

## 8. Cost, sequencing and risk

**Cost:** roughly a day and a half. The original estimate of a day covered a type change across the ~26 files
step 1 already touched. The review's corrections add real scope on top of that: a new SPI surface on
`EnginePersistenceService` (§5.4), a constructor change on `UnusableCatalog` propagated to every site that
builds a placeholder, and the statistics path (§6) that the first audit missed. Still rework rather than new
ground, and no step beyond 1 has started, so nothing else has to be unwound.

**Why now rather than later:** step 4 rewrites `EngineState`'s Kryo serializer regardless, so changing what
the map stores is nearly free today. After step 4 it costs an additional entry in the serializer version
ladder. It also blocks step 2 — not because step 2 touches folder identity (it touches file names *inside* a
folder) but because step 2 would build on the very SPI signatures this reshapes.

**Risks:**

* The client-visible field change in §6 — the only outward-facing effect, and deliberate.
* `UnusableCatalog` is the placeholder used during failure and recovery paths; changing what it carries
  touches error reporting on paths that are, by definition, already going wrong. Test coverage here is worth
  checking rather than assuming.
* Diagnostic value: an operator debugging a broken catalog currently sees an absolute path in the error.
  **Largely neutralised** by pairing the token with the storage root in the message (§6) — the two facts
  together carry everything the absolute path did, without the engine performing the join.
* `UnusableCatalog`'s constructor gains a collaborator (§5.4). Every site that builds a placeholder changes,
  and those sites are on failure and recovery paths, so a wiring mistake shows up only when something else
  has already gone wrong. This is the part of the diff worth the most test attention.

**The argument against doing it at all**, stated fairly: it is a day spent on layering while #649 itself is
still unfixed, and the leak it removes is one the codebase has lived with across 13 sites for a long time.
The counter is that the leak is about to be *codified* into a new SPI contract, and unwinding it after steps
4–7 is materially more expensive than unwinding it now.

## 9. Settled questions

**The token is the minimum representation, not merely a preferred one.** The obvious weaker form — the engine
holds `(catalogName, generation)` and the store module owns even the textual shape — cannot represent the
folders the settled design requires. An adopted foreign folder keeps its bare, suffix-free name, and a legacy
folder whose boot rename fails "simply records the folder under its bare name" (`README.md` §3.2, §3.5).
Neither has a generation. Representing them would need a sentinel generation value, which is the token's
textual form leaking back into the engine as a special case — the exact thing the boundary exists to prevent.
A free-form opaque string is therefore the floor. **Settled: token, as proposed in §5.1.**

**Removing the API field is right, and should ship in one break.** The zero-caller claim was re-verified:
outside the three API files and one test, nothing reaches `getCatalogStoragePath()`. (The two same-named hits
in `evita_store_server` are a different method on `DefaultCatalogPersistenceService`.) A permanently-blank
field is documentation-backed misinformation. Land the removal in the same release as the rest of #649 so
clients absorb one break rather than two, and label the issue `breaking change`.

**Error-message ergonomics are resolved** by carrying the storage root alongside the token (§6), rather than
by accepting the loss.

**Deferred by design:** how the generation reaches the allocation loop (§5.5) — a `SequenceService` call from
the store module, or an `IntSupplier` parameter. Decided when step 5 writes the allocator.

---

## 10. Architectural review

**Verdict: adopt Option B.** The layering argument is correct, the timing argument is decisive — the leak
is about to be frozen into a new SPI contract and a new `EngineState` serializer at the one moment changing
both is free — and §4's central observation survives scrutiny: the engine carries folder paths, it does not
*use* them. Every factual claim in §3, §5.5 and §6 was re-verified against the tree and holds, with the two
exceptions below.

### 10.1 Corrections — the audit in §4 is incomplete

**There is a third filesystem operation, and it is the one with client-visible output.**
`UnusableCatalog#getStatistics()` calls `FileUtils.getDirectorySize(this.catalogStoragePath)`
(`UnusableCatalog:291`) to fill `CatalogStatistics.sizeOnDiskInBytes`, and it is reachable through
`EvitaManagement:469` — the system-API catalog listing includes corrupted catalogs. Under B the placeholder
can no longer compute this. The options are (a) a store-side size operation (§10.3), or (b) reporting `-1`
like the other unavailable statistics fields — but (b) is a *second* client-visible regression and must be
a conscious decision, not a casualty of the type change. Recommendation: (a); the callers that construct
`UnusableCatalog` are engine operators that can hand it the store-backed operations handle.

**`terminateAndDelete()` cannot simply "move down" — it is a `CatalogContract` method** (`evita_api`,
`CatalogContract:203`), invoked polymorphically (`RemoveCatalogSchemaMutationOperator:125`). §5.4 says the
delete "becomes a named operation on the storage SPI" but not who calls it: `UnusableCatalog` is by
definition the catalog *without* a persistence service. The contract method has to stay; its implementation
needs a store-backed collaborator injected at construction (§10.3). Note the settled design already plans
for this delete to become tombstone-commit-plus-boot-drain (README §4.4), so the injected operation is the
same seam that work needs anyway — this is not transitional scaffolding.

**One §6 omission:** `UnusableCatalog#getCause()` returns `BiFunction<String, Path, RuntimeException>` —
the `Path` in that public signature changes to `CatalogFolderId` too. Same zero-population argument as the
accessor, but it belongs in the §6 inventory.

### 10.2 Answers to the open questions

**Q1 — the opaque token is not merely the right cut; the weaker form is insufficient.** The settled design
requires the map to hold folder names that are *not* `<name>_<generation>`: an adopted foreign folder keeps
its bare, suffix-free name, and a legacy folder whose boot rename fails "simply records the folder under
its bare name" (README §3.2, §3.5). A `(name, generation)` pair cannot represent a bare folder without a
sentinel generation — which is the token's textual form leaking back in as a special case. The free-form
opaque string is therefore the *minimum* representation that covers the design, and the record wrapper is
right for the swap-proofing reason §5.1 gives. Settled: token, as proposed.

**Q2 — removal is right.** The zero-caller claim was re-verified: outside the three API files and one test,
nothing reaches `getCatalogStoragePath()` (the two `getCatalogStoragePath()` hits in the store module are a
different method on `DefaultCatalogPersistenceService`). A permanently-blank field is documentation-backed
misinformation. Land the removal in the same release as the rest of #649 so clients absorb one break, not
two.

**Q3 — acceptable, with one cheap improvement.** The engine legitimately knows
`storageOptions.storageDirectory()` — that is configuration, not layout. An error message may therefore
state both facts side by side: `storage folder 'products_3' (storage root: '/data/evita')`. The engine
never computes the join — knowing the root is config, knowing the join rule is layout — but the operator
reading a ticket gets everything the absolute path gave them. This removes most of the §8 ergonomic risk.

### 10.3 Refinements to the design

**(a) Consolidate the folder-lifecycle operations on the engine-level SPI instead of scattering them.**
§5.4 and §5.5 add operations piecemeal. Group them as one small surface on the engine-level persistence
service (`io.evitadb.spi.store.engine.EnginePersistenceService` — the topology-level service, where the
boot classification and tombstone drain of steps 5–6 will live anyway), not on
`CatalogPersistenceServiceFactory`, which stays a pure "open/create a catalog by token" contract:

```java
CatalogFolderId allocateCatalogFolder(@Nonnull String catalogName);   // §5.5 burn-and-skip
boolean catalogFolderExists(@Nonnull CatalogFolderId folderId);       // RestoreCatalogSchemaMutationOperator:88
void dropCatalogFolder(@Nonnull CatalogFolderId folderId);            // UnusableCatalog:172; later the tombstone drain
long catalogFolderSize(@Nonnull CatalogFolderId folderId);            // UnusableCatalog:291 — the missed operation
```

`UnusableCatalog` is constructed with a handle to this surface (or the two lambdas it needs), supplied by
the operators — which already reach the persistence layer today via their `storageDirectory` field, so no
new wiring direction appears.

**(b) Place `CatalogFolderId` beside `EngineState`** — in the SPI model package
(`io.evitadb.spi.store.engine.model`), not in `io.evitadb.core.engine`. `EngineState` holds it and the SPI
signatures carry it; the resolver package should be deletable at step 4 without relocating the token. Give
the record a compact constructor that rejects empty values and path-separator characters (`/`, `\`, `..`) —
tokens are persisted and round-trip through serializers, so a token that can smuggle traversal is a stored
vulnerability, and the check is one line.

**(c) The identity resolver loses its `Path` immediately.** Under B the interim identity mapping needs no
`storageDirectory` argument at all: `CatalogFolderResolver.identity()` is simply `CatalogFolderId::new` on
the catalog name. That deletes the last `Path` from the seam on day one and makes the §5.2 step-4
disappearance a pure deletion.

**(d) Hand the generation into allocation as a supplier.** Rather than store code calling the engine-scoped
`SequenceService` instance directly (legal, per the verified export, but it couples the store to a concrete
engine service and leaves open *how* it obtains the instance), pass `IntSupplier generation` — or the
service — as a parameter of `allocateCatalogFolder`. Sequencing ownership, including the persisted peaks in
`EngineState`, then stays unambiguously engine-side, and the store's allocation loop is testable without an
engine service. Preference, not a blocker.

**(e) State the boundary rule once, so it does not erode:** the rule is not "no `Path` in the engine" — it
is "no *catalog-layout* `Path` in the engine". `storageOptions.storageDirectory()` (configuration),
`restoreCatalogTo`'s `pathToFile` (an exchange artifact — a backup zip), and export-directory paths are all
legitimately engine-visible. What the engine must never hold is a path *derived from* a catalog's identity.
Worth a sentence in `CatalogFolderId`'s JavaDoc, because the next contributor will otherwise "fix" one of
the legitimate `Path` parameters.

### 10.4 Recommendations

1. **Adopt Option B now**, before step 2, for the sequencing reason §8 gives. The rework cost is credible.
2. **Fix the §4 audit first**: fold the `getDirectorySize` statistics path and the `terminateAndDelete`
   contract constraint (§10.1) into the design before implementation starts — both change what
   `UnusableCatalog`'s constructor must receive, which is the center of the ~26-file diff.
3. **Adopt the consolidated folder-lifecycle SPI surface** (§10.3a) on `EnginePersistenceService` instead
   of the piecemeal §5.4/§5.5 operations, and construct `UnusableCatalog` with that handle.
4. **Accept the §6 API removals as specified**, adding `getCause()`'s signature change to the inventory,
   and ship them in the same release as the rest of #649 with the `breaking change` label.
5. **Carry the storage root alongside the token in error messages** (§10.2 Q3) to neutralise the main
   ergonomic risk in §8.
6. **Settle open question 1 as "token, as proposed"** — the weaker `(name, generation)` form cannot
   represent adopted bare folders and is ruled out by the settled design itself, not by preference.
7. When this proposal is folded into the eventual ADR, the `(name, generation)`-insufficiency argument and
   the `CatalogContract#terminateAndDelete` constraint are the two pieces of reasoning most worth keeping —
   both are invisible in the final code and both will otherwise be re-litigated.
