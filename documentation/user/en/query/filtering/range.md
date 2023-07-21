---
title: Range filtering
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

<Note type="info">
In the context of the limitations described in this chapter, you might be interested in the general rules for handling 
data types and arrays described in [the query language basics](../basics.md#generic-query-rules).
</Note>

## Attribute in range

```evitaql-syntax
attributeInRange(
    argument:string!,
    argument:comparable!
)
``` 

<dl>
    <dt>argument:string!</dt>
    <dd>
        the name of the [entity attribute](../../use/schema.md#attributes) whose [number range](../../use/data-types.md#numberrange) 
        or [datetime range](../../use/data-types.md#datetimerange) value starts, ends, or encloses the value 
        in the second argument
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        the value that must be within the attribute range to satisfy this constraint
    </dd>
</dl>

The `attributeInRange` checks whether the value in the second argument is within the range of the attribute value.
The value is within the range if it is equal to the start or end of the range or if it is between the start and end of 
the range.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Products valid for December '23](/documentation/user/en/query/filtering/examples/range/attribute-in-range.evitaql)
</SourceCodeTabs>

Returns a list of products with a *valid* date/time range that includes the date "2023-12-05T12:00:00+01:00" - these are 
the products targeted for the holiday sale. In a real-world query, you would probably want to combine this constraint 
with [attribute is](comparable.md#attribute-is) `NULL` using the [logical OR](logical.md#or) operator to also match 
products that have no date/time range attribute at all.

<Note type="info">

<NoteTitle toggles="true">

##### Products valid for December '23
</NoteTitle>

<LanguageSpecific to="evitaql,java">

<MDInclude>[Products valid for December '23](/documentation/user/en/query/filtering/examples/range/attribute-in-range.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Products valid for December '23](/documentation/user/en/query/filtering/examples/range/attribute-in-range.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Products valid for December '23](/documentation/user/en/query/filtering/examples/range/attribute-in-range.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>