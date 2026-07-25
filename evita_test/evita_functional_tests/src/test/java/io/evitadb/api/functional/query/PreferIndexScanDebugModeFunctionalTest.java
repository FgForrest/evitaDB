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

package io.evitadb.api.functional.query;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.stream.IntStream;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.priceContentRespectingFilter;
import static io.evitadb.api.query.QueryConstraints.priceHistogram;
import static io.evitadb.api.query.QueryConstraints.priceInCurrency;
import static io.evitadb.api.query.QueryConstraints.priceInPriceLists;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REQUIRE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in {@link DebugMode#PREFER_INDEX_SCAN} - the switch that denies the optional, cost-based prefetch so a query
 * is answered by resolving the indexes.
 *
 * The switch exists because a cheap prefetch silently substitutes for the index path: on a small dataset the planner
 * fetches the entity bodies and answers the whole query from them, so an assertion aimed at index resolution never
 * reaches the code it is meant to pin. Tests that must exercise the index therefore cannot rely on dataset size alone.
 *
 * The observable used here is {@link QueryPhase#EXECUTION_PREFETCH}: that telemetry step is emitted if and only if
 * the plan actually carries a prefetcher, which makes its presence or absence a direct read-out of the planner's
 * decision rather than a proxy for it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("The PREFER_INDEX_SCAN debug mode denies the optional prefetch")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REQUIRE)
@Tag(PRICE)
class PreferIndexScanDebugModeFunctionalTest implements EvitaTestSupport {

	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final int PRODUCT_COUNT = 4;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("PreferIndexScanDebugMode");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		seed();
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("preferring the prefetch selects it")
	void shouldPrefetchEntityBodiesWhenPrefetchingIsPreferred() {
		// this is the baseline that gives every other test here its meaning - with no prefetch to suppress,
		// asserting its absence would prove nothing. It is stated against an explicit preference rather than
		// against the default, so the class keeps testing the switch and not the cost model's current mood
		assertTrue(
			executedPrefetch(queryPrices(debug(DebugMode.PREFER_PREFETCHING))),
			"This query must be prefetchable - otherwise this test class no longer exercises the decision it " +
				"exists to pin!"
		);
	}

	@Test
	@DisplayName("preferring the index scan denies that prefetch even when the prefetch is preferred too")
	void shouldDenyThePrefetchEvenWhenItIsExplicitlyPreferred() {
		// paired with the test above this is the differential that proves the switch does something: the SAME query
		// with the SAME explicit prefetch preference prefetches without this switch and does not prefetch with it.
		// Asserting the absence of a prefetch on its own would prove nothing, because this query does not prefetch
		// by default either.
		//
		// The denial is absolute rather than a competing preference: the planning policy refuses the prefetch both
		// when the planner asks whether prefetching is possible at all and when it prices it, and neither decision
		// consults the user preference.
		assertFalse(
			executedPrefetch(queryPrices(debug(DebugMode.PREFER_INDEX_SCAN, DebugMode.PREFER_PREFETCHING))),
			"`PREFER_INDEX_SCAN` must win over `PREFER_PREFETCHING` when both are requested!"
		);
	}

	@Test
	@DisplayName("both paths report the same entities and the same price histogram")
	void shouldReturnIdenticalResultsWhicheverPathIsTaken() {
		final EvitaResponse<SealedEntity> prefetched = queryPrices();
		final EvitaResponse<SealedEntity> scanned = queryPrices(debug(DebugMode.PREFER_INDEX_SCAN));

		final Integer[] expected = IntStream.rangeClosed(1, PRODUCT_COUNT).boxed().toArray(Integer[]::new);
		final Integer[] scannedKeys = primaryKeysOf(scanned);
		assertArrayEquals(
			expected, scannedKeys,
			"Every seeded product must be found through the index path but got " + Arrays.toString(scannedKeys) + "!"
		);
		assertArrayEquals(
			primaryKeysOf(prefetched), scannedKeys,
			"Denying the prefetch must not change which entities the query returns!"
		);

		final PriceHistogram prefetchedHistogram = prefetched.getExtraResult(PriceHistogram.class);
		final PriceHistogram scannedHistogram = scanned.getExtraResult(PriceHistogram.class);
		assertNotNull(prefetchedHistogram, "The prefetching plan must compute a price histogram!");
		assertNotNull(scannedHistogram, "The index-resolving plan must compute a price histogram!");
		assertEquals(
			0, prefetchedHistogram.getMin().compareTo(scannedHistogram.getMin()),
			"Denying the prefetch must not change the price histogram minimum - was " +
				prefetchedHistogram.getMin() + " and " + scannedHistogram.getMin() + "!"
		);
		assertEquals(
			0, prefetchedHistogram.getMax().compareTo(scannedHistogram.getMax()),
			"Denying the prefetch must not change the price histogram maximum - was " +
				prefetchedHistogram.getMax() + " and " + scannedHistogram.getMax() + "!"
		);
	}

	/**
	 * Builds a handful of priced products, each at a distinct price, in a warm-up catalog.
	 */
	private void seed() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withPriceInCurrencyIndexedInScope(2, new Currency[]{CURRENCY_EUR}, Scope.LIVE)
					.updateVia(session);

				for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
					final BigDecimal price = BigDecimal.valueOf(10L * pk);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, pk)
							.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
							.setPrice(1000 + pk, PRICE_LIST_BASIC, CURRENCY_EUR, price, BigDecimal.ZERO, price, true)
					);
				}
			}
		);
	}

	/**
	 * Runs the price query, optionally augmented with extra require constraints, and returns the full response.
	 *
	 * The query is deliberately shaped so the planner *can* prefetch: the primary-key set contributes a resolved
	 * bitmap in conjunctive scope, and the entity fetch contributes the content requirements a prefetch would have to
	 * satisfy. Without both of those the prefetch is not even a candidate and the switch under test would have
	 * nothing to deny.
	 *
	 * @param extraRequirements additional require constraints - typically the `debug` constraint under test
	 * @return the query response including its telemetry
	 */
	@Nonnull
	private EvitaResponse<SealedEntity> queryPrices(@Nonnull RequireConstraint... extraRequirements) {
		final RequireConstraint[] requirements = new RequireConstraint[extraRequirements.length + 3];
		requirements[0] = priceHistogram(20);
		requirements[1] = queryTelemetry();
		requirements[2] = entityFetch(priceContentRespectingFilter());
		System.arraycopy(extraRequirements, 0, requirements, 3, extraRequirements.length);

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			return session.query(
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						entityPrimaryKeyInSet(IntStream.rangeClosed(1, PRODUCT_COUNT).toArray()),
						priceInPriceLists(PRICE_LIST_BASIC),
						priceInCurrency(CURRENCY_EUR)
					),
					require(requirements)
				),
				SealedEntity.class
			);
		}
	}

	/**
	 * Reads the query telemetry out of the response and reports whether the plan carried a prefetcher.
	 *
	 * @param response the response to inspect
	 * @return true when the plan prefetched entity bodies
	 */
	private static boolean executedPrefetch(@Nonnull EvitaResponse<SealedEntity> response) {
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		assertNotNull(telemetry, "Query telemetry must be present - it is the observable this test reads!");
		return containsPhase(telemetry, QueryPhase.EXECUTION_PREFETCH);
	}

	/**
	 * Walks the telemetry tree looking for a step of the given phase.
	 *
	 * @param telemetry the telemetry node to start from
	 * @param phase     the phase to look for
	 * @return true when the phase is present anywhere in the tree
	 */
	private static boolean containsPhase(@Nonnull QueryTelemetry telemetry, @Nonnull QueryPhase phase) {
		if (telemetry.getOperation() == phase) {
			return true;
		}
		for (final QueryTelemetry step : telemetry.getSteps()) {
			if (containsPhase(step, phase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Extracts the sorted primary keys of the returned entity references.
	 *
	 * @param response the response to read
	 * @return the primary keys in ascending order
	 */
	@Nonnull
	private static Integer[] primaryKeysOf(@Nonnull EvitaResponse<SealedEntity> response) {
		return response.getRecordData()
			.stream()
			.map(SealedEntity::getPrimaryKey)
			.sorted()
			.toArray(Integer[]::new);
	}

}
