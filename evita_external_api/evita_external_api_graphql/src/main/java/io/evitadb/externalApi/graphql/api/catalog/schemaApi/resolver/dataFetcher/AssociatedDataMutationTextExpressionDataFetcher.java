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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Renders associated-data schema and mutation expressions as raw source. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AssociatedDataMutationTextExpressionDataFetcher implements DataFetcher<String> {
	/** Shared fetcher for associated-data schema reads and for create and set mutation output. */
	@Nonnull
	@Getter private static final AssociatedDataMutationTextExpressionDataFetcher instance =
		new AssociatedDataMutationTextExpressionDataFetcher();

	/**
	 * Extracts the expression source without the display formatting `Expression.toString()` adds, so a client can
	 * send the value back as mutation input.
	 *
	 * @param environment selection carrying an associated-data schema or a create/set associated-data mutation
	 * @return the raw expression source, or `null` when the declaration has no expression
	 */
	@Nullable
	@Override
	public String get(@Nonnull DataFetchingEnvironment environment) {
		final Object source = environment.getSource();
		final Expression expression = switch (source) {
			case AssociatedDataSchemaContract schema -> schema.getTextExpression();
			case CreateAssociatedDataSchemaMutation mutation -> mutation.getTextExpression();
			case SetAssociatedDataSchemaSearchableMutation mutation -> mutation.getTextExpression();
			default -> throw new GraphQLQueryResolvingInternalError("Unsupported associated-data mutation: " + source);
		};
		return expression == null ? null : expression.toExpressionString();
	}
}
