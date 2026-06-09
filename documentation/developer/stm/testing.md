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

1. **Lives in the long-running module**, `evita_test/evita_long_running_tests`, mirroring the package of the
   structure under test (e.g. `io.evitadb.index.map`). It is **never** placed in `evita_functional_tests` —
   the fast functional suite must stay fast, and these tests run for minutes.
2. Is named `LongRunning<StructureName>Test` (e.g. `LongRunningPersistentTransactionalMapTest`) and the class
   `implements TimeBoundedTestSupport`.
3. Carries the required **layer + capability** class-level tags (e.g. `@Tag(INDEXING) @Tag(DATA_TYPE) @Tag(TRANSACTION)`).
4. Declares the proof as a `@ParameterizedTest(name = "…")` annotated with the **cost tag `@Tag(SLOW)`** (there is
   no `LONG_RUNNING_TEST` tag — `SLOW` is what excludes the test from the fast suite) and
   `@ArgumentsSource(TimeArgumentProvider.class)`.
5. Accepts a `@Nonnull GenerationalTestInput input` carrying `intervalInMinutes` (default 1) and `randomSeed`.

```java
@DisplayName("PersistentTransactionalMap (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningPersistentTransactionalMapTest implements TimeBoundedTestSupport {

@DisplayName("survives generational randomized test applying modifications on it")
@ParameterizedTest(name = "PersistentTransactionalMap should survive generational randomized test applying modifications on it")
@Tag(SLOW)
@ArgumentsSource(TimeArgumentProvider.class)
void generationalProofTest(@Nonnull GenerationalTestInput input) {
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

Every generational test class lives in `evita_test/evita_long_running_tests` and is named `LongRunning…Test`
(the bare `…Test` class in `evita_functional_tests` holds only the fast example-based unit tests).

| Data structure                       | Generational test class (long-running module)      | Operations tested                                      |
|--------------------------------------|----------------------------------------------------|--------------------------------------------------------|
| `TransactionalIntArray`              | `LongRunningTransactionalIntArrayTest`             | Insert, remove, contains, indexOf                      |
| `TransactionalObjArray`              | `LongRunningTransactionalObjArrayTest`             | Insert, remove of comparable objects                   |
| `TransactionalMap`                   | `LongRunningTransactionalMapTest`                  | Put, remove, iterator-update, iterator-remove          |
| `TransactionalSet`                   | `LongRunningTransactionalSetTest`                  | Add, remove, retainAll, removeAll                      |
| `TransactionalList`                  | `LongRunningTransactionalListTest`                 | Add, remove, index-based access                        |
| `TransactionalBitmap`                | `LongRunningTransactionalBitmapTest`              | Add, remove bits, cardinality                          |
| `TransactionalBoolean`               | `LongRunningTransactionalBooleanTest`             | Set/clear, toggle                                      |
| `TransactionalComplexObjArray`       | `LongRunningTransactionalComplexObjArrayTest`     | Insert, merge, subtract, obsolete check                |
| `TransactionalIntBPlusTree`          | `LongRunningTransactionalIntBPlusTreeTest`        | Insert, remove, lookup, range queries                  |
| `TransactionalObjectBPlusTree`       | `LongRunningTransactionalObjectBPlusTreeTest`     | Insert, remove, lookup with comparable keys            |
| `PersistentTransactionalMap`         | `LongRunningPersistentTransactionalMapTest`       | Put, remove, iterator-update, iterator-remove vs. a `HashMap` oracle (committed snapshot must be a `ChampMap`) |
| `PersistentTransactionalProducerMap` | `LongRunningPersistentTransactionalProducerMapTest` | Insert, remove, **in-place marked mutation** vs. a `key → committedValue` oracle |

The bare functional `…Test` classes (e.g. `PersistentTransactionalMapTest`) keep only fast example-based unit
tests; each one points at its `LongRunning…Test` sibling in a class-javadoc note so the generational proof is
discoverable.

### Producer-map specifics — the marking invariant

`PersistentTransactionalProducerMap` is the subtle one: its values are themselves `TransactionalLayerProducer`s
that mutate through their *own* diff layer, invisible to the map's put/remove tracking. The map therefore relies
on every in-place mutation being declared via `PersistentTransactionalProducerMap#markValueMutated` (the
**marking invariant**) so the `O(Δ·log₃₂ N)` commit visits exactly the
`removedKeys ∪ modifiedKeys ∪ valueMutatedKeys` union and shares every untouched producer node by reference. The
generational test pairs each in-place mutation with its mark (as the real `AttributeIndex` paths do) and asserts
the committed snapshot still matches the oracle after every generation — catching a dropped mark, a stale shared
node, or a mis-swept nested layer as accumulated divergence. The deliberate counter-case (an *unmarked* in-place
mutation must throw `StaleTransactionMemoryException` at commit) is covered by `ForgottenMarkSafetyNetTest` in the
fast functional suite.

> **Within-transaction constraint the producer-map test respects:** each key is touched at most once per
> transaction. Mixing an in-place mark with a replace/remove of the *same* key in one transaction would orphan
> the first value's diff layer — unsupported by the Δ-union commit by design. Keys are revisited freely *across*
> generations, which is where the accumulated-correctness coverage comes from.

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

Generational tests live in the `evita_test/evita_long_running_tests` module (whose surefire config sets
`skipTests=true` by default) and each proof method is tagged `@Tag(SLOW)` — so they are excluded from the
normal fast run on two counts. There is no `LONG_RUNNING_TEST` tag; `SLOW` is the cost tag that gates them.

**Maven:**
```
mvn clean install -P longRunning
```

**Environment configuration:**
- `interval` -- duration in minutes (default: 1). Set higher for thorough pre-release testing.

**IntelliJ IDEA:**
Run the `LongRunning…Test` class directly, or select the `SLOW`-tagged method in your JUnit run configuration.
