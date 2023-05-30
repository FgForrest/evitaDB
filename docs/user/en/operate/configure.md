---
title: Configuration
perex: This article is a complete configuration guide for evitaDB instance.
date: '1.3.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
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
  readOnly: false

storage:                                          # [see Storage configuration](#storage-configuration)
  storageDirectory: null
  lockTimeoutSeconds: 60
  waitOnCloseSeconds: 60
  outputBufferSize: 4MB
  maxOpenedReadHandles: 12
  computeCRC32C: true

cache:                                            # [see Cache configuration](#cache-configuration)
  enabled: true
  reflection: CACHE
  reevaluateEachSeconds: 60
  anteroomRecordCount: 100K
  minimalComplexityThreshold: 10K
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
      allowedOrigins: null
    rest:                                         # [see REST API configuration](#rest-api-configuration)
      enabled: true
      host: localhost:5555
      allowedOrigins: null
    gRPC:                                         # [see gRPC API configuration](#grpc-api-configuration)
      enabled: true
      host: localhost:5556
      mTLS:
        enabled: false
        allowedClientCertificatePaths: []
    system:                                       # [see System API configuration](#system-api-configuration)
      enabled: true
      host: localhost:5557
      allowedOrigins: null
```

<Note type="info">

<NoteTitle toggles="true">

##### Are there any shortcuts for large numbers?
</NoteTitle>

Yes there are - you can use standardized metric system shortcuts for counts and sizes (all abbreviations are
**case-sensitive**). See following table:

<Table caption="Number formats">
    <Thead>
        <Tr>
            <Th>Abbreviation</Th>
            <Th>Meaning</Th>
            <Th>Example</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>K</Td>
            <Td>one thousand</Td>
            <Td>1K &rightarrow; 1,000</Td>
        </Tr>
        <Tr>
            <Td>M</Td>
            <Td>one million</Td>
            <Td>1M &rightarrow; 1,000,000</Td>
        </Tr>
        <Tr>
            <Td>G</Td>
            <Td>one billion</Td>
            <Td>1G &rightarrow; 1,000,000,000</Td>
        </Tr>
        <Tr>
            <Td>T</Td>
            <Td>one trillion</Td>
            <Td>1T &rightarrow; 1,000,000,000,000</Td>
        </Tr>
    </Tbody>
</Table>


<Table caption="Size formats">
    <Thead>
        <Tr>
            <Th>Abbreviation</Th>
            <Th>Meaning</Th>
            <Th>Example</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>KB</Td>
            <Td>one kilobyte</Td>
            <Td>1KB &rightarrow; 1,024</Td>
        </Tr>
        <Tr>
            <Td>MB</Td>
            <Td>one megabyte</Td>
            <Td>1MB &rightarrow; 1,048,576</Td>
        </Tr>
        <Tr>
            <Td>GB</Td>
            <Td>one gigabyte</Td>
            <Td>1GB &rightarrow; 1,073,741,824</Td>
        </Tr>
        <Tr>
            <Td>TB</Td>
            <Td>one terabyte</Td>
            <Td>1TB &rightarrow; 1,099,511,627,776</Td>
        </Tr>
    </Tbody>
</Table>


<Table caption="Time formats">
    <Thead>
        <Tr>
            <Th>Abbreviation</Th>
            <Th>Meaning</Th>
            <Th>Example</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>1s</Td>
            <Td>one second</Td>
            <Td>1s &rightarrow; 1 secs</Td>
        </Tr>
        <Tr>
            <Td>m</Td>
            <Td>one minute</Td>
            <Td>1m &rightarrow; 60 secs</Td>
        </Tr>
        <Tr>
            <Td>h</Td>
            <Td>one hour</Td>
            <Td>1h &rightarrow; 3,600 secs</Td>
        </Tr>
        <Tr>
            <Td>d</Td>
            <Td>one day</Td>
            <Td>1d &rightarrow; 86,400 secs</Td>
        </Tr>
        <Tr>
            <Td>d</Td>
            <Td>one week</Td>
            <Td>1w &rightarrow; 604,800 secs</Td>
        </Tr>
        <Tr>
            <Td>y</Td>
            <Td>one year</Td>
            <Td>1y &rightarrow; 31,556,926 secs</Td>
        </Tr>
    </Tbody>
</Table>

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Where the default configuration bundled with Docker image is located?
</NoteTitle>

The default configuration file is located in the file <SourceClass>docker/evita-configuration.yaml</SourceClass>.
As you can see, it contains variables that allow the propagation of arguments from the command line / environment
variables that are present when the server is started. The format used in this file is :

```
${argument_name:defaultValue}
```
</Note>

## Server configuration

This section contains general settings for the evitaDB server. It allows configuring thread pools, queues, timeouts:

<dl>
    <dt>coreThreadCount</dt>
    <dd>
        <p>**Default:** `4`</p>
        <p>It defines the minimum number of threads in the evitaDB main thread pool, threads are used for query processing, 
        transactional updates and service tasks (vacuuming, cache revalidation). The value should be at least equal to 
        the number of machine cores.</p>
    </dd>
    <dt>maxThreadCount</dt>
    <dd>
        <p>**Default:** `16`</p>
        <p>It defines the maximum number of threads in the evitaDB main thread pool. The value should be a multiple of the 
        `coreThreadCount` value.</p>
    </dd>
    <dt>threadPriority</dt>
    <dd>
        <p>**Default:** `5`</p>
        <p>It defines the priority of the threads created in the pool (for future use).</p> 
    </dd>
    <dt>queueSize</dt>
    <dd>
        <p>**Default:** `100`</p>
        <p>It defines the maximum number of tasks that can accumulate in the queue waiting for the free thread from 
        the thread pool to process them. Tasks that exceed this limit will be discarded (new requests/other tasks will 
        fail with an exception).</p>
    </dd>
    <dt>closeSessionsAfterSecondsOfInactivity</dt>
    <dd>
        <p>**Default: `60`**</p>
        <p>It specifies the maximum acceptable period of 
        <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> inactivity before 
        it is forcibly closed by the server side.</p>
    </dd>
    <dt>readOnly</dt>
    <dd>
        <p>**Default: `false`**</p>
        <p>It switches the evitaDB server into read-only mode, where no updates are allowed and the server only provides 
           read access to the data of the catalogs present in the data directory at the start of the server instance.</p>
    </dd>
</dl>

## Storage configuration

This section contains configuration options for the storage layer of the database.

<dl>
    <dt>storageDirectory</dt>
    <dd>
        <p>**Default:** `null`</p>
        <p>It defines the folder where evitaDB stores its catalog data. The path can be specified relative to the working
        directory of the application in absolute form (recommended).</p>
    </dd>
    <dt>lockTimeoutSeconds</dt>
    <dd>
        <p>**Default:** `60`</p>
        <p>It specifies the maximum amount of time the thread may wait to get an exclusive WRITE lock on the file to write 
        its data. Changing this value should not be necessary if everything is going well.</p>
    </dd>
    <dt>waitOnCloseSeconds</dt>
    <dd>
        <p>**Default:** `60`</p>
        <p>It specifies a timeout for evitaDB to wait for the release of read handles to a file. If the file handle is not 
        released within the timeout, the calling process will get an exception. Changing this value should not be 
        necessary if everything works fine.</p>
    </dd>
    <dt>outputBufferSize</dt>
    <dd>
        <p>**Default:** `4MB`</p>
        <p>The output buffer size determines how large a buffer is kept in memory for output purposes. The size of the 
        buffer limits the maximum size of an individual record in the key/value data store.</p>
    </dd>
    <dt>maxOpenedReadHandles</dt>
    <dd>
        <p>**Default:** `12`</p>
        <p>It defines the maximum number of simultaneously opened file read handles.</p>
        <Note type="warning">
            This setting should be set in sync with file handle settings in operating system. 
            Read these articles for [Linux](https://www.baeldung.com/linux/limit-file-descriptors) or 
            [MacOS](https://gist.github.com/tombigel/d503800a282fcadbee14b537735d202c)            
        </Note>
    </dd>
    <dt>computeCRC32C</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>It determines whether CRC32C checksums are calculated for written records in a key/value store, and also whether 
        the CRC32C checksum is checked when a record is read.</p>
        <Note type="warning">
            It is strongly recommended that this setting be set to `true`, as it will report potentially corrupt records as 
            early as possible.
        </Note>
    </dd>
</dl>

## Cache configuration

The cache speeds up responses for fully or partially identical queries. The cache might in some case increase
the throughput of the system several times.

<Note type="warning">
In current version we recommend disabling the cache until [the issue #37](https://github.com/FgForrest/evitaDB/issues/37)
is resolved.
</Note>

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>This setting enables or disables the use of the cache entirely.</p>
    </dd>
    <dt>reflection</dt>
    <dd>
        <p>**Default:** `CACHE`</p>
        <p>This setting enables or disables caching of Java reflection information. The `CACHE` mode is usually recommended 
        unless you're running some kind of test.</p>
    </dd>
    <dt>reevaluateEachSeconds</dt>
    <dd>
        <p>**Default:** `60`</p>
        <p>It defines the period for re-evaluating cache adepts to be propagated to cache or pruned.
        The reevaluation may be also triggered by exceeding maximum allowed `anteroomRecordCount`, but no later than
        `reevaluateEachSeconds` since the last re-evaluation (with the exception when there is no free thread in 
        thread pool to serve this task). See [detailed caching process description](../deep-dive/cache.md).</p>
    </dd>
    <dt>anteroomRecordCount</dt>
    <dd>
        <p>**Default:** `100K`</p>
        <p>It defines the maximum number of records in cache anteroom. When this count is reached the re-evaluation
        process is automatically triggered leading to anteroom purge. The anteroom is also periodically purged
        each `reevaluateEachSeconds`. See [detailed caching process description](../deep-dive/cache.md).</p>
    </dd>
    <dt>minimalComplexityThreshold</dt>
    <dd>
        <p>**Default:** `10K`</p>
        <p>It specifies the minimum computational complexity that must be achieved to store the cached result in the 
        cache. It's sort of a virtual number, so there's no guide as to how big it should be. If the cache fills up with
        a lot of results of doubtful use, you might try to increase this threshold to higher values.</p>
    </dd>
    <dt>minimalUsageThreshold</dt>
    <dd>
        <p>**Default:** `2`</p>
        <p>It specifies the minimum number of times a computed result can be reused before it is cached. If the cache is 
        filling up with cached values with low hit ratios, you might try increasing this threshold to higher values.</p>
    </dd>
    <dt>cacheSizeInBytes</dt>
    <dd>
        <p>**Default:** `null`, which means that evitaDB uses 25% of the free memory measured at the moment it starts and loads all data into it</p>
        <p>evitaDB tries to estimate the memory size of each cached object and avoid exceeding this threshold.</p>
        <Note type="question">

        <NoteTitle toggles="true">
    
        ##### How do we measure the object size?
        </NoteTitle>

        Measuring the exact amount of memory each object allocates is not easy in Java, and at the moment it's only 
        an approximation from our side. According to our experience, our estimates are set higher than the reality and 
        the system stops at around 90% of the set `cacheSizeInBytes` limit (but this experience is based on OS Linux, x86_64 architecture).
        </Note>
    </dd>
</dl>

## API configuration

This section of the configuration allows you to selectively enable, disable, and tweak specific APIs. 

### TLS configuration

TLS support is enabled by default and cannot be disabled. It's configured in the `certificate` subsection of the `api`.
It allows configuring these settings:

<dl>
  <dt>generateAndUseSelfSigned</dt>
  <dd>
    <p>**Default:** `true`</p>
    <p>When set to `true`, a self-signed <Term location="/docs/user/en/operate/tls.md">certificate authority</Term> 
    <Term location="/docs/user/en/operate/tls.md">certificate</Term> and its 
    <Term location="/docs/user/en/operate/tls.md">private key</Term> are automatically generated on server startup 
    and used to communicate with clients.</p>
  </dd>
  <dt>folderPath</dt>
  <dd>
    <p>**Default:** the sub-folder `evita-server-certificates` in the working directory</p>
    <p>It represents a path to a folder where the generated authority certificate and its private key are stored.
    This setting is used only when `generateAndUseSelfSigned` is set to `true`.</p>
  </dd>
  <dt>custom</dt>
  <dd>
    <p>This section allows you to configure an externally supplied <Term location="/docs/user/en/operate/tls.md">certificate</Term>. 
    It is only used if the `generateAndUseSelfSigned` is set to `false`.</p>
    <p>The section requires these nested settings:</p>
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
    <Term location="/docs/user/en/operate/tls.md">certificate authority</Term> 
    <Term location="/docs/user/en/operate/tls.md">certificate</Term> to the Java trust store. This procedure is
    described in great detail in [this article](https://medium.com/expedia-group-tech/how-to-import-public-certificates-into-javas-truststore-from-a-browser-a35e49a806dc).

    </Note>
  </dd>
</dl>

If no custom certificate is configured, the server will not start and an exception will be thrown. The server doesn't
provide an unsecured connection for security reasons.

### GraphQL API configuration

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>It enables / disables GraphQL web API.</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default:** `localhost:5555`</p>
        <p>It specifies the host and port that the GraphQL API should listen on. The value may be identical to the REST 
        API, but not to the gRPC or System API.</p>
    </dd>
    <dt>allowedOrigins</dt>
    <dd>
        <p>**Default:** `null`</p>
        <p>Specifies comma separated [origins](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin) 
        that are allowed to consume the GraphQL API. If no origins are specified, i.e. `null`, all origins are 
        allowed automatically.</p>
    </dd>
</dl>

### REST API configuration

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>It enables / disables REST web API.</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default:** `localhost:5555`</p>
        <p>It specifies the host and port that the GraphQL API should listen on. The value may be identical to the GraphQL 
        API, but not to the gRPC or System API.</p>
    </dd>
    <dt>allowedOrigins</dt>
    <dd>
        <p>**Default:** `null`</p>
        <p>Specifies comma separated [origins](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin) 
        that are allowed to consume the GraphQL API. If no origins are specified, i.e. `null`, all origins are 
        allowed automatically.</p>
    </dd>
</dl>

### gRPC API configuration

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>It enables / disables gRPC web API.</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default:** `localhost:5555`</p>
        <p>It specifies the host and port that the GraphQL API should listen on. The value must be different from all 
        other APIs because gRPC internally uses completely different web server.</p>
    </dd>
</dl>

#### Mutual TLS configuration

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>It enables / disables [mutual authentication](tls.md#mutual-tls-for-grpc).</p>
    </dd>
    <dt>allowedClientCertificatePaths</dt>
    <dd>
        <p>**Default:** `null`</p>
        <p>It allows you to define zero or more file paths pointing to public <Term location="/docs/user/en/operate/tls.md" name="certificate">client certificates</Term>.
        Only clients that present the correct certificate will be allowed to communicate with the gRPC web API.</p>
    </dd>
</dl>

### System API configuration

There is a special `api.endpoints.system` endpoint that allows access over the unsecured HTTP protocol. Since it's the
only exposed endpoint on the unsecured http protocol, it must run on a separate port. The endpoint allows anyone to
download the public part of the server certificate.

It also allows downloading the default client private/public key pair if `api.certificate.generateAndUseSelfSigned` and
`api.gRPC.mTLS` are both set to `true`. See [default unsecure mTLS behaviour](tls.md#default-mtls-behaviour-not-secure) for
more information.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>It enables / disables system web API.</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default:** `localhost:5557`</p>
        <p>It specifies the host and port on which the system API should listen. The value must be different from all 
        other APIs because the system API needs to run on the insecure HTTP protocol while the other APIs use the secure one.</p>
        <p>The system endpoint allows anyone to view public <Term location="/docs/user/en/operate/tls.md">certificate authority</Term> 
        <Term location="/docs/user/en/operate/tls.md">certificate</Term> and it also provides information for 
        [default `mTLS` implementation](tls.md#default-mtls-behaviour-not-secure).</p>
    </dd>
    <dt>allowedOrigins</dt>
    <dd>
        <p>**Default:** `null`</p>
        <p>Specifies comma separated [origins](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin) 
        that are allowed to consume the GraphQL API. If no origins are specified, i.e. `null`, all origins are 
        allowed automatically.</p>
    </dd>
</dl>