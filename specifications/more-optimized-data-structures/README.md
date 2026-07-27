# More optimized data structures in indexes (issue #760)

Design, planning, and investigation documents from evitaDB issue #760 ("more optimized data
structures in indexes / granular storage parts"), carried over from the
`760-more-optimized-data-structures-in-indexes-more-granular-storage-parts` feature branch
before its local checkout was removed. These lived in a gitignored `/docs/` folder on that
branch (private working notes, not previously committed) — moved here, into the
`specifications/` convention this repo already uses for feature design docs (see
`faceted-partially-indexing/`, `bucketed-histogram-indexing/`), so the reasoning behind the
merged code isn't lost.

**Staleness note:** many documents were written mid-development and describe work as
"uncommitted" or "awaiting Johnny's go" at the time. By the time the branch merged, most of
that work had landed — treat status language in these files as a historical snapshot, not
current fact. Check `git log`/`git blame` on the relevant source for what actually shipped.

See also `../write-path-performance-tuning/` — a second #760 line targeting commit/merge latency
and write-path allocation via real production WAL replay, rather than the granular-storage-part
decomposition covered here. Different code areas, same issue, overlapping cast of index classes
(`SortIndex`, `FrontCodedStringColumn`).

## Layout

- **plans/** (17 files) — dated implementation plans: storage-part decomposition, SortIndex/
  FilterIndex slimming, identity-change detection, EntityIndex bitmap eviction, unique B+
  tree, RoaringBitmap vendoring, histogram paging, inverted-index bitmap demotion,
  formula-cache staleness, fromArray dispatch.
- **design/** (20 files) — deeper design notes: RefTypeCardinality paging, SortIndex
  10M-churn ideation and paging, ChainIndex churn/collapse, compaction auto-tuning, CRC32C
  combine caching, FrontCodedStringColumn allocation attacks, Kryo/ObservableOutput buffer
  pooling, RoaringBitmap cloning, dense-walk AndNot, InvertedIndex bucket flyweight, SortIndex
  committed-snapshot cache, Option-B generalized stage-1 results.
- **performance/** (3 files) — B+ tree shared-base extraction plan, decodoma dataset gate
  analysis, PriceSuper page-chunk design.
- **reports/** (15 files + `raw-analysis/`) — final write-ups: FrontCoded remeasures,
  InvertedIndex bucket-flyweight remeasure, SortIndex cache E2E findings, warmup-test
  remeasure, write-and-query throughput remeasure, attrfilter fromArray fix results,
  async-profiler attrfilter summary, `optimization-recommendations.md`,
  `write-churn-findings.md`, the unique/ALIVE churn `warmup-bench-diagnosis.md`/
  `warmup-bench-results.md` (FrontCodedStringColumn byte[] churn made unique-attribute ALIVE
  commits 3.4x slower than range/chain), and `sortindex-benchmark-baseline-vs-optimized.md`.
  `raw-analysis/` holds short analysis.txt notes pulled out of otherwise-omitted raw-profile
  directories. The raw `.jfr`/`.collapsed`/`.csv`/JMH-json/log data behind these reports
  (~200MB) was NOT carried over — regenerate via the same benchmark harnesses if needed.

Spike/benchmark source code from this investigation lives in its normal location:
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/` (10 files added:
`FrontCodedFindKeyBenchmark`, `FrontCodedSerializationBenchmark`,
`FrontCodedTreeQueryBenchmark`, `PrimitiveColumnSerializationBenchmark`,
`SortAnchorExtractor`, `SortIndexBenchSupport`, `SortIndexChurnReport`,
`SortIndexCommittedSnapshotCacheBenchmark`, `SortIndexResolvePositionsBenchmark`,
`SortIndexTimingBenchmark`).

The corresponding Claude session-memory notes from that branch were reviewed and the durable
ones (bug root causes, architecture decisions, reusable recipes) were folded into this
project's own Claude memory rather than duplicated here as files.

## Known open follow-ups (not yet resolved as of the #760 merge)

- **JMH read-benchmark pool exhaustion**: `evita_test/evita_performance_tests/.../performance/setup/EvitaCatalogSetup.java`
  hardcodes `.maxOpenedReadHandles(12)`. On machines with more than 12 cores,
  `@Threads(Threads.MAX)` read benchmarks in `ArtificialEntitiesThroughputBenchmark` exhaust
  the pool and JMH silently reports an empty `[]` result instead of erroring — confirmed
  pre-existing on `dev` too, not a #760 regression. A fix
  (`Runtime.getRuntime().availableProcessors() * 4`) was drafted but never committed; worth a
  small standalone PR.
- **WAL-purge catalog-file race**: pre-existing `FileNotFoundException` race in WAL-rotation
  purge, reproduces on `dev`, unrelated to #760. Already filed as **evitaDB#1203**.
- **`EntityIndexManifestInvariantTest.shouldListAllAttributeSubIndexesInManifest`**: a
  pre-existing (not #760-introduced) manifest gating asymmetry — `getUniqueIndexes()` unions
  view keys ungated while `collectKeys()` gates on `sharedValueIndex.containsKey`. Noted as a
  known follow-up during the AttributeIndex coarse-op refactor; fix direction is to gate
  `getUniqueIndexes` and pair the fixture with both insert+filter.

Two other items investigated during #760 turned out to already be resolved, despite earlier
working notes describing them as open — confirmed via `git log`/`git merge-base` against this
branch's actual HEAD rather than trusting the memory snapshot:
- The stranded-price-id reduced-index data-loss bug (`Price with id N not found in the same
  index!` / `Record id N is already present in the sort index!`) was root-caused to a stale
  `EntityIndex` manifest-change-detection baseline never refreshed after a warm-up flush, and
  shipped in commit `b3f25b4b1` ("fix: advance EntityIndex change-detection baseline after
  warm-up flush").
- RangeIndex's constant-`1L` formula-cache staleness (issue #37) was fully fixed, including
  the leaf-granular refinement, in commit `7fa7648d2` ("fix: leaf-granular formula-cache
  staleness eliminates RangeIndex stale reads (#37)").
