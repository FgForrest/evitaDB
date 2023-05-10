---
title: Hierarchy extra results
perex: |
  Hierarchy requirements allows you to compute data structures from tree oriented data relevant for menu rendering.
  In e-commerce projects, the hierarchy structure is represented by a category tree and the items that refer to it are
  usually products or some kind of "inventory".
date: '5.5.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

There are many types of menus that can be found on e-commerce sites. Starting with mega menus of various types ...

![Mega-menu example](docs/user/en/query/requirements/assets/mega-menu.png "Mega-menu example")

... direct subcategory menus ...

![Direct subcategories menu](docs/user/en/query/requirements/assets/category-listing.png "Direct subcategories menu")

... over a highly dynamic menu that could be rolled out gradually using plus/minus signs without affecting the real item
listing on the right side of the screen (it's updated by category after selection) ...

![Highly dynamic tree example](docs/user/en/query/requirements/assets/dynamic-tree.png "Highly dynamic tree example")

... to a hybrid menu that partially opens to a currently selected category and displays only a direct sibling category 
on the parent axis:

![Hybrid menu example](docs/user/en/query/requirements/assets/hybrid-menu.png "Hybrid menu example")

There are a huge number of possible variations, and it is difficult to support all of them with a single constraint.
That's why there is an extensible mechanism by which you can request the computation of multiple different parts of the 
hierarchy tree, as you actually need it for your particular user interface use case.

There are two type of top hierarchy requirements:

<dl>
    <dt>[`hierarchyOfSelf`](#hierarchy-of-self)</dt>
    <dd>
        realized by <SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/HierarchyOfSelf.java</SourceClass>
        and is used to compute data structures from the data of the directly queried hierarchical entity
    </dd>
    <dt>[`hierarchyOfReference`](#hierarchy-of-reference)</dt>
    <dt>
        realized by <SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/HierarchyOfReference.java</SourceClass>
        and is used to compute data structures from the data of the entities referencing hierarchical entity
    </dt>
</dl>

These top hierarchy requirements must have at least one of the following hierarchy sub-constraints:

- [`fromRoot`](#from-root)
- [`fromNode`](#from-node)
- [`siblings`](#siblings)
- [`children`](#children)
- [`parents`](#parents)

There may be multiple sub-constraints, and each constraint may be duplicated (usually with different settings).
Each hierarchy sub-constraint defines [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
argument with a named value allowing to connect the request constraint with the computed result data structure
in <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/Hierarchy.java</SourceClass>
extra result.

## Hierarchy of self
## Hierarchy of reference

## From root
## From node
## Children
## Siblings
## Parents
## Stop at
## Distance
## Level
## Node
## Statistics