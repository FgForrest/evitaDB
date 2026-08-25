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
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexDetail;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-index query and update counters end to end, through the very calls an operator reads them with.
 *
 * The fixture gives every category a per-referenced-entity index of its own, and the assertions always compare an
 * index that saw traffic against a sibling of the same kind and size that did not - so a counter advancing on every
 * index, which is the way this could plausibly go wrong, fails rather than passes.
 *
 * **The query-side tests do pin which index a plan is built on, and that is deliberate.** The `referenceHaving` filter
 * below builds a genuine per-referenced-entity candidate that then loses the cost comparison to the global index on
 * this fixture - exactly the case separating *chosen* from *consulted* - so `shouldCountTheWinningIndex` and
 * `shouldNotCountAnIndexThatOnlyLostTheCostComparison` together assert the outcome of that comparison. A cost-model
 * change that reverses it surfaces in these two first, and what has to be re-examined then is which index the readings
 * are expected on, not whether the counting itself still works.
 *
 * **The exact query counts hold only for sessions with no verification debug mode enabled.**
 * {@link io.evitadb.core.query.QueryPlanner#planQuery} builds the preferred plan a second time and executes every
 * alternative when {@link io.evitadb.api.query.require.DebugMode#VERIFY_ALTERNATIVE_INDEX_RESULTS} or
 * {@link io.evitadb.api.query.require.DebugMode#VERIFY_POSSIBLE_CACHING_TREES} is on, so such a query advances the
 * winning index more than once and lifts the losing candidates off zero. A case added here with a `debug(...)`
 * requirement has to account for that rather than read it as a double count.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see io.evitadb.index.IndexActivity
 */
@DisplayName("Index usage statistics")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(MANAGEMENT)
class IndexUsageStatisticsTest implements EvitaTestSupport {
	private static final String CATALOG = "indexUsageStatisticsTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final int CATEGORY_COUNT = 4;
	private static final int PRODUCTS_PER_CATEGORY = 5;
	private static final int PRODUCT_COUNT = CATEGORY_COUNT * PRODUCTS_PER_CATEGORY;
	/** The category whose index a query builds a candidate plan around. */
	private static final int QUERIED_CATEGORY = 2;
	/** A category of the same kind and size that no test ever names - the control row. */
	private static final int UNTOUCHED_CATEGORY = 3;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("IndexUsageStatisticsTest");
		this.evita = new Evita(getEvitaConfiguration());
		buildCatalog();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Update side")
	class UpdateSide {

		@Test
		@DisplayName("Building the fixture counts as maintenance on the global index and on every reference index")
		void shouldCountTheBuildAsMaintenance() {
			// a freshly built index counts the mutations that built it, which is index-maintenance cost like any other
			final BrowsedIndex global = rowOfGlobalIndex();
			assertEquals(
				PRODUCT_COUNT, global.updateCount(),
				"The global index is acquired by every entity mutation, so it must have counted exactly one per " +
					"product upsert"
			);
			assertNotNull(global.lastUpdatedAt(), "An index that counted maintenance must carry the stamp of it");

			final BrowsedIndex reference = rowOfReferenceIndex(UNTOUCHED_CATEGORY);
			assertEquals(
				PRODUCTS_PER_CATEGORY, reference.updateCount(),
				"A per-referenced-entity index counts the mutations that populated it, once each: " + reference
			);
			assertNotNull(reference.lastUpdatedAt());
		}

		@Test
		@DisplayName("A write touching one reference index leaves its siblings alone")
		void shouldCountMaintenanceOnlyOnTheIndexesAWriteTouches() {
			final long touchedBefore = rowOfReferenceIndex(QUERIED_CATEGORY).updateCount();
			final long untouchedBefore = rowOfReferenceIndex(UNTOUCHED_CATEGORY).updateCount();

			upsertProductInCategory(PRODUCT_COUNT + 1, QUERIED_CATEGORY);

			assertEquals(
				touchedBefore + 1, rowOfReferenceIndex(QUERIED_CATEGORY).updateCount(),
				"The one entity mutation that acquired this index must have counted exactly once - not once per " +
					"attribute it wrote"
			);
			assertEquals(
				untouchedBefore, rowOfReferenceIndex(UNTOUCHED_CATEGORY).updateCount(),
				"A reference index the write never touched must not have counted anything"
			);
		}

	}

	@Nested
	@DisplayName("Query side")
	class QuerySide {

		@Test
		@DisplayName("The index a plan is built on is counted")
		void shouldCountTheWinningIndex() {
			final long before = rowOfGlobalIndex().queryCount();

			queryProductsOfCategory(QUERIED_CATEGORY);

			final BrowsedIndex global = rowOfGlobalIndex();
			assertEquals(before + 1, global.queryCount(), "The executed plan's index must have counted one query");
			assertNotNull(global.lastQueriedAt(), "An index that counted a query must carry the stamp of it");
		}

		@Test
		@DisplayName("An index the planner only considered is not counted")
		void shouldNotCountAnIndexThatOnlyLostTheCostComparison() {
			// the load-bearing test of the query side. Filtering on `categories = 2` makes the planner build a whole
			// candidate plan around that category's own index - it is read, costed, and then discarded in favour of a
			// cheaper one. Counting it would inflate every index the planner ever glanced at and make the reading
			// useless for deciding what to drop
			final long candidateBefore = rowOfReferenceIndex(QUERIED_CATEGORY).queryCount();
			final long unrelatedBefore = rowOfReferenceIndex(UNTOUCHED_CATEGORY).queryCount();

			queryProductsOfCategory(QUERIED_CATEGORY);

			assertEquals(
				candidateBefore, rowOfReferenceIndex(QUERIED_CATEGORY).queryCount(),
				"A candidate index that lost the cost comparison must not have counted a query - the reading counts " +
					"*chosen*, not *consulted*"
			);
			assertEquals(
				unrelatedBefore, rowOfReferenceIndex(UNTOUCHED_CATEGORY).queryCount(),
				"An index no plan even considered must not have counted anything"
			);
		}

		@Test
		@DisplayName("A query the planner answers without any index counts nothing")
		void shouldCountNothingForAQueryThatSelectsNoIndex() {
			// the planner short-circuits before a plan is ever built when index selection comes back empty, which is
			// what asking for a scope this catalog holds no index in does. Nothing was consulted and nothing may be
			// counted - not even on the live indexes that were passed over
			final long before = totalQueryCountAcrossTheCollection();

			IndexUsageStatisticsTest.this.evita.queryCatalog(
				CATALOG,
				session -> {
					session.queryList(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(scope(Scope.ARCHIVED))
						),
						EntityReferenceContract.class
					);
				}
			);

			assertEquals(
				before, totalQueryCountAcrossTheCollection(),
				"A query that selected no index counted one anyway"
			);
		}

		@Test
		@DisplayName("An index nothing has queried reports no count and no stamp, rather than the epoch")
		void shouldReportAbsenceRatherThanTheEpochForANeverQueriedIndex() {
			final BrowsedIndex untouched = rowOfReferenceIndex(UNTOUCHED_CATEGORY);

			assertEquals(0L, untouched.queryCount());
			assertNull(
				untouched.lastQueriedAt(),
				"A never-queried index must report absence; the epoch would render as a date in 1970"
			);
			assertTrue(untouched.lastQueriedAtIfKnown().isEmpty());
		}

		@Test
		@DisplayName("The drill-down reports the same four readings the browse row does")
		void shouldReportTheSameReadingsOnTheDrillDown() {
			queryProductsOfCategory(QUERIED_CATEGORY);

			final BrowsedIndex row = rowOfGlobalIndex();
			final IndexDetail detail = IndexUsageStatisticsTest.this.evita.management()
				.getIndexDetail(CATALOG, ENTITY_PRODUCT, row.indexPrimaryKey());

			assertEquals(row.queryCount(), detail.queryCount());
			assertEquals(row.updateCount(), detail.updateCount());
			assertEquals(row.lastQueriedAt(), detail.lastQueriedAt());
			assertEquals(row.lastUpdatedAt(), detail.lastUpdatedAt());
		}

	}

	@Nested
	@DisplayName("Lifetime")
	class Lifetime {

		@Test
		@DisplayName("A commit that dirties an index carries its counters into the next catalog version")
		void shouldCarryCountersAcrossACommitThatDirtiesTheIndex() {
			// THE regression test of the whole design. A live catalog rebuilds every dirtied index on commit rather
			// than mutating it, so a counter held as a plain index field would reset here - silently, and precisely on
			// the indexes with enough traffic to be worth looking at
			IndexUsageStatisticsTest.this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);

			queryProductsOfCategory(QUERIED_CATEGORY);
			final long queriesBefore = rowOfGlobalIndex().queryCount();
			final long updatesBefore = rowOfGlobalIndex().updateCount();
			assertTrue(queriesBefore > 0, "The test needs traffic on the index before the commit to prove anything");

			final long untouchedBefore = rowOfReferenceIndex(UNTOUCHED_CATEGORY).updateCount();

			// a transactional write that dirties this very index, forcing the commit-time merge copy
			upsertProductInCategory(PRODUCT_COUNT + 2, QUERIED_CATEGORY);

			final BrowsedIndex after = rowOfGlobalIndex();
			assertEquals(
				queriesBefore, after.queryCount(),
				"The commit rebuilt the index and lost its query count - the activity holder is not being threaded " +
					"through the merge copy"
			);
			// deliberately *not* an exact count: a live catalog applies one entity mutation to the index twice - once
			// against the session's isolated layer and once again when the trunk is incorporated from the WAL - and
			// both passes are maintenance genuinely performed. Pinning the number here would only encode that
			// internal arrangement into a test that is about the counters surviving the merge copy
			assertTrue(
				after.updateCount() > updatesBefore,
				"The committed write must have added maintenance on top of what survived, not replaced it: " + after
			);
			assertEquals(
				untouchedBefore, rowOfReferenceIndex(UNTOUCHED_CATEGORY).updateCount(),
				"A commit must not have counted maintenance on an index the write never touched"
			);
		}

		@Test
		@DisplayName("A restart starts the counters over, because they count since the catalog was loaded")
		void shouldStartOverAfterARestart() {
			queryProductsOfCategory(QUERIED_CATEGORY);
			assertTrue(rowOfGlobalIndex().queryCount() > 0);

			restart();

			final BrowsedIndex after = rowOfGlobalIndex();
			assertEquals(0L, after.queryCount(), "The counters are not persisted, by design");
			assertEquals(0L, after.updateCount());
			assertNull(after.lastQueriedAt());
			assertNull(after.lastUpdatedAt());
		}

	}

	/**
	 * The catalog's own indexes, which the shared fixture cannot reach at all: they are acquired only by a write that
	 * maintains a globally-unique attribute, and chosen only by a query that names no collection. Both sides therefore
	 * need a catalog of their own, declaring such an attribute.
	 */
	@Nested
	@DisplayName("Catalog index")
	class CatalogIndexSide {
		/**
		 * Its own catalog rather than the shared fixture: that one declares no globally-unique attribute, so it never
		 * exercises this path, and the exact counts its tests assert would move under the writes made here.
		 */
		private static final String GLOBAL_CATALOG = CATALOG + "GlobalUnique";

		@BeforeEach
		void buildGloballyUniqueCatalog() {
			// both attributes are nullable, because the lazy-allocation case needs a product that carries no globally
			// unique value at all and the other two need one that carries nothing else
			IndexUsageStatisticsTest.this.evita.defineCatalog(GLOBAL_CATALOG)
				.withAttribute("globalCode", String.class, thatIs -> thatIs.uniqueGlobally().nullable())
				.updateViaNewSession(IndexUsageStatisticsTest.this.evita);
			IndexUsageStatisticsTest.this.evita.updateCatalog(
				GLOBAL_CATALOG,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute("globalCode")
						.withAttribute("code", String.class, whichIs -> whichIs.filterable().nullable())
						.updateVia(session);
				}
			);
		}

		@Test
		@DisplayName("A write of a globally unique value counts once, however often it acquires the index")
		void shouldCountOneUpdatePerEntityMutationOnTheCatalogIndex() {
			final long before = catalogRow(Scope.LIVE).updateCount();

			createProduct(1, "product-1");

			assertEquals(
				before + 1, catalogRow(Scope.LIVE).updateCount(),
				"The entity mutation that wrote a globally unique value must have counted exactly one maintenance " +
					"on the catalog index"
			);

			// changing the value acquires the catalog index twice inside a single entity mutation - once to drop the
			// old value, once to register the new one - so a missing deduplication shows up here as two counts for one
			// mutation, and nowhere else in the suite
			renameProduct(1, "product-1-renamed");

			final BrowsedIndex after = catalogRow(Scope.LIVE);
			assertEquals(
				before + 2, after.updateCount(),
				"One entity mutation counted its two acquisitions of the catalog index separately: " + after
			);
			assertNotNull(after.lastUpdatedAt(), "An index that counted maintenance must carry the stamp of it");

			final IndexDetail detail = IndexUsageStatisticsTest.this.evita.management()
				.getIndexDetail(GLOBAL_CATALOG, null, after.indexPrimaryKey());
			assertEquals(after.updateCount(), detail.updateCount(), "The drill-down lost the count the row reports");
			assertEquals(after.lastUpdatedAt(), detail.lastUpdatedAt());
		}

		@Test
		@DisplayName("A write touching nothing globally unique leaves the catalog index alone")
		void shouldNotCountAWriteThatNeverAcquiresTheCatalogIndex() {
			createProduct(1, "product-1");
			final long before = catalogRow(Scope.LIVE).updateCount();

			// the catalog index is acquired lazily, by a write that actually maintains a globally unique value and by
			// no other - a product carrying none must leave the reading exactly where it was
			IndexUsageStatisticsTest.this.evita.updateCatalog(
				GLOBAL_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, 2).setAttribute("code", "product-2")
					);
				}
			);

			assertEquals(
				before, catalogRow(Scope.LIVE).updateCount(),
				"A mutation that never acquired the catalog index counted maintenance on it anyway"
			);
		}

		@Test
		@DisplayName("A query naming no collection is counted on the catalog index")
		void shouldCountTheCatalogIndexOfACollectionLessQuery() {
			createProduct(1, "product-1");
			final long catalogBefore = catalogRow(Scope.LIVE).queryCount();
			final long collectionBefore = productGlobalRow().queryCount();

			// naming no collection leaves the planner without an entity global index to select, so the catalog indexes
			// are the whole target index set of the plan that executes
			// a block body, not an expression: a bare lambda returning a value matches both the consumer and the
			// function overload of `queryCatalog`
			final EntityReference found = IndexUsageStatisticsTest.this.evita.queryCatalog(
				GLOBAL_CATALOG,
				session -> {
					return session.queryOne(
						query(filterBy(attributeEquals("globalCode", "product-1"))),
						EntityReference.class
					).orElseThrow();
				}
			);
			assertEquals(ENTITY_PRODUCT, found.getType(), "The fixture must resolve the value to its product");

			final BrowsedIndex after = catalogRow(Scope.LIVE);
			assertEquals(catalogBefore + 1, after.queryCount(), "The executed plan's index must have counted a query");
			assertNotNull(after.lastQueriedAt(), "An index that counted a query must carry the stamp of it");
			assertEquals(
				collectionBefore, productGlobalRow().queryCount(),
				"The collection's own global index was no part of the winning target set and must not have counted"
			);
		}

		/**
		 * Reads the browse row of the catalog's own index in one scope, through the null-entity-type route.
		 *
		 * @param scope scope of the index
		 * @return the row
		 */
		@Nonnull
		private BrowsedIndex catalogRow(@Nonnull Scope scope) {
			for (final BrowsedIndex index : browseIndexesOf(null)) {
				if (scope == index.scope()) {
					return index;
				}
			}
			throw new AssertionError("The catalog holds no index in scope " + scope);
		}

		/**
		 * Reads the browse row of the product collection's global index, the control the catalog readings are compared
		 * against.
		 *
		 * @return the row
		 */
		@Nonnull
		private BrowsedIndex productGlobalRow() {
			for (final BrowsedIndex index : browseIndexesOf(ENTITY_PRODUCT)) {
				if (index.indexType() == EntityIndexType.GLOBAL) {
					return index;
				}
			}
			throw new AssertionError("The product collection holds no global index");
		}

		/**
		 * Reads every index of one collection, or the catalog's own when no collection is named, freshly on each call.
		 *
		 * @param entityType the collection to read, or null for the catalog's own indexes
		 * @return the rows
		 */
		@Nonnull
		private BrowsedIndex[] browseIndexesOf(@Nullable String entityType) {
			return IndexUsageStatisticsTest.this.evita.management().browseIndexes(
				GLOBAL_CATALOG, entityType,
				new IndexBrowseCriteria(
					1, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.MAP_ORDER, OrderDirection.ASC,
					EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
				)
			).indexes();
		}

		/**
		 * Writes one product carrying a globally unique value, which is what acquires the catalog index.
		 *
		 * @param primaryKey primary key of the product to create
		 * @param globalCode the globally unique value it carries
		 */
		private void createProduct(int primaryKey, @Nonnull String globalCode) {
			IndexUsageStatisticsTest.this.evita.updateCatalog(
				GLOBAL_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, primaryKey).setAttribute("globalCode", globalCode)
					);
				}
			);
		}

		/**
		 * Changes an existing product's globally unique value, which acquires the catalog index twice within one entity
		 * mutation - once for the removal of the old value and once for the upsert of the new one.
		 *
		 * @param primaryKey primary key of the product to change
		 * @param globalCode the value to give it
		 */
		private void renameProduct(int primaryKey, @Nonnull String globalCode) {
			IndexUsageStatisticsTest.this.evita.updateCatalog(
				GLOBAL_CATALOG,
				session -> {
					session.upsertEntity(
						session.getEntity(ENTITY_PRODUCT, primaryKey, attributeContentAll())
							.orElseThrow()
							.openForWrite()
							.setAttribute("globalCode", globalCode)
					);
				}
			);
		}

	}

	/**
	 * Runs a query naming one category, which makes the planner build a candidate plan around that category's own
	 * per-referenced-entity index alongside the global one.
	 *
	 * @param categoryPrimaryKey the category to filter on
	 */
	private void queryProductsOfCategory(int categoryPrimaryKey) {
		this.evita.queryCatalog(
			CATALOG,
			session -> {
				session.queryList(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(referenceHaving("categories", entityPrimaryKeyInSet(categoryPrimaryKey)))
					),
					EntityReferenceContract.class
				);
			}
		);
	}

	/**
	 * Writes one more product into a category, which acquires that category's index and the global one and nothing
	 * else.
	 *
	 * @param productPrimaryKey  primary key of the product to create
	 * @param categoryPrimaryKey the category to reference
	 */
	private void upsertProductInCategory(int productPrimaryKey, int categoryPrimaryKey) {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, productPrimaryKey)
						.setAttribute("code", "product-" + productPrimaryKey)
						.setReference("categories", categoryPrimaryKey)
				);
			}
		);
	}

	/**
	 * Reads the browse row of the product collection's global index.
	 *
	 * @return the row
	 */
	@Nonnull
	private BrowsedIndex rowOfGlobalIndex() {
		final BrowsedIndex row = findRow(index -> index.indexType() == EntityIndexType.GLOBAL);
		assertNotNull(row, "The product collection must hold a global index");
		return row;
	}

	/**
	 * Reads the browse row of the per-referenced-entity index covering one category.
	 *
	 * @param categoryPrimaryKey the category the index is bound to
	 * @return the row
	 */
	@Nonnull
	private BrowsedIndex rowOfReferenceIndex(int categoryPrimaryKey) {
		final BrowsedIndex row = findRow(
			index -> index.indexType() == EntityIndexType.REFERENCED_ENTITY
				&& index.discriminatorPrimaryKey() != null
				&& index.discriminatorPrimaryKey() == categoryPrimaryKey
		);
		assertNotNull(row, "No per-referenced-entity index covers category " + categoryPrimaryKey);
		return row;
	}

	/**
	 * Sums the query counts of every index the product collection holds, so a test can assert that *nothing* anywhere
	 * counted rather than naming the indexes it expects to have been passed over.
	 *
	 * @return the total across the collection's indexes
	 */
	private long totalQueryCountAcrossTheCollection() {
		long total = 0;
		for (final BrowsedIndex index : browsePage()) {
			total += index.queryCount();
		}
		return total;
	}

	/**
	 * Finds the first browse row matching the predicate, reading a fresh page each time so every assertion sees the
	 * counters as they are now rather than as they were when the test started.
	 *
	 * @param predicate what to look for
	 * @return the matching row, or null when none matches
	 */
	@Nullable
	private BrowsedIndex findRow(@Nonnull Predicate<BrowsedIndex> predicate) {
		for (final BrowsedIndex index : browsePage()) {
			if (predicate.test(index)) {
				return index;
			}
		}
		return null;
	}

	/**
	 * Reads every index of the product collection in one page, freshly on each call so no assertion can be made
	 * against counters that were sampled earlier in the test.
	 *
	 * @return the collection's index rows
	 */
	@Nonnull
	private BrowsedIndex[] browsePage() {
		return this.evita.management().browseIndexes(
			CATALOG, ENTITY_PRODUCT,
			new IndexBrowseCriteria(
				1, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.MAP_ORDER, OrderDirection.ASC,
				EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
			)
		).indexes();
	}

	/**
	 * Closes the embedded instance and opens a new one over the same directories, so what follows reads state that was
	 * rebuilt from disk rather than state still held in memory.
	 *
	 * A freshly constructed instance loads catalogs on a background pool, so the wait is part of restarting rather
	 * than a flake being papered over.
	 */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
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
	 * Builds a fixture in which every category has a per-referenced-entity index of its own, all of the same size, so
	 * a named index and an unnamed one differ in nothing but the traffic they saw.
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
						ReferenceSchemaEditor::indexedForFilteringAndPartitioning
					)
					.updateVia(session);
				for (int i = 1; i <= CATEGORY_COUNT; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, i));
				}
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, i)
							.setAttribute("code", "product-" + i)
							.setReference("categories", ((i - 1) % CATEGORY_COUNT) + 1)
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
