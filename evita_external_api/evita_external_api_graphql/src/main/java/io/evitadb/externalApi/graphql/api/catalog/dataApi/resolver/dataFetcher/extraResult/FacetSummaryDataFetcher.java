/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;

import javax.annotation.Nonnull;

/**
 * Extracts {@link FacetSummary} from {@link EvitaResponse}'s extra results requested by
 * {@link io.evitadb.api.query.require.FacetSummary}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class FacetSummaryDataFetcher implements DataFetcher<DataFetcherResult<FacetSummary>> {

	@Nonnull
	@Override
	public DataFetcherResult<FacetSummary> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<?> response = environment.getSource();
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);
		return DataFetcherResult.<FacetSummary>newResult()
			.data(facetSummary)
			.build();
	}
}
