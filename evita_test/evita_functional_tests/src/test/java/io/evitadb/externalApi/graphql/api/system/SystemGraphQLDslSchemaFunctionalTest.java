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

package io.evitadb.externalApi.graphql.api.system;

import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLSchemaTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;

/**
 * Tests for GraphQL system DSL endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
public class SystemGraphQLDslSchemaFunctionalTest extends SystemGraphQLEndpointFunctionalTest {

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return DSL of GraphQL schema")
	void shouldReturnDslOfGraphQLSchema(GraphQLSchemaTester tester) {
		tester.test(SYSTEM_URL)
			.executeAndExpectOkAndThen()
			.body(not(emptyOrNullString()));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should expose the conflict-resolution schema mutations in the system GraphQL DSL")
	void shouldExposeConflictResolutionMutationsInSystemGraphQLDsl(GraphQLSchemaTester tester) {
		// the 5 conflict-resolution schema mutations must be members of the schema-mutation unions and
		// resolvable as output object types in the system schema (asserting presence, not merely a
		// successful build: a silently dropped union member would boot green yet vanish from the DSL)
		tester.test(SYSTEM_URL)
			.executeAndExpectOkAndThen()
			.body(allOf(
				containsString("ModifyCatalogSchemaConflictResolutionMutation"),
				containsString("ModifyEntitySchemaConflictResolutionMutation"),
				containsString("SetAttributeSchemaConflictResolutionOverrideMutation"),
				containsString("SetAssociatedDataSchemaConflictResolutionOverrideMutation"),
				containsString("SetReferenceSchemaConflictResolutionOverrideMutation")
			));
	}
}
