# P6 — the vector spike: prototype implementation plan

> **Status: an implementation plan, not a decision.** It follows on from the research
> [`../research.md`](../research.md) (v2, consolidated 2026-08-04, last revised 2026-08-12), namely the
> whole of §5, §7 (P6's placement and its mini-gate), O5 and O7. The argument "why an in-house engine and
> not Lucene" is not repeated here — it is in the research (§3).
>
> Written on 2026-08-12. The anchors into evitaDB's code and into the Lucene checkout in
> `/www/oss/lucene` were verified against the state on the same day. The facts about jVector come from the
> web (the repository `datastax/jvector`, Maven Central) — they are marked **web-only** in the text and it
> is stated with them what exactly the spike has to verify empirically, because they could not be read
> locally. Translated from Czech and moved into this record on 2026-08-24.

---

## 1. Goal, scope and criteria

P6 is a **spike**, not a feature prototype. Its task is not to deliver vector search but to decide the
mini-gate of §7 of the research — i.e. to answer the question of whether to build the vector leg over the
adopted jVector library or over an in-house HNSW implementation — and at the same time to verify that the
architectural assumption of §5.3 (immutable append-only files read through mmap, off the heap) really
holds in evitaDB. Both are empirical questions; neither can be answered by reasoning, because both rest on
numbers nobody has measured.

The criteria from §5.2 and §7 of the research stay unchanged:

- **recall@10 ≥ 0.95** against a brute-force ground truth, measured *with rescoring* against the raw
  vectors, not over the quantized codes alone;
- **latency < 10 ms** for 1M vectors (768 dimensions), one thread, without concurrent load.

To them P6 adds three criteria the research did not name but without which the result is unusable for a
decision:

- **a memory cap**: the quantized codes plus the graph have to fit into the order §5.2 estimates
  (~90–100 MB of codes and ~100–250 MB of graph per 1M × 768), and the raw vectors must never appear on
  the heap — verified by measurement, not by construction;
- **behaviour under a filter**: latency and recall are measured for filtered ANN across selectivities too,
  because that is precisely where the choice between a graph walk and a brute-force scan breaks (§8);
- **incremental maintenance**: adding and deleting a vector at runtime, without rebuilding the whole index
  — that is a property evitaDB needs because of its write path and which Lucene by construction does not
  have (§4.3, §5.5).

### 1.1 The mini-gate's criteria

The mini-gate does not decide by a single number. jVector may win on performance and still lose on
integration; an in-house implementation may be integrationally clean and yet not reach the recall in a
reasonable time. The decision criteria are therefore set in advance, so that the result cannot be
rationalized retrospectively — their weights and thresholds are in §6.2.

### 1.2 What P6 deliberately does not do

P6 **does not integrate vectors into the query language**. No new constraint, no change to
`orderBy(relevance())`, no RRF fusion in production code — RRF will appear in P6 at most as an offline
computation over two rank lists, to verify that fusion makes sense (§8.3). The DSL's shape is addressed by
O4 and depends on the results of the text gate (P5 → P1 → P2), which runs first.

P6 **does not address query-side embedding** (O7). The harness produces the query vectors itself: either
they are part of the dataset, or they are generated the same way as the document ones. The question of who
embeds a user's query in production is a product one and a spike will not advance it.

P6 **does not build a production write path**. It verifies incremental adding and deleting as a property
of the library, but does not connect it to the transactional layer nor to the WAL. That is work belonging
after the mini-gate's decision.

---

## 2. Links to the research and to the neighbouring prototypes

P6 stands apart from the text branch. The research's decision gate (P5 → P1 → P2) measures the text core
and P6 does not enter it; conversely P6's result has no influence on how the text gate turns out. That is
deliberate and it is the reason P6 can run in parallel with P1 and P2 as soon as its entry conditions are
met (§3).

The only two points where the branches meet are the delivery phase F2 (the text × vector hybrid, §5.5 of
the research) and the rank profile of §4.3, into which vector similarity will one day enter as another
lane or as the result of an RRF fusion. Neither is the subject of P6 — P6 merely must not choose a
solution that would make them impossible, and therefore §8.3 names what the spike has to verify about
fusion so as not to block it.

Towards O5 and O7 the position is this: **O5 (the origin of the document embeddings) is marked resolved in
the research** — Sage supplies them as entity data. The finding of §5.4 of this document shows, however,
that it is resolved only politically, not technically: the path §5.6 of the research describes (an
attribute or associated data of type `float[]`) **does not exist** in evitaDB. O5 thereby reopens in a
different form and P6 has to answer it, because without a path for a vector to get in there is nothing to
index. **O7 (embedding the query) stays out of scope**, as stated above.

### 2.1 The starting state at the customer: the vector leg already runs today

The analysis of the existing solution (internal, §3.6) changes what P6
measures itself against, and it is worth knowing right at the start: **the vector branch is not a novelty
at the customer but a deployed and functioning state.** The existing fulltext client over plain Lucene has
kNN search over a `vector` field, embedding of documents and queries via OpenAI or HuggingFace, optional
L2 normalization, a minimum text length below which nothing is embedded, and a hybrid composition of the
lexical and semantic legs. The composition there is, however, a plain **sum of Lucene scores with manually
set weights** (`contentBoost` against `semanticBoost`), i.e. exactly the calibration RRF avoids (§8.3).
Qualitatively our design is better, but in delivery terms the new solution is **a step back until phase
F2** — and that is a fact belonging in the decision about phase order, not in a footnote: deploying at a
customer who uses semantic search today needs either a finished F2, or both solutions running in parallel.

One concrete thing can be adopted literally from it, even though it lies outside P6's scope. The query's
embedding is computed in the existing solution **inside the search layer**, not in the client application,
and preceding it is a **two-level cache of query vectors — in memory and in the database — with a
versioning key** that changes with the embedding's configuration and thereby invalidates the cache.
Without it, every keystroke during suggestion would pay for a call to an external model. That is a
finished pattern for O7, i.e. for the question "who embeds the user's query", which §1.2 deliberately
excludes from P6 and which is the only established market exception to the rule "model inference does not
belong in the engine" (research §1.2, test 3). P6 does not decide it; it merely records that when the
decision comes, a proven shape of solution including invalidation exists.

---

## 3. The entry condition: JDK 21 is not in `pom.xml`

The research §5.1 states the JDK 21 entry condition is met and refers to Z1. That is true of the
**performed and successful trial upgrade**, not of the state of the `dev` branch. Verified directly:

| Where | What is there |
|---|---|
| `pom.xml:124` | `<java.version>17</java.version>` |
| `pom.xml:654-657` | `<release>`, `<source>`, `<target>`, `<compilerVersion>` = `${java.version}` |
| `pom.xml:658-660` | `<compilerArgs>` contains a single argument, `-parameters` |
| `pom.xml:698` | surefire `<argLine>`: `-Xmx8g`, locale, `--add-opens`; no `--add-modules` |
| `docker/Dockerfile:29` | `ENV EVITA_JAVA_OPTS=""` — a runtime without additional switches |

The local `~/.m2/toolchains.xml` offers both JDK 17 and 21, so it can be built on 21; **the project is,
however, compiled with `--release 17` and run without any module switches**.

### 3.1 Why it is blocking and not cosmetic

If it were only about a version in the descriptor, it could be worked around. But on JDK 17 the spike
**does not measure what it exists for**, and that twice over.

First, jVector chooses the implementation of its distance functions at runtime in the class
`VectorizationProvider` (web-only) and the condition for the SIMD path is `Runtime.version().feature() >=
20`; it moreover checks the presence of the module `jdk.incubator.vector` in the boot layer. On JDK 17
both conditions fail and jVector silently falls back to the scalar `DefaultVectorizationProvider`. The
criterion "latency < 10 ms per 1M vectors" is then measured on code several times slower than what would
run in production — the result is unusable in both directions, because neither success nor failure says
anything about the target state.

Second, the same version decides which layer of the multi-release jar the JVM sees at all (§4.2), i.e.
also whether the vectors are read through `MemorySegment` or through the older `MappedByteBuffer`. A spike
that is to decide about the mmap integration must not have this variable fixed by accident.

Raising the baseline is therefore **not P6's task but its entry condition** — it has its own blast radius
(compilation, CI, Docker, dependency compatibility) and does not belong under the heading of a vector
spike.

### 3.2 Five coordinated changes

For `jdk.incubator.vector` really to get a word in, five places have to be touched at once; omitting any
of them manifests either as a compilation error or — worse — as a silent run on the scalar fallback:

1. `requires jdk.incubator.vector;` in the `module-info.java` of the module containing the vector
   mathematics (the repository has 23 module descriptors, the modules are named);
2. `--add-modules jdk.incubator.vector` in `<compilerArgs>` (`pom.xml:658`);
3. the same in surefire's `<argLine>` (`pom.xml:698`), otherwise the tests will run differently from the
   compilation;
4. the same in the runtime configuration, i.e. `EVITA_JAVA_OPTS` in `docker/Dockerfile:29`;
5. the same in **JMH's `jvmArgs`**, or `@Fork(jvmArgsAppend = …)` on the benchmark.

The fifth place is the most treacherous and deserves its own paragraph, because it concerns P6's main
number directly. **JMH forks its own JVM and does not inherit surefire's `<argLine>`.** The latency
benchmark per §10.4 will therefore run on the scalar `DefaultVectorizationProvider`, print numbers, report
no error — and those numbers will be compared against the criterion "< 10 ms". It is precisely the failure
§3.1 is meant to prevent, reintroduced seven chapters later by a different mechanism. The consequence for
measurement is recorded in §10.4: until the log from the forked JVM confirms the selection of
`PanamaVectorizationProvider`, no measured latency may be believed.

A practical recommendation following from that for the design: **isolate the vector mathematics into a
single module** with its own descriptor and with a scalar fallback, do not scatter it through
`evita_engine`. Both Lucene and jVector solve the same problem with exactly this pattern and in both cases
it has the same reason — an incubator module must not be a hard condition of startup.

### 3.3 Day zero: what to verify before anything else

There is one fact I could not read reliably from the web and which may turn the whole plan around. The
module `jvector-twenty` is compiled with `<release>20</release>` and `--enable-preview` appears in the
configuration — per the source's summary it is commented out in the compiler and active only in surefire.
Were it, though, active during compilation too, the classes in the `META-INF/versions/20` layer are marked
preview and **will not load at all on JDK 21**, because the preview class-file format is tied to an exact
JDK version.

There is no point in tracking it down by reading more sources — it is a ten-minute experiment and jVector
answers it itself. The oracle is its own logging: `VectorizationProvider` prints the warning "Java vector
incubator module is not readable…" when the module is missing, and stays silent when the SIMD path
succeeds. Day zero of the spike therefore looks like this: run a trivial program against jVector on JDK 21
with `--add-modules jdk.incubator.vector` and without it, and read off from the log which provider was
selected. The same for `ReaderSupplierFactory` (§4.4), which reports the same way which read backend it
fell back on.

---

## 4. Facts about jVector

Everything in this chapter is **web-only** — there is no jVector checkout in `/www/oss` and there is not
even a mention of it in the evitaDB repository (verified with `rg`). Sources: `datastax/jvector` on GitHub
(the files `pom.xml`, `README.md`, `UPGRADING.md`, `ReaderSupplierFactory`, `VectorizationProvider`,
`GraphSearcher`, `SimpleMappedReader`, the assembly descriptor `mrjar.xml`), the GitHub API for the list
of releases and Maven Central. For claims I read through a summarizing layer and not verbatim, I state so
separately.

### 4.1 Maturity and version — a second, separate fork

This is a fact the research could not know and which changes the shape of the decision: **jVector 4.0 has
no final release**. The development branch `main` carries the version `4.0.1-SNAPSHOT`, and the list of
releases looks like this:

| Tag | Released | Note |
|---|---|---|
| `4.0.0-rc.9` | 2026-07-21 | the latest so far |
| `4.0.0-rc.8-hf1` | 2026-06-19 | a hotfix, marked as a prerelease |
| `4.0.0-rc.8` | 2026-04-03 | |
| `4.0.0-rc.6` | 2026-04-03 | |
| `4.0.0-beta.6` | 2026-06-09 | |

The stable line that can be taken from Maven Central as a released version is **3.0.x**. The choice
between it and one of the 4.0 release candidates is therefore **a second fork, independent of the first
one** (jVector vs. an in-house HNSW), and has to be decided consciously:

- **3.0.x** is released and stable, but loses the hierarchical graph (layering à la HNSW over Vamana
  layers, new in 4.0), NVQ and Fused PQ in its current form.
- **4.0.0-rc.x** has all three, but it is a release candidate that has been in RC state suspiciously long
  — from April to July 2026 four came out and no final arrived. For a database's production dependency
  that is a risk not paid for by performance but by a willingness to live with the format or the API still
  moving.

Recommendation: **the spike measures on 4.0.0-rc.9**, because it decides architecture and the target
capabilities need to be seen; but into the mini-gate's decision it should be written that a *production*
deployment either waits for the final 4.0, or is done on 3.0.x with an awareness of what is missing. That
difference has to be explicit in the ADR, otherwise somebody overlooks it later.

### 4.2 The artifact's structure and runtime dispatch

jVector is published as a **multi-release jar** under the coordinates `io.github.jbellis:jvector`,
assembled by the module `jvector-multirelease` through `maven-assembly-plugin` and the descriptor
`src/assembly/mrjar.xml`, with the manifest entry `Multi-Release: true`. The layering is:

| Layer in the jar | Source module | Compilation | Content |
|---|---|---|---|
| root | `jvector-base` | `release 11` | the graph, disk, quantization, scalar mathematics |
| `META-INF/versions/20` | `jvector-twenty` | `release 20` + the vector incubator | Panama SIMD |
| `META-INF/versions/22` | `jvector-native` | (release 22) | native acceleration through FFM |

The choice of implementation is not made by the JVM itself but by reflection at runtime in the class
`VectorizationProvider`, which tries in order `PanamaVectorizationProvider`, `NativeVectorizationProvider`
and finally the scalar `DefaultVectorizationProvider`. While doing so it checks
`Runtime.version().feature() >= 20`, the version `20.0.2` because of the specific bug JDK-8301190, the
presence of the module `jdk.incubator.vector` in the boot layer, an enabled C2 compiler and — curiously —
also a "buggy default locale". It is the same pattern Lucene uses in its `VectorizationProvider`
(`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/internal/vectorization/VectorizationProvider.java:137-193`),
including an almost identical wording of the warning about an unreadable incubator module.

For evitaDB a pleasant conclusion follows: **jVector does not break when the module is missing** — it
merely runs more slowly and says so in the log. A fallback is therefore available for free, which makes
§3.2 easier.

### 4.3 Building the graph and incremental maintenance

Algorithmically jVector does not copy HNSW: per its README it combines **the hierarchy from HNSW with the
Vamana algorithm (the core of DiskANN) inside each layer** and builds on non-blocking concurrency that per
its authors scales linearly with the number of cores. The hierarchy is new in 4.0 and `GraphIndexBuilder`
got a constructor parameter for it; different maximum degrees for different layers were added in the same
place.

The fundamental difference against Lucene, and probably the most important property of the whole library
for evitaDB, is **incremental maintenance**. `GraphIndexBuilder.addGraphNode` adds nodes at runtime and
`markNodeDeleted` deletes directly in the graph — since version 2.0, and since 3.0 thread-safely and with
parallelized removal. The build is closed by calling `cleanup()`.

Lucene cannot do this by construction and it is worth seeing why, because that is exactly the difference an
in-house implementation would have to catch up with. In Lucene the graph does grow incrementally *during a
segment's write* — `Lucene99HnswVectorsWriter$FieldWriter.addValue` calls `addGraphNode` directly
(`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/codecs/lucene99/Lucene99HnswVectorsWriter.java:777-795`)
— but once the segment flushes, the graph is immutable; `HnswBuilder`'s javadoc says so outright ("no
further updates to the graph are accepted"). A change of a vector is handled by deleting it and inserting a
new one into another segment, and merging segments is done through `IncrementalHnswGraphMerger`, which
initializes itself from the largest existing graph — but only if it does not have more than 40 % deleted
documents in it (`DELETE_PCT_THRESHOLD = 40`).

evitaDB's model is, however, a transactional database without segments. Either a library with incremental
maintenance is adopted, or a segment layer nothing needs today has to be invented. That is a strong
argument for jVector and it should have a corresponding weight in the mini-gate.

### 4.4 Reading from disk: what mmap actually is

`ReaderSupplierFactory` (web-only, read verbatim) tries three read backends in this order:

1. `MemorySegmentReader$Supplier` through reflection, with the comment "available under JDK 20+";
2. `MMapReader$Supplier` — note that it lives in `io.github.jbellis.jvector.example.util`, i.e. in the
   module `jvector-examples`, and per its log message requires "a 3rd party linux-only native mmap
   library"; unusable for us;
3. `MappedChunkReader.Supplier` as the last resort, instantiated directly.

Beside them there is `SimpleMappedReader`, which maps a file through
`raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, raf.length())`, holds it as a `MappedByteBuffer`
and **refuses files above 2 GB** with an explicit exception. It releases the mapping through
`sun.misc.Unsafe.invokeCleaner`, which means the module `jdk.unsupported` comes into play — for evitaDB's
JPMS configuration that is a detail to be borne in mind. `MappedChunkReader`, as its name suggests, maps in
parts and therefore does not suffer from the 2 GB cap.

The practical consequence: **on JDK 21 `MappedChunkReader` will probably run, not `MemorySegment`**,
because `java.lang.foreign` is still preview on 21. The research §5.3 anticipates it correctly ("today
`MappedByteBuffer`, from JDK 22 the final FFM API"). Which backend is actually selected the spike will read
off from the log on day zero (§3.3).

### 4.5 Quantization: "BBQ/RaBitQ" is not in jVector

The research §5.2 speaks of "RaBitQ/BBQ-class quantization (~32×)". That naming does not exist in jVector
and it has to be corrected, because otherwise the prototype will be looking for something that is not
there.

jVector offers four techniques: **PQ** (product quantization, optionally with anisotropic weighting), **BQ**
(binary quantization), **Fused PQ** (formerly Fused ADC — the codebooks are written inline into the
neighbour list, so they need not be in memory during a search) and **NVQ** (a non-linear transformation
fitted per vector, new in 4.0).

The names RaBitQ and BBQ belong to a different world. "BBQ" is a marketing name from Elasticsearch and in
Lucene no class is called that — verified, the string `BBQ` is in Lucene's main source tree only in one
comment in a JMH benchmark. The corresponding implementation there is `OptimizedScalarQuantizer`, which
cites RaBitQ directly in the code
(`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/util/quantization/OptimizedScalarQuantizer.java:374`),
and its on-disk format distinguishes among other things the mode `SINGLE_BIT_QUERY_NIBBLE` — 1 bit per
document and 4 bits per query, i.e. asymmetric scoring.

What follows for P6's criteria: **the compression ratio of ~32× in jVector is provided by BQ**, so the
estimate of §5.2 (~90–100 MB of codes per 1M × 768) stays valid. But **BQ with rescoring is not RaBitQ with
rescoring** — RaBitQ is newer and its main benefit is precisely better recall at the same ratio. The
criterion **recall@10 ≥ 0.95 is therefore the one most threatened by that substitution** and the spike has
to give it the most attention: measure BQ and PQ, and if BQ does not suffice for recall, examine whether
the target is not rather PQ or NVQ with a higher memory footprint.

Verification over the Elasticsearch checkout (main, commit `9a100e2d0e41`, 2026-08-13) adds concrete
numbers to it that the spike need not seek from scratch. They have to be read **with the above correction
of terminology, not against it**, though: they are values Elasticsearch chose for its own BBQ, i.e. for a
technique whose counterpart in jVector we look for in BQ, not for the same implementation. Elasticsearch
switches binary quantization on by default from `BBQ_DIMS_DEFAULT_THRESHOLD = 384` dimensions and its
absolute lower bound is `BBQ_MIN_DIMS = 64` (`DenseVectorFieldMapper.java:295` and `:159`); below the
threshold it chooses four bits per dimension instead of one in the disk variant. For typical embeddings of
384 or 768 dimensions it means one-bit quantization is considered usable in the field — good news for the
memory analysis of §5.2 and a reasonable **default measurement value**, not an adopted constant.

The second number is more important, because it aims directly at the recall criterion. Elasticsearch
**adds oversampling by a factor of 3.0 and rescoring at full precision by default** on top of the
quantization (`RescoreVector`, `DEFAULT_OVERSAMPLE = 3.0F`, `DenseVectorFieldMapper.java:294`), and it is
instructive that this default was introduced only subsequently, by a separate index version flag — the
ordering from the quantized space alone turned out insufficient in practice. The criterion of §1
("recall@10 ≥ 0.95, measured with rescoring") is therefore correctly set up and the factor 3.0 is a good
default choice of the ratio of `rerankK` to `topK` in jVector (§4.6). Its consequences for the latency
budget and for the file layout are recorded in §7.2.

A last note about the configuration space: **Fused PQ has fixed constraints**, not optional knobs — it
requires disk format version 6 and higher, a maximum graph degree of 32 and exclusively 256-cluster product
quantization. Whoever wants Fused PQ accepts those three parameters too.

### 4.6 Filtered ANN: `Bits acceptOrds` and what the caller has to watch out for

`GraphSearcher` has three `search` overloads, the most general of which (marked `@Experimental`) looks like
this:

```java
public SearchResult search(SearchScoreProvider scoreProvider, int topK, int rerankK,
                           float threshold, float rerankFloor, Bits acceptOrds)
```

The filter is applied **inline during the graph walk** — in the code there is a test of the form
`acceptOrdsThisLayer.get(topCandidateNode) && topCandidateScore >= threshold`. That is exactly the
mechanics §5.4 of the research assumes, so the design fits on this point.

Critical, though, is a sentence from the javadoc that §5.4 does not cover: *"It is the caller's
responsibility to ensure that there are enough acceptable nodes that we don't search the entire graph
trying to satisfy topK."* In other words **jVector has no brute-force fallback and will not have one** —
it degrades into a walk of the whole graph and it is up to the caller not to let that happen. The
selectivity switch therefore has to be implemented by evitaDB (§8) and it is not an optional improvement
but a necessary part.

Rescoring, by contrast, is built in: `rerankK` says how many candidates are re-scored exactly before `topK`
is returned, `SearchScoreProvider` carries both the approximate and the exact scoring function and
`rerankFloor` permits candidates below a threshold to be skipped. The returned `SearchResult` carries,
besides the results, `visitedCount`, `expandedCount` and the number of nodes actually re-scored — which are
precisely the metrics P6's harness needs, and it means foreign code does not have to be instrumented.

**The interruptibility of the graph walk is unanswered and it is an integration risk.** Verification over
the OpenSearch checkout (main, commit `36edc05ac84`, 2026-08-12) shows it had to solve this problem too:
its `ContextIndexSearcher` implements, beside its own query cancellation mechanism, Lucene's `QueryTimeout`
interface, and explicitly because components such as `TimeLimitingKnnCollectorManager` enforce a limit by
that second route (`server/src/main/java/org/opensearch/search/internal/ContextIndexSearcher.java:163` and
`:623`). The reason is general and applies to us too: **a walk of an ANN graph is one long loop without a
natural interruption point**, and the only thing that stops it from outside is a check the library lets
inside itself. evitaDB has a query interruption mechanism, but it has to grow right into the library's
inner loop — otherwise a cancelled or timed-out query runs to completion and the thread and the memory are
released only after it. Whether `GraphSearcher` permits it at all (a cancellation predicate, a time limit,
or at least a cooperative check in the acceptance function) is **a concrete verification point of the
spike**: it belongs in day zero (§9, step 1) and in the mini-gate's table (§6.2), because a database that
cannot cancel its own query has an integration defect regardless of what latency it measures.

A small thing for readers of older code: the method `usePruning` is marked deprecated in the current `main`
and does nothing.

### 4.7 Dependencies, licence and JPMS

The licence is **Apache 2.0**, which is unproblematic for evitaDB.

The transitive dependencies are four and they are mild: `org.apache.commons:commons-math3:3.6.1`,
`org.agrona:agrona:1.20.0`, `org.slf4j:slf4j-api:2.0.16` and `org.yaml:snakeyaml:2.4`. From evitaDB's point
of view `slf4j-api` is already present (`evita_common/pom.xml:42` and others) and `snakeyaml` too, in
version 2.6 (`pom.xml:131`) — i.e. newer, so version management in the root POM resolves the conflict
itself. **New are `commons-math3` and `agrona`**; both have to be examined for size and security
advisories, but neither is a framework that would drag in a further tree.

**JPMS is an open question and at the same time the biggest integration risk.** In the POM of the module
`jvector-multirelease` I did not find an `Automatic-Module-Name` entry and in the browsed repository tree
no `module-info.java` either — both are, however, **a negative finding from incomplete reading** (the tree
listing was truncated, I did not see the manifest), so I take it as unverified. If `Automatic-Module-Name`
is missing, jVector is an automatic module with a name derived from the file's name, which for evitaDB's
named-module build (23 descriptors) is fragile and for `jlink` outright disqualifying. Verification is
cheap and belongs in the spike's first two days: `unzip -p jvector-*.jar META-INF/MANIFEST.MF` plus a trial
`requires` from a named module.

---

## 5. What already exists in evitaDB's code

### 5.1 Storage is append-only, but "a file per catalog version" does not exist

The philosophy §5.3 of the research leans on really does hold: writing is done with a single write handle in
append mode (`FileOutputStream(theFile, true)` in `WriteOnlyFileHandle.java:220`), the fsync goes through
`FileChannel.force(true)` in the same place at `:281-284`, reading is done with a pool of read-only handles
and old content is never overwritten — only compaction cleans it up (`OffsetIndex.java:1261`).

The file naming convention works differently from what §5.3 assumes, though. The name carries a
**`fileIndex`, not a catalog version**, and `fileIndex` is increased **only on compaction**:

| File | Generator |
|---|---|
| `prefix_<fileIndex>.catalog` | `CatalogPersistenceService.java:185` |
| `camelCase-<pk>_<fileIndex>.collection` | `CatalogPersistenceService.java:237` |
| `prefix_<fileIndex>.wal` | `CatalogPersistenceService.java:283` |
| `prefix.boot` | `CatalogPersistenceService.java:173` |

The mapping from a catalog version onto a file is done by the **bootstrap file** — an array of fixed-length
records (`CatalogBootstrap.java:41`, `RECORD_SIZE` 36 bytes at `:59` and `:64`), addressable by index, so
looking up the state at a given version or at a given moment is a plain computation of a position
(`getPositionForRecord` at `:85`).

The consequence for the design: vector files are not to be named by `catalogVersion`. They are to adopt the
same `fileIndex` convention and **bind to a bootstrap record**, otherwise the prototype builds a second,
parallel and inconsistent versioning system.

### 5.2 Cleanup: `ObsoleteFileMaintainer` is a finished hook

Here the situation is better than could be expected — evitaDB has exactly what §5.3 needs and nothing has
to be invented. `ObsoleteFileMaintainer` (`ObsoleteFileMaintainer.java:77`) offers the method
`removeFileWhenNotUsed(catalogVersion, path, removalLambda)` at `:200`, which
`DefaultCatalogPersistenceService.java:4000` uses today. **That is the API by which the prototype registers
its old vector files for deletion.**

The decision "nobody is looking at the file any more" is made through a retention floor (`getRetentionFloor`
at `:358`), which is the minimum of a monotonically growing floor of active readers and of explicitly
pinned catalog versions (`catalogVersionPinned` at `:300`). The deletion itself then runs under an
exclusive directory lock in `purgeObsoleteFiles` at `:479`, complemented by `reclaimUnreachableFiles` at
`:549`, which cleans up files no retained bootstrap record can reach any more.

Time travel is a configuration option (`StorageOptions.java:145-147`, among others `timeTravelEnabled` and
`minimalActiveRecordShare`); with time travel off the old files are deleted right after compaction. The
decision about compaction itself is in `DefaultCatalogPersistenceService.java:1199-1205`.

### 5.3 mmap is not in the repository at all

Searching the whole tree for `MappedByteBuffer`, `FileChannel.map`, `MemorySegment`, `Arena` and
`java.lang.foreign` returns **zero results** (outside `target/`). Not even the vendored
`evita_roaring_bitmap` has the upstream package `buffer/`, so there is no `ImmutableRoaringBitmap` over
mapped memory there.

Today reading is done through a `RandomAccessFile` wrapped in a `RandomAccessFileInputStream` and further
into an `ObservableInput` (`ReadOnlyFileHandle.java:94-96`); `FileChannel` is used **exclusively** for
fsync, never for `map()`. There is an off-heap branch for in-progress transactions (`OffHeapMemoryManager`),
but that is `ByteBuffer.allocateDirect`, i.e. something else.

It means **P6 introduces the first mmap into evitaDB at all**. That is architecturally exactly what §5.3
wants, but it is good to know that no precedent is being built on — none exists. Two things are connected
with it that the spike has to watch: the behaviour on unmapping (on JDK 17/21 that means
`Unsafe.invokeCleaner`, see §4.4) and the fact that a mapped file must not be deleted before the mapping is
released — which is precisely why the retention floor described in §5.2 is not optional.

A useful detail for the format: `StorageRecord` (`StorageRecord.java:64`) has 22 bytes of overhead
(`OVERHEAD_SIZE` at `:85`), a bit for record continuation (`CONTINUATION_BIT` at `:93`) and — most
interesting for vectors — **raw read methods without deserialization**, `readRaw` at `:326` and
`readRawInto` at `:372`. If the vectors were to be stored in the existing structure, that is the way of
avoiding Kryo.

### 5.4 An embedding cannot get into evitaDB today

This is the most serious finding of the whole plan and it directly refutes the assumption of §5.6 of the
research, per which Sage will supply the embeddings "as entity data (an attribute / associated data of type
`float[]`)".

**Neither `float` nor `Float` is a supported data type.** The list `SUPPORTED_QUERY_DATA_TYPES`
(`EvitaDataTypes.java:660-691`) contains `byte`, `short`, `int`, `long`, `boolean`, `char`, `String`,
`BigDecimal`, temporal types, ranges, `Locale`, `Currency`, `UUID` and a few specials — **no `float` and no
`double`**. The only decimal type is `BigDecimal`. A trap that is easy to fall into: the table
`primitiveWrappers` at `:699` contains `float.class → Float.class`, but that is a boxing map, not the set of
supported types. Arrays are validated by component type (`isSupportedTypeOrItsArray` at `:816`), so `int[]`
passes and **`float[]` does not**.

The second path, associated data through a `ComplexDataObject`, is even worse than one would expect — and
that is a finding not visible from the type list. The conversion does not reject `float` but **silently
converts it to a `BigDecimal` through a decimal string**:

```java
return new DataItemValue(new BigDecimal(Float.toString((float) propertyValue)));
```

(`ComplexDataObjectConverter.java:759`, reading back through `Float.parseFloat` at `:1225-1226`.) For a
768-dimensional vector it means 768 `BigDecimal` instances in the `DataItem` tree per entity — it is
unusable in memory, in computation and in write size, and it moreover goes through the formatting and
parsing of a decimal number for every component.

**Both paths §5.6 gives are therefore closed.** A fork thereby arises that P6 has to decide, because
without a path for a vector to get in there is nothing to index:

**Variant A — extend the type system with `float[]`.** It means an intervention into `EvitaDataTypes`, into
the schemas, into the gRPC, GraphQL and REST layers and into the Kryo serializers including their backward
compatibility. As a target it is probably the right solution, but the extent of the impact is enormous and
utterly disproportionate for a spike.

**Variant B — a `byte[]` carrying float32 in little-endian.** It requires **no change to the engine**:
`byte` is a supported type (`EvitaDataTypes.java:662`) and `byte[]` passes validation through
`isSupportedTypeOrItsArray` (`:816`). It is immediately usable; the cost is a loss of type information and
the need to hold the encoding convention outside the type system.

**Variant C — a custom `StoragePart` and custom mutations.** It gives full control over the format and can
avoid Kryo through `readRaw` (§5.3). For production it is the cleanest path, but it is extra work even
before we know whether jVector passes the mini-gate at all.

**Variant D — a dedicated field type for embeddings.** Verification over the OpenSearch checkout (main,
commit `36edc05ac84`, 2026-08-12) shows that elsewhere this is not done through the type system. In the
experimental record `FieldTypeCapabilities`
(`server/src/main/java/org/opensearch/index/engine/dataformat/FieldTypeCapabilities.java`) `VECTOR_SEARCH`
is carried as **a separate capability of the data format beside `FULL_TEXT_SEARCH`, `COLUMNAR_STORAGE` and
four others**, each with its own physical structure. A vector field there is therefore not "an attribute
that happens to contain an array of numbers" but its own field type with its own storage. (The k-NN plugin
itself is not in that checkout, so what is evidenced is **the capability declaration and the seam**, not the
implementation — the source's caveat applies and nothing about specific vector engines may be derived from
it.)

For us it is more of a relief than a complication and it changes the effort estimate against variant A.
Variant A extends the **type system**: `float[]` becomes a generally usable attribute value, and therefore
touches every path an attribute's value passes through — filters, ordering, projections into gRPC, GraphQL
and REST, and Kryo as well as WAL serialization. A dedicated field type for embeddings — **not filterable,
not sortable, not returned as an ordinary attribute value** — is by contrast one new bounded concept: it
needs a declaration in the schema, a mutation, a path inwards and serialization, i.e. still the path of the
`evita-schema-change` skill, but **none of those query projections**, because nobody queries an embedding
as a value. That is a substantially smaller surface than A and it is a variant neither §5.6 of the research
nor the plan so far considered. The shape of that declaration in the schema belongs in `schema-design.md`;
P6 merely names the variant and attaches its consequence for the effort estimate.

Recommendation: **variant B for the spike**, because it unblocks the work immediately and at zero cost on
the engine's side; 768 × 4 B = 3,072 B per entity fits comfortably below one record's cap
(`DEFAULT_OUTPUT_BUFFER_SIZE` is 2 MB, `StorageOptions.java:153`). The decision for production **belongs in
the ADR after the mini-gate**, not in the spike, and is newly **three-way** (A, C, D) instead of two-way; it
should lean on how the question of ownership of the vector files turns out (§7). By today's state of
knowledge the most promising is **D** — it carries the type information B loses and yet does not have A's
surface — but it is a freshly opened variant without any verification, so the decision belongs beyond the
gate, not here.

### 5.5 The Panama Vector API and JPMS

Use of `jdk.incubator.vector`, `VectorSpecies` or `FloatVector` does not exist in the repository (zero
occurrences). The configuration consequences are described in §3.2. The modules are named — 23
`module-info.java` descriptors, for example `evita.engine`
(`evita_engine/src/main/java/module-info.java`) —, which is precisely the context that makes the
`Automatic-Module-Name` question about jVector (§4.7) so important.

---

## 6. The spike's design

### 6.1 Three paths and a recommendation

**Path A — jVector standalone, outside evitaDB.** A harness as an isolated module or a side project that
builds an index over a dataset, measures recall and latency and does not touch evitaDB's storage at all. It
answers both criteria of §1 and the question of quantization (§4.5) quickly and cheaply. It answers nothing
integrational.

**Path B — jVector integrated into evitaDB's storage.** Vector files as immutable append-only artifacts
beside the catalog, read through mmap, cleaned up through `removeFileWhenNotUsed` (§7). It answers the
integration risks, but is an order of magnitude more expensive and there is no point starting it before
path A shows the library gives numbers at all.

**Path C — an in-house HNSW.** The extent of the work can be estimated from Lucene, but it has to be counted
honestly: the package `org.apache.lucene.util.hnsw` alone has **5,263 lines** in 28 files
(`HnswGraphBuilder` 745, `HnswGraphSearcher` 439, `OnHeapHnswGraph` 328, `NeighborArray` 342,
`FilteredHnswGraphSearcher` 275, the concurrent merge another ~700). To that the distance kernels have to be
added (in Lucene `VectorUtil` 606 lines plus scalar support ~500), an on-disk format — evitaDB would not
write it in Lucene's form, but it needs some, of the order of 800–1,200 lines —, and at least one
quantization (Lucene has 1,720 lines in `util/quantization`, the binary variant alone would be considerably
smaller). A realistic estimate of production code is therefore **8 to 10 thousand lines**. The test surface
in Lucene stays for the same package at 3,553 lines against 5,263 lines of code, i.e. roughly **0.7×**,
which adds another 5 to 7 thousand lines. **In total 13 to 17 thousand lines** — and that is only the
writing; the empirical tuning of recall and latency, i.e. the part Lucene and jVector have behind them after
years, is not in that number at all.

**Recommendation: the spike leads through A, continues into B, and C is opened only if A fails.** Path A
answers the criteria of §1 fastest and is disposable; path B is what actually decides the architecture. Path
C is justifiable at that extent only by A's failure on the criteria or a hard block on integration (the most
likely candidate: JPMS, §4.7) — and even then the first question should be whether, instead of writing an
in-house HNSW, Lucene's vector packages could be adopted as a dependency, because the research rejected
Lucene as an *engine*, not as a library (§3 of the research permits Lucene as an ordinary Maven dependency
for analyzers).

### 6.2 The mini-gate: decision criteria

The criteria and their thresholds are set in advance. The "weight" column says how the result is reflected:
*blocking* means that failing it alone decides against jVector.

| Criterion | How it is measured | Threshold | Weight |
|---|---|---|---|
| recall@10 with rescoring | against a brute-force ground truth | ≥ 0.95 | blocking |
| query latency | 1M × 768, one thread, p50 and p95 | < 10 ms | blocking |
| filtered ANN | latency and recall across selectivities (§8) | no drop in recall | blocking |
| incremental maintenance | adding and deleting at runtime without a rebuild | works | blocking |
| JPMS | `requires` from a named module | works, or has a workaround | blocking |
| interruptibility | cancelling a query inside the graph walk (§4.6) | the query can be cancelled | high |
| mmap compatibility | which backend is selected on the target JDK | maps, does not fail at 2 GB | high |
| memory | codes + graph outside the raw vectors | within the bounds of §5.2 | high |
| maturity and format | a released version vs. an RC, format stability | see §4.1 | high |
| dependency size | the transitive tree, CVEs | no frameworks | medium |

The last row of the table is at the same time a reminder that **the mini-gate's result is not only
"yes/no", but also a choice of version** (§4.1). That is recorded separately.

### 6.3 The seam: the decision has to stay reversible

Regardless of how the gate turns out, all contact with jVector has to pass through **a single narrow
interface** — something of the shape "build an index over a vector supplier", "add", "delete", "search
top-K with an acceptance bitmap". The reason is not aesthetic: if jVector passes with the maturity caveat
(§4.1), it is likely that in a year the version or even the library will change, and the seam is the only
thing distinguishing such a swap from a rewrite. The interface is designed in path A and validated in path
B; whether jVector, Lucene, or in-house code stands behind it in the end should not be recognizable from the
calling code.

---

## 7. Storing vectors and mapping ordinals onto primary keys

### 7.1 Ordinals are not primary keys

This is a construction detail §5.4 of the research implicitly skips when it writes about the inline test
`bitmap.contains(pk)`, and which cannot be circumvented.

Graph indexes — jVector and Lucene alike — address nodes by **dense ordinals `0..N-1`**. Entity primary keys
in evitaDB are by contrast **sparse**: entities are deleted, the keys are not renumbered. `Bits acceptOrds`
is meanwhile indexed by ordinal, not by key. The real shape of the inline test therefore is:

```
bitmap.contains(ordToPk[ord])
```

**Both directions** are needed: `ord → pk` during the graph walk and when translating the results back to
entities, and `pk → ord` on write, when an existing vector is to be replaced or deleted.

A requirement follows that has to be in the storage design from the start: **the mapping is an artifact
bound to a catalog version**, stored beside the vectors, versioned with them and rebuilt on compaction. A
plan omitting it stops somewhere in the third step of realization.

### 7.2 The file layout

The design starts from what §5.1 found about the existing convention — i.e. `fileIndex` in the name and
binding to a bootstrap record, not a catalog version in the name. A triple of artifacts:

- **raw vectors** — a dense `float32` block, immutable, read through mmap, never on the heap; its only
  consumer is rescoring (§4.6);
- **the quantized codes and the graph** — per §5.2 of the order of 200–350 MB per 1M vectors, i.e. a
  candidate for the heap or for direct buffers, not necessarily for mmap;
- **the ordinal mapping** — bidirectional, per §7.1.

The oversampling factor of 3.0 from §4.5 does not introduce this decomposition, but it **quantifies** it,
and thereby refines the layout requirement. With `topK = 10` the rescoring reaches for full precision for
roughly thirty candidates per query, and all of it inside the 10 ms budget. The requirement thereby moves
from "full-precision vectors have to remain reachable even after quantization" to **"they have to be
reachable by random access, by individual ordinals, without deserialization and without a copy onto the
heap"** — i.e. exactly in the shape `readRaw` and `readRawInto` (§5.3) or an mmap over a dense block give. A
second consequence of the same is temporal: full-precision vectors have to survive **any change of
quantization**. In a model of a single live structure a change of quantization is a rebuild of the vector
index (Elasticsearch can afford the opposite only thanks to segments, where every segment carries its own
codec), and the only source from which that rebuild can be performed is precisely this block.

An open question the spike has to decide and which is recorded in §11: whether these files are **owned by
jVector in its own format** (`OnDiskGraphIndex` can write and read its own structure), or whether they are
**owned by evitaDB** and jVector is given only a read interface. The first variant is faster and it is the
obvious path for path A; the second is more consistent with the storage and is necessary if the files are to
pass through compaction and time travel like everything else.

### 7.3 The lifecycle

Writing a new version is an append of a new file; the old version stays as long as somebody is looking at
it. The cleanup **is not invented** — it is registered through
`ObsoleteFileMaintainer.removeFileWhenNotUsed` (`ObsoleteFileMaintainer.java:200`), which already knows the
retention floor from active readers and pinned versions (§5.2).

The registration itself, however, **does not protect a mapped file**, and this is the trickiest place in the
whole of §7. The retention floor is computed from active readers and pinned catalog versions
(`ObsoleteFileMaintainer.java:358`, `:286`, `:300`) — but **a live `MappedByteBuffer` is invisible to it**:
evitaDB tracks sessions and catalog versions, not who holds a mapping. A file registered for deletion and at
the same time mapped can therefore disappear from under a reader's hands.

The solution is in the same API, merely two methods away: the vector reader has to **call
`catalogVersionPinned(v)` on mapping and `catalogVersionReleased(v)` on unmapping**
(`ObsoleteFileMaintainer.java:300` and `:309`). The mapping thereby becomes a full-fledged participant in
the floor's computation and the file survives exactly as long as it should. The pin protocol has moreover to
order the release correctly: **unmapping on JDK 17/21 is an explicit `Unsafe.invokeCleaner`, not the work of
the garbage collector** (§4.4, §5.3), so "release the mapping" is an action somebody has to perform and which
has to happen before `catalogVersionReleased`.

---

## 8. Filtered ANN and integration with bitmaps

### 8.1 Three mechanisms, not one threshold

The research §5.4 speaks of a single selectivity threshold "~1–2 %", below which a switch to a brute-force
scan happens. Lucene shows it is in fact **three different mechanisms with very different thresholds**, and
knowing them is more valuable for setting P6 up than an adopted number.

**The first threshold — an immediate exact search at query level.** In `AbstractKnnVectorQuery` there is the
condition `if (cost <= perLeafTopK) return exactSearch(...)`, where `perLeafTopK` is not a plain `k` but the
expected number of hits in the segment plus three standard deviations of the binomial distribution.

**The second threshold — an exhaustive scan at reader level.** In `Lucene99HnswVectorsReader`
`expectedVisitedNodes(k, graphSize)` is computed, which is per
`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/util/hnsw/HnswGraphSearcher.java:56-58` simply
`(int)(Math.log(graphSize) * k)`; if that number comes out greater than or equal to the number of documents
passing the filter, the graph is not used at all. For our brief it means: with 1M vectors and `k = 10`,
`ln(10^6) × 10 ≈ 138`, so **Lucene goes to brute force only when the filter passes fewer than ~138 out of a
million — i.e. roughly 0.014 %.**

**The third threshold — the choice of a specialized filtered searcher.** `KnnSearchStrategy` has
`DEFAULT_FILTERED_SEARCH_THRESHOLD = 60`, i.e. at **less than 60 % passing** a `FilteredHnswGraphSearcher`
is reached for, which does a multi-step expansion into the neighbourhood of neighbours proportionally to how
restrictive the filter is.

The number "1–2 %" from the research therefore lies between two mechanisms doing different things: above it
Lucene still uses the graph (merely with a different searcher), below it there is still a long way to brute
force. **jVector meanwhile has none of those three mechanisms** — its javadoc explicitly transfers the
responsibility onto the caller (§4.6). The switch is therefore owned by evitaDB and P6 has to design it.

Verification over the Elasticsearch checkout (main, commit `9a100e2d0e41`, 2026-08-13) adds no further
threshold to it, but something more useful: **a naming of both sides of the choice and evidence that it is a
planner's decision, not a fixed strategy.** Elasticsearch has two paths for filtered ANN.
**Pre-filtering** applies the filter already during the graph walk; the default heuristic is, from a certain
index version, the published ACORN method (`DenseVectorFieldMapper.java:199` and `:204`, the choice being
read by `KnnVectorQueryBuilder.java:573`). **Post-filtering**, by contrast, searches the graph without the
filter and applies the filter only to the result (`PostFilterKnnQuery.java`). Between them decides **the
filter's estimated selectivity** — a configurable `postFilterSelectivityThreshold` (same place `:72`), i.e.
classic query planning over an estimate.

Two concrete conclusions follow for P6. First, **our decision is exact, not heuristic**: we have the
candidate bitmap computed at that moment, so we do not estimate selectivity — we know it exactly. The switch
of §8.2 is therefore a threshold function over a known number, not an estimation model that can be wrong; it
is one of the few points where our model is simpler than theirs, and it is worth naming it as such. Second,
**the default strategy for us is post-filtering**. Per Z7 the must-match filter cuts only 5 to 15 % of the
corpus, so it is very weakly selective, and in that regime pre-filtering merely makes the graph walk more
expensive without saving anything. Pre-filtering with an acceptance bitmap is needed only for selective
combinations (a deep category plus a narrow price range), i.e. for the same tail of the distribution the
brute-force insurance exists for. Practically it means the curve of §8.2 has **three branches, not two**: a
graph walk with an acceptance bitmap, a walk without it with a post-filter, and a brute-force scan of the
candidate set.

### 8.2 What P6 measures

The spike's task is not to adopt a number but to **measure the curve**. For a set of selectivities —
proposal: 100 %, 60 %, 20 %, 5 %, 1 %, 0.1 %, 0.01 % — the latency and recall of all three strategies of
§8.1 are measured: a graph walk with an acceptance bitmap, a walk without it with a post-filter, and a
brute-force scan of the candidate set. The curves' intersections are the sought thresholds and Lucene's
values of 60 % and 0.014 % serve as reference points against which the result is compared for common sense.

The second thing this experiment has to show is **the degradation of recall under a filter**. A graph pruned
by an acceptance bitmap stops being well connected, and that is precisely why Lucene reaches for the
multi-step expansion. If recall@10 falls below 0.95 in some band of selectivity, that is a blocking finding
regardless of latency — and it is a finding a purely latency-based measurement would overlook.

Essential for Z7: the research says the must-match filter leaves 85–95 % of the corpus, so **ordinary
traffic falls into a band where the graph is unambiguously the right choice**. A query becomes selective
only in combination with facets and hierarchy; the switch is therefore insurance for the distribution's
tail, not the main path. That is good news for the work budget — but it does not mean it can be omitted,
because without it jVector degrades in that tail into a walk of the whole graph.

### 8.3 The text × vector hybrid

RRF fusion does not belong in P6 (§1.2), but the spike must not close its doors. Concretely that means a
single requirement: the vector leg has to be able to return **a ranking of candidates, not only a top-K with
an incomparable score**, and it has to be able to do it for a `K` markedly larger than the page of results.
RRF works with ranks precisely so that it does not have to calibrate scores against the composite of §4.3,
and `rerankK` in jVector (§4.6) is the parameter through which the ranking's depth is governed. Verifying
that it can be set high enough without latency falling below the criterion is cheap and belongs in the
measurement of §8.2.

Verification over the Elasticsearch checkout (main, commit `9a100e2d0e41`, 2026-08-13) confirms this
assumption in the strongest possible form. The whole RRF fusion is a single line there
(`x-pack/plugin/rank-rrf/…/RRFRetrieverBuilder.java:223`),
`value.score += this.weights[findex] * (1.0f / (rankConstant + frank))` — every leg contributes the
reciprocal of the document's rank in its result, shifted by a constant and weighted by the leg's weight, and
**the individual legs' scores do not enter the computation at all**. For us it follows that RRF is not a
preference among equivalent fusions but **the only correct choice**: our text leg returns a 64-bit
lexicographic composite, and that is a number that cannot be compared with cosine similarity even after
normalization, because it has no scale — it is a packed tuple, not a quantity. The calibration min-max score
normalization would require is therefore not expensive for us but **undefined**. That belongs in the ADR as
a reason, not as a leaning.

From that also follows **where the fusion belongs: it is a named phase between evaluation and paging, not
another lane of the composite.** Verification over the OpenSearch checkout (main, commit `36edc05ac84`,
2026-08-12) shows the same from the other side — fusion hangs there on the seam
`SearchPhaseResultsProcessor`
(`server/src/main/java/org/opensearch/search/pipeline/SearchPhaseResultsProcessor.java:21`), i.e. after the
end of evaluation and before the documents are fetched, and the reason is obvious once stated: **fusion needs
to see both lists whole.** A lane of the composite breaks a tie within one ranking; fusion mixes two
independent rankings and by its nature does not belong in a lexicographic packing. Where that step
physically lives relative to the phases of query evaluation in evitaDB is addressed by `query-design.md`
(§9.5 and §11). P6 holds a single commitment from it — not to close its doors by the vector leg being able
to emit only a top-K without a usable ranking.

---

## 9. The realization procedure, step by step

Steps 0 and 1 are **entry conditions and verifications of assumptions**; there is no point starting anything
else until they pass, because each of them can turn the plan around.

**Step 0 — the JDK 21 baseline.** Raise `java.version` and add the four coordinated changes per §3.2. It is
not P6's work, but without it P6 measures nothing useful (§3.1). Output: the project compiles and the tests
run on 21, `jdk.incubator.vector` is readable.

**Step 1 — day zero, four verifications.** (a) Which `VectorizationProvider` jVector selects on the target
JDK, read off from the log; that at the same time refutes or confirms the suspicion about `--enable-preview`
in `META-INF/versions/20` (§3.3). (b) Which read backend `ReaderSupplierFactory` selects (§4.4). (c) JPMS:
the jar's manifest content and a trial `requires` from a named module (§4.7). (d) Interruptibility: does
`GraphSearcher` offer any way of stopping the graph walk from outside (§4.6)? Any of these four verifications
may decide the mini-gate before the measurement even starts.

**Step 2 — the vectors' ingress.** Introduce for the spike the convention of a `byte[]` with float32 in
little-endian (§5.4, variant B) and verify the round-trip write and read. Without this step further work has
no input.

**Step 3 — the harness and the ground truth.** Build the measurement circuit per §10: loading the dataset,
the brute-force ground truth, computing recall@10, measuring latency. The harness has to be finished
**before** the index, otherwise the first numbers cannot be compared with anything.

**Step 4 — path A, an index without integration.** Build a jVector index over the dataset, measure the
criteria of §1 for the unquantized variant and then for BQ and PQ (§4.5). Output: the first hard numbers of
recall and latency, and the answer to whether BQ suffices for recall at all.

**Step 5 — ordinal mapping and filtered ANN.** Add the bidirectional mapping (§7.1) and measure the
selectivity curve per §8.2, including recall under a filter. Output: a proposed threshold for switching to
brute force, backed by measurement.

**Step 6 — incremental maintenance.** Verify `addGraphNode` and `markNodeDeleted` at runtime: how latency
and recall change after a series of insertions and deletions, and whether the graph degrades enough to need
a periodic rebuild. This is the criterion on which jVector wins against Lucene (§4.3), so it has to be
verified, not assumed.

**Step 7 — path B, integration into the storage.** Only here, and only if steps 4–6 passed: vector files
beside the catalog, mmap reading, registration into `ObsoleteFileMaintainer` (§7.3), a decision about
ownership of the format (§7.2). Output: the answer to the integration half of the mini-gate.

**Step 8 — the decision and the write-up.** Fill in the table of §6.2, decide both branches of the fork (the
library and the version, §4.1) and carry the conclusions into the record ([`../README.md`](../README.md)).

---

## 10. The harness, datasets and measurement

### 10.1 Where the harness lives

The harness belongs **outside the production modules** — it is a disposable measuring rig, not code that
will be maintained. For path A a standalone module that does not depend on evitaDB at all suffices; only
path B connects it with the storage.

### 10.2 Datasets

The brief wants both synthetic and real embeddings and jVector itself shows where to start (web-only): the
repository has a directory `siftsmall` with files in the `.fvecs` and `.ivecs` formats — specifically
`siftsmall_query.fvecs` and `siftsmall_groundtruth.ivecs` — and an example `SiftSmall` demonstrating the
whole cycle over 10,000 vectors. For larger measurements there is `Bench`, which **downloads datasets from
the `ann-benchmarks` project itself** into a `dataset_cache` directory.

A proposal for a three-stage set:

1. **`siftsmall` (10k)** as a smoke test — it verifies the harness computes recall correctly, because the
   ground truth is part of the dataset and the result can be compared with a reference.
2. **A standard ANN dataset at the target scale** — 1M vectors with 768 dimensions are needed for the
   criterion of §1 to make sense. SIFT-1M has 128 dimensions and GloVe around 100, so neither hits the target
   scale; closer are datasets with embeddings from language models (the `dbpedia-openai` families, whose
   vectors have 1536 dimensions). **The specific choice, availability and licence have to be verified** —
   from public sources I could not establish it reliably and it is recorded in §11. If no suitable dataset is
   found, a substitute is a synthetic set with a controlled cluster structure; purely random vectors are
   **misleading** for measuring recall, because in a uniformly distributed high-dimensional space all points
   are similarly distant and the graph then looks better than it would come out on real data.
3. **Real embeddings from a production e-commerce catalog** supplied by Sage. **This is an assumption, not a
   verified fact** — I do not know whether Sage today can produce and export embeddings for those catalogs.
   If it can, it is the most valuable measurement of the three, because only it captures the real
   distribution of the data.

### 10.3 Recall@10 and the ground truth

The ground truth is computed **brute-force** — the exact distance from the query to all the vectors, top-10.
For 1M × 768 that is of the order of a billion floating-point operations per query, so it is computed **once
and stored on disk**; the query set is to be fixed (proposal: 1,000 queries) so that the numbers are
comparable across runs and configurations.

Recall@10 is then a plain ratio: how many of the ten real nearest neighbours appeared in the returned ten,
averaged over the queries. It is measured **after rescoring**, because that is the value the user sees and
because rescoring is precisely what is to make up for the loss from quantization (§4.5).

### 10.4 Latency: a separate harness, JMH for a part only

Recommendation: **measure recall with a separate harness, latency with JMH** — and do not mix them.

The reason is in the nature of both quantities. Recall is a property of a configuration over a fixed query
set; it is deterministic, measured once and JMH would improve nothing about it, because its steady state and
warm-up iterations serve nothing when a ratio of correct answers is being computed. Latency, by contrast,
makes no sense at all without JIT warm-up.

A warning belongs with JMH in this repository, because the harness has documented silent traps — there are
modes in which a benchmark does nothing and yet prints numbers. Recommended settings: `SampleTime` or
`AverageTime` with explicit warm-up iterations, the result always passed into a `Blackhole`, and **run in
the background with completion detected by the content of the output file**, not by the process running. The
configuration is archived with every run — otherwise after three days it is impossible to find out what
number meant what.

**An entry condition of every latency measurement, without exception:** JMH forks its own JVM and does not
inherit surefire's `<argLine>` (§3.2, point 5). Until the log from the **forked** JVM confirms that jVector
selected `PanamaVectorizationProvider`, the measured latency is not valid and must not be compared with the
criterion "< 10 ms". The check is trivial — jVector reports it itself — and it has to be part of the run's
record, not a one-off verification at the start.

### 10.5 Memory

The memory criteria of §1 are measured with **JOL**, by the same procedure P1 prescribes for the text
branch. What is key is to verify two things separately: that the quantized codes and the graph sit within
the estimate of §5.2, and — which is architecturally more important — that **the raw vectors do not appear on
the heap**. The second is not proved by an estimate but by measuring the heap's size with rescoring switched
on over 1M vectors; were the raw data pulled onto the heap, it would show immediately and unmistakably.

---

## 11. Open questions

- **OP6-1 — the JDK entry condition.** When and by whom will the baseline be raised from 17 to 21 per §3?
  Without it P6 measures nothing it exists for. It is the hardest dependency of the whole plan.
- **OP6-2 — the preview lock of `META-INF/versions/20`.** Will the `jvector-twenty` classes load on JDK 21,
  or are they preview-locked to 20 (§3.3)? A ten-minute verification, a fundamental impact.
- **OP6-3 — JPMS.** Does jVector have an `Automatic-Module-Name`? The negative finding of §4.7 is unverified
  and it is a potentially blocking criterion.
- **OP6-4 — the ingress of embeddings into production.** The spike makes do with a `byte[]` (§5.4, variant
  B), but the production shape is undecided and newly **three-way**: extend the type system with `float[]`
  (A), introduce a custom `StoragePart` (C), or introduce a dedicated field type for embeddings with its own
  storage (D). Variant D is supported by the declaration of `VECTOR_SEARCH` as a separate capability of the
  data format in OpenSearch and **substantially changes the effort estimate** against A, because it avoids
  extending the type system with a generally usable attribute value (§5.4). This is a reopening of O5 at the
  technical level; the shape of the declaration in the schema belongs in `schema-design.md`, here belongs only
  the consequence for the scope of work.
- **OP6-5 — ownership of the vector files.** Does jVector write and read them in its own format, or does
  evitaDB own them and jVector gets only a read interface (§7.2)? It decides whether the files pass through
  compaction and time travel like everything else.
- **OP6-6 — the jVector version.** 3.0.x stable without the hierarchy, NVQ and Fused PQ, or 4.0.0-rc.x with
  them and with the risk of a long RC (§4.1)? A separate decision beside the mini-gate.
- **OP6-7 — quantization and recall.** Does BQ suffice for recall@10 ≥ 0.95 with rescoring, or is PQ or NVQ
  necessary at the cost of memory (§4.5)? This is the criterion most threatened by the substitution of RaBitQ
  for BQ.
- **OP6-8 — a dataset at the target scale.** Which public dataset gives 1M vectors with 768 dimensions, under
  what licence terms and how large is it to download (§10.2)?
- **OP6-9 — real embeddings from Sage.** Can Sage today produce and export embeddings for a production e-commerce catalog?
  In §10.2 it is carried as an assumption, not as a fact.
- **OP6-10 — graph degradation under incremental maintenance.** Does the graph need a periodic rebuild after a
  series of deletions, and if so, how does that rebuild meet the storage's compaction (step 6)?
- **OP6-11 — concurrency.** All the criteria of §1 are measured single-threaded. How does vector search behave
  under concurrent load and how does mmap paging meet evitaDB's other I/O? Outside P6's scope, but somebody has
  to measure it before production.
- **OP6-12 — the unit of the vector index: an entity, or a chunk?** The plan so far tacitly assumes one vector
  per entity and nowhere states that assumption. Verification over the Elasticsearch checkout (main,
  `9a100e2d0e41`) shows it does not suffice for long texts: the field type `semantic_text` **creates a nested
  object for chunks itself** and stores in it the chunk's embedding, its text and character offsets
  (`SemanticTextFieldMapper.java:262` and `:276`) — splitting long text is built into the field type there, not
  left to the user. If the unit is a chunk, we need a chunk → PK mapping and **a declared aggregation function
  across the chunks of the same entity** (typically the maximum). That is **the same shape of problem as
  aggregation across references** (O10 of the research, the seam described in P7 §4.5), so the two ought to
  share one mechanism, not two parallel ones. If the unit is the entity, it has to be said how a long article
  fits into a single embedding. The decision is an entry condition for the CMS profile (Z8) and marginal for
  the e-shop one; it influences the data model of the vector branch, i.e. §7.1 and §7.2.
