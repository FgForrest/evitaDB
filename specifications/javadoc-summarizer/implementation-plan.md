# JavaDoc Summarizer — Implementation Plan

## Goal

Replace the existing `JavaDocCopy` test class with a Maven-invocable tool that uses OpenAI API
to generate **concise factory method summaries** from the verbose constraint class JavaDoc in
`QueryConstraints.java`. The tool should be incremental — only regenerating summaries when
the source JavaDoc or method signature has changed.

## Current State

- **`JavaDocCopy.java`** (`evita_test/evita_functional_tests/.../documentation/JavaDocCopy.java`):
  A `@Disabled` JUnit test that copies full JavaDoc from constraint classes to factory methods
  in `QueryConstraints.java` using QDox. Produces a 42K-line file that is hard to maintain.
- **`QueryConstraints.java`** (`evita_query/.../QueryConstraints.java`): ~42,000 lines,
  ~453 static factory methods. Each method currently has the **full** constraint class JavaDoc
  copied verbatim (40–95 lines per method).
- **Constraint classes** (in `evita_query/.../filter/`, `order/`, `require/`, `head/`): Each has
  comprehensive JavaDoc with sections like "Logical Semantics", "Usage Context", "Example Usage",
  etc. plus a `@ConstraintDefinition` annotation with `shortDescription` and `userDocsLink`.

## Architecture

```
tools/generate-query-constraints-javadoc.sh   # Shell entry point
    └── mvn exec:java                         # Invokes the main class via exec-maven-plugin
        └── JavaDocSummarizer.main()          # In evita_functional_tests (test scope)
            ├── QDox                           # Parse source files, extract JavaDoc + signatures
            ├── java.net.http.HttpClient       # Call OpenAI API (4 parallel requests)
            ├── @SourceHash annotation         # Java annotation for storing MD5 hash
            └── MD5 hash check                 # Skip unchanged methods
```

### Why `evita_functional_tests`?

- QDox is already a dependency there (test scope).
- The existing `JavaDocCopy` class lives there — the replacement naturally goes in the same module.
- `exec-maven-plugin` can invoke test-scoped classes via `classpathScope=test`.
- No new module needed.

## Detailed Tasks

### Task 1: Create `@SourceHash` annotation

**Location**: `evita_test/evita_functional_tests/src/test/java/io/evitadb/documentation/SourceHash.java`

A minimal Java method annotation used purely for storing the MD5 hash of the source material
(constraint JavaDoc + method signature) that was used to generate the summary. This annotation
has no runtime behavior — it exists only as a machine-readable marker that the summarizer tool
can read and write to determine whether a method's summary needs regeneration.

```java
package io.evitadb.documentation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stores the MD5 hash of the source constraint JavaDoc and factory method signature
 * that was used to generate the summarized JavaDoc for this method. Used by
 * {@link JavaDocSummarizer} to detect when regeneration is needed.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface SourceHash {
    String value();
}
```

**Key design decisions**:
- `RetentionPolicy.SOURCE` — not retained in bytecode, zero runtime overhead.
- `ElementType.METHOD` — can only be placed on methods.
- Lives in the same package as `JavaDocSummarizer` (test scope, minimal visibility).
- The annotation class itself is in test scope and NOT part of the `evita_query` module.

**Import in QueryConstraints.java**: Since the annotation is in test scope of
`evita_functional_tests` while `QueryConstraints.java` is in `evita_query`, the annotation
**cannot** be a compile-time dependency. Instead, the summarizer will:
- Write the `@SourceHash("abc123")` text into `QueryConstraints.java` as source text.
- The annotation import will NOT be added — instead, the hash will be embedded as a
  **JavaDoc tag** `@sourceHash abc123` (custom Javadoc tag, not a Java annotation).

**Correction**: Given the cross-module constraint, we use a **custom JavaDoc tag** approach
instead of a Java annotation. The `@SourceHash` annotation file is NOT created. The hash is
stored as:

```java
/**
 * Logical conjunction (AND) — returns entities matching ALL child constraints. When multiple
 * filters are placed in a container without an explicit operator, AND is the default.
 * EvitaQL: `and(filterConstraint:any+)`.
 *
 * ```evitaql
 * and(
 *     entityPrimaryKeyInSet(110066, 106742, 110513),
 *     entityPrimaryKeyInSet(110066, 106742)
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/logical#and)
 *
 * @sourceHash a1b2c3d4e5f6...
 */
```

Wait — Johnny explicitly said "create Java Method annotation for it." Let me reconsider.

**Resolution**: The annotation IS created, but in the `evita_query` module (where
`QueryConstraints` lives) so it can be used as an actual Java annotation on the factory methods.
It stays minimal and SOURCE-retention so it has zero footprint.

**Revised Location**: `evita_query/src/main/java/io/evitadb/api/query/SourceHash.java`

Package-private visibility (no `public` modifier) keeps it hidden from external consumers:

```java
package io.evitadb.api.query;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stores the MD5 hash of the source constraint JavaDoc and factory method signature
 * that was used to generate the summarized JavaDoc on this method. Used by
 * {@link io.evitadb.documentation.javadoc.JavaDocSummarizer} to detect when regeneration is needed.
 *
 * This annotation is SOURCE-retained only — it exists purely as a marker in source code
 * and is not present in compiled bytecode.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@interface SourceHash {
    String value();
}
```

**Usage on factory methods** (generated by the tool):

```java
@SourceHash("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
@Nullable
static And and(@Nullable FilterConstraint... constraints) {
```

The summarizer reads the annotation value via QDox (source-level parsing, not reflection),
compares with the freshly computed hash, and skips if they match.

### Task 2: Create `JavaDocSummarizer.java`

**Location**: `evita_test/evita_functional_tests/src/test/java/io/evitadb/documentation/JavaDocSummarizer.java`

**Class structure** (all constants hardcoded):

```java
public class JavaDocSummarizer implements EvitaTestSupport {

    // --- Configuration Constants ---
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_MODEL = "gpt-4.1";
    private static final int MAX_PARALLEL_REQUESTS = 4;
    private static final double TEMPERATURE = 0.3;
    private static final int MAX_TOKENS = 500;

    // --- Source Paths ---
    private static final String[] CONSTRAINTS_ROOT = {
        "evita_query/src/main/java/io/evitadb/api/query/head",
        "evita_query/src/main/java/io/evitadb/api/query/filter",
        "evita_query/src/main/java/io/evitadb/api/query/order",
        "evita_query/src/main/java/io/evitadb/api/query/require"
    };
    private static final String QUERY_CONSTRAINTS_PATH =
        "evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java";
    private static final String QUERY_CONSTRAINTS_CLASS =
        "io.evitadb.api.query.QueryConstraints";

    // --- Prompt (hardcoded) ---
    private static final String SYSTEM_PROMPT = """
        You are a technical writer for evitaDB, a specialized NoSQL database for e-commerce.
        Your task is to write a concise JavaDoc summary for a factory method based on the
        detailed constraint class documentation provided.

        Rules:
        - Output ONLY the JavaDoc body text (no /** or */ delimiters, no leading * prefixes).
        - Use Markdown syntax for formatting (never use HTML tags).
        - Write a SINGLE paragraph (Twitter-length, ~280 chars) describing what the constraint
          does, its key semantics, and any critical behavioral notes.
        - After the paragraph, if the constraint benefits from a short usage example, include
          ONE brief EvitaQL code block (3-5 lines max) showing the most common usage pattern.
          Use ```evitaql fenced code block syntax. Skip the example if the constraint is
          self-explanatory from the paragraph alone.
        - End with a link to detailed docs in this exact Markdown format:
          [Visit detailed user documentation](https://evitadb.io{userDocsLink})
        - Do NOT include @param, @return, @see, or @author tags.
        - Do NOT include section headers or bullet lists.
        """;

    public static void main(String[] args) throws Exception { ... }
}
```

### Task 3: Implement source parsing with QDox

**Method**: `collectMethodInfo(Path queryConstraintsPath, Path rootDir) → List<MethodInfo>`

Reuse the QDox-based approach from `JavaDocCopy`:

1. Parse all constraint source folders + `QueryConstraints.java` via `JavaProjectBuilder`.
2. For each public static method in `QueryConstraints`:
   - Find the matching constraint class by return type.
   - If no matching constraint class found → **skip** this method entirely (fallback: keep
     existing JavaDoc unchanged, no `@SourceHash` annotation).
   - Extract the constraint class JavaDoc (full text).
   - Extract `userDocsLink` from `@ConstraintDefinition` annotation on the constraint class.
   - Extract the method signature string (return type + name + parameters).
   - Look for existing `@SourceHash` annotation on the factory method and extract its value.
3. Return a list of `MethodInfo` records (only for methods that have a matching constraint class).

**Record**:
```java
record MethodInfo(
    int javadocStartLine,      // line where the factory method's JavaDoc starts
    int methodDeclLine,        // line where the method declaration starts
    String methodSignature,    // e.g., "And and(@Nullable FilterConstraint... constraints)"
    String constraintJavaDoc,  // full JavaDoc from the constraint class
    String userDocsLink,       // from @ConstraintDefinition
    @Nullable String existingSourceHash  // from @SourceHash annotation, null if absent
)
```

### Task 4: Implement MD5-based change detection

**Hash computation**:
```java
String hashInput = constraintJavaDoc + "|" + methodSignature;
String hash = md5Hex(hashInput);  // using java.security.MessageDigest
```

**Change detection logic** (applied per method):
1. Read `existingSourceHash` from `MethodInfo` (parsed from `@SourceHash` annotation via QDox).
2. Compute the current hash from constraint JavaDoc + method signature.
3. If hashes match → skip (no API call, no file modification for this method).
4. If hashes differ or no `@SourceHash` present → queue for summary generation via OpenAI.

On the first run, ALL methods with a matching constraint class will be processed (no existing
hashes). On subsequent runs, only methods whose source constraint JavaDoc or factory method
signature changed will be regenerated.

### Task 5: Implement OpenAI API integration (parallel)

**Method**: `generateSummaries(List<MethodInfo> methods) → Map<MethodInfo, String>`

Use `java.net.http.HttpClient` (JDK 17, no extra dependencies) with parallel execution:

1. Read API key from `System.getenv("OPENAI_API_KEY")`.
   - If not set, fail immediately with a clear error message.
2. Create an `ExecutorService` with `MAX_PARALLEL_REQUESTS` threads (default: 4).
3. Submit all methods that need regeneration as `CompletableFuture` tasks, limited to
   `MAX_PARALLEL_REQUESTS` concurrent in-flight requests using a `Semaphore`.
4. Each task:
   - Acquires semaphore permit.
   - Builds the chat completion request JSON:
     - `model`: `OPENAI_MODEL` constant
     - `messages`: system prompt + user message with constraint JavaDoc, method signature,
       and userDocsLink
     - `temperature`: `TEMPERATURE` constant
     - `max_tokens`: `MAX_TOKENS` constant
   - Sends HTTP POST via `HttpClient.sendAsync()`.
   - Parses the JSON response using Jackson (already on classpath).
   - Extracts the generated summary text.
   - Releases semaphore permit.
   - Logs progress: `[42/128] Generated summary for: and(...)`
5. Collect all results into a `Map<MethodInfo, String>`.
6. If any API call fails, log the error and skip that method (keep existing JavaDoc).

**User message template**:
```
Constraint class JavaDoc:
---
{constraintJavaDoc}
---

Factory method signature:
{methodSignature}

User documentation link path: {userDocsLink}
```

**Concurrency model**:
```java
private static final int MAX_PARALLEL_REQUESTS = 4;  // tunable constant

ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS);
Semaphore semaphore = new Semaphore(MAX_PARALLEL_REQUESTS);

List<CompletableFuture<Map.Entry<MethodInfo, String>>> futures = methods.stream()
    .map(method -> CompletableFuture.supplyAsync(() -> {
        semaphore.acquire();
        try {
            String summary = callOpenAI(method);
            return Map.entry(method, summary);
        } finally {
            semaphore.release();
        }
    }, executor))
    .toList();

// Wait for all and collect results
Map<MethodInfo, String> results = futures.stream()
    .map(CompletableFuture::join)
    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
```

With a fixed thread pool of size `MAX_PARALLEL_REQUESTS`, the semaphore is technically
redundant but serves as documentation of intent and a safety net if the executor is changed.
A simpler alternative is to just use the fixed thread pool without a semaphore — either
approach is fine.

### Task 6: Implement source file rewriting

**Method**: `rewriteQueryConstraints(Path path, List<MethodInfo> methods, Map<MethodInfo, String> summaries)`

For each method that has a new summary:

1. Read `QueryConstraints.java` line by line.
2. For each method in the `summaries` map (sorted by line number descending to avoid offset
   shifting — process from bottom to top):
   a. Locate the JavaDoc block preceding the method declaration (from `/**` to `*/`).
   b. Replace the entire JavaDoc block with the new summary formatted as:
      ```
      \t/**
      \t * {generated summary line 1}
      \t * {generated summary line 2}
      \t * ...
      \t */
      ```
   c. Locate or insert the `@SourceHash("...")` annotation line immediately before the
      method's existing annotations (before `@Nullable`/`@Nonnull`), or update it in place
      if already present.
3. Write the modified file back.

**Processing order**: Bottom-to-top (descending line numbers) so that line number changes
from earlier replacements don't affect subsequent ones. This is simpler than the compensation
approach in `JavaDocCopy`.

**`@SourceHash` placement** (example of final output):

```java
/**
 * Logical conjunction (AND) — returns entities matching ALL child constraints. When multiple
 * filters are placed in a container without an explicit operator, AND is the default. The result
 * set is the intersection of entity sets matched by each child constraint.
 *
 * ```evitaql
 * and(
 *     attributeEquals("available", true),
 *     priceBetween(100, 500)
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/logical#and)
 */
@SourceHash("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
@Nullable
static And and(@Nullable FilterConstraint... constraints) {
```

**Import management**: The tool must ensure `import io.evitadb.api.query.SourceHash;` is NOT
needed because `SourceHash` is in the same package (`io.evitadb.api.query`) as
`QueryConstraints`. No import changes required.

**Methods without constraint class match**: Left completely untouched — no JavaDoc change,
no `@SourceHash` annotation. Their existing hand-written JavaDoc is preserved as-is.

### Task 7: Add exec-maven-plugin configuration

**File**: `evita_test/evita_functional_tests/pom.xml`

Add a Maven profile `generate-javadoc` with `exec-maven-plugin`:

```xml
<profile>
    <id>generate-javadoc</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.5.0</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>java</goal>
                        </goals>
                    </execution>
                </executions>
                <configuration>
                    <mainClass>io.evitadb.documentation.javadoc.JavaDocSummarizer</mainClass>
                    <classpathScope>test</classpathScope>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

### Task 8: Create shell script

**File**: `tools/generate-query-constraints-javadoc.sh`

```bash
#!/bin/bash
# ... license header (same as other tools/ scripts) ...

# Generates concise JavaDoc summaries for QueryConstraints factory methods
# using OpenAI API. Requires OPENAI_API_KEY environment variable.
# Usage: ./generate-query-constraints-javadoc.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT" || { echo "Failed to change to project root: $PROJECT_ROOT"; exit 1; }

if [ -z "$OPENAI_API_KEY" ]; then
    echo "Error: OPENAI_API_KEY environment variable is not set."
    echo "Export it before running: export OPENAI_API_KEY=sk-..."
    exit 1
fi

mvn -pl evita_test/evita_functional_tests exec:java -Pgenerate-javadoc -q
```

### Task 9: Delete `JavaDocCopy.java`

After the new tool is verified working, delete:
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/documentation/JavaDocCopy.java`

## Execution Order

1. **Task 1** — Create `@SourceHash` annotation in `evita_query` module.
2. **Task 7** — Add exec-maven-plugin profile to `pom.xml`.
3. **Task 2** — Create `JavaDocSummarizer.java` skeleton with constants and `main()`.
4. **Task 3** — Implement QDox parsing (can test independently — just logs what it finds).
5. **Task 4** — Implement MD5 hashing + `@SourceHash` reading via QDox.
6. **Task 5** — Implement parallel OpenAI API calls.
7. **Task 6** — Implement source file rewriting (JavaDoc replacement + `@SourceHash` insertion).
8. **Task 8** — Create shell script.
9. **Verify** — Run the tool on a few methods first (add a `--limit N` CLI arg for testing),
   inspect results, tune prompt if needed.
10. **Task 9** — Delete `JavaDocCopy.java` after verification.

## Design Decisions Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Hash storage | `@SourceHash` Java annotation | Johnny's explicit requirement; SOURCE retention = zero runtime cost; same package = no import needed |
| Hash input | `constraintJavaDoc + "\|" + methodSignature` | Detects changes in either the source docs or the method signature |
| Parallelism | 4 concurrent requests (configurable constant) | Balances throughput vs. API rate limits; `MAX_PARALLEL_REQUESTS` constant |
| No-match fallback | Keep existing JavaDoc, no `@SourceHash` | Methods like `entityFetchAllAnd` have hand-written docs, not derived from constraints |
| Summary style | Single Twitter-length paragraph + optional short example | Keeps `QueryConstraints` readable; full docs are one click away |
| API key | `System.getenv("OPENAI_API_KEY")` | No CLI args; shell script validates presence before Maven invocation |
| Model/endpoint | Hardcoded constants in class | Per Johnny's requirement; easy to update in one place |
| Module placement | `evita_functional_tests` (test scope) | QDox already there; `exec-maven-plugin` with `classpathScope=test` |
| Annotation module | `evita_query` (production, package-private) | Must be visible to `QueryConstraints` at compile time; package-private hides from consumers |
