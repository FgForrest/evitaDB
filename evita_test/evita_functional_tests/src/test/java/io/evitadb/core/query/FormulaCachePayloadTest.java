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
import io.evitadb.export.file.configuration.FileSystemExportOptions;
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
import java.util.function.Predicate;

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
		final long before = cacheStatistics().hits();
		try (final EvitaSession session = FormulaCacheTestSessions.readOnly(this.enabled, "payload");
			final EvitaSessionContract oracle = this.disabled.createReadOnlySession("payload")) {
			final QueryPlan plan = plan(this.enabled, session, second);
			final EvitaResponse<SealedEntity> response = plan.execute();
			equalResponse(read(oracle, second), response, position + ": " + second);
			assertTrue(cacheStatistics().hits() > before, position + " must hit: " + second);
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
			assertTrue(restored(plan.getFilter(), computed, position),
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
		final FormulaCacheSupervisor.Statistics before = cacheStatistics();
		compare("payload", query);
		assertTrue(cacheStatistics().hits() > before.hits(), reader + " must consume hits: " + query);
		assertEquals(before.misses(), cacheStatistics().misses(),
			reader + " must find every entry it looks up: " + query);
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
		final long before = cacheStatistics().hits();
		compare("payload", second);
		assertTrue(cacheStatistics().hits() > before, "Shared filter must hit: " + second);
		final long admitted = cacheStatistics().admissions();
		final long hits = cacheStatistics().hits();
		compare("payload", second);
		assertTrue(cacheStatistics().hits() > hits, "Warmed computer must hit: " + second);
		assertEquals(admitted, cacheStatistics().admissions(), second.toString());
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
		final FormulaCacheSupervisor.Statistics before = cacheStatistics();
		compare("payload", query);
		assertTrue(cacheStatistics().hits() > before.hits(), query.toString());
		assertEquals(before.admissions(), cacheStatistics().admissions(), query.toString());
		change("payload", s -> s.upsertEntity(product(s, 8, 500)));
		final FormulaCacheSupervisor.Statistics changed = cacheStatistics();
		compare("payload", query);
		assertTrue(cacheStatistics().misses() > changed.misses(), query.toString());
		assertTrue(cacheStatistics().admissions() > changed.admissions(), query.toString());
		final long readmitted = cacheStatistics().hits();
		compare("payload", query);
		assertTrue(cacheStatistics().hits() > readmitted, "Re-admitted entries must serve the next read: " + query);
	}

	/**
	 * Populates and warms both catalogs before swapping identities, then requires correct answers afterwards: a
	 * renamed catalog keeps hitting its own entries, while a recreated collection and a restored catalog bring new
	 * index instances and reuse nothing admitted before the operation.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"rename", "replace", "recreate", "restore", "alternate"})
	@DisplayName("Keeps payloads isolated across catalog and collection lifecycles")
	void shouldIsolateLifecycle(@Nonnull String operation) throws Exception {
		start(0);
		populate("payload", true);
		populate("other", true);
		final Query query = readerQuery("innerHistogram");
		final FormulaCacheSupervisor.Statistics initial = cacheStatistics();
		compare("payload", query);
		final FormulaCacheSupervisor.Statistics payloadCold = cacheStatistics();
		// hits of a cold read come only from nodes shared within the query itself
		final long coldHits = payloadCold.hits() - initial.hits();
		assertTrue(payloadCold.admissions() > initial.admissions(), operation + ": cold read must admit: " + query);
		compare("other", query);
		final FormulaCacheSupervisor.Statistics otherCold = cacheStatistics();
		assertTrue(otherCold.admissions() > payloadCold.admissions(),
			operation + ": an identical catalog must admit entries of its own: " + query);
		assertEquals(coldHits, otherCold.hits() - payloadCold.hits(),
			operation + ": an identical catalog must reuse no entry of the other one: " + query);
		assertWarmHit("payload", query, operation);
		assertWarmHit("other", query, operation);
		if (operation.equals("alternate")) {
			change("other", s -> s.upsertEntity(product(s, 8, 500)));
			for (int run = 0; run < 4; run++) {
				final FormulaCacheSupervisor.Statistics beforeOther = cacheStatistics();
				compare("other", query);
				if (run == 0) {
					assertTrue(cacheStatistics().admissions() > beforeOther.admissions(),
						"The changed catalog must admit its new version: " + query);
				} else {
					assertTrue(cacheStatistics().hits() > beforeOther.hits(),
						"The changed catalog must hit its new entries in run " + run + ": " + query);
				}
				assertWarmHit("payload", query, operation + " run " + run);
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
			final FormulaCacheSupervisor.Statistics beforeFirst = cacheStatistics();
			compare(name, query);
			final FormulaCacheSupervisor.Statistics afterFirst = cacheStatistics();
			if (operation.equals("rename")) {
				assertTrue(afterFirst.hits() > beforeFirst.hits(),
					"A renamed catalog keeps its indexes, so its entries must keep hitting: " + query);
				assertEquals(beforeFirst.admissions(), afterFirst.admissions(),
					"A renamed catalog must not admit its unchanged data again: " + query);
			} else if (!operation.equals("replace")) {
				assertEquals(coldHits, afterFirst.hits() - beforeFirst.hits(),
					operation + " brings new index instances, so nothing admitted before may be reused: " + query);
			}
			// the replacing catalog keeps the indexes of its unchanged data, so for it only the comparison decides
			assertWarmHit(name, query, operation);
		}
	}

	/** Requires a repeated read of one catalog to be served from its entries without admitting anything new. */
	private void assertWarmHit(@Nonnull String name, @Nonnull Query query, @Nonnull String context) {
		final FormulaCacheSupervisor.Statistics before = cacheStatistics();
		compare(name, query);
		final FormulaCacheSupervisor.Statistics after = cacheStatistics();
		assertTrue(after.hits() > before.hits(), context + ": warm read of " + name + " must hit: " + query);
		assertEquals(before.admissions(), after.admissions(),
			context + ": warm read of " + name + " must not admit: " + query);
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
		final FormulaCacheSupervisor.Statistics statistics = cacheStatistics();
		assertTrue(statistics.admissions() > 0, query.toString());
		assertEquals(statistics.misses(), statistics.admissions() + statistics.duplicateAdmissions(), query.toString());
		assertEquals(statistics.admissions(), statistics.entries(), query.toString());
		assertEquals(statistics.duplicateAdmissions() > 0, statistics.duplicateBytes() > 0, query.toString());
	}

	/** Plans the query on the `product` collection of the `payload` catalog, keeping its filter tree inspectable. */
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

	/**
	 * Finds a node restored from an entry the warming query admitted, at the depth the position names: the root
	 * itself at the top, a node that is neither the root nor a price translation in the middle, a price
	 * translation at the frontier.
	 */
	private static boolean restored(@Nonnull Formula root, @Nonnull List<FormulaKey> keys, @Nonnull String position) {
		return switch (position) {
			case "top" -> isRestored(root, keys);
			case "middle" -> anyRestored(root, keys,
				formula -> formula != root && !(formula instanceof PriceIdToEntityIdTranslateFormula));
			case "frontier" -> anyRestored(root, keys, PriceIdToEntityIdTranslateFormula.class::isInstance);
			default -> throw new IllegalArgumentException(position);
		};
	}

	/** Searches the tree for a restored node the depth filter accepts. */
	private static boolean anyRestored(
		@Nonnull Formula formula, @Nonnull List<FormulaKey> keys, @Nonnull Predicate<Formula> depth
	) {
		if (depth.test(formula) && isRestored(formula, keys)) {
			return true;
		}
		for (final Formula child : formula.getInnerFormulas()) {
			if (anyRestored(child, keys, depth)) return true;
		}
		return false;
	}

	/** Tells a node whose result came from an entry the warming query admitted, without computing it here. */
	private static boolean isRestored(@Nonnull Formula formula, @Nonnull List<FormulaKey> keys) {
		return formula instanceof AbstractCacheableFormula && formula.getMemoizedResult() != null &&
			formula.getCost() == 0 && keys.contains(formula.getKey());
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
						statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))));
			case "attributeHistogram" -> query(and(prices(), userFilter(attributeBetween("amount", 20, 60))),
				entityPrimaryKeyNatural(OrderDirection.ASC), attributeHistogram(8, "amount"));
			default -> throw new IllegalArgumentException(reader);
		};
	}

	/** Restores an earlier payload after warming entries for a newer, different live version. */
	private void restore(@Nonnull Evita engine) throws Exception {
		final Path backup = engine.management().backupCatalog("payload", null, null, true).join()
			.path(((FileSystemExportOptions) engine.getConfiguration().export()).getDirectory());
		engine.updateCatalog("payload", s -> {
			s.upsertEntity(product(s, 8, 500));
		});
		final long admitted = engine == this.enabled ? cacheStatistics().admissions() : 0;
		try (final EvitaSessionContract session = engine.createReadOnlySession("payload")) {
			read(session, readerQuery("innerHistogram"));
		}
		if (engine == this.enabled) {
			assertTrue(cacheStatistics().admissions() > admitted,
				"The newer version must be warmed before the restore");
		}
		try (final InputStream input = Files.newInputStream(backup)) {
			engine.management().restoreCatalog("restored", Files.size(backup), input).getFutureResult().join();
		}
		engine.activateCatalog("restored");
		engine.replaceCatalog("restored", "payload");
	}
}
