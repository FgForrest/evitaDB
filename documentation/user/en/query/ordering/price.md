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

<Note type="info">

<NoteTitle toggles="true">

##### How the discount is calculated?

</NoteTitle>

The discount is calculated from the difference between the price for sale and the reference price determined by the prices
set on the entity in the order of the price lists specified in the constraint. The calculation is algorithm is the same
as for the [selling price](/documentation/user/en/deep-dive/price-for-sale-calculation.md) computation - it finds the first
available price in the order of the price lists and uses it as the reference price.

If the reference price is not found, the entity / product is considered not sortable by this constraint and will be 
sorted by the next sort constraint in the query (or by its primary key in ascending order at the end of the list).

However, there are special adjustments for the discount calculation. If the product has a `LOWEST_PRICE` or `SUM` price
inner record handling strategy, the reference price follows the records of the inner records used to select the price 
for sale.

###### Lowest price strategy

The lowest price strategy is typically used for virtual products that represent multiple similar products. In the end, 
only one of the products is selected for sale. When we query products by currency, set of price lists, validity timestamp, 
the virtual product with LOWEST_PRICE strategy manifests itself with sub-product (variant) with the lowest price.
When we calculate the discount, we need to use the price from different price lists, but only the price that refers to 
the same product variant as the price for sale.

Moreover, if we further narrow the query with the [price between constraint](../filtering/price#price-between), 
the representative variant will be the one with the lowest price in the selected price range. In this case, 
the reference price must also be calculated from the different sets of price lists for the same product variant.

###### Sum price strategy

The sum-price strategy is typically used for products that consist of several sub-products (parts). Its sales price is 
calculated as the sum of the prices of the sub-products. If a certain subproduct doesn't have a price in the selected 
price lists, it is excluded from the sum. When we calculate the reference price for this product, we must also omit 
the prices of these sub-products, even if the reference price would be available for them. Otherwise, the calculated 
sum wouldn't be consistent with the sales price.

</Note>

<Note type="warning">

<NoteTitle toggles="true">

##### Do prices have to be indexed to calculate the discount?

</NoteTitle>

Yes, prices must be indexed in the database in order to sort products by discount amount. Non-indexed prices are 
accessible only when the entity body is fetched from disk, which would be very inefficient for sorting large datasets. 
Therefore, even the "non-indexed" prices must be indexed and kept in memory indexes to be able to calculate the discount 
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

<MDInclude>[List products with largest discount first](/documentation/user/en/query/ordering/examples/price/price-discount.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[List products with largest discount first](/documentation/user/en/query/ordering/examples/price/price-discount.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[List products with largest discount first](/documentation/user/en/query/ordering/examples/price/price-discount.rest.json.md)</MDInclude>

</LS>

</Note>
