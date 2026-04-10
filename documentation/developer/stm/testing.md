# Testing Transactional Data Structures

This document describes the testing patterns used to verify STM correctness, focusing on the
generational (property-based) testing approach.

---

## Test infrastructure

### assertStateAfterCommit

**Location:** `evita_test/.../utils/AssertionUtils.java`

The primary test helper for verifying transactional behaviour:

```java
assertStateAfterCommit(
    testedObject,                          // TransactionalLayerProducer to test
    original -> {
        // perform mutations on the transactional view
        original.put("key", value);
    },
    (original, committed) -> {
        // verify: original is unchanged, committed has the mutation
        assertNull(original.get("key"));
        assertEquals(value, committed.get("key"));
    }
);
```

Under the hood:

1. Opens a new `Transaction` using the tested object as the root producer.
2. Binds the transaction to the current thread.
3. Invokes the mutation lambda.
4. Commits the transaction via `Transaction.close()`.
5. Retrieves the committed state via `transaction.getCommitedState()`.
6. Calls `transactionalLayer.verifyLayerWasFullySwept()` to assert no stale layers.
7. Invokes the verification lambda with the original object and the committed copy.

### assertStateAfterRollback

Same structure as `assertStateAfterCommit`, but marks the transaction as rollback-only before closing.
The committed parameter in the verification lambda is `null`.

### Multi-object variant

`TestTransactionHandlerWithMultipleValues` accepts a list of tested items and commits/verifies all of
them atomically. Useful for testing that changes across multiple data structures are committed together.

---

## Standard unit test pattern

Every transactional data structure has unit tests that verify:

1. **Isolation:** Mutations inside a transaction are not visible on the original.
2. **Commit correctness:** After commit, the returned copy contains all mutations.
3. **Rollback correctness:** After rollback, the original is unchanged and no copy is produced.
4. **Iterator consistency:** Iterators see transactional changes correctly.
5. **Edge cases:** Empty structures, single-element structures, boundary values.

Example:

```java
@Test
void shouldNotModifyOriginalStateButCreateModifiedCopy() {
    TransactionalMap<String, Integer> tested = new TransactionalMap<>(
        Map.of("a", 1, "b", 2)
    );

    assertStateAfterCommit(
        tested,
        original -> {
            original.put("c", 3);
            original.remove("a");
            assertEquals(3, original.get("c"));  // visible in transaction
        },
        (original, committed) -> {
            // original is unchanged
            assertEquals(1, original.get("a"));
            assertNull(original.get("c"));
            // committed has the changes
            assertNull(committed.get("a"));
            assertEquals(3, committed.get("c"));
        }
    );
}
```

---

## Generational (property-based) testing

### Concept

Generational tests stress-test STM data structures by running thousands of randomised operations over
a configurable time window. Each "generation" takes the committed output of the previous generation as
input, creating a chain of commit cycles. A parallel "test double" (a standard JDK collection) is
maintained alongside the transactional structure to serve as the ground truth.

### Implementation pattern

Every generational test:

1. Implements `TimeBoundedTestSupport`.
2. Is annotated with `@Tag(LONG_RUNNING_TEST)` and uses `@ArgumentsSource(TimeArgumentProvider.class)`.
3. Accepts a `GenerationalTestInput` with `intervalInMinutes` (default 1) and `randomSeed`.

```java
@ParameterizedTest
@Tag(LONG_RUNNING_TEST)
@ArgumentsSource(TimeArgumentProvider.class)
void generationalProofTest(GenerationalTestInput input) {
    final Map<String, Integer> initialState = generateRandomInitialMap(
        new Random(input.randomSeed()), 100
    );

    runFor(
        input,
        10_000,                              // print progress every 10k iterations
        new TestState(initialState),         // initial state
        (random, testState) -> {
            // 1. Create fresh transactional structure from previous generation
            final TransactionalMap<String, Integer> tested =
                new TransactionalMap<>(testState.initialMap());
            // 2. Create test double from same data
            final Map<String, Integer> reference = new HashMap<>(testState.initialMap());

            // 3. Execute random operations on both
            assertStateAfterCommit(
                tested,
                original -> {
                    int ops = random.nextInt(5);
                    for (int i = 0; i < ops; i++) {
                        int op = random.nextInt(4);
                        if (op == 0) {
                            // insert/update
                            String key = randomKey(random);
                            int value = random.nextInt(200);
                            original.put(key, value);
                            reference.put(key, value);
                        } else if (op == 1) {
                            // remove
                            String key = pickRandomExisting(reference, random);
                            original.remove(key);
                            reference.remove(key);
                        }
                        // ... more operations
                    }
                },
                (original, committed) -> {
                    // 4. Verify committed state matches reference
                    assertEquals(reference.size(), committed.size());
                    for (Map.Entry<String, Integer> entry : reference.entrySet()) {
                        assertEquals(entry.getValue(), committed.get(entry.getKey()));
                    }
                }
            );

            // 5. Return reference as input for next generation
            return new TestState(reference);
        }
    );
}
```

### Key properties verified

| Property                | How it is verified                                                                         |
|-------------------------|--------------------------------------------------------------------------------------------|
| **Isolation**           | Operations on `original` inside the transaction do not affect the `original` reference seen in the verification lambda. |
| **Commit correctness**  | The `committed` copy exactly matches the `reference` (the test double) after every generation. |
| **Accumulated correctness** | Because each generation feeds into the next, any accumulated error (e.g. off-by-one in index tracking) compounds and is caught over thousands of iterations. |
| **Deterministic reproduction** | The random seed is printed on failure. Rerunning with the same seed reproduces the exact operation sequence. |

### Operation trace codes (for debugging)

Some generational tests (e.g. `TransactionalMapTest`) build an operation trace string using short codes:

| Code     | Meaning                              |
|----------|--------------------------------------|
| `+K:V`   | Insert/update key K with value V     |
| `-K`     | Remove key K                         |
| `!I:V`   | Update value at iterator position I  |
| `#I`     | Remove item at iterator position I   |

On test failure, the trace can be printed to show the exact sequence of operations that led to the
inconsistency.

### Reproducing a failure

1. Note the random seed from the output: `"Random seed used: {seed}"`.
2. Set the seed as a test parameter or environment variable.
3. The test generates the exact same sequence of operations.
4. Add the operation trace to narrow down the failing generation.
5. Reduce the test to a minimal reproduction.

---

## Which data structures have generational tests?

| Data structure                  | Test class                                   | Operations tested                                  |
|---------------------------------|----------------------------------------------|----------------------------------------------------|
| `TransactionalIntArray`         | `TransactionalIntArrayTest`                  | Insert, remove, contains, indexOf                  |
| `TransactionalObjArray`         | `TransactionalObjArrayTest`                  | Insert, remove of comparable objects               |
| `TransactionalMap`              | `TransactionalMapTest`                       | Put, remove, iterator-update, iterator-remove      |
| `TransactionalSet`              | `TransactionalSetTest`                       | Add, remove, retainAll, removeAll                  |
| `TransactionalList`             | `TransactionalListTest`                      | Add, remove, index-based access                    |
| `TransactionalBitmap`           | `TransactionalBitmapTest`                    | Add, remove bits, cardinality                      |
| `TransactionalBoolean`          | `TransactionalBooleanTest`                   | Set/clear, toggle                                  |
| `TransactionalComplexObjArray`  | `TransactionalComplexObjArrayTest`           | Insert, merge, subtract, obsolete check            |
| `TransactionalIntBPlusTree`     | `TransactionalIntBPlusTreeTest`              | Insert, remove, lookup, range queries              |
| `TransactionalObjectBPlusTree`  | `TransactionalObjectBPlusTreeTest`           | Insert, remove, lookup with comparable keys        |

---

## Deep-wise atomicity testing

`TransactionalMemoryTest` specifically tests that nested transactional structures commit atomically:

```java
// Outer map contains inner transactional maps as values
TransactionalMap<String, TransactionalMap<String, Integer>> outerMap = ...;

assertStateAfterCommit(
    outerMap,
    original -> {
        original.get("inner1").put("x", 1);
        original.remove("inner2");
    },
    (original, committed) -> {
        // Both outer removal and inner mutation committed atomically
        assertEquals(1, committed.get("inner1").get("x"));
        assertNull(committed.get("inner2"));
    }
);
```

### Stale memory detection test

Tests verify that `StaleTransactionMemoryException` is thrown when a transactional object is modified
outside the commit tree:

```java
assertThrows(StaleTransactionMemoryException.class, () -> {
    assertStateAfterCommit(
        tracked,
        original -> {
            original.put("a", 1);     // this is tracked
            untracked.put("b", 2);    // this is NOT in the commit tree
        },
        (original, committed) -> fail("Should not reach here")
    );
});
```

---

## Running generational tests

Generational tests are marked with `@Tag(LONG_RUNNING_TEST)` and excluded from normal test runs.

**Maven:**
```
mvn clean install -P longRunning
```

**Environment configuration:**
- `interval` -- duration in minutes (default: 1). Set higher for thorough pre-release testing.

**IntelliJ IDEA:**
Include the `longRunning` tag in your JUnit run configuration.
