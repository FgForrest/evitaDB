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
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

/**
 * Finds entity types of available entity collections.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CollectionsDataFetcher implements DataFetcher<Set<String>>, ReadDataFetcher {

    @Nullable
    private static CollectionsDataFetcher INSTANCE;

    @Nonnull
    public static CollectionsDataFetcher getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CollectionsDataFetcher();
        }
        return INSTANCE;
    }

    @Nonnull
    @Override
    public Set<String> get(DataFetchingEnvironment environment) {
        final ExecutedEvent requestExecutedEvent = environment.getGraphQlContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);

        final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
        return requestExecutedEvent.measureInternalEvitaDBExecution(evitaSession::getAllEntityTypes);
    }
}
