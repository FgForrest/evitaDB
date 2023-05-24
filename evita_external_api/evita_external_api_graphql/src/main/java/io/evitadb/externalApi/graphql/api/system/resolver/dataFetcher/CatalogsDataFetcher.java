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

package io.evitadb.externalApi.graphql.api.system.resolver.dataFetcher;

import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.concurrent.Executor;

/**
 * Returns all catalog DTOs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CatalogsDataFetcher extends ReadDataFetcher<Collection<CatalogContract>> {

    @Nonnull private final Evita evita;

    public CatalogsDataFetcher(@Nonnull Executor executor, @Nonnull Evita evita) {
        super(executor);
        this.evita = evita;
    }

    @Override
    @Nonnull
    public Collection<CatalogContract> doGet(@Nonnull DataFetchingEnvironment environment) {
        return evita.getCatalogs();
    }
}
