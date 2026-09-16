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

package io.evitadb.externalApi.api.model;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.test.Entities;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that parametrized descriptions of all descriptor flavours resolve schema arguments to their names instead of
 * printing the schema object itself. Regression test for #1528, where a schema passed to a description template
 * rendered as `io.evitadb.…EntitySchemaDecorator@781bef51` and made the published GraphQL/OpenAPI schema differ
 * between two boots of the same build.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
class DescriptionFormatterTest {

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG,
		NamingConvention.generate(APITestConstants.TEST_CATALOG),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);
	private static final EntitySchemaDecorator ENTITY_SCHEMA = new EntitySchemaDecorator(
		() -> CATALOG_SCHEMA,
		EntitySchema._internalBuild(Entities.PRODUCT)
	);

	@Test
	@DisplayName("property description resolves a schema argument to its name")
	void shouldResolveSchemaInPropertyDescription() {
		assertEquals(
			"Page of PRODUCT records.",
			PropertyDescriptor.builder()
				.name("data")
				.description("Page of %s records.")
				.type(nonNull(String.class))
				.build()
				.description(ENTITY_SCHEMA)
		);
	}

	@Test
	@DisplayName("object description resolves a schema argument to its name")
	void shouldResolveSchemaInObjectDescription() {
		assertEquals(
			"Page of PRODUCT records.",
			ObjectDescriptor.builder()
				.name("EntityRecordPage")
				.description("Page of %s records.")
				.build()
				.description(ENTITY_SCHEMA)
		);
	}

	@Test
	@DisplayName("union description resolves a schema argument to its name")
	void shouldResolveSchemaInUnionDescription() {
		assertEquals(
			"Page of PRODUCT records.",
			UnionDescriptor.builder()
				.name("EntityRecordPage")
				.description("Page of %s records.")
				.build()
				.description(ENTITY_SCHEMA)
		);
	}

	@Test
	@DisplayName("endpoint description resolves a schema argument to its name")
	void shouldResolveSchemaInEndpointDescription() {
		assertEquals(
			"Returns single PRODUCT entity.",
			EndpointDescriptor.builder()
				.operation("getEntity")
				.description("Returns single %s entity.")
				.build()
				.description(ENTITY_SCHEMA)
		);
	}

	@Test
	@DisplayName("description leaves non-schema arguments untouched")
	void shouldLeaveNonSchemaArgumentsUntouched() {
		assertEquals(
			"Page of 10 PRODUCT records.",
			PropertyDescriptor.builder()
				.name("data")
				.description("Page of %d %s records.")
				.type(nonNull(String.class))
				.build()
				.description(10, ENTITY_SCHEMA)
		);
	}
}
