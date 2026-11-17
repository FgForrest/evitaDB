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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaSearchableMutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationConverter;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;

/**
 * Ancestor for all mutation resolver resolving {@link SchemaMutation}s
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class SchemaMutationConverter<M extends SchemaMutation> extends MutationConverter<M> {

	protected SchemaMutationConverter(@Nonnull MutationObjectMapper objectParser,
	                               @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	/**
	 * Parses the raw expression source supplied by an associated-data mutation input.
	 *
	 * @param input the mutation input to read the `textExpression` property from
	 * @return the parsed expression, or `null` when the input omits it
	 */
	@Nullable
	protected static Expression readTextExpression(@Nonnull Input input) {
		final String expression =
			input.getProperty(SetAssociatedDataSchemaSearchableMutationDescriptor.TEXT_EXPRESSION);
		return expression == null ? null : ExpressionFactory.parse(expression);
	}

	/**
	 * Writes an expression as raw source before reflection reaches it, because {@link Output} cannot serialize an
	 * {@link Expression}; the source parses back to the same expression on input. An absent expression writes nothing.
	 *
	 * @param expression the expression to write, or `null` to leave the property out
	 * @param output the mutation output receiving the source
	 */
	protected static void writeTextExpression(@Nullable Expression expression, @Nonnull Output output) {
		output.setProperty(
			SetAssociatedDataSchemaSearchableMutationDescriptor.TEXT_EXPRESSION,
			expression == null ? null : expression.toExpressionString()
		);
	}

	/**
	 * Writes a present decimal ranking weight, preserving omission separately from explicit zero.
	 *
	 * @param propertyName output property receiving the weight
	 * @param weight decimal ranking weight, or `null` to omit the property
	 * @param output mutation output receiving the value
	 */
	protected static void writeSearchWeight(
		@Nonnull String propertyName, @Nullable BigDecimal weight, @Nonnull Output output
	) {
		if (weight != null) {
			output.setProperty(propertyName, weight);
		}
	}
}
