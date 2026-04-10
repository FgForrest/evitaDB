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

import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CatalogSchema}.
 */
@DisplayName("CatalogSchema")
class CatalogSchemaTest {

	private static final EntitySchemaProvider EMPTY_PROVIDER = new EntitySchemaProvider() {
		@Nonnull
		@Override
		public Collection<EntitySchemaContract> getEntitySchemas() {
			return Collections.emptyList();
		}

		@Nonnull
		@Override
		public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
			return Optional.empty();
		}
	};

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should build minimal catalog schema")
		void shouldBuildMinimalSchema() {
			final CatalogSchema schema = CatalogSchema._internalBuild(
				"testCatalog",
				NamingConvention.generate("testCatalog"),
				EnumSet.allOf(CatalogEvolutionMode.class),
				EMPTY_PROVIDER
			);

			assertEquals("testCatalog", schema.getName());
			assertEquals(1, schema.version());
			assertNull(schema.getDescription());
			assertTrue(schema.getAttributes().isEmpty());
			assertFalse(schema.getCatalogEvolutionMode().isEmpty());
		}

		@Test
		@DisplayName("should build schema with version and description")
		void shouldBuildWithVersionAndDescription() {
			final CatalogSchema schema = CatalogSchema._internalBuild(
				5,
				"myCatalog",
				NamingConvention.generate("myCatalog"),
				"Test catalog",
				EnumSet.noneOf(CatalogEvolutionMode.class),
				Collections.emptyMap(),
				EMPTY_PROVIDER
			);

			assertEquals("myCatalog", schema.getName());
			assertEquals(5, schema.version());
			assertEquals("Test catalog", schema.getDescription());
		}

		@Test
		@DisplayName("should build schema with global attributes")
		void shouldBuildWithGlobalAttributes() {
			final GlobalAttributeSchema attr = GlobalAttributeSchema._internalBuild(
				"url",
				null,
				new ScopedGlobalAttributeUniquenessType[]{
					new ScopedGlobalAttributeUniquenessType(
						Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
					)
				},
				new Scope[]{Scope.LIVE},
				null,
				false, false, false,
				String.class, null
			);

			final Map<String, GlobalAttributeSchemaContract> attrs = Map.of("url", attr);

			final CatalogSchema schema = CatalogSchema._internalBuild(
				1,
				"catalog",
				NamingConvention.generate("catalog"),
				null,
				EnumSet.noneOf(CatalogEvolutionMode.class),
				attrs,
				EMPTY_PROVIDER
			);

			assertFalse(schema.getAttributes().isEmpty());
			assertTrue(schema.getAttribute("url").isPresent());
		}
	}

	@Nested
	@DisplayName("Attribute access")
	class AttributeAccess {

		@Test
		@DisplayName("should find attribute by name")
		void shouldFindAttributeByName() {
			final GlobalAttributeSchema attr = GlobalAttributeSchema._internalBuild(
				"code", String.class, false
			);

			final CatalogSchema schema = CatalogSchema._internalBuild(
				1,
				"catalog",
				NamingConvention.generate("catalog"),
				null,
				EnumSet.noneOf(CatalogEvolutionMode.class),
				Map.of("code", attr),
				EMPTY_PROVIDER
			);

			assertTrue(schema.getAttribute("code").isPresent());
			assertTrue(schema.getAttribute("nonexistent").isEmpty());
		}

		@Test
		@DisplayName("should find attribute by naming convention")
		void shouldFindAttributeByNamingConvention() {
			final GlobalAttributeSchema attr = GlobalAttributeSchema._internalBuild(
				"productCode", String.class, false
			);

			final CatalogSchema schema = CatalogSchema._internalBuild(
				1,
				"catalog",
				NamingConvention.generate("catalog"),
				null,
				EnumSet.noneOf(CatalogEvolutionMode.class),
				Map.of("productCode", attr),
				EMPTY_PROVIDER
			);

			assertTrue(schema.getAttributeByName("productCode", NamingConvention.CAMEL_CASE).isPresent());
		}
	}

	@Nested
	@DisplayName("Name variants")
	class NameVariants {

		@Test
		@DisplayName("should provide name variants")
		void shouldProvideNameVariants() {
			final CatalogSchema schema = CatalogSchema._internalBuild(
				"testCatalog",
				NamingConvention.generate("testCatalog"),
				EnumSet.noneOf(CatalogEvolutionMode.class),
				EMPTY_PROVIDER
			);

			assertEquals("testCatalog", schema.getNameVariant(NamingConvention.CAMEL_CASE));
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal for same version and name")
		void shouldBeEqual() {
			final CatalogSchema a = CatalogSchema._internalBuild(
				"catalog",
				NamingConvention.generate("catalog"),
				EnumSet.noneOf(CatalogEvolutionMode.class),
				EMPTY_PROVIDER
			);
			final CatalogSchema b = CatalogSchema._internalBuild(
				"catalog",
				NamingConvention.generate("catalog"),
				EnumSet.noneOf(CatalogEvolutionMode.class),
				EMPTY_PROVIDER
			);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal when names differ")
		void shouldNotBeEqualWhenNamesDiffer() {
			final CatalogSchema a = CatalogSchema._internalBuild(
				"catalog1",
				NamingConvention.generate("catalog1"),
				EnumSet.noneOf(CatalogEvolutionMode.class),
				EMPTY_PROVIDER
			);
			final CatalogSchema b = CatalogSchema._internalBuild(
				"catalog2",
				NamingConvention.generate("catalog2"),
				EnumSet.noneOf(CatalogEvolutionMode.class),
				EMPTY_PROVIDER
			);

			assertNotEquals(a, b);
		}
	}

	@Nested
	@DisplayName("Schema versioning")
	class Versioning {

		@Test
		@DisplayName("should increment version when updated")
		void shouldIncrementVersion() {
			final CatalogSchema original = CatalogSchema._internalBuild(
				1,
				"catalog",
				NamingConvention.generate("catalog"),
				null,
				EnumSet.noneOf(CatalogEvolutionMode.class),
				Collections.emptyMap(),
				EMPTY_PROVIDER
			);

			final CatalogSchema updated = CatalogSchema._internalBuildWithUpdatedVersion(
				original, EMPTY_PROVIDER
			);

			assertEquals(2, updated.version());
			assertEquals(original.getName(), updated.getName());
		}

		@Test
		@DisplayName("should preserve version when updating accessor")
		void shouldPreserveVersionWhenUpdatingAccessor() {
			final CatalogSchema original = CatalogSchema._internalBuild(
				3,
				"catalog",
				NamingConvention.generate("catalog"),
				null,
				EnumSet.noneOf(CatalogEvolutionMode.class),
				Collections.emptyMap(),
				EMPTY_PROVIDER
			);

			final CatalogSchema updated =
				CatalogSchema._internalBuildWithUpdatedEntitySchemaAccessor(original, EMPTY_PROVIDER);

			assertEquals(3, updated.version());
		}
	}

	@Nested
	@DisplayName("differsFrom")
	class DiffersFrom {

		@Test
		@DisplayName("should not differ from itself")
		void shouldNotDifferFromSelf() {
			final CatalogSchema schema = CatalogSchema._internalBuild(
				"catalog",
				NamingConvention.generate("catalog"),
				EnumSet.noneOf(CatalogEvolutionMode.class),
				EMPTY_PROVIDER
			);

			assertFalse(schema.differsFrom(schema));
		}

		@Test
		@DisplayName("should differ when version changes")
		void shouldDifferWhenVersionChanges() {
			final CatalogSchema original = CatalogSchema._internalBuild(
				1,
				"catalog",
				NamingConvention.generate("catalog"),
				null,
				EnumSet.noneOf(CatalogEvolutionMode.class),
				Collections.emptyMap(),
				EMPTY_PROVIDER
			);

			final CatalogSchema updated = CatalogSchema._internalBuildWithUpdatedVersion(
				original, EMPTY_PROVIDER
			);

			assertTrue(original.differsFrom(updated));
		}
	}
}
