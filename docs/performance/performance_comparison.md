# Performance comparison

## The latest results

1. [InMemory](https://jmh.morethan.io/?gist=22cfc1fabfaeee01a1ac7a9279ace362&topBar=Evita%20DB%20InMemory%20Performance%20results)
2. [PostgreSQL](https://jmh.morethan.io/?gist=8142b6a726c223298f4f9bf194950925&topBar=Evita%20DB%20PostgreSQL%20Performance%20results)
3. [ElasticSearch](https://jmh.morethan.io/?gist=ddfbb2827dbfaddc839fd10c241855d6&topBar=Evita%20DB%20Elasticsearch%20Performance%20results)

## Comparisons with inMemory implementation

1. [Elastic/InMemory comparison](https://jmh.morethan.io/?gists=ddfbb2827dbfaddc839fd10c241855d6,22cfc1fabfaeee01a1ac7a9279ace362&topBar=Elastic%2FInMemory%20DB%20Performance%20comparison)
2. [PostgreSQL/InMemory comparison](https://jmh.morethan.io/?gists=8142b6a726c223298f4f9bf194950925,22cfc1fabfaeee01a1ac7a9279ace362&topBar=PostgreSQL%2FInMemory%20DB%20Performance%20comparison)

## Vertical scalability

1. [InMemory](https://jmh.morethan.io/?gists=9170a91206f0083cc4db97f0754da838,2cc4e5cc534400400c853eeb3eb2a637,408c9de7fa824d015e3589de33fef054,165d2967a61b42cd02f098c513a4a645&topBar=InMemory%204%20vs.%208%20vs.%2016%20vs.%2032%20CPU%20comparison)
2. [PostgreSQL](https://jmh.morethan.io/?gists=1d5e93a980276fb652c3397817bf545e,64ea8a0e462ffd5e59cbaeef458bf17f,7cc9e9aa88b9485cefa7d162d61d5791,fbd0ec7e255004b88fadf7d3bd1df258&topBar=PostgreSQL%204%20vs.%208%20vs.%2016%20vs.%2032%20CPU%20comparison)
3. [Elasticsearch](https://jmh.morethan.io/?gists=8177df5301ce212fa1fbbb238bcd1301,8a6c624d4640524dd3152965864aa527,3793b7967bc4693612cb130feb634d20,84535edfd043b01e18043c8b7489f789&topBar=Elasticsearch%204%20vs.%208%20vs.%2016%20vs.%2032%20CPU%20comparison)

[Vertical scalability detail results](vertical_scalability.md)

## Archive

[Results archive](https://gist.github.com/evita-performance)

## Test environment specifications

Final stage of the research phase ends up with functional and performance testing that compare all prototypes of the
evitaDB. evitaDB is targeted to middle low to middle tier of the e-commerce recommended hosting programs and
as such we will be aiming for something similar to [Digital Ocean General Purpose Droplet](https://www.digitalocean.com/docs/droplets/resources/choose-plan/)
that is recommended for e-commerce sites. In order to get repeatable and comparable results the test environment will
have dedicated CPUs.

Considered specification of the environment is:

- 4x CPU (Intel Xeon Skylake or Cascade Lake processors, which have a 2.7GHz base clock speed)
- 16GB RAM
- 25GB SSD (non NVMe)
- OS: Linux (Ubuntu 20.04)
- JDK 11

Specification may change, but for now, this is our working specification.

## Testing process

Each prototype will be executed as Docker Compose composition of required services. evitaDB prototype is expected
to be a standalone JAR executed from command line. Along with the prototype there would be functional and performance
test suite bundled in and after start up and warm up phase, these tests will be executed, and their results recorded.
Final evaluation will build on the results / reports acquired from this process.

FG Forrest operation team will provide its expertise for the UHK teams while the Docker Compose configuration and
database engine configuration and tuning.

Docker would bring us the possibility to run and develop the prototypes also on local developer machines. Docker is
currently supported on all major operating systems - Linux, MacOS, Windows (with WLS). Testing environment may
be provisioned in later stages of the research phase - initial prototype implementations are expected to be developed
and run only on developer machines. 

## Evaluation priorities

1. fulfilling all must-have functional specifications
2. reading operations
   - queries/sec (throughput)
   - query latency
3. fulfilling nice-to-have functional specifications 
4. indexing speed
   - mutations/sec (throughput)
5. HW requirements
    - RAM unused capacity (weighted average)
    - CPU unused capacity (weighted average)

## Implementation-specific configurations

Each implementation of EvitaDB has its own requirements for environment configuration. These configurations are mainly about
correctly setting up underlying engine to fit hardware on which the implementation will eventually run.

### InMemory

In-memory implementation keeps everything in memory that is allocated by the Java process. So we need to allocate as much memory
to Java itself as possible. This is controlled by following option in `.gitlab-ci.yml`:

```
BENCHMARK_JAVA_OPTS: "-Xmx9g"
```

Senesi dataset requires 9GB or RAM and thus 16GB configuration is required for performance tests.
According to Digital Ocean documentation for Kubernetes there is recommended memory usages for each of available HW configurations.
This is copied into an internal document: `src/do_k8s_automation/do_size_slug.txt`
For 16GB spec, there should be 13GB usable by the "aplication" - we've tested that we can assign only 9-10GB to Java for that spec
which is even less than documented. When increasing available memory I'd recommend assigning about 60% of the memory for in-memory
Java implementation.

### PostgreSQL

For each catalog this implementation of EvitaDB needs to have access to separate running PostgreSQL database.
Connection details for each catalog are then specified in configuration of each catalog when creating new Evita instance.

This implementation is built around PostgreSQL version 14.1, therefore it is recommended to use this particular version.
Depending on size of testing dataset and hardware it is recommended to configure PostgreSQL server to use all resources
possible. Useful tool which can help with correct configuration for your hardware is [PGTune](https://pgtune.leopard.in.ua/).

![PostgreSQL PGTune](../research/thesis-assignment/assets/images/postgresql_pgtune.png)

*Example PostgreSQL configuration*

The most suitable DB Type according to PGTune's description is OLTP as EvitaDB is mainly focused on executing complex read queries
and occasional writes.
For running the actual EvitaDB instance with performance tests around 4 GB of RAM should be reserved for the actual
EvitaDB instance. Therefore, PostgreSQL should not be configured to use up all of available RAM.

Other quite important setting for large datasets, which the PGTune does not cover, is:
```properties
max_locks_per_transaction = 120
```
Default of `64` is too small for bulk inserts of datasets with large quantities of prices and attributes.

Senesi dataset requires at least 7 GB or RAM and therefore 16GB configuration is required for performance tests if
running in Digital Ocean environment as some of the RAM is reserved by Digital Ocean as described in this file
`src/do_k8s_automation/do_size_slug.txt`.

Also, there is ready-to-use `docker-compose` file for starting PostgreSQL server as Docker container 
with pre-configured databases for all catalogs needed by functional and performance tests and basic server configuration with some 
useful defaults for running EvitaDB on at least 13 GB of RAM and 4 CPU cores (16GB in case of Digital Ocean). 
This configuration can be used as-is for development purposes or as starting point for
custom configuration for testing. Check `/evita_db_sql/src/test/resources/database/README.md` for more information.

### ElasticSearch

Last implementation listed is implementation that uses ElasticSearch engine as the core. 
This implementation, just like InMemory, mostly strictly rely on memory assigned to the engine. 
In this case and scenarios evaluated in performance tests it's recommended to assign at least 10GB of RAM memory to 
elastic itself - by adding argument `-Xmx10g`.

In case of testing by Gitlab CI/CD, it's possible to define this argument by adding:
```yml
ES_JAVA_OPTS: "-Xmx10g"
```
As import as argument to determine elasticsearch memory, there is also argument for application on top of the engine 
(EvitaDb implementation). For this app we recommend specifying at least 3 GB of memory:
```yml
BENCHMARK_JAVA_OPTS: "-Xmx3g"
```

This separation of memory allocation needs to be done for two main reasons:
 - elasticsearch could "eat up" all memory that machine has and so implementation wouldn't be able to serve the requests.
 - application caches more data than is needed and allocate memory that is critically needed by hosting OS.

For smaller datasets (like artificial dataset in performance tests), it's possible to put arguments to lower boundaries, 
but keep in mind that even if EvitaDB implementation would be less demanding - elasticsearch has own mechanisms that could suddenly
increase memory needs even if dataset is relatively small. For example when computing facet summary there are high-load queries that could 
end up in state where elasticsearch will still be clearing its own memory by GC and practically deadlocks any requests.
This eventuality happens a lot when elasticsearch is low on memory and there are many clients that has large computations queries.
To avoid this, we recommend wisely set memory size on behalf of the number of Evita nodes or possibly configure 
[GC mechanism](https://opster.com/guides/elasticsearch/capacity-planning/elasticsearch-heap-size-usage/) or 
[Circuit breaker](https://www.elastic.co/guide/en/elasticsearch/reference/current/circuit-breaker.html).

Another important setting for elasticsearch is thread_pool of elasticsearch. EvitaDB has one common speciality in using elasticsearch, 
most of other usages of this engine uses relatively shallow queries to shallow structures, but containing large data. 
Our case is opposite, we have deep queries to deep structures with relatively small data (even if we count associated data). 
Because of this, elasticsearch has large demand of memory, as written before, but even if there is enough memory elasticsearch 
has set up thread pool and when this pool is all used, another requests are put in the queue. This behavior is excellent in case of fast queries, 
but in case that few users wants to count prices and others facet summaries, pool will be freed after seconds! 
So we need to set some high number to: 

```yml
 thread_pool:
  search:
   size: 50
   queue_size: 2000
```

In case there ain't enough threads in os, queue will be created at os level, not at the elasticsearch.

Last setting is just for cases when dataset is exceptionally large, so one shard won't be able to handle all data, 
we recommend adding another argument `-DshardSize=3`.

For specifying port and address of the elastic feel free to use common EvitaDB arguments `-Ddbhost=elastic -Ddbport=9200`.

**General recommendation**, in case of scaling, make sure to increase both memory allocation - `ES_JAVA_OPTS` and `BENCHMARK_JAVA_OPTS` in an accurate ratio of about `10:3`.
If the scaled machine has more large number of computational cores, more than let's say 16. Increase `evita_performance_tests/src/do_k8s_automation/elastic/elasticsearch.yml:234`
thread `size` to a larger number, about 70-90 should be optimal in that case. Because of the large amount number of requests that ES implementation sends to ES 
this number should not be decreased to a lower number than the original 50.  
