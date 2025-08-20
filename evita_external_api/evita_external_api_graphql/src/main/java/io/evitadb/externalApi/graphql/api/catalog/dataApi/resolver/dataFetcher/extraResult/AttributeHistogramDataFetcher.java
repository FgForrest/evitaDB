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
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;

/**
 * Extracts individual attribute {@link HistogramContract}s for each attribute from {@link EvitaResponse}s extra results
 * requested by {@link io.evitadb.api.query.require.AttributeHistogram}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AttributeHistogramDataFetcher implements DataFetcher<Map<String, HistogramContract>> {

	@Nullable
	private static AttributeHistogramDataFetcher INSTANCE;

	@Nonnull
	public static AttributeHistogramDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new AttributeHistogramDataFetcher();
		}
		return INSTANCE;
	}

	@Nullable
	@Override
	public Map<String, HistogramContract> get(DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<?> response = Objects.requireNonNull(environment.getSource());
		final AttributeHistogram attributeHistogram = response.getExtraResult(AttributeHistogram.class);
		if (attributeHistogram == null) {
			return null;
		}
		return attributeHistogram.getHistograms();
	}
}
