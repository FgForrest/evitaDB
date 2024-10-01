---
title: Price for sale computation algorithm
date: '7.11.2023'
perex: |
  This chapter details the algorithm behind sale price calculation, exploring how it incorporates factors such as 
  currency selection, applicable discounts, and price list selection based on user context. We'll walk through the logic
  with code snippets and real-world scenarios, providing a clear understanding of how the algorithm functions to 
  accurately compute sale prices in a dynamic e-commerce environment.
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

<UsedTerms>
    <h4>Terms used in this document</h4>
    <dl>
        <dt>ERP</dt>
        <dd>
            Enterprise Resource Planning (ERP) is a type of software system that helps organizations automate and manage
            core business processes for optimal performance. ERP software coordinates the flow of data between an 
            organization's business processes, providing a single source of truth and streamlining operations across 
            the enterprise. It's capable of linking a company's financial, supply chain, operations, trading, reporting, 
            manufacturing, and human resources activities on one platform.

            [Read more here](https://en.wikipedia.org/wiki/Enterprise_resource_planning)
        </dd>
        <dt>product</dt>
		<dd>A product is an entity that represents the item sold at an e-commerce store. The products represent the very core of each e-commerce
			application.</dd>	
		<dt>product with variants</dt>
		<dd>A product with a variant is a "virtual product" that cannot be bought directly. A customer must choose one of its variants
			instead. Products with variants are very often seen in e-commerce fashion stores where clothes come in various sizes
			and colors. A single product can have dozens combinations of size and color. If each combination represented standard
			[product](#product), a product listing in [a category](#category) and other places would become unusable.
			In this situation, products with variants become very handy. This &quot;virtual product&quot; can be listed instead of variants
			and a variant selection is performed at the time of placing the goods into the [cart](#cart). Let's have an example:			
			We have a T-Shirt with a unicorn picture on it. The T-Shirt is produced in different sizes and colors - namely:<br/><br/>
			&ndash; size: S, M, L, XL, XXL<br/>
			&ndash; color: blue, pink, violet<br/><br/>			
			That represents 15 possible combinations (variants). Because we only want a single unicorn T-Shirt in our listings, we
			create a product with variants and enclose all variant combinations to this virtual product.</dd>
		<dt>product set</dt>
		<dd>A product set is a product that consists of several sub products, but is purchased as a whole. A real life example of such
			product set is a drawer - it consists of the body, the door and handles. A customer could even choose which type of doors
			or handles they want in the set - but there always be some defaults.<br/>			
			When displaying and filtering by a product set in the listings on the e-commerce site, we need some price assigned for it
			but there may be a none exact price assigned to the set and the e-commerce owner expects that price would be computed
			as an aggregation of the prices of sub-products. This behaviour is supported by setting proper
			[PriceInnerEntityReferenceHandling](classes/price_inner_entity_reference_handling).</dd>
    </dl>

</UsedTerms>

The primary source of pricing information is typically a company's <Term>ERP</Term> system. There is a wide variety of
such <Term>ERP</Term> systems, often specific to the country in which the e-commerce business operates. These systems
have their own ways of modeling and calculating prices, and B2B pricing strategies are sometimes very "creative".

The price calculation logic in evitaDB is designed in a very simple way that supports common pricing mechanisms and
allows adaptation to even uncommon ones.

The structure of the individual price is defined by the
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass> interface.

The [query](../query/basics.md) allows you to search for prices:

- in a specific currency ([`priceInCurrency`](../query/filtering/price.md#price-in-currency))
- that are valid at a certain time ([`priceValidIn`](../query/filtering/price.md#price-valid-in))
- that belong to some defined sets or price lists to which the end user has
  access ([`priceInPriceLists`](../query/filtering/price.md#price-in-price-lists))

Processing such a query will result in a list of prices, where multiple prices are assigned to a single product. Prices
belonging to the same product will be sorted according to the order of the price lists in
the ([`priceInPriceLists`](../query/filtering/price.md#price-in-price-lists)) constraint used in the query. A sorted
price list is iterated on, taking only the first price for each product and skipping the others until a price is found
for the next product. This means that at any given moment, there could be exactly one price valid for the combination
of price list and currency. If this constraint were not enforced, the engine would not be able to select an appropriate
selling price for the product.

<Note type="warning">

To avoid price ambiguity, evitaDB builders force entities to have only one valid price per different price list.
However, ambiguity can still occur if the entity has two prices with non-overlapping validity spans and the evitaDB
[query](../query/basics.md) is missing the [`priceValidIn`](../query/filtering/price.md#price-valid-in) constraint that
defines the exact time for the correct price evaluation. In such situations, the "undefined" price will be selected.
That's why you should always specify the correct date and time for a correct price for the sales resolution (unless you
know that there are no time-dependent prices in the database).

</Note>

You need to think carefully about how to model price lists and priorities in evitaDB. One of the more intuitive
approaches is to convert price lists from <Term>ERP</Term> (in a 1:1 ratio) to evitaDB. That's usually fine - but
<Term>ERP</Term> systems often use prices that are calculated on the fly according to some defined rules. This isn't
possible in evitaDB and all prices have to be "pre-calculated" in a static form. This is necessary to be able to search
prices quickly. You can create some so-called "virtual price lists", which mimic the <Term>ERP</Term> rule and keep all
calculated prices in them.

<Note type="warning">

You also need to pay attention to combination explosion (sometimes called
[Cartesian product](https://en.wikipedia.org/wiki/Cartesian_product)). Some business rules can result in such a large
number of possible price combinations that it is impossible to precompute and store them in memory. Let's look at an
example:

*Company XYZ has 1 million customers, 1 million products, and each customer may have a unique discount for the products.
A naive approach to this problem is to compute 1 billion prices (i.e. all possible combinations). A smarter approach is
to look at the discount layout. We may find that there are only a few types of discounts: 1%, 2.5%, 5%, 10%. We don't
model our price lists by user, but by discount value - so we'd need 4 million pre-calculated prices*.

</Note>

## Model examples for standard cases

Let's have the following products:

| Product       | Baseline price | Price list A | Price list B                                               | Price list C |
|---------------|----------------|--------------|------------------------------------------------------------|--------------|
| Honor 10      | €10000         |              | €9000<br/>(valid 1.1.2020 00:00:000 - 31.1.2020 23:59:59)  | €7500        |
| HUAWEI 20 Pro | €12000         | €14000       |                                                            | €8500        |
| iPhone Xs Max | €21000         | €23000       | €19000<br/>(valid 1.1.2020 01:00:000 - 31.1.2020 22:59:59) |              |

<Note type="info">

<NoteTitle toggles="true">

##### What if you need to define validity for the entire price list?

</NoteTitle>

You can store your `priceList` validity information in a separate evitaDB entity. Then you could emulate the price list
validity on the client side before the price list code array is passed to
the [`priceInPriceLists`](../query/filtering/price.md#price-in-price-lists) constraint that is used to retrieve
products.

Client logic could work as follows: list all user price lists whose validity overlaps an interval from now to +1 hour,
ordered by priority/order attribute, cache for one hour (this could be handled by a single EvitaDB query).

When the `priceInPriceLists` constraint is about to be used for listing products, filter all locally cached price lists
by the validity attribute using the `isValidFor(OffsetDateTime)` method for the current moment and use them as arguments
in the same order as they were fetched to the cache.

</Note>

These results are expected for the following queries:

#### First query

- **price lists:** `A`, `Baseline` (argument order controls price list priority)
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product       | Price for sale |
|---------------|----------------|
| Honor 10      | €10000         |
| HUAWEI 20 Pro | €14000         |
| iPhone Xs Max | €23000         |

The `A` price list has higher priority, and as such, prices from this price list should be used when available.
The `Honor 10` doesn't have a price in this has no price in this price list, so the `Baseline` price will be used for
it.

#### Second query

- **price lists:** `B`, `A`, `Baseline`, `C` (argument order controls price list priority)
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product       | Price for sale |
|---------------|----------------|
| Honor 10      | €10000         |
| HUAWEI 20 Pro | €14000         |
| iPhone Xs Max | €23000         |

In this case, price list `B` is the most prioritized price list from the query, but the time validity of its prices
doesn't match the constraints, so none of them will be part of the evaluation process. Price list `C` has the lowest
priority, so if there is any price in price list `Baseline` or price list `A`, it would not be used. That's why the
result is the same as in the first query.

#### Third query

- **price lists:** `B`, `A`, `Baseline`, `C` (argument order controls price list priority)
- **valid in:** `2.1.2020 13:00:00`

**Expected result:**

| Product       | Price for sale |
|---------------|----------------|
| Honor 10      | €9000          |
| HUAWEI 20 Pro | €14000         |
| iPhone Xs Max | €19000         |

Pricelist `B` is the highest priority pricelist from this request, and the validity of its prices matches the request,
so it's part of the evaluation process. Price list `C` has the lowest priority, so if there is a price in price list
`Baseline` or price list `A`, it would not be used.

#### Fourth query

- **price lists:** `B`, `A`, `Baseline`, `C` (argument order controls price list priority)
- **valid in:** `2.1.2020 13:00:00`
- **price between:** `€8000` and `€10000` (both inclusive)

**Expected result:**

| Product  | Price for sale |
|----------|----------------|
| Honor 10 | €9000          |

The query is the same as the third query with the addition of the Price Range filter. The result contains only the
`Honor 10` product, which is the only product with a matching price. The other products have prices that would match
the price range in other price lists - but those prices were not selected as the price to sell, so they cannot be used
in the price range predicate.

## Product variants extension

<Term name="product with variants">Product with variants</Term> must contain prices of all its variants. Variant prices
must be distinguished by the inner entity id property (see interface
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>). The product
with variants must have a price inner entity reference handling mode
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceInnerRecordHandling.java</SourceClass>
set to `LOWEST_PRICE`.

In this product setup, the selling price is selected as the lowest selling price of all variants. This price is used to
filter products by price.

The entity will also provide calculated prices for each of the product variants, selecting the first price ranked by
priority for each inner entity identifier. This information can be used to display the price range for the product with
variants (i.e. price from €5 to €6.5) or to calculate an average price for sale of all product variants.

### Model example

Let's have the following products:

| Product        | Master product    | Baseline price | Price list A | Price list B                                            | Price list C |
|----------------|-------------------|----------------|--------------|---------------------------------------------------------|--------------|
| Variant: blue  | T-Shirt I Rock    | €10            |              | €9<br/>(valid 1.1.2020 00:00:000 - 31.1.2020 23:59:59)  | €7.5         |
| Variant: red   | T-Shirt I Rock    | €12            | €14          |                                                         | €8.5         |
| Variant: green | T-Shirt I Rock    | €21            | €23          | €19<br/>(valid 1.1.2020 01:00:000 - 31.1.2020 22:59:59) |              |
| Variant: blue  | Jumper X-Mas Deer | €26            |              | €19<br/>(valid 1.1.2020 02:00:000 - 31.1.2020 21:59:59) | €9           |
| Variant: red   | Jumper X-Mas Deer | €26            | €22          |                                                         | €9           |
| Variant: green | Jumper X-Mas Deer | €26            | €21          | €18<br/>(valid 1.1.2020 03:00:000 - 31.1.2020 20:59:59) |              |

These results are expected for the following queries:

#### First query

- **price lists:** `Baseline`
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product           | Price for sale  |
|-------------------|-----------------|
| T-Shirt I Rock    | from €10 to €21 |
| Jumper X-Mas Deer | €26             |

The `Jumper X-Mas Deer` has a single price for sale because all its variants in the `Baseline` price list have the same
price. The `T-Shirt I Rock` must signal that its cheapest price is `€10` and its most expensive price is `€21`.

#### Second query

- **price lists:** `B`, `Baseline`, `C` (argument order controls price list priority)
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product           | Price for sale  |
|-------------------|-----------------|
| T-Shirt I Rock    | from €10 to €21 |
| Jumper X-Mas Deer | €26             |

The result in this query is the same as in the first query. The prices in price list `B` cannot be used because their
validity period is not valid and price list `C` has the lowest priority and the prices in `Baseline` will override it.

#### Third query

- **price lists:** `B`, `A`, `Baseline`, `C` (argument order controls price list priority)
- **valid in:** `2.1.2020 13:00:00`

**Expected result:**

| Product           | Price for sale  |
|-------------------|-----------------|
| T-Shirt I Rock    | from €9 to €19  |
| Jumper X-Mas Deer | from €18 to €22 |

The result in this query will be determined by prices from price list `B`, all of which are now valid. Price list `C`
has the lowest priority and won't be used at all. The price list `Baseline` wouldn't be used either, because all
products have a price in one of the higher priority price lists - i.e. price lists `A` and `B`.

#### Fourth query

- **price lists:** `B`, `A`, `Baseline`, `C` (argument order controls price list priority)
- **valid in:** `2.1.2020 13:00:00`
- **price range between:** `€8` and `€11` (both inclusive)

**Expected result:**

| Product        | Price for sale |
|----------------|----------------|
| T-Shirt I Rock | from €9 to €19 |

The query is the same as the third query with the addition of the Price Range filter. The result contains only the
product `T-Shirt I Rock`, which has at least one variant with a price that matches the price range.
The `Jumper X-Mas Deer` has a price that would match the price range in other price lists - but those prices were not
selected as the selling price and therefore cannot be considered in the price range filter.

## Product sets extension

<Term name="product set">Product set</Term> must contain prices of all its components. Component prices must be
distinguished by the inner entity id property (see interface
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>.
The product record must have the price inner entity reference handling mode
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceInnerRecordHandling.java</SourceClass>
set to `SUM`.

In this setup, the product sales price is calculated on the fly as the sum of the sales prices of all its components.
This aggregated price is used for filtering products by price.

<Note type="warning">

If the component does not have a sales price for the query passed, the product set sales price is calculated without
that particular component.

</Note>

### Model example

Let's have the following products:

| Product             | Product set | Baseline price | Price list A | Price list B                                             | Price list C |
|---------------------|-------------|----------------|--------------|----------------------------------------------------------|--------------|
| Frame               | Drawer      | €100           |              | €90<br/>(valid 1.1.2020 00:00:000 - 31.1.2020 23:59:59)  | €75          |
| Set of knobs        | Drawer      | €120           | €140         |                                                          | €85          |
| Hinges              | Drawer      | €210           | €230         | €190<br/>(valid 1.1.2020 01:00:000 - 31.1.2020 22:59:59) |              |
| Head/footboard slat | Bed         | €260           |              | €190<br/>(valid 1.1.2020 02:00:000 - 31.1.2020 21:59:59) | €90          |
| Torso               | Bed         | €260           | €220         |                                                          | €90          |
| Drawers             | Bed         | €260           | €210         | €180<br/>(valid 1.1.2020 03:00:000 - 31.1.2020 20:59:59) |              |

These results are expected for the following queries:

#### First query

- **price lists:** `Baseline`
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product | Price for sale            |
|---------|---------------------------|
| Drawer  | €430 (€100 + €120 + €210) |
| Bed     | €780 (€260 + €260 + €260) |

Product sets have a selling price that is the sum of the selling prices of their parts.

#### Second query

- **price lists:** `B`, `A`, `Baseline`, `C` (argument order controls price list priority)
- **valid in:** `1.11.2020 13:00:00`

**Expected result:**

| Product | Price for sale            |
|---------|---------------------------|
| Drawer  | €470 (€100 + €140 + €230) |
| Bed     | €690 (€260 + €220 + €210) |

Price list `B` cannot be used in the calculation because none of its prices meet the validity condition. Price list `C`
would be used if there are no prices in price list `Baseline` or in price list `A`, which is also not fulfilled.
The price for the sale is calculated as the sum of the prices in the `Baseline` price list and the prices in the `A`
price list.

#### Third query

- **price lists:** `B`, `A`, `Baseline`, `C` (argument order controls price list priority)
- **valid in:** `2.1.2020 13:00:00`

**Expected result:**

| Product | Price for sale            |
|---------|---------------------------|
| Drawer  | €420 (€90 + €140 + €190)  |
| Bed     | €590 (€190 + €220 + €180) |

Price list `B` can now be used because its validity constraint is satisfied by the prices in this price list. The parts
that don't have a price in price list `B` will use their price from a second price list with the highest priority -
price list `A`.

#### Fourth query

- **price lists:** `B`, `A`, `Baseline`, `C` (argument order controls price list priority)
- **valid in:** `2.1.2020 13:00:00`
- **price between:** `€0` and `€500`

**Expected result:**

| Product | Price for sale           |
|---------|--------------------------|
| Drawer  | €420 (€90 + €140 + €190) |

The query is the same as the third query with the addition of a price range filter. The result will contain only the
`Drawer` product whose sum of selling price of its parts is in the specified range.
