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

## Test class with empty database

Example of a test that starts with an empty evitaDB instance:

<SourceCodeTabs>
[EvitaQL example](docs/user/en/use/api/example/test-with-empty-dataset-example.java)
</SourceCodeTabs>

## Test class with pre-populated dataset

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