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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AssociatedDataFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * Fetcher for associated data container. Mainly to gather desired locale for individual associated
 * data and pass it to resolution of individual associated data.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class AssociatedDataDataFetcher implements DataFetcher<DataFetcherResult<AssociatedDataContract>> {

    @Nonnull
    @Override
    public DataFetcherResult<AssociatedDataContract> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
        final EntityQueryContext context = environment.getLocalContext();
        final EntityDecorator entity = environment.getSource();
        Locale desiredLocale = environment.getArgumentOrDefault(AssociatedDataFieldHeaderDescriptor.LOCALE.name(), context.getDesiredLocale());
        if (desiredLocale == null) {
            // try implicit locale if no explicit locale was set
            desiredLocale = entity.getImplicitLocale();
        }
        return DataFetcherResult.<AssociatedDataContract>newResult()
            .data(environment.getSource()) // because entity implements AssociatedDataContract
            .localContext(context.toBuilder()
                .desiredLocale(desiredLocale)
                .build())
            .build();
    }
}
