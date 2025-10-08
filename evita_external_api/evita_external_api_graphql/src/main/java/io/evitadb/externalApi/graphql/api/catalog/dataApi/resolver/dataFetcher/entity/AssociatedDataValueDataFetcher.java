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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.ComplexDataObjectToJsonConverter;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/**
 * Finds single associated data from entity by name and possibly locale. The internal {@link ComplexDataObject} is
 * transformed to {@link JsonNode} and JSON tree is returned. Each associated data field should have its own fetcher
 * because each has different schema.
 *
 * @see AssociatedDataDataFetcher
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class AssociatedDataValueDataFetcher<T extends Serializable> implements DataFetcher<T> {

    @Nonnull
    private final ObjectMapper objectMapper;
    /**
     * Schema of associated data to which this fetcher is mapped to.
     */
    @Nonnull
    private final AssociatedDataSchemaContract associatedDataSchema;

    @Nullable
    @Override
    public T get(DataFetchingEnvironment environment) {
        final AssociatedDataContract associatedData = Objects.requireNonNull(environment.getSource());
        final Locale locale = Objects.requireNonNull((EntityQueryContext) environment.getLocalContext()).getDesiredLocale();

        final Serializable associatedDataValue;
        if (locale == null && this.associatedDataSchema.isLocalized()) {
            throw new GraphQLInvalidArgumentException(
                "Associated data '" + this.associatedDataSchema.getName() + "' is localized, yet no locale for associated data was specified."
            );
        } else if (locale == null) {
            associatedDataValue = associatedData.getAssociatedData(this.associatedDataSchema.getName());
        } else {
            associatedDataValue = associatedData.getAssociatedData(this.associatedDataSchema.getName(), locale);
        }

        if (associatedDataValue == null) {
            return null;
        }
        if (!(associatedDataValue instanceof ComplexDataObject)) {
            // if it's not CDO it is simple Evita data type
            //noinspection unchecked
            return (T) associatedDataValue;
        }
        //noinspection unchecked
        return (T) convertComplexDataObjectToJson((ComplexDataObject) associatedDataValue);
    }

    private JsonNode convertComplexDataObjectToJson(@Nonnull ComplexDataObject associatedDataValue) {
        final ComplexDataObjectToJsonConverter converter = new ComplexDataObjectToJsonConverter(this.objectMapper);
        associatedDataValue.accept(converter);
        return converter.getRootNode();
    }
}
