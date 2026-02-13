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

import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SortableAttributeCompoundSchema} and
 * {@link EntitySortableAttributeCompoundSchema}.
 */
@DisplayName("SortableAttributeCompoundSchema")
class SortableAttributeCompoundSchemaTest {

	private static final List<AttributeElement> ELEMENTS = List.of(
		new AttributeElement("name", null, null),
		new AttributeElement("code", null, null)
	);

	@Nested
	@DisplayName("SortableAttributeCompoundSchema")
	class Base {

		@Test
		@DisplayName("should build with description and scopes")
		void shouldBuildWithDescriptionAndScopes() {
			final SortableAttributeCompoundSchema schema = SortableAttributeCompoundSchema._internalBuild(
				"namePlusCode",
				"Compound sort by name then code",
				null,
				new Scope[]{Scope.LIVE},
				ELEMENTS
			);

			assertEquals("namePlusCode", schema.getName());
			assertEquals("Compound sort by name then code", schema.getDescription());
			assertNull(schema.getDeprecationNotice());
			assertTrue(schema.isIndexedInScope(Scope.LIVE));
			assertFalse(schema.isIndexedInScope(Scope.ARCHIVED));
			assertEquals(2, schema.getAttributeElements().size());
		}

		@Test
		@DisplayName("should generate name variants")
		void shouldGenerateNameVariants() {
			final SortableAttributeCompoundSchema schema = SortableAttributeCompoundSchema._internalBuild(
				"nameAndCode",
				null, null,
				null,
				ELEMENTS
			);

			assertEquals("nameAndCode", schema.getNameVariant(NamingConvention.CAMEL_CASE));
		}

		@Test
		@DisplayName("should be equal for same parameters")
		void shouldBeEqual() {
			final SortableAttributeCompoundSchema a = SortableAttributeCompoundSchema._internalBuild(
				"comp", null, null, new Scope[]{Scope.LIVE}, ELEMENTS
			);
			final SortableAttributeCompoundSchema b = SortableAttributeCompoundSchema._internalBuild(
				"comp", null, null, new Scope[]{Scope.LIVE}, ELEMENTS
			);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal when names differ")
		void shouldNotBeEqualWhenNamesDiffer() {
			final SortableAttributeCompoundSchema a = SortableAttributeCompoundSchema._internalBuild(
				"comp1", null, null, null, ELEMENTS
			);
			final SortableAttributeCompoundSchema b = SortableAttributeCompoundSchema._internalBuild(
				"comp2", null, null, null, ELEMENTS
			);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should format toString with elements")
		void shouldFormatToString() {
			final SortableAttributeCompoundSchema schema = SortableAttributeCompoundSchema._internalBuild(
				"comp", null, null, new Scope[]{Scope.LIVE}, ELEMENTS
			);

			final String result = schema.toString();

			assertTrue(result.contains("comp"));
			assertTrue(result.contains("name"));
			assertTrue(result.contains("code"));
		}

		@Test
		@DisplayName("should handle null indexed scopes as empty")
		void shouldHandleNullIndexedScopes() {
			final SortableAttributeCompoundSchema schema = SortableAttributeCompoundSchema._internalBuild(
				"comp", null, null, null, ELEMENTS
			);

			assertFalse(schema.isIndexedInScope(Scope.LIVE));
			assertTrue(schema.getIndexedInScopes().isEmpty());
		}
	}

	@Nested
	@DisplayName("EntitySortableAttributeCompoundSchema")
	class EntitySubtype {

		@Test
		@DisplayName("should build entity compound schema")
		void shouldBuildEntityCompound() {
			final EntitySortableAttributeCompoundSchema schema =
				EntitySortableAttributeCompoundSchema._internalBuild(
					"entityComp",
					"Entity-level compound",
					null,
					new Scope[]{Scope.LIVE},
					ELEMENTS
				);

			assertEquals("entityComp", schema.getName());
			assertEquals("Entity-level compound", schema.getDescription());
			assertTrue(schema.isIndexedInScope(Scope.LIVE));
		}

		@Test
		@DisplayName("should accept custom name variants")
		void shouldAcceptCustomNameVariants() {
			final Map<NamingConvention, String> variants = NamingConvention.generate("myCompound");
			final EntitySortableAttributeCompoundSchema schema =
				EntitySortableAttributeCompoundSchema._internalBuild(
					"myCompound", variants,
					null, null,
					(java.util.Set<Scope>) null,
					ELEMENTS
				);

			assertEquals(
				variants.get(NamingConvention.CAMEL_CASE),
				schema.getNameVariant(NamingConvention.CAMEL_CASE)
			);
		}

		@Test
		@DisplayName("should be equal for same parameters")
		void shouldBeEqual() {
			final EntitySortableAttributeCompoundSchema a =
				EntitySortableAttributeCompoundSchema._internalBuild(
					"comp", null, null, new Scope[]{Scope.LIVE}, ELEMENTS
				);
			final EntitySortableAttributeCompoundSchema b =
				EntitySortableAttributeCompoundSchema._internalBuild(
					"comp", null, null, new Scope[]{Scope.LIVE}, ELEMENTS
				);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not equal base SortableAttributeCompound")
		void shouldNotEqualBase() {
			final EntitySortableAttributeCompoundSchema entity =
				EntitySortableAttributeCompoundSchema._internalBuild(
					"comp", null, null, new Scope[]{Scope.LIVE}, ELEMENTS
				);
			final SortableAttributeCompoundSchema base = SortableAttributeCompoundSchema._internalBuild(
				"comp", null, null, new Scope[]{Scope.LIVE}, ELEMENTS
			);

			// Different classes should not be equal
			assertNotEquals(entity, base);
		}
	}
}
