# The seventh machine: evitaDB and what to take from six neighbours

This is the fourth and final part of the series. The first two parts explained how Lucene works — from
text analysis through posting lists and segments to impacts and HNSW. The third part set six further
engines beside Lucene and showed that they fall into two families: wrappers around Lucene (Solr,
Elasticsearch, OpenSearch) and in-house machines (Vespa, Meilisearch, Typesense) that made different
fundamental decisions.

Now we invert the perspective. We place **evitaDB** in the middle — the database this whole series came
about because of, and which so far has no fulltext at all — and for each of those seven machines we ask:
*does its answer fit the way evitaDB is built?* The goal is not to pick a winner in an abstract contest.
The goal is to find a combination of approaches that does not go against the spirit of our design:
against in-memory indexes, against a write log with an honest commit, against immutable structures with
compaction, against predictable latency and frugality with RAM. And because the home language is Czech,
we will also be interested in which of those six can help us with it at all.

This text is not a decision document — that exists separately (internal research with an architecture
proposal and prototype plans). This part is a guided tour: why the individual pieces from the previous
parts fit into evitaDB, or do not, explained in the language we already know from the series.

Published version: https://claude.ai/code/artifact/42dfd4b2-0b3d-4837-825a-99b117246c5e

---

## 1. evitaDB as the seventh machine

Before we start comparing, the seventh machine has to be introduced as honestly as the six — through the
answers to the three big questions from part three. Nothing of what follows is a proposal; this is how
evitaDB works today, verified by reading its source code.

**Where does the index live?** The answer has two halves, and it is a similar division to the one we saw
with Vespa. Entity bodies — in this series' language "stored fields", i.e. documents as the client
inserted them — lie on disk in files that are only appended to, and are read through the operating
system's page cache, exactly like stored fields in Lucene. By contrast, **all indexes live entirely on
the JVM heap**: bitmaps of identifiers, value trees for filtering and sorting, facet maps, price
indexes. The index reaches disk in pages — serialized blocks of trees — but that is only a backup for
restart, not a working form. A query's read path never touches disk for the index.

And now the detail that will play the leading role in the whole comparison: **at startup indexes are
deserialized, not rebuilt**. evitaDB loads the stored tree pages and bitmaps and assembles from them the
same structures that were in memory before shutdown. The path "walk all the documents again and build
the index from scratch" does not exist in the engine at all — no reindexing, no equivalent of what
Typesense does on every start. We will return to why that is an advantage and a commitment at the same
time.

**How do durability and visibility meet?** evitaDB has a write log — the same idea almost everyone in
part three added to Lucene, but wired in more honestly. Every transaction first writes its changes into
a private buffer; at commit the whole blob is appended to the shared log in one go, protected by a
checksum, and the file is explicitly synced to disk. At that moment the transaction is **durable** — it
survives a crash. The log carries *logical mutations* ("set attribute X of entity 42 to value Y"), never
the state of indexes.

The transaction becomes **visible** a step later: a separate phase of the commit reads the mutations
back from the log, applies them to the shared version of the catalog and thereby produces a **new,
immutable version of the whole catalog** — a new object graph published by a single atomic swap of a
reference. A reader that started reading earlier holds the old graph and nobody changes it under their
hands; that is the whole mechanics of snapshot isolation: no version chains on records, no timestamps,
just immutability. Every committed transaction gets exactly one catalog version, numbered without gaps
one by one — and at commit the client chooses which moment to wait for: durability (written in the log)
or visibility (the new version published).

The third moment is the writing of the data files themselves — and that is **deliberately decoupled**
from both the previous ones. Changed index pages and entity bodies are written to disk continuously but
synced in batches, at a configurable interval; only then does the record "from here everything is in the
data files, replay the rest from the log" move forward. If that reminds you of the conclusion of part
three — Vespa, where "a flush stopped being a question of safety and became an optimization decision" —
it is exactly that. evitaDB already has this pattern, including the consequence: a confirmed transaction
is durable thanks to the log, not thanks to the data files.

It is worth setting a concrete comparison with Elasticsearch, as we came to know it in part three,
alongside. There a written and fsynced document is not visible until the one-second refresh elapses —
the boundary of visibility is a timer unrelated to the data in any way. In evitaDB the boundary of
visibility is the commit of a transaction — a semantically defined point that can be waited for. Both
systems decoupled durability from visibility; the difference is in what bounds visibility.

**How is unnecessary computation avoided?** evitaDB has no score today, so the answer from part three —
impacts and MaxScore versus a deadline and degradation — cannot yet be applied to it directly. It does,
however, have its own source of predictability, and that is important for fulltext plans. A query is
translated into a tree of set operations over bitmaps (a formula); the planner builds several candidate
trees for the various usable indexes, **computes their estimated cost without executing anything**, and
executes only the cheapest. Evaluation is then algebra over compressed bitmaps — intersections, unions,
differences — whose cost grows linearly with data size and has no pathological cases. Facets, histograms
and other supplementary results are computed over that same full candidate set, in the same snapshot, in
the same query.

**And immutability?** Here evitaDB does not fit either box from part three, and it is useful to say
exactly why. Lucene has immutable *segment files* and changes things by rewriting whole segments on
merge. Vespa has *mutable structures in place* and protects readers with generations. evitaDB has a
third shape: **persistent data structures** — trees and maps that are not copied whole on a change but
create a new version sharing the unchanged parts with the old (copy-on-write along the path to the
changed leaf). A commit moreover rebuilds only the indexes the transaction touched; all the others are
carried into the new catalog version by a mere pointer. The cost of a commit therefore grows with the
size of the *change*, not with the size of the *database* — and a new fulltext index must not break that
property.

At the disk level it is again append-only: data files are only appended to, dead records accumulate and
once in a while a compaction rewrites them into a new file — the same task that segment merging solves
in Lucene. With one essential difference: compaction in evitaDB does not change the identity of live
records. Entity primary keys remain, no renumbering of documents happens — the whole "the docid changes
after a merge" trap we had to explain in part two does not exist here.

So much for the seventh machine. Now for the neighbours.

---

## 2. The closest relative: Meilisearch

Of all six engines, evitaDB is closest to Meilisearch in computational model — and it is not a
superficial resemblance but a structural one.

Recall how Meilisearch stores its index: key "bike" → a bitmap of documents. A posting list is not a
packed block of increasing numbers as in Lucene, but a compressed bitmap, and query evaluation is a
sequence of set operations over bitmaps. And now read again how evitaDB evaluates queries: a tree of
formulas over compressed bitmaps, intersections and unions. **It is the same computational model.** A
fulltext branch in evitaDB would not be a foreign subsystem — it would just be another bitmap entering
the same AND in which bitmaps of prices, facets and hierarchies already meet today. A term dictionary →
a bitmap of primary keys is exactly the shape evitaDB can store, version and evaluate with existing
machinery.

The resemblance goes deeper. In part three we praised Meilisearch for a property nobody in the Lucene
family has: its score is a function of *the query and the document only* — no corpus statistics, no IDF
dependent on what the other documents currently contain. The consequence: the index is a deterministic
function of the sequence of writes. For evitaDB this property is directly an **architectural
condition**: after a crash, everything above the last checkpoint is replayed from the write log, and an
index that was not a deterministic function of that stream might not equal after replay what it was
before the crash. A cascade scoring model without corpus statistics is therefore not merely a matter of
taste — it is the one model that fits replay from a log without further conditions.

Visibility is related too. Meilisearch merged durability and visibility into the single moment of an
LMDB transaction commit and paid for it with a serial writer. evitaDB has the same semantic point — the
commit — but decoupled into two waitable events (durability from the log, visibility from publishing a
new version), and the write phase that produces catalog versions is serial as well. The difference is in
the granularity, not in the principle.

And finally a warning we took away from Meilisearch: the most expensive thing in its whole index is
proximity — the database of word pairs and positions, because of which they had to lower the maximum
indexed distance and add an opt-out. That warning is reflected in our plans literally: **positions will
not be indexed**. Word proximity will be computed for the best few hundred candidates by re-analyzing
stored values — and that this is not a desperate concession but a decent production pattern we will
demonstrate in a moment with the Lucene family.

Where we part with Meilisearch: it laid its whole index into a ready-made transactional store (LMDB) and
its trees live in mmapped pages; evitaDB has its own transactional layer and trees on the heap. And
Meilisearch is a single-node engine without a log — its commit is durable in itself, whereas evitaDB
needs a log, because it separates confirming a transaction from writing the data files.

---

## 3. The operational pattern: Vespa

If Meilisearch is our relative in computational model, Vespa is our relative in operational model — and
the supplier of two ideas we take whole.

First, the storage and durability model. Vespa keeps forward data resident in RAM, accepts changes into
memory, gets durability from the write log, and writing structures to disk is a purely optimization
decision scheduled by cost. As we saw in chapter one, evitaDB works exactly the same way — indexes in
RAM, the log as the source of durability, a checkpoint of data files at an interval. There is nothing to
adopt here, because we already have it; but it is valuable to know that the biggest in-house engine on
the market arrived at the same arrangement. And Vespa's warning holds too: a node's capacity is bounded
by RAM. We will return to the memory budget in the conclusion.

Second, **phased ranking**. Vespa computes a cheap score for every document that passed the match phase
and runs an expensive function only on the best few hundred candidates (rerank-count). evitaDB's design
takes this pattern unchanged: the first phase walks the whole candidate set and computes a cheap
composite from bitmaps and precomputed bytes, the second phase — word proximity by re-analysis,
optionally heavier business logic — receives only the top-K. And with it we take **rank profiles** as
well: the idea that the way a score is composed from features is not hard-wired into the engine but is a
named configuration a query can choose. For a platform where weights and boosts are to be learned
continuously from user behaviour, this is architecture, not luxury.

One thing we conversely do *not* take from Vespa, and it is instructive why. For free-text queries Vespa
uses weakAnd — a skipping operator that, as we showed in part three, is not rank-safe: it can discard a
document that would rightly place well. We do not need skipping at all. A typical query in an
e-commerce deployment of evitaDB carries mandatory filters (price lists, currency, validity) that cut
only a few per cent from the corpus — the candidate set is almost the whole catalog and a linear pass
over a bitmap with byte impacts is cheap with the data in RAM and, above all, **predictable**: no worst
case, no dependence on the shape of the query. Where Lucene needs MaxScore in order not to have to read
everything from disk, we read everything from memory and have the certainty that it always takes the
same time. A deadline with quality degradation à la Meilisearch and Typesense we do not need for the
same reason.

The difference in how readers are protected is worth mentioning too. Vespa mutates structures in place
and protects readers with generations (RCU); evitaDB mutates nothing and protects readers with the
immutability of the published graph. Both give lock-free reading; our variant moreover gives snapshot
isolation of the whole catalog for free — which is exactly the property we found missing in all six in
part three: fulltext, facets and prices computed with a guarantee over the same state of the data.

---

## 4. The shape of the score: Typesense

From Typesense the design takes one thing, but an important one: **the shape of the resulting score**.
Recall its `_text_match` — a single 64-bit number into which the criteria are bit-packed from the most
significant to the least: how many query words matched, how many typos it cost, how close the words are
to each other, whether it was an exact match. Comparing two documents is comparing two numbers; no
arithmetic with decimals, no IDF.

In evitaDB this shape has one further, home-grown reason. Ordering here is a chain of sorters: the first
sorter sorts what it can, the next gets the remainder, and whatever nobody can handle finishes in
primary-key order. Relevance fits into such a chain in exactly one way — as **one sorter over one number
per document**. A lexicographic composite à la Typesense is therefore not merely an inspiration; it is
the shape enforced by the semantics of our query language. The precedent is already in the code: sorting
by prices works exactly by the values being computed during query evaluation and the results then being
sorted by them.

The rest of Typesense is for us rather a collection of warnings, because its fundamental bet — "RAM is
cheaper than complexity" — is precisely the one evitaDB does not want to afford. Recall from part three:
Typesense builds the whole index again from documents after every restart (tens of minutes on larger
data), needs roughly twice the size of the data in RAM, and a large write blocks queries with an
exclusive lock. evitaDB escapes all three by construction: the index is deserialized at startup, the
memory budget for fulltext is designed in the order of tens to hundreds of MB per million entities and
language (see the conclusion), and writes do not block readers thanks to snapshot isolation.

---

## 5. The Lucene family: take the library, not the machine

With the Lucene family the answer is double, and both halves are equally important.

**What we take.** The analysis chain. All that wealth from part one — tokenization, diacritics folding,
stopwords, the Czech stemmer — is in Lucene an ordinary, index-independent library that can be added as
a Maven dependency and called as a function: text in, terms out. That it can be done cleanly is proved
by Vespa itself: its Czech works precisely by wiring Lucene analyzers into its own, non-Lucene engine as
a module. That is exactly the move evitaDB plans. And from the same box we also take the ready-made
construction of a Levenshtein automaton for typo tolerance — a class without a single tie to the Lucene
index, which we intersect with our own term dictionary.

To that, three operational patterns we saw with Elasticsearch in part three and that are worth copying.
The field type `match_only_text` is production proof that phrases do not need a positional index: a
cheap approximation from position-free postings, confirmation by re-analysis of the stored value — the
same two-phase pattern we plan for word proximity. The `wait_until` option on write — "answer once the
change is visible" — is a pure synchronization element at the API boundary that fits our commit model
literally. And enforcing "a replaceable synonym dictionary must not be baked into the index" through the
type system instead of by convention is exactly the kind of defensive design we honour in evitaDB.

**What we do not take: Lucene as the engine.** In part three we saw what the wrappers add to Lucene: a
server, a schema, a write log, document identity, distribution. Now notice — evitaDB **already is** all
of that. It has a server, it has a schema, it has a log, it has primary keys, it has transactions. The
whole reason for the wrappers' existence evaporates with that; the honest comparison is not "evitaDB
versus Elasticsearch" but "evitaDB with an embedded Lucene library versus evitaDB with its own
structures". And at that level, reading evitaDB's source code gives three concrete, technical reasons
why an embedded Lucene index grinds against everything evitaDB rests on:

First, the *transactional layer*. Every structure that participates in a transaction in evitaDB has to
be able to do one of two things: either keep a diff layer and produce a new immutable copy from it at
commit, or assemble itself from new copies of its innards. Lucene's IndexWriter can do neither — it has
its own commit point, its own rules of visibility through opening readers, and its unit of isolation (a
reopened reader after a commit) is coarser than one catalog version and cannot be addressed by version.
A reader with a snapshot of version V could not ask fulltext "what did it look like at V".

Second, *replay*. After a crash the state above the checkpoint is reconstructed by replaying logical
mutations from the log — with the same code that applied them the first time. Everything that is to be
recoverable has to be a deterministic function of that stream. A Lucene index is not: the decomposition
into segments, merge scheduling and the assigned document numbers depend on timing and threads. A Lucene
index therefore could not be state replayable from the log — it would have to be a side artifact with
its own durability, its own crash recovery and its own pairing to catalog versions. Two durability
protocols side by side, forever.

Third, the *lifecycle of files*. evitaDB's files are subject to compaction and a history horizon —
machinery that knows when which file is dead. Lucene files mmapped outside that machinery would demand
their own cleanup, their own backup, their own discipline of format backward compatibility.

By contrast, in-house fulltext structures — a term dictionary as a transactional B+ tree, postings as
transactional bitmaps, both serialized in pages like the other indexes — use **all the existing
machinery without remainder**: transactions, the log, checkpoints, compaction, backup, recovery. That is
the core of the whole "do not go against the spirit of the design" argument, expressed in code.

One honest note in the margin: evitaDB's own authors measured that a tree structure for the dictionary
(a radix trie), which suggests itself first for fulltext, lost on real data to a B+ tree with front
coding on memory — and the leaves of evitaDB's trees today store strings literally in the format of
Lucene's term dictionary (shared prefixes, restart points). The substrate for a term dictionary
therefore not only exists but is already tuned.

---

## 6. Czech and one irreversible choice

Let us recall the table from part three: a full Czech chain — stemming, stopwords, folding — is
available out of the box only to members of the Lucene family, because all three carry the same
`CzechAnalyzer` from part one. Vespa can get to it through a module. Meilisearch merely normalizes
Czech, Typesense only strips diacritics. A simple conclusion follows for us: **the only path to good
Czech is to take Lucene's analyzers** — which, as we have just seen, is possible without a Lucene index.
Slovak will get a Hunspell dictionary (there is no ready-made stemmer for it in Lucene) and Polish lives
in a separate artifact; both are solvable the same way.

How much it is needed is shown by the state of evitaDB today. Its string search is an exact substring
match, case-sensitive: `attributeContains("kožená")` does not find an entity with the value "Kožená
bunda", because "k" ≠ "K". The only normalization done at indexing time today is canonical Unicode
decomposition — it unifies two ways of writing "é", but "é" and "e" remain different characters and
"bunda", "bundy", "bundě" are three different words. From a full chain — where the query "kozene bundy"
finds "Kožená bunda" — we are separated by exactly that pipeline from part one.

And now the irreversible choice. In part three we saw an escape route for each engine in case analysis
changes: Elasticsearch has a reindex API, Typesense rebuilds the index at every start anyway,
Meilisearch reindexes on a settings change, Vespa can be fed the data again. **evitaDB has no such
route** — reindexing from documents does not exist in the engine, indexes are only deserialized. From
that follows a consequence sharper than for any neighbour: the analysis chain — tokenization, folding,
the stemmer, stopwords — becomes a **decision about the on-disk format**. The terms in the index are the
output of a specific version of the analyzer; changing the stemmer after a year of operation means
either migrating the whole index during the upgrade, or a reader for the old format version — evitaDB
already does both for other structures and has the discipline for it, but it has to be reckoned with
from day one, not once it starts to hurt. There is, incidentally, an exact precedent for this in
evitaDB's code: a change of string normalization is already carried there as an on-disk format change
with a mandatory backward-compatible reader. The analysis chain is the same category, only bigger.

The other side of the coin is worth saying too: freezing on one version of the analyzers is acceptable
for years in fulltext. Analyzers change rarely and determinism counts in favour of this strictness — the
same catalog version always gives the same terms, on every replica, after every replay.

---

## 7. What the seventh machine has extra

The comparison would not be honest if it enumerated only what evitaDB takes from the neighbours. In
several things it stands better than any of them — and those are exactly the things by which an embedded
fulltext has to justify itself against the variant "just put Elasticsearch next to it".

**One snapshot for everything.** A fulltext match, facet counts, price filters and hierarchies computed
with a guarantee over the same state of the data, in one query, without a network round. None of the six
engines offers this, because none of them has prices and facets as native concepts — and an external
engine beside the database does not have it by definition: two systems, two commits, two states of the
world.

**References as a set operation.** In part three we did not deal with joins, but the research examined
them in depth: relevance across a document boundary ("a match in a content block should lift the page
that embeds it") practically does not exist on the market — whoever has it pays for it by materializing
the join in memory on every query, or by denormalizing the data. evitaDB has reference indexes as
bitmaps already today; the translation "candidates among blocks → pages that reference them" is a cheap
set operation over the full candidate set for it. That is a shape the neighbours lack, and for the CMS
profile (websites assembled from blocks) it may be decisive.

**Fine-grained cache invalidation.** Recall Solr: its filter cache is discarded in its entirety on every
opening of a new view of the index and warmed again by replaying queries. evitaDB caches intermediate
formula results with a key derived from the versions of the specific tree leaves the computation read —
a write elsewhere in the catalog does not invalidate the cached result. A fulltext formula that adopts
this mechanism (and it is finished in the code, it merely needs to be used) gets something even Lucene
does not have: a result cache surviving unrelated writes.

**Semantic points instead of timers.** Visibility after a commit, durability after the log write, both
waitable from the API. The neighbours offer a refresh interval, soft-commit timers, "NRT within a
second". For an application that has just written data and wants to find it, the difference between
"wait for the commit" and "wait a second and hope" is the difference between a contract and a
superstition.

---

## 8. What to take away

Let us assemble the mosaic. A fulltext for evitaDB that does not go against the spirit of its design
looks like this:

- **The computational model from Meilisearch**: postings as bitmaps of primary keys, evaluation by set
  algebra over the full candidate set, the score as a function of the query and the document only —
  because only such an index is a deterministic function of the write log and survives a replay.
- **The operational and ranking model from Vespa**: structures in RAM with durability from the log and
  checkpointing as an optimization (evitaDB already has this), phased ranking — a cheap first phase over
  the whole set, an expensive second phase over the top-K — and rank profiles as configuration.
- **The shape of the score from Typesense**: a lexicographic 64-bit composite, one number per document, a
  descending sort — a shape that is, incidentally, enforced by the semantics of the sorter chain in
  evitaQL anyway.
- **Analysis from Lucene, but as a library**: the Czech analyzer and the Levenshtein automaton as a Maven
  dependency; plus three patterns from Elasticsearch — phrases confirmed by re-analysis instead of a
  positional index, `wait_until` at the API boundary, and type-enforced separation of replaceable
  dictionaries from the index.
- **And our own substrate for everything else**: transactional B+ trees with front coding (the format of
  Lucene's term dictionary is already in the leaves), transactional bitmaps, page-wise serialization, the
  log, checkpointing, compaction, snapshot isolation — zero new infrastructure.

For the balance to be honest, this path asks for three things to be built anew, and one to be decided in
advance. New: an analysis chain wired into indexing (today evitaDB cannot even do case folding), a
scoring channel in the query language (today there is no concept of relevance — the constraint, the
sorter and the path by which a score would flow from the formula into ordering are all new), and a term
dictionary as a new variety of tree (the existing value tree has the invariant "one record in exactly
one bucket", which postings violate in principle — one document has many terms). To be decided in
advance: versioning of the analysis chain, because it is an on-disk format and reindexing does not
exist.

And one limit that has to be said out loud: **everything will live on the heap**. The paged
serialization of indexes is today only a write mechanism — no lazy loading, no eviction of cold parts.
The memory budget of fulltext (in the order of 85–135 MB per million products and language, several
times more with long CMS texts) is therefore not an indicative figure but an entry condition that the
first prototype must measure on real data. The format is paged deliberately — should the budget stop
holding, lazy loading can be built on without a format change — but today that safeguard is not built.

With that the circle of the series closes. Part one ended with the advice "cheap candidates, an
expensive reordering"; part three with the warning that the choice of scoring model is architectural and
falls at the beginning. The seventh machine has the good fortune of starting on a green field — and can
take from each neighbour what fits its hands: the counting from Meilisearch, the operations from Vespa,
the shape of the number from Typesense, the language from Lucene. The rest it already has.

---

### Notes on sources

The claims about evitaDB are read from the source code of the `dev` branch (revision `6a486f0a56`,
August 2026): the write log and the commit pipeline (`evita_store/evita_store_server/…/wal/`,
`evita_engine/…/core/transaction/`), storage and compaction
(`evita_store/evita_store_key_value/…/offsetIndex/`), catalog startup and index loading
(`evita_engine/…/core/catalog/Catalog.java`, `index/component/loader/`), transactional memory
(`evita_engine/…/core/transaction/memory/`), indexes and the query path (`evita_engine/…/index/`,
`core/query/`). Detailed excerpts with line references lie beside this document in `evitadb-background/`.
The claims about the six engines derive from the revisions given in the notes of part three. The
architecture proposal, memory estimates and prototype plans this part refers to are held by the internal
research ([`../research.md`](../research.md)) — this text does not replace it, it merely explains it.
