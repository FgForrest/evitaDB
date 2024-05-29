/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.graphql.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.http.JsonApiExceptionHandler;
import io.undertow.server.HttpHandler;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * Handles exception that occurred in GraphQL API outside of actual GraphQL execution.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GraphQLExceptionHandler extends JsonApiExceptionHandler {

    public GraphQLExceptionHandler(@Nonnull ObjectMapper objectMapper, @Nonnull HttpHandler next) {
        super(objectMapper, next);
    }

    @Nonnull
    @Override
    protected String getExternalApiCode() {
        return GraphQLProvider.CODE;
    }
}
