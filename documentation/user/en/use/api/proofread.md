## GraphQL client setup

In order to consume the change capture streams, you need to set up a GraphQL client to send subscription
requests to the server via WebSockets using the
[GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md) protocol.
The WebSocket URLs are the same as for the query/mutation requests.

Each [API instance](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) provides specific
subscriptions for the API instance domain (described below); however, all API instances provide certain CDC
subscriptions.

A notable aspect of our GraphQL implementation is that each subscription is available in two versions:

- typed
- untyped

This exists because the GraphQL specification requires the client to specify all desired output fields of each
subscription, which can be quite cumbersome for CDC streams. There are tens of mutation implementations that
can be sent by the server. In a traditional GraphQL API, the client would have to specify all the fields of all
mutation implementations. This can be useful when the filtering strategy focuses only on a specific set of
mutation types; however, the client may need to support a wide range of mutations or even all of them.
Therefore, we have implemented the above-mentioned two versions of each subscription.

The typed version fully complies with the GraphQL specification and requires the client to specify all desired
output fields for each mutation type (although there are some restrictive unions).
The untyped version exposes the `body` as a generic `Object`.
This way the client receives all mutation types with all their data through a single output field. This comes
with obvious drawbacks: the client is responsible for mapping the JSON object and extracting the desired data.
Use this option only if the client really needs all the data.

---

## REST client setup

In order to consume the change capture streams, you need to set up a WebSocket client to send subscription
requests to the server using our
[custom WebSocket protocol](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md) based on the
[GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md) protocol.
The WebSocket URLs are the same as for the query/mutation requests.

<Note type="info">

<NoteTitle toggles="true">

##### REST over WebSocket protocol

</NoteTitle>

The OpenAPI specification doesn't define any standard for real-time update APIs, nor is it possible to
document one within the base specification. Therefore, we decided to create a
[custom WebSocket protocol](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md) based on the
[GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md) protocol.
Although the base OpenAPI specification doesn't allow us to directly document the custom protocol, for now we
have included the CDC types in the OpenAPI specification so that there is at least a solid foundation for client
developers (e.g., mutation objects, CDC event objects, etc.).

</Note>

---

The engine-level capture stream accepts
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCaptureRequest.java</SourceClass>
for creating the CDC stream. Clients then receive
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass>
instances representing the changes made to the engine.

---

The engine-level capture stream is available in the system API via the `/rest/system/change-captures` endpoint.

Setup is straightforward:
1. open the WebSocket connection by sending a `GET` request with a connection upgrade,
2. send a `connection_init` message within the WebSocket connection,
3. send a `subscribe` message within the WebSocket connection with a `ChangeSystemCaptureRequest` defining the
   filtering strategy (as specified in the
   [WebSocket specification](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md)).

The CDC stream will now send `ChangeSystemCapture` objects wrapped into `next` messages to the client.

Example of setting up the engine change capture in REST over WebSocket API:

<SourceAlternativeTabs variants="rest">

[Setting up a minimal engine change capture](/documentation/user/en/use/api/example/engine-change-capture-rest.json)

</SourceAlternativeTabs>

The subscriber will start receiving change events as soon as they occur in the engine. The subscriber's
`Complete` method is never called since the change stream is infinite.

---

The engine-level capture stream allows clients to subscribe to `ChangeSystemCapture` (or
`GenericChangeSystemCapture`, based on the chosen subscription type) instances representing changes in the
engine.

The request allows you to specify the following parameters:

<dl>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change
    stream will start from the next version of the catalogue (i.e., the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not
    specified, the change stream will start from the first mutation of the specified version. The index allows you to
    precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
</dl>

Engine capture events are represented by the `ChangeSystemCapture` (or `GenericChangeSystemCapture`, based on the
chosen subscription type) object, which contains the following information:

<dl>
  <dt>long `version`</dt>
  <dd>
    The version of the evitaDB where the mutation occurs.
  </dd>
  <dt>int `index`</dt>
  <dd>
    The index of the mutation within the same transaction. Index `0` is always an infrastructure mutation of type
    <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Classification of the mutation, defined by the following enumeration:
    <ul>
        <li>`UPSERT` - Create or update operation. If there was data with such identity before, it was updated. If not, it was created.</li>
        <li>`REMOVE` - Remove operation — i.e., there was data with such identity before, and it was removed.</li>
        <li>`TRANSACTION` - Delimiting operation signaling the beginning of a transaction.</li>
    </ul>
  </dd>
  <dt>`EngineMutationUnion` `body`</dt>
  <dd>
    Body of the operation.
  </dd>
</dl>

### How to set up a new engine change capture

The engine-level capture stream is available in the system API via the following subscription types:

- `onSystemChange`
- `onSystemChangeUntyped`

The setup is straightforward: define one subscription with the desired parameters and subscribe to the stream via
the WebSocket protocol. The WebSocket stream will then send change events to the client based on the defined
output.

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Setting up a minimal engine change capture](/documentation/user/en/use/api/example/engine-change-capture-graphql.graphql)

</SourceCodeTabs>

The subscriber will start receiving change events as soon as they occur in the engine. The subscriber's
`Complete` method is never called since the change stream is infinite.

---

The catalogue-level capture stream is available in the catalog API via the `/rest/{catalogName}/change-captures` endpoint.

Setup is straightforward:
1. open the WebSocket connection by sending a `GET` request with a connection upgrade,
2. send a `connection_init` message within the WebSocket connection,
3. send a `subscribe` message within the WebSocket connection with a `ChangeCatalogCaptureRequest` defining the
   filtering strategy (as specified in the
   [WebSocket specification](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md)).

The CDC stream will now send `ChangeCatalogCapture` objects wrapped into `next` messages to the client.

Example of retrieving catalogue change history in the WebSocket protocol for REST:

<SourceAlternativeTabs variants="rest">

[Setting up a minimal catalog change capture](/documentation/user/en/use/api/example/catalog-change-capture-rest.json)

</SourceAlternativeTabs>

You can find additional helpful examples below:

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving transaction delimiters and changes for all entities of a specific type

</NoteTitle>

This subscription will deliver all transaction delimiters and all changes made to entities of type `Product`
starting from the next version of the catalogue.

<SourceAlternativeTabs variants="rest">

[Requesting entity level changes in transaction blocks](/documentation/user/en/use/api/example/capture-entity-mutations-with-transaction-rest.json)

</SourceAlternativeTabs>

</Note>

---

The catalogue-level capture stream provides access to
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>
instances representing the changes made to the catalogue.

Catalogue capture events are represented by
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>
instances that contain the following information:

<dl>
  <dt>long `version`</dt>
  <dd>
    The version of the catalogue where the mutation occurs.
  </dd>
  <dt>int `index`</dt>
  <dd>
    The index of the mutation within the same transaction. Index `0` is always an infrastructure mutation of type
    <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass> `area`</dt>
  <dd>
    The area of the operation that was performed:
    <ul>
        <li>`SCHEMA` - changes in the schema are captured</li>
        <li>`DATA` - changes in the data are captured</li>
        <li>`INFRASTRUCTURE` - infrastructural mutations that are neither schema nor data</li>
    </ul>
  </dd>
  <dt>String `entityType` (optional)</dt>
  <dd>
    The name of the entity type that was affected by the operation. This field is null when the operation is executed
    on the catalog schema itself.
  </dd>
  <dt>Integer `entityPrimaryKey` (optional)</dt>
  <dd>
    The primary key of the entity that was affected by the operation. Only present for data area operations.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Classification of the mutation, defined by the following enumeration:
    <ul>
        <li>`UPSERT` - Create or update operation. If there was data with such identity before, it was updated. If not, it was created.</li>
        <li>`REMOVE` - Remove operation — i.e., there was data with such identity before, and it was removed.</li>
        <li>`TRANSACTION` - Delimiting operation signaling the beginning of a transaction.</li>
    </ul>
  </dd>
  <dt>`CatalogBoundMutationUnion` `body` (optional)</dt>
  <dd>
    Optional body of the operation when it is requested by the requested
    <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

There are several ways to access the catalogue change capture stream, each with a slightly different purpose:

### System API

The GraphQL system API exposes the `onCatalogChange`/`onCatalogChangeUntyped` subscription, which allows you to
subscribe to the catalogue change capture stream of any catalogue with fully custom filtering criteria.

This is useful if you need to react to all the changes (transactional, data, schema) in a catalogue.

The subscription accepts the following parameters:

<dl>
  <dt>String `catalogName`</dt>
  <dd>
    The name of the catalogue to subscribe to.
  </dd>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change
    stream will start from the next version of the catalogue (i.e., the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not
    specified, the change stream will start from the first mutation of the specified version. The index allows you to
    precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureCriteria.java</SourceClass>[] `criteria` (optional)</dt>
  <dd>
    Array of criteria that specify which changes you are interested in. If not specified, all changes are captured.
    If multiple criteria are specified, matching any of them is sufficient (OR logic). Each criterion consists of:
    <ul>
        <li>`area` - the capture area (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass>)</li>
        <li>`site` - the capture site (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureSite.java</SourceClass>) providing fine-grained filtering</li>
    </ul>
  </dd>
</dl>

---

#### How to set up a new catalogue change capture in system API

The setup is straightforward: define one subscription with the desired parameters and subscribe to the stream via
the WebSocket protocol. The WebSocket stream will then send change events to the client based on the defined
output.

Example of retrieving catalogue change history in GraphQL system API:

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Setting up a minimal catalog change capture](/documentation/user/en/use/api/example/catalog-change-capture-graphql.graphql)

</SourceCodeTabs>

You can find additional helpful examples below:

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving transaction delimiters and changes for all entities of a specific type

</NoteTitle>

This subscription will deliver all transaction delimiters and all changes made to entities of type `Product`
starting from the next version of the catalogue.

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Requesting entity level changes in transaction blocks](/documentation/user/en/use/api/example/capture-entity-mutations-with-transaction-graphql.graphql)

</SourceCodeTabs>

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving changes for attribute with name `quantityOnStock` of particular entity of type `Product`

</NoteTitle>

The following subscription will deliver all changes made to the `quantityOnStock` attribute of the entity type
`Product` with primary key `745`, starting from the next version of the catalogue.

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Requesting entity level changes](/documentation/user/en/use/api/example/capture-attribute-mutation-graphql.graphql)

</SourceCodeTabs>

</Note>

### Catalogue data API

If you don't need the fully featured system API catalogue CDC subscriptions, the GraphQL data API exposes two
simplified subscriptions:

The first is the `onDataChange`/`onDataChangeUntyped` subscription that allows you to subscribe to the data change
capture stream of the entire API-specified catalogue (across all entity collections).

This is useful if you only need to react to data changes and nothing more. If so, this subscription provides a
simpler interface with a smaller set of mutations to consider.

The subscription accepts the following parameters:

<dl>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change
    stream will start from the next version of the catalogue (i.e., the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not
    specified, the change stream will start from the first mutation of the specified version. The index allows you to
    precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by type of affected container. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`CATALOG` - Catalogue-level containers</li>
      <li>`ENTITY` - Entity containers</li>
      <li>`ATTRIBUTE` - Attribute containers</li>
      <li>`ASSOCIATED_DATA` - Associated data containers</li>
      <li>`PRICE` - Price containers</li>
      <li>`REFERENCE` - Reference containers</li>
    </ul>
  </dd>
  <dt>String[] `containerName` (optional)</dt>
  <dd>
    Filter by specific container name (e.g., a specific attribute name like `name`, `code`). If not specified, changes
    to all containers are captured.
  </dd>
</dl>

The second is the `on{entityType}DataChange`/`on{entityType}DataChangeUntyped` subscription that allows you to
subscribe to the data change capture stream of a specific entity collection within the API-specified catalogue.

This is useful if you need to react only to data changes of a specific entity collection. If so, this subscription
provides a simpler interface with a smaller set of mutations to consider.

The subscription accepts the following parameters:

<dl>
  <dt>Integer `entityPrimaryKey` (optional)</dt>
  <dd>
    Filter by a specific entity primary key. If not specified, changes to all entities are captured.
  </dd>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change
    stream will start from the next version of the catalogue (i.e., the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not
    specified, the change stream will start from the first mutation of the specified version. The index allows you to
    precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by type of affected container. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`CATALOG` - Catalogue-level containers</li>
      <li>`ENTITY` - Entity containers</li>
      <li>`ATTRIBUTE` - Attribute containers</li>
      <li>`ASSOCIATED_DATA` - Associated data containers</li>
      <li>`PRICE` - Price containers</li>
      <li>`REFERENCE` - Reference containers</li>
    </ul>
  </dd>
  <dt>String[] `containerName` (optional)</dt>
  <dd>
    Filter by specific container name (e.g., a specific attribute name like `name`, `code`). If not specified, changes
    to all containers are captured.
  </dd>
</dl>

#### How to set up a new catalogue change capture in catalogue data API

The setup is straightforward: define one subscription with the desired parameters and subscribe to the stream via
the WebSocket protocol. The WebSocket stream will then send change events to the client based on the defined
output.

Example of retrieving catalogue change history in GraphQL catalogue data API:

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Setting up a minimal catalog change capture](/documentation/user/en/use/api/example/catalog-change-capture-data-api.graphql)

</SourceCodeTabs>

You can find additional helpful examples below:

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving changes for attribute with name `quantityOnStock` of particular entity of type `Product`

</NoteTitle>

The following subscription will deliver all changes made to the `quantityOnStock` attribute of the entity type
`Product` with primary key `745`, starting from the next version of the catalogue.

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Requesting entity level changes](/documentation/user/en/use/api/example/capture-attribute-mutation-data-api.graphql)

</SourceCodeTabs>

</Note>

### Catalogue schema API

If you don't need the fully featured system API catalogue CDC subscriptions, the GraphQL schema API exposes two
simplified subscriptions:

The first is the `onSchemaChange`/`onSchemaChangeUntyped` subscription that allows you to subscribe to the schema
change capture stream of the entire API-specified catalogue (with all entity collections).

This is useful if you only need to react to schema changes and nothing more. If so, this subscription provides a
simpler interface with a smaller set of mutations to consider.

The subscription accepts the following parameters:

<dl>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change
    stream will start from the next version of the catalogue (i.e., the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not
    specified, the change stream will start from the first mutation of the specified version. The index allows you to
    precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`CATALOG` - Catalogue-level schema changes</li>
      <li>`ENTITY` - Entity schema changes</li>
      <li>`ATTRIBUTE` - Attribute schema changes</li>
      <li>`ASSOCIATED_DATA` - Associated data schema changes</li>
      <li>`PRICE` - Price schema changes</li>
      <li>`REFERENCE` - Reference schema changes</li>
    </ul>
  </dd>
</dl>

The second is the `on{entityType}SchemaChange`/`on{entityType}SchemaChangeUntyped` subscription that allows you to
subscribe to the schema change capture stream of a specific entity collection within the API-specified catalogue.

This is useful if you need to react to schema changes of a specific entity collection only. If so, this
subscription provides a simpler interface with a smaller set of mutations to consider.

The subscription accepts the following parameters:

<dl>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change
    stream will start from the next version of the catalogue (i.e., the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not
    specified, the change stream will start from the first mutation of the specified version. The index allows you to
    precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`CATALOG` - Catalogue-level schema changes</li>
      <li>`ENTITY` - Entity schema changes</li>
      <li>`ATTRIBUTE` - Attribute schema changes</li>
      <li>`ASSOCIATED_DATA` - Associated data schema changes</li>
      <li>`PRICE` - Price schema changes</li>
      <li>`REFERENCE` - Reference schema changes</li>
    </ul>
  </dd>
</dl>

#### How to set up a new catalogue change capture in catalogue schema API

The setup is straightforward: simply define one subscription with the desired parameters and subscribe to the
stream via the WebSocket protocol. The WebSocket stream will then send change events to the client based on the
defined output.

Example of retrieving catalogue change history in GraphQL catalogue schema API:

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Setting up a minimal catalog change capture](/documentation/user/en/use/api/example/catalog-change-capture-schema-api.graphql)

</SourceCodeTabs>

You can find additional helpful examples below:

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving changes for attributes of particular entity of type `Product`

</NoteTitle>

The following subscription will deliver all schema changes made to attributes of the entity type `Product`
starting from the next version of the catalogue.

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Requesting entity level changes](/documentation/user/en/use/api/example/capture-attribute-mutation-schema-api.graphql)

</SourceCodeTabs>

</Note>
