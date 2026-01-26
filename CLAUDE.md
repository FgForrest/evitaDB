# evitaDB - Claude Code Guidelines

evitaDB is a specialized NoSQL in-memory database with easy-to-use API for e-commerce systems. It handles complex e-commerce tasks with low-latency and is designed to act as a fast secondary lookup/search index for front stores.

## Code Style

- **Indentation**: Use tabs for indentation
- **Line Length**: Limit lines to 100 characters
- **Java modules**: Use Java modules to organize code
- **JavaDoc**: Use Markdown syntax for formatting in JavaDoc - never use HTML tags
- **Data structures**: Prefer immutable classes / records for data structures
- **Annotations**: Automatically add `javax.annotation.Nullable` and `javax.annotation.Nonnull` annotations to method parameters and return types
- **Local variables**: Use `final` for local variables
- **Instance variables**: Use `this` for instance variables
- **Type declarations**: Never use `var` - always use explicit types
- **Documentation**: Automatically add JavaDoc to all generated classes and methods
- **Comments**: Add line comments to complex logic

### Performance-Critical Code

- Prefer performance to readability in performance-critical code
- Avoid unnecessary memory allocations
- Avoid unnecessary object boxing
- Avoid streams - write allocation optimized loops instead
- Avoid using exceptions for control flow

## Key External Libraries

- **RoaringBitmap**: For working with bitmaps
- **Kryo**: For binary serialization and deserialization
- **Netty and Armeria**: For web server and client functionality
- **Logback**: For logging
- **Jackson**: For JSON serialization and deserialization
- **gRPC Java**: For gRPC functionality
- **GraphQL Java**: For GraphQL functionality
- **Byte Buddy**: For runtime code generation
- **MinIO Java**: For S3-compatible storage operations

## Building

- **Primary**: try to use IntelliJ MCP for building and running the project, when not possible use Maven
- **CLI Build Tool**: Maven
- **Java Version**: OpenJDK 17 (requires Maven toolchains configuration)

Build command:

```shell
mvn clean install
```

## Testing

- **Framework**: JUnit 5
- **Test location**: All test files are located in `evita_test/evita_functional_tests/src/test/java`
- **Test naming**: Use format `shouldDoSomethingWhenCondition` or `shouldThrowExceptionWhenCondition`
- **Display names**: Use `@DisplayName` for entire class and for test methods to provide clear descriptions (do not repeat class description content in method descriptions)
- **Coverage**: Automatically generate test cases for all public methods
- for running tests try to use IntelliJ MCP, when not possible use Maven, 
  prefer running individual test classes over running entire test suite, if you run entire test suite use profile `unitAndFunctional`

## Project Organization

### Core Modules

- **evita_common**: Shared functions, exceptions, data types, and common utilities
- **evita_query**: Query language (EvitaQL), query parser, and utilities for query handling
- **evita_api**: Public API including data type conversions and basic structures
- **evita_engine**: Implementation of the database engine core

### Storage Layer

- **evita_store_key_value**: Key-value store implementation with binary serialization using Kryo
- **evita_store_entity**: Entity storage format and Kryo serialization (shared between server and Java client)
- **evita_store_server**: Server data structures persistence implementation
- **evita_traffic_engine**: Traffic engine recorder for storing traffic data

### Export
- **evita_export_fs**: Export service implementation for local file system
- **evita_export_s3**: Export service implementation for S3-compatible storage

### External APIs

- **evita_external_api_core**: Shared logic for all web APIs, Armeria HTTP server integration
- **evita_external_api_graphql**: GraphQL API implementation
- **evita_external_api_grpc**: gRPC API implementation (includes shared stubs, server, and Java client driver)
- **evita_external_api_rest**: REST API implementation with OpenAPI/Swagger support
- **evita_external_api_system**: System API for server management and monitoring
- **evita_external_api_lab**: evitaLab GUI client server support
- **evita_external_api_observability**: Observability API with Prometheus metrics and OpenTelemetry tracing

### Bundles

- **evita_db**: Maven POM bundle for embedded evitaDB usage scenario
- **evita_server**: Standalone server with all APIs bundled

### Testing Modules

- **evita_test_support**: Utility classes for writing integration tests
- **evita_functional_tests**: Test suite verifying functional correctness
- **evita_performance_tests**: JMH-based performance tests

## Code Quality Requirements

- Line coverage with unit tests must be >= 70%
- All classes and methods must have comprehensible JavaDoc
- No TODO statements in committed code
- No commented out code
