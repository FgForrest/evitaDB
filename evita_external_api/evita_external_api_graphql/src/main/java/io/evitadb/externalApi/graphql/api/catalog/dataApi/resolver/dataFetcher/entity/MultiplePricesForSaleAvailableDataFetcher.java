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

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Finds out whether there are multiple unique prices which the entity could be sold for.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class MultiplePricesForSaleAvailableDataFetcher implements DataFetcher<Boolean> {

    @Nonnull
    @Override
    public Boolean get(@Nonnull DataFetchingEnvironment environment) throws Exception {
        final EntityDecorator entity = environment.getSource();
        final EntityQueryContext context = environment.getLocalContext();

        final List<PriceContract> allPricesForSale = entity.getAllPricesForSale();
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
        } else if (priceInnerRecordHandling.equals(PriceInnerRecordHandling.SUM)) {
            hasMultiplePricesForSale = allPricesForSale.size() > 1;
        } else {
            hasMultiplePricesForSale = false;
        }

        return hasMultiplePricesForSale;
    }
}
