---
title: Choosing HTTP server for evitaDB
perex: We plan to provide several ways to communicate with evitaDB clients. For that we needed some universal HTTP server that would serve at least most of the requests.
date: '17.6.2022'
author: 'Lukáš Hornych, Jan Novotný'
motive: assets/images/03-choosing-http-server.png
---

The main goal right now is to
provide [GraphQL](https://graphql.org/), [REST](https://en.wikipedia.org/wiki/Representational_state_transfer),
[gRPC](https://grpc.io/) and potentially [WebSockets](https://en.wikipedia.org/wiki/WebSocket)
or [SSE](https://en.wikipedia.org/wiki/Server-sent_events) APIs for some specific use-cases. However, there is currently
no foundation for HTTP communication as evitaDB comes only with Java API. Because of this, there was a need to find some
HTTP server, library or framework that would serve as a common foundation for all of those mentioned APIs.

<Note type="info">

<NoteTitle toggles="false">

##### The article updated as of June 2023
</NoteTitle>

Thanks to Francesco Nigro's from RedHat comments in [Issue #1](https://github.com/FgForrest/HttpServerEvaluationTest/issues/1)
(thanks!) we updated:

- updated the versions of all tested web servers to the latest versions,
- fixed the problem in the Netty performance test implementation that erroneously closed an HTTP connection in every iteration,
- enforced the HTTP protocol version to 1.1, so that the servers that allow upgrading to HTTP/2 version don't have an advantage,
- changed the performance test behavior to use a separate HTTP client for each JMH thread

... and re-measured all tests with only a single web server running in parallel.

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
      <Td>Netty</Td>
      <Td>32310</Td>
      <Td>31372</Td>
      <Td>33248</Td>
    </Tr>
    <Tr>
      <Td>microhttp</Td>
      <Td>31344</Td>
      <Td>30597</Td>
      <Td>32092</Td>
    </Tr>
    <Tr>
      <Td>Vert.x</Td>
      <Td>30463</Td>
      <Td>28915</Td>
      <Td>32010</Td>
    </Tr>
    <Tr>
      <Td>Javalin</Td>
      <Td>26649</Td>
      <Td>24502</Td>
      <Td>28796</Td>
    </Tr>
    <Tr>
      <Td>Undertow</Td>
      <Td>25214</Td>
      <Td>22444</Td>
      <Td>27985</Td>
    </Tr>
    <Tr>
      <Td>Micronaut</Td>
      <Td>22169</Td>
      <Td>19626</Td>
      <Td>24712</Td>
    </Tr>
    <Tr>
      <Td>Quarkus</Td>
      <Td>21269</Td>
      <Td>19650</Td>
      <Td>22887</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot WebFlux</Td>
      <Td>20016</Td>
      <Td>18677</Td>
      <Td>21355</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot MVC</Td>
      <Td>15550</Td>
      <Td>15059</Td>
      <Td>16041</Td>
    </Tr>
    <Tr>
      <Td>Quarkus (in native mode)</Td>
      <Td>15433</Td>
      <Td>14516</Td>
      <Td>16351</Td>
    </Tr>
    <Tr>
      <Td>NanoHTTPD</Td>
      <Td>9400</Td>
      <Td>9068</Td>
      <Td>9733</Td>
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
      <Td>Microhttp</Td>
      <Td>193</Td>
      <Td>184</Td>
      <Td>202</Td>
    </Tr>
    <Tr>
      <Td>Vert.x</Td>
      <Td>198</Td>
      <Td>194</Td>
      <Td>201</Td>
    </Tr>
    <Tr>
      <Td>Netty</Td>
      <Td>198</Td>
      <Td>182</Td>
      <Td>215</Td>
    </Tr>
    <Tr>
      <Td>Javalin</Td>
      <Td>229</Td>
      <Td>225</Td>
      <Td>233</Td>
    </Tr>
    <Tr>
      <Td>Undertow</Td>
      <Td>253</Td>
      <Td>232</Td>
      <Td>274</Td>
    </Tr>
    <Tr>
      <Td>Micronaut</Td>
      <Td>283</Td>
      <Td>262</Td>
      <Td>304</Td>
    </Tr>
    <Tr>
      <Td>Quarkus</Td>
      <Td>287</Td>
      <Td>264</Td>
      <Td>309</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot WebFlux</Td>
      <Td>306</Td>
      <Td>281</Td>
      <Td>331</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot MVC</Td>
      <Td>385</Td>
      <Td>378</Td>
      <Td>392</Td>
    </Tr>
    <Tr>
      <Td>Quarkus (in native mode)</Td>
      <Td>391</Td>
      <Td>369</Td>
      <Td>412</Td>
    </Tr>
    <Tr>
      <Td>NanoHTTPD</Td>
      <Td>646</Td>
      <Td>635</Td>
      <Td>657</Td>
    </Tr>
  </Tbody>
</Table>

We're still using the Undertow server, even though it's no longer one of the fastest. We hope it will change its 
internal implementation to a Netty server as [promised for version 3.x](https://undertow.io/blog/2019/04/15/Undertow-3.html).

</Note>

## Criteria and requirements

As the main requirement, we wanted the lowest possible latency and the highest possible throughput of HTTP request
processing and also a simple codebase of chosen server, library or framework so that there would be a smaller
probability of “magic” behavior and unexpected surprises. Another important feature was the ability to embed the HTTP
foundation into the existing evitaDB codebase without needing to adjust the whole running and building processes of
evitaDB to the chosen solution because in future there, may be other ways to communicate with users. An advantage to all of
these requirements would be a simple and straightforward API for handling HTTP requests and errors. This means that there
wouldn't be any unnecessary low level HTTP communication work involved, if not explicitly needed by some specific
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
          <Td>30,199</Td>
          <Td>30,034</Td>
          <Td>30,401</Td>
      </Tr>
      <Tr>
          <Td>Netty</Td>
          <Td>28,689</Td>
          <Td>28,617</Td>
          <Td>28,748</Td>
      </Tr>
      <Tr>
          <Td>Undertow</Td>
          <Td>25,760</Td>
          <Td>25,745</Td>
          <Td>25,793</Td>
      </Tr>
      <Tr>
          <Td>Javalin</Td>
          <Td>23,650</Td>
          <Td>23,399</Td>
          <Td>23,995</Td>
      </Tr>
      <Tr>
          <Td>Vert.x</Td>
          <Td>22,850</Td>
          <Td>22,477</Td>
          <Td>23,070</Td>
      </Tr>
      <Tr>
          <Td>Micronaut</Td>
          <Td>19,572</Td>
          <Td>19,394</Td>
          <Td>19,841</Td>
      </Tr>
      <Tr>
          <Td>Spring Boot WebFlux</Td>
          <Td>18,158</Td>
          <Td>17,991</Td>
          <Td>18,234</Td>
      </Tr> 
      <Tr>
          <Td>Spring Boot MVC</Td>
          <Td>17,674</Td>
          <Td>17,603</Td>
          <Td>17,786</Td>
      </Tr>
      <Tr>
          <Td>Quarkus (in native mode)</Td>
          <Td>11,509</Td>
          <Td>11,383</Td>
          <Td>11,642</Td>
      </Tr>
      <Tr>
          <Td>NanoHTTPD</Td>
          <Td>6,171</Td>
          <Td>6,051</Td>
          <Td>6,254</Td>
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
			<Td>microhttp</Td>
			<Td>131</Td>
			<Td>129</Td>
			<Td>133</Td>
		</Tr>
		<Tr>
			<Td>Netty</Td>
			<Td>145</Td>
			<Td>142</Td>
			<Td>146</Td>
		</Tr>
		<Tr>
			<Td>Undertow</Td>
			<Td>156</Td>
			<Td>156</Td>
			<Td>156</Td>
		</Tr>
		<Tr>
			<Td>Javalin</Td>
			<Td>172</Td>
			<Td>168</Td>
			<Td>175</Td>
		</Tr>
		<Tr>
			<Td>Vert.x</Td>
			<Td>173</Td>
			<Td>172</Td>
			<Td>174</Td>
		</Tr>
		<Tr>
			<Td>Micronaut</Td>
			<Td>202</Td>
			<Td>201</Td>
			<Td>203</Td>
		</Tr>
		<Tr>
			<Td>Spring Boot WebFlux</Td>
			<Td>224</Td>
			<Td>223</Td>
			<Td>225</Td>
		</Tr>
		<Tr>
			<Td>Spring Boot MVC</Td>
			<Td>224</Td>
			<Td>222</Td>
			<Td>233</Td>
		</Tr>
		<Tr>
			<Td>Quarkus (in native mode)</Td>
			<Td>348</Td>
			<Td>345</Td>
			<Td>353</Td>
		</Tr>
		<Tr>
			<Td>NanoHTTPD</Td>
			<Td>642</Td>
			<Td>625</Td>
			<Td>649</Td>
		</Tr>
	</Tbody>
</Table>

*A gist with the raw results can be found [here](https://gist.github.com/novoj/cef56bd940a015b4cfb1ad389d2b6705) and
charts for visualization [here](https://jmh.morethan.io/?gist=cef56bd940a015b4cfb1ad389d2b6705&topBar=HTTP%20web%20server%20upgraded%20versions%20from%2003/2023%20(optimalized)).*

From the above results, there are 3 main adepts for the winner: microhttp, Netty and Undertow. Quite interesting and
surprising are the results of Javalin, which is, in fact, a framework built upon the [Jetty](https://www.eclipse.org/jetty/)
server and not a barebone HTTP server.

The results of the popular Netty server, which is a low level server with the most difficult API of all of them, are 
also very good. Also, we expected the Quarkus server, which was run in native mode using
[GraalVM](https://www.graalvm.org/) to end up in higher positions. In contrast to that, large frameworks such as 
Spring and Vert.x were much more performant than we ever expected due to their complex abstraction.

## Choosing the final solution

Final decision - which server, library or framework to pick was narrowed to the 3 solutions: **microhttp**, **Javalin**
and **Undertow**. Because they performed very similarly, the decision was made based upon their advantages and
disadvantages relevant to evitaDB.

<Note type="info">

<NoteTitle toggles="true">

##### Why was the Netty server excluded from the selection?
</NoteTitle>

In initial performance tests, we made a mistake that led to the low performance of the Netty server compared to other
solutions. This bug was fixed months later after a comment by Francesco Nigro. Due to the initially insufficient number
of servers and the complex API, we excluded Netty from the list of web servers we selected for use in evitaDB.

</Note>

At first, the microhttp server seemed like the one to go with, mainly because of its exceptionally small codebase (around 500
LoC) and a simple and straightforward API. Overall, this server ticked almost all the requirement boxes except the one for
support for WebSockets. But the unsure future of this fairly new project made it quite a deal-breaker. Some other
possible disadvantages may be the lack of support for SSL and other advanced features. With that in mind, we focused on
choosing between Javalin and Undertow. Javalin is a lightweight framework built upon the 
[Jetty](https://www.eclipse.org/jetty/) server and Undertow is an actual HTTP server, yet they performed almost the
same. Both tick all the requirement boxes. Both are performant, easily embeddable, small enough to limit the
possibilities of “magic” surprises, have simple and straightforward APIs and even support WebSockets. Both are popular
and are updated regularly. Both support non-blocking request processing and both should probably run on GraalVM in
the future if needed. Javalin comes with shorthand API configuration methods for setting up endpoints, built-in
JSON-to-classes conversion using [Jackson](https://github.com/FasterXML/jackson), request validation and a simple way to
handle errors. On the other hand, Undertow is in some ways leaner, but it lets you configure a lot of low level stuff.
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
