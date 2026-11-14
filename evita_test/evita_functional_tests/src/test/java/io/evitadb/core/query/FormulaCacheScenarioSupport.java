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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaEditor.EntityAttributeSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.FormulaCacheSupervisor;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Owns independent engines and generated catalogs for payload and cross-version comparisons.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
abstract class FormulaCacheScenarioSupport implements EvitaTestSupport {
	/** Stable implicit query moment, also inside the ordinary validity windows. */
	protected static final OffsetDateTime MOMENT = OffsetDateTime.parse("2026-01-01T00:00:00Z");
	/** Currencies of independently indexed prices. */
	protected static final Currency CZK = Currency.getInstance("CZK");
	/** Second currency, indexed independently of the first. */
	protected static final Currency EUR = Currency.getInstance("EUR");
	/** Engine serving cached queries. */
	protected Evita enabled;
	/** Independent engine evaluating without formula result caching. */
	protected Evita disabled;
	/** Storage directories owned by this test instance. */
	private TestPaths enabledPaths;
	/** Storage directories of the independent oracle. */
	private TestPaths disabledPaths;

	/** Starts engines whose only configuration difference is the cache switch. */
	protected void start(int sampling) {
		final CacheOptions options = CacheOptions.builder().enabled(true).minimalComplexityThreshold(0)
			.cacheSizeInBytes(100_000_000L).dropEntriesAfterSecondsOfInactivity(0).verifyOneHitIn(sampling).build();
		this.enabledPaths = createTestPaths(getClass().getSimpleName() + "-enabled");
		this.disabledPaths = createTestPaths(getClass().getSimpleName() + "-disabled");
		this.enabled = new Evita(newTestEvitaConfigurationBuilder(this.enabledPaths).cache(options).build());
		this.disabled = new Evita(newTestEvitaConfigurationBuilder(this.disabledPaths)
			.cache(CacheOptions.builder(options).enabled(false).build()).build());
	}

	/** Closes engines before removing their independent storage. */
	@AfterEach
	void closeEngines() {
		try {
			if (this.enabled != null) this.enabled.close();
		} finally {
			try {
				if (this.disabled != null) this.disabled.close();
			} finally {
				if (this.enabledPaths != null) cleanupTestPaths(this.enabledPaths);
				if (this.disabledPaths != null) cleanupTestPaths(this.disabledPaths);
			}
		}
	}

	/** Builds equal catalogs; a caller can retain their bulk-loading state to test the admission gate. */
	protected void populate(@Nonnull String name, boolean alive) {
		for (final Evita engine : List.of(this.enabled, this.disabled)) {
			engine.defineCatalog(name).updateViaNewSession(engine);
			engine.updateCatalog(name, session -> {
				session.defineEntitySchema("category").withoutGeneratedPrimaryKey().withHierarchy().updateVia(session);
				session.defineEntitySchema("facet").withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema("group").withoutGeneratedPrimaryKey().updateVia(session);
				for (int id = 1; id <= 6; id++) {
					session.upsertEntity(id == 1 || id == 5 ? session.createNewEntity("category", id) :
						session.createNewEntity("category", id).setParent(id == 6 ? 5 : 1));
					session.upsertEntity(session.createNewEntity("facet", id));
				}
				for (int id = 1; id <= 2; id++) session.upsertEntity(session.createNewEntity("group", id));
				products(session, 0);
				if (alive) session.goLiveAndClose();
			});
		}
	}

	/** Creates five indexed attribute types, grouped facets, hierarchy references and two hundred products. */
	protected static void products(@Nonnull EvitaSessionContract session, int offset) {
		final Scope[] scopes = Scope.values();
		final Consumer<EntityAttributeSchemaBuilder> indexed = a -> a.filterableInScope(scopes).sortableInScope(scopes);
		session.defineEntitySchema("product").withoutGeneratedPrimaryKey().withPriceIndexedInScope(scopes)
			.withAttribute("amount", Integer.class, indexed)
			.withAttribute("rank", Long.class, indexed)
			.withAttribute("code", String.class, indexed)
			.withAttribute("decimal", BigDecimal.class, indexed.andThen(a -> a.indexDecimalPlaces(2)))
			.withAttribute("moment", OffsetDateTime.class, indexed)
			.withReferenceToEntity("facets", "facet", Cardinality.ZERO_OR_MORE,
				r -> r.indexedForFilteringAndPartitioning().faceted().withGroupTypeRelatedToEntity("group"))
			.withReferenceToEntity("categories", "category", Cardinality.ZERO_OR_MORE,
				r -> r.indexedForFilteringAndPartitioning()).updateVia(session);
		for (int id = 1; id <= 200; id++) session.upsertEntity(product(session, id, offset));
	}

	/** Generates all price handlings, tied variants, three lists and two currencies. */
	@Nonnull
	protected static EntityBuilder product(@Nonnull EvitaSessionContract session, int id, int offset) {
		final EntityBuilder builder = session.getEntity("product", id, attributeContentAll(), priceContentAll(),
			referenceContentAll()).map(SealedEntity::openForWrite)
			.orElseGet(() -> session.createNewEntity("product", id))
			.setAttribute("amount", (id * 7 + offset) % 100).setAttribute("rank", (long) id + offset)
			.setAttribute("code", "C" + id % 5).setAttribute("decimal", BigDecimal.valueOf(id, 2))
			.setAttribute("moment", MOMENT.plusDays(id % 7))
			.setReference("facets", id % 6 + 1, r -> r.setGroup(id % 2 + 1))
			.setReference("categories", id % 6 + 1);
		final PriceInnerRecordHandling handling = PriceInnerRecordHandling.values()[id % 3];
		builder.setPriceInnerRecordHandling(handling);
		for (final Currency currency : List.of(CZK, EUR)) {
			int priceId = 0;
			for (final String list : List.of("basic", "vip", "reference")) {
				for (int inner = 1; inner <= (handling == PriceInnerRecordHandling.NONE ? 1 : 2); inner++) {
					final int amount = 100 + id + offset + priceAdjustment(list, id, inner);
					builder.setPrice(++priceId, list, currency,
						handling == PriceInnerRecordHandling.NONE ? null : inner,
						BigDecimal.valueOf(amount), BigDecimal.valueOf(21), BigDecimal.valueOf(amount * 121L, 2),
						DateTimeRange.between(MOMENT.minusDays(2), MOMENT.plusDays(2)), true);
				}
			}
		}
		return builder;
	}

	/** Shifts a price list's amount: reference prices vary by product and inner record, vip prices are lower. */
	private static int priceAdjustment(@Nonnull String list, int id, int inner) {
		return switch (list) {
			case "reference" -> id % 17 + inner * 9;
			case "vip" -> -20;
			default -> 0;
		};
	}

	/** Applies one deterministic mutation independently to both engines. */
	protected void change(@Nonnull String name, @Nonnull Consumer<EvitaSessionContract> mutation) {
		this.enabled.updateCatalog(name, mutation);
		this.disabled.updateCatalog(name, mutation);
	}

	/** Returns the production statistics surface of the engine configured with the cache enabled. */
	@Nonnull
	protected FormulaCacheSupervisor.Statistics cacheStatistics() {
		final FormulaCacheSupervisor.Statistics statistics = this.enabled.getFormulaCacheStatistics();
		assertNotNull(statistics);
		return statistics;
	}

	/** Requests every row and every stored price component while preserving an explicit index policy. */
	@Nonnull
	protected static Query query(
		@Nonnull FilterConstraint filter, @Nonnull OrderConstraint order, @Nonnull RequireConstraint... extras
	) {
		final List<RequireConstraint> requirements = new ArrayList<>(List.of(extras));
		requirements.add(page(1, 1000));
		requirements.add(entityFetch(attributeContentAll(), priceContentAll()));
		requirements.add(debug(DebugMode.PREFER_INDEX_SCAN));
		return Query.query(collection("product"), filterBy(filter), orderBy(order),
			require(requirements.toArray(RequireConstraint[]::new)));
	}

	/** Builds a price subtree shared by query pairs and changed by committed price mutations. */
	@Nonnull
	protected static FilterConstraint prices() {
		return and(priceInPriceLists("vip", "basic"), priceInCurrency(CZK), priceValidIn(MOMENT),
			priceBetween(BigDecimal.valueOf(50), BigDecimal.valueOf(650)));
	}

	/** Reads bodies at a fixed implicit moment; entity equality alone would compare only identity and version. */
	@Nonnull
	protected static EvitaResponse<SealedEntity> read(@Nonnull EvitaSessionContract session, @Nonnull Query query) {
		return ((EvitaInternalSessionContract) session).query(
			new EvitaRequest(query, MOMENT, SealedEntity.class, null));
	}

	/** Compares the complete ordered primary-key list, total count, bodies, prices and extra results. */
	protected static void equalResponse(
		@Nonnull EvitaResponse<SealedEntity> expected, @Nonnull EvitaResponse<SealedEntity> actual,
		@Nonnull String message
	) {
		assertEquals(expected.getRecordData().stream().map(SealedEntity::getPrimaryKey).toList(),
			actual.getRecordData().stream().map(SealedEntity::getPrimaryKey).toList(), message);
		assertEquals(expected.getTotalRecordCount(), actual.getTotalRecordCount(), message);
		assertTrue(QueryResponseComparator.matches(expected, actual), () -> message + "\nExpected: " + expected +
			"\nActual: " + actual);
		for (int i = 0; i < expected.getRecordData().size(); i++) {
			final SealedEntity left = expected.getRecordData().get(i);
			final SealedEntity right = actual.getRecordData().get(i);
			assertEquals(left.getPrices().stream().sorted().toList(), right.getPrices().stream().sorted().toList(),
				message + " price components of " + left.getPrimaryKey());
			assertEquals(left.isPriceForSaleContextAvailable(), right.isPriceForSaleContextAvailable(), message);
			if (left.isPriceForSaleContextAvailable()) {
				assertEquals(left.getPriceForSale(), right.getPriceForSale(),
					message + " selling price of " + left.getPrimaryKey());
			}
		}
	}

	/** Compares one complete response in fresh sessions of independently constructed catalogs. */
	protected void compare(@Nonnull String name, @Nonnull Query query) {
		try (final EvitaSessionContract actual = this.enabled.createReadOnlySession(name);
			final EvitaSessionContract oracle = this.disabled.createReadOnlySession(name)) {
			equalResponse(read(oracle, query), read(actual, query), name + ": " + query);
		}
	}
}
