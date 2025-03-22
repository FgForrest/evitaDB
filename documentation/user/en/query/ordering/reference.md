---
title: Ordering references / by reference attribute
perex: |
  You can sort entities by attributes on references, and you can also sort fetched referenced entities by their
  attributes or by attributes of references that point to them. Although these are fundamentally different scenarios,
  both are described in this section.
date: '25.6.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
preferredLang: 'evitaql'
---

## Reference property

```evitaql-syntax
referenceProperty(
    argument:string!
    constraint:(traverseByEntityProperty|pickFirstByEntityProperty)?,
    constraint:orderingConstraint+
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        a mandatory name of the reference whose attribute is to be used for the ordering
    </dd>
    <dt>constraint:(traverseByEntityProperty|pickFirstByEntityProperty)?</dt>
    <dd>
        optional constraint that specifies the way entities are sorted, see [`pickFirstByEntityProperty`](#pick-first-by-entity-property)
        and [`traverseByEntityProperty`](#traverse-by-entity-property) constraint documentation for more details
    </dd>
    <dt>constraint:orderingConstraint+</dt>
    <dd>
        one or more [ordering constraints](./natural.md) that specify the ordering by the reference attribute
    </dd>
</dl>

<Note type="info">

<NoteTitle toggles="false">

##### The `referenceProperty` is implicit in requirement `referenceContent`

</NoteTitle>

In the `orderBy` clause within the [`referenceContent`](../requirements/fetching.md#reference-content) requirement,
the `referenceProperty` constraint is implicit and must not be repeated. All attribute order constraints
in `referenceContent` automatically refer to the reference attributes, unless the [`entityProperty`](#entity-property)
container is used there.

</Note>

Sorting by reference attribute is not as common as sorting by entity attributes, but it allows you to sort entities
that are in a particular category or have a particular group specifically by the priority/order for that particular
relationship.

To sort products related to a "sale" group by the `orderInGroup` attribute set on the reference, you need to issue the
following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Query products in "sale" ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List of products in "sale" ordered by predecessor chain
</NoteTitle>

The example is based on a simple one-to-zero-or-one reference (a product can have at most one reference to a group
entity). The response will only return the products that have a reference to the "sale" group, all of which contain the
`orderInGroup` attribute (since it's marked as a non-nullable attribute). Because the example is so simple, the returned
result can be anticipated:

<LS to="e,j,c">

<MDInclude>[List of products in "sale" ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[List of products in "sale" ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[List of products in "sale" ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural.rest.json.md)</MDInclude>

</LS>

</Note>

<Note type="warning">

<NoteTitle toggles="false">

##### Ordering of 1:N references

</NoteTitle>

Things can get more complicated when the reference is one-to-many. What should you expect when you run a query that 
involves sorting by a property on a reference attribute? Relational databases allow this, but you need to deal with 
the problem of row multiplication. With evitaDB, you work with an entity model, so you don't have to worry about 
the multiplication problem. It's possible, but there are some specifics because evitaDB supports hierarchical entities.

Let's break it down into two cases:

**Non-hierarchical entity**

If the referenced entity is non-hierarchical and the returned entity references multiple entities, only the reference 
with the lowest primary key of the referenced entity, while also having the order property set, will be used for ordering.
This is the same as if you had used the [`pickFirstByEntityProperty`](#pick-first-by-entity-property) constraint in 
your `referenceProperty` container:

```evitaql
pickFirstByEntityProperty(   
    primaryKeyNatural(ASC),   
)
```

**Hierarchical entity**

If the referenced entity is **hierarchical** and the returned entity references multiple entities, the reference used
for ordering is the one that contains the order property and is the closest hierarchy node to the root of the filtered
hierarchy node.

It sounds complicated, but it's really quite simple. Imagine you're listing products from a category and also sorting 
them by a property called `orderInCategory` on the category reference. The first products you get are the ones directly 
related to the category, in order of `orderInCategory`. Then you get the products from the first child category, and so
on, keeping the category tree's order. This is the same as using the [`traverseByEntityProperty`](#traverse-by-entity-property) 
constraint in your `referenceProperty` container:

```evitaql
traverseByEntityProperty(  
    DEPTH_FIRST,  
    primaryKeyNatural(ASC),    
)
```

**Note:**

You can control the behaviour by using the `pickFirstByEntityProperty` or `traverseByEntityProperty` constraints in your
`referenceProperty` container. You can also use the `traverseByEntityProperty` for non-hierarchical entities and for 1:1
references. It changes the order so that the entities are first sorted by the property of the thing they're referring to,
and then by the reference property itself. For more information, check out the examples and the detailed documentation 
of the [`traverseByEntityProperty`] constraint.

</Note>

## Pick first by entity property

```evitaql-syntax
pickFirstByEntityProperty(
    constraint:orderingConstraint+
)
```

<dl>
    <dt>constraint:orderingConstraint+</dt>
    <dd>
        one or more [ordering constraints](./natural.md) that specify the ordering of the references to pick the first
        one from the list of references to the same entity to be used for ordering by `referenceProperty`
    </dd>
</dl>

The `pickFirstByEntityProperty` ordering constraint can only be used within the [`referenceProperty`](#reference-property)
ordering constraint, and makes sense only in case the cardinality of such reference is 1:N (although it's not actively
checked by the query engine). This constraint allows you specify the order of the references to pick the first one from
the list of references to the same entity to be used for ordering by `referenceProperty`.

Let's extend our previous example so that it returns products that refer not only to the group "sale", but also to the
group "new":

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Query products in groups "sale" or "new" ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List of products in groups "sale" or "new" ordered by predecessor chain
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[List of products in groups "sale" or "new" ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[List of products in groups "sale" or "new" ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[List of products in groups "sale" or "new" ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple.rest.json.md)</MDInclude>

</LS>

</Note>

The result will contain first products referring to a "new" group which has the lowest primary key, and then products
referring to a "sale" group. The order of products within each group will be determined by the `orderInGroup` attribute.
This is default behavior for references targeting non-hierarchical entities.

If we want to change the order of the groups, we can use the `pickFirstByEntityProperty` ordering constraint to explicitly
specify the order of the groups. For example, if we want to list products in the "sale" group first, we can use the
following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Query products in groups "sale" or "new" ordered by predecessor chain with explicit ordering](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple-explicit.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List of products in groups "sale" or "new" ordered by predecessor chain with explicit group ordering
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[List of products in groups "sale" or "new" ordered by predecessor chain with explicit group ordering](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple-explicit.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[List of products in groups "sale" or "new" ordered by predecessor chain with explicit group ordering](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple-explicit.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[List of products in groups "sale" or "new" ordered by predecessor chain with explicit group ordering](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple-explicit.rest.json.md)</MDInclude>

</LS>

As you can see when the product is related to both groups, the assignment to the "sale" group takes precedence and is
used for ordering. You can use all sorts of ordering constraints and tune the ordering to your needs.

</Note>

## Traverse by entity property

```evitaql-syntax
traverseByEntityProperty(
    argument:enum(DEPTH_FIRST|BREADTH_FIRST)?,
    constraint:orderingConstraint+
)
```

<dl>
    <dt>argument:enum(DEPTH_FIRST|BREADTH_FIRST)?</dt>
    <dd>
        optional argument that specifies the mode of the reference traversal, the default value is `DEPTH_FIRST`
    </dd>
    <dt>constraint:orderingConstraint+</dt>
    <dd>
        one or more [ordering constraints](./natural.md) that change the traversal order of the references of
        the ordered entity before the `referenceProperty` ordering constraint is applied
    </dd>
</dl>

The `traverseByEntityProperty` ordering constraint can only be used within the [`referenceProperty`](#reference-property) 
ordering constraint. This constraint defines that the entities should be sorted first by the referenced entity property,
and if such entity is hierarchical, it allows to specify whether the hierarchy should be traversed in depth-first or 
breadth-first order. When the order of the referenced entities is resolved, the `referenceProperty` ordering itself
is applied on the references to the referenced entities. If there are multiple references only the first one that can
be evaluated is used for ordering.

This behaviour is best illustrated by a following example. Let's list products in the *Accessories* category ordered
by the `orderInCategory` attribute on the reference to the category:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Query products in "Accessories" category ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-hierarchy.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List products in "Accessories" category ordered by predecessor chain

</NoteTitle>

<LS to="e,j,c">

<MDInclude>[List products in "Accessories" category ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-hierarchy.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[List products in "Accessories" category ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-hierarchy.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[List products in "Accessories" category ordered by predecessor chain](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-hierarchy.rest.json.md)</MDInclude>

</LS>

The result will first contain products directly related to the *Accessories* category, ordered by `orderInCategory` in
ascending order, then products *Christmas electronics* (which is the first child of the *Accessories* category with
the least primary key), then *Smart wearable* (which has no directly related products), then *Bands* (which is the first
child of the *Smart wearable* category), and so on. The order follows the order of the categories in the following
image:

![dynamic-tree.png](../requirements/assets/dynamic-tree.png)

If the product was related to both **Christmas electronics** and **Smart wearable** categories, the product would be
listed only once as if it would be related only to the **Christmas electronics** category, because in this query
primary key of the category is used for the hierarchy traversal.

</Note>

Consider the another example where we want to list products in the *Accessories* category ordered by the `orderInCategory` 
attribute on the reference to the category, but we want to traverse the hierarchy in breadth-first order and the categories
themselves should be sorted by their `order` attribute first:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[List products breadth first by order in category in category order](/documentation/user/en/query/ordering/examples/reference/reference-traverse-by.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Result of listing products by order in category in breadth first category order
</NoteTitle>

<LS to="e,j">

<MDInclude sourceVariable="recordData.0">[Result of listing products by order in category in breadth first category order](/documentation/user/en/query/ordering/examples/reference/reference-traverse-by.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Result of listing products by order in category in breadth first category order](/documentation/user/en/query/ordering/examples/reference/reference-traverse-by.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Result of listing products by order in category in breadth first category order](/documentation/user/en/query/ordering/examples/reference/reference-traverse-by.rest.json.md)</MDInclude>

</LS>

As you can see you have full control over the the ordering of the entities and the order of the references within the
hierarchy.

</Note>

## Entity property

```evitaql-syntax
entityProperty(
    constraint:orderingConstraint+
)
```

<dl>
    <dt>constraint:orderingConstraint+</dt>
    <dd>
        one or more [ordering constraints](./natural.md) that specify the ordering by the referenced entity attributes
    </dd>
</dl>

The `entityProperty` ordering constraint can only be used within the [`referenceContent`](../requirements/fetching.md#reference-content)
requirement. It allows to change the context of the reference ordering from attributes of the reference itself to
attributes of the entity the reference points to.

In other words, if the `Product` entity has multiple references to `ParameterValue` entities, you can sort those
references by, for example, the `order` or `name` attribute of the `ParameterValue` entity. Let's see an example:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Get product with parameters ordered by their priority](/documentation/user/en/query/ordering/examples/reference/entity-property.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Get product with parameter values ordered by their name
</NoteTitle>

<LS to="e,j">

<MDInclude sourceVariable="recordData.0">[Get product with parameters ordered by their name](/documentation/user/en/query/ordering/examples/reference/entity-property.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Get product with parameters ordered by their name](/documentation/user/en/query/ordering/examples/reference/entity-property.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Get product with parameters ordered by their name](/documentation/user/en/query/ordering/examples/reference/entity-property.rest.json.md)</MDInclude>

</LS>

</Note>

## Entity group property

```evitaql-syntax
entityGroupProperty(
    constraint:orderingConstraint+
)
```

<dl>
    <dt>constraint:orderingConstraint+</dt>
    <dd>
        one or more [ordering constraints](./natural.md) that specify the ordering by the referenced entity group
        attributes
    </dd>
</dl>

The `entityGroupProperty` ordering constraint can only be used within the [`referenceContent`](../requirements/fetching.md#reference-content) requirement. It
allows the context of the reference ordering to be changed from attributes of the reference itself to attributes of
the group entity within which the reference is aggregated.

In other words, if the `Product` entity has multiple references to `ParameterValue` entities that are grouped by their
assignment to the `Parameter` entity, you can sort those references primarily by the `name` attribute of the grouping
entity, and secondarily by the `name` attribute of the referenced entity. Let's look at an example:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Get product with parameters ordered by their group name and name](/documentation/user/en/query/ordering/examples/reference/entity-group-property.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Get product with parameters ordered by their priority
</NoteTitle>

<LS to="e,j,c">

<MDInclude sourceVariable="recordData.0">[Get product with parameters ordered by their group name and name](/documentation/user/en/query/ordering/examples/reference/entity-group-property.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Get product with parameters ordered by their group name and name](/documentation/user/en/query/ordering/examples/reference/entity-group-property.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Get product with parameters ordered by their group name and name](/documentation/user/en/query/ordering/examples/reference/entity-group-property.rest.json.md)</MDInclude>

</LS>

</Note>