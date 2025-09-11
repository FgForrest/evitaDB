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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds single price (even not indexed) for fetched entity and for specific price list.
 * Currency is used from entity. If more prices for used currency and price list are found, validIn datetime is used
 * to select the valid one at the moment. If not specified by user in entity, datetime of query execution start is used.
 * Expects entity to be {@link EntityDecorator} to hold needed price parameters (validIn, currency ...).
 *
 * @deprecated The entire `price` field on entities doesn't correctly return price according to price for sale and doesn't
 *      respect price inner record handling. Use `accompanyingPrice` fields within `priceForSale` field instead.
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
// TOBEDONE #538: deprecated, remove
@Deprecated(since = "2024.3", forRemoval = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PriceDataFetcher implements DataFetcher<DataFetcherResult<PriceContract>> {

    @Nullable
    private static PriceDataFetcher INSTANCE;

    @Nonnull
    public static PriceDataFetcher getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PriceDataFetcher();
        }
        return INSTANCE;
    }

    @Nonnull
    @Override
    public DataFetcherResult<PriceContract> get(DataFetchingEnvironment environment) throws Exception {
        final String priceList = environment.getArgument(PriceFieldHeaderDescriptor.PRICE_LIST.name());
        final Currency customCurrency = environment.getArgument(PriceFieldHeaderDescriptor.CURRENCY.name());
        final EntityQueryContext context = environment.getLocalContext();
        if (context == null) {
            throw new GraphQLInternalError("Missing context");
        }

        final EntityDecorator entity = environment.getSource();
        if (entity == null) {
            throw new GraphQLInternalError("Missing entity");
        }
        final Currency currency = Optional.ofNullable(customCurrency)
            .or(() -> Optional.ofNullable(entity.getPricePredicate().getCurrency()))
            .orElseThrow(() -> new GraphQLInvalidArgumentException("Missing `currency` argument. You can use `" + PriceFieldHeaderDescriptor.CURRENCY.name() + "` parameter for specifying custom currency."));
        final Collection<PriceContract> possiblePrices = entity.getPrices(currency, priceList);

        PriceContract pickedPrice = null;
        if (possiblePrices.size() == 1) {
            pickedPrice = possiblePrices.iterator().next();
        } else if (possiblePrices.size() > 1) {
            final OffsetDateTime priceValidIn = Optional.ofNullable(entity.getPricePredicate().getValidIn())
                .orElse(entity.getAlignedNow());

            pickedPrice = possiblePrices.stream()
                .filter(p -> p.validity() == null || p.validAt(priceValidIn))
                .findFirst()
                .orElse(null);
        }

        final Locale customLocale = environment.getArgument(PriceFieldHeaderDescriptor.LOCALE.name());
        final EntityQueryContext newContext = context.toBuilder()
            .desiredLocale(customLocale != null ? customLocale : context.getDesiredLocale())
            .build();

        return DataFetcherResult.<PriceContract>newResult()
            .data(pickedPrice)
            .localContext(newContext)
            .build();
    }
}
