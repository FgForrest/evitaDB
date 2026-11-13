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
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.FormulaCacheSupervisor;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares nested read-only queries with a cache-disabled engine before and after a rolled-back price change.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula cache transaction isolation")
@Tag(ENGINE)
@Tag(CACHE)
@Tag(TRANSACTION)
@Tag(PRICE)
@Tag(HISTOGRAM)
class FormulaCacheTransactionIsolationTest implements EvitaTestSupport {
	/** Currency shared by both catalogs. */
	private static final Currency CURRENCY = Currency.getInstance("CZK");
	/** Storage of the engine with the tier enabled. */
	private TestPaths enabledPaths;
	/** Storage of the independent cache-disabled oracle. */
	private TestPaths disabledPaths;
	/** Engine whose cross-query cache is under test. */
	private Evita enabled;
	/** Engine that evaluates every query without the result tier. */
	private Evita disabled;

	/** Creates identical alive catalogs with two hundred prices and a filterable numeric attribute. */
	@BeforeEach
	void setUp() {
		this.enabledPaths = createTestPaths("FormulaCacheTransactionIsolationTest-enabled");
		this.disabledPaths = createTestPaths("FormulaCacheTransactionIsolationTest-disabled");
		final CacheOptions options = CacheOptions.builder().enabled(true).minimalComplexityThreshold(0)
			.cacheSizeInBytes(100_000_000L).dropEntriesAfterSecondsOfInactivity(0).verifyOneHitIn(0).build();
		this.enabled = new Evita(newTestEvitaConfigurationBuilder(this.enabledPaths).cache(options).build());
		this.disabled = new Evita(newTestEvitaConfigurationBuilder(this.disabledPaths)
			.cache(CacheOptions.builder(options).enabled(false).build()).build());
		populate(this.enabled);
		populate(this.disabled);
	}

	/** Closes the test-owned engines and releases both sets of paths. */
	@AfterEach
	void tearDown() {
		if (this.enabled != null) this.enabled.close();
		if (this.disabled != null) this.disabled.close();
		if (this.enabledPaths != null) cleanupTestPaths(this.enabledPaths);
		if (this.disabledPaths != null) cleanupTestPaths(this.disabledPaths);
	}

	/** Builds the same committed inputs independently, without sharing producer instances. */
	private static void populate(@Nonnull Evita evita) {
		evita.defineCatalog("transactionCache").updateViaNewSession(evita);
		evita.updateCatalog("transactionCache", session -> {
			session.defineEntitySchema("product").withoutGeneratedPrimaryKey().withPrice()
				.withAttribute("amount", Integer.class, attribute -> attribute.filterable()).updateVia(session);
			for (int id = 1; id <= 200; id++) {
				final int amount = id == 7 ? 50 : id * 10;
				session.upsertEntity(session.createNewEntity("product", id).setAttribute("amount", amount)
					.setPrice(1, "basic", CURRENCY, BigDecimal.valueOf(amount), BigDecimal.ZERO,
						BigDecimal.valueOf(amount), true));
			}
			session.goLiveAndClose();
		});
	}

	/** Exercises cold and populated entries for formula, price histogram and attribute histogram paths. */
	@ParameterizedTest
	@CsvSource({"price,false", "price,true", "priceHistogram,false", "priceHistogram,true",
		"attributeHistogram,false", "attributeHistogram,true"})
	@DisplayName("Never looks up or admits during a nested read-only query under a writing transaction")
	void shouldIsolateNestedReadOnlyQuery(@Nonnull String mode, boolean warm) {
		final Query query = query(mode);
		try (final EvitaSessionContract actualSession = this.enabled.createReadOnlySession("transactionCache");
			final EvitaSessionContract oracleSession = this.disabled.createReadOnlySession("transactionCache")) {
			final EvitaResponse<EntityReference> committed = read(oracleSession, query);
			assertFalse(committed.getRecordData().contains(new EntityReference("product", 7)));
			if (warm) {
				read(actualSession, query);
				read(actualSession, query);
				assertTrue(this.enabled.getFormulaCacheStatistics().admissions() > 0);
				assertTrue(this.enabled.getFormulaCacheStatistics().hits() > 0);
			}
			final NestedResult oracle = nested(this.disabled, oracleSession, query);
			final NestedResult actual = nested(this.enabled, actualSession, query);
			assertTrue(oracle.response().getRecordData().contains(new EntityReference("product", 7)));
			assertEquals(oracle.response().getRecordData(), actual.response().getRecordData());
			assertTrue(QueryResponseComparator.matches(oracle.response(), actual.response()));
			for (int i = 0; i < 2; i++) assertTrue(QueryResponseComparator.matches(committed, read(actualSession, query)));
			try (final EvitaSessionContract fresh = this.enabled.createReadOnlySession("transactionCache")) {
				for (int i = 0; i < 2; i++) assertTrue(QueryResponseComparator.matches(committed, read(fresh, query)));
			}
			assertNotNull(actual.before());
			assertNotNull(actual.after());
			assertEquals(actual.before().hits(), actual.after().hits());
			assertEquals(actual.before().misses(), actual.after().misses());
			assertEquals(actual.before().admissions(), actual.after().admissions());
			assertTrue(actual.after().transactionBypasses() > actual.before().transactionBypasses());
			assertTrue(this.enabled.getFormulaCacheStatistics().hits() > actual.after().hits());
		}
	}

	/** Planning with warm entries stays isolated, and subsequent prefetch agrees with an independent engine. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Keeps warm entries outside eager planning and subsequent prefetch filtering")
	void shouldIsolatePlanningAndPrefetch(boolean forcePrefetch) {
		final Query warm = Query.query(collection("product"), filterBy(entityPrimaryKeyInSet(10, 11),
			priceInPriceLists("basic"), priceInCurrency(CURRENCY),
			priceBetween(BigDecimal.valueOf(100), BigDecimal.valueOf(500))),
			require(queryTelemetry(), debug(DebugMode.PREFER_INDEX_SCAN)));
		final Query prefetch = Query.query(collection("product"), warm.getFilterBy(), forcePrefetch ?
			require(queryTelemetry(), debug(DebugMode.PREFER_PREFETCHING)) : require(queryTelemetry()));
		try (final EvitaSessionContract actual = io.evitadb.core.cache.FormulaCacheTestSessions.readOnly(
			this.enabled, "transactionCache");
			final EvitaSessionContract oracle = this.disabled.createReadOnlySession("transactionCache")) {
			read(actual, warm);
			read(actual, warm);
			assertTrue(this.enabled.getFormulaCacheStatistics().hits() > 0);
			final FormulaCacheSupervisor.Statistics before = this.enabled.getFormulaCacheStatistics();
			final io.evitadb.core.collection.EntityCollection collection =
				((io.evitadb.core.catalog.Catalog) this.enabled.getCatalogInstanceOrThrowException("transactionCache"))
					.getCollectionForEntityInternal("product").orElseThrow();
			final QueryPlan plan = QueryPlanner.planQuery(collection.createQueryContext(new EvitaRequest(prefetch,
				OffsetDateTime.parse("2026-01-01T00:00:00Z"), EntityReference.class, null), actual));
			final FormulaCacheSupervisor.Statistics after = this.enabled.getFormulaCacheStatistics();
			assertEquals(before.hits(), after.hits());
			assertEquals(before.misses(), after.misses());
			assertEquals(before.admissions(), after.admissions());
			final EvitaResponse<EntityReference> expected = read(oracle, prefetch);
			final EvitaResponse<EntityReference> response = plan.execute();
			assertTrue(QueryResponseComparator.matches(expected, response));
			assertEquals(1, expected.getExtraResult(QueryTelemetry.class)
				.getMetric(QueryTelemetry.StepMetric.PREFETCHED).orElseThrow());
			assertEquals(1, response.getExtraResult(QueryTelemetry.class)
				.getMetric(QueryTelemetry.StepMetric.PREFETCHED).orElseThrow());
		}
	}

	/** Updates one entity inside the ordinary read-write execute scope and settles the requested rollback. */
	@Nonnull
	private static NestedResult nested(
		@Nonnull Evita evita, @Nonnull EvitaSessionContract reader, @Nonnull Query query
	) {
		final AtomicReference<NestedResult> result = new AtomicReference<>();
		assertThrows(RollbackException.class, () -> evita.updateCatalog("transactionCache", writer -> {
			final FormulaCacheSupervisor.Statistics before = evita.getFormulaCacheStatistics();
			writer.upsertEntity(writer.getEntity("product", 7, attributeContentAll(), priceContentAll())
				.orElseThrow().openForWrite().setAttribute("amount", 300)
				.setPrice(1, "basic", CURRENCY, BigDecimal.valueOf(300), BigDecimal.ZERO, BigDecimal.valueOf(300), true));
			final EvitaResponse<EntityReference> response = read(reader, query);
			result.set(new NestedResult(response, before, evita.getFormulaCacheStatistics()));
			writer.setRollbackOnly();
		}));
		return result.get();
	}

	/** Uses a fixed implicit moment so equal literal attribute queries have deterministic alternative identities. */
	@Nonnull
	private static EvitaResponse<EntityReference> read(@Nonnull EvitaSessionContract session, @Nonnull Query query) {
		return ((EvitaInternalSessionContract) session).query(new EvitaRequest(query,
			OffsetDateTime.parse("2026-01-01T00:00:00Z"), EntityReference.class, null));
	}

	/** Creates either a price filter or a numeric attribute filter with the requested extra-result computer. */
	@Nonnull
	private static Query query(@Nonnull String mode) {
		if (mode.equals("attributeHistogram")) {
			return Query.query(collection("product"), filterBy(attributeBetween("amount", 100, 500)),
				require(page(1, 300), debug(DebugMode.PREFER_INDEX_SCAN), attributeHistogram(10, "amount")));
		}
		if (!mode.equals("price") && !mode.equals("priceHistogram")) {
			throw new IllegalArgumentException("Unknown transaction query mode.");
		}
		return Query.query(collection("product"), filterBy(priceInPriceLists("basic"), priceInCurrency(CURRENCY),
			priceBetween(BigDecimal.valueOf(100), BigDecimal.valueOf(500))), mode.equals("priceHistogram") ?
			require(page(1, 300), debug(DebugMode.PREFER_INDEX_SCAN), priceHistogram(10)) :
			require(page(1, 300), debug(DebugMode.PREFER_INDEX_SCAN)));
	}

	/**
	 * Response from inside the rolled-back transaction and counters immediately around that read.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record NestedResult(
		@Nonnull EvitaResponse<EntityReference> response,
		@Nullable FormulaCacheSupervisor.Statistics before,
		@Nullable FormulaCacheSupervisor.Statistics after
	) {
	}
}
