# Under the hood: data structures, memory, and why it is fast

Part two of the primer. It follows on from `lucene-primer.md` (basic concepts, analysis, segments). It
describes the current state; where the approach reversed over the last few versions, a note explains
why.

The source is the same checkout `/www/oss/lucene` in the state `11.0.0-SNAPSHOT`, index format
`lucene104`. Version differences are **substantial** this time — a large part of what is described
below changed between 9.x and 10.x, i.e. exactly across the boundary plan P5 stands on.

Published version: https://claude.ai/code/artifact/d7d46ab2-a5e3-4acd-8ce1-cc7a99ed7cf2

---

## 1. The frame: what reversed over the last decade

Most of what is written about Lucene on the internet describes a machine built on a different
assumption from today's. That shift is one sentence and it is worth having in your head before you
delve into the details:

**Lucene stopped keeping anything on the heap and moved it into memory-mapped files, where the
operating system kernel takes care of buffering.** The index is still held in RAM — just not in the RAM
the JVM manages.

The change looks like an implementation detail, but it changes everything else. When a structure lies
on the heap, you pay for it in GC pauses, you have to be able to size and bound it, and its size hard-
caps the size of an index on one node. When it lies in mmap, the heap knows nothing about it, the
kernel keeps the hot pages itself and evicts the cold ones, and the index can be many times larger than
the available memory without anything crashing — it merely slows down.

The second shift is in how a query is evaluated. It used to compute the score of every document that
passed the filter and then pick the top k from them. Today the machine knows in advance, for every
block of documents, what the highest score that can possibly come out of it is, and if that is not
enough to break into the current top k, it skips the whole block without looking at the documents.
These are called **impacts** and they are the main answer to the question of why it is so fast.

---

## 2. Posting lists: how a list of numbers is encoded

A posting list is a sorted list of document numbers, optionally with frequencies. If it were stored as
an array of `int`, it would take four bytes per document and be absurdly large. Today's encoding has
three layers.

**Delta encoding.** Document numbers are not stored, the differences between neighbours are. Because
the list is sorted, all the differences are positive and typically small. A term present in every tenth
document has differences around ten — which fits into four bits instead of thirty-two.

**Bit packing in blocks (FOR).** Differences are taken in groups of **256** and the widest of them is
found for the whole block. That determines how many bits each value in the block gets — when the widest
difference is 9, the whole block is stored at nine bits per value. In English this is called *frame of
reference*. Blocks in which all values carry the same number have yet another shortcut.

The choice of 256 is expressly a compromise and the format's javadoc names it: smaller blocks mean less
spread of widths, hence a smaller index; larger blocks mean more efficient batch reads and better
acceleration. The value has to be a multiple of 64 and is also the *skip interval* — the smallest unit
`advance()` can jump across.

**Patching (PFOR).** Pure FOR has a weakness: a single outlier in a block stretches the width for all
the others. That is why, in frequency blocks, a few of the largest values are stored aside as "patches"
and the rest of the block is packed to a narrower width. Lucene does this asymmetrically — it patches
frequency blocks, but not blocks of document-number differences, because there it does not pay off.

Whatever does not fill a whole block, i.e. the last few documents, is appended as ordinary
variable-length `VInt`s. A term with 259 documents therefore gets one packed block of 256 values and
three values in a `VInt` tail.

And then there is one shortcut that shows how far the optimization for the long tail of the dictionary
goes: when a term occurs in a **single** document — and such terms are the overwhelming majority in a
real index — nothing at all is written into `.doc` and the number of that one document is stored
directly in the term dictionary.

> **How it used to be.** In Lucene 9.x the block was **128** values, not 256; it doubled in the tens
> line. Before packed blocks were introduced, posting lists were stored purely as `VInt` value by
> value. That is compact, but decoding is inherently serial — you cannot find where the tenth value
> begins without reading the nine before it. A packed block of fixed width, by contrast, is decoded in
> whole words and a modern compiler turns vector instructions loose on it. That trade of "a slightly
> larger file for an order-of-magnitude faster decoding" is a motif you will see again and again
> throughout the format.

---

## 3. The term dictionary: blocks, prefixes and a trie off the heap

The dictionary has to be able to do two things: find a term on an exact match, and walk terms in sorted
order from some point (prefix and range queries need that). The solution is called a *block tree* and
splits into two files.

In `.tim` lie the terms themselves, grouped into blocks by common prefix. A block holds 25 to 48
entries by default and every entry is either a term or a reference to a sub-block — so a tree arises
from it that branches deeply only where there are many terms. Each term carries its statistics and
pointers into `.doc`, `.pos` and `.pay`.

Whole terms are not kept inside a block — the common prefix is given by the position in the block, so
only suffixes are stored, and even those are compressed further. Lucene picks from three options: leave
them as they are, LZ4, or a special encoding for the case where the suffixes are all lowercase ASCII
letters. And when it comes out at two bytes of suffix per term or fewer, nothing is compressed at all —
on such short data it would not pay off.

In `.tip` is the index over all of it: a prefix trie which, for the sought term, says which block in
`.tim` to jump into.

And here is the interesting thing. That trie is **not loaded onto the heap**. In the reader you can see
that it is read as a slice of the file:

```java
return new TrieReader(indexIn.slice("trie index", indexStart, indexEnd - indexStart), rootFP);
```

`indexIn` is an `IndexInput`, i.e. a mapped file. The trie is walked directly over the bytes in mmap;
nothing of it is on the heap but a pointer and a few numbers.

> **How it used to be.** Up to version 9.x the index over the dictionary was a **finite state
> transducer (FST)** and it was **on the heap**. An FST is an elegant structure — a minimized automaton
> that shares not only prefixes but suffixes as well, so it is remarkably small. Except that "small" is
> relative: for an index with many millions of unique terms it was hundreds of megabytes on the heap,
> per segment, and it was the most frequent cause of a large index simply refusing to open. Replacing
> it with a plain prefix trie read from mmap sacrifices some compression in exchange for the structure
> not having to be on the heap at all.

---

## 4. Skipping: from skip lists to impacts

The core of the answer to "why is it fast". Two different things meet here that are easily conflated.

### The first level: skip documents that cannot match

When you compute the intersection of two posting lists and one of them is sparse, you do not want to
walk the second one document by document. You want to say "move to document number at least 40,000" —
that is the `advance()` operation. For it to be cheap, navigation markers are scattered through the
posting list saying at which document each block begins and where in the file it lies.

Today these markers are **interleaved with the data** at two levels: level 0 between every two packed
blocks, level 1 between every thirty-two. So the search first goes coarsely by groups of thirty-two
blocks and then finely inside the group.

### The second level: skip documents that *can* match, but are not worth it

An intersection tells you which documents match — but the user wants the ten best, not all of them. If
the score of every matching document had to be computed, a query on a common word would always be
expensive.

The solution is called **impacts**. On write, Lucene remembers for every block which pairs *(frequency,
norm)* occurred in it — and not all of them, only those that can yield the highest score. That
structure is called `CompetitiveImpactAccumulator` and it is an elegant piece of work: for ordinary
norms that fit into a single byte it keeps only an array of 256 maximum frequencies, so accumulation
costs one comparison.

Why the pair of frequency and norm specifically? Because BM25 computes the score from nothing other
than these two numbers (plus IDF, which is constant for the whole term). So when you know the best
possible pair in a block, you know an **upper bound of the whole block's score** without opening a
single document.

Impacts are written into those same skip data. At query time the logic in `ImpactsDISI` is then
straightforward: the result collector continuously reports what score the currently tenth-best hit has,
and that is the *minimum competitive score*. As soon as it rises, the iterator recomputes the upper
bound of the current block, and when it is lower, it asks how far it can jump — and jumps.

```java
if (maxScore >= minCompetitiveScore) {
  return target;                                // the block is competitive, walk it
}
final int skipUpTo = maxScoreCache.getSkipUpTo(minCompetitiveScore);
...                                             // otherwise skip straight past it
```

The effect is that a query speeds up in the course of its own execution. The first few hundred
documents are processed normally, which raises the bar, and from then on an ever larger part of the
posting list is skipped in whole blocks.

Above all of that sits `MaxScoreBulkScorer`, which does the same across several terms in a disjunction:
it sorts them by maximum possible score and splits them into *essential* and *non-essential*. The
non-essential ones are those whose maximum score is so low that on their own they cannot get a document
into the top k — those are then not iterated at all and merely queried about documents the essential
ones already found. It is done in windows of 4096 documents and the split is recomputed continuously as
the bar rises.

### The third level: doing it on several cores at once

The last piece of the puzzle is unrelated to the format and concerns the way evaluation happens.
Segments are mutually independent and immutable — which means they can be searched **concurrently
within a single query**. Ten segments can be processed by ten threads and the results merged at the
end.

That is precisely why today's API around collecting results is called `CollectorManager`: it is not one
collector but a factory that produces a collector per thread, plus an operation that combines their
partial results. It is ordinary map-reduce, only wrapped in an interface. The unpleasant consequence is
that you can no longer write your own collector as a single object with internal state — you have to be
able to split it and put it back together.

This moreover composes nicely with impacts: every thread keeps its own bar, but as soon as one of them
finds good results, the others soon benefit too, because the bar is shared continuously.

> **How it used to be.** Result collection was single-threaded: you handed over a `Collector` and it
> saw the segments one after another. Concurrency could be used only between queries, not within one.
> That variant is deprecated today in favour of `CollectorManager` precisely because it prevented cores
> from being used to speed up a single query — which is what matters in interactive search.
>
> In 9.x the skip data was in a **separate structure** at the end of the posting list, with its own
> writer and reader. Interleaving data and navigation in 10.x is a change for the sake of locality: the
> skip information now lies physically next to the block it concerns, so reading it does not pull
> another page from a different end of the file. And above all: even older Lucene had *only* skip
> lists. Those can speed up `advance()`, i.e. the intersection — but they know nothing about scores, so
> a top-k query had to walk and score every matching document. Impacts (and the family of algorithms
> above them: WAND / block-max WAND / MAXSCORE) are the reason why a query on a frequent word costs a
> fraction of what it used to.

---

## 5. What is on the heap and what is in mmap

A direct answer. The analysis is deliberately short on the left-hand side.

| Structure | Where it lives | Note |
| --- | --- | --- |
| `SegmentInfos`, `FieldInfos` | **heap** | Commit metadata and the field description. Kilobytes per segment. |
| doc values / norms "entries" | **heap** | Headers from `.dvm`/`.nvm`: where what begins. Not the data itself. |
| `liveDocs` | **heap** | The bitmap of live documents, one bit per document. |
| query cache | **heap** | If enabled — from Lucene 11 it no longer is, see below. |
| the query's working memory | **heap** | Bitsets, priority queues, decoding buffers. Per query and thread. |
| term dictionary (`.tim`, `.tip`) | mmap | Including the trie index — see chapter 3. |
| posting lists (`.doc`, `.pos`, `.pay`) | mmap | They always were. |
| doc values, norms (data) | mmap | Read through `RandomAccessInput` over the mapped file. |
| stored fields, term vectors | mmap | Only the block concerned is decompressed. |
| vectors and the HNSW graph | mmap | On the heap only the working arrays during a search. |

The left column grows with the number of segments and fields, not with the size of the data. That is
why heap size for Lucene is not derived from index size.

### How that right-hand side is actually read

The default directory is `MMapDirectory`. A segment's files are mapped into the process's address space
and reading is then an ordinary memory access — when the page is in RAM it is an instruction; when it
is not, the kernel fetches it from disk and the operation blocks for that time. No `read()` syscalls,
no copying into a buffer on the heap.

Today this rests on `java.lang.foreign.MemorySegment` from Java 21, which besides performance also
solves the problem that a mapped file can be safely unmapped after closing.

And then there is a layer that is not much talked about: Lucene **advises** the kernel how it will
behave towards each file, through the `madvise()` syscall. The `ReadAdvice` enum has three values and
each means something different for the page cache — `NORMAL` for ordinary use, where the kernel should
keep the hottest pages, `RANDOM` for a structure that is jumped into (typically the dictionary and doc
values), and `SEQUENTIAL` for contiguous reading, where the kernel may read ahead aggressively and
release pages again right behind itself. The last is exactly what you want during segment merging, so
that a merge does not evict the whole hot index from the page cache.

For this to work Lucene needs access to native code; in a modularized application that means adding
`--enable-native-access=org.apache.lucene.core`, otherwise a warning is merely logged and the advice
silently does not happen.

### The point of the whole thing

The question was how search can be fast when the index is not held in memory. The answer is that it
**is** held — just in different memory. The operating system's page cache is that buffer and for this
purpose it is better than anything that could be written in Java: it is shared between processes, it
survives an application restart, the kernel sees into it better than you do, and it costs not one byte
of heap, hence not one millisecond of GC.

The operational consequence is, however, the opposite of what most people do instinctively with a JVM
application: **you want the heap small, not large.** Every gigabyte given to the heap is a gigabyte the
kernel does not have for the page cache. A machine with 64 GB of RAM and an 8 GB heap serves a large
index markedly better than the same machine with a 48 GB heap.

> **How it used to be.** Mapping ran on `ByteBuffer`, which brought two pains: addressing was possible
> only in two-gigabyte chunks, so large files had to be sliced and reads across the boundary handled
> separately, and a buffer officially could not be unmapped — Lucene worked around it by reaching into
> a non-public API, which stopped being sustainable with the arrival of modules. Earlier still, mapping
> was not used for a large part of the data at all; instead there was ordinary reading into buffers on
> the heap (`NIOFSDirectory`), and Lucene built its own cache above it — which ended with the
> application and the kernel doing the same thing twice and competing for the same RAM.
>
> A change for version 11 is related: **the query cache is now off**. `LRUQueryCache` kept precomputed
> bitsets for repeated filters on the heap, which made sense back when scoring was expensive — except
> that with the impacts of chapter 4, evaluation is often cheaper than managing the cache, and the
> cache moreover ate precisely the heap the page cache is missing.

---

## 6. Doc values and sparse data

Doc values are a column: for document number *n* I want the value, fast. If every document had a value,
it would simply be a mapped array. The problem is that not every document has one as a rule — and a
sparse column cannot be indexed by plain multiplication, because the *n*-th value does not belong to
the *n*-th document.

`IndexedDISI` solves this. The space is cut into stretches of **65,536** documents and every stretch
gets one of three encodings according to its density: `ALL` when all of them are present (nothing is
stored), `DENSE` at 4096 and above (a bitmap), and `SPARSE` otherwise (the lower sixteen bits of the
document numbers). It is directly inspired by roaring bitmaps.

On its own, however, this would mean you have to iterate up to the *n*-th document. That is why there
are two lookup tables above it: a skip table which, for a document number, says the offset of its
stretch straight away, and, above the dense stretches, a "rank" structure per 512 bits so that the
position within a stretch can be computed without walking the whole bitmap. Both lie in mmap, not on the
heap.

For monotonically increasing sequences — typically offsets and pointers — Lucene has
`DirectMonotonicWriter` on top of that. It does not store the values but the deviations from a fitted
line; when the sequence grows roughly evenly, those deviations are tiny.

The latest addition to this layer is the **skip index over doc values** (`DocValuesSkipper`). It keeps
the minimum and maximum value per block, so a range query or a sort can skip a whole block of documents
whose range does not overlap the sought one. It is the same idea as impacts, only applied to numeric
values instead of to scores — and just as with impacts, you enable it on the field at indexing time,
you do not get it for free.

It is freshly dated: the abstraction appeared in Lucene 10.0 and the data initially lay inside the main
doc values file; it moved into its own `.dvs` file only in 10.5. So when you read about `.dvs`
somewhere, a very recent state is being talked about.

> **How it used to be.** Doc values did not exist at all for a long time and sorting or faceting was
> done via **`FieldCache`**: on the first query the inverted index was inverted back into a column, that
> column was built entirely **on the heap** and stayed there. It worked and it was fast — until your
> index grew, whereupon the first query after opening a segment took minutes and the heap ended up full
> of arrays nobody could size in advance. Doc values build that column right at indexing time and store
> it on disk. The price is that you have to decide *in advance* which fields want it — and when you
> remember afterwards, it means reindexing.

---

## 7. Stored fields: block compression

Stored fields are the part you return to the user, and they have a different characteristic from
everything else: they are read rarely — only for the ten to fifty documents that are actually displayed
— but whole and all at once.

That is why they are compressed in blocks. Documents are assembled into a buffer in memory on write,
and when it grows past roughly 80 kB, the whole thing is compressed and written. In the default
`BEST_SPEED` mode that is LZ4 in eight-kilobyte blocks with a shared dictionary; the `BEST_COMPRESSION`
mode trades speed for ratio and uses DEFLATE in 48 kB blocks.

A detail worth noticing, because it shows the way of thinking: a large document is not compressed as
one piece but cut into eight-kilobyte blocks. The reason is that when you request only the first field
of a ten-megabyte document, you do not want to decompress ten megabytes — the first eight to sixteen
kilobytes suffice. For the same reason the original lengths are written into the block's metadata, so
that the decompressor knows when it may stop.

Finding the right block is what `.fdx` does, and it is minimalist: two monotonic arrays — the numbers of
the first documents of the individual blocks and their offsets on disk. The first is searched binarily,
the position is read from the second.

The practical consequence: **fetching stored fields is as a rule the most expensive part of a query**,
because it is the only thing doing random reads and decompression. That is why it is always done at the
very end and only for the page of results that is actually displayed — never for all the documents
found.

---

## 8. Vectors: HNSW

The task is different from everything before: you have documents represented by vectors of hundreds to
thousands of dimensions and you look for the ones nearest to a query vector. The inverted index is
useless here — there is no "term" to look up by, and in high dimension the classical tree structures
fail too.

The exact solution is to compare the query with all the vectors, which is linear and unusable with
millions of documents. That is why the search is *approximate* and the measure of quality is **recall**
— what proportion of the true nearest neighbours the method found. The whole discipline is then about
the trade-off between recall, latency and memory.

### What HNSW is

**Hierarchical Navigable Small World.** A graph in which the nodes are vectors and the edges lead
between nearby vectors. Searching is a walk: you start at an entry node, compute the distance to its
neighbours, move to the nearest one, and repeat while you keep improving. Because the edges are laid so
that the graph has the "small world" property — that is, a few long shortcuts alongside many short
connections — you get from anywhere to anywhere in a logarithmic number of steps.

The word *hierarchical* then means that there are several such graphs stacked on top of each other. The
top floor has few nodes and very long edges, each lower one is denser, the bottom contains all of them.
The search goes from the top down: up top you hop coarsely into the right region, at the bottom you find
it precisely. It is in principle the same idea as a skip list, only in vector space.

Lucene estimates for itself how many nodes a search will visit, and that estimate is a one-liner and
eloquent: `log(graph size) × k`.

### The two numbers it rests on

- **`maxConn = 16`** — *M* in the original paper. How many neighbours each node gets at most. A higher
  value means better recall and faster convergence, but a bigger graph on disk and a more expensive
  build.
- **`beamWidth = 100`** — *efConstruction* in the paper. How many candidates are kept in the queue when
  inserting a new node. It affects only the quality of the build and its cost, not the size of the
  result.

Both are set on the format at indexing time and are **not changed retroactively** — they are baked into
the graph's structure.

### How it lies on disk

The `.vex` file holds the graph, and it is simply a list of neighbours: for every floor, for every node,
the number of neighbours and their ordinal numbers, delta-encoded. At the end of the file are the
offsets of where each node begins, stored with that monotonic compression from chapter 6. The `.vem`
file is metadata — the dimension, the similarity function, the number of floors and the lists of nodes
on the individual floors.

The vectors themselves are elsewhere, in `.vec`, and that is deliberate: the graph format and the vector
storage format are separate and compose. Thanks to that, **quantized** vectors can be slipped under the
same graph — instead of four bytes per dimension, one or fewer. Precision drops, but comparisons are
faster and, above all, several times more data fits into the page cache, which is the main gain in
practice. In this tree the formats for that are in `.veq` and `.vemq`.

Both the graph and the vectors are read from mmap. That their heap consumption is no longer counted is
visible even in the interface: `MIGRATE.md` records that the memory counter was removed from
`KnnVectorsReader` on the grounds that such an object takes up little on the heap. During a search only
working structures are allocated on the heap — the candidate queue and a bitmap of visited nodes, and
even for that Lucene chooses between a sparse and a dense variant depending on how many nodes it expects
to visit.

### Trap one: filtered search

The most practical thing in the whole chapter and fundamental for an e-shop. You want "vector-similar
products, but only from category X and in stock". Except that the graph knows nothing about your filter
— it leads you to the nearest vectors regardless of whether they pass. The naive approach, i.e. search
normally and discard the non-conforming, falls apart with a strict filter: the walk spends most of its
time in a region from which nothing passes, and returns few results or none.

Lucene has `FilteredHnswGraphSearcher` for this, built on the ACORN-1 algorithm and modified further.
The idea is that with a strict filter it looks at the neighbours of neighbours too — stretching the walk
through non-conforming nodes instead of stopping at them. How much of such stretching pays off is
computed from **the proportion of documents that pass the filter**: the stricter the filter, the wider
the outlook. And when the filter is really narrow, an exact search over that handful of candidates pays
off directly and the graph is not used at all.

Practically it follows that filtered vector search is a different task from unfiltered and behaves
differently. When you measure recall, you have to measure it with real filters.

### Trap two: the graph is per-segment

**Every segment has its own graph.** A query therefore searches the graph in every segment separately
and merges the results together. Ten segments means ten walks.

With terms the number of segments is an annoyance. With vectors it is considerably worse, because every
walk has its constant overhead and recall gets diluted — every partial graph returns you its *k*
nearest, but the globally nearest may all be in one of them.

And merging is expensive: a graph cannot be "poured together", because the neighbourhood arose relative
to a different set of points. In essence it has to be built again. That is why
`IncrementalHnswGraphMerger` exists, trying to use the largest existing graph as a base and add the rest
into it instead of building from scratch, and that is why for vectors it is far more important than
usual to make sure segments really do get merged.

> **How it is evolving.** Vectors are young in Lucene and move fast, so "used to be" here means two
> years ago. Quantization was added recently and in this tree it already has its own formats. Filtered
> search via ACORN-1 replaced an older, dumber approach with plain discarding.
>
> The last change `MIGRATE.md` describes for 10.5 is instructive beyond vectors too: for a query on
> similarity above a threshold, a parameter for how deep to walk the graph used to have to be given
> manually. It was hard to hit and the search got stuck in a local maximum. Now the threshold is lowered
> *adaptively* according to the scores of nodes that were walked through but not collected. A manual
> knob nobody knew how to set was replaced by feedback from the course of the search — and that is a
> pattern you will meet in several places in this code.

---

## 9. What follows operationally

- **Small heap, free RAM.** The heap covers metadata, bitsets and the working memory of queries; the
  kernel reads the index into the page cache. Adding memory to the heap under a search workload
  typically *hurts*.
- **A cold start is a real thing.** The first queries after startup go to disk, because the page cache
  is empty. `MMapDirectory` can pre-warm files on open, at the cost of a slower start.
- **Watch the number of segments, doubly so with vectors.** With terms the query cost grows linearly
  with the number of segments; with vectors recall suffers on top of that.
- **Merging competes for the page cache.** Hence the `SEQUENTIAL` advice from chapter 5 — a merge should
  read and forget, not evict the hot index.
- **A single query can keep several cores busy.** It does, however, require handing the searcher an
  executor and writing collectors as a `CollectorManager`. Without that, a query runs on one thread,
  however many cores you have.
- **Think doc values through in advance.** There is no computing them at runtime; a missing column means
  reindexing.
- **Fetch stored fields last and only for the displayed page.**
- **Do not pass JVM flags out of habit.** Advice of the "give Lucene a big heap" kind comes from the era
  of `FieldCache` and the on-heap FST. Today the opposite holds.

---

### Version notes

Described over `/www/oss/lucene` in the state `11.0.0-SNAPSHOT`, index format `lucene104`. Against
Lucene 9.12, from which plan P5 starts, the following of the above differs: the packed posting-list
block size is **128** in 9.x instead of 256; the skip data is in a **separate structure** in 9.x, not
interleaved between blocks; the index over the term dictionary is an **on-heap FST** in 9.x, not a trie
read from mmap; the skip index over doc values does not exist in 9.x (the `DocValuesSkipper` abstraction
is from 10.0 and its own `.dvs` file even from 10.5 only); the quantized vector formats `.veq`/`.vemq`
come from 10.4 and the adaptive graph walk from 10.5.

Switching the query cache off is a change for version 11, so not even for 10.x. Impacts and their use in
top-k, by contrast, are in 9.x as well — the behaviour described above does not differ there in
principle, it merely sits on a different layout of the skip data.

The constants given in the text (256, 25–48, 65,536, 4096, 512, 80 kB, 8 kB, 48 kB, 4096, `maxConn` 16,
`beamWidth` 100) are read directly from the source files of this revision.
