---
title: Logical filtering
perex: |
  Logical expressions are the cornerstone of any query language and evitaDB is no different. They allow you to combine 
  multiple filter expressions into one unambiguous expression.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

<Note type="warning">

<NoteTitle toggles="false">

##### What if the logical binding is not set explicitly?
</NoteTitle>

To make the query language more concise, we omit logical binding in container-type filtering constraints and assume 
a logical conjunctive relation "and at the same time" ([`and`](#and)) unless an explicit binding is set.
For example, you can issue the following query:

```evitaql
query(
    collection('Product'),
    filterBy(
        entityPrimaryKeyInSet(110066, 106742),
        attributeEquals('code', 'lenovo-thinkpad-t495-2')
    )
)
```

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

The `and` container represents a [logical conjunction](https://en.wikipedia.org/wiki/Logical_conjunction), that is
demonstrated on following table:

|   A   |   B   | A ∧ B |
|:-----:|:-----:|:-----:|
|  True |  True |  True |
|  True | False | False |
| False |  True | False |
| False | False | False |

The following query:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">
[Logical conjunction example](/docs/user/en/query/filtering/examples/logical/and.evitaql)
</SourceCodeTabs>

... returns a single result - product with entity primary key *106742*, which is the only one that all three 
`entityPrimaryKeyInSet` constraints have in common.

<Note type="info">

<NoteTitle toggles="true">

##### List of all products matching conjunction filter
</NoteTitle>

<MDInclude>[Logical conjunction example result](/docs/user/en/query/filtering/examples/logical/and.evitaql.md)</MDInclude>

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

The `or` container represents a [logical disjunction](https://en.wikipedia.org/wiki/Logical_disjunction), that is
demonstrated on following table:

|   A   |   B   | A ∨ B |
|:-----:|:-----:|:-----:|
|  True |  True | True  |
|  True | False | True  |
| False |  True | True  |
| False | False | False |

The following query:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">
[Logical disjunction example](/docs/user/en/query/filtering/examples/logical/or.evitaql)
</SourceCodeTabs>

... returns four results representing a combination of all primary keys used in the `entityPrimaryKeyInSet` constraints.

<Note type="info">

<NoteTitle toggles="true">

##### List of all products matching disjunction filter
</NoteTitle>

<MDInclude>[Logical disjunction example result](/docs/user/en/query/filtering/examples/logical/or.evitaql.md)</MDInclude>

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

The `not` container represents a [logical negation](https://en.wikipedia.org/wiki/Negation), that is
demonstrated on following table:

|   A   |  ¬ A  |
|:-----:|:-----:|
|  True | False |
| False | True  |

The following query:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">
[Logical negation example](/docs/user/en/query/filtering/examples/logical/not.evitaql)
</SourceCodeTabs>

... returns thousands of results excluding the entities with primary keys menntioned in `entityPrimaryKeyInSet` constraint.

<Note type="info">

<NoteTitle toggles="true">

##### List of all products matching negation filter
</NoteTitle>

<MDInclude>[Logical negation example result](/docs/user/en/query/filtering/examples/logical/not.evitaql.md)</MDInclude>

</Note>

Because this situation is hard to visualize - let's narrow our super set to only a few entities:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">
[Logical conjunction example](/docs/user/en/query/filtering/examples/logical/not-narrowed.evitaql)
</SourceCodeTabs>

... which returns only three products that were not excluded by the following `not` constraint.

<Note type="info">

<NoteTitle toggles="true">

##### List of all products matching negation filter (narrowed)
</NoteTitle>

<MDInclude>[Logical negation example result (narrowed)](/docs/user/en/query/filtering/examples/logical/not-narrowed.evitaql.md)</MDInclude>

</Note>