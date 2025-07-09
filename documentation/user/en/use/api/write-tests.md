---
title: Write tests
perex: |
    Everyone writes tests - even you. Are we right?! Writing tests should be a pleasure and that's why we try to provide
    you with a support to write fast tests with evitaDB with ease. Your integration tests shouldn't take minutes
    but seconds.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'java'
---

<LS to="e">
The evitaQL language selection for this article makes no sense.
</LS>

<LS to="c">
Unfortunately, we don't have a support written for a C# client, yet. Want to
[contribute](https://github.com/FgForrest/evitaDB)?
</LS>

<LS to="j,g,r">

<UsedTerms>
    <h4>Terms used in this document</h4>
	<dl>
		<dt>autowiring</dt>
		<dd>
        Autowiring is the process of placing an instance of a bean into the specified argument of the method. This
        mechanism is known as the [dependency injection pattern](https://en.wikipedia.org/wiki/Dependency_injection)
        and allows the *framework* to resolve the correct instance for the argument of a given name and type, and
        automatically provide it to the application code. This process allows the application logic to be decoupled
        from the integration logic and greatly simplifies it.
        </dd>
	</dl>
</UsedTerms>

To take advantage of our support for application testing with evitaDB, you first need to import the `evita_test_support`
artifact into your project:

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_test_support</artifactId>
    <version>2025.5.1</version>
    <scope>test</scope>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_test_support:2025.5.1'
```
</CodeTabsBlock>
</CodeTabs>

Our testing support requires and is written for the [JUnit 5](https://junit.org/junit5/docs/current/user-guide/) test
framework.

## JUnit test class

Let's first take a look at what a commonly written automated test that uses data in evitaDB would look like:

<SourceCodeTabs local>
[JUnit5 test example](/documentation/user/en/use/api/example/test-with-empty-dataset-example.java)
</SourceCodeTabs>

That's quite a lot of work. Therefore, we provide better support for evitaDB server initialization using
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>,
which can be integrated into your test via JUnit 5 annotation `@ExtendWith(DbInstanceParameterResolver.class)`:

<SourceCodeTabs local>
[Alternative test example](/documentation/user/en/use/api/example/test-with-empty-dataset-alternative.java)
</SourceCodeTabs>

This example class will create *an anonymous instance* of an empty embedded evitaDB server and destroy it immediately
after the test has finished. If you want to name the evitaDB server instance and use it in multiple tests (and possibly
with some initial data fixture), you need to define a new initialization function in your test and use two new
annotations <SourceClass>evita_test_support/src/main/java/io/evitadb/test/annotation/DataSet.java</SourceClass>
and <SourceClass>evita_test_support/src/main/java/io/evitadb/test/annotation/UseDataSet.java</SourceClass>:

<SourceCodeTabs local>
[Named and filled dataset test example](/documentation/user/en/use/api/example/test-with-prefilled-dataset.java)
</SourceCodeTabs>

As you can see in the example, the `setUpData` method declares that it will initialize a data set named
`dataSetWithAFewData`, creates a new collection `Brand` with a single entity containing the attribute `name` with
the value `Siemens`. The <SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
automatically <Term name="autowiring">autowires</Term> a
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>
object that allows communication with the evitaDB instance (see [reference](#annotations-reference) for more options
of the `@DataSet` annotation).

In addition, there is a test method `exampleTestCaseWithAssertions`, which declares that it uses the dataset named
`dataSetWithAFewData` by using the `@UseDataSet` annotation. The same
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
automatically <Term name="autowiring">autowires</Term> a reference to another evitaDB session object which is used in
the test method implementation to query and assert the results of the data in the database.

### Test web APIs

</LS>

<LS to="j">

A similar approach is possible with the evitaDB Java Client through gRPC API. When setting up your dataset, simply declare that you also
want to initialize the gRPC web server and open required set of web APIs:

<SourceCodeTabs local>
[Web API test example](/documentation/user/en/use/api/example/test-with-prefilled-dataset-and-grpc-web-api.java)
</SourceCodeTabs>

The example is identical to the previous one with the only significant difference - instead of communicating
with the embedded evitaDB server via direct method calls, it uses
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>,
which communicates with the same embedded evitaDB server via gRPC protocol using HTTP/2 and local network. The server
opens a free port, generates self-signed <Term location="/documentation/user/en/operate/tls.md">certificate authority</Term>
certificate. The
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
creates a
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
instance that is properly configured to communicate with this gRPC API, the client downloads the self-signed
<Term location="/documentation/user/en/operate/tls.md">certificate authority</Term> certificate and the generic client
certificate to pass [mTLS verification](../../operate/tls.md#default-mtls-behaviour-not-secure), and
communicates with the *embedded evitaDB* over the wire.

</LS>

<LS to="g">

A similar approach is possible with the evitaDB GraphQL API. When setting up your dataset, simply declare that you also
want to initialize the GraphQL web server and open required web API:

<SourceCodeTabs local>
[Web API test example](/documentation/user/en/use/api/example/test-with-prefilled-dataset-and-graphql-web-api.java)
</SourceCodeTabs>

The example is similar to the previous one with the only significant difference - instead of communicating
with the embedded evitaDB server via direct method calls, it uses
exposed GraphQL API,
which communicates with the same embedded evitaDB server via GraphQL protocol using HTTP and local network. The server
opens a free port and generates self-signed <Term location="/documentation/user/en/operate/tls.md">certificate authority</Term>
certificate. The
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
creates a
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/GraphQLTester.java</SourceClass>
instance that is properly configured to communicate with this GraphQL API, and
communicates with the *embedded evitaDB* over the wire.
The <SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/GraphQLTester.java</SourceClass> is essentially
just a wrapper around the [REST-assured](https://rest-assured.io/) library to provide a pre-configured tester with request
builder specific to our GraphQL API. However, after the `.executeAndThen()` method is called, the request is sent,
and you can use assertion methods provided directly by the [REST-assured](https://github.com/rest-assured/rest-assured/wiki/Usage#verifying-response-data) library.

</LS>

<LS to="r">

A similar approach is possible with the evitaDB REST API. When setting up your dataset, simply declare that you also
want to initialize the REST web server and open required web API:

<SourceCodeTabs local>
[Web API test example](/documentation/user/en/use/api/example/test-with-prefilled-dataset-and-rest-web-api.java)
</SourceCodeTabs>

The example is similar to the previous one with the only significant difference - instead of communicating
with the embedded evitaDB server via direct method calls, it uses
exposed REST API,
which communicates with the same embedded evitaDB server via REST protocol using HTTP and local network. The server
opens a free port and generates self-signed <Term location="/documentation/user/en/operate/tls.md">certificate authority</Term>
certificate. The
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
creates a
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/RestTester.java</SourceClass>
instance that is properly configured to communicate with this REST API, and
communicates with the *embedded evitaDB* over the wire.
The <SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/RestTester.java</SourceClass> is essentially
just a wrapper around the [REST-assured](https://rest-assured.io/) library to provide a pre-configured tester with request
builder specific to our REST API. However, after the `.executeAndThen()` method is called, the request is sent,
and you can use assertion methods provided directly by the [REST-assured](https://github.com/rest-assured/rest-assured/wiki/Usage#verifying-response-data) library.

</LS>

<LS to="j,g,r">

### Init shared data objects

In the initialization method marked with the `@DataSet` annotation, you can create a set of additional objects that will
be associated with the test dataset of such name and will be available for <Term>autowiring</Term> in any of your
tests along with other evitaDB related objects. Let's have a look at the following example:

<SourceCodeTabs local>
[Company objects initialization](/documentation/user/en/use/api/example/test-company-objects.java)
</SourceCodeTabs>

In the initial method we return a special
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DataCarrier.java</SourceClass>. It is
a wrapper around a [Map](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Map.html) that carries
the shared object *name* and the *value*. All such objects that are returned in a
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DataCarrier.java</SourceClass> will be
available for <Term>autowiring</Term> in the input arguments of a test method. The arguments are <Term
name="autowiring">autowired</Term> primarily by their name (and matching type), secondarily by type compatibility.
If you only need to propagate a single shared object, it can be returned directly as the return value of the
initialization method without wrapping it in a
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DataCarrier.java</SourceClass>.

You can see that in the test method, we accept `SealedEntity brand` and `String expectedBrandName` arguments that
exactly match the named values provided in a data carrier of the initialization method.

### Test isolation

The data set support allows to run multiple isolated evitaDB instances at the same time - completely isolated one from
each other. This fact allows you to run your integration tests in parallel. You can read more about this technique in
our blog post [about blazing fast integration tests](/blog/04-blazing-fast-integration-tests).

Each dataset is stored in a directory with randomized name in the OS temporary folder. When the dataset opens a web API,
the opened ports are consulted with <SourceClass>evita_test_support/src/main/java/io/evitadb/test/PortManager.java</SourceClass>.
It provides the evitaDB web server with information about free ports that can be used for web APIs of the dataset.
When the dataset is destroyed the ports are closed and returned back to
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/PortManager.java</SourceClass>.

All datasets are switched to read-only mode after they are initially populated, so that the data cannot be
inadvertently modified in the test implementations and cause problems in different tests running at the same time,
or reusing the same dataset later.

You can switch the dataset to read-write mode, but it's recommended to be aware of this fact and mark the dataset to be
destroyed after finishing the test method or the whole test class. All evita session objects that are created and
<Term>autowired</Term> in the arguments of the test method are created as read-write sessions with
[dry-run flag enabled](write-data.md#dry-run-session). This means that they will never affect the data in the dataset
outside the scope of that particular session. This pattern is called the
[transaction rollback teardown pattern](http://xunitpatterns.com/Transaction%20Rollback%20Teardown.html),
and it has been used successfully for a long time in the
[Spring Framework Tests](https://relentlesscoding.com/posts/automatic-rollback-of-transactions-in-spring-tests/).

## Annotations reference

All methods annotated with the evitaDB test annotations can declare the following arguments, which will be
<Term>autowired</Term> by our test support:

<dl>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass></dt>
    <dd>A reference to active instance of the embedded evitaDB.</dd>
    <dt>`String` catalogName</dt>
    <dd>Name of the initial catalog inside the evitaDB instance.</dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></dt>
    <dd>
        A reference to an open evitaDB read-write session. The session is marked as
        [dry-run](write-data.md#dry-run-session) if it's used in a test method annotated with `@UseDataSet' annotation.
        Sessions <Term name="autowiring">autowired</Term> to init or teardown methods are purely read-write.
    </dd>
    <dt><SourceClass>evita_server/src/main/java/io/evitadb/server/EvitaServer.java</SourceClass></dt>
    <dd>A reference to active instance of the evitaDB "web" server.</dd>
</dl>

In methods annotated with `@UseDataSet` or `@OnDataSetTearDown` (other than the initialization method), you can also use
any of the [shared objects](#init-shared-data-objects) initialized and returned by a method annotated with the
`@DataSet` annotation.

### @DataSet

The annotation is expected to be placed on a non-test method that prepares a new evitaDB instance with a sample dataset
to be used in tests. It's analogous to the JUnit `@BeforeEach` method.

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
        access the contents of this catalog in parallel).</p>
    </dd>
    <dt>`openWebApi`</dt>
    <dd>
        <p>**Default:** `none`</p>
        <p>Specifies a set of web APIs to open for this dataset. Valid values are:</p>
        <ul>
            <li>`gRPC` (`GrpcProvider.CODE`) - for gRPC web API (requires the system API)</li>
            <li>`system` (`SystemProvider.CODE`) - system API that provides access to the certificates</li>
            <li>`rest` (`RestProvider.CODE`) - for REST web API</li>
            <li>`graphQL` (`GraphQLProvider.CODE`) - for GraphQL web API</li>
        </ul>
    </dd>
    <dt>`readOnly`</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>Marks the record as read-only after the initialization method completes. This is a safety lock. If you need
        to write to a dataset from the unit test methods, you probably don't want to share it with other tests, or only
        a controlled subset of them (usually in the same test class).</p>
	    <p>If you disable the readOnly safety lock, you should probably set the `destroyAfterClass` or
        `destroyAfterTest` attributes to `true`.</p>
	    <p>That's why `readOnly` is set to `true` by default, we want you to think about it before you turn off this
        safety lock.</p>
    </dd>
    <dt>`destroyAfterClass`</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>If set to true, the evitaDB server instance will be closed and deleted after all test methods of the test
        class in which the `@DataSet` annotation is declared have been executed.</p>
        <p>By default, the dataset remains active so that other test classes can reuse it. Please make sure that there
        are not multiple (different) versions of the dataset initialization methods that execute the dataset
        differently. The recommended approach is to have one abstract class with one setup method implementation and
        multiple tests that extend from it and use the same dataset.</p>
        <p>If you have different implementations of the record initialization method, there is no guarantee which
        version will be called first and which will be skipped (due to the existence of the initialized record).</p>
    </dd>
</dl>

### @UseDataSet

The annotation is expected to be placed on the `@Test` method or an argument of that method. It associates the argument
/ method with the dataset initialized by the [`@DataSet`](#dataset) method.

<dl>
    <dt>`value`</dt>
    <dd>Defines name of the dataset to be used.</dd>
    <dt>`destroyAfterTest`</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>If set to true, the evitaDB server instance will be closed and deleted after this test method has finished.</p>
        <p>It's better to share a single dataset between multiple test methods, but if you know that writing to it
        within the test method will significantly damage the dataset, it's better to discard it completely and let the
        system prepare a new one.</p>
    </dd>
</dl>

</LS>

<LS to="j">

Besides the standard [autowired arguments](#annotations-reference) you can also inject
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
to any of the defined arguments. The client will open a gRPC connection to the web API of
<SourceClass>evita_server/src/main/java/io/evitadb/server/EvitaServer.java</SourceClass> and you can start communicating
with the server over the network even if the server is running locally as an embedded database.

</LS>

<LS to="g">

Besides the standard [autowired arguments](#annotations-reference) you can also inject
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/GraphQLTester.java</SourceClass>
to any of the defined arguments. The tester will open a GraphQL connection to the web API of
<SourceClass>evita_server/src/main/java/io/evitadb/server/EvitaServer.java</SourceClass> and you can start communicating
with the server over the network even if the server is running locally as an embedded database.

</LS>

<LS to="r">

Besides the standard [autowired arguments](#annotations-reference) you can also inject
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/RestTester.java</SourceClass>
to any of the defined arguments. The tester will open a REST connection to the web API of
<SourceClass>evita_server/src/main/java/io/evitadb/server/EvitaServer.java</SourceClass> and you can start communicating
with the server over the network even if the server is running locally as an embedded database.

</LS>

<LS to="j,g,r">

### @OnDataSetTearDown

Annotation is expected to be placed on non-test method. The method is called by the framework just before the dataset
with the given name is closed and destroyed. It's analogous to the JUnit `@AfterEach` method. Usually you don't need to
do anything, since we take care of properly closing all things related to evitaDB. We also call the `close`
method on all [shared objects](#init-shared-data-objects) that implement Java's `AutoCloseable` interface. But sometimes
you'd need a special cleanup procedure for your shared objects, and you might appreciate this destroy callback support.

<dl>
    <dt>`value`</dt>
    <dd>Defines name of the associated dataset.</dd>
</dl>

</LS>
