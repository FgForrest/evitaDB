---
title: Price
date: '9.6.2025'
perex: |
  This chapter covers handling prices with or without tax for B2C and B2B scenarios, and how to set default price list priorities for consistent pricing across your queries.
  
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

## Price type

```evitaql-syntax
priceType(
    argument:enum(WITH_TAX|WITHOUT_TAX)!
)
```

<dl>
    <dt>argument:enum(WITH_TAX|WITHOUT_TAX)!</dt>
    <dd>
        selection of the type of price that should be taken into account when calculating the selling price and
        filtering or sorting by it
    </dd>
</dl>

In B2C scenarios, prices are generally displayed with tax included to give consumers the total purchase cost upfront,
adhering to retail standards and regulations. For the B2B subset, displaying prices without tax is critical as it
aligns with their financial processes and allows them to manage tax reclaim separately.

That's why we need to control what type of price we're working with in our queries, since it would produce different
results for different settings. The [`priceType`](../requirements/price.md#price-type) requirement allows us to do this.

The <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/PriceType.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/PriceType.cs</SourceClass></LS> requirement
controls which price type is used when calculating the sales price and filtering or sorting by it. If no such
requirement is specified, **the price with tax is used by default**.

To demonstrate the effect of this requirement, let's say the user wants to find all products with a selling price
between `€100` and `€105`. The following query will do that:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Example query to filter products with price between `€100` and `€105`](/documentation/user/en/query/requirements/examples/price/price-type.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Results filtered by price between €100 and €105

</NoteTitle>

The result contains some products, which you can see in the following table:

<LS to="e,j,c">

<MDInclude>[Results filtered by price between €100 and €105](/documentation/user/en/query/requirements/examples/price/price-type.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Results filtered by price between €100 and €105](/documentation/user/en/query/requirements/examples/price/price-type.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Results filtered by price between €100 and €105](/documentation/user/en/query/requirements/examples/price/price-type.rest.json.md)</MDInclude>

</LS>

</Note>

But if the user is a legal entity and can subtract the sales tax from the price, he probably wants to find all products
in this range with the price without tax. To do this, we need to modify the query and add the `priceType` requirement:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Example query to filter products with price between `€100` and `€105` without tax](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Different results filtered by price between €100 and €105 without tax

</NoteTitle>

And now the result contains completely different products (in the output we show the price with tax to demonstrate the
difference - in regular UI you'd choose to show the price without tax) with more appropriate price for particular user:

<LS to="e,j,c">

<MDInclude>[Different results filtered by price between €100 and €105 without tax](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Different results filtered by price between €100 and €105 without tax](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Different results filtered by price between €100 and €105 without tax](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.rest.json.md)</MDInclude>

</LS>

</Note>

## Default accompanying price lists

```evitaql-syntax
defaultAccompanyingPriceLists(
    argument:string+
)
```

<dl>
    <dt>argument:string+</dt>
    <dd>
        A mandatory specification of one or more price list names in order of priority from most preferred to least
        preferred.
    </dd>
</dl>

The <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/DefaultAccompanyingPrice.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/DefaultAccompanyingPrice.cs</SourceClass></LS> requirement
defines prioritized list of price list names that will be used for all [`accompanyingPriceContent`](fetching.md#accompanying-price-content)
requirements that don't specify their own price list sequence. This is useful when you want to specify a default rule
at the top level of your query, so that you don't have to repeat the same price list sequence in every 
[`accompanyingPriceContent`](fetching.md#accompanying-price-content) nested in [`entityFetch`](fetching.md#entity-fetch) containers.