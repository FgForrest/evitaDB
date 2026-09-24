---
paths:
  - "**/*.java"
---

# Java Code Style

**Before writing a helper, check `documentation/developer/utilities.md`.** `evita_common`'s `io.evitadb.utils` package holds thirty shared utilities, and most of them wrap a subtlety that is easy to get wrong by hand. The rules below name the ones that are mandatory; the reference covers the rest.

- **Indentation**: Use tabs for indentation. NEVER use spaces for indentation. When using the Edit tool, ensure `old_string` and `new_string` contain literal tab characters matching the source file. If the Edit tool fails due to whitespace mismatch, fall back to a python3 script for the replacement.
- **Line Length**: Limit lines to 120 characters — the project hard wrap (`.editorconfig`, `max_line_length = 120`)
- **Java modules**: Use Java modules to organize code
- **JavaDoc**: Use Markdown syntax for formatting in JavaDoc - never use HTML tags
- **Data structures**: Prefer immutable classes / records for data structures
- **Maps and sets**: create them through `io.evitadb.utils.CollectionUtils` — `createHashMap(n)`, `createLinkedHashMap(n)`, `createHashSet(n)`, `createLinkedHashSet(n)`, `createConcurrentHashMap(n)` — never `new HashMap<>(n)`. The JDK constructor takes a **bucket capacity**, not an expected element count, so `new HashMap<>(64)` rehashes once it passes 48 entries; the factories convert the count you actually know (`n / 0.75 + 1`) into the capacity the JDK wants. Two things are *not* covered and stay as they are: copy constructors (`new HashMap<>(otherMap)`), which the JDK already sizes from the source, and a genuinely unknown size, which stays `new HashMap<>()`.
- **Annotations**: Automatically add `javax.annotation.Nullable` and `javax.annotation.Nonnull` annotations to method parameters and return types
- **Optional as parameter**: `java.util.Optional` is permitted **only as a method return type**, and as a local
  variable holding one. Never declare it as a method parameter, constructor parameter, or field. A field pays a
  wrapper object per instance for something `null` already expresses, and then drags it through every copy,
  serializer and equality check on the class. An argument forces every caller to wrap while the parameter still
  accepts `null` — a signature promising a guarantee it does not give. For an optional input use a `@Nullable`
  reference instead; for optional state store the bare value and wrap it with `Optional.ofNullable(...)` at the
  read boundary. The case that tempts a field is memoizing a nullable lookup, where `null` means *not computed*
  and *absent* at once: do not separate them with an `Optional` field — recompute instead, and keep the scan
  allocation-free. `ReferenceContent#getFilterBy` is the worked example.
- **Local variables**: Use `final` for local variables
- **Instance variables**: Use `this` for instance variables
- **Type declarations**: Never use `var` - always use explicit types
- **Imports**: Wildcard imports (`import foo.bar.*;`) are **allowed** and follow IntelliJ's default auto-folding threshold (5+ imports from the same package). Do not flag or expand existing wildcard imports in reviews — this is not a project convention.
- **Resource management**: Use try-with-resources for all `AutoCloseable` resources wherever applicable
- **Documentation**: Automatically add JavaDoc to all generated classes and methods
- **Comments**: Add line comments to complex logic

## Defensive Design

- **Never silently skip unexpected states.** If a code path should be unreachable (e.g., an `else` after
  exhaustive enum checks, a `default` in a switch over a closed enum), it must throw an exception
  (`GenericEvitaInternalError` or equivalent) — never `continue`, `return`, `break`, or no-op.
- Treat every unhandled enum value, unexpected type, or impossible branch as a programming error that must
  surface immediately at runtime.

## Performance-Critical Code

- Prefer performance to readability in performance-critical code
- Avoid unnecessary memory allocations
- Avoid unnecessary object boxing
- Avoid streams - write allocation optimized loops instead
- Avoid using exceptions for control flow
- Always initialize `StringBuilder` with an estimated capacity — never use `new StringBuilder()` without arguments
- Never use `Objects.hash()` with primitive arguments — it autoboxes every primitive into an `Object`. Use manual `31 * result + Type.hashCode(primitive)` computation instead
