/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.PrefetchedPriceForSale;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds all prices for sale either by price predicate on fetched {@link EntityDecorator} or by explicitly set arguments.
 * If not explicit arguments are present, the ones from query are used otherwise these argument have higher priority.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class AllPricesForSaleDataFetcher extends AbstractPriceForSaleDataFetcher<List<PriceContract>> {

    @Nonnull
    @Override
    public DataFetcherResult<List<PriceContract>> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
        final EntityDecorator entity = environment.getSource();
        final EntityQueryContext context = environment.getLocalContext();

        final String[] priceLists = resolveDesiredPricesLists(environment, context);
        final Currency currency = resolveDesiredCurrency(environment, context);
        final OffsetDateTime validIn = resolveDesiredValidIn(environment, entity, context);
        final AccompanyingPrice[] desiredAccompanyingPrices = resolveDesiredAccompanyingPrices(environment);

        final Optional<List<PriceForSaleWithAccompanyingPrices>> priceForSale = entity.getAllPricesForSaleWithAccompanyingPrices(
            currency,
            validIn,
            priceLists,
            desiredAccompanyingPrices
        );

        final Locale customLocale = environment.getArgument(PriceForSaleFieldHeaderDescriptor.LOCALE.name());
        final EntityQueryContext newContext = context.toBuilder()
            .desiredPriceInPriceLists(priceLists)
            .desiredPriceInCurrency(currency)
            .desiredPriceValidIn(validIn)
            .desiredPriceValidInNow(false)
            .desiredLocale(customLocale != null ? customLocale : context.getDesiredLocale())
            .build();

        return DataFetcherResult.<PriceContract>newResult()
            .data(priceForSale
                .map(it -> new PrefetchedPriceForSale(it.priceForSale(), entity, it.accompanyingPrices()))
                .orElse(null))
            .localContext(newContext)
            .build();
    }
}
