---
title: C#
perex: 
date: '10.11.2023'
author: 'Ing. Tomáš Pozler'
---

**Work in progress**

This article will contain description of C#, mainly the ideas behind its design from the API consumer perspective
(not from the perspective of the C# API developer). It should also contain recommendations and hint how to use
API correctly.

Main goal behind the C# driver of evitaDB was to create as similar API as possible to the Java one for the sake of
consistency and to make it easier for developers to switch between languages. However, there are some minor differences
between the two languages, so the C# API is not 100% identical to the Java one. Many of mentioned differences are mostly 
semantics and language conventions.

This API unification was possible thanks to the common [gRPC](grpc.md) protocol and protobuf data format used by both clients.
It is built on top of the same interfaces (especially <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> 
and <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>) for the client and the database itself,
where on the C# side, there has been only an adaptation without the need of these specific interfaces - classes were used instead.

**Supported versions of .NET**
Since it's a relatively new project which in the implementation uses many of the newer language features, the C# driver
will not be backward compatible with older versions of .NET that .NET 7.

**How to install**
As mentioned previously, to use this client is necessary to have at least .NET 7 installed.
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
reached you need to call some method on it. The usual scenario would be [opening a new session](#open-session-to-catalog)
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

- **`UseGeneratedCertificate`**: (`true` by default) if set to `true`, the client downloads the root certificate of
  the server Certificate Authority from the `system` endpoint automatically
- **`TrustCertificate`**: (`false` by default) when set to `true`, the certificate retrieved from the `system`
  endpoint or manually by `CertificatePath` is automatically added to the local trust store.

  If set to `false` and an untrusted (self-signed) certificate is provided, it will not be trusted by the client and
  the connection to the server will fail. Using `true` for this setting on production is generally not recommended.
- **`CertificateFolderPath`**: (the sub-folder `evita-client-certificates` in the working directory by default)
  it represents a path to a folder where the authority certificate is stored
- **`RootCaCertificatePath`**: (`null` by default) it is a relative path from `CertificateFolderPath` to the root
  certificate of the server. If the `UseGeneratedCertificate` flag is off, it is necessary to set a path to
  the manually provided certificate, otherwise the verification process will fail and the connection will not be
  established.
- **`CertificatePath`**: (`null` by default) is a relative path from `CertificateFolderPath` to the client certificate.
- **`CertificateKeyPath`**: (`null` by default) is a relative path from `CertificateFolderPath` to the client private key
- **`CertificateKeyPassword`**: (`null` by default) is the password for the client's private key (if one is set)

<Note type="warning">
If `mTLS` is enabled on the server side and `UseGeneratedCertificate` is set to `false`, you must provide your
manually generated certificate in settings `CertificatePath` and `CertificateKeyPath`, otherwise the verification 
process will fail and the connection will not be established.
</Note>