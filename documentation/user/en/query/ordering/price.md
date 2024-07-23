---
title: Price ordering
date: '2.7.2023'
perex: |
  Ordering by selling price is one of the basic requirements for e-commerce applications. The price ordering constraint
  allows to sort the output entities by their selling price in ascending or descending order.
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
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


The <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/order/PriceNatural.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Order/PriceNatural.cs</SourceClass></LS> constraint
allows output entities to be sorted by their [selling price](../filtering/price.md#price-for-sale-computation-algorithm)
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

<LS to="e,j,c">

<MDInclude>[List of products sorted by selling price](/documentation/user/en/query/ordering/examples/price/price-natural.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[List of products sorted by selling price](/documentation/user/en/query/ordering/examples/price/price-natural.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[List of products sorted by selling price](/documentation/user/en/query/ordering/examples/price/price-natural.rest.json.md)</MDInclude>

</LS>

</Note>