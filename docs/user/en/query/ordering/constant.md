---
title: Constant ordering
perex: |
  There are situations when the order of entities is specified outside evitaDB. The constant order constraints allow to 
  control the order of the selected entities by a caller logic. 
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
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

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">
[Transitive category listing](/docs/user/en/query/ordering/examples/constant/entity-primary-key-in-filter.evitaql)
</SourceCodeTabs>

The sample query returns exactly 4 products, which maintain the order of the filtered primary keys in the query that 
was issued. 

<Note type="info">

<NoteTitle toggles="true">

##### List of products sorted by order of entity primary keys in filter
</NoteTitle>

<MDInclude>[Entities sorted by order of the filtered primary keys](/docs/user/en/query/ordering/examples/constant/entity-primary-key-in-filter.evitaql.md)</MDInclude>

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

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">
[Transitive category listing](/docs/user/en/query/ordering/examples/constant/entity-primary-key-exact.evitaql)
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

<MDInclude>[Entities sorted by the specified order of the primary keys](/docs/user/en/query/ordering/examples/constant/entity-primary-key-exact.evitaql.md)</MDInclude>

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

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">
[Transitive category listing](/docs/user/en/query/ordering/examples/constant/attribute-set-in-filter.evitaql)
</SourceCodeTabs>

The sample query returns exactly 3 products, preserving the order of the entity's `code` attribute used in the filter 
constraint of the query that was issued.

<Note type="info">

<NoteTitle toggles="true">

##### List of products sorted by order of attribute `code` in filter
</NoteTitle>

<MDInclude>[Entities sorted by order `code` attribute of the filtered entities](/docs/user/en/query/ordering/examples/constant/attribute-set-in-filter.evitaql.md)</MDInclude>

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

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">
[Transitive category listing](/docs/user/en/query/ordering/examples/constant/attribute-set-exact.evitaql)
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

<MDInclude>[Entities sorted by the specified order of the attribute `code` values](/docs/user/en/query/ordering/examples/constant/attribute-set-exact.evitaql.md)</MDInclude>

</Note>