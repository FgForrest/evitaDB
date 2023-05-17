---
title: Query language
perex: |
  The query language is the core of any database machine. evitaDB has chosen a functional form of the language instead
  of a SQL-like language, which is more consistent with how it works internally and, most importantly, much more open 
  to transformations.
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

The evitaDB query language consists of a nested set of functions. Each function has its name and set of arguments 
enclosed in brackets (`functionName(arguments)`), argument can be a plain value of supported 
[data type](../use/data-types.md) or another function. Arguments and functions are separated by a comma 
(`argument1, argument2`). Strings are enclosed in (`'this is string'`).

This language is expected to be used by human operators, at the code level the query is represented by 
a query object tree, which can be constructed directly without any intermediate string language form (as 
opposed to the SQL language, which is strictly string typed).

Query has these four parts:

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

The grammar of the query is as follows:

``` evitaql
query(
    collection('Product'),
    filterBy(entityPrimaryKeyInSet(1, 2, 3)),
    orderBy(attributeNatural('name', DESC)),
    require(entityFetch())
)
```

Or more complex one:

``` evitaql
query(
    collection('Product'),
    filterBy(
       and(
          entityPrimaryKeyInSet(1, 2, 3),
          attributeEquals('visibility', 'VISIBLE')
       )
    ),
    orderBy(
        attributeNatural('name', ASC),
        attributeNatural('priority', DESC)
    ),
    require(
        entityFetch(
			attributeContent(), priceContentAll()
		),
        facetSummary()
    )
)
```

Any part of the query is optional, but at least `filterBy` or `collection` is mandatory. There can be at most one 
part of type `filterBy`, `orderBy`, and `require` in the query. Any part can be swapped (the order is not 
important). I.e. the following query is still a valid query:

``` evitaql
query(
    collection('Product'),   
    require(entityFetch())
)
```

... or even this one (although it is recommended to keep the order for better readability: `collection`, `filterBy`, 
`orderBy`, `require`):

``` evitaql
query(
    require(entityFetch()),
    orderBy(attributeNatural('name', ASC)),
    collection('Product')
)
```

### Syntax format

In the documentation, constraints are described by a **Syntax** section that follows this format:

```
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
    argument at this position accepts an array of [Strings](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
    that has to have at least one item
  </dd>
  <dt>`argument:int*`</dt>
  <dd>
    argument at this position accepts an array of [ints](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
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
    argument at this position accepts a [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
    value, that may be null
  </dd>
  <dt>`argument:int!`</dt>
  <dd>
    argument at this position accepts an [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
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

1. the name of the entity should be in a form (tense) that matches the English query phrase:
*query collection ..., and filter entities by ..., and order result by ..., and require ...*

the query should be understandable to someone who is not familiar with evitaDB's syntax and internal mechanisms.
2. The constraint name starts with the part of the entity it targets - i.e., `entity`, `attribute`, `reference` - 
followed by a word that captures the essence of the constraint.
3. If the constraint only makes sense in the context of some parent constraint, it must not be usable anywhere else, 
and might relax rule #2 (since the context will be apparent from the parent constraint).

## Header

Only a `collection` constraint is allowed in this part of the query. It defines the entity type that the query will 
target. It can be omitted if the [filterBy](#filter-by) contains a constraint that targets a globally unique attribute. 
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
to a single [locale](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html), which is used
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
- [attribute is true](filtering/comparable.md#attribute-is-true)
- [attribute is false](filtering/comparable.md#attribute-is-false)
- [attribute is null](filtering/comparable.md#attribute-is-null)
- [attribute is not null](filtering/comparable.md#attribute-is-not-null)

### String constraints

String constraints are similar to [Comparable](#comparable-constraints), but operate only on the 
[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) attribute datatype and
allow operations specific to it:

- [attribute contains](filtering/string.md#attribute-contains)
- [attribute starts with](filtering/string.md#attribute-starts-with)
- [attribute ends with](filtering/string.md#attribute-ends-with)

### Range constraints

String constraints are similar to [Comparable](#comparable-constraints), but operate only on the
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/Range.java</SourceClass> attribute datatype and
allow operations specific to it:

- [attribute in range](filtering/range.md#attribute-in-range)

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
- [direct relation](filtering/hierarchy.md#direct-relation)

### Special constraints

Special constraints are used only for the definition of a filter constraint scope, which has a different treatment in 
calculations:

- [user filter](filtering/special.md#user-filter)

## Order by

Order constraints allow you to define a rule that controls the order of entities in the response. It's similar to the 
"order by" clause in SQL. Currently, these ordering constraints are available for use:

- [attribute natural](ordering/natural.md#attribute-natural)
- [price natural](ordering/price.md#price-natural)
- [reference property](ordering/reference.md#reference-property)
- [entity property](ordering/reference.md#entity-property)
- [random](ordering/random.md#random)

## Require

Requirements have no direct parallel in other database languages. They define sideway calculations, paging, the amount 
of data fetched for each returned entity, and so on, but never affect the number or order of returned entities.
Currently, these requirements are available to you:

### Paging

Paging requirements control how large and which subset of the large filtered entity set is actually returned in 
the output.

- [page](requirements/paging.md#page)
- [strip](requirements/paging.md#strip)

### Fetching (completeness)

Fetching requirements control the completeness of the returned entities. By default, only a 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass> 
is returned in query response. In order an entity body is returned, some of the following requirements needs to be part
of it:

- [entity fetch](requirements/fetching.md#entity-fetch)
- [attribute content](requirements/fetching.md#attribute-content)
- [associated data content](requirements/fetching.md#associated-data-content)
- [price content](requirements/fetching.md#price-content)
- [reference content](requirements/fetching.md#reference-content)
- [hierarchy content](requirements/fetching.md#hierarchy-content)
- [data in locale](requirements/fetching.md#data-in-locale)

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
- [facet conjunction](requirements/facet.md#facet-conjunction)
- [facet disjunction](requirements/facet.md#facet-disjunction)
- [facet negation](requirements/facet.md#facet-negation)

### Histogram

Histogram requests trigger the calculation of an additional data structure that contains a histogram of entities 
aggregated by their numeric value in a particular attribute or by their sales price:

- [attribute histogram](requirements/histogram.md#attribute-histogram)
- [price histogram](requirements/histogram.md#price-histogram)

### Price

The price requirement controls which form of price for sale is taken into account when entities are filtered, ordered, 
or their histograms are calculated:

- [price type](requirements/price.md#price-type)