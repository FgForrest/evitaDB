---
title: Create first database
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
published: false
---

This 10-minutes introduction will guide you through creating a sample CRUD application for a simplified e-commerce
catalog from scratch.

## Prerequisites

1. [start evitaDB in Docker container](run-evitadb.md#run-as-service-inside-docker)
2. clone sample application repository:
    ```bash
    git clone https://github.com/FgForrest/evitaDB-Sample-CRUD-App.git
    ```
3. open the project in your favourite IDE
4. build the project using Maven
   ```bash
   mvn package
   ```

If you feel the need to check the final example implementation, you may scroll down [to the example implementation](#example-implementation).

<Note type="question">

<NoteTitle toggles="true">

##### Do you want to play interactively with evitaDB?
</NoteTitle>

Actually, you can. In the same example application:

1. checkout the branch `interactive-example`
2. build the project using Maven
   ```bash
   mvn package
   ```
3. run the application
   ```bash
   java -jar target/evita-crud.jar
   ```

   You can also use our predefined shell script `run.sh` in the root of the repository on Linux to simplify running both
   Docker container and the compiled application.

###### Do you want to make breakpoints and play interactively with our example application?

You may configure your IDE with remote debugger on port `8000`. In IntelliJ Idea it would look like this:

![Remote debugger for example application](/www/evita/docs/user/en/get-started/assets/intellij-idea-debugger.png)

</Note>

## Create the evitaDB client

The evitaDB client is created automatically when the example application starts in a following way:

<CodeTabs>
<CodeTabsBlock>

```java
new EvitaClient(
	EvitaClientConfiguration.builder()
		.host("localhost")
		.port(5556)
		.build()
);
```

</CodeTabsBlock>
</CodeTabs>

The source of this action is available in
<SourceClass>[CreateEvitaClient](https://github.com/FgForrest/evitaDB-Sample-CRUD-App/blob/main/src/main/java/io/evitadb/example/crud/api/CreateEvitaClient.java)</SourceClass>.

More details about <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
are stated in [Java connector chapter](../use/connectors/java.md).

## Define schema for your catalog

When you start the application for the first time, evitaDB will be completely empty. You need first to define
a new <Term document="docs/user/en/index.md">catalog</Term> for your data.

```
shell:>create-catalog mycatalog
```

## Open session to catalog

## Insert your first entity

## Create small dataset

## List existing entities

## Get contents of the entity

## Update any of existing entities

## Delete any of existing entities

## What's next?