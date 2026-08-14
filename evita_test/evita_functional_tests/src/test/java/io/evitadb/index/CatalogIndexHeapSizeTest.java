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

package io.evitadb.index;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.index.IndexHeapSizeAssertions.AUTOBOX_CACHE_CEILING;
import static io.evitadb.index.IndexHeapSizeAssertions.assertExceedsMeasuredHeapBy;
import static io.evitadb.index.IndexHeapSizeAssertions.assertMatchesMeasuredHeap;
import static io.evitadb.index.IndexHeapSizeAssertions.excluded;
import static io.evitadb.index.IndexHeapSizeAssertions.measuredHeapOf;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the heap arithmetic of {@link CatalogIndex} - the catalog's own index, one per {@link Scope} - against what
 * JOL actually finds on the heap.
 *
 * The catalog index is the shallowest of the index roots: a key, a dirty latch and a map of {@link GlobalUniqueIndex}
 * instances, each of which prices itself and is pinned by `LeafIndexHeapSizeTest`. What is worth pinning *here* is
 * therefore not the children but the two ownership rulings above them, because getting either wrong shifts every
 * catalog's figure by a term nobody else charges:
 *
 * - the map **keys** are charged as bare records. An {@link AttributeKey}'s name comes from the catalog schema and its
 *   locale is JVM-interned, so neither belongs to this index - and the very same key instance is also the
 *   `attributeKey` field a {@link GlobalUniqueIndex} deliberately does *not* charge, which makes this index its sole
 *   owner rather than its second one;
 * - {@link CatalogIndexKey} is charged as the record object alone, because the {@link Scope} inside it is an enum
 *   constant shared by the whole JVM.
 *
 * A `HashMap` keeps the `keySet`/`values`/`entrySet` view it hands out, so an accessor asked for on a construction or
 * flush path is sixteen retained bytes that the arithmetic cannot see - it would have to call the very accessor that
 * creates one. {@link #shouldNotAccumulateCachedViewsOnFlush} is what holds that line for this index: every walk on
 * those paths goes through `forEach`, and one added later that asks for an accessor instead reappears there as a
 * shortfall.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(MANAGEMENT)
@DisplayName("Catalog index heap size")
class CatalogIndexHeapSizeTest {

	private static final String ENTITY_TYPE = "Product";

	/**
	 * How far one child's value tree may widen the gap when it grows from one leaf block to several, in bytes.
	 *
	 * Taken from `LeafIndexHeapSizeTest`, which bounds a single global unique index's separator-key over-report the
	 * same way. Multiplied by the number of children a fixture holds, since each carries its own tree.
	 */
	private static final long LEAF_GAP_ALLOWANCE = 8L * 16;

	/**
	 * Everything a catalog index reaches but does not charge: the JVM-shared scope constant inside its key, and the
	 * value-copier lambda its transactional map holds - which JOL cannot walk at all, because a lambda is a hidden
	 * class.
	 */
	private static final String[] CATALOG_EXCLUSIONS = {
		"indexKey.scope", "uniqueIndex.transactionalLayerWrapper"
	};

	/**
	 * Everything a {@link GlobalUniqueIndex} reaches but does not charge, mirroring `LeafIndexHeapSizeTest` - minus
	 * `attributeKey`, which is deliberately *not* excluded here.
	 *
	 * That key is the same instance the catalog index files the child under, and the catalog index charges it. Naming
	 * it here would subtract from the walk an object the arithmetic bills, leaving the measurement one record per
	 * attribute short of the reported figure - a shortfall that would look exactly like an under-charge.
	 */
	private static final String[] CHILD_EXCLUSIONS = {
		"comparator", "pageStreamRegistry", "scope",
		"tree.valueColumnFactory", "tree.recordColumnFactory",
		"entitiesPerType.transactionalLayerWrapper"
	};

	/**
	 * Resolves everything to one entity type, whose primary key is seeded past the autobox cache for the reason
	 * `IndexHeapSizeAssertions#AUTOBOX_CACHE_CEILING` gives: a global unique index boxes it as a map key, and inside
	 * the cache that box is the JVM's rather than the index's.
	 */
	private static final EntityTypeClassifierResolver RESOLVER = new EntityTypeClassifierResolver() {
		@Override
		public int toEntityTypePrimaryKey(@Nonnull String entityType) {
			return AUTOBOX_CACHE_CEILING;
		}

		@Nonnull
		@Override
		public String toEntityTypeName(int entityTypePrimaryKey) {
			return ENTITY_TYPE;
		}
	};

	@Test
	@DisplayName("an empty catalog index is measured exactly")
	void shouldMeasureAnEmptyCatalogIndexExactly() {
		final CatalogIndex index = new CatalogIndex(Scope.LIVE);
		assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, CATALOG_EXCLUSIONS);
	}

	@Test
	@DisplayName("the archived index costs exactly what the live one does")
	void shouldChargeBothScopesAlike() {
		// the scope is a shared enum constant either way, so a catalog that has archived something must not pay more
		// for holding a second index than the arithmetic says it does
		final CatalogIndex live = new CatalogIndex(Scope.LIVE);
		final CatalogIndex archived = new CatalogIndex(Scope.ARCHIVED);
		assertMatchesMeasuredHeap(archived.getHeapSizeInBytes(), archived, CATALOG_EXCLUSIONS);
		assertEquals(live.getHeapSizeInBytes(), archived.getHeapSizeInBytes());
	}

	@Test
	@DisplayName("a single-leaf seeded index sits exactly one boxed locale id above the measurement")
	void shouldSitExactlyOneBoxedLocaleIdAboveTheMeasurement() {
		// The one divergence a seeded catalog index carries at this size is not its own: a global unique index over a
		// *localized* attribute files each locale id in both directions - the same `Integer` instance is a value in one
		// map and a key in the other - so a walk, which dedupes by identity, counts one box where the arithmetic, which
		// charges per holder, counts two. `LeafIndexHeapSizeTest` pins that as one box per locale and no more; the
		// fixture here holds exactly one localized attribute over exactly one locale, so the whole gap is one box.
		//
		// Two hundred values keep each child's value tree inside a single leaf block, which is what makes an exact
		// assertion available at all - see the test below for what happens once they do not.
		final CatalogIndex index = seeded(3, 200);
		assertExceedsMeasuredHeapBy(
			index.getHeapSizeInBytes(), VMLayout.current().sizeOfObject(Integer.BYTES),
			index, borrowedRoots(index), CATALOG_EXCLUSIONS
		);
	}

	@Test
	@DisplayName("the gap tracks its children's leaves, not their values")
	void shouldTrackItsChildrensLeavesNotTheirValues() {
		// Past one leaf block a global unique index's value tree over-reports by its separator keys, which is a term
		// that tracks *leaves*. That is the children's, pinned by `LeafIndexHeapSizeTest`, and it is the reason the
		// assertion above cannot simply be repeated at a larger size. What has to hold here is that the catalog index
		// adds no term of its own on top: four times the values must widen the gap by no more than the leaves of its
		// three children can account for, rather than in proportion to the values themselves.
		final CatalogIndex small = seeded(3, 200);
		final CatalogIndex large = seeded(3, 800);
		final long smallGap = small.getHeapSizeInBytes() -
			measuredHeapOf(small, borrowedRoots(small), CATALOG_EXCLUSIONS);
		final long largeGap = large.getHeapSizeInBytes() -
			measuredHeapOf(large, borrowedRoots(large), CATALOG_EXCLUSIONS);

		// never below the measurement: an over-report is a deliberate choice, an under-report is a wrong answer
		assertTrue(
			smallGap >= 0 && largeGap >= 0,
			"the arithmetic must never read low - " + smallGap + ", " + largeGap
		);
		assertTrue(
			largeGap < smallGap + 3L * LEAF_GAP_ALLOWANCE,
			"the gap must track leaves, not values - " + smallGap + " to " + largeGap
		);
	}

	@Test
	@DisplayName("flushing leaves no cached map view behind")
	void shouldNotAccumulateCachedViewsOnFlush() {
		final CatalogIndex index = seeded(3, 50);
		final long beforeFlush = measuredHeapOf(index, borrowedRoots(index), CATALOG_EXCLUSIONS);

		// this is the path that used to ask the map for its `keySet` - once per walk, plus once more to build the
		// storage part. Each accessor a `HashMap` hands out is parked on the map forever, and the arithmetic cannot
		// see it: it would have to call the very accessor that creates one. Measuring is therefore the only way to
		// catch it, which is what this does
		index.getModifiedStorageParts(new TrappedChanges());

		assertEquals(
			beforeFlush, measuredHeapOf(index, borrowedRoots(index), CATALOG_EXCLUSIONS),
			"flushing must not park a cached map view on the index"
		);
	}

	/**
	 * Builds a catalog index holding `attributes` global unique indexes of `values` unique values each.
	 *
	 * Constructed through the map-taking constructor rather than through `insertUniqueAttribute`, so the fixture needs
	 * no schema mocks: what is being measured is the index's own arithmetic, not the path that populates it.
	 *
	 * @param attributes how many globally-unique attributes to hold an index for
	 * @param values     how many unique values each of those indexes holds
	 * @return the seeded index
	 */
	@Nonnull
	private static CatalogIndex seeded(int attributes, int values) {
		// grown organically rather than pre-sized, because that is what the warm-up path does: a fresh catalog index
		// starts from a default `HashMap` and lets `computeIfAbsent` grow it. It is also the only shape
		// `MapHeapSize` can price exactly: it cannot read a table's capacity from outside the JDK, so it replays the
		// JDK's own growth arithmetic and takes the larger candidate - which over-reports a pre-sized map by one table
		// doubling until it outgrows its initial capacity. That over-report is `MapHeapSize`'s and is pinned by
		// `TransactionalMapHeapSizeTest`; seeding it in here would only smuggle it into an assertion about this class
		final Map<AttributeKey, GlobalUniqueIndex> uniqueIndexes = new HashMap<>();
		for (int attribute = 0; attribute < attributes; attribute++) {
			// one localized key among them, so the locale the arithmetic declines to charge is actually present
			final AttributeKey attributeKey = attribute == 0 ?
				new AttributeKey("url", Locale.ENGLISH) : new AttributeKey("code-" + attribute);
			final GlobalUniqueIndex globalUniqueIndex = new GlobalUniqueIndex(
				Scope.LIVE, attributeKey, String.class
			);
			for (int value = 0; value < values; value++) {
				globalUniqueIndex.registerUniqueKey(
					String.format("value-%d-%05d", attribute, value),
					ENTITY_TYPE,
					attributeKey.locale(),
					AUTOBOX_CACHE_CEILING + value,
					RESOLVER
				);
			}
			uniqueIndexes.put(attributeKey, globalUniqueIndex);
		}
		return new CatalogIndex(1, new CatalogIndexKey(Scope.LIVE), uniqueIndexes);
	}

	/**
	 * Collects everything the index reaches through its map but charges to somebody else - or to nobody.
	 *
	 * Two groups, and they are borrowed for different reasons. The map key's **contents** belong to the catalog
	 * schema and to the JVM's locale cache, so no index charges them and every walk has to subtract them; the
	 * children's own exclusions are charged by nobody either, and are reachable only through the map, so no field path
	 * on the catalog index can name them.
	 *
	 * @param index the index whose borrowed structure to collect
	 * @return the roots to subtract from a walk of it
	 */
	@Nonnull
	private static Object[] borrowedRoots(@Nonnull CatalogIndex index) {
		final List<Object> roots = new ArrayList<>(32);
		index.getGlobalUniqueIndexes().forEach((attributeKey, globalUniqueIndex) -> {
			roots.add(attributeKey.attributeName());
			if (attributeKey.locale() != null) {
				roots.add(attributeKey.locale());
			}
			roots.addAll(List.of(excluded(globalUniqueIndex, CHILD_EXCLUSIONS)));
		});
		return roots.toArray();
	}

}
