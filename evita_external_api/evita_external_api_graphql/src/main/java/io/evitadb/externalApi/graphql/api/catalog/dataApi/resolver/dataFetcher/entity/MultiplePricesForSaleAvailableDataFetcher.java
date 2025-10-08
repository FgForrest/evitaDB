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

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.MultiplePricesForSaleAvailableFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/**
 * Finds out whether there are multiple unique prices which the entity could be sold for.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MultiplePricesForSaleAvailableDataFetcher implements DataFetcher<Boolean> {

    @Nullable
    private static MultiplePricesForSaleAvailableDataFetcher INSTANCE;

    @Nonnull
    public static MultiplePricesForSaleAvailableDataFetcher getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new MultiplePricesForSaleAvailableDataFetcher();
        }
        return INSTANCE;
    }

    @Nonnull
    @Override
    public Boolean get(DataFetchingEnvironment environment) throws Exception {
        final EntityDecorator entity = environment.getSource();
        if (entity == null) {
            throw new GraphQLInternalError("Missing entity");
        }
        final EntityQueryContext context = environment.getLocalContext();
        if (context == null) {
            throw new GraphQLInternalError("Missing context");
        }

        final String[] priceLists = resolveDesiredPricesLists(environment, entity);
        final Currency currency = resolveDesiredCurrency(environment, entity);
        final OffsetDateTime validIn = resolveDesiredValidIn(environment, entity);

        final List<PriceContract> allPricesForSale = entity.getAllPricesForSale(currency, validIn, priceLists);
        if (allPricesForSale.size() <= 1) {
            return false;
        }

        final boolean hasMultiplePricesForSale;
        final PriceInnerRecordHandling priceInnerRecordHandling = entity.getPriceInnerRecordHandling();
        if (priceInnerRecordHandling.equals(PriceInnerRecordHandling.LOWEST_PRICE)) {
            if (allPricesForSale.size() <= 1) {
                return false;
            }

            final QueryPriceMode desiredPriceType = entity.getPricePredicate().getQueryPriceMode();
            final long uniquePriceValuesCount = allPricesForSale.stream()
                .map(price -> {
                    if (desiredPriceType.equals(QueryPriceMode.WITH_TAX)) {
                        return price.priceWithTax();
                    } else if (desiredPriceType.equals(QueryPriceMode.WITHOUT_TAX)) {
                        return price.priceWithoutTax();
                    } else {
                        throw new GraphQLInternalError("Unsupported price type `" + desiredPriceType + "`");
                    }
                })
                .distinct()
                .count();
            hasMultiplePricesForSale = uniquePriceValuesCount > 1;
        } else {
            hasMultiplePricesForSale = false;
        }

        return hasMultiplePricesForSale;
    }

    @Nonnull
    protected String[] resolveDesiredPricesLists(@Nonnull DataFetchingEnvironment environment,
                                                 @Nonnull EntityDecorator entity) {
        //noinspection unchecked
        return Optional.ofNullable((List<String>) environment.getArgument(MultiplePricesForSaleAvailableFieldHeaderDescriptor.PRICE_LISTS.name()))
            .map(it -> it.toArray(String[]::new))
            .or(() -> Optional.ofNullable(entity.getPricePredicate().getPriceLists()))
            .orElseThrow(() -> new GraphQLInvalidArgumentException("Missing price list argument. You can use `" + MultiplePricesForSaleAvailableFieldHeaderDescriptor.PRICE_LISTS.name() + "` parameter for specifying custom price list."));
    }

    @Nonnull
    protected Currency resolveDesiredCurrency(@Nonnull DataFetchingEnvironment environment,
                                              @Nonnull EntityDecorator entity) {
        return Optional.ofNullable((Currency) environment.getArgument(MultiplePricesForSaleAvailableFieldHeaderDescriptor.CURRENCY.name()))
            .or(() -> Optional.ofNullable(entity.getPricePredicate().getCurrency()))
            .orElseThrow(() -> new GraphQLInvalidArgumentException("Missing `currency` argument. You can use `" + MultiplePricesForSaleAvailableFieldHeaderDescriptor.CURRENCY.name() + "` parameter for specifying custom currency."));
    }

    @Nullable
    protected OffsetDateTime resolveDesiredValidIn(@Nonnull DataFetchingEnvironment environment,
                                                   @Nonnull EntityDecorator entity) {
        return Optional.ofNullable((OffsetDateTime) environment.getArgument(MultiplePricesForSaleAvailableFieldHeaderDescriptor.VALID_IN.name()))
            .or(() -> Optional.ofNullable((Boolean) environment.getArgument(MultiplePricesForSaleAvailableFieldHeaderDescriptor.VALID_NOW.name()))
                .map(validNow -> validNow ? entity.getAlignedNow() : null))
            .or(() -> Optional.ofNullable(entity.getPricePredicate().getValidIn()))
            .orElse(null);
    }
}
