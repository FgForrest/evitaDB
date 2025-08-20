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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.PropertyDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.FormattableBigDecimal;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.BigDecimalFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Wrapper fetcher for formatting {@link BigDecimal} values. Every present {@link BigDecimal} wraps to {@link FormattableBigDecimal}
 * with format metadata which coercing can properly format for client.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class BigDecimalDataFetcher implements DataFetcher<FormattableBigDecimal> {

    private final DataFetcher<BigDecimal> delegate;

    /**
     * Uses {@link PropertyDataFetcher} as the underlying data fetcher to get the value.
     */
    public BigDecimalDataFetcher(@Nonnull String propertyName) {
        this.delegate = new PropertyDataFetcher<>(propertyName);
    }

    /**
     * Uses the passed data fetcher as underlying data fetcehr to get the value
     *
     * @param delegate data fetcher to get the original value
     */
    public BigDecimalDataFetcher(@Nonnull DataFetcher<BigDecimal> delegate) {
        this.delegate = delegate;
    }

    @Nullable
    @Override
    public FormattableBigDecimal get(DataFetchingEnvironment environment) throws Exception {
        return Optional.ofNullable(this.delegate.get(environment))
            .map(value -> {
                final boolean shouldBeFormatted = shouldBeFormatted(environment);
                if (!shouldBeFormatted) {
                    return new FormattableBigDecimal(value, null);
                }

                validateRequiredArguments(environment);
                return wrapBigDecimal(value, environment);
            })
            .orElse(null);
    }

    /**
     * @return whether found {@link BigDecimal} value should be formatted to human-readable format
     */
    protected boolean shouldBeFormatted(@Nonnull DataFetchingEnvironment environment) {
        return environment.getArgumentOrDefault(BigDecimalFieldHeaderDescriptor.FORMATTED.name(), false);
    }

    /**
     * Validates if there are all required arguments present when this BigDecimal value should be formatted.
     *
     * @param environment data fetcher environment
     */
    protected void validateRequiredArguments(@Nonnull DataFetchingEnvironment environment) {
        final Locale locale = Objects.requireNonNull((EntityQueryContext) environment.getLocalContext()).getDesiredLocale();
        Assert.notNull(
            locale,
            () -> {
                final String parentFieldName = environment.getExecutionStepInfo().getParent().getFieldDefinition().getName();
                return new GraphQLInvalidArgumentException(
                    "Missing specified locale for formatted BigDecimal value. Specify custom locale on parent field `" + parentFieldName + "` or in main query."
                );
            }
        );
    }

    /**
     * Wrap original {@link BigDecimal} value to {@link FormattableBigDecimal} wrapper if this BigDecimal value should be
     * formatted.
     *
     * @param value original value
     * @param environment fetcher environment
     * @return wrapped original value with formatting metadata
     */
    @Nonnull
    protected FormattableBigDecimal wrapBigDecimal(@Nonnull BigDecimal value,
                                                   @Nonnull DataFetchingEnvironment environment) {
        final Locale locale = Objects.requireNonNull((EntityQueryContext) environment.getLocalContext()).getDesiredLocale();
        return new FormattableBigDecimal(value, locale);
    }
}
