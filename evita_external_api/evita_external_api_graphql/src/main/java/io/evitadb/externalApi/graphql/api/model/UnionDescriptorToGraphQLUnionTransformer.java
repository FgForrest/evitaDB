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

import graphql.schema.GraphQLTypeReference;
import graphql.schema.GraphQLUnionType;
import graphql.schema.GraphQLUnionType.Builder;
import io.evitadb.externalApi.api.model.UnionDescriptor;
import io.evitadb.externalApi.api.model.UnionDescriptorTransformer;
import lombok.RequiredArgsConstructor;

/**
 * Transforms API-independent {@link UnionDescriptor} to GraphQL union definition.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class UnionDescriptorToGraphQLUnionTransformer implements UnionDescriptorTransformer<Builder> {

	@Override
	public GraphQLUnionType.Builder apply(UnionDescriptor unionDescriptor) {
		final GraphQLUnionType.Builder unionBuilder = GraphQLUnionType.newUnionType();

		unionBuilder.name(unionDescriptor.name());
		if (unionDescriptor.description() != null) {
			unionBuilder.description(unionDescriptor.description());
		}

		unionDescriptor.types()
			.forEach(typeDescriptor -> unionBuilder.possibleType(GraphQLTypeReference.typeRef(typeDescriptor.name())));

		return unionBuilder;
	}
}
