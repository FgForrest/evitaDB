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

package io.evitadb.externalApi.rest.api.testSuite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.core.util.Yaml31;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static io.evitadb.externalApi.http.MimeTypes.APPLICATION_JSON;
import static io.evitadb.externalApi.http.MimeTypes.APPLICATION_YAML;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared parsing and assertions for the catalog and system OpenAPI documents served as JSON or YAML.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public final class OpenApiAssertions {

	/**
	 * Prevents instantiation of this assertion utility.
	 */
	private OpenApiAssertions() {
	}

	/**
	 * Parses a served OpenAPI document with the parser of its representation. JSON is valid YAML, so parsing every
	 * body as YAML would accept a YAML document served as `application/json`.
	 */
	@Nonnull
	public static JsonNode parseSpecification(
		@Nonnull final String mediaType,
		@Nonnull final String body
	) throws JsonProcessingException {
		final ObjectMapper mapper = switch (mediaType) {
			case APPLICATION_JSON -> Json31.mapper();
			case APPLICATION_YAML -> Yaml31.mapper();
			default -> fail("Unsupported OpenAPI representation: " + mediaType);
		};
		final JsonNode specification = mapper.readTree(body);
		assertNotNull(specification, "The response must contain an OpenAPI document.");
		return specification;
	}

	/**
	 * Checks that the document declares the OpenAPI version matching the 3.1 schema dialect it is written in.
	 */
	public static void assertDeclaresOpenApi31(@Nonnull final JsonNode specification) {
		final JsonNode version = specification.get("openapi");
		assertNotNull(version, "The OpenAPI document must declare its version.");
		assertEquals("3.1.0", version.asText(), "The OpenAPI version must match the 3.1 schema dialect.");
	}

	/**
	 * Walks the whole document and checks that every object with `items` declares an array type.
	 * Accepts both the string and type-set forms of the OpenAPI 3.1 `type` keyword, and logs coverage counts.
	 */
	public static void assertArraySchemasDeclareType(@Nonnull final JsonNode specification) {
		final List<Map.Entry<String, JsonNode>> arraySchemas = new ArrayList<>();
		collectArraySchemas(specification, "", false, arraySchemas);
		final long propertyCount = arraySchemas.stream()
			.filter(entry -> entry.getKey().contains("/properties/"))
			.count();
		log.info(
			"OpenAPI document `{}` contains {} schemas with items, including {} property schemas.",
			specification.path("info").path("title").asText(), arraySchemas.size(), propertyCount
		);
		assertTrue(arraySchemas.size() > 10, "The document must contain more than ten schemas with items.");
		assertTrue(propertyCount > 0, "The document must contain at least one property schema with items.");
		for (final Map.Entry<String, JsonNode> entry : arraySchemas) {
			final JsonNode type = entry.getValue().get("type");
			assertNotNull(type, "Schema with items must declare type array at " + entry.getKey());
			assertTrue(
				declaresArrayType(type),
				() -> "Schema with items must declare type array at " + entry.getKey() + "; found " + type
			);
		}
	}

	/**
	 * Returns whether the `type` keyword names `array`, either as a single string or within a type set.
	 */
	private static boolean declaresArrayType(@Nonnull final JsonNode type) {
		return (type.isTextual() && "array".equals(type.textValue())) ||
			(type.isArray() && StreamSupport.stream(type.spliterator(), false)
				.anyMatch(value -> value.isTextual() && "array".equals(value.textValue())));
	}

	/**
	 * Collects every schema object containing `items`, retaining its tree path for assertion diagnostics. The map
	 * under a `properties` keyword is not a schema - a property named `items` must not make it look like one - but
	 * each of its values is, whatever the property is called (`properties` included).
	 *
	 * @param propertyMap whether `node` is the map under a `properties` keyword rather than a schema
	 */
	private static void collectArraySchemas(
		@Nonnull final JsonNode node,
		@Nonnull final String path,
		final boolean propertyMap,
		@Nonnull final List<Map.Entry<String, JsonNode>> arraySchemas
	) {
		if (node.isObject()) {
			if (!propertyMap && node.has("items")) {
				arraySchemas.add(Map.entry(path, node));
			}
			final Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				final Map.Entry<String, JsonNode> field = fields.next();
				// only a keyword of a schema opens a property map, a property of that name inside a map is a schema
				final boolean childIsPropertyMap = !propertyMap && "properties".equals(field.getKey());
				collectArraySchemas(field.getValue(), path + "/" + field.getKey(), childIsPropertyMap, arraySchemas);
			}
		} else if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) {
				collectArraySchemas(node.get(i), path + "/" + i, false, arraySchemas);
			}
		}
	}
}
