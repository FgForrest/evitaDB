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

package io.evitadb.externalApi.graphql.api;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.validation.QueryComplexityLimits;
import graphql.validation.ValidationError;
import graphql.validation.ValidationErrorClassification;
import graphql.validation.ValidationErrorType;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.graphql.api.system.SystemGraphQLBuilder;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SECURITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link QueryComplexityLimitsInstrumentation} applies the query complexity limits configured in
 * {@link GraphQLOptions} to the requests of a GraphQL instance - with and without the parsed document cache.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@DisplayName("GraphQL query complexity limits")
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SECURITY)
class QueryComplexityLimitsInstrumentationTest {

	/**
	 * Recursive schema in which a query of any depth can be written.
	 */
	private static final String SDL = "type Query { node: Node } type Node { child: Node name: String }";

	/**
	 * Builds the recursive schema whose every node returns a further node, so that deep queries execute too.
	 */
	@Nonnull
	private static GraphQLSchema createSchema() {
		return new SchemaGenerator().makeExecutableSchema(
			new SchemaParser().parse(SDL),
			RuntimeWiring.newRuntimeWiring()
				.type("Query", builder -> builder.dataFetcher("node", env -> Map.of()))
				.type("Node", builder -> builder
					.dataFetcher("child", env -> Map.of())
					.dataFetcher("name", env -> "node"))
				.build()
		);
	}

	/**
	 * Creates GraphQL options with the given limits, the document cache with the given budget and defaults for the
	 * rest.
	 */
	@Nonnull
	private static GraphQLOptions options(
		@Nullable Integer maxQueryDepth,
		@Nullable Integer maxQueryFieldsCount,
		long documentCacheSizeInBytes
	) {
		return new GraphQLOptions(
			null, ":5555", null, null, null, null, null, documentCacheSizeInBytes, maxQueryDepth, maxQueryFieldsCount,
			null
		);
	}

	/**
	 * Creates a query whose deepest field `name` lies at the given depth.
	 */
	@Nonnull
	private static String queryOfDepth(int depth) {
		final StringBuilder query = new StringBuilder(depth * 10 + 16);
		query.append("{ node {");
		for (int i = 2; i < depth; i++) {
			query.append(" child {");
		}
		query.append(" name");
		query.append(" }".repeat(depth - 1));
		query.append(" }");
		return query.toString();
	}

	/**
	 * Creates a query with exactly the given number of fields: `node` plus aliased `name` fields below it.
	 */
	@Nonnull
	private static String queryWithFields(int fieldsCount) {
		final StringBuilder query = new StringBuilder(fieldsCount * 12 + 16);
		query.append("{ node {");
		for (int i = 1; i < fieldsCount; i++) {
			query.append(" n").append(i).append(": name");
		}
		query.append(" } }");
		return query.toString();
	}

	/**
	 * Executes the query and returns the types of its validation errors.
	 */
	@Nonnull
	private static List<ValidationErrorClassification> execute(@Nonnull GraphQL graphQL, @Nonnull String query) {
		final ExecutionResult result = graphQL.execute(ExecutionInput.newExecutionInput(query).build());
		return result.getErrors()
			.stream()
			.map(QueryComplexityLimitsInstrumentationTest::toValidationErrorType)
			.toList();
	}

	/**
	 * Returns the validation error type of the error, failing the test on any other kind of error.
	 */
	@Nonnull
	private static ValidationErrorClassification toValidationErrorType(@Nonnull GraphQLError error) {
		assertTrue(error instanceof ValidationError, () -> "Unexpected error: " + error);
		return ((ValidationError) error).getValidationErrorType();
	}

	@Nested
	@DisplayName("Configuration")
	class ConfigurationTest {

		@Test
		@DisplayName("uses the graphql-java default limits when not configured")
		void shouldUseGraphQLJavaDefaultsWhenNotConfigured() {
			final GraphQLOptions options = new GraphQLOptions();
			assertEquals(QueryComplexityLimits.DEFAULT_MAX_DEPTH, options.getMaxQueryDepth());
			assertEquals(QueryComplexityLimits.DEFAULT_MAX_FIELDS_COUNT, options.getMaxQueryFieldsCount());

			final GraphQLOptions fromConfiguration = options(null, null, 0);
			assertEquals(QueryComplexityLimits.DEFAULT_MAX_DEPTH, fromConfiguration.getMaxQueryDepth());
			assertEquals(QueryComplexityLimits.DEFAULT_MAX_FIELDS_COUNT, fromConfiguration.getMaxQueryFieldsCount());
		}

		@Test
		@DisplayName("keeps explicitly configured limits")
		void shouldKeepExplicitLimits() {
			final GraphQLOptions options = options(7, 42, 0);
			assertEquals(7, options.getMaxQueryDepth());
			assertEquals(42, options.getMaxQueryFieldsCount());
		}

		@Test
		@DisplayName("rejects a non-positive limit")
		void shouldRejectNonPositiveLimit() {
			assertThrowsInvalidUsage(() -> options(0, null, 0));
			assertThrowsInvalidUsage(() -> options(-1, null, 0));
			assertThrowsInvalidUsage(() -> options(null, 0, 0));
			assertThrowsInvalidUsage(() -> options(null, -1, 0));
		}

		/**
		 * Asserts that the passed options construction fails with an invalid usage exception.
		 */
		private static void assertThrowsInvalidUsage(@Nonnull Runnable construction) {
			assertThrows(EvitaInvalidUsageException.class, construction::run);
		}
	}

	@Nested
	@DisplayName("Enforcement")
	class EnforcementTest {

		@Test
		@DisplayName("accepts a query exactly at the configured depth and rejects a deeper one")
		void shouldRejectQueryDeeperThanConfiguredLimit() {
			final GraphQL graphQL = new SystemGraphQLBuilder(createSchema()).build(options(5, null, 0));

			assertEquals(List.of(), execute(graphQL, queryOfDepth(5)));
			assertEquals(List.of(ValidationErrorType.MaxQueryDepthExceeded), execute(graphQL, queryOfDepth(6)));
		}

		@Test
		@DisplayName("accepts a query exactly at the configured field count and rejects a wider one")
		void shouldRejectQueryWithMoreFieldsThanConfiguredLimit() {
			final GraphQL graphQL = new SystemGraphQLBuilder(createSchema()).build(options(null, 10, 0));

			assertEquals(List.of(), execute(graphQL, queryWithFields(10)));
			assertEquals(List.of(ValidationErrorType.MaxQueryFieldsExceeded), execute(graphQL, queryWithFields(11)));
		}

		@Test
		@DisplayName("enforces the default depth limit when none is configured")
		void shouldEnforceDefaultDepthLimit() {
			final GraphQL graphQL = new SystemGraphQLBuilder(createSchema()).build(new GraphQLOptions());

			assertEquals(List.of(), execute(graphQL, queryOfDepth(QueryComplexityLimits.DEFAULT_MAX_DEPTH)));
			assertEquals(
				List.of(ValidationErrorType.MaxQueryDepthExceeded),
				execute(graphQL, queryOfDepth(QueryComplexityLimits.DEFAULT_MAX_DEPTH + 1))
			);
		}

		@Test
		@DisplayName("rejects a too deep query on every repetition when the document cache is enabled")
		void shouldRejectTooDeepQueryRepeatedlyWithDocumentCache() {
			final GraphQL graphQL = new SystemGraphQLBuilder(createSchema()).build(options(5, null, 1024 * 1024));
			assertTrue(graphQL.getPreparsedDocumentProvider() instanceof GraphQLDocumentCache);

			for (int i = 0; i < 3; i++) {
				assertEquals(List.of(), execute(graphQL, queryOfDepth(5)));
				assertEquals(List.of(ValidationErrorType.MaxQueryDepthExceeded), execute(graphQL, queryOfDepth(6)));
			}
		}
	}
}
