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

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptorTransformer;
import io.evitadb.externalApi.api.model.PropertyDescriptorTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Transforms API-independent {@link ObjectDescriptor} to GraphQL object definition.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class ObjectDescriptorToGraphQLInterfaceTransformer implements ObjectDescriptorTransformer<GraphQLInterfaceType.Builder> {

	@Nonnull
	private final PropertyDescriptorTransformer<GraphQLFieldDefinition.Builder> fieldBuilderTransformer;

	@Override
	public GraphQLInterfaceType.Builder apply(ObjectDescriptor objectDescriptor) {
		final GraphQLInterfaceType.Builder interfaceBuilder = GraphQLInterfaceType.newInterface()
			.name(objectDescriptor.name())
			.description(objectDescriptor.description());

		objectDescriptor.staticProperties().stream()
			.map(this.fieldBuilderTransformer)
			.forEach(interfaceBuilder::field);

		return interfaceBuilder;
	}
}
