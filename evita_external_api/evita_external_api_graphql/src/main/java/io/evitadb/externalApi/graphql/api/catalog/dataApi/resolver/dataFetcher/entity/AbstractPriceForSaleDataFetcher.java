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
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AccompanyingPriceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Ancestor for all data fetchers computing price for sales. The main goal is to gather all needed data for computation
 * and correctly pass the price for sale down to other data fetchers.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public abstract class AbstractPriceForSaleDataFetcher<P> implements DataFetcher<DataFetcherResult<P>> {

	@Nonnull
	@Override
	public DataFetcherResult<P> get(DataFetchingEnvironment environment) throws Exception {
		final EntityDecorator entity = environment.getSource();
		if (entity == null) {
			throw new GraphQLInternalError("Missing entity");
		}
		final EntityQueryContext context = environment.getLocalContext();
		if (context == null) {
			throw new GraphQLInternalError("Missing content");
		}

		final String[] priceLists = resolveDesiredPricesLists(environment, entity);
		final Currency currency = resolveDesiredCurrency(environment, entity);
		final OffsetDateTime validIn = resolveDesiredValidIn(environment, entity);

		final P result;
		if (environment.getArguments().isEmpty()) {
			// default priceForSale computed by evita engine from resolved constraints
			result = computeDefaultPrices(entity);
		} else {
			// custom priceForSale based on specified user arguments on fields, doesn't use evita defaults
			final AccompanyingPrice[] desiredAccompanyingPrices = resolveDesiredAccompanyingPricesForCustomPriceForSale(environment);
			result = computePrices(entity, priceLists, currency, validIn, desiredAccompanyingPrices);
		}

		final Locale customLocale = environment.getArgument(PriceForSaleFieldHeaderDescriptor.LOCALE.name());
		final EntityQueryContext newContext = context.toBuilder()
			.desiredLocale(customLocale != null ? customLocale : context.getDesiredLocale())
			.build();

		return DataFetcherResult.<P>newResult()
			.data(result)
			.localContext(newContext)
			.build();
	}

	/**
	 * Computes actual default price for sale or all prices for sale based on request constraints.
	 *
	 * @param entity parent entity
	 * @return computed price for sale
	 */
	@Nullable
	protected abstract P computeDefaultPrices(@Nonnull EntityDecorator entity);

	/**
	 * Computes actual custom price for sale or all prices for sale based on user field arguments. Also should prefetch accompanying prices for nested price
	 * fields.
	 *
	 * @param entity parent entity
	 * @param desiredPriceLists desired price lists
	 * @param desiredCurrency desired currency
	 * @param desiredValidIn desired validity
	 * @param desiredAccompanyingPrices desired accompanying prices
	 * @return computed price for sale
	 */
	@Nullable
	protected abstract P computePrices(@Nonnull EntityDecorator entity,
	                                   @Nonnull String[] desiredPriceLists,
	                                   @Nonnull Currency desiredCurrency,
	                                   @Nullable OffsetDateTime desiredValidIn,
	                                   @Nonnull AccompanyingPrice[] desiredAccompanyingPrices);

	@Nonnull
	protected String[] resolveDesiredPricesLists(@Nonnull DataFetchingEnvironment environment,
												 @Nonnull EntityDecorator entity) {
		//noinspection unchecked
		return Optional.ofNullable((List<String>) environment.getArgument(PriceForSaleFieldHeaderDescriptor.PRICE_LISTS.name()))
			.map(it -> it.toArray(String[]::new))
			.or(() -> Optional.ofNullable((String) environment.getArgument(PriceForSaleFieldHeaderDescriptor.PRICE_LIST.name()))
				.map(priceList -> new String[]{ priceList }))
			.or(() -> Optional.ofNullable(entity.getPricePredicate().getPriceLists()))
			.orElseThrow(() -> new GraphQLInvalidArgumentException("Missing price list argument. You can use `" + PriceForSaleFieldHeaderDescriptor.PRICE_LIST.name() + "` or `" + PriceForSaleFieldHeaderDescriptor.PRICE_LISTS.name() + "` parameter for specifying custom price list."));
	}

	@Nonnull
	protected Currency resolveDesiredCurrency(@Nonnull DataFetchingEnvironment environment,
	                                          @Nonnull EntityDecorator entity) {
		return Optional.ofNullable((Currency) environment.getArgument(PriceForSaleFieldHeaderDescriptor.CURRENCY.name()))
			.or(() -> Optional.ofNullable(entity.getPricePredicate().getCurrency()))
			.orElseThrow(() -> new GraphQLInvalidArgumentException("Missing `currency` argument. You can use `" + PriceForSaleFieldHeaderDescriptor.CURRENCY.name() + "` parameter for specifying custom currency."));
	}

	@Nullable
	protected OffsetDateTime resolveDesiredValidIn(@Nonnull DataFetchingEnvironment environment,
	                                               @Nonnull EntityDecorator entity) {
		return Optional.ofNullable((OffsetDateTime) environment.getArgument(PriceForSaleFieldHeaderDescriptor.VALID_IN.name()))
			.or(() -> Optional.ofNullable((Boolean) environment.getArgument(PriceForSaleFieldHeaderDescriptor.VALID_NOW.name()))
				.map(validNow -> validNow ? entity.getAlignedNow() : null))
			.or(() -> Optional.ofNullable(entity.getPricePredicate().getValidIn()))
			.orElse(null);
	}

	@Nonnull
	protected AccompanyingPrice[] resolveDesiredAccompanyingPricesForCustomPriceForSale(@Nonnull DataFetchingEnvironment environment) {
		return SelectionSetAggregator.getImmediateFields(PriceForSaleDescriptor.ACCOMPANYING_PRICE.name(), environment.getSelectionSet())
			.stream()
			.map(f -> {
				final String priceName = f.getAlias() != null ? f.getAlias() : f.getName();

				//noinspection unchecked
				final List<String> priceLists = (List<String>) f.getArguments().get(AccompanyingPriceFieldHeaderDescriptor.PRICE_LISTS.name());
				if (priceLists == null || priceLists.isEmpty()) {
					throw new GraphQLInvalidArgumentException("Custom price for sale requires custom price lists for accompanying prices.");
				}
				return new AccompanyingPrice(priceName, priceLists.toArray(String[]::new));
			})
			.toArray(AccompanyingPrice[]::new);
	}
}
