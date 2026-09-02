# Fulltext in evitaDB — basis for a decision on prototyping an in-house solution

> **Audience:** management. **Purpose:** decide whether to start a bounded prototyping phase for an
> in-house fulltext core in evitaDB, with an Apache Lucene integration as the alternative and an
> external service (Sage / an external engine) as the fallback position.
>
> **Sources:** the technical research [`research.md`](research.md) (v2, consolidated; its load-bearing
> claims verified directly against the source code of Meilisearch, Typesense, Vespa, Lucene, Solr and,
> since 2026-08-13, Elasticsearch and OpenSearch). Plus two analyses of today's solution built on plain
> Lucene — the Edee CMS fulltext client and its e-commerce layer, analysed internally and **not
> published in this repository** — as the yardstick for what a new solution
> has to cover. This document summarizes and refers; the argument in full depth lives there.

---

## Summary for the decision

The research recommends **prototyping an in-house fulltext core** built on evitaDB's existing bitmap
algebra. Not because Lucene is a bad library — it is outstanding at what it was built for — but
because the centre of gravity of its value lies elsewhere than what search in evitaDB actually needs.
And we would pay the integration price in full, no matter how small a part of Lucene we ended up using.

Three safeguards turn this intent into a managed investment rather than a bet:

1. **This is not "rewriting Lucene".** The most valuable and simultaneously most laborious part of
   Lucene — linguistic analysis, i.e. tokenization, stemming and Czech support — is taken from Lucene
   as an ordinary Maven dependency. Most of the remaining infrastructure Lucene would bring (index
   storage, transactions, persistence, filtering, facets) evitaDB already has, and Lucene would merely
   duplicate it. What is genuinely new are three bounded data structures and one way of ordering
   results. Details in chapter 3.
2. **The decision gate stands before the main investment.** Three prototypes with numeric criteria
   (memory consumption, latency, the tax on the write path, result quality against a Solr baseline)
   decide "continue / do not continue" before delivery work starts. The prototypes moreover build the
   data structures of the target design, not throwaway mock-ups — memory and maintenance criteria
   cannot even be measured on a mock-up. The gate therefore also yields a real basis for estimating
   the remaining effort. Details in chapter 4.
3. **We have somewhere to retreat to.** The fallback position — fulltext in an external engine running
   alongside evitaDB — exists and is described. Failing the gate therefore does not mean a lost year of
   development, but a return to today's state. Details in chapter 2.

One honest note up front: there used to be a strong practical argument against Lucene — its current
10.x line requires JDK 21, whereas evitaDB is on JDK 17. The upgrade to JDK 21 was approved but has not
landed in the development branch yet; in practice this is handled by starting on the Lucene 9.12.x
line, whose API does not differ from 10.x for our purposes. So this is not an argument against Lucene —
the decision rests purely on architecture, which makes the reasoning below all the more important.

---

## 1. Lucene: what it gives, what it complicates — and why it is not the benchmark

### 1.1 What Lucene would realistically give us

An honest list, without understating anything:

- **The analysis chain.** Tokenizers, stemmers for dozens of languages including Czech
  (`CzechAnalyzer`), normalization via ICU. It is Lucene's best-tested value and precisely the part we
  do not want to write again. At the same time it is separable from the rest: it takes text on input,
  returns a stream of tokens on output, and has no tie to the index itself.
- **A ready-made feature bundle.** Phrase and proximity queries, highlighting of matched words,
  suggesters, typo tolerance built on Levenshtein automata.
- **A mature core.** Compressed index storage, algorithms for fast top-K retrieval (block-max WAND /
  MAXSCORE — able to skip large parts of the index as soon as it is clear the documents in question
  cannot reach the first page of results), BM25 scoring, an HNSW index for vector search, and
  segment-wise index replication.

### 1.2 Where the integration into evitaDB grinds

The core of the problem is the **query model**. evitaDB evaluates every query as set algebra over
bitmaps: first the *complete* set of matching products is produced, and only over it are facet counts,
histograms and pagination computed. Fulltext has to enter this algebra as one more condition alongside
hierarchy, prices and facets. Three possible shapes exist for wiring Lucene in — and each hurts
differently:

1. **Take only the top-K results from Lucene and post-filter them in evitaDB.** A naive and tempting
   shape that does not work. How many of those K results survive our filters (stock, price lists,
   categories) cannot be known in advance — either K is inflated "just in case" and paid for in
   throughput, or several rounds of re-querying are issued and paid for in latency. The main problem is
   elsewhere, though: facet counts and histograms need the complete result set, and you can never
   compute that correctly from a top-K. This shape therefore fails not on performance but on the
   correctness of the answer.
2. **Let Lucene enumerate absolutely everything that matches.** Functionally correct — the complete set
   can be intersected with our bitmaps. But this switches off exactly the algorithm (WAND) that makes
   Lucene fast: its whole point is *not* to read most of the index, and full enumeration forces it to
   read everything. What remains of Lucene is a supplier of document lists per word — and the entire
   integration overhead described below is still paid.
3. **Move our filters into Lucene.** Hierarchy, price lists, facets and scope would be rewritten as
   Lucene queries. The speed would remain, but it would mean reimplementing a substantial part of the
   evitaQL query language inside Lucene — and from that moment there would be two independent answers
   to the question of what a "valid product" is. Realistically it would end as a hybrid: some filters
   there, some here, plus a planner deciding between them. We would maintain both.

On top of that, all three shapes pay four cross-cutting taxes:

- **Translating internal document numbers to primary keys.** Lucene numbers documents internally
  (docid) and these numbers are renumbered on every segment merge. The mapping to evitaDB primary keys
  therefore cannot be cached permanently and every pass over the results pays for it again — under full
  enumeration for the whole set, not just for a top-K.
- **Two storage and format lines.** Lucene has its own files, its own commit protocol, its own merging
  and its own format backward-compatibility guarantee. All of that would run alongside evitaDB's
  storage with its WAL and Kryo serialization. In practice: two channels for backup and restore, two
  compactions, two compatibility audits per release — and no single answer to the question "how do I
  recognize corrupted data".
- **Transactions.** The unit of isolation in Lucene is the writer session (IndexWriter), not a
  transaction; a rollback throws away everything since the last commit. Aligning Lucene commit points
  with evitaDB catalog versions is possible — Elasticsearch uses exactly this pattern — but it is a
  permanent integration commitment that has to be maintained across every change on either side.
- **Cluster.** evitaDB plans replication by replaying the WAL. With Lucene the choice would be between
  two bad options: either each replica builds the index itself — then the segment merge history
  diverges across replicas and the same query can return a different result order (for BM25 scores; it
  can be mitigated by deterministic merge scheduling, but that is fragile) — or Lucene's files are
  replicated alongside the WAL, which is a second replication channel with its own failover and its own
  definition of when a replica is up to date.

### 1.3 Why Lucene is not the benchmark for the right approach

Here is the key shift away from the intuition "fulltext equals Lucene". The research verified directly
in source code how the engines that define e-commerce search today are built — and it turned out that
**none of them is built on Lucene and none uses its ranking model**. A short introduction, since apart
from Lucene they are not generally known here:

- **Algolia** — commercial SaaS search, the de facto standard in the e-commerce world; the core is
  proprietary.
- **Meilisearch** — an open-source engine written in Rust, popular for "instant search"; internally
  built on roaring bitmaps (the same technology evitaDB uses).
- **Typesense** — an open-source engine in C++, a direct competitor to Algolia, keeps the index in
  memory.
- **Vespa** — an engine originally from Yahoo, used for the largest deployments (e-commerce
  marketplaces among them); its own C++ core.

| Engine      | Core           | How it orders results                                        |
|-------------|----------------|--------------------------------------------------------------|
| Algolia     | in-house       | criteria cascade: typos → word count → … → business          |
| Meilisearch | in-house (Rust)| progressive partitioning of the candidate set (bucket sort)  |
| Typesense   | in-house (C++) | a single 64-bit composite score                              |
| Vespa       | in-house (C++) | phased ranking over the full set                             |

Two observations follow:

- **Lucene is the core only where the search index is also the primary database** — that is, in Solr,
  Elasticsearch and OpenSearch. There is no second store there that the index would have to be
  reconciled with: no identifier translation, no two format lines, no two replication channels. evitaDB
  is in precisely the opposite situation — it already owns storage, transactions and filtering. And
  systems in our situation, i.e. integrated engines with their own data model, build search on their
  own structures. This is not our hubris; it is a pattern the whole category shares.
- **Document BM25 — the core of Lucene's value — is not used by e-commerce engines.** They order by a
  cascade of simple, discrete criteria: how many query words the document contains, how many typos it
  has, in how important a field the match was found, how exact it is, and finally a business signal.
  Without corpus-wide statistics. The reasons are practical: fields in an e-shop are short (BM25's
  subtleties do not show there), the result can be explained ("it ranks higher because it has zero
  typos and a match in the title") and the ordering is deterministic — the score depends only on the
  query and the document, so it is the same on every replica. The hardest parts of Lucene (corpus
  statistics, WAND, the collector framework) solve a problem that does not arise in our brief: our
  queries carry mandatory filters (price lists, currency, validity) that leave 85–95 % of the catalog
  in place, so "clever skipping" of the index has nothing to save — and our volumes (hundreds of
  thousands to low millions of documents per language) are handled by an ordinary linear pass.

**Verification in the Elasticsearch and OpenSearch source code (2026-08-13) strengthened both
observations.** For word frequencies to be computed across the whole index and not merely across its
parts, every query must be preceded by an entire extra network round in which the nodes exchange
statistics; both engines have this phase built, but **leave it switched off by default**, because it is
too expensive — so by default they return scores that they themselves know are not comparable across
parts of the index. For the choice of ordering without corpus statistics this is the strongest evidence
in existence: it is not a theoretical inconvenience but a whole piece of infrastructure that the largest
deployed engines had to acquire because of that property and still do not use. The second confirmation
concerns multi-word queries: both engines ship a field type `match_only_text` which **deliberately does
not index** word positions and evaluates phrase queries exactly the way our research proposes — cheap
candidate selection from the index followed by verification against the stored text. The proposed
approach is therefore not an experiment but a production-shipped architecture, and that in an engine
that has positions available as standard and could have used them.

### 1.4 Why a different approach makes more sense specifically for us

**Meilisearch is an existence proof of feasibility for exactly our path.** Its index has the shape
"word → bitmap of documents", ordering works as progressive partitioning of the candidate set through
set operations, and facets are computed over that same full set. This is the same computational model
evitaDB uses today for filtering. The full result set — a handicap for a Lucene integration — is an
entry assumption for this model. Fulltext here is not a foreign subsystem glued onto the database; it
is one more formula in the algebra we already have.

From Vespa we take a second proven pattern, **phased ranking**: a cheap score is computed for the whole
candidate set (a fast pass over small numbers), and expensive operations — proximity, machine
re-ranking — are done only for the best K results. Expensive things where there are few of them.

Chapter summary: Lucene would sell us a bundle whose centre of gravity we do not need, at an
integration price that remains whole. This does not mean the Lucene path is impossible — the
counter-review in the first version of the research honestly showed that several obstacles are softer
than they first appeared — but the balance comes out in favour of the in-house path, and verification
against the competition rather strengthened it.

### 1.5 And what about content outside e-commerce? (CMS, tens to hundreds of thousands of pages)

evitaDB does not serve only e-shops — CMS websites run on it too, today in the order of tens to hundreds
of thousands of articles and pages. It is precisely with long texts that BM25's signals (word frequency
in the document, normalization by text length, rarity of the word in the corpus) have real meaning. So
it is a fair question whether the argument from chapter 1.3 collapses. It does not — but it deserves
refinement:

- **The design carries BM25's signals; it rejects only the machinery around them.** Part of the index
  from the first phase is one byte per word–document pair carrying a precomputed "match strength": a
  saturated word frequency and normalization by field length. It is there precisely so that a long
  article does not win merely by repeating a word many times. And should quality over articles come to
  require full BM25F, that is a change of scoring function over the same structures in this
  architecture: word rarity (IDF) is computed in the bitmap model at query time from the size of the
  document list — deterministically, without maintained statistics and without differences between
  replicas. Given our volume of CMS content this is a planned extension (phase F3), not a hypothesis.
- **The competition serves documentation and articles without BM25 too.** Meilisearch orders long texts
  by the position of the match within the field and by word proximity. Algolia splits long documents
  into sections and indexes each as a separate record — this is exactly how its own DocSearch service
  for documentation search works. This splitting into pieces (chunking) is incidentally the same
  preparation that vector search requires, so the work is used twice.
- **For thematic queries over articles** — say "how to file a warranty claim" — the biggest quality
  lever is hybrid vector search (phase F2), not tuning the lexical score. When the query and the article
  share no words, BM25 and the cascade fail in exactly the same way; only understanding meaning helps.
- **Consequences for the plan:** the decision gate measures the criteria on the CMS profile with long
  texts too (chapter 4); an entry condition for CMS deployment is making long texts in associated data
  available for indexing (chapter 3); the memory estimate for CMS is there as well.

**Tuning and oversight — lessons from running Lucene over a CMS.** We know relevance tuning over Lucene
and Solr from our own experience as a laborious and opaque discipline: manual field weights, synonyms
in configuration files, a reload after every change, and debugging of the "why does this phrase not
find this page" kind over a score that is a sum of floating-point numbers computed from corpus
statistics. The proposed model solves this pain architecturally, not through greater discipline:

- The result order is composed of discrete, readable criteria — "three words out of three, zero typos,
  a match in the title" — and the client can request a breakdown of relevance directly in the query
  response (explain).
- Synonyms and dictionaries are data artifacts swappable at runtime: versioned, per language, deployed
  by an API call without reindexing and without a restart. For comparison: in Solr a synonym change
  requires a reload of the whole core.
- Field weights and the ranking profile are configuration passed with the query, not an intervention
  into the index nor a redeploy. And because ordering is deterministic, whatever is tuned once
  reproduces exactly.

### 1.6 Interlinked content: content blocks and related documents

Our content has one more property that ordinary search does not know: it is structurally interlinked.
Pages are assembled from shared **content blocks** — pieces of content that repeat across many pages
and are maintained as separate entities; on their own they usually should not be searchable. Similarly,
products link to related products. Search here is therefore not merely "find the document containing
this word": a match found in a referenced document has to strengthen the document that references it.
Without that, a page assembled from blocks is not found at all, because the searched text lies in the
block, not in the page itself.

How does the market solve this problem? The research verified it in the source code of five engines and
in the documentation of two more, and the answer is surprisingly uniform: **by duplicating content**.
The related text is simply copied into every document that uses it — which multiplies data volume and
turns every edit of a shared piece into a rewrite of many documents. Specifically:

- Algolia has no document linking at all. Long content is cut into separate records and, when results
  are displayed, the best one is picked from the group of records belonging to one page — but matches
  from several pieces of the same page **do not add up**; when two paragraphs each match half the
  query, that does not strengthen the page at all.
- Typesense does have collection linking, but can only **filter** through it — an attempt to search the
  text of a referenced collection answers with a "not yet supported" error. Meilisearch added a similar
  capability only recently as an experiment, again for filtering only and with a hard cap of a thousand
  documents.
- Vespa can project the **values** of a referenced document into ordering (its popularity, say), but
  deliberately not its *textual match* — importing a fulltext field across a reference is rejected by
  its code in three separate places.
- The only two systems that can genuinely aggregate relevance across a link are Elasticsearch and Solr
  — and both at a price they admit themselves. Elasticsearch writes in its own tuning guide that
  parent-child queries are "hundreds of times slower" and recommends denormalizing instead. Solr limits
  its variant to a single JVM and, for documents with multiple links, propagates the score incorrectly
  — it takes the first one found rather than the best.

evitaDB starts from the opposite end: **references between entities, and indexes over them, have been
in it from the beginning**, because it is a database, not a search server. Two shapes of solution
therefore suggest themselves, neither requiring content duplication. Either the block's text is
projected only into the *index* of the referencing pages — a word–page pair costs only a few bytes in
the index, so it is an order of magnitude cheaper than copying the content, and the tax is a batch index
update when a block is edited. Or the match is evaluated at query time: blocks are searched separately,
the result is translated through the reference index onto the referencing pages and enters their
relevance with a lower weight; the tax is one extra query step, and writes pay nothing.

Honestly said: this is a hypothesis that a prototype must verify, not a finished feature — and the
decision gate in chapter 4 is not widened because of it. If it works out, however, it is a third genuine
differentiator: a capability the market substitutes with data duplication simply because its engines do
not have full-fledged references between documents.

---

## 2. Sage as an external service: advantages, disadvantages, fallback position

**Sage** is our research prototype of an AI search platform built on Solr — today a laboratory, not a
product. What matters for the decision is that it plays two different roles that need to be kept apart:

### 2.1 Sage (or an external engine) as the search runtime — the fallback position

- **Advantages:** this approach already works today (at customers typically Elasticsearch or
  OpenSearch; Sage on Solr so far in laboratory mode). The full equipment of Solr and Lucene is
  immediately available — analysis, phrases, highlighting, suggesters — as are ML capabilities
  (embeddings, entity recognition, learning-to-rank) right at the point of search. No new complexity
  arises inside evitaDB.
- **Disadvantages:** an external service sits on the critical path of every search query — its latency
  and availability become the latency and availability of search. Two systems run at every customer and
  data consistency between them is a permanent problem. And above all: fulltext, facets, prices and
  hierarchy cannot be composed in a single query — which is exactly the combination customers choose
  evitaDB for. Searching across two systems always hits the problem described in chapter 1.2, only with
  a non-trivial network in the middle.

This variant is carried in the research as the **baseline the in-house solution must beat** — and at the
same time as the retreat position: if the prototypes do not pass the gate, search stays in an external
engine and evitaDB keeps doing what it is good at. Failure of the prototype therefore does not lead into
a dead end, but to today's state.

### 2.2 Sage as the offline brain — the role that remains in both variants

In the proposed architecture Sage does not run on the query path at all. It is an offline producer of
artifacts: it enriches content (vector embeddings, recognized entities), learns from user behaviour
(boost tables, trained models) and generates synonym and entity dictionaries. evitaDB consumes these
artifacts as ordinary data, swappable at runtime. An outage of Sage therefore degrades only the
freshness of enrichment, never the availability of search. **The investment in Sage is not what is
being decided here** — the offline role holds in all variants; what is being decided is only who serves
the query at runtime.

Bonus: Sage's evaluation infrastructure (golden set, comparison harness against Solr) is precisely the
tool with which the quality of the in-house prototype will be measured at the gate against an
established engine. We will not be measuring quality with our own yardstick.

---

## 3. What evitaDB already has — why this is not "writing Lucene again"

If the brief read "reimplement Lucene", it would be nonsense and the research would have rejected it.
The brief reads differently, though, because most of the subsystems that make Lucene big are either
already in evitaDB or deliberately not needed:

| What a fulltext engine needs | How Lucene solves it | What evitaDB has today |
|---|---|---|
| postings (word → documents) | segments, codecs, compression | `TransactionalBitmap` — deployed, transactional |
| word dictionary | FST, term dictionary | transactional B+ tree + front coding (in production) |
| transactions and durability | commit protocol, translog (ES) | WAL + snapshot isolation |
| persistence and compatibility | Directory, formats, BWC | OffsetIndex, StorageParts, Kryo |
| filtering, facets, histograms | collector, BKD, faceting | formula engine + existing indexes |
| ordering by a computed value | collector framework | precedent: `FilteredPricesSorter` |
| replication | copying segments | plan: WAL replay (the index is a function of the WAL) |
| **linguistic analysis** | analyzers (30+ languages) | **taken from Lucene as a dependency** |
| **typo automaton** | `LevenshteinAutomata` | **taken from lucene-core** |

Genuinely new work is thereby bounded to these six items:

1. **Term dictionary** — an arrangement of (field, word) pairs with references to document bitmaps,
   built on the existing B+ tree.
2. **Impact sidecar** — one byte per (field, word, document) triple with a precomputed match strength;
   the only entirely new data structure.
3. **Relevance sorter and rank profiles** — composing the result order from features; structurally the
   same pattern by which evitaDB today orders by prices computed during filtering.
4. Wiring linguistic analysis into the query, typo and prefix expansion, the suggester (a derivative of
   the dictionary, no further structure), incremental index maintenance on write.
5. **Searchable associated data** — a new schema flag so that long texts, which today live in associated
   data without indexing support, can be indexed too. For CMS use this is an entry condition.
6. Later, as a separate decision with its own mini-gate: the vector branch — the candidate is the
   ready-made embeddable jVector library, not an in-house implementation.

On memory, which was the main stated concern: the estimate for the text branch is ~85–135 MB per
million products and one language — the same order as existing index structures, with no need for an
off-heap subsystem. For the CMS profile the estimate grows with text length: a hundred thousand
articles comes out parametrically at the low hundreds of MB per language. Still heap; and should it
grow further, the structures' format is paged from the start, so it permits later partial loading
without a format change. Both estimates will be verified by prototype P1 through measurement on real
data, not by estimation.

And an honest counterweight: in-house structures mean a permanent maintenance commitment — fixes,
tuning and further development we will carry ourselves, without Lucene's community. We have the
calibration right in the repository: the vendored fork of the RoaringBitmap library, i.e. experience of
the "manageable, we have a process for it, but it is not free" kind. In scope, however, a new core is an
order of magnitude smaller commitment than the two permanent integration lines described in chapter 1.2.

---

## 4. Prototype phasing and the decision gate

The prototypes are ordered so that the biggest risks fall first — and so that final structures, not
mock-ups, are built by the time of the gate. The remaining effort is then estimated from real
experience, not from an armchair.

### Phase A — up to the decision gate

| Step | What it proves | Numeric criterion |
|---|---|---|
| **P5 — analyzers** | Lucene analysis as a dependency; Czech | today's behaviour unchanged; cs/en quality |
| **P1 — core** | dictionary, postings, impact sidecar, scorer | RAM ≤ 150 MB/1M; scan ≤ 25 ms/1M |
| **P2 — write path** | index maintenance while replaying a production WAL | commit throughput drop ≤ 10 % |

**→ Decision gate.** After P5 + P1 + P2 a binding decision (ADR) is written: continue or not. Quality is
not measured at the gate with our own yardstick but **against a Solr baseline** through Sage's existing
evaluation harness (golden set) — we compare ourselves with an established engine. The criteria are
measured on both usage profiles: on the e-commerce catalog (short fields, millions of items) and on CMS
content (long texts, tens to hundreds of thousands of documents). If we do not continue, the retreat
position from chapter 2.1 applies; the work from P5 (analyzers) would moreover be usable in a possible
Lucene variant.

Important for estimating total effort: P1 and P2 build the same structures that would carry the final
solution. The gate therefore gives not only a "yes/no" but also a calibration — after it we know what
the core cost, and the remaining phases are smaller and more mechanical items measured against it.

### Phase B — after the gate (an investment already decided, a different risk profile)

| Step                       | Content                                                                         |
|----------------------------|---------------------------------------------------------------------------------|
| **P7 — profiles and boosts** | ranking configuration, dynamic boosts from user behaviour, feature export     |
| **P3 — suggester**         | suggester: p99 ≤ 5 ms per keystroke including typos                             |
| **P4 — proximity re-rank** | multi-word queries: re-rank of top-1000 ≤ 10 ms; measure on long texts too      |
| **P6 — vector spike**      | jVector vs. in-house HNSW; its own mini-gate (recall@10 ≥ 0.95; < 10 ms / 1M)   |

In delivery terms: **F1** text (analysis, core, typos and prefixes, suggester, highlighting), **F2**
vectors and hybrid search, **F3** re-rank and behavioural or ML layers, including possible BM25F for
long texts and scoring across references (chapter 1.6).

The design moreover has softer retreats built in — the criteria do not work in an "all or nothing"
style. If, for example, transactional index maintenance (P2) turns out expensive, a described fallback
exists: fulltext sees data only after commit and the index is updated in batches. That is a softening of
one product property, not the end of the road.

---

## 5. The balance: investment × risks × benefits

### Investment

What is being decided now is only **phase A** — three bounded prototypes over existing infrastructure,
of which P1 is by far the largest. The commitment to the main delivery (F1–F3) arises only at the gate,
with an estimate calibrated from measurements. The research deliberately gives no up-front person-day
estimate — that is precisely why the gate is built the way it is, so that it can give one on the basis
of measured numbers.

### Risks and their cover

| Risk | Cover |
|---|---|
| quality will not reach Lucene/Solr | analysis from Lucene; measurement against a Solr baseline (golden set) at the gate |
| effort underestimated | the gate builds final structures on a real catalog; calibration of the rest |
| pressure on RAM | numeric criterion of P1 (≤ 150 MB / 1M); paged format as insurance |
| slower writes | numeric criterion of P2 (≤ 10 %); fallback to batch maintenance |
| permanent maintenance of in-house structures | a smaller surface than a Lucene integration; the RoaringBitmap fork precedent |
| the vector branch (highest risk) | a separate decision with its own gate; the jVector library as candidate |
| long texts (CMS) will demand BM25 | tf and normalization from the start; BM25F as a function switch (ch. 1.5) |
| total failure | retreat position: an external engine alongside evitaDB (today's state, ch. 2) |

**Measuring against what we have today.** An analysis of the existing fulltext solution over plain
Lucene was produced in parallel — the Edee CMS client and its e-commerce layer, internal and **not
published in this repository** — precisely so that it could be verified that
the new solution will not be a step back in any capability. The main finding is that **most of the
volume of today's solution is not search but data transport and index maintenance**: a crawler, text
extraction from PDF and office documents, and above all ninety-seven classes of a module for index
synchronization and maintenance — distribution via S3, index twins, locks, watermarks, upload
validation, retries and diagnostics. This part **disappears** in the new model, because evitaDB's
storage already does it, or it stays with the client, where it belongs. The actual search capabilities,
the only thing that should be compared honestly, are covered and in a number of points surpassed —
today's solution has no facets, no typo tolerance and no suggester, and cannot see past the thousandth
result. Three named items remain uncovered: a **curated promo layer** (documents pinned manually by an
editor to given keywords), two minor analytical filters, and one phase dependency — **semantic search
over vectors is deployed in today's client, whereas for us it arrives only with phase F2**, so a
customer using it today needs either a finished F2 or a temporary parallel run of both solutions. It is
moreover advisable to decide about the promo layer before the gate, because it influences the shape of
the query response and thereby the query API.

### Potential benefits — where the result can be better than the competition

- **Fulltext, facets, prices and hierarchy in a single query, over a single snapshot of the data.** A
  combination an external engine cannot deliver in principle — and the reason evitaDB gets chosen. No
  guessing how many results survive the filters; facet counts always correct; one transaction of truth.
- **Determinism and explainability.** The same query returns the same order on every replica, and a
  breakdown of relevance ("why is this higher") is part of the response.
- **Tunability with oversight.** A readable breakdown of the ordering instead of an opaque score;
  synonyms and weights as configuration swappable at runtime without reindexing; deterministic
  reproduction of a state once tuned. A direct answer to our experience with manual relevance tuning
  over Lucene and Solr (chapter 1.5).
- **Interlinked content without duplication.** A match in a content block or a related document
  strengthens the referencing page without the content being copied anywhere. The competition solves
  this by duplicating records — and the only two engines able to aggregate relevance across a link
  themselves label it an order-of-magnitude slower path (chapter 1.6). A hypothesis to be verified by a
  prototype.
- **Data-driven ranking without reindexing.** Synonym and entity dictionaries as well as boost tables
  derived from user behaviour are artifacts swappable at runtime — ranking freshness costs no
  reindexing at all, because in the bitmap model boosts do not go through the write path.
- **A closed learning loop with Sage.** Exporting features from the same snapshot the query ran over
  yields clean training data for learning-to-rank — and removes a known defect of today's behavioural
  platform, which extracts features retrospectively over different data.
- **Recognized entities over a real schema.** The query "bosch drill" recognizes the Bosch brand as a
  real catalog entity and **offers** the user the corresponding facet filter instead of forcing it on
  them. This combination — entities over schema objects plus offering a filter instead of applying it —
  is not available end-to-end in any of the engines examined.

### What is being asked for

Approve **phase A**: prototypes P5 + P1 + P2 with the decision gate described in chapter 4. The
commitment to the main delivery arises only at the gate, on the basis of measured numbers and a quality
comparison. Until then both the Lucene variant (the analyzers from P5 are portable to it) and the
retreat to an external engine remain fully open.
