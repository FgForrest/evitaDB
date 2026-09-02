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

package io.evitadb.core;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.IndexNotFoundException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.statistics.AttributeIndexType;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.CollectionIndexCardinality.AttributeCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the single-index drill-down that follows an index browse.
 *
 * The fixture is deliberately the same shape as `IndexBrowseTest`'s, because the two surfaces have to agree: a client
 * reaches this call by handing back a primary key a browse row reported, and every field the two share must describe
 * one index the same way. The agreement test below is the load-bearing one - the discriminator in particular is
 * produced by a renderer shared between the projections precisely so that it cannot drift.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Entity index detail")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(MANAGEMENT)
class IndexDetailTest implements EvitaTestSupport {
	private static final String CATALOG = "indexDetailTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final int CATEGORY_COUNT = 4;
	private static final int PRODUCTS_PER_CATEGORY = 5;
	private static final int EVENLY_SPREAD_PRODUCTS = CATEGORY_COUNT * PRODUCTS_PER_CATEGORY;
	/**
	 * Products piled onto {@link #FAT_CATEGORY} beyond its even share, so the fixture holds two indexes **of one
	 * kind** that differ only in how much they cover. Without that, the only size comparison available would be a
	 * global index against a reference one - and those differ in kind as well as size, so an estimate that ignored an
	 * index's contents entirely and returned a per-kind constant would satisfy it.
	 */
	private static final int EXTRA_PRODUCTS = 10;
	private static final int FAT_CATEGORY = 1;
	private static final int PRODUCT_COUNT = EVENLY_SPREAD_PRODUCTS + EXTRA_PRODUCTS;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("IndexDetailTest");
		this.evita = new Evita(getEvitaConfiguration());
		buildCatalog();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("What is described")
	class WhatIsDescribed {

		@Test
		@DisplayName("The global index reports a heap estimate and the cardinality of its attribute indexes")
		void shouldDescribeTheGlobalIndex() {
			final BrowsedIndex row = onlyIndexOfKind(EntityIndexType.GLOBAL);

			final IndexDetail detail = describe(row.indexPrimaryKey());

			assertEquals(row.indexPrimaryKey(), detail.indexPrimaryKey(), "The response identifies what was asked for");
			assertTrue(
				detail.heapSizeInBytes() > 0,
				"An index covering " + PRODUCT_COUNT + " entities cannot occupy nothing"
			);
			final IndexCardinality cardinality = detail.cardinality();
			assertEquals(EntityIndexType.GLOBAL, cardinality.indexType());
			assertEquals(Scope.LIVE, cardinality.scope());
			assertNull(cardinality.discriminator(), "A global index has no siblings to be told apart from");
			assertEquals(PRODUCT_COUNT, cardinality.entityCount());

			final AttributeCardinality code = attributeOf(cardinality, "code", AttributeIndexType.FILTER);
			assertNotNull(code, "The filterable attribute the schema declares must be described");
			// the readings that justify carrying cardinality here at all, and the fixture makes them exact: every
			// product has its own `code`, so the index holds one distinct value per record. Asserting only that the
			// attribute is *present* would be satisfied by a projection reporting zeros for all of them
			assertEquals(PRODUCT_COUNT, code.distinctValueCount(), "Every product has a distinct code");
			assertEquals(PRODUCT_COUNT, code.recordsCovered(), "Every product holds one");
		}

		@Test
		@DisplayName("A per-referenced-entity index is described, which no other component ever does")
		void shouldDescribeAPerReferencedEntityIndex() {
			// the whole reason this call carries cardinality at all. `CollectionIndexCardinality` counts these
			// indexes without describing them - their number grows with the data - and asserts that nothing is lost
			// by doing so. This is the only surface on which that assertion is checkable
			final BrowsedIndex row = indexOfCategory(2);

			final IndexDetail detail = describe(row.indexPrimaryKey());

			assertTrue(detail.heapSizeInBytes() > 0, "A populated reference index cannot occupy nothing");
			assertEquals(EntityIndexType.REFERENCED_ENTITY, detail.cardinality().indexType());
			// this is the case that would throw before the discriminator renderer was shared: a per-referenced-entity
			// index is keyed by a RepresentativeReferenceKey rather than by a plain reference name, and the
			// cardinality projection asserts the latter for the schema-bounded kinds it describes
			assertNotNull(
				detail.cardinality().discriminator(),
				"An index with siblings must say what tells it apart from them"
			);
			assertEquals(productsReferencing(2), detail.cardinality().entityCount());
		}

		@Test
		@DisplayName("The heap estimate grows with what the index holds, kind held constant")
		void shouldReportALargerEstimateForALargerIndex() {
			// deliberately two indexes of ONE kind. Comparing a global index against a reference one would prove
			// nothing about the estimate reading an index's contents: the two kinds hold different structures, and
			// a figure that ignored contents entirely and returned a per-kind constant would still come out ordered
			final long fatBytes = describe(indexOfCategory(FAT_CATEGORY).indexPrimaryKey()).heapSizeInBytes();
			final long leanBytes = describe(indexOfCategory(2).indexPrimaryKey()).heapSizeInBytes();

			assertTrue(
				fatBytes > leanBytes,
				"A reference index covering " + productsReferencing(FAT_CATEGORY) + " entities reported " + fatBytes +
					" bytes against " + leanBytes + " for a sibling of the same kind covering " +
					productsReferencing(2)
			);
		}
	}

	@Nested
	@DisplayName("Agreement with the browse it follows")
	class AgreementWithBrowse {

		@Test
		@DisplayName("Every browsed index resolves, and describes itself the same way the row did")
		void shouldAgreeWithTheBrowseRowItWasReachedFrom() {
			// the contract a client actually relies on: it takes a row, hands the key back, and must be told about
			// the same index. Every field the two surfaces share is compared, because a mismatch in any of them
			// means the drill-down silently described something else
			for (final BrowsedIndex row : browseEverything().indexes()) {
				final IndexDetail detail = describe(row.indexPrimaryKey());
				final IndexCardinality cardinality = detail.cardinality();

				assertEquals(row.indexPrimaryKey(), detail.indexPrimaryKey(), "Identity of " + row);
				assertEquals(row.indexType(), cardinality.indexType(), "Kind of " + row);
				assertEquals(row.scope(), cardinality.scope(), "Scope of " + row);
				assertEquals(
					row.discriminator(), cardinality.discriminator(),
					"The two surfaces rendered one index's discriminator differently: " + row
				);
				assertEquals(row.entityCount(), cardinality.entityCount(), "Entity count of " + row);
			}
		}
	}

	@Nested
	@DisplayName("Rejected requests")
	class RejectedRequests {

		@Test
		@DisplayName("An index primary key the collection never held is refused")
		void shouldRejectAnUnknownIndexPrimaryKey() {
			assertThrows(
				IndexNotFoundException.class,
				() -> describe(Integer.MAX_VALUE),
				"An empty answer would be indistinguishable from an index that weighs nothing"
			);
		}

		@Test
		@DisplayName("The primary key of a removed index is refused rather than resolving to another index")
		void shouldRejectThePrimaryKeyOfARemovedIndex() {
			final BrowsedIndex doomed = indexOfCategory(2);
			// resolved first, so a failure below means the key STOPPED resolving rather than never having resolved -
			// without this the test would pass identically against a key that was bad from the start
			assertEquals(doomed.indexPrimaryKey(), describe(doomed.indexPrimaryKey()).indexPrimaryKey());

			// emptying the reference reclaims its index, which is the race a client holding a browse row can lose
			this.removeProductsReferencing(2);

			assertThrows(IndexNotFoundException.class, () -> describe(doomed.indexPrimaryKey()));
		}

		@Test
		@DisplayName("A collection the catalog does not hold is refused")
		void shouldRejectAnUnknownCollection() {
			assertThrows(
				CollectionNotFoundException.class,
				() -> IndexDetailTest.this.evita.management()
					.getIndexDetail(CATALOG, "nonExistingCollection", 1)
			);
		}

		/**
		 * Deletes every product referencing the given category, which leaves that category's index covering nothing.
		 *
		 * @param categoryPrimaryKey primary key of the category to orphan
		 */
		private void removeProductsReferencing(int categoryPrimaryKey) {
			IndexDetailTest.this.evita.updateCatalog(
				CATALOG,
				session -> {
					for (int productKey = 1; productKey <= PRODUCT_COUNT; productKey++) {
						if (categoryOf(productKey) == categoryPrimaryKey) {
							session.deleteEntity(ENTITY_PRODUCT, productKey);
						}
					}
				}
			);
		}
	}

	/**
	 * Describes one index of the product collection.
	 *
	 * @param indexPrimaryKey identity of the index to describe
	 * @return its description
	 */
	@Nonnull
	private IndexDetail describe(int indexPrimaryKey) {
		return this.evita.management().getIndexDetail(CATALOG, ENTITY_PRODUCT, indexPrimaryKey);
	}

	/**
	 * Browses every index of the product collection in one page.
	 *
	 * @return the whole index set
	 */
	@Nonnull
	private IndexBrowseResult browseEverything() {
		return this.evita.management().browseIndexes(
			CATALOG,
			ENTITY_PRODUCT,
			new IndexBrowseCriteria(
				1, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.MAP_ORDER, OrderDirection.ASC,
				EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
			)
		);
	}

	/**
	 * Finds the only index of the given kind, failing when the fixture holds more than one.
	 *
	 * @param kind kind to look for
	 * @return the single index of that kind
	 */
	@Nonnull
	private BrowsedIndex onlyIndexOfKind(@Nonnull EntityIndexType kind) {
		BrowsedIndex found = null;
		for (final BrowsedIndex index : browseEverything().indexes()) {
			if (index.indexType() == kind && index.scope() == Scope.LIVE) {
				assertNull(found, "The fixture holds more than one live index of kind " + kind);
				found = index;
			}
		}
		assertNotNull(found, "The fixture holds no live index of kind " + kind);
		return found;
	}

	/**
	 * Finds any index of the given kind.
	 *
	 * @param kind kind to look for
	 * @return one index of that kind
	 */
	@Nonnull
	private BrowsedIndex anyIndexOfKind(@Nonnull EntityIndexType kind) {
		for (final BrowsedIndex index : browseEverything().indexes()) {
			if (index.indexType() == kind && index.scope() == Scope.LIVE) {
				return index;
			}
		}
		throw new AssertionError("The fixture holds no live index of kind " + kind);
	}

	/**
	 * Finds the readings of one attribute index within an index's cardinality.
	 *
	 * @param cardinality   the readings to search
	 * @param attributeName name of the attribute to find
	 * @param indexType     which of its index structures to find
	 * @return the readings, or null when the index holds no such structure
	 */
	@Nullable
	private static AttributeCardinality attributeOf(
		@Nonnull IndexCardinality cardinality,
		@Nonnull String attributeName,
		@Nonnull AttributeIndexType indexType
	) {
		for (final AttributeCardinality attribute : cardinality.attributes()) {
			if (attributeName.equals(attribute.attributeName()) && attribute.indexType() == indexType) {
				return attribute;
			}
		}
		return null;
	}

	/**
	 * Which category a product of the fixture references.
	 *
	 * @param productPrimaryKey primary key of the product
	 * @return primary key of the category it references
	 */
	private static int categoryOf(int productPrimaryKey) {
		return productPrimaryKey <= EVENLY_SPREAD_PRODUCTS
			? ((productPrimaryKey - 1) % CATEGORY_COUNT) + 1
			: FAT_CATEGORY;
	}

	/**
	 * How many products of the fixture reference the given category.
	 *
	 * @param categoryPrimaryKey primary key of the category
	 * @return the number of products referencing it, which is what its index covers
	 */
	private static int productsReferencing(int categoryPrimaryKey) {
		return categoryPrimaryKey == FAT_CATEGORY ? PRODUCTS_PER_CATEGORY + EXTRA_PRODUCTS : PRODUCTS_PER_CATEGORY;
	}

	/**
	 * Finds the per-referenced-entity index bound to one category.
	 *
	 * @param categoryPrimaryKey primary key of the referenced category
	 * @return the index bound to it
	 */
	@Nonnull
	private BrowsedIndex indexOfCategory(int categoryPrimaryKey) {
		for (final BrowsedIndex index : browseEverything().indexes()) {
			if (index.indexType() == EntityIndexType.REFERENCED_ENTITY
				&& index.scope() == Scope.LIVE
				&& Integer.valueOf(categoryPrimaryKey).equals(index.discriminatorPrimaryKey())) {
				return index;
			}
		}
		throw new AssertionError("The fixture holds no live reference index for category " + categoryPrimaryKey);
	}

	/**
	 * Builds a fixture with one filterable attribute and one indexed reference, so that every index it produces has
	 * something to report both a heap estimate and a cardinality for.
	 */
	private void buildCatalog() {
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute("code", String.class, AttributeSchemaEditor::filterable)
					.withReferenceToEntity(
						"categories", ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
				for (int i = 1; i <= CATEGORY_COUNT; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, i));
				}
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, i)
							.setAttribute("code", "product-" + i)
							.setReference("categories", categoryOf(i))
					);
				}
			}
		);
	}

	/**
	 * Builds the configuration of the embedded instance this test runs against.
	 *
	 * @return configuration rooted at this test's directories
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.build()
			)
			.build();
	}

}
