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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;

import javax.annotation.Nonnull;

/**
 * Translates Java data type from schema to GraphQL equivalent.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class SchemaTypeDataFetcher implements DataFetcher<String> {

	@Nonnull
	@Override
	public String get(DataFetchingEnvironment environment) throws Exception {
		final Class<?> javaType = getJavaType(environment);
		final GraphQLType graphQLType = DataTypesConverter.getGraphQLScalarType(javaType);
		return resolveGraphQLTypeName(graphQLType);
	}


	@Nonnull
	private String resolveGraphQLTypeName(@Nonnull GraphQLType graphQLType) {
		if (graphQLType instanceof final GraphQLList graphQLList) {
			final String wrappedTypeName = resolveGraphQLTypeName(graphQLList.getWrappedType());
			return "[" + wrappedTypeName + "]";
		} else if (graphQLType instanceof final GraphQLNonNull graphQLNonNull) {
			final String wrappedTypeName = resolveGraphQLTypeName(graphQLNonNull.getWrappedType());
			return wrappedTypeName + "!";
		} else if (graphQLType instanceof final GraphQLScalarType graphQLScalarType) {
			return graphQLScalarType.getName();
		} else {
			throw new GraphQLQueryResolvingInternalError("Unsupported GraphQL type `" + graphQLType.getClass().getName() + "`.");
		}
	}

	@Nonnull
	protected abstract Class<?> getJavaType(@Nonnull DataFetchingEnvironment environment);
}
