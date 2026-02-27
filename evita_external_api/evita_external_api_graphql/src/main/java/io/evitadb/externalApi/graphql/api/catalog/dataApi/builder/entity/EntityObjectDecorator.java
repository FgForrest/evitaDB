/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity;

import graphql.schema.GraphQLObjectType;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntityObjectBuilder.EntityObjectVariant;

import javax.annotation.Nonnull;

/**
 * Interface for decorating GraphQL entity objects with additional fields, properties, or customizations.
 * Implementations of this interface may be used to extend or modify the structure of a GraphQL schema
 * during its construction phase, allowing for the dynamic addition of attributes or transformation
 * of entity objects based on predefined variants or specific business logic.
 */
public interface EntityObjectDecorator {

	/**
	 * Called at the start of schema building. Can be used to register common types.
	 */
	default void prepare() {
		// do nothing
	}

	/**
	 * Decorates the provided entity object with additional fields, properties, or customizations.
	 *
	 * @param collectionBuildingContext context for building the specific GraphQL schema for an entity collection
	 * @param variant the variant of the entity object to be decorated, defining its field structure and restrictions
	 * @param entityObjectName the name of the entity object being decorated
	 * @param entityObjectBuilder the builder instance for the GraphQL object to be customized
	 */
	void decorate(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull EntityObjectVariant variant,
		@Nonnull String entityObjectName,
		@Nonnull GraphQLObjectType.Builder entityObjectBuilder
	);
}
