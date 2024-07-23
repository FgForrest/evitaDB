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

import java.util.List;

/**
 * In case an {@link OpenApiObject} is a union of other {@link OpenApiSimpleType}s, this enum specifies the relationship
 * between these types.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public enum OpenApiObjectUnionType {

	/**
	 * States there will be only one type of defined list. Corresponds to {@link io.swagger.v3.oas.models.media.Schema#oneOf(List)}.
	 */
	ONE_OF,
	/**
	 * States there will be any number of types from defined list. Corresponds to {@link io.swagger.v3.oas.models.media.Schema#anyOf(List)}.
	 */
	ANY_OF,
	/**
	 * States there will be all types merged from defined list. Corresponds to {@link io.swagger.v3.oas.models.media.Schema#allOf(List)}.
	 */
	ALL_OF
}
