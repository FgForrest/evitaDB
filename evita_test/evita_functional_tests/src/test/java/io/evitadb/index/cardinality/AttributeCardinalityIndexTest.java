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

package io.evitadb.index.cardinality;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * Tests for {@link AttributeCardinalityIndex} covering construction, non-transactional operations,
 * type safety, dirty flag/storage, STM commit/rollback semantics, and inner record contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("AttributeCardinalityIndex")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class AttributeCardinalityIndexTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should initialize with empty cardinalities using default constructor")
		void shouldInitializeEmpty() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			assertTrue(index.getCardinalities().isEmpty());
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("should initialize with provided cardinalities using map constructor")
		void shouldInitializeWithMap() {
			final AttributeCardinalityKey key = new AttributeCardinalityKey(1, "test");
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(
				String.class,
				java.util.Map.of(key, 2)
			);
			assertFalse(index.isEmpty());
			assertEquals(2, index.getCardinalities().get(key));
		}

		@Test
		@DisplayName("should store value type from constructor")
		void shouldStoreValueType() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(Integer.class);
			assertEquals(Integer.class, index.getValueType());
		}
	}

	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("should return true on first add of record (new entry)")
		void shouldReturnTrueOnFirstAdd() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			assertTrue(index.addRecord("value", 1));
		}

		@Test
		@DisplayName("should return false on subsequent add of same record (increment)")
		void shouldReturnFalseOnSubsequentAdd() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			index.addRecord("value", 1);
			assertFalse(index.addRecord("value", 1));
		}

		@Test
		@DisplayName("should increment cardinality beyond two")
		void shouldIncrementCardinalityBeyondTwo() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			assertTrue(index.addRecord("value", 1));
			assertFalse(index.addRecord("value", 1));
			assertFalse(index.addRecord("value", 1));
			final AttributeCardinalityKey key = new AttributeCardinalityKey(1, "value");
			assertEquals(3, index.getCardinalities().get(key));
		}

		@Test
		@DisplayName("should return true when remove reduces cardinality to zero")
		void shouldReturnTrueWhenRemovedCompletely() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			index.addRecord("value", 1);
			assertTrue(index.removeRecord("value", 1));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("should return false when remove reduces cardinality above zero")
		void shouldReturnFalseWhenStillPresent() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			index.addRecord("value", 1);
			index.addRecord("value", 1);
			assertFalse(index.removeRecord("value", 1));
			assertFalse(index.isEmpty());
		}

		@Test
		@DisplayName("should not be empty after single add")
		void shouldNotBeEmptyAfterAdd() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			index.addRecord("value", 1);
			assertFalse(index.isEmpty());
		}

		@Test
		@DisplayName("should be empty after add then fully removed")
		void shouldBeEmptyAfterFullRemoval() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			index.addRecord("value", 1);
			index.removeRecord("value", 1);
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("should support distinct keys for same value with different recordIds")
		void shouldSupportDistinctKeysForSameValue() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			assertTrue(index.addRecord("value", 1));
			assertTrue(index.addRecord("value", 2));
			assertEquals(2, index.getCardinalities().size());
		}

		@Test
		@DisplayName("should support distinct keys for same recordId with different values")
		void shouldSupportDistinctKeysForSameRecordId() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			assertTrue(index.addRecord("value1", 1));
			assertTrue(index.addRecord("value2", 1));
			assertEquals(2, index.getCardinalities().size());
		}
	}

	@Nested
	@DisplayName("Type safety")
	class TypeSafetyTest {

		@Test
		@DisplayName("should throw when removing absent key")
		void shouldThrowWhenRemovingAbsentKey() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			assertThrows(GenericEvitaInternalError.class, () -> index.removeRecord("missing", 1));
		}

		@Test
		@DisplayName("should throw when adding value of wrong type")
		void shouldThrowWhenAddingWrongType() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(Integer.class);
			assertThrows(IllegalArgumentException.class, () -> index.addRecord("string", 1));
		}
	}

	@Nested
	@DisplayName("Dirty flag and storage part")
	class DirtyFlagTest {

		@Test
		@DisplayName("should return null storage part when not dirty")
		void shouldReturnNullWhenNotDirty() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			final io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey key =
				new io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey(null, "test", null);
			assertNull(index.createStoragePart(1, key));
		}

		@Test
		@DisplayName("should return storage part after addRecord")
		void shouldReturnStoragePartAfterAdd() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			index.addRecord("value", 1);
			final io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey key =
				new io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey(null, "test", null);
			assertNotNull(index.createStoragePart(1, key));
		}

		@Test
		@DisplayName("should return storage part after removeRecord")
		void shouldReturnStoragePartAfterRemove() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			index.addRecord("value", 1);
			index.removeRecord("value", 1);
			final io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey key =
				new io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey(null, "test", null);
			assertNotNull(index.createStoragePart(1, key));
		}

		@Test
		@DisplayName("should return null storage part after resetDirty")
		void shouldReturnNullAfterResetDirty() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			index.addRecord("value", 1);
			index.resetDirty();
			final io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey key =
				new io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey(null, "test", null);
			assertNull(index.createStoragePart(1, key));
		}
	}

	@Nested
	@DisplayName("AttributeCardinalityKey record")
	class AttributeCardinalityKeyTest {

		@Test
		@DisplayName("should hold equality for same recordId and value")
		void shouldBeEqualForSameFields() {
			final AttributeCardinalityKey key1 = new AttributeCardinalityKey(1, "test");
			final AttributeCardinalityKey key2 = new AttributeCardinalityKey(1, "test");
			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should not be equal when recordId differs")
		void shouldNotBeEqualWhenRecordIdDiffers() {
			final AttributeCardinalityKey key1 = new AttributeCardinalityKey(1, "test");
			final AttributeCardinalityKey key2 = new AttributeCardinalityKey(2, "test");
			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should not be equal when value differs")
		void shouldNotBeEqualWhenValueDiffers() {
			final AttributeCardinalityKey key1 = new AttributeCardinalityKey(1, "test1");
			final AttributeCardinalityKey key2 = new AttributeCardinalityKey(1, "test2");
			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("toString contains both components separated by colon")
		void shouldFormatToString() {
			final AttributeCardinalityKey key = new AttributeCardinalityKey(42, "hello");
			final String result = key.toString();
			assertTrue(result.contains("42"));
			assertTrue(result.contains("hello"));
			assertTrue(result.contains(":"));
		}
	}

	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("should return new instance with merged add on commit (INV-7)")
		void shouldCommitAddAndReturnNewInstance() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);

			assertStateAfterCommit(
				index,
				original -> {
					original.addRecord("value", 1);
					assertFalse(original.isEmpty());
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertFalse(committed.isEmpty());
					final AttributeCardinalityKey key = new AttributeCardinalityKey(1, "value");
					assertEquals(1, committed.getCardinalities().get(key));
				}
			);
		}

		@Test
		@DisplayName("should return same instance on commit when nothing changed (INV-8)")
		void shouldReturnSameInstanceWhenNotDirty() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);

			assertStateAfterCommit(
				index,
				original -> {
					// no mutations
				},
				Assertions::assertSame
			);
		}

		@Test
		@DisplayName("original should be unchanged after commit (T2)")
		void shouldLeaveOriginalUnchangedAfterCommit() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);

			assertStateAfterCommit(
				index,
				original -> original.addRecord("value", 1),
				(original, committed) -> {
					assertTrue(original.isEmpty());
					assertFalse(committed.isEmpty());
				}
			);
		}

		@Test
		@DisplayName("should commit multiple adds atomically (T5)")
		void shouldCommitMultipleAddsAtomically() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);

			assertStateAfterCommit(
				index,
				original -> {
					original.addRecord("a", 1);
					original.addRecord("b", 2);
					original.addRecord("c", 3);
				},
				(original, committed) -> {
					assertTrue(original.isEmpty());
					assertEquals(3, committed.getCardinalities().size());
				}
			);
		}
	}

	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("should return original state after rollback (T7)")
		void shouldRollbackAddedRecords() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);

			assertStateAfterRollback(
				index,
				original -> {
					original.addRecord("value", 1);
					assertFalse(original.isEmpty());
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isEmpty());
				}
			);
		}

		@Test
		@DisplayName("should not mark index dirty after rollback")
		void shouldNotBeDirtyAfterRollback() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);

			assertStateAfterRollback(
				index,
				original -> original.addRecord("value", 1),
				(original, committed) -> {
					assertNull(committed);
					// dirty flag should be rolled back too
					final io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey key =
						new io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey(null, "test", null);
					assertNull(index.createStoragePart(1, key));
				}
			);
		}
	}

}
