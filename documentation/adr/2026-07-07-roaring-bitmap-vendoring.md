---
title: Vendor RoaringBitmap as a full, renamed copy instead of a thin JPMS subclass
date: 2026-07-07
updated: 2026-07-31 19:28
status: accepted
kind: infrastructure
issues: [1252]
prs: [1267, 1316]
areas: [evita_roaring_bitmap, evita_engine]
supersedes: []
superseded-by: []
relates: []
---

# Vendor RoaringBitmap as a full, renamed copy under `io.evitadb.roaringbitmap`

evitaDB now ships its own copy of the RoaringBitmap classes it needs, under package
`io.evitadb.roaringbitmap` in a new standalone module `evita_roaring_bitmap/`, reshaped into two
persistent (copy-on-write, structure-sharing) bitmap classes — `PersistentRoaringBitmap` (32-bit)
and `PersistentLongRoaringBitmap` (64-bit) — instead of depending on upstream `org.roaringbitmap`
directly.

## Why

evitaDB needed a persistent/structure-sharing bitmap: binary operations that share unchanged
containers between the operands and the result instead of copying them, so a transactional read-only
snapshot doesn't pay for containers a write didn't touch. An agent-written prototype,
`CopyOnWriteRoaringBitmapV2`, demonstrated the approach by extending upstream `RoaringBitmap` and
reaching into its package-private internals — `highLowContainer` (~89 uses) and `RoaringArray`
methods (~100+ uses).

That access pattern collides with the module system: both upstream RoaringBitmap and `evita_engine`
are JPMS modules, and a class in `io.evitadb.*` cannot reach into `org.roaringbitmap`'s
package-private members from a different module — Java forbids a package split across modules, and
there is no way to "reopen" a package to add members from outside it. A thin subclass living beside
upstream was therefore not an option to weigh against vendoring on style grounds; it does not compile
on the modulepath.

A second constraint shaped the rollout, not the architecture: the vendoring had to land without
touching any file outside a brand-new module, so it could proceed in parallel with the large,
concurrent `#760` branch (granular paged `HistogramIndex` storage) without conflicting with it.

### Previous state

evitaDB depended on the upstream `org.roaringbitmap:RoaringBitmap` Maven artifact directly; call
sites across `evita_engine`/`evita_store` used `org.roaringbitmap.RoaringBitmap`,
`org.roaringbitmap.longlong.Roaring64Bitmap` and friends with no persistence/structure-sharing
behavior.

## Options considered

### Option A — full vendor: copy, rename, and own the classes (chosen)

Copy the minimal class closure needed (32-bit core + `longlong` + `art`), rename the package to
`io.evitadb.roaringbitmap`, rename the two root classes to `PersistentRoaringBitmap` /
`PersistentLongRoaringBitmap`, and fold the `CopyOnWriteRoaringBitmapV2` prototype's overridden
methods directly into the renamed base class.

- **Pros:** the reshape keeps full access to the internals it needs because they are now defined in
  the same module; the result is a normal, single-package-exporting JPMS module; evitaDB owns the
  structure-sharing behavior outright rather than depending on an upstream release for something
  upstream doesn't have.
- **Cons:** vendoring a fork's worth of source means manually tracking upstream fixes going forward
  (this is what motivated building the `roaring-bitmap-sync` skill as part of the same effort).

### Option B — thin subclass beside upstream (declined)

Keep upstream `org.roaringbitmap` as a normal dependency and add the persistent behavior as a
subclass/wrapper living in evitaDB's own package.

- **Pros:** no vendoring, no fork maintenance, upstream fixes arrive via a version bump.
- **Cons / Rejected because:** the persistent behavior needs deep, repeated access to
  `RoaringBitmap`'s and `RoaringArray`'s package-private internals; with both sides as JPMS modules,
  that access is illegal on the modulepath — not a style preference, a compile-time modularity
  constraint. There is no split-package escape hatch for two different modules to share
  `org.roaringbitmap` package-private state.

## Decision

**Chosen: Option A.** The deciding driver is not a trade-off between the two options — Option B does
not compile under JPMS given the depth of internal access the persistent design needs. Vendoring was
the only path left, and the plan (revised once by Johnny on 2026-06-27) narrowed the vendored shape
to exactly two classes: fold the COW prototype into the renamed root class instead of keeping it
separate, and drop `buffer.*`, `insights.*`, `FrozenRoaringBitmap` and `FastRankRoaringBitmap` as
unused by evitaDB.

Both the vendoring itself (module scaffold, rename, fold, tests, sync skill — the "Part 1" scope of
the originating plan) **and** the call-site integration the plan explicitly deferred as a separately
gated "Part 2" (registering the module in the root reactor, migrating the `org.roaringbitmap` call
sites in `evita_engine`/`evita_store`, `module-info` wiring) shipped together in the same PR — see
Timeline. The plan's staged rollout was a sequencing device to avoid conflicting with the concurrent
`#760` branch; both stages landed once that was no longer a concern.

## Key technical details

- Module `evita_roaring_bitmap/` (Maven artifactId `evita_roaring_bitmap`, JPMS module name
  `evita.roaringbitmap`) is registered in the root reactor (`pom.xml:187`,
  `<module>evita_roaring_bitmap</module>`) and exports only `io.evitadb.roaringbitmap`
  (`evita_roaring_bitmap/src/main/java/module-info.java`). `evita_engine/src/main/java/module-info.java:131`
  declares `requires evita.roaringbitmap;`.
- Two bitmap classes only: `PersistentRoaringBitmap` (32-bit; the `CopyOnWriteRoaringBitmapV2`
  prototype is folded in and deleted as a separate class) and `PersistentLongRoaringBitmap` (64-bit,
  ART-based, hoisted from the upstream `longlong` package to the exported root package). Dropped
  entirely: `buffer.*` (Mutable/Immutable/Mappeable), `insights.*`, `FrozenRoaringBitmap`,
  `FastRankRoaringBitmap`, `longlong.Roaring64NavigableMap` — none of these exist anywhere under
  `evita_roaring_bitmap/src`.
- JPMS reshaping beyond a plain package rename (evita-specific; the sync ledger flags these as things
  to preserve on every future re-sync): several internal classes demoted `public` → package-private;
  upstream's JDK8 `ArraysShim` replaced by `java.util.Arrays`; the 64-bit public API hoisted out of
  `longlong` into the root exported package. A further cleanup — hiding the remaining
  still-`public`-but-internal classes (`Container` hierarchy, `Util`, the char-iterator types,
  `FastAggregation`/`BitSetUtil`) — is tracked as open in `UPSTREAM_SYNC.md` under the heading
  "Still public-but-internal (TODO Part 2)". That "Part 2" refers to this further encapsulation pass,
  **not** the call-site-integration Part 2 described above, which already shipped — the two are easy
  to conflate from the name alone.
- In-tree call sites are fully migrated off `org.roaringbitmap.*`; a repo-wide search finds no
  remaining reference in `evita_engine`/`evita_store`. The two holdouts are performance-spike files —
  `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/RoaringBitmapInsert.java` (imports
  `org.roaringbitmap.buffer.ImmutableRoaringBitmap`/`MutableRoaringBitmap`) and
  `.../spike/QuickSortOrPresort.java` (imports `org.roaringbitmap.FastRankRoaringBitmap`) — which
  intentionally reference classes the vendored subset dropped, which is why root `pom.xml`'s
  `roaringbitmap.version` property (currently `1.6.18`, ahead of the vendored fork point) still
  resolves an upstream dependency, scoped to that one module's `pom.xml`.
- Attribution: `evita_roaring_bitmap/LICENSE`, `AUTHORS`, `NOTICE` are present; `NOTICE` records the
  fork commit (`f27cd538`, = upstream v1.6.12) and the currently reviewed-through upstream commit.
- `evita_roaring_bitmap/UPSTREAM_SYNC.md` is the living source of truth for re-syncing with upstream:
  base/reviewed-through commit coordinates, the upstream→vendored class-name mapping, and every
  "evita divergence to preserve on re-sync" found while porting (a COW-adapted `mergeBulk`, the
  `shared[]` copy-on-write flag-array invariant, and three real bugs the port surfaced and fixed —
  a reverse-cursor exhaustion defect, a `clone()` shallow-copy aliasing defect in the iterator
  flyweights, and an `orNot` array-install inconsistency). It is replayed by
  `.claude/skills/roaring-bitmap-sync/SKILL.md`, which this record does not duplicate.

## Verification

Not re-run as part of this conversion (out of scope for a documentation migration); verified by
presence instead. The module's test tree
(`evita_roaring_bitmap/src/test/java/io/evitadb/roaringbitmap/`) contains the ported upstream suite
plus evita-authored tests for the persistent-reshape divergences, confirmed present by file:
`TestPersistentRoaringBitmap`, `MergeBulkCopyOnWriteTest`, `ReverseAdvanceIfNeededContractTest`,
`IteratorCloneIndependenceTest`, `ContainerBinaryOpFreshnessTest`, `SharedFlagPrecisionTest`,
`SharedContainerLockstepFuzzTest`. The module is deliberately outside evitaDB's own coverage
accounting (`9b8d9154f`, "exclude vendored RoaringBitmap and attribute evita_test_support coverage")
and its tests are skipped by CI on pushes/PRs that don't touch the module (root `pom.xml`'s
`roaringBitmap.skipTests` property; `4d7637f87`, `e3e1d7681`).

## Consequences & open follow-ups

- Re-syncing with upstream is a manual, skill-assisted process, not automatic — `evita_roaring_bitmap`
  will drift from upstream RoaringBitmap releases until someone runs `roaring-bitmap-sync`. As of the
  last recorded sync (`UPSTREAM_SYNC.md`, review 2), the vendored code was reviewed through commit
  `ba92f497` (effective coverage v1.6.15), while `evita_test/evita_performance_tests`'s upstream dependency
  has since moved to `1.6.18`.
- The `shared[]` copy-on-write ownership-flag array is a hand-maintained invariant, not something the
  type system enforces: the sync skill calls it "the recurring landmine" — any code that grows
  `highLowContainer` must keep `shared[]` sized in lockstep, or risk an `ArrayIndexOutOfBoundsException`
  (it already has, four times during porting).
- Further encapsulation cleanup on the exported package is explicitly deferred — see the
  "Still public-but-internal" note under Key technical details.

## Related work

- `.claude/skills/roaring-bitmap-sync/SKILL.md` — the skill that replays upstream commits onto this
  vendored copy; read `evita_roaring_bitmap/UPSTREAM_SYNC.md` first, as the skill itself instructs.
- PR #1267's branch (`1252-cheap-savepoint-snapshots-eliminate-the-on-per-entity-rollback-cliff`) also
  carried unrelated STM savepoint-snapshot work and a merge of the concurrent `#760`
  (granular paged `HistogramIndex` storage) branch under the same issue #1252 umbrella. This record
  covers only the RoaringBitmap vendoring thread of that PR.

## Timeline

- **2026-06-27** — module scaffolded; vendor closure copied verbatim; package renamed to
  `io.evitadb.roaringbitmap`; `buffer`/`FastRankRoaringBitmap` stripped; root classes renamed;
  `CopyOnWriteRoaringBitmapV2` folded into `PersistentRoaringBitmap` and deleted as a separate class;
  JPMS `module-info`, `NOTICE` and attribution added; upstream test suite ported (all same day)
- **2026-07-03** — JPMS export surface tightened and the upstream-sync ledger first recorded
  (`f72cb4ca4`); evitaDB call sites migrated onto the vendored module (`4ee2f8ec8`)
- **2026-07-06** — vendored module brought to evitaDB's house coding standard (`e2ad31383`)
- **2026-07-07** — PR #1267 merged into `dev`: module registered in the root reactor,
  `evita_engine` now `requires evita.roaringbitmap`
- **2026-07-10 to 2026-07-16** — CI tuned for the module's test cost (thread fan-out and heap bump,
  coverage-accounting exclusion, doc-CI skip)
- **2026-07-24** — PR #1316 merged: upstream re-sync "review 2" through commit `ba92f497`
  (effective coverage v1.6.15), incorporating three upstream fixes and porting RoaringBitmap issue
  #837
- **2026-07-31** — planning document retired, replaced by this record
