---
title: Rank the index-footprint work on a production catalog, and take 4.2 GB out of its resident heap
date: 2026-09-04
updated: 2026-09-05 05:44
status: accepted
kind: optimization
issues: [1486, 1455]
prs: []
areas: [evita_engine/index/bPlusTree, evita_engine/index/range, evita_engine/index/price, evita_engine/index/attribute, evita_engine/index/hierarchy, evita_engine/index/array, evita_engine/index/invertedIndex, evita_engine/index/bitmap, evita_engine/core/query/algebra, evita_test/evita_performance_tests/spike/trigram]
supersedes: []
superseded-by: []
relates: [2026-09-03-content-sized-value-tree-columns, 2026-08-01-bplustree-cursor-free-insert-path, 2026-09-04-millisecond-temporal-precision, 2026-07-10-more-optimized-data-structures, 2026-08-31-trigram-query-path-optimization, 2026-08-16-per-index-usage-statistics]
---

# Rank the index-footprint work on a production catalog, and take 4.2 GB out of its resident heap

`2026-09-03-content-sized-value-tree-columns` gave the bucket-keyed value tree columns whose physical
backing follows their live content. Its long-keyed sibling `TransactionalLongBPlusTree` never got the
same treatment, and it is what every `RangeIndex` is built on: a leaf allocated `long[512]` plus a
`V[512]` of references — 6,176 bytes — however little it held. Measured on the demo dataset, a range
index holds **4.0 points**, so the leaf ran at **0.78 % occupancy**. The leaf now carries its logical
capacity as a field and lets both arrays follow the content, through the same `ColumnSizing` policy.
The measured saving is **≈49.5 MB on the demo dataset** and **≈2.33 GB on a production e-commerce
catalog** (18 collections, 564,187 entity indexes), over two disjoint sets of objects. The demo figure
is quoted only because it is what the work was developed against; **the production figure is the
result**, and the gap between them is the most transferable thing in this record.

That gap then reorganised the whole campaign. Every remaining item in the queue was ranked on a demo
measurement, and the demo understates anything that scales per index by about two orders of magnitude.
A whole-catalog census on the production catalog was run instead, and it **re-ranked the queue
completely**: three of the four largest targets on that catalog had never been looked at, and the one
the campaign had been carrying as its next big idea — a columnar layout for price record bodies — turned
out to be chasing 173 MB of a 2,242 MB row. Seven optimizations were then implemented against the
production numbers, each above a 50 MB production threshold, each with its own reader-visible invariant
and its own counterfactual.

**The measured result is a resident heap of 7,416.2 MB against the branch base's 11,655.7 MB — 4,239.5 MB,
36.4 %** — and that figure covers only the tree content sizing. Six further optimizations landed after it
was taken; the final number is in *Verification*.

Every commit then went through a gate: an adversarial review of the assembled line and a six-batch
four-agent quality pipeline. They were not a formality. The adversarial pass found a **sixth torn
reader** the campaign's own sweep had missed, in the long tree's keyed iterator constructors, and the
pipeline found a **real regression** the bucket tier had introduced in the trigram query path. Both are
fixed and pinned; the gate results are in *Verification*.

## Why

The earlier record left a bullet in its *Consequences* that reads, about the four sibling tree families:
"they exist in the thousands rather than the hundreds of thousands, so the same change there buys tens
of kilobytes." **That is true of the thing it was written about — the boxed bucket count, worth ~56
bytes per tree — and false of leaf arrays, which are worth ~6 KB per tree.** A reader applying it to
leaf sizing would have closed the largest remaining item in the issue without measuring it. Correcting
that inference is most of the reason this record exists.

The prize also had to be found rather than derived. The issue's own accounting looked only at attribute
value trees; range indexes sat outside it, and price indexes sat outside *that*. Three separate
estimates in this campaign had already been retracted for being arithmetic over an unaudited model, so
the number here comes from probes reading the engine's own accounting on a real catalog, run against a
build of each commit's own source.

**And the ordering of everything that remained was untrustworthy for the same reason.** The queue was
built from demo-dataset figures. The demo carries a few thousand entity indexes; the production catalog
carries 564,187. Any item whose cost is *per index* was therefore understated by roughly two orders of
magnitude, and items were being ranked against each other on those numbers. Re-measuring on production
was cheap — both probes already existed and the catalog loads in about 25 s — and it changed which work
was worth doing.

### Previous state

`BPlusLeafTreeNode` allocated `new long[blockSize]` and `Array.newInstance(valueType, blockSize)` in its
constructor and never resized either. Capacity and physical length were therefore always the same
number, which made every `keys.length` / `values.length` read ambiguous by construction — 23 of them —
and `isNearlyFull()` carried a javadoc arguing *deliberately* that reading the array length was safer
than reading the tree's configured block size, precisely because nothing enforced the coupling.

## Options considered

*These are the options for the tree content sizing itself. The six later decisions, each of which had
its own fork, are in* Decisions taken *below.*

### Option A — content-size the leaf arrays behind an explicit logical capacity (chosen)

Give the leaf a `capacity` field holding the block size, park an empty leaf on the shared empty array,
grow geometrically on insert, and trim when the commit merge builds a new committed leaf. Identical in
policy to `ColumnSizing` as the bucket tree already uses it.

- **Pros:** one package, no contract change, no second representation, no persistence question — the
  leaf page format encodes the live run, never the array length. Costs a large tree nothing: a full
  leaf allocates exactly what it always did.
- **Cons:** decouples two numbers that were the same, so every existing read of an array length has to
  be re-pointed at whichever it actually meant. That is where the risk is, and it is real.

### Option B — lower `RangeIndex`'s value block size (declined)

Leave the tree alone and set the range index's block size to something near the observed occupancy.

- **Pros:** a one-constant change, no new field, no audit of length reads.
- **Cons:** the block size is structural, not a tuning knob. It sets the split fan-out and the persisted
  leaf page granularity (`RangeIndexLeafPagePart`), so lowering it changes page boundaries for every
  existing catalog and multiplies the page count for the indexes that legitimately hold many points.
- **Rejected because:** it trades a fixed cost on small indexes for a fixed cost on large ones and
  perturbs the on-disk paging, whereas content sizing is free for both. **Revisit if** a workload ever
  shows range indexes clustered at a size where a smaller *page* — not merely a smaller array — is the
  win.

### Option C — leave the sibling trees alone, per the earlier record's bullet (declined)

- **Rejected because:** measured. 4,219 range indexes at 0.78 % leaf occupancy are 24.5 MB on the demo
  dataset alone, and the price indexes another 25.0 MB. The "tens of kilobytes" estimate was about a
  different structure.

## Decision

**Chosen: Option A.** The fork was not really "which design" — it was whether the sibling tree was worth
touching at all, and that question was answered by measurement rather than by argument. The port itself
is the policy the bucket tree already proved, applied to a leaf that holds raw arrays instead of column
objects.

**The element tree was declined, then reopened, measured on the wrong catalog, nearly reverted, and
kept — and that sequence is the most useful thing here.** What settled the reopening trigger was not
the per-index occupancy histogram it asked for, but the cheaper move of *building the port and
measuring the difference directly*: the histogram would have cost about as much and still needed
interpreting, whereas the port produces the number itself and is revertible.

**The port was then recommended for reversion on a demo-dataset measurement of 1.7 MB, and that
recommendation was wrong by 84x.** The same port is worth **143 MB** on a production catalog, because
the saving scales with the number of *sparse* leaves and the demo carries ~4,300 price indexes against
production's 283,275. The recommendation was overruled and the port kept; the number that vindicates it
was measured afterwards.

The hypothesis was even written down before the mistake was made — this record already said the saving
"comes from sparse leaves" and that a catalog with many price lists x currencies would be sparser, and
labelled it explicitly as a hypothesis. It was then not tested before ranking the work, although a
production catalog was available. **A measurement on a development dataset is not evidence about
production for any quantity that scales per index**, and quoting one as though it were is the failure
this paragraph exists to stop being repeated. Everything after it was measured on production first.

## When a check cannot fail

The paragraph above is one half of a pair, and the gates found the other half twice in one night. A
measurement on the wrong dataset says nothing about production; **a test whose assertion cannot fail says
nothing about the code**, and it is far harder to notice, because it is green.

- **The single-price holder's own new tests were vacuous against the field the commit introduced.** Every
  fixture set the internal price id equal to the price id, so replacing the new scalar with
  `priceRecord.priceId()` broke nothing in the suite. A fixture with distinct ids now makes four of them
  fail under that substitution.
- **The lazy attribute-family census test compared a literal against its own literal.** It was meant to
  prove that every declared family is named by the suite, so an eighth family added later would have
  escaped it silently. It now enumerates the volatile declared fields by reflection over the class.

Both were found by a *planning* pass reading the tests against the change, not by running them — running
them proves nothing here, which is the whole point. The rule this campaign now works under is that a test
is not evidence until the thing it guards has been broken and the test watched to fail. Five earlier tests
in this issue passed for the wrong reason, three of them unreachable by construction; every change in this
record carries a named counterfactual for that reason.

## The calibration that re-ranked the queue

`CatalogIndexFootprintCensus` walks every entity index of a catalog through the public contracts and
attributes heap to structure families. It exists because the statistics API stops one step short:
`IndexDetail` reports one `heapSizeInBytes` per *named* index and deliberately does not decompose it.
Every figure below is the structure's own `getHeapSizeInBytes()`; nothing is modelled. Catalog: a
production e-commerce catalog, 18 collections, **564,187 entity indexes**, load 24 s, resident heap after
load **7,428.6 MB**.

| family | instances | heap | share |
|---|---:|---:|---:|
| residual — reached by no public contract | 564,187 indexes | **1,970.4 MB** | 31.0 % |
| price reference index | 283,002 | 1,296.0 MB | 20.4 % |
| price super index | 273 | 946.4 MB | 14.9 % |
| attribute sort | 294,152 | 836.1 MB | 13.2 % |
| attribute value tree | 600,815 | 615.6 MB | 9.7 % |
| attribute chain | 33,382 | 297.1 MB | 4.7 % |
| facet reference index | 26,865 | 194.9 MB | 3.1 % |
| attribute range | 92,229 | 167.6 MB | 2.6 % |
| attribute filter / unique | 627,915 | 28.5 MB | 0.4 % |
| **entity index total** | **564,187** | **6,352.7 MB** | **100 %** |

**The accounting explains 85.5 % of the resident heap**, and it reproduces both earlier probes to the
byte — 92,229 range indexes at 167.6 MB, 283,275 price indexes at 2,242.4 MB, 33,806,439 price record
references — which is what says the walk visits every index exactly once and charges each structure to
the right family. Four targets above the threshold, **3.3 GB of the 6.35 GB, had never been looked at**:
the residual, sort indexes, chain indexes and facet indexes.

Three findings changed decisions rather than merely the ordering.

- **Price record bodies are 173.2 MB, not 2.2 GB.** There are 5,673,881 distinct `PriceRecord` objects
  behind 33,806,439 reference slots — **5.96x sharing**. The engine already knows this and does not
  double-charge: the super index prices the bodies, the reference index passes a zero sizer. The 2,069 MB
  above the bodies is the tree holding 4-byte reference slots, so a columnar body layout was chasing the
  small half of the row while making the large half worse.
- **89.7 % of the sort family is value trees nobody had counted.** 261,857 of the 294,152 sort indexes are
  owners, each owning an `InvertedIndex` disjoint from the 615.6 MB of value trees a *filter* index points
  at — ~262,000 trees averaging 32 records and 20 distinct values. The same sparse-leaf shape the range
  port already exploited, on a structure four times the size. One attribute is 52.9 % of the family:
  226,470 sort indexes over an `Integer`, 442.5 MB.
- **Half the price super index is per-entity holders, not prices.** 5,673,881 `EntityPrices` objects — the
  distinct record count exactly — cost 476.3 MB in object shells, two one-element arrays and a boxed map
  key, against 173.2 MB of actual bodies. Every one of them is a single-price holder on this catalog.

For issue #1455 the same run produced the exact per-cardinality distribution the issue needed: 395,613
multi-record buckets holding 8,782,760 records, cardinality p50 5 / p90 22 / p99 213 / max 113,238,
**129.9 MB as Roaring against 39.5 MB as sorted `int[]`**, with 95.1 % of all buckets holding a single
record and no bitmap at all. Only 2,558 buckets are genuinely cheaper as Roaring.

**One decomposition route was tried and does not work.** A JOL walk that subtracts an index's reachable
sub-indexes from the whole index cannot separate that index's own unreachable state from shared attribute
values it merely *points at*, because the owner of those values is a different index and is not available
as a root. Its own guard caught it: 34 reference-type and 269 group indexes alone extrapolate to more
than the entire 1,970.4 MB residual, which is impossible. A table built on it would have looked entirely
plausible.

## Decisions taken

Seven optimizations, each above the 50 MB production threshold, each keeping the two constraints set for
the campaign: **no public API change and no transactional-layer semantic change.**

| Decision | Saving on the production catalog | How known |
|---|---:|---|
| Size the long- and element-keyed tree leaves to their content | **2,326 MB** | measured, two probes |
| Size the sort and chain index tree leaves to their content | family target **749.7 MB** | per-index measured, family modelled |
| Hold a single-price entity's price as scalars | **≈260 MiB** | measured per holder x census count |
| Allocate attribute-index families on first write | **≈218 MB** + 9.7 MB | inferred from a measured 64.4 % empty ratio |
| Stop duplicating every price index's ids in a memoized array | **133.7 MB** | measured, split 112.1 + 21.6 |
| Allocate the hierarchy node store on the first node written | **120.5 MB** | measured per index x census count |
| Keep small B+ tree buckets as sorted int arrays (#1455) | **≈95.9 MB** | measured histogram |

### Size the sort and chain index tree leaves to their content

`SortIndex.sortedRecords` is backed by two trees — the position tree (`UnorderedLookupTree.LeafNode`) and
the value index (`TransactionalIntToLongBPlusTree`) — whose leaves allocated full-block primitive arrays
and never trimmed them. `ChainIndex` shares the position tree at page size 1024. Both now grow and trim
through `ColumnSizing`, which was widened to public rather than copied.

**Invariant.** `isFull()` and `isNearlyFull()` on the value index read capacity off `values.length`, and
the class's own javadoc argued that this was deliberate *given* full-size arrays, warning exactly what a
retrofit breaks: a shorter array reaches full without tripping the guard. Content sizing inverts that
sentence, so both had to move onto a leaf-carried capacity **first**. Missed, every content-sized leaf
splits at four entries: the tree still answers correctly, every existing test still passes, and the
footprint gets *worse*. With the halves content-sized, the reverted guard now crashes the split instead
of silently degenerating, which is the safer of the two failures. The persisted chain leaf page copies
the live run out, never the backing array, so the format is untouched.

**Verification.** 3,079 tests green; 20/20 fuzzers. A one-record sort index 2,072 → 1,112 B, an empty one
1,656 → 856 B; a one-record chain index 6,136 → 1,336 B, an empty one 1,496 → 696 B. **Fourteen of the 23
new tests fail with the clamps neutered and the guard reverted.** The family-level 749.7 MB is the census
figure for what the trees hold today, not a re-measured after-figure; the production re-census is queued.

### Hold a single-price entity's price as scalars

Each of the 5,673,881 single-price holders carried a 24 B shell plus a one-element
`PriceRecordContract[]` and a one-element `int[]` — 72 B for one reference and one `int` — because the
abstract holder API is array-shaped. The record and its internal id are now scalar fields; the holder
weighs 24 B.

**Invariant.** The arrays are built **on demand**, only when an array-returning method is actually called,
so a caller that asks for them still gets them and pays for them. The query path stopped asking: the lazy
price-record iterator streams through `forEachLowestPriceRecordOfEntity`, which the super index implements
without allocating and which the interface default routes through the array form — so a reduced index
rejects it with the same error it rejected the array form with. Holders stay immutable and are replaced on
every add or remove, so the transactional map above them is untouched, and no holder reaches a storage
part: the super index persists its record array and rebuilds the map on load.

**Verification.** 1,309 tests green. Restoring the arrays fails two of the three new size tests,
`expected: <24> but was: <80>`. The quality pass added twelve more tests, renamed the positional accessor
that read like an id lookup across 21 sites, made two shapes hand out detached copies instead of aliasing
internal state, and deleted a diverged override — and it first had to fix the commit's own fixtures, which
could not fail (see *When a check cannot fail*).

### Allocate attribute-index families on first write

`AttributeIndex` allocated its seven sub-index maps at construction whatever it went on to hold, so an
empty index weighed 680 B and 64.4 % of the observable family slots on the production catalog were
allocated and empty — a 366 MiB floor for scaffolding that indexed nothing. Each family is now null until
the first write that needs it, published under a double-checked lock on a volatile field; the constructor
that rebuilds the index from committed maps leaves a family null when nothing committed into it. An empty
index weighs 80 B and an empty family 0 B.

**Invariants.** Three, and the first will look like a leak to someone who was not here:

- **A first write that rolls back leaves its map object behind**, empty, on the pre-commit instance — a
  write inside a transaction needs somewhere to put its diff. It is bounded by construction at 40 B per
  family touched (at most today's cost), the committed state is unchanged, and the commit merge builds a
  *fresh* index that materialises only families with committed content. Pinned by a test so nobody
  "fixes" it.
- **The fields must be `volatile`.** `TransactionalMap`'s own fields are final, but
  `PersistentTransactionalMap.state` is not — it is swapped by `seal()`/`thaw()` — so final-field
  semantics do not cover the publication and a racing reader could observe a map whose state is null.
- **Creation must be exclusive.** Two write sessions doing a first write to the same family would each
  build a map and one would win; the loser's diff layer would be keyed on an orphan while every later
  read found the winner, breaking read-your-writes silently.

The from-maps constructor's ordering constraint is unchanged: shared value and range indexes before the
filter index, filter before the unique views.

**Verification.** 17 new tests, ~6,100 existing green. **Twelve of the 17 fail when eager allocation is
restored.** The load-bearing check is the JOL cross-check — `EntityIndexHeapSizeTest` demands exact
equality between `getHeapSizeInBytes` and a walk of the real object graph for an empty index.

### Stop duplicating every price index's ids in a memoized array

Every price index kept an `int[]` copy of all its indexed price ids beside the bitmap built from the same
ids, and charged for both. `getIndexedPriceIds()` now returns the bitmap itself, which is also what its
javadoc always claimed and what its sibling accessor always did.

**Invariant, and the thing that reads backwards.** The memo is **lazily** built, so the census measured it
by calling the accessor and taking the delta — and read **zero** on reduced indexes. That zero is what a
*populated* memo looks like, not an absent one: 112.1 MB of arrays were already there, built during the
cold load. Super indexes are the other way round and build 21.6 MB on first call. Three shapes legitimately
never carry it — paged super indexes, any index rebuilt by a commit copy constructor, and any index mutated
outside a transaction — so **a warm-up-ingested catalog never had one at all** and this change is worth ~0
there. On a disk-loaded catalog it reclaims 112.1 MB immediately and prevents 21.6 MB from ever being
allocated.

**Verification.** 1,073 price functional tests plus 12 heap-size tests green. Counterfactual on a
512-id cold-loaded reduced index: `expected: <1504> but was: <3552>`.

**The changed method descriptor was raised at the gate and deliberately accepted.** The adversarial review
filed it as high: `getIndexedPriceIds()` moved from `int[]` to `Bitmap` on an interface in an exported
package, so the JVM descriptor changes and a consumer compiled against the old jar would hit
`NoSuchMethodError`. The fact is correct; the severity is a project call, and the call is that
`io.evitadb.index.price` is engine-internal. evitaDB's supported surface is `evita_api`, not the index
packages of `evita_engine`; the interface has no caller outside the engine and its own tests, and an
exported module is not by itself a compatibility contract. No deprecation cycle is owed. **If the index
contracts are ever declared an extension point, this is the change that would have needed one.**

### Allocate the hierarchy node store on the first node written

Every entity index carries a `HierarchyIndex`, and every one of them allocated four transactional node
structures at construction — for a catalog holding **647 hierarchy nodes in total**. The four now live in
one record behind a volatile field, created by the first node written and dropped again by the merge
constructor when everything commits empty. An empty hierarchy index weighs 56 B instead of 280 B.

**Invariants.** The five deferred `get…Formula` methods return the empty formula while no node was ever
written, because their memo key is built from ids that do not exist yet; the answer is identical and only
the plan shape differs, and only for an index that never held a node. `toString()` of an absent store
prints `Orphans: []`, byte-identical to what an allocated-but-empty hierarchy printed — the first attempt
returned the empty string and an existing test caught it. Persistence is unchanged.

**One holder here, seven separate fields for the attribute index — deliberately, not an oversight.** The
four hierarchy structures are populated together by a single `addNode`, so per-field laziness would
recover nothing and cost four null checks per read. The attribute families are populated independently,
which is exactly where the measured waste sits. A reader harmonising the two would make one of them worse.

**Verification.** 5,797 tests green. Six of the original 17 tests fail with eager allocation restored,
headed by `expected: <56> but was: <296>`; after the quality pass the counterfactual is **14 of 26**.

**Four medium defects had come to depend on whether the store was allocated yet, and all four are fixed.**
This is the risk laziness carries and the reason the pass over it was worth its time: behaviour that used
to be unconditional became conditional on allocation history. The absent-parent guard was skipped while the
store was unallocated, so an unknown node stopped failing the way it always had; a parent id not in the
index could be reported as a phantom parent; the all-nodes memo went stale after bootstrap; and the
heap-size test's hierarchy exclusions became unconditionally dead, so it had stopped checking what it was
written to check. Each is pinned by a test that goes red without the fix.

**One defect was deliberately not fixed.** The count-down-to method counts direct children twice, and an
existing test asserts the doubled value and spells the doubling out in a comment above the assertion. That
makes it a stated expectation rather than an accident: correcting it is a contract decision for the
method's owner and would take the pinning test with it. It is documented in the method's `@return` instead.

**The census row that motivated this was an artifact and must not be quoted again.** The decomposition
charged 86.1 MB of "hierarchy orphan bitmaps" because `getOrphanHierarchyNodes()` allocates a fresh bitmap
on every call — the census materialised 564,187 of them and then priced them. The retained cost of an empty
orphan list is 24 B. The real saving came from the four structures above it instead.

### Keep small B+ tree buckets as sorted int arrays (issue #1455)

A bucket promoted from its single bare `int` to a `TransactionalBitmap` at the second distinct record, so
every two-record bucket paid Roaring's fixed overhead. Buckets now pass through a middle tier: an immutable
sorted `int[]`, promoted to a bitmap above 128 records and demoted below 64 — the same pair `TrigramPostings`
uses, re-declared in the tree's own package because `bPlusTree` must not depend on `trigram`.

**Invariants.** Two, both of which have already caused a bug elsewhere:

- **A bucket's representation is not a function of its cardinality.** Hysteresis means a bucket sitting at
  the boundary keeps whatever it has, so every consumer must dispatch on what the slot *is* and never infer
  it from `size()`. `TrigramIndex` has a recorded `ClassCastException` from exactly that inference.
- **`Bitmap#getArray()` answers in signed order while the iterator is unsigned**, and the array view
  reproduces that split exactly rather than normalising it. This was found by the randomized churn oracle,
  not by reading the code.

No transactional type was added: every leaf mutation already decouples the overflow column into the
transaction's layer and the savepoint memento is a shallow copy of that array, so an immutable value
replaced on write inherits isolation from machinery that exists. The on-disk format is unchanged.

**Verification.** `BucketRecordTierTest`, grown from 28 to 49 methods plus two more test classes;
1,772 tests green twice. **Fifteen of the original 28 fail with the tier disabled**, headed by
`Unexpected type, expected: <int[]> but was: <TransactionalBitmap>`; the 13 that still pass are the
tier-agnostic ones, which is the expected split. T = 128 recovers ~95.9 MB against a 96.4 MB per-bucket
optimum and 5.7 MB more than the T = 32 the issue's own comment proposed.

#### The regression the tier introduced, and what closed it

**A new tier changes what a bucket *is*, and one consumer was reading that.** The trigram substring
formula keyed each verified bucket on `TransactionalBitmap#getId()` and skipped operands owning no
transactional identity, so every bucket that had moved into the array tier contributed nothing to the
formula's key. Three `TrigramSubstringSearchTest` cases failed, the first of them reporting `expected: <5>
but was: <0>` — the array tier's own premise, stated as an assertion. The implementer's suite selection had
not included the trigram package, and the quality pipeline's full-module run is what caught it. **Every
implementer now runs the whole functional module once before reporting, not a pattern-selected subset.**

`AbstractFormula#bitmapIdentityToken` now gives every operand a token: a producer's id, else the owner id
plus the memoized content hash, else the content hash alone. `SortedArrayBitmap` carries the owning leaf's
instance id so two indexes holding identical values stay apart, and both the constant and cacheable formula
bases route through the same token — key and staleness set cannot disagree. **Dropping the owner stamp
fails `shouldNotCollideAcrossIndexes`.**

**The hash had to become content-based across all three tiers.** The adversarial review showed that an
equal record set could hash differently depending on which tier held it: the array tier built a fresh
bitmap through a factory that removes run compression, while the bitmap tier hashed the transaction-merged
bitmap after `runOptimize()`, and the underlying bitmap's `hashCode` is documented as varying with
run-compression state while its `equals` is not. Cardinality 128 legitimately sits in either tier under the
hysteresis, and a contiguous run is exactly the shape run compression collapses. `recordSetHashCode` is now
**one** content-based default folding `31*h + id`, so equal sets hash equally in every tier and a
one-element set yields `31 + id` by construction; the three per-tier overrides are gone. Restoring the
Roaring-encoded hash fails five parity tests.

**`BucketCursor#recordArray()` is removed.** It handed out the leaf's own `int[]` from a public cursor in
an exported package, protected by javadoc alone. Its only consumer wrapped the array in a read-only
flyweight and never mutated it — the sharing was deliberate and preserved hash and equals parity — but the
exposure had no reason to exist once `materializeBucket` dispatches on the read-only view instead. The
leaf array no longer leaves the tree package.

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Columnar price record bodies | The 33.8 M figure counts reference *slots*: 5,673,881 distinct bodies are shared 5.96x, so bodies are 173.2 MB of a 2,242.4 MB row and every reduced index would receive its own copy of a columnar leaf. 1.5–2.5 kLOC to make the large half worse | Reduced indexes ever stop sharing instances, or a catalog shows fewer than ~1.6 references per body |
| Primitive key columns for the owner sort tree | Saving unquantified until distinct values are counted by type, and the failure mode is silent mis-ordering: the column factory refuses non-natural comparators because the codec's monotonic encoding must match tree order, and a `DESC` wrapper is order-*reversing* | The distinct numeric/temporal value count is known **and** the unwrap is direction-aware |
| Share sort-index value arrays between reduced and global indexes | Reference attributes have no global counterpart at all, the positional order is genuinely per-index, and the query path needs subset-local ranks | Taken up as a catalog-wide value dictionary alongside #1455's dictionary lever — never on its own |
| A shared immutable empty sentinel for absent attribute families | A `TransactionalMap` is identified in the transactional layer by its id, so one JVM-wide sentinel gives *every* attribute index in the catalog the same layer key. One write reaching it before the swap cross-contaminates every index, and nothing in the type system prevents it | The empty case ever gets its own type that cannot be written to at all |
| One lazy holder for the seven attribute families, or a lazy `EntityIndex.attributeIndex` | Both recover only the 16,104 wholly empty indexes (≈11 MB) and leave the per-family waste, which is where the measured 64.4 % sits. The lazy field additionally puts a null inside `EntityIndex`'s MVCC merge and its whole delegation surface | The distribution ever inverts to mostly all-empty or all-full indexes |
| A lazy `EntityIndex.hierarchyIndex` field | Worth 72 B/index more (≈39 MB), but `hierarchyIndex` is read from 72 places outside the entity-index classes and is registered as an `IndexComponent` swept by the parent's commit | The entity-index shell itself is ever attacked |
| Decompose the residual by subtracting reachable sub-indexes per index with JOL | Cannot separate an index's own unreachable state from shared attribute values it merely points at, because the owner is a *different* index and is unavailable as a root — 34 reference-type plus 269 group indexes alone extrapolate to more than the entire 1,970.4 MB residual | An accessor exposes the cardinality and histogram maps, or a whole-catalog walk with one global address set becomes affordable (needs far more than 24 GB) |
| Exactly-size the flush ordering list | ~15 MB, below the bar, and a refactor rather than a laziness change — the list holds 4-6 live components and is walked on every flush | The entity-index shell is attacked as a whole |
| Lazily create the usage activity holder | 30.1 MB is a ceiling, not an estimate: the holder is already absent when usage-statistics tracking is off, so laziness helps only indexes that are never queried or updated and nothing measures how many those are | The share of never-touched indexes is measured |
| Lazily create the `FacetIndex` store | Same shape as the hierarchy store, ≈41 MB — below the 50 MB production threshold set for this line of work | The bar drops, or its uncharged `dirtyIndexes` proves larger than the charged part |
| Drop the single-price holder's cached internal id | 16 B per holder, ≈45 MiB, below the bar | The holder is reopened for another reason |
| Keep `int[] getIndexedPriceIds()` and derive it on demand | Trades a permanent duplicate for a per-call allocation of the same size, under a method name that used to be free and now is not — nothing in the signature warns a future caller | Never |
| Treat the changed `getIndexedPriceIds()` descriptor as a compatibility break and keep an `int[]` form | `io.evitadb.index.price` is engine-internal: evitaDB's supported surface is `evita_api`, the interface has no caller outside the engine and its tests, and an exported module is not by itself a compatibility contract | The engine's index contracts are ever declared a third-party extension point |
| Fold the trigram index id into the formula's `getHash()` | Tried and reverted: the accelerated path carries the index id and the scan cannot, so folding it in breaks the invariant that both hash identically. The identity token is the seam that can carry it | Never — the token is the right place |
| Keep a per-tier `recordSetHashCode`, run-optimised on the bitmap side | The underlying bitmap's `hashCode` varies with run-compression state while its `equals` does not, so an equal set held in two tiers hashed differently — and the hysteresis leaves cardinality 128 legitimately in either tier | Never |
| Keep `BucketCursor#recordArray()` exposing the stored leaf array | A public cursor in an exported package handing out the leaf's own `int[]` with only javadoc protecting it; the sole consumer needs the read-only view, not the array | Never |
| Port content sizing to `TransactionalObjectBPlusTree` (traffic recording index) | Same defect, but it backs one diagnostic structure rather than an index every catalog carries, and nothing has measured it | The traffic recorder ever shows up in a heap reading |
| Trim on the commit merge's `return this` fast path | Identical to the row in the earlier record: every commit would rebuild every untouched leaf and dirty its page. Trimming happens only in the branches that were already constructing a node | Never |
| Decompose the probes' figure into "useful" and "wasted" bytes | Needs a model of the leaf's internals, and a model is the thing three retracted numbers in this campaign came from. Run the probe on two commits and subtract instead | Never |
| Charge the shared empty key array in `getHeapSizeInBytes` | It is a JVM-wide instance the leaf points at and does not own; the `ValueColumn` family already excludes it and the JOL cross-checks subtract it | Never |

## Key technical details

- **`TransactionalLongBPlusTree.BPlusLeafTreeNode`** gains `private final int capacity`. Everything
  asking "may one more key go in here" — `isFull()`, `isNearlyFull()`, the insert premise — reads it.
  Everything indexing an array reads that array's own length. The `isNearlyFull()` javadoc arguing the
  opposite is rewritten rather than left to contradict the code.
- **`ensurePhysicalLength` grows each array against its own length**, not against the other's. The two
  are equal in every state the class produces, but a guard reading only one would leave the other short
  if they ever diverged, and the write that follows indexes both. Call it on the object about to be
  written — the committed node outside a transaction, the layer inside one — and inside a transaction
  only after `decoupleTransactionalArrays()`.
- **The split's `end` for the right half must be the logical capacity.** It used to be the left half's
  array length, which was harmless only while every leaf array was exactly the block size. Once the
  halves are content-sized that expression collapses to `mid`, the right leaf copies the empty range
  `[mid, mid)`, and half the leaf disappears **with no exception and no failing assert**. The bucket
  tree's split already carries a comment about this exact trap; this is the same hazard in the sibling.
- **An empty leaf parks its keys on `ArrayUtils.EMPTY_LONG_ARRAY` and is not charged for it**, and no
  path clones a zero-length array into a private one — that would cost a header and break the identity
  every heap walk subtracts. The value array has no JVM-wide shared empty of an arbitrary component
  type, so it is privately owned at length zero and charged.
- **Trimming happens only where a node was already being built** (`trimmedCommittedCopy`), never on the
  merge's `return this` path.
- **`ColumnSizing` is now public** rather than copied into a second package, because the sort and chain
  trees needed the same policy from `io.evitadb.index.array`.

## The reader bound content sizing made necessary

**Content sizing changed what a torn read costs.** While every leaf array was allocated at the full
block size and never replaced, an unsynchronized reader pairing a freshly-read `peek` with the array
that preceded a growth landed *inside* that array and returned a stale element — the documented,
accepted staleness. A content-sized array can be shorter than the peek, so the same read now runs off
the end. **This was missed at review and caught by an adversarial review after the long-tree change was
already committed.**

The premise it removed was recorded in `2026-09-03-content-sized-value-tree-columns`, which built the
`observableLiveRun` / `observableLeafPeek` guard family for the bucket tree. The long tree was never in
that sweep, correctly, because at fixed block size it did not need one. Nothing re-read that reasoning
when the fixed block size went away — which is the process lesson, and it is the same shape as the
sweep gap that record already describes. The sort and chain trees were bounded the same way as part of
their own change, before rather than after.

Reachability is asserted by the code itself rather than inferred: `EntityCollection#describeIndex`
states that **no snapshot is taken** and hands a live index to `IndexDetailProjection`, which walks it
for its heap size from a request thread while a warm-up bulk load may be mutating it. For the element
tree the exposed reader is different and worse — a query thread resolving prices, since the
transactional layer is resolved through a `ThreadLocal` that a query thread does not share with a
warm-up writer.

**The sweep missed one, and the adversarial gate found it.** Both keyed iterator constructors of the long
tree searched `getKeys()` over a range taken from a separately resolved `size()`. Once leaf arrays are
content-sized, a size read from a fresh peek paired with an older, shorter array makes the binary search
fail its own range check before comparing a single key — the same torn read the other five readers were
bounded against. It was missed because it sits in the *iterator constructors* rather than in the leaf, and
six iterator entry points funnel through the two of them; `RangeIndex` and the price index reach them from
production paths. The leaf now offers a guarded `findKeyPosition(long)` beside its value accessors,
resolving keys, values and peek from one layer read and bounding the search by the observable peek; the
element tree already carried a method of that name for the same reason. The bucket tree's columns bound
their own search, and the object tree is not content-sized, so its torn read stays benign. **With both
constructors reverted the four new tests fail with `ArrayIndexOutOfBoundsException: Array index out of
range: 5`, and the four pre-existing torn-read tests still pass** — so none of the new ones is proven by
another throwing first.

A seventh reader turned up in the same family during the quality pass: the leaf's verbose rendering read an
unclamped peek that the sibling element tree already guarded. Same fix, own counterfactual test.

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Publish `(array, peek)` together through one immutable volatile state holder | The correct fix in the abstract, and what a green-field design would do. It changes the layout and the write path of every leaf in three trees to close a hazard whose observable form is a *stale read* that these callers already accept; the clamp closes the crash with no write-path cost at all | The leaf ever needs a genuinely consistent multi-field read, rather than merely an in-bounds one |
| Make the fields `volatile` | Costs every writer a fence on the hottest path in the index to fix a reader that is documented to tolerate staleness, and still would not make the *pair* atomic | Never |
| Apply the clamp to the `peek >= values.length` corruption guard as well | It is not asking a capacity question. It is what turns a torn pair into a typed `BPlusTreeCorruptedException` naming the leaf and slot; clamping it would let a genuinely corrupt leaf through to a bare `ArrayIndexOutOfBoundsException`. `peek < values.length` also remains a true invariant, since `ensurePhysicalLength` grows before any path advances `peek`, so the check doubles as a self-test of the new invariant | Never |
| Guard `LeafPageHandleImpl` in the shared base class too | It has the identical two-read shape, but the path is thread-confined to the writer and a clamp there would imply a hazard that does not exist. See below | Page emission ever moves off the writing thread |

**`LeafPageHandleImpl` is the interesting non-fix.** It captures a leaf's array and occupancy as two
independent reads and `collectChangedPages` then walks `size()` indexing `valueAt(int)` — exactly the
crashing shape. It cannot tear, and the proof is an observable consequence rather than an assertion:
both reads resolve through a `ThreadLocal` transaction binding, so a flush on any other thread would
resolve every leaf to its committed instance, read `dirty == false` on each leaf that transaction
touched, and **emit no pages at all**. The paging tests passing is therefore itself the evidence that
emission runs on the writing transaction's thread. That also explains why the earlier sweep left the
identical code alone for the already content-sized bucket tree.

**The hazard worth naming is that this holds by consequence, not by an asserted invariant.** Moving
page emission to a background thread would not fail by crashing — it would emit zero pages long before
it could index out of bounds, so the first symptom would be silently lost writes. That reasoning now
sits in a comment at the site, which is where it is useful; anything moving that work off the writer's
thread must give the leaf a combined publish first.

## Verification

### Resident heap, three builds, one catalog

Three runs of the same measurement script on the same machine, JVM and flags, against the same pristine
catalog-only copy of the same production e-commerce catalog. Only the build differs. Used heap is read
after two full GCs through the observability API, with the cache disabled and compression off; OpenJDK
21.0.12, `-Xmx24g -XX:+UseG1GC`.

| build | commit | used heap after two full GCs |
|---|---|---:|
| release 2026.2.6 | `77777d309` | 11,683.2 MB |
| branch base on dev | `ee2801c8e` | 11,655.7 MB |
| branch, tree content sizing only | `49069da2a` | **7,416.2 MB** |

| segment | saved | share of its starting point |
|---|---:|---:|
| release to branch base — everything merged on dev | 27.5 MB | 0.24 % |
| **branch base to head — this branch** | **4,239.5 MB** | **36.4 %** |
| release to head — combined | 4,267.1 MB | 36.5 % |

**Effectively all of the saving belongs to the branch**, which is what the third run was for: dev moved
the resident heap by a quarter of one percent, and every large class in the breakdown is byte-identical
between the release and the branch base. By class, long arrays fall 1,851 MB and int arrays 856 MB —
what sizing B+ tree leaf arrays to their content looks like — with range-point, comparable and
transactional-bitmap object arrays giving back a further 1,324 MB. The head is larger in exactly the
places the mechanism predicts: B+ tree node and column types grow because there are more of them once
the arrays they hold are sized to content, under 90 MB against 4,240 MB saved.

**This figure covers the tree content sizing alone.** The six later optimizations landed after it was
taken and are not in it.

**Final resident heap, all six follow-up optimizations integrated** (head `79e74e121`, same script, same
JVM, same pristine catalog-only copy, two full collections before reading):

| build | commit | used heap | against release |
|---|---|---:|---:|
| release 2026.2.6 | `77777d309` | 11,683.2 MB | — |
| branch base on dev | `ee2801c8e` | 11,655.7 MB | −27.5 MB |
| head before follow-ups | `49069da2a` | 7,416.2 MB | −4,267.1 MB |
| **final** | **`79e74e121`** | **6,154.4 MB** | **−5,528.8 MB (47.3 %)** |

The branch's own contribution is **5,501.3 MB, 47.2 % of its base**; the six follow-ups taken after the
production calibration are worth **1,261.8 MB** of it, 17 % of what remained after the tree content sizing.
By class against the release: long arrays 2,442.0 → 422.6 MB, int arrays 1,591.9 → 237.6 MB, transactional
bitmap object arrays to zero. The full suite on that head: engine 17,915 tests, functional 22,953, zero
failures; the one error is `ExportS3ServiceTest`, which needs a Docker daemon the measuring host lacks.

**Ingestion regression check** — a full WARM_UP reindex of the same production catalog (432,764 entities,
18 collections, 119,447 of them products) through the gRPC driver, three target servers in turn — release
2026.2.6, the branch base on dev, this branch — with the same branch-built driver, async-profiler cpu attached
to the writer, no Maven between the runs:

| | release | branch base | this branch |
|---|---:|---:|---:|
| load wall-clock | 1,298.4 s | 987.6 s | 991.1 s |
| goLive | 30.2 s | 30.0 s | 29.2 s |
| product load | 1,212.0 s | 900.9 s | 900.9 s |
| product mean upsert | 9,865.9 µs | 7,320.9 µs | 7,316.5 µs |

**This branch is ingestion-neutral.** The 23.9 % saving between the release and the branch base belongs to the
43 dev commits in between (the schema lookups on the reference-mutation path and `PriceRefIndex.addPrice` are
already cheap at the base: 3,993 → 91 → 89, 3,651 → 0 → 0, 1,702 → 36 → 26 samples); the branch then adds 3.5 s,
0.4 %, far inside the harness's ~9 % single-run resolution, with product load identical to the tenth of a
second and 2.7 % fewer cpu samples overall. The one branch-specific movement is the `AttributeMutationFanOut`
subtree, 8,976 → 11,588 samples against the base, a string-classification cost (`Wtf8.classify`) from the
branch's earlier commits that is outweighed elsewhere and worth a look on its own. All three runs exited
cleanly with per-collection counts verified and no full GC on the writer. Two earlier readings of this
comparison — that the speed-up was the branch's, and that the memo removal showed in the profile — were wrong
and are withdrawn; the third run is what made the attribution honest. Caveat: one run per build.

### The tree content sizing

- **Measured on the production catalog**, same probes, each build compiled from its own source,
  `-Xmx24g` so compressed oops stay on:

  | | before | after | saved |
  |---|---|---|---|
  | range indexes (92,229, holding 376,495 points) | 703.8 MB | **167.6 MB** | 536.2 MB (76.2 %) |
  | price indexes (283,275, holding 33,806,439 records) | 4,032.4 MB | **2,242.4 MB** | 1,790.0 MB (44.4 %) |
  | **total** | **4,736 MB** | **2,410 MB** | **2,326 MB (49.1 %)** |

  Isolated by measuring the intermediate commit as well: of the price-index saving, **1,646.9 MB** is
  the long-keyed port and **143.1 MB** the element-keyed one (125 -> 73 -> 69 B per record).
  For scale, the array container this campaign declined was projected at +1.55 GB on an e-commerce
  corpus; what shipped is worth half again as much and is already gated.
- **Two independent checks say this is the same mechanism at a different scale, not an artifact.**
  Per-point cost moves 1,960 -> 466 B here against 1,976 -> 452 B on the demo; and the price probe's
  identity dedup reports `283,275 of 283,275 (1.0x)`, so no instance shared between a super index and
  its 283,002 reference indexes was counted twice.
- **The reader bound is proven by counterfactual, one test per guarded reader.** Sharing a single test
  across readers would let the first one to throw stand in for the rest, so each has its own:
  `TransactionalLongBPlusTreeTest.TornLeafReaderBoundTest` (4) and
  `TransactionalElementBPlusTreeTest.TornLeafReaderBoundTest` (6). With the clamp removed **all ten
  fail** with `ArrayIndexOutOfBoundsException`; with it in place the long tree passes 1,030 tests and
  the element tree 2,044.
- **The element port is worth 1.7 MB on the demo dataset**, same probe, same catalog copy, engine built
  from each commit's own source: price indexes **31.4 MB -> 29.7 MB** across 4,292 indexes holding
  435,580 records, 75 B -> 71 B per record. Against 143 MB on production.
- **No measurable read-path cost from the guard.** `PriceRecordBackingBenchmark` on both builds: 17 of
  18 measurements inside their error bars, including every `array*` control — which is what says the
  box behaved rather than that the code did. The exception, `getByIdTree`, reported -31 % throughput
  **and** -17 % average time for the same operation; a 5-fork re-run resolved it as noise
  (0.0545 +/- 0.0067 -> 0.0491 +/- 0.0063 us/op, control +1.0 %). **The `@Fork(1)` error bars understate
  cross-JVM variance by an order of magnitude on a ~50 ns operation** — a single-fork JMH delta is not
  evidence, and the contradiction between two modes of the same benchmark is the tell.
- **Two write-path costs remain unmeasured, and no run here covers them.** `PriceRecordBackingBenchmark`
  mutates outside a transaction, so `trimmedCommittedCopy` on the commit-merge is never reached; and
  the ~4 reallocation-and-copy steps a leaf now performs while filling (4-8-16-32-64) land in JMH's
  `@Setup`, which is not timed. Both are judged unlikely to matter — the demo catalog builds its price
  indexes in about a second — but that is a judgement and is recorded as one.
- **Full functional gate: 22,668 tests, 0 failures.** The single error is `ExportS3ServiceTest`, "Could
  not find a valid Docker environment" — environmental. 4,538 tests under `io.evitadb.index.**`, which
  include the byte-exact JOL heap-accounting cross-checks, pass on their own as well.
- **Both hazards are covered by existing tests, proven by counterfactual** rather than by assumption:
  disabling `ensurePhysicalLength` raises **242 `ArrayIndexOutOfBoundsException`s**; binding the split's
  `end` back to an array length fails **23 tests across five suites**.
- **The heap-accounting error was caught by a test, not by review.**
  `TrigramIndexTest.shouldPriceAnEmptyIndexExactly` reported 304 bytes against a JOL-measured 288 — the
  16 bytes of the shared empty array.
- **Measured on the demo dataset**, same probe and catalog copy on both sides:

  | | before | after |
  |---|---|---|
  | range indexes (4,219, holding 16,876 points) | 31.8 MB | **7.3 MB** |
  | price indexes (4,292, holding 435,580 records) | 56.4 MB | **31.4 MB** |
  | **total** | **88.2 MB** | **38.7 MB** |

  **≈49.5 MB, over two disjoint sets of objects.** Per range index, 7,906 B → 1,810 B: the 6,096 B saved
  is exactly `long[512]` + `Object[512]` less their four-slot replacements, so mechanism and measurement
  agree with no fitted constant between them.
- **The price half was invisible to the first probe and nearly went unreported.** Every
  `PriceListAndCurrencyPriceIndex` owns a validity `RangeIndex` that no filter index points at, so a
  probe walking filter indexes sees none of it. The first figure for this change was 24.5 MB — less than
  half of what it does.

### The six later optimizations

Test counts and counterfactuals are quoted per decision in *Decisions taken* above. Summarised: 17 + 6 + 23
+ 28 + 3 + 2 new tests before the gates, and **every one of the six has a counterfactual in which the guard
or the laziness is neutered and named tests fail** — 12/17, 6/17 (14/26 after its quality pass), 14/23,
15/28, 2/3, 2/2. This campaign has already shipped five tests that passed for the wrong reason, three of
them unreachable by construction, which is why no change here is reported as verified without one.

### The gates

Six quality-pipeline batches over the whole line of work, plus an adversarial review of the assembled
integration line. Each batch runs four agents — tests, bugs, simplification, javadoc — and finishes on a
full module suite it did not select.

| Batch | Range | Suite | Defects fixed | Notes |
|---|---|---|---|---|
| 1 | `59763c871..b12af5d63`, 25 commits, 89 files | engine 17,915 · functional 22,728, 0 failures | 2 red to green, 1 low deferred | Legacy range bucket order inverted on reload; a half-saturated long range rebuilt with a null bound. The deferred one is a dead sentinel branch — no input makes it fail, so no red step was manufactured |
| 2 | `b12af5d63..65cfebda3`, 17 commits | engine 17,915 · touched classes 384, 0 failures | 2 red to green | The integrated head's test tree did not compile (integration break #2); an unclamped peek in the leaf's verbose rendering. Functional run showed the tier regression, proven pre-existing here and fixed in the next commit |
| 3 | bucket tier, `18f1c5454..HEAD` | 4,654 selected · 3 failures | 4 fixed, 2 of them red to green | Reported FAILED on the trigram regression, correctly: its own success criterion is a green suite. Tier tests 28 → 49 |
| 4 | sort and chain trees, `993c6a098..HEAD` | engine 17,915 · functional 22,724 | 10 findings closed, 6 red to green | Eleven reader guards re-proven by mutating each guard, watching its test fail and restoring the source byte-for-byte; +422 lines of tests |
| 5 | single-price holder, `993c6a098..HEAD` | engine 17,915 · functional 22,707, 0 failures | 5 findings, 12 tests added | Found the commit's own fixtures vacuous |
| 6 | hierarchy node store, `7a2eedf3b..HEAD` | functional 22,724 | 4 medium red to green, 1 deliberately unfixed | Counterfactual strengthened from 6/17 to 14/26 |

**Environmental failures, named so they are not mistaken for defects.** `ExportS3ServiceTest` errors in
every batch with "Could not find a valid Docker environment" — this host runs no Docker daemon. Two
wall-clock assertions failed once each under the load of four concurrent pipelines and passed in isolation:
a change-data-capture ring-buffer concurrency test (green three times) and a gRPC timeout budget test
comparing 1,865 ms against 1,883 ms (green five times).

**The adversarial gate completed, in two sittings.** Five reviews were commissioned; a host sandbox
restriction and an account usage limit stopped the lane after two, and the remaining three ran once the
limit reset. The two that ran first each produced a confirmed finding fixed the same night (the sixth
torn reader; the cross-tier hash and the cursor's array exposure). Of the three that ran later, the sort
and chain trees produced one medium finding — split-created position-tree containers stayed capacity-sized,
confirmed for incremental splits and refuted for the cold-load path every measurement took, fixed as
`bed2f3ba4` — and the single-price holder and the hierarchy node store were approved without findings.

## Consequences & open follow-ups

- **The earlier record's sibling-tree bullet is refined, not reversed.** Its decision — leaving the
  boxed bucket count in the four sibling families — stands, and for the reason it gives. What does not
  generalise from it is the per-tree cost: a bucket count is ~56 B per tree, a pair of full-block leaf
  arrays is ~6 KB. That record now points here.
- **The queue that remains, with production numbers.** These were measured or modelled tonight and not
  taken, and each names its blocker:

  | item | size | why not tonight |
  |---|---:|---|
  | Reduced price index validity `RangeIndex` | ≈685 MB | Structural: it would have to be shared with the super index rather than owned per reduced index |
  | Reduced price index `indexedPriceIds` bitmap | ≈247 MB | Structural: derivable from the record tree instead of stored, which changes what the index is |
  | Per-language entity-id bitmaps | 1,128,306 of them, ~180–290 MB | No accessor hands out the bitmap — only a `Formula` wrapping it — so nothing has priced them exactly |
  | Price super index boxed map keys, plus the tree nodes above them | 86.6 MB + unmeasured | Inferred from the implementation's arithmetic, not measured; the tree spine has no seam |
  | Attribute chain index decomposition | 297.1 MB family, 179.1 MB in one collection at ~6.2 KB/index | None of the four structures it charges is reachable from the public surface; needs an accessor before any lever can be chosen |
  | `FacetIndex.dirtyIndexes` is uncharged by `getHeapSizeInBytes` | unknown | An accounting bug rather than a footprint item: the facet index owns that set, and `EntityIndexHeapSizeTest` excludes it as a borrowed root |

- **Three probes are tracked evidence, deliberately.** `RangeIndexFootprintProbe`,
  `PriceIndexFootprintProbe`, `AttributeIndexScaffoldingProbe`, `CatalogIndexFootprintCensus` and
  `EntityIndexResidualJolProbe` are force-added past the `spike/trigram/` ignore rule, on the carve-out
  that rule states for a spike that becomes evidence an issue depends on. An untracked earlier copy of the
  first one was lost with the worktree that held it, silently, because the ignore rule keeps such files
  out of `git status` entirely.
- **Any probe that prints attribute names must keep its redaction.** The production schema carries at
  least one attribute whose name embeds a customer brand; the census redacts it at run time and one row
  of its output was renamed to a generic equivalent before being quoted anywhere.
- **The census's hierarchy orphan-bitmap row is wrong and should be fixed before it is quoted again.**
  It charges a fresh allocation the accessor makes per call, and the header calls the row "exact".
- **A release build opened against a dev-managed catalog directory deletes the `.catalogname` marker.**
  The catch-all obsolete-file purge in the storage service predates that marker, so downgrading is
  destructive. Found while setting up the release baseline, out of scope for this work, and not fixed.
- **Every per-module Maven build in this tree must carry `-am`.** This box has two local repositories that
  disagree; a `-pl` build without it resolves a stale engine jar and reports the error backwards — a
  compile failure naming an updated *mock* as the incompatible side. The same trap cost a false "integration
  break" tonight.
- **After a cherry-pick, the check is `mvn -o test-compile -P full -DskipTests`, never `compile`.** Two real
  integration breaks got through on this line, and both had the same shape: a change authored in a worktree
  forked *before* another change on the integration line, asserting against a signature that had moved
  underneath it. One was a probe still calling `.length` on what had become a `Bitmap`; the other was a
  quality pass asserting `assertArrayEquals` against the same accessor. Neither sat inside a changed hunk,
  and `compile` cannot see either, because both live in test sources. `-P full` matters too: the performance
  module is outside the default reactor and outside every agent's suite.
- **All five adversarial reviews ran, two of them only after an interruption.** A host sandbox restriction
  (`kernel.apparmor_restrict_unprivileged_userns=1`; the local proxy wrapper that used to lift it was dropped by
  a Codex update) and an account usage limit stopped the lane at two jobs; the remaining three ran after the
  limit reset. Outcomes: the integration line and the bucket tier each produced confirmed findings, fixed the
  same night; the sort trees produced one medium finding, split-created position-tree containers staying
  capacity-sized, confirmed for incremental splits only and fixed; the single-price holder and the hierarchy
  store were approved without findings.
- **Run the functional gate at `-Dsurefire.maxHeapSize=24g` on this suite.** At the default 8 GB the
  fork dies of `OutOfMemoryError`, truncating the run and manufacturing derived failures — including a
  writer thread reported as "hung!" in a *leaf-page split* harness, which is the most misleading possible
  false positive for this change. At 32 GB the VM turns compressed oops off and every absolute-size
  assertion in the suite fails; `MemoryMeasuringConstantsTest.shouldRunUnderCompressedLayout` says so in
  its own failure message.
- **A concurrent sweep cannot prove the reader bound necessary on x86, and never will.** The bound is
  pinned by ten deterministic tests instead. This is the same calibration the bucket tree's
  `observableLeafPeek` javadoc carries, repeated here because the temptation to simplify the bound away
  is what both javadocs exist to resist.
- **`TransactionalObjectBPlusTree` remains unported**, with its trigger in the *Rejected outright*
  table. It backs traffic recording — one diagnostic structure rather than an index every catalog
  carries — and nothing has measured it. Dormant rather than open.
- **The element port's remaining weight is the price record bodies, and this record closes that door.**
  The bodies are 173.2 MB across 5,673,881 objects, not the ~15-17 MB the demo suggested, and they are
  shared 5.96x — which is exactly why the columnar layout that would remove their headers is rejected
  above rather than queued. Field-level compression cannot touch them either: object layout aligns to
  8 bytes, so packing two `int`s into an `int` and a `short` leaves a 32-byte object at 32 bytes, and
  `priceWithTax` cannot be derived from `priceWithoutTax` because per-currency rounding is authoritative.

## Related work

- `2026-09-03-content-sized-value-tree-columns` — the parent decision. This record applies its policy to
  the long-keyed sibling and to the sort, chain and price trees, and refines its sibling-tree consequence.
- `2026-08-01-bplustree-cursor-free-insert-path` — the split and rebalance paths this change had to grow
  arrays inside are the ones that campaign made allocation-free; the growth calls sit on the rebalance
  side, not on the descent.
- `2026-09-04-millisecond-temporal-precision` — the sibling record from the same branch; its epoch-milli
  range keys are the payload the trees measured here carry.
- `2026-07-10-more-optimized-data-structures` — introduced the boundary-stable leaf paging that the
  "never trim on the `return this` path" rule protects.
- `2026-08-31-trigram-query-path-optimization` — issue #1455's bucket tier reuses its
  `SMALL_POSTING_THRESHOLD` / demotion pair and inherits its "representation is not a function of
  cardinality" hazard, including the `ClassCastException` that hazard already caused there.
- `2026-08-16-per-index-usage-statistics` — recorded the per-index usage holder as reclaimable footprint;
  this campaign put a number on it (30.1 MB) and declined to make it lazy.

## Timeline

- **2026-09-03** — content sizing shipped for the bucket-keyed tree; sibling trees explicitly left alone.
- **2026-09-04 15:10** — the array container is declined; the range tree's leaf occupancy is identified
  as the larger remaining prize.
- **2026-09-04 17:35** — implemented; 4,538 index tests pass, both hazards proven by counterfactual.
- **2026-09-04 18:05** — full functional gate green at 24 GB after two heap-related false alarms.
- **2026-09-04 18:20** — price index probe finds a further 25.0 MB and declines the element-tree port.
- **2026-09-04 18:58** — an adversarial review of the committed long-tree change finds the torn read
  that content sizing made fatal; the long tree's reader bound lands with four counterfactual tests.
- **2026-09-04 19:47** — the element port is built anyway to settle its own reopening trigger by
  measurement: 1.7 MB. Kept on a cost/benefit judgement, with its own reader bound and six tests.
- **2026-09-04 20:07** — `PriceRecordBackingBenchmark` on both builds shows no read-path cost; a
  single-fork -31 % outlier is resolved as noise by a 5-fork re-run.
- **2026-09-04 21:07** — the threshold for further work is set at 50 MB *on the production catalog*, with
  no public API change and no transactional-layer semantic change.
- **2026-09-04 21:42** — the whole-catalog census lands and re-ranks the queue; columnar price bodies are
  declined on the 5.96x sharing it measures, and issue #1455 is sized at +90–96 MB.
- **2026-09-04 21:50** — release 2026.2.6 resident heap measured at 11,683.2 MB.
- **2026-09-04 22:05–23:10** — six optimizations implemented and committed, each with counterfactuals:
  attribute-index families, the price-id memo, the sort and chain trees, #1455's bucket tier, the
  single-price holder, the hierarchy node store.
- **2026-09-04 22:32** — resident heap at the tree-sizing head: 7,416.2 MB.
- **2026-09-04 22:45** — the dev branch base is measured at 11,655.7 MB, isolating the saving to this
  branch: dev contributed 27.5 MB of it.
- **2026-09-04 23:12** — the adversarial review of the assembled line confirms the sixth torn reader in the
  keyed iterator constructors, and refutes the changed price-id descriptor as a blocker.
- **2026-09-05 02:12** — the quality pipeline's full-module run catches the bucket tier's trigram
  regression, which the implementer's selected suite had not covered.
- **2026-09-05 02:45** — six optimization commits cherry-picked onto the integration line; a second
  cross-branch integration break is found by a `test-compile`, not by a `compile`.
- **2026-09-05 04:15** — all six quality-pipeline batches and their fixes integrated at head `79e74e121`.
