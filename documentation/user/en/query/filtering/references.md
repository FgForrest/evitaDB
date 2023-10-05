---
title: Reference filtering
date: '4.10.2023'
perex: |
  Reference filtering is used to filter entities based on their references to other entities in the catalog or
  attributes specified in those relations.
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

## Reference having

```evitaql-syntax
referenceHaving(
    argument:string!,
    filterConstraint:any+
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        the name of the [entity reference](../../use/schema.md#reference) that will be subjected to the filtering 
        constraints in the second and subsequent arguments
    </dd>
    <dt>filterConstraint:any+</dt>
    <dd>
        one or more filter constraints that must be satisfied by one of the entity references with name specified in 
        the first argument
    </dd>
</dl>

The `referenceHaving` constraint eliminates entities which has no reference of particular name satisfying set of
filtering constraints. You can examine either the attributes specified on the relation itself or wrap the filtering
constraint in [`entityHaving`](#entity-having) constraint to examine the attributes of the referenced entity.
The constraint is similar to SQL [`EXISTS`](https://www.w3schools.com/sql/sql_exists.asp) operator.

To demonstrate how the `referenceHaving` constraint works, let's query for products that have at least one alternative
product specified. The alternative products are stored in the `relatedProducts` reference on the `Product` entity and
have the `category` attribute set to `alternativeProduct`. There can be different types of related products other than
alternative products, for example spare parts and so on - that's why we need to specify the `category` attribute in
the filtering constraint.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Product with at least one `relatedProducts` reference of `alternativeProduct` category](/documentation/user/en/query/filtering/examples/references/reference-having.evitaql)
</SourceCodeTabs>

Returns the following result:

<Note type="info">

<NoteTitle toggles="true">

##### Products with at least one `relatedProducts` reference of `alternativeProduct` category
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[Products with at least one `relatedProducts` reference of `alternativeProduct` category](/documentation/user/en/query/filtering/examples/references/reference-having.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Products with at least one `relatedProducts` reference of `alternativeProduct` category](/documentation/user/en/query/filtering/examples/references/reference-having.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Products with at least one `relatedProducts` reference of `alternativeProduct` category](/documentation/user/en/query/filtering/examples/references/reference-having.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

If we wanted to query for products that have at least one related product reference of any `category` type, we could use
the following simplified query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Product with at least one `relatedProducts` reference of any category](/documentation/user/en/query/filtering/examples/references/reference-having-any.evitaql)
</SourceCodeTabs>

Which returns the following result:

<Note type="info">

<NoteTitle toggles="true">

##### Products with at least one `relatedProducts` reference of any category
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[Products with at least one `relatedProducts` reference of any category](/documentation/user/en/query/filtering/examples/references/reference-having-any.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Products with at least one `relatedProducts` reference of any category](/documentation/user/en/query/filtering/examples/references/reference-having-any.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Products with at least one `relatedProducts` reference of any category](/documentation/user/en/query/filtering/examples/references/reference-having-any.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

Another frequently used use-case is to query for entities that have at least one reference to another entity with
certain primary key. For example, we want to query for products that are related to `brand` with primary key `66465`.
This can be achieved by following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Products referencing `brand` of particular primary key](/documentation/user/en/query/filtering/examples/references/reference-having-exact-id.evitaql)
</SourceCodeTabs>

Which returns the following result:

<Note type="info">

<NoteTitle toggles="true">

##### Products with at least one `relatedProducts` reference of any category
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[Products referencing `brand` of particular primary key](/documentation/user/en/query/filtering/examples/references/reference-having-exact-id.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Products referencing `brand` of particular primary key](/documentation/user/en/query/filtering/examples/references/reference-having-exact-id.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Products referencing `brand` of particular primary key](/documentation/user/en/query/filtering/examples/references/reference-having-exact-id.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Entity having

```evitaql-syntax
entityHaving(   
    filterConstraint:any+
)
```

<dl>
    <dt>filterConstraint:any+</dt>
    <dd>
        one or more filter constraints that must be satisfied by the target referenced entity of any of the source 
        entity references identified by the parent `referenceHaving` constraint
    </dd>
</dl>

The `entityHaving` constraint is used to examine the attributes or other filterable properties of the referenced entity.
It can only be used within the [`referenceHaving`](#reference-having) constraint, which defines the name of the entity 
reference that identifies the target entity to be subjected to the filtering restrictions in the `entityHaving` 
constraint. The filtering constraints for the entity can use entire range of [filtering operators](../basics.md#filter-by).

Let's use our previous example to query for products that relate to `brand` with particular attribute `code`:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Products referencing `brand` of with code `apple`](/documentation/user/en/query/filtering/examples/references/entity-having.evitaql)

</SourceCodeTabs>

Which returns the following result:

<Note type="info">

<NoteTitle toggles="true">

##### Products with at least one `relatedProducts` reference of any category
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[Products referencing `brand` of with code `apple`](/documentation/user/en/query/filtering/examples/references/entity-having.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Products referencing `brand` of with code `apple`](/documentation/user/en/query/filtering/examples/references/entity-having.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Products referencing `brand` of with code `apple`](/documentation/user/en/query/filtering/examples/references/entity-having.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Facet having