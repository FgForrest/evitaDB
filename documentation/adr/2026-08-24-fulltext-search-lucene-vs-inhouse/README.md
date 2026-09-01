---
title: Prototype an in-house fulltext core over evitaDB's bitmap algebra instead of integrating Lucene
date: 2026-08-24
updated: 2026-08-25 10:45
status: proposed
kind: feature
issues: [258]
prs: []
areas: [evita_engine, evita_api, evita_query, evita_store, evita_external_api]
supersedes: []
superseded-by: []
relates: [2026-07-07-roaring-bitmap-vendoring, 2026-07-10-more-optimized-data-structures, 2026-08-01-bplustree-cursor-free-insert-path]
---

# Fulltext search in evitaDB: an in-house core over the bitmap algebra, not a Lucene integration

Issue #258 asks for fulltext search in evitaDB. The obvious answer — embed Apache Lucene — was
examined first and declined; the research recommends building a fulltext core on evitaDB's own
bitmap algebra, taking from Lucene only the two parts that are genuinely hard and genuinely
separable (linguistic analysis and the Levenshtein automaton) as an ordinary Maven dependency.
Nothing has been implemented. This record captures the reasoning, the design decisions that were
settled along the way, and the numeric gate that decides whether the work proceeds — so that the
options already weighed are not re-proposed, and so that whoever starts the implementation knows
which questions are answered and which are deliberately still open.

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

## Key technical details

Shallow pointers only — the depth is in the supporting files.

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
  it quietly.
- **The fulltext structures must be confined to the global index deliberately.** `AttributeIndex`
  lives on the common `EntityIndex` ancestor, not on `GlobalEntityIndex`, so reduced indexes have it
  too; the restriction is enforced the way `ReferencedTypeEntityIndex` does it for the sort
  structure — by overriding the method with an empty body and a comment saying why.

## Verification

**Nothing is implemented, so there is nothing to verify yet.** The gate is the verification plan,
and it is deliberately numeric so that the answer is not a matter of opinion:

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

## Consequences & open follow-ups

**What this enables.** Fulltext, facets, prices and hierarchy in one query over one snapshot; a
deterministic, explainable order that reproduces on every replica; ranking freshness (boost tables,
synonyms, entity dictionaries) without a reindex, because in the bitmap model boosts never touch the
write path; and a closed learning loop with Sage, which exports features from the same snapshot the
query ran over.

**What it costs.** A permanent maintenance commitment for in-house structures, without Lucene's
community. The calibration is in the repository already — the vendored RoaringBitmap fork is
experience of the "manageable, we have a process, but it is not free" kind.

Open items, each actionable:

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
  way out (`replaceCatalog`) can follow in F1.
- **Diacritics removal is not NFD.** The existing client uses a hand-written code-point table with
  special cases (`ß→ss`, `æ→ae`); we build on evitaDB's NFD normalisation. Results agree in most
  cases but not all, and migrating an existing site means a change in search results that has to be
  flagged in advance. P5 §7 has to verify the produced terms.

## Related work

- [`2026-07-07-roaring-bitmap-vendoring`](../2026-07-07-roaring-bitmap-vendoring.md) — the vendored
  RoaringBitmap fork is both the substrate the postings are built on and the calibration for what
  maintaining an in-house structure costs.
- [`2026-07-10-more-optimized-data-structures`](../2026-07-10-more-optimized-data-structures/) — the
  paged storage-part decomposition the fulltext structures inherit; it is why the format permits
  later partial loading without a format change.
- [`2026-08-01-bplustree-cursor-free-insert-path`](../2026-08-01-bplustree-cursor-free-insert-path.md)
  — the term dictionary is built on this B+ tree, so its insert-path invariants are ours too.

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
- **2026-08-25** — the P8 spike measured on real corpora (cnc, evita-demo-dataset): the performance
  gate passed on all criteria, the dictionary/positions/posting-representation/early-exit forks
  closed and the brief's falsified claims corrected — recorded as
  `prototypes/p8-trigram-substring-index.md` §35

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
- [`prototypes/p5-prior-art-accent-vs-stemming.md`](prototypes/p5-prior-art-accent-vs-stemming.md) —
  the 2026-08-27 prior-art survey behind P5 §12: how seven engines (plus the in-house EdeeCMS
  analyzers) reconcile diacritics folding with stemming, with `path:line` evidence; establishes that
  the co-designed folded-space stemmer is the only known fix and that the second-lane-per-term
  question must be settled before the term dictionary layout freezes.
- [`prototypes/p5-approach-measurements-accent-vs-stemming.md`](prototypes/p5-approach-measurements-accent-vs-stemming.md)
  — the empirical half of the survey: all six proposed mechanisms built and measured over one Czech
  vocabulary on five metrics. Settles that the folded-space stemmer reaches 296/298 on the real
  bare-typed cross-form query at one term per token, that the second-lane mechanism buys 3 pairs of
  72 for a 1.95x term inflation and therefore has **no** claim on the term dictionary layout, and
  that the remaining open questions are whether we own a Czech stemmer and whether the `ů→o` rule is
  worth its false merges.
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
  the closed forks, and the corrections to the brief's own claims.
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
