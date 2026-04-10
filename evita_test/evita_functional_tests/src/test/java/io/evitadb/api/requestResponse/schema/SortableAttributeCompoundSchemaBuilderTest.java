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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
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

import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for
 * {@link io.evitadb.api.requestResponse.schema.builder.SortableAttributeCompoundSchemaBuilder}
 * verifying description, deprecation, scope-based indexing,
 * attribute element configuration, mutation generation, and
 * reference-level compound creation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("SortableAttributeCompoundSchemaBuilder")
class SortableAttributeCompoundSchemaBuilderTest {

	private EntitySchema productSchema;
	private CatalogSchema catalogSchema;

	@BeforeEach
	void setUp() {
		this.productSchema =
			EntitySchema._internalBuild(Entities.PRODUCT);
		this.catalogSchema = CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG,
			NamingConvention.generate(
				APITestConstants.TEST_CATALOG
			),
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract>
				getEntitySchemas() {
					return List.of(
						SortableAttributeCompoundSchemaBuilderTest
							.this.productSchema
					);
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract>
				getEntitySchema(
					@Nonnull String entityType
				) {
					if (entityType.equals(
						SortableAttributeCompoundSchemaBuilderTest
							.this.productSchema.getName()
					)) {
						return of(
							SortableAttributeCompoundSchemaBuilderTest
								.this.productSchema
						);
					}
					return empty();
				}
			}
		);
	}

	/**
	 * Creates a fresh entity schema builder for the product
	 * entity with two pre-defined sortable attributes
	 * (`code` and `name`) required by compound schemas.
	 *
	 * @return new entity schema builder with attributes
	 */
	@Nonnull
	private EntitySchemaBuilder
	createEntitySchemaBuilderWithAttributes() {
		return new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		)
			.withAttribute("code", String.class)
			.withAttribute("name", String.class);
	}

	/**
	 * Creates a fresh entity schema builder without any
	 * pre-defined attributes.
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

	@Nested
	@DisplayName("Description and deprecation")
	class DescriptionAndDeprecation {

		@Test
		@DisplayName(
			"should set description on compound"
		)
		void shouldSetDescription() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.withDescription(
								"Sorts by code then name"
							)
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertEquals(
				"Sorts by code then name",
				compound.getDescription()
			);
		}

		@Test
		@DisplayName(
			"should set null description on compound"
		)
		void shouldSetNullDescription() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.withDescription(
								"Initial description"
							)
							.withDescription(null)
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertNull(
				compound.getDescription(),
				"Description should be null after "
					+ "setting it to null"
			);
		}

		@Test
		@DisplayName(
			"should set deprecation notice on compound"
		)
		void shouldSetDeprecationNotice() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs.deprecated(
							"Use nameWithCode instead"
						)
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertEquals(
				"Use nameWithCode instead",
				compound.getDeprecationNotice()
			);
		}

		@Test
		@DisplayName(
			"should remove deprecation from compound"
		)
		void shouldRemoveDeprecation() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.deprecated(
								"Use nameWithCode instead"
							)
							.notDeprecatedAnymore()
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertNull(
				compound.getDeprecationNotice(),
				"Deprecation should be null after "
					+ "calling notDeprecatedAnymore()"
			);
		}
	}

	@Nested
	@DisplayName("Scope operations")
	class ScopeOperations {

		@Test
		@DisplayName(
			"should be indexed in LIVE scope by default"
		)
		void shouldBeIndexedInLiveScopeByDefault() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						attributeElement("code"),
						attributeElement("name")
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertTrue(
				compound.isIndexedInScope(Scope.LIVE),
				"Compound should be indexed in LIVE "
					+ "scope by default"
			);
			assertFalse(
				compound.isIndexedInScope(Scope.ARCHIVED),
				"Compound should not be indexed in "
					+ "ARCHIVED scope by default"
			);
		}

		@Test
		@DisplayName(
			"should set indexed in ARCHIVED scope"
		)
		void shouldSetIndexedInArchivedScope() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.indexedInScope(
								Scope.ARCHIVED
							)
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertTrue(
				compound.isIndexedInScope(Scope.ARCHIVED),
				"Compound should be indexed in "
					+ "ARCHIVED scope"
			);
		}

		@Test
		@DisplayName(
			"should set indexed in both scopes"
		)
		void shouldSetIndexedInBothScopes() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.indexedInScope(
								Scope.LIVE,
								Scope.ARCHIVED
							)
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertTrue(
				compound.isIndexedInScope(Scope.LIVE),
				"Compound should be indexed in "
					+ "LIVE scope"
			);
			assertTrue(
				compound.isIndexedInScope(Scope.ARCHIVED),
				"Compound should be indexed in "
					+ "ARCHIVED scope"
			);
		}

		@Test
		@DisplayName(
			"should remove index from LIVE scope"
		)
		void shouldRemoveIndexFromLiveScope() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.nonIndexed(Scope.LIVE)
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertFalse(
				compound.isIndexedInScope(Scope.LIVE),
				"Compound should not be indexed in "
					+ "LIVE scope after nonIndexed(LIVE)"
			);
		}

		@Test
		@DisplayName(
			"should remove all indexes via nonIndexed()"
		)
		void shouldRemoveAllIndexesViaNonIndexed() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.indexedInScope(
								Scope.LIVE,
								Scope.ARCHIVED
							)
							.nonIndexed()
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertFalse(
				compound.isIndexedInScope(Scope.LIVE),
				"LIVE scope should be removed"
			);
			assertFalse(
				compound.isIndexedInScope(Scope.ARCHIVED),
				"ARCHIVED scope should be removed"
			);
			assertFalse(
				compound.isIndexedInAnyScope(),
				"Compound should not be indexed "
					+ "in any scope"
			);
		}

		@Test
		@DisplayName(
			"should use indexed() shortcut for default"
		)
		void shouldUseIndexedShortcutForDefaultScope() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.nonIndexed()
							.indexed()
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertTrue(
				compound.isIndexedInScope(Scope.LIVE),
				"indexed() should restore LIVE scope"
			);
			assertFalse(
				compound.isIndexedInScope(Scope.ARCHIVED),
				"indexed() should not add ARCHIVED"
			);
		}
	}

	@Nested
	@DisplayName("Compound schema properties")
	class CompoundSchemaProperties {

		@Test
		@DisplayName(
			"should build compound with all properties"
		)
		void shouldBuildCompleteCompound() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.withDescription("Full desc")
							.deprecated("Deprecated!")
							.indexedInScope(
								Scope.LIVE,
								Scope.ARCHIVED
							)
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertEquals(
				"codeWithName", compound.getName()
			);
			assertEquals(
				"Full desc", compound.getDescription()
			);
			assertEquals(
				"Deprecated!",
				compound.getDeprecationNotice()
			);
			assertTrue(
				compound.isIndexedInScope(Scope.LIVE)
			);
			assertTrue(
				compound.isIndexedInScope(Scope.ARCHIVED)
			);
		}

		@Test
		@DisplayName(
			"should return correct attribute elements"
		)
		void shouldReturnCorrectAttributeElements() {
			final AttributeElement codeElement =
				attributeElement(
					"code",
					OrderDirection.DESC,
					OrderBehaviour.NULLS_FIRST
				);
			final AttributeElement nameElement =
				attributeElement(
					"name",
					OrderDirection.ASC,
					OrderBehaviour.NULLS_LAST
				);

			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						codeElement,
						nameElement
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			final List<AttributeElement> elements =
				compound.getAttributeElements();

			assertEquals(2, elements.size());

			assertEquals(
				"code",
				elements.get(0).attributeName()
			);
			assertEquals(
				OrderDirection.DESC,
				elements.get(0).direction()
			);
			assertEquals(
				OrderBehaviour.NULLS_FIRST,
				elements.get(0).behaviour()
			);

			assertEquals(
				"name",
				elements.get(1).attributeName()
			);
			assertEquals(
				OrderDirection.ASC,
				elements.get(1).direction()
			);
			assertEquals(
				OrderBehaviour.NULLS_LAST,
				elements.get(1).behaviour()
			);
		}

		@Test
		@DisplayName(
			"should return defaults for new compound"
		)
		void shouldReturnDefaultValuesForNewCompound() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						attributeElement("code"),
						attributeElement("name")
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertNull(
				compound.getDescription(),
				"Default description should be null"
			);
			assertNull(
				compound.getDeprecationNotice(),
				"Default deprecation notice "
					+ "should be null"
			);
			assertTrue(
				compound.isIndexedInScope(Scope.LIVE),
				"Should be indexed in LIVE by default"
			);
			assertFalse(
				compound.isIndexedInScope(Scope.ARCHIVED),
				"Should not be indexed in ARCHIVED "
					+ "by default"
			);
		}

		@Test
		@DisplayName(
			"should return name variants for compound"
		)
		void shouldReturnNameVariantsForCompound() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						attributeElement("code"),
						attributeElement("name")
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertNotNull(
				compound.getNameVariant(
					NamingConvention.CAMEL_CASE
				)
			);
			assertEquals(
				"codeWithName",
				compound.getNameVariant(
					NamingConvention.CAMEL_CASE
				)
			);
		}

		@Test
		@DisplayName(
			"should use default attribute element values"
		)
		void shouldUseDefaultAttributeElementValues() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						attributeElement("code"),
						attributeElement("name")
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			final List<AttributeElement> elements =
				compound.getAttributeElements();

			// attributeElement(name) defaults:
			// ASC direction, NULLS_LAST behaviour
			for (final AttributeElement element : elements) {
				assertEquals(
					OrderDirection.ASC,
					element.direction(),
					"Default direction should be ASC"
				);
				assertEquals(
					OrderBehaviour.NULLS_LAST,
					element.behaviour(),
					"Default behaviour should be "
						+ "NULLS_LAST"
				);
			}
		}
	}

	@Nested
	@DisplayName("Deprecation lifecycle")
	class DeprecationLifecycle {

		@Test
		@DisplayName(
			"should handle full deprecation lifecycle"
		)
		void shouldHandleFullDeprecationLifecycle() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.withDescription("Compound")
							.deprecated("Will be removed")
							.notDeprecatedAnymore()
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertNull(
				compound.getDeprecationNotice(),
				"Deprecation notice should be null "
					+ "after full lifecycle"
			);
			assertEquals(
				"Compound",
				compound.getDescription(),
				"Description should be preserved "
					+ "through deprecation lifecycle"
			);
		}
	}

	@Nested
	@DisplayName("Mutation generation")
	class MutationGeneration {

		@Test
		@DisplayName(
			"should generate create mutation by default"
		)
		void shouldGenerateCreateMutation() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						attributeElement("code"),
						attributeElement("name")
					)
					.toInstance();

			// verify compound exists and is valid
			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertNotNull(compound);
			assertEquals(
				"codeWithName", compound.getName()
			);
		}

		@Test
		@DisplayName(
			"should generate description mutation"
		)
		void shouldGenerateDescriptionMutation() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.withDescription(
								"Test description"
							)
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertEquals(
				"Test description",
				compound.getDescription()
			);
		}

		@Test
		@DisplayName(
			"should generate scope mutation"
		)
		void shouldGenerateScopeMutation() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilderWithAttributes()
					.withSortableAttributeCompound(
						"codeWithName",
						new AttributeElement[]{
							attributeElement("code"),
							attributeElement("name")
						},
						whichIs -> whichIs
							.indexedInScope(
								Scope.LIVE,
								Scope.ARCHIVED
							)
					)
					.toInstance();

			final SortableAttributeCompoundSchemaContract
				compound = schema
				.getSortableAttributeCompound(
					"codeWithName"
				)
				.orElseThrow();

			assertTrue(
				compound.isIndexedInScope(Scope.LIVE)
			);
			assertTrue(
				compound.isIndexedInScope(Scope.ARCHIVED)
			);
		}
	}

	@Nested
	@DisplayName("Reference-level compounds")
	class ReferenceLevelCompounds {

		@Test
		@DisplayName(
			"should create compound on reference"
		)
		void shouldCreateCompoundOnReference() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withReferenceTo(
						"brand",
						Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						ref -> ref
							.withAttribute(
								"priority",
								Integer.class
							)
							.withAttribute(
								"order",
								Integer.class
							)
							.withSortableAttributeCompound(
								"priorityWithOrder",
								new AttributeElement[]{
									attributeElement(
										"priority"
									),
									attributeElement(
										"order"
									)
								},
								whichIs -> whichIs
									.withDescription(
										"Reference "
											+ "compound"
									)
							)
					)
					.toInstance();

			final ReferenceSchemaContract refSchema =
				schema.getReference("brand")
					.orElseThrow();
			final SortableAttributeCompoundSchemaContract
				compound = refSchema
				.getSortableAttributeCompound(
					"priorityWithOrder"
				)
				.orElseThrow();

			assertEquals(
				"Reference compound",
				compound.getDescription()
			);
			assertEquals(
				2,
				compound.getAttributeElements().size()
			);
		}

		@Test
		@DisplayName(
			"should create compound with scope on "
				+ "reference"
		)
		void shouldCreateCompoundWithScopeOnReference() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withReferenceTo(
						"brand",
						Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						ref -> ref
							.withAttribute(
								"priority",
								Integer.class
							)
							.withAttribute(
								"order",
								Integer.class
							)
							.withSortableAttributeCompound(
								"priorityWithOrder",
								new AttributeElement[]{
									attributeElement(
										"priority"
									),
									attributeElement(
										"order"
									)
								},
								whichIs -> whichIs
									.indexedInScope(
										Scope.LIVE,
										Scope.ARCHIVED
									)
							)
					)
					.toInstance();

			final ReferenceSchemaContract refSchema =
				schema.getReference("brand")
					.orElseThrow();
			final SortableAttributeCompoundSchemaContract
				compound = refSchema
				.getSortableAttributeCompound(
					"priorityWithOrder"
				)
				.orElseThrow();

			assertTrue(
				compound.isIndexedInScope(Scope.LIVE)
			);
			assertTrue(
				compound.isIndexedInScope(Scope.ARCHIVED)
			);
		}
	}

	@Nested
	@DisplayName("Multiple compounds")
	class MultipleCompounds {

		@Test
		@DisplayName(
			"should create multiple compounds on "
				+ "same entity"
		)
		void shouldCreateMultipleCompounds() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAttribute("code", String.class)
					.withAttribute("name", String.class)
					.withAttribute(
						"priority", Integer.class
					)
					.withSortableAttributeCompound(
						"codeWithName",
						attributeElement("code"),
						attributeElement("name")
					)
					.withSortableAttributeCompound(
						"codeWithPriority",
						attributeElement("code"),
						attributeElement("priority")
					)
					.toInstance();

			assertTrue(
				schema.getSortableAttributeCompound(
					"codeWithName"
				).isPresent()
			);
			assertTrue(
				schema.getSortableAttributeCompound(
					"codeWithPriority"
				).isPresent()
			);
		}
	}

	@Nested
	@DisplayName("Attribute element factory methods")
	class AttributeElementFactoryMethods {

		@Test
		@DisplayName(
			"should create element with direction only"
		)
		void shouldCreateElementWithDirectionOnly() {
			final AttributeElement element =
				attributeElement(
					"code", OrderDirection.DESC
				);

			assertEquals("code", element.attributeName());
			assertEquals(
				OrderDirection.DESC, element.direction()
			);
			assertEquals(
				OrderBehaviour.NULLS_LAST,
				element.behaviour()
			);
		}

		@Test
		@DisplayName(
			"should create element with behaviour only"
		)
		void shouldCreateElementWithBehaviourOnly() {
			final AttributeElement element =
				attributeElement(
					"code", OrderBehaviour.NULLS_FIRST
				);

			assertEquals("code", element.attributeName());
			assertEquals(
				OrderDirection.ASC, element.direction()
			);
			assertEquals(
				OrderBehaviour.NULLS_FIRST,
				element.behaviour()
			);
		}

		@Test
		@DisplayName(
			"should create element with all parameters"
		)
		void shouldCreateElementWithAllParameters() {
			final AttributeElement element =
				attributeElement(
					"code",
					OrderDirection.DESC,
					OrderBehaviour.NULLS_FIRST
				);

			assertEquals("code", element.attributeName());
			assertEquals(
				OrderDirection.DESC, element.direction()
			);
			assertEquals(
				OrderBehaviour.NULLS_FIRST,
				element.behaviour()
			);
		}

		@Test
		@DisplayName(
			"should create element with defaults"
		)
		void shouldCreateElementWithDefaults() {
			final AttributeElement element =
				attributeElement("code");

			assertEquals("code", element.attributeName());
			assertEquals(
				OrderDirection.ASC, element.direction()
			);
			assertEquals(
				OrderBehaviour.NULLS_LAST,
				element.behaviour()
			);
		}
	}
}
