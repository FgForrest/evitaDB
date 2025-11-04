/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.rest.api.openApi;

import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.Nonnull;

/**
 * Placeholder type for endpoints which wish to force no content response (e.g. WebSocket redirecting endpoints).
 *
 * Note: Must be transformed to OpenAPI schema specifically, cannot be transformed directly using {@link #toSchema()} as
 * it doesn't have any directly usable OpenAPI representation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class OpenApiVoid implements OpenApiSimpleType {

	public static final OpenApiVoid INSTANCE = new OpenApiVoid();

	@Nonnull
	@Override
	public Schema<?> toSchema() {
		throw new OpenApiBuildingError("Void type cannot be transformed to OpenAPI schema directly.");
	}
}
