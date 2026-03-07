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

package io.evitadb.index.invertedIndex;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ValueToRecordBitmap} covering construction,
 * non-transactional operations, STM commit/rollback, equality,
 * makeClone, and edge cases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ValueToRecordBitmap")
class ValueToRecordBitmapTest implements TimeBoundedTestSupport {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("empty-bitmap constructor: empty records")
		void shouldCreateWithEmptyRecords() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("alpha");
			assertEquals("alpha", bucket.getValue());
			assertTrue(bucket.isEmpty());
			assertTrue(bucket.getRecordIds().isEmpty());
		}

		@Test
		@DisplayName("Bitmap constructor: records populated")
		void shouldCreateWithBitmap() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap(
					"beta", new BaseBitmap(1, 2, 3)
				);
			assertEquals("beta", bucket.getValue());
			assertFalse(bucket.isEmpty());
			assertEquals(3, bucket.getRecordIds().size());
			assertTrue(bucket.getRecordIds().contains(1));
			assertTrue(bucket.getRecordIds().contains(2));
			assertTrue(bucket.getRecordIds().contains(3));
		}

		@Test
		@DisplayName("TransactionalBitmap constructor: records populated")
		void shouldCreateWithTransactionalBitmap() {
			final TransactionalBitmap tb =
				new TransactionalBitmap(new BaseBitmap(10, 20));
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("gamma", tb);
			assertEquals(2, bucket.getRecordIds().size());
			assertTrue(bucket.getRecordIds().contains(10));
			assertTrue(bucket.getRecordIds().contains(20));
		}

		@Test
		@DisplayName("int-vararg constructor: records populated")
		void shouldCreateWithIntVararg() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("delta", 5, 10, 15);
			assertEquals(3, bucket.getRecordIds().size());
			assertTrue(bucket.getRecordIds().contains(5));
			assertTrue(bucket.getRecordIds().contains(10));
			assertTrue(bucket.getRecordIds().contains(15));
		}

		@Test
		@DisplayName(
			"all four constructors produce same records for same input"
		)
		void shouldProduceSameRecordsAcrossConstructors() {
			final ValueToRecordBitmap fromEmpty =
				new ValueToRecordBitmap("v");
			fromEmpty.addRecord(1, 2, 3);

			final ValueToRecordBitmap fromBitmap =
				new ValueToRecordBitmap(
					"v", new BaseBitmap(1, 2, 3)
				);
			final ValueToRecordBitmap fromTxBitmap =
				new ValueToRecordBitmap(
					"v",
					new TransactionalBitmap(new BaseBitmap(1, 2, 3))
				);
			final ValueToRecordBitmap fromVararg =
				new ValueToRecordBitmap("v", 1, 2, 3);

			assertTrue(fromEmpty.deepEquals(fromBitmap));
			assertTrue(fromBitmap.deepEquals(fromTxBitmap));
			assertTrue(fromTxBitmap.deepEquals(fromVararg));
		}
	}

	@Nested
	@DisplayName("Core mutations — Non-transactional (T8)")
	class NonTransactionalMutationsTest {

		@Test
		@DisplayName(
			"addRecord mutates recordIds, leaves value unchanged"
		)
		void shouldAddRecordAndPreserveValue() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("val");
			bucket.addRecord(1, 2, 3);
			assertEquals("val", bucket.getValue());
			assertEquals(3, bucket.getRecordIds().size());
		}

		@Test
		@DisplayName(
			"removeRecord with non-existent ID is silently skipped"
		)
		void shouldSilentlySkipNonExistentRemove() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("val", 1, 2);
			// should not throw
			bucket.removeRecord(999);
			assertEquals(2, bucket.getRecordIds().size());
		}

		@Test
		@DisplayName("add merges same-value bucket")
		void shouldMergeSameValueBucket() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("val", 1, 2);
			final ValueToRecordBitmap other =
				new ValueToRecordBitmap("val", 3, 4);
			bucket.add(other);
			assertEquals(4, bucket.getRecordIds().size());
			assertTrue(bucket.getRecordIds().contains(3));
			assertTrue(bucket.getRecordIds().contains(4));
		}

		@Test
		@DisplayName("add with different value throws")
		void shouldThrowOnAddDifferentValue() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("a", 1);
			final ValueToRecordBitmap other =
				new ValueToRecordBitmap("b", 2);
			assertThrows(
				Exception.class,
				() -> bucket.add(other)
			);
		}

		@Test
		@DisplayName("remove subtracts same-value bucket")
		void shouldSubtractSameValueBucket() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("val", 1, 2, 3);
			final ValueToRecordBitmap toRemove =
				new ValueToRecordBitmap("val", 2);
			bucket.remove(toRemove);
			assertEquals(2, bucket.getRecordIds().size());
			assertFalse(bucket.getRecordIds().contains(2));
		}

		@Test
		@DisplayName("remove with different value throws")
		void shouldThrowOnRemoveDifferentValue() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("a", 1);
			final ValueToRecordBitmap other =
				new ValueToRecordBitmap("b", 1);
			assertThrows(
				Exception.class,
				() -> bucket.remove(other)
			);
		}

		@Test
		@DisplayName("isEmpty on empty and non-empty instances")
		void shouldTrackEmptiness() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("val");
			assertTrue(bucket.isEmpty());
			bucket.addRecord(1);
			assertFalse(bucket.isEmpty());
			bucket.removeRecord(1);
			assertTrue(bucket.isEmpty());
		}
	}

	@Nested
	@DisplayName("STM — Commit")
	class CommitTest {

		@Test
		@DisplayName(
			"commit with addRecord: committed has new records (T1)"
		)
		void shouldCommitAddRecord() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("val", 1);

			assertStateAfterCommit(
				bucket,
				original -> original.addRecord(2, 3),
				(original, committed) -> {
					// original unchanged (INV-4)
					assertEquals(1, original.getRecordIds().size());
					assertTrue(original.getRecordIds().contains(1));
					// committed has all records
					assertEquals(3, committed.getRecordIds().size());
					assertTrue(committed.getRecordIds().contains(1));
					assertTrue(committed.getRecordIds().contains(2));
					assertTrue(committed.getRecordIds().contains(3));
				}
			);
		}

		@Test
		@DisplayName(
			"committed copy is NEW instance (INV-7)"
		)
		void shouldReturnNewInstanceOnCommit() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("val", 1);

			assertStateAfterCommit(
				bucket,
				original -> original.addRecord(2),
				Assertions::assertNotSame
			);
		}

		@Test
		@DisplayName("baseline unchanged after commit (INV-4)")
		void shouldPreserveBaselineAfterCommit() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("val", 1, 2, 3);

			assertStateAfterCommit(
				bucket,
				original -> {
					original.addRecord(4);
					original.removeRecord(1);
				},
				(original, committed) -> {
					// original has original records
					assertEquals(3, original.getRecordIds().size());
					assertTrue(original.getRecordIds().contains(1));
					// committed reflects mutations
					assertEquals(3, committed.getRecordIds().size());
					assertFalse(committed.getRecordIds().contains(1));
					assertTrue(committed.getRecordIds().contains(4));
				}
			);
		}
	}

	@Nested
	@DisplayName("STM — Rollback")
	class RollbackTest {

		@Test
		@DisplayName(
			"rollback: original exposes same record IDs as before (T7)"
		)
		void shouldRollbackRecordChanges() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("val", 1, 2);

			assertStateAfterRollback(
				bucket,
				original -> {
					original.addRecord(99);
					original.removeRecord(1);
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals(2, original.getRecordIds().size());
					assertTrue(original.getRecordIds().contains(1));
					assertTrue(original.getRecordIds().contains(2));
					assertFalse(original.getRecordIds().contains(99));
				}
			);
		}
	}

	@Nested
	@DisplayName("STM — removeLayer")
	class RemoveLayerTest {

		@Test
		@DisplayName(
			"getMaintainedTransactionalCreators returns singleton"
		)
		void shouldReturnSingletonCreators() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("val");
			assertEquals(
				1,
				bucket.getMaintainedTransactionalCreators().size()
			);
		}
	}

	@Nested
	@DisplayName("makeClone")
	class MakeCloneTest {

		@Test
		@DisplayName("clone is different instance with equal data")
		void shouldCloneWithSameData() {
			final ValueToRecordBitmap original =
				new ValueToRecordBitmap("val", 1, 2, 3);
			final ValueToRecordBitmap clone = original.makeClone();
			assertNotSame(original, clone);
			assertTrue(original.deepEquals(clone));
			assertEquals(original.getValue(), clone.getValue());
		}

		@Test
		@DisplayName("mutating clone does not affect original")
		void shouldNotAffectOriginalOnCloneMutation() {
			final ValueToRecordBitmap original =
				new ValueToRecordBitmap("val", 1, 2);
			final ValueToRecordBitmap clone = original.makeClone();
			clone.addRecord(99);
			assertFalse(original.getRecordIds().contains(99));
			assertEquals(2, original.getRecordIds().size());
		}
	}

	@Nested
	@DisplayName("equals / hashCode / compareTo / deepEquals")
	class EqualityTest {

		@Test
		@DisplayName(
			"same value, different recordIds: equals true, deepEquals false"
		)
		void shouldDistinguishEqualsAndDeepEquals() {
			final ValueToRecordBitmap a =
				new ValueToRecordBitmap("val", 1);
			final ValueToRecordBitmap b =
				new ValueToRecordBitmap("val", 2);
			assertEquals(a, b);
			assertFalse(a.deepEquals(b));
		}

		@Test
		@DisplayName(
			"hashCode consistent with equals (same value → same hash)"
		)
		void shouldHaveConsistentHashCode() {
			final ValueToRecordBitmap a =
				new ValueToRecordBitmap("val", 1);
			final ValueToRecordBitmap b =
				new ValueToRecordBitmap("val", 2, 3);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("compareTo ordering by value")
		void shouldOrderByValue() {
			final ValueToRecordBitmap a =
				new ValueToRecordBitmap("apple");
			final ValueToRecordBitmap b =
				new ValueToRecordBitmap("banana");
			assertTrue(a.compareTo(b) < 0);
			assertTrue(b.compareTo(a) > 0);
			assertEquals(0, a.compareTo(a));
		}

		@Test
		@DisplayName("equals(null) and equals(wrongType) return false")
		void shouldHandleNullAndWrongType() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("val");
			assertNotEquals(null, bucket);
			assertNotEquals("string", bucket);
		}

		@Test
		@DisplayName("toString format includes value and recordIds")
		void shouldFormatToString() {
			final ValueToRecordBitmap bucket =
				new ValueToRecordBitmap("test", 1, 2);
			final String result = bucket.toString();
			assertTrue(result.contains("test"));
			assertTrue(result.contains("ValueToRecordBitmap"));
		}
	}
}
