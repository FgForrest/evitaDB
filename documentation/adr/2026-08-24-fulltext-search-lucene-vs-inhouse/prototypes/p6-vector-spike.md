# P6 — the vector spike: prototype implementation plan

> **Status: an implementation plan, not a decision.** It follows on from the research
> [`../research.md`](../research.md) (v2, consolidated 2026-08-04, last revised 2026-08-12), namely the
> whole of §5, §7 (P6's placement and its mini-gate), O5 and O7. The argument "why an in-house engine and
> not Lucene" is not repeated here — it is in the research (§3).
>
> Written on 2026-08-12. The anchors into evitaDB's code and into the Lucene checkout in
> `/www/oss/lucene` were verified against the state on the same day. Translated from Czech and moved into
> this record on 2026-08-24.
>
> **Revised 2026-09-07, before the spike starts.** Three inputs changed what this plan can say. First,
> **jVector is now on disk** (`/www/oss/jvector`, `main` at `40e0775f`, 2026-09-04, `4.0.0-rc.9` plus
> twelve commits) — every claim the first version marked **web-only** has been read in the source, and
> §4 is rewritten against it; the corrections that matter are listed in §4.0. Second, a further Java HNSW
> library, **hnsw-sb** (`/www/oss/hnsw-sb`, single commit `b09db64b` of 2026-09-07), was put forward for
> assessment; §4.9 records what it is and why it is set aside. Third, an external research note on the
> current state of filtered and quantized ANN (2026-09-07, cited as **(external)** where its claims could
> not be verified against a local checkout) changed the *question* the spike asks: not "jVector or an
> in-house HNSW", but "what is the shape of a vector retrieval subsystem whose planner chooses among
> several physical access paths, and which graph implementation stands behind one of them". §1.3, §6, §8
> and the new §9 carry that reframing; §10 re-sequences the steps accordingly. What changed is marked
> **[2026-09-07]** in place; §4 carries a single marker at its head because none of it survived
> unchanged. Anchors into the Lucene checkout were re-verified against `main` at `13796f80e49`
> (2026-08-11, `11.0.0-SNAPSHOT`); anchors into evitaDB against `258-fulltext-support` at `ee2801c8e`
> (three `ObsoleteFileMaintainer` lines moved, §5.2 and §7.3 carry the new numbers). The criteria of §1
> are unchanged in substance and extended in §1.3.

---

## 1. Goal, scope and criteria

P6 is a **spike**, not a feature prototype. Its task is not to deliver vector search but to decide the
mini-gate of §7 of the research and at the same time to verify that the architectural assumption of §5.3
(immutable append-only files read through mmap, off the heap) really holds in evitaDB. Both are empirical
questions; neither can be answered by reasoning, because both rest on numbers nobody has measured.

**[2026-09-07]** The mini-gate's question is restated in §1.3. The research phrased it as "jVector versus
an in-house HNSW"; reading the libraries shows that the library choice is one component of a larger shape
and cannot be decided on its own. The criteria below stay, because they are criteria on the whole
subsystem, not on a library.

The criteria from §5.2 and §7 of the research stay unchanged:

- **recall@10 ≥ 0.95** against a brute-force ground truth, measured *with rescoring* against the raw
  vectors, not over the quantized codes alone;
- **latency < 10 ms** for 1M vectors (768 dimensions), one thread, without concurrent load.

To them P6 adds three criteria the research did not name but without which the result is unusable for a
decision:

- **a memory cap**: the quantized codes plus the graph have to fit into the order §5.2 estimates
  (~90–100 MB of codes and ~100–250 MB of graph per 1M × 768), and the raw vectors must never appear on
  the heap — verified by measurement, not by construction. **[2026-09-07]** The cap is restated in §9.3
  as *heap-resident bytes per indexed vector*, accounted per component (codes, graph, ordinal mapping,
  search scratch), separately from *mapped* bytes; §9.3 also shows that an 8-bit code already breaks the
  §5.2 figure eightfold, so the cap decides the codec, not merely the library;
- **behaviour under a filter**: latency and recall are measured for filtered ANN across selectivities too,
  because that is precisely where the choice between a graph walk and a brute-force scan breaks (§8);
- **incremental maintenance**: adding and deleting a vector at runtime, without rebuilding the whole index
  — that is a property evitaDB needs because of its write path and which Lucene by construction does not
  have (§4.3, §4.8).

### 1.1 The mini-gate's criteria

The mini-gate does not decide by a single number. jVector may win on performance and still lose on
integration; an in-house implementation may be integrationally clean and yet not reach the recall in a
reasonable time. The decision criteria are therefore set in advance, so that the result cannot be
rationalized retrospectively — their weights and thresholds are in §6.3.

### 1.2 What P6 deliberately does not do

P6 **does not integrate vectors into the query language**. No new constraint, no change to
`orderBy(relevance())`, no RRF fusion in production code — RRF will appear in P6 at most as an offline
computation over two rank lists, to verify that fusion makes sense (§8.4). The DSL's shape is addressed by
O4 and depends on the results of the text gate (P5 → P1 → P2), which runs first.

P6 **does not address query-side embedding** (O7). The harness produces the query vectors itself: either
they are part of the dataset, or they are generated the same way as the document ones. The question of who
embeds a user's query in production is a product one and a spike will not advance it.

P6 **does not build a production write path**. It verifies incremental adding and deleting as a property
of the library, but does not connect it to the transactional layer nor to the WAL. That is work belonging
after the mini-gate's decision.

**[2026-09-07]** P6 **does not implement a partition-assisted structure for pathologically selective
filters** (the Curator family, §8.1). It only verifies that the seam of §6.1 does not make one impossible
later.

### 1.3 [2026-09-07] What the spike decides: a subsystem, not an index

Reading the two libraries and the external note converges on one conclusion, and it changes the spike's
shape more than any single measurement will: **no library on the table is the thing evitaDB needs.** What
evitaDB needs is a retrieval subsystem with several physical access paths — an exact scan over a
candidate bitmap, an unfiltered graph walk with post-filtering, a graph walk with an in-walk acceptance
test, and a predicate-aware walk that widens its neighbourhood when the filter is sparse — chosen per
query by a planner that knows the candidate set's cardinality exactly (§8). Under those paths sit two
independent representations: the graph topology and the vector codec used for traversal, with the raw
vectors kept reachable for reranking (§9). A library can supply the graph, a codec, or a searcher; none of
the three on disk supplies the planner, and only one supplies a predicate-aware searcher (§4.8).

The spike's deliverable is therefore **the measured curve family that lets the planner be written** —
latency and recall per strategy per selectivity, recall per codec per oversampling factor, heap bytes per
component — plus the answer to which graph implementation stands behind the seam of §6.1. The decision
criteria of §6.3 are restated per candidate to reflect that.

Three questions from the external note are adopted as questions the spike must answer, because the
sources on disk confirm they are open (§4.6, §4.8, §9):

1. **Filtering.** At which selectivity does the exact scan beat every graph strategy; at which does the
   predicate-aware walk beat the plain in-walk test; and is there a band where no graph strategy reaches
   the recall criterion at all — which would make the exact scan a required path, not a fallback.
2. **Codec.** How much heap does an 8-bit, a 4-bit and a 1-bit code save at the same *final* recall, what
   oversampling factor each needs, and whether a 1-bit code with the newer asymmetric scoring behaves
   differently from plain binary quantization (they are not the same thing — §4.5, §9.1).
3. **Graph.** Whether a Vamana-pruned base layer beats an HNSW-pruned one at the same memory budget and
   recall target on our data, and whether the hierarchy on top of it earns its cost. jVector exposes both
   knobs in one library (§4.3), so this is a cheap experiment, not a second implementation.

---

## 2. Links to the research and to the neighbouring prototypes

P6 stands apart from the text branch. The research's decision gate (P5 → P1 → P2) measures the text core
and P6 does not enter it; conversely P6's result has no influence on how the text gate turns out. That is
deliberate and it is the reason P6 can run in parallel with P1 and P2 as soon as its entry conditions are
met (§3).

The only two points where the branches meet are the delivery phase F2 (the text × vector hybrid, §5.5 of
the research) and the rank profile of §4.3, into which vector similarity will one day enter as another
lane or as the result of an RRF fusion. Neither is the subject of P6 — P6 merely must not choose a
solution that would make them impossible, and therefore §8.4 names what the spike has to verify about
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
set weights** (`contentBoost` against `semanticBoost`), i.e. exactly the calibration RRF avoids (§8.4).
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
**performed and successful trial upgrade**, not of the state of the `dev` branch. Verified directly, and
**[2026-09-07]** re-verified unchanged at `ee2801c8e`:

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
`VectorizationProvider` and the condition for the SIMD path is `Runtime.version().feature() >= 20`
(**[2026-09-07]** verified: `jvector-base/src/main/java/io/github/jbellis/jvector/vector/VectorizationProvider.java:102-103`);
it moreover checks the presence of the module `jdk.incubator.vector` in the boot layer (`:179-189`). On
JDK 17 both conditions fail and jVector silently falls back to the scalar `DefaultVectorizationProvider`
with a warning at `:174`. The criterion "latency < 10 ms per 1M vectors" is then measured on code several
times slower than what would run in production — the result is unusable in both directions, because
neither success nor failure says anything about the target state.

Second, the same version decides which layer of the multi-release jar the JVM sees at all (§4.2), i.e.
also whether the vectors are read through `MemorySegment` or through the older `MappedByteBuffer`. A spike
that is to decide about the mmap integration must not have this variable fixed by accident.
**[2026-09-07]** The checkout sharpens this: the `MemorySegment` reader and the native provider live in the
**JDK 22** layer, not 20 (§4.4). JDK 21 is the entry condition for SIMD; JDK 22 is a second step that
unlocks the off-heap vector type and the FFM reader, and the spike should record numbers for both if a 22
toolchain is at hand, because the difference is exactly the "raw vectors on the heap" question of §1.

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
benchmark per §11.4 will therefore run on the scalar `DefaultVectorizationProvider`, print numbers, report
no error — and those numbers will be compared against the criterion "< 10 ms". It is precisely the failure
§3.1 is meant to prevent, reintroduced seven chapters later by a different mechanism. The consequence for
measurement is recorded in §11.4: until the log from the forked JVM confirms the selection of
`PanamaVectorizationProvider`, no measured latency may be believed.

**[2026-09-07]** Two more switches join the list, both verified in the checkout. The provider can be
**forced** with `-Djvector.vectorization_provider=panama` (`VectorizationProvider.java:79-100`), and a forced
provider that fails to load throws instead of falling back — the harness should force it, so that a
misconfigured run dies loudly rather than measuring the scalar path. And the only non-deprecated way to
construct a `GraphIndexBuilder` reads two structural graph parameters from a **process-global, JMX-mutable
singleton** (§4.3); `-Djvector.management.backend=none` disables the JMX backend, and every run's record has
to carry the values actually used.

A practical recommendation following from that for the design: **isolate the vector mathematics into a
single module** with its own descriptor and with a scalar fallback, do not scatter it through
`evita_engine`. Both Lucene and jVector solve the same problem with exactly this pattern and in both cases
it has the same reason — an incubator module must not be a hard condition of startup.

### 3.3 Day zero: what to verify before anything else

**[2026-09-07]** The first version of this section named one fact that could turn the plan around: whether
`jvector-twenty` is compiled with `--enable-preview`, which would preview-lock its classes to JDK 20. **That
is now answered from the source and needs no experiment.** `jvector-twenty/pom.xml:22-26` has the flag
*commented out* in the compiler configuration and active only in surefire's `argLine` (`:35-39`); the
shipped bytecode in `META-INF/versions/20` is not preview-marked and loads on any JDK ≥ 20. Question OP6-2
is closed.

What remains for day zero is smaller and is listed in §10, step 1: read off from the log which provider and
which read backend a JDK 21 JVM selects with and without `--add-modules` (the expected answers are now
known — `PanamaVectorizationProvider` and `MappedChunkReader`, §4.2 and §4.4 — so the experiment confirms
the reading rather than replacing it), and compile a `requires` against the jar from a named module (§4.7).

---

## 4. Facts about the candidates on disk

**[2026-09-07] This chapter is rewritten against the checkouts.** The first version read jVector through
GitHub and Maven Central and marked every such claim **web-only**; those markers are gone, every claim
below carries a `path:line` into `/www/oss/jvector` (`main` at `40e0775f`), `/www/oss/lucene` (`main` at
`13796f80e49`) or `/www/oss/hnsw-sb` (`b09db64b`). Two candidates are new: Lucene's vector packages taken
as a library rather than as an engine (§4.8), and hnsw-sb (§4.9).

### 4.0 What the checkout corrected

The reading from the web was mostly right; where it was wrong, it was wrong in ways that would have sent
the spike after something that does not exist. In order of consequence:

| First version said | The source says | Where |
|---|---|---|
| Interruptibility "unanswered", a verification point | **No cancellation of any kind** — no predicate, deadline, visited limit or interrupt check in the search loop | §4.6 |
| Filtering is "the mechanics §5.4 assumes" | The `Bits` test admits or rejects a *popped* candidate only; expansion is unconditional; **no ACORN-style widening exists**, the string does not occur in the repository | §4.6 |
| Fused PQ requires degree 32 | The degree limit was lifted; 256 clusters and format version ≥ 6 remain | §4.5 |
| `MemorySegmentReader` "under JDK 20+" | It lives in the **JDK 22** layer; on 21 the factory falls through to `MappedChunkReader` after a warning | §4.4 |
| `--enable-preview` may lock the 20 layer | Commented out in the compiler config; test-only | §3.3 |
| JPMS "unverified negative" | **Verified absent**: no `module-info.java`, no `Automatic-Module-Name`, no manifest customization beyond `Multi-Release: true` | §4.7 |
| Fallback order Panama → Native → Default | Native is tried first *only if opted in* by a system property, then Panama, then Default | §4.2 |
| "BQ provides the ~32× ratio" | True, but the library's own documentation says BQ "loses too much accuracy with smaller embeddings" and is "primarily suitable for ada002" | §4.5 |
| Quantization: PQ, BQ, Fused PQ, NVQ | Confirmed — and **no scalar (int8) quantization and no RaBitQ-style code exist**; the codec matrix of §9 cannot be run on jVector alone | §4.5 |
| `usePruning` deprecated and does nothing | Confirmed, and it is a hard no-op that ignores its argument; `UPGRADING.md:27-29` still documents it as functional | §4.6 |

### 4.1 Maturity and version — a second, separate fork

**jVector 4.0 still has no final release.** `git tag -l '4.0*'` lists six betas, `rc.1`–`rc.9` and an
`rc.8-hf1`; the newest is `4.0.0-rc.9` and `main` carries `4.0.1-SNAPSHOT` (`pom.xml:56`), twelve commits
past the tag. Of those twelve, three matter here: `6ff7c882` (#703) adds the JMX `GraphIndexBuilderConfig`
(§4.3), `1683ae76` (#668) ports the Panama SIMD kernels to C++ via Google Highway (§4.2), and `711afea5`
(#691) reworks compaction. The `CHANGELOG.md` has never had a non-prerelease header (`:7` is `rc.9`).

The stable line that can be taken from Maven Central as a released version is **3.0.x**. The choice
between it and one of the 4.0 release candidates is therefore **a second fork, independent of the first
one**, and has to be decided consciously:

- **3.0.x** is released and stable, but loses the hierarchical graph, NVQ and Fused PQ in its current form.
- **4.0.0-rc.x** has all three, but it is a release candidate that has been in RC state suspiciously long
  — the betas date from early 2026 and no final has arrived. For a database's production dependency that
  is a risk not paid for by performance but by a willingness to live with the format or the API still
  moving; `UPGRADING.md` is already stale against the code in one place (§4.6), which is a symptom.

Recommendation: **the spike measures on the checkout (`4.0.0-rc.9` + 12)**, because it decides
architecture and the target capabilities need to be seen; but into the mini-gate's decision it should be
written that a *production* deployment either waits for the final 4.0, or is done on 3.0.x with an
awareness of what is missing. That difference has to be explicit in the ADR, otherwise somebody overlooks
it later.

### 4.2 The artifact's structure and runtime dispatch

jVector is published as a **multi-release jar** under the coordinates `io.github.jbellis:jvector`
(`jvector-multirelease/pom.xml:12`), assembled by `maven-assembly-plugin` from the descriptor
`jvector-multirelease/src/assembly/mrjar.xml`, with the single manifest entry `Multi-Release: true`
(`jvector-multirelease/pom.xml:60`). The layering is:

| Layer in the jar | Source module | Compilation | Content |
|---|---|---|---|
| root | `jvector-base` | `release 11` (`pom.xml:81-88`) | the graph, disk, quantization, scalar mathematics — 138 files, ~26,100 lines |
| `META-INF/versions/20` | `jvector-twenty` | `release 20` + the vector incubator (`jvector-twenty/pom.xml:21-26`) | Panama SIMD — **two files**, `PanamaVectorUtilSupport` (1,549 lines) and its provider |
| `META-INF/versions/22` | `jvector-native` | `release 22` (`jvector-native/pom.xml:25`) | FFM bindings to a Highway-built `libjvector.so`, `MemorySegmentVectorFloat`, `MemorySegmentReader` |

The choice of implementation is made once, at class load, in a static holder
(`VectorizationProvider.java:216-221`), so it is **process-wide and immutable**. The order is not what the
first version assumed. A forced choice via `-Djvector.vectorization_provider={default|panama|native}` is
honoured first and throws on failure (`:79-100`). Otherwise, on a JDK ≥ 20 (`:102-103`), three checks
each fall back to the scalar provider with a warning: the JDK-8301190 locale bug below `20.0.2`
(`:104-110`, `:191-198`), the incubator module's presence in the boot layer (`:111-117`; the warning text
is *"Java vector incubator module is not readable. For optimal vector performance, pass '--add-modules
jdk.incubator.vector' to enable Vector API."*), and a disabled C2 (`:118-121`). Then the **native**
provider is tried, but only when `-Djvector.experimental.enable_native_vectorization=true` is set
(`:125`); it requires x86-64 and a loadable `libjvector.so` (`NativeVectorizationProvider.java:33-40`) and
exists only in the 22 layer, so on JDK 21 `Class.forName` fails and the code falls through to
**Panama** (`:157-172`). It is the same pattern Lucene uses in its own `VectorizationProvider`
(`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/internal/vectorization/VectorizationProvider.java:137-193`),
including an almost identical wording of the warning.

For evitaDB a pleasant conclusion follows: **jVector does not break when the module is missing** — it
merely runs more slowly and says so in the log. A fallback is therefore available for free, which makes
§3.2 easier. Two less pleasant ones follow too. The native layer is **not usable from this checkout**: the
Highway submodule directory `jvector-native/src/main/native/third_party/highway` is empty and no prebuilt
`.so` is checked in, so measuring it means `git submodule update --init`, Meson, Ninja and g++ 11 on a
Linux/amd64 box (`README.md:62-80`). And the `VectorFloat` a JDK 21 run hands the graph is **on-heap
`float[]`** (`ArrayVectorFloat`, `jvector-base/.../vector/ArrayVectorFloat.java:30`); the off-heap
`MemorySegmentVectorFloat` (`jvector-native/.../vector/MemorySegmentVectorFloat.java:31`) comes only with the
native provider. The consequence for the "raw vectors never on the heap" criterion is in §4.4.

### 4.3 Building the graph and incremental maintenance

Algorithmically jVector does not copy HNSW: it combines **the hierarchy from HNSW with the Vamana
algorithm (the core of DiskANN) inside each layer** (`README.md:13-14`, `docs/tutorials/1-intro-tutorial.md:3`)
and builds on non-blocking concurrency — in-progress insertions are tracked in a `ConcurrentSkipListSet`
and considered as neighbour candidates by each other (`GraphIndexBuilder.java:836-838`, `:84`). Level
assignment follows the HNSW sampler with `mL = 1/ln(degree₀)` (`:814-831`) from a fixed `new Random(0)`
(`:549`), so levels are deterministic and build nondeterminism comes only from insertion order.

**The two knobs that make the graph question of §1.3 a one-library experiment** are constructor
parameters, documented verbatim at `GraphIndexBuilder.java:128-131`:

> `alpha` — how aggressive pruning diverse neighbors should be. Set alpha > 1.0 to allow longer edges. If
> alpha = 1.0 then the equivalent of the lowest level of an HNSW graph will be created, which is usually
> not what you want.
> `addHierarchy` — whether we want to add an HNSW-style hierarchy on top of the Vamana index.

So `alpha = 1.0, addHierarchy = true` is an HNSW-shaped graph, `alpha ≈ 1.2, addHierarchy = false` is a
plain Vamana graph, and the other two corners are the hybrids. Per-layer degrees are a list
(`:325-326`; more than one entry forces the hierarchy, `:514-516`). One caveat on the "equivalent of
HNSW" claim: jVector's diversity pruning is `VamanaDiversityProvider` at every `alpha` (`:537`), whereas
Lucene's builder uses the Malkov heuristic — the two are the same rule only at `alpha = 1`, and §10 step 4
therefore also builds a real HNSW with Lucene's builder (§4.8) as the reference point.

**A construction hazard the first version could not see.** Every explicit-parameter constructor is
`@Deprecated` (`:136`–`:342`); the only non-deprecated path is the fluent `builder(...)`, which exposes
`beamWidth`, `neighborOverflow` and `alpha` — and reads `addHierarchy`, `refineFinalGraph` and the
build-time compression type from `GraphIndexBuilderConfig.getInstance()`, a **process-global singleton
mutable over JMX** (`:486-496`, `:556-573`; defaults `addHierarchy = true`, `refineFinalGraph = true`,
`NONE`, per `docs/release notes/4.1.0/703.feature.md`; the design document it cites,
`docs/admin-service-interfaces.md`, is absent from the repository). For a spike that must vary
`addHierarchy` per run, the deprecated constructors are the honest choice; for production the global has to
be pinned and logged, and this belongs in the mini-gate's maturity row (§6.3).

The fundamental difference against Lucene, and probably the most important property of the whole library
for evitaDB, is **incremental maintenance** — but its shape is more constrained than "add and delete at
runtime". Verified:

| Operation | Anchor | Thread-safe with concurrent inserts? |
|---|---|---|
| `addGraphNode(int, VectorFloat<?>)` | `GraphIndexBuilder.java:844` | yes (`:836-838`) |
| `markNodeDeleted(int)` | `:934` | yes (`UPGRADING.md:81`) |
| `removeDeletedNodes()` | `:945` | **no** — "Not threadsafe with respect to other modifications; the `synchronized` flag only prevents concurrent calls to this method" (`:940-941`) |
| `cleanup()` | `:728` | **no** — "should not be called during concurrent modifications to the graph" (`:726`); "Must be called before writing to disk" (`:724`) |

Deletion is therefore **two-phase**: a thread-safe tombstone, then a repair pass (FreshDiskANN §4.2 edge
patching, `:955-958`) that needs a quiescent window with respect to writers. Two more constraints bind the
window. A live `View` "represents a point of consistency in the graph, and in-use Views prevent the
removal of marked-deleted nodes from graphs that are being concurrently modified" — so for in-memory
graphs the guidance is to create a **new View per search** (`ImmutableGraphIndex.java:49-59`); a reader
pool that keeps views open would block reclamation. And an on-disk graph "may not contain 'holes' in the
ordinal sequence", so persisting after deletes requires an ordinal remap (`UPGRADING.md:132-135`) — which
meets the ordinal mapping of §7.1 head-on and is an argument for evitaDB owning that mapping. Finally, the
builder "allocates scratch space and copies of the RandomAccessVectorValues for each thread that calls
`addGraphNode`", retained for the builder's lifetime, and its javadoc says outright that "spawning a new
Thread per call is not advisable. This includes virtual threads" (`GraphIndexBuilder.java:58-61`) — a
direct constraint on how the write path may drive it.

Lucene cannot do this by construction and it is worth seeing why, because that is exactly the difference an
in-house implementation would have to catch up with. In Lucene the graph does grow incrementally *during a
segment's write* — `HnswGraphBuilder.addGraphNode` is public
(`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/util/hnsw/HnswGraphBuilder.java:382`) — but
once `getCompletedGraph()` is called, "no further updates to the graph are accepted"
(`.../util/hnsw/HnswBuilder.java:65`), and **there is no deletion at all**: the strings `remove`, `delete`
and `markDeleted` do not occur in `HnswGraphBuilder`, `HnswBuilder` or `OnHeapHnswGraph`. A change of a
vector is handled by deleting it and inserting a new one into another segment, and merging segments is done
through `IncrementalHnswGraphMerger`, which initializes itself from the largest existing graph — but only
if it does not have more than 40 % deleted documents in it (`DELETE_PCT_THRESHOLD = 40`).

evitaDB's model is, however, a transactional database without segments. Either a library with incremental
maintenance is adopted, or a liveness bitmap plus a periodic rebuild has to be designed around a library
that has none (§4.8). That is a strong argument for jVector and it should have a corresponding weight in
the mini-gate — with the two-phase shape above written next to it, not the slogan.

### 4.4 Reading from disk: what mmap actually is

`ReaderSupplierFactory.open` (`jvector-base/src/main/java/io/github/jbellis/jvector/disk/ReaderSupplierFactory.java:45-69`)
tries three read backends in this order:

1. `MemorySegmentReader$Supplier` through reflection, with the comment "available under JDK 20+" (`:47`)
   — **the comment is wrong for the packaging**: the class lives in `jvector-native`, compiled at
   `release 22` and packed under `META-INF/versions/22`, so on JDK 21 the `Class.forName` fails and the
   factory logs a warning and moves on;
2. `MMapReader$Supplier`, which lives in `jvector-examples` (`:31`), needs `com.indeed:util-mmap`
   (`jvector-examples/pom.xml:72-82`) and is Linux-only; unusable for us;
3. `MappedChunkReader.Supplier` as the last resort (`:67`) — `FileChannel.map` in chunks of
   `Integer.MAX_VALUE` bytes (`MappedChunkReader.java:37`), one shared channel per supplier, a new
   reader per `get()` (`:65-100`), so it has no 2 GB cap and no `Unsafe`.

`SimpleMappedReader` — the one with the explicit 2 GB refusal (`SimpleMappedReader.java:71-80`) and the
`Unsafe.invokeCleaner` unmapping (`:87-97`) — **is never selected by the factory**; it is a sample. Two
first-version worries thereby dissolve: the 2 GB cap and the `jdk.unsupported` dependency apply only to a
class we would not use. What replaces them is smaller: `MappedChunkReader` remaps on chunk boundaries and
has a concurrency test (`jvector-tests/.../disk/TestMappedChunkReaderConcurrency.java`), and the file must
still not be deleted under a live mapping — §7.3 holds.

`MemorySegmentReader` is the recommended reader (`MemorySegmentReader.java:38-44`): `Arena.ofShared()`,
one mapping of the whole file, `posix_madvise(MADV_RANDOM)` through an FFM downcall (`:148-179`), a
best-effort `prefetch(offset, length)` that streams ranges through a second descriptor to populate the
page cache (`:181-212`, contract on `ReaderSupplier.java:9-31`), and no size cap. **It needs JDK 22.** On
21 the research's expectation ("today `MappedByteBuffer`, from JDK 22 the final FFM API", §5.3) is
confirmed exactly, only the class name differs from the first version's guess.

**The consequence for the "raw vectors never on the heap" criterion is a division of labour, not a
property of the library.** jVector reads its own on-disk graph and inline or separated vectors through
these readers, off the heap. But during *build* the vectors reach the builder through a
`RandomAccessVectorValues`, and the library's in-memory implementations hand out on-heap `float[]`; the
only disk-backed one, `MMapRandomAccessVectorValues`, sits in `jvector-examples`
(`jvector-examples/.../util/MMapRandomAccessVectorValues.java:32`) and the tutorial warns about
`isValueShared()` semantics for exactly this case (`docs/legacy/jvector-step-by-step.md:45`). So on JDK 21
the criterion is achievable, but **evitaDB writes the mapped `RandomAccessVectorValues`** over its own
vector file (§7.2); the spike verifies that with the JOL measurement of §11.5, not by assumption. On JDK 22
with the native provider the vector type itself becomes a `MemorySegment`, and the same measurement says
what that buys.

### 4.5 Quantization: what jVector has, and what it does not

The research §5.2 speaks of "RaBitQ/BBQ-class quantization (~32×)". That naming does not exist in jVector
and it has to be corrected, because otherwise the prototype will be looking for something that is not
there. Verified in `jvector-base/src/main/java/io/github/jbellis/jvector/quantization/`:

| Scheme | Class | Bytes per 768-d vector | Notes |
|---|---|---|---|
| **PQ** | `ProductQuantization` (`:56`), `PQVectors` | `M` bytes with 256 clusters per subspace (`:62`); codebooks 256 × 768 × 4 B ≈ 786 KB shared | anisotropic weighting optional (`:101-102`, `:445-446`); build-time scoring over PQ via `BuildScoreProvider.pqBuildScoreProvider` (`similarity/BuildScoreProvider.java:170`) |
| **BQ** | `BinaryQuantization` (`:35`), `BQVectors` | 96 B (`long[12]`), Hamming distance (`:31-34`) | symmetric 1-bit, no correction terms; the data-dependent factories are deprecated and now ignore the data (`:44-58`); centering was removed as harmful (`UPGRADING.md:125-126`) |
| **NVQ** | `NVQuantization` (`:47`), `NVQVectors` | 768 B at 8 bits, 384 B at 4 bits (`BitsPerDimension`) | a per-vector fitted non-linear quantizer positioned as the **second-pass** representation (`README.md:30-32`) |
| **Fused PQ** | `graph/disk/feature/FusedPQ.java` | PQ codes of a node's neighbours written inline into its adjacency record | requires exactly 256 clusters (`:56-63`), format version ≥ 6 (`AbstractGraphIndexWriter.java:98`); the degree-32 limit is **lifted** (`UPGRADING.md:17`); PQ only, not BQ |

**Absent, verified by searching the base sources, `docs/`, `CHANGELOG.md`, `UPGRADING.md` and
`README.md` for `rabitq`, `bbq`, `scalar quantiz`, `int8` and `sq8`: zero hits.** jVector has no scalar
quantization and nothing of the RaBitQ family. Its own documentation places BQ narrowly: "primarily
suitable for ada002 embedding vectors and loses too much accuracy with smaller embeddings"
(`UPGRADING.md:138`), "BQ is generally less useful than PQ since it takes such a large toll on search
accuracy" (`docs/legacy/jvector-step-by-step.md:135`).

The names RaBitQ and BBQ belong to a different world. "BBQ" is a marketing name from Elasticsearch; the
Lucene implementation is `OptimizedScalarQuantizer`
(`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/util/quantization/OptimizedScalarQuantizer.java:42`),
which cites RaBitQ directly in its code, and in the current `main` it serves **every** bit width through one
format: `Lucene104ScalarQuantizedVectorsFormat` with the encodings `UNSIGNED_BYTE` (8 bits), `SEVEN_BIT`,
`PACKED_NIBBLE` (4 bits), `DIBIT_QUERY_NIBBLE` (2-bit documents, 4-bit queries) and
`SINGLE_BIT_QUERY_NIBBLE` (1-bit documents, 4-bit queries)
(`.../util/quantization/QuantizedByteVectorValues.java:35-64`). The older dedicated binary format moved to
`backward-codecs` (`lucene102`). The asymmetry — a 1-bit document code scored against a 4-bit query code
with per-vector correction terms — is precisely what distinguishes a RaBitQ-style code from jVector's
symmetric Hamming BQ, and it is the reason the two must be measured as different things, not as "binary".

What follows for P6's criteria: **the compression ratio of ~32× in jVector is provided by BQ**, so the
estimate of §5.2 (~90–100 MB of codes per 1M × 768) stays valid for that scheme. But **BQ with rescoring is
not RaBitQ with rescoring**, and the library's own authors expect BQ to fall short on 768-dimensional
embeddings. The criterion **recall@10 ≥ 0.95 is therefore the one most threatened by that substitution**,
and the spike gives it the codec matrix of §9 rather than a single measurement: BQ, PQ and NVQ from jVector,
and the 8-, 4-, 2- and 1-bit `OptimizedScalarQuantizer` encodings from Lucene, each with oversampling and
exact reranking. Whether a Lucene-quantized code can drive jVector's traversal is a seam question, answered
in §4.6.

Verification over the Elasticsearch checkout (main, commit `9a100e2d0e41`, 2026-08-13) adds concrete
numbers to it that the spike need not seek from scratch. Elasticsearch switches binary quantization on by
default from `BBQ_DIMS_DEFAULT_THRESHOLD = 384` dimensions with an absolute lower bound of
`BBQ_MIN_DIMS = 64` (`DenseVectorFieldMapper.java:295` and `:159`), and it **adds oversampling by a factor
of 3.0 and rescoring at full precision by default** (`RescoreVector`, `DEFAULT_OVERSAMPLE = 3.0F`,
`DenseVectorFieldMapper.java:294`) — a default introduced only subsequently, by a separate index version
flag, because the ordering from the quantized space alone turned out insufficient in practice. The
criterion of §1 ("recall@10 ≥ 0.95, measured with rescoring") is therefore correctly set up and the factor
3.0 is one point of the oversampling axis of §9.2, not its only value.

### 4.6 Filtered ANN, interruptibility and the seam

`GraphSearcher` (`jvector-base/src/main/java/io/github/jbellis/jvector/graph/GraphSearcher.java`, 582
lines) has three instance overloads of `search`, the widest of which is `@Experimental`
(`:221-228`):

```java
public SearchResult search(SearchScoreProvider scoreProvider, int topK, int rerankK,
                           float threshold, float rerankFloor, Bits acceptOrds)
```

The filter is applied **inline during the graph walk** — but the checkout shows exactly *where*, and it is
narrower than §5.4 of the research assumes. The main loop (`:420-451`) pops the best candidate and tests
`acceptOrdsThisLayer.get(topCandidateNode) && topCandidateScore >= threshold` (`:429`) **to decide whether
it may enter the result set**; the candidate's neighbours are then scored and pushed **regardless** of that
test (`:438-450`). Upper layers are walked with `Bits.ALL` (`:263-282`), which is normal. So the `Bits`
parameter is an acceptance filter on results, not a pruning of the walk, and nothing in the library widens
the neighbourhood when most neighbours are rejected: `rg -in acorn` over the whole repository returns
nothing, and the only "filtered" strings in the graph package are in comments saying that filtered
*pruning* was disabled. The javadoc's warning stands verbatim (`:215-218`): *"It is caller's
responsibility to ensure that there are enough acceptable nodes that we don't search the entire graph
trying to satisfy topK."* In other words **jVector has no brute-force fallback and no predicate-aware
walk, and will not have either** — it degrades into a walk of the whole graph and it is up to the caller
not to let that happen. The planner of §8 is therefore owned by evitaDB and is not optional.

**Interruptibility is answered, negatively.** The loop exits only when the candidate queue empties or
`stopSearch` (`:355-369`) sees `rerankK` results better than the best remaining candidate; there is no
predicate, no deadline, no visited limit and no `Thread.interrupted()` check anywhere on the read path
(searched `jvector-base/src/main/java` for `interrupt|cancel|deadline|timeout|visitedLimit|maxVisit`; every
hit is in compaction, the writer or a subprocess probe). A cancelled or timed-out evitaDB query would run to
completion and release its thread and memory afterwards. Verification over the OpenSearch checkout (main,
commit `36edc05ac84`, 2026-08-12) shows what the alternative looks like: its `ContextIndexSearcher`
implements Lucene's `QueryTimeout` because components such as `TimeLimitingKnnCollectorManager` enforce a
limit by that route (`server/src/main/java/org/opensearch/search/internal/ContextIndexSearcher.java:163`,
`:623`), and Lucene's own `HnswGraphSearcher` checks `collector.earlyTerminated()` on every iteration of
its loop (`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/util/hnsw/HnswGraphSearcher.java:307`,
also `:247`, `:294`, `:332`). That is the cooperative check a database needs, and jVector does not have it.
The mini-gate row "interruptibility" in §6.3 is thereby decided against jVector's *searcher* — which is
not the same as against jVector, because of what follows.

**The seam is real, and it is the finding that reshapes the plan.** Three public contracts make it
possible to keep jVector's graph and replace its searcher and its codec:

- `ImmutableGraphIndex.View` (`graph/ImmutableGraphIndex.java:169`) exposes
  `getNeighborsIterator(int level, int node)` (`:174`) and
  `processNeighbors(level, node, scoreFunction, visited, neighborProcessor)` (`:180`), plus the live-node
  bits (`OnHeapGraphIndex.java:496`). A searcher written by evitaDB over this View can do everything
  `GraphSearcher` does — and also what it does not: skip expansion of rejected nodes, widen to the second
  hop when the acceptance rate of a neighbourhood is low (ACORN-1, §8.1), check a cancellation flag per
  iteration, and stop at a visited budget.
- `ScoreFunction.ApproximateScoreFunction` and `ScoreFunction.ExactScoreFunction`
  (`graph/similarity/ScoreFunction.java:75`, `:69`; the whole contract is `float similarityTo(int node2)`,
  `:41`) are what a `SearchScoreProvider` carries. A codec that is not jVector's — Lucene's
  `OptimizedScalarQuantizer` output, or our own — plugs in as an approximate scorer with no change to the
  graph; the graph never sees vector bytes.
- `Bits` (`util/Bits.java:30`, `boolean get(int index)` at `:41`) is a one-method interface;
  `Bits.intersectionOf` short-circuits on `ALL`/`NONE` (`:49-64`), so an unfiltered search pays nothing.
  The adapter to evitaDB's `Bitmap#contains(int)` (`evita_engine/.../index/bitmap/Bitmap.java:110`) through
  the ordinal mapping of §7.1 is trivial.

`SearchResult` carries `visitedCount`, `expandedCount`, `expandedCountL0`, `rerankedCount` and
`worstApproximateScoreInTopK` (`graph/SearchResult.java:22-40`) — the metrics §11 needs, with one reading
note: `visitedCount` is incremented in the neighbour processor (`GraphSearcher.java:448`), so it counts
scored edges, not distinct nodes. Rescoring is built in (`rerankK ≥ topK` enforced at `:233-235`), and
`resume(additionalK, rerankK)` (`:532-547`, experimental) continues a search for more results — which is
the "continuable retrieval" the external note asks for (its §15, after VBASE) and which §8.4 needs for RRF
depth; the threshold path even implements VBase's relaxed-monotonicity stop (`graph/ScoreTracker.java`,
`TwoPhaseTracker`). `usePruning` is a deprecated hard no-op that assigns `false` regardless of its argument
(`:128-139`); `UPGRADING.md:27-29` still documents it as functional.

### 4.7 Dependencies, licence and JPMS

The licence is **Apache 2.0**, which is unproblematic for evitaDB.

The transitive dependencies are four, declared once in the root `pom.xml:185-207` and inherited by every
module: `org.apache.commons:commons-math3:3.6.1`, `org.agrona:agrona:1.20.0`,
`org.slf4j:slf4j-api:2.0.16` and `org.yaml:snakeyaml:2.4`. Verified against the base sources: **agrona
is load-bearing** (eleven files, including `GraphSearcher`, `OnHeapGraphIndex`, `NodeQueue`,
`ProductQuantization`); **commons-math3 is one import** (`graph/ScoreTracker.java:21`, `StatUtils`), and
the exclusions in `jvector-twenty/pom.xml:51-56` and `jvector-native/pom.xml:176-181` are ineffective
because the root declares it directly; **snakeyaml is not used anywhere in `jvector-base`** — it serves the
examples' YAML configs and is dead weight on a consumer's classpath. From evitaDB's point of view
`slf4j-api` is already present and `snakeyaml` too, in version 2.6 (`pom.xml:131`); the new ones are
`commons-math3` and `agrona`, neither a framework. A minor smell: `jvector-native/pom.xml:198-205` declares
JMH at compile scope.

**JPMS is verified absent, and it is a workaround rather than a block.** There is no `module-info.java`
anywhere, no `Automatic-Module-Name` in any POM, and no manifest file in the tree; the only manifest entry
is `Multi-Release: true`. The jar on the module path is therefore an automatic module named from the file
(`jvector`), which a named evitaDB module can `requires` but which is unstable across artifact renames and
**disqualifying for `jlink`**. Lucene's core, by contrast, is a named module and exports exactly the
packages the seam needs (§4.8). If jVector passes the gate, the production remedy is one of: a shaded copy
with our own descriptor, an upstream pull request adding `Automatic-Module-Name` (a one-line change with a
willing maintainer), or living with the automatic module — the choice belongs after the gate, and the
spike only has to prove the `requires` compiles and the multi-release layer resolves under it (§10,
step 1).

### 4.8 [2026-09-07] Lucene's vector packages as a library

The research rejected Lucene as an *engine* (§3) and explicitly permits it as an ordinary Maven dependency
for analyzers. P5 has already taken that path. The same permission covers its vector packages, and the
checkout shows they are built to be taken that way: `org.apache.lucene.util.hnsw`,
`org.apache.lucene.util.quantization` and `org.apache.lucene.search.knn` are all **exported** from the
named module `org.apache.lucene.core` (`/www/oss/lucene/lucene/core/src/java/module-info.java:50`, `:75`,
`:41`), and `lucene-core` has no third-party runtime dependencies. What they offer, against the four
questions of §1.3:

- **Graph.** `HnswGraphBuilder` (745 lines) with the Malkov diversity heuristic, `OnHeapHnswGraph`
  (`NeighborArray[][]`, `.../util/hnsw/OnHeapHnswGraph.java:47`), a `ramBytesUsed()` (`:312`), and a
  public `addGraphNode` (`HnswGraphBuilder.java:382`). Insert-only: no deletion (§4.3).
- **Filtered walk.** `FilteredHnswGraphSearcher` (`.../util/hnsw/FilteredHnswGraphSearcher.java:32-40`),
  "inspired by the ACORN-1 algorithm", which explores the extended neighbourhood only when a
  neighbourhood's filtered share falls below `EXPANDED_EXPLORATION_LAMBDA = 0.10` (`:46`) and by an
  amount derived from the filter ratio (`:55-63`, `:166`). It is selected by `KnnSearchStrategy.Hnsw`
  when fewer than `filteredSearchThreshold` percent of the graph pass — default 60
  (`.../search/knn/KnnSearchStrategy.java:26`, `:80-83`; the selection is in
  `HnswGraphSearcher.java:143-145`). It shipped in Lucene 10.2 as opt-in ("improves runtime of filtered
  HNSW searches as much as 5x", `lucene/CHANGES.txt:1527`) and is the default strategy in `main`.
- **Codecs.** `OptimizedScalarQuantizer` with the five encodings of §4.5, and the older `ScalarQuantizer`
  (int8 with configurable bits, `.../util/quantization/ScalarQuantizer.java:74-101`). Both are plain
  classes over `float[]`; the on-disk vector formats around them are the engine and are not needed.
- **Exact fallbacks.** Two thresholds a planner can borrow as reference points (§8.1):
  `AbstractKnnVectorQuery` goes straight to exact search when the filter's cost is at most the expected
  per-leaf top-K (`.../search/AbstractKnnVectorQuery.java:282-285`), and the reader skips the graph when
  `expectedVisitedNodes(k, graphSize) = ln(graphSize) · k` is not below the filtered count
  (`.../codecs/lucene99/Lucene99HnswVectorsReader.java:369`,
  `.../util/hnsw/HnswGraphSearcher.java:56-58`).
- **Cancellation.** The searcher checks `collector.earlyTerminated()` every iteration (§4.6);
  `TimeLimitingKnnCollectorManager` exists in core.

What it does not offer: deletion, a hierarchy-free (Vamana) graph, product quantization, an off-heap graph
of its own outside the codec machinery, and `resume`. It is `10.x` on Maven Central with a released line
and a stable format — the maturity row of §6.3 reads the opposite way to jVector's.

The size of the borrowable surface, for the in-house estimate of §6.2: `util/hnsw` 5,263 lines in 28
files, `util/quantization` 1,720 lines, `VectorUtil` plus the vectorization package 1,983 lines.

### 4.9 [2026-09-07] hnsw-sb: assessed and set aside

`/www/oss/hnsw-sb` is a Java 21 HNSW library, ~3,276 lines of main code in one flat package, no runtime
dependencies, SIMD cosine through `jdk.incubator.vector`, a hierarchy with the Malkov heuristic, real
deletes with local repair, and text persistence. It was assessed against the four properties the seam of
§6.1 needs. It has none of them, and four further facts close the question:

| Property | Finding | Anchor |
|---|---|---|
| Filtered ANN in the walk | **Absent.** `searchFiltered` is an exhaustive float64 scan over an intersection of equality postings; no search path takes a predicate or bitset | `HnswIndex.java:166-216`, `Snapshot.java:78-157` |
| Quantization | **Absent, deliberately** — a removed construction codec is rejected on load; "PQ4 remains removed" | `IndexIO.java:68-69`, `docs/RESEARCH.md:35` |
| Ordinal id space | **Strings.** Every id passes through `toString()`; internal ordinals are package-private | `HnswIndex.java:415-418`, `Neighbor.java:3` |
| JPMS | No descriptor; `Automatic-Module-Name` only | `pom.xml:41` |
| Concurrency under writes | A fair `ReentrantReadWriteLock` excludes all readers for the duration of a write, and **every effective mutation rebuilds the whole read snapshot** — a fresh `float[N·d]` copy plus per-level CSR arrays; a single `add` on 1M × 768 copies 3 GB | `HnswIndex.java:31`, `Graph.java:304-307`, `Snapshot.java:16-54` |
| Memory | Three copies of every vector in the float32 cosine configuration (`double[]` node vector, `float[]` build vector, flat snapshot): ~12 KB per 768-d vector; everything on-heap | `Node.java:5-10`, `Snapshot.java:21-32` |
| Persistence | Text EDN, ~16 KB per 768-d vector, loaded through `Files.readString` into one `String` — a hard ceiling near 130,000 vectors; adjacency stored as id strings | `IndexIO.java:30-49`, `:166-171` |
| Scale evidence | Largest measured corpus **31,126** vectors; the authors disclaim any million-vector, memory or filtered-search result | `examples/java_comparison/README.md:113-116` |
| Provenance | One commit, dated the day of the assessment, one author, **no licence file or header anywhere**; a port of a Clojure library whose format name it still writes | `git log`, `docs/PORT.md:1-6`, `IndexIO.java:32-33` |

It is not a candidate for the seam and it is not a reference implementation either — the absence of a
licence means nothing may be copied from it. It has one use, and a real one: as a **size data point** for
the in-house estimate. Three and a bit thousand lines buy a hierarchy, a diversity heuristic, SIMD cosine,
deletes with repair and persistence — and *none* of filtered traversal, quantization, an off-heap
representation, a versioned binary format or a cancellation seam, which is where the other ten thousand
lines of §6.2's estimate go. Its benchmark tables (jelmerk, jVector rc.9, Lucene 10.5.1 on a 31k corpus,
`README.md:139-148`) are honest about their own limits and are not evidence at our scale.

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
— **[2026-09-07]** now at `:383`), which is the minimum of a monotonically growing floor of active readers
and of explicitly pinned catalog versions (`catalogVersionPinned` — now at `:325`). The deletion itself
then runs under an exclusive directory lock in `purgeObsoleteFiles`, complemented by
`reclaimUnreachableFiles`, which cleans up files no retained bootstrap record can reach any more.

Time travel is a configuration option (`StorageOptions.java:145-147`, among others `timeTravelEnabled` and
`minimalActiveRecordShare`); with time travel off the old files are deleted right after compaction. The
decision about compaction itself is in `DefaultCatalogPersistenceService.java:1199-1205`.

### 5.3 mmap is not in the repository at all

Searching the whole tree for `MappedByteBuffer`, `FileChannel.map`, `MemorySegment`, `Arena` and
`java.lang.foreign` returns **zero results** (outside `target/`). Not even the vendored
`evita_roaring_bitmap` has the upstream package `buffer/`, so there is no `ImmutableRoaringBitmap` over
mapped memory there. **[2026-09-07]** Re-verified at `ee2801c8e`: still zero, and likewise zero for
`jvector`, `hnsw` and `FloatVector`.

Today reading is done through a `RandomAccessFile` wrapped in a `RandomAccessFileInputStream` and further
into an `ObservableInput` (`ReadOnlyFileHandle.java:94-96`); `FileChannel` is used **exclusively** for
fsync, never for `map()`. There is an off-heap branch for in-progress transactions (`OffHeapMemoryManager`),
but that is `ByteBuffer.allocateDirect`, i.e. something else.

It means **P6 introduces the first mmap into evitaDB at all**. That is architecturally exactly what §5.3
wants, but it is good to know that no precedent is being built on — none exists. Two things are connected
with it that the spike has to watch: the behaviour on unmapping (on JDK 21 a `MappedByteBuffer` is released
by the garbage collector or by `Unsafe.invokeCleaner`; on JDK 22 an `Arena` is closed explicitly, §4.4) and
the fact that a mapped file must not be deleted before the mapping is released — which is precisely why
the retention floor described in §5.2 is not optional.

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
passes and **`float[]` does not**. **[2026-09-07]** All four anchors re-verified unchanged at `ee2801c8e`.

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
automatic-module finding about jVector (§4.7) and the named-module fact about Lucene (§4.8) so important.

---

## 6. The spike's design

### 6.1 [2026-09-07] The shape: a retrieval subsystem with a planner

The first version's design was three *paths* — jVector standalone, jVector integrated, an in-house HNSW —
with the library choice as the gate. That is kept in §6.2, but underneath it now sits a shape that is the
same whichever library wins, because §4.6 and §4.8 show the libraries fill *slots* in it rather than
replacing it. Five contracts, each deliberately thin, each with a reason it must be separate:

| Contract | Shape | Why separate |
|---|---|---|
| **Candidate set** | `contains(ord)`, `cardinality()`, iteration over members | it is evitaDB's `Bitmap` behind the ordinal map (§7.1, §8.3); every strategy reads it, and the planner reads its cardinality *exactly* |
| **Vector store** | dimensions, encoding, random access to a vector's bytes by ordinal, for the raw block and for each code block | the raw block is mmapped and off-heap (§7.2); code blocks may be heap or mapped; a codec change must not touch the graph |
| **Scorer** | `similarityTo(ord)` for a prepared query, one implementation per encoding, plus an exact one over the raw block | this is the seam a foreign codec plugs into — jVector's `ScoreFunction` (§4.6) and Lucene's scorers are both already this shape |
| **Graph** | node ordinals, per-level neighbour iteration, live-node bits, insert and tombstone | jVector's `View` and Lucene's `HnswGraph` are both already this shape; the graph never sees vector bytes |
| **Search strategy** | one of: exact scan over the candidate set; graph walk with post-filter and oversampling; graph walk with in-walk acceptance; predicate-aware walk with second-hop widening; each with a cancellation check and a visited budget | the planner chooses among them per query (§8); a strategy that lives inside a library's searcher cannot be given a cancellation check or a budget (§4.6) |

The planner sits above the strategies, reads the candidate set's cardinality and the index size, and picks
by measured thresholds (§8.2). Above the planner, the oversampled candidates are reranked by the exact
scorer and the top-K is returned with a *ranking*, not merely a score (§8.4).

Two things this shape must permit without implementing them now (§1.2): a partition-assisted secondary
structure for pathologically selective filters (the Curator family, §8.1) — it would be a sixth strategy
plus a sidecar on the vector store, and nothing above forbids it; and a chunk-per-entity unit (OP6-12) —
it is an ordinal-mapping concern and lives entirely in §7.1.

### 6.2 Candidates for the graph component and a recommendation

**Path A — jVector standalone, outside evitaDB.** A harness as an isolated module or a side project that
builds an index over a dataset, measures recall and latency and does not touch evitaDB's storage at all. It
answers both criteria of §1 and the question of quantization quickly and cheaply. It answers nothing
integrational. **[2026-09-07]** Its scope widens by what §4.6 found: the harness writes its own searcher
over jVector's `View` for the filtered strategies and its own scorer for foreign codecs, because the
library's searcher cannot host either. That is the seam of §6.1 being built, not extra work beside it.

**Path L — Lucene's vector packages as a library** (**[2026-09-07]**, new). The same harness over
`HnswGraphBuilder` / `OnHeapHnswGraph`, `FilteredHnswGraphSearcher` and `OptimizedScalarQuantizer`
(§4.8). It gives the true-HNSW reference for the graph question, the only shipped predicate-aware
searcher, the full codec ladder, and a cooperative cancellation check — and no deletion. It is cheap
because the harness is engine-neutral by construction (§11.1); what it costs is the maintenance model it
forces (a liveness bitmap intersected into the acceptance bits, plus a rebuild whose cadence the spike
has to measure — OP6-14).

**Path B — the winner integrated into evitaDB's storage.** Vector files as immutable append-only artifacts
beside the catalog, read through mmap, cleaned up through `removeFileWhenNotUsed` (§7). It answers the
integration risks, but is an order of magnitude more expensive and there is no point starting it before
paths A and L show the numbers.

**Path C — an in-house implementation.** The extent can be estimated from three sources now, not one.
Lucene's `util.hnsw` is 5,263 lines in 28 files, its quantization 1,720, its distance kernels and
vectorization ~2,000 (§4.8). jVector's base is ~26,100 lines and its whole Panama layer is one 1,549-line
class (§4.2). hnsw-sb shows the floor: ~3,300 lines for a bare HNSW with deletes and SIMD cosine and none
of the properties the seam needs (§4.9). An in-house implementation that has them all — graph, one
predicate-aware searcher, exact scan, two codecs, an on-disk format, deletes with repair — is realistically
**8 to 10 thousand lines of production code** plus, at Lucene's test ratio of ~0.7×, another 5 to 7
thousand of tests. **In total 13 to 17 thousand lines** — and that is only the writing; the empirical
tuning of recall and latency, i.e. the part Lucene and jVector have behind them after years, is not in
that number at all.

**hnsw-sb is not a path** (§4.9).

**Recommendation: the spike runs A and L over one harness, continues into B with whichever the gate
picks, and C is opened only if both fail.** The reasoning has changed from the first version. A and L are
not competitors for the same slot so much as complementary evidence: A supplies the graph family
comparison and the incremental maintenance; L supplies the predicate-aware walk, the codec ladder and the
cancellation pattern that the planner will need *regardless of which graph wins*. The most likely gate
outcome is therefore not "A or L" but "A's graph and maintenance behind the seam, with a searcher and a
codec that evitaDB owns and that were first validated against L" — and that outcome is only reachable if
both were measured. Path C at the extent above is justifiable only by a failure of both on the criteria or
a hard block on integration; and even then the first question is whether the seam can be filled from the
libraries' *pieces* — the graph from one, the searcher pattern from the other — rather than written from
nothing.

### 6.3 The mini-gate: decision criteria

The criteria and their thresholds are set in advance. The "weight" column says how the result is reflected:
*blocking* means that failing it alone decides against a candidate. **[2026-09-07]** The table is per
candidate. Cells marked *known* were decided by reading the checkouts; the spike confirms them by
measurement or by a running JVM but does not re-open them.

| Criterion | How it is measured | Threshold | Weight | jVector (A) | Lucene as library (L) |
|---|---|---|---|---|---|
| recall@10 with rescoring | against a brute-force ground truth | ≥ 0.95 | blocking | measured | measured |
| query latency | 1M × 768, one thread, p50 and p95 | < 10 ms | blocking | measured | measured |
| filtered ANN | latency and recall per strategy per selectivity (§8.2) | no band below 0.95 that the exact scan does not cover cheaply | blocking | measured with our searcher (§4.6) | measured with `FilteredHnswGraphSearcher` |
| incremental maintenance | insert and delete at runtime without a full rebuild; recall drift under churn (§10, step 7) | works within a measured rebuild cadence | blocking | **known: yes**, two-phase delete, quiescent `cleanup()` (§4.3) | **known: insert-only**; liveness bits + rebuild, cadence measured |
| JPMS | `requires` from a named module | works, or has a workaround | blocking | **known: automatic module**, no `jlink` (§4.7) | **known: named module** (§4.8) |
| interruptibility | a cancellation check inside the walk | the query can be cancelled | high | **known: absent in the library's searcher**; present in ours (§4.6) | **known: `earlyTerminated()` per iteration** |
| codec pluggability | a foreign approximate scorer drives the walk | works without a fork | high | **known: `ScoreFunction`** (§4.6) | scorers are plain classes |
| heap memory | bytes per indexed vector per component, JOL (§9.3, §11.5) | codes + graph within §5.2; raw vectors not on the heap | high | measured; raw block needs our mapped RAVV on JDK 21 (§4.4) | measured |
| mmap compatibility | which backend is selected on the target JDK | maps, no 2 GB failure | high | **known: `MappedChunkReader` on 21, `MemorySegmentReader` on 22** (§4.4) | not applicable — evitaDB reads its own files |
| maturity and format | released version, format stability, configuration surface | see §4.1 | high | RC only; JMX-global builder config (§4.3); stale `UPGRADING.md` | released 10.x; stable |
| dependency size | the transitive tree, CVEs | no frameworks | medium | four, one dead (§4.7) | none |
| graph family | HNSW vs Vamana vs hybrid at equal memory and recall (§10, step 4) | informative | medium | all four corners in one library | HNSW reference only |

The last-but-one row of the first version's table is at the same time a reminder that **the mini-gate's
result is not only "yes/no", but also a choice of version** (§4.1). That is recorded separately.

### 6.4 The seam: the decision has to stay reversible

Regardless of how the gate turns out, all contact with a library has to pass through the contracts of
§6.1 — nothing wider. The reason is not aesthetic: if jVector passes with the maturity caveat (§4.1), it is
likely that in a year the version or even the library will change, and the seam is the only thing
distinguishing such a swap from a rewrite. The contracts are designed in paths A and L and validated in
path B; whether jVector, Lucene, or in-house code stands behind them in the end should not be recognizable
from the calling code.

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
plan omitting it stops somewhere in the third step of realization. **[2026-09-07]** Two facts from §4.3
make it evitaDB's artifact rather than the library's: jVector's on-disk graph "may not contain holes in the
ordinal sequence" and requires a remap on persisting after deletes (`UPGRADING.md:132-135`), and Lucene
has no deletes at all — so whoever owns the mapping also owns tombstones and compaction of the ordinal
space, and that is the storage layer, not a graph library.

### 7.2 The file layout

The design starts from what §5.1 found about the existing convention — i.e. `fileIndex` in the name and
binding to a bootstrap record, not a catalog version in the name. A triple of artifacts:

- **raw vectors** — a dense `float32` block, immutable, read through mmap, never on the heap; its only
  consumer is rescoring (§4.6) and, on JDK 21, the mapped `RandomAccessVectorValues` that feeds a build
  (§4.4);
- **the quantized codes and the graph** — per §5.2 of the order of 200–350 MB per 1M vectors for a 1-bit
  code, i.e. a candidate for the heap or for direct buffers, not necessarily for mmap; **[2026-09-07]** for
  an 8-bit code the codes alone are 768 MB per 1M and the question whether they, too, are mapped is a
  design fork §9.3 records;
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

An open question the spike has to decide and which is recorded in §12: whether these files are **owned by
jVector in its own format** (`OnDiskGraphIndex` can write and read its own structure, format version 6,
`OnDiskGraphIndex.java:71`), or whether they are **owned by evitaDB** and the library is given only a read
interface. The first variant is faster and it is the obvious path for path A; the second is more consistent
with the storage and is necessary if the files are to pass through compaction and time travel like
everything else. **[2026-09-07]** §7.1 tilts it: the ordinal mapping is evitaDB's in any case, and a
format whose feature flags are an ordinal bitmask (`FeatureId.java:48-62`) is one whose evolution we would
not control.

### 7.3 The lifecycle

Writing a new version is an append of a new file; the old version stays as long as somebody is looking at
it. The cleanup **is not invented** — it is registered through
`ObsoleteFileMaintainer.removeFileWhenNotUsed` (`ObsoleteFileMaintainer.java:200`), which already knows the
retention floor from active readers and pinned versions (§5.2).

The registration itself, however, **does not protect a mapped file**, and this is the trickiest place in the
whole of §7. The retention floor is computed from active readers and pinned catalog versions
(**[2026-09-07]** `ObsoleteFileMaintainer.java:383`, `:325`) — but **a live mapping is invisible to it**:
evitaDB tracks sessions and catalog versions, not who holds a mapping. A file registered for deletion and at
the same time mapped can therefore disappear from under a reader's hands.

The solution is in the same API, merely two methods away: the vector reader has to **call
`catalogVersionPinned(v)` on mapping and `catalogVersionReleased(v)` on unmapping**
(`ObsoleteFileMaintainer.java:325` and `:334`). The mapping thereby becomes a full-fledged participant in
the floor's computation and the file survives exactly as long as it should. The pin protocol has moreover to
order the release correctly: on JDK 22 the release is `Arena.close()` on the shared arena (§4.4), on JDK 21
it is whatever unmaps the `MappedByteBuffer`s of `MappedChunkReader` — in either case "release the mapping"
is an action somebody has to perform, and it has to happen before `catalogVersionReleased`.

---

## 8. Filtered ANN and the planner

### 8.1 Four strategies, not one threshold

The research §5.4 speaks of a single selectivity threshold "~1–2 %", below which a switch to a brute-force
scan happens. Lucene shows it is in fact **three different mechanisms with very different thresholds**, and
knowing them is more valuable for setting P6 up than an adopted number.

**The first threshold — an immediate exact search at query level.** In `AbstractKnnVectorQuery` there is the
condition `if (cost <= perLeafTopK) return exactSearch(...)` (`:282-285`), where `perLeafTopK` is not a
plain `k` but the expected number of hits in the segment plus three standard deviations of the binomial
distribution.

**The second threshold — an exhaustive scan at reader level.** In `Lucene99HnswVectorsReader`
`expectedVisitedNodes(k, graphSize)` is computed (`:369`), which is per
`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/util/hnsw/HnswGraphSearcher.java:56-58` simply
`(int)(Math.log(graphSize) * k)`; if that number comes out greater than or equal to the number of documents
passing the filter, the graph is not used at all. For our brief it means: with 1M vectors and `k = 10`,
`ln(10^6) × 10 ≈ 138`, so **Lucene goes to brute force only when the filter passes fewer than ~138 out of a
million — i.e. roughly 0.014 %.**

**The third threshold — the choice of a specialized filtered searcher.** `KnnSearchStrategy.Hnsw` has
`DEFAULT_FILTERED_SEARCH_THRESHOLD = 60` (`.../search/knn/KnnSearchStrategy.java:26`), i.e. at **less than
60 % passing** (`:80-83`) `FilteredHnswGraphSearcher` is reached for. **[2026-09-07]** That searcher is
Lucene's ACORN-1 (§4.8): it keeps the ordinary walk while a neighbourhood's accepted share is above
`EXPANDED_EXPLORATION_LAMBDA = 0.10` and otherwise explores the neighbours of rejected neighbours, by a
multiplier bounded by `min(1/filterRatio, maxConn/2)` (`FilteredHnswGraphSearcher.java:46-63`, `:166`) —
so the widening is proportional to how sparse the filter is, and it costs nothing when the filter is dense.

The number "1–2 %" from the research therefore lies between two mechanisms doing different things: above it
Lucene still uses the graph (merely with a different searcher), below it there is still a long way to brute
force. **jVector meanwhile has none of those three mechanisms** — its `Bits` is an acceptance test on popped
candidates with unconditional expansion (§4.6). The switch is therefore owned by evitaDB and P6 has to
design it.

**[2026-09-07]** Naming the strategies precisely matters, because the first version called two different
things "pre-filtering". There are four, and the spike measures all four:

1. **Exact scan** over the candidate set: iterate the bitmap, score each member against the raw (or a
   code) block, keep the top-K′. Exact by construction; cost linear in the cardinality; it is also the
   ground-truth generator (§11.3). The external note's strongest single claim (its §12) is that this is a
   required execution path, not a testing utility — and the FANN study it cites (arXiv 2508.16263,
   external, not verified locally) reports that at ~0.1 % qualifying vectors *neither* ACORN nor plain HNSW
   reached the target recall, while subset scanning stayed robust. If our measurement shows the same band,
   the planner's lowest regime is the exact scan and nothing else.
2. **Unfiltered walk with post-filter**: search the graph with `Bits.ALL`, oversample by a factor, filter
   the result, rerank. Cheapest per query when the filter is dense (the Z7 regime: the must-match filter
   leaves 85–95 % of the corpus); the oversampling factor grows as `1/selectivity` and the strategy
   collapses below some measured point.
3. **Walk with in-walk acceptance**: the `Bits` test of §4.6 — rejected nodes are still expanded, only not
   returned. This is what jVector offers and what Lucene did before 10.2. It is safe when the accepted
   share is moderate and degenerates toward a full traversal as it falls; its recall may also drop, because
   the graph pruned to accepted nodes stops being well connected.
4. **Predicate-aware walk** (ACORN-1): as 3, plus second-hop widening when a neighbourhood's accepted
   share is low. Lucene's `FilteredHnswGraphSearcher` for path L; for path A it is written over
   jVector's `View` (§4.6) — the writing is the seam test of §10, step 5. The FANN study (external)
   reports ACORN and plain HNSW competitive above ~10 % qualifying and plain HNSW best above ~50 %, which
   is consistent with Lucene's 60 % switch and says the widening must not run unconditionally.

Verification over the Elasticsearch checkout (main, commit `9a100e2d0e41`, 2026-08-13) adds no further
threshold to it, but something more useful: **a naming of both sides of the choice and evidence that it is a
planner's decision, not a fixed strategy.** Elasticsearch has two paths for filtered ANN: **pre-filtering**
during the graph walk, whose default heuristic is, from a certain index version, ACORN
(`DenseVectorFieldMapper.java:199` and `:204`, the choice being read by `KnnVectorQueryBuilder.java:573`),
and **post-filtering** (`PostFilterKnnQuery.java`). Between them decides **the filter's estimated
selectivity** — a configurable `postFilterSelectivityThreshold` (same place `:72`), i.e. classic query
planning over an estimate. Elastic's published reasoning for choosing ACORN over indexes that require the
filter dimensions to be declared at build time — predicate-agnosticism preserves schema and query
flexibility (external) — maps exactly onto evitaDB, where the candidate set is the output of arbitrary
filter algebra.

Two concrete conclusions follow for P6. First, **our decision is exact, not heuristic**: we have the
candidate bitmap computed at that moment, so we do not estimate selectivity — we know it exactly. The switch
of §8.2 is therefore a threshold function over a known number, not an estimation model that can be wrong; it
is one of the few points where our model is simpler than theirs, and it is worth naming it as such. Second,
**the default strategy for us is post-filtering**. Per Z7 the must-match filter cuts only 5 to 15 % of the
corpus, so it is very weakly selective, and in that regime an in-walk test merely makes the graph walk more
expensive without saving anything. The in-walk and predicate-aware strategies are needed only for selective
combinations (a deep category plus a narrow price range), i.e. for the same tail of the distribution the
exact-scan insurance exists for.

**[2026-09-07]** One structure the spike reads about but does not build: the Curator family (SIGMOD 2026,
external) pairs the graph with a hierarchical partition structure specialized for low-selectivity filters,
because "graph connectivity breaks down when qualifying records are very sparse". If the exact scan turns
out to be too slow in the band where the graph fails — which for 1M vectors it should not be, but for 10M
it might — that is the shape of the sixth strategy, and §6.1 must not preclude a partition sidecar on the
vector store. Nothing more is asked of P6 about it.

### 8.2 What P6 measures

The spike's task is not to adopt a number but to **measure the curve**. **[2026-09-07]** The matrix is
fixed in advance so that it cannot be trimmed to whatever looks good:

- **selectivities**: 100 %, 50 %, 10 %, 5 %, 1 %, 0.1 %, 0.01 % of the corpus qualifying (the external
  note's ladder, its §29; Lucene's 60 % and 0.014 % are reference points against which the result is
  compared for common sense, and 60 % replaces the first version's 60 % / 20 % pair);
- **strategies**: the four of §8.1, over the same graph and the same codec;
- **predicate shape**: **random** subsets *and* **semantically correlated** subsets — a real evitaDB
  filter (a category, a brand, a price band) selects vectors that cluster in embedding space, and a graph
  pruned to a cluster behaves differently from one pruned at random. The correlated generator is built by
  choosing a seed vector and taking its nearest `s·N` neighbours from the ground truth, or, on the real
  catalog of §11.2, an actual facet. Both shapes at every selectivity;
- **outputs per cell**: recall@10 after rerank, p50 and p95 latency, distance computations per query,
  nodes visited per query (`SearchResult` counters for A, `KnnCollector` for L), and the oversampling
  factor that reached the recall.

The curves' intersections are the sought thresholds. The second thing this experiment has to show is **the
degradation of recall under a filter**. A graph pruned by an acceptance test stops being well connected,
and that is precisely why Lucene reaches for the widening. If recall@10 falls below 0.95 in some band of
selectivity for *every* graph strategy, that is a blocking finding regardless of latency — and it decides
whether the exact scan is the planner's lowest regime by necessity (§8.1, strategy 1).

Essential for Z7: the research says the must-match filter leaves 85–95 % of the corpus, so **ordinary
traffic falls into a band where the graph is unambiguously the right choice**. A query becomes selective
only in combination with facets and hierarchy; the switch is therefore insurance for the distribution's
tail, not the main path. That is good news for the work budget — but it does not mean it can be omitted,
because without it jVector degrades in that tail into a walk of the whole graph, with no way to stop it
(§4.6).

### 8.3 [2026-09-07] The candidate-set contract

The strategies of §8.1 read the candidate set in two ways and the contract has to serve both cheaply:
`contains(ord)` for the in-walk and predicate-aware walks (hundreds to thousands of calls per query), and
iteration for the exact scan (every member once). evitaDB's `Bitmap` already has both
(`evita_engine/src/main/java/io/evitadb/index/bitmap/Bitmap.java:110` for `contains`; the RoaringBitmap
backing iterates in sorted order), and the cardinality is a stored count. What sits between the walk and
the bitmap is the ordinal map of §7.1 — `contains(ordToPk[ord])` — one array read per test.

The external note (its §26) records that the FANN study's ACORN implementation paid a per-query memory
overhead for a boolean flag per vector, and asks that membership be evaluated against the existing bitmap
rather than a materialized `boolean[N]`. That is the default here. **But the opposite has to be measured
too**: for a dense filter (50 %, 100 %) a walk that consults a Roaring container per test may lose to one
that first copies the bitmap into a flat bitset of `N/8` bytes (125 KB per 1M — trivial to allocate and
cheap to fill from a Roaring iterator). The cell "in-walk acceptance at 50 %" is run both ways, and the
planner may pick the materialization as part of the strategy above some cardinality.

### 8.4 The text × vector hybrid

RRF fusion does not belong in P6 (§1.2), but the spike must not close its doors. Concretely that means a
single requirement: the vector leg has to be able to return **a ranking of candidates, not only a top-K with
an incomparable score**, and it has to be able to do it for a `K` markedly larger than the page of results.
RRF works with ranks precisely so that it does not have to calibrate scores against the composite of §4.3,
and `rerankK` in jVector (§4.6) is the parameter through which the ranking's depth is governed;
**[2026-09-07]** `resume(additionalK, rerankK)` (§4.6) is the cheaper alternative when the fusion needs
more depth than the first pass fetched, and its cost at depth is one more measurement of §8.2. Verifying
that the depth can be set high enough without latency falling below the criterion is cheap and belongs
there.

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

## 9. [2026-09-07] Codecs, oversampling and the memory budget

This chapter is new. The first version treated quantization as a property of jVector (§4.5); the checkout
shows the codec is a separable slot (§6.1), the libraries fill it differently, and the memory criterion of
§1 is decided by the codec far more than by the graph. The external note's arithmetic makes the point
before any measurement: a 768-dimensional float32 vector is 3,072 B, while a graph of degree 32 is a few
hundred bytes per node — so the traversal representation is where the memory goes, and a 1-bit code turns
that around and makes the graph the dominant term (its §18). Both halves are measured here.

### 9.1 The ladder

Every code is measured with the same graph, the same query set and the same exact reranker, so that the
only variable is the code:

| Code | Source | Bytes / 768-d vector (documents) | Scoring | Note |
|---|---|---|---|---|
| float32 | both | 3,072 | exact | the baseline and the reranker |
| 8-bit | Lucene `OptimizedScalarQuantizer`, `UNSIGNED_BYTE` / `SEVEN_BIT` | 768 + corrections | int8 dot | the "SQ8 baseline" the note asks for (its §17); jVector has none |
| NVQ 8-bit | jVector `NVQuantization` | 768 + per-subvector params | non-uniform | designed as a second-pass code; measured as a first-pass code for comparison only |
| 4-bit | Lucene `PACKED_NIBBLE`; jVector NVQ `FOUR` | 384 | nibble dot | |
| PQ | jVector `ProductQuantization`, 256 clusters | `M` (e.g. 96 or 192) + 786 KB codebooks shared | table lookup | the only PQ on the table; anisotropic option on and off |
| 2-bit | Lucene `DIBIT_QUERY_NIBBLE` | 192 + corrections | asymmetric | |
| 1-bit, asymmetric | Lucene `SINGLE_BIT_QUERY_NIBBLE` | 96 + corrections (~14 B) | 1-bit doc × 4-bit query | the RaBitQ-derived code; Elasticsearch's default from 384 dims (§4.5) |
| 1-bit, symmetric | jVector `BinaryQuantization` | 96 | Hamming | the library's authors expect it to fall short at this dimensionality (§4.5) |

The two 1-bit rows are the same size and are **not** the same thing; measuring both is how the spike
answers whether the newer scheme's recall advantage is real on our embeddings.

### 9.2 Oversampling and reranking

For every code, the walk retrieves `oversample × topK` candidates by approximate score and the exact
reranker over the raw block orders them; the oversampling factors are **1×, 2×, 3×, 4× and 8×** (3× being
Elasticsearch's default, §4.5). Two recalls are recorded per cell — **before** reranking (the code's own
ordering) and **after** — because the second is what the user sees and the first says how much the reranker
is carrying. The latency criterion applies to the whole pipeline including reranking, and the reranked
candidates are random reads into the mapped raw block (§7.2), so this is also where the mmap path is
exercised under realistic access.

What the cell family answers: the smallest code whose final recall reaches 0.95 at an oversampling factor
that keeps latency under 10 ms — and whether that code is the one §5.2 budgeted for.

### 9.3 The memory accounting rule

The criterion of §1 said "codes plus graph within §5.2 and the raw vectors never on the heap". The
external note's rule (its §21) is sharper and is adopted: report **the total incremental memory caused by
enabling vector search**, per component, then per indexed vector. The components are:

| Component | Where it lives | Order of magnitude per 1M × 768 | How measured |
|---|---|---|---|
| raw vectors | mapped file, off-heap | 3.07 GB mapped, **0 on the heap** — verified, not assumed | JOL heap histogram with rescoring on (§11.5) |
| traversal codes | heap, direct buffer, or mapped — a fork (§7.2) | 96 MB (1-bit) … 768 MB (8-bit) | JOL / `ramBytesUsed` |
| graph topology | heap | jVector on-heap: 8 B per edge (id + score, `NodeArray.java:319-329`) + ~40 B per node; at degree 32 ≈ 300 MB; Lucene on-heap likewise `NeighborArray` with scores; on disk both keep ids only, 4 B per edge | `ramBytesUsed()` (`OnHeapGraphIndex.java:250-268`; Lucene `:312`) cross-checked by JOL |
| ordinal mapping | heap | 8 MB (two `int[1M]`) | JOL |
| search scratch | heap, per searcher | jVector allocates per-thread `GraphSearcher` state and per-thread build scratch retained for the builder's life (§4.3) | JOL under concurrent searchers |
| construction buffers | heap, transient | `neighborOverflow × degree` edges per node during build; PQ training set | peak heap during build |

Two consequences are visible before measuring. **The §5.2 budget (~90–100 MB of codes) is a 1-bit budget.**
An 8-bit code is 768 MB per 1M, eightfold over it, and a 4-bit code is still fourfold; if the 1-bit codes
fail the recall criterion and the 4-bit code passes it, the memory criterion of §1 has to be renegotiated
or the codes have to be mapped rather than heap-resident — Lucene keeps its quantized vectors off-heap in
its own directory abstraction for exactly this reason. That is a fork the spike surfaces with numbers,
not one it decides. **And at 1-bit the graph is three times the codes**, so the on-heap adjacency layout
(scores kept next to ids, per-node object overhead) is the next thing to look at, which is an argument for
an on-disk or flat-array graph representation in path B and one more reason the graph contract of §6.1
must not expose the library's node objects.

### 9.4 What jVector alone cannot do, and what it can

On jVector alone the ladder is PQ, BQ and NVQ — no 8-bit scalar code and no asymmetric 1-bit code, so the
two rows the external note calls the baseline and the most promising direction (its §17, §18) are missing.
They come from Lucene's `OptimizedScalarQuantizer`, which is a plain class over `float[]` (§4.8), and they
drive jVector's traversal through the `ScoreFunction` seam of §4.6 without touching the graph. That is the
codec slot of §6.1 being exercised for the first time, and if it works it also answers the pluggability
row of §6.3. What jVector alone *can* do that the others cannot is build the graph over a compressed code
(`BuildScoreProvider.pqBuildScoreProvider`, `bqBuildScoreProvider`, §4.5) — the larger-than-memory build of
its third tutorial — which is out of scope for 1M vectors but is the property that would matter at 10M.

---

## 10. The realization procedure, step by step

Steps 0 and 1 are **entry conditions and verifications of assumptions**; there is no point starting anything
else until they pass. **[2026-09-07]** Step 1 has shrunk, because the checkout answered most of it; steps
3 to 7 are re-sequenced to the priorities of §1.3 — exact ground truth first, then the graph baselines,
then the filtered matrix, then the codecs, then updates — and each names its output so that the spike can
stop after any step with something recorded.

**Step 0 — the JDK 21 baseline.** Raise `java.version` and add the coordinated changes per §3.2. It is not
P6's work, but without it P6 measures nothing useful (§3.1). Output: the project compiles and the tests run
on 21, `jdk.incubator.vector` is readable. If a JDK 22 toolchain is at hand, the harness module alone is
also built on it for the §4.4 comparison; the product baseline is not raised for that.

**Step 1 — day zero, four confirmations.** (a) Which `VectorizationProvider` a JDK 21 JVM selects with and
without `--add-modules`, read off from the log — expected `Panama` and `Default`; then force it with
`-Djvector.vectorization_provider=panama` and keep that in every harness invocation (§3.2). (b) Which read
backend `ReaderSupplierFactory` selects — expected `MappedChunkReader` after a warning about
`MemorySegmentReader` (§4.4). (c) JPMS: a `requires jvector` from a named module compiles, the
`META-INF/versions/20` layer resolves under it, and the same for `org.apache.lucene.core` (§4.7, §4.8).
(d) `GraphIndexBuilderConfig` is pinned or disabled and its effective values are logged (§4.3). The
preview-lock and interruptibility questions of the first version are answered from the source (§3.3, §4.6)
and are not repeated here.

**Step 2 — the vectors' ingress.** Introduce for the spike the convention of a `byte[]` with float32 in
little-endian (§5.4, variant B) and verify the round-trip write and read. Without this step further work
has no input.

**Step 3 — the harness, the ground truth and the exact scan.** Build the measurement circuit per §11:
loading the dataset, the brute-force ground truth over all vectors *and* over candidate bitmaps, computing
recall@10, measuring latency. **[2026-09-07]** The exact scan is written once, SIMD-vectorized over the
raw block and over each code block, because it is three things at once: the correctness oracle, the
lowest planner regime (§8.1, strategy 1), and the reranker. Output: a harness that reports every metric of
§11.6 for a strategy that is trivially correct, so that every later number has a reference. The harness
has to be finished **before** the index, otherwise the first numbers cannot be compared with anything.

**Step 4 — the float32 graph baselines.** **[2026-09-07]** Build four jVector graphs — `alpha = 1.0` and
`alpha ≈ 1.2`, each with and without `addHierarchy` — and one Lucene `HnswGraphBuilder` graph as the
true-HNSW reference (§4.3, §4.8), all over float32, and obtain the recall/latency curves by varying `M`,
`beamWidth`/`efConstruction` and the search beam; record build time and `ramBytesUsed` for each. Output:
the first hard numbers of recall and latency, and the answer to §1.3's graph question at equal memory and
recall — which graph goes forward into steps 5 to 7, so that the matrix is not run five times over.

**Step 5 — ordinal mapping, the seam test and the filtered matrix.** Add the bidirectional mapping (§7.1)
and the candidate-set adapter (§8.3). **[2026-09-07]** Write the searcher of §4.6 over jVector's `View` —
in-walk acceptance, second-hop widening, a cancellation flag, a visited budget — and run the matrix of §8.2:
four strategies × seven selectivities × two predicate shapes, on the graph chosen in step 4, with Lucene's
`FilteredHnswGraphSearcher` over the Lucene graph as the reference row for strategy 4. Output: the measured
thresholds for the planner, the recall-under-filter verdict, and — the seam test — whether a caller-written
searcher over the library's graph is a fork-free reality or not.

**Step 6 — codecs.** **[2026-09-07]** Run the ladder of §9.1 with the oversampling axis of §9.2 on the same
graph; the Lucene codes through the `ScoreFunction` seam, the jVector codes natively. Output: recall
before and after reranking per code per factor, the heap accounting of §9.3 per code, and the answer to
which code the memory criterion can afford at the recall criterion — including, if it is not the 1-bit
one, the fork §9.3 names.

**Step 7 — incremental maintenance.** Verify `addGraphNode` and `markNodeDeleted` at runtime with a churn
protocol: insert 10 %, delete 10 %, replace 10 %, measure recall and latency after each and after
`cleanup()`; for the Lucene graph, the same churn with a liveness bitmap and a full rebuild, measuring the
rebuild's cost and the recall drift before it. **[2026-09-07]** Two things are verified alongside: that
`cleanup()` really needs a quiescent window with respect to writers and what a searcher pool holding
`View`s does to deleted-node reclamation (§4.3); and what the per-thread scratch the builder retains costs
under evitaDB's thread model. Output: the rebuild cadence each candidate needs, and whether the graph
degrades enough to make that cadence a production concern.

**Step 8 — path B, integration into the storage.** Only here, and only if steps 4–7 passed: vector files
beside the catalog, mmap reading, the mapped `RandomAccessVectorValues` for the build (§4.4), registration
into `ObsoleteFileMaintainer` with the pin protocol (§7.3), a decision about ownership of the format
(§7.2). Output: the answer to the integration half of the mini-gate.

**Step 9 — the decision and the write-up.** Fill in the table of §6.3, decide both branches of the fork
(the library and the version, §4.1), carry the planner's measured thresholds and the codec verdict into the
record ([`../README.md`](../README.md)), and delete this plan's working folder per the ADR rules.

---

## 11. The harness, datasets and measurement

### 11.1 Where the harness lives

The harness belongs **outside the production modules** — it is a disposable measuring rig, not code that
will be maintained. For paths A and L a standalone module that does not depend on evitaDB at all suffices;
only path B connects it with the storage. **[2026-09-07]** It is **engine-neutral by construction**: it
talks to the five contracts of §6.1 and nothing else, and jVector, Lucene and the exact scan are three
implementations of them. jVector's own `BenchYAML` computes recall, MAP, visited and expanded counts, mean
and p999 latency, QPS and heap/off-heap peaks (`jvector-examples/yaml-configs/run-config.yml:1-24`,
`docs/benchmarking.md`) and is worth reading for its metric definitions, but it cannot host the other two
implementations and is not reused.

### 11.2 Datasets

**[2026-09-07]** The checkout answers where jVector's datasets come from and reopens OP6-8 in a better
place. `jvector-examples/yaml-configs/dataset-catalogs/public-catalog.yaml` includes a catalog from
`https://jvector-datasets-public.s3.us-east-1.amazonaws.com/datasets-clean/catalog_entries.yaml`; the
loaders read **fvecs/ivecs only** (`docs/benchmarking.md:13`; the `jhdf` dependency is declared and unused),
and the parameter files name the families `cohere-english-v3-1M` and `-10M` (1,024 dims), `dpr-gemma-1M`
and `-10M`, `cap-1M` and `-10M`, `colbert-1M`, `ada_002_100k` (1,536 dims), plus the small classics (glove,
nytimes, sift). A `c4-en_base_1M_norm.fvecs` with a DPR ground truth is named in the loader's own example
(`DataSetLoaderSimpleMFD.java:95-105`); DPR embeddings are 768-dimensional, which is the target shape, but
**the dimension has to be read from the fvecs header, not assumed from the name**, and the catalog's
availability and licence terms have to be checked on day zero. `siftsmall` (10k × 128) is checked into the
repository (`siftsmall/`) and stays the smoke test.

A proposal for a three-stage set:

1. **`siftsmall` (10k)** as a smoke test — it verifies the harness computes recall correctly, because the
   ground truth is part of the dataset and the result can be compared with a reference.
2. **A public dataset at the target scale** — 1M vectors with 768 dimensions are needed for the criterion of
   §1 to make sense; the candidates are the 1M families above, with the caveat about dimension. If none
   fits, a substitute is a synthetic set with a controlled cluster structure; purely random vectors are
   **misleading** for measuring recall, because in a uniformly distributed high-dimensional space all points
   are similarly distant and the graph then looks better than it would come out on real data.
3. **Real embeddings from a production e-commerce catalog** supplied by Sage. **This is an assumption, not a
   verified fact** — I do not know whether Sage today can produce and export embeddings for those catalogs.
   If it can, it is the most valuable measurement of the three, because only it captures the real
   distribution of the data — and it is the only one on which the *correlated* predicates of §8.2 are real
   facets rather than nearest-neighbour balls.

### 11.3 Recall@10 and the ground truth

The ground truth is computed **brute-force** — the exact distance from the query to all the vectors, top-10.
For 1M × 768 that is of the order of a billion floating-point operations per query, so it is computed **once
and stored on disk**; the query set is to be fixed (proposal: 1,000 queries) so that the numbers are
comparable across runs and configurations. **[2026-09-07]** For the filtered matrix the ground truth is
per (query, predicate) pair — the exact scan of step 3 over the candidate set — and is likewise stored.

Recall@10 is then a plain ratio: how many of the ten real nearest neighbours appeared in the returned ten,
averaged over the queries. It is measured **after rescoring**, because that is the value the user sees and
because rescoring is precisely what is to make up for the loss from quantization (§9.2); the before-rescoring
value is recorded beside it.

### 11.4 Latency: a separate harness, JMH for a part only

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
criterion "< 10 ms". **[2026-09-07]** Forcing the provider (§3.2) turns that silent failure into a loud one
and is mandatory in the JMH `jvmArgs`; the check of the log stays as the second line of defence.

### 11.5 Memory

The memory criteria of §1 are measured with **JOL**, by the same procedure P1 prescribes for the text
branch, and reported per component per §9.3. What is key is to verify two things separately: that the
quantized codes and the graph sit within the estimate of §5.2, and — which is architecturally more
important — that **the raw vectors do not appear on the heap**. The second is not proved by an estimate
but by measuring the heap's size with rescoring switched on over 1M vectors; were the raw data pulled onto
the heap, it would show immediately and unmistakably. **[2026-09-07]** On JDK 21 that measurement is the
proof that our mapped `RandomAccessVectorValues` (§4.4) does its job during build as well as during search.

### 11.6 [2026-09-07] The record of every configuration

Every measured cell carries, or the number is not kept: the dataset and its dimension; the graph
(library, `alpha`, hierarchy, degrees, beam, `neighborOverflow`, the effective `GraphIndexBuilderConfig`);
the code and its parameters; the strategy and the selectivity and the predicate shape; the oversampling
factor; recall before and after reranking; p50, p95 and p99 latency; distance computations and nodes
visited per query; heap bytes per component and per vector; build time; and, for step 7, the churn
protocol and the rebuild time. The JVM's provider and read-backend log lines are part of the record.

---

## 12. Open questions

**[2026-09-07]** Closed by the checkout: OP6-2 (no preview lock, §3.3) and OP6-3 (JPMS metadata is absent;
the automatic module is the workaround and `jlink` is out, §4.7). Restated: OP6-7 and OP6-8. New: OP6-13
to OP6-18.

- **OP6-1 — the JDK entry condition.** When and by whom will the baseline be raised from 17 to 21 per §3?
  Without it P6 measures nothing it exists for. It is the hardest dependency of the whole plan. Whether a
  JDK 22 toolchain can be used for the harness alone (§3.1) is a smaller question beside it.
- **OP6-4 — the ingress of embeddings into production.** The spike makes do with a `byte[]` (§5.4, variant
  B), but the production shape is undecided and newly **three-way**: extend the type system with `float[]`
  (A), introduce a custom `StoragePart` (C), or introduce a dedicated field type for embeddings with its own
  storage (D). Variant D is supported by the declaration of `VECTOR_SEARCH` as a separate capability of the
  data format in OpenSearch and **substantially changes the effort estimate** against A, because it avoids
  extending the type system with a generally usable attribute value (§5.4). This is a reopening of O5 at the
  technical level; the shape of the declaration in the schema belongs in `schema-design.md`, here belongs only
  the consequence for the scope of work.
- **OP6-5 — ownership of the vector files.** Does jVector write and read them in its own format, or does
  evitaDB own them and the library gets only a read interface (§7.2)? It decides whether the files pass
  through compaction and time travel like everything else. §7.1 tilts it towards evitaDB.
- **OP6-6 — the jVector version.** 3.0.x stable without the hierarchy, NVQ and Fused PQ, or 4.0.0-rc.x with
  them and with the risk of a long RC (§4.1)? A separate decision beside the mini-gate.
- **OP6-7 — the codec at the recall criterion.** Restated: which rung of the ladder of §9.1 reaches
  recall@10 ≥ 0.95 after reranking at an oversampling factor that holds the latency criterion, and does
  the asymmetric 1-bit code beat symmetric BQ on our embeddings? If the answer is a 4- or 8-bit code, the
  memory criterion of §1 is renegotiated or the codes are mapped (§9.3) — that fork is surfaced, not decided.
- **OP6-8 — a dataset at the target scale.** Restated: which of the 1M families in jVector's public S3
  catalog is 768-dimensional, under what terms, and how large is the download (§11.2)?
- **OP6-9 — real embeddings from Sage.** Can Sage today produce and export embeddings for the production
  e-commerce catalogs? In §11.2 it is carried as an assumption, not as a fact.
- **OP6-10 — graph degradation under incremental maintenance.** Does the graph need a periodic rebuild after a
  series of deletions, and if so, how does that rebuild meet the storage's compaction (step 7)? For jVector
  the added question is the quiescent window `cleanup()` needs and how long-lived `View`s interact with
  reclamation (§4.3).
- **OP6-11 — concurrency.** All the criteria of §1 are measured single-threaded. How does vector search behave
  under concurrent load and how does mmap paging meet evitaDB's other I/O? Outside P6's scope, but somebody has
  to measure it before production. jVector's rule of one `GraphSearcher` and one `View` per thread, and the
  scratch each retains, is the starting constraint (§4.3, §4.6).
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
- **OP6-13 — the seam test.** Can a caller-written searcher over jVector's `View` (§4.6) deliver in-walk
  acceptance, second-hop widening, a cancellation check and a visited budget with the same per-hop cost as
  the library's own searcher — i.e. is `getNeighborsIterator` / `processNeighbors` fast enough to walk
  through, or does the library's searcher enjoy access the View does not expose? Step 5 answers it, and a
  negative answer moves the filtered strategies to path L or to a fork.
- **OP6-14 — the maintenance model without deletes.** If Lucene's graph wins on filtering and codecs, what
  is the rebuild cadence a liveness bitmap forces at the churn rates of the production write path (P2's
  WAL replay is the source of the rate), and is a periodic rebuild acceptable at all in a transactional
  store that compacts on its own schedule (§5.2)?
- **OP6-15 — global configuration.** `GraphIndexBuilderConfig` is a process-wide JMX-mutable singleton
  (§4.3). Is pinning it per process acceptable for a database that may host several catalogs with different
  vector settings, or does that alone force the deprecated constructors or a fork?
- **OP6-16 — the correlated predicate generator.** Nearest-neighbour balls from the ground truth are a
  stand-in for real facets (§8.2). Only OP6-9's data makes them real; until then the generator's shape is a
  choice that has to be stated with every filtered number.
- **OP6-17 — the pathological band.** If the measurement of §8.2 shows a selectivity band in which no graph
  strategy reaches the recall criterion *and* the exact scan exceeds the latency criterion, a partition-
  assisted structure (§8.1) becomes the sixth strategy. P6 does not build it; it records whether the band
  exists at 1M and estimates from the curve whether it would at 10M.
- **OP6-18 — heap-resident or mapped codes.** §9.3's fork: if the affordable code is larger than 1-bit, do
  the codes join the raw block in a mapped file, with the page cache as the memory budget, or stay on the
  heap with the budget renegotiated? It is a storage-layout decision with a measurable cost on the random
  reads of the walk, and it belongs to path B.
