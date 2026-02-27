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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeSchema} and its sealed subtypes.
 */
@DisplayName("AttributeSchema")
class AttributeSchemaTest {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should build minimal schema with name, type and localized flag")
		void shouldBuildMinimalSchema() {
			final AttributeSchema schema = AttributeSchema._internalBuild("code", String.class, false);

			assertEquals("code", schema.getName());
			assertSame(String.class, schema.getType());
			assertSame(String.class, schema.getPlainType());
			assertFalse(schema.isLocalized());
			assertFalse(schema.isNullable());
			assertFalse(schema.isRepresentative());
			assertNull(schema.getDescription());
			assertNull(schema.getDeprecationNotice());
			assertNull(schema.getDefaultValue());
			assertEquals(0, schema.getIndexedDecimalPlaces());
		}

		@Test
		@DisplayName("should build full schema with all parameters")
		void shouldBuildFullSchema() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"priority",
				"Priority of the entity",
				"Use 'weight' instead",
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
				},
				new Scope[]{Scope.LIVE},
				new Scope[]{Scope.LIVE},
				false, true, true,
				Integer.class, 0,
				0
			);

			assertEquals("priority", schema.getName());
			assertEquals("Priority of the entity", schema.getDescription());
			assertEquals("Use 'weight' instead", schema.getDeprecationNotice());
			assertTrue(schema.isUniqueInScope(Scope.LIVE));
			assertTrue(schema.isFilterableInScope(Scope.LIVE));
			assertTrue(schema.isSortableInScope(Scope.LIVE));
			assertTrue(schema.isNullable());
			assertTrue(schema.isRepresentative());
			assertSame(Integer.class, schema.getType());
			assertEquals(0, schema.getDefaultValue());
		}

		@Test
		@DisplayName("should generate name variants for naming conventions")
		void shouldGenerateNameVariants() {
			final AttributeSchema schema = AttributeSchema._internalBuild("productCode", String.class, false);

			final String camelCase = schema.getNameVariant(NamingConvention.CAMEL_CASE);
			assertNotNull(camelCase);
			assertEquals("productCode", schema.getNameVariant(NamingConvention.CAMEL_CASE));
		}

		@Test
		@DisplayName("should accept custom name variants")
		void shouldAcceptCustomNameVariants() {
			final Map<NamingConvention, String> customVariants = NamingConvention.generate("myAttr");
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"myAttr", customVariants,
				null, null,
				(Map<Scope, AttributeUniquenessType>) null,
				null, null,
				false, false, false,
				String.class, null, 0
			);

			assertEquals(
				customVariants.get(NamingConvention.CAMEL_CASE),
				schema.getNameVariant(NamingConvention.CAMEL_CASE)
			);
		}

		@Test
		@DisplayName("should wrap primitive types to wrapper types")
		void shouldWrapPrimitiveTypes() {
			final AttributeSchema schema = AttributeSchema._internalBuild("count", int.class, false);

			assertSame(Integer.class, schema.getType());
			assertSame(Integer.class, schema.getPlainType());
		}

		@Test
		@DisplayName("should resolve plain type from array type")
		void shouldResolvePlainTypeFromArrayType() {
			final AttributeSchema schema = AttributeSchema._internalBuild("tags", String[].class, false);

			assertSame(String[].class, schema.getType());
			assertSame(String.class, schema.getPlainType());
		}

		@Test
		@DisplayName("should default uniqueness to NOT_UNIQUE when null")
		void shouldDefaultUniquenessToNotUnique() {
			final AttributeSchema schema = AttributeSchema._internalBuild("code", String.class, false);

			assertEquals(AttributeUniquenessType.NOT_UNIQUE, schema.getUniquenessType(Scope.DEFAULT_SCOPE));
			assertFalse(schema.isUnique());
			assertFalse(schema.isUniqueWithinLocale());
		}
	}

	@Nested
	@DisplayName("Uniqueness queries")
	class UniquenessQueries {

		@Test
		@DisplayName("should report unique when scope has UNIQUE_WITHIN_COLLECTION")
		void shouldReportUniqueForCollection() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"ean",
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
				},
				null, null,
				false, false, false,
				String.class, null
			);

			assertTrue(schema.isUniqueInScope(Scope.LIVE));
			assertTrue(schema.isUnique());
			assertFalse(schema.isUniqueWithinLocale());
			assertFalse(schema.isUniqueWithinLocaleInScope(Scope.LIVE));
		}

		@Test
		@DisplayName("should report unique within locale for matching scope")
		void shouldReportUniqueWithinLocale() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"slug",
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(
						Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
					)
				},
				null, null,
				true, false, false,
				String.class, null
			);

			assertTrue(schema.isUniqueInScope(Scope.LIVE));
			assertTrue(schema.isUniqueWithinLocaleInScope(Scope.LIVE));
			assertTrue(schema.isUniqueWithinLocale());
		}

		@Test
		@DisplayName("should return NOT_UNIQUE for unregistered scope")
		void shouldReturnNotUniqueForUnregisteredScope() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"ean",
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
				},
				null, null,
				false, false, false,
				String.class, null
			);

			assertEquals(AttributeUniquenessType.NOT_UNIQUE, schema.getUniquenessType(Scope.ARCHIVED));
			assertFalse(schema.isUniqueInScope(Scope.ARCHIVED));
		}
	}

	@Nested
	@DisplayName("Filterable and Sortable")
	class FilterableAndSortable {

		@Test
		@DisplayName("should report filterable in specified scope")
		void shouldReportFilterable() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"name",
				null,
				new Scope[]{Scope.LIVE},
				null,
				false, false, false,
				String.class, null
			);

			assertTrue(schema.isFilterableInScope(Scope.LIVE));
			assertFalse(schema.isFilterableInScope(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("should report sortable in specified scope")
		void shouldReportSortable() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"name",
				null, null,
				new Scope[]{Scope.LIVE, Scope.ARCHIVED},
				false, false, false,
				String.class, null
			);

			assertTrue(schema.isSortableInScope(Scope.LIVE));
			assertTrue(schema.isSortableInScope(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("should report empty scopes when none specified")
		void shouldReportEmptyScopes() {
			final AttributeSchema schema = AttributeSchema._internalBuild("code", String.class, false);

			assertFalse(schema.isFilterableInScope(Scope.LIVE));
			assertFalse(schema.isSortableInScope(Scope.LIVE));
			assertTrue(schema.getFilterableInScopes().isEmpty());
			assertTrue(schema.getSortableInScopes().isEmpty());
		}
	}

	@Nested
	@DisplayName("Type inversion")
	class TypeInversion {

		@Test
		@DisplayName("should invert Predecessor to ReferencedEntityPredecessor")
		void shouldInvertPredecessorType() {
			final AttributeSchema schema = AttributeSchema._internalBuild("order", Predecessor.class, false);

			final AttributeSchemaContract inverted = schema.withInvertedType();

			assertSame(ReferencedEntityPredecessor.class, inverted.getType());
		}

		@Test
		@DisplayName("should invert ReferencedEntityPredecessor to Predecessor")
		void shouldInvertReferencedEntityPredecessorType() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"order", ReferencedEntityPredecessor.class, false
			);

			final AttributeSchemaContract inverted = schema.withInvertedType();

			assertSame(Predecessor.class, inverted.getType());
		}

		@Test
		@DisplayName("should throw when inverting non-predecessor type")
		void shouldThrowWhenInvertingNonPredecessorType() {
			final AttributeSchema schema = AttributeSchema._internalBuild("name", String.class, false);

			assertThrows(GenericEvitaInternalError.class, schema::withInvertedType);
		}
	}

	@Nested
	@DisplayName("Static helpers")
	class StaticHelpers {

		@Test
		@DisplayName("should convert scoped uniqueness array to enum map")
		void shouldConvertToUniquenessEnumMap() {
			final ScopedAttributeUniquenessType[] scoped = new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION),
				new ScopedAttributeUniquenessType(
					Scope.ARCHIVED, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
				)
			};

			final EnumMap<Scope, AttributeUniquenessType> result = AttributeSchema.toUniquenessEnumMap(scoped);

			assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, result.get(Scope.LIVE));
			assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE, result.get(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("should return default NOT_UNIQUE map when input null")
		void shouldReturnDefaultMapWhenNull() {
			final EnumMap<Scope, AttributeUniquenessType> result = AttributeSchema.toUniquenessEnumMap(null);

			assertEquals(AttributeUniquenessType.NOT_UNIQUE, result.get(Scope.DEFAULT_SCOPE));
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal for same construction parameters")
		void shouldBeEqualForSameParams() {
			final AttributeSchema a = AttributeSchema._internalBuild("code", String.class, false);
			final AttributeSchema b = AttributeSchema._internalBuild("code", String.class, false);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal when names differ")
		void shouldNotBeEqualWhenNamesDiffer() {
			final AttributeSchema a = AttributeSchema._internalBuild("code", String.class, false);
			final AttributeSchema b = AttributeSchema._internalBuild("name", String.class, false);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when types differ")
		void shouldNotBeEqualWhenTypesDiffer() {
			final AttributeSchema a = AttributeSchema._internalBuild("code", String.class, false);
			final AttributeSchema b = AttributeSchema._internalBuild("code", Integer.class, false);

			assertNotEquals(a, b);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToStringTests {

		@Test
		@DisplayName("should contain schema name and type in output")
		void shouldContainNameAndType() {
			final AttributeSchema schema = AttributeSchema._internalBuild("code", String.class, false);

			final String result = schema.toString();

			assertTrue(result.contains("code"), "toString should contain attribute name");
			assertTrue(result.contains("String"), "toString should contain type name");
		}

		@Test
		@DisplayName("should format uniqueness entries, not Stream reference")
		void shouldFormatUniquenessEntries() {
			final AttributeSchema schema = AttributeSchema._internalBuild(
				"ean",
				new ScopedAttributeUniquenessType[]{
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
				},
				null, null,
				false, false, false,
				String.class, null
			);

			final String result = schema.toString();

			// The key assertion: toString must NOT contain
			// Stream reference like "java.util.stream.ReferencePipeline"
			assertFalse(result.contains("ReferencePipeline"), "toString should not contain Stream object reference");
			// It should contain the actual formatted entry
			assertTrue(result.contains("LIVE"), "toString should contain scope name");
			assertTrue(result.contains("UNIQUE_WITHIN_COLLECTION"), "toString should contain uniqueness type");
		}
	}
}
