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

package io.evitadb.externalApi.graphql.api.builder;

import graphql.schema.GraphQLSchema;

import javax.annotation.Nonnull;

/**
 * Builds whole final {@link graphql.schema.GraphQLSchema}. Actual building of parts of GraphQL schema can be delegated
 * to {@link PartialGraphQLSchemaBuilder}s but this builder must result in {@link graphql.schema.GraphQLSchema} instance.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class FinalGraphQLSchemaBuilder<C extends GraphQLSchemaBuildingContext> extends GraphQLSchemaBuilder<C> {

	protected FinalGraphQLSchemaBuilder(@Nonnull C graphQLSchemaBuildingCtx) {
		super(graphQLSchemaBuildingCtx);
	}

	/**
	 * Build whole GraphQL schema
	 */
	@Nonnull
	public abstract GraphQLSchema build();
}
