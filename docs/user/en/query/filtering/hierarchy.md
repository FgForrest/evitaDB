---
title: Hierarchy filtering
date: '5.5.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

Hierarchy filtering can be applied only to entities [marked as hierarchical](../../use/data-model.md#hierarchy-placement) 
or to entities that [reference](../../use/data-model.md#references) these hierarchical entities. Hierarchy filtering 
allows filtering all direct or transitive children of a given hierarchy node, or entities that are directly or 
transitively related to the requested hierarchy node or its children. Filtering allows to exclude (hide) several parts 
of the tree from evaluation, which can be useful in situation when part of the store should be (temporarily) hidden 
from (some of) clients.

In addition to filtering, there are query [requirement extensions](../requirements/hierarchy.md) that allow you to 
compute data to help render (dynamic or static) menus that describe the hierarchy context you request in the query.

**The typical use-cases related to hierarchy constraints:**

- [list products in category](../../solve/filtering-products-in-category.md)
- [render category menus](../../solve/render-category-menu.md)
- [list categories for products of a specific brand](../../solve/render-referenced-brand.md)

## Hierarchy within

The constraint <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyWithin.java</SourceClass> 
allows you to restrict the search to only those entities that are part of the hierarchy tree starting with the root 
node identified by the first argument of this constraint. In e-commerce systems the typical representative of 
a hierarchical entity is a *category*, which will be used in all of our examples. The examples in this chapter will
focus on the category *Accessories* in our [demo dataset](https://demo.evitadb.io) with following layout:

![Accessories category listing](assets/accessories-category-listing.png "Accessories category listing")

**Syntax:**

```evitaql
hierarchyWithin(
    filterConstraint,
    (directRelation|excluding|excludingRoot)*
)
```

<dl>
    <dt>filterConstraint</dt>
    <dd>
        a single filter constraint that identifies **one or more** hierarchy nodes that act as hierarchy roots; 
        multiple constraints must be enclosed in [AND](../logical.md#and) / [OR](../logical.md#or) containers
    </dd>
    <dt>(directRelation|excluding|excludingRoot)*</dt>
    <dd>
        optional constraints allow you to narrow the scope of the hierarchy; 
        none or all of the constraints may be present:
        <ul>
            <li>[directRelation](#direct-relation)</li>
            <li>[excluding](#excluding)</li>
            <li>[excludingRoot](#excluding-root)</li>
        </ul>
    </dd>
</dl>

### Self

The most straightforward usage is filtering the hierarchical entities themselves. 

To list all nested categories of *Accessories* category issue this query:

<SourceCodeTabs>
[Transitive category listing](docs/user/en/query/filtering/examples/hierarchy-within-self-simple.evitaql)
</SourceCodeTabs>

You should receive listing of these categories:

<MDInclude>[Single root hierarchy example](docs/user/en/query/filtering/examples/hierarchy-within-self-simple.md)</MDInclude>

The first argument specifies the filter targets the attributes of the `Category` entity. In this example we used
[attributeEquals](comparable.md#attribute-equals) for unique attribute `code`, but you can select the category
by localized `url` attribute (but then you need to provide also [entityLocaleEquals](locale.md#entity-locale-equals)
constraint for determining the proper language), or using [entityPrimaryKeyInSet](constant.md#entity-primary-key-in-set)
and passing category primary key. 

<Note type="info">

<NoteTitle toggles="true">

##### Can the parent node filter constraint match multiple ones? 
</NoteTitle>

Yes, it can. Although, it's apparently one of the edge cases, it's possible. This query:

<SourceCodeTabs>
[Multiple category listing](docs/user/en/query/filtering/examples/hierarchy-within-self-multi.evitaql)
</SourceCodeTabs>

... will return all subcategories of the *Wireless headphones* and *Wired headphones* and their subcategories:

<MDInclude>[Multi-root hierarchy example](docs/user/en/query/filtering/examples/hierarchy-within-self-multi.md)</MDInclude>

![Accessories category listing](assets/accessories-category-listing-multi.png "Accessories category listing")

</Note>

### Referenced entity

The `hierarchyWithin` constraint can also be used for entities that directly reference a hierarchical entity type.
The most common use case from the e-commerce world is a product that is assigned to one or more categories. To list all
products in the *Accessories* category of our [demo dataset](https://demo.evitadb.io), we issue the following query:

<SourceCodeTabs>
[Product listing from *Accessories* category](docs/user/en/query/filtering/examples/hierarchy-within-reference-simple.evitaql)
</SourceCodeTabs>

The query returns the first page of a total of 26 pages of items:

<MDInclude>[Product listing from *Accessories* category](docs/user/en/query/filtering/examples/hierarchy-within-reference-simple.md)</MDInclude>

The category filter constraint specifies a condition that targets the referenced entity (i.e., category attributes,
category references). Currently, it's not possible to specify a filter constraint that takes into account the product
reference that leads to its category. An [issue #105](https://github.com/FgForrest/evitaDB/issues/105) is planned to
address this shortcoming.

## Hierarchy within root

The constraint <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyWithinRoot.java</SourceClass>
allows you to restrict the search to only those entities that are part of the entire hierarchy tree. In e-commerce
systems the typical representative of a hierarchical entity is a *category*, which will be used in all of our examples.

![Root categories listing](assets/category-listing.png "Root categories listing")

**Syntax:**

```evitaql
hierarchyWithin(
    filterConstraint,
    (directRelation|excluding|excludingRoot)*
)
```

<dl>
    <dt>filterConstraint</dt>
    <dd>
        a single filter constraint that identifies **one or more** hierarchy nodes that act as hierarchy roots; 
        multiple constraints must be enclosed in [AND](../logical.md#and) / [OR](../logical.md#or) containers
    </dd>
    <dt>(directRelation|excluding|excludingRoot)*</dt>
    <dd>
        optional constraints allow you to narrow the scope of the hierarchy; 
        none or all of the constraints may be present:
        <ul>
            <li>[directRelation](#direct-relation)</li>
            <li>[excluding](#excluding)</li>
            <li>[excludingRoot](#excluding-root)</li>
        </ul>
    </dd>
</dl>

### Self

### Reference

## Excluding
## Excluding root
## Direct relation