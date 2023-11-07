---
title: Price filtering
date: '7.11.2023'
perex: |
  In the realm of e-commerce, users expect to see prices that are personalized to their context: local currency for easy 
  understanding, accurate selling prices from the correct price list, and timely offers that may only be valid during 
  specific periods. Catering to these expectations with sophisticated database filtering not only enhances user 
  experience but also streamlines the shopping process, boosting satisfaction and sales.
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

This chapter contains the description of evitaDB constraints that help you to control the price for sale selection and 
to filter products by price. It is strongly recommended to read the [price for sale calculation algorithm](/documentation/user/en/deep-dive/price-for-sale-calculation.md) first.

### Typical usage of price constraints

In most scenarios, your query for entities with prices will look like this:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Listing products with valid EUR price](/documentation/user/en/query/filtering/examples/price/price.evitaql)

</SourceCodeTabs>

To correctly identify an appropriate selling price, you must specify all three constraints in logical disjunction:

1. [`priceInCurrency`](#price-in-currency) - the currency of the price for sale
2. [`priceValidIn`](#price-valid-in) - the date and time at which the price for the sale must be valid
3. [`priceInPriceLists`](#price-in-price-lists) - the set of price lists for which the customer is eligible, sorted 
   from most preferred to least preferred.

<Note type="warning">

Only a single occurrence of any of these three constraints is allowed in the filter part of the query. Currently, there
is no way to switch context between different parts of the filter and build queries such as *find a product whose price
is either in "CZK" or "EUR" currency at this or that time* using this constraint.

While it's technically possible to implement support for these tasks in evitaDB, they represent edge cases and there 
were more important scenarios to handle. Multiple combinations of these constraints will effectively stop finding 
a correct selling price and would only allow returning matching entities without a selling price.

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### The result of listing products with valid EUR price

</NoteTitle>

The result set contains only products that have a valid price for sale in EUR currency:

<MDInclude>[The result of listing products with valid EUR price](/documentation/user/en/query/filtering/examples/price/price.evitaql.md)</MDInclude>

</Note>

## Price in currency

```evitaql-syntax
priceInCurrency(
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        A mandatory specification of the currency to which all prices targeted by the query must conform.

        The currency code must be [three-letter code according to ISO 4217] (https://en.wikipedia.org/wiki/ISO_4217).
    </dd>
</dl>

<LanguageSpecific to="java">

If you are working with evitaDB in Java, you can use [`Currency`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html) 
instead of the [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) ISO code. 
This is a natural way to work with locale specific data on the platform.

</LanguageSpecific>

The <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/PriceInCurrency.java</SourceClass> constraint
can be used to limit the result set to entities that have a price in the specified currency. Except for the [standard
use-case](#typical-usage-of-price-constraints) you can also create query with this constraint only:

<SourceCodeTabs langSpecificTabOnly>

[Listing products with any price in EUR currency](/documentation/user/en/query/filtering/examples/price/price-in-currency.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of listing products with any price in EUR currency

</NoteTitle>

The result set contains only products that have a valid price for sale in EUR currency:

<MDInclude>[The result of listing products with any price in EUR currency](/documentation/user/en/query/filtering/examples/price/price-in-currency.evitaql.md)</MDInclude>

</Note>

## Price in price lists

## Price valid in

## Price between

### Price for sale computation algorithm

