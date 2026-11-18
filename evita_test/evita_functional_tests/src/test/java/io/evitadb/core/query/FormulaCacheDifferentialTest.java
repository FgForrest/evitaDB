/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.FormulaCacheSupervisor;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs one query set on an engine with the formula result cache enabled and on an otherwise identical engine with
 * the cache disabled, and requires equal responses cold, warm and after committed changes. The data cover lowest
 * price, sum and plain price handling (most lowest-price entities tie across their variants), grouped facets, a
 * category hierarchy, a nested entity filter and a bucketed reference histogram.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula result cache against a cache-disabled engine")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(CACHE)
@Tag(PRICE)
@Tag(FACET)
@Tag(HISTOGRAM)
@Tag(HIERARCHY)
@Tag(REFERENCE)
class FormulaCacheDifferentialTest implements EvitaTestSupport {
	/** Catalog built identically in both engines. */
	private static final String CATALOG = "cacheDifferential";
	/** Entity carrying prices, attributes and all references. */
	private static final String PRODUCT = "product";
	/** Referenced brand with a filterable code. */
	private static final String BRAND = "brand";
	/** Faceted parameter, grouped. */
	private static final String PARAMETER = "parameter";
	/** Group of parameters. */
	private static final String PARAMETER_GROUP = "parameterGroup";
	/** Hierarchical category. */
	private static final String CATEGORY = "category";
	/** Currency of every price. */
	private static final Currency CZK = Currency.getInstance("CZK");
	/** Fixed implicit moment, so equal queries have equal keys. */
	private static final OffsetDateTime MOMENT = OffsetDateTime.parse("2026-01-01T00:00:00Z");
	/** Number of products. */
	private static final int PRODUCTS = 60;
	/** Products `1..LOWEST_PRICE_PRODUCTS` sell the cheaper of two variants. */
	private static final int LOWEST_PRICE_PRODUCTS = 40;
	/** Products `1..TIED_PRODUCTS` sell both variants for exactly the same amount. */
	private static final int TIED_PRODUCTS = 36;
	/** Products above `LOWEST_PRICE_PRODUCTS` up to this one sum two inner records; the rest has one price. */
	private static final int SUM_PRODUCTS = 50;
	/** Storage of the engine with the cache enabled. */
	@Nullable private TestPaths enabledPaths;
	/** Storage of the cache-disabled oracle. */
	@Nullable private TestPaths disabledPaths;
	/** Engine whose formula result cache is under test. */
	@Nullable private Evita enabled;
	/** Engine computing every query without the cache. */
	@Nullable private Evita disabled;

	/** Closes both test-owned engines and removes their storage. */
	@AfterEach
	void tearDown() {
		if (this.enabled != null) this.enabled.close();
		if (this.disabled != null) this.disabled.close();
		if (this.enabledPaths != null) cleanupTestPaths(this.enabledPaths);
		if (this.disabledPaths != null) cleanupTestPaths(this.disabledPaths);
	}

	/**
	 * A difference is a result the cache changed: the oracle computes the same committed data without it. With every
	 * hit sampled, a recomputed ancestor computes its descendants with the cache suspended, so only the topmost bound
	 * formula of a tree is compared. With every other hit sampled, a restored ancestor leaves its descendants to later
	 * readers - the price sorter and the price histogram reach the lowest-price formula's own entry and sample it,
	 * which compares its selling records over the tied variants too.
	 */
	@ParameterizedTest(name = "verify one hit in {0}")
	@ValueSource(ints = {0, 1, 2})
	@DisplayName("Answers like the cache-disabled engine cold, warm and after committed changes")
	void shouldMatchCacheDisabledEngineAcrossWarmRunsAndCommits(int verifyOneHitIn) {
		startEngines(verifyOneHitIn);
		final List<Query> queries = queries();

		compareWithOracle(queries);
		final FormulaCacheSupervisor.Statistics warm = cacheStatistics();
		change(this.enabled);
		change(this.disabled);
		compareWithOracle(queries);

		final FormulaCacheSupervisor.Statistics last = cacheStatistics();
		assertAll(
			() -> assertTrue(warm.hits() > 0, "Warm runs must be served from the cache"),
			() -> assertTrue(last.hits() > warm.hits(), "Warm runs after the commits must be served from the cache"),
			() -> assertTrue(last.active(), "The cache must stay active"),
			() -> assertEquals(0, last.mismatches()),
			() -> assertTrue(verifyOneHitIn == 0 || last.verifiedHits() > 0, "Sampled hits must be verified")
		);
	}

	/** Builds both engines from equal options differing only in whether the cache is enabled. */
	private void startEngines(int verifyOneHitIn) {
		final CacheOptions options = CacheOptions.builder().enabled(true).minimalComplexityThreshold(0)
			.cacheSizeInBytes(100_000_000L).dropEntriesAfterSecondsOfInactivity(0).verifyOneHitIn(verifyOneHitIn)
			.build();
		this.enabledPaths = createTestPaths("FormulaCacheDifferentialTest-enabled");
		this.disabledPaths = createTestPaths("FormulaCacheDifferentialTest-disabled");
		this.enabled = new Evita(newTestEvitaConfigurationBuilder(this.enabledPaths).cache(options).build());
		this.disabled = new Evita(newTestEvitaConfigurationBuilder(this.disabledPaths)
			.cache(CacheOptions.builder(options).enabled(false).build()).build());
		populate(this.enabled);
		populate(this.disabled);
	}

	/** Returns the counters of the enabled engine's cache. */
	@Nonnull
	private FormulaCacheSupervisor.Statistics cacheStatistics() {
		final FormulaCacheSupervisor.Statistics statistics = this.enabled.getFormulaCacheStatistics();
		assertNotNull(statistics);
		return statistics;
	}

	/** Runs every query cold and twice warm on fresh sessions and compares each response with the oracle's. */
	private void compareWithOracle(@Nonnull List<Query> queries) {
		try (final EvitaSessionContract actual = this.enabled.createReadOnlySession(CATALOG);
			final EvitaSessionContract oracle = this.disabled.createReadOnlySession(CATALOG)) {
			for (final Query query : queries) {
				final EvitaResponse<EntityReference> expected = read(oracle, query);
				for (int run = 1; run <= 3; run++) {
					final EvitaResponse<EntityReference> response = read(actual, query);
					final int attempt = run;
					assertTrue(QueryResponseComparator.matches(expected, response),
						() -> "Run " + attempt + " of " + query + " differs.\nExpected: " + expected +
							"\nActual: " + response);
				}
			}
		}
	}

	/** Reads at a fixed moment so repeated queries build equal keys. */
	@Nonnull
	private static EvitaResponse<EntityReference> read(@Nonnull EvitaSessionContract session, @Nonnull Query query) {
		return ((EvitaInternalSessionContract) session).query(
			new EvitaRequest(query, MOMENT, EntityReference.class, null));
	}

	/** Builds every query in the default planning policy and with index scan preferred over prefetch. */
	@Nonnull
	private static List<Query> queries() {
		final FilterConstraint prices = and(priceInPriceLists("vip", "basic"), priceInCurrency(CZK),
			priceBetween(BigDecimal.valueOf(150), BigDecimal.valueOf(380)));
		final List<Query> queries = new ArrayList<>(16);
		for (final boolean preferIndexScan : new boolean[]{false, true}) {
			queries.add(Query.query(collection(PRODUCT), filterBy(prices), orderBy(priceNatural(OrderDirection.ASC)),
				requirements(preferIndexScan, page(1, 100))));
			queries.add(Query.query(collection(PRODUCT), filterBy(prices), orderBy(priceNatural(OrderDirection.DESC)),
				requirements(preferIndexScan, page(1, 100))));
			queries.add(Query.query(collection(PRODUCT), filterBy(prices),
				requirements(preferIndexScan, page(1, 100), priceHistogram(5))));
			// the filter reads another attribute than the histogram, so only the histogram's index tells a change
			queries.add(Query.query(collection(PRODUCT), filterBy(attributeBetween("amount", 10, 80)),
				requirements(preferIndexScan, page(1, 100), attributeHistogram(5, "weight"))));
			queries.add(Query.query(collection(PRODUCT), filterBy(attributeLessThan("amount", 90),
					userFilter(facetHaving("parameters", entityPrimaryKeyInSet(1, 5)))),
				requirements(preferIndexScan, page(1, 100), facetSummary(FacetStatisticsDepth.IMPACT))));
			queries.add(Query.query(collection(PRODUCT),
				filterBy(hierarchyWithin("categories", entityPrimaryKeyInSet(1))),
				requirements(preferIndexScan, page(1, 100), hierarchyOfReference("categories",
					EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("tree",
						statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))))));
			queries.add(Query.query(collection(PRODUCT),
				filterBy(referenceHaving("brand", entityHaving(attributeEquals("code", "B2")))),
				requirements(preferIndexScan, page(1, 100))));
			queries.add(Query.query(collection(PRODUCT), filterBy(attributeGreaterThan("amount", 5)),
				requirements(preferIndexScan, page(1, 100), referenceSummaryOfReferenceWithHistograms("brand",
					FacetStatisticsDepth.COUNTS, null, null,
					histogramStatistics(3, HistogramBehavior.STANDARD, "ratings")))));
		}
		return queries;
	}

	/** Adds the debug requirement preferring index scan to the requirements, or keeps the default policy. */
	@Nonnull
	private static Require requirements(boolean preferIndexScan, @Nonnull RequireConstraint... constraints) {
		if (!preferIndexScan) {
			return require(constraints);
		}
		final RequireConstraint[] withPolicy = Arrays.copyOf(constraints, constraints.length + 1);
		withPolicy[constraints.length] = debug(DebugMode.PREFER_INDEX_SCAN);
		return require(withPolicy);
	}

	/** Builds the same committed data in one engine, without sharing any instance with the other. */
	private static void populate(@Nonnull Evita evita) {
		evita.defineCatalog(CATALOG).updateViaNewSession(evita);
		evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema(BRAND).withoutGeneratedPrimaryKey()
				.withAttribute("code", String.class, whichIs -> whichIs.filterable()).updateVia(session);
			session.defineEntitySchema(PARAMETER_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema(PARAMETER).withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema(CATEGORY).withoutGeneratedPrimaryKey().withHierarchy().updateVia(session);
			session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey().withPrice()
				.withAttribute("amount", Integer.class, whichIs -> whichIs.filterable().sortable())
				.withAttribute("weight", Integer.class, whichIs -> whichIs.filterable())
				.withReferenceToEntity("brand", BRAND, Cardinality.ZERO_OR_MORE, whichIs -> whichIs
					.indexedForFiltering().faceted()
					.withAttribute("rating", Integer.class, thatIs -> thatIs.filterable())
					.bucketed("ratings", ExpressionFactory.parse("$reference.attributes['rating']")))
				.withReferenceToEntity("parameters", PARAMETER, Cardinality.ZERO_OR_MORE, whichIs -> whichIs
					.withGroupTypeRelatedToEntity(PARAMETER_GROUP).indexedForFilteringAndPartitioning().faceted())
				.withReferenceToEntity("categories", CATEGORY, Cardinality.ZERO_OR_MORE, whichIs -> whichIs
					.indexedForFilteringAndPartitioning())
				.updateVia(session);
			for (int brand = 1; brand <= 4; brand++) {
				session.upsertEntity(session.createNewEntity(BRAND, brand).setAttribute("code", "B" + brand));
			}
			for (int group = 1; group <= 2; group++) {
				session.upsertEntity(session.createNewEntity(PARAMETER_GROUP, group));
			}
			for (int parameter = 1; parameter <= 8; parameter++) {
				session.upsertEntity(session.createNewEntity(PARAMETER, parameter));
			}
			session.upsertEntity(session.createNewEntity(CATEGORY, 1));
			session.upsertEntity(session.createNewEntity(CATEGORY, 2).setParent(1));
			session.upsertEntity(session.createNewEntity(CATEGORY, 3).setParent(1));
			session.upsertEntity(session.createNewEntity(CATEGORY, 4).setParent(2));
			session.upsertEntity(session.createNewEntity(CATEGORY, 5));
			session.upsertEntity(session.createNewEntity(CATEGORY, 6).setParent(5));
			for (int product = 1; product <= PRODUCTS; product++) {
				final int pk = product;
				final int firstParameter = pk % 8 + 1;
				final int secondParameter = (pk * 3) % 8 + 1;
				final EntityBuilder builder = session.createNewEntity(PRODUCT, pk)
					.setAttribute("amount", (pk * 7) % 100)
					.setAttribute("weight", (pk % 13) * 10)
					.setReference("brand", pk % 4 + 1, ref -> ref.setAttribute("rating", pk % 5))
					.setReference("parameters", firstParameter, ref -> ref.setGroup(group(firstParameter)))
					.setReference("categories", pk % 6 + 1);
				if (secondParameter != firstParameter) {
					builder.setReference("parameters", secondParameter, ref -> ref.setGroup(group(secondParameter)));
				}
				setPrices(builder, pk);
				session.upsertEntity(builder);
			}
			session.goLiveAndClose();
		});
	}

	/** Assigns the price handling and prices of one product; every third product also has a VIP price. */
	private static void setPrices(
		@Nonnull EntityBuilder builder, int pk
	) {
		if (pk <= LOWEST_PRICE_PRODUCTS) {
			final int first = 100 + pk * 5;
			final int second = pk <= TIED_PRODUCTS ? first : first - 3;
			builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
			price(builder, pk * 10 + 1, "basic", 1, first);
			price(builder, pk * 10 + 2, "basic", 2, second);
			if (pk % 3 == 0) {
				price(builder, pk * 10 + 3, "vip", 1, first - 20);
			}
		} else if (pk <= SUM_PRODUCTS) {
			builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM);
			price(builder, pk * 10 + 1, "basic", 1, 60 + pk);
			price(builder, pk * 10 + 2, "basic", 2, 40 + pk);
			if (pk % 3 == 0) {
				price(builder, pk * 10 + 3, "vip", 2, 30 + pk);
			}
		} else {
			builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE);
			price(builder, pk * 10 + 1, "basic", null, 100 + pk * 5);
			if (pk % 3 == 0) {
				price(builder, pk * 10 + 3, "vip", null, 80 + pk * 5);
			}
		}
	}

	/** Adds one indexed price whose amount is equal with and without tax. */
	private static void price(
		@Nonnull EntityBuilder builder, int priceId,
		@Nonnull String priceList, @Nullable Integer innerRecordId, int amount
	) {
		builder.setPrice(priceId, priceList, CZK, innerRecordId, BigDecimal.valueOf(amount), BigDecimal.ZERO,
			BigDecimal.valueOf(amount), true);
	}

	/** Returns the group of a parameter. */
	private static int group(int parameter) {
		return parameter <= 4 ? 1 : 2;
	}

	/**
	 * Commits the same changes in one engine: a tied lowest price becomes untied, only the histogrammed attribute of
	 * an entity inside the attribute filter changes, and one product gains a facet while losing another.
	 */
	private static void change(@Nonnull Evita evita) {
		evita.updateCatalog(CATALOG, session -> {
			session.upsertEntity(session.getEntity(PRODUCT, 5, priceContentAll()).orElseThrow().openForWrite()
				.setPrice(52, "basic", CZK, 2, BigDecimal.valueOf(110), BigDecimal.ZERO, BigDecimal.valueOf(110),
					true));
			// product 2 has amount 14, inside the attribute filter, so the filter key stays unchanged
			session.upsertEntity(session.getEntity(PRODUCT, 2, attributeContentAll()).orElseThrow().openForWrite()
				.setAttribute("weight", 990));
			final int removed = 3 % 8 + 1;
			session.upsertEntity(session.getEntity(PRODUCT, 3, referenceContentAll()).orElseThrow().openForWrite()
				.removeReference("parameters", removed)
				.setReference("parameters", 7, ref -> ref.setGroup(group(7))));
		});
	}
}
