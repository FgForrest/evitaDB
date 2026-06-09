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

import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Proves that a both-flagged (filterable + sortable) {@link SortIndex} running in **view mode** over an
 * {@link AttributeIndex}-owned shared {@link InvertedIndex} produces a record order IDENTICAL to a pure owner-mode
 * {@link SortIndex} fed the same operations.
 *
 * Each scenario drives the indexes in the same order the real {@link io.evitadb.index.EntityIndex} coarse
 * orchestration does: the SORT block (the view {@link SortIndex}) runs FIRST — reading the shared tree in its
 * pre-mutation state — and the FILTER block (shared tree) mutates the tree AFTERWARDS. Because the view reads the
 * pristine tree, it observes exactly the same pre-mutation `sortedRecords` layout owner mode does, with no in-flight
 * compensation. The owner-mode reference index receives only the sort operation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
@DisplayName("SortIndex view mode")
class SortIndexViewModeTest {

	private static final AttributeIndexKey KEY = new AttributeIndexKey(null, "a", null);

	/**
	 * Drives a paired owner-mode and view-mode {@link SortIndex} through the same sequence of inserts/removals and
	 * asserts their `sortedRecords` and both directional orders stay identical at every step.
	 */
	private static final class Harness {
		private final SortIndex owner;
		private final InvertedIndex shared;
		private final SortIndex view;
		private final java.util.function.Function<Object, java.io.Serializable> sharedNormalizer;

		Harness(@Nonnull Class<?> type) {
			this(type, KEY);
		}

		Harness(@Nonnull Class<?> type, @Nonnull AttributeIndexKey key) {
			this.owner = new OwnerSortIndex(type, null, key);
			this.sharedNormalizer = FilterIndex.getNormalizer(type);
			this.shared = new InvertedIndex(
				this.sharedNormalizer,
				FilterIndex.getComparator(key, type)
			);
			final Supplier<InvertedIndex> supplier = () -> this.shared;
			this.view = SortIndex.create(type, null, key, supplier);
			// the factory must route a present shared tree to a view and an absent one to an owner
			assertInstanceOf(OwnerSortIndex.class, this.owner, "owner-mode factory must produce an OwnerSortIndex!");
			assertInstanceOf(SortIndexView.class, this.view, "view-mode factory must produce a SortIndexView!");
		}

		/**
		 * Applies an insert in coarse-orchestration order: the SORT block runs first (the view reads the pristine
		 * shared tree), then the FILTER block mutates the shared tree.
		 */
		void insert(@Nonnull Serializable value, int recordId) {
			// owner mode: only the sort block
			this.owner.addRecord(value, recordId);
			// view mode: SORT block runs BEFORE the FILTER block writes the shared bucket (pristine-tree read)
			this.view.addRecord(value, recordId);
			this.shared.addRecord(value, recordId);
			assertConsistent();
		}

		/**
		 * Applies a removal in coarse-orchestration order: the SORT block runs first (the view reads the tree while
		 * the record is still present), then the FILTER block removes it from the shared tree.
		 */
		void remove(@Nonnull Serializable value, int recordId) {
			this.owner.removeRecord(value, recordId);
			this.view.removeRecord(value, recordId);
			this.shared.removeRecord(value, recordId);
			assertConsistent();
		}

		/**
		 * Asserts the view-mode index is bit-for-bit equivalent to the owner-mode reference.
		 */
		void assertConsistent() {
			assertArrayEquals(
				this.owner.getSortedRecords(), this.view.getSortedRecords(),
				"sortedRecords diverged between owner and view mode!"
			);
			// the distinct value ORDER must match, but the two modes may store values in different normalization spaces
			// (owner keeps OffsetDateTime; the view stores the shared tree's Instant). Compare the view's stored values
			// against the owner's values mapped through the SHARED normalizer so the cross-space cases (OffsetDateTime,
			// localized String) are still asserted meaningfully.
			final Serializable[] ownerValues = this.owner.getSortedRecordValues();
			final Serializable[] ownerValuesInSharedSpace = new Serializable[ownerValues.length];
			for (int i = 0; i < ownerValues.length; i++) {
				ownerValuesInSharedSpace[i] = this.sharedNormalizer.apply(ownerValues[i]);
			}
			assertArrayEquals(
				ownerValuesInSharedSpace, this.view.getSortedRecordValues(),
				"distinct value ordering diverged between owner and view mode!"
			);
			// both directional suppliers must yield identical record id orders
			assertArrayEquals(
				this.owner.getAscendingOrderRecordsSupplier().getSortedRecordIds(),
				this.view.getAscendingOrderRecordsSupplier().getSortedRecordIds(),
				"ascending order diverged between owner and view mode!"
			);
			assertArrayEquals(
				this.owner.getDescendingOrderRecordsSupplier().getSortedRecordIds(),
				this.view.getDescendingOrderRecordsSupplier().getSortedRecordIds(),
				"descending order diverged between owner and view mode!"
			);
		}
	}

	@Test
	@DisplayName("insert into an existing value block keeps order identical to owner mode")
	void shouldInsertIntoExistingValueBlock() {
		final Harness h = new Harness(Integer.class);
		h.insert(10, 1);
		h.insert(20, 2);
		h.insert(30, 3);
		// second record sharing the value 20 - inserted into an EXISTING block (the off-by-one hazard)
		h.insert(20, 4);
		h.insert(20, 5);
		h.insert(10, 6);
	}

	@Test
	@DisplayName("insert a brand-new minimum value keeps order identical to owner mode")
	void shouldInsertNewMinimum() {
		final Harness h = new Harness(Integer.class);
		h.insert(50, 1);
		h.insert(40, 2);
		h.insert(30, 3);
		// brand-new minimum: shared bucket has cardinality 1, view must treat it as absent for predecessor computation
		h.insert(10, 4);
		h.insert(5, 5);
	}

	@Test
	@DisplayName("insert a brand-new middle value keeps order identical to owner mode")
	void shouldInsertNewMiddle() {
		final Harness h = new Harness(Integer.class);
		h.insert(10, 1);
		h.insert(30, 2);
		h.insert(50, 3);
		// brand-new middle values
		h.insert(20, 4);
		h.insert(40, 5);
		h.insert(25, 6);
	}

	@Test
	@DisplayName("insert a brand-new maximum value keeps order identical to owner mode")
	void shouldInsertNewMaximum() {
		final Harness h = new Harness(Integer.class);
		h.insert(10, 1);
		h.insert(20, 2);
		h.insert(30, 3);
		h.insert(100, 4);
		h.insert(200, 5);
	}

	@Test
	@DisplayName("interleaved inserts across new and existing blocks keep order identical")
	void shouldHandleInterleavedInserts() {
		final Harness h = new Harness(Integer.class);
		h.insert(20, 1);
		h.insert(10, 2);
		h.insert(20, 3);
		h.insert(30, 4);
		h.insert(10, 5);
		h.insert(25, 6);
		h.insert(20, 7);
		h.insert(5, 8);
		h.insert(30, 9);
	}

	@Test
	@DisplayName("removals (block shrink and full removal) keep order identical")
	void shouldHandleRemovals() {
		final Harness h = new Harness(Integer.class);
		h.insert(10, 1);
		h.insert(20, 2);
		h.insert(20, 3);
		h.insert(20, 4);
		h.insert(30, 5);
		h.insert(10, 6);
		// shrink the 20-block (cardinality 3 -> 2 -> 1) and remove single-record blocks
		h.remove(20, 3);
		h.remove(30, 5);
		h.remove(20, 2);
		h.remove(20, 4);
		h.remove(10, 1);
		h.remove(10, 6);
	}

	@Test
	@DisplayName("string view mode (NFD normalized) keeps order identical to owner mode")
	void shouldHandleStringValues() {
		final Harness h = new Harness(String.class);
		h.insert("banana", 1);
		h.insert("apple", 2);
		h.insert("cherry", 3);
		h.insert("apple", 4);
		h.insert("banana", 5);
		h.insert("avocado", 6);
		h.remove("apple", 2);
		h.remove("cherry", 3);
	}

	@Test
	@DisplayName("interleaved insert/remove churn keeps order identical to owner mode")
	void shouldHandleChurn() {
		final Harness h = new Harness(Integer.class);
		h.insert(5, 1);
		h.insert(5, 2);
		h.insert(3, 3);
		h.remove(5, 1);
		h.insert(7, 4);
		h.insert(3, 5);
		h.remove(3, 3);
		h.insert(5, 6);
		h.insert(1, 7);
		h.remove(7, 4);
		h.insert(3, 8);
	}

	@Test
	@DisplayName("view-mode cardinality reads match the shared tree at rest")
	void shouldReportCardinalityFromSharedTree() {
		final Harness h = new Harness(Integer.class);
		h.insert(10, 1);
		h.insert(10, 2);
		h.insert(10, 3);
		h.insert(20, 4);
		// at rest (no in-flight mutation) the view cardinality equals the shared bucket size
		assertEquals(3, h.view.getValueCardinality(10));
		assertEquals(1, h.view.getValueCardinality(20));
		assertEquals(3, h.owner.getValueCardinality(10));
		assertEquals(1, h.owner.getValueCardinality(20));
	}

	@Test
	@DisplayName("OffsetDateTime view mode (shared Instant space) keeps order identical to owner mode")
	void shouldHandleOffsetDateTimeValues() {
		// the regression type: the shared tree normalizes OffsetDateTime → Instant (FilterIndex.getNormalizer), while the
		// owner-mode SortIndex keeps OffsetDateTime. View mode must operate entirely in the shared Instant space
		// — a mismatch previously threw ClassCastException(Instant→OffsetDateTime).
		final Harness h = new Harness(OffsetDateTime.class);
		final OffsetDateTime t10 = OffsetDateTime.parse("2026-01-10T10:00:00Z");
		final OffsetDateTime t20 = OffsetDateTime.parse("2026-01-20T10:00:00Z");
		final OffsetDateTime t30 = OffsetDateTime.parse("2026-01-30T10:00:00Z");
		final OffsetDateTime t05 = OffsetDateTime.parse("2026-01-05T10:00:00Z");
		final OffsetDateTime t25 = OffsetDateTime.parse("2026-01-25T10:00:00Z");

		h.insert(t10, 1);          // existing block / min
		h.insert(t30, 2);          // max
		h.insert(t20, 3);          // middle (new)
		h.insert(t20, 4);          // existing block (shared cardinality bump)
		h.insert(t05, 5);          // new min
		h.insert(t25, 6);          // new middle
		h.insert(t20, 7);          // existing block again
		h.remove(t20, 3);          // shrink the t20 block
		h.remove(t30, 2);          // remove max single-record block
		h.remove(t05, 5);          // remove min single-record block
	}

	@Test
	@DisplayName("localized String view mode (NFD space) keeps order identical to owner mode")
	void shouldHandleLocalizedStringValues() {
		// localized String: the shared tree normalizes to NFD and orders with a LocalizedStringComparator; view mode must
		// adopt that shared normalizer + comparator
		final AttributeIndexKey localizedKey = new AttributeIndexKey(null, "a", new Locale("cs"));
		final Harness h = new Harness(String.class, localizedKey);
		h.insert("cena", 1);
		h.insert("auto", 2);
		h.insert("čaj", 3);   // Czech collation: č sorts after c
		h.insert("auto", 4);  // existing block
		h.insert("banka", 5); // new middle
		h.insert("ananas", 6);// new min
		h.insert("čaj", 7);   // existing block
		h.remove("auto", 2);
		h.remove("cena", 1);
	}
}
