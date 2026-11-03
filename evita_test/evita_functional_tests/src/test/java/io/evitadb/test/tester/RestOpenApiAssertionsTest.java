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

package io.evitadb.test.tester;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the strict served-document check that every REST dataset passes through after initialization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(REST)
@Tag(TEST_HARNESS)
@DisplayName("Strict check of served OpenAPI documents")
class RestOpenApiAssertionsTest {

	/**
	 * Interfaces name their implementing types only in discriminator mapping values, so a dangling mapping
	 * carries no `$ref` the reference scan would find. The resolvable mapping listed first must not be rejected.
	 */
	@Test
	@DisplayName("Should reject a document whose discriminator mapping names an undefined schema")
	void shouldRejectDocumentWhenDiscriminatorMappingDangles() throws IOException {
		final JsonNode document = new ObjectMapper().readTree("""
			{"components": {"schemas": {
				"Present": {"type": "object"},
				"Root": {
					"discriminator": {"propertyName": "mutationType", "mapping": {
						"Present": "#/components/schemas/Present",
						"Missing": "#/components/schemas/Missing"
					}},
					"properties": {"present": {"$ref": "#/components/schemas/Present"}}
				}
			}}}
			""");

		final AssertionError error = assertThrows(
			AssertionError.class, () -> RestOpenApiAssertions.assertReferencesResolve(document, "test")
		);

		assertTrue(
			error.getMessage().contains("unresolved OpenAPI reference: #/components/schemas/Missing"),
			error.getMessage()
		);
	}
}
