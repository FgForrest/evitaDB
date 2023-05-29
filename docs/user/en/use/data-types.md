---
title: Data types
perex: |
    The article gives an introduction to data types in EvitaDB query language, including basic and complex types,
    and provides code examples to demonstrate their usage.
date: "17.1.2023"
author: "Ing. Jan Novotný"
proofreading: "done"
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

evitaDB data types are limited to following list:

- [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html), 
    formatted as `'string'`
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
    formatted as `00:00:00`
- [LocalTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalTime.html), 
    formatted as `2021-01-01`
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
    formatted as language tag `` `cs-CZ` `` (enclosed in backticks)
- [Currency](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html), 
    formatted as `` `CZK` `` (enclosed in backticks)

An array of a simple type is still a simple data type. All simple types can be wrapped in an array. You cannot mix
arrays and non-array types in a single *attribute* / *associated data* schema. Once an *attribute* or *associated
data* schema specifies that it accepts an array of integers, it cannot store a single integer value, and vice versa.
integer attribute/associated data will never accept an array of integers.

<Note type="warn">
Since evitaDB keeps all data in indexes in main memory, we strongly recommend using the shortest/smallest data types 
that can accommodate your data. We do our best to minimize the memory footprint of the database, but the crucial 
decisions are on your side, so think carefully which data type you choose and whether you make it filterable/sortable 
so that it requires a memory index.
</Note>

<LanguageSpecific to="java">

<Note type="info">
Application logic connected with evitaDB data types is located in 
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/EvitaDataTypes.java</SourceClass>
class.
</Note>

</LanguageSpecific>

### String

The string type is internally encoded with the character set [UTF-8](https://en.wikipedia.org/wiki/UTF-8). evitaDB
query language and other I/O methods of evitaDB implicitly use this encoding.

### Dates and times

Although evitaDB supports *local* variants of the date time like 
[LocalDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDateTime.html), it's always 
converted to [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html) 
using the system default timezone. You can control the default Java timezone in 
[several ways](https://www.baeldung.com/java-jvm-time-zone). If your data is time zone specific, we recommend to work 
directly with the [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html) 
on the client side and be explicit about the offset from the first day.

<Note type="question">

<NoteTitle toggles="true">

##### Why do we internally use OffsetDateTime for time information?
</NoteTitle>

Offset/time zone handling varies from database to database. We wanted to avoid setting the timezone in session or 
database configuration properties, as this mechanism is error-prone and impractical. Saving/loading date times with 
timezone information would be the best option, but we run into problems with 
[parsing](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date/parse) in certain 
environments, and only the date with offset information seems to be widely supported. The offset information is good 
enough for our case - it identifies a globally valid time that is known at the time the data value is stored.
</Note>

### DateTimeRange

The DateTimeRange represents a specific implementation of the 
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/Range.java</SourceClass> defining from and to boundaries
by the [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html) data
types. The offset date times are written in the ISO format.

**Range is written as:**

- when both boundaries are specified:

```plain
[2021-01-01T00:00:00+01:00,2022-01-01T00:00:00+01:00]
```

- when a left boundary (since) is specified:

```plain
[2021-01-01T00:00:00+01:00,]
```

- when a right boundary (until) is specified:

```plain
[,2022-01-01T00:00:00+01:00]
```

### NumberRange

The NumberRange represents a specific implementation of the 
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/Range.java</SourceClass>
defining from and to boundaries by the [Number](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Number.html)
data types. The supported number types are: Byte, Short, Integer, Long and BigDecimal.

Both boundaries of the number range must be of the same type - you cannot mix for example BigDecimal as lower bound
and Byte as upper bound.

**Range is written as:**

- when both boundaries are specified:

```plain
[1,3.256]
```

- when a left boundary (since) is specified:

```plain
[1,]
```

- when a right boundary (until) is specified:

```plain
[,3.256]
```

## Complex data types

The complex types are all types that don't qualify as [simple evitaDB types](#simple-data-types) (or an array of simple 
evitaDB types). Complex types are stored in a 
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/ComplexDataObject.java</SourceClass> data structure that is 
intentionally similar to the JSON data structure so that it can be easily converted to JSON format and can also accept 
and store any valid JSON document.

<LanguageSpecific to="java">
The complex type in Java is a class that implements the serializable interface and does not belong to a `java` package
(i.e. `java.lang.URL` is forbidden to be stored in evitaDB, even if it is serializable, because it belongs to the `java` 
package and is not directly supported by the basic data types). The complex types are intended for the client POJO 
classes to carry larger data or to associate simple logic with the data.
</LanguageSpecific>

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

<LanguageSpecific to="java">

### Serialization

All [properties that comply with JavaBean naming rules](https://www.baeldung.com/java-pojo-class#what-is-a-javabean) and
have both an accessor, a mutator method (i.e. `get` and `set` methods for the property) and are not annotated with
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/data/NonSerializedData.java</SourceClass>
annotation, are serialized into a complex type. See the following example:

<SourceCodeTabs>
[Associated data POJO](/docs/user/en/use/examples/dto.java)
</SourceCodeTabs>

Storing a complex type to entity is executed as follows:

<SourceCodeTabs requires="/docs/user/en/use/examples/dto.java,/docs/user/en/get-started/example/complete-startup.java,/docs/user/en/get-started/example/define-test-catalog.java,/docs/user/en/get-started/example/define-catalog-with-schema.java,/docs/user/en/use/api/example/open-session-manually.java">
[Storing associated data to an entity](/docs/user/en/use/examples/storing.java)
</SourceCodeTabs>

As you can see, annotations can be placed either on methods or property fields, so that if you use
[Lombok support](https://projectlombok.org/), you can still easily define the class as:

<SourceCodeTabs>
[Associated data Lombok POJO](/docs/user/en/use/examples/dto-lombok.java)
</SourceCodeTabs>

If the serialization process encounters any property that cannot be serialized, the
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/exception/SerializationFailedException.java</SourceClass>
is thrown.

#### Generic collections

You can use collections in complex types, but the specific collection types must be extractable from the collection 
generics in deserialization time. Look at the following example:

<SourceCodeTabs>
[Associated data POJO with collections](/docs/user/en/use/examples/dto-collection.java)
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

<SourceCodeTabs requires="/docs/user/en/use/examples/storing.java">
[Loading associated data from an entity](/docs/user/en/use/examples/loading.java)
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

</LanguageSpecific>