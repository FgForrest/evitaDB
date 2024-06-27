/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.lab.tools.diff.graphql;

import io.evitadb.externalApi.lab.tools.diff.SchemaDifferTest;
import io.evitadb.externalApi.lab.tools.schemaDiff.graphql.GraphQLSchemaDiffer;
import io.evitadb.externalApi.lab.tools.schemaDiff.graphql.SchemaDiff;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link GraphQLSchemaDiffer}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class GraphQLSchemaDifferTest extends SchemaDifferTest {

	@ParameterizedTest
	@MethodSource("testSchemas")
	void test(String newSchemaDefinitionFilename, int breakingChanges, int nonBreakingChanges) throws IOException {
		final String oldSchemaDefinition = readFromClasspath("GraphQLSchemaDifferTest_baseSchema.graphql");
		final String newSchemaDefinition = readFromClasspath(newSchemaDefinitionFilename);

		final SchemaDiff diff = GraphQLSchemaDiffer.analyze(oldSchemaDefinition, newSchemaDefinition);
		assertEquals(breakingChanges, diff.breakingChanges().size());
		assertEquals(nonBreakingChanges, diff.nonBreakingChanges().size());
	}

	@Nonnull
	static Stream<Arguments> testSchemas() {
		return Stream.of(
			Arguments.of("GraphQLSchemaDifferTest_addedTypeAndQuery.graphql", 0, 2),
			Arguments.of("GraphQLSchemaDifferTest_renamedField.graphql", 1, 0),
			Arguments.of("GraphQLSchemaDifferTest_changedFieldType.graphql", 3, 0),
			Arguments.of("GraphQLSchemaDifferTest_removedField.graphql", 1, 0),
			Arguments.of("GraphQLSchemaDifferTest_changedArgType.graphql", 1, 0),
			Arguments.of("GraphQLSchemaDifferTest_appliedDirective.graphql", 0, 1),
			Arguments.of("GraphQLSchemaDifferTest_removedAppliedDirective.graphql", 2, 0),
			Arguments.of("GraphQLSchemaDifferTest_removedDirective.graphql", 1, 0),
			Arguments.of("GraphQLSchemaDifferTest_removedTypeAndQuery.graphql", 2, 0)
		);
	}
}
