---
title: Null arguments in query constraints follow one rule set in Java, EvitaQL and REST
date: 2026-11-12
updated: 2026-11-12 19:35
status: accepted
kind: fix
issues: [1943]
prs: []
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
  filter out would widen the result (maintainer's decision).

### A null name list throws (declined)

- **Rejected because:** the documented `referenceContent()` means all references, so a null array means "no names
  given" and takes the empty list's meaning. A null or blank *item* of a name list stays an error.

### Every null enum throws (declined)

- **Rejected because:** 188 factory overloads already document a default for their enum; breaking them gains
  nothing. Only `inScope`, the special value of `attributeIs` and the mode of `priceContent` have none and throw.

### Read a lone bound null in an EvitaQL list position as a null list, or as one null item (declined)

- **Rejected because:** a lone `?` there accepts a list or a single value, and the Java factories answer "no list"
  and "one undefined item" differently (`attributeInSet` would match everything or nothing). Either reading silently
  picks one. It stays a syntax error that names the constraint and says to bind an empty list or leave it out.
  Revisit only if EvitaQL gains a way to tell the two apart.

### A leading bound null before an optional enum is a syntax error (declined)

`statistics(?, ?)` bound to `[null, CHILDREN_COUNT]` can be read as a null base followed by one type, or as a type
list with a null item.

- **Rejected because:** the null enum rule already gives the first reading a meaning (the default base), and the
  second reading would only turn the same request into an error; it never widens a result (maintainer's decision).
  The require constraints make the choice in `EvitaQLRequireConstraintVisitor#consumeLeadingNullEnumDefault`;
  `priceDiscount` in the order visitor applies the same rule to its leading direction.

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
| EvitaQL `attributeInSet('code', ?)` with the lone `?` bound to `null` | syntax error | syntax error, clearer message |

The meaning of an empty list stays a property of each constraint: `attributeContent`, `associatedDataContent`,
`referenceContent` and `dataInLocales` fetch everything, filters match nothing, orderings are left out.

**What would reopen this:** EvitaQL gaining a null literal or a typed binding (then the lone-null exception can go);
a client need to keep an undefined value as "matches nothing" instead of "no filter" (a new explicit constraint, not
a change of these rules).

## Key technical details

- `ConstraintArguments` (`evita_query`) holds the three shared checks: `requireName` (null or blank throws),
  `requireNonNullItems` for name and enum lists, `withoutNullValues` / `withoutUndefinedPriceLists` for value lists.
  The factories call them first, before any "omit on undefined value" branch.
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
- The factory JavaDoc in `QueryConstraints` is generated by `JavaDocSummarizer` (keyed by `@SourceHash`) and was not
  edited; the rules are stated once in the `QueryConstraints` class JavaDoc and here.

## Verification

- `NullArgumentsInventoryTest` reflects over every public factory: 294 scalar names (null and three blank forms),
  194 enum positions compared with the overload without the enum or its documented default, every list parameter in
  five shapes, both null positions in every child-constraint array, and 458 container overloads with no children
  (2,541 cases). A factory added later without the rules fails it.
- `NullArgumentsParityTest` runs the same inputs through Java and through `DefaultQueryParser` with positional and
  named parameters, including the lone-null cases, omitted children and nested arrays inside bound arrays
  (1,011 cases).
- `CatalogRestNullArgumentsTest` (46 cases, over HTTP and at the resolver) and `CatalogGraphQLNullArgumentsTest`
  (9 cases) cover the external APIs, each example of the Decision table included; each of those examples has a
  case that was run against the previous resolver first and failed there. `ConstraintFactoryDispatchTest`
  (119 cases) pins the dispatch described above; `SequentialSorterTest` covers the traversal without an ordering;
  `ConstraintArgumentsTest` (14 cases) pins the diagnostics of the shared checks.
- Gates on the final change: the whole non-slow functional suite with its upstream modules, 37,532 tests, 0
  failures (70 skipped as before). Two correctness review passes; 1,161 null-binding mutations end in no internal
  error; the 189 EvitaQL queries of the user documentation parse exactly as before; the existing REST query suite
  (188 cases) passes unchanged.

## Consequences & open follow-ups

- **Breaking changes:** a null or blank name throws in Java, EvitaQL and REST; a null enum without a default throws;
  a null or blank item in a name or enum list throws; a Java filter list of only nulls matches nothing instead of
  being left out; EvitaQL literals with a blank name (`attributeEquals('', 1)`) are now syntax errors. Callers that
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
- When a constraint's class JavaDoc changes, its generated factory summary needs a `JavaDocSummarizer` run.

## Timeline

- **2026-11-12** — rules agreed with the maintainer and filed as issue #1943; implemented for Java, EvitaQL and REST.
