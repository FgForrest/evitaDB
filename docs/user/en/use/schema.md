---
title: Schema
perex:
date: '17.1.2023'
author: 'Ing. Jan Novotný'
---

**Work in progress**

This article will contain information from [schema API](https://evitadb.io/research/assignment/updating/schema_api),
and additional information about schema.

[//]: # (notes)

#### Allowed decimal places

The allowed decimal places setting represents an optimization that allows converting rich numeric types (such as
[BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) for precise
number representation) to the primitive [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
type, which is much more compact and can be used for fast binary searches in array/bitset representation. The original
rich format is still present in an attribute container, but internally the database uses the primitive form when an
attribute is part of is part of filter or sort conditions.

If number cannot be converted to a compact form (for example, it has more digits in the fractional part than expected),
exception is thrown and the entity update is refused.