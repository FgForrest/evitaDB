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

That's quite a lot of work, so we provide better support for evitaDB server initialization using 
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
as `@ExtendWith(DbInstanceParameterResolver.class)`:

<SourceCodeTabs>
[Alternative test example](docs/user/en/use/api/example/test-with-empty-dataset-alternative.java)
</SourceCodeTabs>

This example class will create *an anonymous instance* of an empty embedded evitaDB server and destroy it immediately 
after the test is finished. If you want to name the evitaDB server instance and use it in multiple tests (and possibly 
with some initial data fixture), you need to define a new initialization function in your test and use two new 
annotations <SourceClass>evita_test_support/src/main/java/io/evitadb/test/annotation/DataSet.java</SourceClass>
and <SourceClass>evita_test_support/src/main/java/io/evitadb/test/annotation/UseDataSet.java</SourceClass>:

<SourceCodeTabs>
[Named and filled dataset test example](evita_functional_tests/src/test/java/io/evitadb/test/PrefilledDataSetTest.java)
</SourceCodeTabs>

As you can see in the example, the `setUpData` method declares that it will initialize a data set named 
`dataSetWithAFewData`, creates a new collection `Brand` with a single entity containing the attribute `name` with
the value `Siemens`. The set up method allows the
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
to automatically create a reference 
to an evitaDB session object that allows to talk to the evitaDB instance (see [reference](#test-annotations-reference)
for more options of the `@DataSet` annotation).

Additionally, there is a test method `shouldWriteTest` which declares that it uses the dataset with the name
`dataSetWithAFewData` by using the annotation `@UseDataSet` and lets the
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
automatically create a reference to another evitaDB session object which it uses to query and assert the results of 
the data in the database.

### Test web APIs

A similar approach is possible with the evitaDB Web APIs. When setting up your dataset, simply declare that you also 
want to initialize the web server and open the named set of web APIs:

<SourceCodeTabs>
[Web API test example](docs/user/en/use/api/example/test-with-prefilled-dataset-and-web-api.java)
</SourceCodeTabs>

The example test is identical to the previous example with the only significant difference - instead of communicating 
with the embedded evitaDB server, you use
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>,
which communicates with gRPC server via HTTP/2 protocol. The server opens a free port, generates self-signed
certificates, the 
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
creates a
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass> 
instance that is properly configured to communicate with this gRPC server, downloads the self-signed server certificate
and the generic client certificate to pass [mTLS verification](../../operate/tls.md#default-mtls-behaviour-not-secure),
and talk to the *embedded evitaDB* over the wire.

### Init shared data objects

### Test isolation

The data set support allows to run multiple isolated evitaDB instances in parallel - completely isolated one from 
another. This approach allows you to run integration tests in parallel. You can read more about this technique in
our blog post [about blazing fast integration testing](/blog/04-blazing-fast-integration-tests).

Each dataset targets a directory with randomized name in OS temporary folder. When the dataset provides web API, 
the opened ports are consulted with <SourceClass>evita_test_support/src/main/java/io/evitadb/test/PortManager.java</SourceClass>
that provides it with information about free ports that can be used for web APIs of the data set. When the dataset
is destroyed the ports are closed and returned back to 
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/PortManager.java</SourceClass>.

All datasets are switched to read-only mode after they are initially filled, so that the data from the test cannot be 
modified inadvertently and cause problems in different tests running simultaneously or reusing the same dataset 
afterwards. You can switch the dataset to read-write mode, but it's recommended to consider this fact and mark the 
dataset to be destroyed after the test or test class. All evita session objects that are created and injected into 
the arguments of the test method are created as read-write sessions with 
[dry-run flag enabled](write-data.md#dry-run-session), which means that they never affect the data in the dataset 
outside the scope of this very session. This pattern is known as the
[transaction rollback teardown pattern](http://xunitpatterns.com/Transaction%20Rollback%20Teardown.html), and it has 
been used successfully for a long time in 
[Spring Framework Tests](https://relentlesscoding.com/posts/automatic-rollback-of-transactions-in-spring-tests/).

### Annotations reference

#### @DataSet

<dl>
    <dt>`value`</dt>
    <dd>Defines name of the dataset.</dd>
    <dt>`catalogName`</dt>
    <dd>
        <p>**Default:** `testCatalog`</p>
        <p>Defines the catalog name for the initial catalog created in a new evitaDB instance.</p>
    </dd>
    <dt>`expectedCatalogState`</dt>
    <dd>
        <p>**Default:** `ALIVE`</p>
        <p>Defines the state of the initial catalog. By default, when the initial dataset is set up, the catalog is 
        switched to transactional mode so that multiple sessions can be opened in this catalog (multiple tests can 
        access the contents of this catalog).</p>
    </dd>
    <dt>`openWebApi`</dt>
    <dd>
        <p>**Default:** `none`</p>
        <p>Specifies a set of web APIs to open for this dataset. Valid values are:</p>
        <ul>
            <li>`gRPC` (`GrpcProvider.CODE`) - for gRPC web API (the system API must be opened as well)</li>
            <li>`system` (`SystemProvider.CODE`) - system API that provides access to the certificates</li>
            <li>`rest` (`RestProvider.CODE`) - for REST web API</li>
            <li>`graphQL` (`GraphQLProvider.CODE`) - for GraphQL web API</li>
        </ul>
    </dd>
    <dt>`readOnly`</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>Marks the record as read-only after the initialization method completes. This is a security lock. If you need
        to write to a dataset from the unit test methods, you probably don't want to share it with other tests, or only 
        a controlled subset of them.</p>
	    <p>If you disable the readOnly security lock, you should probably set the `destroyAfterClass` or 
        `destroyAfterTest` attributes to `true`.</p>
	    <p>That's why `readOnly` is set to true by default, we want you to think about it before you turn off this 
        safety lock.</p>
    </dd>
    <dt>`destroyAfterClass`</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>If set to true, the evitaDB server instance will be closed and deleted after all test methods of the set 
        where the `@DataSet` annotation is used have been executed.</p>
        <p>By default, the dataset remains active so that other test classes can reuse it. Please make sure that there
        are not multiple (different) versions of the dataset initialization methods that execute the dataset 
        differently. The recommended approach is to have an abstract class with a setup method implementation and 
        multiple tests that extend from it and use the same dataset.</p>
        <p>If you have different implementations of the record initialization method, there's no guarantee which version
        will be called first and which will be skipped (due to the existence of the initialized record).</p>
    </dd>
</dl>

#### @UseDataSet
#### @OnDataSetTearDown

[Dry-run session](write-data.md#dry-run-session)

</LanguageSpecific>

<LanguageSpecific to="graphql">
**Work in progress**
</LanguageSpecific>

<LanguageSpecific to="rest">
**Work in progress**
</LanguageSpecific>