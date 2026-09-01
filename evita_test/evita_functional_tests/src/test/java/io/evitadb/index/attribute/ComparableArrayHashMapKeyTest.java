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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link ComparableArray} used as a {@link HashMap} key.
 *
 * `HashMap` converts a bin holding at least `TREEIFY_THRESHOLD` (8) entries into a red-black tree once the table has
 * grown to at least `MIN_TREEIFY_CAPACITY` (64). Navigating that tree uses the key's **natural order** whenever two
 * keys tie on hash — `HashMap.comparableClassFor` accepts any class declaring `Comparable<itself>`, which
 * {@link ComparableArray} does. A `compareTo` that refuses to answer therefore turns an ordinary map lookup into
 * a hard failure, and only on datasets large enough to treeify a bin — which is why the whole test suite stayed
 * green while a real catalog could not be loaded at all.
 *
 * The sink exercised here is {@link SortIndexStoragePart}'s map-based constructor, which folds the sparse
 * `value → cardinality` map into the persisted columns with one `Map.get` per distinct value. It is the common entry
 * point of every de-serialization path (the current serializer plus the backward-compatible readers) and of the
 * storage-protocol migration, so a compound (multi-element) sortable attribute with enough distinct values reaches it
 * on a plain catalog load.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("ComparableArray survives use as a treeified HashMap key")
class ComparableArrayHashMapKeyTest {

	private static final AttributeIndexKey ATTRIBUTE_KEY =
		new AttributeIndexKey(null, "defaultSortingCoefficientCompound", null);

	/**
	 * Two-element compound of `Integer`s, mirroring the shape that failed in production (a compound sortable attribute
	 * whose distinct values outnumber the treeify threshold within a single hash bin).
	 */
	private static final ComparatorSource[] COMPARATOR_BASE = {
		new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
		new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
	};

	/** Number of filler values inserted first, purely to grow the table past `MIN_TREEIFY_CAPACITY`. */
	private static final int FILLER_COUNT = 120;
	/** Number of values sharing one identical hash — must exceed `TREEIFY_THRESHOLD` (8) to convert the bin. */
	private static final int COLLIDING_COUNT = 10;

	/**
	 * Builds a value whose {@link java.util.Arrays#hashCode(Object[])} is `961 + 31 * first + second`. Shifting `first`
	 * up by one while shifting `second` down by 31 therefore yields a *different* value with an *identical* hash, which
	 * is what forces every member of the colliding family into the same bin regardless of table size.
	 */
	@Nonnull
	private static ComparableArray value(int first, int second) {
		return new ComparableArray(new Serializable[]{first, second});
	}

	/**
	 * Reproduces the production failure: a sparse cardinality map big enough to treeify the bin holding the colliding
	 * family, folded by the map-based {@link SortIndexStoragePart} constructor. Before the fix this throws
	 * `ComparableArray must be ordered through the SortIndex comparator, never its natural order!` from
	 * `HashMap.compareComparables`; afterwards the columns are folded correctly.
	 */
	@Test
	@DisplayName("folding a treeified sparse cardinality map does not fail")
	void shouldFoldCardinalityMapWithTreeifiedBin() {
		// distinct values in ascending element-wise order, so the part is well formed; the colliding family is placed
		// last so the fillers have already grown the table beyond MIN_TREEIFY_CAPACITY when the collisions arrive
		final Serializable[] sortedRecordsValues = new Serializable[FILLER_COUNT + COLLIDING_COUNT];
		final Map<Serializable, Integer> valueCardinalities = new HashMap<>();

		for (int i = 0; i < FILLER_COUNT; i++) {
			// spread across distinct buckets - these only exist to grow the table
			final ComparableArray filler = value(i, i * 7);
			sortedRecordsValues[i] = filler;
			valueCardinalities.put(filler, 2);
		}
		for (int i = 0; i < COLLIDING_COUNT; i++) {
			// 31 * (first + 1) + (second - 31) == 31 * first + second -> identical Arrays.hashCode
			final ComparableArray colliding = value(1_000 + i, 50_000 - 31 * i);
			sortedRecordsValues[FILLER_COUNT + i] = colliding;
			valueCardinalities.put(colliding, 2);
		}

		// every distinct value is shared by exactly two records
		final int totalRecords = sortedRecordsValues.length * 2;
		final int[] sortedRecords = new int[totalRecords];
		for (int i = 0; i < totalRecords; i++) {
			sortedRecords[i] = i + 1;
		}

		// guard the test itself: without a genuinely treeified bin this exercises the plain linked-list path and would
		// pass even against the unfixed code
		assertTrue(
			hasTreeifiedBin(valueCardinalities),
			"The cardinality map has no treeified bin - the test would not exercise HashMap's comparison path!"
		);

		final SortIndexStoragePart part = new SortIndexStoragePart(
			1, ATTRIBUTE_KEY, COMPARATOR_BASE,
			sortedRecords, sortedRecordsValues, valueCardinalities, 0, 1L
		);

		assertArrayHasEveryValue(part.getCardinalityValues(), sortedRecordsValues);
		for (final int cardinality : part.getCardinalities()) {
			assertEquals(2, cardinality, "Every distinct value was shared by exactly two records!");
		}
	}

	/**
	 * A direct probe of the same JDK mechanism, independent of any evitaDB storage type: a lookup that *misses* on a
	 * treeified bin traverses the whole tree and consults the natural order on every hash tie.
	 */
	@Test
	@DisplayName("a missing-key lookup on a treeified bin does not fail")
	void shouldTolerateMissingKeyLookupOnTreeifiedBin() {
		final Map<Serializable, Integer> map = new HashMap<>();
		for (int i = 0; i < FILLER_COUNT; i++) {
			map.put(value(i, i * 7), 1);
		}
		for (int i = 0; i < COLLIDING_COUNT; i++) {
			map.put(value(1_000 + i, 50_000 - 31 * i), 1);
		}
		assertTrue(hasTreeifiedBin(map), "The map has no treeified bin - the probe would not reach the tree path!");

		// a key absent from the map but hashing into the treeified bin - the production probe was exactly this shape
		assertNull(map.get(value(1_000 + COLLIDING_COUNT, 50_000 - 31 * COLLIDING_COUNT)));
	}

	/**
	 * Reports whether the map holds at least one bin converted to a red-black tree, by reflecting over the internal
	 * table. Without this the test could silently degrade into the linked-list path and stop protecting anything.
	 */
	private static boolean hasTreeifiedBin(@Nonnull Map<Serializable, Integer> map) {
		try {
			final java.lang.reflect.Field tableField = HashMap.class.getDeclaredField("table");
			tableField.setAccessible(true);
			final Object[] table = (Object[]) tableField.get(map);
			if (table == null) {
				return false;
			}
			final Class<?> treeNodeType = Class.forName("java.util.HashMap$TreeNode");
			for (final Object bin : table) {
				if (bin != null && treeNodeType.isInstance(bin)) {
					return true;
				}
			}
			return false;
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Cannot inspect the HashMap table layout!", ex);
		}
	}

	/**
	 * Asserts the folded cardinality column carries exactly the expected distinct values, in the order they appear in
	 * `sortedRecordsValues` (the storage convention the serializer's two-pointer merge relies on).
	 */
	private static void assertArrayHasEveryValue(
		@Nonnull Serializable[] actual,
		@Nonnull Serializable[] expected
	) {
		assertEquals(expected.length, actual.length, "Every value had cardinality 2, so none may be dropped!");
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], actual[i], "Cardinality columns must stay aligned with the sorted values!");
		}
	}

}
