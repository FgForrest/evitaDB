---
title: Run evitaDB
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
published: false
---

evitaDB is a [Java application](https://openjdk.org/), and you can run it as an
[embedded database](#run-embedded-in-you-application) in any Java application or as 
[a separate service](#run-as-service-inside-docker) that connects to applications via 
the HTTPS protocol using one of the provided web APIs.

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

## Run as service inside Docker

The Docker image is based on RedHat JDK / Linux (see <SourceClass>docker/Dockerfile</SourceClass>) base
image (Fedora family) and is published to [Docker Hub](https://hub.docker.com/repository/docker/evitadb/evitadb/general).

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

<Note type="info">

<NoteTitle toggles="true">

##### What do the arguments of the command mean?
</NoteTitle>

<Table>
    <Thead>
        <Tr>
            <Th>Argument</Th>
            <Th>Description</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>`--name`</Td>
            <Td>
                gives a container a name `evitadb`.

                if you don't give the container a name, Docker will generate a random name for it.
            </Td>
        </Tr>
        <Tr>
            <Td>`-i`</Td>
            <Td>
                keeps STDIN open - the container will run in the foreground, and you'll see the container's 
                standard/error output in the console. in the console, and you can stop the container by sending it 
                a terminal signal (usually the the key combination `Ctrl`+`C`, or `Command`+`.` on MacOS).
            </Td>
        </Tr>
        <Tr>
            <Td>`--rm`</Td>
            <Td>
                removes the file system (data) created by evitaDB, by using this command there will be nothing left on
                your system when evitaDB is stopped, this argument is especially useful for testing purposes
            </Td>
        </Tr>
        <Tr>
            <Td>`--net=host`</Td>
            <Td>
            instructs Docker to use directly host system network stack, this way evitaDB behaves as if it runs directly 
            on the host system network-wise, if the port configured in evitaDB configuration is already used on the 
            system, Evita fails to set up appropriate web API (see next chapter for port remapping or 
            [evitaDB configuration](../operate/configure.md) for specifying open ports)
            </Td>
        </Tr>
    </Tbody>
</Table>
</Note>

#### Open / remap ports

The simplified command shares the network with the host, which is not always the best approach. You can selectively 
open/re-mapping ports opened inside the Docker container in the following way:

```shell
# run on foreground, destroy container after exit, use host ports without NAT
docker run --name evitadb -i --rm \
-p 5555:5555 \ 
-p 5556:5556 \ 
-p 5557:5557 \  
index.docker.io/evitadb/evitadb:latest
```

<Table>
    <Thead>
        <Tr>
            <Th>Default port</Th>
            <Th>Service</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>`5555`</Td>
            <Td>serves end-user GraphQL / REST APIs on `/gql` and `/rest` sub-paths</Td>
        </Tr>
        <Tr>
            <Td>`5556`</Td>
            <Td>serves system gRPC API</Td>
        </Tr>
        <Tr>
            <Td>`5557`</Td>
            <Td>provides access to the TLS/SSL certificate (see [configuring TLS/SSL](../operate/tls.md))</Td>
        </Tr>
    </Tbody>
</Table>

<Note type="info">

<NoteTitle toggles="true">

##### What do the arguments of the command mean?
</NoteTitle>

<Table>
    <Thead>
        <Tr>
            <Th>Argument</Th>
            <Th>Description</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>`-p`</Td>
            <Td>
            port remapping in the format `host port`:`container port`, so you could remap default ports opened by 
            evitaDB inside the container to different ports on the host system.
            </Td>
        </Tr>
    </Tbody>
</Table>
</Note>

### Configure database persistent storage

For regular use, you'll probably want to specify the folder for storing evitaDB data and easily access it in the host's
file system structure. You can specify any (initially) empty host folder to store the evitaDB database files:

```shell
## run on foreground, use host ports without NAT, specify your own data directory
docker run -i --net=host \
-v "__data_dir__:/evita/data" \
index.docker.io/evitadb/evitadb:latest
```

<Note type="info">
You will need to replace `__data_dir__` with the path to the folder on your host system.
</Note>

The folder begins to fill with data as you create your first catalogs and collections of entities. The organisation of
folder will look like this:

```
├── catalogA
├── catalogB
└── catalogC
```

Each folder will contain one or more files representing the contents of the catalog.

<Note type="question">

<NoteTitle toggles="true">

##### Do you want to know more about DB files, backup and restore?
</NoteTitle>

More details about the folder structure and file contents are documented in the 
[back-up and restore chapter](../operate/backup-restore.md).
</Note>

### Configure the evitaDB in the container

You can control all evitaDB settings in the container using environment variables specified in `run` command:

```shell
## run interactively, use host ports without NAT, specify your own data directory and additional configuration options
docker run --name evitadb -i --net=host \
-v "__data_dir__:/evita/data" \
-e "EVITA_JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5000" \
-e "EVITA_ARGS=-Dapi.endpoints.graphQL.enabled=false -Dapi.endpoints.grpc.enabled=false" \
index.docker.io/evitadb/evitadb:latest
```

<Note type="info">
The example command above runs evitaDB and opens Java debug on port `5000`. It also disables the GraphQL and gRPC web
APIs and leaves only the REST API running (by default evitaDB starts all available APIs).
</Note>

You can take advantage of all the following variables:

<Table caption="List of all configurable environment variables">
    <Thead>
        <Tr>
            <Th>Variable name</Th>
            <Th>Meaning</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>**`EVITA_CONFIG_FILE`**</Td>
            <Td>path to configuration file, default: `/evita/conf/evita-configuration.yaml`</Td>
        </Tr>
        <Tr>
            <Td>**`EVITA_STORAGE_DIR`**</Td>
            <Td>path to storage directory, default: `/evita/data`</Td>
        </Tr>
        <Tr>
            <Td>**`EVITA_JAVA_OPTS`**</Td>
            <Td>Java commandline arguments 
            (list of basic arguments [can be found here](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#overview-of-java-options)),
            default: none (empty string)</Td>
        </Tr>
        <Tr>
            <Td>**`EVITA_ARGS`**</Td>
            <Td>
            evitaDB server command-line arguments, default: none (empty string)

            the list of all available arguments is given in the configuration file:
            <SourceClass>docker/evita-configuration.yaml</SourceClass>
            the format of the argument is visible in the variables `${argument_name:default_value}`

            to pass an argument to a Java aplication, you need to prefix it with `-D`, the correct argument name for
            variable `${storage.lockTimeoutSeconds:50}` is `-Dstorage.lockTimeoutSeconds=90`
            </Td>
        </Tr>
    </Tbody>
</Table>

<Note type="info">

<NoteTitle toggles="true">

##### Alternative way to configure evitaDB
</NoteTitle>

You can also provide the entire configuration YAML file using a special volume in the following way:
</Note>

```shell
## run interactively, destroy container after exit, use host ports without NAT, specify your own data directory and configuration file
docker run --name evitadb -i --net=host \
-v "__config_file__:/evita/conf/evita-configuration.yaml" \ 
-v "__data_dir__:/evita/data" \
index.docker.io/evitadb/evitadb:latest
```

You need to replace `__config_file__` with the path to the YAML file on the host file system.

<Note type="info">
The contents should match the default configuration file 
<SourceClass>docker/evita-configuration.yaml</SourceClass>, but you can specify constants instead 
of variables in certain settings.
</Note>

### Check the container status

You can check the container status by running the `docker ps' command, and you'll see similar output:

```shell
host@username:~ $ docker ps
CONTAINER ID   IMAGE                    COMMAND            CREATED         STATUS         PORTS     NAMES
0e4483f9c32e   evitadb/evitadb:latest   "/entrypoint.sh"   7 seconds ago   Up 6 seconds             evitadb
```

<Note type="info">
If you are using the host network stack (`--net=host`), you won't see any ports in the output. If you use [remap/open ports](#open--remap-ports)
you will also see the ports configuration here.
</Note>

### Control logging

evitaDB uses the [Slf4j](https://www.slf4j.org/) logging facade with [Logback](https://logback.qos.ch/) implementation, but
you're free to change this. When you start the evitaDB server you should see the following information in the console output:

```
             _ _        ____  ____  
   _____   _(_) |_ __ _|  _ \| __ ) 
  / _ \ \ / / | __/ _` | | | |  _ \ 
 |  __/\ V /| | || (_| | |_| | |_) |
  \___| \_/ |_|\__\__,_|____/|____/ 
                                    
alpha build 0.5-SNAPSHOT
https://evitadb.io

14:33:02.742 INFO  o.j.threads - JBoss Threads version 3.5.0.Final
```

The default logback configuration is defined in a file <SourceClass>evita_server/src/main/resources/logback.xml</SourceClass>.

You can completely override the default logback configuration by providing your own
[logback configuration file](https://logback.qos.ch/manual/configuration.html#syntax) in a volume:

```shell
## run interactively, destroy container after exit, use host ports without NAT, specify your own data directory and configuration file
docker run --name evitadb -i --net=host \
-v "__config_file__:/evita/conf/evita-configuration.yaml" \ 
-v "__data_dir__:/evita/data" \
-v "__path_to_log_file__:/evita/logback.xml" \
index.docker.io/evitadb/evitadb:latest
```

You need to replace `__path_to_log_file__` with the path to your logback configuration file.

### Restart an existing container

The running (and named) container can be stopped and restarted using the following commands:

```shell
# shut the container down
docker stop evitadb
# bring the container up in frontend mode
docker start evitadb
# bring the container up in daemon mode
docker start evitadb -d
```

Alternatively, you can use `docker ps` to get the ID of a running container and restart it using the
[UUID short identifier](https://docs.docker.com/engine/reference/run/#name---name):

```shell
docker start b0c7b140c6a7
```

### Docker Compose

If you want to use evitaDB in orchestration with other services or your own Dockerised application, you can
evitaDB in the Docker compose file. The basic configuration could look like this:

```yaml
version: "3.7"
services:
  evita:
    image: index.docker.io/evitadb/evitadb:latest  
    environment:
      - EVITA_JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5000
    volumes:
      - ./path/toYourDataDirectory:/evita/data
    ports:
      - 5000:5000
      - 5555:5555
      - 5556:5556
      - 5557:5557
```

All previously documented options for using Docker apply to Docker Compose:

- use [environment variables](#configure-the-evitadb-in-the-container) to configure evitaDB
- use [volumes](#configure-database-persistent-storage) to set the data folder
- use [ports](#open--remap-ports) for mapping ports in the docker composition

## Run embedded in your application

evitaDB can be embedded into any Java application.

### Package evitaDB in your application

To integrate evitaDB into your project, use the following steps:

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_db</artifactId>
    <version>0.5-SNAPSHOT</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_db:0.5-SNAPSHOT'
```
</CodeTabsBlock>
</CodeTabs>

### Start evitaDB server

To start the evitaDB server, you need to instantiate <SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass>,
and keep the reference around so that your application can call it when needed.
The <SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass> is expensive because it loads all
the indexes into memory when it starts.

<SourceCodeTabs requires="docs/blog/en/examples/client-setup">
[Example of web API enabling in Java](docs/user/en/get-started/example/server-startup.java)
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
    <version>0.5-SNAPSHOT</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_external_api_grpc:0.5-SNAPSHOT'
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
    <version>0.5-SNAPSHOT</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_external_api_graphql:0.5-SNAPSHOT'
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
    <version>0.5-SNAPSHOT</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_external_api_rest:0.5-SNAPSHOT'
```
</CodeTabsBlock>
</CodeTabs>

### Start web API HTTP server

The evitaDB web APIs are maintained by a separate class <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/ExternalApiServer.java</SourceClass>.
You must instantiate and configure this class and pass it a reference to the
<SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass> instance:

<SourceCodeTabs>    
[Example of web API startup in Java](docs/user/en/get-started/example/api-startup.java)
</SourceCodeTabs>

<Note type="warning">
Don't forget to close the APIs when your application ends by calling the `close` method on the
the <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/ExternalApiServer.java</SourceClass>
instance. One of the options is to listen to Java process termination:

```java
Runtime.getRuntime().addShutdownHook(new Thread(externalApiServer::close));
```
</Note>

You should see the following information logged to the console when you start the API web server:

```
14:33:02.999 INFO  org.xnio - XNIO version 3.8.8.Final
14:33:03.006 INFO  o.xnio.nio - XNIO NIO Implementation Version 3.8.8.Final
14:33:03.106 INFO  i.e.e.h.ExternalApiServer - API graphQL listening on https://0.0.0.0:5555/gql/
14:33:03.129 INFO  i.e.e.h.ExternalApiServer - API rest listening on https://0.0.0.0:5555/rest/
14:33:03.148 INFO  i.e.e.h.ExternalApiServer - API gRPC listening on https://0.0.0.0:5556/
14:33:03.148 INFO  i.undertow - starting server: Undertow - 2.3.0.Final
```

<Note type="info">
You can see all enabled web APIs with the URLs they listen on. The IP
address `0.0.0.0` is used instead of `127.0.0.1` (i.e. localhost) to catch all traffic on localhost to the specified
ports. You can safely replace it with `https://localhost:5555/gql/` in your browser. This address has been tested to
work correctly in the Docker environment. However, you can use your own IPs or hosts
in [configuration](../operate/configure.md).
</Note>
