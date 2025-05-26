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
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PricesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Finds all entity prices (even not indexed).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PricesDataFetcher implements DataFetcher<DataFetcherResult<Collection<PriceContract>>> {

    @Nullable
    private static PricesDataFetcher INSTANCE;

    @Nonnull
    public static PricesDataFetcher getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PricesDataFetcher();
        }
        return INSTANCE;
    }

    @Nonnull
    @Override
    public DataFetcherResult<Collection<PriceContract>> get(DataFetchingEnvironment environment) throws Exception {
        final List<String> priceLists = environment.getArgument(PricesFieldHeaderDescriptor.PRICE_LISTS.name());
        final Currency currency = environment.getArgument(PricesFieldHeaderDescriptor.CURRENCY.name());

        final EntityDecorator entity = environment.getSource();
        final Collection<PriceContract> prices;
        if (priceLists == null && currency == null) {
            prices = entity.getPrices();
        } else if (priceLists != null && currency != null) {
            prices = priceLists.stream()
                .flatMap(pl -> entity.getPrices(currency, pl).stream())
                .toList();
        } else if (priceLists != null) {
            prices = priceLists.stream()
                .flatMap(pl -> entity.getPrices(pl).stream())
                .toList();
        } else {
            prices = entity.getPrices(currency);
        }

        final Locale customLocale = environment.getArgument(PricesFieldHeaderDescriptor.LOCALE.name());
        final EntityQueryContext context = environment.getLocalContext();
        final EntityQueryContext newContext = context.toBuilder()
            .desiredLocale(customLocale != null ? customLocale : context.getDesiredLocale())
            .build();

        return DataFetcherResult.<Collection<PriceContract>>newResult()
            .data(prices)
            .localContext(newContext)
            .build();
    }
}
