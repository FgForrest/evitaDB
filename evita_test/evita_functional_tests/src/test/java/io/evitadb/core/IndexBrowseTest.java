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
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionIndexSummary;
import io.evitadb.api.statistics.EntityIndexKind;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the paginated, filtered listing of a collection's entity indexes.
 *
 * The fixture is chosen to be **tie-dominated on purpose**, because that is where this surface is fragile: ten
 * categories each referenced by exactly five products produce ten per-referenced-entity indexes covering exactly five
 * entities each. Ordering by entity count alone would leave those ten free to come back in any order, and successive
 * pages would then re-cut a differently-permuted tie block - showing some indexes twice while never showing others.
 * No naturally-written test notices that; the paging tests below exist specifically to.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("Entity index browse")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(MANAGEMENT)
class IndexBrowseTest implements EvitaTestSupport {
	private static final String CATALOG = "indexBrowseTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	/** How many categories the fixture declares, and therefore how many per-referenced-entity indexes it produces. */
	private static final int CATEGORY_COUNT = 10;
	/** How many products reference each category - the size every one of the tied indexes reports. */
	private static final int PRODUCTS_PER_CATEGORY = 5;
	private static final int PRODUCT_COUNT = CATEGORY_COUNT * PRODUCTS_PER_CATEGORY;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("IndexBrowseTest");
		this.evita = new Evita(getEvitaConfiguration());
		buildCatalog();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Paging")
	class Paging {

		@Test
		@DisplayName("Paging through every index in map order yields each exactly once")
		void shouldPageThroughEveryIndexInMapOrderWithoutRepeatingOrLosingOne() {
			assertPagingCoversEveryIndexExactlyOnce(IndexBrowseOrdering.MAP_ORDER, 3);
		}

		@Test
		@DisplayName("Paging through a block of equally-sized indexes yields each exactly once")
		void shouldPageThroughTiedIndexesWithoutRepeatingOrLosingOne() {
			// the load-bearing test of this class. Ten indexes report the same entity count, so ordering by that
			// count alone leaves their relative order to chance - and a page boundary drawn through the middle of
			// that block would then show some of them on both pages and others on neither
			assertPagingCoversEveryIndexExactlyOnce(IndexBrowseOrdering.BY_ENTITY_COUNT_DESC, 3);
		}

		@Test
		@DisplayName("Re-reading the same page returns the same indexes")
		void shouldReturnAStablePageAcrossRepeatedReads() {
			final IndexBrowseResult first = browse(criteria(2, 3, IndexBrowseOrdering.BY_ENTITY_COUNT_DESC));
			final IndexBrowseResult second = browse(criteria(2, 3, IndexBrowseOrdering.BY_ENTITY_COUNT_DESC));
			assertEquals(
				identitiesOf(first), identitiesOf(second),
				"The same page of an unchanged collection came back with different indexes - the order is not " +
					"total, so the tie-break is leaving some pairs to chance"
			);
		}

		@Test
		@DisplayName("A page past the end is empty rather than an error")
		void shouldReturnAnEmptyPagePastTheEnd() {
			final IndexBrowseResult result = browse(criteria(1_000, 10, IndexBrowseOrdering.MAP_ORDER));
			assertEquals(0, result.indexes().length, "A page past the end must hold no indexes");
			assertTrue(
				result.totalRecordCount() > 0,
				"The match count describes the whole result, not the page, so it stays positive past the end"
			);
		}

		@Test
		@DisplayName("The match count agrees with the index summary reached by a different route")
		void shouldReportAMatchCountAgreeingWithTheIndexSummary() {
			// an independent oracle: `INDEX_SUMMARY` counts indexes from the maintained per-kind counters, while the
			// browse counts them by walking the map. Agreement catches drift in either direction
			final CollectionIndexSummary summary = this.summaryOfProducts();
			final IndexBrowseResult result = browse(criteria(1, 1, IndexBrowseOrdering.MAP_ORDER));
			assertEquals(
				summary.totalIndexCount(), result.totalRecordCount(),
				"An unfiltered browse matches every index the collection holds, so its match count must equal the " +
					"total the summary reports"
			);
		}

		@Test
		@DisplayName("Every page reports the catalog version it was read at")
		void shouldReportTheCatalogVersionEachPageWasReadAt() {
			final IndexBrowseResult first = browse(criteria(1, 3, IndexBrowseOrdering.MAP_ORDER));
			final IndexBrowseResult second = browse(criteria(2, 3, IndexBrowseOrdering.MAP_ORDER));
			assertEquals(
				first.catalogVersion(), second.catalogVersion(),
				"Nothing was written between the two reads, so both pages describe one index set and must say so"
			);
		}

		@Nonnull
		private CollectionIndexSummary summaryOfProducts() {
			return IndexBrowseTest.this.evita.management()
				.getEntityCollectionStatistics(
					CATALOG, ENTITY_PRODUCT, EnumSet.of(CatalogStatisticsComponent.INDEX_SUMMARY)
				)
				.indexSummaryIfPresent()
				.orElseThrow();
		}
	}

	@Nested
	@DisplayName("Catalog version")
	class CatalogVersion {

		@Test
		@DisplayName("A warming-up catalog pins the version even as its index set grows")
		void shouldPinTheCatalogVersionWhileTheCatalogIsWarmingUp() {
			// pins the documented caveat rather than the behaviour anyone would want. The version advances per
			// committed transaction and a warming-up catalog runs none, so cross-page comparison is blind exactly
			// during a bulk load - when the index set churns hardest. If this test ever starts failing because the
			// version *did* move, the caveat on `IndexBrowseResult` and on the proto field should come off
			final IndexBrowseResult before = browse(criteria(1, 1, IndexBrowseOrdering.MAP_ORDER));
			addOneMoreReferencedCategory();
			final IndexBrowseResult after = browse(criteria(1, 1, IndexBrowseOrdering.MAP_ORDER));

			assertTrue(
				after.totalRecordCount() > before.totalRecordCount(),
				"Referencing a category nothing referenced before must create an index, or this test proves nothing"
			);
			assertEquals(
				before.catalogVersion(), after.catalogVersion(),
				"The index set grew, so a client comparing catalog versions across pages would have been told " +
					"nothing changed - which is the caveat both the record and the proto field now carry"
			);
		}

		@Test
		@DisplayName("A live catalog moves the version with every committed write")
		void shouldAdvanceTheCatalogVersionOnceTheCatalogIsAlive() {
			IndexBrowseTest.this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);

			final IndexBrowseResult before = browse(criteria(1, 1, IndexBrowseOrdering.MAP_ORDER));
			addOneMoreReferencedCategory();
			final IndexBrowseResult after = browse(criteria(1, 1, IndexBrowseOrdering.MAP_ORDER));

			// the other half of the caveat: once transactions are running the version does discriminate, which is
			// what makes the documented cross-page protocol worth following at all
			assertTrue(
				after.catalogVersion() > before.catalogVersion(),
				"A committed write must move the catalog version, but it stayed at " + before.catalogVersion()
			);
		}

		/**
		 * Adds a category nothing referenced before and one product referencing it, which is the smallest change that
		 * creates a per-referenced-entity index.
		 */
		private void addOneMoreReferencedCategory() {
			final int freshKey = CATEGORY_COUNT + 1;
			IndexBrowseTest.this.evita.updateCatalog(
				CATALOG,
				session -> {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, freshKey));
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, PRODUCT_COUNT + 1)
							.setAttribute("code", "product-" + (PRODUCT_COUNT + 1))
							.setReference("categories", freshKey)
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Ordering")
	class Ordering {

		@Test
		@DisplayName("The largest indexes come first")
		void shouldReturnTheLargestIndexesFirst() {
			final IndexBrowseResult result = browse(
				criteria(1, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.BY_ENTITY_COUNT_DESC)
			);
			int previous = Integer.MAX_VALUE;
			for (final BrowsedIndex index : result.indexes()) {
				assertTrue(
					index.entityCount() <= previous,
					"Indexes came back out of descending order at " + index + " (previous count " + previous + ")"
				);
				previous = index.entityCount();
			}
			// the collection-wide indexes cover every product, the per-category ones only their own five, so the
			// ordering is meaningful rather than accidentally satisfied by equal counts throughout
			assertEquals(
				PRODUCT_COUNT, result.indexes()[0].entityCount(),
				"The widest index covers every product and must therefore lead the descending order"
			);
		}

		@Test
		@DisplayName("Equally-sized indexes are ordered by kind, then scope, then discriminator")
		void shouldBreakTiesDeterministically() {
			final IndexBrowseResult result = browse(
				criteria(1, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.BY_ENTITY_COUNT_DESC)
			);
			final List<Integer> tiedPrimaryKeys = new ArrayList<>(CATEGORY_COUNT);
			for (final BrowsedIndex index : result.indexes()) {
				if (index.indexKind() == EntityIndexKind.REFERENCED_ENTITY &&
					index.entityCount() == PRODUCTS_PER_CATEGORY) {
					tiedPrimaryKeys.add(index.discriminatorPrimaryKey());
				}
			}
			assertEquals(
				CATEGORY_COUNT, tiedPrimaryKeys.size(),
				"The fixture references every one of its categories the same number of times, so all of them must " +
					"appear in the tie block"
			);
			// the discriminator is the last tiebreaker and every index in this block shares kind and scope, so the
			// block has to come back in ascending primary-key order - anything else means the order is not total
			final List<Integer> sorted = new ArrayList<>(tiedPrimaryKeys);
			sorted.sort(null);
			assertEquals(
				sorted, tiedPrimaryKeys,
				"Indexes tied on entity count must fall back to their discriminator, but the block came back in a " +
					"different order: " + tiedPrimaryKeys
			);
		}
	}

	@Nested
	@DisplayName("Filtering")
	class Filtering {

		@Test
		@DisplayName("Only indexes of the requested kinds come back")
		void shouldKeepOnlyTheRequestedKinds() {
			final IndexBrowseResult result = browse(
				new IndexBrowseCriteria(
					1, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.MAP_ORDER,
					EnumSet.of(EntityIndexKind.REFERENCED_ENTITY), Set.of(), Set.of()
				)
			);
			assertEquals(
				CATEGORY_COUNT, result.totalRecordCount(),
				"The fixture holds one per-referenced-entity index per category and the filter must isolate exactly " +
					"those"
			);
			for (final BrowsedIndex index : result.indexes()) {
				assertEquals(EntityIndexKind.REFERENCED_ENTITY, index.indexKind());
			}
		}

		@Test
		@DisplayName("The scopes partition the whole index set between them")
		void shouldPartitionTheIndexSetByScope() {
			final int live = browse(criteriaWithScopes(EnumSet.of(Scope.LIVE))).totalRecordCount();
			final int archived = browse(criteriaWithScopes(EnumSet.of(Scope.ARCHIVED))).totalRecordCount();
			final int unfiltered = browse(criteria(1, 1, IndexBrowseOrdering.MAP_ORDER)).totalRecordCount();
			// a partition property rather than a fixed number, so it holds whether or not the fixture archives
			// anything - and it fails the moment a scope filter starts dropping or double-counting
			assertEquals(
				unfiltered, live + archived,
				"Every index belongs to exactly one scope, so the two filtered counts must add up to the total"
			);
		}

		@Test
		@DisplayName("Only indexes of the requested reference come back, and global indexes never do")
		void shouldKeepOnlyTheRequestedReference() {
			final IndexBrowseResult result = browse(
				new IndexBrowseCriteria(
					1, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.MAP_ORDER,
					EnumSet.noneOf(EntityIndexKind.class), Set.of(), Set.of("categories")
				)
			);
			assertTrue(result.totalRecordCount() > 0, "The fixture indexes the `categories` reference");
			for (final BrowsedIndex index : result.indexes()) {
				assertEquals(
					"categories", index.referenceName(),
					"A reference-name filter must not admit an index bound to another reference, or to none"
				);
				assertNotEquals(
					EntityIndexKind.GLOBAL, index.indexKind(),
					"A global index is bound to no reference at all and can never satisfy a reference filter"
				);
			}
		}

		@Nonnull
		private IndexBrowseCriteria criteriaWithScopes(@Nonnull Set<Scope> scopes) {
			return new IndexBrowseCriteria(
				1, 1, IndexBrowseOrdering.MAP_ORDER, EnumSet.noneOf(EntityIndexKind.class), scopes, Set.of()
			);
		}
	}

	@Nested
	@DisplayName("Rejected requests")
	class RejectedRequests {

		@Test
		@DisplayName("A reference the schema does not declare is an error, not an empty page")
		void shouldRejectAnUndeclaredReferenceName() {
			// the alternative - answering with an empty page - would let a typo read as "this reference has no
			// indexes", which is the one answer an operator investigating index growth must not be given wrongly
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> browse(
					new IndexBrowseCriteria(
						1, 10, IndexBrowseOrdering.MAP_ORDER,
						EnumSet.noneOf(EntityIndexKind.class), Set.of(), Set.of("categoriez")
					)
				)
			);
		}

		@Test
		@DisplayName("An unknown collection is an error, not an empty page")
		void shouldRejectAnUnknownCollection() {
			assertThrows(
				CollectionNotFoundException.class,
				() -> IndexBrowseTest.this.evita.management().browseEntityCollectionIndexes(
					CATALOG, "noSuchCollection", criteria(1, 10, IndexBrowseOrdering.MAP_ORDER)
				)
			);
		}

		@Test
		@DisplayName("Paging outside the permitted range is refused")
		void shouldRejectPagingOutsideThePermittedRange() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> criteria(0, 10, IndexBrowseOrdering.MAP_ORDER),
				"Page numbers are 1-indexed, so page zero addresses nothing"
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> criteria(1, 0, IndexBrowseOrdering.MAP_ORDER),
				"A page of no indexes is not a meaningful request"
			);
			// rejected rather than clamped: a clamped page is indistinguishable from a complete one, so a client
			// paging until it sees a short page would stop early believing it had seen everything
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> criteria(1, IndexBrowseCriteria.MAX_PAGE_SIZE + 1, IndexBrowseOrdering.MAP_ORDER),
				"A page size above the maximum must be refused, never silently reduced"
			);
		}
	}

	/**
	 * Pages through the whole result in the given order and asserts that the pages together describe every matching
	 * index exactly once.
	 *
	 * This is the assertion an unstable order fails: a tie block re-permuted between two requests puts some of its
	 * members on both sides of a page boundary and others on neither, so the union comes up short while the running
	 * total comes up long.
	 *
	 * @param ordering the order to page in
	 * @param pageSize how many indexes to take per page - deliberately small, so boundaries fall inside the tie block
	 */
	private void assertPagingCoversEveryIndexExactlyOnce(@Nonnull IndexBrowseOrdering ordering, int pageSize) {
		final IndexBrowseResult first = browse(criteria(1, pageSize, ordering));
		final int total = first.totalRecordCount();
		final Set<String> seen = new LinkedHashSet<>(total);
		int collected = 0;

		final int pageCount = (total + pageSize - 1) / pageSize;
		for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
			final IndexBrowseResult page = browse(criteria(pageNumber, pageSize, ordering));
			assertEquals(
				total, page.totalRecordCount(),
				"The match count changed mid-browse although nothing was written between the pages"
			);
			for (final BrowsedIndex index : page.indexes()) {
				collected++;
				assertTrue(
					seen.add(identityOf(index)),
					"Index " + index + " appeared on more than one page of a single browse - the order is not " +
						"total, so the page boundary cut through a block the engine re-permuted"
				);
			}
		}

		assertEquals(total, collected, "The pages together returned a different number of indexes than matched");
		assertEquals(
			total, seen.size(),
			"The pages together did not describe every matching index - some were never returned at all"
		);
	}

	/**
	 * Renders an index's identity - the triplet that distinguishes it from every other index of the collection.
	 *
	 * @param index the index to identify
	 * @return a stable textual identity
	 */
	@Nonnull
	private static String identityOf(@Nonnull BrowsedIndex index) {
		return index.indexKind() + "/" + index.scope() + "/" + index.referenceName() + "/" +
			index.discriminatorPrimaryKey();
	}

	/**
	 * Renders the identities of a whole page, in the order the page returned them.
	 *
	 * @param result the page to render
	 * @return the page's identities, order preserved
	 */
	@Nonnull
	private static List<String> identitiesOf(@Nonnull IndexBrowseResult result) {
		final List<String> identities = new ArrayList<>(result.indexes().length);
		for (final BrowsedIndex index : result.indexes()) {
			identities.add(identityOf(index));
		}
		return identities;
	}

	/**
	 * Builds unfiltered criteria for one page.
	 *
	 * @param pageNumber which page to ask for, 1-indexed
	 * @param pageSize   how many indexes the page holds
	 * @param ordering   the order to impose
	 * @return the criteria
	 */
	@Nonnull
	private static IndexBrowseCriteria criteria(int pageNumber, int pageSize, @Nonnull IndexBrowseOrdering ordering) {
		return new IndexBrowseCriteria(
			pageNumber, pageSize, ordering, EnumSet.noneOf(EntityIndexKind.class), Set.of(), Set.of()
		);
	}

	/**
	 * Browses the fixture's product collection.
	 *
	 * @param criteria what to select, in what order, and which page
	 * @return the resulting page
	 */
	@Nonnull
	private IndexBrowseResult browse(@Nonnull IndexBrowseCriteria criteria) {
		return this.evita.management().browseEntityCollectionIndexes(CATALOG, ENTITY_PRODUCT, criteria);
	}

	/**
	 * Builds the tie-dominated fixture: every category is referenced by the same number of products, so every
	 * per-referenced-entity index reports the same entity count.
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
							// spreads the products evenly, which is what makes every reference index the same size
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
