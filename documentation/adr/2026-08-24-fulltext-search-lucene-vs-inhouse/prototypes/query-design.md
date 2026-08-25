# The query side of fulltext: constraints, rank profiles and the ranking function

> **Status: a proposal for discussion, not a decision.** The document follows on from the
> research [`../research.md`](../research.md) (version 2) and elaborates its §4.4 "Query API
> (sketch)" to a depth sufficient for starting implementation. The research remains
> authoritative for the *architecture* (what gets computed), this document for the *shape of
> the query language* (how it is requested) and for the *planning seam* (where state flows
> between query phases).
>
> Date: 2026-08-12. Every claim about today's state of the code is verified against a working
> copy on branch `dev` and carries a `file:line` anchor. Where verification is missing, it is
> said explicitly.
>
> **Revision 2026-08-14:** during the review the sponsor formulated five binding principles
> (§2.6). They change the recommendation on where the field-weight override lives (§4.2 — now
> in `relevance()`), the standing of inline configuration (§6.3), and they add the relationship
> to the planned query profiles from issue #12 (§6.5).
>
> It was translated from Czech and moved out of `specifications/` into this record on
> 2026-08-24; see [`../README.md`](../README.md) for the decision it supports.

---

## Contents

1. [Purpose and intended audience](#1-purpose-and-intended-audience)
2. [What evitaQL implies for the design](#2-what-evitaql-implies-for-the-design)
3. [State of the expression language (verified)](#3-state-of-the-expression-language-verified)
4. [The filter constraint: variants and recommendation](#4-the-filter-constraint-variants-and-recommendation)
5. [`relevance()`: the ordering constraint](#5-relevance-the-ordering-constraint)
6. [Defining a rank profile](#6-defining-a-rank-profile)
7. [The boost channel](#7-the-boost-channel)
8. [Require: feature export and annotations](#8-require-feature-export-and-annotations)
9. [Sharing state between planner phases](#9-sharing-state-between-planner-phases)
10. [Suggest](#10-suggest)
11. [The vector leg and RRF (sketch)](#11-the-vector-leg-and-rrf-sketch)
12. [Impact on the external APIs](#12-impact-on-the-external-apis)
13. [Scope of work](#13-scope-of-work)
14. [Open questions](#14-open-questions)

---

## 1. Purpose and intended audience

The research decided *what* will be computed: a candidate bitmap from postings, a feature
vector per candidate, a rank profile folding the features into an order, two ranking phases.
It did not say how that is requested from a query — and that is something which has to be
decided before any code is written, because the shape of the constraint determines the shape
of the formula, that determines where the score lives, and that in turn determines how the
sorter gets to it. Changing any link in that chain after delivery means a breaking change
across four APIs.

The document is written for the person who will implement fulltext in evitaDB, and for the
person who has to approve the design. It assumes knowledge of the research, not knowledge of
the internals of the query engine — those are explained as they come up.

Three findings from verifying the code change the design enough that they belong up front:

**First: `orderBy` in evitaDB is not a lexicographic sort by a tuple, it is a chain of
substitutes.** The documentation says so directly: "If two entities have the same value of the
first attribute, they are not sorted by the second attribute, but by the primary key (in
ascending order)" (`documentation/user/en/query/ordering/comparable.md:108-111`). The second
order constraint applies only to entities that the *first* constraint could not sort at all.
That turns "let us pack the criteria into a single 64-bit number à la Typesense" into the only
way multi-criteria relevance can be expressed at all — not a stylistic borrowing, but something
the language forces. Detail in §2.3.

**Second: the expression language exists and is richer than the research suspected, but as a
per-candidate interpreter it is unusable — and the codebase says so itself, twice.** In detail
in §3. Conclusion: EvitaEL is a good *authoring format* for a rank profile, provided the profile
is compiled into a primitive scorer at registration time. Interpreting it in a hot loop over a
million candidates would go against two existing refactorings that came about precisely so that
it would not be done.

**Third: sharing state between filtering and ordering has exactly one established shape in
evitaDB, and its failure is silent.** The `FilteredPricesSorter` precedent is good and usable,
but it carries a trap (SHALLOW lookup in the formula tree does not descend into a node that has
already matched) which can be fallen for in such a way that `relevance()` stops working exactly
and only when prefetch is switched on. In detail in §9.2.

---

## 2. What evitaQL implies for the design

This section summarises the language's conventions as the user documentation describes them and
translates them into commitments the new fulltext constraint has to honour. It is not a copy of
the documentation — it picks out only what binds the design.

### 2.1 Naming constraints

The documentation formulates three rules (`documentation/user/en/query/basics.md:311-314`):

1. The name must read as an English sentence in the context of the query — "query collection …,
   and filter entities by …, and order result by …". The query should be comprehensible even to
   someone who does not know evitaQL.
2. The name starts with the **part of the entity the constraint targets** (`entity`,
   `attribute`, `reference`, `price`, `hierarchy`, `facet`) and continues with a word capturing
   the essence of the operation.
3. A constraint that only makes sense inside a particular parent may relax rule 2 — the context
   is obvious from the parent. Hence bare names like `entityHaving`, `having`, `excluding`,
   `entityProperty`, `segment`.

The suffixes are not arbitrary, they form a settled vocabulary: `Equals`, `InSet`, `Between`,
`Contains`, `StartsWith`, `EndsWith`, `Natural`, `Exact`, `Having` (a container narrowing a
sub-range), `Content` (shaping the returned entity), `Of` / `OfSelf` / `OfReference` (an extra
result for self vs. a named reference), `Property` (switching context inside an order container).

For the generated APIs there is in addition a mechanical rule which is essential for naming.
The name in GraphQL and REST is composed like this
(`evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/catalog/`
`dataApi/builder/constraint/ConstraintSchemaBuilder.java:97-99`):

- `{fullName}` — a generic constraint without a classifier,
- `{propertyType}{fullName}` — a non-generic constraint without a classifier,
- `{propertyType}{classifier}{fullName}` — a non-generic constraint with a classifier.

The classifier is therefore injected into the name **only when the constraint declares one** via
the `@Classifier` annotation. `AttributeContains` declares one, and therefore appears in GraphQL
as `attributeCodeContains`. `AttributeHistogram` does not declare one (it takes attribute names
as an ordinary list of values,
`evita_query/src/main/java/io/evitadb/api/query/require/AttributeHistogram.java:90-97`), and so
it stays `attributeHistogram`. For a fulltext constraint that searches **several fields at
once** this means: if we pass the list of fields as a value argument and not as a classifier,
the generated name will not shatter. The worry that `attributeMatches` might turn into
`attributeNameMatches` is therefore unfounded — but only on the assumption that `@Classifier`
is not used.

### 2.2 Containers and `userFilter`

Inside every filter container an implicit conjunction applies — the logical connective is not
written and "and at the same time" is assumed
(`documentation/user/en/query/filtering/logical.md:19-21`). `and`, `or` take 1..N children,
`not` exactly one (`logical.md:36,98,159-168`).

`userFilter` is the most important container for this design and its semantics is stricter than
it looks at first sight. It behaves like `and`, but it separates the **filter the user controls
through the UI** from the mandatory part of the query; only the user-controlled part may be
temporarily peeled off by computations (reference summary, histograms)
(`documentation/user/en/query/filtering/behavioral.md:119-123`). The documentation defines three
carrier families and a peeling matrix for it (`behavioral.md:147-178`): facet carriers
(`facetHaving`), value ranges (`attributeBetween`, `histogramHaving`) and price ranges
(`priceBetween`). Each family is peeled in a different situation, so that "the slider does not
narrow itself".

A crucial precedent: **a constraint that has no defined relaxation semantics is not admitted
into `userFilter` at all.** A plain `referenceHaving` is rejected inside `userFilter` precisely
because it would not take part in baseline relaxation
(`documentation/user/en/query/requirements/histogram.md:529-531`). The fulltext filter must
therefore take a position — either it is added as a fourth family with its own peeling rule, or
it is explicitly forbidden inside `userFilter`. Silence is not an option here, because the
language has already shown once that it resolves silence with an exception. The recommendation
is in §4.5.

The boundary is otherwise hard: "Constraints outside `userFilter` … are never peeled. They
define the universe; the relaxation surface is `userFilter` and nothing else"
(`behavioral.md:177-178`).

### 2.3 `orderBy` is a chain of substitutes, not a lexicographic sort

This is the most important finding of the whole section and it changes the interpretation of
§4.3 of the research.

The documentation: "If you sort entities by two attributes in an `orderBy` clause of the query,
evitaDB sorts them first by the first attribute (if present) and then by the second (but only
those where the first attribute is missing). If two entities have the same value of the first
attribute, they are not sorted by the second attribute, but by the primary key (in ascending
order)" (`documentation/user/en/query/ordering/comparable.md:108-111`).

The explanation is in the engine: `Sorter.sortAndSlice` returns a `SortingContext` whose
`nonSortedKeys` are **the records this sorter was unable to sort**, and those alone are handed
to the next sorter (`evita_engine/src/main/java/io/evitadb/core/query/sort/Sorter.java:52-57`,
the partitioning contract asserted at `:106-128`). The chain always ends with `NoSorter`, which
emits the remainder in natural PK order
(`evita_engine/src/main/java/io/evitadb/core/query/sort/NoSorter.java:57-66`; appending to the
end of the chain at `.../sort/OrderByVisitor.java:276-279`).

Three consequences for fulltext:

1. **Multi-criteria relevance has to be a single number.** `orderBy(relevance(),
   attributeNatural("popularity", DESC))` does not mean "relevance, popularity on a tie" — it
   means "relevance, and popularity for whatever relevance could not score at all". The cascade
   of criteria (matched words → typos → impact → exactness) therefore has to be packed into a
   single `long`, because the language has no other way of expressing "on a tie, continue with
   the next criterion". The research proposed it as a borrowing from Typesense; in fact it is
   the only possibility.
2. **Chaining `relevance()` with anything else is a trap for the user.** It will be
   syntactically legal and it will do something other than expected. The documentation of
   `relevance()` has to warn about it explicitly; a similar trap already exists in the language
   (the existing escape hatch is sortable attribute compounds,
   `ordering/comparable.md:145-150`, but those are defined up front in the schema and are of no
   help for a dynamically computed score).
3. **A missing score must never be an error at sorting time.** The house convention is uniform:
   an entity without a sort key "is considered not sortable by this constraint and will be
   sorted by the next sort constraint in the query (or by its primary key in ascending order at
   the end of the list)" (`documentation/user/en/query/ordering/price.md:111-112`, likewise
   `ordering/constant.md:86-89`). `relevance()` returns non-candidate entities as
   `nonSortedKeys`.

### 2.4 `require` never changes the count or the order

The documentation states it as an invariant: requirements "define sideway calculations, paging,
the amount of data fetched for each returned entity, and so on, but never affect the number or
order of returned entities" (`documentation/user/en/query/basics.md:495-496`).

That is a clean argument for why the score cannot be a require: anything that changes the order
is an order constraint. It also delimits what does belong in require — feature export, explain
and the annotation of recognized facets are sideways-computed structures, i.e. exactly extra
results.

### 2.5 Dependencies between constraints and how they are documented

The language knows five shapes of validation and has a settled wording for each:

- **A constraint only for a given parent** — "can only be used within the `X` constraint"
  (`filtering/references.md:180`).
- **A required sibling** — "requires the presence of exactly one `X` constraint in the filter
  part of the query" (`ordering/constant.md:23-25`).
- **A dependency between query parts** — "you need to specify the `entityLocaleEquals`
  constraint in the `filterBy` part" (`ordering/comparable.md:70-72`).
- **A limit on the number of occurrences** — "Only a single occurrence of any of these three
  constraints is allowed in the filter part" (`filtering/price.md:38-40`).
- **A prerequisite in the schema** — "rejected at construction time"
  (`requirements/reference.md:136-137`).

And one cautionary experience worth not repeating: `priceNatural` **declares** its dependency on
price filters (`ordering/price.md:30-31`), but nowhere says what happens when they are missing.
In that case the engine silently degrades to `NoSorter`
(`evita_engine/src/main/java/io/evitadb/core/query/sort/price/translator/`
`PriceNaturalTranslator.java:86-96`), while for a schema with no prices at all it throws an
exception (`:69-75`). Neither is in the documentation. For `relevance()` this has to be decided
deliberately and the decision written down (§5.2).

### 2.6 Binding principles from the sponsor (2026-08-14)

During the review of the design the sponsor formulated five principles. They are not topics for
discussion but the guard rails within which the design is to move; they are recorded here once
and only referred to further on.

1. **What is mandatory and has no sensible default must be a mandatory argument of the
   constraint.** No "optional, but without it it blows up at runtime". The design reflects this
   as follows: the text in `textMatches` is a mandatory positional argument; `profile(...)`,
   `boostTable(...)`, `rerankCount(...)` and `inField(...)` have one mandatory argument each;
   `fieldWeight(...)` has both mandatory (field and weight), because a weight without a value
   has no sensible default — the default is expressed by not stating the constraint at all.
2. **Bindings between `filterBy`, `orderBy` and `require` are permitted.** The mechanism "the
   filter planner reads another part of the query" (§9.7) is therefore not a trick on the edge
   of the rules but an approved tool; the same legitimises the dependency of `relevance()` on
   the fulltext filter (§5.2). The essential consequence for §4.2: the computational argument
   "the filter formula has to know the weights" stops dictating where the weights live
   *syntactically*.
3. **The field-weight override belongs in `orderBy`, because weights affect relevance.** The
   only situation that would reverse this is a minimum relevance threshold defined directly in
   `textMatches` — the weights would then affect the match set as well and relevance would have
   to be normalised onto a fixed scale (e.g. 0–100). The threshold is therefore not proposed for
   F1; elaborated in §4.2 and Q14.
4. **Rank profiles have to be designed in alignment with the planned query profiles (issue
   #12).** Query profiles are meant to remove the "repeated blocks" of queries that the client
   has to send and that developers forget about; they are related but different things.
   Elaborated in §6.5.
5. **Both forms of the query must remain: with a profile and with hand-written parameters.** A
   developer tunes the query in the query console and only then extracts a part of it into a
   profile. Inline configuration is therefore not just a debugging back door — it is a fully
   fledged form of expression (§6.3).

---

## 3. State of the expression language (verified)

The sponsor phrased the input like this: "we already do expressions to some extent (probably not
to the required depth, but it can be developed)". The answer is: we do them more than the
research assumed, but they can only be developed in one direction — and that direction is not
the one that first suggests itself.

### 3.1 What exists

EvitaEL is a fully fledged expression language with its own ANTLR grammar. The contracts live in
`evita_common` (`evita_common/src/main/java/io/evitadb/dataType/expression/`), the grammar and
operator implementations in `evita_query`
(`evita_query/src/main/resources/META-INF/io/evitadb/api/query/parser/evitaEL/EvitaEL.g4`; the
generated parser is committed in `.../expression/parser/grammar/`).

It can do arithmetic, comparison, logic, ten mathematical functions (`abs`, `ceil`, `floor`,
`log`, `max`, `min`, `pow`, `random`, `round`, `sqrt`), navigation over objects by dot and by
bracket, the collection methods `size`/`any`/`all`/`none`, the spread operator and an extensive
null-safe set (`?.`, `?[`, `??`, `*?`). The complete description is in
`documentation/user/en/query/expression-language.md`. The entry point is
`ExpressionFactory.parse(String)`
(`evita_query/src/main/java/io/evitadb/api/query/expression/ExpressionFactory.java:62`).

Adding a function is cheap and well documented: the grammar takes any identifier as a function
name (`EvitaEL.g4:48`), so it is enough to implement a `FunctionProcessor` and register it
through the `ServiceLoader`
(`evita_query/src/main/java/io/evitadb/api/query/expression/function/processor/`
`FunctionProcessorRegistry.java:59-65`, the procedure in
`documentation/developer/query/expression_language_extension.md`).

### 3.2 Where it is used today

Considerably less than one would expect:

- **`gap()` inside `spacing()`** — conditional gaps in paging; the result is boolean
  (`evita_query/src/main/java/io/evitadb/api/query/require/SpacingGap.java:98,116`).
- **`facetedPartially` on a reference** — boolean
  (`evita_api/.../schema/ReferenceSchemaContract.java:386,398`).
- **`bucketedPartially` on a reference** — boolean
  (`ReferenceSchemaContract.java:507,519`).
- **`HistogramIndexDefinition.assignedWhen`** — boolean
  (`evita_api/.../schema/dto/HistogramIndexDefinition.java:67`).
- **`HistogramIndexDefinition.valueExpression`** — a number, **but it is never evaluated**
  (`HistogramIndexDefinition.java:66`).

**No filter or ordering constraint accepts an expression today.** Verified by searching the whole
packages `evita_query/src/main/java/io/evitadb/api/query/filter/` and `.../order/` — zero hits.
The expression is therefore today exclusively a schema-configuration tool plus one marginal
require.

The variable dictionary has **three names**: `$pageNumber` (supplied by
`evita_engine/src/main/java/io/evitadb/spi/store/catalog/chunk/ExpressionBasedSlicer.java:57`),
`$entity` and `$reference` (supplied by
`evita_engine/src/main/java/io/evitadb/core/expression/proxy/`
`ExpressionVariableContext.java:45`), plus a bare `$` meaning "this element" inside a spread.
There is no variable registry — a new variable today means a new implementation of
`ExpressionEvaluationContext` and a new call site.

### 3.3 Why it cannot be interpreted per candidate

Three independent reasons, ordered by weight:

**The codebase rejects it itself, twice.** When evitaDB needed to evaluate an expression over
many entities, it twice chose the same solution — not to evaluate it:

- `ExpressionToQueryTranslator` translates a boolean expression into a tree of `FilterBy`
  constraints so that cross-entity triggers run over indexes instead of interpretation. Its own
  JavaDoc: "The translator is invoked once per expression at schema load time (not on the hot
  path). Its output — a `FilterBy` constraint tree — is cached in the trigger and reused."
  (`evita_engine/src/main/java/io/evitadb/core/expression/query/`
  `ExpressionToQueryTranslator.java:82-84`).
- `HistogramValueDescriptorFactory` reduces the numeric `valueExpression` to a descriptor "read
  attribute X from source Y with default value D" and **rejects anything more complex**
  (`evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/histogram/`
  `trigger/HistogramValueDescriptorFactory.java:109-117`).

**The cost profile is orders of magnitude off.** Every binary operand is coerced to `BigDecimal`
through an `if/else` chain in `EvitaDataTypes.toTargetType`
(`AbstractBinaryOperator.computeLeft/computeRight` → `ExpressionNode.compute(ctx, class)`,
`evita_common/src/main/java/io/evitadb/dataType/expression/ExpressionNode.java:73-79`). Every
variable read allocates an `Optional`. Every collection element in a spread allocates a new
context. The only existing per-entity path additionally instantiates a ByteBuddy proxy
(`evita_engine/.../expression/proxy/ExpressionProxyInstantiator.java:111`) — and it runs on the
**write** path, not the query path. Against that stands `Sorter`, which works over `int[]` and
bitmaps (`Sorter.java:52-57`). These are two different worlds.

**There is neither an acceptance site nor type support.** Both of today's evaluation sites
require a boolean result outright: `ExpressionBasedSlicer.java:135` tests
`Boolean.TRUE.equals(...)` (anything else silently means "no"),
`AbstractExpressionIndexTrigger.java:388-402` throws an exception. A numeric expression has
nowhere to land.

### 3.4 Concrete gaps in the language for a ranking function

If EvitaEL is used as an authoring format, these things are missing from it or surprising:

- **There is no `exp`.** Anything sigmoidal or with exponential decay (the classic decay boost
  by document age) has to be rewritten, or added as a new `FunctionProcessor`. `log` is the
  natural logarithm (`LogFunctionProcessor.java:56`), so `ln` for BM25 is available.
- **There is no conditional construct at all.** The grammar has neither a ternary operator nor
  an `if` (`EvitaEL.g4:45-70`); the only "conditional" constructs are `??` and `*?`, both only
  for null. Conditional boosts have to be written as arithmetic over a boolean — and the
  boolean → number coercion is not exercised anywhere today.
- **Function names must start with a lowercase letter and must not contain an underscore**
  (`EvitaEL.g4:140`). `matchedWords()` passes, `matched_words()` does not.
- **The parser is lenient in a way that hurts hand-written profiles.**
  `ExpressionFactory.parse` invokes the `expression()` rule, not `root()`
  (`ExpressionFactory.java:64`), so the rule `root : expression EOF` (`EvitaEL.g4:43`) never
  applies and **text after the first valid expression is silently discarded**. A typo in a rank
  profile therefore does not fail — it just truncates the function. For schema-configuration use
  that is marginal, for a user-written scoring function it is a defect that has to be fixed
  before deployment (a one-line change) or worked around with custom validation.

### 3.5 Verdict

**EvitaEL is usable as a declaration of a rank profile, unusable as its interpreter.**

The recommended role: the expression is accepted as text, at **profile registration** (not at
query time) it is parsed, validated against a list of known feature slots and **compiled into a
primitive scorer** working over `int`/`float`/`long` arrays. That is exactly the pattern the
codebase has already used twice (§3.3), so the design does not introduce a new principle, it
merely applies it a third time.

A big practical bonus of that route: **compilation needs no new runtime variable.** The compiler
maps names such as `$matchedWords` or `$impact` onto indexes into the feature vector;
`ExpressionEvaluationContext` is not used in the hot loop at all, so the non-existent variable
registry (§3.2) is not an obstacle.

---

## 4. The filter constraint: variants and recommendation

### 4.1 Naming

The research uses the working name `attributeMatches` and marks naming as open question O4.
Four candidates worth considering:

**N1 — `attributeMatches`.** Holds rule 2 (a prefix by the target part of the entity), follows
on from the existing `attributeContains` / `attributeStartsWith`. The risk is substantive: the
first round indexes only attributes, but searchable associated data is a planned extension (O6)
and for the CMS profile it is even a **precondition for deployment** (research §4.2). The moment
fulltext starts searching associated data too, the `attribute` prefix starts lying — and
renaming a shipped constraint is a breaking change in all four APIs at once.

**N2 — `textMatches`** as a generic constraint (`GenericConstraint<FilterConstraint>`). It reads
well ("filter entities by textMatches('black leather jacket')"), it is not tied to one part of
the entity, and in the generated APIs it appears unchanged as `textMatches`
(`ConstraintSchemaBuilder.java:97`). The price is relaxing rule 2 — but rule 3 admits relaxation
and the language uses it routinely (`random`, `userFilter`, `inScope`, `scope`, `page`).

**N3 — `fulltext` / `fulltextMatches`.** Names the technology, not the data. The most
discoverable for users, but against rules 1 and 2; nowhere else does the language name a
technology.

**N4 — `dataMatches`.** The `data` prefix is **already established in evitaDB for exactly
"attributes and associated data together"**: `dataInLocales` describes itself as "fetching
localized attributes and associated data"
(`evita_query/src/main/java/io/evitadb/api/query/require/DataInLocales.java:94-95`), and it is
likewise a generic constraint, not a constraint with a property type (`:99-100`). N4 therefore
holds rule 2 **and at the same time** covers the target scope, which neither N1 nor N2 manages
on its own.

**Recommendation: N4 (`dataMatches`), with N2 (`textMatches`) a close second.** The decisive
argument against N1 is that the name has to hold even after searchable associated data ships,
because renaming a shipped constraint is a breaking change in four APIs at once — and that will
happen exactly with the arrival of the CMS profile, i.e. soon. Between N4 and N2 the decision is
what one prefers: N4 is more idiomatic (it holds the prefix rule and has a house precedent), N2
carries in its name information that N4 does not — that this is an **analysed, word-based**
search, not an exact match. And that very difference is the most important one for a user
distinguishing the new constraint from `attributeContains` (§4.3). The decision is a matter of
the team's taste, not of a technical argument; but it has to be made **before** F1 ships.

The rest of the document uses `textMatches`. Wherever it appears it can be mechanically replaced
by any of the variants without any impact on the argument.

**A warning from the analysis of the old solution: the engine's query language must not leak
into the public API.** The analysis of the e-shop layer
(internal, §3.3) shows how easily that happens. The `SearchParams`
object there is annotated for OpenAPI, so it is at the same time the public contract of the REST
API, and its `query` field is documented as an expression **supporting Lucene syntax** — `AND`,
`OR`, `NOT`, parentheses, quotes for phrases, `field:value`. The user's text is passed straight
to the Lucene parser. Filtering by product type is then done with a `variables` string which the
client sends in the raw form written by hand; the example in the endpoint's documentation reads
`MASTER OR BASIC OR SET`. The query language of the search engine has therefore leaked into a
public HTTP API, and with it its failure modes — the parser can fail on a single parenthesis
typed by the user, so the code pre-emptively escapes special characters and tries again.

For the constraint design one rule and one check follow. The rule: **the client sends a
structured filter, not text intended for the engine.** The user's text is the value of a single
argument of `textMatches`; everything else — fields, weights, product type, categories — has its
own constraint with its own type and is never encoded into a string. It is the mirror image of
the "smart client" trap from research §1.2: there the text shatters in the client, here the
engine imposes syntax on the client, and both end the same way — the semantics spreads outside
the engine and silently diverges from what indexing does. The check: in none of the variants in
§4.2 may there be an argument whose value **the engine parses by its own grammar**. Variant V2
satisfies this by construction, because every refinement is a child with its own descriptor; V1
with the field list as a value list likewise, because that is an array of strings, not an
expression; and the recommended V3 likewise, because the children of `relevance()` are typed
constraints, not expressions.

### 4.2 The shape of the constraint: three variants

The essential question is not the name but **where the field weights belong**. Research §4.2
built postings per (field, term) precisely so that the field weights would not be baked into the
index and could be given per query. The question is whether they are carried by the filter or by
`relevance()`.

#### Variant V1 — a flat leaf

```java
query(
    collection("Product"),
    filterBy(
        entityLocaleEquals(CZECH),
        textMatches("černá kožená bunda")
    ),
    orderBy(relevance()),
    require(page(1, 20))
)
```

Optionally with the field list as a value list, after the model of `attributeHistogram`:

```java
textMatches("černá kožená bunda", "name", "brand", "description")
```

*For:* cheapest to implement (a single leaf constraint, the grammar rule `valueListArgs` already
exists), the smallest surface in the generated APIs.
*Against:* field weights cannot be expressed at all. That removes one of the reasons why the
postings are per field (research §4.2), and per-query weights from the client application's
query AST have nowhere to map. Usable only as a temporary shape for P1, not as the target API.

#### Variant V2 — a container with children

```java
query(
    collection("Product"),
    filterBy(
        entityLocaleEquals(CZECH),
        priceInPriceLists("basic"),
        priceInCurrency(CZK),
        priceValidInNow(),
        textMatches(
            "černá kožená bunda",
            inField("name", 3.0f),
            inField("brand", 2.0f),
            inField("description", 1.0f)
        )
    ),
    orderBy(relevance()),
    require(page(1, 20), referenceSummary())
)
```

*For:* idiomatic — the language uses containers with children everywhere (`referenceHaving`,
`hierarchyWithin`, `segments`). Every child gets its own descriptor, so GraphQL and REST generate
a typed input instead of a string convention. Extensible without a breaking change: a future
`inAssociatedData("body", 1.0f)` is just another permitted child type, not a new version of the
constraint. One `Text` node from the client application's query AST maps onto **exactly one**
constraint, which is precisely what research §1.2 requires (the "smart client" trap consists in
shattering the text; shattering the intent across two parts of the query is its cousin).
*Against:* more classes to write — every child is a fully fledged constraint across all layers
(§13). A more verbose expression for the most common case.

A note on defaults: without children the set of fields and weights from the schema is used, so an
ordinary query stays a one-liner (`textMatches("černá kožená bunda")`).

#### Variant V3 — a split intent

The filter carries only the text and the set of fields; **all** ranking policy (weights, profile,
boosts) goes into `relevance()`.

```java
filterBy(
    textMatches("černá kožená bunda", "name", "brand", "description")
),
orderBy(
    relevance(
        profile("ecommerce-default"),
        fieldWeight("name", 3.0f),
        fieldWeight("brand", 2.0f)
    )
)
```

*For:* a clean division of labour — the filter says "which documents", the ordering "in what
order". It fits test 4 from research §1.2 (field weights are policy, i.e. the client's choice).
*Against:* a single user intent falls apart into two parts of the query which the client has to
keep in sync; a weight on a field that is not in the filter is either a silent error or a new
validation. The computational objection (the filter formula has to know the weights before it
starts computing) turned out to be solvable by plumbing, see below.

#### The technical argument: when the weights apply — and what does (not) follow from it

Research §4.3 says that phase 1 computes a **feature vector** and the rank profile folds it.
Taken literally, that means the filter formula emits *per-field* impacts per candidate and only
the sorter weighs them. But per-field impacts for a million candidates × N fields is orders of
magnitude more data than a single weighted `long` — and the research itself in the lanes table
(§4.3, lane 3) assumes that the **maximum** impact is stored, i.e. an already weighted one. If
the filter formula knows the weights it can collapse the impacts into a single number in one
pass; if it does not know them it must either materialise the per-field matrix or come back to
the impacts a second time. The weights therefore have to be known **at the time the filter
formula is built** — that is the physics of the computation and it holds regardless of syntax.

An earlier version of the design deduced from that that the weights also have to live in the
filter syntactically (V2). That does not follow: principle 2 (§2.6) explicitly permits bindings
between query parts and the mechanism is cheap and proven in the engine — the filter planner
reads `orderBy` at formula build time, just as it reads the histogram flag from the `require`
part today (§9.7). Syntax can therefore follow semantics and plumbing follow physics,
independently of each other.

#### Recommendation: V3 — field weights in `relevance()`, field selection in the filter

The placement is decided by semantics, and that is unambiguous (principle 3, §2.6): **weights
affect relevance, not the match set.** The filter carries the text and the selection of searched
fields — both change *which* documents are found. `relevance()` carries the profile, the boost
table, the K for phase 2 and the field-weight override — everything that changes only the
*order*. The dividing line "does it change the match set, or only the order?" is moreover
exactly the invariant the language already has: the filter determines the count, the ordering
the order, require neither (§2.4).

The field selection stays in the filter in container form — `inField("title")` with a single
mandatory argument, without a weight. A container (and not a value list) because the
extensibility argument from §4.1 holds for the selection just as it previously held for the
weights: once searchable associated data arrives, `inAssociatedData("body")` is just another
permitted child type. The weight override is a child `fieldWeight("title", 5.0f)` of the
`relevance()` constraint, with both arguments mandatory (principle 1: a weight has no sensible
default — the default is expressed by not stating the constraint).

The original objection against V3 — one intent split across two parts of the query and a silent
error on a mismatch — is resolved by validation, not by placement: a `fieldWeight` aimed at a
field that is not searched in this query is a **planning-time error** with a concrete message,
the same pattern as `textMatches` over a non-indexed field (§4.4). A silent variant is not
admitted.

The exception that would reverse the decision belongs written down right next to it: if
`textMatches` were ever to gain a **minimum relevance threshold** argument, the weights would
start affecting the match set as well (the threshold is evaluated over the weighted score) and
they would belong in the filter. But the threshold would at the same time require **normalising
the score onto a fixed scale** (e.g. 0–100), because no comprehensible threshold can be written
against the raw 64-bit lexicographic composite (§2.3) — its values have no scale and change with
every change to the lane widths. Normalisation itself is technically cheap for bounded lanes and
has a direct precedent (Meilisearch, see Q14); the real cost of a threshold is semantic — the
number changes meaning with every change of profile. The threshold is therefore not proposed for
F1; the analysis of the precedents and conditions is in Q14.

A third kind of child of `textMatches` came out of the follow-up discussion (2026-08-14) and its
principle has already been decided: **adaptive relaxation with a targeted lower limit** — "when
there are fewer than N strict matches, loosen the condition by dropping the least selective
terms". It belongs here, in the filter, because it changes the match set; it is strictly opt-in
(absence of the child = no relaxation, today's semantics) and N is a mandatory argument
(principle 1: two equally sensible defaults compete — 1 "only against an empty page" and the page
size "fill the page" — so there is no single sensible default; the repetition in every query is
removed by the query profiles from issue #12). The decision and the rejected variants are recorded
in [`../README.md`](../README.md) ("Decisions taken"); three implementation rules (level decided
once per query, dead terms out before the ladder, relaxation visible in the response) are in Q16.
The name of the child is open; the example below uses the working `relaxUntil(...)`.

The shape "an optional child with a mandatory argument" has an exact precedent in the language:
`SegmentLimit`. Inside `segment(...)` the child `limit(...)` is declared `@Nullable` — the whole
of it may be omitted and the default is "no limit" — but once it is stated, its number is
mandatory and validated (`Segment.java:117-121`, `SegmentLimit.java:86` — `limit > 0` otherwise
an exception). The language also knows mandatory children for cases where the mandatory value is
itself a constraint: `hierarchyWithin` has `ofParent` as `@Nonnull @Child`
(`HierarchyWithin.java:267`, JavaDoc "required"). The rule therefore reads: an always-mandatory
*value* is a positional argument of the parent (the text in `textMatches`), an always-mandatory
*subtree* is a `@Nonnull` child, and an optional capability without a sensible default for its
value is a `@Nullable` child with mandatory arguments — exactly the shape of `relaxUntil`,
`fieldWeight` and `boostTable`. Behind that mechanics stands a deeper principle of the language
(sponsor, 2026-08-14): the query should read as a **self-explanatory English sentence** (§2.1,
rule 1). A pile of positional arguments loses the context — in `textMatches("jacket", 20)`
nothing says what the twenty is — whereas a child carries an **introducing word** that gives the
meaning a name: `textMatches("jacket", relaxUntil(20))` can be read aloud. A positional argument
is therefore only fine where the parent's own name gives it context (the text in `textMatches`,
the number in `limit`); everything else gets its own word. It is the same principle that decided
R2 against R1 in §5.1 and by which the relaxation ADR rejects string mini-grammars à la
`"3<90%"` — an expression whose meaning cannot be read off does not belong in the language.

V1 remains as a legitimate **shortened form for P1**, so that the prototype does not pay the toll
of the full DSL surface before the core is measured. V2 (weights in the filter) remains recorded
as considered and rejected: the technical argument that spoke for it dissolved with principle 2,
and against it stands the semantics of ordering — a weight inside the filter tempts one to read
it as a match condition, which it is not.

#### A consequence for plumbing: the filter planner reads `orderBy`

The recommendation stands or falls with the filter formula getting to the contents of
`relevance()` at the time it is built — to the per-query overrides from `fieldWeight` as well as
to the profile name, because a named profile carries field weights too (§6.1; Vespa rank profiles
carry them and the CMS profile generated by the Sage platform, which wants to weigh the body of
an article higher than the perex, needs somewhere to say so). The override order is schema →
profile → query, with total replacement at the level of the individual weight (§6.1).

The mechanism exists and is not forced: the filter formula is built in
`QueryPlanner.createFilterFormula`
(`evita_engine/src/main/java/io/evitadb/core/query/QueryPlanner.java:154`) *before* sort planning
(`:168`), but the whole query including `orderBy` is on the request from the start. Reading the
contents of `relevance()` at filter-formula build time is therefore the same trick the engine
already uses for the price histogram — there `FilterByVisitor` reads a flag derived from the
`require` part during filter planning
(`evita_engine/src/main/java/io/evitadb/core/query/filter/FilterByVisitor.java:592-595`). But it
is a **plumbing requirement, not a matter of course** — it is written out in §9.7, because a
reader otherwise naturally assumes that filter planning knows nothing about the `orderBy` part.

#### Example: per-query field weights and a different profile in one query

The recommended shape (V3 + R2 + unified profiles from §6.5) put together, i.e. the case where
the client overrides the weights for this particular query and at the same time picks a
non-default profile:

```java
query(
    collection("Article"),
    filterBy(
        entityLocaleEquals(CZECH),
        scope(LIVE),
        textMatches(
            "jak zateplit půdu",
            inField("title"),
            inField("perex"),
            inField("body"),
            relaxUntil(20)                     // working name; for the principle see ADR + Q16
        )
    ),
    orderBy(
        relevance(
            boostTable("behavioural-2026-08"),
            fieldWeight("title", 5.0f),        // overrides the weight from the profile
            fieldWeight("perex", 2.0f)
        )
    ),
    require(
        page(1, 20),
        profile("cms-longform")                // the query profile (#12) inserts the rest of the configuration
    )
)
```

The profile `cms-longform` is an ordinary query profile from issue #12 (§6.5): its rules insert
the lane order and the default field weights into `relevance()`. Explicit children in the query
beat the inserted ones item by item: a `fieldWeight` from the query overrides the weight from the
profile, that overrides the default weight from the schema; a field not stated in the query keeps
its weight from the profile — the override is not a replacement of the whole set. The field
`body` is therefore searched (it is in the filter's list) and sorted with the weight from the
profile. A `fieldWeight` on a field outside the filter's list is a planning-time error.
`relaxUntil(20)` says: if the intersection of all words has fewer than 20 candidates, drop the
least selective terms until there are at least 20 — and the response gains the information about
what was dropped and how many strict matches there were (Q16).

### 4.3 Relationship to today's `attributeContains` and `attributeStartsWith`

They stay unchanged and have a different contract: an exact substring, case-sensitive, without
analysis (`evita_query/src/main/java/io/evitadb/api/query/filter/AttributeContains.java:104-113`
— the annotation states "case-sensitive", the JavaDoc "No wildcard expansion — the search pattern
is matched literally"). Today's implementation moreover works over NFD normalisation
(`io.evitadb.core.query.filter.translator.attribute.AbstractAttributeStringSearchTranslator`).

For the user the difference is substantive and the documentation has to say it right away:
`attributeContains` searches characters, `textMatches` searches words. Criterion P1 in the
research ("side-by-side quality against `attributeContains` on ~50 real queries") is exactly
about that difference.

The interaction O3 warns about: the `textMatches` analyser and the NFD normalisation of
`attributeContains` have to coexist over the same attribute values without changing each other.
That is a criterion of prototype P5, not a DSL question.

### 4.4 Behaviour without schema support

The engine is strict about indexes — it neither filters nor sorts over data for which no index is
prepared (`documentation/user/en/query/filtering/behavioral.md:38-41`). A `textMatches` over a
collection without a single fulltext-indexed field must therefore fail with an exception at
planning time, not return an empty result. The same for an `inField` aimed at a field that is not
fulltext-indexed. This is consistent with "rejected at construction time" for histograms
(`requirements/reference.md:136-137`).

### 4.5 Membership in `userFilter`

The question from §2.2: does `textMatches` belong in `userFilter`, and if so, when is it peeled?

The consideration starts from what `userFilter` means — a filter the user controls through the UI
and which may be peeled off during a computation so that the computation does not measure itself.
A fulltext query **is** user input, but it does not have the shape of a slider or a checkbox: no
"offer of what would happen if the user cancelled it" can be built from it, because without it
there is nothing to talk about — the whole result would disappear, not merely its narrowing.

At the same time the opposite pressure holds: if `textMatches` is **not** in `userFilter`, facet
counts and histograms are computed over the fulltext-narrowed set. That is almost certainly what
the user wants — facets should describe the search results, not the whole catalogue.

**Recommendation: `textMatches` belongs outside `userFilter`, in the mandatory part of the query,
and inside `userFilter` it is forbidden** — with the same wording and the same mechanism by which
a plain `referenceHaving` is forbidden there (`requirements/histogram.md:529-531`). The peeling
matrix (`filtering/behavioral.md:161-166`) thereby **does not change**, which is a value in
itself: a new carrier family means new rows in two documentation tables and new combinations to
test.

The "offer, do not apply" flow (research §1.3) does not suffer from this. It works with
`facetHaving` inside `userFilter`, which is existing mechanics — the recognized entity is offered
as an extra result (§8.3) and only the user's click generates a second query with an ordinary
`facetHaving`. The fulltext constraint stays in the same, mandatory position throughout that flow.

One variant remains open and needs verifying against a real UI: if the product wanted to offer
"cancel the search, keep the filters" as a single click with a count prediction, that would be an
argument for a fourth family. Recorded as Q4 in §14.

---

## 5. `relevance()`: the ordering constraint

### 5.1 Shape

The model is `Random` — a generic ordering constraint without a classifier, with a clean route to
a named variant (`evita_query/src/main/java/io/evitadb/api/query/order/Random.java:65-75`: name
`random`, `GenericConstraint<OrderConstraint>`, `ConstraintWithSuffix` for `randomWithSeed`).

Two variants of the shape:

**R1 — a leaf with optional arguments.**

```java
orderBy(relevance())
orderBy(relevance("cms-articles"))       // profile name
```

*For:* the smallest surface, `ConstraintWithDefaults` is already used by `QueryTelemetry`
(`evita_query/src/main/java/io/evitadb/api/query/require/QueryTelemetry.java:87-93`) and solves
exactly this situation — `relevance()` and `relevance("default")` are the same constraint.
*Against:* the research wants three independent things in `relevance()` — the profile, a reference
to a boost table and the K for phase 2. Three positional arguments of different types read badly
and every further one is a change of signature.

**R2 — a container with children.**

```java
orderBy(
    relevance(
        boostTable("behavioural-2026-08"),
        rerankCount(1000),
        fieldWeight("title", 5.0f)
    )
)
```

*For:* extensible without a breaking change, every parameter has its own type in the generated
APIs, and reading is unambiguous. There is a precedent for an order container with children in the
language (`referenceProperty`, `segments`).
*Against:* more classes (§13), and for the most common case `relevance()` without children it is
dead weight — but that is not paid, because the children are optional.

**Recommendation: R2.** The decisive point is that growing a leaf into a container later is a
breaking change in the descriptor framework, whereas starting with a container and leaving it
empty costs nothing. Three kinds of children are certain already today — the boost table, K and
the field-weight override (principle 3, §2.6); a fourth (the fusion policy for a hybrid query,
§11) is likely, and for the full inline form the lane permutation will be added too (§6.3,
principle 5). **Profile selection is not among the children** — after unification with query
profiles (§6.5) the profile is chosen in `require(profile(...))` by the issue #12 mechanism and
its rules only then insert the children of `relevance()`. Principle 1 (§2.6) applies to every
child: its arguments are mandatory, because a child without a value has nothing to say —
optionality is expressed by omitting the child, not by an empty argument. `relevance()` itself
without children is the most common shape: the configuration is supplied by the profile, or by the
built-in default.

**One concrete comment belongs to the shape of `rerankCount()`.** The research proposes K as an
absolute constant of 1000. Verification against an OpenSearch checkout (main, commit
`36edc05ac84`, 2026-08-12) shows on a pair of processors that elsewhere it is done as a **multiple
of the requested page size**: `OversampleRequestProcessor` multiplies the request's `size` by a
`sample_factor` (mandatorily ≥ 1.0) and **stores the original value in the processing context**
under the key `original_size`; a processor that reorders is inserted between it and the end; and
`TruncateHitsResponseProcessor` truncates the result back at the end, taking the target size
preferentially from that context and failing without it with an explanatory exception
(`modules/search-pipeline-common/…/TruncateHitsResponseProcessor.java:36`, `:55` and `:58`).

A multiple is a better shape than a constant, and from two sides at once: a query for the first
page of twenty items does not need to reorder a thousand candidates, whereas a query with a large
page could, under a fixed K, want more results than phase 2 ordered at all. The recommendation for
the shape of the constraint is therefore to understand `rerankCount()` as **`max(minimum, multiple
× page size)`**, not as a bare number — either by making the argument a multiple, or by having the
documentation describe the absolute value as a cap over a derived multiple. The concrete numbers
are question O2 and P4 measures them.

The second lesson from that pair is architectural and aims at §9: the enlarged size is passed
there **by the request context, not by the signature**. For us that means the evaluation context
has to be able to carry "how much the client actually wanted" separately from "how many candidates
we let through for phase 2". If K were projected directly into `require(page(...))`, **the enlarged
size would leak all the way into the response** — the client would get a thousand items instead of
twenty and the paging numbers would stop making sense. It is a detail, but exactly the kind of
detail that is discovered late.

### 5.2 Behaviour without a fulltext filter

O4 from the research: an error, or a no-op? The language has both precedents.

- **Silent degradation:** `priceNatural` without a price filter → `NoSorter`
  (`PriceNaturalTranslator.java:86-96`).
- **A hard requirement on a sibling:** `entityPrimaryKeyInFilter` "requires the presence of
  exactly one `entityPrimaryKeyInSet` constraint in the filter part"
  (`ordering/constant.md:23-25`).

The distinguishing criterion is whether the missing key is a **data situation** or an **error in
constructing the query**. With prices it is a data situation: the schema has prices, only this
particular query does not filter them, and different entities may or may not have a selling price.
With relevance it is different — without a fulltext filter **there is no scoring channel for any
candidate**, so `relevance()` cannot sort anything. That is not degradation, that is a nonsensical
query.

**Recommendation: an error at query planning time**, with a concrete message naming the missing
constraint. The argument for a silent no-op — consistency with `priceNatural` — does not hold,
because `priceNatural` is treated both in the research and in the documentation as a cautionary
example, not as a model: it declares the dependency, the consequence nowhere (§2.5).

And regardless of the choice: **the consequence has to be written into the user documentation.**
That is the only part of this decision that is not a matter of taste.

**A note on the error contract which does not follow from the above but belongs next to it.** The
recommendation above talks about an error at *planning* time, i.e. about a badly assembled query. A
different question is what should happen on a *runtime* error — a corrupted or unavailable index
structure. The analysis of the e-shop layer (internal, §3.5) shows
how that is handled in production today: the client catches any exception from the search library,
logs it and returns an **empty page**. An outage or corruption of the index therefore manifests
outwardly as "we found nothing". The lesson is not that this is right, but that **the client wants
degradation, not a crash — and yet has to know about it**. A silent empty page is the worst
combination of both: the search looks functional and returns an untruth which nobody can tell apart
from a legitimate zero result. For `textMatches` and `relevance()` that yields a requirement to be
decided together with the shape of the response: if degradation at runtime is to happen, the
degradation must be **visible in the response** — by a flag, or by a response distinguishable from a
legitimate zero — not knowable only from the server log. For a planning error this does not hold;
that is to fail loudly, as the previous paragraph recommends.

### 5.3 Prohibition in nested contexts

`relevance()` **must not** be usable inside `referenceProperty`, `entityProperty` or any other
nested ordering context, and the reason is structural, not a design choice.

The order translator gets to the state of the filtering phase through
`OrderByVisitor.getFilteringFormula()` and `getFilterByVisitor()`. Both methods **throw
`EvitaInvalidUsageException` when the field is `null`**
(`evita_engine/src/main/java/io/evitadb/core/query/sort/OrderByVisitor.java:456-463` and
`:472-479`). The nested path constructs the visitor with the single-argument constructor
(`OrderByVisitor.java:205-210`), which leaves both fields `null` — it is used by
`OrderByVisitor.createSorter(...)` at `:179`.

Without explicit handling, `relevance()` in a nested context therefore falls over on the generic
message "available only from the main query context", which tells the user nothing. The constraint
has to fail with its own, concrete exception, and the documentation has to state it with the
settled wording "can only be used in the top-level `orderBy`".

### 5.4 Composition with other ordering constraints

See §2.3. `orderBy(relevance(), attributeNatural("popularity", DESC))` is legal and does something
other than the user expects. The recommended documentation wording: state it as an explicit warning
with an example, and point at the right solution — a business signal belongs **in the rank profile
as a lane** (research §4.3, lane 6 "context rank"), not after `relevance()` in `orderBy`.

In practice this means `relevance()` will be the only ordering constraint in the vast majority of
queries. That confirms answer Z6 of the research ("a single `orderBy(relevance())`, chaining makes
no sense") — with the addition that chaining makes no sense **because of the semantics of the
language**, not merely because of a product consideration.

---

## 6. Defining a rank profile

### 6.1 Four layers

Research §4.3 wants the profile to be a configuration (a permutation of lanes, weights, possibly a
different composition), optional per query. Question O1 asks about granularity. The proposal is
four-layered, with the layers ordered by volatility — which is the same dividing line the research
uses for priors versus boosts ("the dividing line is volatility, not dogma", §4.3):

| Layer                    | Where it lives                        | How it changes                        |
|--------------------------|---------------------------------------|---------------------------------------|
| 0 — built-in default     | code                                  | with an evitaDB release               |
| 1 — collection schema    | `EntitySchema`                        | schema mutation, WAL, BWC discipline  |
| 2 — profile + artefact   | query profile (#12) + data artefact   | an API call at runtime, no reindexing |
| 3 — query                | `textMatches` + `relevance()`         | per request                           |

The contents of the individual layers: layer 0 carries the lexicographic packing of the lanes per
the table in research §4.3. Layer 1 carries the information about which fields are fulltext-indexed
at all, their default weights, the pivot of the length normalisation and the name of the default
profile. Layer 2, after unification with query profiles (§6.5), has **two natures**: configuration
(the order and widths of the lanes, **field weights**, coefficients) lives in the rules of the
query profile from issue #12, data (boost tables, synonym and entity dictionaries, possible LTR
models) in named hot-swap artefacts which constraints reference by name. Layer 3 carries the choice
of profile (`require(profile(...))`, the #12 mechanism), the reference to the boost table, the K
for phase 2 and the per-query field-weight override.

**Field weights occur in three layers, and that is by design, not an oversight.** The schema gives
a sensible default state, the profile overrides it for a whole class of queries, the query for one
particular one. Layer 3 lives entirely in `orderBy` — `fieldWeight` as a child of `relevance()`
(principle 3, §2.6); that all three layers meet as early as the building of the filter formula
follows from the fact that the filter planner may read `orderBy` — the mechanism and its cost are
described in §4.2 and §9.7.

The difference between layer 1 and layer 2 is essential and does not follow from convenience.
**What belongs in the schema is what affects the write path**: which field is tokenized at all and
stored into postings, what pivot is baked into the impact byte (research §4.2). Changing those
things means reindexing, so it has to go through a schema mutation with all the BWC discipline.
**What belongs in an artefact is what can be exchanged without reindexing**: the order and weights
of the lanes, coefficients, boost tables. Research §4.6 already introduced this category for
synonym and entity dictionaries; after unification (§6.5) the configuration half of this layer is
carried by the query profile from issue #12 and the artefact channel is left to data alone — but
both share the key property: exchange at runtime by an API call, without a schema mutation.

Composing the layers is **total replacement at the level of the individual item, not merging** —
i.e. the same rule the language already uses for the per-reference override of the reference
summary ("The override is total: every constraint on the per-reference variant replaces the
matching constraint from the generic one — they are never merged",
`documentation/user/en/query/requirements/reference.md:721-726`). A field weight given in the query
overrides the weight from the artefact, that overrides the weight from the schema. Nothing is added
up.

### 6.2 Three variants of defining a profile

> **Note after unification (2026-08-14, §6.5):** the variants below remain as analysis, but P-A is
> realised by the query-profile mechanism from issue #12 — selection by name is
> `require(profile("cms-articles"))`, not a child of `relevance()`, and "artefact" in P-A means the
> definition of a query profile. The conclusions about precedence and the reserved "no profile"
> hold on, they only move into the design of #12.

#### Variant P-A — a named profile, selected by name

The profile is a named configuration in an artefact; the query selects it by name.

```java
orderBy(relevance(profile("cms-articles")))
```

The contents of the profile (outside evitaQL — it is an artefact, not a query) is a list of lanes
with widths and sources, field weights and possible coefficients.

*For:* the smallest surface in the language, the profile can be changed without touching the client
application, an A/B arm is one extra name. That is exactly how Vespa does it (`rank-profile` in the
schema + `ranking.profile` in the query) and Solr LTR (`model store` + `rq={!ltr model=...}`).
*Against:* every new variant of the lane order is a new artefact; a small change cannot be tried
out from the client without a deployment.

**Precedence among profile sources — and why that is not the same as the four layers from §6.1.**
The table in §6.1 describes **where the values come from** (built-in default, schema, artefact,
query) and how they compose, i.e. by total replacement item by item. This is a different axis:
**which profile is used for a particular query at all** when several places offer one at once.
Verification against an OpenSearch checkout (main, commit `36edc05ac84`, 2026-08-12) gives a
ready-made answer in a single method, `SearchPipelineService.resolvePipeline()`
(`server/src/main/java/org/opensearch/search/pipeline/SearchPipelineService.java:464`): first an
inline definition directly in the request, then the name of a stored pipeline in the request
(`:500`), then the index's default pipeline (`:511`), and finally the reserved name `_none` meaning
"none", which is at the same time the default value of that setting.

Of those four levels **three** are usable for us: the profile name in the query > the default
profile in the collection schema > no profile. The first level, i.e. a full inline definition in the
query, is precisely what §6.3 rejects for variant P-B in the matter of lane order and what §6.4
backs with an empirical argument — Elasticsearch is the only one of the five that sends the scoring
function in every query, and at the same time the only one where the ranking logic systematically
settles in client applications. The recommendation therefore remains unchanged; the finding only
adds two things to it that are otherwise easy to omit and are then discovered in production.

**A reserved name for "no profile" has to exist.** Without it a collection's default profile cannot
be suppressed from an individual query, so a client that wants the raw order — while debugging,
while measuring against a baseline, or in the control arm of an A/B test — has no way to ask for it.
In practice that means either a reserved value of the `profile()` argument, or its own shape; it can
be added without a breaking change only until the default profile in the schema ships, so the
decision belongs in F1. **And on ambiguity it is better to use no profile at all** than to pick one
of several arbitrarily — the same choice OpenSearch makes for a query across several indexes with
different default pipelines (`SearchPipelineService.java:515`), and the same one that `CLAUDE.md`
requires after unexpected states. `p7-rank-profiles-and-boost-channel.md` §4.3 keeps it in the same
wording.

#### Variant P-B — inline configuration in the query

The query carries the lane permutation and the weights directly.

```java
orderBy(
    relevance(
        lanes(MATCHED_WORDS, TYPO, IMPACT, EXACTNESS, CONTEXT),
        fieldWeight("name", 3.0f)
    )
)
```

*For:* immediate experimentation, zero deployment latency, per-query determinism is preserved.
*Against:* the ranking logic spreads into the client application — exactly the drift research §1.2
guards against for the analyser. The request grows. And above all: a configuration in the query
cannot be versioned or reviewed, so "why did it sort differently last week" has no answer.

#### Variant P-C — an expression as a custom scoring function

The profile contains an expression in EvitaEL over the features:

```
$matchedWords * 1000000
  + (255 - $typoPenalty) * 10000
  + $impact * 100
  + $contextBoost
```

*For:* full freedom of composition — non-linear weights, ratios, thresholds. It matches the other
pole of the state of the art (Vespa rank expressions, ES `function_score`).
*Against:* per §3 the expression has to be compiled, not interpreted, so the supported subset of the
language will be narrower than the whole of EvitaEL and that has to be documented (precedent:
`HistogramValueDescriptorFactory` rejects anything outside a narrow shape,
`HistogramValueDescriptorFactory.java:109-117`). `exp` and a conditional construct are missing
(§3.4). Explainability drops — "it is higher because 0 typos and a match in the name" is not derived
from a free-form expression as directly as from a cascade.

### 6.3 Recommendation: layering, not a choice

These variants are not mutually exclusive and it makes sense to deploy them in order:

1. **F1 ships the built-in default + the full inline form; named profiles come with issue #12
   (unification, §6.5).** F1 therefore builds no profile storage — P-A is realised by query profiles
   and has to be designed and implemented **concurrently with F1**, so that selection by name (and
   with it the A/B harness and the Algolia↔Meilisearch spectrum through one configuration, research
   §4.3) exists at the moment fulltext needs it.
2. **P-B is a fully fledged form of expression, not just a debugging exception.** The sponsor
   formulated this as principle 5 (§2.6): the developer tunes the query in the query console with
   all parameters inserted by hand and only then extracts a part of the tuned query into a profile.
   The inline form must therefore be able to express **the same as a profile** — including the order
   and widths of the lanes — otherwise what is then deployed as a profile could not be tuned in the
   console. The risk of drift (ranking logic settled in clients, §6.4) is not addressed by a
   prohibition but by two softer tools: the documentation steers production towards profiles and
   marks the inline form as a debugging and experimental route; and the inline children of
   `relevance()` use the same names and the same semantics as the profile's items, so extracting
   "make a profile out of the tuned query" is a mechanical conversion, not a rewrite.
3. **P-C only when it turns out that a lane permutation is not enough** — typically with the arrival
   of LTR models from the Sage platform, i.e. in phase F3. The expression compiler can be built so
   that the built-in default is just one of its outputs, so P-C is not a new system alongside P-A but
   its generalisation.

### 6.4 How the others do it (for comparison of shape)

Briefly, purely as inspiration for the shape of the DSL; the detailed verification is in §8 of the
research.

| Engine | Where the profile is | Selection in the query | Per-query values |
|---|---|---|---|
| Vespa | `rank-profile` in the schema | `ranking.profile=…` | `ranking.features.query(…)` |
| Solr LTR | model store, hot-swap | `rq={!ltr model=…}` | `efi.*` |
| Elasticsearch | no stored profile | `function_score` in the body | directly in the query |
| Typesense | none, a fixed composite | — | `query_by_weights` |
| Meilisearch | `rankingRules` (index setting) | — | none |

Closest to the proposed route is **Vespa**: the profile is named and stored, the query selects it by
name and supplies named per-query values with it. The difference is in the placement — Vespa has the
profile in the application's deploy artefact, the proposal puts it into a hot-swappable data
artefact, because in the target state Sage generates it continuously, not a human at deployment
time. That is a substantive difference following from §1.1 of the research, not an oversight.

Worth noting is that **Elasticsearch is the only one that sends the scoring function in every
query** — and it is at the same time the only one of the five where the ranking logic systematically
settles in client applications. That is an empirical argument against P-B as the main route.

### 6.5 Relationship to the planned query profiles (issue #12)

evitaDB has long planned **query profiles** — issue
[#12 "Query profiles"](https://github.com/FgForrest/evitaDB/issues/12) (open since February 2023).
Their purpose is different from that of rank profiles: to remove the **repeated blocks of queries**
that the client has to send over and over and that developers forget about when assembling queries
by hand — persistent filters (`priceValidInNow()`, `attributeEquals("status", "ACTIVE")`), derived
constraints (`facetGroupsNegation` by an attribute of the referenced entity), fixed settings
(`QueryPriceMode`). A profile is a named server-side definition of declarative rules; the client
writes `require(profile("b2c"))` and the server enriches the query conjunctively. Profiles are meant
to be **composable** (`profile("b2c", "visible")`) and `queryTelemetry` is meant to be able to return
the resulting composed query, because part of the composition is hidden from the developer.

The original version of this section proposed "two related but separate things that must not get in
each other's way". At the review (2026-08-14) the sponsor decided on a stronger direction:
**unification**. A rank profile is not to be a separate mechanism with a specialised notation — it is
to be an ordinary query profile from issue #12, whose rules insert children into `relevance()`. The
intended shape of a rule is `{location, operation, childConstraint}`; for the filters of profile
"b2c" e.g. `{location: "hierarchyWithin", operation: "ADD", childConstraint:
"excluding(attributeEquals('visibility', INVISIBLE))"}` and for ranking in exactly the same way:

```
{
  location: "relevance",
  operation: "ADD",
  childConstraint: "lanes(MATCHED_WORDS, TYPO, IMPACT, EXACTNESS, CONTEXT),
                    fieldWeight('name', 3.0f)"
}
```

The client then writes `require(profile("cms-articles"))` and the server enriches the query — the
ranking configuration travels the same route as persistent filters. **The child `profile(...)` inside
`relevance()` is thereby abolished** — it would be exactly the specialised notation that unification
avoids; the examples in §4.2 and §5.1 are adapted to that.

What unification dissolves:

- **Q15 (the name clash) disappears** — there is only one notion of "profile".
- **No registry of rank profiles is built.** F1 needs no profile storage: it supplies the full inline
  form (P-B, principle 5) and a built-in default; named profiles come with issue #12. A real cut in
  the scope of F1 — and at the same time a hard dependency: **#12 has to be designed in depth and
  implemented concurrently** (the sponsor explicitly), so that profiles exist by the time fulltext
  needs them — Sage is meant to generate them continuously.
- **Precedence and telemetry are not solved twice.** "The client's explicit notation vs. an inserted
  block" is a general rule of #12 for all constraints; the effective ranking configuration is visible
  in the composed query from `queryTelemetry`, exactly as #12 intends.
- **Extraction from the query console is literal** (principle 5): the children tuned in the query are
  copied into the rule's `childConstraint` — the inline form and the profile form are the same
  notation, the conversion is copy-paste.

What unification **does not** dissolve — layer 2 from §6.1 splits into two natures. **Configuration**
(the order and widths of the lanes, field weights, coefficients, the choice of fusion) becomes rules
of a query profile. **Data** (boost tables, synonym and entity dictionaries, LTR models) stays as
named hot-swap artefacts which constraints reference by name
(`boostTable("behavioural-2026-08")`) — a profile can insert a constraint with a reference, but the
artefact itself cannot be and is not carried by a rule.

And what unification **requires from #12** — questions to settle in discussion on the issue before
building starts:

1. **Runtime updates without a schema mutation.** Sage generates profiles continuously; the profile
   definition has to be replaceable by an API call (the issue intends it that way), otherwise layer 2
   loses the hot-swap property on which §4.6 of the research rests.
2. **Conditional application by `location`.** A rule with `location: "relevance"` applies only in a
   query that contains `relevance()` — otherwise it sleeps. Without that a composite profile (filters
   + ranking) would break non-fulltext queries. Decide whether `ADD` can also create the target
   constraint, or only supplement an existing one.
3. **Collision semantics item by item.** When the query already has `fieldWeight("name", …)` and the
   profile inserts `fieldWeight("name", 3.0f)`, no duplicate child may arise. The rule in §6.1 (total
   replacement item by item, query > profile) requires an operation with the semantics "insert only
   if an item with the same classifier is missing" — the vocabulary of operations (`ADD`,
   `ADD_IF_ABSENT`, `REPLACE`?) is the core of the #12 design.
4. **A collection default profile and a reserved "no profile".** The precedence chain from §6.2
   (query > collection default > none) moves into #12 as a general capability — the issue already
   contemplates profiles enforced per session; a per-collection default is a natural sibling. The
   reserved value "no profile" remains necessary (debugging, baseline measurement, the control arm of
   an A/B test).
5. **Cache and hash.** A profile definition may change between two identical client queries — the
   cache key has to be derived from the query **after enrichment** (or carry the version of the
   profile definition), otherwise the cache serves a result computed under the old definition. The
   same principle for which §9.7 mixes the effective weights into `includeAdditionalHash()`.

---

## 7. The boost channel

Question O8 of the research leaned towards **a reference to a stored, Sage-generated table** with an
inline map as a debug channel. The design confirms that and adds the shape.

```java
orderBy(
    relevance(
        profile("ecommerce-default"),
        boostTable("behavioural-2026-08")
    )
)
```

The table is a hot-swappable layer-2 artefact (§6.1), keyed by query, with a map PK → coefficient as
the value. At query time the engine picks the row corresponding to the analysed query and consults
the map in **phase 1 over the full candidate set** — a document outside the top-K has to have a
chance to climb, as research §4.3 stresses.

A debug/override channel for an inline map is needed, but it must not be more convenient than the
table, otherwise it will settle in production. The proposal: the inline variant exists only as a
`boostTable` with literal contents instead of a name, with a documented size cap, and the
documentation labels it a debugging tool.

Two things have to be decided and the research left them open: **versioning of the tables** (does the
query name a version, or an alias to the current one?) and a **size limit**. Both recorded in §14.

The freshness of the boosts costs no reindexing — the query context does not go through the write
path (research §4.3, §4.9). This is a property the DSL should not accidentally throw away: if the
boost table were selected by the schema instead of by the query, changing the boosts would become a
schema mutation and the advantage would disappear.

**A precedent for "a large set of primary keys in a query", should one be needed.** The decision to
send a reference to a stored table instead of a map in the query is also supported by a finding from
the other side. Verification against an OpenSearch checkout (main, commit `36edc05ac84`, 2026-08-12)
shows that where they did decide to send a set in the request, they had to invent a **binary format**
for it: `TermsQueryBuilder` knows the value type `BITMAP`
(`server/src/main/java/org/opensearch/index/query/TermsQueryBuilder.java:108`) and expects a
single-element array with a **base64-encoded serialized roaring bitmap** — the error message says so
literally (`:514`). Evaluation goes through `BitmapIndexQuery` over the inverted index or
`BitmapDocValuesQuery` over the columnar values and a 64-bit variant exists as well. The motivation is
obvious: passing a list of hundreds of thousands of identifiers as an array in JSON is unbearable,
whereas a roaring bitmap is compact and the engine can work with it natively.

For the boost map that yields a confirmation: **even where they did decide to send the set, the
textual form could not bear the volume** — which is exactly why a reference to a stored artefact is
the right thing for us and why the inline map should stay a debugging tool with a documented cap. And
should the need after all arise to send a large pre-selected set of primary keys in a query (typically
a selection the client application computed elsewhere), **a serialized roaring bitmap is a verified
answer to the shape of the API** — evitaDB has its own vendored RoaringBitmap implementation, so it
would be a format both sides understand without a new dependency. It does not belong in F1; it belongs
here as a recorded possibility, so that no custom format is invented for that case.

---

## 8. Require: feature export and annotations

### 8.1 Why these are extra results, and why it is not cheaper otherwise

Feature export and explain change neither the number nor the order of returned entities, so per §2.4
they belong in `require`. But a second possibility suggests itself — attaching the score directly to
the returned entity, as another field in its DTO. That possibility is **markedly more expensive**, not
cheaper, and it is worth saying why.

In evitaDB there is today **no precedent for an engine-computed per-entity scalar that would reach the
client in the entity DTO.** `priceForSale` is not that precedent — it is computed lazily in the
decorator from prices that are already on the entity, driven by a predicate derived from the request
(`evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/`
`EntityDecorator.java:1446-1451`, the predicate at `:139`). The only genuinely engine-computed
per-entity scalar is `ioFetchCount` on `ServerEntityDecorator`
(`evita_engine/src/main/java/io/evitadb/core/query/response/ServerEntityDecorator.java:81-85`) — and
that one **does not reach** the client per entity, it is aggregated into the query telemetry.

Placing the score on the entity therefore means building a new mechanism across gRPC, REST and GraphQL.
An extra result, by contrast, has the whole pipeline done. The naive solution is the expensive one here.

### 8.2 `featureVector()` — shape and plumbing

```java
query(
    collection("Product"),
    filterBy(
        entityLocaleEquals(CZECH),
        textMatches("černá kožená bunda")
    ),
    orderBy(relevance()),
    require(
        page(1, 20),
        entityFetch(attributeContent("name")),
        featureVector()
    )
)
```

The result is an extra result keyed by the PKs of the returned page, with the score decomposed into
features (the number of matched words, the typo penalty, the impact per field, exactness, the context
boost, the resulting lane value and the overall `long`).

**One plumbing change is necessary and it is small.** Extra results are fabricated **after sorting and
paging but before fetching the entity bodies** — `QueryPlan.execute` sets `this.primaryKeys` at
`evita_engine/src/main/java/io/evitadb/core/query/QueryPlan.java:292` and calls
`fabricateExtraResults` ten lines later at `:302`, while the entity fetch starts only at `:309`. The
page is therefore known at that moment, but the producer cannot get to it — `fabricate()` receives only
a `QueryExecutionContext`, and that has no array of primary keys. Propagating the page into the
execution context before the call at `:302` is safe: `fabricateExtraResults` has **exactly one caller**
in the whole repository.

Two consequences to bear in mind: at fabrication time the **entity bodies do not exist yet**, unless
they are prefetched; and no producer today works over a page — they all work over the full candidate
set. `featureVector()` will be the first of its kind.

### 8.3 Annotating recognized facets: `recognizedFacets()`

The "offer, do not apply" flow (research §1.3) needs an extra result that returns, alongside the
result, "recognized 'Bosch' = brand (PK 123); the corresponding facet filter: …".

```java
require(
    page(1, 20),
    recognizedFacets()
)
```

The name is `recognizedFacets`, not `recognizedEntities`, and it is a decision about the mandate, not
about taste (sponsor, 2026-08-14). The engine may annotate only what it finds by a **deterministic
dictionary lookup** and what at the same time maps onto a **primitive the engine already has** — and
the intersection of both conditions is today exactly facets: a surface form from the entity dictionary
→ (reference type, PK) → a ready `facetHaving`. A broader name would invite creeping scope expansion
("why does it not also recognize price and place?"); a narrower name states the contract by itself.

What deliberately **is not** the job of this annotation, because it violates one of the two conditions:

- **Interval intents** — "cheap trousers" → `priceBetween(0, 1000)`. "Cheap" has no dictionary
  translation (what is cheap for trousers is not cheap for a television) — it is a model or a statistic
  over the corpus, i.e. inference, and that never belongs in the engine (research §1.2). And even an
  explicit "up to 1000 CZK" is grammar over the query text, not a dictionary hit — it belongs to the
  layer above the engine, even though it is deterministic.
- **Spatial intents** — "restaurants near Pardubice". A gazetteer "Pardubice → GPS" would formally be a
  dictionary lookup, but the result does not map onto any existing primitive (spatial constraints are
  issue #23, unimplemented) and "near" → a permitted perimeter is again intent parsing. Once #23 exists
  and the gazetteer is in the dictionary, a sibling annotation may come into being — not an extension
  of this one.

Both are the job of the **client-side AST layer above the engine**, with model intelligence from Sage
as a fail-open service that translates a recognized intent into ordinary constraints — `priceBetween`
today, spatial after #23. The boundary and its open ends are recorded in Q18.

The sponsor also raised a control question one floor up: is this too not the job of the superordinate
application? The test reads: **does the application have any way of finding it out without cooperating
with the database?** It does not. Recognizing "bosch = brand PK 123" rests on three things that exist
only in the engine:

1. **A dictionary of surface forms derived from the data just indexed** — it changes with every write.
   The precedent for why it has to agree with the index is Typesense `dynamic_query`: it binds
   `{brand}` only to values actually indexed in the given field, not to an external list (research §8,
   the VK block on the client/engine split).
2. **The analysis chain** — "Bosch", "bosch" and "BOSCH" all have to pass through the same
   normalisation as the index, otherwise exactly the "smart client" drift from research §1.2 arises.
3. **Impact** — "how many results will remain after applying the facet" is a bitmap intersection over
   the index; outside the engine it does not exist.

The application could only get around it in two ways and both are known traps: replicate the dictionary
into middleware (a cache with invalidation — literally the pain that issue #12 enumerates as the reason
for its existence), or ask the database separately (N round trips per reference type per query —
against the principle from §10.2). The dividing line can therefore be stated generally: **the engine
recognizes facts about the indexed data** (deterministic, derived from the state of the index, changing
with every write), **the application interprets the user's intents** (grammar or a model over text,
independent of the state of the index). "Bosch is a brand in this catalogue" is a fact; "cheap means up
to a thousand" is an intent. And the application nevertheless influences recognition — not at runtime
but through an artefact: the entity dictionary may be enriched by Sage (the alias "škodovka" → Škoda
arises by inference offline; the engine consumes the resulting deterministic dictionary, research
§4.6). The declaration side — which references enter recognition (the facetted ones) and from which
attributes of the target collections the surface forms are drawn in the matching locale — is handled by
`schema-design.md` §6.9 and question S13 there.

It is a separate extra result, not part of `featureVector()`, because it has a different consumer (a UI
offering a filter) and a different lifetime. The key to its cheapness is that the entity lookup has
already happened for the sake of relevance — the annotation merely reuses an intermediate result, it
computes nothing extra.

The response format should carry enough for the client to be able to generate a second query without
further knowledge: the surface form from the query, the reference type, the PK, and possibly the
expected impact. If the impact were to be returned as well (how many results would remain), that is a
bitmap intersection per candidate — cheap, but not free, and it should be optional through a parameter
after the model of `referenceSummary(COUNTS | IMPACT)` (`requirements/reference.md:75-100`).

### 8.4 Explain

Explain is `featureVector()` at a higher level of detail — a decomposition including which terms
expanded how and which field contributed. The natural shape is a level parameter after the model of
`queryTelemetry(TIMINGS | PLAN)`, where the documentation explicitly says "The two are levels rather
than flags" (`documentation/user/en/query/requirements/telemetry.md:18-31`):

```java
require(featureVector(FEATURES))   // only the feature values
require(featureVector(EXPLAIN))    // + the decomposition of term expansion and field contributions
```

That is cheaper than two constraints and matches the convention for require parameters: a scalar or an
enum with a documented default first, then nested constraints.

---

## 9. Sharing state between planner phases

This section is the technical core of the design. It describes how the score will flow from the filter
to the sorter and to the extra results, and the four places where it can be done wrong.

### 9.1 The precedent: how `priceNatural` gets the prices computed during filtering

The mechanism is two-phase and has to be understood exactly, because `relevance()` is to copy it.

**The interface on the filter formula.** Formulas that compute prices during filtering implement
`FilteredPriceRecordAccessor`
(`evita_engine/src/main/java/io/evitadb/core/query/algebra/price/`
`FilteredPriceRecordAccessor.java:42-54`) with a single method
`getFilteredPriceRecords(QueryExecutionContext)`.

**The planning phase — find where the values will be.** The order translator walks the **already built
but not yet computed** tree of filter formulas and looks for implementations of that interface in it:

```java
final Collection<FilteredPriceRecordAccessor> accessors = FormulaFinder.find(
    orderByVisitor.getFilteringFormula(), FilteredPriceRecordAccessor.class, LookUp.SHALLOW
);
```

(`evita_engine/src/main/java/io/evitadb/core/query/sort/price/translator/`
`PriceNaturalTranslator.java:81-83`; the visitor is
`evita_engine/src/main/java/io/evitadb/core/query/algebra/utils/visitor/FormulaFinder.java:69-73`.)
The *objects* found are passed to the sorter's constructor (`PriceNaturalTranslator.java:88-92`).

**The execution phase — pull the values out.** Only inside `sortAndSlice` is data pulled from the
accessors, with the context obtained from `sortingContext.queryContext()`
(`evita_engine/src/main/java/io/evitadb/core/query/sort/price/`
`FilteredPricesSorter.java:123-128`).

The split "in the plan find out *where*, at runtime pull out *what*" is the essence of the pattern.
`relevance()` is to have its own interface of the same shape — provisionally `FulltextScoreAccessor` —
and the same two-phase use.

### 9.2 The trap: SHALLOW does not descend, and prefetch inserts a wrapper

This is the most dangerous place in the whole design, because it fails silently and only under certain
conditions.

`FormulaFinder` in `LookUp.SHALLOW` mode **does not descend into a node it has itself matched** — the
recursion is only performed for `DEEP` (`FormulaFinder.java:131-147`, the condition at `:137`). At the
same time, with prefetch switched on, the engine wraps the filter tree in the formulas `SelectionFormula`
and `EntityFilteringFormula`, which themselves implement `FilteredPriceRecordAccessor` and delegate
inwards (`evita_engine/src/main/java/io/evitadb/core/query/algebra/prefetch/`
`SelectionFormula.java:72`, delegation `:243-257`; `EntityFilteringFormula.java:52`).

If prices did not handle this, `priceNatural` would stop working whenever prefetch was switched on. They
handle it precisely by having the wrappers implement the interface and pass it on.

**The consequence for fulltext: `FulltextScoreAccessor` has to be implemented not only by the fulltext
formula itself, but by every wrapper that can sit above it** — today `SelectionFormula` and
`EntityFilteringFormula`, with the same delegating logic. Without that, `relevance()` degrades to
`NoSorter` exactly and only when the planner chooses prefetch — i.e. typically for small results and in
tests with small data sets, where nobody notices until it falls over in production on a big catalogue.
This belongs in the test matrix from the start.

### 9.3 The prefetch path and what to do about it

The engine has two execution paths: the index one (bitmaps and formulas) and prefetch (loaded entities
sorted by a `Comparator`). One order constraint emits a sorter for each of them —
`PriceNaturalTranslator.java:107-110` returns `Stream.of(prefetchSorter, indexSorter)`, with
`PrefetchedRecordsSorter` switching itself off when no prefetch happened
(`evita_engine/src/main/java/io/evitadb/core/query/sort/generic/`
`PrefetchedRecordsSorter.java:104-106`).

For prices the prefetch path makes sense, because the price is part of the entity body and can be
requested through `addRequirementToPrefetch(PriceContent.respectingFilter())`
(`PriceNaturalTranslator.java:78`). **For relevance that does not hold — the score is not entity content
and there is no `EntityContentRequire` that would get it onto the entity.**

At the same time there is a property that prices do not have: **the score is keyed by primary key, not
by the entity body.** A sorter working over PKs therefore works the same on both paths, provided the
accessor gets through the wrappers (§9.2). The recommendation is therefore: `relevance()` emits a
**single** sorter, which does not switch itself off on prefetch, after the model of
`FilteredPricesSorter` (which likewise does not switch off). It is simpler than a pair of sorters and has
no weak spot.

A checkpoint to verify during implementation: the planner can compare the results of both paths
(`PlanningPolicy` / `VERIFY_ALTERNATIVE_INDEX_RESULTS`), so a differing order between the paths should
show up in tests, not at a customer.

### 9.4 Feature export: a side-output funnel, not reading from the sorter

For `featureVector()` reading the data from the sorter suggests itself — a precedent exists, the price
histogram does that
(`evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/histogram/`
`PriceHistogramTranslator.java:85-99`, through `ExtraResultPlanningVisitor.findSorter(Class)` at
`:791-800`).

**But that is not the primary mechanism and it is not suitable for feature export.** The primary pattern
is a *side-output funnel* on the filter formula and it looks like this:

1. The require constraint sets a flag on the request — `EvitaRequest.isPriceHistogramRequested()`
   (`evita_api/src/main/java/io/evitadb/api/requestResponse/EvitaRequest.java:1295-1300`).
2. **The filter planner** reads that flag at formula build time —
   `FilterByVisitor.isHistogramSideOutputApplicable()`
   (`evita_engine/src/main/java/io/evitadb/core/query/filter/FilterByVisitor.java:592-595`).
3. The formula is built with a per-record side output which is allocated only when needed
   (`evita_engine/src/main/java/io/evitadb/core/query/algebra/price/termination/`
   `LowestPriceTerminationFormula.java:127-130`, allocation `:472-474`, guard `:266-277`).
4. The producer picks the result up at fabrication time.

**A detail that looks like a bug if you were not there:** the flag is mixed into the formula's
`includeAdditionalHash()` (`LowestPriceTerminationFormula.java:90-91,129-130`). The reason is that
without it the cache could serve a query with export a payload computed for a query without export —
i.e. without the side output. The fulltext formula has to do the same.

Reading from the sorter has moreover two defects which matter in `featureVector()`: `findSorter` walks
**only the top-level elements** of the sorter chain, not nested ones (the JavaDoc at `:787-790` claims
the opposite, the implementation at `:791-800` does not), and the result field on
`FilteredPricesSorter` is mutable and `null` until the sorter has run (`FilteredPricesSorter.java:94,126`).

### 9.5 The phases of query execution

For orientation, because the order of the phases determines what is available when
(`evita_engine/src/main/java/io/evitadb/core/query/QueryPlan.java:254-405`):

| Order | Phase | Anchor |
|---|---|---|
| 1 | entity prefetch | `:265-272` |
| 2 | computing the filter formula, `totalRecordCount` | `:274-281` |
| 3 | sorting and paging → `this.primaryKeys` | `:284-299` |
| 4 | fabricating extra results | `:302` |
| 5 | fetching entity bodies | `:309-355` |
| 6 | constructing the response | `:326-380` |

The fulltext formula therefore computes in phase 2, the score is consumed in phase 3, feature export in
phase 4 — and at that moment the entity bodies do not exist yet.

One warning belongs with this table for the hybrid query, because **the fusion of two legs does not fit
naturally into any of those six phases**. Verification against an OpenSearch checkout (main, commit
`36edc05ac84`, 2026-08-12) shows where it was placed by someone who has already built it: on the seam
`SearchPhaseResultsProcessor`
(`server/src/main/java/org/opensearch/search/pipeline/SearchPhaseResultsProcessor.java:21`), i.e. **after
the end of evaluation and before fetching the documents** — at the moment when the identifiers and scores
are available but the bodies are not yet. The reason is obvious once stated: **fusion needs to see both
lists whole**, which is in principle impossible inside the scoring loop of a single leg.

Translated into our table: fusion is a **named step between phase 2 and phase 3** — after computing both
legs, before sorting and paging — not a part of phase 2 and not a lane of the composite. For RRF that is
cheap, because it works with ranks that can be derived from what phase 2 yields anyway, and it needs no
second pass. Had we chosen min-max normalisation of the scores instead, that step would first have to
compute the score range in each leg, i.e. walk the results twice — that is the concrete reason why §11
recommends RRF. It is at the same time the answer to the question of where that step physically lives,
which research §4.3 leaves undetermined when it speaks of "mapping back into a single
`orderBy(relevance())`".

### 9.6 The cost of sorting, which the research did not budget for

In the whole `sort` package **there is no top-N selection**. `FilteredPricesSorter` performs a full
`Arrays.sort` over all matches and only then slices out the page
(`FilteredPricesSorter.java:139,162-174`); `PrefetchedRecordsSorter` a full `entities.sort(comparator)`
(`PrefetchedRecordsSorter.java:127`). The only sorters that do not sort are those over pre-computed
indexes — and relevance can have no pre-computed index.

`relevance()` therefore inherits **O(N log N) over the whole match set for every page**, where N is the
number of matches. Research §4.3 budgeted phase 1 at ≤ 25 ms for 10⁶ candidates, but the sorting is not
in that budget. With full-set scoring (Z7 says the candidate set ≈ 85–95 % of the corpus) that is not
negligible.

At the same time it is an opportunity: **partial selection to `offset + limit` is natural for relevance**
— we want the first 20 out of a million, not a sorted million. The existing code does not do it, so it
would be a new precedent, not following one. It belongs in P1 as a measured item and in §14 as an open
question next to O2.

### 9.7 A plumbing requirement: the filter planner has to see the contents of `relevance()`

The recommendation of §4.2 (field weights in `relevance()`, principle 3 in §2.6) holds together only if
the filter formula knows the effective field weights at the time it is built — and those are written in
the query only in `orderBy`. After unification with query profiles (§6.5) the situation is simpler than
it was: **enrichment of the query by profiles happens before planning** (the server rewrites the query,
only then is it planned), so at the moment the filter formula is built the inserted children of
`relevance()` are already part of the query and the planner does not distinguish any profile names — it
reads only the resulting constraints. What remains is the binding "the filter planner reads `orderBy`",
which principle 2 (§2.6) explicitly permits; this section shows that it is also cheap to do. It is not a
matter of course and a reader does not assume it.

Mechanically it works and the route is short: the filter formula is created in
`QueryPlanner.createFilterFormula`
(`evita_engine/src/main/java/io/evitadb/core/query/QueryPlanner.java:154`), i.e. before `createSorter`
(`:168`) — but the whole query including `orderBy` is on the request from the start and
`QueryPlanningContext` exposes it (`getOrderBy()` is used by `QueryPlanner.java:573`). The exact
precedent for "the filter planner reads another part of the query" is already in the engine:
`FilterByVisitor.isHistogramSideOutputApplicable()`
(`evita_engine/src/main/java/io/evitadb/core/query/filter/FilterByVisitor.java:592-595`) reads a flag
derived from the `require` part.

Three things follow from this for the implementation:

1. Composing the effective weights (schema → children inserted from the profile → explicit children of
   the query, total replacement item by item) has to happen **once at filter planning time**, not per
   candidate. Distinguishing profile names does not concern planning — it happened already at query
   enrichment (§6.5, point 5: the cache key has to be derived from the query after enrichment, or carry
   the version of the profile definition).
2. **The effective field weights after composition** have to be mixed into the fulltext formula's
   `includeAdditionalHash()`, otherwise the cache would serve a query with different weights a result
   computed under different weights. It is the same reason why the side-output flag is mixed into the
   hash (§9.4).
3. The error "the profile does not exist" arises at query enrichment (the #12 mechanism), the error
   "`fieldWeight` aims at a field that is not searched" at filter planning, not at sort planning — the
   messages have to reflect that, so that the user does not look for the mistake in the wrong part of
   the query.

---

## 10. Suggest

### 10.1 The essence and two hard requirements

The suggester does not look for documents but for **terms**: it is a query over the dictionary, i.e.
over the same B+ tree of terms that carries the postings (research §4.6). Over a partially typed "černá
koz" it does three things: it expands the last word by prefix (a range scan of the dictionary) and by
typo (a Levenshtein automaton), scores the candidate terms and returns the top-M completions. The latency
target for P3: p99 ≤ 5 ms per keystroke including typo expansion. Entity suggestions ("brand Bosch") are
a different mechanism — the extra result of recognized facets (§8.3), not a dictionary suggest.

Two requirements are hard, not tunable (sponsor, 2026-08-14):

1. **Never offer a completion that yields no results in the user's context.** A suggestion passes only
   when "the completed words ∧ the candidate term ∧ the must-match filter" (price lists, currency,
   validity, locale, scope) is a non-empty intersection. Without that the suggester offers blind alleys —
   completions that return zero once entered. That intersection is at the same time the reason why no
   "shorter" entry point gets rid of the filter (§10.4).
2. **Scoring the suggestions has to be composable like the main search, not a hardwired cardinality.**
   The default signal for F1 is the cardinality of the postings after intersection with the filter — "how
   many results that completion really yields" — but the order of suggestions must be able to take into
   account popularised relevance as well: what users actually search for and click on. That is the same
   kind of signal as the boost channel (§7) and it heads into the same bin — a Sage-generated hot-swap
   data artefact (§6.5: data, not configuration), this time keyed by term or phrase instead of by the
   pair (query, PK). That is exactly how Algolia builds suggestions (Query Suggestions = an index fed
   from analytics). The composition of the signals is open — Q17.

### 10.2 The shape: a require in an ordinary query (S-A, DECIDED)

The sponsor decided (2026-08-14): suggest is a require constraint of an ordinary query and the session
contract is not extended by any method. A standalone suggester looks like this:

```java
query(
    collection("Product"),
    filterBy(
        entityLocaleEquals(CZECH),
        priceInPriceLists("basic"),
        priceInCurrency(CZK),
        priceValidInNow(),
        textMatches("černá koz")
    ),
    require(
        page(1, 0),          // suggestions only, no entity bodies
        suggest(10)
    )
)
```

Two arguments decided it. The first is a basic building block of evitaDB: **minimising round trips** —
computing in a single query as much of what the client needs for presentation as possible. A rich
autocomplete in an e-shop is not a list of words: the dropdown shows completion suggestions, a preview
of the first few products and the recognized facets all at once, and with the require shape that is one
round trip — it is enough to add `orderBy(relevance())` to the same query and:

```java
require(
    page(1, 5),                            // a preview of the first five products…
    entityFetch(attributeContent("name")),
    suggest(10),                           // …alongside ten completion suggestions…
    recognizedFacets()                     // …and the recognized facets (§8.3)
)
```

A dedicated method cannot do this by construction — for a rich dropdown it would force two round trips
per keystroke, the exact opposite of the principle evitaDB is built on. The second argument is empirical:
the survey of engines (§10.3) — practically nobody has a dedicated method at the API level and the only
one who had it abolished it.

### 10.3 How the others do it

Verified against local checkouts in answer to the sponsor's question of whether the other engines have a
separate method for suggest. The answer: **at the engine level almost nobody — a dedicated method is a
legacy of the library layer, not a pattern of products.**

- **Elasticsearch**: suggest is a section of the `_search` body (`SearchSourceBuilder.suggest(...)`,
  `server/src/main/java/org/elasticsearch/search/builder/SearchSourceBuilder.java:777`); a separate
  suggest API does not exist in today's rest-api-spec. Historically they did have a `_suggest` endpoint —
  in the 5.x line they deprecated it and subsequently removed it by consolidating into `_search`. Exactly
  the route "S-B → S-A", walked in production.
- **Solr**: `SuggestComponent` is an ordinary `SearchComponent` pluggable into the pipeline of a search
  request (`handler/component/SuggestComponent.java`); the typical `/suggest` handler is merely a
  configuration of the same pipeline with a different set of components, not a separate subsystem.
- **Typesense**: there is no suggest route in `core_api.cpp` — autocomplete IS an ordinary search (a
  prefix match on the last word is the default behaviour of a query).
- **Meilisearch**: the same, search-as-you-type is the product itself — the index routes are search,
  similar, facet_search…; a document suggest has no route of its own. A nuance: `facet_search` is a
  dedicated route for suggesting **facet values** — the only place where they did acquire a separate
  entry point, and it is a different problem from ours (our counterpart is the extra result of recognized
  facets, §8.3).
- **Vespa and Algolia**: they treat suggest as **data, not API** — a separate document type/index of
  suggestions (with Algolia fed from analytics as the Query Suggestions product), queried by the ordinary
  search API.
- **Lucene**: the only genuinely separate API — the suggest module with its own structures (the FST
  `Lookup`, `AnalyzingInfixSuggester`). But that is the library layer; this is precisely where the
  intuition "suggest = a special method" comes from, an intuition that the engines built on Lucene (ES,
  Solr) did not take over into their public APIs.

### 10.4 The rejected alternative S-B: a dedicated session method

The shape considered was `session.suggest("Product", "černá koz", suggestionSettings(CZECH, 10),
filterBy(...))` — a new method on `EvitaSessionContract`, a new RPC in `GrpcEvitaSessionAPI.proto`, an
implementation in `EvitaSessionService`, a client stub, a GraphQL field and a REST endpoint; roughly as
much work as two new constraints, with a BWC commitment of its own. **Rejected for three reasons:** (1) it
does not get rid of the must-match filter — suggestions are "candidate terms ∧ filter" (§10.1, requirement
1), so the method has to accept a `filterBy` and it is the same query pipeline with a smaller set of
requires, not a qualitatively shorter route; the only real saving is the overhead around the pipeline,
which nobody has measured; (2) for a rich dropdown it forces two round trips per keystroke (§10.2); (3) no
engine does it that way at the API level and the only one that had it abolished it (§10.3).

When to come back to it: if P3 measures the pipeline overhead outside the dictionary expansion itself as
fatal against the 5 ms budget. Even then it would share the same computer and would be added **alongside**
the require shape as an optimisation, not instead of it — and the allow-list of the session interceptor
would not have to be dealt with
(`evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/`
`grpc/services/interceptors/ServerSessionInterceptor.java:83-88` enumerates the methods that do **not**
need a session; suggest does need one, so it is covered by the default).

---

## 11. The vector leg and RRF (sketch)

> This section is a sketch, not a design. It is not based on verifying the vector part in the repository
> nor on studying issue #23; it serves to keep the DSL in F1 from closing in a way that would prevent
> hybrid from being added later without a breaking change.

The good news is that the mechanism from §9 generalises to vectors without a new principle.

**The vector leg is a filter constraint**, because it produces candidates:

```java
filterBy(
    entityLocaleEquals(CZECH),
    vectorSimilarity("descriptionEmbedding", queryVector, 200)
)
```

Just like `textMatches`, its formula implements a score accessor. That gets us to hybrid without new
mechanics:

```java
query(
    collection("Article"),
    filterBy(
        entityLocaleEquals(CZECH),
        or(
            textMatches("jak zateplit půdu"),
            vectorSimilarity("bodyEmbedding", queryVector, 200)
        )
    ),
    orderBy(relevance()),
    require(page(1, 20), profile("hybrid-rrf"))   // the query profile (#12) inserts the fusion policy
)
```

`relevance()` finds **all** accessors in the tree through `FormulaFinder`, not just one, and the fusion
policy is determined by the rank profile. Three notes on what must not be confused:

1. **RRF works with ranks, not with scores** — therefore the fusion has to happen in the sorter, once
   both legs have yielded ordered lists. It is not a merge on the filter side and it cannot be packed
   into a single lane of the feature vector without first sorting each leg separately. Verification
   against an Elasticsearch checkout (main, commit `9a100e2d0e41`, 2026-08-13) confirms this in its
   strongest form: the whole fusion there is a single line
   (`x-pack/plugin/rank-rrf/…/RRFRetrieverBuilder.java:223`) into which **the scores of the individual
   legs do not enter at all** — only the ranks, shifted by a constant and weighted by the leg's weight.
   For us this is not a preference but the only correct choice: a 64-bit lexicographic composite cannot
   be compared with cosine similarity even after normalisation, because it has no scale. Where that step
   physically lives with respect to the query execution phases is in §9.5.
2. **The fusion policy is part of the profile**, not a separate constraint. Were it separate, a query
   could select the profile and the fusion inconsistently.
3. **An explicit fusion argument** (`relevance(fusion(RRF, 60))`) is an alternative for the case where a
   need arises to change the fusion without changing the profile. The container shape of `relevance()`
   from §5.1 allows that to be added later without a breaking change — which is the main reason why the
   container is recommended already now.

**A precedent for a composed shape of the API.** Verification against an Elasticsearch checkout (main,
commit `9a100e2d0e41`, 2026-08-13) shows that the problem "the result is composed from several sources"
has a named answer in the field, and it is worth knowing before hybrid is delivered. The package
`server/src/main/java/org/elasticsearch/search/retriever/` introduces **retrievers**, i.e. tree-composable
sources of results: `StandardRetrieverBuilder` for an ordinary query, `KnnRetrieverBuilder` for vector
search, `RescorerRetrieverBuilder` for rescoring as a node of the tree, and the abstract
`CompoundRetrieverBuilder` for nodes merging several inputs. RRF is one of those compound nodes and
carries its own `rank_window_size`, i.e. how many results from each leg enter the fusion; curation rules
fit into the same tree (`PinnedRetrieverBuilder`).

What is essential, though, is the way they got there, and its cost. Elasticsearch first had a **flat query
body** with a growing pile of mutually interacting options, added retrievers only years later, and **now
carries both**. Two concrete things follow for us. First, an `or()` around two legs (above) works for two,
but says nothing about how many candidates are taken from which leg and in what order they are composed —
and with a third leg it stops being sufficient altogether. Second, the container shape of `relevance()`
from §5.1 is exactly the insurance that will allow a fusion node to be added later without a breaking
change, and it is the main reason why it is recommended already now. I do not recommend copying
retrievers — evitaQL has its own idiom of containers and already has tree structure in `filterBy` — but I
do recommend that the question "how is a result composed from three sources written" gets an answer at the
latest together with F2, not only once the flat form is public.

What remains open and does not belong in F1: how "take the top-K from each leg separately" is written
(today hinted at by the third argument of `vectorSimilarity`), and whether `or` is the right container for
joining two legs, or whether hybrid needs its own.

---

## 12. Impact on the external APIs

The extent of the impact differs according to whether a **constraint** or a **response object** is being
added.

**New constraints are almost free.** gRPC sends queries as EvitaQL strings, so the proto file does not
change (`.claude/skills/new-constraint/SKILL.md:82`). GraphQL and REST schemas are generated from the
constraint descriptors, so a leaf constraint requires no manual code (`SKILL.md:81`). Manual work is
needed only for containers with children — and both `textMatches` and `relevance()` are containers per the
recommendation, so it cannot be avoided.

The name in the generated APIs is derived by the rule from §2.1 (`ConstraintSchemaBuilder.java:97-99`).
For the recommended variants that means: `textMatches` as a generic constraint without a classifier
appears as `textMatches`, `relevance` as `relevance`. No classifier injection takes place.

**New response objects are expensive.** Every extra result from §8 needs its own class implementing
`EvitaResponseExtraResult`, and the map of extra results in the response is keyed by the DTO class
(`evita_api/src/main/java/io/evitadb/api/requestResponse/EvitaResponse.java:126-138`) — so three outputs
mean three classes, not one with flags. And for each of them: a message in the proto and a converter for
gRPC, a serializer and an OpenAPI schema for REST, a descriptor and a data fetcher for GraphQL. The
procedure is in `.claude/skills/new-external-api-object/SKILL.md`.

One item that is easy to forget: **a Kryo serializer for every new constraint** is registered in
`QuerySerializationKryoConfigurer` and **has to be appended at the end** — inserting one in the middle
shifts the class IDs and breaks deserialization of stored data
(`.claude/skills/new-constraint/SKILL.md:78`).

---

## 13. Scope of work

An estimate per the checklist `.claude/skills/new-constraint/LAYERS.md` (25 items across six layers). The
numbers are **rough and not backed by measurement** — they serve to compare the items against each other
and to identify the long poles, not to plan capacity.

| Item | New constraints | New formula | New extra result | Note |
|---|---|---|---|---|
| `textMatches` + `inField` | 2 | yes | no | long pole: the formula and the index |
| `relevance` + 4 parametric children | 5 | no | no | + sorter, accessor, delegation in the wrappers |
| `featureVector` | 1 | no | yes | + plumbing the page into the execution context |
| `recognizedFacets` | 1 | no | yes | reuses the entity lookup from relevance |
| `suggest` (require shape S-A, §10.2) | 1 | no | yes | computer shared with a possible S-B optimisation |
| `vectorSimilarity` (F2) | 1 | yes | no | outside the scope of this document |

Where the work actually is:

- **The formula and the index** (`textMatches`) are the overwhelming majority of the effort and this
  document does not address them — they are the content of prototypes P1 and P2. The constraint layer
  above them is thin.
- **The accessor and its delegation in the wrappers** (§9.2) is a small piece of code with a large impact,
  which has to be covered by a test for both execution paths from the start.
- **The external API for three new extra results** is per §12 probably a bigger item than the constraints
  themselves, and it is easy to underestimate, because it consists of many small steps.
- **Documentation** is a non-trivial item in this language: every constraint needs a page with an
  `evitaql-syntax` block, a description of the arguments, prose, a working `.evitaql` example and a
  generated result block. For `relevance()` there is in addition the warning about composing with other
  ordering constraints (§5.4) and the recorded consequence of a missing fulltext filter (§5.2).

The recommended delivery order of the constraints, so that measurement can start as early as possible:
`textMatches` in variant V1 (a flat leaf) → `relevance()` without parameters → measurement P1 → building
out to V3 and R2 before anything is released. A shortened shape is fine while it stays inside; once it is
published it stops being a shortcut and becomes a commitment.

---

## 14. Open questions

Questions arising from this design. References to O1–O10 point into §6 of the research.

**Q1 — the name of the fulltext constraint (develops O4).** `dataMatches` (recommended) or `textMatches`
vs. `attributeMatches` (the research's working name). The `attribute` prefix does not hold up once
searchable associated data is delivered (O6), which is a precondition for the CMS profile; between
`dataMatches` and `textMatches` it is taste that decides, not a technical argument (§4.1). **It has to be
settled before F1 ships** — renaming after release is a breaking change in four APIs.

**Q2 — where the field weights live (DECIDED by principle 3, §2.6; developed O4).** The weight override
belongs in `orderBy` as `fieldWeight(...)` children of the `relevance()` constraint (V3, §4.2): weights
affect relevance, not the match set. The computational argument that previously spoke for the filter
(collapsing the per-field impacts in a single pass) still holds, but it is solved by plumbing — the filter
planner reads `orderBy` (§9.7), which principle 2 explicitly permits. A `fieldWeight` on a field that is
not searched in the query is an error at planning time. It remains to verify during implementation that
reading `orderBy` holds for all the alternative plans the planner considers.

**Q3 — `relevance()` without a fulltext filter (closes O4).** An error at planning time is recommended,
not silent degradation (§5.2). A counter-argument that has to be considered: if a client generated
`orderBy(relevance())` unconditionally and the fulltext filter conditionally (which is a likely shape of
generated code), a hard error forces it into branching. Decide with regard to the real shape of the client
integration.

**Q4 — `textMatches` and `userFilter` (new).** A prohibition inside `userFilter` is recommended (§4.5).
What is open is whether the product will want to offer "cancel the search, keep the filters" with a count
prediction — that would require a fourth carrier family and new rows in the peeling matrix
(`filtering/behavioral.md:161-166`). To be found out from the product brief, not from the code.

**Q5 — the cost of sorting (develops O2).** There is no top-N selection in the `sort` package;
`relevance()` inherits a full sort of the match set for every page (§9.6). Measure in P1 as a separate item
alongside the 25 ms budget of phase 1 and decide whether partial selection to `offset + limit` will be
introduced for relevance. Related to the choice of K for phase 2 (O2), because both are about how many
candidates have to be ordered in full.

**Q6 — versioning and limits of boost tables (closes the rest of O8).** Does the query name a version of
the table, or an alias to the current one? What is the size cap of a table and what happens when it is
exceeded? A version in the query gives reproducibility, an alias gives freshness without touching the
client — probably both, with the alias as the default.

**Q7 — the supported subset of EvitaEL for a rank profile (new, related to O1).** If variant P-C (§6.2)
is delivered, it is necessary to delimit what the compiler accepts and reject the rest with an exception
after the model of `HistogramValueDescriptorFactory`. Part of it is the decision whether to add `exp` and
some conditional construct (§3.4). It does not belong in F1, but the decision affects the shape of the
feature vector, which is being designed now.

**Q8 — the leniency of the expression parser (new, small but real).** `ExpressionFactory.parse` calls
`expression()` instead of `root()` (`ExpressionFactory.java:64`), so text after the first valid expression
is silently discarded. For schema-configuration use that is marginal; for a hand-written scoring function
it is a defect that has to be fixed before profiles start being written by hand.

**Q9 — the shape of the suggest API (DECIDED by the sponsor 2026-08-14: S-A, a require in an ordinary
query).** Two arguments decided it (§10.2, §10.3): the principle of minimising round trips — a rich
autocomplete (suggestions + a product preview + recognized facets) is one query with the require shape and
two with a dedicated method — and the survey of engines: practically nobody has a dedicated method at the
API level (ES consolidated suggest into `_search` and removed the standalone `_suggest`; Typesense and
Meilisearch suggest through ordinary search; Vespa and Algolia solve it with data, not API; only the
library layer of Lucene has a separate API). P3 still measures the share of the pipeline overhead in the 5
ms budget — but the result only decides whether S-B is ever added as an optimisation alongside the require
shape, not the shape of the API.

**Q10 — hybrid and RRF in the DSL (develops O5/O7, sketch in §11).** Is `or` the right container for
joining the text and vector legs? Where is K per leg written? To be opened only after P6, but keep the
container shape of `relevance()` (§5.1) so that the fusion argument can be added without a breaking change.

**Q11 — master/variant grouping (DECIDED: outside fulltext, belongs under issue #17).** The analysis of
the e-shop layer (internal, §3.4 and §5.5) describes a real production
requirement for which this design has no primitive. Today it is carried entirely by the application: the
query sent to the engine is **always for page 1 with a size of 1000**, the returned hits pass through one
of **three interchangeable grouping rules** which fold the variants under a master, inside a group the
variants are ordered by an editorially set priority, and **the caller's paging is applied only to the
result of the grouping**. The response therefore carries two mutually inconsistent totals: the number of
hits before grouping and the number of groups after it, so the paging UI has to know which of them it may
use. The decision has been made: grouping is the intent of the **general issue [#17 "Entity grouping by
selected attribute / reference"](https://github.com/FgForrest/evitaDB/issues/17)** (open since February
2023, describing exactly this mechanism including paging and sorting after grouping) and it **will not be
included** in fulltext search — this design will not get a grouping primitive, not even once #17 comes into
being, because it will then be an orthogonal capability of the engine composable with any query. What
remains of the decision for this document: until #17 exists, grouping is carried by the client, and a hard
requirement holds for the engine — to return a page deep enough with a consistent count, because today's
cap of 1000 is the source of both defects at once. Related to Q5 (the cost of sorting): the depth of the
page requires it directly.

**Q12 — the curated promo layer and the shape of the response (new, shared with P7 §9.3).** The existing
solution returns promoted documents as a **separate group at the beginning of the result** and subtracts
them from the main list so that they do not appear twice. The question for this document is not where that
capability lives — that is elaborated by `p7-rank-profiles-and-boost-channel.md` §9.3 — but what follows
from its decision for the query API: **does the engine return one set of results, or two separate groups?**
One set means that pinning shows up only as an order and the client cannot tell why a document is at the
top. Two groups mean a new shape of the response alongside `page()` — i.e. a new extra result or a new page
wrapper — and with it the question of how the total count is computed and how paging works across the group
boundary. To be decided before F1, because the shape of the response is a breaking change in all external
APIs once released.

**Q13 — phrases in quotes: a filter, or a boost? (new).** Verification against an OpenSearch checkout
(main, commit `36edc05ac84`, 2026-08-12) shows on the field type `match_only_text` that there a phrase
match is **a filter with zero contribution to the score**: a cheap approximation from position-less
postings is verified by re-analysing the stored value and the result is a `ConstantScoreScorer`
(`server/src/main/java/org/opensearch/index/query/SourceFieldMatchQuery.java:125`). Our design by contrast
treats proximity as a **ranking lane over the top-K** (P4). These are different contracts with different
costs: if a phrase is a filter, top-K is in principle insufficient — a document with the phrase that ended
up at position K+1 in phase 1 would fall out of the result even though it satisfies the condition. For an
e-shop a boost is defensible (a phrase should lift a result, not prune it), for the CMS profile, where a
user is looking for a specific formulation in an article, the expectation is rather a filter. The impact on
this document is direct, because it concerns the **semantics of the `textMatches` argument**: do quotes
inside the text mean anything at all, is a phrase a child of the constraint of its own, and may the
behaviour differ by profile? To be decided before F1 together with P4 — it changes what P4 actually
measures.

**Q14 — a minimum relevance threshold and score normalisation (new, from principle 3 in §2.6).** If
`textMatches` were to get a minimum relevance threshold argument, field weights would start affecting the
match set and the Q2 decision would be reversed. We would not be the first — verification against a
Meilisearch checkout (main, commit `594f0e59d`, 2026-08-14) shows that Meilisearch has exactly this:
`rankingScoreThreshold` filters out documents below a normalised score of 0–1 and that score is **a
normalised lexicographic composite of the same family as ours** — every ranking rule yields a pair
`rank/max_rank` with the maximum known per query and `Rank::merge` composes them positionally, by
multiplying by the maximum of the inner rule (`crates/milli/src/score_details.rs:515-549`); the threshold is
evaluated directly during the bucket sort and removes candidates below it from the universe
(`crates/milli/src/search/new/bucket_sort.rs:219-227`). From this follows a **correction of this question's
earlier argument**: normalising a bounded cascade requires no second pass over the results — the score is
absolute per document, and because dividing by a per-query constant is monotone, the threshold can be
recomputed once into the raw scale of the composite and filtered with a single `long` comparison per
candidate. A second pass is needed only by min-max normalisation over the *observed* scores (which afflicts
the Solr hack `scale(query(...))` + `frange` and the min-max fusion rejected in §9.5). The condition is that
all the lanes stay bounded per query — today's design satisfies that (the impact is a quantised byte,
research §4.3) and it is one more reason to stay with that property. The other engines: ES/OS have
`min_score` over the raw BM25 score (the documentation discourages it — a raw scale is not comparable
between queries) and for knn a `similarity` threshold; Vespa has `rank-score-drop-limit` per rank profile,
again over the profile's raw scale (`config-model/src/main/java/com/yahoo/schema/RankProfile.java`,
checkout `780f10016ed`); Lucene deliberately removed queryNorm in version seven. The real costs of a
threshold are therefore not computational but semantic: (a) the meaning of the number changes with every
change of the profile, the order of the lanes or the field weights — Meilisearch can afford an absolute
threshold because the order of its rules is an index setting and changes rarely, whereas for us the profile
is per query, so a stored threshold without a stored profile is nonsense and the API would have to tie them
together; (b) the semantics of the total count changes — the count before the threshold, or after it?
Meilisearch switches to exhaustive counting because of that when a threshold is present
(`bucket_sort.rs:188`); (c) for the most common intent "cut off the junk at the end" a **structural
threshold** is more comprehensible — a minimum coverage of the query's words (the mainstream shape: ES/Solr
`minimum_should_match`), which for us is directly lane 1 and could be expressed by a cheap argument of
`textMatches` without any normalisation. An essential reservation of the sponsor belongs with word coverage:
**an absolute count is a defective shape** — "at least 4 words" makes no sense for a one-word query.
`minimum_should_match` therefore almost never uses an absolute number; its spec is evaluated **relative to
the number of words of the particular query** and can express a percentage (`75%`), "all but N" (a negative
number) and a conditional ladder — `3<90%` means "up to three words all of them, above three 90 %"
(`server/src/main/java/org/elasticsearch/common/lucene/search/Queries.java:174-207`, checkout
`9a100e2d0e41`). But it is a string mini-grammar that the engine would parse — exactly what §4.1 forbids —
so for us the shape would have to be typed (an enumeration ALL / ALL_BUT(n) / PERCENT(p), possibly a ladder
as children). The second family of solutions is **adaptive degradation** instead of a static predicate:
Meilisearch `matchingStrategy=last` removes words from the end until it finds something, Typesense
`drop_tokens_threshold` starts dropping words only when there are fewer results than the threshold
(`include/collection.h:90,220`) — the knob is on the number of results, not on the number of words, so it
scales by itself. A second reservation of the sponsor belongs with adaptive degradation: "remove from the
end" rests on a linguistic assumption about word order. Mechanically RTL is no problem (strings carry
logical order, direction is a matter of display), but the assumption "the last word is the least important"
falls over even in Czech — in "černá kožená bunda" ("black leather jacket") the head noun is last, so
dropping from the end throws away "jacket" and leaves "black leather". Both engines have already hit this:
Typesense has the direction configurable (`drop_tokens_mode`: `right_to_left` default / `left_to_right` /
`both_sides`, `include/index.h:114-118`) and Meilisearch added positional independence —
`matchingStrategy=frequency` drops words by descending document frequency, i.e. the least selective first
(`crates/milli/src/search/new/query_graph.rs:303-332`). For CJK the unit is a token from dictionary
segmentation (charabia: jieba/lindera) — a positional heuristic means nothing there, a frequency-based one
still works; n-gram tokenization makes nonsense of the whole "word count" family (even ES warns against
`minimum_should_match` over n-grams). Were adaptive degradation ever to be designed for us, it is defined
**by selectivity, not by position**: drop the term with the largest postings cardinality first — the engine
has that for free in the dictionary (the suggester already counts on it, §10) and the shape is immune to
RTL, to word-order typology and to CJK with real segmentation; positional modes at most as a per-language
option. The concrete shape of adaptive relaxation for evitaDB — and why a threshold rather than
unconditional degradation makes sense for us — is elaborated by Q16.
A threshold is not proposed for F1; should the product want one, the order of preference is: a threshold on
word coverage → a normalised threshold tied to the profile → never a threshold over the raw composite. For
the vector leg (F2) a threshold on cosine similarity is standard with a natural scale and belongs in
`vectorSimilarity`, not in `textMatches`.

**Q15 — the name of the `profile` child vs. the query profiles from issue #12 (DISSOLVED by unification,
2026-08-14).** The sponsor decided the direction: a rank profile is not a separate mechanism but an
ordinary query profile from issue #12, whose rules `{location, operation, childConstraint}` insert children
into `relevance()` (§6.5). There is therefore only one notion of "profile", the name clash disappears and
the child `profile(...)` inside `relevance()` is abolished — the profile is chosen in
`require(profile(...))` by the #12 mechanism. The replacement open item: the questions that unification
poses for the design of #12 (the vocabulary of operations and collision semantics item by item, conditional
application by `location`, a collection default + a reserved "no profile", a cache from the query after
enrichment — the list is in §6.5) are to be settled **in the discussion on issue #12 and implemented
concurrently with F1**.

**Q16 — adaptive relaxation of the fulltext condition in evitaDB (PRINCIPLE DECIDED by the sponsor
2026-08-14; follows on from Q14).** The sponsor confirmed the principle with the words "here we will
differentiate ourselves — it makes far more sense for our use case"; what remains open are only the items at
the end (the name, prefetch, placement in the phases). **The decision is recorded** in
[`../README.md`](../README.md) ("Decisions taken", 2026-08-14) together with the rejected variants;
this paragraph carries the detail behind it. **Unconditional degradation after the model of Meilisearch
does not suit evitaDB**:
Meilisearch always admits the looser matches into the result and relies on paging to cut them off — but
evitaDB always computes **the full match set**, because facets, histograms and totals stand on it.
Unconditional loosening would therefore, in the ordinary case (there are enough strict matches), poison the
facet counts and histograms with a set the user did not ask for; paging is not a trimming mechanism for us.
The right shape is **a targeted lower limit N** after the model of Typesense: relaxation kicks in only when
the strict set has fewer than N candidates — a parachute against an empty page, not a permanent loosening.
The key finding that makes relaxation cheap for us: with frequency-ordered dropping (Q14) the **relaxation
levels are a by-product of the standard evaluation of the conjunction**. The intersection of the postings is
computed from the most selective term anyway for performance reasons, and the intermediate results of that
pass — the rarest term alone, ∧ the second rarest, ∧ the third… — form exactly a ladder of levels from the
loosest to the strictest, because dropping a conjunct only enlarges the set. Adaptivity is then merely a
series of cardinality checks on the intermediate bitmaps, no repeated searching. Three rules without which
it will be silently wrong: (a) **the relaxation level is decided once** against the full filter and is a
constant for the whole query — the peeling of `userFilter` for histograms and facets (§2.2) must not
recompute the level, otherwise every computation describes a different universe; (b) terms with empty
postings after expansion are excluded **before** the ladder is built — they have zero frequency, so in
frequency order they would survive to the very end and all the levels would be empty; (c) relaxation has to
be **visible in the response** (an extra result: the chosen level, the dropped terms, the number of strict
matches) — it is degradation in the sense of §5.2 and the client builds the message "we have no exact
matches, we are showing similar ones" from it. Facets, histograms and the total count are computed over the
final chosen level — one truth for the whole response; stricter matches are kept at the top by lane 1 of the
composite without any further mechanics.

On the limit N the question of a default vs. mandatoriness came up and the answer follows from principle 1
(§2.6): **the absence of the constraint means no relaxation** (today's strict semantics — relaxation changes
the match set and that must not happen silently), and **if the constraint is stated, N is mandatory**. A
sensible default simply does not exist — two equally defensible candidates compete (N = 1 "loosen only when
it is completely empty" vs. N = the page size "fill the page"), and the existence of two sensible defaults is
proof that there is no single sensible default. The objection "the client will repeat that in every query" is
answered exactly by the query profiles from issue #12 (§6.5) — the "b2c" profile inserts the constraint
server-side and the engine stays explicit. Placement: the constraint changes the match set, so it belongs as a
child of `textMatches` in the filter, not in `relevance()` — the same dividing line as in principle 3.

A contrast with the Lucene family (at the sponsor's request): **engine-level adaptive relaxation does not
exist there.** The Lucene world has a static policy declared up front and related to the length of the query —
the `minimum_should_match` ladder (Solr eDisMax `mm`, `SolrPluginUtils.setMinShouldMatch`,
`solr/core/src/java/org/apache/solr/util/SolrPluginUtils.java:582`; `mm.autoRelax` only handles compensation
for clauses dropped by analysis, not feedback on the number of results) — or an OR with coverage scoring,
which however computes the union **unconditionally**, so aggregations/facets describe the whole loosened
universe. The feedback loop "too few results → loosen" is done in the Lucene world **above the engine, in the
application** (a repeated query). The reason is architectural: doc-at-a-time evaluation — the conjunction does
lead with the rarest term (`ConjunctionDISI` orders the iterators by cost,
`lucene/core/src/java/org/apache/lucene/search/ConjunctionDISI.java:132`, checkout `13796f80e`), but it
streams document by document straight into the collectors and **materialises no intermediate sets** — the
number of matches is known only after the pass finishes, so "check and loosen" means the whole evaluation
again (in a distributed ES additionally across shards). A free ladder is therefore an advantage of the
set-at-a-time model with bitmaps that evitaDB has — and even Typesense, which does have the loop, searches for
each rung again (`index.cpp:4062`); we would have it as an intermediate result.

What remains open: the name of the constraint, the interaction with the prefetch path and whether it goes into
F1 at all — probably not, to be decided after the P1 measurement.

**Q17 — the scoring composition of suggest (new, from the sponsor's review, §10.1).** The default signal is
the cardinality of the postings after intersection with the must-match filter; popularised relevance (what
users actually search for and click on) is to come as a Sage-generated artefact keyed by term or phrase — the
same hot-swap data channel as the boost tables (§7), a different key. Open: the exact composition of the two
signals (multiplication, or popularity as the first criterion?), whether it should be controllable by a
profile (#12), and what to do before analytics are deployed (a cold start = pure cardinality). To be measured
and decided in P3.

**Q18 — the boundary of intent recognition: engine vs. the client layer (new, develops O9 of the research).**
The engine annotates only deterministic dictionary hits mapped onto existing primitives (`recognizedFacets`,
§8.3). Interval intents ("cheap trousers", but also the deterministic "up to 1000 CZK" — that is grammar over
text, not a dictionary) and spatial intents ("restaurants near Pardubice" — geocoding + converting "near" into
a perimeter + a spatial query, which is waiting for issue #23) belong to the layer above the engine: a
client-side AST with model intelligence from Sage as a fail-open service (research §1.2/§1.3, O9). Open: where
exactly that layer lives (a library in the client application, or a Sage endpoint?), whether to share its
deterministic part (numeric intervals, currencies) as a common library across clients, and whether, after
spatial (#23) ships, to promote a gazetteer of places into the engine's dictionary as a sibling annotation
alongside `recognizedFacets`. To be decided outside F1; for F1 only the narrow contract of `recognizedFacets`
applies.
