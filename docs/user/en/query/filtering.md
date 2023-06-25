---
title: Filtering
perex: |
  The query language is the core of any database machine. evitaDB has chosen a functional form of the language instead
  of a SQL-like language, which is more consistent with how it works internally and, most importantly, much more open 
  to transformations.
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

### Array types targeted by the constraint

If the constraint targets an attribute that is of array type, the constraint automatically matches an entity in case
**any** of the attribute array items satisfies it.

For example let's have a [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
array attribute named `oneDayDeliveryCountries` with the following values: `GB`, `FR`, `CZ`. The filtering constraint
[`attributeEquals`](filtering/comparable.md#attribute-equals) worded as follows: `attributeEquals('oneDayDeliveryCountries', 'GB')`
will match the entity, because the *GB* is one of the array values.

Let's look at a more complicated, but more useful example. Let's have a [`DateTimeRange`](../use/data-types.md#datetimerange)
array attribute called `validity` that contains multiple time periods when the entity can be used:

```plain
[2023-01-01T00:00:00+01:00,2023-02-01T00:00:00+01:00]
[2023-06-01T00:00:00+01:00,2022-07-01T00:00:00+01:00]
[2023-12-01T00:00:00+01:00,2024-01-01T00:00:00+01:00]
```

In short, the entity is only valid in January, June, and December 2023. If we want to know if it's possible to access
(e.g. buy a product) in May using the constraint `attributeInRange('validity', '2023-05-05T00:00:00+01:00')`, the result
will be empty because none of the `validity` array ranges matches that date and time. Of course, if we ask for an entity
that is valid in June using `attributeInRange('validity', '2023-06-05T00:00:00+01:00')`, the entity will be returned
because there is a single date/time range in the array that satisfies this constraint.