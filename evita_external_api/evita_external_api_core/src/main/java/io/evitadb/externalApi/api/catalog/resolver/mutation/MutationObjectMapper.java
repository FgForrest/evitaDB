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

package io.evitadb.externalApi.api.catalog.resolver.mutation;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Parses input data structure specific to API into common Java structures needed by {@link MutationConverter}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface MutationObjectMapper {

	/**
	 * Parses raw object in API's representation representing mutation. It can be nested object or single value (e.g. boolean).
	 * This object must be parsed into Java primitive type or generic {@link Map} representing nested object.
	 */
	@Nullable
	Object parse(@Nullable Object inputMutationObject);

	/**
	 * Serializes Java object representing mutation into API's specific representation. It can be nested object or single value (e.g. boolean).
	 * This object must be serialized from Java primitive type or generic {@link Map} representing nested object into API's format.
	 */
	@Nullable
	Object serialize(@Nullable Object outputMutationObject);
}
