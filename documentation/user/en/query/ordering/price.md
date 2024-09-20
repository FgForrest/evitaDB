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

To sort products by their selling price (currently considering only `basic` price list and `EUR`), we can use
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

## Price discount

```evitaql-syntax
priceDiscount(
    argument:enum(ASC|DESC)?
    argument:string+
)
```

<dl>
    <dt>argument:enum(ASC|DESC)?</dt>
    <dd>
        the ordering direction (ascending or descending), **default value** is `DESC`
    </dd>
    <dt>argument:string+</dt>
    <dd>
        A mandatory specification of one or more price list names in order of priority from most preferred to least
        preferred from which the reference price should be taken into account for the discount calculation.
    </dd>
</dl>


The <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/order/PriceDiscount.java</SourceClass></LS> constraint
allows output entities to be sorted by the difference between their [selling price](../filtering/price.md#price-for-sale-computation-algorithm)
and any calculated alternative, in their natural numeric order. It requires the definition of the prioritized set 
of price list names that define the rules for calculating the alternative price and optionally the order direction 
(default is `DESC`). The price variant (with or without tax) is determined by the [`priceType`](../requirements/price.md#price-type) 
requirement of the query (price with tax is used by default).

<Note type="warning">

<NoteTitle toggles="true">

##### Do prices have to be indexed to calculate the discount?

</NoteTitle>

Yes, prices must be indexed in the database in order to sort products by discount amount. Non-indexed prices are 
accessible only when the entity body is fetched from disk, which would be very inefficient for sorting large datasets. 
Therefore, even the "non-sellable" prices must be indexed and kept in memory indexes to be able to calculate the discount 
amount in an efficient way.

</Note>

To sort products by their discount amount (i.e. to compare how much discount you'll get with `b2b-basic-price` compared
to `basic` price list and `EUR`), we can use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[List products with largest discount first](/documentation/user/en/query/ordering/examples/price/price-discount.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List products with largest discount first
</NoteTitle>

<LS to="e,j">

<MDInclude>[List products with largest discount first](/documentation/user/en/query/ordering/examples/price/price-natural.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[List products with largest discount first](/documentation/user/en/query/ordering/examples/price/price-natural.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[List products with largest discount first](/documentation/user/en/query/ordering/examples/price/price-natural.rest.json.md)</MDInclude>

</LS>

</Note>
