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

package io.evitadb.externalApi.lab.tools.diff.openApi;

import io.evitadb.externalApi.lab.tools.diff.SchemaDifferTest;
import io.evitadb.externalApi.lab.tools.schemaDiff.openApi.OpenApiSchemaDiffer;
import io.evitadb.externalApi.lab.tools.schemaDiff.openApi.SchemaDiff;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link OpenApiSchemaDiffer}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class OpenApiSchemaDifferTest extends SchemaDifferTest {

	@ParameterizedTest
	@MethodSource("testSchemas")
	void test(String newSchemaDefinitionFilename, int breakingChanges, int nonBreakingChanges) throws IOException {
		final String oldSchemaDefinition = readFromClasspath("OpenApiSchemaDifferTest_baseSchema.json");
		final String newSchemaDefinition = readFromClasspath(newSchemaDefinitionFilename);

		final SchemaDiff diff = OpenApiSchemaDiffer.analyze(oldSchemaDefinition, newSchemaDefinition);
		assertEquals(breakingChanges, diff.breakingChanges().size());
		assertEquals(nonBreakingChanges, diff.nonBreakingChanges().size());
	}

	@Nonnull
	static Stream<Arguments> testSchemas() {
		return Stream.of(
			Arguments.of("OpenApiSchemaDifferTest_addedEndpoint.json", 0, 1),
			Arguments.of("OpenApiSchemaDifferTest_deprecatedEndpoint.json", 0, 1),
			Arguments.of("OpenApiSchemaDifferTest_removedEndpoint.json", 1, 0),
			Arguments.of("OpenApiSchemaDifferTest_changedPropertyType.json", 2, 0),
			Arguments.of("OpenApiSchemaDifferTest_changedPropertyFormat.json", 2, 0),
			Arguments.of("OpenApiSchemaDifferTest_addedOneOfReference.json", 2, 0),
			Arguments.of("OpenApiSchemaDifferTest_changedOneOfReference.json", 2, 0),
			Arguments.of("OpenApiSchemaDifferTest_changedOneOfReferenceToNestedObject.json", 2, 0),
			Arguments.of("OpenApiSchemaDifferTest_removedOneOfReference.json", 0, 2),
			Arguments.of("OpenApiSchemaDifferTest_changedAllOfReferenceToNestedObject.json", 0, 2),
			Arguments.of("OpenApiSchemaDifferTest_removedAllOfReference.json", 0, 2)
		);
	}
}
