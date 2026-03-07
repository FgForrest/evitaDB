/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.map;

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.AbstractMap.SimpleEntry;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link TransactionalMap} implementation, covering construction,
 * non-transactional operations, transactional commit and rollback semantics, iterator contracts,
 * the {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} contract, and edge cases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@SuppressWarnings("SameParameterValue")
@DisplayName("TransactionalMap")
class TransactionalMapTest implements TimeBoundedTestSupport {
	/** The map under test, pre-populated with {"a"->1, "b"->2} before each test. */
	private TransactionalMap<String, Integer> tested;

	@BeforeEach
	void setUp() {
		final HashMap<String, Integer> underlyingData = new LinkedHashMap<>();
		underlyingData.put("a", 1);
		underlyingData.put("b", 2);
		this.tested = new TransactionalMap<>(underlyingData);
	}

	// -----------------------------------------------------------------------
	// Nested groups
	// -----------------------------------------------------------------------

	/**
	 * Tests verifying construction-time behaviour and identity guarantees.
	 */
	@Nested
	@DisplayName("Construction and identity")
	class ConstructionAndIdentityTest {

		@Test
		@DisplayName("assigns unique id to each instance")
		void shouldAssignUniqueIdPerInstance() {
			final TransactionalMap<String, Integer> first = new TransactionalMap<>(new HashMap<>());
			final TransactionalMap<String, Integer> second = new TransactionalMap<>(new HashMap<>());

			assertNotEquals(first.getId(), second.getId());
		}

		@Test
		@DisplayName("createLayer returns MapChanges backed by the same delegate")
		void shouldCreateSimpleLayerWithNullValueType() {
			// The simple constructor leaves valueType null; createLayer() must still return a non-null layer
			final MapChanges<String, Integer> layer = TransactionalMapTest.this.tested.createLayer();

			assertNotNull(layer);
			// The delegate exposed by MapChanges must be the same map instance passed to the constructor
			assertMapContains(layer.getMapDelegate(), new Tuple("a", 1), new Tuple("b", 2));
		}

		@Test
		@DisplayName("toString contains key=value pairs from the delegate")
		void shouldProduceReadableToString() {
			final String str = TransactionalMapTest.this.tested.toString();

			assertTrue(str.contains("a=1"), "toString should contain 'a=1' but was: " + str);
			assertTrue(str.contains("b=2"), "toString should contain 'b=2' but was: " + str);
		}

	}

	/**
	 * Tests verifying that all Map operations work correctly when no transaction is active,
	 * i.e. when mutations go directly to the delegate.
	 */
	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("put adds new entry directly to delegate")
		void shouldPutWithoutTransaction() {
			TransactionalMapTest.this.tested.put("c", 3);

			assertMapContains(TransactionalMapTest.this.tested, new Tuple("a", 1), new Tuple("b", 2), new Tuple("c", 3));
		}

		@Test
		@DisplayName("put returns the previous value for an existing key")
		void shouldPutExistingKeyReturnsPreviousValueWithoutTransaction() {
			final Integer previous = TransactionalMapTest.this.tested.put("a", 99);

			assertEquals(1, previous);
			assertEquals(99, TransactionalMapTest.this.tested.get("a"));
		}

		@Test
		@DisplayName("put returns null when the key did not exist before")
		void shouldPutNewKeyReturnsNullWithoutTransaction() {
			final Integer previous = TransactionalMapTest.this.tested.put("z", 99);

			assertNull(previous);
		}

		@Test
		@DisplayName("remove deletes an existing entry from the delegate")
		void shouldRemoveWithoutTransaction() {
			TransactionalMapTest.this.tested.remove("a");

			assertMapContains(TransactionalMapTest.this.tested, new Tuple("b", 2));
		}

		@Test
		@DisplayName("remove returns null when the key does not exist")
		void shouldRemoveNonExistentKeyReturnsNullWithoutTransaction() {
			final Integer result = TransactionalMapTest.this.tested.remove("z");

			assertNull(result);
		}

		@Test
		@DisplayName("get returns null for a key not present in the delegate")
		void shouldGetNonExistentKeyReturnsNullWithoutTransaction() {
			final Integer result = TransactionalMapTest.this.tested.get("z");

			assertNull(result);
		}

		@Test
		@DisplayName("containsKey and containsValue reflect delegate state")
		void shouldContainKeyAndValueWithoutTransaction() {
			assertTrue(TransactionalMapTest.this.tested.containsKey("a"));
			assertTrue(TransactionalMapTest.this.tested.containsValue(1));
			assertFalse(TransactionalMapTest.this.tested.containsKey("z"));
			assertFalse(TransactionalMapTest.this.tested.containsValue(99));
		}

		@Test
		@DisplayName("size and isEmpty reflect delegate state")
		void shouldReportSizeAndIsEmptyWithoutTransaction() {
			assertEquals(2, TransactionalMapTest.this.tested.size());
			assertFalse(TransactionalMapTest.this.tested.isEmpty());

			TransactionalMapTest.this.tested.remove("a");
			TransactionalMapTest.this.tested.remove("b");

			assertEquals(0, TransactionalMapTest.this.tested.size());
			assertTrue(TransactionalMapTest.this.tested.isEmpty());
		}

		@Test
		@DisplayName("putAll adds all entries from a source map to the delegate")
		void shouldPutAllWithoutTransaction() {
			final Map<String, Integer> extra = new LinkedHashMap<>();
			extra.put("c", 3);
			extra.put("d", 4);

			TransactionalMapTest.this.tested.putAll(extra);

			assertMapContains(
				TransactionalMapTest.this.tested,
				new Tuple("a", 1), new Tuple("b", 2), new Tuple("c", 3), new Tuple("d", 4)
			);
		}

		@Test
		@DisplayName("clear empties the delegate")
		void shouldClearWithoutTransaction() {
			// clear() without a transaction touches the delegate directly
			final HashMap<String, Integer> map = new HashMap<>();
			map.put("x", 10);
			final TransactionalMap<String, Integer> local = new TransactionalMap<>(map);

			local.clear();

			assertTrue(local.isEmpty());
			//noinspection ConstantValue
			assertEquals(0, local.size());
		}

		@Test
		@DisplayName("keySet, values, and entrySet iterate over delegate entries")
		void shouldIterateKeySetValuesAndEntrySetWithoutTransaction() {
			final Set<String> keys = new HashSet<>(TransactionalMapTest.this.tested.keySet());
			final Set<Integer> values = new HashSet<>(TransactionalMapTest.this.tested.values());
			final Set<Entry<String, Integer>> entries = new HashSet<>(TransactionalMapTest.this.tested.entrySet());

			assertEquals(new HashSet<>(Arrays.asList("a", "b")), keys);
			assertEquals(new HashSet<>(Arrays.asList(1, 2)), values);
			assertTrue(entries.contains(new SimpleEntry<>("a", 1)));
			assertTrue(entries.contains(new SimpleEntry<>("b", 2)));
		}

	}

	/**
	 * Tests verifying that mutations made inside a transaction are isolated from the original
	 * state and produce the expected committed copy on transaction close.
	 */
	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("put and update produce correct committed map without modifying original")
		void shouldNotModifyOriginalStateButCreateModifiedCopy() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("a", 3);
					original.put("c", 3);
					assertMapContains(original, new Tuple("a", 3), new Tuple("b", 2), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("a", 3), new Tuple("b", 2), new Tuple("c", 3));
				}
			);
		}

		@Test
		@DisplayName("remove produces correct committed map without modifying original")
		void removalsShouldNotModifyOriginalState() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.remove("a");
					original.put("c", 3);
					assertMapContains(original, new Tuple("b", 2), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("b", 2), new Tuple("c", 3));
				}
			);
		}

		@Test
		@DisplayName("mixed removals, updates, and insertions produce correct committed map")
		void shouldMergeRemovalsAndUpdatesAndInsertionsOnTransactionCommit() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.remove("a");
					original.put("b", 3);
					original.put("c", 3);

					assertMapContains(original, new Tuple("b", 3), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("b", 3), new Tuple("c", 3));
				}
			);
		}

		@Test
		@DisplayName("isEmpty reflects transactional state correctly throughout the transaction")
		void shouldInterpretIsEmptyCorrectly() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					assertFalse(original.isEmpty());

					original.put("c", 3);
					assertFalse(original.isEmpty());

					original.remove("a");
					assertFalse(original.isEmpty());

					original.remove("c");
					assertFalse(original.isEmpty());

					original.remove("b");
					assertTrue(original.isEmpty());

					original.put("d", 4);
					assertFalse(original.isEmpty());

					original.remove("d");
					assertTrue(original.isEmpty());

					assertMapContains(original);
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion);
				}
			);
		}

		@Test
		@DisplayName("values() returns correct collection reflecting transactional removes and inserts")
		void shouldProduceValidValueCollection() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					original.remove("b");

					final Set<Integer> result = new HashSet<>(original.values());
					assertEquals(2, result.size());
					assertTrue(result.contains(1));
					assertTrue(result.contains(3));
				},
				(original, committedVersion) -> {
					final Set<Integer> result = new HashSet<>(committedVersion.values());
					assertEquals(2, result.size());
					assertTrue(result.contains(1));
					assertTrue(result.contains(3));
				}
			);
		}

		@Test
		@DisplayName("keySet() returns correct set reflecting transactional removes and inserts")
		void shouldProduceValidKeySet() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					original.remove("b");

					final Set<String> result = new HashSet<>(original.keySet());
					assertEquals(2, result.size());
					assertTrue(result.contains("a"));
					assertTrue(result.contains("c"));
				},
				(original, committedVersion) -> {
					final Set<String> result = new HashSet<>(committedVersion.keySet());
					assertEquals(2, result.size());
					assertTrue(result.contains("a"));
					assertTrue(result.contains("c"));
				}
			);
		}

		@Test
		@DisplayName("entrySet() returns correct set reflecting transactional removes and inserts")
		void shouldProduceValidEntrySet() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					original.remove("b");

					final Set<Entry<String, Integer>> entries = new HashSet<>(original.entrySet());
					assertEquals(2, entries.size());
					assertTrue(entries.contains(new SimpleEntry<>("a", 1)));
					assertTrue(entries.contains(new SimpleEntry<>("c", 3)));
				},
				(original, committedVersion) -> {
					final Set<Entry<String, Integer>> entries = new HashSet<>(committedVersion.entrySet());
					assertEquals(2, entries.size());
					assertTrue(entries.contains(new SimpleEntry<>("a", 1)));
					assertTrue(entries.contains(new SimpleEntry<>("c", 3)));
				}
			);
		}

		@Test
		@DisplayName("keySet iterator remove does not modify the original state")
		void shouldNotModifyOriginalStateOnKeySetIteratorRemoval() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);

					final Iterator<String> it = original.keySet().iterator();
					//noinspection Java8CollectionRemoveIf
					while (it.hasNext()) {
						final String key = it.next();
						if (key.equals("b")) {
							it.remove();
						}
					}

					assertMapContains(original, new Tuple("a", 1), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("c", 3));
				}
			);
		}

		@Test
		@DisplayName("values iterator remove does not modify the original state")
		void shouldNotModifyOriginalStateOnValuesIteratorRemoval() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);

					final Iterator<Integer> it = original.values().iterator();
					//noinspection Java8CollectionRemoveIf
					while (it.hasNext()) {
						final Integer value = it.next();
						if (value.equals(2)) {
							it.remove();
						}
					}

					assertMapContains(original, new Tuple("a", 1), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("c", 3));
				}
			);
		}

		@Test
		@DisplayName("removeIf on keySet correctly removes matching keys within a transaction")
		void shouldRemoveValuesWhileIteratingOverThem() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.clear();
					original.put("ac", 1);
					original.put("bc", 2);
					original.put("ad", 3);
					original.put("ae", 4);

					original.keySet().removeIf(key -> key.contains("a"));

					assertMapContains(original, new Tuple("bc", 2));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("bc", 2));
				}
			);
		}

		@Test
		@DisplayName("entrySet setValue during iteration is reflected in the committed map")
		void shouldMergeChangesInEntrySetIterator() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);

					final Iterator<Entry<String, Integer>> it = original.entrySet().iterator();
					//noinspection WhileLoopReplaceableByForEach
					while (it.hasNext()) {
						final Entry<String, Integer> entry = it.next();
						if ("b".equals(entry.getKey())) {
							entry.setValue(5);
						}
					}

					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 5), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("b", 5), new Tuple("c", 3));
				}
			);
		}

		@Test
		@DisplayName("entrySet iterator hasNext is idempotent and next throws when exhausted")
		void shouldKeepIteratorContract() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);

					final List<String> result = new ArrayList<>(3);

					final Iterator<Entry<String, Integer>> it = original.entrySet().iterator();
					for (int i = 0; i < 50; i++) {
						assertTrue(it.hasNext());
					}

					result.add(it.next().getKey());
					for (int i = 0; i < 50; i++) {
						assertTrue(it.hasNext());
					}

					result.add(it.next().getKey());
					for (int i = 0; i < 50; i++) {
						assertTrue(it.hasNext());
					}

					result.add(it.next().getKey());
					for (int i = 0; i < 50; i++) {
						assertFalse(it.hasNext());
					}

					assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")), new HashSet<>(result));

					assertThrows(NoSuchElementException.class, it::next);
				},
				(original, committedVersion) -> {
					// iterator contract only; committed state is not the focus
				}
			);
		}

		@Test
		@DisplayName("entrySet iterator hasNext is idempotent and next throws when exhausted after removals")
		void shouldKeepIteratorContractWhenItemsRemoved() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					original.remove("b");

					final List<String> result = new ArrayList<>(2);

					final Iterator<Entry<String, Integer>> it = original.entrySet().iterator();
					for (int i = 0; i < 50; i++) {
						assertTrue(it.hasNext());
					}

					result.add(it.next().getKey());
					for (int i = 0; i < 50; i++) {
						assertTrue(it.hasNext());
					}

					result.add(it.next().getKey());
					for (int i = 0; i < 50; i++) {
						assertFalse(it.hasNext());
					}

					assertEquals(new HashSet<>(Arrays.asList("a", "c")), new HashSet<>(result));

					assertThrows(NoSuchElementException.class, it::next);
				},
				(original, committedVersion) -> {
					// iterator contract only; committed state is not the focus
				}
			);
		}

		@Test
		@DisplayName("putAll within a transaction commits all entries correctly")
		void shouldPutAllInTransaction() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					final Map<String, Integer> extra = new LinkedHashMap<>();
					extra.put("c", 3);
					extra.put("d", 4);
					original.putAll(extra);

					assertMapContains(
						original,
						new Tuple("a", 1), new Tuple("b", 2), new Tuple("c", 3), new Tuple("d", 4)
					);
				},
				(original, committedVersion) -> {
					// original must remain unchanged
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(
						committedVersion,
						new Tuple("a", 1), new Tuple("b", 2), new Tuple("c", 3), new Tuple("d", 4)
					);
				}
			);
		}

		@Test
		@DisplayName("clear within a transaction commits an empty map")
		void shouldClearInTransaction() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.clear();

					assertMapContains(original);
				},
				(original, committedVersion) -> {
					// original must remain unchanged
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion);
				}
			);
		}

		@Test
		@DisplayName("remove of non-existent key returns null inside a transaction")
		void shouldRemoveNonExistentKeyReturnsNull() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					final Integer result = original.remove("z");

					assertNull(result);
					// map unchanged
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				},
				(original, committedVersion) -> {
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("get of non-existent key returns null inside a transaction")
		void shouldGetNonExistentKeyReturnsNull() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					final Integer result = original.get("z");

					assertNull(result);
				},
				(original, committedVersion) -> {
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("put of an existing key returns the previous value inside a transaction")
		void shouldPutExistingKeyReturnsPreviousValue() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					final Integer previous = original.put("a", 99);

					assertEquals(1, previous);
					assertEquals(99, original.get("a"));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("a", 99), new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("put of a new key returns null inside a transaction")
		void shouldPutNewKeyReturnsNull() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					final Integer previous = original.put("z", 99);

					assertNull(previous);
					assertEquals(99, original.get("z"));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("b", 2), new Tuple("z", 99));
				}
			);
		}

	}

	/**
	 * Tests verifying that all mutations performed within a transaction are discarded upon rollback,
	 * leaving the original delegate state untouched.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("put is discarded on rollback")
		void shouldDiscardPutOnRollback() {
			assertStateAfterRollback(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					// rollback produces no committed version
					assertNull(committedVersion);
					// original state must be completely unchanged
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("remove is discarded on rollback")
		void shouldDiscardRemoveOnRollback() {
			assertStateAfterRollback(
				TransactionalMapTest.this.tested,
				original -> {
					original.remove("a");
					assertMapContains(original, new Tuple("b", 2));
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("putAll is discarded on rollback")
		void shouldDiscardPutAllOnRollback() {
			assertStateAfterRollback(
				TransactionalMapTest.this.tested,
				original -> {
					final Map<String, Integer> extra = new LinkedHashMap<>();
					extra.put("c", 3);
					extra.put("d", 4);
					original.putAll(extra);
					assertEquals(4, original.size());
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("clear is discarded on rollback")
		void shouldDiscardClearOnRollback() {
			assertStateAfterRollback(
				TransactionalMapTest.this.tested,
				original -> {
					original.clear();
					assertTrue(original.isEmpty());
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("multiple mixed operations are all discarded on rollback")
		void shouldDiscardMultipleOperationsOnRollback() {
			assertStateAfterRollback(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					original.remove("a");
					original.put("b", 99);
					assertMapContains(original, new Tuple("b", 99), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				}
			);
		}

	}

	/**
	 * Tests verifying the {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}
	 * contract methods on {@link TransactionalMap}.
	 */
	@Nested
	@DisplayName("TransactionalLayerProducer contract")
	class TransactionalLayerProducerContractTest {

		@Test
		@DisplayName("createLayer with simple constructor returns MapChanges referencing the delegate")
		void shouldCreateSimpleLayer() {
			final MapChanges<String, Integer> layer = TransactionalMapTest.this.tested.createLayer();

			assertNotNull(layer);
			// delegate must be the same object that was wrapped
			assertEquals(2, layer.getMapDelegate().size());
			assertTrue(layer.getMapDelegate().containsKey("a"));
			assertTrue(layer.getMapDelegate().containsKey("b"));
		}

		@Test
		@DisplayName("createLayer with Function-based constructor stores the wrapper and references the delegate")
		void shouldCreateLayerWithFunctionConstructor() {
			// Use the Function<Object, V> constructor which stores valueType = TransactionalMap.class
			final HashMap<String, Integer> delegate = new HashMap<>();
			delegate.put("x", 10);

			final TransactionalMap<String, Integer> map =
				new TransactionalMap<>(delegate, Integer.class::cast);

			final MapChanges<String, Integer> layer = map.createLayer();
			assertNotNull(layer);
			assertEquals(1, layer.getMapDelegate().size());
			assertEquals(10, layer.getMapDelegate().get("x"));
		}

		@Test
		@DisplayName("createCopyWithMergedTransactionalMemory with null layer returns the delegate")
		void shouldReturnDelegateWhenLayerIsNull() {
			// For a simple map (no TransactionalLayerProducer values), null layer => return delegate
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);

			final Map<String, Integer> result =
				TransactionalMapTest.this.tested.createCopyWithMergedTransactionalMemory(null, maintainer);

			// must return the underlying delegate when no layer exists and no producer values
			assertNotNull(result);
			assertEquals(2, result.size());
			assertEquals(1, result.get("a"));
			assertEquals(2, result.get("b"));
		}

		@Test
		@DisplayName("removeLayer can be called without throwing when no layer exists")
		void shouldRemoveLayerWithoutError() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);

			// Should not throw even when no layer has been created
			assertDoesNotThrow(() -> TransactionalMapTest.this.tested.removeLayer(maintainer));
		}

	}

	/**
	 * Tests verifying equals, hashCode, and toString behaviour of {@link TransactionalMap}.
	 */
	@Nested
	@DisplayName("equals, hashCode, and toString")
	class EqualsHashCodeToStringTest {

		@Test
		@DisplayName("equals a plain map with identical entries")
		void shouldEqualPlainMap() {
			final Map<String, Integer> plain = new HashMap<>();
			plain.put("a", 1);
			plain.put("b", 2);

			assertEquals(plain, TransactionalMapTest.this.tested);
		}

		@Test
		@DisplayName("not equal to a map with different entries")
		void shouldNotEqualMapWithDifferentEntries() {
			final Map<String, Integer> different = new HashMap<>();
			different.put("a", 9);
			different.put("b", 9);

			assertNotEquals(different, TransactionalMapTest.this.tested);
		}

		@Test
		@DisplayName("equals a plain map with identical entries after transactional mutations are committed")
		void shouldEqualPlainMapAfterTransactionCommit() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					original.remove("a");
				},
				(original, committedVersion) -> {
					final Map<String, Integer> expected = new HashMap<>();
					expected.put("b", 2);
					expected.put("c", 3);
					assertEquals(expected, committedVersion);
				}
			);
		}

		@Test
		@DisplayName("hashCode is consistent across multiple calls")
		void shouldHaveConsistentHashCode() {
			final int first = TransactionalMapTest.this.tested.hashCode();
			final int second = TransactionalMapTest.this.tested.hashCode();

			assertEquals(first, second);
		}

		@Test
		@DisplayName("toString produces a non-empty, brace-enclosed string for a non-empty map")
		void shouldProduceNonEmptyToString() {
			final String str = TransactionalMapTest.this.tested.toString();

			assertNotNull(str);
			assertTrue(str.startsWith("{"), "toString should start with '{' but was: " + str);
			assertTrue(str.endsWith("}"), "toString should end with '}' but was: " + str);
			assertFalse(str.isEmpty());
		}

	}

	/**
	 * Tests verifying the clone behaviour of {@link TransactionalMap} both inside and outside a transaction.
	 */
	@Nested
	@DisplayName("Clone")
	class CloneTest {

		@Test
		@DisplayName("clone outside a transaction produces a copy with the same entries")
		void shouldCloneOutsideTransaction() throws CloneNotSupportedException {
			@SuppressWarnings("unchecked")
			final TransactionalMap<String, Integer> clone = (TransactionalMap<String, Integer>) TransactionalMapTest.this.tested.clone();

			assertMapContains(clone, new Tuple("a", 1), new Tuple("b", 2));
			assertNotSame(TransactionalMapTest.this.tested, clone);
		}

		@Test
		@DisplayName("clone inside a transaction carries the transactional changes into the clone")
		void shouldCloneInsideTransaction() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);

					try {
						@SuppressWarnings("unchecked")
						final TransactionalMap<String, Integer> clone =
							(TransactionalMap<String, Integer>) original.clone();
						// clone must see the transactional change
						assertMapContains(clone, new Tuple("a", 1), new Tuple("b", 2), new Tuple("c", 3));
					} catch (CloneNotSupportedException ex) {
						fail("Clone should be supported: " + ex.getMessage());
					}
				},
				(original, committedVersion) -> {
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("b", 2), new Tuple("c", 3));
				}
			);
		}

	}

	/**
	 * Tests verifying correct behaviour in boundary and edge-case scenarios.
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCasesTest {

		@Test
		@DisplayName("put then remove the same key within a transaction leaves map unchanged")
		void shouldCancelPutAndRemoveSameKey() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					original.remove("c");

					// "c" must not appear — net change is zero
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("remove then re-put the same key with a new value reflects the new value")
		void shouldReflectNewValueAfterRemoveThenRePut() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.remove("a");
					original.put("a", 99);

					assertEquals(99, original.get("a"));
					assertMapContains(original, new Tuple("a", 99), new Tuple("b", 2));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("a", 99), new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("repeated put of the same key reflects the last written value")
		void shouldReflectLastPutValue() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.put("a", 10);
					original.put("a", 20);
					original.put("a", 30);

					assertEquals(30, original.get("a"));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("a", 30), new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("removing the same key twice returns the value on first call and null on second")
		void shouldReturnNullOnDoubleRemove() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					final Integer first = original.remove("a");
					final Integer second = original.remove("a");

					assertEquals(1, first);
					assertNull(second);
					assertMapContains(original, new Tuple("b", 2));
				},
				(original, committedVersion) -> {
					assertMapContains(committedVersion, new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("containsValue returns false after the entry holding that value has been removed")
		void shouldNotContainValueAfterRemove() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					original.remove("a");

					assertFalse(original.containsValue(1));
					assertTrue(original.containsValue(2));
				},
				(original, committedVersion) -> {
					assertFalse(committedVersion.containsValue(1));
					assertTrue(committedVersion.containsValue(2));
				}
			);
		}

		@Test
		@DisplayName("operations on an empty delegate work correctly inside a transaction")
		void shouldHandleEmptyDelegate() {
			final TransactionalMap<String, Integer> empty = new TransactionalMap<>(new HashMap<>());

			assertStateAfterCommit(
				empty,
				original -> {
					assertTrue(original.isEmpty());
					original.put("x", 42);
					assertFalse(original.isEmpty());
					assertMapContains(original, new Tuple("x", 42));
				},
				(original, committedVersion) -> {
					assertTrue(original.isEmpty());
					assertMapContains(committedVersion, new Tuple("x", 42));
				}
			);
		}

	}

	/**
	 * Regression test that verifies a specific sequence of operations that was previously found to produce
	 * incorrect results. The exact sequence is documented in the comment inside the method body.
	 */
	@Nested
	@DisplayName("Regression tests")
	class RegressionTest {

		@Test
		@DisplayName("handles put-then-iterator-remove-then-re-put sequence correctly")
		void verify() {
			/*
			 * START: Q: 33,b: 29,S: 185,3: 86,c: 110,T: 181,e: 38,6: 91,J: 65
			 * */

			TransactionalMapTest.this.tested.clear();
			TransactionalMapTest.this.tested.put("Q", 33);
			TransactionalMapTest.this.tested.put("b", 29);
			TransactionalMapTest.this.tested.put("S", 185);
			TransactionalMapTest.this.tested.put("3", 86);
			TransactionalMapTest.this.tested.put("c", 110);
			TransactionalMapTest.this.tested.put("T", 181);
			TransactionalMapTest.this.tested.put("e", 38);
			TransactionalMapTest.this.tested.put("6", 91);
			TransactionalMapTest.this.tested.put("J", 65);

			final HashMap<String, Integer> referenceMap = new HashMap<>(TransactionalMapTest.this.tested);

			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {

					/* +D:18#0+D:72 */

					original.put("D", 18);
					referenceMap.put("D", 18);
					final Iterator<Entry<String, Integer>> it = original.entrySet().iterator();
					final Entry<String, Integer> entry = it.next();
					it.remove();
					referenceMap.remove(entry.getKey());
					original.put("D", 72);
					referenceMap.put("D", 72);

					assertMapContains(original, referenceMap.entrySet().stream().map(x -> new Tuple(x.getKey(), x.getValue())).toArray(Tuple[]::new));
				},
				(original, committedVersion) -> {
					assertMapContains(committedVersion, referenceMap.entrySet().stream().map(x -> new Tuple(x.getKey(), x.getValue())).toArray(Tuple[]::new));
				}
			);
		}

	}

	/**
	 * Generational randomized proof test that applies random map modifications within transactions and verifies
	 * the committed state matches a reference map that tracks the same operations.
	 */
	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

		@DisplayName("survives generational randomized test applying modifications on it")
		@ParameterizedTest(name = "TransactionalMap should survive generational randomized test applying modifications on it")
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		void generationalProofTest(GenerationalTestInput input) {
			final int initialCount = 100;
			final Map<String, Integer> initialState = generateRandomInitialMap(new Random(input.randomSeed()), initialCount);

			runFor(
				input,
				10_000,
				new TestState(
					new StringBuilder(256),
					initialState
				),
				(random, testState) -> {
					final TransactionalMap<String, Integer> transactionalMap = new TransactionalMap<>(testState.initialMap());
					final Map<String, Integer> referenceMap = new HashMap<>(testState.initialMap());

					final StringBuilder codeBuffer = testState.code();
					codeBuffer.append("\nSTART: ")
						.append(
							transactionalMap.entrySet()
								.stream()
								.map(entry -> entry.getKey() + ": " + entry.getValue())
								.collect(Collectors.joining(","))
						)
						.append("\n");

					assertStateAfterCommit(
						transactionalMap,
						original -> {
							final int operationsInTransaction = random.nextInt(5);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = transactionalMap.size();
								assertEquals(referenceMap.size(), length);
								final int operation = random.nextInt(4);
								if ((operation == 0 || length < 10) && length < 120) {
									// insert / update item
									final String newRecKey = String.valueOf((char) (40 + random.nextInt(64)));
									final Integer newRecId = random.nextInt(initialCount << 1);
									transactionalMap.put(newRecKey, newRecId);
									referenceMap.put(newRecKey, newRecId);
									codeBuffer.append("+").append(newRecKey).append(":").append(newRecId);
								} else if (operation == 1) {
									String recKey = null;
									final int index = random.nextInt(length);
									final Iterator<String> it = referenceMap.keySet().iterator();
									for (int j = 0; j <= index; j++) {
										final String key = it.next();
										if (j == index) {
											recKey = key;
										}
									}
									codeBuffer.append("-").append(recKey);
									transactionalMap.remove(recKey);
									referenceMap.remove(recKey);
								} else if (operation == 2) {
									// update existing item by iterator
									final int updateIndex = random.nextInt(length);
									final Integer updatedValue = random.nextInt(initialCount << 1);
									codeBuffer.append("!").append(updateIndex).append(":").append(updatedValue);
									final Iterator<Entry<String, Integer>> it = transactionalMap.entrySet().iterator();
									for (int j = 0; j <= updateIndex; j++) {
										final Entry<String, Integer> entry = it.next();
										if (j == updateIndex) {
											entry.setValue(updatedValue);
											referenceMap.put(entry.getKey(), updatedValue);
										}
									}
								} else {
									// remove existing item by iterator
									final int updateIndex = random.nextInt(length);
									codeBuffer.append("#").append(updateIndex);
									final Iterator<Entry<String, Integer>> it = transactionalMap.entrySet().iterator();
									for (int j = 0; j <= updateIndex; j++) {
										final Entry<String, Integer> entry = it.next();
										if (j == updateIndex) {
											it.remove();
											referenceMap.remove(entry.getKey());
										}
									}
								}
							}
							codeBuffer.append("\n");
						},
						(original, committed) -> {
							assertMapContains(
								committed,
								referenceMap.entrySet()
									.stream()
									.map(it -> new Tuple(it.getKey(), it.getValue()))
									.toArray(Tuple[]::new)
							);
						}
					);

					return new TestState(
						new StringBuilder(256),
						referenceMap
					);
				}
			);
		}

	}

	// -----------------------------------------------------------------------
	// Shared helpers
	// -----------------------------------------------------------------------

	/**
	 * Verifies that the given `map` contains exactly the entries described by `data` and no others.
	 * Checks size, isEmpty, get, containsKey, containsValue, and all three iterator types.
	 *
	 * @param map  the map under test
	 * @param data the expected key-value pairs; pass no arguments to assert an empty map
	 */
	@SuppressWarnings("WhileLoopReplaceableByForEach")
	private static void assertMapContains(@Nonnull Map<String, Integer> map, @Nonnull Tuple... data) {
		if (data.length == 0) {
			assertTrue(map.isEmpty());
		} else {
			assertFalse(map.isEmpty());
		}

		assertEquals(data.length, map.size());

		final Map<String, Integer> expectedMap = new HashMap<>(data.length);
		for (Tuple tuple : data) {
			expectedMap.put(tuple.key(), tuple.value());
			assertEquals(tuple.value(), map.get(tuple.key()));
			assertTrue(map.containsKey(tuple.key()));
			assertTrue(map.containsValue(tuple.value()));
		}

		final Iterator<Entry<String, Integer>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<String, Integer> entry = it.next();
			assertEquals(expectedMap.get(entry.getKey()), entry.getValue());
		}

		final Iterator<String> keyIt = map.keySet().iterator();
		while (keyIt.hasNext()) {
			final String key = keyIt.next();
			assertTrue(expectedMap.containsKey(key));
		}

		final Iterator<Integer> valueIt = map.values().iterator();
		while (valueIt.hasNext()) {
			final Integer value = valueIt.next();
			assertTrue(expectedMap.containsValue(value));
		}
	}

	/**
	 * Generates a randomized initial map of the given size, using single printable characters as keys
	 * and random integers as values.
	 *
	 * @param rnd   the random source — pass a seeded instance for reproducibility
	 * @param count the number of entries to generate (actual size may be smaller due to key collisions)
	 * @return the generated map
	 */
	@Nonnull
	private static Map<String, Integer> generateRandomInitialMap(@Nonnull Random rnd, int count) {
		final Map<String, Integer> initialArray = new HashMap<>(count);
		for (int i = 0; i < count; i++) {
			final String recKey = String.valueOf((char) (40 + rnd.nextInt(64)));
			final int recId = rnd.nextInt(count << 1);
			initialArray.put(recKey, recId);
		}
		return initialArray;
	}

	/**
	 * Tests that expose real bugs in TransactionalMemoryEntryWrapper and MapChanges.
	 */
	@Nested
	@DisplayName("Bug fixes")
	class BugFixesTest {

		/**
		 * BUG-1: TransactionalMemoryEntryWrapper.equals() used `this.delegate.getClass().isInstance(obj)`
		 * which always returns false for SimpleEntry (and any class other than the JDK-internal HashMap$Node).
		 * Also when overwrittenValue != null, it compared value.equals(entry-object) instead of
		 * comparing key+value per the Map.Entry contract.
		 */
		@Test
		@DisplayName("entrySet contains-check recognises a SimpleEntry matching a transactionally modified value")
		void shouldCorrectlyCompareEntryWrapperWithSimpleEntry() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					// Modify "a"->1 to "a"->99 via entry iterator setValue (goes through TransactionalMemoryEntryWrapper)
					for (Entry<String, Integer> entry : original.entrySet()) {
						if ("a".equals(entry.getKey())) {
							entry.setValue(99);
						}
					}

					// After modification the entry set must report that ("a", 99) is present
					final Set<Entry<String, Integer>> entries = original.entrySet();
					assertTrue(
						entries.contains(new SimpleEntry<>("a", 99)),
						"entrySet.contains(SimpleEntry(\"a\", 99)) must be true after setValue(99)"
					);
					// The old value must no longer be considered present
					assertFalse(
						entries.contains(new SimpleEntry<>("a", 1)),
						"entrySet.contains(SimpleEntry(\"a\", 1)) must be false after setValue(99)"
					);
				},
				(original, committedVersion) -> {
					assertTrue(committedVersion.containsKey("a"));
					assertEquals(99, committedVersion.get("a"));
				}
			);
		}

		/**
		 * BUG-2: TransactionalMemoryEntryWrapper.hashCode() returned only overwrittenValue.hashCode()
		 * when the value was overwritten, omitting the XOR with the key's hashCode.
		 * The Map.Entry contract requires: key.hashCode() ^ value.hashCode().
		 */
		@Test
		@DisplayName("entry wrapper hashCode equals key.hashCode XOR newValue.hashCode after setValue")
		void shouldComputeCorrectHashCodeForModifiedEntryWrapper() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					// Modify "b"->2 to "b"->77 via entry iterator
					Entry<String, Integer> modifiedEntry = null;
					for (Entry<String, Integer> entry : original.entrySet()) {
						if ("b".equals(entry.getKey())) {
							entry.setValue(77);
							modifiedEntry = entry;
						}
					}

					assertNotNull(modifiedEntry, "entry for 'b' must be found");

					// Per Map.Entry contract: hashCode must be key.hashCode() ^ value.hashCode()
					final int expectedHashCode = "b".hashCode() ^ Integer.valueOf(77).hashCode();
					assertEquals(
						expectedHashCode,
						modifiedEntry.hashCode(),
						"Entry hashCode must be key.hashCode() ^ newValue.hashCode()"
					);
				},
				(original, committedVersion) -> {
					assertEquals(77, committedVersion.get("b"));
				}
			);
		}

		/**
		 * BUG-3: MapChanges.containsValue() checked `!containsRemoved(key)` for delegate entries
		 * but did NOT check `!containsCreatedOrModified(key)`. So if key "a" was updated from
		 * value 1 to value 3, containsValue(1) still returned true because the delegate still
		 * held the old value and the key was not in the removed set.
		 */
		@Test
		@DisplayName("containsValue returns false for a stale delegate value after the key is updated in transaction")
		void shouldNotReturnStaleValueAfterUpdateInTransaction() {
			assertStateAfterCommit(
				TransactionalMapTest.this.tested,
				original -> {
					// Update "a" from 1 to 3 — the delegate still has "a"->1
					original.put("a", 3);

					// containsValue(1) must be false: the only holder of value 1 was "a", now updated to 3
					assertFalse(
						original.containsValue(1),
						"containsValue(1) must be false after updating 'a' from 1 to 3"
					);
					// containsValue(3) must be true: "a" now holds value 3
					assertTrue(
						original.containsValue(3),
						"containsValue(3) must be true after updating 'a' to 3"
					);
				},
				(original, committedVersion) -> {
					assertFalse(committedVersion.containsValue(1));
					assertTrue(committedVersion.containsValue(3));
				}
			);
		}

	}

	/**
	 * Carries the state between generational test iterations.
	 *
	 * @param code       accumulated operation log for diagnosing failures
	 * @param initialMap the map state to use at the start of the next iteration
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull Map<String, Integer> initialMap
	) {}

	/**
	 * A simple key-value pair used as expected data in {@link #assertMapContains}.
	 *
	 * @param key   the map key
	 * @param value the expected value for that key
	 */
	private record Tuple(@Nonnull String key, @Nonnull Integer value) {
	}

}
