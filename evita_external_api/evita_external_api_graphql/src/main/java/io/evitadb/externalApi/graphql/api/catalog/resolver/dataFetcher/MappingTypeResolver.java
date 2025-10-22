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

package io.evitadb.externalApi.graphql.api.catalog.resolver.dataFetcher;

import graphql.schema.GraphQLObjectType;
import graphql.schema.TypeResolver;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public abstract class MappingTypeResolver<K> implements TypeResolver {

	@Nonnull
	private final Map<K, GraphQLObjectType> mapping;

	protected MappingTypeResolver(int expectedSize) {
		this.mapping = createHashMap(expectedSize);
	}

	public void registerTypeMapping(
		@Nonnull K key,
		@Nonnull GraphQLObjectType outputType
	) {
		this.mapping.put(key, outputType);
	}

	@Nonnull
	protected GraphQLObjectType getOutputType(@Nonnull K key) {
		return Optional.ofNullable(this.mapping.get(key))
			.orElseThrow(
				() -> new GraphQLQueryResolvingInternalError("Missing output type for key `" + key + "`."));
	}
}
