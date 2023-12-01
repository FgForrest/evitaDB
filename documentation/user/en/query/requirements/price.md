---
title: Price
date: '7.11.2023'
perex: |
  In B2C scenarios, prices are generally displayed with tax included to give consumers the total purchase cost upfront, 
  adhering to retail standards and regulations. For the B2B subset, displaying prices without tax is critical as it 
  aligns with their financial processes and allows them to manage tax reclaim separately.
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

That's why we need to control what type of price we're working with in our queries, since it would produce different 
results for different settings. The [`priceType`](../requirements/price.md#price-type) requirement allows us to do this.

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

<LanguageSpecific to="java,evitaql,rest,graphql">
The <SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/PriceType.java</SourceClass> requirement 
controls which price type is used when calculating the sales price and filtering or sorting by it. If no such 
requirement is specified, **the price with tax is used by default**.
</LanguageSpecific>
<LanguageSpecific to="csharp">
The <SourceClass>EvitaDB.Client/Queries/Requires/PriceType.cs</SourceClass> requirement 
controls which price type is used when calculating the sales price and filtering or sorting by it. If no such 
requirement is specified, **the price with tax is used by default**.
</LanguageSpecific>


To demonstrate the effect of this requirement, let's say the user wants to find all products with a selling price 
between `€100` and `€105`. The following query will do that:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Example query to filter products with price between `100€` and `105€`](/documentation/user/en/query/requirements/examples/price/price-type.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Results filtered by price between €100 and €105

</NoteTitle>

The result contains some products, which you can see in the following table:

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[Results filtered by price between 100€ and 105€](/documentation/user/en/query/requirements/examples/price/price-type.evitaql.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="graphql">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Results filtered by price between 100€ and 105€](/documentation/user/en/query/requirements/examples/price/price-type.graphql.json.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="rest">

<MDInclude sourceVariable="recordPage">[Results filtered by price between 100€ and 105€](/documentation/user/en/query/requirements/examples/price/price-type.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

But if the user is a legal entity and can subtract the sales tax from the price, he probably wants to find all products
in this range with the price without tax. To do this, we need to modify the query and add the `priceType` requirement:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Example query to filter products with price between `100€` and `105€` without tax](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Different results filtered by price between €100 and €105 without tax

</NoteTitle>

And now the result contains completely different products (in the output we show the price with tax to demonstrate the 
difference - in regular UI you'd choose to show the price without tax) with more appropriate price for particular user:

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[Different results filtered by price between 100€ and 105€ without tax](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.evitaql.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="graphql">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Different results filtered by price between 100€ and 105€ without tax](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.graphql.json.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="rest">

<MDInclude sourceVariable="recordPage">[Different results filtered by price between 100€ and 105€ without tax](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>
