---
title: Data model
perex: |
    This article describes the structure of the database entity (alternative to record in a relational database or
    document in some NoSQL databases). Understanding the entity structure is crucial for working with
    evitaDB.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
---

TODO JNO - write me

## Catalog

## Entity

Minimal entity definition consists of: 

- [Entity type](#entity-type) 
- [Primary key](#primary-key)

Other entity data is purely optional and may not be used at all. The primary key can be set to `NULL` and let 
the database generate it automatically.

<LanguageSpecific to="java">
This minimal entity structure is covered by interface 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityReferenceContract.java</SourceClass>.
Full entity with data, references, attributes and associated data is represented by interface
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java</SourceClass>.
</LanguageSpecific>

### Entity type

Entity type must be [String type](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html).
Entity type is the main business key (equivalent to a *table name* in relational database) - all data of entities of 
the same type is stored in a separate index. Within the entity type the entity is uniquely represented by
[the primary key](#primary-key).

<Note type="info">

<NoteTitle toggles="true">

##### Is the entity type restricted by a schema?
</NoteTitle>

Yes, the entity type is described by its [schema](schema.md).

Although evitaDB requires a schema for each entity type, it supports automatic evolution if you allow it. If you don't
specify otherwise, evitaDB learns about entity attributes, their data types and all necessary relations as you add new
data. Once the attributes, associated data or other contours of the entity are known, they are enforced by evitaDB. This
mechanism is somewhat similar to the schema-less approach, but results in a much more consistent data store.

The details about [schema definition](schema.md) are part of different document.
</Note>

### Primary key

Primary key must be [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) positive 
number (max. 2<sup>63</sup>-1). It can be used for fast lookup of entity(s). Primary key must be unique 
within the same [entity type](#entity-type).

It can be left `NULL` if it is to be generated automatically by the database. The primary key allows evitaDB to decide 
whether the entity should be inserted as a new entity or whether an existing entity should be updated instead.

<Note type="question">

<NoteTitle toggles="true">

##### Why the limited `int` type was chosen for the primary key?
</NoteTitle>

All primary keys are stored in data structure called "[RoaringBitmap](https://github.com/RoaringBitmap/RoaringBitmap)".
It was originally written in [C](https://github.com/RoaringBitmap/CRoaring) by [Daniel Lemire](https://lemire.me/blog/),
and a team, led by [Richard Statin](https://richardstartin.github.io/), managed to port it to Java. The library is 
used in many existing databases for similar purposes (Lucene, Druid, Spark, Pinot and many others).

We chose this library for two main reasons:

1. it allows us to store int arrays in a more compressed format than a simple array of primitive integers,
2. and contains the algorithms for fast boolean operations on these integer sets

This data structure works best for integers that are close together. This fact plays well with the database sequences 
that produce numbers incremented by one. There is a variant of the same data structure that works with the `long` type, 
but it has two drawbacks:

1. it uses twice as much memory
2. it's much slower for Boolean operations

Since evitaDB is an in-memory database, we expect that the number of entities will not exceed two billion.
</Note>

### Hierarchical placement

Entities can be organized hierarchically. This means that an entity can refer to a single parent entity and can be 
referred to by multiple child entities. A hierarchy always consists of entities of the same type.

Each entity must be part of at most one hierarchy (tree).

<LanguageSpecific to="java">
Hierarchy placement is represented by the interface:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/HierarchicalPlacementContract.java</SourceClass>.
</LanguageSpecific>

<Note type="question">

<NoteTitle toggles="true">

##### Why do we support hierarchical entities as the first class citizens?
</NoteTitle>

Most of the e-commerce systems organize their products in hierarchical category system. The categories are the source
for the catalog menus and when the user examines the category content, he/she usually sees products in the entire 
category subtree of the category. That's why hierarchies are directly supported by evitaDB.
</Note>

### Attributes (unique, filterable, sortable, localized)

The entity attributes allow you to define a set of data to be fetched in bulk along with the entity body.
Each attribute schema can be marked as filterable to allow filtering by it, or sortable to allow sorting by it.

<Note type="warning">
The attributes are automatically filterable / sortable when they are automatically added by the automatic schema 
evolution mechanism to make the "I don't care" approach to the schema easy and "just working". However, filterable or 
sortable attributes require indexes that are kept entirely in memory by evitaDB, and this approach leads to a waste of 
resources. Therefore, we recommend to use the schema-first approach and to mark as filterable / sortable only those 
attributes that are really used for filtering / sorting.
</Note>

Attributes are also recommended to be used for frequently used data that accompanies the entity (for example "name". 
"perex", "main motive"), even if you don't necessarily need it for filtering/sorting purposes. evitaDB stores and 
fetches all attributes in a single container, so keeping this frequently used data in attributes reduces the overall 
I/O.

<LanguageSpecific to="java">
The attribute provider ([entity](#entity-type) or [reference](#references)) is represented by the interface:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AttributesContract.java</SourceClass>

The attribute schema is described by:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/AttributeSchemaContract.java</SourceClass>
</LanguageSpecific>

#### Localized attributes

An attribute can contain localized values. This means that different values should be used for filtering/sorting and 
should be returned together with the entity when a specific locale is used in the 
[search query](../query/filtering/locale.md). Localized attributes are a standard part of most e-commerce systems and 
that's why evitaDB provides special treatment for them.

#### Data types in attributes

Attributes allow using [variety of data types](data-types.md) and their arrays. The database supports all basic types,
date-time types and <SourceClass>evita_common/src/main/java/io/evitadb/dataType/Range.java</SourceClass> types. Range
values are allowed using a special type of [query](../query/basics.md) filtering constraint - 
[`inRange`](../query/filtering/range.md). This filtering constraint allows to filter entities that are inside the range 
boundaries.

<Note type="question">

<NoteTitle toggles="true">

##### Can I store multiple values to single attribute?
</NoteTitle>

Any of the supported data types can be wrapped into an array - that is, the attribute can represent multiple values at 
once. Such an attribute cannot be used for sorting, but it can be used for filtering, where it will satisfy the filter 
constraint if **any** of the values in the array match the constraint predicate. This is particularly useful for ranges, 
where you can simply define multiple validity periods, for example, and the [`inRange`](../query/filtering/range.md) 
constraint will match all entities that have at least one period that includes the input date and time (this is another 
common use case in e-commerce systems).
</Note>

[//]: # (TODO JNO - continue from here)

### Associated data

Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
along with entity in order to display data to the target consumer (i.e. an user / API / bot). Associated data allow
storing all basic [data types](model/data_types) and also complex, document like types.

The complex data type is used for rich objects, such as Java POJOs and [automatically converted by](model/associated_data_implicit_conversion)
to an internal representation that is composed solely of supported data types (or another complex objects) and can be
deserialized back to the client custom POJO on demand providing the POJO structure matches the original document format.

AssociatedData provider ([entity](#entity-type)) is represented by the interface:
<SourceClass>[AssociatedDataContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/AssociatedDataContract.java)</SourceClass>

Associated data schema is described by:
<SourceClass>[AssociatedDataSchema.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/schema/AssociatedDataSchema.java)</SourceClass>

The [search query](querying/query_language) must contain specific [requirement](querying/query_language#require)
to fetch the associated data along with the entity. Associated data are stored and fetched separately by their name.

#### Localized associated data

Associated data value may contain localized values. It means that different values should be returned along with entity
when certain locale is used in the [search query](querying/query_language). Localized data are standard part of most
of the e-commerce systems, and that's why evitaDB provides special treatment for those.

### References

The references, as the name suggest, refer to other entities (of the same or different entity type).
The references allow entity filtering by the attributes defined on the reference relation or the attributes of
the referenced entities. The references enable [statistics](#parameters--faceted-search-) computation if
facet index is enabled for this referenced entity type. The reference is uniquely represented by
[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
positive number (max. 2<sup>63</sup>-1) and [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
entity type and can represent <Term>facet</Term> that is part of one or multiple <Term name="facet group">facet groups</Term>,
which are also identified by [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html).
The reference identifier in one entity is unique and belongs to single group id. Among multiple entities the reference
to same referenced entity may be part of different groups.

The referenced entity type may relate to another entity managed by evitaDB, or it may refer to any external entity
possessing unique [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) key as its identifier.
We expect that evitaDB will maintain data only partially, and that it will co-exist with other systems in one runtime -
such as content management systems, warehouse systems, ERPs and so on.

The references may carry additional key-value data linked to this entity relation (fe. item count present on the
relation to a stock). The data on references is subject to the same rules as [entity attributes](#attributes--unique-filterable-sortable-localized-).

Reference is represented by the interface: <SourceClass>[ReferenceContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/ReferenceContract.java)</SourceClass>.
Reference schema is described by: <SourceClass>[ReferenceSchema.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/schema/ReferenceSchema.java)</SourceClass>

### Prices

Prices are specific to a very few entity types (usually products, shipping methods and so on), but because correct price
computation is very complex and important part of the e-commerce systems and highly affects performance of the entities
filtering and sorting, they deserve first class support in entity model. It is pretty common in B2B systems a single
product has assigned dozens of prices for the different customers.

Price provider is represented by the interface:
<SourceClass>[PricesContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/PricesContract.java)</SourceClass>
Single price is represented by the interface:
<SourceClass>[PriceContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/PriceContract.java)</SourceClass>

Price schema is part of main entity schema:
<SourceClass>[EntitySchema.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/schema/EntitySchema.java)</SourceClass>

<Note type="info">
For detail information about price for sale computation [see this article](querying/price_computation.md).
</Note>