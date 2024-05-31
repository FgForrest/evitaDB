/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.PrefetchedPriceForSale;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AccompanyingPriceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Returns accompanying price for price for sale.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class AccompanyingPriceDataFetcher implements DataFetcher<DataFetcherResult<PriceContract>> {

	private static final String CUSTOM_ACCOMPANYING_PRICE_KEY = "customAccompanyingPrice";

	@Override
	@Nonnull
	public DataFetcherResult<PriceContract> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final EntityQueryContext context = environment.getLocalContext();
		final PrefetchedPriceForSale prefetchedPrices = environment.getSource();
		final EntityDecorator entity = prefetchedPrices.getParentEntity();
		final String priceName = resolvePriceName(environment);

		PriceContract pickedPrice = null;

		if (environment.getArguments().size() == 1 &&
			(environment.getArguments().containsKey(AccompanyingPriceFieldHeaderDescriptor.PRICE_LISTS.name()))) {
			final Optional<PriceContract> priceForName = getPrefetchedPrice(prefetchedPrices, priceName);
			pickedPrice = priceForName.orElse(null);
		} else {
			final String[] priceLists = resolveDesiredPriceListsForCustomPrice(environment);
			final Currency currency = resolveDesiredCurrencyForCustomPrice(environment, context);
			final OffsetDateTime validIn = resolveDesiredValidInForCustomPrice(environment, entity, context);

			final Optional<PriceForSaleWithAccompanyingPrices> customPriceForSaleWithAccompanyingPrices = entity.getPriceForSaleWithAccompanyingPrices(
				currency,
				validIn,
				context.getDesiredPriceInPriceLists(),
				new AccompanyingPrice[] { new AccompanyingPrice(CUSTOM_ACCOMPANYING_PRICE_KEY, priceLists) }
			);

			pickedPrice = customPriceForSaleWithAccompanyingPrices.flatMap(it -> it.accompanyingPrices().get(CUSTOM_ACCOMPANYING_PRICE_KEY)).orElse(null);
		}

		final Locale customLocale = environment.getArgument(AccompanyingPriceFieldHeaderDescriptor.LOCALE.name());
		final EntityQueryContext newContext = context.toBuilder()
			.desiredLocale(customLocale != null ? customLocale : context.getDesiredLocale())
			.build();

		return DataFetcherResult.<PriceContract>newResult()
			.data(pickedPrice)
			.localContext(newContext)
			.build();
	}

	@Nonnull
	private String resolvePriceName(@Nonnull DataFetchingEnvironment environment) {
		return environment.getField().getAlias() != null ? environment.getField().getAlias() : environment.getField().getName();
	}

	@Nonnull
	private Optional<PriceContract> getPrefetchedPrice(@Nonnull PrefetchedPriceForSale prefetchedPrices,
	                                                   @Nonnull String priceName) {
		final Optional<PriceContract> prefetchedPrice = prefetchedPrices.getAccompanyingPrices().get(priceName);
		if (prefetchedPrice == null) {
			throw new GraphQLInternalError("Missing prefetched price `" + priceName + "` for entity `" + prefetchedPrices.getParentEntity().getType() + ":" + prefetchedPrices.getParentEntity().getPrimaryKey() + "`.");
		}
		return prefetchedPrice;
	}

	@Nonnull
	private String[] resolveDesiredPriceListsForCustomPrice(@Nonnull DataFetchingEnvironment environment) {
		return Optional.ofNullable((List<String>) environment.getArgument(AccompanyingPriceFieldHeaderDescriptor.PRICE_LISTS.name()))
			.map(it -> it.toArray(String[]::new))
			.orElseThrow(() -> new GraphQLInvalidArgumentException("Missing price list argument. You can use `" + AccompanyingPriceFieldHeaderDescriptor.PRICE_LISTS.name() + "` parameter for specifying custom price list."));
	}

	@Nonnull
	private Currency resolveDesiredCurrencyForCustomPrice(@Nonnull DataFetchingEnvironment environment, @Nonnull EntityQueryContext context) {
		return Optional.ofNullable((Currency) environment.getArgument(AccompanyingPriceFieldHeaderDescriptor.CURRENCY.name()))
			.or(() -> Optional.ofNullable(context.getDesiredPriceInCurrency()))
			.orElseThrow(() -> new GraphQLInvalidArgumentException("Missing `currency` argument. You can use `" + AccompanyingPriceFieldHeaderDescriptor.CURRENCY.name() + "` parameter for specifying custom currency."));
	}

	@Nullable
	private OffsetDateTime resolveDesiredValidInForCustomPrice(@Nonnull DataFetchingEnvironment environment,
	                                                           @Nonnull EntityDecorator entity,
	                                                           @Nonnull EntityQueryContext context) {
		return Optional.ofNullable((OffsetDateTime) environment.getArgument(AccompanyingPriceFieldHeaderDescriptor.VALID_IN.name()))
			.or(() -> Optional.ofNullable((Boolean) environment.getArgument(AccompanyingPriceFieldHeaderDescriptor.VALID_NOW.name()))
				.map(validNow -> validNow ? entity.getAlignedNow() : null))
			.or(() -> Optional.ofNullable(context.getDesiredPriceValidIn()))
			.or(() -> Optional.of(context.isDesiredPriceValidInNow())
				.map(validNow -> validNow ? entity.getAlignedNow() : null))
			.orElse(null);
	}
}
