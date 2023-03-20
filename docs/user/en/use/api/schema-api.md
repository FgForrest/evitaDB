---
title: Schema API
perex: |
    Currently, you can define the schema using the Java, REST, and GraphQL APIs. All three approaches are covered in 
    this chapter.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

<LanguageSpecific to="java">

## Imperative schema definition

A schema can be programmatically defined this way:

<SourceCodeTabs>
[Imperative schema definition via Java API](docs/user/en/use/api/example/imperative-schema-definition.java)
</SourceCodeTabs>

## Declarative schema definition

evitaDB offers an alternative way to define the entity type schema. You can define a model class annotated with evitaDB
annotations that describe the entity structure you want to work with in your project. Then just ask
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> to define an entity schema
for you:

<SourceCodeTabs>
[Declarative schema definition via Java API](docs/user/en/use/api/example/declarative-schema-definition.java)
</SourceCodeTabs>

The model template can be:

- [an interface](https://www.baeldung.com/java-interfaces)
- [a class](https://www.baeldung.com/java-pojo-class)
- [a record](https://www.baeldung.com/java-record-keyword)

that is annotated with following annotations:

<dl>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Entity.java</SourceClass></dt>
    <dd>
        Annotation can be placed only on a java type (interface / class / record) and marks the entity type.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PrimaryKey.java</SourceClass></dt>
    <dd>
        Annotation can be placed on [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) 
        field / getter method / record component and marks an entity [primary key](#primary-key-generation).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Attribute.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component and marks an entity [attribute](#attribute).
        Default values in case of interfaces can be provided using default method implementation (see the example 
        below).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AssociatedData.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component and marks an entity 
        [associated data](#associated-data).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Parent.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component and marks an entity as 
        [hierarchical entity](#hierarchy-placement). It should point to another model class (interface / class / record) 
        that contains properties for `@ParentEntity' and @OrderAmongSiblings' annotations.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ParentEntity.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component and marks a reference to another entity 
        that represents the hierarchical parent for this entity. The model class should be the same as the entity class 
        (see `@Entity` annotation).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/OrderAmongSiblings.java</SourceClass></dt>
    <dd>
        Annotation can be placed on [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) 
        field / getter method / record component that provides information about the order of the entity about
        siblings in the hierarchical placement.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Price.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component of collection / array of type
        <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>
        that provides access to all prices of the entity. Using this annotation in the entity model class enables 
        [prices](#prices) in the entity schema.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/SellingPrice.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component of type 
        <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass> 
        that provides access to price for sale of the entity. Using this annotation in the entity model class enables 
        [prices](#prices) in the entity schema.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Reference.java</SourceClass></dt>
    <dd>
        Annotation can be placed on field / getter method / record component and marks an entity as a 
        [reference](#reference) to another entity. It can point to another model class (interface/class/record) 
        that contains properties for `@ReferencedEntity' and @ReferencedEntityGroup' annotations and relation
        attributes.
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

For a better idea, let's demonstrate a sample of the interface design of the product entity.

<SourceCodeTabs>
[Example of the model interface](docs/user/en/use/api/example/declarative-model-example.java)
</SourceCodeTabs>

<Note type="info">
This approach is planned to be extended in the future. We plan to provide automatic implementation for the interfaces
or class you use for declarative schema definition. This feature request is recorded in 
[issue #43](https://github.com/FgForrest/evitaDB/issues/43).
</Note>

</LanguageSpecific>

<LanguageSpecific to="graphql">
**Work in progress**

The procedure and documentation for schema definition from GraphQL will be added.
</LanguageSpecific>

<LanguageSpecific to="rest">
**Work in progress**

The procedure and documentation for schema definition from REST will be added.
</LanguageSpecific>

<LanguageSpecific to="csharp">
**Work in progress**

The procedure and documentation for schema definition from C# will be added.
</LanguageSpecific>

<LanguageSpecific to="evitaql">
Unfortunately, it is currently not possible to define a schema using EvitaQL. This extension is also not planned to be
implemented in the near future, because we believe that sufficient options (Java, GraphQL, REST API) are sufficient 
for schema definition.
</LanguageSpecific>