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
than *Adam*, but *Jasmine* is not greater than *Joanna*. The correct [collator](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/text/Collator.html) is used to compare the localized attribute string, so that the order is consistent with the national customs of 
the language.

When you compare two **[Range](../../use/data-types.md#numberrange)** data types, the larger one is the one whose left
boundary is greater than the left boundary of the other value. If both left boundaries are equal, the greater is the
one with the greater right boundary.

The **[boolean](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)** data type is compared as 
a numeric value, where the *true* is 1, and *false* is 0.
</Note>

## Attribute equals

```evitaql-syntax
attributeEquals(
    argument:string!,
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

The `attributeEquals` compares filterable or unique entity [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
for strict equality with the passed value.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Product with `code` attribute equal to `apple-iphone-13-pro-3`](/docs/user/en/query/filtering/examples/comparable/attribute-equals.evitaql)
</SourceCodeTabs>

Returns exactly one product with *code* equal to *apple-iphone-13-pro-3*.

<Note type="info">

<NoteTitle toggles="true">

##### Product found by a `code` attribute
</NoteTitle>

<LanguageSpecific to="evitaql,java">

<MDInclude>[Product with `code` attribute equal to `apple-iphone-13-pro-3`](/docs/user/en/query/filtering/examples/comparable/attribute-equals.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Product with `code` attribute equal to `apple-iphone-13-pro-3`](/docs/user/en/query/filtering/examples/comparable/attribute-equals.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Product with `code` attribute equal to `apple-iphone-13-pro-3`](/docs/user/en/query/filtering/examples/comparable/attribute-equals.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Attribute greater than

```evitaql-syntax
attributeGreaterThan(
    argument:string!,
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

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Products with `battery-life` attribute greater than 40 hours](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than.evitaql)
</SourceCodeTabs>

Returns exactly several products with *battery-life* greater than *40* hours.

<Note type="info">

<NoteTitle toggles="true">

##### Products with `battery-life` attribute greater than 40 hours
</NoteTitle>

<LanguageSpecific to="evitaql,java">

<MDInclude>[Products with `battery-life` attribute greater than 40 hours](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Products with `battery-life` attribute greater than 40 hours](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Products with `battery-life` attribute greater than 40 hours](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Attribute greater than, equals

```evitaql-syntax
attributeGreaterThanEquals(
    argument:string!,
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

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Products with `battery-life` attribute greater than or equal to 40 hours](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than-equals.evitaql)
</SourceCodeTabs>

Returns exactly several products with *battery-life* greater than or equal to *40* hours.

<Note type="info">

<NoteTitle toggles="true">

##### Products with `battery-life` attribute greater than or equal to 40 hours
</NoteTitle>

<LanguageSpecific to="evitaql,java">

<MDInclude>[Products with `battery-life` attribute greater than or equal to 40 hours](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than-equals.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Products with `battery-life` attribute greater than or equal to 40 hours](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than-equals.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Products with `battery-life` attribute greater than or equal to 40 hours](/docs/user/en/query/filtering/examples/comparable/attribute-greater-than-equals.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Attribute less than

```evitaql-syntax
attributeLessThan(
    argument:string!,
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
        the arbitrary value that will be compared to [entity attribute](../../use/schema.md#attributes) to be less
        than the attribute of the examined entity
    </dd>
</dl>

The `attributeLessThan` compares the filterable or unique entity [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
with the value in the second argument and is satisfied only if the entity attribute is less than the value.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Products with `battery-life` attribute less than 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-less-than.evitaql)
</SourceCodeTabs>

Returns exactly several products with *battery-capacity* less than *125* mWH.

<Note type="info">

<NoteTitle toggles="true">

##### Products with `battery-capacity` attribute less than 125 mWH
</NoteTitle>

<LanguageSpecific to="evitaql,java">

<MDInclude>[Products with `battery-life` attribute less than 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-less-than.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Products with `battery-life` attribute less than 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-less-than.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Products with `battery-life` attribute less than 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-less-than.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Attribute less than, equals

```evitaql-syntax
attributeLessThanEquals(
    argument:string!,
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
        the arbitrary value that will be compared to [entity attribute](../../use/schema.md#attributes) to be less
        than or equal to the attribute of the examined entity
    </dd>
</dl>

The `attributeLessThanEquals` compares the filterable or unique entity [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
with the value in the second argument and is satisfied only if the entity attribute is less than or equal to the value.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Products with `battery-life` attribute less than or equal to 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-less-than-equals.evitaql)
</SourceCodeTabs>

Returns exactly several products with *battery-capacity* less than or equal to *125* mWH.

<Note type="info">

<NoteTitle toggles="true">

##### Products with `battery-capacity` attribute less than or equal to 125 mWH
</NoteTitle>

<LanguageSpecific to="evitaql,java">

<MDInclude>[Products with `battery-life` attribute less than or equal to 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-less-than-equals.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Products with `battery-life` attribute less than or equal to 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-less-than-equals.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Products with `battery-life` attribute less than or equal to 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-less-than-equals.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Attribute between

```evitaql-syntax
attributeBetween(
    argument:string!,
    argument:comparable!,
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
    <dt>argument:comparable!</dt>
    <dd>
        the arbitrary value that will be compared to [entity attribute](../../use/schema.md#attributes) to be less
        than or equal to the attribute of the examined entity
    </dd>
</dl>

The `attributeBetween` compares the filterable or unique entity [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
and is satisfied only if the entity attribute is less than or equal to the first argument and at the same time greater
than or equal to the second argument of the constraint.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Products with `battery-life` attribute less than or equal to 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-between.evitaql)
</SourceCodeTabs>

Returns exactly several products with *battery-capacity* between *125* and *160* mWH.

<Note type="info">

<NoteTitle toggles="true">

##### Products with `battery-capacity` attribute is between 125 mWH and 160 mWH
</NoteTitle>

<LanguageSpecific to="evitaql,java">

<MDInclude>[Products with `battery-life` attribute less than or equal to 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-between.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Products with `battery-life` attribute less than or equal to 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-between.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Products with `battery-life` attribute less than or equal to 125 mWH](/docs/user/en/query/filtering/examples/comparable/attribute-between.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Attribute in set

```evitaql-syntax
attributeInSet(
    argument:string!,
    argument:comparable+
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        the name of the [entity attribute](../../use/schema.md#attributes) whose value will be compared to the value
        in the second argument
    </dd>
    <dt>argument:comparable+</dt>
    <dd>
        one or more values that will be compared to [entity attribute](../../use/schema.md#attributes) for equality
    </dd>
</dl>

The `attributeInSet` compares filterable or unique entity [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
for strict equality with any of the passed values.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Product found by a `code` attribute in given set](/docs/user/en/query/filtering/examples/comparable/attribute-in-set.evitaql)
</SourceCodeTabs>

Returns exactly three product with *code* matching one of the arguments. Last of the products was not found 
in the database and is missing in the result.

<Note type="info">

<NoteTitle toggles="true">

##### Product found by a `code` attribute in given set
</NoteTitle>

<LanguageSpecific to="evitaql,java">

<MDInclude>[Product found by a `code` attribute in given set](/docs/user/en/query/filtering/examples/comparable/attribute-in-set.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Product found by a `code` attribute in given set](/docs/user/en/query/filtering/examples/comparable/attribute-in-set.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Product found by a `code` attribute in given set](/docs/user/en/query/filtering/examples/comparable/attribute-in-set.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Attribute is

```evitaql-syntax
attributeIs(
    argument:string!
    argument:enum(NULL|NOT_NULL)
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        the name of the [entity attribute](../../use/schema.md#attributes) whose value will be checked for (non) existence
    </dd>

</dl>

The `attributeIs` can be used to test for the existence of an entity 
[attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized) of a given name.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Product with `catalogNumber` attribute present](/docs/user/en/query/filtering/examples/comparable/attribute-is-not-null.evitaql)
</SourceCodeTabs>

Returns hundreds of products with the *catalogNumber* attribute set.

<Note type="info">

<NoteTitle toggles="true">

##### Products with `catalogNumber` present
</NoteTitle>

<LanguageSpecific to="evitaql,java">

<MDInclude>[Product with `catalogNumber` attribute present](/docs/user/en/query/filtering/examples/comparable/attribute-is-not-null.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Product with `catalogNumber` attribute present](/docs/user/en/query/filtering/examples/comparable/attribute-is-not-null.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Product with `catalogNumber` attribute present](/docs/user/en/query/filtering/examples/comparable/attribute-is-not-null.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

When you try to list products without such attribute:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Product with `catalog-number` attribute missing](/docs/user/en/query/filtering/examples/comparable/attribute-is-null.evitaql)
</SourceCodeTabs>

... you will get a single one:

<Note type="info">

<NoteTitle toggles="true">

##### Products with `catalog-number` missing
</NoteTitle>

<LanguageSpecific to="evitaql,java">

<MDInclude>[Product with `catalog-number` attribute missing](/docs/user/en/query/filtering/examples/comparable/attribute-is-null.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Product with `catalog-number` attribute missing](/docs/user/en/query/filtering/examples/comparable/attribute-is-null.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Product with `catalog-number` attribute missing](/docs/user/en/query/filtering/examples/comparable/attribute-is-null.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>