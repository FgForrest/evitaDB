/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Tests for {@link ReferenceSchema}.
 */
@DisplayName("ReferenceSchema")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(REFERENCE)
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
				NamingConvention.generate("category"),
				"Category reference",
				"Use tags instead",
				Cardinality.ZERO_OR_MORE,
				"Category",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
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
				NamingConvention.generate("brand"),
				null, null,
				Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				EnumSet.of(Scope.LIVE),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
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
				NamingConvention.generate("brand"),
				null, null,
				"Brand",
				Collections.emptyMap(),
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				Collections.emptyMap(),
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
				null,
				null, null,
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
				NamingConvention.generate("brand"),
				null, null,
				"Brand",
				Collections.emptyMap(),
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				Collections.emptyMap(),
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
				null,
				null, null,
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
				NamingConvention.generate("brand"),
				null, null,
				"Brand",
				Collections.emptyMap(),
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				Collections.emptyMap(),
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
				null,
				null, null,
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
	@DisplayName("FacetedPartially expression handling")
	class FacetedPartiallyTests {

		/**
		 * Verifies that _internalBuild with ScopedFacetedPartially produces
		 * a schema where the expression is accessible via the scope accessor.
		 */
		@Test
		@DisplayName("should build with facetedPartially expression")
		void shouldBuildWithFacetedPartially() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null,
				"Brand",
				Collections.emptyMap(),
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				Collections.emptyMap(),
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING
					)
				},
				null,
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, expression)
				},
				null, null,
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			final Expression actual = schema.getFacetedPartiallyInScope(Scope.LIVE);

			assertNotNull(actual);
			assertEquals(
				expression.toExpressionString(),
				actual.toExpressionString()
			);
		}

		/**
		 * Verifies that toFacetedPartiallyMap correctly converts a non-empty
		 * ScopedFacetedPartially array into a scope-to-expression map.
		 */
		@Test
		@DisplayName("should convert scoped facetedPartially to map")
		void shouldConvertScopedFacetedPartiallyToMap() {
			final Expression liveExpr = ExpressionFactory.parse("1 > 0");
			final Expression archivedExpr = ExpressionFactory.parse("2 > 1");
			final ScopedFacetedPartially[] input = new ScopedFacetedPartially[]{
				new ScopedFacetedPartially(Scope.LIVE, liveExpr),
				new ScopedFacetedPartially(Scope.ARCHIVED, archivedExpr)
			};

			final Map<Scope, Expression> result =
				ReferenceSchema.toFacetedPartiallyMap(input);

			assertEquals(2, result.size());
			assertEquals(
				liveExpr.toExpressionString(),
				result.get(Scope.LIVE).toExpressionString()
			);
			assertEquals(
				archivedExpr.toExpressionString(),
				result.get(Scope.ARCHIVED).toExpressionString()
			);
		}

		/**
		 * Verifies that toFacetedPartiallyMap returns an empty map for null
		 * and for an empty array.
		 */
		@Test
		@DisplayName("should return empty map for null or empty array")
		void shouldConvertNullOrEmptyToEmptyMap() {
			final Map<Scope, Expression> fromNull =
				ReferenceSchema.toFacetedPartiallyMap(null);
			final Map<Scope, Expression> fromEmpty =
				ReferenceSchema.toFacetedPartiallyMap(ScopedFacetedPartially.EMPTY);

			assertTrue(fromNull.isEmpty());
			assertTrue(fromEmpty.isEmpty());
		}

		/**
		 * Verifies that entries with null expressions are filtered out
		 * in toFacetedPartiallyMap.
		 */
		@Test
		@DisplayName("should filter null expressions in toFacetedPartiallyMap")
		void shouldFilterNullExpressionsInToFacetedPartiallyMap() {
			final Expression liveExpr = ExpressionFactory.parse("1 > 0");
			final ScopedFacetedPartially[] input = new ScopedFacetedPartially[]{
				new ScopedFacetedPartially(Scope.LIVE, liveExpr),
				new ScopedFacetedPartially(Scope.ARCHIVED, null)
			};

			final Map<Scope, Expression> result =
				ReferenceSchema.toFacetedPartiallyMap(input);

			assertEquals(1, result.size());
			assertNotNull(result.get(Scope.LIVE));
			assertNull(result.get(Scope.ARCHIVED));
		}

		/**
		 * Verifies that schemas differing only in facetedPartially expressions
		 * are not considered equal.
		 */
		@Test
		@DisplayName("should include facetedPartially in equality check")
		void shouldIncludeFacetedPartiallyInEquality() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			final ReferenceSchema withPartially = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null,
				"Brand",
				Collections.emptyMap(),
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				Collections.emptyMap(),
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING
					)
				},
				null,
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, expression)
				},
				null, null,
				Collections.emptyMap(),
				Collections.emptyMap()
			);
			final ReferenceSchema withoutPartially = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null,
				"Brand",
				Collections.emptyMap(),
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				Collections.emptyMap(),
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING
					)
				},
				null,
				new Scope[]{Scope.LIVE},
				null,
				null, null,
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			assertNotEquals(withPartially, withoutPartially);
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

	@Nested
	@DisplayName("Validation")
	class Validation {

		/**
		 * Verifies that validate() reports an error when facetedPartially is configured
		 * for a scope that is not faceted.
		 */
		@Test
		@DisplayName("should fail when facetedPartially set for non-faceted scope")
		void shouldFailValidationWhenFacetedPartiallySetForNonFacetedScope() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			// Create schema with facetedPartially in LIVE but NOT faceted in LIVE
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				false,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(), // NOT faceted in any scope
				Map.of(Scope.LIVE, expression), // but facetedPartially is set for LIVE
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			final EntitySchema entitySchema = EntitySchema._internalBuild("TestEntity");
			final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
				"testCatalog",
				NamingConvention.generate("testCatalog"),
				EnumSet.allOf(CatalogEvolutionMode.class),
				new EntitySchemaProvider() {
					@Nonnull
					@Override
					public Collection<EntitySchemaContract> getEntitySchemas() {
						return List.of(entitySchema);
					}

					@Nonnull
					@Override
					public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
						return Optional.empty();
					}
				}
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> schema.validate(catalogSchema, entitySchema)
			);
			assertTrue(
				ex.getMessage().contains("FacetedPartially expression is defined for scope"),
				"Expected error about facetedPartially for non-faceted scope, got: " + ex.getMessage()
			);
		}

		/**
		 * Verifies that validate() reports an error when bucketedPartially is configured
		 * for a scope that is not bucketed.
		 */
		@Test
		@DisplayName("should fail when bucketedPartially set for non-bucketed scope")
		void shouldFailValidationWhenBucketedPartiallySetForNonBucketedScope() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			// Create schema with bucketedPartially in LIVE but NOT bucketed in LIVE
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				false,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				Collections.emptyMap(), // NOT bucketed in any scope
				Map.of(Scope.LIVE, expression), // but bucketedPartially is set for LIVE
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			final EntitySchema entitySchema = EntitySchema._internalBuild("TestEntity");
			final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
				"testCatalog",
				NamingConvention.generate("testCatalog"),
				EnumSet.allOf(CatalogEvolutionMode.class),
				new EntitySchemaProvider() {
					@Nonnull
					@Override
					public Collection<EntitySchemaContract> getEntitySchemas() {
						return List.of(entitySchema);
					}

					@Nonnull
					@Override
					public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
						return Optional.empty();
					}
				}
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> schema.validate(catalogSchema, entitySchema)
			);
			assertTrue(
				ex.getMessage().contains("BucketedPartially expression is defined for scope"),
				"Expected error about bucketedPartially for non-bucketed scope, got: " + ex.getMessage()
			);
		}

		/**
		 * Verifies that validateScopeSettings rejects a bucketed scope that is not indexed.
		 */
		@Test
		@DisplayName("should reject bucketed in non-indexed scope")
		void shouldRejectBucketedInNonIndexedScope() {
			final EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap = new EnumMap<>(Scope.class);
			bucketedMap.put(Scope.LIVE, Map.of("hist", HistogramIndexDefinition.of("hist", null)));

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> ReferenceSchema.validateScopeSettings(
					Collections.emptySet(),
					bucketedMap,
					Map.of(Scope.LIVE, ReferenceIndexType.NONE),
					null
				)
			);
		}
	}

	@Nested
	@DisplayName("Bucketed tests")
	class BucketedTests {

		/**
		 * Verifies that building a {@link ReferenceSchema} with a {@link HistogramIndexDefinition}
		 * produces the expected bucketed configuration.
		 */
		@Test
		@DisplayName("should build with bucketed histogram definition")
		void shouldBuildWithHistogramIndexDefinition() {
			final Expression valueExpr = ExpressionFactory.parse("$price * $quantity");
			final EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap = new EnumMap<>(Scope.class);
			bucketedMap.put(
				Scope.LIVE,
				Map.of("priceHistogram", HistogramIndexDefinition.of("priceHistogram", valueExpr))
			);

			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				bucketedMap,
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			assertTrue(schema.isBucketedInScope(Scope.LIVE));
			assertFalse(schema.isBucketedInScope(Scope.ARCHIVED));
			assertTrue(schema.isBucketedInAnyScope());
			assertEquals(
				"priceHistogram",
				schema.getHistogramIndexDefinition(Scope.LIVE, "priceHistogram").nameOfTheIndex()
			);
			assertEquals(
				valueExpr.toExpressionString(),
				schema.getHistogramIndexDefinition(Scope.LIVE, "priceHistogram").valueExpression().toExpressionString()
			);
			assertEquals(Set.of(Scope.LIVE), schema.getBucketedInScopes());
			assertEquals(1, schema.getAllHistogramIndexDefinitions().size());
		}

		/**
		 * Verifies that a {@link ReferenceSchema} can be built with a bucketedPartially expression.
		 */
		@Test
		@DisplayName("should build with bucketedPartially expression")
		void shouldBuildWithBucketedPartiallyExpression() {
			final Expression partiallyExpr = ExpressionFactory.parse("$status == 1");
			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null,
				"Brand",
				Collections.emptyMap(),
				true,
				Cardinality.ZERO_OR_ONE,
				null,
				Collections.emptyMap(),
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				null,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, "hist", null, null)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, partiallyExpr)
				},
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			assertNotNull(schema.getBucketedPartiallyInScope(Scope.LIVE));
			assertEquals(
				partiallyExpr.toExpressionString(),
				schema.getBucketedPartiallyInScope(Scope.LIVE).toExpressionString()
			);
			assertEquals(
				partiallyExpr.toExpressionString(),
				schema.getBucketedPartially().toExpressionString()
			);
			assertEquals(1, schema.getBucketedPartiallyInScopes().size());
		}

		/**
		 * Verifies that a {@link HistogramIndexDefinition} with a null valueExpression
		 * can be constructed and used correctly.
		 */
		@Test
		@DisplayName("should build with null value expression")
		void shouldBuildWithNullValueExpression() {
			final EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap = new EnumMap<>(Scope.class);
			bucketedMap.put(Scope.LIVE, Map.of("hist", HistogramIndexDefinition.of("hist", null)));

			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				bucketedMap,
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			assertTrue(schema.isBucketedInScope(Scope.LIVE));
			assertNull(schema.getHistogramIndexDefinition(Scope.LIVE, "hist").valueExpression());
		}

		/**
		 * Verifies that a minimal {@link ReferenceSchema} built without bucketed arguments
		 * returns empty bucketed state.
		 */
		@Test
		@DisplayName("should return empty bucketed when not configured")
		void shouldReturnEmptyBucketedWhenNotConfigured() {
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

			assertFalse(schema.isBucketedInScope(Scope.LIVE));
			assertFalse(schema.isBucketedInAnyScope());
			assertTrue(schema.getBucketedInScopes().isEmpty());
			assertTrue(schema.getAllHistogramIndexDefinitions().isEmpty());
			assertNull(schema.getBucketedPartiallyInScope(Scope.LIVE));
			assertTrue(schema.getBucketedPartiallyInScopes().isEmpty());
		}

		/**
		 * Verifies that multiple histogram definitions can coexist within a single scope
		 * and are individually accessible by name.
		 */
		@Test
		@DisplayName("should support multiple histograms per scope")
		void shouldSupportMultipleHistogramsPerScope() {
			final Expression priceExpr = ExpressionFactory.parse("$price");
			final Expression quantityExpr = ExpressionFactory.parse("$quantity");
			final EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap = new EnumMap<>(Scope.class);
			bucketedMap.put(
				Scope.LIVE,
				Map.of(
					"priceHistogram", HistogramIndexDefinition.of("priceHistogram", priceExpr),
					"quantityHistogram", HistogramIndexDefinition.of("quantityHistogram", quantityExpr)
				)
			);

			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				bucketedMap,
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			assertTrue(schema.isBucketedInScope(Scope.LIVE));
			assertEquals(2, schema.getHistogramIndexDefinitions(Scope.LIVE).size());
			assertEquals(1, schema.getAllHistogramIndexDefinitions().size());

			final HistogramIndexDefinition priceDef =
				schema.getHistogramIndexDefinition(Scope.LIVE, "priceHistogram");
			assertNotNull(priceDef);
			assertEquals("priceHistogram", priceDef.nameOfTheIndex());
			assertEquals(
				priceExpr.toExpressionString(),
				priceDef.valueExpression().toExpressionString()
			);

			final HistogramIndexDefinition quantityDef =
				schema.getHistogramIndexDefinition(Scope.LIVE, "quantityHistogram");
			assertNotNull(quantityDef);
			assertEquals("quantityHistogram", quantityDef.nameOfTheIndex());
			assertEquals(
				quantityExpr.toExpressionString(),
				quantityDef.valueExpression().toExpressionString()
			);

			assertNull(schema.getHistogramIndexDefinition(Scope.LIVE, "nonExistent"));
		}

		/**
		 * Verifies that schemas differing only in bucketed definitions are not equal.
		 */
		@Test
		@DisplayName("should include bucketed in equality check")
		void shouldIncludeBucketedInEquality() {
			final EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap = new EnumMap<>(Scope.class);
			bucketedMap.put(Scope.LIVE, Map.of("hist", HistogramIndexDefinition.of("hist", null)));

			final ReferenceSchema withBucketed = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				bucketedMap,
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);
			final ReferenceSchema withoutBucketed = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			assertNotEquals(withBucketed, withoutBucketed);

			// Two identical bucketed schemas should be equal
			final ReferenceSchema withBucketed2 = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				bucketedMap,
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);
			assertEquals(withBucketed, withBucketed2);
			assertEquals(withBucketed.hashCode(), withBucketed2.hashCode());
		}

		/**
		 * Verifies that {@link ReferenceSchema} filters out empty inner maps from the bucketed
		 * scopes, so that `isBucketedInScope` returns false for a scope with no actual
		 * histogram definitions.
		 */
		@Test
		@DisplayName("should filter out empty inner maps in bucketed scopes")
		void shouldFilterOutEmptyInnerMapsInBucketedScopes() {
			final EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap = new EnumMap<>(Scope.class);
			bucketedMap.put(Scope.LIVE, Collections.emptyMap());

			final ReferenceSchema schema = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				bucketedMap,
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			assertFalse(
				schema.isBucketedInScope(Scope.LIVE),
				"Scope with empty inner map should not be considered bucketed"
			);
			assertTrue(
				schema.getAllHistogramIndexDefinitions().isEmpty(),
				"Empty inner maps should be filtered from histogram definitions"
			);
		}

		@Test
		@DisplayName("should include bucketedPartially in equality check")
		void shouldIncludeBucketedPartiallyInEquality() {
			final Expression expression = ExpressionFactory.parse("$status == 1");
			final EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap = new EnumMap<>(Scope.class);
			bucketedMap.put(Scope.LIVE, Map.of("hist", HistogramIndexDefinition.of("hist", null)));

			final ReferenceSchema withPartially = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				bucketedMap,
				Map.of(Scope.LIVE, expression),
				Collections.emptyMap(),
				Collections.emptyMap()
			);
			final ReferenceSchema withoutPartially = ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				bucketedMap,
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			assertNotEquals(withPartially, withoutPartially);
		}
	}

	@Nested
	@DisplayName("Bucketed static helpers")
	class BucketedStaticHelpers {

		/**
		 * Verifies that toBucketedHistogramMap correctly converts a multi-element array.
		 */
		@Test
		@DisplayName("should convert scoped bucketed histogram to map")
		void shouldConvertScopedHistogramIndexDefinitionToMap() {
			final Expression expr = ExpressionFactory.parse("$price");
			final ScopedHistogramIndexDefinition[] input = new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, "liveHist", expr, null),
				new ScopedHistogramIndexDefinition(Scope.ARCHIVED, "archivedHist", null, null)
			};

			final Map<Scope, Map<String, HistogramIndexDefinition>> result =
				ReferenceSchema.toBucketedHistogramMap(input);

			assertEquals(2, result.size());
			assertEquals(1, result.get(Scope.LIVE).size());
			assertEquals("liveHist", result.get(Scope.LIVE).get("liveHist").nameOfTheIndex());
			assertEquals(
				expr.toExpressionString(),
				result.get(Scope.LIVE).get("liveHist").valueExpression().toExpressionString()
			);
			assertEquals(1, result.get(Scope.ARCHIVED).size());
			assertEquals("archivedHist", result.get(Scope.ARCHIVED).get("archivedHist").nameOfTheIndex());
			assertNull(result.get(Scope.ARCHIVED).get("archivedHist").valueExpression());

			// null and empty input should return empty maps
			assertTrue(ReferenceSchema.toBucketedHistogramMap(null).isEmpty());
			assertTrue(ReferenceSchema.toBucketedHistogramMap(ScopedHistogramIndexDefinition.EMPTY).isEmpty());
		}

		/**
		 * Verifies that toBucketedHistogramMap groups multiple histograms under the same scope
		 * when the input contains multiple entries for the same scope with different names.
		 */
		@Test
		@DisplayName("should group multiple histograms per scope in toBucketedHistogramMap")
		void shouldGroupMultipleHistogramsPerScopeInToBucketedHistogramMap() {
			final Expression priceExpr = ExpressionFactory.parse("$price");
			final Expression quantityExpr = ExpressionFactory.parse("$quantity");
			final ScopedHistogramIndexDefinition[] input = new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHist", priceExpr, null),
				new ScopedHistogramIndexDefinition(Scope.LIVE, "quantityHist", quantityExpr, null)
			};

			final Map<Scope, Map<String, HistogramIndexDefinition>> result =
				ReferenceSchema.toBucketedHistogramMap(input);

			assertEquals(1, result.size());
			final Map<String, HistogramIndexDefinition> liveHistograms = result.get(Scope.LIVE);
			assertNotNull(liveHistograms);
			assertEquals(2, liveHistograms.size());
			assertEquals("priceHist", liveHistograms.get("priceHist").nameOfTheIndex());
			assertEquals(
				priceExpr.toExpressionString(),
				liveHistograms.get("priceHist").valueExpression().toExpressionString()
			);
			assertEquals("quantityHist", liveHistograms.get("quantityHist").nameOfTheIndex());
			assertEquals(
				quantityExpr.toExpressionString(),
				liveHistograms.get("quantityHist").valueExpression().toExpressionString()
			);
		}

		/**
		 * Verifies that {@link ReferenceSchema#toBucketedHistogramMap(ScopedHistogramIndexDefinition[])}
		 * throws {@link InvalidSchemaMutationException} when two entries share the same scope and name.
		 */
		@Test
		@DisplayName("should reject duplicate histogram name in same scope")
		void shouldRejectDuplicateHistogramNameInSameScope() {
			final Expression expr1 = ExpressionFactory.parse("$price");
			final Expression expr2 = ExpressionFactory.parse("$quantity");
			final ScopedHistogramIndexDefinition[] input = new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, "price", expr1, null),
				new ScopedHistogramIndexDefinition(Scope.LIVE, "price", expr2, null)
			};

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> ReferenceSchema.toBucketedHistogramMap(input)
			);
		}

		/**
		 * Verifies that toBucketedPartiallyMap correctly converts arrays, filters nulls,
		 * and handles null/empty input.
		 */
		@Test
		@DisplayName("should convert scoped bucketed partially to map")
		void shouldConvertScopedBucketedPartiallyToMap() {
			final Expression liveExpr = ExpressionFactory.parse("$status == 1");
			final ScopedBucketedPartially[] input = new ScopedBucketedPartially[]{
				new ScopedBucketedPartially(Scope.LIVE, liveExpr),
				new ScopedBucketedPartially(Scope.ARCHIVED, null) // should be filtered
			};

			final Map<Scope, Expression> result = ReferenceSchema.toBucketedPartiallyMap(input);

			assertEquals(1, result.size());
			assertNotNull(result.get(Scope.LIVE));
			assertNull(result.get(Scope.ARCHIVED));

			// null and empty input should return empty maps
			assertTrue(ReferenceSchema.toBucketedPartiallyMap(null).isEmpty());
			assertTrue(ReferenceSchema.toBucketedPartiallyMap(ScopedBucketedPartially.EMPTY).isEmpty());
		}
	}

	@Nested
	@DisplayName("getHistogramIndexDefinitionByName (name-variant lookup)")
	class HistogramIndexDefinitionByNameTest {

		/**
		 * Verifies that a histogram registered under a camelCase canonical name is resolvable via
		 * every known {@link NamingConvention} using the variant produced by
		 * {@link NamingConvention#generate(String)}. This is the core happy-path test for the new
		 * variant-indexed lookup — it exercises every convention in one test so the assertion does
		 * not silently drift if a new convention is added.
		 */
		@Test
		@DisplayName("should resolve histogram by every naming convention")
		void shouldResolveHistogramByEveryNamingConvention() {
			final String canonicalName = "priceHistogram";
			final ReferenceSchema schema = createSchemaWithLiveHistograms(canonicalName);
			final Map<NamingConvention, String> expectedVariants = NamingConvention.generate(canonicalName);

			for (final NamingConvention convention : NamingConvention.values()) {
				final String variant = expectedVariants.get(convention);
				final Optional<HistogramIndexDefinition> found =
					schema.getHistogramIndexDefinitionByName(Scope.LIVE, variant, convention);

				assertTrue(
					found.isPresent(),
					"Lookup for variant `" + variant + "` in convention " + convention + " must succeed"
				);
				assertEquals(
					canonicalName, found.get().nameOfTheIndex(),
					"Lookup for variant `" + variant + "` must return the canonical histogram"
				);
			}
		}

		/**
		 * Verifies that looking up a histogram via the correct variant string but the wrong
		 * naming convention returns empty. For example, the snake_case variant "price_histogram"
		 * under convention KEBAB_CASE must not match anything. This pins down the array-indexed
		 * nature of the lookup (variant string → per-convention slot), which is critical because
		 * the canonical name is often identical in multiple conventions (e.g. single-word names).
		 */
		@Test
		@DisplayName("should return empty when convention does not match variant slot")
		void shouldReturnEmptyWhenConventionDoesNotMatchVariantSlot() {
			final String canonicalName = "priceHistogram";
			final ReferenceSchema schema = createSchemaWithLiveHistograms(canonicalName);
			final Map<NamingConvention, String> expectedVariants = NamingConvention.generate(canonicalName);
			final String snakeVariant = expectedVariants.get(NamingConvention.SNAKE_CASE);

			// the snake_case variant stored under the SNAKE_CASE slot — it must NOT resolve when
			// the caller asks for it under the KEBAB_CASE slot (provided the two casings differ).
			if (!snakeVariant.equals(expectedVariants.get(NamingConvention.KEBAB_CASE))) {
				final Optional<HistogramIndexDefinition> found =
					schema.getHistogramIndexDefinitionByName(Scope.LIVE, snakeVariant, NamingConvention.KEBAB_CASE);

				assertTrue(
					found.isEmpty(),
					"Looking up snake_case variant under KEBAB_CASE slot must return empty"
				);
			}
		}

		/**
		 * Verifies that a lookup in a scope that is not bucketed at all returns empty (not null
		 * and no NPE). This covers the early-exit branch inside the implementation when the
		 * per-scope map is absent.
		 */
		@Test
		@DisplayName("should return empty for scope without any histograms")
		void shouldReturnEmptyForScopeWithoutAnyHistograms() {
			final ReferenceSchema schema = createSchemaWithLiveHistograms("priceHistogram");

			final Optional<HistogramIndexDefinition> found = schema.getHistogramIndexDefinitionByName(
				Scope.ARCHIVED, "priceHistogram", NamingConvention.CAMEL_CASE
			);

			assertNotNull(found, "Method must never return a null Optional");
			assertTrue(found.isEmpty(), "Looking up in a non-bucketed scope must return empty");
		}

		/**
		 * Verifies that an unknown name returns empty. This exercises the case where the scope
		 * map exists but does not contain the requested key.
		 */
		@Test
		@DisplayName("should return empty for unknown histogram name")
		void shouldReturnEmptyForUnknownHistogramName() {
			final ReferenceSchema schema = createSchemaWithLiveHistograms("priceHistogram");

			final Optional<HistogramIndexDefinition> found = schema.getHistogramIndexDefinitionByName(
				Scope.LIVE, "completelyUnknown", NamingConvention.CAMEL_CASE
			);

			assertTrue(found.isEmpty(), "Unknown histogram name must resolve to empty");
		}

		/**
		 * Verifies that two histograms sharing a scope are each independently resolvable by their
		 * own variants and do not collide in the name-variant index. The variant strings of the
		 * two distinct canonical names must route to different definitions.
		 */
		@Test
		@DisplayName("should resolve each of two same-scope histograms independently")
		void shouldResolveEachOfTwoSameScopeHistogramsIndependently() {
			final Expression priceExpr = ExpressionFactory.parse("$price");
			final Expression quantityExpr = ExpressionFactory.parse("$quantity");
			final EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap = new EnumMap<>(Scope.class);
			bucketedMap.put(
				Scope.LIVE,
				Map.of(
					"priceHistogram", HistogramIndexDefinition.of("priceHistogram", priceExpr),
					"quantityHistogram", HistogramIndexDefinition.of("quantityHistogram", quantityExpr)
				)
			);

			final ReferenceSchema schema = buildBucketedSchema(bucketedMap);

			final String priceKebab = NamingConvention.generate("priceHistogram").get(NamingConvention.KEBAB_CASE);
			final String quantityKebab = NamingConvention.generate("quantityHistogram").get(NamingConvention.KEBAB_CASE);

			final Optional<HistogramIndexDefinition> priceFound =
				schema.getHistogramIndexDefinitionByName(Scope.LIVE, priceKebab, NamingConvention.KEBAB_CASE);
			final Optional<HistogramIndexDefinition> quantityFound =
				schema.getHistogramIndexDefinitionByName(Scope.LIVE, quantityKebab, NamingConvention.KEBAB_CASE);

			assertTrue(priceFound.isPresent(), "Price histogram must be resolvable by its kebab variant");
			assertEquals("priceHistogram", priceFound.get().nameOfTheIndex());
			assertEquals(
				priceExpr.toExpressionString(),
				priceFound.get().valueExpression().toExpressionString(),
				"Resolved price histogram must carry the price expression"
			);

			assertTrue(quantityFound.isPresent(), "Quantity histogram must be resolvable by its kebab variant");
			assertEquals("quantityHistogram", quantityFound.get().nameOfTheIndex());
			assertEquals(
				quantityExpr.toExpressionString(),
				quantityFound.get().valueExpression().toExpressionString(),
				"Resolved quantity histogram must carry the quantity expression"
			);

			// sanity: neither variant cross-resolves to the other definition
			assertNotEquals(priceFound.get(), quantityFound.get());
		}

		/**
		 * Verifies that a multi-scope schema keeps per-scope variant indexes isolated — a histogram
		 * defined only in LIVE must not leak into an ARCHIVED lookup and vice versa.
		 */
		@Test
		@DisplayName("should keep per-scope histogram indexes isolated")
		void shouldKeepPerScopeHistogramIndexesIsolated() {
			final EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap = new EnumMap<>(Scope.class);
			bucketedMap.put(Scope.LIVE, Map.of("liveOnly", HistogramIndexDefinition.of("liveOnly", null)));
			bucketedMap.put(Scope.ARCHIVED, Map.of("archivedOnly", HistogramIndexDefinition.of("archivedOnly", null)));

			final ReferenceSchema schema = buildBucketedSchemaForScopes(bucketedMap, Scope.LIVE, Scope.ARCHIVED);

			// liveOnly must resolve only in LIVE
			assertTrue(
				schema.getHistogramIndexDefinitionByName(
					Scope.LIVE, "liveOnly", NamingConvention.CAMEL_CASE
				).isPresent()
			);
			assertTrue(
				schema.getHistogramIndexDefinitionByName(
					Scope.ARCHIVED, "liveOnly", NamingConvention.CAMEL_CASE
				).isEmpty(),
				"liveOnly histogram must NOT leak into ARCHIVED scope"
			);

			// archivedOnly must resolve only in ARCHIVED
			assertTrue(
				schema.getHistogramIndexDefinitionByName(
					Scope.ARCHIVED, "archivedOnly", NamingConvention.CAMEL_CASE
				).isPresent()
			);
			assertTrue(
				schema.getHistogramIndexDefinitionByName(
					Scope.LIVE, "archivedOnly", NamingConvention.CAMEL_CASE
				).isEmpty(),
				"archivedOnly histogram must NOT leak into LIVE scope"
			);
		}

		/**
		 * Verifies the critical cross-scope invariant: two different histograms registered under the
		 * same canonical name in different scopes must each resolve to their own definition — not
		 * to a merged / either-one-wins result. The value expression is the distinguishing fact.
		 */
		@Test
		@DisplayName("should resolve same-name histograms in different scopes to distinct definitions")
		void shouldResolveSameNameHistogramsInDifferentScopesToDistinctDefinitions() {
			final Expression liveExpr = ExpressionFactory.parse("$price");
			final Expression archivedExpr = ExpressionFactory.parse("$quantity");

			final EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap = new EnumMap<>(Scope.class);
			bucketedMap.put(Scope.LIVE, Map.of("hist", HistogramIndexDefinition.of("hist", liveExpr)));
			bucketedMap.put(Scope.ARCHIVED, Map.of("hist", HistogramIndexDefinition.of("hist", archivedExpr)));

			final ReferenceSchema schema = buildBucketedSchemaForScopes(bucketedMap, Scope.LIVE, Scope.ARCHIVED);

			final Optional<HistogramIndexDefinition> inLive =
				schema.getHistogramIndexDefinitionByName(Scope.LIVE, "hist", NamingConvention.CAMEL_CASE);
			final Optional<HistogramIndexDefinition> inArchived =
				schema.getHistogramIndexDefinitionByName(Scope.ARCHIVED, "hist", NamingConvention.CAMEL_CASE);

			assertTrue(inLive.isPresent(), "LIVE `hist` must resolve");
			assertTrue(inArchived.isPresent(), "ARCHIVED `hist` must resolve");

			assertEquals(
				liveExpr.toExpressionString(),
				inLive.get().valueExpression().toExpressionString(),
				"LIVE `hist` must carry the live-scope expression"
			);
			assertEquals(
				archivedExpr.toExpressionString(),
				inArchived.get().valueExpression().toExpressionString(),
				"ARCHIVED `hist` must carry the archived-scope expression"
			);
			assertNotEquals(
				inLive.get(), inArchived.get(),
				"Same canonical name in different scopes must not collapse to one definition"
			);
		}

		/**
		 * Verifies consistency between the scope-only lookup and the variant-aware lookup: for a
		 * histogram's canonical name under the CAMEL_CASE convention, both accessors must return
		 * the exact same {@link HistogramIndexDefinition} instance.
		 */
		@Test
		@DisplayName("should agree with scope-only lookup for canonical camelCase name")
		void shouldAgreeWithScopeOnlyLookupForCanonicalCamelCaseName() {
			final String canonicalName = "priceHistogram";
			final ReferenceSchema schema = createSchemaWithLiveHistograms(canonicalName);

			final HistogramIndexDefinition viaScopeOnly =
				schema.getHistogramIndexDefinition(Scope.LIVE, canonicalName);
			final Optional<HistogramIndexDefinition> viaVariant =
				schema.getHistogramIndexDefinitionByName(Scope.LIVE, canonicalName, NamingConvention.CAMEL_CASE);

			assertNotNull(viaScopeOnly, "scope-only lookup must find the histogram");
			assertTrue(viaVariant.isPresent(), "variant-aware lookup must find the histogram");
			assertSame(
				viaScopeOnly, viaVariant.get(),
				"Both lookup paths must return the same definition instance"
			);
		}

		/**
		 * Builds a minimally-valid {@link ReferenceSchema} with a single LIVE-scope histogram that
		 * uses {@link HistogramIndexDefinition#of(String, Expression)} under the given canonical name.
		 *
		 * @param canonicalName the canonical histogram name to register under LIVE
		 * @return the newly built schema
		 */
		@Nonnull
		private static ReferenceSchema createSchemaWithLiveHistograms(@Nonnull String canonicalName) {
			final EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap = new EnumMap<>(Scope.class);
			bucketedMap.put(
				Scope.LIVE,
				Map.of(canonicalName, HistogramIndexDefinition.of(canonicalName, null))
			);
			return buildBucketedSchema(bucketedMap);
		}

		/**
		 * Builds a single-LIVE-scope bucketed schema from a pre-built bucketed map. The reference is
		 * `brand`, zero-or-one cardinality, indexed FOR_FILTERING in LIVE only.
		 *
		 * @param bucketedMap the per-scope bucketed histogram map
		 * @return the newly built schema
		 */
		@Nonnull
		private static ReferenceSchema buildBucketedSchema(
			@Nonnull EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap
		) {
			return ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				bucketedMap,
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);
		}

		/**
		 * Builds a multi-scope bucketed schema. Every provided scope is indexed FOR_FILTERING so
		 * that the bucketed state passes validation. The bucketed map is passed through verbatim.
		 *
		 * @param bucketedMap the per-scope bucketed histogram map
		 * @param scopes      the scopes to register as indexed FOR_FILTERING
		 * @return the newly built schema
		 */
		@Nonnull
		private static ReferenceSchema buildBucketedSchemaForScopes(
			@Nonnull EnumMap<Scope, Map<String, HistogramIndexDefinition>> bucketedMap,
			@Nonnull Scope... scopes
		) {
			final EnumMap<Scope, ReferenceIndexType> indexedInScopes = new EnumMap<>(Scope.class);
			for (final Scope scope : scopes) {
				indexedInScopes.put(scope, ReferenceIndexType.FOR_FILTERING);
			}
			return ReferenceSchema._internalBuild(
				"brand",
				NamingConvention.generate("brand"),
				null, null, Cardinality.ZERO_OR_ONE,
				"Brand",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				indexedInScopes,
				ReferenceSchema.defaultIndexedComponents(indexedInScopes),
				Collections.emptySet(),
				Collections.emptyMap(),
				bucketedMap,
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);
		}
	}
}
