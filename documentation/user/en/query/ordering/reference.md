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
    constraint:orderingConstraint+
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        a mandatory name of the reference whose attribute is to be used for the ordering
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

The example is based on a simple one-to-zero-or-one reference (a product can have at most one reference to a group
entity). The response will only return the products that have a reference to the "sale" group, all of which contain the
`orderInGroup` attribute (since it's marked as a non-nullable attribute). Because the example is so simple, the returned
result can be anticipated.

### Behaviour of zero or one to many references ordering

The situation is more complicated when the reference is one-to-many. What is the expected result of a query that
involves ordering by a property on a reference attribute? Is it wise to allow such ordering query in this case?

We decided to allow it and bind it with the following rules:

#### Non-hierarchical entity

If the referenced entity is **non-hierarchical**, and the returned entity references multiple entities, only
the reference with the lowest primary key of the referenced entity, while also having the order property set, will be
used for ordering.

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

The result will contain first products referring to a "new" group which has the lowest primary key, and then products
referring to a "sale" group. The order of products within each group will be determined by the `orderInGroup` attribute.

</Note>

#### Hierarchical entity

If the referenced entity is **hierarchical** and the returned entity references multiple entities, the reference used
for ordering is the one that contains the order property and is the closest hierarchy node to the root of the filtered
hierarchy node.

It sounds complicated, but it's really quite simple. If you list products of a certain category and at the same time
order them by a property `orderInCategory` set on the reference to the category, the first products will be those
directly related to the category, ordered by `orderInCategory`, followed by the products of the first child category,
and so on, maintaining the depth-first order of the category tree.

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

</Note>

<Note type="warning">

<NoteTitle toggles="true">

##### Both rules order the sorted groups by primary key in ascending order. Do you need different behaviour?
</NoteTitle>

If so, please vote for the [issue #160](https://github.com/FgForrest/evitaDB/issues/160) on GitHub. This issue won't
be resolved until there is a demand for it.

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