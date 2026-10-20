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
import io.restassured.response.Response;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks serialized REST documents independently of the production OpenAPI validator.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class RestOpenApiAssertions {

	/**
	 * Utility class.
	 */
	private RestOpenApiAssertions() {
	}

	/**
	 * Fetches a served document, allowing asynchronous catalog registration to finish.
	 */
	@Nonnull
	public static JsonNode fetchDocument(@Nonnull RestTester tester, @Nonnull String documentName) throws IOException {
		final AtomicReference<Response> response = new AtomicReference<>();
		await().atMost(Duration.ofSeconds(30)).until(() -> {
			response.set(tester.test(documentName)
				.httpMethod(RestTester.Request.METHOD_GET)
				.acceptHeader("application/json")
				.executeAndThen().extract().response());
			return response.get().statusCode() == 200;
		});
		return new ObjectMapper().readTree(response.get().asString());
	}

	/**
	 * Requires schemas and references to be present and every serialized reference to resolve. Discriminator
	 * mapping values are references too: interfaces name their implementing types only there, never via `$ref`.
	 */
	public static void assertReferencesResolve(@Nonnull JsonNode document, @Nonnull String documentName) {
		final JsonNode schemas = document.path("components").path("schemas");
		assertTrue(schemas.isObject() && !schemas.isEmpty(), documentName + ": component schemas must be present");
		final List<JsonNode> references = document.findValues("$ref");
		assertFalse(references.isEmpty(), documentName + ": the document must contain references");
		for (final JsonNode reference : references) {
			assertReferenceResolves(document, documentName, reference.asText());
		}
		for (final JsonNode discriminator : document.findValues("discriminator")) {
			for (final JsonNode target : discriminator.path("mapping")) {
				assertReferenceResolves(document, documentName, target.asText());
			}
		}
	}

	/**
	 * Requires a reference to point at a component schema the document defines.
	 */
	private static void assertReferenceResolves(
		@Nonnull JsonNode document, @Nonnull String documentName, @Nonnull String target
	) {
		assertTrue(target.startsWith("#/components/schemas/"), documentName + ": unexpected reference: " + target);
		assertFalse(document.at(target.substring(1)).isMissingNode(),
			documentName + ": unresolved OpenAPI reference: " + target);
	}
}
