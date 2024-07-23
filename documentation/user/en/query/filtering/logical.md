---
title: Logical filtering
perex: |
  Logical expressions are the cornerstone of any query language and evitaDB is no different. They allow you to combine
  multiple filter expressions into one unambiguous expression.
date: '26.5.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
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

The <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/And.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/And.cs</SourceClass></LS> container represents
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

<LS to="e,j,c">

<MDInclude>[Logical conjunction example result](/documentation/user/en/query/filtering/examples/logical/and.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Logical conjunction example result](/documentation/user/en/query/filtering/examples/logical/and.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Logical conjunction example result](/documentation/user/en/query/filtering/examples/logical/and.rest.json.md)</MDInclude>

</LS>

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

The <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/Or.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/Or.cs</SourceClass></LS> container represents
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

<LS to="e,j,c">

<MDInclude>[Logical disjunction example result](/documentation/user/en/query/filtering/examples/logical/or.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Logical disjunction example result](/documentation/user/en/query/filtering/examples/logical/or.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Logical disjunction example result](/documentation/user/en/query/filtering/examples/logical/or.rest.json.md)</MDInclude>

</LS>

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

The <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/Not.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/Not.cs</SourceClass></LS> container represents
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

<LS to="e,j,c">

<MDInclude>[Logical negation example result](/documentation/user/en/query/filtering/examples/logical/not.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Logical negation example result](/documentation/user/en/query/filtering/examples/logical/not.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Logical negation example result](/documentation/user/en/query/filtering/examples/logical/not.rest.json.md)</MDInclude>

</LS>

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

<LS to="e,j,c">

<MDInclude>[Logical negation example result (narrowed)](/documentation/user/en/query/filtering/examples/logical/not-narrowed.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Logical negation example result (narrowed)](/documentation/user/en/query/filtering/examples/logical/not-narrowed.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Logical negation example result (narrowed)](/documentation/user/en/query/filtering/examples/logical/not-narrowed.rest.json.md)</MDInclude>

</LS>

</Note>