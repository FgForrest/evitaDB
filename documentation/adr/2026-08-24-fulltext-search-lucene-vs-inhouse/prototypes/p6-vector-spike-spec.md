# P6 — the vector spike: specification

> This is the specification of the P6 spike as finally shaped. It follows on from the research
> ([`../research.md`](../research.md)), namely the whole of [§5](../research.md#5-the-vector-branch-must-have-second-phase), [§7](../research.md#7-next-steps-prototypes-and-phasing) (P6's placement and its mini-gate), O5 and
> O7; the argument "why an in-house engine and not Lucene" is in the research ([§3](../research.md#3-why-not-lucene-as-the-engine--and-what-to-take-from-it)) and is not repeated here.
> Facts about the candidate libraries were read from local checkouts of jVector (`main`, `4.0.0-rc.9` plus
> twelve commits), Lucene (`main`, `11.0.0-SNAPSHOT`) and hnsw-sb, and verified against the evitaDB branch
> `258-fulltext-support`. Claims taken from an external research note on filtered and quantized ANN that could
> not be verified against a local checkout are marked *(external)*.

---

## 1. Goal and expectations

### 1.1 What the spike decides

P6 is a **spike**, not a feature prototype. It does not deliver vector search; it decides the mini-gate of
research [§7](../research.md#7-next-steps-prototypes-and-phasing) and verifies that the architectural assumption of research [§5.3](../research.md#53-the-off-heap-model--an-answer-to-the-compatibility-concern) — immutable append-only files
read through mmap, off the heap — really holds in evitaDB. Both are empirical questions that rest on numbers
nobody has measured.

The mini-gate's question is **not** "jVector or an in-house HNSW". Reading the libraries shows that no
library on the table is the thing evitaDB needs. What evitaDB needs is a **retrieval subsystem with several
physical access paths** — an exact scan over a candidate bitmap, an unfiltered graph walk with
post-filtering, a graph walk with an in-walk acceptance test, and a predicate-aware walk that widens its
neighbourhood when the filter is sparse — chosen per query by a **planner** that knows the candidate set's
cardinality exactly ([§3.4](#34-filtered-ann-and-the-planner)). Under those paths sit two independent representations: the graph topology and
the vector codec used for traversal, with the raw vectors kept reachable for reranking ([§3.5](#35-codecs-and-oversampling)). A library can
supply the graph, a codec, or a searcher; none of the candidates supplies the planner, and only Lucene
supplies a predicate-aware searcher.

The spike's deliverable is therefore **the measured curve family that lets the planner be written** —
latency and recall per strategy per selectivity, recall per codec per oversampling factor, heap bytes per
component — plus the answer to **which graph implementation stands behind the seam** of [§2.6](#26-the-seam-every-library-contact-is-reversible). Three
questions have to be answered by measurement, because the sources confirm they are open:

1. **Filtering.** At which selectivity does the exact scan beat every graph strategy; at which does the
   predicate-aware walk beat the plain in-walk test; and is there a band where no graph strategy reaches the
   recall criterion at all — which would make the exact scan a required path, not a fallback.
2. **Codec.** How much heap an 8-bit, a 4-bit and a 1-bit code save at the same *final* recall, what
   oversampling factor each needs, and whether a 1-bit code with asymmetric (RaBitQ-style) scoring behaves
   differently from plain symmetric binary quantization — they are not the same thing ([§3.5](#35-codecs-and-oversampling)).
3. **Graph.** Whether a Vamana-pruned base layer beats an HNSW-pruned one at the same memory budget and
   recall target on our data, and whether the hierarchy on top of it earns its cost. jVector exposes both
   knobs in one library, so this is a cheap experiment, not a second implementation.

### 1.2 Criteria

The two criteria of research [§5.2](../research.md#52-quantization-and-memory) and [§7](../research.md#7-next-steps-prototypes-and-phasing):

- **recall@10 ≥ 0.95** against a brute-force ground truth, measured *with rescoring* against the raw
  vectors, never over the quantized codes alone;
- **latency < 10 ms** for 1M vectors of 768 dimensions, one thread, without concurrent load.

Three more criteria without which the result is unusable for a decision:

- **a memory cap**, stated as *heap-resident bytes per indexed vector*, accounted per component (codes,
  graph, ordinal mapping, search scratch) and separately from *mapped* bytes ([§2.8](#28-the-memory-accounting-rule)). The budget it is
  checked against is research [§5.2](../research.md#52-quantization-and-memory)'s estimate — ~90–100 MB of codes and ~100–250 MB of graph per
  1M × 768 — and the raw vectors must never appear on the heap, verified by measurement, not by
  construction. That budget is a **1-bit budget**: an 8-bit code is 768 MB per 1M (eightfold over it) and a
  4-bit code 384 MB (fourfold), so the cap decides the codec, not merely the library;
- **behaviour under a filter**: latency and recall are measured for filtered ANN across selectivities,
  because that is exactly where the choice between a graph walk and a brute-force scan breaks ([§3.4](#34-filtered-ann-and-the-planner));
- **incremental maintenance**: adding and deleting a vector at runtime without rebuilding the whole index — a
  property evitaDB's write path needs and which Lucene by construction does not have ([§3.2](#32-the-graph-component-candidates-and-recommendation)).

### 1.3 The mini-gate's decision criteria

The criteria and their thresholds are fixed in advance so the result cannot be rationalized retrospectively.
The mini-gate does not decide by a single number: a candidate may win on performance and lose on integration.
*Blocking* means that failing it alone decides against a candidate. Cells marked *known* were decided by
reading the checkouts; the spike confirms them by measurement or by a running JVM but does not re-open them.

| Criterion | How it is measured | Threshold | Weight | jVector (path A) | Lucene as library (path L) |
|---|---|---|---|---|---|
| recall@10 with rescoring | against a brute-force ground truth | ≥ 0.95 | blocking | measured | measured |
| query latency | 1M × 768, one thread, p50 and p95 | < 10 ms | blocking | measured | measured |
| filtered ANN | latency and recall per strategy per selectivity ([§4.4](#44-the-filtered-matrix)) | no selectivity band below 0.95 that the exact scan does not cover cheaply | blocking | measured with our searcher ([§2.7](#27-the-searcher-and-the-planner-are-evitadb-owned)) | measured with `FilteredHnswGraphSearcher` |
| incremental maintenance | insert and delete at runtime without a full rebuild; recall drift under churn ([§4.7](#47-the-churn-protocol)) | works within a measured rebuild cadence | blocking | **known: yes**, two-phase delete with a quiescent `cleanup()` ([§3.2](#32-the-graph-component-candidates-and-recommendation)) | **known: insert-only**; liveness bits plus rebuild, cadence measured |
| JPMS | `requires` from a named module | works, or has a workaround | blocking | **known: automatic module**, no `jlink` ([§2.6](#26-the-seam-every-library-contact-is-reversible)) | **known: named module** |
| interruptibility | a cancellation check inside the walk | the query can be cancelled | high | **known: absent in the library's searcher**; present in ours ([§2.7](#27-the-searcher-and-the-planner-are-evitadb-owned)) | **known: `earlyTerminated()` per iteration** |
| codec pluggability | a foreign approximate scorer drives the walk | works without a fork | high | **known: `ScoreFunction`** seam ([§2.6](#26-the-seam-every-library-contact-is-reversible)) | scorers are plain classes |
| heap memory | bytes per indexed vector per component, JOL ([§2.8](#28-the-memory-accounting-rule), [§4.6](#46-memory)) | codes + graph within the [§1.2](#12-criteria) budget; raw vectors not on the heap | high | measured; the raw block needs our mapped `RandomAccessVectorValues` on JDK 21 ([§2.3](#23-storage-append-only-files-fileindex-naming-the-first-mmap)) | measured |
| mmap compatibility | which read backend is selected on the target JDK | maps, no 2 GB failure | high | **known: `MappedChunkReader` on 21, `MemorySegmentReader` on 22** ([§2.3](#23-storage-append-only-files-fileindex-naming-the-first-mmap)) | not applicable — evitaDB reads its own files |
| maturity and format | released version, format stability, configuration surface | see [§3.2](#32-the-graph-component-candidates-and-recommendation) | high | RC only; JMX-global builder config; stale `UPGRADING.md` | released 10.x; stable format |
| dependency size | the transitive tree, CVEs | no frameworks | medium | four, one dead ([§2.6](#26-the-seam-every-library-contact-is-reversible)) | none |
| graph family | HNSW vs Vamana vs hybrid at equal memory and recall ([§3.7](#37-the-realization-procedure), step 4) | informative | medium | all four corners in one library | HNSW reference only |

The gate's result is not only "yes/no" but also **a choice of version** (jVector 3.0.x against 4.0.0-rc.x,
[§3.2](#32-the-graph-component-candidates-and-recommendation)), recorded separately.

### 1.4 Non-goals

- **No integration into the query language.** No new constraint, no change to `orderBy(relevance())`, no RRF
  fusion in production code; RRF appears at most as an offline computation over two rank lists to verify
  that fusion makes sense ([§3.6](#36-the-text--vector-hybrid)). The DSL's shape is O4's and depends on the text gate (P5 → P1 → P2).
- **No query-side embedding (O7).** The harness produces the query vectors itself — they are part of the
  dataset or are generated the same way as the document vectors. Who embeds a user's query in production is
  a product question.
- **No production write path.** Incremental adding and deleting is verified as a property of the library,
  not connected to the transactional layer nor to the WAL.
- **No partition-assisted structure for pathologically selective filters** (the Curator family, [§3.4](#34-filtered-ann-and-the-planner)). The
  spike only verifies that the seam of [§2.6](#26-the-seam-every-library-contact-is-reversible) does not make one impossible later.
- **No concurrency measurement.** All criteria are single-threaded; concurrent load and the interaction of
  mmap paging with evitaDB's other I/O are measured before production, not in P6 ([§5](#5-open-questions)).

### 1.5 Relation to the neighbouring work and the starting state

P6 stands apart from the text branch: the decision gate (P5 → P1 → P2) measures the text core, P6 does not
enter it, and P6's result has no influence on the text gate. P6 can therefore run in parallel with
[`p1-index-core.md`](p1-index-core.md) and [`p2-transactional-maintenance.md`](p2-transactional-maintenance.md)
as soon as its entry conditions ([§2.1](#21-jdk-21-and-the-module-wiring)) are met. The branches meet only in the delivery phase F2 (the
text × vector hybrid, research [§5.5](../research.md#55-the-text--vector-hybrid)) and in the rank profile of research [§4.3](../research.md#43-ranking-feature-vector--rank-profiles-two-phases)
([`p7-rank-profiles-and-boost-channel.md`](p7-rank-profiles-and-boost-channel.md)), into which vector
similarity will enter as another lane or as the result of a fusion. P6 must not choose a solution that makes
either impossible ([§2.9](#29-hybrid-readiness), [§3.6](#36-the-text--vector-hybrid)).

**O5 (the origin of the document embeddings) is marked resolved in the research** — Sage
(`$OSS_PROJECT_PATH/Sage`, research [§1.1](../research.md#11-the-wider-platform-context-revision-2026-08-11)) supplies them as entity data. It is resolved only politically:
the path research [§5.6](../research.md#56-assumption) describes (an attribute or associated data of type `float[]`) does not exist in
evitaDB ([§2.2](#22-how-a-vector-gets-into-evitadb)), so O5 reopens technically and P6 has to answer it. **O7 stays out of scope.**

**The starting state is a production e-commerce deployment where a vector leg already runs** (internal
analysis, its [§3.5](#35-codecs-and-oversampling)): kNN search over a vector field in plain Lucene, embedding of documents and queries through
external embedding APIs, optional L2 normalization, a minimum text length below which nothing is embedded,
and a hybrid composition that is a plain **sum of Lucene scores with manually set weights** (a content
boost against a semantic boost) — exactly the calibration RRF avoids. Two consequences belong in the plan,
not in a footnote. First, in delivery terms the new solution is **a step back until phase F2**: deploying
where semantic search is used today needs either a finished F2 or both solutions running in parallel.
Second, a proven shape for O7 exists: the query's embedding is computed **inside the search layer**, behind
a **two-level cache of query vectors (in memory and in the database) with a versioning key** that changes
with the embedding's configuration and thereby invalidates the cache — without it every keystroke during
suggestion pays for an external model call. P6 does not decide O7; it records that the pattern exists.

---

## 2. Technical requirements

### 2.1 JDK 21 and the module wiring

**The baseline is JDK 21; the module switches are not in place.** `dev` carries `<java.version>21`,
compiles at `--release 21` and runs on an Azul Zulu 21 image, but no `pom.xml` in the tree contains
`--add-modules`, `--enable-preview` or `jdk.incubator`, no `module-info.java` among the 23 descriptors
requires `jdk.incubator.vector`, and the Docker `EVITA_JAVA_OPTS` is empty. That matters because of how
jVector selects its arithmetic:

- `VectorizationProvider` picks the implementation **once at class load**, process-wide and immutably. A
  forced `-Djvector.vectorization_provider={default|panama|native}` is honoured first and **throws on
  failure**. Otherwise, on a JDK ≥ 20, three checks each fall back to the scalar
  `DefaultVectorizationProvider` with only a warning: the JDK-8301190 locale bug below 20.0.2, the
  readability of `jdk.incubator.vector` in the boot layer (*"Java vector incubator module is not readable.
  For optimal vector performance, pass '--add-modules jdk.incubator.vector' to enable Vector API."*), and
  a disabled C2. The native provider is tried only when
  `-Djvector.experimental.enable_native_vectorization=true` is set, needs x86-64, a loadable `libjvector.so`
  and the JDK 22 jar layer, and is not usable from the checkout (empty Highway submodule, no prebuilt
  library); then Panama. Lucene's own `VectorizationProvider` follows the same pattern.
- On today's `dev` the version check passes and the boot-layer check fails: **a silent scalar run that
  reports numbers and no error**, which says nothing about the target state in either direction. jVector
  does not break without the module — a free fallback for production, a trap for measurement.
- jVector is a **multi-release jar**: the root is `jvector-base` at release 11; `META-INF/versions/20` is
  the Panama SIMD layer (compiled **without** `--enable-preview`, so it loads on any JDK ≥ 20);
  `META-INF/versions/22` is the native layer with the FFM bindings, `MemorySegmentVectorFloat` and
  `MemorySegmentReader`. A JDK 21 run therefore sees the 20 layer: the Panama provider once the module is
  readable, `MappedChunkReader` as the read backend, and the on-heap `ArrayVectorFloat` vector type; only a
  JDK 22 run with the native provider gets an off-heap `MemorySegment` vector type and the FFM reader. **The
  spike measures the JDK 21 configuration by construction**; a JDK 22 run of the harness module alone is an
  optional second data point, and if it is not taken the record says so rather than leaving a gap.

**Five coordinated places** must carry the switch, or the run lands on the scalar path:

1. `requires static jdk.incubator.vector;` in the descriptor of the module holding the vector mathematics.
   A hard `requires` was rejected by
   [`2026-09-10-simd-vector-api-feasibility`](../../2026-09-10-simd-vector-api-feasibility/README.md) — every embedded user and
   every launcher would need the flag or fail with `NoClassDefFoundError` — in favour of `requires static`
   plus self-detection of module, C2 and CPU at class-init, a scalar twin for every kernel and per-kernel
   switches. A missing switch then degrades to the scalar path *by design*: the server never fails to start
   over a forgotten flag, but a silent scalar run still reports a meaningless latency.
2. `--add-modules jdk.incubator.vector` in the compiler's `<compilerArgs>`.
3. The same in surefire's `<argLine>`, otherwise tests run differently from the compilation.
4. The same in the runtime configuration, `EVITA_JAVA_OPTS` in `docker/Dockerfile`.
5. The same in **JMH's `jvmArgs`** or `@Fork(jvmArgsAppend = …)`. JMH forks its own JVM and does not inherit
   surefire's `<argLine>`; without this the latency benchmark runs on the scalar provider, prints numbers
   and reports no error.

Items 2–4 are the wiring that issue #1541 ("provider and wiring" of the SIMD record) adds for the whole
server; **P6 depends on that wiring rather than introducing a second one** — if #1541 lands first the spike
gets its switches free, if the spike runs first whatever it wires must be the shape #1541 specifies. Item 5
is P6's own: JMH is outside the server's launcher and no other line of work covers it. The one fact P6 owes
that record — whether the chosen HNSW library carries its own Panama provider with the same `--add-modules`
requirement — is answered above for jVector: yes.

**Two more switches are mandatory in every harness invocation**: `-Djvector.vectorization_provider=panama`,
so that a misconfigured run dies loudly instead of measuring the scalar path, and
`-Djvector.management.backend=none` together with logging the effective values of
`GraphIndexBuilderConfig` ([§3.2](#32-the-graph-component-candidates-and-recommendation)), because the non-deprecated builder reads structural parameters from a
process-global, JMX-mutable singleton.

**Module isolation.** The vector mathematics lives in a **single module with its own descriptor and a scalar
fallback**, not scattered through `evita_engine` — the pattern Lucene, jVector and now evitaDB's own SIMD
provider all use, because an incubator module must not be a hard condition of startup. A hard side
condition: `<java.driver.release>17` pins `evita_common`, `evita_api`, `evita_query` and the three gRPC
shared/client modules to release 17, enforced per build by `.github/actions/verify-driver-on-jdk17`, so
**the vector module must not be one of those six and nothing on the driver's dependency path may touch
vector code**. `evita_engine` is clear. Whether the vector distance kernels share one provider with the SIMD
record's bitmap and price kernels or get their own is a design-time question — the axes differ, the
module-detection and C2 checks in front of both are identical.

### 2.2 How a vector gets into evitaDB

**Neither `float` nor `double` is a supported data type.** `EvitaDataTypes.SUPPORTED_QUERY_DATA_TYPES` holds
`byte`, `short`, `int`, `long`, `boolean`, `char`, `String`, `BigDecimal`, temporal types, ranges, `Locale`,
`Currency`, `UUID` and a few specials; the only decimal type is `BigDecimal`. The `primitiveWrappers` table
contains `float.class → Float.class`, but it is a boxing map, not the supported set. Arrays are validated by
component type (`isSupportedTypeOrItsArray`), so `int[]` passes and `float[]` does not. The second path,
associated data through a `ComplexDataObject`, is worse: `ComplexDataObjectConverter` does not reject
`float` but silently converts it to a `BigDecimal` through a decimal string — 768 `BigDecimal` instances in
the `DataItem` tree per entity, unusable in memory, computation and write size. **Both paths research [§5.6](../research.md#56-assumption)
names are closed.**

**Requirement for the spike: a `byte[]` carrying float32 in little-endian** (variant B). `byte[]` passes
validation, no engine change is needed, and 768 × 4 B = 3,072 B per entity fits comfortably below the 2 MB
`DEFAULT_OUTPUT_BUFFER_SIZE` of one record. The cost — type information lost, the encoding convention held
outside the type system — is acceptable for a spike.

**The production decision belongs after the mini-gate and is three-way**, leaning on how the ownership of
the vector files turns out ([§3.3](#33-the-file-layout)):

- **A — extend the type system with `float[]`.** Rejected for the spike because it touches every path an
  attribute value passes through — filters, ordering, projections into gRPC, GraphQL and REST, Kryo and WAL
  serialization with backward compatibility — an impact utterly disproportionate for a spike.
- **C — a custom `StoragePart` and custom mutations.** Full control over the format, can avoid Kryo through
  `StorageRecord#readRaw` ([§2.3](#23-storage-append-only-files-fileindex-naming-the-first-mmap)); the cleanest production path but extra work before the gate is decided.
- **D — a dedicated field type for embeddings**, not filterable, not sortable, not returned as an ordinary
  attribute value. OpenSearch declares `VECTOR_SEARCH` as a separate capability of the data format beside
  `FULL_TEXT_SEARCH` and `COLUMNAR_STORAGE` (`FieldTypeCapabilities`), each with its own physical structure —
  a vector field there is its own field type, not an attribute that happens to contain numbers. D needs a
  schema declaration, a mutation, a path inwards and serialization (the `evita-schema-change` path) but
  **none of the query projections**, a substantially smaller surface than A. It is the most promising
  variant and the least verified; the shape of the declaration belongs in
  [`schema-design.md`](schema-design.md).

### 2.3 Storage: append-only files, `fileIndex` naming, the first mmap

**What holds.** Writing goes through a single handle in append mode (`WriteOnlyFileHandle`), fsync through
`FileChannel.force(true)`, reading through a pool of read-only handles; old content is never overwritten and
only compaction reclaims it (`OffsetIndex`). Research [§5.3](../research.md#53-the-off-heap-model--an-answer-to-the-compatibility-concern)'s philosophy stands.

**What differs from the research's assumption: there is no "file per catalog version".** The file name
carries a **`fileIndex`, increased only on compaction** — `prefix_<fileIndex>.catalog`,
`camelCase-<pk>_<fileIndex>.collection`, `prefix_<fileIndex>.wal`, `prefix.boot` (all generated by
`CatalogPersistenceService`). The mapping from a catalog version to a file is done by the bootstrap file: an
array of fixed-length 36-byte records (`CatalogBootstrap.RECORD_SIZE`), addressable by index, so the state at
a version or a moment is a position computation. **Requirement: vector files adopt the same `fileIndex`
convention and bind to a bootstrap record; they are never named by `catalogVersion`**, otherwise the
prototype builds a second, parallel and inconsistent versioning system.

**Cleanup is a finished hook.** `ObsoleteFileMaintainer#removeFileWhenNotUsed(catalogVersion, path,
removalLambda)` — used by `DefaultCatalogPersistenceService` today — is the API through which old vector
files are registered for deletion. "Nobody looks at the file any more" is decided by the retention floor
(`getRetentionFloor`): the minimum of a monotonically growing floor of active readers and of explicitly
pinned catalog versions. Deletion runs under an exclusive directory lock in `purgeObsoleteFiles`,
complemented by `reclaimUnreachableFiles` for files no retained bootstrap record reaches. Time travel is
configuration (`StorageOptions.timeTravelEnabled`, `minimalActiveRecordShare`); with it off, old files are
deleted right after compaction.

**mmap does not exist in the repository.** `MappedByteBuffer`, `FileChannel.map`, `MemorySegment`, `Arena`,
`java.lang.foreign`, `jdk.incubator.vector`, `FloatVector`, `jvector` and `hnsw` return zero hits; the
vendored `evita_roaring_bitmap` has no upstream `buffer/` package. Reading goes through a `RandomAccessFile`
wrapped in `RandomAccessFileInputStream` and `ObservableInput`; `FileChannel` is used exclusively for fsync;
the `OffHeapMemoryManager` for in-progress transactions is `ByteBuffer.allocateDirect`, something else.
**P6 introduces the first mmap into evitaDB**, with no precedent to build on. Two things follow:

- **unmapping is an explicit action** — on JDK 21 a `MappedByteBuffer` is released by the garbage collector
  or `Unsafe.invokeCleaner`, on JDK 22 a shared `Arena` is closed explicitly — and a mapped file must not
  be deleted before the mapping is released;
- **a live mapping is invisible to the retention floor**, which tracks sessions and catalog versions, not
  who holds a mapping. A file registered for deletion and mapped at the same time can disappear from under
  a reader. **The pin protocol:** the vector reader calls `catalogVersionPinned(v)` on mapping and
  `catalogVersionReleased(v)` on unmapping, and the release of the mapping happens *before*
  `catalogVersionReleased`.

**Reading with jVector on the target JDK.** `ReaderSupplierFactory` tries `MemorySegmentReader` (JDK 22
layer only — shared `Arena`, one mapping of the whole file, `MADV_RANDOM`, best-effort `prefetch`, no size
cap; absent on 21, the factory warns and moves on), then `MMapReader` (Linux-only, in `jvector-examples`,
needs `com.indeed:util-mmap` — unusable), then **`MappedChunkReader`**: `FileChannel.map` in chunks of
`Integer.MAX_VALUE` bytes over one shared channel, no 2 GB cap, no `Unsafe`. `SimpleMappedReader`, the class
with the explicit 2 GB refusal and `Unsafe.invokeCleaner`, is a sample the factory never selects. The one
constraint that remains is the deletion rule above.

**The raw vectors stay off the heap by division of labour, not by library property.** jVector reads its own
on-disk graph and vectors off-heap; but during *build* the vectors reach `GraphIndexBuilder` through a
`RandomAccessVectorValues`, and the library's in-memory implementations hand out on-heap `float[]`; the only
disk-backed one, `MMapRandomAccessVectorValues`, sits in `jvector-examples`. **Requirement: evitaDB writes the
mapped `RandomAccessVectorValues` over its own vector file**, and the JOL measurement of [§4.6](#46-memory) verifies that
the raw block never reaches the heap during build or search.

**A format detail for storing vectors in the existing structure:** `StorageRecord` has 22 bytes of overhead,
a continuation bit, and raw read methods without deserialization (`readRaw`, `readRawInto`) — the way of
avoiding Kryo on the read path.

### 2.4 Ordinals are not primary keys

Graph indexes — jVector and Lucene alike — address nodes by **dense ordinals `0..N-1`**; entity primary
keys are **sparse** (entities are deleted, keys are not renumbered); the acceptance `Bits` is indexed by
ordinal. The real shape of the inline test is `bitmap.contains(ordToPk[ord])`, and **both directions** are
needed: `ord → pk` during the walk and when translating results back to entities, `pk → ord` on write when a
vector is replaced or deleted.

**Requirement: the mapping is an artifact bound to a catalog version, stored beside the vectors, versioned
with them, rebuilt on compaction — and owned by evitaDB, not the graph library.** jVector's on-disk graph
"may not contain holes in the ordinal sequence" and requires an ordinal remap when persisting after deletes;
Lucene has no deletes at all. Whoever owns the mapping also owns tombstones and the compaction of the
ordinal space, and that is the storage layer. A chunk-per-entity unit ([§5](#5-open-questions), OP6-12) is an ordinal-mapping
concern and lives entirely here.

### 2.5 The candidate-set contract

The strategies of [§3.4](#34-filtered-ann-and-the-planner) read the candidate set in two ways and the contract serves both cheaply:
`contains(ord)` for the in-walk and predicate-aware walks (hundreds to thousands of calls per query), and
iteration for the exact scan (every member once), plus `cardinality()` for the planner. evitaDB's `Bitmap`
already has `contains(int)`, sorted iteration from its RoaringBitmap backing, and a stored cardinality; the
ordinal map of [§2.4](#24-ordinals-are-not-primary-keys) sits between the walk and the bitmap, one array read per test. jVector's `Bits` is a
one-method interface and `Bits.intersectionOf` short-circuits on `ALL`/`NONE`, so an unfiltered search pays
nothing; the adapter is trivial.

**The planner's decision is exact, not heuristic.** The candidate bitmap is computed at that moment, so
selectivity is known, not estimated — the switch is a threshold function over a known number, not an
estimation model that can be wrong.

**Membership is evaluated against the existing bitmap by default**, never against a materialized
`boolean[N]` per query *(external: the FANN study's ACORN implementation paid that overhead)*. The opposite
is measured too: for a dense filter (50 %, 100 %) a walk consulting a Roaring container per test may lose
to one that first copies the bitmap into a flat bitset of `N/8` bytes (125 KB per 1M, cheap to fill from a
Roaring iterator). The cell "in-walk acceptance at 50 %" runs both ways, and the planner may pick the
materialization as part of a strategy above some cardinality.

### 2.6 The seam: every library contact is reversible

**All contact with a library passes through the five contracts of [§3.1](#31-the-shape-a-retrieval-subsystem-with-a-planner) — nothing wider.** If jVector passes
with its maturity caveat ([§3.2](#32-the-graph-component-candidates-and-recommendation)), the version or the library is likely to change within a year, and the seam
is the only thing distinguishing that swap from a rewrite. Whether jVector, Lucene or in-house code stands
behind the contracts must not be recognizable from the calling code. The seam is real in both libraries:

- jVector's `ImmutableGraphIndex.View` exposes `getNeighborsIterator(level, node)`,
  `processNeighbors(level, node, scoreFunction, visited, neighborProcessor)` and the live-node bits
  (`OnHeapGraphIndex`); `ScoreFunction.ApproximateScoreFunction` / `ExactScoreFunction` (the whole contract
  is `float similarityTo(int node2)`) is what a `SearchScoreProvider` carries, so a foreign codec plugs in
  as an approximate scorer and **the graph never sees vector bytes**.
- Lucene's `org.apache.lucene.util.hnsw`, `util.quantization` and `search.knn` are **exported from the named
  module `org.apache.lucene.core`**, `lucene-core` has no third-party runtime dependencies, and its scorers
  and quantizers are plain classes over `float[]`.

**JPMS.** jVector has no `module-info.java`, no `Automatic-Module-Name` and no manifest beyond
`Multi-Release: true`: on the module path it is an automatic module named from the file (`jvector`), which a
named evitaDB module can `requires` but which is unstable across artifact renames and **disqualifying for
`jlink`**. The production remedy — a shaded copy with our own descriptor, an upstream pull request adding
`Automatic-Module-Name`, or living with the automatic module — is decided after the gate; the spike only
proves the `requires` compiles and the multi-release layer resolves under it.

**Licence and dependencies.** Apache 2.0. jVector's transitive dependencies are four — `agrona:1.20.0`
(load-bearing), `commons-math3:3.6.1` (a single import), `slf4j-api:2.0.16` (already present) and
`snakeyaml:2.4` (unused by `jvector-base`; evitaDB has 2.6 anyway) — none a framework.

### 2.7 The searcher and the planner are evitaDB-owned

jVector's `GraphSearcher.search(scoreProvider, topK, rerankK, threshold, rerankFloor, Bits acceptOrds)`
applies the filter **only as an acceptance test on popped candidates**: a rejected candidate is kept out of
the result set, but its neighbours are scored and pushed regardless, upper layers are walked with
`Bits.ALL`, and nothing widens the neighbourhood when most neighbours are rejected (`acorn` does not occur
in the repository). The javadoc says it verbatim: *"It is caller's responsibility to ensure that there are
enough acceptable nodes that we don't search the entire graph trying to satisfy topK."* **jVector has no
brute-force fallback, no predicate-aware walk and no cancellation of any kind** — no predicate, deadline,
visited limit or `Thread.interrupted()` check on the read path; a cancelled evitaDB query would run to
completion. Lucene's `HnswGraphSearcher` checks `collector.earlyTerminated()` on every iteration and
`TimeLimitingKnnCollectorManager` lives in core; OpenSearch enforces its `QueryTimeout` through that route.

**Requirements that follow:** the planner ([§3.4](#34-filtered-ann-and-the-planner)) is owned by evitaDB and is not optional; every search
strategy carries **a cancellation check per iteration and a visited budget**, which is only possible in a
searcher evitaDB writes over the graph contract ([§3.1](#31-the-shape-a-retrieval-subsystem-with-a-planner)) — the gate row "interruptibility" is decided against
jVector's *searcher*, not against jVector.

Useful on the way: `SearchResult` carries `visitedCount` (scored edges, not distinct nodes),
`expandedCount`, `expandedCountL0`, `rerankedCount` and `worstApproximateScoreInTopK`; rescoring is built in
(`rerankK ≥ topK` enforced); `resume(additionalK, rerankK)` continues a search for more results — the
continuable retrieval [§2.9](#29-hybrid-readiness) needs; `usePruning` is a deprecated hard no-op.

### 2.8 The memory accounting rule

Report **the total incremental memory caused by enabling vector search**, per component, then per indexed
vector. Heap-resident and mapped bytes are accounted separately; the cap of [§1.2](#12-criteria) applies to the heap
column. A 768-dimensional float32 vector is 3,072 B while a graph of degree 32 is a few hundred bytes per
node, so the traversal representation is where the memory goes — and a 1-bit code turns that around and
makes the graph the dominant term.

| Component | Where it lives | Order of magnitude per 1M × 768 | How measured |
|---|---|---|---|
| raw vectors | mapped file, off-heap | 3.07 GB mapped, **0 on the heap** — verified, not assumed | JOL heap histogram with rescoring on ([§4.6](#46-memory)) |
| traversal codes | heap, direct buffer, or mapped — a fork ([§3.3](#33-the-file-layout)) | 96 MB (1-bit) … 768 MB (8-bit) | JOL / `ramBytesUsed` |
| graph topology | heap | jVector on-heap: 8 B per edge (id + score) plus ~40 B per node, ≈ 300 MB at degree 32; Lucene's `NeighborArray` likewise keeps scores; on disk both keep ids only, 4 B per edge | `ramBytesUsed()` cross-checked by JOL |
| ordinal mapping | heap | 8 MB (two `int[1M]`) | JOL |
| search scratch | heap, per searcher | jVector allocates per-thread `GraphSearcher` state and per-thread build scratch retained for the builder's life | JOL under concurrent searchers |
| construction buffers | heap, transient | `neighborOverflow × degree` edges per node during build; the PQ training set | peak heap during build |

Two consequences are visible before measuring. If the 1-bit codes fail the recall criterion and the 4-bit
code passes, the memory criterion is renegotiated or the codes are mapped rather than heap-resident — Lucene
keeps its quantized vectors off-heap in its directory abstraction for exactly this reason; the spike
surfaces that fork with numbers and does not decide it. And at 1-bit the graph is three times the codes, so
the on-heap adjacency layout (scores next to ids, per-node object overhead) is the next thing to look at —
an argument for an on-disk or flat-array graph representation in path B and one more reason the graph
contract must not expose the library's node objects.

### 2.9 Hybrid readiness

The vector leg must be able to return **a ranking of candidates, not only a top-K with an incomparable
score**, for a `K` markedly larger than the page of results. `rerankK` governs the ranking's depth;
`resume(additionalK, rerankK)` is the cheaper alternative when the fusion needs more depth than the first
pass fetched, and its cost at depth is one measurement of [§4.4](#44-the-filtered-matrix). Verifying that the depth can be set high
enough without breaching the latency criterion is cheap and belongs there.

---

## 3. Proposed approach

### 3.1 The shape: a retrieval subsystem with a planner

The shape is the same whichever library wins, because the libraries fill *slots* in it rather than
replacing it. Five contracts, each deliberately thin, each with a reason it must be separate:

| Contract | Shape | Why separate |
|---|---|---|
| **Candidate set** | `contains(ord)`, `cardinality()`, iteration over members | evitaDB's `Bitmap` behind the ordinal map ([§2.4](#24-ordinals-are-not-primary-keys), [§2.5](#25-the-candidate-set-contract)); every strategy reads it, and the planner reads its cardinality *exactly* |
| **Vector store** | dimensions, encoding, random access to a vector's bytes by ordinal, for the raw block and for each code block | the raw block is mmapped and off-heap ([§3.3](#33-the-file-layout)); code blocks may be heap or mapped; a codec change must not touch the graph |
| **Scorer** | `similarityTo(ord)` for a prepared query, one implementation per encoding, plus an exact one over the raw block | the seam a foreign codec plugs into — jVector's `ScoreFunction` and Lucene's scorers are both already this shape |
| **Graph** | node ordinals, per-level neighbour iteration, live-node bits, insert and tombstone | jVector's `View` and Lucene's `HnswGraph` are both already this shape; the graph never sees vector bytes and must not expose the library's node objects ([§2.8](#28-the-memory-accounting-rule)) |
| **Search strategy** | one of: exact scan over the candidate set; graph walk with post-filter and oversampling; graph walk with in-walk acceptance; predicate-aware walk with second-hop widening — each with a cancellation check and a visited budget | the planner chooses among them per query; a strategy living inside a library's searcher cannot be given a cancellation check or a budget ([§2.7](#27-the-searcher-and-the-planner-are-evitadb-owned)) |

The **planner** sits above the strategies, reads the candidate set's cardinality and the index size, and
picks by measured thresholds ([§3.4](#34-filtered-ann-and-the-planner)). Above the planner, the oversampled candidates are **reranked by the
exact scorer** and the top-K is returned with a ranking, not merely a score ([§2.9](#29-hybrid-readiness), [§3.6](#36-the-text--vector-hybrid)).

Two things the shape must permit without implementing them now: a partition-assisted secondary structure
for pathologically selective filters (a sixth strategy plus a sidecar on the vector store — nothing above
forbids it), and a chunk-per-entity unit (an ordinal-mapping concern, [§2.4](#24-ordinals-are-not-primary-keys)).

### 3.2 The graph component: candidates and recommendation

**Path A — jVector standalone, outside evitaDB.** A harness that builds an index over a dataset, measures
recall and latency and does not touch evitaDB's storage. It answers the two main criteria and the
quantization question quickly, nothing integrational. Because the library's searcher can host neither the
filtered strategies nor a foreign codec ([§2.7](#27-the-searcher-and-the-planner-are-evitadb-owned)), the harness writes its own searcher over jVector's `View` and
its own scorers — that is the seam of [§3.1](#31-the-shape-a-retrieval-subsystem-with-a-planner) being built, not extra work beside it. What jVector brings:

- **The graph family in one library.** jVector combines the hierarchy of HNSW with the Vamana algorithm
  (the core of DiskANN) inside each layer. Two constructor parameters make the graph question of [§1.1](#11-what-the-spike-decides) a
  one-library experiment: `alpha` (pruning aggressiveness; `1.0` yields "the equivalent of the lowest level
  of an HNSW graph", `≈ 1.2` longer Vamana edges) and `addHierarchy`. The four corners are
  `alpha = 1.0 / ≈ 1.2` × hierarchy on/off. One caveat: jVector's diversity pruning is Vamana's at every
  `alpha` and equals Lucene's Malkov heuristic only at `alpha = 1`, so a real Lucene HNSW is built as the
  reference point. Level assignment uses `mL = 1/ln(degree₀)` from a fixed `Random(0)`, so build
  nondeterminism comes only from insertion order.
- **Incremental maintenance, in a constrained shape.** `addGraphNode` and `markNodeDeleted` are thread-safe
  with concurrent inserts (in-progress insertions are tracked and considered as neighbour candidates by each
  other); `removeDeletedNodes()` and `cleanup()` are **not** — `cleanup()` "should not be called during
  concurrent modifications to the graph" and "must be called before writing to disk". Deletion is therefore
  **two-phase**: a thread-safe tombstone, then a FreshDiskANN-style edge-repair pass that needs a quiescent
  window with respect to writers. A live `View` prevents the reclamation of marked-deleted nodes, so the
  guidance is a **new `View` per search** — a reader pool keeping views open would block reclamation. The
  builder retains per-thread scratch and a copy of the `RandomAccessVectorValues` for every thread that
  calls `addGraphNode`, and its javadoc says "spawning a new Thread per call is not advisable. This includes
  virtual threads" — a direct constraint on how the write path may drive it.
- **A construction hazard.** Every explicit-parameter constructor of `GraphIndexBuilder` is `@Deprecated`;
  the only non-deprecated path is the fluent `builder(...)`, which exposes `beamWidth`, `neighborOverflow`
  and `alpha` but reads `addHierarchy`, `refineFinalGraph` and the build-time compression from
  `GraphIndexBuilderConfig.getInstance()`, a **process-global singleton mutable over JMX** (defaults:
  hierarchy on, refine on, no compression). For a spike that varies `addHierarchy` per run the deprecated
  constructors are the honest choice; for production the global has to be pinned and logged ([§2.1](#21-jdk-21-and-the-module-wiring)), and it
  sits in the maturity row of the gate.
- **A version fork, independent of the gate.** jVector 4.0 has **no final release**: six betas, nine
  release candidates, `main` at `4.0.1-SNAPSHOT` twelve commits past `rc.9`, and `UPGRADING.md` already
  stale against the code. The stable line on Maven Central is **3.0.x**, which lacks the hierarchical graph,
  NVQ and Fused PQ. **The spike measures on the checkout** because it decides architecture and needs the
  target capabilities; the record must state explicitly that production either waits for the final 4.0 or
  runs 3.0.x knowing what is missing.

**Path L — Lucene's vector packages as a library.** The research rejected Lucene as an *engine* and permits
it as an ordinary Maven dependency; [`p5-analyzers.md`](p5-analyzers.md) already takes that path, and the
vector packages are exported for exactly that use ([§2.6](#26-the-seam-every-library-contact-is-reversible)). The same harness over `HnswGraphBuilder` /
`OnHeapHnswGraph` (Malkov diversity heuristic, `ramBytesUsed()`, public `addGraphNode`),
`FilteredHnswGraphSearcher` and `OptimizedScalarQuantizer`. It supplies the true-HNSW reference for the graph
question, the only shipped predicate-aware searcher, the full codec ladder and the cooperative cancellation
check — and **no deletion**: the graph is insert-only during a segment's write, frozen after
`getCompletedGraph()`, and the strings `remove`, `delete`, `markDeleted` do not occur in the builder or the
graph; a changed vector is a delete plus an insert into another segment, and `IncrementalHnswGraphMerger`
initializes from the largest existing graph only if it has at most 40 % deleted documents
(`DELETE_PCT_THRESHOLD`). It is a released `10.x` line with a stable format — the maturity row reads the
opposite way to jVector's. What it costs is the maintenance model it forces on a transactional store without
segments: a liveness bitmap intersected into the acceptance bits plus a rebuild whose cadence the spike has
to measure ([§5](#5-open-questions), OP6-14).

**Path B — the winner integrated into evitaDB's storage.** Vector files as immutable append-only artifacts
beside the catalog, read through mmap, cleaned up through `removeFileWhenNotUsed` ([§3.3](#33-the-file-layout)). It answers
the integration risks and is an order of magnitude more expensive; it does not start before A and L show
the numbers.

**Path C — an in-house implementation**, sized from three sources: Lucene's `util.hnsw` is 5,263 lines in
28 files, its quantization 1,720 and its distance kernels plus vectorization ~2,000; jVector's base is
~26,100 lines in 138 files and its whole Panama layer one 1,549-line class; hnsw-sb shows the floor at
~3,300 lines for a
bare HNSW with deletes and SIMD cosine and none of the seam's properties. An in-house implementation with
them all — graph, one predicate-aware searcher, exact scan, two codecs, an on-disk format, deletes with
repair — is realistically **8 to 10 thousand lines of production code** plus, at Lucene's test ratio of
~0.7×, another 5 to 7 thousand of tests: **13 to 17 thousand lines**, before the empirical tuning of recall
and latency that the libraries have years of behind them.

**hnsw-sb is not a path.** Rejected because it has none of the properties the seam needs (no filtered walk,
no quantization, string ids, no module descriptor), every mutation rebuilds the whole on-heap read snapshot
under a lock that excludes all readers (a single `add` on 1M × 768 copies 3 GB), its text persistence caps
out near 130,000 vectors, and it carries **no licence**, so nothing may be copied from it. Its one use is the
size data point above.

**Recommendation: the spike runs A and L over one harness, continues into B with whichever the gate picks,
and opens C only if both fail.** A and L are complementary evidence rather than competitors for one slot: A
supplies the graph-family comparison and incremental maintenance; L supplies the predicate-aware walk, the
codec ladder and the cancellation pattern the planner needs *regardless of which graph wins*. Path C is
justifiable only by a failure of both on the criteria or a hard block on integration — and even then the
first question is whether the seam can be filled from the libraries' *pieces* rather than written from
nothing.

### 3.3 The file layout

Three artifacts, following the `fileIndex` convention and bound to a bootstrap record ([§2.3](#23-storage-append-only-files-fileindex-naming-the-first-mmap)):

- **raw vectors** — a dense `float32` block, immutable, read through mmap, never on the heap. Its consumers
  are rescoring and, on JDK 21, the mapped `RandomAccessVectorValues` that feeds a build. Rescoring with
  `topK = 10` at an oversampling factor of 3 reaches for roughly thirty full-precision vectors per query
  inside the 10 ms budget, so the requirement is **random access by individual ordinal, without
  deserialization and without a copy onto the heap** — the shape `readRaw`/`readRawInto` or an mmap over a
  dense block give. The block must also survive **any change of quantization**: in a model of a single live
  structure a codec change is a rebuild of the vector index (Elasticsearch can afford otherwise only thanks
  to per-segment codecs), and this block is the only source for that rebuild;
- **the quantized codes and the graph** — of the order of 200–350 MB per 1M vectors for a 1-bit code, a
  candidate for the heap or for direct buffers; for an 8-bit code the codes alone are 768 MB per 1M, and
  whether they are mapped too is the fork of [§2.8](#28-the-memory-accounting-rule) (OP6-18);
- **the ordinal mapping** — bidirectional, per [§2.4](#24-ordinals-are-not-primary-keys).

**Ownership of the format** is decided in path B ([§5](#5-open-questions), OP6-5). jVector's `OnDiskGraphIndex` can write and read
its own structure (format version 6) and that is the fast path for A; evitaDB owning the files with the
library given only a read interface is the path consistent with the storage, necessary if the files are to
pass through compaction and time travel like everything else. The design tilts to evitaDB: the ordinal
mapping is evitaDB's in any case ([§2.4](#24-ordinals-are-not-primary-keys)), and a format whose feature flags are an ordinal bitmask
(`FeatureId`) is one whose evolution evitaDB would not control.

**The lifecycle.** Writing a new version is an append of a new file; the old version stays as long as
somebody is looking at it. Cleanup is registered through `ObsoleteFileMaintainer#removeFileWhenNotUsed` and
the mapping is made a participant in the retention floor through the pin protocol of [§2.3](#23-storage-append-only-files-fileindex-naming-the-first-mmap) — pin on mapping,
release the mapping (`Arena.close()` on JDK 22, whatever unmaps `MappedChunkReader`'s buffers on JDK 21),
then `catalogVersionReleased(v)`.

### 3.4 Filtered ANN and the planner

**Four strategies, not one threshold.** Research [§5.4](../research.md#54-filtered-ann) speaks of a single selectivity threshold of "~1–2 %"
below which a brute-force scan takes over. Lucene shows three different mechanisms with very different
thresholds, and they are the reference points the measured curve is sanity-checked against:

- **an immediate exact search at query level** — `AbstractKnnVectorQuery` goes to `exactSearch` when the
  filter's cost is at most the expected per-leaf top-K (expected hits plus three standard deviations);
- **an exhaustive scan at reader level** — `Lucene99HnswVectorsReader` skips the graph when
  `expectedVisitedNodes(k, graphSize) = ln(graphSize) · k` is not below the filtered count; at 1M and
  `k = 10` that is `ln(10⁶) × 10 ≈ 138`, so **Lucene goes to brute force only below ~138 of a million,
  roughly 0.014 %**;
- **a specialized filtered searcher** — `KnnSearchStrategy.Hnsw` reaches for `FilteredHnswGraphSearcher`
  when **fewer than 60 %** of the graph pass (`DEFAULT_FILTERED_SEARCH_THRESHOLD`). It is Lucene's ACORN-1:
  the ordinary walk while a neighbourhood's accepted share is above `EXPANDED_EXPLORATION_LAMBDA = 0.10`,
  otherwise exploration of the neighbours of rejected neighbours by a multiplier bounded by
  `min(1/filterRatio, maxConn/2)` — widening proportional to the filter's sparsity, free when the filter is
  dense. Shipped opt-in in Lucene 10.2 ("as much as 5x" on filtered searches), default in `main`.

The "1–2 %" lies between mechanisms doing different things. jVector has none of the three ([§2.7](#27-the-searcher-and-the-planner-are-evitadb-owned)), so the
switch is owned by evitaDB. Elasticsearch confirms it is **a planner's decision, not a fixed strategy**:
pre-filtering in the walk (ACORN by default) against post-filtering (`PostFilterKnnQuery`), chosen by a
configurable `postFilterSelectivityThreshold` over an *estimated* selectivity; its reason for ACORN over
indexes that need the filter dimensions declared at build time — predicate-agnosticism preserves schema and
query flexibility *(external)* — maps exactly onto evitaDB, where the candidate set is the output of
arbitrary filter algebra. Our decision is exact, not estimated ([§2.5](#25-the-candidate-set-contract)).

The four strategies the spike measures, over the same graph and the same codec:

1. **Exact scan** over the candidate set: iterate the bitmap, score each member against the raw (or a code)
   block, keep the top-K′. Exact by construction, cost linear in the cardinality, also the ground-truth
   generator ([§4.3](#43-recall10-and-the-ground-truth)) and the reranker. The external note's strongest claim is that this is a **required
   execution path, not a testing utility**; the FANN study it cites *(external)* reports that at ~0.1 %
   qualifying vectors *neither* ACORN nor plain HNSW reached the target recall while subset scanning stayed
   robust. If our measurement shows the same band, the planner's lowest regime is the exact scan and
   nothing else.
2. **Unfiltered walk with post-filter**: search with `Bits.ALL`, oversample, filter the result, rerank.
   Cheapest when the filter is dense; the oversampling factor grows as `1/selectivity` and the strategy
   collapses below some measured point.
3. **Walk with in-walk acceptance**: the `Bits` test of [§2.7](#27-the-searcher-and-the-planner-are-evitadb-owned) — rejected nodes are still expanded, only not
   returned. What jVector offers and what Lucene did before 10.2. Safe when the accepted share is moderate;
   it degenerates toward a full traversal as it falls, and its recall may drop because the graph pruned to
   accepted nodes stops being well connected.
4. **Predicate-aware walk** (ACORN-1): as 3, plus second-hop widening when a neighbourhood's accepted share
   is low. Lucene's `FilteredHnswGraphSearcher` for path L; for path A written over jVector's `View` — the
   seam test of step 5. The FANN study *(external)* reports ACORN and plain HNSW competitive above ~10 %
   qualifying and plain HNSW best above ~50 %, consistent with Lucene's 60 % switch: the widening must not
   run unconditionally.

**The default strategy is post-filtering.** Per the research's Z7 profile the must-match filter leaves
85–95 % of the corpus (it cuts only 5–15 %), so ordinary traffic falls into a band where the graph is
unambiguously right and an in-walk test only makes the walk more expensive. The in-walk and predicate-aware
strategies and the exact-scan insurance serve the tail — a deep category plus a narrow price band. The tail
cannot be omitted: without the switch, jVector degrades there into a walk of the whole graph with no way to
stop it.

**One structure is read about but not built:** the Curator family (SIGMOD 2026, *external*) pairs the
graph with a hierarchical partition structure for low-selectivity filters, because graph connectivity breaks
down when qualifying records are very sparse. If the exact scan is too slow in the band where the graph
fails — which at 1M it should not be, at 10M it might — that is the shape of the sixth strategy, and [§3.1](#31-the-shape-a-retrieval-subsystem-with-a-planner)
must not preclude a partition sidecar on the vector store.

### 3.5 Codecs and oversampling

The codec is a separable slot ([§3.1](#31-the-shape-a-retrieval-subsystem-with-a-planner)), the libraries fill it differently, and the memory criterion is
decided by the codec far more than by the graph. The naming has to be precise: "RaBitQ/BBQ-class
quantization" from research [§5.2](../research.md#52-quantization-and-memory) does not exist in jVector. "BBQ" is Elasticsearch's name for Lucene's
`OptimizedScalarQuantizer`, which cites RaBitQ and in the current `main` serves **every bit width** through
one format (`Lucene104ScalarQuantizedVectorsFormat`): `UNSIGNED_BYTE` (8 bits), `SEVEN_BIT`,
`PACKED_NIBBLE` (4 bits), `DIBIT_QUERY_NIBBLE` (2-bit documents, 4-bit queries) and
`SINGLE_BIT_QUERY_NIBBLE` (1-bit documents, 4-bit queries); the older int8 `ScalarQuantizer` also exists.
The asymmetry — a 1-bit document code scored against a 4-bit query code with per-vector correction terms —
is what distinguishes a RaBitQ-style code from jVector's symmetric Hamming BQ.

jVector has **PQ** (`ProductQuantization`, 256 clusters per subspace, optional anisotropic weighting, a build
over PQ codes via `BuildScoreProvider.pqBuildScoreProvider`), **BQ** (`BinaryQuantization`, 96 B per 768-d
vector, Hamming, no correction terms; its own documentation says it "loses too much accuracy with smaller
embeddings" and is "primarily suitable for ada002"), **NVQ** (`NVQuantization`, per-vector fitted non-linear
at 8 or 4 bits, positioned as the *second-pass* representation) and **Fused PQ** (neighbours' PQ codes inline
in the adjacency record; exactly 256 clusters, format version ≥ 6, PQ only). It has **no scalar (int8)
quantization and nothing of the RaBitQ family**. Research [§5.2](../research.md#52-quantization-and-memory)'s ~32× ratio is BQ's, and the recall
criterion is the one most threatened by that substitution — hence a ladder, not one measurement. What
jVector alone can do is build the graph over a compressed code (the larger-than-memory build), out of scope
at 1M but the property that matters at 10M.

**The ladder.** Every code is measured with the same graph, the same query set and the same exact
reranker, so the only variable is the code; the Lucene codes drive jVector's traversal through the
`ScoreFunction` seam ([§2.6](#26-the-seam-every-library-contact-is-reversible)), which exercises the codec slot for the first time and answers the pluggability
row of the gate:

| Code | Source | Bytes / 768-d vector (documents) | Scoring | Note |
|---|---|---|---|---|
| float32 | both | 3,072 | exact | the baseline and the reranker |
| 8-bit | Lucene `OptimizedScalarQuantizer`, `UNSIGNED_BYTE` / `SEVEN_BIT` | 768 + corrections | int8 dot | the "SQ8 baseline"; jVector has none |
| NVQ 8-bit | jVector `NVQuantization` | 768 + per-subvector params | non-uniform | designed as a second-pass code; measured as first-pass for comparison only |
| 4-bit | Lucene `PACKED_NIBBLE`; jVector NVQ `FOUR` | 384 | nibble dot | |
| PQ | jVector `ProductQuantization`, 256 clusters | `M` (e.g. 96 or 192) + 786 KB codebooks shared | table lookup | the only PQ on the table; anisotropic option on and off |
| 2-bit | Lucene `DIBIT_QUERY_NIBBLE` | 192 + corrections | asymmetric | |
| 1-bit, asymmetric | Lucene `SINGLE_BIT_QUERY_NIBBLE` | 96 + corrections (~14 B) | 1-bit doc × 4-bit query | the RaBitQ-derived code; Elasticsearch's default from 384 dimensions |
| 1-bit, symmetric | jVector `BinaryQuantization` | 96 | Hamming | expected by its authors to fall short at this dimensionality |

The two 1-bit rows are the same size and **not** the same thing; measuring both answers whether the newer
scheme's recall advantage is real on our embeddings.

**Oversampling and reranking.** For every code the walk retrieves `oversample × topK` candidates by
approximate score and the exact reranker over the raw block orders them; the factors are **1×, 2×, 3×, 4×
and 8×**. Elasticsearch switches binary quantization on by default from 384 dimensions (absolute lower bound
64) and adds **oversampling by 3.0 with full-precision rescoring by default** — introduced subsequently by
a separate index-version flag because ordering from the quantized space alone proved insufficient — so 3.0
is one point of the axis, not its only value. Two recalls are recorded per cell, **before** reranking (the
code's own ordering) and **after** (what the user sees); the latency criterion applies to the whole
pipeline including the reranking's random reads into the mapped raw block, which is also where the mmap
path is exercised under realistic access. The cell family answers: the smallest code whose final recall
reaches 0.95 at an oversampling factor that keeps latency under 10 ms — and whether it is the 1-bit code the
budget assumes.

### 3.6 The text × vector hybrid

RRF fusion is out of P6's scope, but the spike must not close its doors ([§2.9](#29-hybrid-readiness)). Two conclusions are fixed:

- **RRF is the only correct fusion, not a preference.** In Elasticsearch the whole fusion is one line,
  `score += weight · 1 / (rankConstant + rank)` per leg — the legs' scores do not enter at all. Our text leg
  returns a 64-bit lexicographic composite, a packed tuple with no scale that cannot be compared with a
  cosine similarity even after normalization; the calibration min-max normalization would need is not
  expensive but **undefined**. That is a reason for the record, not a leaning.
- **Fusion is a named phase between evaluation and paging, not another lane of the composite.** A lane
  breaks a tie within one ranking; fusion mixes two independent rankings and needs to see both lists whole —
  OpenSearch hangs it on `SearchPhaseResultsProcessor`, after evaluation and before fetch, for the same
  reason. Where that phase lives in evitaDB's query evaluation is [`query-design.md`](query-design.md)
  ([§9.5](query-design.md#95-the-phases-of-query-execution), [§11](query-design.md#11-the-vector-leg-and-rrf-sketch)); P6 holds one commitment from it — the vector leg emits a usable ranking, not only a top-K.

### 3.7 The realization procedure

Steps 0 and 1 are entry conditions and verifications; nothing else starts until they pass. Each step names
its output so the spike can stop after any step with something recorded.

**Step 0 — the module wiring.** Confirm or supply the `--add-modules` wiring of [§2.1](#21-jdk-21-and-the-module-wiring) in the shape #1541
specifies, with `requires static` in the vector module's descriptor. Output: `jdk.incubator.vector` is
readable in compilation, tests and runtime. If a JDK 22 toolchain is at hand, the harness module alone is
also built on it for the optional off-heap comparison; the product baseline is not raised for that.

**Step 1 — day zero, four confirmations.** (a) Which `VectorizationProvider` a JDK 21 JVM selects with and
without `--add-modules`, read off the log — expected `Panama` and `Default`; the negative arm pins down the
exact warning text the harness greps for ([§4.5](#45-latency)); then force `panama` and keep it in every invocation.
(b) Which read backend `ReaderSupplierFactory` selects — expected `MappedChunkReader` after a warning about
`MemorySegmentReader`. (c) JPMS: `requires jvector` from a named module compiles and the
`META-INF/versions/20` layer resolves under it; the same for `org.apache.lucene.core`.
(d) `GraphIndexBuilderConfig` is pinned or disabled and its effective values are logged.

**Step 2 — the vectors' ingress.** Introduce the `byte[]`-with-float32-little-endian convention ([§2.2](#22-how-a-vector-gets-into-evitadb)) and
verify the round-trip write and read. Without it further work has no input.

**Step 3 — the harness, the ground truth and the exact scan.** Build the measurement circuit of [§4](#4-testing-approach): dataset
loading, the brute-force ground truth over all vectors *and* over candidate bitmaps, recall@10, latency. The
exact scan is written once, SIMD-vectorized over the raw block and over each code block, because it is three
things at once: the correctness oracle, the lowest planner regime and the reranker. Output: a harness that
reports every metric of [§4.8](#48-the-record-of-every-configuration) for a trivially correct strategy, so every later number has a reference. The
harness is finished **before** the index.

**Step 4 — the float32 graph baselines.** Build four jVector graphs — `alpha = 1.0` and `≈ 1.2`, each with
and without `addHierarchy` — and one Lucene `HnswGraphBuilder` graph as the true-HNSW reference, all over
float32; obtain recall/latency curves by varying `M`, `beamWidth`/`efConstruction` and the search beam;
record build time and `ramBytesUsed` for each. Output: the first hard recall and latency numbers, and the
answer to the graph question at equal memory and recall — which graph goes forward, so the matrix is not
run five times over.

**Step 5 — ordinal mapping, the seam test and the filtered matrix.** Add the bidirectional mapping ([§2.4](#24-ordinals-are-not-primary-keys))
and the candidate-set adapter ([§2.5](#25-the-candidate-set-contract)). Write the searcher of [§2.7](#27-the-searcher-and-the-planner-are-evitadb-owned) over jVector's `View` — in-walk acceptance,
second-hop widening, a cancellation flag, a visited budget — and run the matrix of [§4.4](#44-the-filtered-matrix) on the graph chosen
in step 4, with Lucene's `FilteredHnswGraphSearcher` over the Lucene graph as the reference row for strategy
4. Output: the measured planner thresholds, the recall-under-filter verdict, and the seam test — whether a
caller-written searcher over the library's graph is a fork-free reality (OP6-13).

**Step 6 — codecs.** Run the ladder of [§3.5](#35-codecs-and-oversampling) with the oversampling axis on the same graph: the Lucene codes
through the `ScoreFunction` seam, the jVector codes natively. Output: recall before and after reranking per
code per factor, the heap accounting of [§2.8](#28-the-memory-accounting-rule) per code, and which code the memory criterion can afford at the
recall criterion — including, if it is not the 1-bit one, the fork of OP6-18.

**Step 7 — incremental maintenance.** The churn protocol of [§4.7](#47-the-churn-protocol) on both graphs. Output: the rebuild cadence
each candidate needs, and whether the graph degrades enough to make that cadence a production concern.

**Step 8 — path B, integration into the storage.** Only here, and only if steps 4–7 passed: vector files
beside the catalog, mmap reading, the mapped `RandomAccessVectorValues` for the build, registration into
`ObsoleteFileMaintainer` with the pin protocol, a decision about ownership of the format ([§3.3](#33-the-file-layout)). Output: the
integration half of the mini-gate.

**Step 9 — the decision and the write-up.** Fill in the table of [§1.3](#13-the-mini-gates-decision-criteria), decide both branches of the fork
(the library and the version), carry the planner's measured thresholds and the codec verdict into the
record ([`../README.md`](../README.md)), and delete the working folder per the ADR rules.

---

## 4. Testing approach

### 4.1 Where the harness lives

The harness is a disposable measuring rig **outside the production modules**. For paths A and L a
standalone module that does not depend on evitaDB suffices; only path B connects it to the storage. It is
**engine-neutral by construction**: it talks to the five contracts of [§3.1](#31-the-shape-a-retrieval-subsystem-with-a-planner) and nothing else, and jVector,
Lucene and the exact scan are three implementations of them. jVector's own `BenchYAML` (recall, MAP, visited
and expanded counts, mean and p999 latency, QPS, heap and off-heap peaks) is worth reading for its metric
definitions but cannot host the other two implementations and is not reused.

### 4.2 Datasets

A three-stage set:

1. **`siftsmall` (10k × 128)**, checked into the jVector repository, as the smoke test: its ground truth is
   part of the dataset, so it verifies the harness computes recall correctly against a reference.
2. **A public dataset at the target scale** — 1M vectors of 768 dimensions, otherwise the criterion of [§1.2](#12-criteria)
   is meaningless. jVector's public S3 catalog (fvecs/ivecs only; the `jhdf` dependency is declared and
   unused) names the families `cohere-english-v3-1M` and `-10M` (1,024 dims), `dpr-gemma-1M` and `-10M`,
   `cap-1M` and `-10M`, `colbert-1M`, `ada_002_100k` (1,536 dims) and the small classics (glove, nytimes,
   sift); a `c4-en_base_1M_norm.fvecs` with a DPR ground truth appears in the loader's own example, and DPR
   embeddings are 768-dimensional. **The dimension is read from the fvecs header, never assumed from the
   name**, and the catalog's availability and licence terms are checked on day zero (OP6-8). If nothing fits,
   the substitute is a synthetic set with a controlled cluster structure — purely random vectors are
   **misleading**, because in a uniformly distributed high-dimensional space all points are similarly
   distant and the graph looks better than it would on real data.
3. **Real embeddings from a production e-commerce catalog**, exported by Sage
   (`$OSS_PROJECT_PATH/Sage`).
   This is an assumption, not a verified fact (OP6-9). If available it is the most valuable of the three,
   because only it captures the real distribution — and only on it are the *correlated* predicates of [§4.4](#44-the-filtered-matrix)
   real facets rather than nearest-neighbour balls.

### 4.3 Recall@10 and the ground truth

The ground truth is computed **brute-force** — exact distance from the query to all vectors, top-10 — once
and stored on disk (for 1M × 768 it is of the order of a billion floating-point operations per query). The
query set is fixed at **1,000 queries** so numbers are comparable across runs and configurations. For the
filtered matrix the ground truth is per (query, predicate) pair — the exact scan over the candidate set —
and is likewise stored.

Recall@10 is the plain ratio of the ten true nearest neighbours that appeared in the returned ten, averaged
over the queries, measured **after rescoring** (what the user sees, and what is to make up for the
quantization loss) with the before-rescoring value recorded beside it.

### 4.4 The filtered matrix

Fixed in advance so it cannot be trimmed to whatever looks good:

- **selectivities**: 100 %, 50 %, 10 %, 5 %, 1 %, 0.1 %, 0.01 % of the corpus qualifying; Lucene's 60 % and
  0.014 % are the reference points the result is compared against for common sense;
- **strategies**: the four of [§3.4](#34-filtered-ann-and-the-planner), over the same graph and the same codec, with the "in-walk acceptance
  at 50 %" cell run against the Roaring bitmap and against a materialized flat bitset ([§2.5](#25-the-candidate-set-contract));
- **predicate shape**: **random** subsets *and* **semantically correlated** subsets — a real filter (a
  category, a brand, a price band) selects vectors that cluster in embedding space, and a graph pruned to a
  cluster behaves differently from one pruned at random. The correlated generator picks a seed vector and
  takes its nearest `s·N` neighbours from the ground truth, or, on the real catalog, an actual facet; both
  shapes at every selectivity, and the generator's shape is stated with every filtered number (OP6-16);
- **outputs per cell**: recall@10 after rerank, p50 and p95 latency, distance computations per query, nodes
  visited per query (`SearchResult` counters for A, `KnnCollector` for L), and the oversampling factor that
  reached the recall.

The curves' intersections are the planner's thresholds. The second thing the matrix shows is **the
degradation of recall under a filter**: if recall@10 falls below 0.95 in some band for *every* graph
strategy, that is a blocking finding regardless of latency and decides whether the exact scan is the
planner's lowest regime by necessity. The ranking depth of [§2.9](#29-hybrid-readiness) (`rerankK`, `resume`) is measured here too.

### 4.5 Latency

**Recall is measured with a separate harness, latency with JMH — never mixed.** Recall is a deterministic
property of a configuration over a fixed query set, measured once; JMH's warm-up and steady state add
nothing to a ratio. Latency makes no sense without JIT warm-up.

JMH in this repository has documented silent traps — modes in which a benchmark does nothing and prints
numbers. Settings: `SampleTime` or `AverageTime` with explicit warm-up iterations, the result always
passed into a `Blackhole`, the run in the background with completion detected by the content of the output
file rather than by the process running, and the configuration archived with every run.

**An entry condition of every latency measurement, without exception:** JMH forks its own JVM and does not
inherit surefire's `<argLine>` ([§2.1](#21-jdk-21-and-the-module-wiring), item 5). Forcing `-Djvector.vectorization_provider=panama` in the JMH
`jvmArgs` is mandatory and turns a silent scalar run into a loud failure; the second line of defence is the
log of the **forked** JVM, and until it confirms `PanamaVectorizationProvider` no measured latency is valid
or compared against "< 10 ms".

### 4.6 Memory

Measured with **JOL** by the same procedure [`p1-index-core.md`](p1-index-core.md) prescribes for the text
branch, reported per component per [§2.8](#28-the-memory-accounting-rule). Two things are verified separately: that the quantized codes and
the graph sit within the [§1.2](#12-criteria) budget, and — architecturally more important — that **the raw vectors do not
appear on the heap**. The second is proved by measuring the heap's size with rescoring switched on over 1M
vectors: pulled-in raw data would show immediately and unmistakably. On JDK 21 that measurement is also the
proof that the mapped `RandomAccessVectorValues` of [§2.3](#23-storage-append-only-files-fileindex-naming-the-first-mmap) does its job during build as well as search.

### 4.7 The churn protocol

Verify `addGraphNode` and `markNodeDeleted` at runtime: insert 10 %, delete 10 %, replace 10 %, measuring
recall and latency after each and after `cleanup()`; for the Lucene graph the same churn with a liveness
bitmap and a full rebuild, measuring the rebuild's cost and the recall drift before it. Verified alongside:
that `cleanup()` really needs a quiescent window with respect to writers, what a searcher pool holding
`View`s does to deleted-node reclamation, and what the per-thread scratch the builder retains costs under
evitaDB's thread model ([§3.2](#32-the-graph-component-candidates-and-recommendation)).

### 4.8 The record of every configuration

Every measured cell carries, or the number is not kept: the dataset and its dimension; the graph (library,
`alpha`, hierarchy, degrees, beam, `neighborOverflow`, the effective `GraphIndexBuilderConfig`); the code and
its parameters; the strategy, the selectivity and the predicate shape; the oversampling factor; recall
before and after reranking; p50, p95 and p99 latency; distance computations and nodes visited per query;
heap bytes per component and per vector; build time; and, for step 7, the churn protocol and the rebuild
time. The JVM's provider and read-backend log lines are part of the record.

---

## 5. Open questions

- **OP6-4 — the ingress of embeddings into production.** The spike uses a `byte[]`; production is
  three-way — `float[]` in the type system (A), a custom `StoragePart` (C), or a dedicated embedding field
  type (D), which substantially changes the effort estimate against A ([§2.2](#22-how-a-vector-gets-into-evitadb)). A technical reopening of O5;
  the schema declaration belongs in [`schema-design.md`](schema-design.md).
- **OP6-5 — ownership of the vector files.** jVector's own format, or evitaDB's files with a read interface
  for the library ([§3.3](#33-the-file-layout))? It decides whether the files pass through compaction and time travel; the design
  tilts to evitaDB.
- **OP6-6 — the jVector version.** 3.0.x stable without the hierarchy, NVQ and Fused PQ, or 4.0.0-rc.x with
  them and the risk of a long RC ([§3.2](#32-the-graph-component-candidates-and-recommendation))? A separate decision beside the mini-gate.
- **OP6-7 — the codec at the recall criterion.** Which rung of the ladder reaches recall@10 ≥ 0.95 after
  reranking at an oversampling factor that holds the latency criterion, and does the asymmetric 1-bit code
  beat symmetric BQ on our embeddings? A 4- or 8-bit answer renegotiates the memory criterion or maps the
  codes (OP6-18).
- **OP6-8 — a dataset at the target scale.** Which 1M family in jVector's public catalog is 768-dimensional,
  under what terms, and how large is the download ([§4.2](#42-datasets))?
- **OP6-9 — real embeddings.** Can Sage today produce and export embeddings for
  the production e-commerce catalogs? Carried as an assumption.
- **OP6-10 — graph degradation under incremental maintenance.** Does the graph need a periodic rebuild after
  a series of deletions, and how does that rebuild meet the storage's compaction? For jVector: the quiescent
  window `cleanup()` needs and how long-lived `View`s interact with reclamation.
- **OP6-11 — concurrency.** All criteria are single-threaded; concurrent load and the interaction of mmap
  paging with evitaDB's other I/O must be measured before production. jVector's one `GraphSearcher` and one
  `View` per thread, and the scratch each retains, are the starting constraint.
- **OP6-12 — the unit of the vector index: an entity, or a chunk?** One vector per entity is a tacit
  assumption that does not suffice for long texts — Elasticsearch's `semantic_text` creates a nested object
  per chunk itself (embedding, text, character offsets). A chunk unit needs a chunk → PK mapping and **a
  declared aggregation function across an entity's chunks** (typically the maximum) — the same shape of
  problem as aggregation across references (research O10,
  [`p7-rank-profiles-and-boost-channel.md`](p7-rank-profiles-and-boost-channel.md) [§4.5](p7-rank-profiles-and-boost-channel.md#45-the-seam-for-matching-across-a-reference-o10)), so the two ought
  to share one mechanism; an entity unit has to say how a long article fits one embedding. An entry
  condition for the CMS profile (Z8), marginal for the e-shop one; it shapes [§2.4](#24-ordinals-are-not-primary-keys) and [§3.3](#33-the-file-layout).
- **OP6-13 — the seam test.** Can a caller-written searcher over jVector's `View` deliver in-walk
  acceptance, second-hop widening, a cancellation check and a visited budget at the same per-hop cost as the
  library's own searcher? Step 5 answers it; a negative answer moves the filtered strategies to path L or to
  a fork.
- **OP6-14 — the maintenance model without deletes.** If Lucene's graph wins on filtering and codecs, what
  rebuild cadence does a liveness bitmap force at the churn rates of the production write path (P2's WAL
  replay is the source of the rate), and is a periodic rebuild acceptable in a transactional store that
  compacts on its own schedule?
- **OP6-15 — global configuration.** Is pinning the process-wide `GraphIndexBuilderConfig` acceptable for a
  database hosting several catalogs with different vector settings, or does that alone force the deprecated
  constructors or a fork?
- **OP6-16 — the correlated predicate generator.** Nearest-neighbour balls from the ground truth are a
  stand-in for real facets; only OP6-9's data makes them real.
- **OP6-17 — the pathological band.** If a selectivity band exists in which no graph strategy reaches the
  recall criterion *and* the exact scan exceeds the latency criterion, a partition-assisted structure ([§3.4](#34-filtered-ann-and-the-planner))
  becomes the sixth strategy. P6 records whether the band exists at 1M and estimates whether it would at 10M.
- **OP6-18 — heap-resident or mapped codes.** If the affordable code is larger than 1-bit, do the codes join
  the raw block in a mapped file with the page cache as the budget, or stay on the heap with the budget
  renegotiated? A storage-layout decision with a measurable cost on the walk's random reads; it belongs to
  path B.
- **One provider or two.** Whether the vector distance kernels share the SIMD record's provider or get their
  own behind the same module and C2 detection ([§2.1](#21-jdk-21-and-the-module-wiring)) — decided at design time, under the driver-17 side
  condition either way.
