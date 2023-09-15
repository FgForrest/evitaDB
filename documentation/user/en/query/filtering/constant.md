---
title: Constant filtering
perex: |
  If you need to retrieve entities by their entity primary keys, or verify that entities with particular primary keys 
  exist in the database, the constant filter constraint is the place to go. Filtering entities by their primary keys is 
  the fastest way to access entities in evitaDB. 
date: '26.5.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

## Entity primary key in set

```evitaql-syntax
entityPrimaryKeyInSet(
    argument:int+
)
```

<dl>
    <dt>argument:int+</dt>
    <dd>
        a mandatory set of entity primary keys representing the entities to be returned
    </dd>
</dl>

The constraint limits the list of returned entities by exactly specifying their entity primary keys. 

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[List of products filtered by entity primary key](/documentation/user/en/query/filtering/examples/constant/entity-primary-key-in-set.evitaql)
</SourceCodeTabs>

The sample query returns products whose primary keys are declared in the `entityPrimaryKeyInSet` constraint. The order
of the primary keys in the constraint doesn't matter. The returned entities are always returned in ascending order of
their primary keys, unless the `orderBy` clause is used in the query.

<Note type="info">

If you want the entities to be returned in the exact order of the primary keys used in the argument 
of the `entityPrimaryKeyInSet` constraint, use the 
[`entityPrimaryKeyInFilter`](../ordering/constant.md#exact-entity-primary-key-order-used-in-filter)
ordering constraint.

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### List of products filtered by entity primary key
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[Entities filtered by the primary keys](/documentation/user/en/query/filtering/examples/constant/entity-primary-key-in-set.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Entities filtered by the primary keys](/documentation/user/en/query/filtering/examples/constant/entity-primary-key-in-set.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Entities filtered by the primary keys](/documentation/user/en/query/filtering/examples/constant/entity-primary-key-in-set.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>
