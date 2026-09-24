/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import graphql.TypeResolutionEnvironment;
import graphql.schema.GraphQLObjectType;
import graphql.schema.TypeResolver;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Tells the two members of a parent-chain union apart. An ancestor whose requested body was materialized arrives as
 * a {@link SealedEntity} and is reported as the entity object; anything else is the bodyless pointer the `COMPLETE`
 * parents behaviour substitutes for a body that could not be materialized.
 *
 * The distinction cannot be made on the entity type, because an ancestor and the pointer standing in for it report
 * the very same one - which is why the union carries no discriminator field.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ParentUnionTypeResolver implements TypeResolver {

	/**
	 * Name of the object type reporting an ancestor together with the body that was asked for.
	 */
	@Nonnull private final String entityObjectName;
	/**
	 * Name of the object type reporting an ancestor whose requested body could not be materialized.
	 */
	@Nonnull private final String parentPointerObjectName;

	/**
	 * Maps one element of a fetched parent chain onto the union member reporting it: the entity object for
	 * a {@link SealedEntity}, the parent pointer object for anything else.
	 *
	 * @param env the resolution environment carrying the chain element to be typed
	 * @return the object type the element is reported through
	 * @throws GraphQLQueryResolvingInternalError when the schema does not hold the object type the chain element maps
	 *                                            to
	 */
	@Nonnull
	@Override
	public GraphQLObjectType getType(TypeResolutionEnvironment env) {
		final String targetObjectName = env.getObject() instanceof SealedEntity
			? this.entityObjectName
			: this.parentPointerObjectName;
		final GraphQLObjectType targetObject = env.getSchema().getObjectType(targetObjectName);
		if (targetObject == null) {
			throw new GraphQLQueryResolvingInternalError(
				"Missing object type `" + targetObjectName + "` for a parent chain element.");
		}
		return targetObject;
	}
}
