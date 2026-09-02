# P1 — the index core: prototype implementation plan

> **Status: an implementation plan, not a decision.** It follows on from the research
> [`../research.md`](../research.md) (v2, consolidated 2026-08-04, last revised 2026-08-12), namely
> §4.1, §4.2, §4.3, §4.8 and §7. The argument "why an in-house engine and not Lucene" is not repeated
> here — it is in the research (§3).
>
> Written on 2026-08-12. All anchors into the code were verified against the state of the `dev` branch
> on the same day. Translated from Czech and moved into this record on 2026-08-24.

---

## 1. Goal, scope and criteria

P1 is **the prototype of the core of the risk**. Its task is not to deliver a feature but to answer by
measurement two questions on which the whole B′ design rests and which cannot be answered by reasoning:

1. **Does the text branch fit into memory?** The research estimates ~85–135 MB per 1M products and one
   locale (§4.8) and ~250–400 MB for the CMS profile with a hundred thousand long documents. Both
   estimates are parametric, unsupported by measurement, and — as §4.3 below shows — they are
   **sensitive to the shape of the impact sidecar**, i.e. precisely to what P1 designs. Without
   measuring, the gate P5→P1→P2 is blind.
2. **Is phase 1 fast enough?** The goal is ≤ 25 ms per 1M candidates in a single thread. The real
   variable, however, is not the number of candidates but **the sum of the postings lengths of all
   expanded terms** (§5.1) — and that is something nobody has computed or measured so far.

Beside that, P1 delivers a **qualitative comparison** against today's `attributeContains`, so that it is
visible what deploying fulltext gains and what (less obviously) it loses.

### 1.1 The gate's criteria

| Criterion | Goal | Note |
|---|---|---|
| RAM, e-commerce profile | ≤ 150 MB per 1M products and locale | JOL, deep-retained delta |
| RAM, CMS profile | within the bounds of §4.8 (~250–400 MB / 100k documents) | the same measurement procedure |
| Phase 1 latency | ≤ 25 ms per 1M candidates, 1 thread | JMH, measured per expansion width |
| Quality | side-by-side against `attributeContains` on ~50 queries | breakdown see §7.5 |

The RAM criterion is evaluated **for the shape of sidecar P1 actually builds** — not against the number
from §4.8. If P1 chooses the more robust (and more expensive) sidecar variant, the goal moves too; §4.3
gives the arithmetic of both.

Both numbers in the table moreover concern the **finished structure**, as JOL measures it. Beside them
P1 reports the **memory peak during the build** as well, as a separate figure without a threshold of its
own — the gate does not rest on it, but it must not remain unknown. The reason is a concrete precedent:
OpenSearch had to write a second builder for its side structure, `OffHeapStarTreeBuilder` beside
`OnHeapStarTreeBuilder`, because the build did not fit onto the heap for large segments
(`server/src/main/java/org/opensearch/index/compositeindex/datacube/startree/builder/StarTreesBuilder.java:152`
and `:154`; verified over the `main` checkout, commit `36edc05ac84`, 2026-08-12). The peak during the
build can therefore be a multiple of the size of the result — and for us it is relevant precisely
because **the initial population over an existing catalog builds everything at once**, unlike later
incremental operation. The measurement procedure is in §7.2.

### 1.2 What P1 deliberately does not do

- **No transactionality.** P1 builds the structures once from a catalog snapshot. Maintenance under
  write load, COW of chunks and the write-path budget are the subject of P2 (§4.5, §4.9 of the
  research). P1 must, however, **design a data shape that makes P2 possible** — and name where the traps
  in that shape are.
- **No integration into the write path.** `AttributeIndexMutator`, `EntityIndex` and the schema are not
  touched. The design in §3.3 shows where the structure will be hung in F1, so that the prototype does
  not go against the grain.
- **No analyzers of its own.** Tokenization is the subject of P5, which precedes the gate. P1 works with
  the tokenizer as a pluggable interface and, should P5 not have run yet, with its provisional stand-in
  (§7.1).
- **No rank profiles nor boost channel.** That is P7. P1 implements a single, hard-wired profile — the
  lexicographic 64-bit composite of §4.3 of the research — and does so in a way that generalizing it to
  profiles will not require rewriting the scorer.
- **No DSL, no constraint.** `attributeMatches` and `relevance()` are not implemented in P1; the scorer
  is called directly from the harness. The connection to the formula engine is nevertheless sketched
  (§5.4), because it affects the shape of phase 1's output.

---

## 2. Links to the research and to the neighbouring prototypes

**What P1 adopts as its brief:** placing the structures exclusively in `GlobalEntityIndex`, partitioned
per locale and scope (§4.1); three structures per (collection, locale, scope) — the term dictionary,
postings, the impact sidecar (§4.2); postings **per (field, term)**, not field-collapsed, because field
weights belong in the query, not in the index (§4.2); the impact byte as `sat(tf) × norm(field length
against a pivot)` without corpus statistics (§4.2); the lexicographic composite of lanes as the default
profile (§4.3); full-set scoring without WAND (§2.3).

**What P1 assumes from P5:** a stable tokenizer per locale and agreement between the indexing and query
analysis on the produced terms. P1 does not produce a tokenizer, but its output is an input of the index
build — if P5 decides otherwise (a different stemmer, a different normalization), the **content** of the
dictionary changes, not its shape, so P1 can be remeasured against a new analyzer without a rewrite. The
concrete shape of the contract is already proposed by the P5 plan (`p5-analyzers.md`, §4.2): one method
emitting records `(term, startOffset, endOffset, positionIncrement)`, with a seam left for an
allocation-free variant with a callback. P1 consumes only the terms from it — offsets are needed only by
highlighting and P4 — but it should consume **that** contract, not a simplified one of its own.

**A decision P5 explicitly delegates to P1:** whether the dictionary will carry only the stem, or the
stem and the surface form (`p5-analyzers.md`, §4.2 and the recommendation on the same fork). P5 designs
the contract so that it emits the surface form — after analysis it cannot be recovered — but what the
dictionary does with it is to be decided by **P1 by measuring the dictionary's size** (and by P3 through
the suggester's quality). K3 therefore measures it in both variants; it is one builder parameter, not a
second implementation.

**What P1 hands over to P2:** the decision on the sidecar's shape including its invariants, the measured
RAM of both considered shapes, and a list of places where the rank alignment can diverge under the
transactional layer (§4.3.4). That is input for P2, not a detail. The P2 plan
(`p2-transactional-maintenance.md`, §2) moreover stipulates two things and it is good for both sides to
understand each other:

- **The sidecar's physical shape.** P2 marks it as a commitment that P1 will build the sidecar in the
  shape its recommended variant B assumes. That variant is about the **timing** of the realignment
  (aligned chunks arise once at commit-merge, not continuously), not about the layout — and the
  committed shape it aims at is rank-aligned `byte[]` chunks per container, i.e. exactly variant **S1**
  below. The recommendations of both documents therefore agree and P2 rebuilds nothing. **The same holds
  for the later variant D of plan P2** (invalidation on write, lazy computation on read): it moves the
  realignment from the commit to the first read, does not change the chunks' shape and asks nothing of
  P1 but one validity bit per chunk. P1's commitment to P2 is therefore single and the same for both
  variants.
- **A schema flag for a fulltext field including a maintenance switch.** P2 needs it as a measurement
  baseline (the same build with the flag switched off) and warns that if P1 does not deliver it, it
  becomes P2's first step. **P1 does not deliver it** — §1.2 excludes it and §3.3 explains why (the flag
  does not exist in the schema today and it is F1 work intertwined with O6). It is therefore
  deliberately P2, step 1, not an omission; recorded as question Q7.

**What P1 hands over to P7:** phase 1's feature vector as an array of values aligned to the candidate set
(§5.3). The boost map and rank profiles hook into it without changing the scorer — which is exactly why
the scorer composes the lanes into the composite **only in the last step** and not continuously.

**What P1 does not touch, even though §1.4 of the research mentions it:** the scoring expansion across
references (content blocks, related documents). The research says explicitly that the gate is not widened
because of it, but that the design seam must be present in P1. That seam is concretely: **the feature
vector carries the provenance of the match**, i.e. a separately tracked contribution "the match came
through a reference of type R". In P1 that channel is left empty, but a lane for it exists in the
composite (§5.3).

---

## 3. What already exists in the code — verified anchors

This is the load-bearing part of the plan: most of what §4.2 of the research describes as "new work" is
already in the repo in some form. Verified by reading the sources, not by estimation.

### 3.1 The dictionary and postings: `TransactionalBucketBPlusTree` is a finished term dictionary

`io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree` is a transactional B+ tree with a **columnar
leaf** that maps a comparable key onto a set of record ids. Its properties are exactly those a term
dictionary needs:

- **The key is `Comparable`, the value is a set of PKs.** The leaf holds three parallel columns of length
  `valueBlockSize`: the keys, a primitive `int` column with a single record id, and a **lazily allocated
  sparse overflow column** `TransactionalBitmap[]` for multi-element buckets
  (`TransactionalBucketBPlusTree.java:60-99`). A bucket with one record therefore costs no bitmap — it is
  the same small-set pattern Meilisearch solves with the CBO codec (§8 of the research). Promotion to a
  bitmap happens at the second distinct PK, demotion is postponed to commit-merge so that a bucket does
  not oscillate between representations within one transaction.
- **The write surface** is `IntRecordBucketTree` (`IntRecordBucketTree.java:41-91`): `addRecord(K,
  int...)`, `removeRecord(K, int...)`, `getRecordsEqualTo(K)`, `cardinalityOf(K)` — the bucket's
  cardinality without materializing a bitmap, which is exactly the free `docFreq` §4.2 of the research
  speaks of in the paragraph on BM25F.
- **A range scan for prefixes** is given by `cursor(K value)` — a forward cursor established at a value
  (`TransactionalBucketBPlusTree.java:1178`), complemented by `cursor()`, `reverseCursor()` and
  `contains(K)`. A prefix expansion is therefore "establish a cursor at the prefix and walk while the key
  starts with the prefix" — without a single new tree operation.
- **Front coding** is not a property of the tree in general but of its columnar leaf:
  `ValueColumnFactory` picks `FrontCodedStringColumn` **exclusively for `String` keys**
  (`ValueColumnFactory.java:88-100`, the branch `String.class.isAssignableFrom(plainType)`).
  `FrontCodedStringColumn` itself is "the Lucene term-dictionary layout" with a restart point every 16
  entries (`FrontCodedStringColumn.java:48-50, 121`) — i.e. literally the structure Lucene uses for its
  term dictionary.
- **Transactionality** is ensured by participation in the MVCC framework as a
  `TransactionalLayerProducer`, with the same diff layers as the rest of the indexes.

**A consequence for the design that is easy to overlook:** `TransactionalObjectBPlusTree` and
`TransactionalElementBPlusTree` **do not use `ValueColumnFactory` at all** (verified: zero occurrences in
both files). Whoever wants a front-coded dictionary has to use `TransactionalBucketBPlusTree` and must
have a `String` key. That is a hard constraint from which the whole of §4.1 below follows.

### 3.2 Bitmaps and `rank` — and why `rank` must not get into the hot loop

`io.evitadb.index.bitmap.TransactionalBitmap` is a transactional roaring bitmap over a vendored fork
(`PersistentRoaringBitmap`). Three things about it matter for the sidecar:

- **`rank(pk)` is not O(1) in any useful sense.** `PersistentRoaringBitmap.rankLong`
  (`PersistentRoaringBitmap.java:2321`) walks the array of containers linearly up to the one `pk` falls
  into, and calls `Container.rank` in it. That is a binary search for an `ArrayContainer`
  (`ArrayContainer.java:1407`), but for a `BitmapContainer` it is **a loop of up to 1024 popcounts**
  (`BitmapContainer.java:1645`). The formulation of §4.2 of the research — "the lookup is
  `bitmap.rank(pk)`, O(1) with respect to postings length" — is therefore technically true (the upper
  bound does not depend on postings length), but the constant is such that in a linear pass over a
  million candidates times the number of terms it is unbearable. The solution is not to optimize `rank`
  but to **remove it from the hot loop** (§5.1).
- **A sequential rank exists, but only at container level.** `Container.getCharRankIterator()`
  (`Container.java:416`) returns a `PeekableCharRankIterator` with `peekNextRank()`, i.e. an iterator that
  continuously carries the rank of the next value. The implementations are finished for all three
  container types (`ArrayContainer.java:2025`, `BitmapContainer.java:2547`, `RunContainer.java:3504`) and
  are covered by the test `TestRankIteratorsOfContainers`.
- **At bitmap level, however, no such factory exists.** `PersistentRoaringBitmap` does not expose
  `getIntRankIterator()`; the field `highLowContainer` is package-private
  (`PersistentRoaringBitmap.java:103`), `RoaringArray` is a package-private class (`RoaringArray.java:50`)
  and `ContainerPointer` is a package-private interface. **Outside the package `io.evitadb.roaringbitmap`
  one therefore cannot walk by containers.**

The last point is concrete work for P1 with a cost that has to be admitted: any container-aligned sidecar
needs **a new public API in the vendored fork**. The ADR
`documentation/adr/2026-07-07-roaring-bitmap-vendoring.md` warns in its "Consequences & open follow-ups"
section that (a) re-syncing with upstream is a manual process driven by the `roaring-bitmap-sync` skill,
(b) the `shared[]` array for copy-on-write is a manually maintained invariant that broke four times during
the port, and (c) the whitespace of the vendored tree must stay byte-identical with upstream, otherwise
every replay turns into a merge conflict (which is why `evita_roaring_bitmap/**` is excluded from
`.editorconfig`). Extending the fork is therefore not a zero item — it is an extension of the surface
somebody will be merging manually next year.

`Bitmap.indexOf(int)` (`Bitmap.java:118`) is the public random variant with the `Arrays.binarySearch`
contract; the implementation in `RoaringBitmapBackedBitmap.indexOf`
(`RoaringBitmapBackedBitmap.java:154-162`) is built precisely on `rank` + `select`. For sparse use
(feature export over a returned page of 20–50 entities) it is entirely sufficient; for a hot loop it is
not.

### 3.3 Where the structure will be hung

`GlobalEntityIndex` (`GlobalEntityIndex.java:79`) inherits from `EntityIndex`. That holds shared
subsystems as fields — `attributeIndex`, `facetIndex`, `hierarchyIndex`, `entityIds`,
`entityIdsByLanguage` (`EntityIndex.java:122-159`) — and offers a registration mechanism for subsystems
owned by a subclass: `addComponent(IndexComponent)` (`EntityIndex.java:993`). `GlobalEntityIndex` already
uses it for `priceIndex` (`GlobalEntityIndex.java:160`). The `IndexComponent` interface
(`IndexComponent.java:53-112`) asks for three methods: `collectModifiedStorageParts`, `resetDirty`,
`removeLayer` — i.e. exactly the contract "I can say what I changed, and I can take part in a commit".

The granularity of §4.1 of the research fits this structure without friction: both the **collection** and
the **scope** are already the identity of the index itself (`EntityIndexKey` is a record carrying `Scope
scope`, `EntityIndexKey.java:49-64`), so the only dimension the new structure has to handle itself is the
**locale** — and for that `EntityIndex` has a precedent in `entityIdsByLanguage`, which is a
`TransactionalMap<Locale, TransactionalBitmap>`.

The target shape for F1 (P1 does not build it, it merely aims at it): `GlobalEntityIndex` gets a field
`FulltextIndex`, registered via `addComponent(...)` in the constructor. Mind the documented rule:
`captureOriginalsFromComponents()` has to be called as the **last step** of the terminal subclass's
constructor, i.e. after all `addComponent(...)` calls (`EntityIndex.java:1012`, JavaDoc).

**The write path** leads through `AttributeIndexMutator.executeAttributeUpsert`
(`AttributeIndexMutator.java:151`). There is also the gate deciding whether the indexes are touched at
all:

```java
if (attributeDefinition.isUniqueInScope(scope) ||
    attributeDefinition.isFilterableInScope(scope) ||
    attributeDefinition.isSortableInScope(scope)) { … }
```

Fulltext will add a fourth disjunct. **That flag does not exist today, though** —
`AttributeSchemaContract` knows only `isUniqueInScope`, `isFilterableInScope`, `isSortableInScope`
(`AttributeSchemaContract.java:125, 249, 294`) and nothing in the vicinity of "searchable". Introducing
the flag (and with it the pivot of length normalization, §4.4) is therefore **schema work for F1**,
intertwined with O6 of the research (searchable associated data). P1 avoids it by configuring both the
pivot and the field selection outside the schema, in the harness.

### 3.4 Persistence: granular paging is a finished pattern

§4.2 of the research wants "a paged `StoragePart` format as a deliberate seam". That pattern is finished
in the repo and used by five indexes. The template is a triple:

- `FilterIndexLeafPagePart` (`FilterIndexLeafPagePart.java:24-110`) — one persisted **leaf page** of a
  tree; it carries the leaf's buckets in ascending order, the routing spine is not stored and is
  reconstructed on load. The identity is the pair `(streamId, pageSequence)`, packed into the storage
  part's PK.
- `FilterIndexLeafPageRemoval` — the counterpart for deleting pages.
- `LeafStreamKey` — one entry in the `KeyCompressor` dictionary per persisted sub-index.

The key detail, which is hard to guess and is described in the JavaDoc: a writing page **does not know
its `streamId`**, because a writable `KeyCompressor` lives on the storage side; the identity is resolved
only in `computeUniquePartIdAndSet(KeyCompressor)` just before the write. The numeric side of that seam is
supplied by `BucketBPlusTree.leafPageHandles()` (an enumeration of leaves in ascending key order) and
`bulkLoadPage(...)` for the opposite direction.

P1 **does not implement** persistence — it builds in memory. But the format of the dictionary and the
chunks has to be such that it fits into this template without rearrangement, and §4 takes that into
account.

### 3.5 Today's baseline: `attributeContains`

`AbstractAttributeStringSearchTranslator` (`.../filter/translator/attribute/`) builds a predicate from the
constraint and delegates to `FilterIndex`. What matters is what `FilterIndex` does:

```java
public Formula getRecordsWhoseValuesContains(@Nonnull String text) {
    /* TOBEDONE JNO naive and slow - use RadixTree */
    final String normalizedText = (String) this.normalizer.apply(text);
    return this.invertedIndex.getRecordsMatchingFormula(
        value -> ((String) value).contains(normalizedText));
}
```

(`FilterIndex.java:592`; `getRecordsWhoseValuesEndsWith` on line 579 is of the same stamp.) It is a
**complete pass over all the distinct values** of the attribute with the predicate `String::contains`,
over values normalized to Unicode NFD (`FilterIndex.java:278-284`). Two things for §7.5 follow: the
baseline is slow in proportion to the number of distinct values, and above all it **matches a substring
of the whole value**, so a multi-word query ends on it with practically zero results. The comparison "who
is better" therefore has to be split, otherwise we win by definition and learn nothing.

### 3.6 The seam for the sorter: `FilteredPriceRecordAccessor`, not `FilteredPricesSorter`

The research points at `FilteredPricesSorter` as a precedent for "a sort by values computed during query
evaluation" (`FilteredPricesSorter.java:63`, implementing `Sorter` with the single method
`sortAndSlice`). The genuinely transferable pattern is, however, one floor down — it is **the interface by
which the formula tree hands the sorter the values it created during the computation**:

```java
final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors = FormulaFinder.find(
    orderByVisitor.getFilteringFormula(), FilteredPriceRecordAccessor.class, LookUp.SHALLOW
);
```

(`PriceNaturalTranslator.java:81-83`; `PriceDiscountTranslator.java:181` does the same.) The nodes of the
formula tree that produce price records implement `FilteredPriceRecordAccessor`
(`FilteredPriceRecordAccessor.java:42-54`) and the ordering translator finds them in the filter's tree.
The relevance sorter will be the same pattern with a different interface (§5.4) — and that is good news,
because it means **the formula engine really is not touched**, as §4.3 of the research promises.

### 3.7 Measurement precedents

- **JOL** is declared only in `evita_test/evita_performance_tests/pom.xml:151`
  (`org.openjdk.jol:jol-core`). Anything using JOL must therefore live in that module.
- **The measurement pattern** is given by `BucketStoreMemorySpike` — in the module
  `evita_test/evita_performance_tests` under `src/main/java/io/evitadb/spike/radixtrie/`. It is a plain
  `main`, not JMH; it measures footprint as `GraphLayout.parseInstance(live).totalSize() -
  GraphLayout.parseInstance(empty).totalSize()`, i.e. a **deep-retained delta against an empty structure
  of the same type**, by which the framework's constant graph is subtracted out. The input is
  `*.buckets.tsv` files extracted from a real catalog. P1 will repeat exactly this shape.
- **A JMH harness** for latency has dozens of precedents in the same module
  (`InvertedIndexBlockSizeBenchmark`, `FrontCodedFindKeyBenchmark`, …).
- **Real data** gets into the measurement by two routes: the JMH state family under
  `io/evitadb/performance/` that drives a production e-commerce catalog, and `WalReplayBenchmark`, which
  boots an embedded instance from a
  directory snapshot passed by the system properties `evita.replay.catalogName` and
  `evita.replay.pristineDataDir` (`WalReplayState.java:139-265`). The datasets are **not** in the repo —
  they are supplied externally.

---

## 4. Design of the data structures

### 4.1 The dictionary's layout: one tree, or a tree per field?

The question is how to represent the key (field, term). The answer is predetermined by the finding of
§3.1: front coding exists only in the columnar leaf of `TransactionalBucketBPlusTree` and only for
`String` keys. **A composite key as a record or as a pair therefore falls away at once** — it would land
on `BoxedObjectColumn` and front coding would be lost entirely.

Two live variants remain.

**Variant D1 — one tree, key `prefix(field) + term` (recommended).** The key is a `String` composed of a
fixed-width field identifier and the normalized term. The order of keys is then exactly (field, term),
because the prefix has a constant length.

- *For:* one structure per (collection, locale, scope) — one root, one `LeafStreamKey`, one page
  sequence. Front coding pays for the field prefix practically entirely, because every neighbouring key
  in the same field shares it (a restart point comes only every 16 entries,
  `FrontCodedStringColumn.java:121`). Prefix expansion is a single cursor established at `prefix(field) +
  sought_prefix`, walking while the key starts with the prefix.
- *Against:* expanding across several fields requires as many passes as there are fields. But with single
  digits of searched fields that is insignificant and it is the same work a tree per field would do.
- *A detail worth not skimping on:* encode the field prefix as **a fixed few ASCII characters** (four
  hexadecimal digits, say). Single-character encoding `(char) fieldId` is three characters cheaper, but
  introduces the risk of the identifier hitting the surrogate range `0xD800–0xDFFF`, which would break the
  assumptions of `FrontCodedStringColumn`'s fast path (a byte compare over UTF-8 for a BMP-only corpus).
  For three characters front coding erases in the vast majority of cases anyway, it is not worth it.

**Variant D2 — a tree per field, the key being a plain term.**

- *For:* the keys are shorter and cleaner, no prefix encoding; prefix expansion within one field is
  trivial; per-field statistics (the number of terms, `docFreq`) are directly `bucketCount()`.
- *Against:* the number of trees = the number of searched fields × locales × scopes × collections. Every
  tree has its own root, its own stream in `KeyCompressor` and its own page sequence; the overhead grows
  linearly with the number of fields and in the CMS profile (where the fields are often only two or
  three) it does not matter, but for a product catalog with ten searched fields it is ten parallel
  structures instead of one. Moreover, the sharing of front-coded prefixes between fields, which exists in
  practice (the same term is commonly in the name and in the description), disappears.
- *Where it would win:* if it turned out we want field weights projected into the structure already
  (which §4.2 of the research explicitly rejects) or if it turned out that individual fields have
  orders-of-magnitude different write cadences and we want to page them independently.

**Recommendation: D1.** The measurable difference is mainly in the dictionary's RAM (~8 MB per §4.8, i.e.
an item the total budget barely feels), so the decision should be made on simplicity and the number of
persistent streams — and D1 wins on both. P1 will nevertheless build the builder so that the field prefix
is a parameter; remeasuring D2 then costs one run, not a rewrite.

### 4.2 Postings: a bucket's value, not a separate map

The research asks whether to hold postings as a dictionary→bitmap map, or as a value in the B+ tree. The
answer is given by §3.1: **`TransactionalBucketBPlusTree` holds postings as a value already today** and
does it better than a map would — a single-element bucket is a single `int` in the primitive column, a
bitmap is allocated only at the second PK, and demotion is postponed to the commit. That is, without
further work, the small-set codec because of which the research refers to Meilisearch (§8,
`cbo_roaring_bitmap_codec.rs`). A separate map `term → bitmap` would moreover mean **a second occurrence
of the term's string in memory** as the map's key, which with 400–500 thousand keys is not negligible.

One parameter remains: **the leaf block size.** `InvertedIndex` uses `VALUE_BLOCK_SIZE = 256` with a
reference to `InvertedIndexBlockSizeBenchmark` and to the fact that its workload is "point lookup +
limited range + many writes" (`InvertedIndex.java:~120`, the constant's JavaDoc). The term dictionary has
a different profile: writes in P1 are one-off (a bulk load), reads are a point lookup **plus a prefix
range scan**, which may be long. That argues rather for a larger block. P1 will measure it as a
by-product — it is one constructor parameter — and record a recommendation; it is not a point the gate
would rest on.

### 4.3 The impact sidecar — P1's real decision

Here lies the greatest uncertainty of the design, because the sidecar is the only structure §4.2 of the
research labels new work, and at the same time it is the item the RAM budget is most sensitive to.

#### 4.3.1 The relation chunk ↔ container ↔ rank

A roaring bitmap divides the PK space into containers of 2¹⁶ values; a container is either an
`ArrayContainer` (a sorted `char[] content`, up to 4096 values), a `BitmapContainer` (1024 `long` words),
or a `RunContainer`. The sidecar is partitioned **along container boundaries**: for a (field, term) pair
and for every container of its postings bitmap there is one chunk. Inside a chunk the i-th byte is the
impact value of the i-th PK **in the order within the container** — i.e. the rank within the container,
not the global rank in the bitmap. The global rank is computed as the sum of the cardinalities of the
preceding containers, which is information the bitmap has to hand.

The choice of the container boundary is not aesthetics: it is the only boundary at which **a write does
not propagate further**. Inserting a PK into a container shifts the positions of all the following PKs **in
that same container** and in no other — so one chunk is rewritten, not the whole sidecar of the term. That
is exactly what §4.5(2) of the research speaks of when it says whole chunks of the affected (term ×
container) combinations are rewritten.

#### 4.3.2 Three shapes and their arithmetic

Take the parameters of §4.8 of the research: ~20M (field, term, PK) triples for 1M products and one
locale.

| Variant | Bytes per pair | Estimate for 20M pairs | Random lookup |
|---|---|---|---|
| **S1** rank-aligned `byte[]` | 1 B + array overhead | ~20 MB + ~10 MB overhead | requires rank |
| **S2** parallel `char[]`+`byte[]` | 3 B + array overhead | ~60 MB + ~20 MB overhead | binary search |
| **S3** hybrid by container type | 1–3 B | in between | depends on type |

For those two overhead numbers to be readable, it has to be said what is in them. **The column "bytes per
pair" already counts both arrays**: 3 B for S2 is 2 B for the `char` plus 1 B for the `byte`, i.e. 20M
pairs × 3 B ≈ 60 MB. **The overhead column, by contrast, is only the array headers on the heap** and is
governed by the *number* of arrays, not by their size: with 400–500 thousand (field, term) keys and on
average one to two containers per key it is of the order of half a million chunks. For S1 that is half a
million arrays (~10 MB of headers), for S2 a million, because **there are two arrays per chunk** (~20 MB).
The doubling of overhead therefore does not come from the arrays being bigger but from there being twice
as many of them — and that is at the same time why the overhead estimate is the softest number in the
whole table: it depends on the real distribution of the number of containers per key, which only K3 will
measure (handover P→budget, §8).

**What that does to the gate:** S1 moves the total estimate of §4.8 to ~85–135 MB (as it is written),
whereas S2 moves it to roughly **~125–175 MB** — i.e. above the 150 MB criterion, or just below it,
depending on where in the range the measurement falls. The gate's criterion is therefore **genuinely
sensitive** to this choice and it cannot be decided from an armchair.

**S1 — a rank-aligned chunk.** The chunk is a `byte[]` of length equal to the container's cardinality; the
i-th byte belongs to the i-th value of the container.
- *For:* the cheapest possible representation, exactly what §4.2 of the research assumes.
- *Against:* the chunk **carries no information about which PK a byte belongs to** — it is a pure function
  of position in the bitmap. Any operation that rebuilds the bitmap silently derails the sidecar. That is
  a hazard, not a theoretical note; §4.3.4 develops it.

**S2 — a self-describing chunk.** The chunk is a pair `char[] lowbits` and `byte[] impacts` of the same
length, sorted by `lowbits`.
- *For:* it **does not depend on the bitmap at all**. It survives a rebuild, a swap and a merge of
  transactional layers. A random lookup is a binary search in a `char[]` of at most 65536 elements, i.e.
  ≤ 16 steps — an order of magnitude cheaper than `BitmapContainer.rank`.
- *Against:* three times the memory.
- *Beware of confusion:* this is **not** variant C of plan P2 (`p2-transactional-maintenance.md`, §6),
  even though both are "a sidecar without alignment". P2 there considers a transactional **tree or map**
  keyed by the pair (term, PK) and rejects it on the cost of reading and on memory ("a tree node per pair
  instead of a single byte is an order-of-magnitude difference") — which holds for a tree. S2 is a sorted
  parallel array: reading is a binary search without pointer indirection and the memory is three times,
  not an order. P2 marks its rejection as **conditional** and awaits a number from P1; S2 is precisely
  that number, because it probes a cheaper point in the same space than the one P2 rejected.

**S3 — a hybrid by container type.** The observation: an `ArrayContainer` **already holds its `char[]
content`** and the position in it *is* the rank within the container. For sparse containers S1 therefore
gives self-description for free — if the sidecar has access to that `content[]`. Only dense
`BitmapContainer`s are expensive, where the rank costs popcounts; but there are few of them (they arise
only from high-frequency terms, of which there are a handful in a Zipf distribution), so precisely for
them either S2 or a small precomputed table of prefix popcounts pays off.
- *For:* the best ratio of memory to robustness.
- *Against:* two representations mean two paths in every operation, and above all access to the
  container's `content[]` **runs again into the package-private boundary** of §3.2.

#### 4.3.3 Recommendation

**Build S1 as the primary shape and measure S2 as the fallback — in the same run.** S1 is at the same
time the shape the recommended variant B of plan P2 (§2) commits to, so by choosing S1 P1 breaks nothing
for P2. The reason for the double measurement is pragmatic: the builder is the same for both variants
(they differ only in what they write to the output), so the double measurement costs hours, not days. The
gate's result is then not a single number but **a budget and its insurance**: what the cheap variant
costs and what it would cost to escape to the robust one, should P2 knock the cheap one down. Without
that, P2 in the event of failure would have to send the whole gate back to the beginning.

**Do not build S3 in P1.** It is an optimization over a decision that has not been made yet, and its cost
(extending the vendored fork with access to containers' internals) is exactly the item the vendoring ADR
labels the most expensive. Note it as a path in case S1 falls on robustness and S2 on memory.

**It is worth noticing that P2 arrives at the same hybrid from the other side.** The concluding refinement
of its §6 proposes a **cardinality threshold**: under a Zipf distribution most (term, container)
combinations are tiny and for them the chunk apparatus is overhead without benefit, so they would be
stored as direct pairs — which is the S2 shape applied selectively, i.e. exactly S3. P2 itself adds that
introducing a threshold right away makes no sense until it is measured whether there is anything to save.
**And that measurement is K3 and K4.** The distribution of cardinalities per (term, container) pair and
the measured difference S1 vs. S2 are precisely the two numbers without which the threshold cannot be
chosen — so P1 does not build the hybrid but supplies the material by which P2 either chooses or rejects
it.

#### 4.3.4 What P1 must hand over to P2: where the rank alignment can diverge

This is the most important thing P1 will record, because P2 would discover it expensively:

1. **A commit produces a new bitmap.** `TransactionalBitmap.createCopyWithMergedTransactionalMemory`
   returns **a new `BaseBitmap`** built from the merged layer (`TransactionalBitmap.java:108-116`), not a
   mutated original instance. A sidecar aligned to the rank of the original bitmap has to be realigned at
   commit, or be aligned to the merged one. That is a design point, not an implementation detail.
2. **The diff layer changes the rank already during the transaction.** `TransactionalBitmap.indexOf` and
   `get(int)` read `layer.getMergedBitmap()` when a layer exists (`TransactionalBitmap.java:326-343`), i.e.
   the order *including* uncommitted changes. A reader seeing the previous version has to read a sidecar
   aligned to the previous version — otherwise it gets foreign impact values, and does so **silently**,
   because every byte is a valid value.
3. **Demoting a bucket removes the bitmap entirely.** A bucket that returns at commit from a bitmap to a
   single `int` (§3.1) ceases to have a container — the sidecar for it has to be able to degenerate into a
   single byte.
4. **Compaction and re-materialization of pages.** The ADR
   `documentation/adr/2026-07-10-more-optimized-data-structures/README.md` has among its open points that
   `InvertedIndex.collectChangedPages` builds fresh bitmap and array objects during compaction. Anything
   aligned to a bitmap's identity or order passes through this place and has to survive it.

Points 1–3 are exactly what variant S2 makes moot. That is why S2 is insurance, and why P1 measures it
even though it does not recommend it as primary.

#### 4.3.5 Versioning the sidecar's format — decide in P1, not afterwards

The sidecar is **a new binary format with a non-trivial layout** (chunks along roaring container
boundaries) and the research skimps on it in §4.2 with a single sentence that it is subject to the same
Kryo and BWC discipline as the other indexes. That is true, but it is little: the discipline says *how* an
incompatible change is made, not *what* happens when the engine hits a chunk of an older shape.

How seriously versioning side structures is taken elsewhere is visible in OpenSearch (verified over the
`main` checkout, commit `36edc05ac84`, 2026-08-12). Their star-tree is wired in as an extension of the
Lucene codec and **four generations of composite codecs** live side by side in the repository
(`composite101`, `composite103` and `composite104` in
`server/src/main/java/org/opensearch/index/codec/composite/backward_codecs/`, plus the current
`composite912`). The format version is moreover such first-class information that even the replication
checkpoint carries it: `ReplicationCheckpoint` has, beside the segment generation, a `codec` field right
there (`server/src/main/java/org/opensearch/indices/replication/checkpoint/ReplicationCheckpoint.java:37`–`:41`).

**What follows for P1.** The decision is not "write migrations" — those are written when needed — but
**reserving a place for the version in the layout and stating in advance what happens on a mismatch**.
There are two choices and both are legitimate: either an older chunk is read by a compatible reader (the
path described in evitaDB by the `kryo-bwc-audit` skill: bump `serialVersionUID` exactly once and register
a BWC reader in every Kryo configurer that registers the type), or the **sidecar is declared a derived
structure and on a mismatch is discarded and rebuilt from the postings and the length array**. The second
path is defensible for us in a way Lucene engines cannot manage, because the sidecar is a deterministic
function of data that is entirely in the catalog — but it is a choice that **must fall before the format
comes into being**, because it determines whether a chunk needs a self-describing header at all. Recorded
as question Q8.

**These points are, however, no longer open — P2 answered them.** The recommended variant B of plan P2
(`p2-transactional-maintenance.md`, §6) sidesteps points 1 and 2 by construction: during a transaction the
alignment is not maintained at all (the diff layer is a plain unaligned delta of `(PK, impact)` pairs) and
the aligned chunks arise **once, inside the owner's commit-merge**, where the merged bitmap is already to
hand — so "realign onto the new bitmap" is not an extra step but the only place the alignment arises at
all. Point 3 remains as a minor edge case to handle and point 4 as a watch during compaction. The record in
this document is therefore not an open question for P2 but **a consistency check**: should P1 end up with
a shape other than S1, variant B stops fitting on these points. The same holds for the later variant D of
the same plan — it merely postpones the realignment from the commit to the first read and assumes the same
chunk shape, so it too asks nothing new of P1 beyond one validity bit.

### 4.4 Computing the impact byte at indexing time

The impact byte is `min(255, sat(tf) × norm(field_length))`, quantized into a single byte. Lucene has the
precedent for the quantization: it stores the length norm in a single byte (`SmallFloat.intToByte4`) and
decodes it with a 256-entry table in `BM25Similarity` (§8 of the research, verified anchors). P1 will do
the same for the product, with a decoding table of 256 `float` values computed at startup.

**What has to be to hand for the computation:** the tokenized content of the field (from P5) — i.e. for
every (field, PK) pair a sequence of terms — from which every term's `tf` and **the field's length in
tokens** are derived. Nothing else: per §4.2 of the research `norm` is not computed against the corpus
`avgFieldLength` but against a **pivot configured in the schema**, so the score remains a function of the
query and the document only.

**Where it is computed:**
- In P1 in the builder (§7.1), with the pivot as a harness parameter.
- In F1 inside the indexing path. The entry point is `AttributeIndexMutator.executeAttributeUpsert`
  (`AttributeIndexMutator.java:151`), which already today has the value converted to the schema type and
  passes it into `indexForUpsert.upsertAttribute(...)`. Fulltext will hook in there, with a fourth disjunct
  in the gate and with the pivot from the schema.

**A fork worth naming: store the product, or the factors separately?** Storing the product (as §4.2 of the
research assumes) is the cheapest and gives one lookup. It has two unpleasantnesses, though: changing the
pivot is then **a reindex**, and the full BM25F of F3 (which wants `tf` and the field length separately)
cannot decompose the factors back out of it. Cheap insurance: **additionally store the field length per
(field, PK)** in a dense array of bytes (quantized, the same table). For 1M products and single digits of
fields it is a fraction of a per cent of the budget — and it unlocks both retuning the pivot without a
reindex and F3.

**Recommendation: store the product in the sidecar (as the design says) and at the same time keep a
length array per (field, PK).** It is the cheapest item of the whole budget with the best ratio to what it
unlocks, and P1 has to compute it anyway in order to produce the product at all.

---

## 5. The phase 1 scorer

### 5.1 Why `rank` must not appear in the hot loop — and why that does not matter

A naive reading of §4.2 of the research leads to this scorer: "walk the candidate bitmap, for every PK and
every term fetch the impact via `bitmap.rank(pk)`". Per §3.2 that would mean, for every (candidate, term)
pair, a linear walk of the containers plus up to 1024 popcounts. With a million candidates and single
digits to tens of expanded terms it is 10⁸ to 10¹⁰ operations — two to three orders of magnitude away from
the goal of 25 ms.

The answer to that is not a faster `rank`. It is the observation that **phase 1 does not need rank at
all**:

- Postings are walked **sequentially**. In a sequential walk the rank is simply the step's ordinal number
  — a counter costing one `int` and one increment.
- Skipping would be needed only if the candidate set were small relative to the postings. Per Z7 of the
  research, though, the must-match filter removes only 5–15 % of the corpus, so the candidate set is
  **almost the whole corpus** and there is nothing to skip. That is the same argument by which §2.3 of the
  research removes WAND — merely applied one floor lower.

Random access remains needed only in two places where it is cheap: **maintenance on write** (P2) and
**feature export / explain** over a returned page of 20–50 entities, where `Bitmap.indexOf` (§3.2) is more
than sufficient.

### 5.2 The cost model the harness has to measure

The cost of phase 1 is **not** a function of the number of candidates. It is:

```
Σ_{t ∈ expanded terms} |postings(field, t)|   +   |candidates| × composing the composite
```

The first addend dominates and has two independent variables the harness has to vary separately:

- **the term's frequency.** A query for three high-frequency terms ("pro", "set", "black") reads three
  postings of a size close to the corpus; a query for three rare terms reads thousands of PKs.
- **the expansion width.** Prefix and typo expansion (§4.6 of the research) multiplies the number of
  postings read. Three tokens each expanded into fifty variants means 150 passes, and if the variants are
  frequent, it is 1.5×10⁸ steps — **out of budget**, and that with a single million candidates.

This is the main result P1 is to bring: **where the knee is**. The mitigations are known and both have a
precedent: a hard cap on the number of expanded terms (§4.6 of the research mentions it) and ordering the
expansion by postings cardinality so that the cap is spent on variants that bring something — which is
exactly what Typesense does via `rank_tokens_by=FREQUENCY` (§8 of the research, VK). P1 implements both as
a parameter and measures where the usable value lies.

### 5.3 The walk and composing the composite

The recommended shape of the walk — the analogue of what `FilteredPricesSorter` does with price records:

1. **Materialize the candidate set as a sorted `int[]`.** `Bitmap.getArray()` can do that and the formula
   engine does it anyway. A parallel array of accumulators of the same length arises beside it.
2. **For every expanded term, merge.** The term's postings and the candidate array are both sorted, so
   they are walked in a single concurrent step with two cursors. The position in the postings gives the
   **rank for free** (§5.1), i.e. an index into the sidecar chunk; the position in the candidate array
   gives an index into the accumulator. No searching, no hashing.
3. **Accumulate the lanes' contributions, not a finished score.** For every candidate is kept: the number
   of matched terms, the weighted sum of typos, the maximum impact (across all fields and terms, with the
   field's weight from the query), the best exactness reached, and a prepared place for the contextual
   rank and for the provenance of a match across a reference (§2, the seam for §1.4 of the research).
4. **Compose the 64-bit composite only at the end**, in a single pass over the accumulators, per the table
   of lanes in §4.3 of the research. The bit split (8/8/8/8/16/16) is a matter for the prototype, not of
   principle.
5. **Select the top-N with a heap** over the array of composites.

Why compose at the end and not continuously: because **P7 needs to enter between steps 3 and 4** — the
boost map and the rank profile both work with the feature vector, not with a finished composite, and §4.3
of the research explicitly asks that a boost act on the full set, not only on the top-K. When the composite
is composed continuously, P7 means rewriting the scorer; when it is composed at the end, it is a new step
between 3 and 4.

**The walk's memory cost:** a million candidates times an accumulator. With a `long[]` for the composite
and several parallel `byte[]`/`short[]` arrays for the lanes it is single digits to the low tens of MB
**transiently per query**. With a share of fulltext queries below 1 % (Z7) that is acceptable;
nevertheless P1 measures it and records it, because as the read share grows (which §4.9 of the research
warns of) it becomes an item.

### 5.4 Wiring into the formula engine and the sorter

P1 calls the scorer directly. A design of the target wiring, so that the prototype does not go against the
grain:

- The `attributeMatches` constraint (O4 of the research) translates into a **formula node** that computes
  the candidate bitmap (an OR over the postings of the expanded terms, an AND with the must-match filter)
  and **keeps beside it the feature vectors** that arose during that computation anyway.
- That node implements an interface analogous to `FilteredPriceRecordAccessor` (§3.6) — say
  `MatchFeatureAccessor` with a single method returning feature vectors aligned to its computed bitmap.
- The `relevance()` translator finds those nodes in the filter's tree through
  `FormulaFinder.find(..., LookUp.SHALLOW)`, exactly as `PriceNaturalTranslator.java:81` does, and builds
  a sorter that is merely a top-N selection over an array of `long`s.

That keeps the promise of §4.3 of the research that the formula engine is not touched: fulltext is one
more node able to compute a bitmap, and one more sorter able to order by values computed during
evaluation. Facets, histograms and `require` blocks receive the full candidate bitmap as they do today.

### 5.5 Parallelizing phase 1 — why it is safe and why it is nevertheless not the first step

P1 measures single-threaded and that does not change. This part merely records **what parallelizing phase
1 entails, should it come to it**, so that the walk design of §5.3 does not close itself against it before
somebody considers it.

**For us it is safe by definition, not by construction.** OpenSearch added parallelization within a shard
— the segments are divided into slices and each is processed by its own thread — and had to **prove that
slicing does not change the score**. It proved it by reading the corpus statistics from the top-level
reader, not from the slice: `ContextIndexSearcher` overrides both statistical methods so that the slice
does not enter the answer
(`server/src/main/java/org/opensearch/search/internal/ContextIndexSearcher.java:555` and `:570`; verified
over the `main` checkout, commit `36edc05ac84`, 2026-08-12). Our model **has no corpus statistics at all**
(§2.1 of the research): the score is a function of the query and the document only, so there is nothing to
share between the parts and splitting the candidate set cannot change the score even theoretically. It is
the same conclusion they reached by work.

**The natural splitting boundary is the roaring container boundary**, i.e. 2¹⁶ PKs — and that is at the
same time the boundary at which we chunk the sidecar (§4.3.1). That is not an aesthetic coincidence but a
load-bearing reason: within a part the mechanics of §5.1 stay preserved, where the rank is simply the
sequential step's ordinal number. Were the split made anywhere else, every part would have to get its
starting rank from somewhere, and the argument the whole scorer rests on would fall. The scheduling of
parts into threads has a ready-made template, there is no need to invent it: OpenSearch uses the classic
**LPT schedule** — the parts are sorted descending by document count and each is assigned to the least
loaded slice
(`server/src/main/java/org/opensearch/search/internal/MaxTargetSliceSupplier.java:123`).

**Calibrating expectations, and why it is the second step.** Their default number of slices is
`max(1, min(processors / 2, 4))`, so very conservatively **at most four**
(`server/src/main/java/org/opensearch/search/SearchService.java:2109`), and in automatic mode it
parallelizes essentially only when **the query has aggregations**
(`server/src/main/java/org/opensearch/search/DefaultSearchContext.java:1043`). That is the empirical
verdict of somebody who measured it at production volumes: it pays off on the full-set aggregation path,
not on the top-K text one. Our phase 1 over almost the whole corpus plus facets has the character of the
former, so there is hope — but for small candidate sets the overhead of scheduling and merging will
outweigh it. The order of work therefore stays as §1 says: **the single-threaded number first, parallelism
only as a second step**, with an expectation of single-digit parts, not tens. P1 does not create a step K
for it.

**A warning formulated in advance.** The risk of parallelization does not lie in ordering, but in
**aggregations with truncation per part**. In OpenSearch every slice truncates to `shard_size` before the
slices are merged (`InternalTerms.java:467`), so a term that is just below the boundary in every part but
would globally make it into the top-N disappears — and they had to introduce their own error flag for it,
`hasSliceLevelDocCountError` (`InternalTerms.java:232`), which enlarges the reported upper bound of
inaccuracy. **Our facets and histograms are exact**, because they are computed from full bitmaps by set
operations and the cardinality of an intersection is the same whether computed whole or in parts and
summed. They will stay exact exactly as long as nobody introduces the "top-N per part" shortcut into them
— whoever ever proposes it as a speed-up buys this error with it.

---

## 6. The realization procedure, step by step

The steps are ordered so that each builds on a finished and measurable predecessor. Steps K1–K4 are
necessary to answer the RAM question, K5–K6 the latency question, K7–K8 quality.

**K1 — a corpus extractor.** A standalone tool that extracts from a real catalog, for chosen fields, the
triples `(pk, fieldId, text)` into a plain file. It boots an embedded instance from a directory with a
snapshot, i.e. the same way as `WalReplayState` (the system properties `evita.replay.catalogName`,
`evita.replay.pristineDataDir`, `WalReplayState.java:139-265`). *Why separate:* the measurement run then
has no live catalog on the heap, repeats in seconds and can be run on a different machine from the one
where the data lies. Precedent: `BucketStoreMemorySpike` reads `*.buckets.tsv` extracted in advance.

**K2 — the tokenization interface and its provisional implementation.** Not an interface of our own but
**the contract designed in P5** (`p5-analyzers.md`, §4.2): a method emitting records
`(term, startOffset, endOffset, positionIncrement)`. P1 reads only the terms from them, but hooks into the
same contract, so that swapping the stand-in for the real analyzer is a swap of implementation, not a
rewrite of the caller. Until P5 delivers, the provisional implementation: NFD normalization (because of
coexistence with today's `FilterIndex`, `FilterIndex.java:278-284`), lowercasing, splitting on non-letter
characters. *Explicitly: the numbers from this run are not qualitatively comparable with a run over the P5
analyzer* — they serve to develop the harness and for a first RAM estimate.

**K3 — the builder of the dictionary and the postings.** From the input of K1+K2, build a
`TransactionalBucketBPlusTree` per variant D1 (§4.1): the key `prefix(field) + term`, the value via
`addRecord`. The field prefix and the leaf block size are parameters. The output is the structure and the
computed `docFreq` per key. Part of the step is also **measuring the dictionary's size in two modes — stem
only, and stem plus surface form** (§2): P5 conditions its open fork on this number and without it has
nothing to decide it with. A by-product is the real Zipf curve of both profiles (handover P→budget, §8).

**K4 — the sidecar builder, both variants.** From the same input compute `tf` and the field lengths, from
them the impact bytes (§4.4), and store them both as S1 (rank-aligned `byte[]` chunks) and as S2 (parallel
`char[]`+`byte[]`). Beside that a dense table of lengths per (field, PK). Part of the step is also
**reading the memory peak during the build** and the smallest `-Xmx` at which the build completes (§7.2) —
without that, that figure never comes into being in the procedure, because a measurement run after the
build completes shows only the finished structure. *Here we learn the answer to the gate's first question.*

**K5 — term expansion.** Prefix expansion via a cursor (§3.1) and typo expansion via a Levenshtein DFA.
For P1 the direct construction from `lucene-core` suffices (`LevenshteinAutomata`, §3 of the research; a
hard cap of distance 2), interleaved with a dictionary-guided walk of the tree. *Should wiring Lucene in as
a dependency prove a friction in P1, a crude expansion (all terms up to distance 2 computed naively) can be
used for the latency measurement — the phase 1 latency numbers do not depend on it, because they measure
walking postings, not the cost of expansion.*

**K6 — the phase 1 scorer.** The walk per §5.3, with a parameterizable expansion cap and expansion ordering
by cardinality. *Here we learn the answer to the gate's second question.*

**K7 — a set of ~50 queries and the baseline.** Assembling the queries (§7.5) and running today's
`attributeContains` over the same catalog for a comparison of the result sets.

**K8 — writing up the findings.** The measured numbers, the expansion knee, the recommendation on the
sidecar variant, and the list of traps for P2 per §4.3.4.

---

## 7. The harness and measurement

### 7.1 Where the harness lives and what it looks like

**Module:** `evita_test/evita_performance_tests`. It is not a choice of style — JOL is declared only there
(`pom.xml:151`).

**Shape:** two plain `main`s in `io/evitadb/spike/fulltext/` (the K1 extractor and the K2–K4 measurement
run), plus one JMH benchmark for latency (K6). *Do not put the index build into JMH.* The build is a
one-off operation of the order of minutes; JMH would either measure it as a whole (which says nothing) or
would have to do it in `@Setup` (and then it measures only the scorer anyway). The precedent for both is in
the module: `BucketStoreMemorySpike` is a `main`, `InvertedIndexBlockSizeBenchmark` is JMH.

### 7.2 Measuring RAM with JOL

Take the procedure literally from `BucketStoreMemorySpike:574`:

```java
GraphLayout.parseInstance(live).totalSize() - GraphLayout.parseInstance(empty).totalSize()
```

The deep-retained size of the live structure minus the deep-retained size of an empty structure of the same
type. The difference subtracts the framework's constant graph (the tree's root, the empty columns, the
transactional wrappers), so the result really is the cost of the data.

Measure separately, not merely the sum: the dictionary (the tree without the sidecar), the postings (the
bitmaps in the overflow column), the S1 sidecar, the S2 sidecar, the length table. Without a breakdown one
cannot say which item failed to meet the estimate of §4.8 — and that is precisely the information P2 and a
possible reworking of the design need.

Two measurement traps worth explicit mention:
- **The structure shares objects with its surroundings.** The bitmaps in the overflow column belong to the
  structure, but term strings may be interned or shared with the input. Discard the input after the build
  and force a GC before measuring, otherwise `GraphLayout` counts the input data too.
- **The transactional wrappers are not free and belong in the measurement.** Precisely the difference
  between an "ideal immutable structure" (arrays indexed by the term's ordinal) and the transactional tree
  is the cost of transactionality. P1 ought to measure **both** — it is one extra run and it gives P2
  exactly the number it will be deciding on.

**The peak during the build is measured differently from the finished structure** (§1.1). JOL can weigh a
graph of objects but says nothing about how much memory the build demanded transiently — and yet precisely
that decides whether the initial population of the index over a large catalog passes or ends in an
`OutOfMemoryError`. A procedure sufficient for it that requires no profiler:

- at several points of the build (after the dictionary, after the postings, after the sidecar) force a GC
  and record `Runtime.totalMemory() - Runtime.freeMemory()`; the difference against the size of the
  finished structure is the build's transient overhead,
- and above all find **the smallest `-Xmx` at which the build completes** — that is the number somebody
  actually needs when writing an operational recommendation, and it is obtained by bisection in a few runs.

Both are reported for both profiles separately; a worse ratio is expected for the CMS profile, because an
order of magnitude more text is tokenized at once.

### 7.3 Measuring phase 1 latency

JMH, one thread, with the parameters: the number of query tokens, the expansion width, the terms' frequency
class (rare / medium / high-frequency), the share of candidates in the corpus. Without varying the expansion
width and the frequency class the number is worthless (§5.2).

The usual traps of measurement runs recorded in the repo apply: the benchmark must demonstrably do work
(check that the result is not constant), and the run must not share the machine with anything else.

### 7.4 Datasets

**The e-commerce profile.** A real production e-commerce catalog. It is **not** in the repo — it is supplied
externally as a directory with a snapshot, the way `WalReplayState` consumes it. Mind the memory: booting a
real catalog is gigabytes and the measurement run must not take place on a machine where the heap is tight.
That is precisely why K1 is separated from K2–K4 (§6).

**The CMS profile (Z8).** Here the dataset **does not exist even externally** and it is a real schedule
risk, not a formality. The options, in order of evidentiary value:

1. **A real CMS export from a customer.** The only variant with the right distribution of document
   lengths, the right Zipf curve of terms and the structure of content blocks from §1.4 of the research. It
   requires somebody specific to deliver it — and that is a matter for agreement, not for planning.
2. **A dump of the Czech Wikipedia** (`cswiki`, articles). The right language (hence relevant for collation
   and stemming), the right order of article length, publicly available, reproducible. The disadvantage:
   encyclopedic prose is not a marketing website, the vocabulary is richer — the RAM estimate will come out
   rather pessimistic, which is a safe direction of error for the gate. It has to be reckoned with that
   downloading the dump is a manual step outside the run (sandbox networking is restricted), so it has to be
   planned, not assumed.
3. **Synthesis from existing data** — assemble long documents by concatenating product descriptions so that
   ~500–600 unique terms per document come out. Available immediately, but the distribution of terms is
   product-like, not prose-like, and the RAM estimate is sensitive to precisely that distribution. **Use
   only to develop the harness, never as a gate number.**

*Recommendation:* develop on (3), measure the gate on (1), and if (1) does not arrive in time, measure on
(2) with an explicit note that it is a pessimistic surrogate corpus.

### 7.5 The qualitative side-by-side against `attributeContains`

The baseline is per §3.5 a substring match over the whole attribute value, without any ordering. Two things
follow that the comparison has to respect if it is not to be mere self-congratulation:

**Split the queries into two groups.**
- **~25 single-word queries.** Here the comparison is honest and interesting in both directions. Fulltext
  should win on morphology ("bunda" finds "bundy"), diacritics and typos. The baseline, however, **will win
  on infixes**: `attributeContains("5000")` finds the token "5000mAh", whereas a tokenized index finds it
  only if the tokenizer split it. That is a real regression P1 has to see and name, not conceal — and it is
  an argument for keeping `attributeContains` alongside fulltext, as §4.4 of the research promises.
- **~25 multi-word queries.** Here the baseline **structurally cannot** — it looks for a contiguous
  substring of the whole value, so "black leather jacket" finds nothing unless the attribute value reads
  exactly that. Report this group as a **difference of capabilities**, not as a score. Presenting "50 : 0"
  as a measurement of quality would be misleading.

**Where to take the queries from.** The best source is real queries from production (the search logs of a
customer's website), because they carry the real distribution of lengths, typos and brands. If they are not
available, assemble them by hand so that the set covers: an exact name, a brand + parameter, a query with a
typo, a query with missing diacritics, a prefix (search-as-you-type), a word form outside the base one, a
numeric token inside a word, a multi-word phrase, a query for a term from long text (the CMS profile).

**What it is evaluated by.** The research (§7) says quality is not to be measured at the gate with our own
yardstick, and refers to the Sage comparison harness / golden set. Verifying that premise is part of the
open questions (§8, Q5): both referenced documents
(`/www/oss/Sage/docs/analysis/golden-set-analyzer.md`, `search-comparison-final.md`) are **analyses and
plans**, the second of them with an explicit "Status: analysis only, no code changes" from 2026-05-04.
Whether a harness has come about in the meantime cannot be told from those documents. Until that is
confirmed, P1 ought to plan with a plain but honest evaluation: for every query record both result sets and
the first 10 fulltext results, and have them assessed by a human — with the output stored in a
machine-readable form so that it can later be run through a harness once one exists.

### 7.6 A testable property: a score independent of the candidate set

Beside latency and memory, P1 is to introduce one **correctness test** that is cheap and protects a
property much of the research's argument rests on: **a document's score must not depend on who that
document is currently being compared with.**

The test's shape is trivial — the same document, the same query, two different must-match filters (one
broad, one narrowed so that a handful of entities remain in the candidate set), and a comparison that the
document got an **identical value** of both the feature vector and the composite. The property follows
directly from the score being a function of the query and the document only, without corpus statistics
(§4.2 of the research), so the test has nothing to uncover — until somebody introduces into the scorer a
shortcut that looks at the size of the candidate set or normalizes by its maximum. That is precisely why it
should come about right away: it is a guardian of an assumption, not a check of a computation.

**It is at the same time a property Lucene engines do not have**, and it is worth recalling, because it
explains why this test is not written elsewhere. A BM25 score is a function of the corpus, and because
every shard sees a different subset of documents, two identical documents get different scores depending on
where they lie. Elasticsearch has a remedy for it — the DFS scatter phase, which before the query collects
statistics from all shards and unifies them — but **it is not the default mode**: `SearchType.DEFAULT` is
`QUERY_THEN_FETCH` (`server/src/main/java/org/elasticsearch/action/search/SearchType.java:36`; verified
over the `main` checkout, commit `9a100e2d0e41`, 2026-08-13). In other words, even with full knowledge of
the problem its authors chose inaccuracy as the default behaviour, because the remedy costs an entire extra
network round before every query.

The test has one more concrete consumer: **variant D of plan P2**
(`p2-transactional-maintenance.md`, §6) considers a degraded mode in which the impact lane is skipped when a
chunk is missing. Such a mode violates this property and question O-P2-9 is to be decided precisely against
it — without the test that discussion would be conducted on impressions.

---

## 8. Open questions and handovers

Questions arising **in P1** that the research does not address. P1 neither answers nor reopens the questions
O1–O10 of the research; the relation to them is summarized at the end of the section.

The section is deliberately divided: **questions** (Q1–Q7) are things P1 cannot decide and somebody has to;
**handovers** (P→3, P→2) are conversely results P1 produces that somebody else consumes. Mixing them
together would mean a reader looking for blockers would find among them items that are no blockers at all.

### Questions

**Q1 — Extending the vendored roaring fork: when and how much?** The sidecar variant S3 (§4.3.2) and any
container-aligned optimization need public access to containers, which the package-private boundary closes
today (§3.2). The cost is permanent — it enlarges the surface of the manual re-sync with upstream the
vendoring ADR warns of. Decide only per the result of K4: if S1 passes and P2 sustains it, the question
dissolves. The second potential pressure on the fork — vendoring the `buffer` package (~14–17k lines) for
zero-copy mmap reading — is mapped and deferred in `bitmap-memory-optimizations.md` (§3.4, §3.7); it does
not belong in F1.

**Q2 — The dictionary's leaf block size.** `InvertedIndex` has 256 on the basis of its own benchmark for a
different load profile (§4.2). The term dictionary has a bulk-load + prefix-scan profile, which argues for a
larger block. Measure as a by-product of K3; it is not a point the gate would rest on.

**Q3 — The query's transient memory.** Feature vectors aligned to a million candidates (§5.3) are single
digits to the low tens of MB **per query**. At today's fulltext share (< 1 %, Z7) that is immaterial; as the
read share grows, which §4.9 of the research anticipates, it is an item. Measure in K6 and record it, so
that somebody does not have to discover it under load.

**Q4 — Availability of a CMS dataset.** Without it the gate's second criterion cannot be evaluated (§7.4).
It is an organizational dependency, not a technical one, and therefore nobody will resolve it automatically.

**Q5 — Does the Sage comparison harness exist, or is it so far a plan?** §7 of the research relies on it at
the gate; both referenced documents are "analysis only" analyses (§7.5). Until that is confirmed, P1 plans
with its own simple evaluation and a machine-readable output.

**Q6 — The loss of infix matching against `attributeContains`.** It is assumed that `attributeContains`
remains alongside fulltext (§4.4 of the research promises it) — but P1 ought to measure how often real
queries depend on an infix. If often, it is input for P5 (rules for tokenizing numbers and alphanumeric
codes), not for P1. A second consumer of that number now exists: the trigram substring index
(`p8-trigram-substring-index.md`, §33), which attacks the infix case from the other side by making the
literal `contains` path fast — if infix dependence turns out to be common, it strengthens that line
rather than P5's tokenization rules.

**Q7 — who will deliver the schema flag for a fulltext field?** P2 needs it as a switch for its baseline
(`p2-transactional-maintenance.md`, §2 and §10.1) and reckons that if P1 does not deliver it, it becomes
P2's own first step. P1 deliberately does not deliver it (§1.2, §3.3): there is nothing of the kind in the
schema today and introducing it is F1 work intertwined with O6 of the research. This is not a technical
question but a question of the order of work — and it is better decided before P2 discovers it at the start.

**Q8 — how is the sidecar's format versioned and what happens when an older chunk is read?** (§4.3.5.) Two
legitimate answers: a compatible reader per the Kryo and `serialVersionUID` discipline (the recipe is in the
`kryo-bwc-audit` skill), or declaring the sidecar a derived structure that on a mismatch is discarded and
rebuilt from the postings and the length array. **P1 does not implement persistence (§3.4), but it needs the
answer before the format**, because it determines whether a chunk needs a self-describing header, or whether
a version at the level of the whole persisted sub-index suffices. Deciding it later means changing the
format.

### Handovers — P1's results somebody else is waiting for

**P→3 — where the expansion knee is.** The cap on the number of expanded terms and its interaction with a
term's frequency class is **the result of K6** (§5.2), not an open question. The consumer is P3 (the
suggester), which runs over the same structures with a substantially stricter latency budget (p99 ≤ 5 ms per
keystroke, including typo expansion). Without the number from K6, P3 would have to seek the same cap again.

**P→2 — the distribution of cardinalities and the difference S1 vs. S2.** The result of K3 and K4. Without
those two numbers the cardinality threshold proposed by the conclusion of §6 of plan P2 (§4.3.3) cannot be
chosen, nor can its variant C be conditionally rejected.

**P→5 — the dictionary's size in two modes.** The result of K3. P5 conditions its fork "stem only vs. stem
plus surface form" on this number (§2). The research `bitmap-memory-optimizations.md` (§2) adds a third
variant K3 is to measure too: the tree of surface forms owns the postings and the stem tree holds only lists
of term keys (a lazy OR at query time, no duplication of bitmaps).

**P→budget — the real Zipf curve of both profiles.** The estimate of §4.8 rests on ~300k unique terms for
1M products and on ~500–600 terms per CMS document. Both are estimates; K3 measures both as a by-product and
the numbers belong back in the budget — if they diverge, the whole estimate of §4.8 diverges. Beside the
curve itself, **the distribution of the number of containers per key** is needed too, because the softest
item of the table in §4.3.2 (array overhead) rests precisely on it.

**Relation to the research's open questions.** P1 **touches** O3 (typo thresholds — it implements them
parametrically, it does not decide the defaults) and indirectly O6 (searchable associated data — P1 feeds
long texts in as attributes, as §4.2 of the research permits, so it says nothing about the shape of the
schema flag). P1 **does not touch at all** O1, O2, O4, O5, O7, O8, O9 or O10; those belong to P4, P6, P7 and
phase F3. The only one of them P1 must not close is O10 — which is why the feature vector has a place for
the provenance of a match across a reference from the start (§5.3, step 3).
