# Fulltext from the ground up: how Lucene prepares an index

This text is an on-ramp, not a tenth plan. It assumes an experienced programmer who so far knows
nothing concrete about fulltext and wants to understand in one sitting what really happens inside such
a machine — from raw text to a file on disk and back to an answer to a query.

All references into the code point at the checkout in `/www/oss/lucene`, currently `11.0.0-SNAPSHOT`
(the main branch, revision `13796f80e4` of 11 August 2026), with the default index format `lucene104`,
i.e. Lucene 10.4. This matters: the prototype plan P5 starts from Lucene 9.12.x, so two major versions
lie between what you read here and what you will have on the classpath. The fundamentals — `Analyzer`,
`TokenStream`, `IndexWriter`, segments, BM25 — have not budged in that time and you can trust them
completely. A few classes you will see in this tree do not yet exist in 9.12: `CaseFoldingFilter`,
`DocValuesSkipper`, `Float16VectorValues`, `SentenceAttribute`, the package
`analysis/common/.../morph`, and of course the whole generation of the `Lucene103*` and `Lucene104*`
formats. Wherever we hit such a place, it is noted at the bottom.

---

## 1. The single idea the whole thing rests on

The database you know stores a record and values inside it. A fulltext machine stores the exact
opposite: for every word it keeps a list of the documents in which that word occurred. This is called
an **inverted index**, because it inverts the natural relation — instead of "a document contains
words" we have "a word occurs in documents". Lucene describes it exactly this way itself in the
introduction to its format
(`lucene/core/src/java/org/apache/lucene/codecs/lucene104/package-info.java`, the *Inverted Indexing*
section).

That list of documents is called a **posting list** and the word it hangs under is called a **term**.
A term is not a word in the linguistic sense, it is simply a sequence of bytes; Lucene does not
understand it and does not need to. What matters is that a term is always a pair *(field name, bytes)*
— the same bytes in two different fields are two different terms, so `name:bike` and `description:bike`
are independent things with independent posting lists.

A single-word query is then a lookup of one term in the dictionary and a read of its posting list. A
query for two words joined by `AND` is the intersection of two posting lists, `OR` is their union.
Because posting lists are stored sorted by document number, those intersections and unions are done in
a single pass, without random jumps. That is the whole trick: fulltext is fast not because it is
clever, but because it converted the question "which sentence contains this" into a sequential merge of
sorted lists of integers.

From that, however, follows the first non-trivial consequence. For the intersection to make sense, the
bytes of the term from the query have to be **exactly** the same bytes that got into the index. Not
similar — identical. And because the user types "Mountain Bikes" into the search box while the product
description says "mountain bike", somebody has to convert both sides into a common form. That somebody
is called an analyzer and it is the subject of the whole second half of this text.

---

## 2. Documents and fields: there are actually six indexes in one index

Before we get to analysis, the data model needs sorting out, because it is where beginners err most
often and err expensively.

Lucene knows a **document**, which is an ordered sequence of **fields**, and a field has a name and a
value. But what happens to a field's value is not one thing — it is several independent choices that do
not exclude each other and that you make for every field separately. The best way to rearrange this in
your head is this: **Lucene is not one index, it is five or six differently shaped indexes over the
same documents, and the design decision consists in which of them each field gets.**

Those structures are:

**The inverted index (indexed).** The value is decomposed into terms and those are written into the
dictionary and the posting lists. This is the only structure in which content can be searched. How
detailed that write is is governed by the `IndexOptions` enum
(`lucene/core/src/java/org/apache/lucene/index/IndexOptions.java`) — from `DOCS`, where only the
presence of a term in a document is remembered, through `DOCS_AND_FREQS`, where how many times is
remembered as well, and `DOCS_AND_FREQS_AND_POSITIONS`, which adds word positions, up to the variant
with `OFFSETS`, which also keeps character ranges in the original text. Every level costs space and
time on write. Without positions you cannot do a phrase query; without offsets you cannot highlight
results straight from the index.

**The stored value (stored).** A verbatim copy of the text, not decomposed in any way, stored on the
side so that it can be returned to the user once the document is found. It cannot be searched at all.
That a field is indexed therefore does not mean you can read it back — almost every beginner makes this
conflation, and it manifests as an empty title on an otherwise correctly found product.

**Doc values.** A columnar structure oriented the opposite way to the inverted index: one value per
document, quickly readable by document number. It serves sorting, faceting, aggregations and
computations in the score. When you want to sort by price, the price has to be in doc values; a posting
list is of no use to you for that.

**Norms.** A special, very compressed case of doc values — one value per document and field, typically
the field's length in terms. It is used during scoring, see below. It can be switched off, and when you
do, BM25 stops taking text length into account.

**Points.** A multi-dimensional BKD tree for numeric and spatial ranges. When you ask "price between
100 and 500", that does not go through terms, it goes this way.

**Vectors (KNN).** A dense vector per document plus an HNSW graph over them, for semantic search by
embedding similarity. In this tree that is `Lucene99HnswVectorsFormat` and, above it, the quantization
variants from `lucene104`. This structure differs from the others enough that it has its own chapter in
part two (`lucene-under-the-hood.md`) — including two traps, filtered search, and the fact that the
graph is per-segment.

On top of that there are **term vectors**, an inverted index in miniature stored separately for each
document — used for highlighting and for "find similar documents".

The point: the decision "this field will be indexed only, that one stored only, and the third both plus
doc values" is schema design, and that design determines what the index can do and how much it weighs.
It cannot be easily changed afterwards — a schema change means reindexing.

---

## 3. Analysis: the road from text to terms

Now for the main thing. **Analysis** is the process that converts a field's value into a sequence of
terms. It consists of three kinds of component and one conductor, and you will find all four in
`lucene/core/src/java/org/apache/lucene/analysis/`.

**CharFilter** works even before the split into words. It is a descendant of `java.io.Reader`, so it
turns a stream of characters into a stream of characters: it strips HTML tags, replaces entities,
rewrites characters according to a map. It takes special care to remember how character positions
shifted, so that highlighting later points at the right place in the *original* text, not in the
cleaned-up one.

**Tokenizer** is the one that chops the text into pieces. Nothing more — its only responsibility is to
decide where one word ends and the next begins. The default choice is `StandardTokenizer`, which
implements text segmentation according to Unicode Annex 29, so it copes with punctuation, apostrophes,
and with the fact that Chinese does not use spaces. Simpler ones exist (`WhitespaceTokenizer`) as do
specialized ones (`PathHierarchyTokenizer` for paths, `NGramTokenizer` for splitting into overlapping
character n-grams).

**TokenFilter** receives a stream of tokens and changes it. It can rewrite tokens (lowercasing,
stripping diacritics, stemming), discard them (stopwords) or add them (synonyms). You chain as many
filters as you need and the order matters a great deal — a stopword filter running before lowercasing
lets "The" through, because its list only has "the".

**Analyzer** itself processes no text at all. It is a **factory** for that chain: it receives a field
name and builds a tokenizer and filters from it. Lucene stresses this nuance explicitly in its
documentation, because it is confusing: a `Tokenizer` is a `TokenStream`, but an `Analyzer` is not. On
the other hand, the `Analyzer` is the only one that knows which field is in play, and so it can build a
different chain for `name` than for `description`.

### A concrete example: the Czech analyzer

The quickest way to settle this is to read `CzechAnalyzer`
(`lucene/analysis/common/src/java/org/apache/lucene/analysis/cz/CzechAnalyzer.java:107`). The whole
factory is five lines:

```java
protected TokenStreamComponents createComponents(String fieldName) {
  final Tokenizer source = new StandardTokenizer();
  TokenStream result = new LowerCaseFilter(source);
  result = new StopFilter(result, stopwords);
  if (!this.stemExclusionTable.isEmpty())
    result = new SetKeywordMarkerFilter(result, stemExclusionTable);
  result = new CzechStemFilter(result);
  return new TokenStreamComponents(source, result);
}
```

It reads from the outside in: `StandardTokenizer` chops the text into words, `LowerCaseFilter` shrinks
them, `StopFilter` throws away those on the stopword list, and `CzechStemFilter` trims the endings off
the rest. In the middle sits `SetKeywordMarkerFilter`, and it is worth explaining, because it
demonstrates an elegant pattern: it marks the enumerated tokens with a flag "this is a keyword, do not
touch", and the stemmer running behind it skips tokens marked that way. It is a way of exempting
brands, codes or proper names from the chain without having to change the stemmer.

### A TokenStream is not a list of tokens

Here is the single place in the API where Lucene surprises anyone expecting ordinary Java. A
`TokenStream` is **not** a `List<Token>` nor an `Iterator<Token>`. It is a pull iterator without a
value — you call `incrementToken()`, which returns a `boolean`, and you read the current token's data
from **shared, mutable attributes** that you registered on the stream beforehand. No `Token` object
comes into being; the same attribute object is overwritten on every step.

The protocol is fixed and looks like this (the example is from the analysis package documentation):

```java
TokenStream ts = analyzer.tokenStream("myfield", new StringReader("some text"));
OffsetAttribute offsetAtt = ts.addAttribute(OffsetAttribute.class);
try {
  ts.reset();                       // mandatory, sets the stream to the beginning
  while (ts.incrementToken()) {
    System.out.println(ts.reflectAsString(true));
    System.out.println(offsetAtt.startOffset() + ".." + offsetAtt.endOffset());
  }
  ts.end();                          // finishes off the end of the stream, mainly the final offset
} finally {
  ts.close();
}
```

That design is deliberate and dates from the era called "Flexible Indexing": the point is that indexing
millions of documents should not allocate one object per token. The price is that the components have
to be reusable — the `Analyzer` recycles them by default, merely slipping them the next input through a
new `setReader()`, so all state has to be correctly cleared in `reset()`.

There are eight attributes in the base set and they live in
`lucene/core/src/java/org/apache/lucene/analysis/tokenattributes/`: `CharTermAttribute` carries the
term's text itself, `OffsetAttribute` the character range in the original input,
`PositionIncrementAttribute` and `PositionLengthAttribute` the position (more on those in a moment),
`PayloadAttribute` arbitrary bytes attached to the occurrence, `TypeAttribute` and `FlagsAttribute`
auxiliary marks for communication between filters, and `KeywordAttribute` precisely that "do not touch"
flag used by `SetKeywordMarkerFilter`.

---

## 4. Stopwords, stemming and lemmatization

**Stopwords** are very frequent words without discriminating value — "a", "the", "is", "on". The
classic advice is to throw them out, because their posting lists are enormous and the information in
them negligible. That advice is nowadays rather historical, though, and it is worth knowing why.

First, the scoring function copes with frequent words by itself: the IDF (inverse document frequency)
component gives a word that is in every second document a weight approaching zero. Second, removing
stopwords irreversibly damages phrase queries — once "to" is thrown out of the index you will never
again find "to be or not to be". And third, modern posting-list encoding is so compressed that the
space saved is not worth the loss. The compromise is `CommonGramsFilter`, which does not throw the
stopword away but glues it to its neighbour into a single term ("to_be"), so that phrases work while
that huge posting list is never read on its own.

For Czech, Lucene has a default list in the file `stopwords.txt` next to `CzechAnalyzer`, loaded via
`WordlistLoader`.

**Stemming** is the mechanical trimming of an ending so that different forms of the same word collapse
onto the same term. It is not linguistics, it is a set of rules — the Czech `CzechStemmer` has all of
four and a half kilobytes of source and essentially just successively cuts off known suffixes and
endings. The result is often not an existing word, which does not matter, because the same rule is
applied to the query as well and both sides come out the same. The stemmer family is broad: `snowball`
(generated rules for dozens of languages), `hunspell` (dictionary-based, using OpenOffice
dictionaries) and `stempel`, which is interesting in that its rules are not written but learned — it is
a trie built by a tool you feed pairs of form/base into. Lucene's module ships a trained table for
Polish only (`PolishAnalyzer`), but the training tool itself is part of the distribution, so a Czech
table is a matter of data, not of code.

**Lemmatization** is a different task: converting a form into a real dictionary headword, i.e. "wheels"
into "wheel" and "was" into "be". That requires a dictionary and morphological analysis, not rules. For
Czech it is considerably more important than for English, because a Czech form can differ from its base
a great deal ("pes"/"psa", "hrát"/"hraji"). Lucene has the `analysis/morfologik` module for this, which
is entirely dictionary-driven: by default it loads a Polish dictionary, but through the `dictionary`
attribute you slip it any `.dict` file. For Czech it is therefore more about obtaining and maintaining
a dictionary than about code.

For your context it is worth mentioning that dictionary lemmatization sits on the right side of your
own rule about the division of labour — it is a deterministic dictionary lookup, not inference, so it
belongs in the engine, not outside it.

---

## 5. Positions: why phrases work and how they break

This is the second concept that carries disproportionate weight and that almost every introductory text
omits.

Every token has a **position increment**, which is by how much the position shifts relative to the
previous token. The default value is one, so tokens simply follow one another: 1, 2, 3. But filters
deliberately manipulate this number, and all the behaviour of phrase and proximity queries is governed
by it.

When `StopFilter` throws a token away, it **must** increase the increment of the following one so that
a hole remains after the discarded word. Lucene says so explicitly: a filter that discards tokens must
raise the increment, otherwise it produces a corrupted stream. The consequence is beautifully
illustrated right in the documentation: from the sentence "blue is the sky", after removing "is" and
"the", only "blue" and "sky" are indexed, but the position of "sky" is three higher than the position
of "blue". The phrase query "blue is the sky" therefore finds the document, because the same analyzer
throws the same stopwords out of the query too and hole matches hole. The phrase query "blue sky",
however, **does not find it**, because it expects a distance of one and the index has three.

Synonyms go the opposite way: an added token gets an increment of **zero**, so it lies at the *same*
position as the original word. "red" and "magenta" at the same position means they are interchangeable
for every query.

Multi-word synonyms cannot be handled this way, though. If you want "IBM" to be a synonym for
"International Business Machines", giving "International" a zero increment is not enough — you would be
saying that "IBM" is a synonym only for "International". That is why **position length** exists: "IBM"
gets a length of three and thereby stretches across three positions in the stream, so that both
variants begin and end at the same place.

And with that the token stream becomes a **graph** — a directed acyclic graph where the edges are
tokens and the nodes positions. This is where `GraphTokenFilter` and `TokenStreamToAutomaton` in the
core come from, and this is where the recommendation to use `SynonymGraphFilter` at query time only,
not at indexing time, comes from. Position length is **not stored in the index**; the index knows only
increments. The graph therefore lives only for the duration of query processing, where it can be
expanded into alternatives, whereas at indexing time its information would be lost.

Because of this, Lucene has a set of hard rules for writing your own filters that are worth
remembering: the first increment must be greater than zero, positions must not go backwards, tokens
with the same start position must have the same start offset, tokenizers must call `clearAttributes()`
in `incrementToken()` and must override `end()` and set the final offset in it. Filters should not
change offsets (whoever needs to change offsets is in fact writing a tokenizer) and added tokens must
have a zero increment.

---

## 6. The symmetry of index and query, and how a silent zero arises from it

Because posting lists are looked up on an exact byte match, the query has to be analyzed **the same
way** the index was analyzed. Otherwise the result is empty and no error arises anywhere — zero results
is not an exception, it is a legitimate answer. This is the most frequent silent failure in fulltext
altogether: somebody changes the analyzer, forgets to reindex, and the system simply stops finding
things without anything crashing.

The default rule therefore reads: the same analyzer on both sides. Legitimate exceptions do exist,
though, precisely where alternatives are expanded — synonyms are typically expanded in the query only
(see `SynonymGraphFilter` above), as are typo correction and expansion by abbreviations. Conversely,
n-grams for a suggester are generated only at indexing time and the query asks about them whole.

It is also worth noting that some queries do not go through analysis at all — wildcard and prefix
queries go straight to the dictionary, so when you have stemmed forms in the index, `bik*` behaves
differently from what you would expect.

---

## 7. The write path: what happens on `addDocument`

Now let us put the whole chain together. The entry point is `IndexWriter`, which is the only thing that
writes into the index and which holds a lock on the directory, so only one of them may exist on one
index at a time.

When you call `addDocument`, the document is assigned to one **`DocumentsWriterPerThread`**
(`lucene/core/src/java/org/apache/lucene/index/DocumentsWriterPerThread.java:52`). That is the key to
parallel writing: every thread has its own, independent buffer in memory and nothing is shared between
them, so indexing happens without locks. That buffer is in fact a small index in itself.

Inside runs the **`IndexingChain`**, specifically `processDocument()`
(`lucene/core/src/java/org/apache/lucene/index/IndexingChain.java:581`), which walks the document's
fields one by one and decides for each what to do with it — `processField()` on line 1391 and
`invertAndStore()` on 1412. The "inversion" proper, i.e. the conversion of text into terms and their
write into the buffer, is `invertTokenStream()` on line 1905; that is where exactly the
`reset()`/`incrementToken()`/`end()` protocol from chapter 3 spins, except that Lucene calls it, not
you. Terms are stored into shared byte pools (`ByteSlicePool`), positions and frequencies into
structures you see as `FreqProxTermsWriter`.

When the buffer grows, a **flush** writes it to disk as a **segment**. When that happens is governed by
the `FlushPolicy`, or rather `FlushByRamOrCountsPolicy`; the default threshold is 16 MB of memory
(`IndexWriterConfig.DEFAULT_RAM_BUFFER_SIZE_MB`), while the limit on document count is off by default.
Nothing here is configured by the size of the resulting file — Lucene watches memory, because that is
the resource that runs out.

**A segment is immutable.** Once written, it never changes again. That is a fundamental decision from
which almost everything else follows: readers need no locks, files can be memory-mapped and caches need
not invalidate anything.

---

## 8. What lies on disk

A segment is a set of files with a common name and different extensions; every extension is one of
those structures from chapter 2. The following list is extracted from the constants in this checkout,
i.e. for format 10.4 — in 9.12 the extensions are the same, only the classes carry different version
numbers.

The term dictionary is written by `Lucene103BlockTreeTermsWriter` into `.tim` (dictionary data), `.tip`
(an index over the dictionary) and `.tmd` (metadata). The principle is that terms are grouped into
blocks by common prefix so that every block holds enough terms, and a tree is built over those prefixes
which is kept in memory and says which `.tim` block to jump into. In this version that tree is an
ordinary prefix trie; up to Lucene 9.x it was a finite state transducer (FST), so if you meet an older
text speaking of an FST in `.tip`, it is speaking about 9.12, not about this tree. Posting lists are
written by `Lucene104PostingsFormat` into `.doc` (document numbers and frequencies), `.pos`
(positions), `.pay` (payloads and offsets) and `.psm` (metadata). That positions are in a different
file from document numbers is no accident — a query that does not need positions never touches them and
does not clog its cache with them.

Stored values are in `.fdt` (data, compressed in blocks), `.fdx` (index) and `.fdm` (metadata), term
vectors in the same triple `.tvd`/`.tvx`/`.tvm`. Doc values are in `.dvd`/`.dvm`, plus `.dvs` for the
skip index, which is new in version 10. Norms are in `.nvd`/`.nvm`, BKD tree points in
`.kdd`/`.kdi`/`.kdm`, vectors in `.vec`/`.vex`/`.vem` (and quantized ones in `.veq`/`.vemq`, which is
also a version 10.4 thing). The field description is in `.fnm`, the segment description in `.si`.

`.liv`, the so-called **live docs**, deserves a separate mention. Deleting a document in fact deletes
nothing — you cannot delete from an immutable segment. Instead, a bitmap is written beside it saying
which documents are still alive, and all queries respect it. The data physically disappears only on
merge. Updating a document is then merely shorthand for "delete and add again", i.e. a new document in
a new segment and one bit dropped in the old one.

The last piece is the file `segments_N`, which holds the **commit point** — the list of segments
forming a consistent state of the index. A new commit writes a new `segments_N`; until that happens,
the new segments do lie on disk but no commit refers to them.

Optionally all of it is glued into a single `.cfs` file with a directory in `.cfe` — that is against
exhausting file descriptors on indexes with many small segments.

---

## 9. Document numbers, merging and one trap

Inside a segment every document has a **docID**, which is a plain integer from zero. All the speed
rests on that — posting lists are sorted sequences of these numbers, delta-compressed.

The trap is that **a docID is not stable**. It is valid only within one segment and only until the next
merge; on merge documents are renumbered and deleted ones omitted. Never store a docID outside the
index and never send it out as an identity. The link to your own primary keys has to be a separate
field. (In your evitaDB context this is exactly the difference between an ephemeral internal number and
a stable entity PK, and it is one of the places where an in-house engine makes a different choice.)

**Merging** is the process that turns several smaller segments into one bigger one. It is necessary
because reading is done across all segments and the cost of a query grows linearly with their number,
and also because otherwise the space after deleted documents would never be returned. When and what to
merge is decided by the `MergePolicy` — the default being `TieredMergePolicy`, which groups segments of
roughly equal size into "tiers" and has a cap on the size of the resulting segment, so that giant
one-off merges do not arise. Who actually performs the merge is handled by the `MergeScheduler`,
normally `ConcurrentMergeScheduler`, which runs them in the background on its own threads.

Merging is the most expensive thing the index does in the background, and during bulk indexing it is
the main brake — the same bytes are rewritten again and again as small segments gradually coalesce into
large ones.

---

## 10. Visibility: commit versus near-real-time

Reading is done through an `IndexReader`, and it works over a **snapshot**: at the moment of opening it
fixes the list of segments and from then on sees an immutable world, whatever the writer does. For the
reader to see the new material, it has to be reopened (`DirectoryReader.openIfChanged`).

There are two ways for a write to become visible. **`commit()`** is the durable one: it forces a flush,
performs an fsync and writes a new `segments_N`. It survives a process crash, but it is expensive. The
**NRT reader**, i.e. `DirectoryReader.open(indexWriter)`, is the fast one: it makes available even
segments that are so far only in memory or written without an fsync, so the latency from write to
visibility is milliseconds — but it does not guarantee durability. It is to this difference that
Elasticsearch and Solr owe the pair of terms "refresh" and "flush"; refresh makes a document visible,
flush makes it durable.

Accepting that a fulltext index is eventually consistent with respect to the data source is part of the
design, not a compromise. Striving for immediate visibility together with durability kills write
throughput.

---

## 11. Scoring: why it returns exactly this at the top

Finding documents is only half the work; the other half is ordering them. The default function is
**BM25** and it rests on three quantities.

**Term frequency** — how many times the word occurred in the document. More occurrences is better, but
with diminishing returns; BM25 explicitly damps this with a saturation function, so that a document
with fifty repetitions is not fifty times more relevant than a document with one. **Inverse document
frequency** — how rare that word is across the whole collection. A rare word carries more information,
so it gets a higher weight; this is the component that largely handles stopwords by itself. And **field
length**, stored in the norms: a match in a short title weighs more than a match in a long description,
because it is less accidental.

All of it is replaceable through the `Similarity` abstraction (`BM25Similarity` is merely the default
implementation), and above that sit query-level and field-level boosts by which one can say "a match in
the name is three times as valuable as a match in the description".

For e-commerce it is essential that textual relevance alone is almost never enough — the customer wants
a combination of relevance, availability, margin and popularity. That leads to a two-phase evaluation
in which a cheap function selects candidates and an expensive one reorders them, and to those further
signals living in doc values, where they are cheaply available. Your plans P4 and P7 develop exactly
this consideration.

---

## 12. What to take away

If it had to be five sentences: fulltext is the intersection of sorted lists of numbers, and everything
else is preparation for those lists to exist and be correctly filled. Analysis is the only place where
it is decided what will actually be in the index, and it has to be done identically on write and on
query, otherwise the system silently stops finding things. Token positions are not a detail, they are
what makes phrases and synonyms. A segment is immutable, deleting is only a bit in a bitmap, and a
docID does not survive a merge. And the decision about which structures each field gets is schema
design, which can later be changed only by reindexing.

If you want to get hands-on, the cheapest experiment is to run the analyzer standalone with that piece
of code from chapter 3 over a Czech sentence and look at what falls out of `CzechAnalyzer`. The second
cheapest is the **Luke** tool (`lucene/luke/` in this tree), a GUI in which you open a finished index
and click through the term dictionary, the posting lists and individual documents. For understanding
what was really written, nothing is faster.

And if you want to see how it works one floor down — how posting lists are encoded, what exactly lies
on the heap and what in mmap, why search is fast even for an index larger than memory, and how vectors
work — continue with part two: `lucene-under-the-hood.md`.

---

### Version notes

The text describes `/www/oss/lucene` in the state `11.0.0-SNAPSHOT` (index format 10.4). Against Lucene
9.12, from which plan P5 starts, the following mentioned things differ: the doc values skip index
(`.dvs`, `DocValuesSkipper`) is new in version 10; the quantized vector formats `.veq`/`.vemq` come
from 10.4; `CaseFoldingFilter`, `SentenceAttribute` and the `morph` package are not in 9.12; and the
classes `Lucene103BlockTree*` and `Lucene104Postings*` carry the numbers `Lucene90`/`Lucene99` there,
even though they write the same file extensions. One difference is not merely in the name: the index
over the term dictionary (`.tip`) is still a finite state transducer (FST) in 9.12, whereas here it is
a plain prefix trie. The concepts and APIs described above hold unchanged in both versions.
