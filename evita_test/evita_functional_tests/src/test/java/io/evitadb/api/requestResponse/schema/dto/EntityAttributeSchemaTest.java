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

import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityAttributeSchema}.
 */
@DisplayName("EntityAttributeSchema")
class EntityAttributeSchemaTest {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should build minimal schema")
		void shouldBuildMinimalSchema() {
			final EntityAttributeSchema schema = EntityAttributeSchema._internalBuild(
				"name", String.class, true
			);

			assertEquals("name", schema.getName());
			assertSame(String.class, schema.getType());
			assertTrue(schema.isLocalized());
			assertFalse(schema.isNullable());
			assertFalse(schema.isRepresentative());
		}

		@Test
		@DisplayName("should build full schema with all parameters")
		void shouldBuildFullSchema() {
			final EntityAttributeSchema schema = EntityAttributeSchema._internalBuild(
				"priority",
				"Entity priority",
				null,
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
				},
				new Scope[]{Scope.LIVE},
				new Scope[]{Scope.LIVE},
				false, true, true,
				Integer.class, 10,
				0
			);

			assertEquals("priority", schema.getName());
			assertEquals("Entity priority", schema.getDescription());
			assertTrue(schema.isUniqueInScope(Scope.LIVE));
			assertTrue(schema.isFilterableInScope(Scope.LIVE));
			assertTrue(schema.isSortableInScope(Scope.LIVE));
			assertTrue(schema.isNullable());
			assertTrue(schema.isRepresentative());
			assertEquals(10, schema.getDefaultValue());
		}

		@Test
		@DisplayName("should accept custom name variants")
		void shouldAcceptCustomNameVariants() {
			final Map<NamingConvention, String> variants = NamingConvention.generate("myAttribute");
			final EntityAttributeSchema schema = EntityAttributeSchema._internalBuild(
				"myAttribute", variants,
				null, null,
				(Map<Scope, AttributeUniquenessType>) null,
				null, null,
				false, false, false,
				String.class, null, 0
			);

			assertEquals(
				variants.get(NamingConvention.CAMEL_CASE),
				schema.getNameVariant(NamingConvention.CAMEL_CASE)
			);
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal for same parameters")
		void shouldBeEqual() {
			final EntityAttributeSchema a = EntityAttributeSchema._internalBuild("code", String.class, false);
			final EntityAttributeSchema b = EntityAttributeSchema._internalBuild("code", String.class, false);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not equal base AttributeSchema with same params")
		void shouldNotEqualBaseAttributeSchema() {
			final EntityAttributeSchema entity = EntityAttributeSchema._internalBuild(
				"code", String.class, false
			);
			final AttributeSchema base = AttributeSchema._internalBuild("code", String.class, false);

			assertNotEquals(entity, base);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToStringTests {

		@Test
		@DisplayName("should contain EntityAttributeSchema prefix")
		void shouldContainPrefix() {
			final EntityAttributeSchema schema = EntityAttributeSchema._internalBuild(
				"code", String.class, false
			);

			final String result = schema.toString();

			assertTrue(result.startsWith("EntityAttributeSchema{"), "should start with EntityAttributeSchema{");
		}

		@Test
		@DisplayName("should format uniqueness entries, not Stream reference")
		void shouldFormatUniquenessEntries() {
			final EntityAttributeSchema schema = EntityAttributeSchema._internalBuild(
				"ean",
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
				},
				new Scope[]{Scope.LIVE},
				null,
				false, false, false,
				String.class, null
			);

			final String result = schema.toString();

			assertFalse(result.contains("ReferencePipeline"), "toString must not contain Stream reference");
			assertTrue(result.contains("UNIQUE_WITHIN_COLLECTION"), "toString should contain uniqueness type");
		}
	}
}
