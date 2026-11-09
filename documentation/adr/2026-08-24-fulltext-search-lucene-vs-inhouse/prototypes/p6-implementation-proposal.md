# P6 — vector search in evitaDB: the implementation proposal

**What the vector spike concluded, written as instructions for the implementation.** This document says what
to build, from which library parts, and which traps the spike already hit. It does not repeat the evidence:
every number cites the measurement that produced it.

- [`p6-vector-spike-spec.md`](p6-vector-spike-spec.md): the specification the spike answered (its `§` sections).
- [`p6-measurement-results.md`](p6-measurement-results.md): the measurements on corpora A–E, cited as
  "results N", and the mini-gate with the verdict. The corpora are described there; corpus D (416,559 × 2,048)
  stands in for the spec's 1M × 768.
- [`p6-measurement-harness.md`](p6-measurement-harness.md): how to re-run any measurement. The prototype code
  is in `evita_test/evita_performance_tests`, package `io.evitadb.spike.vector`; class names below refer to it.

Versions: **jVector 4.0.2, Lucene 10.6.0, JDK 21** (the floor). Measured 2026-11-04 to 2026-11-09.

---

## 1. The decision

**A library combination with evitaDB in the middle:**

- **jVector supplies the graph** and its maintenance. It is the only library whose graph takes concurrent inserts
  and deletes under readers and keeps its ordinals (results 11).
- **Lucene supplies the codes**: its optimized scalar quantization (OSQ), its scorers and its SIMD kernels.
  jVector's own codes (PQ, BQ) miss recall@10 ≥ 0.95 on some corpora (results 3).
- **evitaDB owns everything between**: the searcher, the planner, the exact scan, the files (raw vectors, codes,
  graph), the primary key ↔ ordinal map, and the transaction layer.

Neither library alone passes every blocking criterion: jVector alone misses recall, Lucene alone misses
incremental maintenance (its graph crashes concurrent readers during inserts, results 11).

| | recall@10 at 4× (A–E) | JMH p95 unfiltered (A–E) |
|---|---|---|
| combination, OSQ 4-bit | 0.976–0.996 | 0.23–0.81 ms |
| combination, OSQ 1-bit | 0.967–0.995 | 0.17–0.64 ms |
| for comparison: Lucene alone, OSQ 4-bit | 0.945–0.996 (misses on A) | 0.13–0.67 ms |
| for comparison: jVector alone, BQ | 0.898–0.984 (misses on B) | 0.14–0.33 ms |
| for comparison: exact scan | 1.000 | 3.5–110 ms |

Sources: results 3, 4 and 8. The combination is 20–95 % slower than Lucene alone, because its denser graph scores
more nodes per step, and still about ten times under the 10 ms criterion. The full gate table is in results,
"The mini-gate".

---

## 2. Who does what

| part | from jVector | from Lucene | evitaDB writes |
|---|---|---|---|
| graph construction, insert | `GraphIndexBuilder` | — | when to insert; the ordinal |
| graph delete and repair | `markNodeDeleted`, `removeDeletedNodes` | — | the liveness bitmap; when to repair |
| graph read | `ImmutableGraphIndex.View` | — | `GraphView` over the view and over our graph file |
| graph file | — | — | format, writer, mapped reader (`MappedGraphFile`) |
| raw vectors | reads them through `RandomAccessVectorValues` | reads them through `FloatVectorValues` | the append-only file, mmap, both adapters |
| codes | — | `OptimizedScalarQuantizer` computes them | the code file and its mapped reader (`OsqCodeFile`) |
| scoring | — | `Lucene104ScalarQuantizedVectorScorer`, flat scorer, `VectorUtil` kernels | `NodeScorer` adapter (one method) |
| search | — | — | `EvitaGraphSearcher`: walk, filter, visit budget, cancellation |
| filtered search | — | — | the planner and the exact scan |
| primary keys | — | — | `pk ↔ ord` map (`PrimaryKeyOrdinals`), the accept test (`Acceptor`) |
| transactions | — | — | own vectors and deletes beside the shared graph |
| storage lifecycle | — | — | append-only files, compaction, `ObsoleteFileMaintainer`, the pin protocol |

**Not used, and why:**

- **jVector's `GraphSearcher`:** it cannot be cancelled (with the interrupt flag set and a filter that accepts
  nothing it visits the whole graph) and it has no predicate-aware strategy or visit budget. Our searcher returns
  the same recall within ±0.003 (results 8).
- **jVector's PQ and BQ codes:** below 0.95 at 4× on some corpora (PQ on A, B and E; BQ on B, results 3), and held
  on the heap once loaded.
- **jVector's on-disk format (`OnDiskGraphIndex`):** on JDK 21 its reader (`MappedChunkReader`) maps the file once
  per view and leaves the unmapping to the GC, which breaks an explicit unmapping protocol. Its writer also
  renumbers ordinals silently. Our own file is simpler (section 4.4).
- **Lucene's graph:** not safe under concurrent inserts (`ClassCastException`, `ArrayIndexOutOfBoundsException`
  in readers, results 11), and so sparse (mean degree 7–12 whatever `M`, results 6) that it caps recall on the hard
  corpus A at 0.957 even with float32.
- **Lucene's ACORN (`FilteredHnswGraphSearcher`):** loses recall under most filters of 50 % and below, 0.39 / 0.14
  at the real filters of A and E. Our port gives the same numbers, so the cause is the graph's sparsity and the
  filter's shape, not the wiring (results 6).
- **Lucene's full index (`IndexWriter`, segments):** a second durability system that merges and deletes its own
  files, renumbers documents on merges and commits on its own; it cannot sit inside evitaDB's append-only storage
  and its bootstrap record (`durability-model.md`).

---

## 3. The contracts

What the rest of evitaDB sees (spec §3.1). The prototype has `GraphView`, `NodeScorer` and `Acceptor`; the
others are named after their role.

```java
interface VectorStore  { int size(); int dimension(); float[] read(int ord, float[] into); } // raw block, mapped
interface CodeStore    { byte[] code(int ord); Corrections corrections(int ord); Header header(); } // mapped
interface NodeScorer   { float score(int ord); }                       // made for one query
interface GraphView    { int entryNode(); int maxLevel(); int neighbors(int level, int ord, int[] into);
                         boolean isLive(int ord); }
interface CandidateSet { boolean contains(int ord); int cardinality(); }  // filter ∩ visible, by ordinal
interface SearchStrategy { int[] search(float[] query, int k, CandidateSet candidates, Budget budget); }
```

Both libraries reduce a scorer to one method taking an ordinal and returning a float (jVector's
`ScoreFunction.similarityTo(int)`, Lucene's `RandomVectorScorer.score(int)`). That is why a Lucene code can drive
a jVector graph through a three-line adapter, and why the decision stays reversible at every seam (spec §2.6).

---

## 4. Storage

### 4.1 Ordinals

An **ordinal** is the library's dense number for a vector and its graph node. It is not a primary key (spec §2.4):
keys are sparse and never renumbered, while the graph, its bitsets and the files want dense numbers.

1. **Insert:** the vector is appended to the raw block; its slot number is its ordinal. The map gets `ord → pk`
   and `pk → ord`.
2. **Change of a vector:** a delete of the old ordinal plus an insert under a new one. A vector is never
   overwritten in place, because the published version still reads the old one.
3. **Delete:** the ordinal gets a bit in the liveness bitmap and `markNodeDeleted` in the graph. The slot and its
   bytes stay; the graph repair still reads the deleted vector.
4. **Compaction** rewrites raw block, codes and graph into new files with dense ordinals and a new map. It is the
   only point where ordinals change, so it must rewrite `ord → pk` in the same step. For the graph, jVector
   provides the translation: its writers renumber through an `OrdinalMapper` (`withMapper`), and
   `OnDiskGraphIndexCompactor` merges several graphs, drops deleted nodes and returns an `OrdinalMapper` per input.

**Correctness never depends on the graph.** A deleted or filtered-out entity is rejected by the accept test
(`!deleted(ord) && members.contains(ordToPk[ord])`) even while its node is still in the graph. Graph deletes
exist for recall and space only. During a jVector replacement, readers do see 19–32 just-deleted nodes
(results 11), so the accept test is mandatory, not a safety net.

### 4.2 The raw block

Float32, `dim × 4` B per vector (8,192 B at 2,048 dimensions, 3,072 B at 768), append-only, mapped. Both
libraries read it through an adapter of about 40 lines (`JVectorFootprintScenarios.MappedRavv`,
`MappedVectors`). The mapped source is a requirement, not an optimization: without it jVector holds every vector
on the heap (120 MB against 0.01 MB at 15k × 2,048; 3 GB per 1M × 768).

- **Ingress:** evitaDB has no `float[]` data type; a vector arrives as `byte[]` (768 floats = 3,072 B), and the
  little-endian float32 round trip is bit-exact (NaN payloads, −0.0 and subnormals included), also through a
  mapped file. That is the premise of a mapped raw block (spec §2.2).
- **Growth:** the jVector builder needs `size()` to cover the newest ordinal, so a block that grows by appending
  needs a remap after growth or a mapping in fixed-size chunks, as the store already does for its own files.
- **The rerank reads only the candidates** (4 × 10 per query), so the raw block can stay mostly cold outside a
  graph build.
- **Allocation:** jVector treats a mapped source as value-shared and copies the 8 KB vector out of the mapping for
  every score function it creates. That is the source of the short-lived garbage in the graph build and in the
  delete repair (section 5.3). It is GC load, not live heap.

### 4.3 The code file

One record per ordinal: the packed OSQ code plus 16 B of corrections, and a header with the centroid and the
encoding (`OsqCodeFile`). Lucene computes the code (`OptimizedScalarQuantizer.scalarQuantize`, then `packNibbles`
for 4-bit or `packAsBinary` for 1-bit); we append it. A `QuantizedByteVectorValues` subclass of about 100 lines
hands the mapped file to Lucene's scorer, with recall identical to the third decimal to Lucene's own `.veq`
(results 2). The codes never reach the heap: the reader owns 0.01 MB whatever the file size.

| code | B per vector at 2,048 | at 768 | per 1M × 768 |
|---|---|---|---|
| OSQ 4-bit (`PACKED_NIBBLE`) | 1,040 | 400 | 400 MB |
| OSQ 1-bit (`SINGLE_BIT_QUERY_NIBBLE`, RaBitQ-derived) | 272 | 112 | 112 MB |

- **The centroid is fixed per file.** Vectors inserted later are encoded against it; compaction recomputes it and
  re-encodes from the raw block. How far recall drifts in between was not measured (results, "Deviations").
- **Never `UNSIGNED_BYTE` (8-bit) on JDK 21:** Lucene's Panama kernel for it is 77× slower than scalar code
  (section 9.2). The 8-bit-class code is `SEVEN_BIT`.

### 4.4 The graph file

The graph being changed lives on the jVector builder's heap; every published version is a read-only file of
ours, written from the builder's `View` (`MappedGraphFile`):

- a header (version, node count, levels, entry node, maximum degree);
- level 0 as one fixed slot per ordinal: the neighbour count, then the neighbour ordinals, found by arithmetic;
- each upper level as its sorted node list (binary search), then the slots in the same order.

Measured (results 10): 136 B per node, written in about 150 ms at D, mapped in 0.2 ms, nothing on the heap, and
every walk returns exactly the candidates of the heap graph. A restart opens the graph instead of rebuilding it
(minutes of all cores at 1M). The reader implements both our `GraphView` and Lucene's `HnswGraph`.

Open format decisions: a variable-length level 0 (offset table plus packed lists, roughly 60 B per node for an
`alpha = 1.0` graph, not measured) and the append-only update of the file (spec §2.3).

---

## 5. The graph

### 5.1 Configuration

```java
new GraphIndexBuilder(raw, VectorSimilarityFunction.DOT_PRODUCT,
        32,      // maximum degree
        100,     // construction beam
        1.2f,    // neighbour overflow
        1.0f,    // alpha
        true,    // hierarchy (optional, see below)
        false);  // refineFinalGraph
```

- **The deprecated eight-argument constructor is deliberate.** The fluent builder reads `addHierarchy` and
  `refineFinalGraph` from the process-wide `GraphIndexBuilderConfig` singleton, which is also registered as a
  JMX MBean by default, so a configuration could change under a running build. The constructor is the only
  per-build control. Pin it, and run with `-Djvector.management.backend=none` (section 9).
- **`alpha = 1.0`:** about twice as fast to build at half the degree, with the same recall at 4× (results 9). The
  degree, and with it the graph's heap, is set by the corpus, not by the maximum.
- **Hierarchy:** no systematic recall difference up to 416k vectors (results 9). Keep it on until a 1M run says
  otherwise; it costs little.
- **`refineFinalGraph = false`:** with the default `true`, every `cleanup()` searches again from every upper-level
  node, a fixed 8–14 s on corpora C–E even for one delete, and buys no recall (within jVector's ±0.02 run-to-run
  variation, results 12).

### 5.2 Inserts

`builder.addGraphNode(ord, raw)` per vector, thread-safe, concurrent with readers: 0.8–2.3 ms per node (results
11, 12). Readers walk the live graph meanwhile with zero errors, and recall ends equal to a fresh build.

### 5.3 Deletes

- **Immediately:** a bit in the liveness bitmap (correctness) and `markNodeDeleted(ord)` (the graph never returns
  the node again).
- **In batches, rarely:** `removeDeletedNodes()` repairs the deleted nodes' neighbours. It costs a fixed pass over
  the graph (2.5–7.9 s on C–E) plus 2–4 ms per deleted node on six cores; 10,000 deletes take 22–30 s and allocate
  up to 147 GB of short-lived garbage (section 4.2), cleared by 0.4–0.8 s of GC (results 12).
- **Concurrency:** `removeDeletedNodes()` documents itself as not thread-safe against other modifications. Readers
  were safe in every run (0 errors), writers must pause, so the repair needs a window without inserts, not
  without readers. It is also `synchronized`, which pins a virtual thread's carrier on JDK 21: run it on a
  platform thread.
- **`removeDeletedNodes()` alone** leaves the builder's "all mutations completed" flag unset, so `getView()` stays
  the concurrent view and jVector's own on-disk writer would refuse the graph. Our graph file does not care.
- **A `View` is a live handle, not a snapshot:** after a repair, a view taken earlier no longer sees the removed
  nodes. Isolation must come from the bitmaps (section 7), never from the view.

### 5.4 Reindex and compaction

A reindex builds a second catalog next to the live one, so it holds two graphs on the heap and two sets of
mapped files, with the new raw block read randomly by the build (section 8). Compaction is the place to drop
deleted slots, renumber densely, recompute the centroid and write all three files anew.

---

## 6. Search

### 6.1 The searcher

`EvitaGraphSearcher` (about 500 lines) walks `GraphView` with a `NodeScorer`:

- greedy descent through the upper levels, then best-first on level 0 to a depth of 100;
- **in-walk acceptance**: only accepted nodes enter the results, every node may route the walk;
- **visit budget** in distinct scored nodes (Lucene's unit), so the planner's fallback rule is exact;
- **cancellation**: a flag checked on every iteration; every walk stopped at the check it was cancelled at
  (results 8);
- deleted nodes are walked through, never returned.

Then the exact rerank: the best `4 × k` candidates by code score are rescored against the raw block (float32
dot product, `VectorUtil.dotProduct`), and the best `k` are kept.

### 6.2 The planner

The filter arrives as the RoaringBitmap of primary keys the query computes anyway. The planner needs its exact
cardinality, **intersected with the entities that have a vector**; the formula's bitmap also holds entities
without one, which would overstate the selectivity. `Formula.getEstimatedCardinality()` is a rough upper bound
and must not be used.

| members of the filter | strategy | measured |
|---|---|---|
| above ~85 % of the corpus | **post-filter**: unfiltered walk, drop non-members | cheapest; below ~50 % it returns fewer than k results |
| between | **in-walk** acceptance | real filters of B (29.7 %) and E (38.9 %): recall ≥ 0.98 at p95 ≤ 2 ms |
| ≤ 5–10 % (corpora up to ~214k), ≤ 1–5 % (313k–417k) | **exact scan** of the members | recall 1.000; on A (1.66 %) 0.2 ms against p95 12 ms for in-walk |

Sources: results 5 and 6. Two rules carry it:

- **The switch to the scan depends on the members relative to the corpus**, not on a fixed count; a fixed 100
  members is far too low.
- **The fallback** `visitLimit = members + 1`: a walk that has scored more nodes than there are members was
  more expensive than the scan, so it is abandoned and the scan runs. It is the rule of Lucene's
  `AbstractKnnVectorQuery`, and it chose correctly on every corpus without tuning.

The membership structure does not matter (`boolean[]`, flat bitset and RoaringBitmap within noise, results 6),
so the walk can test the RoaringBitmap directly.

### 6.3 The open risk: correlated filters of 5–10 % on large corpora

Recall passes, but neither path is under 10 ms there: the float32 exact scan of the members takes 7–27 ms p50
on C–E and in-walk 6–28 ms (results 6). The candidate fix, a scan over the 4-bit codes with a float32 rerank of
the best candidates, reads an eighth of the bytes and is not measured. It is the first thing to measure in the
implementation.

---

## 7. Transactions

A private graph per read-write transaction is too expensive: a copy takes 13 ms–2.1 s, doubles the graph's heap
while the old readers finish, and deletes on it cost seconds (results 12). Instead (results 13):

- **One shared graph per committed catalog version.**
- **A transaction's own inserts** are not put into the graph. It keeps their float32 vectors on the heap (8 KB
  each at 2,048 dimensions) and scans them exactly on each of its searches.
- **Its own deletes and updates** go into a bitmap of the transaction, which the walk rejects through the accept
  test, the same seam as the filter. An update is a delete plus an own insert.
- **A search** merges the reranked walk with the best own vectors by exact score.
- **On commit** the own inserts go into the shared graph (0.8–2.3 ms each), the deletes are marked and wait for the
  batch repair (section 5.3).

| own vectors | added to a search, p50 / p95 | heap |
|---|---|---|
| 1,000 | 0.12–0.14 / 0.14–0.18 ms | 7.8 MB |
| 10,000 | 1.2–1.3 / 1.3–2.0 ms | 78 MB |

No recall is lost on any corpus, and a delete bitmap of 1 % of the graph costs nothing measurable. The practical
bound is thousands of pending inserts per transaction. Not measured, and part of the implementation: the merge
under a filter, the OSQ code of an own insert, and the connection to evitaDB's transactional layer and the WAL.

---

## 8. Memory

### 8.1 Per vector

| part | memory | B per vector | at corpus D (416,559 × 2,048) | 1M × 768, estimated |
|---|---|---|---|---|
| raw vectors, float32 | mmap | 8,192 / 3,072 | 3.25 GB | 3.07 GB |
| OSQ 4-bit codes | mmap | 1,040 / 400 | ~430 MB | 400 MB |
| OSQ 1-bit codes | mmap | 272 / 112 | ~113 MB | 112 MB |
| jVector graph being built or changed (degree 32, `alpha = 1.2`) | **heap** | ~380 | 151 MB | ~380–400 MB |
| our graph file | mmap | 136 | 57 MB | ~140 MB |

Sources: results 1 and 10. The 1M × 768 column scales the codes by their layout and the graph by node count.
The graph is the only heap term, and at `alpha = 1.0` it shrinks with the degree (section 5.1). **The spec's
§2.8 budget of 100–250 MB of graph per 1M is not met by a degree-32 heap graph**; it is met by the mapped graph
file, and the heap graph is needed only where the graph changes.

### 8.2 Sizing a deployment

Mapped pages are page cache, which the container's cgroup counts against its limit `L`. Clean mapped pages are
reclaimable (the cost of too little is page faults, not an OOM kill); active ones count in the working set the
kubelet evicts by. From one production deployment (four catalogs, 5.4 GiB of catalog files, one pod per node,
`L` = 28,000 MiB, heap at 78 % on JDK 17) over 30 days including a full reindex:

- **The anonymous memory** is `1.045 × H + ~1,200 MiB` (heap, GC structures, code cache, metaspace, threads,
  direct buffers, allocator overhead).
- **The resident budget for vector files** is `V = L − 1.045 × H − 1,200 − 0.1 × L − C`, with `C` the catalog
  files' own peak page cache (4,200 MiB there). At today's heap it is **zero**: a vector file would evict catalog
  pages.
- **A reindex is the worst case**: two copies of the catalog (live set ~7.5 GiB against ~5.6 GiB normally), two
  graphs, two sets of files, and the new raw block should be resident for the build. At 1M × 768 that is
  ~3.3–4.2 GB resident against ~0.15–0.55 GB steady.

Recommendations:

1. **Heap `H ≥ 1.6 × (R + 2 × graph heap)`**, `R` the live set during a reindex, and no larger than the vector
   budget allows. For that deployment's own data (178k × 2,048) `-XX:MaxRAMPercentage=60`; for 1M × 768, `50`.
   Existing deployments therefore need a smaller heap and the move from JDK 17 to JDK 21.
2. **`InitialRAMPercentage` equal to `MaxRAMPercentage`**, so the anonymous memory is constant.
3. **Request = limit** (Guaranteed QoS); a Burstable pod above its request is evicted first.
4. **`MaxDirectMemorySize` unchanged**: `FileChannel.map` does not count toward it.
5. **Monitor mapped memory through the container** (`container_memory_mapped_file`, page cache, working set). On
   JDK 21 a `MemorySegment` mapping is not in the JVM's `mapped` buffer pool, which shows 0.
6. **Verify a full reindex on a test environment** with the smaller heap and the GC log on: no
   `To-space exhausted`, no Full GC, the floor after mixed GC below ~60 % of the heap.

---

## 9. Runtime prerequisites

### 9.1 JVM switches and logging

| switch | why |
|---|---|
| `--add-modules jdk.incubator.vector` | SIMD for both libraries; without it Lucene's low-bit kernels are 10–12× slower. Needed at compile time, in surefire, at runtime (`docker/entrypoint.sh`, `run-server.sh`, `dist/run.sh`) and in every JMH `@Fork` |
| `-Djvector.management.backend=none` | stops jVector registering its global builder configuration as a writable JMX MBean |
| `--enable-native-access=ALL-UNNAMED` (or `=jvector` on the module path), JDK ≥ 22 only | jVector's native provider is the default there, and Lucene's `madvise` downcall warns without it |

- **jVector and Lucene log through `java.util.logging`**, and evitaDB has no JUL → SLF4J bridge. A fallback to the
  scalar provider would reach stderr only, never the server log. Either install the bridge or have the vector
  module log the selected provider itself.
- **Checking SIMD:** a forced Panama provider logs only `Preferred f32 species is N`, not "Using
  PanamaVectorizationProvider"; check that line or the provider class.
- **JPMS:** jVector is an automatic module (`jvector`, no `module-info`), so a `jlink` image cannot include it;
  Lucene is the named module `org.apache.lucene.core`. Both resolve on the module path at `--release 21`.

### 9.2 The SIMD kernels on JDK 21 (JMH, ns per vector)

| kernel (`VectorUtil`) | used by | 2,048 SIMD | 2,048 scalar | speed-up |
|---|---|---|---|---|
| `dotProduct(float[], float[])` | exact rerank, exact scan, own vectors | 137 | 462 | 3.4× |
| `int4DotProductSinglePacked` | OSQ 4-bit at query time | 54 | 528 | 9.7× |
| `int4BitDotProduct` | OSQ 1-bit | 22 | 252 | 11.6× |
| `dotProduct(byte[], byte[])` | OSQ 7-bit | 250 | 527 | 2.1× |
| `uint8DotProduct` | OSQ 8-bit | **40,489** | 524 | **0.013×** |

Measured on 2026-11-04 (`LuceneVectorKernelBenchmark`, Lucene 10.6.0, 128-bit NEON on arm64). End to end SIMD
makes queries 2–3.5× faster and builds about 1.8× faster. The `uint8DotProduct` row is a JDK 21 problem in
Lucene's Panama path (251 ns on JDK 25) and the reason `UNSIGNED_BYTE` is excluded. JDK 25 changes nothing else
measurably. **None of these kernels was timed on x86 (AVX2 / AVX-512), the production image's architecture:**
treat the `UNSIGNED_BYTE` row as a JDK 21 risk and confirm the table on the production host before relying on
the ratios.

---

## 10. The wiring in pseudo-code

The combination end to end, after the prototype (`EvitaGraphSearcher`, `GraphView`, `OsqCodeFile`,
`MappedGraphFile`, `Acceptor`, `TransactionOverlayScenario`). Illustrative, not compiled: the signatures are
simplified (the prototype's `Acceptor` takes a membership array, its searcher a depth and a budget).

**Write path, one entity's vector:**

```java
int ord = rawBlock.append(vector);                        // slot number = ordinal (4.1)
ordinals.put(ord, pk);
Corrections c = quantizer.scalarQuantize(vector, code, bits, codeFile.header().centroid()); // Lucene
codeFile.append(pack(code), c);                           // our file (4.3)
// inside a transaction: only remembered (7); on commit:
builder.addGraphNode(ord, raw);                           // jVector, concurrent with readers (5.2)
```

**Delete:**

```java
liveness.add(ord);                                        // correctness, immediately
builder.markNodeDeleted(ord);                             // the graph stops returning it
// later, in a batch, without concurrent inserts, on a platform thread (5.3):
builder.removeDeletedNodes();
```

**Publishing a graph version:**

```java
MappedGraphFile.write(GraphView.ofJVector(builder.getGraph(), builder.getGraph().getView()), newFile);
// published through the bootstrap record like any other file; the old one goes to ObsoleteFileMaintainer
```

**One query:**

```java
RoaringBitmap members = filterBitmap.and(entitiesWithVector);        // exact cardinality (6.2)
Acceptor accept = Acceptor.of(members, ordinals, liveness, tx.deletes());

int[] top;
if (planner.preferScan(members.getCardinality(), corpusSize)) {
    top = exactScan(members, raw, query, k);                         // float32 over the mapped block
} else {
    RandomVectorScorer codes = osqScorer.getRandomVectorScorer(DOT_PRODUCT, codeFile.values(), query);
    Walk walk = new EvitaGraphSearcher().search(
            graphView, NodeScorer.ofLucene(codes), accept,
            /* depth */ 100, /* keep */ 4 * k,
            /* visit budget */ members.getCardinality() + 1, cancelled);
    top = walk.exhausted()
            ? exactScan(members, raw, query, k)                      // the fallback rule (6.2)
            : exactRerank(walk.candidates(), raw, query, k);
}
top = mergeByExactScore(top, scanExactly(tx.ownVectors(), query, k), k);   // the transaction (7)
```

---

## 11. Before the implementation starts

Not done by the spike, because its non-goals keep production code out (spec §1.4):

- the three files inside evitaDB's storage: format versioning, the append-only update of the graph file,
  `ObsoleteFileMaintainer` and the pin protocol (spec §2.3);
- the transaction layer of section 7 against evitaDB's transactional structures and the WAL;
- the OSQ code of an inserted vector against the file's fixed centroid, and the recall drift until compaction;
- the code choice: **OSQ 1-bit** fits the 1-bit budget of spec §1.2 at a margin of 0.017 on the hardest corpus;
  **OSQ 4-bit** gives a larger margin for four times the mapped bytes.

Open measurements:

- the 4-bit code scan for the 5–10 % band on large corpora (section 6.3);
- a real 1M dataset when one exists; corpus D stands in for it, and a walk over 1M nodes visits somewhat more of
  them than over 416k;
- a variable-length level 0 in the graph file (section 4.4).

---

## 12. What this changes in the specification

The spec is kept as the question the spike answered; these are the points where the answer differs from it.

1. **§1.3, §3.2, OP6-6, versions:** jVector 4.0.2 is a final release; Lucene 10.6.0, not 9.12 (which has neither
   the OSQ formats nor `FilteredHnswGraphSearcher`).
2. **§3.2, the candidates:** the library combination of section 1, which neither path of the spec is.
3. **§2.1, the module wiring:** the switches of #1541 are in `dev`; the JMH fork is the part left to each
   benchmark. JDK ≥ 22 adds `--enable-native-access`, and the management backend must be `none` everywhere, not
   only in the harness (section 9.1).
4. **§2.3, mmap on JDK 21:** jVector's reader maps per view; evitaDB maps its own files (section 2).
5. **§2.4, ordinals:** compaction, and jVector's own writer, renumber; the `ord → pk` map is rewritten in the same
   step (section 4.1).
6. **§2.7, the searcher:** written and measured (section 6.1). `SearchResult#visitedCount` counts distinct scored
   nodes, not scored edges.
7. **§2.8, the budget:** a degree-32 heap graph costs ~380–400 B per node, not ~300; only the mapped graph file
   meets the budget, and a reindex doubles every term (section 8).
8. **§3.4, the planner:** post-filter is right only above ~85 %; the scan threshold depends on the corpus size;
   ACORN is rejected (section 6.2).
9. **§3.5, the codes:** no code reaches 0.95 on its own ordering, the rerank is what makes a code pass;
   `UNSIGNED_BYTE` is excluded on JDK 21; the RaBitQ-derived 1-bit code beats symmetric BQ at the same size.
10. **§4.2, real exports:** an export repeats a referenced record under every referencing one; indexed as is, the
    repeats collapse both libraries' graphs. The index holds one copy per record.
11. **§4.3, the query set:** the oversampling a code needs depends on the query distribution; recall is checked
    with embeddings of the production query model.
12. **§1.2, clicks:** users' clicks bound the embedding, not the index (results 7); recall@10 against the exact
    scan stays the index criterion.
13. **§4.7, churn and transactions:** jVector's deletes are repaired with `removeDeletedNodes()` and
    `refineFinalGraph = false`; transactions keep their changes beside one shared graph (sections 5.3 and 7).
