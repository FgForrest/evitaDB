---
title: The magic of traffic recording, analysis and replay
perex: |
  Imagine you could record all the traffic on your database, analyze it and replay it. How much easier would your life be? It's surely worth some effort to set up proper tooling to make this possible. But, what if I tell you, that all this magic can be done with just turning on a single switch?
date: '24.01.2025'
author: 'Ing. Jan Novotný'
motive: assets/images/16-traffic-recording.png
proofreading: 'done'
draft: true
---
In the latest version `2025.1` of evitaDB we have introduced a new feature called `Traffic Recording`. It's disabled by default, but you can easily enable it by setting `server.trafficRecording.enabled` to `true`. Even if you don't do this, you can always start it manually in the evitaLab console. This feature allows you to record all traffic that passes through the database - sessions, queries, mutations, entity fetches - everything.

The capture engine is designed to be as lightweight as possible, so you can run it in production without noticing any performance degradation. If the traffic is heavy, you can also configure the recording engine to sample the traffic, reducing the amount of data stored, but still enough to analyse traffic patterns. Traffic data is serialised in a limited buffer in memory, which is very fast, and then flushed asynchronously to the disk buffer file. If the flushing process can't keep up with the traffic, the recording engine will automatically discard any traffic that doesn't fit into the memory buffer. So the worst-case scenario is that you'll lose some data for analysis, but the database will still work fine and your customers won't notice a thing.

The disk buffer is designed to behave like a ring buffer and is allocated at the start of the database, so you can't run out of disk space. When the end of the buffer is reached, the recording engine starts to overwrite the oldest data from the beginning. You can start another asynchronous task that will capture all the data from the disk buffer file before it's overwritten into another compressed file for later analysis. This task can be set to stop automatically when it reaches a certain data size or after a certain period of time.

Now that you have a basic understanding of how the tool works, let's look at some practical examples of how you can use it.

## Development assistance

There are several developers and teams working on the same application. Often the developers using evitaDB's web APIs are not the same developers designing the database schema or managing the data in it. We often encounter a situation where frontend developers designing a web page in modern JavaScript frameworks like React, Angular or Vue can't easily provide complete GraphQL / REST queries that are dynamically composed in their applications to get proper support and advice from backend developers. They can use tools like Open Telemetry Tracing (which evitaDB also supports) to access the necessary data, but often there are a lot of traces and finding the right one can be a challenge. Another problem is the size of a query, which can be quite large and may not fit into the trace or log record and may be truncated. All these approaches are feasible, but not very developer friendly.

With Traffic Recording, evitaDB can be set up to automatically capture all traffic on the developer's machine or test environment, and if necessary, the front-end developer can access any of his queries through the evitaLab interface. The appropriate request can be found in various ways, the most convenient of which is the search for automatic or developer-assigned labels. The developer immediately sees basic telemetry data - such as query duration, number of rows retrieved, number of matching records, etc. - and can re-execute the query in the evitaLab console to see the result of the query and tweak it if necessary. This way, the developer gets immediate feedback on the query and can easily share it with the backend developer for further assistance.

Traffic Recorder captures queries in their original format (GraphQL, REST, gRPC) as well as in the internal evitaQL format. Often a single GraphQL query can represent several evitaQL queries that are combined into a single result and actually executed in parallel by the query engine. All these relationships are captured and visualised in the evitaLab interface. All input queries are available in the exact form in which they arrived in the database, so you can access variables, fragments, etc. as they were sent by the client.

The traffic recorder also records all invalid requests, so you can see requests that couldn't be parsed or were rejected by the database for some reason. This can be very useful for debugging and improving the client application.

The filter allows you to filter queries by various criteria - for example, you can filter only queries that took more than a certain amount of time to execute, or only queries that fetched excessive amounts of data from disk. This can help you identify performance bottlenecks in your application early in the development process.

## Performance analysis

Because you can capture a representative sample of traffic, you can use it to analyse the performance of your application. You can see which queries are the most frequent, which are the slowest, which are fetching the most data, etc. You can restore backups of your production data and replay the captured traffic on different machines to test different hardware configurations, or stress test the database by increasing the replay speed or multiplying it by parallelizing clients replaying the traffic.

This is a very cost effective way of discovering the limits of your application in real-world scenarios on an accurate dataset and making correct assumptions about the performance of your application and the necessary hardware requirements. You can also use traffic recording to compare the performance of different versions of your application or database engine.

## Traffic replay and testing

Besides performance reasons, traffic replay can be used to find hard to reproduce bugs in your application or the database itself. One such bug is [issue #37](https://github.com/FgForrest/evitaDB/issues/37), which is related to evitaDB's internal cache. It happens randomly and only from time to time, and we weren't able to catch it, so we recommend to disable the cache (and it is disabled by default), even though we know that this internal cache significantly improves the performance of the database. With the Traffic Recorder in our toolbox, we can capture enough traffic on production data that if we replay it locally on two parallel evitaDB instances - one with cache enabled and one with cache disabled - we can easily identify the situation when the bug occurs and which queries or state of the cache contents trigger it. We are confident that we will be able to catch the bug in the next couple of releases and enable the cache by default again.

## Security audit

Using traffic logging for security auditing is an edge case scenario, but if you can afford to store all traffic for the necessary amount of time, you can use it to audit all accesses to the database - who communicated with it, when, and what data they accessed.

## Conclusion

Traffic Recording is a powerful tool that can help you in many ways. It's easy to set up and use, and can save you a lot of time and effort in development, performance analysis, testing and security auditing. We believe it's a must-have feature for any modern database engine, yet very few databases offer it. This feature usually requires separate tools and a lot of effort to set up and keep running. With evitaDB you get it out of the box, and it's designed to be as lightweight and easy to use as possible.

We hope you'll find it as useful as we have. If you have any questions or need help setting it up, don't hesitate to contact us. We are here to help!