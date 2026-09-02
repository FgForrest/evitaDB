# P4 — proximity re-rank: prototype implementation plan

> **Status: an implementation plan, not a decision.** It follows on from the research
> [`../research.md`](../research.md) (v2, consolidated 2026-08-04, last revised 2026-08-12), namely §4.3,
> §4.7, §7 (prototype P4), §8 (VK2, VK3) and open question O2. It builds over the structures and the
> phase 1 scorer proposed by [`p1-index-core.md`](p1-index-core.md), and over the tokenization contract
> from [`p5-analyzers.md`](p5-analyzers.md).
>
> Written on 2026-08-12. The anchors into evitaDB's code were verified against the `dev` branch on the
> same day, the anchors into Typesense against `/www/oss/typesense` (v31, `ee7784f3`), the anchors into
> Elasticsearch against `/www/oss/elasticsearch` (branch `main`, `9a100e2d0e41`, verified 2026-08-13) and
> the anchors into OpenSearch against `/www/oss/OpenSearch` (branch `main`, `36edc05ac84`, verified
> 2026-08-12). Claims about the existing solution are taken from the internal analysis of the Edee
> CMS client, which is not published in this repository. Translated from Czech and moved into this
> record on 2026-08-24.

---

## 1. Goal, scope and criteria

The research (§4.7) rests proximity on a single claim: positions are not indexed, because that is the most
expensive part of the Meilisearch experience, and proximity is instead computed in phase 2 by re-analyzing
the stored values of the top-K candidates. The criterion (§7) reads: re-analysis of the top-1000 within
10 ms, measured **separately on short fields and on long texts**, with failure on the long ones activating
the positional seam.

On reading the sources, however, it turned out that P4 has **two** tasks, and the second is more important
than the assigned one. Beside the question "how much does the re-analysis cost" stands the question "**is
proximity in the cascade in the place the design put it**" — and the answer to it decides whether a top-K
re-rank is a correct procedure at all. §3 is devoted to that question and it is the backbone of the whole
document.

### 1.1 The criterion and its three components

The number "10 ms per top-1000" measures three different things under one name and can be planned with
only after a breakdown:

| Component | What it is | Scales with |
|---|---|---|
| **Loading** | K reads from the `OffsetIndex` and their Kryo deserialization | K, the container's size |
| **Tokenization** | a pass through the analyzer, producing terms and offsets | the text's length |
| **Computation** | a window over the positions of the matched tokens | the number of tokens and their occurrences |

The expected order of importance is exactly this. The computation itself (§6) is a few hundred instructions
per document and it will not be what overruns the budget. Loading is by contrast K independent seeks to
disk plus K deserializations of whole containers (§4.4), and for the CMS profile tokenization is a
different cost class too — a thousand-token article against a five-token product name.

**They therefore have to be reported separately**, otherwise from a single number one cannot deduce which
measure from §5 makes sense.

### 1.2 Two numbers in the research contradict each other — and P4 is to adjudicate which holds

The research gives the cost of the same operation in two places and the numbers do not agree:

- §4.6 on highlighting: re-analysis of the stored values of **the returned page (20–50 entities)** costs
  "sub-ms". That is at least 20 to 50 entities per millisecond.
- §4.7 on proximity: re-analysis of **the top-1000** has a goal of ≤ 10 ms. That is 100 entities per
  millisecond, i.e. two to five times better unit throughput.

The difference is in fact even bigger than it looks, and to §4.7's disadvantage: the entities highlighting
re-analyzes are **already loaded** — it is exactly the page being rendered — so the "loading" component is
zero for them. The proximity top-K, by contrast, is not loaded and loading is the dominant item for it.

**The conclusion for the plan: the goal of 10 ms is a hypothesis P4 tests, not a goal whose attainability
is assumed.** For the CMS profile in particular it is more likely that it will not be met — which is exactly
the result the research labels the trigger of the positional seam (§7 below). A failure is therefore not a
failure of the prototype, it is its legitimate output.

And one more caveat about that number, which §3.6 develops: "top-1000 within 10 ms" tacitly assumes a
phrase is a **ranking signal**. If it were to be a filter, the top-1000 measures something other than what
matters — the budget is then computed from the size of the candidate set, not from K.

### 1.3 What P4 deliberately does not do

- **It does not build a positional index.** §4.7 of the research holds it as a seam activated only per
  measurement. P4 measures and proposes its shape (§7), but does not build it.
- **It does not address highlighting.** It shares the mechanics with it (re-analysis of stored values with
  offsets), but not the problem — highlighting works over an already loaded page.
- **It does no LTR nor behavioural re-rank.** Phase 2 is per §4.3 of the research to be generally pluggable
  and proximity is only its first inhabitant. P4 will design the interface so that a further inhabitant
  fits, but delivers no further one.
- **It does not decide the rank profile.** It does, however, recommend a change to the order of lanes (§3)
  and that is input for O1 and for P7.

---

## 2. Links to the research and to the neighbouring prototypes

**What P4 adopts as its brief.** Positions are not indexed (§4.7); proximity is computed in phase 2 over
the top-K by phase 1; lane 5 of the composite is reserved for it and has 16 bits (§4.3); K is proposed as
1000 and it is precisely P4 that is to decide it (O2); short fields and long texts are measured separately
(Z8).

**What P4 inherits from P1.** The phase 1 scorer and its output — feature vectors aligned to the candidate
set (`p1-index-core.md`, §5.3) —, the top-N selection with a heap over an array of composites (same place,
step 5) and the seam for the sorter through an interface analogous to `FilteredPriceRecordAccessor` (same
place, §3.6 and §5.4). Phase 2 is a step inserted between the top-K selection and the final ordering.

**What P4 inherits from P5.** The tokenization contract emitting records
`(term, startOffset, endOffset, positionIncrement)` (`p5-analyzers.md`, §4.2). P4 is the first real
consumer of the offsets and positions — P1 takes only the terms from the contract. It is worth recording
that P5 designed that contract with offsets precisely because of highlighting and P4 benefits from it
without anything having to be changed.

**One thing P4 deliberately does not re-tell.** The research points at `FilteredPricesSorter` as a precedent
for a sort by values computed during query evaluation. P1 already corrected that anchor (§3.6): the
transferable pattern is one floor down, it is the interface by which the formula tree hands the sorter
values, and `FilteredPricesSorter` itself reads no stored values. P4 adopts that correction and does not
repeat it.

**Where the bar is today.** The analysis of the Edee CMS client
(internal, §4.10 and §6.5) brings a finding worth recording precisely
because it is negative. The existing solution's index carries **positions as well as term vectors on all
text fields**, but phrase and proximity queries are **never** built over it — a grep for `PhraseQuery` and
`SpanQuery` in the Java code of all fourteen modules returns nothing. The full positional tax is therefore
paid without any benefit from it. The same holds for highlighting matches: highlighting does not exist in
the client (no `Highlighter`, `QueryScorer` nor `SimpleHTMLFormatter`) and a stored perex is displayed, not
a snippet with the match highlighted. Two things follow for P4. The bar is set low — anything P4 delivers
is an increment against today and no regression is threatened. And second, the existing solution has the
substrate for highlighting to hand and would catch up without reindexing, whereas our design deliberately
does not index positions; should highlighting over long CMS texts turn out to be a requirement, it is the
same re-analysis of stored values P4 measures, and not a separate structure.

---

## 3. The real fork: where in the cascade proximity lies

### 3.1 The design places proximity elsewhere than all three reference engines

The lane table in §4.3 of the research orders the criteria thus: (1) the number of matched words, (2)
typos, (3) impact, i.e. `sat(tf) × norm` with the field's weight, (4) exactness, (5) **proximity**, (6)
contextual rank. Proximity therefore lies **below** both the field criterion and exactness.

The reference engines do it differently:

| Engine | The cascade in order from the strongest criterion |
|---|---|
| **Algolia** | typo, geo, words, filters, **proximity**, attribute, exact |
| **Meilisearch** | words, typo, **proximity**, attributeRank, sort, wordPosition, exactness |
| **Typesense** ¹ | words, unique, typo, **distance**, exact, offset, synonym |
| *design B′* | words, typo, *impact (field)*, *exactness*, **proximity**, contextual rank |

¹ The Typesense row **is not commensurable with the others** and must not be read as such — it is the score
within one field, the field criterion is not in it at all. The paragraph right below the table develops it.

The Typesense row deserves a refinement so that the claim is not stronger than it is. The order given is the
**48-bit score inside one field** (`include/match_score.h:56-68`), where proximity (`(100 − distance) <<
16`) lies above exactness (`exact_match << 12`) as well as above the word's position in the field
(`(255 − max_offset) << 4`). The field's weight and the number of fields are in Typesense only in the outer
64-bit word (`src/index.cpp:5417`), i.e. one level up — so for Typesense it is correct to claim only that
proximity is above exactness, not above the field.

**An honest formulation therefore reads: all three reference engines place proximity above exactness, two
of the three also above the field criterion. The design places it below both.**

### 3.2 Why that happened — and why it is not a non-binding detail

The reason for that placement is not a relevance consideration, it is mechanical. **Phase 2 can fill only a
lane the selection of the top-K in phase 1 does not depend on.** If proximity lay higher, the composite
computed in phase 1 would have a hole in the middle: the order would be composed of lanes 1–2, then the
unfilled proximity lane would follow, and only then impact and exactness. A top-K selection by such a
composite would not order the candidates by the real key but by a key with a member omitted — and a
document with a weak impact but perfect proximity would never get into the top-K, even though it belongs at
the top.

Placing proximity on lane 5 is therefore **a consequence of the chosen mechanism, not a decision about
relevance**. That is a legitimate engineering compromise, but it has to be stated and decided consciously,
because with it the design departs from the behaviour of all three engines whose cascade it otherwise
adopts.

### 3.3 Three paths

**Path A — leave proximity on lane 5 and document the divergence.**
- *For:* a top-K re-rank is correct, K can be small, the whole of §4.7 holds as written.
- *Against:* proximity applies only in ties of lanes 1–4. Whether such ties occur at all is an empirical
  question — and if they do not, the whole of phase 2 is dead weight (§3.4).
- *When it is right:* when measurement shows that ties above lane 5 are frequent.

**Path B — move proximity up, the way the reference engines do.**
- *For:* behaviour matching established products; "black leather jacket" in the name beats a document that
  has those words scattered through the description, even if it had a higher impact.
- *Against:* **a top-K re-rank ceases to be correct.** The tie group at the lane above proximity is "the
  same number of matched words, the same number of typos" — and for a two-word query, where the vast
  majority of candidates match both words without a typo, that is practically the whole candidate set.
  Correct proximity over the full set means positions in the index, i.e. the positional seam (§7) — and with
  that the load-bearing assumption of §4.7 is cancelled.
- *When it is right:* when it turns out relevance without it is not acceptable, and the cost of a positional
  index is bearable at the same time.

**Path C — two proximities: a cheap flag in phase 1 at the top, the exact distance in phase 2 at the bottom
(recommended).**

The observation that opens this path: **postings are per (field, term)** — §4.2 of the research requires it
because of per-query field weights. During its walk, phase 1 therefore knows **for free in which field each
query token matched**. And "all the query's tokens met in one and the same field" is a very cheap proximity
proxy: precisely the difference between a document that has "black leather jacket" in the name and a
document that has "black" in the name, "leather" in a parameter and "jacket" in the description.

The cost is one extra `long` per candidate. Query tokens are per Z4 two to three (a cap of eight is amply
enough) and searched fields are single digits (a cap of eight too), so the bit mask "token *t* matched in
field *f*" fits into 64 bits. The test "does a field exist in which all the tokens matched" is then a few
instructions over that mask, not another pass.

- *For:* a lane that can be filled **in phase 1**, and therefore can be placed where the reference engines
  put it — above impact and above exactness. The exact token distance stays on lane 5 as a refinement,
  where a top-K re-rank is correct. Nothing is added to the index and the write path pays nothing.
- *Against:* it is a coarser signal than a real distance; it does not distinguish "black leather jacket"
  from "jacket leather black" nor from two occurrences at opposite ends of a long article. In the CMS
  profile, where there is one long field, it degenerates into a constant and brings nothing.
- *Recommended for the e-commerce profile*, where there are more fields and they are short — i.e. exactly
  where today's lane 5 is least useful. For the CMS profile the measurement of §3.4 decides.

### 3.4 What follows from that for K (O2)

Question O2 asks what K should be. The answer §4.7 offers ("proposal 1000") is an estimate without support.
Correctly posed, the question reads differently: **K has to cover the whole tie group at the lane
immediately above proximity.** A different answer follows for every path:

- **Path A:** the tie group is "the same number of words, typos, impact and exactness". Impact is an
  eight-bit quantity with real entropy, so ties ought to be few and a K of the order of hundreds to a
  thousand is probably enough.
- **Path B:** the tie group is "the same number of words and typos", i.e. potentially the whole candidate
  set. No reasonable K suffices and a top-K re-rank is the wrong tool.
- **Path C:** the lane at the top is filled by phase 1 over the full set, so there is no problem there at
  all; for the refinement on lane 5 the same applies as for path A.

**This is at the same time the measurement the stated criteria omit and which is the most valuable of them:
the distribution of tie group sizes.** Concretely — how often for real queries lanes 1–4 fail to distinguish
documents at the boundary of the displayed page. The result has two possible shapes and both are decisions:

- **Ties are rare.** Then proximity on lane 5 is almost never applied and the whole apparatus of phase 2 is
  expensive for what it brings. Either it moves up (path B or C), or it is admitted that proximity is not
  worth its own phase. That is **a result about the quality of the decision**, which is more valuable than a
  number about latency.
- **Ties are frequent.** Then the measurement immediately gives the distribution of their sizes too, and
  thereby the choice of K.

An input to it is also a by-product of P1: **the distribution of impact byte values**. If impact takes only a
few dozen different values in practice, ties are frequent; if it is spread over 256 values, they are rare. P1
produces that data anyway (step K4), it merely has to be recorded.

**An alternative this plan has not considered so far: truncate by predicate instead of by rank.**
Verification over the Elasticsearch checkout (branch `main`, `9a100e2d0e41`, 2026-08-13) shows that the same
pattern "a cheap approximation from postings, an expensive confirmation from stored values" can be truncated
differently from how this plan truncates it. `SourceConfirmedTextQuery` (§4.8) does not work with a top-K at
all: the approximation is **a conjunction of the phrase's terms**, i.e. the set of documents containing all
the words regardless of their placement, and every element of it is confirmed. The truncation is therefore
given by a predicate, not by rank.

That variant has two advantages and one disadvantage against our top-K. It is **exact** — no document drops
out because it did not fit into the window — and in e-commerce queries a conjunction of two to three words is
typically substantially more selective than a thousand candidates. It has no **bounded worst case**, though:
for a query where the conjunction is not selective, the number of documents confirmed approaches the whole
candidate set, and that is per Z7 almost the whole corpus.

What matters is that the building block for it is already in the plan. The cheap co-occurrence flag of phase
1 from path C (§3.3) is exactly such an approximation, merely in a coarser form: the bit mask "token *t*
matched in field *f*" can answer the question "did all the tokens match", and for free during the pass phase
1 makes anyway. **Recommendation: P4 measures both.** If it turns out the number of candidates containing all
the query's tokens is typically below K, the predicate truncation is better in both directions and **the
choice of K largely falls away** — there is nothing to choose, because phase 2 processes exactly the set that
matters. If conversely it turns out the conjunction is not very selective on real queries, that is an argument
for a top-K and the answer to O2 remains a number.

### 3.5 A non-obvious consequence: 16 bits for lane 5 is excessive

The reference engines **cap proximity low**. Meilisearch lowered the maximum indexed pair distance from 7 to
3 (VK2 of the research, `crates/milli/src/proximity.rs:7`), Typesense works with a window
`WINDOW_SIZE = 10` (`include/match_score.h:11`) and holds the resulting distance in a `uint8_t` (`:39`).
Sixteen bits for lane 5 therefore describes a resolution nobody uses.

It is not a memory saving — the composite is one `long` anyway — but it is a free budget of bits that comes
in handy precisely for the lane of path C. The exact bit widths of the lanes are per §4.3 of the research a
matter for the prototype, not of principle.

So that the handover to P7 is not merely a direction but a proposal that can be carried out, I give the whole
layout as it would look after path C. The new lane is the only one added and **2 bits** suffice for it (no
field with all the tokens / some field with all the tokens / plus a distinction that it is the highest-weight
field); proximity shortens from 16 to 8 bits and the sum stays 64.

| Order | Bits | Criterion | Who fills it |
|---|---|---|---|
| 1 | 8 | the number of matched query words | phase 1 |
| 2 | 8 | 255 − the weighted sum of typos | phase 1 |
| 3 | **2** | **co-occurrence of all tokens in one field (new)** | **phase 1** |
| 4 | 8 | max. impact (the former lane 3) | phase 1 |
| 5 | 8 | exactness (the former lane 4) | phase 1 |
| 6 | **8** | proximity — formerly 16 bits (the former lane 5) | phase 2 |
| 7 | 16 | contextual rank | phase 1 (the boost map, P7) |

The remaining 6 bits stay unused as a reserve; the proposal is not to give them away in advance. What matters
about that layout is that **all the lanes above proximity are filled by phase 1**, so the top-K selection
stays correct in the sense of §3.2 — which is precisely the property path B loses.

### 3.6 A second entry question: is a phrase a filter, or a boost?

The whole of §3 so far decides **where in the cascade** proximity lies. Verification over the Elasticsearch
(branch `main`, `9a100e2d0e41`) and OpenSearch (branch `main`, `36edc05ac84`) checkouts opens a question one
floor up, which this plan did not pose at all: **what a phrase is in the response.** It is a real fork, and
for the best possible reason — both engines answered it oppositely, even though they have the same field type
and the same verification mechanics (§4.8).

Elasticsearch has in the JavaDoc of `SourceConfirmedTextQuery` at `:59-63` the sentence "This query
**matches and scores** the same way as the wrapped query": the confirmation from the stored value enters the
score, so a phrase is a ranking signal. OpenSearch returns from the same construction a
**`ConstantScoreScorer`** (`SourceFieldMatchQuery.java:125`) — a phrase match does not contribute to the
score at all and it is purely a yes/no filter, verified for **every** candidate that passed the cheap
approximation. The placement corresponds to that difference too: in Elasticsearch `match_only_text` is an
optional module `mapper-extras`, whereas in OpenSearch it sits in the core.

For us it is not academic, because on the answer hangs whether a top-K is an admissible tool at all:

- **A phrase as a boost.** Whoever has the words close together ranks higher. A top-K suffices, §4.7 of the
  research holds as written, and the criterion "top-1000 within 10 ms" measures the right thing.
- **A phrase as a filter.** The user wants only documents with the given formulation. A top-K **does not
  suffice** in principle: a document with the phrase that ended up at position 1001 in phase 1 drops out of
  the result even though it satisfies the condition. Every candidate has to be verified and the cost grows
  with the candidate set, which is per Z7 almost the whole corpus. It is the same defect as with path B (§3.3),
  merely from another direction.

**The expected answer moreover differs by profile**, and that is the unpleasant part. In an e-shop a boost is
defensible — a user typing "black leather jacket" wants the best matches at the top, not an empty result. In
the CMS profile (Z8), where the user looks for a specific formulation in an article and puts it in quotation
marks precisely in order to get only that, the expectation is rather a filter. The answer "both, depending on
the constraint used" is therefore legitimate, but it means **two cost classes of phase 2**, not one.

P4 cannot decide it on its own — it is a question about the query language, not about a mechanism — but **it
has to fall before P4 measures**, because the two readings measure different things: a boost is measured on a
top-K, a filter on the whole candidate set. Carried as Q6 (§10).

---

## 4. What already exists in the code — verified anchors

### 4.1 Query phases: nothing is loaded between the filter and the ordering today

`QueryPlan` orders the phases thus: `EXECUTION_PREFETCH` (`QueryPlan.java:267-268`), `EXECUTION_FILTER`
(`:274`), `EXECUTION_SORT_AND_SLICE` (`:284`) and finally `FETCHING` (`:310`).

Two things follow and both are fundamental for P4.

**The prefetch runs before the filter, not after it.** It is moreover decided already at planning time and
only from constant bitmaps in the conjunctive scope (`PrefetchFormulaVisitor.java:264-277`), so "the top-K of
the filter's result" is not something it can do. There is therefore no hook today of the kind "after the
filter, load K entities".

**But phase 2 does not need it as a separate step** — it belongs inside the sorter, which runs in
`EXECUTION_SORT_AND_SLICE`. And precisely that is already done by `PrefetchedRecordsSorter`: in
`sortAndSlice` (`PrefetchedRecordsSorter.java:98`) it reads entities and compares them with a comparator
(`AttributeComparator.java:97-99` calls a plain `entityContract.getAttribute(...)`). The only difference is
that today's implementation silently gives up when the prefetch is missing (`:104` returns the context
unchanged), whereas the relevance sorter would have to request the loading itself.

**What is new about it is therefore not the place but the initiative:** a sorter that itself requests the
loading of the subset it has just selected. The `Sorter` interface (`Sorter.java:42-57`) does not prevent it —
it receives a `SortingContext` including the `QueryExecutionContext`, i.e. everything needed.

### 4.2 The 1000 threshold in the prefetch is a coincidence, not an opportunity for reuse

`PrefetchFormulaVisitor.java:69` defines `BITMAP_SIZE_THRESHOLD = 1000` and `isPrefetchPossible()`
(`:283-284`) permits a prefetch only below it. The number is the same as the proposed K, but it is a
coincidence: that threshold guards a *planning* decision "is it worth reading bodies instead of bitmap
algebra", not the size of a re-ranked window. Nothing can be reused and nobody should get the impression it
can.

The cost model by which it is decided is worth attention too: `DefaultPolicy.java:72` computes
`prefetchedEntityCount * requirements.length * 148L`, the same constant is in `SelectionFormula.java:147`
and `:413`. Where 148 came from is nowhere in the repo — it is a heuristic without a derivation. For P4 a
warning follows: **that model will systematically underestimate the cost of re-analysis**, because it was
tuned on ordinary attributes, not on long texts. Should anybody want to reuse it for phase 2, they have to
remeasure it first — and P4 will produce the numbers for that.

### 4.3 The pattern for re-analysis is `AttributeBitmapFilter`

The nearest existing shape "take the candidates, reach for their stored values, evaluate a predicate" is not
a sorter but a filter: `AttributeBitmapFilter.filter` (`AttributeBitmapFilter.java:114`) takes the prefetched
entities (`:116-119`), iterates them (`:124`), obtains the values through an accessor (`:140`) and emits the
conforming PKs (`:141-143`); it memoizes the result (`:115`, `:147`). The siblings are
`SellingPriceAvailableBitmapFilter` and `LocaleEntityToBitmapFilter`, the common interface
`EntityToBitmapFilter`.

Phase 2 is the same shape with a different output: instead of a bitmap of conforming PKs it emits an array of
lane values.

### 4.4 Read granularity: the whole attribute container per (PK, locale)

This is the hardest constraint of the whole "read from entity storage" variant and it has to be known
precisely. `DefaultEntityCollectionPersistenceService.fetchAttributes` (`:392-430`) loads for an entity the
global (non-localized) container with the key `EntityAttributesSetKey(pk, null)` (`:404`) and then **one
container per each requested locale** (`:414-421`). The decision point is at `:400` and it is a plain
**boolean** — `isRequiresEntityAttributes()`.

It means that **selecting an individual attribute to load is impossible**. The set of attribute names in
`AttributeValueSerializablePredicate` (the field `attributeSet`) is a *visibility* filter, not a narrowing of
the read. The minimum unit of reading is one `AttributesStoragePart` for a (PK, locale) pair, deserialized
whole.

It has two sides. The unpleasant one: for a CMS entity with long texts the whole container balloons even
though a single field is searched. The pleasant one: once that container is loaded, **all** the fields of the
given locale are free — batching phase 2's requests into a single request therefore costs nothing extra and
the locale is a natural narrowing, because fulltext is per locale anyway (§4.1 of the research).

### 4.5 The cost of reading: no batch reading, no cache by default

Three verified facts that together determine the lower bound of the "loading" component:

**Batch reading does not exist.** `DataStoreReader` exposes only single-key `fetch(...)`
(`DataStoreReader.java:62` and `:76`); all the "bulk" paths above are loops over individual PKs
(`EntityCollection.java:1265-1277`, `QueryExecutionContext.java:358-362`). K entities are therefore **K
independent lookups**, not one sequential read.

**The cache is off by default.** `CacheOptions.DEFAULT_ENABLED = false` (`CacheOptions.java:67`), and the
deployment documentation recommends leaving it off. Even if it were on, it caches **whole deserialized
entities** (`EntityPayload` is a `record(Entity entity, …)`), not storage parts, evicts by a cost-to-benefit
ratio with cooling after three rounds (`CacheEden.java:88`) and **a payload above 1 MB never enters it**
(`CacheEden.java:93`) — which for CMS documents is a limit that can realistically be exceeded.

**Beneath it there is nothing.** `OffsetIndex.get` (`OffsetIndex.java:722`) on every call looks up the
`FileLocation` and deserializes with Kryo again; there is no cache of records nor of bytes there and the
class's JavaDoc relies on the operating system's page cache.

**Conclusion for measurement: the baseline is measured with the cache off**, because that is production's
default state. A number with the cache is interesting as a second run, not as the main result.

### 4.6 Measuring the cost of reading does not have to be built — the engine already counts it

`ioFetchCount` and `ioFetchedBytes` are collected via `IoFetchStatistics` and every
`dataStoreReader.fetch(...)` is wrapped by them
(`DefaultEntityCollectionPersistenceService.java:722-780`); they get out in `QueryTelemetry` and the
individual plan phases are named in the telemetry (`QueryPlan.java:262` onwards). The phase 2 harness
therefore need not write instrumentation — it merely has to read it off. It is at the same time the best way
of separating the "loading" component from the rest (§1.1).

### 4.7 The stored values are nowhere in memory

For the record, because everybody will ask: there is no structure from which attribute values could be read
without reaching into storage. `SortIndex` holds a sorted array of PKs and the values only per *distinct*
value in an `InvertedIndex`, and moreover only for attributes marked `sortable`; its `getSortedRecordValues`
has in its JavaDoc that it is intended for serialization and nowhere else. `UniqueIndex` and
`GlobalUniqueIndex` hold a map value → PK, but only for unique attributes, which a long text by definition is
not. The variant "read it from the index" therefore does not exist and §5 does not reckon with it.

### 4.8 A precedent outside evitaDB: `match_only_text` in Elasticsearch and OpenSearch

Unlike the rest of §4, this part's anchors point not into evitaDB but into the Elasticsearch (branch `main`,
`9a100e2d0e41`, 2026-08-13) and OpenSearch (branch `main`, `36edc05ac84`, 2026-08-12) checkouts. It belongs
here nevertheless, because it does the same thing §4 does for evitaDB: it evidences that the described
solution exists and in what form. The argument of §4.7 of the research so far rests on **negative** evidence
— Meilisearch indexes positions and it is the most expensive part of its index, so let us not index them. The
following finding is **positive** evidence, and that in both mainstream Lucene servers at once.

**A field type that deliberately has no positions.** Elasticsearch's `MatchOnlyTextFieldMapper` (the module
`modules/mapper-extras/`) has in its JavaDoc at `:122-125` literally "A `FieldMapper` for full-text fields
that only indexes `IndexOptions.DOCS` and runs positional queries by looking at the `_source`". The field's
default settings (`:134-141`) are without stored term vectors, **without norms** (`setOmitNorms(true)`) and
with `IndexOptions.DOCS` — so for every term only a list of documents, no positions nor frequencies.
OpenSearch has the same type right in the core
(`server/…/index/mapper/MatchOnlyTextFieldMapper.java:43`, `IndexOptions.DOCS` hard-coded at `:60`) and its
JavaDoc (`:40`) describes the same choice as switching positions and norms off for the sake of space, in
exchange for phrase queries evaluated in a "slightly less efficient" way. The field moreover refuses to switch
on the auxiliary index of word pairs with the explicit message "Index phrases cannot be enabled on for
match_only_text field. Use text field instead" (`:103`) — whoever wants positions should take an ordinary text
field. The choice is therefore offered as a deliberate compromise, not as the default behaviour.

**A phrase query goes through a two-phase iterator.** The cheap approximation is a conjunction of terms from
position-free postings, i.e. a superset of candidates containing all the phrase's words regardless of their
placement. Only over those is the value **re-analyzed from its stored form** and the phrase confirmed or
rejected. In Elasticsearch that is done by `SourceConfirmedTextQuery` (the JavaDoc at `:59-63`, the
approximation's construction at `:68`) and the same mechanics covers interval, prefix, fuzzy, wildcard and
regular variants too (`MatchOnlyTextFieldMapper.java:837-905`, `SourceIntervalsSource.java`). In OpenSearch it
is done by `SourceFieldMatchQuery` (`:101`) and it is more illustrative, because the verification builds from
the stored value a **one-off `MemoryIndex`**, analyzes it with the same analyzer and runs the phrase query
against it (`:115`); the declared cost of the verification is admittedly an arbitrary number (`matchCost`
1000 at `:120`, the comment "arbitrary cost") that serves Lucene only to order this step after cheaper ones.
Without a stored value it works in neither engine and Elasticsearch says so with an explicit error right in
the mapper (`MatchOnlyTextFieldMapper.java:438`): the field rejects a positional query when the source is
switched off.

Three things I take from it into the plan, each somewhere different.

**Switched-off norms are a warning our design avoids.** `match_only_text` had to sacrifice length
normalization too, because norms in Lucene are part of the positional write. Our impact byte (§4.2 of the
research) is our own structure independent of positions, so we have length normalization even without them.
It is a concrete point where an in-house format earns over an adopted one — in the Lucene format this
combination is simply impossible and obtaining it would mean indexing positions.

**The limit of applicability is the CMS profile's limit.** Elasticsearch targets this field type mainly at
logs, i.e. short to medium-length lines in an enormous volume, and the cost of re-analysis grows with the
value's length. The finding therefore does not refute §4.7's concern about long articles, it merely gives it
context — and it supports the decision to measure short and long fields separately (§9.2) and to consider
failure on the long ones a legitimate trigger of the positional seam (§7), not a failure of the prototype.

**The pattern "an expensive lane declares its cheap approximation" is not a one-off trick.** In OpenSearch it
appears three times independently and in three different contexts: at `match_only_text` (an approximation
from postings, confirmation from the stored value), at derived fields, where the parameter `prefilter_field`
(`DerivedFieldMapper.java:68`) names an existing indexed field as a cheap approximation and the query is
translated into a conjunction of the prefilter with the expensive script evaluation
(`DerivedFieldType.java:171`; a non-existent prefilter field brings the configuration down already at
validation at `:104`), and at phasing search in general. Three independent occurrences are a signal that it is
a load-bearing design pattern, not an improvisation around one problem. What follows from that for P4 is in
§8, step K1.

---

## 5. Where the values come from — four variants

### V1 — read from entity storage after the filter (the baseline)

After selecting the top-K the sorter requests those K entities to be loaded, reads the sought fields,
tokenizes them and computes the lane. The mechanics are described in §4.1: what is new is only the impulse,
not the place.

- *For:* no new structure, zero tax on the write path, exactly what §4.7 of the research assumes. It is at
  the same time the **only honest baseline** — a number saying what the proposed solution costs without any
  further invention.
- *Against:* K independent seeks and K deserializations of whole containers, without a cache and without
  batching (§4.5). For the CMS profile, tokenizing a long text on top.
- *An optimization nobody may omit:* pass the loaded containers on to the `FETCHING` phase
  (`QueryPlan.java:310`), which will be loading the returned page's bodies anyway. Without that, twenty to
  fifty entities are loaded twice. With K equal to the page size the "loading" component thereby amortizes
  almost entirely — which is at the same time the strongest argument for a small K (§6.3).

### V2 — read only the searched fields — **closed**

Rejected not for design reasons but because the storage cannot do it: the minimum unit of reading is a whole
`AttributesStoragePart` for (PK, locale) and the set of attribute names is only a visibility filter (§4.4,
`DefaultEntityCollectionPersistenceService.java:392-430`). **What would have to change for it to be
possible:** the storage part's granularity would have to descend below the locale level — i.e. a new key and
a new on-disk format, with a migration. That is incommensurable with what P4 addresses, and for long texts it
would moreover mean a storage part per attribute, which is a different storage design, not an optimization.

I record it as a closed possibility with its reason, so that nobody reopens it.

### V3 — a forward index of tokens (doc-major)

For every (field, PK) pair store the sequence of term identifiers as they go in the text. Re-analysis thereby
turns into a walk over an array of ints: no reading from disk, no deserialization, no analyzer.

**The arithmetic.** For the e-commerce profile §4.8 of the research counts ~20 tokens per product, i.e. 20M
tokens per million products; with varint-encoded identifiers (two to three bytes) that is 40–60 MB plus a
directory of the sequences. Against the budget of 85–135 MB it is an increment of thirty to seventy per cent.
For the CMS profile it is worse: 100 thousand documents at the order of a thousand to two thousand tokens
gives 100–200M tokens, i.e. 200–400 MB — roughly a **doubling** of the estimate of §4.8.

**The tension that has to be seen:** long fields need this structure most (re-analysis is most expensive for
them) and at the same time pay most for it (they have the most tokens). Switching it off per field is
therefore possible, but it is switched on precisely where it is most expensive.

**And a non-obvious conclusion:** the forward index **is** the positional seam of §4.7 of the research, merely
inverted. The term-major shape (positions inside the postings) and the doc-major shape (a sequence of terms
per document) carry the same information and differ in what they are oriented towards — and for a top-K
re-rank the doc-major is the right direction. §7 develops it.

### V4 — a cache of tokenized values

A bounded cache keyed by the triple (PK, field, catalog version), holding an already tokenized sequence.

- *For:* the cost is bounded by configuration and zero on the write path (the catalog version in the key
  makes invalidation trivial). The assumed benefit rests on **the hypothesis that head queries have a stable
  top-K** — that the same products are re-ranked again and again, so the hit rate is high on the head and low
  on the tail. That is, however, a conjecture without support in data, just like the goal of 10 ms in §1.2,
  and it is at the same time the only unsupported item of the whole variant. It is verified by varying the
  cold / warm state in §9.2 and **without that verification V4 is not to be built**.
- *Against:* the measurement thereby splits into a cold and a warm run and only one of those numbers is
  honest. The p99 budget has to be evaluated on a realistic mix of queries, not on repeating one.
- *It is not the existing `CacheEden`.* That caches whole entities, is off and has a 1 MB cap (§4.5). This is
  a different structure with a different key.

### Recommendation

**Measure V1 as the baseline, build V4 as the first mitigation, hold V3 as the answer to the CMS profile.**
The mapping onto the research's criteria comes out by itself:

| The result of measuring V1 | The consequence |
|---|---|
| ≤ 10 ms on both profiles | done, nothing further is built |
| overruns on the e-commerce profile | V4 (a cache) — a small set of short values, a high hit rate |
| overruns on the CMS profile | V3 for the affected fields — and that **is** the activation of the positional seam (§7) |

The last row is worth emphasizing: the research says "failure on the long ones activates the positional
seam". This plan merely makes that seam concrete — it has the shape of a forward index switched on per field,
not of positions in the postings.

---

## 6. Computing proximity

### 6.1 The template: Typesense's sliding window

`include/match_score.h:129-275` is a finished and transferable algorithm. The input is **the sorted positions
of each query token in one document**, the output the number of tokens in the best window and their mutual
distance. The procedure, as the comment at `:113-127` describes it and the loop at `:149-228` implements it:

1. Fill the window with the first position of every token (`:135-137`).
2. Sort descending; the smallest position is at the end (`:150-162`).
3. Compute how many tokens lie within `WINDOW_SIZE` of the smallest one, and sum their adjacent gaps
   (`:184-187`).
4. If the result is better (more tokens, on a tie a smaller distance), remember it (`:197-205`).
5. Remove the smallest position and insert the next position of the same token (`:213-227`); repeat until the
   window has a single element.

The constant `WINDOW_SIZE = 10` is at `:11` and caps at the same time the number of tokens considered
(`:132`). Early termination when a better result is no longer possible is at `:207-210`.

Two things about that template are defects there is no point in transferring:

- **Distance overflow.** `distance` is a `uint8_t` (`:39`) and is assigned by a truncation
  `uint8_t(best_displacement)` (`:235`). Our lane has bits enough (§3.5), so it should saturate, not truncate
  — truncation can turn a large distance into a small one.
- **Position overflow for long documents.** At `:164-167` there is a guard `if(int(min_offset) <
  prev_min_offset) break;` with a comment that "one of the positions overflows, for example in a long
  document" — the positions are `uint16_t`. That is directly our CMS profile and it means not even Typesense
  is prepared for long documents. Our positions are to be `int`.

### 6.2 What is stored in the lane

The candidate is a monotone function of the best window, decreasing with distance and increasing with the
number of tokens in the window. A recommendation consistent with how the other lanes are built: **saturate
over a small range** — a distance above a chosen cap (proposal 8 to 16, considering that Meilisearch caps at
3 and Typesense with a window at 10) all maps onto the worst value. A finer resolution has no relevance
meaning: a user does not tell the difference between a distance of 40 and 60 tokens.

The order within the window (i.e. whether the words come in the same order as in the query) is a separate
signal that Typesense carries separately as `max_offset`. **I do not recommend introducing it in the
prototype** — it is another lane without a measured benefit; note it as a possibility.

### 6.3 Which queries are computed at all

Z4 of the research says phrases and proximity make sense for two to three tokens. The design turns that into
a hard condition: **phase 2 is triggered only for queries of two to three tokens** and for single-word
queries it is skipped entirely (proximity has nothing to compute over). For longer queries it is either
skipped, or the window cap after Typesense's pattern is applied (`:132`).

It is at the same time the most effective way of keeping the total cost low — single-word queries make up a
large part of the traffic and do not need phase 2 at all.

And from §5 (V1) one more economic argument for the choice of K follows: the entities of the returned page
are loaded anyway (`QueryPlan.java:310`). **With K equal to the page size the "loading" component is
therefore almost free** — if the loaded containers are passed on. The larger K is above the page size, the
larger the share of work that is not realized anywhere else. That is a strong argument for K being of the
order of tens, not thousands — and it is a hypothesis §3.4 is to confirm or refute by measuring tie groups.

**And one finding about that number's shape, not its size.** Verification over the OpenSearch checkout
(branch `main`, `36edc05ac84`) shows a pair of processors from the `search-pipeline-common` module that
composes the same two-phase ranking as configuration: `OversampleRequestProcessor` multiplies the requested
page size by the factor `sample_factor` (it has to be at least 1.0, otherwise the configuration does not
pass) and `TruncateHitsResponseProcessor` truncates the result back at the end. K is for them therefore **a
multiple of what the client wants, not an absolute constant** — and it is a better shape than ours. A query
for the first page of twenty items has no reason to reorder a thousand candidates, whereas a query with a
large page could with a fixed K want more results than phase 2 ordered at all. **A recommendation for O2:
parameterize K as `max(minimum, multiple × page size)`** and measure how it behaves with deep paging, where
the requested offset is large.

The second half of that finding is architectural and is easy to overlook. The oversample processor stores the
originally requested size **into the processing context** under the key `original_size` and the truncate
processor retrieves it from there; it is not passed by the signature, and when nobody put it there it ends in
an exception, not in a silent guess. For us it means the query's evaluation context has to be able to carry
"how much the client really wanted" separately from "how many candidates we let through for the sake of
phase 2". If K were reflected directly into `require(page(...))`, the enlarged size would fall through into
the response — a small thing that gets discovered late and looks like a paging bug.

---

## 7. The positional seam: when it is activated and what shape it should have

§4.7 of the research names the seam but does not give it a shape. P4's proposal is to give it a shape right
away, because the choice of orientation is a decision made once.

**Two orientations of the same information:**

| | Term-major (positions in the postings) | Doc-major (a forward index, V3) |
|---|---|---|
| Shape | (field, term) → a list of (PK, position…) | (field, PK) → a sequence of terms |
| Good for | an exact phrase **filter** | a top-K re-rank, highlighting |
| Write on a value change | the postings of **every** term are affected | a rewrite of **one** sequence |
| Precedent | Meilisearch, the most expensive part of its index (VK2) | forward indexes in general |

The difference on the write path is the essential one and it is in our favour. The term-major shape has on
every change of a field's value to distribute the difference into the postings of all the affected terms — it
is exactly the work the token diff in `p2-transactional-maintenance.md` (§7) models, only multiplied by the
positions. The doc-major shape is by contrast **a replacement of one contiguous sequence**: the old one is
discarded, the new one written, no fan-out. For the CMS profile, where editing a long document is a common
operation, it is an order-of-magnitude difference.

**Recommendation: if the seam is activated, it is activated as a doc-major forward index switched on per
field** (i.e. V3). A term-major positional index makes sense only if the product required *exact phrase
filters* over long texts — i.e. "find only documents where these words are exactly in sequence" as a filter,
not as a ranking signal. The research mentions that as a separate, so far unrequested case, and it holds that
the format of §4.2 does not preclude it.

---

## 8. The realization procedure, step by step

**K1 — the phase 2 interface.** The seam between the top-K selection and the final ordering: an interface
that receives an array of PKs and feature vectors and returns a filled lane. Proximity is its first
implementation, the LTR re-rank of F3 the second. Without this step phase 2 would be baked into the relevance
sorter and §4.3 of the research explicitly wants it to be pluggable.

Into that interface belongs **one additional duty, which §4.8 elevates from an ad-hoc solution for proximity
into a requirement**: every implementation of phase 2 has to be able to declare its **cheap approximation
computable already in phase 1** — a lower bound, a flag, or a predicate, depending on the lane's nature.
Without it the candidate set cannot be narrowed with a guarantee and the only remaining tool is a blind
truncation to K. Proximity has such an approximation (the co-occurrence flag of path C, §3.3) and §3.4 builds
the predicate truncation on it; three independent occurrences of the same pattern in OpenSearch say it should
not remain the exception of one lane.

**K2 — the flag of tokens co-occurring in one field (path C).** The bit mask (token × field) in phase 1's
accumulator per §3.3 and a lane above impact. *Before proximity itself* because it is cheap, measurable in
isolation and answers the question of whether it is necessary to go further at all.

**K3 — measuring tie groups.** Over a set of real queries record how often lanes 1–4 fail to distinguish
documents at the page boundary, and what the tie group sizes are (§3.4). *Here we learn whether phase 2 makes
sense at all and what K should be.* An input is also the distribution of impact byte values from P1 (step K4
of plan P1).

**K4 — loading the top-K and its breakdown into components.** Variant V1: request the containers, measure
loading, tokenization and computation separately (§1.1), using the existing instrumentation `ioFetchCount`
and `ioFetchedBytes` (§4.6). Part of it is passing the loaded containers to the `FETCHING` phase (§5, V1).

**K5 — computing proximity.** A port of the sliding window per §6.1, with saturation instead of truncation
and with `int` positions. A brute-force reference implementation for the test (the same consideration as with
P3: the window algorithm's errors manifest as silently worse scores, not as a crash).

**K6 — measuring on both profiles and varying K.** Per §9.

**K7 — writing up the findings.** A recommendation on path A/B/C, the value of K (O2), and whether the seam is
activated (§7).

---

## 9. The harness and measurement

### 9.1 Placement and shape

The module `evita_test/evita_performance_tests`, JMH for latency, a plain `main` for the distribution of tie
groups (K3) — the same division P1 introduced (`p1-index-core.md`, §7.1). The datasets are the same and are
supplied externally; the CMS dataset is the same organizational dependency P1 carries as question Q5.

### 9.2 Both profiles are measured as two different measurements, not as two points

The research requires it (§7: "measured separately on short fields and on long texts") and it matters, because
those two profiles differ **in all three components** of §1.1 at once: short fields have small containers and
few tokens, long texts large containers and many tokens. Averaging them would give a number that holds for
neither.

Parameters to vary:

| Parameter | Range | Why |
|---|---|---|
| K | 20, 50, 100, 1000 | the main axis (O2); 20 and 50 because of the amortization of §6.3 |
| profile | e-commerce, CMS | two different cost classes |
| the number of query tokens | 2, 3 | only those are computed (§6.3) |
| the token cache (V4) | off, on cold, on warm | three different numbers, three different conclusions |
| the number of locales in an entity | 1, several | it changes the size of the loaded container (§4.4) |
| page and offset | the first page; an offset beyond the K boundary | K's shape (§6.3), the break's visibility (§9.3) |
| the phase 2 truncation | top-K, predicate (all tokens) | two different answers to O2 (§3.4) |

The last row is easy to overlook and yet may be significant: the container is read per locale, so an entity
with five locales pays for one locale, but the container of global attributes is read always.

### 9.3 What is reported

Not one number, but a breakdown: loading (the number of reads and bytes from `ioFetchStatistics`),
tokenization (tokens per second), computation (documents per second). Plus the unit cost per document in both
profiles — that is a quantity extrapolable to another K, whereas the total number is not.

And one extra comparison worth a few lines of code and explaining a lot: **the cost of re-analysis against the
cost of loading the same value**. If it turns out tokenization is noise against reading, it is clear where to
aim optimizations (V4 or V3), and the contradiction of §1.2 falls with it — because highlighting measured on
already loaded entities really can be sub-ms without that saying anything about proximity.

**The break in the ordering at the K boundary is a separate criterion, not a side effect.** Elasticsearch has
a cautionary example of the same: the default window size of its rescorer is **10**
(`RescorerBuilder.java:38`, `DEFAULT_WINDOW_SIZE`), so the user gets a reordered first page and nothing beyond
— and because the window is moreover applied per shard, the result is not even reproducible when the cluster's
topology changes. That second trap does not concern us (our unit of evaluation is single), the first does
unchanged: the K boundary is **visible in the result** and manifests as a break in the ordering exactly at
`from + size = K`. The harness is to measure it directly — for a growing offset record on which page the
ordering stops following the proximity lane — and the behaviour beyond that boundary belongs in
`query-design.md` as a documented property, not as a surprise somebody finds in production.

**Allocations are to be measured too, not only time.** OpenSearch builds a `MemoryIndex` **inside the loop,
anew for every verified document** (§4.8). For us an equivalent pattern — a new tokenizer instance, a new
buffer or a new list of tokens per candidate — would barely show in the time of one run, but it would show in
GC, and that is an area where evitaDB has a documented history of problems on the write path. The breakdown
of §1.1 should therefore be supplemented with **bytes allocated per document** (JMH `-prof gc`), separately
for both profiles: for a CMS document the tokenized sequence is two orders of magnitude longer than for a
product name and the allocation will grow with it.

The usual traps of measurement runs recorded in the repo apply: the benchmark must demonstrably do work (check
that the computed distances are not constant), the run must not share the machine with anything else, and for
the cache variant the cold and warm states must be reported separately.

### 9.4 Quality

Latency alone will not say whether relevance improved. A minimum worth doing: over a set of two- and
three-word queries record the first ten results **without proximity and with it** and have the difference
assessed by a human. If the ordering barely changes, it confirms the result of K3 from the other side and it
is an argument for path B or C rather than for A. Record it machine-readably, for the same reason as with P1
(§7.5).

---

## 10. Open questions and handovers

The division is the same as in `p1-index-core.md`, §8.

### Questions

**Q1 — which of the paths of §3.3 is taken.** It is P4's only real decision and P4 cannot make it itself: path
A is faithful to the mechanism, path B faithful to the reference engines, path C is the compromise I
recommend. K3 (the distribution of tie groups) will supply the material. The decision belongs to O1 (the
default rank profile) and P7 will consume it.

**Q2 — may a sorter initiate a load?** §4.1 shows there is a place for it (inside
`EXECUTION_SORT_AND_SLICE`) and that `PrefetchedRecordsSorter` already reads entities there. What is new is
that the sorter would request them itself instead of relying on a planning decision. It is an architectural
question about the phases' responsibilities, not a technical obstacle, and the same head that owns the planner
should assess it.

**Q3 — how are the loaded containers passed to the `FETCHING` phase.** Without that the page is loaded twice
(§5, V1). There is a place for it in `QueryExecutionContext`, but it is an intervention into the loading
lifecycle and it should be done consciously.

**Q4 — does the cache of tokenized values (V4) have its own configuration?** The existing `CacheOptions` does
not fit (§4.5) and adding a configuration option for a prototype is premature. For F1 it is a question,
though, including whether it should be governed by size or by the number of entries.

**Q5 — where exactly "a long field" ends.** The decision to switch V3 on per field needs a criterion. A
threshold length in tokens from the statistics P1 produces anyway suggests itself (the dense table of lengths
per (field, PK), `p1-index-core.md`, §4.4), so it could even be automatic. But automatically switching on a
structure that costs memory is something somebody ought to approve.

**Q6 — is a phrase given in quotation marks a filter, or a boost?** §3.6 shows Elasticsearch and OpenSearch
answered the same question oppositely — a scoring confirmation against a `ConstantScoreScorer` — and that on
the answer hangs whether a top-K is an admissible tool at all. It is not a question about a mechanism but
about the query language, so it belongs to O4 and to `query-design.md`. **It has to fall before P4 measures**,
though, because the two readings measure different things: a boost on a top-K, a filter on the whole candidate
set. The expectation moreover differs by profile (an e-shop boost, a CMS filter), so the answer "both,
depending on the constraint" is legitimate and means two cost classes of phase 2, hence two sets of
measurements too.

### Handovers — P4's results somebody else is waiting for

**P→O2 — the value of K, its shape and the behaviour of deep paging.** The answer will be given by K3 and K6
together; §3.4 gives the method by which K is derived (it has to cover the tie group above proximity), **as
well as the alternative under which the choice of K falls away entirely** (predicate truncation instead of
truncation by rank), and §6.3 the economic argument for it being small, plus the recommendation to parameterize
it as a multiple of the page size instead of a bare constant. Part of the handover is also **the measured break
in the ordering at the K boundary** (§9.3): it is a separate criterion, not a side observation, and
`query-design.md` is to make it a documented property.

**P→O1 and P7 — a proposal to change the order of lanes.** §3 recommends placing the cheap proximity flag above
impact and shortening lane 5 to 8 bits (§3.5). It is an intervention into the default rank profile, i.e. P7's
territory.

**P→P1 — a request for one extra number.** The distribution of impact byte values on real data. P1 produces it
as a by-product of step K4 and for §3.4 it is an input without which tie frequency cannot be predicted.

**P→F1 — the shape of the positional seam, if it is activated.** §7 gives both the recommendation (a doc-major
forward index per field, not positions in the postings) and the reason, which is mainly on the write path.

**P→P2 — good news.** If the seam is activated in a doc-major form, **it adds no work to the token diff**: the
sequence of terms is rewritten whole on a value change and the fan-out into the postings does not concern it. A
term-major positional index would by contrast burden the budget of §4.9 of the research again, and more.

**Relation to the research's open questions.** P4 **answers** O2 (§3.4, §6.3) and **reopens** O1 (§3, the order
of lanes) — not because the previous answer was wrong, but because it turned out that placing proximity was not
a relevance choice but a consequence of the mechanism. It does not touch the other questions.
