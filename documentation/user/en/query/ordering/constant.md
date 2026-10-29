---
title: Constant ordering
perex: |
  There are situations when the order of entities is specified outside evitaDB. The constant order constraints allow to
  control the order of the selected entities by a caller logic.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
preferredLang: 'evitaql'
---

The constant ordering constraints are particularly useful when you have a sorted set of unique attributes or directly
the entity primary keys provided by an external system that needs to be maintained in the evitaDB output (for example,
it represents a relevance of those entities from the full-text engine).

## Exact entity primary key order used in filter

```evitaql-syntax
entityPrimaryKeyInFilter()
```

The constraint allows output entities to be sorted by primary key values in the exact order used to filter them.
The constraint requires the presence of exactly one [`entityPrimaryKeyInSet`](../filtering/constant.md#entity-primary-key-in-set)
constraint in the filter part of the query. It uses the specified array of entity primary keys to sort the result
returned by the query.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entities sorted by order of the filtered primary keys](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-in-filter.evitaql)
</SourceCodeTabs>

The sample query returns exactly 4 products, which maintain the order of the filtered primary keys in the query that
was issued.

<Note type="info">

<NoteTitle toggles="true">

##### List of products sorted by order of entity primary keys in filter
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Entities sorted by order of the filtered primary keys](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-in-filter.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Entities sorted by order of the filtered primary keys](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-in-filter.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Entities sorted by order of the filtered primary keys](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-in-filter.rest.json.md)</MDInclude>

</LS>

</Note>

## Exact entity primary key order

```evitaql-syntax
entityPrimaryKeyExact(
    argument:int+
)
```

<dl>
    <dt>argument:int+</dt>
    <dd>
        a mandatory set of entity primary keys that control the order of the query result
    </dd>
</dl>

The constraint allows output entities to be sorted by entity primary keys in the exact order specified in the 2nd through
Nth arguments of this constraint.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entities sorted by the specified order of the primary keys](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-exact.evitaql)
</SourceCodeTabs>

The sample query returns all products whose code starts with the string *lenovo*, but uses the order of the first three
entities in the output as specified by the order constraint `entityPrimaryKeyExact`. Because the query returns more
results than the order constraint has information for, the rest of the result set is sorted *traditionally* by
the entity primary key in ascending order. If there is another order constraint in the chain, it would be used to sort
the rest of the query result.

<Note type="info">

<NoteTitle toggles="true">

##### List of products sorted by the exact order of entity primary keys
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Entities sorted by the specified order of the primary keys](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-exact.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Entities sorted by the specified order of the primary keys](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-exact.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Entities sorted by the specified order of the primary keys](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-exact.rest.json.md)</MDInclude>

</LS>

</Note>

## Exact entity attribute value order used in filter

```evitaql-syntax
attributeSetInFilter(
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        a mandatory name of the [attribute name](../../use/schema.md#attributes) that control the order of
        the query result
    </dd>
</dl>

This constraint allows output entities to be sorted by values of the specified attribute in the exact order in which
they were filtered. The constraint requires the presence of exactly one [`attribute-in-set`](../filtering/comparable.md#attribute-in-set)
in the filter part of the query, referring to the attribute with the same name as used in the first argument of this
constraint. It uses the specified array of attribute values to sort the result returned by the query.

<Note type="info">

<NoteTitle toggles="true">

##### Chain attributes cannot be ordered this way

</NoteTitle>

Chain attributes ([`Predecessor`](../../use/data-types.md#predecessor) and `ReferencedEntityPredecessor`) cannot
supply these filter values: they cannot be marked as filterable, so the required
[`attributeInSet`](../filtering/comparable.md#attribute-in-set) filter is refused. Use
[`attributeNatural`](./comparable.md#attribute-natural) to order by the chain instead.

</Note>

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entities sorted by order `code` attribute of the filtered entities](/documentation/user/en/query/ordering/examples/constant/attribute-set-in-filter.evitaql)
</SourceCodeTabs>

The sample query returns exactly 3 products, preserving the order of the entity's `code` attribute used in the filter
constraint of the query that was issued.

<Note type="info">

<NoteTitle toggles="true">

##### List of products sorted by order of attribute `code` in filter
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Entities sorted by order `code` attribute of the filtered entities](/documentation/user/en/query/ordering/examples/constant/attribute-set-in-filter.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Entities sorted by order `code` attribute of the filtered entities](/documentation/user/en/query/ordering/examples/constant/attribute-set-in-filter.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Entities sorted by order `code` attribute of the filtered entities](/documentation/user/en/query/ordering/examples/constant/attribute-set-in-filter.rest.json.md)</MDInclude>

</LS>

</Note>

## Exact entity attribute value order

```evitaql-syntax
attributeSetExact(
    argument:string!,
    argument:comparable+
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        a mandatory name of the [attribute name](../../use/schema.md#attributes) that control the order of
        the query result
    </dd>
    <dt>argument:comparable+</dt>
    <dd>
        a mandatory set of attribute values whose data type matches [attribute data type](../../use/schema.md#attributes),
        which define the order of the query result
    </dd>
</dl>

The constraint allows output entities to be sorted by attribute values in the exact order specified in the 2nd through
Nth arguments of this constraint.

<Note type="info">

<NoteTitle toggles="true">

##### Chain attributes cannot be ordered this way

</NoteTitle>

Chain attributes of type [`Predecessor`](../../use/data-types.md#predecessor) or `ReferencedEntityPredecessor` are
refused when the query is translated, both at entity level and inside
[`referenceProperty`](./reference.md#reference-property) orderings. A chain orders by predecessor links, so its
values cannot be listed in an exact value order. Use [`attributeNatural`](./comparable.md#attribute-natural) to follow
the chain instead. Inside `referenceProperty`, that needs
[`traverseByEntityProperty`](./reference.md#traverse-by-entity-property) or no traversal constraint at all, because
[`pickFirstByEntityProperty`](./reference.md#pick-first-by-entity-property) cannot order by a chain.

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Exact ordering inside `traverseByEntityProperty`

</NoteTitle>

Inside a [`referenceProperty`](./reference.md#reference-property) ordering with
[`traverseByEntityProperty`](./reference.md#traverse-by-entity-property), `attributeSetExact` orders each block of
the traversal separately (see [how the entities are placed](./reference.md#how-the-entities-are-placed)). In each
block, the listed values come first, in the listed order, with ties by ascending primary key. An entity whose value
is not listed, or that has no value, passes to the next ordering constraint inside `referenceProperty`; if none of
them places it, it waits for a later block. Entities that no block places continue to the next ordering constraint of
the query.

The value of an entity is taken from its first reference to the block's entity that has a value - in the order of
the [representative attribute](../../use/schema.md#reference-cardinality) values when there are several. A later
reference with a listed value does not replace an earlier one with an unlisted value.

Under [`pickFirstByEntityProperty`](./reference.md#pick-first-by-entity-property), `attributeSetExact` works only
when the query narrows the ordered reference down to a single referenced entity, for example by filtering for it with
[`referenceHaving`](../filtering/references.md#reference-having). The same applies to
[`attributeSetInFilter`](#exact-entity-attribute-value-order-used-in-filter) inside any reference ordering.

</Note>

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entities sorted by the specified order of the attribute `code` values](/documentation/user/en/query/ordering/examples/constant/attribute-set-exact.evitaql)
</SourceCodeTabs>

The sample query returns all products whose code starts with the string *lenovo*, but uses the order of the first three
entities in the output as specified by the order constraint `attributeSetExact`. Because the query returns more
results than the order constraint has information for, the rest of the result set is sorted *traditionally* by
the entity primary key in ascending order. If there is another order constraint in the chain, it would be used to sort
the rest of the query result.

<Note type="info">

<NoteTitle toggles="true">

##### List of products sorted by the exact order of entity attribute `code`
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Entities sorted by the specified order of the attribute `code` values](/documentation/user/en/query/ordering/examples/constant/attribute-set-exact.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Entities sorted by the specified order of the attribute `code` values](/documentation/user/en/query/ordering/examples/constant/attribute-set-exact.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Entities sorted by the specified order of the attribute `code` values](/documentation/user/en/query/ordering/examples/constant/attribute-set-exact.rest.json.md)</MDInclude>

</LS>

</Note>
