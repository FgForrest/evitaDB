---
title: Data types
perex: |
    The article gives an introduction to data types in EvitaDB query language, including basic and complex types,
    and provides code examples to demonstrate their usage.
date: '23.8.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'java'
---

This document lists all data types supported by evitaDB that can be used in
[attributes](data-model.md#attributes-unique-filterable-sortable-localized) or [associated data](data-model.md#associated-data)
for storing client relevant information.

There are two categories of data types:

1. [simple data types](#simple-data-types) that can be used both for
    [attributes](data-model.md#attributes-unique-filterable-sortable-localized) and
    [associated data](data-model.md#associated-data)
2. [complex data types](#complex-data-types) that can be used only for [associated data](data-model.md#associated-data)

## Simple data types

<LS to="e,j">

evitaDB data types are limited to following list:

- [String](#string),
    formatted as `"string"`
- [Byte](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Byte.html),
    formatted as `5`
- [Short](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Short.html),
    formatted as `5`
- [Integer](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Integer.html),
    formatted as `5`
- [Long](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Long.html),
    formatted as `5`
- [Boolean](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Boolean.html),
    formatted as `true`
- [Character](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Character.html),
    formatted as `'c'`
- [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html),
    formatted as `1.124`
- [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html),
    formatted as `2021-01-01T00:00:00+01:00`
- [LocalDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDateTime.html),
    formatted as `2021-01-01T00:00:00`
- [LocalDate](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDate.html),
    formatted as `2021-01-01`
- [LocalTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalTime.html),
    formatted as `00:00:00`
- [DateTimeRange](#datetimerange),
    formatted as `[2021-01-01T00:00:00+01:00,2022-01-01T00:00:00+01:00]`
- [BigDecimalNumberRange](#numberrange),
    formatted as `[1.24,78]`
- [LongNumberRange](#numberrange),
    formatted as `[5,9]`
- [IntegerNumberRange](#numberrange),
    formatted as `[5,9]`
- [ShortNumberRange](#numberrange),
    formatted as `[5,9]`
- [ByteNumberRange](#numberrange),
    formatted as `[5,9]`
- [Locale](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html),
    formatted as language tag `'cs-CZ'`
- [Currency](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html),
    formatted as `'CZK'`
- [UUID](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/UUID.html),
    formatted as `2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9`
- [Predecessor](#predecessor),
    formatted as `789`

</LS>
<LS to="c">

evitaDB data types are limited to the following list:

- [string](#string),
  formatted as `"string"`
- [byte](https://learn.microsoft.com/cs-cz/dotnet/api/system.byte),
  formatted as `5`
- [short](https://learn.microsoft.com/en-us/dotnet/api/system.int16),
  formatted as `5`
- [int](https://learn.microsoft.com/en-us/dotnet/api/system.int32),
  formatted as `5`
- [long](https://learn.microsoft.com/en-us/dotnet/api/system.int64),
  formatted as `5`
- [bool](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/bool),
  formatted as `true`
- [char](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/char),
  formatted as `'c'`
- [decimal](https://learn.microsoft.com/en-us/dotnet/api/system.decima),
  formatted as `1.124`
- [DateTimeOffset](https://learn.microsoft.com/en-us/dotnet/api/system.datetimeoffset),
  formatted as `2021-01-01T00:00:00+01:00`
- [DateTime](https://learn.microsoft.com/en-us/dotnet/api/system.datetime),
  formatted as `2021-01-01T00:00:00`
- [DateOnly](https://learn.microsoft.com/en-us/dotnet/api/system.dateonly),
  formatted as `00:00:00`
- [TimeOnly](https://learn.microsoft.com/en-us/dotnet/api/system.timeonly),
  formatted as `2021-01-01`
- [DateTimeRange](#datetimerange),
  formatted as `[2021-01-01T00:00:00+01:00,2022-01-01T00:00:00+01:00]`
- [DecimalNumberRange](#numberrange),
  formatted as `[1.24,78]`
- [LongNumberRange](#numberrange),
  formatted as `[5,9]`
- [IntegerNumberRange](#numberrange),
  formatted as `[5,9]`
- [ShortNumberRange](#numberrange),
  formatted as `[5,9]`
- [ByteNumberRange](#numberrange),
  formatted as `[5,9]`
- [CultureInfo](https://learn.microsoft.com/en-us/dotnet/api/system.globalization.cultureinfo),
  formatted as language tag `'cs-CZ'`
- [Currency](https://github.com/FgForrest/evitaDB-C-Sharp-client/blob/master/EvitaDB.Client/DataTypes/Currency.cs),
  formatted as `'CZK'`
- [GUID](https://learn.microsoft.com/en-us/dotnet/api/system.guid),
  formatted as `2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9`
- [Predecessor](#predecessor),
  formatted as `789`

</LS>
<LS to="g,r">

The data types are based on the Java data types because that's how they are stored under the hood. The only difference
is how they are formatted. evitaDB data types are limited to following list:

- [String](#string),
    formatted as `'string'`
- [Byte](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Byte.html),
    formatted as `5`
- [Short](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Short.html),
    formatted as `5`
- [Integer](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Integer.html),
    formatted as `5`
- [Long](#long),
    formatted as `"5"`
- [Boolean](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Boolean.html),
    formatted as `true`
- [Character](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Character.html),
    formatted as `"c"`
- [BigDecimal](#bigdecimal),
    formatted as `"1.124"`
- [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html),
    formatted as `"2021-01-01T00:00:00+01:00"`
- [LocalDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDateTime.html),
    formatted as `"2021-01-01T00:00:00"`
- [LocalDate](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDate.html),
    formatted as `"00:00:00"`
- [LocalTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalTime.html),
    formatted as `"2021-01-01"`
- [DateTimeRange](#datetimerange),
    formatted as `["2021-01-01T00:00:00+01:00", "2022-01-01T00:00:00+01:00"]`
- [BigDecimalNumberRange](#numberrange),
    formatted as `["1.24", "78"]`
- [LongNumberRange](#numberrange),
    formatted as `["5", "9"]`
- [IntegerNumberRange](#numberrange),
    formatted as `[5, 9]`
- [ShortNumberRange](#numberrange),
    formatted as `[5, 9]`
- [ByteNumberRange](#numberrange),
    formatted as `[5, 9]`
- [Locale](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html),
    formatted as language tag `"cs-CZ"`
- [Currency](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html),
    formatted as `"CZK"`
- [UUID](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/UUID.html),
    formatted as `"2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9"`
- [Predecessor](#predecessor),
  formatted as `789`

</LS>

<LS to="j,g,r,c">

An array of a simple type is still a simple data type. All simple types can be wrapped in an array. You cannot mix
arrays and non-array types in a single *attribute* / *associated data* schema. Once an *attribute* or *associated
data* schema specifies that it accepts an array of integers, it cannot store a single integer value, and vice versa.
integer attribute/associated data will never accept an array of integers.

<Note type="warning">
Since evitaDB keeps all data in indexes in main memory, we strongly recommend using the shortest/smallest data types
that can accommodate your data. We do our best to minimize the memory footprint of the database, but the crucial
decisions are on your side, so think carefully which data type you choose and whether you make it filterable/sortable
so that it requires a memory index.
</Note>

</LS>

<LS to="j">

<Note type="info">
Application logic connected with evitaDB data types is located in
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/EvitaDataTypes.java</SourceClass>
class.
</Note>

</LS>

<LS to="c">

<Note type="info">
Application logic connected with evitaDB data types is located in
<SourceClass>EvitaDB.Client/DataTypes/EvitaDataTypes.cs</SourceClass>
class.
</Note>

</LS>

### String

<LS to="j,e,g,r">
The [string type](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) is internally encoded with the character set [UTF-8](https://en.wikipedia.org/wiki/UTF-8). evitaDB
query language and other I/O methods of evitaDB implicitly use this encoding.
</LS>
<LS to="c">
The [string type](https://learn.microsoft.com/en-us/dotnet/api/system.string) is internally encoded with the character set [UTF-8](https://en.wikipedia.org/wiki/UTF-8). evitaDB
query language and other I/O methods of evitaDB implicitly use this encoding.
</LS>

<LS to="g,r">

### Long

Because the [64-bit long integer](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Long.html) data type comes from Java,
some languages (e.g., [JavaScript](https://stackoverflow.com/a/17320771)) may have problems with its size when
parsing large numbers from JSON into their default number data types.
That's why we decided to format the long integer data type as a string. This way, there is no size limit, and
a client can always parse the long number without worrying that the default number data type is not large enough for the parsed number.

### BigDecimal

evitaDB supports the [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) data type
instead of the basic float or double data types found in most programming languages. The main reason is that the float and double data types
are not precise enough for financial calculations. This is the same reason why BigDecimal values are formatted as strings.
Even though the JSON format has its ways to ensure correct precision to some degree, we cannot guarantee that the client programming language
parsing the number will use the correct data type that preserves the precision.

</LS>

### Dates and times

<LS to="j,e,g,r">
Although evitaDB supports *local* variants of the date time like
[LocalDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDateTime.html), it's always
converted to [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html)
using the evitaDB server system default timezone. You can control the default Java timezone in
[several ways](https://www.baeldung.com/java-jvm-time-zone). If your data is time zone specific, we recommend to work
directly with the [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html)
on the client side and be explicit about the offset from the first day.
</LS>
<LS to="c">
Although evitaDB supports *local* variants of the date time like
[DateTime](https://learn.microsoft.com/en-us/dotnet/api/system.datetime), it's always
using the evitaDB server system default timezone. You can control the default Java timezone in
[several ways](https://www.baeldung.com/java-jvm-time-zone). If your data is time zone specific, we recommend to work
directly with the [DateTimeOffset](https://learn.microsoft.com/en-us/dotnet/api/system.datetimeoffset)
on the client side and be explicit about the offset from the first day.
</LS>

<Note type="question">

<LS to="j,e,g,r">
<NoteTitle toggles="true">
##### Why do we internally use OffsetDateTime for time information?
</NoteTitle>
</LS>

<LS to="c">
<NoteTitle toggles="true">
##### Why do we internally use DateTimeOffset for time information?
</NoteTitle>
</LS>

Offset/time zone handling varies from database to database. We wanted to avoid setting the timezone in session or
database configuration properties, as this mechanism is error-prone and impractical. Saving/loading date times with
timezone information would be the best option, but we run into problems with
[parsing](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date/parse) in certain
environments, and only the date with offset information seems to be widely supported. The offset information is good
enough for our case - it identifies a globally valid time that is known at the time the data value is stored.
</Note>

### DateTimeRange

<LS to="j,e,g,r">
The DateTimeRange represents a specific implementation of the
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/Range.java</SourceClass> defining from and to boundaries
by the [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html) data
types. The offset date times are written in the ISO format.
</LS>
<LS to="c">
The DateTimeRange represents a specific implementation of the
<SourceClass>EvitaDB.Client/DataTypes/Range.cs</SourceClass> defining from and to boundaries
by the [DateTimeOffset](https://learn.microsoft.com/en-us/dotnet/api/system.datetimeoffset) data
types. The offset date times are written in the ISO format.
</LS>

**Range is written as:**

- when both boundaries are specified:

<LS to="e,j,c">

```plain
[2021-01-01T00:00:00+01:00,2022-01-01T00:00:00+01:00]
```

</LS>
<LS to="g,r">

```json
["2021-01-01T00:00:00+01:00","2022-01-01T00:00:00+01:00"]
```

</LS>

- when a left boundary (since) is specified:

<LS to="e,j,c">

```plain
[2021-01-01T00:00:00+01:00,]
```

</LS>
<LS to="g,r">

```json
["2021-01-01T00:00:00+01:00",null]
```

</LS>

- when a right boundary (until) is specified:

<LS to="e,j,c">

```plain
[,2022-01-01T00:00:00+01:00]
```

</LS>
<LS to="g,r">

```json
[null,"2022-01-01T00:00:00+01:00"]
```

</LS>

### NumberRange

<LS to="j,e,g,r">
The NumberRange represents a specific implementation of the
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/Range.java</SourceClass>
defining from and to boundaries by the [Number](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Number.html)
data types. The supported number types are: Byte, Short, Integer, Long and BigDecimal.

Both boundaries of the number range must be of the same type - you cannot mix for example BigDecimal as lower bound
and Byte as upper bound.
</LS>
<LS to="c">
The NumberRange represents a specific implementation of the
<SourceClass>EvitaDB.Client/DataTypes/Range.cs</SourceClass>
defining from and to boundaries by any of the supported
data type. The supported number types are: byte, short, int, long and decimal.

Both boundaries of the number range must be of the same type - you cannot mix for example decimal as lower bound
and byte as upper bound.
</LS>

**Range is written as:**

- when both boundaries are specified:

<LS to="e,j,c">

```plain
[1,3.256]
```

</LS>
<LS to="g,r">

```json
["1","3.256"]
```
</LS>

- when a left boundary (since) is specified:

<LS to="e,j,c">

```plain
[1,]
```

</LS>
<LS to="g,r">

```json
["1",null]
```

</LS>

- when a right boundary (until) is specified:

<LS to="e,j,c">

```plain
[,3.256]
```

</LS>
<LS to="g,r">

```json
[null,"3.256"]
```

</LS>

### Predecessor

The <LS to="e,j,r,g"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/Predecessor.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/DataTypes/Predecessor.cs</SourceClass></LS> is a special data type
used to define a single oriented linked list of entities of the same type. It represents a pointer to a previous entity
in the list. The head element is a special case and is represented by the constant `Predecessor#HEAD`. The predecessor
attribute can only be used in the [attributes](data-model.md#attributes-unique-filterable-sortable-localised) of an
entity or its reference to another entity. It cannot be used to filter entities, but is very useful for sorting.

#### Motivation for linked lists in database sorting

The linked list is a very optimal data structure for sorting entities in a database that holds large amounts of data.
Inserting a new element into a linked list is a constant time operation and requires only two updates:

1) inserting a new element into the list, pointing to an existing element as its predecessor
2) updating the original element pointing to the predecessor to point to the new element.

Moving (updating) an element or removing an existing element from a linked list is also a constant time operation,
requiring similar two updates. The disadvantage of the linked list is its poor random access performance (get element
at n-th index) and list traversal, which requires a lot of random access to different parts of memory. However, these
disadvantages can be mitigated by keeping the linked list in the form of an array or binary tree of properly positioned
primary keys.

<Note type="info">

<NoteTitle toggles="true">

##### Aren't there any better approaches for keeping ordered list of entities?
</NoteTitle>

There are alternatives approaches to this problem, but they all have their downsides. Some of them are summarized in
[the article "Keeping an ordered collection in PostgreSQL" by Nicolas Goy](https://medium.com/the-missing-bit/keeping-an-ordered-collection-in-postgresql-9da0348c4bbe). We went through similar journey and
concluded that the linked list is the least of all evils:

- It doesn't require mass updates of surrounding entities or occasional "reshuffling".
- it doesn't force the client logic to be complicated (and it plays well with the UI drag'n'drop repositioning flow)
- it is very data efficient - it only requires a single <LS to="e,j,r,g">[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)</LS><LS to="c">[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32)</LS>
  (4B) per single item in the list

</Note>

#### Maintaining consistency of the linked list

Constructing a linked list could be a tricky process from a consistency point of view - especially in the
[warm-up](api/write-data.md#bulk-indexing) phase, when you need to reconstruct the data from an external primary store.
To be consistent at all times, you'd need to start with the entity that represents the head of the chain, then insert
its successor, and vice versa. This is often not trivial, and if you have two predecessor attributes with different
"order" for the same entities, it's absolutely impossible.

That's why we designed our linked list implementation to tolerate partial inconsistencies, and to converge to
a consistent state as missing data is inserted. We support these inconsistency scenarios:

- multiple head elements
- multiple successor elements for a single predecessor
- circular dependencies, where a head element points to an element in its tail

The sorting by an inconsistent predecessor attribute sorts the entities by the chains in the following order:

1) the chains starting with a head element (starting with the chain with most elements, to the chain with least elements)
2) the chains with elements sharing the same predecessor (starting with the chain with most elements, to the chain with least elements)
3) the chains with circular dependencies (starting with the chain with most elements, to the chain with least elements)

When the dependencies are fixed, the sort order will converge to the correct one.
The <SourceClass>evita_engine/src/main/java/io/evitadb/index/attribute/ChainIndex.java</SourceClass> will contain only
a single chain of correctly ordered elements and will return true when the `isConsistent()` method is called on it.

The inconsistent state is also allowed in the transactional phase, but we recommend avoiding it and updating all
the elements involved (in any order) within a single transaction, which will ensure that the linked list remains
consistent for all other transactions.

## Complex data types

<LS to="e,j,c">

The complex types are types that don't qualify as [simple evitaDB types](#simple-data-types) (or an array of simple
evitaDB types). Complex types are stored in a
<LS to="e,j"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ComplexDataObject.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/DataTypes/ComplexDataObject.cs</SourceClass></LS> data structure that is
intentionally similar to the JSON data structure so that it can be easily converted to JSON format and can also accept
and store any valid JSON document.

</LS>

<LS to="g,r">

The complex types are all types that don't qualify as [simple evitaDB types](#simple-data-types) (or an array of simple
evitaDB types). Complex types are written as JSON objects to allow any object structure with easy serialization and deserialization.
However, this comes with caveats - data types of properties of a complex type are currently limited only to the data types
supported by plain JSON. This means that you can store e.g. date time as a string as you would normally do with
[simple data types](#simple-data-types), but internally, it will be stored as a plain string (because we don't have
any information about the concrete data type), and it is up to you to do a manual conversion on the client side.

</LS>

<LS to="j">

The complex type in Java is a class that implements the serializable interface and does not belong to a `java` package
or is not directly supported by the [simple data types](#simple-data-types)(i.e. `java.lang.URL` is forbidden to be
stored in evitaDB, even if it is serializable and belongs to a `java` package, because it is not directly supported by
the [simple data types](#simple-data-types)). The complex types are intended for the client POJO
classes to carry larger data or to associate simple logic with the data.

</LS>
<LS to="c">

The complex type in C# is a record that does not belong among `built-in` C# types,
and it is not directly supported by the [simple data types](#simple-data-types)(i.e. `System.Uri` is forbidden to be
stored in evitaDB, even if it is serializable by design (implements ISerializable interface) and belongs to a `C#`
`built-in` types, because it is not directly supported by the [simple data types](#simple-data-types)).
The complex types are intended for the client POCO classes to carry larger data or to associate simple logic with the data.

</LS>

<LS to="e,j">

<Note type="info">
Associated data may even contain array of complex objects. Such data will be automatically converted to an array of
`ComplexDataObject` types - i.e. `ComplexDataObject[]`.
</Note>

### The complex type can contain the properties of

- any [simple evitaDB types](#simple-data-types)
- any other complex types (additional inner POJOs)
- generic [Lists](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html)
- generic [Sets](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)
- generic [Maps](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Map.html)
- any array of [simple evitaDB types](#simple-data-types) or [complex types](#complex-data-types)

</LS>
<LS to="c">

<Note type="info">
Associated data may even contain array of complex objects. Such data will be automatically converted to an array of
`ComplexDataObject` types - i.e. `ComplexDataObject[]`.
</Note>

### The complex type can contain the properties of

- any [simple evitaDB types](#simple-data-types)
- any other complex types (additional inner POCOs)
- generic [Lists](https://learn.microsoft.com/en-us/dotnet/api/system.collections.generic.ilist-1)
- generic [Sets](https://learn.microsoft.com/en-us/dotnet/api/system.collections.generic.iset-1)
- generic [Dictionaries](https://learn.microsoft.com/cs-cz/dotnet/api/system.collections.idictionary)
- any array of [simple evitaDB types](#simple-data-types) or [complex types](#complex-data-types)

</LS>
<LS to="g,r">

<Note type="info">
Associated data may even contain array of complex objects. Such data will be automatically converted to an array of
`ComplexDataObject` types - i.e. `ComplexDataObjectArray`.
</Note>

</LS>

<LS to="c">

### Serialization

All public properties with public getters and setters are serialized into a complex type if they are not decorated with
and are not annotated with <SourceClass>EvitaDB.Client/DataTypes/Data/NonSerializableDataAttribute.cs</SourceClass>.
See the following example:

<SourceCodeTabs local>
[Associated data POCO](/documentation/user/en/use/examples/dto.cs)
</SourceCodeTabs>

Storing a complex type to entity is executed as follows:

<SourceCodeTabs requires="/documentation/user/en/use/examples/dto.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/get-started/example/define-catalog-with-schema.java,/documentation/user/en/use/api/example/open-session-manually.java" local>

[Storing associated data to an entity](/documentation/user/en/use/examples/storing.cs)
</SourceCodeTabs>

If the serialization process encounters any property that cannot be serialized,
the <SourceClass>EvitaDB.Client/Exceptions/EvitaInvalidUsageException.cs</SourceClass> exception is thrown.

### Deserialization

Retrieving a complex type from an entity is executed as follows:

<SourceCodeTabs requires="/documentation/user/en/use/examples/storing.java" local>

[Loading associated data from an entity](/documentation/user/en/use/examples/loading.cs)
</SourceCodeTabs>

Complex types are internally converted to a
<SourceClass>EvitaDB.Client/DataTypes/ComplexDataObject.cs</SourceClass> type,
that can be safely stored in evitaDB storage. The (de)serialization process is also designed to prevent data loss, and
to allow model evolution.

The deserialization process may fail with two exceptions:

- [FormatException](https://learn.microsoft.com/en-us/dotnet/api/System.FormatException)
  is raised when certain property cannot be deserialized due to an incompatibility
  with the specified [contract](#the-complex-type-can-contain-the-properties-of)
- [InvalidCastException](https://learn.microsoft.com/en-us/dotnet/api/System.InvalidCastException)
  is raised when any of the serialized data was not deserialized due to a lack of a mutator method on the class it's being converted to

</LS>

<LS to="j">

### Serialization

All [properties that comply with JavaBean naming rules](https://www.baeldung.com/java-pojo-class#what-is-a-javabean) and
have both an accessor, a mutator method (i.e. `get` and `set` methods for the property) and are not annotated with
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/data/NonSerializedData.java</SourceClass>
annotation, are serialized into a complex type. See the following example:

<SourceCodeTabs local>
[Associated data POJO](/documentation/user/en/use/examples/dto.java)
</SourceCodeTabs>

Storing a complex type to entity is executed as follows:

<SourceCodeTabs requires="/documentation/user/en/use/examples/dto.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/get-started/example/define-catalog-with-schema.java,/documentation/user/en/use/api/example/open-session-manually.java" local>

[Storing associated data to an entity](/documentation/user/en/use/examples/storing.java)
</SourceCodeTabs>

As you can see, annotations can be placed either on methods or property fields, so that if you use
[Lombok support](https://projectlombok.org/), you can still easily define the class as:

<SourceCodeTabs local>
[Associated data Lombok POJO](/documentation/user/en/use/examples/dto-lombok.java)
</SourceCodeTabs>

If the serialization process encounters any property that cannot be serialized, the
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/exception/SerializationFailedException.java</SourceClass>
is thrown.

#### Generic collections

You can use collections in complex types, but the specific collection types must be extractable from the collection
generics in deserialization time. Look at the following example:

<SourceCodeTabs local>
[Associated data POJO with collections](/documentation/user/en/use/examples/dto-collection.java)
</SourceCodeTabs>

This class will (de)serialize just fine.

<Note type="warning">
Collection generics must be resolvable to an exact class (meaning that wildcard generics are not supported). The complex
type may also be an immutable class, accepting properties via the constructor parameters. Immutable classes must be
compiled with the javac `-parameters` argument, and their names in the constructor must match their property names of
the getter fields. This fact plays really well with [Lombok @Data annotation](https://projectlombok.org/features/Data).
</Note>

#### Test recommendations

Because methods that don't follow the JavaBeans contract are silently skipped, it is highly recommended to always
store and retrieve associated data in the unit test and check that all important data is actually stored:

``` java
@Test
void verifyProductStockAvailabilityIsProperlySerialized() {
    final EntityBuilder entity = new InitialEntityBuilder("product");
    final ProductStockAvailability beforeStore = new ProductStockAvailability();
    entity.setAssociatedData("stockAvailability", beforeStore);
    //some custom logic to load proper entity
    final SealedEntity loadedEntity = entity();
    final ProductStockAvailability afterLoad = loadedEntity.getAssociatedData(
        "stockAvailability", ProductStockAvailability.class
    );
    assertEquals(
        beforeStore, afterLoad,
        "ProductStockAvailability was not entirely serialized!"
    );
}
```

### Deserialization

Retrieving a complex type from an entity is executed as follows:

<SourceCodeTabs requires="/documentation/user/en/use/examples/storing.java" local>

[Loading associated data from an entity](/documentation/user/en/use/examples/loading.java)
</SourceCodeTabs>

Complex types are internally converted to a
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/ComplexDataObject.java</SourceClass> type,
that can be safely stored in evitaDB storage. The (de)serialization process is also designed to prevent data loss, and
to allow model evolution.

The deserialization process may fail with two exceptions:

- <SourceClass>evita_common/src/main/java/io/evitadb/dataType/exception/UnsupportedDataTypeException.java</SourceClass>
  is raised when certain property cannot be deserialized due to an incompatibility
  with the specified [contract](#the-complex-type-can-contain-the-properties-of)
- <SourceClass>evita_common/src/main/java/io/evitadb/dataType/exception/IncompleteDeserializationException.java</SourceClass>
  is raised when any of the serialized data was not deserialized due to a lack of a mutator method on the class it's being converted to

#### Model evolution support

##### Field removal

The <SourceClass>evita_common/src/main/java/io/evitadb/dataType/exception/IncompleteDeserializationException.java</SourceClass>
exception protects developers from unintentional data loss by making a mistake in the Java model and then executing:

- a fetch of existing complex type
- altering a few properties
- storing it back again to evitaDB

If there is legal reason for dropping some data stored along with its complex type in the previous versions of the application,
you can use <SourceClass>evita_common/src/main/java/io/evitadb/dataType/data/DiscardedData.java</SourceClass> annotation
on any complex type class to declare that it is ok to throw away data during deserialization.

<Note type="example">
Associated data were stored with this class definition:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    private int id;
    private String stockName;
}
```

In future versions, developer will decide that the `id` field is not necessary anymore and may be dropped. But there is
a lot of data written by the previous version of the application. So, when dropping a field, we need to make a note
for evitaDB that the presence of any `id` data is ok, even if there is no field for it anymore. This data will be
discarded when the associated data gets rewritten by the new version of the class:

``` java
@Data
@DiscardedData("id")
public class ProductStockAvailability implements Serializable {
    private String stockName;
}
```
</Note>

##### Field renaming and controlled migration

There are also situations when you need to rename the field (for example you made a typo in the previous version of the
Java Bean type). In such case you"d also experience the
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/exception/IncompleteDeserializationException.java</SourceClass>
when you try to deserialize the type with the corrected Java Bean definition. In this situation, you can use the
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/data/RenamedData.java</SourceClass> annotation to migrate
old versions of data.

<Note type="example">
First version of the Java type with the mistake:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    private String stockkName;
}
```

Next time we'll try to fix the typo:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    @RenamedData("stockkName")
    private String stockname;
}
```

But we make yet another mistake, so we need another correction:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    @RenamedData({"stockkName", "stockname"})
    private String stockName;
}
```
</Note>

We may get rid of those annotations when we're confident there is no data with the old contents in evitaDB.
Annotation <SourceClass>evita_common/src/main/java/io/evitadb/dataType/data/RenamedData.java</SourceClass>
can also be used for model evolution - i.e. automatic translation of an old data format to the new one.

<Note type="example">
Old model:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    private String stockName;
}
```

New model:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    private String upperCasedStockName;

    @RenamedData
    public void setStockName(String stockName) {
        this.upperCasedStockName = stockName == null ?
            null : stockName.toUpperCase();
    }
}
```
</Note>

</LS>
