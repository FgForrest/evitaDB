# Fulltext and vector search in evitaDB — research, version 2 (consolidated)

> **Status: basis for a decision, NOT the decision.** Version 2 consolidates the original research, its
> counter-review (corrections K1–K9), a state-of-the-art supplement and the sponsor's answers (Z1–Z7
> below) into one coherent whole, and ends with a concrete architecture proposal and prototyping steps.
> The first version of the research was deleted after consolidation (2026-08-13); this document is the
> single maintained authority for both the argument and the conclusions.
> It was translated from Czech and moved out of `specifications/` into this record on 2026-08-24;
> see [`README.md`](README.md) for the decision it supports.
>
> Consolidation date: 2026-08-04. Verification against engine source code: 2026-08-11 (§8).
> Revision 2026-08-11: the wider platform context of Sage was incorporated (§1.1) — rank profiles,
> dynamic boosts, per-field postings, feature export, re-ordered delivery phases.
> Revision 2026-08-11 (2nd round): the client/evitaDB dividing line (§1.2), entities vs. facets and the
> home of NER (§1.3), query understanding verified against sources (§8, VK9–VK14), glossary.
> Revision 2026-08-12 (3rd round): CMS as a second usage profile (Z8) — BM25F moves from conditional to
> planned (§4.2, F3), P4 and the positional seam (§4.7), CMS RAM estimate (§4.8), the gate measures both
> profiles (§7), O6 = entry condition of CMS deployment; chunking of long documents (VK14).
> Revision 2026-08-12 (2nd part): matching across references — content blocks and related documents
> (§1.4, O10); joins verified against the sources of six engines (§8, VK15–VK20).
> Revision 2026-08-13: the server layer above Lucene verified against Elasticsearch and OpenSearch
> checkouts (§8, fourth round; VK13 and VK20 flipped from web to source code) — the cost of corpus
> scoring (§2.1), durability vs. visibility and the tone of the fallback (§4.5), synonyms and
> highlighting (§4.6), `match_only_text` (§4.7), quantization and filtered ANN (§5); an analysis of the
> existing Lucene client of Edee CMS and its e-commerce layer as a coverage yardstick (§7).

---

## Glossary

Terms used throughout the document (ordered so that each builds on the previous):

- **Postings** — the foundation of a fulltext index: for each term, the list of documents containing it
  (a bitmap of PKs in our case).
- **Feature** — one number about a candidate: *how* it matched (word count, typos, field, exactness),
  *what* it is (popularity, margin), or *what the query context says about it* (a boost from Sage).
- **Feature vector** — all features of a single candidate together; phase 1 computes it for every
  document of the candidate set.
- **Ordering lane (lane)** — one component of the comparison "who ranks higher": word count first, typos
  on a tie, … Each lane reads one feature from the feature vector.
- **Rank profile** — the recipe for how features compose into an order (selection and order of lanes,
  field weights, composition method). The default profile = lexicographic packing of lanes into a single
  64-bit number.
- **Boost map** — a table (query, PK) → coefficient, computed in Sage from user behaviour; at search
  time it lifts specific documents for specific queries.
- **Hot-swappable artifact** — a data bundle (synonym/entity dictionary, boost table, model) replaceable
  at runtime by an API call, without reindexing documents and without a restart.
- **Gazetteer** — a dictionary of known namings (brands, categories, parameter values) against which
  matches are sought in the query text; a pure data lookup, no model.
- **Span matching** — finding a match across a contiguous run of several words ("lord of the rings" =
  one dictionary entry); the mechanics of FST/trie automata, see §8 VK12.
- **Curation / pin / hide** — manual curator rules "for query X pin/hide document Y"; merchandising, not
  relevance.
- **Impact byte** — a 1-byte precomputed "how strongly the term sits on the document in the given field"
  (saturated tf × length normalization), §4.2.
- **RRF** — Reciprocal Rank Fusion: merging orders from several legs (text × vector) without score
  calibration, §5.5.

---

## 0. Summary

**Recommended path: an in-house fulltext engine ("B′") built on evitaDB's existing bitmap algebra, with
ranking as a feature vector composed by a configurable rank profile — the default profile being a
tie-break cascade of discrete criteria (no document BM25) —, Lucene's analysis chain as an ordinary
Maven dependency, and the vector branch as a mandatory part of the prototype (the JDK 21 entry
condition is satisfied, Z1).**

The load-bearing reasons, in order of importance:

1. **E-commerce engines (Algolia, Meilisearch, Typesense) do not use document BM25.** They rank by a
   cascade of discrete criteria without corpus statistics (§2). This dissolves the three hardest open
   problems of the first version of the research (defining the corpus, statistics drift vs. snapshot,
   score instability across replicas) — and Lucene's main value (BM25 + WAND + collector framework)
   ceases to be what we need.
2. **Meilisearch is an existence proof of this path:** roaring bitmaps as postings, bucket sort as
   ranking, facets over the full candidate set. The full set — which the first version labelled a
   handicap of a Lucene integration — is an *entry assumption* for this model. evitaDB's formula engine
   fits it more precisely than Lucene's collector model does.
3. **The sponsor's answers fit the model without remainder** (§1): a single `orderBy(relevance())` ⇒ a
   relevance sorter with a rank profile; suggesters in the first round ⇒ a term dictionary with
   prefix/typo support is part of the core, not a layer on top; the must-match filter leaves 85–95 % of
   the corpus ⇒ full-set scoring is the norm and WAND is unnecessary; read-your-writes can be relaxed ⇒
   the transactional integration has a cheap fallback. None of the answers brings Lucene back into play.
4. **The concern about RAM and off-heap has a concrete answer** (§4.8, §5.3): the text branch fits on
   the heap (estimate ~70–120 MB per 1M products and locale) and needs no off-heap subsystem; off-heap
   enters only with vectors, and then only as read-only mmap of immutable files — a pattern compatible
   with evitaDB's append-only storage, not a foreign allocator.
5. **The wider platform context confirms the division of roles** (§1.1): Sage as the offline brain
   (enrichment + learning from user feedback), evitaDB as local serving with general primitives (rank
   profiles, boost channel, feature export), AST translation in the client application. Dynamic boosts
   are a must-have — and in the bitmap model they do not go through the write path, so ranking freshness
   costs no reindexing at all. The dividing line between client and engine is refined in §1.2, the three
   senses of "entity" and the flow of offering a facet filter in §1.3.

---

## 1. The brief after refinement, and the wider context

Answers to the open questions of the first version of the research and their design consequences. Note:
Z1–Z7 are a **snapshot of the brief at the time of consolidation**, not axioms — revisions in the light
of the wider context (§1.1) shift some of the consequences.

- **Z1 — JDK 21 baseline?** Confirmed and done: the upgrade of evitaDB to JDK 21 happened and passed
  (2026-08-11). → The entry condition of the vector branch (§5) is satisfied; the text branch does not
  depend on the JDK. Note: the upgrade has not landed in the `dev` branch yet (`pom.xml` holds 17) — for
  P6, raising the baseline is an entry condition (P6 plan, §3).
- **Z2 — What is the corpus (global vs. reduced)?** Undecided; the question is the cost of separate
  corpora. → **The question dissolves** — the cascade needs no corpus (§2.1) and the structures live only
  in `GlobalEntityIndex` (§4.1). The cost of "variant 2" = 0, because separate corpora are not needed.
- **Z3 — Is read-your-writes necessary?** Ideal; it can be relaxed (sacrificing exact fulltext over
  uncommitted data). → Build the structures transactionally, the sidecar via COW; the measured fallback
  = visibility after commit (§4.5).
- **Z4 — Phrases / proximity?** Useful, typically 2–3 words. → **Do not index** positions; proximity as a
  top-K re-rank over stored values (§4.7).
- **Z5 — Suggesters / highlighting?** Suggesters in the 1st round; highlighting "would not hurt".
  → Suggester = prefix+typo over the term dictionary, part of the core (§4.6); highlighting at render
  time, without index support.
- **Z6 — Composition with `orderBy`?** A single `orderBy(relevance())`, chaining makes no sense.
  → A relevance sorter with a rank profile; the default profile = a 64-bit composite à la Typesense
  (§4.3), business/behavioural signals entering as features.
- **Z7 — Share of fulltext queries?** < 1 % of queries, but important; the must-match filter (price
  lists, currency, validities, states) removes only 5–15 % of the corpus. → The candidate set ≈ the
  corpus ⇒ full-set scoring is the norm and WAND is unnecessary (§2.3); the write-path maintenance tax
  must be small, since reads are rare (§4.9). The read share will grow, though, once search becomes a
  product (§1.1) — it is a snapshot, not a constant.
- **Z8 — usage profile (added 2026-08-12):** evitaDB does not serve only e-shops, but also CMS websites
  with **tens to hundreds of thousands of long documents** (articles, pages). → Long texts are a second
  full-fledged profile, not a fringe: BM25F moves from conditional to planned F3 (§4.2), P4 measures
  long texts too and decides about the positional seam (§4.7), the RAM analysis gets a CMS estimate
  (§4.8), the gate measures both profiles (§7) and searchable associated data (O6) is an entry condition
  of CMS deployment. For thematic queries over articles the main quality lever is the hybrid vector
  branch (§5), not the lexical score. Pages are moreover structurally interlinked (content blocks,
  related products) → §1.4.

Furthermore: **the vector index is a MUST HAVE**, albeit in a later phase (§5). The sponsor is drawn to
Vespa's approach (phased ranking) and Typesense's (comparing 64-bit ints) — both are in the design
(§4.3). The biggest concern voiced: pressure on RAM and the compatibility of off-heap processing with
the existing architecture — answered in §4.8 and §5.3.

### 1.1 The wider platform context (revision 2026-08-11)

Fulltext in evitaDB is not an isolated feature — it is part of a wider intent. **Sage** (`/www/oss/Sage`)
is a research prototype of an AI-powered search platform (model: Grainger & Turnbull, *AI-Powered
Search*), today built on Solr and still iterating; evitaDB is the production database, so far without
fulltext. Nothing from Sage is therefore a binding template — it is a laboratory. The target division of
roles:

- **Sage = the offline brain.** Content enrichment (vector embeddings of text and images, image→text
  captions, an NER-RE graph) and all **learning** from user feedback (click models, judgments, LTR
  training, ALS) — the outputs propagate back into evitaDB as ordinary data and artifacts (boost tables,
  expansion dictionaries, models).
- **evitaDB = local serving.** Search runs next to the data, with no runtime dependency on an external
  engine — an outage of Sage degrades the freshness of enrichment, not search. evitaDB remains a general
  database: no model inference (deterministic dictionary lookup, on the contrary, yes — §1.2, §1.3), no
  Solr specifics inside; only general primitives (match, profiles, boost channel, feature export,
  vectors).
- **The client application = translation and orchestration.** The query AST (see
  `Sage/docs/analysis/query-ast-portability.md`) is translated into evitaQL constraints in the client
  application, not in the engine; the application is at the same time the **producer of behavioural
  signals** (queries, clicks, purchases) — without them the learning loop starves.

Design consequences for this document: ranking as a **feature vector + rank profiles** instead of a
hard-wired composite (§4.3); **dynamic per-(query, PK) boosts as a must-have** input of phase 1 (§4.3);
postings **per (field, term)** because of per-query field weights (§4.2); feature export and explain as
a requirement (§4.3); ranking artifacts as hot-swappable data (§4.6); the vector branch in the core of
the prototype, not in the epilogue (§5, §7); the client/engine dividing line and the three senses of
"entity" (§1.2, §1.3).

### 1.2 The client / evitaDB dividing line (revision 2026-08-11, 2nd round)

The vision of §1.1 stands or falls with a precise boundary between AST translation in the client
application and the engine's primitives. Five decision tests by which any disputed case can be settled:

1. **Does it need the index or the full candidate set?** → evitaDB (matching, ranking, the boost map
   over the full set, facets, RRF fusion).
2. **Must it be consistent with processing at indexing time?** → evitaDB: tokenization, folding, typos,
   synonyms, entity lookup. A refinement after verifying Solr (§8, VK12): the index-time and query-time
   analyzers *deliberately* differ (synonyms typically only on the query) — agreement is required on the
   *produced terms*; both chains are nevertheless configured by the engine in one place, which is
   exactly why tokenization must not be given to the client.
3. **Is it model inference or knowledge outside the catalog?** → never evitaDB: NER-RE, intent parsing,
   learning. The only established market exception is query embeddings (§8, VK9/VK13; O7).
4. **Is it a choice (policy) rather than a mechanism?** → the client: field weights, profile selection, K
   for re-ranking, boost table selection, A/B arm.
5. **Is it data that changes without reindexing?** → a hot-swappable artifact in evitaDB; produced by
   Sage, selected by the client.

| Part of the AST / pipeline | Home | Note |
|---|---|---|
| `Bool`/`Term`/`Range`, filters, sort, facets, paging | client → native evitaQL | pure translation |
| the `Text` node (raw text + fields + weights) | evaluated by evitaDB | the "smart client" trap |
| analysis, typos, prefix, synonyms, entity lookup | evitaDB | test 2 |
| feature vector, profiles, applying boosts, phase 2 | evitaDB | test 1 |
| RRF fusion of text × vector | evitaDB | one snapshot |
| corpus NER-RE, learning, document embeddings | Sage | test 3 |
| intent parsing of free text (intervals, geo phrases) | client, ± fail-open Sage | test 3, O9 |
| field weights, profile, A/B arm | client | test 4 |
| dictionaries, boost tables, models | Sage → artifact in evitaDB | test 5 |
| behavioural signals | client → Sage | the learning loop (§1.1) |
| the `Relation` node | client → `referenceHaving`/`entityHaving` | scoring expansion §1.4/O10 |
| the `GeoDistance` node | evitaDB, later phase | issue #23 (§4.4) |

Two symmetric traps that most often break the boundary:

- **The "smart client" trap:** the client "pre-chews" the text — tokenizes it into `Term` nodes, expands
  synonyms or entities into an OR tree. This bakes the analyzer into every client (drift), kills ranking
  (the engine cannot tell an original term from an expansion and cannot weight them differently) and
  devalues explain. Consequence: the `Text` node crosses the wire whole and raw; `attributeMatches`
  (§4.4) is a high-level constraint, not a construction kit of term primitives.
- **The "smart engine" trap:** intent interpretation is pushed into evitaDB ("near Brno" → geo filter,
  "cheap" → price interval). The precise formulation of the prohibition: evitaDB does not do **model
  inference** — a deterministic dictionary lookup (synonyms, entities) on the contrary belongs in the
  engine; every engine examined does it that way (§8, VK9–VK12).

On the `Relation` node evitaDB has a head start over Solr: a filtering join across references exists
today (`referenceHaving`/`entityHaving`) — exactly the semantics that Sage's AST document marks as a
missing new capability. What is new for us is only the *scoring* expansion (a match in a variant raises
the product's score) — legitimate engine work for F3, not for the prototype. The whole topic of matching
across references (content blocks, related documents) is developed in §1.4.

### 1.3 Entities: NER vs. facets, and the "offer, do not apply" flow

The term "entity" carries three different things here; the design has to keep them apart:

1. **Schema entities / facets** — what is already in evitaDB: references to categories, brands,
   parameters (the facet map is built from them today). evitaQL **filters** through them — documents
   drop out of the result. Nothing changes about that.
2. **Recognized entities mapped onto the schema** — during NER over the corpus Sage recognizes "Bosch"
   and at the same time knows it is a reference to a brand entity with a specific PK. An estimated
   significant share of recognized entities (~40 %) has such a mapping; a dictionary artifact carries it:
   surface form → (reference type, PK / attribute value).
3. **Recognized entities without schema support** — meaningful nouns of the corpus ("cordless",
   "hammer action"). They have nothing to filter on; their role is purely relevance and expansion.

"NER" moreover glues together two operations of different natures, and that is the core of the division:

- **Corpus NER-RE** = genuine ML inference (detection, merging into categories, relations → a knowledge
  graph) — Sage, offline. Outputs: per-document entity annotations (enriching documents through the
  ordinary write path → an indexed attribute; matching on an entity is then a cheap bitmap operation and
  a strong feature §4.3), an entity dictionary and relations/expansion weights (artifacts).
- **Query-side recognition** = a gazetteer lookup against a known dictionary — deterministic, the same
  class of operation as multi-word synonyms (§4.6). It belongs engine-side; policy (what happens with a
  recognized entity) stays with the client and the profile.

**The "offer, do not apply" flow:** the first query carries no facet filter — recognized entities affect
only relevance (nothing drops out, recall holds). Alongside the result the engine returns an
**annotation**: "recognized 'Bosch' = brand (PK 123); the corresponding facet filter: …". Only after the
user clicks does the client send a second query with an ordinary filter. In evitaDB terms:

- the annotation is an **extra result** requested by a `require` constraint — it is computed together
  with the response in the `ExtraResultPlanningVisitor` pipeline and reuses shared intermediate results
  (the entity lookup already happened for relevance);
- the applied filter is an ordinary `facetHaving` inside `userFilter` — which pays for the existing
  facet summary semantics for free (impact counts, relaxation of user filters). **No new filtering
  mechanics arise.**

The first iteration offers **only facet filters** (brands, categories, parameters — all already in the
facet map today). **Interval filters** (histograms: "price up to 5000", "a low fridge") are a harder
class — they require interpreting a qualitative/numeric intent, i.e. model work, not a lookup — and go
into a later phase (O9).

**Why nobody runs a model over the query inside the engine — and what to do about it.** Verification
(§8, VK9–VK14) says: everybody does dictionary lookup engine-side, nobody runs a model over the query.
An honest reading of that absence: mostly latency economics (a model = ms to tens of ms on the hot path;
a dictionary = µs and in a closed e-commerce domain it covers most of the value) plus product philosophy
(determinism, reproducibility) — **not proof that it is a bad idea**. Vespa has the capability as an
extension point (searcher chains + ONNX in the container) and the trend is breaking: Typesense and
Meilisearch both offer an LLM path "natural language → query parameters" today, both as an opt-in call
to an external model (§8, VK10/VK11). Differentiation is therefore real for us, but the shape decides: a
deterministic gazetteer in the engine right away; model interpretation as an **optional, fail-open step**
(client → Sage; an outage degrades to the dictionary, never blocks search); and only possibly later as
an engine-side artifact after the pattern of Vespa embedders. The combination "gazetteer over real
schema objects + offering a filter instead of forcing it + a fail-open model" is not available
end-to-end in any of the engines examined.

### 1.4 Matching across references: content blocks and related documents (Z8, 2026-08-12)

Real content is structurally interlinked: pages are assembled from **content blocks** — shared fragments
maintained as separate entities, often not searchable on their own — and products link to related
products. The requirement: a fulltext match in a referenced document should **strengthen the referencing
document** — without that, a page assembled from blocks is not found at all. The market alternative is
duplicating block content into all referencing documents, which multiplies both data and writes.

**What the market does (verified, §8 VK15–VK20).** Filtering across a reference is common today;
**aggregated relevance across a reference is almost non-existent**:

- **Elasticsearch** — the only engine that aggregates child relevance into the parent (`has_child` +
  `score_mode` avg/max/min/sum). The default, however, is `none` (parent = score 0), a shared shard for
  parent and children is an **entry condition** rejected already at index creation (not a mere
  consequence), the join field is one per index and eager global ordinals are **on by default** on the
  join field, i.e. not an optional tax (refined by source verification, §8 VK20) — and its own tuning
  guide says "nested several times slower, parent-child **hundreds of times slower**; denormalize".
- **Solr/Lucene** — `{!join score=…}` via `JoinUtil` does carry relevance, but keeps all unique join
  values on the heap **on every query**, and for a multi-valued reference it propagates the score of the
  *first* occurrence, not the maximum (Solr hard-codes `multipleValuesPerDocument=true`). Same-JVM only;
  the networked variant (`crossCollection`) never carries a score — distance and relevance are mutually
  exclusive. Block join scores correctly but requires physical blocks (VK15/VK16).
- **Vespa** — imported parent fields work as native *attributes* (filtering, ranking, grouping) — the
  strongest cross-document *value* primitive on the market. A parent's textual match, however, never
  gets through: importing an indexed field is rejected in three layers (VK17).
- **Typesense** — a join is a purely set-based operation over ids; `query_by` on a referenced collection
  = HTTP 400 "not yet supported"; the only scoring channel is a predicate boost with a manual constant
  (VK18).
- **Meilisearch** — since 1.53 an experimental `_foreign` semi-join: filtering only, a cap of 1000
  documents, one level; the engine core does not see the join at all (VK19).
- **Algolia** — no join; the record size limit forces chunking, and `distinct` reassembles sections by
  **picking the best piece and discarding the rest** — sibling matches do not compose (VK20).

The summary pattern (formulation from the verification): no engine examined has a **persistent,
incrementally maintained cross-document relevance edge**. Relevance across a document boundary exists
only where the join is materialized onto the heap on every query (JoinUtil, ES global ordinals), or
where the documents are glued together already at indexing time (blocks, denormalization, chunking).

**Three shapes for evitaDB** (the fork is recorded as O10):

1. **Data duplication** — the market default born of necessity; a rejected starting point, not a model.
2. **Index-time expansion** — the *index* is denormalized, not the data: the block's tokens are added to
   the postings of the referencing pages (a pair ≈ 3 B: bitmap + impact byte), the text stays stored once
   (highlighting and proximity re-analysis read the block entity). Cost: fan-out when a block is edited
   (token diff × number of referencing pages); mitigation = batch application in trunk incorporation, the
   same mechanics as the fallback in §4.5(3) and the budget in §4.9.
3. **Query-time scoring expansion** — blocks/related entities are searched as structures of their own,
   candidates are translated bitmap-wise through the reference index (the filtering mechanics of
   `referenceHaving`/`entityHaving` exist today) and enter the feature vector as "a match across a
   reference of type R" with a lower weight. Aggregation over several matched references has to be
   defined (max/sum + decay per reference type) — the anti-pattern is Solr's first-encountered defect
   (VK16). Cost: an extra query leg; the write path pays nothing.

Leaning: **composition → index-time** (a content block is semantically the page's text; its words count
into the lanes as the page's own) and **association → query-time** (a related product boosts, it does not
pretend to be an own term). The data will decide: the distribution of block fan-out and the ratio of
edit frequency to query frequency (O10). Blocks that are not searchable on their own = a separate
collection outside the result scope, only an internal query leg. Synergy: blocks are ready-made chunking
units for the vector branch — block embeddings + the same block→page translation (§5).

**evitaDB's position:** reference indexes exist today and the full-set bitmap model turns the block→page
translation into a set operation without per-query materialization of values — that is, exactly the shape
the market lacks. It is a hypothesis to be verified by a prototype, not a finished feature; the gate
P5→P1→P2 is **not widened** because of it, but the design seam (reference provenance in the feature
vector, a defined aggregation function) has to be in P1 from the start. Delivery: F3, a candidate for
being pulled forward for the CMS profile with content blocks.

---

## 2. What the state of the art uses instead of document BM25

### 2.1 A tie-break cascade without corpus statistics

The dominant e-commerce engines rank by a **cascade of discrete criteria evaluated lexicographically** —
the next criterion only breaks ties left by the previous one:

| Engine | Cascade (default order) | Business ranking |
|---|---|---|
| **Algolia** | Typo → Geo → Words → Filters → Proximity → Attribute → Exact | last step (Custom) |
| **Meilisearch** | words → typo → proximity → attrRank → sort → wordPos → exactness¹ | in the middle |
| **Typesense** | `_text_match`: a bit-packed 64-bit composite² | 3 sort slots incl. the score |

¹ Verified in the source (§8, VK1): today's default has seven members — `attribute` split into
`attributeRank` (before `sort`) and `wordPosition` (after `sort`); `crates/milli/src/criterion.rs:121`.
² Two-layered: a 48-bit per-field score packed into a 64-bit cross-field word; `_text_match` occupies one
of the three sort slots (§8, VK3).

None of them uses TF-IDF or BM25 — **no `docFreq`, no `avgFieldLength`, no corpus statistics**. The
reasons: short structured fields (tf ≈ 1, length normalization meaningless), explainability ("it ranks
higher because it has 0 typos and a match in the name") and determinism. The score is a function of *the
query and the document only* — which is why the questions of the corpus (Z2), statistics drift vs.
snapshot and score instability across replicas all dissolve: the index is a deterministic function of the
WAL and every replica computes the same thing.

**Verification against the ES/OS checkouts (2026-08-13): the cost of corpus scoring is quantified in the
architecture.** Evidence from the opposite side is supplied by engines that do use corpus statistics. For
term frequencies to be global rather than per shard, the query proper has to be preceded by an entire
scatter phase `DFS_QUERY_THEN_FETCH`, i.e. one extra network round before every evaluation. Both
Elasticsearch and OpenSearch have it built — and both leave it **switched off by default**
(`SearchType.DEFAULT = QUERY_THEN_FETCH`; ES `SearchType.java:36`, OS `SearchType.java:62`). By default
they therefore score from per-shard statistics and admittedly sacrifice score comparability across
shards. OpenSearch's segment replication is a second, independent cost of the same property: for
statistics to be stable across replicas, the bytes of finished segments are sent instead of documents,
which commits the whole cluster to a shared Lucene format — and even so it is opt-in, the default
remaining document replication (`IndexMetadata.java:357`, `:360`). Choosing to work without corpus
statistics therefore does not merely eliminate a theoretical inconvenience: it eliminates a whole
infrastructure that the largest deployed engines of this category had to acquire because of it and still
leave switched off. For §4.2 this yields a condition worth stating as an **invariant** rather than as a
consequence: the cardinality of postings may serve as `docFreq` precisely because postings are never
split into parts scored independently (§4.1).

The cascade is nevertheless only one pole of the state of the art — the other pole (Vespa rank profiles,
Solr/ES function queries) scores by a **configurable function over a feature vector**. Which pole is
architecture and which is mere configuration is settled in §4.3: the design takes the cascade's substrate
(discrete, deterministic criteria without a corpus), but the architecture is a feature vector + profiles.

### 2.2 Meilisearch: existence proof

The Meilisearch engine (formerly "milli") is architecturally the closest existing system to the proposed
path: postings are **roaring bitmaps** (token → bitmap of doc ids) over LMDB, ranking is **bucket sort =
progressive partitioning of the candidate bitmap into ordered sub-bitmaps by set operations**, facets are
computed over that same full set. This is the same computational model as evitaDB's formulas over
`TransactionalBitmap`. Honesty requires adding its known pains: slow indexing and space amplification of
derived databases — **the most expensive being proximity** (word pairs, positions). The code evidences
this with two concessions (§8, VK2): the maximum indexed pair distance was lowered from 7 to 3 in October
2023 (`crates/milli/src/proximity.rs:7`) and an opt-out `ProximityPrecision::ByAttribute` exists. This is
exactly why proximity goes into the re-rank phase in our design rather than into the index (§4.7).

### 2.3 Vespa: phased ranking — the full set and expensive scoring are not mutually exclusive

Vespa formalizes **phased ranking**: first-phase computes a cheap score for every document that passed
the match phase — right inside the match loop — and second-phase re-ranks only the top-K (default
`rerank-count` = 100) with an expensive function; facets and histograms receive the whole matched set.
One refinement after source verification (§8, VK5): for free-text queries the default operator has been
**weakAnd** since Vespa 8 (2022) (targetHits 100), full-set mode applying to filters and structured
queries — phasing and WAND are orthogonal mechanisms. Our conclusion does not lean on Vespa's default,
though: with byte impacts over roaring bitmaps, full-set scoring is a linear scan of ints — fine for
e-commerce volumes (10⁵–10⁷ products), and Z7 (candidate set ≈ 85–95 % of the corpus) says selective
shortcuts would have nothing to save anyway. **WAND is therefore not in the design at all** — not
"deferred", but unnecessary; the only thing that would change that is an order-of-magnitude growth of
catalogs beyond ~10⁷ per locale.

---

## 3. Why not Lucene as the engine — and what to take from it

The abridged verdict (the full argument was made in the first, now deleted version of the research; the
conclusions remain here):

- **Its core solves a different problem.** BM25 + block-max WAND + collector = early-termination top-k
  with corpus statistics. We need a full-set cascade without statistics (§2). From Lucene we would use
  postings and analysis and pay for everything else: two storage and format lines (Kryo/`serialVersionUID`
  discipline + Lucene codecs), two backup/restore and compaction channels, a docid↔PK translation
  invalidated by every merge.
- **The honest corrections from the counter-review hold in the other direction too:** read-your-writes in
  Lucene does *not* mean an fsync per transaction (NRT reader + sparse commits + WAL replay — the
  Elasticsearch translog pattern), and score instability can be mitigated by a deterministic merge policy.
  The Lucene path is therefore not impossible — it merely buys a bundle whose centre of gravity we do not
  need, at an integration cost that remains.
- **The tipping scenario was examined and rejected:** the only thing that could have tipped the balance
  towards Lucene was "phrases + suggesters + highlighting mandatory in the first delivery" as a bundle of
  ready-made features. Answers Z4/Z5 activated it — but in the cascade model those items are bounded: the
  suggester is a range scan over the dictionary (§4.6), proximity is a re-rank without a positional index
  (§4.7), highlighting is re-analysis at render time. None of them requires the Lucene runtime.
- **What to take:**
  1. `lucene-analysis-common` (+ optionally `lucene-analysis-icu`) as an **ordinary Maven dependency** —
     the 9.12.x line runs on JDK 11+, the 10.x line on JDK 21; with the confirmed JDK 21 baseline (Z1)
     both are available and the choice is a detail of P5 (main = 11-dev already wants JDK 25). The
     language analyzers (incl. `CzechAnalyzer`) are themselves index-free, although the artifact drags in
     the whole `lucene-core` jar (§8, VK7). Two gaps: Polish lives in a separate artifact
     `lucene-analysis-stempel` and `SlovakAnalyzer` **does not exist** — Slovak = a Hunspell dictionary,
     or an in-house pipeline. No vendoring; freezing on a single minor version is acceptable for
     analyzers for years.
  2. With it comes `lucene-core` transitively (the `Analyzer`/`TokenStream` classes live there) — and in
     it `org.apache.lucene.util.automaton.LevenshteinAutomata`: a ready-made construction of a
     Levenshtein DFA for typo tolerance (§4.6) — the class has three imports and zero ties to the index.
     The helper `CompiledAutomaton` is no longer index-free: we write the DFA × dictionary intersection
     ourselves over our own B+ tree, the string → automaton bridge being shown by
     `FuzzyAutomatonBuilder` (§8, VK6). The index part of core goes unused.
  3. Inspiration: Lucene quantizes the length norm into a single byte (`SmallFloat.intToByte4`, decoded
     through a 256-entry table in `BM25Similarity`) — a direct precedent for the impact byte of §4.2; the
     impacts list is a "competitive frontier" of exact (freq, norm) pairs per block
     (`CompetitiveImpactAccumulator`). And phased ranking (Vespa) — as patterns, not code.
- **Variant D (delegation to an external engine) remains the baseline** that the delivery has to beat on
  the quality of the combination fulltext × facets × prices in a single query — which an external engine
  by definition does not have.

---

## 4. Target architecture B′ — the concrete design

### 4.1 Placement and granularity

Fulltext structures live **exclusively in `GlobalEntityIndex`**, partitioned per **locale** (analysis is
per language) and per **scope** (LIVE/ARCHIVED). Reduced indexes (`ReducedEntityIndex`) carry no fulltext
structures — they contribute to the query by what they contribute today: their bitmap, which is ANDed
with the fulltext candidates. Because the cascade uses no corpus statistics, this is **correct, not an
approximation** — a document's score does not depend on which sub-index the query is evaluated in.
Question Z2 ("the cost of separate corpora for reduced indexes") thereby has its answer: nothing is paid,
because separate corpora are not needed for anything.

### 4.2 Data structures

Three new structures per (collection, locale, scope), all built from technologies already in the repo:

| Structure | Content | Technology | New work |
|---|---|---|---|
| **Term dictionary** | (field, term) → postings ref | transactional B+ tree, front coding | layout + range scan |
| **Postings** | (field, term) → bitmap of PKs | `TransactionalBitmap` (exists) | wiring only |
| **Impact sidecar** | 1 byte per (field, term, PK) | `byte[]` chunks per roaring container | **new** — see below |

Front coding of the dictionary has its template in the existing `FrontCodedStringColumn`.

**Postings per (field, term), not field-collapsed** — a revision following §1.1: the client
application's query AST carries per-query field weights (`name^3, description`), so a field's weight
must not be baked into the index. All the verified neighbours have the precedent (§8): Lucene has
postings per field by design, Meilisearch has `word_fid_docids`, Typesense a per-field ART. The cost is
mild — every occurrence of a token lies in exactly one field, so the number of (field, term, PK) pairs ≈
the number of (term, PK) pairs; the dictionary grows and smaller bitmaps are added (P1 will verify,
reflected in §4.8).

**Impact byte** = `min(255, sat(tf) × norm(field_length))`, where `sat` is a saturating function of tf
and `norm` is length normalization (see below). Field weights are applied only by the rank profile at
query time (§4.3) — default weights (name > brand > description…) are held by the schema, and the query
may override them. The byte is aligned to the *rank position* of the PK in the postings bitmap — the
lookup is `bitmap.rank(pk)`, O(1) with respect to postings length (the constant is high, though; the hot
loop of phase 1 does not use rank and reads sequentially — plan P1, §5.1). The sidecar is partitioned
into **chunks along roaring container boundaries** (a range of 2¹⁶ PKs), so a write rewrites only the
chunks of the affected (field, term) combinations (COW, §4.5) and the structure can be paged per chunk.

**Long fields and the cheap path to BM25F.** The impact byte carries the document half of BM25F already
in F1: tf saturation and length normalization; field weights are supplied by the rank profile (§4.3).
Length normalization — without it a long description would win the impact lane merely by repeating the
term — is handled **without corpus statistics**: the length of the *own* field is normalized against a
pivot configured in the schema (not against the corpus `avgFieldLength`) and baked into the byte at
indexing time; for short fields norm ≈ 1. The score remains a function of the query and the document
only — §2.1 continues to hold. Should the product ever demand full BM25F: IDF is **free at query time**
in this architecture — a term's `docFreq` is the cardinality of its postings bitmap, deterministic per
catalog version, no maintained statistic. The only genuinely corpus-wide quantity of BM25
(`avgFieldLength`) is replaced by the pivot. "BM25F" in a later phase (F3) therefore means switching the
scoring function over the existing structures, not returning to the Lucene model. With the CMS profile
(Z8) the product has effectively demanded full BM25F — what was a conditional item ("should it ever…")
becomes a **planned extension of F3**.

**Scope of fields:** the first round indexes **attributes only**. Long localized texts (description) live
today in associated data, which has no indexing concept at all (`AssociatedDataSchema` knows only
`localized`/`nullable`) — searchable associated data is a planned extension: a new schema flag, the same
structures and weights (O6), not a condition of F1. For the CMS profile (Z8), however, they are an
**entry condition of production deployment** — long texts live precisely there. This does not block the
measurements at the gate: the harness can feed long texts in as attributes.

**Persistence:** every structure is serialized as a `StoragePart` in pages (a dictionary page, postings +
sidecar chunk) — subject to the same Kryo/BWC discipline as the other indexes. Phase 1 loads them onto the
heap in full (like the other indexes); the paged format is a **deliberately designed seam** for later lazy
loading / eviction, should the RAM analysis (§4.8) stop holding — without a format change.

### 4.3 Ranking: feature vector + rank profiles, two phases

A revision following §1.1: the score is **not** a hard-wired 64-bit composite. The real state-of-the-art
fork, which §2 originally passed over: cascade-as-architecture (Algolia/Meilisearch/Typesense) vs. a
**configurable scoring function over a feature vector** (Vespa rank profiles, Solr/ES). For e-shop search
alone the first pole suffices; for a platform where Sage continuously generates boosts, judgments and
models the second is necessary — otherwise every new ranking signal means an intervention into the score
format.

**Phase 1 computes the feature vector** of every candidate from three sources: (1) **match features**
from the index — the number of matched words, a weighted sum of typos, impact, exactness, a match on an
annotated entity (§1.3) (bitmaps + the impact sidecar §4.2); (2) **document attributes** — slow
per-document priors (popularity, margin, stock) read as an attribute / `SortIndex`; (3) **query context**
— dynamic inputs supplied with the query, above all the boost map per (query, PK) from the behavioural
platform (see below) and per-query field weights.

**The rank profile** determines how the order is composed from features. A single `orderBy(relevance())`
(Z6) is evaluated internally as a **descending sort by a single `long`** — the default, cheapest profile
being a lexicographic packing à la Typesense, filled with the criteria of the Algolia/Meilisearch
cascade. Proposed lanes from the highest bits:

| Lane | Bits | Criterion | Source of the value |
|---|---|---|---|
| 1 | 8 | number of matched query words | membership in per-token bitmaps |
| 2 | 8 | 255 − weighted sum of typos | typo expansion of terms (§4.6) |
| 3 | 8 | max. impact (sat(tf) × norm; field weights in the profile) | impact sidecar (§4.2) |
| 4 | 8 | exactness (exact > prefix > fuzzy) | provenance of the term in the expansion |
| 5 | 16 | proximity — **filled only by phase 2** | re-analysis of stored values (§4.7) |
| 6 | 16 | contextual rank (boost map / static prior) | query context, attributes |

The profile is configuration: a permutation of lanes, field weights, possibly a composition other than
lexicographic — one configuration covers the spectrum from Algolia (business last) to Meilisearch
(business in the middle). A profile selectable per query moreover gives an A/B harness for free (arm →
profile; determinism per profile is preserved). The exact bit widths of the lanes are a matter for
prototype P1, not of principle.

**Dynamic boosts (must-have, §1.1):** the boost map of the current query is small (hundreds of PKs) and
is consulted **in phase 1 over the full set** — a document outside the top-K must have a chance to climb;
a re-rank is not enough. In the bitmap model this is O(1) per candidate (a hash lookup), or possibly
cheaper: the boosted set is evaluated separately and merged into the heap. A crucial property: the query
context **does not go through the write path** — no reindexing, boost freshness = the freshness of
aggregation in Sage (§4.9). Slow priors, on the contrary, belong in attributes; the dividing line is
volatility, not dogma.

**Phase 1 (cheap, full set):** a linear pass over the candidate bitmap; match features are composed from
bitmaps and impact bytes. For 10⁶ candidates this is a scan of small ints — target ≤ 25 ms
single-threaded (P1). Facets, histograms and `require` blocks receive the full candidate set as they do
today — **the formula engine is not touched**.

**Phase 2 (expensive, top-K only):** generally a **pluggable re-rank** over the top-K by phase 1
(proposal K = 1000, P4 will decide). Its first inhabitant is the proximity lane (§4.7); next in line is a
behavioural / LTR model learned in Sage, applied over the feature vectors of the top-K. Deeper pages keep
the phase 1 order — a documented property, and Vespa makes the same trade-off (`rerank-count`).
Determinism is preserved: both phases are a function of the query, the data and the explicitly passed
query context.

**Feature export and explain (a requirement, not a nice-to-have):** `require` has to be able to return
the feature vector / relevance breakdown of the returned entities — without it, training data for LTR
cannot be collected (precedents: Solr's `[features]` transformer, Vespa's `match-features`) nor can
relevance be debugged. A bonus over Solr: features read from the same snapshot as the query remove the
known defect of retroactive extraction of volatile features, which the behavioural platform admits to
today. The export has a second consumer as well: annotations of recognized entities with proposed facet
filters are returned as an extra result for the "offer, do not apply" flow (§1.3).

**Sort integration:** the precedent is `FilteredPricesSorter` — a sorter over values computed during query
evaluation, not over a precomputed order. The relevance sorter = the same pattern: bitmap + an array of
`long` scores → a top-N heap select.

### 4.4 Query API (sketch)

```
filterBy(
    priceInPriceLists(...), priceInCurrency(...), priceValidInNow(),  // must-match (Z7)
    entityLocaleEquals(CZECH), scope(LIVE),
    attributeMatches("cerna kozena bunda")   // working name of the constraint
),
orderBy(relevance()),
require(page(1, 20), facetSummaryOfReference(...))
```

`attributeMatches` performs the analysis of the query (the same analyzer as at indexing time), typo/prefix
expansion of the terms and yields a candidate bitmap + a score context for `relevance()`. The naming and
the exact shape of the constraints is open question O4; the implementation procedure is covered by the
`new-constraint` skill. Today's `attributeContains`/`attributeStartsWith` remain unchanged (a different
contract: exact substring). The suggester gets its own input (proposal: a separate require / a dedicated
endpoint, O4 will decide).

Compatibility with the client application's query AST (§1.1): the node `Text {text, fields with boosts}`
maps onto `attributeMatches` + per-query field weights (§4.2), `Bool/Term/Range` onto existing filtering
constraints, `Vector` onto a vector constraint (§5). `relevance()` is parameterizable by a rank profile
and a query context (boost map) — the exact shape is handled by O4 and O8. The `GeoDistance` node has no
counterpart yet — geo primitives are covered by issue
[#23 Spatial indexes and constraints](https://github.com/FgForrest/evitaDB/issues/23); they have a
separate e-commerce motivation (nearest pickup points) and belong to a later phase.

### 4.5 Transactions and visibility (Z3)

Three levels, ordered by preference:

1. **The dictionary and postings are transactional for free** — both the B+ tree and `TransactionalBitmap`
   are existing transactional types with diff layers; read-your-writes holds for them structurally.
2. **The impact sidecar via COW of chunks:** rank alignment changes underneath the diff layer, so on write
   the whole chunks of the affected (term × container) combinations are rewritten. The precedent in the
   repo: the bucket-anchored rebuild of `InvertedIndex` value trees (mechanism D5). The cost is bounded by
   the number of affected terms × chunk size; prototype P2 measures it.
3. **The fallback that Z3 explicitly permits — and which is a full-fledged variant, not an emergency
   exit:** if P2 shows an unbearable tax, phase 1 falls back to **visibility after commit** (fulltext reads
   the last committed catalog version; uncommitted changes of the own transaction are not visible). The
   index is a deterministic function of the WAL, so catching up is always possible in batch during trunk
   incorporation.

**Verification against the ES/OS checkouts (2026-08-13): splitting durability from visibility is a tax we
do not pay.** In Elasticsearch, durability is held by the translog (default `index.translog.durability =
REQUEST`, i.e. an fsync after every write request), whereas visibility comes only with a **refresh** —
opening a new reader over the newly created segments, with a default interval of **1 second**
(`IndexSettings.java:311`, `:320`). A written and fsynced document is therefore not visible in search
until a refresh happens; a workaround reading directly from the translog exists only for access by
identifier and cannot in principle be generalized to search. All of this is the tax for immutable
segments, which our model does not pay: a transactional B+ tree and `TransactionalBitmap` give durability
and visibility from a single mechanism, and with the same semantics for reads by key as for search. It is
the single strongest argument for the chosen storage architecture.

The same finding also changes the weight of point (3). "Visibility only after commit" is the **ordinary
operating mode** of the largest deployed engines of this category — with the difference that for them the
boundary is the passing of a second, i.e. a point unrelated to the data at all, whereas for us it would be
the commit of a transaction, i.e. a semantically defined point. Our fallback is therefore strictly better
than their default behaviour. OpenSearch goes further still: the star-tree, i.e. a side structure sitting
next to the inverted index, is built **exclusively in batch** at flush and merge
(`StarTreesBuilder.build()`, `buildDuringMerge()`) and has no incremental update path at all. Two caveats,
so that this is not overrated: the star-tree is a combinatorially much heavier structure than our flat
sidecar (one byte per field, term, PK triple), and its answer "rebuild it at flush" presupposes immutable
segments, which we do not have — P2 therefore measures something for which no comparison point exists in
OpenSearch, which is a more honest formulation than "nobody does it, so it is risky". Should the fallback
fall through, the **`WAIT_UNTIL`** pattern (`WriteRequest.RefreshPolicy`) is worth adopting: the write API
optionally holds its response until a subsequent search would already see the change. It does not speed
visibility up, it merely synchronizes the client with it — a purely synchronizing element at the API
boundary, not a change of the index model.

### 4.6 Typo tolerance, prefix, suggester (Z5 — first round)

- **Prefix:** a range scan of the sorted dictionary → an OR of the bitmaps of the found terms (with an
  upper limit on the number of expanded terms). For search-as-you-type the last token of the query is
  taken as a prefix.
- **Typo:** a Levenshtein DFA (the ready-made `LevenshteinAutomata` construction from `lucene-core`, §3;
  a hard cap of distance 2, more is unsupported by the class) is intersected with the dictionary by a
  guided walk of the B+ tree. Product conventions to be taken from Algolia/Meili: 1 typo from ~4–5
  characters, 2 from ~8–9, the first letter not counted (exact thresholds = O3).
- **Suggester:** candidate terms from the prefix+typo expansion, scored by postings cardinality
  (popularity) × field weight; entity suggestions = an OR of the bitmaps of the top-M terms ∧ the
  must-match filter, top-N by the composite of §4.3. No additional structure — the suggester is a
  derivative of the dictionary, which is why it belongs in the core and not in a layer above.
- **Synonyms and expansion:** query-time expansion according to a dictionary that is a **hot-swappable
  data artifact** (versioned, per locale, replaceable at runtime), not a schema mutation — in the target
  state it is continuously generated by Sage (SKG → expansion dictionary, §1.1). No impact on index
  structures. The same span-matching mechanics (a phrase dictionary over an FST/trie, §8 VK12) will also
  serve the entity dictionary (§1.3) — one implementation, two artifacts. **Verification against the ES
  checkout (2026-08-13):** "no impact on index structures" is not a convention there but a property
  enforced by the type system. Declaring a synonym dictionary `updateable` **automatically** moves the
  filter into `AnalysisMode.SEARCH_TIME` mode (`SynonymTokenFilterFactory.java:199`) and a chain mixing an
  index-time and a query-time component refuses to be assembled (merging `INDEX_TIME` with `SEARCH_TIME`
  throws, `AnalysisMode.java`). A replaceable artifact thus never could be baked into the index. Worth
  adopting: without enforcement the same claim is fragile and an error surfaces as inexplicably missing
  results rather than as an exception — which is exactly the case the defensive-design rule in `CLAUDE.md`
  is aimed at.
- **Highlighting:** re-analysis of the stored values of **the returned page only** (20–50 entities) with
  offsets from the analyzer; no index support, sub-ms cost. **Verification against the ES checkout
  (2026-08-13):** this is exactly `OffsetSource.ANALYSIS`, and in Elasticsearch it is the **default
  behaviour** of an ordinarily configured field — the choice of offset source falls to re-analysis of the
  stored value whenever nothing better is available (`DefaultHighlighter.getOffsetSource`, lines 248–259).
  Index support (`offsets` in `index_options`, term vectors) is an optional speed-up paid for on write,
  not a condition; `FastVectorHighlighter`, conversely, will not start at all without term vectors. The
  claim of this bullet therefore no longer stands unsupported, as it did until now.

### 4.7 Phrases and proximity (Z4)

Positions are **not indexed** (the most expensive part of the Meilisearch experience, §2.2). Proximity
for 2–3 word queries is computed in **phase 2**: for the top-K candidates the stored values of the
searched attributes are re-analyzed and the minimum distance/order of the matched tokens is computed →
lane 5. Short e-commerce fields make the re-analysis cheap (target ≤ 10 ms for the top-1000, P4). Careful:
that premise rests on short fields, and the CMS profile (Z8) breaks it — re-analyzing thousand-token
articles is a different cost class. P4 therefore measures short and long fields separately; failure on
long ones is the first legitimate trigger for the positional index below (possibly enabled per
field/collection only). Should the need for *exact phrase filters* (not merely ranking proximity) over
long texts ever arise, that is the point at which a positional index is added retroactively — the format
of §4.2 does not preclude it, it merely is in no hurry.

**Verification against the ES/OS checkouts (2026-08-13): this trade-off has a production precedent.** The
field type `match_only_text` — shipped in both Elasticsearch and OpenSearch — indexes for every term
**only the list of documents** (`IndexOptions.DOCS`: no positions, no frequencies, no norms) and evaluates
phrase queries in two phases: position-free postings give a cheap approximation, i.e. a superset of
candidates containing all the words of the phrase, and only over those is the stored value **re-analyzed**
and the phrase confirmed or rejected (ES `SourceConfirmedTextQuery`; OS `SourceFieldMatchQuery.java:101`,
which builds a one-off `MemoryIndex` over the value of a single document). It is the same pattern of
"approximation from postings, confirmation by re-analysis of the stored value" that this section proposes
— and it is shipped as a full-fledged field type in an engine that has positions as standard and could
have used them. Until now the argument of §4.7 stood only on negative evidence from Meilisearch (that
positions are expensive); this is positive evidence.

Three differences are worth recording, because they change what P4 should measure. First, their pruning
is **predicate-based** (every candidate that passed the approximation is verified), ours is **by rank**
(top-K): their variant is exact but without a bounded worst case, ours is the opposite. P4 ought to
measure both — if the number of candidates containing all the query's words is typically below K, the
question of K (O2) may fall away. Second, for them a phrase match is a **filter** returning a constant
score, whereas for us it is a ranking lane; for an e-shop a boost is defensible, but for the CMS profile
(Z8) a user looking for a specific formulation rather expects a filter — and if it is a filter, top-K is
fundamentally not enough. Third, `match_only_text` had to sacrifice length normalization too, because in
Lucene norms are part of the positional write; our impact byte (§4.2) is our own structure independent of
positions, so we have length normalization even without positions. That is a concrete point at which an
in-house format earns over an adopted one, and it belongs in the argument of §3 — in the Lucene format
this combination is simply impossible.

### 4.8 RAM analysis (the sponsor's main concern)

A parametric estimate for **1M products, one locale**, ~20 tokens per product across the searched fields
(name, brand, code, short description), ~300k unique terms:

| Structure | Estimate | Note |
|---|---|---|
| term dictionary (front-coded) | ~8 MB | (field, term) keys, ~400–500k entries (§4.2) |
| postings bitmaps | ~45–65 MB | ~20M pairs × ~2 B + overhead of more small bitmaps (per-field) |
| impact sidecar | ~20 MB | ~20M pairs × 1 B + chunk overhead |
| optional prefix bitmaps | +10–30 MB | hot prefixes only, can be switched off |
| **total** | **~85–135 MB** | per locale; scales ~linearly with the number of products |

Conclusion: **the text branch fits on the heap and needs no off-heap subsystem** — it is the same order as
the existing index structures, and production memory pressure (OOMKills on non-heap overhead) concerns it
just as it does the rest of the engine, no more. Safeguards: (a) the paged `StoragePart` format of §4.2 is
a prepared seam for lazy loading/eviction without a format change, (b) long fields (full descriptions) are
added to the searched fields deliberately, per schema — whoever indexes them pays for their postings;
their scoring is handled by the length-normalized impact byte (§4.2). The RAM figures will be verified by
prototype P1 on a real catalog (JOL), not by estimation.

The CMS profile (Z8), parametrically: ~100k long documents × ~500–600 unique terms per document ≈ 50–60M
(field, term, PK) pairs, i.e. ~2.5–3× the product estimate → **~250–400 MB per locale**. Still heap and
the paged seam still applies — but it is an estimate unsupported by measurement, which is why P1 measures
real CMS content too, not only a product catalog.

Off-heap enters only with vectors — and differently from how the concern was phrased: see §5.3.

### 4.9 Write-path budget (Z7)

Fulltext is < 1 % of reads, but index maintenance runs on **every** write of a searched attribute — the
tax is paid even by those who never query fulltext. Maintenance is incremental: a token diff of the old
and new value → adjusting postings (diff layers) + COW of the impact sidecar chunks of the affected terms.
The risky case: an entity with a long description = hundreds of affected terms per mutation. Target: ≤ 10 %
of commit throughput on a real WAL (P2, harness `WalReplayBenchmark` + the production e-commerce export). Mitigation
should the target not hold: the fulltext delta is applied in batch/asynchronously during trunk
incorporation (the Z3 fallback permits it) — the write path then pays nothing for fulltext beyond
serializing the mutation.

Two revisions following §1.1: (a) query-context boosts (§4.3) do not burden the write path at all — the
dynamics of ranking are not a reason to write; (b) the share of fulltext reads will grow once search
becomes a product — a higher read share rather justifies the maintenance tax, and Z7 is a snapshot of
today's state.

---

## 5. The vector branch (MUST HAVE, second phase)

### 5.1 Timing and choice

The entry condition **JDK 21+** (Panama SIMD for distance functions) is satisfied — the upgrade to JDK 21
happened (Z1). Revision per §1.1: the hybrid (text × vector × visual) is the core of the Sage experiment,
so the vector branch belongs in the prototype right behind the text core, not in the epilogue (§7, phase
F2). Candidates: **jVector** (Apache 2.0, an embeddable ANN library without Lucene formats) vs. an
**in-house HNSW**. Spike P6 decides; a priori jVector has the edge — the risky structure of the task
(empirical tuning of recall/latency) argues for adopting measured code, and unlike Lucene it does not drag
a whole foreign engine along.

### 5.2 Quantization and memory

For 1M vectors × 768 dim: raw float32 ≈ 2.9 GB — **never on the heap**. RaBitQ/BBQ-class quantization
(~32×) ≈ 90–100 MB of codes + an HNSW graph ≈ 100–250 MB; the query runs over the codes and the top-K′ is
re-scored against the raw vectors from disk. Target for P6: recall@10 ≥ 0.95 with rescoring.

**Verification against the ES checkout (2026-08-13)** adds three numbers to this consideration that P6
need not seek from scratch. The default quantization of a `dense_vector` field has historically moved
along the line unindexed `float` → `int8_hnsw` → `bbq_hnsw` → `bbq_disk`, and binary quantization is today
the default **from 384 dimensions** (`BBQ_DIMS_DEFAULT_THRESHOLD`, `DenseVectorFieldMapper.java:295`),
with an absolute minimum of 64 dimensions (`BBQ_MIN_DIMS`, `:159`); for typical embeddings (384 or 768
dimensions) it is therefore usable, which is good news for the RAM analysis above. The loss of precision
is handled by **oversampling with a default factor of 3.0** (`DEFAULT_OVERSAMPLE`, `:294`): three times the
requested number of candidates is retrieved in the quantized space and re-scored at full precision. This
yields a layout requirement that §5.3 does not state explicitly today — **full-precision vectors must
remain reachable even after quantization**, and that for three times `k`, not merely for the returned
page. It is at the same time a second argument for keeping embeddings stored outside the vector index as
well: in our model of a single live structure, a change of quantization means rebuilding the index
(Elasticsearch can afford to do it at runtime only thanks to the segment model, where each segment carries
its own codec).

### 5.3 The off-heap model — an answer to the compatibility concern

Raw vectors are stored in **immutable, append-only files per catalog version** — the same philosophy as
the existing storage (append-only, immutable after write, compaction). They are read via mmap: today
`MappedByteBuffer`, from JDK 22 the final FFM API (`MemorySegment`, JEP 454; still preview on JDK 21).
"Off-heap" here therefore means **the operating system's page cache over read-only files** — no custom
allocator, no interaction with the transactional layer (the file is a snapshot; a new version = an append;
old versions are held by readers and cleaned up by compaction). Quantized codes and the graph
(~200–350 MB/1M) can live on the heap or in direct buffers. That is the entire extent of off-heap in the
design — the text branch (§4.8) needs none.

### 5.4 Filtered ANN

Z7 says the must-match filter leaves 85–95 % of the corpus — for such a filter an **inline test during the
graph walk** suffices (`bitmap.contains(pk)`, cheap). A candidate becomes selective only in combination
with facets/hierarchy; below a selectivity threshold (~1–2 %, P6 will tune it) it switches to a
**brute-force scan of the candidate bitmap** — exact and, for a small set, cheap. Filtered-DiskANN remains
a research frontier and is not implemented in the first version.

**Verification against the ES checkout (2026-08-13)** confirms this pair as a shape somebody else arrived
at independently — and corrects one item. Elasticsearch has both paths side by side: pre-filtering during
the graph walk, whose heuristic switched to **ACORN** from a certain index version
(`DEFAULT_TO_ACORN_HNSW_FILTER_HEURISTIC`, `DenseVectorFieldMapper.java:199`), and post-filtering
(`PostFilterKnnQuery`). ACORN is therefore no longer a research frontier but a shipped default — the
previous formulation of this paragraph underrated it. More significant, though, is **what the choice
between the strategies is made on**: the estimated selectivity of the filter
(`postFilterSelectivityThreshold`, configurable per index). Two things follow for us. That two strategies
are necessary and one does not suffice; and that for us the decision between them is **exact, not
heuristic** — we have the candidate bitmap computed, so we do not estimate selectivity, we know it. With
our profile (Z7: the must-match filter cuts only 5–15 % of the corpus) the default choice is
post-filtering, because the filter cuts almost nothing and pre-filtering would merely make the graph walk
more expensive; pre-filtering is needed only for genuinely selective combinations, for example a deep
category plus a narrow price range.

### 5.5 The text × vector hybrid

Fusion via **RRF** (Reciprocal Rank Fusion) — it works with ranks, not scores, so it requires no
calibration between the composite of §4.3 and cosine distance; the result maps back into a single
`orderBy(relevance())`.

### 5.6 Assumption

Document embeddings are **supplied by Sage** as entity data (an attribute / associated data of type
`float[]`); model inference is outside the engine's scope — the document side of O5 is thereby resolved
(§1.1). Careful: `float[]` is not a supported attribute type today — the technical path of embeddings
inwards is handled by plan P6 (§5.4, question OP6-4). What remains open is the **query side**: the query
has to be embedded at search time — local inference in the client application (ONNX, a small model), or a
network call, which on the vector leg reintroduces a dependency we otherwise eliminate (O7). Careful about
the lifecycle too: changing the embedding model = re-embedding the catalog = a bulk write through the
write path + a rebuild of the vector index; mmapped immutable files per catalog version (§5.3) accommodate
that.

**An open question that the ES verification opened (2026-08-13): is the unit of the vector index the
entity, or a chunk?** The field type `semantic_text` has the splitting of long text into parts built
**right into the field type** — it creates a nested object for chunks and stores per chunk its embedding,
text and character offsets (`SemanticTextFieldMapper.java:262`, `:276`); chunking is therefore not left to
the user, it is a property of the field. For us this decides the data model of the whole vector branch,
and today the question is not being asked anywhere. If the unit is a chunk, a chunk → PK mapping is needed
plus a defined aggregation function across the chunks of the same entity (typically the maximum) — which
is **the same shape of problem as aggregation across references** (§1.4, O10), so the two ought to share a
mechanism. If the unit is the entity, it has to be said how a long article fits into a single embedding.
For the CMS profile (Z8) this matters more than server-side inference itself, and the synergy with content
blocks is direct: blocks that are not searchable on their own are ready-made chunking units (§1.4).

---

## 6. Remaining open questions

A shorter list than in the first version of the research:

- **O1 — the default rank profile:** the position of the contextual rank (the last lane à la Algolia vs. in
  the middle à la Meilisearch) and the granularity of profile configuration — per schema, per query (§4.3).
- **O2 — K for phase 2** and the behaviour of deep paging (document it vs. compute it out).
- **O3 — typo thresholds and defaults** (length thresholds, the first letter, diacritics vs. typo — mind
  the interaction with the NFD normalization of today's `attributeContains`).
- **O4 — the shape of the DSL:** the naming of constraints (`attributeMatches`? `matches`?), the shape of
  the suggest API, the behaviour of `relevance()` without a fulltext filter (error vs. no-op).
- **O5 — the origin of embeddings:** the document side is resolved — supplied by Sage (§1.1, §5.6).
- **O6 — searchable associated data:** the shape of the schema flag and its scope — in the 1st round only
  `String` / localized `String`; a selector of text paths inside `ComplexDataObject` only on demand (§4.2).
  The priority has risen: for the CMS profile (Z8) it is an entry condition of production deployment.
- **O7 — query-time embedding of the query:** local ONNX inference in the client application vs. a network
  call (§5.6).
- **O8 — the shape of the boost channel:** after verifying the precedents (Algolia Dynamic Re-Ranking,
  Solr's LTR store; §8 VK12/VK14) the design leans towards a **reference to a stored, Sage-generated
  table** in evitaDB — the engine does the join, the request does not balloon, freshness is held by the
  artifact; an inline PK→boost map remains as a debug/override channel. What remains: versioning of the
  tables and size limits (§4.3).
- **O9 — intent parsing of free text:** the home of query intelligence was settled by the inference vs.
  lookup division (§1.2, §1.3): entity lookup is engine-side, model interpretation of intent ("price up to
  5000", "a low fridge", "near Brno" → an interval/geo filter) stays outside the engine. What remains open
  is the shape of the fail-open call to Sage from the client, the product priority of interval filters, and
  whether the model step should in time be offered as an optional engine-side artifact (the precedent being
  Vespa embedders; potential differentiation, §1.3).
- **O10 — the shape of the match-across-reference expansion (§1.4):** index-time vs. query-time vs. hybrid
  depending on the reference type (composition vs. association); the aggregation function across several
  matched references (max/sum, decay per type) and its place in the rank profile (§4.3); the input data for
  the decision: the distribution of content-block fan-out and the ratio of block edit frequency to query
  frequency — to be measured from production before building.

---

## 7. Next steps: prototypes and phasing

### Prototypes (P1–P7)

- **P5 — analyzers.** locale→analyzer mapping, Czech (`CzechAnalyzer`), coexistence with NFD
  normalization. Harness: unit tests + real attribute values. Criteria: `attributeContains` unchanged in
  behaviour; smoke quality of cs/en stemming.
- **P1 — the core of the risk.** Dictionary + postings + impact sidecar + the composite scorer of phase 1
  over a production e-commerce catalog **and over a CMS dataset with long texts (Z8)**, RAM via JOL. Criteria:
  RAM ≤ 150 MB per 1M products and locale; the CMS profile within the bounds of the §4.8 estimate; phase 1
  ≤ 25 ms per 1M candidates (1 thread); quality side-by-side against `attributeContains` on ~50 real
  queries.
- **P2 — transactional maintenance.** COW of sidecar chunks under a real write load; harness
  `WalReplayBenchmark` + the production e-commerce WAL. Criterion: a drop in commit throughput ≤ 10 %, otherwise activate
  the fallback of §4.5(3).
- **P7 — rank profiles, boost channel, feature export.** The query-context boost map in phase 1 (an effect
  on the full order, not merely the top-K), the profile as configuration, the feature vector and
  annotations of recognized entities in the `require` response (§1.3). Criteria: a boost demonstrably lifts
  a document from a depth outside the top-K; channel overhead ≤ 1 ms per 10⁶ candidates with an empty map.
- **P3 — suggester.** Prefix + typo expansion + scoring over the structures of P1. Criterion: p99 ≤ 5 ms
  per keystroke including typos (≤ 2 ms without).
- **P4 — proximity re-rank.** Phase 2 over P1 + stored values. Criteria: re-analysis of the top-1000
  ≤ 10 ms, measured separately on short fields and on long texts (Z8) — failure on long ones activates the
  positional seam (§4.7); decides K (O2).
- **P6 — vector spike.** jVector vs. an in-house HNSW; BBQ/RaBitQ; mmap integration; synthetic + real
  embeddings. Criteria: recall@10 ≥ 0.95 with rescoring; latency < 10 ms per 1M.

**Order:** P5 → P1 → P2 form the **decision gate** — after them a binding decision is recorded in
[`README.md`](README.md). The gate measures **both usage profiles (Z8)**: the e-commerce catalog and CMS long
texts. Quality at the gate is not measured with our own yardstick: side-by-side against a Solr baseline
through Sage's existing comparison harness / golden set (`Sage/docs/analysis/golden-set-analyzer.md`,
`search-comparison-final.md`). P7 belongs to the core right behind P1 — rank profiles and the boost channel
are architecture, not delivery. P3 + P4 run in parallel after the gate. P6 has its own mini-gate (jVector
vs. in-house), belongs in the prototype (§1.1); the JDK 21 condition is satisfied.

Note: the prototype "variant C with Lucene as a measuring rig" from the first version of the research is
**cancelled** — it was meant to answer the cost of full enumeration from Lucene and the qualitative
difference in analysis; the first question fell with the Lucene path (§3), and the second will be answered
by P1/P5 directly on the target architecture, which is both cheaper and more informative.

### Detailed implementation plans (2026-08-12)

Every prototype has a separate implementation plan in the [`prototypes/`](prototypes/) directory, verified
against evitaDB's code as well as the engines' sources; two cross-cutting documents cover the schema and
the query language:

- [P5 — analyzers](prototypes/p5-analyzers.md) ·
  [P1 — index core](prototypes/p1-index-core.md) ·
  [P2 — transactional maintenance](prototypes/p2-transactional-maintenance.md)
- [P7 — rank profiles and the boost channel](prototypes/p7-rank-profiles-and-boost-channel.md) ·
  [P3 — suggester](prototypes/p3-suggester.md) ·
  [P4 — proximity re-rank](prototypes/p4-proximity-rerank.md) ·
  [P6 — vector spike](prototypes/p6-vector-spike.md)
- [Fulltext configuration in the schema](prototypes/schema-design.md) ·
  [The query side and the rank function](prototypes/query-design.md)

The plans correct the research on several points (the full argument is in them): `bitmap.rank` is not
usable in the hot loop (P1 §3.2); JDK 21 is not in `dev` yet (P5 §3.1, P6 §3); `float[]` is not a supported
type — the embedding path of §5.6 is technically closed today (P6 §5.4); the "D5" precedent means "do not
maintain global alignment", not "copy chunks" (P2 §3.3); a mechanism for reindexing on a schema change does
not exist and the change passes silently (schema-design §4.3, §7); `orderBy` is a chain of substitutes, so
the 64-bit composite is enforced by the language's semantics rather than borrowed (query-design §2.3);
placing proximity on lane 5 is a consequence of the mechanism, not a relevance choice — a proposal for a
cheap co-occurrence feature in phase 1 (P4 §3); trunk incorporation applies every mutation twice, so the
fallback of §4.5(3) erases work rather than moving it (P2 §3.5, §8).

### Analysis of the existing solution (2026-08-13)

Two documents describe **today's fulltext solution built on plain Lucene**, so that it can be verified that
the new engine will not be a step back in any capability. They are not templates to be copied — they are
**coverage yardsticks**:

- The existing Edee CMS fulltext client (internal analysis, **not published in this repository**) — the
  libraries over Lucene 9.12.1: analyzers, the indexing and query pipeline, the operational layer. Its §6
  maps every capability of the old client onto a place in our plans and names three uncovered items (promo
  curation, protecting a term from analysis, splitting a word and a number) plus the phase dependency in
  vectors.
- The existing e-commerce fulltext layer (internal analysis, **not published in this repository**) — how
  the e-shop builds on
  that client: a flat text blob instead of weighted fields, prefix and infix emulated by writing
  substrings, filters outside the engine and a window of 1000 hits.

### Delivery phases

- **F1 — text:** analyzers (P5) + the structures of §4.2 + rank profiles, the boost channel and feature
  export of §4.3 (P7) + prefix/typo + suggester + highlighting at render time. No positions, no WAND.
- **F2 — vectors and hybrid:** §5 including RRF fusion — moved ahead of proximity, because the hybrid
  (text × vector × visual) is the core of the Sage experiment (§1.1); the JDK 21 condition is satisfied.
- **F3 — phase 2 re-rank:** the proximity lane via re-rank (P4) + a behavioural/LTR re-rank over the
  feature vectors of the top-K; full BM25F for the long texts of the CMS profile (Z8; planned, no longer
  conditional) — the path without stored statistics is described in §4.2 (IDF from postings cardinality, a
  pivot instead of `avgFieldLength`); the scoring expansion across references (§1.4, O10) — a candidate for
  being pulled forward for the CMS profile with content blocks.

---

## 8. Verification of hypotheses against source code (2026-08-11 to 2026-08-13)

The claims of §2–§4 were verified against local checkouts in `/www/oss`: meilisearch 1.53.0 (main,
`594f0e59d`), typesense v31 (`ee7784f3`), vespa 8 (master, `780f10016`), lucene main (= 11-dev,
`13796f80e`; the 9.12/10.0 branches available in the clone), solr main. In the fourth round (2026-08-13)
elasticsearch main (9.6.0-SNAPSHOT, `9a100e2d0e41`) and OpenSearch main (3.9.0-SNAPSHOT, `36edc05ac84`)
were added — see the last block of this section. The design's load-bearing claims are **confirmed**; the
inaccuracies found (VK1–VK8) are incorporated directly in the text and summarized below. Concrete paths for
drill-down are in the Sources section.

**Confirmed (load-bearing points, with their place in the code):**

- **Meilisearch = the existence proof holds.** Postings are `Str → RoaringBitmap` in LMDB
  (`crates/milli/src/index.rs:140`, `word_docids`); bucket sort slices the universe with set operations
  (`bucket_sort.rs:298`: `universe -= bucket`; the invariant bucket ⊆ universe is asserted); the score
  arises only from the order of buckets (`score_details.rs`) — the single docFreq primitive
  (`word_documents_count`) has not a single caller in the whole repo. Facets receive the full candidate
  bitmap (`facet_distribution.rs`). Bonus: bitmaps of ≤ 7 ids are stored as a plain array of ints (the CBO
  codec) — the same small-set pattern our bitmap layer knows.
- **Typesense = the composite template holds.** The score is a bit-packed integer compared as a single
  number, and even in two layers: a 48-bit per-field one (words, unique, 255−typo, 100−proximity, exact,
  offset, synonym; `include/match_score.h:56`) packed into a 64-bit cross-field word (tokens, field score,
  field weight, number of fields; `src/index.cpp:5417`). Grepping for bm25/idf/docfreq: zero hits in
  scoring; the only frequency statistic ranks the candidates of the typo/prefix expansion
  (`rank_tokens_by=FREQUENCY`) — the same principle by which our suggester scores (§4.6). In hybrid mode
  Typesense replaces the composite with an RRF value — our §5.5 does the same.
- **Vespa = phasing holds.** First-phase scores every match right inside the match loop
  (`match_thread.cpp:172`), second-phase re-ranks the top-K from a min-heap (`hitcollector.h`), the default
  `rerank-count` = 100 (`indexproperties.cpp:699`). Match-phase degradation is a separate safeguard
  (`match_phase_limiter.h`). Vespa keeps attributes for ranking in memory and reads postings by mmap from
  immutable files — the same split as proposed in §5.3.
- **Lucene = the parts worth taking hold.** `LevenshteinAutomata` has three imports and zero ties to the
  index; `CzechAnalyzer` imports analysis classes exclusively; BM25 is the default (`IndexSearcher:123`);
  the length norm is 1-byte with a 256-entry decoding table (`SmallFloat`, `BM25Similarity:149`) — a direct
  precedent for the impact byte of §4.2.

**Corrections (VK1–VK8, incorporated in the text):**

- **VK1 (§2.1):** Meilisearch's default cascade has 7 rules today — `attribute` split into `attributeRank`
  (before `sort`) and `wordPosition` (after `sort`); `criterion.rs:121`.
- **VK2 (§2.2):** The maximum indexed proximity distance is 3, lowered from 7 in October 2023 because of
  the cost of the pair database (`proximity.rs:7`); the code does not directly claim "the most expensive
  database", but two concessions (the reduced range, the `ByAttribute` opt-out) evidence it indirectly.
- **VK3 (§2.1):** Typesense has **3 sort slots in total** and `_text_match` occupies one of them
  (`topster.h:28` `scores[3]`, `collection.cpp:2456`) — not "3 sort_by fields after the score".
- **VK4:** Typesense's postings are not roaring — they are FOR-compressed block chains behind a per-field
  ART dictionary (`posting_list.h`). The roaring-postings model is evidenced by Meilisearch; Typesense
  evidences the composite, not bitmap algebra.
- **VK5 (§2.3):** Since Vespa 8 (2022) the default for text queries is **weakAnd** (targetHits 100;
  `Model.java:93`) — full-set mode applies to filters and structured queries. Our conclusion about WAND
  rests on Z7 and on volumes, not on Vespa's default.
- **VK6 (§3, §4.6):** `LevenshteinAutomata` has a hard cap of distance 2 (`MAXIMUM_SUPPORTED_DISTANCE`);
  `CompiledAutomaton` (the dictionary intersection) is already bound to the index API — we write the
  dictionary intersection ourselves, with `FuzzyAutomatonBuilder` showing the bridge.
- **VK7 (§3):** `PolishAnalyzer` lives in a separate artifact `lucene-analysis-stempel`; `SlovakAnalyzer`
  does not exist in Lucene (sk = Hunspell / an in-house pipeline); the `analysis-common` module depends on
  the whole `lucene-core` jar — the analyzers are index-free, the artifact is not.
- **VK8 (§3):** Lucene's JDK lines: 9.12.x = JDK 11+, 10.x = JDK 21, main (11-dev) = JDK 25. With the JDK
  21 baseline (Z1) the 10.x line is usable too; the choice of the exact line is a detail of P5. Context:
  Lucene's default block-max path today is not WANDScorer but MAXSCORE (`MaxScoreBulkScorer`); WANDScorer
  remains as a fallback for `minShouldMatch > 1`.

**Second round (query understanding, 2026-08-11):** where understanding of the query lives — synonyms,
entities, curator rules, boost tables. VK9–VK12 verified locally in source code; VK13 (Elasticsearch) arose
only through a web search and was **flipped to source code on 2026-08-13** (see the fourth round below),
VK14 (Algolia) remains web-based — treat its version numbers with reservation. The summary pattern
underpinning §1.2 and §1.3: dictionary/rule-based understanding of the query is engine-side everywhere, as
hot-swap data; model inference over the query does not exist in any engine (the single exception:
embeddings).

- **VK9 — Vespa (§1.2, §1.3):** engine-side rule-based query rewriting (`com.yahoo.prelude.semantics`):
  "condition → action" productions (`[brand] :- sony, dell;` + `[brand] -> brand:[brand];`), multi-word and
  recursive lists, boost-only terms ($), negations, stopword removal; the condition dictionary can be
  supplied as a compiled FSA automaton and **hot-reloaded** (`RuleBase.setAutomataFile`). Without supplied
  rules the mechanism is inactive. Query-time embeddings are computed by the container (the `Embedder`
  interface, ONNX implementations in `model-integration`) — model inference in the engine exists only for
  embeddings, not for NER. Custom `Searcher` chains = a general extension point for query intelligence.
- **VK10 — Typesense:** synonyms = global named sets, multi-word span matching (trie + DP,
  `synonym_index.cpp`), hot-swap without reindexing. Curation rules can pin/hide and, above all,
  `dynamic_query`: the placeholder `{brand}` binds to a query token **only if it is a genuinely indexed
  value of the brand field** (verified against its own ART index, `check_for_curations`) — engine-side
  entity detection without ML; `remove_matched_tokens` optionally removes the token from the text search.
  Plus an opt-in LLM path "natural language → query parameters" (a call to an external model,
  `natural_language_search_model.h`).
- **VK11 — Meilisearch:** synonyms are an index setting applied at query time; changing them is **not**
  among the triggers of reindexing. Multi-word keys only up to the width of the ngram window.
  Query-conditioned rules (dynamic search rules) can **only pin** — no injection of a filter from the query
  text. The LLM chat path does generate filters, but their author is the model, not the engine.
- **VK12 — Lucene/Solr:** `SynonymMap` = an FST dictionary of phrases (words joined by `\0`),
  `SynonymGraphFilter` does longest-match span expansion — the canonical gazetteer mechanics; two caveats
  for reuse: greedy matching from every position (not Aho-Corasick, overlaps unhandled) and an immutable
  FST (hot-swap = rebuild + swap). The index-time and query-time analyzers **differ deliberately**
  (synonyms only in the query chain) — agreement is on the produced terms. Solr: managed synonyms over REST
  require a core reload; `QueryElevationComponent` matches the analyzed query (exact/subset) → pin/exclude
  ids; the LTR feature/model store hot-swaps **without a reload** (the model is resolved per query). NER
  exists in both repos **index-side only** (a Solr update processor over documents); zero occurrences in
  the query path.
- **VK13 — Elasticsearch (verified against source code 2026-08-13, commit `9a100e2d0e41`):** the Synonyms
  API — server-side sets, changed without reindexing (`SynonymsManagementAPIService`); Query Rules — a
  curator table in the engine, but the triggering criteria are declared by the client in every request, and
  mandatorily so: a missing or empty `match_criteria` map is an error (`RuleQueryBuilder.java:119`);
  `semantic_text` — the server computes embeddings at indexing and at query time, the client sends raw text
  (`SemanticQueryBuilder`). Version numbers cannot be verified from the code, but all three mechanisms exist
  in the repository and work as the item describes. Three corrections against the web round: **(a) the
  formulation about NER** — `NerProcessor` is in the repository, but it is a general NLP inference task over
  text available through the inference API as well as the ingest pipeline, so the precise wording is not
  "only an ingest processor over documents" but **not wired into the query path at all**; the only model
  inference a query triggers is computing an embedding — which confirms this round's summary pattern rather
  than weakening it. **(b)** Synonyms carry the binding `updateable` → `AnalysisMode.SEARCH_TIME` enforced
  by the analyzer type system (§4.6); that is the most valuable detail of the whole item and had been
  missing from it. **(c)** Part of `semantic_text` is **built-in chunking** with per-chunk embeddings and
  offsets — more significant for the CMS profile (Z8) than server-side inference itself, see the open
  question in §5.6.
- **VK14 — Algolia (web):** Rules match the query string server-side; the `{facet:}` placeholder +
  automaticFacetFilters = engine-side conversion of a word into a facet filter — the word is, however, **not
  removed** from the query by default (a separate "Remove Word" consequence). Synonyms per index, hot-swap.
  Dynamic Re-Ranking: a boost table (query, record) learned from clicks/conversions (a 30-day window,
  recomputed ~24 h), applied in the engine — a direct precedent for the boost map (§4.3, O8). Algolia
  handles long documents by splitting them into sections/paragraphs as separate records (the pattern of its
  own DocSearch, record size limits) — relevant for Z8: the market's answer to long text is chunking, not
  BM25.

**Third round (matching across references / joins, 2026-08-12):** how engines solve "a match in a
referenced document affects the referencing document" (§1.4). VK15–VK19 verified locally in source code;
VK20 arose only through a web search (WebFetch blocked in the sandbox, everything from result snippets) and
its **Elasticsearch half was flipped to source code on 2026-08-13** — the Algolia half remains web-based,
verbatim quotations to be treated with reservation. The summary pattern: filtering across a reference is
common, aggregated relevance is rare and expensive, and a persistent incrementally maintained
cross-document relevance edge exists nowhere.

- **VK15 — Lucene (§1.4):** a block join requires a contiguous block (children first, parent last;
  `ToParentBlockJoinQuery` JavaDoc) and `CheckJoinIndex` rejects an index with mismatched liveness of parent
  and children — the practical rule being "a change of a child = reindex the block". Query-time `JoinUtil`
  does carry relevance (5 ScoreModes incl. Min), but the JavaDoc says explicitly: all unique join values on
  the heap per query, and for a multi-valued join field the score of the *first* occurrence is mapped "even
  in the case when a second join value … yields a higher score". The global-ordinals variant has a cap of 1
  target per document and a same-reader guard.
- **VK16 — Solr:** `{!join}` is by default a `ConstantScoreWeight` (a pure filter); `score=…` delegates to
  JoinUtil with a hard-coded `multipleValuesPerDocument=true` (first-encountered always applies, even for
  `score=max`) and works only for cores in the same JVM / a single-shard collection;
  `method=crossCollection` goes over the network but never carries a score — distance and relevance are
  mutually exclusive. `{!parent score=…}` aggregates correctly, but only over physical blocks (`_root_`, a
  rewrite of the whole tree on update). `{!graph}` has a hard-coded score of 1.
- **VK17 — Vespa:** parent-child via `reference<type>` + `import field` — an imported field behaves as a
  native **attribute** (filtering, ranking, grouping, summary; chaining across generations works). The
  parent must be `global` = replicated on all content nodes (a broadcast join, validated at deploy).
  Importing an indexed (fulltext) field is rejected in three layers (schema validation "Is an index field.
  Not supported", derived config without tokenization, a C++ read guard) — a parent's values into the score
  yes, a parent's textual match never; an imported tensor cannot be used for nearestNeighbor.
- **VK18 — Typesense:** a join = a set operation over ids (a filter on the second collection + translation
  through the index-time materialized `<field>_sequence_id` helper index); `query_by` on a reference returns
  400 "Query by reference is not yet supported"; there is no scorer on the join path. The only scoring
  channel: `sort_by $Coll(_eval(filter):desc)` — a predicate tier with a manually assigned constant, a
  multi-valued reference collapsing to first-match.
- **VK19 — Meilisearch:** since 1.53 an experimental "document join" — the `foreignKeys` setting +
  `_foreign(fk, filter)`: an eager semi-join rewritten into a materialized IN list, a cap of 1000 documents,
  one level, **filtering only** — the core (milli) does not see the join (`IndexFilterCondition` has no
  Foreign variant); hydration of the response runs only after ranking. A federated multi-search = fusion of
  the results of independent queries, not a join.
- **VK20 — ES (verified against source code 2026-08-13, commit `9a100e2d0e41`) + Algolia (web):** ES is the
  only one that aggregates child relevance into the parent (`has_child` + `score_mode` avg/max/min/sum;
  default `none` = parent score 0 and the parameter is mandatory, `HasChildQueryBuilder.java:67`), one join
  field per index (`ParentJoinFieldMapper.java:55`), `nested` = hidden documents in a single block written
  "children before their parent", so a change of a child rewrites the whole block
  (`DocumentParserContext.java:1035`; moreover `include_in_parent` cannot be changed on an existing nested
  mapping). Two refinements against the web round: **eager global ordinals are not an optional tax but the
  default setting of a join field** (`ParentJoinFieldMapper.java:87–92`, default `true` — for an ordinary
  `text` field the default is conversely `false`, `TextFieldMapper.java:292`), because without precomputation
  after every refresh the join would be unbearably slow; and **same-shard routing is an entry condition, not
  a consequence** — a join field is rejected on an index with `routing_partition` or `routing_path` already
  at creation (`checkIndexCompatibility`). The tuning guide's claim ("nested several times, parent-child
  hundreds of times slower — denormalize") is documentation and cannot be verified in source code; the code
  nevertheless supports it indirectly through exactly those three mechanisms. Algolia (web, unchanged): no
  join; the record limit (10/100 kB depending on plan) forces chunking and `distinct`/`attributeForDistinct`
  reassembles the result **by picking the best record of the group** — sibling evidence is not aggregated.

**Fourth round (the server layer above Lucene, 2026-08-13):** what a server adds above the Lucene library,
which §3 handles separately. Verified against the local checkouts `/www/oss/elasticsearch` (main,
9.6.0-SNAPSHOT, commit `9a100e2d0e41`) and `/www/oss/OpenSearch` (main, 3.9.0-SNAPSHOT, commit
`36edc05ac84`). The findings are incorporated directly in the text: §2.1 (the switched-off DFS phase and the
cost of segment replication), §4.5 (durability vs. visibility, star-tree, `WAIT_UNTIL`), §4.6
(`AnalysisMode` for synonyms, `OffsetSource.ANALYSIS` for highlighting), §4.7 (`match_only_text`) and §5
(quantization thresholds, oversampling, the two filtered-ANN strategies, chunking). The summary pattern of
this round: most of the complexity of the server layer is a direct consequence of three assumptions evitaDB
does not share — sharding, immutable segments and corpus scoring. The most valuable insight therefore often
reads "this problem does not arise for us at all".

- **VK21 — OpenSearch:** verified in the core that `SearchType.DEFAULT = QUERY_THEN_FETCH`
  (`SearchType.java:62`); that segment replication is opt-in and immutable after index creation
  (`IndexMetadata.java:357`, `:360`, `Property.Final`); that the star-tree as a side structure next to the
  inverted index is built exclusively at flush and merge and cannot be added to an existing index
  (`CompositeIndexValidator.java:37`); that concurrent segment search does not change the score, because
  statistics are read from the top-level reader, but that it introduces a new error in term aggregations
  through pruning at slice level (`InternalTerms.java:467`); and that `match_only_text` exists here too
  (`SourceFieldMatchQuery.java:101`). **A scope caveat that must be maintained:** k-NN, neural-search
  (hybrid search and the normalization processor), learning-to-rank and query insights are **separate
  plugins outside this checkout**. Only the seam by which they hook into the core is evidenced for them
  (`SearchPhaseResultsProcessor` for fusion, `FieldTypeCapabilities` for vectors), not their internals.
  §5.5 therefore **must not cite OpenSearch as a precedent for RRF fusion**; neither the normalization
  algorithms (min-max, L2) nor the matrix of supported vector engines can be evidenced from this checkout.
  The same holds for the claim that scores diverge between replicas under document replication — that is
  general knowledge about Lucene's behaviour, not a finding from the checkout; what is anchored and usable
  without reservation is only the non-default status of the DFS phase and the opt-in nature of segment
  replication.

---

## Sources (consolidated)

**State of the art (ranking, engines):**
- [Algolia: The eight ranking criteria][s1]; [Inside the engine — textual relevance][s2]
- [Meilisearch: Built-in ranking rules][s3]; [Bucket sort — a practical guide][s4];
  [How full-text search engines work][s5] (roaring bitmaps as postings)
- [Typesense: Ranking and relevance][s7] (the components of `_text_match`)
- [Vespa: Phased ranking][s8]

**Query understanding (VK13–VK14; web only, without reading source code):**
- [Elasticsearch: Synonyms API][e1]; [Query Rules][e2]; [semantic_text][e3]
- [Algolia: Rules — dynamic filtering][a1]; [Dynamic Re-Ranking][a2]

**References / joins (VK20; web only, without reading source code):**
- [Elasticsearch: parent-join][e4]; [has_child query][e5]; [Tune for search speed][e6]
- [Algolia: Handle data relationships][a3]; [Split long pages into records][a4];
  [distinct][a5]

**Typo/prefix:** Schulz & Mihov: *Fast string correction with Levenshtein automata* (2002);
[SymSpell][s9]

**Vectors:** Patel et al.: *ACORN* (SIGMOD 2024); Gollapudi et al.: *Filtered-DiskANN* (WWW 2023);
Gao & Long: *RaBitQ* (SIGMOD 2024); [Elastic BBQ][r10]; [jVector][s10];
Cormack, Clarke & Büttcher: *Reciprocal Rank Fusion* (SIGIR 2009)

**Lucene / JDK:** [Lucene 10.0 System Requirements][r1] (min. JDK 21);
[endoflife.date — Apache Lucene][r2]; [JEP 454: Foreign Function & Memory API][j1] (final in JDK 22)

**Internal:**
- [`background/elasticsearch.md`](background/elasticsearch.md),
  [`background/opensearch.md`](background/opensearch.md) — the full studies of both engines' server layer
  (2026-08-13); the load-bearing findings are incorporated in the text, the studies carry the complete
  anchors and details. Separate studies do not exist for the other engines deliberately: Lucene, Solr,
  Meilisearch, Typesense and Vespa were studied in parallel with writing this research, so their
  "background" is its text directly (the versions and commits of the code examined are in §8);
  Elasticsearch and OpenSearch were added on top of a finished version 2, which is why input studies
  remained after them as files
- An internal analysis of the existing Edee CMS fulltext client over plain Lucene (**not published in
  this repository**); a yardstick of capability coverage, not a template to adopt (§7)
- An internal analysis of the e-commerce layer over the same client (**not published in this
  repository**); same purpose
- `/www/oss/Sage/docs/analysis/query-ast-portability.md` — the query AST, the limits of portability onto
  Lucene-as-library, a proposal for an engine-neutral AST (§1.1)
- `/www/oss/Sage/docs/analysis/behavioural-ranking-platform.md` — 11 behavioural algorithms and where they
  attach at query time; the source of the requirement for dynamic boosts and feature export
- `/www/oss/Sage/docs/analysis/golden-set-analyzer.md`, `search-comparison-final.md` — the evaluation
  harness for the P1/P2 gate (§7)
- `documentation/adr/2026-07-07-roaring-bitmap-vendoring.md` — the precedent and cost of vendoring
- `evita_engine/src/main/java/io/evitadb/index/invertedIndex/` — today's inverted index (without tf)
- `io.evitadb.index.bPlusTree.FrontCodedStringColumn`, `io.evitadb.index.bitmap.TransactionalBitmap`
- `io.evitadb.core.query.sort.price.FilteredPricesSorter` — the precedent of a sort by computed values
- `io.evitadb.core.query.filter.translator.attribute.AbstractAttributeStringSearchTranslator` — today's
  NFD substring baseline
- `io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor` — the extra results pipeline; the anchor
  for annotating recognized entities in the response (§1.3)
- `io.evitadb.api.query.filter.UserFilter` — the container of user filters; the anchor for applying an
  offered facet filter (§1.3)
- [issue #23 — Spatial indexes and constraints](https://github.com/FgForrest/evitaDB/issues/23) — geo
  primitives for the `GeoDistance` node (§4.4)

**Drill-down into the engines' source code (local checkouts `/www/oss`, versions see §8):**

*Meilisearch (`/www/oss/meilisearch`):*
- `crates/milli/src/index.rs:98-300` — the whole on-disk model: every LMDB database, key and codec
- `crates/milli/src/search/new/bucket_sort.rs` — the complete ranking engine (universe stack)
- `crates/milli/src/search/new/ranking_rules.rs` — the `RankingRule` contract (bucket ⊆ universe)
- `crates/milli/src/criterion.rs` — the rule dictionary, `default_criteria()`, the attributeRank split
- `crates/milli/src/score_details.rs:440-545` — `_rankingScore` from bucket order only
- `crates/milli/src/update/new/extract/searchable/` — the live index-time extractors (amplification)
- `crates/milli/src/heed_codec/roaring_bitmap/cbo_roaring_bitmap_codec.rs` — the small-set codec
- `crates/milli/src/search/facet/facet_distribution.rs` — facets over the candidate bitmap
- `crates/milli/src/search/new/db_cache.rs` — which DBs a query reads and in what order
- `crates/milli/src/search/new/query_term/{parse_query,compute_derivations}.rs` — synonym expansion at
  query time (VK11)
- `crates/meilisearch-types/src/dynamic_search_rules.rs` — pin-only rules (VK11)
- `crates/meilisearch/src/documents_retrieval/preprocessing.rs` — the `_foreign` semi-join, the cap of 1000
  documents, the rewrite into an IN list (VK19)
- `crates/filter-parser/src/lib.rs:312-341` — `IndexFilterCondition` without a Foreign variant: the engine
  core never sees the join (VK19)

*Typesense (`/www/oss/typesense`):*
- `include/match_score.h:56-68` — the 48-bit lane layout; `:129-275` sliding-window proximity
- `src/index.cpp:5300-5455` — the roll-up of per-field scores into a 64-bit word (an ASCII diagram of the
  layout)
- `src/collection.cpp:4876-4906` — decoding `_text_match` (a cross-check of the bit offsets)
- `include/topster.h` — `scores[3]` and the lexicographic comparison for the top-K heap
- `include/art.h` + `include/posting_list.h` — the ART dictionary with max_score pruning, FOR postings
- `include/synonym_index.h` + `src/synonym_index.cpp` — trie + DP span matching of synonyms (VK10)
- `include/curation.h` + `src/index.cpp:2952-3205` — dynamic_query, `check_for_curations` (VK10)
- `src/join.cpp` + `src/filter_result_iterator.cpp:1173-1260` — the reference filter, id translation
  through `<field>_sequence_id` (VK18)
- `src/collection.cpp:2807-2816` — the 400 for `query_by` on a reference (VK18)

*Vespa (`/www/oss/vespa`):*
- `searchcore/src/vespa/searchcore/proton/matching/match_thread.cpp` — both phases in one file
- `searchcore/src/vespa/searchcore/proton/matching/match_params.cpp` — how rerank-K comes about
- `searchlib/src/vespa/searchlib/queryeval/hitcollector.h` — the top-K min-heap, setReRankedHits
- `searchlib/src/vespa/searchlib/fef/indexproperties.cpp` — the registry of all `vespa.*` knobs
- `searchcore/src/vespa/searchcore/proton/matching/match_phase_limiter.h` — match-phase degradation
- `searchlib/src/vespa/searchlib/queryeval/wand/weak_and_search.cpp` — the weakAnd threshold mechanics
- `container-search/src/main/java/com/yahoo/search/query/Model.java:93` — WEAKAND as the default
- `container-search/.../prelude/semantics/{RuleBase,SemanticSearcher}.java` — rule-based query rewriting;
  the benchmark rule base `prelude/semantics/benchmark/rules.sr` (VK9)
- `container-search/.../prelude/querytransform/PhrasingSearcher.java` + `fsa/.../FSA.java` — FSA span
  matching of phrases (VK9)
- `linguistics/.../language/process/Embedder.java`, `model-integration/.../ai/vespa/embedding/` —
  in-container query embeddings (VK9)
- `config-model/.../schema/processing/ImportedFieldsResolver.java:177-189` — attribute-only validation of
  imports ("Is an index field. Not supported", VK17)
- `config-model/.../content/GlobalDistributionValidator.java` — parents must be global (VK17)
- `searchlib/src/vespa/searchlib/attribute/imported_attribute_vector.h` — the LID→LID indirection of an
  imported attribute (VK17)

*Lucene (`/www/oss/lucene`, main; the 9.12/10.0 branches in the clone):*
- `lucene/core/src/java/org/apache/lucene/util/automaton/LevenshteinAutomata.java` — the DFA, cap 2
- `lucene/core/src/java/org/apache/lucene/search/FuzzyAutomatonBuilder.java` — string → automaton
- `lucene/core/src/java/org/apache/lucene/search/FuzzyTermsEnum.java:178-187` — the intersection loop
- `lucene/analysis/common/src/java/org/apache/lucene/analysis/cz/CzechAnalyzer.java` — index-free
- `lucene/analysis/common/.../analysis/custom/CustomAnalyzer.java` — a builder pipeline without SPI
- `lucene/core/src/java/org/apache/lucene/util/SmallFloat.java:147` — 1-byte quantization of the norm
- `lucene/core/.../search/similarities/BM25Similarity.java:149-153` — the 256-entry decoding table
- `lucene/core/src/java/org/apache/lucene/codecs/CompetitiveImpactAccumulator.java` — the core of impacts
- `lucene/core/src/java/org/apache/lucene/search/MaxScoreBulkScorer.java` — today's block-max path
- `lucene/suggest/.../suggest/analyzing/FuzzySuggester.java` — fuzzy over an FST (the suggester analogy)
- `lucene/analysis/common/.../analysis/synonym/{SynonymMap,SynonymGraphFilter}.java` — the FST phrase
  dictionary and longest-match span expansion (VK12)
- `lucene/join/.../search/join/{ToParentBlockJoinQuery,CheckJoinIndex,JoinUtil}.java` — block join, block
  validation, query-time join + ScoreMode and the memory JavaDoc (VK15)

*Elasticsearch (`/www/oss/elasticsearch`, main 9.6.0-SNAPSHOT, `9a100e2d0e41`):* paths are relative to the
checkout root, the intermediate `server/src/main/java/org/elasticsearch/` abbreviated to `…/`.
- `…/action/search/SearchType.java:36` — `DEFAULT = QUERY_THEN_FETCH`; `…/search/dfs/DfsPhase.java` — what
  the DFS phase costs (collecting statistics, `AggregatedDfs`)
- `…/index/IndexSettings.java:117`, `:311`, `:320` — translog durability and the refresh interval;
  `…/action/support/WriteRequest.java:53` — `RefreshPolicy` including `WAIT_UNTIL`
- `…/index/analysis/AnalysisMode.java` + `modules/analysis-common/…/SynonymTokenFilterFactory.java`
  (line 199) — the `updateable` → `SEARCH_TIME` binding; `…/index/mapper/TextParams.java:33` — the three
  analyzer slots (indexing, query, phrase)
- `…/index/mapper/FieldMapper.java:1006`, `:1030`, `:1874` — `updateable` on a parameter and the conflict
  accumulator; `…/index/mapper/MapperService.java:64`, `:151` — `MergeReason`, the field limit
- `modules/mapper-extras/…/MatchOnlyTextFieldMapper.java` + `…/SourceConfirmedTextQuery.java` — the
  position-free field and phrase confirmation by re-analysis (§4.7)
- `…/search/fetch/subphase/highlight/DefaultHighlighter.java:248` — `OffsetSource.ANALYSIS`
- `…/index/mapper/vectors/DenseVectorFieldMapper.java:159`, `:199`, `:294`, `:295` — `BBQ_MIN_DIMS`, the
  ACORN heuristic, `DEFAULT_OVERSAMPLE`, the 384-dimension threshold; post-filtering is
  `…/search/vectors/PostFilterKnnQuery.java`
- `modules/parent-join/…/mapper/ParentJoinFieldMapper.java:55`, `:87–92` — one join field, eager global
  ordinals default `true`; `…/index/mapper/TextFieldMapper.java:292` — the same, `false`
- `x-pack/plugin/inference/…/mapper/SemanticTextFieldMapper.java:262`, `:276` — chunks inside the field
  type; `x-pack/plugin/ml/…/inference/nlp/NerProcessor.java` — NER outside the query path

*OpenSearch (`/www/oss/OpenSearch`, main 3.9.0-SNAPSHOT, `36edc05ac84`):* the intermediate
`server/src/main/java/org/opensearch/` abbreviated to `…/`. Note: k-NN, neural-search, LTR and query
insights are plugins **outside this checkout** — only the seam is evidenced for them (see §8, VK21).
- `…/action/search/SearchType.java:62` — `DEFAULT = QUERY_THEN_FETCH`
- `…/cluster/metadata/IndexMetadata.java:357`, `:360` — segment replication is opt-in and `Property.Final`,
  i.e. immutable after index creation
- `…/index/compositeindex/datacube/startree/builder/StarTreesBuilder.java:77`, `:114` — star-tree only at
  flush and merge; `…/index/compositeindex/CompositeIndexValidator.java:37` — the prohibition on change
- `…/search/startree/StarTreeQueryHelper.java:53` — the gate and the silent fall back to normal evaluation
- `…/search/internal/ContextIndexSearcher.java:555`, `:570` — statistics from the top-level reader;
  `…/search/aggregations/bucket/terms/InternalTerms.java:467` — pruning at slice level
- `…/index/mapper/MatchOnlyTextFieldMapper.java` + `…/index/query/SourceFieldMatchQuery.java:101`
- `…/search/pipeline/SearchPhaseResultsProcessor.java:21` — the seam where score fusion is hung
- `…/index/engine/dataformat/FieldTypeCapabilities.java` — the catalog of data-format capabilities

*Solr (`/www/oss/solr`):* a server over released Lucene 10.4.0 artifacts (`gradle/libs.versions.toml:42`).
- `core/.../handler/component/QueryElevationComponent.java` — analyzed match → pin/exclude
- `modules/ltr/.../store/rest/{ManagedFeatureStore,ManagedModelStore}.java` — hot-swap without a reload,
  resolve per query (VK12)
- `core/.../rest/schema/analysis/ManagedSynonymGraphFilterFactory.java` — REST synonyms (requiring a core
  reload)
- `core/.../search/JoinQParserPlugin.java` + `search/join/ScoreJoinQParserPlugin.java` — the `{!join}`
  constant-score default and the scoring variant (VK16)
- `solr-ref-guide/.../pages/{join-query-parser,indexing-nested-documents}.adoc` — the limits of joins and
  the update semantics of nested documents (VK16)

[s1]: https://www.algolia.com/doc/guides/managing-results/relevance-overview/in-depth/ranking-criteria
[s2]: https://www.algolia.com/blog/engineering/inside-the-algolia-enginepart-4-textual-relevance
[s3]: https://www.meilisearch.com/docs/capabilities/full_text_search/relevancy/ranking_rules
[s4]: https://www.meilisearch.com/blog/bucket-sort-guide
[s5]: https://www.meilisearch.com/blog/how-full-text-search-engines-work
[s7]: https://typesense.org/docs/guide/ranking-and-relevance.html
[s8]: https://docs.vespa.ai/en/phased-ranking.html
[s9]: https://github.com/wolfgarbe/SymSpell
[s10]: https://github.com/datastax/jvector
[r1]: https://lucene.apache.org/core/10_0_0/SYSTEM_REQUIREMENTS.html
[r2]: https://endoflife.date/apache-lucene
[r10]: https://www.elastic.co/search-labs/blog/better-binary-quantization-lucene-elasticsearch
[j1]: https://openjdk.org/jeps/454
[e1]: https://www.elastic.co/docs/solutions/search/full-text/search-with-synonyms
[e2]: https://www.elastic.co/docs/reference/elasticsearch/rest-apis/searching-with-query-rules
[e3]: https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/semantic-text
[a1]: https://www.algolia.com/doc/guides/managing-results/rules/detecting-intent/
[a2]: https://www.algolia.com/doc/guides/algolia-ai/re-ranking
[e4]: https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/parent-join
[e5]: https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-has-child-query
[e6]: https://www.elastic.co/docs/deploy-manage/production-guidance/optimize-performance/search-speed
[a3]: https://www.algolia.com/doc/guides/sending-and-managing-data/prepare-your-data/how-to/handling-data-relationships
[a4]: https://www.algolia.com/doc/guides/sending-and-managing-data/prepare-your-data/how-to/indexing-long-documents
[a5]: https://www.algolia.com/doc/api-reference/api-parameters/distinct
