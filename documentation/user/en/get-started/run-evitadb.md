---
title: Run evitaDB
perex: If you are new to evitaDB, try these baby steps to get your own server up and running.
date: '1.3.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

evitaDB is a [Java application](https://openjdk.org/), and you can run it as an
[embedded database](../use/connectors/java.md) in any Java application or as 
[a separate service](../operate/run.md) connected to applications via 
the HTTPS protocol using one of the provided web APIs.

<LanguageSpecific to="java">

<Note type="question">

<NoteTitle toggles="true">

##### What platforms are supported?
</NoteTitle>

Java applications support multiple platforms depending on the 
[JRE/JDK vendor](https://wiki.openjdk.org/display/Build/Supported+Build+Platforms). All major hardware
architectures (x86_64, ARM64) and operating systems (Linux, MacOS, Windows) are supported. Due to the size of our
team, we regularly test evitaDB only on the Linux AMD64 platform (which you can also use on Windows thanks to the
[Windows Linux Subsystem](https://learn.microsoft.com/en-us/windows/wsl/install)). The performance can be worse,
and you may experience minor problems when running evitaDB on other (non-Linux) environments. Please report any bugs
you might encounter, and we'll try to fix them as soon as possible.
</Note>

<Note type="question">

<NoteTitle toggles="true">

##### What are the pros &amp; cons of running embedded evitaDB?
</NoteTitle>

Embedded evitaDB will be faster, because you can work directly with the data objects fetched from disk, and you don't 
need to go through several translation layers required for remote API access. You could also disable all standard APIs 
and avoid running an embedded HTTP server, which takes its toll on system load.

The downside is that your application heap will be cluttered with large evitaDB data structures of in-memory indexes,
which makes it harder to find memory leaks in your application. We recommend to use embedded evitaDB for
[writing tests](../use/api/write-tests.md), which greatly simplifies integration testing with evitaDB and allows for 
fast and easy setup / teardown of the test data.
</Note>

<Note type="info">
This introductory article describes how to run evitaDB in embedded mode. If you prefer to run evitaDB in client & server 
mode, please refer to the separate chapters describing [how to run evitaDB in Docker](../operate/run.md) and 
[how to set up EvitaClient](../use/connectors/java.md).
</Note>

### Package evitaDB in your application

To integrate evitaDB into your project, use the following steps:

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_db</artifactId>
    <version>0.8.ALPHA</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_db:0.8.ALPHA'
```
</CodeTabsBlock>
</CodeTabs>

### Start evitaDB server

To start the evitaDB server, you need to instantiate <SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass>,
and keep the reference around so that your application can call it when needed.
The <SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass> is expensive because it loads all
the indexes into memory when it starts.

<SourceCodeTabs>
[Example of web API enabling in Java](/documentation/user/en/get-started/example/server-startup.java)
</SourceCodeTabs>

<Note type="warning">
Don't forget to ensure that the `close` method is called before you release the reference to the
<SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass> instance. If you don't do this,
your file handlers will leak, and you may also lose any updates cached in caches, thus losing some
recent updates to the database.
</Note>

### Enabling evitaDB web APIs

If you want evitaDB to be able to open its web APIs (you still need to [configure this](../operate/configure.md)), you
also need to add dependencies on these API variants. If you don't do this, you will get a 
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/exception/ExternalApiInternalError.java</SourceClass>
exception when you enable the corresponding API in evitaDB's configuration.

#### gRPC

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_external_api_grpc</artifactId>
    <version>0.8.ALPHA</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_external_api_grpc:0.8.ALPHA'
```
</CodeTabsBlock>
</CodeTabs>

#### GraphQL

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_external_api_graphql</artifactId>
    <version>0.8.ALPHA</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_external_api_graphql:0.8.ALPHA'
```
</CodeTabsBlock>
</CodeTabs>

#### REST

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_external_api_rest</artifactId>
    <version>0.8.ALPHA</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_external_api_rest:0.8.ALPHA'
```
</CodeTabsBlock>
</CodeTabs>

### Start web API HTTP server

The evitaDB web APIs are maintained by a separate class <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/ExternalApiServer.java</SourceClass>.
You must instantiate and configure this class and pass it a reference to the
<SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass> instance:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/server-startup.java">    
[Example of web API startup in Java](/documentation/user/en/get-started/example/api-startup.java)
</SourceCodeTabs>

<Note type="warning">
Don't forget to close the APIs when your application ends by calling the `close` method on the
the <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/ExternalApiServer.java</SourceClass>
instance. One of the options is to listen to Java process termination:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/api-startup.java">    
[Example of web API teardown in Java](/documentation/user/en/get-started/example/server-teardown.java)
</SourceCodeTabs>

</Note>

You should see the following information logged to the console when you start the API web server:

```plain
Root CA Certificate fingerprint:        CERTIFICATE AUTHORITY FINGERPRINT
API `gRPC` listening on                 https://your-domain:5556/
API `graphQL` listening on              https://your-domain:5555/gql/
API `rest` listening on                 https://your-domain:5555/rest/
API `system` listening on               http://your-domain:5557/system/
```

</LanguageSpecific>
<LanguageSpecific to="evitaql,graphql,rest,csharp">

### Install Docker

Before we get started, you need to install Docker. You can find instructions for your platform in the
[Docker documentation](https://docs.docker.com/get-docker/).

### Pull and run image

Once Docker is installed, you need to grab the evitaDB image from
[Docker Hub](https://hub.docker.com/repository/docker/evitadb/evitadb/general) and create a container.
You can do both in one command using `docker run`. This is the easiest way to run evitaDB for testing purposes:

```shell
# run on foreground, destroy container after exit, use host ports without NAT
docker run --name evitadb -i --rm --net=host \ 
index.docker.io/evitadb/evitadb:latest
```

When you start the evitaDB server you should see the following information in the console output:

```plain
            _ _        ____  ____  
  _____   _(_) |_ __ _|  _ \| __ ) 
 / _ \ \ / / | __/ _` | | | |  _ \ 
|  __/\ V /| | || (_| | |_| | |_) |
 \___| \_/ |_|\__\__,_|____/|____/ 

You'll see some version here
Visit us at: https://evitadb.io

Root CA Certificate fingerprint:        You'll see some fingerprint here
API `graphQL` listening on              https://your-server:5555/gql/
API `rest` listening on                 https://your-server:5555/rest/
API `gRPC` listening on                 https://your-server:5556/
API `system` listening on               http://your-server:5557/system/
```

More information about running evitaDB Server in Docker is available in the [separate chapter](../operate/run.md).

</LanguageSpecific>

## What's next?

You may want to [create your first database](create-first-database.md) or [play with our dataset](query-our-dataset.md). 