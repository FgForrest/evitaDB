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

import javax.annotation.Nonnull;

/**
 * Complex type is {@link OpenApiComplexType} that is usually some kind of object and should be registered as component and referenced in
 * properties. Should not be used as inline schema.
 *
 * @see OpenApiObject
 * @see OpenApiEnum
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface OpenApiComplexType extends OpenApiType {

	/**
	 * Returns global name of complex type so that it can be globally registered and referenced. Must be unique in context
	 * of single OpenAPI specs.
	 */
	@Nonnull
	String getName();
}
