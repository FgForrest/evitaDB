---
title: Query introduction
perex:
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

evitaDB query language is composed of nested set of functions. Each function has its name and set of arguments inside
round brackets (`function_name(arguments)`), argument can be a plain value of supported [data type](../model/data_types)
or other function. Arguments and functions are delimited by a comma (`argument1, argument2`). Strings are enveloped by
apostrophes (`'this is string'`).

This language is expected to be used by human operators, on the code level query is represented by a [query object tree](query_api),
that can be constructed directly without intermediate string language form (on the contrary to SQL language which is
strictly string typed). The human-readable form is used in this documentation. The human-readable form can be parsed
to object representation using [the parser](query_api#conversion-of-the-evitaql-from-string-to-ast-and-back).

Query has these four parts:

- **[header](#header):** contains entity (mandatory) specification
- **[filter](#filter-by):** contains constraints limiting entities being returned (optional, if missing all are
returned)
- **[order](#order-by):** defines in what order will the entities return (optional, if missing entities are ordered by
primary integer key in ascending order)
- **[require](#require):** contains additional information for the query engine, may hold pagination settings, richness
of the entities and so on (optional, if missing only primary keys of the entities are returned)

Query always returns result in the form of <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/EvitaResponse.java</SourceClass>
containing:

- **<SourceClass>evita_common/src/main/java/io/evitadb/dataType/PaginatedList.java</SourceClass>** of result data
- [Map](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Map.html) of extra results indexed by their
class (`<T extends EvitaResponseExtraResult> Map<Class<T>, T>`), see detailed documentation for the individual
[require](#require) constraints producing extra results

## Grammar

The grammar of the query is as follows:

``` evitaQL
query(
    entities('product'),
    filterBy(entityPrimaryKeyInSet(1, 2, 3)),
    orderBy(attributeNatural('name', DESC)),
    require(entityFetch())
)
```

Or more complex one:

``` evitaql
query(
    entities('product'),
    filterBy(
       and(
          entityPrimaryKeyInSet(1, 2, 3),
          attributeEquals('visible', true)
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

The filter, order and require constraints are optional, first argument specifying entity type is mandatory. There may be
at most one argument of type `filterBy`, `orderBy`, and `require`. Any of these arguments may be missing or may be
swapped. I.e. following query is still a valid query:

``` evitaQL
query(
    entities('product'),
    filterBy(entityPrimaryKeyInSet(1)),
    require(entityFetch())
)
```

... or even this one (although, for better readability is recommended to maintain order of: `entities`, `filterBy`,
`orderBy`, `require`):

``` evitaQL
query(
    require(entityFetch()),
    orderBy(attributeNatural('name', ASC)),
    entities('product')
)
```

This also means that there cannot be a constraint of the same name that could be used either in filtering or ordering or
require constraint. Constraint name uniquely identifies whether constraint is a filter, order or require constraint.
Entity type is always [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html).

### Constraint naming rules

In order to make constraints more comprehensible we created a set of internal rules for naming the constraints:

1. the name of the entity should be in form (tense) that fits to English query sentence:
*query entities ..., and filter them by ..., and order them by ..., and require ...*

the query should be understandable even to someone who does not know evitaDB syntax and internal mechanisms
2. the constraint name starts with the part of the entity that it targets - i.e. `entity`, `attribute`, `reference`,
followed by a word that captures the essence of the constraint
3. if the constraint makes sense only within context of some parent constraint, it must not be usable anywhere else
and might relax on rule #2 (since the context will be apparent from the parent constraint)
