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
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AssociatedDataFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;

/**
 * Fetcher for associated data container. Mainly to gather desired locale for individual associated
 * data and pass it to resolution of individual associated data.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AssociatedDataDataFetcher implements DataFetcher<DataFetcherResult<AssociatedDataContract>> {

    @Nullable
    private static AssociatedDataDataFetcher INSTANCE;

    @Nonnull
    public static AssociatedDataDataFetcher getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AssociatedDataDataFetcher();
        }
        return INSTANCE;
    }

    @Nonnull
    @Override
    public DataFetcherResult<AssociatedDataContract> get(DataFetchingEnvironment environment) throws Exception {
        final EntityQueryContext context = Objects.requireNonNull(environment.getLocalContext());
        final AssociatedDataContract associatedData = Objects.requireNonNull(environment.getSource()); // because entity implements AssociatedDataContract

        final Locale customLocale = environment.getArgument(AssociatedDataFieldHeaderDescriptor.LOCALE.name());
        if (customLocale != null && !associatedData.getAssociatedDataLocales().contains(customLocale)) {
            // This entity doesn't have associated data for given custom locale, so we don't want to try to fetch individual
            // associated data. It would be pointless as there are no associated data and would result in GQL error because
            // some associated data may be non-nullable
            return DataFetcherResult.<AssociatedDataContract>newResult().build();
        }

        Locale desiredLocale = environment.getArgumentOrDefault(AssociatedDataFieldHeaderDescriptor.LOCALE.name(), context.getDesiredLocale());
        if (desiredLocale == null) {
            // try implicit locale if no explicit locale was set
            if (associatedData instanceof final EntityDecorator entity) {
                desiredLocale = entity.getImplicitLocale();
            } else {
                throw new GraphQLInternalError("Unsupported source `" + associatedData.getClass().getName() + "`.");
            }
        }
        return DataFetcherResult.<AssociatedDataContract>newResult()
            .data(associatedData)
            .localContext(context.toBuilder()
                .desiredLocale(desiredLocale)
                .build())
            .build();
    }
}
