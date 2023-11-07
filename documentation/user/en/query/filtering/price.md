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
to filter products by price.

## Quick guide to filtering by price

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

### Price for sale selection in a nutshell

<Note type="warning">

If you find any statements in this chapter unclear, please read the [price for sale calculation algorithm documentation](/documentation/user/en/deep-dive/price-for-sale-calculation.md), where you will find more examples and step-by-step 
breakdown of the procedure.

</Note>

The price for sale selection depends on <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceInnerRecordHandling.java</SourceClass>
mode the [entity has set](../../use/data-model.md#entity), but for all of the modes there is a common denominator - 
the price for sale is selected from [prices](../../use/data-model.md#prices) marked as `sellable` that conform to
the selected [currency](#price-in-currency) and [price lists](#price-in-price-lists) and are valid at the specified
[time](#price-valid-in). The first price that matches all of these criteria in the order of the price lists in the
[price list constraint argument](#price-in-price-lists) is selected as the price for sale.

For non-default price inner record handling modes, the price is calculated this way:

<dl>
   <dt>FIRST_OCCURRENCE</dt>
   <dd>
      The sales price is selected as the lowest sales price calculated separately for blocks of prices with the same 
      inner record id. If the [price between](#price-between) constraint is specified, the price is the lowest selling
      price valid for the specified price range.
   </dd>
   <dt>SUM</dt>
   <dd>
      The sales price is calculated as the sum of the sales prices calculated separately for blocks of prices with 
      the same inner record ID. If the [price between](#price-between) constraint is specified, the sales price is 
      valid only if the total is within the specified price range.
   </dd>
</dl>

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

        The currency code must be [three-letter code according to ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
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

The result set contains only products that have at least one price in EUR currency:

<MDInclude>[The result of listing products with any price in EUR currency](/documentation/user/en/query/filtering/examples/price/price-in-currency.evitaql.md)</MDInclude>

</Note>

## Price in price lists

```evitaql-syntax
priceInPriceLists(
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

The <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/PriceInPriceLists.java</SourceClass> constraint
defines the allowed set(s) of price lists that the entity must have to be included in the result set. The order of 
the price lists in the argument is important for the final price for sale calculation - see the 
[price for sale calculation algorithm documentation](/documentation/user/en/deep-dive/price-for-sale-calculation.md). 
Price list names are represented by plain [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
and are case-sensitive. Price lists don't have to be stored in the database as an entity, and if they are, they are not 
currently associated with the price list code defined in the prices of other entities. The pricing structure is simple 
and flat for now (but this may change in the future).

Except for the [standard use-case](#typical-usage-of-price-constraints) you can also create query with this constraint 
only:

<SourceCodeTabs langSpecificTabOnly>

[Listing products with any ani VIP price lists](/documentation/user/en/query/filtering/examples/price/price-in-price-lists.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of listing products with any price in one of the VIP price lists

</NoteTitle>

The result set contains only products that have at least one price in one of the VIP price lists mentioned:

<MDInclude>[The result of listing products with any price in one of the VIP price lists](/documentation/user/en/query/filtering/examples/price/price-in-price-lists.evitaql.md)</MDInclude>

</Note>

## Price valid in

```evitaql-syntax
priceValidIn(
    argument:offsetDateTime!
)
```

<dl>
    <dt>argument:offsetDateTime!</dt>
    <dd>
        A mandatory argument of date and time (with offset) in the format 'yyyy-MM-ddTHH:mm:ssXXX', for example
        `2007-12-03T10:15:30+01:00`. In Java language you can use directly [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html)
    </dd>
</dl>

The <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/PriceValidIn.java</SourceClass> excludes all 
entities that don't have a valid price for sale at the specified date and time. If the price doesn't have a validity 
property specified, it passes all validity checks.

To demonstrate the effect of validity constraints, let's create a query that lists products in the *Christmas 
Electronics* category and tries to access prices in their *Christmas Price List*, with a fallback to the *Basic Price 
List*, using a spring holiday date and time as the reference point for the price validity check:

<SourceCodeTabs langSpecificTabOnly>

[Listing products with Christmas prices in May](/documentation/user/en/query/filtering/examples/price/price-valid-in.evitaql)

</SourceCodeTabs>

Now let's update the query to use a date and time in December:

<SourceCodeTabs langSpecificTabOnly>

[Listing products with Christmas prices in December](/documentation/user/en/query/filtering/examples/price/price-valid-in-correct.evitaql)

</SourceCodeTabs>

As you can see, you'll get a somewhat different sale price because the Christmas prices have now been applied:

<MDInclude>[Compare December prices with May prices](/documentation/user/en/query/filtering/examples/price/price-valid-in-correct.evitaql.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Compare December prices with May prices

</NoteTitle>

The prices for the sale in May were different, because the Christmas prices were not valid at that time:

<MDInclude>[Compare December prices with May prices](/documentation/user/en/query/filtering/examples/price/price-valid-in.evitaql.md)</MDInclude>

</Note>

## Price valid in now

```evitaql-syntax
priceValidInNow()
```

This is the variant of the [`priceValidIn`](#price-valid-in) constraint that uses the current date and time as the
reference point for the price validity check. The [`priceValidIn`](#price-valid-in) constraint allows you to specify
any date and time in the future or in the past as the reference point.

## Price between

```evitaql-syntax
priceBetween(
    argument:bigDecimal!,
    argument:bigDecimal!
)
```

<dl>
    <dt>argument:bigDecimal!</dt>
    <dd>
        A mandatory argument of the price range lower bound. The price range is inclusive, so the price must be greater
        than or equal to the lower bound. In the Java language you can use directly [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) in plain text format, you must use the string representation of the number. 
    </dd>
    <dt>argument:bigDecimal!</dt>
    <dd>
        A mandatory argument of the price range upper bound. The price range is inclusive, so the price must be lesser
        than or equal to the upper bound. In the Java language you can use directly [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) in plain text format, you must use the string representation of the number. 
    </dd>
</dl>

The <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/PriceBetween.java</SourceClass> constraint 
restricts the result set to items that have a price for sale within the specified price range. This constraint is 
typically set by the user interface to allow the user to filter products by price, and should be nested inside 
the [`userFilter`](behavioral.md#user-filter) constraint container so that it can be properly handled by 
the [facet](../requirements/facet.md) or [histogram](../requirements/histogram.md) computations.

To demonstrate the price range constraint, let's create a query that lists products in the *E-readers* category and
filters only those between `150€` and `170.5€`:

<SourceCodeTabs langSpecificTabOnly>

[Listing E-readers with price between `150€` and `170.5€`](/documentation/user/en/query/filtering/examples/price/price-between.evitaql)

</SourceCodeTabs>

The range is quite narrow, so the result set contains only a single product:

<MDInclude>[Compare December prices with May prices](/documentation/user/en/query/filtering/examples/price/price-between.evitaql.md)</MDInclude>