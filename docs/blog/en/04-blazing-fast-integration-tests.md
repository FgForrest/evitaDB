---
title: Thousands integrations tests under 30 seconds? Yes, you can.
perex: |
    A fast test suite is a critical aspect that motivates developers to write more tests and run the suite frequently. 
    The ideal test suite should finish in a matter of seconds or low units of minutes.
date: '2023-01-12'
author: 'Ing. Jan Novotný'
motive: assets/images/04-blazing-fast-integration-tests.png
proofreading: 'needed'
---

This requirement is easily met with pure unit tests that have no interaction with the environment. When tests involve 
communication with an external system, such as a database, this resolution is often impossible to maintain. Before you 
read any further, ask yourself: how long have your integration tests been running?

The evitaDB test suite, including the integration ones, runs on developer laptops with 6 physical CPUs (12 threads) for 
about 30 seconds. The CPU on my developer machine is `Intel(R) Core(TM) i7-10750H @ 2.60GHz`. The tests use all 6 
CPUs and the tests create 65 database instances, with 13 running in parallel side by side at peak, insert nearly 9,
000 entities into the databases, access the web API on 31 ports, generate SSL self-signed certificates, and test in 
parallel from HTTP clients over an encrypted protocol. If you don't believe me, check out the video below:

<p style="margin-top: 1em; margin-bottom: 1em;">
    <video width="850" height="478" controls="controls">
      <source src="https://evitadb.io/download/automated_testing.mp4" type="video/mp4"/>
        Your browser does not support the video tag.
    </video>
</p>

**The obvious question is:**

## How did we achieve such results?

There are two main advantages of the evitaDB:

1. it's an in-memory database, which is naturally quite fast
2. it's a lightweight embedded database that can be started at the snap of a finger

But neither of them alone will produce the result you've seen in the screen recording.

We use the experimental 
[JUnit 5 Parallel Tests](https://junit.org/junit5/docs/snapshot/user-guide/#writing-tests-parallel-execution) feature 
that allows us to run our tests at full speed on all host CPUs. Since evitaDB is mainly an in-memory database, we use 
the CPUs almost to their full capacity and the I/O doesn't get in the way. Enabling parallel tests is a matter of a few 
lines in <SourceClass>evita_functional_tests/src/test/resources/junit-platform.properties</SourceClass>. 
The hard part is implementing the tests in a way that allows them to run in parallel.

## Principles of fast parallel, integration tests

There are implicit barriers to overcome in all integration suites, regardless of the technology or database used, and 
principles to follow:

### Immutable shared data and isolated writes

To make integration testing fast, you need to avoid each test class (or worse, each test method) creating its own test 
dataset to work with. This means that you need to think about the content of the data set that would satisfy 
the requirements of as many tests as possible. It also means that no test can modify the shared data in such a dataset,
or do so in a [way that does not affect the other tests](http://xunitpatterns.com/Transaction%20Rollback%20Teardown.html).

### Multiple datasets alive

The first principle is hard to maintain in a large team. It's hard to do the same thing on a one-person team over a long 
period of time as the code you write evolves. New features require a different data set composition, and you don't want 
to rewrite your older tests. Creating an additional dataset is a natural and easy way to test new system functionality 
at low cost.

Since we want to run the tests in parallel, we can't easily manage the order in which the tests are executed. It can 
easily happen that first tests require dataset `A`, then tests require dataset `B`, and then the JUnit framework 
executes the tests working with dataset `A` again.

We need to be able to operate multiple datasets simultaneously without colliding one with another. Can we do that with
a regular database? Probably, but with a significant overhead. If you run your database engine in a Docker 
you can dynamically spawn a new container instance. You could also create a new database schema in the same engine and
have your application use the correct database schema within a specific test method. Both of these options have 
their own issues, whether it is resource consumption, synchronization issues or implementation complexity.

### Keep control of the battlefield

Writing parallel applications is [hard on its own](https://www.cs.cmu.edu/~jurgend/thesis/intro/node2.html). You need 
to provide a simple and predictable mechanism for handling datasets, so that the developers using it don't get confused 
and maintain control over the tests, and can always find out why the test failed when it fails.

The more obstacles your database throws in the way of concurrent testing, the more sophisticated and complex constructs 
you'll have to invent to overcome them, and the harder it will be for developers to reason about the "weird" reasons for 
test failures.

## How does the evitaDB test suite handle this?

### No shared storage

evitaDB stores its data in a local file system. Test datasets are stored in an operating system temporary folder - each 
dataset instance in its own subfolder with a randomly generated name. When you run our test suite, you can observe 
different subfolders appearing and disappearing in the `/tmp/evita` folder.

The same principle is applied to an evitaDB web server, which generates self-signed 
<Term location="docs/user/en/operate/tls.md">certificate authority</Term> and server and client 
<Term location="docs/user/en/operate/tls.md" name="certificate">certificates</Term>. 
All of these are stored in a randomly named folder that is isolated from other instances.

The evitaDB client, which has to pass the [mTLS verification](/documentation/operate/tls?codelang=java#mutual-tls-for-grpc)
and download the generic client certificate, stores the 
<Term location="docs/user/en/operate/tls.md" name="certificate">certificates</Term> in the separate isolated folder.

Without this principle, some tests could (and will) start rewriting the dataset/certificate contents while other tests 
running in parallel are still using it.

#### Port management

Several evitaDB instances can be running at the same time during the tests, and some of them need to open some web APIs
that are being tested. So logically we need to manage the list of network ports used by each of the evitaDB instances, 
since only one web server can listen to a single port. This logic is handled by the
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/PortManager.java</SourceClass> class, which maintains the 
list of ports used by each of the test datasets and keeps track of released ones when the dataset is destroyed.

### No globally shared state

No part of the evitaDB codebase can use so-called [singletons](https://www.baeldung.com/java-singleton) or mutable 
static fields. This applies not only to production code, but also to entire test stacks and test implementations. 
While this may sound simple, it's often hard to do if you don't have control over your entire stack. The fact that our 
entire test suite is able to run in massive parallel is proof that all evitaDB logic is properly encapsulated and 
isolated within class instances.

## The next turn is yours

The good news is that you can use the same test support in your own integration tests with evitaDB.
Read [our documentation](/documentation/use/api/write-tests?codelang=java) and replicate our approach in your 
integration tests. Keep the time required to execute your integration test suite to a minimum and enjoy the convenience
of running all tests locally after each change to your application code.