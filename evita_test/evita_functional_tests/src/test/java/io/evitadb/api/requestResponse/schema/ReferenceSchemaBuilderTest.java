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
import io.evitadb.api.requestResponse.schema.builder.ReferenceSchemaBuilder.ReferenceSchemaBuilderResult;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
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
 * @author evitaDB
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
		@DisplayName("should contain indexed and faceted mutations")
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

			final boolean hasIndexedMutation = mutations.stream()
				.anyMatch(SetReferenceSchemaIndexedMutation.class::isInstance);
			final boolean hasFacetedMutation = mutations.stream()
				.anyMatch(SetReferenceSchemaFacetedMutation.class::isInstance);

			assertAll(
				() -> assertTrue(hasIndexedMutation, "Should contain indexed mutation"),
				() -> assertTrue(hasFacetedMutation, "Should contain faceted mutation")
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
}
