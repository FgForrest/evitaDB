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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies the raw-source rendering of associated-data text expressions without creating a server. */
@Tag(EXTERNAL_API)
@Tag(GRAPHQL)
@Tag(SCHEMA)
@Tag(FULLTEXT)
@DisplayName("GraphQL associated-data text expression output")
class AssociatedDataMutationTextExpressionDataFetcherTest {
	private static final String SOURCE = "$value.title";

	/**
	 * Supplies an associated-data schema and both mutations that carry a text expression.
	 *
	 * @return sources declaring the expression `$value.title`
	 */
	@Nonnull
	static Stream<Object> sourcesWithExpression() {
		return sources(ExpressionFactory.parse(SOURCE));
	}

	/**
	 * Supplies an associated-data schema and both mutations that declare no text expression.
	 *
	 * @return sources without an expression
	 */
	@Nonnull
	static Stream<Object> sourcesWithoutExpression() {
		return sources(null);
	}

	/** A client sends the rendered value back as mutation input, so it must be the source it would type. */
	@ParameterizedTest
	@MethodSource("sourcesWithExpression")
	@DisplayName("renders the declared expression as its raw source")
	void shouldRenderRawExpressionSource(@Nonnull Object source) {
		assertEquals(SOURCE, fetch(source));
	}

	/** A declaration without an expression reads as `null`, the value that means "no extraction". */
	@ParameterizedTest
	@MethodSource("sourcesWithoutExpression")
	@DisplayName("renders a missing expression as null")
	void shouldRenderMissingExpressionAsNull(@Nonnull Object source) {
		assertNull(fetch(source));
	}

	/** A source the fetcher is not registered for is a programming error and must not read as "no expression". */
	@Test
	@DisplayName("refuses an unsupported source")
	void shouldRefuseUnsupportedSource() {
		assertThrows(GraphQLQueryResolvingInternalError.class, () -> fetch("not a declaration"));
	}

	/**
	 * Builds one source per supported type, all declaring the same expression.
	 *
	 * @param expression the declared expression, or `null` for none
	 * @return a real schema, a create mutation and a set mutation
	 */
	@Nonnull
	private static Stream<Object> sources(@Nullable Expression expression) {
		return Stream.of(
			AssociatedDataSchema._internalBuild(
				"text", null, null, ComplexDataObject.class, true, true, Scope.DEFAULT_SCOPES, expression,
				ConflictResolutionOverride.INHERITED, FulltextAnalyzerSchemaContract.TEXT,
				AttributeSchemaContract.DEFAULT_LENGTH_PIVOT, AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			),
			new CreateAssociatedDataSchemaMutation(
				"text", null, null, ComplexDataObject.class, true, true, Scope.DEFAULT_SCOPES, expression,
				ConflictResolutionOverride.INHERITED, null, null, null
			),
			new SetAssociatedDataSchemaSearchableMutation("text", Scope.DEFAULT_SCOPES, expression, null, null, null)
		);
	}

	/**
	 * Runs the shared fetcher over the given source.
	 *
	 * @param source the object the selected field belongs to
	 * @return the rendered expression
	 */
	@Nullable
	private static String fetch(@Nonnull Object source) {
		final DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
			.source(source)
			.build();
		return AssociatedDataMutationTextExpressionDataFetcher.getInstance().get(environment);
	}
}
