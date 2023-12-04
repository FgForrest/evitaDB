---
title: Data model
perex: |
    This article describes the structure of the database entity (alternative to record in a relational database or
    document in some NoSQL databases). Understanding the entity structure is crucial for working with
    evitaDB.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

<UsedTerms>
    <h4>Terms used in this document</h4>
	<dl>
		<dt>facet</dt>
		<dd>Facet is a property of entity used for quick filtering of entities by the user. It is displayed as
        a checkbox in the filter bar or as a slider in case of a large number of different numerical values. Facets help
        the customer to narrow down the current category list, manufacturer list, or full-text search results. It would
        be hard for the customer to go through dozens of pages of results and probably would be forced to look for some
        subcategory or find a better search phrase. It's frustrating for the user, and facets could make this process
        easier. With a few clicks, the user can narrow down the results to relevant facets. The key aspect here is to
        provide enough information and require the user to go to the most relevant facet combinations. It's very helpful
        to disregard facets as soon as they would cause no results to be returned, or even to inform the user that
        selecting a particular facet would narrow the results to very few records and that his freedom of choice will be
        severely limited.</dd>
		<dt>facet group</dt>
		<dd>Facet group is used to group facets of the same type. The facet group controls the mechanism of facet
        filtering. It means that facet groups allow to define whether facets in the group will be combined with boolean
        OR, AND relations when used in filtering. It also allows to define how this facet group will be combined with
        other facet groups in the same query (i.e. AND, OR, NOT). This type of Boolean logic affects the facet
        statistics calculation and is the crucial part of facet evaluation.</dd>
	</dl>
</UsedTerms>

The evitaDB data model consists of three layers:

1. [catalog](#catalog)
2. [entity collection](#collection)
3. [entity](#entity) (data)

Each catalog occupies a single folder within the evitaDB `data` folder. Each collection within this catalog is usually
represented by a single file (key/value store) in this folder. Entities are stored in binary format in the collection
file. More [details about the storage format](../deep-dive/storage-model.md) can be found in a separate chapter.

## Catalog

The catalog is a top-level isolation layer. It's equivalent to a *database* in other database terms. The catalog
contains a set of entity collections that maintain data for a single tenant. evitaDB doesn't support queries that could
span multiple catalogs. The catalogs are completely isolated from each other on disk and in memory.

A catalog is described by its [schema](schema.md#catalog). Changes to the catalog structure can only be made using
catalog schema mutations.

## Collection

The collection is a storage unit for data related to the same [entity type](#entity-type). It's equivalent to
a *collection* in terms of other NoSQL databases like MongoDB. In the relational world, the closest term is *table*,
but the collection in evitaDB manages much more data than a single relational table could. The correct projection
in the relational world would be "a set of logically related linked tables".

Collections in evitaDB are not isolated and entities in them can be related to entities in different collections.
Currently, the relationships are only unidirectional.

<Note type="info">

<NoteTitle toggles="true">

##### Do I need to define schema prior to inserting data?
</NoteTitle>

Although evitaDB requires a schema for each entity type, it supports automatic evolution if you allow it. If you don't
specify otherwise, evitaDB learns about entity attributes, their data types and all necessary relations as you add new
data. Once the attributes, associated data or other contours of the entity are known, they are enforced by evitaDB. This
mechanism is somewhat similar to the schema-less approach, but results in a much more consistent data store.
</Note>

A collection is described by its [schema](schema.md#entity). Changes to the entity type definition can only be made
using entity schema mutations.

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

<Note type="info">

Hierarchy placement is represented by `parent` field in:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java</SourceClass>.

Hierarchy definition is part of main entity schema:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/EntitySchemaContract.java</SourceClass>

</Note>

</LanguageSpecific>
<LanguageSpecific to="graphql">

<Note type="info">

Hierarchy placement is represented by `parentPrimaryKey` and `parents` fields in entity object.
Hierarchy definition is part of main entity schema.

</Note>

</LanguageSpecific>
<LanguageSpecific to="rest">

<Note type="info">
Hierarchy placement is represented by `parent` and `parentEntity` fields in entity object.
Hierarchy definition is part of main entity schema.
</Note>

</LanguageSpecific>

<Note type="question">

<NoteTitle toggles="true">

##### Why do we support hierarchical entities as the first class citizens?
</NoteTitle>

Most of the e-commerce systems organize their products in hierarchical category system. The categories are the source
for the catalog menus and when the user examines the category content, he/she usually sees products in the entire
category subtree of the category. That's why hierarchies are directly supported by evitaDB.
</Note>

More details about hierarchy placement are described in the [schema definition chapter](schema.md#hierarchy-placement).

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
fetches all attributes in a single block, so keeping this frequently used data in attributes reduces the overall I/O.

<LanguageSpecific to="java">

<Note type="info">
The attribute provider ([entity](#entity-type) or [reference](#references)) is represented by the interface:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AttributesContract.java</SourceClass>

The attribute schema is described by:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/AttributeSchemaContract.java</SourceClass>

</Note>

</LanguageSpecific>

More details about attributes are described in the [schema definition chapter](schema.md#attribute).

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

### Sortable attribute compounds

Sortable attribute compounds are not inserted into an entity, but are automatically created by the database when
an entity is inserted and maintain the index for the defined entity / reference attribute values. The attribute
compounds can only be used to sort the entities in the same way as the attribute.

### Associated data

Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
along with entity in order to display data to the target consumer (i.e. a user / API / bot). Associated data allows
storing all basic [data types](data-types.md#simple-data-types) and also [complex](data-types.md#complex-data-types),
document like types.

The [search query](../query/basics.md) must contain specific
[requirement](../query/requirements/fetching.md#associated-data) to fetch the associated data along with the entity.
Associated data are stored and fetched separately by their name and *locale* (if the associated data is
[localized](#localized-associated-data)).

<LanguageSpecific to="java">

<Note type="info">
AssociatedData provider ([entity](#entity-type)) is represented by the interface:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AssociatedDataContract.java</SourceClass>

Associated data schema is described by:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/AssociatedDataSchemaContract.java</SourceClass>

</Note>

</LanguageSpecific>

More details about associated data are described in the [schema definition chapter](schema.md#associated-data).

#### Localized associated data

Associated data value can contain localized values. It means that different values should be returned along with entity
when certain locale is used in the [search query](../query/basics.md). Localized data is a standard part of most
e-commerce systems and that's why evitaDB provides special treatment for it.

### References

The references, as the name suggests, refer to other entities (of the same or different entity type). The references
allow entity filtering by the attributes defined on the reference relation or the attributes of the referenced entities.
The references enable [statistics](../query/requirements/facet.md) computation if facet index is enabled for this
referenced entity type. The reference is uniquely represented by
[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
positive number (max. 2<sup>63</sup>-1) and
[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
entity type and may represent a <Term>facet</Term> that is part of one or more
<Term name="facet group">facet groups</Term>, also identified by
[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html). The reference identifier in an entity
is unique and belongs to a single group id. Among multiple entities, the reference to the same referenced entity may be
part of different groups.

The referenced entity type can refer to another entity managed by evitaDB, or it can refer to any external entity that
has a unique [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) key as its identifier. We
expect that evitaDB will only partially manage data and that it will coexist with other systems in a runtime - such as
content management systems, warehouse systems, ERPs and so on.

The references may carry additional key-value data related to this entity relationship (e.g. number of items present on
the relationship to a stock). The data on references is subject to the same rules as
[entity attributes](#attributes-unique-filterable-sortable-localized).

<LanguageSpecific to="java">

<Note type="info">
Reference is represented by the interface:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReferenceContract.java</SourceClass>.

Reference schema is described by:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/ReferenceSchemaContract.java</SourceClass>

</Note>

</LanguageSpecific>

More details about references are described in the [schema definition chapter](schema.md#reference).

### Prices

Prices are specific to very few entity types (usually products, shipping methods, and so on), but since correct price
calculation is a very complex and important part of e-commerce systems and highly affects the performance of entity
filtering and sorting, they deserve first-class support in the entity model. It is quite common in B2B systems that
a single product has dozens of prices assigned to different customers.

The price has the following structure:

<dl>
    <dt>
        [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) `priceId`
    </dt>
    <dd>
	    Contains the identification of the price in the external systems. This ID is expected to be used for
        synchronization of the price in relation to the primary source of the prices. The price with the same ID must
        be unique within the same entity. The prices with the same ID in multiple entities should represent the same
        price in terms of other values - such as validity, currency, price list, the price itself, and all other
        properties. These values can be different for a limited time (for example, the prices of Entity A and Entity B
        can be the same, but Entity A is updated in a different session/transaction and at a different time than
        Entity B).
    </dd>
    <dt>
        [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) `priceList`
    </dt>
    <dd>
        Contains the identification of the price list in the external system. Every price must refer to a price list.
        The price list identification can refer to another Evita entity or contain any external price list
        identification (e.g. ID or unique name of the price list in the external system).
		A single entity is expected to have a single price for the price list unless `validity' is specified. In other
        words, it makes no sense to have multiple concurrently valid prices for the same entity that are rooted in the
        same price list.
    </dd>
    <dt>[Currency](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html) `currency`</dt>
    <dd>
        Identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
    </dd>
    <dt>[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) `innerRecordId`</dt>
    <dd>
        Some special products (such as master products or product sets) may contain prices of all "child" products so
        that the aggregating product can display them in certain views of the product. In this case, it is necessary
        to distinguish the projected prices of the subordinate products in the product that represents them.
    </dd>
    <dt>
        [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html)
        `priceWithoutTax`
    </dt>
    <dd>
        Price without tax.
    </dd>
    <dt>
        [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html)
        `priceWithTax`
    </dt>
    <dd>
        Price with tax.
    </dd>
    <dt>
        [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html)
        `taxRate`
    </dt>
    <dd>
        Tax percentage (i.e. for 19% it'll be 19.00)
    </dd>
    <dt>
        [DateTimeRange](data-types.md#datetimerange) `validity`
    </dt>
    <dd>
        Date and time interval for which the price is valid (inclusive).
    </dd>
    <dt>
        [boolean](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) `sellable`
    </dt>
    <dd>
        Controls whether the price is subject to filtering/sorting logic, unindexed prices will be fetched along with
        the entity, but will not be considered when evaluating the query. These prices can be used for "informational"
        prices, such as the reference price (the crossed out price often found on e-commerce sites as the
        "usual price"), but are not used as the "price for sale".
    </dd>
</dl>

<LanguageSpecific to="java">

<Note type="info">
Price provider is represented by the interface:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PricesContract.java</SourceClass>

Single price is represented by the interface:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>

Price schema is part of main entity schema:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/EntitySchemaContract.java</SourceClass>

</Note>

</LanguageSpecific>

<Note type="question">

<NoteTitle toggles="true">

##### Want to know more about how the price for sale is calculated?
</NoteTitle>

The algorithm is quite complex and needs a lot of examples to understand it. Therefore, there is
a [separate chapter on this subject](../query/filtering/price.md#price-for-sale-computation-algorithm).
</Note>

More details about prices are described in the [schema definition chapter](schema.md#prices).