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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.validation.ValidationError;
import graphql.validation.ValidationErrorType;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.api.catalog.CatalogGraphQLBuilder;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLEndpointFunctionalTest;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SECURITY;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the query complexity limits configured in {@link GraphQLOptions} protect the catalog GraphQL API -
 * the instances built by {@link CatalogGraphQLBuilder} and the requests served over HTTP.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@DisplayName("GraphQL query complexity limits - catalog API")
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SECURITY)
public class CatalogGraphQLQueryComplexityLimitsFunctionalTest extends GraphQLEndpointFunctionalTest {

	/**
	 * Number of fragment levels in {@link #createFieldBombDocument()}, each doubling the field count of the previous
	 * one - 2^17 = 131,072 fields, above the default limit of 100,000.
	 */
	private static final int FIELD_BOMB_LEVELS = 17;

	/**
	 * Creates a short document that expands to more fields than the default field count limit allows: every
	 * fragment spreads the previous one twice.
	 */
	@Nonnull
	private static String createFieldBombDocument() {
		final StringBuilder document = new StringBuilder(64 * FIELD_BOMB_LEVELS);
		document.append("query { ...f").append(FIELD_BOMB_LEVELS).append(" }\n");
		document.append("fragment f0 on Query { __typename }\n");
		for (int i = 1; i <= FIELD_BOMB_LEVELS; i++) {
			document.append("fragment f").append(i).append(" on Query { ...f").append(i - 1)
				.append(" ...f").append(i - 1).append(" }\n");
		}
		return document.toString();
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should apply the configured limits to catalog GraphQL instances")
	void shouldApplyConfiguredLimitsToCatalogInstances(Evita evita) {
		final GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(
			new SchemaParser().parse("type Query { node: Node } type Node { child: Node name: String }"),
			RuntimeWiring.newRuntimeWiring()
				.type("Query", builder -> builder.dataFetcher("node", env -> Map.of()))
				.type("Node", builder -> builder.dataFetcher("child", env -> Map.of()))
				.build()
		);
		final GraphQL graphQL = new CatalogGraphQLBuilder(
			evita, evita.getCatalogInstanceOrThrowException(TEST_CATALOG), schema, new ObjectMapper()
		).build(new GraphQLOptions(null, ":5555", null, null, null, null, null, null, 2, null, null));

		final List<GraphQLError> errors = graphQL
			.execute(ExecutionInput.newExecutionInput("{ node { child { name } } }").build())
			.getErrors();

		assertEquals(1, errors.size(), () -> "Unexpected errors: " + errors);
		assertEquals(
			ValidationErrorType.MaxQueryDepthExceeded,
			((ValidationError) errors.get(0)).getValidationErrorType()
		);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should reject a query expanding to more fields than the default limit over HTTP")
	void shouldRejectQueryExceedingDefaultFieldsLimitOverHttp(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(createFieldBombDocument())
			.executeAndThen()
			.statusCode(200)
			.body("data", nullValue())
			.body(ERRORS_PATH, hasSize(1))
			// the numbers in the message are formatted according to the locale, so only the wording is asserted
			.body(ERRORS_PATH + "[0].message", containsString("fields which exceeds maximum allowed"));
	}
}
