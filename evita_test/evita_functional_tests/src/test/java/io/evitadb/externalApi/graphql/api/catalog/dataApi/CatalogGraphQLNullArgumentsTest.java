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

import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.QUERY;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Confirms that GraphQL's non-null list item schema rejects undefined filter and ordering values.
 */
@DisplayName("GraphQL null value-list validation")
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
class CatalogGraphQLNullArgumentsTest extends CatalogGraphQLDataEndpointFunctionalTest {

	/**
	 * Exercises both list constraints without reaching query execution.
	 */
	@Nested
	@DisplayName("Non-null value-list items")
	class ValueLists {

		/**
		 * Confirms schema rejection regardless of the null item's position.
		 *
		 * @param container query argument holding the list constraint
		 * @param key list constraint field
		 * @param values GraphQL list literal with one defined value and one null
		 * @param tester client for the running GraphQL server
		 */
		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
			"filterBy|attributeCodeInSet|[\"A\", null]", "filterBy|attributeCodeInSet|[null, \"A\"]",
			"orderBy|attributeCodeSetExact|[\"A\", null]", "orderBy|attributeCodeSetExact|[null, \"A\"]"
		})
		@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
		@DisplayName("Reject null literal items in filter and exact ordering lists")
		void shouldRejectNullItemsWhenListsAreLiterals(
			@Nonnull String container, @Nonnull String key, @Nonnull String values, @Nonnull GraphQLTester tester
		) {
			tester.test(TEST_CATALOG).document(
				"query { queryProduct(%s: {%s: %s}) { recordPage { data { primaryKey } } } }",
				container, key, values
			).executeAndExpectErrorsAndThen()
				.body("data", nullValue())
				.body("errors[0].message", containsString("must not be null"));
		}

		/**
		 * Confirms that variable coercion enforces the same non-null component type as literal validation.
		 *
		 * @param container query argument holding the list constraint
		 * @param key list constraint field
		 * @param nullFirst whether the undefined item precedes the defined value
		 * @param tester client for the running GraphQL server
		 */
		@ParameterizedTest
		@CsvSource({
			"filterBy,attributeCodeInSet,true", "filterBy,attributeCodeInSet,false",
			"orderBy,attributeCodeSetExact,true", "orderBy,attributeCodeSetExact,false"
		})
		@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
		@DisplayName("Reject null variable items in filter and exact ordering lists")
		void shouldRejectNullItemsWhenListsAreVariables(
			@Nonnull String container, @Nonnull String key, boolean nullFirst, @Nonnull GraphQLTester tester
		) {
			final List<String> values = nullFirst ? Arrays.asList(null, "A") : Arrays.asList("A", null);
			tester.test(TEST_CATALOG).document(
				"query($values: [String!]!) { queryProduct(%s: {%s: $values}) { recordPage { data { primaryKey } } } }",
				container, key
			).variable("values", values).executeAndExpectErrorsAndThen()
				.body("data", nullValue())
				.body("errors[0].message", containsString("Coerced Null value for NonNull type 'String!'"));
		}
	}
	/**
	 * Verifies omission after GraphQL accepts a container holding a null child field.
	 */
	@Nested
	@DisplayName("Factory construction")
	class Construction {
		/**
		 * Preserves a sibling ordering after the scoped ordering loses its only child.
		 *
		 * @param tester running GraphQL endpoint client
		 */
		@Test
		@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
		@DisplayName("An emptied scoped ordering preserves its sibling")
		void shouldPreserveOrderingWhenScopedChildIsOmitted(@Nonnull GraphQLTester tester) {
			tester.test(TEST_CATALOG).document("""
				query {
					queryProduct(orderBy: [
						{inScope: {scope: LIVE, ordering: [{attributeCodeNatural: null}]}},
						{entityPrimaryKeyNatural: DESC}
					]) { recordPage { data { primaryKey } } }
				}
				""").executeAndExpectOkAndThen()
				.body("data.queryProduct.recordPage.data[0].primaryKey", equalTo(1000));
		}
	}
}
