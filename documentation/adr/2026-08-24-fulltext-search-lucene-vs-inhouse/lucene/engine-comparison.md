# Six machines on the same task: a comparison with Lucene

Part three of the primer. It assumes both previous parts — `lucene-primer.md` (concepts: term, posting
list, analysis, segment, commit vs. NRT) and `lucene-under-the-hood.md` (block encoding, the term
dictionary, impacts, mmap vs. heap, HNSW). It assumes nothing else: you need know nothing at all about
the six engines compared, each is introduced first. Everything is related back to Lucene: "this is done
the same way, here it differs and it costs this."

The source is six checkouts in `/www/oss` — solr, elasticsearch, OpenSearch, vespa, meilisearch,
typesense — read in the same week as the Lucene of the first two parts. Versions and revisions are in
the note at the end.

Published version: https://claude.ai/code/artifact/7bf93f62-a4e5-428d-ad20-918ac01527c2

---

## 1. A map of the terrain

The six engines fall into two families, and that division carries the rest of the text.

**Wrappers around Lucene: Solr, Elasticsearch, OpenSearch.** Inside each of them runs literally that
library from the first two parts — the same analyzers, the same segments, the same packed posting-list
blocks, the same impacts, the same HNSW. They reimplement none of it. "Wrapper" here means a concrete
thing: Lucene is a Java library you call from your code, taking care of the index files yourself. These
three projects turned it into a **server** — you send it documents and queries over HTTP and it takes
care of everything around it for you: configuring analyzers, distributing data across several machines,
replicas, surviving a crash. Comparing them with Lucene therefore means asking **what they add to it** —
and you will see that the answer is essentially the same for all three (a server, distribution, a schema
and a write log), differing only in how far each of them took each of those things.

**In-house machines: Vespa, Meilisearch, Typesense.** Each of them wrote its own inverted index and each
made different fundamental decisions from Lucene's — and different from the other two. Vespa is built as
a database: structures change in place, nothing is rewritten into immutable segments. Meilisearch laid
its whole index into a ready-made transactional store and replaced posting lists with bitmaps. Typesense
keeps absolutely everything in main memory and uses the disk only as a log from which the index is
rebuilt after a restart.

For each machine we will ask the three questions we know from part two, because it is in the answers to
them that the engines diverge most:

1. **Where does the index live?** Lucene answered "in memory-mapped files, with the kernel's page cache
   taking care of hot data". We will see the answers "same place", "in RAM, hard", and "halfway".
2. **How do durability and visibility meet?** Lucene merges them into a single expensive commit and
   offers an NRT reader as a fast but non-durable shortcut. We will see that almost everyone added a
   write log to it — and one engine merged both concepts into a single moment.
3. **How is unnecessary computation avoided?** Lucene's answer is impacts and MaxScore: an upper bound
   on a block's score permits skipping the block, while the result is guaranteed to be the same. We will
   see two engines that do not have this possibility at all — and what they do instead.

---

## 2. Solr: the thinnest wrapper, with one big exception

A short introduction: Solr is the oldest of the three (created in 2004 at Apache, today a sister project
of Lucene with a shared history of developers). It runs as a standalone Java server, is configured with
XML files and is spoken to over HTTP. For a long time it was the default choice for enterprise search;
today Elasticsearch has long since outgrown it in popularity, but architecturally it is still the most
honest illustration of what "wrapping Lucene in a server" means — because it wraps the thinnest.

**Analysis: the same classes, a different way of wiring.** Analyzers, tokenizers and filters are
literally those Lucene classes from part one. The only difference is how they are assembled into a
chain: instead of writing Java code you enumerate them in XML by name ("standard", "lowercase",
"czechStem") and Solr finds the corresponding classes itself. The practical consequence: changing
analysis requires no compilation. Czech is prepared in the base set — the field type `text_cz` composes
exactly that chain from chapter 3 of part one: `StandardTokenizer`, lowercasing, the Czech stopword list
and `CzechStemFilter`. And Solr adds one handy primitive that Lucene itself does not have: for every
field type you can declare **two different chains** — one for indexing, the other for the query. Recall
the symmetry rule from part one and its legitimate exceptions (synonyms only in the query): here that
exception is a matter of configuration, not code.

**Visibility and durability: Lucene's concepts with a timer.** Solr has two kinds of commit and both are
a literal translation of what you know from chapter 10 of part one. A **soft commit** opens a new NRT
reader — new documents are visible from then on, but a process crash would still lose them. A **hard
commit** is `IndexWriter.commit()` — fsync, a new `segments_N`, durability. All Solr added to that is
automation: in the default configuration a hard commit runs every 15 seconds (deliberately in a way that
does not open a new searcher — it merely makes things durable) and a soft commit every 3 seconds. Read
that again as an operational sentence: *data is visible within three seconds and survives a crash within
fifteen.*

**Transaction log: the first encounter with a write log.** Here Solr adds something Lucene does not have
at all, and because we will meet it four more times, it is worth explaining properly. Between two
commits Lucene lives dangerously: whatever is not committed is irretrievably lost in a crash. Committing
after every document is not possible — a commit is expensive (an fsync of all files) and would flood the
index with tiny segments. The classic database solution is called a **write-ahead log (WAL)**: before
the server confirms a write to you, it appends it to a simple sequential file — a log. A sequential
write to the end of a file is cheap, so it can keep up with every document. After a crash the log is
read from the last commit and everything is replayed. Solr calls it the **transaction log (tlog)**: a
document is written there first and only then confirmed to the client; a hard commit closes the log and
starts a new one.

The log has unexpected side benefits. Because the absolutely latest version of every freshly written
document always lies in it, Solr can do **Real-Time Get**: "give me the document with key X" is answered
immediately and exactly — read from the log — even when search does not see that document yet (a soft
commit has not happened). And over the `_version_` field in the log stands optimistic concurrency
control: "write only if the current version is still 42". Both are things Lucene as a library cannot
express — it has nowhere to put that running truth.

**Cache: where Solr and Lucene parted ways in opinion.** Recall from part two that Lucene 11 **switched
off** its query cache — impacts made scoring so cheap that maintaining the cache stopped paying. With
Solr the story is spicier: Solr has in its source, in `SolrIndexSearcher`, the line `setQueryCache(null)`
with the comment "we have our own filter cache" — it never used the Lucene cache, so its abolition is a
non-event for Solr. Except that its own is built differently, and that difference is worth
understanding. A filter is an omnipresent thing in an e-shop: "category = bikes AND in stock" is glued
to almost every query, and it pays off to have the resulting document set computed once and stored as a
bitmap. The Lucene cache kept such a bitmap **per segment** — and because segments are immutable, a
bitmap for an old segment stayed valid even after new data was added. Solr's `filterCache` keeps a
bitmap across the **whole index** in one piece. That is more convenient (Solr builds facets and joins on
it), but vulnerable: as soon as a new searcher is opened — and that is every soft commit — document
numbers may have shifted and **the whole cache is instantly useless**. Solr solves this by "warming":
for a new searcher it fills the cache by simply running the cached filters again. And because one bitmap
over 100 million documents is 12 MB and the default cache size is 512 entries, this one structure alone
can swallow gigabytes of heap. The point: the advice "small heap, RAM for the page cache" from part two
holds for Solr too — its own documentation repeats it — but filterCache gives it a hard floor that a
user of plain Lucene does not know.

**Distribution and replica types.** When an index outgrows one machine, SolrCloud splits a collection
into **shards** (each shard is a standalone, complete Lucene index with a portion of the documents) and
every shard has **replicas** — copies on further machines, for resilience against failure and for
scaling reads. And here comes a choice that maps beautifully onto segment immutability: how do you keep
a replica up to date? Solr offers three types. An `NRT` replica receives **documents** and analyzes and
indexes them itself — you pay indexing CPU as many times as you have replicas, but new material is
visible within seconds. `TLOG` and `PULL` replicas receive **finished segment files** — the leader
indexes once and the others copy the files. That is possible only thanks to two properties you already
know: a segment never changes after being written (you copy a stable thing) and the file format is
precisely defined by the codec (an index file is here literally a transfer format). The price: a replica
lags by the copying interval.

**Vectors and concurrency.** Vector fields pass straight through to Lucene HNSW; Solr merely renamed the
parameters after the literature (`hnswM` is Lucene's `maxConn`, `hnswEfConstruction` is `beamWidth` —
the same two knobs from chapter 8 of part two). A curiosity: the cuVS module can build the graph on a
GPU and **serialize the result into the ordinary HNSW format** — index building is accelerated, search
stays on the CPU unchanged, so the segment is readable even by a node without a graphics card. And one
lag: concurrent searching of segments within a single query (the third level from chapter 4 of part two)
is fully wired in Solr, but **switched off twice** — the executor has a default of zero threads and the
request parameter `multiThreaded` is `false` by default with an "experimental" label. Solr's answer to a
large index is still "add shards", not "add threads". Version-wise Solr is deliberately one major behind
Lucene: releases are tied to *released* Lucene, so Solr 11 builds on Lucene 10.4.

---

## 3. Elasticsearch: a distributed machine that leaves Lucene alone

A short introduction: Elasticsearch (2010, the company Elastic) is today the most widespread fulltext
server in the world — it powers e-shop search as well as log analytics for half the internet. It speaks
JSON over a REST API, the schema is called a *mapping*, and its "index" is a logical name for a group of
**shards**, where every shard is — as with Solr — one complete Lucene index. A five-shard ES index is
five independent Lucene indexes; nothing is ever merged between shards. Almost every difference from
Lucene can be traced to three things the library does not have: the network, the log and the schema.

**The life of one document: refresh and flush.** The fastest way to understand ES's write path is to
follow one document. It arrives over HTTP; ES writes it into the **translog** (the same write-ahead log
concept as Solr's tlog — the client is confirmed only after the write into the log) and at the same time
into the memory buffer of Lucene's `IndexWriter`. At this moment the document is **durable but
invisible** — search knows nothing about it. Every second (the default `refresh_interval`) a **refresh**
happens: a new NRT reader is opened and the document becomes visible — but it still lives only in the
translog and in unfsynced files. And once in a while (the default thresholds: the translog grows to
10 GB, or a minute elapses) a **flush** happens: a real Lucene commit with an fsync, after which the
translog may be truncated. Refresh = visibility, flush = durability in the index; both words from the
end of part one get their precise technical content here. A consequence of that separation: a document
can be fsynced and yet unfindable (translog yes, refresh not yet). ES patches this for reads by id only
— a "realtime GET" reads the document straight from the translog, the same trick as Solr. For *search*
no such patch can exist: the log is only a list of operations, you cannot run a query over an inverted
index on top of it.

**Document identity: the deepest intervention into the Lucene model.** Recall the trap from part one: a
Lucene docID is an unstable internal number and deleting is only a dropped bit in live docs. For a
library on one machine that suffices. A distributed system, however, needs more: after a failure a
replica has to be able to **resynchronize** ("what all has happened since operation no. 4711?"), and
that requires operations to have identity and order. ES therefore gives every document a real `_id` (an
ordinary indexed term) and assigns every operation a sequence number `_seq_no` — which creates on every
shard a complete, replayable record of history. And deletion and overwriting are **soft** because of
that history: an update does not physically delete the old version of a document, it merely marks it
(the doc-values field `_soft_deletes`) and writes the new version beside it; a delete writes a special
mini-document, a "tombstone", saying "id X was deleted by operation no. N". Old versions and tombstones
are deliberately kept across merges too (in a controlled way, for a limited time), because it is
precisely from them that replica recovery and cross-cluster replication are fed. Interesting for you as
a reader of Lucene: the soft deletes API exists in Lucene precisely because ES needed it — the library
added the feature for its biggest consumer. It is paid for in disk (deleted versions live longer than
with plain Lucene) and heap (a map of recent versions for fast conflict checks).

**The schema and `_source`.** A mapping is a schema in JSON and translates field types exactly onto the
structures from chapter 2 of part one: `text` → analyzed posting lists, `keyword` → one term + doc
values (sorting, facets), numbers and dates → BKD points + doc values, `dense_vector` → HNSW. It can
also infer a schema from data at runtime ("dynamic mapping" — convenient, and therefore fenced in with
limits, the default cap being 1000 fields). The best idea of that layer is inconspicuous: every mapping
parameter declares whether it can be changed later, and the rule is exact — **changeable is only what
does not change the bytes written to disk**. `analyzer` is locked (a change would break the symmetry of
index and query — the silent zero of chapter 6 of part one), `search_analyzer` is free (it changes only
the query side).

One real deviation from the Lucene model: ES does not store stored values field by field but the whole
original JSON document as **one blob** called `_source`. Surprisingly much rests on that blob: update by
id (read `_source`, modify, index again), reindexing into a new index, highlighting (the text from
`_source` is analyzed again). The disadvantage is obvious — you pay storage for a complete copy of
everything. The newer "synthetic source" goes at it the other way: the blob is not stored at all and the
JSON is **reconstructed** on read from doc values and points. ES thereby retroactively buys itself the
columnar frugality that Lucene's per-field model always had — at the cost of CPU on read and small
losses of fidelity (the order of elements in an array, duplicates), which it honestly records in a
special field.

**Memory: the doctrine holds, the exceptions have names.** ES's documentation advises exactly what
chapter 5 of part two does: the heap at most half of RAM and less is fine, leave the rest to the page
cache — the index is in mmap. More interesting are the named exceptions, i.e. things ES keeps on the
heap beyond plain Lucene. The largest are **global ordinals**. Context: ES "aggregations" are
generalized facets — "count documents by category", "average price by brand" — and read from doc values.
Except that doc values store strings as numbers (ordinals) pointing into the dictionary of **the given
segment**; ordinal 7 means a different word in every segment. An aggregation across a whole shard
therefore needs a conversion map of per-segment ordinals to global ones — and that is built **on the
heap** and rebuilt on every change of segments (the structure itself is incidentally Lucene's
`OrdinalMap`; ES only supplied the cache and lifecycle). On fields with millions of unique values that
is a real load — and that is precisely why ES has **circuit breakers**: counters that reject a request
before an allocation would bring the JVM down (fielddata may use 40 % of the heap). Read that as
indirect evidence of the doctrine: everything search rests on is off the heap, so what remains on the
heap can be counted and policed.

**Query evaluation: do not switch MaxScore off unknowingly.** ES does not reimplement impacts and
MaxScore — it inherited them — but it made one decision that determines whether they may work at all.
Recall how MaxScore works: it skips blocks that cannot speak into the top-k. Except that a skipped
document **cannot be counted** — and when the user wants an exact total hit count, nothing may be
skipped. ES therefore introduced the default `track_total_hits: 10000`: the hit count is computed
exactly only up to ten thousand and above that limit "more than 10,000" is reported (literally the
relation `gte` in the response). That is the reason modern e-shops write "10,000+ results" — it is not
laziness, it is the price of fast queries. And conversely: whoever requests `track_total_hits: true` in
a query silently switches off the engine's most valuable optimization.

The distributed layer then mirrors the per-segment tricks one floor up. The **can_match** phase asks
every shard cheaply, before the query is dispatched, whether it can return anything at all (say by the
min/max value of a time field) and skips the shards that cannot — the same idea as the skip index over
doc values, only at the level of whole shards. And **query-then-fetch** is the distributed form of the
rule "stored fields last": the first phase collects only ids and scores from all shards, the coordinator
merges and sorts them, and only the second phase requests `_source` — for that one page returned to the
user. The tax for sharding: IDF is computed per shard, so the score of the same document depends on
which shard it lies on; a global unification of statistics (DFS mode) exists, but costs an extra round
and is opt-in.

**Vectors.** Lucene HNSW, with ES adding mainly defaults and policy. Quantization (shrinking vectors
from 4 bytes per dimension to a fraction — chapter 8 of part two) gradually became the default
behaviour; today's default for larger vectors is BBQ, binary quantization, from 384 dimensions. ES
handles the loss of precision by **oversampling**: it pulls 3× more candidates out of the quantized
index than the number of results you want and then re-scores those at full precision — a cheap coarse
sieve, an expensive fine adjustment, once again the two-phase pattern. Filtered search chooses the
strategy according to the estimated selectivity of the filter (an ACORN walk vs. a post-filter) — you
already know that task from "trap one" of part two. And a nice detail to close: `index_options` of a
vector field is the only parameter changing bytes on disk that **can** be changed at runtime — because
every segment carries its own codec, the new format simply applies to new segments and the old ones
merge away in time. Segment immutability functions here as a mechanism of gradual format migration.

And one thing that will broaden your horizon from the chapter on positions: the type `match_only_text`
(intended for logs) indexes only `DOCS` — no frequencies, positions or norms, the cheapest level of
`IndexOptions` from part one. Phrase queries nevertheless work: the posting lists serve as a coarse
**approximation** (documents containing all the words of the phrase anywhere) and every candidate is
then confirmed by pushing its `_source` through analysis again and verifying the phrase in it. Slower on
the query, an index a third smaller — and proof that positions in the index are optional when the
original text lies beside it.

---

## 4. OpenSearch: what the fork allowed itself to do differently

A short introduction: OpenSearch came into being in 2021 as a fork of Elasticsearch 7.10 — Elastic had
changed the licence to prevent Amazon selling ES as a service, and Amazon answered by splitting off the
last open-source version. The API and architecture are therefore almost identical to ES from a user's
point of view. What matters is that OpenSearch **did not freeze** on the 2021 version: it keeps pulling
Lucene forward and today carries the same 10.5 as ES. Take everything from the shared core — shard =
Lucene index, translog, refresh/flush, mapping, analysis including Czech, `track_total_hits` 10,000 —
exactly according to the previous chapter and let us not repeat it. What is interesting is where the
fork diverged over the years.

**Segment replication: cashing in on segment immutability.** ES replicas are maintained by replicating
*documents* — every replica receives the document and analyzes and indexes it itself, so indexing work
is paid for as many times as there are copies. OpenSearch added a second mode, replication of *files*:
only the primary shard indexes and the replicas receive finished segment files; a replica does not even
have an `IndexWriter`, only a reader fed by copied files. It is the same idea as Solr's TLOG/PULL
replicas — and again it rests on a segment being immutable (you copy a thing that does not change under
your hands) and the format being precisely defined by the codec. What is bought: indexing CPU once
instead of N times, and replicas **byte-identical** with the primary — which incidentally means the
score is identical too (the same docFreq, the same not-yet-merged-away deletions). What is paid: a
cluster-wide tie to the binary format (a replica must be able to read what the primary wrote — upgrading
a cluster gets complicated by this) and visible replica lag: whoever reads from a replica has no
guarantee of seeing their own fresh write. The default remains document replication; the mode is chosen
at index creation and then cannot be changed.

**A storage story hung on segment replication.** Once "the truth is the segment files, not the stream of
documents" holds, doors open that ES did not open this way. **Remote store**: segments and the translog
are uploaded into object storage (S3 and friends) and replicas are fed from there — the primary ceases
to be the bottleneck of distribution. **Searchable snapshots**: an index whose files lie remotely and
locally only a cache of the blocks actually read is kept (the default ratio: a local cache may serve 5×
more remote data). Technically it is almost boring, and that is what is instructive about it — recall
from part two that Lucene reads everything through the `Directory` abstraction, i.e. "give me the bytes
at this offset of this file". It suffices to wrap that abstraction in a decorator that downloads a
missing block, and search over a half-remote index works **without a single change in Lucene**. And
thirdly **search replicas**: shards that never index, running on nodes with a dedicated `search` role
and scaling reads independently of writes — a separation of read and write hardware, possible precisely
because a replica no longer has to index.

**Concurrent search of a single shard.** That third level from chapter 4 of part two — segments are
independent, so let us search them at once — is, uniquely in this family, **on by default** in
OpenSearch, in `auto` mode with a pragmatic rule: parallelize when the query carries aggregations (there
is something to compute there), otherwise not. And it went a step further than Lucene's model of "a
slice = a set of whole segments": it can slice **inside** a segment as well, so one giant post-merge
segment no longer holds the whole query on one thread. The cap is 4 slices per query. A small honest
detail: top-k results are invariant with respect to slicing (the score does not depend on who processed
the document), but terms aggregations are not — every slice does its own running pruning to the most
frequent values, and so slicing adds a new layer of possible count inaccuracy, which OpenSearch admits
to with a special flag in the response.

**Two things without an equivalent in ES.** **Pull-based ingestion**: the index pulls documents itself
from Kafka or Kinesis (classically something pushes them into ES/OS from outside) and checkpoints its
position in the stream — now hold on — **into Lucene's commit data**, that little key-value dictionary
written into `segments_N`. The stream offset thus commits atomically with the data and after a restart
it picks up exactly. **Star-tree index**: precomputed aggregations ("turnover by category and day")
stored as a tree directly in the segment, formally a codec extension. It is built only at flush and
merge, cannot be added to an existing index, and is used exclusively as a silent speed-up — when a query
does not meet the conditions (say it has an additional filter), it falls back into normal doc values
iteration. And furthest of all reaches the experimental "pluggable data format": an interface by which a
plugin could slip a completely different storage format from Lucene into a shard (the code explicitly
mentions Parquet, the columnar format from the analytics world). So far it is only a seam — switched
off, an empty factory class, no plugin in the repo — but the direction is legible: OpenSearch is keeping
open the possibility that a shard will one day not be a synonym for "Lucene index".

**Vectors: outside the core.** Vector search **is not** in the OpenSearch repo itself — no field type,
no kNN query. It lives in a separate k-NN plugin with its own release cycle, which offers a choice of
engine: Lucene HNSW, or the native faiss library. For this text we did not have the plugin in the
checkout, so leave the details to the documentation; the structural point holds anyway — OpenSearch
treats vectors as a separate capability with its own storage, not as another structure of a segment, the
way Lucene did.

---

## 5. Vespa: a database in which an inverted index lives

A short introduction: Vespa is an engine Yahoo built for its own search and recommendation over twenty
years and opened up in 2017 (today an independent company, Vespa.ai). It is the heaviest calibre of the
comparison: a distributed platform where the data nodes run in C++ (the layer is called "proton") and
the query/application layer in Java. One sentence carries the whole chapter: **Lucene is a library
building immutable segments; Vespa is a database that contains an inverted index.** Almost every
difference follows from Vespa never having accepted immutability — the cornerstone of Lucene from
chapter 7 of part one.

**The write memory is searchable.** Recall how Lucene does it: documents accumulate in a memory buffer
that is **not queryable** — it is just an append-only heap of bytes optimized for fast writing — and
they become visible only on being flushed into a segment and a new reader being opened. Vespa's memory
index is instead built on B-trees constructed to tolerate one writer and many readers **simultaneously**:
a query happily walks a structure into which insertion is currently happening. There is therefore no
refresh, no reopen; the configuration option `visibilitydelay` (by how much visibility may lag so that
writes can be batched) has a default value of zero. A document is visible at the moment it is written.

**One disk index instead of many segments.** A flush in Vespa writes the memory index out into a disk
index — the counterpart of a Lucene flush into a segment. But then comes a step Lucene does not have:
**fusion** merges the disk indexes into **one**. No merge policy, no "tiers" of segments by size, no
tuning; the steady state of a node is one large disk index plus at most two fresh flushes awaiting the
next fusion. The whole chapter "watch the number of segments" disappears — query cost does not grow with
fragmentation, because fragmentation is not permitted. It is paid for by fusion being a rewrite of
**everything**: a Lucene merge is incremental (it merges a few segments and leaves the rest), fusion
rebuilds the whole index. And one more fundamental change: in Vespa a document **does not belong to a
segment**. The document number (they call it a lid) is global to the whole node, and beside the indexes
lives an array called a *source selector* — for every lid it says which index (memory, which flush, the
fused one) is currently responsible for that document. When a document arrives again, it is written into
the memory index and its cell in the selector is switched; nobody deletes the old records in the disk
index, they simply stop being pointed at. Compare with Lucene: there "which segment a document is in" is
given by construction and overwriting means delete + add.

**Attributes: doc values inside out.** This is the most important paragraph of the whole text for your
e-commerce context. Lucene doc values are a column of values per document — and like everything in a
segment they are immutable: changing a price means reindexing the whole document (delete + add, chapter
8 of part one). Vespa has **attributes** instead: also columns per document, but held in RAM and
**mutable in place**. A price update is a write into one cell of the column — the document is not read,
not serialized, nothing is reindexed; the update grammar even includes arithmetic ("add 5 to
popularity") without touching the document at all. Prices, stock and popularity can thus be changed a
thousand times a second at a cost the Lucene family cannot even dream of.

How does that square with the lock-free reading Lucene rests so much on? Lucene's answer is "the data
does not change, so there is nothing to lock". Vespa's answer is a **generation scheme** (RCU,
read-copy-update, in the literature): on entry every reader takes a "ticket" with the number of the
current generation; the writer changes data by not overwriting the old value but setting it aside, and
increments the generation; the memory of old values is released only once the last reader holding an old
ticket leaves. Readers never wait on a lock — and it is paid for by deferred memory reclamation and
explicit background compaction. The same mechanism protects the memory index and the HNSW graph. Two
philosophies, one coin: Lucene buys lock-free reading by prohibiting changes and pays by rewriting
during merges; Vespa buys it with versioned reclamation and pays with the complexity of memory
management.

**Durability: a WAL taken to its conclusion.** Vespa has a transaction log server — a write log with an
fsync after every commit as the default behaviour. And it thought it through further than Solr and ES:
when the log is the sole source of durability, a flush stops being a question of safety and becomes a
**purely performance decision** — by flushing you merely shorten the future replay after a restart. That
is why in Vespa a flush is not decided by buffer size as in Lucene but by a global scheduler that looks
at all the flushable structures of a node (the memory index, every attribute, the document store),
computes whose flush saves the most (memory, future replay), and runs them by cost. Visibility
immediate (memory index), durability immediate (the log), writing into index structures on disk —
whenever it suits. Three events that Lucene merges into one.

**On-disk formats: a generation more conservative, and instructive as to why.** Vespa encodes posting
lists with byte varints (the same principle as `VInt` from part two — and recall that Lucene fled from
them to SIMD blocks precisely because of serial decoding) with a four-level skip list. The dictionary is
not a trie but compressed 4 KiB pages binary-searched so that a lookup costs one read from disk — the
file `bitcompression/README.md` in the repo describes six generations of that format since 1998,
including which problem forced each rebuild; I have not seen anything more instructive about the
evolution of on-disk formats. One trick Lucene does not have: a term matching more than ~1.6 % of the
corpus automatically gets a **bitmap** next to the posting list — for such dense terms a bitmap is
smaller and faster than a list. And the main difference: **there are no block-max impacts in the
files**. Vespa interleaves the field length and the number of occurrences into the posting list (so BM25
is computed without touching positions), but an upper bound of a block's score does not exist — there is
nothing to skip safely from.

**WAND as an operator the user writes.** And here is the biggest behavioural difference for a reader of
chapter 4 of part two. Lucene's MaxScore is invisible and **rank-safe**: you write an OR query, the
engine skips by itself, and the top-k is guaranteed to be the same as if it had scored everything. Vespa
has `weakAnd` instead — an operator you deliberately write into the query and which does something
different: it computes a single coarse ceiling for every term (weight × IDF, derived from the number of
documents in the dictionary — no per-block refinement) and picks roughly `targetHits` candidates by an
**internal auxiliary score** that is not your real ranking expression. Real scoring runs only over those
candidates. The consequence: `weakAnd` can omit a document your ranking would rate highly — recall is
approximate **deliberately**, as a conscious trade for latency. On top of that Vespa has **match-phase
degradation**, a safeguard without an equivalent in Lucene: when a query matches too many documents (say
two million for "iphone case"), the engine ANDs it at runtime with a range over a chosen attribute —
"out of those two million consider only the 200 thousand most popular". Bounded latency bought with
recall, expressed as configuration.

**Ranking: a three-phase pipeline inside the engine.** Lucene gives you one `Similarity` and one score;
the two-phase evaluation from the end of part one (a cheap function selects candidates, an expensive one
reorders them) you build yourself in the application. Vespa has that pattern built in as configuration:
the **first-phase** expression is computed for every match right on the data node, **second-phase**
re-scores only the top-N on the node, and **global-phase** runs in the Java layer over the results
merged from all nodes. Concretely, in a schema definition it looks something like this: first-phase
`bm25(name) + 0.3 * attribute(popularity)` — cheap, runs on every match; second-phase
`onnx(rerank_model)` with `rerank-count: 200` — an expensive model, but only on the two hundred best per
node. Ranking expressions are a full-fledged language with tensor arithmetic, so an "expensive model"
may literally be a neural network over the document's tensors. Expensive logic runs next to the data,
not over the wire above it.

**HNSW: one mutable graph per node.** Recall both traps from chapter 8 of part two: a graph per segment
(N walks, diluted recall) and an expensive merge (the graph is built again on merging). Neither arises
in Vespa, because there is one graph, long-lived, and documents are inserted into and deleted from it
continuously — under the same generational protection as the attributes. Insertion is cleverly split in
two: the expensive part (finding candidate neighbours by a graph walk) runs on any thread and changes
nothing; the write thread then merely applies the finished list of edges. Filtered search Vespa solves
by the same reasoning as Lucene (a global filter carried into the walk, switching strategy by
selectivity), only with the filter computed once for the whole node.

**Memory and Czech.** The memory doctrine divides the world in half: **forward data** — attributes, the
document map, the HNSW graph, the memory index — must be resident in RAM (attributes can optionally be
paged out into an mmapped file, at the cost of page faults); the inverted disk index and the document
store are mmapped as in Lucene. In practice: the capacity of a Vespa node is determined by the size of
the attributes, not by the disk — Lucene will serve you an index larger than RAM (more slowly), Vespa
simply has to have the forward data in memory. And a gem to finish: linguistics (tokenization, stemming)
lives in the Java layer, the default implementation is OpenNLP — and that **does not stem Czech at
all**: the map of languages to Snowball algorithms does not contain Czech, so what remains is
tokenization, normalization and lowercasing. The official escape hatch? The `lucene-linguistics` module,
which wires Lucene analyzers into Vespa. A Czech Vespa deployment therefore ends up in practice at
`CzechStemFilter` from chapter 3 of part one — inside a completely foreign engine.

---

## 6. Meilisearch: an index in a transactional KV store

A short introduction: Meilisearch (2018, a French company, written in Rust; the engine itself is called
*milli*) aims elsewhere than everything before it: it is a single-purpose, single-binary server for
"instant search" — suggestions and search-as-you-type, with typo tolerance as its main calling card. No
cluster, no JVM tuning; you download it, run it, send JSON. Architecturally it is the most radical cut
of the whole comparison: **no segments, no merges, no format files of its own.**

**The whole index is one LMDB database.** LMDB is a ready-made, embedded key-value database: a sorted
B-tree of keys and values, mapped into memory (mmap — reads go through the page cache exactly as with
Lucene), with ACID transactions. Writing works in a **copy-on-write** style: a transaction does not
overwrite B-tree pages in place but writes modified copies and swaps the root at commit; the old pages
fall onto a free list and the next transaction recycles them. Readers meanwhile see a consistent
snapshot as of the moment they began (MVCC — multi-version concurrency control) and do not block against
the writer in any way. Read that paragraph again with the eyes of part one: copy-on-write pages do
exactly the work immutable segments do in Lucene (readers see a stable world), and the free list does
the work of merging (reclaiming space) — except continuously and incidentally, without a merge policy,
without background threads, without "too many segments". The tax is on the other side: LMDB permits
**one write transaction at a time**, so the final write of a batch is serial in principle. (Meilisearch
damps this by having term extraction from documents run in parallel on all cores, with finished goods
flowing into the single write thread through lock-free queues.) And one curiosity with a lesson: an LMDB
key may be at most 500 bytes — and because terms are keys (see below), a hard limit of 250 bytes per
indexed word follows. A search engine's limit dictated by the storage beneath it.

**Posting lists are roaring bitmaps — and positions live in the keys.** Inside that one LMDB environment
there are ~27 named sub-databases and you will understand the whole design best by reading a few of
their keys and values literally:

- `word_docids`: key "bike" → value: a bitmap of the numbers of documents containing "bike".
- `word_position_docids`: key ("bike", position 3) → a bitmap of documents where "bike" is at position 3.
- `word_pair_proximity_docids`: key (distance 2, "mountain", "bike") → a bitmap of documents where those
  two words are two positions apart.

The value is always a RoaringBitmap (you know that format from part two — `IndexedDISI` is "directly
inspired" by it) — and intersection, union and difference, the bread and butter of chapter 1 of part
one, are native operations on bitmaps without merging iterators. For lists of up to seven documents the
bitmap header does not pay off, so seven numbers are simply stored — the same instinct as Lucene's
shortcut for terms with one document, invented independently. What is essential, though, is the shift
with positions: Lucene stores a list of positions per document and computes phrases/proximity at query
time by walking; Meilisearch **precomputed** questions of the type "are those words close?" **into the
keys** and merely looks them up at query time. Proximity thereby becomes a lookup instead of a
computation — except that it has only four possible values (distance saturates at 4; "far apart" and "in
a different field" are the same value) and exact positions are maintained only up to the sixteenth,
after which resolution degrades in steps. This model simply cannot express an exact phrase distance —
that is the price of the lookup.

**The dictionary: an FST that survived — and typos as an intersection of automata.** Meilisearch keeps
the dictionary of all words as an FST (a finite state transducer — a compact automaton sharing common
prefixes and suffixes of words). That is the nicest irony of the comparison: recall from part two that
Lucene **abandoned** its FST in 10.x, because it lay on the heap and weighed hundreds of megabytes for
large indexes. Meilisearch kept it — but as one value in LMDB, read straight from mmap; it was never on
the heap. Two machines arrived at "the dictionary is a mapped range of bytes" from opposite directions.
And the FST pays off here: typo tolerance is done by building a Levenshtein automaton from the query
word (a machine accepting all words up to k edits away) and **intersecting it with the dictionary** —
the result is exactly those indexed words that are 1–2 typos from the query, without trying candidates.
The permitted number of typos grows with word length (1 from five characters, 2 from nine — for "bike"
nothing can be tolerated, for "waterproof" it can). And there is a hand-tuned asymmetry in it: a typo in
the **first letter** counts as two — statistically people rarely err in the first letter, so let "dike"
not find "bike" quite so readily. Prefix search (for suggestions), by contrast, is not solved with an
automaton at runtime: the bitmaps of frequent prefixes are **materialized at indexing time** into their
own databases — a further portion of write amplification traded for a search-as-you-type query being one
lookup.

**Scoring: rules instead of a metric.** Now brace yourself: Meilisearch has **no BM25, no IDF, no
norms** — no scoring function in the sense of part one. Ordering is a cascade of **rules** in a
user-given order; the default is: words, typo, proximity, attribute, sort, exactness. Every rule takes
the current set of candidates (a bitmap) and cuts it into buckets from best to worst; the next rule then
sorts only within a bucket. Concretely for the query "mountain bike": the *words* rule puts documents
with both words in front, documents with only one behind them; *typo* within that prefers matches
without a typo over matches with one; *proximity* puts documents where the words are close together in
front (a lookup into those precomputed pairs!); *attribute* prefers a match in the name over a match in
the description; and so on. Notice what is not in it: occurrence frequency plays no role, word rarity
plays no role. It is a ladder of priorities, not a weighted sum — simpler to explain to a customer ("why
is this first? it has both words without a typo in the name"), coarser at fine discrimination.

And now the consequence that follows for the third big question: when there is no score composed of
contributions, **there is no upper bound of the score either — impacts and MaxScore have no way to exist
here.** There is nothing to skip safely. Meilisearch insures itself with a clock instead: the default
query deadline is 1500 ms, and when it expires the engine dumps the remaining candidates **unsorted**
and marks the response `degraded: true`. Put those two philosophies side by side, because it is perhaps
the deepest sentence of the whole comparison: *Lucene degrades work* (it skips blocks it can prove will
lose — the result is still exactly correct), *Meilisearch degrades quality* (it stops ordering, but
answers in time).

**The rest briefly.** Analysis is configuration, not code: one built-in tokenizer (the charabia library)
tuned by four settings keys — you cannot build your own filter chain after Lucene's fashion; Czech runs
through general Latin segmentation plus diacritics normalization, which is a decent foundation and a
ceiling at the same time. Vectors: an HNSW graph (the hannoy library; M=16, efConstruction=125) stored
as keys and values **in the same LMDB environment** — so a text and a vector write commit atomically in
one transaction, one graph per embedder, no per-segment multiplication; graph maintenance does,
however, sit on that single serial writer. Visibility and durability: **the commit of an LMDB
transaction is both at once** — no refresh, no flush, no WAL (the transactional semantics of the storage
replace the log here); above it sits a task queue that batches writes (the API returns a task id and the
batch commits at once). And the limits of the design: there is no distribution in the open-source build
(sharding exists in the code, but behind a paid feature flag), write throughput is capped by the single
writer, and long-running read transactions hold old pages — a slow query brakes space reclamation, the
counterpart of "an open IndexReader holds deleted segments".

---

## 7. Typesense: everything in RAM, disk only for recovery

A short introduction: Typesense (C++, since 2015) positions itself as an open-source alternative to
Algolia — a hosted service for instant search — and its central bet is the simplest of all: **the whole
index lives in main memory as ordinary data structures.** Recall the question from part two, "how can
search be fast when the index is not held in memory?" — Typesense answers "not holding the index in
memory is an error in the brief; buy RAM."

**The disk is only a recovery log.** On disk (in the embedded KV database RocksDB) lie only the raw JSON
documents and metadata — none of it a searchable structure. On startup the server reads all the
documents and **builds the index from scratch in memory**; the project's README states 78 minutes for 28
million books. Set that against Lucene's mmap: Lucene has a cold start for free (the files are merely
mapped) and pays with the first queries, which pull pages from disk; Typesense pays the start at the
full price of indexing and then never waits on disk again — no page faults, no decompression on the read
path, and because it is C++, no GC pauses either. The capacity rule from the README: RAM ≈ 2× the size
of the indexed data, and no knob changes anything about that. An index larger than RAM does not exist
here as a mode.

**The dictionary: an adaptive radix trie with a score hint.** The term dictionary is one ART (adaptive
radix tree) per field — a trie whose nodes change their size according to the number of children
(variants for 4, 16, 48 and 256 descendants), so that sparse places in the tree do not waste memory.
Unlike Lucene's block tree it is mutable — terms are added and deleted in place, no per-segment
duplication of the dictionary. Typesense added one peculiarity to it: every node carries a `max_score` —
the best document score in its whole subtree. What that is for you will see in a moment. Typos are
handled differently from Meilisearch: instead of building an automaton, the trie is walked with a
Levenshtein dynamic table in hand — every edge of the tree shifts the table by a character and the
subtree is discarded as soon as even the best row of the table cannot end up under the typo limit.
Asymptotically the same prefix pruning as automaton ∩ FST, only with the cost distributed differently.
And `max_score` in the nodes governs **which variants of a word to expand at all** — a best-first
selection of candidate terms ("bike" has typo neighbours "bikes", "bine", "like"…, take them from the
most promising). It is the only place in the system reminiscent of impacts; note, though, that it prunes
*terms*, never *documents*.

**Posting lists: Lucene blocks, but mutable.** A posting list is a chain of blocks of at most 256
documents, bit-packed with FOR compression — in spirit exactly the packed blocks from chapter 2 of part
two, with one fundamental difference: a Lucene block is written once and never touched again, a
Typesense block is **repacked on every insert and delete** (and merged with a neighbouring block on
underflow). Word positions (for phrases and proximity) lie right in the block next to the document
numbers — no separate `.pos` file, so proximity is always at hand and always paid for in RAM. Deleting
is real deleting: the document is read from RocksDB, tokenized again, and its id struck out of every
posting list it belonged to. No tombstones, no live docs, no merging — the whole machinery of chapters
8–9 of part one is not here, because there is nothing for it to work against. The price is mirrored: the
writer holds an **exclusive lock** on the collection for the duration of a batch, so a large import
stops queries — exactly the interference of readers with writers because of which Lucene invented
immutable segments.

**Scoring: a ladder of priorities in one number.** The second engine without BM25 — in the whole code
base there is neither IDF nor document length. The `_text_match` score is a single `uint64_t` into
which, from the highest bits, are packed: the number of query words found, the number of unique ones,
the cost of typos (fewer = better), proximity (a sliding window of 10 tokens over the positions), an
exact-match flag, the position of the match in the field (earlier = better). Comparing two documents is
one integer comparison and the semantics are lexicographic — like sorting by several columns. An example
on the query "mountain bike": document A contains both words exactly, but far apart; document B has them
right next to each other, except one with a typo. A wins — the cost of typos sits higher in the ladder
than proximity, so "without a typo" beats "next to each other" regardless of by how much. And a document
with fifty occurrences of a rare word scores **the same** as a document with one occurrence — frequency
is not in the ladder at all; field weights only break ties. And the same consequences as with
Meilisearch: without an accumulated score there is no upper bound, without an upper bound there is no
MaxScore — every candidate from the intersection is scored. The safeguard is again a deadline (30 s by
default, checked every 65,536 documents): when it expires, what there is is returned with a truncation
flag. A timeout, not a proof. The whole engine's bet in one sentence: brute force over pointers in RAM
beats clever pruning over mmapped bytes.

**Analysis and Czech.** An analysis chain in the Lucene sense does not exist — there is one fixed
tokenizer switched by `locale`. For Czech that means **only** removing diacritics (transliteration via
iconv: "příliš" → "prilis") on both sides, nothing more: no ICU segmentation, no stemming. A Snowball
stemmer can newly be switched on per field, but Snowball does not do Czech; the escape hatch is a
manually uploaded dictionary of forms (word → base). "Kolo" and "kola" are two different terms for
Typesense until you supply it with that dictionary — for Czech e-commerce this is probably
disqualifying, unless you are willing to maintain a dictionary.

**Vectors and durability.** HNSW: one mutable graph per vector field (the vendored hnswlib library,
M=16, efConstruction=200) — again no per-segment graphs; deletion is soft (marking a node) with a
background repair pass that rewires orphaned nodes. Filtered search: the filter is evaluated as a
predicate during the walk, and when it is very selective the graph is skipped entirely and brute force
over the handful of candidates is used — the same reasoning as Lucene's fallback from part two.
Durability is the most exotic part: **every write, even on a single node, goes through Raft consensus**
(Raft = a protocol by which a group of servers agrees on the order of operations; here the vendored
braft). A write is first appended into the Raft log, only its application indexes; RocksDB has its own
WAL switched off for documents — the source of truth is the Raft log plus hourly checkpoints. A write
log is therefore here too, only elevated into a cluster protocol. Visibility has no concept of refresh:
what was applied under the lock is seen by the next query. And the HEAD commit of the checkout is a nice
illustration of the fragility of the model "disk = a recovery log": a field marked `store: false` is
indexed but not stored — and after a restart there is nothing to build it from.

---

## 8. Quick orientation: who answers the big questions how

**Write log (WAL).** Lucene does not have one — durability is only an expensive commit — and everyone
added one: Solr's tlog, ES/OS's translog, Vespa's transaction log server, Typesense's Raft log. The only
one that does not need a WAL is Meilisearch: the commit of an LMDB transaction is durable in itself, so
there is nothing for a log to catch up on.

**Visibility vs. durability.** Lucene: an NRT reader (fast, non-durable) vs. a commit (expensive,
durable). Solr named those two concepts (soft/hard commit) and gave them timers; ES/OS automated them (a
refresh every second, a flush on thresholds). Vespa decoupled them entirely — visible immediately,
durable immediately via the WAL, writing the index to disk whenever it suits. Meilisearch and Typesense,
conversely, merged them into a single moment — and paid with a serial writer and an exclusive lock
respectively.

**Where the index lives.** The page cache via mmap: Lucene, its whole family, and also Meilisearch
(LMDB is mmapped). Vespa splits the world: forward data (attributes, HNSW) resident in RAM, inverted
data on disk via mmap. Typesense: everything on the process heap, the disk is not on the read path at
all.

**Segment immutability.** Lucene rests on it. Solr and OpenSearch cashed in on it — an immutable file
can be replicated by plain copying. The in-house machines all rejected it and each secured lock-free
reading differently: Vespa with generations (RCU), Meilisearch with the MVCC transactions of a
copy-on-write B-tree, Typesense with an ordinary reader-writer lock (and it is therefore the only one
that admits a large write will stop queries).

**Top-k and skipping.** Rank-safe skipping (impacts + MaxScore, the result guaranteed correct): only
Lucene and its family — ES/OS merely condition it on the `track_total_hits` default. Vespa has WAND as
an explicit operator with admittedly approximate recall. Meilisearch and Typesense have no score whose
ceiling could be estimated — their safeguard is a deadline and an admitted degradation of the response.
A mnemonic: Lucene degrades work, they degrade quality.

**Scoring model.** BM25 (a metric: frequency × rarity × field length): Lucene, Solr, ES, OS, and Vespa
(there as one of the functions in rank profiles). A ladder of priorities (lexicographic comparison,
without IDF — word rarity plays no role): Meilisearch (a cascade of rules) and Typesense (a bit-packed
tuple).

**Vectors.** Per-segment immutable graphs with a rebuild on merge: Lucene, Solr, ES. One mutable graph:
Vespa (per node, generational protection, a two-phase insert), Meilisearch (in LMDB, atomically with the
text), Typesense (hnswlib per field). OpenSearch: outside the core, in a separate plugin with a choice
of engine.

**Czech.** A full chain of stemming + stopwords out of the box: Solr, ES, OS — everywhere it is the same
Lucene `CzechAnalyzer` from part one. Vespa: no stemming until you wire in the module with Lucene
analyzers. Meilisearch: Latin segmentation + diacritics normalization. Typesense: only removing
diacritics; stemming only via a manually supplied dictionary.

---

## 9. What to take away

First: **the Lucene family does not differ from Lucene in anything you learned in the first two parts.**
Analysis, segments, encoding, impacts, HNSW — everything holds literally. They differ in the things the
library deliberately leaves to you: the write log, the schema, distribution, document identity. That has
a pleasant consequence for learning: whoever knows the NRT reader and commit understands soft/hard
commit and refresh/flush; whoever knows why a segment is immutable understands why a replica can be
maintained by copying files.

Second: **the in-house machines do not lose on details of encoding — they play a different game.** Vespa
bet on mutable columns and partial updates (for a catalog where prices and stock change a thousand times
a second, that is exactly it), Meilisearch on the simplicity of the KV model and precomputed answers for
search-as-you-type, Typesense on RAM being cheaper than complexity. And all three pay with something
Lucene gets for free from immutable segments: Meilisearch with a serial writer, Typesense with a lock
blocking queries during a write, Vespa with a node's capacity ceiling set by RAM.

Third, for your context: two things from this comparison are worth stealing regardless of the choice of
engine. **Decoupling durability from visibility via a write log** — Vespa shows the endgame: a flush
stops being a question of safety and becomes an optimization decision. And **two-phase ranking as close
to the data as possible** — Vespa's first/second-phase is exactly the consideration from the end of part
one (cheap candidates, an expensive reordering with business signals), elevated from application code to
engine configuration. And one warning in parting: the choice of scoring model is architectural and falls
at the beginning. A model without upper bounds (a cascade of rules, a lexicographic tuple) cannot be
rescued by impacts afterwards — whoever wants rank-safe skipping one day must have a score composed of
estimable contributions from day one.

---

### Version notes

Read from these revisions (all August 2026): **Solr** main, 11.0.0-SNAPSHOT on Lucene 10.4.0
(`5c28547dd8`); **Elasticsearch** main, 9.6.0-SNAPSHOT on Lucene 10.5.0 (`9a100e2d0e`); **OpenSearch**
main, 3.9.0 on Lucene 10.5.0 (`36edc05ac8`); **Vespa** 8.x (`780f10016e`); **Meilisearch** v1.53.0
(`594f0e59d4`) — heed/LMDB 0.22, roaring 0.10, charabia 0.9, fst 0.4, hannoy 0.1; **Typesense** nightly
(`ee7784f331`). Lucene for comparison is the same checkout as in the previous parts: 11.0.0-SNAPSHOT,
format `lucene104`.

The constants in the text (Solr 15 s/3 s, ES refresh 1 s and flush 10 GB/1 min, `track_total_hits`
10,000, oversampling 3×, BBQ from 384 dimensions, the 1000-field mapping cap, the OS cap of 4 slices and
the cache ratio of 5×, Vespa `visibilitydelay` 0, a bitvector from 1/64 of the corpus, 4 KiB dictionary
pages, the Meilisearch threshold of 7 documents, proximity max 4, exact positions up to 16, typos from
5/9 characters, a deadline of 1500 ms, a 500 B key → a 250 B word, hannoy M=16/ef=125, Typesense blocks
of 256, a window of 10, a cutoff of 30 s, hnswlib M=16/ef=200, RAM ≈ 2×) are read directly from the
source files of the stated revisions.
