---
title: Choosing HTTP server for evitaDB
perex: We plan to provide several ways to communicate with evitaDB clients. For that we needed some universal HTTP server that would serve at least most of the requests.
date: '2022-01-12'
author: 'Lukáš Hornych'
motive: assets/images/03-choosing-http-server.png
---

The main goal right now is to
provide [GraphQL](https://graphql.org/), [REST](https://en.wikipedia.org/wiki/Representational_state_transfer),
[gRPC](https://grpc.io/) and potentially [WebSockets](https://en.wikipedia.org/wiki/WebSocket)
or [SSE](https://en.wikipedia.org/wiki/Server-sent_events) APIs for some specific use-cases. However, there is currently
no foundation for HTTP communication as evitaDB comes only with Java API. Because of this, there was a need to find some
HTTP server, library or framework that would serve as a common foundation for all of those mentioned APIs.

## Criteria and requirements

As the main requirement, we wanted the lowest possible latency and the highest possible throughput of HTTP request
processing and also a simple codebase of chosen server, library or framework so that there would be a smaller
probability of “magic” behavior and unexpected surprises. Another important feature was the ability to embed the HTTP
foundation into the existing evitaDB codebase without needing to adjust the whole running and building processes of
evitaDB to the chosen solution because in future there, may be other ways to communicate with users. An advantage to all of
these requirements would be a simple and straightforward API for handling HTTP requests and errors. This means that there
wouldn’t be any unnecessary low level HTTP communication work involved, if not explicitly needed by some specific
use-cases. Last but not least, it would be nice to have at least partial built-in support for handling WebSockets for
future specific use-cases. Finally, a server or library that is publicly known to work or being tested with GraalVM or
having direct GraalVM support is a plus.

## Servers, libraries and frameworks

We decided to spend up to 8 hours on ecosystem exploration and chose the following 10 Java HTTP servers and frameworks to
test:

- [microhttp](https://github.com/ebarlas/microhttp)
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)
- [Netty](https://github.com/netty/netty)
- [Undertow](https://github.com/undertow-io/undertow)
- [Spring MVC](https://docs.spring.io/spring-framework/docs/current/reference/html/web.html#mvc)
  with [Spring Boot](https://docs.spring.io/spring-boot/docs/current/reference/html/) *(runs on Tomcat, Jetty or
  Undertow, we used Tomcat as it is the default)*
- [Spring WebFlux](https://docs.spring.io/spring-framework/docs/current/reference/html/web-reactive.html#webflux)
  with [Spring Boot](https://docs.spring.io/spring-boot/docs/current/reference/html/) *(runs on Tomcat, Jetty or
  Undertow, we used Tomcat as it is the default)*
- [Vert.x](https://github.com/eclipse-vertx/vert.x) *(runs on Netty)*
- [Quarkus Native](https://quarkus.io/) *(runs on Netty through Vert.x)*
- [Micronaut](https://micronaut.io/) *(runs on Netty)*
- [Javalin](https://github.com/tipsy/javalin) *(runs on Jetty)*

This list contains low level servers, small libraries and even big and well known frameworks for building web applications.
We decided to include those big frameworks to have some performance comparison with lower level servers so that we know
whether the possible sacrifice of missing high level abstractions is really worth it. Our servers, libraries and frameworks
were selected upon several recommendation articles and mainly by GitHub repository popularity, i.e. star count, issues,
dates of last commits and so on.

## Testing environment

We tested the servers, libraries and frameworks on a simple “echo” GraphQL API because we wanted to test the baseline
latency of HTTP request processing for GraphQL API. The GraphQL API was chosen because it is the first API evitaDB is
going to support, and we believe that this approach will give us the accurate measurement even for other future APIs.
This simplified test API was then implemented on top of each chosen server, library and framework.

The “echo” API contains single query that takes single string argument:

```graphql
query Echo {
    echo(message: "hello") {
        message
    }
}
```

which it then returns in response message:

```json
{
  "data": {
    "echo": {
      "message": "hello"
    }
  }
}
```

No additional business logic is implemented there.

For actual testing and result interpretation, we used
the [Java Microbenchmark Harness (JMH)](https://github.com/openjdk/jmh). A testing workflow consists of two parts:
running servers with implemented APIs and running JMH tests. Firstly, an application with tested HTTP servers is started,
which starts each server in a separate thread with a custom port. Then, the JMH application is started and each test,
for each server, builds a Java HTTP Client which then continuously generates calls to the appropriate server for one
minute from several threads.

## Implementation of servers

Implementation of individual servers involved basically looking up the official library or server examples and transforming
them into the “echo” GraphQL API with help of the [GraphQL Java library](https://www.graphql-java.com/) (which is de-facto
the industry standard for writing GraphQL APIs on the Java platform). Besides the fact that this approach was rather time
efficient, when dealing with so many servers, libraries and frameworks, it also showed how easy it is to work with each
individual server or library and what surprises there are when building even a simple server based on an official
example. Another advantage of this approach is that it shows base performance of a particular solution without need for
complex low level configuration. If there is a need for complex configuration even at the beginning, we fear that in
future this could be rather difficult to maintain.

**microhttp**, **Javalin**, **Vert.x** and **Undertow** were pretty straightforward to handle. We needed to write just a
few lines of code to implement simple HTTP request handlers. The implementation of the remaining servers and libraries was a
little bit more complicated.

**NanoHTTPD** comes with a rather simple API, but it was not designed to handle JSON requests, only basic HTTP POST
bodies. Therefore, it involved reimplementation of body parsing. Also, it seems that it is no longer maintained, although
some discussion is still happening in issues of its GitHub repository.

**Netty** on the other hand required to manually configure several worker threads and other communication options. In
the case of their HTTP codec, there is quite a large and really not straightforward abstraction for HTTP request and
response classes. There are several questionably named classes (at least for new users of this server) that are
ultimately composed together and passed to handlers. This was a problem when trying to find a way to read a POST request
body. We had to register Netty’s body aggregator and use a specific HTTP request class that has access to that body in
the end.

**Spring’s MVC** and **WebFlux** were not difficult to implement but were difficult to set up as they require a custom
Spring Boot Maven plugin to compile which ultimately discards the idea of embedding it in other applications.
Fortunately, the solution was rather simple, build separate jar files for each Spring server.

Implementing the service in the **Quarkus** framework was much more difficult. If we omit difficulties
with [GraalVM](https://www.graalvm.org/) itself, such as custom building, there were also difficulties in the implementation
of the actual controllers. The most notable obstacle was the inability to split an implementation into multiple modules, although
it [should be possible](https://quarkus.io/guides/maven-tooling#multi-module-maven). Therefore, unlike other
implementations, this one had to have all of its controllers in one place together with the main application class.

In the case of the **Micronaut**, the implementation itself was rather simple, probably the simplest one among other
servers and libraries (with the help of a built-in controller for GraphQL). The problem was in the Maven module setup.
Micronaut uses a custom Maven parent POM and there are some hidden dependencies, and it wasn’t possible to run
Micronaut without it as part of a larger multi-module Maven project.

## Benchmarking servers

The final tests were run with 1 warm-up iteration, 5 measurement iterations and 2 forks on a laptop with Ubuntu 21.10
and an 8-core Intel Core i7-8550U CPU with 16 GB of RAM. The tests were run in two modes: throughput (operations per
second) and average time (microseconds per operation).

**The results**

<Table caption="Throughput results (ops/s - higher is better).">
	<Thead>
      <Tr>
          <Th>Server, library or framework</Th>
          <Th>JMH Score (ops/s)</Th>
          <Th>Min JMH Score (ops/s)</Th>
          <Th>Max JMH Score (ops/s)</Th>
      </Tr>
	</Thead>
	<Tbody>
      <Tr>
          <Td>microhttp</Td>
          <Td>18,187</Td>
          <Td>18,003</Td>
          <Td>18,384</Td>
      </Tr>
      <Tr>
          <Td>Javalin</Td>
          <Td>17,958</Td>
          <Td>17,697</Td>
          <Td>18,357</Td>
      </Tr>
      <Tr>
          <Td>Undertow</Td>
          <Td>17,874</Td>
          <Td>17,573</Td>
          <Td>18,289</Td>
      </Tr>
      <Tr>
          <Td>Micronaut</Td>
          <Td>14,742</Td>
          <Td>14,464</Td>
          <Td>15,052</Td>
      </Tr>
      <Tr>
          <Td>Spring Boot WebFlux</Td>
          <Td>12,986</Td>
          <Td>12,889</Td>
          <Td>13,224</Td>
      </Tr>
      <Tr>
          <Td>Vert.x</Td>
          <Td>12,223</Td>
          <Td>12,120</Td>
          <Td>12,406</Td>
      </Tr>
      <Tr>
          <Td>Spring Boot MVC</Td>
          <Td>11,691</Td>
          <Td>11,360</Td>
          <Td>11,819</Td>
      </Tr>
      <Tr>
          <Td>Netty</Td>
          <Td>11,272</Td>
          <Td>11,043</Td>
          <Td>11,407</Td>
      </Tr>
      <Tr>
          <Td>Quarkus (in native mode)</Td>
          <Td>10,100</Td>
          <Td>10,012</Td>
          <Td>10,159</Td>
      </Tr>
      <Tr>
          <Td>NanoHTTPD</Td>
          <Td>8,874</Td>
          <Td>8,709</Td>
          <Td>9,023</Td>
      </Tr>
	</Tbody>
</Table>

<Table caption="Average time results (us/op - smaller is better).">
	<Thead>
		<Tr>
			<Th>Server, library or framework</Th>
			<Th>JMH Score (us/op)</Th>
			<Th>Min JMH Score (us/op)</Th>
			<Th>Max JMH Score (us/op)</Th>
		</Tr>
	</Thead>
	<Tbody>
		<Tr>
			<Td>Undertow</Td>
			<Td>435</Td>
			<Td>417</Td>
			<Td>460</Td>
		</Tr>
		<Tr>
			<Td>microhttp</Td>
			<Td>449</Td>
			<Td>448</Td>
			<Td>451</Td>
		</Tr>
		<Tr>
			<Td>Javalin</Td>
			<Td>452</Td>
			<Td>437</Td>
			<Td>457</Td>
		</Tr>
		<Tr>
			<Td>Micronaut</Td>
			<Td>543</Td>
			<Td>534</Td>
			<Td>555</Td>
		</Tr>
		<Tr>
			<Td>Spring Boot WebFlux</Td>
			<Td>612</Td>
			<Td>601</Td>
			<Td>620</Td>
		</Tr>
		<Tr>
			<Td>Vert.x</Td>
			<Td>659</Td>
			<Td>653</Td>
			<Td>676</Td>
		</Tr>
		<Tr>
			<Td>Spring Boot MVC</Td>
			<Td>689</Td>
			<Td>678</Td>
			<Td>698</Td>
		</Tr>
		<Tr>
			<Td>Netty</Td>
			<Td>714</Td>
			<Td>706</Td>
			<Td>725</Td>
		</Tr>
		<Tr>
			<Td>Quarkus (in native mode)</Td>
			<Td>790</Td>
			<Td>787</Td>
			<Td>793</Td>
		</Tr>
		<Tr>
			<Td>NanoHTTPD</Td>
			<Td>913</Td>
			<Td>899</Td>
			<Td>926</Td>
		</Tr>
	</Tbody>
</table>

*A gist with the raw results can be found [here](https://gist.github.com/lukashornych/4f500cd1e20de805c697888f263c415c) and
charts for visualization [here](https://jmh.morethan.io/?gist=4f500cd1e20de805c697888f263c415c&topBar=Java%20HTTP%20servers%20and%20libraries%20performance%20comparison%20test).*

From the above results, there are 3 main adepts for the winner: microhttp, Javalin and Undertow. Quite interesting and
surprising are the results of Javalin, which is, in fact, a framework built upon the [Jetty](https://www.eclipse.org/jetty/)
server and not a barebone HTTP server.
Even more surprising are the results of the popular Netty server, which is a low level server with the most difficult API
of all of them. This is probably due to the complexity involved in setting up Netty and the large number of small
options and optimizations, because there are a lot of other libraries that successfully use Netty as their HTTP communication layer.
Unfortunately, it is not apparent from the documentation or examples what an optimal configuration is. Also, we expected the
Quarkus server, which was run in native mode using [GraalVM](https://www.graalvm.org/) to end up in higher positions. In
contrast to that, large frameworks such as Spring and Vert.x were much more performant than we ever expected due to
their complex abstraction.

## Choosing the final solution

Final decision - which server, library or framework to pick was narrowed to the 3 solutions: **microhttp**, **Javalin**
and **Undertow**. Because they performed very similarly, the decision was made based upon their advantages and
disadvantages relevant to evitaDB.

At first, the microhttp server seemed like the one to go with, mainly because of its exceptionally small codebase (around 500
LoC) and a simple and straightforward API. Overall, this server ticked almost all the requirement boxes except the one for
support for WebSockets. But the unsure future of this fairly new project made it quite a deal-breaker. Some other
possible disadvantages may be the lack of support for SSL and other advanced features. With that in mind, we focused on
choosing between Javalin and Undertow. Javalin is a lightweight framework built
upon the [Jetty](https://www.eclipse.org/jetty/) server and Undertow is an actual HTTP server, yet they performed almost the
same. Both tick all the requirement boxes. Both are performant, easily embeddable, small enough to limit the
possibilities of “magic” surprises, have simple and straightforward APIs and even support WebSockets. Both are popular
and are updated regularly. Both support non-blocking request processing and both should probably run on GraalVM in
the future if needed. Javalin comes with shorthand API configuration methods for setting up endpoints, built-in
JSON-to-classes conversion using [Jackson](https://github.com/FasterXML/jackson), request validation and a simple way to
handle errors. On the other hand, Undertow is in some ways leaner but it lets you configure a lot of low level stuff.
Similarly to Javalin, Undertow also comes with some built-in features like routing or different HTTP handlers, but
actual HTTP request handling is not as simple as in Javalin because of a missing built-in JSON-to-classes conversion.

Generally, in both cases, implemented servers are fairly simple, and it took only a few lines to get a working GraphQL API
with basic routing. For a web application, this would be a pretty easy win for Javalin because of all of those
shorthands. But for evitaDB, which is a specialized database and not web application where HTTP APIs are not the main
thing, we think that those shorthands could come short in future expansions of evitaDB or wouldn’t be even used. Another
point against the Javalin in case of evitaDB is the lack of low level HTTP communication configuration. We currently
don’t have any particular use for that, but we think that losing the ability could be unnecessarily limiting in the future.
Thus, we chose the **Undertow** server.

## Conclusion

Every single server, library or framework was successfully used to build a server with the example “echo” API. That
means that, functionally, all of these solutions would be sufficient. But when we incorporated requirements for
performance and embedding possibilities, possible servers, libraries and frameworks narrowed. For example, Spring Boot
solutions required its own build Maven plugins and workflow, the Quarkus solution also required its own workflow and we
couldn’t even make it work across multiple Maven modules, although it should be possible. Micronaut was probably the
worst when it came to setting it up, because not only did it require its own code structure and custom Maven plugin, but also
required its own Maven parent POM which is quite a problem in multi-module projects like evitaDB. This shortcoming could
probably be overcome by investing more time to deeply understand the required dependencies, but we fear that if simple
setup is that difficult, there may be other surprises that could arise in the future. Other solutions were embedded fairly
easily. One exception was the Netty server. Implementation of a server on Netty required a lot of low level HTTP
configuring (which for new users seems almost like gibberish). Another difficulty was the enormous amount of different
HTTP request and response classes that are somehow combined to the final ones. But this is probably due to the
universality of the whole Netty ecosystem.

All source codes and the whole test suite can be found
in [our Github repository](https://github.com/FgForrest/HttpServerEvaluationTest). We appreciate any constructive
feedback and also recommend you to experiment with the sources and draw appropriate conclusions yourself.
