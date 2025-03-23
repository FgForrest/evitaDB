---
title: Collection
date: '12.12.2024'
perex: Only a few constraints can be used in the header part of the query. Collection defines the query target.
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