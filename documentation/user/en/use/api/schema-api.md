---
title: Schema API
perex: |
    Currently, you can define the schema using the Java, C#, REST, and GraphQL APIs. All three approaches are covered in
    this chapter.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'java'
---

<LS to="j">

## Imperative schema definition

A schema can be programmatically defined this way:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Imperative schema definition via Java API](/documentation/user/en/use/api/example/imperative-schema-definition.java)
</SourceCodeTabs>

## Declarative schema definition

evitaDB offers an alternative way to define the entity type schema. You can define a model class annotated with evitaDB
annotations that describe the entity structure you want to work with in your project. Then just ask
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> to define an entity schema
for you:

<SourceCodeTabs setup="/documentation/user/en/use/api/example/declarative-model-example.java,/documentation/user/en/get-started/example/define-test-catalog.java" local>

[Declarative schema definition via Java API](/documentation/user/en/use/api/example/declarative-schema-definition.java)
</SourceCodeTabs>

The model template can be:

- [an interface](https://www.baeldung.com/java-interfaces)
- [a class](https://www.baeldung.com/java-pojo-class)
- [a record](https://www.baeldung.com/java-record-keyword)

### Schema controlling annotations

The model is expected to be annotated with following annotations:

<dl>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Entity.java</SourceClass></dt>
    <dd>
        Annotation can be placed only on a java type (interface / class / record) and marks the entity type.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PrimaryKey.java</SourceClass></dt>
    <dd>
        Annotation can be placed on [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
        field / getter method / record component and marks an entity [primary key](../../use/schema.md#primary-key-generation).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Attribute.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component and marks an entity [attribute](../../use/schema.md#attribute).
        Default values in case of interfaces can be provided using default method implementation (see the example
        below).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/SortableAttributeCompound.java</SourceClass></dt>
    <dd>
        Annotation can be placed on a class / record and marks an entity [sortable attribute compound](../../use/schema.md#sortable-attribute-compounds),
        which aggregated multiple attributes of the class into a sortable compound, that cannot be accessed only used in query
        for the sorting.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/SortableAttributeCompounds.java</SourceClass></dt>
    <dd>
        Annotation can be placed on a class / record as a container for multiple `@SortableAttributeCompound` annotations.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AssociatedData.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component and marks an entity
        [associated data](../../use/schema.md#associated-data).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ParentEntity.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component and marks a reference to another entity
        that represents the hierarchical parent for this entity. The model class should be the same as the entity class
        (see `@Entity` annotation).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Price.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component of collection / array of type
        <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>
        that provides access to all prices of the entity. Using this annotation in the entity model class enables
        [prices](../../use/schema.md#prices) in the entity schema.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PriceForSale.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component of type
        <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>
        that provides access to price for sale of the entity. Using this annotation in the entity model class enables
        [prices](../../use/schema.md#prices) in the entity schema.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Reference.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component and marks an entity as a
        [reference](../../use/schema.md#reference) to another entity. It can point to another model class (interface/class/record)
        that contains properties for `@ReferencedEntity` and `@ReferencedEntityGroup` annotations and relation
        attributes.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReflectedReference.java</SourceClass></dt>
    <dd>
        <p>Annotation can be placed on field / getter method / record component and marks an entity as a 
        [reflected reference](../../use/schema.md#reference) to another entity. It can point to another model class (interface/class/record)
        that contains properties for `@ReferencedEntity` and `@ReferencedEntityGroup` annotations and relation
        attributes.</p>
        <p>Original reference may not yet exists in the schema, but it must be defined before the transaction is committed
        or session is closed (in warm-up phase).</p>
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReferencedEntity.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component and marks a reference to another entity
        that represents the referenced entity for this entity. The model class should represent a entity class model
        (see `@Entity` annotation).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReferencedEntityGroup.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component and marks a reference to another entity
        that represents the referenced entity group for this entity. The model class should represent a entity class
        model (see `@Entity` annotation).
    </dd>
</dl>

Methods / fields / record components that are not annotated are ignored during schema definition. For a better idea,
let's demonstrate a sample of the interface design of the product entity.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java" local>

[Example of the model interface](/documentation/user/en/use/api/example/declarative-model-example.java)

</SourceCodeTabs>

<Note type="info">

You can also use the contract for the schema definition in the [query API](./query-data.md) as an expected result type
and evitaDB will automatically generate an appropriate proxy class that maps the generic underlying data structure
to the contract of your imagination. You can find more information on this topic in
the [Java Connector chapter](../connectors/java.md#custom-contracts).

</Note>

</LS>

<LS to="g">

Unlike the Java approach, the GraphQL API supports only an imperative schema definition. The schema is defined using
atomic mutations where each mutation adds, changes or removes a small part of the entire schema. To define an entire schema,
you typically need to pass a collection of multiple mutations.

<Note type="question">

<NoteTitle toggles="true">

##### Why do we use the mutation approach for schema definition?
</NoteTitle>

We know that this approach is not very user-friendly. However, the idea behind this approach is to provide a simple and versatile
way to programmatically define a schema (in fact, this is how evitaDB works internally,
so the collection of mutations is passed directly to the engine on the server). It is expected that the developer
using the GraphQL API will create a library with e.g. entity schema builders that will generate the collection of mutations for
the schema definition.

</Note>

You can define a new catalog schema or update an existing one using the
[catalog schema API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances)
at the `https://your-server:5555/gql/evita/schema` URL:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Imperative catalog schema definition via GraphQL API](/documentation/user/en/use/api/example/imperative-catalog-schema-definition.graphql)
</SourceCodeTabs>

or update the schema of a specific entity collection at the same URL using a GraphQL mutation of the selected collection like this:

<SourceCodeTabs setup="/documentation/user/en/use/api/example/imperative-schema-definition.java" langSpecificTabOnly local>

[Imperative collection schema definition via GraphQL API](/documentation/user/en/use/api/example/imperative-collection-schema-definition.graphql)
</SourceCodeTabs>

</LS>

<LS to="r">

Unlike the Java approach, the REST API supports only an imperative schema definition. The schema is defined using
atomic mutations where each mutation adds, changes or removes a small part of the entire schema. To define an entire schema,
you typically need to pass a collection of multiple mutations.

<Note type="question">

<NoteTitle toggles="true">

##### Why do we use the mutation approach for schema definition?
</NoteTitle>

We know that this approach is not very user-friendly. However, the idea behind this approach is to provide a simple and versatile
way to programmatically define a schema with transactions in mind (in fact, this is how evitaDB works internally,
so the collection of mutations is passed directly to the engine on the server). It is expected that the developer
using the REST API will create a library with e.g. entity schema builders that will generate the collection of mutations for
the schema definition.

</Note>

You can define a new catalog schema or update an existing one using the
[catalog API](/documentation/user/en/use/connectors/rest.md#rest-api-instances)
at the `https://your-server:5555/rest/evita/schema` URL:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Imperative catalog schema definition via REST API](/documentation/user/en/use/api/example/imperative-catalog-schema-definition.rest)
</SourceCodeTabs>

or update the schema of a specific entity collection at e.g. an `https://your-server:5555/rest/evita/product/schema` URL
for the collection `Product` using a REST mutation of the selected collection like this:

<SourceCodeTabs setup="/documentation/user/en/use/api/example/imperative-schema-definition.java" langSpecificTabOnly local>

[Imperative collection schema definition via REST API](/documentation/user/en/use/api/example/imperative-collection-schema-definition.rest)
</SourceCodeTabs>

</LS>

<LS to="c">

Unlike the Java approach, the C# client supports only an imperative schema definition.
The schema is defined using builder pattern, that is provided by <SourceClass>EvitaDB.Client/Models/Schemas/IEntitySchemaBuilder.cs</SourceClass> interface.
Behind the scenes, instance of such builder is converted to the collection of mutations, that are sent to the server.

## Imperative schema definition

A schema can be programmatically defined this way:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Imperative schema definition via AP evitaDB API](/documentation/user/en/use/api/example/imperative-schema-definition.cs)
</SourceCodeTabs>

</LS>

<LS to="e">
Unfortunately, it is currently not possible to define a schema using EvitaQL. This extension is also not planned to be
implemented in the near future, because we believe that sufficient options (Java, GraphQL, REST API) are available
for schema definition.
</LS>
