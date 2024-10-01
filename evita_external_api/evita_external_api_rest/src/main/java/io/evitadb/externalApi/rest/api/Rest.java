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

package io.evitadb.externalApi.rest.api;

import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.externalApi.rest.io.RestEndpointHandler;
import io.evitadb.externalApi.utils.UriPath;
import io.swagger.v3.oas.models.OpenAPI;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Represents final built REST API with its specs and handlers for registration into routers.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public record Rest(@Nonnull OpenAPI openApi, @Nonnull List<Endpoint> endpoints) {

	public record Endpoint(@Nonnull UriPath path,
	                       @Nonnull HttpMethod method,
	                       @Nonnull RestEndpointHandler<?> handler) {}
}
