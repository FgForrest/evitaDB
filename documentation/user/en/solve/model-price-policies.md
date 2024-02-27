---
title: Model price policies
perex: |
  Pricing policies in the B2B environment are often complex and require a lot of creativity to model them accurately 
  while keeping calculations efficient and fast. This article contains various approaches we've used in the past that 
  you may find useful. This article is expected to grow over time as we discover new approaches.
date: '25.2.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

## Natural modeling

In most scenarios, there is one main price list with prices for sales - we usually call it *basic*, which contains 
prices for all products. Very often there is also another price list called *"reference"* which contains reference 
market prices or the lowest price in the last period. The prices in the *"reference"* price list are marked as 
non-selling prices and are used for comparison with the real selling price and for calculating the discount.

Then there are various other price lists, which usually contain prices for only a subset of products and take precedence
over the *basic* price list. These price lists are usually used for special customers, special products, special 
regions, etc.

When a user is authenticated, the system selects the set of price lists relevant to the user and sorts them by priority. 
This set is stored in the user's session or in a [JWT token](https://en.wikipedia.org/wiki/JSON_Web_Token) and is used 
for all price calculations.

## Avoiding unique price list per customer

In some cases, it is tempting to create a unique price list for each customer. We recommend avoiding this approach as 
long as possible because it doesn't scale well. The number of price lists grows with the number of customers and 
products, and the database grows very quickly. It's also not common for the seller to have a truly unique price per 
product for each customer, because it would be very hard to maintain such a database. If you try to discover 
the background mechanics, there is usually some kind of rules that can be exploited to reduce the number of price lists.

### Discount per customer

Sometimes the prices in B2B systems are calculated as a discount from the basic price list. The seller may try to 
convince you that each customer has a unique discount. In this case - try to get the distribution of these discounts,
and you may find out that there the largest discount is 15% and the discounts are always rounded to whole percentages.
This situation is quite easy to model - instead of storing the discount for each customer, you can have a special price 
list for each discounted rate (this would make only 15 such price lists) and then just choose the right price list based 
on the customer's discount.

