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

package io.evitadb.index.bitmap;

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;
import org.roaringbitmap.RoaringBitmap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link TransactionalBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("TransactionalBitmap")
class TransactionalBitmapTest implements TimeBoundedTestSupport {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create empty bitmap")
		void shouldCreateEmptyBitmap() {
			final TransactionalBitmap bitmap = new TransactionalBitmap();
			assertTrue(bitmap.isEmpty());
			assertEquals(0, bitmap.size());
		}

		@Test
		@DisplayName("should create bitmap from varargs")
		void shouldCreateBitmapFromVarargs() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(5, 3, 8, 1);
			assertEquals(4, bitmap.size());
			assertArrayEquals(new int[]{1, 3, 5, 8}, bitmap.getArray());
		}

		@Test
		@DisplayName("should create bitmap from Bitmap copy")
		void shouldCreateBitmapFromBitmapCopy() {
			final BaseBitmap original = new BaseBitmap(1, 2, 3);
			final TransactionalBitmap bitmap = new TransactionalBitmap(original);
			assertEquals(3, bitmap.size());
			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray());
			// modifying the copy should not affect the original
			bitmap.add(4);
			assertEquals(3, original.size());
		}

		@Test
		@DisplayName("should create bitmap from non-RoaringBitmap implementation")
		void shouldCreateBitmapFromNonRoaringBitmap() {
			// ArrayBitmap does not implement RoaringBitmapBackedBitmap,
			// so this exercises the else branch in the Bitmap constructor
			final ArrayBitmap source = new ArrayBitmap(1, 2, 3);
			final TransactionalBitmap bitmap = new TransactionalBitmap(source);

			assertEquals(3, bitmap.size());
			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray());
			// verify independence from original
			source.add(4);
			assertEquals(3, bitmap.size());
		}
	}

	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("should add element without transaction")
		void shouldAddElementWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			assertTrue(bitmap.add(3));
			assertArrayEquals(new int[]{1, 3, 5, 10}, bitmap.getArray());
		}

		@Test
		@DisplayName("should return false on add duplicate without transaction")
		void shouldReturnFalseOnAddDuplicateWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			assertFalse(bitmap.add(5));
		}

		@Test
		@DisplayName("should remove element without transaction")
		void shouldRemoveElementWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			assertTrue(bitmap.remove(5));
			assertArrayEquals(new int[]{1, 10}, bitmap.getArray());
		}

		@Test
		@DisplayName("should return false on remove non-existing without transaction")
		void shouldReturnFalseOnRemoveNonExistingWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			assertFalse(bitmap.remove(99));
		}

		@Test
		@DisplayName("should contain element without transaction")
		void shouldContainElementWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			assertTrue(bitmap.contains(5));
			assertFalse(bitmap.contains(3));
		}

		@Test
		@DisplayName("should return indexOf without transaction")
		void shouldReturnIndexOfWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			assertEquals(0, bitmap.indexOf(1));
			assertEquals(1, bitmap.indexOf(5));
			assertEquals(2, bitmap.indexOf(10));
			assertTrue(bitmap.indexOf(3) < 0);
		}

		@Test
		@DisplayName("should return element by get without transaction")
		void shouldReturnElementByGetWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			assertEquals(1, bitmap.get(0));
			assertEquals(5, bitmap.get(1));
			assertEquals(10, bitmap.get(2));
		}

		@Test
		@DisplayName("should return getRange without transaction")
		void shouldReturnGetRangeWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10, 15, 20);
			assertArrayEquals(new int[]{5, 10, 15}, bitmap.getRange(1, 4));
		}

		@Test
		@DisplayName("should return getFirst and getLast without transaction")
		void shouldReturnGetFirstAndGetLastWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			assertEquals(1, bitmap.getFirst());
			assertEquals(10, bitmap.getLast());
		}

		@Test
		@DisplayName("should return isEmpty and size without transaction")
		void shouldReturnIsEmptyAndSizeWithoutTransaction() {
			final TransactionalBitmap empty = new TransactionalBitmap();
			assertTrue(empty.isEmpty());
			assertEquals(0, empty.size());

			final TransactionalBitmap nonEmpty = new TransactionalBitmap(1, 2, 3);
			assertFalse(nonEmpty.isEmpty());
			assertEquals(3, nonEmpty.size());
		}

		@Test
		@DisplayName("should addAll varargs without transaction")
		void shouldAddAllVarargsWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			bitmap.addAll(3, 7, 15);
			assertArrayEquals(new int[]{1, 3, 5, 7, 10, 15}, bitmap.getArray());
		}

		@Test
		@DisplayName("should removeAll varargs without transaction")
		void shouldRemoveAllVarargsWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10, 15);
			bitmap.removeAll(5, 15);
			assertArrayEquals(new int[]{1, 10}, bitmap.getArray());
		}
	}

	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("should add items on first, last, and middle positions")
		void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(11);
					original.addAll(11, 0, 6);

					assertTransactionalBitmapIs(new int[]{0, 1, 5, 6, 10, 11}, bitmap);
					assertFalse(bitmap.contains(2));
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[]{1, 5, 10}, original);
					assertArrayEquals(new int[]{0, 1, 5, 6, 10, 11}, committed.getArray());
				}
			);
		}

		@Test
		@DisplayName("should remove items from first, last, and middle positions")
		void shouldCorrectlyRemoveItemsFromFirstLastAndMiddlePositionsAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 5, 6, 10, 11);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.removeAll(11, 1, 5);
					assertTransactionalBitmapIs(new int[]{2, 6, 10}, bitmap);
					assertFalse(bitmap.contains(11));
					assertFalse(bitmap.contains(1));
					assertFalse(bitmap.contains(5));
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[]{1, 2, 5, 6, 10, 11}, original);
					assertArrayEquals(new int[]{2, 6, 10}, committed.getArray());
				}
			);
		}

		@Test
		@DisplayName("should remove multiple items in a row")
		void shouldCorrectlyRemoveMultipleItemsInARowAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 5, 6, 10, 11);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.removeAll(6, 5, 2);

					assertTransactionalBitmapIs(new int[]{1, 10, 11}, original);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[]{1, 2, 5, 6, 10, 11}, original);
					assertArrayEquals(new int[]{1, 10, 11}, committed.getArray());
				}
			);
		}

		@Test
		@DisplayName("should remove multiple items in a row till the end")
		void shouldCorrectlyRemoveMultipleItemsInARowTillTheEndAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 5, 6, 10, 11);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.remove(6);
					original.removeAll(6, 5, 10, 11);

					assertTransactionalBitmapIs(new int[]{1, 2}, original);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[]{1, 2, 5, 6, 10, 11}, original);
					assertArrayEquals(new int[]{1, 2}, committed.getArray());
				}
			);
		}

		@Test
		@DisplayName("should remove multiple items from the beginning")
		void shouldCorrectlyRemoveMultipleItemsInARowFromTheBeginningAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 5, 6, 10, 11);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.removeAll(2, 6, 5, 1, 10);

					assertTransactionalBitmapIs(new int[]{11}, original);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[]{1, 2, 5, 6, 10, 11}, original);
					assertArrayEquals(new int[]{11}, committed.getArray());
				}
			);
		}

		@Test
		@DisplayName("should add nothing and commit")
		void shouldAddNothingAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterCommit(
				bitmap,
				original -> {
					assertTransactionalBitmapIs(new int[]{1, 5, 10}, original);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[]{1, 5, 10}, bitmap);
					assertArrayEquals(new int[]{1, 5, 10}, committed.getArray());
				}
			);
		}

		@Test
		@DisplayName("should add and remove everything")
		void shouldAddAndRemoveEverythingAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(new int[0]);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(1);
					original.add(5);
					original.remove(1);
					original.remove(5);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[0], bitmap);
					assertArrayEquals(new int[0], committed.getArray());
				}
			);
		}

		@Test
		@DisplayName("should add multiple items on same positions")
		void shouldCorrectlyAddMultipleItemsOnSamePositionsAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.addAll(11, 6, 0, 3, 7, 12, 2, 8);
					original.add(3);

					assertTransactionalBitmapIs(
						new int[]{0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12}, original
					);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[]{1, 5, 10}, original);
					assertArrayEquals(
						new int[]{0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12},
						committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should add and remove on non-overlapping positions")
		void shouldCorrectlyAddAndRemoveOnNonOverlappingPositionsAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 5, 6, 10, 11);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(4);
					original.add(3);
					original.remove(10);
					original.remove(6);
					original.add(15);

					assertTransactionalBitmapIs(
						new int[]{1, 2, 3, 4, 5, 11, 15}, original
					);
					assertFalse(bitmap.contains(10));
					assertFalse(bitmap.contains(6));
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(
						new int[]{1, 2, 5, 6, 10, 11}, original
					);
					assertArrayEquals(
						new int[]{1, 2, 3, 4, 5, 11, 15}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should add and remove same number")
		void shouldCorrectlyAddAndRemoveSameNumberAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 5, 6, 10, 11);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(4);
					original.remove(4);

					assertTransactionalBitmapIs(
						new int[]{1, 2, 5, 6, 10, 11}, original
					);
					assertFalse(bitmap.contains(4));
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(
						new int[]{1, 2, 5, 6, 10, 11}, original
					);
					assertArrayEquals(
						new int[]{1, 2, 5, 6, 10, 11}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should remove and add same number")
		void shouldCorrectlyRemoveAndAddSameNumberAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 5, 6, 10, 11);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.remove(5);
					original.remove(10);
					original.remove(11);
					original.add(10);
					original.add(11);
					original.add(5);

					assertTransactionalBitmapIs(
						new int[]{1, 2, 5, 6, 10, 11}, original
					);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(
						new int[]{1, 2, 5, 6, 10, 11}, original
					);
					assertArrayEquals(
						new int[]{1, 2, 5, 6, 10, 11}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should add and remove on overlapping boundary positions")
		void shouldCorrectlyAddAndRemoveOnOverlappingBoundaryPositionsAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 5, 6, 10, 11);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.remove(1);
					original.remove(11);
					original.add(0);
					original.add(12);

					assertTransactionalBitmapIs(
						new int[]{0, 2, 5, 6, 10, 12}, original
					);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(
						new int[]{1, 2, 5, 6, 10, 11}, original
					);
					assertArrayEquals(
						new int[]{0, 2, 5, 6, 10, 12}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should add and remove on overlapping middle positions")
		void shouldCorrectlyAddAndRemoveOnOverlappingMiddlePositionsAndCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 8, 11);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.remove(5);
					original.remove(8);
					original.add(6);
					original.add(7);
					original.add(8);
					original.add(9);
					original.add(10);

					assertTransactionalBitmapIs(
						new int[]{1, 6, 7, 8, 9, 10, 11}, original
					);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[]{1, 5, 8, 11}, original);
					assertArrayEquals(
						new int[]{1, 6, 7, 8, 9, 10, 11}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should handle changes on single position")
		void shouldProperlyHandleChangesOnSinglePosition() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.remove(1);
					original.remove(2);
					original.add(2);
					original.add(4);
					original.remove(2);
					original.add(5);

					assertTransactionalBitmapIs(new int[]{4, 5}, original);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[]{1, 2}, original);
					assertArrayEquals(new int[]{4, 5}, committed.getArray());
				}
			);
		}

		@Test
		@DisplayName("should wipe all correctly")
		void shouldCorrectlyWipeAll() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(36, 59, 179);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(31);
					original.remove(31);
					original.addAll(140, 115);
					original.removeAll(179, 36, 140);
					original.add(58);
					original.removeAll(58, 115, 59);
					original.addAll(156, 141);
					original.remove(141);
					original.add(52);
					original.removeAll(52, 156);

					assertTransactionalBitmapIs(new int[0], bitmap);
				},
				(original, committed) -> assertArrayEquals(
					new int[0], committed.getArray()
				)
			);
		}
	}

	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("should rollback added items on first, last, and middle positions")
		void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndRollback() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterRollback(
				bitmap,
				original -> {
					original.addAll(11, 0, 6);
					assertTransactionalBitmapIs(
						new int[]{0, 1, 5, 6, 10, 11}, bitmap
					);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTransactionalBitmapIs(new int[]{1, 5, 10}, original);
				}
			);
		}

		@Test
		@DisplayName("should rollback removed items on first, last, and middle positions")
		void shouldCorrectlyRemoveItemsOnFirstLastAndMiddlePositionsAndRollback() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterRollback(
				bitmap,
				original -> {
					original.remove(5);
					assertTransactionalBitmapIs(new int[]{1, 10}, original);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTransactionalBitmapIs(new int[]{1, 5, 10}, original);
				}
			);
		}

		@Test
		@DisplayName("should rollback mixed add and remove operations")
		void shouldRollbackMixedAddAndRemoveOperations() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterRollback(
				bitmap,
				original -> {
					original.add(3);
					original.remove(5);
					original.add(20);
					assertTransactionalBitmapIs(
						new int[]{1, 3, 10, 20}, original
					);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTransactionalBitmapIs(new int[]{1, 5, 10}, original);
				}
			);
		}
	}

	@Nested
	@DisplayName("TransactionalLayerProducer contract")
	class TransactionalLayerProducerContractTest {

		@Test
		@DisplayName("should return unique id per instance")
		void shouldReturnUniqueIdPerInstance() {
			final TransactionalBitmap first = new TransactionalBitmap(1, 2, 3);
			final TransactionalBitmap second = new TransactionalBitmap(4, 5, 6);

			assertNotEquals(first.getId(), second.getId());
		}

		@Test
		@DisplayName("should return this when createCopy called with null layer")
		void shouldReturnThisWhenCreateCopyWithNullLayer() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);

			final RoaringBitmapBackedBitmap result =
				bitmap.createCopyWithMergedTransactionalMemory(null, maintainer);

			assertSame(bitmap, result);
		}

		@Test
		@DisplayName("should return new BaseBitmap when createCopy called with layer")
		void shouldReturnNewBaseBitmapWhenCreateCopyWithLayer() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(3);
					original.add(20);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertInstanceOf(BaseBitmap.class, committed);
					assertArrayEquals(
						new int[]{1, 3, 5, 10, 20}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should create layer reflecting current baseline")
		void shouldCreateLayerReflectingCurrentBaseline() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			final BitmapChanges layer = bitmap.createLayer();

			// freshly created layer should reflect the baseline (no changes)
			assertNotNull(layer);
			final RoaringBitmap merged = layer.getMergedBitmap();
			assertArrayEquals(
				new int[]{1, 5, 10}, merged.toArray(),
				"Layer with no changes should reflect original bitmap"
			);
		}

		@Test
		@DisplayName("should remove layer and clean up transactional state")
		void shouldRemoveLayerCleanUpTransactionalState() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterRollback(
				bitmap,
				original -> {
					// make a change inside the transaction
					original.add(99);
					assertTrue(original.contains(99));

					// after rollback, the change should be discarded
				},
				(original, committed) -> {
					assertNull(committed);
					// verify bitmap reads the original baseline
					assertFalse(original.contains(99));
					assertArrayEquals(
						new int[]{1, 5, 10}, original.getArray()
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Bitmap overload operations")
	class BitmapOverloadOperationsTest {

		@Test
		@DisplayName("should addAll from Bitmap without transaction")
		void shouldAddAllFromBitmapWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			final BaseBitmap toAdd = new BaseBitmap(3, 7, 15);

			bitmap.addAll(toAdd);

			assertArrayEquals(
				new int[]{1, 3, 5, 7, 10, 15}, bitmap.getArray()
			);
		}

		@Test
		@DisplayName("should addAll from Bitmap in transaction")
		void shouldAddAllFromBitmapInTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			final BaseBitmap toAdd = new BaseBitmap(3, 7, 15);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.addAll(toAdd);
					assertTransactionalBitmapIs(
						new int[]{1, 3, 5, 7, 10, 15}, original
					);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[]{1, 5, 10}, original);
					assertArrayEquals(
						new int[]{1, 3, 5, 7, 10, 15}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should removeAll from Bitmap without transaction")
		void shouldRemoveAllFromBitmapWithoutTransaction() {
			final TransactionalBitmap bitmap =
				new TransactionalBitmap(1, 3, 5, 7, 10, 15);
			final BaseBitmap toRemove = new BaseBitmap(3, 7, 15);

			bitmap.removeAll(toRemove);

			assertArrayEquals(new int[]{1, 5, 10}, bitmap.getArray());
		}

		@Test
		@DisplayName("should removeAll from Bitmap in transaction")
		void shouldRemoveAllFromBitmapInTransaction() {
			final TransactionalBitmap bitmap =
				new TransactionalBitmap(1, 3, 5, 7, 10, 15);
			final BaseBitmap toRemove = new BaseBitmap(3, 7, 15);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.removeAll(toRemove);
					assertTransactionalBitmapIs(
						new int[]{1, 5, 10}, original
					);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(
						new int[]{1, 3, 5, 7, 10, 15}, original
					);
					assertArrayEquals(
						new int[]{1, 5, 10}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should handle removeAll of empty Bitmap without transaction")
		void shouldRemoveAllEmptyBitmapWithoutTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			final BaseBitmap emptyBitmap = new BaseBitmap();

			bitmap.removeAll(emptyBitmap);

			assertArrayEquals(new int[]{1, 5, 10}, bitmap.getArray());
		}

		@Test
		@DisplayName("should handle addAll of empty Bitmap in transaction")
		void shouldAddAllEmptyBitmapInTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);
			final BaseBitmap emptyBitmap = new BaseBitmap();

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.addAll(emptyBitmap);
					assertTransactionalBitmapIs(
						new int[]{1, 5, 10}, original
					);
				},
				(original, committed) -> {
					assertTransactionalBitmapIs(new int[]{1, 5, 10}, original);
					assertArrayEquals(
						new int[]{1, 5, 10}, committed.getArray()
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Read methods in transaction")
	class ReadMethodsInTransactionTest {

		@Test
		@DisplayName("should return indexOf reflecting transactional changes")
		void shouldReturnIndexOfInTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(3);
					original.remove(5);
					// transactional state: [1, 3, 10]
					assertEquals(0, original.indexOf(1));
					assertEquals(1, original.indexOf(3));
					assertEquals(2, original.indexOf(10));
					assertTrue(original.indexOf(5) < 0);
				},
				(original, committed) -> {
					assertArrayEquals(
						new int[]{1, 3, 10}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should return get(index) reflecting transactional changes")
		void shouldReturnGetInTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(3);
					original.remove(5);
					// transactional state: [1, 3, 10]
					assertEquals(1, original.get(0));
					assertEquals(3, original.get(1));
					assertEquals(10, original.get(2));
				},
				(original, committed) -> {
					assertArrayEquals(
						new int[]{1, 3, 10}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should return getRange reflecting transactional changes")
		void shouldReturnGetRangeInTransaction() {
			final TransactionalBitmap bitmap =
				new TransactionalBitmap(1, 5, 10, 15, 20);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(3);
					original.remove(10);
					// transactional state: [1, 3, 5, 15, 20]
					assertArrayEquals(
						new int[]{3, 5, 15}, original.getRange(1, 4)
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						new int[]{1, 3, 5, 15, 20}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should return getFirst reflecting transactional min")
		void shouldReturnGetFirstInTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(5, 10, 15);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(1);
					// transactional state: [1, 5, 10, 15]
					assertEquals(1, original.getFirst());
				},
				(original, committed) -> {
					assertArrayEquals(
						new int[]{1, 5, 10, 15}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should return getLast reflecting transactional max")
		void shouldReturnGetLastInTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(20);
					// transactional state: [1, 5, 10, 20]
					assertEquals(20, original.getLast());
				},
				(original, committed) -> {
					assertArrayEquals(
						new int[]{1, 5, 10, 20}, committed.getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("should return toString reflecting merged transactional state")
		void shouldReturnToStringInTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(3);
					original.remove(5);
					// transactional state: [1, 3, 10]
					assertEquals("[1, 3, 10]", original.toString());
				},
				(original, committed) -> {
					// after commit, original reverts to baseline
					assertEquals("[1, 5, 10]", original.toString());
				}
			);
		}
	}

	@Nested
	@DisplayName("getRoaringBitmap direct")
	class GetRoaringBitmapDirectTest {

		@Test
		@DisplayName("should return baseline roaring bitmap outside transaction")
		void shouldReturnBaselineRoaringBitmapOutsideTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			final RoaringBitmap roaring = bitmap.getRoaringBitmap();

			assertNotNull(roaring);
			assertArrayEquals(new int[]{1, 5, 10}, roaring.toArray());
		}

		@Test
		@DisplayName("should return merged roaring bitmap inside transaction")
		void shouldReturnMergedRoaringBitmapInsideTransaction() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertStateAfterCommit(
				bitmap,
				original -> {
					original.add(3);
					original.remove(5);

					final RoaringBitmap roaring = original.getRoaringBitmap();
					assertNotNull(roaring);
					assertArrayEquals(
						new int[]{1, 3, 10}, roaring.toArray()
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						new int[]{1, 3, 10}, committed.getArray()
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName("should throw on getRange past bitmap end")
		void shouldHandleGetRangeThrowsWhenOutOfBounds() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			assertThrows(
				IndexOutOfBoundsException.class,
				() -> bitmap.getRange(1, 10)
			);
		}

		@Test
		@DisplayName("should throw on getFirst of empty bitmap")
		void shouldHandleGetFirstThrowsOnEmptyBitmap() {
			final TransactionalBitmap bitmap = new TransactionalBitmap();

			// RoaringBitmap.first() throws NoSuchElementException on empty
			assertThrows(
				NoSuchElementException.class,
				bitmap::getFirst
			);
		}

		@Test
		@DisplayName("should throw on getLast of empty bitmap")
		void shouldHandleGetLastThrowsOnEmptyBitmap() {
			final TransactionalBitmap bitmap = new TransactionalBitmap();

			// RoaringBitmap.last() throws NoSuchElementException on empty
			assertThrows(
				NoSuchElementException.class,
				bitmap::getLast
			);
		}

		@Test
		@DisplayName("should throw on get with out-of-bounds index")
		void shouldHandleGetThrowsOnOutOfBoundsIndex() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			// RoaringBitmap.select() throws IllegalArgumentException on OOB
			assertThrows(
				IllegalArgumentException.class,
				() -> bitmap.get(100)
			);
		}

		@Test
		@DisplayName("should recompute size after memoization invalidated by addAll")
		void shouldRecomputeSizeAfterMemoizationInvalidated() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 5, 10);

			// initial size is memoized
			assertEquals(3, bitmap.size());

			// addAll invalidates memoizedCardinality (sets to -1)
			bitmap.addAll(20, 30);

			// next size() call must recompute
			assertEquals(5, bitmap.size());
			assertArrayEquals(
				new int[]{1, 5, 10, 20, 30}, bitmap.getArray()
			);
		}
	}

	@Nested
	@DisplayName("Equals, hashCode, and toString")
	class EqualsHashCodeToStringTest {

		@Test
		@DisplayName("should be equal to identical TransactionalBitmap")
		void shouldBeEqualToIdenticalTransactionalBitmap() {
			final TransactionalBitmap bitmap1 = new TransactionalBitmap(1, 2, 3);
			final TransactionalBitmap bitmap2 = new TransactionalBitmap(1, 2, 3);
			assertEquals(bitmap1, bitmap2);
		}

		@Test
		@DisplayName("should not be equal to different TransactionalBitmap")
		void shouldNotBeEqualToDifferentTransactionalBitmap() {
			final TransactionalBitmap bitmap1 = new TransactionalBitmap(1, 2, 3);
			final TransactionalBitmap bitmap2 = new TransactionalBitmap(4, 5, 6);
			assertNotEquals(bitmap1, bitmap2);
		}

		@Test
		@DisplayName("should have consistent hashCode")
		void shouldHaveConsistentHashCode() {
			final TransactionalBitmap bitmap1 = new TransactionalBitmap(1, 2, 3);
			final TransactionalBitmap bitmap2 = new TransactionalBitmap(1, 2, 3);
			assertEquals(bitmap1.hashCode(), bitmap2.hashCode());
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);
			final String result = bitmap.toString();
			assertNotNull(result);
			assertEquals("[1, 2, 3]", result);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);

			assertNotEquals(null, bitmap);
		}

		@Test
		@DisplayName("should be equal to self")
		void shouldBeEqualToSelf() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);

			assertEquals(bitmap, bitmap);
		}
	}

	@Nested
	@DisplayName("BitmapChanges memoization")
	class BitmapChangesMemoizationTest {

		@Test
		@DisplayName("should not invalidate memoized bitmap when addRecordId is a no-op")
		void shouldNotInvalidateMemoWhenAddRecordIdIsNoOp() {
			final RoaringBitmap original = new RoaringBitmap();
			original.add(1, 5, 10);
			final BitmapChanges changes = new BitmapChanges(original);

			// make a real change so memoizedMergedBitmap gets populated
			changes.addRecordId(20);
			final RoaringBitmap firstMerged = changes.getMergedBitmap();
			assertArrayEquals(
				new int[]{1, 5, 10, 20}, firstMerged.toArray()
			);

			// add a record that already exists in the original -- this is a no-op
			final boolean result = changes.addRecordId(5);
			assertFalse(result, "Adding an already-present record should return false");

			// the memoized bitmap should be preserved (same identity)
			final RoaringBitmap secondMerged = changes.getMergedBitmap();
			assertSame(
				firstMerged, secondMerged,
				"No-op addRecordId should not invalidate the memoized merged bitmap"
			);
		}

		@Test
		@DisplayName("should not invalidate memoized bitmap when removeRecordId is a no-op")
		void shouldNotInvalidateMemoWhenRemoveRecordIdIsNoOp() {
			final RoaringBitmap original = new RoaringBitmap();
			original.add(1, 5, 10);
			final BitmapChanges changes = new BitmapChanges(original);

			// prime the memoized merged bitmap by adding a record first
			changes.addRecordId(20);
			final RoaringBitmap firstMerged = changes.getMergedBitmap();

			// remove a record that doesn't exist -- this is a no-op
			final boolean result = changes.removeRecordId(99);
			assertFalse(result, "Removing a non-present record should return false");

			// the memoized bitmap should be preserved (same identity)
			final RoaringBitmap secondMerged = changes.getMergedBitmap();
			assertSame(
				firstMerged, secondMerged,
				"No-op removeRecordId should not invalidate the memoized merged bitmap"
			);
		}
	}

	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalRandomizedProofTest {

		@ParameterizedTest(name = "TransactionalBitmap should survive generational randomized test applying modifications on it")
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		void generationalProofTest(GenerationalTestInput input) {
			final int initialCount = 100;
			final int[] initialState =
				generateRandomInitialBitmap(new Random(input.randomSeed()), initialCount);

			runFor(
				input,
				10_000,
				new TestState(initialState),
				(random, testState) -> {
					final TransactionalBitmap transactionalBitmap =
						new TransactionalBitmap(testState.initialBitmap());
					final AtomicReference<int[]> nextBitmapToCompare =
						new AtomicReference<>(testState.initialBitmap());

					assertStateAfterCommit(
						transactionalBitmap,
						original -> {
							final int operationsInTransaction = random.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = transactionalBitmap.size();
								if (random.nextBoolean() || length < 10) {
									// insert new item
									final int newRecId =
										random.nextInt(initialCount * 2);
									transactionalBitmap.add(newRecId);
									nextBitmapToCompare.set(
										ArrayUtils.insertIntIntoOrderedArray(
											newRecId, nextBitmapToCompare.get()
										)
									);
								} else {
									// remove existing item
									final int removedRecId =
										transactionalBitmap.get(
											random.nextInt(length)
										);
									transactionalBitmap.remove(removedRecId);
									nextBitmapToCompare.set(
										ArrayUtils.removeIntFromOrderedArray(
											removedRecId,
											nextBitmapToCompare.get()
										)
									);
								}
							}

							assertTransactionalBitmapIs(
								nextBitmapToCompare.get(), transactionalBitmap
							);
						},
						(original, committed) -> {
							assertArrayEquals(
								nextBitmapToCompare.get(),
								committed.getArray()
							);
						}
					);

					return new TestState(
						nextBitmapToCompare.get()
					);
				}
			);
		}
	}

	/**
	 * Generates a random sorted array of unique integers for use as initial bitmap state.
	 */
	private int[] generateRandomInitialBitmap(Random rnd, int count) {
		final Set<Integer> uniqueSet = new HashSet<>();
		final int[] initialBitmap = new int[count];
		for (int i = 0; i < count; i++) {
			boolean added;
			do {
				final int recId = rnd.nextInt(count * 2);
				added = uniqueSet.add(recId);
				if (added) {
					initialBitmap[i] = recId;
				}
			} while (!added);
		}
		Arrays.sort(initialBitmap);
		return initialBitmap;
	}

	/**
	 * Asserts that the given {@link TransactionalBitmap} contains exactly the expected
	 * record ids in sorted order, verifying emptiness, containment, array equality,
	 * size, and iterator consistency.
	 */
	private static void assertTransactionalBitmapIs(
		int[] expectedResult,
		TransactionalBitmap bitmap
	) {
		if (ArrayUtils.isEmpty(expectedResult)) {
			assertTrue(bitmap.isEmpty());
		} else {
			assertFalse(bitmap.isEmpty());
		}

		for (int recordId : expectedResult) {
			assertTrue(
				bitmap.contains(recordId),
				"IntegerBitmap should contain " + recordId + ", but does not!"
			);
		}

		assertArrayEquals(expectedResult, bitmap.getArray());
		assertEquals(expectedResult.length, bitmap.size());

		final OfInt it = bitmap.iterator();
		int index = -1;
		while (it.hasNext()) {
			final int nextInt = it.next();
			assertTrue(expectedResult.length > index + 1);
			assertEquals(expectedResult[++index], nextInt);
		}
		assertEquals(
			expectedResult.length, index + 1,
			"There are more expected ints than int bitmap produced by iterator!"
		);
	}

	private record TestState(
		int[] initialBitmap
	) {}
}
