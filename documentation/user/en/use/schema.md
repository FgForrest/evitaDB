---
title: Schema
perex: |
    A schema is the logical representation of a catalog that specifies the types of entities that can be stored and 
    the relationships between them. It allows you to maintain the consistency of your data and is very useful 
    for automatic generation of the web APIs on top of it.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'java'
---

evitaDB internally maintains a schema for each [entity collection](data-model.md#collection) / [catalog](data-model.md#catalog), 
although it supports a [relaxed approach](#evolution), where the schema is automatically built according to data 
inserted into the database.

The schema is not only crucial for maintaining data consistency, but is also a key source for web API schema 
generation. It allows us to create [Open API](connectors/rest.md) and [GraphQL](connectors/graphql.md) schemas. If you
pay close attention to the schema definition, you'll be rewarded with nice, understandable, and self-documented APIs. 
Every single piece of information in the schema affects the way the web APIs look. For example, relation cardinality 
(zero or one, exactly one, zero or more, one or more) affects whether the API marks the relation as optional, returns 
a single value/object, or returns an array of them. Filterable attributes are propagated to the documented query 
language blocks, while non-filterable attributes are not. The data types of the attributes affect which query 
constraints can be used in relation to this very attribute, and so on. The documentation you write in the evitaDB schema
is propagated to all your APIs. You can read more about this projection in the dedicated Web API chapters of the 
documentation.

## Mutations and versioning

The schema can only be changed by what are called *mutations*. While this is a rather cumbersome approach, it has some
big advantages for the system:

- **mutation represents an isolated change to the schema** - this means that the client making the schema change 
  only sends deltas to the server, which saves a lot of network traffic and also implies server-side logic that doesn't
  need to resolve deltas internally
- **mutation is directly used as a [WAL](../deep-dive/transactions.md#write-ahead-log) entry** - the mutation 
  represents an atomic operation in the transactional log that is distributed across the cluster, and it also 
  represents a place where conflict resolution takes place (if the server receives similar mutations from two 
  parallel sessions, it easily decides whether to throw a concurrent change exception - if the mutations are equal,  
  there is no conflict; if they are different, the first mutation is accepted and the second is rejected with an 
  exception)

The schema is versioned - each time a schema mutation is performed, its version number is incremented by one. If you 
have two schema instances on the client side, you can easily tell if they're the same by comparing their version 
number, and if not, which one is newer.

<Note type="question">

<NoteTitle toggles="true">

##### Do I really have to write all the mutations by hand?
</NoteTitle>

Hopefully not. We're aware that writing mutations is cumbersome, and provide better support in our drivers. The client
drivers wrap the immutable schemas inside the builder objects, so you can just call alter methods on them and 
the builder will generate the list of mutations at the end. See [the example](#schema-definition-example).

However, if you want to use evitaDB on a platform that is not yet supported and covered by a specific client driver, 
you have to work directly with our web APIs that only accept mutations, and you have no other options than to write 
the mutations directly or to write your own client driver. But you can open source it and help the community. Let us 
know about it!
</Note>

<LanguageSpecific to="java">
All schema mutations implement interface 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/SchemaMutation.java</SourceClass>
</LanguageSpecific>
<LanguageSpecific to="csharp">
All schema mutations implement interface 
<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/ISchemaMutation.cs</SourceClass>
</LanguageSpecific>

## Structure

There are following types of schemas:

- [catalog schema](#catalog)
- [entity schema](#entity)
- [attribute schema](#attributes)
- [associated data schema](#associated-data)
- [reference schema](#reference)

### Catalog

Catalog schema contains list of [entity schemas](#entity), the `name` and `description` of the catalog. It also keeps
dictionary of [global attribute schemas](#global-attribute-schema) that can be shared among multiple 
[entity schemas](#entity).

<Note type="info">

<NoteTitle toggles="true">

##### Name requirements and name variants
</NoteTitle>

Each named data object - [catalog](#catalog), [entity](#entity), [attribute](#attribute), 
[associated data](#associated-data) and [reference](#reference) must be uniquely identifiable by its name within its
parent scope.

<LanguageSpecific to="java,evitaql,rest,graphql">
The name validation logic and reserved words are present in the class 
<SourceClass>evita_common/src/main/java/io/evitadb/utils/ClassifierUtils.java</SourceClass>.

There is also a special property called `nameVariants` in the schema of each named object. It contains variants
of the object name in different "developer" notations such as *camelCase*, *PascalCase*, *snake_case* and so on. See
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/catalog/schemaApi/model/NameVariantsDescriptor.java</SourceClass>
for a complete listing.
</LanguageSpecific>
<LanguageSpecific to="csharp">
The name validation logic and reserved words are present in the class
<SourceClass>EvitaDB.Client/Utils/ClassifierUtils.cs</SourceClass>.

There is also a special property called `nameVariants` in the schema of each named object. It contains variants
of the object name in different "developer" notations such as *camelCase*, *PascalCase*, *snake_case* and so on. See
<SourceClass>NamingConvention.cs</SourceClass> for a complete listing.
</LanguageSpecific>

</Note>

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to catalog
</NoteTitle>

<LanguageSpecific to="java,evitaql,rest,graphql">

Top level mutations:
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/catalog/CreateCatalogSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/catalog/RemoveCatalogSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/catalog/ModifyCatalogSchemaMutation.java</SourceClass>**

Within `ModifyCatalogSchemaMutation` you can use mutations:

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/catalog/ModifyCatalogSchemaNameMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/catalog/ModifyCatalogSchemaDescriptionMutation.java</SourceClass>**

</LanguageSpecific>

<LanguageSpecific to="csharp">

Top level mutations:
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Catalogs/CreateCatalogSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Catalogs/RemoveCatalogSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Catalogs/ModifyCatalogSchemaMutation.cs</SourceClass>**

Within `ModifyCatalogSchemaMutation` you can use mutations:

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Catalogs/ModifyCatalogSchemaNameMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Catalogs/ModifyCatalogSchemaDescriptionMutation.cs</SourceClass>**
</LanguageSpecific>

And [entity top level mutations](#entity).

<LanguageSpecific to="java">

The catalog schema is described by:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/CatalogSchemaContract.java</SourceClass>

</LanguageSpecific>

<LanguageSpecific to="csharp">

The catalog schema is described by:
<SourceClass>EvitaDB.Client/Models/Schemas/ICatalogSchema.cs</SourceClass>

</LanguageSpecific>

</Note>

#### Global attribute schema

Global attribute schema has the same structure as [attribute schema](#attribute) except for one additional 
characteristic. A global attribute can be made `uniqueGlobally`, which means that values of such an attribute must be 
unique across all entities and entity types in the entire catalog.

<Note type="question">

<NoteTitle toggles="true">

##### What is the global uniqueness good for?
</NoteTitle>

Well, it is useful for entity URL that we naturally want to be unique among all entities in the catalog. The global 
unique attribute allows us to ask evitaDB for an entity with a specific value without knowing its type in advance. 
This solves the use case when a new request arrives in your application and you need to check if there is an entity 
that matches it (no matter if it's a product, category, brand, group or whatever types you have in your project).
</Note>

A global attribute can also be used as a "dictionary definition" for an attribute that is used in multiple entity
collections, and we want to make sure it's named and described the same in all of them. An entity collection cannot
define an attribute with the same name as the global attribute. It can only "use" the global attribute with that name
and thus share its complete definition.

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to global attribute
</NoteTitle>

<LanguageSpecific to="java,evitaql,rest,graphql">

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/CreateGlobalAttributeSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/UseGlobalAttributeSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/SetAttributeSchemaGloballyUniqueMutation.java</SourceClass>**

And of course all [standard attribute mutations](#attributes).
</LanguageSpecific>
<LanguageSpecific to="csharp">

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/CreateGlobalAttributeSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/UseGlobalAttributeSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/SetAttributeSchemaGloballyUniqueMutation.cs</SourceClass>**

And of course all [standard attribute mutations](#attributes).
</LanguageSpecific>

<LanguageSpecific to="java">
The global attribute schema is described by:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/GlobalAttributeSchemaContract.java</SourceClass>
</LanguageSpecific>

<LanguageSpecific to="csharp">
The global attribute schema is described by:
<SourceClass>EvitaDB.Client/Models/Schemas/IGlobalAttributeSchema.cs</SourceClass>
</LanguageSpecific>

</Note>

### Entity

Entity schema contains information about the `name`, `description` and the:

- [enabling primary key generation](#primary-key-generation)
- [evolution limits](#evolution)
- [allowed locales and currencies](#locales-and-currencies)
- [enabling hierarchical structure](#hierarchy-placement)
- [enabling price information](#prices)
- [attributes](#attribute)
- [associated data](#associated-data)
- [references](#reference)

Entity schema can be made *deprecated*, which will be propagated to generated web API documentation.

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to entity type
</NoteTitle>

<LanguageSpecific to="java,evitaql,rest,graphql">

Top level entity mutations:

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/catalog/CreateEntitySchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/catalog/RemoveEntitySchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/catalog/ModifyEntitySchemaNameMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/catalog/ModifyEntitySchemaMutation.java</SourceClass>**

Within `ModifyEntitySchemaMutation` you can use mutations:

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/entity/ModifyEntitySchemaDescriptionMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/entity/ModifyEntitySchemaDeprecationNoticeMutation.java</SourceClass>**

</LanguageSpecific>

<LanguageSpecific to="csharp">

Top level entity mutations:

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Catalogs/CreateEntitySchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Catalogs/RemoveEntitySchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Catalogs/ModifyEntitySchemaNameMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Catalogs/ModifyEntitySchemaMutation.cs</SourceClass>**

Within `ModifyEntitySchemaMutation` you can use mutations:

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Entities/ModifyEntitySchemaDescriptionMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Entities/ModifyEntitySchemaDeprecationNoticeMutation.cs</SourceClass>**

</LanguageSpecific>

<LanguageSpecific to="java">
The entity schema is described by:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/EntitySchemaContract.java</SourceClass>
</LanguageSpecific>
<LanguageSpecific to="csharp">
The entity schema is described by:
<SourceClass>EvitaDB.Client/Models/Schemas/IEntitySchema.cs</SourceClass>
</LanguageSpecific>

</Note>

#### Primary key generation

<LanguageSpecific to="java,evitaql,rest,graphql">
If primary key generation is enabled, evitaDB assigns a unique
[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) number to a newly inserted entity.
The primary key always starts with `1` and is incremented by `1`. evitaDB guarantees its uniqueness within the same 
entity type. The primary keys generated in this way are optimal for binary operations in the data structures used.
</LanguageSpecific>
<LanguageSpecific to="csharp">
If primary key generation is enabled, evitaDB assigns a unique
[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32) number to a newly inserted entity.
The primary key always starts with `1` and is incremented by `1`. evitaDB guarantees its uniqueness within the same 
entity type. The primary keys generated in this way are optimal for binary operations in the data structures used.
</LanguageSpecific>

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to primary key
</NoteTitle>

Within `ModifyEntitySchemaMutation` you can use mutation:

<LanguageSpecific to="java,evitaql,rest,graphql">

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/entity/SetEntitySchemaWithGeneratedPrimaryKeyMutation.java</SourceClass>**

</LanguageSpecific>

<LanguageSpecific to="csharp">

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Entities/SetEntitySchemaWithGeneratedPrimaryKeyMutation.cs</SourceClass>**

</LanguageSpecific>

</Note>

#### Evolution

We recommend the schema-first approach, but there are cases where you don't want to bother with the schema and just want
to insert and query the data (e.g. rapid prototyping). When a new [catalog](data-model.md#catalog) is created, it is set up
in "auto evolution" mode, where the schema adapts to the data on first insertion. If you want to control the schema
strictly, you have to limit the evolution by changing the default schema. In strict mode, evitaDB throws an exception
if the input data violates the schema.

You still need to create [entity collections](data-model.md#collection) manually, but after that you can immediately insert
your data and the schema will be built accordingly. The existing schemas will still be validated on each entity
insertion/update - you will not be allowed to store the same attribute as a number type the first time and as a string
the next time. The first use will set up the schema, which must be respected from that moment on.

<Note type="info">
If the first entity has its primary key, evitaDB expects all entities to have their primary key set when inserting. 
If the first entity has its primary key set to `NULL`, evitaDB will generate primary keys for you and will reject 
external primary keys. New attribute schemas are implicitly created as `nullable`, `filterable` and non-array data types 
as `sortable`. This means that the client is immediately able to filter/sort on almost anything, but the database itself 
will consume a lot of resources. The references will be created as `indexed` but not `faceted`.
</Note>

<LanguageSpecific to="java,evitaql,rest,graphql">
There are several partial lax modes between strict and fully automatic evolution mode - see
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/EvolutionMode.java</SourceClass> for details.
For example - you can strictly control the entire schema, except for new locale or currency definitions, which are
allowed to be added automatically on first use.
</LanguageSpecific>

<LanguageSpecific to="csharp">
There are several partial lax modes between strict and fully automatic evolution mode - see
<SourceClass>EvitaDB.Client/Models/Schemas/EvolutionMode.cs</SourceClass> for details.
For example - you can strictly control the entire schema, except for new locale or currency definitions, which are
allowed to be added automatically on first use.
</LanguageSpecific>

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to evolution mode
</NoteTitle>

<LanguageSpecific to="java,evitaql,rest,graphql">

Within `ModifyEntitySchemaMutation` you can use mutations:

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/entity/AllowEvolutionModeInEntitySchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/entity/DisallowEvolutionModeInEntitySchemaMutation.java</SourceClass>**

</LanguageSpecific>

<LanguageSpecific to="csharp">

Within `ModifyEntitySchemaMutation` you can use mutations:

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Entities/AllowEvolutionModeInEntitySchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Entities/DisallowEvolutionModeInEntitySchemaMutation.cs</SourceClass>**

</LanguageSpecific>

</Note>

#### Locales and currencies

The schema specifies a list of allowed currencies and locales. We assume that the list of allowed currencies / locales 
will be relatively small (units, max lower tens of them) and if the system knows them in advance, it can generate enums 
for each of them in a web APIs. This helps developers to write queries with auto-completion. There is another positive
effect. E-commerce systems don't often extend the list of used currencies or locales (because there are usually a lot 
of manual operations involved), and having the allowed set guarded by the system eliminates the possibility of inserting
invalid prices or localizations by mistake.

<Note type="question">

<NoteTitle toggles="true">

##### Why are price lists not listed in the schema if currencies are?
</NoteTitle>

The price lists are closer to "data" than locales or currencies. The set of price lists is expected to change very 
often, and their numbers can reach high cardinality (thousands, tens of thousands). It wouldn't be practical to generate 
enumeration values for them and change the Web API schemas every time a price list is added or removed.
</Note>

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to locales & currencies
</NoteTitle>

<LanguageSpecific to="java,evitaql,rest,graphql">

Within `ModifyEntitySchemaMutation` you can use mutations:

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/entity/AllowCurrencyInEntitySchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/entity/DisallowCurrencyInEntitySchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/entity/AllowLocaleInEntitySchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/entity/DisallowLocaleInEntitySchemaMutation.java</SourceClass>**
</LanguageSpecific>
- 
<LanguageSpecific to="csharp">

  Within `ModifyEntitySchemaMutation` you can use mutations:

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Entities/AllowCurrencyInEntitySchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Entities/DisallowCurrencyInEntitySchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Entities/AllowLocaleInEntitySchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Entities/DisallowLocaleInEntitySchemaMutation.cs</SourceClass>**

</LanguageSpecific>

</Note>

#### Hierarchy placement

When hierarchy placement is enabled, entities of this type can form a tree structure. Each entity can have a maximum 
of one parent node and zero or more child entities. Neither the depth of the tree nor the number of siblings at each 
level is limited.

Enabling hierarchy placement implies the creation of a new 
<SourceClass>evita_engine/src/main/java/io/evitadb/index/hierarchy/HierarchyIndex.java</SourceClass> for the involved 
entity type. When another entity references a hierarchy entity and the reference is marked as *indexed*, the special 
<SourceClass>io.evitadb.index.ReducedEntityIndex</SourceClass> is created for each hierarchical entity. This index will 
hold reduced attribute and price indices of the referencing entity, allowing quick evaluation of 
[`withinHierarchy`](../query/filtering/hierarchy.md) filter conditions.

##### Orphan hierarchy nodes

The typical problem associated with creating a tree structure is the order in which nodes are attached to it. In
order to have a consistent tree, one should start from the root nodes and gradually descend along the axis of their
children. This isn't always easy to do when we need to copy an existing tree to an external system (for scripting
purposes, it's much easier and more performance-effective to index in batch using the natural order of records). Similar
situation is when the intermediate tree node needs to be removed, but its children do not. We can force developers to
rewire children to different parents before removing their parent, but they often don't have direct control over the
order of operations and can't easily do that.

That's why evitaDB recognizes so-called **orphan hierarchy nodes**. An orphan node is a node that declares itself to be
a child of a parent node with a certain primary key that evitaDB doesn't know yet (or the orphan node itself). Orphan
nodes do not participate in the evaluation of [queries on hierarchical structures](../query/filtering/hierarchy.md),
but are present in the index. If a node of a referenced primary key is appended to the main hierarchy tree, the 
orphan nodes (sub-trees) are also appended. In this way, the hierarchy tree eventually becomes consistent.

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to hierarchy placement
</NoteTitle>

<LanguageSpecific to="java,evitaql,rest,graphql">

Within `ModifyEntitySchemaMutation` you can use mutation:

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/entity/SetEntitySchemaWithHierarchyMutation.java</SourceClass>**
</LanguageSpecific>

<LanguageSpecific to="csharp">

Within `ModifyEntitySchemaMutation` you can use mutation:

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Entities/SetEntitySchemaWithHierarchyMutation.cs</SourceClass>**
</LanguageSpecific>

</Note>

### Prices

When prices are enabled, entities of this type can have a set of prices associated with them and can be 
[filtered](../query/filtering/price.md) and [sorted](../query/ordering/price.md) by price constraints. Single entity 
can have zero or more prices (the system is designed for situation when entity has tens or hundreds of prices attached 
to it).For each combination of `priceList` and `currency` there is a special 
<SourceClass>evita_engine/src/main/java/io/evitadb/index/price/PriceListAndCurrencyPriceSuperIndex.java</SourceClass>.

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to hierarchy placement
</NoteTitle>

Within `ModifyEntitySchemaMutation` you can use mutation:
<LanguageSpecific to="java,evitaql,rest,graphql">
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/entity/SetEntitySchemaWithPriceMutation.java</SourceClass>**
</LanguageSpecific>
<LanguageSpecific to="csharp">
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Entities/SetEntitySchemaWithPriceMutation.cs</SourceClass>**
</LanguageSpecific>

</Note>

### Attributes

An entity type can have zero or more attributes. The system is designed for situation when entity has tens of 
attributes. You should pay attention to the number of `filterable` / `sortable` / `unique` attributes. There is a 
separate instance of 
<SourceClass>evita_engine/src/main/java/io/evitadb/index/attribute/FilterIndex.java</SourceClass> for each filterable
attribute, <SourceClass>evita_engine/src/main/java/io/evitadb/index/attribute/SortIndex.java</SourceClass> for each
sortable attribute and <SourceClass>evita_engine/src/main/java/io/evitadb/index/attribute/UniqueIndex.java</SourceClass>
or <SourceClass>evita_engine/src/main/java/io/evitadb/index/attribute/GlobalUniqueIndex.java</SourceClass> for each
unique attribute. Attributes that are neither `filterable` / `sortable` / `unique` don't consume operating memory.

<LanguageSpecific to="java,evitaql,rest,graphql">
Attribute schema can be marked as `localized`, meaning that it only makes sense in a specific 
[locale](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html). 
</LanguageSpecific>
<LanguageSpecific to="csharp">
Attribute schema can be marked as `localized`, meaning that it only makes sense in a specific 
[locale](https://learn.microsoft.com/en-us/dotnet/api/system.globalization.cultureinfo). 
</LanguageSpecific>

Attribute schema can be made *deprecated*, which will be propagated to generated web API documentation.

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to attribute
</NoteTitle>

<LanguageSpecific to="java,evitaql,rest,graphql">
Within `ModifyEntitySchemaMutation` you can use mutation:

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/CreateAttributeSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/RemoveAttributeSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/ModifyAttributeSchemaNameMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/ModifyAttributeSchemaDescriptionMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/ModifyAttributeSchemaDefaultValueMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/ModifyAttributeSchemaDeprecationNoticeMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/ModifyAttributeSchemaTypeMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/SetAttributeSchemaFilterableMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/SetAttributeSchemaLocalizedMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/SetAttributeSchemaNullableMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/SetAttributeSchemaRepresentativeMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/SetAttributeSchemaSortableMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/SetAttributeSchemaUniqueMutation.java</SourceClass>**
</LanguageSpecific>
<LanguageSpecific to="csharp">
Within `ModifyEntitySchemaMutation` you can use mutation:

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/CreateAttributeSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/RemoveAttributeSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/ModifyAttributeSchemaNameMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/ModifyAttributeSchemaDescriptionMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/ModifyAttributeSchemaDefaultValueMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/ModifyAttributeSchemaDeprecationNoticeMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/ModifyAttributeSchemaTypeMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/SetAttributeSchemaFilterableMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/SetAttributeSchemaLocalizedMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/SetAttributeSchemaNullableMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/SetAttributeSchemaRepresentativeMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/SetAttributeSchemaSortableMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/Attributes/SetAttributeSchemaUniqueMutation.cs</SourceClass>**
</LanguageSpecific>

<LanguageSpecific to="java">
The attribute schema is described by:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/AttributeSchemaContract.java</SourceClass>
</LanguageSpecific>
<LanguageSpecific to="csharp">
The attribute schema is described by:
<SourceClass>EvitaDB.Client/Models/Schemas/IAttributeSchema.cs</SourceClass>
</LanguageSpecific>

</Note>

#### Default value

An attribute may have a default value defined. The value is used when a new entity is created and no value has been 
assigned to a particular attribute. There is no other situation where the default value matters.

#### Allowed decimal places

<LanguageSpecific to="java,evitaql,rest,graphql">
The allowed decimal places setting is an optimization that allows rich numeric types (such
as [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) for precise
number representation) to be converted to the
primitive [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) type, which is much more
compact and can be used for fast binary searches in array/bitset representation. The original rich format is still
present in an attribute container, but internally the database uses the primitive form when an attribute is part of is
part of filter or sort conditions.
</LanguageSpecific>
<LanguageSpecific to="csharp">
The allowed decimal places setting is an optimization that allows rich numeric types (such
as [decimal](https://learn.microsoft.com/en-us/dotnet/api/system.decimal) for precise
number representation) to be converted to the
[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32) type, which is much more
compact and can be used for fast binary searches in array/bitset representation. The original rich format is still
present in an attribute container, but internally the database uses the primitive form when an attribute is part of is
part of filter or sort conditions.
</LanguageSpecific>

If number cannot be converted to a compact form (for example, it has more digits in the fractional part than expected),
an exception is thrown and the entity update is refused.

### Sortable attribute compounds

Sortable attribute compound is a virtual attribute composed of the values of several other attributes, which can only be 
used for sorting. evitaDB requires a previously prepared sort index to be able to sort entities. This fact makes sorting 
much faster than ad-hoc sorting by attribute value. Also, the sorting mechanism of evitaDB is somewhat different from 
what you might be used to. If you sort entities by two attributes in an `orderBy` clause of the query, evitaDB sorts 
them first by the first attribute (if present) and then by the second (but only those where the first attribute is 
missing). If two entities have the same value of the first attribute, they are not sorted by the second attribute, but 
by the primary key (in ascending order). If we want to use fast "pre-sorted" indexes, there is no other way to do it,
because the secondary order would not be known until a query time.

This default sorting behavior by multiple attributes is not always desirable, so evitaDB allows you to define a sortable 
attribute compound, which is a virtual attribute composed of the values of several other attributes. evitaDB also allows 
you to specify the order of the "pre-sorting" behavior (ascending/descending) for each of these attributes, and also 
the behavior for NULL values (first/last) if the attribute is completely missing in the entity. The sortable attribute 
compound is then used in the `orderBy` clause of the query instead of specifying the multiple individual attributes to 
achieve the expected sorting behavior while maintaining the speed of the "pre-sorted" indexes.

Sortable attribute compound schema can be made *deprecated*, which will be propagated to generated web API documentation.

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to sortable attribute compound
</NoteTitle>

<LanguageSpecific to="java,evitaql,rest,graphql">

Within `ModifyEntitySchemaMutation` you can use mutation:

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/sortableAttributeCompound/CreateSortableAttributeCompoundSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/sortableAttributeCompound/RemoveSortableAttributeCompoundSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/sortableAttributeCompound/ModifySortableAttributeCompoundSchemaNameMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/sortableAttributeCompound/ModifySortableAttributeCompoundSchemaDescriptionMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/sortableAttributeCompound/ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.java</SourceClass>**
</LanguageSpecific>
<LanguageSpecific to="csharp">
Within `ModifyEntitySchemaMutation` you can use mutation:

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/SortableAttributeCompounds/CreateSortableAttributeCompoundSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/SortableAttributeCompounds/RemoveSortableAttributeCompoundSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/SortableAttributeCompounds/ModifySortableAttributeCompoundSchemaNameMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/SortableAttributeCompounds/ModifySortableAttributeCompoundSchemaDescriptionMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/SortableAttributeCompounds/ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.cs</SourceClass>**
</LanguageSpecific>

<LanguageSpecific to="java">

The sortable attribute compound schema is described by:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/SortableAttributeCompoundSchemaContract.java</SourceClass>

</LanguageSpecific>

<LanguageSpecific to="csharp">
The sortable attribute compound schema is described by:
<SourceClass>EvitaDB.Client/Models/Schemas/ISortableAttributeCompoundSchema.cs</SourceClass>
</LanguageSpecific>

</Note>

### Associated data

An entity type may have zero or more associated data. The system is designed for the situation when an entity has 
tens of associated data.

<LanguageSpecific to="java,evitaql,rest,graphql">
Associated data schema can be marked as `localized`, meaning that it only makes sense in a specific
[locale](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html).
</LanguageSpecific>
<LanguageSpecific to="csharp">
Associated data schema can be marked as `localized`, meaning that it only makes sense in a specific
[locale](https://learn.microsoft.com/en-us/dotnet/api/system.globalization.cultureinfo).
</LanguageSpecific>

Associated data schema can be made *deprecated*, which will be propagated to generated web API documentation.

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to associated data
</NoteTitle>

<LanguageSpecific to="java,evitaql,rest,graphql">

Within `ModifyEntitySchemaMutation` you can use mutation:

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/associatedData/CreateAssociatedDataSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/associatedData/RemoveAssociatedDataSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/associatedData/ModifyAssociatedDataSchemaNameMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/associatedData/ModifyAssociatedDataSchemaDescriptionMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/associatedData/ModifyAssociatedDataSchemaDeprecationNoticeMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/associatedData/ModifyAssociatedDataSchemaTypeMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/associatedData/SetAssociatedDataSchemaLocalizedMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/associatedData/SetAssociatedDataSchemaNullableMutation.java</SourceClass>**

</LanguageSpecific>
<LanguageSpecific to="csharp">

Within `ModifyEntitySchemaMutation` you can use mutation:

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/AssociatedData/CreateAssociatedDataSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/AssociatedData/RemoveAssociatedDataSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/AssociatedData/ModifyAssociatedDataSchemaNameMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/AssociatedData/ModifyAssociatedDataSchemaDescriptionMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/AssociatedData/ModifyAssociatedDataSchemaDeprecationNoticeMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/AssociatedData/ModifyAssociatedDataSchemaTypeMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/AssociatedData/SetAssociatedDataSchemaLocalizedMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/AssociatedData/SetAssociatedDataSchemaNullableMutation.cs</SourceClass>**

</LanguageSpecific>

<LanguageSpecific to="java">
The associated data schema is described by:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/AssociatedDataSchemaContract.java</SourceClass>
</LanguageSpecific>
<LanguageSpecific to="csharp">
The associated data schema is described by:
<SourceClass>EvitaDB.Client/Models/Schemas/IAssociatedDataSchema.cs</SourceClass>
</LanguageSpecific>

</Note>

### Reference

An entity type may have zero or more references. References can be managed or unmanaged. The managed references refer 
to entities within the same catalog and can be checked for consistency by evitaDB. The non-managed references refer 
to entities that are managed by external systems outside the scope of evitaDB. An entity can have a self-reference 
that refers to the same entity type. An entity type can have several references to the same entity type.

References can have zero or more attributes that apply only to a particular "link" between these two entity instances.
[Global attribute](#global-attribute-schema) cannot be used as a reference attribute. Otherwise, the same rules apply 
for reference attributes as for regular entity attributes.

When another entity references an entity and the reference is marked as *indexed*, the special
<SourceClass>io.evitadb.index.ReducedEntityIndex</SourceClass> is created for each referenced entity. This index will
hold reduced attribute and price indices of the referencing entity, allowing quick evaluation of
[`referencedEntityHaving`](../query/filtering/references.md) filter conditions and 
[`referenceProperty`](../query/ordering/reference.md) sorting.

If the reference is marked as *faceted*, the special 
<SourceClass>evita_engine/src/main/java/io/evitadb/index/facet/FacetReferenceIndex.java</SourceClass> is created for 
the entity type. This index contains optimized data structures for [facet summary](../query/requirements/facet.md) 
computation. All reference instances of a given type are then inserted into the *facet reference index* (there is no 
way to exclude a reference from indexing in the facet reference index). References can (but don't have to) be organized 
into facet groups that refer to a *managed* or *non-managed* entity type.

Each reference schema has a certain cardinality. The cardinality describes the expected number of relations of this 
type. In evitaDB we define only one-way relations from the perspective of the entity. We follow the ERD modeling 
[standards](https://www.gleek.io/blog/crows-foot-notation.html). Cardinality affects the design of the Web API schemas
(returning only single references or arrays) and also helps us to protect the consistency of the data so that it 
conforms to the creator's mental model.

<Note type="info">

<NoteTitle toggles="false">

##### List of mutations related to reference
</NoteTitle>

<LanguageSpecific to="java,evitaql,rest,graphql">

Within `ModifyEntitySchemaMutation` you can use mutation:

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/CreateReferenceSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/RemoveReferenceSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/ModifyReferenceSchemaNameMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/ModifyReferenceSchemaDescriptionMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/ModifyReferenceSchemaDeprecationNoticeMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/ModifyReferenceSchemaCardinalityMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/ModifyReferenceSchemaRelatedEntityMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/ModifyReferenceSchemaRelatedEntityGroupMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/SetReferenceSchemaIndexedMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/SetReferenceSchemaFacetedMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/ModifyReferenceAttributeSchemaMutation.java</SourceClass>**
</LanguageSpecific>
- 
<LanguageSpecific to="csharp">
  Within `ModifyEntitySchemaMutation` you can use mutation:

- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/References/CreateReferenceSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/References/RemoveReferenceSchemaMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/References/ModifyReferenceSchemaNameMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/References/ModifyReferenceSchemaDescriptionMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/References/ModifyReferenceSchemaDeprecationNoticeMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/References/ModifyReferenceSchemaCardinalityMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/References/ModifyReferenceSchemaRelatedEntityMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/References/ModifyReferenceSchemaRelatedEntityGroupMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/References/SetReferenceSchemaIndexedMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/References/SetReferenceSchemaFacetedMutation.cs</SourceClass>**
- **<SourceClass>EvitaDB.Client/Models/Schemas/Mutations/References/ModifyReferenceAttributeSchemaMutation.cs</SourceClass>**

</LanguageSpecific>

The `ModifyReferenceAttributeSchemaMutation` expect nested [attribute mutation](#attributes).

<LanguageSpecific to="java">
The reference schema is described by:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/ReferenceSchemaContract.java</SourceClass>
</LanguageSpecific>
<LanguageSpecific to="csharp">
The reference schema is described by:
<SourceClass>EvitaDB.Client/Models/Schemas/IReferenceSchema.cs</SourceClass>
</LanguageSpecific>

</Note>

## What's next?

The next obvious step is to learn [how to define the schema](api/schema-api.md) using the evitaDB API. But you might
be interested in [writing](api/write-data.md) or [querying](api/query-data.md) the data instead.