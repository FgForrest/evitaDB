---
paths:
  - "**/test/**/*.java"
  - "**/evita_test/**/*.java"
  - "**/evita_functional_tests/**/*.java"
  - "**/evita_performance_tests/**/*.java"
---

# Testing Conventions

- **Framework**: JUnit 5
- **Test location**: All test files are located in `evita_test/evita_functional_tests/src/test/java`
- **Test naming**: Use format `shouldDoSomethingWhenCondition` or `shouldThrowExceptionWhenCondition`
- **Display names**: Use `@DisplayName` for entire class and for test methods to provide clear descriptions (do not repeat class description content in method descriptions)
- **Coverage**: Automatically generate test cases for all public methods
- When creating new test consider implementing helper interface `io.evitadb.test.EvitaTestSupport` that provides utility methods for working with folders, ports, certificates and similar helpful things
- When creating tests consolidate similar tests in nested classes using `@Nested` annotation
- For running tests try to use IntelliJ MCP, when not possible use Maven, prefer running individual test classes over running entire test suite, if you run entire test suite use profile `unitAndFunctional`
