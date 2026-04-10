---
paths:
  - "**/*.java"
---

# Java Code Style

- **Indentation**: Use tabs for indentation
- **Line Length**: Limit lines to 100 characters
- **Java modules**: Use Java modules to organize code
- **JavaDoc**: Use Markdown syntax for formatting in JavaDoc - never use HTML tags
- **Data structures**: Prefer immutable classes / records for data structures
- **Annotations**: Automatically add `javax.annotation.Nullable` and `javax.annotation.Nonnull` annotations to method parameters and return types
- **Local variables**: Use `final` for local variables
- **Instance variables**: Use `this` for instance variables
- **Type declarations**: Never use `var` - always use explicit types
- **Resource management**: Use try-with-resources for all `AutoCloseable` resources wherever applicable
- **Documentation**: Automatically add JavaDoc to all generated classes and methods
- **Comments**: Add line comments to complex logic

## Performance-Critical Code

- Prefer performance to readability in performance-critical code
- Avoid unnecessary memory allocations
- Avoid unnecessary object boxing
- Avoid streams - write allocation optimized loops instead
- Avoid using exceptions for control flow
- Always initialize `StringBuilder` with an estimated capacity — never use `new StringBuilder()` without arguments
- Never use `Objects.hash()` with primitive arguments — it autoboxes every primitive into an `Object`. Use manual `31 * result + Type.hashCode(primitive)` computation instead
