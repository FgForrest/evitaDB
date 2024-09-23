---
title: Introducing the `priceDiscount` Ordering Constraint in evitaDB
perex: |
  We're excited to introduce a powerful new feature in evitaDB—the `priceDiscount` ordering constraint. This allows you to sort your product listings based on the discount amount, helping you highlight the best deals to your customers. In this post, we'll explore how to use this feature effectively, with real-world examples, practical query snippets, and insights into different pricing strategies, including handling products with variants and product sets.
date: '7.11.2023'
author: 'Ing. Jan Novotný'
motive: assets/images/price-discount-ordering.png
proofreading: 'done'
---

# Introducing the `priceDiscount` Ordering Constraint in evitaDB

In the ever-competitive e-commerce landscape, showcasing the best deals to your customers can make all the difference. To help you achieve this, we're excited to introduce a powerful new feature in evitaDB—the `priceDiscount` ordering constraint. This feature empowers you to sort products based on the discount amount, making it easier to highlight significant savings and entice shoppers.

In this post, we'll dive deep into how the `priceDiscount` constraint works, provide real-world examples—including handling products with variants and product sets—and show you how to implement it in your e-commerce solution effectively.

## Understanding the `priceDiscount` Constraint

The `priceDiscount` constraint allows you to order products by the difference between their selling price and a reference price from specified price lists. This is especially useful for e-commerce platforms aiming to promote products with the highest discounts.

### How the Discount is Calculated

The discount is calculated as the difference between the **selling price** (the price the customer pays) and the **reference price** (the original or listed price). Both prices are determined using the same algorithm, considering the prioritized price lists you specify and the validity date.

**Calculation Steps:**

1. **Selling Price**: The first valid price found in the `priceInPriceLists` constraint, matching the `priceValidIn` date and currency specified in `priceInCurrency`. Prices are considered in the order of the price lists provided.
2. **Reference Price**: The first valid price found in the price lists specified in the `priceDiscount` constraint, matching the same date and currency.
3. **Discount**: Calculated as `Reference Price - Selling Price`.

If a price in a given price list is not available or not valid at the specified time, it is skipped. The algorithm automatically continues to the next price list in the priority order until it finds a valid price.

**Important Note:** Non-indexed prices are not part of the calculation. **Prices must be indexed** in evitaDB to be considered in sorting by discount. This ensures efficient performance, especially with large datasets.

**Special Adjustments for Products with Variants or Sets**

- **`LOWEST_PRICE` Strategy**: For products with variants, the discount is calculated based on the variant selected for sale (usually the one with the lowest price). The reference price must come from the same variant across different price lists.
- **`SUM` Strategy**: For product sets, the selling price is the sum of the selling prices of all components. The reference price is calculated by summing the reference prices of the same components, excluding any components that didn't have a selling price to maintain consistency.

## Implementing `priceDiscount` in Your E-Commerce Solution

Let's look at how you can implement the `priceDiscount` constraint in real-world scenarios, including handling products with variants and product sets.

### Scenario: Highlighting Top Discounts During a Flash Sale

Suppose you're running a flash sale and want to display products with the highest discounts. You have multiple price lists:

- **"flash-sale"**: Contains special prices for the flash sale.
- **"standard"**: Contains regular prices.
- **"msrp"**: Contains manufacturer suggested retail prices.

You want to show products sorted by the highest discount, calculated between the selling price from the "flash-sale" price list and the reference price from the "msrp" price list.

#### Crafting the Query

```evitaql
query(
    collection("Product"),
    filterBy(
        priceInPriceLists("flash-sale"),
        priceInCurrency("USD"),
        priceValidIn(2023-11-07T12:00:00-05:00)
    ),
    orderBy(
        priceDiscount("msrp")
    ),
    require(
        entityFetch(
            priceContentRespectingFilter("msrp")
        )
    )
)
```

This query filters products that have valid prices in the "flash-sale" price list, priced in USD, and valid at the specified time. It orders them by the discount amount compared to the "msrp" price list.

#### How the Algorithm Selects Prices

- **Selling Price**: The algorithm searches the "flash-sale" price list for a valid price at `2023-11-07T12:00:00-05:00`. If not found, it skips the product.
- **Reference Price**: It looks for a valid price in the "msrp" price list at the same time.
- **Skipping Invalid or Missing Prices**: If prices are not available or valid in the specified price lists, they are skipped, and the algorithm moves to the next price list in the priority order.

### Real-World Example: Electronics Store Flash Sale

Imagine you're managing an online electronics store preparing for a 24-hour flash sale. Your products have prices in different price lists, some with time-limited validity.

#### Product Data

**Standard Products:**

| Product               | MSRP Price | Flash Sale Price (Valid: 7 Nov 2023) |
|-----------------------|------------|---------------------------------------|
| 4K Smart TV           | $1,000     | $800 (valid all day)                 |
| Gaming Laptop         | $2,000     | $1,600 (valid all day)               |
| Bluetooth Speaker     | $100       | (Not available)                      |

**Product with Variants (`LOWEST_PRICE` Strategy):**

| Product                      | Variant | MSRP Price | Flash Sale Price | Validity            |
|------------------------------|---------|------------|------------------|---------------------|
| Noise-Canceling Headphones   | Black   | $200       | $150             | Until 10:00 AM      |
|                              | Silver  | $200       | $155             | All day             |
|                              | Gold    | $200       | $160             | All day             |

**Product Set (`SUM` Strategy):**

| Product                | Component     | MSRP Price | Flash Sale Price | Validity            |
|------------------------|---------------|------------|------------------|---------------------|
| Home Theater Bundle    | Soundbar      | $500       | $400             | All day             |
|                        | Subwoofer     | $300       | $250             | Until 1:00 PM       |
|                        | Rear Speakers | $200       | $150             | All day             |

#### Query at 9:00 AM

Using the same query as before but at `priceValidIn(2023-11-07T09:00:00-05:00)`, let's see how prices are selected and discounts calculated.

**Price Selection and Discount Calculation:**

| Product                     | Selling Price | Reference Price | Discount |
|-----------------------------|---------------|-----------------|----------|
| 4K Smart TV                 | $800          | $1,000          | $200     |
| Gaming Laptop               | $1,600        | $2,000          | $400     |
| Noise-Canceling Headphones  | $150 (Black)  | $200            | $50      |
| Home Theater Bundle         | $800          | $1,000          | $200     |

- **Noise-Canceling Headphones**: Using `LOWEST_PRICE` strategy, the Black variant with the lowest price ($150) is selected, valid until 10:00 AM.
- **Home Theater Bundle**: Using `SUM` strategy, all components are valid, and their prices are summed.
- **Bluetooth Speaker**: Excluded due to no selling price in "flash-sale".

**Sorting Order:**

1. Gaming Laptop ($400 discount)
2. 4K Smart TV ($200 discount)
3. Home Theater Bundle ($200 discount)
4. Noise-Canceling Headphones ($50 discount)

### Time Validity Impact

The validity of prices affects both inclusion in results and discount amounts.

#### Query at 11:00 AM

Adjusting `priceValidIn(2023-11-07T11:00:00-05:00)`, the Black variant of the Noise-Canceling Headphones is no longer valid. The algorithm selects the next lowest priced valid variant.

**Updated Selection:**

| Product                     | Selling Price | Reference Price | Discount |
|-----------------------------|---------------|-----------------|----------|
| Noise-Canceling Headphones  | $155 (Silver) | $200            | $45      |

**Home Theater Bundle** remains the same until 1:00 PM.

#### Query at 2:00 PM

After 1:00 PM, the Subwoofer's flash sale price is no longer valid.

**Updated Home Theater Bundle Calculation:**

- **Selling Price**: Soundbar ($400) + Rear Speakers ($150) = $550
- **Reference Price**: Soundbar ($500) + Rear Speakers ($200) = $700
- **Discount**: $700 - $550 = $150

**Explanation:** The Subwoofer is excluded from both selling and reference price calculations to maintain consistency.

### Important Notes on Indexing

**Prices must be indexed in evitaDB to be considered in discount calculations.** Non-indexed prices are not part of the calculation. Indexing ensures efficient sorting and retrieval, crucial for performance in high-traffic environments.

## Best Practices for Handling Price Inner Record Strategies

**`LOWEST_PRICE` Strategy:**

- **Use Case:** Products with variants (e.g., different sizes or colors).
- **Calculation:** Discount is based on the variant with the lowest selling price, and the reference price is from the same variant.

**`SUM` Strategy:**

- **Use Case:** Product sets or bundles composed of multiple components.
- **Calculation:** Selling price is the sum of the selling prices of all valid components. Reference price sums the same components. Components without valid selling prices are excluded from both calculations.

**Consistency is Key:** Always ensure that components or variants excluded from the selling price due to invalidity are also excluded from the reference price calculation to maintain accurate discounts.

## Best Practices and Tips

- **Ensure Price Indexing:** Only indexed prices are considered in discount calculations. Always index prices used in `priceDiscount` constraints.
- **Model Price Lists Carefully:** The order and priority of price lists affect discount calculations. Plan your price lists to reflect your pricing strategy effectively.
- **Mind Time Validity:** Always specify `priceValidIn` to consider time-sensitive prices accurately. The algorithm automatically skips prices not valid at the specified time.
- **Choose Appropriate Inner Record Handling:** Select the right `PriceInnerRecordHandling` strategy (`NONE`, `FIRST_OCCURRENCE`, `SUM`, `LOWEST_PRICE`) based on your product structure to ensure correct discount calculations.

## Conclusion

The `priceDiscount` ordering constraint is a powerful tool for enhancing your e-commerce platform. By sorting products based on discount amounts, you can effectively promote deals and increase customer engagement.

Implementing this feature is straightforward with evitaDB's flexible query language. Understanding how selling and reference prices are determined, considering time validity, and utilizing appropriate pricing strategies—including handling products with variants and product sets—allows you to tailor this feature to your business needs.

**Remember:** Only indexed prices are included in the discount calculation. Ensure all relevant prices are indexed for optimal performance.

---

## Join the Conversation

We'd love to hear about your experience implementing the `priceDiscount` constraint. Join our community on [Discord](https://discord.gg/your-discord-channel) to share your thoughts and connect with other developers!
