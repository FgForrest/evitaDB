---
title: Price ordering
date: '2.7.2023'
perex: |
  Ordering by selling price is one of the basic requirements for e-commerce applications. The price ordering constraint
  allows to sort the output entities by their selling price in ascending or descending order.
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

## Price natural

```evitaql-syntax
priceNatural(
    argument:enum(ASC|DESC)
)
```

<dl>
    <dt>argument:enum(ASC|DESC)</dt>
    <dd>
        the ordering direction (ascending or descending), **default value** is `ASC`
    </dd>
</dl>

This constraint allows output entities to be sorted by their [selling price](../filtering/price.md#price-for-sale-computation-algorithm) 
in their natural numeric order. It requires only the order direction and the price constraints in the `filterBy` section
of the query. The price variant (with or without tax) is determined by the [`priceType`](../requirements/price.md#price-type) 
requirement of the query (price with tax is used by default).

To sort products by their selling price (currently considering only `basic` price list and `CZK`), we can use 
the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[List of products sorted by selling price](/documentation/user/en/query/ordering/examples/price/price-natural.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List of products sorted by selling price
</NoteTitle>

<LanguageSpecific to="evitaql,java">

<MDInclude>[List of products sorted by selling price](/documentation/user/en/query/ordering/examples/price/price-natural.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[List of products sorted by selling price](/documentation/user/en/query/ordering/examples/price/price-natural.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[List of products sorted by selling price](/documentation/user/en/query/ordering/examples/price/price-natural.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>