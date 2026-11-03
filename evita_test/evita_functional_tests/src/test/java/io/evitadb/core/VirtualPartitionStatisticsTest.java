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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.IndexNotFoundException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionIndexCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.api.statistics.CollectionIndexSummary;
import io.evitadb.api.statistics.CollectionIndexSummary.IndexTypeCount;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionIndexEntries;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how the index statistics report the single-owner partitions of a reference indexed for filtering only,
 * which a catalog holds as rows of the registry its reference type index carries rather than as index objects.
 *
 * Two claims are checked against each other. The rows are counted - by `virtualPartitionCount` of the reference type
 * index, and by nothing else - and they are *not* indexes: neither `omittedIndexCount`, nor the index summary, nor a
 * browse sees them, and their primary key does not describe an index. Each test asserts both halves, because a count
 * that is right while the physical counts still include the rows would report the same partition twice.
 *
 * The fixture references five categories from seven products: three categories have one owner each (single-owner
 * partitions, registry rows) and two have two owners each (index objects). Every reference carries the same group, so
 * the reference group indexes exist next to the reference ones.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Single-owner reference partitions in the index statistics")
@Tag(ENGINE)
@Tag(MANAGEMENT)
@Tag(REFERENCE)
class VirtualPartitionStatisticsTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualPartitionStatisticsTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_BRAND = "brand";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_ORDER = "order";
	/**
	 * The brand every category reference is grouped by.
	 */
	private static final int BRAND = 1;
	/**
	 * The category only product 1 references - a single-owner partition whose row is accompanied by a sort value and a
	 * chain predecessor, so the registry holds more than one entry for it.
	 */
	private static final int RICH_CATEGORY = 1;
	/**
	 * A category only product 2 references - a single-owner partition with no attribute values.
	 */
	private static final int PLAIN_CATEGORY = 2;
	/**
	 * A category products 4 and 5 reference - a partition of two owners.
	 */
	private static final int SHARED_CATEGORY = 4;
	/**
	 * The number of categories the fixture references from exactly one product.
	 */
	private static final int SINGLE_OWNER_PARTITIONS = 3;
	/**
	 * The number of categories the fixture references from exactly two products.
	 */
	private static final int MULTI_OWNER_PARTITIONS = 2;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualPartitionStatisticsTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("A catalog holding its single-owner partitions as registry rows")
	class VirtualCatalog {

		@BeforeEach
		void buildVirtualCatalog() {
			buildCatalog();
		}

		@Test
		@DisplayName("Rows are counted on the reference type index and are absent from every physical index count")
		void shouldCountRowsApartFromThePhysicalIndexes() {
			assertPartitions(SINGLE_OWNER_PARTITIONS, MULTI_OWNER_PARTITIONS, "while warming up");

			VirtualPartitionStatisticsTest.this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			assertPartitions(SINGLE_OWNER_PARTITIONS, MULTI_OWNER_PARTITIONS, "after going live");
		}

		@Test
		@DisplayName("A row is counted once, however many other entries the registry keeps for its partition")
		void shouldCountAPartitionOnceWhateverElseItsRowRecords() {
			// the premise: the partition of the rich category is a row whose registry keeps a sort value and a chain
			// predecessor next to its owner, so a count of the registry's entries rather than of its rows reports it
			// three times
			final VirtualReferencePartitionIndex registry = registry();
			final int partition = rowOf(registry, RICH_CATEGORY);
			final AttributeIndexKey priority = new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_PRIORITY, null);
			final AttributeIndexKey order = new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_ORDER, null);
			assertNotEquals(
				VirtualReferencePartitionIndex.NO_SORT_VALUE_ID, registry.getSortValueId(priority, partition),
				"The row of the rich category must carry a sort value"
			);
			assertTrue(
				registry.getChainPredecessor(order, partition).isPresent(),
				"The row of the rich category must carry a chain predecessor"
			);

			assertEquals(
				SINGLE_OWNER_PARTITIONS,
				referenceTypeIndexOf(cardinalityOf()).virtualPartitionCountIfKnown().orElseThrow(),
				"Each single-owner partition is one row, whatever else the registry records about it"
			);
		}

		@Test
		@DisplayName("A partition a second owner joins leaves the rows and becomes an index")
		void shouldMovePartitionToTheIndexesWhenASecondOwnerJoins() {
			goLive();
			VirtualPartitionStatisticsTest.this.evita.updateCatalog(
				CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, 8)
							.setReference(
								REFERENCE_CATEGORIES, RICH_CATEGORY,
								whichIs -> whichIs.setGroup(BRAND)
									.setAttribute(ATTRIBUTE_PRIORITY, 20)
									.setAttribute(ATTRIBUTE_ORDER, new Predecessor(1))
							)
					);
				}
			);

			assertPartitions(SINGLE_OWNER_PARTITIONS - 1, MULTI_OWNER_PARTITIONS + 1, "after the promotion");
		}

		@Test
		@DisplayName("A partition left with one owner leaves the indexes and becomes a row again")
		void shouldMovePartitionBackToTheRowsWhenItIsLeftWithOneOwner() {
			goLive();
			VirtualPartitionStatisticsTest.this.evita.updateCatalog(
				CATALOG,
				session -> {
					session.getEntity(ENTITY_PRODUCT, 5, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY)
						.upsertVia(session);
				}
			);

			assertPartitions(SINGLE_OWNER_PARTITIONS + 1, MULTI_OWNER_PARTITIONS - 1, "after the demotion");
		}

		@Test
		@DisplayName("The count read back from disk is the count written")
		void shouldReportTheSameCountAfterReload() {
			goLive();
			reload();

			assertPartitions(SINGLE_OWNER_PARTITIONS, MULTI_OWNER_PARTITIONS, "after the reload");
		}

		@Test
		@DisplayName("The count is reported by the reference type index alone, and absent from every other index")
		void shouldReportTheCountOnTheReferencedEntityTypeIndexOnly() {
			final CollectionIndexCardinality cardinality = cardinalityOf();
			assertTrue(
				indexOf(cardinality, EntityIndexType.GLOBAL).virtualPartitionCountIfKnown().isEmpty(),
				"The global index holds no registry, so it must report no count rather than zero"
			);
			assertTrue(
				indexOf(cardinality, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE)
					.virtualPartitionCountIfKnown().isEmpty(),
				"The reference group type index never holds a group as a row, so it must report no count rather " +
					"than zero"
			);
			assertEquals(
				SINGLE_OWNER_PARTITIONS,
				referenceTypeIndexOf(cardinality).virtualPartitionCountIfKnown().orElseThrow()
			);

			// describing one index reports what the component reports for it - both read the same projection
			final IndexDetail referenceTypeDetail = describe(primaryKeyOf(EntityIndexType.REFERENCED_ENTITY_TYPE));
			assertEquals(
				SINGLE_OWNER_PARTITIONS,
				referenceTypeDetail.cardinality().virtualPartitionCountIfKnown().orElseThrow()
			);
			final IndexDetail globalDetail = describe(primaryKeyOf(EntityIndexType.GLOBAL));
			assertTrue(globalDetail.cardinality().virtualPartitionCountIfKnown().isEmpty());
		}

		@Test
		@DisplayName("A row's primary key names no index - it is neither described nor browsed")
		void shouldRefuseToDescribeAPartitionHeldAsARow() {
			final int partition = rowOf(registry(), PLAIN_CATEGORY);

			assertThrows(
				IndexNotFoundException.class,
				() -> describe(partition),
				"A partition held as a row is not an index, so its primary key must not describe one"
			);
			for (final BrowsedIndex row : browseEverything().indexes()) {
				assertNotEquals(
					partition, row.indexPrimaryKey(),
					"A partition held as a row must never be browsed as an index: " + row
				);
			}
		}

	}

	/**
	 * Asserts how the partitions of the category reference split between registry rows and index objects, against
	 * every statistic that sees either half: the count on the reference type index, the omitted index count, the
	 * index summary and the index browse.
	 *
	 * @param rows    how many partitions must be held as registry rows
	 * @param objects how many partitions must be index objects
	 * @param stage   what has happened to the catalog, for the failure messages
	 */
	private void assertPartitions(int rows, int objects, @Nonnull String stage) {
		final CollectionIndexCardinality cardinality = cardinalityOf();
		assertEquals(
			rows, referenceTypeIndexOf(cardinality).virtualPartitionCountIfKnown().orElseThrow(),
			"The reference type index reports a wrong number of partitions held as rows " + stage + ": " + cardinality
		);
		final CollectionIndexSummary summary = summaryOf();
		assertEquals(
			objects, countOf(summary, EntityIndexType.REFERENCED_ENTITY),
			"The summary reports a wrong number of partition indexes " + stage + ": " + summary
		);
		// one group, so one reference group index - the other data-bounded index the omitted count includes
		assertEquals(1, countOf(summary, EntityIndexType.REFERENCED_GROUP_ENTITY));
		assertEquals(
			objects + 1, cardinality.omittedIndexCount(),
			"The omitted indexes must be the partition indexes and the group index, and no row " + stage
		);
		int browsedPartitions = 0;
		for (final BrowsedIndex row : browseEverything().indexes()) {
			if (row.indexType() == EntityIndexType.REFERENCED_ENTITY) {
				browsedPartitions++;
			}
		}
		assertEquals(objects, browsedPartitions, "The browse lists a wrong number of partition indexes " + stage);
	}

	/**
	 * Defines the catalog and fills it with the fixture described in the class documentation.
	 */
	private void buildCatalog() {
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						// indexed for filtering only - the indexing level whose single-owner partitions may be rows -
						// with the group indexes built as well, so the group family is described next to the
						// reference one
						whichIs -> whichIs.indexedForFiltering()
							.indexedWithComponents(
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.withGroupTypeRelatedToEntity(ENTITY_BRAND)
							.withAttribute(
								ATTRIBUTE_PRIORITY, Integer.class,
								thatIs -> thatIs.sortableInScope(Scope.LIVE).nullable()
							)
							.withAttribute(
								ATTRIBUTE_ORDER, Predecessor.class,
								thatIs -> thatIs.sortableInScope(Scope.LIVE).nullable()
							)
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND));
				for (int category = 1; category <= SINGLE_OWNER_PARTITIONS + MULTI_OWNER_PARTITIONS; category++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, category));
				}
				// the rich category: one owner, and a row accompanied by a sort value and a chain predecessor
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REFERENCE_CATEGORIES, RICH_CATEGORY,
							whichIs -> whichIs.setGroup(BRAND)
								.setAttribute(ATTRIBUTE_PRIORITY, 10)
								.setAttribute(ATTRIBUTE_ORDER, Predecessor.HEAD)
						)
				);
				// two more single-owner partitions, then two partitions of two owners each
				addProduct(session, 2, PLAIN_CATEGORY);
				addProduct(session, 3, 3);
				addProduct(session, 4, SHARED_CATEGORY);
				addProduct(session, 5, SHARED_CATEGORY);
				addProduct(session, 6, 5);
				addProduct(session, 7, 5);
			}
		);
	}

	/**
	 * Adds a product referencing one category under the fixture's brand, with no reference attribute.
	 *
	 * @param session  the session of the catalog being built
	 * @param product  primary key of the product
	 * @param category primary key of the referenced category
	 */
	private static void addProduct(@Nonnull EvitaSessionContract session, int product, int category) {
		session.upsertEntity(
			session.createNewEntity(ENTITY_PRODUCT, product)
				.setReference(REFERENCE_CATEGORIES, category, whichIs -> whichIs.setGroup(BRAND))
		);
	}

	/**
	 * Transitions the catalog out of warm-up, so the following changes are committed by transactions.
	 */
	private void goLive() {
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Closes the engine and opens a new one over the same directories, waiting until the catalog is loaded.
	 */
	private void reload() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(
				() -> !this.evita.management()
					.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.IDENTITY))
					.identity()
					.unusable()
			);
	}

	/**
	 * Reads the index cardinality component of the product collection.
	 *
	 * @return the delivered component
	 */
	@Nonnull
	private CollectionIndexCardinality cardinalityOf() {
		return this.evita.management()
			.getEntityCollectionStatistics(
				CATALOG, ENTITY_PRODUCT, EnumSet.of(CatalogStatisticsComponent.INDEX_CARDINALITY)
			)
			.indexCardinalityIfPresent()
			.orElseThrow();
	}

	/**
	 * Reads the index summary component of the product collection.
	 *
	 * @return the delivered component
	 */
	@Nonnull
	private CollectionIndexSummary summaryOf() {
		return this.evita.management()
			.getEntityCollectionStatistics(
				CATALOG, ENTITY_PRODUCT, EnumSet.of(CatalogStatisticsComponent.INDEX_SUMMARY)
			)
			.indexSummaryIfPresent()
			.orElseThrow();
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
	 * Describes one index of the product collection.
	 *
	 * @param indexPrimaryKey primary key of the index
	 * @return its description
	 */
	@Nonnull
	private IndexDetail describe(int indexPrimaryKey) {
		return this.evita.management().getIndexDetail(CATALOG, ENTITY_PRODUCT, indexPrimaryKey);
	}

	/**
	 * Finds the primary key of the only live index of the given kind, as the browse reports it.
	 *
	 * @param indexType kind of the index
	 * @return its primary key
	 */
	private int primaryKeyOf(@Nonnull EntityIndexType indexType) {
		for (final BrowsedIndex row : browseEverything().indexes()) {
			if (row.indexType() == indexType && row.scope() == Scope.LIVE) {
				return row.indexPrimaryKey();
			}
		}
		throw new AssertionError("No live index of kind `" + indexType + "` was browsed!");
	}

	/**
	 * Picks the live reference type index of the category reference out of the component.
	 *
	 * @param cardinality the delivered component
	 * @return its readings
	 */
	@Nonnull
	private static IndexCardinality referenceTypeIndexOf(@Nonnull CollectionIndexCardinality cardinality) {
		return indexOf(cardinality, EntityIndexType.REFERENCED_ENTITY_TYPE);
	}

	/**
	 * Picks the single live described index of one kind.
	 *
	 * @param cardinality the delivered component
	 * @param indexType   kind to look for
	 * @return the one live index of that kind
	 */
	@Nonnull
	private static IndexCardinality indexOf(
		@Nonnull CollectionIndexCardinality cardinality,
		@Nonnull EntityIndexType indexType
	) {
		for (final IndexCardinality index : cardinality.indexes()) {
			if (index.indexType() == indexType && index.scope() == Scope.LIVE) {
				return index;
			}
		}
		throw new AssertionError("No live index of kind `" + indexType + "` was described: " + cardinality);
	}

	/**
	 * Returns how many live indexes of the given kind the summary counts.
	 *
	 * @param summary   the delivered summary
	 * @param indexType kind to count
	 * @return the count, `0` when the summary omits the kind
	 */
	private static int countOf(@Nonnull CollectionIndexSummary summary, @Nonnull EntityIndexType indexType) {
		for (final IndexTypeCount typeCount : summary.byTypeAndScope()) {
			if (typeCount.indexType() == indexType && typeCount.scope() == Scope.LIVE) {
				return typeCount.count();
			}
		}
		return 0;
	}

	/**
	 * Returns the catalog instance of the engine.
	 *
	 * @return the catalog
	 */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstance(CATALOG).orElseThrow();
	}

	/**
	 * Returns the registry of the live category reference - the engine-side view the statistics are compared with.
	 *
	 * @return the registry
	 */
	@Nonnull
	private VirtualReferencePartitionIndex registry() {
		final EntityCollection products = (EntityCollection) catalog()
			.getCollectionForEntity(ENTITY_PRODUCT)
			.orElseThrow();
		final EntityIndex typeIndex = products.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_CATEGORIES)
		);
		assertNotNull(typeIndex, "The category reference must have a live reference type index");
		return assertInstanceOf(ReferencedTypeEntityIndex.class, typeIndex).getVirtualReferencePartitionIndex();
	}

	/**
	 * Finds the primary key of the partition the registry holds as a row for the given category.
	 *
	 * @param registry the registry
	 * @param category primary key of the referenced category
	 * @return the partition primary key
	 */
	private static int rowOf(@Nonnull VirtualReferencePartitionIndex registry, int category) {
		final int[] partition = {-1};
		VirtualReferencePartitionIndexEntries.forEachOwnerRow(
			registry,
			(partitionPrimaryKey, payload) -> {
				if (NumberUtils.unpackLow(payload) == category) {
					partition[0] = partitionPrimaryKey;
				}
			}
		);
		assertTrue(partition[0] > 0, "The registry holds no row of category " + category);
		return partition[0];
	}

}
