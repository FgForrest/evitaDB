---
title: Price filtering
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

**Work in progress**

This article will contain copy of:

- [currency](https://evitadb.io/research/assignment/querying/query_language#price-in-currency)
- [price lists](https://evitadb.io/research/assignment/querying/query_language#price-in-price-lists)
- [valid in time](https://evitadb.io/research/assignment/querying/query_language#price-valid-in-time)
- [between](https://evitadb.io/research/assignment/querying/query_language#price-between)

once it's proof-read.

<UsedTerms>
    <h4>Terms used in this document</h4>
    <dl>
        <dt>ERP</dt>
        <dd>
            Enterprise resource planning (ERP) is a type of software system that helps
            organizations automate and manage core business processes for optimal performance. ERP software
            coordinates the flow of data between a company’s business processes, providing a single source
            of truth and streamlining operations across the enterprise. It’s capable of linking a company’s
            financials, supply chain, operations, commerce, reporting, manufacturing, and human resources
            activities on one platform.

            [Read more here](https://en.wikipedia.org/wiki/Enterprise_resource_planning)
        </dd>
    </dl>
</UsedTerms>

## Price for sale computation algorithm

The primary source of pricing information is usually a company's
<Term>ERP</Term> system. There is a wide variety of such <Term>ERP</Term> systems,
often specific for the country e-commerce business operates in. These systems have their own ways on how to model and
compute prices and B2B pricing strategies are sometimes very "creative", so to speak.

Price computation logic in evitaDB is designed in a very simplistic way that supports common pricing mechanisms and
allows adaptation, even to uncommon ones.

The structure of the individual price is visible from the
<SourceClass>[PriceContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/PriceContract.java)</SourceClass> interface.

[Search query](query_language) allows looking up for prices:

- with a specific currency
- that are valid in certain moment of time
- that belong to some defined sets or price lists (that the end user has access to)

Processing such a query will result in a list of prices, where multiple ones are assigned to a single product. Prices
belonging to the same product will be sorted according the order of the price lists in the
[priceInPriceList](query_language#price-in-price-lists) constraint used in the query. A sorted list of prices will be
iterated on, taking only the first price for each product and skipping the others until a price for the next product is found.
That means that in every single moment, there could be exactly one price valid for the price list and currency
combination. If this constraint wasn't enforced, the engine wouldn't be able to select an appropriate selling price for
the product.

<Note type="warning">
To avoid price ambiguity, evitaDB builders enforce entities to only have a single price per distinct
price list valid at each time. Even so, the ambiguity might still occur if the entity has two prices
with non-overlapping validity spans and the evitaDB [search query](query_language) lacks the [priceValidIn](query_language#price-valid-in)
constraint that defines the exact time for correct price evaluation. In such situations, the "undefined" price will be
selected from these. That's why you should always provide the correct date and time for a correct price for the sale
resolution (unless you know that there are no time-limited prices in the database).
</Note>

You need to think out carefully how to model price lists and priorities in evitaDB. One of the more intuitive approaches is to convert
price lists from <Term>ERP</Term> (in a 1:1 ratio) to evitaDB. That's usually fine - but <Term>ERP</Term> systems often use prices
computed on the fly according some defined rules. That's not possible to do in evitaDB, and all prices must be
"pre-computed" to a static form. This is necessary in order to search through prices quickly. You might create some so-called
"virtual price lists", that may mimic the <Term>ERP</Term> rule, and maintain all of the computed prices in them.

<Note type="warning">
You also need to pay attention to combination explosion (sometimes also called the [Cartesian product](https://en.wikipedia.org/wiki/Cartesian_product)).
Some business rules may lead to such quantities of possible price combinations that are not be possible to pre-compute
and keep in memory. Let's look at an example:

*Company XYZ has 1 million customers, 1 million products and each customer may have unique discount for the products. A naive
approach to this problem is to compute 1 billion prices (i.e. all possible combinations). A more clever approach is to look
at the discount layout. We may find that there are only several types of discounts: 1%, 2.5%, 5%, 10%. We model our
price lists not by user, but by discount value - i.e. we'd need 4 million pre-computed prices.*
</Note>

## Model examples for standard cases

Let's have the following products:

| Product       | Baseline price | Price list A | Price list B                                                | Price list C |
|---------------|----------------|--------------|-------------------------------------------------------------|--------------|
| Honor 10      | 10000€         |              | 9000€ (valid 1.1.2020 00:00:000 - 31.1.2020 23:59:59)       | 7500€        |
| HUAWEI 20 Pro | 12000€         | 14000€       |                                                             | 8500€        |
| iPhone Xs Max | 21000€         | 23000€       | 19000€ (valid 1.1.2020 01:00:000 - 31.1.2020 22:59:59)      |              |

<Note type="info">

<NoteTitle toggles="true">

#### What if you need to define validity for entire price list?

</NoteTitle>

You may record your `priceList` validity information on a separate evitaDB entity. Then, you might emulate price list
validity on the client side before the price list code array is passed to a [priceInPriceList](query_language#price-in-price-lists)
constraint that is used for fetching products.

Client logic might work as follows: list all user price lists whose validity overlaps an interval from now until +1 hour
ordered by priority attribute descending, cache it for an hour (this could be handled by a single EvitaDB query).

When the `priceInPriceList` constraint is about to be used for listing products, filter all locally cached prices lists by the
validity attribute, using the `isValidFor(OffsetDateTime)` method for the current moment and use it as arguments in the same
order as they were fetched to the cache.
</Note>

These results are expected on the following queries:

#### First query

- **price lists:** `A`, `Baseline` (order of the argument controls price list priority)
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product       | Price for sale |
|---------------|----------------|
| Honor 10      | 10000€         |
| HUAWEI 20 Pro | 14000€         |
| iPhone Xs Max | 23000€         |

The price list `A` has greater priority, and as such, prices from this price list should be used when available. The `Honor 10` doesn't
have a price in that price list, so the `Baseline` price will be used for it.

#### Second query

- **price lists:** `B`, `A`, `Baseline`, `C` (order of the argument controls price list priority)
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product       | Price for sale |
|---------------|----------------|
| Honor 10      | 10000€         |
| HUAWEI 20 Pro | 14000€         |
| iPhone Xs Max | 23000€         |

In this case, price list `B` is the most prioritized price list from the query, but time validity of its prices doesn't match
the constraints, so none of them will be a part of evaluation process. Price list `C` has the lowest priority, so
if there is any price in the price list `Baseline` or price list `A`, it would not be used. That's why the result stays
the same as in first query.

#### Third query

- **price lists:** `B`, `A`, `Baseline`, `C` (order of the argument controls price list priority)
- **valid in:** `2.1.2020 13:00:00`

**Expected result:**

| Product       | Price for sale |
|---------------|----------------|
| Honor 10      | 9000€          |
| HUAWEI 20 Pro | 14000€         |
| iPhone Xs Max | 19000€         |

The price list `B` is the most prioritized price list from this query and the validity of its prices match the query, so they're a
part of the evaluation process. Price list `C` has the lowest priority, so if there is any price in price list
`Baseline` or the price list `A`, it would not be used.

#### Fourth query

- **price lists:** `B`, `A`, `Baseline`, `C` (order of the argument controls price list priority)
- **valid in:** `2.1.2020 13:00:00`
- **price between:** `8000€` and `10000€` (both inclusive)

**Expected result:**

| Product       | Price for sale |
|---------------|----------------|
| Honor 10      | 9000€          |

The query is the same as the third query with the addition of the price range filter. The result only contains the `Honor 10`
product, which is the single product with matching price. The other products have prices that would match the price
range in other price lists  - but those prices were not chosen as the price for sale, and thus cannot be considered in the
price range predicate.

## Product variants extension

<Term location="docs/research/en/assignment/index.md" name="product-with-variants">Product with variants</Term> must contain prices of all of its
variants. Variant prices needs to be differentiated by the inner entity id property (see interface
<SourceClass>[PriceContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/PriceContract.java)</SourceClass>.
The product with variants must have a price inner entity reference handling mode
<SourceClass>[PriceInnerRecordHandlingMutation.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/mutation/price/PriceInnerRecordHandlingMutation.java)</SourceClass>
set to `FIRST_OCCURRENCE`.

In this setup product, the price for sale will be selected as the smallest price for sale of all variants. This price will
be used for filtering products by price.

The entity will also provide computed prices for each of the product variants, selecting the first price ordered by priority
distinctively for each inner entity identifier. This information can be used to display price span for the product
with variants (i.e. price from `5€` to `6.5€`) or to compute an average price for sale of all the product variants.

### Model example

Let's have the following products:

| Product        | Master product    | Baseline price | Price list A | Price list B                                        | Price list C |
|----------------|-------------------|----------------|--------------|-----------------------------------------------------|--------------|
| Variant: blue  | T-Shirt I Rock    | 10€            |              | 9€ (valid 1.1.2020 00:00:000 - 31.1.2020 23:59:59)  | 7.5€         |
| Variant: red   | T-Shirt I Rock    | 12€            | 14€          |                                                     | 8.5€         |
| Variant: green | T-Shirt I Rock    | 21€            | 23€          | 19€ (valid 1.1.2020 01:00:000 - 31.1.2020 22:59:59) |              |
| Variant: blue  | Jumper X-Mas Deer | 26€            |              | 19€ (valid 1.1.2020 02:00:000 - 31.1.2020 21:59:59) | 9€           |
| Variant: red   | Jumper X-Mas Deer | 26€            | 22€          |                                                     | 9€           |
| Variant: green | Jumper X-Mas Deer | 26€            | 21€          | 18€ (valid 1.1.2020 03:00:000 - 31.1.2020 20:59:59) |              |

These results are expected on the following queries:

#### First query

- **price lists:** `Baseline`
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product           | Price for sale  |
|-------------------|-----------------|
| T-Shirt I Rock    | from 10€ to 21€ |
| Jumper X-Mas Deer | 26€             |

the `Jumper X-Mas Deer` has a single price for sale because all of its variants in the `Baseline` price list share the same
price. The `T-Shirt I Rock` needs to signalize that its cheapest price is `10€` and its most expensive price is
`21€`.

#### Second query

- **price lists:** `B`, `Baseline`, `C` (order of the argument controls price list priority)
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product           | Price for sale  |
|-------------------|-----------------|
| T-Shirt I Rock    | from 10€ to 21€ |
| Jumper X-Mas Deer | 26€             |

The result in this query remains the same as it was in the first query. Price list `B` prices cannot be used because their
validity span is not valid and price list `C` has the lowest priority and `Baseline` prices will take over it.

#### Third query

- **price lists:** `B`, `A`, `Baseline`, `C` (order of the argument controls price list priority)
- **valid in:** `2.1.2020 13:00:00`

**Expected result:**

| Product           | Price for sale  |
|-------------------|-----------------|
| T-Shirt I Rock    | from 9€ to 19€  |
| Jumper X-Mas Deer | from 18€ to 22€ |

The result in this query will be determined by prices from price list `B`, which are all now valid. Price list `C` has
the lowest priority and won't be used at all. The `Baseline` price list wouldn't be used either, because all products
have some price in one of the more prioritized price lists - i.e. price list `A` and `B`.

#### Fourth query

- **price lists:** `B`, `A`, `Baseline`, `C` (order of the argument controls price list priority)
- **valid in:** `2.1.2020 13:00:00`
- **price range between:** `8€` and `11€` (both inclusive)

**Expected result:**

| Product           | Price for sale  |
|-------------------|-----------------|
| T-Shirt I Rock    | from 9€ to 19€  |

The query is the same as the third query with the addition of the price range filter. The result contains only
the `T-Shirt I Rock` product, which has at least one variant with the price for sale matching the price range.
The `Jumper X-Mas Deer` has the price that would match the price range in other price lists - but those prices were
not selected as the selling price, and thus cannot be considered in the price range filter.

## Product sets extension

<Term location="docs/research/en/assignment/index.md" name="product set">Product set</Term> must contain prices of all its components. Component
prices needs to be differentiated by the inner entity id property (see interface
<SourceClass>[PriceContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/PriceContract.java)</SourceClass>. The product set
must have the price inner entity reference handling mode
<SourceClass>[PriceInnerRecordHandlingMutation.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/mutation/price/PriceInnerRecordHandlingMutation.java)</SourceClass> set
to `SUM`.

In this setup, the product price for sale will be computed on the fly as a sum of the prices for sale of all its components.
This aggregated price will be used for filtering products by price.

<Note type="warning">
If the component has no price for sale for the passed query, the product set price for sale is computed
without this particular component.
</Note>

### Model example

Let's have the following products:

| Product             | Product set | Baseline price | Price list A | Price list B                                         | Price list C |
|---------------------|-------------|----------------|--------------|------------------------------------------------------|--------------|
| Frame               | Drawer      | 100€           |              | 90€ (valid 1.1.2020 00:00:000 - 31.1.2020 23:59:59)  | 75€          |
| Set of knobs        | Drawer      | 120€           | 140€         |                                                      | 85€          |
| Hinges              | Drawer      | 210€           | 230€         | 190€ (valid 1.1.2020 01:00:000 - 31.1.2020 22:59:59) |              |
| Head/footboard slat | Bed         | 260€           |              | 190€ (valid 1.1.2020 02:00:000 - 31.1.2020 21:59:59) | 90€          |
| Torso               | Bed         | 260€           | 220€         |                                                      | 90€          |
| Drawers             | Bed         | 260€           | 210€         | 180€ (valid 1.1.2020 03:00:000 - 31.1.2020 20:59:59) |              |

These results are expected on following queries:

#### First query

- **price lists:** `Baseline`
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product | Price for sale            |
|---------|---------------------------|
| Drawer  | 430€ (100€ + 120€ + 210€) |
| Bed     | 780€ (260€ + 260€ + 260€) |

Product sets have their price for sale composed of the sum of prices for sale of their parts.

#### Second query

- **price lists:** `B`, `A`, `Baseline`, `C` (order of the argument controls price list priority)
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product | Price for sale            |
|---------|---------------------------|
| Drawer  | 470€ (100€ + 140€ + 230€) |
| Bed     | 690€ (260€ + 220€ + 210€) |

The price list `B` cannot be used in computation, because validity constraint of none of its prices is met. The price
list `C` would be used, if there are no prices in the `Baseline` price list or the price list `A`, which is not
fulfilled as well. Price for sale will be computed as a sum of the `Baseline` prices and prices from the price list `A`.

#### Third query

- **price lists:** `B`, `A`, `Baseline`, `C` (order of the argument controls price list priority)
- **valid in:** `2.1.2020 13:00:00`

**Expected result:**

| Product | Price for sale            |
|---------|---------------------------|
| Drawer  | 420€ (90€ + 140€ + 190€)  |
| Bed     | 590€ (190€ + 220€ + 180€) |

The price list `B` can be used now, because its validity constraint is fulfilled by the prices of this price list.
Those parts that don't have a price in the price list `B` will use their price from a second price list with the greatest
priority - which is the price list `A`.

#### Fourth query

- **price lists:** `B`, `A`, `Baseline`, `C` (order of the argument controls price list priority)
- **valid in:** `2.1.2020 13:00:00`
- **price between:** `0€` and `500€`

**Expected result:**

| Product | Price for sale            |
|---------|---------------------------|
| Drawer  | 420€ (90€ + 140€ + 190€)  |

The query is the same as the third query with the addition of a price range filter. The result only contains the `Drawer`
product, whose sum of price for sale of its parts is in the specified range.