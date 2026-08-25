# Fulltext configuration in the evitaDB schema — a design

> **Status: a proposal for discussion, not a decision.** The document follows on from the research
> [`../research.md`](../research.md) (v2, consolidated) and elaborates one of its open areas: what
> exactly the **schema** has to know about fulltext, in what shape to write it down, and what on the
> contrary does not belong in the schema. The anchors into the source code (`file:line`) are verified
> against a working copy of branch `dev` as of 12 August 2026 and against local checkouts of the
> engines in `/www/oss`.
>
> The depth is chosen so as to suffice for starting implementation, not to constrain it: for every
> question several shapes of API are sketched with their trade-offs and a recommendation. The open
> questions are at the end.
>
> It was translated from Czech and moved out of `specifications/` into this record on 2026-08-24;
> see [`../README.md`](../README.md) for the decision it supports.

---

## Contents

1. [Purpose and scope](#1-purpose-and-scope)
2. [What has to be baked into the index](#2-what-has-to-be-baked-into-the-index)
3. [How the other engines solve the same task](#3-how-the-other-engines-solve-the-same-task)
4. [The starting state of evitaDB](#4-the-starting-state-of-evitadb)
5. [A design for evitaDB: three shapes of API](#5-a-design-for-evitadb-three-shapes-of-api)
6. [The individual configuration items](#6-the-individual-configuration-items)
7. [Schema evolution and reindexing](#7-schema-evolution-and-reindexing)
8. [The boundary: schema versus hot-swap artefact](#8-the-boundary-schema-versus-hot-swap-artefact)
9. [Open questions](#9-open-questions)

---

## 1. Purpose and scope

The research recommended a custom fulltext engine over evitaDB's bitmap algebra. However it is built,
somewhere it has to be recorded **which fields are searched at all, what language processing they go
through and with what default parameters they are scored**. That "somewhere" is the entity schema —
the only place evitaDB replicates, versions, serializes into the WAL and mirrors into gRPC, GraphQL
and REST.

The document answers four questions:

- **What has to go into the schema.** Everything that determines the *shape of the written data*:
  which attributes are tokenized, by which analyser, with which pivot of the length normalisation.
  Without that the index will not come into being, or will come into being wrong.
- **What on the contrary must not go into the schema.** Everything that can be evaluated over the
  query: synonyms, entity dictionaries, boost tables, per-query field weights, the choice of rank
  profile. Baking these things into the schema would turn every change of ranking into a reindex.
- **In what shape to write it.** Three variants of the API with the same example for each, with
  trade-offs and a recommendation.
- **What happens on a change.** The hardest part: evitaDB today has **no reindexing mechanism at all**
  and a change of an indexing flag over existing data passes silently. Fulltext did not cause that
  problem, but it is the first feature that cannot ignore it.

**What the document does not address:** the mechanics of the index structures (research §4.2), the
shape of the query constraints (`attributeMatches`, `relevance()` — open question O4 of the research),
the contents of the analyser registry (prototype P5) nor the vector index (prototype P6). For vectors
and matching through references only the shape of the declaration in the schema is given, because even
a sketch has to be consistent with the rest.

---

## 2. What has to be baked into the index

The strongest lesson from verifying five engines is that **the only useful criterion for where a piece
of configuration belongs and how it may be handled is the cost of changing it.** Not the topic, not
kinship, not the user's convenience.

Meilisearch expresses this most cleanly: its decision function `InnerIndexSettingsDiff::new`
(`/www/oss/meilisearch/crates/milli/src/update/settings.rs:1685`) does not ask "should we reindex?"
but asks separately about the inverted index, the facet databases, the vectors and geo. A change of
`filterableAttributes` does not touch the postings at all, a change of `stopWords` does not touch the
facets.

The boundary the engine thereby draws is legible and transferable: **what changes the produced tokens
requires a recomputation always; what is read only at ranking time can be changed for free.** In
Meilisearch the field weights lie in their own database key (`FIELDIDS_WEIGHTS_MAP_KEY`,
`/www/oss/meilisearch/crates/milli/src/index.rs:61`), not in the postings, and that is why reordering
`searchableAttributes` is free — and yet immediately effective, because even the branch without a
reindex calls `recompute_searchables`
(`crates/milli/src/update/new/indexer/write.rs:189`).

Solr says the same in different words and it is the only place where it permits changing analysis at
all without rebuilding the index: if the index and query chains are declared separately and only the
query one changes, no reindex is necessary
(`/www/oss/solr/solr/solr-ref-guide/modules/indexing-guide/pages/reindexing.adoc:74`); any change of
the index chain, on the contrary, requires it almost always (ibid., `:76`).

From this follows the **first binding design rule of this document**: fulltext options may be grouped
into shared objects only according to the cost of their change, never according to topic. The
cautionary example is Meilisearch itself — its `typoTolerance` is a single object in the HTTP API, but
three of its members require no recomputation (`enabled`, `minWordSizeForTypos`, `disableOnWords`) and
two do (`disableOnAttributes` at `settings.rs:1721`, `disableOnNumbers` at `:1718`). The user has no
way of knowing which edit of the same object will be expensive.

### 2.1 Classification of the items of the brief

| Item | Changes tokens? | Cost of change | Where it belongs |
|---|---|---|---|
| `searchable` on an attribute | yes (creates/removes postings) | rebuild of the field | schema |
| `searchable` on associated data | yes | rebuild of the field | schema |
| Default field weight | no — read at ranking time | free | schema, but separately |
| Pivot of the length normalisation | yes — it is in the impact byte | rebuild of the field | schema, with the analyser |
| Analyser identification (index chain) | yes | rebuild of the field | schema |
| Analyser identification (query chain) | no | free | registry, not schema |
| Synonyms, entity dictionaries, boosts | no | free | hot-swap artefact |
| Prefix bitmaps | no — derivable from the dictionary | recomputation from the index | runtime configuration |
| Vector: dimension and metric | yes | rebuild of the graph | schema |
| Vector: graph parameters | partly | see §6.7 | schema |
| Scoring through a reference | depends on the variant | see §6.8 | schema |

The "pivot" row deserves an explanation, because it is not obvious. Per research §4.2 the impact byte
is defined as `min(255, sat(tf) × norm(field_length))`, where the length normalisation is computed
against a **pivot configured in the schema** and is baked into the byte at indexing time. The pivot is
therefore not a parameter of the scoring function that could be switched at runtime — it is an input
into a value that already lies on disk. That puts it in the same category as the analyser, even though
intuition suggests otherwise.

The field weight is on the contrary exactly the opposite case, and that is explicit in the research:
"the field weights are applied only by the rank profile at query time (§4.3) — the default weights
(name > brand > description…) are held by the schema, the query may override them" (§4.2). The schema
here therefore carries **a default for the rank profile**, not a property of the index.

---

## 3. How the other engines solve the same task

### 3.1 Vespa — the schema is the only artefact, ranking included

Vespa has the schema in `.sd` files and it is the most complete declaration of all the engines
examined: it contains the data model, the indexing prescription, the linguistics **and the rank
profiles**. The most economical complete fulltext schema is twenty lines long and is in the
application template for text search (`config-application-package/…/text-search/schemas/doc.sd:1`;
the paths in this section are relative to `/www/oss/vespa`):

```
schema doc {
    document doc {
        field text type string {
            indexing: index | summary
            index: enable-bm25
        }
    }
    rank-profile default {
        first-phase {
            expression: bm25(text)
        }
    }
}
```

What is essential is that `indexing:` **is not a list of flags but a fully fledged expression
language** with its own grammar (`/www/oss/vespa/indexinglanguage/src/main/javacc/IndexingParser.jj`),
embedded into the schema grammar (`/www/oss/vespa/config-model/src/main/javacc/SchemaParser.jj`). The
words `index`, `attribute` and `summary` are output expressions of the pipe, so `index | summary` means
"send the same value into both stores". That it is a real language is documented by the fixture
`/www/oss/vespa/config-model/src/test/derived/advanced/advanced.sd:70` with conditional branching
directly in the indexing prescription. **For evitaDB this is a cautionary illustration, not a model** —
it is a second language to maintain and evitaDB is to solve value transformation on the write path, not
in the schema.

What is on the contrary directly adoptable is **naming an analyser instead of describing it**. Vespa
makes it possible to declare a linguistic profile on a field, either one for both, or two different ones
— separately for indexing and separately for parsing the query (`SchemaParser.jj:941`):

```
    <LINGUISTICS> lbrace() <PROFILE> (
      ( <COLON> indexProfile = identifier() (<NL>)* )
      |
      ( lbrace()
          (
              ( <INDEX>  <COLON> indexProfile  = identifier() (<NL>)* ) |
              ( <SEARCH> <COLON> searchProfile = identifier() (<NL>)* )
          )+
        <RBRACE> (<NL>)*
      )
    )
```

The fallback `searchProfile = indexProfile` (ibid., `:403`) is an important default: symmetry is the
starting state and asymmetry has to be asked for. And the profile is **just a name** — the definition of
the analyser lives outside the schema, in a component deployed with the application. The schema therefore
carries a reference to a named policy, not its contents.

**In Vespa the field weight is a property of the rank profile, not of the index.** One fixture in the
repository carries three different weights for the same field in three profiles over a single index. And
none of the change validators looks at rank profiles at all — `rank-profiles.cfg` is missing from the
watched configurations, just as `RankProfilesConfig` is missing from `@RestartConfigs` at
`SearchNode.java:43`. A rank profile is therefore pure deploy-time configuration.

**The most valuable part of Vespa for us is its model of schema change.** On every deployment Vespa
compares the old and the new model and produces actions of exactly three types
(`config-model-api/…/config/model/api/ConfigChangeAction.java:18`):

```
    enum Type {
        RESTART("restart"), REFEED("refeed"), REINDEX("reindex");
```

The semantics is precise and usable for us in full: **restart** means bring the process down and start
it again, the data stays; **reindex** means Vespa itself reads the stored documents again and rewrites
the indexes; **refeed** means the data has to be sent again by the *client*, because the engine cannot
restore it from the stored state (the only such change is a change of a field's data type,
`DocumentTypeChangeValidator.java:142`).

Onto that a **second, independent axis** is hung: some actions carry a `ValidationId`, and if they do,
the deployment is refused until the user explicitly permits the change (`ConfigChangeAction.java:42`).
The permission is written into `validation-overrides.xml` and **with an expiry** of at most 30 days
ahead (`ValidationOverrides.java:90`, the message at `:106`), so an exception cannot silently remain
forever.

In Vespa a reindex is triggered by **any change of a value transformation**
(`IndexingScriptChangeValidator.java:62`, `ValidationId.indexingChange`), with the comparison
deliberately stripping the output expressions (ibid., `:67`) — adding summarisation over an
already-indexed field therefore does not trigger a reindex, whereas a change of `match`, `stemming` or
`normalizing` does, indirectly through the generated indexing script.

And finally a finding that is counter-intuitive and the most useful of all for our design:
**`enable-bm25` changes the format of the posting lists and yet requires no reindex.** It switches on
the storage of so-called interleaved features, i.e. the term frequency and the field length
(`ConvertParsedFields.java:358`, the target configuration `indexschema.def:20`). No validator looks at
`indexschema.cfg`; instead the engine reconciles the change itself with an urgent flush and fusion
(`/www/oss/vespa/searchcore/src/vespa/searchcorespi/index/indexmaintainer.cpp:1132`):

```
    // Non-matching interleaved features in schemas means that we need to
    // reconstruct or drop interleaved features in posting lists. Schedule
    // urgent flush until all indexes are in sync.
```

Vespa therefore distinguishes two categories that easily merge into one: changes requiring the **source
document** to be read again, and changes computable from the **already stored index**. That is a cut
evitaDB has to adopt.

Vespa declares vectors with a very clean division of responsibilities
(`/www/oss/vespa/config-model/src/test/derived/hnsw_index/test.sd:2`):

```
    field t1 type tensor(x[128]) {
      indexing: attribute | index
      attribute {
        distance-metric: prenormalized-angular
      }
      index {
        hnsw {
          max-links-per-node: 32
          neighbors-to-explore-at-insert: 300
        }
      }
    }
```

The dimension is part of the **data type**, the metric belongs under `attribute` (it is used even
without a graph, for exact brute-force search) and the graph parameters under `index`, because the graph
is an extra approximation structure. A change of the metric or of `max-links-per-node` on an existing
graph is a change refused without an explicit exception (`AttributeChangeValidator.java:149` and
`:165`), whereas `neighbors-to-explore-at-insert` is a mere restart.

### 3.2 Lucene and Solr — the pair of analysers as the closest model

Lucene **has no schema at all**. The field type is carried by each document separately through the
interface `IndexableFieldType`
(`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/index/IndexableFieldType.java:27`) and the
analyser is chosen per field by the wrapper `PerFieldAnalyzerWrapper`, whose entire API is a single
constructor with a map:

```java
public PerFieldAnalyzerWrapper(Analyzer defaultAnalyzer, Map<String, Analyzer> fieldAnalyzers)
```

The degrees of what can be baked into the postings are expressed in Lucene **by an enumeration, not by a
set of booleans** (`IndexOptions.java:26`): `NONE`, `DOCS`, `DOCS_AND_FREQS`,
`DOCS_AND_FREQS_AND_POSITIONS`, `DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS` and `DOCS_AND_CUSTOM_FREQS`.
Solr has two independent booleans in the same place (`omitTermFreqAndPositions`, `omitPositions`) and
translates them into the same enumeration. **An enumeration is more legible, because it does not allow a
nonsensical combination to be expressed** — and that is a direct recommendation for evitaDB everywhere a
pair of flags would otherwise suggest itself.

The freezing mechanism is worth a mention too: `FieldType` has a field `frozen`, a method `freeze()` and
a guard `checkIfFrozen()`, which **every setter without exception calls as its first statement**
(`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/document/FieldType.java:43-95`). A field type
is mutable only until it is first used. That is exactly the protection we will need on the boundary
between the definition and the first write — and Lucene solves it with a single boolean, not with a
state machine.

**Over Lucene, Solr builds `managed-schema` and it is the closest model of our need.** The type
`text_general` from the default configset
(`/www/oss/solr/solr/server/solr/configsets/_default/conf/managed-schema.xml:299`):

```xml
<fieldType name="text_general" class="solr.TextField" positionIncrementGap="100">
  <analyzer type="index">
    <tokenizer name="standard"/>
    <filter name="stop" ignoreCase="true" words="stopwords.txt" />
    <filter name="lowercase"/>
  </analyzer>
  <analyzer type="query">
    <tokenizer name="standard"/>
    <filter name="stop" ignoreCase="true" words="stopwords.txt" />
    <filter name="synonymGraph" synonyms="synonyms.txt" ignoreCase="true" expand="true"/>
    <filter name="lowercase"/>
  </analyzer>
</fieldType>
```

The whole contract is visible in this example. A chain is an ordered sequence of elements with exactly
one tokenizer and any number of filters; every element is a **name from a registry plus a map of named
parameters**; there is no Java code in the configuration. And the index and query chains differ exactly
in what they are meant to differ in — synonyms only in the query.

That an analyser can really be assembled from such a purely data specification is documented by Lucene
itself with the class `CustomAnalyzer`
(`lucene/analysis/common/…/analysis/custom/CustomAnalyzer.java:73`):

```java
Analyzer ana = CustomAnalyzer.builder(Paths.get("/path/to/config/dir"))
  .withTokenizer("standard")
  .addTokenFilter("lowercase")
  .addTokenFilter("stop", "ignoreCase", "false", "words", "stopwords.txt")
  .build();
```

The names are translated by an SPI registry right in the Lucene core: `TokenizerFactory.forName(...)`,
`availableTokenizers()` and the sibling `TokenFilterFactory` with `CharFilterFactory` have an identical
interface (`TokenizerFactory.java:51-66`). The registry is moreover **monotone** — `reloadTokenizers`
explicitly only adds and never removes or replaces (ibid., `:79`), which is a very sensible property for
a persistent schema: a name once written down will always be loadable.

The only crack in the data-ness are parameters referring to files (`words="stopwords.txt"`,
`synonyms=`). Those require a `ResourceLoader`, i.e. someone to translate the resource name into
content (`CustomAnalyzer.java:105-122`). For evitaDB that means the contract has to include a story
about where the dictionaries live and how they survive replication of the catalogue onto another node.

**In Solr the field weights are demonstrably in the query, not in the schema.** The `qf` parameter is
parsed by `SolrPluginUtils.parseFieldBoosts`
(`solr/core/…/apache/solr/util/SolrPluginUtils.java:504-508`) and the whole mechanism is splitting a
string by a caret. In the schema two things nevertheless remain that influence the score: `omitNorms`,
because switching norms off permanently discards the length normalisation, and the `<similarity>`
element. **That dividing line is exactly ours:** the parameters of the similarity function and the
decision about norms belong in the schema, because they affect the shape of the written data; per-field
weights belong in the query, because they are a property of the scenario.

Solr's answer to schema change at runtime is the worst possible and deserves quoting verbatim, because
it shows what to avoid (`solr-ref-guide/modules/indexing-guide/pages/schema-api.adoc:51-57`):

> If you modify your schema, you will likely need to reindex all documents. If you do not, you may lose
> access to documents, or not be able to interpret them properly (…) Queries and updates made after the
> change may encounter errors that were not present before the change. Completely deleting the index and
> rebuilding it is usually the only option to fix such errors.

Solr therefore permits the change and leaves the responsibility for consistency to the operator — with
three warning blocks in the documentation. Vespa does exactly the opposite: it refuses and asks for a
time-limited exception. **evitaDB is today unfortunately closer to Solr than to Vespa, and without any
doing of its own** (§4.3).

### 3.3 Elasticsearch (from the web only, with a reservation)

Elasticsearch separates `mappings` (field types, the analyser per field) from `settings.analysis`
(definitions of custom analysers from a tokenizer and filters). A custom analyser is declared by name in
the settings and a field refers to it by name in the mappings — in shape it is therefore Solr's model
split into two sections. Settings are divided into static (changeable only on a closed index) and
dynamic; a change of analyser is among the static ones and requires reindexing through the Reindex API
into a new index, with an alias switch. Since version 8.10 synonyms are a server-side set changeable
without reindexing (the Synonyms API), as are curation rules (Query Rules, GA 8.15) — in both cases
therefore a hot-swap artefact, not the schema.

The sources of this section are from the web, without reading the source code; the version numbers are
marked with the same reservation in the research (§8, VK13).

**A note on the reservation.** Since this section was written a local checkout of Elasticsearch has been
added (`/www/oss/elasticsearch`, branch `main`, commit `9a100e2d0e41`, 2026-08-13), so the claims in
§6.5, §6.7 and §7.2 that refer to Elasticsearch **do have an anchor in the source code** and the
reservation does not apply to them. The text of this section stays in its original form; whoever updates
it has the checkout available.

### 3.4 Meilisearch — index settings and the finest model of reindexing

Meilisearch has no field schema; it has **index settings** which are set through the API. What matters
for us is that its decision about recomputation is the finest of the whole sample. An extract from the
table verified in the source (all anchors point into
`/www/oss/meilisearch/crates/milli/src/update/settings.rs`):

| setting change                          | reindex?                       | anchor                              |
|-----------------------------------------|--------------------------------|-------------------------------------|
| `searchableAttributes` — adding a field  | yes, only the added fields    | `:1692`, `:1870`                    |
| `searchableAttributes` — removing a field| yes, fully                    | `:1723`                             |
| `searchableAttributes` — order only      | **no**                        | `:1724` (comment)                   |
| `stopWords`                              | yes                           | `:1710`                             |
| `separatorTokens` / `dictionary`         | yes                           | `:1712`, `:1713`                    |
| `localizedAttributes`                    | yes — postings **and** facets | `:1716`, `:1954`                    |
| `proximityPrecision`                     | yes                           | `:1714`                             |
| `synonyms`                               | **no**                        | not a member of `InnerIndexSettings`|
| `rankingRules`                           | **no**                        | `:1478`                             |
| `filterableAttributes`                   | yes, facets only              | `:1909`                             |
| `embedders`                              | yes, vectors only             | `:1963`                             |

Two things from that table are directly usable. First, **the granularity goes down to a single field and
to distinguishing "add" from "overwrite"**: `reindex_searchable_id` (`:1870`) returns `None` for
untouched fields and a mere `Addition` instead of `DeletionAndAddition` when the list was only added to
— adding a new searchable attribute is thus cheap even over a large index.

Second, **synonyms do not trigger reindexing, and structurally so**: they are not part of
`InnerIndexSettings` at all, so the diff does not see them and cannot react to them. That is a stronger
guarantee than an exception in a condition. Instead the synonyms are stored already pre-tokenized
(`update_synonyms`, `settings.rs:700`) and the code forces their re-tokenization itself whenever the
stop words, the separators or the dictionary change (the same pattern three times, for instance
`settings.rs:642`).

The most striking feature of Meilisearch, however, is that **the order in `searchableAttributes` is the
field weight**. The weight is literally the index in the list — `Some(i as u16)` in
`/www/oss/meilisearch/crates/milli/src/fields_ids_map/metadata.rs:571` — and it is stored separately from
the postings (`fieldids-weights-map`), so changing it is free and yet immediately effective. The postings
are keyed by *field id*, not by weight, which is exactly why the two things are independent.

### 3.5 Typesense — everything hangs on the field, but the weight is missing from the schema

Typesense has no index settings at all; it has **a collection schema and all the fulltext configuration
hangs on the individual fields**. The `field` structure (`/www/oss/typesense/include/field.h:116`)
carries, alongside the name and type, the flags `facet`, `index`, `sort`, `infix`, `range_index`,
`store`, then `locale`, `stem` and `stem_dictionary` for language processing, `token_separators` and
`symbols_to_index` **per field** (which Meilisearch cannot do — it has them only globally), and finally
`num_dim`, `vec_dist` and `hnsw_params` for vectors.

**A per-field weight in the schema, however, does not exist.** The weight is given only in the query by
the parameter `query_by_weights` (`/www/oss/typesense/include/collection.h:95`), and when the user does
not give it, it is derived in descending order from the order in `query_by` (`src/collection.cpp:4801`).
An essential limitation that the documentation does not stress is a hard cap
(`/www/oss/typesense/include/index.h:730`):

```cpp
    // Values 0 to 15 are allowed
    enum {FIELD_MAX_WEIGHT = 15};
```

Weights given outside the range are rescaled so that only the **order is preserved, not the ratios**
(`src/collection.cpp:4830`) — the weights `100, 50, 1` and `3, 2, 1` give an identical result. Both
reference e-commerce engines are therefore effectively **ordinal**: Meilisearch admits it by not allowing
a weight to be given at all, Typesense pretends to take a number and internally reduces it to sixteen
levels. A continuous `float` boost per field would be above and beyond both, and it is worth deciding on
it deliberately, not by accident.

Schema change at runtime is markedly coarser in Typesense than in Meilisearch. Direct modification of an
existing field is not supported — a drop and an add in a single payload are necessary; a type change is
limited to `int32 → int64`; adding a field always means a **full scan of all stored documents** and for
the duration of the indexing it blocks writes with an exclusive lock; and `alter` **is neither atomic nor
does it have a rollback** — a failure in the middle leaves the schema changed in memory but not
persisted. Changing the `locale` of an existing field is possible only through a drop and an add.

### 3.6 Algolia (from the web only, with a reservation)

Algolia has no field schema; it has index settings, where `searchableAttributes` is again an ordered list
and the order carries importance (with the option of marking a field as `unordered`, which switches off
the criterion of position inside the attribute). Synonyms are a per-index hot swap, as are Rules and the
boost table of Dynamic Re-Ranking learned from clicks. An essential structural limitation is the record
size limit (10 or 100 kB depending on the plan), because of which long documents are split into sections
as separate records and composed back through `distinct` — Algolia's market answer to long text is
chunking, not a better scoring function.

### 3.7 Synthesis: what is baked into the index where, and what is changeable at runtime

A cell says **where the configuration lives**; an asterisk after it means that changing it requires a
rebuild of the index. The column "ES" is Elasticsearch, "Meili" is Meilisearch.

| Configuration           | Vespa                     | Solr             | ES         | Meili       | Typesense |
|-------------------------|---------------------------|------------------|------------|-------------|-----------|
| searched fields         | schema\*                  | schema\*         | mapping\*  | setting\*   | field\*   |
| analyser and tokenization | schema\*                | schema\*         | settings\* | setting\*   | field\*   |
| separate query chain    | yes                       | **yes**          | partly     | no          | no        |
| field weight            | rank profile              | **query**        | query      | field order | **query** |
| order of rank criteria  | rank profile              | configuration    | query      | setting     | query     |
| synonyms                | artefact (FSA)            | managed, reload  | API        | **free**    | free      |
| curation rules          | artefact                  | reload           | API        | pin only    | free      |
| boost table             | no                        | LTR store        | no         | no          | no        |
| length normalisation    | recomputation from index  | schema\*         | mapping\*  | built in    | built in  |
| vector: dimension       | data type\*               | schema\*         | mapping\*  | embedder\*  | field\*   |
| vector: metric          | refused                   | schema\*         | mapping\*  | embedder\*  | field\*   |

**Four lessons for evitaDB** follow from the table, ordered by weight:

1. **Nobody has the field weight baked into the index.** It is either in the query (Solr, Typesense,
   Elasticsearch), or in configuration read only at ranking time (a Vespa rank profile, the Meilisearch
   weights map). The evitaDB schema may carry at most a *default value*, and it has to hold it in such a
   way that changing it never touches the postings.
2. **The pair of an index chain and a query chain is the only known way of changing analysis cheaply.**
   Vespa can do it (`linguistics { profile { index: … search: … } }`) and so can Solr
   (`<analyzer type="index">` and `type="query"`), and in both cases changing the query branch is free.
   Without that split every intervention into analysis is a full rebuild.
3. **Changing the language analysis is the most expensive knob and nobody can do it cheaply.** In
   Meilisearch `localizedAttributes` is the only setting triggering a recomputation of the postings and
   the facets at once; in Typesense `locale` cannot be changed other than by a drop and an add. That
   strongly suggests it cannot be done cheaply — and that in evitaDB the per-locale analyser should be
   either immutable, or explicitly marked as an operation requiring a rebuild.
4. **Distinguish "read the source document" from "compute it from the index".** Vespa is the only one to
   have this and it gains by it that `enable-bm25` is a free change. On this point evitaDB has a potential
   advantage over the market, see §7.

---

## 4. The starting state of evitaDB

### 4.1 What the schema knows about indexing today

An attribute carries three indexing flags, all **scope-aware**, i.e. settable separately for
`Scope.LIVE` and `Scope.ARCHIVED`: `isFilterableInScope`, `isSortableInScope` and `isUniqueInScope`
(`AttributeSchemaContract.java:249`, `:294`, `:125`). Beside them stand flags that are **not**
scope-aware — `isLocalized()`, `isNullable()`, `isRepresentative()` and `getIndexedDecimalPlaces()`.

That division is not accidental and is a guide for us. The annotation `@ScopeAttributeSettings`
(`evita_api/…/data/annotation/ScopeAttributeSettings.java:44-74`) contains, of the flags, only `unique`,
`uniqueGlobally`, `filterable` and `sortable` — i.e. exactly those that create an index. `localized`,
`nullable` and `representative` are not there.

The editor realises every scope-aware flag with **exactly five methods**, two abstract and three default
(`AttributeSchemaEditor.java:73-129`):

```java
	@Nonnull
	default T filterable() {
		return filterableInScope(Scope.DEFAULT_SCOPE);
	}

	@Nonnull
	T filterableInScope(@Nonnull Scope... inScope);

	@Nonnull
	default T filterable(@Nonnull BooleanSupplier decider) {
		return decider.getAsBoolean() ? filterable() : nonFilterable();
	}

	@Nonnull
	default T nonFilterable() {
		return nonFilterableInScope(Scope.values());
	}

	@Nonnull
	T nonFilterableInScope(@Nonnull Scope... inScope);
```

**Associated data, by contrast, is a greenfield.** `AssociatedDataSchema`
(`evita_api/…/schema/dto/AssociatedDataSchema.java:51-59`) has nine fields and **none of them concerns
indexing**; the editor offers only `localized()` and `nullable()`, each in two variants
(`AssociatedDataSchemaEditor.java:47-76`). The set of eleven mutations in
`schema/mutation/associatedData/` matches that, and not one of them is an indexing flag.

Locales live on the entity schema as `Set<Locale> getLocales()` (`EntitySchemaContract.java:280`), are
added by the method `withLocale(...)` (`EntitySchemaEditor.java:428`) and are validated during indexing
by the method `verifyLocalizedAttribute` (`AttributeIndex.java:266`), which checks at the same time that
a localized value has a locale and that it is among the permitted ones.

### 4.2 How a flag becomes an index

The route is short and worth describing in full, because `searchable()` will follow it.

An attribute mutation is dispatched to a handler in `LocalMutationHandlerRegistry` (`:56`), which through
the shared `AttributeMutationFanOut` (`:65`) calls
`EntityIndexLocalMutationExecutor.updateAttribute` (`:1144`), and that selects a method in
`AttributeIndexMutator` according to the type of mutation. There is the **single coarse gate** — the
question "does this attribute belong in the indexes at all?" (`AttributeIndexMutator.java:176-180`):

```java
if (
    attributeDefinition.isUniqueInScope(scope) ||
        attributeDefinition.isFilterableInScope(scope) ||
        attributeDefinition.isSortableInScope(scope)
) {
```

The actual selection of structures happens only in `EntityIndex.upsertAttribute` (`EntityIndex.java:633`),
where the flags are read again and the individual primitives are called. The write path reads these three
flags in **exactly four places** — `AttributeIndexMutator.java:177-179` and `:320-321`,
`EntityIndex.java:642-644` and `:695-697`. The list is exhaustive, not illustrative; all the other
occurrences are the read path.

Two things on that route are essential for the design.

**The structure comes into being lazily, at the first write of a value**, not at the schema change. The
write path requests it through `getOrCreateFilterView` (`AttributeIndex.java:1637`), which produces the
shared tree only when it is missing. A localized attribute moreover gets a separate structure for each
locale, because `AttributeIndexKey` carries the locale exactly when `attributeSchema.isLocalized()`
(`AttributeIndex.java:323`) — and only for those locales that were actually written.

**`FilterIndex` is not an owned structure but a view.** The owner of the data is the shared
`InvertedIndex` in `sharedValueIndex` (`AttributeIndex.java:201`); the map `filterIndex` (`:166`) is a
derived cache of views that hold no transactional state themselves and are discarded and re-derived at
commit time by the method `buildFilterViews` (`:524`). By contrast `sortIndex`, `chainIndex` and the
standalone `uniqueIndex` are real owners with their own commit walk and serialization. **For the fulltext
structure the choice of owner versus view will determine the commit walk, the serialization and the
behaviour on emptying** — it is a decision that has to be made deliberately.

One claim of the research also needs correcting. §4.1 says that the fulltext structures live "exclusively
in `GlobalEntityIndex`". But `AttributeIndex` does not live in `GlobalEntityIndex`, it lives in the
common ancestor `EntityIndex` (`EntityIndex.java:122`), and the reduced indexes have it too. Restricting
fulltext to the global index is therefore **a legitimate design decision that has to be enforced**, not a
property the architecture gives for free. It is enforced exactly the way `ReferencedTypeEntityIndex`
does it for the sorting structure — by overriding the method with an empty body and a comment on why the
structure is not maintained (`ReferencedTypeEntityIndex.java:620`).

### 4.3 Reindexing: the mechanism does not exist and a change passes silently

**This is the most serious finding of the whole document.**

If `filterable()` is switched on today for an attribute that already has data written, **the index stays
empty and nothing computes it retrospectively.** The application of schema mutations is in
`EntityCollection.updateSchema` (`EntityCollection.java:966`) and the whole loop contains a **single**
case where an index is touched — the hierarchy — and above it hangs an admission
(`EntityCollection.java:978`):

```java
/* TOBEDONE #409 JNO - this should be diverted to separate class and handle
   all necessary DDL operations */
```

There are three supporting pieces of evidence. The class `SetAttributeSchemaFilterableMutation` has
**not a single use** in the `evita_engine` module. Its validation does exist, but it guards something
else — that filterability is not switched off on an attribute referenced by a histogram expression
(`SetAttributeSchemaFilterableMutation.java:239`, `:310`, `:353`); it never checks existing data. And
searching for `reindex`, `rebuildIndex` or `recreateIndex` across `evita_engine` and `evita_api` returns
no mechanism — all the occurrences are exception texts or comments about locally reindexing a single
entity.

In practice this means that after switching a flag on over existing data, the index will start filling
up only with those entities that receive a new mutation. Until an entity receives a mutation, it is
simply not in the structure — and **a query over it returns an incomplete result entirely silently.**

The documented answer is work outside the engine. The file
`documentation/user/en/deep-dive/bulk-vs-incremental-indexing.md:48` has a section "Full reindex of the
live catalog":

> The recommended approach is to create a new temporary catalog and fill it with an initial set of data
> using bulk indexing. Once the new catalog is fully indexed, you can switch your application to the new
> catalog using the replace catalog operation.

The only place in the engine where something is genuinely de-indexed and re-indexed for a single change
is a scope change — `SetEntityScopeMutationHandler`, whose core is five lines with the load-bearing order
`removeEntityFromIndexes` → `setMemoizedScope` → `addEntityToIndexes`. It is the best available model for
switching `searchable()` and carries three lessons: the order is load-bearing, the values have to be
captured before removal, and the handling does not fit into the shared fan-out helpers.

### 4.4 The cost of introducing a new field into the schema

The repository has a ready eight-layer recipe for this work — the skill
`.claude/skills/evita-schema-change/SKILL.md`. It is nevertheless good to know the scope in advance,
because it influences the choice of the shape of the API.

Adding a single field to `AttributeSchema` means touching **eighteen signatures** of `_internalBuild()`
(six overloads in each of the three classes `AttributeSchema`, `EntityAttributeSchema` and
`GlobalAttributeSchema`) and over **sixty call sites** — and what is worse, **none of them fails to
compile** if it reaches for a shorter overload. The new field then silently disappears. A single mutation
then lives in **52 files** across seven modules.

Three traps worth naming individually:

- `AttributeSchema.withInvertedType()` calls the private constructor directly at lines 541 and 558;
  searching for `_internalBuild` will not find it.
- Both overloads of `combineWith(...)` in `ModifyAttributeSchemaTypeMutation` (`:94`, `:111`) have a
  **hardcoded list** of classes with which a type change is swapped in order. The new mutation has to be
  added there, otherwise it will behave differently from its siblings — and neither a test nor the
  compiler will report it.
- The backward-compatible Kryo reader is established against the **latest released branch**, which is
  today `origin/release_2026-2` — so the suffix is `_2026_2`, not `_2026_1` as the contents of the
  directory might lead one to conclude.

A suitable `EvolutionMode` for fulltext does not exist and cannot be derived: the documentation of
`ADDING_ATTRIBUTES` (`EvolutionMode.java:46-49`) is explicit that an automatically added attribute comes
into being unindexed. A new `searchable` will therefore be automatically off under schema evolution,
which is consistent with all its siblings and, given the cost of a fulltext index, desirable.

---

## 5. A design for evitaDB: three shapes of API

All three variants show **the same example**: a collection of products with two locales, where `name` is
a short localized name of the highest importance, `brand` is a short non-localized string of medium
importance and `description` is a long localized text of low importance and a different pivot of the
length normalisation.

The idiom of the snippets follows
`documentation/user/en/get-started/example/define-catalog-with-schema.java`: indentation by tabs, the
lambda parameter `whichIs` at the outer level and `thatIs` exclusively for a nested attribute on a
reference, ending with `updateVia(session)`.

### 5.1 Variant A — flat sibling flags

The most conservative shape: `searchable` behaves exactly like `filterable` and `sortable`, the other
parameters are separate methods beside it.

```java
session.defineEntitySchema("Product")
	.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
	.withAttribute(
		"name", String.class,
		whichIs -> whichIs
			.localized()
			.filterable()
			.searchable()
			.searchWeight(SearchWeight.PRIMARY)
			.searchAnalyzer("ecommerce-short")
	)
	.withAttribute(
		"brand", String.class,
		whichIs -> whichIs
			.filterable()
			.searchable()
			.searchWeight(SearchWeight.SECONDARY)
			.searchAnalyzer("ecommerce-short")
	)
	.withAttribute(
		"description", String.class,
		whichIs -> whichIs
			.localized()
			.searchable()
			.searchWeight(SearchWeight.SUPPLEMENTARY)
			.searchAnalyzer("ecommerce-long")
			.searchLengthPivot(180)
	)
	.updateVia(session);
```

**For:** it fits into the established set of five methods without a single exception, every parameter is
independently settable, the mutations can be composed one by one and merged by the existing pipeline.

**Against:** five new mutation classes mean **five times** the whole plumbing work of layers 4 to 8
(§4.4), i.e. roughly 250 files touched. And above all — a flat list of methods **in no way signals which
of them are free and which trigger a rebuild.** `searchWeight` is free, `searchAnalyzer` and
`searchLengthPivot` are not, and from the calling code that is not apparent. That is exactly the mistake
Meilisearch made with `typoTolerance` (§2).

### 5.2 Variant B — one flag carrying components

An aggregated value object carrying the whole fulltext configuration of a field at once. The closest
existing shape in the repository is
`indexedWithComponentsInScope(Scope, ReferenceIndexedComponents...)` at
`ReferenceSchemaEditor.java:142`.

```java
session.defineEntitySchema("Product")
	.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
	.withAttribute(
		"name", String.class,
		whichIs -> whichIs
			.localized()
			.filterable()
			.searchable(
				SearchSettings.builder()
					.weight(SearchWeight.PRIMARY)
					.analyzer("ecommerce-short")
					.build()
			)
	)
	.withAttribute(
		"description", String.class,
		whichIs -> whichIs
			.localized()
			.searchable(
				SearchSettings.builder()
					.weight(SearchWeight.SUPPLEMENTARY)
					.analyzer("ecommerce-long")
					.lengthPivot(180)
					.build()
			)
	)
	.updateVia(session);
```

**For:** one field in the DTO, one item in each of the eighteen `_internalBuild()`s, one message in the
`.proto`, one WAL serializer. The plumbing work drops to roughly a fifth of variant A.

**Against:** the object **straddles the boundary of the cost of change** — it carries the weight (free)
and the analyser with the pivot (rebuild) at the same time. A user who only wants to raise the weight
sends a mutation from which it is impossible to tell that the other fields stayed the same; the engine
would have to compare contents in order to make the change cheap. Meilisearch does it that way
(`InnerIndexSettingsDiff` compares contents, not presence), but it is extra work and it is easily
forgotten. Moreover a partial modification from a client API requires a read-modify-write of the whole
object.

### 5.3 Variant C — a named fulltext profile on the entity schema

The analyser and the pivot are not declared on the attribute but as a **named profile at the schema
level**; the attribute merely refers to the profile by name. The model is Solr's `fieldType` and Vespa's
`linguistics { profile }`.

```java
session.defineEntitySchema("Product")
	.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
	/* named analysis profiles — shared by several attributes */
	.withSearchProfile(
		"shortText",
		whichIs -> whichIs
			/* names and codes are not stemmed, so that a brand does not merge with a common word */
			.withAnalyzer(new Locale("cs", "CZ"), "czech-minimal")
			.withAnalyzer(Locale.ENGLISH, "english-minimal")
			.withLengthPivot(12)
	)
	.withSearchProfile(
		"longText",
		whichIs -> whichIs
			/* continuous prose: stemming pays off, length is normalised towards the pivot */
			.withAnalyzer(new Locale("cs", "CZ"), "czech-prose")
			.withAnalyzer(Locale.ENGLISH, "english-prose")
			.withLengthPivot(180)
	)
	.withAttribute(
		"name", String.class,
		whichIs -> whichIs
			.localized()
			.filterable()
			.searchable("shortText")
			.searchWeight(SearchWeight.PRIMARY)
	)
	.withAttribute(
		"brand", String.class,
		whichIs -> whichIs
			.filterable()
			.searchable("shortText")
			.searchWeight(SearchWeight.SECONDARY)
	)
	.withAttribute(
		"description", String.class,
		whichIs -> whichIs
			.localized()
			.searchable("longText")
			.searchWeight(SearchWeight.SUPPLEMENTARY)
	)
	.updateVia(session);
```

**For:** the cost boundary is visible in the very shape of the API. Everything inside the profile is
expensive, everything on the attribute is either trivial (`searchable` — it creates the structure of a
single field) or free (`searchWeight`). The mapping of locale onto analyser finally has a natural home —
today, with variants A and B, it would have to be repeated for every attribute. And because the profile
is shared, changing the analyser for a whole class of fields is a single mutation, not ten.

**Against:** it introduces a **new named object** into the schema, i.e. its own contract, editor, DTO,
mutations for creation, change and removal, referential integrity (a profile that someone uses cannot be
deleted) and its own mirroring into three external APIs. It is the largest one-off investment of all
three variants. Moreover it is a new abstraction the user has to understand before indexing the first
field.

### 5.4 Recommendation

**I recommend variant C, but introduced in two steps.**

The decisive argument is the boundary of the cost of change (§2). Variant C is the only one in which it
is visible in the shape of the API — and that is a property which cannot be added later, because it would
mean breaking an already released interface. The second argument is localization: the analyser is mapped
**per locale**, and in both the other variants that mapping would be repeated for every attribute, which
is exactly the kind of duplication that diverges in practice.

Introducing it in two steps reduces the risk:

- **Step 1 (prototypes P5/P1):** only `searchable()` without an argument, with a **built-in default
  profile** derived from the entity's `getLocales()`. No `withSearchProfile`, no `searchWeight`. That is
  enough for the prototypes to measure what they are meant to measure, and it is a single new mutation.
- **Step 2 (delivery F1):** named profiles, `searchable(String profileName)` and `searchWeight(...)`. The
  overload `searchable()` without an argument stays and means "the default profile", so step 1 does not
  become technical debt.

The weight stays **on the attribute even in variant C**, and deliberately so — it is free, it changes
independently of the profile and it concerns a particular field, not a class of fields. The fact that it
lies outside the profile is itself a carrier of the information "this is cheap".

If variant C were evaluated as too expensive for the first round, **the second choice is variant A**, not
B — because although a flat list does not signal the cost boundary, it at least does not violate it: every
mutation is single-purpose and the engine can tell from it what changed. Variant B is the worst of both
worlds, because to the invisibility of the boundary it adds the need to compare contents.

**Considered and rejected (2026-08-25): folding `searchable` into `filterable(...)` as a capability
argument.** The question arose when the trigram substring flag (`p8-trigram-substring-index.md`) adopted
exactly that shape — `filterable(FilterIndexCapability.SUBSTRING)` — and it deserves an answer here
because the two decisions look contradictory and are not. The discriminator: **fold a capability into a
host flag only when it is a pure same-semantics accelerator of that flag's own index and cannot exist
without it.** Substring passes on all counts — it is a view over the filter index's value tree, shares
its NFD normalization contract, returns byte-identical results to the scan, and has nothing to configure.
`searchable` fails on all three:

1. **It exists without `filterable` — and that is the normal case.** A long description is searchable
   but never filtered by equality or range; folding would force building the exact-match value tree
   (the dominant heap term per the P8 replication census) for attributes that only search — paying real
   memory for API symmetry.
2. **It carries parameters** — the profile and the weight. Pushing those into `filterable`'s argument
   list recreates variant B inside a method call, with the same invisible cost boundary this section
   already rejected.
3. **It changes the query surface** — a new constraint (`attributeMatches`) and ranking, not a faster
   path for constraints `filterable` already grants. A `filterable` argument that widens the query
   language beyond the filter family would make the flag's name a lie.

The principle does transfer in one direction: future sub-capabilities *of the fulltext index itself*
(suggester participation, vectors) belong inside the search profile — variant C's container for exactly
this — not as sibling flags beside `searchable()`.

### 5.5 The complete set of methods of the recommended variant

The snippets above show only ordinary usage. Because the choice of variant per S1 is practically
irreversible, the whole proposed surface of the `AttributeSchemaEditor` interface belongs here:

```java
	/* --- the flag: scope-aware, with a profile choice --- */

	@Nonnull
	default T searchable() {
		return searchableInScope(DEFAULT_SEARCH_PROFILE, Scope.DEFAULT_SCOPE);
	}

	@Nonnull
	default T searchable(@Nonnull String searchProfile) {
		return searchableInScope(searchProfile, Scope.DEFAULT_SCOPE);
	}

	@Nonnull
	T searchableInScope(@Nonnull String searchProfile, @Nonnull Scope... inScope);

	@Nonnull
	default T searchable(@Nonnull BooleanSupplier decider) {
		return decider.getAsBoolean() ? searchable() : nonSearchable();
	}

	@Nonnull
	default T nonSearchable() {
		return nonSearchableInScope(Scope.values());
	}

	@Nonnull
	T nonSearchableInScope(@Nonnull Scope... inScope);

	/* --- the weight: outside the profile, because changing it is free --- */

	@Nonnull
	T searchWeight(@Nonnull SearchWeight weight);
```

Two small things deserve a deliberate decision, because they are easy to overlook until implementation.

**The order of the parameters is forced by varargs.** The reference side of the schema has the scope
first — `indexedWithComponentsInScope(@Nonnull Scope scope, @Nonnull ReferenceIndexedComponents...
components)` at `ReferenceSchemaEditor.java:142`. The attribute side, by contrast, has the scope as
varargs (`filterableInScope(@Nonnull Scope... inScope)`), and varargs have to come last. Either the
convention of the attribute side is broken, or the profile is put first. The design chooses the second
option, because the family `...InScope(Scope...)` is more numerous on the attribute and breaking it would
be more conspicuous.

**The overloads `searchable(String)` and `searchable(BooleanSupplier)` are legal, but
`searchable(null)` will not compile** because of ambiguity. In practice nobody writes that, so I do not
consider it blocking; if it were a problem, the clean solution is to name the variant with the profile
differently (`searchableUsing(String)`), at the cost of a new convention in the interface.

---

## 6. The individual configuration items

### 6.1 The `searchable` flag on an attribute

**Scope-aware, five methods after the model of `filterable`.** The argument for scope-awareness is
direct: `@ScopeAttributeSettings` contains exclusively the flags that create an index (§4.1), and
`searchable` creates an index. The argument against — that the archived scope is not searched by fulltext
— does not hold, because the research (§4.1) explicitly assumes a per-scope division for the fulltext
structures.

The contract therefore gets `isSearchableInScope(Scope)`, `isSearchable()`, `isSearchableInAnyScope()`
and `getSearchableInScopes()`; the editor gets the five
`searchable()` / `searchableInScope(Scope...)` / `searchable(BooleanSupplier)` / `nonSearchable()` /
`nonSearchableInScope(Scope...)`. The naming convention for the negation is taken from the attribute side
(`nonSearchableInScope`), not from the reference side (`nonIndexed(Scope...)`) — the sibling interfaces
disagree on this and one has to be chosen.

**The range of types in the first round: only `String` and `String[]`.** Other types have to be rejected
by validation at schema build time, not silently ignored. The precedent is right at hand:
`AbstractAttributeSchemaBuilder.java:548-551` rejects `sortable` over an array of values with an
`InvalidSchemaMutationException` and an explanatory message, and `:552-559` rejects `filterable` or
`unique` over a type that does not implement `Comparable`. The type validation of `searchable` belongs in
the same method and should look the same.

**Consistency rules** that have to be decided (`filterable` has an analogue at
`AbstractAttributeSchemaBuilder.java:560-563`, where the combination of `unique` and `filterable` is
forbidden on the grounds that unique attributes are filterable implicitly):

- May a `searchable` attribute be on a non-indexed reference? For `filterable` that is a hard error
  (`SetAttributeSchemaFilterableMutation.java:232-247`). The proposal: **yes, it may** — if the fulltext
  structures live only in the global index (§4.2), the indexedness of the reference is irrelevant.
- Does `searchable` imply anything for `filterable`? The proposal: **no.** They are different contracts —
  `attributeContains` is an exact substring, `attributeMatches` is an analysed match (research §4.4). They
  must not be mixed.

### 6.2 The `searchable` flag on associated data (O6)

The research marks this as a planned extension which for the CMS profile (Z8) is a **precondition for
production deployment**. The shape is the same as for an attribute, but it has to be understood that
budget-wise it is **a separate piece of work comparable to the attribute variant** (§4.1) — associated
data has no indexing flag today, so it is not a matter of adding a field to an established pattern but of
the first indexing flag at all.

```java
session.defineEntitySchema("Article")
	.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
	.withAssociatedData(
		"body", String.class,
		whichIs -> whichIs
			.localized()
			.nullable()
			.searchable("longText")
	)
	.updateVia(session);
```

**The range in the first round: only `String` and localized `String`.** A selector of text paths inside a
`ComplexDataObject` is deferred until asked for — it is a separate task comparable to Typesense's
`enable_nested_fields` and there is no point in solving it before it is clear whether anyone needs it.

One mechanical point is open which the schema does not decide but does demand: associated data is today
stored separately and loaded lazily. Indexing requires its value on the write path, so it either has to be
loaded on every write (a toll even for those who do not use fulltext), or the indexing has to be deferred
to trunk incorporation (which the research admits in §4.5(3) as a fallback).

### 6.3 The default field weight

**Recommendation: named levels, not a number.**

The argument is empirical. Both reference e-commerce engines are ordinal — Meilisearch does not allow a
weight to be given at all and derives it from the position in the list, Typesense pretends to take a
number and internally reduces it to sixteen levels preserving nothing but the order (§3.5). A continuous
`float` would be above and beyond both, and something above and beyond that nobody needs is just another
knob to tune.

Practical reasons for an enumeration: it survives Kryo and gRPC evolution better than a floating-point
number (names, not ordinals — `EnumNameSerializer`, as layer 7 of the recipe requires); it does not allow
the user to tune relevance in the schema when they should be tuning it in the query; and it reads better.

A proposed enumeration with four values, covering "name > brand > description" from the brief with room to
spare:

```java
public enum SearchWeight {
	PRIMARY,          // product name, article headline
	SECONDARY,        // brand, category, code
	SUPPLEMENTARY,    // short description, parameters
	MARGINAL          // long text, image transcript
}
```

**A per-query override always exists** (research §4.2: the `Text` node of the client AST carries
`name^3, description`), so the schema carries only a default for the rank profile. A technical requirement
follows from that: the value **must not be stored in the postings nor in the impact byte** but separately,
so that changing it never requires a rebuild — exactly like Meilisearch's `fieldids-weights-map`
(`index.rs:61`).

The weight should not be scope-aware. It is a ranking parameter, not the existence of an index, and
`indexedDecimalPlaces` is a precedent for a non-scope-aware value on an attribute.

### 6.4 The pivot of the length normalisation

The pivot belongs **in the same group as the analyser**, because it is baked into the impact byte at
indexing time (§2). In variant C it therefore lives on the profile, not on the attribute.

The unit is the **expected length of the field in tokens**. For short fields (name, brand) a sensible
default is in the order of units to low tens, for long texts in the order of hundreds; at a length equal
to the pivot the normalisation is neutral. The concrete numbers in the examples above (12 and 180) are an
illustration, not a recommendation — determining them is for prototype P1 by measurement on a real
catalogue and on a CMS dataset.

A sensible default state is **a pivot derived automatically from the observed distribution of lengths**
when the index is first filled, with the option of an explicit override in the schema. That way an
ordinary user never learns about the pivot. But beware of the consequence: an automatically derived pivot
is a data-dependent state that has to be serialized with the index, and its possible recomputation is a
change of the values of the impact bytes, i.e. the same class of operation as a change of analyser. A safer
first version is **a fixed default per profile with no automation** and to leave the derivation as a later
extension.

### 6.5 Identification of the analyser

**The schema carries a name, not a definition.** That is the strongest recommendation of the whole
document and behind it stands the agreement of Vespa (`linguistics { profile }` is only an identifier, the
definition lives in a component) and Solr (`fieldType` refers to tokenizers and filters by name from an
SPI registry).

I propose the contract between the schema and the registry (whose contents is the business of prototype
P5) as follows:

1. **The analyser identifier is a string** from a monotone registry. Monotonicity is taken from Lucene
   (`TokenizerFactory.reloadTokenizers` only adds, never removes) and it is essential for a persistent
   schema: a name once written down must always be loadable, otherwise the catalogue will not open after
   an upgrade.
2. **The registry is validated at schema write time, not at the first query.** Lucene has
   `availableTokenizers()` for that, we need an analogue. An unknown name has to be a hard error at the
   moment of the schema mutation.
3. **The mapping is per locale**, because analysis is per language (research §4.1). The profile therefore
   carries a `Map<Locale, String>` plus one fallback for non-localized attributes. The locales used in the
   profile have to be a subset of `EntitySchemaContract.getLocales()` — that is a validation the schema can
   do by itself.
4. **The index and query chains are different chains, but one name.** The registry holds both under a
   single name; symmetry is the default and asymmetry is asked for, exactly as in Vespa
   (`SchemaParser.jj:403`). The reason is Solr's rule: changing the query branch is free, changing the
   index one requires a rebuild (`reindexing.adoc:74`, `:76`). If the schema carried both branches
   separately, the user would have to understand which is which; this way the difference is hidden in the
   registry, where it belongs.

   **But there are three slots, not two, and the third is not dispensable.** Elasticsearch has them
   together in the class `TextParams.Analyzers`
   (`server/src/main/java/org/elasticsearch/index/mapper/TextParams.java:33`; verified against checkout
   `main`, commit `9a100e2d0e41`, 2026-08-13): `analyzer` is used at indexing time and is at the same time
   the default value for both the others (`:47`), `search_analyzer` on the query text (`:61`) and
   `search_quote_analyzer` on **query text given in quotes**, i.e. on a phrase (`:78`). The third slot
   exists because of stop words and synonyms in phrases: an analyser that discards stop words damages the
   phrase "to be or not to be", because nothing or something else will be left of it. In Lucene no such
   concept exists — it is purely a server-side invention, forced by practice.

   It concerns us directly, and that because of the recommendation in §8 not to apply stop words at
   indexing time at all: that moves their whole agenda into the query branch, and that is exactly where the
   difference between a free and a phrase query starts to show. The recommendation is therefore **to admit
   a separate query (and prospectively phrase) chain already in the design**, even if in the first round
   both values point at the same object. Splitting it later is otherwise a change of a **locked**
   parameter, i.e. a rebuild from entities — a single slot has to serve both, so only what indexing can
   bear fits into it.
5. **The analyser's version is part of its identity for the purposes of change detection.** Lucene has the
   same problem under the name `luceneMatchVersion` and its change is per `reindexing.adoc:83` a reason to
   reindex. The registry therefore has to return a fingerprint (a version) alongside the name, and the
   schema or the index has to remember it, so that the engine can tell that the contents changed under the
   same name. **Without that a silent inconsistency after an upgrade is inevitable.**
6. **Every component of the chain declares when it may be used, and mixing is an error.** Elasticsearch
   solves this with the enumeration `AnalysisMode`
   (`server/src/main/java/org/elasticsearch/index/analysis/AnalysisMode.java`) with three values
   `INDEX_TIME`, `SEARCH_TIME` and `ALL`. Every token filter and char filter declares its mode, an analyser
   derives its mode by merging the modes of all its components, and **merging `INDEX_TIME` with
   `SEARCH_TIME` throws an exception** (`:26` and `:36`) — a chain mixing both cannot come into being. The
   connection to the schema is then direct: the `analyzer` parameter has the validator
   `checkAllowedInMode(INDEX_TIME)`, both query slots have `SEARCH_TIME` (`TextParams.java:60`, `:77`,
   `:92`).

   What is decisive is what follows from this for **hot-swap artefacts** (§8). The synonym filter has a
   configuration option `updateable`, and `SynonymTokenFilterFactory` derives the mode from it with a
   single expression: `analysisMode = updateable ? AnalysisMode.SEARCH_TIME : AnalysisMode.ALL`
   (`modules/analysis-common/src/main/java/org/elasticsearch/analysis/common/`
   `SynonymTokenFilterFactory.java:199`). By declaring the dictionary exchangeable at runtime it therefore
   **automatically becomes a component that cannot be used at indexing time** — not by convention, not by
   documentation, but by the type system of the analysers, which refuses the attempt.

   The claim in §8 that synonyms and entity dictionaries are hot-swap artefacts with no impact on the index
   structures is correct, but **fragile without enforcement**. It is enough for someone to plug synonyms
   into the indexing chain once, and exchanging the dictionary at runtime will start silently diverging the
   index from the data; it will manifest as inexplicably missing results, not as an exception. I therefore
   recommend that every component of our registry carry a mode and that the **chain refuse to be
   assembled** when it is mixed. The exchangeability of the artefact thereby turns from a promise into an
   enforced property — and it is exactly what the defensive-design rule in `CLAUDE.md` asks of us.

**Deferred here from P5 (2026-08-25, PR #1453 review).** The P5 prototype shipped the *identifier* half
of the contract of `p5-analyzers.md` §4.5 only — `AnalyzerAssignment` carries names, no parameters. Two
items therefore land in this step and must not be lost:

- **Analyzer parameters.** Named values travelling with the identifier: a custom stop-word list per
  analyzer, and switches for the optional pipeline steps (concretely the word/number split filter of
  `p5-analyzers.md` §4.6 point 2, implemented off-by-default in PR #1453 and waiting for exactly this
  switch). Note the parameter set shrank: "expressions exempted from stemming" dropped out when the
  keyword-marker protection was discarded (§4.6 point 1 — exact-match values go into separate
  non-fulltext attributes served by `attributeContains`/the trigram `SUBSTRING` lane, #1454).
- **User documentation of the unknown-language fallback** (P5-5): an unknown language silently gets the
  `generic` analyzer (tokenize + lowercase) plus a one-time log warning. That behaviour becomes
  user-visible the moment the schema exposes analyzer selection, so its documentation belongs to this
  step's deliverables, not to the inert prototype.

An illustration of the registry contract — it is not part of the schema, but it shows what the schema
refers to:

```java
public interface AnalyzerRegistry {

	@Nonnull
	Set<String> getAvailableAnalyzers();

	@Nonnull
	Optional<AnalyzerDefinition> getAnalyzer(@Nonnull String name);

	/**
	 * Fingerprint changing whenever the analyzer produces different tokens for
	 * the same input; stored alongside the index to detect silent drift.
	 */
	long getIndexTimeFingerprint(@Nonnull String name);

}
```

**Synonyms do not belong in the index chain.** Solr's default `text_general` has them exclusively in the
query branch and the research makes this precise in §1.2: the index and query analysers *deliberately*
differ. That also means the synonym dictionary can be a hot-swap artefact (§8) — changing it will never
touch the postings.

### 6.6 Prefix bitmaps

**They do not belong in the schema.** The recommendation follows directly from Vespa's `enable-bm25`
(§3.1): there it is a change of the format of the posting lists which nevertheless requires no reindex,
because the engine computes it from the already stored index with an urgent flush.

Prefix bitmaps are in the same position — they are derivable from the term dictionary that is already in
the index. Switching them on and off is therefore an operation over the index, not over the source data,
and there is no reason for it to go through a schema mutation, the WAL, gRPC or three external APIs. It
belongs in the configuration of the server or of the catalogue, where it can be changed without being
replicated into the schema.

That also gives the research (§4.8, "optional prefix bitmaps, +10–30 MB, can be switched off") the right
shape: it is an operational memory-versus-latency knob, and knobs like that do not belong in the data
model.

### 6.7 Vectors — and one blocking finding

**`float[]` is not a valid attribute type today.** The set of supported types
(`evita_common/src/main/java/io/evitadb/dataType/EvitaDataTypes.java:659-691`) contains `String`, the
integral types and their wrappers, `boolean`, `char`, `BigDecimal`, the date-time types, ranges, `Locale`,
`Currency`, `UUID`, `Predecessor`, `ReferencedEntityPredecessor` and `Expression`. **`float`, `Float`,
`double` and `Double` are not among them** — verified by reading the initialisation block directly.

That is not an oversight but a principle: evitaDB indexes decimal numbers through `BigDecimal` with
`indexedDecimalPlaces` precisely because floating-point comparison is not safe for an index. The vector
branch therefore hits a real fork that has to be decided before the first line is written:

- **(a) Extend `EvitaDataTypes` with `float[]`.** The most direct, but it breaches the principle — and the
  whole of it, not only for vectors, because the type could then be used as `filterable` as well. It would
  require a restriction that `float[]` is legal exclusively for a vector field.
- **(b) Introduce a separate data type**, for instance `VectorEmbedding`, wrapping a `float[]` and not
  implementing `Comparable`, so that it cannot be marked `filterable`, `sortable` or `unique`. The
  principle stays intact, the type system itself enforces the correct usage. **This is the variant I lean
  towards.**
- **(c) Leave the embeddings in associated data.** That has no type restriction. The disadvantage:
  associated data is loaded lazily and separately, which is unsuitable for a vector index on the write
  path, and it runs into the same question as §6.2.

The arguments above rest on evitaDB's own principle. Verification against an OpenSearch checkout (`main`,
commit `36edc05ac84`, 2026-08-12) adds a second, independent and above all **budgetary** one. Vector search
is treated there as **a separate capability of the data format, not as a type of attribute that happens to
contain an array of numbers**: the enumeration `FieldTypeCapabilities.Capability`
(`server/src/main/java/org/opensearch/index/engine/dataformat/FieldTypeCapabilities.java`) puts
`VECTOR_SEARCH` (`:39`) beside `FULL_TEXT_SEARCH`, `COLUMNAR_STORAGE`, `POINT_RANGE` and others — they are
different physical structures with their own storage, not two modes of one. The organisation of the project
says the same: the whole vector branch lives there outside the core, as a plugin in its own repository with
its own release cycle.

For us a concrete saving follows from that, and that is why it belongs here. Variant (b) means a type that
**need not be filterable, sortable nor returned as an ordinary value** — and it thereby avoids the eight
layers a fully fledged new attribute type would have to go through per the skill
`.claude/skills/evita-schema-change/SKILL.md` (contracts, DTOs, builders, mutations, gRPC, GraphQL, REST,
Kryo and WAL serializers), as well as the traps from §4.4. That is not an aesthetic difference but **a
substantial change to the effort estimate for P6** — and it is good to have it in the budget before the
vector leg is decided on the basis of what it costs.

The declaration in the schema then adopts the clean division of responsibilities from Vespa (§3.1): the
dimension is a property of the **data type**, the metric and the graph parameters are a property of the
**index**.

```java
session.defineEntitySchema("Product")
	.withAttribute(
		"embedding", VectorEmbedding.class,
		whichIs -> whichIs
			.withDimension(768)
			.nearestNeighbourIndexed(
				whichGraph -> whichGraph
					.withDistanceMetric(DistanceMetric.COSINE)
					.withMaxLinksPerNode(16)
					.withNeighboursToExploreAtInsert(200)
			)
	)
	.updateVia(session);
```

The detail is the business of prototype P6; what matters here is only that **a change of the dimension or
of the metric is a rebuild of the graph** and Vespa refuses both without an explicit exception
(`AttributeChangeValidator.java:149`), whereas `neighboursToExploreAtInsert` is a cheap change. The design
in §7 covers this with the same mechanism as a change of analyser.

### 6.8 Scoring through a reference (§1.4, O10)

The research leaves open whether the composition will be index-time and the association query-time, and it
says explicitly that **the design seam has to be in P1 from the start**, even if the mechanics is built
later. In the schema that seam means a declaration on the reference, not on the attribute.

```java
session.defineEntitySchema("Page")
	.withReferenceToEntity(
		"blocks", "ContentBlock", Cardinality.ZERO_OR_MORE,
		whichIs -> whichIs
			.indexedForFilteringAndPartitioning()
			/* the block's text counts as the page's own text */
			.searchableThrough(ReferenceSearchMode.COMPOSITION)
	)
	.withReferenceToEntity(
		"related", "Product", Cardinality.ZERO_OR_MORE,
		whichIs -> whichIs
			.indexedForFilteringAndPartitioning()
			/* a match in a related product only boosts, it does not assert an own term */
			.searchableThrough(
				ReferenceSearchMode.ASSOCIATION,
				thatIs -> thatIs
					.withAggregation(ReferenceScoreAggregation.MAX)
					.withDecay(0.4f)
			)
	)
	.updateVia(session);
```

Two things about that shape are deliberate. **The aggregation function is a mandatory part of the
declaration**, because its absence is exactly the defect Solr has — `JoinUtil` propagates the score of the
*first* occurrence even for `score=max` (research §8 VK16). And **the mode is an enumeration, not a
boolean**, because composition and association have a different cost on the write path: `COMPOSITION` is an
index-time expansion with a fan-out when a block is edited, `ASSOCIATION` is a query-time leg costing the
write path nothing.

From the reindexing point of view the difference is essential: switching `COMPOSITION` on is a rebuild of
the postings of the referring entities, switching `ASSOCIATION` on is a free change. That is another
argument for the mode to be visible in the API, not hidden in a configuration object.

---

### 6.9 The source of the entity dictionary: what facets are and where the surface forms come from

The sponsor's question (2026-08-14) revealed a hole in this document: §8 declares the entity dictionary a
hot-swap artefact and that is where it ends. But the artefact is only half the truth — the easier half. The
annotation `recognizedFacets()` (query-design §8.3) needs a dictionary "surface form → (reference type,
PK)" and that has two sources of different natures:

1. **The derived layer** — surface forms pulled out of the attributes of the referenced entities:
   `Brand.name` = "Bosch" → (brand, PK 123). That layer is a function of the indexed data, it changes with
   every write into the target collection and the engine has to maintain it — otherwise the dictionary
   silently goes stale (a renamed brand, a new category). It is not an artefact, it is an **index over
   another collection**.
2. **The alias layer** — an enrichment from Sage ("škodovka" → Škoda), arising by inference offline and
   delivered as a hot-swap artefact (§8). This part fits §8.

Both layers are unified into a single structure at lookup time; the annotation's response carries the
provenance of an item (derived from data vs. an alias), because the client may want to present aliases
differently.

For the derived layer the schema has to answer two questions — and for both it mostly has the mechanisms
already:

**What facets are.** The answer exists in the schema: a reference declares `faceted()`
(`ReferenceSchemaContract.isFaceted()`, `:352`; the per-scope variant `:368`) and it is exactly this flag
that drives the facet summary today. The proposal: **the target collections of facetted references enter
recognition** — no new "recognizable" flag is introduced until the product asks for one. A second flag
beside `faceted()` would create a matrix of four combinations, of which "recognize but do not facet" makes
no sense: the annotation returns a ready `facetHaving`, so recognizable without facettable has nothing to
return.

**Where the surface forms come from.** Two variants of the declaration; the decision remains open (S13),
with the sponsor leaning towards the second:

- **The representative attributes of the target collection as the default.** `representative`
  (`AttributeSchemaContract.isRepresentative()`, `:82`) is defined by its JavaDoc as a small number of
  attributes by which an entity is recognised — which is the definition of a surface form — and a
  declaration on the target collection is not duplicated for every referring collection. The sponsor,
  however, does not lean towards overloading `representative`: the flag would gain a second meaning and
  with it a cost of change (a rebuild of the dictionary) that it does not have today.
- **A separate binding at the level of the searchable definition on the reference** (the sponsor's
  leaning). §6.8 already introduces `searchableThrough(...)` on a reference; a list of source attributes of
  the target collection is its natural extension and participation in recognition is declared where the
  rest of the reference's fulltext behaviour is declared. The cost: the declaration is repeated per
  referring collection — in practice a handful of cases.

Localization applies the same way in both variants: a localized attribute supplies surface forms per locale
and recognition runs in the query's locale (`entityLocaleEquals`); a non-localized attribute (typically a
code) applies to all locales.

**A hard requirement from the sponsor: renaming a brand must not mean a large recomputation of anything in
the products.** That decides the architecture of the derived layer: recognition is **a translation at query
time over a dictionary**, never an index-time denormalisation of the target entity's text into the postings
of the referring collection. The dictionary maps a surface form → (reference type, PK) and the products
refer to the PK — a rename therefore changes only the items of the target collection's dictionary, it does
not touch the products' postings. (A deliberate exception is the COMPOSITION mode from §6.8/O10 — there the
index-time fan-out is bought on purpose and for a different aim; the two mechanics must not be confused.)

From the same requirement came a direction that makes maintenance cheaper by one more step: **a dictionary
colocated with the target collection, evaluated dynamically within the query.** Every collection maintains
its own dictionary of the surface forms of its entities — maintenance is purely local (a write into Brand
changes only Brand's structures, no cross-collection trigger) — and a query over Product consults, during
recognition, the dictionaries of the target collections of its facetted references. Catalogue versions are
global, so a query sees dictionaries consistent with its snapshot. The sponsor called it not thought
through and possibly a blind alley; the first analysis does not suggest blindness — the cross-collection
dependency moves from the write path onto the read path, where it is cheap (K dictionaries × a few query
tokens) — but it carries open details: by which analysis chain the dictionary's items are normalised (it has
to meet the query branch of the referring collection), multi-word surface forms (span matching over the
query's tokens) and the cost of consulting K dictionaries within the latency budget. All in S13.

## 7. Schema evolution and reindexing

### 7.1 The problem

Three facts from §4 compose into a correctness trap:

1. The structures come into being **lazily, at the first write of a value** (`AttributeIndex.java:1637`).
2. Switching an indexing flag over existing data **passes validation silently**
   (`SetAttributeSchemaFilterableMutation` has no use in the engine, `EntityCollection.java:966` touches an
   index only for the hierarchy).
3. **A reindexing mechanism does not exist** and the documented solution is to fill a new catalogue and
   swap it in (`bulk-vs-incremental-indexing.md:48`).

The result is a query that **silently returns an incomplete result**. That is exactly the state the project
rule in `CLAUDE.md` forbids: "Never silently skip unexpected states." Fulltext did not cause the problem
and is not obliged to solve it in full — but it is the first feature for which the probability that a user
will hit it is high. Fulltext configuration has more moving parts than `filterable`, it changes over time
(tuning relevance) and analysers are upgraded together with the library.

### 7.2 The proposal: classification of changes and refusal instead of silent acceptance

I recommend adopting **Vespa's four-axis model** (§3.1), narrowed to what evitaDB needs. Every fulltext
schema change falls into exactly one category:

| Category | Meaning | Examples |
|---|---|---|
| **Free** | read only at ranking time | weight, the query branch of the profile, `ASSOCIATION` |
| **Recomputation from the index** | derivable from the already stored index | prefix bitmaps |
| **Rebuild from entities** | walk the entities and tokenize | `searchable`, analyser, pivot, composition |
| **Refused** | cannot be done without consent | the dimension or metric of a vector over data |

Key observations about that division:

**evitaDB has no Vespa "refeed" category, and that is an advantage over the market.** For a change of data
type Vespa has to ask the client to send the data again, because the stored document is not enough. evitaDB
holds the attribute values in the entity's body, so a rebuild of the fulltext index is in principle a
**local recomputation** — walk the collection's entities and tokenize them again. No external system gets
involved. It is a mechanism that does not exist today, but nothing stands in its way; the phrase "in
principle" is deliberate here and is not to be read as an existing capability.

**The middle category is empty for now apart from prefix bitmaps, but it is necessary to have it.** Vespa
gets a demonstrable benefit from it with `enable-bm25` and it is likely that other things will move into it
over time (for instance the transition to BM25F in phase F3, which per research §4.2 means only switching
the scoring function over the existing structures).

**The category is to be carried by the parameter, not by the documentation.** The classification above is
useless if every author of a new configuration option has to remember it themselves. Elasticsearch has a
mechanism for the same problem that is worth adopting, because it is cheap and impossible to forget
(verified against checkout `main`, commit `9a100e2d0e41`, 2026-08-13). Every field parameter there is a
`FieldMapper.Parameter` object and its construction **mandatorily** states the flag `updateable`, i.e. "may
this parameter be changed on an existing field?"; the documentation right in the code reads "whether the
parameter can be updated with a new value during a mapping update"
(`server/src/main/java/org/elasticsearch/index/mapper/FieldMapper.java:1006`). The enforcement is one line
and elegant: if the parameter is `updateable`, the merge validator always returns `true`; if it is not, the
validator requires equality of the old and the new value (`FieldMapper.java:1030`). Whoever adds a new
option has to decide the cost of changing it — otherwise they have no way to construct it.

The dividing line for a text field moreover comes out entirely consistently and can be stated in a single
sentence: **changeable is exactly what does not affect the bytes written into the index.** The indexing
`analyzer` cannot be changed (`TextParams.java:47`), whereas `search_analyzer` can (`:61`);
`index_phrases` cannot, because it creates an auxiliary index (`TextFieldMapper.java:298`), whereas
`fielddata` can, because it is only a runtime structure (`:281`). It is the same boundary that §2 of this
document draws, only written down in a place where it cannot be overlooked.

The second half of that mechanism is a **conflict accumulator**. A mismatch is neither discarded nor logged
— it is written into `FieldMapper.Conflicts` (`FieldMapper.java:1874`) and the method `check()` (`:1891`)
throws them all at once as a single exception. The user therefore learns about all the problems from a
single attempt, not only about the first one. It is a few dozen lines and it fits evitaDB's existing style
of schema validation without friction.

**How to write it down for us.** The category from the table above is to become a mandatory part of the
declaration of every fulltext option, not a comment beside it. The classification of the concrete items is
already in this document in two places (§2.1 and §8) and there is no point in copying it a third time; what
needs adding is only what neither of those tables covers, because it came into being only with the rank
profiles. **Locked** is, alongside the indexing analyser, the set of searchable fields and the pivot, also
the **`tf` saturation function**, because that too is baked into the impact byte (research §4.2). **Free**
are, alongside the field weights and the synonyms, also the **choice of rank profile**, the **typo tolerance
thresholds** and the **boost map**, because all three are evaluated only over the query and do not touch
the index.

### 7.3 What to do in the first round

Building a full reindexing mechanism is not within the scope of the fulltext prototype and should not be.
The minimum that nevertheless **has** to be done before `searchable()` reaches users:

1. **Refuse a change the engine cannot perform, instead of silently accepting it.** A mutation changing
   `searchable`, the analyser or the pivot over a **non-empty** collection ends with an exception, not with
   a silent success. Over an empty collection it passes without restriction, which covers the overwhelming
   majority of real usage (the schema is defined before the data is poured in).

   This point has two independent supports and both are worth stating, because they move its status from
   "recommended" to "otherwise we make things worse". The first is from the field: OpenSearch refuses to add
   a composite field to an existing index with an **explicit exception** carrying the message "Composite
   fields must be specified during index creation, addition of new composite fields during update is not
   supported"
   (`server/src/main/java/org/opensearch/index/compositeindex/CompositeIndexValidator.java:37`; verified
   against checkout `main`, commit `36edc05ac84`, 2026-08-12) — and that is a rule stricter for them than
   for ordinary fields, where adding is possible. They would rather refuse a change than produce an index
   inconsistent with their own schema.

   The second support is closer and less pleasant: **the solution we are replacing can do it too.** The
   existing Lucene client catches an incompatible change of a field type and translates it into its own
   exception `IndexSchemaChangedException`, whose text ends with the instruction "Run full reindex to
   rebuild the index with the new schema" (internal analysis of the Edee CMS client, §2.7, anchor
   `IndexCreatorImpl.java:165–172`). It cannot recover from it by itself, but **it reports the disagreement
   loudly and with instructions on what to do.** If the new solution admitted a silent change, that would be
   a regression against a fifteen-year-old client — and that is an argument not to be brushed aside in a
   discussion about the scope of the first round.

   And the order matters: **first the loud prohibition, only then the reindexing route** that softens it.
   The opposite order means that until reindexing comes into being, indexes inconsistent with the schema
   silently accumulate — and they are recognised only by the missing results.
2. **Give that refusal an escape route with an expiry**, after the model of Vespa's
   `validation-overrides.xml` (`ValidationOverrides.java:90`, at most 30 days). An operator who knows that
   they will load the data again right afterwards has to have a way of pushing the change through — but that
   exception must not remain permanently. The concrete shape in evitaDB is an open question (§9); a simple
   variant is a parameter of the mutation, not a global configuration file.
3. **Store the analyser's fingerprint with the index** (§6.5, point 5) and compare it when the catalogue is
   opened. A mismatch means that the contents changed under the same name — the engine has to report it,
   because otherwise a silent inconsistency after a library upgrade is inevitable.
4. **Document the way out** — today it is `replaceCatalog` per `bulk-vs-incremental-indexing.md:48`. A
   refusal without an alternative is worse than nothing.

Point 1 is the only genuinely blocking one. Points 2 to 4 can be delivered in F1 and must not block the gate
P5 → P1 → P2.

### 7.4 Where this is heading

The target state is a **local rebuild of a single structure** — walk the collection's entities, re-tokenize
the affected attribute, build the dictionary and the postings, and swap atomically. The model in the
repository is `SetEntityScopeMutationHandler` (§4.3) with its load-bearing order remove, switch, insert; and
the catalogue's `WARMING_UP` mode shows that a fast bulk route exists in the engine.

Replaying the WAL is **unusable** for this purpose and it is good to say so out loud, so that nobody tries
it: the WAL is trimmed and compacted, so it need not reach all the way back to the first write. The source
of truth for a rebuild is the stored entities, not the history of mutations.

It is likewise good to say out loud that **computing a missing structure at query time is not a
substitute**. The temptation exists and in the field it has a name: OpenSearch has the field type `derived`,
whose value is not stored but computed at query time by a script over the source document, with an optional
cheap indexed prefilter that is joined with the expensive script evaluation into a conjunction
(`server/src/main/java/org/opensearch/index/mapper/DerivedFieldType.java:171`, the prefilter validation at
`:104`; verified against checkout `main`, commit `36edc05ac84`, 2026-08-12). The motivation is exactly ours
— querying something that was not indexed in advance, without a rebuild. **For us, however, it solves
nothing**, and the difference is essential: their problem is a *missing value* which can be computed from
the source document, whereas our problem is a *missing index structure*. Fulltext without postings means
walking all the collection's entities and tokenizing them, which is work of the order of indexing performed
at query time. Written down so that nobody proposes it a second time as a cheap substitute for point 1 in
§7.3.

**How expensive the reindexing route is elsewhere, and why it need not be for us.** Elasticsearch paid for
its own with two modules: beside `modules/reindex/` itself (reading page by page from the source index
through a point-in-time snapshot or scroll and writing in bulk into the target) stands
`modules/reindex-management/` with actions for listing, querying and cancelling a running operation,
changing the throttling at runtime, metrics and resumption after an interruption. A reindex there **is not
an API call but a long-running managed task** — and that is the realistic cost of reindexing in a system
where the index cannot be derived from anywhere other than another index.

Our position is different and it is an advantage which this document should name as the solution to its own
hole from §4.3: **the fulltext structures are a deterministic function of the catalogue's data, all of which
is already in evitaDB.** A rebuild therefore does not copy documents between two indexes and needs no
cursor, no resumption and no alias switching — it is a **local recomputation from our own storage**,
described above as walk the entities, re-tokenize, swap atomically. What remains to be solved is the
progress and the visibility (does it run in the background? is the collection queryable through the old
structure meanwhile?), not the transport of data. That is a substantially smaller task than the one
Elasticsearch had to solve.

---

## 8. The boundary: schema versus hot-swap artefact

The research introduces the notion of a hot-swappable artefact (a synonym dictionary, an entity dictionary,
a boost table, a model) as a data bundle exchangeable at runtime without reindexing and without a restart.
The boundary between it and the schema is simple and follows from §2:

**What belongs in the schema is what affects the tokens stored in the index. An artefact is what is
evaluated only over the query.**

| Thing | Home | Why |
|---|---|---|
| `searchable`, analyser (index branch), pivot | schema | changes the stored tokens and impact bytes |
| default field weight | schema | it is part of the definition of the data, but is read only at ranking time |
| synonyms | artefact | query branch only; Meilisearch does not even have them in the settings |
| entity dictionary — alias layer | artefact | Sage inference offline; span matching like synonyms |
| entity dictionary — derived layer | engine-maintained index | a function of the target collections' data, declaration §6.9 |
| stop words | **schema or artefact — beware** | see below |
| boost table (query, PK) | artefact | Sage generates it continuously, research §4.3 |
| rank profile, order of lanes | configuration / query | Vespa guards it with no validator |
| curation rules (pin, hide) | artefact | merchandising, not relevance |

**Stop words are a trap and deserve a separate mention.** In Meilisearch changing them is a reindex
(`settings.rs:1710`), because they are applied in the index chain. If they were applied in evitaDB only in
the query branch, they would be free — at the price of larger postings. **I recommend not applying them at
indexing time at all** and leaving them purely a query-time matter: e-commerce fields are short, the saving
in space is small, and we gain that the list can be changed without consequences. For the CMS profile with
long texts this is to be re-measured in P1 — there the saving may be substantial and then the stop words
have to be put into the category "rebuild from entities" (§7.2).

A lesson worth adopting from Meilisearch (§3.4): **an artefact that is derived has to be recomputed when
what it is derived from changes.** Meilisearch stores synonyms pre-tokenized and on every change of stop
words, separators or the dictionary forces their re-tokenization itself (`settings.rs:642`, `:667`, `:692`),
with a comment about the binding order of the operations (`:1596`). If synonyms or the entity dictionary are
stored pre-tokenized in evitaDB, the same applies — and it is better to have it in mind from the start than
to discover it as a bug.

Finally, artefacts need their own story about replication. Lucene's `CustomAnalyzer` ran into the same
thing: as soon as a parameter refers to a file (`words="stopwords.txt"`), the specification stops being
purely data and requires a `ResourceLoader` (`CustomAnalyzer.java:105-122`). In evitaDB the dictionaries have
to be stored so as to survive replication of the catalogue onto another node — i.e. as data of the
catalogue, not as files beside it.

---

## 9. Open questions

**S1 — The API variant.** Variant C (named profiles) in two steps is recommended (§5.4). The decision is the
sponsor's and is **irreversible in the sense** that the boundary of the cost of change cannot be added to a
released API later. The second choice is variant A, not B.

**S2 — The shape of the escape route from a refused change** (§7.3, point 2). Vespa has
`validation-overrides.xml` with a mandatory expiry within 30 days. evitaDB has nowhere to put such a file
nor a mechanism of expiry. What suggests itself is a parameter of the mutation (`allowingRebuild(...)`), a
flag on the session, or an item of the catalogue's configuration. To be decided before `searchable()`
reaches users.

**S3 — Owner versus view for the fulltext structure** (§4.2). `FilterIndex` is a view over a shared
`InvertedIndex`, `SortIndex` is an owner. The choice determines the commit walk, the serialization and the
behaviour on emptying, and it has to be settled in P1, not later.

**S4 — The vector's data type** (§6.7). `float[]` is not a valid attribute type today and extending
`EvitaDataTypes` breaches the principle because of which it is not there. A separate type `VectorEmbedding`
without `Comparable` is recommended. It blocks P6, it blocks neither P1 nor the gate. The decision has a
budgetary side alongside the principled one: a dedicated type need be neither filterable nor sortable, and
thereby avoids the eight-layer operation from the skill `evita-schema-change` — which changes the effort
estimate for P6 (§6.7).

**S5 — Weight: an enumeration or a number** (§6.3). An enumeration of four levels is recommended, following
the ordinality of both reference engines. Should it turn out that rank profiles need continuous weights, the
transition from an enumeration to a number is additive, the opposite direction is not — hence start with an
enumeration.

**S6 — The pivot: in the profile or on the attribute, and where to get the value from** (§6.4). Two questions
in one, both open.

The first is placement, and there is a tension in the design worth naming: the pivot is admittedly in the
same cost category as the analyser (both are a rebuild from entities), so placing it on the attribute would
not violate the rule from §2 — it would only violate the softer principle "what is on the attribute is
cheap". If the pivot lived on the attribute, profiles would multiply only because of analysis, which is
their real purpose. The risk is the opposite: with two methods side by side (`searchWeight` free,
`searchLengthPivot` expensive) the user would have no way of telling the difference — exactly the mistake of
variant A. The design therefore leaves the pivot in the profile, but it is the weakest link of
recommendation C and it is worth revisiting once it is clear how many profiles a real schema needs.

The second is the origin of the value. Deriving it from the observed distribution of lengths is the most
pleasant for the user, but it introduces into the index a data-dependent state whose recomputation is as
expensive as a change of analyser. A fixed default per profile is recommended; P1 will decide, according to
how sensitive the score is to the choice of pivot.

**S7 — Stop words: index or query branch** (§8). The query branch is recommended (the change is free), but
for the CMS profile with long texts this is to be re-measured by P1 — it may turn out that the saving in
space outweighs it.

**S8 — The annotation route.** `ClassSchemaAnalyzer` derives a schema from an annotated class. A new flag
will demand an intervention into **two annotations at once** — `@Attribute` (15 elements today,
`Attribute.java:53-157`) and `@ScopeAttributeSettings` (6 elements). The question is whether the annotation
route should support fulltext from the start or only in F1; if only later, it has to be clear from the start
that a missing annotation means "not searchable", not "the default".

**S9 — Associated data and lazy loading** (§6.2). Indexing requires the value on the write path, but
associated data is today loaded lazily and separately. Either it is loaded always (a toll even for those who
do not use fulltext), or the indexing is deferred to trunk incorporation. P2 will decide by measurement.

**S10 — The analyser's fingerprint and a library upgrade** (§6.5, point 5; §7.3, point 3). The registry has
to return a version alongside the name, otherwise an upgrade of `lucene-analysis-common` will silently
change the produced tokens under the same profile name. What is open is where to store the fingerprint (the
schema, or the index header) and what exactly to do on a mismatch — refusing to open the catalogue is
probably too harsh, a warning in the log too soft.

**S11 — The phrase analyser slot: introduce it now, or merely leave room** (§6.5, point 4). Three slots
(index, query, phrase) are forced in Elasticsearch by the practice with stop words in phrases, and for us
that practice meets the recommendation from §8 not to apply stop words at indexing time at all. The question
is whether the first round carries all three slots (even if pointing at the same object) or only two with an
explicit place for the third. It is directly related to **S7**: as soon as stop words are decided to be
applied at index time, the reason for the third slot disappears too. To be decided together, not separately.

**S12 — The mode of an analyser component and its enforcement** (§6.5, point 6). It is recommended that every
component of the registry carry a mode `INDEX_TIME` / `SEARCH_TIME` / `ALL` and that a mixed chain refuse to
be assembled. What remains open is who declares the mode (the author of the component, hardcoded, or it is
derived from the configuration as with synonyms and the `updateable` flag) and when the check is performed —
at schema write time, or at analyser assembly time. The answer belongs to prototype P5, but the schema
depends on it: if the check does not pass already at the schema mutation, a configuration will be stored that
cannot be used.

**S13 — Declaration and storage of the source of the entity dictionary** (§6.9). Open, with the sponsor's
leanings recorded (2026-08-14): participation in recognition = facetted references (`faceted()` unchanged);
the declaration of the source attributes rather by **a separate binding on the reference's searchable
definition** (an extension of `searchableThrough` from §6.8) than by overloading `representative` (which
would gain a second meaning and a cost of change it does not have today,
`AttributeSchemaContract.java:77-82`); and the hard requirement — **renaming a target entity must not trigger
a large recomputation in the referring collections**, from which it follows that recognition is a translation
at query time over a dictionary, not an index-time denormalisation. Examine the model of **colocated
dictionaries** (the dictionary at the target collection, local maintenance, consultation at query time,
§6.9): the analysis chain of the dictionary's items vs. the query branch of the referring collection,
multi-word surface forms, the cost of consulting K dictionaries within the latency budget. The fallback,
should colocation turn out to be blind: a central derived dictionary with cross-collection maintenance after
the model of reflected references (`ReflectedReferenceSchemaContract`). Related to Q18 in query-design (the
boundary between the engine and the client layer).

---

## Sources

**evitaDB** (branch `dev`, 2026-08-12) — schema:
`evita_api/.../schema/AttributeSchemaContract.java`, `AttributeSchemaEditor.java`,
`AssociatedDataSchemaEditor.java`, `EntitySchemaEditor.java`, `ReferenceSchemaEditor.java`,
`dto/AttributeSchema.java`, `dto/AssociatedDataSchema.java`,
`mutation/attribute/SetAttributeSchemaFilterableMutation.java`,
`builder/AbstractAttributeSchemaBuilder.java`, `EvolutionMode.java`,
`data/annotation/Attribute.java`, `data/annotation/ScopeAttributeSettings.java`;
the index path: `evita_engine/.../index/EntityIndex.java`,
`index/attribute/AttributeIndex.java`, `index/mutation/local/AttributeIndexMutator.java`,
`index/mutation/local/EntityIndexLocalMutationExecutor.java`,
`index/mutation/local/handler/SetEntityScopeMutationHandler.java`,
`core/collection/EntityCollection.java`; types:
`evita_common/.../dataType/EvitaDataTypes.java`; the recipe:
`.claude/skills/evita-schema-change/SKILL.md`; documentation:
`documentation/user/en/deep-dive/bulk-vs-incremental-indexing.md`,
`documentation/user/en/get-started/example/define-catalog-with-schema.java`,
`documentation/developer/indexes/schema-settings.md`.

**Vespa** (`/www/oss/vespa`, master) — `config-model/src/main/javacc/SchemaParser.jj`,
`config-model-api/.../ConfigChangeAction.java`, `.../ValidationId.java`,
`.../ValidationOverrides.java`,
`config-model/.../validation/change/search/IndexingScriptChangeValidator.java`,
`.../change/search/AttributeChangeValidator.java`,
`.../change/search/DocumentTypeChangeValidator.java`,
`config-model/.../schema/parser/ConvertParsedFields.java`,
`searchcore/.../searchcorespi/index/indexmaintainer.cpp`, the fixtures
`config-model/src/test/derived/{hnsw_index,advanced,music}/`.

**Lucene and Solr** (`/www/oss/lucene`, `/www/oss/solr`) —
`lucene/core/.../index/IndexableFieldType.java`, `.../index/IndexOptions.java`,
`lucene/core/.../document/FieldType.java`,
`lucene/analysis/common/.../analysis/custom/CustomAnalyzer.java`,
`lucene/core/.../analysis/TokenizerFactory.java`,
`lucene/analysis/common/.../miscellaneous/PerFieldAnalyzerWrapper.java`;
`solr/server/solr/configsets/_default/conf/managed-schema.xml`,
`solr/core/.../schema/FieldTypePluginLoader.java`, `.../schema/FieldProperties.java`,
`solr/core/.../util/SolrPluginUtils.java`,
`solr/solr-ref-guide/modules/indexing-guide/pages/{reindexing,schema-api}.adoc`.

**Meilisearch** (`/www/oss/meilisearch`, 1.53) —
`crates/milli/src/update/settings.rs`, `crates/milli/src/index.rs`,
`crates/milli/src/fields_ids_map/metadata.rs`,
`crates/milli/src/update/new/indexer/write.rs`,
`crates/milli/src/search/new/ranking_rule_graph/fid/mod.rs`.

**Typesense** (`/www/oss/typesense`, `v30.1-118-gee7784f3` — not v31, verify before citing) —
`include/field.h`, `include/index.h`, `include/collection.h`, `src/field.cpp`,
`src/collection.cpp`.

**Elasticsearch** (`/www/oss/elasticsearch`, branch `main`, commit `9a100e2d0e41`, 2026-08-13) —
`server/.../index/mapper/FieldMapper.java`, `.../index/mapper/TextParams.java`,
`.../index/mapper/TextFieldMapper.java`, `.../index/analysis/AnalysisMode.java`,
`modules/analysis-common/.../analysis/common/SynonymTokenFilterFactory.java`,
`modules/reindex/`, `modules/reindex-management/`. Section §3.3 is older and rests on the web only;
the anchors in §6.5, §6.7 and §7.2 are verified against this checkout.

**OpenSearch** (`/www/oss/OpenSearch`, branch `main`, commit `36edc05ac84`, 2026-08-12) —
`server/.../index/compositeindex/CompositeIndexValidator.java`,
`server/.../index/mapper/DerivedFieldType.java`,
`server/.../index/engine/dataformat/FieldTypeCapabilities.java`.

**Algolia** — from the web only, without reading the source code; versions and formulations with a
reservation, in line with §8 of the research (VK13, VK14, VK20).
