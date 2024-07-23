---
title: Query language
perex: |
  The query language is the core of any database machine. evitaDB has chosen a functional form of the language instead
  of a SQL-like language, which is more consistent with how it works internally and, most importantly, much more open
  to transformations.
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

<LS to="e,j,c">

The evitaDB query language consists of a nested set of functions representing individual "constraints".
Each constraint (function) has its name and set of arguments enclosed in brackets `constraintName(arguments)`,
argument can be a plain value of supported [data type](../use/data-types.md) or another constraint.
Arguments and constraints are separated by a comma
`argument1, argument2`. Strings are enclosed in <LS to="e">`'this is string'` or </LS>`"this is string"`.

</LS>
<LS to="g,r">

The evitaDB query language consists of a nested JSON objects and primitives representing individual "constraints".
Each constraint (represented either by nested object or simple primitive value) has its name specified by the property key
and set of arguments defined as property value. Argument can be a plain value of supported [data type](../use/data-types.md)
or another constraint. Arguments and constraints can be written in number of different styles, depending on a particular constraint
and its support arguments:

- as primitive value when constraint takes single primitive argument
	- `constraintName: "string argument"`
	- `constraintName: [ value1, value2 ]`
- as nested object containing multiple arguments (e.g., multiple primitive arguments)
	- `constraintName: { argument1: 100, argument2: [ 45 ] }`
- as nested object containing child constraints
	- `constraintName: { childConstraintName: "string argument" }`

There also may be different combinations of these.

</LS>

This language is expected to be used by human operators, at the code level the query is represented by
a query object tree, which can be constructed directly without any intermediate string language form (as
opposed to the SQL language, which is strictly string typed).

Query has these four <LS to="g">_logical_</LS> parts:

- **[header](#header):** defines the queried entity collection (it's mandatory unless the filter contains
  constraints targeting globally unique attributes)
- **[filter](#filter-by):** defines constraints that limit the entities returned (optional, if missing, all entities in
  the collection are returned)
- **[order](#order-by):** defines the order in which entities are returned (optional, if missing entities are sorted by
  primary integer key in ascending order)
- **[require](#require):** contains additional information for the query engine - such as pagination settings,
  requirements for completeness of returned entities, and requirements for calculation of accompanying data structures
  (optional, if missing, only primary keys of entities are returned).

## Grammar

<LS to="e,j,c">
The grammar of a query is as follows:
</LS>
<LS to="g,r">
The grammar of a full query is as follows:
</LS>

<SourceCodeTabs langSpecificTabOnly>

[Example of grammar of a query](/documentation/user/en/query/examples/grammar.evitaql)
</SourceCodeTabs>

Or more complex one:

<SourceCodeTabs langSpecificTabOnly>

[Example of grammar of a complex query](/documentation/user/en/query/examples/complexGrammar.evitaql)
</SourceCodeTabs>

<LS to="g">

Where the _header_ part (queried collection) is part of the GraphQL query name itself, and the _filter_, _order_, and _require_
parts are defined using GraphQL arguments of that GraphQL query.
On top of that, GraphQL has kind of a unique representation of the _require_ part. Even though you can define _require_
constraints as GraphQL argument, these are only generic constraints that defines rules for the calculations.
The main require part that defines the completeness of returned entities (and extra results) is defined using output fields
of the GraphQL query. This way, unlike in the other APIs, you specifically define the output form of the query result from
what the domain evitaDB schema allows you to fetch.

There are other simpler variations of the GraphQL query grammar described [here](../use/api/query-data.md#defining-queries-in-graphql-api)
in more detail, but the underlying logic is always the same.

</LS>
<LS to="r">
Where the _header_ part (queried collection) is part a URL path, and the _filter_, _order_, and _require_ parts are defined
as properties of the input JSON object.

There are other simpler variations of the REST query grammar described [here](../use/api/query-data.md#defining-queries-in-rest-api)
in more detail, but the underlying logic is always the same.

</LS>

<LS to="e,j,c">

Any part of the query is optional. Only the `collection` part is usually mandatory, but there is an exception to this rule.
If the `filterBy` part contains a constraint that targets a globally unique attribute, the `collection` part can be omitted
as well because evitaDB can pick the implicit collection of that globally unique attribute automatically.
However, there can be at most one part of each `collection`, `filterBy`, `orderBy`, and `require` in the query.
Any part can be swapped (the order is not important). I.e. the following query is still a valid query and represents
the simplest query possible:

</LS>
<LS to="g,r">

Almost any part of the query is optional. Only the `collection` is usually mandatory, but there is an exception to this rule.
You always need to use specific <LS to="g">GraphQL query</LS><LS to="r">REST endpoint</LS>
where the name of the collection is already defined, however, you can
use generic <LS to="g">GraphQL query</LS><LS to="r">REST endpoint</LS>
(although, it is very limited due to the nature of the generated schema)
and use a constraint that targets a globally unique attribute. In this case, the `collection`
part can be omitted because evitaDB can pick the implicit collection of that globally unique attribute automatically.
<LS to="g">Other parts defined using arguments are optional, but due to the nature of the GraphQL, you have to define at least
one output field.</LS>
<LS to="r">Other parts defined using properties of input JSON object are optional.</LS>
However, there can be at most one part of each _header_, _filter_, _order_, and _require_ in the query.

Another specific in the <LS to="g">GraphQL</LS><LS to="r">REST</LS>
query grammar is that the constraint names usually contains classifiers of targeted data (e.g. name of attribute).
This is important difference from other APIs, and it's because this way the
<LS to="g">GraphQL</LS><LS to="r">REST</LS>
schema for constraint property value can be specific to the constraint and targeted data and an IDE can provide
proper auto-completion and validation of the constraint arguments.

I.e. the following query is still a valid query and represents the simplest query possible:

</LS>

<SourceCodeTabs langSpecificTabOnly>

[Example of the simplest query](/documentation/user/en/query/examples/simplestQuery.evitaql)
</SourceCodeTabs>

... or even this one (although it is recommended to keep the order for better readability:
<LS to="e,j,c">`collection`</LS>, `filterBy`, `orderBy`, `require`):

<SourceCodeTabs langSpecificTabOnly>

[Example random order of query parts](/documentation/user/en/query/examples/randomOrderQuery.evitaql)
</SourceCodeTabs>

<LS to="g,r">
<Note type="info">

<NoteTitle toggles="true">

##### Want to read more about how the grammar was designed?
</NoteTitle>

You can read more about the specifics of the <LS to="g">GraphQL</LS><LS to="r">REST</LS>
query grammar [here](/documentation/blog/en/02-designing-evita-query-language-for-graphql-api.md).

</Note>
</LS>

### Syntax format

In the documentation, constraints are described by a **Syntax** section that follows this format:

```evitaql-syntax
constraintName(
    argument:type,specification
    constraint:type,specification
)
```

<dl>
  <dt>argument:type,specification</dt>
  <dd>
    argument represents an argument of a particular type, for example: `argument:string` represents a string argument at
    a particular position.
  </dd>
  <dt>constraint:type,specification</dt>
  <dd>
    constraint represents an argument of constraint type - the supertype (`filter`/`order`/`require`) of the constraint
    is always specified before the colon, for example: `filterConstraint:any`;

    after the colon, the exact type of allowed constraint is listed, or the keyword `any' is used if any of
    the standalone constraints can be used
  </dd>
</dl>

<LS to="g,r">

<Note type="warning">

This syntax format is currently specific to the base evitaQL language and doesn't reflect the differences in the
<LS to="g">GraphQL</LS><LS to="r">REST</LS> API.
However, you can still benefit from this syntax as the naming and accepted arguments are the same (only in slightly different format).
<LS to="g">The more specific GraphQL constraints/queries do have specific documentation however.</LS>

</Note>

</LS>

#### Variadic arguments

If the argument can be multiple values of the same type (an array type), the specification is appended with a special
character:

<dl>
  <dt>`*` (asterisk)</dt>
  <dd>denoting the argument must occur zero, one, or more times (optional multi-value argument).</dd>
  <dt>`+` (plus)</dt>
  <dd>denoting the argument must one, or more times (mandatory multi-value argument).</dd>
</dl>

<Note type="info">

<NoteTitle toggles="false">

##### Example of variadic arguments
</NoteTitle>

<dl>
  <dt>`argument:string+`</dt>
  <dd>
    argument at this position accepts an array of <LS to="e,j,r,g">[Strings](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)</LS><LS to="c">[strings](https://learn.microsoft.com/en-us/dotnet/api/system.string)</LS>
    that has to have at least one item
  </dd>
  <dt>`argument:int*`</dt>
  <dd>
    argument at this position accepts an array of <LS to="e,j,r,g">[ints](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)</LS><LS to="c">[ints](https://learn.microsoft.com/en-us/dotnet/api/system.int32)</LS>
    and may have zero or multiple items
  </dd>
  <dt>`filterConstraint:any*`</dt>
  <dd>
    argument at this position accepts an array of any standalone filter constraints with zero or more occurrences
  </dd>
</dl>

</Note>

#### Mandatory arguments

Mandatory argument is denoted by `!` (exclamation) sign or in case of variadic arguments by a `+` (plus) sign.

<Note type="info">

<NoteTitle toggles="false">

##### Example of mandatory arguments
</NoteTitle>

<dl>
  <dt>`argument:string`</dt>
  <dd>
    argument at this position accepts a <LS to="e,j,r,g">[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)</LS><LS to="c">[string](https://learn.microsoft.com/en-us/dotnet/api/system.string)</LS>
    value, that may be null
  </dd>
  <dt>`argument:int!`</dt>
  <dd>
    argument at this position accepts an <LS to="e,j,r,g">[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)</LS><LS to="c">[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32)</LS>
    value that is mandatory and must be provided
  </dd>
</dl>

</Note>

#### Combined arguments

The specification list might have a combined expression using `|` for combining multiple specification in logical
disjunction meaning (boolean OR) and `()` signs for aggregation.

<Note type="info">

<NoteTitle toggles="false">

##### Example of combined arguments
</NoteTitle>

<dl>
  <dt>`filterConstraint:(having|excluding)`</dt>
  <dd>
    either `having` or `excluding`, or none, but not both, and no filtering constraint of other type
    is allowed
  </dd>
  <dt>`filterConstraint:(having|excluding)!`</dt>
  <dd>
    either `with` or `exclude` filter constraint, but not both, and not none, but no other filter constraint is allowed
  </dd>
  <dt>`filterConstraint:(having|excluding)*`</dt>
  <dd>
    either `having` or `excluding` a filter constraint, or both, or none, but no other filter constraint is allowed.
  </dd>
  <dt>`filterConstraint:(having|excluding)+`</dt>
  <dd>
    either `having` or `excluding` a filter constraint, or both, but at least one of them and no filter constraint
    of other type is allowed
  </dd>
</dl>

</Note>

### Constraint naming rules

To make constraints more understandable, we have created a set of internal rules for naming constraints:

1. the name of the entity should be in a form (tense) that matches the English query phrase: *query collection ..., and filter entities by ..., and order result by ..., and require ...*
    - the query should be understandable to someone who is not familiar with evitaDB's syntax and internal mechanisms.
2. The constraint name starts with the part of the entity it targets - i.e., `entity`, `attribute`, `reference` - followed <LS to="g,r">usually by classifier of targeted data, which is followed</LS> by a word that captures the essence of the constraint.
3. If the constraint only makes sense in the context of some parent constraint, it must not be usable anywhere else, and might relax rule #2 (since the context will be apparent from the parent constraint).

## Generic query rules

### Data type conversion

If the value to be compared in the constraint argument doesn't match the attribute data type, evitaDB tries to
automatically convert it into the correct type before the comparison. Therefore, you can also provide *string* values
for comparison with number types. Of course, it's better to provide evitaDB with the correct types and avoid
the automatic conversion.

### Array types targeted by the constraint

If the constraint targets an attribute that is of array type, the constraint automatically matches an entity in case
**any** of the attribute array items satisfies it.

For example let's have a <LS to="e,j,r,g">[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)</LS><LS to="c">[string](https://learn.microsoft.com/en-us/dotnet/api/system.string)</LS>
array attribute named `oneDayDeliveryCountries` with the following values: `GB`, `FR`, `CZ`. The filtering constraint
[`attributeEquals`](filtering/comparable.md#attribute-equals) worded as follows: <LS to="e,j,c">`attributeEquals("oneDayDeliveryCountries", "GB")`</LS>
<LS to="g">`attributeOneDayDeliveryCountriesEquals: "GB"`</LS>
<LS to="r">`"attributeOneDayDeliveryCountriesEquals": "GB"`</LS>
will match the entity, because the *GB* is one of the array values.

Let's look at a more complicated, but more useful example. Let's have a [`DateTimeRange`](../use/data-types.md#datetimerange)
array attribute called `validity` that contains multiple time periods when the entity can be used:

```plain
[2023-01-01T00:00:00+01:00,2023-02-01T00:00:00+01:00]
[2023-06-01T00:00:00+01:00,2022-07-01T00:00:00+01:00]
[2023-12-01T00:00:00+01:00,2024-01-01T00:00:00+01:00]
```

In short, the entity is only valid in January, June, and December 2023. If we want to know if it's possible to access
(e.g. buy a product) in May using the constraint <LS to="e,j,c">`attributeInRange("validity", "2023-05-05T00:00:00+01:00")`</LS>
<LS to="g">`attributeValidityInRange: "2023-05-05T00:00:00+01:00"`</LS>
<LS to="r">`"attributeValidityInRange": "2023-05-05T00:00:00+01:00"`</LS>, the result
will be empty because none of the `validity` array ranges matches that date and time. Of course, if we ask for an entity
that is valid in June using
<LS to="e,j,c">`attributeInRange("validity", "2023-06-05T00:00:00+01:00")`</LS>
<LS to="g">`attributeValidityInRange: "2023-06-05T00:00:00+01:00"`</LS>
<LS to="r">`"attributeValidityInRange": "2023-06-05T00:00:00+01:00"`</LS>, the entity will be returned
because there is a single date/time range in the array that satisfies this constraint.

## Header

<LS to="e,j,c">Only a `collection` constraint is allowed in this part of the query.</LS>
<LS to="g,r">Only a collection definition is allowed and is defined as part of <LS to="g">a GraphQL query name</LS><LS to="r">an endpoint URL</LS>.</LS>
It defines the entity type that the query will
target. It can be omitted
<LS to="g,r">when using generic <LS to="g">GraphQL query</LS><LS to="r">endpoint</LS></LS>
if the [filterBy](#filter-by) contains a constraint that targets a globally unique attribute.
This is useful for one of the most important e-commerce scenarios, where the requested URI needs to match one of the
existing entities (see the [routing](../solve/routing.md) chapter for a detailed guide).

## Filter by

Filtering constraints allow you to select only a few entities from many that exist in the target collection. It's
similar to the "where" clause in SQL. Currently, these filtering constraints are available for use.

### Logical constraints

Logical constraints are used to perform logical operations on the products of child functions:

- [and](filtering/logical.md#and)
- [or](filtering/logical.md#or)
- [not](filtering/logical.md#not)

### Constant constraints

Constant constraints directly specify the entity primary keys that are expected on the output.

- [entity primary key in set](filtering/constant.md#entity-primary-key-in-set)

### Localization constraints

Localization constraints allow you to narrow down the [localized attributes](../use/data-model.md#localized-attributes)
to a single <LS to="e,j,r,g">[locale](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html)</LS><LS to="c">[locale](https://learn.microsoft.com/en-us/dotnet/api/system.globalization.cultureinfo)</LS>, which is used
to pick the correct values for comparison in other filter constraints that target those attributes:

- [entity locale equals](filtering/locale.md#entity-locale-equals)

### Comparable constraints

Comparable constraints compare the constants passed as arguments with a specific attribute of an entity, and then filter
the resulting output to only include values that satisfy the constraint.

- [attribute equals](filtering/comparable.md#attribute-equals)
- [attribute greater than](filtering/comparable.md#attribute-greater-than)
- [attribute greater than, equals](filtering/comparable.md#attribute-greater-than-equals)
- [attribute less than](filtering/comparable.md#attribute-less-than)
- [attribute less than, equals](filtering/comparable.md#attribute-less-than-equals)
- [attribute between](filtering/comparable.md#attribute-between)
- [attribute in set](filtering/comparable.md#attribute-in-set)
- [attribute is](filtering/comparable.md#attribute-is)

### String constraints

String constraints are similar to [Comparable](#comparable-constraints), but operate only on the
<LS to="e,j,r,g">[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)</LS><LS to="c">[string](https://learn.microsoft.com/en-us/dotnet/api/system.string)</LS> attribute datatype and
allow operations specific to it:

- [attribute contains](filtering/string.md#attribute-contains)
- [attribute starts with](filtering/string.md#attribute-starts-with)
- [attribute ends with](filtering/string.md#attribute-ends-with)

### Range constraints

String constraints are similar to [Comparable](#comparable-constraints), but operate only on the
<LS to="e,j,r,g"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/Range.java</SourceClass></LS><LS to="e,j,r,g"><SourceClass>EvitaDB.Client/DataTypes/Range.cs</SourceClass></LS> attribute datatype and
allow operations specific to it:

- [attribute in range](filtering/range.md#attribute-in-range)
- [attribute in range now](filtering/range.md#attribute-in-range-now)

### Price constraints

Price constraints allow filtering entities by matching a price they posses:

- [price in currency](filtering/price.md#price-in-currency)
- [price in price lists](filtering/price.md#price-in-price-lists)
- [price valid in](filtering/price.md#price-valid-in)
- [price between](filtering/price.md#price-between)

### Reference constraints

Reference constraints allow filtering of entities by existence of reference attributes specified on their
references/relationships to other entities, or a filtering constraint on the referenced entity itself:

- [reference having](filtering/references.md#reference-having)
- [entity having](filtering/references.md#entity-having)
- [facet having](filtering/references.md#facet-having)

### Hierarchy constraints

Hierarchy constraints take advantage of references to a hierarchical set of entities (forming a tree) and allow
filtering of entities by the fact that they refer to a particular part of the tree:

- [hierarchy within](filtering/hierarchy.md#hierarchy-within)
- [hierarchy within root](filtering/hierarchy.md#hierarchy-within-root)
- [excluding root](filtering/hierarchy.md#excluding-root)
- [excluding](filtering/hierarchy.md#excluding)
- [having](filtering/hierarchy.md#having)
- [direct relation](filtering/hierarchy.md#direct-relation)

### Special constraints

Special constraints are used only for the definition of a filter constraint scope, which has a different treatment in
calculations:

- [user filter](filtering/behavioral.md#user-filter)

## Order by

Order constraints allow you to define a rule that controls the order of entities in the response. It's similar to the
"order by" clause in SQL. Currently, these ordering constraints are available for use:

- [entity primary key in filter](ordering/constant.md#exact-entity-primary-key-order-used-in-filter)
- [entity primary key exact](ordering/constant.md#exact-entity-primary-key-order)
- [entity primary key natural](ordering/comparable.md#primary-key-natural)
- [attribute set in filter](ordering/constant.md#exact-entity-attribute-value-order-used-in-filter)
- [attribute set exact](ordering/constant.md#exact-entity-attribute-value-order)
- [attribute natural](ordering/comparable.md#attribute-natural)
- [price natural](ordering/price.md#price-natural)
- [reference property](ordering/reference.md#reference-property)
- [entity property](ordering/reference.md#entity-property)
- [entity group property](ordering/reference.md#entity-group-property)
- [random](ordering/random.md#random)

## Require

Requirements have no direct parallel in other database languages. They define sideway calculations, paging, the amount
of data fetched for each returned entity, and so on, but never affect the number or order of returned entities.
Currently, these requirements are available to you:

### Paging

Paging requirements control how large and which subset of the large filtered entity set is actually returned in
the output.

<LS to="e,j,r,c">
- [page](requirements/paging.md#page)
- [strip](requirements/paging.md#strip)
</LS>
<LS to="g">
- [`list` queries](requirements/paging.md#pagination-of-list-queries)
- [`query` queries - `recordPage`](requirements/paging.md#page-recordpage)
- [`query` queries - `recordStrip`](requirements/paging.md#page-recordstrip)
</LS>

### Fetching (completeness)

Fetching requirements control the completeness of the returned entities. <LS to="e,j,r,c">By default, only a
<LS to="e,j,r"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/Structure/EntityReference.cs</SourceClass></LS>
is returned in query response.</LS> In order an entity body is returned, some of the following requirements needs to be part
of it:

- [entity fetch](requirements/fetching.md#entity-fetch)
- [entity group fetch](requirements/fetching.md#entity-group-fetch)
- [attribute content](requirements/fetching.md#attribute-content)
- [associated data content](requirements/fetching.md#associated-data-content)
- [price content](requirements/fetching.md#price-content)
- [reference content](requirements/fetching.md#reference-content)
- [hierarchy content](requirements/fetching.md#hierarchy-content)
<LS to="e,j,r,c">
- [data in locale](requirements/fetching.md#data-in-locales)
</LS>

### Hierarchy

Hierarchy requirements trigger the calculation of additional data structure that can be used to render a menu that
organizes the entities into a more understandable tree-like categorization:

- [hierarchy of self](requirements/hierarchy.md#hierarchy-of-self)
- [hierarchy of reference](requirements/hierarchy.md#hierarchy-of-reference)
- [from root](requirements/hierarchy.md#from-root)
- [from node](requirements/hierarchy.md#from-node)
- [children](requirements/hierarchy.md#children)
- [siblings](requirements/hierarchy.md#siblings)
- [parents](requirements/hierarchy.md#parents)
- [stop at](requirements/hierarchy.md#stop-at)
- [distance](requirements/hierarchy.md#distance)
- [level](requirements/hierarchy.md#level)
- [node](requirements/hierarchy.md#node)
- [statistics](requirements/hierarchy.md#statistics)

### Facets

Facet requirements trigger the computation of an additional data structure that lists all entity faceted references,
organized into a group with a calculated count of all entities that match each respective facet. Alternatively,
the summary could include a calculation of how many entities will be left when that particular facet is added to
the filter:

- [facet summary](requirements/facet.md#facet-summary)
<LS to="e,j,r,c">
- [facet summary of reference](requirements/facet.md#facet-summary-of-reference)
</LS>
- [facet conjunction](requirements/facet.md#facet-groups-conjunction)
- [facet disjunction](requirements/facet.md#facet-groups-disjunction)
- [facet negation](requirements/facet.md#facet-groups-negation)

### Histogram

Histogram requests trigger the calculation of an additional data structure that contains a histogram of entities
aggregated by their numeric value in a particular attribute or by their sales price:

- [attribute histogram](requirements/histogram.md#attribute-histogram)
- [price histogram](requirements/histogram.md#price-histogram)

### Price

The price requirement controls which form of price for sale is taken into account when entities are filtered, ordered,
or their histograms are calculated:

- [price type](requirements/price.md#price-type)

### Telemetry

The telemetry requirements trigger the calculation of additional telemetry data for looking under the hood of the
database engine:

- [query telemetry](requirements/telemetry.md#query-telemetry)