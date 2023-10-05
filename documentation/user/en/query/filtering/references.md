---
title: Reference filtering
date: '4.10.2023'
perex: |
  Reference filtering is used to filter entities based on their references to other entities in the catalog or
  attributes specified in those relations.
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---



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