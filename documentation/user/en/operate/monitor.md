---
title: Monitor
perex: |
  evitaDB's monitoring facilities are designed to help you monitor running evitaDB instances as well as to help you
  optimise your application during development. All monitoring facilities are based on our operational experience and
  development of e-commerce projects.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'java'
---

**Work in progress**

This article will contain description of Evita monitoring facilities - would it be directly Prometheus or OpenTelemetry.
There should be also information how to log slow queries or see other problems within application (logging).
The functionality is not finalized - [see issue #18](https://github.com/FgForrest/evitaDB/issues/18).

## Logging

evitaDB uses the [SLF4J](https://www.slf4j.org/) logging facade for logging both application log messages and access log messages. By default
only application log messages are enabled, the access log messages must be explicitly [enabled in configuration](#access-log).

### Access log

If the `accessLog` property is set to `true` in the [configuration](configure.md#api-configuration), the server will log
access log messages for all APIs using the
[Slf4j](https://www.slf4j.org/) logging facade. These messages are logged at the `INFO` level and contain the `ACCESS_LOG`
marker which you can use to separate standard messages from access log messages.

Access log messages can be further categorized using `UNDERTOW_ACCESS_LOG` and `GRPC_ACCESS_LOG` markers. This is because
evitaDB uses [Undertow](https://undertow.io/) web server for REST and GraphQL APIs and separate web server
for [gRPC](https://grpc.io/). It might be sometimes useful to log these separately because even though they both
use the same log format, for example, gRPC doesn't support all properties as Undertow.

### Server Logback utilities

evitaDB server comes ready with several custom utilities for easier configuration of the custom logged data.

*Note:* These utilities are only available in evitaDB server because the rest of the evitaDB codebase
doesn't rely on a concrete implementation of the [Slf4j](https://www.slf4j.org/) logging facade.
If the evitaDB is used as embedded instance, the following tools are not available, but can be used as reference to
custom implementation in chosen framework.

#### Log filters

The basic utilities are two [Logback](https://logback.qos.ch/) filters ready-to-use to easily separate access log messages
from app log messages.

There is `io.evitadb.server.log.AccessLogFilter` filter to only log access log messages.
This filter can be used as follows:
```xml
<appender name="FILE" class="ch.qos.logback.core.FileAppender">
    <filter class="io.evitadb.server.log.AccessLogFilter"/>
    <file>/path/to/access.log</file>
    <encoder>
        <pattern>%msg%n</pattern>
    </encoder>
</appender>
```

There is also `io.evitadb.server.log.AppLogFilter` filter to only log standard log messages.
This filter can be used as follows:
```xml
<appender name="FILE" class="ch.qos.logback.core.FileAppender">
    <filter class="io.evitadb.server.log.AppLogFilter"/>
    <file>/evita/logs/evita_server.log</file>
    <encoder>
        <pattern>%date %level [%thread] %logger{10} [%file:%line] -%kvp- %msg%n</pattern>
    </encoder>
</appender>
```
This filter exists because when you enable access logs the log messages with the `ACCESS_LOG` marker aren't filtered out
by default.

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
        <filter class="io.evitadb.server.log.AppLogFilter" />
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

The evitaDB server provides endpoints for Kubernetes [readiness and liveness probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/). The liveness probe is also c
onfigured as [healthcheck](https://docs.docker.com/reference/dockerfile/#healthcheck) by default in our Docker image. By default the health check waits `30s` before it
starts checking the server health, for larger databases you may need to increase this value using environment variable 
`HEALTHCHECK_START_DELAY` so that they have enough time to be loaded into memory.

<Note type="warning">

<NoteTitle toggles="false">

##### When you change system API port don't forget to set `SYSTEM_API_PORT` environment variable
</NoteTitle>

The healthcheck in the Docker image is configured to use the default system API port, which is `5557`. If you change 
the port, the health check will immediately report an unhealthy container because it won't be able to reach the probe 
endpoint. You need to specify the new port using the `SYSTEM_API_PORT` environment variable of the Docker container.

</Note>

Both probes are available in the `system` API and are accessible at the following endpoints:

### Readiness probe

```shell
curl -k "http://localhost:5557/system/readiness" \
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
curl -k "http://localhost:5557/system/liveness" \
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

## Client and request identification

In order to monitor which requests each client executes against evitaDB, each client and each request can be identified by
a unique identifier. In this way, evitaDB calls can be grouped by requests and clients. This may be useful, for example,
to see if a particular client is executing queries optimally and not creating unnecessary duplicate queries.

Both identifiers are provided by the client itself. The client identifier is expected to be a constant for a particular
client, e.g. `Next.js application`, and will group together all calls to a evitaDB from this client.
The request identifier is expected to be a [UUID](https://en.wikipedia.org/wiki/Universally_unique_identifier)
but can be any string value, and will group together all evitaDB calls with this request identifier for a particular client.
The request definition (what a request identifier represents) is up to the client to decide, for example, a single request
for JavaScript client may group together all evitaDB calls for a single page render.

### Configuration

<LS to="e">

This mechanism is not part of an evitaQL language. Check documentation for your specific client for more information.

</LS>
<LS to="j">

If you are using the Java remote client, you are suggested to provide the `clientId` in
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/EvitaClientConfiguration.java</SourceClass>
for all requests. The `requestId` is then provided by wrapping your code in a lambda passed to `executeWithRequestId`
method on <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> interface.

<SourceCodeTabs langSpecificTabOnly local ignoreTest>

[Provide the client and request ids to the server](/documentation/user/en/operate/example/call-server-with-ids.java)

</SourceCodeTabs>

If you use embedded variant of evitaDB server there is no sense to provide `clientId` since there is only one client.
The `requestId` is then provided the same way as described above.

</LS>
<LS to="g">

To pass the request identification using GraphQL API, our GraphQL API utilizes [GraphQL extensions](https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md#request-parameters).
Therefore, to pass request identification information to the evitaDB, pass the following JSON object within the `extensions`
property of a GraphQL request:

```json
"clientContext": {
  "clientId": "Next.js application",
  "requestId": "05e620b2-5b40-4932-b585-bf3bb6bde4b3"
}
```

Both identifiers are optional.

</LS>
<LS to="r">

In order to pass request identification using REST API, our REST API utilizes [HTTP headers](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers).
Therefore, to pass request identification information to the evitaDB, pass the following HTTP headers:

```
X-EvitaDB-ClientID: Next.js application
X-EvitaDB-RequestID: 05e620b2-5b40-4932-b585-bf3bb6bde4b3
```

Both headers are optional.

</LS>

### Logging

These identifiers can be also used to group application log messages by clients and requests for easier debugging of
errors that happened during a specific request. This is done using [MDC](https://www.slf4j.org/manual.html#mdc)
support. evitaDB passes the client and request identifiers to the MDC context under `clientId` and `requestId` names.

The specific usage depends on the used implementation of the [SLF4J](https://www.slf4j.org/) logging facade. For example
in [Logback](https://logback.qos.ch/index.html) this can be done using `%X{clientId}` and `%X{requestId}` patterns in the log pattern:
```xml
<encoder>
    <pattern>%d{HH:mm:ss.SSS} %-5level %logger{10} C:%X{clientId} R:%X{requestId} - %msg%n</pattern>
</encoder>
```
