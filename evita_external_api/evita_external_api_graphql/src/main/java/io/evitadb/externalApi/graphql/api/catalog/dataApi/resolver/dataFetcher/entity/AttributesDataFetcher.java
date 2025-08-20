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
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AttributesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;

/**
 * Fetcher for attributes container. Mainly to gather desired locale for individual attributes and
 * pass it to resolution of individual attributes.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AttributesDataFetcher implements DataFetcher<DataFetcherResult<AttributesContract<?>>> {

    @Nullable
    private static AttributesDataFetcher INSTANCE;

    @Nonnull
    public static AttributesDataFetcher getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AttributesDataFetcher();
        }
        return INSTANCE;
    }

    @Nonnull
    @Override
    public DataFetcherResult<AttributesContract<?>> get(DataFetchingEnvironment environment) throws Exception {
        final EntityQueryContext context = environment.getLocalContext();
        final AttributesContract<?> attributes = environment.getSource(); // because entity implements AttributesContract
        if (attributes == null) {
            throw new GraphQLInternalError("Missing attributes");
        }

        final Locale customLocale = environment.getArgument(AttributesFieldHeaderDescriptor.LOCALE.name());
        if (customLocale != null && !attributes.getAttributeLocales().contains(customLocale)) {
            // This entity doesn't have attributes for given custom locale, so we don't want to try to fetch individual
            // attributes. It would be pointless as there are no attributes and would result in GQL error because some attributes
            // may be non-nullable
            return DataFetcherResult.<AttributesContract<?>>newResult().build();
        }

        Locale desiredLocale = environment.getArgumentOrDefault(AttributesFieldHeaderDescriptor.LOCALE.name(), context.getDesiredLocale());
        if (desiredLocale == null) {
            // try implicit locale if no explicit locale was set
            if (attributes instanceof final EntityDecorator entity) {
                desiredLocale = entity.getImplicitLocale();
            } else if (attributes instanceof final ReferenceDecorator reference) {
                desiredLocale = reference.getAttributePredicate().getImplicitLocale();
            } else {
                throw new GraphQLInternalError("Unsupported source `" + attributes.getClass().getName() + "`.");
            }
        }
        return DataFetcherResult.<AttributesContract<?>>newResult()
            .data(attributes)
            .localContext(context.toBuilder()
                .desiredLocale(desiredLocale)
                .build())
            .build();
    }
}
