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

package io.evitadb.index.attribute;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * Test verifies contract of {@link UniqueIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class UniqueIndexTest {
	private final UniqueIndex tested = new OwnerUniqueIndex(Entities.PRODUCT, new AttributeIndexKey(null, "whatever", null), String.class);

	@Test
	void shouldRegisterUniqueValueAndRetrieveItBack() {
		this.tested.registerUniqueKey("A", 1);
		assertEquals(1, this.tested.getRecordIdByUniqueValue("A"));
		assertNull(this.tested.getRecordIdByUniqueValue("B"));
	}

	@Test
	void shouldFailToRegisterDuplicateValues() {
		this.tested.registerUniqueKey("A", 1);
		assertThrows(UniqueValueViolationException.class, () -> this.tested.registerUniqueKey("A", 2));
	}

	@Test
	void shouldUnregisterPreviouslyRegisteredValue() {
		this.tested.registerUniqueKey("A", 1);
		assertEquals(1, this.tested.unregisterUniqueKey("A", 1));
		assertNull(this.tested.getRecordIdByUniqueValue("A"));
	}

	@Test
	void shouldFailToUnregisterUnknownValue() {
		assertThrows(IllegalArgumentException.class, () -> this.tested.unregisterUniqueKey("B", 1));
	}

	@Test
	void shouldRegisterAndPartialUnregisterValues() {
		this.tested.registerUniqueKey(new String[]{"A", "B", "C"}, 1);
		assertEquals(1, this.tested.getRecordIdByUniqueValue("A"));
		assertEquals(1, this.tested.getRecordIdByUniqueValue("B"));
		assertEquals(1, this.tested.getRecordIdByUniqueValue("C"));
		this.tested.unregisterUniqueKey(new String[]{"B", "C"}, 1);
		assertEquals(1, this.tested.getRecordIdByUniqueValue("A"));
		assertNull(this.tested.getRecordIdByUniqueValue("B"));
		assertNull(this.tested.getRecordIdByUniqueValue("C"));
	}

	/**
	 * Tests for STM invariants of {@link UniqueIndex}.
	 */
	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@SuppressWarnings("EqualsWithItself")
		@Test
		@DisplayName("getId() returns stable value and unique across instances (INV-1)")
		void shouldReturnStableAndUniqueId() {
			final UniqueIndex first = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			final UniqueIndex second = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);

			// stable — same value on repeated calls
			assertEquals(first.getId(), first.getId());
			// unique — different instances have different ids
			assertNotEquals(first.getId(), second.getId());
		}

		@Test
		@DisplayName("baseline unchanged after commit (INV-4, T2)")
		void shouldLeaveBaselineUnchangedAfterCommit() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			index.registerUniqueKey("pre-existing", 100);

			assertStateAfterCommit(
				index,
				original -> {
					original.registerUniqueKey("new-value", 200);
					original.unregisterUniqueKey("pre-existing", 100);
				},
				(original, committed) -> {
					// original baseline must still see "pre-existing" and not "new-value"
					assertEquals(100, original.getRecordIdByUniqueValue("pre-existing"));
					assertNull(original.getRecordIdByUniqueValue("new-value"));
					assertEquals(1, original.size());

					// committed sees the transactional changes
					assertNull(committed.getRecordIdByUniqueValue("pre-existing"));
					assertEquals(200, committed.getRecordIdByUniqueValue("new-value"));
					assertEquals(1, committed.size());
				}
			);
		}

		@Test
		@DisplayName("removeLayer cleans all three nested producers (INV-5)")
		void shouldCleanAllNestedProducersOnRollback() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			index.registerUniqueKey("A", 1);

			// rollback implicitly calls removeLayer on nested producers
			assertStateAfterRollback(
				index,
				original -> {
					original.registerUniqueKey("B", 2);
					original.unregisterUniqueKey("A", 1);
				},
				(original, committed) -> {
					assertNull(committed);
					// all changes reverted
					assertEquals(1, original.getRecordIdByUniqueValue("A"));
					assertNull(original.getRecordIdByUniqueValue("B"));
					assertEquals(1, original.size());
				}
			);
		}

		@Test
		@DisplayName("dirty commit merges all three nested states into new instance (INV-6)")
		void shouldMergeAllNestedStatesOnDirtyCommit() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);

			assertStateAfterCommit(
				index,
				original -> {
					original.registerUniqueKey("X", 10);
					original.registerUniqueKey("Y", 20);
				},
				(original, committed) -> {
					// committed must carry map, bitmap, and dirty state
					assertEquals(10, committed.getRecordIdByUniqueValue("X"));
					assertEquals(20, committed.getRecordIdByUniqueValue("Y"));
					assertArrayEquals(
						new int[]{10, 20},
						committed.getRecordIds().getArray()
					);
					assertEquals(2, committed.size());
				}
			);
		}

		@Test
		@DisplayName("committed is not same instance as original when dirty (INV-7)")
		void shouldReturnNewInstanceWhenDirty() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);

			assertStateAfterCommit(
				index,
				original -> original.registerUniqueKey("A", 1),
				Assertions::assertNotSame
			);
		}

		@Test
		@DisplayName("zero mutations returns same instance on commit (INV-8)")
		void shouldReturnSameInstanceWhenNoMutations() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);

			assertStateAfterCommit(
				index,
				original -> {
					// no mutations at all
				},
				Assertions::assertSame
			);
		}

		@Test
		@DisplayName("committed instance has no stale transactional layer (INV-10)")
		void shouldHaveNoStaleLayerOnCommittedInstance() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);

			final AtomicReference<UniqueIndex> committedRef = new AtomicReference<>();

			assertStateAfterCommit(
				index,
				original -> original.registerUniqueKey("A", 1),
				(original, committed) -> committedRef.set(committed)
			);

			// outside any transaction, the committed instance should work normally
			final UniqueIndex committed = committedRef.get();
			assertEquals(1, committed.getRecordIdByUniqueValue("A"));
			// further mutations should work without transaction
			committed.registerUniqueKey("B", 2);
			assertEquals(2, committed.getRecordIdByUniqueValue("B"));
		}
	}

	/**
	 * Tests for transactional rollback semantics of {@link UniqueIndex}.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("register + unregister keys are reverted after rollback (T7)")
		void shouldRevertRegisterAndUnregisterAfterRollback() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			index.registerUniqueKey("X", 10);
			index.registerUniqueKey("Y", 20);

			assertStateAfterRollback(
				index,
				original -> {
					original.registerUniqueKey("Z", 30);
					original.unregisterUniqueKey("X", 10);
					// within the transaction both changes are visible
					assertNull(original.getRecordIdByUniqueValue("X"));
					assertEquals(30, original.getRecordIdByUniqueValue("Z"));
				},
				(original, committed) -> {
					assertNull(committed);
					// after rollback, the index is identical to its pre-transaction state
					assertEquals(10, original.getRecordIdByUniqueValue("X"));
					assertEquals(20, original.getRecordIdByUniqueValue("Y"));
					assertNull(original.getRecordIdByUniqueValue("Z"));
					assertEquals(2, original.size());
					assertArrayEquals(
						new int[]{10, 20},
						original.getRecordIds().getArray()
					);
				}
			);
		}
	}

	/**
	 * Tests for formula memoization and cache invalidation in {@link UniqueIndex}.
	 */
	@Nested
	@DisplayName("Formula and memoization")
	class FormulaAndMemoizationTest {

		@Test
		@DisplayName("getRecordIdsFormula() caches across calls and invalidates on mutation (T8)")
		void shouldCacheFormulaAndInvalidateOnMutation() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			index.registerUniqueKey("A", 1);

			// two consecutive calls return the same cached instance
			final Formula first = index.getRecordIdsFormula();
			final Formula second = index.getRecordIdsFormula();
			assertSame(first, second);

			// mutation invalidates cache — a new formula is returned
			index.registerUniqueKey("B", 2);
			final Formula afterMutation = index.getRecordIdsFormula();
			assertNotSame(first, afterMutation);
		}

		@Test
		@DisplayName("getRecordIdsFormula() cache invalidation on unregister")
		void shouldInvalidateCacheOnUnregister() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			index.registerUniqueKey("A", 1);
			index.registerUniqueKey("B", 2);

			final Formula before = index.getRecordIdsFormula();
			index.unregisterUniqueKey("A", 1);
			final Formula after = index.getRecordIdsFormula();
			assertNotSame(before, after);
		}

		@Test
		@DisplayName("getRecordIdsFormula() during open transaction with dirty flag returns fresh formula")
		void shouldReturnFreshFormulaInDirtyTransaction() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			index.registerUniqueKey("A", 1);

			// cache formula before transaction
			final Formula cachedBefore = index.getRecordIdsFormula();

			assertStateAfterCommit(
				index,
				original -> {
					original.registerUniqueKey("B", 2);
					// inside a dirty transaction, formula should reflect changes
					final Formula inTx = original.getRecordIdsFormula();
					assertArrayEquals(
						new int[]{1, 2},
						inTx.compute().getArray()
					);
				},
				(original, committed) -> {
					// committed index should provide a formula with both records
					final Formula committedFormula = committed.getRecordIdsFormula();
					assertArrayEquals(
						new int[]{1, 2},
						committedFormula.compute().getArray()
					);
				}
			);
		}
	}

	/**
	 * Tests for functional gaps in {@link UniqueIndex}: constructors, state queries, storage parts, and
	 * value validation.
	 */
	@Nested
	@DisplayName("Functional gaps")
	class FunctionalGapsTest {

		@Test
		@DisplayName("isEmpty() and size() on freshly constructed empty index")
		void shouldReportEmptyOnFreshIndex() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);

			assertTrue(index.isEmpty());
			assertEquals(0, index.size());
		}

		@Test
		@DisplayName("appendStorageParts emits nothing when not dirty")
		void shouldReturnNullStoragePartWhenNotDirty() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);

			final TrappedChanges sink = new TrappedChanges();
			index.appendStorageParts(1, sink);
			assertEquals(0, sink.getTrappedChangesCount());
		}

		@Test
		@DisplayName("appendStorageParts emits the correct part when dirty")
		void shouldReturnStoragePartWhenDirty() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			index.registerUniqueKey("A", 1);

			final TrappedChanges sink = new TrappedChanges();
			index.appendStorageParts(42, sink);
			assertEquals(1, sink.getTrappedChangesCount());

			final StoragePart result = sink.getTrappedChangesIterator().next();
			assertInstanceOf(UniqueIndexStoragePart.class, result);

			final UniqueIndexStoragePart storagePart = (UniqueIndexStoragePart) result;
			assertEquals(42, storagePart.getEntityIndexPrimaryKey());
			assertEquals(
				new AttributeIndexKey(null, "code", null),
				storagePart.getAttributeIndexKey()
			);
			assertSame(String.class, storagePart.getType());
		}

		@Test
		@DisplayName("resetDirty() clears dirty flag so appendStorageParts emits nothing")
		void shouldClearDirtyFlagOnReset() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			index.registerUniqueKey("A", 1);

			// dirty — a part is emitted
			final TrappedChanges beforeReset = new TrappedChanges();
			index.appendStorageParts(1, beforeReset);
			assertEquals(1, beforeReset.getTrappedChangesCount());

			// reset dirty flag
			index.resetDirty();

			// now nothing is emitted
			final TrappedChanges afterReset = new TrappedChanges();
			index.appendStorageParts(1, afterReset);
			assertEquals(0, afterReset.getTrappedChangesCount());
		}

		@Test
		@DisplayName("array value registration: partial duplicate throws UniqueValueViolationException before any mutation")
		void shouldThrowOnPartialDuplicateInArrayBeforeMutation() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			// register "B" owned by record 99
			index.registerUniqueKey("B", 99);

			// attempt to register array ["A", "B", "C"] for record 1 — "B" is already taken
			assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey(new String[]{"A", "B", "C"}, 1)
			);

			// "A" and "C" should NOT have been registered (validation-first approach)
			assertNull(index.getRecordIdByUniqueValue("A"));
			assertNull(index.getRecordIdByUniqueValue("C"));
			// "B" should still belong to record 99
			assertEquals(99, index.getRecordIdByUniqueValue("B"));
		}

		@Test
		@DisplayName("inlineSnapshot() exposes the registered (value, recordId) columns")
		void shouldReturnUnmodifiableMap() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			index.registerUniqueKey("A", 1);

			final UniqueIndex.InlineSnapshot snapshot = index.inlineSnapshot();
			assertArrayEquals(new Serializable[]{"A"}, snapshot.values());
			assertArrayEquals(new int[]{1}, snapshot.recordIds());
		}

		@Test
		@DisplayName("constructor with pre-populated value/record columns")
		void shouldConstructFromPrePopulatedMapAndBitmap() {
			final Serializable[] values = {"X", "Y"};
			final int[] recordIds = {10, 20};

			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class,
				values,
				recordIds
			);

			assertFalse(index.isEmpty());
			assertEquals(2, index.size());
			assertEquals(10, index.getRecordIdByUniqueValue("X"));
			assertEquals(20, index.getRecordIdByUniqueValue("Y"));
			assertArrayEquals(
				new int[]{10, 20},
				index.getRecordIds().getArray()
			);
		}

		@Test
		@DisplayName("unregisterUniqueKey on array with wrong owner throws assertion error")
		void shouldThrowWhenUnregisteringArrayWithWrongOwner() {
			final UniqueIndex index = new OwnerUniqueIndex(
				Entities.PRODUCT,
				new AttributeIndexKey(null, "code", null),
				String.class
			);
			index.registerUniqueKey(new String[]{"A", "B"}, 1);

			// try to unregister with wrong owner — should throw before any removal
			assertThrows(
				IllegalArgumentException.class,
				() -> index.unregisterUniqueKey(new String[]{"A", "B"}, 999)
			);

			// both values should still be owned by record 1
			assertEquals(1, index.getRecordIdByUniqueValue("A"));
			assertEquals(1, index.getRecordIdByUniqueValue("B"));
		}

		@Test
		@DisplayName("verifyValue rejects non-Serializable value")
		void shouldRejectNonSerializableValue() {
			// a non-Serializable, non-Comparable value
			final Object notSerializable = new Object();
			assertThrows(
				IllegalArgumentException.class,
				() -> UniqueIndex.verifyValue(notSerializable)
			);
		}

		@SuppressWarnings("SerializableInnerClassWithNonSerializableOuterClass")
		@Test
		@DisplayName("verifyValue rejects non-Comparable Serializable value")
		void shouldRejectNonComparableSerializableValue() {
			// Serializable but NOT Comparable
			final Serializable notComparable = new Serializable() {
				@Serial private static final long serialVersionUID = 1L;
			};
			assertThrows(
				IllegalArgumentException.class,
				() -> UniqueIndex.verifyValue(notComparable)
			);
		}

		@Test
		@DisplayName("verifyValueArray rejects array of non-Serializable components")
		void shouldRejectNonSerializableArrayValue() {
			final Object[] notSerializableArray = new Object[]{"A", "B"};
			assertThrows(
				IllegalArgumentException.class,
				() -> UniqueIndex.verifyValueArray(notSerializableArray)
			);
		}
	}

}
