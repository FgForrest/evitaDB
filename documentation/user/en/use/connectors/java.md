---
title: Java
perex:
date: '17.1.2023'
author: 'Ing. Jan Novotný'
---

**Work in progress**

This article will contain description of Java, mainly the ideas behind its design from the API consumer perspective
(not from the perspective of the Java API developer). It should also contain recommendations and hint how to use
API correctly.

The chapter should not describe the API itself - since there is separate [chapter](../api/api.md) about it, but rather
describe the usage from the client point of view.

This chapter should contain description both for embedded and for remote evitaDB server.

****************************************************************

## Notes

The <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
is thread safe and only single instance of it is expected to be used in the application.

<Note type="info">
The client instance is created regardless of whether the server is available. In order to verify that the server can be
reached you need to call some method on it. The usual scenario would be [opening a new session](#open-session-to-catalog)
to existing <Term location="/documentation/user/en/index.md">catalog</Term>.
</Note>

<Note type="warning">
The <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
keeps a pool of opened resources and should be terminated by a `close()` method when you stop using it.  
</Note>

### TLS configuration

The following settings must be configured in the
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/EvitaClientConfiguration.java</SourceClass>
configuration on the client side:

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