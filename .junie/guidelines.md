# Junie Guidelines

This document outlines the coding guidelines and best practices for the Junie project. 
It is intended to ensure consistency, readability, and maintainability across the codebase.

### Code Style

- **Indentation**: Use tabs for indentation
- **Line Length**: Limit lines to 100 characters
- **Java modules**: Use Java modules to organize code

- in JavaDoc use MarkDown syntax for formatting - never use HTML tags
- prefer immutable classes / records for data structures
- prefer performance to readability in performance-critical code
- automatically add `javax.annotation.Nullable` and `javax.annotation.Nonnull` annotations to method parameters and return types
- use `final` for local variables
- use `this` for instance variables
- never use `var` - always use explicit types
- automatically add JavaDoc to all generated classes and methods
- add line comments to complex logic
- avoid unnecessary memory allocations in performance-critical code
- avoid unnecessary object boxing in performance-critical code
- avoid using exceptions for control flow
- prefer performance to readability in performance-critical code

### Key external libraries

- RoaringBitmap: for working with bitmaps
- Kryo: for binary serialization and deserialization
- Netty and Armeria: for web server and client functionality
- Logback: for logging
- Jackson: for JSON serialization and deserialization
- gRPC Java: for gRPC functionality
- GraphQL Java: for GraphQL functionality
- Byte Buddy: for runtime code generation

### Building

- **Build Tool**: Use Maven for building the project

### Testing

- **JUnit 5**: Use JUnit 5 for unit tests
- all test files are located in the `evita_functional_tests/src/test/java` directory
- automatically generate test cases for all public methods
- name tests using the format `shouldDoSomethingWhenCondition` or `shouldThrowExceptionWhenCondition`
- use @DisplayName for entire class
- use @DisplayName for test methods to provide a clear description of the test, do not repeat the content in class @DisplayName