---
title: Ordering references / by reference attribute
perex: |
  You can sort entities by attributes on references, and you can also sort fetched referenced entities by their 
  attributes or by attributes of references that point to them. Although these are fundamentally different scenarios, 
  both are described in this section.
date: '25.6.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
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
that are in a particular category or have a particular brand specifically by the priority/order for that particular
relationship.

To sort products related to a "Sony" brand by the `orderInBrand` attribute set on the reference, you need to issue the
following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[List of "Sony" products ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List of "Sony" products ordered by priority
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[List of "Sony" products ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[List of "Sony" products ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[List of "Sony" products ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

The example is based on a simple one-to-zero-or-one reference (a product can have at most one reference to a brand 
entity). The response will only return the products that have a reference to the "Sony" brand, all of which contain the 
`orderInBrand` attribute (since it's marked as a non-nullable attribute). Because the example is so simple, the returned 
result can be anticipated.

### Behaviour of zero or one to many references ordering

The situation is more complicated when the reference is one-to-many. What is the expected result of a query that
involves ordering by a property on a reference attribute? Is it wise to allow such ordering query in this case?

We decided to allow it and bind it with the following rules:

#### Non-hierarchical entity

If the referenced entity is **non-hierarchical**, and the returned entity references multiple entities, only 
the reference with the lowest primary key of the referenced entity, while also having the order property set, will be
used for ordering.

Let's extend our previous example so that it returns products that refer not only to the brand "Sony", but also to the 
brand "Google":

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[List of "Sony" or "Google" products ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List of "Sony" or "Google" products ordered by priority
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[List of "Sony" or "Google" products ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[List of "Sony" or "Google" products ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[List of "Sony" or "Google" products ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-multiple.rest.json.md)</MDInclude>

</LanguageSpecific>

The result will contain first products referring to a "Google" brand which has the lowest primary key, and then products
referring to a "Sony" brand. The order of products within each group will be determined by the `orderInBrand` attribute.

</Note>

#### Hierarchical entity

If the referenced entity is **hierarchical** and the returned entity references multiple entities, the reference used 
for ordering is the one that contains the order property and is the closest hierarchy node to the root of the filtered 
hierarchy node.

It sounds complicated, but it's really quite simple. If you list products of a certain category and at the same time 
order them by a property "priority" set on the reference to the category, the first products will be those directly 
related to the category, ordered by "priority", followed by the products of the first child category, and so on, 
maintaining the depth-first order of the category tree.

This behaviour is best illustrated by a following example. Let's list products in the "Accessories" category ordered 
by the `orderInCategory` attribute on the reference to the category:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[List products in "Accessories" category ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-hierarchy.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List products in "Accessories" category ordered by priority
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[List products in "Accessories" category ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-hierarchy.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[List products in "Accessories" category ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-hierarchy.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[List products in "Accessories" category ordered by priority](/documentation/user/en/query/ordering/examples/reference/reference-attribute-natural-hierarchy.rest.json.md)</MDInclude>

</LanguageSpecific>

The result will first contain products directly related to the "Accessories" category, ordered by `orderInCategory` in 
ascending order, then products "Christmas electronics" (which is the first child of the "Accessories" category with 
the least primary key), then "Smart wearable" (which has no directly related products), then "Bands" (which is the first 
child of the "Smart wearable" category), and so on. The order follows the order of the categories in the following 
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

In other words, if the `Product` entity has multiple references to `Parameter` entities, you can sort those references
by, for example, the `priority` or `name` attribute of the `Parameter` entity. Let's see an example:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Get product with parameters ordered by their priority](/documentation/user/en/query/ordering/examples/reference/entity-property.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Get product with parameters ordered by their priority
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude sourceVariable="recordData.0">[Get product with parameters ordered by their priority](/documentation/user/en/query/ordering/examples/reference/entity-property.evitaql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Get product with parameters ordered by their priority](/documentation/user/en/query/ordering/examples/reference/entity-property.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Get product with parameters ordered by their priority](/documentation/user/en/query/ordering/examples/reference/entity-property.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>