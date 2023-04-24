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

package io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.api.system.model.CreateCatalogMutationHeaderDescriptor;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Returns single catalog dto by name.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class CreateCatalogMutatingDataFetcher implements DataFetcher<DataFetcherResult<CatalogContract>> {

    private final Evita evita;

    @Nonnull
    @Override
    public DataFetcherResult<CatalogContract> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
        final String catalogName = environment.getArgument(CreateCatalogMutationHeaderDescriptor.NAME.name());

        evita.defineCatalog(catalogName);
        final CatalogContract newCatalog = evita.getCatalogInstanceOrThrowException(catalogName);
        // we don't have a way to use benefits of warming up state in GQL
        newCatalog.goLive();

        return DataFetcherResult.<CatalogContract>newResult()
            .data(newCatalog)
            .build();
    }
}
