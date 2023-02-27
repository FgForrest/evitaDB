---
title: Setting up TLS
perex:
date: '17.1.2023'
author: 'Bc. Tomáš Pozler'
published: false
---

# Setting up TLS certificates

gRPC is based on the HTTP/2 protocol, which requires TLS. Because of this, all evitaDB external APIs require it to keep 
the security uniform.

The gRPC API, and thus EvitaClient, also offers the possibility of authentication via
[mutual TLS](https://en.wikipedia.org/wiki/Mutual_authentication), in which client and
server verify their identities with the help of a certificate exchange. The use of mTLS can be set in the configuration
file <SourceClass>docker/evita-configuration.yaml</SourceClass> in the section `api.endpoints.gRPC.mTLS`, 
where it is possible to allow and set the path to the list of certificate authorities. This list will be allowed to 
communicate with the gRPC API. If this setting is enabled, the client must provide its certificate when initiating 
communication with the gRPC API, which is verified by the server. In the configuration class 
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/EvitaClientConfiguration.java</SourceClass>
in EvitaClient it is possible to set the path to the certificate, the private key and optionally the key password using
a builder.

We recommend the use of mTLS because it prevents a large number of attacks and thus emphasizes the security of 
the communication.

Examples of attacks prevented:

- [on-path attack](https://www.wallarm.com/what/what-is-an-on-path-attacker)
- [man in the middle](https://en.wikipedia.org/wiki/Man-in-the-middle_attack)
- [spoofing](https://en.wikipedia.org/wiki/Spoofing_attack)
- [credential stuffing](https://en.wikipedia.org/wiki/Credential_stuffing)
- [brute force attack](https://en.wikipedia.org/wiki/Brute-force_attack)
- [phishing](https://www.cloudflare.com/learning/access-management/phishing-attack/)

## How to get a certificate?

You need a certificate to prove your authority, whether you are a server or a client.

It is possible to divide certificates into two groups according to the certificate signature:

- Certificates signed by a publicly trusted root certificate authority
- Certificates signed by a private certification authority

[Let's Encrypt] (https://letsencrypt.org) falls into the first category. It issues certificates to domains. All 
certificates in this category are automatically trusted.

In this guide, we will focus on the second group: self-signed certificates. When using mTLS, it is necessary for the
server to have access to a certificate authority that trusts it, and for clients that prove their identity with a
certificate issued by that authority to allow communication.

### Creating a certificate authority

To generate a certificate, we will use the [OpenSSL](https://www.openssl.org/) tool. It is pre-installed on many 
Linux distributions, as well as on newer versions of the MacOS system. On Windows operating systems, you will need 
to download and install the tool.

1. Execute following command:

    ```bash
    openssl req -x509 -sha256 -days 1825 \
            -newkey rsa:2048 -keyout rootCA.key \
            -out rootCA.crt
    ```

    - it generates a private key `domain.key` for the client and a request `domain.csr` to be signed by a certificate 
      authority (after confirmation you have to enter a password).
    - it generates a certificate `rootCA.crt` and a private key `rootCA.key` for the certification authority
      (after confirmation you have to enter a password).

    <Note type="warning">
    After running the commands in steps 1 and 2, you must enter a password - if you want an unencrypted certificate without 
    a password, specify the `-nodes' parameter in the command.
    </Note>

2. Now you need to create a text file called `domain.ext` with the following content (replace DNS with the name of your
   test domain):

    ```
    authorityKeyIdentifier=keyid,issuer
    basicConstraints=CA:FALSE
    subjectAltName = @alt_names
    [alt_names]
    DNS.1 = domain
    ```

3. Sign the domain.crt certificate with the `rootCA.crt` certificate authority using the following command

    ```bash
    openssl x509 -req -CA rootCA.crt -CAkey rootCA.key \
            -in domain.csr -out domain.crt -days 365 \
            -CAcreateserial -extfile domain.ext
    ```

This gave us a pair of certificates and a private key for the user, and a certificate and a private key for the CA.

Both the server and the client can be provided with:

- certificate in the format `.crt`, `.cer` or `.pem`
- private key in the format `.key` or `.pem`

## Server:

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
download the public part of the server certificate.

## Client:

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

<LanguageSpecific to="javascript">

<Note type="info">
If you need to connect to a server that provides a self-signed certificate from the Node.js application, you need to set 
the variable `NODE_TLS_REJECT_UNAUTHORIZED` to the value `0`. However, this setting will cause Node.JS to log a warning 
message:

```
Setting the NODE_TLS_REJECT_UNAUTHORIZED environment variable to '0' makes TLS connections and HTTPS requests insecure 
by disabling certificate verification.
```

</Note>

</LanguageSpecific>

<Note type="warning">
Using generated self-signed certificates is only appropriate for testing and development purposes and should never be 
used in a production environment!
</Note>

## Manual verification of certificates in use

After starting both server and client, fingerprints (unique identifier calculated from the certificate) of used
certificates are printed to the console on both sides. It is recommended to verify that the fingerprints are the same,
i.e. that the certificates are the same.
