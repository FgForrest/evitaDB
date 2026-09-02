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

package io.evitadb.core.query;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.exception.ReferenceNotFacetedException;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.usage.SchemaCapabilityKey;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry.UsageEntry;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the query side of the capabilities a **reference and an entity declare on themselves** - `faceted()`,
 * `withHierarchy()` and `withPrice()` - as opposed to the attribute and compound flags
 * `RequestedCapabilityAccumulationTest` pins.
 *
 * These reach the accumulator from translators of their own rather than from the one attribute accessor, and the
 * hazard is correspondingly different: not deduplication, which is already established one level down, but **whose
 * flag a query actually depended on**. Two of the sites make a claim about that in a comment and nothing else checks
 * it - a `hierarchyWithin` naming another collection's tree records against no registry at all, and a price histogram
 * counts the flag on its own, so a catalog whose only price usage is the histogram does not report it as unused.
 *
 * Every case reads the **difference** one query made to a registry, exactly as the sibling class does, so that neither
 * the fixture's own writes nor an earlier case can be mistaken for the query under test. Where a capability could
 * plausibly be counted per candidate plan rather than per query, the query is built to offer more than one candidate
 * and the assertion is on an exact one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see QueryPlanningContext#recordRequestedReferenceCapability
 * @see QueryPlanningContext#recordRequestedEntityCapability
 */
@DisplayName("The flags a reference and the entity declare on themselves are requested by queries")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
class ReferenceAndEntityCapabilityRequestTest implements EvitaTestSupport {
	private static final String CATALOG = "referenceAndEntityCapabilityRequestTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String REFERENCE_CATEGORIES = "categories";
	/** A second indexed reference that declares no faceting - the negative side of every facet case below. */
	private static final String REFERENCE_TAGS = "tags";
	private static final String EXTERNAL_TAG_TYPE = "tag";
	private static final String ATTRIBUTE_ORDER_IN_CATEGORY = "orderInCategory";
	/** A histogram carrying a value expression - the only kind anything ever maintains. */
	private static final String HISTOGRAM_ORDER_IN_CATEGORY = "orderInCategoryHistogram";
	/** A count histogram - declared, but with no value expression there is nothing to maintain it. */
	private static final String HISTOGRAM_TAG_COUNT = "tagCountHistogram";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final int CATEGORY_COUNT = 4;
	private static final int PRODUCTS_PER_CATEGORY = 5;
	private static final int PRODUCT_COUNT = CATEGORY_COUNT * PRODUCTS_PER_CATEGORY;
	/** The category every multi-candidate query names, so the planner builds a plan around its own index. */
	private static final int QUERIED_CATEGORY = 2;

	private static final SchemaCapabilityKey CATEGORIES_FACETED = SchemaCapabilityKey.reference(
		REFERENCE_CATEGORIES, Capability.FACETED, Scope.LIVE
	);
	private static final SchemaCapabilityKey TAGS_FACETED = SchemaCapabilityKey.reference(
		REFERENCE_TAGS, Capability.FACETED, Scope.LIVE
	);
	private static final SchemaCapabilityKey CATEGORIES_BUCKETED = SchemaCapabilityKey.reference(
		REFERENCE_CATEGORIES, Capability.BUCKETED, Scope.LIVE
	);
	private static final SchemaCapabilityKey TAGS_BUCKETED = SchemaCapabilityKey.reference(
		REFERENCE_TAGS, Capability.BUCKETED, Scope.LIVE
	);
	private static final SchemaCapabilityKey PRODUCT_PRICE_INDEXED = SchemaCapabilityKey.entity(
		ENTITY_PRODUCT, Capability.PRICED, Scope.LIVE
	);
	private static final SchemaCapabilityKey PRODUCT_HIERARCHY_INDEXED = SchemaCapabilityKey.entity(
		ENTITY_PRODUCT, Capability.HIERARCHICAL, Scope.LIVE
	);
	private static final SchemaCapabilityKey CATEGORY_HIERARCHY_INDEXED = SchemaCapabilityKey.entity(
		ENTITY_CATEGORY, Capability.HIERARCHICAL, Scope.LIVE
	);

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceAndEntityCapabilityRequestTest");
		this.evita = new Evita(getEvitaConfiguration());
		buildCatalog();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Faceting")
	@Tag(FACET)
	class Faceted {

		@Test
		@DisplayName("A facet summary counts the faceting of every reference it summarises, once each")
		void shouldRecordFacetedWhenASummaryIsRequested() {
			// the summary translator loops every reference on every translation, so `present` would prove nothing
			// here - the query is deliberately given more than one candidate index set, and the count still has to
			// come out at one
			final Map<SchemaCapabilityKey, Long> requested = requestedByProductQuery(
				Query.query(
					collection(ENTITY_PRODUCT),
					filterBy(referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))),
					require(facetSummary())
				)
			);

			assertRequested(requested, CATEGORIES_FACETED);
			assertNotRequested(
				requested, TAGS_FACETED,
				"A reference declaring no faceting was counted as depended upon by the summary that skipped it"
			);
		}

		@Test
		@DisplayName("A summary of one reference counts that reference, through a translator of its own")
		void shouldRecordFacetedWhenASummaryOfReferenceIsRequested() {
			// the *OfReference forms do not route through the all-references translator above, so deleting either
			// recording site leaves the other passing - which is why both are asserted separately
			final Map<SchemaCapabilityKey, Long> requested = requestedByProductQuery(
				Query.query(
					collection(ENTITY_PRODUCT),
					filterBy(referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))),
					require(facetSummaryOfReference(REFERENCE_CATEGORIES))
				)
			);

			assertRequested(requested, CATEGORIES_FACETED);
		}

		@Test
		@DisplayName("A facet filter on a reference declaring no faceting counts nothing")
		void shouldRecordNothingWhenTheReferenceIsNotFaceted() {
			// every faceted site records *past* the assertion that established the flag, so a query rejected for
			// lacking it must leave the count where it was - the reading means `a query depended on this flag`, not
			// `a query mentioned this reference`
			final Map<SchemaCapabilityKey, Long> before = requestedCounts(ENTITY_PRODUCT);

			assertThrows(
				ReferenceNotFacetedException.class,
				() -> executeAgainstProducts(
					Query.query(
						collection(ENTITY_PRODUCT),
						filterBy(facetHaving(REFERENCE_TAGS, entityPrimaryKeyInSet(1)))
					)
				)
			);

			assertNotRequested(
				requestedCountsSince(ENTITY_PRODUCT, before), TAGS_FACETED,
				"A rejected query counted the flag it was rejected for lacking"
			);
		}

	}

	@Nested
	@DisplayName("Bucketed histograms")
	@Tag(HISTOGRAM)
	class Bucketed {

		@Test
		@DisplayName("A reference histogram counts the reference's `bucketed()`")
		void shouldRecordBucketedWhenAHistogramIsRequested() {
			// `bucketed()` is the one flag no query names directly - the histogram is reached through its declared
			// definition - so this is the only shape that can move the count at all
			final Map<SchemaCapabilityKey, Long> requested = requestedByProductQuery(
				Query.query(
					collection(ENTITY_PRODUCT),
					require(
						referenceSummaryWithHistograms(
							null, null, null,
							histogramStatistics(10, HISTOGRAM_ORDER_IN_CATEGORY)
						)
					)
				)
			);

			assertRequested(requested, CATEGORIES_BUCKETED);
		}

		@Test
		@DisplayName("A count-only histogram is left without a row, though the query named it")
		void shouldMintNoRowForACountOnlyHistogram() {
			// a histogram declared without a value expression yields no trigger, so nothing ever maintains it - which
			// is why the seeding enumeration and the update side both refuse it. A request site minting it anyway
			// would put a row on the surface that the other two keep off, and the assertion is therefore on the row
			// existing at all rather than on its count: the query is rejected before any count could move
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> executeAgainstProducts(
					Query.query(
						collection(ENTITY_PRODUCT),
						require(
							referenceSummaryWithHistograms(
								null, null, null,
								histogramStatistics(10, HISTOGRAM_TAG_COUNT)
							)
						)
					)
				)
			);

			assertNoRowHeld(
				TAGS_BUCKETED,
				"A histogram nothing can maintain was minted a row anyway - it reads as a capability going " +
					"unmaintained, which is exactly what the seeding rule refuses to put there"
			);
		}

	}

	@Nested
	@DisplayName("The flags the entity declares on itself")
	@Tag(HIERARCHY)
	@Tag(PRICE)
	class EntityOwnFlags {

		@Test
		@DisplayName("Filtering within a collection's own tree counts its hierarchy indexing")
		void shouldRecordHierarchyIndexedWhenFilteringWithinItsOwnHierarchy() {
			final Map<SchemaCapabilityKey, Long> requested = requestedBy(
				ENTITY_CATEGORY,
				Query.query(
					collection(ENTITY_CATEGORY),
					filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(1)))
				)
			);

			assertRequested(requested, CATEGORY_HIERARCHY_INDEXED);
		}

		@Test
		@DisplayName("Filtering within another collection's tree counts nothing, on either registry")
		void shouldNotRecordHierarchyIndexedWhenTheTreeBelongsToAnotherCollection() {
			// the attribution rule the site states in a comment and nothing else checks: the flag verified belongs to
			// the *target* schema, and a request is only ever filed against the collection being queried - so this
			// query, which depends on `category`'s hierarchy while querying `product`, files against neither
			final Map<SchemaCapabilityKey, Long> productsBefore = requestedCounts(ENTITY_PRODUCT);
			final Map<SchemaCapabilityKey, Long> categoriesBefore = requestedCounts(ENTITY_CATEGORY);

			executeAgainstProducts(
				Query.query(
					collection(ENTITY_PRODUCT),
					filterBy(hierarchyWithin(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(1)))
				)
			);

			assertNotRequested(
				requestedCountsSince(ENTITY_PRODUCT, productsBefore), PRODUCT_HIERARCHY_INDEXED,
				"The queried collection was credited with a hierarchy flag it does not even declare"
			);
			assertNotRequested(
				requestedCountsSince(ENTITY_CATEGORY, categoriesBefore), CATEGORY_HIERARCHY_INDEXED,
				"A query against `" + ENTITY_PRODUCT + "` filed a request against another collection's registry"
			);
		}

		@Test
		@DisplayName("A price filter counts the entity's price indexing")
		void shouldRecordPriceIndexedWhenFilteringByPrice() {
			assertRequested(requestedByProductQuery(priceFilteringQuery()), PRODUCT_PRICE_INDEXED);
		}

		@Test
		@DisplayName("A price histogram counts the flag on its own, without any price filter")
		void shouldRecordPriceIndexedWhenAPriceHistogramIsRequested() {
			// the site exists precisely for a catalog whose only price usage is the histogram, so a query that also
			// filtered by price would prove nothing about it
			final Map<SchemaCapabilityKey, Long> requested = requestedByProductQuery(
				Query.query(
					collection(ENTITY_PRODUCT),
					filterBy(
						and(
							priceInPriceLists(PRICE_LIST_BASIC),
							priceInCurrency(CURRENCY_CZK)
						)
					),
					require(priceHistogram(10))
				)
			);

			assertRequested(requested, PRODUCT_PRICE_INDEXED);
		}

		@Test
		@DisplayName("One query that both filters by price and histograms it is still one request")
		void shouldRecordPriceIndexedOnceWhenOneQueryBothFiltersAndHistograms() {
			// two separate sites resolve the same holder in one query - the accumulator's identity comparison is what
			// keeps that at one, and this is the only pair of entity-flag sites able to collide
			final Map<SchemaCapabilityKey, Long> requested = requestedByProductQuery(
				Query.query(
					collection(ENTITY_PRODUCT),
					priceFilteringQuery().getFilterBy(),
					require(priceHistogram(10))
				)
			);

			assertRequested(requested, PRODUCT_PRICE_INDEXED);
		}

		/**
		 * Builds the price filter both price cases share - a full price context plus a range, which is what makes the
		 * filter translator verify `withPrice()` rather than merely carry a price constraint.
		 *
		 * @return the query
		 */
		@Nonnull
		private static Query priceFilteringQuery() {
			return Query.query(
				collection(ENTITY_PRODUCT),
				filterBy(
					and(
						priceInPriceLists(PRICE_LIST_BASIC),
						priceInCurrency(CURRENCY_CZK),
						priceBetween(BigDecimal.ONE, new BigDecimal("1000"))
					)
				)
			);
		}

	}

	/**
	 * Runs one query against the product collection and reports the request counts it moved there.
	 *
	 * @param query the query to execute
	 * @return the capabilities whose count the query moved, and by how much
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> requestedByProductQuery(@Nonnull Query query) {
		return requestedBy(ENTITY_PRODUCT, query);
	}

	/**
	 * Runs one query and reports the request counts it moved on the named collection's registry.
	 *
	 * A difference rather than an absolute reading, so that neither the fixture's own writes nor a query an earlier
	 * case ran can be mistaken for the query under test.
	 *
	 * @param entityType the collection whose registry is read
	 * @param query      the query to execute
	 * @return the capabilities whose count the query moved, and by how much
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> requestedBy(@Nonnull String entityType, @Nonnull Query query) {
		final Map<SchemaCapabilityKey, Long> before = requestedCounts(entityType);
		executeAgainstProducts(query);
		return requestedCountsSince(entityType, before);
	}

	/**
	 * Executes one query the way a client would.
	 *
	 * @param query the query to execute
	 */
	private void executeAgainstProducts(@Nonnull Query query) {
		this.evita.queryCatalog(
			CATALOG,
			session -> {
				session.queryList(query, EntityReference.class);
			}
		);
	}

	/**
	 * Reads every request count the named collection's registry currently holds.
	 *
	 * @param entityType the collection whose registry is read
	 * @return the counts, keyed by capability
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> requestedCounts(@Nonnull String entityType) {
		final Map<SchemaCapabilityKey, Long> result = new HashMap<>();
		for (final UsageEntry entry : collectionOf(entityType).getUsageRegistry().listUsages()) {
			result.put(entry.key(), entry.usage().getRequestedCount());
		}
		return result;
	}

	/**
	 * Reports how much each request count has moved since the reading was taken, dropping the ones that did not move.
	 *
	 * @param entityType the collection whose registry is read
	 * @param before     counts read before the query ran
	 * @return the capabilities whose count moved, and by how much
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> requestedCountsSince(
		@Nonnull String entityType,
		@Nonnull Map<SchemaCapabilityKey, Long> before
	) {
		final Map<SchemaCapabilityKey, Long> result = new LinkedHashMap<>();
		for (final UsageEntry entry : collectionOf(entityType).getUsageRegistry().listUsages()) {
			final long delta = entry.usage().getRequestedCount() - before.getOrDefault(entry.key(), 0L);
			if (delta != 0L) {
				result.put(entry.key(), delta);
			}
		}
		return result;
	}

	/**
	 * Asserts one logical query moved the capability's request count by exactly one.
	 *
	 * @param requested what the query moved
	 * @param key       the capability that must have moved
	 */
	private static void assertRequested(
		@Nonnull Map<SchemaCapabilityKey, Long> requested,
		@Nonnull SchemaCapabilityKey key
	) {
		assertEquals(
			1L, (long) requested.getOrDefault(key, 0L),
			"The capability " + key + " must be counted exactly once per logical query, whatever the planner did " +
				"on the way there: " + requested
		);
	}

	/**
	 * Asserts the product collection's registry holds no holder for the capability at all.
	 *
	 * A stronger statement than {@link #assertNotRequested} and a different one: resolving a key mints its holder on
	 * the spot, so a site recording a capability the query is then rejected for leaves a row behind with both counts
	 * at zero. Such a row moves no count and is therefore invisible to the reading every other case here makes.
	 *
	 * @param key     the capability no row may exist for
	 * @param message what it means if one does
	 */
	private void assertNoRowHeld(@Nonnull SchemaCapabilityKey key, @Nonnull String message) {
		for (final UsageEntry entry : collectionOf(ENTITY_PRODUCT).getUsageRegistry().listUsages()) {
			assertNotEquals(key, entry.key(), message);
		}
	}

	/**
	 * Asserts the query left the capability's request count where it was.
	 *
	 * @param requested what the query moved
	 * @param key       the capability that must not have moved
	 * @param message   what it means if it did
	 */
	private static void assertNotRequested(
		@Nonnull Map<SchemaCapabilityKey, Long> requested,
		@Nonnull SchemaCapabilityKey key,
		@Nonnull String message
	) {
		assertEquals(0L, (long) requested.getOrDefault(key, 0L), message + ": " + requested);
	}

	/**
	 * Looks a live collection up behind the public API - the registry it holds is engine-internal state.
	 *
	 * @param entityType name of the collection
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection collectionOf(@Nonnull String entityType) {
		return ((Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG))
			.getCollectionForEntityInternal(entityType)
			.orElseThrow(
				() -> new AssertionError("Catalog `" + CATALOG + "` holds no collection `" + entityType + "`")
			);
	}

	/**
	 * Builds a fixture carrying one element of every kind this class is about: a hierarchical collection, a priced
	 * collection, and a reference that is indexed, faceted and bucketed at once alongside one that is indexed and
	 * declares a histogram nothing maintains.
	 */
	private void buildCatalog() {
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withPrice()
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.faceted()
							.withAttribute(
								ATTRIBUTE_ORDER_IN_CATEGORY, Long.class,
								thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE)
							)
							.bucketed(
								HISTOGRAM_ORDER_IN_CATEGORY,
								ExpressionFactory.parse("$reference.attributes['" + ATTRIBUTE_ORDER_IN_CATEGORY + "']")
							)
					)
					.withReferenceTo(
						REFERENCE_TAGS, EXTERNAL_TAG_TYPE, Cardinality.ZERO_OR_MORE,
						// the count-only counterpart of the histogram above: declared in the same scope, and
						// maintained by nothing at all for want of a value expression
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.bucketed(HISTOGRAM_TAG_COUNT, null)
					)
					.updateVia(session);
				// a shallow tree rather than a flat list, so that filtering within it has something to descend
				session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 1));
				for (int i = 2; i <= CATEGORY_COUNT; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, i).setParent(1));
				}
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					final int productPrimaryKey = i;
					final int categoryPrimaryKey = ((i - 1) % CATEGORY_COUNT) + 1;
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, productPrimaryKey)
							.setPrice(
								productPrimaryKey, PRICE_LIST_BASIC, CURRENCY_CZK,
								BigDecimal.valueOf(productPrimaryKey),
								BigDecimal.ZERO,
								BigDecimal.valueOf(productPrimaryKey),
								true
							)
							.setReference(
								REFERENCE_CATEGORIES, categoryPrimaryKey,
								whichIs -> whichIs.setAttribute(
									ATTRIBUTE_ORDER_IN_CATEGORY, (long) productPrimaryKey
								)
							)
							.setReference(REFERENCE_TAGS, categoryPrimaryKey)
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
