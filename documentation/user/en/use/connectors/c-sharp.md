---
title: C#
perex: |
  Main goal behind the C# driver of evitaDB was to create as similar API as possible to the Java one for the sake of
  consistency and to make it easier for developers to switch between languages. However, there are some minor differences
  between the two languages, so the C# API is not 100% identical to the Java one. Many of mentioned differences are mostly
  semantics and language conventions.
date: '10.11.2023'
author: 'Ing. Tomáš Pozler'
preferredLang: 'cs'
---

<LS to="e,j,g,r">
This chapter describes the C# driver for evitaDB and doesn't make sense for other languages. If you're interested in
the details of the C# implementation, please change your preferred language in the upper right corner.
</LS>
<LS to="c">
This API unification was possible thanks to the common [gRPC](grpc.md) protocol and protobuf data format used by both clients.
It is built on top of the same interfaces (especially <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>
and <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>) for the client and the database itself,
where on the C# side, there has been only an adaptation without the need of these specific interfaces - classes were used instead.

**Supported versions of .NET**

Since it's a relatively new project which in the implementation uses many of the newer language features, the C# driver
will not be backward compatible with older versions of .NET that .NET 8.

**How to install**

As mentioned previously, to use this client is necessary to have at least .NET 8 installed.
To install the client, you can use the NuGet package manager or the dotnet CLI. For alternative ways of installation,
please refer to the [nuget repository](https://www.nuget.org/packages/EvitaDB.Client).

<CodeTabs>
<CodeTabsBlock>
```.NET CLI
dotnet add package EvitaDB.Client
```
</CodeTabsBlock>
<CodeTabsBlock>
```Package Manager
Install-Package EvitaDB.Client
```
</CodeTabsBlock>
</CodeTabs>

As you may notice, install commands lack the version specification. This is because the client is currently meant to be
always compatible with the latest version of the server in [master branch](https://github.com/FgForrest/evitaDB/tree/master)
which corresponds with latest evitaDB [docker image](https://hub.docker.com/r/evitadb/evitadb). This may change in the future.

*Suggestions to use*
- in the most cases, you should be specifying `using` keyword when initializing <SourceClass>EvitaDB.Client/EvitaClient.cs</SourceClass> and <SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass> to take advantage of their `IDisposable` implementation for automatic resource release
- when working with queries, you should statically use `IQueryConstrains` interface for more compact and readable queries

## Notes
The <SourceClass>EvitaDB.Client/EvitaClient.cs</SourceClass>
is thread safe and only single instance of it is expected to be used in the application.

<Note type="info">
The client instance is created regardless of whether the server is available. In order to verify that the server can be
reached you need to call some method on it. The usual scenario would be opening a new session
to existing <Term location="/documentation/user/en/index.md">catalog</Term>.
</Note>

<Note type="warning">
The <SourceClass>EvitaDB.Client/EvitaClient.cs</SourceClass>
keeps a pool of opened resources and should be terminated by a `Close()` method when you stop using it.
</Note>

### TLS configuration

The following settings must be configured in the
<SourceClass>EvitaDB.Client/Config/EvitaClientConfiguration.cs</SourceClass>
configuration on the client side:

- **`UseGeneratedCertificate`**: (`true` by default) if set to `true`, the client downloads the server certificate
  (`server.crt`) from the `system` endpoint automatically. When `MtlsEnabled` is `true` as well, it also downloads
  the generated client certificate and its private key.
- **`UsingTrustedRootCaCertificate`**: (`false` by default) when set to `false`, the client accepts only the server
  certificate that is identical to the `server.crt` file in the server's sub-folder of `CertificateFolderPath`. If
  `UseGeneratedCertificate` is `false`, you have to place that file there yourself, otherwise the verification
  process will fail and the connection will not be established.

  Set it to `true` when the server uses a certificate issued by a certificate authority your operating system
  trusts; the standard .NET certificate validation is used then.
- **`CertificateFolderPath`**: (the sub-folder `evita-client-certificates` in the system temporary folder by default)
  it represents a path to a folder where the certificates are stored. Each server gets its own sub-folder named
  after the server name the `system` endpoint reports.
- **`CertificateFileName`**: (`null` by default) is a relative path to the client certificate.
- **`CertificateKeyFileName`**: (`null` by default) is a relative path to the client private key
- **`CertificateKeyPassword`**: (`null` by default) is the password for the client's private key (if one is set)

<Note type="warning">
If `mTLS` is enabled on the server side and `UseGeneratedCertificate` is set to `false`, you must provide your
manually generated certificate in settings `CertificateFileName` and `CertificateKeyFileName`, otherwise
the verification process will fail and the connection will not be established.
</Note>

### Schema caching

Both catalog and entity schemas are used quite often - every retrieved entity has a reference to its schema. At the same
time, the schema is quite complex and doesn't change often. It is therefore beneficial to cache the schema on the client
and avoid fetching it from the server every time it is needed.

The cache is handled by the <SourceClass>EvitaDB.Client/EvitaEntitySchemaCache.cs</SourceClass>
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
</LS>