# OffsetIndex CHAMP persistent map (kill the O(N) promote copy → collapse MVCC into versioned roots)

Status: **design agreed, no production code yet** (2026-05-31). Reference material cloned.
Branch: `760-more-optimized-data-structures-...`.
Design fork decided: **CHAMP** (Steindorfer & Vinju, OOPSLA'15), reimplemented clean-room from the **Scala stdlib**
reference (Apache-2.0) — *not* the in-repo Clojure HAMT (EPL). Reference clone: `documentation/scala/` (sparse, untracked).

Related memory: `[[offsetindex-champ-map-port]]`. Sibling #760 structures it mirrors: `[[unordered-lookup-twotree-integration]]`
(path-copying COW, no parent pointers — same philosophy).

> **Two stages, sequenced for risk.**
> **Stage 1** — build a clean-room CHAMP immutable map behind the `java.util.Map<RecordKey,FileLocation>` interface and
> drop it in for `keyToLocations` only. Kills the O(N) per-flush copy; changes nothing about `VolatileValues`; proven by
> the existing `LongRunningOffsetIndexTest`. **Low risk.**
> **Stage 2** — the architectural rewrite (Johnny's idea): collapse `SharedState` + the `VolatileValues` MVCC machinery
> into **versioned CHAMP roots**. Deletes the `PastMemory` historical-diff subsystem and its lock; reduces `VolatileValues`
> to a working-root + durability watermark + statistics sidecar. **This is where most of the code-deletion payoff lives**,
> and it must rest on a Stage-1-proven CHAMP foundation.

---

## 1. Problem

`OffsetIndex.promoteNonFlushedValuesToSharedState` (`evita_store/evita_store_key_value/.../offsetIndex/OffsetIndex.java:1427`)
runs on **every flush**. Its first act:

```java
final Map<RecordKey, FileLocation> newKeyToLocations = createHashMap(currentLocations.size() + valueCount);
// "it would be much better to have something like persistent map ..."   ← existing comment, :1434-1435
newKeyToLocations.putAll(currentLocations);                              // :1436  — O(N) deep copy
```

then applies only **M** changes (`:1442-1485`) and republishes via one volatile write (`:1495`). With **M ≪ N** this is
**O(N + M)** plus the allocation/GC of a fresh N-entry `HashMap` whose backing `Node[]` table is, for large N, a **G1
humongous array** — the same pathology #760 fights elsewhere. Net cost per flush today:

| Cost | Where | Magnitude |
|---|---|---|
| Full map copy | `:1436` | O(N) time + O(N) garbage |
| Peak memory during copy | old + new map coexist | ~2N |
| Humongous backing array | `HashMap` table for large N | G1 humongous alloc |

### 1.1 The concurrency contract (must be preserved)

`SharedState` (`:278-281`) pairs `(keyCatalogVersion, keyToLocations)` and is published through **one volatile**
(`:256`) so lock-free readers never observe a torn `(version, locations)` pair (`:247-255`, `:676-678`). The map is
**single-writer** (flush is serialized) and **never mutated in place after publication** (promote always builds a fresh
map). This is exactly the access pattern an immutable, structurally-shared map is built for.

### 1.2 What reads actually do — 3-tier MVCC resolution

`get(catalogVersion, primaryKey, type)` (`:650`) resolves a key against a *version*:

| Tier | Source | Guard | Code |
|---|---|---|---|
| A — non-flushed | `volatileValues.getNonFlushedValueIfVersionMatches` | newer than last flush | `:657` |
| B — historical | `volatileValues.getVolatileValueInformation` (PastMemory side-table) | only if `cv < keyCatalogVersion` | `:679-695` |
| C — current | `sharedState.keyToLocations().get(key)` then filter `generationId ≤ cv` | — | `:697-701` |

Tiers A and B exist as **diff side-tables** *only because* `keyToLocations` is a destructively-replaced `HashMap` and old
snapshots can't be kept cheaply. **That is the constraint CHAMP removes.**

### 1.3 Read surface on `keyToLocations()` (Stage-1 compatibility target)

`get` · `containsKey` · `size` · `keySet` · `values` · `entrySet` · `equals` (~11 sites incl. `:1236`, `:1220`, `:1228`,
`:1159`, `:631`). Mutation only inside promote (`putAll`/`put`/`remove`, `:1436/:1464/:1472/:1449`). Built initially by
`OffsetIndexBuilder.getBuiltIndex()` (`:468`); shared by reference into a copy-constructed instance (`:534`); re-published
unchanged on version bump (`:922`).

---

## 2. Reference material & port scope

Cloned to `documentation/scala/src/library/scala/collection/immutable/` (Apache-2.0; clean-room — read for *algorithm*,
write fresh):

| File | Port | Becomes |
|---|---|---|
| `ChampCommon.scala` (252 L) | ~whole | bit math (`maskFrom`/`bitposFrom`/`indexFrom`, `BitPartitionSize=5`, `BranchingFactor=32`, `MaxDepth=7`), array copy-insert/remove, `ChampBaseIterator`/`...ReverseIterator` (cursor-stack, **no parent pointers**) |
| `HashMap.scala` (2426 L → keep ~30%) | core only | `MapNode` (abstract), `BitmapIndexedMapNode` (datamap/nodemap + `updated` + `removed`-with-compaction + inline↔node migration), `HashCollisionMapNode`, forward iterator, `HashMapBuilder` → our **Builder/Transient** |
| **drop** | — | `concat`/`merged`/`removedAll(that)`/`filterImpl`/`transform`/bulk-equals, `MapOps`/factory/CanBuildFrom plumbing, variance hacks |

Tests cloned to `documentation/scala/test/junit/scala/collection/immutable/`: **`ChampMapSmokeTest.scala`** (port in full —
the canonicalization-on-delete suite), **`CustomHashInt.scala`** (collision-forcing key helper — port), and
`HashMapTest`/`MapTest`/`MapHashcodeTest`/`SmallMapTest` (cherry-pick).

**Home:** `evita_common/src/main/java/io/evitadb/dataType/champ/` (mirrors `dataType/bPlusTree/`; `evita_common` is the
foundational module `evita_store` already builds on). **Naming (proposed):** `ChampMap<K,V> implements Map<K,V>`,
`ChampMap.Builder<K,V>`, package-private `MapNode`/`BitmapIndexedMapNode`/`HashCollisionMapNode`, infra in `ChampNodes`.

**evitaDB adaptations:** generic `<K,V>` but **no null key/value support** (`RecordKey`/`FileLocation` are non-null —
removes Clojure's `hasNull`/`nullValue` branches); implement `java.util.Map` read-views + iterator so it drops into
`SharedState` unchanged; credit Steindorfer / Scala stdlib / Bagwell in JavaDoc.

**Code style (`.claude/rules/code-style.md`):** tabs; ≤100 col; JavaDoc in Markdown; `@Nonnull`/`@Nullable`; no `var`;
`final` locals; **manual `31*h + Type.hashCode(x)`** (never `Objects.hash` on primitives); `StringBuilder` with capacity;
allocation-optimized loops, no streams in hot paths.

---

## 3. Stage 1 — CHAMP behind the `Map` interface (drop-in for `keyToLocations`)

### Phase 0 — clean-room structure
- `ChampNodes` (bit math, array helpers, base iterators) + `MapNode` + `BitmapIndexedMapNode` + `HashCollisionMapNode`.
- `ChampMap<K,V>` implementing `Map<K,V>` **read** surface (`get`/`containsKey`/`size`/`isEmpty`/`keySet`/`values`/
  `entrySet`/`equals`/`hashCode`/`iterator`); mutators (`put`/`remove`/`putAll`/`clear`) throw `UnsupportedOperationException`
  (immutable) **or** return a new instance via `updated`/`removed` — see Phase 3 decision below.
- `ChampMap.Builder<K,V>` (the Transient): `addOne(k,v)` / `removeKey(k)` / `build()`. Ownership guard = the Scala
  `ensureUnaliased` mechanism (≈ Clojure's `AtomicReference<Thread> edit`). **Single-threaded use only.**
- **Sharp edge:** `removed`-with-compaction (inline a single-survivor subnode back into its parent; shrink a
  `HashCollisionMapNode` of arity 2→1 back to an inline entry). Most-failed CHAMP detail — guarded by Phase 1.

### Phase 1 — correctness tests (the safeguard)
- Port `ChampMapSmokeTest` in full → JUnit5 `shouldCompact…` (begin/middle delete + `HashCollisionNode1/2/3`), using a
  ported `CustomHashInt` to force collisions and same-prefix paths.
- Cherry-pick `HashMapTest`/`MapTest`/`MapHashcodeTest`/`SmallMapTest` edge cases.
- **Generational oracle** in `evita_long_running_tests` (`io.evitadb.dataType.champ.LongRunningChampMapTest`), cloned from
  `LongRunningTransactionalMapTest` shape on `TimeBoundedTestSupport.runFor`: random `updated`/`removed` vs a
  `java.util.HashMap` oracle, asserting equivalence after every op **plus retained-snapshot immutability** (keep N prior
  `build()` snapshots; assert each still equals its own oracle after later mutations — the MVCC property). Tags
  `@Tag(DATA_TYPE)` + `@Tag(CONTRACT)` + `@Tag(SLOW)`.

### Phase 2 — benchmarks (decision input)
- Read latency `ChampMap.get` vs `HashMap.get` (expect 1–4×; OffsetIndex is read-heavy on `:697`/`:771`/`:820`/`:1506`/`:1555`).
- Flush cost: `Builder` apply-M-then-build vs today's `putAll(N)` across N ∈ {1e4…1e7}.
- Allocation/GC + **humongous** profile (must show the humongous backing-array site gone).
- Decision: if reads regress unacceptably at small N, keep a size-threshold switch behind the `Map` interface (plain
  `HashMap` below threshold, `ChampMap` above). Default expectation: CHAMP outright.

### Phase 3 — integrate (keyToLocations only)
- `SharedState.keyToLocations` stays typed `Map<RecordKey,FileLocation>`; assign a `ChampMap`.
- `OffsetIndexBuilder.getBuiltIndex()` (`:468`) returns a `ChampMap` (built via `Builder`).
- `promoteNonFlushedValuesToSharedState`: replace `createHashMap` + `putAll` (`:1431-1436`) with
  `current.builder()` → apply the same M `put`/`remove` (`:1442-1485`) → `build()` → publish (`:1495`). **No other logic
  changes.** Mutator-API decision: `SharedState`/promote are the only writers and they go through the Builder, so
  `ChampMap`'s `Map` mutators can safely throw `UnsupportedOperationException` (documents immutability; nothing calls them).
- Run `LongRunningOffsetIndexTest` (drives real flush cycles + verifies historical versions) + the OffsetIndex/STM suites.

**Stage-1 done criteria:** all OffsetIndex + STM + generational suites green; flush no longer O(N); humongous site gone;
read latency within budget. `VolatileValues` untouched.

---

## 4. Stage 2 — collapse the MVCC machinery into versioned CHAMP roots

**Premise (validated against the read path `:650`):** tiers A and B are diff side-tables that exist *only* because the
central map was destructively replaced. Once the central map is an immutable CHAMP root with cheap structural sharing,
**each version is just a retained root**, and version-resolution becomes a root lookup + one `get`.

### 4.1 Target shape

- **`SharedState` → `(version, ChampMap root)`** — literal (already true after Stage 1; Stage 2 generalizes to *many*).
- **A versioned-root registry** replacing `SharedState`'s single slot **and** tier B:
  a small immutable holder `Roots{ long[] versions; ChampMap[] roots }` (sorted ascending), published by **one volatile
  write** — preserving the exact torn-read guarantee of today's `SharedState` (`:676-678`). Historical read =
  `floor(versions, cv)` → `roots[idx].get(key)`. **Lock-free** (deletes the `ReentrantLock` at `:1896` and its critical
  sections in `getVolatileValueInformation`/`countDifference`).
- **Working root for in-flight writes (tier A data):** the writer keeps a `ChampMap workingRoot` layered on the latest
  published root via the `Builder`; reads at the writer's in-flight version use it; on flush it is **published into the
  registry** (reference flip — *no copy, no promote loop*).

### 4.2 What gets DELETED

- `PastMemory` (`:2506`), the `ConcurrentHashMap<Long,PastMemory> volatileValues` (`:1913`), `historicalVersions`
  (`:1920`), `recordHistoricalVersions` (`:1429`), `getVolatileValueInformation` (`:2097`), the `VolatileValueInformation`
  record, the parallel `long[]` version arrays + their `binarySearch` walks (`:1944`/`:1966`/`:2020`/`:2048`), the
  `ReentrantLock`. Tier B in `get`/`getBinary`/sibling lookups (`:679-695`, `:754-768`, `:813`, `:1546`) reduces to one
  registry lookup.
- The O(N) promote copy (already gone in Stage 1) and the per-version add/update/remove diff bookkeeping in
  `NonFlushedValueSet` *for read resolution* (the set may survive only as the carrier of pending writes pre-publish — see
  4.4).

### 4.3 What CANNOT become a bare root pointer (stays as a thin sidecar)

- **Durability watermark** — records are appended to the file but not durable until sync. `lastSyncedPosition` +
  `doSoftFlush()` (`:665-666`) + `RecordNotYetWrittenException` semantics must survive: a working-root hit whose
  `FileLocation.endPosition()` exceeds `lastSyncedPosition` still triggers a soft flush. A root says *what* maps where,
  not *whether the location is fsynced*.
- **Statistics CHAMP does not carry** — `root.size()` gives total count, but **not** the per-`recordType` histogram
  (`countDifference(cv, recordType)`, `:1991`) or byte totals (`nonFlushedRecordSizeInBytes`, `recordLengthDelta`,
  `maxRecordSizeBytes`). Maintain a small per-version stats sidecar on add/remove, or recompute at flush.
- **Observers & purge triggers** — `nonFlushedBlockObserver`, `historicalVersionsObserver`, `purgeOlderThan` (`:1882`),
  oldest-kept-timestamp. Orthogonal; ride alongside. Purge *logic* simplifies to "drop registry roots older than the
  watermark once no reader needs them" (same trigger; GC reclaims only now-unreferenced CHAMP nodes via structural
  sharing).

### 4.4 How each read tier translates (after Stage 2)

| Before | After |
|---|---|
| Tier A diff lookup (`:657`) + sync guard | `workingRoot.get(key)` (if reading in-flight version) + same `lastSyncedPosition` guard |
| Tier B PastMemory reconstruction (`:679-695`) | `registry.floor(cv).get(key)` — one immutable lookup, lock-free |
| Tier C current map + generationId filter (`:697-701`) | `registry.latest().get(key)` (the `generationId ≤ cv` filter is largely subsumed because the chosen root already matches the version; keep as belt-and-suspenders) |

### 4.5 Stage-2 sequencing
1. Introduce `Roots` registry + working-root; route `get`/`getBinary`/`count*` through it; **keep** `VolatileValues`
   fields temporarily, dual-writing, to A/B-compare resolution under `LongRunningOffsetIndexTest`.
2. Move statistics to the sidecar; verify histogram/size/`countDifference` parity.
3. Delete `PastMemory`/historical machinery + lock once parity holds.
4. Re-run full OffsetIndex + STM + generational suites; re-profile.

**Stage-2 done criteria:** historical reads lock-free; `PastMemory` subsystem removed; `VolatileValues` reduced to
working-root + watermark + stats + observers; `LongRunningOffsetIndexTest` (incl. its prior-version verification, ~`:509`)
green; no behavioural change to durability or MVCC semantics.

---

## 5. Cost analysis (before → after)

| Operation | Today | Stage 1 | Stage 2 |
|---|---|---|---|
| Flush promote | O(N) copy + 2N peak + humongous | **O(M·log₃₂N)**, no humongous | same |
| Historical read (tier B) | diff-walk + `binarySearch` + **lock** | unchanged | **O(log V + log₃₂N)**, lock-free |
| Current read | `HashMap.get` | `ChampMap.get` (1–4×) | same |
| Keep K old versions | K diff side-tables (`PastMemory`) | unchanged | K retained roots (structural sharing — cheap) |
| `equals` (`:1236`) | O(N) | O(N) | sub-linear (CHAMP canonical form) |

---

## 6. Risks & guardrails

- **G1 — canonicalization-on-delete** is the correctness crux. Gate: `ChampMapSmokeTest` (ported in full) + delete-heavy
  generational runs must be green before Phase 3.
- **G2 — Builder single-threaded.** The Transient is used only by the serialized writer in promote/build. Document and
  assert ownership (Scala `ensureUnaliased`).
- **G3 — read-latency regression.** Phase 2 benchmark is a go/no-go; size-threshold fallback retained behind the `Map`
  interface if needed.
- **G4 — Stage 2 must not change durability or MVCC semantics.** Dual-write A/B comparison (4.5 step 1) before any
  deletion; `LongRunningOffsetIndexTest`'s prior-version checks are the contract.
- **G5 — safe publication.** The `Roots` registry must be published by a single volatile write (one immutable holder),
  never field-by-field — preserving the torn-read guarantee (`:247-255`).
- **Licensing** — reimplement from algorithm/Scala-reference (Apache-2.0); do not copy the EPL Clojure file. Credit in
  JavaDoc.

## 7. Out of scope
- `HashSet`/CHAMP set variant (only the map is needed).
- Applying CHAMP to other evitaDB maps (`TransactionalMap` keeps its STM design; this is for the non-STM OffsetIndex path).
- CHAMP bulk operations (concat/merged/filter) — not needed by OffsetIndex.
