# evitaDB - Claude Code Guidelines

evitaDB is a specialized NoSQL in-memory database with easy-to-use API for e-commerce systems. It handles complex e-commerce tasks with low-latency and is designed to act as a fast secondary lookup/search index for front stores.

## Building

- **Primary**: try to use IntelliJ MCP for building and running the project, when not possible use Maven
- **CLI Build Tool**: Maven
- **Java Version**: OpenJDK 17 (requires Maven toolchains configuration)

Build command:

```shell
mvn clean install
```

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

## Project Structure

See "How this repository is organized" in README.md for module descriptions and dependency graph.

## Code Quality Requirements

- Line coverage with unit tests must be >= 70%
- All classes and methods must have comprehensible JavaDoc
- No TODO statements in committed code
- No commented out code
