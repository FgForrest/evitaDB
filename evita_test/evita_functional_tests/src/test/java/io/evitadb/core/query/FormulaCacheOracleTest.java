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
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.Debug;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.FormulaCacheSupervisor;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays bounded deterministic mutations against independent engines and a primary-key scope model.
 * Every query compares the key-free verification path, the ordinary path and the cache-disabled engine.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula cache bounded cross-version oracle")
@Tag(ENGINE)
@Tag(CACHE)
@Tag(QUERY)
@Tag(PRICE)
@Tag(FACET)
@Tag(HIERARCHY)
@Tag(SCHEMA)
@Tag(TRANSACTION)
class FormulaCacheOracleTest extends FormulaCacheScenarioSupport {
	/** Fixed number of mutation steps per seed. */
	private static final int STEPS = 40;
	/** Basic CZK prices at the query moment: the list and currency whose validity a mutation moves past it. */
	private static final Query BASIC_CZK = query(and(priceInPriceLists("basic"), priceInCurrency(CZK),
		priceValidIn(MOMENT)), priceNatural(), priceHistogram(6));

	/** Executes every mutation kind for each seed; wall time never controls operation selection. */
	@ParameterizedTest(name = "seed {0}")
	@ValueSource(longs = {41, 42})
	@Timeout(60)
	@DisplayName("Agrees across catalog versions, debug policies and pinned readers")
	void shouldMatchOracleAcrossVersions(long seed) {
		start((int) (seed % 2));
		populate("first", false);
		populate("second", false);
		final Random random = new Random(seed);
		final String[] names = {"first", "second"};
		final int[][] model = {initialModel(), initialModel()};
		final int liveStep = 2 + random.nextInt(3);
		int crossVersionReads = 0;
		int validityChanges = 0;
		for (int step = 0; step < STEPS; step++) {
			final int selected = step % 2;
			final int operation = step % 12;
			final int value = random.nextInt(300);
			final String context = "seed=" + seed + ", step=" + step + ", operation=" + operation;
			try {
				if (step == liveStep) {
					for (final String name : names) change(name, EvitaSessionContract::goLiveAndClose);
				}
				// A registered reader pins the old catalog before a different session commits its successor.
				if (step > liveStep && operation < 9) {
					if (mutateUnderPinnedReader(names, model, selected, operation, step, value, context)) {
						validityChanges++;
					}
					crossVersionReads += operation >= 6 ? 1 : 0;
				} else {
					mutate(names, model, selected, operation, step, value);
				}
				for (int catalog = 0; catalog < names.length; catalog++) {
					compareQueries(names[catalog], seed, step);
					assertModel(names[catalog], model[catalog], context);
				}
				final String diagnostic = context + ", query=" + query(prices(), priceNatural());
				assertTrue(cacheStatistics().active(), diagnostic);
				assertEquals(0, cacheStatistics().mismatches(), diagnostic);
				if (step < liveStep) assertEquals(0, cacheStatistics().admissions(), diagnostic);
			} catch (final Throwable failure) {
				throw new AssertionError(context + ", query=mutation or query set " + queries(), failure);
			}
		}
		final String last = "seed=" + seed + ", step=" + (STEPS - 1) + ", query=" + queries();
		assertTrue(cacheStatistics().hits() > 0, last);
		if (seed % 2 == 1) assertTrue(cacheStatistics().verifiedHits() > 0, last);
		assertTrue(crossVersionReads > 0, last + ": no commit kept the price operands");
		assertTrue(validityChanges > 0, last + ": no validity change was visible to " + BASIC_CZK);
	}

	/**
	 * Commits one mutation while a reader stays pinned to the previous version, then requires the pinned reader to
	 * keep its answer and the first read of the new version to agree with the cache-disabled engine. After a commit
	 * that keeps the price operands (operations 6 to 8), that first read must hit entries admitted before it.
	 *
	 * @return whether the cache-disabled answer for basic CZK prices changed with the commit
	 */
	private boolean mutateUnderPinnedReader(
		@Nonnull String[] names, @Nonnull int[][] model, int selected, int operation, int step, int value,
		@Nonnull String context
	) {
		final Query pinnedQuery = query(prices(), priceNatural(), priceHistogram(6));
		try (final EvitaSessionContract pinned = this.enabled.createReadOnlySession(names[selected]);
			final EvitaSessionContract oracle = this.disabled.createReadOnlySession(names[selected])) {
			final EvitaResponse<SealedEntity> expected = read(oracle, pinnedQuery);
			equalResponse(expected, read(pinned, pinnedQuery), context + ", query=" + pinnedQuery);
			final List<Integer> validBefore = operation == 5 ? oracleKeys(names[selected]) : null;
			mutate(names, model, selected, operation, step, value);
			final boolean validityChanged = validBefore != null && !validBefore.equals(oracleKeys(names[selected]));
			final FormulaCacheSupervisor.Statistics committed = cacheStatistics();
			compare(names[selected], pinnedQuery);
			if (operation >= 6) {
				// operations 6 to 8 move facets, groups and the hierarchy, so the price operands survive
				assertTrue(cacheStatistics().hits() > committed.hits(), context + ", query=" + pinnedQuery +
					": the first read after a commit keeping the price operands must hit earlier entries");
			}
			equalResponse(expected, read(pinned, pinnedQuery), context + ", pinned query=" + pinnedQuery);
			equalResponse(read(oracle, pinnedQuery), read(pinned, flagged(pinnedQuery)),
				context + ", flagged pinned query=" + pinnedQuery);
			return validityChanged;
		}
	}

	/** Reads the primary keys the cache-disabled engine returns for basic CZK prices at the query moment. */
	@Nonnull
	private List<Integer> oracleKeys(@Nonnull String name) {
		try (final EvitaSessionContract oracle = this.disabled.createReadOnlySession(name)) {
			return read(oracle, BASIC_CZK).getRecordData().stream().map(SealedEntity::getPrimaryKey).toList();
		}
	}

	/** Includes the same query with verification enabled and with ordinary prefetch policy. */
	private void compareQueries(@Nonnull String name, long seed, int step) {
		try (final EvitaSessionContract actual = this.enabled.createReadOnlySession(name);
			final EvitaSessionContract oracle = this.disabled.createReadOnlySession(name)) {
			for (final Query query : queries()) {
				final String context = "seed=" + seed + ", step=" + step + ", catalog=" + name + ", query=" + query;
				try {
					final EvitaResponse<SealedEntity> expected = read(oracle, query);
					final EvitaResponse<SealedEntity> verified = read(actual, flagged(query));
					equalResponse(expected, verified, context + ", flagged");
					equalResponse(verified, read(actual, ordinary(query)), context + ", unflagged");
				} catch (final Throwable failure) {
					throw new AssertionError(context, failure);
				}
			}
		}
	}

	/** Exercises all five attribute types, both scopes, ordered price components and relaxed extra results. */
	@Nonnull
	private static List<Query> queries() {
		return List.of(
			query(prices(), priceNatural()),
			query(prices(), priceDiscount(OrderDirection.DESC, "reference")),
			query(prices(), priceNatural(), priceHistogram(6)),
			BASIC_CZK,
			query(and(priceInPriceLists("basic"), priceInCurrency(EUR),
				userFilter(priceBetween(BigDecimal.valueOf(150), BigDecimal.valueOf(400)))),
				priceNatural(), priceHistogram(6)),
			query(and(attributeBetween("amount", 10, 90), userFilter(attributeBetween("amount", 20, 60))),
				attributeNatural("rank"), attributeHistogram(6, "amount")),
			query(and(attributeEquals("code", "C2"), attributeGreaterThan("decimal", BigDecimal.valueOf(50, 2))),
				attributeNatural("decimal")),
			query(and(attributeGreaterThanEquals("moment", MOMENT.plusDays(2)),
				attributeGreaterThan("rank", 40L)), attributeNatural("moment")),
			query(userFilter(facetHaving("facets", entityPrimaryKeyInSet(2, 3))),
				entityPrimaryKeyNatural(OrderDirection.ASC),
				facetSummary(FacetStatisticsDepth.IMPACT)),
			query(hierarchyWithin("categories", entityPrimaryKeyInSet(1)), entityPrimaryKeyNatural(OrderDirection.ASC)),
			query(scope(Scope.ARCHIVED), entityPrimaryKeyNatural(OrderDirection.ASC))
		);
	}

	/** Replaces the policy requirement without changing the semantics or the requested bodies. */
	@Nonnull
	private static Query flagged(@Nonnull Query query) {
		return withDebug(query, DebugMode.VERIFY_POSSIBLE_CACHING_TREES);
	}

	/** Leaves production planning free to use prefetch and omits the key-free verification flag. */
	@Nonnull
	private static Query ordinary(@Nonnull Query query) {
		return withDebug(query, null);
	}

	/** Rebuilds the query without its debug requirement, appending the passed mode last when there is one. */
	@Nonnull
	private static Query withDebug(@Nonnull Query query, @Nullable DebugMode mode) {
		final List<RequireConstraint> requirements = new ArrayList<>(Arrays.asList(query.getRequire().getChildren()));
		requirements.removeIf(Debug.class::isInstance);
		if (mode != null) {
			requirements.add(debug(mode));
		}
		return Query.query(query.getCollection(), query.getFilterBy(), query.getOrderBy(),
			require(requirements.toArray(RequireConstraint[]::new)));
	}

	/** Cycles every mutation kind while fixed-seed values determine changed operands and price validity. */
	private void mutate(
		@Nonnull String[] names, @Nonnull int[][] model, int selected, int operation, int step, int value
	) {
		final String name = names[selected];
		switch (operation) {
			case 0 -> {
				change(name, s -> s.upsertEntity(product(s, 199, value)));
				model[selected][199] = 1;
			}
			case 1 -> {
				final int inserted = 1 - selected;
				change(names[inserted], s -> s.deleteEntity("product", 199));
				model[inserted][199] = 0;
			}
			case 2 -> {
				change(name, s -> s.archiveEntity("product", 181));
				model[selected][181] = 2;
			}
			case 3 -> {
				// Archive and restore the same catalog even though adjacent steps alternate catalog selection.
				final int archived = 1 - selected;
				change(names[archived], s -> s.restoreEntity("product", 181));
				model[archived][181] = 1;
			}
			case 4 -> change(name, s -> s.upsertEntity(product(s, 8, value)));
			case 5 -> change(name, s -> s.upsertEntity(s.getEntity("product", 9, priceContentAll())
				.orElseThrow().openForWrite().setPrice(1, "basic", CZK, BigDecimal.valueOf(99 + step),
					BigDecimal.valueOf(21), BigDecimal.valueOf((99 + step) * 121L, 2),
					DateTimeRange.between(MOMENT.plusDays(1), MOMENT.plusDays(3)), true)));
			case 6 -> change(name, s -> s.upsertEntity(s.getEntity("product", 10, referenceContentAll())
				.orElseThrow().openForWrite().removeReference("facets", 5)
				.setReference("facets", 2, r -> r.setGroup(1))));
			case 7 -> change(name, s -> s.upsertEntity(s.getEntity("product", 11, referenceContentAll())
				.orElseThrow().openForWrite().setReference("facets", 6, r -> r.setGroup(step / 12 % 2 + 1))));
			case 8 -> change(name, s -> s.upsertEntity(s.getEntity("category", 4, hierarchyContent())
				.orElseThrow().openForWrite().setParent(step / 12 % 2 == 0 ? 2 : 5)));
			case 9 -> change(name, s -> s.defineEntitySchema("product")
				.withAttribute("generation" + step, Integer.class, a -> a.filterable().nullable()).updateVia(s));
			case 10 -> {
				change(name, s -> {
					s.deleteCollection("product");
					products(s, value);
				});
				model[selected] = initialModel();
			}
			case 11 -> {
				if (step == 23) {
					final int source = 1 - selected;
					for (final Evita engine : List.of(this.enabled, this.disabled)) {
						engine.replaceCatalog(names[source], name);
					}
					model[selected] = model[source].clone();
					populate(names[source], true);
					model[source] = initialModel();
				} else {
					final String renamed = name + "Moved";
					for (final Evita engine : List.of(this.enabled, this.disabled)) engine.renameCatalog(name, renamed);
					names[selected] = renamed;
				}
			}
			default -> throw new IllegalArgumentException("Unknown operation " + operation);
		}
	}

	/** Models absent, live and archived primary keys independently of either engine's indexes. */
	@Nonnull
	private static int[] initialModel() {
		final int[] model = new int[201];
		Arrays.fill(model, 1, model.length, 1);
		return model;
	}

	/** Checks the complete scope membership against the independent model on both execution policies. */
	private void assertModel(@Nonnull String name, @Nonnull int[] model, @Nonnull String context) {
		for (final Scope scope : Scope.values()) {
			final Query query = query(scope(scope), entityPrimaryKeyNatural(OrderDirection.ASC));
			final List<Integer> expected = new ArrayList<>();
			for (int id = 1; id < model.length; id++) {
				if (model[id] == (scope == Scope.LIVE ? 1 : 2)) expected.add(id);
			}
			try (final EvitaSessionContract session = this.enabled.createReadOnlySession(name)) {
				for (final Query policy : List.of(flagged(query), ordinary(query))) {
					assertEquals(expected, read(session, policy).getRecordData().stream()
						.map(SealedEntity::getPrimaryKey).toList(),
						context + ", catalog=" + name + ", query=" + policy);
				}
			}
		}
	}
}
