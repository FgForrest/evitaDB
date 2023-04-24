<h1 align="center" style="border-bottom: none">
    <a href="https://evitadb.io" target="_blank"><img src="https://raw.githubusercontent.com/FgForrest/evitaDB/dev/docs/assets/img/evita.png"/></a><br>evitaDB
</h1>

<p align="center">Visit <a href="https://evitadb.io" target="_blank">evitadb.io</a> for the full documentation,
examples and guides.</p>

<p align="center">
  <a href="https://github.com/FgForrest/evitaDB" title="Build"><img src="https://img.shields.io/github/v/release/FgForrest/evitadb?color=%23ff00a0&include_prereleases&label=version&sort=semver"/></a>
  &nbsp;
  <a href="https://codecov.io/gh/FgForrest/evitaDB"><img src="https://codecov.io/gh/FgForrest/evitaDB/branch/dev/graph/badge.svg?token=9VDOBPOBFL"/></a>
  &nbsp;
  <a href="https://github.com/FgForrest/evitaDB" title="Platform"><img src="https://img.shields.io/badge/Built%20with-Java-red"/></a>
  &nbsp;
  <a href="https://github.com/FgForrest/evitaDB" title="GitHub Workflow Status"><img src="https://img.shields.io/github/actions/workflow/status/FgForrest/evitaDB/ci-dev.yml"/></a>
  &nbsp;
  <a href="https://github.com/FgForrest/evitaDB/blob/master/LICENSE" title="License"><img src="https://img.shields.io/badge/license-BSL_1.1-blue.svg"/></a>
</p>

<p align="center">
  <a href="https://evitadb.io/en/blog" title="Blog"><img src="https://img.icons8.com/carbon-copy/100/FFFFFF/blog.png" width="50px"/></a>
  &nbsp;
  <a href="https://evitadb.io/documentation/index" title="Documentation"><img src="https://img.icons8.com/carbon-copy/100/FFFFFF/saving-book.png" width="50px"/></a>
  &nbsp;
  <a href="https://evitadb.io/research/introduction" title="Research"><img src="https://img.icons8.com/carbon-copy/100/FFFFFF/microscope.png" width="50px"/></a>
  &nbsp;
  <a href="https://twitter.com/evitadb_io" title="Twitter"><img src="https://img.icons8.com/carbon-copy/100/FFFFFF/twitter.png" width="50px"/></a>
  &nbsp;
  <a href="https://github.com/FgForrest/evitaDB/" title="GitHub"><img src="https://img.icons8.com/carbon-copy/100/FFFFFF/github.png" width="50px"/></a>
  &nbsp;
  <a href="https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x9d1149b0c74e939dd766c7a93de3cdccf660797f" title="PGP public key"><img src="https://img.icons8.com/carbon-copy/100/FFFFFF/fingerprint-scan.png" width="50px"/></a>
</p>

evitaDB is a specialized database with easy-to-use API for e-commerce systems. It is a low-latency NoSQL in-memory engine 
that handles all the complex tasks that e-commerce systems have to deal with on a daily basis. evitaDB is expected to act 
as a fast secondary lookup/search index used by front stores.

We aim for an order of magnitude better latency (10x faster or better) for common e-commerce tasks than other SQL or 
NoSQL database solutions on the same hardware specification. evitaDB should not be used for storing and processing primary data.

## Why should you consider using evitaDB instead of Elasticsearch, MongoDB or relational database?

- evitaDB is a database specialized for e-commerce tasks and has everything you need to implement an e-commerce catalog
- evitaDB is [more performant](docs/performance/performance_comparison.md) than Elasticsearch or PostgreSQL on the same
  HW sizing in typical e-commerce scenarios
- evitaDB has a ready to use API from the day one:

    - [GraphQL](docs/user/en/use/connectors/graphql.md) - targets rich JavaScript front-ends
    - [REST](docs/user/en/use/connectors/rest.md) - targets server side applications
    - [gRPC](docs/user/en/use/connectors/grpc.md) - targets fast inter-server communication used in microservices 
      architecture and is used for the evitaDB client drivers

## What's current status of evitaDB?

evitaDB is currently under active development. evitaDB is supported by the company [FG Forrest](https://www.fg.cz),
which specializes in the development of e-commerce stores for large clients in the Czech Republic and abroad. evitaDB
concepts have been proven to work well in production systems with annual sales exceeding 50 million €.

Engineers from FG Forrest cooperate with academic team from [University of Hradec Králové](https://www.uhk.cz), so our
statements about evitaDB performance are backed by thorough (and unbiased) testing and research. All proofs can be found
in [this repository](https://github.com/FgForrest/evitaDB-research), and you can run tests on your HW to verify our conclusions.

## What's the license of the evitaDB

evitaDB is licensed under the [Business Source License 1.1](LICENSE). Technically, it is not
an open source license, but is an [open source friendly](https://itsfoss.com/making-the-business-source-license-open-source-compliant/)
license, because it automatically converts to one after a period of time specified in the license.

We're fans of open source, and we've benefited a lot from open source software (even the database engine uses some of it).
The database implementation has taken thousands of man-days and, if successful, will take several thousand more. We were
lucky to get an [EU grant](https://evitadb.io/project-info) that partially funded the initial implementation, but we
need to build a self-sustaining project in the long run. [Our company](https://www.fg.cz) uses evitaDB for its own
commercial projects, so the development of the database is guaranteed, but without additional income the development
would be limited. That's why we have chosen this type of license, but in the end we allow you - our users - almost any
use.

**In a nutshell:**

- the BSL license covers a period of 4 years from the date of the software release
- 4 year old version of evitaDB becomes [permissive Apache License, v.2](https://fossa.com/blog/open-source-licenses-101-apache-license-2-0/)
- both BSL and Apache licenses allow you to use evitaDB for OSS and/or commercial projects free of charge
- there is one exception - you may not offer and sell evitaDB as a service to third parties

That's it.

[Read license FAQ](https://evitadb.io/documentation/use/license)

## Prerequisities

evitaDB requires and is tested on OpenJDK 17.

Java applications support multiple platforms depending on the
[JRE/JDK vendor](https://wiki.openjdk.org/display/Build/Supported+Build+Platforms). All major hardware
architectures (x86_64, ARM64) and operating systems (Linux, MacOS, Windows) are supported. Due to the size of our
team, we regularly test evitaDB only on the Linux AMD64 platform (which you can also use on Windows thanks to the
[Windows Linux Subsystem](https://learn.microsoft.com/en-us/windows/wsl/install)). The performance can be worse,
and you may experience minor problems when running evitaDB on other (non-Linux) environments. Please report any bugs
you might encounter, and we'll try to fix them as soon as possible.

## How this repository is organized

- **docs**: research documents, documentation, specifications
- **evita_api**: set of all supported data types in evitaDB, conversions to & from other types, common data structures, basic exception hierarchy
- **evita_db**: Maven POM allowing to link all necessary libraries for embedded evitaDB usage scenario
- **evita_engine**: implementation of the database engine
- **evita_external_api**: web API implementation
  - **evita_external_api_core**: shared logic for all web APIs, Undertow web server integration, annotation framework for APIs
  - **evita_external_api_graphql**: implementation of GraphQL API
  - **evita_external_api_grpc**: implementation of gRPC API
    - **client**: Java driver for client/server usage scenario  
    - **server**: gRPC server  
    - **shared**: shared classes between client & server (generated gRPC stubs)
  - **evita_external_api_rest**: implementation of REST API
- **evita_functional_tests**: test suite verifying functional correctness of standard and edge cases of the API on a
  small amount of data, this library also contains unit tests for evita_db
- **evita_performance_tests**: test suite executing most common operations on real world data that generates performance
  statistics of each implementation
- **evita_query**: query language, query parser, utilities for query handling
- **evita_store**: binary serialization using Kryo library, persistent key/value datastore implementation
- **evita_test_support**: utility classes that make writing integration tests with evitaDB easier
- **jacoco**: Maven POM that allows to aggregate test coverage for entire project
- **workaround**:
  - **grpc**: workaround shaded build allowing to link gRPC libraries as a single "automatic Java module", 
    direct linking of original libraries leads to problems with Java module system 
  - **roaringBitmap**: workaround shaded build allowing to link RoaringBitmap libraries as a single "automatic Java module",
    direct linking of original libraries leads to problems with Java module system

# Quality requirements for the code

In order code to be accepted it will fulfill following criteria:

- line coverage with unit tests will be >= 70%
- all classes and methods will have comprehensible JavaDoc
- there will be no TODO statements in the code
- there will be no commented out code

-------------------------------------------------------------------------

[Icons sourced at Icons8.com](https://icons8.com/)

[//]: # (https://icons8.com/icon/set/github/carbon-copy--static--white)