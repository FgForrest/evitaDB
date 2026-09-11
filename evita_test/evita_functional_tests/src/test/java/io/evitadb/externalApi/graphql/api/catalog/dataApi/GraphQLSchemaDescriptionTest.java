/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import graphql.schema.GraphQLSchema;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.graphql.utils.GraphQLSchemaPrinter;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.AbstractMap.SimpleEntry;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that the built {@link GraphQLSchema} SDL never embeds a JVM identity string (an internal class name
 * followed by `Object#toString()`'s `@<hex>` identity hash) into a field or type description. Such a leak makes
 * the published document non-reproducible across restarts, because the hash changes on every JVM start.
 *
 * @see io.evitadb.api.requestResponse.schema.EntitySchemaDecoratorTest
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ExtendWith(EvitaParameterResolver.class)
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
class GraphQLSchemaDescriptionTest {
	private static final String GRAPHQL_SCHEMA_DESCRIPTION_TEST_DATA_SET = "GraphQLSchemaDescriptionTestDataSet";
	private static final Pattern JVM_IDENTITY_STRING_PATTERN =
		Pattern.compile("io\\.evitadb(?:\\.[\\w$]+)*@[0-9a-f]{1,8}\\b");

	@DataSet(value = GRAPHQL_SCHEMA_DESCRIPTION_TEST_DATA_SET, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		TestDataGenerator.generateMockCatalogs(evita);
		final Set<Entry<String, Object>> dataCarrier =
			new HashSet<>(TestDataGenerator.generateMainCatalogEntities(evita, 20, false).entrySet());

		final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final GraphQLSchema graphQLSchema = new CatalogDataApiGraphQLSchemaBuilder(
			new GraphQLOptions("localhost:5555"),
			evita,
			catalog
		).build();
		dataCarrier.add(new SimpleEntry<>("graphQLSchema", graphQLSchema));

		return new DataCarrier(dataCarrier);
	}

	@Test
	@UseDataSet(GRAPHQL_SCHEMA_DESCRIPTION_TEST_DATA_SET)
	@DisplayName("Should not leak a JVM identity hash or internal class name into any description")
	void shouldNotLeakJvmIdentityHashIntoDescriptions(Evita evita, GraphQLSchema graphQLSchema) {
		final String sdl = GraphQLSchemaPrinter.print(graphQLSchema);

		final Matcher matcher = JVM_IDENTITY_STRING_PATTERN.matcher(sdl);
		assertFalse(matcher.find(), () -> "GraphQL SDL leaks a JVM identity string: `" + matcher.group() + "`");
	}
}
