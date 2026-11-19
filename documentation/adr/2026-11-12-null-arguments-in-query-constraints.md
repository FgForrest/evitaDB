---
title: Null arguments in query constraints follow one rule set in Java, EvitaQL and REST
date: 2026-11-12
updated: 2026-11-19 09:29
status: accepted
kind: fix
issues: [1943, 2013]
prs: [1965, 2016]
areas: [evita_query, evita_query/src/main/java/io/evitadb/api/query/parser, evita_external_api/evita_external_api_core, evita_engine/src/main/java/io/evitadb/core/query]
supersedes: []
superseded-by: []
relates: []
---

# Null arguments in query constraints follow one rule set in Java, EvitaQL and REST

A `null` passed to a query constraint now means the same thing whether the query is built with the Java factories
in `QueryConstraints`, written in EvitaQL with bound parameters, or sent as REST JSON. **A value** (what the user
typed or picked) may be undefined: the constraint is then left out. **A name** (attribute, reference, entity type,
output name, ...) is set by the application: a null or blank name is an error. **An enum** setting falls back to its
documented default, and is an error only where the constraint has none. Lists apply the same rules to their items.
A single value is not a list: `entityPrimaryKeyInSet(id)` with an undefined `id` leaves the filter out, in Java and in
EvitaQL alike, while an explicit list whose every item is undefined matches nothing.

## Why

Before this change the same `null` was handled four different ways, and the worst of them were silent:

- **A filter or ordering disappeared.** A bound EvitaQL list such as `["A", null]` kept the null inside
  `attributeInSet`; the constraint became non-applicable and query normalization dropped the whole `filterBy`, so
  the query returned every entity. REST did the same with a JSON list (`["A", null]` returned 1,000 of 1,000
  products although no product had code `A`). In Java, `attributeSetExact("code", "A", null)` lost the ordering.
- **A missing name widened the query.** `referenceHaving(null, ...)` left the filter out, `referenceContent(null)`
  fetched every reference instead of one, `hierarchyWithinRoot(null, ...)` switched to the queried entity's own
  hierarchy.
- **EvitaQL refused what Java accepted.** Every bound null was a syntax error, even `attributeEquals(?, ?)` with an
  undefined value, which the Java factory simply leaves out.
- **Internal errors.** A null as the first item of a bound list, `priceDiscount((String[]) null)` and
  `priceContentRespectingFilter` ended in raw `NullPointerException`s or `GenericEvitaInternalError`.

The distinction between values and names is what makes one rule possible: a search form legitimately leaves fields
empty, while an empty attribute name is always a bug in the application, and hiding it widens the result.

### Previous state

The Java factories were written to support conditional query building ("Null Handling" in the `QueryConstraints`
JavaDoc): most of them returned `null` for any null argument, names included. EvitaQL constructed constraints
directly in its visitors, not through the factories, and refused every null parameter. REST and GraphQL build
constraints from JSON through the shared `ConstraintResolver`, which invoked the constraint constructors directly
and copied list items unchanged, so no factory rule applied there.

## Options considered

### Rules by the meaning of the argument, applied to every entry point (chosen)

Values may be undefined, names must be present, enums use their documented default. EvitaQL and REST build their
constraints through the same `QueryConstraints` factories, so they cannot drift from Java.

- **Pros:** undefined user input keeps working for conditional queries; application bugs surface as errors instead
  of wider results; one rule set for three entry points.
- **Cons:** breaking for Java callers that relied on a null name to leave a constraint out, and for EvitaQL literals
  with blank names (listed under Consequences).

### Keep omitting constraints with a null name (declined)

- **Pros:** no breaking change for existing Java callers.
- **Rejected because:** it silently widens filters, loses orderings and fetches every reference instead of one; the
  caller never learns about the bug.

### Refuse every null item in a bound EvitaQL list (declined)

- **Rejected because:** values come from the user side and an undefined value is legitimate input; the Java
  factories accept it, so clients would need different list preparation for Java and EvitaQL.

### A null filter list matches nothing (declined)

- **Rejected because:** "no list given" means the user did not filter. A null filter list leaves the filter out.

### A filter list of only nulls leaves the filter out (declined)

- **Rejected because:** the caller sent a list and every item of it is undefined, so nothing matches. Leaving the
  filter out would widen the result (maintainer's decision). Reconsidered and declined again in #2013, where the
  real complaint was a single undefined value, not a list (see below).

### A null name list throws (declined)

- **Rejected because:** the documented `referenceContent()` means all references, so a null array means "no names
  given" and takes the empty list's meaning. A null or blank *item* of a name list stays an error.

### Every null enum throws (declined)

- **Rejected because:** 188 factory overloads already document a default for their enum; breaking them gains
  nothing. Only `inScope`, the special value of `attributeIs` and the mode of `priceContent` have none and throw.

### A single value is a single value, in Java and EvitaQL (chosen in #2013)

javac compiles `entityPrimaryKeyInSet(id)` into `entityPrimaryKeyInSet(new Integer[]{id})`, so the first
implementation read one undefined variable as a list of only nulls, and the filter matched nothing. Up to 2026.2
the same call left the filter out, and conditional query building relied on it (the old "Null Handling" JavaDoc
advertised the pattern). The three filter value lists got single-value overloads that leave the filter out for an
undefined value, and EvitaQL reads a list position the way javac reads a single argument.

- **Pros:** 2026.2 behaviour for `f(nullVariable)` comes back for every value list (name and enum lists keep the
  refusal decided above); the list rule stays intact for explicit lists; EvitaQL and Java answer a lone null or
  blank the same way in every list position.
- **Cons:** a literal `priceInPriceLists(null)` no longer compiles; binaries compiled before the overloads existed
  keep the list form until recompiled (both under Consequences).

### Read a lone bound null in an EvitaQL list position as a syntax error (declined in #2013)

The first implementation kept it an error: a lone `?` there accepts a list or a single value, and the Java
factories then answered "no list" and "one undefined item" differently.

- **Rejected because:** once Java decides by the static shape of the argument, EvitaQL can decide the same way from the
  bound object: an array or collection is the list, any other iterable is refused, anything else is one value. The
  ambiguity is gone, and Java and EvitaQL must behave alike (maintainer's decision). 2026.2 refused every null
  parameter, so no working query depended on the error.

### Tell `f(nullVariable)` from a list at run time (declined)

A one-item array holding `null` would leave the filter out, longer all-null arrays would match nothing.

- **Rejected because:** `f(nullVariable)` and `f(new Integer[]{null})` are the same array at run time, so an
  explicitly built one-item list would contradict the list rule, and `f(null)` against `f(null, null)` would mean
  opposite things by count alone. Only the compiler sees the difference.

### Only document that `f(nullVariable)` matches nothing (declined)

- **Rejected because:** the regression is silent (an empty result, no error) and hits a pattern the previous
  JavaDoc recommended.

### Unwrap a lone array in an attribute value list at run time (first declined, then chosen in #2013)

- **First rejected because:** javac already sends every statically typed object array (`String[]`, `Serializable[]`)
  to the list overload; what reaches the single-value one as an array (a primitive array, an array behind a
  `Serializable` variable) was wrapped into a one-item list before too, so the plain overload changes nothing for a
  defined value. Unwrapping looked like a new feature nobody asked for.
- **Chosen after measuring:** the wrapped array never gave a sensible result. Embedded, the one-item list compared
  the array by its identity text and matched nothing; over gRPC the client printed `attributeInSet(?, ?)` and the
  server read the lone bound array as the whole list and matched its items, so the same Java query gave two answers.
  A value list whose only item is an array is now that array, in both `attributeInSet` overloads and in
  `attributeSetExact`, with primitive items boxed. EvitaQL already read a lone bound array that way and now reads a
  lone array inside a bound array or collection the same way. An array among other items is refused: that shape never
  worked anywhere.

### A single-value `dataInLocales` that leaves the requirement out (declined)

- **Rejected because:** 2026.2 fetched every locale for `dataInLocales(nullLocale)` (measured on 2026.2.9), and
  the one-null-item list still does; the maintainer chose 2026.2 parity. The other non-filter list positions
  (orderings, price contents, accompanying price lists) need no overload either: their list with one undefined
  item already means what 2026.2 gave a single undefined value.

### A leading bound null before an optional enum is a syntax error (declined)

`statistics(?, ?)` bound to `[null, CHILDREN_COUNT]` can be read as a null base followed by one type, or as a type
list with a null item.

- **Rejected because:** the null enum rule already gives the first reading a meaning (the default base), and the
  second reading would only turn the same request into an error; it never widens a result (maintainer's decision).
  The require constraints make the choice in `EvitaQLRequireConstraintVisitor#consumeLeadingNullEnumDefault`;
  `priceDiscount` in the order visitor applies the same rule to its leading direction.

### Accept an array as one EvitaQL attribute value (declined in #2013)

The attribute factories take `T extends Serializable`, so `attributeEquals("words", new String[]{"ALPHA", "BETA"})`
compiles and runs, and the gRPC parameters could carry the array.

- **Rejected because:** the engine converts the items and then compares the array by its identity text
  (`[Ljava.lang.String;@…`), so the query never matches the array it names. Measured on an embedded catalog: equality
  with a product's exact `words` array returned nothing, and `attributeBetween` with two arrays failed on inverted
  bounds. That is neither a conversion nor a refusal, so EvitaQL keeps refusing it, and the Java factories now refuse
  it too. Worth revisiting only if the engine compares an array value by its items.

### Accept a `Predecessor` as an EvitaQL attribute value (declined in #2013)

- **Rejected because:** a Java query with one cannot leave the client (`QueryConverter#convertQueryParam` has no
  parameter type for it), a predecessor attribute cannot be filterable (the schema refuses it), and the chain's sort
  translator refuses `attributeSetExact`. The only path that runs is the planner stringifying the value against an
  unrelated string attribute. Accepting it needs a lossless gRPC parameter first. The Java factories now refuse it
  as well, `ReferencedEntityPredecessor` included; a chain is ordered with `attributeNatural`.

### Keep blank names working as before (declined)

Some factories silently left the constraint out on a blank name (`label`, `attributeSetExact`,
`attributeSetInFilter`, `accompanyingPriceContent`), while EvitaQL kept `attributeEquals('', 1)` as written.

- **Rejected because:** a blank name is the same application bug as a null one (maintainer's decision). Blank
  price-list names are values and are ignored like null items.

### Keep the syntax error for a container without children, e.g. `inScope(LIVE)` (declined)

- **Rejected because:** a container can end up empty because all its children were left out for undefined values;
  it is then left out too, like any other constraint with nothing to apply (maintainer's decision). The same holds
  for `and()`, `or()`, `filterBy()`, `orderBy()`, `require()` and the other containers without a meaning of their
  own when empty; containers that mean something when empty (`entityFetch()`, `hierarchyContent()`) stay.

### Leave REST as a follow-up (declined)

- **Rejected because:** REST had the same silent loss of a filter or ordering that this change fixes in EvitaQL.

### Normalize REST lists in the resolver and keep invoking constructors (declined)

This was implemented first: the resolver cleaned list arguments before calling the constructor, guided by two
hand-kept sets of constraint classes (which `String[]` parameters are names, which are price lists).

- **Rejected because:** a new list constraint had to be added to a set, or its items silently counted as values; and
  everything outside lists still bypassed the rules: a blank `outputName` or `accompanyingPriceName` was accepted, a
  blank `histogramName` turned into the unnamed histogram, `priceDiscount` with only nulls and an `inScope` emptied
  by omitted children were refused with HTTP 400 instead of left out. Building through the factories covers every
  position by construction.

### Keep REST's own results where they differed from Java (declined)

Routing REST through the factories also changed two results for requests without any null: a
`referenceXContentWithAttributes` without `attributeContent` fetched no reference attributes, and a
`traverseByEntityProperty` with a traversal mode but no ordering was dropped, so `referenceProperty` fell back to its
default pick-first ordering and ignored the requested mode.

- **Rejected because:** both contradicted the documentation and Java and EvitaQL (all reference attributes; the
  traversal in the requested mode, ordered by primary key), so REST was the one in error (maintainer's decision).

## Decision

**Chosen: rules by the meaning of the argument, for Java, EvitaQL and REST alike.**

| Argument | `null` | null or blank item beside others | only null items | empty list |
|---|---|---|---|---|
| value | constraint left out (1) | - | - | - |
| name | **error** | - | - | - |
| enum | documented default; **error** where none | - | - | - |
| value list in a filter | constraint left out | item ignored | = empty list: matches nothing | per constraint |
| value list elsewhere | = empty list | item ignored | = empty list | per constraint |
| name or enum list | = empty list | **error** | **error** | per constraint |

(1) Unless the constraint gives `null` a meaning of its own: an open bound of `attributeBetween`, the default page.
A blank name counts as a null name; a blank price-list name counts as a null value. "Error" is
`EvitaInvalidUsageException` from Java, `EvitaSyntaxException` from EvitaQL and HTTP 400 from REST. GraphQL keeps
refusing null list items at schema validation, which is stricter but never wider.

**A single value is not a list (#2013).** `entityPrimaryKeyInSet(Integer)`, `attributeInSet(String, T)` and
`priceInPriceLists(String)` take one value and follow the value row: null, or a blank price list, leaves the filter
out. javac picks them for a single argument whose static type is not an object array (a primitive `int[]` still
reaches the existing `int[]` overload of `entityPrimaryKeyInSet`). The other value lists have no such overload, so
`f(nullVariable)` is a list of one null item there and takes the empty-list meaning; name and enum lists refuse it,
whether through a list or through a single-name overload such as `referenceContent(String)`. EvitaQL reads a list
position the way javac reads a single argument: a bound array or collection is the list (any other iterable is
refused, because the type check would read it before the visitor); one literal, or one parameter bound to a scalar
or to `null`, is one value and reaches the same overload Java would pick.

One undefined value per list position, measured on 2026.2.9 and on the snapshot before #2013:

| Position | 2026.2 Java `f(nullVariable)` | First implementation, Java | Now, Java and EvitaQL |
|---|---|---|---|
| `entityPrimaryKeyInSet`, `attributeInSet`, `priceInPriceLists` | left out | matches nothing | left out |
| blank price list (`" "`) | left out | matches nothing | left out |
| `attributeSetExact`, `entityPrimaryKeyExact`, `defaultAccompanyingPriceLists` | left out | left out | left out |
| `dataInLocales` | every locale | every locale | every locale |
| `priceContentRespectingFilter` | kept with a null item | no extra price lists | no extra price lists |
| `priceContent(RESPECTING_FILTER, ...)` | no extra price lists | the same | the same |
| `attributeContent`, `associatedDataContent`, `scope` | kept or left out | **error** | **error** |

EvitaQL refused every lone null parameter in 2026.2 and in the first implementation; it now answers like the last
column.

**Attribute values are scalars (#2013).** The attribute comparisons and both bounds of `attributeBetween` refuse an
array and a chain predecessor (`Predecessor`, `ReferencedEntityPredecessor`), and so do the items of `attributeInSet`
and `attributeSetExact`; a value list whose only item is an array is that array (see the option above).
`histogramHaving` refuses a NaN or infinite bound as every other factory refuses one. EvitaQL gives the same answers:
the parser lets these shapes reach the factories, and their refusal becomes the syntax error.

Examples, before and after:

| Input | Before | After |
|---|---|---|
| EvitaQL `attributeEquals(?, ?)` bound to `'code', null` | syntax error | left out |
| EvitaQL `attributeBetween(?, ?, ?)` bound to `'price', null, 100` | syntax error | open lower bound |
| Java `attributeEquals(null, "A")` | left out | **error** |
| Java `referenceContent((String) null, entityFetch())` | all references | **error** |
| Java `hierarchyWithinRoot(null)` | the entity's own hierarchy | **error** |
| EvitaQL `attributeEquals('', 1)`, Java `attributeSetExact("", "A")` | kept / left out | **error** |
| Java `statistics(null, CHILDREN_COUNT)` | left out | `statistics(CHILDREN_COUNT)` |
| Java `inScope(null, ...)`, `attributeIs("a", null)` | left out | **error** |
| EvitaQL `attributeInSet(?, ?)` bound to `'code', ['A', null]` | `filterBy` dropped | `attributeInSet('code', 'A')` |
| REST `{"attributeCodeInSet": ["A", null]}` | whole `filterBy` dropped | `attributeInSet('code', 'A')` |
| REST `accompanyingPriceName` or `outputName` `" "` | accepted as a name | HTTP 400 |
| REST `histogramName` `""` | the unnamed histogram | HTTP 400 |
| REST `priceDiscount` `[null, null]`, `inScope` emptied by omitted children | HTTP 400 | left out |
| REST `segment` whose ordering is empty or emptied, with `limit` | HTTP 400 | segment left out |
| REST/GraphQL `stopAt` whose definition is undefined, e.g. `{"level": null}` | HTTP 400 | stop left out |
| REST `referenceXContentWithAttributes` without `attributeContent` | no reference attributes | all of them |
| REST `traverseByEntityProperty` with a mode and no ordering | dropped, mode ignored | traversal by primary key |
| Java `attributeInSet("code", null, null)` | left out | matches nothing |
| Java `attributeSetExact("code", "A", null)` | whole `orderBy` dropped | orders by `A` |
| Java `priceDiscount((String[]) null)`, `priceDiscount()` | NPE / error | left out |
| Java `scope(Scope.LIVE, null)` | whole `filterBy` dropped | **error** |
| Java `userFilter(attributeEquals("a", null), entityPrimaryKeyInSet(1))` | NPE | keeps the second filter |
| EvitaQL `inScope(LIVE)` without children | syntax error | left out |
| EvitaQL `attributeInSet('code', ?)` with the lone `?` bound to `null` | syntax error | left out (#2013) |
| EvitaQL `priceInPriceLists(' ')` | kept as written | left out (#2013) |
| Java `attributeEquals("words", new String[]{"A", "B"})` | compared by identity: matches nothing | **error** (#2013) |
| Java `attributeInSet("code", (Serializable) new String[]{"a", "b"})` | nothing; `a`, `b` in gRPC | `a`, `b` (#2013) |
| Java `attributeInSet("code", "x", new String[]{"a"})` | matches nothing | **error** (#2013) |
| Java `attributeEquals("code", Predecessor.HEAD)` | planner error, or the record's text compared | **error** (#2013) |
| Java `histogramHaving("ref", Double.NaN, 5.0)` | `NumberFormatException` | **error** (#2013) |

The meaning of an empty list stays a property of each constraint: `attributeContent`, `associatedDataContent`,
`referenceContent` and `dataInLocales` fetch everything, filters match nothing, orderings are left out.

**What would reopen this:** a client need to keep an undefined value as "matches nothing" instead of "no filter" (a
new explicit constraint, not a change of these rules).

## Key technical details

- `ConstraintArguments` (`evita_query`) holds the three shared checks: `requireName` (null or blank throws),
  `requireNonNullItems` for name and enum lists, `withoutNullValues` / `withoutUndefinedPriceLists` for value lists.
  The factories call them first, before any "omit on undefined value" branch. For attribute values it also holds
  `requireScalarAttributeValue` (refuses an array and a chain predecessor), `normalizeAttributeValues` (unwraps a lone
  array, boxing primitive items, then checks every item before dropping nulls, so an array among other items, even
  beside a null, is refused) and `toHistogramBound` (normalizes a `Float` or `Double` bound through
  `EvitaDataTypes.toSupportedType`, which refuses NaN and the infinities).
- **The attribute value checks sit in the factories, not in the constructors.** The `evita_store` query serializers
  and `cloneWithArguments` build constraints through the constructors, and a stored query must stay readable. Every
  entry point that builds a new query (Java, EvitaQL, REST, GraphQL) reaches these constraints through the factories
  (`AttributeInRange`, a REST/GraphQL constructor fallback, takes no array or predecessor); a constraint made with
  `new AttributeEquals(...)` by hand is not checked.
- **EvitaQL builds constraints through the `QueryConstraints` factories**, not constructors, so parity holds by
  construction; a few reference-fetch forms that have no matching factory call `ConstraintArguments` directly.
  `ParseContext` accepts present null bindings; `Value` keeps nulls through conversions; bound arrays and lists are
  still type-checked by component type and by each non-null item.
- **A visitor child may now be `null`** (left out). `EvitaQLBaseConstraintVisitor#visitChildConstraint` still checks
  an omitted child's type, through the return type of the `QueryConstraints` factory of the same name, so an invalid
  child stays a syntax error even when it would have been left out.
- **REST/GraphQL build through the factories too.** `ConstraintResolver#instantiateConstraint` calls the
  `QueryConstraints` factory whose name and erased parameter types match the descriptor's creator exactly, and
  passes a `null` result up, so a parent emptied that way is left out in turn; a required single child that was
  left out reaches the factory as `null` too instead of refusing the request (`stopAt` is the only constraint with
  such a child). Where no factory signature matches,
  `Segment` and `HistogramHaving` go through explicit adapters (the latter tells an absent histogram name from a
  blank one), `siblings` without an output name picks the anonymous overload, and the remaining creators keep their
  constructor (at the time of this change 117 of 126 creators matched a factory, 2 use an adapter, 7 keep the
  constructor). Matching by types alone cannot see two same-typed parameters in a different order, which is why
  `ConstraintFactoryDispatchTest` pins the fallback and adapter sets and compares constructor and factory with a
  different value at every position. A new creator without a matching factory fails that test.
- **A `referenceProperty` with a traversal and no ordering of its own** crashed in `SequentialSorter`
  (`OrderByVisitor#getSorters` returns an immutable `List.of(NoSorter.INSTANCE)`, which the sorter tried to edit),
  and without the default sorter it emitted no blocks. REST now sends such a traversal, so the sorter copies the
  list and keeps `NoSorter` when it is the only one: owners stay in traversal blocks, by primary key within each.
- **Empty containers return `null` from their factories.** Engine code that needs an empty `FilterBy` as a sentinel
  (`ReferencedEntityFetcher#extractBodyFilter`, `FacetHavingTranslator`) constructs it directly.
- **The single-value overloads construct their constraint directly.** Calling the factory of the same name from
  inside would resolve to the overload itself. For a defined value they build the same constraint as the one-item
  list; `attributeInSet(String, T)` passes its value through `normalizeAttributeValues`, so an array behind a
  `Serializable` variable becomes its items there too. `(String, T[])` is more specific than `(String, T)`, so a
  `String[]` or `Serializable[]` still reaches the list form of `attributeInSet`; the REST/GraphQL
  `ConstraintResolver` matches creators by exact erased types and every `@Creator` takes an array, so it keeps calling
  the list forms.
- **EvitaQL keeps the argument shape.** `EvitaQLValueTokenVisitor#parseVariadicArguments` and
  `#visitExplicitVariadicValueTokens` mark one literal or one scalar or null binding with
  `Value#isSingleVariadicArgument`; the filter visitors convert once (keeping the overflow check of
  `asIntegerArray()`) and pick the overload from that flag. A positional parameter is consumed when it is visited,
  so a visitor must never visit it a second time just to look at its shape.
  `EvitaQLBaseConstraintVisitor#parseVariadicTail` no longer needs to know whether an optional enum preceded the
  tail: a lone null after it is one null item too.
- **Attribute value positions share one type set.** `EvitaQLValueTokenVisitor#withAttributeValueTypesAllowed` serves
  the attribute comparisons, `attributeBetween`, `attributeInSet` and `attributeSetExact`, and reads an unquoted enum
  literal there as its name. `ParseContext` lets a finite `Float` or `Double` through and refuses NaN and the
  infinities, as the factories' `EvitaDataTypes.toSupportedType` does. The attribute value positions also let an array
  binding (a lone array, or one array item of a bound array or collection, one level deep) and a chain predecessor
  through to the factories, which unwrap or refuse them; `EvitaQLBaseVisitor#parse` turns the factory's
  `EvitaInvalidUsageException` into the syntax error. Besides the attribute value positions, only
  the `attributeInRange` point and the `histogramHaving` bounds accept them (their factories take any `Number`); the
  visitor of every other position refuses both. `label` refuses floating-point numbers and enums too, because the
  `Label` constructor refuses them at run time although its signature compiles them. The parser has no schema, so a
  value that fits no attribute type (`1.5` for an `Integer`) is still refused by the planner, as in Java.
- The factory JavaDoc in `QueryConstraints` is generated by `JavaDocSummarizer` (keyed by `@SourceHash`, which hashes
  the constraint class JavaDoc and the factory signature); the rules are stated once in the `QueryConstraints` class
  JavaDoc and here. The generated summaries of the three single-value overloads described the list form, because the
  generator saw only the class JavaDoc and a bare signature. Their text is now written by hand under an unchanged
  hash, so the generator leaves it until the class JavaDoc changes, and the generator now also sends the model the
  method's annotations and body, asking it to describe that overload and its null result.

## Verification

- `NullArgumentsInventoryTest` reflects over every public factory: 294 scalar names (null and three blank forms),
  194 enum positions compared with the overload without the enum or its documented default, every list parameter in
  five shapes, both null positions in every child-constraint array, and 458 container overloads with no children
  (2,541 cases at the time, 2,548 after #2013). A factory added later without the rules fails it.
- `NullArgumentsParityTest` runs the same inputs through Java and through `DefaultQueryParser` with positional and
  named parameters, including the lone-null cases, omitted children and nested arrays inside bound arrays
  (1,011 cases at the time, 1,026 after #2013).
- `CatalogRestNullArgumentsTest` (46 cases, over HTTP and at the resolver) and `CatalogGraphQLNullArgumentsTest`
  (9 cases) cover the external APIs, each example of the Decision table included; each of those examples has a
  case that was run against the previous resolver first and failed there. `ConstraintFactoryDispatchTest`
  (119 cases) pins the dispatch described above; `SequentialSorterTest` covers the traversal without an ordering;
  `ConstraintArgumentsTest` (14 cases) pins the diagnostics of the shared checks.
- Gates on the final change: the whole non-slow functional suite with its upstream modules, 37,532 tests, 0
  failures (70 skipped as before). Two correctness review passes; 1,161 null-binding mutations end in no internal
  error; the 189 EvitaQL queries of the user documentation parse exactly as before; the existing REST query suite
  (188 cases) passes unchanged.
- **#2013:** the cases with an expectation of their own were run against the first implementation and failed there (9
  failures and 24 errors in the selected classes): the three null-variable cases, the blank price list, the issue's
  `hierarchyWithin` scenario in `QueryTest`, the three missing overloads, the blank EvitaQL literal, and 24
  lone-parameter parity cases that hit the old syntax error. The equality of a defined single value with its one-item
  list, and `dataInLocales(nullLocale)`, pass on both and pin behaviour that did not change.
  `NullArgumentsInventoryTest` requires a single-value sibling for every factory in its filter value-list set whose last
  parameter is an object array, and compares it with the one-item list.
  `NullArgumentsParityTest#shouldMatchFactoriesWhenListPositionHasOneValue` compares a lone positional and named
  parameter (null, a value, a blank price list) with the Java single-argument call in 21 list positions: filters,
  orderings, fetch and statistics requirements, name and enum lists. A refusal must now carry the factory's diagnostic
  (argument names masked); with the old syntax error put back, 18 lone-null name and enum cases fail on that check
  alone. Gates: the `query | contract` groups, 24,775 tests, 0 failures (9 skipped), with
  `ConstraintFactoryDispatchTest` (119), the EvitaQL parser suites and the REST/GraphQL null-argument suites inside; the
  whole reactor compiles with `-P full`, so no call site anywhere became ambiguous.
- **Attribute values (#2013):** an embedded probe ran each candidate value (a supported and an ordinary enum, a
  `String[]`, two `Double`s, a `Predecessor`) through the attribute filters and `attributeSetExact` against `String`,
  `Integer`, `BigDecimal`, `String[]` and `Predecessor` attributes, and through the gRPC parameter conversion; the
  accepted and declined values above follow its table. The new parser, embedded and gRPC cases failed before the
  change (20 failures and 4 errors), the `Float` cases again with only `Float` taken out (7 failures, each refusing
  `java.lang.Float`). Gate: the `query | contract | grpc` groups, 25,152 tests, 0 failures (9 skipped).
  A second probe covered `attributeInRange` against integer and decimal ranges, `histogramHaving` with `Float` and
  `Double` bounds and `label` with floating-point numbers and enums, embedded and through the gRPC conversion: the
  first two run in Java and now in EvitaQL (14 cases failed before, again with the two types taken out), `label`
  refuses them in Java, so EvitaQL keeps refusing.
- **Java side of the declined inputs (#2013):** a probe measured 57 input rows (`String[]`, `Integer[]`, `int[]`, an
  array behind `Serializable`, an array beside a scalar, both predecessor types, NaN and the infinities) through the
  Java factory, embedded, through the gRPC round trip and in EvitaQL; the before column of the examples above comes
  from it. The new factory, parser, embedded and gRPC cases failed before the change (541 tests, 9 failures and 2
  errors). Six counterfactuals each took one change out (the array refusal, the predecessor refusal, the unwrapping,
  the parser's admission of array items, the value visitor's admission of arrays, the histogram bound normalization)
  and each turned its own cases red. `QueryConverterTest#shouldRoundTripWrappedAttributeValueArrays` compares the
  embedded primary keys with those of the query the gRPC server parses. Gate: the new and changed tests with the
  parser, null-argument, dispatch, gRPC, embedded, REST and GraphQL suites, 5,920 tests, 0 failures (5 skipped).
  `AttributeConstraintPathParityFunctionalTest` lost its ten array-as-one-value rows, which pinned the identity
  comparison and are now refused before any execution path.
- **Final gate (#2013):** the whole non-slow functional suite with its upstream modules and the release upgrade
  fixtures, 39,387 tests, 0 failures (70 skipped); the whole reactor compiles with `-P full`. After the Java side of
  the declined inputs: 39,397 tests, 0 failures, 3 errors (70 skipped). The errors were two TLS session-creation
  timeouts (`LabTlsFunctionalTest`, `CatalogRestCdcFunctionalTest`) and a session clean-up race in
  `CatalogGoLiveSessionDrainTest`; the three classes pass when rerun alone (13 tests, 0 failures).

## Consequences & open follow-ups

- **Breaking changes:** a null or blank name throws in Java, EvitaQL and REST; a null enum without a default throws;
  a null or blank item in a name or enum list throws; a Java filter list of only nulls matches nothing instead of
  being left out (an explicit list such as `f(null, null)` or an array; a single nullable variable still leaves the
  filter out); EvitaQL literals with a blank name (`attributeEquals('', 1)`) are now syntax errors. Callers that
  build queries conditionally guard the name, not the factory result. The explicit forms `referenceContentAll()`,
  `hierarchyWithinRootSelf()` and `attributeContentAll()` remain.
- **REST and GraphQL results change** where they differed from Java: a `...ContentWithAttributes` without
  `attributeContent` now returns the reference attributes, a traversal without an ordering now orders by the
  requested traversal, and a blank name supplied as a value is refused (HTTP 400 in REST).
- **GraphQL** refuses null list items at schema level (non-null component types). Accepting and ignoring them as the
  other entry points do would be a separate schema decision.
- **Follow-up (#1964):** 17 overloads of 8 factories (`referenceHaving`, `children`, `siblings`, `parents`, `fromRoot`,
  `fromNode`, `facetSummaryOfReference`, `referenceSummaryOfReference`) still throw a raw `NullPointerException` for a
  null child-constraint *array*, for example `referenceHaving("brand", (FilterConstraint[]) null)`. Before this work
  there were 21 in 10 factories; `facetSummary` and `referenceSummary` were fixed on the way.
- **Source compatibility (#2013):** a literal `priceInPriceLists(null)`, or a generic expression such as
  `priceInPriceLists(value())` with `<V> V value()`, no longer compiles (ambiguous); `entityPrimaryKeyInSet(null)`
  already did not, because of its `int[]` overload. `(String) null` leaves the filter out as one undefined value,
  `(String[]) null` as no list. `attributeInSet("code", null)` still resolves to the list form and is left out. The
  maintainer accepted this without a `breaking change` label; the upgrade note is on #2013.
- **Binary compatibility (#2013):** code compiled before the single-value overloads existed, against 2026.2 or an
  earlier 2026.3 snapshot, still calls the list form, so `f(nullVariable)` matches nothing until the code is
  recompiled. Nothing at run time can tell the two apart (see the declined run-time option).
- **EvitaQL (#2013):** most changes turn a 2026.2 error into a result: a lone bound null; a `Currency`, `Locale`, enum
  or finite `Float`/`Double` attribute value, bound or (for an enum) an unquoted literal; a finite `Float`/`Double`
  bound to an `attributeInRange` point or a `histogramHaving` bound; `histogramStatistics` with
  its `entityFetch` after the index names (the order Java prints, so such a Java query now also works over gRPC).
  Attribute values are normalized as the Java factories normalize them: a number to a `BigDecimal`, an enum to its
  name unless it is a supported enum. So an unquoted literal in an attribute value position is now a string, and a
  missing-quotes typo is no longer an error; a supported enum printed by Java (`attributeEquals('x', OPTIMIZED)`)
  parses back as the string, which the engine compares the same way (over gRPC the parameter keeps the enum).
  These inputs that 2026.2 accepted change meaning:
  - a blank price list, literal (`priceInPriceLists(' ')`) or bound to a lone parameter, was kept as written and is
    now left out;
  - a bound `Iterable` that is not a `Collection` was read as a list and is now refused, because the type check
    reads the items before the visitor and one that can be read only once reached the filter empty; the maintainer
    chose the refusal over reading every iterable once into a list;
  - a positional argument that no `?` uses was ignored and is now refused;
  - `referenceContent…` shapes that bound values to the wrong placeholder are refused at the offending argument (a
    second name before filter, order or fetch children; a requirement after `page` or `strip`), and
    `referenceContentWithAttributes('brand', 'code')` keeps the attribute instead of dropping it.
- **Fixed in passing (#2013):** EvitaQL parameter binding defects found while aligning EvitaQL with Java:
  `histogramStatistics` read its nested fetch before its bucket count; a list bound to the `accompanyingPriceContent`
  name, or given as one of several separate string list items, ended in an internal error instead of a syntax error;
  `Value#asComparable` had no production caller. Each is its own commit on the #2013 branch.
- A left-out `attributeInSet` takes the filter that `attributeSetInFilter` orders by with it, so that ordering then
  fails for a missing filter, exactly as it did in 2026.2.
- A new filter value list factory needs its own single-value overload; `NullArgumentsInventoryTest` checks it once
  the factory is added to the test's filter value-list set.
- When a constraint's class JavaDoc changes, its generated factory summary needs a `JavaDocSummarizer` run
  (`tools/generate-query-constraints-javadoc.sh`); the #2013 overloads and `histogramStatistics` were regenerated, and
  the three single-value summaries were then rewritten by hand (see Key technical details). Removing a method's
  `@SourceHash` forces its regeneration; a change of the method body alone does not trigger one.
- **Java (#2013), the inputs EvitaQL already declined:** they compiled and gave a wrong result or an unrelated error,
  and now throw `EvitaInvalidUsageException` from the factory: an array as one attribute value or `attributeBetween`
  bound, an array among other items of `attributeInSet` or `attributeSetExact`, a `Predecessor` or
  `ReferencedEntityPredecessor` attribute value, and a NaN or infinite `histogramHaving` bound (before, a raw
  `NumberFormatException`). One input changes its result instead: an array behind a `Serializable` variable, or
  wrapped in a one-item varargs array, in `attributeInSet` or `attributeSetExact` now means its items embedded too
  (it matched nothing embedded and its items over gRPC). The maintainer chose these refusals and the unwrapping.

## Timeline

- **2026-11-12** — rules agreed with the maintainer and filed as issue #1943; implemented for Java, EvitaQL and REST.
- **2026-11-18** — #2013: a single nullable variable matched nothing since #1965. Single-value overloads for the
  three filter value lists, EvitaQL aligned with Java in every list position, the lone-null syntax error removed;
  `dataInLocales` keeps every locale (2026.2 parity). The parameter binding defects listed under Consequences were
  fixed on the same branch.
- **2026-11-19** — #2013 follow-up: the Java factories refuse the attribute values EvitaQL had declined and unwrap
  a lone array in an attribute value list (maintainer's decision); the factory JavaDoc generator also receives the
  method source.
