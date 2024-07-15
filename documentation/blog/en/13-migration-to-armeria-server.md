---
title: Migration to Armeria Server
perex: |
  evitaDB started its journey with the Undertow server from JBoss. It was chosen because of its performance and ease of 
  use. However, due to the gRPC API, we still had to run a separate Netty server under the hood. This was far from ideal,
  and we hoped that with Undertow 3.0, which promised to move to Netty, we could unify all APIs under one server. 
  Unfortunately, the development of Undertow 3.0 is not going anywhere and we had to look for alternatives.
date: '12.7.2024'
author: 'Jan Novotný'
motive: assets/images/13-armeria-motive.png
proofreading: 'todo'
---

[Armeria server](https://armeria.dev/) is a Java microservices framework built on top of Netty, by members of Netty team,
developed in South Korea and Japan. It wraps quite complex Netty server API into much simpler one and also solves some
quite hard problems we will talk about in this article. Armeria framework targets similar area as [Quarkus](https://quarkus.io/)
or [Micronaut](https://micronaut.io/) frameworks, but is can be still used very easily as low-level asynchronous server
without any "annotation magic" and that's what we needed.

## Why Armeria?

First of all, we looked for a solution to serving gRPC for web clients. gRPC is a binary protocol on top of HTTP/2, which
cannot be directly consumed by rich JavaScript applications (directly from the browser). It requires a special gRPC-Web
proxy, which translates gRPC calls to JSON format between the client and the server. By having such proxy, we could
avoid creating a new layer for our [evitaLab](12-evitalab-after-6-months.md) tooling, and we could implement all necessary 
services in gRPC protocol, which in turn greatly enhances the possibilities of Java client and other clients in the future.

Secondly, we wanted to have a single server for all our APIs. We have a REST API, a gRPC API, and a GraphQL API.
Single server will save a lot of resources because all thread pools, sockets, buffers etc. can be shared between all APIs.
It would also allow us to use single port for all the services if we wanted to, which was not possible in our current
setup.

And last but not least, we wanted to have a server that is easy to use, has good performance, and is actively developed.
The asynchronous reactive Netty server is a good choice, which promises good enough performance and Armeria team is
very active and helpful on their [Discord](https://armeria.dev/s/discord) channel. Such a good support is not very common
nowadays.

## What we got with Armeria

With migration to Armeria we can now provide all our APIs and services on single port, which is a huge simplification.
This can be of course changed in configuration and operate each API on separate port, but we have now complete freedom
about the port layout.

This fact also requires us to be able to serve both TLS and non-TLS traffic on the same port, which was not possible
before. Armeria can do this out of the box, and it can also automatically generate self-signed certificates for us.
Our default API layout is now as follows:

- `https://server:5555/rest/**` - REST API, TLS only
- `https://server:5555/gql/**` - GraphQL API, TLS only
- `https://server:5555/**` - gRPC API, TLS only
- `http://server:5555/system/**` - system API, non-TLS only
- `http://server:5555/observability/**` - observability API, non-TLS only

We can also switch any API to *relaxed* mode which will operate on both TLS and non-TLS traffic on the same port and 
path depending on what the client requests. This is invaluable for testing and development purposes, although we don't
recommend to use *relaxed* mode in production.

Another big improvements were related to our gRPC API. Existing gRPC API is now consumable directly from web browsers
thanks to gRPC-Web proxy built-in into Armeria. There is also "automatic" gRPC documentation and test tooling for gRPC
API built in Armeria, which is very useful for development and testing. We are considering to integrate this service
in our evitaLab directly so that we have all testing tools for all our APIs in one place.

![Armeria gRPC documentation viewer](https://armeria.dev/static/07425b49e3908d0a974b067ff0a964eb/f594a/docservice-carousel-1.webp)

We've also rewrote our Java client to use Armeria client instead of plain Java gRPC client. Its API is much simpler and
more powerful than the original one. We're looking forward to discovering all the possibilities it offers, because we
have only scratched the surface so far.

## Asynchronous request processing trap

Migration to Armeria was not without problems. When you implement [HttpService](https://github.com/line/armeria/blob/main/core/src/main/java/com/linecorp/armeria/server/HttpService.java) the serve method is 
executed within the event loop thread. Even the request body parsing is done asynchronously, so you simply can't block
and wait for the request body in the serve method. This was a fundamental shift from our original logic implemented
for Undertow, but [Armeria team helped us out](https://discord.com/channels/1087271586832318494/1087272728177942629/1253656914106253374).

The key to solving this problem is to use `HttpResponse.of(CompletableFuture<> lambda)`, which allows deferring
the request handling to a point when the request body is available and chain the processing logic in a non-blocking way.

## Dynamic routing

Another problem we had to solve was dynamic routing. We have a lot of endpoints that are not known at compile time, but
are set up dynamically according to database schemas in runtime. We assume this could be handled by 
[Server#reconfigure](https://github.com/line/armeria/blob/main/core/src/main/java/com/linecorp/armeria/server/Server.java)
method, but in the first version we ported Undertow PathHandler logic and used our existing logic to handle dynamic
routing in Armeria. This was not ideal, but it worked.

## Jigsaw is still a problem in 2024

evitaDB is fully modularized using Java 9 modules. But this fact complicates our life from the start. Armeria was not
modularized and implemented [automatic modules](https://medium.com/technowriter/heres-a-cool-java-9-feature-automatic-module-name-2746641ebb7) 
on our request. Although this works nice with Javac compiler and Maven, IntelliJ IDEA we use for development has still
[some bugs](https://youtrack.jetbrains.com/issue/IDEA-353903) in its implementation of Java 9 modules and refused 
to compile the project. Fortunately we've found a workaround and helped ourselves with manual exclusion of `module-info.java`
files from the compilation. Let's hope that IDEA will fix these bugs soon.

It's miserable that Jigsaw is still a problem in libraries and tooling in 2024 - more than 7 years after its release.

## Summary

Although the migration brings some incompatible changes in server configuration, we believe Armeria has great future and
is the right choice for evitaDB and it's development. New version is already merged to `dev` branch and will be released 
in version `2024.9` in the next month.

TODO PERFORMANCE RESULTS
