/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AssociatedDataSchema}.
 */
@DisplayName("AssociatedDataSchema")
class AssociatedDataSchemaTest {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should build minimal schema with name and type")
		void shouldBuildMinimalSchema() {
			final AssociatedDataSchema schema =
				(AssociatedDataSchema) AssociatedDataSchema._internalBuild("labels", String.class);

			assertEquals("labels", schema.getName());
			assertSame(String.class, schema.getType());
			assertSame(String.class, schema.getPlainType());
			assertFalse(schema.isLocalized());
			assertFalse(schema.isNullable());
			assertNull(schema.getDescription());
			assertNull(schema.getDeprecationNotice());
		}

		@Test
		@DisplayName("should build full schema with all parameters")
		void shouldBuildFullSchema() {
			final AssociatedDataSchema schema = AssociatedDataSchema._internalBuild(
				"description",
				"Product description",
				"Use richDescription instead",
				String.class,
				true, true
			);

			assertEquals("description", schema.getName());
			assertEquals("Product description", schema.getDescription());
			assertEquals("Use richDescription instead", schema.getDeprecationNotice());
			assertTrue(schema.isLocalized());
			assertTrue(schema.isNullable());
		}

		@Test
		@DisplayName("should accept custom name variants")
		void shouldAcceptCustomNameVariants() {
			final Map<NamingConvention, String> variants = NamingConvention.generate("productData");
			final AssociatedDataSchema schema = AssociatedDataSchema._internalBuild(
				"productData", variants,
				null, null,
				String.class, false, false
			);

			assertEquals(
				variants.get(NamingConvention.CAMEL_CASE),
				schema.getNameVariant(NamingConvention.CAMEL_CASE)
			);
		}

		@Test
		@DisplayName("should wrap primitive types to wrapper types")
		void shouldWrapPrimitiveTypes() {
			final AssociatedDataSchema schema = AssociatedDataSchema._internalBuild(
				"count", null, null, int.class, false, false
			);

			assertSame(Integer.class, schema.getType());
		}

		@Test
		@DisplayName("should resolve plain type from array type")
		void shouldResolvePlainTypeFromArray() {
			final AssociatedDataSchema schema = AssociatedDataSchema._internalBuild(
				"tags", null, null, String[].class, false, false
			);

			assertSame(String[].class, schema.getType());
			assertSame(String.class, schema.getPlainType());
		}

		@Test
		@DisplayName("should use ComplexDataObject for unsupported types")
		void shouldUseComplexDataObjectForUnsupportedTypes() {
			final AssociatedDataSchema schema = AssociatedDataSchema._internalBuild(
				"metadata", null, null, java.util.HashMap.class, false, false
			);

			assertSame(ComplexDataObject.class, schema.getType());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal for same parameters")
		void shouldBeEqual() {
			final AssociatedDataSchema a = AssociatedDataSchema._internalBuild(
				"data", null, null, String.class, false, false
			);
			final AssociatedDataSchema b = AssociatedDataSchema._internalBuild(
				"data", null, null, String.class, false, false
			);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal when names differ")
		void shouldNotBeEqualWhenNamesDiffer() {
			final AssociatedDataSchema a = AssociatedDataSchema._internalBuild(
				"data1", null, null, String.class, false, false
			);
			final AssociatedDataSchema b = AssociatedDataSchema._internalBuild(
				"data2", null, null, String.class, false, false
			);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when localized differs")
		void shouldNotBeEqualWhenLocalizedDiffers() {
			final AssociatedDataSchema a = AssociatedDataSchema._internalBuild(
				"data", null, null, String.class, true, false
			);
			final AssociatedDataSchema b = AssociatedDataSchema._internalBuild(
				"data", null, null, String.class, false, false
			);

			assertNotEquals(a, b);
		}
	}
}
