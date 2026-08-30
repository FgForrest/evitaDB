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


package io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeFilterAcceleratorsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the REST rendering of an attribute's filter accelerators.
 *
 * The REST schema endpoints reach this through `EntitySchemaJsonSerializer`, but the accelerator-specific logic all
 * lives in the one protected method exercised here, so it is tested directly rather than through a booted server.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class SchemaJsonSerializerAcceleratorsTest {

	/**
	 * `serializeAccelerators` is protected on an abstract class, so the test reaches it through the smallest possible
	 * concrete subclass rather than through a booted REST endpoint.
	 */
	private static final class TestSerializer extends SchemaJsonSerializer {
		TestSerializer() {
			super(new ObjectJsonSerializer(new ObjectMapper()));
		}

		@Nonnull
		ArrayNode render(@Nonnull AttributeSchemaContract attributeSchema) {
			return serializeAccelerators(attributeSchema);
		}
	}

	@Test
	@DisplayName("renders the accelerators of a unique-only attribute")
	void shouldRenderAcceleratorsOfUniqueOnlyAttribute() {
		// the attribute is never filterable - `unique()` alone supplies the filter index. The REST output must carry
		// the accelerator regardless, because the JSON is written from the accelerator map and nothing else
		final AttributeSchemaContract attributeSchema = AttributeSchema._internalBuild(
			"code", null, null,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			},
			Scope.NO_SCOPE,
			new ScopedAttributeFilterAccelerators[]{
				new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
			},
			Scope.NO_SCOPE,
			false, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.INHERITED
		);

		final ArrayNode rendered = new TestSerializer().render(attributeSchema);

		assertEquals(1, rendered.size());
		assertEquals(
			Scope.LIVE.name(),
			rendered.get(0).get(ScopedDataDescriptor.SCOPE.name()).asText()
		);
		final ArrayNode accelerators = (ArrayNode) rendered.get(0)
			.get(ScopedAttributeFilterAcceleratorsDescriptor.ACCELERATORS.name());
		assertEquals(1, accelerators.size());
		assertEquals(AttributeFilterAccelerator.SUBSTRING_SEARCH.name(), accelerators.get(0).asText());
	}

	@Test
	@DisplayName("renders an empty array for an attribute declaring no accelerator")
	void shouldRenderEmptyArrayForAttributeWithoutAccelerators() {
		// the shape every attribute written before this axis existed produces - it must be an empty array, never null
		final AttributeSchemaContract attributeSchema = AttributeSchema._internalBuild(
			"ean", null, null,
			// the untyped nulls would leave the array- and map-shaped overloads equally applicable
			(ScopedAttributeUniquenessType[]) null,
			Scope.DEFAULT_SCOPES,
			(ScopedAttributeFilterAccelerators[]) null,
			Scope.NO_SCOPE,
			false, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.INHERITED
		);

		assertTrue(new TestSerializer().render(attributeSchema).isEmpty());
	}
}
