# P6 — vector search measured on five corpora

The results of the final P6 run: every scenario of the measurement harness (`p6-measurement-harness.md`) and the
JMH latency benchmark, run once on each of five corpora. The document is ordered by **what is measured**; inside
each measurement, by **corpus**; inside each corpus, by **library and configuration**. Each measurement opens with
what it measures, why the decision needs it, and what we expect to see — written for a reader who knows what a
search index is, but not how a vector index works.

`p6-implementation-proposal.md` says what follows from these measurements for the implementation: what to
build, from which library parts, and the traps found on the way. The earlier exploratory runs on one or two corpora
are superseded by this run.

> **Status:** complete. Every table below is generated from the raw outputs of the run (kept outside the
> repository with the customer data); the prose around them is written by hand.

## Before reading

### Vector search in five sentences

Every record (a product, an article) is turned by an embedding model into a vector of 2,048 numbers; records with
similar meaning get vectors pointing in similar directions. A search turns the query text into a vector too and
asks for the ten records whose vectors are the closest. Comparing the query with every record (the **exact scan**)
gives the right answer, but costs time proportional to the corpus. An **HNSW graph** links each record to a few dozen
similar ones; a search **walks** the graph from an entry point towards the query and looks at only a thousand
or so records, at the price of sometimes missing one of the true ten. **Codes** (quantization) shrink each
vector to a fraction of its size so that the walk is cheaper; the best candidates the walk finds are then
**reranked** with the full vectors.

### Terms used in the tables

| term | meaning |
|---|---|
| **recall@10** | the share of the true ten nearest records (by the exact scan) that the index returned. 1.000 = nothing lost. The criterion is **≥ 0.95** (spec §1.2). |
| **oversampling** (1×, 2×, 4×, 8×) | how many walk candidates are reranked with the full float32 vectors: `factor × 10`. Higher costs a little time and repairs what the codes got wrong. |
| **visited** | the mean number of records whose distance the walk computed; the main driver of latency. |
| **p50 / p95 / p99** | the latency that half / 95 % / 99 % of queries stay under, in ms, one query on one thread. The criterion is **p95 < 10 ms at 1M vectors** (spec §1.2). |
| **heap** | memory inside the JVM, the part the garbage collector manages and a container limit counts first. |
| **mapped (off-heap)** | files mapped into memory (`mmap`). The operating system keeps them in the page cache and can drop them under pressure; **resident** is the part that is in RAM at that moment. |
| **B/vec** | bytes per indexed vector, the figure that scales a result to other corpus sizes. |
| **float32** | the full vector, 4 bytes × 2,048 = 8,192 B. |
| **OSQ 1/4/7-bit** | Lucene's optimized scalar quantization: each number kept in 1, 4 or 7 bits (32×, 8×, ~4.6× smaller). |
| **PQ 256 × 8**, **BQ** | jVector's product quantization (256 B per vector) and binary quantization (1 bit per number). |
| **hit@10** | with users' clicks only: the share of (query, clicked record) pairs whose record lands in the top ten. Measures the embedding together with the index. |
| **post-filter / in-walk / ACORN / planner** | ways to search only a subset (a filter); described in the filtered measurements. |

### The corpora

All five are exports of production evitaDB catalogs with 2,048-dimensional text embeddings of one model. None
reaches 1M vectors; the two largest stand in for the 1M dataset of the spec. Corpus D's raw vectors (3.25 GB) are
about 1.1× the bytes of 1M × 768, the spec's 1M configuration, so its **memory** figures are comparable; its
latency and scan figures are harsher than 1M × 768 would be, because every distance costs 2,048 numbers, not 768.

| | A | B | C | D | E |
|---|---|---|---|---|---|
| content | e-commerce, products | e-commerce, products, four locales | news articles, one locale | news articles, one locale | e-commerce, products with short descriptions, two locales |
| size class | small | medium | large | large (the 1M stand-in) | large |
| indexed vectors | 14,837 | 22,895 | 214,410 | 416,559 | 312,787 |
| records | 14,813 | 22,832 | 206,703 | 412,000 | 312,145 |
| raw float32 block | 116 MB | 179 MB | 1,675 MB | 3,254 MB | 2,444 MB |
| queries | 107 real search texts | 1,000 real search texts | 1,000 held out of the corpus | 1,000 held out of the corpus | 1,000 held out of the corpus |
| users' clicks | **yes** (227 resolved clicks) | **yes** (1,883 resolved clicks) | no | no | no |
| real filter | products of one locale, 1.66 % | products of one locale, 29.7 % | none | none | products of one collection, 38.9 % |

- **Real queries** (A, B) are texts users typed into the production search, embedded by the production embedding
  service; the clicks that followed them say which record the user wanted.
- **Held-out queries** (C, D, E) stand in where no search log exists: 1,000 records are drawn at random (seed 42)
  and taken out of the corpus with all their vectors; the first vector of each (for an article its title and lead)
  becomes a query. Taken *out*, because a query left in the corpus finds itself as its own nearest neighbour and
  would inflate every recall. They measure the index faithfully; they say nothing about relevance, since a record's
  own text is a better-matched query than anything a user types.
- **The real filter** is the collection the clicks point to (A, B) or the largest product collection (E). Corpora
  C and D are one collection each, so their filtered behaviour comes from the synthetic filter matrix only.

### Environment

- JDK 21.0.12, 12 logical cores, 30 GB RAM; Lucene 10.6.0, jVector 4.0.2. SIMD confirmed in every run (jVector
  `PanamaVectorizationProvider`, Lucene "Java vector incubator API enabled").
- Heap 4 GB for A and B, 8 GB for C, D and E; `jvector-heap` 4 / 10 / 10 / 12 / 10 GB (A / B / C / D / E), because
  it holds the raw vectors on the heap; `churn` 6 GB for A and B.
- One measurement at a time, nothing else running. Run between 2026-11-06 and 2026-11-07, follow-ups on 2026-11-09.
- Graph parameters fixed for every corpus (harness doc, part 5): Lucene `M = 16`, beam 100, one thread, seed 42;
  jVector degree 32, beam 100, `alpha = 1.2`, hierarchy on, all cores. Walk depth 100, ten results.
- Latencies in the harness tables are one-thread timings after one warm-up pass, good to an order of magnitude;
  the criterion is checked against JMH (measurement 3).

### Results at a glance

- **Memory.** The graph is the only part that has to be on the heap: about 380 B per vector in jVector (degree
  32) and 260–350 B in Lucene (`M = 16`), 151 MB and 139 MB at corpus D's 416k vectors. The raw vectors (8,192 B
  each) and the codes live in mapped files at no loss of recall. A graph written into our own file takes 136 B per
  node, is written in about 150 ms, maps in 0.2 ms and leaves nothing on the heap.
- **Recall.** At 4× oversampling every Lucene code (OSQ 1, 4 and 7 bits, float32) reaches recall@10 ≥ 0.95 on
  corpora B–E. Corpus A is the hard one: its float32 walk itself reaches only 0.957, OSQ 4-bit 0.945, OSQ 1-bit
  0.910 (0.913 at 8×): with 107 short queries the walk, not the code, limits A. jVector's PQ stays below
  0.95 at 4× on A, B and E.
- **Latency (JMH).** Every unfiltered walk has p95 ≤ 1.6 ms, even on corpus D; OSQ 1-bit 0.45 ms. The exact
  scan crosses 10 ms between 23k and 214k vectors (49 ms on C, 110 ms on D): the graph is needed.
- **Filters.** With the real filters of B and E (30–39 % of the corpus), in-walk filtering keeps recall@10 ≥ 0.98
  at p95 ≤ 2 ms. With A's 1.66 % filter, in-walk on float32 costs p95 12 ms while the planner's exact scan of
  the members costs 0.2 ms. Post-filter starves below about 50 %. **ACORN loses recall** under
  most filters of 50 % and below, and under the real filters of A and E (0.39 and 0.14); a larger `M` does not help,
  because Lucene's graph stays equally sparse (measurement 6). On the large corpora,
  in-walk with a correlated filter of 10 % reaches p95 25–49 ms. The exact scan of the members is cheaper than
  in-walk from 5–10 % down on A–C, from 1–5 % on D and E. The planner's switch at 100 members is far too low:
  it needs a switch on the member count relative to the corpus.
- **Our searcher** returns the same recall as jVector's `GraphSearcher` (within ±0.003) with 5–10 % more visited
  nodes, over jVector's graph and over Lucene's OSQ codes alike; every walk stops when cancelled.
- **Hierarchy.** No systematic recall difference between the flat graph and the hierarchy at any size up to
  416k vectors (within ±0.007, in both directions, below jVector's run-to-run noise); `alpha = 1.0` builds
  about twice as fast with half the degree and the same recall at 4×.
- **Churn.** jVector takes concurrent inserts, deletes and `cleanup()` with zero errors and recall at the level
  of a fresh build; readers do see 19–32 just-deleted nodes during a replacement. Lucene's graph is not made for
  concurrent writes: readers hit a `ClassCastException` (B) and an `ArrayIndexOutOfBoundsException` (C, D).
- **The library combination** (jVector graph, our searcher, Lucene OSQ codes) has the best recall (measurement 8) at p95
  ≤ 1.1 ms (JMH, measurement 4) and behaves under churn like jVector alone: no errors, transient dead nodes to filter
  (measurement 11).
- **Copy and swap** (measurement 12): a copy of the live graph takes 13 ms–2.1 s and doubles the graph's heap; inserts
  into it cost what they cost in place; deletes are expensive in both libraries (Lucene's repair 23–53 s for 10,000,
  jVector 2.5–7.9 s for one on the large corpora once its default graph refinement is switched off, plus 2–4 ms per
  deleted node). Batches only, deletes as liveness bits.
- **Transactions** (measurement 13): one shared graph per committed version, each transaction's own inserts scanned
  exactly beside it and its deletes in the walk's accept bits. No loss of recall; 1,000 own inserts add 0.12–0.14
  ms to a search, 10,000 add 1.2–1.3 ms.
- **Clicks.** The index costs little relevance: on B, pairs hit@10 is 0.121 for the exact scan and 0.110–0.114
  for every code at 4×; with the filter, 0.220 against 0.205–0.207. The embedding, not the index, bounds
  relevance.

## Measurements

### 1. Memory and build time

**What is measured.** How much memory a vector index needs and where it sits: on the heap, mapped off the heap, or
on disk; and how long the graph takes to build. Each library is measured in the arrangements evitaDB could use:
raw vectors on the heap, raw vectors in a mapped file, and the whole index as an on-disk file.

**Why.** evitaDB runs in containers with a fixed memory limit next to all its other indexes. A vector index that
keeps the raw vectors on the heap needs 8 KB per record on the heap; at 1M records that alone is 8 GB. The decision
needs to know which parts must be on the heap (the graph), which can live in mapped files that the operating system
manages (the raw vectors, the codes), and what a build costs.

**What we expect.** The graph costs a few hundred bytes per vector on the heap in both libraries; the raw vectors
cost 8,192 B per vector wherever they are; mapping them takes them off the heap at no loss of recall. Build time
grows a little faster than linearly with the corpus; jVector builds on all cores, Lucene on one.

**Setup.** One scenario per JVM. The sanity recall@10 of measurements 1 and 2 uses 100 query vectors: on A and B
the first 100 of the export's visual embeddings (out of the corpus), on C, D and E the first 100 held-out queries
(harness doc, part 3). It is therefore not comparable with measurement 3, which uses the text queries. Graph on heap is
JOL's owned size of the graph object; the jVector codes are measured with the raw vectors mapped.

#### Corpus A — 14,837 vectors, small, real clicks

##### jVector

| arrangement | build s | graph on heap MB (B/vec) | heap peak during build MB | raw vectors on heap MB | mapped MB / resident MB | file on disk MB | recall@10 (sanity) |
|---|---|---|---|---|---|---|---|
| raw vectors on the heap (`jvector-heap`) | 3.1 | 5.38 (380) | 304 | 117.09 | — | — | 0.9960 |
| raw vectors mapped (`jvector-mmap`) | 5.5 | 5.67 (401) | 674 | 0.01 | 116 / 116 | — | 0.9960 |
| on-disk index, inline vectors (`jvector-ondisk`) | — | 0.08 (6) | — | — | 118 / 29 after one query | 118 | 0.9960 |

##### jVector codes

| code | on heap MB (B/vec) | recall@10 before rerank | recall@10 at 1× / 2× / 4× / 8× |
|---|---|---|---|
| PQ 256 × 8 | 6.33 (448) | 0.7690 | 0.7690 / 0.9160 / 0.9680 / 0.9830 |
| BQ | 4.33 (306) | 0.6020 | 0.6020 / 0.7880 / 0.9050 / 0.9690 |

##### Lucene

| arrangement | build s | heap after open MB | graph on heap MB (B/vec) | `.vec` MB | `.veq` MB | `.vex` MB | on disk total MB | recall@10 (sanity) |
|---|---|---|---|---|---|---|---|---|
| `util.hnsw` as a library, raw mapped (`lucene-hnsw`) | 4.1 | — | 3.79 (268) | — | — | — | — | 0.9940 |
| `IndexWriter`, float32 (`lucene-float32`) | 3.1 | 0.06 | — | 115.91 | — | 0.25 | 116 | 0.9940 |
| `IndexWriter`, OSQ 1-bit (`lucene-osq1`) | 4.0 | 0.07 | — | 115.91 | 3.85 | 0.25 | 120 | 0.6980 |
| `IndexWriter`, OSQ 2-bit (`lucene-osq2`) | 3.6 | 0.07 | — | 115.91 | 7.47 | 0.25 | 124 | 0.7730 |
| `IndexWriter`, OSQ 4-bit (`lucene-osq4`) | 3.7 | 0.07 | — | 115.91 | 14.72 | 0.25 | 131 | 0.8660 |
| `IndexWriter`, OSQ 7-bit (`lucene-osq7`) | 3.7 | 0.07 | — | 115.91 | 29.21 | 0.25 | 145 | 0.9230 |
| `IndexWriter`, OSQ 8-bit (`lucene-osq8`) | 3.6 | 0.07 | — | 115.91 | 29.21 | 0.25 | 145 | 0.9330 |

#### Corpus B — 22,895 vectors, medium, real clicks

##### jVector

| arrangement | build s | graph on heap MB (B/vec) | heap peak during build MB | raw vectors on heap MB | mapped MB / resident MB | file on disk MB | recall@10 (sanity) |
|---|---|---|---|---|---|---|---|
| raw vectors on the heap (`jvector-heap`) | 4.6 | 8.34 (382) | 333 | 180.80 | — | — | 0.9050 |
| raw vectors mapped (`jvector-mmap`) | 7.9 | 8.57 (392) | 655 | 0.01 | 179 / 179 | — | 0.9290 |
| on-disk index, inline vectors (`jvector-ondisk`) | — | 0.12 (6) | — | — | 182 / 26 after one query | 182 | 0.9290 |

##### jVector codes

| code | on heap MB (B/vec) | recall@10 before rerank | recall@10 at 1× / 2× / 4× / 8× |
|---|---|---|---|
| PQ 256 × 8 | 8.33 (382) | 0.5710 | 0.5710 / 0.7350 / 0.8340 / 0.8840 |
| BQ | 6.44 (295) | 0.3560 | 0.3560 / 0.5070 / 0.6620 / 0.7720 |

##### Lucene

| arrangement | build s | heap after open MB | graph on heap MB (B/vec) | `.vec` MB | `.veq` MB | `.vex` MB | on disk total MB | recall@10 (sanity) |
|---|---|---|---|---|---|---|---|---|
| `util.hnsw` as a library, raw mapped (`lucene-hnsw`) | 7.2 | — | 6.40 (293) | — | — | — | — | 0.9140 |
| `IndexWriter`, float32 (`lucene-float32`) | 5.6 | 0.06 | — | 178.87 | — | 0.54 | 179 | 0.9140 |
| `IndexWriter`, OSQ 1-bit (`lucene-osq1`) | 6.3 | 0.08 | — | 178.87 | 5.94 | 0.54 | 185 | 0.5100 |
| `IndexWriter`, OSQ 2-bit (`lucene-osq2`) | 6.5 | 0.07 | — | 178.87 | 11.53 | 0.54 | 191 | 0.6610 |
| `IndexWriter`, OSQ 4-bit (`lucene-osq4`) | 6.3 | 0.07 | — | 178.87 | 22.71 | 0.54 | 202 | 0.7800 |
| `IndexWriter`, OSQ 7-bit (`lucene-osq7`) | 6.3 | 0.07 | — | 178.87 | 45.07 | 0.54 | 224 | 0.8510 |
| `IndexWriter`, OSQ 8-bit (`lucene-osq8`) | 6.4 | 0.07 | — | 178.87 | 45.07 | 0.54 | 224 | 0.8370 |

#### Corpus C — 214,410 vectors, large, held-out queries

##### jVector

| arrangement | build s | graph on heap MB (B/vec) | heap peak during build MB | raw vectors on heap MB | mapped MB / resident MB | file on disk MB | recall@10 (sanity) |
|---|---|---|---|---|---|---|---|
| raw vectors on the heap (`jvector-heap`) | 71.0 | 77.64 (380) | 2,834 | 1,687.83 | — | — | 0.9940 |
| raw vectors mapped (`jvector-mmap`) | 117.4 | 77.91 (381) | 1,738 | 0.01 | 1,675 / 1,675 | — | 0.9930 |
| on-disk index, inline vectors (`jvector-ondisk`) | — | 1.10 (5) | — | — | 1,704 / 76 after one query | 1,704 | 0.9930 |

##### jVector codes

| code | on heap MB (B/vec) | recall@10 before rerank | recall@10 at 1× / 2× / 4× / 8× |
|---|---|---|---|
| PQ 256 × 8 | 58.33 (285) | 0.8040 | 0.8040 / 0.9660 / 0.9890 / 0.9930 |
| BQ | 56.98 (279) | 0.7960 | 0.7960 / 0.9460 / 0.9800 / 0.9880 |

##### Lucene

| arrangement | build s | heap after open MB | graph on heap MB (B/vec) | `.vec` MB | `.veq` MB | `.vex` MB | on disk total MB | recall@10 (sanity) |
|---|---|---|---|---|---|---|---|---|
| `util.hnsw` as a library, raw mapped (`lucene-hnsw`) | 204.7 | — | 71.94 (352) | — | — | — | — | 0.9980 |
| `IndexWriter`, float32 (`lucene-float32`) | 166.5 | 0.11 | — | 1,675.08 | — | 9.45 | 1,685 | 0.9980 |
| `IndexWriter`, OSQ 1-bit (`lucene-osq1`) | 171.7 | 0.12 | — | 1,675.08 | 55.62 | 9.45 | 1,740 | 0.8660 |
| `IndexWriter`, OSQ 2-bit (`lucene-osq2`) | 171.7 | 0.12 | — | 1,675.08 | 107.96 | 9.45 | 1,793 | 0.8970 |
| `IndexWriter`, OSQ 4-bit (`lucene-osq4`) | 173.2 | 0.12 | — | 1,675.08 | 212.66 | 9.45 | 1,897 | 0.9450 |
| `IndexWriter`, OSQ 7-bit (`lucene-osq7`) | 174.2 | 0.12 | — | 1,675.08 | 422.04 | 9.45 | 2,107 | 0.9780 |
| `IndexWriter`, OSQ 8-bit (`lucene-osq8`) | 170.6 | 0.12 | — | 1,675.08 | 422.04 | 9.45 | 2,107 | 0.9820 |

#### Corpus D — 416,559 vectors, large (1M stand-in), held-out queries

##### jVector

| arrangement | build s | graph on heap MB (B/vec) | heap peak during build MB | raw vectors on heap MB | mapped MB / resident MB | file on disk MB | recall@10 (sanity) |
|---|---|---|---|---|---|---|---|
| raw vectors on the heap (`jvector-heap`) | 147.4 | 150.74 (380) | 5,063 | 3,273.86 | — | — | 0.9920 |
| raw vectors mapped (`jvector-mmap`) | 223.4 | 151.04 (380) | 1,584 | 0.01 | 3,254 / 3,254 | — | 0.9930 |
| on-disk index, inline vectors (`jvector-ondisk`) | — | 2.14 (5) | — | — | 333,065 / 30 after one query | 3,310 | 0.9930 |

##### jVector codes

| code | on heap MB (B/vec) | recall@10 before rerank | recall@10 at 1× / 2× / 4× / 8× |
|---|---|---|---|
| PQ 256 × 8 | 106.33 (268) | 0.7970 | 0.7970 / 0.9530 / 0.9880 / 0.9930 |
| BQ | 110.15 (277) | 0.7850 | 0.7850 / 0.9340 / 0.9830 / 0.9900 |

##### Lucene

| arrangement | build s | heap after open MB | graph on heap MB (B/vec) | `.vec` MB | `.veq` MB | `.vex` MB | on disk total MB | recall@10 (sanity) |
|---|---|---|---|---|---|---|---|---|
| `util.hnsw` as a library, raw mapped (`lucene-hnsw`) | 427.8 | — | 138.79 (349) | — | — | — | — | 0.9940 |
| `IndexWriter`, float32 (`lucene-float32`) | 400.6 | 0.12 | — | 3,254.37 | — | 17.51 | 3,272 | 0.9930 |
| `IndexWriter`, OSQ 1-bit (`lucene-osq1`) | 404.0 | 0.13 | — | 3,254.37 | 108.06 | 17.71 | 3,380 | 0.8610 |
| `IndexWriter`, OSQ 2-bit (`lucene-osq2`) | 416.5 | 0.13 | — | 3,254.37 | 209.75 | 17.50 | 3,482 | 0.9030 |
| `IndexWriter`, OSQ 4-bit (`lucene-osq4`) | 405.2 | 0.13 | — | 3,254.37 | 413.15 | 17.47 | 3,685 | 0.9500 |
| `IndexWriter`, OSQ 7-bit (`lucene-osq7`) | 465.0 | 0.13 | — | 3,254.37 | 819.95 | 17.50 | 4,092 | 0.9730 |
| `IndexWriter`, OSQ 8-bit (`lucene-osq8`) | 10,794.7 | 0.13 | — | 3,254.37 | 819.95 | 17.50 | 4,092 | 0.9800 |

#### Corpus E — 312,787 vectors, large, short product texts, held-out queries

##### jVector

| arrangement | build s | graph on heap MB (B/vec) | heap peak during build MB | raw vectors on heap MB | mapped MB / resident MB | file on disk MB | recall@10 (sanity) |
|---|---|---|---|---|---|---|---|
| raw vectors on the heap (`jvector-heap`) | 68.2 | 112.91 (378) | 5,798 | 2,457.84 | — | — | 0.9660 |
| raw vectors mapped (`jvector-mmap`) | 112.7 | 113.15 (379) | 1,785 | 0.01 | 2,444 / 2,444 | — | 0.9560 |
| on-disk index, inline vectors (`jvector-ondisk`) | — | 1.53 (5) | — | — | 310,256 / 76 after one query | 2,486 | 0.9560 |

##### jVector codes

| code | on heap MB (B/vec) | recall@10 before rerank | recall@10 at 1× / 2× / 4× / 8× |
|---|---|---|---|
| PQ 256 × 8 | 82.34 (276) | 0.6960 | 0.6960 / 0.8490 / 0.9040 / 0.9230 |
| BQ | 82.78 (278) | 0.8420 | 0.8420 / 0.9500 / 0.9620 / 0.9640 |

##### Lucene

| arrangement | build s | heap after open MB | graph on heap MB (B/vec) | `.vec` MB | `.veq` MB | `.vex` MB | on disk total MB | recall@10 (sanity) |
|---|---|---|---|---|---|---|---|---|
| `util.hnsw` as a library, raw mapped (`lucene-hnsw`) | 116.7 | — | 77.79 (261) | — | — | — | — | 0.9680 |
| `IndexWriter`, float32 (`lucene-float32`) | 106.4 | 0.09 | — | 2,443.65 | — | 5.65 | 2,449 | 0.9690 |
| `IndexWriter`, OSQ 1-bit (`lucene-osq1`) | 127.8 | 0.10 | — | 2,443.65 | 81.14 | 6.29 | 2,531 | 0.8830 |
| `IndexWriter`, OSQ 2-bit (`lucene-osq2`) | 132.1 | 0.10 | — | 2,443.65 | 157.50 | 6.18 | 2,607 | 0.8370 |
| `IndexWriter`, OSQ 4-bit (`lucene-osq4`) | 124.4 | 0.10 | — | 2,443.65 | 310.23 | 5.65 | 2,760 | 0.9280 |
| `IndexWriter`, OSQ 7-bit (`lucene-osq7`) | 132.6 | 0.10 | — | 2,443.65 | 615.68 | 5.65 | 3,065 | 0.9420 |
| `IndexWriter`, OSQ 8-bit (`lucene-osq8`) | 2,473.7 | 0.10 | — | 2,443.65 | 615.68 | 5.65 | 3,065 | 0.9570 |

#### Per vector, across corpora

| component | A | B | C | D | E |
|---|---|---|---|---|---|
| jVector graph, B/vec | 401 | 392 | 381 | 380 | 379 |
| Lucene graph, B/vec | 268 | 293 | 352 | 349 | 261 |
| jVector build s per 100k vectors (all cores) | 36.9 | 34.3 | 54.8 | 53.6 | 36.0 |
| Lucene build s per 100k vectors (one thread) | 27.7 | 31.4 | 95.5 | 102.7 | 37.3 |

### 2. Our code file and the mapped raw block (the seams)

**What is measured.** evitaDB would not use either library as a whole index: it would keep the codes in a file of
its own, the raw vectors in its own mapped block, and borrow only the graph and the scoring. These scenarios wire
the libraries that way and measure memory, recall and latency of the result.

**Why.** It shows that the split evitaDB needs (spec §2.6) works with each library, and what it costs compared with
the library's own index.

**What we expect.** The same recall as the library's own index of the same code, a heap that holds only the graph,
and the codes and raw vectors resident as mapped files.

#### Corpus A — 14,837 vectors, small, real clicks

| configuration | code file MB (B/vec) | graph on heap MB (B/vec) | build s | recall@10 1× / 2× / 4× / 8× | library's own index, 4× | p50 / p95 ms at 4× | mapped MB | process RSS MB |
|---|---|---|---|---|---|---|---|---|
| Lucene + OSQ 1-bit (`lucene-wired-osq1`) | 3.86 (273) | 3.79 (268) | 3.9 | 0.6980 / 0.8610 / 0.9350 / 0.9720 | 0.9350 | 0.11 / 0.13 | 120 | 281 |
| Lucene + OSQ 4-bit (`lucene-wired-osq4`) | 14.72 (1,041) | 3.79 (268) | 3.9 | 0.8660 / 0.9670 / 0.9830 / 0.9840 | 0.9830 | 0.15 / 0.20 | 131 | 290 |
| Lucene + OSQ 7-bit (`lucene-wired-osq7`) | 29.21 (2,065) | 3.79 (268) | 3.9 | 0.9230 / 0.9900 / 0.9930 / 0.9950 | 0.9930 | 0.28 / 0.33 | 145 | 298 |
| jVector + PQ (`jvector-wired`) | 5.62 (397) | 5.67 (401) | 5.7 | 0.7580 / 0.9170 / 0.9640 / 0.9890 | 0.9680 | 0.53 / 0.59 | 116 | 387 |
| jVector + BQ (`jvector-wired`) | 3.63 (257) | 5.67 (401) | 5.7 | 0.6020 / 0.7880 / 0.9050 / 0.9710 | 0.9050 | 0.10 / 0.12 | 116 | 387 |

#### Corpus B — 22,895 vectors, medium, real clicks

| configuration | code file MB (B/vec) | graph on heap MB (B/vec) | build s | recall@10 1× / 2× / 4× / 8× | library's own index, 4× | p50 / p95 ms at 4× | mapped MB | process RSS MB |
|---|---|---|---|---|---|---|---|---|
| Lucene + OSQ 1-bit (`lucene-wired-osq1`) | 5.95 (272) | 6.40 (293) | 7.5 | 0.5100 / 0.6900 / 0.8120 / 0.8810 | 0.8120 | 0.14 / 0.19 | 185 | 349 |
| Lucene + OSQ 4-bit (`lucene-wired-osq4`) | 22.72 (1,040) | 6.40 (293) | 7.3 | 0.7800 / 0.8920 / 0.9090 / 0.9090 | 0.9090 | 0.16 / 0.23 | 202 | 373 |
| Lucene + OSQ 7-bit (`lucene-wired-osq7`) | 45.07 (2,064) | 6.40 (293) | 7.7 | 0.8510 / 0.8980 / 0.9000 / 0.9000 | 0.9000 | 0.34 / 0.54 | 224 | 386 |
| jVector + PQ (`jvector-wired`) | 7.59 (348) | 8.55 (391) | 8.0 | 0.5700 / 0.7260 / 0.8280 / 0.8580 | 0.8340 | 0.56 / 0.68 | 179 | 574 |
| jVector + BQ (`jvector-wired`) | 5.60 (256) | 8.55 (391) | 8.0 | 0.3410 / 0.4770 / 0.6310 / 0.7440 | 0.6620 | 0.13 / 0.19 | 179 | 574 |

#### Corpus C — 214,410 vectors, large, held-out queries

| configuration | code file MB (B/vec) | graph on heap MB (B/vec) | build s | recall@10 1× / 2× / 4× / 8× | library's own index, 4× | p50 / p95 ms at 4× | mapped MB | process RSS MB |
|---|---|---|---|---|---|---|---|---|
| Lucene + OSQ 1-bit (`lucene-wired-osq1`) | 55.63 (272) | 71.94 (352) | 200.9 | 0.8660 / 0.9930 / 0.9960 / 0.9980 | 0.9960 | 0.26 / 0.35 | 1,731 | 2,180 |
| Lucene + OSQ 4-bit (`lucene-wired-osq4`) | 212.66 (1,040) | 71.94 (352) | 203.2 | 0.9450 / 0.9970 / 0.9970 / 0.9970 | 0.9970 | 0.42 / 0.60 | 1,888 | 2,309 |
| Lucene + OSQ 7-bit (`lucene-wired-osq7`) | 422.05 (2,064) | 71.94 (352) | 210.1 | 0.9780 / 0.9980 / 0.9980 / 0.9980 | 0.9980 | 0.86 / 1.26 | 2,097 | 2,521 |
| jVector + PQ (`jvector-wired`) | 54.35 (266) | 77.75 (380) | 111.3 | 0.8090 / 0.9530 / 0.9900 / 0.9920 | 0.9890 | 0.72 / 0.94 | 1,675 | 2,391 |
| jVector + BQ (`jvector-wired`) | 52.35 (256) | 77.75 (380) | 111.3 | 0.7970 / 0.9450 / 0.9790 / 0.9870 | 0.9800 | 0.20 / 0.29 | 1,675 | 2,391 |

#### Corpus D — 416,559 vectors, large (1M stand-in), held-out queries

| configuration | code file MB (B/vec) | graph on heap MB (B/vec) | build s | recall@10 1× / 2× / 4× / 8× | library's own index, 4× | p50 / p95 ms at 4× | mapped MB | process RSS MB |
|---|---|---|---|---|---|---|---|---|
| Lucene + OSQ 1-bit (`lucene-wired-osq1`) | 108.06 (272) | 138.79 (349) | 441.2 | 0.8610 / 0.9840 / 0.9940 / 0.9940 | 0.9910 | 0.33 / 0.42 | 3,362 | 4,043 |
| Lucene + OSQ 4-bit (`lucene-wired-osq4`) | 413.16 (1,040) | 138.79 (349) | 439.1 | 0.9500 / 0.9930 / 0.9940 / 0.9940 | 0.9930 | 0.48 / 0.65 | 3,668 | 4,349 |
| Lucene + OSQ 7-bit (`lucene-wired-osq7`) | 819.96 (2,064) | 138.79 (349) | 430.7 | 0.9730 / 0.9940 / 0.9940 / 0.9940 | 0.9940 | 0.89 / 1.25 | 4,074 | 4,719 |
| jVector + PQ (`jvector-wired`) | 103.70 (261) | 150.72 (379) | 229.8 | 0.8030 / 0.9570 / 0.9870 / 0.9890 | 0.9880 | 0.87 / 1.10 | 3,254 | 4,493 |
| jVector + BQ (`jvector-wired`) | 101.71 (256) | 150.72 (379) | 229.8 | 0.7860 / 0.9350 / 0.9830 / 0.9910 | 0.9830 | 0.29 / 0.43 | 3,254 | 4,493 |

#### Corpus E — 312,787 vectors, large, short product texts, held-out queries

| configuration | code file MB (B/vec) | graph on heap MB (B/vec) | build s | recall@10 1× / 2× / 4× / 8× | library's own index, 4× | p50 / p95 ms at 4× | mapped MB | process RSS MB |
|---|---|---|---|---|---|---|---|---|
| Lucene + OSQ 1-bit (`lucene-wired-osq1`) | 81.14 (272) | 77.79 (261) | 110.5 | 0.8960 / 0.9650 / 0.9680 / 0.9680 | 0.9550 | 0.16 / 0.24 | 2,525 | 3,001 |
| Lucene + OSQ 4-bit (`lucene-wired-osq4`) | 310.24 (1,040) | 77.79 (261) | 110.9 | 0.9320 / 0.9660 / 0.9680 / 0.9680 | 0.9630 | 0.17 / 0.27 | 2,754 | 3,185 |
| Lucene + OSQ 7-bit (`lucene-wired-osq7`) | 615.69 (2,064) | 77.79 (261) | 111.6 | 0.9470 / 0.9580 / 0.9580 / 0.9580 | 0.9530 | 0.28 / 0.44 | 3,059 | 3,331 |
| jVector + PQ (`jvector-wired`) | 78.37 (263) | 113.15 (379) | 116.1 | 0.7190 / 0.8760 / 0.9270 / 0.9400 | 0.9040 | 0.55 / 0.72 | 2,444 | 3,470 |
| jVector + BQ (`jvector-wired`) | 76.37 (256) | 113.15 (379) | 116.1 | 0.8320 / 0.9440 / 0.9570 / 0.9590 | 0.9620 | 0.12 / 0.20 | 2,444 | 3,470 |

### 3. Recall per code and oversampling, unfiltered

**What is measured.** For every code, how many of the true ten nearest records a search returns, depending on how
many candidates are reranked with the full vectors, and how long it takes. All queries search the whole corpus.

**Why.** The code decides the memory per vector (measurement 1), and the oversampling decides how much of the lost
accuracy is recovered. The decision needs the cheapest code and factor that keep recall@10 ≥ 0.95 on every corpus,
not only on the one it was tuned on.

**What we expect.** float32 near 0.99; the coarse codes (OSQ 1-bit, BQ) clearly lower at 1× and back above 0.95
from 2× or 4×; latency roughly proportional to the bytes the walk reads, so the coarser the code the faster.

#### Corpus A — 14,837 vectors, small, real clicks

Exact scan (the reference): p50 / p95 3.35 / 3.68 ms.

##### Lucene

| code | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|
| float32 | 560 | 0.957 | 0.957 | 0.957 | 0.957 | 0.22 / 0.32 |
| OSQ 1-bit | 575 | 0.800 | 0.894 | 0.910 | 0.913 | 0.09 / 0.10 |
| OSQ 4-bit | 561 | 0.911 | 0.944 | 0.945 | 0.945 | 0.10 / 0.12 |
| OSQ 7-bit | 559 | 0.946 | 0.957 | 0.957 | 0.957 | 0.25 / 0.33 |

##### jVector

| code | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|
| PQ 256 × 8 | 774 | 0.714 | 0.859 | 0.909 | 0.933 | 0.55 / 0.61 |
| BQ | 840 | 0.757 | 0.912 | 0.971 | 0.982 | 0.10 / 0.12 |

#### Corpus B — 22,895 vectors, medium, real clicks

Exact scan (the reference): p50 / p95 5.05 / 5.27 ms.

##### Lucene

| code | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|
| float32 | 1,012 | 0.974 | 0.974 | 0.974 | 0.974 | 0.42 / 0.61 |
| OSQ 1-bit | 1,021 | 0.779 | 0.932 | 0.963 | 0.966 | 0.11 / 0.14 |
| OSQ 4-bit | 1,014 | 0.911 | 0.971 | 0.972 | 0.972 | 0.15 / 0.19 |
| OSQ 7-bit | 1,014 | 0.950 | 0.975 | 0.975 | 0.975 | 0.40 / 0.54 |

##### jVector

| code | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|
| PQ 256 × 8 | 932 | 0.674 | 0.844 | 0.921 | 0.949 | 0.57 / 0.69 |
| BQ | 1,085 | 0.631 | 0.802 | 0.898 | 0.942 | 0.12 / 0.15 |

#### Corpus C — 214,410 vectors, large, held-out queries

Exact scan (the reference): p50 / p95 48.04 / 50.79 ms.

##### Lucene

| code | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|
| float32 | 1,210 | 0.996 | 0.996 | 0.996 | 0.996 | 0.89 / 1.29 |
| OSQ 1-bit | 1,231 | 0.865 | 0.985 | 0.994 | 0.995 | 0.27 / 0.36 |
| OSQ 4-bit | 1,215 | 0.951 | 0.996 | 0.996 | 0.996 | 0.41 / 0.56 |
| OSQ 7-bit | 1,213 | 0.977 | 0.996 | 0.996 | 0.996 | 0.72 / 1.03 |

##### jVector

| code | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|
| PQ 256 × 8 | 1,275 | 0.809 | 0.965 | 0.993 | 0.996 | 0.76 / 0.97 |
| BQ | 1,302 | 0.790 | 0.944 | 0.984 | 0.993 | 0.25 / 0.35 |

#### Corpus D — 416,559 vectors, large (1M stand-in), held-out queries

Exact scan (the reference): p50 / p95 92.06 / 98.59 ms.

##### Lucene

| code | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|
| float32 | 1,267 | 0.990 | 0.990 | 0.990 | 0.990 | 1.00 / 1.50 |
| OSQ 1-bit | 1,291 | 0.860 | 0.980 | 0.990 | 0.991 | 0.35 / 0.49 |
| OSQ 4-bit | 1,272 | 0.946 | 0.990 | 0.990 | 0.990 | 0.46 / 0.65 |
| OSQ 7-bit | 1,265 | 0.970 | 0.989 | 0.989 | 0.989 | 0.79 / 1.15 |

##### jVector

| code | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|
| PQ 256 × 8 | 1,366 | 0.794 | 0.947 | 0.982 | 0.989 | 0.81 / 1.05 |
| BQ | 1,390 | 0.778 | 0.930 | 0.975 | 0.986 | 0.26 / 0.34 |

#### Corpus E — 312,787 vectors, large, short product texts, held-out queries

Exact scan (the reference): p50 / p95 69.06 / 75.27 ms.

##### Lucene

| code | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|
| float32 | 509 | 0.979 | 0.979 | 0.979 | 0.979 | 0.33 / 0.57 |
| OSQ 1-bit | 521 | 0.900 | 0.969 | 0.973 | 0.973 | 0.15 / 0.22 |
| OSQ 4-bit | 509 | 0.942 | 0.975 | 0.976 | 0.976 | 0.18 / 0.28 |
| OSQ 7-bit | 509 | 0.969 | 0.977 | 0.977 | 0.977 | 0.30 / 0.48 |

##### jVector

| code | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|
| PQ 256 × 8 | 654 | 0.728 | 0.875 | 0.919 | 0.937 | 0.55 / 0.73 |
| BQ | 656 | 0.863 | 0.957 | 0.970 | 0.973 | 0.14 / 0.21 |

#### Recall at 4×, across corpora

| code | A | B | C | D | E |
|---|---|---|---|---|---|
| Lucene float32 | 0.957 | 0.974 | 0.996 | 0.990 | 0.979 |
| Lucene OSQ 1-bit | 0.910 | 0.963 | 0.994 | 0.990 | 0.973 |
| Lucene OSQ 4-bit | 0.945 | 0.972 | 0.996 | 0.990 | 0.976 |
| Lucene OSQ 7-bit | 0.957 | 0.975 | 0.996 | 0.989 | 0.977 |
| jVector PQ 256 × 8 | 0.909 | 0.921 | 0.993 | 0.982 | 0.919 |
| jVector BQ | 0.971 | 0.898 | 0.984 | 0.975 | 0.970 |

#### PQ with and without global centering (follow-up run, 2026-11-09)

**What and why.** At 2,048 dimensions jVector's PQ 256 × 8 (256 subspaces of 8 dimensions, one byte each) and BQ (one
bit per dimension) both take 256 B per vector, yet BQ beats PQ on A and E above. "BQ is always worse" holds only when BQ
has fewer bits; at equal bytes neither wins by rule. PQ was trained with `globallyCenter = false`, and centering usually
helps PQ, so it was measured both ways on one jVector graph per corpus, BQ alongside. The rows come from a separate run;
jVector's run-to-run variation (±0.02, see the deviations) applies when they are compared with the table above.
| corpus | code (one jVector graph per corpus) | recall@10 1× | 2× | 4× | 8× |
|---|---|---|---|---|---|
| A | PQ 256 × 8, not centered | 0.774 | 0.915 | 0.950 | 0.959 |
| A | PQ 256 × 8, centered | 0.443 | 0.597 | 0.731 | 0.821 |
| A | BQ | 0.752 | 0.910 | 0.963 | 0.971 |
| B | PQ 256 × 8, not centered | 0.675 | 0.827 | 0.911 | 0.950 |
| B | PQ 256 × 8, centered | 0.550 | 0.720 | 0.814 | 0.891 |
| B | BQ | 0.632 | 0.796 | 0.896 | 0.942 |
| C | PQ 256 × 8, not centered | 0.821 | 0.964 | 0.989 | 0.994 |
| C | PQ 256 × 8, centered | 0.778 | 0.929 | 0.968 | 0.983 |
| C | BQ | 0.794 | 0.943 | 0.981 | 0.992 |
| D | PQ 256 × 8, not centered | 0.777 | 0.947 | 0.984 | 0.988 |
| D | PQ 256 × 8, centered | 0.759 | 0.909 | 0.963 | 0.976 |
| D | BQ | 0.780 | 0.935 | 0.979 | 0.989 |
| E | PQ 256 × 8, not centered | 0.722 | 0.856 | 0.905 | 0.921 |
| E | PQ 256 × 8, centered | 0.717 | 0.865 | 0.899 | 0.916 |
| E | BQ | 0.840 | 0.942 | 0.955 | 0.957 |

**Result.** Centering does not help PQ and on some corpora costs a lot (A: 0.731 against 0.950 at 4×). The cause is in
jVector's scorer: with centering, `PQVectors#scoreFunctionFor` scores a dot product as (q − c)·(x̂ − c) instead of q·x̂.
The difference contains c·x̂, which varies from node to node, so the ranking changes; centering is consistent only for
Euclidean distance. The uncentered PQ of the other measurements is the right setting. PQ's ceiling at 8× below BQ's on A
and E comes from the walk, which the PQ scores steer past some true neighbours, not from the rerank.

### 4. Latency under JMH

**What is measured.** The latency of one search, timed by JMH: a forked JVM per configuration, warm-up, then
sampling of every query; one thread, walk plus rerank at 4×.

**Why.** It is the number the criterion is checked against (p95 < 10 ms, spec §1.2, §4.5); the harness timings of
the other measurements are only orders of magnitude.

**What we expect.** Every walk well under 10 ms on every corpus; the exact scan growing with the corpus and
crossing 10 ms somewhere between 100k and 1M vectors — the reason a graph is needed at all.

**Setup.** `ClickSearchLatencyBenchmark`, `SampleTime`, 5 × 2 s warm-up, 5 × 4 s measurement, one fork per
configuration. Corpora A, B and E: every code × strategy (with their real filter); C and D, no filter: the
unfiltered configurations only.

#### Corpus A — 14,837 vectors, small, real clicks

| configuration | strategy | samples | p50 | p90 | p95 | p99 | p99.9 | max (ms) |
|---|---|---|---|---|---|---|---|---|
| exact float32 | all | 5,952 | 3.330 | 3.432 | 3.518 | 3.678 | 4.743 | 14.942 |
| exact float32 | filtered | 276,838 | 0.071 | 0.074 | 0.076 | 0.082 | 0.109 | 4.653 |
| Lucene float32 | all | 87,238 | 0.217 | 0.294 | 0.330 | 0.438 | 0.499 | 4.071 |
| Lucene float32 | post-filter | 90,595 | 0.209 | 0.288 | 0.322 | 0.429 | 0.495 | 5.202 |
| Lucene float32 | in-walk | 2,064 | 10.297 | 11.518 | 12.009 | 12.796 | 13.874 | 14.516 |
| Lucene float32 | acorn | 200,790 | 0.021 | 0.236 | 0.244 | 0.264 | 0.317 | 5.513 |
| Lucene float32 | planner | 123,885 | 0.158 | 0.186 | 0.197 | 0.224 | 0.266 | 3.740 |
| Lucene OSQ 1-bit | all | 233,674 | 0.085 | 0.100 | 0.106 | 0.124 | 0.161 | 3.559 |
| Lucene OSQ 1-bit | post-filter | 271,061 | 0.075 | 0.086 | 0.090 | 0.098 | 0.123 | 4.407 |
| Lucene OSQ 1-bit | in-walk | 12,196 | 1.739 | 1.849 | 1.954 | 2.298 | 4.271 | 5.743 |
| Lucene OSQ 1-bit | acorn | 263,639 | 0.046 | 0.191 | 0.201 | 0.220 | 0.276 | 2.154 |
| Lucene OSQ 1-bit | planner | 154,155 | 0.132 | 0.140 | 0.144 | 0.157 | 0.194 | 3.105 |
| Lucene OSQ 4-bit | all | 193,316 | 0.103 | 0.120 | 0.129 | 0.151 | 0.201 | 5.661 |
| Lucene OSQ 4-bit | post-filter | 214,346 | 0.094 | 0.109 | 0.116 | 0.137 | 0.197 | 4.948 |
| Lucene OSQ 4-bit | in-walk | 7,712 | 2.658 | 3.169 | 3.618 | 4.718 | 6.173 | 7.045 |
| Lucene OSQ 4-bit | acorn | 179,867 | 0.048 | 0.214 | 0.224 | 0.237 | 0.280 | 5.546 |
| Lucene OSQ 4-bit | planner | 144,922 | 0.140 | 0.148 | 0.153 | 0.170 | 0.201 | 2.974 |
| Lucene OSQ 7-bit | all | 83,178 | 0.239 | 0.281 | 0.307 | 0.347 | 0.422 | 8.995 |
| Lucene OSQ 7-bit | post-filter | 87,357 | 0.228 | 0.273 | 0.296 | 0.343 | 0.412 | 3.764 |
| Lucene OSQ 7-bit | in-walk | 2,956 | 7.152 | 8.121 | 8.684 | 9.716 | 10.777 | 11.813 |
| Lucene OSQ 7-bit | acorn | 140,142 | 0.062 | 0.286 | 0.296 | 0.321 | 0.379 | 3.604 |
| Lucene OSQ 7-bit | planner | 103,082 | 0.194 | 0.208 | 0.216 | 0.238 | 0.280 | 6.808 |
| jVector PQ 256 × 8 | all | 36,557 | 0.547 | 0.601 | 0.620 | 0.660 | 0.748 | 2.789 |
| jVector PQ 256 × 8 | post-filter | 38,837 | 0.520 | 0.563 | 0.577 | 0.611 | 0.695 | 3.965 |
| jVector PQ 256 × 8 | in-walk | 2,731 | 7.938 | 8.549 | 9.051 | 10.361 | 11.892 | 12.698 |
| jVector BQ | all | 178,241 | 0.111 | 0.130 | 0.137 | 0.156 | 0.187 | 3.142 |
| jVector BQ | post-filter | 229,824 | 0.089 | 0.100 | 0.104 | 0.113 | 0.135 | 2.699 |
| jVector BQ | in-walk | 3,863 | 5.726 | 6.087 | 6.341 | 7.143 | 8.307 | 8.765 |

#### Corpus B — 22,895 vectors, medium, real clicks

| configuration | strategy | samples | p50 | p90 | p95 | p99 | p99.9 | max (ms) |
|---|---|---|---|---|---|---|---|---|
| exact float32 | all | 3,891 | 5.095 | 5.300 | 5.390 | 5.573 | 7.784 | 9.519 |
| exact float32 | filtered | 11,495 | 1.726 | 1.778 | 1.808 | 1.927 | 2.073 | 5.161 |
| Lucene float32 | all | 44,817 | 0.439 | 0.601 | 0.648 | 0.751 | 0.883 | 4.317 |
| Lucene float32 | post-filter | 45,431 | 0.434 | 0.596 | 0.641 | 0.745 | 0.879 | 4.129 |
| Lucene float32 | in-walk | 15,154 | 1.317 | 1.821 | 1.999 | 2.454 | 4.252 | 4.973 |
| Lucene float32 | acorn | 41,612 | 0.481 | 0.595 | 0.638 | 0.734 | 0.904 | 4.579 |
| Lucene float32 | planner | 15,087 | 1.323 | 1.827 | 2.013 | 2.479 | 5.854 | 6.889 |
| Lucene OSQ 1-bit | all | 176,190 | 0.113 | 0.133 | 0.140 | 0.156 | 0.197 | 4.026 |
| Lucene OSQ 1-bit | post-filter | 182,698 | 0.109 | 0.131 | 0.137 | 0.153 | 0.188 | 3.027 |
| Lucene OSQ 1-bit | in-walk | 90,733 | 0.221 | 0.280 | 0.304 | 0.378 | 0.645 | 5.235 |
| Lucene OSQ 1-bit | acorn | 137,931 | 0.149 | 0.168 | 0.175 | 0.192 | 0.246 | 2.630 |
| Lucene OSQ 1-bit | planner | 91,775 | 0.219 | 0.273 | 0.294 | 0.359 | 2.474 | 4.415 |
| Lucene OSQ 4-bit | all | 122,109 | 0.161 | 0.202 | 0.218 | 0.256 | 0.326 | 2.058 |
| Lucene OSQ 4-bit | post-filter | 127,147 | 0.156 | 0.195 | 0.209 | 0.244 | 0.318 | 4.997 |
| Lucene OSQ 4-bit | in-walk | 56,727 | 0.347 | 0.459 | 0.510 | 0.650 | 1.217 | 12.550 |
| Lucene OSQ 4-bit | acorn | 104,693 | 0.194 | 0.222 | 0.233 | 0.261 | 0.333 | 4.571 |
| Lucene OSQ 4-bit | planner | 58,285 | 0.336 | 0.443 | 0.490 | 0.629 | 2.939 | 4.702 |
| Lucene OSQ 7-bit | all | 48,650 | 0.409 | 0.513 | 0.545 | 0.626 | 0.796 | 4.170 |
| Lucene OSQ 7-bit | post-filter | 49,830 | 0.401 | 0.503 | 0.531 | 0.600 | 0.785 | 5.644 |
| Lucene OSQ 7-bit | in-walk | 19,874 | 1.023 | 1.337 | 1.454 | 1.772 | 3.188 | 5.915 |
| Lucene OSQ 7-bit | acorn | 43,835 | 0.471 | 0.544 | 0.570 | 0.641 | 0.782 | 5.259 |
| Lucene OSQ 7-bit | planner | 19,958 | 1.015 | 1.325 | 1.446 | 1.765 | 4.588 | 5.071 |
| jVector PQ 256 × 8 | all | 35,022 | 0.565 | 0.655 | 0.683 | 0.737 | 0.828 | 3.518 |
| jVector PQ 256 × 8 | post-filter | 35,158 | 0.564 | 0.655 | 0.682 | 0.736 | 0.813 | 1.575 |
| jVector PQ 256 × 8 | in-walk | 18,804 | 1.087 | 1.368 | 1.462 | 1.726 | 2.816 | 3.039 |
| jVector BQ | all | 160,783 | 0.121 | 0.153 | 0.162 | 0.184 | 0.215 | 3.056 |
| jVector BQ | post-filter | 175,359 | 0.111 | 0.146 | 0.156 | 0.184 | 0.220 | 3.953 |
| jVector BQ | in-walk | 62,464 | 0.323 | 0.437 | 0.474 | 0.614 | 1.015 | 4.465 |

#### Corpus C — 214,410 vectors, large, held-out queries

| configuration | strategy | samples | p50 | p90 | p95 | p99 | p99.9 | max (ms) |
|---|---|---|---|---|---|---|---|---|
| exact float32 | all | 422 | 47.383 | 48.955 | 49.480 | 50.680 | 52.822 | 52.822 |
| Lucene float32 | all | 21,816 | 0.904 | 1.229 | 1.317 | 1.515 | 1.757 | 4.899 |
| Lucene OSQ 1-bit | all | 70,817 | 0.279 | 0.353 | 0.377 | 0.434 | 0.524 | 4.260 |
| Lucene OSQ 4-bit | all | 45,518 | 0.431 | 0.569 | 0.614 | 0.724 | 0.885 | 3.944 |
| Lucene OSQ 7-bit | all | 26,529 | 0.740 | 0.989 | 1.055 | 1.217 | 1.490 | 12.567 |
| jVector PQ 256 × 8 | all | 26,662 | 0.739 | 0.910 | 0.950 | 1.029 | 1.167 | 6.783 |
| jVector BQ | all | 92,796 | 0.211 | 0.272 | 0.285 | 0.311 | 0.387 | 5.898 |

#### Corpus D — 416,559 vectors, large (1M stand-in), held-out queries

| configuration | strategy | samples | p50 | p90 | p95 | p99 | p99.9 | max (ms) |
|---|---|---|---|---|---|---|---|---|
| exact float32 | all | 198 | 100.598 | 107.256 | 109.707 | 110.892 | 111.411 | 111.411 |
| Lucene float32 | all | 19,806 | 0.998 | 1.405 | 1.503 | 1.673 | 1.914 | 5.964 |
| Lucene OSQ 1-bit | all | 59,619 | 0.333 | 0.425 | 0.451 | 0.506 | 0.610 | 4.874 |
| Lucene OSQ 4-bit | all | 42,125 | 0.469 | 0.628 | 0.669 | 0.755 | 0.871 | 5.972 |
| Lucene OSQ 7-bit | all | 25,044 | 0.788 | 1.083 | 1.155 | 1.294 | 1.495 | 10.404 |
| jVector PQ 256 × 8 | all | 24,492 | 0.810 | 1.010 | 1.051 | 1.153 | 1.328 | 6.726 |
| jVector BQ | all | 81,771 | 0.245 | 0.312 | 0.327 | 0.365 | 0.413 | 4.465 |

#### Corpus E — 312,787 vectors, large, short product texts, held-out queries

| configuration | strategy | samples | p50 | p90 | p95 | p99 | p99.9 | max (ms) |
|---|---|---|---|---|---|---|---|---|
| exact float32 | all | 284 | 69.992 | 73.269 | 74.154 | 77.241 | 80.085 | 80.085 |
| exact float32 | filtered | 672 | 29.590 | 30.727 | 31.808 | 33.236 | 38.273 | 38.273 |
| Lucene float32 | all | 58,818 | 0.323 | 0.507 | 0.568 | 0.698 | 0.817 | 4.301 |
| Lucene float32 | post-filter | 57,059 | 0.333 | 0.522 | 0.587 | 0.721 | 0.854 | 22.905 |
| Lucene float32 | in-walk | 27,007 | 0.685 | 1.102 | 1.348 | 2.523 | 3.514 | 5.063 |
| Lucene float32 | acorn | 265,956 | 0.055 | 0.174 | 0.219 | 0.286 | 0.395 | 4.481 |
| Lucene float32 | planner | 26,827 | 0.691 | 1.108 | 1.354 | 2.473 | 3.548 | 5.104 |
| Lucene OSQ 1-bit | all | 137,843 | 0.142 | 0.192 | 0.207 | 0.247 | 0.305 | 7.184 |
| Lucene OSQ 1-bit | post-filter | 137,314 | 0.142 | 0.195 | 0.211 | 0.252 | 0.312 | 10.846 |
| Lucene OSQ 1-bit | in-walk | 82,175 | 0.232 | 0.335 | 0.404 | 0.616 | 0.861 | 4.084 |
| Lucene OSQ 1-bit | acorn | 321,391 | 0.053 | 0.111 | 0.135 | 0.176 | 0.232 | 12.616 |
| Lucene OSQ 1-bit | planner | 81,218 | 0.234 | 0.344 | 0.411 | 0.624 | 0.868 | 5.169 |
| Lucene OSQ 4-bit | all | 104,064 | 0.187 | 0.265 | 0.292 | 0.342 | 0.401 | 4.809 |
| Lucene OSQ 4-bit | post-filter | 105,180 | 0.184 | 0.265 | 0.291 | 0.345 | 0.406 | 5.112 |
| Lucene OSQ 4-bit | in-walk | 58,262 | 0.325 | 0.490 | 0.585 | 0.955 | 1.346 | 4.366 |
| Lucene OSQ 4-bit | acorn | 302,966 | 0.055 | 0.123 | 0.147 | 0.188 | 0.249 | 4.612 |
| Lucene OSQ 4-bit | planner | 58,010 | 0.326 | 0.499 | 0.595 | 0.967 | 1.354 | 4.907 |
| Lucene OSQ 7-bit | all | 63,467 | 0.301 | 0.444 | 0.493 | 0.589 | 0.684 | 4.375 |
| Lucene OSQ 7-bit | post-filter | 62,671 | 0.304 | 0.453 | 0.507 | 0.610 | 0.735 | 7.291 |
| Lucene OSQ 7-bit | in-walk | 32,303 | 0.574 | 0.899 | 1.085 | 1.950 | 2.639 | 5.095 |
| Lucene OSQ 7-bit | acorn | 213,869 | 0.077 | 0.181 | 0.213 | 0.256 | 0.360 | 11.928 |
| Lucene OSQ 7-bit | planner | 32,529 | 0.570 | 0.890 | 1.079 | 1.935 | 2.658 | 6.455 |
| jVector PQ 256 × 8 | all | 35,681 | 0.545 | 0.673 | 0.717 | 0.806 | 0.986 | 12.124 |
| jVector PQ 256 × 8 | post-filter | 35,137 | 0.549 | 0.686 | 0.739 | 0.856 | 1.024 | 11.846 |
| jVector PQ 256 × 8 | in-walk | 24,933 | 0.760 | 1.008 | 1.171 | 1.834 | 2.405 | 4.424 |
| jVector BQ | all | 139,511 | 0.135 | 0.195 | 0.216 | 0.273 | 0.355 | 2.048 |
| jVector BQ | post-filter | 134,883 | 0.139 | 0.206 | 0.231 | 0.294 | 0.350 | 8.159 |
| jVector BQ | in-walk | 64,596 | 0.287 | 0.427 | 0.525 | 0.869 | 1.156 | 2.695 |

#### p95 across corpora, unfiltered, 4× (ms)

| configuration | A | B | C | D | E |
|---|---|---|---|---|---|
| exact float32 | 3.518 | 5.390 | 49.480 | 109.707 | 74.154 |
| Lucene float32 | 0.330 | 0.648 | 1.317 | 1.503 | 0.568 |
| Lucene OSQ 1-bit | 0.106 | 0.140 | 0.377 | 0.451 | 0.207 |
| Lucene OSQ 4-bit | 0.129 | 0.218 | 0.614 | 0.669 | 0.292 |
| Lucene OSQ 7-bit | 0.307 | 0.545 | 1.055 | 1.155 | 0.493 |
| jVector PQ 256 × 8 | 0.620 | 0.683 | 0.950 | 1.051 | 0.717 |
| jVector BQ | 0.137 | 0.162 | 0.285 | 0.327 | 0.216 |

#### The library combination: jVector graph, our searcher, Lucene OSQ codes (follow-up run, 2026-11-09)

Measurement 8 showed that the jVector graph walked by our searcher and scored by Lucene's OSQ codes from our code file
has the best recall of all the configurations. This run times it like the rest of this measurement (`jvgraph-osq1`,
`jvgraph-osq4`; the planner is the one of measurement 5 over our searcher). The other columns are copied from the tables
above: Lucene codes over the Lucene graph, jVector BQ over the jVector graph through `GraphSearcher`. p50 / p95 ms at
4×; C and D unfiltered only.
##### Corpus A — 14,837 vectors, small, real clicks

| strategy | Lucene OSQ 1-bit | Lucene OSQ 4-bit | jVector BQ | jVector graph + OSQ 1-bit | jVector graph + OSQ 4-bit |
|---|---|---|---|---|---|
| all | 0.09 / 0.11 | 0.10 / 0.13 | 0.11 / 0.14 | 0.12 / 0.17 | 0.16 / 0.23 |
| post-filter | 0.08 / 0.09 | 0.09 / 0.12 | 0.09 / 0.10 | 0.10 / 0.14 | 0.14 / 0.23 |
| in-walk | 1.74 / 1.95 | 2.66 / 3.62 | 5.73 / 6.34 | 2.56 / 3.88 | 4.36 / 7.01 |
| planner | 0.13 / 0.14 | 0.14 / 0.15 | — | 0.14 / 0.18 | 0.14 / 0.18 |

##### Corpus B — 22,895 vectors, medium, real clicks

| strategy | Lucene OSQ 1-bit | Lucene OSQ 4-bit | jVector BQ | jVector graph + OSQ 1-bit | jVector graph + OSQ 4-bit |
|---|---|---|---|---|---|
| all | 0.11 / 0.14 | 0.16 / 0.22 | 0.12 / 0.16 | 0.14 / 0.18 | 0.22 / 0.32 |
| post-filter | 0.11 / 0.14 | 0.16 / 0.21 | 0.11 / 0.16 | 0.13 / 0.18 | 0.20 / 0.30 |
| in-walk | 0.22 / 0.30 | 0.35 / 0.51 | 0.32 / 0.47 | 0.32 / 0.53 | 0.57 / 0.99 |
| planner | 0.22 / 0.29 | 0.34 / 0.49 | — | 0.34 / 0.55 | 0.46 / 0.75 |

##### Corpus C — 214,410 vectors, large, held-out queries

| strategy | Lucene OSQ 1-bit | Lucene OSQ 4-bit | jVector BQ | jVector graph + OSQ 1-bit | jVector graph + OSQ 4-bit |
|---|---|---|---|---|---|
| all | 0.28 / 0.38 | 0.43 / 0.61 | 0.21 / 0.29 | 0.37 / 0.62 | 0.50 / 0.76 |

##### Corpus D — 416,559 vectors, large (1M stand-in), held-out queries

| strategy | Lucene OSQ 1-bit | Lucene OSQ 4-bit | jVector BQ | jVector graph + OSQ 1-bit | jVector graph + OSQ 4-bit |
|---|---|---|---|---|---|
| all | 0.33 / 0.45 | 0.47 / 0.67 | 0.24 / 0.33 | 0.40 / 0.64 | 0.52 / 0.81 |

##### Corpus E — 312,787 vectors, large, short product texts, held-out queries

| strategy | Lucene OSQ 1-bit | Lucene OSQ 4-bit | jVector BQ | jVector graph + OSQ 1-bit | jVector graph + OSQ 4-bit |
|---|---|---|---|---|---|
| all | 0.14 / 0.21 | 0.19 / 0.29 | 0.14 / 0.22 | 0.19 / 0.35 | 0.23 / 0.42 |
| post-filter | 0.14 / 0.21 | 0.18 / 0.29 | 0.14 / 0.23 | 0.17 / 0.31 | 0.25 / 0.45 |
| in-walk | 0.23 / 0.40 | 0.33 / 0.58 | 0.29 / 0.53 | 0.35 / 0.78 | 0.46 / 1.04 |
| planner | 0.23 / 0.41 | 0.33 / 0.59 | — | 0.31 / 0.59 | 0.43 / 0.89 |

**Result.** The combination keeps p95 below 1.1 ms in every configuration except in-walk under A's 1.66 % filter (7.0 ms
with the 4-bit code), where the planner's exact scan takes over (0.18 ms). It is 20–95 % slower at p95 than Lucene's
codes over the Lucene graph: the degree-32 graph scores more neighbours per hop (A, OSQ 1-bit: 913 visited against 575).
That cost buys measurement 8's recall.

### 5. Filtered search with a real filter

**What is measured.** Searches restricted to a real subset of the corpus — the products of one locale or one
collection — with four strategies:

- **post-filter**: walk the whole graph, then drop what is not in the subset. Cheap, but when the subset is small
  the ten nearest overall may contain few or no members, and the result comes back short (**starved**).
- **in-walk**: the walk only accepts members as results but still travels through non-members. Correct, but with a
  small subset it wanders far before it finds ten.
- **ACORN**: Lucene's walk for filters, which also hops over non-members to reach members faster.
- **planner**: the rule evitaDB would apply: an exact scan of the subset when it is small (≤ 100 members), in-walk
  otherwise, and an exact scan as a **fallback** when the walk exceeds a visit limit.

**Why.** Almost every evitaDB query is filtered (locale, category, availability, price). A vector search that ignores
the filter, or degrades badly under it, is useless for the product.

**What we expect.** At 30–40 % every strategy works; at 1.66 % post-filter starves and in-walk gets slow, and the
planner is both correct and fast.

#### Corpus A — filter 1.66 % (246 vectors)

Exact scan of the filter members (the reference): p50 / p95 0.07 / 0.07 ms.

##### Lucene

| code | strategy | recall@10 1× | 2× | 4× | 8× | visited | fallback | starved | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|---|---|
| float32 | post-filter | 0.103 | 0.103 | 0.103 | 0.103 | 560 | 0 | 102 | 0.20 / 0.30 |
| float32 | in-walk | 1.000 | 1.000 | 1.000 | 1.000 | 13,420 | 0 | 0 | 10.66 / 12.92 |
| float32 | ACORN | 0.393 | 0.393 | 0.393 | 0.393 | 149 | 0 | 0 | 0.02 / 0.24 |
| float32 | planner | 1.000 | 1.000 | 1.000 | 1.000 | 247 | 107 | 0 | 0.16 / 0.19 |
| OSQ 1-bit | post-filter | 0.095 | 0.095 | 0.095 | 0.095 | 575 | 0 | 103 | 0.07 / 0.09 |
| OSQ 1-bit | in-walk | 0.894 | 0.991 | 1.000 | 1.000 | 13,600 | 0 | 0 | 1.68 / 1.86 |
| OSQ 1-bit | ACORN | 0.220 | 0.240 | 0.243 | 0.243 | 114 | 0 | 0 | 0.05 / 0.23 |
| OSQ 1-bit | planner | 1.000 | 1.000 | 1.000 | 1.000 | 247 | 107 | 0 | 0.14 / 0.15 |
| OSQ 4-bit | post-filter | 0.102 | 0.102 | 0.102 | 0.102 | 561 | 0 | 102 | 0.09 / 0.10 |
| OSQ 4-bit | in-walk | 0.969 | 1.000 | 1.000 | 1.000 | 13,492 | 0 | 0 | 2.50 / 2.65 |
| OSQ 4-bit | ACORN | 0.409 | 0.430 | 0.430 | 0.430 | 159 | 0 | 0 | 0.05 / 0.22 |
| OSQ 4-bit | planner | 1.000 | 1.000 | 1.000 | 1.000 | 247 | 107 | 0 | 0.13 / 0.14 |
| OSQ 7-bit | post-filter | 0.100 | 0.101 | 0.101 | 0.101 | 559 | 0 | 102 | 0.24 / 0.32 |
| OSQ 7-bit | in-walk | 0.982 | 1.000 | 1.000 | 1.000 | 13,441 | 0 | 0 | 7.13 / 8.51 |
| OSQ 7-bit | ACORN | 0.400 | 0.411 | 0.411 | 0.411 | 154 | 0 | 0 | 0.06 / 0.30 |
| OSQ 7-bit | planner | 1.000 | 1.000 | 1.000 | 1.000 | 247 | 107 | 0 | 0.19 / 0.20 |

##### jVector

| code | strategy | recall@10 1× | 2× | 4× | 8× | visited | starved | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|---|
| PQ 256 × 8 | post-filter | 0.087 | 0.091 | 0.091 | 0.091 | 774 | 102 | 0.55 / 0.62 |
| PQ 256 × 8 | in-walk | 0.853 | 0.989 | 1.000 | 1.000 | 13,636 | 0 | 7.72 / 8.42 |
| BQ | post-filter | 0.109 | 0.113 | 0.113 | 0.113 | 840 | 102 | 0.09 / 0.10 |
| BQ | in-walk | 0.766 | 0.916 | 0.984 | 1.000 | 13,841 | 0 | 4.94 / 5.52 |

#### Corpus B — filter 29.71 % (6,801 vectors)

Exact scan of the filter members (the reference): p50 / p95 1.55 / 1.62 ms.

##### Lucene

| code | strategy | recall@10 1× | 2× | 4× | 8× | visited | fallback | starved | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|---|---|
| float32 | post-filter | 0.852 | 0.852 | 0.852 | 0.852 | 1,012 | 0 | 215 | 0.44 / 0.65 |
| float32 | in-walk | 0.990 | 0.990 | 0.990 | 0.990 | 2,614 | 0 | 0 | 1.30 / 1.94 |
| float32 | ACORN | 0.958 | 0.958 | 0.958 | 0.958 | 1,119 | 0 | 0 | 0.47 / 0.61 |
| float32 | planner | 0.990 | 0.990 | 0.990 | 0.990 | 2,614 | 1 | 0 | 1.28 / 1.90 |
| OSQ 1-bit | post-filter | 0.697 | 0.797 | 0.811 | 0.811 | 1,021 | 0 | 215 | 0.11 / 0.14 |
| OSQ 1-bit | in-walk | 0.773 | 0.930 | 0.975 | 0.984 | 2,639 | 0 | 0 | 0.21 / 0.28 |
| OSQ 1-bit | ACORN | 0.748 | 0.896 | 0.937 | 0.944 | 1,140 | 0 | 0 | 0.15 / 0.17 |
| OSQ 1-bit | planner | 0.773 | 0.930 | 0.975 | 0.984 | 2,638 | 1 | 0 | 0.21 / 0.28 |
| OSQ 4-bit | post-filter | 0.800 | 0.840 | 0.840 | 0.840 | 1,014 | 0 | 235 | 0.15 / 0.20 |
| OSQ 4-bit | in-walk | 0.918 | 0.989 | 0.990 | 0.990 | 2,623 | 0 | 0 | 0.33 / 0.49 |
| OSQ 4-bit | ACORN | 0.894 | 0.958 | 0.959 | 0.959 | 1,134 | 0 | 0 | 0.19 / 0.24 |
| OSQ 4-bit | planner | 0.918 | 0.989 | 0.990 | 0.990 | 2,622 | 1 | 0 | 0.33 / 0.47 |
| OSQ 7-bit | post-filter | 0.834 | 0.848 | 0.848 | 0.848 | 1,014 | 0 | 226 | 0.39 / 0.52 |
| OSQ 7-bit | in-walk | 0.962 | 0.990 | 0.990 | 0.990 | 2,615 | 0 | 0 | 0.99 / 1.39 |
| OSQ 7-bit | ACORN | 0.933 | 0.957 | 0.957 | 0.957 | 1,124 | 0 | 0 | 0.46 / 0.54 |
| OSQ 7-bit | planner | 0.962 | 0.990 | 0.990 | 0.990 | 2,615 | 1 | 0 | 0.99 / 1.39 |

##### jVector

| code | strategy | recall@10 1× | 2× | 4× | 8× | visited | starved | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|---|
| PQ 256 × 8 | post-filter | 0.571 | 0.660 | 0.683 | 0.685 | 932 | 313 | 0.56 / 0.68 |
| PQ 256 × 8 | in-walk | 0.725 | 0.878 | 0.972 | 0.988 | 2,872 | 0 | 1.09 / 1.46 |
| BQ | post-filter | 0.550 | 0.658 | 0.692 | 0.694 | 1,085 | 247 | 0.11 / 0.15 |
| BQ | in-walk | 0.624 | 0.797 | 0.912 | 0.968 | 3,097 | 0 | 0.32 / 0.47 |

#### Corpus E — filter 38.93 % (121,760 vectors)

Exact scan of the filter members (the reference): p50 / p95 27.14 / 29.30 ms.

##### Lucene

| code | strategy | recall@10 1× | 2× | 4× | 8× | visited | fallback | starved | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|---|---|
| float32 | post-filter | 0.951 | 0.951 | 0.951 | 0.951 | 509 | 0 | 39 | 0.34 / 0.59 |
| float32 | in-walk | 0.984 | 0.984 | 0.984 | 0.984 | 1,053 | 0 | 0 | 0.69 / 1.35 |
| float32 | ACORN | 0.140 | 0.140 | 0.140 | 0.140 | 117 | 0 | 0 | 0.06 / 0.22 |
| float32 | planner | 0.984 | 0.984 | 0.984 | 0.984 | 1,053 | 0 | 0 | 0.69 / 1.33 |
| OSQ 1-bit | post-filter | 0.868 | 0.942 | 0.945 | 0.945 | 521 | 0 | 37 | 0.14 / 0.19 |
| OSQ 1-bit | in-walk | 0.896 | 0.978 | 0.982 | 0.982 | 1,070 | 0 | 0 | 0.23 / 0.40 |
| OSQ 1-bit | ACORN | 0.128 | 0.129 | 0.129 | 0.129 | 114 | 0 | 0 | 0.05 / 0.15 |
| OSQ 1-bit | planner | 0.896 | 0.978 | 0.982 | 0.982 | 1,070 | 0 | 0 | 0.22 / 0.39 |
| OSQ 4-bit | post-filter | 0.915 | 0.948 | 0.948 | 0.948 | 509 | 0 | 40 | 0.18 / 0.28 |
| OSQ 4-bit | in-walk | 0.948 | 0.985 | 0.985 | 0.985 | 1,052 | 0 | 0 | 0.32 / 0.57 |
| OSQ 4-bit | ACORN | 0.137 | 0.138 | 0.138 | 0.138 | 116 | 0 | 0 | 0.06 / 0.14 |
| OSQ 4-bit | planner | 0.948 | 0.985 | 0.985 | 0.985 | 1,052 | 0 | 0 | 0.32 / 0.59 |
| OSQ 7-bit | post-filter | 0.940 | 0.948 | 0.948 | 0.948 | 509 | 0 | 39 | 0.30 / 0.49 |
| OSQ 7-bit | in-walk | 0.975 | 0.984 | 0.984 | 0.984 | 1,052 | 0 | 0 | 0.58 / 1.06 |
| OSQ 7-bit | ACORN | 0.142 | 0.142 | 0.142 | 0.142 | 117 | 0 | 0 | 0.08 / 0.24 |
| OSQ 7-bit | planner | 0.975 | 0.984 | 0.984 | 0.984 | 1,052 | 0 | 0 | 0.57 / 1.05 |

##### jVector

| code | strategy | recall@10 1× | 2× | 4× | 8× | visited | starved | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|---|
| PQ 256 × 8 | post-filter | 0.718 | 0.856 | 0.894 | 0.897 | 654 | 34 | 0.54 / 0.71 |
| PQ 256 × 8 | in-walk | 0.753 | 0.903 | 0.950 | 0.968 | 1,332 | 0 | 0.76 / 1.18 |
| BQ | post-filter | 0.820 | 0.925 | 0.940 | 0.941 | 656 | 36 | 0.13 / 0.20 |
| BQ | in-walk | 0.846 | 0.962 | 0.982 | 0.987 | 1,372 | 0 | 0.26 / 0.50 |

#### Corpus C, corpus D

No real filter (one collection). Their filtered behaviour is in measurement 6.

### 6. Filtered search, synthetic matrix

**What is measured.** The same strategies over filters made up to cover the whole range: from 0.01 % to 100 % of the
corpus, in three **shapes**:

- **random**: members picked at random, unrelated to what the vectors mean (like "in stock");
- **correlated**: members are one region of the vector space (like a category: similar products are together);
- **excluded**: the region nearest to the queries is *not* in the filter (the hard case: the walk heads where no
  member is).

Also, at 50 %, four structures for testing membership during the walk: the default bitmap of primary keys,
and three structures over vector ordinals (`boolean[]`, a flat bitset, a Roaring bitmap).

**Why.** A real catalog has filters of every selectivity and shape. The planner needs the point where a walk stops
paying off and an exact scan of the members is cheaper, and the spec asks where in-walk filtering stops working
(between 50 and 85 % excluded).

**What we expect.** Walks fine down to about 5–10 %; below that in-walk slows down and post-filter starves, and the
planner's exact scan takes over; the excluded shape hardest. The exact scan of the members gets more expensive with
the corpus, so the switch point should move lower on C, D and E than on A and B.

**Setup.** 500 queries per cell, walk depth 100, recall at 1× and 4×. A filter is a bitmap of primary keys of whole
records: a record selected through one of its vectors brings all of them, so the member count can exceed the
nominal share slightly. Each cell below is *recall@10 at 4× / p50 ms*; `sN`: N queries starved (fewer than
ten results), `fN`: N planner fallbacks to the exact scan. Corpus A has 107 queries, all used. Lucene OSQ
4-bit and jVector PQ are shown with the planner only; their other strategies follow the float32 ones.

#### Corpus A — 14,837 vectors, small, real clicks

| shape | selectivity | members | exact scan p50 | Lucene float32 post-filter | Lucene float32 in-walk | Lucene float32 ACORN | Lucene float32 planner | Lucene OSQ 4-bit planner | jVector float32 in-walk | jVector float32 planner | jVector PQ planner |
|---|---|---|---|---|---|---|---|---|---|---|---|
| random | 100 % | 14,837 | 3.77 | 0.957 / 0.30 | 0.957 / 0.28 | 0.957 / 0.26 | 0.957 / 0.22 | 0.945 / 0.11 | 0.978 / 0.56 | 0.978 / 0.54 | 0.921 / 0.50 |
| random | 95 % | 14,099 | 3.58 | 0.956 / 0.23 | 0.960 / 0.25 | 0.951 / 0.25 | 0.960 / 0.26 | 0.944 / 0.12 | 0.974 / 0.70 | 0.974 / 0.67 | 0.917 / 0.50 |
| random | 90 % | 13,360 | 3.44 | 0.961 / 0.22 | 0.964 / 0.27 | 0.956 / 0.24 | 0.964 / 0.28 | 0.949 / 0.12 | 0.979 / 0.61 | 0.979 / 0.58 | 0.926 / 0.50 |
| random | 85 % | 12,622 | 3.27 | 0.961 / 0.23 | 0.964 / 0.29 | 0.940 / 0.28 | 0.964 / 0.25 | 0.962 / 0.12 | 0.979 / 0.66 | 0.979 / 0.61 | 0.941 / 0.51 |
| random | 50 % | 7,433 | 2.51 | 0.950 / 0.22 | 0.966 / 0.48 | 0.707 / 0.44 | 0.966 / 0.40 | 0.966 / 0.15 | 0.982 / 1.01 | 0.982 / 0.90 | 0.959 / 0.62 |
| random | 10 % | 1,492 | 0.45 | 0.876 / 0.21 s25 | 0.984 / 1.44 | 0.485 / 0.36 | 1.000 / 1.37 f107 | 1.000 / 0.87 f107 | 1.000 / 3.03 | 1.000 / 1.57 f107 | 1.000 / 1.24 f107 |
| random | 5 % | 743 | 0.22 | 0.637 / 0.21 s93 | 0.991 / 2.53 | 0.373 / 0.24 | 1.000 / 0.56 f107 | 1.000 / 0.40 f107 | 0.999 / 4.80 | 1.000 / 0.73 f107 | 1.000 / 0.78 f107 |
| random | 1 % | 148 | 0.05 | 0.106 / 0.24 s107 | 1.000 / 8.28 | 0.443 / 0.53 | 1.000 / 0.11 f107 | 1.000 / 0.11 f107 | 1.000 / 12.94 | 1.000 / 0.13 f107 | 1.000 / 0.39 f107 |
| random | 0.10 % | 15 | 0.01 | 0.014 / 0.26 s107 | 1.000 / 12.69 | 0.036 / 0.02 | 1.000 / 0.01 f107 | 1.000 / 0.01 f107 | 1.000 / 15.21 | 1.000 / 0.01 f107 | 1.000 / 0.32 f107 |
| random | 0.01 % | 1 | 0.00 | 0.000 / 0.21 s107 | 1.000 / 10.45 | 0.000 / 0.02 | 1.000 / 0.00 f107 | 1.000 / 0.00 f107 | 1.000 / 15.19 | 1.000 / 0.00 f107 | 1.000 / 0.31 f107 |
| correlated | 95 % | 14,103 | 3.76 | 0.957 / 0.24 | 0.957 / 0.26 | 0.956 / 0.30 | 0.957 / 0.29 | 0.945 / 0.14 | 0.978 / 0.68 | 0.978 / 0.56 | 0.921 / 0.50 |
| correlated | 90 % | 13,363 | 3.51 | 0.957 / 0.22 | 0.957 / 0.23 | 0.956 / 0.23 | 0.957 / 0.23 | 0.945 / 0.11 | 0.978 / 0.55 | 0.978 / 0.56 | 0.921 / 0.50 |
| correlated | 85 % | 12,621 | 3.40 | 0.957 / 0.22 | 0.957 / 0.24 | 0.956 / 0.23 | 0.957 / 0.28 | 0.945 / 0.12 | 0.976 / 0.62 | 0.976 / 0.58 | 0.925 / 0.50 |
| correlated | 50 % | 7,425 | 2.30 | 0.936 / 0.22 s3 | 0.950 / 0.28 | 0.928 / 0.24 | 0.950 / 0.27 | 0.940 / 0.12 | 0.986 / 0.70 | 0.986 / 0.69 | 0.945 / 0.53 |
| correlated | 10 % | 1,484 | 0.59 | 0.576 / 0.22 s51 | 0.993 / 1.33 | 0.652 / 0.27 | 0.996 / 1.29 f69 | 0.996 / 0.86 f70 | 0.996 / 2.40 | 0.996 / 1.74 f99 | 0.994 / 1.32 f87 |
| correlated | 5 % | 742 | 0.32 | 0.426 / 0.24 s69 | 0.999 / 1.85 | 0.501 / 0.20 | 1.000 / 0.67 f106 | 1.000 / 0.48 f106 | 1.000 / 3.61 | 1.000 / 0.81 f107 | 1.000 / 0.85 f107 |
| correlated | 1 % | 148 | 0.06 | 0.129 / 0.21 s102 | 0.997 / 6.11 | 0.547 / 0.22 | 1.000 / 0.13 f107 | 1.000 / 0.11 f107 | 1.000 / 10.64 | 1.000 / 0.14 f107 | 1.000 / 0.40 f107 |
| correlated | 0.10 % | 15 | 0.01 | 0.008 / 0.23 s107 | 1.000 / 12.62 | 0.112 / 0.02 | 1.000 / 0.01 f107 | 1.000 / 0.01 f107 | 1.000 / 15.29 | 1.000 / 0.01 f107 | 1.000 / 0.32 f107 |
| correlated | 0.01 % | 1 | 0.00 | 0.000 / 0.21 s107 | 1.000 / 10.43 | 0.009 / 0.02 | 1.000 / 0.00 f107 | 1.000 / 0.00 f107 | 1.000 / 15.09 | 1.000 / 0.00 f107 | 1.000 / 0.30 f107 |
| excluded | 95 % | 14,095 | 3.65 | 0.952 / 0.22 | 0.956 / 0.28 | 0.921 / 0.27 | 0.956 / 0.25 | 0.952 / 0.12 | 0.976 / 0.61 | 0.976 / 0.69 | 0.947 / 0.51 |
| excluded | 90 % | 13,353 | 3.49 | 0.947 / 0.22 | 0.958 / 0.27 | 0.915 / 0.24 | 0.958 / 0.26 | 0.958 / 0.13 | 0.983 / 0.63 | 0.983 / 0.62 | 0.960 / 0.54 |
| excluded | 85 % | 12,611 | 3.45 | 0.940 / 0.22 s3 | 0.959 / 0.27 | 0.916 / 0.25 | 0.959 / 0.27 | 0.959 / 0.14 | 0.993 / 0.65 | 0.993 / 0.64 | 0.943 / 0.55 |
| excluded | 50 % | 7,413 | 2.40 | 0.668 / 0.21 s39 | 0.978 / 0.97 | 0.807 / 0.28 | 0.978 / 1.00 f2 | 0.979 / 0.33 f2 | 0.988 / 2.42 | 0.988 / 2.46 f9 | 0.951 / 1.02 f8 |

##### Membership structure at 50 % (in-walk, p50 / p95 ms)

| configuration | shape | primary-key bitmap (default) | `boolean[]` | flat bitset | Roaring over ordinals |
|---|---|---|---|---|---|
| Lucene float32 | random | 0.48 / 0.68 | 0.50 / 0.66 | 0.48 / 0.63 | 0.37 / 0.51 |
| Lucene float32 | correlated | 0.28 / 0.94 | 0.30 / 1.04 | 0.29 / 1.05 | 0.27 / 1.00 |
| Lucene float32 | excluded | 0.97 / 4.27 | 0.94 / 4.28 | 1.03 / 4.37 | 1.00 / 4.44 |
| jVector float32 | random | 1.01 / 1.37 | 1.06 / 1.30 | 0.88 / 1.17 | 0.92 / 1.21 |
| jVector float32 | correlated | 0.70 / 1.74 | 0.67 / 1.78 | 0.67 / 1.70 | 0.70 / 1.81 |
| jVector float32 | excluded | 2.42 / 7.70 | 2.32 / 7.73 | 2.29 / 7.43 | 2.43 / 7.63 |

#### Corpus B — 22,895 vectors, medium, real clicks

| shape | selectivity | members | exact scan p50 | Lucene float32 post-filter | Lucene float32 in-walk | Lucene float32 ACORN | Lucene float32 planner | Lucene OSQ 4-bit planner | jVector float32 in-walk | jVector float32 planner | jVector PQ planner |
|---|---|---|---|---|---|---|---|---|---|---|---|
| random | 100 % | 22,895 | 5.64 | 0.969 / 0.42 | 0.969 / 0.44 | 0.969 / 0.45 | 0.969 / 0.43 | 0.963 / 0.16 | 0.976 / 0.71 | 0.976 / 0.73 | 0.906 / 0.53 |
| random | 95 % | 21,752 | 5.38 | 0.968 / 0.42 | 0.969 / 0.44 | 0.967 / 0.45 | 0.969 / 0.46 | 0.965 / 0.16 | 0.976 / 0.76 | 0.976 / 0.73 | 0.908 / 0.52 |
| random | 90 % | 20,613 | 5.15 | 0.968 / 0.44 | 0.969 / 0.46 | 0.967 / 0.45 | 0.969 / 0.46 | 0.967 / 0.20 | 0.977 / 0.80 | 0.977 / 0.77 | 0.913 / 0.54 |
| random | 85 % | 19,473 | 5.23 | 0.966 / 0.47 | 0.971 / 0.53 | 0.964 / 0.50 | 0.971 / 0.47 | 0.968 / 0.17 | 0.977 / 0.82 | 0.977 / 0.80 | 0.914 / 0.54 |
| random | 50 % | 11,484 | 4.30 | 0.955 / 0.50 | 0.970 / 0.71 | 0.941 / 0.67 | 0.970 / 0.72 | 0.965 / 0.22 | 0.986 / 1.20 | 0.986 / 1.21 | 0.931 / 0.65 |
| random | 10 % | 2,322 | 0.74 | 0.909 / 0.41 s66 | 0.962 / 2.88 | 0.860 / 0.47 | 0.998 / 2.30 f499 | 0.996 / 1.24 f498 | 0.999 / 4.22 | 1.000 / 2.65 f500 | 0.998 / 1.71 f498 |
| random | 5 % | 1,168 | 0.34 | 0.624 / 0.42 s470 | 0.977 / 5.02 | 0.770 / 0.41 | 0.998 / 0.93 f499 | 0.996 / 0.56 f498 | 0.999 / 7.09 | 1.000 / 1.17 f500 | 0.998 / 0.96 f499 |
| random | 1 % | 233 | 0.08 | 0.165 / 0.43 s500 | 0.957 / 13.02 | 0.802 / 0.97 | 0.998 / 0.17 f499 | 0.996 / 0.14 f498 | 0.971 / 18.09 | 1.000 / 0.22 f500 | 0.998 / 0.43 f499 |
| random | 0.10 % | 23 | 0.01 | 0.006 / 0.39 s500 | 0.998 / 16.95 | 0.016 / 0.02 | 1.000 / 0.01 f500 | 1.000 / 0.01 f500 | 1.000 / 24.01 | 1.000 / 0.01 f500 | 1.000 / 0.32 f500 |
| random | 0.01 % | 2 | 0.00 | 0.000 / 0.42 s500 | 0.998 / 16.84 | 0.014 / 0.02 | 1.000 / 0.00 f500 | 1.000 / 0.00 f500 | 1.000 / 23.70 | 1.000 / 0.00 f500 | 1.000 / 0.31 f500 |
| correlated | 95 % | 21,778 | 5.50 | 0.970 / 0.43 | 0.970 / 0.44 | 0.969 / 0.45 | 0.970 / 0.44 | 0.964 / 0.20 | 0.977 / 0.84 | 0.977 / 0.73 | 0.907 / 0.53 |
| correlated | 90 % | 20,615 | 5.32 | 0.969 / 0.42 | 0.969 / 0.44 | 0.969 / 0.47 | 0.969 / 0.47 | 0.964 / 0.18 | 0.977 / 0.87 | 0.977 / 0.87 | 0.906 / 0.53 |
| correlated | 85 % | 19,465 | 5.12 | 0.969 / 0.42 s1 | 0.970 / 0.45 | 0.969 / 0.53 | 0.970 / 0.45 | 0.965 / 0.17 | 0.978 / 0.76 | 0.978 / 0.79 | 0.912 / 0.53 |
| correlated | 50 % | 11,449 | 3.61 | 0.918 / 0.47 s33 | 0.964 / 0.75 | 0.950 / 0.48 | 0.964 / 0.74 | 0.959 / 0.24 | 0.988 / 1.16 | 0.988 / 1.15 | 0.940 / 0.62 |
| correlated | 10 % | 2,291 | 0.83 | 0.323 / 0.40 s369 | 0.918 / 4.55 | 0.690 / 0.48 | 0.997 / 2.09 f478 | 0.996 / 1.30 f477 | 0.996 / 6.56 | 1.000 / 2.52 f480 | 0.996 / 1.75 f464 |
| correlated | 5 % | 1,145 | 0.42 | 0.195 / 0.44 s442 | 0.940 / 6.12 | 0.473 / 0.32 | 0.998 / 1.01 f497 | 0.996 / 0.68 f496 | 0.999 / 9.08 | 1.000 / 1.26 f498 | 0.998 / 1.07 f497 |
| correlated | 1 % | 229 | 0.08 | 0.033 / 0.41 s498 | 0.941 / 12.20 | 0.292 / 0.03 | 0.998 / 0.21 f499 | 0.996 / 0.15 f498 | 1.000 / 16.92 | 1.000 / 0.24 f500 | 0.998 / 0.43 f499 |
| correlated | 0.10 % | 23 | 0.01 | 0.001 / 0.39 s500 | 0.998 / 16.83 | 0.138 / 0.02 | 1.000 / 0.01 f500 | 1.000 / 0.01 f500 | 1.000 / 23.61 | 1.000 / 0.01 f500 | 1.000 / 0.32 f500 |
| correlated | 0.01 % | 2 | 0.00 | 0.000 / 0.41 s500 | 0.998 / 17.01 | 0.002 / 0.02 | 1.000 / 0.00 f500 | 1.000 / 0.00 f500 | 1.000 / 23.84 | 1.000 / 0.00 f500 | 1.000 / 0.31 f500 |
| excluded | 95 % | 21,750 | 5.46 | 0.968 / 0.44 | 0.969 / 0.46 | 0.968 / 0.47 | 0.969 / 0.45 | 0.965 / 0.17 | 0.977 / 0.76 | 0.977 / 0.86 | 0.903 / 0.58 |
| excluded | 90 % | 20,605 | 5.25 | 0.968 / 0.44 s2 | 0.972 / 0.55 | 0.969 / 0.53 | 0.972 / 0.51 | 0.969 / 0.19 | 0.978 / 0.77 | 0.978 / 0.77 | 0.909 / 0.54 |
| excluded | 85 % | 19,460 | 5.08 | 0.966 / 0.42 s3 | 0.975 / 0.47 | 0.970 / 0.44 | 0.975 / 0.49 | 0.972 / 0.17 | 0.980 / 0.82 | 0.980 / 0.89 | 0.912 / 0.57 |
| excluded | 50 % | 11,447 | 3.41 | 0.889 / 0.41 s72 | 0.986 / 0.91 | 0.945 / 0.55 | 0.986 / 0.82 | 0.983 / 0.32 | 0.991 / 1.36 | 0.991 / 1.34 f1 | 0.947 / 0.71 f1 |

##### Membership structure at 50 % (in-walk, p50 / p95 ms)

| configuration | shape | primary-key bitmap (default) | `boolean[]` | flat bitset | Roaring over ordinals |
|---|---|---|---|---|---|
| Lucene float32 | random | 0.71 / 1.07 | 0.74 / 1.12 | 0.69 / 0.97 | 0.69 / 0.98 |
| Lucene float32 | correlated | 0.75 / 1.92 | 0.77 / 1.89 | 0.74 / 1.95 | 0.73 / 1.83 |
| Lucene float32 | excluded | 0.91 / 3.33 | 0.85 / 3.18 | 0.88 / 3.19 | 0.98 / 3.72 |
| jVector float32 | random | 1.20 / 1.69 | 1.17 / 1.61 | 1.14 / 1.58 | 1.16 / 1.58 |
| jVector float32 | correlated | 1.16 / 2.77 | 1.19 / 2.83 | 1.18 / 2.85 | 1.13 / 2.80 |
| jVector float32 | excluded | 1.36 / 4.91 | 1.31 / 4.77 | 1.48 / 5.29 | 1.33 / 4.90 |

#### Corpus C — 214,410 vectors, large, held-out queries

| shape | selectivity | members | exact scan p50 | Lucene float32 post-filter | Lucene float32 in-walk | Lucene float32 ACORN | Lucene float32 planner | Lucene OSQ 4-bit planner | jVector float32 in-walk | jVector float32 planner | jVector PQ planner |
|---|---|---|---|---|---|---|---|---|---|---|---|
| random | 100 % | 214,410 | 47.84 | 0.997 / 0.96 | 0.997 / 0.95 | 0.997 / 0.95 | 0.997 / 0.98 | 0.997 / 0.47 | 0.993 / 1.13 | 0.993 / 1.17 | 0.990 / 0.70 |
| random | 95 % | 204,340 | 50.32 | 0.996 / 0.94 | 0.996 / 1.05 | 0.996 / 0.96 | 0.996 / 0.99 | 0.996 / 0.49 | 0.993 / 1.18 | 0.993 / 1.16 | 0.992 / 0.69 |
| random | 90 % | 194,217 | 50.84 | 0.996 / 0.92 | 0.996 / 0.98 | 0.994 / 1.04 | 0.996 / 0.98 | 0.996 / 0.48 | 0.995 / 1.31 | 0.995 / 1.23 | 0.992 / 0.70 |
| random | 85 % | 184,103 | 51.20 | 0.995 / 0.95 | 0.995 / 1.04 | 0.992 / 1.01 | 0.995 / 1.04 | 0.995 / 0.48 | 0.995 / 1.26 | 0.995 / 1.32 | 0.993 / 0.72 |
| random | 50 % | 111,181 | 44.27 | 0.993 / 0.89 | 0.996 / 1.53 | 0.991 / 1.60 | 0.996 / 1.52 | 0.998 / 0.69 | 0.997 / 1.88 | 0.997 / 1.85 | 0.993 / 0.93 |
| random | 10 % | 23,528 | 14.08 | 0.895 / 0.87 s179 | 1.000 / 5.66 | 0.959 / 1.28 | 1.000 / 5.64 | 1.000 / 2.43 | 1.000 / 7.04 | 1.000 / 7.06 | 0.999 / 2.47 |
| random | 5 % | 12,149 | 7.59 | 0.551 / 0.89 s469 | 0.999 / 10.41 | 0.915 / 1.66 | 1.000 / 16.97 f320 | 1.000 / 12.00 f323 | 0.999 / 13.97 | 1.000 / 18.80 f361 | 1.000 / 11.91 f361 |
| random | 1 % | 3,048 | 0.95 | 0.116 / 0.91 s499 | 1.000 / 39.58 | 0.656 / 1.86 | 1.000 / 3.80 f500 | 1.000 / 2.31 f500 | 0.999 / 50.78 | 1.000 / 4.43 f500 | 1.000 / 2.45 f500 |
| random | 0.10 % | 234 | 0.08 | 0.013 / 0.92 s500 | 1.000 / 180.24 | 0.025 / 0.05 | 1.000 / 0.23 f500 | 1.000 / 0.18 f500 | 1.000 / 208.41 | 1.000 / 0.23 f500 | 1.000 / 0.44 f500 |
| random | 0.01 % | 22 | 0.01 | 0.001 / 0.88 s500 | 1.000 / 240.24 | 0.001 / 0.05 | 1.000 / 0.01 f500 | 1.000 / 0.01 f500 | 1.000 / 268.24 | 1.000 / 0.01 f500 | 1.000 / 0.32 f500 |
| correlated | 95 % | 204,390 | 46.72 | 0.996 / 0.94 | 0.996 / 0.99 | 0.996 / 0.96 | 0.996 / 1.00 | 0.996 / 0.46 | 0.992 / 1.16 | 0.992 / 1.21 | 0.989 / 0.69 |
| correlated | 90 % | 194,009 | 45.56 | 0.996 / 0.92 | 0.996 / 1.20 | 0.996 / 0.95 | 0.996 / 1.03 | 0.997 / 0.47 | 0.993 / 1.22 | 0.993 / 1.23 | 0.991 / 0.72 |
| correlated | 85 % | 183,585 | 44.70 | 0.996 / 0.94 | 0.996 / 1.05 | 0.996 / 1.04 | 0.996 / 1.07 | 0.997 / 0.49 | 0.993 / 1.27 | 0.993 / 1.28 | 0.992 / 0.73 |
| correlated | 50 % | 109,545 | 38.91 | 0.928 / 0.90 s50 | 0.997 / 1.51 | 0.981 / 1.20 | 0.997 / 1.56 | 0.997 / 0.65 | 0.996 / 1.85 | 0.996 / 1.85 | 0.992 / 0.88 |
| correlated | 10 % | 22,063 | 13.31 | 0.451 / 0.90 s344 | 0.997 / 7.54 | 0.891 / 1.60 | 0.998 / 7.79 f72 | 0.998 / 3.32 f63 | 0.998 / 9.97 | 0.999 / 9.77 f85 | 0.996 / 3.34 f78 |
| correlated | 5 % | 11,024 | 7.12 | 0.265 / 0.92 s423 | 0.997 / 15.46 | 0.775 / 1.74 | 1.000 / 16.31 f358 | 1.000 / 11.79 f355 | 0.998 / 19.89 | 1.000 / 17.78 f369 | 0.999 / 11.41 f363 |
| correlated | 1 % | 2,205 | 1.50 | 0.052 / 0.90 s492 | 0.999 / 64.50 | 0.378 / 0.10 | 1.000 / 3.40 f497 | 1.000 / 2.45 f497 | 0.998 / 81.57 | 1.000 / 3.75 f497 | 1.000 / 2.71 f497 |
| correlated | 0.10 % | 218 | 0.09 | 0.002 / 0.90 s499 | 1.000 / 188.03 | 0.064 / 0.05 | 1.000 / 0.27 f500 | 1.000 / 0.21 f500 | 1.000 / 214.80 | 1.000 / 0.31 f500 | 1.000 / 0.48 f500 |
| correlated | 0.01 % | 21 | 0.01 | 0.000 / 0.90 s500 | 1.000 / 240.85 | 0.014 / 0.05 | 1.000 / 0.01 f500 | 1.000 / 0.01 f500 | 1.000 / 266.50 | 1.000 / 0.01 f500 | 1.000 / 0.33 f500 |
| excluded | 95 % | 203,387 | 46.54 | 0.992 / 0.93 s3 | 0.998 / 0.98 | 0.994 / 1.01 | 0.998 / 0.96 | 0.998 / 0.45 | 0.995 / 1.15 | 0.995 / 1.13 | 0.992 / 0.70 |
| excluded | 90 % | 192,347 | 45.79 | 0.990 / 0.93 s3 | 0.998 / 1.02 | 0.994 / 0.97 | 0.998 / 1.03 | 0.998 / 0.47 | 0.995 / 1.23 | 0.995 / 1.34 | 0.991 / 0.79 |
| excluded | 85 % | 181,399 | 44.68 | 0.989 / 0.92 s6 | 0.999 / 1.07 | 0.990 / 1.02 | 0.999 / 1.07 | 0.999 / 0.50 | 0.995 / 1.43 | 0.995 / 1.27 | 0.993 / 0.74 |
| excluded | 50 % | 104,865 | 38.67 | 0.865 / 0.90 s90 | 0.998 / 1.84 | 0.951 / 1.14 | 0.998 / 1.86 | 0.998 / 0.85 | 0.998 / 2.31 | 0.998 / 2.31 | 0.993 / 1.06 |

##### Membership structure at 50 % (in-walk, p50 / p95 ms)

| configuration | shape | primary-key bitmap (default) | `boolean[]` | flat bitset | Roaring over ordinals |
|---|---|---|---|---|---|
| Lucene float32 | random | 1.53 / 2.19 | 1.52 / 2.22 | 1.50 / 2.18 | 1.50 / 2.16 |
| Lucene float32 | correlated | 1.51 / 4.64 | 1.52 / 4.72 | 1.50 / 4.60 | 1.49 / 4.58 |
| Lucene float32 | excluded | 1.84 / 11.29 | 1.87 / 10.91 | 2.05 / 12.71 | 1.84 / 11.20 |
| jVector float32 | random | 1.88 / 3.03 | 1.88 / 2.98 | 1.87 / 2.94 | 1.83 / 2.87 |
| jVector float32 | correlated | 1.85 / 5.84 | 1.82 / 5.68 | 1.89 / 5.93 | 1.84 / 5.97 |
| jVector float32 | excluded | 2.31 / 15.39 | 2.25 / 15.46 | 2.30 / 15.88 | 2.28 / 14.89 |

#### Corpus D — 416,559 vectors, large (1M stand-in), held-out queries

| shape | selectivity | members | exact scan p50 | Lucene float32 post-filter | Lucene float32 in-walk | Lucene float32 ACORN | Lucene float32 planner | Lucene OSQ 4-bit planner | jVector float32 in-walk | jVector float32 planner | jVector PQ planner |
|---|---|---|---|---|---|---|---|---|---|---|---|
| random | 100 % | 416,559 | 97.41 | 0.986 / 1.17 | 0.986 / 1.17 | 0.986 / 1.22 | 0.986 / 1.22 | 0.987 / 0.57 | 0.989 / 1.50 | 0.989 / 1.42 | 0.979 / 0.81 |
| random | 95 % | 396,128 | 97.73 | 0.986 / 1.29 | 0.987 / 1.28 | 0.985 / 1.32 | 0.987 / 1.43 | 0.988 / 0.66 | 0.989 / 1.49 | 0.989 / 1.52 | 0.979 / 0.82 |
| random | 90 % | 375,666 | 96.18 | 0.987 / 1.30 | 0.990 / 1.49 | 0.986 / 1.33 | 0.990 / 1.36 | 0.989 / 0.68 | 0.991 / 1.59 | 0.991 / 1.68 | 0.982 / 0.95 |
| random | 85 % | 355,164 | 108.00 | 0.987 / 1.14 | 0.992 / 1.33 | 0.984 / 1.30 | 0.992 / 1.44 | 0.990 / 0.64 | 0.990 / 1.70 | 0.990 / 1.90 | 0.981 / 0.91 |
| random | 50 % | 210,554 | 94.12 | 0.985 / 1.18 | 0.994 / 2.15 | 0.985 / 2.14 | 0.994 / 1.97 | 0.993 / 0.91 | 0.994 / 2.42 | 0.994 / 2.50 | 0.985 / 1.12 |
| random | 10 % | 42,602 | 27.30 | 0.880 / 1.08 s198 | 0.998 / 7.56 | 0.943 / 1.59 | 0.998 / 7.52 | 0.998 / 3.11 | 0.997 / 9.17 | 0.997 / 8.95 | 0.993 / 3.23 |
| random | 5 % | 21,291 | 14.43 | 0.510 / 1.39 s482 | 0.998 / 13.07 | 0.881 / 1.91 | 0.998 / 13.80 f20 | 0.998 / 6.20 f22 | 0.999 / 16.14 | 0.999 / 16.60 f64 | 0.997 / 5.76 f70 |
| random | 1 % | 4,258 | 3.94 | 0.111 / 1.17 s500 | 1.000 / 49.98 | 0.643 / 2.27 | 1.000 / 7.49 f500 | 1.000 / 4.98 f500 | 0.999 / 63.86 | 1.000 / 7.50 f500 | 1.000 / 4.62 f500 |
| random | 0.10 % | 423 | 0.14 | 0.011 / 1.10 s500 | 1.000 / 255.55 | 0.023 / 0.09 | 1.000 / 0.57 f500 | 1.000 / 0.34 f500 | 1.000 / 307.26 | 1.000 / 0.55 f500 | 1.000 / 0.58 f500 |
| random | 0.01 % | 43 | 0.03 | 0.000 / 1.07 s500 | 1.000 / 521.34 | 0.003 / 0.08 | 1.000 / 0.02 f500 | 1.000 / 0.03 f500 | 1.000 / 585.19 | 1.000 / 0.03 f500 | 1.000 / 0.34 f500 |
| correlated | 95 % | 395,831 | 93.34 | 0.986 / 1.13 | 0.986 / 1.23 | 0.986 / 1.47 | 0.986 / 1.38 | 0.988 / 0.63 | 0.989 / 1.72 | 0.989 / 1.45 | 0.979 / 0.88 |
| correlated | 90 % | 375,120 | 93.12 | 0.986 / 1.36 | 0.986 / 1.58 | 0.985 / 1.27 | 0.986 / 1.45 | 0.987 / 0.61 | 0.989 / 1.56 | 0.989 / 1.58 | 0.980 / 0.83 |
| correlated | 85 % | 354,408 | 92.86 | 0.982 / 1.39 s2 | 0.986 / 1.31 | 0.983 / 1.42 | 0.986 / 1.36 | 0.988 / 0.61 | 0.988 / 1.66 | 0.988 / 1.57 | 0.980 / 0.86 |
| correlated | 50 % | 209,204 | 84.11 | 0.928 / 1.30 s50 | 0.991 / 1.96 | 0.964 / 1.81 | 0.991 / 1.91 | 0.990 / 0.95 | 0.991 / 2.40 | 0.991 / 2.80 | 0.980 / 1.09 |
| correlated | 10 % | 42,285 | 26.11 | 0.375 / 1.12 s360 | 0.990 / 13.10 | 0.798 / 1.95 | 0.990 / 14.05 f54 | 0.990 / 5.64 f52 | 0.991 / 16.24 | 0.993 / 16.21 f76 | 0.983 / 5.80 f78 |
| correlated | 5 % | 21,194 | 13.79 | 0.224 / 1.12 s419 | 0.993 / 27.91 | 0.614 / 1.98 | 0.998 / 32.33 f322 | 0.998 / 23.04 f317 | 0.994 / 35.63 | 0.998 / 35.06 f336 | 0.996 / 21.83 f332 |
| correlated | 1 % | 4,197 | 3.14 | 0.063 / 1.13 s485 | 0.997 / 104.29 | 0.283 / 0.13 | 1.000 / 6.96 f495 | 1.000 / 5.31 f495 | 0.998 / 131.02 | 1.000 / 7.62 f495 | 1.000 / 5.07 f495 |
| correlated | 0.10 % | 425 | 0.23 | 0.006 / 1.12 s499 | 0.999 / 291.47 | 0.079 / 0.08 | 1.000 / 0.68 f500 | 1.000 / 0.47 f500 | 1.000 / 343.92 | 1.000 / 0.68 f500 | 1.000 / 0.69 f500 |
| correlated | 0.01 % | 42 | 0.03 | 0.000 / 1.09 s500 | 1.000 / 522.16 | 0.010 / 0.08 | 1.000 / 0.02 f500 | 1.000 / 0.03 f500 | 1.000 / 582.15 | 1.000 / 0.03 f500 | 1.000 / 0.36 f500 |
| excluded | 95 % | 395,365 | 94.52 | 0.983 / 1.18 s2 | 0.990 / 1.20 | 0.985 / 1.28 | 0.990 / 1.18 | 0.988 / 0.55 | 0.990 / 1.59 | 0.990 / 1.59 | 0.982 / 0.86 |
| excluded | 90 % | 374,274 | 93.65 | 0.979 / 1.21 s6 | 0.990 / 1.44 | 0.973 / 1.40 | 0.990 / 1.34 | 0.990 / 0.75 | 0.992 / 1.64 | 0.992 / 1.61 | 0.984 / 0.86 |
| excluded | 85 % | 353,260 | 92.49 | 0.967 / 1.30 s15 | 0.991 / 1.31 | 0.967 / 1.24 | 0.991 / 1.33 | 0.990 / 0.83 | 0.992 / 1.64 | 0.992 / 1.71 | 0.983 / 0.88 |
| excluded | 50 % | 207,356 | 80.18 | 0.807 / 1.24 s122 | 0.993 / 2.50 | 0.882 / 1.49 | 0.993 / 2.52 | 0.993 / 1.24 | 0.995 / 3.61 | 0.995 / 3.26 | 0.982 / 1.39 |

##### Membership structure at 50 % (in-walk, p50 / p95 ms)

| configuration | shape | primary-key bitmap (default) | `boolean[]` | flat bitset | Roaring over ordinals |
|---|---|---|---|---|---|
| Lucene float32 | random | 2.15 / 3.27 | 1.96 / 2.90 | 1.97 / 2.97 | 1.93 / 2.94 |
| Lucene float32 | correlated | 1.96 / 6.07 | 1.88 / 5.89 | 1.94 / 5.93 | 2.22 / 7.49 |
| Lucene float32 | excluded | 2.50 / 29.63 | 2.47 / 30.08 | 2.60 / 30.55 | 2.69 / 32.45 |
| jVector float32 | random | 2.42 / 3.69 | 2.77 / 4.33 | 2.45 / 3.69 | 2.68 / 4.35 |
| jVector float32 | correlated | 2.40 / 7.33 | 2.31 / 7.07 | 2.35 / 7.39 | 2.82 / 9.84 |
| jVector float32 | excluded | 3.61 / 39.22 | 3.47 / 41.50 | 3.23 / 39.66 | 3.14 / 37.43 |

#### Corpus E — 312,787 vectors, large, short product texts, held-out queries

| shape | selectivity | members | exact scan p50 | Lucene float32 post-filter | Lucene float32 in-walk | Lucene float32 ACORN | Lucene float32 planner | Lucene OSQ 4-bit planner | jVector float32 in-walk | jVector float32 planner | jVector PQ planner |
|---|---|---|---|---|---|---|---|---|---|---|---|
| random | 100 % | 312,787 | 80.82 | 0.978 / 0.34 | 0.978 / 0.34 | 0.978 / 0.40 | 0.978 / 0.34 | 0.976 / 0.20 | 0.981 / 0.49 | 0.981 / 0.50 | 0.923 / 0.51 |
| random | 95 % | 297,196 | 82.05 | 0.977 / 0.37 | 0.977 / 0.40 | 0.963 / 0.33 | 0.977 / 0.33 | 0.975 / 0.20 | 0.983 / 0.52 | 0.983 / 0.51 | 0.922 / 0.51 |
| random | 90 % | 281,603 | 85.38 | 0.976 / 0.35 | 0.976 / 0.38 | 0.950 / 0.42 | 0.976 / 0.35 | 0.974 / 0.19 | 0.983 / 0.51 | 0.983 / 0.51 | 0.926 / 0.50 |
| random | 85 % | 266,010 | 85.16 | 0.978 / 0.34 | 0.979 / 0.40 | 0.938 / 0.39 | 0.979 / 0.39 | 0.977 / 0.21 | 0.985 / 0.56 | 0.985 / 0.56 | 0.928 / 0.53 |
| random | 50 % | 156,737 | 70.16 | 0.975 / 0.33 | 0.981 / 0.54 | 0.870 / 0.61 | 0.981 / 0.57 | 0.981 / 0.27 | 0.988 / 0.79 | 0.988 / 0.80 | 0.953 / 0.61 |
| random | 10 % | 31,493 | 20.46 | 0.858 / 0.32 s230 | 0.992 / 1.99 | 0.582 / 0.46 | 0.992 / 2.00 | 0.992 / 0.89 | 0.998 / 2.96 | 0.998 / 3.11 | 0.980 / 1.31 |
| random | 5 % | 15,812 | 10.85 | 0.486 / 0.32 s479 | 0.995 / 3.88 | 0.437 / 0.62 | 0.995 / 3.87 | 0.995 / 1.75 | 0.999 / 5.92 | 0.999 / 5.93 | 0.985 / 2.31 |
| random | 1 % | 3,216 | 1.23 | 0.102 / 0.31 s500 | 0.998 / 19.11 | 0.451 / 1.47 | 1.000 / 4.26 f500 | 1.000 / 2.70 f500 | 0.999 / 27.07 | 1.000 / 4.79 f500 | 1.000 / 2.85 f500 |
| random | 0.10 % | 336 | 0.11 | 0.011 / 0.30 s500 | 0.998 / 127.75 | 0.028 / 0.05 | 1.000 / 0.35 f500 | 1.000 / 0.23 f500 | 1.000 / 175.27 | 1.000 / 0.34 f500 | 1.000 / 0.50 f500 |
| random | 0.01 % | 31 | 0.02 | 0.001 / 0.33 s500 | 1.000 / 309.83 | 0.004 / 0.05 | 1.000 / 0.02 f500 | 1.000 / 0.02 f500 | 1.000 / 369.40 | 1.000 / 0.02 f500 | 1.000 / 0.33 f500 |
| correlated | 95 % | 297,451 | 78.24 | 0.972 / 0.35 s5 | 0.978 / 0.37 | 0.975 / 0.38 | 0.978 / 0.36 | 0.976 / 0.19 | 0.981 / 0.52 | 0.981 / 0.52 | 0.924 / 0.52 |
| correlated | 90 % | 281,769 | 75.92 | 0.960 / 0.34 s11 | 0.977 / 0.37 | 0.967 / 0.35 | 0.977 / 0.37 | 0.975 / 0.22 | 0.979 / 0.55 | 0.979 / 0.59 | 0.923 / 0.52 |
| correlated | 85 % | 266,091 | 72.86 | 0.945 / 0.33 s21 | 0.976 / 0.39 | 0.959 / 0.35 | 0.976 / 0.40 | 0.974 / 0.22 | 0.982 / 0.64 | 0.982 / 0.65 | 0.926 / 0.55 |
| correlated | 50 % | 156,522 | 50.46 | 0.702 / 0.32 s155 | 0.974 / 0.58 | 0.816 / 0.37 | 0.974 / 0.57 | 0.975 / 0.30 | 0.979 / 0.89 | 0.979 / 0.88 | 0.922 / 0.63 |
| correlated | 10 % | 31,279 | 13.70 | 0.238 / 0.33 s394 | 0.982 / 5.87 | 0.428 / 0.08 | 0.983 / 6.07 f70 | 0.982 / 2.74 f67 | 0.980 / 9.27 | 0.982 / 9.46 f102 | 0.938 / 3.67 f105 |
| correlated | 5 % | 15,639 | 7.01 | 0.158 / 0.31 s429 | 0.975 / 13.45 | 0.294 / 0.07 | 0.987 / 17.97 f256 | 0.987 / 11.78 f255 | 0.987 / 19.71 | 0.995 / 20.53 f281 | 0.962 / 11.93 f276 |
| correlated | 1 % | 3,128 | 1.58 | 0.044 / 0.31 s483 | 0.987 / 53.34 | 0.236 / 0.07 | 1.000 / 3.95 f477 | 1.000 / 2.73 f478 | 0.996 / 75.59 | 1.000 / 4.32 f484 | 0.995 / 2.99 f475 |
| correlated | 0.10 % | 313 | 0.12 | 0.010 / 0.33 s496 | 1.000 / 135.45 | 0.087 / 0.06 | 1.000 / 0.38 f500 | 1.000 / 0.25 f500 | 1.000 / 185.46 | 1.000 / 0.37 f500 | 1.000 / 0.51 f500 |
| correlated | 0.01 % | 31 | 0.02 | 0.000 / 0.30 s500 | 1.000 / 311.85 | 0.016 / 0.05 | 1.000 / 0.02 f500 | 1.000 / 0.02 f500 | 1.000 / 366.88 | 1.000 / 0.02 f500 | 1.000 / 0.33 f500 |
| excluded | 95 % | 297,148 | 78.31 | 0.964 / 0.34 s9 | 0.978 / 0.36 | 0.958 / 0.36 | 0.978 / 0.36 | 0.976 / 0.23 | 0.982 / 0.52 | 0.982 / 0.60 | 0.929 / 0.54 |
| excluded | 90 % | 281,508 | 75.54 | 0.936 / 0.34 s27 | 0.980 / 0.39 | 0.942 / 0.35 | 0.980 / 0.39 | 0.978 / 0.24 | 0.984 / 0.56 | 0.984 / 0.55 | 0.927 / 0.51 |
| excluded | 85 % | 265,869 | 72.79 | 0.907 / 0.33 s40 | 0.983 / 0.38 | 0.911 / 0.35 | 0.983 / 0.40 | 0.980 / 0.22 | 0.986 / 0.57 | 0.986 / 0.57 | 0.931 / 0.52 |
| excluded | 50 % | 156,266 | 50.84 | 0.653 / 0.33 s185 | 0.984 / 0.75 | 0.704 / 0.29 | 0.984 / 0.73 | 0.982 / 0.37 | 0.993 / 1.19 | 0.993 / 1.17 f2 | 0.927 / 0.78 f2 |

##### Membership structure at 50 % (in-walk, p50 / p95 ms)

| configuration | shape | primary-key bitmap (default) | `boolean[]` | flat bitset | Roaring over ordinals |
|---|---|---|---|---|---|
| Lucene float32 | random | 0.54 / 0.92 | 0.58 / 1.01 | 0.54 / 0.94 | 0.54 / 0.91 |
| Lucene float32 | correlated | 0.58 / 5.47 | 0.57 / 5.47 | 0.57 / 5.25 | 0.57 / 5.54 |
| Lucene float32 | excluded | 0.75 / 58.12 | 0.72 / 55.63 | 0.75 / 57.89 | 0.74 / 58.17 |
| jVector float32 | random | 0.79 / 1.49 | 0.80 / 1.49 | 0.83 / 1.61 | 0.78 / 1.49 |
| jVector float32 | correlated | 0.89 / 8.01 | 0.86 / 7.57 | 0.87 / 7.67 | 0.89 / 8.12 |
| jVector float32 | excluded | 1.19 / 85.19 | 1.18 / 81.26 | 1.14 / 83.24 | 1.21 / 87.78 |

#### Where the planner switches to the exact scan, across corpora

|  | A | B | C | D | E |
|---|---|---|---|---|---|
| queries per cell | 107 | 500 | 500 | 500 | 500 |
| exact scan of the members faster than Lucene float32 in-walk (p50) from (random / correlated) | 10 % / 10 % | 10 % / 10 % | 5 % / 5 % | 1 % / 5 % | 1 % / 5 % |
| Lucene float32 in-walk keeps recall@10 ≥ 0.95 at 4× and p95 < 10 ms down to (random / correlated) | 1 % / 5 % | 5 % / 50 % | 10 % / 50 % | 50 % / 50 % | 5 % / 50 % |
| exact scan of 1 % of the corpus (random), p50 ms | 0.05 | 0.08 | 0.95 | 3.94 | 1.23 |

#### ACORN over denser Lucene graphs (follow-up run, 2026-11-09)

**What and why.** ACORN lost recall over the Lucene graph because its walk runs out of members within two hops (see the
deviations), and Lucene's graph at `M = 16` has a mean level-0 degree of only 6.9–11 of the 32 allowed. This run builds
Lucene graphs with `M` 16, 32 and 64 and compares in-walk with ACORN under the real filter and random filters of about
50, 10 and 1 %: float32, 200 queries (107 on A), recall@10 at 4×, visited nodes in brackets.
##### Corpus A — 14,837 vectors, small, real clicks

Mean level-0 degree: M=16: 8.0 of 32, M=32: 8.1 of 64, M=64: 8.1 of 128.

| filter | M=16 in-walk | M=16 ACORN | M=32 in-walk | M=32 ACORN | M=64 in-walk | M=64 ACORN |
|---|---|---|---|---|---|---|
| real filter 1.66 % | 1.000 (13,420) | 0.393 (149) | 1.000 (13,449) | 0.355 (134) | 1.000 (13,452) | 0.327 (123) |
| random filter 49.65 % | 0.972 (913) | 0.865 (876) | 0.989 (968) | 0.865 (998) | 0.992 (997) | 0.879 (1,051) |
| random filter 9.65 % | 0.984 (3,090) | 0.570 (393) | 0.995 (3,183) | 0.579 (400) | 0.995 (3,227) | 0.557 (397) |
| random filter 1.02 % | 0.999 (11,659) | 0.558 (145) | 1.000 (11,698) | 0.672 (165) | 1.000 (11,710) | 0.147 (63) |

##### Corpus B — 22,895 vectors, medium, real clicks

Mean level-0 degree: M=16: 11.4 of 32, M=32: 11.7 of 64, M=64: 11.7 of 128.

| filter | M=16 in-walk | M=16 ACORN | M=32 in-walk | M=32 ACORN | M=64 in-walk | M=64 ACORN |
|---|---|---|---|---|---|---|
| real filter 29.71 % | 0.988 (2,633) | 0.952 (1,104) | 0.993 (2,961) | 0.962 (1,270) | 0.993 (3,018) | 0.976 (1,304) |
| random filter 49.80 % | 0.983 (1,584) | 0.964 (1,462) | 0.990 (1,779) | 0.966 (1,697) | 0.990 (1,808) | 0.965 (1,731) |
| random filter 9.89 % | 0.926 (5,151) | 0.815 (585) | 0.998 (5,336) | 0.877 (606) | 0.998 (5,348) | 0.846 (608) |
| random filter 0.94 % | 0.887 (19,643) | 0.684 (207) | 0.964 (19,816) | 0.887 (259) | 1.000 (19,752) | 0.211 (81) |

##### Corpus E — 312,787 vectors, large, short product texts, held-out queries

Mean level-0 degree: M=16: 6.9 of 32, M=32: 7.0 of 64, M=64: 7.1 of 128.

| filter | M=16 in-walk | M=16 ACORN | M=32 in-walk | M=32 ACORN | M=64 in-walk | M=64 ACORN |
|---|---|---|---|---|---|---|
| real filter 38.93 % | 0.980 (925) | 0.188 (115) | 0.991 (966) | 0.178 (117) | 0.988 (988) | 0.128 (118) |
| random filter 49.94 % | 0.979 (803) | 0.860 (773) | 0.988 (840) | 0.847 (834) | 0.984 (861) | 0.848 (867) |
| random filter 10.13 % | 0.993 (2,842) | 0.589 (452) | 0.993 (3,001) | 0.575 (494) | 0.991 (3,040) | 0.577 (519) |
| random filter 1.07 % | 1.000 (21,446) | 0.454 (290) | 1.000 (22,490) | 0.659 (654) | 0.998 (22,763) | 0.089 (125) |

**Result.** A larger `M` does not make the Lucene graph denser: the mean level-0 degree stays at 8.0–8.1 on A, 11.4–11.7
on B and 6.9–7.1 on E for every `M`, and the graph's heap barely grows. Lucene's diversity rule keeps a neighbour only
when it is closer to the node than to every neighbour already kept; on these embeddings it prunes the lists to the same
length whatever the bound. The density is set by the data together with the pruning rule, not by `M`; jVector's relaxed
rule (`alpha = 1.2`) fills its bound of 32. ACORN therefore does not recover (E's real filter: 0.13–0.19), and under a 1
% filter `M = 64` is worse still (0.09–0.21): ACORN's exploration constants scale with `M`, and `minToScore` drops to
zero once 2·M exceeds the inverse selectivity. In-walk gains a little from a larger `M` on B (random 10 %: 0.926 at `M =
16`, 0.998 at 32).

### 7. Relevance against users' clicks

**What is measured.** Whether the record a user clicked after a search appears in the ten results the vector
search returns for the same query text.

**Why.** recall@10 only checks the index against the exact scan; it would be 1.000 even if the embedding model ranked
nonsense. Clicks check the whole chain — model and index together — against what users actually wanted. They bound
how much a perfect index can deliver, and show whether a code or strategy costs any relevance in practice.

**What we expect.** Low absolute numbers (short, typed queries against long product texts; the clicked record is
often not the only correct one), and **no difference** between the exact scan and any configuration with
recall@10 ≥ 0.95: the index is not the limiting factor.

#### Corpus A — 31 queries with clicks, 41 (query, record) pairs

| configuration | strategy | factor | recall@10 | pairs hit@1 | pairs hit@10 | MRR | clicks hit@10 |
|---|---|---|---|---|---|---|---|
| exact float32 | all | — | 1.000 | 0.000 | 0.073 | 0.012 | 0.018 |
| exact float32 | filtered | — | 1.000 | 0.098 | 0.488 | 0.181 | 0.115 |
| Lucene float32 | all | 4× | 0.957 | 0.000 | 0.073 | 0.016 | 0.018 |
| Lucene float32 | post-filter | 4× | 0.103 | 0.073 | 0.073 | 0.073 | 0.018 |
| Lucene float32 | in-walk | 4× | 1.000 | 0.098 | 0.488 | 0.181 | 0.115 |
| Lucene float32 | ACORN | 4× | 0.393 | 0.049 | 0.195 | 0.089 | 0.040 |
| Lucene float32 | planner | 4× | 1.000 | 0.098 | 0.488 | 0.181 | 0.115 |
| Lucene OSQ 1-bit | all | 4× | 0.910 | 0.000 | 0.073 | 0.013 | 0.018 |
| Lucene OSQ 1-bit | post-filter | 4× | 0.095 | 0.073 | 0.073 | 0.073 | 0.018 |
| Lucene OSQ 1-bit | in-walk | 4× | 1.000 | 0.098 | 0.488 | 0.181 | 0.115 |
| Lucene OSQ 1-bit | ACORN | 4× | 0.243 | 0.049 | 0.122 | 0.065 | 0.026 |
| Lucene OSQ 1-bit | planner | 4× | 1.000 | 0.098 | 0.488 | 0.181 | 0.115 |
| Lucene OSQ 4-bit | all | 4× | 0.945 | 0.000 | 0.073 | 0.016 | 0.018 |
| Lucene OSQ 4-bit | post-filter | 4× | 0.102 | 0.073 | 0.073 | 0.073 | 0.018 |
| Lucene OSQ 4-bit | in-walk | 4× | 1.000 | 0.098 | 0.488 | 0.181 | 0.115 |
| Lucene OSQ 4-bit | ACORN | 4× | 0.430 | 0.049 | 0.195 | 0.089 | 0.040 |
| Lucene OSQ 4-bit | planner | 4× | 1.000 | 0.098 | 0.488 | 0.181 | 0.115 |
| Lucene OSQ 7-bit | all | 4× | 0.957 | 0.000 | 0.073 | 0.016 | 0.018 |
| Lucene OSQ 7-bit | post-filter | 4× | 0.101 | 0.073 | 0.073 | 0.073 | 0.018 |
| Lucene OSQ 7-bit | in-walk | 4× | 1.000 | 0.098 | 0.488 | 0.181 | 0.115 |
| Lucene OSQ 7-bit | ACORN | 4× | 0.411 | 0.049 | 0.195 | 0.089 | 0.040 |
| Lucene OSQ 7-bit | planner | 4× | 1.000 | 0.098 | 0.488 | 0.181 | 0.115 |
| jVector PQ 256 × 8 | all | 4× | 0.909 | 0.000 | 0.073 | 0.016 | 0.018 |
| jVector PQ 256 × 8 | post-filter | 4× | 0.091 | 0.073 | 0.073 | 0.073 | 0.018 |
| jVector PQ 256 × 8 | in-walk | 4× | 1.000 | 0.098 | 0.488 | 0.181 | 0.115 |
| jVector BQ | all | 4× | 0.971 | 0.000 | 0.073 | 0.016 | 0.018 |
| jVector BQ | post-filter | 4× | 0.113 | 0.073 | 0.073 | 0.073 | 0.018 |
| jVector BQ | in-walk | 4× | 0.984 | 0.098 | 0.488 | 0.181 | 0.115 |

#### Corpus B — 892 queries with clicks, 1,195 (query, record) pairs

| configuration | strategy | factor | recall@10 | pairs hit@1 | pairs hit@10 | MRR | clicks hit@10 |
|---|---|---|---|---|---|---|---|
| exact float32 | all | — | 1.000 | 0.029 | 0.121 | 0.053 | 0.104 |
| exact float32 | filtered | — | 1.000 | 0.054 | 0.220 | 0.099 | 0.210 |
| Lucene float32 | all | 4× | 0.974 | 0.031 | 0.114 | 0.053 | 0.097 |
| Lucene float32 | post-filter | 4× | 0.852 | 0.052 | 0.186 | 0.089 | 0.173 |
| Lucene float32 | in-walk | 4× | 0.990 | 0.053 | 0.207 | 0.094 | 0.197 |
| Lucene float32 | ACORN | 4× | 0.958 | 0.052 | 0.202 | 0.092 | 0.193 |
| Lucene float32 | planner | 4× | 0.990 | 0.053 | 0.207 | 0.094 | 0.197 |
| Lucene OSQ 1-bit | all | 4× | 0.963 | 0.030 | 0.110 | 0.051 | 0.087 |
| Lucene OSQ 1-bit | post-filter | 4× | 0.811 | 0.050 | 0.185 | 0.086 | 0.165 |
| Lucene OSQ 1-bit | in-walk | 4× | 0.975 | 0.053 | 0.205 | 0.094 | 0.196 |
| Lucene OSQ 1-bit | ACORN | 4× | 0.937 | 0.053 | 0.199 | 0.092 | 0.192 |
| Lucene OSQ 1-bit | planner | 4× | 0.975 | 0.053 | 0.205 | 0.094 | 0.196 |
| Lucene OSQ 4-bit | all | 4× | 0.972 | 0.030 | 0.111 | 0.052 | 0.090 |
| Lucene OSQ 4-bit | post-filter | 4× | 0.840 | 0.051 | 0.186 | 0.088 | 0.169 |
| Lucene OSQ 4-bit | in-walk | 4× | 0.990 | 0.053 | 0.206 | 0.094 | 0.196 |
| Lucene OSQ 4-bit | ACORN | 4× | 0.959 | 0.052 | 0.198 | 0.091 | 0.191 |
| Lucene OSQ 4-bit | planner | 4× | 0.990 | 0.053 | 0.206 | 0.094 | 0.196 |
| Lucene OSQ 7-bit | all | 4× | 0.975 | 0.031 | 0.114 | 0.053 | 0.097 |
| Lucene OSQ 7-bit | post-filter | 4× | 0.848 | 0.052 | 0.189 | 0.089 | 0.176 |
| Lucene OSQ 7-bit | in-walk | 4× | 0.990 | 0.053 | 0.207 | 0.094 | 0.197 |
| Lucene OSQ 7-bit | ACORN | 4× | 0.957 | 0.052 | 0.201 | 0.092 | 0.193 |
| Lucene OSQ 7-bit | planner | 4× | 0.990 | 0.053 | 0.207 | 0.094 | 0.197 |
| jVector PQ 256 × 8 | all | 4× | 0.921 | 0.029 | 0.114 | 0.052 | 0.096 |
| jVector PQ 256 × 8 | post-filter | 4× | 0.683 | 0.049 | 0.171 | 0.083 | 0.156 |
| jVector PQ 256 × 8 | in-walk | 4× | 0.972 | 0.054 | 0.207 | 0.096 | 0.198 |
| jVector BQ | all | 4× | 0.898 | 0.028 | 0.113 | 0.051 | 0.098 |
| jVector BQ | post-filter | 4× | 0.692 | 0.054 | 0.180 | 0.088 | 0.165 |
| jVector BQ | in-walk | 4× | 0.912 | 0.055 | 0.212 | 0.098 | 0.199 |

#### Corpora C, D, E

No clicks: these corpora have no search log. Not measurable.

### 8. Our searcher over the jVector graph, and one graph walked by every code

**What is measured.** A graph walk written by us (`OwnGraphSearcher`) against jVector's own `GraphSearcher`, on the
same jVector graph: recall, visited nodes and latency per code; then whether a running walk can be cancelled.

**Why.** evitaDB needs control over the walk that neither library offers: one graph shared by every code (no graph
per code, spec §3.5), its own filter and planner, and cancelling a query that runs too long (spec §2.7). If our
walk is as good as the library's, the library is needed only to build the graph.

**What we expect.** The same recall within noise, a comparable number of visited nodes and comparable latency, and
every walk stopping when cancelled.

#### Corpus A — 14,837 vectors, small, real clicks

| code, searcher | strategy | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|
| BQ, GraphSearcher | all | 858 | 0.750 | 0.907 | 0.961 | 0.969 | 0.10 / 0.13 |
| BQ, our searcher | all | 903 | 0.750 | 0.910 | 0.961 | 0.970 | 0.07 / 0.10 |
| PQ 256 × 8, GraphSearcher | all | 786 | 0.753 | 0.888 | 0.930 | 0.952 | 0.53 / 0.58 |
| PQ 256 × 8, our searcher | all | 832 | 0.754 | 0.888 | 0.930 | 0.952 | 0.49 / 0.55 |
| float32, GraphSearcher | all | 840 | 0.979 | 0.979 | 0.979 | 0.979 | 0.60 / 0.77 |
| float32, our searcher | all | 884 | 0.979 | 0.979 | 0.979 | 0.979 | 0.55 / 0.73 |
| jVector graph + Lucene OSQ 1-bit, our searcher | all | 913 | 0.843 | 0.949 | 0.967 | 0.970 | 0.11 / 0.14 |
| jVector graph + Lucene OSQ 4-bit, our searcher | all | 890 | 0.941 | 0.975 | 0.976 | 0.976 | 0.15 / 0.21 |
| BQ, GraphSearcher | in-walk | 13,835 | 0.766 | 0.916 | 0.984 | 1.000 | 5.02 / 6.36 |
| BQ, our searcher | in-walk | 13,937 | 0.768 | 0.917 | 0.983 | 0.999 | 2.26 / 2.71 |
| PQ 256 × 8, GraphSearcher | in-walk | 13,638 | 0.857 | 0.981 | 1.000 | 1.000 | 7.79 / 8.19 |
| PQ 256 × 8, our searcher | in-walk | 13,748 | 0.857 | 0.981 | 1.000 | 1.000 | 5.16 / 5.75 |
| float32, GraphSearcher | in-walk | 13,733 | 1.000 | 1.000 | 1.000 | 1.000 | 17.33 / 19.29 |
| float32, our searcher | in-walk | 13,844 | 1.000 | 1.000 | 1.000 | 1.000 | 14.12 / 15.83 |
| jVector graph + Lucene OSQ 1-bit, our searcher | in-walk | 14,029 | 0.894 | 0.991 | 1.000 | 1.000 | 2.79 / 4.37 |
| jVector graph + Lucene OSQ 4-bit, our searcher | in-walk | 13,917 | 0.969 | 1.000 | 1.000 | 1.000 | 3.44 / 4.45 |
| BQ, our searcher | planner | 248 | 1.000 | 1.000 | 1.000 | 1.000 | 0.09 / 0.09 |
| PQ 256 × 8, our searcher | planner | 248 | 1.000 | 1.000 | 1.000 | 1.000 | 0.41 / 0.45 |
| float32, our searcher | planner | 248 | 1.000 | 1.000 | 1.000 | 1.000 | 0.20 / 0.24 |
| jVector graph + Lucene OSQ 1-bit, our searcher | planner | 248 | 1.000 | 1.000 | 1.000 | 1.000 | 0.12 / 0.13 |
| jVector graph + Lucene OSQ 4-bit, our searcher | planner | 248 | 1.000 | 1.000 | 1.000 | 1.000 | 0.13 / 0.14 |

Cancellation (flag set after 20 checks): **107 of 107** walks cancelled, 0 finished before the flag, 0 checks after it;
an uncancelled walk scores 13,844 nodes.

#### Corpus B — 22,895 vectors, medium, real clicks

| code, searcher | strategy | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|
| BQ, GraphSearcher | all | 1,053 | 0.632 | 0.804 | 0.900 | 0.944 | 0.12 / 0.17 |
| BQ, our searcher | all | 1,110 | 0.632 | 0.801 | 0.897 | 0.943 | 0.07 / 0.10 |
| PQ 256 × 8, GraphSearcher | all | 905 | 0.668 | 0.833 | 0.917 | 0.951 | 0.53 / 0.64 |
| PQ 256 × 8, our searcher | all | 965 | 0.669 | 0.833 | 0.917 | 0.951 | 0.51 / 0.64 |
| float32, GraphSearcher | all | 1,048 | 0.981 | 0.981 | 0.981 | 0.981 | 0.72 / 1.03 |
| float32, our searcher | all | 1,109 | 0.980 | 0.980 | 0.980 | 0.980 | 0.69 / 0.98 |
| jVector graph + Lucene OSQ 1-bit, our searcher | all | 1,127 | 0.787 | 0.940 | 0.971 | 0.975 | 0.12 / 0.15 |
| jVector graph + Lucene OSQ 4-bit, our searcher | all | 1,112 | 0.917 | 0.977 | 0.978 | 0.978 | 0.18 / 0.25 |
| BQ, GraphSearcher | in-walk | 3,071 | 0.624 | 0.797 | 0.911 | 0.968 | 0.32 / 0.47 |
| BQ, our searcher | in-walk | 3,131 | 0.624 | 0.796 | 0.911 | 0.969 | 0.17 / 0.26 |
| PQ 256 × 8, GraphSearcher | in-walk | 2,859 | 0.713 | 0.861 | 0.970 | 0.990 | 1.07 / 1.46 |
| PQ 256 × 8, our searcher | in-walk | 2,931 | 0.712 | 0.860 | 0.969 | 0.989 | 0.92 / 1.24 |
| float32, GraphSearcher | in-walk | 2,934 | 0.995 | 0.995 | 0.995 | 0.995 | 2.28 / 3.50 |
| float32, our searcher | in-walk | 3,005 | 0.995 | 0.995 | 0.995 | 0.995 | 2.12 / 3.24 |
| jVector graph + Lucene OSQ 1-bit, our searcher | in-walk | 3,074 | 0.781 | 0.941 | 0.985 | 0.994 | 0.27 / 0.37 |
| jVector graph + Lucene OSQ 4-bit, our searcher | in-walk | 3,013 | 0.922 | 0.994 | 0.995 | 0.995 | 0.41 / 0.61 |
| BQ, our searcher | planner | 3,127 | 0.626 | 0.797 | 0.911 | 0.969 | 0.17 / 0.26 |
| PQ 256 × 8, our searcher | planner | 2,928 | 0.713 | 0.860 | 0.969 | 0.989 | 0.92 / 1.24 |
| float32, our searcher | planner | 3,003 | 0.995 | 0.995 | 0.995 | 0.995 | 2.06 / 3.15 |
| jVector graph + Lucene OSQ 1-bit, our searcher | planner | 3,071 | 0.782 | 0.941 | 0.985 | 0.994 | 0.27 / 0.39 |
| jVector graph + Lucene OSQ 4-bit, our searcher | planner | 3,011 | 0.923 | 0.994 | 0.995 | 0.995 | 0.40 / 0.59 |

Cancellation (flag set after 20 checks): **1000 of 1000** walks cancelled, 0 finished before the flag, 0 checks after
it; an uncancelled walk scores 3,005 nodes.

#### Corpus C — 214,410 vectors, large, held-out queries

| code, searcher | strategy | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|
| BQ, GraphSearcher | all | 1,296 | 0.791 | 0.944 | 0.984 | 0.994 | 0.21 / 0.28 |
| BQ, our searcher | all | 1,349 | 0.791 | 0.944 | 0.984 | 0.994 | 0.14 / 0.19 |
| PQ 256 × 8, GraphSearcher | all | 1,272 | 0.810 | 0.967 | 0.993 | 0.996 | 0.72 / 0.94 |
| PQ 256 × 8, our searcher | all | 1,331 | 0.810 | 0.967 | 0.993 | 0.996 | 0.66 / 0.84 |
| float32, GraphSearcher | all | 1,238 | 0.996 | 0.996 | 0.996 | 0.996 | 1.23 / 1.91 |
| float32, our searcher | all | 1,298 | 0.996 | 0.996 | 0.996 | 0.996 | 1.25 / 1.95 |
| jVector graph + Lucene OSQ 1-bit, our searcher | all | 1,321 | 0.864 | 0.985 | 0.995 | 0.996 | 0.30 / 0.45 |
| jVector graph + Lucene OSQ 4-bit, our searcher | all | 1,298 | 0.950 | 0.996 | 0.996 | 0.996 | 0.40 / 0.59 |

Cancellation (flag set after 20 checks): **1000 of 1000** walks cancelled, 0 finished before the flag, 0 checks after
it; an uncancelled walk scores 1,298 nodes.

#### Corpus D — 416,559 vectors, large (1M stand-in), held-out queries

| code, searcher | strategy | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|
| BQ, GraphSearcher | all | 1,385 | 0.776 | 0.928 | 0.975 | 0.986 | 0.24 / 0.32 |
| BQ, our searcher | all | 1,438 | 0.776 | 0.929 | 0.975 | 0.987 | 0.17 / 0.22 |
| PQ 256 × 8, GraphSearcher | all | 1,362 | 0.786 | 0.946 | 0.981 | 0.988 | 0.80 / 1.04 |
| PQ 256 × 8, our searcher | all | 1,419 | 0.786 | 0.946 | 0.980 | 0.987 | 0.74 / 0.95 |
| float32, GraphSearcher | all | 1,311 | 0.991 | 0.991 | 0.991 | 0.991 | 1.35 / 2.15 |
| float32, our searcher | all | 1,368 | 0.991 | 0.991 | 0.991 | 0.991 | 1.34 / 2.10 |
| jVector graph + Lucene OSQ 1-bit, our searcher | all | 1,393 | 0.860 | 0.980 | 0.991 | 0.991 | 0.31 / 0.44 |
| jVector graph + Lucene OSQ 4-bit, our searcher | all | 1,371 | 0.947 | 0.991 | 0.991 | 0.991 | 0.45 / 0.67 |

Cancellation (flag set after 20 checks): **1000 of 1000** walks cancelled, 0 finished before the flag, 0 checks after
it; an uncancelled walk scores 1,368 nodes.

#### Corpus E — 312,787 vectors, large, short product texts, held-out queries

| code, searcher | strategy | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|
| BQ, GraphSearcher | all | 653 | 0.868 | 0.963 | 0.977 | 0.980 | 0.14 / 0.22 |
| BQ, our searcher | all | 708 | 0.867 | 0.960 | 0.974 | 0.978 | 0.09 / 0.15 |
| PQ 256 × 8, GraphSearcher | all | 645 | 0.746 | 0.889 | 0.945 | 0.964 | 0.54 / 0.71 |
| PQ 256 × 8, our searcher | all | 708 | 0.747 | 0.889 | 0.945 | 0.964 | 0.49 / 0.64 |
| float32, GraphSearcher | all | 606 | 0.990 | 0.990 | 0.990 | 0.990 | 0.55 / 1.07 |
| float32, our searcher | all | 668 | 0.989 | 0.989 | 0.989 | 0.989 | 0.50 / 0.97 |
| jVector graph + Lucene OSQ 1-bit, our searcher | all | 688 | 0.911 | 0.979 | 0.984 | 0.984 | 0.15 / 0.24 |
| jVector graph + Lucene OSQ 4-bit, our searcher | all | 671 | 0.956 | 0.989 | 0.990 | 0.990 | 0.19 / 0.32 |
| BQ, GraphSearcher | in-walk | 1,365 | 0.850 | 0.968 | 0.989 | 0.993 | 0.26 / 0.48 |
| BQ, our searcher | in-walk | 1,424 | 0.850 | 0.966 | 0.987 | 0.992 | 0.16 / 0.31 |
| PQ 256 × 8, GraphSearcher | in-walk | 1,319 | 0.758 | 0.915 | 0.963 | 0.979 | 0.74 / 1.16 |
| PQ 256 × 8, our searcher | in-walk | 1,388 | 0.758 | 0.915 | 0.963 | 0.978 | 0.66 / 0.99 |
| float32, GraphSearcher | in-walk | 1,286 | 0.993 | 0.993 | 0.993 | 0.993 | 1.08 / 2.28 |
| float32, our searcher | in-walk | 1,358 | 0.993 | 0.993 | 0.993 | 0.993 | 1.41 / 5.60 |
| jVector graph + Lucene OSQ 1-bit, our searcher | in-walk | 1,388 | 0.904 | 0.989 | 0.993 | 0.994 | 0.25 / 0.46 |
| jVector graph + Lucene OSQ 4-bit, our searcher | in-walk | 1,358 | 0.954 | 0.992 | 0.992 | 0.992 | 0.34 / 0.66 |
| BQ, our searcher | planner | 1,424 | 0.850 | 0.966 | 0.987 | 0.992 | 0.16 / 0.30 |
| PQ 256 × 8, our searcher | planner | 1,388 | 0.758 | 0.915 | 0.963 | 0.978 | 0.66 / 1.01 |
| float32, our searcher | planner | 1,358 | 0.993 | 0.993 | 0.993 | 0.993 | 1.04 / 2.22 |
| jVector graph + Lucene OSQ 1-bit, our searcher | planner | 1,388 | 0.904 | 0.989 | 0.993 | 0.994 | 0.25 / 0.46 |
| jVector graph + Lucene OSQ 4-bit, our searcher | planner | 1,358 | 0.954 | 0.992 | 0.992 | 0.992 | 0.34 / 0.67 |

Cancellation (flag set after 20 checks): **1000 of 1000** walks cancelled, 0 finished before the flag, 0 checks after
it; an uncancelled walk scores 1,358 nodes.

### 9. Graph family

**What is measured.** jVector graphs built four ways: pruning parameter `alpha` 1.0 or 1.2, with or without the
upper layers (the **hierarchy**). For each: build time, heap, degree, and recall and latency per code.

**Why.** The hierarchy adds layers above the base graph that help the walk find the right region quickly; it costs
memory and complicates our own file format and searcher. Without it, the graph is one flat layer (DiskANN-style),
simpler to store and update. `alpha` trades degree (memory) for long-range edges. The spec (§3.2) leaves the choice
open until it is measured at scale — this is the measurement.

**What we expect.** On small corpora no difference. On large ones the flat graph needs more visited nodes for the
same recall; whether it loses recall at our sizes is the open question. `alpha = 1.2` gives more edges and higher
recall than 1.0 at more memory.

#### Corpus A — 14,837 vectors, small, real clicks

| alpha | hierarchy | build s | heap MB (B/vec) | levels (nodes / mean degree) |
|---|---|---|---|---|
| 1.0 | on | 1.8 | 6.03 (426) | 14,837 / 12.1 · 489 / 7.7 · 10 / 2.1 |
| 1.0 | off | 1.5 | 5.86 (414) | 14,837 / 12.2 |
| 1.2 | on | 5.3 | 5.65 (399) | 14,837 / 32.0 · 489 / 32.0 · 10 / 8.4 |
| 1.2 | off | 5.0 | 5.45 (385) | 14,837 / 32.0 |

| graph, code, searcher | strategy | visited | recall@10 1× | 4× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|
| alpha 1.0, flat, BQ | all | 743 | 0.746 | 0.960 | 0.08 / 0.10 |
| alpha 1.0, flat, PQ 256 × 8 | all | 731 | 0.771 | 0.947 | 0.50 / 0.53 |
| alpha 1.0, flat, PQ 256 × 8, our searcher | all | 733 | 0.772 | 0.947 | 0.46 / 0.49 |
| alpha 1.0, flat, float32 | all | 736 | 0.981 | 0.981 | 0.52 / 0.66 |
| alpha 1.0, flat, float32, our searcher | all | 734 | 0.981 | 0.981 | 0.45 / 0.56 |
| alpha 1.0, hierarchy, BQ | all | 736 | 0.755 | 0.962 | 0.08 / 0.09 |
| alpha 1.0, hierarchy, PQ 256 × 8 | all | 735 | 0.777 | 0.950 | 0.51 / 0.57 |
| alpha 1.0, hierarchy, PQ 256 × 8, our searcher | all | 745 | 0.771 | 0.941 | 0.49 / 0.54 |
| alpha 1.0, hierarchy, float32 | all | 732 | 0.978 | 0.978 | 0.53 / 0.69 |
| alpha 1.0, hierarchy, float32, our searcher | all | 744 | 0.978 | 0.978 | 0.44 / 0.57 |
| alpha 1.2, flat, BQ | all | 893 | 0.746 | 0.950 | 0.10 / 0.12 |
| alpha 1.2, flat, PQ 256 × 8 | all | 834 | 0.767 | 0.936 | 0.53 / 0.58 |
| alpha 1.2, flat, PQ 256 × 8, our searcher | all | 835 | 0.768 | 0.939 | 0.47 / 0.51 |
| alpha 1.2, flat, float32 | all | 873 | 0.964 | 0.964 | 0.59 / 0.80 |
| alpha 1.2, flat, float32, our searcher | all | 871 | 0.964 | 0.964 | 0.58 / 0.82 |
| alpha 1.2, hierarchy, BQ | all | 850 | 0.738 | 0.944 | 0.10 / 0.12 |
| alpha 1.2, hierarchy, PQ 256 × 8 | all | 776 | 0.769 | 0.936 | 0.52 / 0.58 |
| alpha 1.2, hierarchy, PQ 256 × 8, our searcher | all | 820 | 0.754 | 0.919 | 0.47 / 0.51 |
| alpha 1.2, hierarchy, float32 | all | 821 | 0.970 | 0.970 | 0.53 / 0.70 |
| alpha 1.2, hierarchy, float32, our searcher | all | 871 | 0.970 | 0.970 | 0.56 / 0.76 |
| alpha 1.0, flat, PQ 256 × 8, our searcher | in-walk | 13,585 | 0.850 | 1.000 | 4.48 / 4.96 |
| alpha 1.0, flat, float32, our searcher | in-walk | 13,682 | 1.000 | 1.000 | 14.00 / 15.79 |
| alpha 1.0, hierarchy, PQ 256 × 8, our searcher | in-walk | 13,597 | 0.850 | 1.000 | 4.50 / 4.81 |
| alpha 1.0, hierarchy, float32, our searcher | in-walk | 13,698 | 1.000 | 1.000 | 13.93 / 15.66 |
| alpha 1.2, flat, PQ 256 × 8, our searcher | in-walk | 13,652 | 0.850 | 1.000 | 4.59 / 4.90 |
| alpha 1.2, flat, float32, our searcher | in-walk | 13,745 | 1.000 | 1.000 | 14.20 / 17.35 |
| alpha 1.2, hierarchy, PQ 256 × 8, our searcher | in-walk | 13,759 | 0.850 | 1.000 | 4.61 / 5.05 |
| alpha 1.2, hierarchy, float32, our searcher | in-walk | 13,841 | 1.000 | 1.000 | 13.99 / 15.20 |

#### Corpus B — 22,895 vectors, medium, real clicks

| alpha | hierarchy | build s | heap MB (B/vec) | levels (nodes / mean degree) |
|---|---|---|---|---|
| 1.0 | on | 2.6 | 9.17 (420) | 22,895 / 12.0 · 747 / 9.6 · 23 / 3.8 |
| 1.0 | off | 2.4 | 8.85 (405) | 22,895 / 12.1 |
| 1.2 | on | 7.9 | 8.57 (393) | 22,895 / 32.0 · 747 / 32.0 · 23 / 21.2 |
| 1.2 | off | 7.5 | 8.27 (379) | 22,895 / 32.0 |

| graph, code, searcher | strategy | visited | recall@10 1× | 4× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|
| alpha 1.0, flat, BQ | all | 923 | 0.631 | 0.900 | 0.10 / 0.13 |
| alpha 1.0, flat, PQ 256 × 8 | all | 835 | 0.684 | 0.932 | 0.54 / 0.64 |
| alpha 1.0, flat, PQ 256 × 8, our searcher | all | 836 | 0.684 | 0.932 | 0.48 / 0.55 |
| alpha 1.0, flat, float32 | all | 903 | 0.983 | 0.983 | 0.60 / 0.82 |
| alpha 1.0, flat, float32, our searcher | all | 903 | 0.983 | 0.983 | 0.58 / 0.80 |
| alpha 1.0, hierarchy, BQ | all | 921 | 0.633 | 0.898 | 0.10 / 0.12 |
| alpha 1.0, hierarchy, PQ 256 × 8 | all | 833 | 0.676 | 0.919 | 0.51 / 0.59 |
| alpha 1.0, hierarchy, PQ 256 × 8, our searcher | all | 848 | 0.672 | 0.913 | 0.50 / 0.59 |
| alpha 1.0, hierarchy, float32 | all | 914 | 0.978 | 0.978 | 0.62 / 0.85 |
| alpha 1.0, hierarchy, float32, our searcher | all | 932 | 0.977 | 0.977 | 0.58 / 0.80 |
| alpha 1.2, flat, BQ | all | 1,238 | 0.633 | 0.900 | 0.14 / 0.17 |
| alpha 1.2, flat, PQ 256 × 8 | all | 1,058 | 0.680 | 0.928 | 0.57 / 0.68 |
| alpha 1.2, flat, PQ 256 × 8, our searcher | all | 1,059 | 0.680 | 0.928 | 0.52 / 0.61 |
| alpha 1.2, flat, float32 | all | 1,197 | 0.979 | 0.979 | 0.81 / 1.11 |
| alpha 1.2, flat, float32, our searcher | all | 1,198 | 0.979 | 0.979 | 0.76 / 1.03 |
| alpha 1.2, hierarchy, BQ | all | 1,088 | 0.631 | 0.897 | 0.12 / 0.15 |
| alpha 1.2, hierarchy, PQ 256 × 8 | all | 924 | 0.680 | 0.924 | 0.53 / 0.64 |
| alpha 1.2, hierarchy, PQ 256 × 8, our searcher | all | 979 | 0.675 | 0.918 | 0.49 / 0.58 |
| alpha 1.2, hierarchy, float32 | all | 1,072 | 0.979 | 0.979 | 0.72 / 1.03 |
| alpha 1.2, hierarchy, float32, our searcher | all | 1,133 | 0.978 | 0.978 | 0.69 / 0.97 |
| alpha 1.0, flat, PQ 256 × 8, our searcher | in-walk | 2,608 | 0.735 | 0.971 | 0.86 / 1.17 |
| alpha 1.0, flat, float32, our searcher | in-walk | 2,553 | 0.994 | 0.994 | 1.76 / 2.80 |
| alpha 1.0, hierarchy, PQ 256 × 8, our searcher | in-walk | 2,595 | 0.729 | 0.966 | 0.85 / 1.13 |
| alpha 1.0, hierarchy, float32, our searcher | in-walk | 2,573 | 0.993 | 0.993 | 1.79 / 2.77 |
| alpha 1.2, flat, PQ 256 × 8, our searcher | in-walk | 3,008 | 0.733 | 0.970 | 0.92 / 1.22 |
| alpha 1.2, flat, float32, our searcher | in-walk | 3,079 | 0.993 | 0.993 | 2.42 / 3.76 |
| alpha 1.2, hierarchy, PQ 256 × 8, our searcher | in-walk | 2,958 | 0.729 | 0.966 | 0.91 / 1.21 |
| alpha 1.2, hierarchy, float32, our searcher | in-walk | 3,047 | 0.994 | 0.994 | 2.14 / 3.23 |

#### Corpus C — 214,410 vectors, large, held-out queries

| alpha | hierarchy | build s | heap MB (B/vec) | levels (nodes / mean degree) |
|---|---|---|---|---|
| 1.0 | on | 55.8 | 83.57 (409) | 214,410 / 17.2 · 6,872 / 16.1 · 199 / 9.6 · 5 / 1.6 |
| 1.0 | off | 52.4 | 80.58 (394) | 214,410 / 17.2 |
| 1.2 | on | 108.8 | 77.93 (381) | 214,410 / 32.0 · 6,871 / 32.0 · 199 / 32.0 · 5 / 4.0 |
| 1.2 | off | 102.5 | 75.06 (367) | 214,410 / 32.0 |

| graph, code, searcher | strategy | visited | recall@10 1× | 4× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|
| alpha 1.0, flat, BQ | all | 1,197 | 0.789 | 0.982 | 0.21 / 0.27 |
| alpha 1.0, flat, PQ 256 × 8 | all | 1,189 | 0.814 | 0.992 | 0.64 / 0.78 |
| alpha 1.0, flat, PQ 256 × 8, our searcher | all | 1,190 | 0.814 | 0.992 | 0.66 / 0.78 |
| alpha 1.0, flat, float32 | all | 1,152 | 0.996 | 0.996 | 1.15 / 1.60 |
| alpha 1.0, flat, float32, our searcher | all | 1,153 | 0.996 | 0.996 | 1.09 / 1.50 |
| alpha 1.0, hierarchy, BQ | all | 1,178 | 0.790 | 0.983 | 0.20 / 0.26 |
| alpha 1.0, hierarchy, PQ 256 × 8 | all | 1,169 | 0.815 | 0.993 | 0.71 / 0.88 |
| alpha 1.0, hierarchy, PQ 256 × 8, our searcher | all | 1,189 | 0.815 | 0.993 | 0.64 / 0.77 |
| alpha 1.0, hierarchy, float32 | all | 1,130 | 0.996 | 0.996 | 1.11 / 1.54 |
| alpha 1.0, hierarchy, float32, our searcher | all | 1,149 | 0.996 | 0.996 | 1.08 / 1.50 |
| alpha 1.2, flat, BQ | all | 1,334 | 0.790 | 0.984 | 0.24 / 0.32 |
| alpha 1.2, flat, PQ 256 × 8 | all | 1,306 | 0.814 | 0.992 | 0.68 / 0.89 |
| alpha 1.2, flat, PQ 256 × 8, our searcher | all | 1,307 | 0.814 | 0.992 | 0.68 / 0.85 |
| alpha 1.2, flat, float32 | all | 1,264 | 0.995 | 0.995 | 1.21 / 1.85 |
| alpha 1.2, flat, float32, our searcher | all | 1,265 | 0.995 | 0.995 | 1.16 / 1.77 |
| alpha 1.2, hierarchy, BQ | all | 1,301 | 0.790 | 0.984 | 0.23 / 0.31 |
| alpha 1.2, hierarchy, PQ 256 × 8 | all | 1,275 | 0.813 | 0.992 | 0.68 / 0.90 |
| alpha 1.2, hierarchy, PQ 256 × 8, our searcher | all | 1,332 | 0.813 | 0.991 | 0.68 / 0.88 |
| alpha 1.2, hierarchy, float32 | all | 1,242 | 0.996 | 0.996 | 1.19 / 1.85 |
| alpha 1.2, hierarchy, float32, our searcher | all | 1,299 | 0.995 | 0.995 | 1.22 / 1.90 |

#### Corpus D — 416,559 vectors, large (1M stand-in), held-out queries

| alpha | hierarchy | build s | heap MB (B/vec) | levels (nodes / mean degree) |
|---|---|---|---|---|
| 1.0 | on | 115.0 | 161.31 (406) | 416,559 / 16.7 · 13,238 / 16.1 · 416 / 11.4 · 7 / 2.1 |
| 1.0 | off | 113.9 | 155.54 (392) | 416,559 / 16.8 |
| 1.2 | on | 219.6 | 151.03 (380) | 416,559 / 32.0 · 13,236 / 32.0 · 416 / 32.0 · 7 / 6.0 |
| 1.2 | off | 211.7 | 146.31 (368) | 416,559 / 32.0 |

| graph, code, searcher | strategy | visited | recall@10 1× | 4× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|
| alpha 1.0, flat, BQ | all | 1,263 | 0.778 | 0.976 | 0.22 / 0.28 |
| alpha 1.0, flat, PQ 256 × 8 | all | 1,256 | 0.788 | 0.981 | 0.69 / 0.86 |
| alpha 1.0, flat, PQ 256 × 8, our searcher | all | 1,257 | 0.788 | 0.981 | 0.71 / 0.86 |
| alpha 1.0, flat, float32 | all | 1,203 | 0.990 | 0.990 | 1.22 / 1.78 |
| alpha 1.0, flat, float32, our searcher | all | 1,204 | 0.990 | 0.990 | 1.24 / 1.79 |
| alpha 1.0, hierarchy, BQ | all | 1,242 | 0.779 | 0.977 | 0.24 / 0.32 |
| alpha 1.0, hierarchy, PQ 256 × 8 | all | 1,237 | 0.789 | 0.981 | 0.77 / 0.97 |
| alpha 1.0, hierarchy, PQ 256 × 8, our searcher | all | 1,256 | 0.789 | 0.981 | 0.69 / 0.85 |
| alpha 1.0, hierarchy, float32 | all | 1,183 | 0.992 | 0.992 | 1.23 / 1.81 |
| alpha 1.0, hierarchy, float32, our searcher | all | 1,204 | 0.992 | 0.992 | 1.18 / 1.72 |
| alpha 1.2, flat, BQ | all | 1,433 | 0.778 | 0.977 | 0.25 / 0.33 |
| alpha 1.2, flat, PQ 256 × 8 | all | 1,400 | 0.788 | 0.982 | 0.75 / 0.98 |
| alpha 1.2, flat, PQ 256 × 8, our searcher | all | 1,401 | 0.788 | 0.982 | 0.74 / 0.95 |
| alpha 1.2, flat, float32 | all | 1,345 | 0.994 | 0.994 | 1.38 / 2.15 |
| alpha 1.2, flat, float32, our searcher | all | 1,346 | 0.994 | 0.994 | 1.32 / 2.04 |
| alpha 1.2, hierarchy, BQ | all | 1,387 | 0.777 | 0.976 | 0.28 / 0.37 |
| alpha 1.2, hierarchy, PQ 256 × 8 | all | 1,365 | 0.786 | 0.979 | 0.76 / 1.03 |
| alpha 1.2, hierarchy, PQ 256 × 8, our searcher | all | 1,423 | 0.786 | 0.979 | 0.73 / 0.95 |
| alpha 1.2, hierarchy, float32 | all | 1,315 | 0.992 | 0.992 | 1.33 / 2.12 |
| alpha 1.2, hierarchy, float32, our searcher | all | 1,372 | 0.992 | 0.992 | 1.37 / 2.16 |

#### Corpus E — 312,787 vectors, large, short product texts, held-out queries

| alpha | hierarchy | build s | heap MB (B/vec) | levels (nodes / mean degree) |
|---|---|---|---|---|
| 1.0 | on | 41.9 | 121.54 (407) | 312,787 / 11.4 · 9,913 / 9.8 · 290 / 8.4 · 6 / 2.3 |
| 1.0 | off | 39.4 | 117.47 (394) | 312,787 / 11.5 |
| 1.2 | on | 115.8 | 113.48 (380) | 312,787 / 32.0 · 9,915 / 32.0 · 290 / 32.0 · 6 / 5.0 |
| 1.2 | off | 113.7 | 109.31 (366) | 312,787 / 32.0 |

| graph, code, searcher | strategy | visited | recall@10 1× | 4× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|
| alpha 1.0, flat, BQ | all | 710 | 0.872 | 0.980 | 0.14 / 0.21 |
| alpha 1.0, flat, PQ 256 × 8 | all | 710 | 0.750 | 0.944 | 0.56 / 0.68 |
| alpha 1.0, flat, PQ 256 × 8, our searcher | all | 712 | 0.751 | 0.945 | 0.52 / 0.63 |
| alpha 1.0, flat, float32 | all | 663 | 0.991 | 0.991 | 0.62 / 0.99 |
| alpha 1.0, flat, float32, our searcher | all | 665 | 0.991 | 0.991 | 0.63 / 0.99 |
| alpha 1.0, hierarchy, BQ | all | 631 | 0.863 | 0.972 | 0.13 / 0.19 |
| alpha 1.0, hierarchy, PQ 256 × 8 | all | 645 | 0.740 | 0.935 | 0.52 / 0.67 |
| alpha 1.0, hierarchy, PQ 256 × 8, our searcher | all | 661 | 0.740 | 0.935 | 0.50 / 0.63 |
| alpha 1.0, hierarchy, float32 | all | 596 | 0.984 | 0.984 | 0.58 / 1.01 |
| alpha 1.0, hierarchy, float32, our searcher | all | 611 | 0.985 | 0.985 | 0.53 / 0.91 |
| alpha 1.2, flat, BQ | all | 860 | 0.858 | 0.966 | 0.18 / 0.26 |
| alpha 1.2, flat, PQ 256 × 8 | all | 850 | 0.743 | 0.937 | 0.62 / 0.79 |
| alpha 1.2, flat, PQ 256 × 8, our searcher | all | 839 | 0.746 | 0.940 | 0.55 / 0.68 |
| alpha 1.2, flat, float32 | all | 782 | 0.987 | 0.987 | 0.71 / 1.13 |
| alpha 1.2, flat, float32, our searcher | all | 778 | 0.985 | 0.985 | 0.66 / 1.07 |
| alpha 1.2, hierarchy, BQ | all | 644 | 0.861 | 0.969 | 0.15 / 0.23 |
| alpha 1.2, hierarchy, PQ 256 × 8 | all | 645 | 0.732 | 0.925 | 0.55 / 0.72 |
| alpha 1.2, hierarchy, PQ 256 × 8, our searcher | all | 707 | 0.730 | 0.922 | 0.50 / 0.64 |
| alpha 1.2, hierarchy, float32 | all | 602 | 0.986 | 0.986 | 0.56 / 1.13 |
| alpha 1.2, hierarchy, float32, our searcher | all | 663 | 0.986 | 0.986 | 0.54 / 1.06 |
| alpha 1.0, flat, PQ 256 × 8, our searcher | in-walk | 1,435 | 0.759 | 0.966 | 0.70 / 0.98 |
| alpha 1.0, flat, float32, our searcher | in-walk | 1,368 | 0.992 | 0.992 | 1.15 / 2.17 |
| alpha 1.0, hierarchy, PQ 256 × 8, our searcher | in-walk | 1,404 | 0.755 | 0.960 | 0.70 / 1.01 |
| alpha 1.0, hierarchy, float32, our searcher | in-walk | 1,338 | 0.992 | 0.992 | 1.11 / 2.24 |
| alpha 1.2, flat, PQ 256 × 8, our searcher | in-walk | 1,506 | 0.758 | 0.967 | 0.73 / 1.14 |
| alpha 1.2, flat, float32, our searcher | in-walk | 1,459 | 0.996 | 0.996 | 1.17 / 2.37 |
| alpha 1.2, hierarchy, PQ 256 × 8, our searcher | in-walk | 1,389 | 0.752 | 0.957 | 0.67 / 1.03 |
| alpha 1.2, hierarchy, float32, our searcher | in-walk | 1,355 | 0.995 | 0.995 | 1.05 / 2.36 |

### 10. The graph in a file of our own

**What is measured.** A Lucene-built graph written into a simple file format of ours (fixed slots per node), mapped,
and walked from the mapped file instead of the heap: the file size, the time to write and map it, the heap left, and
whether the walk returns the same results.

**Why.** If evitaDB stores the graph in its own file, it controls persistence, versions and memory; the heap holds
almost nothing and a restart does not rebuild the graph.

**What we expect.** About the heap size of the graph as a file, a write in a fraction of a second, an instant map, an
empty heap, and exactly the same candidates as the heap graph.

#### Corpus A — 14,837 vectors, small, real clicks

| graph | build s | heap graph MB | file MB (B/node) | levels | write ms | map ms | reader heap MB | walks with different candidates |
|---|---|---|---|---|---|---|---|---|
| Lucene | 4.3 | 3.80 | 1.93 (137) | 4 | 14 | 0.093 | 0.00 | 0 of 107 |
| jVector | 5.7 | 5.66 | 1.93 (137) | 3 | 15 | 0.092 | 0.00 | 0 of 214 |

| code, graph, searcher | strategy | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|
| Lucene OSQ 4-bit, heap graph | all | 561 | 0.911 | 0.944 | 0.945 | 0.945 | 0.12 / 0.15 |
| Lucene OSQ 4-bit, mapped graph | all | 561 | 0.911 | 0.944 | 0.945 | 0.945 | 0.10 / 0.13 |
| Lucene float32, heap graph | all | 560 | 0.957 | 0.957 | 0.957 | 0.957 | 0.21 / 0.31 |
| Lucene float32, mapped graph | all | 560 | 0.957 | 0.957 | 0.957 | 0.957 | 0.24 / 0.37 |
| PQ 256 × 8, heap graph, our searcher | all | 835 | 0.765 | 0.911 | 0.952 | 0.961 | 0.49 / 0.54 |
| PQ 256 × 8, mapped graph, our searcher | all | 835 | 0.765 | 0.911 | 0.952 | 0.961 | 0.48 / 0.52 |
| Lucene OSQ 4-bit, heap graph | in-walk | 13,492 | 0.969 | 1.000 | 1.000 | 1.000 | 2.58 / 2.71 |
| Lucene OSQ 4-bit, mapped graph | in-walk | 13,492 | 0.969 | 1.000 | 1.000 | 1.000 | 2.34 / 2.74 |
| Lucene float32, heap graph | in-walk | 13,420 | 1.000 | 1.000 | 1.000 | 1.000 | 9.92 / 11.76 |
| Lucene float32, mapped graph | in-walk | 13,420 | 1.000 | 1.000 | 1.000 | 1.000 | 9.74 / 10.90 |
| PQ 256 × 8, heap graph, our searcher | in-walk | 13,742 | 0.860 | 0.986 | 1.000 | 1.000 | 4.65 / 4.85 |
| PQ 256 × 8, mapped graph, our searcher | in-walk | 13,742 | 0.860 | 0.986 | 1.000 | 1.000 | 4.49 / 4.70 |

#### Corpus B — 22,895 vectors, medium, real clicks

| graph | build s | heap graph MB | file MB (B/node) | levels | write ms | map ms | reader heap MB | walks with different candidates |
|---|---|---|---|---|---|---|---|---|
| Lucene | 7.7 | 6.40 | 2.99 (137) | 4 | 16 | 0.110 | 0.00 | 0 of 1000 |
| jVector | 8.0 | 8.57 | 2.98 (137) | 3 | 17 | 0.096 | 0.00 | 0 of 2000 |

| code, graph, searcher | strategy | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|
| Lucene OSQ 4-bit, heap graph | all | 1,014 | 0.911 | 0.971 | 0.972 | 0.972 | 0.16 / 0.22 |
| Lucene OSQ 4-bit, mapped graph | all | 1,014 | 0.911 | 0.971 | 0.972 | 0.972 | 0.15 / 0.20 |
| Lucene float32, heap graph | all | 1,012 | 0.974 | 0.974 | 0.974 | 0.974 | 0.42 / 0.60 |
| Lucene float32, mapped graph | all | 1,012 | 0.974 | 0.974 | 0.974 | 0.974 | 0.42 / 0.62 |
| PQ 256 × 8, heap graph, our searcher | all | 992 | 0.635 | 0.808 | 0.889 | 0.917 | 0.50 / 0.59 |
| PQ 256 × 8, mapped graph, our searcher | all | 992 | 0.635 | 0.808 | 0.889 | 0.917 | 0.50 / 0.59 |
| Lucene OSQ 4-bit, heap graph | in-walk | 2,623 | 0.918 | 0.989 | 0.990 | 0.990 | 0.34 / 0.48 |
| Lucene OSQ 4-bit, mapped graph | in-walk | 2,623 | 0.918 | 0.989 | 0.990 | 0.990 | 0.32 / 0.46 |
| Lucene float32, heap graph | in-walk | 2,614 | 0.990 | 0.990 | 0.990 | 0.990 | 1.36 / 2.12 |
| Lucene float32, mapped graph | in-walk | 2,614 | 0.990 | 0.990 | 0.990 | 0.990 | 1.25 / 1.86 |
| PQ 256 × 8, heap graph, our searcher | in-walk | 2,856 | 0.711 | 0.856 | 0.952 | 0.968 | 0.89 / 1.20 |
| PQ 256 × 8, mapped graph, our searcher | in-walk | 2,856 | 0.711 | 0.856 | 0.952 | 0.968 | 0.90 / 1.22 |

#### Corpus C — 214,410 vectors, large, held-out queries

| graph | build s | heap graph MB | file MB (B/node) | levels | write ms | map ms | reader heap MB | walks with different candidates |
|---|---|---|---|---|---|---|---|---|
| Lucene | 203.8 | 72.00 | 27.97 (137) | 5 | 82 | 0.161 | 0.00 | 0 of 1000 |
| jVector | 110.5 | 78.32 | 27.91 (136) | 4 | 86 | 0.132 | 0.00 | 0 of 2000 |

| code, graph, searcher | strategy | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|
| Lucene OSQ 4-bit, heap graph | all | 1,215 | 0.951 | 0.996 | 0.996 | 0.996 | 0.45 / 0.64 |
| Lucene OSQ 4-bit, mapped graph | all | 1,215 | 0.951 | 0.996 | 0.996 | 0.996 | 0.43 / 0.62 |
| Lucene float32, heap graph | all | 1,210 | 0.996 | 0.996 | 0.996 | 0.996 | 0.91 / 1.33 |
| Lucene float32, mapped graph | all | 1,210 | 0.996 | 0.996 | 0.996 | 0.996 | 0.89 / 1.30 |
| PQ 256 × 8, heap graph, our searcher | all | 1,335 | 0.813 | 0.964 | 0.989 | 0.993 | 0.67 / 0.85 |
| PQ 256 × 8, mapped graph, our searcher | all | 1,335 | 0.813 | 0.964 | 0.989 | 0.993 | 0.66 / 0.84 |

#### Corpus D — 416,559 vectors, large (1M stand-in), held-out queries

| graph | build s | heap graph MB | file MB (B/node) | levels | write ms | map ms | reader heap MB | walks with different candidates |
|---|---|---|---|---|---|---|---|---|
| Lucene | 427.7 | 138.91 | 54.35 (137) | 5 | 133 | 0.156 | 0.00 | 0 of 1000 |
| jVector | 235.1 | 151.05 | 54.21 (136) | 4 | 152 | 0.170 | 0.00 | 0 of 2000 |

| code, graph, searcher | strategy | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|
| Lucene OSQ 4-bit, heap graph | all | 1,272 | 0.946 | 0.990 | 0.990 | 0.990 | 0.46 / 0.66 |
| Lucene OSQ 4-bit, mapped graph | all | 1,272 | 0.946 | 0.990 | 0.990 | 0.990 | 0.44 / 0.62 |
| Lucene float32, heap graph | all | 1,267 | 0.990 | 0.990 | 0.990 | 0.990 | 1.01 / 1.52 |
| Lucene float32, mapped graph | all | 1,267 | 0.990 | 0.990 | 0.990 | 0.990 | 0.98 / 1.49 |
| PQ 256 × 8, heap graph, our searcher | all | 1,422 | 0.791 | 0.949 | 0.983 | 0.989 | 0.74 / 0.94 |
| PQ 256 × 8, mapped graph, our searcher | all | 1,422 | 0.791 | 0.949 | 0.983 | 0.989 | 0.73 / 0.94 |

#### Corpus E — 312,787 vectors, large, short product texts, held-out queries

| graph | build s | heap graph MB | file MB (B/node) | levels | write ms | map ms | reader heap MB | walks with different candidates |
|---|---|---|---|---|---|---|---|---|
| Lucene | 115.7 | 77.88 | 40.81 (137) | 5 | 113 | 0.152 | 0.00 | 0 of 1000 |
| jVector | 112.1 | 113.18 | 40.70 (136) | 4 | 127 | 0.142 | 0.00 | 0 of 2000 |

| code, graph, searcher | strategy | visited | recall@10 1× | 2× | 4× | 8× | p50 / p95 ms at 4× |
|---|---|---|---|---|---|---|---|
| Lucene OSQ 4-bit, heap graph | all | 509 | 0.942 | 0.975 | 0.976 | 0.976 | 0.19 / 0.30 |
| Lucene OSQ 4-bit, mapped graph | all | 509 | 0.942 | 0.975 | 0.976 | 0.976 | 0.18 / 0.28 |
| Lucene float32, heap graph | all | 509 | 0.979 | 0.979 | 0.979 | 0.979 | 0.33 / 0.57 |
| Lucene float32, mapped graph | all | 509 | 0.979 | 0.979 | 0.979 | 0.979 | 0.32 / 0.56 |
| PQ 256 × 8, heap graph, our searcher | all | 711 | 0.727 | 0.873 | 0.922 | 0.942 | 0.49 / 0.64 |
| PQ 256 × 8, mapped graph, our searcher | all | 711 | 0.727 | 0.873 | 0.922 | 0.942 | 0.49 / 0.63 |
| Lucene OSQ 4-bit, heap graph | in-walk | 1,052 | 0.948 | 0.985 | 0.985 | 0.985 | 0.34 / 0.61 |
| Lucene OSQ 4-bit, mapped graph | in-walk | 1,052 | 0.948 | 0.985 | 0.985 | 0.985 | 0.30 / 0.56 |
| Lucene float32, heap graph | in-walk | 1,053 | 0.984 | 0.984 | 0.984 | 0.984 | 0.70 / 1.35 |
| Lucene float32, mapped graph | in-walk | 1,053 | 0.984 | 0.984 | 0.984 | 0.984 | 0.66 / 1.28 |
| PQ 256 × 8, heap graph, our searcher | in-walk | 1,392 | 0.749 | 0.907 | 0.960 | 0.973 | 0.71 / 1.15 |
| PQ 256 × 8, mapped graph, our searcher | in-walk | 1,392 | 0.749 | 0.907 | 0.960 | 0.973 | 0.67 / 1.02 |

### 11. Churn: inserts, deletes and replacements under concurrent searches

**What is measured.** An index that changes while it is being searched: half of the corpus is inserted, then the
rest in 10 % steps by one writer while four readers search; then 10–30 % is deleted and replaced. After each step:
recall, the searchers' latency during the writes, errors, and whether a deleted record was ever returned. At the
end, the same live set is built afresh for comparison.

**Why.** evitaDB applies changes continuously (stock, prices, new products); a full rebuild for every change is
impossible on large corpora. The decision needs to know whether the graph stays good under incremental changes, and
whether searching while writing is safe.

**What we expect.** jVector supports concurrent inserts and deletes (`cleanup()`), recall stays at the level of a
fresh build, and no errors or dead records appear. Lucene's graph does not support concurrent writes and reads
(its searches during inserts run outside its contract and are marked *unsupported*); deletes are liveness bits and
cost more visited nodes until a rebuild.

#### Corpus A — 14,837 vectors, small, real clicks

##### jVector

| step | live nodes | ms per insert | readers during writes: searches, p50 / p95 ms | errors | recall@10 GraphSearcher / ours | dead returned |
|---|---|---|---|---|---|---|
| initial 50 % (all cores, 2.9 s) | 7,418 | — | 18,955, 0.63 / 0.81 (no writer) | 0 | 0.981 / 0.979 | 0 |
| inserts to 60 % | 8,901 | 1.374 | 11,850, 0.69 / 0.88 | 0 | 0.977 / 0.977 | 0 during, 0 after |
| inserts to 70 % | 10,384 | 1.790 | 15,327, 0.69 / 0.89 | 0 | 0.962 / 0.962 | 0 during, 0 after |
| inserts to 80 % | 11,867 | 1.930 | 16,670, 0.69 / 0.89 | 0 | 0.978 / 0.979 | 0 during, 0 after |
| inserts to 90 % | 13,350 | 1.970 | 16,251, 0.72 / 0.94 | 0 | 0.984 / 0.985 | 0 during, 0 after |
| 10 % deleted, before `cleanup()` | 11,867 | — | — | — | 0.986 / 0.987 | 0 |
| `cleanup()` (2.2 s; held View: 0 of 107 walks failed) | 11,867 | — | 8,707, 0.85 / 1.83 | 0 | 0.987 / 0.987 | 0 during, 0 after |
| replacement of 1,483 records + `cleanup()` (3.9 s) | 11,867 | — | 18,477, 0.76 / 1.39 | 0 | 0.983 / 0.983 | 24 during, 0 after |
| same live set built afresh (4.0 s) | 11,867 | — | — | — | 0.969 / 0.969 | 0 |

##### Lucene

| step | live nodes | ms per insert | readers during writes (unsupported): p50 / p95 ms | errors | recall@10 | visited | dead returned |
|---|---|---|---|---|---|---|---|
| initial 50 % (one thread, 2.1 s) | 7,418 | — | — | — | 0.982 | 662 | 0 |
| inserts to 60 % | 8,901 | 0.445 | 0.29 / 0.61 | 0 | 0.982 | 639 | 0 |
| inserts to 70 % | 10,384 | 0.391 | 0.28 / 0.36 | 0 | 0.988 | 618 | 0 |
| inserts to 80 % | 11,867 | 0.397 | 0.27 / 0.35 | 0 | 0.992 | 585 | 0 |
| inserts to 90 % | 13,350 | 0.399 | 0.26 / 0.34 | 0 | 0.988 | 564 | 0 |
| 10 % deleted (liveness bits) | 11,867 | — | — | — | 0.992 | 625 | 0 |
| 20 % deleted (liveness bits) | 10,384 | — | — | — | 0.991 | 690 | 0 |
| 30 % deleted (liveness bits) | 8,901 | — | — | — | 0.992 | 770 | 0 |
| live set rebuilt (2.5 s) | 8,901 | — | — | — | 0.971 | 649 | 0 |

#### Corpus B — 22,895 vectors, medium, real clicks

##### jVector

| step | live nodes | ms per insert | readers during writes: searches, p50 / p95 ms | errors | recall@10 GraphSearcher / ours | dead returned |
|---|---|---|---|---|---|---|
| initial 50 % (all cores, 4.5 s) | 11,447 | — | 17,043, 0.69 / 0.97 (no writer) | 0 | 0.986 / 0.985 | 0 |
| inserts to 60 % | 13,736 | 1.357 | 15,684, 0.78 / 1.08 | 0 | 0.986 / 0.986 | 0 during, 0 after |
| inserts to 70 % | 16,025 | 1.804 | 19,993, 0.81 / 1.14 | 0 | 0.987 / 0.988 | 0 during, 0 after |
| inserts to 80 % | 18,314 | 1.779 | 19,383, 0.83 / 1.16 | 0 | 0.985 / 0.985 | 0 during, 0 after |
| inserts to 90 % | 20,603 | 1.825 | 19,256, 0.86 / 1.20 | 0 | 0.985 / 0.985 | 0 during, 0 after |
| 10 % deleted, before `cleanup()` | 18,314 | — | — | — | 0.987 / 0.987 | 0 |
| `cleanup()` (3.2 s; held View: 0 of 500 walks failed) | 18,314 | — | 10,593, 1.02 / 2.19 | 0 | 0.990 / 0.989 | 0 during, 0 after |
| replacement of 2,289 records + `cleanup()` (5.8 s) | 18,314 | — | 21,090, 0.95 / 1.91 | 0 | 0.989 / 0.988 | 32 during, 0 after |
| same live set built afresh (6.2 s) | 18,314 | — | — | — | 0.981 / 0.979 | 0 |

##### Lucene

| step | live nodes | ms per insert | readers during writes (unsupported): p50 / p95 ms | errors | recall@10 | visited | dead returned |
|---|---|---|---|---|---|---|---|
| initial 50 % (one thread, 5.0 s) | 11,447 | — | — | — | 0.989 | 958 | 0 |
| inserts to 60 % | 13,736 | 0.597 | 0.44 / 0.62 | 0 | 0.983 | 946 | 0 |
| inserts to 70 % | 16,025 | 0.578 | 0.45 / 0.62 | 0 | 0.980 | 951 | 0 |
| inserts to 80 % | 18,314 | 0.558 | 0.46 / 0.62 | 0 | 0.980 | 959 | 0 |
| inserts to 90 % | 20,603 | 0.613 | 0.49 / 0.86 | 1 (ClassCastException) | 0.980 | 956 | 0 |
| 10 % deleted (liveness bits) | 18,314 | — | — | — | 0.984 | 1034 | 0 |
| 20 % deleted (liveness bits) | 16,025 | — | — | — | 0.984 | 1149 | 0 |
| 30 % deleted (liveness bits) | 13,736 | — | — | — | 0.986 | 1266 | 0 |
| live set rebuilt (4.5 s) | 13,736 | — | — | — | 0.988 | 1008 | 0 |

#### Corpus C — 214,410 vectors, large, held-out queries

##### jVector

| step | live nodes | ms per insert | readers during writes: searches, p50 / p95 ms | errors | recall@10 GraphSearcher / ours | dead returned |
|---|---|---|---|---|---|---|
| initial 50 % (all cores, 57.3 s) | 107,205 | — | 8,919, 1.32 / 1.99 (no writer) | 0 | 0.997 / 0.997 | 0 |
| inserts to 60 % | 128,646 | 2.116 | 128,144, 1.39 / 2.13 | 0 | 0.998 / 0.998 | 0 during, 0 after |
| inserts to 70 % | 150,087 | 2.601 | 157,307, 1.40 / 2.13 | 0 | 0.998 / 0.998 | 0 during, 0 after |
| inserts to 80 % | 171,528 | 2.602 | 158,164, 1.38 / 2.13 | 0 | 0.996 / 0.996 | 0 during, 0 after |
| inserts to 90 % | 192,969 | 2.594 | 156,812, 1.38 / 2.17 | 0 | 0.997 / 0.997 | 0 during, 0 after |
| 10 % deleted, before `cleanup()` | 171,528 | — | — | — | 0.998 / 0.998 | 0 |
| `cleanup()` (37.4 s; held View: 0 of 500 walks failed) | 171,528 | — | 70,725, 1.70 / 5.62 | 0 | 0.993 / 0.994 | 0 during, 0 after |
| replacement of 21,441 records + `cleanup()` (78.8 s) | 171,528 | — | 190,819, 1.48 / 2.83 | 0 | 0.996 / 0.996 | 23 during, 0 after |
| same live set built afresh (89.7 s) | 171,528 | — | — | — | 0.996 / 0.994 | 0 |

##### Lucene

| step | live nodes | ms per insert | readers during writes (unsupported): p50 / p95 ms | errors | recall@10 | visited | dead returned |
|---|---|---|---|---|---|---|---|
| initial 50 % (one thread, 119.3 s) | 107,205 | — | — | — | 0.994 | 1244 | 0 |
| inserts to 60 % | 128,646 | 1.232 | 0.99 / 1.39 | 0 | 0.995 | 1235 | 0 |
| inserts to 70 % | 150,087 | 1.235 | 0.99 / 1.39 | 0 | 0.994 | 1226 | 0 |
| inserts to 80 % | 171,528 | 1.240 | 0.99 / 1.41 | 1 (ArrayIndexOutOfBoundsException) | 0.995 | 1219 | 0 |
| inserts to 90 % | 192,969 | 1.213 | 0.99 / 1.41 | 0 | 0.994 | 1211 | 0 |
| 10 % deleted (liveness bits) | 171,528 | — | — | — | 0.995 | 1327 | 0 |
| 20 % deleted (liveness bits) | 150,087 | — | — | — | 0.995 | 1465 | 0 |
| 30 % deleted (liveness bits) | 128,646 | — | — | — | 0.996 | 1654 | 0 |
| live set rebuilt (126.9 s) | 128,646 | — | — | — | 0.995 | 1254 | 0 |

#### Corpus D — 416,559 vectors, large (1M stand-in), held-out queries

##### jVector

| step | live nodes | ms per insert | readers during writes: searches, p50 / p95 ms | errors | recall@10 GraphSearcher / ours | dead returned |
|---|---|---|---|---|---|---|
| initial 50 % (all cores, 114.7 s) | 208,279 | — | 7,797, 1.55 / 2.27 (no writer) | 0 | 0.991 / 0.991 | 0 |
| inserts to 60 % | 249,934 | 2.266 | 233,652, 1.62 / 2.42 | 0 | 0.990 / 0.990 | 0 during, 0 after |
| inserts to 70 % | 291,589 | 2.725 | 279,117, 1.62 / 2.45 | 0 | 0.990 / 0.990 | 0 during, 0 after |
| inserts to 80 % | 333,244 | 2.770 | 284,838, 1.61 / 2.44 | 0 | 0.992 / 0.991 | 0 during, 0 after |
| inserts to 90 % | 374,899 | 2.805 | 288,644, 1.62 / 2.44 | 0 | 0.991 / 0.991 | 0 during, 0 after |
| 10 % deleted, before `cleanup()` | 333,244 | — | — | — | 0.993 / 0.992 | 0 |
| `cleanup()` (73.3 s; held View: 0 of 500 walks failed) | 333,244 | — | 121,406, 2.03 / 5.78 | 0 | 0.989 / 0.989 | 0 during, 0 after |
| replacement of 41,655 records + `cleanup()` (164.7 s) | 333,244 | — | 339,031, 1.78 / 3.30 | 0 | 0.990 / 0.990 | 22 during, 0 after |
| same live set built afresh (178.5 s) | 333,244 | — | — | — | 0.990 / 0.990 | 0 |

##### Lucene

| step | live nodes | ms per insert | readers during writes (unsupported): p50 / p95 ms | errors | recall@10 | visited | dead returned |
|---|---|---|---|---|---|---|---|
| initial 50 % (one thread, 244.9 s) | 208,279 | — | — | — | 0.990 | 1370 | 0 |
| inserts to 60 % | 249,934 | 1.361 | 1.15 / 1.61 | 0 | 0.985 | 1365 | 0 |
| inserts to 70 % | 291,589 | 1.362 | 1.14 / 1.62 | 0 | 0.986 | 1359 | 0 |
| inserts to 80 % | 333,244 | 1.356 | 1.14 / 1.64 | 1 (ArrayIndexOutOfBoundsException) | 0.987 | 1356 | 0 |
| inserts to 90 % | 374,899 | 1.412 | 1.14 / 1.64 | 0 | 0.986 | 1350 | 0 |
| 10 % deleted (liveness bits) | 333,244 | — | — | — | 0.987 | 1478 | 0 |
| 20 % deleted (liveness bits) | 291,589 | — | — | — | 0.988 | 1636 | 0 |
| 30 % deleted (liveness bits) | 249,934 | — | — | — | 0.989 | 1839 | 0 |
| live set rebuilt (263.2 s) | 249,934 | — | — | — | 0.989 | 1380 | 0 |

#### Corpus E — 312,787 vectors, large, short product texts, held-out queries

##### jVector

| step | live nodes | ms per insert | readers during writes: searches, p50 / p95 ms | errors | recall@10 GraphSearcher / ours | dead returned |
|---|---|---|---|---|---|---|
| initial 50 % (all cores, 66.6 s) | 156,393 | — | 17,326, 0.63 / 1.20 (no writer) | 0 | 0.989 / 0.989 | 0 |
| inserts to 60 % | 187,671 | 1.598 | 270,638, 0.67 / 1.31 | 0 | 0.991 / 0.989 | 0 during, 0 after |
| inserts to 70 % | 218,949 | 2.120 | 373,396, 0.64 / 1.27 | 0 | 0.992 / 0.990 | 0 during, 0 after |
| inserts to 80 % | 250,227 | 2.121 | 380,319, 0.63 / 1.25 | 0 | 0.991 / 0.989 | 0 during, 0 after |
| inserts to 90 % | 281,505 | 2.088 | 383,541, 0.61 / 1.23 | 0 | 0.992 / 0.990 | 0 during, 0 after |
| 10 % deleted, before `cleanup()` | 250,227 | — | — | — | 0.993 / 0.991 | 0 |
| `cleanup()` (49.2 s; held View: 0 of 500 walks failed) | 250,227 | — | 202,972, 0.74 / 1.95 | 0 | 0.992 / 0.992 | 0 during, 0 after |
| replacement of 31,278 records + `cleanup()` (91.3 s) | 250,227 | — | 461,991, 0.65 / 1.47 | 0 | 0.990 / 0.990 | 19 during, 0 after |
| same live set built afresh (92.2 s) | 250,227 | — | — | — | 0.979 / 0.976 | 0 |

##### Lucene

| step | live nodes | ms per insert | readers during writes (unsupported): p50 / p95 ms | errors | recall@10 | visited | dead returned |
|---|---|---|---|---|---|---|---|
| initial 50 % (one thread, 97.3 s) | 156,393 | — | — | — | 0.985 | 619 | 0 |
| inserts to 60 % | 187,671 | 0.642 | 0.46 / 0.75 | 0 | 0.988 | 591 | 0 |
| inserts to 70 % | 218,949 | 0.623 | 0.44 / 0.72 | 0 | 0.988 | 570 | 0 |
| inserts to 80 % | 250,227 | 0.600 | 0.41 / 0.70 | 0 | 0.984 | 550 | 0 |
| inserts to 90 % | 281,505 | 0.615 | 0.40 / 0.67 | 0 | 0.981 | 528 | 0 |
| 10 % deleted (liveness bits) | 250,227 | — | — | — | 0.984 | 571 | 0 |
| 20 % deleted (liveness bits) | 218,949 | — | — | — | 0.985 | 626 | 0 |
| 30 % deleted (liveness bits) | 187,671 | — | — | — | 0.986 | 701 | 0 |
| live set rebuilt (79.3 s) | 187,671 | — | — | — | 0.985 | 575 | 0 |

#### The library combination under churn (follow-up run, 2026-11-09)

The jVector protocol above, run again with the readers and the recall checkpoints switched to the library combination:
our searcher over the jVector graph (a new `View` per search), scored by Lucene's OSQ codes from our code file, the
candidates reranked exactly at 4×. The readers walk with the 4-bit code; each checkpoint measures both codes. The graph
is still changed by jVector's builder. The codes of inserted nodes come from the code file written over the whole
corpus; appending a code when a node is inserted is not part of the protocol.
| corpus | ms per insert, 60→90 % (jVector float32 in measurement 11) | readers p95 ms: no writer / inserts / `cleanup()` / replacement | errors | dead returned during replacement | recall@10 4× OSQ 1-bit / 4-bit: at 90 % / after replacement / built afresh | `cleanup()` / replacement s |
|---|---|---|---|---|---|---|
| A | 1.55–2.15 (1.37–1.97) | 0.34 / 0.39 / 1.08 / 0.95 | 0 | 14 | 0.976 / 0.983 / 0.971 / 0.979 / 0.942 / 0.968 | 2.1 / 4.5 |
| B | 1.56–2.04 (1.36–1.82) | 0.31 / 0.33 / 1.27 / 0.85 | 0 | 7 | 0.976 / 0.980 / 0.980 / 0.984 / 0.976 / 0.980 | 3.3 / 6.2 |
| C | 2.43–3.23 (2.12–2.60) | 0.67 / 0.80 / 3.03 / 1.72 | 0 | 20 | 0.994 / 0.995 / 0.995 / 0.996 / 0.994 / 0.997 | 40.0 / 92.9 |
| D | 2.67–3.68 (2.27–2.81) | 0.78 / 1.01 / 5.23 / 2.56 | 0 | 16 | 0.987 / 0.988 / 0.986 / 0.989 / 0.986 / 0.991 | 83.7 / 195.6 |
| E | 1.64–2.56 (1.60–2.12) | 0.45 / 0.49 / 2.23 / 1.41 | 0 | 13 | 0.984 / 0.993 / 0.986 / 0.989 / 0.972 / 0.985 | 47.1 / 94.2 |

**Result.** The combination behaves under churn like jVector alone: no errors on any corpus, recall at 4× at the level
of a fresh build or above, and 7–20 just-deleted nodes returned during a replacement, which the acceptor has to filter
as it does for jVector. Inserts are 10–30 % slower than in the jVector protocol above, probably because the writer
shares the cores with four readers that now also rerank. `cleanup()` and the replacement take 40–196 s on the large
corpora, during which the readers' p95 rises to 1.4–5.2 ms, the highest during `cleanup()`.

### 12. Graph copy and swap

**What is measured.** Copy-and-swap maintenance of a graph that readers must never see changing: a second instance is
created from the live graph, a batch of changes is applied to the private copy, and one reference write makes the copy
live, while the readers of the old instance finish on it. For both libraries: the copy alone (what a copy per session or
per transaction would cost), the copy plus a batch of 1–10,000 inserts, and the copy with as many deletes as inserts.
For each: wall time, bytes allocated by all threads, collections and their time, the heap of both graphs, and the
recall@10 of the swapped-in graph (float32 walk, no rerank, 100 queries).

**Why.** Lucene's graph cannot be read while it is written (measurement 11), and evitaDB's transactional layer publishes
immutable states; copy-and-swap is how a library graph could sit behind that layer without a rebuild per change.

**What we expect.** A copy costing O(nodes × degree) without vector scoring, a batch costing what the same inserts cost
in place, and two graphs on the heap during the swap.

**Setup.** `GraphSwapScenario`. The live graph holds 90 % of the corpus (the churn protocol's shuffled order); the
remaining 10 % feed the inserts, so batches are capped at that reserve. Lucene copies with
`InitializedHnswGraphBuilder#fromGraph`: the neighbour lists without their scores, deleted nodes dropped through an
ordinal map, the nodes that lost neighbours repaired, the levels rebalanced. It accepts only a dense graph, so the live
graph numbers its nodes from 0, and a copy with deletes compacts the ordinals again: every such swap renumbers the
vectors behind the graph and the primary-key ↔ ordinal map. jVector copies with `GraphIndexBuilder#rescore`, which
copies every edge and scores it again on all cores; deletes are `markNodeDeleted` plus `cleanup()` on the copy, which no
reader sees yet, and keep the ordinals. Inserts run on one thread (evitaDB's single writer). One copy per library runs
first to warm up and is not reported.
#### Corpus A — 14,837 vectors, small, real clicks

Live graph: Lucene 13,354 nodes, built from scratch in 5.9 s on one thread, 2.91 MB; jVector 13,354 nodes, built from
scratch in 5.4 s on all cores, 5.07 MB.

| library | batch | copy ms (alloc) | inserts ms, per insert (alloc) | GC count / ms | copy + inserts ms | graphs on heap during the swap MB | recall@10 |
|---|---|---|---|---|---|---|---|
| Lucene | none (copy only) | 19 (6 MB) | 0, 0.00 (0 MB) | 0 / 0 | 19 | 2.91 + 2.90 | 0.987 |
| Lucene | 1 inserts | 18 (6 MB) | 0, 0.54 (0 MB) | 0 / 0 | 18 | 2.91 + 2.90 | 0.987 |
| Lucene | 1 deletes + 1 inserts | 24 (7 MB) | 0, 0.43 (0 MB) | 0 / 0 | 24 | 2.91 + 2.91 | 0.987 |
| Lucene | 100 inserts | 13 (6 MB) | 40, 0.41 (1 MB) | 0 / 0 | 53 | 2.91 + 2.92 | 0.987 |
| Lucene | 100 deletes + 100 inserts | 238 (7 MB) | 40, 0.41 (1 MB) | 0 / 0 | 278 | 2.91 + 2.90 | 0.987 |
| Lucene | 1000 inserts | 14 (6 MB) | 370, 0.37 (8 MB) | 0 / 0 | 384 | 2.91 + 3.11 | 0.986 |
| Lucene | 1000 deletes + 1000 inserts | 1,526 (10 MB) | 342, 0.34 (8 MB) | 1 / 2 | 1,868 | 2.91 + 2.87 | 0.944 |
| jVector | none (copy only) | 48 (111 MB) | 0, 0.00 (0 MB) | 0 / 0 | 48 | 5.07 + 5.08 | 0.981 |
| jVector | 1 inserts | 33 (111 MB) | 1, 1.17 (1 MB) | 0 / 0 | 34 | 5.07 + 5.08 | 0.981 |
| jVector | 1 deletes + 1 inserts | 419 (1.2 GB) | 1, 1.30 (1 MB) | 1 / 2 | 420 | 5.07 + 5.08 | 0.981 |
| jVector | 100 inserts | 33 (111 MB) | 87, 0.88 (109 MB) | 1 / 2 | 120 | 5.07 + 5.12 | 0.981 |
| jVector | 100 deletes + 100 inserts | 649 (3.1 GB) | 81, 0.82 (109 MB) | 4 / 7 | 730 | 5.07 + 5.08 | 0.987 |
| jVector | 1000 inserts | 31 (111 MB) | 968, 0.97 (1.3 GB) | 1 / 2 | 999 | 5.07 + 5.48 | 0.976 |
| jVector | 1000 deletes + 1000 inserts | 1,781 (9.4 GB) | 1,015, 1.01 (1.3 GB) | 12 / 39 | 2,796 | 5.07 + 5.08 | 0.980 |

#### Corpus B — 22,895 vectors, medium, real clicks

Live graph: Lucene 20,606 nodes, built from scratch in 11.4 s on one thread, 5.24 MB; jVector 20,606 nodes, built from
scratch in 8.4 s on all cores, 7.87 MB.

| library | batch | copy ms (alloc) | inserts ms, per insert (alloc) | GC count / ms | copy + inserts ms | graphs on heap during the swap MB | recall@10 |
|---|---|---|---|---|---|---|---|
| Lucene | none (copy only) | 28 (14 MB) | 0, 0.00 (0 MB) | 0 / 0 | 28 | 5.24 + 5.24 | 0.986 |
| Lucene | 1 inserts | 24 (14 MB) | 0, 0.77 (0 MB) | 0 / 0 | 24 | 5.24 + 5.24 | 0.986 |
| Lucene | 1 deletes + 1 inserts | 25 (14 MB) | 0, 0.67 (0 MB) | 1 / 2 | 25 | 5.24 + 5.24 | 0.987 |
| Lucene | 100 inserts | 18 (14 MB) | 57, 0.58 (1 MB) | 0 / 0 | 75 | 5.24 + 5.26 | 0.983 |
| Lucene | 100 deletes + 100 inserts | 391 (14 MB) | 58, 0.58 (1 MB) | 0 / 0 | 449 | 5.24 + 5.24 | 0.985 |
| Lucene | 1000 inserts | 18 (14 MB) | 603, 0.60 (8 MB) | 0 / 0 | 621 | 5.24 + 5.49 | 0.981 |
| Lucene | 1000 deletes + 1000 inserts | 3,048 (14 MB) | 566, 0.57 (8 MB) | 0 / 0 | 3,614 | 5.24 + 5.20 | 0.985 |
| jVector | none (copy only) | 47 (171 MB) | 0, 0.00 (0 MB) | 0 / 0 | 47 | 7.87 + 7.83 | 0.987 |
| jVector | 1 inserts | 105 (172 MB) | 1, 1.14 (1 MB) | 1 / 2 | 106 | 7.87 + 7.83 | 0.987 |
| jVector | 1 deletes + 1 inserts | 668 (1.8 GB) | 1, 1.29 (1 MB) | 2 / 3 | 669 | 7.87 + 7.83 | 0.987 |
| jVector | 100 inserts | 53 (171 MB) | 96, 0.97 (108 MB) | 0 / 0 | 149 | 7.87 + 7.87 | 0.986 |
| jVector | 100 deletes + 100 inserts | 785 (3.4 GB) | 87, 0.88 (108 MB) | 4 / 8 | 872 | 7.87 + 7.83 | 0.987 |
| jVector | 1000 inserts | 47 (171 MB) | 917, 0.92 (1.1 GB) | 2 / 3 | 964 | 7.87 + 8.21 | 0.987 |
| jVector | 1000 deletes + 1000 inserts | 2,186 (12.6 GB) | 860, 0.86 (1.1 GB) | 15 / 55 | 3,046 | 7.87 + 7.83 | 0.989 |

#### Corpus C — 214,410 vectors, large, held-out queries

Live graph: Lucene 192,969 nodes, built from scratch in 230.6 s on one thread, 57.78 MB; jVector 192,969 nodes, built
from scratch in 105.8 s on all cores, 73.77 MB.

| library | batch | copy ms (alloc) | inserts ms, per insert (alloc) | GC count / ms | copy + inserts ms | graphs on heap during the swap MB | recall@10 |
|---|---|---|---|---|---|---|---|
| Lucene | none (copy only) | 217 (178 MB) | 0, 0.00 (0 MB) | 1 / 8 | 217 | 57.78 + 57.51 | 0.994 |
| Lucene | 1 inserts | 249 (178 MB) | 1, 1.49 (0 MB) | 5 / 19 | 250 | 57.78 + 57.51 | 0.994 |
| Lucene | 1 deletes + 1 inserts | 260 (180 MB) | 1, 1.52 (0 MB) | 3 / 6 | 261 | 57.78 + 57.51 | 0.994 |
| Lucene | 100 inserts | 241 (178 MB) | 123, 1.24 (1 MB) | 1 / 18 | 364 | 57.78 + 57.54 | 0.994 |
| Lucene | 100 deletes + 100 inserts | 787 (180 MB) | 141, 1.42 (1 MB) | 1 / 9 | 928 | 57.78 + 57.51 | 0.994 |
| Lucene | 1000 inserts | 217 (178 MB) | 1,279, 1.28 (9 MB) | 1 / 9 | 1,496 | 57.78 + 57.83 | 0.994 |
| Lucene | 1000 deletes + 1000 inserts | 5,364 (180 MB) | 1,198, 1.20 (9 MB) | 3 / 11 | 6,562 | 57.78 + 57.50 | 0.994 |
| Lucene | 10000 inserts | 211 (178 MB) | 13,548, 1.35 (90 MB) | 2 / 13 | 13,759 | 57.78 + 60.55 | 0.993 |
| Lucene | 10000 deletes + 10000 inserts | 43,003 (178 MB) | 11,826, 1.18 (84 MB) | 4 / 42 | 54,829 | 57.78 + 57.20 | 0.993 |
| jVector | none (copy only) | 708 (1.6 GB) | 0, 0.00 (0 MB) | 2 / 8 | 708 | 73.77 + 73.33 | 0.988 |
| jVector | 1 inserts | 761 (1.6 GB) | 1, 1.97 (1 MB) | 1 / 5 | 762 | 73.77 + 73.33 | 0.988 |
| jVector | 1 deletes + 1 inserts | 7,606 (16.9 GB) | 2, 2.26 (1 MB) | 18 / 69 | 7,608 | 73.77 + 73.33 | 0.988 |
| jVector | 100 inserts | 770 (1.6 GB) | 161, 1.61 (105 MB) | 1 / 8 | 931 | 73.77 + 73.37 | 0.988 |
| jVector | 100 deletes + 100 inserts | 8,074 (18.7 GB) | 155, 1.55 (105 MB) | 23 / 66 | 8,229 | 73.77 + 73.33 | 0.988 |
| jVector | 1000 inserts | 704 (1.6 GB) | 1,470, 1.47 (1012 MB) | 3 / 15 | 2,174 | 73.77 + 73.98 | 0.988 |
| jVector | 1000 deletes + 1000 inserts | 10,277 (34.9 GB) | 1,482, 1.48 (1012 MB) | 85 / 126 | 11,759 | 73.77 + 73.33 | 0.988 |
| jVector | 10000 inserts | 790 (1.6 GB) | 15,499, 1.55 (10.7 GB) | 15 / 54 | 16,289 | 73.77 + 77.48 | 0.993 |
| jVector | 10000 deletes + 10000 inserts | 24,444 (129.1 GB) | 15,700, 1.57 (10.8 GB) | 81 / 271 | 40,144 | 73.77 + 73.70 | 0.993 |

#### Corpus D — 416,559 vectors, large (1M stand-in), held-out queries

Live graph: Lucene 374,904 nodes, built from scratch in 512.5 s on one thread, 111.16 MB; jVector 374,904 nodes, built
from scratch in 248.8 s on all cores, 142.89 MB.

| library | batch | copy ms (alloc) | inserts ms, per insert (alloc) | GC count / ms | copy + inserts ms | graphs on heap during the swap MB | recall@10 |
|---|---|---|---|---|---|---|---|
| Lucene | none (copy only) | 499 (341 MB) | 0, 0.00 (0 MB) | 6 / 31 | 499 | 111.16 + 110.66 | 0.996 |
| Lucene | 1 inserts | 486 (341 MB) | 2, 2.71 (0 MB) | 0 / 0 | 488 | 111.16 + 110.66 | 0.996 |
| Lucene | 1 deletes + 1 inserts | 622 (344 MB) | 1, 1.52 (0 MB) | 1 / 24 | 623 | 111.16 + 110.66 | 0.996 |
| Lucene | 100 inserts | 475 (341 MB) | 157, 1.57 (1 MB) | 1 / 29 | 632 | 111.16 + 110.69 | 0.996 |
| Lucene | 100 deletes + 100 inserts | 1,034 (344 MB) | 131, 1.32 (1 MB) | 3 / 25 | 1,165 | 111.16 + 110.66 | 0.996 |
| Lucene | 1000 inserts | 980 (341 MB) | 2,016, 2.02 (9 MB) | 1 / 59 | 2,996 | 111.16 + 110.97 | 0.996 |
| Lucene | 1000 deletes + 1000 inserts | 6,482 (344 MB) | 1,421, 1.42 (9 MB) | 1 / 20 | 7,903 | 111.16 + 110.65 | 0.996 |
| Lucene | 10000 inserts | 515 (341 MB) | 15,142, 1.51 (91 MB) | 5 / 63 | 15,657 | 111.16 + 113.69 | 0.996 |
| Lucene | 10000 deletes + 10000 inserts | 52,949 (342 MB) | 13,589, 1.36 (86 MB) | 1 / 82 | 66,538 | 111.16 + 110.40 | 0.996 |
| jVector | none (copy only) | 2,091 (3.0 GB) | 0, 0.00 (0 MB) | 2 / 21 | 2,091 | 142.89 + 142.40 | 0.994 |
| jVector | 1 inserts | 1,416 (3.0 GB) | 3, 3.01 (1 MB) | 2 / 12 | 1,419 | 142.89 + 142.40 | 0.994 |
| jVector | 1 deletes + 1 inserts | 16,211 (32.6 GB) | 7, 7.55 (1 MB) | 23 / 120 | 16,218 | 142.89 + 142.40 | 0.994 |
| jVector | 100 inserts | 1,674 (3.0 GB) | 208, 2.08 (105 MB) | 3 / 71 | 1,882 | 142.89 + 142.79 | 0.994 |
| jVector | 100 deletes + 100 inserts | 16,185 (34.7 GB) | 199, 2.00 (105 MB) | 24 / 121 | 16,384 | 142.89 + 142.40 | 0.994 |
| jVector | 1000 inserts | 1,606 (3.0 GB) | 1,768, 1.77 (1018 MB) | 3 / 43 | 3,374 | 142.89 + 142.78 | 0.994 |
| jVector | 1000 deletes + 1000 inserts | 18,704 (51.4 GB) | 2,211, 2.21 (1018 MB) | 38 / 252 | 20,915 | 142.89 + 143.40 | 0.994 |
| jVector | 10000 inserts | 1,867 (3.0 GB) | 18,352, 1.83 (10.0 GB) | 9 / 95 | 20,219 | 142.89 + 146.15 | 0.994 |
| jVector | 10000 deletes + 10000 inserts | 33,092 (n/a¹) | 20,347, 2.04 (10.0 GB) | 112 / 551 | 53,439 | 142.89 + 142.61 | 0.996 |

#### Corpus E — 312,787 vectors, large, short product texts, held-out queries

Live graph: Lucene 281,509 nodes, built from scratch in 180.6 s on one thread, 61.08 MB; jVector 281,509 nodes, built
from scratch in 125.9 s on all cores, 106.67 MB.

| library | batch | copy ms (alloc) | inserts ms, per insert (alloc) | GC count / ms | copy + inserts ms | graphs on heap during the swap MB | recall@10 |
|---|---|---|---|---|---|---|---|
| Lucene | none (copy only) | 300 (135 MB) | 0, 0.00 (0 MB) | 1 / 10 | 300 | 61.08 + 61.02 | 0.973 |
| Lucene | 1 inserts | 341 (135 MB) | 0, 0.67 (0 MB) | 4 / 15 | 341 | 61.08 + 61.02 | 0.973 |
| Lucene | 1 deletes + 1 inserts | 433 (137 MB) | 0, 0.50 (0 MB) | 5 / 24 | 433 | 61.08 + 61.02 | 0.973 |
| Lucene | 100 inserts | 365 (135 MB) | 55, 0.55 (1 MB) | 2 / 27 | 420 | 61.08 + 61.04 | 0.973 |
| Lucene | 100 deletes + 100 inserts | 676 (137 MB) | 57, 0.57 (1 MB) | 3 / 17 | 733 | 61.08 + 61.02 | 0.973 |
| Lucene | 1000 inserts | 318 (135 MB) | 553, 0.55 (8 MB) | 1 / 17 | 871 | 61.08 + 61.21 | 0.973 |
| Lucene | 1000 deletes + 1000 inserts | 3,191 (140 MB) | 628, 0.63 (8 MB) | 1 / 36 | 3,819 | 61.08 + 60.98 | 0.974 |
| Lucene | 10000 inserts | 288 (135 MB) | 5,705, 0.57 (76 MB) | 3 / 23 | 5,993 | 61.08 + 62.94 | 0.973 |
| Lucene | 10000 deletes + 10000 inserts | 23,488 (166 MB) | 5,013, 0.50 (76 MB) | 1 / 15 | 28,501 | 61.08 + 60.59 | 0.974 |
| jVector | none (copy only) | 719 (2.3 GB) | 0, 0.00 (0 MB) | 2 / 9 | 719 | 106.67 + 106.92 | 0.985 |
| jVector | 1 inserts | 734 (2.3 GB) | 0, 0.99 (1 MB) | 2 / 11 | 734 | 106.67 + 106.88 | 0.985 |
| jVector | 1 deletes + 1 inserts | 9,486 (23.7 GB) | 1, 1.32 (1 MB) | 24 / 88 | 9,487 | 106.67 + 106.92 | 0.985 |
| jVector | 100 inserts | 722 (2.3 GB) | 125, 1.25 (109 MB) | 2 / 8 | 847 | 106.67 + 106.96 | 0.985 |
| jVector | 100 deletes + 100 inserts | 9,876 (25.6 GB) | 95, 0.96 (108 MB) | 29 / 101 | 9,971 | 106.67 + 107.51 | 0.985 |
| jVector | 1000 inserts | 711 (2.3 GB) | 1,026, 1.03 (1.0 GB) | 4 / 23 | 1,737 | 106.67 + 107.89 | 0.985 |
| jVector | 1000 deletes + 1000 inserts | 11,691 (42.0 GB) | 899, 0.90 (1.0 GB) | 105 / 146 | 12,590 | 106.67 + 106.92 | 0.985 |
| jVector | 10000 inserts | 777 (2.3 GB) | 9,647, 0.96 (10.5 GB) | 41 / 75 | 10,424 | 106.67 + 110.67 | 0.985 |
| jVector | 10000 deletes + 10000 inserts | 27,856 (143.7 GB) | 9,241, 0.92 (10.5 GB) | 249 / 435 | 37,097 | 106.67 + 107.52 | 0.987 |

¹ The allocation sum went negative: most likely a thread of the shared pool ended during the phase and took its
allocation counter with it. The sum is not valid.

**Result.**

- **The copy alone is cheap in time:** Lucene 13–28 ms on A and B, 0.2–1.0 s on C–E; jVector 31–105 ms on A and B,
  0.7–2.1 s on C–E. During the swap two graphs are on the heap (D: 111 + 111 MB Lucene, 143 + 142 MB jVector). Lucene's
  copy allocates 2–3× the graph; jVector's about 22× (D: 3.1 GB), because `rescore` scores every edge again.
- **Inserts cost what they cost in place:** Lucene 0.3–0.8 ms per insert on A, B and E, 1.2–2.7 ms on C and D; jVector
  0.8–2.3 ms (batches of a single insert on D: 3.0 and 7.6 ms). jVector allocates about 1 MB per insert (search
  scratch), Lucene about 8 kB.
- **Deletes are the expensive part.** Lucene's repair grows with the batch: 1,000 deletes cost 1.5–6.5 s, 10,000 cost
  23–53 s on C–E; on A, 1,000 deletes (7.5 % of the graph) left recall at 0.944 against 0.986 without them, elsewhere
  recall is unchanged. jVector's `cleanup()` walks the whole graph whatever the batch: one delete costs 0.4–0.7 s on A
  and B and 7.6–16 s on C–E, allocating 1.2–33 GB; 10,000 deletes cost 24–33 s and allocate up to 144 GB, with up
  to about 0.5 s of collections per phase. Most of the single-delete cost is jVector's default
  `refineFinalGraph = true`, not the delete; the follow-up below separates the two.
- **Consequence.** A copy per transaction or per session is out of reach on the large corpora: the copy alone takes
  0.2–2 s and doubles the graph's heap. Copy-and-swap works for batches; deletes belong in a liveness bitmap the walk
  filters through, with the repair run rarely. jVector's `cleanup()` costs the same in place (measurement 11).

#### Delete repair without the refinement (follow-up run, 2026-11-09)

**What is measured.** Where jVector's delete repair spends its time and its allocation. On a `rescore` copy of the
live graph (90 % of the corpus), 1, 1,000 or 10,000 nodes are marked deleted and repaired three ways:

- `cleanup()` of a builder with `refineFinalGraph = true`, the library default, as in the table above;
- `removeDeletedNodes()` alone on the same builder: the repair of the deleted nodes' neighbours, without the
  refinement pass and without `enforceDegree`;
- `cleanup()` of a builder with `refineFinalGraph = false`, whose live graph is built separately with that flag.

Recall@10 after the repair as in the table above (float32 walk, no rerank, 100 queries). Scenario
`jv-delete-repair` (`TransactionOverlayScenario#deleteRepair`). On A and B, 10,000 deletes are 75 % and 49 % of the
graph.

**What we expect.** The refinement searches again from every upper-level node on every `cleanup()`, so it should be a
fixed cost independent of the batch; the repair itself should grow with the deletes.

#### Corpus A — 14,837 vectors, live graph 13,354 nodes

Live graph recall@10: refinement on 0.981, off 0.980.

| refinement | repair | deletes | repair ms (alloc) | GC count / ms | recall@10 after |
|---|---|---|---|---|---|
| on | `cleanup()` | 1 | 295 (1.1 GB) | 1 / 2 | 0.981 |
| on | `cleanup()` | 1,000 | 1,904 (9.4 GB) | 8 / 79 | 0.983 |
| on | `cleanup()` | 10,000 | 1,514 (2.8 GB) | 2 / 10 | 0.989 |
| on | `removeDeletedNodes()` | 1 | 105 (52 MB) | 0 / 0 | 0.981 |
| on | `removeDeletedNodes()` | 1,000 | 6,591 (8.4 GB) | 7 / 85 | 0.983 |
| on | `removeDeletedNodes()` | 10,000 | 1,191 (2.5 GB) | 2 / 7 | 0.994 |
| off | `cleanup()` | 1 | 239 (56 MB) | 0 / 0 | 0.980 |
| off | `cleanup()` | 1,000 | 1,527 (8.3 GB) | 6 / 37 | 0.988 |
| off | `cleanup()` | 10,000 | 1,795 (2.5 GB) | 2 / 25 | 0.989 |

#### Corpus B — 22,895 vectors, live graph 20,606 nodes

Live graph recall@10: refinement on 0.982, off 0.989.

| refinement | repair | deletes | repair ms (alloc) | GC count / ms | recall@10 after |
|---|---|---|---|---|---|
| on | `cleanup()` | 1 | 543 (1.6 GB) | 2 / 11 | 0.982 |
| on | `cleanup()` | 1,000 | 2,628 (12.4 GB) | 14 / 64 | 0.981 |
| on | `cleanup()` | 10,000 | 4,343 (11.8 GB) | 10 / 93 | 0.994 |
| on | `removeDeletedNodes()` | 1 | 393 (47 MB) | 0 / 0 | 0.982 |
| on | `removeDeletedNodes()` | 1,000 | 2,026 (10.9 GB) | 10 / 67 | 0.981 |
| on | `removeDeletedNodes()` | 10,000 | 3,739 (10.9 GB) | 8 / 81 | 0.994 |
| off | `cleanup()` | 1 | 355 (50 MB) | 0 / 0 | 0.989 |
| off | `cleanup()` | 1,000 | 2,111 (10.9 GB) | 8 / 75 | 0.989 |
| off | `cleanup()` | 10,000 | 4,506 (11.0 GB) | 8 / 170 | 0.991 |

#### Corpus C — 214,410 vectors, live graph 192,969 nodes

Live graph recall@10: refinement on 0.986, off 0.989.

| refinement | repair | deletes | repair ms (alloc) | GC count / ms | recall@10 after |
|---|---|---|---|---|---|
| on | `cleanup()` | 1 | 8,006 (15.3 GB) | 15 / 82 | 0.986 |
| on | `cleanup()` | 1,000 | 13,335 (32.9 GB) | 32 / 224 | 0.986 |
| on | `cleanup()` | 10,000 | 44,197 (127.0 GB) | 107 / 795 | 0.988 |
| on | `removeDeletedNodes()` | 1 | 3,000 (393 MB) | 1 / 16 | 0.986 |
| on | `removeDeletedNodes()` | 1,000 | 6,997 (18.0 GB) | 16 / 61 | 0.986 |
| on | `removeDeletedNodes()` | 10,000 | 23,342 (112.2 GB) | 79 / 530 | 0.988 |
| off | `cleanup()` | 1 | 3,302 (466 MB) | 1 / 31 | 0.989 |
| off | `cleanup()` | 1,000 | 10,241 (18.3 GB) | 13 / 212 | 0.990 |
| off | `cleanup()` | 10,000 | 52,666 (112.5 GB) | 66 / 461 | 0.987 |

#### Corpus D — 416,559 vectors, live graph 374,904 nodes

Live graph recall@10: refinement on 0.997, off 0.995.

| refinement | repair | deletes | repair ms (alloc) | GC count / ms | recall@10 after |
|---|---|---|---|---|---|
| on | `cleanup()` | 1 | 13,769 (29.6 GB) | 31 / 89 | 0.997 |
| on | `cleanup()` | 1,000 | 16,768 (48.2 GB) | 52 / 169 | 0.997 |
| on | `cleanup()` | 10,000 | 35,345 (172.1 GB) | 188 / 740 | 0.997 |
| on | `removeDeletedNodes()` | 1 | 7,889 (764 MB) | 0 / 0 | 0.997 |
| on | `removeDeletedNodes()` | 1,000 | 11,162 (19.3 GB) | 20 / 145 | 0.997 |
| on | `removeDeletedNodes()` | 10,000 | 30,377 (143.2 GB) | 194 / 369 | 0.998 |
| off | `cleanup()` | 1 | 7,632 (874 MB) | 1 / 11 | 0.995 |
| off | `cleanup()` | 1,000 | 10,052 (19.4 GB) | 31 / 95 | 0.995 |
| off | `cleanup()` | 10,000 | 29,151 (144.0 GB) | 176 / 392 | 0.995 |

#### Corpus E — 312,787 vectors, live graph 281,509 nodes

Live graph recall@10: refinement on 0.987, off 0.982.

| refinement | repair | deletes | repair ms (alloc) | GC count / ms | recall@10 after |
|---|---|---|---|---|---|
| on | `cleanup()` | 1 | 8,125 (21.4 GB) | 15 / 146 | 0.987 |
| on | `cleanup()` | 1,000 | 11,587 (39.5 GB) | 26 / 205 | 0.986 |
| on | `cleanup()` | 10,000 | 23,908 (141.4 GB) | 89 / 600 | 0.989 |
| on | `removeDeletedNodes()` | 1 | 2,485 (490 MB) | 0 / 0 | 0.987 |
| on | `removeDeletedNodes()` | 1,000 | 6,846 (18.6 GB) | 12 / 126 | 0.986 |
| on | `removeDeletedNodes()` | 10,000 | 22,320 (121.1 GB) | 81 / 466 | 0.989 |
| off | `cleanup()` | 1 | 5,242 (666 MB) | 1 / 10 | 0.982 |
| off | `cleanup()` | 1,000 | 7,697 (18.4 GB) | 15 / 145 | 0.984 |
| off | `cleanup()` | 10,000 | 23,731 (120.6 GB) | 83 / 368 | 0.993 |

**Result.**

- **The fixed cost of `cleanup()` is the refinement.** One delete on C–E costs 8.0–13.8 s with the default and
  2.5–7.9 s with `removeDeletedNodes()` alone, allocating 0.4–0.9 GB instead of 16–30 GB. `cleanup()` with the
  refinement off costs the same as `removeDeletedNodes()`.
- **The refinement buys no recall.** Graphs built without it reach the same recall as with it, within jVector's
  ±0.02 run-to-run variation (C 0.989 against 0.986, D 0.995 against 0.997, E 0.982 against 0.987), and recall
  after every repair is unchanged.
- **What remains grows with the deletes.** `removeDeletedNodes()` walks every node's neighbours once (the fixed
  2.5–7.9 s on C–E), then re-prunes the neighbours of each deleted node: 2–4 ms per deleted node on six cores,
  22–30 s for 10,000.
- **The allocation is short-lived heap garbage.** About 11 MB per deleted node, up to 147 GB for 10,000, cleared
  by 0.4–0.8 s of collections; the live heap stays at the graph. It comes from jVector's score provider: with a
  value-shared vector source (our mapped one) every score function copies its 8 KB anchor vector, and the diversity
  pruning creates one per candidate in each pass. The same copy explains the copy's own allocation above (one
  anchor per node, 8 KB × nodes).
- **Consequence.** jVector deletes are repaired with `removeDeletedNodes()`, or the graph is built with
  `refineFinalGraph = false`. The repair still costs a pass over the graph plus milliseconds per delete, so deletes
  wait in the liveness bitmap and are repaired in batches. `removeDeletedNodes()` alone leaves the graph marked
  as having pending mutations (only `cleanup()` clears it), so `getView()` keeps returning the concurrent view and
  jVector's own on-disk writer refuses the graph; neither matters when the graph is written into our own file.

### 13. A transaction's changes beside one shared graph

**What is measured.** Searches of a transaction that has written vectors of its own and deleted records, over one
shared graph it does not change. Its own vectors are not inserted into the graph: every search walks the shared
graph with the transaction's deletes rejected through the accept bits, scans the transaction's own vectors
exactly, and merges both lists by exact score.

**Why.** Neither library graph can take a diff layer per transaction the way evitaDB's transactional structures do,
and a private copy of the graph per transaction costs 0.2–2 s and a second graph on the heap (measurement 12). If
a transaction's changes can live beside the shared graph, the graph changes only on commit.

**What we expect.** No loss of recall from the merge; a scan cost linear in the transaction's own vectors (one
2,048-dimensional dot product each, about 0.12 µs); no measurable cost of the delete bitmap.

**Setup.** Scenario `tx-overlay` (`TransactionOverlayScenario#overlay`). A jVector graph over 90 % of the corpus
(the churn protocol's shuffled order), our searcher with OSQ 4-bit, exact rerank of 40 candidates. The transaction's
own vectors (0–10,000, capped at the remaining 10 %) are float32 arrays on the heap, scored with Lucene's
`VectorUtil.dotProduct`; its deletes are 1 % of the graph in a `RoaringBitmap`. Recall@10 against the exact scan over
what the transaction sees: the graph's records minus its deletes plus its own vectors. 500 queries (A: 107), one
warm-up pass, timed in the harness on one thread with `System.nanoTime`, not under JMH: the walk's absolute times
are 2–3× the JMH figures of measurement 4 and its p95 is noisy on the large corpora; the scan's times are stable.
A first run of corpus C was stopped by the host running out of memory, and its walk times were invalid; the table
is the rerun.

#### Corpus A — 14,837 vectors, shared graph 13,354 nodes

| own deletes | own vectors (heap) | walk + rerank p50 / p95 ms | own scan p50 / p95 ms | total p50 / p95 ms | recall@10 |
|---|---|---|---|---|---|
| 0 | 0 (0.0 MB) | 0.404 / 0.695 | 0.001 / 0.001 | 0.409 / 0.701 | 0.987 |
| 0 | 1 (0.0 MB) | 0.381 / 0.448 | 0.001 / 0.001 | 0.386 / 0.452 | 0.987 |
| 0 | 10 (0.1 MB) | 0.408 / 0.558 | 0.003 / 0.004 | 0.414 / 0.567 | 0.987 |
| 0 | 100 (0.8 MB) | 0.363 / 0.449 | 0.015 / 0.019 | 0.389 / 0.473 | 0.987 |
| 0 | 1,000 (7.8 MB) | 0.436 / 0.777 | 0.138 / 0.176 | 0.591 / 0.923 | 0.987 |
| 133 | 0 (0.0 MB) | 0.389 / 0.567 | 0.001 / 0.001 | 0.393 / 0.572 | 0.987 |
| 133 | 1 (0.0 MB) | 0.402 / 0.544 | 0.001 / 0.002 | 0.407 / 0.550 | 0.987 |
| 133 | 10 (0.1 MB) | 0.380 / 0.468 | 0.003 / 0.003 | 0.386 / 0.476 | 0.987 |
| 133 | 100 (0.8 MB) | 0.379 / 0.481 | 0.014 / 0.018 | 0.399 / 0.501 | 0.987 |
| 133 | 1,000 (7.8 MB) | 0.363 / 0.435 | 0.123 / 0.137 | 0.494 / 0.565 | 0.987 |

#### Corpus B — 22,895 vectors, shared graph 20,606 nodes

| own deletes | own vectors (heap) | walk + rerank p50 / p95 ms | own scan p50 / p95 ms | total p50 / p95 ms | recall@10 |
|---|---|---|---|---|---|
| 0 | 0 (0.0 MB) | 0.471 / 0.643 | 0.001 / 0.001 | 0.475 / 0.652 | 0.983 |
| 0 | 1 (0.0 MB) | 0.458 / 0.651 | 0.001 / 0.002 | 0.464 / 0.657 | 0.983 |
| 0 | 10 (0.1 MB) | 0.444 / 0.558 | 0.002 / 0.003 | 0.449 / 0.565 | 0.983 |
| 0 | 100 (0.8 MB) | 0.447 / 0.687 | 0.014 / 0.017 | 0.465 / 0.709 | 0.983 |
| 0 | 1,000 (7.8 MB) | 0.408 / 0.501 | 0.122 / 0.135 | 0.537 / 0.637 | 0.984 |
| 206 | 0 (0.0 MB) | 0.459 / 0.948 | 0.001 / 0.001 | 0.463 / 0.952 | 0.983 |
| 206 | 1 (0.0 MB) | 0.414 / 0.507 | 0.001 / 0.001 | 0.419 / 0.513 | 0.983 |
| 206 | 10 (0.1 MB) | 0.442 / 1.037 | 0.002 / 0.003 | 0.448 / 1.043 | 0.983 |
| 206 | 100 (0.8 MB) | 0.409 / 0.507 | 0.014 / 0.016 | 0.426 / 0.526 | 0.983 |
| 206 | 1,000 (7.8 MB) | 0.427 / 1.140 | 0.122 / 0.140 | 0.555 / 1.274 | 0.984 |

#### Corpus C — 214,410 vectors, shared graph 192,969 nodes

| own deletes | own vectors (heap) | walk + rerank p50 / p95 ms | own scan p50 / p95 ms | total p50 / p95 ms | recall@10 |
|---|---|---|---|---|---|
| 0 | 0 (0.0 MB) | 1.152 / 5.705 | 0.001 / 0.002 | 1.156 / 5.709 | 0.993 |
| 0 | 1 (0.0 MB) | 1.166 / 2.190 | 0.001 / 0.002 | 1.171 / 2.197 | 0.993 |
| 0 | 10 (0.1 MB) | 1.954 / 11.624 | 0.003 / 0.006 | 1.960 / 11.631 | 0.993 |
| 0 | 100 (0.8 MB) | 1.254 / 3.697 | 0.015 / 0.019 | 1.274 / 3.718 | 0.993 |
| 0 | 1,000 (7.8 MB) | 1.339 / 3.359 | 0.127 / 0.154 | 1.475 / 3.496 | 0.993 |
| 0 | 10,000 (78.1 MB) | 1.150 / 2.103 | 1.239 / 1.445 | 2.429 / 3.439 | 0.994 |
| 1,929 | 0 (0.0 MB) | 1.179 / 3.820 | 0.001 / 0.002 | 1.183 / 3.825 | 0.993 |
| 1,929 | 1 (0.0 MB) | 1.236 / 3.439 | 0.001 / 0.003 | 1.243 / 3.446 | 0.993 |
| 1,929 | 10 (0.1 MB) | 1.366 / 3.606 | 0.003 / 0.005 | 1.374 / 3.617 | 0.993 |
| 1,929 | 100 (0.8 MB) | 0.957 / 1.657 | 0.015 / 0.017 | 0.975 / 1.679 | 0.993 |
| 1,929 | 1,000 (7.8 MB) | 1.276 / 2.674 | 0.127 / 0.153 | 1.425 / 2.828 | 0.993 |
| 1,929 | 10,000 (78.1 MB) | 1.981 / 7.648 | 1.304 / 2.036 | 3.305 / 9.831 | 0.994 |

#### Corpus D — 416,559 vectors, shared graph 374,904 nodes

| own deletes | own vectors (heap) | walk + rerank p50 / p95 ms | own scan p50 / p95 ms | total p50 / p95 ms | recall@10 |
|---|---|---|---|---|---|
| 0 | 0 (0.0 MB) | 1.211 / 2.819 | 0.001 / 0.002 | 1.215 / 2.825 | 0.991 |
| 0 | 1 (0.0 MB) | 1.437 / 2.759 | 0.001 / 0.002 | 1.442 / 2.765 | 0.991 |
| 0 | 10 (0.1 MB) | 1.904 / 4.825 | 0.003 / 0.010 | 1.910 / 4.833 | 0.991 |
| 0 | 100 (0.8 MB) | 1.178 / 1.878 | 0.014 / 0.017 | 1.198 / 1.896 | 0.991 |
| 0 | 1,000 (7.8 MB) | 1.244 / 2.090 | 0.121 / 0.145 | 1.369 / 2.249 | 0.991 |
| 0 | 10,000 (78.1 MB) | 1.043 / 1.929 | 1.183 / 1.377 | 2.247 / 3.306 | 0.992 |
| 3,749 | 0 (0.0 MB) | 1.405 / 2.902 | 0.001 / 0.001 | 1.409 / 2.906 | 0.991 |
| 3,749 | 1 (0.0 MB) | 1.846 / 5.908 | 0.001 / 0.003 | 1.850 / 5.915 | 0.991 |
| 3,749 | 10 (0.1 MB) | 1.429 / 3.769 | 0.003 / 0.004 | 1.436 / 3.779 | 0.991 |
| 3,749 | 100 (0.8 MB) | 1.484 / 4.125 | 0.014 / 0.018 | 1.504 / 4.147 | 0.991 |
| 3,749 | 1,000 (7.8 MB) | 1.413 / 2.442 | 0.120 / 0.139 | 1.546 / 2.611 | 0.991 |
| 3,749 | 10,000 (78.1 MB) | 1.215 / 2.061 | 1.177 / 1.305 | 2.422 / 3.276 | 0.992 |

#### Corpus E — 312,787 vectors, shared graph 281,509 nodes

| own deletes | own vectors (heap) | walk + rerank p50 / p95 ms | own scan p50 / p95 ms | total p50 / p95 ms | recall@10 |
|---|---|---|---|---|---|
| 0 | 0 (0.0 MB) | 0.590 / 2.184 | 0.001 / 0.002 | 0.595 / 2.189 | 0.986 |
| 0 | 1 (0.0 MB) | 0.596 / 1.501 | 0.001 / 0.002 | 0.601 / 1.505 | 0.986 |
| 0 | 10 (0.1 MB) | 0.544 / 1.263 | 0.003 / 0.004 | 0.551 / 1.271 | 0.986 |
| 0 | 100 (0.8 MB) | 0.686 / 1.853 | 0.016 / 0.022 | 0.705 / 1.872 | 0.986 |
| 0 | 1,000 (7.8 MB) | 0.872 / 3.605 | 0.128 / 0.164 | 1.013 / 3.854 | 0.986 |
| 0 | 10,000 (78.1 MB) | 0.720 / 3.286 | 1.241 / 1.468 | 1.979 / 5.114 | 0.986 |
| 2,815 | 0 (0.0 MB) | 0.638 / 1.887 | 0.001 / 0.002 | 0.642 / 1.892 | 0.986 |
| 2,815 | 1 (0.0 MB) | 0.631 / 1.366 | 0.001 / 0.002 | 0.635 / 1.371 | 0.986 |
| 2,815 | 10 (0.1 MB) | 0.552 / 1.336 | 0.003 / 0.004 | 0.558 / 1.342 | 0.986 |
| 2,815 | 100 (0.8 MB) | 0.575 / 1.234 | 0.014 / 0.018 | 0.594 / 1.255 | 0.986 |
| 2,815 | 1,000 (7.8 MB) | 0.401 / 0.761 | 0.119 / 0.134 | 0.530 / 0.888 | 0.986 |
| 2,815 | 10,000 (78.1 MB) | 0.471 / 0.927 | 1.173 / 1.283 | 1.664 / 2.113 | 0.986 |

**Result.**

- **The merge loses no recall.** Every corpus keeps the recall it has without own vectors and deletes (A 0.987,
  B 0.983–0.984, C 0.993–0.994, D and E as in their rows).
- **The delete bitmap costs nothing measurable.** 1 % of the graph deleted leaves recall and walk time unchanged.
- **The scan is linear and cheap up to thousands.** 1,000 own vectors cost 0.12–0.14 ms p50, 10,000 cost 1.2–1.3
  ms p50 on every corpus. Up to about 1,000 the scan adds at most a quarter to a search; at 10,000 it costs about
  as much as the walk on the large corpora.
- **Memory:** 8 KB of heap per own vector (78 MB for 10,000), for the life of the transaction.
- **Consequence.** One shared graph per committed version is enough for transactions: the graph is an accelerator,
  correctness comes from the bitmaps, and a transaction's own inserts reach the graph on commit (0.8–2.3 ms each,
  measurement 12). The practical bound is thousands of pending inserts per transaction.

## Deviations and reruns

- Corpus C, `lucene-float32` and `lucene-osq1`: the first runs failed on Lucene's file lock
  (`NativeFSLock`, "Underlying file changed by an external force"): the shared file system of the work directory
  changes the lock file's metadata. The writer now uses `NoLockFactory` (one writer per index directory, so nothing
  is lost); both reran and passed.
- **The filter path changed during the run**, after corpus D's unfiltered scenarios and before corpus E. Before,
  every strategy read the filter as a `boolean[]` over vector ordinals, and the exact scan visited all N ordinals
  and skipped non-members. Now the filter is what evitaDB would hand over (spec §2.4, §2.5): a `RoaringBitmap` of
  the members' primary keys, whole records, behind an ordinal map (`PrimaryKeyOrdinals`). A walk tests a node with
  `bitmap.contains(ordToPk[ord])`; the exact scan iterates the bitmap and reaches each member's vectors through
  `pkStart`, so its cost is linear in the members, not in the corpus. The `boolean[]` stays as one of the
  materializations compared at 50 % (measurement 6). Recall is unaffected (the same members, the same exact
  results); only the latencies of filtered rows change. Corpora E, A and B were measured with the new path
  throughout; on corpora C and D the filter matrix was rerun with it. Their `churn` ran with the old path: its
  liveness filter was a `boolean[]` test, whose cost differs from the bitmap's by less than the noise (the
  membership-structure table of measurement 6).
- **jVector rows vary between runs** by up to about ±0.02 recall (parallel build, PQ retrained per run). Two
  runs of the same code on corpus A differed in the jVector rows only, unfiltered ones included; the exact and
  Lucene rows are identical between runs.
- **JMH on C and D** ran the unfiltered configurations only (seven); A, B and E ran all 28.
- **JOL inside the JMH forks** could not attach and printed "JOL walk failed"; it only annotates memory, the
  latencies are unaffected.
- **Lucene readers during inserts** (`churn`, marked unsupported) failed once on B (`ClassCastException`, at
  90 %), on C and on D (`ArrayIndexOutOfBoundsException`, at 80 %). Both are races between the writer and a
  reader. The graph is built without a known size, so `OnHeapHnswGraph#maxNodeId` grows with every insert.
  `HnswGraphSearcher#search` sizes its visited set (`SparseFixedBitSet`) from it when the search starts;
  `findBestEntryPoint` reads it again and, when a node was added in between, grows the set through
  `FixedBitSet.ensureCapacityAndClear((FixedBitSet) visited, …)`, and that cast fails. The out-of-bounds error
  ("Index 32 out of bounds for length 32") was recorded without a stack trace; most likely a reader read a
  node's neighbour list while the writer was growing or pruning it. Lucene says so at `maxNodeId`: such a graph
  "cannot be concurrently modified (and searched)". A known size would remove the cast error but not the
  out-of-bounds one; Lucene's per-node locks (`HnswLock`) serve its
  concurrent merge only, the public search takes none. A Lucene graph therefore has to be replaced, not
  mutated, under readers: `InitializedHnswGraphBuilder#fromGraph` copies an existing graph with deleted nodes
  dropped, repairs the nodes that lost neighbours, and accepts new inserts (not measured here).
- **ACORN's low recall is not our wiring.** A diagnostic ran Lucene's `FilteredHnswGraphSearcher` and our own
  port of it (the same constants) over the same Lucene graph and the real filters: recall@10 0.393 for both on
  A, 0.140 for both on E. ACORN puts only members into its candidate queue and reaches members through at most
  two hops over non-members; when none is that close, the queue runs dry and the walk ends. On E the walks
  return on average 21 of the 100 requested candidates, on A 39. The cause differs per corpus. On E it is the
  sparse Lucene graph: mean level-0 degree 6.9 of the allowed 32, and our port over jVector's graph (degree 32)
  reaches 0.965 with the same filter. On A it is the filter: 246 members in one cluster defeat ACORN on every
  graph (0.16–0.19 over jVector's hierarchical graphs, 0.000 over the flat ones, measurement 9). Lucene does not
  fall back on a short result. ACORN is not a candidate strategy. Why Lucene's graph is this sparse is answered by
  the follow-up of measurement 6: a larger `M` leaves the degree unchanged, the data and Lucene's diversity rule set
  it.

- **Follow-up runs (2026-11-09)**: PQ centering (measurement 3), JMH of the library combination (4), ACORN over denser
  Lucene graphs (6), the combination under churn (11), the graph copy and swap with the delete repair variants (12)
  and the transaction overlay (13) ran after the main run, one at a time, with the same build of the harness plus the
  new scenarios. Their jVector rows carry the usual run-to-run
  variation; the tables of the main run were not rerun.

- **Corpus D stands in for 1M × 768.** No 1M dataset was available; the spec's latency criterion and the gate are
  judged on D (416,559 × 2,048), which is about the same bytes. See the mini-gate below.
- **Distance computations per query** (spec §4.4, §4.8) are the visited counts: our searcher scores every visited node
  exactly once, so the two are the same number.
- **The `siftsmall` smoke test** (spec §4.2) was not run. Its purpose, a check of the recall computation against a
  reference ground truth, is covered by the exact scan, which shares the kernel with the ground truth and returns
  recall 1.000 in every table.
- **Not part of the spike** (spec §1.4): the OSQ code of an inserted vector against the code file's fixed centroid,
  the transaction overlay under a filter, and any connection to evitaDB's transactional layer and WAL. They are
  implementation work.

## Criteria

| spec criterion | result | corpora |
|---|---|---|
| memory per component, raw vectors off the heap (§2.3, §2.8, §4.6) | **met**: graph 260–400 B/vec on the heap, raw vectors and codes mapped at no loss of recall | A–E |
| recall@10 ≥ 0.95 per code, oversampling (§1.2, §3.5, §4.3) | **met for Lucene OSQ 4 and 7 bits and float32 at 4×** on B–E; A misses it by 0.005 (OSQ 4-bit), A's float32 walk itself reaches only 0.957; OSQ 1-bit reaches 0.913 on A; jVector PQ misses on A, B, E | A–E |
| latency p95 < 10 ms (§1.2, §4.5) | **met**: every unfiltered walk p95 ≤ 1.6 ms at 416k × 2,048 (JMH) | A–E |
| filtered ANN per strategy and selectivity (§3.4, §4.4) | **met with the planner** at the real filters; in-walk breaks the latency criterion on large corpora with correlated filters ≤ 10 %, so the planner needs a selectivity-based switch to the exact scan (cheaper from 1–10 % down); post-filter and ACORN rejected | A, B, E real; A–E synthetic |
| our searcher, cancellation (§2.7) | **met**: same recall as `GraphSearcher`, all walks cancelled | A–E |
| graph family, hierarchy (§3.2) | no difference up to 416k vectors; the flat graph is enough at our sizes | A–E |
| graph in our own file (§2.3) | **met**: 136 B/node, identical candidates, empty reader heap | A–E |
| incremental maintenance (§4.7) | **met by jVector** (no errors, recall as a fresh build; transient dead nodes during replacement must be filtered), also with our searcher and OSQ codes; Lucene's graph is not concurrent; copy-and-swap works for batches, deletes as liveness bits repaired with `removeDeletedNodes()` (measurement 12); transactions keep their changes beside one shared graph (measurement 13) | A–E |
| relevance against clicks (§1.2) | the index loses no pairs hit@10 on A and at most 0.011 on B against the exact scan; the embedding bounds relevance | A, B |

## The mini-gate (spec §1.3) and the verdict

The table of spec §1.3, filled in from the measurements above. Corpus D (416,559 × 2,048) stands in for the
1M × 768 configuration of the spec, which was not available: its raw block is about 1.1× the bytes of 1M × 768,
so its memory figures are comparable, and every distance costs 2,048 numbers instead of 768, so its latency and
scan figures are harsher than 1M × 768 would be. A walk over 1M nodes visits somewhat more of them than over
416k; that part is not covered.

**Three candidates** were measured, not two. jVector alone (path A) and Lucene's vector packages as a library
(path L) are the spec's. The third is **the library combination**: jVector's graph, Lucene's OSQ codes in a
mapped file of our own, and evitaDB's own searcher, planner and exact scan. The spec allowed it once the codes
proved to plug in through the scorer seam (measurement 8).

| criterion | threshold | weight | jVector alone (A) | Lucene as library (L) | combination |
|---|---|---|---|---|---|
| recall@10 with rescoring | ≥ 0.95 | blocking | **misses on B** with both own codes at 4× (PQ 256 × 8 0.921, BQ 0.898); PQ also on A and E (measurement 3) | **met on B–E** with OSQ 4/7-bit and float32 at 4×; on A the walk itself limits it (float32 0.957, OSQ 4-bit 0.945) | **met everywhere**: OSQ 4-bit at 4× 0.976–0.996, OSQ 1-bit at 4× 0.967–0.995 (measurement 8) |
| query latency, p95 | < 10 ms | blocking | met: PQ ≤ 1.1 ms, BQ ≤ 0.33 ms (JMH, measurement 4) | met: ≤ 1.6 ms, OSQ 1-bit 0.45 ms (JMH, measurement 4) | met: ≤ 0.81 ms on D with OSQ 4-bit (JMH, measurement 4) |
| filtered ANN | no band below 0.95 the exact scan does not cover cheaply | blocking | met with float32 and our searcher (in-walk ≥ 0.97, the exact scan below); with PQ the planner falls to 0.92–0.94 in correlated cells of E (measurement 6) | Lucene's own predicate-aware walk (ACORN) **fails**: 0.39 / 0.14 at the real filters of A and E, below 0.95 in most cells of 1–10 %; in-walk plus our exact scan pass | met: in-walk plus the exact scan, as for A |
| incremental maintenance | works within a measured rebuild cadence | blocking | **met**: concurrent inserts, deletes and `cleanup()` with zero errors, recall equal to a fresh build | **fails as a live structure**: readers crash during inserts (`ClassCastException`, `ArrayIndexOutOfBoundsException`); usable only by copy and swap, and deletes renumber the ordinals | **met**, as jVector (measurement 11); deletes repaired with `removeDeletedNodes()` (measurement 12) |
| JPMS | works or has a workaround | blocking | automatic module, no `jlink` (known, unchanged) | named module | both, as in A and L |
| interruptibility | the query can be cancelled | high | met by our searcher: every walk cancelled within one check (measurement 8) | `earlyTerminated()` per iteration | met by our searcher |
| codec pluggability | a foreign scorer drives the walk without a fork | high | met: Lucene's OSQ drives jVector's graph through the one-method scorer seam | met: scorers are plain classes | it *is* this criterion |
| heap memory | codes + graph within the §1.2 budget; raw vectors not on the heap | high | graph ~380 B/vec on the heap (151 MB at D), over §1.2's 100–250 MB per 1M; raw vectors mapped | graph 260–350 B/vec (139 MB at D); codes and raw vectors mapped | codes and raw vectors mapped; the graph in our own file takes 136 B/node mapped and no heap (measurement 10) |
| mmap compatibility | maps, no 2 GB failure | high | jVector's own reader is `MappedChunkReader` on JDK 21; not used, we map our own files | not applicable | our own files, mapped by us |
| maturity and format | released version, format stability | high | **4.0.2 is final** (the spec's "RC only" is out of date); builder configuration pinned per builder through the deprecated eight-argument constructor, no fork (OP6-15) | released 10.6.0, stable format | both released; the formats on disk are ours |
| dependency size | no frameworks | medium | four, one dead | none | jVector's four plus Lucene core, which evitaDB already pins |
| graph family | informative | medium | `alpha = 1.0` builds about twice as fast at half the degree with the same recall; the hierarchy makes no difference up to 416k (measurement 9) | HNSW only; its mean degree stays 7–12 whatever `M`, which is what defeats ACORN | as jVector |

**Verdict.**

- **The library: the combination.** jVector supplies the graph and its maintenance, because it is the only
  library whose graph takes concurrent inserts and deletes under readers and keeps its ordinals. Lucene supplies
  the codes, because its OSQ codes reach the recall criterion where jVector's PQ does not. evitaDB owns
  everything between: the searcher, the planner, the exact scan, the code and graph files and the ordinal map.
  Neither library alone passes every blocking criterion: jVector alone misses recall, Lucene alone misses
  incremental maintenance.
- **The version:** jVector 4.0.2 (final; OP6-6 is closed), Lucene 10.6.0. jVector's process-wide builder
  configuration is bypassed by its deprecated eight-argument constructor, which fixes every parameter per builder;
  no fork is needed (OP6-15).
- **The codec:** OSQ 4-bit for a recall margin, or OSQ 1-bit within the 1-bit budget of §1.2. Both pass at 4×
  with the combination; 1-bit at 0.967 on the hardest corpus.
- **The graph:** jVector with `alpha = 1.0` unless a later measurement says otherwise, the hierarchy optional.
  Built with `refineFinalGraph = false`: the refinement costs a pass over the whole graph on every `cleanup()`
  and buys no recall here.
- **The planner's thresholds**, measured:
  - post-filter only above ~85 % (it starves below ~50 %);
  - in-walk in between;
  - the exact scan of the members from 5–10 % down on corpora up to 214k vectors, from 1–5 % down on 313k–417k.
  The switch must depend on the member count relative to the corpus, not on a fixed 100 members.
- **ACORN is rejected:** Lucene's implementation and our port return the same low recall; the cause is the
  graph's sparsity and the filter's shape, not the wiring (measurement 6).
- **Transactions:** one shared graph per committed version, each transaction's changes beside it (its own
  inserts scanned exactly, its deletes in the walk's accept bits). It loses no recall, and up to ~1,000 own
  inserts it adds at most ~25 % to a search (measurement 13).

**Open risks, none blocking.**

- **Correlated filters of 5–10 % on the large corpora.** Recall passes, but neither path is under 10 ms there:
  the float32 exact scan of the members takes 7–27 ms p50 on C–E and in-walk 6–28 ms. A scan over the 4-bit
  code with a float32 rerank of the best candidates reads an eighth of the bytes and should close the gap; it is
  not measured.
- **Deletes are expensive to repair.** A fixed pass over the graph (2.5–7.9 s on C–E) plus 2–4 ms per deleted
  node. Deletes therefore wait in the liveness bitmap and are repaired in batches, rarely.
- **The step that the spec calls path B** (step 8) is answered in the harness, not in the engine: the graph,
  the codes and the raw vectors are read from mapped files of our own without loss and without heap. Wiring the
  files into `ObsoleteFileMaintainer` with the pin protocol is implementation work; the spec's non-goals keep
  production code out of the spike.
