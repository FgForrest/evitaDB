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

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReferenceSchema}.
 */
@DisplayName("ReferenceSchema")
class ReferenceSchemaTest {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should build minimal reference schema")
		void shouldBuildMinimalSchema() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				"Brand",
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null
			);

			assertEquals("brand", schema.getName());
			assertEquals("Brand", schema.getReferencedEntityType());
			assertTrue(schema.isReferencedEntityTypeManaged());
			assertEquals(Cardinality.ZERO_OR_ONE, schema.getCardinality());
			assertNull(schema.getReferencedGroupType());
			assertFalse(schema.isReferencedGroupTypeManaged());
			assertNull(schema.getDescription());
			assertNull(schema.getDeprecationNotice());
		}

		@Test
		@DisplayName("should build schema with description and deprecation")
		void shouldBuildWithDescriptionAndDeprecation() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"category",
				"Category reference",
				"Use tags instead",
				"Category",
				true,
				Cardinality.ZERO_OR_MORE,
				null,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			assertEquals("Category reference", schema.getDescription());
			assertEquals("Use tags instead", schema.getDeprecationNotice());
		}
	}

	@Nested
	@DisplayName("Indexing queries")
	class IndexingQueries {

		@Test
		@DisplayName("should report indexed in specified scope")
		void shouldReportIndexed() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				"Brand",
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null
			);

			assertTrue(schema.isIndexedInScope(Scope.LIVE));
			assertFalse(schema.isIndexedInScope(Scope.ARCHIVED));
			assertEquals(ReferenceIndexType.FOR_FILTERING, schema.getReferenceIndexType(Scope.LIVE));
			assertEquals(ReferenceIndexType.NONE, schema.getReferenceIndexType(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("should report faceted in specified scope")
		void shouldReportFaceted() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				null, null,
				"Brand",
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				new Scope[]{Scope.LIVE},
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			assertTrue(schema.isFacetedInScope(Scope.LIVE));
			assertFalse(schema.isFacetedInScope(Scope.ARCHIVED));
		}
	}

	@Nested
	@DisplayName("Name variants")
	class NameVariants {

		@Test
		@DisplayName("should generate name variants")
		void shouldGenerateNameVariants() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"productBrand",
				"Brand",
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null
			);

			assertEquals("productBrand", schema.getNameVariant(NamingConvention.CAMEL_CASE));
		}

		@Test
		@DisplayName("should generate group type name variants for non-managed types")
		void shouldGenerateGroupTypeNameVariants() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"productCategory",
				"Category",
				true,
				Cardinality.ZERO_OR_MORE,
				"CategoryGroup",
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null
			);

			// Non-managed group type should have name variants
			assertNotNull(
				schema.getGroupTypeNameVariants(s -> {
					throw new UnsupportedOperationException();
				})
			);
			assertFalse(
				schema.getGroupTypeNameVariants(s -> {
					throw new UnsupportedOperationException();
				}).isEmpty(),
				"Non-managed group type should have generated name variants"
			);
		}
	}

	@Nested
	@DisplayName("Static helpers")
	class StaticHelpers {

		@Test
		@DisplayName("should convert scoped reference index types")
		void shouldConvertToReferenceIndexEnumMap() {
			final ScopedReferenceIndexType[] scoped = new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING),
				new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING)
			};

			final Map<Scope, ReferenceIndexType> result = ReferenceSchema.toReferenceIndexEnumMap(scoped);

			assertEquals(ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, result.get(Scope.LIVE));
			assertEquals(ReferenceIndexType.FOR_FILTERING, result.get(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("should return empty map when input null")
		void shouldReturnEmptyMapWhenNull() {
			final Map<Scope, ReferenceIndexType> result = ReferenceSchema.toReferenceIndexEnumMap(null);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should convert to indexed components enum map")
		void shouldConvertToIndexedComponentsEnumMap() {
			final ScopedReferenceIndexedComponents[] input = new ScopedReferenceIndexedComponents[]{
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
				),
				new ScopedReferenceIndexedComponents(
					Scope.ARCHIVED,
					new ReferenceIndexedComponents[]{
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					}
				)
			};

			final Map<Scope, Set<ReferenceIndexedComponents>> result =
				ReferenceSchema.toIndexedComponentsEnumMap(input);

			assertEquals(2, result.size());
			assertEquals(
				EnumSet.of(ReferenceIndexedComponents.REFERENCED_ENTITY),
				result.get(Scope.LIVE)
			);
			assertEquals(
				EnumSet.of(
					ReferenceIndexedComponents.REFERENCED_ENTITY,
					ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
				),
				result.get(Scope.ARCHIVED)
			);
		}

		@Test
		@DisplayName("should return empty map when indexed components null")
		void shouldReturnEmptyMapWhenIndexedComponentsNull() {
			final Map<Scope, Set<ReferenceIndexedComponents>> result =
				ReferenceSchema.toIndexedComponentsEnumMap(null);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should generate default indexed components")
		void shouldGenerateDefaultIndexedComponents() {
			final EnumMap<Scope, ReferenceIndexType> indexedScopes = new EnumMap<>(Scope.class);
			indexedScopes.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
			indexedScopes.put(Scope.ARCHIVED, ReferenceIndexType.NONE);

			final Map<Scope, Set<ReferenceIndexedComponents>> result =
				ReferenceSchema.defaultIndexedComponents(indexedScopes);

			// Only LIVE scope should be in result (ARCHIVED has NONE)
			assertEquals(1, result.size());
			assertEquals(
				EnumSet.of(ReferenceIndexedComponents.REFERENCED_ENTITY),
				result.get(Scope.LIVE)
			);
		}

		@Test
		@DisplayName("should resolve explicit indexed components when non-null")
		void shouldResolveExplicitIndexedComponents() {
			final ScopedReferenceIndexedComponents[] explicit = new ScopedReferenceIndexedComponents[]{
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					}
				)
			};
			final EnumMap<Scope, ReferenceIndexType> indexedScopes = new EnumMap<>(Scope.class);
			indexedScopes.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);

			final Map<Scope, Set<ReferenceIndexedComponents>> result =
				ReferenceSchema.resolveIndexedComponents(explicit, indexedScopes);

			assertEquals(1, result.size());
			assertEquals(
				EnumSet.of(
					ReferenceIndexedComponents.REFERENCED_ENTITY,
					ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
				),
				result.get(Scope.LIVE)
			);
		}

		@Test
		@DisplayName("should resolve default indexed components when null")
		void shouldResolveDefaultIndexedComponentsWhenNull() {
			final EnumMap<Scope, ReferenceIndexType> indexedScopes = new EnumMap<>(Scope.class);
			indexedScopes.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);

			final Map<Scope, Set<ReferenceIndexedComponents>> result =
				ReferenceSchema.resolveIndexedComponents(null, indexedScopes);

			assertEquals(1, result.size());
			assertEquals(
				EnumSet.of(ReferenceIndexedComponents.REFERENCED_ENTITY),
				result.get(Scope.LIVE)
			);
		}
	}

	@Nested
	@DisplayName("Indexed components")
	class IndexedComponentsTests {

		@Test
		@DisplayName("should default to REFERENCED_ENTITY when no explicit components specified")
		void shouldDefaultToReferencedEntity() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				"Brand",
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null
			);

			final Set<ReferenceIndexedComponents> components = schema.getIndexedComponents(Scope.LIVE);

			assertFalse(components.isEmpty(), "Default indexed components should not be empty");
			assertTrue(
				components.contains(ReferenceIndexedComponents.REFERENCED_ENTITY),
				"Default should include REFERENCED_ENTITY"
			);
		}

		@Test
		@DisplayName("should return empty set for non-indexed scope")
		void shouldReturnEmptySetForNonIndexedScope() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				"Brand",
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null
			);

			final Set<ReferenceIndexedComponents> components = schema.getIndexedComponents(Scope.ARCHIVED);

			assertTrue(components.isEmpty(), "Non-indexed scope should return empty set");
		}

		@Test
		@DisplayName("should build with explicit indexed components")
		void shouldBuildWithExplicitComponents() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				null, null,
				"Brand",
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.LIVE,
						new ReferenceIndexedComponents[]{
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						}
					)
				},
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			final Set<ReferenceIndexedComponents> components = schema.getIndexedComponents(Scope.LIVE);

			assertEquals(2, components.size());
			assertTrue(components.contains(ReferenceIndexedComponents.REFERENCED_ENTITY));
			assertTrue(components.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY));
		}

		@Test
		@DisplayName("should build with only REFERENCED_GROUP_ENTITY component")
		void shouldBuildWithOnlyGroupComponent() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				null, null,
				"Brand",
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.LIVE,
						new ReferenceIndexedComponents[]{
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						}
					)
				},
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			final Set<ReferenceIndexedComponents> components = schema.getIndexedComponents(Scope.LIVE);

			assertEquals(1, components.size());
			assertTrue(components.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY));
			assertFalse(components.contains(ReferenceIndexedComponents.REFERENCED_ENTITY));
		}

		@Test
		@DisplayName("should return all scopes via getIndexedComponentsInScopes")
		void shouldReturnAllScopesViaMap() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				null, null,
				"Brand",
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
					new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING)
				},
				new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.LIVE,
						new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
					),
					new ScopedReferenceIndexedComponents(
						Scope.ARCHIVED,
						new ReferenceIndexedComponents[]{
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						}
					)
				},
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			final Map<Scope, Set<ReferenceIndexedComponents>> allComponents = schema.getIndexedComponentsInScopes();

			assertEquals(2, allComponents.size());
			assertEquals(1, allComponents.get(Scope.LIVE).size());
			assertEquals(2, allComponents.get(Scope.ARCHIVED).size());
		}

		@Test
		@DisplayName("should use default scope via convenience method")
		void shouldUseDefaultScopeViaConvenience() {
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				"Brand",
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
				},
				null
			);

			final Set<ReferenceIndexedComponents> components = schema.getIndexedComponents();

			assertFalse(components.isEmpty());
			assertTrue(components.contains(ReferenceIndexedComponents.REFERENCED_ENTITY));
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal for same parameters")
		void shouldBeEqual() {
			final ReferenceSchema a = createBrandRef();
			final ReferenceSchema b = createBrandRef();

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal when names differ")
		void shouldNotBeEqualWhenNamesDiffer() {
			final ReferenceSchema a = ReferenceSchema._internalBuild(
				"brand", "Brand", true,
				Cardinality.ZERO_OR_ONE,
				null, false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null
			);
			final ReferenceSchema b = ReferenceSchema._internalBuild(
				"category", "Brand", true,
				Cardinality.ZERO_OR_ONE,
				null, false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null
			);

			assertNotEquals(a, b);
		}

		private static ReferenceSchema createBrandRef() {
			return ReferenceSchema._internalBuild(
				"brand", "Brand", true,
				Cardinality.ZERO_OR_ONE,
				null, false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null
			);
		}
	}
}
