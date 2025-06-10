/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.PrefetchedPriceForSale;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Returns accompanying price for price for sale.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccompanyingPriceDataFetcher implements DataFetcher<DataFetcherResult<PriceContract>> {

	@Nullable
	private static AccompanyingPriceDataFetcher INSTANCE;

	@Nonnull
	public static AccompanyingPriceDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new AccompanyingPriceDataFetcher();
		}
		return INSTANCE;
	}

	@Nonnull
	private static String resolvePriceName(@Nonnull DataFetchingEnvironment environment) {
		return environment.getField().getAlias() != null ? environment.getField().getAlias() : environment.getField().getName();
	}

	@Nonnull
	private static Optional<PriceContract> getPrefetchedPrice(
		@Nonnull PrefetchedPriceForSale prefetchedPrices,
		@Nonnull String priceName
	) {
		final Optional<PriceContract> prefetchedPrice = prefetchedPrices.getAccompanyingPrices().get(priceName);
		if (prefetchedPrice == null) {
			throw new GraphQLInternalError("Missing prefetched price `" + priceName + "` for entity `" + prefetchedPrices.getParentEntity().getType() + ":" + prefetchedPrices.getParentEntity().getPrimaryKey() + "`.");
		}
		return prefetchedPrice;
	}

	@Override
	@Nonnull
	public DataFetcherResult<PriceContract> get(DataFetchingEnvironment environment) throws Exception {
		final PrefetchedPriceForSale prefetchedPrices = environment.getSource();
		if (prefetchedPrices == null) {
			throw new GraphQLInternalError("PrefetchedPriceForSale is null");
		}

		final String priceName = resolvePriceName(environment);
		final Optional<PriceContract> priceForName = getPrefetchedPrice(prefetchedPrices, priceName);
		final PriceContract pickedPrice = priceForName.orElse(null);

		return DataFetcherResult.<PriceContract>newResult()
			.data(pickedPrice)
			.build();
	}

}
