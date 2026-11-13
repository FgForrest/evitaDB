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
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.query.require.QueryTelemetryContent;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.FormulaCacheSupervisor;
import io.evitadb.core.cache.FormulaCacheTestSessions;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.price.translate.PriceIdToEntityIdTranslateFormula;
import io.evitadb.core.session.EvitaSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises cached payload consumers through real query pairs and catalog lifecycle transitions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula cache payload and lifecycle")
@Tag(ENGINE)
@Tag(CACHE)
@Tag(QUERY)
@Tag(PRICE)
@Tag(FACET)
@Tag(HIERARCHY)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class FormulaCachePayloadTest extends FormulaCacheScenarioSupport {
	/** Requires an actual reused node at the requested depth, rather than merely equal answers. */
	@ParameterizedTest
	@ValueSource(strings = {"top", "middle", "frontier"})
	@DisplayName("Restores a shared subtree in a fresh query plan")
	void shouldRestoreSharedSubtree(@Nonnull String position) {
		start(0);
		populate("payload", true);
		final Query first = query(and(prices(), attributeGreaterThan("amount", 10)), priceNatural(),
			queryTelemetry(QueryTelemetryContent.PLAN));
		final Query second = switch (position) {
			case "top" -> first;
			case "middle" -> query(and(prices(), attributeLessThan("amount", 80)), priceNatural());
			case "frontier" -> query(and(priceInPriceLists("vip", "basic"), priceInCurrency(CZK),
				priceValidIn(MOMENT), priceBetween(BigDecimal.valueOf(100), BigDecimal.valueOf(550)),
				attributeLessThan("amount", 80)), priceNatural());
			default -> throw new IllegalArgumentException(position);
		};
		final List<FormulaKey> computed = new ArrayList<>();
		try (final EvitaSession session = FormulaCacheTestSessions.readOnly(this.enabled, "payload");
			final EvitaSessionContract oracle = this.disabled.createReadOnlySession("payload")) {
			final QueryPlan plan = plan(this.enabled, session, first);
			equalResponse(read(oracle, first), plan.execute(), position + " warming query: " + first);
			collectComputed(plan.getFilter(), computed);
		}
		final long before = statistics().hits();
		try (final EvitaSession session = FormulaCacheTestSessions.readOnly(this.enabled, "payload");
			final EvitaSessionContract oracle = this.disabled.createReadOnlySession("payload")) {
			final QueryPlan plan = plan(this.enabled, session, second);
			final EvitaResponse<SealedEntity> response = plan.execute();
			equalResponse(read(oracle, second), response, position + ": " + second);
			assertTrue(statistics().hits() > before, position + " must hit: " + second);
			if (position.equals("top")) {
				assertEquals(0L, plan.getFilter().getCost(), second.toString());
				final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
				assertNotNull(telemetry.getPlan(), second.toString());
				assertEquals(0L, telemetry.getPlan().actualCost(), second.toString());
				assertTrue(telemetry.getPlan().children().stream().anyMatch(child -> child.resultCount() == null),
					"An uncomputed child must have no reported result: " + second);
			} else {
				assertTrue(plan.getFilter().getCost() > 0, "The changed ancestor must compute: " + second);
			}
			assertTrue(restored(plan.getFilter(), computed, position.equals("frontier")),
				"Expected reused " + position + " node in " + plan.getFilter());
		}
	}

	/** Compares full bodies and every price component while readers reach already admitted subtrees. */
	@ParameterizedTest
	@ValueSource(strings = {"priceAsc", "priceDesc", "discount", "histogram", "innerHistogram",
		"facets", "hierarchy", "attributeHistogram"})
	@DisplayName("Keeps ordered prices and extra-result readers correct over hits")
	void shouldPreserveReaderPayload(@Nonnull String reader) {
		start(0);
		populate("payload", true);
		final Query query = readerQuery(reader);
		compare("payload", query);
		final long before = statistics().hits();
		compare("payload", query);
		assertTrue(statistics().hits() > before, reader + " must consume hits: " + query);
	}

	/** Makes both histogram computers consume a filter admitted by a different query, then restores the computers. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Builds and restores histogram computers over a shared filter")
	void shouldRestoreHistogramComputersOverSharedFilter(boolean price) {
		start(0);
		populate("payload", true);
		final Query first = query(and(prices(), attributeGreaterThan("amount", 10)), priceNatural());
		final Query second = query(and(first.getFilterBy().getChildren()), priceNatural(),
			price ? priceHistogram(8) : attributeHistogram(8, "amount"));
		compare("payload", first);
		final long before = statistics().hits();
		compare("payload", second);
		assertTrue(statistics().hits() > before, "Shared filter must hit: " + second);
		final long admitted = statistics().admissions();
		final long hits = statistics().hits();
		compare("payload", second);
		assertTrue(statistics().hits() > hits, "Warmed computer must hit: " + second);
		assertEquals(admitted, statistics().admissions(), second.toString());
	}

	/** Distinguishes an unrelated write from changing the operand used by an admitted price subtree. */
	@Test
	@DisplayName("Retains unrelated entries and admits changed price operands")
	void shouldDistinguishRelatedAndUnrelatedCommits() {
		start(0);
		populate("payload", true);
		final Query query = readerQuery("priceAsc");
		compare("payload", query);
		change("payload", s -> s.upsertEntity(s.createNewEntity("category", 20)));
		final FormulaCacheSupervisor.Statistics before = statistics();
		compare("payload", query);
		assertTrue(statistics().hits() > before.hits(), query.toString());
		assertEquals(before.admissions(), statistics().admissions(), query.toString());
		change("payload", s -> s.upsertEntity(product(s, 8, 500)));
		final FormulaCacheSupervisor.Statistics changed = statistics();
		compare("payload", query);
		assertTrue(statistics().misses() > changed.misses(), query.toString());
		assertTrue(statistics().admissions() > changed.admissions(), query.toString());
		compare("payload", query);
	}

	/** Populates both sides before swapping identities, then requires correct cold and warm answers afterwards. */
	@ParameterizedTest
	@ValueSource(strings = {"rename", "replace", "recreate", "restore", "alternate"})
	@DisplayName("Keeps payloads isolated across catalog and collection lifecycles")
	void shouldIsolateLifecycle(@Nonnull String operation) throws Exception {
		start(0);
		populate("payload", true);
		populate("other", true);
		final Query query = readerQuery("innerHistogram");
		compare("payload", query);
		compare("other", query);
		compare("payload", query);
		if (operation.equals("alternate")) {
			change("other", s -> s.upsertEntity(product(s, 8, 500)));
			for (int run = 0; run < 4; run++) {
				compare("other", query);
				compare("payload", query);
			}
		} else {
			for (final Evita engine : List.of(this.enabled, this.disabled)) {
				switch (operation) {
					case "rename" -> engine.renameCatalog("payload", "renamed");
					case "replace" -> {
						engine.updateCatalog("other", s -> {
							s.upsertEntity(product(s, 8, 500));
						});
						engine.replaceCatalog("other", "payload");
					}
					case "recreate" -> engine.updateCatalog("payload", s -> {
						s.deleteCollection("product");
						products(s, 500);
					});
					case "restore" -> restore(engine);
					default -> throw new IllegalArgumentException(operation);
				}
			}
			final String name = operation.equals("rename") ? "renamed" : "payload";
			compare(name, query);
			final long before = statistics().hits();
			compare(name, query);
			assertTrue(statistics().hits() > before, operation + ": " + query);
		}
		assertTrue(statistics().active(), operation);
		assertEquals(0, statistics().mismatches(), operation);
	}

	/** Accounts for every completed miss, including concurrent computations losing publication. */
	@Test
	@DisplayName("Returns equal results to concurrent readers and accounts for all publications")
	void shouldAccountForConcurrentQueries() throws Exception {
		start(0);
		populate("payload", true);
		final Query query = readerQuery("innerHistogram");
		final EvitaResponse<SealedEntity> expected;
		try (final EvitaSessionContract oracle = this.disabled.createReadOnlySession("payload")) {
			expected = read(oracle, query);
		}
		final CountDownLatch ready = new CountDownLatch(4);
		final CountDownLatch release = new CountDownLatch(1);
		try (final ExecutorService workers = Executors.newFixedThreadPool(4)) {
			final List<Future<EvitaResponse<SealedEntity>>> futures = new ArrayList<>();
			for (int worker = 0; worker < 4; worker++) {
				futures.add(workers.submit(() -> {
					try (final EvitaSessionContract session = this.enabled.createReadOnlySession("payload")) {
						ready.countDown();
						assertTrue(release.await(30, TimeUnit.SECONDS), query.toString());
						return read(session, query);
					}
				}));
			}
			try {
				assertTrue(ready.await(30, TimeUnit.SECONDS), query.toString());
			} finally {
				release.countDown();
			}
			for (final Future<EvitaResponse<SealedEntity>> future : futures) {
				equalResponse(expected, future.get(30, TimeUnit.SECONDS), query.toString());
			}
		}
		final FormulaCacheSupervisor.Statistics statistics = statistics();
		assertTrue(statistics.admissions() > 0, query.toString());
		assertEquals(statistics.misses(), statistics.admissions() + statistics.duplicateAdmissions(), query.toString());
		assertEquals(statistics.admissions(), statistics.entries(), query.toString());
		assertEquals(statistics.duplicateAdmissions() > 0, statistics.duplicateBytes() > 0, query.toString());
	}

	/** Constructs a preferred plan retaining public access to the filtering tree for hit-depth assertions. */
	@Nonnull
	private static QueryPlan plan(@Nonnull Evita engine, @Nonnull EvitaSession session, @Nonnull Query query) {
		return QueryPlanner.planQuery(((Catalog) engine.getCatalogInstanceOrThrowException("payload"))
			.getCollectionForEntityInternal("product").orElseThrow()
			.createQueryContext(new EvitaRequest(query, MOMENT, SealedEntity.class, null), session));
	}

	/** Records only eligible nodes whose computation actually completed in the warming query. */
	private static void collectComputed(@Nonnull Formula formula, @Nonnull List<FormulaKey> keys) {
		if (formula instanceof AbstractCacheableFormula && formula.getMemoizedResult() != null) {
			keys.add(formula.getKey());
		}
		for (final Formula child : formula.getInnerFormulas()) collectComputed(child, keys);
	}

	/** Finds a restored shared candidate, optionally requiring a frontier price translation specifically. */
	private static boolean restored(@Nonnull Formula formula, @Nonnull List<FormulaKey> keys, boolean frontier) {
		if (formula instanceof AbstractCacheableFormula && formula.getMemoizedResult() != null &&
			formula.getCost() == 0 && keys.contains(formula.getKey()) &&
			(!frontier || formula instanceof PriceIdToEntityIdTranslateFormula)) {
			return true;
		}
		for (final Formula child : formula.getInnerFormulas()) {
			if (restored(child, keys, frontier)) return true;
		}
		return false;
	}

	/** Builds the payload-sensitive reader query, including histogram relaxation through user filters. */
	@Nonnull
	private static Query readerQuery(@Nonnull String reader) {
		return switch (reader) {
			case "priceAsc" -> query(prices(), priceNatural(OrderDirection.ASC));
			case "priceDesc" -> query(prices(), priceNatural(OrderDirection.DESC));
			case "discount" -> query(prices(), priceDiscount(OrderDirection.DESC, "reference"));
			case "histogram" -> query(and(priceInCurrency(CZK), priceInPriceLists("basic"),
				userFilter(priceBetween(BigDecimal.valueOf(150), BigDecimal.valueOf(400)))),
				priceNatural(), priceHistogram(8));
			case "innerHistogram" -> query(prices(), priceNatural(), priceHistogram(8));
			case "facets" -> query(and(attributeGreaterThan("amount", 10),
				userFilter(facetHaving("facets", entityPrimaryKeyInSet(2, 3)))),
				entityPrimaryKeyNatural(OrderDirection.ASC),
				facetSummary(FacetStatisticsDepth.IMPACT));
			case "hierarchy" -> query(hierarchyWithin("categories", entityPrimaryKeyInSet(1)),
				entityPrimaryKeyNatural(OrderDirection.ASC), hierarchyOfReference("categories",
					EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("tree",
						io.evitadb.api.query.QueryConstraints.statistics(
						StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))));
			case "attributeHistogram" -> query(and(prices(), userFilter(attributeBetween("amount", 20, 60))),
				entityPrimaryKeyNatural(OrderDirection.ASC), attributeHistogram(8, "amount"));
			default -> throw new IllegalArgumentException(reader);
		};
	}

	/** Restores an earlier payload after warming entries for a newer, different live version. */
	private static void restore(@Nonnull Evita engine) throws Exception {
		final Path backup = engine.management().backupCatalog("payload", null, null, true).join()
			.path(((FileSystemExportOptions) engine.getConfiguration().export()).getDirectory());
		engine.updateCatalog("payload", s -> {
			s.upsertEntity(product(s, 8, 500));
		});
		try (final EvitaSessionContract session = engine.createReadOnlySession("payload")) {
			read(session, readerQuery("innerHistogram"));
		}
		try (final InputStream input = Files.newInputStream(backup)) {
			engine.management().restoreCatalog("restored", Files.size(backup), input).getFutureResult().join();
		}
		engine.activateCatalog("restored");
		engine.replaceCatalog("restored", "payload");
	}
}
