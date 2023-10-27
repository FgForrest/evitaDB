---
title: Java
perex: |
  The Java API is the native interface for communicating with evitaDB. It allows you to run evitaDB as an embedded 
  database or to connect to a remote database server. It is designed to share common interfaces for both scenarios, 
  allowing you to switch between embedded and remote without changing your code. This is particularly useful during 
  development or unit testing, when you can use the embedded database and switch to the remote database in production. 
date: '26.10.2023'
author: 'Ing. Jan Novotný'
---

Starting evitaDB in embedded mode is described in detail in chapter [Run evitaDB](../../get-started/run-evitadb?lang=java).
Connecting to a remote database instance is described in chapter [Connect to a remote database](../../get-started/query-our-dataset?lang=java).
The same applies to [query API](../../use/api/query-data?lang=java) and [write API](../../use/api/write-data?lang=java).
So none of these topics will be covered here.

## Java remote client

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
how to create a client instance that connects to the server running on `localhost` on port `5556`:

```java
var evita = new EvitaClient(
	EvitaClientConfiguration.builder()
		.host("localhost")
		.port(5556)
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
        <p></p>
    </dd>
    <dt>host</dt>
    <dd></dd>
    <dt>port</dt>
    <dd></dd>
    <dt>systemApiPort</dt>
    <dd></dd>
    <dt>useGeneratedCertificate</dt>
    <dd></dd>
    <dt>trustCertificate</dt>
    <dd></dd>
    <dt>mtlsEnabled</dt>
    <dd></dd>
    <dt>rootCaCertificatePath</dt>
    <dd></dd>
    <dt>certificateFileName</dt>
    <dd></dd>
    <dt>certificateKeyFileName</dt>
    <dd></dd>
    <dt>certificateKeyPassword</dt>
    <dd></dd>
    <dt>certificateFolderPath</dt>
    <dd></dd>
    <dt>trustStorePassword</dt>
    <dd></dd>
    <dt>reflectionLookupBehaviour</dt>
    <dd>
        <p>**Default: `CACHE`**</p>
        <p>The behaviour of <SourceClass>evita_common/src/main/java/io/evitadb/utils/ReflectionLookup.java</SourceClass>
        class analyzing classes for reflective information. Controls whether the once analyzed reflection information 
        should be cached or freshly (and costly) retrieved each time asked.</p>
    </dd>
    <dt>waitForClose</dt>
    <dd>
        <p>**Default: `5`**</p>
        <p>Number of `waitForCloseUnit` client should wait for opened connection to terminate gracefully before killing 
        them by force.</p>
    </dd>
    <dt>waitForCloseUnit</dt>
    <dd>
        <p>**Default: `TimeUnit.SECONDS`**</p>
        <p>It specifies the time unit for `waitForClose` property.</p>
    </dd>
</dl>

- **`useGeneratedCertificate`**: (`true` by default) if set to `true`, the client downloads the root certificate of
  the server Certificate Authority from the `system` endpoint automatically
- **`trustCertificate`**: (`false` by default) when set to `true`, the certificate retrieved from the `system`
  endpoint or manually by `certificatePath` is automatically added to the local trust store.

  If set to `false` and an untrusted (self-signed) certificate is provided, it will not be trusted by the client and
  the connection to the server will fail. Using `true` for this setting on production is generally not recommended.
- **`certificateFolderPath`**: (the sub-folder `evita-client-certificates` in the working directory by default)
  it represents a path to a folder where the authority certificate is stored
- **`rootCaCertificatePath`**: (`null` by default) it is a relative path from `certificateFolderPath` to the root
  certificate of the server. If the `useGeneratedCertificate` flag is off, it is necessary to set a path to
  the manually provided certificate, otherwise the verification process will fail and the connection will not be
  established.
- **`certificatePath`**: (`null` by default) is a relative path from `certificateFolderPath` to the client certificate.
- **`certificateKeyPath`**: (`null` by default) is a relative path from `certificateFolderPath` to the client private key
- **`certificateKeyPassword`**: (`null` by default) is the password for the client's private key (if one is set)
- **`trustStorePassword`**: (`null` by default). If not set, the default password `trustStorePassword` is used.
  This is a password for a trust store used to store trusted certificates. It is used when `trustCertificate` is
  set to `true`.

<Note type="warning">
If `mTLS` is enabled on the server side and `useGeneratedCertificate` is set to `false`, you must provide your
manually generated certificate in settings `certificatePath` and `certificateKeyPath`, otherwise the verification 
process will fail and the connection will not be established.
</Note>

## Custom contracts

### Runtime requirements

The custom contracts API uses Java proxies under the hood which requires the [Proxycian](https://github.com/FgForrest/Proxycian) 
library to be present on classpath at runtime. Because the API is optional, we didn't want to bloat the evitaDB
JAR with the Proxycian library.
However, when developer wants to use the custom contracts API, the Proxycian library needs to be added as dependency
```xml
<dependency>
  <groupId>one.edee.oss</groupId>
  <artifactId>proxycian_bytebuddy</artifactId>
  <version>1.3.7</version>
</dependency>
```
and also, if the application uses [Java Modules](https://www.oracle.com/corporate/features/understanding-java-9-modules.html), 
the `--add-modules` parameter needs to be used
```shell
--add-modules proxycian.bytebuddy
```