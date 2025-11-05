---
title: Implementing Change Data Capture
perex: |
  Change Data Capture (CDC) is a pattern for detecting and streaming inserts, updates, and deletes from a source database in near real-time.
  It enables low-latency data replication, event-driven workflows, and keeps services in sync without heavy batch jobs. If you're wondering how CDC is implemented in evitaDB and how its reliability is ensured, read on.
date: '1.11.2025'
author: 'Jan Novotný'
motive: assets/images/20-change-data-capture.png
proofreading: 'needed'
draft: true
---

Change Data Capture (CDC) is essentially a filtered stream of logical operations read from the Write-Ahead Log (WAL) of the database. When you commit changes to the database, these changes are first written to the WAL before being applied to the actual shared database state and its data files. This ensures that in the event of a crash or failure, the database can still apply all transactions that were committed and confirmed to be durable.

In evitaDB, the WAL is implemented as a sequence of operations wrapped inside transaction boundaries. If you could read our binary format, you'd see something like this:

```
TRANSACTION: txId=16c9...40c7, version=41, mutationCount = 2, walSizeInBytes=891, commitTimestamp=2025-10-23T13:31:12.283+02:00
ENTITY_UPSERT: entityPrimaryKey=1234, entityType="Product", entityExistence=MAY_EXIST, localMutations={
    UPSERT_ATTRIBUTE: attributeName="title", locale=Locale.ENGLISH, value="New product title"
    UPSERT_PRICE: priceId=5678, priceList="DEFAULT", currency="USD", priceWithTax=199.99, priceWithoutTax=179.99, taxRate=20
}
TRANSACTION: ...
```

## Java implementation

The natural way to implement streaming of changes in Java is to use the `Flow API`, which was introduced in Java 9. It defines a standard for asynchronous stream processing with non-blocking backpressure. The main building blocks of the Flow API are `Publisher`, `Subscriber`, and `Subscription`. A `Publisher` produces items and sends them to `Subscribers` that have subscribed to it. The `Subscription` represents the relationship between a `Publisher` and a `Subscriber`, allowing the `Subscriber` to request items and cancel the subscription.

The interface is very minimalistic and doesn't give us much "designing" freedom. Because we need to allow clients to define a filtering strategy for the changes they want to receive, we must enclose it in the method that creates the `Publisher`. The publisher itself then contains only the method `void subscribe(Subscriber)`. This might seem complicated at first, since a client usually doesn't need to subscribe multiple subscribers to the same CDC stream, but it's the only way to comply with the Flow API specification (which brings the interoperability we need). So to create a CDC stream that would capture all schema and data changes with full mutation bodies, the code would look like this:

```java
// open a read-only session to access the catalog
try (final EvitaSessionContract session = evita.createReadOnlySession("evita")) {
    // retrieve change history from the catalog
    final ChangeCapturePublisher<ChangeCatalogCapture> changePublisher =
	    session.registerChangeCatalogCapture(
	        ChangeCatalogCaptureRequest.builder()
	            // capture both schema and data changes
	            .criteria(
	                // capture all schema changes
	                ChangeCatalogCaptureCriteria.builder()
	                    .schemaArea()
	                    .build(),
	                // capture all data changes
	                ChangeCatalogCaptureCriteria.builder()
	                    .dataArea()
	                    .build()
	            )
	            // include full mutation bodies
	            .content(ChangeCaptureContent.BODY)
	            .build()
        );

    // subscribe one or more subscribers to the same publisher
	changePublisher.subscribe(
        ... subscriber implementation ...
	);
}
```

### Backpressure handling

The problem with CDC streams is that the reading speed depends on the network and client processing speed. If the client is slow, the server must not overwhelm it with data faster than it can process. We must also prevent slowing down the server's WAL processing or exhausting the server memory by buffering too much data for slow clients. On the other hand, we need to ship the data as fast as possible to fast clients to minimize the lag between data changes and their reception. Reading from the WAL file for each client separately would be slow and resource-intensive.

Therefore, we have a two-speed implementation that builds on two premises:

1. most CDC clients are interested in the latest changes
2. most CDC clients can keep up with the speed at which changes are incorporated into the shared state (using some reasonable buffering)

For each unique filtering strategy (predicate), we maintain a separate shared <SourceClass>evita_engine/src/main/java/io/evitadb/core/cdc/ChangeCatalogCaptureSharedPublisher.java</SourceClass> instance that contains an internal ring buffer of limited size. When the database transactional engine processes a mutation, it pushes the object representation of the WAL record into each shared publisher, which applies its unique predicate. Only if the predicate matches does it convert the mutation to a CDC event to be sent to the client. CDC events are immediately written to the internal ring buffer. This way, we don't keep the WAL mutation objects in memory longer than necessary, and we only keep the CDC event objects that are actually needed by at least one subscriber.

Those events may still not be ready for sending to the subscribers because the mutation effects might not yet have reached the database shared state (changes must be visible only at the end of the transaction, and when there are multiple small transactions, they're processed and applied in bulk during time windows). Therefore, each publisher maintains its own "watermark" in the ring buffer, and only events that are older than the last "published" transaction are made available for reading. This way, we ensure that subscribers never read events that are not yet visible in the database shared state.

For each subscriber, we maintain its own subscription with an internal queue containing events to be delivered to the subscriber. Each subscription also keeps its own watermark of the last read event (in fact, a pointer to the WAL position), so there might be different subscribers attached to the same publisher reading events at different speeds. When the subscriber requests more events, we try to deliver them from the internal queue. If the queue is empty, we try to read more events from the shared publisher ring buffer, and there are three possible outcomes:

1. there is another event in the shared publisher ring buffer—we read it, put it into the subscription queue, and deliver it to the subscriber
2. there is no new event in the shared publisher ring buffer—we stop reading and wait for new events to arrive
3. the subscriber is lagging too much, and the shared publisher ring buffer has already overwritten events that the subscriber hasn't yet read

In the second case, we "wake up" all the dormant subscriptions when new events arrive in the shared publisher ring buffer. This ensures all fast subscribers get their events as soon as possible (of course, in the case of remote clients, there is a short delay for events to be picked up by the thread pool taking care of propagating events over the web APIs).

In the third case, we switch the subscription to "resync mode," in which we start reading mutations from the WAL file directly, skipping the shared publisher ring buffer until we reach the state where the subscriber can be safely switched back to reading from the shared publisher ring buffer (the subscriber catches up). This way, we ensure that slow subscribers don't block the entire CDC system but can always resync to the latest state.

To avoid unnecessary memory consumption, events are discarded from the subscription queue as soon as they are passed to the subscriber. Events in the shared publisher ring buffer are discarded as soon as all known subscriptions move their watermarks past them. So if all subscribers are fast enough and have sent all events to their clients, all the queues and ring buffers are empty (but what's more important—all the CDC event objects can be garbage collected).

### gRPC implementation

Our Java client builds on top of our gRPC API, so when you use the publisher/subscriber API in the Java client, 
under the hood it uses gRPC streaming to receive events from the server. On the client, we set up a gRPC stream the 
moment the `subscribe(...)` method is called on the publisher. Creating a publisher instance only creates a new 
definition in the client memory, ready to create a new gRPC subscription by passing the filtering criteria to the server,
but it doesn't actually communicate with the server yet.

The Java Flow API and gRPC streaming API are translated on the client side using adapter classes that implement the 
Flow API interfaces and use gRPC streaming stubs to communicate with the server. Backpressure handling is implemented 
using gRPC flow control mechanisms, so when the subscriber requests more items, we request more items from the gRPC stream. 
When the subscriber is slow, we stop requesting more items from the gRPC stream, which automatically stops the server from sending more items.

Thanks to gRPC streaming capabilities, we can cancel the subscription from the client side at any time, which closes the 
gRPC stream and releases all resources on the server side as well. The CDC implementation is not limited to Java clients 
only. Any gRPC-capable client can implement the CDC subscriber using the same filtering criteria and receive the same 
events as the Java client.

### GraphQL implementation

Our GraphQL API uses the [subscriptions API](https://graphql.org/learn/subscriptions/) to 
provide a way to subscribe to CDC events. We have chosen the [GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md)
protocol to implement the subscriptions so that existing GraphQL clients can easily connect to the stream. 

Under the hood, the WebSocket stream from a client is translated to the Java Flow API stream to receive events from the engine.
When the client opens a WebSocket stream with subscription, it requests a new publisher with a CDC stream from the evitaDB
engine which sends all future events back through the WebSocket stream to the client. 

Backpressure handling is implemented using WebSocket flow control mechanisms, so when the client requests more events,
we request more events from the Java Flow stream. When the client is slow, we stop requesting more events from the 
Java Flow stream.
Thanks to the WebSocket streaming capabilities, we can cancel the subscription from the client side at any time, which closes
the Java Flow stream on the server side and releases all resources.

### REST implementation

Our REST API is implemented similarly as the GraphQL API. However, the OpenAPI specification doesn't directly specify
any standard for real-time updates APIs, nor is it possible to document one within the base OpenAPI specification. Therefore,
we have decided to create a [custom WebSocket specification](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md) 
based on the [GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md) protocol. 
Although the base OpenAPI specification doesn't allow us to directly document the custom protocol, for now, we have
included the CDC types in the OpenAPI specification so that there is at least some building ground for the client developers 
(e.g., mutation objects, CDC event objects, etc.).

Under the hood, the WebSocket stream from a client is translated to the Java Flow API stream to receive events from the engine.
When the client opens a WebSocket stream with subscription, it requests a new publisher with a CDC stream from the evitaDB
engine which sends all future events back through the WebSocket stream to the client.

Backpressure handling is implemented using WebSocket flow control mechanisms, so when the client requests more events,
we request more events from the Java Flow stream. When the client is slow, we stop requesting more events from the
Java Flow stream.
Thanks to the WebSocket streaming capabilities, we can cancel the subscription from the client side at any time, which closes
the Java Flow stream on the server side and releases all resources.
