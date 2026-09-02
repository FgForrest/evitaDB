# Bitmap memory optimizations: sharing between trees and mmap from disk

**Research 2026-08-14.** The brief from the sponsor: evitaDB holds its indexes in memory and RAM has got
more expensive. Fulltext will add another tree of keys — stems — which will largely lead to the same sets
of primary keys as the tree of surface forms. Examine two optimizations:

- **O1:** instead of the stem tree materializing a unioned bitmap, hold in it only references to the
  existing `PersistentRoaringBitmap`s from the original tree — similarly to what reduced price list
  indexes do — and pay for the union (OR) only at the moment of use;
- **O2:** consume `PersistentRoaringBitmap` through mmap from disk. That would save Xmx outside fulltext
  too, because there are very many bitmaps in the indexes.

Everything below is verified by reading source code, not from memory. The revisions verified against:
evitaDB `dev` `6a486f0a56`; a RoaringBitmap checkout `d77f8806` (note it is the fork novoj/RoaringBitmap
with local prototypes — the `buffer` package this is about is, however, byte-identical with upstream
`ba92f497`); Lucene `13796f80e`; Elasticsearch `9a100e2d0e41`; Vespa `780f10016ed`; Meilisearch
`594f0e59d`; Typesense `ee7784f3`. The `file:line` references hold for those revisions. Translated from
Czech and moved into this record on 2026-08-24.

---

## 1. Summary

**O1 — the principle is right, the form has to be different.** (By a union this document means the union
of sets — see the explanation with an example in §2.1.) Not materializing the union is a good instinct and
evitaDB does it that way itself almost everywhere. Holding **references to instances** of bitmaps is not
possible, though: a commit replaces the affected bitmap with an entirely new object, so a held reference
starts silently pointing at stale data after the first write. And the precedent of the reduced price
indexes — read from today's code, not from tradition — says the exact opposite of holding references: the
reference to the superordinate index was **deliberately removed**, because it forced even unchanged indexes
to be rebuilt in every transaction. Only immutable value objects are shared and everything else is looked
up **by key, anew on every operation**. The right shape for the stem tree is therefore: an entry holds **a
list of term keys** (not bitmaps), the bitmaps are looked up in the tree of surface forms at query time and
unioned by the "lazy" route today's `OrFormula` uses. Details in §2.

**O2 — real zero-copy reading from disk is locked today; a weaker, still useful variant is, however,
available.** The good news: the bytes we write to disk today **already are** in the standard format an mmap
reader would be able to read — the serialization does not have to be changed. What is locked is everything
around it: a bitmap cannot be found in a record without parsing the whole record, large records fragment on
disk, our RoaringBitmap fork does not contain the classes for reading from a buffer at all, and Java can
release a mapping safely only from version 22. The precedents of the other engines moreover speak against
it for bitmaps: even Meilisearch, which has its whole index in mmap, copies bitmaps into its own memory on
every read. The realistic path has two storeys: **storey 1** — load parts of the index from disk lazily, on
first use (it works right away, without touching the library), **storey 2** — real zero-copy through a
dedicated bitmap file, conditional on Java 22 and on vendoring another ~14 thousand lines of the library.
Details in §3.

---

## 2. O1: the stem tree shares the postings of the surface forms

### 2.1 What is proposed — on an example

The tree of surface forms (a front-coded `TransactionalBucketBPlusTree`, see `p1-index-core.md` §3.1) maps
every form of a word, as it occurred in the documents, onto its postings — a bitmap of primary keys:

```
"bunda"  → {3, 17, 250, 1024, …}
"bundy"  → {17, 88, 250, …}
"bundě"  → {3, 88, …}
"bundou" → {1024, 2048, …}
```

Stemming normalizes all four forms onto the stem "bund". The stem tree is therefore to answer the question
"which documents contain *any* form of the word bunda" — and the answer is a **union**: the set containing
every primary key that occurs in **at least one** of the input sets. In our example: document 3 has "bundu"
and "bundě", document 88 only "bundy" and "bundě", document 2048 only "bundou" — all three are in the
union, each once (a union knows no duplicates; that a document contains two different forms changes nothing
about its membership). Over bitmaps a union is computed with the bit operation OR — which is why "union"
and "OR" are used interchangeably in the text: OR is the way of computing, the union is its result. The
counterpart is the intersection (AND): the documents present in **all** the sets at once — fulltext uses
that elsewhere, when composing a multi-word query.

The naive solution computes that union at indexing time and stores it as a fifth bitmap:

```
"bund" → {1, 3, 17, 88, 250, 1024, 2048, …}     ← a second copy of all the postings
```

The O1 proposal instead holds in the stem tree only the information "bund = bunda + bundy + bundě + bundou"
and computes the union only when a query actually needs it. The memory saving is obvious — the stem tree
then contains no bitmaps, only keys and short lists. The question is whether it is safe (transactionally)
and what it costs on read. The answers are in §2.3 and §2.6; first, though, to the precedent that motivated
the proposal.

### 2.2 How sharing in the reduced price indexes really works today

Price indexes have two levels: a **super index** owns the complete price data for a (price list, currency)
combination and **reduced indexes** — one per referenced entity — hold the same view narrowed to the
entities falling under the given reference. It is our closest internal pattern for the situation "two
structures over the same data", and therefore it is worth reading precisely. Three observations:

**First: what is shared are immutable value objects — never bitmaps.** A `PriceRecord` is a Java `record`,
i.e. an object that cannot change after creation
(`evita_engine/.../price/model/priceRecord/PriceRecord.java:51-57`). On adding a price it is created once
and the same object is inserted into the super index and into the reduced index
(`PriceListAndCurrencyPriceRefIndex.java:91-95,299-304`). Bitmaps, by contrast, are owned by each index
separately — none is shared between super and reduced
(`AbstractPriceListAndCurrencyPriceIndex.java:97,102`). Sharing an immutable object is trivially safe:
nobody ever overwrites it, so it cannot "go stale under anyone's hands". A bitmap in a transactional
wrapper is not immutable — and that is precisely why this pattern does not share one.

**Second: a reduced index holds no reference to the super index.** When it needs something only the super
index has, it receives it **as a parameter of the operation** and looks up what it needs in it **by key**.
The JavaDoc says so explicitly: the super index "is supplied by the caller on every operation — this index
holds no reference to it, so it is not pinned to a catalog version and can be carried between versions by a
plain reference" (`PriceListAndCurrencyPriceRefIndex.java:55-58`; the lookup by key is
`superPriceIndex.getPriceIndexOrThrow(this.priceIndexKey)` at `:145-147`).

**Third — and this is the essential part — the reference used to BE there and was deliberately removed.**
A comment in `EntityCollection.pruneMergeIndexes` records why
(`evita_engine/.../core/collection/EntityCollection.java:2677-2682`): a reduced index once held a reference
to the global index of its scope. The global index is, however, rebuilt in almost every transaction — and
because the held reference had to point at the new instance after the rebuild, **every clean reduced index
had to be rebuilt in every transaction too, purely to refresh that reference**. A commit that should cost
proportionally to the size of the change was becoming a commit proportional to the size of the whole index
forest. After the reference was removed, an unchanged index is carried between catalog versions whole, by a
single reference, without the commit touching it at all (`:2661-2670`).

The precedent therefore reads: *share immutable objects, look up by key on every operation, never hold a
reference to an instance that can be rebuilt.* The variant with a held reference existed and was deleted
for exactly the cost the naive form of O1 ("hold references to `PersistentRoaringBitmap` instances") would
reintroduce.

One more practical note about sharing and disk: Kryo does not preserve instance sharing on serialization —
every index writes a shared object as its own copy. That is why after loading from disk there is a step
that "collapses" the duplicates back onto a single shared instance (`restorePriceRecordsFrom`,
`PriceListAndCurrencyPriceRefIndex.java:115-137`). For O1 that is good news: the stem tree owns no bitmaps,
it stores only lists of keys to disk, so no duplicates arise and no collapse after loading is needed.

### 2.3 Why a reference to a bitmap instance must not be held — the mechanics of transactional memory

A short reminder of how the MVCC layer works. `TransactionalBitmap` is a wrapper over
`PersistentRoaringBitmap`: readers see the committed state, writes go into the transaction's diff layer. At
commit `createCopyWithMergedTransactionalMemory` is called, which merges the diff with the original state
and returns an object representing the new committed state. And here is the key detail
(`evita_engine/.../index/bitmap/TransactionalBitmap.java:108-116`):

- if the transaction **did not touch** the bitmap, the method returns `this` — the object's identity
  survives and structures referring to it need not change;
- if it **did touch** it, the method returns `new BaseBitmap(...)` — **a new object, and even of a different
  class** (a plain immutable `BaseBitmap`, not a `TransactionalBitmap`). The owning index is rebuilt at
  commit and re-wraps the new bitmap into a transactional wrapper (`EntityIndex.java:307,314`).

What that means for a foreign structure that stored a reference to an instance: after the first write into
the given term its reference points at the **old** object nobody updates any more. No error is thrown — the
structure simply silently reads a stale set of documents. And were it to defend itself by refreshing the
reference at every commit, it is back at the problem of §2.2: it would have to let itself be rebuilt in the
rhythm of its referent, which is exactly the O(the whole forest) cost evitaDB has just engineered away.

(That rebuilding with a re-wrap is bearable for the owning index at all is made possible by the fork's
copy-on-write `clone()`, which is effectively O(1) — see §2.4. But that is an optimization for the owner; a
foreign holder of a reference gets nothing from it.)

### 2.4 The right shape: keys instead of instances, a lazy OR instead of materialization

The design following from §2.2 and §2.3:

**A stem entry holds a list of keys** — the surface forms, or their numeric term ids (the choice between
them is an open question, see §5). At query time the procedure is this: the query finds the stem, walks its
list of keys, looks each key up in the tree of surface forms and obtains the current postings. Both trees
live inside the same fulltext index, so the query always sees them **in the same catalog version** —
consistency between them is free, it is one transactional forest with one commit.

**The union is computed by the lazy aggregation the query layer already uses today.** A "lazy OR" means
that when merging many bitmaps the running cleanup of the internal structures is deferred to the end —
instead of N cleanups, one. `FastAggregation` handles that for us and that is exactly the path today's
`OrFormula` takes (`evita_engine/.../query/algebra/base/OrFormula.java:184` calls the varargs
`PersistentRoaringBitmap.or(...)`, which delegates to `FastAggregation.or`). In fact materialization is
mostly not even reached: the consumer is a formula tree, so a stem translates into an OR formula over
per-term leaves — the same pattern today's `superSetFormula` works by
(`FilterByVisitor.java:1683,1990-2000`) — and only what the query really needs is evaluated.

**One trap the implementation has to watch out for.** Our RoaringBitmap fork has copy-on-write sharing:
`clone()` does not copy the data, it merely marks the internal blocks (containers) with a "shared" flag;
the real copy of a block is made only when somebody wants to change it. The **binary** static `or(x1, x2)`,
though, has a side effect: the containers it takes into the result unchanged it does not copy but shares —
and while doing so it **writes sharing flags into both operands** (`markAllShared`,
`PersistentRoaringBitmap.java:612-624`). A write into an operand means two threads calling OR over the same
live index bitmap would reach into each other's memory — the fork's documentation explicitly forbids it
(`:77-79`). The binary `or` therefore must not be used for unioning at query time; the route through
`FastAggregation` does not share the operands' containers (it builds its own result) and is safe. Today's
query code already observes this convention, the new fulltext path has to observe it too.

### 2.5 Why not to materialize the union — four independent reasons

**a) The cost of writing (write amplification).** A materialized union has to be updated on **every** change
of every document containing any of the surface forms. Insert a document with "bundou" — write into the
postings of "bundou" *and once more* into the union of "bund". Every posting is written twice, forever. A
list of keys, by contrast, changes only at two rare moments: when a new surface form appears in the corpus
for the first time (a key is added), and when the last document with that form disappears (the key is
removed). After the dictionary warms up, both events are rare. For P2, which addresses transactional
maintenance, it is an order-of-magnitude difference in the amount of work per mutation.

**b) The decrement problem.** A derived union has an insidious property on deletion. An example: document 17
contains "bunda" as well as "bundy". An editor changes the document so that "bunda" disappears from it. PK
17 is removed from the postings of "bunda" — but may it be removed from the union of "bund" too? **No**,
because "bundy" is still in the document. The union may drop a bit only when it proves no contributor
remains. evitaDB solves this problem for its derived unions in two ways: either with **persisted occurrence
counters** (in the cardinality indexes — a bit is removed only on a 1→0 transition of the count, and the
counters have to be stored to disk, otherwise correct decrementing would be impossible after a restart;
`AttributeCardinalityIndex.java:50-53`, `GroupCardinalityComponent.java:41-43`), or with a **control probe**
— before a bit is dropped it is verified that the entity has no other price (`containsAnyPriceOf`,
`PriceListAndCurrencyPriceRefIndex.java:224-232`). And where `entityIds` is a derived union, the codebase
outright **prohibits a plain decrement at the API level** — the single-argument `removePrimaryKey(int)`
throws an `UnsupportedOperationException` (`ReferencedTypeEntityIndex.java:487-492`,
`ReducedGroupEntityIndex.java:443-448`). A materialized stem union would need the same persisted counter
apparatus. A list of keys does not need it at all: the query always reads fresh postings, so "decrementing
the union" does not exist as an operation.

**c) It is the convention of the whole codebase.** evitaDB materializes unions almost nowhere:
`superSetFormula` is a lazy formula over live bitmaps, the union of facet groups is computed on every query
(`FacetGroupOrFormula.java:120-132`), hierarchical subtrees are a `DeferredFormula` over a tree walk.
Repeated computations across queries are handled by the formula cache (`CacheEden`), and it handles
invalidation elegantly: every `TransactionalBitmap` gets a unique sequential id on creation
(`TransactionalBitmap.java:62`) and the cache keys precisely by it — after a commit a new instance with a
new id arises, the old keys stop matching and the entries naturally fall out. A reference-based stem tree
fits into that architecture; a materialized union would depart from it.

**d) Scoring would decompose the union anyway.** Ranking needs to know *which* form matched in the
document: the EXACTNESS lane distinguishes an exact match of the surface form ("bunda" sought, "bunda"
found) from a match through the stem ("bunda" sought, "bundou" found). And the relaxation ladder (Q16 in the
query design, ADR 2026-08-14) works with per-term postings ordered by selectivity. Both mechanisms
therefore need the individual postings, not their mixture. A materialized union would have to be decomposed
back into its components for every ranked query — we would pay memory for a structure the hot path does not
want and the cold path does not need.

### 2.6 What it costs on read and what P1 is to measure

The query's cost is K lookups in the tree of surface forms plus a lazy OR, where K is the number of forms
mapping onto the given stem. For Czech, K is in single digits for ordinary words and in the low tens for
frequent lemmas. What matters is that it is **the same work the query does anyway** when expanding synonyms
or typo variants — several terms from one input word and their union is the basic move of a fulltext query,
no new mechanism.

The degenerate case is free: when the stem equals a single surface form (digits, abbreviations,
indeclinable words), K = 1 and the "union" is a plain reference — no OR happens.

Two things belong in P1's measurement (step K3):

- **the distribution of K on a real corpus** — it decides whether it makes sense to consider a cache of hot
  unions (given point c above probably not, but the number should exist);
- **the memory of the stem tree in both shapes** — with lists of keys vs. with materialized unions. The
  expected difference is a second copy of all the postings, i.e. an item of the order of the estimates of
  §4.8 of the research.

And one consequence for the question P1 got delegated from P5 ("should the dictionary carry only the stem,
or the stem and the surface form?"): a reference-based tree is a **third variant**, which preserves the
surface forms — which the suggester (P3) and the EXACTNESS lane want — and yet does not duplicate the
postings. K3 should measure it beside both the original ones.

### 2.7 The cost of the keys: why collators do not belong here and how to make strings cheaper

The sponsor's objection: fine, we do not duplicate the bitmaps — but will the memory not grow again by
holding lots of expensive keys, i.e. strings, collators and everything around them? The answer has two
parts and both are favourable.

**Collators do not belong in a term dictionary — and it is a design rule, not a coincidence.** Expensive
comparison through a `Collator` (and with it a collation cache, keys and all the overhead we know from the
attribute indexes, where collation was the most expensive item of the write CPU profile) exists because
ordering attributes is **visible to the user** and has to be linguistically correct — "ch" after "h" and so
on. A term dictionary has no such requirement: it is an internal structure whose ordering nobody will ever
see. The terms are moreover the output of an analyzer that already performed the normalization
(lowercasing, possibly diacritics). A binary comparison suffices for the dictionary — a plain comparison of
characters, no collator, no cache. That is exactly what Lucene does: it orders terms by pure UTF-8 byte
order. A prefix scan for the suggester over a binary order works unchanged and the suggester orders its
candidates by popularity anyway, not alphabetically. And there is a third reason, the hardest one: a
collator's behaviour can change between JDK versions and language data — a binary order is eternal. For a
structure that has to be deterministically replicated from the WAL on every node, that is an argument in
itself. The bucket B+ tree takes its comparator in its constructor (`InvertedIndex.java:183,230`), so
technically nothing prevents it.

**Strings in the reference lists: the stemmer gives us suffix encoding almost for free.** First to the trees
themselves: a front-coded tree does not hold one `String` object per key — the keys lie columnarly in leaf
blocks as shared runs of bytes, front coding takes care of that itself. The question is only the cost of the
**reference lists** in the stem tree. Holding full `String` objects there would be expensive (~40 B header +
data + a reference per form). But a look into the Czech stemmer shows it is not necessary: `CzechStemmer`
purely **trims** endings (`removeCase`, `removePossessives` only shorten) and the only thing it ever
rewrites is the **last one or two characters** of the stem — palatalization normalization čt→ck, št→sk,
c/č→k, z/ž→h and the dropping of an epenthetic -e-
(`lucene/analysis/common/.../cz/CzechStemmer.java:46-150`). A surface form therefore differs from its stem
only at the end. Concretely: "matka, matku, matkou" have the stem "matk" as a literal prefix; "matce,
matci" stem through the rewrite c→k onto the same "matk" and with the prefix "matc" diverge in a single
character. A reference list can therefore store every form as a **difference against the stem**: how many
characters to discard from the stem's end + what characters to append — typically single-digit bytes per
form, stored in one shared byte array per entry, no objects. The full key for the lookup in the tree of
surface forms is composed from it by concatenation.

The "matka" example incidentally also shows why a reference list has to exist at all and cannot be replaced
by a mere prefix scan of the surface tree by the stem: a scan for "matk" finds "matka, matku, matkou" but
**misses** "matce" and "matci" — the palatalized forms with the prefix "matc" lie elsewhere in the tree. The
list is therefore the carrier of precisely those exceptions the prefix does not cover.

The arithmetic for an idea (orders of magnitude, not measurements): for a stem with three forms a
suffix-encoded list costs roughly 10–20 bytes; the same list as an array of `String` objects, hundreds of
bytes; a materialized union of postings, kilobytes to megabytes depending on the word's frequency. Suffix
encoding is therefore two to four orders of magnitude cheaper than the variant O1 avoids, and the question
"strings vs. term ids" (§5) is shifted by it: a numeric term id (4 B per reference) would require a stable
surrogate id and an id→term map that would hold the strings we wanted to save a second time — suffix
encoding is cheaper and simpler.

---

## 3. O2: mmapping bitmaps from disk

### 3.1 The basics: what mmap really does — and three different things called that

`mmap` is a system call by which a process has a file "projected" into its address space: it gets an address
and from then on reads the file with ordinary memory accesses. The magic is that the operating system does
not load the data in advance — it loads a 4 KB page only when the program first touches it (a page fault),
holds it in the **page cache** (the system's shared disk cache), and when memory is short it may quietly
evict it again — an unchanged page need not be written anywhere, on the next touch it is simply loaded from
disk again. The attraction for us is obvious: data that today lies on the heap (and counts towards Xmx)
would lie in a file and only the part currently in use would be in memory, managed by the kernel.

The catch is that **a Java object cannot live in a file**. The heap belongs to the garbage collector; only
*bytes* can be mapped. From that follow three fundamentally different techniques, all called "mmap", and the
survey of engines showed they are commonly conflated:

1. **Map the file, but decode into heap objects on every use.** The file is the data's persistent form;
   reading from it produces ordinary Java objects the GC collects after use. The saving: the whole structure
   is not in memory, only the parts currently touched (the rest is held by the page cache, which can evict
   it). The non-saving: the hot path still allocates. That is how Lucene reads postings and Meilisearch
   bitmaps.
2. **Map the file and run the algorithm directly over the bytes.** Nothing is decoded; the code reads bytes
   at offsets. The only technique that removes the structure from the heap entirely. The cost: it works only
   for operations somebody **rewrote so that they can work over the serialized layout** — you do not get a
   zero-copy data structure, you get zero-copy versions of those specific operations somebody took the
   trouble to rewrite. That is how Lucene reads the term dictionary and doc values.
3. **Map an empty file as a store of pages for a still-mutable structure in memory.** That is swap in user
   space: the structure does not change, only the pages under it belong to a file, so the kernel may page
   them out. Those are Vespa's "paged" attributes — its C++ allocator redirects allocations into a file
   `swapdirs/swapfile` deleted at startup (`mmap_file_allocator.cpp:30,37-38`, `proton.cpp:400`). **For the
   JVM this path is closed**: one cannot tell the garbage collector to back part of the heap with a file.
   Vespa therefore cannot be cited as a precedent for us — the Java counterpart of "get the bytes off the
   heap" always leads back to technique 2 with all its work.

The goal "lower Xmx and keep evaluating queries over bitmaps" therefore means: technique 2 in the best case,
technique 1 as the always-available foundation.

### 3.2 The good news: the bytes on disk are already right today

The first question was whether an mmap reader would even understand what we write to disk today. The answer
is yes, and without any change of serialization. The Kryo serializer of bitmaps namely does nothing of its
own — it hands the RoaringBitmap library the output stream directly and lets it write its own format
(`TransactionalIntegerBitmapSerializer.java:49-52`). There was one thing that could have killed it: Kryo
writes numbers big-endian, whereas the Roaring format is defined little-endian. Verified in the code —
`RoaringArray.serialize` byte-swaps every value on its way out itself
(`evita_roaring_bitmap/.../RoaringArray.java:1089-1125`), so on disk lies **the standard portable
RoaringFormatSpec format as a contiguous, uninterrupted run of bytes** inside the record's payload.

That is exactly the format the upstream class `ImmutableRoaringBitmap` can read straight from a ByteBuffer:
its constructor merely slices the buffer from the current position, sets the endianness itself and reads
only the metadata — the containers' data stays in the buffer and is read on demand
(`buffer/ImmutableRoaringArray.java:42-52`). The portable format has no alignment requirements. The
serialization therefore need not be changed. Everything else unfortunately does — four locks follow.

### 3.3 Four locks of today's storage format

**a) A bitmap cannot be found in a record.** A `FileLocation` — the only address the storage knows — is a
pair (file offset, record length). It points at a **record**, nothing finer. Inside a record the bitmap is
preceded by Kryo framing of variable width: a length varint, class registrations, further fields of the part
(`FilterIndexStoragePartSerializer.java:49-66`). Varints have the property that their length is discovered
only by reading them — they cannot be skipped over. In the paged form N preceding buckets of variable width
moreover lie before the sought bitmap, and a single-element bucket has no bitmap framing at all — it is a
bare varint (`BucketLeafPagePartSerializer.java:65-79`, `BUCKET_KIND_PRIMITIVE`). Whoever wants the k-th
bitmap has to parse everything before it; and its length they will discover only by parsing its own header.
An mmap reader would need an **offset directory** (a table "bitmap → offset + length") that does not exist
today and has nowhere to be written.

**b) Large records fragment.** Writing goes through a buffer of size `outputBufferSize` (2 MB by default,
`StorageOptions.java:140,153`). When the payload does not fit into the buffer, what there is is written as a
fragment with a continuation flag and a new one starts — with 13 B of header and 8 B of checksum of the next
fragment inserted **into the middle** of the logical run of bytes
(`StorageRecord.OnBufferOverflowHandler`, `StorageRecord.java:1153-1161`). A dense postings bitmap of a
large collection really does exceed 2 MB. For mmap it is a hard cap: a fragmented record would have to be
stitched together before reading, stitching is copying, and copying is exactly what mmap tries to avoid.

**c) Compression — solved by the sponsor's proposal.** A deflate-compressed record cannot be mapped: the
bytes on disk are not the bytes the algorithm reads — they first have to be unpacked into fresh memory, so a
mapping buys nothing at all. Compression is today opportunistic **per record**: the writer tries to pack the
payload and keeps the result only if it came out smaller; the decision is carried by the `COMPRESSION_BIT`
in the header's control byte (`ObservableOutput.java:310-320`, `StorageRecord.java:89-101`), so the reader
**already today** decides per record by the header. The sponsor's proposal — selected storage part types
declare on their serializer "always store me uncompressed", even when compression is globally on — is
therefore a fully compatible change: old compressed records stay readable and new ones are rewritten in raw
form at the latest at the next compaction. This lock therefore falls. Note that an uncompressed write of
bitmap-carrying parts is a prerequisite for "storey 1" of §3.7 too — lazily reading a compressed record
would pay for unpacking on every touch.

**d) Checksums.** CRC32C is on by default (`StorageOptions.java:161`) and today's read path verifies every
record read. An mmap path has only two options: either reimplement the verification over the mapping — which
means touching every byte of the record and thereby negating the point of lazy paging —, or deliberately
drop it for these records. Both are legitimate, but it is a decision somebody has to make out loud, not a
detail that surfaces in a code review.

### 3.4 What the library can and cannot do

**Our fork does not have the buffer classes.** Reading from a ByteBuffer is provided upstream by a separate
package `org.roaringbitmap.buffer` — a parallel hierarchy of classes (`ImmutableRoaringBitmap` and
"Mappeable" containers) that does not hold the data in arrays on the heap but reads it through views into a
buffer. The fork `evita_roaring_bitmap` **does not vendor** this package and its sync ledger
(`UPSTREAM_SYNC.md`) carries it in the category "never concerns us — skip without analysis". The only thing
the fork can do from a ByteBuffer is `RoaringArray.deserialize(ByteBuffer)` — and that **copies all the
data from the buffer into arrays on the heap** (`RoaringArray.java:675-753`). That is not zero-copy, that is
a slower version of today's state.

**A "frozen" format does not exist in Java.** In the discussion a mention of RoaringBitmap's frozen format
came up — a special aligned layout that can be mapped and used immediately without parsing. Verified:
`serializeFrozen`/`FrozenView` are concepts of **CRoaring, i.e. the C library**; they are not in the whole
Java repo (searched case-insensitively across all files). The only mmap path in Java is the portable format
read through the buffer package. (The local class `FrozenRoaringBitmap` in the checkout is a prototype of
the novoj fork for sharing containers on the heap — it is unrelated to mmap.)

**What porting the buffer package would cost.** 24 files, ~17.6 thousand lines; the read-only subset (the
immutable side) ~14 thousand. Good news from the diff: the fork differs from upstream at the level of the
`Container` contract in exactly one abstract method (`toMappeableContainer` — the bridge between the
hierarchies the fork surgically cut off when vendoring), so restoring the bridge is bounded work. The bad
news: it is a permanent commitment — it doubles the surface every future upstream sync has to go through and
decide on.

**The tax for the buffer classes is paid even before mapping.** The upstream containers have ~132 fast
branches guarded by the test "is this buffer backed by an ordinary Java array?". The point: the views
`asCharBuffer()`/`asLongBuffer()`, through which the buffer containers read, **never** have a backing array
— whether the buffer is on the heap or mapped (verified by running it on this machine's JDK). All ~132 fast
paths are therefore unreachable for any ByteBuffer-backed bitmap and reading happens element by element. On
top of that, every touch of a container allocates three small helper objects (a duplicate of the buffer for
thread safety, a view, a container wrapper). Upstream says it in prose too: the performance of the classes
from the main package "is generally better, because the overhead of ByteBuffers falls away" (README:467-469)
— and its own benchmark of a mapped OR has the word "Slow" in its name. Numbers for our workload do not
exist; were it to be measured, three variants are needed (a heap `RoaringBitmap`, an
`ImmutableRoaringBitmap` over a heap buffer, an `ImmutableRoaringBitmap` over a mapping), because the first
difference is the package's tax and only the second is the I/O tax.

**And above all: every set operation returns the heap anyway.** `and`, `or`, `xor` and the whole
`BufferFastAggregation` over immutable bitmaps all return a `MutableRoaringBitmap` — an ordinary heap object
(README:457-459). Mmap therefore saves **resident** bitmaps (those permanently sitting in the index today),
but not **query intermediate results**. Should it turn out the heap suffers rather from intermediate results
than from residence, mmap will not help at all — hence the measurement in §3.8.

**The impact on MVCC.** A mapped bitmap is by principle read-only — it cannot take part in the in-place
copy-on-write mutations `TransactionalBitmap` rests on. The model "an immutable mapped base + a heap diff on
top" is conceivable (committed bitmaps really are immutable between commits), but the commit merge produces
new bitmaps that would have to be written to disk and remapped — that is already an LSM-tree-like
architecture and a separate project, not an optimization.

### 3.5 What the other engines do — and what to take from it

**Meilisearch — the strongest datapoint, and it is negative.** Its whole index lives in LMDB, a database
built on mmap: every byte Meilisearch reads **already is** in mapped memory, zero-copy would be free if it
were possible. And yet both its bitmap codecs **deserialize into their own memory** on every read
(`RoaringBitmapCodec`, `CboRoaringBitmapCodec`, `cbo_roaring_bitmap_codec.rs:160-166`). It permitted itself
zero-copy only for two specific operations somebody hand-rewrote against the serialized form: intersecting a
finished bitmap with a serialized stream and computing the cardinality from the header without building a
bitmap. The transferable lesson: a general zero-copy Roaring bitmap is not a solved problem even where the
mapping is free; at most it is *a handful of hot operations rewritten over bytes*, everything else fully
deserializes.

**Lucene — off-heap only where it is pointed out.** The structures Lucene really consumes directly from a
mapping are the term dictionary (an FST/trie walked by reading bytes at offsets, `FieldReader.java:80-87`,
`TrieReader.java:74-78`) and doc values — i.e. structures whose access is "jump to an offset, read a few
bytes, jump on". **It decodes postings into arrays on the heap** — the mapping there merely speeds up its
decoding, it does not replace it. That division is instructive for us: a bitmap used for set algebra
resembles postings, not the dictionary. And a second lesson is a platform one: Lucene fought for years with
ByteBuffer mappings not being safely closable (a JVM crash threatened on access after release), and solved
it definitively only by moving to `MemorySegment`/`Arena` from Java 21+ — it has since removed the
ByteBuffer path from the code entirely.

**Elasticsearch — documentation of when mmap hurts.** Mmap is a per-file policy for it: the default
`hybridfs` storage sends only selected file extensions to mmap and **excludes** large, randomly accessed
files (stored fields, term vectors) from it on the grounds that mmap there "leads to page cache thrashing"
(`FsDirectoryFactory.java:257-260`) — every touched page evicts one that is really hot. An operational
threshold on top: ES refuses to start until `vm.max_map_count ≥ 262144`, which is a sysctl at node/container
level. And the number of mappings is a first-class design constraint — Vespa pools small allocations into
1 MB pre-mapped regions because of it. From that follows the shape our design would have to have: **a file
(or a large region) is mapped, never an individual bitmap**.

**Typesense — a counter-precedent.** It holds its index purely in RAM and does not use mmap for index
structures at all; it addresses the footprint through the structures' compactness. A reminder that
"everything on the heap, but frugally" is a legitimate answer too.

### 3.6 The platform and operations

A summary of what each Java can do:

| mechanism                              | Java 17 (today) | Java 21 (approved) | Java 22+        |
|----------------------------------------|-----------------|--------------------|-----------------|
| `MappedByteBuffer` (`FileChannel.map`) | yes             | yes                | yes             |
| FFM `MemorySegment`/`Arena`            | incubator       | preview            | final           |
| deterministic release of a mapping     | no              | no                 | `Arena.close()` |

The key row is the last one. `MappedByteBuffer` cannot do "unmap now": a mapping disappears when the garbage
collector collects the buffer and its cleaner runs — which may be in a millisecond or in an hour. Why that
matters: compaction in evitaDB writes a new file and a purge then deletes the old one. Protecting deletion
is today governed by `ObsoleteFileMaintainer` by catalog versions and directory holds — it knows nothing
about mapped regions and has no way of finding out. The contract of the read handles is "wait a bounded
time, then close forcibly" (`OffsetIndex.clearReadOnlyOpenedHandles`, `:1304-1337`) — survivable for a file
handle, not for a mapping a query is currently reading through. On Linux deleting a file with a live mapping
works (the inode survives until the mapping ends, it merely holds disk space), on Windows deletion **fails**
and the purge quietly stops cleaning up. The emergency escape `sun.misc.Unsafe.invokeCleaner` would want an
`--add-opens` that is not in the build today. Practically: a safe mapping lifecycle = Java 22+, i.e. beyond
the approved (and so far unlanded) move to 21. The codebase uses mmap nowhere today; the only off-heap
precedent is `OffHeapMemoryManager` with direct buffers for transactional write buffers.

**Memory accounting in Kubernetes.** Here it is necessary to say out loud what is actually being bought. A
saving of Xmx does not turn into "saved memory" — mapped pages count towards the container's limit too (they
are in RSS) and unlike direct buffers there is **no cap** of the `MaxDirectMemorySize` kind for them. The
difference is qualitative: the heap is hard-bounded and under pressure the process dies (an OOMKill),
whereas clean mapped pages are **reclaimable** — the kernel quietly evicts them under pressure and it is
paid for by the latency of the next touch, not by death. Given our OOMKill profile (absolute non-heap
overhead is what kills us) it is probably an advantageous trade, but it is a *trade of a hard limit for a
soft one*, not a saving — and it has to be modelled as such. A side cost: off-heap memory disappears from
the JVM metrics; after a similar move OpenSearch had to build its own native memory trackers to see it at
all and be able to react to it. Observability would be part of the work for us, not an addendum.

### 3.7 The realistic path: two storeys

**Storey 1 — lazy loading at leaf page level (technique 1, available right away).** The work on granular
paging gave the large index parts something valuable: **addressability**. A leaf page is a separately keyed
storage part whose id can be computed from its identity (`AbstractLeafPagePart.computeUniquePartId`, `:83`)
and which has its own `FileLocation`. What does not exist today is lazy reading — on opening, the catalog
loads **all** the index parts onto the heap (`Catalog.java:455-524`; `AttributeIndexLoader` faithfully
iterates every page sequence, `:373-377` and on). Storey 1 therefore means: leave selected populations of
bitmaps (postings, bucket leaves) on disk after startup and materialize a page only on first touch, with a
cache and eviction. The prerequisite is an uncompressed write of those parts (§3.3c), otherwise unpacking is
paid on every touch. What is bought: the resident heap drops by everything untouched, and the catalog's
startup speeds up. What is paid: deserialization on first touch, the young generation of the GC gets more
work, and new infrastructure is added — an eviction policy, invalidation by catalog versions, an interaction
with the prefetch path. No port of the library, no Java 22. It is exactly what Lucene and Meilisearch do for
postings.

**Storey 2 — real zero-copy through a sidecar (technique 2, conditional).** Bitmaps in their own file: the
portable format, an explicit directory of offsets and lengths, no record envelope, no compression, no
fragmentation — `RoaringArray.serialize` can be used unchanged, the format work is small precisely because
we already know how to produce the right bytes. To that a vendored immutable buffer subset (~14k lines,
§3.4) and a mapping lifecycle through FFM `Arena`, i.e. Java 22+. Start it only when the measurement of
storey 1 shows deserialization on touch is unbearable — and with an awareness that set operations return
heap results anyway.

The order is no accident: storey 1 is measurable a generation earlier, requires no irreversible decision,
and its results (how much heap resident bitmaps hold vs. what touch deserialization costs) are precisely the
input that will decide about storey 2.

### 3.8 What to measure before anything is decided

**1. Who actually holds the heap — a few large bitmaps, or millions of small ones?** This is for mmap a
question of life and death, and the structural analysis suggests an unpleasant answer. The largest
populations of bitmaps are not the postings: they are the facet leaves (one bitmap per reference–group–facet
triple, in every index including all the reduced ones) and the `RangeIndex`, which holds **two bitmaps per
distinct threshold, both always allocated** (`TransactionalRangePoint.java:72-73`) — for predominantly
unique values (temporal validity) that is roughly two tiny bitmaps per record, each with one or two ids. In
such a bitmap the useful data is a few bytes and the rest is object overhead (headers, arrays, wrappers) —
and mmap **does not address that at all**, because data can be mapped, not object overhead. The remedy for
populations of small bitmaps is structural (the counterpart of the "primitive demotion" of the bucket tree:
below a threshold hold bare int values, no object), not mmap. A JOL census on a real catalog will decide
which world we have.

**2. Resident vs. transient memory.** Mmap saves only bitmaps permanently sitting in the index. If the heap
suffers rather from query intermediate results (which the buffer classes return to the heap anyway, §3.4),
it will not help. An indirect signal from the K8s profile — a Full GC appearing on idle pods — suggests
steady-state residence is a real problem, but the number is missing.

**3. For storey 1: the cost of touch deserialization.** A microbenchmark of reading one leaf page from disk
against today's reading from the heap; the seam for reading a raw payload without Kryo already exists
(`StorageRecord.readRawInto`, `:372-406`).

---

## 4. The impact on the prototypes

- **P1 (`p1-index-core.md`):** a reference-based stem tree is a third variant for the handover P→5 ("stem
  only vs. stem and surface form") and K3 should measure it beside both the original ones — the dictionary's
  memory as well as the distribution of K (§2.6). Question Q1 (extending the vendored roaring fork) gets a
  second potential pressure: the buffer package for storey 2 means ~14–17k lines (§3.4). It is a further
  argument for deciding Q1 only per the measurements' results and not planning storey 2 into F1 at all.
- **P2 (`p2-transactional-maintenance.md`):** a reference-based stem tree fundamentally changes the
  maintenance profile — an ordinary document mutation does not touch the stem tree at all, work arises only
  when a term comes into being and when it disappears (§2.5a). That is an input into the choice of
  structures in §5–§6 of plan P2.
- **Outside fulltext:** storey 1 (lazy reading of index parts) is not a fulltext thing — it is a general
  optimization of the serving layer for all bitmap populations. If it is to be pursued, it deserves its own
  issue outside #258, with the measurements of §3.8 as its entry gate.

## 5. Open questions

1. **The form of the references in the stem tree.** After the analysis in §2.7 the favourite is suffix
   encoding against the stem (single-digit bytes per form, no objects, stability for free); a numeric term
   id would want a stable surrogate id plus an id→term map duplicating the strings a second time. What
   remains is to verify it by measurement in K3 and to think through the behaviour for languages whose
   stemmer is not suffix-based (with dictionary lemmatizers a form can be arbitrarily far from the lemma —
   the entry then stores the full form; it is a degradation of cost, not of correctness). The trigram
   substring index (`p8-trigram-substring-index.md`, §33) is the counter-case that fixes the
   discriminator: with single-digit references per stem, suffix-encoded key lists win; with up to
   hundreds of thousands of values per trigram, only a dense numeric id space that roaring can compress
   is viable — so a stable surrogate id is rejected here and required there, and the two decisions are
   consistent, not contradictory.
2. **Persisting the stem tree.** Simple: only lists of keys/ids are stored, the stem tree never owns any
   bitmaps, so no duplication arises on disk and there is nothing to collapse after loading (unlike the
   price indexes, §2.2).
3. **The eviction policy of storey 1** and its interplay with `ObsoleteFileMaintainer` and the prefetch path
   — to be opened only with its own issue outside #258.
4. **A structural remedy for populations of small bitmaps** (`RangeIndex`, the facet leaves in reduced
   indexes) — a candidate for a separate optimization line; mmap is a blind alley for them (§3.8).
