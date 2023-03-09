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

package io.evitadb.externalApi.rest.api.dto;

import io.evitadb.externalApi.rest.io.handler.RestHandler;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.List;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
public record Rest(@Nonnull OpenAPI openApi, @Nonnull List<Endpoint> endpoints) {

	public record Endpoint(@Nonnull Path path,
	                       @Nonnull PathItem.HttpMethod method,
	                       @Nonnull RestHandler<?> handler) {}
}
