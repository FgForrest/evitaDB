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

package io.evitadb.index.set;

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the contract of {@link TransactionalSet} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@SuppressWarnings("SameParameterValue")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class TransactionalSetTest {
	private TransactionalSet<String> tested;

	@BeforeEach
	void setUp() {
		final Set<String> underlyingData = new LinkedHashSet<>();
		underlyingData.add("a");
		underlyingData.add("b");
		this.tested = new TransactionalSet<>(underlyingData);
	}

	/**
	 * Tests verifying construction and identity of {@link TransactionalSet}.
	 */
	@Nested
	@DisplayName("Construction and identity")
	class ConstructionAndIdentityTest {

		@Test
		@DisplayName("each instance gets a unique ID")
		void shouldAssignUniqueIdPerInstance() {
			final TransactionalSet<String> other = new TransactionalSet<>(new HashSet<>());

			assertNotEquals(
				TransactionalSetTest.this.tested.getId(),
				other.getId()
			);
		}

		/**
		 * Verifies that a new diff layer exposes the delegate's original membership.
		 */
		@Test
		@DisplayName("createLayer returns SetChanges backed by delegate")
		void shouldCreateLayerBackedByDelegate() {
			final SetChanges<String> layer = TransactionalSetTest.this.tested.createLayer();

			assertNotNull(layer);
			assertEquals(2, layer.size());
			assertTrue(layer.contains("a"));
			assertTrue(layer.contains("b"));
		}

		@Test
		@DisplayName("toString includes element values")
		void shouldProduceReadableToString() {
			final String str = TransactionalSetTest.this.tested.toString();

			assertNotNull(str);
			assertTrue(str.startsWith("{"));
			assertTrue(str.endsWith("}"));
			assertTrue(str.contains("a"));
			assertTrue(str.contains("b"));
		}

	}

	/**
	 * Tests verifying operations without an active transaction.
	 */
	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("add inserts directly into the delegate")
		void shouldAddDirectly() {
			final boolean result = TransactionalSetTest.this.tested.add("c");

			assertTrue(result);
			assertEquals(3, TransactionalSetTest.this.tested.size());
			assertTrue(TransactionalSetTest.this.tested.contains("c"));
		}

		@Test
		@DisplayName("add of existing element returns false")
		void shouldReturnFalseWhenAddingExisting() {
			final boolean result = TransactionalSetTest.this.tested.add("a");

			assertFalse(result);
			assertEquals(2, TransactionalSetTest.this.tested.size());
		}

		@Test
		@DisplayName("remove deletes directly from the delegate")
		void shouldRemoveDirectly() {
			final boolean result = TransactionalSetTest.this.tested.remove("a");

			assertTrue(result);
			assertEquals(1, TransactionalSetTest.this.tested.size());
			assertFalse(TransactionalSetTest.this.tested.contains("a"));
		}

		@Test
		@DisplayName("remove of absent element returns false")
		void shouldReturnFalseWhenRemovingAbsent() {
			final boolean result = TransactionalSetTest.this.tested.remove("z");

			assertFalse(result);
			assertEquals(2, TransactionalSetTest.this.tested.size());
		}

		@Test
		@DisplayName("size reports correct count")
		void shouldReportCorrectSize() {
			assertEquals(2, TransactionalSetTest.this.tested.size());
		}

		@Test
		@DisplayName("isEmpty reports correctly")
		void shouldReportEmptyCorrectly() {
			assertFalse(TransactionalSetTest.this.tested.isEmpty());

			final TransactionalSet<String> empty = new TransactionalSet<>(new HashSet<>());
			assertTrue(empty.isEmpty());
		}

		@Test
		@DisplayName("contains checks membership")
		void shouldCheckContains() {
			assertTrue(TransactionalSetTest.this.tested.contains("a"));
			assertFalse(TransactionalSetTest.this.tested.contains("z"));
		}

		@Test
		@DisplayName("containsAll checks all elements")
		void shouldCheckContainsAll() {
			assertTrue(TransactionalSetTest.this.tested.containsAll(Arrays.asList("a", "b")));
			assertFalse(TransactionalSetTest.this.tested.containsAll(Arrays.asList("a", "z")));
		}

		@Test
		@DisplayName("addAll adds all elements to delegate")
		void shouldAddAllDirectly() {
			final boolean result = TransactionalSetTest.this.tested.addAll(Arrays.asList("c", "d"));

			assertTrue(result);
			assertEquals(4, TransactionalSetTest.this.tested.size());
		}

		@Test
		@DisplayName("retainAll retains only specified elements")
		void shouldRetainAllDirectly() {
			final boolean result = TransactionalSetTest.this.tested.retainAll(List.of("a"));

			assertTrue(result);
			assertSetContains(TransactionalSetTest.this.tested, "a");
		}

		@Test
		@DisplayName("removeAll removes specified elements")
		void shouldRemoveAllDirectly() {
			//noinspection SlowAbstractSetRemoveAll
			final boolean result = TransactionalSetTest.this.tested.removeAll(List.of("a"));

			assertTrue(result);
			assertSetContains(TransactionalSetTest.this.tested, "b");
		}

		@Test
		@DisplayName("clear empties the delegate")
		void shouldClearDirectly() {
			TransactionalSetTest.this.tested.clear();

			assertTrue(TransactionalSetTest.this.tested.isEmpty());
			assertEquals(0, TransactionalSetTest.this.tested.size());
		}

	}

	/**
	 * Tests verifying mutations within a transaction are visible during
	 * the transaction and correctly committed.
	 */
	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("add creates modified copy on commit")
		void shouldNotModifyOriginalStateButCreateModifiedCopy() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("a");
					original.add("c");
					assertSetContains(original, "a", "b", "c");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "a", "b", "c");
				}
			);
		}

		@Test
		@DisplayName("remove creates modified copy on commit")
		void shouldNotModifyOriginalOnRemoval() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.remove("a");
					original.add("c");
					assertSetContains(original, "b", "c");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "b", "c");
				}
			);
		}

		@Test
		@DisplayName("mixed operations merge correctly on commit")
		void shouldMergeRemovalsAndInsertions() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.remove("a");
					original.add("b");
					original.add("c");
					assertSetContains(original, "b", "c");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "b", "c");
				}
			);
		}

		@Test
		@DisplayName("isEmpty transitions correctly through operations")
		void shouldInterpretIsEmptyCorrectly() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					assertFalse(original.isEmpty());
					original.add("c");
					assertFalse(original.isEmpty());
					original.remove("a");
					assertFalse(original.isEmpty());
					original.remove("c");
					assertFalse(original.isEmpty());
					original.remove("b");
					assertTrue(original.isEmpty());
					original.add("d");
					assertFalse(original.isEmpty());
					original.remove("d");
					assertTrue(original.isEmpty());
					assertSetContains(original);
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion);
				}
			);
		}

		@Test
		@DisplayName("containsAll works inside a transaction")
		void shouldContainAllInTransaction() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");
					assertTrue(original.containsAll(Arrays.asList("a", "b", "c")));
					assertFalse(original.containsAll(Arrays.asList("a", "z")));
				},
				(original, committedVersion) -> {
					assertSetContains(committedVersion, "a", "b", "c");
				}
			);
		}

		@Test
		@DisplayName("addAll commits all added elements")
		void shouldAddAllInTransaction() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.addAll(Arrays.asList("c", "d"));
					assertSetContains(original, "a", "b", "c", "d");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "a", "b", "c", "d");
				}
			);
		}

		@Test
		@DisplayName("retainAll commits retained elements")
		void shouldRetainAllInTransaction() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.retainAll(Arrays.asList("a", "c", "d"));
					assertSetContains(original, "a");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "a");
				}
			);
		}

		@Test
		@DisplayName("removeAll commits removed elements")
		void shouldRemoveAllInTransaction() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					//noinspection SlowAbstractSetRemoveAll
					original.removeAll(Arrays.asList("a", "c", "d"));
					assertSetContains(original, "b");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "b");
				}
			);
		}

		@Test
		@DisplayName("toArray reflects transactional state")
		void shouldCreateToArrayInTransaction() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");
					original.remove("b");
					assertSetContains(original, "a", "c");
					assertArrayEquals(new String[]{"a", "c"}, original.toArray());
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertArrayEquals(new String[]{"a", "c"}, committedVersion.toArray());
				}
			);
		}

		@Test
		@DisplayName("typed toArray reflects transactional state")
		void shouldCreateTypedToArrayInTransaction() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");
					original.remove("b");

					final String[] result = original.toArray(new String[0]);
					assertEquals(2, result.length);
					final Set<String> resultSet = new HashSet<>(Arrays.asList(result));
					assertTrue(resultSet.contains("a"));
					assertTrue(resultSet.contains("c"));
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
				}
			);
		}

		@Test
		@DisplayName("iterator removal in transaction preserves original")
		void shouldNotModifyOriginalOnIteratorRemoval() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");

					final Iterator<String> it = original.iterator();
					//noinspection Java8CollectionRemoveIf
					while (it.hasNext()) {
						final String key = it.next();
						if (key.equals("b")) {
							it.remove();
						}
					}

					assertSetContains(original, "a", "c");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "a", "c");
				}
			);
		}

		@Test
		@DisplayName("removeIf works inside a transaction")
		void shouldRemoveValuesWhileIterating() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.clear();
					original.add("ac");
					original.add("bc");
					original.add("ad");
					original.add("ae");

					original.removeIf(key -> key.contains("a"));

					assertSetContains(original, "bc");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "bc");
				}
			);
		}

		@Test
		@DisplayName("contains newly added element in transaction")
		void shouldContainNewlyAddedElement() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");
					assertTrue(original.contains("c"));
					assertFalse(original.contains("z"));
				},
				(original, committedVersion) -> {
					assertSetContains(committedVersion, "a", "b", "c");
				}
			);
		}

		@Test
		@DisplayName("putAll within a transaction commits all entries")
		void shouldPutAllInTransaction() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.addAll(Arrays.asList("c", "d"));
					assertSetContains(original, "a", "b", "c", "d");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "a", "b", "c", "d");
				}
			);
		}

	}

	/**
	 * Tests verifying that all mutations are discarded upon rollback.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("add is discarded on rollback")
		void shouldDiscardAddOnRollback() {
			assertStateAfterRollback(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");
					assertSetContains(original, "a", "b", "c");
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertSetContains(original, "a", "b");
				}
			);
		}

		@Test
		@DisplayName("remove is discarded on rollback")
		void shouldDiscardRemoveOnRollback() {
			assertStateAfterRollback(
				TransactionalSetTest.this.tested,
				original -> {
					original.remove("a");
					assertSetContains(original, "b");
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertSetContains(original, "a", "b");
				}
			);
		}

		@Test
		@DisplayName("addAll is discarded on rollback")
		void shouldDiscardAddAllOnRollback() {
			assertStateAfterRollback(
				TransactionalSetTest.this.tested,
				original -> {
					original.addAll(Arrays.asList("c", "d"));
					assertEquals(4, original.size());
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertSetContains(original, "a", "b");
				}
			);
		}

		@Test
		@DisplayName("clear is discarded on rollback")
		void shouldDiscardClearOnRollback() {
			assertStateAfterRollback(
				TransactionalSetTest.this.tested,
				original -> {
					original.clear();
					assertTrue(original.isEmpty());
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertSetContains(original, "a", "b");
				}
			);
		}

		@Test
		@DisplayName("multiple operations all discarded on rollback")
		void shouldDiscardMultipleOperationsOnRollback() {
			assertStateAfterRollback(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");
					original.remove("a");
					assertSetContains(original, "b", "c");
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertSetContains(original, "a", "b");
				}
			);
		}

	}

	/**
	 * Tests verifying the {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}
	 * contract methods on {@link TransactionalSet}.
	 */
	@Nested
	@DisplayName("TransactionalLayerProducer contract")
	class TransactionalLayerProducerContractTest {

		@Test
		@DisplayName("createCopyWithMergedTransactionalMemory with null layer returns delegate")
		void shouldReturnDelegateWhenLayerIsNull() {
			final TransactionalLayerMaintainer maintainer = Mockito.mock(TransactionalLayerMaintainer.class);

			final Set<String> result = TransactionalSetTest.this.tested
				.createCopyWithMergedTransactionalMemory(null, maintainer);

			assertNotNull(result);
			assertEquals(2, result.size());
			assertTrue(result.contains("a"));
			assertTrue(result.contains("b"));
		}

		/**
		 * Verifies that merging a populated diff applies both the added key and the removed delegate key.
		 */
		@Test
		@DisplayName("createCopyWithMergedTransactionalMemory with layer returns merged set")
		void shouldReturnMergedSetWhenLayerExists() {
			final TransactionalLayerMaintainer maintainer = Mockito.mock(TransactionalLayerMaintainer.class);

			final SetChanges<String> layer = TransactionalSetTest.this.tested.createLayer();
			layer.put("c");
			layer.remove("a");

			final Set<String> result = TransactionalSetTest.this.tested
				.createCopyWithMergedTransactionalMemory(layer, maintainer);

			assertNotNull(result);
			assertEquals(2, result.size());
			assertTrue(result.contains("b"));
			assertTrue(result.contains("c"));
			assertFalse(result.contains("a"));
		}

		@Test
		@DisplayName("removeLayer does not throw")
		void shouldRemoveLayerWithoutError() {
			final TransactionalLayerMaintainer maintainer = Mockito.mock(TransactionalLayerMaintainer.class);

			assertDoesNotThrow(() -> TransactionalSetTest.this.tested.removeLayer(maintainer));
		}

	}

	/**
	 * Tests verifying equals, hashCode, and toString behaviour.
	 */
	@Nested
	@DisplayName("equals, hashCode, and toString")
	class EqualsHashCodeToStringTest {

		@Test
		@DisplayName("equals returns true for self-reference")
		void shouldEqualItself() {
			assertEquals(
				TransactionalSetTest.this.tested,
				TransactionalSetTest.this.tested
			);
		}

		@Test
		@DisplayName("equals returns false for null")
		@SuppressWarnings({"SimplifiableAssertion", "ObjectEqualsNull"})
		void shouldNotEqualNull() {
			// deliberately not `assertNotEquals`: with `null` as its first argument it calls no `equals` at all, so
			// the null check of the class under test would never run
			assertFalse(TransactionalSetTest.this.tested.equals(null));
		}

		@Test
		@DisplayName("equals returns false for non-collection")
		@SuppressWarnings({"SimplifiableAssertion", "EqualsBetweenInconvertibleTypes"})
		void shouldNotEqualString() {
			// deliberately not `assertNotEquals`: it calls `equals` on its first argument, and the IDE's argument-order
			// fix moves the literal there, so `String.equals` would be tested instead of the class under test
			assertFalse(TransactionalSetTest.this.tested.equals("not a set"));
		}

		@Test
		@DisplayName("equals returns true for HashSet with same elements")
		void shouldEqualHashSetWithSameElements() {
			final Set<String> plain = new HashSet<>();
			plain.add("a");
			plain.add("b");

			assertEquals(
				TransactionalSetTest.this.tested, plain,
				"TransactionalSet should equal a HashSet with " +
					"the same elements"
			);
		}

		@Test
		@DisplayName(
			"equals returns true for another TransactionalSet " +
				"with same elements"
		)
		void shouldEqualAnotherTransactionalSetWithSameElements() {
			final TransactionalSet<String> other =
				new TransactionalSet<>(
					new LinkedHashSet<>(
						Arrays.asList("a", "b")
					)
				);

			assertEquals(
				TransactionalSetTest.this.tested, other,
				"Two TransactionalSets with the same elements " +
					"should be equal"
			);
		}

		@Test
		@DisplayName("hashCode is consistent across calls")
		void shouldHaveConsistentHashCode() {
			final int first = TransactionalSetTest.this.tested.hashCode();
			final int second = TransactionalSetTest.this.tested.hashCode();

			assertEquals(first, second);
		}

		@Test
		@DisplayName("two sets with same elements have equal hashCode")
		void shouldHaveEqualHashCodeForSameElements() {
			final TransactionalSet<String> other =
				new TransactionalSet<>(
					new LinkedHashSet<>(Arrays.asList("a", "b"))
				);

			assertEquals(
				TransactionalSetTest.this.tested.hashCode(),
				other.hashCode()
			);
		}

		@Test
		@DisplayName("empty set toString returns {}")
		void shouldReturnCurlyBracesForEmptySet() {
			final TransactionalSet<String> empty = new TransactionalSet<>(new HashSet<>());

			assertEquals("{}", empty.toString());
		}

		@Test
		@DisplayName("non-empty toString starts with { and ends with }")
		void shouldReturnFormattedToString() {
			final String str = TransactionalSetTest.this.tested.toString();

			assertTrue(str.startsWith("{"));
			assertTrue(str.endsWith("}"));
			assertFalse(str.isEmpty());
		}

	}

	/**
	 * Tests verifying the iterator contract.
	 */
	@Nested
	@DisplayName("Iterator contract")
	class IteratorContractTest {

		/**
		 * Verifies removal after lookahead for a created key and after exhaustion for a delegate key.
		 *
		 * @param moreElements whether the iterator should have another element after the one being removed
		 */
		@ParameterizedTest
		@ValueSource(booleans = {true, false})
		@DisplayName("remove targets the last returned element after hasNext")
		void shouldRemoveLastReturnedElementWhenHasNextLooksAhead(boolean moreElements) {
			final Set<String> expected = new HashSet<>(Set.of("a", "c", "d"));
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");
					original.add("d");
					original.remove("b");

					final Iterator<String> iterator = original.iterator();
					if (!moreElements) {
						iterator.next();
						iterator.next();
					}
					final String returned = iterator.next();
					assertEquals(moreElements, iterator.hasNext());
					iterator.remove();

					assertTrue(expected.remove(returned));
					assertSetContains(original, expected.toArray(String[]::new));
					if (moreElements) {
						assertTrue(expected.contains(iterator.next()));
						assertEquals("a", iterator.next());
					}
					assertFalse(iterator.hasNext());
					assertFalse(original.contains(returned));
				},
				(original, committedVersion) -> assertSetContains(committedVersion, expected.toArray(String[]::new))
			);
		}

		/**
		 * Checks both illegal removal states on a merged iterator whose only visible key comes from the delegate.
		 */
		@Test
		@DisplayName("merged iterator requires a fresh next before removing a delegate key")
		void shouldRejectRemovalWhenMergedIteratorHasNoRemovableDelegateKey() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					assertTrue(original.remove("b"));
					removeFirstKeyAndCheckIllegalRemovals(original.iterator());
					assertSetContains(original);
				},
				(original, committedVersion) -> assertSetContains(committedVersion)
			);
		}

		/**
		 * Checks both illegal removal states on a delegate iterator outside a transaction.
		 */
		@Test
		@DisplayName("delegate iterator requires a fresh next outside a transaction")
		void shouldRejectRemovalWhenDelegateIteratorHasNoRemovableKeyOutsideTransaction() {
			final TransactionalSet<String> set = TransactionalSetTest.this.tested;
			removeFirstKeyAndCheckIllegalRemovals(set.iterator());
			assertSetContains(set, "b");
		}

		@Test
		@DisplayName("hasNext is idempotent")
		void shouldKeepIteratorContract() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");

					final List<String> result = new ArrayList<>(3);

					final Iterator<String> it = original.iterator();
					for (int i = 0; i < 50; i++) {
						assertTrue(it.hasNext());
					}
					result.add(it.next());
					for (int i = 0; i < 50; i++) {
						assertTrue(it.hasNext());
					}
					result.add(it.next());
					for (int i = 0; i < 50; i++) {
						assertTrue(it.hasNext());
					}
					result.add(it.next());
					for (int i = 0; i < 50; i++) {
						assertFalse(it.hasNext());
					}

					assertEquals(
						new HashSet<>(Arrays.asList("a", "b", "c")),
						new HashSet<>(result)
					);

					assertThrows(NoSuchElementException.class, it::next);
				},
				(original, committedVersion) -> {
					// iterator contract only
				}
			);
		}

		@Test
		@DisplayName("iterator skips removed items")
		void shouldKeepIteratorContractWhenItemsRemoved() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");
					original.remove("b");

					final List<String> result = new ArrayList<>(2);

					final Iterator<String> it = original.iterator();
					for (int i = 0; i < 50; i++) {
						assertTrue(it.hasNext());
					}
					result.add(it.next());
					for (int i = 0; i < 50; i++) {
						assertTrue(it.hasNext());
					}
					result.add(it.next());
					for (int i = 0; i < 50; i++) {
						assertFalse(it.hasNext());
					}

					assertEquals(
						new HashSet<>(Arrays.asList("a", "c")),
						new HashSet<>(result)
					);

					assertThrows(NoSuchElementException.class, it::next);
				},
				(original, committedVersion) -> {
					// iterator contract only
				}
			);
		}

		@Test
		@DisplayName("iterator.remove on created key works")
		void shouldRemoveCreatedKeyViaIterator() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");

					original.removeIf("c"::equals);

					assertSetContains(original, "a", "b");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "a", "b");
				}
			);
		}

		@Test
		@DisplayName("iterator.remove on delegate key works")
		void shouldRemoveDelegateKeyViaIterator() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");

					original.removeIf("a"::equals);

					assertSetContains(original, "b", "c");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "b", "c");
				}
			);
		}

		/**
		 * Verifies that either removal operation can be rolled back when it is the first write.
		 *
		 * @param useRemoveIf whether to remove via `removeIf` instead of the iterator
		 */
		@ParameterizedTest(name = "removeIf={0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("first removal stays isolated through rollback")
		void shouldIsolateFirstRemovalWhenTransactionRollsBack(boolean useRemoveIf) {
			assertFirstRemovalIsolated(useRemoveIf, false);
		}

		/**
		 * Verifies that either removal operation can be committed when it is the first write.
		 *
		 * @param useRemoveIf whether to remove via `removeIf` instead of the iterator
		 */
		@ParameterizedTest(name = "removeIf={0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("first removal stays isolated until commit")
		void shouldIsolateFirstRemovalWhenTransactionCommits(boolean useRemoveIf) {
			assertFirstRemovalIsolated(useRemoveIf, true);
		}

		/**
		 * Checks the transactional view, the shared delegate during the transaction, and the outcome on close.
		 *
		 * @param useRemoveIf whether to remove via `removeIf` instead of the iterator
		 * @param commit whether to commit the removal
		 */
		private void assertFirstRemovalIsolated(boolean useRemoveIf, boolean commit) {
			final Set<String> initial = Set.of("a", "b");
			final Set<String> delegate = new LinkedHashSet<>(List.of("a", "b"));
			final TransactionalSet<String> set = new TransactionalSet<>(delegate);
			final Set<String> transactionalView = new HashSet<>();
			final Set<String> committedWhileOpen = new HashSet<>();
			final Consumer<TransactionalSet<String>> removeFirst = original -> {
				if (useRemoveIf) {
					original.removeIf("a"::equals);
				} else {
					removeFirstKeyAndCheckIllegalRemovals(original.iterator());
				}
				transactionalView.addAll(original);
				committedWhileOpen.addAll(delegate);
			};
			final BiConsumer<TransactionalSet<String>, Set<String>> verify = (original, committedVersion) -> assertAll(
				() -> assertEquals(Set.of("b"), transactionalView, "Transactional view after removal"),
				() -> assertEquals(initial, committedWhileOpen, "Committed set while the transaction is open"),
				() -> assertEquals(initial, original, "Original set after the transaction closes"),
				() -> assertEquals(commit ? Set.of("b") : null, committedVersion, "Commit result")
			);
			if (commit) {
				assertStateAfterCommit(set, removeFirst, verify);
			} else {
				assertStateAfterRollback(set, removeFirst, verify);
			}
		}

		/**
		 * Removes the first delegate key and checks that both illegal removal states throw.
		 *
		 * @param iterator the fresh iterator over the set containing the delegate key "a"
		 */
		private static void removeFirstKeyAndCheckIllegalRemovals(@Nonnull Iterator<String> iterator) {
			assertThrows(IllegalStateException.class, iterator::remove);
			assertEquals("a", iterator.next());
			iterator.remove();
			assertThrows(IllegalStateException.class, iterator::remove);
		}

		@Test
		@DisplayName("exhausted iterator throws NoSuchElementException")
		void shouldThrowWhenExhausted() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					final Iterator<String> it = original.iterator();
					while (it.hasNext()) {
						it.next();
					}
					assertThrows(NoSuchElementException.class, it::next);
				},
				(original, committedVersion) -> {
					// iterator contract only
				}
			);
		}

		@Test
		@DisplayName("iterates all elements exactly once")
		void shouldIterateAllElements() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");

					final Set<String> visited = new HashSet<>();
					for (String key : original) {
						assertTrue(
							visited.add(key),
							"Duplicate visit: " + key
						);
					}
					assertEquals(
						new HashSet<>(Arrays.asList("a", "b", "c")),
						visited
					);
				},
				(original, committedVersion) -> {
					// iterator verification only
				}
			);
		}

	}

	/**
	 * Tests verifying edge cases and boundary conditions.
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCasesTest {

		@Test
		@DisplayName("add then remove same key leaves set unchanged")
		void shouldCancelAddAndRemoveSameNewKey() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");
					original.remove("c");
					assertSetContains(original, "a", "b");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "a", "b");
				}
			);
		}

		@Test
		@DisplayName("remove then re-add existing key retains it")
		void shouldHandleRemoveThenReAdd() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.remove("a");
					original.add("a");
					assertSetContains(original, "a", "b");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "a", "b");
				}
			);
		}

		@Test
		@DisplayName("double remove returns false on second call")
		void shouldReturnFalseForDoubleRemove() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					final boolean first = original.remove("a");
					final boolean second = original.remove("a");

					assertTrue(first);
					assertFalse(second);
					assertSetContains(original, "b");
				},
				(original, committedVersion) -> {
					assertSetContains(committedVersion, "b");
				}
			);
		}

		@Test
		@DisplayName("add existing returns false in transaction")
		void shouldReturnFalseWhenAddingExistingInTx() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					final boolean result = original.add("a");
					assertFalse(result);
					assertSetContains(original, "a", "b");
				},
				(original, committedVersion) -> {
					assertSetContains(committedVersion, "a", "b");
				}
			);
		}

		@Test
		@DisplayName("empty delegate works correctly in transaction")
		void shouldWorkWithEmptyDelegate() {
			final TransactionalSet<String> empty = new TransactionalSet<>(new HashSet<>());

			assertStateAfterCommit(
				empty,
				original -> {
					assertTrue(original.isEmpty());
					original.add("x");
					assertFalse(original.isEmpty());
					assertSetContains(original, "x");
				},
				(original, committedVersion) -> {
					assertTrue(original.isEmpty());
					assertSetContains(committedVersion, "x");
				}
			);
		}

		@Test
		@DisplayName("single element delegate works correctly")
		void shouldWorkWithSingleElementDelegate() {
			final TransactionalSet<String> single =
				new TransactionalSet<>(
					new LinkedHashSet<>(List.of("x"))
				);

			assertStateAfterCommit(
				single,
				original -> {
					original.remove("x");
					original.add("y");
					assertSetContains(original, "y");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "x");
					assertSetContains(committedVersion, "y");
				}
			);
		}

		@Test
		@DisplayName("add after clear in transaction works")
		void shouldHandleAddAfterClear() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.clear();
					original.add("x");
					assertSetContains(original, "x");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "x");
				}
			);
		}

		@Test
		@DisplayName("retainAll with newly added elements works")
		void shouldRetainAllWithNewlyAddedElements() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");
					original.add("d");
					original.retainAll(Arrays.asList("a", "c"));
					assertSetContains(original, "a", "c");
				},
				(original, committedVersion) -> {
					assertSetContains(original, "a", "b");
					assertSetContains(committedVersion, "a", "c");
				}
			);
		}

	}

	/**
	 * Regression test verifying a specific sequence that was previously
	 * found to produce incorrect results.
	 */
	@Nested
	@DisplayName("Regression tests")
	class RegressionTest {

		@Test
		@DisplayName("handles add-then-iterator-remove-then-re-add sequence")
		void verify() {
			/*
			 * START: Q,b,S,3,c,T,e,6,J
			 * */

			TransactionalSetTest.this.tested.clear();
			TransactionalSetTest.this.tested.add("Q");
			TransactionalSetTest.this.tested.add("b");
			TransactionalSetTest.this.tested.add("S");
			TransactionalSetTest.this.tested.add("3");
			TransactionalSetTest.this.tested.add("c");
			TransactionalSetTest.this.tested.add("T");
			TransactionalSetTest.this.tested.add("e");
			TransactionalSetTest.this.tested.add("6");
			TransactionalSetTest.this.tested.add("J");

			final HashSet<String> referenceMap = new HashSet<>(TransactionalSetTest.this.tested);

			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {

					/* +D#0+D */

					original.add("D");
					referenceMap.add("D");
					final Iterator<String> it = original.iterator();
					final String entry = it.next();
					it.remove();
					referenceMap.remove(entry);
					original.add("D");
					referenceMap.add("D");

					assertSetContains(
						original,
						referenceMap.toArray(String[]::new)
					);
				},
				(original, committedVersion) -> {
					assertSetContains(
						committedVersion,
						referenceMap.toArray(String[]::new)
					);
				}
			);
		}

	}



	/**
	 * Tests exposing real bugs in TransactionalSet and SetChanges.
	 */
	@Nested
	@DisplayName("Bug fixes")
	class BugFixesTest {

		@Test
		@DisplayName(
			"add returns true when re-adding a previously " +
				"removed delegate key"
		)
		void shouldReturnTrueWhenReAddingRemovedDelegateKey() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.remove("a");
					final boolean result = original.add("a");
					assertTrue(
						result,
						"add() should return true for a " +
							"previously removed key"
					);
					assertSetContains(original, "a", "b");
				},
				(original, committedVersion) -> {
					assertSetContains(committedVersion, "a", "b");
				}
			);
		}

	}

	// -----------------------------------------------------------------------
	// Shared helpers
	// -----------------------------------------------------------------------

	/**
	 * Verifies that the given set contains exactly the specified elements.
	 *
	 * @param set  the set under test
	 * @param data the expected elements
	 */
	@SuppressWarnings("WhileLoopReplaceableByForEach")
	private static void assertSetContains(
		@Nonnull Set<String> set,
		@Nonnull String... data
	) {
		if (data.length == 0) {
			assertTrue(set.isEmpty());
		} else {
			assertFalse(set.isEmpty());
		}

		assertEquals(data.length, set.size());

		final Set<String> expectedSet = new HashSet<>(data.length);
		for (String dataItem : data) {
			expectedSet.add(dataItem);
			assertTrue(set.contains(dataItem));
		}

		final Iterator<String> it = set.iterator();
		while (it.hasNext()) {
			final String entry = it.next();
			assertTrue(expectedSet.contains(entry));
		}
	}

}
