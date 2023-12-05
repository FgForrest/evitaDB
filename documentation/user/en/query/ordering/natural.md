---
title: Natural ordering
perex: |
  Natural ordering is the most common type of ordering. It allows you to sort entities by their attributes in their 
  natural order (numerical, alphabetical, temporal, etc.).
date: '25.6.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
preferredLang: 'evitaql'
---

## Attribute natural

```evitaql-syntax
attributeNatural(
    argument:string!
    argument:enum(ASC|DESC)
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        a mandatory name of a sortable attribute
    </dd>
    <dt>argument:enum(ASC|DESC)</dt>
    <dd>
        the ordering direction (ascending or descending), **default value** is `ASC`
    </dd>
</dl>            

The constraint allows output entities to be sorted by their attributes in their natural order (numeric, alphabetical,
temporal). It requires specification of a single [attribute](../../use/data-model.md#attributes-unique-filterable-sortable-localized)
and the direction of the ordering.

To sort products by the number of their sales (the best-selling products first), we can use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[List of products sorted by number attribute](/documentation/user/en/query/ordering/examples/natural/attribute-natural-non-localized.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List of products sorted by number attribute
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[List of products sorted by number attribute](/documentation/user/en/query/ordering/examples/natural/attribute-natural-non-localized.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[List of products sorted by number attribute](/documentation/user/en/query/ordering/examples/natural/attribute-natural-non-localized.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[List of products sorted by number attribute](/documentation/user/en/query/ordering/examples/natural/attribute-natural-non-localized.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

If you want to sort products by their name, which is a localized attribute, you need to specify the `entityLocaleEquals`
constraint in the `filterBy` part of the query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[List of products sorted by localized attribute](/documentation/user/en/query/ordering/examples/natural/attribute-natural-localized.evitaql)
</SourceCodeTabs>

The correct <LanguageSpecific to="evitaql,java,rest,graphql">[collator](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/text/Collator.html)</LanguageSpecific><LanguageSpecific to="csharp">collator on the database side</LanguageSpecific> is used to 
order the localized attribute string, so that the order is consistent with the national customs of the language.

<Note type="info">

<NoteTitle toggles="true">

##### List of products sorted by localized attribute
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[List of products sorted by localized attribute](/documentation/user/en/query/ordering/examples/natural/attribute-natural-localized.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[List of products sorted by localized attribute](/documentation/user/en/query/ordering/examples/natural/attribute-natural-localized.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[List of products sorted by localized attribute](/documentation/user/en/query/ordering/examples/natural/attribute-natural-localized.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

The sorting mechanism of evitaDB is somewhat different from what you might be used to. If you sort entities by two
attributes in an `orderBy` clause of the query, evitaDB sorts them first by the first attribute (if present) and then
by the second (but only those where the first attribute is missing). If two entities have the same value of the first
attribute, they are not sorted by the second attribute, but by the primary key (in ascending order).

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[List of products sorted by multiple attributes](/documentation/user/en/query/ordering/examples/natural/attribute-natural-multiple.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List of products sorted by multiple attributes
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[List of products sorted by multiple attributes](/documentation/user/en/query/ordering/examples/natural/attribute-natural-multiple.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[List of products sorted by multiple attributes](/documentation/user/en/query/ordering/examples/natural/attribute-natural-multiple.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[List of products sorted by multiple attributes](/documentation/user/en/query/ordering/examples/natural/attribute-natural-multiple.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

If we want to use fast "pre-sorted" indexes, there is no other way to do it, because the secondary order would not be 
known until a query time. If you want to sort by multiple attributes in the conventional way, you need to define the
[sortable attribute compound](../../use/schema.md#sortable-attribute-compounds) in advance and use its name instead of
the default attribute name. The sortable attribute compound will cover multiple attributes and prepares a special
sort index for this particular combination of attributes, respecting the predefined order and NULL values behaviour.
In the query, you can then use the compound name instead of the default attribute name and achieve the expected results.

## Primary key natural

```evitaql-syntax
primaryKeyNatural(   
    argument:enum(ASC|DESC)
)
```

<dl>
    <dt>argument:enum(ASC|DESC)</dt>
    <dd>
        the ordering direction (ascending or descending), **default value** is `ASC`
    </dd>
</dl>

If no ordering constraint is specified in the query, the entities are sorted by their primary key in ascending order. 
If you want to sort them in descending order, you can use the `primaryKeyNatural` constraint with the `DESC` argument.
Although the constraint also accepts the `ASC` argument, it doesn't make sense to use it because this is the default
ordering behavior.

To sort products by their primary key in descending order, we can use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[List of products sorted by primary key in descending order](/documentation/user/en/query/ordering/examples/natural/primary-key-natural.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### List of products sorted by primary key in descending order
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[List of products sorted by primary key in descending order](/documentation/user/en/query/ordering/examples/natural/primary-key-natural.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[List of products sorted by primary key in descending order](/documentation/user/en/query/ordering/examples/natural/primary-key-natural.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[List of products sorted by primary key in descending order](/documentation/user/en/query/ordering/examples/natural/primary-key-natural.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>
