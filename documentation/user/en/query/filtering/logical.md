---
title: Logical filtering
perex: |
  Logical expressions are the cornerstone of any query language and evitaDB is no different. They allow you to combine 
  multiple filter expressions into one unambiguous expression.
date: '26.5.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

<Note type="warning">

<NoteTitle toggles="false">

##### What if the logical binding is not set explicitly?
</NoteTitle>

To make the query language more concise, we omit logical binding in container-type filtering constraints and assume 
a logical conjunctive relation "and at the same time" ([`and`](#and)) unless an explicit binding is set.
For example, you can issue the following query:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Implicit binding example](/documentation/user/en/query/filtering/examples/logical/implicit-binding.evitaql)
</SourceCodeTabs>

As you can see - there is no logical binding between `entityPrimaryKeyInSet` and `attributeEquals` constraints, and for 
this case the logical conjunction will be applied, which will result in a single returned product with *code* 
*lenovo-thinkpad-t495-2* in the response.

</Note>

## And

```evitaql-syntax
and(
    filterConstraint:any+
)
```

<dl> 
    <dt>filterConstraint:any+</dt>
    <dd>
        one or more mandatory filter constraints that will produce logical conjunction
    </dd>
</dl>

The <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/And.java</SourceClass> container represents 
a [logical conjunction](https://en.wikipedia.org/wiki/Logical_conjunction), that is demonstrated on following table:

|   A   |   B   | A ∧ B |
|:-----:|:-----:|:-----:|
|  True |  True |  True |
|  True | False | False |
| False |  True | False |
| False | False | False |

The following query:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Logical conjunction example](/documentation/user/en/query/filtering/examples/logical/and.evitaql)
</SourceCodeTabs>

... returns a single result - product with entity primary key *106742*, which is the only one that all three 
`entityPrimaryKeyInSet` constraints have in common.

<Note type="info">

<NoteTitle toggles="true">

##### List of all products matching conjunction filter
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[Logical conjunction example result](/documentation/user/en/query/filtering/examples/logical/and.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Logical conjunction example result](/documentation/user/en/query/filtering/examples/logical/and.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Logical conjunction example result](/documentation/user/en/query/filtering/examples/logical/and.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Or

```evitaql-syntax
or(
    filterConstraint:any+
)
```

<dl> 
    <dt>filterConstraint:any+</dt>
    <dd>
        one or more mandatory filter constraints that will produce logical disjunction
    </dd>
</dl>

The <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/Or.java</SourceClass> container represents 
a [logical disjunction](https://en.wikipedia.org/wiki/Logical_disjunction), that is demonstrated on following table:

|   A   |   B   | A ∨ B |
|:-----:|:-----:|:-----:|
|  True |  True | True  |
|  True | False | True  |
| False |  True | True  |
| False | False | False |

The following query:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Logical disjunction example](/documentation/user/en/query/filtering/examples/logical/or.evitaql)
</SourceCodeTabs>

... returns four results representing a combination of all primary keys used in the `entityPrimaryKeyInSet` constraints.

<Note type="info">

<NoteTitle toggles="true">

##### List of all products matching disjunction filter
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[Logical disjunction example result](/documentation/user/en/query/filtering/examples/logical/or.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Logical disjunction example result](/documentation/user/en/query/filtering/examples/logical/or.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Logical disjunction example result](/documentation/user/en/query/filtering/examples/logical/or.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Not

```evitaql-syntax
not(
    filterConstraint:any!
)
```

<dl> 
    <dt>filterConstraint:any!</dt>
    <dd>
        one or more mandatory filter constraints that will be subtracted from the superset of all entities
    </dd>
</dl>

The <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/Not.java</SourceClass> container represents 
a [logical negation](https://en.wikipedia.org/wiki/Negation), that is demonstrated on following table:

|   A   |  ¬ A  |
|:-----:|:-----:|
|  True | False |
| False | True  |

The following query:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Logical negation example](/documentation/user/en/query/filtering/examples/logical/not.evitaql)
</SourceCodeTabs>

... returns thousands of results excluding the entities with primary keys mentioned in `entityPrimaryKeyInSet` constraint.

<Note type="info">

<NoteTitle toggles="true">

##### List of all products matching negation filter
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[Logical negation example result](/documentation/user/en/query/filtering/examples/logical/not.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Logical negation example result](/documentation/user/en/query/filtering/examples/logical/not.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Logical negation example result](/documentation/user/en/query/filtering/examples/logical/not.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>

Because this situation is hard to visualize - let's narrow our super set to only a few entities:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Logical conjunction example](/documentation/user/en/query/filtering/examples/logical/not-narrowed.evitaql)
</SourceCodeTabs>

... which returns only three products that were not excluded by the following `not` constraint.

<Note type="info">

<NoteTitle toggles="true">

##### List of all products matching negation filter (narrowed)
</NoteTitle>

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude>[Logical negation example result (narrowed)](/documentation/user/en/query/filtering/examples/logical/not-narrowed.evitaql.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="graphql">

<MDInclude>[Logical negation example result (narrowed)](/documentation/user/en/query/filtering/examples/logical/not-narrowed.graphql.json.md)</MDInclude>

</LanguageSpecific>

<LanguageSpecific to="rest">

<MDInclude>[Logical negation example result (narrowed)](/documentation/user/en/query/filtering/examples/logical/not-narrowed.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>