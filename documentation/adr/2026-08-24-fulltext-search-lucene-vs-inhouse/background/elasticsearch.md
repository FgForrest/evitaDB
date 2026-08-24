> **A background study for the research, not a decision.** The document was written on 2026-08-13 as
> input for incorporation into the research (`../research.md`) and the prototype plans; the
> load-bearing findings are already reflected there. It was translated from Czech and moved out of
> `specifications/` into this record on 2026-08-24.

# Elasticsearch — findings for the design of evitaDB's own fulltext engine

> **Source:** local checkout `/www/oss/elasticsearch`, branch `main`, commit `9a100e2d0e41` of
> 2026-08-13. The version in the tree is `9.6.0-SNAPSHOT`, but the last commit finalizes the release
> notes for release 9.5.1 — so it is the development tip just after 9.5.1.
>
> All paths in this document are **relative to `/www/oss/elasticsearch`**.
>
> Context of the brief: the research [`../research.md`](../research.md) (version 2) in the evitaDB
> repository. Elasticsearch is a server built on the Lucene library; Lucene
> itself is covered by the research in §3 and §8. This document therefore concentrates on **what the
> server layer adds above Lucene** — what choices it makes, what it names, what it validates and above
> all what it refuses.

## How to read this document

Every area has a descriptive part with anchors into the source code and closes with a paragraph
**"Consequence for our design"**, which says what follows from the finding for a specific document of
the research. I use the plan abbreviations as the research introduces them: **P1** is the index core
prototype, **P2** transactional maintenance, **P3** the suggester, **P4** proximity re-rank, **P5**
analyzers, **P6** vectors and **P7** rank profiles. Beside them stand two cross-cutting documents:
`schema-design.md` (fulltext configuration in the schema) and `query-design.md` (the query side).

One warning about the overall reading up front. Elasticsearch is a system designed around assumptions
evitaDB does not share: data is split into **shards** (independent Lucene indexes on different nodes),
the index is from the write point of view an **append-only set of immutable segments**, and the score
is computed from **corpus statistics**. Most of the complexity of the server layer described below is a
direct consequence of exactly these three assumptions. Where relevant I point that out explicitly —
because the most valuable finding from Elasticsearch for us often reads "this problem does not arise
for us at all, and therefore we do not need its solution either".

---

## 1. The mapping (schema) and the cost of changing it

### 1.1 A mapping is a type description of fields that is merged, not overwritten

Elasticsearch calls an index's schema a **mapping**. Unlike evitaDB, where the schema is changed by
mutations, in Elasticsearch a mapping is always **merged**: the client sends a new mapping fragment and
the server joins it with what already exists. The merge either passes or ends in an error — a silent
overwrite never happens.

The entry point is `MapperService.merge(...)`
(`server/src/main/java/org/elasticsearch/index/mapper/MapperService.java`, overloads on lines 426, 438
and 572). Every merge carries a **reason**, the enum `MergeReason` defined in the same place on line
64. The values are:

- `MAPPING_AUTO_UPDATE_PREFLIGHT` — a preliminary check before sending a dynamic mapping update to the
  master node,
- `MAPPING_AUTO_UPDATE` — a dynamic mapping update (the server itself added a field it found in an
  indexed document),
- `MAPPING_UPDATE` — an explicit creation or change of the mapping by the client,
- `INDEX_TEMPLATE` — merging the mapping from a composable index template,
- `MAPPING_RECOVERY` — recovery of an existing mapping after a restart or a shard relocation.

That enum is instructive in itself: **the same operation behaves differently depending on why it is
happening.** Recovery after a restart has to pass even if today's validation would reject the mapping in
question — otherwise the node would not start after an upgrade. Line 673 in `MapperService` does this
explicitly: validation is called with a flag that is `false` precisely for `MAPPING_RECOVERY`.

### 1.2 The cost of a change is declared at each parameter separately

This is the most valuable mechanism of the whole area. Every field parameter in Elasticsearch is an
object of class `FieldMapper.Parameter` and on its construction the flag `updateable` must be given —
that is, "may this parameter be changed on an existing field?". The documentation of the parameter
right in the code reads "whether the parameter can be updated with a new value during a mapping update"
(`server/src/main/java/org/elasticsearch/index/mapper/FieldMapper.java:1006`).

The enforcement is then a one-liner and very elegant (`FieldMapper.java:1030`): if the parameter is
`updateable`, the merge validator always returns `true`; if it is not, the validator requires
**equality of the old and the new value**. A mismatch is neither discarded nor logged — it is written
into the accumulator `FieldMapper.Conflicts` (`FieldMapper.java:1874`), which collects conflicts and in
the method `check()` (line 1891) throws them all at once as a single `IllegalArgumentException` with a
message of the form:

```
Mapper for [<field>] conflicts with existing mapper:
	Cannot update parameter [<parameter>] from [<old>] to [<new>]
```

That accumulator deserves attention: the error **does not fall out on the first conflict**, it collects
all of them and reports them at once. From one attempt the user therefore knows about all the problems,
not just the first.

The concrete division of a text field's parameters (`TextFieldMapper.java`, the list of parameters is
together in the method `getParameters()` on line 414) shows exactly where the dividing line lies:

| Parameter | Line | `updateable` | What it means in practice |
|---|---|---|---|
| `analyzer` (indexing) | `TextParams.java:47` | **no** (for modern indexes) | a change = reindex |
| `search_analyzer` | `TextParams.java:61` | **yes** | changes at runtime |
| `search_quote_analyzer` | `TextParams.java:78` | **yes** | changes at runtime |
| `position_increment_gap` | `TextParams.java:93` | **no** | affects stored positions |
| `index_phrases` | `TextFieldMapper.java:298` | **no** | creates an auxiliary index |
| `index_prefixes` | `TextFieldMapper.java:304` | **no** | creates an auxiliary index |
| `fielddata` | `TextFieldMapper.java:281` | **yes** | a runtime structure only |
| `fielddata_frequency_filter` | `TextFieldMapper.java:282` | **yes** | a runtime structure only |
| `eager_global_ordinals` | `TextFieldMapper.java:291` | **yes** | only a precomputation strategy |

The pattern is absolutely consistent and can be stated in one sentence: **changeable is exactly what
does not affect the bytes written into the index.** The indexing analyzer, the positional gap between a
field's values and the auxiliary phrase and prefix indexes write data — they are locked. The search
analyzer, fielddata and eager global ordinals are applied at read time — they are free.

Beware one subtlety with the indexing analyzer: `updateable` for it is not the constant `false` but the
expression `indexCreatedVersion.isLegacyIndexVersion()` (`TextParams.java:49`). For old indexes the
change is therefore permitted — not because it would be correct, but because on old indexes it
historically passed and rejecting it today would mean such an index could not be loaded. That is
instructive in itself: once you let through a change you should not have let through, you carry it in
your validation forever.

A change of field type (for example from `text` to `keyword`) is not handled by a parameter but by a
hard check in `FieldMapper.java:565` and `570` with the message `mapper [...] cannot be changed from
type [...] to [...]`. No permitting flag exists for it.

### 1.3 Dynamic mapping and why it forced limits

Elasticsearch can **infer the schema from data**. The behaviour is governed by the four-valued enum
`ObjectMapper.Dynamic` (`server/src/main/java/org/elasticsearch/index/mapper/ObjectMapper.java:98`):

- `TRUE` (the default, `ObjectMapper.java:95`) — an unknown field is added into the mapping and indexed,
- `FALSE` — an unknown field is stored in the document source but not indexed and does not enter the
  mapping,
- `STRICT` — an unknown field is an error; the parser throws `StrictDynamicMappingException`
  (`server/src/main/java/org/elasticsearch/index/mapper/DocumentParser.java`),
- `RUNTIME` — the field is added as a **runtime field**, i.e. computed at read time from the source,
  without any index structure.

The rules by which the inferred type is chosen can be governed by **dynamic templates**
(`DynamicTemplate.java`) — matching by name, by path or by detected type.

More interesting than the feature itself is, however, its **cost**, which Elasticsearch pays with a set
of limits concentrated in `MapperService.java`, lines 106–180:

- `index.mapping.total_fields.limit`, default **1000** (line 151),
- `index.mapping.nested_fields.limit`, default 100 (line 108; historically 50),
- `index.mapping.nested_parents.limit`, default 50 (line 122),
- `index.mapping.nested_objects.limit`, default 10,000 per document (line 135),
- `index.mapping.array_objects.limit`, default 50,000 (line 143),
- `index.mapping.depth.limit` (line 179).

To that a newer valve was added, `index.mapping.total_fields.ignore_dynamic_beyond_limit` (line 163),
which on exceeding the limit **discards** dynamically inferred fields **instead of rejecting the whole
document**. It switches itself on for the `LOGSDB` and `LOGSDB_COLUMNAR` modes (line 167) — that is,
precisely where the data is log data and the schema really is unbounded.

That trio — dynamic inference, a hard limit on the number of fields, and a valve that silently discards
on exceeding the limit — is entirely forced by a single decision: allowing data to define the schema. A
mapping lives in the **cluster state**, replicated to all nodes; unbounded growth of fields is
therefore an operational risk to the whole cluster, not just to one index.

### 1.4 Reindex as the sanctioned escape route

Because changing the indexing analyzer or a field type is not possible, there has to be a way of doing
it by a detour. That is the **Reindex API**, the module `modules/reindex/` (entry points
`RestReindexAction`, `Reindexer`, validation `ReindexValidator`). The mechanics are conceptually
simple: reading is paged from the source index (`PitPaginatedHitSource`,
`ClientScrollablePaginatedHitSource` — i.e. via a point-in-time snapshot or a scroll) and writing is in
bulk into the target.

What is worth noting is how much infrastructure had to arise around it: a separate module
`modules/reindex-management/` with the actions `ListReindex`, `GetReindex` and `CancelReindex`, plus
rethrottling at runtime (`RestReindexRethrottleAction`), metrics (`ReindexMetrics`), resumption of an
interrupted operation (`AbstractResumeBulkByPaginatedSearchAction`) and a cursor (`PaginationCursor`).
Reindex is in fact a **long-running managed task**, not an API call. That is the realistic price of
reindexing in a system where the index cannot be derived from another source.

### Consequence for our design

This is the strongest direct input into the document `schema-design.md`. Its own finding says that "a
mechanism for reindexing on a schema change does not exist and the change passes silently" (§4.3, §7).
Elasticsearch is the exact opposite of that and offers a ready-made, proven pattern worth adopting in
three steps.

First, **the cost of a change belongs at the parameter, not in the documentation**. The pattern
`Parameter(..., updateable, ...)` with an automatic merge validator is cheap and mechanically
unforgettable: whoever adds a new fulltext configuration element to the schema must decide right away
whether it may be changed. The equivalent list for us looks like this — and it is worth writing it into
`schema-design.md` explicitly, because the decision is the same as in Elasticsearch and for the same
reason: **locked** is everything that determines the content of the term dictionary, postings or the
impact sidecar — that is, the choice of analyzer for indexing, the set of indexed fields, the pivot of
length normalization and the tf saturation function baked into the impact byte (§4.2 of the research);
**free** is everything applied at query time — field weights, the choice and configuration of the rank
profile, typo tolerance thresholds, the synonym dictionary and the boost map.

Second, **a conflict should be accumulated, not thrown on the first find**. The pattern
`FieldMapper.Conflicts` is a few dozen lines and gives an incomparably better error message. In evitaDB
it moreover sits well on the existing style of schema validation.

Third — and this is the most important — **there must be a named path for locked changes.**
Elasticsearch has the Reindex API and paid for it with two modules. We are in a different position and
that is our advantage: fulltext structures are a **deterministic function of the catalog's data**,
which is already entirely inside evitaDB. A rebuild therefore does not need to copy documents between
two indexes — it is a local recomputation from our own storage. That is in effect a cheaper variant of
Reindex and `schema-design.md` ought to name it as the solution to its own open hole.

Fourth, regarding dynamic mapping: evitaDB has a schema-first model and **none of what Elasticsearch
forced upon itself around `total_fields.limit` need apply**. The opposite lesson does suggest itself,
though. If a proposal "fulltext-index all text attributes automatically" ever falls, the limit of 1000
fields and the `ignore_dynamic_beyond_limit` valve are evidence of where such a path leads. Fulltext
indexability should remain an **explicit opt-in flag in the schema** — which is, incidentally, exactly
what the research plans for searchable associated data (open question O6).

---

## 2. Analysis chains

### 2.1 The trio of analyzers and why there are three

A text field in Elasticsearch has three slots for an analyzer, defined together in the class
`TextParams.Analyzers` (`server/src/main/java/org/elasticsearch/index/mapper/TextParams.java:33`):

- **`analyzer`** — used at indexing time; it is at the same time the default value for both of the
  following,
- **`search_analyzer`** — used on the query text; the default is the value of `analyzer`, but if
  `analyzer` was not explicitly set, a named analyzer `default_search` is looked up
  (`TextParams.java:66–72`),
- **`search_quote_analyzer`** — used on **query text given in quotation marks**, i.e. on a phrase
  query; the default is `search_analyzer` (`TextParams.java:82–89`).

The third slot is inconspicuous but conceptually important. It exists because of **stopwords and
synonyms in phrases**: an analyzer that discards stopwords damages the phrase "the who" or "to be or not
to be", because nothing remains of it or something else remains. A phrase query can therefore go through
a different chain than a free query over the same field. That is a purely server-side invention, no such
concept exists in Lucene.

### 2.2 AnalysisMode — the mechanical separation of index and query components

This is in my view **the single most valuable finding of the whole document** and it bears directly on
the research's claim about "hot-swappable artifacts" (§4.6).

Elasticsearch introduces the enum `AnalysisMode`
(`server/src/main/java/org/elasticsearch/index/analysis/AnalysisMode.java`) with three values:
`INDEX_TIME`, `SEARCH_TIME` and `ALL`. Every component of an analysis chain (a token filter, a char
filter) declares its mode and an analyzer derives its own mode by **merging the modes of all its
components** — the `merge` method is defined separately for every value of the enum. Merging
`INDEX_TIME` with `SEARCH_TIME` **throws an exception** (lines 26 and 36): a chain that mixes an index
and a query component cannot come into being.

The connection to the mapping is then direct: the parameter `analyzer` has the validator
`a -> a.checkAllowedInMode(AnalysisMode.INDEX_TIME)` (`TextParams.java:60`), whereas `search_analyzer`
and `search_quote_analyzer` have `checkAllowedInMode(AnalysisMode.SEARCH_TIME)` (lines 77 and 92).

Now the essential part. The synonym filter has a configuration option `updateable` and in
`SynonymTokenFilterFactory`
(`modules/analysis-common/.../analysis/common/SynonymTokenFilterFactory.java:199`) it reads like this:

```java
boolean updateable = settings.getAsBoolean("updateable", false);
...
this.analysisMode = updateable ? AnalysisMode.SEARCH_TIME : AnalysisMode.ALL;
```

In other words: **declaring a dictionary replaceable at runtime automatically turns it into a component
that cannot be used at indexing time.** Not by convention, not by documentation — by the analyzers' type
system, which rejects the attempt. That guarantees that a replaceable artifact never could have been
baked into the index, and its replacement therefore cannot make the index diverge from the data.

### 2.3 Reload at runtime and everything it had to solve

The replacement itself runs through the action `_reload_search_analyzers`
(`server/.../action/admin/indices/analyze/TransportReloadAnalyzersAction.java`, the REST layer
`RestReloadAnalyzersAction.java`) over instances of `ReloadableCustomAnalyzer`
(`server/.../index/analysis/ReloadableCustomAnalyzer.java`). That class re-checks in its constructor
that it received components in `SEARCH_TIME` mode, and fails otherwise (line 76).

The comments in that class are unexpectedly instructive, because they describe problems that arise as
soon as an artifact is shared and mutable. An analyzer instance is **shared across indexes** on a node,
the reload is `synchronized` so that the same instance is not rebuilt in parallel (line 202), and it
carries a `lastReloadToken` — a token of the last reload attempt. If a reload failed, the next sharing
request enters the method again only in order to **repeat the same error** rather than silently succeed
(lines 168–180 and 239–244). Beside that there is a `closed` flag for the case where the last user
releases the instance during an ongoing rebuild (line 160).

Beside the per-index configuration stands a central **Synonyms API** —
`server/src/main/java/org/elasticsearch/synonyms/SynonymsManagementAPIService.java` — which holds named
synonym sets in a system index and on their change triggers a reload of the affected analyzers itself.

### 2.4 Normalizers for keyword fields

A field of type `keyword` (unanalyzed, the whole value is indexed as one term) has no analyzer, but it
does have a **normalizer** — the parameter `normalizer` in `KeywordFieldMapper.java:291`. It is a chain
of char filters and token filters that **must not contain a tokenizer**; it always produces exactly one
token. It serves to make a `keyword` field tolerate letter case or diacritics without falling apart into
words.

Two concrete observations are worth mentioning. First, in `KeywordFieldMapper.java:512` the normalizer
is used **simultaneously as the `searchAnalyzer` and as the `quoteAnalyzer`** — normalizing the query
and normalizing the value are by definition the same code, which is the only way of guaranteeing that
the values will meet. Second, there is a supplementary parameter `split_queries_on_whitespace` (line
241) which on the **query side** inserts splitting by whitespace (line 513), so that the query "red
jacket" can hit two different keyword values. That is a nice example of deliberate asymmetry between
index and query, which is safe precisely because it concerns only the query side.

### Consequence for our design

Into plan **P5 (analyzers)** and into `schema-design.md` I would carry three things from this.

**Introduce an analogue of `AnalysisMode` and do not rely on discipline.** The research claims in §4.6
that the synonym dictionary and the entity dictionary will be "hot-swappable data artifacts, not schema
mutations, with no impact on index structures". That claim is correct and Elasticsearch confirms it —
but it also shows that without enforcement it is fragile. It suffices for somebody to wire synonyms into
the indexing chain once and replacing the dictionary at runtime starts silently diverging the index from
the data; the error manifests as inexplicably missing results, not as an exception. I therefore
recommend that every component of an analysis chain in evitaDB carries a mode flag and that the chain
**refuses to be assembled** if it mixes an index and a query component. That corresponds exactly to the
rule in `CLAUDE.md` about defensive design: an unexpected state must throw an exception immediately.

**Reckon with three slots, not two.** The research states in §4.4 that `attributeMatches` "performs the
analysis of the query (the same analyzer as at indexing time)". That is fine for the first round, but
Elasticsearch has three slots for two concrete reasons, both of which concern us. The difference between
the index and query analyzers is necessary as soon as synonyms come into play (expansion belongs on the
query side — VK12 of the research about Lucene confirms it too). The difference between the phrase and
the free analyzer is necessary as soon as stopwords are addressed — and that concerns us in the CMS
profile (Z8), where the texts are long and stopwords make sense. I therefore recommend at least
**allowing by design** for a separate query analyzer already in P5, even if in F1 both values point to
the same object; a later split is otherwise a change of a locked parameter, i.e. reindexing.

**A normalizer for unanalyzed fields is a concept that in effect already exists in evitaDB.** Today's
`attributeContains` works over NFD normalization (the research, open question O3). That is the same
thing Elasticsearch calls a normalizer, and the same rule applies to us: **normalizing the query and
normalizing the value must be the same code**, otherwise the values will not meet. The risk of the two
paths diverging when fulltext is introduced is stated in P5 as a criterion ("`attributeContains`
unchanged in behaviour") and this finding gives it a concrete shape: share the implementation, do not
duplicate it.

---

## 3. Scoring across shards: `query_then_fetch` versus `dfs_query_then_fetch`

I put this area right after the mapping, because it is a **direct answer to the problem "score
instability across replicas"** that the research in §0 and §2.1 marks as one of the three hardest open
problems of the first version — and which dissolves with cascade ranking.

### 3.1 Two modes and which is the default

The enum `SearchType` (`server/src/main/java/org/elasticsearch/action/search/SearchType.java`) has only
two live values today (two others were removed, which the code evidences with a comment at the unused
identifiers 2 and 3):

- `QUERY_THEN_FETCH` — the query goes to all shards, each returns only identifiers and scores, the
  coordinator merges them and only then requests the content of the documents from the relevant shards,
- `DFS_QUERY_THEN_FETCH` — the same, but preceded by a **scatter phase that computes distributed term
  frequencies for the sake of more accurate scoring** (I paraphrase the JavaDoc on lines 18–21).

And now the essential part: **the default is `QUERY_THEN_FETCH`**, the constant `SearchType.DEFAULT` on
line 36. A distributed-consistent score is therefore **opt-in**.

### 3.2 What that extra phase costs

The implementation is in `server/src/main/java/org/elasticsearch/search/dfs/DfsPhase.java`. The method
`execute` (line 60) wraps the searcher so that it intercepts calls to `termStatistics` (line 87) and
`collectionStatistics` (line 106), and collects them into the maps `Map<Term, TermStatistics>` and
`Map<String, CollectionStatistics>` (lines 82–83). Those are then sent over the network as a
`DfsSearchResult`, the coordinator sums them into `AggregatedDfs`
(`server/src/main/java/org/elasticsearch/search/dfs/AggregatedDfs.java:23`) and sends them back so that
all shards score against the same numbers.

The cost is therefore: **an entire extra round of network communication before the query proper**, and
the transfer of a statistics map whose size grows with the number of distinct terms in the query
(serialization on lines 29–40 of `AggregatedDfs`). It is paid for on every query anew.

The same file incidentally shows that the DFS phase meanwhile got a second job unrelated to term
frequencies — `executeKnnVectorQuery` (`DfsPhase.java:215`). Vector queries need a global ordering of
candidates from all shards, so they hung themselves on the single existing phase that already did an
extra round. I return to that in the area about vectors.

### 3.3 Why it came about at all

The answer is simple and key for us: **BM25 is a function of the corpus.** The document frequency
(`docFreq`) of a given term and the average field length are computed from what the shard sees — and
every shard sees a different subset of documents. Two documents with the same content therefore get
different scores depending on which shard they lie on. The whole DFS phase exists **solely in order to
cancel that consequence** — and it is switched off, because it is expensive.

Elasticsearch has, beside it, a second and even cheaper workaround: the `preference` parameter on the
search request (`server/src/main/java/org/elasticsearch/action/search/SearchRequest.java:93`, the setter
on line 607), by which the client pins the query to a specific shard copy. Its JavaDoc (lines 602–606)
is extraordinarily eloquent in this context:

> Sets the preference to execute the search. Defaults to randomize across shards. Can be set to
> `_local` to prefer local shards or a custom value, which guarantees that the same order will
> be used across different requests.

It is worth reading those two statements carefully. The default behaviour **randomizes across
replicas**, and a custom `preference` value is what first **guarantees the same order across
requests**. In other words: Elasticsearch admits in the documentation of its own API that without
pinning to a replica the order of results between two identical queries is not guaranteed to be the
same. `preference` does not unify the score — it merely ensures that **the same user sees an order
consistent with itself**. That is an admission that the underlying problem cannot be solved in a
distributed arrangement with a corpus score, only hidden.

### Consequence for our design

This is a hard, code-evidenced **argument for §2.1 of the research**, and I recommend adding it there as
a named finding, because it is stronger than the present formulation.

The research today says that with cascade ranking "the questions of the corpus, statistics drift vs.
snapshot and score instability across replicas dissolve", and derives that from Algolia, Meilisearch and
Typesense not using corpus statistics. Elasticsearch supplies **evidence from the opposite side**: an
engine that does use corpus statistics had to build a whole separate distributed phase because of them —
and then **switch it off by default**, because it does not pay off. Elasticsearch therefore returns by
default a score that it itself knows is not consistent across shards. A formulation for the research
could read: *choosing to work without corpus statistics does not merely eliminate a theoretical
inconvenience — it eliminates a whole infrastructure that the largest deployed engine in this category
had to acquire because of it and still leaves switched off.*

For us one concrete design conclusion also follows towards the planned **BM25F in phase F3** (§4.2,
motivated by the CMS profile Z8). The research rightly writes that `docFreq` is free for us, because it
is the cardinality of the postings bitmap, and that a configured pivot will replace `avgFieldLength`.
Elasticsearch gives that a second, independent argument: **the problem is not computing a corpus
statistic, the problem is keeping it consistent across units that are evaluated independently.** As long
as fulltext structures live exclusively in `GlobalEntityIndex` (§4.1), there is exactly one unit and the
problem does not arise. I recommend stating this condition explicitly in `README` §4.2 at BM25F as an
**invariant**, not as a consequence: the cardinality of postings may serve as `docFreq` precisely
because postings are never split into parts scored independently. Should a proposal ever fall to
replicate or split fulltext structures by scope or by reduced indexes, this is exactly the place where
BM25F would silently diverge — and `DfsPhase` is an illustration of what would then have to be built.

For **P1** a small but concrete testable property follows from it: a document's score must be
demonstrably independent of what the candidate set is. That is an easy test (the same document, two
different must-match filters, the same score) and it is exactly the property Elasticsearch does not
have.

---

## 4. Ranking and its configuration

### 4.1 BM25 as the default, but replaceable per field

The scoring function in Elasticsearch is called a **similarity** and is managed by
`server/src/main/java/org/elasticsearch/index/similarity/SimilarityService.java`. The default is the
constant `DEFAULT_SIMILARITY = "BM25"` (line 41), specifically `LegacyBM25Similarity` with the
parameters `k1` and `b`. Beside it several built-in alternatives are registered
(`SimilarityProviders.java`) and — which is more interesting — a **scripted similarity**
(`ScriptedSimilarity.java`, provider `ScriptedSimilarityProvider.java`), where the user writes the
scoring formula themselves.

What matters is that a similarity is configured **per field**, as the parameter `similarity`
(`TextFieldMapper.java:276`). Different fields of the same document may therefore score differently.

Also interesting is the class `NonNegativeScoresSimilarity.java`, a wrapper that watches that a score
does not come out negative. It exists because the rest of the system — early termination of the search
in particular — builds on scores being non-negative, and a scripted similarity may violate it.

### 4.2 `rank_feature`: a numeric signal with the scoring function chosen only in the query

This is the most relevant mechanism of the whole area for our rank profile.

The field type `rank_feature`
(`modules/mapper-extras/.../index/mapper/extras/RankFeatureFieldMapper.java`) stores one positive number
per document — popularity, rating, PageRank, margin. It is stored through the Lucene class
`FeatureField` (line 223), which encodes the value into a term frequency. Beside it there is
`rank_features` (plural), which holds a whole map name → value in one field.

The decisive detail is **where the scoring function is chosen**. Only one thing is chosen in the
mapping: `positive_score_impact` (line 52), i.e. whether a higher value means a better or a worse
document — and that is `updateable = false`, because it is reflected in the stored value (line 219
inverts the value when `false`). **The shape of the function itself is chosen only in the query**, in
the class `RankFeatureQueryBuilder.ScoreFunction`, which has four implementations:

- `Log` — the score is `log(scalingFactor + value)` (line 55),
- `Saturation` — a saturating function with a `pivot` parameter, **the default choice** (lines 36 and
  118),
- `Sigmoid` — with the parameters `pivot` and `exp` (line 191),
- `Linear` — a plain linear function (line 253).

The index therefore holds the **raw value** and the rank profile (here: the query) decides how to
convert it into a contribution to the score. If saturation with a pivot of 20 turns out to give worse
results than a logarithm, the query changes — not the index.

### 4.3 `function_score` and `script_score`: a scoring function assembled in the query body

Beside an indexed signal of the `rank_feature` type, Elasticsearch also has a path where the client
assembles the whole shape of the score directly in the query. The package is
`server/src/main/java/org/elasticsearch/index/query/functionscore/`.

`FunctionScoreQueryBuilder` wraps an arbitrary query with a set of **scoring functions**, each
optionally with its own filter (a function then applies only to documents satisfying the filter). The
catalog of functions is in `ScoreFunctionBuilders.java`:

- **decay functions** — `GaussDecayFunctionBuilder`, `LinearDecayFunctionBuilder` and
  `ExponentialDecayFunctionBuilder` over the common interface `DecayFunction`; typically "the older or
  more distant, the less relevant",
- **`FieldValueFactorFunctionBuilder`** — a score derived directly from a field's numeric value,
- **`RandomScoreFunctionBuilder`** — a pseudorandom score with a seed (useful for evenly rotating an
  offer),
- **`ScriptScoreFunctionBuilder`** — an arbitrary script,
- **`WeightBuilder`** — a plain constant.

Composition is governed by two independent choices (`FunctionScoreQueryBuilder.java:64–65`):
`score_mode` says how **individual functions** combine with each other (default `MULTIPLY`), and
`boost_mode` says how the result combines with the **score of the original query** (default likewise
`MULTIPLY`, type `CombineFunction`). To that belong the safeguards `maxBoost` (line 69) and `minScore`
(line 75).

The simpler sibling `ScriptScoreQueryBuilder` (same place) simply replaces the score with the result of
a script.

### 4.4 Rescore: the second phase, and why its window is tricky

Elasticsearch has the classic two-phase architecture: a cheap query over everything, an expensive
re-scoring of the top-K. The package is `server/src/main/java/org/elasticsearch/search/rescore/`, the
main classes `RescorePhase`, `QueryRescorer` (re-scores with another query function) and
`ScriptRescorer` (re-scores with a script).

The window size is the parameter `window_size` (`RescorerBuilder.java:42`) and its default value is
**`DEFAULT_WINDOW_SIZE = 10`** (line 38) — remarkably small. For some rescorer types it is moreover
mandatory and omitting it is a parse error (line 113).

Two properties of that window are worth naming explicitly, because they are classic sources of surprise.
Both follow directly from **where in the course of the query the re-scoring runs**: `RescorePhase.execute`
is called by `QueryPhase` (`server/.../search/query/QueryPhase.java`), i.e. **a phase running on an
individual shard**, and it works over `context.queryResult().topDocs()` (`RescorePhase.java:46`) — over
the results of *that one shard*, before the merge at the coordinator.

1. **The window is applied on every shard separately.** The top-`window_size` results of one shard are
   re-scored, and only then are the results merged. The resulting order therefore depends on the number
   of shards — which is another case where the answer is a function of cluster topology, not just of
   data.
2. **Paging has to fit into the window.** Because re-scoring touches only the first `window_size`
   documents, all the others carry their score from the first phase. As soon as `from + size` exceeds
   `window_size`, re-scored documents mix with non-re-scored ones on the further pages and the order
   across that boundary stops making sense.

### 4.5 Retrievers: a named composition instead of a flat query body

The newer layer `server/src/main/java/org/elasticsearch/search/retriever/` introduces **retrievers** —
tree-composed sources of results. The basic building blocks are `StandardRetrieverBuilder` (an ordinary
query), `KnnRetrieverBuilder` (vector search), `RescorerRetrieverBuilder` (re-scoring as a node of the
tree) and the abstract `CompoundRetrieverBuilder` for nodes merging several inputs. Curator rules fit
into this too, see `PinnedRetrieverBuilder` in `x-pack/plugin/search-business-rules/`.

The most interesting compound retriever is RRF, i.e. **Reciprocal Rank Fusion**, in the module
`x-pack/plugin/rank-rrf/`. The core of the fusion is a single line (`RRFRetrieverBuilder.java:223`):

```java
value.score += this.weights[findex] * (1.0f / (rankConstant + frank));
```

Every "leg" of the search contributes the reciprocal of the document's rank in its result, shifted by the
constant `rank_constant` and weighted by the leg's weight. The scores of the individual legs **do not
enter the computation at all** — only the ranks. That is the whole point of RRF: to merge results whose
scores are incommensurable (BM25 versus cosine similarity), without any calibration.

Retrievers have their own analogue of the window — `rank_window_size`
(`RRFRetrieverBuilder.java:80`), i.e. how many results from each leg enter the fusion; the truncation at
the end is on line 246.

### 4.6 Learning-to-rank is in the repository

The research mentions the LTR feature/model store for Solr. Elasticsearch has its own analogue and it is
worth recording **what form it chose**: LTR is implemented as a **rescorer**, not as an alternative
scoring function. The package is
`x-pack/plugin/ml/src/main/java/org/elasticsearch/xpack/ml/inference/ltr/` with the classes
`LearningToRankRescorerBuilder`, `LearningToRankRescorer`, `LearningToRankRescorerContext` and
`LearningToRankService`.

Feature extraction is declarative: `QueryExtractorBuilder`
(`x-pack/plugin/core/.../ml/inference/trainedmodel/ltr/QueryExtractorBuilder.java`) describes a feature
**as a query whose score is the value of the feature**. The model itself is stored as a trained machine
learning model and the interface `LearningToRankFeatureExtractorBuilder` is extensible.

That choice is instructive: LTR did not get into the first phase of scoring but stayed in the second —
exactly where our research places it too (§4.3, "next in line is a behavioural / LTR model learned in
Sage, applied over the feature vectors of the top-K"). An independent confirmation of the same decision.

### Consequence for our design

For plan **P7 (rank profiles, boost channel, feature export)** there are several concrete confirmations
here and one warning.

**Confirmation of the division "the index holds the raw value, the profile chooses the function".** The
`rank_feature` mechanism is exactly the pattern the research proposes in §4.2 for the impact byte and
field weights: store the value, choose the function at query time. Elasticsearch thereby evidences that
this division pays off in practice — and shows its limit too. Whatever is **necessarily** reflected in
the stored value (for them `positive_score_impact`, for us the tf saturation and the pivot of length
normalization baked into the impact byte) stays locked. I recommend naming this boundary explicitly in
`p7-rank-profiles-and-boost-channel.md`, because it is easy to overlook: **the impact byte is the only
place where our design makes an irreversible decision about scoring already at indexing time.**
Everything else in the rank profile is free, this is not. The set of four functions
`Log`/`Saturation`/`Sigmoid`/`Linear` is moreover a good default catalog for the contextual lane 6 of the
rank profile — there is no need to invent our own.

**Evidence for the second pole of the state of the art.** §4.3 of the research builds the whole rank
profile design on the contrast of two poles: cascade-as-architecture (Algolia, Meilisearch, Typesense)
against a **configurable scoring function over a feature vector**, whose examples the research names as
"Vespa rank profiles, Solr/ES". That mention of Elasticsearch had no support in source code until now —
`function_score` (section 4.3) supplies it, and in a stronger form than the research assumes.
Elasticsearch does not merely have a configurable function; it has **two independent axes of
composition** (`score_mode` among the functions, `boost_mode` against the query's score), the ability to
give each function its own filter, and the safeguards `maxBoost` and `minScore`. For
`p7-rank-profiles-and-boost-channel.md` two concrete things follow. First, **a function with its own
filter** is exactly the shape curator and merchandising logic needs ("in this category lift the in-stock
ones"), and in our model it is extraordinarily cheap, because the filter is a bitmap we already know how
to compute. Second, separating "how the functions compose with each other" from "how the result meets
the match score" is a distinction our lexicographic composite does not make at all today — all the lanes
are packed into a single `long`. As long as the default profile is a cascade, that is fine; but as soon
as a profile with weights instead of lexicographic order arrives, this pair of axes is what will need to
be named. It is worth allowing for it in the profile design before the first non-trivial requirement
forces it.

**Confirmation that RRF needs no score calibration.** §5.5 of the research proposes RRF for the fusion of
the text and vector legs. The one-line implementation above confirms it in the strongest possible form:
the legs' scores do not enter the formula at all. For us that is important because our text leg returns a
**64-bit lexicographic composite**, a number that cannot be compared with cosine similarity even after
normalization. RRF is therefore not merely a suitable choice — it is in effect the only correct choice,
and `p6-vector-spike.md` should state that as a reason, not as a preference.

**A warning about the second phase's window, directed at open question O2.** The research proposes
K = 1000 for the second phase and marks the behaviour of deep paging as open ("document it vs. compute it
out"). Elasticsearch demonstrates both traps at once here. The default window of 10 is so small that the
user gets a re-scored first page and nothing beyond — and because the window is moreover applied per
shard, the result is not even reproducible when the topology changes. Our position is better (one unit of
evaluation, so the second trap falls away), but the first holds unchanged: **the K boundary is visible in
the result and manifests as a break in the order exactly at `from + size = K`.** I recommend that P4
measure this break as a separate criterion, not only latency, and that `query-design.md` describe the
behaviour beyond the K boundary as a documented property — the way Vespa does with `rerank-count` and as
the research in §4.3 already assumes.

**A recommendation about the API's shape.** Retrievers show a way of expressing "the result is composed
of several sources" without the query body turning into a flat heap of mutually interacting options. For
`query-design.md` it is relevant at the moment when a vector leg and fusion arrive beside `relevance()`.
At the same time it is honest to add that Elasticsearch arrived at retrievers only after years with a
flat shape and now carries both — which is an argument for thinking composition through earlier, not
later.

---

## 5. The transactional and near-real-time model

### 5.1 Durability and visibility are two different things in Elasticsearch

This is the most important structural difference from evitaDB and it is worth stating precisely.

**Durability** is ensured by the translog (the shard's transaction log,
`server/src/main/java/org/elasticsearch/index/translog/`). It is governed by the setting
`index.translog.durability` (`IndexSettings.java:117`) with the two-valued enum `Translog.Durability`
(`Translog.java:2115`):

- `REQUEST` — **the default** (`IndexSettings.java:120`); the translog is fsynced after every write
  request,
- `ASYNC` — the translog is fsynced after the time interval `index.translog.sync_interval`
  (`IndexSettings.java:103`).

**Visibility**, by contrast, is ensured by a **refresh** — opening a new Lucene reader over the newly
created segments. It is governed by `index.refresh_interval` (`IndexSettings.java:320`) with a default
value of **1 second** (`DEFAULT_REFRESH_INTERVAL`, line 311; in stateless mode 5 seconds, line 318).

A written and fsynced document is therefore **not visible in search** until a refresh happens. Hence the
term *near-real-time*: not "at once", but "within a second".

### 5.2 Realtime GET as a workaround for the same property

Elasticsearch does not leave that inconsistency entirely unaddressed, but it addresses it **only for
access by identifier**. The method `InternalEngine.get(...)`
(`server/src/main/java/org/elasticsearch/index/engine/InternalEngine.java:925`) on `get.realtime()`
(line 933) reaches into `realtimeGetUnderLock` (line 960), which, when it finds the document is not yet
in an open reader, **reads it straight from the translog** — `getFromTranslog` (lines 886 and 1017). The
engine even keeps its own counter `translogGetCount` for this (line 883). In some cases it forces a
refresh instead: `refreshIfNeeded(REAL_TIME_GET_REFRESH_SOURCE, ...)` (line 1029).

For **search no such path exists**, and that in principle: a document can be read from the translog by
identifier, but you cannot search over it invertedly. Search therefore sees only what has passed through
a refresh.

The client can force the behaviour on write via `WriteRequest.RefreshPolicy`
(`server/src/main/java/org/elasticsearch/action/support/WriteRequest.java:53`):

- `NONE` — the default, the write does not care about a refresh,
- `IMMEDIATE` — force a refresh right away (expensive, creates small segments),
- `WAIT_UNTIL` — hold the response until the change is visible.

`WAIT_UNTIL` is a nice design pattern: **it does not speed visibility up, it merely synchronizes the
client with it.** The client gets a response at the moment when a subsequent search would already see the
change, without anyone paying for a forced refresh.

### 5.3 Why it is that way at all

The root is that a Lucene segment is an immutable file and creating it is expensive. Refreshing on every
write would produce a segment per document; the system would drown in merging. Everything else follows
from that: batching by the second, the translog as the holder of durability between batches, the merge
policy in the background, and finally also the fact that internal document numbers change after a merge,
so the system has to keep a translation between them and identifiers.

### Consequence for our design

This is a direct input into **§4.5 of the research (Transactions and visibility)** and into plan **P2**,
and it is in my view the place where our design has its strongest structural advantage — it deserves to
be formulated as a merit, not as a mere difference.

The research sets up three levels: the dictionary and postings are transactional for free, the impact
sidecar is maintained by copying chunks on write, and the fallback is visibility after commit.
Elasticsearch adds the following to that. **Splitting durability from visibility is not a property
anybody would want — it is a tax for immutable segments.** Elasticsearch had to build a translog, a
refresh scheduler, three different refresh policies on write and a separate realtime path for reads by
identifier because of it, which for search cannot in principle be generalized. In evitaDB this tax does
not arise: a transactional B+ tree and `TransactionalBitmap` give durability and visibility from a single
mechanism, and with the same semantics for reads by key as for search. I recommend adding this sentence
to §4.5 — it is missing there today and it is the strongest argument for the whole storage architecture.

A second, more practical consequence concerns the **fallback of §4.5(3)**, i.e. visibility only after
commit. Elasticsearch shows that this is not an emergency solution but **the ordinary operating mode of
the largest deployed engine in this category** — with the difference that for us the boundary would be a
transaction's commit (a semantically defined point), whereas for them it is the passing of a second (a
point unrelated to the data at all). Our fallback is therefore strictly better than Elasticsearch's
default behaviour. If P2 measures that maintaining the impact sidecar under write load costs more than
the agreed 10 % of commit throughput, switching to the fallback is a defensible decision and not a
retreat — and `p2-transactional-maintenance.md` should formulate it that way, because today the fallback
reads as a defeat.

Third, the **`WAIT_UNTIL`** pattern is worth adopting. If evitaDB were to decide on the fallback, it is a
cheap way of giving the client read-your-writes without the engine speeding anything up: the write API
can optionally wait until the fulltext structures catch up. It is a purely synchronizing element at the
API boundary, not a change of the index model, and `p2-transactional-maintenance.md` can list it as a
complement to the fallback.

---

## 6. Suggesters and suggestions

Elasticsearch has three different mechanisms all called a "suggester", but they solve three different
tasks. The package is `server/src/main/java/org/elasticsearch/search/suggest/`.

### 6.1 Term suggester — correcting individual words

The subpackage `term/`, resting on Lucene's `DirectSpellChecker`; the configuration is in
`DirectSpellcheckerSettings.java`. It takes the query's words one by one and looks for similar terms in
the index's dictionary. It does not know the sentence context.

### 6.2 Phrase suggester — "did you mean…" over a whole phrase

The subpackage `phrase/`, and it is the most sophisticated piece. It builds on the noisy-channel model
(`NoisyChannelSpellChecker.java`) and a **language model over n-grams**: candidates for correcting
individual words (`DirectCandidateGenerator`) are composed into whole phrases and those are scored by
the probability of the sequence. There are three smoothing models (`SmoothingModel`): `Laplace`,
`StupidBackoff` and `LinearInterpolation`, each with its own scorer.

The price is, however, fundamental: the phrase suggester **needs shingles in the index**, i.e. a field
indexed with word n-grams. Those have to be introduced into the mapping in advance. It is therefore a
feature that cannot be switched on retroactively without reindexing.

### 6.3 Completion suggester — a separate FST index

The subpackage `completion/` and the field type `completion`
(`server/src/main/java/org/elasticsearch/index/mapper/CompletionFieldMapper.java`). The JavaDoc on line
56 says it precisely: the values are indexed **as a weighted FST** (a finite automaton) against which
`CompletionSuggester` then searches.

Properties worth noting:

- It is a **separate index structure beside the main index**, not a derivative of it.
- **The weight is part of the indexed document** — the field `weight` on input (line 108, processing on
  line 439). A suggestion's popularity is therefore baked in at indexing time; changing it means
  reindexing the document.
- It has **its own pair of analyzers** (`analyzer` and `search_analyzer`, line 187) and its own
  parameters `preserve_separators` and `preserve_position_increments` (lines 124 and 130).
- **Contexts** (`contexts`, line 135) allow suggestions to be filtered by category or geographic
  location — it is the only filtering mechanism the completion suggester has, because ordinary index
  filters do not apply to an FST. Their number is limited by the constant `COMPLETION_CONTEXTS_LIMIT`
  (line 80), exceeding it is an error (line 218).
- `max_input_length` (line 152) truncates the input length.

### 6.4 `search_as_you_type` — and what it costs

The field type `search_as_you_type`
(`modules/mapper-extras/.../index/mapper/extras/SearchAsYouTypeFieldMapper.java`) is a different answer
to the same need. The JavaDoc on lines 79–82 describes what one such field in fact creates: a root
field, then a `ShingleFieldMapper` for 2-shingles, another for 3-shingles up to `max_shingle_size`, and
finally a `PrefixFieldMapper` with edge n-grams over the longest shingles.

With the default `max_shingle_size = 3` (line 95; the permitted range is 2 to 4, lines 88–89) one
logical field therefore creates **four physically indexed fields**. That is the price of suggestions
built on index structures instead of on a dictionary.

### Consequence for our design

For plan **P3 (suggester)** this is a very strong confirmation of the design from §4.6 of the research —
and that from an unexpected direction, namely through the cost of the alternatives.

The research claims: *"No additional structure — the suggester is a derivative of the dictionary, which
is why it belongs in the core and not in a layer above."* Elasticsearch offers precisely both
counter-alternatives and with both it is visible what they cost. The completion suggester is a whole
second index with its own analyzers, its own set of parameters and **weights baked into the documents**.
`search_as_you_type` is four times the indexed fields. Our design — a range scan over the sorted term
dictionary, which exists for search anyway, and scoring by postings cardinality — needs neither.

Two concrete points for `p3-suggester.md`:

**The freshness of popularity is a property for us, not configuration.** The completion suggester has to
be given the weight at indexing time, so "suggestions by popularity" are as fresh for it as the last
reindex is. Our suggester scores by the cardinality of the postings bitmap, which it reads at the moment
of the query — it is therefore always consistent with the current state of the catalog, for free. It is
worth writing that down, because it is a selling point not visible in the plan today.

**Filtering suggestions is also free for us.** The completion suggester had to invent `contexts` with a
hard limit, because ordinary index filters cannot be run over an FST. For us the entity suggestion is
defined as "an OR of the bitmaps of the top-M terms ∧ the must-match filter" (§4.6) — i.e. the same
bitmap algebra as in an ordinary query, with an arbitrarily complex filter. No new concept, no limit.

One thing remains that Elasticsearch can do and our plan cannot, and it is honest to name it:
**correcting a whole phrase using a language model** (the phrase suggester). Our design corrects words
independently through a Levenshtein automaton, so it cannot decide that "leather jacket" is a more
probable sequence than "leather jackety". For that capability Elasticsearch pays with shingles in the
index, which is exactly the positional tax §4.7 avoids. I recommend listing it in `p3-suggester.md` as a
**deliberately undelivered feature with the reason given**, so that nobody proposes it again later
without knowing the price — which is exactly what `.claude/rules/adr.md` requires of rejected options.

---

## 7. Proximity and phrases — and a direct precedent for our second phase

Here is the strongest single finding for plan **P4** and for §4.7 of the research.

### 7.1 The standard path: positions in the index

A text field has the parameter `index_options` (`TextFieldMapper.java:278`), which determines what is
written into the index about a term: only documents (`docs`), documents and frequencies (`freqs`), plus
positions (`positions`, the default for `text`), or plus character offsets (`offsets`). A `match_phrase`
query with `slop` (a permitted deviation in order and distance) needs positions.

Elasticsearch adds two optimizations to that in the mapping, both `updateable = false`:

- **`index_phrases`** (`TextFieldMapper.java:298`) — additionally indexes pairs of adjacent words as
  separate terms, so that a two-word phrase is evaluated by a single term lookup instead of joining
  positional lists.
- **`index_prefixes`** (`TextFieldMapper.java:304`) — indexes word prefixes in a configured range of
  lengths as separate terms, so that a prefix query does not have to walk the dictionary.

Both are the classic trade: disk space and work at indexing time in exchange for query speed. Meilisearch
went the same way with proximity according to VK2 of the research, and had to trim it twice.

### 7.2 `match_only_text` — an index without positions, phrases from stored values

And now the essential part. Elasticsearch has the field type **`match_only_text`**
(`modules/mapper-extras/.../index/mapper/extras/MatchOnlyTextFieldMapper.java`), whose JavaDoc on lines
122–125 reads literally:

> A `FieldMapper` for full-text fields that only indexes `IndexOptions.DOCS` and runs
> positional queries by looking at the `_source`.

The field's default settings (lines 134–141) confirm it: no stored term vectors, **no norms**
(`setOmitNorms(true)`) and `IndexOptions.DOCS` — so in the index there is for every term only a list of
documents, nothing more. No positions, no frequencies, no length normalization.

How then does a phrase query work? Through the class `SourceConfirmedTextQuery.java`, whose JavaDoc
(lines 59–63) describes the mechanics precisely:

> A variant of `TermQuery`, `PhraseQuery`, `MultiPhraseQuery` and span queries that uses
> postings for its approximation, but falls back to stored fields or `_source` whenever term
> frequencies or positions are needed. This query matches and scores the same way as the
> wrapped query.

It is therefore a two-phase iterator in the Lucene sense: **the postings give a cheap approximation — a
superset of candidates** (documents containing all the words of the phrase, regardless of their
positions; the comment at the construction of the approximation on line 68 says so explicitly), and only
on those candidates is the value **re-analyzed from the source** and the phrase confirmed or rejected.
The same mechanics covers interval queries too (`SourceIntervalsSource.java`) and the prefix, fuzzy,
wildcard and regular variants go through it as well (`MatchOnlyTextFieldMapper.java`, lines 837–905).

The price and the limits are visible in the code too. Without `_source` it does not work and the field
rejects it with an explicit error (line 438):

```
Field [...] of type [match_only_text] cannot run positional queries since [_source] is disabled.
```

And because norms are switched off, the field has no length normalization — the score is therefore
coarser than for a full-fledged `text`.

### Consequence for our design

**This is a direct, production-deployed precedent for the design of §4.7.** The research proposes not
indexing positions at all and computing proximity in the second phase by re-analyzing stored values for
the top-K candidates. Elasticsearch not only considered the same trade-off but **shipped it as a
first-class field type** — and that in a system that has positions as standard and could use them. I
propose adding `match_only_text` into §4.7 and into `p4-proximity-rerank.md` as a named precedent; today
there is none there and the argument stands only on the negative evidence from Meilisearch (that
positions are expensive).

Three concrete things I would carry from this into P4.

**The pattern "approximation from postings, confirmation from values" is the same as ours, but with a
different truncation — and it is worth considering whether theirs is not better.** We truncate by
**rank** (top-K by the first phase, proposal K = 1000), Elasticsearch truncates by **predicate** (only
documents containing all the words of the phrase). Their variant has two advantages: it is exact (no
document drops out because it did not fit into the window) and in e-commerce queries it is usually far
more selective than 1000 candidates. Our variant, on the other hand, has a bounded worst case, which
theirs does not. **I recommend that P4 measure both**, because the decision about K (open question O2)
may thereby fall away entirely: if the number of candidates containing all the query's words is
typically below K, the predicate truncation is better in both directions. The research moreover mentions
in §7 "a proposal for a cheap co-occurrence feature in phase 1" (P4 §3) — that is exactly the building
block such a truncation needs.

**The limit of applicability is the same and it is the limit of the CMS profile.** Elasticsearch admits
that `match_only_text` is for cases where the saving of space is paid for with slower positional queries;
the cost of re-analysis grows with the length of the value. That is exactly the concern §4.7 itself
voices at Z8 (long articles). The finding does not refute it, but gives it context: Elasticsearch targets
this field type mainly at logs — i.e. short to medium-length lines with an enormous volume. That supports
P4's decision to measure short and long fields separately and to consider failure on long ones a
legitimate trigger for the positional seam.

**Switched-off norms are a warning about the impact byte.** `match_only_text` had to sacrifice length
normalization too, because norms are part of Lucene's positional write. Our design is **better in this
and it is worth realizing**: the impact byte (§4.2) is our own structure independent of positions, so we
have length normalization even without positions. It is a concrete point where an in-house format earns
over an adopted one — and it belongs in the argument of §3 ("why not Lucene as the engine"), because in
the Lucene format this combination is simply impossible.

---

## 8. Vectors

### 8.1 `dense_vector` and the evolution of the default quantization

The field type is `dense_vector`
(`server/src/main/java/org/elasticsearch/index/mapper/vectors/DenseVectorFieldMapper.java`). The maximum
number of dimensions is 4096 (`MAX_DIMS_COUNT`, line 285). The element type (`element_type`, line 359)
is `float`, `bfloat16`, `byte` or `bit` and is **`updateable = false`**.

The most interesting, though, is the parameter `index_options`, for two reasons.

First, **the default value changes with the version in which the index was created** (the method
`defaultIndexOptions`, line 503, and the constants on lines 265–269). The historical sequence of default
choices is: unindexed `float` → `int8_hnsw` (eight-bit scalar quantization over an HNSW graph) →
`bbq_hnsw` → `bbq_disk`. The abbreviation **BBQ** stands for *Better Binary Quantization*, i.e. binary
quantization, which typically reduces a vector to a single bit per dimension.

The thresholds are concrete: BBQ is used from `BBQ_DIMS_DEFAULT_THRESHOLD = 384` dimensions (line 295
and the condition on line 531); below that threshold the disk variant chooses 4 bits instead of 1 (line
516); the absolute minimum for BBQ is `BBQ_MIN_DIMS = 64` (line 159).

Second — and this is an exception against text fields — **`index_options` is `updateable = true`** (line
434). The quantization strategy can therefore be changed at runtime. It works because every Lucene
segment carries its own codec: the change takes effect on newly arising segments and the old ones carry
their format until merging absorbs them.

### 8.2 Oversampling and rescoring as the standard solution to the loss of precision

Quantization distorts distances, so the ordering from the quantized index alone is inaccurate. The answer
is explicit in the code: `RescoreVector` with the value `DEFAULT_OVERSAMPLE = 3.0F` (line 294, used on
line 522). Three times the requested number of candidates is therefore retrieved in the quantized space
and those are then re-scored at full precision.

It is worth noting that oversampling became the default only subsequently — evidenced both by the
constant `IndexVersions.DEFAULT_OVERSAMPLE_VALUE_FOR_BBQ` and its backport (lines 259–261) and by a
separate `NodeFeature` `USE_DEFAULT_OVERSAMPLE_VALUE_FOR_BBQ` (line 280).

### 8.3 Filtered nearest-neighbour search

The combination "find the most similar vectors, but only among documents satisfying a filter" is
notoriously difficult, because walking the HNSW graph and applying a filter get in each other's way.
Elasticsearch has **two strategies and a heuristic that chooses between them**:

- **Pre-filtering** — the filter is applied during the graph walk. The heuristic is governed by the
  setting `index.dense_vector.hnsw_filter_heuristic` (line 204), whose default value switched from a
  certain index version (`DEFAULT_TO_ACORN_HNSW_FILTER_HEURISTIC`, line 199) to **ACORN** — a published
  method for walking a graph under a predicate. The choice is read by `KnnVectorQueryBuilder.java:573`.
- **Post-filtering** — the graph is searched without the filter and the filter is applied to the result;
  implementation `server/src/main/java/org/elasticsearch/search/vectors/PostFilterKnnQuery.java`. The
  switch is **the filter's selectivity**: `postFilterSelectivityThreshold` (line 72), configurable per
  index (`DenseVectorFieldMapper.java:230`), with the default value `DEFAULT_POST_FILTERING_THRESHOLD =
  1f` (`PostFilterKnnQuery.java:55`).

It is therefore visible that this is a **decision based on an estimate of selectivity**, i.e. classic
query planning, not one fixed strategy.

### 8.4 Where vectors meet distribution

As I mentioned in the part about scoring across shards: vector queries need a global ordering of
candidates, and therefore hung themselves on the DFS phase — `DfsPhase.executeKnnVectorQuery`
(`server/src/main/java/org/elasticsearch/search/dfs/DfsPhase.java:215`). Every shard returns its top-k
and the coordinator picks the global top-k from them. The results are then held by `DfsKnnResults.java`.

### Consequence for our design

For plan **P6 (vector spike)** there are several direct inputs here.

**The criterion "recall@10 ≥ 0.95 with rescoring" is correctly set up and a concrete number can be added
to it.** Elasticsearch uses an oversampling factor of **3.0** as the default and arrived at it through
experience. That is a good starting point for the measurements in P6 — and at the same time useful
information for the latency budget, because it means the vector leg has to be able to load full precision
for three times `k` candidates. The research speaks in §5.2 about quantization and in §5.3 about mmapping
immutable files; **full-precision vectors must therefore remain reachable even after quantization**,
which is a layout requirement not visible explicitly in the plan today.

**The threshold numbers for the choice of quantization are transferable.** The threshold of 384
dimensions for binary quantization and the lower bound of 64 dimensions are empirical values of a large
deployment and P6 can take them as defaults instead of seeking them from scratch. For typical embeddings
(384 or 768 dimensions) it means binary quantization is usable — which is good news for the RAM analysis
of §5.2.

**Filtered ANN (§5.4) needs two strategies, not one.** I would emphasize this, because the research
opens this question in §5.4 but does not give it a shape. Elasticsearch has both paths and decides
between them by the **estimated selectivity of the filter**. For us that is especially relevant because
of Z7: the must-match filter typically removes only 5–15 % of the corpus for us, i.e. it is **very
weakly selective** — and that is exactly the mode in which post-filtering is the right choice, because
the filter cuts almost nothing and pre-filtering would only make the graph walk more expensive. I
recommend that `p6-vector-spike.md` list post-filtering as the default strategy, with pre-filtering
needed only for selective filters (for example a deep category plus a narrow price range). Our advantage
over Elasticsearch is that **we have the candidate bitmap computed exactly**, so we do not estimate
selectivity — we know it. The decision between the strategies is therefore exact for us, not heuristic,
and that is worth naming in the plan.

**Changing quantization without reindexing is a property we will not get for free.** Elasticsearch can
afford `updateable = true` on `index_options` only thanks to the segment model, where every segment
carries its codec. In our model of a single live structure it means that changing quantization is a
**rebuild of the vector index**. It is not a blocking problem (a rebuild is a local recomputation from
the stored embeddings, see section 1), but it belongs in `schema-design.md` among the locked parameters —
and it is at the same time a second argument for keeping full-precision embeddings stored outside the
index as well.

---

## 9. Further relevant mechanisms

### 9.1 The `text`/`keyword` duality, multi-fields and `copy_to`

Elasticsearch consistently separates an **analyzed** field (`text` — decomposed into terms, searched
within words) from an **unanalyzed** one (`keyword` — the whole value is one term, suitable for
filtering, aggregations and sorting). Because it is typical to need both over the same value,
**multi-fields** exist: one field in the document, several ways of indexing it under derived names. The
canonical example is a `text` field with a `.keyword` subfield.

Beside that stands **`copy_to`** (`FieldMapper.java:208`), which at indexing time copies the value into
another field — typically into a catch-all "all" field, over which the search is then a single query
instead of ten. The restrictions are interesting: `copy_to` must not copy from a multi-field nor into one
(`FieldMapper.java:511` and `517`), both with an explicit error.

### 9.2 Runtime fields

A field computed by a script only at read time, without any index structure (the classes
`AbstractScriptFieldType` and the derived `LongScriptFieldType`, `KeywordScriptFieldType`,
`BooleanScriptFieldType` and others in the mappers package; plus `LookupRuntimeFieldType` and
`CompositeRuntimeField`). They can be added even **to an existing index** and can even be defined right
in the query body.

It is an elegant answer to "I need a new field but do not want to reindex" — and an honest one at the
same time: a runtime field is computed for every affected document on every query, so it is cheap to
write and expensive to read, exactly the opposite of an indexed field.

### 9.3 Highlighting: three implementations and three sources of offsets

The package `server/src/main/java/org/elasticsearch/search/fetch/subphase/highlight/` contains three
highlighters: `PlainHighlighter`, `FastVectorHighlighter` and `DefaultHighlighter` (the unified one, over
Lucene's `UnifiedHighlighter`).

The decisive method is `DefaultHighlighter.getOffsetSource` (line 248), which chooses where the character
positions of the highlighted stretches come from. The enum `OffsetSource` has three options:

- `POSTINGS` — from the index, if the field has `index_options: offsets` (line 254),
- `TERM_VECTORS` — from stored term vectors, if they exist (line 257),
- `ANALYSIS` — **the stored value is analyzed again** (lines 250 and 259).

`ANALYSIS` is moreover **the return value when nothing else is available** — i.e. the default behaviour
for an ordinarily configured field. `FastVectorHighlighter`, by contrast, does not work at all without
term vectors with offsets and refuses to start (`FastVectorHighlighter.java:75` and `272`).

### 9.4 Curator rules (Query Rules) and their triggers

The module `x-pack/plugin/ent-search/.../rules/`. Rules live in sets (`QueryRuleset`) stored by the
server in a system index (`QueryRulesIndexService`). The condition type `QueryRuleCriteriaType` offers
`ALWAYS`, `EXACT`, `FUZZY`, `PREFIX`, `SUFFIX`, `CONTAINS` and the numeric comparisons `LT`, `LTE`, `GT`,
`GTE`. The action is pinning or excluding documents, technically through `PinnedQueryBuilder` in
`x-pack/plugin/search-business-rules/`.

The essential detail is in `RuleQueryBuilder.java`: the query carries a field `match_criteria` (lines 65
and 71) as a map, which **the client supplies in every request**, and it is mandatory — an empty or
missing map is an error (line 119). The engine therefore holds the table of rules, but **the values
against which the rules are evaluated have to be brought by the client**.

### 9.5 `semantic_text`: chunking as part of the field type

The type `semantic_text`
(`x-pack/plugin/inference/.../inference/mapper/SemanticTextFieldMapper.java:117`) is a field into which
the client sends **raw text** and the server calls a model itself to compute embeddings — both at
indexing time and at query time (the query side is `SemanticQueryBuilder.java`, which invokes inference
on rewriting the query). The model is identified by `inference_id`; there is also a default value per
index (`index.semantic_text.default_inference_id`, line 145).

The most valuable detail for us is, however, the internal structure: the field **creates a nested object
for chunks itself** (`chunksField` as a `NestedObjectMapper.Builder`, line 262), into which it stores the
chunk's embedding, its text and character offsets (`OffsetSourceFieldMapper` for `CHUNKED_OFFSET_FIELD`,
line 276). Chunking configuration is a separate capability (`SEMANTIC_TEXT_SUPPORT_CHUNKING_CONFIG`, line
93). **The splitting of long text into parts is therefore built into the field type, not left to the
user.** The field moreover does not support `copy_to` (line 329).

### Consequence for our design

**Highlighting (§4.6) is confirmed.** The research proposes "re-analysis of the stored values of the
returned page only, without index support". `OffsetSource.ANALYSIS` is exactly that mechanism and in
Elasticsearch it is **the default behaviour**, not an emergency variant — index support (`offsets` or
term vectors) is an optional speed-up paid for on write. I recommend adding this evidence to §4.6,
because today the claim stands there without support.

**Multi-fields and `copy_to` have a different shape for us and it is good to know that.** A catch-all
field via `copy_to` is an answer to the query "search in all fields at once" in a system where a query
over ten fields would otherwise arise. Our design has postings **per (field, term)** (§4.2) and per-query
field weights, so we solve the same problem by unioning bitmaps at query time — without duplicating data
in the index and with the possibility of changing weights from query to query, which `copy_to` cannot do
(a catch-all field discards the weights). It is a concrete point where our model is more flexible, and it
belongs in `query-design.md` as an argument for per-field postings.

**Chunking of long texts deserves a place in the plan, not just a note.** The research mentions it at
VK14 as "Algolia's market answer to long text" and at Z8 as the context of the CMS profile.
Elasticsearch gives it much greater weight: chunking is **built into the field type**, with separately
stored embeddings and character offsets per chunk. For us a concrete question follows that
`p6-vector-spike.md` ought to answer: **is the unit of the vector index the entity, or a chunk?** If a
chunk, a chunk → PK mapping is needed plus an aggregation function across the chunks of the same entity
(typically the maximum) — and that is the same shape of problem as aggregation across references (§1.4,
open question O10), so the two ought to share a mechanism. If the entity, it has to be said how a long
article fits into a single embedding. The plan does not ask this question today and that is in my view a
gap, because it decides the data model of the vector branch.

**Query Rules confirm the dividing line of §1.2.** The curator table is server-side, but the triggering
values are supplied by the client in every request — the engine derives nothing from the free text of the
query itself. That is exactly the division the research advocates: dictionary and rule lookup belongs to
the engine, interpretation of intent stays outside it. Details are in the verification of VK13 below.

---

## 10. Verification of the claims of VK13 and VK20 against source code

The research in §8 explicitly states for both of these items that they arose **without reading source
code** — VK13 "only through a web search, version numbers with reservation", VK20 "WebFetch blocked in
the sandbox, everything from result snippets, verbatim quotations with reservation". Because I have the
checkout available, I went through both items claim by claim. The result: **all load-bearing claims were
confirmed**, for two I add a refinement and for one a correction.

### VK13 — Elasticsearch, query understanding

| Claim of the research | Verdict | Anchor |
|---|---|---|
| Synonyms API: server-side sets, without reindexing | **confirmed** | `SynonymsManagementAPIService` |
| Query Rules: a curator table in the engine | **confirmed** | `QueryRulesIndexService` |
| …but the criteria are declared by the client in every request | **confirmed** | `RuleQueryBuilder.java:119` |
| `semantic_text`: inference on both sides | **confirmed** | `SemanticQueryBuilder` |
| Query-side NER does not exist | **confirmed, refined** | `ml/inference/nlp/NerProcessor.java` |

Details and refinements:

**Synonyms.** The mechanism is richer than the research states, and in a way that is instructive for us —
see section 2.2. The key sentence: declaring a synonym dictionary `updateable` **automatically moves it
into `SEARCH_TIME` mode** and thereby mechanically makes its use at indexing time impossible
(`SynonymTokenFilterFactory.java:199–204`). That is not in the research and it is the most valuable
detail of the whole of VK13.

**Query Rules — confirmed in the strongest form.** The research's formulation "the triggering criteria
are declared by the client in every request" is precise: `match_criteria` is a mandatory map in the query
body and its absence is an error. I add the set of supported condition types (`QueryRuleCriteriaType`:
`ALWAYS`, `EXACT`, `FUZZY`, `PREFIX`, `SUFFIX`, `CONTAINS`, `LT`, `LTE`, `GT`, `GTE`) and the refinement
that the pinning action is technically `PinnedQueryBuilder` from the `search-business-rules` module,
which also has a newer retriever form (`PinnedRetrieverBuilder`).

**`semantic_text` — confirmed and supplemented.** Server-side inference over both the query and the
document holds. The research does not, however, state that part of the field type is **built-in
chunking** with per-chunk embeddings and offsets (section 9.5) — which for Z8 (the CMS profile) is more
significant than server-side inference itself.

**NER — confirmed, with a refinement of the formulation.** `NerProcessor.java` is in the repository
(`x-pack/plugin/ml/src/main/java/org/elasticsearch/xpack/ml/inference/nlp/NerProcessor.java`,
configuration `NerConfig.java`), but it is an **NLP inference task over text**, available through the
inference API and the ingest pipeline. Nothing calls it in the query path. A more precise formulation
than "only as an ingest processor" would therefore be: *NER is a general inference task over text in
Elasticsearch; it is not, however, wired into the query path — the only model inference a query triggers
is computing an embedding.* That fits into the summary pattern of §8 ("model inference over the query
does not exist in any engine, the single exception: embeddings") and confirms it.

**The version numbers** (8.10 for the Synonyms API, 8.15 for Query Rules, 8.18/9.0 for `semantic_text`)
cannot be verified from the code — the version history would have to be traced in git. Given that all
three mechanisms exist in the repository and are functionally as the research describes, the risk of an
error in the numbers is low and immaterial to the argument.

### VK20 — Elasticsearch, aggregating child relevance into the parent

| Claim of the research | Verdict | Anchor |
|---|---|---|
| `has_child` + `score_mode` avg/max/min/sum | **confirmed** | `HasChildQueryBuilder.java:84` |
| Default `score_mode` = `none`, parent score 0 | **confirmed** | `HasChildQueryBuilder.java:67` |
| One join field per index | **confirmed** | `ParentJoinFieldMapper.java:55` |
| The global ordinals tax | **confirmed and refined** | `ParentJoinFieldMapper.java:87–92` |
| Same-shard routing | **confirmed indirectly** | `ParentJoinFieldMapper.java:64–72` |
| `nested` = hidden documents in one block | **confirmed** | `DocumentParserContext.java:1035` |
| A change of a child rewrites the whole block | **confirmed** | same place + `NestedObjectMapper.java:92` |

Details:

**The default `score_mode` is `None`** — the constant `DEFAULT_SCORE_MODE = ScoreMode.None`
(`modules/parent-join/src/main/java/org/elasticsearch/join/query/HasChildQueryBuilder.java:67`), and it
is mandatory: the constructor rejects it as `null` with the message "requires 'score_mode' field" (line
104). Elasticsearch therefore really **is the only one of the verified engines that can aggregate child
relevance into the parent**, but by default it does not do so. The delegation is to Lucene's
`JoinUtil.createJoinQuery` (a reference in the JavaDoc on line 365), so the reservations of VK15 about
Lucene's join semantics apply here too.

**One join field per index** — the JavaDoc of the class `ParentJoinFieldMapper` says literally "Only one
parent-join field can be defined per index" (line 55). I add three further restrictions the research does
not have and which are essential for assessing the cost: a join field **must not** be on an index with
`routing_partition` nor with `routing_path` (`checkIndexCompatibility`, lines 64–72) and **must not** be
inside an object nor a multi-field (`checkObjectOrNested`, line 74). That first pair is precisely the
"same-shard routing" — a parent-child join requires parent and children to end up on the same shard,
which rules out any other routing scheme. The research states it as a consequence; the code has it as an
entry condition rejected at index creation.

**Global ordinals — a refinement.** The research speaks of "the global ordinals tax on refresh". The code
shows that the parameter `eager_global_ordinals` is **on by default** on a join field
(`ParentJoinFieldMapper.java:87–92`, default value `true`; for an ordinary `text` field the default is
conversely `false`, `TextFieldMapper.java:291–296`). The formulation is therefore not merely correct but
understated: it is not an optional tax but **the default setting** — a join field forces precomputation
of global ordinals after every refresh, because without them the join would be unbearably slow. What is
interesting is that the parameter itself is `updateable = true`, so it can be switched off; "switching
off" here, however, means moving the same work into the first query.

**`nested` and rewriting the whole block** — confirmed by the mechanics of writing. The method
`DocumentParserContext.luceneDocumentsInShardIndexOrder()` has a JavaDoc saying that the order is meant
for `IndexWriter#addDocuments` and is **"children before their parent"**
(`server/src/main/java/org/elasticsearch/index/mapper/DocumentParserContext.java:1035`). That is Lucene's
block write: a parent and all its nested documents are written as one indivisible block, so a change of a
single child means rewriting the whole block — i.e. N+1 Lucene documents, exactly as the research states.
I add that `include_in_parent` **cannot be changed on an existing nested mapping**
(`NestedObjectMapper.java:92`) — another locked parameter, consistent with the rule of section 1.2.

**The tuning guide ("nested several times, parent-child hundreds of times slower — denormalize")** is a
documentation claim and cannot be verified in source code. The source code does, however, support it
indirectly: eager global ordinals on by default, the prohibition on custom routing and the block rewrite
on a child's change are exactly the three mechanisms such numbers follow from.

### Recommendation for §8 of the research

Both items can be flipped in §8 from the category "only through a web search" into the category verified
against source code, with the commit `9a100e2d0e41` given. I propose three substantive amendments while
doing so: at VK13 add the binding `updateable` → `AnalysisMode.SEARCH_TIME` (it is the most valuable
detail and is missing today) and the built-in chunking of `semantic_text`; reformulate the claim about
NER to "NER is a general inference task, but it is not wired into the query path"; and at VK20 strengthen
the formulation about global ordinals from "a tax" to "the default setting" and add the prohibition on
custom routing as an entry condition, not a consequence.

---

## 11. Summary: what to take from Elasticsearch, what not, and what we do not need

**Take as a mechanism (highest value):**

1. **Declaring the cost of a change at every schema parameter** (`updateable` + the conflict accumulator,
   section 1.2). Cheap, mechanically unforgettable, and it closes a hole `schema-design.md` identified for
   itself.
2. **The mode of an analysis component (`AnalysisMode`) enforced by the type system** (section 2.2). It
   turns "hot-swappable artifact" into a verifiable property instead of a convention.
3. **Three analyzer slots** — indexing, query, phrase (section 2.1). At least by design, because a later
   split is a change of a locked parameter.
4. **Choosing the scoring function only in the query, over the raw stored value** (`rank_feature`,
   section 4.2), including the catalog of functions `log`/`saturation`/`sigmoid`/`linear`.
5. **Two independent axes of score composition** — among the functions themselves and against the match
   score — plus the ability to give each function its own filter (`function_score`, section 4.3). It
   evidences the second pole §4.3 of the research builds on and names a distinction the lexicographic
   composite does not make today.
6. **`WAIT_UNTIL` as a synchronizing element** at the API boundary, if the fallback of §4.5(3) falls
   (section 5.2).

**Adopt as a precedent for the argument:**

7. **`match_only_text`** (section 7.2) is production-shipped evidence that "no positions in the index,
   proximity from stored values" is a functional architecture, not a compromise born of necessity. It
   belongs in §4.7 and in `p4-proximity-rerank.md`.
8. **`OffsetSource.ANALYSIS`** (section 9.3) confirms highlighting without index support.
9. **RRF as fusion without score calibration** (section 4.5) — for us not a preference but the only
   correct choice, because a 64-bit composite cannot be compared with cosine similarity.
10. **LTR as a rescorer, not as a scoring function** (section 4.6) — an independent confirmation of the
   placement from §4.3.

**Deliberately do not take:**

11. **Dynamic inference of the schema from data** and the whole apparatus of limits around it (section
    1.3). evitaDB's schema-first model is an advantage here; the opposite direction leads to a limit of
    1000 fields and a valve that silently discards data.
12. **The completion suggester as a separate FST index** and `search_as_you_type` as four times the
    fields (sections 6.3 and 6.4). Our suggester as a derivative of the dictionary is cheaper and
    fresher.
13. **Phrase correction by a language model** (the phrase suggester, section 6.2) — the only capability
    we deliberately do not deliver; the reason (shingles in the index) is worth writing down so that
    nobody proposes it again without knowing the price.

**What will not arise for us at all — and why that is a load-bearing argument, not just a difference:**

14. **The DFS phase** (section 3). It exists solely because BM25 is a function of the corpus and shards
    see different corpora. It is switched off because it is expensive — Elasticsearch therefore returns by
    default a score it itself knows is not consistent across shards. Our choice without corpus statistics
    (§2.1) eliminates that whole infrastructure.
15. **Splitting durability from visibility** (section 5). The translog, the refresh interval, three
    refresh policies on write and a separate realtime path for reads by identifier are all a tax for
    immutable segments. A transactional B+ tree and `TransactionalBitmap` give both from a single
    mechanism, with the same semantics for key reads as for search.
16. **Estimating selectivity in filtered ANN** (section 8.3). Elasticsearch decides between pre-filtering
    and post-filtering with a heuristic over an estimate; we know the candidate bitmap exactly, so we
    decide by fact.

**Open questions the finding opens or refines:**

- **O2 (K for the second phase)** may fall away entirely if a predicate truncation in the style of
  `SourceConfirmedTextQuery` is used instead of truncation by rank (section 7.2). P4 ought to measure
  both.
- **The unit of the vector index — entity, or chunk?** A question `p6-vector-spike.md` does not ask today
  and which `semantic_text` brings to the fore (section 9.5). It shares its shape with O10 (aggregation
  across references), so the two ought to share a mechanism.
- **Full-precision embeddings must remain reachable even after quantization**, because rescoring with
  oversampling 3.0 is a condition of the required recall (section 8.2). A layout requirement not visible
  explicitly in §5.3.
