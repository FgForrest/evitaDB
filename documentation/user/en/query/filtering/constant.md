---
title: Constant filtering
perex: |
  If you need to retrieve entities by their entity primary keys, or verify that entities with particular primary keys
  exist in the database, the constant filter constraint is the place to go. Filtering entities by their primary keys is
  the fastest way to access entities in evitaDB.
date: '26.5.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
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

<LS to="e,j,c">

<MDInclude>[Entities filtered by the primary keys](/documentation/user/en/query/filtering/examples/constant/entity-primary-key-in-set.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Entities filtered by the primary keys](/documentation/user/en/query/filtering/examples/constant/entity-primary-key-in-set.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Entities filtered by the primary keys](/documentation/user/en/query/filtering/examples/constant/entity-primary-key-in-set.rest.json.md)</MDInclude>

</LS>

</Note>

## Scope

```evitaql-syntax
scope(
    argument:enum(LIVE|ARCHIVED)+
)
```

<dl>
    <dt>argument:enum(LIVE|ARCHIVED)+</dt>
    <dd>
        mandatory one or more enum arguments representing the scope to be searched for the result
    </dd>
</dl>

The `scope` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filtering/Scope.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Filtering/Scope.cs</SourceClass></LS>) filtering allows you to specify
the scope in which the result is searched. Two scopes are available:

- `LIVE` - the default scope, which searches the live data for the result
- `ARCHIVED` - the scope that searches for the result in the archived data.

Scopes represent the means how evitaDB handles so called "soft deletes". The application can choose between a hard
delete and archiving the entity, which simply moves the entity to the archive scope. The details of the archiving
process are described in the chapter [scopes](../../use/schema.md#scopes) and the reasons why this feature
exists are explained in the [dedicated blog post](https://evitadb.io/blog/15-soft-delete).

By default, all queries behave as if the `scope(LIVE)` is present in the filter part, unless you explicitly specify
the scope constraint yourself. This means that no entity from the archive scope will be returned. If the entity has
a reference to an entity in the archive scope, the [`referenceHaving`](../filtering/references.md#reference-having)
won't be satisfied if only entities in the `LIVE` scope are queried. If you change the scope to `scope(ARCHIVE)`, you
will only get entities from the archive scope. You can also mix entities from both scopes by specifying
`scope(LIVE, ARCHIVE)`, and in such a case the [`referenceHaving`](../filtering/references.md#reference-having)
may also match entities from different scopes than the one being queried.

<Note type="warning">

<NoteTitle toggles="true">

##### Specific behavior related to unique keys

</NoteTitle>

Unique constraints are only enforced within the same scope. This means that two entities in different scopes can have
the same unique attribute value. When you move an entity from one scope to another, the unique constraints within
the target scope are checked and if the entity violates the unique constraint, the move is refused.

If you query entities in both scopes using [scope](../query/filtering/behavioral.md#scope) filter and use the filtering
constraint that exactly matches the unique attribute ([attribute equals](../filtering/comparable.md#attribute-equals),
[attribute in set](../filtering/comparable.md#attribute-in-set), [attribute is](../filtering/comparable.md#attribute-is)),
evitaDB will prefer the entity from the first scope specified in `scope` constraint over the entities in scopes defined
later in this `scope` constraint. This means that if you query a single entity by its unique attribute value (e.g. `URL`)
and search for the entity in both scopes, you will always get the entity from the first scope you declare in your query.
This behavior is not applied, when only partial match is used (e.g. [attribute starts with](../filtering/string.md#attribute-starts-with),
etc.).

</Note>

There are a few archived entities in our demo dataset. Our schema is configured to index only the `URL` and `code`
attributes in the archived scope, so we can search for archived entities using only these attributes and, of course,
the primary key.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Accessing archived entities example](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-listing.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of querying archived entities
</NoteTitle>

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of querying archived entities](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-listing.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of querying archived entities](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-listing.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of querying archived entities](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-listing.rest.json.md)</MDInclude>

</LS>

</Note>

When we need to look up by the `URL` attribute, which is usually unique, there's an important difference, and that is
that the `URL` is only unique within its scope. This means that the same URL can be used for different entities in
different scopes. This is the case for some of our entities in our demo data set. The conflict for the unique key
between different entities is resolved by evitaDB by favouring the live entity over the archived one.