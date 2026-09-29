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

package io.evitadb.utils;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.require.Debug;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Objects;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The plan an entity query is steered towards, so that an expectation can be held against both ways the engine
 * answers a filter: by resolving the indexes, or by prefetching the entity bodies and evaluating the constraints
 * that offer an alternative on them.
 *
 * Steering onto the prefetch takes more than leaving the index scan out. `VERIFY_POSSIBLE_CACHING_TREES` and
 * `PREFER_INDEX_SCAN` both select a planning policy that denies the prefetch, and the prefetch needs resolved primary
 * keys in conjunctive scope, which most filters - a `referenceHaving` over attributes among them - do not produce. A
 * query run merely without the index-scan preference therefore usually resolves the indexes too, and a test comparing
 * it with the index-scan arm compares the index scan with itself. {@link #PREFETCH} supplies both missing pieces, and
 * {@link #assertTaken} proves from the telemetry that the plan ran - the query must request `queryTelemetry()` for it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum PlanPreference {

	/**
	 * `PREFER_INDEX_SCAN` together with `VERIFY_POSSIBLE_CACHING_TREES`: the prefetch is denied, so the query
	 * resolves the indexes, and every cacheable variant of the formula tree is checked against the main plan.
	 */
	INDEX_SCAN,
	/**
	 * `PREFER_PREFETCHING` alone, with the filter conjoined with an `entityPrimaryKeyInSet` over every candidate
	 * entity. The keys cover every entity that may match, so they are neutral to the answer, and they are what makes
	 * the prefetch possible. The engine then prefetches whenever some formula registers the entity content it reads -
	 * every attribute formula does - whatever the cost. The bodies are read only by the constraints that offer an
	 * alternative evaluated on them, such as `entityLocaleEquals` or the `attributeIsNotNull` of an attribute unique in
	 * no requested scope; every other constraint, `attributeIsNull` and a `referenceHaving` body among them, is still
	 * answered from the indexes on this plan, under the default planning policy.
	 */
	PREFETCH;

	/**
	 * Returns the `debug` requirement steering the query onto this plan.
	 *
	 * @return the debug requirement
	 */
	@Nonnull
	public Debug debug() {
		return switch (this) {
			case INDEX_SCAN ->
				QueryConstraints.debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.PREFER_INDEX_SCAN);
			case PREFETCH -> QueryConstraints.debug(DebugMode.PREFER_PREFETCHING);
		};
	}

	/**
	 * Returns the filter constraints this plan queries with: the passed ones on {@link #INDEX_SCAN}, and the passed
	 * ones conjoined with `entityPrimaryKeyInSet(candidates)` on {@link #PREFETCH}.
	 *
	 * @param candidates primary keys of every entity that may match the filter
	 * @param filter     the filter constraints
	 * @return the filter constraints to place under `filterBy`
	 */
	@Nonnull
	public FilterConstraint[] filter(@Nonnull int[] candidates, @Nonnull FilterConstraint... filter) {
		if (this == INDEX_SCAN) {
			return filter;
		}
		final FilterConstraint[] result = Arrays.copyOf(filter, filter.length + 1);
		result[filter.length] = entityPrimaryKeyInSet(candidates);
		return result;
	}

	/**
	 * Asserts from the telemetry of the response that the query ran on this plan: the entity bodies were prefetched
	 * exactly when this is {@link #PREFETCH}.
	 *
	 * @param response the response of a query that requested `queryTelemetry()`
	 */
	public void assertTaken(@Nonnull EvitaResponse<?> response) {
		assertEquals(
			this == PREFETCH, prefetched(response),
			() -> "The query did not run on the " + this + " plan:\n" + response.getExtraResult(QueryTelemetry.class)
		);
	}

	/**
	 * Answers whether the plan that answered the query prefetched the entity bodies.
	 *
	 * @param response the response of a query that requested `queryTelemetry()`
	 * @return true when the {@link QueryPhase#EXECUTION_PREFETCH} step ran
	 */
	public static boolean prefetched(@Nonnull EvitaResponse<?> response) {
		return hasStep(
			Objects.requireNonNull(response.getExtraResult(QueryTelemetry.class), "The query must collect telemetry!"),
			QueryPhase.EXECUTION_PREFETCH
		);
	}

	/**
	 * Answers whether some step of the telemetry subtree belongs to the phase.
	 *
	 * @param telemetry the telemetry subtree
	 * @param phase     the phase looked for
	 * @return true when such a step exists
	 */
	private static boolean hasStep(@Nonnull QueryTelemetry telemetry, @Nonnull QueryPhase phase) {
		if (telemetry.getOperation() == phase) {
			return true;
		}
		for (QueryTelemetry step : telemetry.getSteps()) {
			if (hasStep(step, phase)) {
				return true;
			}
		}
		return false;
	}

}
