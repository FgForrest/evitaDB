---
title: Behavioral filtering containers
date: '7.11.2023'
perex: |
  Special behavioural filter constraint containers are used to define a filter constraint scope, which has a different 
  treatment in calculations, or to define a scope in which the entities are searched. 
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

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
process are described in the chapter [Archiving](../../use/schema.md#scopes) and the reasons why this feature 
exists are explained in the [dedicated blog post](https://evitadb.io/blog/15-soft-delete).

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
evitaDB will prefer the entity from the live scope over the entity from the archive scope. This means that if you query
a single entity by its unique attribute value (e.g. `URL`) and search for the entity in both scopes, you will always get
the entity from the live scope. This behavior is not applied, when only partial match is used (e.g. [attribute starts with](../filtering/string.md#attribute-starts-with), 
etc.).

</Note>

<Note type="warning">

<NoteTitle toggles="true">

##### Specific behaviour when indexed and non-indexed data in different scopes are queried

</NoteTitle>

Another important aspect of the `scope` filter is the way it handles indexed and non-indexed data. This change in 
behaviour stems from practical reasons and situations encountered in the field. Imagine you have a schema with 
an attribute `code` that is indexed in the live scope and not indexed in the archived scope. If you query the entities 
by the `code` attribute in both scopes in the following way

```evitaql
query(
    collection("entity"),
    filterBy(
        entityPrimaryKeyInSet(1, 2, 3),
        attributeIs("code", NOT_NULL),
        scope(LIVE)
    )
)
```

You'd expect to get the entities with primary keys 1, 2 and 3 from the live scope. However, if you archive some of these 
entities, say 1 and 2, and repeat the same query using the `scope(LIVE, ARCHIVED)` filter, you'd only get the entity 
with primary key 3. This is because the `code` attribute is not indexed in the archived scope and the query would be 
translated into a conjunction of `1`, `2`, `3` and non-null keys from the live scope - which is only the entity with 
primary key `3` - and this would result in a result set containing only the entity with primary key `3`.

This is not what developers expect when working with archived entities - they expect the query to return all 
the entities that match all the constraints that are applicable (indexed) for a particular scope, and to ignore 
the constraints that cannot be computed for that scope. So, in practice, they expect the query to be translated:

```
OR(
    AND(                 // SCOPE(ARCHIVED)
        [1, 2],          // SUPER SET OF ALL ARCHIVED ENTITIES
        [1, 2, 3]        // CONSTANT: ENTITY_PRIMARY_KEY_IN_SET(1, 2, 3)
    ),            
    AND(                  // SCOPE(LIVE)
        [3],              // SUPER SET OF ALL LIVE ENTITIES
        [1, 2, 3],        // CONSTANT: ENTITY_PRIMARY_KEY_IN_SET(1, 2, 3)
        [3]               // NOT_NULL("code"),
    )
)
```

This means that when both scopes are queried, the engine constructs two complement queries, each containing only 
the constraints that apply to that scope, and ignoring other constraints. If there is no constraint left for 
a particular scope, the whole scope is omitted so that the result isn't polluted with all the entities from the scope 
(super set).

In the case of the same query used with only the `scope(ARCHIVED)`, you'd get the exception informing you that the used
attribute `code` is not indexed in the archived scope, and you'd have to reformulate the query to target only indexed 
data in that scope.

This hybrid behaviour makes it much easier for applications to use, because the application doesn't have to construct 
different or more complex queries (to target only the indexed data) when both live and archived data are being queried,
and the engine does the heavy lifting for them. On the other hand, if only a single area is targeted, the engine is
strict and checks that all the constraints apply to that area (queries are expected to be tailored for that area).

</Note>

There are a few archived entities in our demo dataset. Our schema is configured to index only the `URL` and `code`
attributes in the archived scope, so we can search for archived entities using only these attributes and, of course, 
the primary key.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Accessing archived entities example](/documentation/user/en/query/filtering/examples/fetching/archived-entities-listing.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of querying archived entities
</NoteTitle>

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of querying archived entities](/documentation/user/en/query/filtering/examples/fetching/archived-entities-listing.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of querying archived entities](/documentation/user/en/query/filtering/examples/fetching/archived-entities-listing.rest.json.md)</MDInclude>

</LS>

</Note>

When we need to look up by the `URL` attribute, which is usually unique, there's an important difference, and that is 
that the `URL` is only unique within its scope. This means that the same URL can be used for different entities in 
different scopes. This is the case for some of our entities in our demo data set. The conflict for the unique key 
between different entities is resolved by evitaDB by favouring the live entity over the archived one. See the following
example:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Resolving conflicting entities example](/documentation/user/en/query/filtering/examples/fetching/archived-entities-conflict.evitaql)

</SourceCodeTabs>

Producing the following result:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of querying conflicting entities](/documentation/user/en/query/filtering/examples/fetching/archived-entities-conflict.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of querying conflicting entities](/documentation/user/en/query/filtering/examples/fetching/archived-entities-conflict.rest.json.md)</MDInclude>

</LS>

We can still access the archived entity via its `URL` property if we are only looking in the archived scope:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Accessing archived entity by URL](/documentation/user/en/query/filtering/examples/fetching/archived-entity-conflict-access.evitaql)

</SourceCodeTabs>

Producing the following result:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of querying archived entity by URL](/documentation/user/en/query/filtering/examples/fetching/archived-entity-conflict-access.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of querying archived entity by URL](/documentation/user/en/query/filtering/examples/fetching/archived-entity-conflict-access.rest.json.md)</MDInclude>

</LS>

## User filter

```evitaql-syntax
userFilter(
    filterConstraint:any+
)
```

<dl>
    <dt>filterConstraint:any+</dt>
    <dd>
        one or more mandatory filter constraints that will produce logical conjunction
    </dd>
</dl>


The <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/UserFilter.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/UserFilter.cs</SourceClass></LS>
works identically to the [`and`](logical.md#and) constraint, but it distinguishes the filter scope, which is controlled by the user
through some kind of user interface, from the rest of the query, which contains the mandatory constraints on the result
set. The user-defined scope can be modified during certain calculations (such as the [facet](../filtering/facet.md)
or [histogram](../filtering/histogram.md) calculation), while the mandatory part outside of `userFilter` cannot.

Let's look at the example where the [`facetHaving`](references.md#facet-having) constraint is used inside
the `userFilter` container:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[User filter container example](/documentation/user/en/query/filtering/examples/behavioral/user-filter.evitaql)

</SourceCodeTabs>

And compare it to the situation when we remove the `userFilter` container:

| Facet summary with `facetHaving` in `userFilter`  | Facet summary without `userFilter` scope       |
|---------------------------------------------------|------------------------------------------------|
| ![Before](assets/user-filter-before.png "Before") | ![After](assets/user-filter-after.png "After") |

As you can see in the second image, the facet summary is greatly reduced to a single facet option that is selected by
the user. Because the facet is considered a "mandatory" constraint in this case, it behaves the same as
the [`referenceHaving`](references.md#reference-having) constraint, which is combined with other constraints via logical
disjunction. Since there is no other entity that would refer to both the *amazon* brand and another brand (of course,
a product can only have a single brand), the other possible options are automatically removed from the facet summary
because they would produce an empty result set.
