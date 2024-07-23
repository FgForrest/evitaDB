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

package io.evitadb.externalApi.rest.api.openApi;

import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.Nonnull;

/**
 * Represents any OpenAPI type (primitive, object, array, ...) which corresponds to {@link Schema} object in OpenAPI.
 * This is just building facade for the {@link Schema} and should be immutable.
 *
 * There 2 types of basic types:
 * <ul>
 *   <li>{@link OpenApiSimpleType}</li>
 *   <li>{@link OpenApiComplexType}</li>
 * </ul>
 *
 * Each specific type must implement one of these types.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface OpenApiType {

	/**
	 * Returns OpenAPI equivalent of this type. Should be used only when constructing final OpenAPI schema, not as
	 * intermediate DTO.
	 */
	@Nonnull
	Schema<?> toSchema();
}
