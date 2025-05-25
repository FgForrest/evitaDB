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

package io.evitadb.externalApi.rest.api.catalog.resolver.mutation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementation of {@link MutationObjectParser} for REST-specific input data structure. It is excepted to receive
 * {@link com.fasterxml.jackson.databind.JsonNode} which is than converted to plain Java value or generic {@link java.util.Map}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class RestMutationObjectParser implements MutationObjectParser {

	@Nonnull
	private final ObjectMapper objectMapper;

	@Nullable
	@Override
	public Object parse(@Nullable Object inputMutationObject) {
		return this.objectMapper.convertValue(inputMutationObject, new TypeReference<>() {});
	}

	@Nullable
	@Override
	public Object serialize(@Nullable Object outputMutationObject) {
		return this.objectMapper.valueToTree(outputMutationObject);
	}
}
