---
title: Headers
date: '12.12.2024'
perex: |
  Only a few constraints can be used in the header part of the query. They define the query target or may be used to
  to tag the query for later identification.
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

## Collection

```evitaql-syntax
collection(
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        mandatory string argument representing the name of the entity collection to be queried
    </dd>
</dl>

<LS to="e,j,c">This constraint defines entity collection targeted by this query.</LS>
<LS to="g,r">Target entity collection definition is defined as part of <LS to="g">a GraphQL query name</LS><LS to="r">an endpoint URL</LS>.</LS>
It can be omitted <LS to="g,r">when using generic <LS to="g">GraphQL query</LS><LS to="r">endpoint</LS></LS>
if the [filterBy](../basics#filter-by) contains a constraint that targets a globally unique attribute.
This is useful for one of the most important e-commerce scenarios, where the requested URI needs to match one of the
existing entities (see the [routing](../../solve/routing.md) chapter for a detailed guide).

## Label

```evitaql-syntax
label(
    argument:string!,
    argument:any!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        mandatory string argument representing the name of the label
    </dd>
    <dt>argument:any!</dt>
    <dd>
        mandatory any argument representing the value of the label, 
        any [supported type](../../use/data-types.md#simple-data-types) can be used
    </dd>
</dl>

This `label` constraint allows a single label name with associated value to be specified in the query header and
propagated to the trace generated for the query. A query can be tagged with multiple labels.

Labels are also recorded with the query in the traffic record and can be used to look up the query in the traffic
inspection or traffic replay. Labels are also attached to JFR events related to the query.