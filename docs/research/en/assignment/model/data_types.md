---
title: Supported data types
perex:
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

This document lists all data types supported by evitaDB that can be used in [attributes](../index#attributes-unique-filterable-sortable-localized)
or [associated data](../index#associated-data) for storing client relevant data. There is
[automatic conversion process](associated_data_implicit_conversion) connected with associated data if the data
doesn't fit into list of allowed data types. Attributes must always comply with allowed data types.

evitaDB data types are limited to following list:

- [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html), formatted as `'string'`
- [Byte](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Byte.html), formatted as `5`
- [Short](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Short.html), formatted as `5`
- [Integer](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Integer.html), formatted as `5`
- [Long](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Long.html), formatted as `5`
- [Boolean](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Boolean.html), formatted as `true`
- [Character](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Character.html), formatted as `'c'`
- [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html), formatted as `1.124`
- [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html), formatted as `2021-01-01T00:00:00+01:00` ([offset needs to be maintained](https://spin.atomicobject.com/2016/07/06/time-zones-offsets/))
- [LocalDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDateTime.html), formatted as `2021-01-01T00:00:00`
- [LocalDate](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDate.html), formatted as `00:00:00`
- [LocalTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalTime.html), formatted as `2021-01-01`
- [DateTimeRange](#datetimerange), formatted as `[2021-01-01T00:00:00+01:00,2022-01-01T00:00:00+01:00]`
- [BigDecimalNumberRange](#numberrange), formatted as `[1.24,78]`
- [LongNumberRange](#numberrange), formatted as `[5,9]`
- [IntegerNumberRange](#numberrange), formatted as `[5,9]`
- [ShortNumberRange](#numberrange), formatted as `[5,9]`
- [ByteNumberRange](#numberrange), formatted as `[5,9]`
- [Locale](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html), formatted as `` `cs_CZ` `` (enclosed in backticks)
- [Currency](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html), formatted as `` `CZK` `` (enclosed in backticks)

<Note type="info">
Application logic connected with evitaDB data types is located in 
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/EvitaDataTypes.java</SourceClass>
class.
</Note>

## DateTimeRange

represents a specific implementation of the <SourceClass branch="POC">evita_data_types/src/main/java/io/evitadb/api/dataType/Range.java</SourceClass>
defining from and to boundaries
by the [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html) data
types. The offset date times are written in the ISO format.

**Range is written as:**

- when both boundaries are specified:

```
[2021-01-01T00:00:00+01:00,2022-01-01T00:00:00+01:00]
```

- when a left boundary (since) is specified:

```
[2021-01-01T00:00:00+01:00,]
```

- when a right boundary (until) is specified:

```
[,2022-01-01T00:00:00+01:00]
```

## NumberRange

Represents a specific implementation of the <SourceClass branch="POC">evita_data_types/src/main/java/io/evitadb/api/dataType/Range.java</SourceClass>,
defining from and to boundaries by the [Number](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Number.html)
data types. The supported number types are: Byte, Short, Integer, Long and BigDecimal.

Both boundaries of the number range must be of the same type - you cannot mix for example BigDecimal as lower bound
and Byte as upper bound.

**Range is written as:**

- when both boundaries are specified:

```
[1,3.256]
```

- when a left boundary (since) is specified:

```
[1,]
```

- when a right boundary (until) is specified:

```
[,3.256]
```
