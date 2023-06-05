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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds price for sale either by price predicate on fetched {@link EntityDecorator} or by explicitly set arguments.
 * If not explicit arguments are present, the ones from query are used otherwise these argument have higher priority.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class PriceForSaleDataFetcher implements DataFetcher<DataFetcherResult<PriceContract>> {

    @Nonnull
    @Override
    public DataFetcherResult<PriceContract> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
        final EntityDecorator entity = environment.getSource();
        final EntityQueryContext context = environment.getLocalContext();

        final String[] priceLists = Optional.ofNullable((String) environment.getArgument(PriceForSaleFieldHeaderDescriptor.PRICE_LIST.name()))
            .map(priceList -> new String[] { priceList })
            .or(() -> Optional.ofNullable(context.getDesiredPriceInPriceLists()))
            .orElseThrow(() -> new GraphQLInvalidArgumentException("Missing `priceList` argument. You can use `" + PriceForSaleFieldHeaderDescriptor.PRICE_LIST.name() + "` parameter for specifying custom price list."));

        final Currency currency = Optional.ofNullable((Currency) environment.getArgument(PriceForSaleFieldHeaderDescriptor.CURRENCY.name()))
            .or(() -> Optional.ofNullable(context.getDesiredPriceInCurrency()))
            .orElseThrow(() -> new GraphQLInvalidArgumentException("Missing `currency` argument. You can use `" + PriceForSaleFieldHeaderDescriptor.CURRENCY.name() + "` parameter for specifying custom currency."));

        final OffsetDateTime validIn = Optional.ofNullable((OffsetDateTime) environment.getArgument(PriceForSaleFieldHeaderDescriptor.VALID_IN.name()))
            .or(() -> Optional.ofNullable((Boolean) environment.getArgument(PriceForSaleFieldHeaderDescriptor.VALID_NOW.name()))
                .map(validNow -> validNow ? entity.getAlignedNow() : null))
            .or(() -> Optional.ofNullable(context.getDesiredPriceValidIn()))
            .or(() -> Optional.of(context.isDesiredpriceValidInNow())
                .map(validNow -> validNow ? entity.getAlignedNow() : null))
            .orElse(null);

        final Optional<PriceContract> priceForSale = entity.getPriceForSale(currency, validIn, priceLists);

        final Locale customLocale = environment.getArgument(PriceForSaleFieldHeaderDescriptor.LOCALE.name());
        final EntityQueryContext newContext = context.toBuilder()
            .desiredLocale(customLocale != null ? customLocale : context.getDesiredLocale())
            .build();

        return DataFetcherResult.<PriceContract>newResult()
            .data(priceForSale.orElse(null))
            .localContext(newContext)
            .build();
    }
}
