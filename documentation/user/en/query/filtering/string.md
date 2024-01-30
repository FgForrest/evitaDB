---
title: String filtering
date: '17.1.2023'
perex: |
  There are several filtering constraints designed to work especially with string attributes. They are useful for
  looking for entities with attributes that contain a specific string.
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

<Note type="info">
In the context of the limitations described in this chapter, you might be interested in the general rules for handling
data types and arrays described in [the query language basics](../basics.md#generic-query-rules).
</Note>

## Attribute contains

```evitaql-syntax
attributeContains(
    argument:string!,
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        the name of the [entity attribute](../../use/schema.md#attributes) whose value will be scanned for
        the occurrence of the string in the second argument
    </dd>
    <dt>argument:string!</dt>
    <dd>
        the arbitrary value to search for in the attribute value (case-sensitive)
    </dd>
</dl>

The `attributeContains` searches the filterable or unique entity [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
for the occurrence of the string. The constraint behaves exactly like the <LS to="e,j,r,g">[Java `contains` method](https://www.javatpoint.com/java-string-contains)</LS><LS to="c">[C# `Contains` method](https://learn.microsoft.com/en-us/dotnet/api/system.string.contains)</LS>.
It's case-sensitive, works with national characters (since we're working with UTF-8 strings), and requires an exact
match of the searched string anywhere in the attribute value.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Products having a `epix` string in the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-contains.evitaql)
</SourceCodeTabs>

Returns a few products having a string *epix* in the attribute *code*.

<Note type="info">

<NoteTitle toggles="true">

##### Products having a `epix` string in the `code` attribute
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Products having a `epix` string in the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-contains.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Products having a `epix` string in the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-contains.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Products having a `epix` string in the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-contains.rest.json.md)</MDInclude>

</LS>

</Note>

## Attribute starts with

```evitaql-syntax
attributeStartsWith(
    argument:string!,
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        the name of the [entity attribute](../../use/schema.md#attributes) whose value will be tested to see if it
        starts with the string in the second argument
    </dd>
    <dt>argument:string!</dt>
    <dd>
        the arbitrary value to search for in the attribute value (case-sensitive)
    </dd>
</dl>

The `attributeStartsWith` searches the filterable or unique entity [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
and checks if it starts with the specified string. The constraint behaves exactly like the <LS to="e,j,r,g">[Java `startsWith` method](https://www.javatpoint.com/java-string-startswith)</LS><LS to="c">[C# `StartsWith` method](https://learn.microsoft.com/en-us/dotnet/api/system.string.startswith)</LS>.
It's case-sensitive, works with national characters (since we're working with UTF-8 strings), and requires an exact
match of the search string at the beginning of the attribute value.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Products having a `garmin` string at the beginning of the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-starts-with.evitaql)
</SourceCodeTabs>

Returns a few pages of products that start with a *garmin* string in the *code* attribute.

<Note type="info">

<NoteTitle toggles="true">

##### Products having a `garmin` string at the beginning of the `code` attribute
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Products having a `garmin` string at the beginning of the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-starts-with.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Products having a `garmin` string at the beginning of the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-starts-with.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Products having a `garmin` string at the beginning of the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-starts-with.rest.json.md)</MDInclude>

</LS>

</Note>

## Attribute ends with

```evitaql-syntax
attributeEndsWith(
    argument:string!,
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        the name of the [entity attribute](../../use/schema.md#attributes) whose value will be tested to see if it ends
        with the string in the second argument
    </dd>
    <dt>argument:string!</dt>
    <dd>
        the arbitrary value to search for in the attribute value (case-sensitive)
    </dd>
</dl>

The `attributeEndssWith` searches the filterable or unique entity [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
and checks if it ends with the specified string. The constraint behaves exactly like the
<LS to="e,j,r,g">[Java `endsWith` method](https://www.javatpoint.com/java-string-endswith)</LS><LS to="c">[C# `EndsWith` method](https://learn.microsoft.com/en-us/dotnet/api/system.string.endswith)</LS>.
It's case-sensitive, works with national characters (since we're working with UTF-8 strings), and requires an exact
match of the search string at the end of the attribute value.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Products having a `solar` string at the end of the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-ends-with.evitaql)
</SourceCodeTabs>

Returns a few products that end with a *solar* string in the *code* attribute.

<Note type="info">

<NoteTitle toggles="true">

##### Products having a `solar` string at the end of the `code` attribute
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Products having a `solar` string at the end of the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-ends-with.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Products having a `solar` string at the end of the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-ends-with.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Products having a `solar` string at the end of the `code` attribute](/documentation/user/en/query/filtering/examples/string/attribute-ends-with.rest.json.md)</MDInclude>

</LS>

</Note>