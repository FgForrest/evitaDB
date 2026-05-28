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

import io.evitadb.api.requestResponse.data.ContentComparator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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

		@Test
		@DisplayName("createLayer returns SetChanges backed by delegate")
		void shouldCreateLayerBackedByDelegate() {
			final SetChanges<String> layer = TransactionalSetTest.this.tested.createLayer();

			assertNotNull(layer);
			assertEquals(2, layer.getSetDelegate().size());
			assertTrue(layer.getSetDelegate().contains("a"));
			assertTrue(layer.getSetDelegate().contains("b"));
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
		@DisplayName("createLayer returns SetChanges referencing the delegate")
		void shouldCreateLayerReferencingDelegate() {
			final SetChanges<String> layer = TransactionalSetTest.this.tested.createLayer();

			assertNotNull(layer);
			assertEquals(2, layer.getSetDelegate().size());
			assertTrue(layer.getSetDelegate().contains("a"));
			assertTrue(layer.getSetDelegate().contains("b"));
		}

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

		@Test
		@DisplayName("createCopyWithMergedTransactionalMemory with layer returns merged set")
		void shouldReturnMergedSetWhenLayerExists() {
			final TransactionalLayerMaintainer maintainer = Mockito.mock(TransactionalLayerMaintainer.class);

			final SetChanges<String> layer = TransactionalSetTest.this.tested.createLayer();
			layer.put("c");
			layer.registerRemovedKey("a");

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
			//noinspection EqualsWithItself
			assertEquals(
				TransactionalSetTest.this.tested,
				TransactionalSetTest.this.tested
			);
		}

		@Test
		@DisplayName("equals returns false for null")
		void shouldNotEqualNull() {
			assertNotEquals(null, TransactionalSetTest.this.tested);
		}

		@Test
		@DisplayName("equals returns false for non-collection")
		void shouldNotEqualString() {
			assertNotEquals("not a set", TransactionalSetTest.this.tested);
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
	 * Tests verifying clone behaviour.
	 */
	@Nested
	@DisplayName("Clone")
	class CloneTest {

		@Test
		@DisplayName("clone outside a transaction produces a copy")
		void shouldCloneOutsideTransaction()
			throws CloneNotSupportedException {

			@SuppressWarnings("unchecked") final TransactionalSet<String> clone =
				(TransactionalSet<String>)
					TransactionalSetTest.this.tested.clone();

			assertSetContains(clone, "a", "b");
			assertNotSame(TransactionalSetTest.this.tested, clone);
		}

		@Test
		@DisplayName("clone inside a transaction carries changes")
		void shouldCloneInsideTransaction() {
			assertStateAfterCommit(
				TransactionalSetTest.this.tested,
				original -> {
					original.add("c");

					try {
						@SuppressWarnings("unchecked") final TransactionalSet<String> clone =
							(TransactionalSet<String>) original.clone();
						assertSetContains(clone, "a", "b", "c");
					} catch (CloneNotSupportedException ex) {
						fail("Clone should be supported: "
							     + ex.getMessage());
					}
				},
				(original, committedVersion) -> {
					assertSetContains(committedVersion, "a", "b", "c");
				}
			);
		}

	}

	/**
	 * Tests verifying the iterator contract.
	 */
	@Nested
	@DisplayName("Iterator contract")
	class IteratorContractTest {

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

	/**
	 * Tests content substitution for identity-equal but content-different elements.
	 */
	@Nested
	@DisplayName("Content substitution (identity-equal, content-different elements)")
	class ContentSubstitutionTest {

		@Test
		void shouldReplaceSameIdentityElementWithDifferentContentOnAdd() {
			final Box oldA = new Box(1, "OLD-A");
			final Box newA = new Box(1, "NEW-A");
			final Set<Box> underlying = new LinkedHashSet<>();
			underlying.add(oldA);
			final TransactionalSet<Box> set = new TransactionalSet<>(underlying);

			assertStateAfterCommit(
				set,
				original -> original.add(newA),
				(original, committed) -> {
					assertEquals(1, committed.size());
					assertEquals("NEW-A", committed.iterator().next().content(),
						"Committed set must hold the NEW content, not the stale original instance");
				}
			);
		}

		@Test
		void shouldReplaceSameIdentityElementOnRemoveThenAdd() {
			final Box oldA = new Box(1, "OLD-A");
			final Box newA = new Box(1, "NEW-A");
			final Set<Box> underlying = new LinkedHashSet<>();
			underlying.add(oldA);
			final TransactionalSet<Box> set = new TransactionalSet<>(underlying);

			assertStateAfterCommit(
				set,
				original -> {
					original.remove(oldA);
					original.add(newA);
				},
				(original, committed) -> {
					assertEquals(1, committed.size());
					assertEquals("NEW-A", committed.iterator().next().content(),
						"Committed set must hold the NEW content after remove-then-add of same identity");
				}
			);
		}

		@Test
		void shouldKeepLatestContentWhenSubstitutedElementIsAddedAgain() {
			final Box oldA = new Box(1, "OLD-A");
			final Box v1 = new Box(1, "V1");
			final Box v2 = new Box(1, "V2");
			final Set<Box> underlying = new LinkedHashSet<>();
			underlying.add(oldA);
			final TransactionalSet<Box> set = new TransactionalSet<>(underlying);

			assertStateAfterCommit(
				set,
				original -> {
					original.add(v1);
					original.add(v2);
				},
				(original, committed) -> {
					assertEquals(1, committed.size());
					assertEquals("V2", committed.iterator().next().content(),
						"Committed set must hold the latest substituted content");
				}
			);
		}

		@Test
		void shouldRevertToOriginalContentWhenSubstitutionFollowedByOriginalAdd() {
			// regression guard: after substituting with new content and adding the original-content
			// instance again, the merged set must contain exactly one logical element and the change-layer
			// bookkeeping (createdKeys / removedKeys) must not double-count or drop the entry
			final Box oldA = new Box(1, "OLD-A");
			final Box newA = new Box(1, "NEW-A");
			final Box originalAgain = new Box(1, "OLD-A");
			final Box otherB = new Box(2, "B");
			final Set<Box> underlying = new LinkedHashSet<>();
			underlying.add(oldA);
			underlying.add(otherB);
			final TransactionalSet<Box> set = new TransactionalSet<>(underlying);

			assertStateAfterCommit(
				set,
				original -> {
					original.add(newA);
					original.add(originalAgain);
					assertEquals(2, original.size(),
						"Reverting a substitution must not change the logical size");
					assertTrue(original.contains(oldA), "Identity must remain present after revert");
					assertTrue(original.contains(otherB), "Unrelated elements must remain present");
				},
				(original, committed) -> {
					assertEquals(2, committed.size(),
						"Committed set must contain exactly one entry per identity");
					assertTrue(committed.contains(oldA), "Identity must survive the substitute-then-revert");
					assertTrue(committed.contains(otherB), "Unrelated elements must remain in the committed set");
				}
			);
		}

		@Test
		void shouldRemoveSubstitutedElementWithinSameTransaction() {
			final Box oldA = new Box(1, "OLD-A");
			final Box newA = new Box(1, "NEW-A");
			final Set<Box> underlying = new LinkedHashSet<>();
			underlying.add(oldA);
			final TransactionalSet<Box> set = new TransactionalSet<>(underlying);

			assertStateAfterCommit(
				set,
				original -> {
					original.add(newA);
					assertTrue(original.contains(newA), "Substituted element must be visible via contains()");
					original.remove(newA);
					assertFalse(original.contains(newA), "Removed substituted element must not be present");
				},
				(original, committed) -> assertTrue(committed.isEmpty(),
					"Committed set must be empty after substitute-then-remove")
			);
		}

		/**
		 * Identity-based record that mimics PriceRecordContract: equals/hashCode key only on
		 * {@link #id()} while {@link #content()} is logical payload that may change without changing
		 * identity. Implements {@link ContentComparator} so the change layer can detect content
		 * divergence and substitute the stale instance.
		 */
		record Box(int id, @Nonnull String content) implements ContentComparator<Box> {

			@Override
			public boolean equals(Object o) {
				if (this == o) return true;
				if (!(o instanceof Box other)) return false;
				return this.id == other.id;
			}

			@Override
			public int hashCode() {
				return Integer.hashCode(this.id);
			}

			@Override
			public boolean differsFrom(@Nullable Box other) {
				if (other == this) return false;
				if (other == null) return true;
				return this.id != other.id || !this.content.equals(other.content);
			}
		}

	}

}
