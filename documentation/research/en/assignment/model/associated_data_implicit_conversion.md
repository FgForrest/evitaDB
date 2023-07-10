---
title: Associated data implicit conversion process
perex: |
  Related data represents complex unstructured or semi-structured documents that can be automatically converted 
  from/to Java POJO classes automatically using implicit conversion mechanisms.
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

The complex types are all types that don't qualify as [simple evitaDB types](data_types) (or an array of simple evitaDB types)
and don't belong to a `java` package (i.e. `java.lang.URL` is forbidden to be stored in evitaDB, even if it is Serializable,
because is in the package `java`, and is not directly supported by the basic data types). The complex types are targeted 
for the client POJO classes to carry bigger data or associate simple logic along with the data.

<Note type="info">
Associated data may even contain array of POJOs. Such data will be automatically converted to an array of
`ComplexDataObject` types - i.e. `ComplexDataObject[]`.
</Note>

### The complex type can contain the properties of:

- any [simple evitaDB types](data_types)
- any other complex types (additional inner POJOs)
- generic [Lists](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html)
- generic [Sets](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)
- generic [Maps](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Map.html)
- any array of [simple evitaDB types](data_types) or complex types

Collection generics must be resolvable to an exact class (meaning that wildcard generics are not supported). The complex type may also be
an immutable class, accepting properties via the constructor parameters. Immutable classes must be compiled with the javac
`-parameters` argument, and their names in the constructor must match their property names of the getter fields. This plays
really well with [Lombok @Data annotation](https://projectlombok.org/features/Data).

## Serialization

Storing a complex type to entity is executed as follows:

``` java
new InitialEntityBuilder('product')
	.setAssociatedData('stockAvailability', new ProductStockAvailabilityDTO());
```

All [properties that comply with JavaBean naming rules](https://www.baeldung.com/java-pojo-class#what-is-a-javabean) and
have both an accessor, a mutator method (i.e. `get` and `set` methods for the property) and are not annotated with
<SourceClass>[NonSerializedData.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/data/NonSerializedData.java)</SourceClass>
annotation, are serialized into a complex type. See the following example:

``` java
public class ProductStockAvailabilityDTO implements Serializable {
    private int id;
    private String stockName;
    @NonSerializedData
    private URL stockUrl;
    private URL stockMotive;
    
    // id gets serialized - both methods are present and are valid JavaBean property methods
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    
    // id gets serialized - both methods are present and are valid JavaBean property methods
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    
    // stockUrl will not be serialized - corresponding field is annotated with @NonSerializedData
    public URL getStockUrl() { return stockUrl; }
    public void setStockUrl(URL stockUrl) { this.stockUrl = stockUrl; }
    
    // active will not be serialized - it has no corresponding mutator method
    public isActive() { return false; }
    
    // stock motive will not be serialized because getter method is marked with @NonSerializedData
    @NonSerializedData
    public URL getStockMotive() { return stockMotive; }
    public void setStockMotive(URL stockMotive) { this.stockMotive = stockMotive; }
    
}
```

As you can see, annotations can be placed either on methods or property fields, so that if you use
[Lombok support](https://projectlombok.org/), you can still easily define the class:

``` java
@Data
public class ProductStockAvailabilityDTO implements Serializable {
    private int id;
    private String stockName;
    @NonSerializedData private URL stockUrl;
    @NonSerializedData private URL stockMotive;
    
    public isActive() { return false; }
}
```

If the serialization process encounters any property that cannot be serialized, the
<SourceClass>[SerializationFailedException.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/exception/SerializationFailedException.java)</SourceClass> is thrown.

### Generic collections

You can use collections in complex types, but the specific collection types must be extractable from the collection generics
in deserialization time. Look at the following example:

``` java
@Data
public class SomeDataWithCollections implements Serializable {
    private List<String> names;
    private Map<String, Integer> index;
    private Set<BigDecimal> amounts;
    private SomeDataWithCollections[] innerContainers;
}
```

### Recommended test coverage

Because methods that don't follow the JavaBeans contract are silently skipped, it is highly recommended to always
store and retrieve associated data in the unit test and check that all important data is actually stored:

``` java
@Test
void verifyProductStockAvailabilityDTOIsProperlySerialized() {
    final EntityBuilder entity = new InitialEntityBuilder('product');
    final ProductStockAvailabilityDTO stockAvailabilityBeforeStore = new ProductStockAvailabilityDTO(); 
    entity.setAssociatedData('stockAvailability', stockAvailabilityBeforeStore);
    final SealedEntity loadedEntity = entity(); //some custom logic to load proper entity
    final ProductStockAvailabilityDTO stockAvailabilityAfterLoad = loadedEntity.getAssociatedData(
        'stockAvailability', ProductStockAvailabilityDTO.class
    );
    assertEquals(
        stockAvailabilityBeforeStore, stockAvailabilityAfterLoad, 
        "ProductStockAvailabilityDTO was not entirely serialized!"
    );
}
```

## Deserialization, model evolution support

Retrieving a complex type from an entity is executed as follows:

``` java
final SealedEntity entity = entity(); //some custom logic to load proper entity
final ProductStockAvailabilityDTO stockAvailability = entity.getAssociatedData(
    'stockAvailability', ProductStockAvailabilityDTO.class
);
```

Complex types are internally converted to a <SourceClass>[ComplexDataObject.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/dataType/ComplexDataObject.java)</SourceClass> type, 
that can be safely stored in evitaDB storage. The (de)serialization process is also designed to prevent data loss, and allow model evolution.

The deserialization process may fail with two exceptions:

- <SourceClass>[UnsupportedDataTypeException.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/exception/UnsupportedDataTypeException.java)</SourceClass>
  is raised when certain property cannot be deserialized due to an incompatibility
  with the specified [contract](#complex-type-can-contain-properties-of)
- <SourceClass>[IncompleteDeserializationException.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/exception/IncompleteDeserializationException.java)</SourceClass>
  is raised when any of the serialized data was not deserialized due to a lack of a mutator method on the class it's being converted to
  
### Field removal

The <SourceClass>[IncompleteDeserializationException.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/exception/IncompleteDeserializationException.java)</SourceClass> 
exception protects developers from unintentional data loss by making a mistake in the Java model and then executing:

- a fetch of existing complex type
- altering a few properties
- storing it back again to evitaDB

If there is legal reason for dropping some data stored along with its complex type in the previous versions of the application,
you can use <SourceClass>[DiscardedData.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/data/DiscardedData.java)</SourceClass> annotation
on any complex type class to declare that it is ok to throw away data during deserialization.

**Example:**

Associated data were stored with this class definition:

``` java
@Data
public class ProductStockAvailabilityDTO implements Serializable {
    private int id;
    private String stockName;
}
```

In future versions, developer will decide that the `id` field is not necessary anymore and may be dropped. But there is a lot
of data written by the previous version of the application. So, when dropping a field, we need to make a note for evitaDB
that the presence of any `id` data is ok, even if there is no field for it anymore. This data will be discarded when
the associated data gets rewritten by the new version of the class:

``` java
@Data
@DiscardedData("id");
public class ProductStockAvailabilityDTO implements Serializable {
    private String stockName;
}
```

### Field renaming and controlled migration

There are also situations when you need to rename the field (for example you made a typo in the previous version of the
Java Bean type). In such case you'd also experience the
<SourceClass>[IncompleteDeserializationException.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/exception/IncompleteDeserializationException.java)</SourceClass>
when you try to deserialize the type with the corrected Java Bean definition. In this situation, you can use the
<SourceClass>[RenamedData.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/data/RenamedData.java)</SourceClass> annotation to migrate old versions of data.

**Example:**

First version of the Java type with the mistake:

``` java
@Data
public class ProductStockAvailabilityDTO implements Serializable {
    private String stockkName;
}
```

Next time we'll try to fix the typo:

``` java
@Data
public class ProductStockAvailabilityDTO implements Serializable {
    @RenamedData("stockkName")
    private String stockname;
}
```

But we make yet another mistake, so we need another correction:

``` java
@Data
public class ProductStockAvailabilityDTO implements Serializable {
    @RenamedData({"stockkName", "stockname"})
    private String stockName;
}
```

We may get rid of those annotations when we're confident there is no data with the old contents in evitaDB.
Annotation <SourceClass>[RenamedData.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/data/RenamedData.java)</SourceClass>
can also be used for model evolution - i.e. automatic translation of an old data format to the new one.

**Example:**

Old model:

``` java
@Data
public class ProductStockAvailabilityDTO implements Serializable {
    private String stockName;
}
```

New model:

``` java
@Data
public class ProductStockAvailabilityDTO implements Serializable {
    private String upperCasedStockName;
    
    @RenamedData
    public void setStockName(String stockName) {
        this.upperCasedStockName = stockName == null ? null : stockName.toUpperCase();
    }
}
```
