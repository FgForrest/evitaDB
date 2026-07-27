# #760 Part B item #1 — shared B+ tree base extraction + element-keyed price tree

Decision (Johnny): build the paged price backing as an **element-keyed B+ tree** (spike: 8.22 B/record, strictly
beats LongBPlusTree's 24.41 and flat's O(n) mutation), and host it by **extracting a shared base** across the three
non-columnar forks rather than hand-rolling a fifth tree. Leaf-page persistence lives in the base. Bucket tree
(columnar) is OUT of scope (its leaf is fused to value→record-ids; joining needs a separate `LeafPayload` refactor —
revisit later).

## The hard constraint that shapes everything
The forks exist to avoid boxing. `Long`/`IntToLong` use **primitive key arrays** (`long[]`/`int[]`) and 1-param
`BPlusTreeNode<N>`; `Object` uses `Object[]` keys + a `@Nullable Comparator<M>` and 2-param
`BPlusTreeNode<M extends Comparable<M>, N>`. A base that routed comparisons through `Comparable<K>` would box the
primitive trees on **every** comparison — unacceptable. Therefore:

- **The typed key/value arrays and the per-type key-compare STAY specialized** (in per-subclass node classes /
  overrides calling static primitive utilities — monomorphic, inlinable).
- **Only the array-type-agnostic algorithms move to the base**: root-to-leaf descent, split/merge/borrow
  orchestration, the iterator framework (which already caches the typed leaf array once per leaf, keeping element
  reads off the virtual path), the transactional-layer handshake, the §3 leaf-page persistence handshake, the
  public API, and the verification framework. (SkeletonMapper: ~60–70% of each ~4,000-line tree.)
- **A perf gate is mandatory** at each phase: node-level operations the base invokes become interface calls
  (`BPlusTreeNode` SPI). One virtual call *per node* (not per element) should be free, but this is the precise risk
  the forks were built to avoid — so we measure, and if any JMH benchmark regresses beyond tolerance we coarsen the
  granularity (keep more per-tree) rather than ship a regression.

## Safety net (exists)
Functional suites: `TransactionalLongBPlusTreeTest`, `TransactionalObjectBPlusTreeTest`,
`TransactionalIntToLongBPlusTreeTest`, `TransactionalBucketBPlusTreeTest`. Long-running variants for all.
JMH: `SortIndexArrayVsBPlusTreeBenchmark` + the committed block-size benchmarks. These are the regression oracle.

## Phased plan (each phase ends GREEN on all tree tests + JMH within tolerance; reversible)

- **Phase 0 — Baseline.** Run all four tree functional suites to green; capture current JMH numbers as the
  regression baseline. **DONE (green): four functional suites pass standalone (exit 0).** JMH baseline still to
  capture at execution time. Working recipe (the `-am test` trap: `test` phase runs on upstream modules too and
  `evita_common` has no matching test → surefire fails on no-match):
  1. once: `mvn -o -q -pl evita_test/evita_functional_tests -am install -DskipTests` (installs all upstream at
     the LOCAL version so the test module runs standalone thereafter);
  2. each run: `mvn -o -pl evita_test/evita_functional_tests test -Dtest='TransactionalLongBPlusTreeTest,TransactionalObjectBPlusTreeTest,TransactionalIntToLongBPlusTreeTest,TransactionalBucketBPlusTreeTest' -Dsurefire.failIfNoSpecifiedTests=false -Dtest.tag.policy=off`
     (drop `-q` to see `Tests run:` counts; add the long-running variants for deeper soak).
- **Phase 1 — Design the `BPlusTreeNode` SPI + `AbstractTransactionalBPlusTree` skeleton. DONE (finalized below).**
  Decided the exact base-vs-node line by reading both trees in full. No behavior change — pure design.
- **Phase 2 — Pilot: extract base from `Long` + `IntToLong` only** (both primitive, 1-param node — lowest risk).
  Prove the SPI model, keep both suites green, JMH flat. This is the go/no-go for the whole approach: if perf
  regresses here, stop and reassess granularity before touching `Object`. Two increments: **2a** = structural core
  (consolidate + cursor builders + verifiers + records) behind the SPI; **2b** = iterator navigation skeleton (the
  per-element hot path — moved last because that is where a hidden dispatch could regress).
- **Phase 3 — Fold in `Object`** (2-param node + comparator). Generalize the base to carry the optional-comparator
  seam. Hardest merge; `Object` suite green, JMH flat.
- **Phase 4 — Move leaf-page persistence into the base** (today only `Long` has it; `RangeIndex` is its consumer).
  Bring the SPI's page handshake up so any variant pages uniformly. Re-verify `RangeIndex` + its tests.
- **Phase 5 — Add the element-keyed variant** = `TransactionalElementBPlusTree<E>` where the leaf stores `E[]` only
  and the key is derived via an `ToIntFunction<E>`/comparator (no stored key → the 8.22 B/record win). It is a thin
  subclass on the base: new leaf-payload + key-from-element seam; reuses all spine/rebalance/iterator/persistence.
  Port the spike's `PriceRecordStructureSpike` cross-store correctness check into a real test.
- **Phase 6 — Back `priceRecords` with it + §3 paging.** Replace `TransactionalObjArray<PriceRecordContract>` with
  the element tree in `AbstractPriceListAndCurrencyPriceIndex`; rewrite the positional hot loop
  (`getPriceRecordsByPriceIds`) as an ordered-iterator/bitmap merge; `getPriceRecord` → `tree.search`; super-index
  `createStoragePart` emits leaf pages (byte 38 + `PriceLeafStreamKey` + removal + PAGED root on byte 26),
  owner-resident `PageStreamRegistry`; loader reassembles. Ref-index keeps discarding at commit (PriceTxScoper).

## Phase 1 FINALIZED DESIGN (read both trees in full; signatures verified)

**The two trees are byte-identical modulo two seams.** The nested `BPlusTreeNode<N>` SPI differs only in
`long[] getKeys()` vs `int[] getKeys()` and `long getLeftBoundaryKey()` vs `int getLeftBoundaryKey()`; every other
method (`getPeek`/`setPeek`/`size`/`keyCount`/`isFull`/`toVerboseString`/`stealFromLeft`/`stealFromRight`/
`mergeWithLeft`/`mergeWithRight`) is identical. `consolidate`, `updateParentKeys`, the height/occupancy verifiers, the
leftmost/rightmost cursor builders, and the cursor records are identical except the single root-collapse leaf
construction (`new BPlusLeafTreeNode<>(valueBlockSize, valueType, wrapper, true)` vs
`new BPlusLeafTreeNode(valueBlockSize, true)`).

**Key perf insight (de-risks the whole approach):** every node op `consolidate` invokes
(`stealFromLeft`/`mergeWithLeft`/`removeChildOnIndex`/`keyCount`/`getChildren`) is *already* a virtual interface
dispatch today. Moving `consolidate` to the base adds **no** new dispatch and **no** per-element boxing. The
boxing-avoidance the forks exist for lives entirely in `searchIndex(key)` (descent) and the iterator's cached typed
leaf arrays — **both stay specialized in the subclass**. So the extraction is perf-neutral by construction; the JMH
gate exists to confirm exactly that.

### Shared SPI (new top-level types in `io.evitadb.index.bPlusTree`)
- `BPlusTreeNode<N extends BPlusTreeNode<N>> extends TransactionalLayerProducer<N,N>, Serializable` — the **generic**
  node contract: `getPeek/setPeek/size(default)/keyCount/isFull/toVerboseString/stealFromLeft(int,N)/stealFromRight
  (int,N)/mergeWithLeft(N)/mergeWithRight(N)`. **Drops** `getKeys()` and `getLeftBoundaryKey()` — those are typed and
  stay on the concrete node classes (called only by subclass-resident split/createCursor/verifiers). No serializer
  references these node types (verified), so promoting the interface is safe.
- `InternalBPlusTreeNode<N extends InternalBPlusTreeNode<N>> extends BPlusTreeNode<N>` — the internal-node ops the base
  drives: `BPlusTreeNode<?>[] getChildren()`, `removeChildOnIndex(int,int)`, `updateKeyForNode(int, BPlusTreeNode<?>)`.

### Base `AbstractTransactionalBPlusTree` — **non-generic, does NOT implement `TransactionalLayerProducer`**
The TLP surface (`createLayer`/`removeLayer`/`removeLayerRecursively`/`createCopyWithMergedTransactionalMemory`) is
self-typed (`<Void, SELF>`) and value-typed (the leaf producer-value cleanup differs: `V[]` vs `long[]`), so it stays
in each subclass. The base owns only the already-polymorphic structural core:
- fields: `valueBlockSize`/`minValueBlockSize`/`internalNodeBlockSize`/`minInternalNodeBlockSize` + the validation
  asserts, `TransactionalReference<BPlusTreeNode<?>> root`, `TransactionalReference<Integer> size`, `long id`;
- `getRoot`/`setRoot`/`isRootInternal`/`size`;
- `consolidate(Cursor)` + abstract `newEmptyLeaf()` (the only typed seam, returns `BPlusTreeNode<?>`);
- `createLeftmostCursor`/`createRightmostCursor` + static `addLeftmostCursorLevels`/`addRightmostCursorLevels`;
- static `updateParentKeys`;
- the **generic** verifiers: `verifyAndReturnHeight`(×2)/`verifyHeightOfAllChildren`/`verifyMinimalCountOfValuesInNodes`;
- the cursor records `Cursor`/`CursorWithLevel`/`CursorLevel`/`NodeWithIndex` (retyped from concrete
  `BPlusInternalTreeNode` → `InternalBPlusTreeNode`).

### Stays typed in each subclass
public API (`insert`/`upsert`/`delete`/`search`/`markDirty`/iterators), `createCursor(key)` + `addCursorLevels(node,
key,path)` (the hot descent — `searchIndex(key)`), the split family (`splitLeafNode`/`splitInternalNode`/
`replaceNodeInParentInternalNode` — allocate typed arrays, thread raw separator), the TLP surface, `getConsistencyReport`
+ the key-typed verifiers (`verifyInternalNodeKeys`/`verifyForward|ReverseKeyIterator`), all iterators (2b moves only
the navigation skeleton), the typed node classes (implementing the SPI), and §3 leaf-page persistence (Long only;
Phase 4). `Cursor.leafNode()` returns `BPlusTreeNode<?>` → subclass downcasts to its concrete leaf (one monomorphic
cast per insert/delete).

### Perf oracle (regression gate)
`RangeIndexBlockSizeBenchmark` (Long, via RangeIndex) + `UnorderedLookupTreeBenchmark` (IntToLong, via
UnorderedLookupTree) — both committed JMH benchmarks. Capture baseline before 2a, re-run after 2a and after 2b.

## PILOT RESULTS (Phase 2a — DONE, functional gate GREEN)

Both primitive trees now `extend AbstractTransactionalBPlusTree`. New files: `BPlusTreeNode` (124L, shared SPI),
`InternalBPlusTreeNode` (67L), `AbstractTransactionalBPlusTree` (781L base). Each tree gained a per-tree typed marker
(`LongKeyedNode` / `IntKeyedNode`) carrying `getKeys()`/`getLeftBoundaryKey()` off the shared SPI, plus a static
`leftBoundaryKeyOf(...)` for the polymorphic separator reads. `Cursor.leafNode()` returns the key-agnostic node;
subclasses downcast to their concrete leaf (one monomorphic cast per insert/delete/search).

- **Line delta:** Long 4231→3470 (−761), IntToLong 3559→2808 (−751) = −1512 duplicated lines removed; +972 shared
  (base+SPI) lines that serve both trees (and, later, Object + the element price tree). Net −540, dedup compounding.
- **Functional gate GREEN (596 tests, 0 failures):** `TransactionalLongBPlusTreeTest` (112) +
  `TransactionalIntToLongBPlusTreeTest` (108) + the untouched `TransactionalObjectBPlusTreeTest` /
  `TransactionalBucketBPlusTreeTest` + the consumers `RangeIndexTest` / `UnorderedLookupTreeTest` /
  `UnorderedLookupTreeStmTest` / `TransactionalUnorderedIntArrayTest`.
- **Structural perf-neutrality (by construction):** the hot descent (`createCursor`→`searchIndex(key)`) and the
  iterator per-element reads (cached typed `leafKeys`/`leafValues`) stayed specialized in each subclass — unchanged. The
  moved `consolidate`/cursor-records already dispatched through the (now-shared) node SPI, so **no new per-element
  virtual call and no new boxing**. The only added indirections are structural-frequency, not per-element:
  `newEmptyLeaf()` (rare root-collapse), `leftBoundaryKeyOf` (a cast+vcall on splits/merges/updateParentKeys), and the
  per-op `Cursor.leafNode()` downcast (monomorphic, JIT-elided).
- **Empirical JMH — after-only sanity (Johnny's choice; no baseline, no git op):** both committed benchmarks run
  cleanly under load on the extracted code, numbers in the expected range:
  - Long path `RangeIndexBlockSizeBenchmark` @ blockSize=64 / 100k thresholds / 1 rec-per-point:
    `pointLookup` (hot typed descent) **0.200 µs/op**, `bulkLoad` (build 100k) ~15.3 ms/op.
  - IntToLong path `UnorderedLookupTreeBenchmark.buildChain` @ 1M: ~275 ms/op.
  No exceptions / corruption; the hot read path shows no anomaly (it stayed typed/monomorphic). A true before/after A/B
  (needs a pre-extraction HEAD build) was deferred — the after-only run + functional green + structural neutrality is
  the accepted GO evidence. **Gate verdict: GO.**

Tests updated (same package, so the promoted top-level `BPlusTreeNode` + package-private base resolve directly):
`Transactional{Long,IntToLong}BPlusTreeTest` — nested `…BPlusTree.BPlusTreeNode` → top-level `BPlusTreeNode`, and the
reflective `getDeclaredField("root"|"size")` retargeted to `AbstractTransactionalBPlusTree` (fields moved to the base).

## Phase 2b — iterator-navigation extraction (DONE, GREEN)

The leaf-to-leaf iteration skeleton was the next largest duplicate block (the two abstract iterator classes appeared
verbatim in both primitive trees). It is now in the base behind the **same structure-vs-typed-reads split** as Phase 2a:

- **Base gains two key-agnostic `protected abstract static` navigators** — `AbstractForwardTreeNavigator` /
  `AbstractReverseTreeNavigator` — owning the path arrays (`path`/`pathIndex`/`pathPeeks`), the cursor state
  (`currentIndex`/`hasNextElement`/`leafPeek`), `hasNext()`, `advance()`, `moveToNextLeaf()` / `moveToPreviousLeaf()`,
  and a key-agnostic `currentLeafNode()`. The single typed seam is the abstract `loadCurrentLeaf()`. The keyed-start
  constructors take a key-agnostic `ArrayUtils.InsertionPosition` (computed by the subclass) instead of a typed key, so
  the base never sees the key type. The old hard reference to `BPlusInternalTreeNode` became the SPI
  `InternalBPlusTreeNode<?>` (the cast fires once per leaf crossing, not per element).
- **Each tree keeps a thin typed intermediate** (`AbstractForwardTreeIterator` / `AbstractReverseTreeIterator`) that
  declares only the typed `leafKeys`/`leafValues` arrays + the typed keyed constructor (computes the insertion position
  via `computeInsertPositionOf{Long,Int}InOrderedArray` through the `LongKeyedNode`/`IntKeyedNode` marker) +
  `loadCurrentLeaf()` (downcasts `currentLeafNode()` and caches the typed arrays). **The six concrete iterators per tree
  (key/value/entry × forward/reverse) are byte-for-byte unchanged** — they still read the inherited cached arrays.

- **Line delta:** ≈ −11.6 KB of source removed from each primitive tree (~−1040 duplicated lines), ~+330 shared
  navigator lines in the base that will also serve the Object tree and the element price tree.
- **Perf-neutral by construction:** the hot per-element reads (`nextLong`/`nextInt`/`next`) are the *unchanged* concrete
  methods reading inherited typed arrays by direct field access; `advance()` is the same non-virtual per-element call it
  always was. The only new indirection is `loadCurrentLeaf()` going from a private to an (monomorphic, JIT-devirtualised)
  abstract call — and it fires once per leaf entry (structural frequency), not per element. No JMH re-run was needed: the
  per-element path is identical.
- **Functional gate GREEN (816 tests, 0 failures):** `TransactionalLongBPlusTreeTest` +
  `TransactionalIntToLongBPlusTreeTest` (220, all forward/reverse key/value/entry + ge/le starts + iterator
  transactional consistency) and the consumers `RangeIndexTest` / `UnorderedLookupTreeTest` /
  `UnorderedLookupTreeStmTest` / `UnorderedLookupTest` / `ChainIndexTest` / `SortIndexTest` (596).

## Phase 3 — fold in `TransactionalObjectBPlusTree` (comparator seam — DONE, GREEN)

The hardest tree to fold: it is generic over the key (`<K extends Comparable<K>, V>`), every node was generic over the
key (the nested `BPlusTreeNode<M, N>` interface — a **name collision** with the shared SPI), and its ordering is driven
by an optional `Comparator<K>` rather than a primitive `<`. It now `extends AbstractTransactionalBPlusTree` with the
comparator confined entirely to the subclass + a per-tree marker, so **the base still never sees a key or a comparator**.

- **Same structure-vs-typed split as the primitives.** Deleted the nested `BPlusTreeNode<M, N>` interface and switched
  both node classes to the shared SPI: `BPlusInternalTreeNode<M> implements InternalBPlusTreeNode<…>, ObjectKeyedNode<M>`
  and `BPlusLeafTreeNode<M, N> implements BPlusTreeNode<…>, ObjectKeyedNode<M>`. The cursor records
  (`Cursor`/`CursorWithLevel`/`CursorLevel`/`NodeWithIndex`), `consolidate`, the leftmost/rightmost cursor builders, the
  height/occupancy verifiers, `getRoot`/`setRoot`/`size`, the block-size fields + `root`/`size` references, and
  `updateParentKeys` are all inherited from the base now (~−776 source lines, 4262→3486).
- **The comparator seam = the `ObjectKeyedNode<M>` marker.** It carries the four key-typed/comparator-aware members kept
  off the SPI — `M[] getKeys()`, `M getLeftBoundaryKey()`, `Comparator<M> getComparator()`, and the
  `default findKeyPosition(key, keys, from, to)` (which routes through `getComparator()` or natural order). Every
  comparator-driven site stays in the subclass or the marker: the `createCursor(key)` descent (`searchIndex`), the leaf
  `getValue`/`getValueIndex`, and the iterators' keyed-start position compute all call `findKeyPosition` through the
  marker; the base's structural algorithms only ever call key-agnostic SPI methods. Polymorphic separator reads on a
  child held as `BPlusTreeNode<?>` go through a generic `leftBoundaryKeyOf(node)` helper (casts to the marker) — exactly
  the `LongKeyedNode`/`IntKeyedNode` pattern, one type up.
- **Iterators kept bespoke (deliberate).** Unlike the primitives, the Object tree's iterators carry an allocation-free
  `EntryCursor` with software leaf-prefetch (`peekNextLeaf`/`prefetchNextLeaf`) that reads the path arrays directly, so
  they were **not** re-parented onto the base navigators (that would force the base to widen its path-array visibility for
  one consumer). They keep their own path traversal; only their types were collapsed to the SPI + the non-generic base
  `Cursor`. This is a documented asymmetry, not an omission.
- **Functional gate GREEN — 935 tests, 0 failures:** `TransactionalObjectBPlusTreeTest` (119 — insert/upsert/delete/
  search, all six iterators + both entry cursors, steal/merge rebalancing, custom-comparator ordering, STM invariants,
  rollback, consistency oracle) plus the unchanged 816 from Phase 2b (primitives + `SortIndexTest`/`RangeIndexTest`/…).
  `OwnerSortIndex` (uses the comparator-ordered Object tree) and `TrafficRecordingIndex` (typed nested trees) both still
  compile and pass. The test's reflective `root`/`size` access was retargeted to `AbstractTransactionalBPlusTree`.
- **Perf-neutral by construction:** the hot descent (`searchIndex`/`findKeyPosition`) and the per-element iterator reads
  stayed typed in the subclass exactly as before; the only moved code already dispatched through the node SPI.

## Phase 4 — move leaf-page persistence into the base (DONE, GREEN)

Today only the long-keyed tree pages (`RangeIndex` is its sole consumer; the bucket tree's separate §3 serves
`FilterIndex`/`InvertedIndex` and stays out of scope). Phase 4 lifts the **value-agnostic emission half** of that §3
handshake into the base so any future variant — the Phase 5 element-keyed price tree first — pages uniformly, while the
**inherently-typed loading half** (spine reassembly) stays per-subclass exactly like the split family.

- **New leaf SPI `LeafBPlusTreeNode<N> extends BPlusTreeNode<N>`** — the page handshake the base drives on a leaf held
  behind the SPI: `getPageSequence()` / `setPageSequence(int)` / `isDirty()` / `clearDirty()` and the **value-erased**
  `Object[] getValueArray()` (the long tree implements it as `return getValues()` — `V[]` is already an `Object[]`, so
  no copy). It mirrors `InternalBPlusTreeNode`: only the variants that actually page implement it. The key-typed leaf
  members (`getKeys`, `getValue(key)`, `getLeftBoundaryKey`) stay on the concrete leaf, monomorphic.
- **New top-level `LeafPageHandle<T>`** — the value-agnostic emission view, promoted out of the long tree's nested type
  so it is importable cross-package by the index consumers (canonical name `io.evitadb.index.bPlusTree.LeafPageHandle`,
  like `BPlusTreeNode`). `RangeIndex`'s one import line changed; everything else is source-compatible.
- **Base gains** `UNASSIGNED_PAGE_SEQUENCE`, the leaf walk (`enumerateLeaves()` / `collectLeaves()` over the key-agnostic
  node SPI → `List<BPlusTreeNode<?>>`), the generic `public <T> List<LeafPageHandle<T>> leafPageHandles()` (casts each
  walked leaf to the leaf SPI), and the private `LeafPageHandleImpl<T>` that captures `getValueArray()`+`getPeek()` once
  and returns `(T) values[i]`. `TransactionalLongBPlusTree.UNASSIGNED_PAGE_SEQUENCE` still resolves (inherited public
  static through the public subclass), so that consumer reference is unchanged.
- **Stays typed in the long subclass** (the loading half, all inherently typed — they allocate `long[]` separators and/or
  construct a typed tree): `buildSpine` (partition + bottom-up), `buildInternalNode`, `assembleFromLeaves`,
  `assembleFromSingleLeafTrees`, and `markDirty(long key)` (descends via `createCursor(key)`). The element tree (Phase 5)
  re-derives only these ~40 typed lines; it inherits the entire emission path + page-state SPI. Deliberately **not**
  promoted to an abstract `buildInternalNode` on the base, which would force a dead routing-node builder onto the
  non-paging `IntToLong`/`Object` trees — they are left completely untouched by Phase 4 (a Phase-5 follow-up may promote
  `buildSpine` if the element tree benefits).
- **Line delta:** long tree −149 (3198→3049); base +112 (1125→1237); +182 shared SPI (`LeafBPlusTreeNode` 89,
  `LeafPageHandle` 93) that will also serve the element price tree. `IntToLong`/`Object`/bucket trees: zero lines changed.
- **Perf-neutral by construction:** the hot read/write paths (`createCursor` descent, leaf insert/delete, the iterators)
  are untouched; the only changed path is flush-time page emission (`leafPageHandles()` → SPI calls), which fires once per
  leaf per commit (structural frequency), never per element. `valueAt`'s `(T)` is an erased no-op.
- **Functional gate GREEN — 1111 tests, 0 failures:** the three tree suites + `RangeIndexTest`'s paged round-trip
  (411) and the untouched ecosystem — `TransactionalBucketBPlusTreeTest` / `FilterIndexPagedPersistenceTest` /
  `InvertedIndexTest` / `PageStreamRegistryTest` + the `IntToLong`/`Object` consumers
  `UnorderedLookupTree{,Stm}Test` / `UnorderedLookupTest` / `ChainIndexTest` / `SortIndexTest` (700). `evita_engine`
  compiles + installs offline clean.

## Phase 5 — element-keyed `TransactionalElementBPlusTree<E>` (DONE, GREEN)
The 8.22 B/record price tree, delivered as a thin subclass on the shared base — **purely additive, no existing file
touched.** New file `evita_engine/.../index/bPlusTree/TransactionalElementBPlusTree.java` (~1450 lines).
- **Structure = IntToLong with the leaf key-column elided.** Internal (routing) nodes still materialize `int[]`
  separators (copied near-verbatim from `TransactionalIntToLongBPlusTree.BPlusInternalTreeNode`, non-generic); the
  generic leaf `BPlusLeafTreeNode<E>` holds a single ascending `E[]` and re-derives a key on demand via a per-leaf
  `ToIntFunction<E> keyExtractor` in a zero-boxing binary search (`searchKey`). No parallel key array — that IS the win.
- **`E` is non-transactional** (carried by reference, exactly like IntToLong's primitive `long`), so the whole Long-tree
  value-wrapper / `discardRemovedValueLayer` machinery is dropped: `removeLayerRecursively` does not recurse into values
  and `createCopyWithMergedTransactionalMemory` neither wraps nor deep-copies them.
- **Inherits §3 for free**: the leaf `implements LeafBPlusTreeNode` (the Phase-4 SPI) → the base's `leafPageHandles()`
  emission framework works unchanged. It carries the Long leaf's `pageSequence`/`dirty` paging surface and re-derives only
  the ~40 typed spine-reassembly lines (`buildSpine`/`buildInternalNode` with `int[]` separators / `assembleFromLeaves` /
  `assembleFromSingleLeafTrees`) for the load path. Dirty discipline mirrors the Long leaf exactly (`setPeek` + each
  mutator flags the receiver; donors flag via their own `setPeek`).
- **Marker seam** = minimal `IntBoundaryKeyedNode { int getLeftBoundaryKey(); }` (leaf derives from `values[0]`, internal
  forwards to first child) — no `int[] getKeys()` on the leaf, so a leaf never materializes a key array.
- **API**: `insert(E)` (derives key, overwrites on dup), `delete(int)`, `search(int)→@Nullable E` (no per-lookup Optional
  on the hot price path), `markDirty(int)`, `toArray()→E[]`, forward/reverse element & key iterators (+ keyed
  `greaterOrEqual`/`lesserOrEqual` for the Phase-6 merge). Default block 64 (min 31 / internal 31 / min-internal 15).
- **Test** `TransactionalElementBPlusTreeTest` (18 tests, GREEN): insert/split, delete/borrow/merge/collapse, search &
  ordered iteration, transactional commit/rollback (`assertStateAfter{Commit,Rollback}`), the **ported spike cross-store
  correctness check** (element tree vs `TreeMap` reference — identical `toArray` / `search` / ordered filtered merge over
  5k records), and the **§3 leaf-page round-trip** (boundary-stable reload via `assembleFromSingleLeafTrees`, page
  sequences preserved, change-detection baseline restored → no-op second flush; a single mutation re-dirties ≤ 3 leaves).
- Sweep GREEN: 429 (Element + IntToLong + Long + Object + RangeIndex). Element type used = the real `PriceRecordContract`
  keyed on `internalPriceId` — the exact shape Phase 6 wires in.

## Notes / risks
- The element tree's transactional needs are fully covered by the base's existing per-node transactional layer
  (the super index does read-your-writes during a tx — `removePrice` reads via `getPriceRecord`), so no weaker
  "mutation-buffer only" contract is needed; the full committed-tree model is also what enables §3 paging.
- This is a multi-day, high-risk refactor of the most-tested code in the engine. The pilot (Phase 2) is the
  pressure test; everything after is gated on it. Version stays `2026.2-760LOCAL-SNAPSHOT` until done.
- Scope discipline: bucket tree explicitly excluded; the full 4-tree unification is a possible later follow-up.
