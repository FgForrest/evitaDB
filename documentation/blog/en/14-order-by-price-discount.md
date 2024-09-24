---
title: Introducing the `priceDiscount` Ordering Constraint in evitaDB
perex: |
  We're excited to introduce a powerful new feature in evitaDB — the `priceDiscount` ordering constraint. This feature 
  allows you to sort your product listings based on the discount amount, helping you highlight the best deals to your
  customers. In this post, we'll explore how to use this feature effectively, with real-world examples, practical query
  snippets, and insights into different pricing strategies, including handling products with variants and product sets.
date: '23.09.2024'
author: 'Ing. Jan Novotný'
motive: assets/images/14-price-discount-ordering.png
proofreading: 'done'
---

In the ever-competitive e-commerce landscape, showcasing the best deals to your customers can make all the difference. 
To help you achieve this, we're excited to introduce a powerful new feature in evitaDB—the `priceDiscount` ordering 
constraint. This feature empowers you to sort products based on the discount amount, making it easier to highlight 
significant savings and entice shoppers.

### How the Discount is Calculated

The discount is calculated as the difference between the **selling price** (the price the customer pays) and 
the **reference price** (the original or listed price). Both prices are determined using the same algorithm, 
considering the prioritized price lists you specify and the validity date.

**Calculation Steps:**

1. **Selling Price**: The first valid price found in the `priceInPriceLists` constraint, 
   matching the `priceValidIn` date and currency specified in `priceInCurrency`. 
   Prices are considered in the order of the price lists provided.
2. **Reference Price**: The first valid price found in the price lists specified in the `priceDiscount` constraint,
   matching the same date and currency.
3. **Discount**: Calculated as `Reference Price - Selling Price`.

If a price in a given price list is not available or not valid at the specified time, it is skipped.
The algorithm automatically continues to the next price list in the priority order until it finds a valid price.

<Note type="info">

Non-indexed prices are not part of the calculation. **Prices must be indexed** in evitaDB to be considered in sorting
by discount. This ensures efficient performance, especially with large datasets.

</Note>

**Special Adjustments for Products with Variants or Sets**

- **`LOWEST_PRICE` Strategy**: For products with variants, the discount is calculated based on the variant selected for 
  sale. It's usually the one with the lowest price, or if the `priceBetween` filter is used, the one with the lowest 
  price that still meets the selected price range. The reference price must be from the same variant in different price lists.
- **`SUM` Strategy**: For product sets, the selling price is the sum of the selling prices of all components.
  The reference price is calculated by summing the reference prices of the same components, excluding any components 
  that didn't have a selling price to maintain consistency.

## Implementing `priceDiscount` in Your E-Commerce Solution

Let's look at how you can implement the `priceDiscount` constraint in real-world scenarios, including handling products
with variants and product sets.

### Scenario: Highlighting Top Discounts During a Flash Sale

Suppose you're running a flash sale and want to display products with the highest discounts. You have multiple price lists:

- **"flash-sale"**: Contains special prices for the flash sale.
- **"standard"**: Contains regular prices.
- **"msrp"**: Contains manufacturer suggested retail prices.

You want to show products sorted by the highest discount, calculated between the selling price from the "flash-sale" 
price list and the reference price from the "msrp" price list.

#### Crafting the Query

```evitaql
query(
    collection("Product"),
    filterBy(
        priceInPriceLists("flash-sale", "basic),
        priceInCurrency("USD"),
        priceValidIn(2023-11-07T12:00:00-05:00)
    ),
    orderBy(
        priceDiscount("msrp", "basic")
    ),
    require(
        entityFetch(
            priceContentRespectingFilter("msrp")
        )
    )
)
```

This query filters products that have valid prices in the "flash-sale" price list, priced in USD, and valid at 
the specified time. It orders them by the discount amount compared to the "msrp" price list. If products lack price
in the "msrp" or "flash-sale" price lists, their price from "basic" price list is used instead.

#### How the Algorithm Selects Prices

- **Selling Price**: The algorithm searches the "flash-sale" price list for a valid price at `2023-11-07T12:00:00-05:00`.
  If not found, it tries to find price in "basic" price list and if that is not found, it skips the product entirely.
- **Reference Price**: It looks for a valid price in the "msrp" price list with fallback to "basic" price at the same time.

### Detailed Example: Electronics Store Flash Sale

Imagine you're managing an online electronics store preparing for a 24-hour flash sale. Your products have prices 
in different price lists, some with time-limited validity.

#### Product Data valid on 7 Nov 2023

**Standard Products:**

| Product               | MSRP Price | Basic Price | Flash Sale Price | Flash Sale Price Validity |
|-----------------------|------------|-------------|------------------|---------------------------|
| 4K Smart TV           | $1,000     | $950        | $800             | All day                   |
| Gaming Laptop         | $2,000     | $1,950      | $1,600           | All day                   |
| Bluetooth Speaker     | $100       | $95         | (Not available)  |                           |

**Product with Variants (`LOWEST_PRICE` Strategy):**

| Product                      | Variant | MSRP Price | Basic Price | Flash Sale Price | Flash Sale Price Validity  |
|------------------------------|---------|------------|-------------|------------------|----------------------------|
| Noise-Canceling Headphones   | Black   | $200       | $190        | $150             | Until 13:00                |
|                              | Silver  | $200       | $180        | (Not available)  |                            |
|                              | Gold    | $200       | $170        | (Not available)  |                            |

**Product Set (`SUM` Strategy):**

| Product                | Component     | MSRP Price | Basic Price | Flash Sale Price | Flash Sale Price Validity |
|------------------------|---------------|------------|-------------|------------------|---------------------------|
| Home Theater Bundle    | Soundbar      | $500       | $450        | $400             | Until 13:00               |
|                        | Subwoofer     | $300       | $280        | (Not available)  |                           |
|                        | Rear Speakers | $200       | $190        | $150             | All day                   |

#### Query at 12:00 AM

Using the updated query with `priceInPriceLists("flash-sale", "basic")` and `priceDiscount("msrp", "basic")`
at `priceValidIn(2023-11-07T12:00:00-00:00)`, let's see how prices are selected and discounts calculated.

**Price Selection and Discount Calculation:**

| Product                     | Selling Price                    | Reference Price     | Discount |
|-----------------------------|----------------------------------|---------------------|----------|
| 4K Smart TV                 | $800 (flash-sale)                | $1,000 (msrp)       | $200     |
| Gaming Laptop               | $1,600 (flash-sale)              | $2,000 (msrp)       | $400     |
| Bluetooth Speaker           | $95 (basic)                      | $100 (msrp)         | $5       |
| Noise-Canceling Headphones  | $150 (flash-sale, Black variant) | $200 (msrp)         | $50      |
| Home Theater Bundle         | $830 (components)                | $1,000 (components) | $170     |

<Note type="info">

Discount can never be negative, so if it happens that the selling price is higher than the reference price,
the discount is perceived as $0.

</Note>

**Result Sorting Order:**

1. Gaming Laptop ($400 discount)
2. 4K Smart TV ($200 discount)
3. Home Theater Bundle ($170 discount)
4. Noise-Canceling Headphones ($10 discount)
5. Bluetooth Speaker ($5 discount)

The validity of prices affects both inclusion in results and discount amounts.

#### Query at 2:00 PM

At `priceValidIn(2023-11-07T14:00:00-00:00)`, the "flash-sale" prices for Noise-Canceling Headphones products is no 
longer valid.

**Updated Price Selection and Discount Calculation:**

- **Noise-Canceling Headphones**:
   - **Selling Price**: $170 (basic) - now the Gold variant is the one with the lowest price
   - **Reference Price**: $200 (msrp)
   - **Discount**: $200 - $170 = **$30**
- **Home Theater Bundle:**
   - **Selling Price**: $450 (Soundbar, basic - flash sale price is no longer valid) + $280 (Subwoofer, basic) + $150 (Rear Speakers, flash-sale) = $880
   - **Reference Price**: Remains $1,000 (msrp)
   - **Discount**: $1,000 - $880 = **$120**

<Note type="question">

<NoteTitle>

##### What if there is no MSRP and Basic Price for a component part?

When calculating the reference price for a product set, the algorithm excludes components that don't have a selling price.
But what if a reference price for a component is missing? In such cases, the algorithm uses the selling price as the
component reference price to maintain consistency.

</NoteTitle>


</Note>

## Conclusion

The `priceDiscount` ordering constraint is a powerful tool for enhancing your e-commerce platform. By sorting products 
based on discount amounts, you can effectively promote deals and increase customer engagement. As you can see from 
the detailed examples and edge cases discussed, correctly calculating discounts is not trivial, especially when dealing 
with products with variants and product sets. Different users have access to different prices, the search has to adapt
correctly when the `priceBetween` constraint is used, and may change its output at any time due to the time validity 
of the prices. Making this process fast on large datasets requires careful indexing and efficient algorithms that you'd
be hard-pressed to get from a generic database.

Implementing this feature is straightforward with evitaDB's flexible query language. Understanding how selling and 
reference prices are determined, considering time validity, and utilizing appropriate pricing strategies—including 
handling products with variants and product sets—allows you to tailor this feature to your business needs.

This feature will be available in the upcoming `2024.10` evitaDB release, but is already available in the canary
version and also on the [evitaDB Demo site](https://demo.evitadb.io).

---

## Join the Conversation

We'd love to hear about your experience implementing the `priceDiscount` constraint. Join our community 
on [Discord](https://discord.gg/VsNBWxgmSw) to share your thoughts and connect with other developers!
