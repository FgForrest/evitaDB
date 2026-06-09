/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level contract tests for the stateless {@link FilterIndexView} flyweight that wraps an
 * {@link AttributeIndex}-owned shared {@link InvertedIndex} tree.
 *
 * The view owns no transactional state of its own; every identity / dirtiness / read decision is delegated to the
 * wrapped shared tree. These tests pin that delegation at the unit level — most importantly that two views over two
 * DISTINCT shared trees report DISTINCT ids. A view's id is the histogram-cache key, so a collapsed (constant) id
 * lets the attribute-histogram cache serve another attribute's records: the precise failure this suite guards against.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(FILTER)
@DisplayName("FilterIndex view contracts")
class FilterIndexViewTest {

	private static final AttributeIndexKey KEY_A = new AttributeIndexKey(null, "a", null);
	private static final AttributeIndexKey KEY_B = new AttributeIndexKey(null, "b", null);

	/**
	 * Builds a fresh shared {@link InvertedIndex} for the given key/type using the very same normalizer/comparator
	 * the production {@link AttributeIndex} wires in for an owned tree.
	 *
	 * @param key  the attribute key driving the comparator
	 * @param type the plain attribute type
	 * @return a fresh, empty shared inverted index
	 */
	@Nonnull
	private static InvertedIndex newSharedTree(@Nonnull AttributeIndexKey key, @Nonnull Class<?> type) {
		return new InvertedIndex(
			FilterIndex.getNormalizer(type),
			FilterIndex.getComparator(key, type)
		);
	}

	/**
	 * Wraps the passed shared tree in a fresh {@link FilterIndexView} for the given key/type.
	 *
	 * @param key    the attribute key
	 * @param shared the shared tree to wrap
	 * @param type   the plain attribute type
	 * @return a fresh view over the shared tree
	 */
	@Nonnull
	private static FilterIndexView newView(
		@Nonnull AttributeIndexKey key,
		@Nonnull InvertedIndex shared,
		@Nonnull Class<?> type
	) {
		return new FilterIndexView(key, shared, null, type);
	}

	@Nested
	@DisplayName("Identity delegation")
	class IdentityTest {

		@Test
		@DisplayName("views over two distinct shared trees report distinct ids")
		void shouldReportDistinctIdsForDistinctSharedTrees() {
			final InvertedIndex sharedA = newSharedTree(KEY_A, Integer.class);
			final InvertedIndex sharedB = newSharedTree(KEY_B, Integer.class);

			final FilterIndexView viewA = newView(KEY_A, sharedA, Integer.class);
			final FilterIndexView viewB = newView(KEY_B, sharedB, Integer.class);

			// THE regression guard: every view's id used to collapse to the constant 1L (the
			// VoidTransactionMemoryProducer default), so the histogram cache served the wrong attribute. Each tree now
			// carries a unique sequence id, so the two views must NOT share one.
			assertNotEquals(
				viewA.getId(), viewB.getId(),
				"distinct shared trees must yield distinct view ids - a collapsed id poisons the histogram cache!"
			);
		}

		@Test
		@DisplayName("a view's id equals the id of the shared tree it wraps")
		void shouldMirrorWrappedTreeId() {
			final InvertedIndex shared = newSharedTree(KEY_A, Integer.class);
			final FilterIndexView view = newView(KEY_A, shared, Integer.class);

			assertEquals(
				shared.getId(), view.getId(),
				"the view must derive its id straight from the wrapped shared tree!"
			);
		}

		@Test
		@DisplayName("a view's id stays equal to the tree's id and stable across a mutation")
		void shouldKeepIdStableAcrossMutation() {
			final InvertedIndex shared = newSharedTree(KEY_A, Integer.class);
			final FilterIndexView view = newView(KEY_A, shared, Integer.class);

			final long idBefore = view.getId();
			assertEquals(shared.getId(), idBefore);

			// a non-transactional add must not allocate a new tree (the instance is carried forward by reference), so
			// the id stays put - this is what keeps the formula cache warm across no-op commits.
			view.addRecord(1, 10);

			assertEquals(idBefore, view.getId(), "the view id must stay stable across a mutation!");
			assertEquals(shared.getId(), view.getId(), "the view id must keep tracking the shared tree's id!");
		}
	}

	@Nested
	@DisplayName("Dirtiness delegation")
	class DirtinessTest {

		@Test
		@DisplayName("a fresh view over an empty tree is not dirty")
		void shouldNotBeDirtyWhenFresh() {
			final InvertedIndex shared = newSharedTree(KEY_A, Integer.class);
			final FilterIndexView view = newView(KEY_A, shared, Integer.class);

			assertFalse(view.isDirty(), "a fresh view over an untouched tree must not be dirty!");
			assertNull(
				view.createStoragePart(1),
				"a clean view must not emit a storage part!"
			);
		}

		@Test
		@DisplayName("a view becomes dirty after an add and resets clean afterwards")
		void shouldDelegateDirtyFlagToSharedTree() {
			final InvertedIndex shared = newSharedTree(KEY_A, Integer.class);
			final FilterIndexView view = newView(KEY_A, shared, Integer.class);

			view.addRecord(1, 10);

			assertTrue(view.isDirty(), "adding a record must flip the wrapped tree's dirty flag!");
			final StoragePart partWhileDirty = view.createStoragePart(1);
			assertNotNull(partWhileDirty, "a dirty view must emit a storage part!");

			view.resetDirty();

			assertFalse(view.isDirty(), "resetDirty must clear the wrapped tree's dirty flag!");
			assertNull(
				view.createStoragePart(1),
				"a view reset to clean must stop emitting a storage part!"
			);
		}
	}

	@Nested
	@DisplayName("Read delegation through the shared tree")
	class ReadDelegationTest {

		@Test
		@DisplayName("records written straight to the shared tree are visible through the view")
		void shouldReadRecordsWrittenToSharedTree() {
			final InvertedIndex shared = newSharedTree(KEY_A, Integer.class);
			final FilterIndexView view = newView(KEY_A, shared, Integer.class);

			// write directly to the shared tree (bypassing the view), as the FILTER block of the coarse orchestration does
			shared.addRecord(10, 1);
			shared.addRecord(10, 2);
			shared.addRecord(20, 3);

			final Bitmap equalToTen = view.getRecordsEqualTo(10);
			assertArrayContainsExactly(equalToTen, 1, 2);
			assertArrayContainsExactly(view.getRecordsEqualTo(20), 3);
			assertArrayContainsExactly(view.getAllRecords(), 1, 2, 3);
			assertEquals(3, view.size(), "size() must reflect the total record count of the shared tree!");
		}

		@Test
		@DisplayName("records written through the view are visible in the shared tree")
		void shouldWriteRecordsVisibleInSharedTree() {
			final InvertedIndex shared = newSharedTree(KEY_A, Integer.class);
			final FilterIndexView view = newView(KEY_A, shared, Integer.class);

			view.addRecord(1, 10);
			view.addRecord(2, 10);
			view.addRecord(3, 20);

			// the view holds no state of its own - the writes must land in the wrapped shared tree
			assertArrayContainsExactly(shared.getRecordsEqualTo(10), 1, 2);
			assertArrayContainsExactly(shared.getRecordsEqualTo(20), 3);
		}
	}

	@Nested
	@DisplayName("Sealed hierarchy typing")
	class SealedTypingTest {

		@Test
		@DisplayName("a view is a FilterIndex but not an OwnerFilterIndex")
		void shouldBeFilterIndexButNotOwner() {
			final InvertedIndex shared = newSharedTree(KEY_A, Integer.class);
			final FilterIndex view = newView(KEY_A, shared, Integer.class);

			assertInstanceOf(FilterIndex.class, view, "a view must be a FilterIndex!");
			assertFalse(
				view instanceof OwnerFilterIndex,
				"a view must NOT be an OwnerFilterIndex - it owns no transactional state!"
			);
		}
	}

	/**
	 * Asserts the bitmap contains exactly the expected record ids in ascending (bitmap) order.
	 *
	 * @param actual   the bitmap under test
	 * @param expected the record ids that must be present, in order
	 */
	private static void assertArrayContainsExactly(@Nonnull Bitmap actual, int... expected) {
		assertArrayEquals(
			expected, actual.getArray(),
			"bitmap contents diverged from the expected record ids!"
		);
	}
}
