---
title: Configuration
perex: This article is a complete configuration guide for evitaDB instance.
date: '14.7.2024'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

The evitaDB server is configured in YAML format and its default settings are best described by the following code
snippet:

```yaml
name: evitaDB                                     # [see Name configuration](#name)

server:                                           # [see Server configuration](#server-configuration)
  requestThreadPool:
    minThreadCount: 4
    maxThreadCount: 16
    threadPriority: 5
    queueSize: 100
  transactionThreadPool:
    minThreadCount: 4
    maxThreadCount: 16
    threadPriority: 5
    queueSize: 100
  serviceThreadPool:
    minThreadCount: 4
    maxThreadCount: 16
    threadPriority: 5
    queueSize: 100
  queryTimeoutInMilliseconds: 5s
  transactionTimeoutInMilliseconds: 5M
  closeSessionsAfterSecondsOfInactivity: 60
  readOnly: false
  quiet: false
  trafficRecording:
    enabled: false
    sourceQueryTracking: false
    trafficMemoryBufferSizeInBytes: 4MB
    trafficDiskBufferSizeInBytes: 32MB
    exportFileChunkSizeInBytes: 16MB
    trafficSamplingPercentage: 100
    trafficFlushIntervalInMilliseconds: 1m

storage:                                          # [see Storage configuration](#storage-configuration)
  storageDirectory: "./data"
  exportDirectory: "./export"
  lockTimeoutSeconds: 60
  waitOnCloseSeconds: 60
  outputBufferSize: 4MB
  maxOpenedReadHandles: 12
  syncWrites: true
  computeCRC32C: true
  compress: false
  minimalActiveRecordShare: 0.5
  fileSizeCompactionThresholdBytes: 100MB
  timeTravelEnabled: false
  exportDirectorySizeLimitBytes: 1G
  exportFileHistoryExpirationSeconds: 7d

transaction:                                      # [see Transaction configuration](#transaction-configuration)
  transactionWorkDirectory: /tmp/evitaDB/transaction
  transactionMemoryBufferLimitSizeBytes: 16MB
  transactionMemoryRegionCount: 256
  walFileSizeBytes: 16MB
  walFileCountKept: 8
  flushFrequencyInMillis: 1s

cache:                                            # [see Cache configuration](#cache-configuration)
  enabled: false
  reflection: CACHE
  reevaluateEachSeconds: 60
  anteroomRecordCount: 100K
  minimalComplexityThreshold: 10K
  minimalUsageThreshold: 2
  cacheSizeInBytes: null

api:                                              # [see API configuration](#api-configuration)
  workerGroupThreads: 4
  idleTimeoutInMillis: 2K
  requestTimeoutInMillis: 2K  
  maxEntitySizeInBytes: 2MB
  accessLog: false
  headers:
    forwardedUri: ["X-Forwarded-Uri"]
    forwardedFor: ["Forwarded", "X-Forwarded-For", "X-Real-IP"]
    label: ["X-EvitaDB-Label"]
    clientId: ["X-EvitaDB-ClientID"]
    traceParent: ["traceparent"]
  certificate:                                    # [see TLS configuration](#tls-configuration) 
    generateAndUseSelfSigned: true
    folderPath: './evita-server-certificates/'
    custom:
      certificate: null
      privateKey: null
      privateKeyPassword: null
  endpointDefaults:
    enabled: true
    host: ":5555"
    exposeOn: "localhost:5555"
    tlsMode: FORCE_TLS
    keepAlive: true
    mTLS:
      enabled: false
      allowedClientCertificatePaths: []
  endpoints:
    system:                                       # [see System API configuration](#system-api-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: FORCE_NO_TLS
      keepAlive: null
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    graphQL:                                      # [see GraphQL API configuration](#graphql-api-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      parallelize: true
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    rest:                                         # [see REST API configuration](#rest-api-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    gRPC:                                         # [see gRPC API configuration](#grpc-api-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      exposeDocsService: false
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    lab:                                          # [see evitaLab configuration](#evitalab-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      gui:
        enabled: true
        readOnly: false    
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    observability:                                # [see Observability configuration](#observability-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      tracing:
        serviceName: evitaDB
        endpoint: null
        protocol: grpc
      allowedEvents: null
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
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

##### Where is the default configuration bundled with the Docker image?
</NoteTitle>

The default configuration file is located in the file <SourceClass>evita_server/src/main/resources/evita-configuration.yaml</SourceClass>.
As you can see, it contains variables that allow the propagation of arguments from the command line / environment
variables that are present when the server is started. The format used in this file is:

```
${argument_name:defaultValue}
```
</Note>

## Overriding defaults

There are several ways to override the defaults specified in the <SourceClass>evita_server/src/main/resources/evita-configuration.yaml</SourceClass> 
file on the classpath.

### Environment Variables

Any configuration property can be overridden by setting an environment variable with a specially crafted name. The name
of the variable can be calculated from the variable used in the default config file, which is always constructed from 
the path to the property in the configuration file. The calculation consists of capitalizing the variable name and 
replacing all dots with underscores. For example, the `server.coreThreadCount` property can be overridden by setting
the `SERVER_CORETHREADCOUNT` environment variable.

### Command Line Arguments

Any configuration property can also be overridden by setting a command line argument with the following format

```shell
java -jar "target/evita-server.jar" "storage.storageDirectory=../data"
```

Application arguments have priority over environment variables.

<Note type="info">

<NoteTitle toggles="true">

##### How do I set application arguments in a Docker container?

</NoteTitle>

When using Docker containers, you can set application arguments in the `EVITA_ARGS` environment variable - for example

```shell
docker run -i --rm --net=host -e EVITA_ARGS="storage.storageDirectory=../data" index.docker.io/evitadb/evitadb:latest
```

</Note>

### Custom configuration file

Finally, the configuration file can be overridden by specifying a custom configuration file in the configuration folder
specified by the `configDir` application argument. The custom configuration file must be in the same YAML format as 
the default configuration, but may only contain a subset of the properties to be overridden. It's also possible to 
define multiple override files. The files are applied in alphabetical order of their names. If you are building your 
own Docker image, you can use the following command to override the configuration file:

```shell
COPY "your_file.yaml" "$EVITA_CONFIG_DIR"
```

If you have a more complex concatenated pipeline, you can copy multiple files to this folder at different stages of 
the pipeline - but you must maintain the proper alphabetical order of the files so that overrides are applied the way
you want.

## Name

The server name is a unique name for the evitaDB server instance and should be unique for each instance (environment) 
of the evitaDB installation. If no name is specified and the default `evitaDB` is left intact, it is automatically 
appended with a hash value calculated from the server host name, the main server storage directory path and 
the timestamp of the storage directory creation. This is done to ensure that the server name is unique even if 
the server is started multiple times on the same machine. The server name is used in clients to distinguish one server 
from another and to handle unique server certificates correctly.

## Server configuration

This section contains general settings for the evitaDB server. It allows configuring thread pools, queues, timeouts:

<dl>
    <dt>requestThreadPool</dt>
    <dd>
        <p>Sets limits on the core thread pool used to serve all incoming requests. Threads from this pool handle all 
        queries and updates until the transaction is committed/rolled back. See [separate chapter](#thread-pool-configuration) 
        for more information.</p>
    </dd>
    <dt>transactionThreadPool</dt>
    <dd>
        <p>Sets limits on the transaction thread pool used to process transactions when they're committed. I.e. conflict
        resolution, inclusion in trunk, and replacement of shared indexes used. See [separate chapter](#thread-pool-configuration) 
        for more information.</p>
    </dd>
    <dt>serviceThreadPool</dt>
    <dd>
        <p>Sets limits on the service thread pool used for service tasks such as maintenance, backup creation, backup
        restoration, and so on. See [separate chapter](#thread-pool-configuration) for more information.</p>
    </dd>
    <dt>queryTimeoutInMilliseconds</dt>
    <dd>
        <p>**Default:** `5s`</p>
        <p>Sets the timeout in milliseconds after which threads executing read-only session requests should timeout and 
        abort their execution.</p>
    </dd>
    <dt>transactionTimeoutInMilliseconds</dt>
    <dd>
        <p>**Default:** `5m`</p>
        <p>Sets the timeout in milliseconds after which threads executing read-write session requests should timeout and 
        abort their execution.</p>
    </dd>
    <dt>closeSessionsAfterSecondsOfInactivity</dt>
    <dd>
        <p>**Default:** `60`</p>
        <p>It specifies the maximum acceptable period of 
        <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> inactivity before 
        it is forcibly closed by the server side.</p>
    </dd>
    <dt>readOnly</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>It switches the evitaDB server into read-only mode, where no updates are allowed and the server only provides 
           read access to the data of the catalogs present in the data directory at the start of the server instance.</p>
    </dd>
    <dt>quiet</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>It disables logging of helper info messages (e.g.: startup info). Note that it doesn't disable the main logging
           handled by the [Slf4j](https://www.slf4j.org/) logging facade.</p>
         <Note type="warning">
            This setting should not be used when running multiple server instances inside single JVM because it is currently
            not thread-safe.            
        </Note>
    </dd>
</dl>

### Thread pool configuration

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
</dl>

### Traffic recording configuration

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>When set to `true`, the server records all traffic to the database (all catalogues) in a single shared memory
        and disk buffer, which can optionally be persisted to file. If traffic recording is disabled, it can still be 
        enabled on demand via the API (but won't be automatically enabled and recorded). Recording is optimised for low 
        performance overhead, but should not be enabled on production systems (hence the default is `false`).</p>
    </dd>
    <dt>sourceQueryTracking</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>When set to `true`, the server will record the query in its original form (GraphQL / REST / gRPC) and track 
        sub-queries related to the original query. This is useful for debugging and performance analysis, but isn't
        necessary for traffic replay.</p>
    </dd>
    <dt>trafficMemoryBufferSizeInBytes</dt>
    <dd>
        <p>**Default:** `4MB`</p>
        <p>Sets the size in bytes of the memory buffer used for traffic recording. Even if `enabled` is set to `false`,
        this property is used when on-demand traffic recording is requested. This property affects the number of 
        parallel sessions that are recorded. All requests made in the same session must first be collected in this 
        memory buffer before they're persisted sequentially to the disk buffer.</p> 
    </dd>
    <dt>trafficDiskBufferSizeInBytes</dt>
    <dd>
        <p>**Default:** `32MB`</p>
        <p>Sets the size in bytes of the disk buffer used for traffic recording. Even if `enabled` is set to `false`,
        this property will be used when on-demand traffic recording is requested. The disk buffer represents a ring
        buffer that is indexed and available for viewing in the evitaLab interface. The larger the buffer, the more 
        historical data it can hold.</p>
    </dd>
    <dt>exportFileChunkSizeInBytes</dt>
    <dd>
        <p>**Default:** `16MB`</p>
        <p>Sets the size in bytes of the exported file chunk. The file is split into chunks of this size when exporting 
        the traffic recording contents. The chunks are then compressed and stored in the export directory.</p>
    </dd>
    <dt>trafficSamplingPercentage</dt>
    <dd>
        <p>**Default:** `100`</p>
        <p>Specifies the percentage of traffic to be captured. The value is between 0 and 100 - zero means that no 
           traffic is captured (equivalent to `enabled: false`) and 100 means that all traffic is attempted to be captured.</p>
    </dd>
    <dt>trafficFlushIntervalInMilliseconds</dt>
    <dd>
        <p>**Default:** `1m`</p>
        <p>Sets the interval in milliseconds at which the traffic buffer is flushed to disk. For development 
        (i.e. low traffic, immediate debugging) it can be set to 0. For production it should be set to a reasonable 
        value (e.g. 60000 = minute).</p>
    </dd>
</dl>

## Storage configuration

This section contains configuration options for the storage layer of the database.

<dl>
    <dt>storageDirectory</dt>
    <dd>
        <p>**Default:** `./data`</p>
        <p>It defines the folder where evitaDB stores its catalog data. The path can be specified relative to the working
        directory of the application in absolute form (recommended).</p>
    </dd>
    <dt>exportDirectory</dt>
    <dd>
        <p>**Default:** `./export`</p>
        <p>It defines the folder where evitaDB stores its exported files. The path can be specified relative to the working
        directory of the application in absolute form (recommended). Files are automatically removed according to limits
        defined in `exportFileHistoryExpirationSeconds` and `exportDirectorySizeLimitBytes`.</p>
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
    <dt>syncWrites</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>Determines whether the storage layer forces the operating system to flush the internal buffers to disk at
        regular "safe points" or not. The default is true, so that data is not lost in the event of a power failure. 
        There are situations where disabling this feature can improve performance and the client can accept the risk
        of data loss (e.g. when running automated tests, etc.).</p>
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
    <dt>compress</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>Specifies whether or not to compress the data. If set to true, all data will be compressed, but only those 
        whose compressed size is less than the original size will be stored in compressed form. Setting this property 
        to `true` may slow down writes (though not significantly) and increase read speed and throughput, as there's 
        less slow disk I/O involved. Currently the standard ZIP/deflate compression method is used.</p>
    </dd>
    <dt>minimalActiveRecordShare</dt>
    <dd>
        <p>**Default:** `0.5` (when waste exceeds 50% the file is compacted)</p>
        <p>Minimal share of active records in the data file. If the share is lower and the file size exceeds also
            `fileSizeCompactionThresholdBytes` limit, the file will be compacted. It means new file containing only 
            active records will be written next to original file.</p>
    </dd>
    <dt>fileSizeCompactionThresholdBytes</dt>
    <dd>
        <p>**Default:** `100MB`</p>
        <p>Minimal file size threshold for compaction. If the file size is lower, the file will not be compacted even 
            if the share of active records is lower than the minimal share.</p>
    </dd>
    <dt>timeTravelEnabled</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>When set to true, the data files are not removed immediately after compacting, but are kept on disk as long 
        as there is history available in the WAL log. This allows a snapshot of the database to be taken at any point 
        in the history covered by the WAL log. From the snapshot, the database can be restored to the exact point in 
        time with all the data available at that time.</p>
    </dd>
    <dt>exportDirectorySizeLimitBytes</dt>
    <dd>
        <p>**Default:** `1G`</p>
        <p>It specifies the maximum size of the export directory. If the size of the directory exceeds this limit, the 
        oldest files are removed until the size of the directory is below the limit.</p>
    </dd>
    <dt>exportFileHistoryExpirationSeconds</dt>
    <dd>
        <p>**Default:** `7d`</p>
        <p>It specifies the maximum age of the files in the export directory. If the age of the file exceeds this limit, 
        the file is removed from the directory.</p>
    </dd>
</dl>

## Transaction configuration

This section contains configuration options for the storage layer of the database dedicated to transaction handling.

<dl>
    <dt>transactionWorkDirectory</dt>
    <dd>
        <p>**Default:** `/tmp/evitaDB/transaction`</p>
        <p>Directory on local disk where Evita creates temporary folders and files for transactional transaction. 
            By default, temporary directory is used - but it is a good idea to set your own directory to avoid problems 
            with disk space.</p>
    </dd>
    <dt>transactionMemoryBufferLimitSizeBytes</dt>
    <dd>
        <p>**Default:** `16MB`</p>
        <p>Number of bytes that are allocated on off-heap memory for transaction memory buffer. This buffer is used to 
            store temporary (isolated) transactional data before they are committed to the database.
            If the buffer is full, the transaction data are immediately written to the disk and the transaction 
            processing gets slower.</p>
    </dd>
    <dt>transactionMemoryRegionCount</dt>
    <dd>
        <p>**Default:** `256`</p>
        <p>Number of slices of the `transactionMemoryBufferLimitSizeBytes` buffer.
            The more slices the smaller they get and the higher the probability that the buffer will be full and will 
            have to be copied to the disk.</p>
    </dd>
    <dt>walFileSizeBytes</dt>
    <dd>
        <p>**Default:** `16MB`</p>
        <p>Size of the Write-Ahead Log (WAL) file in bytes before it is rotated.</p>
    </dd>
    <dt>walFileCountKept</dt>
    <dd>
        <p>**Default:** `8`</p>
        <p>Number of WAL files to keep. Increase this number in combination with `walFileSizeBytes` if you want to
            keep longer history of changes.</p>
    </dd>
    <dt>flushFrequencyInMillis</dt>
    <dd>
        <p>**Default:** `1s`</p>
        <p>The frequency of flushing the transactional data to the disk when they are sequentially processed.
            If database process the (small) transaction very quickly, it may decide to process next transaction before 
            flushing changes to the disk. If the client waits for `WAIT_FOR_CHANGES_VISIBLE` he may wait entire 
            `flushFrequencyInMillis` milliseconds before he gets the response.</p>
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
        <p>**Default:** `false`</p>
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

<dl>
    <dt>workerGroupThreads</dt>
    <dd>
        <p>**Default:** `number of CPUs`</p>
        <p>Defines the number of IO threads that will be used by Armeria for accept and send HTTP payload.</p>
    </dd>
    <dt>idleTimeoutInMillis</dt>
    <dd>
        <p>**Default:** `2K`</p>
        <p>The amount of time a connection can be idle for before it is timed out. An idle connection is a connection 
            that has had no data transfer in the idle timeout period. Note that this is a fairly coarse grained approach,
            and small values will cause problems for requests with a long processing time.</p>
    </dd>
    <dt>requestTimeoutInMillis</dt>
    <dd>
        <p>**Default:** `2K`</p>
        <p>The amount of time a connection can sit idle without processing a request, before it is closed by the server.</p>
    </dd> 
    <dt>maxEntitySizeInBytes</dt>
    <dd>
        <p>**Default:** `2MB`</p>
        <p>The default maximum size of a request entity. If entity body is larger than this limit then a IOException 
            will be thrown at some point when reading the request (on the first read for fixed length requests, when too 
            much data has been read for chunked requests).</p>
    </dd>
    <dt>accessLog</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>It enables / disables access log messages logging for all APIs.</p>
    </dd> 
</dl>

### Headers configuration

The headers contain sensible defaults, but you may want to override them in some cases (for example, 
the `X-Forwarded-For` header is sometimes used by proxy servers between the client and the server).

A common configuration is in the `headers` subsection of the `api`.
It allows you to configure these settings:

<dl>
    <dd>
        <p>This section contains configuration for HTTP header names that are recognized by evitaDB.</p>
        <dl>
            <dt>forwardedUri</dt>
            <dd>
                <p>**Default:** `["X-Forwarded-Uri"]`</p>
                <p>Array of header names that are recognized as forwarded URI headers. These headers are used when evitaDB is behind a proxy to determine the original URI requested by the client.</p>
            </dd>
            <dt>forwardedFor</dt>
            <dd>
                <p>**Default:** `["Forwarded", "X-Forwarded-For", "X-Real-IP"]`</p>
                <p>Array of header names that are recognized as forwarded client IP headers. These headers are used when evitaDB is behind a proxy to determine the original client IP address.</p>
            </dd>
            <dt>label</dt>
            <dd>
                <p>**Default:** `["X-EvitaDB-Label"]`</p>
                <p>Array of header names for meta labels that allow to set traffic recording labels via HTTP headers.</p>
            </dd>
            <dt>clientId</dt>
            <dd>
                <p>**Default:** `["X-EvitaDB-ClientID"]`</p>
                <p>Array of header names that are recognized as client identifier headers. These headers can be used to identify the client application making the request.</p>
            </dd>
            <dt>traceParent</dt>
            <dd>
                <p>**Default:** `["traceparent"]`</p>
                <p>Array of header names that are recognized as trace parent headers. These headers are used for distributed tracing to correlate requests across different services.</p>
            </dd>
        </dl>
    </dd>
</dl>

### TLS configuration

TLS support is enabled by default for most APIs but can be disabled individually per API in API configs.
Note that if you set that each API has different TLS settings, each API must have its own port.

Common configuration is in the `certificate` subsection of the `api`.
It allows configuring these settings:

<dl>
  <dt>generateAndUseSelfSigned</dt>
  <dd>
    <p>**Default:** `true`</p>
    <p>When set to `true`, a self-signed <Term location="/documentation/user/en/operate/tls.md">certificate authority</Term> 
    <Term location="/documentation/user/en/operate/tls.md">certificate</Term> and its 
    <Term location="/documentation/user/en/operate/tls.md">private key</Term> are automatically generated on server startup 
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
    <p>This section allows you to configure an externally supplied <Term location="/documentation/user/en/operate/tls.md">certificate</Term>. 
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
    <Term location="/documentation/user/en/operate/tls.md">certificate authority</Term> 
    <Term location="/documentation/user/en/operate/tls.md">certificate</Term> to the Java trust store. This procedure is
    described in great detail in [this article](https://medium.com/expedia-group-tech/how-to-import-public-certificates-into-javas-truststore-from-a-browser-a35e49a806dc).

    </Note>
  </dd>
</dl>

If no custom certificate is configured, the server will not start and an exception will be thrown. The server doesn't
provide an unsecured connection for security reasons.

### Default endpoint configuration

Default endpoint settings are used as the basis for all endpoints unless overridden in the specific endpoint.
This allows you to set common settings for all endpoints in one place.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>It enables / disables a particular web API.</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default:** `:5555`</p>
        <p>It specifies the host and port that a particular API should listen on. If the host is not defined, 
        the wildcard address `0.0.0.0` for IPv4 and `::` for IPv6 is used instead. If the host is defined as a valid 
        IP address, it's used directly. If the domain name is specified, it's translated into an IP address by the Java 
        DNS lookup and used instead (the resolved IP address may not be the one, you expected - but the result IP is 
        logged to the log and console during the evitaDB server startup, so you can easily check it there).</p>
        <p>You may define multiple hosts / ports separated by a comma. The server will listen on all of them.</p>
    </dd>
    <dt>exposeOn</dt>
    <dd>
        <p>**Default:** `localhost`</p>
        <p>When evitaDB is running in a Docker container and the ports are exposed on the host systems 
           the internally resolved local host name and port usually don't match the host name and port 
           evitaDB is available on that host system.</p> 
        <p>The `exposedHost` property allows you to override not only the external hostname, scheme, but also to specify 
        an external port, but the minimum configuration is the hostname. If you don't specify scheme / port, exposed 
        host will assume that the default scheme / port configured for a web API should be used.</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Default:** `FORCE_TLS`</p>
        <p>Whether to enable the [TLS](./tls.md) for a particular API. Three modes are available:</p>
        <ol>
            <li>`FORCE_TLS`: Only encrypted (TLS) communication is allowed.</li>
            <li>`FORCE_NO_TLS`: Only unencrypted (non-TLS) communication is allowed.</li>
            <li>`RELAXED`: Both variants will be available, depending on the client's choice.</li>
        </ol>
    </dd>
    <dt>keepAlive</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>If this is set to false server closes connection via HTTP `connection: close` after each request.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>It enables / disables [mutual authentication](tls.md#mutual-tls-for-http) for a particular API.</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Default:** `[]`</p>
        <p>It allows you to define zero or more file paths pointing to public <Term location="/documentation/user/en/operate/tls.md" name="certificate">client certificates</Term> that can only communicate with the API.</p>
    </dd>
</dl>

### GraphQL API configuration

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default:** `:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Default:** `localhost:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Default:** `FORCE_TLS`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>parallelize</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>Controls whether queries that fetch data from evitaDB engine will be executed in parallel.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Default:** `[]`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
</dl>

### REST API configuration

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default:** `:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Default:** `localhost:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Default:** `FORCE_TLS`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Default:** `[]`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
</dl>

### gRPC API configuration

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default:** `:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Default:** `localhost:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Default:** `FORCE_TLS`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposeDocsService</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>It enables / disables the gRPC service, which provides documentation for the gRPC API and allows to
        experimentally call any of the services from the web UI and examine its output.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Default:** `[]`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
</dl>

### System API configuration

There is a special `api.endpoints.system` endpoint that allows access over the unsecured HTTP protocol. Since it's the
only exposed endpoint on the unsecured http protocol, it must run on a separate port. The endpoint allows anyone to
download the public part of the server certificate.

It also allows downloading the default client private/public key pair if `api.certificate.generateAndUseSelfSigned` and
any of `api.*.mTLS` are both set to `true`. See [default unsecure mTLS behaviour](tls.md#default-mtls-behaviour-not-secure) for
more information.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default:** `:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Default:** `localhost:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Default:** `FORCE_NO_TLS`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Default:** `[]`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
</dl>

### evitaLab configuration

evitaLab configuration primarily provides access to all enabled evitaDB APIs for the [evitaLab web client](https://github.com/lukashornych/evitaLab).
Besides that, it can also expose and serve an entire embedded version of the evitaLab web client. In default configuration,
it will expose the embedded evitaLab web client with preconfigured connection to the evitaDB server based on configuration
of other APIs.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default:** `:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Default:** `localhost:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Default:** `FORCE_TLS`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>gui</dt>
    <dd>
        <p>[See config](#gui-configuration)</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Default:** `[]`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
</dl>

#### GUI configuration

This configuration controls how the actual evitaLab web client will be served through HTTP protocol.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default**: `true`</p>
        <p>Whether evitaDB should serve the built-in evitaLab web client alongside the evitaLab API.</p>
    </dd>
    <dt>readOnly</dt>
    <dd>
        <p>**Default**: `false`</p>
        <p>Whether the evitaLab web client should be served in read-only mode. This means that it's runtime data and
        configuration cannot be changed. It doesn't mean that it will not allow you to change the data
        of an accessed evitaDB instance. This must be configured at the [scope of the evitaDB instance](#server-configuration).</p>
    </dd>
</dl>

### Observability configuration

The configuration controls all observability facilities exposed to the external systems. Currently, it's the endpoint
pro scraping Prometheus metrics, OTEL trace exporter and Java Flight Recorder events recording facilities.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Default:** `true`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Default:** `:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Default:** `localhost:5555`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Default:** `FORCE_NO_TLS`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>tracing.serviceName</dt>
    <dd>
        <p>**Default:** `evitaDB`</p>
        <p>Specifies the name of the service the traces should be published for.</p>
    </dd>
    <dt>tracing.endpoint</dt>
    <dd>
        <p>**Default:** `null`</p>
        <p>Specifies the URL to the [OTEL collector](https://opentelemetry.io/docs/collector/) that collects the traces.
        It's a good idea to run the collector on the same host as evitaDB so that it can further filter out traces and
        avoid unnecessary remote network communication.</p>
    </dd>
    <dt>tracing.protocol</dt>
    <dd>
        <p>**Default:** `grpc`</p>
        <p>Specifies the protocol used between the application and the OTEL collector to pass the traces. Possible 
        values are `grpc` and `http`. gRPC is much more performant and is the preferred option.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Default:** `false`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Default:** `[]`</p>
        <p>See [default endpoint configuration](#default-endpoint-configuration)</p>
    </dd>
</dl>
