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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.dto;

import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Extension of {@link FormattableBigDecimal} for price values with additional formatting options (currency...).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Value
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class PriceBigDecimal extends FormattableBigDecimal {

    /**
     * If present, original value will be formatted and decorated with currency code.
     * Note: {@link #getFormatLocale()} must be specified as well, if currency is present
     */
    Currency currency;

    public PriceBigDecimal(@Nonnull BigDecimal value,
                           @Nullable Locale formatLocale,
                           @Nullable Currency currency) {
        super(value, formatLocale);
        this.currency = currency;

        if (currency != null && formatLocale == null) {
            throw new GraphQLInvalidArgumentException("Price should be formatted with currency but no format locale is specified.");
        }
    }

    /**
     * @return whether formatted value should contain currency symbol
     */
    public boolean isWithCurrency() {
        return this.currency != null;
    }

    @Override
    public boolean isShouldFormat() {
        return super.isShouldFormat() || isWithCurrency();
    }

    @Nonnull
    @Override
    public String toFormattedString() {
        if (!isWithCurrency()) {
            return super.toFormattedString();
        }

        final NumberFormat numberFormat = NumberFormat.getCurrencyInstance(getFormatLocale());
        numberFormat.setCurrency(getCurrency());
        return numberFormat.format(getValue());
    }
}
