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
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Passthrough data fetcher for the `histogramStatistics` wrapper field on a reference group statistics object.
 * Returns the parent {@link ReferenceGroupStatistics} unchanged so that child histogram index fields can extract
 * their named histograms via {@link HistogramStatisticsDataFetcher}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HistogramStatisticsWrapperDataFetcher implements DataFetcher<ReferenceGroupStatistics> {

	private static final HistogramStatisticsWrapperDataFetcher INSTANCE = new HistogramStatisticsWrapperDataFetcher();

	@Nonnull
	public static HistogramStatisticsWrapperDataFetcher getInstance() {
		return INSTANCE;
	}

	@Nullable
	@Override
	public ReferenceGroupStatistics get(DataFetchingEnvironment environment) throws Exception {
		return environment.getSource();
	}
}
