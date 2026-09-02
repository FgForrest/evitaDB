> **A background study for the research, not a decision.** The document was written on 2026-08-13 as
> input for incorporation into the research (`../research.md`) and the prototype plans; the
> load-bearing findings are already reflected there. It was translated from Czech and moved out of
> `specifications/` into this record on 2026-08-24.

# OpenSearch — findings for the design of evitaDB's own fulltext engine

> **Source:** local checkout `/www/oss/OpenSearch`, branch `main`, version 3.9.0-SNAPSHOT, commit
> `36edc05ac84f6812fd44c21cb47f771d16f42558` of 2026-08-12.
> All `file:line` anchors are relative to that directory and that commit.
> Lines move fast in OpenSearch — take the anchors as an entry point for `rg`, not as eternal truth.
>
> **Scope:** OpenSearch is a 2021 fork of Elasticsearch 7.10. The shared Lucene-server layer (mapping,
> analyzers, translog and refresh, BM25, suggesters, rescore, function_score) this document
> **deliberately skimps on** — the parallel research over Elasticsearch covers it. The depth here belongs
> to what OpenSearch does differently or in addition.
>
> **Honesty about sources.** I keep the convention of §8 of the research (VK13/VK14/VK20): for every
> area it is marked whether the claim rests on source code read in this checkout, or only on
> documentation in the repository, or on general knowledge. **Four of the nine assigned areas do not
> live in this repository at all** — hybrid search and the normalization processor, k-NN,
> learning-to-rank and query insights are separate plugins in separate repositories. For those I
> describe **only the seam** by which they hook into the core, because that seam really is in this
> checkout; I do not describe the plugin's internals at all, so as not to bring an unsupported paragraph
> into a document whose whole value rests on verifiable anchors.
>
> **How to read this.** The entry point is **[§10 Summary of consequences](#10-summary-of-consequences-for-our-design)**
> — ten new design options (A1–A10), twelve arguments strengthening what is already decided (B1–B12) and
> a table of what this checkout could not answer (C). Sections 1–9 are the evidence for them; every claim
> in §10 refers to the section it comes from. Whoever is short of time reads §10.

---

## Contents

1. [Concurrent segment search](#1-concurrent-segment-search)
2. [Hybrid search and score normalization — the seam only](#2-hybrid-search-and-score-normalization)
3. [k-NN and vectors — the seam only](#3-k-nn-and-vectors)
4. [Star-tree index — a side structure beside the inverted index](#4-star-tree-index)
5. [Segment replication and remote storage](#5-segment-replication-and-remote-storage)
6. [Search pipelines — server-side configuration of query and response modification](#6-search-pipelines)
7. [Learning-to-rank, rescore, function_score](#7-learning-to-rank-rescore-function_score)
8. [Mapping changes and reindex](#8-mapping-changes-and-reindex)
9. [Other OpenSearch-specific things](#9-other-opensearch-specific-things)
10. [Summary of consequences for our design](#10-summary-of-consequences-for-our-design)

---

## 1. Concurrent segment search

*(Verified by reading source code.)*

### What it is

The classic Lucene-server model parallelizes a query **between shards** — every shard is a standalone
Lucene index and runs in its own thread. Inside a shard, however, a query was historically evaluated
serially: one thread walked all the segments (a Lucene *segment* = an immutable batch of documents
arising at flush or merge) one after another. OpenSearch added **parallelization inside a shard**: the
segments are divided into *slices* and each slice is processed by its own thread from a dedicated pool.

The parallel run itself is done by Lucene — an `IndexSearcher` handed an `Executor`. OpenSearch supplies
three further things: the decision *when* to parallelize, the computation of *how* to slice the
segments, and the collection of partial results.

### How the slicing works

Slicing is governed by `ContextIndexSearcher.slices()`
(`server/src/main/java/org/opensearch/search/internal/ContextIndexSearcher.java:589`), which delegates to
`MaxTargetSliceSupplier.getSlices()`
(`server/src/main/java/org/opensearch/search/internal/MaxTargetSliceSupplier.java:34`). Three strategies
exist, switched by the setting `search.concurrent_segment_search.partition_strategy`
(`server/src/main/java/org/opensearch/search/SearchService.java:352`):

- **`segment`** — the simplest: a slice = a whole group of segments, a segment is never split
  (`MaxTargetSliceSupplier.java:59`).
- **`balanced`** (the default) — a segment exceeding a fair share of the work and at the same time a
  minimum size is split into parts; smaller segments stay whole (`MaxTargetSliceSupplier.java:70`).
- **`force`** — every segment is cut into as many parts as there are slices
  (`MaxTargetSliceSupplier.java:93`).

The last two strategies mean **searching inside a single segment**: a slice is no longer "a set of
segments" but an interval of document identifiers inside a segment
(`LeafReaderContextPartition.createFromAndTo`, `MaxTargetSliceSupplier.java:112`). That is a substantial
departure from the original Lucene model and it solves a real pain: one giant post-merge segment
otherwise degrades parallelism to a single thread.

Distributing the parts into slices is a classic **LPT schedule** (Longest Processing Time first): the
parts are sorted descending by document count and each is assigned to the least loaded slice
(`MaxTargetSliceSupplier.java:123`, comment at `:120`). A binding Lucene condition: two parts of the same
segment must not end up in the same slice — otherwise two `LeafCollector`s for the same segment would
arise in one slice.

The default number of slices is `max(1, min(available_processors / 2, 4))` (`SearchService.java:2109`),
so very conservatively **at most four**. The threads come from the dedicated `index_searcher` pool
(`server/src/main/java/org/opensearch/threadpool/ThreadPool.java:131`), which is of type `RESIZABLE` and
sized at twice the processors with a queue of 1000 (`ThreadPool.java:349`).

### When it parallelizes — and what that reveals

The most interesting part is not the mechanics but the **decision logic**. The mode is set by
`search.concurrent_segment_search.mode` with the values `all`, `none`, `auto`, the default being `auto`
(`SearchService.java:313`). In `auto` mode the decision is made by
`DefaultSearchContext.evaluateAutoMode()`
(`server/src/main/java/org/opensearch/search/DefaultSearchContext.java:1029`), and its basic rule is
remarkably straightforward:

> **Does the query have aggregations? Then parallelize. Does it not? Then don't.**
> (`DefaultSearchContext.java:1043`, with an explicit log message "request does not have
> aggregations, not using concurrent search" at `:1061`.)

Only as a second condition is a newer branch added: if the query has no aggregations but supports
intra-segment search itself (`query.supportsIntraSegmentSearch()`, `DefaultSearchContext.java:1051`) and
the strategy is not `segment`, it parallelizes too.

Above all of that sit hard prohibitions in `evaluateRequestShouldUseConcurrentSearch()`
(`DefaultSearchContext.java:1078`), which **switch concurrency off regardless of the setting**:

- sorting by a time-series field (`:1079`),
- an aggregation for which some factory does not support concurrency (`:1081`),
- **`terminate_after` other than the default** (`:1085`).

The last point is significant: `terminate_after` is an early-termination mechanism, i.e. exactly the
"shortcut" our design rejects as unnecessary in §2.3. OpenSearch arrived at early termination and
parallelism being mutually exclusive — every thread would terminate on its own counter and the result
would stop being deterministic.

Plugins can intervene in the decision too, through `ConcurrentSearchRequestDecider`
(`server/src/main/java/org/opensearch/search/deciders/ConcurrentSearchRequestDecider.java`); their votes
compose in `ConcurrentSearchDecision.getCompositeDecision()` and the value `NO_OP` means "I have no
opinion, decide yourself" (`DefaultSearchContext.java:1041`).

### What it does to the score — and that is the key question

What is decisive is whether the slicing changes the **results**, or only the **latency**. The answer is
in where the corpus statistics come from. `ContextIndexSearcher` overrides both statistical methods:

```java
// ContextIndexSearcher.java:555
public TermStatistics termStatistics(Term term, int docFreq, long totalTermFreq) {
    if (aggregatedDfs == null) {
        return super.termStatistics(term, docFreq, totalTermFreq);
    }
    ...
}
// ContextIndexSearcher.java:570 — collectionStatistics analogously
```

Both paths are **independent of the slice**: either the statistic is supplied by `aggregatedDfs`, which
is the result of the optional DFS phase aggregating statistics across *shards*, or `super` is consulted,
i.e. Lucene's `IndexSearcher`, which reads them from the **top-level `IndexReader`** — from the whole
shard, not from the slice. A slice is therefore purely a scheduling unit; neither `docFreq` nor
`avgFieldLength` changes with it. **The score is invariant with respect to slicing** and parallelization
changes only the latency.

Merging the partial results is done by Lucene's `CollectorManager.reduce()` — in OpenSearch wrapped in
`ReduceableSearchResult`
(`server/src/main/java/org/opensearch/search/query/TopDocsCollectorContext.java:558`, `:588`). For top-K
documents the merge is correct: every slice yields its own `TopDocs` and `TopDocs.merge` composes a
global order from them. The only thing that has to be corrected manually is the shard index, which Lucene
sets during the merge and OpenSearch zeroes out, because it is assigned only by the coordinator
(`ContextIndexSearcher.java:268`, with an explanatory comment).

### Where concurrency does change the results after all: aggregations

Here is the finding that makes this section worth reading. Aggregations, unlike top-K, are **not**
invariant with respect to slicing. The reduction has a new intermediate stage — "slice level" — and
`InternalTerms.reduce()` behaves in it as if a slice were a separate shard:

```java
// InternalTerms.java:467
if (reduceContext.isFinalReduce() || reduceContext.isSliceLevel()) {
    final int size = Math.min(localBucketCountThresholds.getRequiredSize(), reducedBuckets.size());
    final BucketPriorityQueue<B> ordered = new BucketPriorityQueue<>(size, order.comparator());
    ...
}
```

In other words: **every slice truncates its list of terms to `shard_size` before the slices are
merged**. A term that is just under the boundary in every slice but would globally make it into the
top-N disappears. OpenSearch knows this and keeps its own error flag for it —
`hasSliceLevelDocCountError` (`InternalTerms.java:232`, set at `:411`, consumed when computing
`docCountError` at `:516`). Switching concurrent search on can therefore enlarge the
`doc_count_error_upper_bound` of a terms aggregation. `InternalSignificantTerms` handles the same
(`:248`, `:279`, `:321`).

The switch enters the reduction context from `DefaultSearchContext.partialOnShard()` (`:1119`) by a
single line `rc.setSliceLevel(shouldUseConcurrentSearch())` — this whole approximation hangs on whether
concurrency is on.

### Consequence for our design

**First: our case is strictly stronger than theirs.** OpenSearch had to prove that slicing does not
change the score, and proved it only because it reads statistics from the top-level reader. Our model
(§2.1) **has no corpus statistics at all** — the score is a function of the query and the document only.
Splitting phase 1's candidate bitmap into parts therefore cannot change the score even theoretically;
there is nothing to share between the parts. An argument OpenSearch has to defend by construction
follows for us from the definition. That is a concrete, citable point for §4.3 or for plan P1:
parallelizing phase 1 is safe, and for a different and firmer reason than in Lucene engines.

**Second: watch out for truncation, not for the score.** The finding at `InternalTerms` says where the
risk of parallelization really lies — not in ordering, but in **approximate aggregations with truncation
per partition**. Our facets and histograms are computed from full bitmaps by set operations, i.e.
**exactly**; the cardinality of the intersection of two bitmaps is the same whether it is computed whole
or in parts and summed. As long as the facet computation does not introduce a "top-N per partition"
shortcut, parallelizing the facet branch is lossless. This is a warning formulated in advance: should
somebody in future want to speed facets up by truncating on the parts, they buy exactly the error
OpenSearch had to start measuring and reporting in the response.

**Third: `terminate_after` vs. parallelism.** OpenSearch found that early termination and concurrency
exclude each other. Our design has no early termination (§2.3, WAND is "not deferred, it is
unnecessary") — which means we do not have one of the obstacles to parallelization. Worth recording as a
further unintended benefit of the decision not to implement WAND.

**Fourth: calibrating expectations of parallelism.** The default of four slices and the rule
"parallelize only when there are aggregations" are the empirical verdict of somebody who measured it at
production volumes: **parallelization pays off on the full-set aggregation path, not on the top-K text
one**. That is exactly our division — phase 1 over the full set plus facets is "aggregation" in
character. For P1 a concrete order follows: measure the goal "phase 1 ≤ 25 ms per 10⁶ candidates, one
thread" single-threaded first (as the plan says) and consider parallelism only as a second step, with an
expectation of a small number of parts (single digits, not tens) and with the awareness that the
overhead of scheduling and merging will outweigh it for small candidate sets.

**Fifth: the LPT schedule as a ready-made recipe.** If it comes to splitting the candidate bitmap,
`MaxTargetSliceSupplier.distributePartitions` (`:123`) is a usable template — the parts are sorted by
size descending and greedily assigned to the least loaded slice. With roaring bitmaps a natural splitting
boundary moreover suggests itself that Lucene does not have: **the container boundary (2¹⁶ PKs)**, i.e.
the same boundary on which we already chunk the impact sidecar (§4.2). The parts would thereby be aligned
with the sidecar and the rank alignment would stay local within a part.

---

## 2. Hybrid search and score normalization

*(Verified by reading source code — but only the **existence and shape of the seam** is verified. The
normalization processor itself is not in this repository.)*

### The finding: it is not in the core

Grepping for `NormalizationProcessor`, `HybridQuery`, `ReciprocalRank` and `RRF` across the whole
checkout returns **zero in the core's production code**. The only hits are in the `rank-eval` module
(`modules/rank-eval/…/MeanReciprocalRank.java`), which is an *evaluation metric of search quality*, not a
fusion operator — it computes MRR over judged results, it does not merge the results of two queries.

OpenSearch's hybrid search, including the `hybrid` query and the `normalization-processor`, lives in a
**separate repository `opensearch-project/neural-search`**, which is not cloned here. The concrete
normalization algorithms (min-max, L2) and combinations (arithmetic, geometric, harmonic mean) and their
RRF implementation therefore **cannot be evidenced from this checkout** and I deliberately do not
describe them. For the research it means: if §5.5 is to cite OpenSearch as a precedent for fusion, either
`neural-search` has to be cloned, or the claim has to be marked with the same reservation as
VK13/VK14/VK20 ("only via the web, without reading source code").

### What is in the core: the `SearchPhaseResultsProcessor` seam

More interesting than the missing plugin is **the seam the core offers** — and that is fully legible in
this checkout. It is a general mechanism "a processor between search phases":

```java
// server/src/main/java/org/opensearch/search/pipeline/SearchPhaseResultsProcessor.java:21
public interface SearchPhaseResultsProcessor extends Processor {
    <Result extends SearchPhaseResult> void process(
        final SearchPhaseResults<Result> searchPhaseResult,
        final SearchPhaseContext searchPhaseContext
    );
    SearchPhaseName getBeforePhase();   // :55
    SearchPhaseName getAfterPhase();    // :61
}
```

A processor declares between which two phases it should run — the phases are named in `SearchPhaseName`
(`server/src/main/java/org/opensearch/action/search/SearchPhaseName.java:20`): `dfs_pre_query`, `query`,
`fetch`, `dfs_query`. It is triggered from the main search loop:
`AbstractSearchAsyncAction.java:852` calls `PipelinedRequest.transformSearchPhaseResults()`
(`server/src/main/java/org/opensearch/search/pipeline/PipelinedRequest.java:112`, the actual handover to
the processor at `:125`) at every transition between phases.

What matters is **what the processor is handed**: `SearchPhaseResults<Result>`, i.e. the raw partial
results from all shards after the `query` phase, but **before the `fetch` phase**. At that moment the
document identifiers and their scores are available, but the documents have not yet been fetched. The
processor can therefore **rewrite, normalize and reorder** the scores arbitrarily, and the fetch then
pulls only the winners according to the new order. This is exactly where the `normalization-processor`
from the `neural-search` plugin hooks in: it runs once over the results of the text and the vector leg
together, aligns their scales and composes them into a single order.

It is registered through `SearchPipelinePlugin.getSearchPhaseResultsProcessors()`
(`server/src/main/java/org/opensearch/plugins/SearchPipelinePlugin.java:104`), i.e. by the same mechanism
as ordinary request/response processors (`:44`, `:55`).

### Consequence for our design

**The seam is architecturally instructive even if we do not read the plugin.** OpenSearch did not place
score fusion into the ranking function nor into a query operator, but **after the end of evaluation and
before the documents are fetched**. The reason is obvious once stated: fusing two differently scaled
lists needs to **see both lists whole**, which is in principle impossible inside the scoring loop of one
query. The score in one leg cannot be normalized until the range of scores in the whole leg is known.

For §5.5 a concrete confirmation of our choice follows: **RRF is right for us, because it works with
ranks**, i.e. with a quantity that needs no normalization and is knowable locally. If we went the way of
min-max score normalization, we would have to build our own analogue of this seam — a place where both
legs meet before the final ordering. In our model (§4.3, a single `long` and a heap select) that is not
free: one global sort by one number is the whole mechanism, and adding "but first compute the min and
max of both legs" means a second pass. **RRF needs no second pass**, because the order arises from the
heap we have anyway.

For **P6** (the vector spike) and for **P7** (rank profiles) a design recommendation follows: conceive the
fusion step as **a separate, named phase between evaluation and paging**, not as a lane in the composite.
The composite of §4.3 is a lexicographic packing and fusion by its nature does not belong in it — fusion
mixes two independent orders, whereas a lane breaks a tie. It is worth formulating now, because §4.3
today speaks of "mapping back into a single `orderBy(relevance())`" without saying where that step
physically lives.

A second, smaller note: OpenSearch can place a processor between `dfs_pre_query` and `query` too, i.e.
before the evaluation proper. That is their analogue of "enrich the query with statistics one leg does
not have itself". For us the analogy does not exist and we do not need it — precisely because we have no
corpus statistics (§2.1). Worth recording as another thing that decision means we do not have to build.

---

## 3. k-NN and vectors

*(Verified by reading source code — and the finding is largely negative.)*

### What is not in the core

**The OpenSearch core has no vector data type.** In the directory
`server/src/main/java/org/opensearch/index/mapper/` there is no mapper containing `vector`, `dense` or
`knn` in its name (verified by listing the directory). There is no `knn_vector` field type, no `knn`
query builder. The whole k-NN plugin — the data type, HNSW indexes, integration of native libraries —
lives in a separate repository `opensearch-project/k-NN`, which is not cloned here.

**The supported vector engines therefore cannot be evidenced from this checkout.** I will not claim them
from memory. I searched `docs/` and `release-notes/` in the repository and the only mention of vectors in
the release notes is a technical note about a timeout expiring:
`release-notes/opensearch.release-notes-3.7.0.md` states "Add `queryTimeout` to IndexSearcher for n
vector search timeout enforcement (#21316)". There is nothing about Lucene HNSW versus faiss versus
NMSLIB in this repository. If the research needs an engine matrix, it has to take it from the project's
documentation with the reservation "only via the web" after the pattern of VK13/VK20, or clone `k-NN`.

### What is in the core: two traces of vectors

**The first trace — a timeout for vector queries.** `ContextIndexSearcher` contains an explanatory
comment at `server/src/main/java/org/opensearch/search/internal/ContextIndexSearcher.java:163` and
`:623` saying that its timeout class implements both
`ExitableDirectoryReader.QueryCancellation` (OpenSearch's interruption mechanism) and Lucene's
`QueryTimeout` interface, because Lucene components such as `TimeLimitingKnnCollectorManager` enforce a
limit by the latter route. The core therefore knows about vector queries enough to be able to deliver
them an interruption signal.

**The second trace is more interesting — declaring a data format's capabilities.** In
`server/src/main/java/org/opensearch/index/engine/dataformat/FieldTypeCapabilities.java` lives an
experimental (`@ExperimentalApi`) record by which a data format declares what it can do:

```java
public enum Capability {
    FULL_TEXT_SEARCH,      // inverted index (BM25, phrase queries)
    COLUMNAR_STORAGE,      // columnar storage for aggregations
    VECTOR_SEARCH,         // kNN / ANN                       :39
    POINT_RANGE,           // range queries via a point tree
    STORED_FIELDS,         // original values, row-wise
    BLOOM_FILTER,          // probabilistic lookup for pruning
    FORWARD_TERMS_INDEX
}
```

That is an **explicit catalog of the separate physical structures a modern search engine decomposes
into**, written by people who have seven of them in production. Vector search is one capability beside
the inverted index here, not an extension of it.

### Consequence for our design

**First, a confirmation of separating the legs.** Our design keeps the vector branch (§5) as a separate
structure with its own storage model (mmapped immutable files, §5.3) beside the text structures on the
heap (§4.2). The `Capability` enum says that an established player does it that way too, and even at the
level of a declared contract: `FULL_TEXT_SEARCH` and `VECTOR_SEARCH` are two different capabilities, not
two modes of one. That is an argument against the temptation to "stuff vectors into the same dictionary".

**Second, a strong organizational precedent.** That OpenSearch keeps the whole vector branch **outside
the core, as a plugin in its own repository with its own release cycle**, is not a detail of project
administration. The vector part has an entirely different risk structure from the text part: it depends
on native libraries, is tuned empirically on the recall versus latency trade-off, and changes far faster.
For **P6** that supports two things the plan already suspects: (a) preferring jVector over an in-house
HNSW — adopting measured code is the right choice for an empirically tuned structure, and OpenSearch
reached the same conclusion in an extreme form (it factored the whole thing out); (b) the proposal that
the vector branch have **its own mini-gate** and not be an entry condition of the text delivery.

**Third, a warning about `float[]`.** Plan P6 §5.4 recorded that `float[]` is not a supported attribute
type today — the path of embeddings inwards is technically closed. The `FieldTypeCapabilities` enum shows
how it is solved elsewhere: **a vector field is not "an attribute of type array of floats" but a separate
field type with its own declared capability and its own storage.** For us that is more of a relief than a
complication — we do not have to push `float[]` as a general attribute type through the whole schema,
mutations, the gRPC and Kryo layers (which per the `evita-schema-change` skill is an eight-layer
operation). A **dedicated type for embeddings** suffices, one that need not be filterable, sortable nor
returned as an ordinary value. This is worth adding to P6 and to `schema-design` as an option, because it
fundamentally changes the effort estimate.

**Fourth, interruptibility.** The detail with `QueryTimeout` is a reminder P6 does not mention today: **a
walk of an ANN graph is a long loop without a natural interruption point** and has to have its own
query-cancellation check. An interruption mechanism exists in evitaDB; when integrating the vector leg it
has to be threaded right into the library's inner loop, otherwise a cancelled query runs to completion.
With jVector that means verifying whether its API allows it at all — which is a concrete verification
point for the spike.

---

## 4. Star-tree index

*(Verified by reading source code. The most important section of this document for §4.2 and plan P2.)*

### What it is

A star-tree is a **precomputed aggregation structure lying beside the inverted index** in the same
segment. A list of *dimensions* (fields to group by — status, region, time window) and *metrics* (fields
to aggregate — sum, count, minimum, maximum) is given in the schema. The structure then precomputes the
aggregates for combinations of dimension values, including "star" nodes where a dimension is represented
by a placeholder meaning "across all values" — hence the name. A query asking for the sum of a metric for
a given combination of filters then does not have to walk documents at all; it reads one precomputed node.

For us what is interesting is exactly the reason it was put on the list: it is a **precedent for a side
structure beside the inverted index**, i.e. the same thing the impact sidecar is in our design (§4.2).
The question it should answer is not "how does a star-tree aggregate" but **"how is such a side structure
maintained when the index is being written to"**.

### Where it lives in the code

Construction and the data model: `server/src/main/java/org/opensearch/index/compositeindex/`,
specifically `datacube/startree/` with the subdirectories `builder/`, `aggregators/`, `fileformats/`,
`node/`, `index/`, `utils/`. The query side: `server/src/main/java/org/opensearch/search/startree/`.

What is decisive, though, is **the wiring into storage**: the star-tree is implemented as an **extension
of the Lucene codec**. The file
`server/src/main/java/org/opensearch/index/codec/composite/composite912/Composite912Codec.java` on line
25 declares `public class Composite912Codec extends FilterCodec`, and it supplies its own
`Composite912DocValuesFormat` (`:49`) with its own file extensions and codec names
(`Composite912DocValuesFormat.java:41`, `:44`). The structure is therefore **part of the segment's
format**, not a runtime object beside it. There is a series of backward-compatible codecs
(`backward_codecs/composite101/`, `composite103/`, plus `composite104/`), which is their analogue of our
Kryo and `serialVersionUID` discipline.

### The answer to the key question: is it maintained incrementally? No.

This is the finding this section exists for. **The star-tree has no incremental update path at all.**
There is no method "add a document to the star-tree" nor "remove a document". `StarTreesBuilder`
(`server/src/main/java/org/opensearch/index/compositeindex/datacube/startree/builder/StarTreesBuilder.java`)
has **exactly two entry points**, and both build the structure whole:

- **`build()`** (`:77`, JavaDoc "Builds all star-trees for given star-tree fields") — the path at
  **flush**, i.e. when a buffer in memory becomes a new segment on disk.
- **`buildDuringMerge()`** (`:114`, JavaDoc "Merges star tree fields from multiple segments") — the path
  at **merge** of segments.

The caller confirms it. `Composite912DocValuesWriter` has the flush path explicitly distinguished by the
condition `mergeState.get() == null` with the comment "Perform this only during flush flow"
(`Composite912DocValuesWriter.java:165`, `:184`, `:193`), which leads into
`createCompositeIndicesIfPossible()` and there into `starTreesBuilder.build(...)` (`:243`). The merge
path is separate: `merge(MergeState)` at `:304` calls `mergeCompositeFields` (`:315`) and
`mergeStarTreeFields` (`:324`), which end at `buildDuringMerge(...)` (`:361`).

One refining note so that the picture is not harsher than it is: **a merge does not recompute the
structure from raw documents.** `mergeStarTreeFields` collects the existing star-tree values from the
`CompositeIndexReader` of the merged segments (`Composite912DocValuesWriter.java:331`) and merges the
precomputed aggregates with each other. Hierarchical pre-aggregation composes — a sum of sums is a sum.
That is a property our impact sidecar has too, only more trivially: bytes are not merged, they are simply
carried over.

There are two builders and the choice is by configuration: `OnHeapStarTreeBuilder` and
`OffHeapStarTreeBuilder` (`StarTreesBuilder.java:152` and `:154`). That an off-heap variant exists at all
is information in itself: building this structure is so memory-demanding for large segments that it
called for a second implementation spilling intermediate results to disk.

### The second finding: the structure cannot be added to an existing index

`CompositeIndexValidator` rejects a configuration change entirely explicitly:

```java
// server/src/main/java/org/opensearch/index/compositeindex/CompositeIndexValidator.java:37
throw new IllegalArgumentException(
    "Composite fields must be specified during index creation, "
    + "addition of new composite fields during update is not supported"
);
```

A star-tree is therefore specified **at index creation and never afterwards**. If you want to add it to
existing data, the only path is reindexing into a new index. `StarTreeValidator` moreover limits the
number of star-tree fields per index (`StarTreeValidator.java:38`) and rejects dimensions and metrics over
fields whose type does not support aggregation (`:74`, `:92`).

### The third finding: the query side is a pure optimization with a fallback

The star-tree is used for a query only when it passes the gate `StarTreeQueryHelper.isStarTreeSupported()`
(`server/src/main/java/org/opensearch/search/startree/StarTreeQueryHelper.java:53`):

```java
return context.aggregations() != null
    && context.mapperService().isCompositeIndexPresent()
    && context.parsedPostFilter() == null;
```

Three conditions, and the third is instructive: **the presence of a post-filter disqualifies the
star-tree**. A post-filter is a filter applied only after aggregations are computed (in e-commerce
exactly the mechanism by which facets are computed "as if the selected facet were not selected"). The
star-tree aggregates over the set given by the filter *before* the aggregation; if the result were then
filtered further, the precomputed nodes would answer a different question. It does not crash, the
structure simply is not used.

When the gate passes, the aggregation is **precomputed instead of iterating documents** —
`precomputeLeafUsingStarTree()` (`:74`) walks the bitmap of matching *star-tree nodes*
(`getStarTreeFilteredValues`, `:122`) and pours values into the consumer without touching a single
document. Decomposing the filter into dimensional conditions is done by `search/startree/filter/` with
providers for term, range and bool conditions (`filter/provider/StarTreeFilterProvider.java`,
`BoolStarTreeFilterProvider.java`, `StarTreeRangeQuery.java`). When a filter cannot be decomposed into
dimensions, the normal path runs. The structure is therefore **purely an acceleration, never a bearer of
correctness**.

### Consequence for our design

Here one has to be careful, because the finding can be read two ways and both are partly true.

**Reading one — support for the fallback of §4.5(3).** An established player with enormous operational
experience builds a side structure beside the inverted index **exclusively in batch, at flush and merge,
as an immutable part of the segment, and cannot even add it to an existing index.** Incremental
maintenance of such a structure under live writes does not exist in OpenSearch. That is a strong argument
that our fallback "visibility after commit" (§4.5, point 3) **is not a concession but the market's
ordinary answer** — and that it should not be formulated as a failure of P2 but as a full-fledged variant
that P2 either surpasses or confirms. For the research I propose reformulating the tone of §4.5(3): today
it reads as an emergency exit ("if P2 shows an unbearable tax, we relax"), and yet it is the
configuration the star-tree has hard-wired.

**Reading two — and why the first reading is weaker than it looks.** A star-tree is a **considerably
heavier structure than our sidecar** and its non-incrementality largely follows from that. A star-tree is
combinatorial: one new document contributes to all the nodes corresponding to all the subsets of its
dimension values, including the star ones. An incremental update would have to touch a large and
unknown-in-advance number of nodes, and would moreover have to be able to subtract on deletion. Our impact
sidecar is by contrast **flat**: one byte per (field, term, PK) triple. Writing a document touches exactly
the terms the document contains — the number of affected places is bounded by the document's length, not
by combinatorics. One therefore cannot deduce from "a star-tree is not maintained incrementally" that "a
rank-aligned sidecar is not maintained incrementally"; they are tasks of different weight and ours is the
lighter one.

**Reading three, and this is the load-bearing one — their answer presupposes segments we do not have.**
"Build it again at flush" is cheap only because a Lucene index is a sequence of **immutable segments**: a
write never modifies existing data, deletion is a bitmap, and the rebuild amortizes across the merge
policy. evitaDB does not have that substrate — it has a live transactional index in memory with diff
layers. **We therefore could not adopt the "rebuild at flush" model even if we wanted to**, because we
have no flush at which it could be performed. That is in my view the most important sentence of this
section and it belongs in §4.2 or in P2 §3: OpenSearch does not solve our problem differently, it **does
not have** it, and its solution is not transferable. P2 therefore measures something for which no
comparison point exists in OpenSearch — which is a more honest formulation than "nobody does it, so it is
risky".

**Practical transfer number one: the sidecar as a pure acceleration with a fallback.** The
`isStarTreeSupported` gate and the silent fall back to the normal path is a pattern we ought to adopt
literally. Our impact byte is a precomputed value; if a sidecar chunk for a given (term, container)
combination were missing or marked invalid, **phase 1 ought to be able to compute it or skip the lane**,
not fail. That gives P2 a third option beside "maintain via COW" and "give up read-your-writes":
**invalidate the affected chunks on write and compute them lazily on read.** Writing then pays only for
the marking, reading pays only for what it actually reads, and because fulltext is under 1 % of queries
per Z7, the asymmetry is in our favour. Plan P2 does not have this option today and in my view it is the
most valuable concrete output of this whole OpenSearch study.

**Practical transfer number two: the structure's configuration is irreversible — and we have no mechanism
for it.** The finding that a star-tree cannot be added to an existing index resonates with the finding of
`schema-design` §4.3 and §7 that **in evitaDB a mechanism for reindexing on a schema change does not exist
and the change passes silently**. OpenSearch solves this problem with hard validation: it would rather
reject the change than produce an index inconsistent with its own schema. That is a usable and cheap
pattern for us — until we have a reindexing mechanism, **explicitly rejecting a change of fulltext
configuration over a non-empty collection is better than silently accepting it**. A silent change produces
an index that does not correspond to the schema and nobody finds out; a loud rejection is unpleasant but
truthful. It belongs in `schema-design` as a concrete recommendation.

**Practical transfer number three: the existence of an off-heap builder as a warning about a memory
peak.** That OpenSearch had to write `OffHeapStarTreeBuilder` because the build could not be kept on the
heap is a reminder for P1: **the memory peak while building a structure can be several times higher than
the memory of the finished structure.** P1's criterion reads "RAM ≤ 150 MB per 1M products and locale"
and is measured with JOL over the finished structure. **The maximum during the build** should be measured
too — especially for the initial population of the index over an existing catalog, where everything is
built at once.

---

## 5. Segment replication and remote storage

*(Verified by reading source code and the HEAD commit message — with one reservation marked inside: the
mechanism of score divergence under document replication is a judgement, not a finding.)*

### Two replication models

Elasticsearch replicates **by document**: a write goes to the primary shard and to every replica and
**every replica indexes the document itself**, i.e. builds its own segments. OpenSearch added a second
model, **segment replication**: only the primary shard indexes and the replicas receive finished **segment
files**. It is chosen by the setting `index.replication.type`
(`server/src/main/java/org/opensearch/cluster/metadata/IndexMetadata.java:357`) with the values
`DOCUMENT` and `SEGMENT`
(`server/src/main/java/org/opensearch/indices/replication/common/ReplicationType.java:19`).

Two things about that setting are worth noticing. The default value is still **`DOCUMENT`**
(`IndexMetadata.java:360`, with a comment at `:355` "By default, document replication is used") — segment
replication is opt-in even after years. And the setting is `Property.Final` (`:389`), i.e. **immutable
after index creation**; the replication model cannot be switched on an existing index.

The mechanics are in `server/src/main/java/org/opensearch/indices/replication/`. A replica tracks a
`ReplicationCheckpoint` (`.../replication/checkpoint/ReplicationCheckpoint.java:34`), which carries
`primaryTerm`, `segmentsGen`, `segmentInfosVersion`, `length` and — importantly — **`codec`** (`:37`–`:41`).
`SegmentReplicationTarget` requests the checkpoint's metadata, downloads the missing segment files and
finalizes replication by uploading a serialized `SegmentInfos`
(`.../replication/SegmentReplicationTarget.java:65`, `:75`, `:91`).

### What segment replication buys and what it pays with

**It buys byte-identical indexes.** A replica has no segments of its own; it has copies of the primary's.
It follows that any quantity derived from the index's structure — `docFreq`, the number of deleted
documents not yet removed by a merge, `avgFieldLength` — is **identical** on the replica to the primary
shard. The score is therefore stable across replicas. Indexing CPU time is also saved, which under
document replication is paid as many times as there are replicas.

> **A reservation about the source.** The supplementary claim that *under document replication scores
> diverge between replicas* — because every replica merges independently and at a different time, so the
> share of deleted documents still counted into `docFreq` differs — is **general knowledge about Lucene's
> behaviour and my own judgement, not a finding from this checkout.** I did not find an anchor for it in
> the repository and I deliberately do not present it as verified. What is verified and anchored is only
> what follows: the tie to the format under segment replication (a quotation from the HEAD commit message
> below) and the existence and non-default status of the DFS phase. Whoever cites the divergence claim
> into §2.1 has to mark it with the same reservation as VK13/VK14/VK20, or find support for it elsewhere.

**It pays, however, with a tie to the binary format across nodes.** Here is the best evidence in the
repository, and by coincidence it is the message of the very commit this checkout stands on
(`36edc05ac84f6812fd44c21cb47f771d16f42558`, "Promote lowest-version replica on doc-rep failover and make
node_version decider Lucene-aware"). I quote it verbatim:

> "The segment-replication check in canAllocate compared Lucene versions in the wrong
> direction: it asked whether the candidate primary node could read the replica's segments.
> With segment replication the replica continuously reads segments written by the primary,
> so the question is the reverse — whether the replica can read what the primary would write."

Segment replication therefore introduces into the cluster a **constraint of format compatibility between
nodes** that document replication does not have — under document replication every node writes with its
own codec and nobody reads foreign files. The commit at the same time shows how fine it is: the shard
allocation decision rule had to start comparing **the actual Lucene version** (`Version#luceneVersion`,
identical or a newer `major.minor`), not the OpenSearch version, because patch updates without a change to
the Lucene format were needlessly evaluated as incompatible and replicas stayed unallocated. The second
tax is lag: a replica is visibly behind until it catches up with the checkpoint, so **reading from a
replica cannot give read-your-writes**.

### Remote storage

Remote storage (`index.remote_store.enabled`, `IndexMetadata.java:392`, the definition at `:403`) moves
the model further: segments and the transaction log are uploaded into object storage and replicas read
them from there, not from the primary shard (`.../replication/RemoteStoreReplicationSource.java`). The
supporting mechanics are in `server/src/main/java/org/opensearch/index/remote/` — path strategies
(`RemoteStorePathStrategy`), transfer tracking (`RemoteSegmentTransferTracker`,
`RemoteTranslogTransferTracker`) and backpressure when uploading cannot keep up
(`RemoteStorePressureService`).

What matters is the hard coupling: **remote storage requires segment replication.** The validator enforces
it from both sides — enabling remote storage without `SEGMENT` ends in an exception
(`IndexMetadata.java:414`) and so does setting another replication type with remote storage enabled
(`:370`). The reason is obvious: shared object storage makes sense only when there is a single writer and
the others read its output.

### An extension of the picture: the DFS phase

One more finding belongs to replication, which I found while searching for the answer to the question of
score stability in section 1, and which deserves separate emphasis, because it is **the most direct
existing evidence for the claim of §2.1**.

Corpus statistics are **per shard** in the Lucene-server model. A document therefore gets a score
depending on which shard it landed on at indexing time — because a term's `docFreq` is computed inside the
shard, not across the whole index. The only remedy is the **optional DFS phase**: an extra network round
*before* the evaluation proper, which collects statistics from every shard, sums them and only then
scores. `DfsPhase.execute()` (`server/src/main/java/org/opensearch/search/dfs/DfsPhase.java:57`) does it
by wrapping the `IndexSearcher` and intercepting every call to `termStatistics` and `collectionStatistics`
into maps, which it then sends to the coordinator; the coordinator composes them into `AggregatedDfs`,
which `ContextIndexSearcher` consumes in the second round (`.../internal/ContextIndexSearcher.java:557`,
`:571`).

And now the main thing: **it is not the default mode.** `SearchType.DEFAULT = QUERY_THEN_FETCH`
(`server/src/main/java/org/opensearch/action/search/SearchType.java:62`); `DFS_QUERY_THEN_FETCH` is
described as "same as QUERY_THEN_FETCH, except for an initial scatter phase which goes and computes the
distributed term frequencies" (`:45`) and has to be requested. **By default, therefore, both Elasticsearch
and OpenSearch score from per-shard statistics and admittedly sacrifice score comparability between
shards** — in exchange for one saved network round.

### Consequence for our design

**First, this is the best ammunition for §2.1 I found in OpenSearch.** The research today argues that
corpus statistics bring "the definition of the corpus, statistics drift vs. snapshot, score instability
across replicas". The DFS phase is **the cost of that property quantified in the architecture**: for the
statistics to be global, an entire extra network round is needed before every query, and it is so
expensive that nobody has it switched on by default. Segment replication is then **a second, independent
cost of the same property**: for the statistics to be stable between replicas, the bytes of segments have
to be sent instead of documents, which commits the whole cluster to a shared format. Our model pays
neither of them, because the score is a function of the query and the document only. One sentence in this
spirit belongs in §2.1 or §8, with the anchor `SearchType.java:62` — that is stronger than an abstract
argument, because it shows that even with full knowledge of the problem its creators chose inaccuracy as
the default behaviour.

**Second, mapping onto our replication.** evitaDB attains determinism by a different route — the index is
a deterministic function of the WAL, a model closer in spirit to **document replication** (every reader
replays the same operations and builds its own structures). The key difference is why that route does not
lead to divergence for us, whereas it does for Lucene. In Lucene what diverges are **non-deterministic
side effects**: the merge policy runs at a different time on every node, so the share of deleted documents
still counted into `docFreq` differs. Our index **has no such non-deterministic side effect** — we have no
merge policy that would affect the score, and no statistic it would affect. **Replaying the same WAL gives
byte-identical results.** We therefore gain the property OpenSearch had to build segment replication for,
without replicating anything. This is the most precise formulation of our lead and it is worth writing it
into the research exactly this way, because "the index is a function of the WAL" does not by itself
explain why that is not enough for Lucene.

**Third, visibility and the ladder in §4.5.** Segment replication confirms that **reading from a replica
does not give read-your-writes** — a replica is by definition behind the checkpoint. It is further
evidence that the visibility ladder in §4.5 (transactional structures → COW sidecar → visibility after
commit) moves within bounds that are normal in the field, not inferior. It is also worth recording that
OpenSearch made the replication type **immutable after index creation** (`Property.Final`) — the
visibility model is not switchable at runtime, because it would change the guarantees clients rely on. If
we ever considered making the §4.5(3) fallback configurable *per catalog*, this is a reminder that such a
switch belongs in the configuration at creation, not among dynamic settings.

**Fourth, a small point about format.** The existence of several generations of codecs (`composite101`,
`composite103`, `composite104`, `composite912`) plus a `codec` field right in the replication checkpoint
is a reminder of how seriously versioning the binary format of side structures is taken in this field. For
us the same role is played by Kryo and the `serialVersionUID` discipline, which `§4.2` refers to in a
single sentence ("subject to the same Kryo/BWC discipline as the other indexes"). Given that the impact
sidecar is a new format with a non-trivial layout (chunks along roaring container boundaries), it is worth
deciding already in P1 **how it will be versioned and what happens when an older chunk is read** — and not
leaving it to the time when the first incompatible change is needed. The `kryo-bwc-audit` skill in evitaDB
has a ready-made procedure for it.

---

## 6. Search pipelines

*(Verified by reading source code. The closest existing analogue of our rank profiles.)*

### What it is

A search pipeline is a **named, server-stored sequence of processors** that modify a search request before
evaluation and the response after it. Three kinds exist, declared in `SearchPipelinePlugin`
(`server/src/main/java/org/opensearch/plugins/SearchPipelinePlugin.java`):

- **request processors** (`:44`) — rewrite the `SearchRequest` before it is dispatched to the shards,
- **response processors** (`:55`) — rewrite the `SearchResponse` after the results are collected,
- **phase results processors** (`:104`) — run between phases, see section 2.

The core holds the infrastructure in `server/src/main/java/org/opensearch/search/pipeline/`, a set of
concrete processors is supplied by the module `modules/search-pipeline-common/`.

### The distinction of where a profile lives — a direct answer to O1

Open question O1 of the research asks about the **granularity of rank profile configuration: per schema,
or per query?** OpenSearch answers the same question with "all levels at once, with a defined order of
precedence", and that order is legible in the code in a single method —
`SearchPipelineService.resolvePipeline()`
(`server/src/main/java/org/opensearch/search/pipeline/SearchPipelineService.java:464`):

1. **A definition directly in the request.** If the request carries an inline pipeline definition, a
   one-off pipeline is built with the identifier `_ad_hoc_pipeline` (`:83`, construction at `:484`). It
   takes precedence over everything else.
2. **The name of a stored pipeline in the request.** `searchRequest.pipeline()` (`:500`; the field at
   `server/src/main/java/org/opensearch/action/search/SearchRequest.java:128`) — the client says "use the
   profile named X".
3. **The index's default pipeline.** The setting `index.search.default_pipeline`
   (`server/src/main/java/org/opensearch/index/IndexSettings.java:778`), read at `:511`. The client need
   not know anything is happening.
4. **Switching off.** The reserved name `_none` (`NOOP_PIPELINE_ID`, `:84`) means "no pipeline", and it is
   also the default value of the index setting (`IndexSettings.java:780`).

A detail worth attention: when a query targets **several indexes with different default pipelines**,
OpenSearch does not pick one nor chain them — **it switches them all off** and falls back to `_none`
(`SearchPipelineService.java:515`). Rather nothing than an arbitrary choice. That is a defensive decision
of exactly the kind evitaDB's rules require for unexpected states.

### Two-phase ranking as configuration, not as architecture

The most interesting finding in the `search-pipeline-common` module is a pair of processors that together
compose **oversample and truncate**, i.e. "pull more than the client wants, reorder, truncate back":

- **`OversampleRequestProcessor`**
  (`modules/search-pipeline-common/src/main/java/org/opensearch/search/pipeline/common/OversampleRequestProcessor.java`)
  multiplies the request's `size` by the factor `sample_factor` and **stores the original value into the
  processing context** under the key `original_size`. The class's JavaDoc says it literally: "Multiplies
  the 'size' parameter on the SearchRequest by the given scaling factor, storing the original value in the
  request context as 'original_size'." The factor must be ≥ 1.0, otherwise the configuration does not pass.
- **`TruncateHitsResponseProcessor`** (`.../TruncateHitsResponseProcessor.java:31`) truncates the result
  back at the end. It takes the target size either from its own configuration `target_size` (`:36`), or —
  and this is the nice part — **from the context where oversample stored it** (`:55`). When neither is
  available, it throws an `IllegalStateException` with the explanatory message "Must specify target_size
  unless an earlier processor set …" (`:58`).

Between them any response processor that reorders is inserted — which is exactly where the hybrid search
normalization of section 2 sits, or a re-rank by a model. **It is our two-phase ranking (§4.3) with the
parameter K (O2), built as pipeline configuration instead of as an engine property.**

What matters is how the two pieces are bound: **by the request's stateful context**
(`PipelineProcessingContext`), into which the first processor stores a value and from which the last
retrieves it. The processors are therefore marked `Stateful…` (`StatefulSearchRequestProcessor`,
`StatefulSearchResponseProcessor`).

### Explainability: `ProcessorExecutionDetail`

A pipeline can return **a record of what every processor did**. `ProcessorExecutionDetail`
(`server/src/main/java/org/opensearch/search/pipeline/ProcessorExecutionDetail.java:38`) carries for every
processor:

```
processor_name, tag, duration_millis, input_data, output_data, status, error
```

(fields at `:40`–`:46`, their serialization names at `:47`–`:53`). So not only how long the step took but
**the step's input and output data**. The structure is both `Writeable` and `ToXContentObject`, so it
travels over the network and serializes into the response under the key `processorExecutionDetails`
(`:55`). Beside it run the aggregated metric `PipelineWithMetrics` and `SearchPipelineStats`.

### Processors requested by the server, not by the client

`SystemGeneratedProcessor`
(`server/src/main/java/org/opensearch/search/pipeline/SystemGeneratedProcessor.java:14`) is a processor
the system **inserts into the pipeline itself**, without the client asking for it. The method
`shouldGenerate(ProcessorGenerationContext)` (`:38`) decides, and the processor declares where it should
be placed: `ExecutionStage.PRE_USER_DEFINED` or `POST_USER_DEFINED` (`:44`–`:52`, the default being
`POST_USER_DEFINED` at `:19`). Because user and system steps can thereby come into conflict, there is also
a conflict evaluation (`ProcessorConflictEvaluationContext.java`, called from
`SystemGeneratedPipelineWithMetrics.java:222`), which sees the user's set and both system sets separately.

### Consequence for our design

**First, O1 has a ready-made answer and it is worth adopting.** The question "profile per schema, or per
query" has a falsely binary shape. The usable answer is **four-level with a clear precedence**: an inline
definition in the query > a profile name in the query > the default profile in the collection's schema >
switched off. I recommend writing this into O1 as a proposed solution, including two details OpenSearch
has trodden out: having a **reserved name for "no profile"** (their `_none`), because otherwise the
schema's default profile cannot be suppressed from an individual query; and having **defined behaviour
under ambiguity** — their choice "on conflict rather nothing" is directly usable for us, because it fits
evitaDB's rule that an unexpected state must not pass silently.

**Second, the oversample and truncate pair is a lesson about the shape of our K.** Our design (§4.3, O2)
has K as a number in phase 2's configuration with the proposal K = 1000. OpenSearch has it as a
**multiple of the requested page size**, not as an absolute constant — `sample_factor` is multiplied by
what the client wants. That is a better shape, for two reasons: a query for the first page of 20 items
does not need to re-rank a thousand candidates, and conversely a query with a large page could with a
fixed K want more results than phase 2 ordered at all. **A recommendation for O2 and P4: parameterize K
as `max(minimum, multiple × page size)` instead of a bare constant**, and measure how it behaves with deep
paging, where the requested offset is large.

The second lesson from that pair is architectural: the value `original_size` is passed **through the
request's context, not through the signature**. For us that means the query's evaluation context has to be
able to carry "how much the client really wanted" separately from "how many candidates we let through for
the sake of phase 2". If K were reflected directly in the paging `require(page(...))`, the enlarged size
would fall through into the response. It is a small thing, but exactly the small thing that gets
discovered late.

**Third, `ProcessorExecutionDetail` is a precedent for the feature export requirement.** The research in
§4.3 marks exporting the feature vector and the relevance breakdown as a **requirement, not a
nice-to-have**, and names the precedents Solr's `[features]` and Vespa's `match-features`. OpenSearch adds
a third, and of a different kind: Solr and Vespa export **the model's features**, whereas
`ProcessorExecutionDetail` exports **a trace of the processing** — who got what, what they emitted, how
long it took and whether it failed. For debugging relevance those are two different things and both are
needed: "what number did the document get in lane 3" versus "which step of the profile changed the order".
I recommend adding precisely this distinction to P7 — the feature vector answers the first question, the
profile's trace the second. The concrete items are adoptable too: **the step's duration and its status**,
because without them there is no way of telling that an expensive lane was silently skipped.

**Fourth, `SystemGeneratedProcessor` is a warning and a pattern.** The pattern: there is a legitimate need
for the server to insert into evaluation a step the client did not ask for — for us typically the **boost
channel (§4.3, O8)**, because dynamic boosts from Sage are to be applied by the engine, not by the client.
The warning: OpenSearch had to write a whole conflict evaluation against user processors for system
processors, because two steps may want the same thing or contradict each other. If we design the boost
channel as "the server silently adds a lane", we have to answer in advance what happens when a user
profile defines that same lane itself. The cheapest answer is not to do it implicitly: **leave the boost
channel a visible part of the profile** (a lane the profile either contains or does not), instead of an
invisible step inserted behind the configuration's back. That way no conflict arises and the profile stays
a complete description of how the order came about — which is a property §4.3 wants when it insists on the
determinism of "a function of the query, the data and the explicitly passed context".

**Fifth, the limits of the analogy.** A pipeline is **coordinator-side** in OpenSearch, it runs over the
results collected from the shards, and therefore it can do only what can be done after evaluation. Our
rank profile is by contrast **part of the evaluation** — it determines how phase 1 composes the feature
vector into a `long`. They are different places and cannot be interchanged. What is useful is exactly
what is configurational: **naming, storing on the server, distinguishing levels, switchability and the
trace of execution**. Conversely, do not adopt the model "a sequence of processors passing data through a
context" for phase 1 — phase 1 is a hot loop over a million candidates and a chain of generic processors
passing data through a map is exactly what must not be in it.

---

## 7. Learning-to-rank, rescore, function_score

*(Briefly — it is mostly a layer inherited from Elasticsearch, covered by the parallel research. I list
only what is different.)*

**Rescore and function_score are in the core and look inherited.** The directory
`server/src/main/java/org/opensearch/search/rescore/` contains `QueryRescorer`, `QueryRescorerBuilder`,
`RescoreContext` and `QueryRescoreMode` — i.e. the standard mechanism "recompute the score of the top-N
window with a second query and combine it with the original score" (modes sum, multiply, minimum, maximum,
average). The directory `server/src/main/java/org/opensearch/index/query/functionscore/` likewise carries
the familiar set: decay functions (`GaussDecayFunctionBuilder`, `ExponentialDecayFunctionBuilder`,
`LinearDecayFunctionBuilder`), `FieldValueFactorFunctionBuilder`, `RandomScoreFunctionBuilder`,
`ScriptScoreFunctionBuilder` and `ScriptScoreQueryBuilder`. The only item that was not in the base set of
Elasticsearch 7.10 in this form is `TermFrequencyFunction` and `TermFrequencyFunctionFactory` — access to
a term's frequency statistics from a script. I did not trace the novelty in this checkout anywhere but in
the existence of those two files, so I do not label it OpenSearch-specific with certainty.

**Learning-to-rank is not in the core.** Grepping for `learningtorank`, `learning_to_rank` and `LTR` in
the core's production code returns nothing relevant: all hits on `ltr` are accidental substrings across
word boundaries in identifiers of the type `defau`**`ltR`**`emoteStoreSettings` or
`addLoca`**`lTr`**`anslogStatsXContent`. LTR is in the OpenSearch ecosystem a separate plugin in its own
repository, which is not here. **I do not describe it** — nothing verifiable can be said about it from
this checkout.

**Consequence for our design.** The difference against Elasticsearch is so small here that nothing new
follows for us; the load-bearing lesson is already in the research from other sources. One thing is worth
recording, and it is organizational: **both rescore and function_score are in the core, whereas LTR is a
plugin.** The dividing line runs between "a scoring function over values the engine has" and "a model
learned elsewhere". That is exactly the division §1.1 and §1.2 of the research maintain — evitaDB provides
general primitives (rank profiles, the boost channel, feature export), Sage supplies models. OpenSearch
draws the same line in an entirely different context and drew it in the same place, which is weak but
independent evidence that it is drawn correctly. For **F3** (behavioural and LTR re-rank over the feature
vectors of the top-K) it follows that the model should be a **supplied artifact consumed by the profile**,
not a part of the engine.

---

## 8. Mapping changes and reindex

*(Briefly — a layer inherited from Elasticsearch. The only OpenSearch-specific deviation is described in
section 4.)*

**The model is inherited and unchanged.** Fields are in effect immutable after creation:
`ParametrizedFieldMapper` rejects a type change with the message "mapper […] cannot be changed from type
[…] to […]" (`server/src/main/java/org/opensearch/index/mapper/ParametrizedFieldMapper.java:114`, `:119`,
the same in `FieldMapper.java:480`, `:506`) and a change of a non-updateable parameter with the message
"Cannot update parameter […] from […] to […]" (`ParametrizedFieldMapper.java:575`). A new field can be
added, an existing one cannot be changed. The Reindex API lives in `modules/reindex/` and the class names
(`Reindexer`, `TransportReindexAction`, `AsyncDeleteByQueryAction`, `BulkByScrollParallelizationHelper`,
`RethrottleAction`) correspond to the inherited implementation including throttling and changing it at
runtime; `remote/` moreover can reindex from a remote cluster.

**The only deviation is the star-tree** and it is described in section 4: a composite field **has to be
specified at index creation and cannot be added later** (`CompositeIndexValidator.java:37`), which is a
harder rule than for ordinary fields, where adding a new field is possible.

**Consequence for our design.** The combination "fields are immutable" plus "a full-fledged reindex API
with throttling and parallelization exists" is the whole mechanism by which one lives with a schema in
this field. The finding of `schema-design` §4.3 and §7 that **in evitaDB a mechanism for reindexing on a
schema change does not exist and the change passes silently** is a substantial gap against that — both
halves are missing: the prohibition as well as the substitute path. The recommendation for `schema-design`
is therefore twofold and its order matters: **first reject loudly** a change of fulltext configuration
over a non-empty collection (cheap, immediate, and in line with evitaDB's rule that an unexpected state
must fail loudly), and **only then** address the reindexing path that softens that prohibition. The
opposite order means silently inconsistent indexes arise in the meantime.

---

## 9. Other OpenSearch-specific things

*(Verified by reading source code, except at explicitly marked places.)*

### 9.1 `match_only_text` — our §4.7 as a finished field type

This is the most surprising finding outside the assigned list, because it is **our design for proximity
without a positional index (§4.7), built by somebody else and shipped to production as a full-fledged
field type.**

`MatchOnlyTextFieldMapper` (`server/src/main/java/org/opensearch/index/mapper/MatchOnlyTextFieldMapper.java:43`)
is a variant of a text field that **switches off both positions and norms** to save space. The index
options are hard-coded to `IndexOptions.DOCS` (`:60`), i.e. only a list of documents — no frequencies, no
positions. The class's JavaDoc sums it up itself (`:40`): "A specialized type of TextFieldMapper which
disables the positions and norms to save on storage and executes phrase queries, which requires positional
data, in a slightly less efficient manner."

So how does it do phrase queries without positions? Through `SourceFieldMatchQuery`
(`server/src/main/java/org/opensearch/index/query/SourceFieldMatchQuery.java:37`), and the mechanism is
nicely straightforward — **a Lucene two-phase iterator**:

```java
// SourceFieldMatchQuery.java:101
TwoPhaseIterator twoPhase = new TwoPhaseIterator(approximation) {
    @Override public boolean matches() {
        List<Object> values = valueFetcher.fetchValues(leafSearchLookup.source());
        if (values.isEmpty()) return false;
        MemoryIndex memoryIndex = new MemoryIndex();
        for (Object value : values) {
            memoryIndex.addField(fieldType.name(), (String) value, fieldType.indexAnalyzer());
        }
        float score = memoryIndex.search(filter);      // :115
        return score > 0.0f;
    }
    @Override public float matchCost() { return 1000f; }  // :120
};
```

The cheap approximation is a conjunction of terms from position-free postings; the expensive verification
**builds a one-off in-memory index from the document's stored value** (`MemoryIndex`), analyzes it again
with the same analyzer and runs the phrase query against it. The `matchCost` of 1000 is admittedly an
arbitrary number (the comment "arbitrary cost" at `:121`) and serves Lucene to order this verification
after cheaper ones.

Two details are more important for us than the mechanism itself:

- **The result is a `ConstantScoreScorer`** (`:125`). A phrase match therefore **does not contribute to
  the score**, it is purely a yes/no filter.
- The field rejects `index_phrases` with the explicit message "Index phrases cannot be enabled on for
  match_only_text field. Use text field instead" (`MatchOnlyTextFieldMapper.java:103`) — whoever wants
  positions should take an ordinary text field. The choice is therefore deliberate and offered as a
  compromise, not as a default.

**Consequence for our design (§4.7, plan P4).** First, **confirmation of the direction**: the decision Z4
not to index positions and to solve proximity by re-analyzing stored values is not exotic, it has a
production precedent in a mainstream engine, and that precedent even uses the same building mechanics (an
analyzer plus a one-off index over the value of a single document).

Second, and this is more valuable, **a warning about a difference in contract that plan P4 does not
distinguish today**. OpenSearch uses re-analysis as **a filter verified for every candidate** that passed
the cheap approximation. We plan re-analysis as **a ranking lane over the top-K** (§4.3, lane 5; K
proposed at 1000). Those are two different things with different costs:

- If a phrase is a **ranking signal** (whoever has the words close together ranks higher), the top-K
  suffices and our design is correct and cheap.
- If a phrase is a **filter** ("I want only documents with the exact phrase"), **the top-K is not
  enough** — a document with the phrase that ended up at position 1001 in phase 1 would drop out of the
  result despite satisfying the condition. Then every candidate has to be verified as OpenSearch does, and
  the cost grows with the size of the candidate set, which per Z7 is almost the whole corpus.

I recommend writing this into P4 as an explicit entry question: **is a phrase given in quotation marks a
filter or a boost in our query language?** For an e-shop a boost is defensible; for the CMS profile (Z8),
where a user looks for a specific formulation in an article, the expectation is rather a filter. The
answer changes the measurement budget — P4's criterion "re-analysis of the top-1000 ≤ 10 ms" measures only
the first variant.

Third, a small thing with a large impact on measurement: OpenSearch builds the `MemoryIndex` **inside the
loop, anew for every verified document**. That is allocation-costly and in our environment it would show
up in GC — which is an area where evitaDB has, by its own memory, repeated problems on the write path. P4
ought to measure allocations too, not only time.

### 9.2 Derived fields — a field computed at query time, with an index prefilter

`DerivedFieldMapper` (`server/src/main/java/org/opensearch/index/mapper/DerivedFieldMapper.java:32`, the
type `derived` at `:34`) defines a field whose value **is not stored in the index but computed at query
time by a script** over the source document (the parameter `script`, `:53`). The motivation is obvious: to
query something that was not indexed in advance, without reindexing the whole corpus.

What is interesting, though, is the accompanying parameter **`prefilter_field`** (`:68`). A derived field
can name **an existing indexed field that serves as a cheap approximation**, and the query is then
translated into **a conjunction of the index prefilter and the expensive script evaluation**:

```java
// DerivedFieldType.java:171
.map(prefilterFieldType -> createConjuctionQuery(prefilterFieldType.termQuery(value, context),
                                                 derivedFieldQuery))
```

The validation is strict — a non-existent prefilter field brings the mapping down with the message
"prefilter_field[…] is not defined in the index mappings" (`DerivedFieldType.java:104`).

**Consequence for our design.** The pattern "a cheap indexed prefilter and an expensive exact evaluation
in conjunction" is the same shape `SourceFieldMatchQuery` has in 9.1 and that our two-phase ranking has.
That it appears in OpenSearch **a third time and in an entirely different context** is a signal that it is
a load-bearing design pattern, not a one-off trick. For us a concrete recommendation follows for **phase 2
(§4.3)**: every expensive lane ought to have a declared **cheap lower bound or approximation** computable
already in phase 1. Plan P4 proposes something like that for proximity ("a cheap co-occurrence feature in
phase 1"); the pattern says it should not be an exception for one lane but **a requirement on phase 2's
interface** — a pluggable re-rank ought to be able to say "here is my cheap approximation", otherwise
phase 1 cannot be narrowed with a guarantee.

The second note is a warning aimed at `schema-design`: derived fields are an answer to the same pain we
have — **a schema change without reindexing**. Their answer (compute it at query time by a script) is
unusable for us, because our problem is not a missing value but a missing index structure; fulltext
without postings cannot be computed cheaply at query time. It is worth saying so out loud in the document
so that nobody proposes it a second time.

### 9.3 A roaring bitmap as a filter format at the API boundary

The OpenSearch core depends on the **RoaringBitmap 1.3.0** library (`server/build.gradle:133`, the version
in `gradle/libs.versions.toml:45`) and uses it for something that has a special flavour for us: **a terms
query can receive a serialized roaring bitmap instead of an enumeration of values.**

`TermsQueryBuilder` knows the value type `BITMAP`
(`server/src/main/java/org/opensearch/index/query/TermsQueryBuilder.java:108`) and expects a single-element
array with a base64-encoded serialized bitmap — the error message says it literally: "Invalid value for
bitmap type: Expected a single-element array with a base64 encoded serialized bitmap." (`:514`, similarly
`:588`). Evaluation then goes through `numberFieldType.bitmapQuery(bytesArray)` (`:568`) into a pair of
implementations: `BitmapIndexQuery` (over the inverted index,
`server/src/main/java/org/opensearch/search/query/BitmapIndexQuery.java:46`) and `BitmapDocValuesQuery`
(over columnar values, `.../BitmapDocValuesQuery.java:44`). A 64-bit variant via `Roaring64NavigableMap`
exists too (`Bitmap64IndexQuery.java:37`, `Bitmap64DocValuesQuery.java:32`). A detail: for the bitmap type
the set-complement optimization is switched off (the comment "If this uses BITMAP value type, or if we're
using termsLookup, we can't provide the complement", `TermsQueryBuilder.java:754`).

**Consequence for our design.** No direct impact on the engine — we have bitmaps inside, we do not need to
receive them from outside. But it is an instructive **precedent for the shape of an API**, and twice over.

First, the motivation: it came about because passing a list of hundreds of thousands of identifiers as a
JSON array is unbearable, whereas a roaring bitmap is compact and the engine can work with it natively.
Exactly this problem will be had by the **boost channel (O8)**, if the boost map were sent inline in the
query. The research already leaned towards the right solution in O8 — **a reference to a stored,
Sage-generated table, with the engine doing the join** — and this finding supports that leaning from the
other side: even where they decided to send the set in the request, they had to invent a binary format for
it, because a textual one could not carry the volume.

Second, if a need to send a large set of PKs in a query did after all arise (typically a preselected set
from the client application), a **serialized roaring bitmap is a proven answer** — and evitaDB has its own
vendored RoaringBitmap implementation, so it would be a format both sides can handle without a new
dependency.

### 9.4 gRPC and protobuf transport

The module `modules/transport-grpc/` adds a **gRPC interface beside REST**. It is built on `grpc` 1.75.0
and `protobuf` 3.25.8 (`gradle/libs.versions.toml:37`, `:31`) and the message schema is factored out into a
separate artifact `opensearchprotobufs` 1.6.0 (`:30`) — so the proto files are maintained outside this
repository. The server runs on Netty (`Netty4GrpcServerTransport.java`) and today exposes two services:
`SearchServiceImpl` and `DocumentServiceImpl`
(`modules/transport-grpc/src/main/java/org/opensearch/transport/grpc/services/`).

**Consequence for our design.** Practically zero — evitaDB has a gRPC interface from the start and far
further along (the whole query grammar, not two services). It is worth recording only as context: what
OpenSearch is only now building and what so far has the scope of two services is for us a finished and
production-used layer. If the research were to compare integration maturity anywhere, this is a point in
favour of the variant "an in-house engine inside evitaDB" against variant D (delegation to an external
engine) — an external engine connects through an interface it is only just defining for itself.

### 9.5 Query insights — not in the core

Grepping for `queryinsight` and `top_n_queries` in the core returns nothing. Monitoring slow and expensive
queries is in the OpenSearch ecosystem a separate plugin (`query-insights`) outside this repository. **I do
not describe it.** What is in the core is general telemetry infrastructure (`libs/telemetry/`, the plugin
`telemetry-otel`) and search statistics at shard level
(`server/src/main/java/org/opensearch/index/search/stats/ShardSearchStats.java`).

Nothing new follows for us — evitaDB has its own, by its documentation richer query telemetry (query
telemetry, JFR metrics, traffic recording), and the research does not deal with it.

### 9.6 Workload management

The plugin `plugins/workload-management/` introduces rules that **automatically tag incoming requests** and
assign them into groups with their own resource limits (`AutoTaggingActionFilter.java`, the subdirectories
`rule/` and `service/`). Related to it is the overload-control mechanism in the core
(`server/src/main/java/org/opensearch/ratelimitting/admissioncontrol/`).

I mention it only for completeness: it is an operational property of a distributed cluster that an
embedded engine in an embedded database does not address the same way. The one transferable lesson is more
of a reminder than news — **expensive queries need a budget**. For us it means that parameters such as the
maximum number of expanded terms in prefix and typo expansion (§4.6, where an upper limit is already
proposed) are not a detail but protection against a query that on its own generates unbearable work.

---

## 10. Summary of consequences for our design

Ordered by how substantively each changes what stands in the research and in the plans today. At the top
is what I recommend incorporating; at the bottom what merely confirms what is already decided.

### A. New design options not in the plans today

**A1 — A third option for maintaining the impact sidecar: invalidate and compute lazily (P2, §4.2).**
A star-tree is used on the query path in OpenSearch only when it passes a gate, and otherwise the query
falls back silently to normal evaluation (`StarTreeQueryHelper.java:53`). The structure is a pure
acceleration, never a bearer of correctness. Transferred onto our sidecar: a write **marks the affected
chunks invalid** (cheap), a read **computes them lazily** or skips the lane. Given Z7 (fulltext under 1 %
of queries) that asymmetry is in our favour. P2 knows only two options today — maintain via COW, or give
up read-your-writes; this is a third and in my view the cheapest.

**A2 — K as a multiple of the page size, not a constant (O2, P4, §4.3).**
`OversampleRequestProcessor` multiplies the requested size by a factor and hides the original value in the
context; `TruncateHitsResponseProcessor` restores it at the end. Our design has K as a fixed 1000.
Recommendation: **`max(minimum, multiple × page size)`**, plus a note that the enlarged size must not fall
through into the response — they solve it with the request's context, not with the signature.

**A3 — A dedicated field type for embeddings instead of `float[]` as an attribute type (P6,
schema-design).** `FieldTypeCapabilities.Capability` shows that vector search is carried elsewhere as **a
separate capability with its own storage**, not as an attribute that happens to contain an array of
numbers. P6 §5.4 today reports that `float[]` is not a supported attribute type and takes it as a closed
path. A dedicated field type for embeddings — not filterable, not sortable, not returned as an ordinary
value — bypasses the eight-layer operation of the `evita-schema-change` skill and is substantially
cheaper. It fundamentally changes P6's effort estimate.

**A4 — Phase 2 ought to require a cheap approximation for every expensive lane (§4.3, P4).**
The pattern "a cheap indexed prefilter in conjunction with an expensive exact evaluation" appears in
OpenSearch three times independently: the two-phase iterator at `match_only_text`, `prefilter_field` at
derived fields, and phasing as such. P4 proposes something like that ad hoc for proximity; I recommend
elevating it into **a requirement on the pluggable re-rank interface**.

**A5 — Phrases: a filter, or a boost? (P4, Z4, Z8).** OpenSearch uses re-analysis as **a filter verified
for every candidate**, and the result is a `ConstantScoreScorer` — i.e. zero contribution to the score. We
plan re-analysis as **a ranking lane over the top-K**. Those are different contracts with different costs:
if a phrase is a filter, the top-K is fundamentally not enough. For an e-shop a boost is defensible, for
the CMS profile (Z8) the user rather expects a filter. P4 has to decide it in advance, because it changes
what is actually measured.

**A6 — Four-level precedence of the rank profile (O1).** `SearchPipelineService.resolvePipeline()` gives a
ready-made answer to the question "per schema, or per query": an inline definition in the query > a profile
name in the query > the schema's default profile > switched off. Plus two trodden-out details: **a
reserved name for "no profile"** (otherwise the default profile cannot be suppressed) and **under
ambiguity, rather nothing** than an arbitrary choice.

**A7 — Loudly reject a change of fulltext configuration over a non-empty collection (schema-design).**
OpenSearch rejects adding a composite field to an existing index with an explicit exception
(`CompositeIndexValidator.java:37`) and generally does not allow a field's type to change. `schema-design`
§4.3 and §7 found that for us **the change passes silently**. The recommendation has an order: **first the
prohibition, then the reindexing path** — the other way round, silently inconsistent indexes arise.

**A8 — Decide the sidecar's format versioning in P1, not later.** The existence of four generations of
composite codecs and a `codec` field right in the replication checkpoint shows how seriously versioning of
side structures' binary format is taken. `§4.2` skimps on it today in a single sentence.

**A9 — Measure the memory peak during the build too, not only the size of the finished structure (P1).**
That `OffHeapStarTreeBuilder` had to come into being because the build could not be kept on the heap is a
warning: the peak during the build may be several times higher than the result. P1's criterion "RAM ≤
150 MB" measures JOL over the finished structure.

**A10 — The boost channel as a visible part of the profile, not a hidden inserted step (O8, P7).**
OpenSearch had to write a whole conflict evaluation against user processors for system-inserted ones. The
cheapest way not to have that problem is to insert nothing behind the configuration's back — the profile
then stays a complete description of how the order came about.

### B. Arguments that strengthen conclusions already written

**B1 — The DFS phase is the cost of corpus statistics quantified in the architecture (§2.1, §8).**
For the statistics to be global, an entire extra network round is needed before every query — and it is so
expensive that **it is not the default mode** (`SearchType.java:62`). At the same time it means that both
Elasticsearch and OpenSearch score by default from per-shard statistics and admittedly sacrifice score
comparability. That is the strongest ammunition for §2.1 I found: it shows that even with full knowledge
of the problem its creators chose inaccuracy as the default behaviour.

**B2 — Segment replication is a second, independent cost of the same property (§2.1, §4.5).**
For statistics to be stable between replicas, the bytes of segments have to be sent instead of documents —
which commits the whole cluster to a shared Lucene format (a verbatim quotation from the HEAD commit
message in section 5). Our model pays neither of those two costs.

**B3 — The precise formulation of our lead in determinism (§2.1).** "The index is a deterministic function
of the WAL" does not by itself explain why that is not enough for Lucene. More precisely: in Lucene what
diverges are **non-deterministic side effects** — the merge policy runs at a different time on every node,
so the share of deleted documents still counted into `docFreq` differs. We have no such side effect,
because we have no statistic it would affect.
**Beware of this point's status:** the divergence mechanism is **general knowledge and a judgement, not a
finding from this checkout** (see the reservation in section 5). It belongs in §2.1 only with a
corresponding reservation, or after support is found elsewhere. Anchored and usable without reservation are
B1 and B2.

**B4 — Parallelizing phase 1 is safe for us for a firmer reason than for them (§4.3, P1).**
OpenSearch had to prove the score's invariance with respect to slicing by construction — it reads
statistics from the top-level reader (`ContextIndexSearcher.java:555`, `:570`). For us it follows from the
definition: there is nothing to share between the parts. **But beware of a different trap**: invariance
does not hold for aggregations, because every slice truncates to `shard_size` before the merge and a new
error arises (`InternalTerms.java:467`, the flag `hasSliceLevelDocCountError` at `:232`). Our facets are
exact as long as we do not introduce a "top-N per partition" shortcut into them — which is a warning
formulated in advance.

**B5 — Calibrating expectations of parallelism (P1).** The default of at most four slices
(`SearchService.java:2109`) and the rule "parallelize only when there are aggregations"
(`DefaultSearchContext.java:1043`) are the empirical verdict of somebody who measured it at production
volumes: it pays off on the **full-set aggregation path, not on the top-K text one**. Single-threaded
first, as plan P1 says.

**B6 — Early termination and parallelism exclude each other (§2.3).** `terminate_after` in OpenSearch
switches concurrency off hard (`DefaultSearchContext.java:1085`). The decision not to implement WAND has
therefore incidentally removed one of the obstacles to parallelization for us.

**B7 — The fallback of §4.5(3) is the norm, not a concession.** An established player builds a side
structure beside the inverted index **exclusively in batch, at flush and merge, as an immutable part of
the segment** — incremental maintenance under live writes does not exist in OpenSearch. I recommend
reformulating the tone of §4.5(3): today it reads as an emergency exit, and yet it is the configuration the
star-tree has hard-wired.
**But — with two reservations so that it is not overrated:** a star-tree is a combinatorially much heavier
structure than our flat sidecar (one byte per field, term, PK triple), so from "nobody does it" one cannot
deduce "it cannot be done". And above all: **their answer presupposes segments we do not have.** "Build it
again at flush" is cheap only thanks to immutable segments; evitaDB has a live transactional index and no
flush at which it could be performed. OpenSearch does not solve our problem differently — it **does not
have** it, and its solution is not transferable. P2 therefore measures something without a comparison
point, which is a more honest formulation than "it is risky".

**B8 — §4.7 (proximity without a positional index) has a production precedent.** `match_only_text` is our
design built by somebody else and shipped to production as a full-fledged field type, even with the same
building mechanics (an analyzer plus a one-off index over the value of a single document,
`SourceFieldMatchQuery.java:101`).

**B9 — RRF is right for §5.5.** Fusion needs to see both lists whole, which is impossible inside the
scoring loop — which is why OpenSearch placed it after the end of evaluation
(`SearchPhaseResultsProcessor`). RRF works with ranks, so it needs no second pass; min-max normalization
would demand our own analogue of that seam.

**B10 — Conceive the fusion step as a named phase, not as a lane of the composite (P6, P7).**
The composite of §4.3 is a lexicographic packing and fusion does not belong in it: fusion mixes two
independent orders, whereas a lane breaks a tie. §4.3 today speaks of "mapping back into a single
`orderBy(relevance())`" without saying where that step physically lives.

**B11 — The dividing line engine versus model is drawn correctly (§1.1, §1.2, F3).**
Both rescore and function_score are in the core, LTR is a plugin. The line runs between "a scoring function
over values the engine has" and "a model learned elsewhere" — exactly where §1.1 and §1.2 place it. An
independent if weak piece of evidence.

**B12 — Explainability has two different forms and both are needed (§4.3, P7).** Solr's `[features]` and
Vespa's `match-features` export **the model's features**; `ProcessorExecutionDetail` exports **a trace of
the processing** (who got what, what they emitted, how long it took, whether it failed). "What number did
the document get in lane 3" and "which step of the profile changed the order" are different questions. The
concrete items are adoptable too — the step's duration and its status, without which there is no way of
telling that an expensive lane was silently skipped.

### C. What this checkout could not answer

An honest record of the gaps, so that nobody runs into them a second time. Four of the nine assigned areas
do not live in this repository:

| Area | Where it actually is | What of it can be verified in the core |
|---|---|---|
| Hybrid search, normalization | the `neural-search` plugin | the `SearchPhaseResultsProcessor` seam |
| k-NN, vector engines | the `k-NN` plugin | `FieldTypeCapabilities`, the ANN timeout |
| Learning-to-rank | a separate LTR plugin | nothing |
| Query insights | the `query-insights` plugin | general telemetry, `ShardSearchStats` |

Specifically **unevidenced and deliberately not written**: the normalization algorithms (min-max, L2) and
combinations (arithmetic, geometric, harmonic mean), the implementation of RRF in OpenSearch, and the
matrix of supported vector engines (Lucene HNSW, faiss, NMSLIB). I searched `docs/` and `release-notes/` —
the only mention of vectors is a technical note about a timeout expiring in
`release-notes/opensearch.release-notes-3.7.0.md`. Should the research need these claims, there are two
paths: clone `opensearch-project/neural-search` and `opensearch-project/k-NN`, or take them from the
project's documentation **with a reservation after the pattern of VK13/VK14/VK20** ("only via a web search,
without reading source code, verbatim quotations with reservation").
