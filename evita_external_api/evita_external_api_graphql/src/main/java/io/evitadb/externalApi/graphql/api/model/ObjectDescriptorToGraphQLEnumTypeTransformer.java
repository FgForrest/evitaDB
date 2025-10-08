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

package io.evitadb.externalApi.graphql.api.model;

import graphql.schema.GraphQLEnumType;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptorTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

/**
 * Transforms API-independent {@link ObjectDescriptor} to {@link GraphQLEnumType}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class ObjectDescriptorToGraphQLEnumTypeTransformer implements ObjectDescriptorTransformer<GraphQLEnumType.Builder> {

	/**
	 * Serialized enum values to as items.
	 */
	@Nonnull private final Set<Map.Entry<String, ?>> enumValues;

	@Override
	public GraphQLEnumType.Builder apply(ObjectDescriptor objectDescriptor) {
		final GraphQLEnumType.Builder enumBuilder = GraphQLEnumType.newEnum();

		if (objectDescriptor.isNameStatic()) {
			enumBuilder.name(objectDescriptor.name());
		}
		enumBuilder.description(objectDescriptor.description());

		this.enumValues.forEach(v -> enumBuilder.value(v.getKey(), v.getValue()));

		return enumBuilder;
	}
}
