---
title: Comparable filtering
date: '29.5.2023'
perex: |
  All attribute data types in evitaDB have mutually comparable values - it is always possible to unambiguously tell 
  whether two values are the same, one is smaller than the other, or vice versa. This fact underlies the basic set of 
  filtering constraints that you will commonly use when building queries.  
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

<Note type="info">
In the context of the limitations described in this chapter, you might be interested in the general rules for handling 
data types and arrays described in [the query language basics](../basics.md#generic-query-rules).
</Note>

<Note type="warning">
When you compare two **[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)** 
data types, the strings are compared alphabetically from the beginning of the string. For example, *Walther* is greater 
than *Adam*, but *Jasmine* is not greater than *Joanna*.

When you compare two [Range](../../use/data-types.md#numberrange) data types, the larger one is the one whose left
boundary is greater than the left boundary of the other value. If both left boundaries are equal, the greater is the
one with the greater right boundary.

The boolean data type is compared as a numeric value, where the *true* is 1, and *false* is 0.
</Note>

## Attribute equals

```evitaql-syntax
attributeEquals(
    argument:string!
    argument:comparable!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        the name of the [entity attribute](../../use/schema.md#attributes) whose value will be compared to the value
        in the second argument
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        the arbitrary value that will be compared to [entity attribute](../../use/schema.md#attributes) for equality
    </dd>
</dl>

The `attributeEquals` will compare filterable or unique entity [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
for strict equality with the passed value.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">
[Transitive category listing](/docs/user/en/query/filtering/examples/comparable/attribute-equals.evitaql)
</SourceCodeTabs>

Returns exactly one product with *code* equal to *apple-iphone-13-pro-3*.

<Note type="info">

<NoteTitle toggles="true">

##### Product found by a `code` attribute
</NoteTitle>

<MDInclude>[Product with `code` attribute equal to `apple-iphone-13-pro-3`](/docs/user/en/query/filtering/examples/comparable/attribute-equals.evitaql.md)</MDInclude>

</Note>

## Attribute greater than

```evitaql-syntax
attributeGreaterThan(
    argument:string!
    argument:comparable!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        the name of the [entity attribute](../../use/schema.md#attributes) whose value will be compared to the value
        in the second argument
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        the arbitrary value that will be compared to [entity attribute](../../use/schema.md#attributes) to be greater
        than the attribute of the examined entity
    </dd>
</dl>

The `attributeGreaterThan` compares the filterable or unique entity [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
with the value in the second argument and is satisfied only if the entity attribute is greater than the value.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">
[Transitive category listing](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than.evitaql)
</SourceCodeTabs>

Returns exactly several products with *battery-life* greater than *40* hours.

<Note type="info">

<NoteTitle toggles="true">

##### Products with `battery-life` attribute greater than 40 hours
</NoteTitle>

<MDInclude>[Products with `battery-life` attribute greater than 40 hours](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than.evitaql.md)</MDInclude>

</Note>

## Attribute greater than, equals

```evitaql-syntax
attributeGreaterThanEquals(
    argument:string!
    argument:comparable!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        the name of the [entity attribute](../../use/schema.md#attributes) whose value will be compared to the value
        in the second argument
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        the arbitrary value that will be compared to [entity attribute](../../use/schema.md#attributes) to be greater
        than or equal to the attribute of the examined entity
    </dd>
</dl>

The `attributeGreaterThanEquals` compares the filterable or unique entity [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
with the value in the second argument and is satisfied only if the entity attribute is greater than or equal to 
the value.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">
[Transitive category listing](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than-equals.evitaql)
</SourceCodeTabs>

Returns exactly several products with *battery-life* greater than or equal to *40* hours.

<Note type="info">

<NoteTitle toggles="true">

##### Products with `battery-life` attribute greater than or equal to 40 hours
</NoteTitle>

<MDInclude>[Products with `battery-life` attribute greater than or equal to 40 hours](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than-equals.evitaql.md)</MDInclude>

</Note>

## Attribute less than
## Attribute less than, equals
## Attribute between
## Attribute in set
## Attribute is false
## Attribute is true
## Attribute is null
## Attribute is not null