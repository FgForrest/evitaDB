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

package io.evitadb.core.catalog;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.statistics.CatalogIndexCardinality;
import io.evitadb.api.statistics.CatalogIndexCardinality.GlobalUniqueIndexCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.AttributeCardinality;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.index.IndexActivity;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the catalog-level management readers - {@link CatalogIndexProjection#describe} and
 * {@link CatalogIndexCardinalityProjection#describe} - answer while a warm-up writer adds a globally unique attribute
 * to the catalog index they are walking.
 *
 * Outside a transaction the catalog index files a new {@link GlobalUniqueIndex} straight into the `HashMap` behind its
 * `TransactionalMap`, and both projections walk that map with `forEach` from a management thread that shares no
 * happens-before edge with the writer. The write is placed deterministically: one fixture index files the new one
 * from inside its own `size()`, which both projections call once per entry of the walk - the same point a concurrent
 * writer's store can land at. `HashMap#forEach` then throws `ConcurrentModificationException` after it has visited the
 * whole table.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog index projections under a concurrent warm-up write")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class CatalogIndexConcurrentWriteTest {
	/**
	 * The one entity type every fixture record belongs to.
	 */
	private static final String ENTITY_TYPE = "Product";
	/**
	 * Resolves every entity type to one primary key - the fixture holds a single entity type.
	 */
	private static final EntityTypeClassifierResolver RESOLVER = new EntityTypeClassifierResolver() {
		@Override
		public int toEntityTypePrimaryKey(@Nonnull String entityType) {
			return 1;
		}

		@Nonnull
		@Override
		public String toEntityTypeName(int entityTypePrimaryKey) {
			return ENTITY_TYPE;
		}
	};

	/**
	 * Builds a global unique index holding `values` distinct values, each owned by its own record.
	 *
	 * @param index  the (possibly armed) index to fill
	 * @param name   name of the attribute the index is filed under, used as the value prefix
	 * @param values how many values to register
	 * @return the same index
	 */
	@Nonnull
	private static GlobalUniqueIndex filled(@Nonnull GlobalUniqueIndex index, @Nonnull String name, int values) {
		for (int value = 0; value < values; value++) {
			index.registerUniqueKey(name + "-" + value, ENTITY_TYPE, null, value + 1, RESOLVER);
		}
		return index;
	}

	/**
	 * Builds a catalog index holding `code` (two values) and `url` (three values), where `url` files a third unique
	 * index, `late` (one value), into the catalog index the first time its size is read - a warm-up write landing
	 * inside the projection's walk.
	 *
	 * @param written receives `true` once the write has landed
	 * @return the catalog index
	 */
	@Nonnull
	private static CatalogIndex catalogIndexWritingOnFirstRead(@Nonnull boolean[] written) {
		// held by reference: the catalog index adopts it as the delegate of its `TransactionalMap`, which is the very
		// map a non-transactional `insertUniqueAttribute` writes into
		final Map<AttributeKey, GlobalUniqueIndex> uniqueIndexes = new HashMap<>();
		// armed only once the fixture is complete, so filling the index cannot fire the write early
		final boolean[] armed = {false};
		uniqueIndexes.put(
			new AttributeKey("code"),
			filled(new GlobalUniqueIndex(Scope.LIVE, new AttributeKey("code"), String.class, 0), "code", 2)
		);
		final GlobalUniqueIndex writing = new GlobalUniqueIndex(Scope.LIVE, new AttributeKey("url"), String.class, 0) {
			@Override
			public int size() {
				if (armed[0] && !written[0]) {
					written[0] = true;
					uniqueIndexes.put(
						new AttributeKey("late"),
						filled(new GlobalUniqueIndex(Scope.LIVE, new AttributeKey("late"), String.class, 0), "late", 1)
					);
				}
				return super.size();
			}
		};
		uniqueIndexes.put(new AttributeKey("url"), filled(writing, "url", 3));
		final CatalogIndex catalogIndex =
			new CatalogIndex(1, new CatalogIndexKey(Scope.LIVE), uniqueIndexes, new IndexActivity());
		armed[0] = true;
		return catalogIndex;
	}

	@Test
	@DisplayName("the detail describes every unique index, including the one filed while it was walking")
	void shouldDescribeTheCatalogIndexAWriteLandsInWhileItIsWalked() {
		final boolean[] written = {false};
		final CatalogIndex catalogIndex = catalogIndexWritingOnFirstRead(written);

		final IndexDetail detail = CatalogIndexProjection.describe(catalogIndex);

		assertTrue(written[0], "the write must have landed during the walk");
		final Map<String, AttributeCardinality> byName = Arrays.stream(detail.cardinality().attributes())
			.collect(Collectors.toMap(AttributeCardinality::attributeName, it -> it));
		assertEquals(List.of("code", "late", "url"), byName.keySet().stream().sorted().toList());
		assertReading(byName.get("code"), 2);
		assertReading(byName.get("url"), 3);
		assertReading(byName.get("late"), 1);
	}

	@Test
	@DisplayName("the cardinality describes every unique index, including the one filed while it was walking")
	void shouldCountTheCatalogIndexAWriteLandsInWhileItIsWalked() {
		final boolean[] written = {false};
		final CatalogIndex catalogIndex = catalogIndexWritingOnFirstRead(written);

		final CatalogIndexCardinality cardinality = CatalogIndexCardinalityProjection.describe(List.of(catalogIndex));

		assertTrue(written[0], "the write must have landed during the walk");
		final Map<String, Integer> byName = Arrays.stream(cardinality.globalUniqueIndexes())
			.collect(
				Collectors.toMap(
					GlobalUniqueIndexCardinality::attributeName, GlobalUniqueIndexCardinality::distinctValueCount
				)
			);
		assertEquals(Map.of("code", 2, "url", 3, "late", 1), byName);
	}

	/**
	 * Builds a catalog index holding `code` (two values) and `url` (three values), plus a third key, `torn`, whose
	 * value reads `null` - the state a management reader observes when a warm-up writer has linked a new node into the
	 * table but the node's value store has not become visible to the reader yet. `HashMap.Node#value` is not final, so
	 * with no happens-before edge between the two threads nothing orders that store before the link. Putting a `null`
	 * straight into the delegate the catalog index adopted builds that state deterministically, the way the tree tests
	 * raise `peek` without publishing the child.
	 *
	 * @return the catalog index
	 */
	@Nonnull
	private static CatalogIndex catalogIndexWithAnUnpublishedValue() {
		// held by reference: the catalog index adopts it as the delegate of its `TransactionalMap`
		final Map<AttributeKey, GlobalUniqueIndex> uniqueIndexes = new HashMap<>();
		uniqueIndexes.put(
			new AttributeKey("code"),
			filled(new GlobalUniqueIndex(Scope.LIVE, new AttributeKey("code"), String.class, 0), "code", 2)
		);
		uniqueIndexes.put(
			new AttributeKey("url"),
			filled(new GlobalUniqueIndex(Scope.LIVE, new AttributeKey("url"), String.class, 0), "url", 3)
		);
		final CatalogIndex catalogIndex =
			new CatalogIndex(1, new CatalogIndexKey(Scope.LIVE), uniqueIndexes, new IndexActivity());
		uniqueIndexes.put(new AttributeKey("torn"), null);
		return catalogIndex;
	}

	@Test
	@DisplayName("the detail steps over a unique index whose value a racing writer has not published yet")
	void shouldDescribeTheCatalogIndexPastAnUnpublishedValue() {
		final IndexDetail detail = CatalogIndexProjection.describe(catalogIndexWithAnUnpublishedValue());

		final Map<String, AttributeCardinality> byName = Arrays.stream(detail.cardinality().attributes())
			.collect(Collectors.toMap(AttributeCardinality::attributeName, it -> it));
		assertEquals(List.of("code", "url"), byName.keySet().stream().sorted().toList());
		assertReading(byName.get("code"), 2);
		assertReading(byName.get("url"), 3);
	}

	@Test
	@DisplayName("the cardinality steps over a unique index whose value a racing writer has not published yet")
	void shouldCountTheCatalogIndexPastAnUnpublishedValue() {
		final CatalogIndexCardinality cardinality =
			CatalogIndexCardinalityProjection.describe(List.of(catalogIndexWithAnUnpublishedValue()));

		final Map<String, Integer> byName = Arrays.stream(cardinality.globalUniqueIndexes())
			.collect(
				Collectors.toMap(
					GlobalUniqueIndexCardinality::attributeName, GlobalUniqueIndexCardinality::distinctValueCount
				)
			);
		assertEquals(Map.of("code", 2, "url", 3), byName);
	}

	/**
	 * Asserts one attribute reading of a unique index whose every value belongs to its own record.
	 *
	 * @param reading the reading, or null when the index was not described
	 * @param values  how many values the index holds
	 */
	private static void assertReading(@Nullable AttributeCardinality reading, int values) {
		assertTrue(reading != null, "the unique index must be described");
		assertEquals(values, reading.distinctValueCount());
		assertEquals(values, reading.recordsCovered());
	}

}
