---
title: Write tests
perex: |
    Everyone writes tests - even you. Are we right?! Writing tests should be a pleasure and that's why we try to provide 
    you with a support for you to write fast tests with evitaDB with ease. Your integration test shouldn't take minutes 
    but seconds.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
---

<LanguageSpecific to="evitaql">
The evitaQL language selection for this article makes no sense.
</LanguageSpecific>

<LanguageSpecific to="csharp">
Unfortunately, we don't have a support written for a C# client, yet. Want to
[contribute](https://github.com/FgForrest/evitaDB)?
</LanguageSpecific>

<LanguageSpecific to="java">

To take advantage of our support for application testing with evitaDB, you first need to import the `evita_test_support`
artifact into your project. artifact into your project:

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_test_support</artifactId>
    <version>0.5-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_test_support:0.5-SNAPSHOT'
```
</CodeTabsBlock>
</CodeTabs>

Our testing support requires and is written for the [JUnit 5](https://junit.org/junit5/docs/current/user-guide/) testing
framework.

## JUnit test class

Example of a test that starts with an empty evitaDB instance:

<SourceCodeTabs>
[JUnit5 test example](docs/user/en/use/api/example/test-with-empty-dataset-example.java)
</SourceCodeTabs>

That's quite a lot of work and therefore there is a better support for evitaDB server initialization using 
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
as `@ExtendWith(DbInstanceParameterResolver.class)`:

<SourceCodeTabs>
[Alternative test example](docs/user/en/use/api/example/test-with-empty-dataset-alternative.java)
</SourceCodeTabs>

[//]: # (TODO JNO - toto už je jinak)

As you can see the test defines an initialization method with `@DataSet` annotation, and one or more test methods
annotated with `@UseDataSet` annotation and works the exactly same way as the previous more complex example.
The `DbInstanceParameterResolver` performs all necessary operations instead of you:

1. when it encounters a test with `@UseDataSet`, it tries to locate instance of the evitaDB server named according 
   to annotation value
2. if no existing evitaDB server instance is found, it tries to find method annotated with `@DataSet` and if it's 
   successful it:
    - creates new empty evitaDB instance in a random system temporary directory
    - invokes the method annotated with `@DataSet` annotation to init the contents of the evitaDB instance
3. passes the evitaDB server instance as a parameter of the test method and the test is executed

### Test annotations reference

<dl>
    <dt>`@CatalogName`</dt>
    <dd></dd>
    <dt>`@DataSet`</dt>
    <dd></dd>
    <dt>`@OnDataSetTearDown`</dt>
    <dd></dd>
    <dt>`@UseDataSet`</dt>
    <dd></dd>
</dl>

## Test class using a client

### Random data generator

## Rollback on test teardown pattern

[Dry-run session](write-data.md#dry-run-session)

</LanguageSpecific>

<LanguageSpecific to="graphql">
**Work in progress**
</LanguageSpecific>

<LanguageSpecific to="rest">
**Work in progress**
</LanguageSpecific>