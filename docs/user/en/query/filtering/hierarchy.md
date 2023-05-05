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

### Self

**Syntax:**

```evitaql
hierarchyWithin(
    filterConstraint:any,
    filterConstraint:(directRelation|excluding|excludingRoot)*
)
```

<dl>
    <dt>filterConstraint:any</dt>
    <dd>
        a single filter constraint that identifies **one or more** hierarchy nodes that act as hierarchy roots; 
        multiple constraints must be enclosed in [AND](../logical.md#and) / [OR](../logical.md#or) containers
    </dd>
    <dt>filterConstraint:(directRelation|excluding|excludingRoot)*</dt>
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

**Syntax:**

```evitaql
hierarchyWithin(
    argument:string,
    filterConstraint:any,
    filterConstraint:(directRelation|excluding|excludingRoot)*
)
```

<dl>
    <dt>argument:string</dt>
    <dd>
        a name of the queried entity [reference schema](../../use/schema.md#reference) that represents the relationship 
        to the hierarchical entity type, your entity may target different hierarchical entities in different reference
        types, or it may target the same hierarchical entity through multiple semantically different references, and 
        that is why the reference name is used instead of the target entity type.
    </dd>
    <dt>filterConstraint:any</dt>
    <dd>
        a single filter constraint that identifies **one or more** hierarchy nodes that act as hierarchy roots; 
        multiple constraints must be enclosed in [AND](../logical.md#and) / [OR](../logical.md#or) containers
    </dd>
    <dt>filterConstraint:(directRelation|excluding|excludingRoot)*</dt>
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

The `hierarchyWithin` constraint can also be used for entities that directly reference a hierarchical entity type.
The most common use case from the e-commerce world is a product that is assigned to one or more categories. To list all
products in the *Accessories* category of our [demo dataset](https://demo.evitadb.io), we issue the following query:

<SourceCodeTabs>
[Product listing from *Accessories* category](docs/user/en/query/filtering/examples/hierarchy-within-reference-simple.evitaql)
</SourceCodeTabs>

Products assigned to two or more subcategories of *Accessories* category will only appear once in the response (contrary 
to what you might expect if you have experience with SQL).

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

The single difference to [hierarchyWithin constraint](#hierarchy-within) is that it doesn't accept a root node 
specification. Because evitaDB accepts multiple root nodes in your entity hierarchy, it may be helpful to imagine
there is an invisible "virtual" top root above all the top nodes (whose `parent` property remains `NULL`) you have in 
your entity hierarchy and this virtual top root is targeted by this constraint.

![Root categories listing](assets/category-listing.png "Root categories listing")

### Self

**Syntax:**

```evitaql
hierarchyWithinRoot(
    filterConstraint:(directRelation|excluding)*
)
```

<dl>
    <dt>filterConstraint:(directRelation|excluding)*</dt>
    <dd>
        optional constraints allow you to narrow the scope of the hierarchy; 
        none or all of the constraints may be present:
        <ul>
            <li>[directRelation](#direct-relation)</li>
            <li>[excluding](#excluding)</li>
        </ul>
    </dd>
</dl>

The `hierarchyWithinRoot`, which targets the `Category` collection itself, returns all categories except those that 
would point to non-existent parent nodes, such hierarchy nodes are called [orphans](../../use/schema.md#orphan-hierarchy-nodes)
and do not satisfy any hierarchy query.

<SourceCodeTabs>
[Category listing](docs/user/en/query/filtering/examples/hierarchy-within-root-simple.evitaql)
</SourceCodeTabs>

The query returns the first page of a total of 2 pages of items:

<MDInclude>[Category listing](docs/user/en/query/filtering/examples/hierarchy-within-root-simple.md)</MDInclude>

### Referenced entity

**Syntax:**

```evitaql
hierarchyWithinRoot(
    argument:string,   
    filterConstraint:(excluding)*
)
```

<dl>
    <dt>argument:string</dt>
    <dd>
        a name of the queried entity [reference schema](../../use/schema.md#reference) that represents the relationship 
        to the hierarchical entity type, your entity may target different hierarchical entities in different reference
        types, or it may target the same hierarchical entity through multiple semantically different references, and 
        that is why the reference name is used instead of the target entity type.
    </dd>
    <dt>filterConstraint:(excluding)*</dt>
    <dd>
        optional constraints allow you to narrow the scope of the hierarchy; 
        none or all of the constraints may be present:
        <ul>
            <li>[directRelation](#direct-relation)</li>
            <li>[excluding](#excluding)</li> 
        </ul>
    </dd>
</dl>

The `hierarchyWithinRoot` constraint can also be used for entities that directly reference a hierarchical entity type.
The most common use case from the e-commerce world is a product that is assigned to one or more categories. To list all
products assigned to any category of our [demo dataset](https://demo.evitadb.io), we issue the following query:

<SourceCodeTabs>
[Product listing assigned to a category](docs/user/en/query/filtering/examples/hierarchy-within-root-reference-simple.evitaql)
</SourceCodeTabs>

Products assigned to only one [orphan category](../../use/schema.md#orphan-hierarchy-nodes) will be missing from 
the result. Products assigned to two or more categories will only appear once in the response (contrary to what you 
might expect if you have experience with SQL).

The query returns the first page of a total of 212 pages of items:

<MDInclude>[Product listing assigned to a category](docs/user/en/query/filtering/examples/hierarchy-within-root-reference-simple.md)</MDInclude>

## Direct relation

The constraint <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyDirectRelation.java</SourceClass> 
is a constraint that can only be used within `hierarchyWithin` or `hierarchyWithinRoot` parent constraints. It simply 
makes no sense anywhere else because it changes the default behavior of those constraints. Hierarchy constraints return 
all hierarchy children of the parent node or entities that are transitively or directly related to them and the parent 
node itself. If the `directRelation` is used as a sub-constraint, this behavior changes and only direct descendants or 
directly referencing entities are matched.

**Syntax:**

```evitaql
directRelation()
```

### Self

If the hierarchy constraint targets the hierarchy entity, the `directRelation` will cause only the children of a direct
parent node to be returned. In the case of the `hierarchyWithinRoot` constraint, the parent is an invisible "virtual"
top root - so only the top-level categories are returned.

<SourceCodeTabs>
[Top categories listing](docs/user/en/query/filtering/examples/hierarchy-within-self-top-categories.evitaql)
</SourceCodeTabs>

<MDInclude>[Top categories listing](docs/user/en/query/filtering/examples/hierarchy-within-self-top-categories.md)</MDInclude>

In the case of the `hierarchyWithin` the result will contain direct children of the filtered category (or categories).

<SourceCodeTabs>
[Accessories children categories listing](docs/user/en/query/filtering/examples/hierarchy-within-self-direct-categories.evitaql)
</SourceCodeTabs>

<MDInclude>[Accessories children categories listing](docs/user/en/query/filtering/examples/hierarchy-within-self-direct-categories.md)</MDInclude>

### Referenced entity

If the hierarchy constraint targets a non-hierarchical entity that references the hierarchical one (typical example is 
a product assigned to a category), it can only be used in the `hierarchyWithin` parent constraint.

In the case of `hierarchyWithinRoot`, the `directRelation` constraint makes no sense because no entity can be assigned 
to a "virtual" top parent root.

Se we can list only a products directly related to a certain category - when we try to list products that have
*Accessories* category assigned:

<SourceCodeTabs>
[Products directly assigned to Accessories category](docs/user/en/query/filtering/examples/hierarchy-within-reference-direct-categories.evitaql)
</SourceCodeTabs>

... we get an empty result. There are no products directly assigned to the *Accessories* category, they all refer to 
some of its subcategories. Let's try the *Smartwatches* subcategory:

<SourceCodeTabs>
[Products directly assigned to Smartwatches category](docs/user/en/query/filtering/examples/hierarchy-within-reference-direct-categories-smart.evitaql)
</SourceCodeTabs>

... and we get the list of all products related directly to a *Smartwatches* category:

<MDInclude>[Product directly assigned to Smartwatches category](docs/user/en/query/filtering/examples/hierarchy-within-reference-direct-categories-smart.md)</MDInclude>

## Excluding root

The constraint <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyExcludingRoot.java</SourceClass>
is a constraint that can only be used within `hierarchyWithin` or `hierarchyWithinRoot` parent constraints. It simply
makes no sense anywhere else because it changes the default behavior of those constraints. Hierarchy constraints return
all hierarchy children of the parent node or entities that are transitively or directly related to them and the parent 
node itself. When the `excludingRoot` is used as a sub-constraint, this behavior changes and the parent node itself or the
entities directly related to that parent node are be excluded from the result.

**Syntax:**

```evitaql
excludingRoot()
```

### Self

If the hierarchy constraint targets the hierarchy entity, the `excludingRoot` will the requested parent node will be
omitted from the result. In the case of the `hierarchyWithinRoot` constraint, the parent is an invisible "virtual"
top root, and this constraint makes no sense.

<SourceCodeTabs>
[Category listing excluding parent](docs/user/en/query/filtering/examples/hierarchy-within-self-excluding-root.evitaql)
</SourceCodeTabs>

As we can see the requested parent category *Accessories* is excluded from the result:

<MDInclude>[Category listing excluding parent](docs/user/en/query/filtering/examples/hierarchy-within-self-excluding-root.md)</MDInclude>

### Referenced entity

If the hierarchy constraint targets a non-hierarchical entity that references the hierarchical one (typical example is
a product assigned to a category), it can only be used in the `hierarchyWithin` parent constraint.

In the case of `hierarchyWithinRoot`, the `excludingRoot` constraint makes no sense because no entity can be assigned
to a "virtual" top parent root.

Because we learned that *Accessories* category has no directly assigned products, the `exludingRoot` constraint presence
would not affect the query result. Therefore, we choose *Keyboard* category for our example. When we list all products
in *Keyboard* category using `hierarchyWithin` constraint, we obtain **20 items**. When the `excludingRoot` constraint
is used:

<SourceCodeTabs>
[Products in subcategories of Keyboard category](docs/user/en/query/filtering/examples/hierarchy-within-reference-excluding-root.evitaql)
</SourceCodeTabs>

... we get only **4 items**, which means that 16 were assigned directly to *Keyboards* category and only 4 of them were
assigned to *Exotic keyboards*:

<MDInclude>[Products in subcategories of Keyboard category](docs/user/en/query/filtering/examples/hierarchy-within-reference-excluding-root.md)</MDInclude>

## Excluding
