# Junie Guidelines

This document outlines the coding guidelines and best practices for the project. It is intended to ensure consistency, readability, and maintainability across the codebase.

### Code Style

- Indentation: Use tabs for indentation
- Line Length: Limit lines to 100 characters
- Java modules: Use Java modules to organize code
- In JavaDoc use Markdown syntax for formatting; never use HTML tags
- Prefer immutable classes / records for data structures
- Automatically add `javax.annotation.Nullable` and `javax.annotation.Nonnull` annotations to method parameters and return types
- Use `final` for local variables
- Use `this` for instance variables
- Never use `var` — always use explicit types
- Automatically add JavaDoc to all generated classes and methods
- Add line comments to complex logic

### Performance-Critical Code

- Prefer performance to readability in performance-critical code
- Avoid unnecessary memory allocations
- Avoid unnecessary object boxing
- Avoid streams; write allocation-optimized loops instead
- Avoid using exceptions for control flow

### Key external libraries

- RoaringBitmap: for working with bitmaps
- Kryo: for binary serialization and deserialization
- Netty and Armeria: for web server and client functionality
- Logback: for logging
- Jackson: for JSON serialization and deserialization
- gRPC Java: for gRPC functionality
- GraphQL Java: for GraphQL functionality
- Byte Buddy: for runtime code generation
- MinIO Java: for S3-compatible storage operations

### Building

- Build Tool: Use Maven for building the project
- Java Version: OpenJDK 17 (requires Maven toolchains configuration)

### Testing

- JUnit 5: Use JUnit 5 for unit tests
- All test files are located in the `evita_test/evita_functional_tests/src/test/java` directory
- Automatically generate test cases for all public methods
- Name tests using the format `shouldDoSomethingWhenCondition` or `shouldThrowExceptionWhenCondition`
- Use @DisplayName for entire class
- Use @DisplayName for test methods to provide a clear description of the test; do not repeat the content in class @DisplayName

### Project organization

Project structure and organization is described in "How this repository is organized" of the README.md file located in the root directory of the repository.

### Code quality requirements

- Line coverage with unit tests must be >= 70%
- All classes and methods must have comprehensible JavaDoc
- No TODO statements in committed code
- No commented out code