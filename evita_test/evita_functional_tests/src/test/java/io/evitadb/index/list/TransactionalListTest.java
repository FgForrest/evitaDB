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

package io.evitadb.index.list;

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TransactionalList} verifying the full contract of the transactional list
 * wrapper: isolation between transactional and non-transactional state, commit and rollback
 * semantics, the {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}
 * interface contract, all List API operations both inside and outside transactions, iterator
 * mutation behaviour, equals/hashCode, clone, and edge-case correctness.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2018
 */
@DisplayName("TransactionalList")
class TransactionalListTest implements TimeBoundedTestSupport {

	/** Underlying mutable list initialised to [1, 2] before each test. */
	private List<Integer> underlyingData;
	/** Transactional wrapper under test, backed by `underlyingData`. */
	private TransactionalList<Integer> tested;

	@BeforeEach
	void setUp() {
		this.underlyingData = new LinkedList<>();
		this.underlyingData.add(1);
		this.underlyingData.add(2);
		this.tested = new TransactionalList<>(this.underlyingData);
	}

	// -------------------------------------------------------------------------
	// Group 1: Construction and identity
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying construction behaviour and identity properties of {@link TransactionalList}.
	 */
	@Nested
	@DisplayName("Construction and identity")
	class ConstructionAndIdentityTest {

		@Test
		@DisplayName("genericClass() returns TransactionalList class token")
		void shouldReturnCorrectGenericClass() {
			final Class<TransactionalList<Integer>> cls = TransactionalList.genericClass();

			assertSame(TransactionalList.class, cls);
		}

		@Test
		@DisplayName("each instance receives a distinct id")
		void shouldAssignUniqueIdPerInstance() {
			final TransactionalList<Integer> first = new TransactionalList<>(new ArrayList<>());
			final TransactionalList<Integer> second = new TransactionalList<>(new ArrayList<>());

			// IDs are drawn from TransactionalObjectVersion.SEQUENCE which starts at Long.MIN_VALUE —
			// never assume > 0, only assert they differ
			assertNotEquals(first.getId(), second.getId());
		}

		@Test
		@DisplayName("toString() on non-empty list returns bracket-comma representation")
		void shouldProduceReadableToStringForNonEmptyList() {
			// tested is initialised to [1, 2] by setUp
			assertEquals("[1, 2]", TransactionalListTest.this.tested.toString());
		}

		@Test
		@DisplayName("toString() on empty list returns \"[]\"")
		void shouldProduceReadableToStringForEmptyList() {
			final TransactionalList<Integer> empty = new TransactionalList<>(new ArrayList<>());

			assertEquals("[]", empty.toString());
		}

		@Test
		@DisplayName("toString() inside a transaction reflects in-progress changes")
		void shouldProduceReadableToStringWithinTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					original.remove(Integer.valueOf(1));
					// during transaction the view must show [2, 3]
					assertEquals("[2, 3]", original.toString());
				},
				(original, committedVersion) -> assertListContains(committedVersion, 2, 3)
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 2: TransactionalLayerProducer contract
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying the {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}
	 * interface contract of {@link TransactionalList}.
	 */
	@Nested
	@DisplayName("TransactionalLayerProducer contract")
	class TransactionalLayerProducerContractTest {

		@Test
		@DisplayName("createLayer() returns ListChanges backed by the same delegate")
		void shouldCreateLayerWithDelegateContents() {
			// createLayer is normally called by the transaction infrastructure;
			// we call it directly to inspect the layer properties
			final ListChanges<Integer> layer = TransactionalListTest.this.tested.createLayer();

			assertSame(TransactionalListTest.this.underlyingData, layer.getListDelegate());
			assertTrue(layer.getRemovedItems().isEmpty(), "No removals expected in a fresh layer");
			assertTrue(layer.getAddedItems().isEmpty(), "No additions expected in a fresh layer");
		}

		@Test
		@DisplayName("createCopyWithMergedTransactionalMemory(null, maintainer) returns copy of delegate contents")
		void shouldReturnCopyWhenLayerIsNull() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);

			final List<Integer> copy =
				TransactionalListTest.this.tested.createCopyWithMergedTransactionalMemory(null, maintainer);

			// the result must equal the delegate but be a separate ArrayList object
			assertEquals(Arrays.asList(1, 2), copy);
			assertNotSame(TransactionalListTest.this.underlyingData, copy);
		}

		@Test
		@DisplayName("createCopyWithMergedTransactionalMemory with changes produces correct merged list")
		void shouldReturnMergedListWhenLayerHasChanges() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					original.remove(Integer.valueOf(1));
				},
				(original, committedVersion) -> {
					// original delegate unchanged, committed version reflects changes
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 2, 3);
				}
			);
		}

		@Test
		@DisplayName("removeLayer() delegates to TransactionalLayerMaintainer")
		void shouldRemoveLayerOnRequest() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);

			TransactionalListTest.this.tested.removeLayer(maintainer);

			// verify that the correct method was called exactly once on the maintainer
			Mockito.verify(maintainer, Mockito.times(1))
				.removeTransactionalMemoryLayerIfExists(TransactionalListTest.this.tested);
		}

	}

	// -------------------------------------------------------------------------
	// Group 3: Non-transactional operations (layer == null branch)
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying that all List operations executed outside a transaction modify
	 * the underlying delegate directly, without creating a transactional layer.
	 */
	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("add(element) appends to delegate directly")
		void shouldAddElementToDelegate() {
			TransactionalListTest.this.tested.add(99);

			assertListContains(TransactionalListTest.this.underlyingData, 1, 2, 99);
			assertListContains(TransactionalListTest.this.tested, 1, 2, 99);
		}

		@Test
		@DisplayName("add(index, element) inserts at position in delegate directly")
		void shouldAddElementAtIndexToDelegate() {
			TransactionalListTest.this.tested.add(1, 55);

			assertListContains(TransactionalListTest.this.underlyingData, 1, 55, 2);
			assertListContains(TransactionalListTest.this.tested, 1, 55, 2);
		}

		@Test
		@DisplayName("remove(Object) removes by value from delegate directly")
		void shouldRemoveByValueFromDelegate() {
			final boolean removed = TransactionalListTest.this.tested.remove(Integer.valueOf(1));

			assertTrue(removed);
			assertListContains(TransactionalListTest.this.underlyingData, 2);
			assertListContains(TransactionalListTest.this.tested, 2);
		}

		@Test
		@DisplayName("remove(int) removes by index from delegate directly")
		void shouldRemoveByIndexFromDelegate() {
			final Integer removed = TransactionalListTest.this.tested.remove(0);

			assertEquals(Integer.valueOf(1), removed);
			assertListContains(TransactionalListTest.this.underlyingData, 2);
			assertListContains(TransactionalListTest.this.tested, 2);
		}

		@Test
		@DisplayName("set(index, element) replaces element in delegate directly")
		void shouldSetElementInDelegate() {
			final Integer previous = TransactionalListTest.this.tested.set(0, 77);

			assertEquals(Integer.valueOf(1), previous);
			assertListContains(TransactionalListTest.this.underlyingData, 77, 2);
			assertListContains(TransactionalListTest.this.tested, 77, 2);
		}

		@Test
		@DisplayName("get(index) reads from delegate directly")
		void shouldGetElementFromDelegate() {
			assertEquals(Integer.valueOf(1), TransactionalListTest.this.tested.get(0));
			assertEquals(Integer.valueOf(2), TransactionalListTest.this.tested.get(1));
		}

		@Test
		@DisplayName("contains(element) delegates to underlying list")
		void shouldContainAddedElement() {
			assertTrue(TransactionalListTest.this.tested.contains(1));
			assertFalse(TransactionalListTest.this.tested.contains(99));
		}

		@Test
		@DisplayName("clear() empties the delegate directly")
		void shouldClearDelegate() {
			TransactionalListTest.this.underlyingData.clear();

			assertTrue(TransactionalListTest.this.tested.isEmpty());
			assertEquals(0, TransactionalListTest.this.tested.size());
		}

		@Test
		@DisplayName("addAll(collection) appends all elements to delegate directly")
		void shouldAddAllToDelegate() {
			TransactionalListTest.this.tested.addAll(Arrays.asList(3, 4, 5));

			assertListContains(TransactionalListTest.this.underlyingData, 1, 2, 3, 4, 5);
		}

		@Test
		@DisplayName("size() and isEmpty() reflect delegate size correctly")
		void shouldReportCorrectSizeAndIsEmpty() {
			assertEquals(2, TransactionalListTest.this.tested.size());
			assertFalse(TransactionalListTest.this.tested.isEmpty());

			TransactionalListTest.this.underlyingData.clear();

			assertEquals(0, TransactionalListTest.this.tested.size());
			assertTrue(TransactionalListTest.this.tested.isEmpty());
		}

		@Test
		@DisplayName("containsAll() returns true when all elements are present")
		void shouldReturnTrueForContainsAll() {
			assertTrue(TransactionalListTest.this.tested.containsAll(Arrays.asList(1, 2)));
			assertFalse(TransactionalListTest.this.tested.containsAll(Arrays.asList(1, 2, 3)));
		}

	}

	// -------------------------------------------------------------------------
	// Group 4: Transactional rollback
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying that transactional changes are discarded on rollback, leaving
	 * both the original delegate and the transactional view unchanged.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("add is discarded on rollback")
		void shouldDiscardAddOnRollback() {
			assertStateAfterRollback(
				TransactionalListTest.this.tested,
				original -> original.add(99),
				(original, committedVersion) -> {
					// after rollback committedVersion is null
					assertNull(committedVersion);
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
				}
			);
		}

		@Test
		@DisplayName("remove is discarded on rollback")
		void shouldDiscardRemoveOnRollback() {
			assertStateAfterRollback(
				TransactionalListTest.this.tested,
				original -> original.remove(Integer.valueOf(1)),
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
				}
			);
		}

		@Test
		@DisplayName("set is discarded on rollback")
		void shouldDiscardSetOnRollback() {
			assertStateAfterRollback(
				TransactionalListTest.this.tested,
				original -> original.set(0, 77),
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
				}
			);
		}

		@Test
		@DisplayName("clear is discarded on rollback")
		void shouldDiscardClearOnRollback() {
			assertStateAfterRollback(
				TransactionalListTest.this.tested,
				original -> {
					original.clear();
					assertTrue(original.isEmpty());
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
				}
			);
		}

		@Test
		@DisplayName("multiple operations are all discarded on rollback")
		void shouldDiscardMultipleOperationsOnRollback() {
			assertStateAfterRollback(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					original.remove(Integer.valueOf(1));
					original.set(0, 55);
					// visible inside the transaction
					assertListContains(original, 55, 3);
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
				}
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 5: Transactional commit — existing tests reorganised
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying that transactional changes are committed correctly, producing the
	 * expected new state in the committed copy while the original delegate remains unchanged.
	 */
	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("additions do not modify original state")
		void shouldNotModifyOriginalState() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					original.add(3);
					assertListContains(original, 1, 2, 3, 3);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 1, 2, 3, 3);
				}
			);
		}

		@Test
		@DisplayName("remove-then-insert leaves correct order")
		void shouldAppendAtEnd() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.remove(0);
					original.add(1, 3);
					original.add(1, 4);
					original.remove(1);
					assertListContains(original, 2, 3);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 2, 3);
				}
			);
		}

		@Test
		@DisplayName("multiple modifications on same index produce correct result")
		void shouldMakeModificationsOnSameIndex() {
			TransactionalListTest.this.tested.addAll(Arrays.asList(3, 4, 5, 6, 7, 8, 9, 10));
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.remove(1);
					original.add(5, 0);
					original.remove(2);
					original.remove(5);
					assertListContains(original, 1, 3, 5, 6, 0, 8, 9, 10);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
					assertListContains(committedVersion, 1, 3, 5, 6, 0, 8, 9, 10);
				}
			);
		}

		@Test
		@DisplayName("clear inside transaction commits to empty list")
		void shouldProcessClear() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(4);
					original.add(5);
					original.clear();
					assertListContains(original);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion);
				}
			);
		}

		@Test
		@DisplayName("clear then add inside transaction commits to added elements only")
		void shouldProcessClearAndThenAdd() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.clear();
					original.add(4);
					original.add(5);
					assertListContains(original, 4, 5);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 4, 5);
				}
			);
		}

		@Test
		@DisplayName("transactional add then remove produces correct committed copy")
		void shouldCreateTransactionalCopyWithRemove() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					original.add(4);
					original.remove(1);
					original.remove(0);
					assertListContains(original, 3, 4);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 3, 4);
				}
			);
		}

		@Test
		@DisplayName("multiple mixed modifications commit to correct state")
		void shouldReturnProperlyConstructedCopyOnMultipleModifyOperations() {
			TransactionalListTest.this.tested.add(5);
			TransactionalListTest.this.tested.add(2);
			TransactionalListTest.this.tested.add(9);
			TransactionalListTest.this.tested.add(0);
			TransactionalListTest.this.tested.add(4);

			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.remove(5);
					original.add(7);
					original.remove(1);
					original.remove(Integer.valueOf(4));
					original.remove(4);

					assertListContains(original, 1, 5, 2, 9);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2, 5, 2, 9, 0, 4);
					assertListContains(committedVersion, 1, 5, 2, 9);
				}
			);
		}

		@Test
		@DisplayName("remove by value does not modify original state")
		void removalsShouldNotModifyOriginalState() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.remove(Integer.valueOf(2));
					original.add(3);

					assertListContains(original, 1, 3);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 1, 3);
				}
			);
		}

		@Test
		@DisplayName("prepend then remove by index commits to correct state")
		void removalsShouldFailWhenPrepended() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(0, 4);
					original.add(0, 3);
					original.remove(3);

					assertListContains(TransactionalListTest.this.tested, 3, 4, 1);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 3, 4, 1);
				}
			);
		}

		@Test
		@DisplayName("remove all then add commits to added elements")
		void shouldMergeRemovalsAndUpdatesAndInsertionsOnTransactionCommit() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.remove(0);
					original.remove(0);
					original.add(3);
					original.add(3);

					assertListContains(TransactionalListTest.this.tested, 3, 3);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 3, 3);
				}
			);
		}

		@Test
		@DisplayName("adding several items at position zero inserts in reverse order")
		void shouldAddSeveralItemsOnASamePosition() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(0, 3);
					original.add(0, 4);

					assertListContains(TransactionalListTest.this.tested, 4, 3, 1, 2);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 4, 3, 1, 2);
				}
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 6: isEmpty and contains in transactions
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying that {@code isEmpty()} and collection-view operations work correctly
	 * within transactions as elements are added and removed.
	 */
	@Nested
	@DisplayName("isEmpty and contains in transactions")
	class IsEmptyAndContainsTest {

		@Test
		@DisplayName("isEmpty() tracks add/remove correctly inside transaction")
		void shouldInterpretIsEmptyCorrectly() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					assertFalse(original.isEmpty());

					original.add(3);
					assertFalse(original.isEmpty());

					original.remove(Integer.valueOf(1));
					assertFalse(original.isEmpty());

					original.remove(Integer.valueOf(3));
					assertFalse(original.isEmpty());

					original.remove(Integer.valueOf(2));
					assertTrue(original.isEmpty());

					original.add(4);
					assertFalse(original.isEmpty());

					original.remove(Integer.valueOf(4));
					assertTrue(original.isEmpty());
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion);
				}
			);
		}

		@Test
		@DisplayName("transactional view can be converted to a Set correctly")
		void shouldProduceValidValueCollection() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					original.remove(Integer.valueOf(2));

					final Set<Integer> result = new HashSet<>(TransactionalListTest.this.tested);
					assertEquals(2, result.size());
					assertTrue(result.contains(1));
					assertTrue(result.contains(3));
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 1, 3);
				}
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 7: Bulk operations in transactions
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying the bulk mutation methods ({@code addAll}, {@code removeAll},
	 * {@code retainAll}) behave correctly inside a transaction.
	 */
	@Nested
	@DisplayName("Bulk operations in transactions")
	class BulkOperationsInTransactionTest {

		@Test
		@DisplayName("addAll(collection) appends all elements transactionally")
		void shouldAddAllInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.addAll(Arrays.asList(3, 4, 5));
					assertListContains(original, 1, 2, 3, 4, 5);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 1, 2, 3, 4, 5);
				}
			);
		}

		@Test
		@DisplayName("addAll(index, collection) inserts all elements at given position")
		void shouldAddAllAtIndexInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.addAll(1, Arrays.asList(10, 20));
					assertListContains(original, 1, 10, 20, 2);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 1, 10, 20, 2);
				}
			);
		}

		@Test
		@DisplayName("addAll(empty collection) returns false and leaves list unchanged")
		void shouldAddAllEmptyCollectionInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					final boolean modified = original.addAll(Collections.emptyList());
					assertFalse(modified);
					assertListContains(original, 1, 2);
				},
				(original, committedVersion) -> {
					assertListContains(committedVersion, 1, 2);
				}
			);
		}

		@Test
		@DisplayName("removeAll(collection) removes all matching elements transactionally")
		void shouldRemoveAllInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					final boolean modified = original.removeAll(Arrays.asList(1, 3));
					assertTrue(modified);
					assertListContains(original, 2);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 2);
				}
			);
		}

		@Test
		@DisplayName("retainAll(collection) keeps only matching elements transactionally")
		void shouldRetainAllInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					final boolean modified = original.retainAll(Arrays.asList(2, 3));
					assertTrue(modified);
					assertListContains(original, 2, 3);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 2, 3);
				}
			);
		}

		@Test
		@DisplayName("containsAll() returns true when all queried elements are in transactional view")
		void shouldReturnTrueForContainsAllInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					assertTrue(original.containsAll(Arrays.asList(1, 2, 3)));
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2, 3)
			);
		}

		@Test
		@DisplayName("containsAll() returns false when any queried element is absent from transactional view")
		void shouldReturnFalseForContainsAllWhenElementMissingInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.remove(Integer.valueOf(1));
					assertFalse(original.containsAll(Arrays.asList(1, 2)));
				},
				(original, committedVersion) -> assertListContains(committedVersion, 2)
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 8: Index query operations in transactions
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying index-based query operations ({@code indexOf}, {@code lastIndexOf},
	 * {@code toArray}, {@code subList}) inside a transaction.
	 */
	@Nested
	@DisplayName("Index query operations in transactions")
	class IndexQueryOperationsInTransactionTest {

		@Test
		@DisplayName("indexOf() returns correct position for present element")
		void shouldFindIndexOfPresentElement() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					assertEquals(1, original.indexOf(2));
					assertEquals(0, original.indexOf(1));
					assertEquals(2, original.indexOf(3));
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2, 3)
			);
		}

		@Test
		@DisplayName("indexOf() returns -1 for absent element")
		void shouldReturnMinusOneForAbsentElement() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> assertEquals(-1, original.indexOf(99)),
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2)
			);
		}

		@Test
		@DisplayName("lastIndexOf() returns last occurrence when duplicates exist")
		void shouldFindLastIndexOfWithDuplicates() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(1); // list is now [1, 2, 1]
					assertEquals(2, original.lastIndexOf(1));
					assertEquals(1, original.lastIndexOf(2));
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2, 1)
			);
		}

		@Test
		@DisplayName("lastIndexOf() returns -1 for absent element")
		void shouldReturnMinusOneForAbsentLastIndex() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> assertEquals(-1, original.lastIndexOf(99)),
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2)
			);
		}

		@Test
		@DisplayName("toArray() returns object array with transactional view contents")
		void shouldReturnObjectArrayOfTransactionalView() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					final Object[] arr = original.toArray();
					assertArrayEquals(new Object[]{1, 2, 3}, arr);
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2, 3)
			);
		}

		@Test
		@DisplayName("toArray(T[]) returns typed array with transactional view contents")
		void shouldReturnTypedArrayOfTransactionalView() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					final Integer[] arr = original.toArray(new Integer[0]);
					assertArrayEquals(new Integer[]{1, 2, 3}, arr);
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2, 3)
			);
		}

		@Test
		@DisplayName("subList() returns correct full-range view within a transaction")
		void shouldReturnSubListOfTransactionalView() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					original.add(4);
					final List<Integer> sub = original.subList(0, 4);
					assertEquals(4, sub.size());
					assertEquals(Integer.valueOf(1), sub.get(0));
					assertEquals(Integer.valueOf(2), sub.get(1));
					assertEquals(Integer.valueOf(3), sub.get(2));
					assertEquals(Integer.valueOf(4), sub.get(3));
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2, 3, 4)
			);
		}

		@Test
		@DisplayName("subList() delegates to underlying list outside a transaction")
		void shouldDelegateSubListOutsideTransaction() {
			// without a transaction, subList delegates to listDelegate which has a correct impl
			TransactionalListTest.this.underlyingData.add(3);
			TransactionalListTest.this.underlyingData.add(4);

			final List<Integer> sub = TransactionalListTest.this.tested.subList(1, 3);

			assertEquals(2, sub.size());
			assertEquals(Integer.valueOf(2), sub.get(0));
			assertEquals(Integer.valueOf(3), sub.get(1));
		}

		@Test
		@DisplayName("subList() on empty range starting at zero returns empty list")
		void shouldReturnEmptySubListForEmptyRange() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					final List<Integer> sub = original.subList(0, 0);
					assertTrue(sub.isEmpty());
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2)
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 9: Iterator contract in transactions
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying that the transactional iterator and list-iterator honour the
	 * standard {@link Iterator}/{@link ListIterator} contracts including removal,
	 * {@code hasNext()}, {@code hasPrevious()}, and the {@link NoSuchElementException}
	 * boundary condition.
	 */
	@Nested
	@DisplayName("Iterator contract in transactions")
	class IteratorContractTest {

		@Test
		@DisplayName("iterator removal does not modify original state")
		void shouldNotModifyOriginalStateOnIteratorRemoval() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);

					final Iterator<Integer> it = original.iterator();
					final Integer first = it.next();
					final Integer second = it.next();
					it.remove();
					assertTrue(it.hasNext());
					final Integer third = it.next();

					assertListContains(TransactionalListTest.this.tested, 1, 3);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 1, 3);
				}
			);
		}

		@Test
		@DisplayName("iterator removal of specific element commits correct state")
		void shouldMergeChangesInKeySetIterator() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);

					final Iterator<Integer> it = original.iterator();
					it.next();
					final Integer removed = it.next();
					it.remove();

					assertEquals(Integer.valueOf(2), removed);

					assertListContains(TransactionalListTest.this.tested, 1, 3);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 1, 3);
				}
			);
		}

		@Test
		@DisplayName("iterator hasNext() is idempotent and next() throws when exhausted")
		void shouldKeepIteratorContract() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);

					final List<Integer> result = new ArrayList<>(3);

					final Iterator<Integer> it = original.iterator();
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

					assertEquals(new HashSet<>(Arrays.asList(1, 2, 3)), new HashSet<>(result));

					try {
						it.next();
						fail("Exception expected!");
					} catch (NoSuchElementException ex) {
						//ok
					}
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 1, 2, 3);
				}
			);
		}

		@Test
		@DisplayName("iterator contract holds after transactional removals")
		void shouldKeepIteratorContractWhenItemsRemoved() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					original.remove(1);

					final List<Integer> result = new ArrayList<>(3);

					final Iterator<Integer> it = original.iterator();
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

					assertEquals(new HashSet<>(Arrays.asList(1, 3)), new HashSet<>(result));

					try {
						it.next();
						fail("Exception expected!");
					} catch (NoSuchElementException ex) {
						//ok
					}
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 1, 3);
				}
			);
		}

		@Test
		@DisplayName("ListIterator forward and backward traversal matches expected sequence after removals")
		void shouldKeepListIteratorContractWhenItemsRemoved() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					original.add(4);
					original.remove(1);
					original.remove(2);

					final ListIterator<Integer> it = original.listIterator();
					assertEquals(Integer.valueOf(1), it.next());
					assertEquals(Integer.valueOf(3), it.next());

					try {
						it.next();
						fail("Exception expected!");
					} catch (NoSuchElementException ex) {
						//ok
					}

					assertEquals(Integer.valueOf(3), it.previous());
					assertEquals(Integer.valueOf(1), it.previous());

					try {
						it.previous();
						fail("Exception expected!");
					} catch (NoSuchElementException ex) {
						//ok
					}

					assertListContains(original, 1, 3);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 1, 3);
				}
			);
		}

		@Test
		@DisplayName("ListIterator bidirectional traversal is correct after sets on two items")
		void shouldProperlyReturnLastItemWhenTwoRemovals() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.set(0, 99);
					original.add(3);
					original.add(4);
					original.add(5);
					original.set(2, 13);
					original.set(3, 14);

					final ListIterator<Integer> it = original.listIterator();
					assertEquals(Integer.valueOf(99), it.next());
					assertEquals(Integer.valueOf(2), it.next());
					assertEquals(Integer.valueOf(13), it.next());
					assertEquals(Integer.valueOf(14), it.next());
					assertEquals(Integer.valueOf(5), it.next());
					assertEquals(Integer.valueOf(5), it.previous());
					assertEquals(Integer.valueOf(14), it.previous());
					assertEquals(Integer.valueOf(13), it.previous());
					assertEquals(Integer.valueOf(2), it.previous());
					assertEquals(Integer.valueOf(99), it.previous());
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 99, 2, 13, 14, 5);
				}
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 10: ListIterator mutation in transactions
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying that {@link ListIterator} mutation methods ({@code set}, {@code add})
	 * work correctly inside a transaction and that precondition violations throw the right
	 * exception.
	 */
	@Nested
	@DisplayName("ListIterator mutation in transactions")
	class ListIteratorMutationInTransactionTest {

		@Test
		@DisplayName("set() via ListIterator replaces current element in transactional view")
		void shouldSetViaListIterator() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					// A write operation must precede listIterator() to ensure the transactional layer is
					// created — listIterator() returns a delegate iterator when no layer exists yet, which
					// would bypass the transaction and mutate underlyingData directly.
					original.add(3); // forces layer creation; list is now [1, 2, 3] in transaction
					original.remove(Integer.valueOf(3)); // undo to restore [1, 2]

					final ListIterator<Integer> it = original.listIterator();
					it.next(); // advances past element at index 0 (value 1)
					it.set(99); // replaces element at index 0 with 99
					assertListContains(original, 99, 2);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 99, 2);
				}
			);
		}

		@Test
		@DisplayName("add() via ListIterator inserts element at current position")
		void shouldAddViaListIterator() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					// A write operation must precede listIterator() to ensure the transactional layer is
					// created — listIterator() returns a delegate iterator when no layer exists yet, which
					// would bypass the transaction and mutate underlyingData directly.
					original.add(3); // forces layer creation; list is now [1, 2, 3] in transaction
					original.remove(Integer.valueOf(3)); // undo; back to [1, 2]

					final ListIterator<Integer> it = original.listIterator();
					it.next(); // move past first element (value 1)
					it.add(55); // inserts 55 at current position (index 1)
					assertListContains(original, 1, 55, 2);
				},
				(original, committedVersion) -> {
					assertListContains(TransactionalListTest.this.underlyingData, 1, 2);
					assertListContains(committedVersion, 1, 55, 2);
				}
			);
		}

		@Test
		@DisplayName("nextIndex() and previousIndex() track position correctly")
		void shouldTrackNextIndexAndPreviousIndex() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					final ListIterator<Integer> it = original.listIterator();
					assertEquals(0, it.nextIndex());
					assertEquals(-1, it.previousIndex());

					it.next();
					assertEquals(1, it.nextIndex());
					assertEquals(0, it.previousIndex());

					it.next();
					assertEquals(2, it.nextIndex());
					assertEquals(1, it.previousIndex());
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2)
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 11: Equals and hashCode
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying that {@link TransactionalList#equals(Object)} and
	 * {@link TransactionalList#hashCode()} conform to the standard List contract.
	 */
	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("equals returns true for two lists with same contents")
		void shouldBeEqualToListWithSameContents() {
			final List<Integer> other = Arrays.asList(1, 2);

			assertEquals(TransactionalListTest.this.tested, other);
		}

		@Test
		@DisplayName("equals returns false for lists with different contents")
		void shouldNotBeEqualToListWithDifferentContents() {
			final List<Integer> other = Arrays.asList(1, 3);

			assertNotEquals(TransactionalListTest.this.tested, other);
		}

		@Test
		@DisplayName("equals returns false for non-List objects")
		void shouldNotBeEqualToNonListObject() {
			assertNotEquals(TransactionalListTest.this.tested, "not a list");
			assertNotEquals(TransactionalListTest.this.tested, Integer.valueOf(1));
		}

		@Test
		@DisplayName("equals returns true when compared to itself")
		void shouldBeEqualToItself() {
			assertEquals(TransactionalListTest.this.tested, TransactionalListTest.this.tested);
		}

		@Test
		@DisplayName("hashCode is consistent across multiple calls")
		void shouldHaveConsistentHashCode() {
			final int first = TransactionalListTest.this.tested.hashCode();
			final int second = TransactionalListTest.this.tested.hashCode();

			assertEquals(first, second);
		}

	}

	// -------------------------------------------------------------------------
	// Group 12: Clone
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying the {@link TransactionalList#clone()} behaviour both inside
	 * and outside a transaction.
	 */
	@Nested
	@DisplayName("Clone")
	class CloneTest {

		@Test
		@DisplayName("clone() outside transaction produces independent list with same contents")
		void shouldCloneOutsideTransaction() throws CloneNotSupportedException {
			@SuppressWarnings("unchecked")
			final TransactionalList<Integer> cloned =
				(TransactionalList<Integer>) TransactionalListTest.this.tested.clone();

			// same observable contents
			assertListContains(cloned, 1, 2);
			// but different object identity
			assertNotSame(TransactionalListTest.this.tested, cloned);
		}

		@Test
		@DisplayName("clone() inside transaction copies transactional state to the clone")
		void shouldCloneInTransactionCopiesState() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					original.remove(Integer.valueOf(1));
					// the in-transaction view is [2, 3]
					assertListContains(original, 2, 3);

					try {
						@SuppressWarnings("unchecked")
						final TransactionalList<Integer> cloned =
							(TransactionalList<Integer>) original.clone();
						// clone must have the same in-transaction view
						assertListContains(cloned, 2, 3);
					} catch (CloneNotSupportedException ex) {
						fail("Clone should be supported: " + ex.getMessage());
					}
				},
				(original, committedVersion) -> assertListContains(committedVersion, 2, 3)
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 13: Edge cases
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying boundary conditions and edge-case behaviour of {@link TransactionalList}.
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCasesTest {

		@Test
		@DisplayName("add(index) beyond size throws IndexOutOfBoundsException in transaction")
		void shouldThrowWhenAddingBeyondSizeInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> assertThrows(
					IndexOutOfBoundsException.class,
					() -> original.add(99, 42)
				),
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2)
			);
		}

		@Test
		@DisplayName("remove(index) beyond size throws IndexOutOfBoundsException in transaction")
		void shouldThrowWhenRemovingBeyondSizeInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> assertThrows(
					IndexOutOfBoundsException.class,
					() -> original.remove(99)
				),
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2)
			);
		}

		@Test
		@DisplayName("operations on initially empty list work correctly in transaction")
		void shouldHandleEmptyListOperationsInTransaction() {
			final TransactionalList<Integer> empty = new TransactionalList<>(new ArrayList<>());

			assertStateAfterCommit(
				empty,
				original -> {
					assertTrue(original.isEmpty());
					assertEquals(0, original.size());
					assertFalse(original.contains(1));

					original.add(10);
					assertFalse(original.isEmpty());
					assertEquals(1, original.size());
				},
				(original, committedVersion) -> {
					assertEquals(1, committedVersion.size());
					assertEquals(Integer.valueOf(10), committedVersion.get(0));
				}
			);
		}

		@Test
		@DisplayName("single-element list add and remove work correctly in transaction")
		void shouldHandleSingleElementListInTransaction() {
			final List<Integer> delegate = new ArrayList<>();
			delegate.add(42);
			final TransactionalList<Integer> single = new TransactionalList<>(delegate);

			assertStateAfterCommit(
				single,
				original -> {
					assertEquals(1, original.size());
					assertEquals(Integer.valueOf(42), original.get(0));
					original.remove(Integer.valueOf(42));
					assertTrue(original.isEmpty());
					original.add(99);
					assertListContains(original, 99);
				},
				(original, committedVersion) -> {
					assertEquals(1, committedVersion.size());
					assertEquals(Integer.valueOf(99), committedVersion.get(0));
				}
			);
		}

		@Test
		@DisplayName("exhausted transactional iterator throws NoSuchElementException")
		void shouldThrowNoSuchElementExceptionOnExhaustedIterator() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					final Iterator<Integer> it = original.iterator();
					it.next();
					it.next();
					assertFalse(it.hasNext());

					assertThrows(NoSuchElementException.class, it::next);
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2)
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 14: Regression tests
	// -------------------------------------------------------------------------

	/**
	 * Regression tests capturing specific input sequences that previously exposed bugs
	 * in the transactional list merge logic.
	 */
	@Nested
	@DisplayName("Regression tests")
	class RegressionTests {

		@Test
		@DisplayName("regression case 1: mixed add-at-index and remove-by-value sequence")
		void verify() {
			final List<Integer> base = Arrays.asList(18, 18, 1, 5, 5, 18, 18, 10, 19, 5, 9, 1, 13, 11, 14);
			final List<Integer> verify = new ArrayList<>(base);
			TransactionalListTest.this.tested = new TransactionalList<>(base);
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(4, 3);
					verify.add(4, 3);
					original.add(14, 10);
					verify.add(14, 10);
					original.remove(Integer.valueOf(1));
					verify.remove(Integer.valueOf(1));
					original.remove(Integer.valueOf(5));
					verify.remove(Integer.valueOf(5));

					assertListContains(original, verify.stream().mapToInt(it -> it).toArray());
				},
				(original, committedVersion) ->
					assertListContains(committedVersion, verify.stream().mapToInt(it -> it).toArray())
			);
		}

		@Test
		@DisplayName("regression case 2: remove-by-index then add-at-index then remove-by-value")
		void verify2() {
			final List<Integer> base =
				Arrays.asList(0, 23, 26, 12, 21, 30, 9, 36, 0, 3, 21, 1, 22, 22, 19, 7, 27, 25, 22, 8);
			final List<Integer> verify = new ArrayList<>(base);
			TransactionalListTest.this.tested = new TransactionalList<>(base);
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.remove(1);
					verify.remove(1);
					original.add(14, 30);
					verify.add(14, 30);
					original.remove(Integer.valueOf(22));
					verify.remove(Integer.valueOf(22));
					original.remove(Integer.valueOf(7));
					verify.remove(Integer.valueOf(7));

					assertListContains(original, verify.stream().mapToInt(it -> it).toArray());
				},
				(original, committedVersion) ->
					assertListContains(committedVersion, verify.stream().mapToInt(it -> it).toArray())
			);
		}

		@Test
		@DisplayName("regression case 3: add-at-index and remove-by-value on 14-element list")
		void verify3() {
			final List<Integer> base = Arrays.asList(31, 14, 2, 4, 10, 2, 13, 12, 39, 15, 26, 11, 21, 31);
			final List<Integer> verify = new ArrayList<>(base);
			TransactionalListTest.this.tested = new TransactionalList<>(base);
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(4, 33);
					verify.add(4, 33);
					original.remove(Integer.valueOf(10));
					verify.remove(Integer.valueOf(10));

					assertListContains(original, verify.stream().mapToInt(it -> it).toArray());
				},
				(original, committedVersion) ->
					assertListContains(committedVersion, verify.stream().mapToInt(it -> it).toArray())
			);
		}

		@Test
		@DisplayName("regression case 4: remove-by-index, add-at-index, add-at-end, remove-by-value")
		void verify4() {
			final List<Integer> base =
				Arrays.asList(25, 32, 3, 17, 21, 9, 34, 29, 13, 6, 4, 3, 15, 38, 0, 28, 13, 22, 10);
			final List<Integer> verify = new ArrayList<>(base);
			TransactionalListTest.this.tested = new TransactionalList<>(base);
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.remove(5);
					verify.remove(5);
					original.add(15, 16);
					verify.add(15, 16);
					original.add(35);
					verify.add(35);
					original.remove(Integer.valueOf(6));
					verify.remove(Integer.valueOf(6));

					assertListContains(original, verify.stream().mapToInt(it -> it).toArray());
				},
				(original, committedVersion) ->
					assertListContains(committedVersion, verify.stream().mapToInt(it -> it).toArray())
			);
		}

		@Test
		@DisplayName("regression case 5: interleaved add-at-index, remove-by-index, and remove-by-value")
		void verify5() {
			final List<Integer> base =
				Arrays.asList(15, 5, 11, 2, 36, 11, 31, 1, 23, 37, 4, 3, 7, 18, 6, 8, 32, 29, 9);
			final List<Integer> verify = new ArrayList<>(base);
			TransactionalListTest.this.tested = new TransactionalList<>(base);
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(12, 38);
					verify.add(12, 38);
					original.add(2, 23);
					verify.add(2, 23);
					original.remove(6);
					verify.remove(6);
					original.remove(Integer.valueOf(7));
					verify.remove(Integer.valueOf(7));

					assertListContains(original, verify.stream().mapToInt(it -> it).toArray());
				},
				(original, committedVersion) ->
					assertListContains(committedVersion, verify.stream().mapToInt(it -> it).toArray())
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 15: Bug-fix regression tests
	// -------------------------------------------------------------------------

	/**
	 * Tests written to reproduce specific bugs and guard against regressions.
	 */
	@Nested
	@DisplayName("Bug-fix regression tests")
	class BugFixTests {

		/**
		 * BUG-1: subList() in the transactional branch never called it.next() for elements before fromIndex,
		 * so the iterator never advanced and the method looped infinitely when toIndex < size().
		 */
		@Test
		@Timeout(value = 5, unit = TimeUnit.SECONDS)
		@DisplayName("subList(1,3) on [1,2,3,4] returns [2,3] without hanging (BUG-1)")
		void shouldReturnCorrectSubListFromMiddleInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3);
					original.add(4);
					// list is now [1, 2, 3, 4]; subList(1, 3) must return [2, 3]
					final List<Integer> sub = original.subList(1, 3);
					assertEquals(2, sub.size());
					assertEquals(Integer.valueOf(2), sub.get(0));
					assertEquals(Integer.valueOf(3), sub.get(1));
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2, 3, 4)
			);
		}

		/**
		 * BUG-2: ListChanges.remove(int) used `index > size()` instead of `index >= size()`,
		 * allowing remove(size()) to pass the guard and produce undefined behaviour instead of
		 * the required IndexOutOfBoundsException.
		 */
		@Test
		@DisplayName("remove(size()) throws IndexOutOfBoundsException in transaction (BUG-2)")
		void shouldThrowIndexOutOfBoundsWhenRemovingAtSizeInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					final int sz = original.size(); // == 2
					assertThrows(
						IndexOutOfBoundsException.class,
						() -> original.remove(sz)
					);
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2)
			);
		}

		/**
		 * BUG-3: ListChanges.get(int) fell through to `return null` for out-of-bounds indexes
		 * instead of throwing IndexOutOfBoundsException as required by the List contract.
		 */
		@Test
		@DisplayName("get(size()) throws IndexOutOfBoundsException in transaction (BUG-3)")
		void shouldThrowIndexOutOfBoundsForGetBeyondSizeInTransaction() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					final int sz = original.size(); // == 2
					assertThrows(
						IndexOutOfBoundsException.class,
						() -> original.get(sz)
					);
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2)
			);
		}

		/**
		 * BUG-4: previous() stored the pre-decrement value in previousPosition, so a subsequent
		 * remove() used the wrong index and removed the wrong element.
		 */
		@Test
		@DisplayName("remove() after previous() removes the element returned by previous() (BUG-4)")
		void shouldCorrectlyRemoveElementReturnedByPrevious() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					original.add(3); // list is now [1, 2, 3]

					final ListIterator<Integer> it = original.listIterator(3); // start at end
					final Integer last = it.previous(); // returns element at index 2, i.e. 3
					assertEquals(Integer.valueOf(3), last);
					it.remove(); // must remove index 2 (value 3), not some other position

					assertListContains(original, 1, 2);
				},
				(original, committedVersion) -> assertListContains(committedVersion, 1, 2)
			);
		}

		/**
		 * BUG-5: After remove() the iterator did not reset previousPosition to -1, so a second
		 * consecutive remove() was silently allowed and removed another element.
		 * The JDK ListIterator contract requires IllegalStateException on double-remove.
		 */
		@Test
		@DisplayName("second consecutive remove() on ListIterator throws (BUG-5)")
		void shouldThrowOnDoubleRemoveFromListIterator() {
			assertStateAfterCommit(
				TransactionalListTest.this.tested,
				original -> {
					// force layer creation so we get the transactional iterator, not the delegate iterator
					original.add(99);
					original.remove(Integer.valueOf(99));

					final ListIterator<Integer> it = original.listIterator();
					it.next(); // advance past element 1
					it.remove(); // first remove is valid — removes element 1

					// second remove without intervening next/previous must throw
					assertThrows(
						Exception.class,
						it::remove
					);
				},
				(original, committedVersion) -> {
					// only one element should have been removed
					assertEquals(1, committedVersion.size());
					assertListContains(committedVersion, 2);
				}
			);
		}

	}

	// -------------------------------------------------------------------------
	// Group 16: Generational proof test
	// -------------------------------------------------------------------------

	/**
	 * Tests verifying long-running randomised operation sequences remain consistent
	 * between the transactional list and a plain ArrayList reference implementation.
	 */
	@Nested
	@DisplayName("Generational proof")
	class GenerationalProofTest {

		@ParameterizedTest(name = "TransactionalList should survive generational randomized test applying modifications on it")
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		void generationalProofTest(GenerationalTestInput input) {
			final int initialCount = 20;
			final List<Integer> initialState =
				generateRandomInitialArray(new Random(input.randomSeed()), initialCount);

			runFor(
				input,
				10_000,
				new TestState(
					new StringBuilder(),
					initialState
				),
				(random, testState) -> {
					final List<Integer> referenceList = new ArrayList<>(testState.initialState);
					final TransactionalList<Integer> transactionalList =
						new TransactionalList<>(testState.initialState());

					final StringBuilder codeBuffer = testState.code();
					codeBuffer.append("\nSTART: ")
						.append(transactionalList.stream().map(Object::toString).collect(Collectors.joining(",")))
						.append("\n");

					assertStateAfterCommit(
						transactionalList,
						original -> {

							final int operationsInTransaction = random.nextInt(5);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = transactionalList.size();
								assertEquals(referenceList.size(), length);
								final int operation = random.nextInt(3);
								if ((operation == 0 || length < 10) && length < 120) {
									if (random.nextBoolean()) {
										// insert new item at the end
										final Integer newRecId = random.nextInt(initialCount * 2);
										transactionalList.add(newRecId);
										referenceList.add(newRecId);
										codeBuffer.append("+").append(newRecId);
									} else if (length > 0) {
										// insert new item in the middle
										final int addIndex = random.nextInt(length - 1);
										final Integer newRecId = random.nextInt(initialCount * 2);
										transactionalList.add(addIndex, newRecId);
										referenceList.add(addIndex, newRecId);
										codeBuffer.append("++(").append(addIndex).append(")").append(newRecId);
									}
								} else if (operation == 1) {
									if (random.nextBoolean()) {
										// remove existing item by index
										final int removeIndex = random.nextInt(length);
										codeBuffer.append("-").append(removeIndex);
										transactionalList.remove(removeIndex);
										referenceList.remove(removeIndex);
									} else {
										// remove existing item by value
										final Integer removedRecId = transactionalList.get(random.nextInt(length));
										transactionalList.remove(removedRecId);
										referenceList.remove(removedRecId);
										codeBuffer.append("--").append(removedRecId);
									}
								} else {
									// update existing item by index
									final int updateIndex = random.nextInt(length);
									final Integer updatedValue = random.nextInt(initialCount * 2);
									codeBuffer.append("!").append(updateIndex);
									transactionalList.set(updateIndex, updatedValue);
									referenceList.set(updateIndex, updatedValue);
								}
							}
							codeBuffer.append("\n");
						},
						(original, committed) ->
							assertListContains(committed, referenceList.stream().mapToInt(it -> it).toArray())
					);

					return new TestState(
						new StringBuilder(),
						referenceList
					);
				}
			);
		}

	}

	// =========================================================================
	// Shared helper methods
	// =========================================================================

	/**
	 * Generates a random list of `count` integers in the range `[0, count * 2)`.
	 *
	 * @param rnd   random source — must be non-null
	 * @param count number of elements to generate
	 * @return a new mutable list of random integers
	 */
	@Nonnull
	private List<Integer> generateRandomInitialArray(@Nonnull Random rnd, int count) {
		final List<Integer> initialArray = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			final int recId = rnd.nextInt(count * 2);
			initialArray.add(recId);
		}
		return initialArray;
	}

	/**
	 * Asserts that `list` contains exactly `recordIds` (in order) and that all standard
	 * List access methods agree: size, element-by-index, contains, forward and backward
	 * ListIterator traversal, and isEmpty.
	 *
	 * @param list      the list to verify — must be non-null
	 * @param recordIds expected contents in order
	 */
	private void assertListContains(@Nonnull List<Integer> list, int... recordIds) {
		final String errorMessage = "\nExpected: " + Arrays.toString(recordIds) +
			"\nActual:   [" + list.stream().map(Object::toString).collect(Collectors.joining(", ")) + "]";

		assertEquals(recordIds.length, list.size(), errorMessage);
		assertArrayEquals(
			Arrays.stream(recordIds).boxed().toArray(Integer[]::new),
			list.toArray(new Integer[0]),
			errorMessage
		);

		if (recordIds.length == 0) {
			assertTrue(list.isEmpty(), errorMessage);
		} else {
			assertFalse(list.isEmpty(), errorMessage);
		}

		for (int i = 0; i < recordIds.length; i++) {
			final int recordId = recordIds[i];
			assertEquals(Integer.valueOf(recordId), list.get(i), errorMessage);
		}

		for (int recordId : recordIds) {
			assertTrue(list.contains(recordId), errorMessage);
		}

		int index = 0;
		final ListIterator<Integer> it = list.listIterator();
		while (it.hasNext()) {
			final Integer nextRecord = it.next();
			assertEquals(recordIds[index++], nextRecord, errorMessage);
		}
		assertEquals(recordIds.length, index, errorMessage);

		while (it.hasPrevious()) {
			final Integer prevRecord = it.previous();
			assertEquals(recordIds[--index], prevRecord, errorMessage);
		}
		assertEquals(0, index, errorMessage);
	}

	/**
	 * Immutable state carrier for the generational proof test, holding the code buffer
	 * used to reproduce a failing sequence and the current list state.
	 *
	 * @param code         accumulated operation log for debugging
	 * @param initialState current list state that becomes the next iteration's delegate
	 */
	private record TestState(
		StringBuilder code,
		List<Integer> initialState
	) {}

}
