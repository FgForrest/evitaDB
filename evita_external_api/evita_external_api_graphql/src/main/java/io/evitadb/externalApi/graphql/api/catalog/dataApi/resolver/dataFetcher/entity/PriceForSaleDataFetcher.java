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

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.PrefetchedPriceForSale;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Optional;

/**
 * Finds price for sale either by price predicate on fetched {@link EntityDecorator} or by explicitly set arguments.
 * If not explicit arguments are present, the ones from query are used otherwise these argument have higher priority.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PriceForSaleDataFetcher extends AbstractPriceForSaleDataFetcher<PriceContract> {

    @Nullable
    private static PriceForSaleDataFetcher INSTANCE;

    @Nonnull
    public static PriceForSaleDataFetcher getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PriceForSaleDataFetcher();
        }
        return INSTANCE;
    }

    @Nullable
    @Override
    protected PriceContract computeDefaultPrices(@Nonnull EntityDecorator entity) {
        return entity.getPriceForSaleWithAccompanyingPrices()
            .map(it -> new PrefetchedPriceForSale(it.priceForSale(), entity, it.accompanyingPrices()))
            .orElse(null);
    }

    @Nullable
    @Override
    protected PriceContract computePrices(@Nonnull EntityDecorator entity,
                                          @Nonnull String[] desiredPriceLists,
                                          @Nonnull Currency desiredCurrency,
                                          @Nullable OffsetDateTime desiredValidIn,
                                          @Nonnull AccompanyingPrice[] desiredAccompanyingPrices) {
        final Optional<PriceForSaleWithAccompanyingPrices> priceForSale = entity.getPriceForSaleWithAccompanyingPrices(
            desiredCurrency,
            desiredValidIn,
            desiredPriceLists,
            desiredAccompanyingPrices
        );

        return priceForSale
            .map(it -> new PrefetchedPriceForSale(it.priceForSale(), entity, it.accompanyingPrices()))
            .orElse(null);
    }
}
