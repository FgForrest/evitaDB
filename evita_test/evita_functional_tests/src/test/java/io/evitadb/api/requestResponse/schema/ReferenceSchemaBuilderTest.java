/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.AbstractReferenceSchemaBuilder.ReferenceSchemaBuilderResult;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.test.Entities;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReferenceSchemaBuilder} verifying description, deprecation,
 * group type operations, scope-based indexing and faceting, reference attributes,
 * sortable attribute compounds, and mutation generation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ReferenceSchemaBuilder")
class ReferenceSchemaBuilderTest {

	private EntitySchema productSchema;
	private CatalogSchema catalogSchema;

	@BeforeEach
	void setUp() {
		this.productSchema = EntitySchema._internalBuild(Entities.PRODUCT);
		this.catalogSchema = CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG,
			NamingConvention.generate(APITestConstants.TEST_CATALOG),
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return List.of(
						ReferenceSchemaBuilderTest.this.productSchema
					);
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(
					@Nonnull String entityType
				) {
					if (entityType.equals(
						ReferenceSchemaBuilderTest.this.productSchema.getName()
					)) {
						return of(
							ReferenceSchemaBuilderTest.this.productSchema
						);
					}
					return empty();
				}
			}
		);
	}

	/**
	 * Creates a fresh entity schema builder for the product entity.
	 *
	 * @return new entity schema builder instance
	 */
	@Nonnull
	private EntitySchemaBuilder createEntitySchemaBuilder() {
		return new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);
	}

	/**
	 * Builds a reference schema by configuring a managed reference on the product schema
	 * and extracting the resulting reference schema.
	 *
	 * @param referenceName the name of the reference
	 * @param entityType the referenced entity type
	 * @param cardinality the cardinality
	 * @param whichIs the consumer to configure the reference
	 * @return the built reference schema
	 */
	@Nonnull
	private ReferenceSchemaContract buildReference(
		@Nonnull String referenceName,
		@Nonnull String entityType,
		@Nonnull Cardinality cardinality,
		@Nonnull Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> whichIs
	) {
		final EntitySchemaContract schema = createEntitySchemaBuilder()
			.withReferenceToEntity(referenceName, entityType, cardinality, whichIs)
			.toInstance();
		return schema.getReference(referenceName).orElseThrow();
	}

	@Nested
	@DisplayName("Description and deprecation")
	class DescriptionAndDeprecation {

		@Test
		@DisplayName("should set description on reference")
		void shouldSetDescription() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.withDescription("Brand reference")
			);

			assertEquals("Brand reference", ref.getDescription());
		}

		@Test
		@DisplayName("should clear description by setting null")
		void shouldClearDescription() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.withDescription("Brand reference")
					.withDescription(null)
			);

			assertNull(ref.getDescription());
		}

		@Test
		@DisplayName("should set deprecation notice")
		void shouldSetDeprecationNotice() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.deprecated("Use category instead")
			);

			assertEquals("Use category instead", ref.getDeprecationNotice());
		}

		@Test
		@DisplayName("should remove deprecation notice")
		void shouldRemoveDeprecation() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.deprecated("Use category instead")
					.notDeprecatedAnymore()
			);

			assertNull(ref.getDeprecationNotice());
		}

		@Test
		@DisplayName("should combine description and deprecation")
		void shouldCombineDescriptionAndDeprecation() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.withDescription("Brand reference")
					.deprecated("Will be removed")
			);

			assertAll(
				() -> assertEquals("Brand reference", ref.getDescription()),
				() -> assertEquals("Will be removed", ref.getDeprecationNotice())
			);
		}
	}

	@Nested
	@DisplayName("Group type operations")
	class GroupTypeOperations {

		@Test
		@DisplayName("should set external group type")
		void shouldSetExternalGroupType() {
			final ReferenceSchemaContract ref = buildReference(
				"categories", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.withGroupType("categoryGroup")
			);

			assertAll(
				() -> assertEquals("categoryGroup", ref.getReferencedGroupType()),
				() -> assertFalse(ref.isReferencedGroupTypeManaged())
			);
		}

		@Test
		@DisplayName("should set managed group type")
		void shouldSetManagedGroupType() {
			final ReferenceSchemaContract ref = buildReference(
				"categories", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.withGroupTypeRelatedToEntity(Entities.STORE)
			);

			assertAll(
				() -> assertEquals(Entities.STORE, ref.getReferencedGroupType()),
				() -> assertTrue(ref.isReferencedGroupTypeManaged())
			);
		}

		@Test
		@DisplayName("should remove group type")
		void shouldRemoveGroupType() {
			final ReferenceSchemaContract ref = buildReference(
				"categories", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withGroupType("categoryGroup")
					.withoutGroupType()
			);

			assertNull(ref.getReferencedGroupType());
		}

		@Test
		@DisplayName("should switch from external to managed group type")
		void shouldSwitchExternalToManagedGroupType() {
			final ReferenceSchemaContract ref = buildReference(
				"categories", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withGroupType("categoryGroup")
					.withGroupTypeRelatedToEntity(Entities.STORE)
			);

			assertAll(
				() -> assertEquals(Entities.STORE, ref.getReferencedGroupType()),
				() -> assertTrue(ref.isReferencedGroupTypeManaged())
			);
		}
	}

	@Nested
	@DisplayName("Scope-based indexing")
	class ScopeBasedIndexing {

		@Test
		@DisplayName("should make reference indexed in LIVE scope")
		void shouldMakeIndexedInLiveScope() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedInScope(Scope.LIVE)
			);

			assertAll(
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertFalse(ref.isIndexedInScope(Scope.ARCHIVED))
			);
		}

		@Test
		@DisplayName("should make reference indexed in both scopes")
		void shouldMakeIndexedInBothScopes() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedInScope(Scope.LIVE, Scope.ARCHIVED)
			);

			assertAll(
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertTrue(ref.isIndexedInScope(Scope.ARCHIVED))
			);
		}

		@Test
		@DisplayName("should create non-indexed reference by default")
		void shouldCreateNonIndexedByDefault() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> {}
			);

			assertAll(
				() -> assertFalse(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertFalse(ref.isIndexedInScope(Scope.ARCHIVED))
			);
		}

		@Test
		@DisplayName("should set reference indexed for filtering only")
		void shouldSetIndexedForFilteringOnly() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE)
			);

			assertAll(
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertEquals(
					ReferenceIndexType.FOR_FILTERING,
					ref.getReferenceIndexType(Scope.LIVE)
				)
			);
		}

		@Test
		@DisplayName("should set reference indexed for filtering and partitioning")
		void shouldSetIndexedForFilteringAndPartitioning() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
			);

			assertAll(
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertEquals(
					ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
					ref.getReferenceIndexType(Scope.LIVE)
				)
			);
		}

		@Test
		@DisplayName("should set different index types per scope")
		void shouldSetDifferentIndexTypesPerScope() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE)
					.indexedForFilteringAndPartitioningInScope(Scope.ARCHIVED)
			);

			assertAll(
				() -> assertEquals(
					ReferenceIndexType.FOR_FILTERING,
					ref.getReferenceIndexType(Scope.LIVE)
				),
				() -> assertEquals(
					ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
					ref.getReferenceIndexType(Scope.ARCHIVED)
				)
			);
		}
	}

	@Nested
	@DisplayName("Indexed components")
	class IndexedComponentsTests {

		@Test
		@DisplayName("should set indexed components in default scope")
		void shouldSetIndexedComponentsInDefaultScope() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedInScope(Scope.LIVE)
					.indexedWithComponentsInScope(
						Scope.LIVE,
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
			);

			assertAll(
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertEquals(
					2,
					ref.getIndexedComponents(Scope.LIVE).size()
				),
				() -> assertTrue(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_ENTITY
					)
				),
				() -> assertTrue(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
				)
			);
		}

		@Test
		@DisplayName("should set indexed components with only REFERENCED_GROUP_ENTITY")
		void shouldSetOnlyGroupEntityComponent() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedInScope(Scope.LIVE)
					.indexedWithComponentsInScope(
						Scope.LIVE,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
			);

			assertAll(
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertEquals(
					1,
					ref.getIndexedComponents(Scope.LIVE).size()
				),
				() -> assertTrue(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
				),
				() -> assertFalse(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_ENTITY
					)
				)
			);
		}

		@Test
		@DisplayName("should auto-promote to indexed when setting components on non-indexed scope")
		void shouldAutoPromoteToIndexedWhenSettingComponents() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedWithComponentsInScope(
						Scope.LIVE,
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
			);

			assertAll(
				() -> assertTrue(
					ref.isIndexedInScope(Scope.LIVE),
					"Reference should be auto-promoted to indexed"
				),
				() -> assertEquals(
					ReferenceIndexType.FOR_FILTERING,
					ref.getReferenceIndexType(Scope.LIVE),
					"Auto-promoted reference should use FOR_FILTERING type"
				),
				() -> assertEquals(
					2,
					ref.getIndexedComponents(Scope.LIVE).size()
				),
				() -> assertTrue(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_ENTITY
					)
				),
				() -> assertTrue(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
				)
			);
		}

		@Test
		@DisplayName("should preserve components when changing index type after")
		void shouldPreserveComponentsWhenChangingIndexTypeAfter() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedWithComponentsInScope(
						Scope.LIVE,
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
			);

			assertAll(
				() -> assertEquals(
					ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
					ref.getReferenceIndexType(Scope.LIVE),
					"Index type should be upgraded to FOR_FILTERING_AND_PARTITIONING"
				),
				() -> assertEquals(
					2,
					ref.getIndexedComponents(Scope.LIVE).size(),
					"Components should be preserved after changing index type"
				),
				() -> assertTrue(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_ENTITY
					)
				),
				() -> assertTrue(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
				)
			);
		}

		@Test
		@DisplayName("should preserve index type when setting components after")
		void shouldPreserveIndexTypeWhenSettingComponentsAfter() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
					.indexedWithComponentsInScope(
						Scope.LIVE,
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
			);

			assertAll(
				() -> assertEquals(
					ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
					ref.getReferenceIndexType(Scope.LIVE),
					"Index type should be preserved as FOR_FILTERING_AND_PARTITIONING"
				),
				() -> assertEquals(
					2,
					ref.getIndexedComponents(Scope.LIVE).size()
				),
				() -> assertTrue(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_ENTITY
					)
				),
				() -> assertTrue(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
				)
			);
		}

		@Test
		@DisplayName("should use default components when only index type is set")
		void shouldUseDefaultComponentsWhenOnlyTypeIsSet() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
			);

			assertAll(
				() -> assertEquals(
					ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
					ref.getReferenceIndexType(Scope.LIVE)
				),
				() -> assertEquals(
					1,
					ref.getIndexedComponents(Scope.LIVE).size(),
					"Should default to single REFERENCED_ENTITY component"
				),
				() -> assertTrue(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_ENTITY
					)
				)
			);
		}

		@Test
		@DisplayName("should absorb indexed with components into Create mutation")
		void shouldProduceMutationWithComponents() {
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.indexedInScope(Scope.LIVE)
							.indexedWithComponentsInScope(
								Scope.LIVE,
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);
			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(builder.toMutation());

			// Set mutations should be absorbed into the Create mutation
			final boolean hasNoSeparateIndexedMutation = mutations.stream()
				.noneMatch(SetReferenceSchemaIndexedMutation.class::isInstance);
			final CreateReferenceSchemaMutation createMutation = mutations.stream()
				.filter(CreateReferenceSchemaMutation.class::isInstance)
				.map(CreateReferenceSchemaMutation.class::cast)
				.findFirst()
				.orElse(null);

			assertAll(
				() -> assertTrue(
					hasNoSeparateIndexedMutation,
					"Indexed mutation should be absorbed into Create"
				),
				() -> assertNotNull(createMutation, "Should have Create mutation"),
				() -> assertTrue(
					createMutation.isIndexed(),
					"Create mutation should have indexed flag"
				),
				() -> assertNotNull(
					createMutation.getIndexedComponentsInScopes(),
					"Create mutation should have components"
				),
				() -> assertTrue(
					createMutation.getIndexedComponentsInScopes().length > 0,
					"Create mutation should have non-empty components"
				)
			);
		}
	}

	@Nested
	@DisplayName("Faceting operations")
	class FacetingOperations {

		@Test
		@DisplayName("should make reference faceted in LIVE scope")
		void shouldMakeFacetedInLiveScope() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.facetedInScope(Scope.LIVE)
			);

			assertAll(
				() -> assertTrue(ref.isFacetedInScope(Scope.LIVE)),
				() -> assertFalse(ref.isFacetedInScope(Scope.ARCHIVED))
			);
		}

		@Test
		@DisplayName("should make reference faceted in both scopes")
		void shouldMakeFacetedInBothScopes() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.facetedInScope(Scope.LIVE, Scope.ARCHIVED)
			);

			assertAll(
				() -> assertTrue(ref.isFacetedInScope(Scope.LIVE)),
				() -> assertTrue(ref.isFacetedInScope(Scope.ARCHIVED))
			);
		}

		@Test
		@DisplayName("should implicitly index when setting faceted")
		void shouldImplicitlyIndexWhenFaceted() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.facetedInScope(Scope.LIVE)
			);

			assertTrue(
				ref.isIndexedInScope(Scope.LIVE),
				"Reference should be implicitly indexed when faceted"
			);
		}

		@Test
		@DisplayName("should remove faceting from scope")
		void shouldRemoveFacetingFromScope() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.facetedInScope(Scope.LIVE, Scope.ARCHIVED)
					.nonFaceted(Scope.LIVE)
			);

			assertAll(
				() -> assertFalse(ref.isFacetedInScope(Scope.LIVE)),
				() -> assertTrue(ref.isFacetedInScope(Scope.ARCHIVED))
			);
		}

		@Test
		@DisplayName("should preserve indexing when adding faceting to already indexed scope")
		void shouldPreserveIndexingWhenAddingFaceting() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedInScope(Scope.LIVE)
					.facetedInScope(Scope.LIVE)
			);

			assertAll(
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertTrue(ref.isFacetedInScope(Scope.LIVE))
			);
		}

		/**
		 * Verifies that facetedPartiallyInScope sets a partial expression
		 * on the built reference schema.
		 */
		@Test
		@DisplayName("should set facetedPartially expression in scope")
		void shouldSetFacetedPartiallyInScope() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.facetedInScope(Scope.LIVE)
					.facetedPartiallyInScope(Scope.LIVE, expression)
			);

			assertAll(
				() -> assertTrue(ref.isFacetedInScope(Scope.LIVE)),
				() -> assertNotNull(
					ref.getFacetedPartiallyInScope(Scope.LIVE)
				),
				() -> assertEquals(
					expression.toExpressionString(),
					ref.getFacetedPartiallyInScope(Scope.LIVE)
						.toExpressionString()
				)
			);
		}

		/**
		 * Verifies that calling facetedPartiallyInScope for two different scopes retains
		 * both expressions. Also verifies that facetedPartiallyInScope implicitly enables
		 * faceting for the specified scope.
		 */
		@Test
		@DisplayName("should retain facetedPartially expressions for multiple scopes")
		void shouldRetainFacetedPartiallyForMultipleScopes() {
			final Expression liveExpression = ExpressionFactory.parse("1 > 0");
			final Expression archivedExpression = ExpressionFactory.parse("2 > 1");
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.facetedPartiallyInScope(Scope.LIVE, liveExpression)
					.facetedPartiallyInScope(Scope.ARCHIVED, archivedExpression)
			);

			assertAll(
				// facetedPartiallyInScope should implicitly enable faceted for that scope
				() -> assertTrue(
					ref.isFacetedInScope(Scope.LIVE),
					"facetedPartiallyInScope should implicitly enable faceted for LIVE"
				),
				() -> assertTrue(
					ref.isFacetedInScope(Scope.ARCHIVED),
					"facetedPartiallyInScope should implicitly enable faceted for ARCHIVED"
				),
				// both expressions should be present
				() -> assertNotNull(
					ref.getFacetedPartiallyInScope(Scope.LIVE),
					"LIVE expression should not be overwritten by ARCHIVED"
				),
				() -> assertNotNull(
					ref.getFacetedPartiallyInScope(Scope.ARCHIVED),
					"ARCHIVED expression should be set"
				),
				() -> assertEquals(
					liveExpression.toExpressionString(),
					ref.getFacetedPartiallyInScope(Scope.LIVE).toExpressionString()
				),
				() -> assertEquals(
					archivedExpression.toExpressionString(),
					ref.getFacetedPartiallyInScope(Scope.ARCHIVED).toExpressionString()
				)
			);
		}

		/**
		 * Verifies that nonFacetedPartially clears the partial expression
		 * for the specified scope.
		 */
		@Test
		@DisplayName("should clear facetedPartially expression via nonFacetedPartially")
		void shouldClearFacetedPartially() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.facetedInScope(Scope.LIVE)
					.facetedPartiallyInScope(Scope.LIVE, expression)
					.nonFacetedPartially(Scope.LIVE)
			);

			assertAll(
				() -> assertTrue(ref.isFacetedInScope(Scope.LIVE)),
				() -> assertNull(
					ref.getFacetedPartiallyInScope(Scope.LIVE)
				)
			);
		}

		/**
		 * Verifies that the builder generates a SetReferenceSchemaFacetedMutation
		 * containing the facetedPartially expression.
		 */
		@Test
		@DisplayName("should generate facetedPartially mutation")
		void shouldGenerateFacetedPartiallyMutation() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			final AtomicReference<ReferenceSchemaBuilder> captured =
				new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.facetedInScope(Scope.LIVE)
							.facetedPartiallyInScope(Scope.LIVE, expression);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);
			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(builder.toMutation());

			// The facetedPartially should be absorbed into the Create
			// mutation via combineWith
			final CreateReferenceSchemaMutation createMutation =
				mutations.stream()
					.filter(CreateReferenceSchemaMutation.class::isInstance)
					.map(CreateReferenceSchemaMutation.class::cast)
					.findFirst()
					.orElseThrow();

			final ScopedFacetedPartially[] partiallyInScopes =
				createMutation.getFacetedPartiallyInScopes();
			assertNotNull(partiallyInScopes);
			assertTrue(partiallyInScopes.length > 0);
			assertEquals(Scope.LIVE, partiallyInScopes[0].scope());
			assertNotNull(partiallyInScopes[0].expression());
		}
	}

	@Nested
	@DisplayName("Reference attributes")
	class ReferenceAttributes {

		@Test
		@DisplayName("should add simple attribute")
		void shouldAddAttribute() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.withAttribute("priority", Integer.class)
			);

			final AttributeSchemaContract attr =
				ref.getAttribute("priority").orElseThrow();

			assertSame(Integer.class, attr.getType());
		}

		@Test
		@DisplayName("should add attribute with customizer")
		void shouldAddAttributeWithCustomizer() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexed()
					.withAttribute(
						"priority", Integer.class,
						attr -> attr.sortable().nullable()
					)
			);

			final AttributeSchemaContract attr =
				ref.getAttribute("priority").orElseThrow();

			assertAll(
				() -> assertSame(Integer.class, attr.getType()),
				() -> assertTrue(attr.isSortable()),
				() -> assertTrue(attr.isNullable())
			);
		}

		@Test
		@DisplayName("should remove attribute")
		void shouldRemoveAttribute() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.withAttribute("priority", Integer.class)
					.withoutAttribute("priority")
			);

			assertTrue(ref.getAttribute("priority").isEmpty());
		}

		@Test
		@DisplayName("should throw on attribute type mismatch")
		void shouldThrowOnTypeMismatch() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> buildReference(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs
						.withAttribute("priority", Integer.class)
						.withAttribute("priority", String.class)
				)
			);
		}

		@Test
		@DisplayName("should add multiple attributes")
		void shouldAddMultipleAttributes() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.withAttribute("priority", Integer.class)
					.withAttribute("label", String.class)
					.withAttribute("visible", Boolean.class)
			);

			assertAll(
				() -> assertTrue(ref.getAttribute("priority").isPresent()),
				() -> assertTrue(ref.getAttribute("label").isPresent()),
				() -> assertTrue(ref.getAttribute("visible").isPresent()),
				() -> assertEquals(3, ref.getAttributes().size())
			);
		}
	}

	@Nested
	@DisplayName("Sortable attribute compounds")
	class SortableAttributeCompounds {

		@Test
		@DisplayName("should add sortable attribute compound")
		void shouldAddCompound() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.withAttribute("name", String.class)
					.withAttribute("priority", Integer.class)
					.withSortableAttributeCompound(
						"namePriority",
						attributeElement("name"),
						attributeElement("priority")
					)
			);

			final SortableAttributeCompoundSchemaContract compound =
				ref.getSortableAttributeCompound("namePriority").orElseThrow();

			assertAll(
				() -> assertEquals("namePriority", compound.getName()),
				() -> assertEquals(2, compound.getAttributeElements().size())
			);
		}

		@Test
		@DisplayName("should add compound with customizer")
		void shouldAddCompoundWithCustomizer() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.withAttribute("name", String.class)
					.withAttribute("priority", Integer.class)
					.withSortableAttributeCompound(
						"namePriority",
						new AttributeElement[]{
							attributeElement("name"),
							attributeElement("priority")
						},
						compound -> compound.withDescription("Name and priority compound")
					)
			);

			final SortableAttributeCompoundSchemaContract compound =
				ref.getSortableAttributeCompound("namePriority").orElseThrow();

			assertEquals("Name and priority compound", compound.getDescription());
		}

		@Test
		@DisplayName("should remove sortable attribute compound")
		void shouldRemoveCompound() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.withAttribute("name", String.class)
					.withAttribute("priority", Integer.class)
					.withSortableAttributeCompound(
						"namePriority",
						attributeElement("name"),
						attributeElement("priority")
					)
					.withoutSortableAttributeCompound("namePriority")
			);

			assertTrue(ref.getSortableAttributeCompound("namePriority").isEmpty());
		}

		@Test
		@DisplayName("should not allow removing attribute used in compound")
		void shouldNotAllowRemovingAttributeUsedInCompound() {
			assertThrows(
				Exception.class,
				() -> buildReference(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs
						.withAttribute("name", String.class)
						.withAttribute("priority", Integer.class)
						.withSortableAttributeCompound(
							"namePriority",
							attributeElement("name"),
							attributeElement("priority")
						)
						.withoutAttribute("name")
				)
			);
		}
	}

	@Nested
	@DisplayName("Mutation generation")
	class MutationGeneration {

		@Test
		@DisplayName("should generate CreateReferenceSchemaMutation as first mutation")
		void shouldGenerateCreateMutation() {
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder.withDescription("Brand reference");
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);
			final Collection<LocalEntitySchemaMutation> mutations = builder.toMutation();

			assertFalse(mutations.isEmpty());
			assertInstanceOf(
				CreateReferenceSchemaMutation.class,
				mutations.iterator().next()
			);
		}

		@Test
		@DisplayName("should place attribute mutations after reference mutations")
		void shouldPlaceAttributeMutationsAfterReferenceMutations() {
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.indexed()
							.withAttribute("priority", Integer.class)
							.facetedInScope(Scope.LIVE);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);
			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(builder.toMutation());

			// attribute mutations should be sorted last
			boolean foundAttribute = false;
			boolean foundNonAttributeAfterAttribute = false;
			for (final LocalEntitySchemaMutation mutation : mutations) {
				if (mutation instanceof ModifyReferenceAttributeSchemaMutation) {
					foundAttribute = true;
				} else if (foundAttribute) {
					foundNonAttributeAfterAttribute = true;
				}
			}

			assertTrue(foundAttribute, "Should contain attribute mutations");
			assertFalse(
				foundNonAttributeAfterAttribute,
				"Non-attribute mutations should not appear after attribute mutations"
			);
		}

		@Test
		@DisplayName("should produce consistent toResult()")
		void shouldProduceConsistentResult() {
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.withDescription("Brand reference")
							.indexedInScope(Scope.LIVE)
							.facetedInScope(Scope.LIVE)
							.withAttribute("priority", Integer.class);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);
			final ReferenceSchemaBuilderResult result = builder.toResult();

			assertAll(
				() -> assertNotNull(result.schema()),
				() -> assertFalse(result.mutations().isEmpty()),
				() -> assertEquals("brand", result.schema().getName()),
				() -> assertEquals("Brand reference", result.schema().getDescription()),
				() -> assertTrue(result.schema().isIndexedInScope(Scope.LIVE)),
				() -> assertTrue(result.schema().isFacetedInScope(Scope.LIVE)),
				() -> assertTrue(result.schema().getAttribute("priority").isPresent())
			);
		}

		@Test
		@DisplayName("should absorb indexed and faceted into single Create mutation")
		void shouldContainIndexedAndFacetedMutations() {
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.indexedInScope(Scope.LIVE)
							.facetedInScope(Scope.LIVE);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);
			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(builder.toMutation());

			// Set mutations should be absorbed into the Create mutation
			final boolean hasNoSeparateIndexedMutation = mutations.stream()
				.noneMatch(SetReferenceSchemaIndexedMutation.class::isInstance);
			final boolean hasNoSeparateFacetedMutation = mutations.stream()
				.noneMatch(SetReferenceSchemaFacetedMutation.class::isInstance);
			final CreateReferenceSchemaMutation createMutation = mutations.stream()
				.filter(CreateReferenceSchemaMutation.class::isInstance)
				.map(CreateReferenceSchemaMutation.class::cast)
				.findFirst()
				.orElse(null);

			assertAll(
				() -> assertTrue(
					hasNoSeparateIndexedMutation,
					"Indexed mutation should be absorbed into Create"
				),
				() -> assertTrue(
					hasNoSeparateFacetedMutation,
					"Faceted mutation should be absorbed into Create"
				),
				() -> assertNotNull(createMutation, "Should have Create mutation"),
				() -> assertTrue(
					createMutation.isIndexed(),
					"Create mutation should have indexed flag"
				),
				() -> assertTrue(
					createMutation.isFaceted(),
					"Create mutation should have faceted flag"
				)
			);
		}
	}

	@Nested
	@DisplayName("Method chaining")
	class MethodChaining {

		@Test
		@DisplayName("should support full fluent chain")
		void shouldSupportFluentChain() {
			final ReferenceSchemaContract ref = buildReference(
				"categories", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withDescription("Product categories")
					.deprecated("Will be replaced")
					.withGroupTypeRelatedToEntity(Entities.STORE)
					.indexedInScope(Scope.LIVE)
					.facetedInScope(Scope.LIVE)
					.withAttribute("priority", Integer.class,
						attr -> attr.sortable().nullable())
					.withAttribute("label", String.class)
					.withSortableAttributeCompound(
						"priorityLabel",
						attributeElement("priority"),
						attributeElement("label")
					)
			);

			assertAll(
				() -> assertEquals("Product categories", ref.getDescription()),
				() -> assertEquals("Will be replaced", ref.getDeprecationNotice()),
				() -> assertEquals(Entities.STORE, ref.getReferencedGroupType()),
				() -> assertTrue(ref.isReferencedGroupTypeManaged()),
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertTrue(ref.isFacetedInScope(Scope.LIVE)),
				() -> assertEquals(2, ref.getAttributes().size()),
				() -> assertTrue(ref.getAttribute("priority").isPresent()),
				() -> assertTrue(ref.getAttribute("label").isPresent()),
				() -> assertTrue(ref.getSortableAttributeCompound("priorityLabel").isPresent()),
				() -> assertEquals(Cardinality.ZERO_OR_MORE, ref.getCardinality()),
				() -> assertEquals(Entities.CATEGORY, ref.getReferencedEntityType()),
				() -> assertTrue(ref.isReferencedEntityTypeManaged())
			);
		}

		@Test
		@DisplayName("should support undoing operations in chain")
		void shouldSupportUndoingOperationsInChain() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.withDescription("Brand reference")
					.deprecated("Deprecated")
					.notDeprecatedAnymore()
					.withGroupType("brandGroup")
					.withoutGroupType()
					.indexedInScope(Scope.LIVE, Scope.ARCHIVED)
					.facetedInScope(Scope.LIVE)
					.nonFaceted(Scope.LIVE)
			);

			assertAll(
				() -> assertEquals("Brand reference", ref.getDescription()),
				() -> assertNull(ref.getDeprecationNotice()),
				() -> assertNull(ref.getReferencedGroupType()),
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertTrue(ref.isIndexedInScope(Scope.ARCHIVED)),
				() -> assertFalse(ref.isFacetedInScope(Scope.LIVE))
			);
		}
	}

	@Nested
	@DisplayName("Cardinality and entity type")
	class CardinalityAndEntityType {

		@Test
		@DisplayName("should preserve cardinality")
		void shouldPreserveCardinality() {
			final ReferenceSchemaContract refZeroOrOne = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> {}
			);
			final ReferenceSchemaContract refZeroOrMore = buildReference(
				"categories", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> {}
			);
			final ReferenceSchemaContract refExactlyOne = buildReference(
				"store", Entities.STORE, Cardinality.EXACTLY_ONE,
				whichIs -> {}
			);

			assertAll(
				() -> assertEquals(Cardinality.ZERO_OR_ONE, refZeroOrOne.getCardinality()),
				() -> assertEquals(Cardinality.ZERO_OR_MORE, refZeroOrMore.getCardinality()),
				() -> assertEquals(Cardinality.EXACTLY_ONE, refExactlyOne.getCardinality())
			);
		}

		@Test
		@DisplayName("should set managed entity type")
		void shouldSetManagedEntityType() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> {}
			);

			assertAll(
				() -> assertEquals(Entities.BRAND, ref.getReferencedEntityType()),
				() -> assertTrue(ref.isReferencedEntityTypeManaged())
			);
		}

		@Test
		@DisplayName("should create external reference")
		void shouldCreateExternalReference() {
			final EntitySchemaContract schema = createEntitySchemaBuilder()
				.withReferenceTo(
					"externalBrand", "ExternalBrand", Cardinality.ZERO_OR_ONE,
					whichIs -> {}
				)
				.toInstance();

			final ReferenceSchemaContract ref =
				schema.getReference("externalBrand").orElseThrow();

			assertAll(
				() -> assertEquals("ExternalBrand", ref.getReferencedEntityType()),
				() -> assertFalse(ref.isReferencedEntityTypeManaged())
			);
		}
	}

	@Nested
	@DisplayName("Mutation absorption")
	class MutationAbsorption {

		@Test
		@DisplayName("should absorb indexed into Create")
		void shouldAbsorbIndexedIntoCreate() {
			final AtomicReference<ReferenceSchemaBuilder> captured =
				new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder.indexedInScope(Scope.LIVE);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(captured.get().toMutation());

			assertAll(
				() -> assertEquals(
					1, mutations.size(),
					"Should have single Create mutation"
				),
				() -> assertInstanceOf(
					CreateReferenceSchemaMutation.class,
					mutations.get(0)
				),
				() -> assertTrue(
					((CreateReferenceSchemaMutation) mutations.get(0))
						.isIndexed()
				)
			);
		}

		@Test
		@DisplayName("should absorb faceted into Create")
		void shouldAbsorbFacetedIntoCreate() {
			final AtomicReference<ReferenceSchemaBuilder> captured =
				new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder.facetedInScope(Scope.LIVE);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(captured.get().toMutation());

			final CreateReferenceSchemaMutation create =
				(CreateReferenceSchemaMutation) mutations.stream()
					.filter(CreateReferenceSchemaMutation.class::isInstance)
					.findFirst()
					.orElseThrow();

			assertAll(
				() -> assertTrue(
					mutations.stream().noneMatch(
						SetReferenceSchemaFacetedMutation.class::isInstance
					),
					"No separate faceted mutation"
				),
				() -> assertTrue(
					mutations.stream().noneMatch(
						SetReferenceSchemaIndexedMutation.class::isInstance
					),
					"No separate indexed mutation"
				),
				() -> assertTrue(
					create.isIndexed(),
					"Create should be indexed (required for faceted)"
				),
				() -> assertTrue(
					create.isFaceted(),
					"Create should be faceted"
				)
			);
		}

		@Test
		@DisplayName("should absorb indexedForFiltering into Create")
		void shouldAbsorbIndexedForFilteringIntoCreate() {
			final AtomicReference<ReferenceSchemaBuilder> captured =
				new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder.indexedForFilteringInScope(Scope.LIVE);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(captured.get().toMutation());

			assertAll(
				() -> assertEquals(
					1, mutations.size(),
					"Should have single Create mutation"
				),
				() -> assertInstanceOf(
					CreateReferenceSchemaMutation.class,
					mutations.get(0)
				)
			);
		}

		@Test
		@DisplayName(
			"should absorb indexed with components into Create"
		)
		void shouldAbsorbIndexedWithComponentsIntoCreate() {
			final AtomicReference<ReferenceSchemaBuilder> captured =
				new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder.indexedWithComponentsInScope(
							Scope.LIVE,
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(captured.get().toMutation());

			final CreateReferenceSchemaMutation create =
				(CreateReferenceSchemaMutation) mutations.stream()
					.filter(CreateReferenceSchemaMutation.class::isInstance)
					.findFirst()
					.orElseThrow();

			assertAll(
				() -> assertTrue(
					mutations.stream().noneMatch(
						SetReferenceSchemaIndexedMutation.class::isInstance
					),
					"No separate indexed mutation"
				),
				() -> assertTrue(create.isIndexed()),
				() -> assertTrue(
					create.getIndexedComponentsInScopes().length > 0,
					"Should have components"
				)
			);
		}

		@Test
		@DisplayName(
			"should absorb both indexed and faceted into Create"
		)
		void shouldAbsorbBothIndexedAndFacetedIntoCreate() {
			final AtomicReference<ReferenceSchemaBuilder> captured =
				new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.indexedInScope(Scope.LIVE)
							.facetedInScope(Scope.LIVE);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(captured.get().toMutation());

			final CreateReferenceSchemaMutation create =
				(CreateReferenceSchemaMutation) mutations.stream()
					.filter(CreateReferenceSchemaMutation.class::isInstance)
					.findFirst()
					.orElseThrow();

			assertAll(
				() -> assertEquals(
					1, mutations.size(),
					"Should have single Create mutation"
				),
				() -> assertTrue(create.isIndexed()),
				() -> assertTrue(create.isFaceted())
			);
		}

		@Test
		@DisplayName(
			"should keep indexed in Create when nonIndexed uses merge semantics"
		)
		void shouldKeepIndexedWhenNonIndexedUsesMergeSemantics() {
			// nonIndexed(LIVE) creates a Set mutation with only the REMAINING scopes
			// (i.e. empty array). Under merge semantics (matching Set+Set combining),
			// an empty incoming array does not remove existing scopes — the Create
			// stays indexed. This is consistent with the pre-existing Set+Set behavior.
			final AtomicReference<ReferenceSchemaBuilder> captured =
				new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.indexedInScope(Scope.LIVE)
							.nonIndexed(Scope.LIVE);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(captured.get().toMutation());

			final CreateReferenceSchemaMutation create =
				(CreateReferenceSchemaMutation) mutations.stream()
					.filter(CreateReferenceSchemaMutation.class::isInstance)
					.findFirst()
					.orElseThrow();

			assertAll(
				() -> assertEquals(
					1, mutations.size(),
					"Should have single Create mutation"
				),
				// merge semantics: empty incoming preserves existing scopes
				() -> assertTrue(
					create.isIndexed(),
					"Create stays indexed (merge can't subtract)"
				)
			);
		}

		@Test
		@DisplayName(
			"should absorb faceted then nonFaceted into Create"
		)
		void shouldAbsorbFacetedThenNonFacetedIntoCreate() {
			final AtomicReference<ReferenceSchemaBuilder> captured =
				new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.facetedInScope(Scope.LIVE)
							.nonFaceted(Scope.LIVE);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(captured.get().toMutation());

			final CreateReferenceSchemaMutation create =
				(CreateReferenceSchemaMutation) mutations.stream()
					.filter(CreateReferenceSchemaMutation.class::isInstance)
					.findFirst()
					.orElseThrow();

			assertAll(
				() -> assertTrue(
					mutations.stream().noneMatch(
						SetReferenceSchemaFacetedMutation.class::isInstance
					),
					"No separate faceted mutation"
				),
				() -> assertFalse(
					create.isFaceted(),
					"Create should be non-faceted after undoing"
				)
			);
		}

		@Test
		@DisplayName(
			"should produce correct schema after absorption"
		)
		void shouldProduceCorrectSchemaAfterAbsorption() {
			final ReferenceSchemaContract ref = buildReference(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedInScope(Scope.LIVE)
					.facetedInScope(Scope.LIVE)
			);

			assertAll(
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertFalse(ref.isIndexedInScope(Scope.ARCHIVED)),
				() -> assertTrue(ref.isFacetedInScope(Scope.LIVE)),
				() -> assertFalse(ref.isFacetedInScope(Scope.ARCHIVED))
			);
		}

		@Test
		@DisplayName(
			"should not absorb when modifying existing reference"
		)
		void shouldNotAbsorbWhenModifyingExistingReference() {
			// Create a schema with an existing reference first
			final EntitySchemaContract baseSchema = createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> {}
				)
				.toInstance();

			// Now modify the existing reference
			final InternalEntitySchemaBuilder modifyBuilder =
				new InternalEntitySchemaBuilder(
					ReferenceSchemaBuilderTest.this.catalogSchema,
					baseSchema
				);
			modifyBuilder.withReferenceToEntity(
				"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedInScope(Scope.LIVE)
			);
			final LocalEntitySchemaMutation[] mutations =
				modifyBuilder.toMutation()
					.orElseThrow()
					.getSchemaMutations();

			// Should have separate Set mutation (no Create to absorb into)
			final boolean hasSetIndexedMutation =
				Arrays.stream(mutations).anyMatch(
					SetReferenceSchemaIndexedMutation.class::isInstance
				);

			assertTrue(
				hasSetIndexedMutation,
				"Should have separate Set mutation for existing reference"
			);
		}

		@Test
		@DisplayName(
			"should absorb Set into Create via withMutations entry point"
		)
		void shouldAbsorbViaWithMutationsEntryPoint() {
			final CreateReferenceSchemaMutation createMutation =
				new CreateReferenceSchemaMutation(
					"brand", null, null,
					Cardinality.ZERO_OR_ONE,
					Entities.BRAND, true,
					null, false,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE,
							ReferenceIndexType.FOR_FILTERING
						)
					},
					new ScopedReferenceIndexedComponents[]{
						new ScopedReferenceIndexedComponents(
							Scope.LIVE,
							ReferenceIndexedComponents
								.DEFAULT_INDEXED_COMPONENTS
						)
					},
					Scope.NO_SCOPE
				);

			// SetIndexed with empty scopes — under merge semantics this
			// doesn't remove existing scopes, it just adds nothing
			final SetReferenceSchemaIndexedMutation setMutation =
				new SetReferenceSchemaIndexedMutation(
					"brand",
					ScopedReferenceIndexType.EMPTY
				);

			// Pass both mutations through the builder's addMutations
			final InternalEntitySchemaBuilder builder =
				new InternalEntitySchemaBuilder(
					ReferenceSchemaBuilderTest.this.catalogSchema,
					ReferenceSchemaBuilderTest.this.productSchema,
					List.of(createMutation, setMutation)
				);
			final LocalEntitySchemaMutation[] mutations =
				builder.toMutation()
					.orElseThrow()
					.getSchemaMutations();

			final CreateReferenceSchemaMutation resultCreate =
				Arrays.stream(mutations)
					.filter(
						CreateReferenceSchemaMutation.class::isInstance
					)
					.map(CreateReferenceSchemaMutation.class::cast)
					.findFirst()
					.orElse(null);

			assertAll(
				() -> assertNotNull(
					resultCreate, "Should have Create mutation"
				),
				// merge semantics: empty Set preserves Create's existing scopes
				() -> assertTrue(
					resultCreate.isIndexed(),
					"Create stays indexed (merge can't subtract)"
				),
				() -> assertTrue(
					Arrays.stream(mutations).noneMatch(
						SetReferenceSchemaIndexedMutation
							.class::isInstance
					),
					"No separate Set mutation"
				)
			);
		}
	}

	@Nested
	@DisplayName("Mutation and instance coherence")
	class MutationAndInstanceCoherence {

		/**
		 * Verifies that indexed components survive applying
		 * {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaRelatedEntityGroupMutation}.
		 */
		@Test
		@DisplayName("should preserve components when setting group type")
		void shouldPreserveComponentsWhenSettingGroupType() {
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.indexedWithComponentsInScope(
								Scope.LIVE,
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.withGroupTypeRelatedToEntity(Entities.BRAND);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);

			final ReferenceSchemaContract fromResult = builder.toResult().schema();

			assertAll(
				() -> assertTrue(
					builder.getIndexedComponentsInScopes()
						.getOrDefault(Scope.LIVE, EnumSet.noneOf(ReferenceIndexedComponents.class))
						.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
					"toInstance() must keep REFERENCED_GROUP_ENTITY"
				),
				() -> assertTrue(
					fromResult.getIndexedComponentsInScopes()
						.getOrDefault(Scope.LIVE, EnumSet.noneOf(ReferenceIndexedComponents.class))
						.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
					"toResult() must keep REFERENCED_GROUP_ENTITY"
				),
				() -> assertEquals(
					builder.getIndexedComponentsInScopes(),
					fromResult.getIndexedComponentsInScopes(),
					"toInstance() and toResult() components must match"
				)
			);
		}

		/**
		 * Verifies that indexed components survive applying
		 * {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDescriptionMutation}.
		 */
		@Test
		@DisplayName("should preserve components when changing description")
		void shouldPreserveComponentsWhenChangingDescription() {
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.indexedWithComponentsInScope(
								Scope.LIVE,
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.withDescription("Updated description");
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);

			final ReferenceSchemaContract fromResult = builder.toResult().schema();

			assertAll(
				() -> assertEquals(
					builder.getIndexedComponentsInScopes(),
					fromResult.getIndexedComponentsInScopes(),
					"Components must match after description change"
				),
				() -> assertTrue(
					fromResult.getIndexedComponentsInScopes()
						.getOrDefault(Scope.LIVE, EnumSet.noneOf(ReferenceIndexedComponents.class))
						.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
					"REFERENCED_GROUP_ENTITY must survive description mutation"
				)
			);
		}

		/**
		 * Verifies that indexed components survive applying
		 * {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDeprecationNoticeMutation}.
		 */
		@Test
		@DisplayName("should preserve components when setting deprecation notice")
		void shouldPreserveComponentsWhenSettingDeprecationNotice() {
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.indexedWithComponentsInScope(
								Scope.LIVE,
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.deprecated("Use category instead");
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);

			final ReferenceSchemaContract fromResult = builder.toResult().schema();

			assertAll(
				() -> assertEquals(
					builder.getIndexedComponentsInScopes(),
					fromResult.getIndexedComponentsInScopes(),
					"Components must match after deprecation change"
				),
				() -> assertTrue(
					fromResult.getIndexedComponentsInScopes()
						.getOrDefault(Scope.LIVE, EnumSet.noneOf(ReferenceIndexedComponents.class))
						.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
					"REFERENCED_GROUP_ENTITY must survive deprecation mutation"
				)
			);
		}

		/**
		 * Verifies that indexed components survive applying
		 * {@link io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation}.
		 */
		@Test
		@DisplayName("should preserve components when setting faceted")
		void shouldPreserveComponentsWhenSettingFaceted() {
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.indexedWithComponentsInScope(
								Scope.LIVE,
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.facetedInScope(Scope.LIVE);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);

			final ReferenceSchemaContract fromResult = builder.toResult().schema();

			assertAll(
				() -> assertEquals(
					builder.getIndexedComponentsInScopes(),
					fromResult.getIndexedComponentsInScopes(),
					"Components must match after faceted change"
				),
				() -> assertTrue(
					fromResult.getIndexedComponentsInScopes()
						.getOrDefault(Scope.LIVE, EnumSet.noneOf(ReferenceIndexedComponents.class))
						.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
					"REFERENCED_GROUP_ENTITY must survive faceted mutation"
				)
			);
		}

		/**
		 * Verifies that indexed components survive applying
		 * {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaCardinalityMutation}
		 * and {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaRelatedEntityMutation}
		 * by rebuilding an existing reference with different cardinality and entity type.
		 */
		@Test
		@DisplayName("should preserve components when rebuilding with changed cardinality and entity type")
		void shouldPreserveComponentsWhenRebuildingReference() {
			// first build an entity schema with a reference that has both components
			final EntitySchemaContract baseSchema = createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> builder
						.indexedWithComponentsInScope(
							Scope.LIVE,
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						)
				)
				.toInstance();

			// now rebuild the same reference with different cardinality and entity type
			// this triggers ModifyReferenceSchemaCardinalityMutation and
			// ModifyReferenceSchemaRelatedEntityMutation
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();
			new InternalEntitySchemaBuilder(
				ReferenceSchemaBuilderTest.this.catalogSchema, baseSchema
			)
				.withReferenceToEntity(
					"brand", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
					builder -> captured.set((ReferenceSchemaBuilder) builder)
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);

			final ReferenceSchemaContract fromResult = builder.toResult().schema();

			assertAll(
				() -> assertEquals(
					builder.getIndexedComponentsInScopes(),
					fromResult.getIndexedComponentsInScopes(),
					"Components must match after cardinality and entity type change"
				),
				() -> assertTrue(
					fromResult.getIndexedComponentsInScopes()
						.getOrDefault(Scope.LIVE, EnumSet.noneOf(ReferenceIndexedComponents.class))
						.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
					"REFERENCED_GROUP_ENTITY must survive cardinality/entity type mutation"
				)
			);
		}

		/**
		 * Verifies that indexed components survive when using the convenience
		 * {@code indexedWithComponents} method (default scope) combined with
		 * {@code withGroupTypeRelatedToEntity} — the exact scenario reported
		 * in the bug where the group entity component was lost.
		 */
		@Test
		@DisplayName("should preserve components when using indexedWithComponents and withGroupTypeRelatedToEntity")
		void shouldPreserveComponentsWhenUsingIndexedWithComponentsAndGroupType() {
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.indexedWithComponents(
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.withGroupTypeRelatedToEntity(Entities.BRAND);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);

			final ReferenceSchemaContract fromResult = builder.toResult().schema();

			assertAll(
				() -> assertTrue(
					builder.getIndexedComponentsInScopes()
						.getOrDefault(Scope.LIVE, EnumSet.noneOf(ReferenceIndexedComponents.class))
						.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
					"toInstance() must keep REFERENCED_GROUP_ENTITY after indexedWithComponents + withGroupTypeRelatedToEntity"
				),
				() -> assertTrue(
					fromResult.getIndexedComponentsInScopes()
						.getOrDefault(Scope.LIVE, EnumSet.noneOf(ReferenceIndexedComponents.class))
						.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
					"toResult() must keep REFERENCED_GROUP_ENTITY after indexedWithComponents + withGroupTypeRelatedToEntity"
				),
				() -> assertEquals(
					builder.getIndexedComponentsInScopes(),
					fromResult.getIndexedComponentsInScopes(),
					"toInstance() and toResult() components must match"
				),
				() -> assertNotNull(
					fromResult.getReferencedGroupType(),
					"Group type must be set"
				),
				() -> assertTrue(
					fromResult.isReferencedGroupTypeManaged(),
					"Group type must be managed"
				)
			);
		}
		/**
		 * Verifies that indexed components survive when adding an attribute to a reference
		 * that has custom indexed components configured. This exercises the bug in
		 * {@link CreateAttributeSchemaMutation#mutate} which calls an {@code _internalBuild}
		 * overload that omits {@code indexedComponentsInScopes}, defaulting to only
		 * {@code REFERENCED_ENTITY}.
		 */
		@Test
		@DisplayName("should preserve components when adding attribute to reference")
		void shouldPreserveComponentsWhenAddingAttribute() {
			final AtomicReference<ReferenceSchemaBuilder> captured = new AtomicReference<>();

			createEntitySchemaBuilder()
				.withReferenceToEntity(
					"brand", Entities.BRAND, Cardinality.ZERO_OR_ONE,
					builder -> {
						builder
							.indexedWithComponentsInScope(
								Scope.LIVE,
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.withGroupTypeRelatedToEntity(Entities.BRAND)
							.withAttribute("priority", int.class);
						captured.set((ReferenceSchemaBuilder) builder);
					}
				);

			final ReferenceSchemaBuilder builder = captured.get();
			assertNotNull(builder);

			final ReferenceSchemaContract fromResult = builder.toResult().schema();

			assertAll(
				() -> assertTrue(
					builder.getIndexedComponentsInScopes()
						.getOrDefault(Scope.LIVE, EnumSet.noneOf(ReferenceIndexedComponents.class))
						.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
					"toInstance() must keep REFERENCED_GROUP_ENTITY after adding attribute"
				),
				() -> assertTrue(
					fromResult.getIndexedComponentsInScopes()
						.getOrDefault(Scope.LIVE, EnumSet.noneOf(ReferenceIndexedComponents.class))
						.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
					"toResult() must keep REFERENCED_GROUP_ENTITY after adding attribute"
				),
				() -> assertEquals(
					builder.getIndexedComponentsInScopes(),
					fromResult.getIndexedComponentsInScopes(),
					"toInstance() and toResult() components must match"
				)
			);
		}
	}
}
