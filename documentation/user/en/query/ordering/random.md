---
title: Random ordering
date: '25.6.2023'
perex: |
  Random ordering is useful in situations where you want to present the end user with the unique entity listing every
  time he/she accesses it.
author: 'Ing. Jan Novotný'
proofreading: 'needed'
preferredLang: 'evitaql'
---

## Random

```evitaql-syntax
random()
```

The constraint makes the order of the entities in the result random and does not take any arguments.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entities sorted randomly](/documentation/user/en/query/ordering/examples/random/random.evitaql)
</SourceCodeTabs>

The sample query always returns a different page of products.

<Note type="info">

<NoteTitle toggles="true">

##### List of randomized products
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[List of randomized products](/documentation/user/en/query/ordering/examples/random/randomized.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[List of randomized products](/documentation/user/en/query/ordering/examples/random/randomized.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[List of randomized products](/documentation/user/en/query/ordering/examples/random/randomized.rest.json.md)</MDInclude>

</LS>

</Note>

## Random with seed

```evitaql-syntax
randomWithSeed(
    argument:long!
)
```

<dl>
    <dt>argument:long!</dt>
    <dd>
        defines the seed for the random number generator, providing the same seed always produces the same order 
        of entities in the result 
    </dd>
</dl>

The constraint makes the order of the entities in the result pseudo-random based on the seed provided. The seed is 
a number that determines the order of the entities. The same seed will always produce the same order of entities.

This variant of random ordering is useful when you need to make the output random, but always the same way (e.g. for 
testing purposes, or for consistent output for a given user).

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entities sorted pseudo randomly](/documentation/user/en/query/ordering/examples/random/pseudo-random.evitaql)
</SourceCodeTabs>

The sample query always returns a same page of products, which seems to be random, but it is always the same.

<Note type="info">

<NoteTitle toggles="true">

##### List of pseudo-randomized products using seed
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[List of pseudo-randomized products using seed](/documentation/user/en/query/ordering/examples/random/pseudo-randomized.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[List of pseudo-randomized products using seed](/documentation/user/en/query/ordering/examples/random/pseudo-randomized.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[List of pseudo-randomized products using seed](/documentation/user/en/query/ordering/examples/random/pseudo-randomized.rest.json.md)</MDInclude>

</LS>

</Note>
