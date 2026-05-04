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

package io.evitadb.index.facet;

import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.FACET;

/**
 * Tests for {@link FacetIdIndex} covering construction, non-transactional operations,
 * STM commit/rollback semantics, and invariant verification.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetIdIndex")
@Tag(INDEXING)
@Tag(FACET)
class FacetIdIndexTest {

	@Nested
	@DisplayName("Constructor")
	class ConstructorTest {

		@Test
		@DisplayName("should create empty index with facetId-only constructor")
		void shouldCreateEmptyIndex() {
			final FacetIdIndex index = new FacetIdIndex(42);
			assertTrue(index.isEmpty());
			assertEquals(0, index.size());
			assertEquals(42, index.getFacetId());
		}

		@Test
		@DisplayName("should create index with supplied bitmap")
		void shouldCreateIndexWithBitmap() {
			final FacetIdIndex index = new FacetIdIndex(42, new BaseBitmap(1, 2, 3));
			assertFalse(index.isEmpty());
			assertEquals(3, index.size());
			assertEquals(42, index.getFacetId());
			assertTrue(index.getRecords().contains(1));
			assertTrue(index.getRecords().contains(2));
			assertTrue(index.getRecords().contains(3));
		}
	}

	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("addFacet returns true for new entity ID")
		void shouldReturnTrueForNewEntityId() {
			final FacetIdIndex index = new FacetIdIndex(1);
			assertTrue(index.addFacet(100));
		}

		@Test
		@DisplayName("addFacet returns false for duplicate entity ID")
		void shouldReturnFalseForDuplicateEntityId() {
			final FacetIdIndex index = new FacetIdIndex(1);
			index.addFacet(100);
			assertFalse(index.addFacet(100));
		}

		@Test
		@DisplayName("removeFacet returns true for existing entity ID")
		void shouldReturnTrueForExistingEntityId() {
			final FacetIdIndex index = new FacetIdIndex(1);
			index.addFacet(100);
			assertTrue(index.removeFacet(100));
		}

		@Test
		@DisplayName("removeFacet returns false for non-present entity ID")
		void shouldReturnFalseForNonPresentEntityId() {
			final FacetIdIndex index = new FacetIdIndex(1);
			assertFalse(index.removeFacet(999));
		}

		@Test
		@DisplayName("size reflects number of added entity IDs")
		void shouldReflectCorrectSize() {
			final FacetIdIndex index = new FacetIdIndex(1);
			assertEquals(0, index.size());
			index.addFacet(10);
			assertEquals(1, index.size());
			index.addFacet(20);
			assertEquals(2, index.size());
			index.addFacet(30);
			assertEquals(3, index.size());
		}

		@Test
		@DisplayName("isEmpty transitions correctly through add/remove lifecycle")
		void shouldTrackEmptinessCorrectly() {
			final FacetIdIndex index = new FacetIdIndex(1);
			assertTrue(index.isEmpty());
			index.addFacet(100);
			assertFalse(index.isEmpty());
			index.removeFacet(100);
			assertTrue(index.isEmpty());
		}
	}

	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("commits addFacet and preserves original (T1, T2)")
		void shouldCommitAddFacet() {
			final FacetIdIndex index = new FacetIdIndex(1, new BaseBitmap(10));

			assertStateAfterCommit(
				index,
				original -> {
					original.addFacet(20);
					assertTrue(original.getRecords().contains(20));
				},
				(original, committed) -> {
					// Original unchanged (T2)
					assertEquals(1, original.size());
					assertTrue(original.getRecords().contains(10));
					assertFalse(original.getRecords().contains(20));
					// Committed has change
					assertEquals(2, committed.size());
					assertTrue(committed.getRecords().contains(10));
					assertTrue(committed.getRecords().contains(20));
				}
			);
		}

		@Test
		@DisplayName("commits removeFacet and preserves original")
		void shouldCommitRemoveFacet() {
			final FacetIdIndex index = new FacetIdIndex(1, new BaseBitmap(10, 20));

			assertStateAfterCommit(
				index,
				original -> {
					original.removeFacet(10);
					assertFalse(original.getRecords().contains(10));
				},
				(original, committed) -> {
					// Original unchanged
					assertEquals(2, original.size());
					assertTrue(original.getRecords().contains(10));
					// Committed has change
					assertEquals(1, committed.size());
					assertFalse(committed.getRecords().contains(10));
					assertTrue(committed.getRecords().contains(20));
				}
			);
		}

		@Test
		@DisplayName("committed copy is a NEW instance (INV-7)")
		void shouldReturnNewInstanceOnCommit() {
			final FacetIdIndex index = new FacetIdIndex(1);

			assertStateAfterCommit(
				index,
				original -> original.addFacet(100),
				(original, committed) -> assertNotSame(original, committed)
			);
		}

		@Test
		@DisplayName("nested records bitmap properly merged (INV-6)")
		void shouldMergeNestedRecordsBitmap() {
			final FacetIdIndex index = new FacetIdIndex(5, new BaseBitmap(1, 2, 3));

			assertStateAfterCommit(
				index,
				original -> {
					original.addFacet(4);
					original.addFacet(5);
					original.removeFacet(2);
				},
				(original, committed) -> {
					// Original unchanged
					assertEquals(3, original.size());
					assertTrue(original.getRecords().contains(2));
					// Committed reflects all changes atomically
					assertEquals(4, committed.size());
					assertTrue(committed.getRecords().contains(1));
					assertFalse(committed.getRecords().contains(2));
					assertTrue(committed.getRecords().contains(3));
					assertTrue(committed.getRecords().contains(4));
					assertTrue(committed.getRecords().contains(5));
				}
			);
		}

		@Test
		@DisplayName("handles null layer (always null for VoidTransactionMemoryProducer) (INV-8)")
		void shouldHandleNullLayerOnCommit() {
			// VoidTransactionMemoryProducer always receives null layer —
			// this is exercised by every commit test above, but we verify explicitly
			// that a no-op transaction still produces a valid committed copy
			final FacetIdIndex index = new FacetIdIndex(1, new BaseBitmap(10));

			assertStateAfterCommit(
				index,
				original -> {
					// no mutations — the layer parameter will still be null (Void)
				},
				(original, committed) -> {
					// committed copy is still a new instance (FacetIdIndex always creates new)
					assertNotSame(original, committed);
					assertEquals(1, committed.size());
					assertTrue(committed.getRecords().contains(10));
				}
			);
		}
	}

	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("rolled-back addFacet is not visible (T7)")
		void shouldRollbackAddFacet() {
			final FacetIdIndex index = new FacetIdIndex(1, new BaseBitmap(10));

			assertStateAfterRollback(
				index,
				original -> {
					original.addFacet(20);
					assertTrue(original.getRecords().contains(20));
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals(1, original.size());
					assertTrue(original.getRecords().contains(10));
					assertFalse(original.getRecords().contains(20));
				}
			);
		}

		@Test
		@DisplayName("removeLayer cleans transactional layer for records (INV-5)")
		void shouldCleanLayerOnRollback() {
			// Rollback implicitly calls removeLayer on all nested producers
			final FacetIdIndex index = new FacetIdIndex(1, new BaseBitmap(10, 20));

			assertStateAfterRollback(
				index,
				original -> {
					original.addFacet(30);
					original.removeFacet(10);
				},
				(original, committed) -> {
					assertNull(committed);
					// All changes reverted — original is clean
					assertEquals(2, original.size());
					assertTrue(original.getRecords().contains(10));
					assertTrue(original.getRecords().contains(20));
					assertFalse(original.getRecords().contains(30));
				}
			);
		}
	}

	@Nested
	@DisplayName("Other")
	class OtherTest {

		@Test
		@DisplayName("toString has format '<facetId>: <records>'")
		void shouldFormatToString() {
			final FacetIdIndex index = new FacetIdIndex(42, new BaseBitmap(1, 2, 3));
			final String result = index.toString();
			assertTrue(result.startsWith("42: "), "Expected toString to start with '42: ' but was: " + result);
		}
	}
}
