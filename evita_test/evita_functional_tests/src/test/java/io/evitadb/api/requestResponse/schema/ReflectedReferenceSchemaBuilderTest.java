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
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.AbstractReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.ReflectedReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReflectedReferenceAttributeInheritanceSchemaMutation;
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
 * Tests for {@link ReflectedReferenceSchemaBuilder} verifying description, deprecation,
 * cardinality inheritance, attribute inheritance modes, scope-based indexing and faceting,
 * own attributes, sortable attribute compounds, and mutation generation.
 *
 * Prohibited operations (group type, nonIndexed()) are already covered by
 * {@link ReflectedReferenceSchemaEditorTest} and are not duplicated here.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ReflectedReferenceSchemaBuilder")
class ReflectedReferenceSchemaBuilderTest {

	private EntitySchema productSchema;
	private EntitySchema categorySchema;
	private CatalogSchema catalogSchema;

	@BeforeEach
	void setUp() {
		this.productSchema = EntitySchema._internalBuild(Entities.PRODUCT);
		this.categorySchema = EntitySchema._internalBuild(Entities.CATEGORY);
		this.catalogSchema = CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG,
			NamingConvention.generate(APITestConstants.TEST_CATALOG),
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return List.of(
						ReflectedReferenceSchemaBuilderTest.this.productSchema,
						ReflectedReferenceSchemaBuilderTest.this.categorySchema
					);
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(
					@Nonnull String entityType
				) {
					if (entityType.equals(
						ReflectedReferenceSchemaBuilderTest.this.productSchema.getName()
					)) {
						return of(
							ReflectedReferenceSchemaBuilderTest.this.productSchema
						);
					} else if (entityType.equals(
						ReflectedReferenceSchemaBuilderTest.this.categorySchema.getName()
					)) {
						return of(
							ReflectedReferenceSchemaBuilderTest.this.categorySchema
						);
					}
					return empty();
				}
			}
		);
	}

	/**
	 * Creates an entity schema builder for the category entity (which hosts reflected references).
	 *
	 * @return new entity schema builder instance
	 */
	@Nonnull
	private EntitySchemaBuilder createCategorySchemaBuilder() {
		return new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.categorySchema
		);
	}

	/**
	 * Creates a reflected reference builder by first setting up a base reference
	 * on the product schema (product -> category) and then creating a reflected
	 * reference on the category schema (category -> product via "productCategory").
	 *
	 * The builder is captured via an {@link AtomicReference} so we can inspect
	 * mutations and inheritance state directly.
	 *
	 * @return the captured reflected reference schema builder
	 */
	@Nonnull
	private ReflectedReferenceSchemaBuilder captureReflectedBuilder(
		@Nonnull Consumer<ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder> whichIs
	) {
		// first, create the base reference on product -> category
		final EntitySchemaBuilder productBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema, this.productSchema
		);
		productBuilder.withReferenceToEntity(
			"productCategory", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
			ReferenceSchemaEditor::indexed
		);

		// now create reflected reference on category -> product
		final AtomicReference<ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder>
			captured = new AtomicReference<>();
		createCategorySchemaBuilder()
			.withReflectedReferenceToEntity(
				"categoryProducts", Entities.PRODUCT, "productCategory",
				builder -> {
					whichIs.accept(builder);
					captured.set(builder);
				}
			);

		final ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder result = captured.get();
		assertNotNull(result, "Builder should have been captured");
		return (ReflectedReferenceSchemaBuilder) result;
	}

	/**
	 * Builds a reflected reference schema on the category entity and extracts
	 * the resulting reference schema contract.
	 *
	 * @param whichIs consumer to configure the reflected reference builder
	 * @return the built reflected reference schema
	 */
	@Nonnull
	private ReflectedReferenceSchemaContract buildReflectedReference(
		@Nonnull Consumer<ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder> whichIs
	) {
		// first, create the base reference on product -> category
		final EntitySchemaBuilder productBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema, this.productSchema
		);
		productBuilder.withReferenceToEntity(
			"productCategory", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
			ReferenceSchemaEditor::indexed
		);

		// create reflected reference and build the schema
		final EntitySchemaContract schema = createCategorySchemaBuilder()
			.withReflectedReferenceToEntity(
				"categoryProducts", Entities.PRODUCT, "productCategory",
				whichIs
			)
			.toInstance();

		return (ReflectedReferenceSchemaContract) schema
			.getReference("categoryProducts").orElseThrow();
	}

	@Nested
	@DisplayName("Description operations")
	class DescriptionOperations {

		@Test
		@DisplayName("should set explicit description")
		void shouldSetDescription() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.withDescription("Category products")
			);

			assertAll(
				() -> assertEquals("Category products", ref.getDescription()),
				() -> assertFalse(ref.isDescriptionInherited())
			);
		}

		@Test
		@DisplayName("should inherit description when set to null")
		void shouldInheritDescription() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.withDescription("Category products")
					.withDescriptionInherited()
			);

			assertTrue(ref.isDescriptionInherited());
		}

		@Test
		@DisplayName("should override inherited description")
		void shouldOverrideInheritedDescription() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.withDescriptionInherited()
					.withDescription("My own description")
			);

			assertAll(
				() -> assertEquals("My own description", ref.getDescription()),
				() -> assertFalse(ref.isDescriptionInherited())
			);
		}
	}

	@Nested
	@DisplayName("Deprecation operations")
	class DeprecationOperations {

		@Test
		@DisplayName("should set explicit deprecation notice")
		void shouldSetDeprecationNotice() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.deprecated("Use direct reference")
			);

			assertAll(
				() -> assertEquals("Use direct reference", ref.getDeprecationNotice()),
				() -> assertFalse(ref.isDeprecatedInherited())
			);
		}

		@Test
		@DisplayName("should inherit deprecation when cleared")
		void shouldInheritDeprecation() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.deprecated("Deprecated")
					.withDeprecatedInherited()
			);

			assertTrue(ref.isDeprecatedInherited());
		}

		@Test
		@DisplayName("should delegate notDeprecatedAnymore to withDeprecatedInherited")
		void shouldDelegateNotDeprecatedAnymore() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.deprecated("Deprecated")
					.notDeprecatedAnymore()
			);

			// notDeprecatedAnymore() delegates to withDeprecatedInherited()
			assertTrue(ref.isDeprecatedInherited());
		}
	}

	@Nested
	@DisplayName("Cardinality operations")
	class CardinalityOperations {

		@Test
		@DisplayName("should set explicit cardinality")
		void shouldSetExplicitCardinality() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.withCardinality(Cardinality.EXACTLY_ONE)
			);

			assertAll(
				() -> assertEquals(Cardinality.EXACTLY_ONE, ref.getCardinality()),
				() -> assertFalse(ref.isCardinalityInherited())
			);
		}

		@Test
		@DisplayName("should inherit cardinality")
		void shouldInheritCardinality() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.withCardinality(Cardinality.EXACTLY_ONE)
					.withCardinalityInherited()
			);

			assertTrue(ref.isCardinalityInherited());
		}

		@Test
		@DisplayName("should override inherited cardinality")
		void shouldOverrideInheritedCardinality() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.withCardinalityInherited()
					.withCardinality(Cardinality.ZERO_OR_ONE)
			);

			assertAll(
				() -> assertEquals(Cardinality.ZERO_OR_ONE, ref.getCardinality()),
				() -> assertFalse(ref.isCardinalityInherited())
			);
		}
	}

	@Nested
	@DisplayName("Attribute inheritance")
	class AttributeInheritanceTests {

		@Test
		@DisplayName("should set inherit all attributes mode")
		void shouldSetInheritAllMode() {
			final ReflectedReferenceSchemaBuilder builder = captureReflectedBuilder(
				ReflectedReferenceSchemaEditor::withAttributesInherited
			);

			final ReflectedReferenceSchemaContract schema =
				(ReflectedReferenceSchemaContract) builder.toResult().schema();

			assertEquals(
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				schema.getAttributesInheritanceBehavior()
			);
		}

		@Test
		@DisplayName("should set inherit no attributes mode")
		void shouldSetInheritNoneMode() {
			final ReflectedReferenceSchemaBuilder builder = captureReflectedBuilder(
				ReflectedReferenceSchemaEditor::withoutAttributesInherited
			);

			final ReflectedReferenceSchemaContract schema =
				(ReflectedReferenceSchemaContract) builder.toResult().schema();

			assertEquals(
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				schema.getAttributesInheritanceBehavior()
			);
		}

		@Test
		@DisplayName("should set inherit specific attributes by name")
		void shouldSetInheritSpecificAttributes() {
			final ReflectedReferenceSchemaBuilder builder = captureReflectedBuilder(
				whichIs -> whichIs.withAttributesInherited("name", "code")
			);

			final ReflectedReferenceSchemaContract schema =
				(ReflectedReferenceSchemaContract) builder.toResult().schema();

			assertAll(
				() -> assertEquals(
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					schema.getAttributesInheritanceBehavior()
				),
				() -> assertArrayEquals(
					new String[]{"name", "code"},
					schema.getAttributeInheritanceFilter()
				)
			);
		}

		@Test
		@DisplayName("should set inherit all attributes except specified")
		void shouldSetInheritAllExcept() {
			final ReflectedReferenceSchemaBuilder builder = captureReflectedBuilder(
				whichIs -> whichIs.withAttributesInheritedExcept("internal")
			);

			final ReflectedReferenceSchemaContract schema =
				(ReflectedReferenceSchemaContract) builder.toResult().schema();

			assertAll(
				() -> assertEquals(
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					schema.getAttributesInheritanceBehavior()
				),
				() -> assertArrayEquals(
					new String[]{"internal"},
					schema.getAttributeInheritanceFilter()
				)
			);
		}

		@Test
		@DisplayName("should switch from inherit-all to inherit-specific")
		void shouldSwitchInheritanceModes() {
			final ReflectedReferenceSchemaBuilder builder = captureReflectedBuilder(
				whichIs -> whichIs
					.withAttributesInherited()
					.withAttributesInherited("name")
			);

			final ReflectedReferenceSchemaContract schema =
				(ReflectedReferenceSchemaContract) builder.toResult().schema();

			assertAll(
				() -> assertEquals(
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					schema.getAttributesInheritanceBehavior()
				),
				() -> assertArrayEquals(
					new String[]{"name"},
					schema.getAttributeInheritanceFilter()
				)
			);
		}

		@Test
		@DisplayName("should generate attribute inheritance mutation")
		void shouldGenerateAttributeInheritanceMutation() {
			final ReflectedReferenceSchemaBuilder builder = captureReflectedBuilder(
				ReflectedReferenceSchemaEditor::withAttributesInherited
			);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(builder.toMutation());

			final boolean hasInheritanceMutation = mutations.stream()
				.anyMatch(ModifyReflectedReferenceAttributeInheritanceSchemaMutation.class::isInstance);

			assertTrue(hasInheritanceMutation, "Should contain attribute inheritance mutation");
		}
	}

	@Nested
	@DisplayName("Scope-based indexing")
	class ScopeBasedIndexing {

		@Test
		@DisplayName("should set explicit indexed scope")
		void shouldSetExplicitIndexedScope() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.indexedInScope(Scope.LIVE)
			);

			assertAll(
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertFalse(ref.isIndexedInherited())
			);
		}

		@Test
		@DisplayName("should set indexed in both scopes")
		void shouldSetIndexedInBothScopes() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.indexedInScope(Scope.LIVE, Scope.ARCHIVED)
			);

			assertAll(
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertTrue(ref.isIndexedInScope(Scope.ARCHIVED)),
				() -> assertFalse(ref.isIndexedInherited())
			);
		}

		@Test
		@DisplayName("should have indexed inherited by default")
		void shouldHaveIndexedInheritedByDefault() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> {}
			);

			assertTrue(ref.isIndexedInherited());
		}

		@Test
		@DisplayName("should throw when calling indexedInScope with empty array")
		void shouldThrowOnEmptyIndexedScope() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> buildReflectedReference(
					whichIs -> whichIs.indexedInScope(Scope.NO_SCOPE)
				)
			);
		}

		@Test
		@DisplayName("should set reference indexed for filtering only")
		void shouldSetIndexedForFilteringOnly() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE)
			);

			assertTrue(ref.isIndexedInScope(Scope.LIVE));
		}

		@Test
		@DisplayName("should set reference indexed for filtering and partitioning")
		void shouldSetIndexedForFilteringAndPartitioning() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
			);

			assertTrue(ref.isIndexedInScope(Scope.LIVE));
		}
	}

	@Nested
	@DisplayName("Indexed components")
	class IndexedComponentsTests {

		@Test
		@DisplayName("should set indexed components on reflected reference")
		void shouldSetIndexedComponents() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
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
				() -> assertFalse(ref.isIndexedComponentsInherited()),
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
		@DisplayName("should inherit indexed components by default")
		void shouldInheritIndexedComponentsByDefault() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> {}
			);

			assertTrue(ref.isIndexedComponentsInherited());
		}

		@Test
		@DisplayName("should set inherited after explicit components")
		void shouldSetInheritedAfterExplicitComponents() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.indexedInScope(Scope.LIVE)
					.indexedWithComponentsInScope(
						Scope.LIVE,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withIndexedComponentsInherited()
			);

			assertTrue(ref.isIndexedComponentsInherited());
		}

		@Test
		@DisplayName("should override inherited with explicit components")
		void shouldOverrideInheritedWithExplicit() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.withIndexedComponentsInherited()
					.indexedInScope(Scope.LIVE)
					.indexedWithComponentsInScope(
						Scope.LIVE,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
			);

			assertAll(
				() -> assertFalse(ref.isIndexedComponentsInherited()),
				() -> assertEquals(
					1,
					ref.getIndexedComponents(Scope.LIVE).size()
				),
				() -> assertTrue(
					ref.getIndexedComponents(Scope.LIVE).contains(
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
				)
			);
		}
	}

	@Nested
	@DisplayName("Faceting operations")
	class FacetingOperations {

		@Test
		@DisplayName("should set faceted in scope")
		void shouldSetFacetedInScope() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.facetedInScope(Scope.LIVE)
			);

			assertAll(
				() -> assertTrue(ref.isFacetedInScope(Scope.LIVE)),
				() -> assertFalse(ref.isFacetedInherited())
			);
		}

		@Test
		@DisplayName("should set faceted in both scopes")
		void shouldSetFacetedInBothScopes() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.facetedInScope(Scope.LIVE, Scope.ARCHIVED)
			);

			assertAll(
				() -> assertTrue(ref.isFacetedInScope(Scope.LIVE)),
				() -> assertTrue(ref.isFacetedInScope(Scope.ARCHIVED))
			);
		}

		@Test
		@DisplayName("should inherit faceted status")
		void shouldInheritFaceted() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.facetedInScope(Scope.LIVE)
					.withFacetedInherited()
			);

			assertTrue(ref.isFacetedInherited());
		}

		@Test
		@DisplayName("should make non-faceted without reflected reference")
		void shouldMakeNonFacetedWithoutReflectedRef() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.facetedInScope(Scope.LIVE)
					.nonFaceted()
			);

			assertAll(
				() -> assertFalse(ref.isFacetedInScope(Scope.LIVE)),
				() -> assertFalse(ref.isFacetedInherited())
			);
		}

		@Test
		@DisplayName("should implicitly index when setting faceted")
		void shouldImplicitlyIndexWhenFaceted() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.facetedInScope(Scope.LIVE)
			);

			assertTrue(
				ref.isIndexedInScope(Scope.LIVE),
				"Reference should be implicitly indexed when faceted"
			);
		}
	}

	@Nested
	@DisplayName("Own attributes")
	class OwnAttributes {

		@Test
		@DisplayName("should add own attribute")
		void shouldAddOwnAttribute() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.withAttribute("priority", Integer.class)
			);

			assertTrue(ref.getAttribute("priority").isPresent());
		}

		@Test
		@DisplayName("should add own attribute with customizer")
		void shouldAddOwnAttributeWithCustomizer() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs.withAttribute(
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
		@DisplayName("should remove own attribute")
		void shouldRemoveOwnAttribute() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
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
				() -> buildReflectedReference(
					whichIs -> whichIs
						.withAttribute("priority", Integer.class)
						.withAttribute("priority", String.class)
				)
			);
		}

		@Test
		@DisplayName("should add multiple own attributes")
		void shouldAddMultipleOwnAttributes() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.withAttribute("priority", Integer.class)
					.withAttribute("label", String.class)
			);

			assertAll(
				() -> assertTrue(ref.getAttribute("priority").isPresent()),
				() -> assertTrue(ref.getAttribute("label").isPresent()),
				() -> assertEquals(2, ref.getAttributes().size())
			);
		}
	}

	@Nested
	@DisplayName("Sortable attribute compounds")
	class SortableAttributeCompoundsTests {

		@Test
		@DisplayName("should add sortable attribute compound")
		void shouldAddCompound() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.withAttribute("name", String.class)
					.withAttribute("priority", Integer.class)
					.withSortableAttributeCompound(
						"namePriority",
						attributeElement("name"),
						attributeElement("priority")
					)
			);

			assertTrue(
				ref.getSortableAttributeCompound("namePriority").isPresent()
			);
		}

		@Test
		@DisplayName("should add compound with customizer")
		void shouldAddCompoundWithCustomizer() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
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
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
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
	}

	@Nested
	@DisplayName("Mutation generation")
	class MutationGeneration {

		@Test
		@DisplayName("should generate CreateReflectedReferenceSchemaMutation as first mutation")
		void shouldGenerateCreateMutation() {
			final ReflectedReferenceSchemaBuilder builder = captureReflectedBuilder(
				whichIs -> whichIs.withDescription("Category products")
			);

			final Collection<LocalEntitySchemaMutation> mutations = builder.toMutation();

			assertFalse(mutations.isEmpty());
			assertInstanceOf(
				CreateReflectedReferenceSchemaMutation.class,
				mutations.iterator().next()
			);
		}

		@Test
		@DisplayName("should place attribute mutations after reference mutations")
		void shouldPlaceAttributeMutationsLast() {
			final ReflectedReferenceSchemaBuilder builder = captureReflectedBuilder(
				whichIs -> whichIs
					.withAttribute("priority", Integer.class)
					.facetedInScope(Scope.LIVE)
			);

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
			final ReflectedReferenceSchemaBuilder builder = captureReflectedBuilder(
				whichIs -> whichIs
					.withDescription("Category products")
					.indexedInScope(Scope.LIVE)
					.facetedInScope(Scope.LIVE)
					.withAttribute("priority", Integer.class)
			);

			final AbstractReferenceSchemaBuilder.ReferenceSchemaBuilderResult result = builder.toResult();

			assertAll(
				() -> assertNotNull(result.schema()),
				() -> assertFalse(result.mutations().isEmpty()),
				() -> assertEquals("categoryProducts", result.schema().getName()),
				() -> assertEquals("Category products", result.schema().getDescription()),
				() -> assertTrue(result.schema().isIndexedInScope(Scope.LIVE)),
				() -> assertTrue(result.schema().isFacetedInScope(Scope.LIVE))
			);
		}
	}

	@Nested
	@DisplayName("Method chaining")
	class MethodChaining {

		@Test
		@DisplayName("should support full fluent chain combining inheritance and own operations")
		void shouldSupportFluentChain() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.withDescription("Product categories from category perspective")
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.indexedInScope(Scope.LIVE)
					.facetedInScope(Scope.LIVE)
					.withoutAttributesInherited()
					.withAttribute("ownPriority", Integer.class,
						attr -> attr.sortable().nullable())
					.withAttribute("ownLabel", String.class)
					.withSortableAttributeCompound(
						"priorityLabel",
						attributeElement("ownPriority"),
						attributeElement("ownLabel")
					)
			);

			assertAll(
				() -> assertEquals(
					"Product categories from category perspective",
					ref.getDescription()
				),
				() -> assertFalse(ref.isDescriptionInherited()),
				() -> assertEquals(Cardinality.ZERO_OR_MORE, ref.getCardinality()),
				() -> assertFalse(ref.isCardinalityInherited()),
				() -> assertTrue(ref.isIndexedInScope(Scope.LIVE)),
				() -> assertFalse(ref.isIndexedInherited()),
				() -> assertTrue(ref.isFacetedInScope(Scope.LIVE)),
				() -> assertFalse(ref.isFacetedInherited()),
				() -> assertEquals(
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					ref.getAttributesInheritanceBehavior()
				),
				() -> assertTrue(ref.getAttribute("ownPriority").isPresent()),
				() -> assertTrue(ref.getAttribute("ownLabel").isPresent()),
				() -> assertTrue(
					ref.getSortableAttributeCompound("priorityLabel").isPresent()
				)
			);
		}

		@Test
		@DisplayName("should support switching between inherited and explicit values")
		void shouldSupportSwitchingInheritedAndExplicit() {
			final ReflectedReferenceSchemaContract ref = buildReflectedReference(
				whichIs -> whichIs
					.withDescription("First")
					.withDescriptionInherited()
					.withDescription("Final")
					.withCardinality(Cardinality.EXACTLY_ONE)
					.withCardinalityInherited()
					.withCardinality(Cardinality.ZERO_OR_ONE)
					.deprecated("Deprecated")
					.withDeprecatedInherited()
			);

			assertAll(
				() -> assertEquals("Final", ref.getDescription()),
				() -> assertFalse(ref.isDescriptionInherited()),
				() -> assertEquals(Cardinality.ZERO_OR_ONE, ref.getCardinality()),
				() -> assertFalse(ref.isCardinalityInherited()),
				() -> assertTrue(ref.isDeprecatedInherited())
			);
		}
	}

	@Nested
	@DisplayName("Mutation absorption")
	class MutationAbsorption {

		@Test
		@DisplayName(
			"should keep indexed separate from CreateReflected"
		)
		void shouldKeepIndexedSeparateFromCreateReflected() {
			final ReflectedReferenceSchemaBuilder builder =
				captureReflectedBuilder(
					whichIs -> whichIs.indexedInScope(Scope.LIVE)
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(builder.toMutation());

			assertAll(
				() -> assertEquals(
					2, mutations.size(),
					"CreateReflected + SetIndexed"
				),
				() -> assertInstanceOf(
					CreateReflectedReferenceSchemaMutation.class,
					mutations.get(0)
				),
				() -> assertInstanceOf(
					SetReferenceSchemaIndexedMutation.class,
					mutations.get(1)
				)
			);
		}

		@Test
		@DisplayName("should absorb faceted into CreateReflected")
		void shouldAbsorbFacetedIntoCreateReflected() {
			final ReflectedReferenceSchemaBuilder builder =
				captureReflectedBuilder(
					whichIs -> whichIs.facetedInScope(Scope.LIVE)
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(builder.toMutation());

			final CreateReflectedReferenceSchemaMutation create =
				mutations.stream()
					.filter(
						CreateReflectedReferenceSchemaMutation
							.class::isInstance
					)
					.map(
						CreateReflectedReferenceSchemaMutation
							.class::cast
					)
					.findFirst()
					.orElseThrow();

			assertAll(
				() -> assertTrue(
					mutations.stream().noneMatch(
						SetReferenceSchemaFacetedMutation
							.class::isInstance
					),
					"No separate faceted mutation"
				),
				() -> assertTrue(
					create.isFaceted(),
					"CreateReflected should have faceted flag"
				)
			);
		}

		@Test
		@DisplayName(
			"should keep indexed with components separate "
				+ "from CreateReflected"
		)
		void shouldKeepIndexedWithComponentsSeparateFromCreateReflected() {
			final ReflectedReferenceSchemaBuilder builder =
				captureReflectedBuilder(
					whichIs -> whichIs
						.indexedWithComponentsInScope(
							Scope.LIVE,
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents
								.REFERENCED_GROUP_ENTITY
						)
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(builder.toMutation());

			final SetReferenceSchemaIndexedMutation setIndexed =
				mutations.stream()
					.filter(
						SetReferenceSchemaIndexedMutation
							.class::isInstance
					)
					.map(
						SetReferenceSchemaIndexedMutation
							.class::cast
					)
					.findFirst()
					.orElseThrow();

			assertAll(
				() -> assertEquals(
					2, mutations.size(),
					"CreateReflected + SetIndexed"
				),
				() -> assertInstanceOf(
					CreateReflectedReferenceSchemaMutation.class,
					mutations.get(0)
				),
				() -> assertNotNull(
					setIndexed.getIndexedComponentsInScopes(),
					"SetIndexed should have components"
				)
			);
		}

		@Test
		@DisplayName(
			"should absorb faceted but keep indexed separate "
				+ "for CreateReflected"
		)
		void shouldAbsorbFacetedButKeepIndexedSeparateForCreateReflected() {
			final ReflectedReferenceSchemaBuilder builder =
				captureReflectedBuilder(
					whichIs -> whichIs
						.indexedInScope(Scope.LIVE)
						.facetedInScope(Scope.LIVE)
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(builder.toMutation());

			final CreateReflectedReferenceSchemaMutation create =
				mutations.stream()
					.filter(
						CreateReflectedReferenceSchemaMutation
							.class::isInstance
					)
					.map(
						CreateReflectedReferenceSchemaMutation
							.class::cast
					)
					.findFirst()
					.orElseThrow();

			assertAll(
				() -> assertEquals(
					2, mutations.size(),
					"CreateReflected(with faceted) + SetIndexed"
				),
				() -> assertInstanceOf(
					CreateReflectedReferenceSchemaMutation.class,
					mutations.get(0)
				),
				() -> assertInstanceOf(
					SetReferenceSchemaIndexedMutation.class,
					mutations.get(1)
				),
				() -> assertTrue(
					create.isFaceted(),
					"Faceted should be absorbed into CreateReflected"
				),
				() -> assertTrue(
					mutations.stream().noneMatch(
						SetReferenceSchemaFacetedMutation
							.class::isInstance
					),
					"No separate faceted mutation"
				)
			);
		}

		@Test
		@DisplayName(
			"should keep indexed with explicit scopes "
				+ "separate from CreateReflected"
		)
		void shouldKeepIndexedWithExplicitScopesSeparateFromCreateReflected() {
			final ReflectedReferenceSchemaBuilder builder =
				captureReflectedBuilder(
					whichIs -> whichIs
						.indexedInScope(Scope.LIVE)
				);

			final List<LocalEntitySchemaMutation> mutations =
				List.copyOf(builder.toMutation());

			final SetReferenceSchemaIndexedMutation setIndexed =
				mutations.stream()
					.filter(
						SetReferenceSchemaIndexedMutation
							.class::isInstance
					)
					.map(
						SetReferenceSchemaIndexedMutation
							.class::cast
					)
					.findFirst()
					.orElseThrow();

			assertAll(
				() -> assertEquals(
					2, mutations.size(),
					"CreateReflected + SetIndexed"
				),
				() -> assertInstanceOf(
					CreateReflectedReferenceSchemaMutation.class,
					mutations.get(0)
				),
				() -> assertNotNull(
					setIndexed.getIndexedInScopes(),
					"Scopes should be explicit (not inherited)"
				),
				() -> assertEquals(
					1,
					setIndexed.getIndexedInScopes().length,
					"Should have one indexed scope"
				),
				() -> assertEquals(
					Scope.LIVE,
					setIndexed.getIndexedInScopes()[0].scope()
				)
			);
		}
	}
}
