# P7 — rank profiles, the boost channel, feature export

> **Status: a prototype implementation plan, not a decision.** The document follows on from the research
> [`../research.md`](../research.md) (version 2, consolidated) and develops prototype P7 from section §7
> into a form from which building can start.
>
> Date: 2026-08-12. Verification of the anchors against evitaDB's source code: 2026-08-12, branch `dev`.
> Translated from Czech and moved into this record on 2026-08-24.
>
> **The interface towards the other documents.** The exact shape of the query language (the naming of
> constraints, the syntax, the gRPC/GraphQL/REST projections) is addressed by the parallel document
> `query-design.md`. This document defines only the **contract** — what exactly phase 1 needs to receive
> from the query and in what form (§7) — and nowhere anticipates constraint names; where something needs
> naming, it is a working name in quotation marks. The structures P7 computes over (the term dictionary,
> the postings, the impact sidecar) are built by prototype P1 and their shape is taken here as a given
> input.

---

## 1. Goal and criteria

P7 is to prove that **ranking in evitaDB is configuration, not a baked-in algorithm**, and that a dynamic
boost from the behavioural platform can influence the ordering before the result is narrowed to a page.
The research formulates it as an architectural, not a delivery requirement: "P7 belongs to the core right
behind P1 — rank profiles and the boost channel are architecture, not delivery" (README §7). The reason is
simple: once the score's shape is fixed, every further ranking signal means an intervention into the
format, and Sage will be producing many such signals continuously.

The prototype has four independently verifiable outputs:

1. **The rank profile as a data structure and configuration** — the selection and order of lanes, their bit
   widths, field weights, the composition function; the default profile is a lexicographic packing of the
   lanes into a single 64-bit `long` (README §4.3).
2. **The boost channel** — a query context supplied with the query, consulted **in phase 1 over the full
   candidate set**, with a defined place where the stored artifact lives.
3. **Feature export and explain** — a `require` constraint returning the feature vector and the relevance
   breakdown for the entities of the returned page, read from the **same snapshot** as the query itself.
4. **Annotations of recognized entities** — an extra result carrying "recognized 'Bosch' = brand PK 123,
   the corresponding facet filter is …", i.e. the mechanics of the "offer, do not apply" flow from
   README §1.3.

### Acceptance criteria

| # | Criterion | How it is measured |
|---|---|---|
| K1 | A boost lifts a document from a depth outside the top-K onto the page | §11, a differential test |
| K2 | Channel overhead ≤ 1 ms per 10⁶ candidates with an empty map | §11, two arms of a JMH benchmark |
| K3 | The profile is replaceable without changing the score's format | §4, switching the profile at runtime |
| K4 | The feature export is consistent with the emitted ordering | §9, a check against the score in a test |

Criterion K1 is deliberately formulated as a **difference**, not as an absolute phenomenon — "the boost
works" alone proves nothing. Details in §11.

**Scope boundaries.** The whole of P7 is validated through the embedded API. Projecting the new constraints
and extra results into gRPC, GraphQL and REST is the work of delivery phase F1 and follows the
`new-external-api-object` skill; it does not belong in the prototype and none of the criteria K1 to K4
requires it.

---

## 2. Links to the research

What P7 inherits as **decided** and does not question:

- The score is a function of **the query, the document and the explicitly passed query context only**; no
  corpus statistics, no `docFreq`, no maintained global quantity (README §2.1, §4.1). It thereby stays
  deterministic across replicas and stable with respect to a snapshot.
- Phase 1 runs **over the full candidate set**, phase 2 only over the top-K and is pluggable (README §4.3).
  A boost belongs in phase 1, because a document outside the top-K has to have a chance to climb.
- Facets, histograms and the other `require` blocks receive the full candidate set as they do today — **the
  formula engine is not touched** (README §4.3).
- The query context **does not go through the write path**. The boosts' freshness equals the freshness of
  aggregation in Sage, not the frequency of reindexing (README §4.3, §4.9).
- The dividing line: field weights, the choice of profile, the choice of boost table and the A/B arm are
  **policy** and belong to the client; the join through a stored artifact, the feature vector and the
  application of boosts are **mechanism** and belong to the engine (README §1.2, tests 1 and 4).

What P7 inherits as **open** and is to advance:

- **O1** — the position of the contextual rank in the cascade (the last lane after Algolia's pattern vs. in
  the middle after Meilisearch's) and the granularity of profile configuration. P7 is not to decide this
  question as a product matter; it is to make it **configurable**, so that it can be decided by measurement
  (§4.3).
- **O8** — the shape of the boost channel. The research leans towards a reference to a stored,
  Sage-generated table where the engine does the join; an inline PK → coefficient map stays as a debug and
  override channel. What remains is versioning the tables and size limits (§6).
- **O10** — the shape of matching across references. P7 **does not decide** this question, but the research
  says "the design seam (the reference provenance in the feature vector, a defined aggregation function) has
  to be in P1 from the start" (README §1.4). Because the feature vector and the profile are the subject of
  P7, that seam is this document's property (§4.5).

What P7 **does not do**: it does not implement query analysis nor typo expansion (P5, P3), does not build
the postings nor the impact sidecar (P1), does not address proximity (P4) nor vectors (P6). The only thing
it needs from P1 is an interface through which it reaches a candidate's match features.

---

## 3. What already exists in the code — verified anchors

### 3.1 The sorter framework and the precedent of `FilteredPricesSorter`

The research labels `FilteredPricesSorter` a precedent for "a sort by values computed during query
evaluation, not over a precomputed order" (README §4.3). Verified; and the precedent is stronger than the
research suggests — it contains the whole mechanism of **passing data from the filter into the sorter**
that P7 needs.

The sorter's contract is a single method `sortAndSlice`
(`evita_engine/src/main/java/io/evitadb/core/query/sort/Sorter.java:53`), which receives a `SortingContext`
(same place `:70`) with a bitmap of the so-far unsorted keys and a page range, writes the primary keys into
the passed array and returns a new context with what it did not sort. Sorters are chained — each processes
what it can and passes the rest on.

The link to the filter is made like this: the order translator searches **the filtering formula's tree** for
nodes implementing a certain interface and passes them to the sorter in its constructor. Concretely,
`PriceNaturalTranslator` calls `FormulaFinder.find` over `orderByVisitor.getFilteringFormula()` with the
sought interface `FilteredPriceRecordAccessor` and the mode `LookUp.SHALLOW`
(`evita_engine/…/core/query/sort/price/translator/PriceNaturalTranslator.java:81`) and passes the found
nodes into `new FilteredPricesSorter(...)` (same place `:88`). During execution the sorter then pulls from
those nodes the values the filter has already computed (`.../sort/price/FilteredPricesSorter.java:126`).
`FormulaFinder` is a general visitor
(`evita_engine/src/main/java/io/evitadb/core/query/algebra/utils/visitor/FormulaFinder.java:43`).

**For P7 it means:** the relevance sorter finds in the filtering formula the node produced by the fulltext
filtering constraint (P1), and takes the match features from there. No new passing mechanism is introduced.
The registry of order translators, where one item will be added, is
`evita_engine/src/main/java/io/evitadb/core/query/sort/OrderByVisitor.java:90` with its static
initialization at `:93`–`:108`.

### 3.2 What is missing in the sorter framework — two gaps P7 has to fill

**Gap A: there is no top-N selection.** No sorter today does a partial selection. The sorters over
`SortIndex` read a precomputed order and do not sort at all; `FilteredPricesSorter` sorts the whole array
(`Arrays.sort` at
`evita_engine/src/main/java/io/evitadb/core/query/sort/price/FilteredPricesSorter.java:139`) and only then
slices the page out of it (`:165`–`:174`). A helper function for the concurrent sorting of two arrays
exists in `ArrayUtils` only in the `int[]` against `int[]` variant
(`evita_common/src/main/java/io/evitadb/utils/ArrayUtils.java:992`) — for a `long` score against `int`
primary keys it has no counterpart.

That is fundamental for relevance. Phase 1's budget is per README §4.3 at most 25 ms per 10⁶ candidates on
one thread, and that is a budget for **computing the features**. Fully sorting an array of 10⁶ `long`
values is work of the same order, so it would exhaust the budget on its own. The relevance sorter therefore
needs a **bounded selection** — a heap of size `startIndex + pageSize`, or a quickselect over a pair of
`long[]` scores and `int[]` keys. It is new work and P7 owns it.

The connection with the boost is no coincidence, it is the same design point: **the selection may happen
only over a score into which the boost is already mixed.** Were the boost applied after the selection, it
would in effect be a phase 2 re-rank and criterion K1 could not hold. Therefore §5 describes computing the
score and selecting the page as one operation, not as two.

**Gap B: relevance has no counterpart for the prefetch path.** Every sorter today returns a pair — a
comparator for the case where the engine prefetched the entities, and an index sorter for the case where it
did not. `PriceNaturalTranslator` does it literally:
`Stream.of(new PrefetchedRecordsSorter(entityComparator), thisSorter)`
(`.../sort/price/translator/PriceNaturalTranslator.java:107`–`110`). A relevance score, however, arises
from index structures, not from an entity's body, so a meaningful comparator over prefetched bodies **does
not exist**. Three options, ordered by preference:

1. **The presence of relevance suppresses the prefetch.** The simplest and semantically honest — a fulltext
   query produces a large candidate set anyway, where a prefetch is counterproductive. Recommended for
   phase 1.
2. **The score is carried to the comparator.** Phase 1 produces a map PK → score for the returned page
   anyway (the feature export needs it, §8); the comparator would read from it. It works, but it means the
   score has to arise before the path is decided.
3. **Relevance is hard-prohibited on the prefetch path** by an exception. Rejected — the prefetch is an
   internal optimization of the planner and the user has no way of influencing its choice; the error would
   look like non-determinism.

Whatever is chosen, it has to be **explicit** in the design. An unmentioned gap of this kind manifests as a
mysterious ordering error only during integration.

### 3.3 The extra result pipeline

Declaring a new extra result is a well-trodden path. A `require` constraint has to implement the marker
interface `ExtraResultRequireConstraint`
(`evita_query/src/main/java/io/evitadb/api/query/require/ExtraResultRequireConstraint.java:47`), register
itself in the `ConstraintRegistry`
(`evita_query/src/main/java/io/evitadb/api/query/descriptor/ConstraintRegistry.java:164` for
`attributeHistogram`) and in the EvitaQL grammar
(`evita_query/src/main/resources/META-INF/io/evitadb/api/query/parser/evitaQL/EvitaQL.g4:207`). The
translator is entered into the static registry
`evita_engine/src/main/java/io/evitadb/core/query/extraResult/ExtraResultPlanningVisitor.java:144` (the
entries `:150`–`:172`, for example `:156` for `AttributeHistogram`) and its single method `createProducer`
(`.../extraResult/translator/RequireConstraintTranslator.java:54`) is to be cheap — all the work is
deferred into execution.

The producer implements `fabricate(QueryExecutionContext)` (`.../extraResult/ExtraResultProducer.java:48`)
and the resulting object gets into the response through a map keyed by the result's **concrete class**
(`evita_api/src/main/java/io/evitadb/api/requestResponse/EvitaResponse.java:81`, filled at `:125`–`:137`).
A practical consequence: every new extra result has to be its own top-level class, joining an existing DTO
is not possible.

Sharing intermediate results is key for P7 and works by **sharing a formula instance**. The planner passes
the extra result visitor the same instance of the filtering formula the executor will compute
(`evita_engine/src/main/java/io/evitadb/core/query/QueryPlanner.java:684`), and `Formula.compute()`
memoizes its result (`.../core/query/algebra/AbstractFormula.java:197`–`201`). A producer holding a
reference to the same formula therefore gets the computed bitmap for free. Moreover
`ExtraResultPlanningVisitor` can find an already registered producer (`findExistingProducer`, same place
`:291` and `:323`), so two `require` blocks asking for a related thing can share one producer — exactly
what the feature export and the entity annotations, which both read from the same structures, need.

**The order in execution suits P7.** `QueryPlan.execute` first computes the filter
(`.../core/query/QueryPlan.java:274`–`281`), then sorts and slices the page (`:284`–`:299`, the assignment
into the `primaryKeys` array at `:292`) and **only then** fabricates the extra results (`:302`). At the
moment the producer runs, the primary keys of the returned page already exist.

**But they are not available.** `fabricate` receives only a `QueryExecutionContext`
(`.../core/query/QueryExecutionContext.java:103`) and it has no access point to the sliced array of keys —
the array lives on the `QueryPlan` (`:159`) and is never passed into the context. Today that deficiency
bothers nobody: **no existing extra result is page-oriented.** The facet and reference summaries, both
histograms and the hierarchy are aggregates over the whole filtered set, keyed by names from the schema or
by the primary keys of *referenced* entities. The feature export will be the first extra result keyed by
the primary keys of the returned page.

The least invasive intervention is therefore **to set the sliced keys (and the page range) onto the
`QueryExecutionContext` right after `QueryPlan.java:292` and add a getter** — not to extend the signature of
`fabricate`, which would touch all the existing producers.

### 3.4 The limits of constraint arguments

Constraint arguments pass through validation against a closed list of types
(`evita_query/src/main/java/io/evitadb/api/query/BaseConstraint.java:206`, the list in
`evita_common/src/main/java/io/evitadb/dataType/EvitaDataTypes.java:659`–`691`). Scalars, their arrays and
enum types are permitted. **A map does not belong among them.** The inline debug channel "PK →
coefficient" therefore has to be transferred either as two parallel arrays, or as an encoded string.

A second trap: neither `float` nor `double` is in the list and they are normalized to `BigDecimal`
(`EvitaDataTypes.java:990` and `:996`). An inline map with decimal coefficients would therefore arrive as
an array of `BigDecimal`s, i.e. as thousands of objects on the hot path. **Recommendation: the inline
channel carries an `int[]` of primary keys and an `int[]` of coefficients at a fixed scale** (thousandths,
say), the conversion into a working representation happening once at planning time.

### 3.5 A summary of the verified anchors

| What | Where |
|---|---|
| The sorter's contract, `sortAndSlice` | `evita_engine/…/core/query/sort/Sorter.java:53` |
| A sort by computed values | `…/core/query/sort/price/FilteredPricesSorter.java:63` |
| Passing data from the filter into the sorter | `…/sort/price/translator/PriceNaturalTranslator.java:81` |
| The pair prefetch + index sorter | same place `:107`–`:110` |
| Finding a node in a formula | `…/core/query/algebra/utils/visitor/FormulaFinder.java:43` |
| The registry of order translators | `…/core/query/sort/OrderByVisitor.java:90` |
| The missing `long`-keyed concurrent sort | `evita_common/…/utils/ArrayUtils.java:992` |
| The registry of require translators | `…/core/query/extraResult/ExtraResultPlanningVisitor.java:144` |
| The producer's contract | `…/core/query/extraResult/ExtraResultProducer.java:48` |
| Sharing a formula between filter and producer | `…/core/query/QueryPlanner.java:684` |
| Memoizing a formula's result | `…/core/query/algebra/AbstractFormula.java:197` |
| The order of filter, sort and extra results | `…/core/query/QueryPlan.java:274`, `:292`, `:302` |
| The map of extra results in the response | `evita_api/…/requestResponse/EvitaResponse.java:81` |
| Validation of argument types | `evita_query/…/api/query/BaseConstraint.java:206` |
| The container of user filters | `evita_query/…/api/query/filter/UserFilter.java:153` |
| The facet filter | `evita_query/…/api/query/filter/FacetHaving.java:164` |
| The rank in a bitmap (`indexOf` = position) | `evita_engine/…/index/bitmap/Bitmap.java:118` |

---

## 4. The rank profile as configuration

### 4.1 What a profile is as data

A profile is **a recipe for composing an ordering from a feature vector**. A minimal representation
covering the spectrum from Algolia to Meilisearch while being evaluable without allocating per candidate:

```
RankProfile
  ├─ name (identity for the cache, telemetry and A/B)
  ├─ composition: LEXICOGRAPHIC (F1) | weighted sum (F3) | model (F3)
  ├─ lanes: an ordered array of records
  │     ├─ the feature's source (an enum: WORD_COUNT, TYPO_PENALTY, IMPACT,
  │     │                        EXACTNESS, PROXIMITY, CONTEXT_RANK, REFERENCE_MATCH, …)
  │     ├─ the number of bits (only for LEXICOGRAPHIC)
  │     ├─ the direction (ascending / descending)
  │     ├─ the scoring function over the feature's raw value and its parameters
  │     │      (IDENTITY | LOG | SATURATION | SIGMOID | LINEAR | DECAY)
  │     └─ a feature parameter (e.g. the reference's name for REFERENCE_MATCH)
  ├─ field weights: field → weight (default from the schema, the query may override)
  └─ aggregation across references: MAX | SUM, plus a decay per reference type
```

The default profile is a lexicographic packing of the six lanes of README §4.3 into a single `long`. The
essential property that makes the profile fast to evaluate: **the composition is decided once at planning
time, not per candidate.** From the profile a closed composition function is produced at planning time
(shifts, masks and weights precomputed into `int[]` arrays), which phase 1 calls in a tight loop.

**Why a lane carries a scoring function and not merely a width and a direction.** Verification over the
Elasticsearch checkout (main, commit `9a100e2d0e41`, 2026-08-13) offers on the field type `rank_feature` a
ready-made template for the same division this document advocates. The index holds a **raw positive value**
— popularity, a rating, a margin —, and in the mapping a single thing is chosen, `positive_score_impact`,
i.e. whether a higher value means a better document; that is locked precisely because it is reflected in
the stored value (`RankFeatureFieldMapper.java:52` and `:219`). **The function's shape is chosen only in the
query**, from four implementations of `RankFeatureQueryBuilder.ScoreFunction`: `Log`, `Saturation` with a
`pivot` parameter (the default choice), `Sigmoid` with the parameters `pivot` and `exp`, and `Linear`. When
it turns out saturation gives worse results than a logarithm, the query changes, not the index. That
foursome is a good default catalog of functions for our contextual lane and there is no need to invent our
own.

That it is not academic latitude is evidenced by the existing solution. The analysis
(internal, §4.5) describes a **boost by a document's age** in two
variants — linear interpolation and a **logarithmic one with a given base**, where a higher base means a
steeper drop right at the start — with four parameters (the minimum and maximum boost, a lower bound of 30
days and an upper one of 1095 days) and with a wrapper that **handles a missing date with a neutral value**
so that a document without a date is not penalized. It is a small but well-tuned function that editorial
websites really use. Were a lane able to carry only a feature source, a width and a direction, **it could
not be expressed in a profile at all** and a migration would for such a website mean a drop in quality.
Elasticsearch has for the same a family of decay functions (`GaussDecayFunctionBuilder`,
`LinearDecayFunctionBuilder`, `ExponentialDecayFunctionBuilder`) — an independent confirmation that "the
older, the less relevant" is a separate class of scoring function, not a lane parameter. The consequence
for the structure above is therefore concrete: **a lane's record carries the function and its parameters**
and the profile's validation has to be able to reject a function that makes no sense for a given feature
source.

The boundary between the index and the profile has meanwhile to be stated precisely, because it is easily
overlooked: **the impact byte is the only place where our design makes an irreversible decision about
*scoring* already at indexing time.** The tf saturation function and the pivot of length normalization are
baked into it (README §4.2) and changing them is a reindex; it is our counterpart of their
`positive_score_impact`. Everything else in the profile is free and changes at runtime. (There are of course
more irreversible decisions at indexing time — which field is tokenized and stored into the postings at all
is one of them —, but those are not decisions about a scoring function and they live not in the profile but
in the schema.)

**The "model" composition is an artifact, not engine code.** Both verified checkouts draw the same dividing
line in the same place, and that matters for the `model` item in the structure above. `rescore` as well as
`function_score` are in the core, whereas learning-to-rank is factored out — in Elasticsearch as a
**rescorer** in a separate module (`x-pack/plugin/ml/…/inference/ltr/`, the classes
`LearningToRankRescorerBuilder` and `LearningToRankService`), in OpenSearch as a plugin in its own
repository, after which not a single trace remains in the core. The line therefore runs between "a scoring
function over values the engine has" and "a model learned elsewhere", which is an independent confirmation
of F3's placement from README §1.1 and §1.2: **a model is a supplied artifact consumed by a profile.** For
§4.1 it follows that the value `model` in the composition field is **a reference to an artifact, not a
variant of a built-in function**, and that the path should be designed from the start as phase 2 over the
top-K — exactly where Elasticsearch placed it — not as an alternative composition of phase 1.

### 4.2 The 64-bit budget and why configurability follows from it

The default lane table of README §4.3 has widths 8, 8, 8, 8, 16 and 16 bits. That is **exactly 64**. Not a
single free bit remains. Any further signal — a match across a reference (§1.4), a match on an annotated
entity (§1.3), a score from the vector leg (§5) — does not fit into the default packing without something
else being reduced. That is not a deficiency of the design, it is the strongest argument for **the widths
and order of the lanes being configuration and not a constant**: adding a signal is then a choice of
profile, not a change of the score's format.

Two consequences that have to be in the implementation from the start:

- **Lane 5 (proximity) is empty in phase 1 and is filled only by phase 2.** The lexicographic ordering must
  not be broken by that: an empty lane has to be written as a neutral value (not a random one), so that the
  ordering from phase 1 is stable and phase 2 can merely overwrite its bits and reorder the top-K. The
  profile therefore carries for every lane a flag of which phase fills it.
- **Zero reserve means the sum of the widths has to validate the profile**, not the runtime. A sum above 64
  bits is a configuration error and has to fall at query planning time with a clear message, not silently
  truncate the top lane.

**Two axes of composition the lexicographic composite does not have.** Verification over the Elasticsearch
checkout (main, commit `9a100e2d0e41`, 2026-08-13) shows in `function_score` (the package
`server/src/main/java/org/elasticsearch/index/query/functionscore/`) a distinction our default format does
not make at all, and it is good to know why. `score_mode` says how the **individual scoring functions**
combine with each other; `boost_mode` says how their result meets the **match score** (both `MULTIPLY` by
default, `FunctionScoreQueryBuilder.java:64-65`), and to that belong the safeguards `maxBoost` and
`minScore`. Moreover **every function may have its own filter** and apply only to documents satisfying it.

Two things about that are interesting for us. The first is the last named: **a function with its own filter
is exactly the shape merchandising needs** — "in this category lift the in-stock ones" — and in our model it
is extraordinarily cheap, because the filter is a bitmap we already know how to compute, and its
intersection with the candidate set is the same operation path 2 of the boost channel does (§6.4). The
second is that both axes are in a lexicographic packing **inexpressible in principle, not by oversight**: in
a composite the lanes do not compose, they merely queue up, and "the match score" is not a separate quantity
in it that something could be combined with — it is one of the lanes. As long as the default profile is a
cascade, that is fine and it is a saving; the zero reserve of 64 bits described above is after all the same
property seen from another side. As soon as a profile with weights instead of a lexicographic order arrives
(the "weighted sum" composition of §4.1), though, this pair of axes is what will need to be named — and **it
is worth allowing for it in the profile's design before the first non-trivial requirement forces it**,
because it is then added to a format clients already have in hand. Concretely it means the profile's
composition function has to be designed so that a weighted sum is not a special case of packing but an
equivalent variant.

### 4.3 Where named profiles live — three variants

| Variant | Where | For | Against |
|---|---|---|---|
| (a) The entity schema | `EntitySchema` | versioning and backups for free | a change is a schema mutation |
| (b) An artifact store | like the boost table (§6) | a hot swap without a mutation | an extra storage concept |
| (c) Inline in the query | constraint parameters | zero infrastructure | the shape bakes into the clients |

**Recommendation for P7: (c) inline for the prototype, (a) the schema as the target of phase F1, (b)
rejected.**

The justification. An inline profile is the only variant requiring nothing to be built, and P7 is to prove
*that a profile is configuration*, not *where it lives*. For delivery, though, inline is wrong — the "smart
client" trap of README §1.2 applies here too: were every client to carry the profile's shape, changing the
composition would mean a coordinated change of all the clients and explain would stop being comparable
between them. The schema is the right home because a profile is **a property of a collection** (which fields
exist and what they mean), not a property of one query; and a schema mutation is fine for a profile, because
a profile changes orders of magnitude more rarely than a boost table.

The artifact store (b) is rejected not because it would not work but because for a very small volume of data
(single-digit kilobytes) it would pay the whole cost of a new storage concept described in §6.2 — without a
single one of the advantages that make that cost worth it (size, paging, independence from the schema).
Should it turn out that profiles have to be changed on a scale of minutes and schema mutations are too
expensive for that, that is the point where the decision is reconsidered.

The query may in any case **select a named profile** and override the **field weights** — those are policy,
i.e. the client's territory (README §1.2, test 4). It must not define a new profile at runtime; that
boundary is the same decision as with analyzers.

**Precedence between the levels is a question of its own and has a ready-made answer.** The table above
addresses *where a profile lives*; this is the second axis — *which profile is used for a specific query*
when several places offer one at once. Verification over the OpenSearch checkout (main, commit
`36edc05ac84`, 2026-08-12) gives an answer to question O1 ("per schema, or per query?") that rejects that
falsely binary shape: **all levels at once, with a defined order of precedence.** All of it is legible in a
single method, `SearchPipelineService.resolvePipeline()`
(`server/src/main/java/org/opensearch/search/pipeline/SearchPipelineService.java:464`), and it descends
thus: (1) an inline definition right in the request, from which a one-off pipeline is built; (2) the name of
a stored pipeline in the request (`:500`); (3) the index's default pipeline from the setting
`index.search.default_pipeline` (`:511`), which the client need not know about; (4) switched off, through
the reserved name `_none`, which is at the same time that setting's default value.

For us **levels 2 to 4** apply and it has to be said explicitly, because their level 1 is exactly what this
section rejects two paragraphs above — defining a new profile at runtime in a query. The finding does not
cancel that decision: variant (c) stays a shortcut for the prototype and `query-design.md` §6.2 keeps the
inline shape limited to field weights, with its own empirical argument against extending it. The usable
order of precedence for us therefore reads **a profile name in the query > the default profile in the
collection's schema > no profile**, and it is not to be adopted from OpenSearch as a four-item list, because
the first item is not on our table.

Two details OpenSearch has trodden out are, however, worth adopting literally, because both are otherwise
discovered only in operation. First, **there has to be a reserved name for "no profile"**: without it the
collection's default profile cannot be suppressed from an individual query and a client wanting the raw
ordering — typically when debugging or when doing a comparative measurement — has no way of requesting it.
Second, **under ambiguity rather nothing**: when a query targets several indexes with different default
pipelines, OpenSearch does not pick one nor chain them but switches them all off
(`SearchPipelineService.java:515`). That is a defensive choice of exactly the kind `CLAUDE.md` requires for
unexpected states, and for us it has a counterpart everywhere a default profile could come from more than
one place.

**A home-grown precedent for named profiles exists, and it carries its lesson in its trap.** The analysis of
the existing solution (internal, §4.7) describes a mechanism introduced
in 2025: in the configuration a set of default parameter values is named — the weights of the individual
legs, the age boost's parameters, the default operator —, the query sends only the profile's name and the
builder fills in all the values the caller **did not set themselves**, so an explicit parameter always takes
precedence. An unknown profile is logged as a warning and the search continues with the default values. It
is the same shape of precedence the preceding paragraph proposes, only one level poorer (it knows neither
the selection and order of lanes nor another composition) — and it is evidence that this path is walked
naturally.

The lesson is, however, in the trap that mechanism produced at the boundary of the client and the library.
The analysis of the e-commerce layer (internal, §3.2) shows that when a profile
is given, the client sets **only its name and nothing else**; when it is not given, it sets the title's
weight and the exact term leg. Because the default value of the title's weight is zero and zero per its own
documentation means "search only in the content, not in the title", **specifying a profile silently switches
off searching in the title and the exact match**, unless the profile switches them back on itself. No error
arises anywhere, the search merely silently searches differently. For P7 a rule follows that belongs in the
design as well as in the documentation: **choosing a profile must not silently change the semantics of the
rest of the query.** A profile is a named set of values for lanes and weights, not a path on which somebody
forgets to set a parameter. And secondarily: a value whose zero means "switch the whole feature off" is a
design error in itself, because it cannot be distinguished from unset — in the new format switching a lane
off is to be expressed by the lane not being in the profile.

### 4.4 The default profile and the position of the contextual rank (O1)

O1 asks whether the contextual rank belongs at the end of the cascade (Algolia — business decides only on a
full tie of relevance) or in the middle (Meilisearch — business speaks earlier). **P7 is not to decide this
question, it is to make it measurable.** The concrete task for the prototype: deliver two named profiles
differing only in the position of the `CONTEXT_RANK` lane and show that switching between them is a one-line
change of the query requiring neither reindexing nor a restart. The decision will fall by measurement at the
gate against the golden set, not in this document.

A practical note on the consequences: the contextual lane's position fundamentally changes how strong a
boost has to be to satisfy K1. At the end of the cascade it will not lift a document that has one matched
word fewer; in the middle it will. The test per §11 therefore has to say which profile it measures with.

### 4.5 The seam for matching across a reference (O10)

README §1.4 is explicit about this: the gate is not widened because of matching across references, but "the
design seam — the reference provenance in the feature vector, a defined aggregation function — has to be in
P1 from the start". Because the feature vector and the profile are owned by P7, that seam belongs here. What
concretely has to be present, even though it will never be filled in phase 1:

1. **A feature carries its provenance.** A match feature has to be able to say whether the match occurred
   directly on the document, or across a reference of type `R`. It suffices for the feature's source to be
   parameterized by the reference's name (`REFERENCE_MATCH(R)`), and for a direct match to be a special
   case.
2. **The aggregation function is declared, not implicit.** When a document matches across several
   references, the profile has to say whether the maximum or the sum is taken, and with what decay per
   reference type. The anti-pattern is Solr, which for a multi-valued reference propagates the score of the
   *first* occurrence, not the best, and does so even when the user requested `score=max` (README §8, VK16).
   An undeclared aggregation is exactly the class of error that never manifests as an exception, only as a
   permanently slightly wrong ordering.
3. **The decay is per reference type, not global.** The research distinguishes composition (a content block
   *is* the page's text) from association (a related product merely boosts) — and that is a difference in the
   decay's value, not in the mechanism.

P7 **models and validates** these three things but does not fill them: aggregation over an empty set of
references is a neutral element and costs nothing. Were the seam deferred, adding it later would mean a
change of the score's format — i.e. exactly what profiles are meant to avoid.

---

## 5. Phase 1: composing the score and selecting the page

This section joins two things often described separately and which must not be designed separately:
computing the score and selecting the page. Criterion K1 ("a boost lifts a document from a depth outside the
top-K") is namely **a claim about the order of these two operations**.

### 5.1 The pass

Phase 1 is a single linear pass over the candidate bitmap. Per candidate:

1. It reads the match features from P1's index structures. Membership in the per-token bitmaps gives the
   number of matched words; the impact byte is read through the primary key's rank in the postings bitmap
   (`Bitmap.indexOf`, `evita_engine/…/index/bitmap/Bitmap.java:118`, returning a position with the same
   semantics as `Arrays.binarySearch`).
2. It reads the slow priors from attributes, if the profile uses them.
3. **It consults the boost channel** (§6.4).
4. It calls the profile's composition function and gets one `long`.
5. It offers the pair (score, PK) to the selection structure.

Points 1 and 2 are P1's domain; P7 owns points 3 to 5 and the interface through which points 1 and 2 supply
values.

### 5.2 Selecting the page

Because no partial selection exists in the repo (§3.2), P7 introduces it. Two paths:

- **A heap of size `endIndex`.** A min-heap of (score, PK) pairs of the size of the requested page's end is
  maintained; a candidate with a worse score than the root is discarded without a write. For shallow paging
  (`endIndex` in the tens) it is practically free and the memory is constant. It degrades with deep paging,
  where the heap grows with the offset.
- **Quickselect over two arrays.** A `long[]` of scores and an `int[]` of keys are materialized for the whole
  candidate set, the `endIndex`-th element is selected and only the prefix is sorted. The cost is 12 bytes
  per candidate (12 MB per 10⁶) and linear average complexity regardless of the page's depth.

**Recommendation: the heap as the default path, quickselect as insurance for deep paging**, with a threshold
determined by measurement. The heap corresponds to typical traffic (fulltext looks at the first pages) and
does not allocate an array the size of the corpus; quickselect is better exactly where the heap fails. The
threshold value is question O2 from README (the behaviour of deep paging) and P7 is to narrow it, not decide
it.

Whichever is chosen, it needs **a concurrent sort of a `long[]` of scores and an `int[]` of keys**, which
`ArrayUtils` does not have today (§3.2). It is a small, well-testable piece and belongs in `evita_common`
beside the existing `int[]` variant, not in the sorter.

### 5.3 Why the boost has to come before the selection

Were the boost applied to a set that had already passed the selection, it would by definition be a phase 2
re-rank and a document from a depth would have no way of climbing — it would never get into the set being
selected from. This is not a theoretical concern; it is exactly the compromise Vespa documents with
`rerank-count`, and the research deliberately rejects it for the boost (README §4.3: "a document outside the
top-K has to have a chance to climb, a re-rank is not enough").

The practical consequence for the implementation: **the boost enters as the value of one lane of the feature
vector, not as a correction of a finished score.** That is also why point 3 in §5.1 is inside the loop and
not after it. The test per §11.1 verifies this property differentially against a variant where the boost acts
after the selection.

---

## 6. The boost channel

### 6.1 What Sage produces today and what follows from it

Per `/www/oss/Sage/docs/analysis/behavioural-ranking-platform.md` §3 Sage aggregates historical interactions
into rows `(query, document, boost)` and applies them as a multiplicative Solr `boost` parameter. Two things
from that document have a direct impact on the design and are worth naming explicitly:

**The boost table is not a table today.** It is a `SELECT` with a self-join over QuestDB, run on demand and
cached for two minutes (same place §3, "Cached per query with a 2-minute TTL, or it would be a second engine
call on every search"). The artifact model of O8 therefore **requires something new** of Sage: materializing
the aggregate into a publishable table with its own cadence. That is not an integration detail, that is a
requirement on the other side of the interface and it belongs in the brief for Sage. Market cadence for
comparison: Algolia Dynamic Re-Ranking computes over a thirty-day window and recomputes of the order of once
a day (README §8, VK14). A cadence in minutes and a cadence in a day lead to very different decisions about
transactionality (§6.2).

**The table's key needs exactly one normalization.** The same document describes a lesson from LTR that
transfers here literally: `efi.keywords` has to share one normalization function with training, "two copies
would drift and the model would see a different string distribution at serving time than at training time,
with no error to notice" (§5). For the boost table the same holds of the key: the query under which Sage
computed the boost and the query under which evitaDB looks it up have to pass through the same normalization.
**Recommendation: the engine owns the normalization**, because it operates it for indexing and querying
anyway (README §1.2, test 2), and Sage keys the table by the output of the same chain. The alternative —
Sage normalizes and the engine takes the key literally — is simpler, but introduces a second copy of the
rules outside the engine and a silent drift on any change of the analyzer. An open item: what to do with
queries that merge after normalization, and whether the key is the normalized string or its hash (§12, Q4).

**Today's boost is on the write path and in effect does nothing — the boost channel is therefore purely a new
capability, not a replacement.** The analysis of the existing solution
(internal, §4.1) shows that every document gets a constant boost from the
builder's configuration at indexing time (the default value being 10000) and the default resolver merely
passes it on. A constant the same for all documents is a **no-op** between documents; it would gain meaning
only where one section mixes several data providers and one type of document is to have precedence over
another. A real seam for a per-document boost — "bestsellers higher", "in stock higher" — exists in the
architecture as the interface `FulltextResourceBoostResolver`, but in the examined sample of projects the
analysis's author **found not a single implementation** of it; they themselves add that this is no proof
that none exists, because nobody went through a wider sample.

Two things follow for P7. First, **the difference against our design is qualitative, not parametric**:
today's boost lives on the write path, so changing it means reindexing, whereas the boost channel is a query
input of phase 1 and does not go through writing at all (README §4.3, §4.9). Second, and this is more
important for expectations of criterion K1: **it is not measured against established practice, because there
is none.** The boost channel does not take over a function anybody uses today and could compare with — it
fills an empty place in the architecture. For the brief towards Sage it means the table has nothing to be
backward compatible with, and for the ADR it means formulating P7 as a new capability, not as a migration of
an existing one.

**A third lesson aimed at the feature export:** LTR features are extracted in Sage retroactively from the
live index, so volatile features (popularity, price, stock) are read as they look *now*, not as they looked
at the moment of the click — the document itself labels it a known limit of accuracy (§5, "a known accuracy
limit for the rest"). That is the strongest justification of the requirement for an export from the same
snapshot in §8.

### 6.2 Where the artifact lives — three variants

The requirements: catalog-wide scope, versioning, replacement at runtime without reindexing entities,
survival of a backup, a restore, a copy and a rename of the catalog, and replication wherever the rest is
replicated.

#### (a) A custom `StoragePart` in the catalog's offset index

The catalog has its own offset index separate from the collections
(`evita_engine/…/spi/store/catalog/persistence/CatalogPersistenceService.java:300`) and writing into it is
`putStoragePart(catalogVersion, part)`
(`…/spi/store/catalog/persistence/StoragePartPersistenceService.java:117`). MVCC by catalog version gives the
artifact for free: readers at version N see the old table while N+1 writes the new one — exactly the
semantics of a hot swap. Paging into chunks has a ready-made template in `GlobalUniqueIndexLeafPagePart`,
where the `long` key is composed of a stream identifier and a page's ordinal
(`…/spi/store/catalog/persistence/storageParts/index/GlobalUniqueIndexLeafPagePart.java:96`–`100`). The size
of one record is not limited by the output buffer's size — `StorageRecord` chains continuation records
through `CONTINUATION_BIT`
(`evita_store/evita_store_key_value/…/offsetIndex/model/StorageRecord.java:93`, the overflow handler
installed at `:908`–`:909`); the hard cap is only the range of an `int` in the record's length.

The cost is, however, real and it is fourfold:

1. A byte discriminator and type registration, and that **in two places** — in `META-INF/services` as well as
   in `provides` in `module-info.java`. The module's declaration today lists only `IndexStoragePartRegistry`
   (`evita_store/evita_store_server/src/main/java/module-info.java:42`), whereas the services file lists
   `CatalogStoragePartRegistry` too. On the module path only `provides` counts, so a type registered only in
   the catalog registry silently will not be found under JPMS.
2. A Kryo serializer wired into the catalog's chain of configurers, with all the `serialVersionUID`
   discipline and backward-compatible readers.
3. A mutation by which the replacement gets into the WAL. The mutation hierarchy is `sealed`
   (`evita_api/…/requestResponse/mutation/CatalogBoundMutation.java:57`), so a new type means either editing
   the `permits` clause, or squeezing into `SchemaMutation`.
4. A transaction size cap: the whole transaction has to fit into one WAL file, otherwise a
   `TransactionTooBigException` falls
   (`evita_store/evita_store_server/…/store/wal/AbstractMutationLog.java:1319`–`1330`); the limit is
   `walFileSizeBytes` (`evita_api/…/configuration/TransactionOptions.java:100`). The WAL is not chunked.

#### (b) A system collection of entities

evitaDB **has no concept of an internal system collection** — only the names of a catalog (`system`) and of
entity types (`catalog`, `entity`, `schema`) are reserved, and that because of collisions with external API
endpoints (`evita_common/…/utils/ClassifierUtils.java:54`–`58`). Nothing, though, prevents creating an
**ordinary collection** named say `BoostTable`, where one entity carries one key of the table: the
normalized query as a unique attribute, the version and validity time as further attributes, and the actual
payload (parallel arrays of primary keys and scaled coefficients, i.e. two `int[]` arrays) as associated
data. Associated data can carry an array of a supported scalar type as well as a structured
`ComplexDataObject` (`evita_engine/…/storageParts/entity/AssociatedDataStoragePart.java:60`,
`evita_common/…/dataType/ComplexDataObject.java:59`); `int[]` is recommended because it corresponds to the
scaled representation of §3.4 and is verifiably supported.

What is gained **for free, without a single line of storage infrastructure**: transactionality, replication
through the WAL, backup and restore, a copy and a rename of the catalog, versioning by catalog version, bulk
writing with the existing API, and key lookup through the existing unique index. Replacing the table is an
ordinary upsert. Sage needs no new interface — it publishes entities, which it can already do.

What is lost: the collection is visible in the catalog like any other, so it appears in listings and in the
schema (it is more a property than a defect — an artifact *ought* to be visible and auditable, but it is a
decision, not an oversight). The lookup goes through the index path, not through a plain hash map, so it is
paid for with one lookup per query instead of zero. And an entity carries overhead the table does not need.

#### (c) A file in the catalog's directory

**Rejected, and for a verified reason, not out of caution.** A full backup as well as a copy and a rename of
the catalog walk the directory and filter it by a **closed list of extensions**: the backup calls the copying
routine separately for `.boot`, `.catalog`, `.collection` and `.wal`
(`evita_store/evita_store_server/…/store/catalog/task/FullBackupTask.java:175`–`186`, the filter itself at
`:241`), a catalog copy discards everything without a known extension
(`…/store/catalog/DefaultCatalogPersistenceService.java:253`–`257` and `:3203`–`:3234`). A file with its own
extension would therefore **silently disappear at the first backup or copy of the catalog** — without an
error, without a warning. That today's loose files in the catalog's directory are exclusively regenerable
flags (`.restored`, `.provisional`, `.catalogname`) confirms that intent. For variant (c) to work, at least
three extension-filtering places plus the storage prefix discovery would have to be extended — and even then
the artifact would be neither transactional nor replicated.

#### Recommendation

**For P7 and phase F1: (b) an ordinary collection of entities. (a) as a named fallback. (c) rejected.**

The decisive argument for (b) is not elegance but the ratio of cost to demonstrated need. Variant (a) is
architecturally cleaner and possibly ultimately right — a hash map in memory without entity overhead is
better for a lookup on the hot path than a walk through an index. But it is paid for with the four items of
§6.2(a), one of which (editing the `sealed` clause of the mutations) reaches into the engine's central
contract, and P7 has nothing with which to evidence that the cost is necessary. Variant (b) allows the
**real size and cadence of the table to be measured on real data** and only then the decision to be made.
The transition from (b) to (a) is moreover cheap: the data's shape does not change, only the storage under
the loading layer does.

One thing cannot be decided from here and there is no point guessing it: **the table's granularity** —
whether the artifact is the whole table in one piece, or one record per query key. It depends on the
cardinality of distinct queries and on the total size, which are numbers we do not have (§12, Q1).

### 6.3 Versioning and the atomicity of the swap

Independently of the variant, three rules apply:

1. **The swap is atomic from the query's point of view.** A query resolves the table once at planning time
   and holds it for the whole execution; it must not happen that the first half of the candidates sees the
   old table and the second the new one. With variant (b) that follows from snapshot isolation for free.
2. **The table carries its own version** and that is propagated into the explain output (§8). Without it,
   why a query emitted the ordering it emitted cannot be reproduced — and that is the only reason explain
   exists.
3. **A missing or unknown table is a no-op, not an error.** A reference to a table that does not exist
   lowers the quality but must not bring the search down. It is the same fail-open rule README §1.3 requires
   of the model path.
4. **The boost channel is a visible part of the profile, not a step inserted behind the configuration's
   back.** Verification over the OpenSearch checkout (main, commit `36edc05ac84`, 2026-08-12) shows what the
   opposite costs. `SystemGeneratedProcessor`
   (`server/src/main/java/org/opensearch/search/pipeline/SystemGeneratedProcessor.java:14`) is a step the
   system inserts into the pipeline itself, without the client asking for it, and declares whether it
   belongs before the user's steps or after them (`:44`–`:52`). Because system and user steps can thereby
   come into conflict, a whole conflict evaluation had to arise for it
   (`ProcessorConflictEvaluationContext`, called from `SystemGeneratedPipelineWithMetrics.java:222`). The
   temptation to do the same is concrete for us: dynamic boosts from Sage are to be applied by the engine,
   not by the client, so "the server silently adds a contextual lane" suggests itself. The cheapest way not
   to have that problem is to insert nothing implicitly — the contextual lane is a lane the profile either
   contains or does not. No conflict thereby arises and **the profile stays a complete description of how
   the ordering came about**, which is exactly the property README §4.3 wants when it insists on the
   determinism of "a function of the query, the data and the explicitly passed context".

### 6.4 The lookup in phase 1 — two paths

**Path 1: a hash map consulted per candidate.** The table is resolved at planning time into a primitive map
`int → int` (a PK onto a scaled coefficient), and phase 1 asks it about every candidate. The cost is one
hash map lookup per candidate, i.e. one to two cache misses with a shuffled map.

**Path 2: the boosted set evaluated separately.** The boosted primary keys are converted into a bitmap, that
is intersected with the candidate set, and the intersection is evaluated by a second pass with the
contextual lane filled. The main pass then does not consult the boost at all.

When each wins. Path 2 is better when the boosted set is small relative to the candidate set (README §4.3
speaks of hundreds of PKs against a million candidates) — a bitmap intersection is orders of magnitude
cheaper than 10⁶ hash map lookups, and the main loop stays branch-free. Path 1 wins when the boosted set is
large or when a second pass would have to be done anyway. Path 2 has, though, one condition that has to be
watched: **the page selection has to happen only after both passes are merged**, otherwise §5.3 is violated.
Practically it means offering into the selection structure from both passes, not merging the results from
finished pages.

**Recommendation: implement path 1 as the reference and path 2 as an optimization with a threshold** by the
ratio of sizes. Path 1 is simpler, easier to test and evidently correct; path 2 is a performance variant of
the same that can be verified against path 1 by agreement of results.

### 6.5 A fast path for an empty map (criterion K2)

The criterion asks for overhead of at most 1 ms per 10⁶ candidates when the map is empty. The key is for an
empty channel to cost **nothing per candidate**, i.e. not even one condition in the loop:

- The decision falls **once at planning time**. When the query has no boost channel, or when the key lookup
  in the table found nothing, the planner picks a composition function **without the contextual lane** — not
  a function with a lane that happens to return zero.
- Both forms of "empty" — the channel is not in the query at all, and the channel is present but the query's
  key is missing from the table — have to lead to **the same** unboosted composition. Otherwise something
  other than what most often happens in operation would be measured (the majority of queries in the long
  tail will have no boost).
- Resolving the table (loading it, looking the key up) is a **one-off cost at planning time**, not a cost per
  candidate. For the measurement per K2 it counts into the overhead, but it does not scale with the
  candidate set's size — and that is precisely why K2 is measured as a difference of two arms, not
  absolutely (§11.2).

A concrete implementation rule follows from that: the profile's composition function is a **selected
instance**, not a function with a `boostMap` parameter that may be `null`. A `null` check inside a tight loop
over 10⁶ elements is exactly what eats into the 1 ms budget.

---

## 7. The contract towards the DSL

The query language's shape is addressed by `query-design.md`. Here is only a list of what phase 1 needs to
receive, formulated independently of the syntax. Every item is marked with where it belongs per the dividing
line of README §1.2.

| What | Mandatory | Who determines it | Note |
|---|---|---|---|
| The choice of a named profile | no (the default profile) | the client (policy) | a name, not a definition |
| Field weights for this query | no (the default from the schema) | the client (policy) | pairs of field + weight |
| A reference to a boost table | no | the client (policy) | the artifact's identity + optionally a version |
| An inline boost map (debug) | no | the client | `int[]` PKs + `int[]` scaled coefficients (§3.4) |
| The key for the lookup in the table | no | the engine | derived from the query's text, not sent separately |
| K for phase 2 | no | the client (policy) | O2; phase 1 merely passes it on |

Two contract points that are not syntax and therefore belong here:

**The boost table's key is not sent.** The engine derives it from the same text it analyzes for matching, by
the same normalization (§6.1). Were the client to send it, it would be the "smart client" trap of README §1.2
in pure form: the normalization rules would spread into the clients and silently diverge from what indexing
uses.

**The profile and the boost table are independent axes.** The profile says *how* the contextual lane composes
with the rest; the table says *what values* go into it. An A/B experiment may change one, the other, or both
— and explain has to state both so that the arm can be reconstructed. Assignment into an arm is in Sage a
pure function `(experimentId, unitId, exploreShare)` (`behavioural-ranking-platform.md` §6), i.e. a client
matter without state; the engine merely receives the result of the choice.

---

## 8. Feature export and explain

### 8.1 Why it is not a nice-to-have

Two consumers, both named in the research. Training data for LTR (README §4.3, the precedents being Solr's
`[features]` transformer and Vespa's `match-features`) and relevance tuning. Sage today extracts features
**retroactively from the live index**, so it reads volatile features at extraction time, not at the time of
the click — its own documentation labels it a known limit of accuracy
(`behavioural-ranking-platform.md` §5). An export from the same snapshot from which the query computed the
ordering **removes** that defect, and that is its main value, not convenience.

### 8.2 The response's shape

An extra result keyed by **the primary keys of the entities of the returned page**, not of the whole filtered
set. For every key a map feature name → value, plus the composed score and the identity of the profile and
the table. In rough outline:

```
FeatureVectors
  ├─ profile: name + version
  ├─ boost table: identity + version (or empty)
  └─ per PK of the returned page:
        ├─ score: long (exactly what the ordering was by)
        ├─ features: name → value
        └─ lanes: the order in which they composed (explain mode only)
```

This is **the first page-oriented extra result** in evitaDB. All of today's are aggregates over the whole
filtered set, keyed by names from the schema or by the primary keys of *referenced* entities (§3.3). The
missing piece is therefore only plumbing: the sliced keys are not passed from `QueryPlan` into the execution
context today, even though they already exist at the moment of fabrication (`QueryPlan.java:292` against
`:302`). The recommended intervention — setting them onto the `QueryExecutionContext` and adding a getter —
is described in §3.3 and is a condition of P7.

### 8.3 The link to a deterministic snapshot

The producer **must not compute the features again**. It has to receive them from the same computation that
emitted the ordering. Mechanically that is solved by sharing an instance: during execution the relevance
sorter stores the feature vectors of the candidates that got into the set being selected from, and the
producer reads them. Sharing an instance between planning objects is an established pattern in the engine —
the planner passes the same instance of the filter's formula to the producers too (`QueryPlanner.java:684`)
and memoization ensures nothing is computed twice (`AbstractFormula.java:197`–`201`).

Mind a trap: **the feature vectors are kept only for the returned page**, not for all the candidates. Keeping
them for 10⁶ candidates would mean tens of megabytes per query. For an ordinary export that suffices. For
*training* LTR a deeper part of the result is interesting, though — and that is an open question (§12, Q5):
either the export is limited to the page and Sage requests deeper pages separately, or the export gets its
own depth parameter with a hard cap.

### 8.4 Volume limits

A feature vector is of the order of tens of values per entity; at twenty to fifty entities per page it is
single-digit kilobytes. The risk is elsewhere: in **explain mode**, where the composition's breakdown is added
to the values, and in a possible deep export. Recommendation: two levels of detail — compact (feature values
only, the default) and explain (plus the lanes' breakdown) — and a hard cap on the number of entities in the
response, which is rejected with a clear error instead of a silent truncation. A silent truncation is worse
than an error for training data: the model gets trained on a skewed sample and nothing reports it.

### 8.5 The second form of explainability: the processing trace

The feature export of §8.2 answers the question "what number did the document get in lane 3". That is not,
though, the only question relevance tuning asks, and the second this document does not cover anywhere so far.

Verification over the OpenSearch checkout (main, commit `36edc05ac84`, 2026-08-12) distinguishes both forms
clearly. Solr's `[features]` and Vespa's `match-features`, which the research names as precedents, export
**the model's features**. `ProcessorExecutionDetail`
(`server/src/main/java/org/opensearch/search/pipeline/ProcessorExecutionDetail.java:38`) by contrast exports
**a trace of the processing**: for every step it carries a name, a tag, **the duration in milliseconds**, the
input and output data, a **status** and any error (the fields at `:40`–`:46`), and all of it travels over the
network and serializes into the response. "What number did the document get in lane 3" and "which step of the
profile changed the ordering" are two different questions and in tuning both are needed.

For P7 a concrete supplement to §8.2 follows: in **explain mode** the response is to carry, beside the lanes'
breakdown, a short trace of the processing too — which lanes were evaluated, how long it took and with what
status. What is load-bearing are precisely **the duration and the status**, not a mere enumeration: without
them there is no way of telling that an expensive lane was **silently skipped**. And skipping is for us a
built-in, intended phenomenon, not a failure — phase 2 need not run, the boost table may be missing (§6.3,
rule 3 about fail-open) and the proximity lane is not filled in phase 1 at all (§4.2). A fail-open without a
trace is a silent fail-open and that manifests in relevance tuning as an inexplicable ordering nobody can
reproduce. The extent is meanwhile small — single-digit items per query, not per entity — so it fits
comfortably under the volume limits of §8.4.

---

## 9. Annotations of recognized entities

### 9.1 What is to arise

The "offer, do not apply" flow of README §1.3: the first query carries no facet filter, the recognized entity
acts only on relevance, and beside the result the engine returns an annotation "in the query I recognized
'Bosch', it is a reference to a brand with PK 123, the corresponding filter would be …". Only after the user
clicks does the client send a second query with an ordinary filter. The research is very economical about
what of this is new: **no new filtering mechanics arise** — the applied filter is an ordinary `facetHaving`
(`evita_query/…/api/query/filter/FacetHaving.java:164`) inside `userFilter`
(`…/api/query/filter/UserFilter.java:153`), and thereby the existing facet summary semantics including impact
counts is paid for free.

What is new is only that the engine has to **say what it recognized**.

### 9.2 The mechanics

The recognition itself is a dictionary lookup against a gazetteer — the same span-matching mechanics as
multi-word synonyms, one implementation over two artifacts (README §4.6). It therefore belongs in P3 and in
the analysis chain, not in P7. **P7 owns only the way out**: how the result of that lookup gets into the
response.

The producer computes nothing — the lookup happened already for the sake of relevance, because a match on an
annotated entity is one of the feature vector's lanes (README §4.3). The producer therefore merely **reads
what is already finished** and translates it into the response's shape.

What is shared meanwhile are **the inputs, not the producer.** The entity annotations and the feature export
are two `require` blocks and each has its own producer, because the response is keyed by the result's
concrete class (`evita_api/…/requestResponse/EvitaResponse.java:81`) and `fabricate` returns one object
(`…/extraResult/ExtraResultProducer.java:48`). What is common is **where both read from** — the same
structures computed during query evaluation. `findExistingProducer`
(`…/extraResult/ExtraResultPlanningVisitor.java:291`) is in the pipeline precisely for that: it serves to
reuse **computed intermediate results**, not to share a producer's output slot.

The response's shape, in rough outline:

```
RecognizedEntities
  └─ a list of recognitions:
        ├─ the surface form in the query (and its range in the text)
        ├─ the type of binding: a reference to an entity | an attribute value | no schema support
        ├─ the target: the reference's name + PK, or the attribute's name + value (or empty)
        └─ the proposed filtering constraint (serializable, the client can insert it)
```

Three notes on the mechanics, each with a verified reason:

- **The proposed filter is returned as a constraint, not as text.** evitaQL constraints are serializable
  objects and the client can insert them into `userFilter` unchanged. Returning a string to be parsed would
  be a needless extra round and would open an injection surface.
- **A recognition without schema support is returned too.** Category 3 from README §1.3 ("cordless", "hammer
  action") has nothing to filter on, but the client wants to know about it — say in order to highlight it or
  to offer it as a query refinement. Omitting it would be a silent discarding of information.
- **Nothing is removed from the query.** Algolia documents that with it a recognized word is by default
  **not** removed from the query and removal is a separate consequence (README §8, VK14); Typesense has the
  same option as an opt-in. In phase 1 nothing is removed — otherwise the annotation would stop being an
  "offer" and would become a hidden filter, which is exactly what the "offer, do not apply" flow avoids.

### 9.3 A neighbouring, undecided capability: the curated promo layer

The entity annotations neighbour another capability none of the prototype plans has so far and which the
existing solution **actively uses**. It belongs here, because the decision about it affects the response's
shape — i.e. exactly what §9 is about.

The analysis of the existing solution (internal, §4.2 and §6.6, the
e-commerce layer §4.3) describes a mechanism of two parts, and both are functional requirements, not an
implementation detail. An editor assigns a document keywords on which the document is to be shown at the top
of the results. The engine then performs **two separate queries**: a promo query searching only in the
keywords field, with its own cap on the number of items, and a regular query that **explicitly excludes the
documents that already won in the promo phase**, so that they do not appear twice. The results are joined so
that the promo items go at the beginning, and the paging is computed across both. In the research's
terminology it is *curation / pinning*, i.e. merchandising, not relevance — the classification is correct,
but it means that **nowhere is it said where that capability will live**, while a real deployment has it
today.

Three options that have to be decided between, and their cost:

1. **A lane with an infinite weight in the profile.** A pinned document gets in one lane a value no other can
   beat. The cheapest to implement, but it mixes merchandising into the score — i.e. does exactly what the
   research resists — and it cannot do the mechanism's second half: subtracting the promo results from the
   main list and computing the paging across both.
2. **Pinning as an artifact beside the boost table.** The same storage and publication path as in §6.2, only
   with a different semantics of the value (pin at a position, or hide) and with evaluation outside the
   score. It keeps merchandising separated from relevance and is consistent with how Elasticsearch solves it
   — curator rules are a table stored on the server, the triggering values are supplied by the client in
   every request, and the action is pinning or excluding a document.
3. **It stays with the client, which sends two queries.** Nothing is built, but the client application has
   to learn the merging with correct paging — i.e. precisely the work the search library does today and
   which the application should not have to learn again.

**It has to be decided before the gate**, not after it. Variants 2 and 3 namely differ in whether the engine
returns one set of results or two separate groups — and that is the response's shape, i.e. something that
after release changes only by a breaking change in all the external APIs. Recorded as Q8 (§12); the
consequence for the query API and for the response's shape is developed by `query-design.md`.

---

## 10. The realization procedure

The steps are ordered so that each can be verified independently and so that none waits for P3 or P4. The
entry condition of the whole of P7 is a running P1 — specifically the candidate bitmap and reading match
features. Until then work proceeds against a synthetic feature source (§11.3).

**Step 1 — the profile as a data structure and a composition function.** The model of §4.1, validation of the
sum of bit widths, production of the closed composition function at planning time. Without the engine, purely
unit tests: two profiles over the same feature vector give different orderings; a profile with a sum of
widths above 64 bits is rejected at planning time.

**Step 2 — the concurrent sort and top-N selection.** `long[]` against `int[]` in `evita_common` beside the
existing `int[]` variant (§3.2), then the heap and quickselect per §5.2. Verification against a brute-force
sorted array on random data including ties — ties are frequent with a lexicographic score and their stability
is an observable property.

**Step 3 — the relevance sorter.** An implementation of `Sorter` after the pattern of §3.1: the order
translator finds in the filtering formula the node with the match features through `FormulaFinder`, passes it
to the sorter, the sorter performs the pass of §5.1 and the selection of step 2. Here the behaviour on the
prefetch path is decided and **recorded** (§3.2, gap B).

**Step 4 — the boost channel, the inline variant.** First an inline map as two `int[]` arrays (§3.4),
because it needs no storage and unlocks criterion K1. Part of it is the fast path of §6.5 — i.e. selecting a
composition function without the contextual lane, not a condition in the loop.

**Step 5 — criterion K1.** A differential test per §11.1. Only here is P7 defensible for the first time,
because it has proved what it came into being for. Step 6 is not to be started without it.

**Step 6 — the stored artifact.** A collection of entities per §6.2(b): the collection's schema, the
publication path, resolution at planning time, the version into explain, fail-open on a missing table. Here
the real size and cadence on real data is measured for the first time — input for Q1.

**Step 7 — the feature export.** The `require` constraint, the producer, the plumbing of the sliced keys into
the execution context (§3.3, §8.2). The external APIs (gRPC, GraphQL, REST) follow the
`new-external-api-object` skill and **do not belong** in the prototype — P7 is validated through the embedded
API.

**Step 8 — the entity annotations.** Per §9, with its own producer reading the same structures as step 7
(§9.2 — the inputs are shared, not the producer). Until P3 delivers the gazetteer, it is tested against a
manually populated dictionary.

**Step 9 — criterion K2.** Measuring the empty channel per §11.2, over the finished channel from steps 4 and
6. Deliberately after step 6, because "empty" has to include the variant "the table is there, the key is
not" (§6.5).

**Step 10 — the seam for references.** Modelling and validation per §4.5. The filling is zero, but the
profile has to be able to declare and validate the aggregation. Last because it is the only step without an
observable output of its own — it is insurance against a later change of the format.

Steps 1–5 form the **core of P7** and are independently defensible. Steps 6–10 are the build-out; were the
prototype to be shortened, it is shortened from the end, not from the beginning.

---

## 11. The harness and measurement

### 11.1 K1 — a boost lifts a document from a depth (a differential test)

"The boost works" cannot be proved by a document appearing at the top — it could have been there anyway. The
test has to show **a pair**: that with the boost in phase 1 the document reaches the page, and that the same
boost applied as a re-rank over the top-K cannot reach it.

The arrangement:

1. Over P1's structures a query is found under which a specific document ends up deep — the proposed target
   is a rank around 5000, i.e. far below any reasonable K for phase 2.
2. **Arm A (phase 1, the target design):** the boost map contains its PK with a coefficient sufficient for it
   to climb onto the first page with the given profile. The claim is verified **on the query's paged result**,
   not on an internal dump of scores. The internal score is an implementation detail; the criterion speaks
   about what the user will see.
3. **Arm B (control, a re-rank):** the same boost is applied only to the top-K from phase 1. The expected
   result: the document **does not appear** on the page, because it did not get into the top-K.
4. The test's claim is the **difference between A and B**, not the result of A.

Arm B is what makes the test a proof. Without it the test would pass even an implementation applying the
boost after the selection — i.e. exactly the one README §4.3 rejects.

Two conditions that have to be explicit in the test: **which profile it measures with** (the contextual lane's
position fundamentally changes the boost strength needed, §4.4) and **how deep the document is** (a rank of
5000 with K = 1000 is honest, a rank of 1200 with K = 1000 is borderline and the test would be fragile).

### 11.2 K2 — the overhead of an empty channel ≤ 1 ms per 10⁶ candidates

It is measured **as a difference of two arms of one benchmark**, not absolutely. The reason is practical: the
noise of the query JMH harness is by experience of the order of single-digit per cent — the exact number is to
be measured in step 9, not adopted — so an absolute value below 1 ms is indistinguishable from zero in the
noise, whereas the difference of two arms measured in the same run is stable.

The arms:

- **Arm 0 (the base):** phase 1 without a boost channel in the query at all.
- **Arm 1 (an empty channel):** the boost channel is in the query, the table resolves, but the query's key is
  not in it.
- **Arm 2 (a filled channel, control):** the table contains several hundred PKs — it serves to verify that
  the measurement measures anything at all.

Criterion K2 is `arm1 − arm0 ≤ 1 ms` per 10⁶ candidates on one thread. Arm 2 has no threshold, it is merely
reported. The key expectation following from §6.5: **arm 1 is not to differ measurably from arm 0**, because
both are to lead to the same composition function. Were they to differ, it means "empty" is tested inside the
loop and the fast path is not finished.

The operational discipline of measurement follows the repo's established rules — warm-up iterations, a stable
heap size, no concurrent load on the machine. For volumes above 10⁶ candidates the same memory caution
applies as for the other JMH runs in this repo.

### 11.3 The harness before P1 is finished

Steps 1 and 2 of §10 need no index structures at all — they work over the feature vector as over data. Steps
3 and 4 need only **the interface** through which P1 supplies match features. Recommendation: define that
interface narrowly (candidate → feature values) and have, beside the production implementation, a synthetic
one generating features from a deterministic generator. P7 is thereby unblocked before P1 and at the same
time precisely the testing tool steps 1, 2 and 5 need arises — a reproducible corpus with a known ordering in
advance.

The synthetic path must, however, **not serve for measuring K2**. That number has to fall over P1's real
structures and a real candidate set; otherwise the generator's speed would be measured.

### 11.4 Quality

The quality of ranking is not measured at the gate with our own yardstick — README §7 prescribes for that a
side-by-side against a Solr baseline through the existing Sage comparison harness and golden set
(`/www/oss/Sage/docs/analysis/golden-set-analyzer.md`,
`/www/oss/Sage/docs/analysis/search-comparison-final.md`). P7 brings one new capability into it that is worth
noticing: because a profile is optional per query, **the golden set is runnable over several profiles in one
run**. That is a cheap A/B harness and it should be used for deciding O1, instead of the contextual lane's
position being guessed.

---

## 12. Open questions

**Q1 — the granularity of the boost artifact.** Is the artifact the whole table in one piece, or one record
per query key? The cardinality of distinct queries and the table's total size decide. Measure in step 6 on
Sage's real production, do not estimate. Input for the definitive choice between §6.2(a) and §6.2(b).

**Q2 — the swap's cadence and its consequence for transactionality.** Sage today aggregates on demand with a
two-minute cache; Algolia recomputes roughly daily. Publishing once a day tolerates the full transactional
path without a second thought; publishing every two minutes over a large table would mean a permanent stream
of writes into the WAL. A necessary input from Sage's side: **what cadence it actually wants**, and whether
materializing the table is an acceptable change against today's on-demand querying at all.

**Q3 — the contextual lane's position (O1).** P7 does not decide it, it merely makes it measurable (§4.4). The
decision belongs at the gate, on the basis of the golden set, and it should be recorded together with the data
it fell on.

**Q4 — the key's normalization and collisions.** §6.1's recommendation is that the engine owns the
normalization. What remains: is the key the normalized string, or its hash (more compact, but collisions are
silent and manifest as a mysterious boost of a foreign query)? And what to do with queries that merge after
normalization into one key — are their boosts summed, or taken separately?

**Q5 — the feature export's depth.** A page suffices for debugging, but for training LTR a deeper part of the
result is interesting (§8.3). Either limit it to the page and let Sage request deeper pages separately, or
give the export its own depth parameter with a hard cap. The volume of training data Sage needs decides.

**Q6 — the behaviour of relevance on the prefetch path.** Three variants in §3.2; the recommendation is to
suppress the prefetch, but the decision has an impact outside P7 (the planner) and should be confirmed by
somebody who owns the prefetch heuristic.

**Q7 — the interaction of the boost and a later LTR re-rank.** Sage itself notices that a boost changes the
base score, whereas the LTR model was trained on features that did not contain the boost
(`behavioural-ranking-platform.md` §2: "that composition works but is worth measuring rather than assuming").
In phase F3, once the phase 2 LTR re-rank is added, it becomes our problem: either the boost will be one of
the features the model sees, or two independent corrections of the same ordering compose. Recorded now so
that it is not discovered only by measurement.

**Q8 — where the curated promo layer lives.** Three variants and their cost are in §9.3: a lane with an
infinite weight in the profile, pinning as a separate artifact beside the boost table, or two queries on the
client's side. The decision belongs **before the gate P5 → P1 → P2**, because it determines the response's
shape — does the engine return one set, or two separate groups? The input is a product brief (is pinning,
hiding, or both wanted; and is the author an editor, or Sage) and what `query-design.md` decides about the
response's shape. P7 opens the question, because the promo layer touches both the profile and the boost
channel, but does not decide it itself.
