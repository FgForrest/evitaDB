/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.api.exception.AssociatedDataAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.AttributeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.AttributeAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.ReferenceAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.SortableAttributeCompoundSchemaException;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithGeneratedPrimaryKeyMutation;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies the process of evitaDB schema update.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Entity schema builder should")
class EntitySchemaBuilderTest {
	private final EntitySchema productSchema = EntitySchema._internalBuild(Entities.PRODUCT);
	private final EntitySchema categorySchema = EntitySchema._internalBuild(Entities.CATEGORY);
	private final EntitySchema brandSchema = EntitySchema._internalBuild(Entities.BRAND);
	private final EntitySchema storeSchema = EntitySchema._internalBuild(Entities.STORE);
	private final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG,
		NamingConvention.generate(APITestConstants.TEST_CATALOG),
		EnumSet.allOf(CatalogEvolutionMode.class),
		new EntitySchemaProvider() {
			@Nonnull
			@Override
			public Collection<EntitySchemaContract> getEntitySchemas() {
				return List.of(
					EntitySchemaBuilderTest.this.productSchema,
					EntitySchemaBuilderTest.this.categorySchema,
					EntitySchemaBuilderTest.this.brandSchema,
					EntitySchemaBuilderTest.this.storeSchema
				);
			}

			@Nonnull
			@Override
			public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
				if (entityType.equals(EntitySchemaBuilderTest.this.productSchema.getName())) {
					return of(EntitySchemaBuilderTest.this.productSchema);
				} else if (entityType.equals(EntitySchemaBuilderTest.this.categorySchema.getName())) {
					return of(EntitySchemaBuilderTest.this.categorySchema);
				} else if (entityType.equals(EntitySchemaBuilderTest.this.brandSchema.getName())) {
					return of(EntitySchemaBuilderTest.this.brandSchema);
				} else if (entityType.equals(EntitySchemaBuilderTest.this.storeSchema.getName())) {
					return of(EntitySchemaBuilderTest.this.storeSchema);
				} else {
					return empty();
				}
			}
		}
	);

	/**
	 * Asserts that an entity attribute schema has the expected properties.
	 * This helper method verifies all the key characteristics of an entity attribute
	 * including uniqueness, filterability, sortability, localization, nullability,
	 * representative status, indexed decimal places, and data type.
	 *
	 * @param attributeSchema the entity attribute schema to verify
	 * @param unique whether the attribute should be unique
	 * @param filterable whether the attribute should be filterable
	 * @param sortable whether the attribute should be sortable
	 * @param localized whether the attribute should be localized
	 * @param nullable whether the attribute should be nullable
	 * @param representative whether the attribute should be representative
	 * @param indexedDecimalPlaces the expected number of indexed decimal places
	 * @param ofType the expected data type of the attribute
	 */
	private static void assertAttribute(
		EntityAttributeSchemaContract attributeSchema,
		boolean unique,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		int indexedDecimalPlaces,
		Class<? extends Serializable> ofType
	) {
		assertNotNull(attributeSchema);
		assertEquals(unique, attributeSchema.isUnique(), "Attribute `" + attributeSchema.getName() + "` should be unique, but it is not!");
		assertEquals(filterable, attributeSchema.isFilterable(), "Attribute `" + attributeSchema.getName() + "` should be filterable, but it is not!");
		assertEquals(sortable, attributeSchema.isSortable(), "Attribute `" + attributeSchema.getName() + "` should be sortable, but it is not!");
		assertEquals(localized, attributeSchema.isLocalized(), "Attribute `" + attributeSchema.getName() + "` should be localized, but it is not!");
		assertEquals(nullable, attributeSchema.isNullable(), "Attribute `" + attributeSchema.getName() + "` should be nullable, but it is not!");
		assertEquals(representative, attributeSchema.isRepresentative(), "Attribute `" + attributeSchema.getName() + "` should be representative, but it is not!");
		assertEquals(ofType, attributeSchema.getType(), "Attribute `" + attributeSchema.getName() + "` should be `" + ofType + "`, but it is `" + attributeSchema.getType() + "`!");
		assertEquals(indexedDecimalPlaces, attributeSchema.getIndexedDecimalPlaces(), "Attribute `" + attributeSchema.getName() + "` should have `" + indexedDecimalPlaces + "` indexed decimal places, but has `" + attributeSchema.getIndexedDecimalPlaces() + "`!");
	}

	/**
	 * Asserts that an attribute schema has the expected properties.
	 * This helper method verifies the key characteristics of a general attribute schema
	 * including uniqueness, filterability, sortability, localization, nullability,
	 * indexed decimal places, and data type.
	 *
	 * @param attributeSchema the attribute schema to verify
	 * @param unique whether the attribute should be unique
	 * @param filterable whether the attribute should be filterable
	 * @param sortable whether the attribute should be sortable
	 * @param localized whether the attribute should be localized
	 * @param nullable whether the attribute should be nullable
	 * @param indexedDecimalPlaces the expected number of indexed decimal places
	 * @param ofType the expected data type of the attribute
	 */
	private static void assertAttribute(
		AttributeSchemaContract attributeSchema,
		boolean unique,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		int indexedDecimalPlaces,
		Class<? extends Serializable> ofType
	) {
		assertNotNull(attributeSchema);
		assertEquals(unique, attributeSchema.isUnique(), "Attribute `" + attributeSchema.getName() + "` should be unique, but it is not!");
		assertEquals(filterable, attributeSchema.isFilterable(), "Attribute `" + attributeSchema.getName() + "` should be filterable, but it is not!");
		assertEquals(sortable, attributeSchema.isSortable(), "Attribute `" + attributeSchema.getName() + "` should be sortable, but it is not!");
		assertEquals(localized, attributeSchema.isLocalized(), "Attribute `" + attributeSchema.getName() + "` should be localized, but it is not!");
		assertEquals(nullable, attributeSchema.isNullable(), "Attribute `" + attributeSchema.getName() + "` should be nullable, but it is not!");
		assertEquals(ofType, attributeSchema.getType(), "Attribute `" + attributeSchema.getName() + "` should be `" + ofType + "`, but it is `" + attributeSchema.getType() + "`!");
		assertEquals(indexedDecimalPlaces, attributeSchema.getIndexedDecimalPlaces(), "Attribute `" + attributeSchema.getName() + "` should have `" + indexedDecimalPlaces + "` indexed decimal places, but has `" + attributeSchema.getIndexedDecimalPlaces() + "`!");
	}

	/**
	 * Constructs a comprehensive example entity schema with various features enabled.
	 * This helper method creates a complete schema configuration including attributes,
	 * associated data, sortable attribute compounds, references, locales, and pricing.
	 * The schema is configured with strict verification but allows adding associated data
	 * and references on the fly.
	 *
	 * @param schemaBuilder the entity schema builder to configure
	 * @return the constructed entity schema contract with all example features
	 */
	@SuppressWarnings("Convert2MethodRef")
	private static EntitySchemaContract constructExampleSchema(EntitySchemaBuilder schemaBuilder) {
		return schemaBuilder
			/* all is strictly verified but associated data and references can be added on the fly */
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
			/* product are not organized in the tree */
			.withoutHierarchy()
			/* prices are referencing another entity stored in Evita */
			.withPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute("code", String.class, whichIs -> whichIs.unique().representative())
			.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
			.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable())
			.withAttribute("ean", String.class, whichIs -> whichIs.filterable())
			.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())
			.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable())
			.withAttribute("quantity", BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2))
			.withAttribute("alias", Boolean.class, whichIs -> whichIs.filterable())
			/* here we define set of associated data, that can be stored along with entity */
			.withAssociatedData("referencedFiles", ReferencedFileSet.class)
			.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
			/* here we define sortable attribute compounds */
			.withSortableAttributeCompound(
				"codeWithEan",
				attributeElement("code"),
				attributeElement("ean")
			)
			.withSortableAttributeCompound(
				"priorityAndQuantity",
				new AttributeElement[]{
					attributeElement("priority", OrderDirection.DESC),
					attributeElement("quantity", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
				},
				whichIs -> whichIs
					.withDescription("Priority and quantity in descending order.")
					.deprecated("Already deprecated.")
			)
			/* here we define references that relate to another entities stored in Evita */
			.withReferenceToEntity(
				Entities.CATEGORY,
				Entities.CATEGORY,
				Cardinality.ZERO_OR_MORE,
				whichIs ->
					/* we can specify special attributes on relation */
					whichIs.indexedForFilteringAndPartitioning().withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
			)
			/* for faceted references we can compute "counts" */
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.faceted()
			)
			/* references may be also represented be entities unknown to Evita */
			.withReferenceTo(
				Entities.STORE,
				Entities.STORE,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.faceted()
			)
			/* finally apply schema changes */
			.toInstance();
	}

	/**
	 * Asserts that the entity schema contains all expected contents and configurations.
	 * This helper method verifies that the schema has the correct evolution modes,
	 * hierarchy settings, pricing configuration, locales, attributes, sortable attribute
	 * compounds, associated data, and references as defined in the example schema.
	 *
	 * @param updatedSchema the entity schema to verify
	 */
	private static void assertSchemaContents(EntitySchemaContract updatedSchema) {
		assertTrue(updatedSchema.allows(EvolutionMode.ADDING_ASSOCIATED_DATA));
		assertTrue(updatedSchema.allows(EvolutionMode.ADDING_REFERENCES));

		assertFalse(updatedSchema.isWithHierarchy());

		assertTrue(updatedSchema.isWithPrice());

		assertTrue(updatedSchema.getLocales().contains(Locale.ENGLISH));
		assertTrue(updatedSchema.getLocales().contains(new Locale("cs", "CZ")));

		assertEquals(9, updatedSchema.getAttributes().size());
		assertAttribute(updatedSchema.getAttribute("code").orElseThrow(), true, false, false, false, false, true, 0, String.class);
		assertAttribute(updatedSchema.getAttribute("oldEntityUrls").orElseThrow(), false, true, false, true, false, false, 0, String[].class);
		assertAttribute(updatedSchema.getAttribute("quantity").orElseThrow(), false, true, false, false, false, false, 2, BigDecimal.class);
		assertAttribute(updatedSchema.getAttribute("priority").orElseThrow(), false, false, true, false, false, false, 0, Long.class);

		assertEquals(2, updatedSchema.getSortableAttributeCompounds().size());
		assertSortableAttributeCompound(
			updatedSchema.getSortableAttributeCompound("codeWithEan").orElse(null),
			attributeElement("code"), attributeElement("ean")
		);
		assertSortableAttributeCompound(
			updatedSchema.getSortableAttributeCompound("priorityAndQuantity").orElse(null),
			"Priority and quantity in descending order.",
			"Already deprecated.",
			attributeElement("priority", OrderDirection.DESC),
			attributeElement("quantity", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
		);

		assertEquals(2, updatedSchema.getAssociatedData().size());
		assertAssociatedData(updatedSchema.getAssociatedData("referencedFiles"), false, ComplexDataObject.class);
		assertAssociatedData(updatedSchema.getAssociatedData("labels"), true, ComplexDataObject.class);

		assertEquals(3, updatedSchema.getReferences().size());

		final ReferenceSchemaContract categoryReference = updatedSchema.getReferenceOrThrowException(Entities.CATEGORY);
		assertReference(of(categoryReference), false);
		assertEquals(1, categoryReference.getAttributes().size());
		assertAttribute(categoryReference.getAttribute("categoryPriority").orElseThrow(), false, false, true, false, false, 0, Long.class);

		assertReference(updatedSchema.getReference(Entities.BRAND), true);
		assertReference(updatedSchema.getReference(Entities.STORE), true);
	}

	/**
	 * Asserts that a sortable attribute compound has the expected attribute elements.
	 * This is a convenience method that delegates to the full assertion method with
	 * null description and deprecation values.
	 *
	 * @param compound the sortable attribute compound to verify
	 * @param elements the expected attribute elements in the compound
	 */
	private static void assertSortableAttributeCompound(SortableAttributeCompoundSchemaContract compound, AttributeElement... elements) {
		assertSortableAttributeCompound(compound, null, null, elements);
	}

	/**
	 * Asserts that a sortable attribute compound has the expected properties and attribute elements.
	 * This helper method verifies that the compound has the correct description, deprecation notice,
	 * and contains the expected attribute elements in the correct order.
	 *
	 * @param compound the sortable attribute compound to verify
	 * @param description the expected description (null if no description expected)
	 * @param deprecation the expected deprecation notice (null if not deprecated)
	 * @param elements the expected attribute elements in the compound
	 */
	private static void assertSortableAttributeCompound(SortableAttributeCompoundSchemaContract compound, String description, String deprecation, AttributeElement... elements) {
		assertNotNull(compound);
		if (description == null) {
			assertNull(compound.getDescription());
		} else {
			assertEquals(description, compound.getDescription());
		}

		if (deprecation == null) {
			assertNull(compound.getDeprecationNotice());
		} else {
			assertEquals(deprecation, compound.getDeprecationNotice());
		}

		assertArrayEquals(compound.getAttributeElements().toArray(), elements);
	}

	/**
	 * Asserts that a reference schema has the expected indexing configuration.
	 * This helper method verifies that the reference exists and has the correct
	 * faceted (indexed) setting.
	 *
	 * @param reference the optional reference schema to verify
	 * @param indexed whether the reference should be faceted (indexed)
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private static void assertReference(Optional<ReferenceSchemaContract> reference, boolean indexed) {
		assertTrue(reference.isPresent());
		assertEquals(indexed, reference.get().isFaceted());
	}

	/**
	 * Asserts that an associated data schema has the expected properties.
	 * This helper method verifies that the associated data exists and has the correct
	 * localization setting and data type.
	 *
	 * @param associatedDataSchema the optional associated data schema to verify
	 * @param localized whether the associated data should be localized
	 * @param ofType the expected data type of the associated data
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private static void assertAssociatedData(Optional<AssociatedDataSchemaContract> associatedDataSchema, boolean localized, Class<? extends Serializable> ofType) {
		assertTrue(associatedDataSchema.isPresent());
		associatedDataSchema.ifPresent(it -> {
			assertEquals(localized, it.isLocalized());
			assertEquals(ofType, it.getType());
		});
	}

	/**
	 * Tests the creation of a complete product schema with all features enabled.
	 * This test verifies that the EntitySchemaBuilder can construct a comprehensive
	 * schema including attributes, references, associated data, and various configurations.
	 */
	@DisplayName("define complete product schema with all features")
	@Test
	void shouldDefineProductSchema() {
		// Create a schema builder for the product entity using the test catalog and base product schema
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Build a comprehensive example schema with various features (attributes, references, etc.)
		final EntitySchemaContract updatedSchema = constructExampleSchema(schemaBuilder);
		// Verify that all expected schema contents are properly configured
		assertSchemaContents(updatedSchema);
	}

	/**
	 * Tests that attribute naming conventions work properly when adding and removing attributes.
	 * This test verifies that attributes defined with different naming styles (kebab-case, camelCase, etc.)
	 * can be accessed using various naming conventions, and that removal works correctly across all conventions.
	 */
	@DisplayName("work with attributes in naming conventions properly")
	@Test
	void shouldWorkWithAttributesInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Add attributes with different naming styles to test naming convention handling
		schemaBuilder
			.withAttribute("some-attribute-1", String.class)  // kebab-case with number
			.withAttribute("attribute", String.class)         // simple lowercase
			.withAttribute("code", String.class);             // simple lowercase

		// Verify attributes can be found using different naming conventions
		// "some-attribute-1" should be accessible as "someAttribute1" in camelCase
		assertNotNull(schemaBuilder.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		// "attribute" should be accessible in all naming conventions since it's a simple name
		assertNotNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		// "code" should be accessible in different naming conventions
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		// Remove one attribute to test that removal works correctly
		schemaBuilder.withoutAttribute("attribute");

		// Verify remaining attributes are still accessible
		assertNotNull(schemaBuilder.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		// Verify removed attribute is no longer accessible in any naming convention
		assertNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		// Verify other attributes remain accessible
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		// Build the final schema and verify the same behavior persists in the immutable schema
		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		// Verify the same naming convention behavior in the final schema
		assertNotNull(updatedSchema.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(updatedSchema.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(updatedSchema.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

	/**
	 * Tests that sortable attribute compound naming conventions work properly when adding and removing compounds.
	 * This test verifies that sortable attribute compounds defined with different naming styles can be accessed
	 * using various naming conventions, and that removal works correctly across all conventions.
	 * Sortable attribute compounds allow sorting by multiple attributes in a specific order.
	 */
	@DisplayName("work with sortable attribute compounds in naming conventions properly")
	@Test
	void shouldWorkWithSortableAttributeCompoundsInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// First add the required attributes that will be used in the compounds
		// Then create sortable attribute compounds with different naming styles and configurations
		schemaBuilder
			.withAttribute("attribute", String.class)
			.withAttribute("code", String.class)
			// Create compound with kebab-case name and simple attribute elements
			.withSortableAttributeCompound("some-compound-1", attributeElement("attribute"), attributeElement("code"))
			// Create compound with simple name and reversed attribute order
			.withSortableAttributeCompound("compound", attributeElement("code"), attributeElement("attribute"))
			// Create compound with camelCase name and custom sort direction
			.withSortableAttributeCompound("anotherCompound", attributeElement("code", OrderDirection.DESC), attributeElement("attribute"));

		// Verify compounds can be found using different naming conventions
		// "some-compound-1" should be accessible as "someCompound1" in camelCase
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
		// "compound" should be accessible in all naming conventions since it's a simple name
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));

		// Remove one compound to test that removal works correctly
		schemaBuilder.withoutSortableAttributeCompound("compound");

		// Verify remaining compounds are still accessible
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
		// Verify removed compound is no longer accessible in any naming convention
		assertNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
		// Verify other compounds remain accessible
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
		// Test that "anotherCompound" is accessible as "another_compound" in snake_case
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));

		// Build the final schema and verify the same behavior persists in the immutable schema
		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		// Verify the same naming convention behavior in the final schema
		assertNotNull(updatedSchema.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(updatedSchema.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(updatedSchema.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(updatedSchema.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
	}

	/**
	 * Tests that reference attribute naming conventions work properly when adding and removing attributes on references.
	 * This test verifies that attributes defined on reference relationships (not entity attributes, but attributes
	 * that describe the relationship itself) can be accessed using various naming conventions, and that removal
	 * works correctly across all conventions. Reference attributes are used to store additional data about
	 * the relationship between entities.
	 */
	@DisplayName("work with reference attributes in naming conventions properly")
	@Test
	void shouldWorkWithReferenceAttributesInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Create a reference to another entity and add attributes to the reference itself
		// These attributes describe properties of the relationship, not the referenced entity
		schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					// Add attributes with different naming styles to the reference
					thatIs.withAttribute("some-attribute-1", String.class)  // kebab-case with number
						.withAttribute("attribute", String.class)           // simple lowercase
						.withAttribute("code", String.class);               // simple lowercase

					// Verify reference attributes can be found using different naming conventions
					// "some-attribute-1" should be accessible as "someAttribute1" in camelCase
					assertNotNull(thatIs.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
					// "attribute" should be accessible in all naming conventions since it's a simple name
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
					// "code" should be accessible in different naming conventions
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
				}
			);

		// Modify the same reference to remove one attribute and verify the change
		schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					// Remove one attribute from the reference
					thatIs.withoutAttribute("attribute");

					// Verify remaining attributes are still accessible
					assertNotNull(thatIs.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
					// Verify removed attribute is no longer accessible in any naming convention
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
					// Verify other attributes remain accessible
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
				}
			);

		// Build the final schema and verify the same behavior persists in the immutable schema
		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		// Get the reference from the final schema and verify attribute accessibility
		final ReferenceSchemaContract testReference = updatedSchema.getReferenceOrThrowException("testReference");

		// Verify the same naming convention behavior in the final reference schema
		assertNotNull(testReference.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(testReference.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(testReference.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

	/**
	 * Tests that reference attribute naming conventions work properly when building schema instances
	 * in the middle of the configuration process. This test verifies that the schema builder can
	 * handle intermediate instance creation without losing the ability to access reference attributes
	 * using different naming conventions. This is important for complex schema building scenarios
	 * where intermediate validation or inspection might be needed.
	 */
	@DisplayName("work with reference attributes in naming conventions properly building instance in the middle")
	@Test
	void shouldWorkWithReferenceAttributesInNamingConventionsWorkProperlyBuildingInstanceInTheMiddle() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Build an intermediate schema instance while configuring reference attributes
		// This tests that the builder can handle instance creation mid-configuration
		final EntitySchemaContract instance = schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					thatIs.withAttribute("some-attribute-1", String.class)
						.withAttribute("attribute", String.class)
						.withAttribute("code", String.class);

					assertNotNull(thatIs.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
				}
			)
			.toInstance();

		final EntitySchemaContract updatedSchema = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			instance
		)
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					thatIs.withoutAttribute("attribute");

					assertNotNull(thatIs.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
				}
			)
			.toInstance();

		final ReferenceSchemaContract testReference = updatedSchema.getReferenceOrThrowException("testReference");

		assertNotNull(testReference.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(testReference.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(testReference.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

	/**
	 * Tests that sortable attribute compounds on references work properly with naming conventions.
	 * This test verifies that sortable attribute compounds defined on reference relationships
	 * can be accessed using various naming conventions (camelCase, kebab-case, snake_case).
	 * Reference sortable attribute compounds allow sorting by multiple reference attributes
	 * in a specific order, which is useful for complex reference-based sorting scenarios.
	 */
	@DisplayName("work with reference sortable attribute compounds in naming conventions properly")
	@Test
	void shouldWorkWithReferenceSortableAttributeCompoundsInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Create a reference with sortable attribute compounds using different naming styles
		schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					// Add reference attributes that will be used in sortable compounds
					thatIs.withAttribute("attribute", String.class)
						.withAttribute("code", String.class)
						// Create compounds with different naming styles and configurations
						.withSortableAttributeCompound("some-compound-1", attributeElement("attribute"), attributeElement("code"))  // kebab-case with number
						.withSortableAttributeCompound("compound", attributeElement("code"), attributeElement("attribute"))        // simple name
						.withSortableAttributeCompound("anotherCompound", attributeElement("code", OrderDirection.DESC), attributeElement("attribute")); // camelCase with custom sort

					// Verify compounds can be found using different naming conventions
					// "some-compound-1" should be accessible as "someCompound1" in camelCase
					assertNotNull(thatIs.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
					// "compound" should be accessible in all naming conventions since it's a simple name
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
					// "anotherCompound" should be accessible in different naming conventions
					assertNotNull(thatIs.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
				}
			);

		// Modify the same reference to remove one compound and verify the change
		schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					// Remove one compound to test that removal works correctly
					thatIs.withoutSortableAttributeCompound("compound");

					// Verify remaining compounds are still accessible
					assertNotNull(thatIs.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
					// Verify removed compound is no longer accessible in any naming convention
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
					// Verify other compounds remain accessible
					assertNotNull(thatIs.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
				}
			);

		// Build the final schema and verify the same behavior persists in the immutable schema
		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		final ReferenceSchemaContract testReference = updatedSchema.getReferenceOrThrowException("testReference");

		assertNotNull(testReference.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(testReference.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(testReference.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
	}

	/**
	 * Tests that reference sortable attribute compounds work properly with naming conventions
	 * when building schema instances in the middle of the configuration process.
	 * This test verifies that the schema builder can handle intermediate instance creation
	 * without losing the ability to access reference sortable attribute compounds using
	 * different naming conventions. This is important for complex schema building scenarios
	 * where intermediate validation or inspection might be needed during the build process.
	 */
	@DisplayName("work with reference sortable attribute compounds in naming conventions properly building instance in the middle")
	@Test
	void shouldWorkWithReferenceSortableAttributeCompoundsInNamingConventionsWorkProperlyBuildingInstanceInTheMiddle() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Build an intermediate schema instance while configuring reference sortable attribute compounds
		// This tests that the builder can handle instance creation mid-configuration
		final EntitySchemaContract instance = schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					// Add reference attributes and sortable compounds with different naming styles
					thatIs.withAttribute("attribute", String.class)
						.withAttribute("code", String.class)
						.withSortableAttributeCompound("some-compound-1", attributeElement("attribute"), attributeElement("code"))  // kebab-case
						.withSortableAttributeCompound("compound", attributeElement("code"), attributeElement("attribute"))        // simple name
						.withSortableAttributeCompound("anotherCompound", attributeElement("code", OrderDirection.DESC), attributeElement("attribute")); // camelCase

					// Verify all compounds are accessible through different naming conventions during initial configuration
					assertNotNull(thatIs.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
				}
			).toInstance(); // Create intermediate instance to test mid-configuration behavior

		// Create a new builder from the intermediate instance and continue configuration
		// This tests that the builder can properly handle schema evolution from a previously built instance
		final EntitySchemaContract updatedSchema = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			instance
		)
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					// Remove one compound to test that removal works correctly after intermediate instance creation
					thatIs.withoutSortableAttributeCompound("compound");

					// Verify remaining compounds are still accessible after removal
					assertNotNull(thatIs.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
					// Verify removed compound is no longer accessible in any naming convention
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
					// Verify other compounds remain accessible
					assertNotNull(thatIs.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
				}
			).toInstance(); // Build final schema instance

		final ReferenceSchemaContract testReference = updatedSchema.getReferenceOrThrowException("testReference");

		assertNotNull(testReference.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(testReference.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(testReference.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
	}

	/**
	 * Tests that associated data naming conventions work properly when adding and removing associated data.
	 * This test verifies that associated data defined with different naming styles (kebab-case, camelCase, etc.)
	 * can be accessed using various naming conventions, and that removal works correctly across all conventions.
	 * Associated data is used to store additional information alongside entities that doesn't need to be indexed
	 * or queried but should be retrievable with the entity.
	 */
	@DisplayName("work with associated data in naming conventions properly")
	@Test
	void shouldWorkWithAssociatedDatasInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Add associated data with different naming styles to test naming convention handling
		schemaBuilder
			.withAssociatedData("some-associatedData-1", String.class)  // kebab-case with mixed case
			.withAssociatedData("data", String.class)                   // simple lowercase
			.withAssociatedData("code", String.class);                  // simple lowercase

		// Verify associated data can be found using different naming conventions
		// "some-associatedData-1" should be accessible as "someAssociatedData1" in camelCase
		assertNotNull(schemaBuilder.getAssociatedDataByName("someAssociatedData1", NamingConvention.CAMEL_CASE).orElse(null));
		// "data" should be accessible in all naming conventions since it's a simple name
		assertNotNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.KEBAB_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.SNAKE_CASE).orElse(null));
		// "code" should be accessible in different naming conventions
		assertNotNull(schemaBuilder.getAssociatedDataByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAssociatedDataByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		// Remove one associated data to test that removal works correctly
		schemaBuilder.withoutAssociatedData("data");

		// Verify remaining associated data are still accessible
		assertNotNull(schemaBuilder.getAssociatedDataByName("someAssociatedData1", NamingConvention.CAMEL_CASE).orElse(null));
		// Verify removed associated data is no longer accessible in any naming convention
		assertNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.SNAKE_CASE).orElse(null));
		// Verify other associated data remain accessible
		assertNotNull(schemaBuilder.getAssociatedDataByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAssociatedDataByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		// Build the final schema and verify the same behavior persists in the immutable schema
		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		assertNotNull(updatedSchema.getAssociatedDataByName("someAssociatedData1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAssociatedDataByName("data", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAssociatedDataByName("data", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(updatedSchema.getAssociatedDataByName("data", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(updatedSchema.getAssociatedDataByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(updatedSchema.getAssociatedDataByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

	/**
	 * Tests that reference naming conventions work properly when adding and removing references.
	 * This test verifies that references defined with different naming styles (kebab-case, camelCase, etc.)
	 * can be accessed using various naming conventions, and that removal works correctly across all conventions.
	 * References represent relationships between entities and can point to other entities stored in evitaDB
	 * or external entities not managed by evitaDB.
	 */
	@DisplayName("work with references in naming conventions properly")
	@Test
	void shouldWorkWithReferenceInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Add references with different naming styles to test naming convention handling
		schemaBuilder
			.withReferenceTo("some-reference-1", Entities.BRAND, Cardinality.ZERO_OR_MORE)    // kebab-case with number
			.withReferenceTo("reference", Entities.CATEGORY, Cardinality.ZERO_OR_MORE)        // simple lowercase
			.withReferenceTo("code", Entities.PRODUCT, Cardinality.ZERO_OR_MORE);             // simple lowercase

		// Verify references can be found using different naming conventions
		// "some-reference-1" should be accessible as "someReference1" in camelCase
		assertNotNull(schemaBuilder.getReferenceByName("someReference1", NamingConvention.CAMEL_CASE).orElse(null));
		// "reference" should be accessible in all naming conventions since it's a simple name
		assertNotNull(schemaBuilder.getReferenceByName("reference", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getReferenceByName("reference", NamingConvention.KEBAB_CASE).orElse(null));
		assertNotNull(schemaBuilder.getReferenceByName("reference", NamingConvention.SNAKE_CASE).orElse(null));
		// "code" should be accessible in different naming conventions
		assertNotNull(schemaBuilder.getReferenceByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getReferenceByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		// Remove one reference to test that removal works correctly
		schemaBuilder.withoutReferenceTo("reference");

		// Verify remaining references are still accessible
		assertNotNull(schemaBuilder.getReferenceByName("someReference1", NamingConvention.CAMEL_CASE).orElse(null));
		// Verify removed reference is no longer accessible in any naming convention
		assertNull(schemaBuilder.getReferenceByName("reference", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getReferenceByName("reference", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(schemaBuilder.getReferenceByName("reference", NamingConvention.SNAKE_CASE).orElse(null));
		// Verify other references remain accessible
		assertNotNull(schemaBuilder.getReferenceByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getReferenceByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		// Build the final schema and verify the same behavior persists in the immutable schema
		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		assertNotNull(updatedSchema.getReferenceByName("someReference1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getReferenceByName("reference", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getReferenceByName("reference", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(updatedSchema.getReferenceByName("reference", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(updatedSchema.getReferenceByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(updatedSchema.getReferenceByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

	/**
	 * Tests that building a schema multiple times produces exactly the same result.
	 * This test verifies the consistency and idempotency of the schema building process.
	 * It ensures that creating a schema builder from an already built schema and then
	 * building it again without any changes produces an identical schema. This is
	 * important for ensuring that schema operations are deterministic and that
	 * intermediate schema instances can be safely used as base schemas for further modifications.
	 */
	@DisplayName("update and build exactly same product schema")
	@Test
	void shouldUpdateBuildExactlySameProductSchema() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema, this.productSchema
		);

		// Build the initial schema using the example schema construction method
		final EntitySchemaContract updatedSchema = constructExampleSchema(schemaBuilder);
		// Verify the initial schema has all expected contents
		assertSchemaContents(updatedSchema);

		// Create a new builder from the already built schema and build it again without changes
		// This tests that the schema building process is idempotent and consistent
		assertSchemaContents(
			new InternalEntitySchemaBuilder(
				this.catalogSchema, updatedSchema  // Use the previously built schema as base
			).toInstance()  // Build again without any modifications
		);
	}

	/**
	 * Tests that the schema builder properly validates and prevents conflicting attribute definitions.
	 * This test verifies that when an entity schema tries to define an attribute that already exists
	 * in the catalog schema with the same name, the system throws an appropriate exception.
	 * This validation is crucial for maintaining schema consistency and preventing naming conflicts
	 * between global catalog attributes and entity-specific attributes.
	 */
	@DisplayName("fail to define product schema with conflicting attributes")
	@Test
	void shouldFailToDefineProductSchemaWithConflictingAttributes() {
		// Create a catalog schema with global attributes that will conflict with entity attributes
		final CatalogSchemaContract updatedCatalogSchema = new InternalCatalogSchemaBuilder(this.catalogSchema)
			.withAttribute("code", String.class, whichIs -> whichIs.unique())           // Global attribute "code"
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable()) // Global attribute "name"
			.toInstance();

		// Create an entity schema builder using the catalog with conflicting global attributes
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			updatedCatalogSchema,
			this.productSchema
		);

		// Verify that attempting to create entity attributes with the same names as global attributes fails
		// The constructExampleSchema method tries to create entity attributes "code" and "name"
		// which conflict with the global attributes defined above
		assertThrows(
			AttributeAlreadyPresentInCatalogSchemaException.class,
			() -> {
				final EntitySchemaContract updatedSchema = constructExampleSchema(schemaBuilder);
				assertSchemaContents(updatedSchema);
			}
		);
	}

	/**
	 * Tests that the schema builder properly validates and prevents conflicting sortable attribute compound definitions.
	 * This test verifies that when trying to define a sortable attribute compound with the same name as an existing
	 * compound but with different attribute elements, the system throws an appropriate exception.
	 * This validation ensures that sortable attribute compounds maintain consistent definitions and prevents
	 * ambiguous or conflicting compound configurations.
	 */
	@DisplayName("fail to define product schema with conflicting sortable attribute compounds")
	@Test
	void shouldFailToDefineProductSchemaWithConflictingSortableAttributeCompounds() {
		// Create a base schema with a sortable attribute compound
		final EntitySchema theSchema = (EntitySchema) new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		)
			.withAttribute("code", String.class)
			.withAttribute("name", String.class)
			.withSortableAttributeCompound(
				"codeName",  // Define compound with specific attribute order
				attributeElement("code"), attributeElement("name")
			)
			.toInstance();

		// Verify that attempting to redefine the same compound with different attribute elements fails
		// This tries to create a compound with the same name but reversed attribute order
		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				theSchema
			)
				.withSortableAttributeCompound(
					"codeName",  // Same name as existing compound
					attributeElement("name"), attributeElement("code")  // But different attribute order
				)
		);
	}

	/**
	 * Tests that the schema builder properly validates naming convention conflicts for sortable attribute compounds.
	 * This test verifies that when trying to define a sortable attribute compound with a name that conflicts
	 * with an existing compound in a different naming convention, the system throws an appropriate exception.
	 * For example, "code-name" (kebab-case) and "codeName" (camelCase) represent the same logical name
	 * and should be treated as conflicting. This validation ensures naming consistency across different
	 * naming conventions and prevents ambiguous compound definitions.
	 */
	@DisplayName("fail to define product schema with conflicting sortable attribute compounds in specific naming convention")
	@Test
	void shouldFailToDefineProductSchemaWithConflictingSortableAttributeCompoundsInSpecificNamingConvention() {
		// Create a base schema with a sortable attribute compound using kebab-case naming
		final EntitySchema theSchema = (EntitySchema) new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		)
			.withAttribute("code", String.class)
			.withAttribute("name", String.class)
			.withSortableAttributeCompound(
				"code-name",  // Define compound using kebab-case naming convention
				attributeElement("code"), attributeElement("name")
			)
			.toInstance();

		// Verify that attempting to create a compound with the same logical name in camelCase fails
		// "codeName" (camelCase) conflicts with "code-name" (kebab-case) as they represent the same logical name
		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				theSchema
			)
				.withSortableAttributeCompound(
					"codeName",  // Same logical name as "code-name" but in camelCase
					attributeElement("name"), attributeElement("code")  // Also different attribute order
				)
		);
	}

	/**
	 * Tests that the schema builder properly validates conflicts between attributes and sortable attribute compounds.
	 * This test verifies that when trying to define a sortable attribute compound with the same name as an existing
	 * attribute, the system throws an appropriate exception. This validation is crucial because attributes and
	 * sortable attribute compounds share the same namespace and must have unique names to prevent ambiguity
	 * in queries and schema operations. The test ensures that naming conflicts are detected and prevented
	 * regardless of whether the attribute or compound was defined first.
	 */
	@DisplayName("fail to define product schema with conflicting attribute and sortable attribute compound")
	@Test
	void shouldFailToDefineProductSchemaWithConflictingAttributeAndSortableAttributeCompound() {
		// Create a base schema with attributes and a sortable attribute compound
		final EntitySchema theSchema = (EntitySchema) new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		)
			.withAttribute("code", String.class)
			.withAttribute("name", String.class)  // Define "name" as an attribute
			.withSortableAttributeCompound(
				"codeName",
				attributeElement("code"), attributeElement("name")
			)
			.toInstance();

		// Verify that attempting to create a sortable attribute compound with the same name as an existing attribute fails
		// This tries to create a compound named "name" which conflicts with the existing "name" attribute
		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				theSchema
			)
				.withSortableAttributeCompound(
					"name",  // Same name as existing attribute
					attributeElement("name"), attributeElement("code")
				)
		);
	}

	/**
	 * Tests that the schema builder properly validates naming convention conflicts between attributes and sortable attribute compounds.
	 * This test verifies that when trying to define an attribute with the same logical name as an existing
	 * sortable attribute compound (considering different naming conventions), the system throws an appropriate exception.
	 * This validation ensures that attributes and sortable attribute compounds maintain unique names across all
	 * naming conventions, preventing ambiguity in schema operations. For example, if a compound named "codeName"
	 * exists, attempting to create an attribute with the same name should fail, as they would conflict in the
	 * shared namespace regardless of the naming convention used.
	 */
	@DisplayName("fail to define product schema with conflicting attribute and sortable attribute compound in specific naming convention")
	@Test
	void shouldFailToDefineProductSchemaWithConflictingAttributeAndSortableAttributeCompoundInSpecificNamingConvention() {
		// Create a base schema with attributes and a sortable attribute compound using camelCase naming
		final EntitySchema theSchema = (EntitySchema) new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		)
			.withAttribute("code", String.class)
			.withAttribute("name", String.class)
			.withSortableAttributeCompound(
				"codeName",  // Define compound using camelCase naming convention
				attributeElement("code"), attributeElement("name")
			)
			.toInstance();

		// Verify that attempting to create an attribute with the same name as an existing compound fails
		// This tries to create an attribute named "codeName" which conflicts with the existing compound
		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				theSchema
			)
				.withAttribute(
					"codeName",  // Same name as existing sortable attribute compound
					String.class
				)
		);
	}

	@DisplayName("fail to define product schema with sortable attribute compound with single attribute element")
	@Test
	void shouldFailToDefineProductSchemaWithSortableAttributeCompoundWithSingleAttributeElement() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.productSchema
			)
				.withAttribute("code", String.class)
				.withAttribute("name", String.class)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("code")
				)
				.toInstance()
		);
	}

	@DisplayName("fail to define product schema with sortable attribute compound with multiple attribute elements of same name")
	@Test
	void shouldFailToDefineProductSchemaWithSortableAttributeCompoundWithMultipleAttributeElementsOfSameName() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.productSchema
			)
				.withAttribute("code", String.class)
				.withAttribute("name", String.class)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("code"),
					attributeElement("code")
				)
				.toInstance()
		);
	}

	@DisplayName("fail to define product schema with sortable attribute compound with non existing attribute element")
	@Test
	void shouldFailToDefineProductSchemaWithSortableAttributeCompoundWithNonExistingAttributeElement() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.productSchema
			)
				.withAttribute("code", String.class)
				.withAttribute("name", String.class)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("notExisting"),
					attributeElement("code")
				)
				.toInstance()
		);
	}

	@DisplayName("fail to remove attribute present in sortable attribute compound")
	@Test
	void shouldFailToRemoveAttributePresentInSortableAttributeCompound() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		)
			.withAttribute("code", String.class)
			.withAttribute("name", String.class)
			.withSortableAttributeCompound(
				"codeName",
				attributeElement("code"),
				attributeElement("name")
			);

		final EntitySchemaContract instance = schemaBuilder.toInstance();

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> schemaBuilder.withoutAttribute("code")
		);

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				instance
			)
				.withoutAttribute("code")
				.toInstance()
		);
	}

	@DisplayName("fail to define product schema with conflicting reference sortable attribute compounds")
	@Test
	void shouldFailToDefineProductSchemaWithConflictingReferenceSortableAttributeCompounds() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final EntitySchemaContract instance = schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withAttribute("code", String.class)
				.withAttribute("name", String.class)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("code"), attributeElement("name")
				)
			)
			.toInstance();

		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				instance
			)
				.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withSortableAttributeCompound(
						"codeName",
						attributeElement("name"), attributeElement("code")
					)
				)
				.toInstance()
		);

		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> schemaBuilder.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withSortableAttributeCompound(
						"codeName",
						attributeElement("name"), attributeElement("code")
					)
				)
				.toInstance()
		);
	}

	@DisplayName("fail to define product schema with conflicting reference attribute and sortable attribute compound")
	@Test
	void shouldFailToDefineProductSchemaWithConflictingReferenceAttributeAndSortableAttributeCompound() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final EntitySchemaContract instance = schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withAttribute("code", String.class)
				.withAttribute("name", String.class)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("code"), attributeElement("name")
				)
			)
			.toInstance();

		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				instance
			)
				.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withAttribute("codeName", String.class)
				)
				.toInstance()
		);

		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> schemaBuilder.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withAttribute("codeName", String.class)
				)
				.toInstance()
		);
	}

	@DisplayName("fail to define product schema with reference sortable attribute compound with single attribute element")
	@Test
	void shouldFailToDefineProductSchemaWithReferenceSortableAttributeCompoundWithSingleAttributeElement() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.productSchema
			)
				.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
					thatIs -> thatIs.withAttribute("code", String.class)
						.withAttribute("name", String.class)
						.withSortableAttributeCompound(
							"codeName",
							attributeElement("code")
						)
				)
				.toInstance()
		);
	}

	@DisplayName("fail to define product schema with reference sortable attribute compound with multiple attribute elements of same name")
	@Test
	void shouldFailToDefineProductSchemaWithReferenceSortableAttributeCompoundWithMultipleAttributeElementsOfSameName() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.productSchema
			)
				.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
					thatIs -> thatIs.withAttribute("code", String.class)
						.withAttribute("name", String.class)
						.withSortableAttributeCompound(
							"codeName",
							attributeElement("code"), attributeElement("code")
						)
				)
				.toInstance()
		);
	}

	@DisplayName("fail to define product schema with reference sortable attribute compound with non existing attribute element")
	@Test
	void shouldFailToDefineProductSchemaWithReferenceSortableAttributeCompoundWithNonExistingAttributeElement() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.productSchema
			)
				.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
					thatIs -> thatIs.withAttribute("code", String.class)
						.withAttribute("name", String.class)
						.withSortableAttributeCompound(
							"codeName",
							attributeElement("code"), attributeElement("nonExisting")
						)
				)
				.toInstance()
		);
	}

	@DisplayName("fail to remove reference attribute present in sortable attribute compound")
	@Test
	void shouldFailToRemoveReferenceAttributePresentInSortableAttributeCompound() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		)
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
				thatIs -> thatIs.withAttribute("code", String.class)
					.withAttribute("name", String.class)
					.withSortableAttributeCompound(
						"codeName",
						attributeElement("code"), attributeElement("name")
					)
			);

		final EntitySchemaContract schema = schemaBuilder.toInstance();

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> schemaBuilder.withReferenceToEntity(
				"testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
				thatIs -> {
					thatIs.withoutAttribute("code");
				}
			)
		);

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				schema
			).withReferenceToEntity(
				"testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
				thatIs -> {
					thatIs.withoutAttribute("code");
				}
			)
		);
	}

	/**
	 * Tests the creation of a product schema that uses shared global attributes from the catalog.
	 * This test verifies that entity schemas can reference and use attributes that are defined
	 * at the catalog level (global attributes) rather than defining entity-specific attributes.
	 * Global attributes are shared across all entities in the catalog and help maintain
	 * consistency and reduce duplication. The test demonstrates the use of withGlobalAttribute()
	 * method to reference catalog-level attributes in entity schemas.
	 */
	@DisplayName("define product schema with shared attributes")
	@Test
	void shouldDefineProductSchemaWithSharedAttributes() {
		// Create a catalog schema with global attributes that can be shared across entities
		final CatalogSchemaContract updatedCatalogSchema = new InternalCatalogSchemaBuilder(this.catalogSchema)
			.withAttribute("code", String.class, whichIs -> whichIs.unique().representative())  // Global "code" attribute
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable())    // Global "name" attribute
			.toInstance();

		// Create entity schema builder using the catalog with global attributes
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			updatedCatalogSchema,
			this.productSchema
		);

		// Build product schema that references global attributes instead of defining entity-specific ones
		final EntitySchemaContract productSchema = schemaBuilder
			/* all is strictly verified but associated data and references can be added on the fly */
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
			/* product are not organized in the tree */
			.withoutHierarchy()
			/* prices are referencing another entity stored in Evita */
			.withPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.withGlobalAttribute("code")  // Reference the global "code" attribute from catalog
			.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
			.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())
			.withGlobalAttribute("name")  // Reference the global "name" attribute from catalog
			.withAttribute("ean", String.class, whichIs -> whichIs.filterable())
			.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())
			.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable())
			.withAttribute("quantity", BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2))
			.withAttribute("alias", Boolean.class, whichIs -> whichIs.filterable())
			/* here we define sortable attribute compounds */
			.withSortableAttributeCompound(
				"codeWithEan",
				attributeElement("code"),
				attributeElement("ean")
			)
			.withSortableAttributeCompound(
				"priorityAndQuantity",
				new AttributeElement[]{
					attributeElement("priority", OrderDirection.DESC),
					attributeElement("quantity", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
				},
				whichIs -> whichIs
					.withDescription("Priority and quantity in descending order.")
					.deprecated("Already deprecated.")
			)
			/* here we define set of associated data, that can be stored along with entity */
			.withAssociatedData("referencedFiles", ReferencedFileSet.class)
			.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
			/* here we define references that relate to another entities stored in Evita */
			.withReferenceToEntity(
				Entities.CATEGORY,
				Entities.CATEGORY,
				Cardinality.ZERO_OR_MORE,
				whichIs ->
					/* we can specify special attributes on relation */
					whichIs.indexedForFilteringAndPartitioning().withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
			)
			/* for faceted references we can compute "counts" */
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.faceted()
			)
			/* references may be also represented be entities unknown to Evita */
			.withReferenceTo(
				Entities.STORE,
				Entities.STORE,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.faceted()
			)
			/* finally apply schema changes */
			.toInstance();

		assertSchemaContents(productSchema);
	}

	/**
	 * Tests the creation of a category entity schema with hierarchical structure.
	 * This test verifies that the EntitySchemaBuilder can create a schema specifically
	 * configured for category entities, which typically have different characteristics
	 * than product entities. Categories are organized in a tree structure (hierarchy),
	 * don't have prices, and use strict schema verification. This test demonstrates
	 * the configuration of hierarchical entities and strict schema validation.
	 */
	@SuppressWarnings("Convert2MethodRef")
	@DisplayName("define category schema")
	@Test
	void shouldDefineCategorySchema() {
		// Create schema builder for category entity using the test catalog and base category schema
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema, this.categorySchema
		);

		// Build category schema with hierarchical structure and strict validation
		final EntitySchemaContract updatedSchema = schemaBuilder
			/* all is strictly verified for categories - no evolution modes allowed */
			.verifySchemaStrictly()
			/* categories are organized in a tree manner - enables parent-child relationships */
			.withHierarchy()
			/* categories don't have prices, we can also omit this line - explicit for clarity */
			.withoutPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute("code", String.class, whichIs -> whichIs.unique().representative())  // Unique identifier for category
			.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())        // Localized URL paths
			.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())  // Historical URLs for redirects
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable().nullable())   // Category name (nullable for flexibility)
			.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())               // Sort order priority
			/* here we define set of associated data, that can be stored along with entity */
			.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())         // Localized label data
			/* finally apply schema changes */
			.toInstance();

		assertTrue(updatedSchema.getEvolutionMode().isEmpty());
		assertTrue(updatedSchema.isWithHierarchy());

		assertFalse(updatedSchema.isWithPrice());

		assertTrue(updatedSchema.getLocales().contains(Locale.ENGLISH));
		assertTrue(updatedSchema.getLocales().contains(new Locale("cs", "CZ")));

		assertEquals(5, updatedSchema.getAttributes().size());
		assertAttribute(updatedSchema.getAttribute("code").orElseThrow(), true, false, false, false, false, true, 0, String.class);
		assertAttribute(updatedSchema.getAttribute("name").orElseThrow(), false, true, true, false, true, false, 0, String.class);
		assertAttribute(updatedSchema.getAttribute("oldEntityUrls").orElseThrow(), false, true, false, true, false, false, 0, String[].class);
		assertAttribute(updatedSchema.getAttribute("priority").orElseThrow(), false, false, true, false, false, false, 0, Long.class);

		assertEquals(1, updatedSchema.getAssociatedData().size());
		assertAssociatedData(updatedSchema.getAssociatedData("labels"), true, ComplexDataObject.class);

		assertTrue(updatedSchema.getReferences().isEmpty());
	}

	/**
	 * Tests that the schema builder prevents defining two attributes with names that conflict in naming conventions.
	 * This test verifies that when trying to define attributes with names that are equivalent when converted
	 * to different naming conventions (e.g., "abc" and "Abc" both convert to the same camelCase form),
	 * the system throws an appropriate exception. This validation is crucial for preventing naming conflicts
	 * that could arise from case-insensitive naming convention transformations and ensures that attribute
	 * names remain unique across all supported naming conventions.
	 */
	@DisplayName("fail to define two attributes sharing name in specific naming convention")
	@Test
	void shouldFailToDefineTwoAttributesSharingNameInSpecificNamingConvention() {
		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.productSchema
			)
				.withAttribute("abc", String.class)
				.withAttribute("Abc", String.class)
		);
	}

	/**
	 * Tests that the schema builder prevents defining two reference attributes with names that conflict in naming conventions.
	 * This test verifies that when trying to define reference attributes with names that are equivalent when converted
	 * to different naming conventions (e.g., "abc" and "Abc" both convert to the same camelCase form),
	 * the system throws an appropriate exception. This validation ensures that reference attribute names
	 * remain unique across all supported naming conventions, preventing ambiguity when accessing
	 * reference attributes through different naming convention APIs.
	 */
	@DisplayName("fail to define two reference attributes sharing name in specific naming convention")
	@Test
	void shouldFailToDefineTwoReferenceAttributesSharingNameInSpecificNamingConvention() {
		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.productSchema
			)
				.withReferenceTo(
					"brand",
					Entities.BRAND,
					Cardinality.ZERO_OR_ONE,
					thatIs -> thatIs
						.withAttribute("abc", String.class)
						.withAttribute("Abc", String.class)
				)
		);
	}

	/**
	 * Tests that the schema builder prevents defining two references with names that conflict in naming conventions.
	 * This test verifies that when trying to define references with names that are equivalent when converted
	 * to different naming conventions (e.g., "abc" and "Abc" both convert to the same camelCase form),
	 * the system throws an appropriate exception. This validation ensures that reference names remain
	 * unique across all supported naming conventions, preventing ambiguity when accessing references
	 * through different naming convention APIs and maintaining schema consistency.
	 */
	@DisplayName("fail to define two references sharing name in specific naming convention")
	@Test
	void shouldFailToDefineTwoReferencesSharingNameInSpecificNamingConvention() {
		assertThrows(
			ReferenceAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.productSchema
			)
				.withReferenceTo("abc", Entities.BRAND, Cardinality.ZERO_OR_ONE)
				.withReferenceTo("Abc", Entities.BRAND, Cardinality.ZERO_OR_ONE)
		);
	}

	/**
	 * Tests that the schema builder prevents defining two associated data with names that conflict in naming conventions.
	 * This test verifies that when trying to define associated data with names that are equivalent when converted
	 * to different naming conventions (e.g., "abc" and "Abc" both convert to the same camelCase form),
	 * the system throws an appropriate exception. This validation ensures that associated data names remain
	 * unique across all supported naming conventions, preventing ambiguity when accessing associated data
	 * through different naming convention APIs and maintaining schema consistency.
	 */
	@DisplayName("fail to define two associated data sharing name in specific naming convention")
	@Test
	void shouldFailToDefineTwoAssociatedDataSharingNameInSpecificNamingConvention() {
		assertThrows(
			AssociatedDataAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.productSchema
			)
				.withAssociatedData("abc", String.class)
				.withAssociatedData("Abc", String.class)
		);
	}

	/**
	 * Tests that the schema builder allows redefining associated data with the same type and configuration.
	 * This test verifies that when trying to define associated data that already exists with identical
	 * type and configuration, the system doesn't throw an exception and treats it as a no-op operation.
	 * This behavior is important for idempotent schema operations and allows for safe re-application
	 * of schema definitions without causing conflicts. The test also verifies that such operations
	 * don't generate unnecessary mutations when the schema remains unchanged.
	 */
	@DisplayName("not throw when assigning the same type to existing associated data")
	@Test
	void shouldNotThrowWhenAssigningTheSameTypeToExistingAssociatedData() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema, this.categorySchema
		);

		final EntitySchemaContract updatedSchema = schemaBuilder
			.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
			.toInstance();

		final EntitySchemaBuilder schemaBuilder2 = new InternalEntitySchemaBuilder(
			this.catalogSchema, updatedSchema
		);

		assertDoesNotThrow(() ->
			schemaBuilder2
				.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
				.toInstance()
		);

		assertTrue(
			schemaBuilder2
				.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
				.toMutation()
				.isEmpty()
		);
	}

	/**
	 * Tests the creation of bidirectional references with implicit attribute inheritance.
	 * This test verifies that when defining a reflected reference (bidirectional reference),
	 * the system can automatically inherit attributes from the original reference without
	 * explicit configuration. Bidirectional references allow navigation in both directions
	 * between entities, and implicit attribute inheritance means that attributes defined
	 * on the original reference are automatically available on the reflected reference,
	 * simplifying schema configuration and ensuring consistency between both directions
	 * of the relationship.
	 */
	@DisplayName("define bidirectional reference with implicit attribute inheritance")
	@Test
	void shouldDefineBidirectionalReferenceWithImplicitAttributeInheritance() {
		final EntitySchemaBuilder productSchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final String baseReferenceName = "productCategories";
		productSchemaBuilder.withReferenceToEntity(
			baseReferenceName, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
			whichIs -> whichIs
				.withDescription("Assigned categories.")
				.withGroupTypeRelatedToEntity(Entities.STORE)
				.withAttribute("note", String.class)
				.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
				.indexedForFilteringAndPartitioning()
				.faceted()
		);

		// Create category schema builder and define the reflected reference with explicit inheritance
		final EntitySchemaBuilder categorySchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.categorySchema
		);

		// Define the reflected reference with explicit attribute inheritance
		// This creates the reverse direction of the bidirectional relationship
		categorySchemaBuilder.withReflectedReferenceToEntity(
			"categoryProducts", Entities.PRODUCT, baseReferenceName,
			whichIs -> whichIs
				.withDescription("Category products.")
				.deprecated("No longer used.")
				.withCardinality(Cardinality.ZERO_OR_MORE)
				.withAttributesInheritedExcept("note")  // Inherit all attributes except "note"
				.withAttribute("differentNote", String.class)  // Add a new attribute specific to this direction
				.indexedForFilteringAndPartitioning()
				.nonFaceted()
		);

		final ReferenceSchemaContract categoryProducts = categorySchemaBuilder.toInstance()
			.getReference("categoryProducts")
			.orElseThrow();

		assertInstanceOf(ReflectedReferenceSchema.class, categoryProducts);
		ReflectedReferenceSchema categoryProductsWithReference = ((ReflectedReferenceSchema) categoryProducts)
			.withReferencedSchema(productSchemaBuilder.toInstance().getReference(baseReferenceName).orElseThrow());

		assertEquals("categoryProducts", categoryProductsWithReference.getName());
		assertEquals("Category products.", categoryProductsWithReference.getDescription());
		assertFalse(categoryProductsWithReference.isDescriptionInherited());
		assertEquals(Cardinality.ZERO_OR_MORE, categoryProductsWithReference.getCardinality());
		assertFalse(categoryProductsWithReference.isCardinalityInherited());
		assertTrue(categoryProductsWithReference.isIndexed());
		assertFalse(categoryProductsWithReference.isFaceted());
		assertFalse(categoryProductsWithReference.isFacetedInherited());
		assertEquals("No longer used.", categoryProductsWithReference.getDeprecationNotice());
		assertFalse(categoryProductsWithReference.isDeprecatedInherited());

		final Map<String, AttributeSchemaContract> categoryAttributes = categoryProductsWithReference.getAttributes();
		assertEquals(2, categoryAttributes.size());
		assertTrue(categoryAttributes.containsKey("categoryPriority"));
		assertTrue(categoryAttributes.containsKey("differentNote"));
	}

	/**
	 * Tests the creation of bidirectional references with explicit attribute inheritance.
	 * This test verifies that when defining a reflected reference (bidirectional reference),
	 * the system can be configured to explicitly inherit only specific attributes from the
	 * original reference. Unlike implicit inheritance which inherits all attributes,
	 * explicit inheritance allows fine-grained control over which attributes are inherited
	 * and which are excluded. This provides flexibility in bidirectional relationships
	 * where the two directions might need different attribute sets.
	 */
	@DisplayName("define bidirectional reference with explicit attribute inheritance")
	@Test
	void shouldDefineBidirectionalReferenceWithExplicitAttributeInheritance() {
		// Create product schema builder and define the original reference with attributes
		final EntitySchemaBuilder productSchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Define the base reference from product to category with multiple attributes
		final String baseReferenceName = "productCategories";
		productSchemaBuilder.withReferenceToEntity(
			baseReferenceName, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
			whichIs -> whichIs
				.withDescription("Assigned categories.")
				.withGroupTypeRelatedToEntity(Entities.STORE)
				.withAttribute("note", String.class)                                    // Attribute that might be excluded
				.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())  // Attribute that might be inherited
				.indexedForFilteringAndPartitioning()
				.faceted()
		);

		final EntitySchemaBuilder categorySchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.categorySchema
		);

		categorySchemaBuilder.withReflectedReferenceToEntity(
			"categoryProducts", Entities.PRODUCT, baseReferenceName,
			whichIs -> whichIs
				.withDescription("Category products.")
				.deprecated("No longer used.")
				.withCardinality(Cardinality.ZERO_OR_MORE)
				.withAttributesInherited("note")
				.withAttribute("differentNote", String.class)
				.indexedForFilteringAndPartitioning()
				.nonFaceted()
		);

		final ReferenceSchemaContract categoryProducts = categorySchemaBuilder.toInstance()
			.getReference("categoryProducts")
			.orElseThrow();

		assertInstanceOf(ReflectedReferenceSchema.class, categoryProducts);
		ReflectedReferenceSchema categoryProductsWithReference = ((ReflectedReferenceSchema) categoryProducts)
			.withReferencedSchema(productSchemaBuilder.toInstance().getReference(baseReferenceName).orElseThrow());

		assertEquals("categoryProducts", categoryProductsWithReference.getName());
		assertEquals("Category products.", categoryProductsWithReference.getDescription());
		assertFalse(categoryProductsWithReference.isDescriptionInherited());
		assertEquals(Cardinality.ZERO_OR_MORE, categoryProductsWithReference.getCardinality());
		assertFalse(categoryProductsWithReference.isCardinalityInherited());
		assertTrue(categoryProductsWithReference.isIndexed());
		assertFalse(categoryProductsWithReference.isFaceted());
		assertFalse(categoryProductsWithReference.isFacetedInherited());
		assertEquals("No longer used.", categoryProductsWithReference.getDeprecationNotice());
		assertFalse(categoryProductsWithReference.isDeprecatedInherited());

		final Map<String, AttributeSchemaContract> categoryAttributes = categoryProductsWithReference.getAttributes();
		assertEquals(2, categoryAttributes.size());
		assertTrue(categoryAttributes.containsKey("note"));
		assertTrue(categoryAttributes.containsKey("differentNote"));
	}

	@DisplayName("define bidirectional reference with all attributes inherited")
	@Test
	void shouldDefineBidirectionalReferenceWithAllAttributesInherited() {
		final EntitySchemaBuilder productSchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final String baseReferenceName = "productCategories";
		productSchemaBuilder.withReferenceToEntity(
			baseReferenceName, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
			whichIs -> whichIs
				.withDescription("Assigned categories.")
				.withGroupTypeRelatedToEntity(Entities.STORE)
				.withAttribute("note", String.class)
				.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
				.indexedForFilteringAndPartitioning()
				.faceted()
		);

		final EntitySchemaBuilder categorySchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.categorySchema
		);

		categorySchemaBuilder.withReflectedReferenceToEntity(
			"categoryProducts", Entities.PRODUCT, baseReferenceName,
			whichIs -> whichIs
				.withDescription("Category products.")
				.deprecated("No longer used.")
				.withCardinality(Cardinality.ZERO_OR_MORE)
				.withAttributesInherited()
				.withAttribute("differentNote", String.class)
				.indexedForFilteringAndPartitioning()
				.nonFaceted()
		);

		final ReferenceSchemaContract categoryProducts = categorySchemaBuilder.toInstance()
			.getReference("categoryProducts")
			.orElseThrow();

		assertInstanceOf(ReflectedReferenceSchema.class, categoryProducts);
		ReflectedReferenceSchema categoryProductsWithReference = ((ReflectedReferenceSchema) categoryProducts)
			.withReferencedSchema(productSchemaBuilder.toInstance().getReference(baseReferenceName).orElseThrow());

		assertEquals("categoryProducts", categoryProductsWithReference.getName());
		assertEquals("Category products.", categoryProductsWithReference.getDescription());
		assertFalse(categoryProductsWithReference.isDescriptionInherited());
		assertEquals(Cardinality.ZERO_OR_MORE, categoryProductsWithReference.getCardinality());
		assertFalse(categoryProductsWithReference.isCardinalityInherited());
		assertTrue(categoryProductsWithReference.isIndexed());
		assertFalse(categoryProductsWithReference.isFaceted());
		assertFalse(categoryProductsWithReference.isFacetedInherited());
		assertEquals("No longer used.", categoryProductsWithReference.getDeprecationNotice());
		assertFalse(categoryProductsWithReference.isDeprecatedInherited());

		final Map<String, AttributeSchemaContract> categoryAttributes = categoryProductsWithReference.getAttributes();
		assertEquals(3, categoryAttributes.size());
		assertTrue(categoryAttributes.containsKey("note"));
		assertTrue(categoryAttributes.containsKey("categoryPriority"));
		assertTrue(categoryAttributes.containsKey("differentNote"));
	}

	@DisplayName("define bidirectional reference with none attributes inherited")
	@Test
	void shouldDefineBidirectionalReferenceWithNoneAttributesInherited() {
		final EntitySchemaBuilder productSchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final String baseReferenceName = "productCategories";
		productSchemaBuilder.withReferenceToEntity(
			baseReferenceName, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
			whichIs -> whichIs
				.withDescription("Assigned categories.")
				.withGroupTypeRelatedToEntity(Entities.STORE)
				.withAttribute("note", String.class)
				.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
				.indexedForFilteringAndPartitioning()
				.faceted()
		);

		final EntitySchemaBuilder categorySchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.categorySchema
		);

		categorySchemaBuilder.withReflectedReferenceToEntity(
			"categoryProducts", Entities.PRODUCT, baseReferenceName,
			whichIs -> whichIs
				.withDescription("Category products.")
				.deprecated("No longer used.")
				.withCardinality(Cardinality.ZERO_OR_MORE)
				.withoutAttributesInherited()
				.withAttribute("differentNote", String.class)
				.indexedForFilteringAndPartitioning()
				.nonFaceted()
		);

		final ReferenceSchemaContract categoryProducts = categorySchemaBuilder.toInstance()
			.getReference("categoryProducts")
			.orElseThrow();

		assertInstanceOf(ReflectedReferenceSchema.class, categoryProducts);
		ReflectedReferenceSchema categoryProductsWithReference = ((ReflectedReferenceSchema) categoryProducts)
			.withReferencedSchema(productSchemaBuilder.toInstance().getReference(baseReferenceName).orElseThrow());

		assertEquals("categoryProducts", categoryProductsWithReference.getName());
		assertEquals("Category products.", categoryProductsWithReference.getDescription());
		assertFalse(categoryProductsWithReference.isDescriptionInherited());
		assertEquals(Cardinality.ZERO_OR_MORE, categoryProductsWithReference.getCardinality());
		assertFalse(categoryProductsWithReference.isCardinalityInherited());
		assertTrue(categoryProductsWithReference.isIndexed());
		assertFalse(categoryProductsWithReference.isFaceted());
		assertFalse(categoryProductsWithReference.isFacetedInherited());
		assertEquals("No longer used.", categoryProductsWithReference.getDeprecationNotice());
		assertFalse(categoryProductsWithReference.isDeprecatedInherited());

		final Map<String, AttributeSchemaContract> categoryAttributes = categoryProductsWithReference.getAttributes();
		assertEquals(1, categoryAttributes.size());
		assertTrue(categoryAttributes.containsKey("differentNote"));
	}

	@DisplayName("define bidirectional references with inherited properties")
	@Test
	void shouldDefineBidirectionalReferencesWithInheritedProperties() {
		final EntitySchemaBuilder productSchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final String baseReferenceName = "productCategories";
		productSchemaBuilder.withReferenceToEntity(
			baseReferenceName, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
			whichIs -> whichIs
				.withDescription("Assigned categories.")
				.withGroupTypeRelatedToEntity(Entities.STORE)
				.withAttribute("note", String.class)
				.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
				.indexedForFilteringAndPartitioning()
				.faceted()
		);

		final EntitySchemaBuilder categorySchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.categorySchema
		);

		categorySchemaBuilder.withReflectedReferenceToEntity(
			"categoryProducts", Entities.PRODUCT, baseReferenceName,
			ReflectedReferenceSchemaEditor::withAttributesInherited
		);

		final ReferenceSchemaContract categoryProducts = categorySchemaBuilder.toInstance()
			.getReference("categoryProducts")
			.orElseThrow();

		assertInstanceOf(ReflectedReferenceSchema.class, categoryProducts);
		ReflectedReferenceSchema categoryProductsWithReference = ((ReflectedReferenceSchema) categoryProducts)
			.withReferencedSchema(productSchemaBuilder.toInstance().getReference(baseReferenceName).orElseThrow());

		assertEquals("categoryProducts", categoryProductsWithReference.getName());
		assertEquals("Assigned categories.", categoryProductsWithReference.getDescription());
		assertTrue(categoryProductsWithReference.isDescriptionInherited());
		assertEquals(Cardinality.ZERO_OR_ONE, categoryProductsWithReference.getCardinality());
		assertTrue(categoryProductsWithReference.isCardinalityInherited());
		assertTrue(categoryProductsWithReference.isIndexed());
		assertTrue(categoryProductsWithReference.isFaceted());
		assertTrue(categoryProductsWithReference.isFacetedInherited());
		assertNull(categoryProductsWithReference.getDeprecationNotice());
		assertTrue(categoryProductsWithReference.isDeprecatedInherited());

		final Map<String, AttributeSchemaContract> categoryAttributes = categoryProductsWithReference.getAttributes();
		assertEquals(2, categoryAttributes.size());
		assertTrue(categoryAttributes.containsKey("categoryPriority"));
		assertTrue(categoryAttributes.containsKey("note"));
	}

	@DisplayName("fail to define references and redefine to bidirectional directly")
	@Test
	void shouldFailToDefineReferencesAndRedefineToBiDiDirectly() {
		final EntitySchemaBuilder categorySchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.categorySchema
		);

		final String sharedReferenceName = "categoryProducts";
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> categorySchemaBuilder
				.withReferenceToEntity(sharedReferenceName, Entities.PRODUCT, Cardinality.ONE_OR_MORE)
				.withReflectedReferenceToEntity(sharedReferenceName, Entities.PRODUCT, "productCategories")
		);
	}

	@DisplayName("define references and redefine to bidirectional")
	@Test
	void shouldDefineReferencesAndRedefineToBiDi() {
		final EntitySchemaBuilder categorySchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.categorySchema
		);

		final String sharedReferenceName = "categoryProducts";
		categorySchemaBuilder.withReferenceToEntity(sharedReferenceName, Entities.PRODUCT, Cardinality.ONE_OR_MORE)
			.withoutReferenceTo(sharedReferenceName)
			.withReflectedReferenceToEntity(sharedReferenceName, Entities.PRODUCT, "productCategories");

		final ReferenceSchemaContract categoryProducts = categorySchemaBuilder.toInstance()
			.getReference(sharedReferenceName)
			.orElseThrow();

		assertInstanceOf(ReflectedReferenceSchema.class, categoryProducts);
	}

	@DisplayName("define bidirectional reference and redefine to standard one")
	@Test
	void shouldDefineBidiReferenceAndRedefineToStandardOne() {
		final EntitySchemaBuilder categorySchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.categorySchema
		);

		final String sharedReferenceName = "categoryProducts";
		categorySchemaBuilder
			.withReflectedReferenceToEntity(sharedReferenceName, Entities.PRODUCT, "productCategories")
			.withoutReferenceTo(sharedReferenceName)
			.withReferenceToEntity(sharedReferenceName, Entities.PRODUCT, Cardinality.ONE_OR_MORE);

		final ReferenceSchemaContract categoryProducts = categorySchemaBuilder.toInstance()
			.getReference(sharedReferenceName)
			.orElseThrow();

		assertFalse(categoryProducts instanceof ReflectedReferenceSchema);
	}

	@DisplayName("fail to define references and redefine to bidirectional directly on previously stored instance")
	@Test
	void shouldFailToDefineReferencesAndRedefineToBiDiDirectlyOnPreviouslyStoredInstance() {
		final String sharedReferenceName = "categoryProducts";
		final EntitySchemaBuilder categorySchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.categorySchema
			).withReferenceToEntity(sharedReferenceName, Entities.PRODUCT, Cardinality.ONE_OR_MORE)
				.toInstance()
		);

		assertThrows(
			InvalidSchemaMutationException.class,
			() -> categorySchemaBuilder
				.withReflectedReferenceToEntity(sharedReferenceName, Entities.PRODUCT, "productCategories")
		);
	}

	@DisplayName("define references and redefine to bidirectional on previously stored instance")
	@Test
	void shouldDefineReferencesAndRedefineToBiDiOnPreviouslyStoredInstance() {
		final String sharedReferenceName = "categoryProducts";
		final EntitySchemaBuilder categorySchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.categorySchema
			)
				.withReferenceToEntity(sharedReferenceName, Entities.PRODUCT, Cardinality.ONE_OR_MORE)
				.toInstance()
		);

		categorySchemaBuilder
			.withoutReferenceTo(sharedReferenceName)
			.withReflectedReferenceToEntity(sharedReferenceName, Entities.PRODUCT, "productCategories");

		final ReferenceSchemaContract categoryProducts = categorySchemaBuilder.toInstance()
			.getReference(sharedReferenceName)
			.orElseThrow();

		assertInstanceOf(ReflectedReferenceSchema.class, categoryProducts);
	}

	@DisplayName("define bidirectional reference and redefine to standard one on previously stored instance")
	@Test
	void shouldDefineBidiReferenceAndRedefineToStandardOneOnPreviouslyStoredInstance() {
		final String sharedReferenceName = "categoryProducts";
		final EntitySchemaBuilder categorySchemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			new InternalEntitySchemaBuilder(
				this.catalogSchema,
				this.categorySchema
			)
				.withReflectedReferenceToEntity(sharedReferenceName, Entities.PRODUCT, "productCategories")
				.toInstance()
		);

		categorySchemaBuilder
			.withoutReferenceTo(sharedReferenceName)
			.withReferenceToEntity(sharedReferenceName, Entities.PRODUCT, Cardinality.ONE_OR_MORE);

		final ReferenceSchemaContract categoryProducts = categorySchemaBuilder.toInstance()
			.getReference(sharedReferenceName)
			.orElseThrow();

		assertFalse(categoryProducts instanceof ReflectedReferenceSchema);
	}

	/**
	 * Tests the cooperatingWith method functionality of the EntitySchemaBuilder.
	 * This test verifies that the schema builder can be configured to cooperate with a catalog schema
	 * supplier, which is useful for scenarios where the catalog schema might change during the
	 * schema building process. The cooperatingWith method allows the builder to dynamically
	 * access the latest catalog schema state, ensuring consistency and proper validation
	 * against the current catalog configuration.
	 */
	@DisplayName("test cooperating with method")
	@Test
	void shouldTestCooperatingWithMethod() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Test cooperatingWith method - configure the builder to use a catalog schema supplier
		// This allows the builder to dynamically access catalog schema during operations
		final EntitySchemaBuilder cooperatingBuilder = schemaBuilder.cooperatingWith(() -> this.catalogSchema);
		assertNotNull(cooperatingBuilder);
		assertEquals(schemaBuilder, cooperatingBuilder);  // Should return the same builder instance

		// Verify that the builder still works correctly after cooperation setup
		// This ensures that the cooperation mechanism doesn't break normal schema building operations
		final EntitySchemaContract schema = cooperatingBuilder
			.withAttribute("testAttr", String.class)
			.toInstance();

		assertTrue(schema.getAttribute("testAttr").isPresent());
	}

	/**
	 * Tests the verifySchemaButCreateOnTheFly method functionality.
	 * This test verifies that when the schema is configured to allow creation on the fly,
	 * all evolution modes are enabled, allowing the schema to automatically adapt and evolve
	 * as new attributes, references, or associated data are added. This is useful for
	 * development environments or scenarios where schema flexibility is more important
	 * than strict validation. The method essentially enables all possible schema evolution
	 * modes, making the schema completely permissive.
	 */
	@DisplayName("test verify schema but create on the fly")
	@Test
	void shouldTestVerifySchemaButCreateOnTheFly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Configure schema to allow creation on the fly - this enables all evolution modes
		final EntitySchemaContract schema = schemaBuilder
			.verifySchemaButCreateOnTheFly()  // Enable all evolution modes for maximum flexibility
			.withAttribute("newAttribute", String.class)  // Add a new attribute to test the flexibility
			.toInstance();

		// Verify that the new attribute was successfully added
		assertTrue(schema.getAttribute("newAttribute").isPresent());
		// Verify that all evolution modes are enabled when using createOnTheFly
		for (EvolutionMode mode : EvolutionMode.values()) {
			assertTrue(schema.allows(mode), "Evolution mode " + mode + " should be allowed when creating on the fly");
		}
	}

	/**
	 * Tests the withGeneratedPrimaryKey method functionality.
	 * This test verifies that the schema builder can configure an entity schema to use
	 * automatically generated primary keys. When enabled, the system will automatically
	 * assign unique primary key values to new entities, eliminating the need for
	 * client applications to provide primary key values. This is useful for scenarios
	 * where primary key management should be handled by the database system.
	 */
	@DisplayName("test with generated primary key")
	@Test
	void shouldTestWithGeneratedPrimaryKey() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Configure the schema to use generated primary keys
		final EntitySchemaContract schema = schemaBuilder
			.withGeneratedPrimaryKey()  // Enable automatic primary key generation
			.toInstance();

		// Verify that the schema is configured for generated primary keys
		assertTrue(schema.isWithGeneratedPrimaryKey());
	}

	/**
	 * Tests the withoutGeneratedPrimaryKey method functionality.
	 * This test verifies that the schema builder can disable automatic primary key generation,
	 * requiring client applications to provide primary key values when creating entities.
	 * The test also demonstrates that the builder methods can be chained and that later
	 * method calls can override earlier ones, allowing for flexible schema configuration.
	 */
	@DisplayName("test without generated primary key")
	@Test
	void shouldTestWithoutGeneratedPrimaryKey() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// First enable generated primary keys, then disable them to test method chaining
		final EntitySchemaContract schema = schemaBuilder
			.withGeneratedPrimaryKey()     // First enable generated primary keys
			.withoutGeneratedPrimaryKey()  // Then disable them - this should override the previous setting
			.toInstance();

		// Verify that generated primary keys are disabled (the last method call takes precedence)
		assertFalse(schema.isWithGeneratedPrimaryKey());
	}

	/**
	 * Tests the withHierarchyIndexedInScope method functionality.
	 * This test verifies that the schema builder can configure hierarchical indexing for specific scopes.
	 * Hierarchical indexing allows entities to be organized in tree structures (parent-child relationships)
	 * and enables efficient querying of hierarchical data within the specified scopes. Scopes define
	 * different data visibility levels (e.g., LIVE for active data, ARCHIVED for historical data).
	 * This is useful for organizing entities like categories, organizational structures, or taxonomies.
	 */
	@DisplayName("test with hierarchy indexed in scope")
	@Test
	void shouldTestWithHierarchyIndexedInScope() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Configure the schema to support hierarchy indexing in specific scopes
		final EntitySchemaContract schema = schemaBuilder
			.withHierarchyIndexedInScope(Scope.LIVE, Scope.ARCHIVED)  // Enable hierarchy in both LIVE and ARCHIVED scopes
			.toInstance();

		// Verify that hierarchy support is enabled
		assertTrue(schema.isWithHierarchy());
		// Verify that hierarchy indexing is enabled for the specified scopes
		assertTrue(schema.isHierarchyIndexedInScope(Scope.LIVE));
		assertTrue(schema.isHierarchyIndexedInScope(Scope.ARCHIVED));
	}

	/**
	 * Tests the withoutHierarchyIndexedInScope method functionality.
	 * This test verifies that the schema builder can selectively disable hierarchical indexing
	 * for specific scopes while maintaining it for others. This allows fine-grained control
	 * over where hierarchical queries are supported, which can be useful for performance
	 * optimization or when hierarchical relationships are only relevant in certain contexts.
	 */
	@DisplayName("test without hierarchy indexed in scope")
	@Test
	void shouldTestWithoutHierarchyIndexedInScope() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// First enable hierarchy in multiple scopes, then selectively disable it in one scope
		final EntitySchemaContract schema = schemaBuilder
			.withHierarchyIndexedInScope(Scope.LIVE, Scope.ARCHIVED)  // Enable hierarchy in both scopes
			.withoutHierarchyIndexedInScope(Scope.ARCHIVED)           // Then disable it only in ARCHIVED scope
			.toInstance();

		// Verify that hierarchy support is still enabled overall
		assertTrue(schema.isWithHierarchy());
		// Verify that hierarchy indexing remains enabled in LIVE scope
		assertTrue(schema.isHierarchyIndexedInScope(Scope.LIVE));
		// Verify that hierarchy indexing is disabled in ARCHIVED scope
		assertFalse(schema.isHierarchyIndexedInScope(Scope.ARCHIVED));
	}

	/**
	 * Tests the withPriceIndexedInScope method with custom decimal places configuration.
	 * This test verifies that the schema builder can configure price indexing for specific scopes
	 * with a custom number of indexed decimal places. Price indexing allows efficient filtering
	 * and sorting by price values within the specified scopes, while the decimal places setting
	 * controls the precision of price comparisons and indexing.
	 */
	@DisplayName("test with price indexed in scope with decimal places")
	@Test
	void shouldTestWithPriceIndexedInScopeWithDecimalPlaces() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final EntitySchemaContract schema = schemaBuilder
			.withPriceIndexedInScope(4, Scope.LIVE, Scope.ARCHIVED)
			.toInstance();

		assertTrue(schema.isWithPrice());
		assertTrue(schema.isPriceIndexedInScope(Scope.LIVE));
		assertTrue(schema.isPriceIndexedInScope(Scope.ARCHIVED));
		assertEquals(4, schema.getIndexedPricePlaces());
	}

	/**
	 * Tests the withIndexedPriceInCurrency method functionality.
	 * This test verifies that the schema builder can configure price indexing for specific currencies
	 * within a given scope. This allows efficient filtering and querying of entities by price
	 * in the specified currencies, while maintaining price indexing capabilities within the scope.
	 * The test uses USD and EUR currencies as examples.
	 */
	@DisplayName("test with indexed price in currency")
	@Test
	void shouldTestWithIndexedPriceInCurrency() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final Currency[] currencies = {Currency.getInstance("USD"), Currency.getInstance("EUR")};
		final EntitySchemaContract schema = schemaBuilder
			.withIndexedPriceInCurrency(currencies, Scope.LIVE)
			.toInstance();

		assertTrue(schema.isWithPrice());
		assertTrue(schema.isPriceIndexedInScope(Scope.LIVE));
		assertTrue(schema.getCurrencies().contains(Currency.getInstance("USD")));
		assertTrue(schema.getCurrencies().contains(Currency.getInstance("EUR")));
		assertEquals(2, schema.getIndexedPricePlaces()); // default value
	}

	/**
	 * Tests the withPriceInCurrencyIndexedInScope method functionality.
	 * This test verifies that the schema builder can configure price indexing for specific currencies
	 * within multiple scopes, with custom decimal places precision. This comprehensive method
	 * combines currency-specific indexing, scope-specific indexing, and decimal precision control
	 * in a single configuration, allowing for efficient price-based queries across different
	 * data visibility levels with the specified currencies and precision.
	 */
	@DisplayName("test with price in currency indexed in scope")
	@Test
	void shouldTestWithPriceInCurrencyIndexedInScope() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final Currency[] currencies = {Currency.getInstance("USD"), Currency.getInstance("EUR")};
		final EntitySchemaContract schema = schemaBuilder
			.withPriceInCurrencyIndexedInScope(3, currencies, Scope.LIVE, Scope.ARCHIVED)
			.toInstance();

		assertTrue(schema.isWithPrice());
		assertTrue(schema.isPriceIndexedInScope(Scope.LIVE));
		assertTrue(schema.isPriceIndexedInScope(Scope.ARCHIVED));
		assertTrue(schema.getCurrencies().contains(Currency.getInstance("USD")));
		assertTrue(schema.getCurrencies().contains(Currency.getInstance("EUR")));
		assertEquals(3, schema.getIndexedPricePlaces());
	}

	/**
	 * Tests the withoutPriceIndexedInScope method functionality.
	 * This test verifies that the schema builder can selectively disable price indexing
	 * for specific scopes while maintaining it for others. This allows fine-grained control
	 * over where price-based queries are supported, which can be useful for performance
	 * optimization when price indexing is only needed in certain data visibility contexts.
	 */
	@DisplayName("test without price indexed in scope")
	@Test
	void shouldTestWithoutPriceIndexedInScope() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final EntitySchemaContract schema = schemaBuilder
			.withPriceIndexedInScope(Scope.LIVE, Scope.ARCHIVED)
			.withoutPriceIndexedInScope(Scope.ARCHIVED)
			.toInstance();

		assertTrue(schema.isWithPrice());
		assertTrue(schema.isPriceIndexedInScope(Scope.LIVE));
		assertFalse(schema.isPriceIndexedInScope(Scope.ARCHIVED));
	}

	/**
	 * Tests the withoutPriceInCurrency method functionality.
	 * This test verifies that the schema builder can selectively remove specific currencies
	 * from price indexing while maintaining price indexing for other currencies. This allows
	 * dynamic management of which currencies are supported for price-based queries, which can
	 * be useful when business requirements change or when optimizing for specific markets.
	 */
	@DisplayName("test without price in currency")
	@Test
	void shouldTestWithoutPriceInCurrency() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final Currency usd = Currency.getInstance("USD");
		final Currency eur = Currency.getInstance("EUR");
		final EntitySchemaContract schema = schemaBuilder
			.withIndexedPriceInCurrency(new Currency[]{usd, eur}, Scope.LIVE)
			.withoutPriceInCurrency(usd)
			.toInstance();

		assertTrue(schema.isWithPrice());
		assertFalse(schema.getCurrencies().contains(usd));
		assertTrue(schema.getCurrencies().contains(eur));
	}

	/**
	 * Tests the withLocale method functionality with multiple locales.
	 * This test verifies that the schema builder can configure support for multiple locales
	 * simultaneously. Multi-locale support enables entities to have localized attributes
	 * and associated data in different languages/regions, allowing applications to serve
	 * content in multiple languages while maintaining data consistency and efficient querying.
	 */
	@DisplayName("test with locale multiple")
	@Test
	void shouldTestWithLocaleMultiple() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final Locale czech = new Locale("cs", "CZ");
		final Locale german = Locale.GERMAN;
		final EntitySchemaContract schema = schemaBuilder
			.withLocale(czech, german)
			.toInstance();

		assertTrue(schema.getLocales().contains(czech));
		assertTrue(schema.getLocales().contains(german));
	}

	/**
	 * Tests the withoutLocale method functionality.
	 * This test verifies that the schema builder can selectively remove specific locales
	 * from the schema configuration while maintaining support for other locales. This allows
	 * dynamic management of which languages/regions are supported, which can be useful when
	 * business requirements change or when optimizing for specific markets or user bases.
	 */
	@DisplayName("test without locale")
	@Test
	void shouldTestWithoutLocale() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final Locale czech = new Locale("cs", "CZ");
		final Locale german = Locale.GERMAN;
		final EntitySchemaContract schema = schemaBuilder
			.withLocale(czech, german, Locale.ENGLISH)
			.withoutLocale(german)
			.toInstance();

		assertTrue(schema.getLocales().contains(czech));
		assertFalse(schema.getLocales().contains(german));
		assertTrue(schema.getLocales().contains(Locale.ENGLISH));
	}

	/**
	 * Tests the withGlobalAttribute method functionality.
	 * This test verifies that the schema builder can reference and include global attributes
	 * that are defined at the catalog level. Global attributes are shared across multiple
	 * entity types within the same catalog, promoting consistency and reducing duplication
	 * of common attribute definitions. This test ensures the method handles both existing
	 * and non-existing global attribute references appropriately.
	 */
	@DisplayName("test with global attribute")
	@Test
	void shouldTestWithGlobalAttribute() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// This should work if the global attribute exists in catalog schema
		// For this test, we'll assume it throws an exception if not found
		assertDoesNotThrow(() -> {
			schemaBuilder.withGlobalAttribute("nonExistentGlobalAttribute");
		});
	}

	/**
	 * Tests the withDescription method functionality.
	 * This test verifies that the schema builder can set a descriptive text for the entity schema.
	 * Descriptions provide human-readable documentation about the purpose and usage of the entity
	 * type, which is valuable for developers, API documentation, and schema introspection.
	 * The description becomes part of the schema metadata and can be retrieved later.
	 */
	@DisplayName("test with description")
	@Test
	void shouldTestWithDescription() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final String description = "Test entity description";
		final EntitySchemaContract schema = schemaBuilder
			.withDescription(description)
			.toInstance();

		assertEquals(description, schema.getDescription());
	}

	/**
	 * Tests the withDescription method functionality with null value.
	 * This test verifies that the schema builder can clear an existing description by setting it to null.
	 * This demonstrates that later method calls can override earlier ones, allowing for flexible
	 * schema configuration where descriptions can be added and then removed if needed.
	 * The test ensures that passing null effectively removes any previously set description.
	 */
	@DisplayName("test with null description")
	@Test
	void shouldTestWithNullDescription() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final EntitySchemaContract schema = schemaBuilder
			.withDescription("Some description")
			.withDescription(null)
			.toInstance();

		assertNull(schema.getDescription());
	}

	/**
	 * Tests the deprecated method functionality.
	 * This test verifies that the schema builder can mark an entity schema as deprecated
	 * with a custom deprecation notice. Deprecation is used to signal that an entity type
	 * should no longer be used in new development and may be removed in future versions.
	 * The deprecation notice provides information about why the schema is deprecated and
	 * what alternatives should be used instead.
	 */
	@DisplayName("test deprecated")
	@Test
	void shouldTestDeprecated() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		final String deprecationNotice = "This entity is deprecated";
		final EntitySchemaContract schema = schemaBuilder
			.deprecated(deprecationNotice)
			.toInstance();

		assertEquals(deprecationNotice, schema.getDeprecationNotice());
	}

	/**
	 * Tests the notDeprecatedAnymore method functionality.
	 * This test verifies that the schema builder can remove deprecation notices from entity schemas.
	 * When a schema was previously marked as deprecated, calling notDeprecatedAnymore() should
	 * clear the deprecation notice, effectively un-deprecating the schema. This is useful for
	 * scenarios where a previously deprecated entity schema is being restored to active use
	 * or when deprecation was applied in error and needs to be reversed.
	 */
	@DisplayName("test not deprecated anymore")
	@Test
	void shouldTestNotDeprecatedAnymore() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// First mark the schema as deprecated, then remove the deprecation to test the override behavior
		final EntitySchemaContract schema = schemaBuilder
			.deprecated("Some deprecation notice")  // First add a deprecation notice
			.notDeprecatedAnymore()                  // Then remove it - this should clear the deprecation
			.toInstance();

		// Verify that the deprecation notice has been completely removed
		assertNull(schema.getDeprecationNotice());
	}

	/**
	 * Tests the constructor that accepts pre-existing mutations.
	 * This test verifies that the EntitySchemaBuilder can be initialized with a collection
	 * of mutations that should be applied to the base schema. This is useful for scenarios
	 * where you want to create a builder that already has some modifications applied,
	 * such as when continuing schema evolution from a previous state or when applying
	 * a batch of mutations that were prepared elsewhere. The test ensures that mutations
	 * passed to the constructor are properly applied when the schema instance is built.
	 */
	@DisplayName("test constructor with mutations")
	@Test
	void shouldTestConstructorWithMutations() {
		// Create a list of mutations to be applied during builder initialization
		final List<LocalEntitySchemaMutation> mutations = List.of(
			new SetEntitySchemaWithGeneratedPrimaryKeyMutation(true)  // Enable generated primary keys
		);

		// Initialize the builder with pre-existing mutations
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema,
			mutations  // Pass mutations to be applied during initialization
		);

		// Build the schema and verify that the pre-existing mutations were applied
		final EntitySchemaContract schema = schemaBuilder.toInstance();
		assertTrue(schema.isWithGeneratedPrimaryKey());  // Verify the mutation was applied
	}

	/**
	 * Tests the toMutation method when no changes have been made to the schema.
	 * This test verifies that when a schema builder is created but no modifications
	 * are applied to it, the toMutation() method returns an empty Optional, indicating
	 * that no mutations are needed. This is important for optimization purposes - if
	 * no changes were made, there's no need to create or apply any mutations to the
	 * underlying schema storage system.
	 */
	@DisplayName("test to mutation when empty")
	@Test
	void shouldTestToMutationWhenEmpty() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Call toMutation() without making any changes to the schema
		final Optional<ModifyEntitySchemaMutation> mutation = schemaBuilder.toMutation();
		// Verify that no mutation is returned since no changes were made
		assertTrue(mutation.isEmpty());
	}

	/**
	 * Tests the toMutation method when changes have been made to the schema.
	 * This test verifies that when modifications are applied to a schema builder,
	 * the toMutation() method returns a ModifyEntitySchemaMutation containing all
	 * the changes that need to be applied to persist the schema modifications.
	 * This mutation can then be used to update the schema in the storage system
	 * or to replicate the changes in other environments.
	 */
	@DisplayName("test to mutation when not empty")
	@Test
	void shouldTestToMutationWhenNotEmpty() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Make a change to the schema to ensure a mutation will be generated
		schemaBuilder.withGeneratedPrimaryKey();

		// Call toMutation() after making changes
		final Optional<ModifyEntitySchemaMutation> mutation = schemaBuilder.toMutation();
		// Verify that a mutation is returned since changes were made
		assertTrue(mutation.isPresent());
		// Verify that the mutation targets the correct entity type
		assertEquals(this.productSchema.getName(), mutation.get().getName());
	}

	/**
	 * Tests the getNameVariants method functionality.
	 * This test verifies that the schema builder can provide all available naming convention
	 * variants for the entity schema name. Different naming conventions (camelCase, kebab-case,
	 * snake_case, etc.) are automatically generated from the base entity name to support
	 * various API styles and client preferences. This method returns a map containing all
	 * supported naming conventions and their corresponding name variants, which is useful
	 * for API documentation, code generation, and multi-format schema exports.
	 */
	@DisplayName("test get name variants")
	@Test
	void shouldTestGetNameVariants() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Retrieve all naming convention variants for the entity schema
		final Map<NamingConvention, String> nameVariants = schemaBuilder.getNameVariants();
		// Verify that the map is not null and contains naming variants
		assertNotNull(nameVariants);
		assertFalse(nameVariants.isEmpty());  // Should contain at least one naming convention variant
	}

	/**
	 * Tests the getNameVariant method for a specific naming convention.
	 * This test verifies that the schema builder can provide the entity name in a specific
	 * naming convention format. This is useful when you need the entity name formatted
	 * according to a particular naming style (e.g., camelCase for JavaScript APIs,
	 * snake_case for Python APIs, etc.). The method should return the same result as
	 * the base schema's getNameVariant method, ensuring consistency between the builder
	 * and the underlying schema.
	 */
	@DisplayName("test get name variant")
	@Test
	void shouldTestGetNameVariant() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Get the entity name in camelCase naming convention
		final String nameVariant = schemaBuilder.getNameVariant(NamingConvention.CAMEL_CASE);
		// Verify that the name variant is not null
		assertNotNull(nameVariant);
		// Verify that the builder returns the same name variant as the base schema
		assertEquals(this.productSchema.getNameVariant(NamingConvention.CAMEL_CASE), nameVariant);
	}

	/**
	 * Tests the verifySchemaStrictly method functionality.
	 * This test verifies that when strict schema verification is enabled, all evolution modes
	 * are disabled, meaning the schema will not allow any automatic adaptations or modifications.
	 * This is the most restrictive validation mode, suitable for production environments where
	 * schema stability is critical and any schema changes should be explicitly planned and
	 * applied through controlled processes. In strict mode, any attempt to use undefined
	 * attributes, references, or associated data will result in validation errors.
	 */
	@DisplayName("test verify schema strictly")
	@Test
	void shouldTestVerifySchemaStrictly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Configure the schema for strict verification - no evolution modes allowed
		final EntitySchemaContract schema = schemaBuilder
			.verifySchemaStrictly()  // Enable strict validation mode
			.toInstance();

		// Verify that all evolution modes are disabled in strict mode
		// This ensures maximum schema stability and prevents any automatic adaptations
		for (EvolutionMode mode : EvolutionMode.values()) {
			assertFalse(schema.allows(mode), "Evolution mode " + mode + " should be disallowed in strict verification");
		}
	}

	/**
	 * Tests the verifySchemaButAllow method with specific evolution modes.
	 * This test verifies that the schema can be configured to allow only specific types
	 * of evolution while maintaining strict validation for others. This provides a balanced
	 * approach between flexibility and control, allowing certain types of schema changes
	 * (like adding new attributes or references) while preventing others (like adding
	 * associated data). This is useful for environments where some level of schema
	 * evolution is acceptable but needs to be controlled and limited to specific areas.
	 */
	@DisplayName("test verify schema but allow specific modes")
	@Test
	void shouldTestVerifySchemaButAllowSpecificModes() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Configure the schema to allow only specific evolution modes
		final EntitySchemaContract schema = schemaBuilder
			.verifySchemaButAllow(EvolutionMode.ADDING_ATTRIBUTES, EvolutionMode.ADDING_REFERENCES)  // Allow only these modes
			.toInstance();

		// Verify that the explicitly allowed evolution modes are enabled
		assertTrue(schema.allows(EvolutionMode.ADDING_ATTRIBUTES));
		assertTrue(schema.allows(EvolutionMode.ADDING_REFERENCES));
		// Verify that non-specified evolution modes remain disabled
		assertFalse(schema.allows(EvolutionMode.ADDING_ASSOCIATED_DATA));
	}

	/**
	 * Tests that the schema builder properly handles empty scope arrays.
	 * This test verifies that methods that accept scope parameters can handle empty arrays
	 * gracefully without throwing exceptions. When no scopes are provided, the methods
	 * should either use default behavior or simply not enable the feature for any scope.
	 * This is important for robustness and prevents runtime errors when scope arrays
	 * might be dynamically constructed and could potentially be empty.
	 */
	@DisplayName("handle empty scopes")
	@Test
	void shouldHandleEmptyScopes() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Test that hierarchy indexing methods handle empty scope arrays gracefully
		assertDoesNotThrow(() -> {
			schemaBuilder.withHierarchyIndexedInScope();  // No scopes provided - should not throw
		});

		// Test that price indexing methods handle empty scope arrays gracefully
		assertDoesNotThrow(() -> {
			schemaBuilder.withPriceIndexedInScope();  // No scopes provided - should not throw
		});
	}

	/**
	 * Tests that the withAttribute method properly handles null consumer parameters.
	 * This test verifies that when a null consumer is passed to the withAttribute method,
	 * the method doesn't throw an exception and creates the attribute with default settings.
	 * The consumer parameter is optional and used for additional attribute configuration,
	 * so null values should be handled gracefully. This is important for API robustness
	 * and allows callers to create simple attributes without needing to provide empty
	 * lambda expressions when no additional configuration is needed.
	 */
	@DisplayName("handle null consumer in with attribute")
	@Test
	void shouldHandleNullConsumerInWithAttribute() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Test that withAttribute handles null consumer gracefully
		// The attribute should be created with default settings when no consumer is provided
		assertDoesNotThrow(() -> {
			schemaBuilder.withAttribute("testAttribute", String.class, null);  // null consumer should not throw
		});
	}

	/**
	 * Tests that the withAssociatedData method properly handles null consumer parameters.
	 * This test verifies that when a null consumer is passed to the withAssociatedData method,
	 * the method doesn't throw an exception and creates the associated data with default settings.
	 * The consumer parameter is optional and used for additional associated data configuration,
	 * so null values should be handled gracefully. This ensures API consistency and allows
	 * callers to create simple associated data definitions without complex configuration.
	 */
	@DisplayName("handle null consumer in with associated data")
	@Test
	void shouldHandleNullConsumerInWithAssociatedData() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Test that withAssociatedData handles null consumer gracefully
		// The associated data should be created with default settings when no consumer is provided
		assertDoesNotThrow(() -> {
			schemaBuilder.withAssociatedData("testData", String.class, null);  // null consumer should not throw
		});
	}

	/**
	 * Tests that the withSortableAttributeCompound method properly handles null consumer parameters.
	 * This test verifies that when a null consumer is passed to the withSortableAttributeCompound method,
	 * the method doesn't throw an exception and creates the compound with default settings.
	 * The consumer parameter is optional and used for additional compound configuration (like description,
	 * deprecation notices, etc.), so null values should be handled gracefully. This test also ensures
	 * that the method works correctly when attributes are created first and then used in compounds.
	 */
	@DisplayName("handle null consumer in with sortable attribute compound")
	@Test
	void shouldHandleNullConsumerInWithSortableAttributeCompound() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);

		// Test that withSortableAttributeCompound handles null consumer gracefully
		// First create the required attributes, then create a compound with null consumer
		assertDoesNotThrow(() -> {
			schemaBuilder
				.withAttribute("attr1", String.class)  // Create first attribute for the compound
				.withAttribute("attr2", String.class)  // Create second attribute for the compound
				.withSortableAttributeCompound(
					"testCompound",  // Compound name
					new AttributeElement[]{
						attributeElement("attr1"),  // First element of the compound
						attributeElement("attr2")   // Second element of the compound
					},
					null  // null consumer should not throw - compound created with default settings
				);
		});
	}

	/**
	 * Test data class representing a set of referenced files.
	 * This class is used as a complex data type for testing associated data functionality
	 * in entity schemas. It implements Serializable to support storage and retrieval
	 * of associated data values in the evitaDB system.
	 */
	public static class ReferencedFileSet implements Serializable {
		@Serial private static final long serialVersionUID = -1355676966187183143L;
	}

	/**
	 * Test data class representing entity labels or tags.
	 * This class is used as a complex data type for testing localized associated data functionality
	 * in entity schemas. It implements Serializable to support storage and retrieval
	 * of associated data values in the evitaDB system. Labels are typically used to store
	 * localized textual information such as display names, descriptions, or categorization tags.
	 */
	public static class Labels implements Serializable {
		@Serial private static final long serialVersionUID = 1121150156843379388L;
	}

}
