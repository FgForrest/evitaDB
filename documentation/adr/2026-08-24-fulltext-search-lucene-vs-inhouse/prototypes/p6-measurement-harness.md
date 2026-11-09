# P6 — the measurement harness: how to re-run the vector spike on a new dataset

**Companion to [`p6-vector-spike-spec.md`](p6-vector-spike-spec.md),
[`p6-measurement-results.md`](p6-measurement-results.md) and
[`p6-implementation-proposal.md`](p6-implementation-proposal.md).** The results doc records what was measured,
the proposal what follows from it; this file says how to measure it again, on another dataset, without
redesigning anything:

- where the harness lives;
- what a dataset is;
- which scenario answers which criterion of the spec;
- the traps that already cost a run once.

---

## 1. Where it lives

Module `evita_test/evita_performance_tests` (built only under the `full` Maven profile), package
`io.evitadb.spike.vector`.

| class | role |
|---|---|
| `P6Harness` | entry point: `P6Harness <workDir> <scenario>`, or `P6Harness list` for the catalogue of section 4 |
| `SpikeDataset` | a dataset = a work directory with a `dataset.properties` descriptor (section 3) |
| `SpikeIndexes` | every index the scenarios build, with its structural parameters fixed in one place |
| `VectorExportExtractor` | importer: an evitaDB entity export with embeddings → the dataset's blocks |
| `ClickQueryEmbedder` | importer: the production search and click logs → embedded query vectors and clicks |
| `ClickRecallScenario` | results doc measurements 5 and 7: every code and strategy on production queries; also the shared evaluator and walks |
| `GraphSeamScenarios` | results doc measurements 8–10: our searcher, the graph family, the graph file |
| `FilteredMatrixScenario` | results doc measurement 6: the filtered matrix |
| `ChurnScenario` | results doc measurement 11: the churn protocol; also on the library combination (`churn-combined`) |
| `GraphSwapScenario` | results doc measurement 12: a graph copied from the live one, changed by a batch and swapped in |
| `GraphDensityScenarios` | results doc measurements 3 and 6: PQ centering, ACORN over denser Lucene graphs |
| `TransactionOverlayScenario` | results doc measurements 12 and 13: jVector delete repair variants, a transaction's changes beside one shared graph |
| `JVectorFootprintScenarios`, `LuceneFootprintScenarios`, `LuceneWiringScenario`, `JVectorWiringScenario` | results doc measurements 1–3: memory, codecs, the wiring |
| `ClickSearchLatencyBenchmark` | results doc measurement 4: JMH latency |
| `LuceneVectorKernelBenchmark` | implementation proposal, section 9.2: JMH latency of Lucene's distance kernels |
| `GraphView`, `EvitaGraphSearcher`, `Acceptor`, `MappedGraphFile`, `OsqCodeFile` | the evitaDB-owned seams under test |

## 2. Running

**Build.** Once, and after every change to the module:

```shell
mvn -nsu -Pfull -pl evita_test/evita_performance_tests compile
mvn -nsu -Pfull -pl evita_test/evita_performance_tests dependency:build-classpath -Dmdep.outputFile=cp.txt
# the classpath is target/classes followed by the content of cp.txt
```

When a compile fails outside the vector package, the local repository holds stale engine jars. Reinstall them
with `mvn -nsu -q install -DskipTests -pl evita_test/evita_test_support -am`. Never pass `-o`.

**One scenario.** One JVM per scenario, on **JDK 21** (the production target; on 22+ jVector loads another
multi-release layer). Name the JDK 21 `java` explicitly; the machine's default may be newer:

```shell
<JDK 21>/bin/java -Xmx4g -XX:NativeMemoryTracking=summary \
  --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -Djvector.vectorization_provider=panama -Djvector.management.backend=none -Djol.magicFieldOffset=true \
  -Dstdout.encoding=UTF-8 \
  -cp <classpath> io.evitadb.spike.vector.P6Harness <workDir> <scenario> > <output>.txt 2>&1
```

- `-Djvector.vectorization_provider=panama` turns a silent scalar run into a loud failure.
- `--add-modules jdk.incubator.vector` is what enables Lucene's SIMD.
- Every output must show both `PanamaVectorizationProvider` and Lucene's "Java vector incubator API enabled"
  before any number from it is used.
- `churn` ran with `-Xmx6g` (4 GB was not tried); everything else ran in 4 GB at 23k × 2,048.

**JMH latency** (results doc measurement 4, spec §4.5). JMH forks its own JVM and takes its flags from the
benchmark's `@Fork`, so the command line needs none:

```shell
<JDK 21>/bin/java -cp <classpath> \
  io.evitadb.spike.vector.ClickSearchLatencyBenchmark ClickSearchLatencyBenchmark \
  -p workDir=<workDir> -rf json -rff <result>.json > <result>.txt 2>&1
```

One dataset per run. It takes about 25 minutes for 28 configurations. The JSON is the configuration record of
§4.8; keep it.

## 3. A dataset

A dataset is a work directory under the git-ignored `specifications/p6-vector-spike/work/`. Its descriptor
`dataset.properties` holds:

```properties
# how the results doc names it; never a customer name, it is printed into every output
label=corpus C
# how the blocks are produced: evitadb-export is the only importer today
source.format=evitadb-export
# the source, absolute or relative to the work directory; customer data never enters the repository
source.file=../../../../vector-data/<export>.json
# optional: the collection the query-driven scenarios filter to; with clicks, the collection the clicks point to
collection=<collection code>
# optional, for a dataset without users' queries: records held out of the corpus as the 1,000 queries of §4.3
queries.holdOut=1000
queries.seed=42
```

`P6Harness <workDir> prepare` extracts the blocks, holds the queries out, and prints the vectors and records per
collection and locale, which is what `collection` is chosen by. Run it first on every new dataset.

**The blocks every scenario reads:**

| file | content | written by |
|---|---|---|
| `text.f32` | the corpus: float32 little-endian, row-major, `dimension` floats per vector, unit length | importer |
| `text-ordinals.tsv` | one line per vector: collection, record id, locale, url (tab-separated) | importer |
| `visual.f32` | out-of-corpus query vectors for parts 1–8 (same format as `text.f32`); empty when the export has no visual embeddings, and the parts 1–8 scenarios then take the first 100 vectors of the query block | importer |
| `dimension.txt`, `corpus-format.txt` | the dimension; the block format version (`2`: one copy per record) | importer |
| `click-queries.f32` | the query block: embedded production queries, or the held-out vectors | `ClickQueryEmbedder` or `HeldOutQueries` |
| `held-out-queries.tsv`, `held-out.txt` | the ordinal line of each held-out record; the marker of the hold-out | `HeldOutQueries` |
| `click-queries.tsv`, `clicks.tsv` | the query texts; the clicks joined to them | `ClickQueryEmbedder` |
| `click-lucene-*.codes`, `graph-*.graph` | derived: our OSQ code files (rewritten when older than `text.f32`), graph files (rewritten by every `graph-file` run) | scenarios |

**Adding an evitaDB export.**
1. Create the directory with its descriptor. The first scenario extracts the blocks. The extraction is redone
   when the block format changes; a different source file is refused, because a work directory holds one
   dataset.
2. To run the click scenarios, embed the queries with the production embedding service. This is the only step
   that calls an external service. The credentials come from the environment and never from the command line:

   ```shell
   export EMBEDDING_SERVICE_URL=<encode endpoint>
   export SERVICE_AUTH=<Basic credentials, base64>
   java -cp <classpath> io.evitadb.spike.vector.ClickQueryEmbedder \
     <result-clicks.csv> <search-queries.csv> <workDir> --sample 1000
   ```

   `--sample N` takes a fixed-seed sample of the distinct query texts that led to a click; a larger N is a
   superset of a smaller one. The embedder pauses 0.5 s between calls, caches by query text, and turns the search
   engine's logged typo-tolerance rewrite back into the typed words.

**Adding an evitaDB export without clicks.** Declare `queries.holdOut=1000`. `HeldOutQueries` takes a seeded sample
of records out of the corpus with all their vectors and writes the first vector of each (an article's title and
lead) as the query block. Held out, not in the corpus: an in-corpus query finds itself and raises every recall. The
click scenarios then print recall and latency without the click columns. Without `collection` they run the `all`
mode only, and the filtered measurement is `filter-matrix`'s synthetic one; the JMH benchmark then takes
`-p configuration=exact:all,lucene-float32:all,lucene-osq1:all,lucene-osq4:all,lucene-osq7:all,jvector-pq:all,jvector-bq:all`.
A collection that is a sizeable share of the corpus can serve as a real-shaped filter even without clicks.

**Adding a dataset in another format** (the public 1M set the spec's criteria are stated for, OP6-8):
1. Write an importer that produces `text.f32`, `text-ordinals.tsv`, `dimension.txt` and `corpus-format.txt`. A
   public set has no records, so every vector is its own record: one collection, the ordinal as id, an empty
   url.
2. Write its query vectors as `click-queries.f32`, or declare `queries.holdOut`.
3. Set `source.format` to the new format's name and give `SpikeDataset#open` the branch that calls the importer.
   It is marked in the code.

Every scenario runs on every dataset that has a query block. Only the click columns need clicks.

## 4. Criteria → scenario → doc part

The gate rows of spec §1.3 and the test sections of spec §4, with what measures them today. `P6Harness list`
prints the scenario column.

| spec criterion | scenario or benchmark | records into | measured on | still open |
|---|---|---|---|---|
| memory per component, raw vectors off the heap (§2.3, §2.8, §4.6) | `jvector-heap`, `jvector-mmap`, `jvector-ondisk`, `lucene-hnsw`, `lucene-float32` | results doc 1; proposal 8 | A–E | **1M × 768** (OP6-8) |
| recall@10 ≥ 0.95 per code, oversampling (§1.2, §3.5, §4.3) | `lucene-osq*`, `click-recall` (production queries) | results doc 3, 5 | A–E | — (corpus D stands in for 1M) |
| one graph walked by every code (§3.5) | `own-searcher` | results doc 8 | A–E | — |
| SIMD, JDK 21 traps (§2.1, §4.5) | `LuceneVectorKernelBenchmark` | proposal 9.2 | kernels | — |
| the seams: our code file, mapped raw block (§2.6) | `lucene-wired-*`, `jvector-wired` | results doc 2 | A–E | — |
| latency < 10 ms (§1.2, §4.5) | `ClickSearchLatencyBenchmark` (JMH) | results doc 4 | A–E | **1M**: the criterion is for 1M |
| filtered ANN per strategy and selectivity (§3.4, §4.4) | `click-recall` (real filter), `filter-matrix` (synthetic) | results doc 5, 6 | real filter A, B, E; synthetic A–E | 1M; the switch point between 50 and 85 % |
| membership structure at 50 % (§2.5, §4.4) | `filter-matrix` (the `in-walk` row against `in-walk, BOOLEAN_ARRAY / FLAT_BITSET / ROARING`) | results doc 6 | A–E | JMH ranking (harness timings only) |
| our searcher, interruptibility (§2.7, step 5) | `own-searcher` | results doc 8 | A–E | — |
| graph family (§3.2, step 4) | `graph-family` | results doc 9 | A–E | **1M** decides the hierarchy |
| incremental maintenance, churn (§4.7, step 7) | `churn`, `churn-combined` (jVector graph, our searcher, OSQ codes) | results doc 11 | A–E | 1M; a fresh Lucene build at the same size |
| copy-and-swap maintenance (§4.7) | `graph-swap`, `jv-delete-repair` | results doc 12 | A–E | appending OSQ codes for inserted nodes (implementation) |
| a transaction's changes beside one shared graph (§4.7) | `tx-overlay` | results doc 13 | A–E | the overlay under a filter (implementation) |
| ACORN against graph density, PQ centering (§3.4, §3.5) | `acorn-density`, `pq-center` | results doc 6, 3 | ACORN A, B, E; PQ A–E | — |
| graph persisted in our own file (§2.3) | `graph-file` | results doc 10 | A–E | append-only update, variable-length slots |
| recall against users' clicks (§1.2) | `click-recall` | results doc 7 | A, B (the only corpora with clicks) | — (bounds the embedding, not the index) |
| the gate table and the library verdict (§1.3, step 9) | — | results doc, the mini-gate | A–E, D for 1M | — |

**For the 1M dataset, in this order:**
1. `jvector-mmap` and `lucene-float32` (memory and build time);
2. `lucene-osq1|4|7` and `own-searcher` (codes);
3. `ClickSearchLatencyBenchmark` (the 10 ms criterion);
4. `filter-matrix` (watch the scan cost of the 10 % band, results doc 6);
5. `graph-family` (hierarchy);
6. `graph-file`;
7. `churn`.

## 5. What is fixed, and what is not the same everywhere

**Fixed in `SpikeIndexes`**:
- Lucene: `M = 16`, beam 100, seed 42, one thread, so the Lucene rows reproduce exactly between runs.
- jVector: degree 32, beam 100, overflow 1.2, refine on, `alpha` 1.2 with the hierarchy unless a scenario varies
  them, all cores, so jVector rows vary by about ±0.02 between runs.
- Codes: PQ 256 subspaces × 8 bits, retrained per run; BQ; Lucene OSQ through our `OsqCodeFile`.
- Walk depth 100, ten results, rerank at 1/2/4/8×.

The parts 1–8 scenarios keep their own construction, because they measure the memory of exactly that construction.

**Filters are bitmaps of primary keys** (spec §2.4, §2.5), as the rest of an evitaDB query hands them over:
- `ClickRecallScenario.Filter` holds a `RoaringBitmap` of record indexes (the record index is the primary key)
  and the `PrimaryKeyOrdinals` map: `ordToPk` for every vector, and `pkStart`, the first ordinal of every record.
  A record's vectors are consecutive ordinals; `PrimaryKeyOrdinals.of` refuses a corpus where they are not.
- A filter always holds whole records, so the member count of a synthetic filter can slightly exceed its
  nominal share.
- In-walk, ACORN, post-filter and jVector's `acceptOrds` test `bitmap.contains(ordToPk[ord])`
  (`Acceptor.Representation.PRIMARY_KEY_BITMAP`, the default everywhere).
- The exact scan iterates the bitmap and scores each member's ordinals `pkStart[pk]..pkStart[pk + 1]`, so its
  cost follows the member count, not the corpus size. The planner uses both.
- The `boolean[]` over ordinals stays only for ground truth, click resolution and the 50 % comparison, where
  `BOOLEAN_ARRAY`, `FLAT_BITSET` and `ROARING` over ordinals are measured against the default.
- Recall does not depend on the representation; only the filtered latencies do. Runs before this model (the
  first corpus C and D matrices) iterated every ordinal in the exact scan.

**Three recall definitions, on purpose.** Do not unify them: unifying would change recorded numbers.

| where | definition |
|---|---|
| `click-recall`, `own-searcher`, `graph-family`, `graph-file` (`ClickRecallScenario.Evaluator`) | results collapsed to records first; ties at the tenth exact score count as hits |
| `filter-matrix` (`FilteredMatrixScenario.Cell`) | on ordinals, ties counted; fewer than ten members → against all of them |
| `churn` | on ordinals of the float32 walk without a rerank, against the exact scan over the nodes live at that moment |
| `churn-combined` | as `churn`, but the OSQ walk reranked exactly at 4× |
| `graph-swap` | as `churn` (float32 walk, no rerank), over the nodes of the swapped-in graph, 100 queries |
| `acorn-density`, `pq-center` | `ClickRecallScenario.Evaluator` without clicks, 200 queries (107 on A) |
| `jv-delete-repair` | as `graph-swap`, over the nodes of the repaired copy, 100 queries |
| `tx-overlay` | the merged top ten against the exact scan over the graph's records minus the deletes plus the own vectors, 500 queries (107 on A) |

## 6. Traps that already cost a run

- **JMH needs the class name as its first argument.** Without it, `org.openjdk.jmh.Main` runs every benchmark
  of the module, for hours.
- **Never compile while JMH runs.** A compile regenerates `jmh_generated`, and every later fork fails with
  "Benchmark does not match a class". Check the fork count in the output, not the exit code.
- **One measurement at a time.** Parallel runs share the cores, and jVector's build and `cleanup()` use all of them.
- **A change is in effect only after a compile.** The churn run once failed on a fix that was edited but not yet
  compiled; compile, then start the run.
- **Read the provider lines of the *forked* JVM** (JMH) or of the scenario's own output, not the launcher's.
- **Set jVector's search beam explicitly.** The convenience overload `GraphSearcher.search(provider, topK,
  acceptOrds)` searches with a beam of `topK`; at a beam of 10 it settles in a local optimum in about 2 of 20
  runs. Every reference walk passes `rerankK` itself.
- **The latency of the recall harness is not JMH.** Use it for orders of magnitude, quote JMH for the criterion.
- **The machine's default `java` may be 25.** An early exploratory run used it by accident; name the JDK explicitly.
- **A block over 2 GB** is mapped in segments (`MappedVectors`); one `MappedByteBuffer` cannot exceed 2 GB.
- **Exports repeat records** under every referencing record; the extractor keeps one copy per record (block
  format 2). An index over the raw export collapses (implementation proposal, section 12).
- **Customer data stays out of the repository:** exports, logs, query texts, work directories, collection codes,
  outputs. The work directory is under the git-ignored `specifications/`. In the docs, datasets are "corpus A,
  B, …".

## 7. Where outputs go

Raw outputs (scenario `.txt`, JMH `.json`, the matrix rows) go to `specifications/p6-vector-spike/work/results/`,
next to the scripts that produced them. They are never committed. The results doc quotes the numbers, names
the scenario and the JDK, and states every deviation (a different JDK, a failed fork, a rerun).
