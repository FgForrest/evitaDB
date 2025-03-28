---
title: Observe
perex: |
  evitaDB's observability facilities are designed to help you monitor running evitaDB instances as well as to help you
  optimize your application during development. All monitoring facilities are based on our operational experience and
  development of e-commerce projects.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'java'
---

**Work in progress**

The functionality is not finalized - [see issue #18](https://github.com/FgForrest/evitaDB/issues/18) 
and [issue #628](https://github.com/FgForrest/evitaDB/issues/628).

## Logging

evitaDB uses the [SLF4J](https://www.slf4j.org/) logging facade for logging both application log messages and access log messages. By default
only application log messages are enabled, the access log messages must be explicitly [enabled in configuration](#access-log).

You can override our default logback configuration by providing your own `logback.xml` and configuring it in a standard
way as [documented on Logback site](https://logback.qos.ch/manual/configuration.html#auto_configuration). For example by
passing `logback.configurationFile=/path/to/logback.xml` as a JVM argument.

Our default Logback configuration can be found in GitHub repository:
<SourceClass>https://github.com/FgForrest/evitaDB/blob/dev/evita_server/src/main/resources/META-INF/logback.xml</SourceClass>

### Access log

If the `accessLog` property is set to `true` in the [configuration](configure.md#api-configuration), the server will log
access log messages for all APIs using the
[Slf4j](https://www.slf4j.org/) logging facade. These messages are logged at the `INFO` level and contain the `ACCESS_LOG`
marker which you can use to separate standard messages from access log messages. Access log messages are logged
by logger `com.linecorp.armeria.logging.access` (see [Armeria documentation](https://armeria.dev/docs/server-access-log)).

### Server Logback utilities

evitaDB server comes ready with several custom utilities for easier configuration of the custom logged data.

*Note:* These utilities are only available in evitaDB server because the rest of the evitaDB codebase
doesn't rely on a concrete implementation of the [Slf4j](https://www.slf4j.org/) logging facade.
If the evitaDB is used as embedded instance, the following tools are not available, but can be used as reference to
custom implementation in chosen framework.

#### Tooling for log aggregators

If a log aggregator is used to consume evitaDB log messages, it is often useful to app log messages as one-line JSON objects.
Therefore, there is [Logback](https://logback.qos.ch/) layout ready-to-use to easily log app log messages as JSON objects.
This layout logs messages as JSON objects and makes sure that everything is properly escaped, even newline characters
in log messages (e.g. stack traces).

The layout is the `io.evitadb.server.log.AppLogJsonLayout` layout to log app log messages, and can be used as follows:
```xml
<configuration>
    <!-- ... -->
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <target>System.out</target>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>INFO</level>
        </filter>
        <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
            <layout class="io.evitadb.server.log.AppLogJsonLayout"/>
        </encoder>
    </appender>
    <!-- ... -->
</configuration>
```

## Readiness and liveness probes

The evitaDB server provides endpoints for Kubernetes [readiness and liveness probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/). The liveness probe is also 
configured as [healthcheck](https://docs.docker.com/reference/dockerfile/#healthcheck) by default in our Docker image. By default the health check waits `30s` before it
starts checking the server health, for larger databases you may need to increase this value using environment variable 
`HEALTHCHECK_START_DELAY` so that they have enough time to be loaded into memory.

<Note type="warning">

<NoteTitle toggles="false">

##### When you change system API port don't forget to set `SYSTEM_API_PORT` environment variable
</NoteTitle>

The healthcheck in the Docker image is configured to use the default system API port, which is `5555`. If you change 
the port, the health check will immediately report an unhealthy container because it won't be able to reach the probe 
endpoint. You need to specify the new port using the `SYSTEM_API_PORT` environment variable of the Docker container.

</Note>

Both probes are available in the `system` API and are accessible at the following endpoints:

### Readiness probe

```shell
curl -k "http://localhost:5555/system/readiness" \
     -H 'Content-Type: application/json'
```

The probe will return `200 OK` if the server is ready to accept traffic, otherwise it will return `503 Service Unavailable`.
Probe internally calls all enabled APIs via HTTP call on the server side to check if they are ready to serve traffic. 
Example response:

```json
{
  "status": "READY",
  "apis": {
	"rest": "ready",
	"system": "ready",
	"graphQL": "ready",
	"lab": "ready",
	"observability": "ready",
	"gRPC": "ready"
  }
}
```

The overall status may be one of the following constants:

<dl>
    <dt>STARTING</dt>
    <dd>At least one API is not yet ready.</dd>
    <dt>READY</dt>
    <dd>The server is ready to serve traffic.</dd>
    <dt>STALLING</dt>
    <dd>At least one API that was ready is not ready anymore.</dd>
    <dt>SHUTDOWN</dt>
    <dd>Server is shutting down. None of the APIs are ready.</dd>
</dl>

Each of the enabled APIs has its own status so that you can see which particular API is not ready in case of `STARTING` 
or `STALLING` status.

### Liveness probe

```shell
curl -k "http://localhost:5555/system/liveness" \
     -H 'Content-Type: application/json'
```

If the server is healthy, the probe will return `200 OK`. Otherwise, it will return `503 Service Unavailable`.
Example response:

```json
{
  "status": "healthy",
  "problems": []
}
```

If the server is unhealthy, the response will list the problems.

<dl>
    <dt>MEMORY_SHORTAGE</dt>
    <dd>Signalized when the consumed memory never goes below 85% of the maximum heap size and the GC tries to free 
    the old generation at least once. This leads to repeated attempts of expensive old generation GC and pressure on 
    host CPUs.</dd>
    <dt>INPUT_QUEUES_OVERLOADED</dt>
    <dd>Signalized when the input queues are full and the server is not able to process incoming requests. The problem
	is reported when there is ration of rejected tasks to accepted tasks >= 2. This flag is cleared when the rejection
	ratio decreases below the specified threshold, which signalizes that server is able to process incoming requests 
	again.</dd>
    <dt>JAVA_INTERNAL_ERRORS</dt>
    <dd>Signaled when there are occurrences of Java internal errors. These errors are usually caused by the server
    itself and are not related to the client's requests. Java errors signal fatal problems inside the JVM.</dd>
    <dt>EXTERNAL_API_UNAVAILABLE</dt>
    <dd>Signalized when the readiness probe signals that at least one external API, that is configured to be enabled
	doesn't respond to internal HTTP check call.</dd>
</dl>

## Metrics

evitaDB server can publish [metrics](https://en.wikipedia.org/wiki/Observability_(software)#Metrics).
The popular option of using the [Prometheus](https://prometheus.io/) solution was chosen as a way to make them available
outside the application. evitaDB exposes a scraping endpoint to which the application publishes collected metrics at
regular intervals, which can then be visualized using any tool such as [Grafana](https://grafana.com/).

Prometheus offers 4 types of metrics which can be published from applications, more in official [docs](https://prometheus.io/docs/concepts/metric_types/):

- Counter: a cumulative metric that represents a single monotonically increasing counter whose value can only be
  increase or be reset to zero on the start.
- Gauge: represents a single numerical value that can arbitrarily go up and down.
- Histogram: samples observations (usually things like request durations or response sizes) and counts them in
  configurable buckets. It also provides a sum of all observed values.
- Summary: Similar to a histogram, a summary samples observations (usually things like request durations and response
  sizes). While it also provides a total count of observations and a sum of all observed values, it calculates
  configurable quantiles over a sliding time window.

The database server exposes two types of metrics:

- JVM related metrics: these allow us to visualize important system information that can give us an overview of, for
  example, the state of the JVM, such as how much CPU and memory the database is using, how many threads it is using, or
  the current state of the Garbage Collector.
- Internal evitaDB metrics: have a direct link to the state of the database, its data, indexes, query processing speed,
  etc.

### Prometheus endpoint settings

To collect metrics and publish them to the scrape endpoint, you don't need to do anything other than have the
*observability* API enabled in the evitaDB config - this is a default behaviour. You can also set the path to a YAML 
file that can be used to restrict what metrics are actually collected. Without its specification (or with an empty file),
all metrics from both groups are automatically collected. The metrics are then available at the URL: 
*http://[evita-server-name]:5555/observability/metrics*.

The sample below shows the relevant part of the configuration file related to the metrics.

```yaml
api:
  endpoints:
    observability:
      enabled: ${api.endpoints.observability.enabled:true}
      host: ${api.endpoints.observability.host:":5555"}
      exposeOn: ${api.endpoints.observability.exposeOn:"localhost:5555"}
      tlsMode: ${api.endpoints.observability.tlsMode:FORCE_NO_TLS}
      allowedEvents: !include ${api.endpoints.observability.allowedEvents:null}
```

As mentioned above, separate groups can be specified from the two groups of metrics (system - JVM, internal - database)
to constrain the collected data. From the JVM category (
more [here](https://prometheus.github.io/client_java/instrumentation/jvm/)), published metrics can be constrained by
specifying selected names in the YAML array:

- `AllMetrics`
- `JvmThreadsMetrics`
- `JvmBufferPoolMetrics`
- `JvmClassLoadingMetrics`
- `JvmCompilationMetrics`
- `JvmGarbageCollectorMetrics`
- `JvmMemoryPoolAllocationMetrics`
- `JvmMemoryMetrics`
- `JvmRuntimeInfoMetric`
- `ProcessMetrics`

From the internal metrics section, metrics can also be constrained using the *wildcard* pattern in conjunction with Java
package. Thus, we can specify the name of the package that contains the metrics we want to enable with the suffix ".*",
which will enable the collection of all events in that category (package). It is also possible to specify individual
metrics by specifying the full name of their corresponding class (*package_path.class_name*).

Internal metrics are documented in the [Metrics reference](#metrics-reference) section.

### JFR events

The [JFR events](https://docs.oracle.com/javacomponents/jmc-5-4/jfr-runtime-guide/about.htm#JFRUH170) can also be
associated with metrics, which are used for local diagnostics of Java applications and can provide deeper insight into
their functioning. Unlike metrics that publish different types of data (counter, gauge, ...), JFR (Java Flight Recorder) 
responds to the invocation of any of the events targeted by the current recording. It is usually desirable to
run a streaming recording that collects data on all registered events while it is running, and it can be saved to a file
when the recording stops if desired. With JFR, one can also learn how long it took to process the monitored events or
how many times each event was called during the monitored time. These internal events, which in the case of evitaDB
share a class with Prometheus metrics, carry auxiliary information (catalog name, query parameters, etc.) which can help
in solving more complex problems and finding performance bottlenecks. Among other things, of course, possible stack
traces are stored in conjunction with this data.

In evitaDB, this concept has been integrated into the aforementioned Observability API (URL: */observability/*), on which
these endpoints exist for JFR control:

#### Check recording is running

Since only one recording can be running at a time, it is necessary to check if the recording is currently running.
You can do this by calling the following endpoint:

```shell
curl -k "http://localhost:5555/observability/checkRecording" \
     -H 'Content-Type: application/json'
```

The response will be empty if there is no active JFR recording. If there is an active recording, the response will 
return a JSON object representing the task:

```json
{
  "taskType": "JfrRecorderTask",
  "taskName": "JFR recording",
  "taskId": "e36303cd-9e20-4e51-8972-2b98c9945dd4",
  "catalogName": null,
  "issued": "2024-07-22T17:20:28.157+02:00",
  "started": "2024-07-22T17:20:28.16+02:00",
  "finished": null,
  "progress": 0,
  "settings": {
    "allowedEvents": [
      "io.evitadb.query",
      "MemoryAllocation"
    ],
    "maxSizeInBytes": null,
    "maxAgeInSeconds": null
  },
  "result": null,
  "publicExceptionMessage": null,
  "exceptionWithStackTrace": null
}
```

#### List event groups to include in recording

To start recording JFR events, you must specify which JFR event groups to include in the recording.
You can list all available event groups by invoking the following endpoint:

```shell
curl -k "http://localhost:5555/observability/getRecordingEventTypes" \
     -H 'Content-Type: application/json'
```

And you will get a list of all available event groups:

```json
[
  {
    "id": "io.evitadb.cache",
    "name": "evitaDB - Cache",
    "description": "evitaDB events relating to internal database cache."
  },
  {
    "id": "io.evitadb.externalApi.graphql.instance",
    "name": "evitaDB - GraphQL API",
    "description": "evitaDB events relating to GraphQL API."
  },
  ... and more ...
]
```

The `id` property is the identification of the group, which you must specify when starting the recording. 
Other properties just describe the group.

#### Start recording

Recording is started by calling the following endpoint:

```shell
curl -k -X POST "http://localhost:5555/observability/startRecording" \
     -H 'Content-Type: application/json' \
     -d '{
           "allowedEvents": [
             "io.evitadb.query",
             "MemoryAllocation"
           ]
         }'
```

The `allowedEvents` property is an array of event group IDs that you want to include in the recording.

<Note type="info">

Only one recording can be running at a time. If you try to start a new recording while another one is running, 
the server will return an error.

</Note>

#### Stop recording

Recording can be stopped by calling the following endpoint:

```shell
curl -k -X POST "http://localhost:5555/observability/stopRecording" \
     -H 'Content-Type: application/json'
```

And you'll get similar response as when checking if the recording is running:

```json
{
  "taskType": "JfrRecorderTask",
  "taskName": "JFR recording",
  "taskId": "e36303cd-9e20-4e51-8972-2b98c9945dd4",
  "catalogName": null,
  "issued": "2024-07-22T17:20:28.157+02:00",
  "started": "2024-07-22T17:20:28.16+02:00",
  "finished": "2024-07-22T17:28:00.729+02:00",
  "progress": 100,
  "settings": {
    "allowedEvents": [
      "io.evitadb.query",
      "MemoryAllocation"
    ],
    "maxSizeInBytes": null,
    "maxAgeInSeconds": null
  },
  "result": {
    "fileId": "45cee61c-a233-4c36-ad3c-fa2325434bf6",
    "name": "jfr_recording_2024-07-22T17-20-28.157316739-02-00.jfr",
    "description": "JFR recording started at 2024-07-22T17:20:28.157619773+02:00 with events: [io.evitadb.query, MemoryAllocation].",
    "contentType": "application/octet-stream",
    "totalSizeInBytes": 180281,
    "created": "2024-07-22T17:20:28.158+02:00",
    "origin": [
      "JfrRecorderTask"
    ]
  },
  "publicExceptionMessage": null,
  "exceptionWithStackTrace": null
}
```

The main difference is that the result of a JFR recording task includes a file that you can download from the server or 
find directly in the server's export folder. The file is automatically removed after a configured period of time.

<Note type="info">

Since the produced JFR files are binary and therefore not directly readable (the JDK offers a terminal utility *JMC*,
but this is not ideal in terms of readability and orientation in the output), we plan to add a visualizer to evitaLab.

</Note>

## Tracing

As an additional tool to support observability, evitaDB offers support for tracing, which is implemented here
using [OpenTelemetry](https://opentelemetry.io/).
It offers to collect useful information on all queries to the database within a request made via any of the external
APIs. This data is exported from the database using
the [OTLP exporter](https://opentelemetry.io/docs/specs/otel/protocol/exporter/) and then forwarded to
the [OpenTelemetry collector](https://opentelemetry.io/docs/collector/). The latter can sort, reduce, and forward the
data to other applications that can be used to visualize it, for example (tools like [Grafana](https://grafana.com/)
using the [Tempo](https://grafana.com/oss/tempo/) module, [Jaeger](https://www.jaegertracing.io/),...). To maximize the
effect of tracing, it is also possible to use the so-called distributed tracing method, where not only data from evitaDB
related to the executed requests will be collected and forwarded within the published query data, but also data from
consumer applications that communicate with the database using the API.

Published information (`spans` using their `spanId`) can be aggregated using the same `traceId`, where a span is
referred to in [OpenTelemetry](https://opentelemetry.io/) terminology as one specific recorded piece of information from
any application with metadata about its processing, such as the duration of execution of a given action and any other
custom attributes. A `Span` can optionally have a parent span (`parent span`), which will take care of the processing (
decision processes about keeping or discarding parts of the `span` tree) and propagating resulting decisions from
parent `spans` to their children (`child spans`).

### Tracing settings within evitaDB

As with metrics, tracing requires the *observability* API to be enabled and configured in the evitaDB config and enabled
by default. To configure it, you need to specify the URL
of [OpenTelemetry collector](https://opentelemetry.io/docs/collector/) and also the protocol (HTTP, GRPC) that the data
will be sent over.

```yaml
Observability:
  enabled: ${api.endpoints.observability.enabled:true}
  host: ${api.endpoints.observability.host:":5555"}
  exposeOn: ${api.endpoints.observability.exposeOn:"localhost:5555"}
  tlsMode: ${api.endpoints.observability.tlsMode:FORCE_NO_TLS}
  tracing:
    serviceName: ${api.endpoints.observability.tracing.serviceName:evitaDB}
    endpoint: ${api.endpoints.observability.tracing.endpoint:null}
    protocol: ${api.endpoints.observability.tracing.protocol:grpc}
```

<LS to="j,c">

When using evitaDB drivers, it is possible to pass an instance of [OpenTelemetry](https://opentelemetry.io/) that is
used within the consumer application to the driver configuration class. This instance can only be one in the
application, and since drivers are only libraries targeted for use from applications, this approach was chosen instead
of reconfiguring the [OpenTelemetry collector](https://opentelemetry.io/docs/collector/) endpoint, which is consistent
with the approach of other autoinstrumentation libraries.

</LS>

### Connecting consumer applications

[OpenTelemetry](https://opentelemetry.io/) offers first-party libraries (for selected technologies with the ability to
auto-instrument within consumer applications) to provide tracing from relevant parts such as HTTP communication or
database operations. For most applications using either implicit support or additional libraries, no additional
configuration is required to enable tracing, including auto-promotion to other services. However, the aforementioned
automated approach limits the possibilities of adding custom traces, for which the use of a tracing SDK is required to
manually create and integrate them with additional information into the tracing tree. For evitaDB there is no such
library yet and therefore you need to manually configure [OpenTelemetry](https://opentelemetry.io/) including the
mentioned [OTLP exporter](https://opentelemetry.io/docs/specs/otel/protocol/exporter/) and set
up [Context propagation](https://opentelemetry.io/docs/concepts/context-propagation/) - for supported technologies you
can find instructions on the official [OpenTelemetry](https://opentelemetry.io/) website.

The official libraries offer `inject` and `extract` methods
on [Context](https://opentelemetry.io/docs/specs/otel/context/), which can be used to set (or extract) identifiers about
the current context into the open connection of the transport layer used (HTTP, gRPC,...). This approach is heavily
integrated into [OpenTelemetry](https://opentelemetry.io/) and is compatible across all the technologies it supports,
where the use of this library is very similar and thus not difficult to integrate into multiple services.

Internally, [OpenTelemetry](https://opentelemetry.io/) uses a `traceparent` value for context propagation across services, which may look 
like this: `00-d4cda95b652f4a1592b449d5929fda1b-6e0c63257de34c92-01`.

This consists of four parts:

- **00:** represents the version of `TraceContext`, nowadays this value is immutable,
- **d4cda95b652f4a1592b449d5929fda1b:** `traceId`,
- **6e0c63257de34c92:** `spanId` - respectively, may represent `parent-span-id`,
- **01:** sampling decision, i.e. whether this span and its descendants should be published (`01` means the span will be
  published, `00` means that it won't be published).

Available SDKs provide options for getting the current tracing context containing `traceId` and `spanId` across an
application, often methods on the Context class are named `current` or `active`.

<LS to="r,g">

To connect applications using REST and GraphQL APIs, the HTTP header `traceparent` must be sent over some form of open
HTTP connection to evitaDB. The before mentioned `inject` and `extract` methods insert the `traceparent` value into the
HTTP header (or extract it from the header from the current context).

</LS>

<LS to="j,c">

To connect applications using the gRPC API, you must send in the metadata sent with queries over the gRPC channel to
evitaDB. Our recommended method at this point is to use gRPC [Interceptor](https://grpc.io/docs/guides/interceptors/).
The aforementioned `inject` and `extract` methods insert the `traceparent` value into the gRPC Metadata (or extract it
from the header of the current context).

</LS>

<LS to="j">

evitaDB provides two essential methods for tracing purposes on the `TracingContext` interface, which is used both
internally and from external APIs. These methods include:

- executeWithinBlock: creates a parent `span` which, including any other exported traces inside the passed lambda
  function, will be implicitly logged and sent to
  the [OpenTelemetry collector](https://opentelemetry.io/docs/collector/),
- executeWithinBlockIfParentContextAvailable: method for tracing if the parent context is currently open (intentionally
  some internal traces not coming from external APIs are suppressed in this way).

</LS>

#### Inclusion of the `traceId` in the logs

The trace identifiers can be also used to group application log messages by traces and spans for easier debugging of
errors that happened during a specific request. This is done using [MDC](https://www.slf4j.org/manual.html#mdc)
support. evitaDB passes the trace and span identifiers to the MDC context under `traceId` and `spanId` names.

The specific usage depends on the used implementation of the [SLF4J](https://www.slf4j.org/) logging facade. For example
in [Logback](https://logback.qos.ch/index.html) this can be done using `%X{traceId}` and `%X{spanId}` patterns in the log pattern:

```xml
<encoder>
	<pattern>%d{HH:mm:ss.SSS} %-5level %logger{10} C:%X{traceId} R:%X{spanId} - %msg%n</pattern>
</encoder>
```

## Traffic recording

In addition to the observability tools mentioned above, evitaDB also offers the ability to record all incoming traffic 
to the server. This feature is useful for debugging and development purposes, as it allows you to play back the recorded
traffic and analyse the behaviour of the server in detail. The traffic recording feature is disabled by default and must
be enabled in the server [configuration](../operate/configure.md#traffic-recording-configuration).

These settings are recommended for local development:

```yaml
  trafficRecording:
    enabled: true
    sourceQueryTracking: true      
    trafficFlushIntervalInMilliseconds: 0
```

For test/staging environments, omit `trafficFlushIntervalInMilliseconds` and leave it at the default. If you enable
traffic logging in production, disable `sourceQueryTracking` as you won't normally need to access the query source code 
in production. In production you'll probably want to set a sampling rate using `trafficSamplingPercentage`.

Besides having access to the `Active Traffic Recording` tab in evitaLab, where you can list through all sessions, 
queries, mutations and entity fetches, you can also issue a traffic reporting task that will save the traffic data in 
a ZIP file and make it available for download. This file can be used for further analysis or to replay the traffic on 
different evitaDB instances.

The recorded traffic can be browsed and filtered in evitaLab and any query can be easily executed in the corresponding 
query console on the current dataset. Records can also be filtered by custom [labels](../query/header/label.md#label), 
traceIds or protocol types. You can easily isolate sets of traffic records that relate to a single business case, such 
as a single page rendering or a single API call.

## Reference documentation

<MDInclude>[Java Flight Recorder events](/documentation/user/en/operate/reference/jfr-events.md)</MDInclude>

<MDInclude>[Metrics](/documentation/user/en/operate/reference/metrics.md)</MDInclude>
