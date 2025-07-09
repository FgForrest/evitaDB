---
title: Query our dataset
perex: |
   This article will show you how to connect to our demo instance or spin up a demo instance on your own hardware and
   experiment with the evitaDB query language.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'java'
---

While it is much better to play with the schema and data that suits your own e-commerce use case, we believe that
many of you will want to test the Query API without too much work. Therefore, we have created a sample schema of
the virtual e-commerce store with data for more than a thousand products for you to play with right away.

There are two ways in which you can play with this data set:

1. simple but limited: [use our evitaDB.io server instance](#use-our-evitadbio-server-instance)
2. more difficult, but without limits: [run your own evitaDB server with our dataset](#run-your-own-evitadb-server-with-our-dataset)

## Use our evitaDB.io server instance

We host the demo dataset directly on the [evitadb.io](https://evitadb.io) site, but to make sure it works reliably for
all of you, we had to make it read-only. So you cannot make any changes to it. But you can still use it to test all our
web APIs and all supported drivers.

The next limitation is that the server is hosted on cheap shared infrastructure of
[Contabo hosting](https://contabo.com/en/vps/) (which has been known to buy old servers to provide low-cost hosting
services) with following specs:

![Server specs](assets/contabo-hosting.png)

If you experience slow responses, let us know and
[try evitaDB on your hardware instead](#run-your-own-evitadb-server-with-our-dataset).

You can access all our APIs on these addresses:

- `graphQL` API: [https://demo.evitadb.io:5555/gql/](https://demo.evitadb.io:5555/gql/evita)
   - you can explore GraphQL API with our dataset using [online GraphiQL editor](https://cloud.hasura.io/public/graphiql?endpoint=https%3A%2F%2Fdemo.evitadb.io%3A5555%2Fgql%2Fevita) (for more, visit [GraphQL](../use/connectors/graphql.md) documentation)
- `REST` API: [https://demo.evitadb.io:5555/rest/](https://demo.evitadb.io:5555/rest/evita)
   - you can fetch OpenAPI schema just be sending GET request to this URL (for more, visit [REST](../use/connectors/rest.md) documentation)
- `gRPC` API: [https://demo.evitadb.io:5555/](https://demo.evitadb.io:5555/)

## Run your own evitaDB server with our dataset

This option requires more work, but you will have control over the performance, and you will be able to modify any data
in the set. To access the dataset on your hardware, you need to:

1. [download the archive with the dataset](https://evitadb.io/download/evita-demo-dataset.zip)
   ```shell
   wget https://evitadb.io/download/evita-demo-dataset.zip
   ```

2. unzip the contents to the `data` folder
   ```shell
   unzip -d data evita-demo-dataset.zip
   ```

3. pull the evitaDB docker image
   ```shell
   docker pull index.docker.io/evitadb/evitadb:latest
   ```
4. start the evitaDB server
   ```shell
   docker run --name evitadb -i --net=host \          
          -v "./data:/evita/data" \
          index.docker.io/evitadb/evitadb:latest

   # there is open issue https://github.com/docker/roadmap/issues/238 for Windows / Mac OS
   # and you need to open ports manually and propagate host IP address to the container
   docker run --name evitadb -i -p 5555:5555 \        
          -v "./data:/evita/data" \
          index.docker.io/evitadb/evitadb:latest
   ```

When this procedure is completed you should see the similar output in the console:

```plain

            _ _        ____  ____
  _____   _(_) |_ __ _|  _ \| __ )
 / _ \ \ / / | __/ _` | | | |  _ \
|  __/\ V /| | || (_| | |_| | |_) |
 \___| \_/ |_|\__\__,_|____/|____/

beta build 2025.5.1 (keep calm and report bugs 😉)
Visit us at: https://evitadb.io

19:45:37.088 INFO  i.e.s.c.DefaultCatalogPersistenceService - Catalog `evita` is being loaded and  it contains:
	- Group (10)
	- ShippingMethod (0)
	- ObsoleteProduct (0)
	- Category (36)
	- ParameterValue (3319)
	- ProductBundle (0)
	- Product (4223)
	- PickupPoint (9747)
	- Brand (57)
	- AdjustedPricePolicy (2)
	- ParameterGroup (5)
	- Parameter (113)
	- PaymentMethod (0)
	- Tag (18)
	- PriceList (20)
	- Stock (1)
19:45:37.091 INFO  i.e.s.c.DefaultCatalogPersistenceService - Catalog loaded in 0.054817170s
19:45:41.363 INFO  i.e.c.Evita - Catalog evita fully loaded.
Root CA Certificate fingerprint:        43:51:C6:A0:9C:21:9A:8A:BE:18:2B:53:93:CF:4E:1A:CE:7F:FF:B0:16:99:A5:4C:22:52:25:09:72:6F:5C:E3
API `graphQL` listening on              https://localhost:5555/gql/
API `rest` listening on                 https://localhost:5555/rest/
API `gRPC` listening on                 https://localhost:5555/
API `system` listening on               http://localhost:5555/system/
   - server certificate served at:      http://localhost:5555/system/evitaDB-CA-selfSigned.crt
   - client certificate served at:      http://localhost:5555/system/client.crt
   - client private key served at:      http://localhost:5555/system/client.key

************************* WARNING!!! *************************
You use mTLS with automatically generated client certificate.
This is not safe for production environments!
Supply the certificate for production manually and set `useGeneratedCertificate` to false.
************************* WARNING!!! *************************
```

It means that your evitaDB server is up and running, and also that it has loaded the `evita` catalog dataset with some
thousands of products.

<LS to="e,j">

## Connect the Java client

Open your Java IDE and add the following dependency to your project:

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_java_driver</artifactId>
    <version>2025.5.1</version>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_java_driver:2025.5.1'
```
</CodeTabsBlock>
</CodeTabs>

Then, create an <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
instance:

<SourceCodeTabs langSpecificTabOnly local>
[Connect the demo server](/documentation/user/en/get-started/example/connect-demo-server.java)
</SourceCodeTabs>

After that you can create a new session and try any of the evitaQL queries described in
[the reference documentation](../query/basics.md):

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Query the demo server](/documentation/user/en/get-started/example/query-demo-server.java)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Do you need more instructions?

</NoteTitle>

For complete instructions on setting up a Java client, see [the Java drivers chapter](../use/connectors/java.md).
If you need more hints for querying the data, try [the query API chapter](../use/query-api.md).

</Note>

</LS>

<LS to="c">

## Connect the C# client

Open your .NET IDE and create an instance of <SourceClass>EvitaDB.Client/EvitaClient.cs</SourceClass>:

<SourceCodeTabs langSpecificTabOnly local>
[Connect the demo server](/documentation/user/en/get-started/example/connect-demo-server.cs)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Why is EvitaClient initialized with static async method instead of constructor?

</NoteTitle>

In the initialization process, the client needs to get `server-name` from evitaDB's *system* endpoint and in case of
usage of generated self-signed certificates, it needs to get the certificate from the server. Since both of these operations
are asynchronous in .NET, we decided to make the initialization process asynchronous as well. These asynchronous calls
cannot be done  in the constructor (without blocking the main application thread, which could cause serious problems in
your application), so we decided to use static async method instead.
</Note>

After that you can create a new session and try any of the evitaQL queries described in
[the reference documentation](../query/basics.md):

<SourceCodeTabs requires="/documentation/user/en/get-started/example/connect-demo-server.java" langSpecificTabOnly>

[Query the demo server](/documentation/user/en/get-started/example/query-demo-server.cs)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Do you need more instructions?

</NoteTitle>

For complete instructions on setting up a C# client, see [the C# drivers chapter](../use/connectors/c-sharp.md).
If you need more hints for querying the data, try [the query API chapter](../use/query-api.md).
</Note>

</LS>

<LS to="g">

## Connect to the GraphQL API

Open your [GraphQL client](../use/connectors/graphql.md#recommended-ides) of choice and enter
the URL of the catalog data API of our demo catalog `https://demo.evitadb.io:5555/gql/evita`.

After that you can send GraphQL requests to that URL and try any of the GraphQL queries described in
[the reference documentation](../query/basics.md):

<SourceCodeTabs langSpecificTabOnly>

[Query the demo server](/documentation/user/en/get-started/example/query-demo-server.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Do you need more instructions?

</NoteTitle>

For complete instructions on setting up a GraphQL client, see [the GraphQL drivers chapter](../use/connectors/graphql.md).
If you need more hints for querying the data, try [the query API chapter](../use/query-api.md).

</Note>

</LS>

<LS to="r">

## Connect to the REST API

Open your [REST client](../use/connectors/rest.md#recommended-ides) of choice and enter
the basic URL of the catalog data API of our demo catalog `https://demo.evitadb.io:5555/rest/evita`.

After that you can send REST requests to different variants of that URL and try any of the REST queries described in
[the reference documentation](../query/basics.md):

<SourceCodeTabs langSpecificTabOnly>

[Query the demo server](/documentation/user/en/get-started/example/query-demo-server.rest)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Do you need more instructions?

</NoteTitle>

For complete instructions on setting up a REST client, see [the REST drivers chapter](../use/connectors/rest.md).
If you need more hints for querying the data, try [the query API chapter](../use/query-api.md).

</Note>

</LS>
