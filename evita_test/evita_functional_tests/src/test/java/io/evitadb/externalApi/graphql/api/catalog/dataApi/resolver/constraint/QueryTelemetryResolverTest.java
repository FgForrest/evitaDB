/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.QueryTelemetry;
import io.evitadb.api.query.require.QueryTelemetryContent;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.QueryTelemetryNodeDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.REQUIRE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link QueryTelemetryResolver}, which is the only place the GraphQL API decides *how much* telemetry to
 * ask the engine for.
 *
 * This is unit-level on purpose. GraphQL has no field argument for the level - selecting the `plan` field is itself
 * the opt-in - so the negative half of the contract, "a client that does not select `plan` never pays for one", is
 * not reachable over the wire: a wire test cannot assert the absence of a field it did not select in the first place.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Query telemetry level derived from the GraphQL selection set")
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(REQUIRE)
class QueryTelemetryResolverTest {

	/**
	 * Builds a `queryTelemetry` selected field that either does or does not select the `plan` sub-field.
	 *
	 * @param planSelected whether the returned field's selection set contains `plan`
	 * @return the mocked selected field
	 */
	@Nonnull
	private static SelectedField telemetryField(boolean planSelected) {
		final DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
		when(selectionSet.contains(anyString())).thenReturn(false);
		when(selectionSet.contains(QueryTelemetryNodeDescriptor.PLAN.name())).thenReturn(planSelected);

		final SelectedField field = mock(SelectedField.class);
		when(field.getSelectionSet()).thenReturn(selectionSet);
		return field;
	}

	/**
	 * Builds an aggregator reporting the passed fields as the immediate `queryTelemetry` fields.
	 *
	 * @param fields the fields the aggregator reports
	 * @return the mocked aggregator
	 */
	@Nonnull
	private static SelectionSetAggregator aggregatorOf(@Nonnull List<SelectedField> fields) {
		final SelectionSetAggregator aggregator = mock(SelectionSetAggregator.class);
		when(aggregator.getImmediateFields(ExtraResultsDescriptor.QUERY_TELEMETRY.name())).thenReturn(fields);
		return aggregator;
	}

	@Nested
	@DisplayName("Opting in")
	class OptingIn {

		@Test
		@DisplayName("should resolve no constraint at all when telemetry is not selected")
		void shouldResolveNothingWhenTelemetryNotSelected() {
			final Optional<RequireConstraint> resolved =
				QueryTelemetryResolver.getInstance().resolve(aggregatorOf(List.of()));

			// an absent constraint rather than an argument-less one - the engine must not even build the tree
			assertTrue(resolved.isEmpty());
		}

		@Test
		@DisplayName("should resolve the timings level when the plan field is not selected")
		void shouldResolveTimingsWhenPlanNotSelected() {
			final Optional<RequireConstraint> resolved =
				QueryTelemetryResolver.getInstance().resolve(aggregatorOf(List.of(telemetryField(false))));

			// this is the half a wire test cannot reach, and it is the one that matters: selecting telemetry
			// without selecting the plan must not charge the query for rendering one
			assertTrue(resolved.isPresent());
			final QueryTelemetry constraint = (QueryTelemetry) resolved.get();
			assertEquals(QueryTelemetryContent.TIMINGS, constraint.getContent());
			assertFalse(constraint.isPlanRequested());
		}

		@Test
		@DisplayName("should resolve the plan level when the plan field is selected")
		void shouldResolvePlanWhenPlanSelected() {
			final Optional<RequireConstraint> resolved =
				QueryTelemetryResolver.getInstance().resolve(aggregatorOf(List.of(telemetryField(true))));

			assertTrue(resolved.isPresent());
			assertTrue(((QueryTelemetry) resolved.get()).isPlanRequested());
		}

		@Test
		@DisplayName("should resolve the plan level when any one of several selections asks for it")
		void shouldResolvePlanWhenAnySelectionAsksForIt() {
			// the same extra-result field can be selected more than once - through fragments, or under aliases -
			// so the decision is taken across all of them rather than from whichever happens to come first
			final Optional<RequireConstraint> resolved = QueryTelemetryResolver.getInstance().resolve(
				aggregatorOf(List.of(telemetryField(false), telemetryField(true), telemetryField(false)))
			);

			assertTrue(resolved.isPresent());
			assertTrue(((QueryTelemetry) resolved.get()).isPlanRequested());
		}
	}
}
