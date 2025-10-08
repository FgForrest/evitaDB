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
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/**
 * Finds single attribute value from entity by name and possibly locale. Each attribute field should have its own fetcher
 * because each attribute has its own schema.
 *
 * @see AttributesDataFetcher
 * @param <T> type of found value
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class AttributeValueDataFetcher<T extends Serializable> implements DataFetcher<T> {

    /**
     * Schema of attribute to which this fetcher is mapped to.
     */
    @Nonnull
    private final AttributeSchemaContract attributeSchema;

    @Nullable
    @Override
    public T get(@Nonnull DataFetchingEnvironment environment) throws Exception {
        final AttributesContract<?> attributes = environment.getSource();
        final Locale locale = ((EntityQueryContext) environment.getLocalContext()).getDesiredLocale();

        if (locale == null && this.attributeSchema.isLocalized()) {
            throw new GraphQLInvalidArgumentException(
                "Attribute `" + this.attributeSchema.getName() + "` is localized, yet no locale for attributes was specified."
            );
        } else if (locale == null) {
            //noinspection unchecked
            return attributes.getAttributeValue(this.attributeSchema.getName())
                .map(a -> (T) a.value())
                .orElse(null);
        } else {
            //noinspection unchecked
            return attributes.getAttributeValue(this.attributeSchema.getName(), locale)
                .map(a -> (T) a.value())
                .orElse(null);
        }
    }
}
