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

import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GlobalAttributeSchema}.
 */
@DisplayName("GlobalAttributeSchema")
class GlobalAttributeSchemaTest {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should build minimal schema")
		void shouldBuildMinimalSchema() {
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
				"code", String.class, false
			);

			assertEquals("code", schema.getName());
			assertSame(String.class, schema.getType());
			assertFalse(schema.isLocalized());
			assertFalse(schema.isUnique());
			assertFalse(schema.isUniqueGloballyInScope(Scope.LIVE));
		}

		@Test
		@DisplayName("should build schema with global uniqueness")
		void shouldBuildWithGlobalUniqueness() {
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
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

			assertTrue(schema.isUniqueGloballyInScope(Scope.LIVE));
			// global uniqueness implies collection-level uniqueness
			assertTrue(schema.isUniqueInScope(Scope.LIVE));
			assertTrue(schema.isUnique());
		}

		@Test
		@DisplayName("should accept custom name variants")
		void shouldAcceptCustomNameVariants() {
			final Map<NamingConvention, String> variants = NamingConvention.generate("globalCode");
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
				"globalCode", variants,
				null, null,
				null, null,
				EnumSet.noneOf(Scope.class),
				EnumSet.noneOf(Scope.class),
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
	@DisplayName("Global uniqueness queries")
	class GlobalUniquenessQueries {

		@Test
		@DisplayName("should report globally unique in specified scope")
		void shouldReportGloballyUnique() {
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
				"url",
				null,
				new ScopedGlobalAttributeUniquenessType[]{
					new ScopedGlobalAttributeUniquenessType(
						Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
					)
				},
				null, null,
				false, false, false,
				String.class, null
			);

			assertTrue(schema.isUniqueGloballyInScope(Scope.LIVE));
			assertFalse(schema.isUniqueGloballyWithinLocaleInScope(Scope.LIVE));
			assertEquals(
				GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG,
				schema.getGlobalUniquenessType(Scope.LIVE)
			);
		}

		@Test
		@DisplayName("should report globally unique within locale")
		void shouldReportGloballyUniqueWithinLocale() {
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
				"slug",
				null,
				new ScopedGlobalAttributeUniquenessType[]{
					new ScopedGlobalAttributeUniquenessType(
						Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE
					)
				},
				null, null,
				true, false, false,
				String.class, null
			);

			assertTrue(schema.isUniqueGloballyWithinLocaleInScope(Scope.LIVE));
			assertTrue(schema.isUniqueGloballyInScope(Scope.LIVE));
		}

		@Test
		@DisplayName("should return NOT_UNIQUE for unregistered scope")
		void shouldReturnNotUniqueForUnregisteredScope() {
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
				"code", String.class, false
			);

			assertEquals(GlobalAttributeUniquenessType.NOT_UNIQUE, schema.getGlobalUniquenessType(Scope.LIVE));
			assertFalse(schema.isUniqueGloballyInScope(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("should default global uniqueness to NOT_UNIQUE")
		void shouldDefaultGlobalUniquenessToNotUnique() {
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
				"code", String.class, false
			);

			assertEquals(
				GlobalAttributeUniquenessType.NOT_UNIQUE,
				schema.getGlobalUniquenessType(Scope.DEFAULT_SCOPE)
			);
		}
	}

	@Nested
	@DisplayName("Uniqueness override behavior")
	class UniquenessOverrideBehavior {

		@Test
		@DisplayName("should override isUnique when globally unique")
		void shouldOverrideIsUniqueWhenGloballyUnique() {
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
				"url",
				null,
				new ScopedGlobalAttributeUniquenessType[]{
					new ScopedGlobalAttributeUniquenessType(
						Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
					)
				},
				null, null,
				false, false, false,
				String.class, null
			);

			// isUnique() checks DEFAULT_SCOPE (=LIVE) for both local and global
			assertTrue(schema.isUnique());
		}

		@Test
		@DisplayName("should override isUniqueWithinLocale when globally unique within locale")
		void shouldOverrideIsUniqueWithinLocale() {
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
				"slug",
				null,
				new ScopedGlobalAttributeUniquenessType[]{
					new ScopedGlobalAttributeUniquenessType(
						Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE
					)
				},
				null, null,
				true, false, false,
				String.class, null
			);

			assertTrue(schema.isUniqueWithinLocale());
		}

		@Test
		@DisplayName("should propagate global uniqueness to collection-level uniqueness")
		void shouldPropagateGlobalToCollectionUniqueness() {
			// When UNIQUE_WITHIN_CATALOG is set globally,
			// verifyAndAlterUniquenessTypes should also set
			// UNIQUE_WITHIN_COLLECTION at the local level
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
				"url",
				null,
				new ScopedGlobalAttributeUniquenessType[]{
					new ScopedGlobalAttributeUniquenessType(
						Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
					)
				},
				null, null,
				false, false, false,
				String.class, null
			);

			assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, schema.getUniquenessType(Scope.LIVE));
		}
	}

	@Nested
	@DisplayName("Static helpers")
	class StaticHelpers {

		@Test
		@DisplayName("should convert scoped global uniqueness array to map")
		void shouldConvertToGlobalUniquenessEnumMap() {
			final ScopedGlobalAttributeUniquenessType[] scoped = new ScopedGlobalAttributeUniquenessType[]{
				new ScopedGlobalAttributeUniquenessType(
					Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
				)
			};

			final EnumMap<Scope, GlobalAttributeUniquenessType> result =
				GlobalAttributeSchema.toGlobalUniquenessEnumMap(scoped);

			assertEquals(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG, result.get(Scope.LIVE));
		}

		@Test
		@DisplayName("should return default NOT_UNIQUE map when null")
		void shouldReturnDefaultMapWhenNull() {
			final EnumMap<Scope, GlobalAttributeUniquenessType> result =
				GlobalAttributeSchema.toGlobalUniquenessEnumMap(null);

			assertEquals(GlobalAttributeUniquenessType.NOT_UNIQUE, result.get(Scope.DEFAULT_SCOPE));
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal for same parameters")
		void shouldBeEqual() {
			final GlobalAttributeSchema a = GlobalAttributeSchema._internalBuild("code", String.class, false);
			final GlobalAttributeSchema b = GlobalAttributeSchema._internalBuild("code", String.class, false);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not equal EntityAttributeSchema")
		void shouldNotEqualEntityAttributeSchema() {
			final GlobalAttributeSchema global = GlobalAttributeSchema._internalBuild(
				"code", String.class, false
			);
			final EntityAttributeSchema entity = EntityAttributeSchema._internalBuild(
				"code", String.class, false
			);

			assertNotEquals(global, entity);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToStringTests {

		@Test
		@DisplayName("should contain GlobalAttributeSchema prefix")
		void shouldContainPrefix() {
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
				"code", String.class, false
			);

			assertTrue(schema.toString().startsWith("GlobalAttributeSchema{"));
		}

		@Test
		@DisplayName("should format uniqueness entries, not Stream reference")
		void shouldFormatUniquenessEntries() {
			final GlobalAttributeSchema schema = GlobalAttributeSchema._internalBuild(
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

			final String result = schema.toString();

			assertFalse(result.contains("ReferencePipeline"), "toString must not contain Stream reference");
			assertTrue(result.contains("UNIQUE_WITHIN_CATALOG"), "toString should contain global uniqueness type");
		}
	}
}
