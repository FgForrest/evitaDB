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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Returns the referenced entity whose value anchors the minimum bucket of a reference-scope
 * {@link HistogramContract}. When the histogram has no anchor entity the fetcher returns `null`.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReferenceHistogramMinEntityDataFetcher implements DataFetcher<SealedEntity> {

	private static final ReferenceHistogramMinEntityDataFetcher INSTANCE = new ReferenceHistogramMinEntityDataFetcher();

	@Nonnull
	public static ReferenceHistogramMinEntityDataFetcher getInstance() {
		return INSTANCE;
	}

	@Nullable
	@Override
	public SealedEntity get(DataFetchingEnvironment environment) throws Exception {
		final HistogramContract histogram = environment.getSource();
		if (histogram == null) {
			return null;
		}
		return histogram.getMinReferencedEntity().orElse(null);
	}
}
