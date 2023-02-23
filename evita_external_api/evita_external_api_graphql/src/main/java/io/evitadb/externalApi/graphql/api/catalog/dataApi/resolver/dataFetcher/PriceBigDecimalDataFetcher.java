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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.FormattableBigDecimal;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.PriceBigDecimal;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * Extension of {@link BigDecimalDataFetcher} for price values (currency formatting and so on).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class PriceBigDecimalDataFetcher extends BigDecimalDataFetcher {

    /**
     * Parameter specifying if price value should be formatted and the formatted string should contain currency on output
     * based on desired entity locale.
     */
    public static final String WITH_CURRENCY_PARAMETER = "withCurrency";

    public PriceBigDecimalDataFetcher(@Nonnull String propertyName) {
        super(propertyName);
    }

    @Override
    protected boolean shouldBeFormatted(@Nonnull DataFetchingEnvironment environment) {
        return super.shouldBeFormatted(environment) ||
            environment.getArgumentOrDefault(WITH_CURRENCY_PARAMETER, false);
    }

    @Nonnull
    @Override
    protected FormattableBigDecimal wrapBigDecimal(@Nonnull BigDecimal value,
                                                   @Nonnull DataFetchingEnvironment environment) {
        final EntityQueryContext context = environment.getLocalContext();
        final boolean withCurrency = environment.getArgumentOrDefault(WITH_CURRENCY_PARAMETER, false);
        return new PriceBigDecimal(
            value,
            context.getDesiredLocale(),
            withCurrency ? ((PriceContract) environment.getSource()).getCurrency() : null
        );
    }
}
