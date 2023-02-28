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

TLS support is enabled by default and cannot be disabled. It's configured in the `certificate` subsection of the `api`.
It allows configuring these settings:

<Table>
  <Thead>
    <Tr>
      <Th>Setting</Th>
      <Th>Default</Th>
      <Th>Meaning</Th>
    </Tr>
  </Thead>
  <TBody>
    <Tr>
      <Td>generateAndUseSelfSigned</Td>
      <Td>TRUE</Td>
      <Td>
        When set to `true`, a self-signed <Term document="docs/user/en/operate/tls.md">certificate authority</Term> 
        <Term document="docs/user/en/operate/tls.md">certificate</Term> and its 
        <Term document="docs/user/en/operate/tls.md">private key</Term> are automatically generated on server startup 
        and used to communicate with clients.
      </Td>
    </Tr>
    <Tr>
      <Td>folderPath</Td>
      <Td>the sub-folder `evita-server-certificates` in the working directory by default</Td>
      <Td>
        It represents a path to a folder where the generated authority certificate and its private key are stored.
        This setting is used only when `generateAndUseSelfSigned` is set to `true`.
      </Td>
    </Tr>
    <Tr>
      <Td>custom</Td>
      <Td>(no value)</Td>
      <Td>
        This section allows you to configure an externally supplied <Term document="docs/user/en/operate/tls.md">certificate</Term>. 
        It is only used if the `generateAndUseSelfSigned` is set to `false`.

        The section requiers these nested settings: 

          - **`certificate`**: path to the public part of the certificate file (*.crt)
          - **`privateKey`**: path to the private key of the certificate (*.key)
          - **`privateKeyPassword`**: password for the private key

        <Note type="info">
          It is recommended to provide the private key password using command line argument (environment variable) 
          `api.certificate.custom.privateKeyPasssword` and store id in a CI server secrets vault.
        </Note>
      </Td>
    </Tr>
  </TBody>
</Table>

If no custom certificate is configured, the server will not start and an exception will be thrown. The server doesn't
provide an unsecured connection for security reasons.

There is a special `api.endpoints.system` endpoint that allows access over the unsecured HTTP protocol. Since it's the
only exposed endpoint on the unsecured http protocol, it must run on a separate port. The endpoint allows anyone to
download the public part of the server certificate.

It also allows downloading the default client private/public key pair if `api.certificate.generateAndUseSelfSigned` and 
`api.gRPC.mTLS` are both set to `true`. See [default unsecure mTLS behaviour](#default-mtls-behaviour--not-secure-) for 
more information.