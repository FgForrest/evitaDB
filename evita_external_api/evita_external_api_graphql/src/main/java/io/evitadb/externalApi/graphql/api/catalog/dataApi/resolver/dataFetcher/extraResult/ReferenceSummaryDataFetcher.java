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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Extracts {@link ReferenceSummary} from {@link EvitaResponse}'s extra results requested by
 * {@link io.evitadb.api.query.require.ReferenceSummary}.
 *
 * When a single query mixes the deprecated `facetSummaryOfReference(...)` form with the new
 * `referenceSummaryOfReference(...)` form, the engine emits two carriers — {@link FacetSummary} for the
 * deprecated form and {@link ReferenceSummary} for the new one — registered under different exact-class keys in
 * {@link EvitaResponse#getExtraResult(Class)}. Each carrier holds a disjoint subset of reference names (every
 * reference is registered on exactly one producer). The unified `referenceSummary` GraphQL field is therefore
 * resolved against the union of both carriers — otherwise the deprecated-form references would silently disappear
 * from the response whenever any sibling reference triggered the new form (for instance because it requested
 * histogram statistics).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@SuppressWarnings("deprecation")
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReferenceSummaryDataFetcher implements DataFetcher<ReferenceSummary> {

	@Nullable
	private static ReferenceSummaryDataFetcher INSTANCE;

	@Nonnull
	public static ReferenceSummaryDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ReferenceSummaryDataFetcher();
		}
		return INSTANCE;
	}

	@Nullable
	@Override
	public ReferenceSummary get(DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<?> response = Objects.requireNonNull(environment.getSource());
		final ReferenceSummary newForm = response.getExtraResult(ReferenceSummary.class);
		final FacetSummary deprecatedForm = response.getExtraResult(FacetSummary.class);
		if (newForm == null) {
			return deprecatedForm;
		}
		if (deprecatedForm == null) {
			return newForm;
		}
		// both carriers are present — merge their (disjoint) reference statistics into a single ReferenceSummary so
		// downstream resolvers find every selected reference regardless of which constraint family produced it
		final Collection<? extends ReferenceGroupStatistics> fromNewForm = newForm.getReferenceStatistics();
		final Collection<? extends ReferenceGroupStatistics> fromDeprecatedForm = deprecatedForm.getReferenceStatistics();
		final List<ReferenceGroupStatistics> merged = new ArrayList<>(fromNewForm.size() + fromDeprecatedForm.size());
		merged.addAll(fromNewForm);
		merged.addAll(fromDeprecatedForm);
		return new ReferenceSummary(merged);
	}
}
