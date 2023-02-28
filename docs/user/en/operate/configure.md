---
title: Configuration
perex:
date: '17.1.2023'
author: 'Ing. Jan Novotný'
published: false
---

**Work in progress**

This article will contain description of evitaDB server configuration options.

## TLS Configuration

The way the server will approach the certificate can be set in a section `certificate` in `evita-configuration.yml`. It
is possible to set these important things:

- **`api.certificate.generateAndUseSelfSigned`**: (`true` by default) when set to `true`, a self-signed Certificate
  Authority certificate and its private key are automatically generated on server startup and used to communicate with
  clients.
- **`api.certificate.folderPath`**: (the sub-folder `evita-server-certificates` in the working directory by default)
  it represents a path to a folder where the authority certificate and its private key are stored
- **`api.certificate`**: (optional) This section allows you to configure an externally supplied certificate. This section
  is only used if the `generateAndUseSelfSigned` is set to `false`. If `generateAndUseSelfSigned` is set to `false` and
  no custom certificate is configured, the server will not start and an exception will be thrown. The server doesn't
  provide an unsecured connection for security reasons.
    - **`api.certificate.custom.certificate`**: path to the public part of the certificate file
    - **`api.certificate.custom.privateKey`**: path to the private key of the certificate
    - **`api.certificate.custom.privateKeyPassword`**: password for the private key

There is a special `api.endpoints.system` endpoint that allows access over the unsecured HTTP protocol. Since it's the
only exposed endpoint on the unsecured http protocol, it must run on a separate port. The endpoint allows clients to
download the public part of the server certificate. See [default mTLS behaviour](#default-mtls-behaviour--not-secure-).