---
title: Prototype an in-house fulltext core over evitaDB's bitmap algebra instead of integrating Lucene
date: 2026-08-24
updated: 2026-08-30 02:32
status: partially-implemented
kind: feature
issues: [258, 1454]
prs: []
areas: [evita_engine, evita_api, evita_query, evita_store, evita_external_api, evita_engine/index/trigram]
supersedes: []
superseded-by: []
relates: [2026-07-07-roaring-bitmap-vendoring, 2026-07-10-more-optimized-data-structures, 2026-08-01-bplustree-cursor-free-insert-path, 2026-07-27-write-path-performance-tuning]
---

# Fulltext search in evitaDB: an in-house core over the bitmap algebra, not a Lucene integration

Issue #258 asks for fulltext search in evitaDB. The obvious answer — embed Apache Lucene — was
examined first and declined; the research recommends building a fulltext core on evitaDB's own
bitmap algebra, taking from Lucene only the two parts that are genuinely hard and genuinely
separable (linguistic analysis and the Levenshtein automaton) as an ordinary Maven dependency.

**The fulltext core itself is not implemented; one prototype of it is.** P8 — the trigram substring
index of issue [#1454](https://github.com/FgForrest/evitaDB/issues/1454), a sub-issue of #258 — shipped
in full: an opt-in per-attribute accelerator for literal `attributeContains` / `attributeEndsWith`,
deliberately targeted **before** P1 because its value does not depend on the fulltext gate and because
it path-finds the shared hard parts (a scoped schema capability with reindex refusal, an MVCC-safe id
allocator on the shared value tree, a new index component on the global index, the formula-cache
contract) at membership-only scale. Everything else here is still a proposal. This record captures the
reasoning, the design decisions that were settled along the way, what P8's implementation measured and
changed, and the numeric gate that decides whether the rest proceeds — so that the options already
weighed are not re-proposed, and so that whoever starts the implementation knows which questions are
answered and which are deliberately still open.

## Why

evitaDB evaluates every query as set algebra over roaring bitmaps: the **complete** matching set is
produced first, and facet counts, histograms and paging are computed over it. Fulltext has to enter
that algebra as one more condition beside hierarchy, prices and facets — because "fulltext, facets,
prices and hierarchy in one query over one snapshot" is the combination customers choose evitaDB
for. A search engine that can only hand back a top-K cannot satisfy it: how many of those K survive
the mandatory filters (price lists, currency, validity, stock, scope) is not knowable in advance,
and facet counts computed from a top-K are simply wrong.

The constraint that makes the choice non-obvious is that this cuts against the intuition "fulltext
means Lucene". Lucene is excellent at what it was built for, and the parts of it we would actually
use are small; the parts we would pay for — its own storage, commit protocol, format compatibility
line, replication and internal document numbering — duplicate subsystems evitaDB already owns. The
integration price stays whole no matter how little of Lucene ends up being used.

The second driver is the pain of the existing solution. Fulltext at FG Forrest runs today on plain
Lucene inside the Edee CMS application server, with an e-commerce layer on top. Both were analysed
in full (see *Supporting material*) as the yardstick a new solution must not fall below. The finding
that matters: **most of the volume of today's solution is not searching**. It is a crawler, text
extraction from PDF and Office documents, and ninety-seven classes of an index-synchronisation
module — S3 distribution, index twins, locks, watermarks, upload validation, retries and
diagnostics. All of that exists because the index is not in the database.

### Previous state

Nothing in evitaDB indexes text today. `attributeContains` / `attributeStartsWith` match exact
substrings, case-sensitively, over NFD-normalised values — they search characters, not words, and
they have no notion of relevance. The existing production fulltext therefore lives outside the
database entirely:

- A second copy of the data, built **from** evitaDB and maintained by a batch rewrite, with an N+1
  query per entity on the build path.
- All content except the title concatenated into a single flat text blob, so a match in the name,
  the meta description and the brand name are indistinguishable after indexing — field weighting is
  impossible for anything but the title.
- A language is a separate Lucene index, so searching two languages means two queries with
  incomparable scores.
- The filters that matter in an e-shop (availability, validity, category depth, price) are either
  baked into *membership* of the index at write time or applied after the search, in the
  application.
- A hard cap of 1000 hits, over which grouping and paging are then done in the application, yielding
  a response with two mutually inconsistent totals.

## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-08-12 | Build the fulltext core on evitaDB's bitmap algebra; take linguistic analysis and the typo automaton from Lucene as a dependency | The full candidate set is a handicap for a Lucene integration and an entry assumption for the bitmap model; analysis is the one part of Lucene that is separable and expensive to rewrite | [`research.md`](research.md) §1–§4, [`management-summary.md`](management-summary.md) ch. 1, 3 |
| 2026-08-12 | Order results by a cascade of discrete criteria packed into one 64-bit composite, not by document BM25 | No corpus-wide statistics to maintain, deterministic across replicas, explainable ("three words of three, zero typos, match in the title"); e-commerce engines all do it this way | [`research.md`](research.md) §4.3, [`lucene/engine-comparison.md`](lucene/engine-comparison.md) |
| 2026-08-12 | Keep the impact of a (field, term, document) triple as one quantised byte computed at index time | Carries BM25's real signals (saturated term frequency, length normalisation) without corpus statistics, and keeps every ranking lane bounded per query — which is what makes a later threshold or normalisation cheap | [`research.md`](research.md) §4.2, [`prototypes/p1-index-core.md`](prototypes/p1-index-core.md) |
| 2026-08-12 | Do not index term positions; treat proximity and phrases as a re-rank over the top-K | Positions are the largest single cost in the index; Elasticsearch and OpenSearch ship `match_only_text`, which does exactly this in production | [`research.md`](research.md) §4.7, [`prototypes/p4-proximity-rerank.md`](prototypes/p4-proximity-rerank.md) |
| 2026-08-12 | Split configuration by the **cost of changing it**, not by topic: what changes stored tokens goes into the schema, what is read at ranking time is a hot-swap artefact | The only criterion the five engines examined agree on; grouping by topic is how Meilisearch's `typoTolerance` ended up with three free members and two that force a reindex | [`prototypes/schema-design.md`](prototypes/schema-design.md) §2, §8 |
| 2026-08-12 | Boost tables, synonym and entity dictionaries are named artefacts exchangeable by an API call, never schema | Ranking freshness must not cost a reindex; in the bitmap model boosts never touch the write path | [`research.md`](research.md) §4.6, [`prototypes/p7-rank-profiles-and-boost-channel.md`](prototypes/p7-rank-profiles-and-boost-channel.md) |
| 2026-08-12 | The relevance sorter reads the score from the filter formula through an accessor interface, copying the `FilteredPricesSorter` two-phase pattern | The only established shape in the engine for sharing state between filtering and ordering; inventing a second one would be a new precedent for no gain | [`prototypes/query-design.md`](prototypes/query-design.md) §9.1–§9.3 |
| 2026-08-14 | Field weights live in `orderBy` as children of `relevance()`, field selection stays in the filter | Weights change the order, not the match set — the dividing line the language already enforces; the computational need for the filter formula to know them is solved by plumbing, not by syntax | [`prototypes/query-design.md`](prototypes/query-design.md) §4.2, §9.7 |
| 2026-08-14 | Rank profiles are not a separate mechanism — they are ordinary query profiles from issue [#12](https://github.com/FgForrest/evitaDB/issues/12) whose rules insert children into `relevance()` | One notion of "profile" instead of two; removes the profile registry from F1's scope and makes extraction from the query console a copy-paste | [`prototypes/query-design.md`](prototypes/query-design.md) §6.5 |
| 2026-08-14 | Suggest is a `require` constraint of an ordinary query, not a session method | A rich autocomplete (suggestions + product preview + recognized facets) is one round trip that way and two with a dedicated method; no engine has a dedicated suggest API — the only one that had it removed it | [`prototypes/query-design.md`](prototypes/query-design.md) §10.2–§10.4 |
| 2026-08-14 | Adaptive relaxation of the fulltext condition uses a targeted lower limit `N` and drops terms by **selectivity**, never by position; it is strictly opt-in | evitaDB always computes the full match set, so unconditional loosening would poison facets and histograms; and "drop the last word" fails on Czech head-final phrases, on RTL and on CJK segmentation | [`prototypes/query-design.md`](prototypes/query-design.md) Q16, §4.2 |
| 2026-08-14 | The engine annotates recognized **facets** only — deterministic dictionary hits that map onto a primitive the engine already has | A narrower name states the contract; interval and spatial intents are grammar or inference over the query text and belong to the client layer above the engine | [`prototypes/query-design.md`](prototypes/query-design.md) §8.3, [`prototypes/schema-design.md`](prototypes/schema-design.md) §6.9 |
| 2026-08-14 | Master/variant grouping is **not** part of fulltext; it belongs to the general issue [#17](https://github.com/FgForrest/evitaDB/issues/17) | It is an orthogonal engine capability composable with any query; the fulltext query API gets no grouping primitive | the internal e-commerce layer analysis, §5.5 |
| 2026-08-12 | Order the prototypes P5 → P1 → P2 and put a numeric decision gate after them | The biggest risks (memory, scan latency, the write-path tax) fall first, and the prototypes build the final structures, so the gate also calibrates the estimate for the rest | [`management-summary.md`](management-summary.md) ch. 4 |
| 2026-08-24 | Target the P8 trigram substring index before P1, with its offline analyzer as phase 0; P5 may run in parallel (disjoint scopes) | P8's value is independent of the fulltext gate (it fixes today's naive `contains`/`endsWith` and carries [#545](https://github.com/FgForrest/evitaDB/issues/545) regardless of the gate's outcome), it path-finds the shared hard parts — a new persisted index component, the scoped schema flag with reindex refusal, an MVCC-safe allocator, the formula-cache contract — at membership-only scale, and it closes P1's Q6 and Q7 before P1/P2 hit them | [`prototypes/p8-trigram-substring-index.md`](prototypes/p8-trigram-substring-index.md) §33–§34 |
| 2026-08-25 | SUBSTRING ships as an additive **capability of `filterable(...)`**, not as a sibling schema flag | It makes substring-without-filterable unrepresentable, following the `unique(AttributeUniquenessType)` precedent instead of minting a second axis over the same physical structure; every API mirror gains an optional field and old clients keep seeing `filterable: true` | `FilterIndexCapability` (`evita_api`), `SetAttributeSchemaFilterableMutation`; [`prototypes/schema-design.md`](prototypes/schema-design.md) §5.4 records why fulltext `searchable()` is deliberately **not** folded the same way |
| 2026-08-25 | Value ids are a lazy parallel column on the shared value tree, and switching them on or off is **refused** on a populated tree rather than made persistable | The back-fill writes id columns into leaves that nothing marks dirty, so the ids never reach disk and a reload would disagree with what is in memory — in both directions. The refusal is affordable only because the capability is already refused on a non-empty collection, so the tree is empty whenever an attach happens | `InvertedIndex#enableValueIds` / `#attachValueIdConsumer` / `#detachValueIdConsumer`; the two load premises in `AttributeIndexLoader` — the id column is all-or-nothing across a generation, and the root's high-water agrees with the pages about whether ids exist at all |
| 2026-08-26 | The value birth/death seam is a **sink threaded through the write call**, not a listener stored on the index | Every commit re-shells the inverted index, the attribute index and the global index, and the shared value tree is created lazily on first write — a stored listener needs re-binding at four separate points, and a missed re-bind stops maintenance with no symptom until a query silently under-reports. A parameter is compile-checked at every hop and holds no state that can go stale | `ValueLifecycleSink`, the sink-aware `InvertedIndex#addRecord` / `#removeRecord` overloads |
| 2026-08-26 | The posting store is a `TransactionalLongBPlusTree` — **overturning the spike's flat open-addressing table** | The spike measured the flat table at 1.1–1.6 ns/lookup, 40–60× a binary search over a sorted `long[]`, and it still lost: a published flat table is immutable, so one touched posting clones both spine arrays (`O(K)` per touched index per commit, ~1.5 MB at the largest measured `K`), which is exactly the large short-lived allocation this codebase moved its indexes onto B+ trees to escape. A pattern issues 2–15 lookups, so the whole probe penalty is under a microsecond against a query whose verification phase alone runs for tens to hundreds of microseconds | `TrigramPostingStore`; the spike's contrary measurement is [`prototypes/p8-trigram-substring-index.md`](prototypes/p8-trigram-substring-index.md) §35.2 |
| 2026-08-27 | **No persisted trigram format.** The index stays derived state, the rebuild is made bulk instead, and the duplicate global-index load is removed | The rebuild cost was the entire case for persisting, and it had been mis-costed: measured in tree order the incremental loop costs ~77 s on `article/title/cs`, and the catalog paid it twice (~154 s) because the global index was deserialized once serially and again inside the async task. An ordered-append bulk build does the same work in 4.0 s, once — after which a persisted form buys a second or two of catalog open and costs whole-posting rewrites on every commit forever | `TrigramPostingAccumulator`, `TrigramIndex#rebuildAll`, `Catalog` (the second read removed); the write-amplification numbers are under *Rejected outright* |
| 2026-08-29 | Reduced-index plans take **Shape P** — one verified global answer, ANDed with each target index's primary keys — and Shape Q is deferred | Shape P is not a new composition: `HierarchyOfReferenceTranslator` already runs exactly it in production over a memoised global formula, staleness tokens come out correct by construction, and the hoist has an existing home in `QueryPlanningContext#computeOnlyOnce`. Shape Q (cross into the reduced tree carrying *values*, probe by value) has a genuine per-index cost advantage and an exact precedent in `AttributeInSetTranslator`, but needs a new `InvertedIndex` accessor, a second typed memo and a hand-seeded token set — new surface justified by a measurement nobody has taken. Build P, measure it, let Q be earned | `AbstractAttributeStringSearchTranslator#resolveFromIndex` |
| 2026-08-29 | The gate prices against the **summed distinct-value count of the whole target set**, with an early exit | The trigram path displaces the scan over every index in the fan-out, not over any one of them: pricing against the global bucket count takes the accelerated path precisely where the scan is cheapest, and pricing against a single partition declines a wide fan-out that clears the floor by summation. Walking the target set in order to decide reads like a bug and is not — the worst case is one `getFilterIndex` plus one `getBucketCount` per index, strictly dominated by the per-index scan being decided against, which resolves those same filter indexes and then visits every bucket of every one of them | `TrigramSubstringSearch#accelerationThreshold`, `AbstractAttributeStringSearchTranslator#sumDistinctValuesUpTo` |
| 2026-08-29 | One hoisted global operand **per scope**, never one per query | A target set can span LIVE and ARCHIVED, and a `TrigramIndex` is hosted per `GlobalEntityIndex`, hence per scope. A single operand would intersect an archived reduced index's primary keys with the live global answer — a wrong answer, not a slow one. Pinned by a test whose two scopes hold disjoint primary-key ranges, so a cross-scope pairing collapses to empty rather than to something plausible | `AbstractAttributeStringSearchTranslator#hoistGlobalSubstringFormulas` |
| 2026-08-29 | `match` stays **outside** the per-query memo, and the scanned-value count stays **out of** the memo key | `computeOnlyOnce` stores and initialises a `@Nonnull Formula`, while `match` has three outcomes — declined, provably empty, real buckets — and "declined" has no `Formula` representation that is not already spoken for. The two halves are a matched pair: the memoised fold is a pure function of (trigram index, value tree, pattern, predicate) and the count only decides whether a caller gets far enough to ask for it, but moving `match` into the supplier would drag the *gate* inside a key that cannot tell two target sets apart, so a plan whose own gate declined would inherit the verdict of whichever plan was translated first. A cost gate whose answer depends on candidate ordering is worse than one occasionally paid twice | `AbstractAttributeStringSearchTranslator#createGlobalSubstringFormula`; the seam split that dissolves this is an open follow-up, below |
| 2026-08-30 | `CANDIDATE_SELECTIVITY_DIVISOR` raised from **4 to 12** | The shipped 4 admitted a 15–25 % posting-width band where the accelerator ran 1.1–2.3× *slower* than the scan it replaced. The measured crossover is 9.5 % width at n = 100 000 — a divisor of 10.5 — and **12 is that plus a margin bought by three conservatisms the benchmark could not measure**, not a number the data states. See the section below | *Verification*; `TrigramSubstringSearch.CANDIDATE_SELECTIVITY_DIVISOR` |
| 2026-08-30 | The eager fold stays, but **its stated reason is retired rather than confirmed** | "Eager, because an `OrFormula` is cacheable and a `DeferredFormula` is not" is formally true and worth close to nothing: the expensive half — candidate resolution and per-candidate verification — runs during *translation*, so a cache hit skips only the OR, which is exactly what deferring would have skipped. Measured, the substring formula is admitted in **0 of 15** cells at shipped settings, and buys 5–11 % with both admission floors removed — nothing at all on the widest patterns at n = 100 000. Eager stays because the fork is small in both directions and eager is the simpler shape: no new formula type on the path, no deferred-evaluation semantics, and a result that behaves exactly like the scan path's | *Verification*; `InvertedIndex#toFormula` |

### Why the in-house core won

The three shapes of a Lucene integration each fail on a different driver, and none of them is
recoverable by tuning:

- **Take the top-K and post-filter it in evitaDB** fails on *correctness*, not speed: facet counts
  and histograms need the complete set and cannot be derived from a top-K.
- **Let Lucene enumerate everything** is correct but switches off block-max WAND, the very algorithm
  that makes Lucene fast — what is left is a supplier of per-word document lists, with the full
  integration overhead still paid.
- **Move evitaDB's filters into Lucene** keeps the speed but means reimplementing a substantial part
  of evitaQL inside Lucene, after which there are two independent answers to "what is a valid
  product".

All three additionally pay four permanent taxes: docid↔primary-key translation that cannot be cached
across segment merges, a second storage and format-compatibility line, aligning `IndexWriter` commit
points with catalog versions, and a second replication channel (or divergent merge histories, which
make the same query return a different order on different replicas).

What would reverse this: if evitaDB ever stopped needing the full match set — no facets, no
histograms, no exact totals — the top-K shape would become viable and Lucene's strongest algorithms
would come back into play. That is not a plausible direction for this product, which is why the
decision is recorded as durable rather than provisional.

### The trigram gate constant, and the failure mode of a hand-set threshold

`CANDIDATE_SELECTIVITY_DIVISOR` admits the accelerated path only while the candidate bound is at most
`distinctValueCount / D`. It shipped at `D = 4` and was retuned to `12`. Both halves of that are worth
keeping, because both will be re-derived otherwise.

**How 4 was wrong.** The constant's own JavaDoc derived the right band — "the break-even ratio somewhere
between a third and a tenth" — and then took the wrong end of it, calling `4` "the conservative end" and
arguing the asymmetry correctly: over-caution costs a speedup that was never guaranteed, over-eagerness
costs a regression on a query that used to be fine. But a **larger** divisor is the strict one, so within
a band running from `D = 3` to `D = 10` the cautious end is 10, and 4 sits one step from the most
permissive end of the band the author had just written down. Nothing in the code could show it: a
too-eager cost gate produces slower correct answers, never failures. That is the failure mode of a
hand-set threshold, and it is the argument for the planner cost model the constant's last paragraph
already promises — the same paragraph that says this constant is a stand-in.

**Why 12 and not 10.5.** The bisect puts the crossover at 9.52 % posting width at n = 100 000, i.e. a
divisor of **10.5**; the SCAN arm's ±8–11 % confidence intervals put the crossover at 9.5 % ± 1 pp, i.e.
a divisor somewhere between 9.5 and 11.7. **10 sits inside that band and 12 sits outside it.** The step
past the measurement is bought by three conservatisms the benchmark could not measure, all pointing the
same way and none pointing back: the corpus produces no
false candidates at all (every candidate the intersection nominated survived the predicate, so the
trigram arm never pays for a rejected one), it is all-ASCII where verification runs ~2× slower per
candidate on decomposed Czech, and its trigram dictionary saturates at ~1 237 keys, making candidate
sets tighter than real text would. Sizing one of them: 30 % false candidates on production text raises
the trigram arm's verification cost ~30 % and moves the crossover from 9.5 % to roughly 7 %, below 12's
own 8.33 % boundary. The cost of choosing 12 rather than 10 is the forfeited 8.33–10 % band, which wins
1.28–1.53× at n = 10 000 and runs 0.95–1.10× at n = 100 000 — the band where the win was smallest
anyway. That asymmetry is the constant's own argument, used in the direction its author did not: a
forfeited 1.3× is invisible, an introduced 1.3× regression is a bug report.

**And not from `1 / mean f*`.** `f* = share × speedup` is the break-even share recoverable from every
cell; seven classes over a 25-fold width range agree on it within ±15 %, which is what makes the model
believable. Its **mean** is not what a threshold is set from, because `f*` is not constant across width
— at n = 100 000 it rises monotonically from 0.082 at the narrow end to 0.110 at 25 %, so the narrow
classes drag the mean down and those are exactly the cells the gate never has to adjudicate.
`1 / mean f*` gives 11.0; the curve where it crosses 1 gives 10.5. The general form of the lesson is
worth more than the constant: **when picking a threshold off a curve, use the curve where it crosses,
not its average over a range you chose.**

**One explanation the data killed, recorded because it is the first one anyone reaches for.** The
depressed `f*` at narrow widths looks like a fixed per-query overhead — planning, session, response
assembly — which would make trigram cost `A + share·n·c_t` with `A > 0`. Fitting a line through the
cells gives a slightly **negative** intercept, so there is no such floor; the curve is mildly
*super-linear* instead, and the cost of serving one unit of share rises ~10 % from the narrow end to
25 %, saturating past ~8 %. The distinction points opposite ways: a fixed overhead would mean narrow
patterns are under-served and the gate could be looser, whereas super-linearity means wide ones are
dearer than linear costing suggests and the gate should be tighter.

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Embed Lucene as the fulltext engine | The three integration shapes fail on correctness (top-K), on performance (full enumeration) or on duplicated query semantics (filters in Lucene); plus four permanent integration taxes that duplicate subsystems evitaDB already owns | evitaDB stops needing the full match set, or a fourth integration shape appears that keeps both WAND and the complete result set |
| Fulltext in an external engine (Sage/Solr, Elasticsearch) as the runtime | Fulltext, facets, prices and hierarchy cannot be composed in one query across two systems, and the external service lands on the critical path of every search | Kept deliberately as the **fallback**: if the gate fails, this is today's state and the retreat position |
| Document BM25 with maintained corpus statistics | Requires distributed statistics to be comparable — ES and OpenSearch built that phase and leave it **off by default** because it costs an extra network round per query; and it makes the score non-deterministic across replicas | Quality on the CMS profile demands it — then BM25F is a scoring-function switch over the same structures (IDF from bitmap cardinality at query time), planned as phase F3 |
| Index term positions for phrases and proximity | The largest single cost in the index, for a signal that a top-K re-rank recovers; `match_only_text` in ES/OpenSearch ships exactly this trade-off | P4 measures the re-rank as insufficient for the CMS profile, or highlighting turns out to require positions |
| Copy the text of referenced entities into the referring document (the market's answer to interlinked content) | Multiplies data volume and turns every edit of a shared content block into a rewrite of many documents — it is what the current e-shop layer does and what hurts | Never as the primary route; index-time expansion of the *postings* (a few bytes per word–page pair) or query-time translation through the reference index are the two candidates |
| Full inline definition of the rank profile in every query (the Elasticsearch shape) | Elasticsearch is the only one of the five that sends the scoring function in every query, and the only one where ranking logic systematically settles in client applications — an empirical argument, not a preference | Never as the *main* route; the inline form nonetheless stays a fully fledged notation for tuning in the query console, because a profile has to be extractable from a tuned query |
| A dedicated `session.suggest(...)` method | Does not escape the must-match filter (a suggestion has to be intersected with it), forces two round trips per keystroke for a rich dropdown, and no engine has one at API level | P3 measures the query-pipeline overhead as fatal against the 5 ms budget — then it is added *beside* the require form as an optimisation, never instead of it |
| `float[]` added to `EvitaDataTypes` for embeddings | Breaches the principle that keeps floating-point out of indexes wholesale, not just for vectors; a dedicated `VectorEmbedding` type without `Comparable` cannot be marked filterable/sortable and skips the eight-layer schema-change cost | The vector branch needs values that must also be filterable or sortable in their own right |
| A string mini-grammar for match strictness (`"3<90%"`, Lucene syntax in the query argument) | The engine parsing its own grammar out of a user-supplied string is exactly how the old solution leaked Lucene syntax into a public HTTP API, complete with its failure modes | Never; a typed shape (enumeration or child constraints) carries the same expressiveness |
| **P8** — persist the trigram postings as paged leaf pages, at any granularity (leaf page / one record per key / hash shards) | Measured, not argued: all three rewrite *whole postings*, and the postings a write touches are the big ones — a touched key carries **30.7×** the bytes of an average key. One new `article/title/cs` value touches ~86 keys and rewrites **~4.8 MB** at the per-key floor, **4 285×** what a delta journal would append; at the current 512-key block, **94.1 % of every byte written is a bystander**, and no block size ≥ 8 comes within 2× of the floor. Granularity only sets the constant above a floor that is a fixed *share* of the whole index and grows with the corpus | The real fork is whole-posting rewrite versus delta journal, not granularity — so revisit only with a journal design, never with a different block size |
| **P8** — a delta journal for the postings | Not rejected on its numbers (it is the only scale-free row in the write-amplification table, flat at ~1.1 kB per new value at every batch size) but on what it would be: a second write-ahead structure inside an engine that already treats a flush failure between durability and merge as unrecoverable-by-retry, whose central parameter would be compaction cadence, because folding a journal into a base snapshot is a whole-index rewrite by construction. The bulk rebuild then removed the reason to persist anything at all | Catalog open becomes rebuild-dominated again — a much wider opt-in attribute set, or many collections carrying one — and the ~4 s bulk figure stops being affordable |
| **P8** — a flat open-addressing `long[]`/`Object[]` posting table | This is the spike's own §35.2 winner (1.1–1.6 ns/lookup against 4.5–28 ns boxed, 40–60× against binary search) and it still lost: an immutable published table clones both spine arrays on every commit that touches one posting, and the probe advantage is worth under a microsecond on a query whose verification phase runs tens to hundreds of microseconds | Never for the persisted/transactional structure. A flat table remains right for a **rebuilt-on-load, read-only** derived cache, which is what the spike actually measured |
| **P8** — Shape Q for reduced-index plans (cross into the reduced tree carrying values, probe by value) | **Deferred, not rejected.** It has a real per-index cost advantage — the probes are cheap exactly where Shape P's operand is largest — and an exact precedent in `AttributeInSetTranslator`. But it needs a new `InvertedIndex` accessor, a second typed memo (`computeOnlyOnce` stores `Formula`; Q's artefact is `Serializable[]`) and a hand-seeded staleness token set, which is a lot of new surface for a measurement nobody has taken. The fact that makes it *possible*, and that a re-proposal should not re-derive: a reduced index's normalizer and comparator are provably identical to the global tree's, because both are pure functions of the attribute schema and the `AttributeIndexKey`, and the normalizer is idempotent — so values recovered from the global tree probe into a reduced one with no conversion | Shape P is measured on a real fan-out and the per-index AND shows up as the dominant term. Shape P is the baseline Q has to beat |
| **P8** — defer the fold and hand back a `DeferredFormula` | Measured worth ~0 % as shipped and 5–11 % at best in the other direction, because the expensive half is spent before any formula exists. The deciding argument is therefore simplicity, not cacheability — and the ceiling on what deferring could save is the OR itself (10–17 % of the path on wide patterns, 39–44 % on rare ones), reached only when a formula is built and then never computed | The `probe`/`isWorthResolving`/`resolve` split lands, at which point deferring and memoising become the same question and the memo answers it better |
| **P8** — a shaped gate, `w ≤ n / (D₀ + D₁·log n)` | More parameters than there are measured points, fitted around one unexplained knee — and the constant's own comment already names the planner's cost model as its replacement, so a shaped gate would be a second crude stand-in built on top of the first | The n = 1 000 000 run shows `f*` still falling rather than plateauing: a constant that has to move with n is not a constant, and *then* the answer is a shape, not another scalar |
| **P8** — `Long.hashCode` as a shard function over packed trigram keys | It discards code points 2 and 3 entirely whenever cp2 < 2048 — i.e. all Latin, Greek, Cyrillic and every NFD combining mark — so the shard *is* the first code point: S = 256 and S = 1024 come out byte-identical, and it scored worse than every other option measured | Never with `Long.hashCode`. A MurmurHash3-mixed variant behaves sanely but is then just coarser key granularity with no upside and no ordered-key locality |
| **P8** — refuse a capability *withdrawal* at the schema boundary, or clean the value-id column up live | The refusal reverses two written decisions and would leave users no way to switch an index off without deleting their data; the live cleanup is blocked outright, because `removeValueIdMinter` refuses to run inside a transaction on a populated tree. What shipped instead makes the lockstep invariant unconditional at the index level | A reindexing story exists at all — the same prerequisite that gates `searchable()`. The orphaned id column is a memory cost, not a correctness one; see *Consequences* |

**If the persisted form is ever re-proposed, four properties of the existing paged-index machinery decide
its shape and none of them is visible from the outside.** Nothing bounds a leaf page by *bytes* — the only
knob is a key count, and a page of trigram postings spans six orders of magnitude of payload. A leaf split
writes **both** halves fresh, so bulk indexing is dominated by splits. `publishPreviousFlush` is
load-bearing: skip it and a warm-up flush leaves overlapping leaf pages at cold load, silently, which is
what `StaleLeafPageTwinWriterReproductionTest` exists for. And a page whose value is mutated *in place*
reads CLEAN and is never rewritten — a wrong-answer-after-restart bug that `markDirty(long)` exists to
prevent. One further gap is structural rather than a hazard: `LeafPageHandle` exposes values but never
keys, and every existing paged family gets away with it because its key is inside its value — a posting
carries no key, so a long-keyed accessor or a boxed `(trigram, posting)` entry has to be decided before
any page builder is written.

Three more of the same kind, expensive to rediscover and cheap to write down. **Removals are
mandatory**: the append-only OffsetIndex never reclaims a record that is neither superseded nor
explicitly removed, and page ids are never re-keyed, so a page left unremoved is copied forward by every
compaction forever. **`TrappedChanges` does not de-duplicate**, which makes emission order load-bearing
rather than incidental — pages, then freed-page removals, then the root, with collapse-path removals
emitted *before* `forgetPageStream`. And **the KeyCompressor entry is per stream** — per (entity index,
attribute, locale, type) — never per page and never per key, which is precisely why a per-key stream
design would be catastrophic while a per-sub-index one is free; that asymmetry is invisible unless you
already know where the compressor's entries come from.

## Key technical details

Shallow pointers only — the depth is in the supporting files.

### The fulltext core — not implemented

- **Three new structures, one of them genuinely new.** The term dictionary is an arrangement of
  (field, term) pairs over the existing `TransactionalBucketBPlusTree`; the postings are
  `TransactionalBitmap`; the impact sidecar — one byte per (field, term, document) — is the only
  entirely new data structure. Everything else (storage, WAL, transactions, filtering, facets,
  replication) already exists.
- **Postings are per (field, term), not per term.** This is what makes per-query field weights
  possible at all, and it is the direct answer to the flat blob of the existing solution. It also
  means the filter formula has to know the effective weights **at build time** so it can collapse
  per-field impacts into one weighted value in a single pass — hence the plumbing requirement below.
- **The filter planner reads `orderBy`.** `QueryPlanner.createFilterFormula` runs before
  `createSorter`, but the whole query is on the request from the start; the precedent is
  `FilterByVisitor.isHistogramSideOutputApplicable()`, which already reads a flag derived from the
  `require` part. The effective field weights must be mixed into the formula's
  `includeAdditionalHash()`, or the cache will serve a result computed under different weights.
- **A silent-failure trap worth knowing before writing the code.** `FormulaFinder` in
  `LookUp.SHALLOW` mode does not descend into a node it has already matched, and with prefetch on,
  the engine wraps the filter tree in `SelectionFormula` / `EntityFilteringFormula`. The fulltext
  score accessor must therefore be implemented by **those wrappers too**, exactly as
  `FilteredPriceRecordAccessor` is. Without it, `relevance()` degrades to `NoSorter` precisely when
  the planner chooses prefetch — i.e. on small results and small test datasets, where nobody
  notices until production.
- **`orderBy` is a chain of substitutes, not a lexicographic sort.** The second order constraint
  applies only to entities the first could not sort *at all*. Multi-criteria relevance therefore has
  to be one 64-bit value; there is no other way to express "on a tie, continue with the next
  criterion". This is why the cascade is packed rather than chained.
- **Sorting cost is not in the research's budget.** There is no top-N selection anywhere in the
  `sort` package — `FilteredPricesSorter` performs a full `Arrays.sort` over all matches and slices
  the page afterwards. With full-set scoring (85–95 % of the corpus is a candidate) `relevance()`
  inherits O(N log N) per page. Partial selection to `offset + limit` would be a new precedent, not
  a following of one.
- **Reindexing does not exist and a schema change passes silently.** Switching an indexing flag on
  over existing data leaves the index empty and nothing back-fills it;
  `SetAttributeSchemaFilterableMutation` has no use in `evita_engine`. Fulltext did not cause this,
  but it is the first feature likely to hit it, so the minimum before `searchable()` reaches users
  is to **refuse** a change the engine cannot perform over a non-empty collection rather than accept
  it quietly. **P8 shipped that refusal for its own capability** and is the worked example — see
  below.
- **The fulltext structures must be confined to the global index deliberately.** `AttributeIndex`
  lives on the common `EntityIndex` ancestor, not on `GlobalEntityIndex`, so reduced indexes have it
  too; the restriction is enforced the way `ReferencedTypeEntityIndex` does it for the sort
  structure — by overriding the method with an empty body and a comment saying why.

### The trigram substring index — shipped

- **Entry points.** `io.evitadb.index.trigram` holds the whole structure: `TrigramCodec` (63-bit
  packing of three NFD code points), `TrigramPostings` (the hybrid), `TrigramPostingStore` (the
  `TransactionalLongBPlusTree` wrapper), `TrigramIndex` (the index and its `ValueLifecycleSink`
  implementation), `TrigramPostingAccumulator` (the bulk load-path build) and
  `TrigramSubstringSearch` (the query-side pipeline and its gate). It is hosted on
  `GlobalEntityIndex` through `TrigramIndexMapComponent`; the query side enters from
  `AbstractAttributeStringSearchTranslator`, whose `isServedByTrigramIndex()` defaults to `false` and
  is overridden `true` by the `contains` and `endsWith` translators only — `attributeStartsWith`
  extends the same base, inherits the default, and keeps its existing anchored fast path untouched.
- **The storage surface is exactly zero, and that is a decision, not an omission.**
  `TrigramIndexMapComponent` emits no storage part and announces no manifest key; the index is
  re-derived by the finalizer of `GlobalEntityIndex.reloadPlan()` from the already-loaded shared
  value trees, and `EntityIndexReloadPlanSymmetryTest` carries a stated mapping exception for it. So
  there is no `serialVersionUID`, no serializer, no Kryo BWC surface and no migration on this index —
  the value-id column on the shared value tree is the only thing P8 added to disk.
- **A posting is never mutated in place.** The tree versions the `trigram → posting` *mapping* and
  restores **references** on a savepoint rollback; it has no idea what a posting's content is. An
  in-place mutation would therefore be seen by every older index version that still shares the
  instance, and a rollback would put the reference back with the content already changed. The bitmap
  arm clones first; a mutation that changes nothing returns the very instance it was given, so the
  write path's common no-op allocates nothing.
- **A rebuild failure fails the catalog load, deliberately.** `TrigramIndex.rebuildAll` has no
  `catch` anywhere in it: a tree it cannot use means the persisted state and the schema disagree, and
  skipping it would open the catalog with the accelerator silently missing, after which every
  substring query on that attribute quietly matches fewer entities than it should.
- **Churn on an existing value costs zero trigram writes.** Births and deaths are detected from the
  tree's own transaction-aware bucket count, so a record joining or leaving a value that survives
  notifies nothing and descends nothing. This is the property the whole update model rests on.
- **The trigram path is confined to entity-level attributes by an explicit `referenceSchema == null`
  guard.** The confinement is unreachable today — three independent guards close it, including a
  schema refusal documented as liftable — but the `instanceof GlobalEntityIndex` check that used to
  close it was removed by the reduced-index work. Global reference-attribute postings would mean "on
  *some* reference of type R" where a reduced plan means one specific `R:k`, so the day the schema
  restriction lifts, this must fail loudly rather than compose over-broad answers.
- **The hoisted operand is resolved from the `GlobalEntityIndex` directly, never cloned out of the
  plan's own formula tree.** The planner may have selected a reduced target set, in which case the
  clone's leaves express a narrower primary-key universe than the composition assumes — the same trap
  `ExtraResultPlanningVisitor#canUseShortcut` documents for its own operand. Resolving the global index
  by identity makes the universe the whole collection by construction.
- **A withdrawn capability could reach an ordinary `upsertEntity` before it was fixed, from a pure
  public-API sequence**: withdraw `SUBSTRING` → delete every entity (the shared value tree is dropped,
  the trigram entry survives) → re-declare the capability, now legal because the collection is empty →
  upsert, at which point the stale entry skips the consumer attach and the write fails. Neither the
  calibrated guards nor the broad suite caught it. The fix makes the lockstep invariant unconditional
  rather than guarding the sequence: the entry is dropped on the sinkless branch of all four intercepted
  write primitives, gated on emptiness so the no-capability majority pays one boolean.
- **A third composition is available-looking and silently wrong**: handing the *reduced* tree the
  *global* candidate value ids. A reduced index mints no value ids at all
  (`InvertedIndex#getRecordsOfValueIdsMatching` returns an empty result, `getValueById` returns
  `null`), so that shape compiles, returns empty, and passes any test whose fixture is small enough.
  The note lives at the crossing site.
- **Both paths hash identically, on purpose.** The accelerated formula and the scan's produce equal
  `getHash()` for the same answer, because both fold the same bucket record sets and the bitmap ids
  are sorted before hashing — so the two paths share one cache entry instead of fragmenting it. That
  is a consequence of *eager* selection: the formula is content-addressed, which is why no
  text/kind discriminator is needed. Deferring would hash the question instead and make one
  mandatory.
- **Test fixtures derive their sizes from `accelerationThreshold(...)`, never hard-code them.** Both
  suites compare an accelerated path against a scan that agrees with it by construction, so a corpus
  that stops clearing the gate does not fail — it silently compares the scan against itself. Three
  fixtures were found that would have gone vacuous inside the range the retune was drawn from, the
  worst of them destroying the disjointness that makes a cross-scope defect observable at all. A
  dedicated assertion now reddens first, naming what to raise.

## Verification

### The fulltext core — the gate, still a plan

**Nothing of the fulltext core is implemented, so there is nothing to verify yet.** The gate is the
verification plan, and it is deliberately numeric so that the answer is not a matter of opinion:

| Prototype | What it proves | Criterion |
|---|---|---|
| **P5 — analysers** | Lucene analysis as a dependency; Czech support; coexistence with today's NFD normalisation | today's `attributeContains` behaviour unchanged; cs/en quality |
| **P1 — index core** | dictionary, postings, impact sidecar, scorer | RAM ≤ 150 MB per 1M products and language; phase-1 scan ≤ 25 ms / 1M candidates |
| **P2 — write path** | index maintenance while replaying a production WAL | commit throughput drop ≤ 10 % |

Quality at the gate is **not** measured against our own yardstick: it is a side-by-side comparison
against a Solr baseline through Sage's existing golden-set harness. For the CMS profile there is a
second, cheaper option already in production — the Atlas checks `SearchResultCheck` and
`SearchResultFromCsvCheck`, whose CSV files are real customer queries with real expected URLs and a
percentage accuracy threshold. All criteria are measured on **both** usage profiles: the e-commerce
catalogue (short fields, millions of items) and CMS content (long texts, tens to hundreds of
thousands of documents).

Later prototypes carry their own numbers: P3 suggester p99 ≤ 5 ms per keystroke including typo
expansion; P4 re-rank of the top 1000 ≤ 10 ms; P6 vector spike recall@10 ≥ 0.95 and < 10 ms / 1M as
its own mini-gate.

### P8 — measured, not planned

The spike's own gate (2026-08-25) passed on all four criteria on a production CMS corpus and the demo corpus — latency
against the actual engine scan rather than a re-implementation, memory against the analyzer-confirmed
budget; the numbers are in [`prototypes/p8-trigram-substring-index.md`](prototypes/p8-trigram-substring-index.md)
§35.1 and are not repeated here, with one exception: the **per-attribute** memory table, which is
carried below rather than left in the spike's working notes, because the aggregate cannot substitute
for it.

**Memory, per attribute — the number that decides what to flag.** Measured on a production CMS catalog (972 611
articles, a production CMS corpus), variant B (`trigram → valueId`), heap by JOL deep-retained walk
against an empty structure:

| attribute | N/V | heap | serialized | A/B serialized | verdict |
|---|---|---|---|---|---|
| `article.title` (cs) | 1.03 | **158.8 MB** | 114.3 MB | 1.02× | the attribute one would actually flag |
| `article.keywords` | 17.5 | 21.1 MB | 8.2 MB | **8.27×** | valueId compression works |
| `article.authors` | 134 | 2.6 MB | 0.4 MB | **20.99×** | valueId compression shines |
| `article.url` / `.path` | ~1.0 | ~160 MB each | ~131 MB each | 1.01× | expensive — do not flag |
| `article.contentHash` | 1.0 | 138.7 MB | 120.3 MB | 1.00× | a hex hash; never flag |
| `article.externalId` | 1.0 | 92.6 MB | 45.9 MB | 1.00× | a structured id — `startsWith` territory |
| category / section names | ~1.0 | ~9 MB total | — | ~1.00× | cheap |

The realistic opt-in set (`title` + `keywords` + `authors` + category and section names) is **~184 MB
heap**, and ~25 MB without `title`; flagging everything would be **743 MB**, almost all of it wasted on
hashes, ids and URLs. That contrast *is* the argument for a per-attribute capability, and this table is
what a user needs to make the choice — the aggregate on its own does not say which attribute is which.

Four things must travel with these figures:

- **They are one catalog's shape, not a universal ratio.** The same measurement on two e-commerce
  corpora totals 10.5 MB and 2.4 MB serialized, at very different `N/V`.
- **`A/B` is bounded by `N/V` sub-linearly and is never equal to it** — 134 → 21×, 17.5 → 8.3×, 8.1 →
  4.0×, ~1.0 → 1.00–1.14×. Any cost model predicting the ratio from `N/V` alone is wrong in both
  directions; the residual advantage at `N/V ≈ 1` comes from dense value ids producing fewer Roaring
  containers than sparse entity primary keys.
- **Heap is 1.1×–3.2× serialized** depending on container density, so a budget stated in serialized
  bytes understates by up to three times. State budgets in heap. The JOL figures also assume the
  compressed-oops regime, which flips above a 32 GB heap (+~9 % object overhead).
- **They calibrate P1's own estimates**, which is the forward-looking reason to keep them: P1's gate
  criterion is RAM ≤ 150 MB per 1M products and language, and this is the only measurement of
  comparable structures on a real corpus at that scale.

**End-to-end query latency.** `SubstringQueryBenchmark` in `evita_performance_tests`: a real embedded
Evita, formula cache disabled, `Mode.AverageTime` in µs/op, `@Threads(1)`, `-f 3 -wi 5 -w 2s -i 5 -r 2s`
on a quiet box. Two arms over the identical corpus — TRIGRAM declares `FilterIndexCapability.SUBSTRING`,
SCAN declares plain `filterable()`. The corpus is all-distinct (so `entityCount == distinctValueCount`,
the unit both gate constants are expressed in), the attribute is a non-localized ASCII `String`, the
query is a plain `attributeContains` with `page(1, 20)`, every answer is checked against a
corpus-derived oracle, and every TRIGRAM cell prints its exact two-sided posting width and its
`accelerated` flag — so no losing cell can be dismissed as a silent decline. The width bisect,
28 cells in one invocation:

| posting width | n = 10 000 | n = 100 000 |
|---|---|---|
| 1 % | 12.26× faster | 8.29× faster |
| 2 % | 5.60× faster | 4.16× faster |
| 4 % | 3.61× faster | 2.05× faster |
| 8 % | 1.60× faster | 1.15× faster |
| 12 % | 1.06× (tie) | **1.34× slower** |
| 15 % | 1.21× slower | 1.52× slower |
| 25 % | 1.80× slower | 2.27× slower |

Monotone in width at both sizes, sign change between 8 % and 12 %, crossover interpolated at **9.52 %
at n = 100 000**. **The n = 100 000 column is the load-bearing one and the two columns must not be
averaged**: at n = 10 000 the SCAN arm — which should be near-constant across pattern classes — scatters
by 41 % with confidence intervals up to ±21 %, against 8–11 % and monotone at n = 100 000.

`MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT = 256` was priced by the same sweep and kept: at 100 distinct
values every pattern class with a non-empty candidate set ties within its confidence interval — the gate
declines and the scan of a single contiguous leaf block is unbeatable, exactly as the constant claims —
while at 256 the winning classes are already 2.58–2.84× ahead. The floor sits where the wins begin. A
pattern the index proves absent wins even at 100, which does not contradict it: that answer is returned
before the gate is consulted.

The earlier five-class matrix, taken at the shipped `D = 4` on a slightly lighter corpus (so its
absolute scores must not be spliced onto the bisect's), is what condemned that constant and shows the
shape of the win: at n = 100 000 a ~1 % pattern is **9.35×** faster, a handful-of-values pattern
**730×**, and a pattern the index proves absent **1 674×** — while the 15 % and 25 % cells, both
admitted by `D = 4` and both confirmed `accelerated=true`, ran **1.52×** and **2.10×** slower. At
`D = 12` those two decline and take the scan.

**The losing cells genuinely took the accelerated path — checked, not assumed.** This table exists to
refute the one objection that would make the whole retune meaningless: that a "slower" TRIGRAM arm had
quietly declined and run the scan under a trigram label, leaving the comparison measuring nothing. The
fixture prints each cell's exact two-sided posting width and its `accelerated` flag, and for every
losing cell it reads `true`. Ratios are the five-class matrix's, so the column is one run:

| pattern class | n | posting width | width / n | `accelerated` | result |
|---|---|---|---|---|---|
| `COMMON` | 256 | 38 | 14.8 % | **true** | 1.24× slower |
| `COMMON` | 10 000 | 1 500 | 15.0 % | **true** | 1.14× slower |
| `COMMON` | 100 000 | 15 000 | 15.0 % | **true** | 1.52× slower |
| `THRESHOLD` | 256 | 64 | 25.0 % | **true** | 1.61× slower |
| `THRESHOLD` | 10 000 | 2 500 | 25.0 % | **true** | 1.79× slower |
| `THRESHOLD` | 100 000 | 25 000 | 25.0 % | **true** | 2.10× slower |

The bisect confirms it independently on its own corpus: **all fourteen** of its TRIGRAM cells reported
`accelerated=true` at exact widths, including the sign-change cell that decided the retune — 12 %
width, 12 000 postings over 100 000 values, 1.34× slower.

The same flag confirms the floor from the other side: every `n = 100` cell with a non-empty candidate
set reports `accelerated=false`, which is the distinct-value floor declining exactly as it should.

**The formula cache does not earn the eager fold.** `SubstringCacheRepeatBenchmark`, cache ENABLED at
shipped settings, 15 cells (5 pattern classes × n ∈ {1 000, 10 000, 100 000}): `admitted=false` in **all
fifteen**. The counterfactual proves that is a refusal and not a broken probe — with both admission
floors at zero the same 15 cells admit in **eleven**, one of them logging 13 715 interval hits against
0 misses on a 514-byte record. Comparing that floors-removed arm against the shipped DISABLED arm — the
honest measure of what caching is worth when it does happen — the benefit is **1.05–1.11×**, and on the
two widest pattern classes at n = 100 000 it is **0.98–1.00×**, i.e. nothing. The ceiling on
cacheability is the fold, not the query.

**What the fold itself costs** — `SubstringEagerFoldBenchmark`, `matchAndFold` minus `matchOnly`, which
is the *ceiling* on what deferring could have saved and is reached only when a formula is built and then
never computed: 10.5–16.6 % on 15 % patterns, 11.7–17.3 % on 25 % patterns, 39–44 % on rare ones. The
two largest absolute folds (1.5 ms and 2.6 ms, both at n = 100 000) belong to the cells `D = 12` no
longer admits at all.

**Catalog load.** On the real `article/title/cs` of a production CMS catalog (V = 943 410 distinct values,
K = 62 079 trigram keys, E = 61.7 M memberships), driven through the production structures with values
arriving in bucket-cursor order as the load path actually delivers them: the incremental rebuild loop
costs **~77 s**, the ordered-append bulk build **4.0 s** — ~19× — and the two indexes were compared
member-for-member across all 62 079 keys before either was timed. Both figures are "accumulate and
build": the bucket-cursor walk that feeds them, ~0.1 s for 943 410 values, is excluded from each. The
catalog paid that figure **twice** (~154 s) because the global index was deserialized once serially and
again inside the async task, with
the second copy discarded; removing the second read is the other half of the win. Two side effects worth
knowing: the bulk-built store is **118 MB against the write path's 151 MB** (22 % smaller, because the
writer materializes each container once at its exact size), and the build holds a transient
`4 bytes × memberships` — **247 MB** on that attribute — that the incremental path never allocated at
once. The finalize strategy was chosen by measurement too: `bitmapOfUnordered` + `removeRunCompression`
at 4.0 s / 118 MB against sorting every buffer into `fromArray` at 6.6 s / 133 MB.

**Write amplification, counted rather than timed.** An exhaustive census over every holdout value of the
same attribute: one new value touches ~86 trigram keys and rewrites **4.77 MB** at the per-key floor —
4.3 % of the whole index — against **1 113 B** for a delta journal, a ratio of **4 285×**. By leaf-page
block size the ratio to that floor runs 512 → 16.91×, 128 → 9.38×, 32 → 4.73×, 8 → 2.23×, 1 → 1.000002
(the last of which arithmetically validates the harness). At the current 512, **94.1 % of every byte
written is a bystander**. `url` behaves the same within ~5 % at every step. The floor should be quoted
as ~3.5–4.8 MB rather than sharply, because the holdout skews long.

**Correctness.** Every increment's guards were proven red by counterfactual before being trusted: 24 of
24 across the index structure and its maintenance, 8 across the query path and the value-id directory,
and all nine of the reduced-index counterfactuals (gate priced against the global tree, no early exit,
price one partition, un-hoist, one operand for all scopes, drop the intersection, drop the confinement,
off-by-one threshold, AND the global plan too) reddened exactly the cases predicted for them, with 44
tests running each time at the shipped divisor. Broad regression sweeps
`-Dgroups='(indexing | storage) & !slow & !flaky'` ended at **6 382 tests, 0 failures, 0 errors, 27
skipped**, and one full `unitAndFunctional` sweep at **22 113 tests**, whose two failures were a
pre-existing `_internalBuild` arity defect introduced by the value-id increment — Java inherits statics,
so stale 10-argument calls rebound silently to the parent overload and the mistake could not produce a
compile error — fixed later on the same branch; its three errors were environmental (no Docker, plus two
data-set setup failures under a 22 k-test parallel run).

**Two negative results, kept because they cost real time and will otherwise be re-derived.**

- **A test can be decorative for six green runs.** The reduced-index functional test declared its
  reference with `indexedInScope`, which `SetReferenceSchemaIndexedMutation` maps to `FOR_FILTERING` —
  not `FOR_FILTERING_AND_PARTITIONING`, as a neighbouring test's JavaDoc prose had suggested. Under
  `FOR_FILTERING` the entity-level attribute is never fanned into a reduced index and the planner never
  builds the reduced candidate, so the test passed on the global plan alone. A throwing probe in the
  reduced branch counted **0** hits before the fix and **5** after. The ordering that would have caught
  it: the code that *assigns* the value beats a signature, a signature beats the JavaDoc on the thing
  itself, and that beats prose in another file which merely mentions it.
- **The value-id directory's publication fix has no test, on evidence rather than on assertion.** A
  stress harness ran 10.5 M reads against the pre-fix three-non-volatile-field shape over ~15 000
  rebuilds and produced **no failure**, because `valueOf` validates every hit against the slot it lands
  on, so any mixture of two generations — or a torn read — resolves to `null`, which is also what a
  consistent read of a stale generation returns. The two shapes are observationally equivalent through
  the public surface. What the fix buys is *safe publication* of a freshly built map, which does not
  manifest on x86; the deterministic guard is the rebuild's defensive copy instead.

## Consequences & open follow-ups

**What this enables.** Fulltext, facets, prices and hierarchy in one query over one snapshot; a
deterministic, explainable order that reproduces on every replica; ranking freshness (boost tables,
synonyms, entity dictionaries) without a reindex, because in the bitmap model boosts never touch the
write path; and a closed learning loop with Sage, which exports features from the same snapshot the
query ran over.

**What it costs.** A permanent maintenance commitment for in-house structures, without Lucene's
community. The calibration is in the repository already — the vendored RoaringBitmap fork is
experience of the "manageable, we have a process, but it is not free" kind.

**What P8 delivered, and what it costs.** `attributeContains` and `attributeEndsWith` stop being a
full scan on attributes that opt in, and the four "shared hard parts" the ordering decision named are
now worked examples rather than open questions: a scoped schema capability that refuses the change the
engine cannot perform, an MVCC-safe persisted id allocator on the shared value tree, a new index
component on the global index — which, in the event, needed no persisted form at all, so that part of
the path-finding came back as a *negative* answer worth having — and the formula-cache contract for an
accelerator whose work happens during translation. The price is per-attribute memory: the realistic production CMS
opt-in set is ~184 MB heap on 972 K articles, +25 % over that catalog's existing attribute-index heap,
against 743 MB if everything were flagged. That is why the capability is per attribute, and why the
brief's census names the attributes never to flag (hashes, structured ids, URLs). The gate constant is
a hand-set stand-in and is known to be one; the planner's cost model is what replaces it.

### Open items — the fulltext core

- **Semantic search is a phase dependency, not a gap.** Today's Lucene client has kNN over a
  `vector` field, document and query embeddings, and a query-vector cache, all deployed. For us it
  arrives with F2, and `float[]` is not a valid attribute type today. A customer using semantic
  search now needs either F2 finished or a temporary parallel run of both solutions.
- **The curated promo layer is uncovered and must be decided before the gate.** The existing
  solution pins documents by editor-supplied keywords and subtracts them from the main result so
  they do not appear twice. This is merchandising, not relevance — but nowhere is it said where the
  capability lives, and the answer changes the **shape of the response** (one result set, or two
  groups?) and thereby the query API, which is a breaking change once released.
- **Two small analysis filters have no counterpart in P5**: protecting an individual token from
  analysis inside a sentence (a per-attribute analyser choice is a coarser instrument, it protects
  a whole field), and splitting a token into its word and numeric parts for catalogue numbers.
- **Issue #12 (query profiles) is now a hard dependency**, not a neighbour. It has to be designed in
  depth and implemented concurrently with F1, and unification poses five questions to its design:
  runtime updates without a schema mutation, conditional application by `location`, per-item
  collision semantics, a collection default plus a reserved "no profile", and cache keys derived
  from the query *after* enrichment.
- **The constraint name must be settled before F1 ships** — `dataMatches` is recommended,
  `textMatches` a close second; renaming a shipped constraint is a breaking change in four APIs at
  once, and the `attribute` prefix stops being true the moment searchable associated data arrives,
  which is an entry condition for the CMS profile.
- **Whether a phrase is a filter or a boost** changes what P4 measures and has to be decided with
  it: an e-shop wants a boost, a CMS user searching for a specific formulation expects a filter.
- **Refusing a schema change the engine cannot perform** is the only genuinely blocking item of the
  reindexing story; the escape hatch with an expiry, the analyser fingerprint and documenting the
  way out (`replaceCatalog`) can follow in F1. P8 shipped the refusal for `SUBSTRING`, so the shape
  exists — but only for *adding* a capability to a non-empty collection. Withdrawal stays legal and
  is where the loose end is (below).
- **Diacritics removal is not NFD.** The existing client uses a hand-written code-point table with
  special cases (`ß→ss`, `æ→ae`); we build on evitaDB's NFD normalisation. Results agree in most
  cases but not all, and migrating an existing site means a change in search results that has to be
  flagged in advance. P5 §7 has to verify the produced terms.

### Open items — the trigram substring index

- **Split `TrigramSubstringSearch` on the seam its own class JavaDoc already describes** —
  `probe(...)` returning a small carrier or `null` for the two genuine pre-flight declines,
  `isWorthResolving(...)` carrying the gate including the provably-empty bypass, and `resolve(...)`
  producing the buckets, with the 4-argument `match` remaining their composition so consumers are
  untouched. The supplier then always returns a `Formula`, the decline-sentinel problem dissolves
  instead of being worked around, and the expensive half — 55–87 % of the path by the class's own
  measurement — moves inside the memo without dragging the gate in with it. Deliberately not folded
  into the reduced-index increment: it changes the public API of the one class the benchmark module
  links against, and that wants a compiler and a test run available.
- **Rebuild the substring functional fixture around `hierarchyWithin`.** The end-to-end reduced-index
  test cannot observe the intersection being deleted, and that is structural rather than a defect:
  `ReferenceHavingTranslator` does not consult `isTargetIndexRepresentingConstraint` — only the two
  hierarchy translators do — so a `referenceHaving` plan re-imposes the reference restriction itself
  and ANDs the answer back down to the queried partition. The intersection is the *sole* restriction
  only under a hierarchy plan. The unit suite pins that case today; the end-to-end suite cannot.
- **One run at n = 1 000 000 to settle whether a single scalar gate can be right at all.** It is the
  only experiment that separates *the degradation saturates, so a constant is fine past the knee* from
  *no scalar can be right across the range*: `1/f*` runs 7.1 → 7.5 → 10.0 across n = 256 / 10 000 /
  100 000, which is flat-then-worse — a working-set signature, not the tree-depth one a `log n`
  explanation predicts. The prediction is recorded in advance: if the working-set reading holds, `f*`
  plateaus near 0.09–0.10, so 8 % width still wins by ~1.15–1.25× and 12 % loses by ~1.2–1.35×, much as
  at n = 100 000. `f*` continuing down to ~0.07 falsifies it, and the answer is then a shaped gate
  rather than a different scalar. Not run because the fixture has never been built at that size and its
  heap requirement is an extrapolation; **the divisor is defensible either way**, which is why this is
  not urgent.
- **A withdrawn `SUBSTRING` capability orphans the value-id column.** `detachValueIdConsumer` has no
  production caller, so after a withdrawal plus a restart the tree carries ids with an empty consumer
  registry — its own gate invariant, violated permanently, since the restore path dirties nothing. This
  is memory, not correctness, and the JavaDoc says so rather than claiming the withdrawal stops costing
  anything. Whoever writes the cleanup must know that `detachValueIdConsumer` no-ops on a null
  registry, so a name-keyed sweep silently misses exactly these orphans. Attribute *removal*
  (`RemoveAttributeSchemaMutation`, which has no `evita_engine` caller) is the second route past the
  index-level reconciliation and was not traced.
- **`CacheOptions#minimalComplexityThreshold` gates twice, in two different dimensions.** It is passed
  to `CacheAnteroom` as a **cost** floor and to `CacheEden` as `minimalSpaceToPerformanceRatio`. A cost
  and a space-to-performance ratio are not the same dimension, and one configuration number is serving
  as the floor for both. Nothing in this line of work depends on resolving it; it surfaced while
  measuring why the substring formula is never admitted.
- **Substring queries are invisible to the formula cache's admission decision, and this predates the
  trigram index.** A folded substring answer is an `OrFormula` over already-computed bitmaps, so its
  declared cost is `14 × Σ(bitmap sizes)` — a function of the *result size alone*. The candidate
  resolution and per-candidate verification that produced it contribute nothing, because they ran
  during translation rather than during formula computation. **The scan path has the identical shape**
  (`getRecordsWhoseValuesContains` → `getRecordsMatchingFormula` → the same `toSortedOrFormula`), so
  this was already true before P8 and must not be read as something P8 introduced. Memoising the match
  — keyed on index identity, attribute key, normalised pattern and predicate kind — is where the
  remaining win on this path is; the seam split above is its per-query half.
- **The transient heap of the bulk rebuild is unmeasured.** It holds `4 bytes × memberships` (247 MB on
  the flagship attribute) that the incremental path never allocated at once, and that is the peak **per
  rebuild in flight**, not a whole-server peak — archived global indexes rebuild on pool threads
  concurrently across collections, so a heap sized from the single-index figure under-provisions.
- **The duplicate-load fix has no regression test.** Returning the already-read global index instead of
  reading it a second time is observationally identical; only the cost would come back, silently. A
  test would need a counting spy on the collection persistence service, which was judged
  disproportionate — but it means a future refactor can undo the win with nothing going red.
- **Distinct values per reduced index were never counted.** The design ruling deliberately made this
  informative rather than blocking, because the summed gate self-regulates — but it is still the "how
  often does this actually fire" number, and no such statistic exists in the repository. The Stage-0
  census tooling already walks every index; one bucket-count column would answer it.
- **An attribute whose values are all shorter than three code points builds an empty trigram index.**
  The census found a real one (`countryCode`: zero trigram keys over 45 K occurrences). Every query then
  falls back to the scan, correctly but having paid for an index that can never match — and nothing
  warns, because it cannot be detected at schema time, when there is no data yet. The natural home for a
  diagnostic is the capability-usage surface.
- **A second replication layer sits next to the one this work addressed, and is not P8's.** The
  replication census found that in reduced indexes **87–88 % of sort-family heap is owner-mode private
  value trees** — very nearly a full second copy of the values beside the filter trees (12.2 of 14.0 MB
  on one catalog, 25.8 of 29.3 MB on another). A deduplication that hoists only the filter side would
  leave roughly 38 % of the replicated value bytes untouched. Recorded here because the measurement was
  taken by this line of work and would otherwise be lost; it belongs to the value-duplication line the
  brief's §34 opens, not to the substring index.
- **User documentation was not written.** Nothing under `documentation/user/en/` mentions
  `filterable(FilterIndexCapability.SUBSTRING)` — not the schema pages, and not
  `query/filtering/string.md`, where `attributeContains` / `attributeEndsWith` are documented without
  any note that an opt-in accelerator now exists, that it is String-only, or that it is refused on a
  non-empty collection. English is the only hand-written source; the Czech mirror is
  machine-translated.

## Related work

- [`2026-07-07-roaring-bitmap-vendoring`](../2026-07-07-roaring-bitmap-vendoring.md) — the vendored
  RoaringBitmap fork is both the substrate the postings are built on and the calibration for what
  maintaining an in-house structure costs.
- [`2026-07-10-more-optimized-data-structures`](../2026-07-10-more-optimized-data-structures/) — the
  paged storage-part decomposition the fulltext structures inherit; it is why the format permits
  later partial loading without a format change.
- [`2026-08-01-bplustree-cursor-free-insert-path`](../2026-08-01-bplustree-cursor-free-insert-path.md)
  — the term dictionary is built on this B+ tree, so its insert-path invariants are ours too.
- [`2026-07-27-write-path-performance-tuning`](../2026-07-27-write-path-performance-tuning/) — its
  finding that a commit re-shells every reduced index (~179 K on the catalog it measured) is why the
  trigram index's value-id allocator is scoped per shared value tree rather than being one
  catalog-global hot point.

## Timeline

- **2026-08-12** — research v1 completed against the source of Meilisearch, Typesense, Vespa, Lucene
  and Solr; the query and schema designs drafted
- **2026-08-13** — Elasticsearch and OpenSearch checkouts added and the load-bearing claims
  re-verified against them; the two analyses of the existing Edee solution produced
- **2026-08-14** — sponsor review: five binding principles, unification of rank profiles with query
  profiles (#12), the suggest shape and adaptive relaxation decided
- **2026-08-24** — the research translated into English and consolidated into this record; the
  decision awaits approval of phase A
- **2026-08-24** — the trigram substring-index brief, originating from a separate discussion, verified
  against primary sources and against the codebase, and adopted as
  `prototypes/p8-trigram-substring-index.md`
- **2026-08-25** — the P8 spike measured on real corpora (a production CMS catalog, evita-demo-dataset): the performance
  gate passed on all criteria, the dictionary/positions/posting-representation/early-exit forks
  closed and the brief's falsified claims corrected — recorded as
  `prototypes/p8-trigram-substring-index.md` §35
- **2026-08-25** — P8 implementation opens on issue #1454, a sub-issue of #258: the `SUBSTRING` filter
  capability and its refusal on a non-empty collection, then the value-id column on the shared value
  tree
- **2026-08-26** — `TrigramIndex` lands as a component of `GlobalEntityIndex`; the posting store is
  migrated from a flat table onto `TransactionalLongBPlusTree` the same day
- **2026-08-27** — the persistence granularity spike measures the write amplification, the ruling
  reverses to "no persisted format" and P8 ships the bulk rebuild plus the duplicate-global-load fix
  instead
- **2026-08-29** — `attributeContains` / `attributeEndsWith` are wired to the accelerator; the
  end-to-end benchmark suite is written and the reduced-index Shape P lands
- **2026-08-30** — the width bisect pins the crossover, `CANDIDATE_SELECTIVITY_DIVISOR` is retuned
  4 → 12, and the eager/lazy fork is settled with its original reason retired; P8 complete on branch
  `1454-trigram-substring-index`

## Supporting material

- [`management-summary.md`](management-summary.md) — the decision basis written for management: what
  is being asked for, the investment, the risks and their cover. Read this first if you want the
  argument without the source-code anchors.
- [`research.md`](research.md) — the consolidated technical research (v2). Authoritative for the
  *architecture*: what gets computed, in what phases, and what the boundary between the engine, the
  client and Sage is. Every load-bearing claim carries a `file:line` anchor into a checkout.
- [`prototypes/p1-index-core.md`](prototypes/p1-index-core.md) — the index core prototype: the
  dictionary, the postings, the impact sidecar and the scorer, with the memory and latency criteria
  the gate is measured against.
- [`prototypes/p2-transactional-maintenance.md`](prototypes/p2-transactional-maintenance.md) — how the
  index is maintained on the write path inside a transaction, and the batch-maintenance fallback if
  the throughput criterion is missed.
- [`prototypes/p3-suggester.md`](prototypes/p3-suggester.md) — the suggester as a query over the term
  dictionary, and why a completion that yields no results in the user's context must never be
  offered.
- [`prototypes/p4-proximity-rerank.md`](prototypes/p4-proximity-rerank.md) — how multi-word queries
  are handled without indexed positions, by re-ranking the top-K.
- [`prototypes/p5-analyzers.md`](prototypes/p5-analyzers.md) — the analyser registry, the Czech chain,
  and the coexistence of the analysis chain with today's NFD normalisation.
- [`prototypes/p6-vector-spike.md`](prototypes/p6-vector-spike.md) — the vector branch as a separate
  decision with its own mini-gate: jVector as an embeddable library versus an in-house HNSW.
- [`prototypes/p7-rank-profiles-and-boost-channel.md`](prototypes/p7-rank-profiles-and-boost-channel.md)
  — rank profiles, the boost channel, feature export, and the curated layer's placement.
- [`prototypes/query-design.md`](prototypes/query-design.md) — the shape of the query language and
  the planning seam: which constraints appear, where the score flows between phases, and the four
  places that can be got wrong. The eighteen open questions Q1–Q18 live at its end.
- [`prototypes/schema-design.md`](prototypes/schema-design.md) — what the schema has to know about
  fulltext, the three API shapes with a recommendation, and the reindexing problem in full. Its
  thirteen open questions S1–S13 live at its end.
- [`prototypes/bitmap-memory-optimizations.md`](prototypes/bitmap-memory-optimizations.md) — the
  memory analysis of the bitmap structures underlying the RAM estimate.
- [`prototypes/p8-trigram-substring-index.md`](prototypes/p8-trigram-substring-index.md) — a related
  spike adopted from a separate discussion: accelerating literal `attributeContains` /
  `attributeEndsWith` with a `trigram → distinct-value-id` index. It shares the substrate, the memory
  risks, the measurement harness and the valueId open question with P1/P2 and the bitmap memory
  analysis; its §32 corrects its assumptions against the code, its §33 maps the convergence, its §19
  ties issue [#545](https://github.com/FgForrest/evitaDB/issues/545) (case-insensitive attributes) to
  the shared normalization contract, and its §34 records why the valueId is also the key to the
  reduced-index value duplication. Its §35 holds the 2026-08-25 spike measurements: the gate verdict,
  the closed forks, and the corrections to the brief's own claims. **It is now a historical document**:
  the design it proposes shipped, and its status header records what the implementation confirmed, what
  it reversed, and which of its open items are closed. Read that header before treating anything in
  §5–§17 — or in §35 — as current.
- The two analyses of today's plain-Lucene solution — the Edee CMS fulltext client and its
  e-commerce layer — are **internal and not published in this repository**. They were the yardstick:
  what today's client can do, capability by capability, so that "no step back" is a checkable claim
  rather than an assurance; and, for the e-commerce layer, the five findings that changed the design
  (the flat blob, Lucene as a mere producer of identifiers, filters outside the engine, substring
  expansion, and the 1000-hit window).
- [`lucene/lucene-primer.md`](lucene/lucene-primer.md) and
  [`lucene/lucene-under-the-hood.md`](lucene/lucene-under-the-hood.md) — what Lucene actually is and
  how its structures work, for a reader who has to judge the decision without having used it.
- [`lucene/engine-comparison.md`](lucene/engine-comparison.md) — the six engines against Lucene:
  which of them is built on it, how each orders results, and where each keeps its configuration.
- [`lucene/evitadb-the-seventh-engine.md`](lucene/evitadb-the-seventh-engine.md) — evitaDB placed in
  that same comparison, which is what makes the "engines with their own data model build their own
  search" pattern visible.
- [`lucene/evitadb-background/index-query-arch.md`](lucene/evitadb-background/index-query-arch.md) and
  [`lucene/evitadb-background/storage-engine.md`](lucene/evitadb-background/storage-engine.md) — the
  descriptions of evitaDB's index/query architecture and storage engine the comparison rests on.
- [`background/elasticsearch.md`](background/elasticsearch.md) and
  [`background/opensearch.md`](background/opensearch.md) — the verification notes from those two
  checkouts, which supply the two strongest external confirmations: distributed statistics built but
  off by default, and `match_only_text` shipping the position-less phrase evaluation we propose.
