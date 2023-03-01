---
title: Configuration
perex:
date: '1.3.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
published: false
---

The evitaDB server is configured in YAML format and its default settings are best described by the following code
snippet:

```yaml
server:                                           # [see Server configuration](#server-configuration)
  coreThreadCount: 4
  maxThreadCount: 16
  threadPriority: 5
  queueSize: 100
  closeSessionsAfterSecondsOfInactivity: 60

storage:                                          # [see Storage configuration](#storage-configuration)
  storageDirectory: null
  lockTimeoutSeconds: 50
  waitOnCloseSeconds: 50
  outputBufferSize: 4MB
  maxOpenedReadHandles: 12
  computeCRC32C: true

cache:                                            # [see Cache configuration](#cache-configuration)
  enabled: true
  reflection: CACHE
  reevaluateEachSeconds: 60
  anteroomRecordCount: 100k
  minimalComplexityThreshold: 10k
  minimalUsageThreshold: 2
  cacheSizeInBytes: null

api:                                              # [see API configuration](#api-configuration)
  ioThreads: 4
  certificate:                                    # [see TLS configuration](#tls-configuration) 
    generateAndUseSelfSigned: true
    folderPath: './evita-server-certificates/'
    custom:
      certificate: null
      privateKey: null
      privateKeyPassword: null
  endpoints:                                      
    graphQL:                                      # [see GraphQL API configuration](#graphql-api-configuration)
      enabled: true
      host: localhost:5555
    rest:                                         # [see REST API configuration](#rest-api-configuration)
      enabled: true
      host: localhost:5555
    gRPC:                                         # [see gRPC API configuration](#grpc-api-configuration)
      enabled: true
      host: localhost:5556
      mTLS:
        enabled: false
        allowedClientCertificatePaths: []
    system:                                       # [see System API configuration](#system-api-configuration)
      enabled: true
      host: localhost:5557
```

<Note type="info">

<NoteTitle toggles="true">

##### Where the default configuration bundled with Docker image is located?
</NoteTitle>

The default configuration file is located in the file <SourceClass>docker/evita-configuration.yaml</SourceClass>.
As you can see it contains variables that allow propagating arguments from the command line / environment variables
located at the server start-up. The format used in this file is:

```
${argument_name:defaultValue}
```
</Note>

## Server configuration

## Storage configuration

## Cache configuration

## API configuration

### TLS configuration

TLS support is enabled by default and cannot be disabled. It's configured in the `certificate` subsection of the `api`.
It allows configuring these settings:

<dl>
  <dt>generateAndUseSelfSigned</dt>
  <dd>
    <Note type="info">

    <NoteTitle toggles="false">
    
    ##### Default
    </NoteTitle>

    is set to `true`

    </Note>

    When set to `true`, a self-signed <Term document="docs/user/en/operate/tls.md">certificate authority</Term> 
    <Term document="docs/user/en/operate/tls.md">certificate</Term> and its 
    <Term document="docs/user/en/operate/tls.md">private key</Term> are automatically generated on server startup 
    and used to communicate with clients.
  </dd>
  <dt>folderPath</dt>
  <dd>
    <Note type="info">

    <NoteTitle toggles="false">
    
    ##### Default
    </NoteTitle>

    is the sub-folder `evita-server-certificates` in the working directory

    </Note>

    It represents a path to a folder where the generated authority certificate and its private key are stored.
    This setting is used only when `generateAndUseSelfSigned` is set to `true`.
  </dd>
  <dt>custom</dt>
  <dd>
    This section allows you to configure an externally supplied <Term document="docs/user/en/operate/tls.md">certificate</Term>. 
    It is only used if the `generateAndUseSelfSigned` is set to `false`.

    The section requiers these nested settings: 

      - **`certificate`**: path to the public part of the certificate file (*.crt)
      - **`privateKey`**: path to the private key of the certificate (*.key)
      - **`privateKeyPassword`**: password for the private key

    <Note type="info">

    <NoteTitle toggles="false">
        
    ##### Tip
    </NoteTitle>

      It is recommended to provide the private key password using command line argument (environment variable) 
      `api.certificate.custom.privateKeyPasssword` and store id in a CI server secrets vault.
    </Note>

    <Note type="question">

    <NoteTitle toggles="true">
    
    ##### Is there an alternative to this manual configuration?
    </NoteTitle>

    Yes there is. You can use standardized way importing the 
    <Term document="docs/user/en/operate/tls.md">certificate authority</Term> 
    <Term document="docs/user/en/operate/tls.md">certificate</Term> to the Java trust store. This procedure is
    described in great detail in [this article](https://medium.com/expedia-group-tech/how-to-import-public-certificates-into-javas-truststore-from-a-browser-a35e49a806dc).

    </Note>
  </dd>
</dl>

If no custom certificate is configured, the server will not start and an exception will be thrown. The server doesn't
provide an unsecured connection for security reasons.

### GraphQL API configuration

### REST API configuration

### gRPC API configuration

#### Mutual TLS configuration

### System API configuration

There is a special `api.endpoints.system` endpoint that allows access over the unsecured HTTP protocol. Since it's the
only exposed endpoint on the unsecured http protocol, it must run on a separate port. The endpoint allows anyone to
download the public part of the server certificate.

It also allows downloading the default client private/public key pair if `api.certificate.generateAndUseSelfSigned` and
`api.gRPC.mTLS` are both set to `true`. See [default unsecure mTLS behaviour](#default-mtls-behaviour--not-secure-) for
more information.