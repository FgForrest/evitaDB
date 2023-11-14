---
title: Locale filtering
perex: |
  Numerous e-commerce applications function in various regions and rely on localized data. While product labels and 
  descriptions are clear examples, there are also several numeric values that must be specific to each locale due to 
  the distinction between the metric system and imperial units. That's why evitaDB offers first-class support for 
  localization in its data structures and query language.
date: '27.5.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

## Entity locale equals

```evitaql-syntax
entityLocaleEquals(
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        a mandatory specification of the [locale](https://en.wikipedia.org/wiki/IETF_language_tag) to which all 
        localized attributes targeted by the query must conform; examples of a valid language tags are: `en-US` or 
        `en-GB`, `cs` or `cs-CZ`, `de` or `de-AT`, `de-CH`, `fr` or `fr-CA` etc.
    </dd>
</dl>

<LanguageSpecific to="java">

If you are working with evitaDB in Java, you can use [`Locale`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html) 
instead of the language tag. This is a natural way to work with locale specific data on the platform.

</LanguageSpecific>

<LanguageSpecific to="csharp">

If you are working with evitaDB in C#, you can use [`CultureInfo`](https://learn.microsoft.com/en-us/dotnet/api/system.globalization.cultureinfo)
instead of the language tag. This is a natural way to work with locale specific data on the platform.

</LanguageSpecific>

<Note type="question">

<NoteTitle toggles="true">

##### What is a language tag?
</NoteTitle>

The language tag, also known as the locale or language identifier, is a standardized format used to represent a specific
language or locale in computer systems and software. It provides a way to identify and differentiate languages, 
dialects, and regional variations.

The most commonly used format for language tags is the [BCP 47](https://www.rfc-editor.org/info/bcp47) (IETF Best 
Current Practice 47) standard. BCP 47 defines a syntax and set of rules for constructing language tags.

A language tag is typically constructed using a combination of subtags that represent various components. Here's an 
example breakdown of a language tag: `en-US`.

1. **Primary Language Subtag:** In the example above, *en* represents the primary language subtag, which indicates 
   English as the primary language.

2. **Region Subtag:** The region subtag is optional and represents a specific region or country associated with 
   the language. In the example, *US* represents the United States.

Language tags can also include additional subtags to specify variations such as script, variant, and extensions, 
allowing for more granular language identification.

<LanguageSpecific to="graphql">

In the GraphQL API, for ease of use, we convert all locales defined in stored data into an enum for better code completion.
However, the GraphQL doesn't support hyphens in enum items, and thus we use underscores instead. Otherwise, the syntax
is the same.

</LanguageSpecific>

</Note>

If any filter constraint of the query targets a localized attribute, the `entityLocaleEquals` must also be provided,
otherwise the query interpreter will return an error. Localized attributes **must** be identified by both their name 
and language tag in order to be used.

<Note type="warning">

Only a single occurrence of `entityLocaleEquals` is allowed in the filter part of the query. Currently, there is no way 
to switch context between different parts of the filter and build queries such as *find a product whose name in `en-US` 
is "screwdriver" or in `cs` is "šroubovák"*.

Also, it's not possible to omit the language specification for a localized attribute and ask questions like: *find 
a product whose name in any language is "screwdriver"*.

While it's technically possible to implement support for these tasks in evitaDB, they represent edge cases, and there 
were more important scenarios to handle.

</Note>

To test the locale specific query, we need to focus on the *Vouchers for shareholders* category in our 
[demo dataset](/documentation/get-started/query-our-dataset). We know that there are products that have only English 
(*en_US*) localization. To select the products with English localization, we can issue this query:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Listing product with English locale](/documentation/user/en/query/filtering/examples/locale/locale.evitaql)

</SourceCodeTabs>

... and we will get a list with the number of them.

<Note type="info">

<NoteTitle toggles="false">

##### List of all products with English localization in category
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[List of all products with English localization](/documentation/user/en/query/filtering/examples/locale/locale.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[List of all products with English localization](/documentation/user/en/query/filtering/examples/locale/locale.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[List of all products with English localization](/documentation/user/en/query/filtering/examples/locale/locale.rest.json.md)</MDInclude>

</LanguageSpecific>

You will notice that the output contains two columns: *code* and *name*. The *code* is not a localized attribute, while 
the *name* is. The names listed in the response reflect the English locale that is part of the filter constraint. 

If you use `entityLocaleEquals` in your filter, all returned localized data (both 
[attributes](../../use/data-model.md#localized-attributes) and [associated data](../../use/data-model.md#localized-associated-data)) 
will respect the filtered locale. If you need data for locales other than the one used in the filter constraint, 
you can use the require constraint [`data-in-locale`](../requirements/fetching.md#data-in-locale).

</Note>

But when we request products in Czech locale:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Listing product with English locale](/documentation/user/en/query/filtering/examples/locale/locale_missing.evitaql)
</SourceCodeTabs>

... the query returns none of them, even though we know there are products in this category.

<Note type="info">

<NoteTitle toggles="true">

##### List of all products with Czech localization in category
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[List of all products with Czech localization](/documentation/user/en/query/filtering/examples/locale/locale_missing.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[List of all products with Czech localization](/documentation/user/en/query/filtering/examples/locale/locale_missing.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[List of all products with Czech localization](/documentation/user/en/query/filtering/examples/locale/locale_missing.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>