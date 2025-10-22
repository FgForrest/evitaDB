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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.TypeResolutionEnvironment;
import graphql.schema.GraphQLObjectType;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.externalApi.graphql.api.catalog.resolver.dataFetcher.MappingTypeResolver;

import javax.annotation.Nonnull;

/**
 * Resolve specific entity DTO for entity interface based on fetched original entity object.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class EntityDtoTypeResolver extends MappingTypeResolver<String> {

	public EntityDtoTypeResolver(int collectionSize) {
		super(collectionSize);
	}

	@Nonnull
	@Override
	public GraphQLObjectType getType(TypeResolutionEnvironment env) {
		final EntityClassifier entity = env.getObject();
		return getOutputType(entity.getType());
	}
}
