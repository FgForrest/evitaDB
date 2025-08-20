---
title: Java
perex: |
  The Java API is the native interface for communicating with evitaDB. It allows you to run evitaDB as an embedded
  database or to connect to a remote database server. It is designed to share common interfaces for both scenarios,
  allowing you to switch between embedded and remote without changing your code. This is particularly useful during
  development or unit testing, when you can use the embedded database and switch to the remote database in production.
date: '26.10.2023'
author: 'Ing. Jan Novotný'
preferredLang: 'java'
---

<LS to="e,c,g,r">
This chapter describes the Java driver for evitaDB and doesn't make sense for other languages. If you're interested
in the details of the Java driver implementation, please change your preferred language in the upper right corner.
</LS>
<LS to="j">
Starting evitaDB in embedded mode is described in detail in chapter [Run evitaDB](../../get-started/run-evitadb?lang=java).
Connecting to a remote database instance is described in chapter [Connect to a remote database](../../get-started/query-our-dataset?lang=java).
The same applies to [query API](../../use/api/query-data?lang=java) and [write API](../../use/api/write-data?lang=java).
So none of these topics will be covered here.

## Java remote client

In order to use a Java remote client you need only to add following dependency to your project:

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_java_driver</artifactId>
    <version>2025.6.0</version>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_java_driver:2025.6.0'
```
</CodeTabsBlock>
</CodeTabs>

Java remote client builds on top of the [gRPC API](./grpc.md). The <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
is thread safe and only single instance of it is expected to be used in the application. The client internally manages
a pool of gRPC connections to handle parallel communication with the server.

<Note type="info">
The client instance is created regardless of whether the server is available. In order to verify that the server can be
reached you need to call some method on it. The usual scenario would be [opening a new session](../../get-started/create-first-database?lang=java#open-session-to-catalog-and-insert-your-first-entity) to existing <Term location="/documentation/user/en/index.md">catalog</Term>.
</Note>

<Note type="warning">
The <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
keeps a pool of opened resources and should be terminated by a `close()` method when you stop using it.
</Note>

### Configuration

The minimal configuration of the client is done by providing the server address and port. The following example shows
how to create a client instance that connects to the server running on `localhost` on port `5555`:

```java
var evita = new EvitaClient(
	EvitaClientConfiguration.builder()
		.host("localhost")
		.port(5555)
		.build()
);
```

But there are more options that can be configured. Following table describes all available options that can be set in
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/EvitaClientConfiguration.java</SourceClass>
on the client side:

<dl>
    <dt>clientId</dt>
    <dd>
        <p>**Default: `gRPC client at hostname`**</p>
        <p>
          This property allows you to distinguish requests from this particular client from requests from other clients.
          This information can be used in logs or in the [troubleshooting](../../use/api/troubleshoot.md) process.
        </p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default: `localhost`**</p>
        <p>Identification of the server on which evitaDB is running. This can be either a host name or an IP address.</p>
    </dd>
    <dt>port</dt>
    <dd>
        <p>**Default: `5555`**</p>
        <p>Identification of the server port on which evitaDB is running.</p>
    </dd>
    <dt>systemApiPort</dt>
    <dd>
        <p>**Default: `5555`**</p>
        <p>Identification of the server port on which the evitaDB system API is running. The system API is used to
        automatically set up the client certificate for mTLS or to download the server's self-signed certificate.
        See [TLS Configuration and Principles](../../operate/tls.md). The system API is not required if the server uses
        a trusted certificate and mTLS is disabled, or the server / client's private/public key pair is distributed
        "manually" with the client.</p>
    </dd>
    <dt>useGeneratedCertificate</dt>
    <dd>
        <p>**Default: `true`**</p>
        <p>When set to `true`, the client automatically downloads the root certificate of the server CA from
        the `system` endpoint. When set to `false`, the client expects the root certificate to be provided manually
        via the `serverCertificatePath` property.</p>
    </dd>
    <dt>trustCertificate</dt>
    <dd>
        <p>**Default: `false`**</p>
        <p>When set to `true`, the certificate obtained from the `system` endpoint or manually through `serverCertificatePath`
        is automatically added to the local trust store. If set to `false` and an untrusted (self-signed) certificate is
        provided, it will not be trusted by the client and the connection to the server will fail. Using `true` for this
        setting in production is generally not recommended.</p>
    </dd>
    <dt>tlsEnabled</dt>
    <dd>
        <p>**Default: `true`**</p>
        <p>When set to `true`, the client will use TLS encryption for communication with the server. When set to `false`,
        the client will use HTTP/2 without TLS encryption. Corresponding setting must be set on the server side.</p>
    </dd>
    <dt>mtlsEnabled</dt>
    <dd>
        <p>**Default: `false`**</p>
        <p>When set to `true`, the client and server will use mutual TLS authentication. The client must correctly
        identify itself using a public/private key pair that is known and trusted by the server in order to establish
        a connection. See [TLS Configuration and Principles](../../operate/tls.md).</p>
    </dd>
    <dt>serverCertificatePath</dt>
    <dd>
        <p>**Default: `null`**</p>
        <p>A relative path to the server certificate. Has to be provided when `useGeneratedCertificate` and `trustCertificate`
        flag is disabled and server is using non-trusted certificate (for example self-signed one). If the `useGeneratedCertificate`
        flag is off, it is necessary to set a path to the manually provided certificate, otherwise the verification
        process will fail and the connection will not be established.</p>
    </dd>
    <dt>certificateFolderPath</dt>
    <dd>
        <p>**Default: `evita-client-certificates`**</p>
        <p>A relative path to the folder where the client certificate and private key will be located,
        or if already not present there, downloaded. In the latter, the default path in the temp folder will be used.</p>
    </dd>
    <dt>certificateFileName</dt>
    <dd>
        <p>**Default: `null`**</p>
        <p>The relative path from `certificateFolderPath` to the client certificate. Must be configured if mTLS is
        enabled and `useGeneratedCertificate` is set to `false`.</p>
    </dd>
    <dt>certificateKeyFileName</dt>
    <dd>
        <p>**Default: `null`**</p>
        <p>The relative path from `certificateFolderPath` to the client private key. Must be configured if mTLS is
        enabled and `useGeneratedCertificate` is set to `false`.</p>
    </dd>
    <dt>certificateKeyPassword</dt>
    <dd>
        <p>**Default: `null`**</p>
        <p>The password for the client's private key (if one is set). Must be configured if mTLS is enabled and
        `useGeneratedCertificate` is set to `false`.</p>
    </dd>
    <dt>trustStorePassword</dt>
    <dd>
        <p>**Default: `trustStorePassword`**</p>
        <p>The password for a trust store used to store server certificates. It is used when `trustCertificate` is set
        to `true`.</p>
    </dd>
    <dt>reflectionLookupBehaviour</dt>
    <dd>
        <p>**Default: `CACHE`**</p>
        <p>The behaviour of <SourceClass>evita_common/src/main/java/io/evitadb/utils/ReflectionLookup.java</SourceClass>
        class analyzing classes for reflective information. Controls whether the once analyzed reflection information
        should be cached or freshly (and costly) retrieved each time asked.</p>
    </dd>
    <dt>timeout</dt>
    <dd>
        <p>**Default: `5`**</p>
        <p>Number of `timeoutUnit` time units the client should wait for server response before throwing an exception
        or closing connection forcefully.</p>
    </dd>
    <dt>timeoutUnit</dt>
    <dd>
        <p>**Default: `TimeUnit.SECONDS`**</p>
        <p>Time unit for `timeout` property.</p>
    </dd>
    <dt>openTelemetryInstance</dt>
    <dd>
        <p>**Default: `null`**</p>
        <p>OpenTelemetry instance that should be used for tracing. If set to `null`, no tracing will be performed.</p>
    </dd>
    <dt>retry</dt>
    <dd>
        <p>**Default: `false`**</p>
        <p>Whether the client will retry the call in case of timeout or other network related problems.</p>
    </dd>
    <dt>trackedTaskLimit</dt>
    <dd> 
        <p>**Default: `100`**</p>
        <p>The maximum number of server tasks that can be tracked by the client. If the limit is reached, 
         the client will stop tracking the oldest tasks.</p>
    </dd>
</dl>

<Note type="warning">
If `mTLS` is enabled on the server side and `useGeneratedCertificate` is set to `false`, you must provide your
manually generated certificate in settings `certificateFileName` and `certificateKeyFileName`, otherwise the verification
process will fail and the connection will not be established.
</Note>

### Schema caching

Both catalog and entity schemas are used quite often - every retrieved entity has a reference to its schema. At the same
time, the schema is quite complex and doesn't change often. It is therefore beneficial to cache the schema on the client
and avoid fetching it from the server every time it is needed.

The cache is handled by the <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaEntitySchemaCache.java</SourceClass>
class which handles two schema access scenarios:

#### Accessing last schema versions

The client maintains the last known schema versions for each catalog. This cache is invalidated each time a schema is
changed by that particular client, the collection is renamed or deleted, or the client fetches an entity that uses
a schema version that is newer than the one cached as the last entity schema version.

#### Accessing specific schema versions

The client also maintains a cache of specific schema versions. Each time a client fetches an entity, the entity returned
from the server side carries information about the schema version it refers to. The client tries to find the schema of
that particular version in its cache, and if it is not found, it fetches it from the server and caches it. The cache is
invalidated once in a while (every minute) and the old schemas that have not been used for a long time (4 hours) are
removed.

<Note type="info">

The above intervals are not currently configurable because we believe they are optimal for most use cases. If you need
to change them, please contact us with your specific use case and we will consider adding the configuration option.

</Note>

## Custom contracts

The Java API contains only two forms of the data model interfaces:

1. <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityReferenceContract.java</SourceClass>
   which represents a lightweight form of the entity consisting only of its primary key and entity type
2. <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>
   which represents a partial or complete form of the entity with its data

Both are valid and easy-to-use data structures, but neither speaks the language of your business domain. Developers
generally prefer to work with their own domain objects, and we understand that. Their application would usually wrap
the evitaDB model classes into their domain objects, which would require tedious manual work.

To make this process easier, we have created a custom contract API that allows you to define your own domain objects
and map them to evitaDB entities. Model objects can be used to define entity schemas as well as to read and write
entities from and to the database. Custom contracts use [ByteBuddy](https://bytebuddy.net/#/) and [Proxycian](https://github.com/FgForrest/Proxycian)
library to create dynamic proxies of your domain objects. There is a small performance overhead associated with this,
but it is negligible compared to the time spent on communication with the database. The API is optional and can be used
in parallel to the standard API.

### Runtime requirements

The custom contracts API uses Java proxies under the hood which requires the [Proxycian](https://github.com/FgForrest/Proxycian)
library to be present on classpath at runtime. Because the API is optional, we didn't want to bloat the evitaDB
JAR with the Proxycian library. However, when developer wants to use the custom contracts API, the Proxycian library
needs to be added as dependency:

```xml
<dependency>
  <groupId>one.edee.oss</groupId>
  <artifactId>proxycian_bytebuddy</artifactId>
  <version>1.4.0</version>
</dependency>
```

and also, if the application uses [Java Modules](https://www.oracle.com/corporate/features/understanding-java-9-modules.html),
the `--add-modules` parameter needs to be used

```shell
--add-modules proxycian.bytebuddy
```

### Schema definition

The schema definition is done by annotating the domain object with the annotations from the <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation</SourceClass> package and is
described in detail in the [schema API chapter](../../use/api/schema-api.md#declarative-schema-definition).

### Entity fetching

Entity in the form of custom contract can be read from the database using dedicated methods on
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> interface:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/selective-imports.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,documentation/user/en/use/api/example/declarative-schema-definition.java" langSpecificTabOnly local>

[Fetching the entity using custom interface](/documentation/user/en/use/connectors/examples/custom-contract-reading.java)

</SourceCodeTabs>

<Note type="info">

The example works with the same product definition as the [example in the schema API chapter](../../use/api/schema-api.md#declarative-schema-definition)
<SourceClass>/documentation/user/en/use/api/example/declarative-model-example.java</SourceClass>.

</Note>

Read-only entity fetching is described in detail in the [read API chapter](../../use/api/query-data.md#custom-contracts).

### Entity writing

Entity in the form of custom contract can be written to the database using dedicated methods on
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> interface:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/selective-imports.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,documentation/user/en/use/api/example/declarative-schema-definition.java" langSpecificTabOnly local>

[Writing the entity using custom interface](/documentation/user/en/use/connectors/examples/custom-contract-writing.java)

</SourceCodeTabs>

Writing data using custom contracts is described in detail in the [write API chapter](../../use/api/write-data.md#custom-contracts).

### Data modeling recommendations

You can define a single interface for both reading and writing data in evitaDB. However, it is recommended to separate
the read and write interfaces and to use different instances of the data objects for these purposes. In other words,
to follow similar principles that evitaDB is based on and uses itself. Although this may seem more complex in
the beginning, it will pay off in the long run. The reasons behind this idea are:

1. the read instances remain immutable and can be safely shared between threads and cached in shared memory
2. the read interface is not polluted with methods that are not needed to read data, and stays clean and simple.

We call this the "sealed/open" principle, and it works like this:

#### 1. define a read only interface

You define an interface or class with final fields that are initialized in the constructor:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Sealed instance in custom interface](/documentation/user/en/use/connectors/examples/sealed-instance-example.java)

</SourceCodeTabs>

As you can see, the interface looks exactly like the [example in the Schema API chapter](../../use/api/schema-api.md#declarative-schema-definition)
with the only difference that this version extends the <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedInstance.java</SourceClass>
interface. The declaration signals that `<READ_INTERFACE>` is the `Product` interface and `<WRITE_INTERFACE>` is
the `ProductEditor` interface.

<Note type="info">

We expect that the read interface will be used both to read your data and to define the schema structure. It's good
practice to keep the schema definition and the data access interface in the same place.

</Note>

#### 2. define a write interface

Then you define a separate interface to modify the data:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/sealed-instance-example.java" langSpecificTabOnly local>

[Instance editor in custom interface](/documentation/user/en/use/connectors/examples/instance-editor-example.java)

</SourceCodeTabs>

Note that this interface extends the `Product` interface and adds methods for modifying the data. It also extends
the <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/InstanceEditor.java</SourceClass> interface
and specifies that the `<READ_INTERFACE>` is the `Product` interface.

#### 3. take advantage of the sealed/open principle

Now we can use the interfaces described above in the following way:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/custom-contract-writing.java" langSpecificTabOnly local>

[Opening the sealed interface](/documentation/user/en/use/connectors/examples/sealed-open-lifecycle-example.java)

</SourceCodeTabs>

The sealed/open principle is a bit more complex than the naive approach of using a single interface for both reading and
writing data, but it clearly separates the read and write scenarios, allowing you to maintain control over mutations and
their visibility in a multi-threaded environment.
</LS>
