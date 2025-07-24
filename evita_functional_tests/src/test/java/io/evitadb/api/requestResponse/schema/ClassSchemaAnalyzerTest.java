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

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.SchemaClassInvalidException;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.model.*;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link ClassSchemaAnalyzer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class ClassSchemaAnalyzerTest implements EvitaTestSupport {
	public static final String DIR_CLASS_SCHEMA_ANALYZER_TEST = "classSchemaAnalyzerTest";
	public static final String DIR_CLASS_SCHEMA_ANALYZER_TEST_EXPORT = "classSchemaAnalyzerTest_export";
	public static final String ATTRIBUTE_DCODE = "code";
	public static final String ATTRIBUTE_NAME = "name";
	public static final String ATTRIBUTE_EAN = "ean";
	public static final String ATTRIBUTE_QUANTITY = "quantity";

	private Evita evita;

	private static void assertAttribute(
		@Nonnull AttributeSchemaProvider<?> attributeSchemaProvider,
		@Nonnull String attributeName,
		@Nullable String description,
		@Nullable String deprecation,
		@Nonnull Class<?> expectedType,
		boolean global,
		boolean globallyUnique,
		boolean unique,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		boolean representative
	) {
		final AttributeSchemaContract attributeSchema = attributeSchemaProvider
			.getAttribute(attributeName)
			.orElseThrow();

		if (description == null) {
			assertNull(attributeSchema.getDescription());
		} else {
			assertEquals(description, attributeSchema.getDescription());
		}
		if (deprecation == null) {
			assertNull(attributeSchema.getDeprecationNotice());
		} else {
			assertEquals(deprecation, attributeSchema.getDeprecationNotice());
		}
		assertEquals(expectedType, attributeSchema.getType());
		if (global) {
			assertTrue(attributeSchema instanceof GlobalAttributeSchema);
		}
		if (globallyUnique) {
			assertTrue(attributeSchema instanceof GlobalAttributeSchema);
			assertTrue(((GlobalAttributeSchema) attributeSchema).isUniqueGlobally());
		}
		assertEquals(unique, attributeSchema.isUnique(), "Attribute `" + attributeName + "` is expected to be " + (unique ? "" : "not") + " unique, but it " + (unique ? "is not" : "is") + ".");
		assertEquals(filterable, attributeSchema.isFilterable(), "Attribute `" + attributeName + "` is expected to be " + (filterable ? "" : "not") + " filterable, but it " + (filterable ? "is not" : "is") + ".");
		assertEquals(sortable, attributeSchema.isSortable(), "Attribute `" + attributeName + "` is expected to be " + (sortable ? "" : "not") + " sortable, but it " + (sortable ? "is not" : "is") + ".");
		assertEquals(localized, attributeSchema.isLocalized(), "Attribute `" + attributeName + "` is expected to be " + (localized ? "" : "not") + "localized, but it " + (localized ? "is not" : "is") + ".");
		assertEquals(nullable, attributeSchema.isNullable(), "Attribute `" + attributeName + "` is expected to be " + (nullable ? "" : "not") + " nullable, but it " + (nullable ? "is not" : "is") + ".");
		if (attributeSchema instanceof EntityAttributeSchema entityAttributeSchema) {
			assertEquals(
				representative,
				entityAttributeSchema.isRepresentative(),
				"Attribute `" + attributeName + "` is expected to be " + (nullable ? "" : "not") + " representative, but it " + (nullable ? "is not" : "is") + "."
			);
		}
	}

	private static void assertAssociatedData(
		@Nonnull EntitySchemaContract entitySchemaContract,
		@Nonnull String associatedDataName,
		@Nullable String description,
		@Nullable String deprecation,
		@Nonnull Class<ComplexDataObject> expectedType,
		boolean localized,
		boolean nullable
	) {
		final AssociatedDataSchemaContract associatedData = entitySchemaContract.getAssociatedData(associatedDataName)
			.orElseThrow();

		if (description == null) {
			assertNull(associatedData.getDescription());
		} else {
			assertEquals(description, associatedData.getDescription());
		}
		if (deprecation == null) {
			assertNull(associatedData.getDeprecationNotice());
		} else {
			assertEquals(deprecation, associatedData.getDeprecationNotice());
		}
		assertEquals(expectedType, associatedData.getType(), "Associated data `" + associatedDataName + "` is expected to be `" + expectedType + "`, but is `" + associatedData.getType() + "`.");
		assertEquals(localized, associatedData.isLocalized(), "Associated data `" + associatedDataName + "` is expected to be " + (localized ? "" : "not") + "localized, but it " + (localized ? "is not" : "is") + ".");
		assertEquals(nullable, associatedData.isNullable(), "Associated data `" + associatedDataName + "` is expected to be " + (nullable ? "" : "not") + " nullable, but it " + (nullable ? "is not" : "is") + ".");
	}

	private static void assertReference(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecation,
		@Nonnull Cardinality cardinality,
		boolean referencedEntityTypeManaged,
		@Nonnull String entityType,
		boolean referencedGroupTypeManaged,
		@Nonnull String groupEntityType,
		boolean faceted,
		boolean indexed
	) {
		assertEquals(name, referenceSchema.getName());
		if (description == null) {
			assertNull(referenceSchema.getDescription());
		} else {
			assertEquals(description, referenceSchema.getDescription());
		}
		if (deprecation == null) {
			assertNull(referenceSchema.getDeprecationNotice());
		} else {
			assertEquals(deprecation, referenceSchema.getDeprecationNotice());
		}
		assertEquals(cardinality, referenceSchema.getCardinality());
		assertEquals(entityType, referenceSchema.getReferencedEntityType());
		assertEquals(referencedEntityTypeManaged, referenceSchema.isReferencedEntityTypeManaged());
		assertEquals(groupEntityType, referenceSchema.getReferencedGroupType());
		assertEquals(referencedGroupTypeManaged, referenceSchema.isReferencedGroupTypeManaged());
		assertEquals(faceted, referenceSchema.isFaceted(), "Attribute `" + name + "` is expected to be " + (faceted ? "" : "not") + " faceted, but it " + (faceted ? "is not" : "is") + ".");
		assertEquals(indexed, referenceSchema.isIndexed(), "Attribute `" + name + "` is expected to be " + (indexed ? "" : "not") + " indexed, but it " + (indexed ? "is not" : "is") + ".");
	}

	@SafeVarargs
	private static <T> void assertSetEquals(Set<T> actualValues, T... expectedValues) {
		assertEquals(expectedValues.length, actualValues.size());
		for (T expectedValue : expectedValues) {
			assertTrue(actualValues.contains(expectedValue), "Expected value not found: " + expectedValue);
		}
	}

	private static void assertReflectedReference(
		@Nonnull ReflectedReferenceSchemaContract referenceSchema,
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecation,
		@Nonnull Cardinality cardinality,
		@Nonnull String entityType,
		boolean referencedGroupTypeManaged,
		@Nonnull String groupEntityType,
		boolean faceted,
		boolean indexed
	) {
		assertEquals(name, referenceSchema.getName());
		if (description == null) {
			assertNull(referenceSchema.getDescription());
		} else {
			assertEquals(description, referenceSchema.getDescription());
		}
		if (deprecation == null) {
			assertNull(referenceSchema.getDeprecationNotice());
		} else {
			assertEquals(deprecation, referenceSchema.getDeprecationNotice());
		}
		assertEquals(cardinality, referenceSchema.getCardinality());
		assertEquals(entityType, referenceSchema.getReferencedEntityType());
		assertEquals(groupEntityType, referenceSchema.getReferencedGroupType());
		assertEquals(referencedGroupTypeManaged, referenceSchema.isReferencedGroupTypeManaged());
		assertEquals(faceted, referenceSchema.isFaceted(), "Attribute `" + name + "` is expected to be " + (faceted ? "" : "not") + " faceted, but it " + (faceted ? "is not" : "is") + ".");
		assertEquals(indexed, referenceSchema.isIndexed(), "Attribute `" + name + "` is expected to be " + (indexed ? "" : "not") + " indexed, but it " + (indexed ? "is not" : "is") + ".");
	}

	private static void assertEvolutionMode(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EvolutionMode... expectedEvolutionModes
	) {
		final Set<EvolutionMode> evolutionMode = entitySchema.getEvolutionMode();
		assertEquals(expectedEvolutionModes.length, evolutionMode.size());
		for (EvolutionMode expectedEvolutionMode : expectedEvolutionModes) {
			assertTrue(evolutionMode.contains(expectedEvolutionMode));
		}
	}

	private static void assertSortableAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String compoundName,
		@Nullable String description,
		@Nullable String deprecation,
		@Nonnull Scope[] indexedScopes,
		@Nonnull AttributeElement[] attributeElements
	) {
		final SortableAttributeCompoundSchemaContract compound = entitySchema.getSortableAttributeCompound(compoundName)
			.orElseThrow();

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

		for (Scope scope : Scope.values()) {
			boolean shouldBeIndexed = false;
			for (Scope indexedScope : indexedScopes) {
				if (scope == indexedScope) {
					shouldBeIndexed = true;
					break;
				}
			}
			assertEquals(shouldBeIndexed, compound.isIndexedInScope(scope),
				"Compound `" + compoundName + "` is expected to be " + (shouldBeIndexed ? "" : "not") +
					" indexed in scope " + scope + ", but it " + (shouldBeIndexed ? "is not" : "is") + ".");
		}

		assertEquals(attributeElements.length, compound.getAttributeElements().size());
		for (int i = 0; i < attributeElements.length; i++) {
			final AttributeElement expectedElement = attributeElements[i];
			final AttributeElement actualElement = compound.getAttributeElements().get(i);

			assertEquals(expectedElement.attributeName(), actualElement.attributeName());
			assertEquals(expectedElement.direction(), actualElement.direction());
			assertEquals(expectedElement.behaviour(), actualElement.behaviour());
		}
	}

	private static void assertSortableAttributeCompound(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String compoundName,
		@Nullable String description,
		@Nullable String deprecation,
		@Nonnull Scope[] indexedScopes,
		@Nonnull AttributeElement[] attributeElements
	) {
		final SortableAttributeCompoundSchemaContract compound = referenceSchema.getSortableAttributeCompound(compoundName)
			.orElseThrow();

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

		for (Scope scope : Scope.values()) {
			boolean shouldBeIndexed = false;
			for (Scope indexedScope : indexedScopes) {
				if (scope == indexedScope) {
					shouldBeIndexed = true;
					break;
				}
			}
			assertEquals(shouldBeIndexed, compound.isIndexedInScope(scope),
				"Compound `" + compoundName + "` is expected to be " + (shouldBeIndexed ? "" : "not") +
					" indexed in scope " + scope + ", but it " + (shouldBeIndexed ? "is not" : "is") + ".");
		}

		assertEquals(attributeElements.length, compound.getAttributeElements().size());
		for (int i = 0; i < attributeElements.length; i++) {
			final AttributeElement expectedElement = attributeElements[i];
			final AttributeElement actualElement = compound.getAttributeElements().get(i);

			assertEquals(expectedElement.attributeName(), actualElement.attributeName());
			assertEquals(expectedElement.direction(), actualElement.direction());
			assertEquals(expectedElement.behaviour(), actualElement.behaviour());
		}
	}

	private static void assertReferenceWithScopeSettings(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecation,
		@Nonnull Cardinality cardinality,
		boolean referencedEntityTypeManaged,
		@Nonnull String entityType,
		boolean referencedGroupTypeManaged,
		@Nonnull String groupEntityType,
		@Nonnull Map<Scope, Boolean> facetedInScopes,
		@Nonnull Map<Scope, ReferenceIndexType> indexedInScopes
	) {
		assertEquals(name, referenceSchema.getName());
		if (description == null) {
			assertNull(referenceSchema.getDescription());
		} else {
			assertEquals(description, referenceSchema.getDescription());
		}
		if (deprecation == null) {
			assertNull(referenceSchema.getDeprecationNotice());
		} else {
			assertEquals(deprecation, referenceSchema.getDeprecationNotice());
		}
		assertEquals(cardinality, referenceSchema.getCardinality());
		assertEquals(entityType, referenceSchema.getReferencedEntityType());
		assertEquals(referencedEntityTypeManaged, referenceSchema.isReferencedEntityTypeManaged());
		assertEquals(groupEntityType, referenceSchema.getReferencedGroupType());
		assertEquals(referencedGroupTypeManaged, referenceSchema.isReferencedGroupTypeManaged());

		// Check scope-specific faceted settings
		for (Map.Entry<Scope, Boolean> entry : facetedInScopes.entrySet()) {
			assertEquals(
				entry.getValue(),
				referenceSchema.isFacetedInScope(entry.getKey()),
				"Reference `" + name + "` is expected to be " + (entry.getValue() ? "" : "not") +
					" faceted in scope " + entry.getKey() + ", but it " + (entry.getValue() ? "is not" : "is") + "."
			);
		}

		// Check scope-specific indexed settings
		for (Map.Entry<Scope, ReferenceIndexType> entry : indexedInScopes.entrySet()) {
			assertEquals(
				entry.getValue(),
				referenceSchema.getReferenceIndexType(entry.getKey()),
				"Reference `" + name + "` is expected to have index type " + entry.getValue() +
					" in scope " + entry.getKey() + ", but has " + referenceSchema.getReferenceIndexType(entry.getKey()) + "."
			);
		}
	}

	private static void assertReflectedReferenceWithScopeSettings(
		@Nonnull ReflectedReferenceSchemaContract referenceSchema,
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecation,
		@Nonnull Cardinality cardinality,
		@Nonnull String entityType,
		boolean referencedGroupTypeManaged,
		@Nonnull String groupEntityType,
		@Nonnull Map<Scope, Boolean> facetedInScopes,
		@Nonnull Map<Scope, ReferenceIndexType> indexedInScopes
	) {
		assertEquals(name, referenceSchema.getName());
		if (description == null) {
			assertNull(referenceSchema.getDescription());
		} else {
			assertEquals(description, referenceSchema.getDescription());
		}
		if (deprecation == null) {
			assertNull(referenceSchema.getDeprecationNotice());
		} else {
			assertEquals(deprecation, referenceSchema.getDeprecationNotice());
		}
		assertEquals(cardinality, referenceSchema.getCardinality());
		assertEquals(entityType, referenceSchema.getReferencedEntityType());
		assertEquals(groupEntityType, referenceSchema.getReferencedGroupType());
		assertEquals(referencedGroupTypeManaged, referenceSchema.isReferencedGroupTypeManaged());

		// Check scope-specific faceted settings
		for (Map.Entry<Scope, Boolean> entry : facetedInScopes.entrySet()) {
			assertEquals(
				entry.getValue(),
				referenceSchema.isFacetedInScope(entry.getKey()),
				"Reflected reference `" + name + "` is expected to be " + (entry.getValue() ? "" : "not") +
					" faceted in scope " + entry.getKey() + ", but it " + (entry.getValue() ? "is not" : "is") + "."
			);
		}

		// Check scope-specific indexed settings
		for (Map.Entry<Scope, ReferenceIndexType> entry : indexedInScopes.entrySet()) {
			assertEquals(
				entry.getValue(),
				referenceSchema.getReferenceIndexType(entry.getKey()),
				"Reflected reference `" + name + "` is expected to have index type " + entry.getValue() +
					" in scope " + entry.getKey() + ", but has " + referenceSchema.getReferenceIndexType(entry.getKey()) + "."
			);
		}
	}

	private static void assertAttributeWithScopeSettings(
		@Nonnull AttributeSchemaProvider<?> attributeSchemaProvider,
		@Nonnull String attributeName,
		@Nullable String description,
		@Nullable String deprecation,
		@Nonnull Class<?> expectedType,
		boolean global,
		@Nonnull Map<Scope, GlobalAttributeUniquenessType> globallyUniqueInScopes,
		@Nonnull Map<Scope, AttributeUniquenessType> uniqueInScopes,
		@Nonnull Map<Scope, Boolean> filterableInScopes,
		@Nonnull Map<Scope, Boolean> sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative
	) {
		final AttributeSchemaContract attributeSchema = attributeSchemaProvider
			.getAttribute(attributeName)
			.orElseThrow();

		if (description == null) {
			assertNull(attributeSchema.getDescription());
		} else {
			assertEquals(description, attributeSchema.getDescription());
		}
		if (deprecation == null) {
			assertNull(attributeSchema.getDeprecationNotice());
		} else {
			assertEquals(deprecation, attributeSchema.getDeprecationNotice());
		}
		assertEquals(expectedType, attributeSchema.getType());
		if (global) {
			assertTrue(attributeSchema instanceof GlobalAttributeSchema);
		}
		assertEquals(localized, attributeSchema.isLocalized(), "Attribute `" + attributeName + "` is expected to be " + (localized ? "" : "not") + "localized, but it " + (localized ? "is not" : "is") + ".");
		assertEquals(nullable, attributeSchema.isNullable(), "Attribute `" + attributeName + "` is expected to be " + (nullable ? "" : "not") + " nullable, but it " + (nullable ? "is not" : "is") + ".");
		if (attributeSchema instanceof EntityAttributeSchema entityAttributeSchema) {
			assertEquals(
				representative,
				entityAttributeSchema.isRepresentative(),
				"Attribute `" + attributeName + "` is expected to be " + (representative ? "" : "not") + " representative, but it " + (representative ? "is not" : "is") + "."
			);
		}

		// Check scope-specific globally unique settings
		for (Map.Entry<Scope, GlobalAttributeUniquenessType> entry : globallyUniqueInScopes.entrySet()) {
			if (attributeSchema instanceof GlobalAttributeSchema globalAttributeSchema) {
				assertEquals(
					entry.getValue(),
					globalAttributeSchema.getGlobalUniquenessType(entry.getKey()),
					"Global attribute `" + attributeName + "` is expected to have global uniqueness type " + entry.getValue() +
						" in scope " + entry.getKey() + ", but has " + globalAttributeSchema.getGlobalUniquenessType(entry.getKey()) + "."
				);
			}
		}

		// Check scope-specific unique settings
		for (Map.Entry<Scope, AttributeUniquenessType> entry : uniqueInScopes.entrySet()) {
			assertEquals(
				entry.getValue(),
				attributeSchema.getUniquenessType(entry.getKey()),
				"Attribute `" + attributeName + "` is expected to have uniqueness type " + entry.getValue() +
					" in scope " + entry.getKey() + ", but has " + attributeSchema.getUniquenessType(entry.getKey()) + "."
			);
		}

		// Check scope-specific filterable settings
		for (Map.Entry<Scope, Boolean> entry : filterableInScopes.entrySet()) {
			assertEquals(
				entry.getValue(),
				attributeSchema.isFilterableInScope(entry.getKey()),
				"Attribute `" + attributeName + "` is expected to be " + (entry.getValue() ? "" : "not") +
					" filterable in scope " + entry.getKey() + ", but it " + (entry.getValue() ? "is not" : "is") + "."
			);
		}

		// Check scope-specific sortable settings
		for (Map.Entry<Scope, Boolean> entry : sortableInScopes.entrySet()) {
			assertEquals(
				entry.getValue(),
				attributeSchema.isSortableInScope(entry.getKey()),
				"Attribute `" + attributeName + "` is expected to be " + (entry.getValue() ? "" : "not") +
					" sortable in scope " + entry.getKey() + ", but it " + (entry.getValue() ? "is not" : "is") + "."
			);
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(DIR_CLASS_SCHEMA_ANALYZER_TEST);
		cleanTestSubDirectory(DIR_CLASS_SCHEMA_ANALYZER_TEST_EXPORT);
		this.evita = new Evita(
			EvitaConfiguration.builder()
				.storage(
					StorageOptions.builder()
						.storageDirectory(getTestDirectory().resolve(DIR_CLASS_SCHEMA_ANALYZER_TEST))
						.exportDirectory(getTestDirectory().resolve(DIR_CLASS_SCHEMA_ANALYZER_TEST_EXPORT))
						.build()
				)
				.build()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() throws IOException {
		this.evita.close();
		cleanTestSubDirectory(DIR_CLASS_SCHEMA_ANALYZER_TEST);
		cleanTestSubDirectory(DIR_CLASS_SCHEMA_ANALYZER_TEST_EXPORT);
	}

	@DisplayName("Verify that interface methods re analyzed and set up with defaults")
	@Test
	void shouldSetupNewSchemaByClassGetters() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(GetterBasedEntity.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("GetterBasedEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertNull(entitySchema.getDescription());
				assertNull(entitySchema.getDeprecationNotice());
				assertTrue(entitySchema.isWithGeneratedPrimaryKey());
				assertTrue(entitySchema.isWithHierarchy());
				assertTrue(entitySchema.isWithPrice());
				assertTrue(entitySchema.getCurrencies().isEmpty());
				assertTrue(entitySchema.getLocales().isEmpty());
				assertEquals(5, entitySchema.getAttributes().size());

				assertAttribute(
					entitySchema, ATTRIBUTE_DCODE, null, null, String.class,
					false, false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, ATTRIBUTE_NAME, null, null, String.class,
					false, false, false, false, false, true, false, false
				);
				assertAttribute(
					entitySchema, ATTRIBUTE_EAN, null, null, String.class,
					false, false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, ATTRIBUTE_QUANTITY, null, null, BigDecimal.class,
					false, false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, "years", null, null, Integer[].class,
					false, false, false, false, false, false, false, false
				);
				assertArrayEquals(
					new Integer[]{1978, 2005, 2020},
					(Integer[]) entitySchema.getAttribute("years").orElseThrow().getDefaultValue()
				);

				final Map<String, AssociatedDataSchemaContract> associatedData = entitySchema.getAssociatedData();
				assertNotNull(associatedData);
				assertEquals(1, associatedData.size());
				assertAssociatedData(
					entitySchema, "referencedFiles", null, null,
					ComplexDataObject.class,
					false, false
				);

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(2, references.size());

				final ReferenceSchemaContract brand = references.get("marketingBrand");
				assertNotNull(brand);
				assertReference(
					brand,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					false,
					"brand",
					false,
					"brandGroup",
					false, false
				);

				final Map<String, AttributeSchemaContract> brandAttributes = brand.getAttributes();
				assertNotNull(brandAttributes);
				assertEquals(2, brandAttributes.size());

				assertAttribute(
					brand, "market", null, null, String.class,
					false, false, false, false, false, false, false, false
				);

				assertAttribute(
					brand, "inceptionYear", null, null, Integer.class,
					false, false, false, false, false, false, false, false
				);

				final ReferenceSchemaContract licensingBrands = references.get("licensingBrands");
				assertNotNull(licensingBrands);
				assertReference(
					licensingBrands,
					"licensingBrands",
					null, null,
					Cardinality.ZERO_OR_MORE,
					false, "brand",
					false, "brandGroup",
					false, false
				);

				assertEvolutionMode(entitySchema, EvolutionMode.ADDING_CURRENCIES, EvolutionMode.ADDING_LOCALES);

				// Assert sortable attribute compounds
				assertEquals(2, entitySchema.getSortableAttributeCompounds().size());

				assertSortableAttributeCompound(
					entitySchema,
					"compoundA",
					"Compound A description",
					null,
					new Scope[]{Scope.LIVE},
					new AttributeElement[]{
						new AttributeElement(ATTRIBUTE_DCODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement(ATTRIBUTE_NAME, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					}
				);

				assertSortableAttributeCompound(
					entitySchema,
					"compoundB",
					"Compound B description",
					"Not used anymore",
					Scope.NO_SCOPE,
					new AttributeElement[] {
						new AttributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_QUANTITY, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					}
				);

				// Assert sortable attribute compound on reference 'brand'
				final ReferenceSchemaContract marketingBrand = references.get("marketingBrand");
				assertSortableAttributeCompound(
					marketingBrand,
					"compoundC",
					"Compound C description",
					null,
					new Scope[]{Scope.LIVE},
					new AttributeElement[]{
						new AttributeElement("market", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement("inceptionYear", OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					}
				);
			});
	}

	@DisplayName("Verify that class fields are analyzed and set up with defaults")
	@Test
	void shouldSetupNewSchemaByClassFields() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(FieldBasedEntity.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("FieldBasedEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertNull(entitySchema.getDescription());
				assertNull(entitySchema.getDeprecationNotice());
				assertTrue(entitySchema.isWithGeneratedPrimaryKey());
				assertTrue(entitySchema.isWithHierarchy());
				assertTrue(entitySchema.isWithPrice());
				assertTrue(entitySchema.getCurrencies().isEmpty());
				assertTrue(entitySchema.getLocales().isEmpty());
				assertEquals(5, entitySchema.getAttributes().size());

				assertAttribute(
					entitySchema, ATTRIBUTE_DCODE, null, null, String.class,
					false, false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, ATTRIBUTE_NAME, null, null, String.class,
					false, false, false, false, false, true, false, false
				);
				assertAttribute(
					entitySchema, ATTRIBUTE_EAN, null, null, String.class,
					false, false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, ATTRIBUTE_QUANTITY, null, null, BigDecimal.class,
					false, false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, "years", null, null, Integer[].class,
					false, false, false, false, false, false, false, false
				);
				assertArrayEquals(
					new Integer[]{1978, 2005, 2020},
					(Integer[]) entitySchema.getAttribute("years").orElseThrow().getDefaultValue()
				);

				final Map<String, AssociatedDataSchemaContract> associatedData = entitySchema.getAssociatedData();
				assertNotNull(associatedData);
				assertEquals(1, associatedData.size());
				assertAssociatedData(
					entitySchema, "referencedFiles", null, null,
					ComplexDataObject.class,
					false, false
				);

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(2, references.size());

				final ReferenceSchemaContract brand = references.get("marketingBrand");
				assertNotNull(brand);
				assertReference(
					brand,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					false,
					"brand",
					false,
					"brandGroup",
					false, false
				);

				final Map<String, AttributeSchemaContract> brandAttributes = brand.getAttributes();
				assertNotNull(brandAttributes);
				assertEquals(2, brandAttributes.size());

				assertAttribute(
					brand, "market", null, null, String.class,
					false, false, false, false, false, false, false, false
				);

				assertAttribute(
					brand, "inceptionYear", null, null, Integer.class,
					false, false, false, false, false, false, false, false
				);

				final ReferenceSchemaContract licensingBrands = references.get("licensingBrands");
				assertNotNull(licensingBrands);
				assertReference(
					licensingBrands,
					"licensingBrands",
					null, null,
					Cardinality.ZERO_OR_MORE,
					false, "brand",
					false, "brandGroup",
					false, false
				);

				assertEvolutionMode(entitySchema, EvolutionMode.ADDING_CURRENCIES, EvolutionMode.ADDING_LOCALES);

				// Assert sortable attribute compounds
				assertEquals(2, entitySchema.getSortableAttributeCompounds().size());

				assertSortableAttributeCompound(
					entitySchema,
					"compoundA",
					"Compound A description",
					null,
					new Scope[]{Scope.LIVE},
					new AttributeElement[]{
						new AttributeElement(ATTRIBUTE_DCODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement(ATTRIBUTE_NAME, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					}
				);

				assertSortableAttributeCompound(
					entitySchema,
					"compoundB",
					"Compound B description",
					"Not used anymore",
					Scope.NO_SCOPE,
					new AttributeElement[] {
						new AttributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_QUANTITY, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					}
				);

				// Assert sortable attribute compound on reference 'brand'
				final ReferenceSchemaContract marketingBrand = references.get("marketingBrand");
				assertSortableAttributeCompound(
					marketingBrand,
					"compoundC",
					"Compound C description",
					null,
					new Scope[]{Scope.LIVE},
					new AttributeElement[]{
						new AttributeElement("market", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement("inceptionYear", OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					}
				);
			});
	}

	@DisplayName("Verify that record components are analyzed and set up with defaults")
	@Test
	void shouldSetupNewSchemaByRecordComponents() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(RecordBasedEntity.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("RecordBasedEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertNull(entitySchema.getDescription());
				assertNull(entitySchema.getDeprecationNotice());
				assertTrue(entitySchema.isWithGeneratedPrimaryKey());
				assertTrue(entitySchema.isWithHierarchy());
				assertTrue(entitySchema.isWithPrice());
				assertTrue(entitySchema.getCurrencies().isEmpty());
				assertTrue(entitySchema.getLocales().isEmpty());
				assertEquals(5, entitySchema.getAttributes().size());

				assertAttribute(
					entitySchema, ATTRIBUTE_DCODE, null, null, String.class,
					false, false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, ATTRIBUTE_NAME, null, null, String.class,
					false, false, false, false, false, true, false, false
				);
				assertAttribute(
					entitySchema, ATTRIBUTE_EAN, null, null, String.class,
					false, false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, ATTRIBUTE_QUANTITY, null, null, BigDecimal.class,
					false, false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, "years", null, null, Integer[].class,
					false, false, false, false, false, false, false, false
				);

				final Map<String, AssociatedDataSchemaContract> associatedData = entitySchema.getAssociatedData();
				assertNotNull(associatedData);
				assertEquals(1, associatedData.size());
				assertAssociatedData(
					entitySchema, "referencedFiles", null, null,
					ComplexDataObject.class,
					false, false
				);

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(2, references.size());

				final ReferenceSchemaContract brand = references.get("marketingBrand");
				assertNotNull(brand);
				assertReference(
					brand,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					false,
					"brand",
					false,
					"brandGroup",
					false, false
				);

				final Map<String, AttributeSchemaContract> brandAttributes = brand.getAttributes();
				assertNotNull(brandAttributes);
				assertEquals(2, brandAttributes.size());

				assertAttribute(
					brand, "market", null, null, String.class,
					false, false, false, false, false, false, false, false
				);

				assertAttribute(
					brand, "inceptionYear", null, null, Integer.class,
					false, false, false, false, false, false, false, false
				);

				final ReferenceSchemaContract licensingBrands = references.get("licensingBrands");
				assertNotNull(licensingBrands);
				assertReference(
					licensingBrands,
					"licensingBrands",
					null, null,
					Cardinality.ZERO_OR_MORE,
					false, "brand",
					false, "brandGroup",
					false, false
				);

				assertEvolutionMode(entitySchema, EvolutionMode.ADDING_CURRENCIES, EvolutionMode.ADDING_LOCALES);

				// Assert sortable attribute compounds
				assertEquals(2, entitySchema.getSortableAttributeCompounds().size());

				assertSortableAttributeCompound(
					entitySchema,
					"compoundA",
					"Compound A description",
					null,
					new Scope[]{Scope.LIVE},
					new AttributeElement[]{
						new AttributeElement(ATTRIBUTE_DCODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement(ATTRIBUTE_NAME, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					}
				);

				assertSortableAttributeCompound(
					entitySchema,
					"compoundB",
					"Compound B description",
					"Not used anymore",
					Scope.NO_SCOPE,
					new AttributeElement[] {
						new AttributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_QUANTITY, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					}
				);

				// Assert sortable attribute compound on reference 'brand'
				final ReferenceSchemaContract marketingBrand = references.get("marketingBrand");
				assertSortableAttributeCompound(
					marketingBrand,
					"compoundC",
					"Compound C description",
					null,
					new Scope[]{Scope.LIVE},
					new AttributeElement[]{
						new AttributeElement("market", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement("inceptionYear", OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					}
				);
			});
	}

	@DisplayName("Verify that all interface method annotation attributes are recognized and processed")
	@Test
	void shouldSetupNewSchemaByClassGettersWithAttributesDefined() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithNonDefaults.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("CustomEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertEquals("CustomEntity description", entitySchema.getDescription());
				assertEquals("And already deprecated!", entitySchema.getDeprecationNotice());
				assertFalse(entitySchema.isWithGeneratedPrimaryKey());
				assertTrue(entitySchema.isWithHierarchy());
				assertTrue(entitySchema.isWithPrice());
				assertSetEquals(entitySchema.getLocales(), new Locale("cs", "CZ"), new Locale("en", "US"));
				assertSetEquals(entitySchema.getCurrencies(), Currency.getInstance("CZK"), Currency.getInstance("EUR"));

				assertEquals(3, entitySchema.getAttributes().size());
				assertAttribute(
					entitySchema, "customCode",
					"customCode description", "And already deprecated!",
					String.class,
					true, true, true, false, true, false, false, true
				);
				assertAttribute(
					entitySchema, "customYears",
					"customYears description", "And already deprecated!",
					Integer[].class,
					false, false, false, true, false, false, true, false
				);
				assertAttribute(
					entitySchema, "customName",
					"customName description", "And already deprecated!",
					String.class,
					true, false, false, false, false, true, false, false
				);

				final Map<String, AssociatedDataSchemaContract> associatedData = entitySchema.getAssociatedData();
				assertNotNull(associatedData);
				assertEquals(2, associatedData.size());
				assertAssociatedData(
					entitySchema, "customReferencedFiles",
					"customReferencedFiles description", "And already deprecated!",
					ComplexDataObject.class,
					false, true
				);

				assertAssociatedData(
					entitySchema, "customLocalizedTexts",
					"customLocalizedTexts description", "And already deprecated!",
					ComplexDataObject.class,
					true, false
				);

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(2, references.size());

				final ReferenceSchemaContract brand = references.get("marketingBrand");
				assertNotNull(brand);
				assertReference(
					brand,
					"marketingBrand", null, null,
					Cardinality.ZERO_OR_ONE,
					false,
					"brand",
					false,
					"brandGroup",
					false, true
				);

				final Map<String, AttributeSchemaContract> brandAttributes = brand.getAttributes();
				assertNotNull(brandAttributes);
				assertEquals(1, brandAttributes.size());

				assertAttribute(
					brand, "customMarket",
					"customMarket description", "And already deprecated!",
					String.class,
					false, false, false, true, true, false, false, true
				);

				final ReferenceSchemaContract licensingBrands = references.get("customLicensingBrand");
				assertNotNull(licensingBrands);
				assertReference(
					licensingBrands,
					"customLicensingBrand",
					"customLicensingBrand description",
					"And already deprecated!",
					Cardinality.ONE_OR_MORE,
					false, "customLicensingBrand",
					false, "customBrandGroup",
					true, true
				);

				assertEvolutionMode(entitySchema, EvolutionMode.values());
			});
	}

	@DisplayName("Verify that all class field annotation attributes are recognized and processed")
	@Test
	void shouldSetupNewSchemaByFieldsWithAttributesDefined() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(FieldBasedEntityWithNonDefaults.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("CustomEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertEquals("CustomEntity description", entitySchema.getDescription());
				assertEquals("And already deprecated!", entitySchema.getDeprecationNotice());
				assertFalse(entitySchema.isWithGeneratedPrimaryKey());
				assertTrue(entitySchema.isWithHierarchy());
				assertTrue(entitySchema.isWithPrice());
				assertSetEquals(entitySchema.getLocales(), new Locale("cs", "CZ"), new Locale("en", "US"));
				assertSetEquals(entitySchema.getCurrencies(), Currency.getInstance("CZK"), Currency.getInstance("EUR"));

				assertEquals(3, entitySchema.getAttributes().size());
				assertAttribute(
					entitySchema, "customCode",
					"customCode description", "And already deprecated!",
					String.class,
					true, true, true, false, true, false, false, true
				);
				assertAttribute(
					entitySchema, "customYears",
					"customYears description", "And already deprecated!",
					Integer[].class,
					false, false, false, true, false, false, true, false
				);
				assertArrayEquals(
					new Integer[]{1978, 2005, 2020},
					(Integer[]) entitySchema.getAttribute("customYears").orElseThrow().getDefaultValue()
				);
				assertAttribute(
					entitySchema, "customName",
					"customName description", "And already deprecated!",
					String.class,
					true, false, false, false, false, true, false, false
				);

				final Map<String, AssociatedDataSchemaContract> associatedData = entitySchema.getAssociatedData();
				assertNotNull(associatedData);
				assertEquals(2, associatedData.size());
				assertAssociatedData(
					entitySchema, "customReferencedFiles",
					"customReferencedFiles description", "And already deprecated!",
					ComplexDataObject.class,
					false, true
				);

				assertAssociatedData(
					entitySchema, "customLocalizedTexts",
					"customLocalizedTexts description", "And already deprecated!",
					ComplexDataObject.class,
					true, false
				);

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(2, references.size());

				final ReferenceSchemaContract brand = references.get("marketingBrand");
				assertNotNull(brand);
				assertReference(
					brand,
					"marketingBrand", null, null,
					Cardinality.ZERO_OR_ONE,
					false,
					"brand",
					false,
					"brandGroup",
					false, true
				);

				final Map<String, AttributeSchemaContract> brandAttributes = brand.getAttributes();
				assertNotNull(brandAttributes);
				assertEquals(1, brandAttributes.size());

				assertAttribute(
					brand, "customMarket",
					"customMarket description", "And already deprecated!",
					String.class,
					false, false, false, true, true, false, false, true
				);

				final ReferenceSchemaContract licensingBrands = references.get("customLicensingBrand");
				assertNotNull(licensingBrands);
				assertReference(
					licensingBrands,
					"customLicensingBrand",
					"customLicensingBrand description",
					"And already deprecated!",
					Cardinality.ONE_OR_MORE,
					false, "customLicensingBrand",
					false, "customBrandGroup",
					true, true
				);

				assertEvolutionMode(entitySchema, EvolutionMode.values());
			});
	}

	@DisplayName("Verify that all record components annotation attributes are recognized and processed")
	@Test
	void shouldSetupNewSchemaByRecordComponentsWithAttributesDefined() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(RecordBasedEntityWithNonDefaults.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("CustomEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertEquals("CustomEntity description", entitySchema.getDescription());
				assertEquals("And already deprecated!", entitySchema.getDeprecationNotice());
				assertFalse(entitySchema.isWithGeneratedPrimaryKey());
				assertTrue(entitySchema.isWithHierarchy());
				assertTrue(entitySchema.isWithPrice());
				assertSetEquals(entitySchema.getLocales(), new Locale("cs", "CZ"), new Locale("en", "US"));
				assertSetEquals(entitySchema.getCurrencies(), Currency.getInstance("CZK"), Currency.getInstance("EUR"));

				assertEquals(3, entitySchema.getAttributes().size());
				assertAttribute(
					entitySchema, "customCode",
					"customCode description", "And already deprecated!",
					String.class,
					true, true, true, false, true, false, false, true
				);
				assertAttribute(
					entitySchema, "customYears",
					"customYears description", "And already deprecated!",
					Integer[].class,
					false, false, false, true, false, false, true, false
				);
				assertAttribute(
					entitySchema, "customName",
					"customName description", "And already deprecated!",
					String.class,
					true, false, false, false, false, true, false, false
				);

				final Map<String, AssociatedDataSchemaContract> associatedData = entitySchema.getAssociatedData();
				assertNotNull(associatedData);
				assertEquals(2, associatedData.size());
				assertAssociatedData(
					entitySchema, "customReferencedFiles",
					"customReferencedFiles description", "And already deprecated!",
					ComplexDataObject.class,
					false, true
				);

				assertAssociatedData(
					entitySchema, "customLocalizedTexts",
					"customLocalizedTexts description", "And already deprecated!",
					ComplexDataObject.class,
					true, false
				);

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(2, references.size());

				final ReferenceSchemaContract brand = references.get("marketingBrand");
				assertNotNull(brand);
				assertReference(
					brand,
					"marketingBrand", null, null,
					Cardinality.ZERO_OR_ONE,
					false,
					"brand",
					false,
					"brandGroup",
					false, true
				);

				final Map<String, AttributeSchemaContract> brandAttributes = brand.getAttributes();
				assertNotNull(brandAttributes);
				assertEquals(1, brandAttributes.size());

				assertAttribute(
					brand, "customMarket",
					"customMarket description", "And already deprecated!",
					String.class,
					false, false, false, true, true, false, false, true
				);

				final ReferenceSchemaContract licensingBrands = references.get("customLicensingBrand");
				assertNotNull(licensingBrands);
				assertReference(
					licensingBrands,
					"customLicensingBrand",
					"customLicensingBrand description",
					"And already deprecated!",
					Cardinality.ONE_OR_MORE,
					false, "customLicensingBrand",
					false, "customBrandGroup",
					true, true
				);

				assertEvolutionMode(entitySchema, EvolutionMode.values());
			});
	}

	@DisplayName("Verify that external entities in methods are recognized and used in references")
	@Test
	void shouldSetupNewSchemaByClassGettersWithExternalReferences() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithReferencedEntity.Brand.class);
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithReferencedEntity.BrandGroup.class);
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithReferencedEntity.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("GetterBasedEntityWithReferencedEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertNull(entitySchema.getDescription());
				assertNull(entitySchema.getDeprecationNotice());
				assertTrue(entitySchema.isWithGeneratedPrimaryKey());
				assertFalse(entitySchema.isWithHierarchy());
				assertFalse(entitySchema.isWithPrice());
				assertTrue(entitySchema.getCurrencies().isEmpty());
				assertTrue(entitySchema.getLocales().isEmpty());
				assertTrue(entitySchema.getAttributes().isEmpty());
				assertTrue(entitySchema.getAssociatedData().isEmpty());

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(1, references.size());

				final ReferenceSchemaContract brand = references.get("marketingBrand");
				assertNotNull(brand);
				assertReference(
					brand,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					true,
					"Brand",
					true,
					"BrandGroup",
					false, false
				);

				assertEvolutionMode(entitySchema, EvolutionMode.values());
			});
	}

	@DisplayName("Verify that external entities in methods are recognized and used in reflected references")
	@Test
	void shouldSetupNewSchemaByClassGettersWithReflectedReferences() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithReflectedReferencedEntity.Brand.class);
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithReflectedReferencedEntity.BrandGroup.class);
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithReflectedReferencedEntity.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("GetterBasedEntityWithReflectedReferencedEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertNull(entitySchema.getDescription());
				assertNull(entitySchema.getDeprecationNotice());
				assertTrue(entitySchema.isWithGeneratedPrimaryKey());
				assertFalse(entitySchema.isWithHierarchy());
				assertFalse(entitySchema.isWithPrice());
				assertTrue(entitySchema.getCurrencies().isEmpty());
				assertTrue(entitySchema.getLocales().isEmpty());
				assertTrue(entitySchema.getAttributes().isEmpty());
				assertTrue(entitySchema.getAssociatedData().isEmpty());

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(1, references.size());

				final ReferenceSchemaContract marketingBrand = references.get("marketingBrand");
				assertInstanceOf(ReflectedReferenceSchemaContract.class, marketingBrand);
				final ReflectedReferenceSchemaContract reflectedReference = (ReflectedReferenceSchemaContract) marketingBrand;
				assertNotNull(reflectedReference);
				assertReflectedReference(
					reflectedReference,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					"Brand",
					true,
					"BrandGroup",
					false, true
				);

				assertEvolutionMode(entitySchema, EvolutionMode.values());
				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(2, attributes.size());

				assertTrue(attributes.containsKey("brandNote"));
				assertTrue(attributes.containsKey("order"));
			});
	}

	@DisplayName("Verify that external entities in fields are recognized and used in references")
	@Test
	void shouldSetupNewSchemaByClassFieldsWithExternalReferences() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(FieldBasedEntityWithReflectedReferencedEntity.Brand.class);
				session.defineEntitySchemaFromModelClass(FieldBasedEntityWithReflectedReferencedEntity.BrandGroup.class);
				session.defineEntitySchemaFromModelClass(FieldBasedEntityWithReflectedReferencedEntity.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("FieldBasedEntityWithReflectedReferencedEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertNull(entitySchema.getDescription());
				assertNull(entitySchema.getDeprecationNotice());
				assertTrue(entitySchema.isWithGeneratedPrimaryKey());
				assertFalse(entitySchema.isWithHierarchy());
				assertFalse(entitySchema.isWithPrice());
				assertTrue(entitySchema.getCurrencies().isEmpty());
				assertTrue(entitySchema.getLocales().isEmpty());
				assertTrue(entitySchema.getAttributes().isEmpty());
				assertTrue(entitySchema.getAssociatedData().isEmpty());

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(1, references.size());

				final ReferenceSchemaContract marketingBrand = references.get("marketingBrand");
				assertInstanceOf(ReflectedReferenceSchemaContract.class, marketingBrand);
				final ReflectedReferenceSchemaContract reflectedReference = (ReflectedReferenceSchemaContract) marketingBrand;
				assertNotNull(reflectedReference);
				assertReflectedReference(
					reflectedReference,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					"Brand",
					true,
					"BrandGroup",
					false, true
				);

				assertEvolutionMode(entitySchema, EvolutionMode.values());
				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(2, attributes.size());

				assertTrue(attributes.containsKey("brandNote"));
				assertTrue(attributes.containsKey("order"));
			});
	}

	@DisplayName("Verify that external entities in fields are recognized and used in reflected references")
	@Test
	void shouldSetupNewSchemaByClassFieldsWithReflectedReferences() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(FieldBasedEntityWithReferencedEntity.Brand.class);
				session.defineEntitySchemaFromModelClass(FieldBasedEntityWithReferencedEntity.BrandGroup.class);
				session.defineEntitySchemaFromModelClass(FieldBasedEntityWithReferencedEntity.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("FieldBasedEntityWithReferencedEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertNull(entitySchema.getDescription());
				assertNull(entitySchema.getDeprecationNotice());
				assertTrue(entitySchema.isWithGeneratedPrimaryKey());
				assertFalse(entitySchema.isWithHierarchy());
				assertFalse(entitySchema.isWithPrice());
				assertTrue(entitySchema.getCurrencies().isEmpty());
				assertTrue(entitySchema.getLocales().isEmpty());
				assertTrue(entitySchema.getAttributes().isEmpty());
				assertTrue(entitySchema.getAssociatedData().isEmpty());

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(1, references.size());

				final ReferenceSchemaContract brand = references.get("marketingBrand");
				assertNotNull(brand);
				assertReference(
					brand,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					true,
					"Brand",
					true,
					"BrandGroup",
					false, false
				);

				assertEvolutionMode(entitySchema, EvolutionMode.values());
			});
	}

	@DisplayName("Verify that external entities in record components are recognized and used in references")
	@Test
	void shouldSetupNewSchemaByRecordComponentsWithExternalReferences() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(RecordBasedEntityWithReferencedEntity.Brand.class);
				session.defineEntitySchemaFromModelClass(RecordBasedEntityWithReferencedEntity.BrandGroup.class);
				session.defineEntitySchemaFromModelClass(RecordBasedEntityWithReferencedEntity.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("RecordBasedEntityWithReferencedEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertNull(entitySchema.getDescription());
				assertNull(entitySchema.getDeprecationNotice());
				assertTrue(entitySchema.isWithGeneratedPrimaryKey());
				assertFalse(entitySchema.isWithHierarchy());
				assertFalse(entitySchema.isWithPrice());
				assertTrue(entitySchema.getCurrencies().isEmpty());
				assertTrue(entitySchema.getLocales().isEmpty());
				assertTrue(entitySchema.getAttributes().isEmpty());
				assertTrue(entitySchema.getAssociatedData().isEmpty());

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(1, references.size());

				final ReferenceSchemaContract brand = references.get("marketingBrand");
				assertNotNull(brand);
				assertReference(
					brand,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					true,
					"Brand",
					true,
					"BrandGroup",
					false, false
				);

				assertEvolutionMode(entitySchema, EvolutionMode.values());
			});
	}

	@DisplayName("Verify that external entities in record components are recognized and used in reflected references")
	@Test
	void shouldSetupNewSchemaByRecordComponentsWithReflectedReferences() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(RecordBasedEntityWithReflectedReferencedEntity.Brand.class);
				session.defineEntitySchemaFromModelClass(RecordBasedEntityWithReflectedReferencedEntity.BrandGroup.class);
				session.defineEntitySchemaFromModelClass(RecordBasedEntityWithReflectedReferencedEntity.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("RecordBasedEntityWithReflectedReferencedEntity").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				assertNull(entitySchema.getDescription());
				assertNull(entitySchema.getDeprecationNotice());
				assertTrue(entitySchema.isWithGeneratedPrimaryKey());
				assertFalse(entitySchema.isWithHierarchy());
				assertFalse(entitySchema.isWithPrice());
				assertTrue(entitySchema.getCurrencies().isEmpty());
				assertTrue(entitySchema.getLocales().isEmpty());
				assertTrue(entitySchema.getAttributes().isEmpty());
				assertTrue(entitySchema.getAssociatedData().isEmpty());

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(1, references.size());

				final ReferenceSchemaContract marketingBrand = references.get("marketingBrand");
				assertInstanceOf(ReflectedReferenceSchemaContract.class, marketingBrand);
				final ReflectedReferenceSchemaContract reflectedReference = (ReflectedReferenceSchemaContract) marketingBrand;
				assertNotNull(reflectedReference);
				assertReflectedReference(
					reflectedReference,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					"Brand",
					true,
					"BrandGroup",
					false, true
				);

				assertEvolutionMode(entitySchema, EvolutionMode.values());

				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(2, attributes.size());

				assertTrue(attributes.containsKey("brandNote"));
				assertTrue(attributes.containsKey("order"));
			});
	}

	@Test
	void shouldFailToSetupAttributeOnTwoPlaces() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					SchemaClassInvalidException.class,
					() -> session.defineEntitySchemaFromModelClass(EntityWithAttributeOnTwoPlaces.class)
				);
			});
	}

	@Test
	void shouldFailToSetupAssociatedDataOnTwoPlaces() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					SchemaClassInvalidException.class,
					() -> session.defineEntitySchemaFromModelClass(EntityWithAssociatedDataOnTwoPlaces.class)
				);
			});
	}

	@Test
	void shouldMapSingleReferenceTwiceWithPrimitive() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(EntityWithReferenceMappedTwice.Brand.class);
				session.defineEntitySchemaFromModelClass(EntityWithReferenceMappedTwice.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("EntityWithReferenceMappedTwice").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				final ReferenceSchemaContract marketingBrand = entitySchema.getReference("marketingBrand")
					.orElseThrow();

				assertTrue(marketingBrand.isReferencedEntityTypeManaged());
				assertEquals("Brand", marketingBrand.getReferencedEntityType());
			});
	}

	@Test
	void shouldFailToSetupReferencesOnTwoPlaces() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					SchemaClassInvalidException.class,
					() -> session.defineEntitySchemaFromModelClass(EntityWithReferenceOnTwoPlaces.class)
				);
			});
	}

	@DisplayName("Verify that ScopeReferenceSettings work correctly with getter-based entities")
	@Test
	void shouldSetupNewSchemaWithScopeReferenceSettingsForGetters() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithScopeReferenceSettings.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("GetterBasedEntityWithScopeReferenceSettings").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(3, references.size());

				// Test marketingBrand - indexed and faceted in LIVE scope only
				final ReferenceSchemaContract marketingBrand = references.get("marketingBrand");
				assertNotNull(marketingBrand);
				assertReferenceWithScopeSettings(
					marketingBrand,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					false, "brand",
					false, "brandGroup",
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					Map.of(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING,
						Scope.ARCHIVED, ReferenceIndexType.NONE
					)
				);

				// Test supplierBrands - different settings for different scopes
				final ReferenceSchemaContract supplierBrands = references.get("supplierBrands");
				assertNotNull(supplierBrands);

				assertReferenceWithScopeSettings(
					supplierBrands,
					"supplierBrands",
					null, null,
					Cardinality.ZERO_OR_MORE,
					false, "brand",
					false, "brandGroup",
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					Map.of(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
						Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
					)
				);

				// Test defaultBrand - should use defaults (LIVE scope only)
				final ReferenceSchemaContract defaultBrand = references.get("defaultBrand");
				assertNotNull(defaultBrand);
				assertReference(
					defaultBrand,
					"defaultBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					false, "brand",
					false, "brandGroup",
					true, true
				);
			});
	}

	@DisplayName("Verify that ScopeReferenceSettings work correctly with field-based entities")
	@Test
	void shouldSetupNewSchemaWithScopeReferenceSettingsForFields() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(FieldBasedEntityWithScopeReferenceSettings.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("FieldBasedEntityWithScopeReferenceSettings").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(3, references.size());

				// Test marketingBrand - indexed and faceted in LIVE scope only
				final ReferenceSchemaContract marketingBrand = references.get("marketingBrand");
				assertNotNull(marketingBrand);
				assertReferenceWithScopeSettings(
					marketingBrand,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					false, "brand",
					false, "brandGroup",
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					Map.of(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING,
						Scope.ARCHIVED, ReferenceIndexType.NONE
					)
				);

				// Test supplierBrands - different settings for different scopes
				final ReferenceSchemaContract supplierBrands = references.get("supplierBrands");
				assertNotNull(supplierBrands);
				assertReferenceWithScopeSettings(
					supplierBrands,
					"supplierBrands",
					null, null,
					Cardinality.ZERO_OR_MORE,
					false, "brand",
					false, "brandGroup",
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					Map.of(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
						Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
					)
				);

				// Test defaultBrand - should use defaults (LIVE scope only)
				final ReferenceSchemaContract defaultBrand = references.get("defaultBrand");
				assertNotNull(defaultBrand);
				assertReference(
					defaultBrand,
					"defaultBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					false, "brand",
					false, "brandGroup",
					true, true
				);
			});
	}

	@DisplayName("Verify that ScopeReferenceSettings work correctly with record-based entities")
	@Test
	void shouldSetupNewSchemaWithScopeReferenceSettingsForRecords() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(RecordBasedEntityWithScopeReferenceSettings.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("RecordBasedEntityWithScopeReferenceSettings").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(3, references.size());

				// Test marketingBrand - indexed and faceted in LIVE scope only
				final ReferenceSchemaContract marketingBrand = references.get("marketingBrand");
				assertNotNull(marketingBrand);
				assertReferenceWithScopeSettings(
					marketingBrand,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					false, "brand",
					false, "brandGroup",
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					Map.of(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING,
						Scope.ARCHIVED, ReferenceIndexType.NONE
					)
				);

				// Test supplierBrands - different settings for different scopes
				final ReferenceSchemaContract supplierBrands = references.get("supplierBrands");
				assertNotNull(supplierBrands);
				assertReferenceWithScopeSettings(
					supplierBrands,
					"supplierBrands",
					null, null,
					Cardinality.ZERO_OR_MORE,
					false, "brand",
					false, "brandGroup",
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					Map.of(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
						Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
					)
				);

				// Test defaultBrand - should use defaults (LIVE scope only)
				final ReferenceSchemaContract defaultBrand = references.get("defaultBrand");
				assertNotNull(defaultBrand);
				assertReference(
					defaultBrand,
					"defaultBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					false, "brand",
					false, "brandGroup",
					true, true
				);
			});
	}

	@DisplayName("Verify that ScopeReferenceSettings work correctly with reflected references")
	@Test
	void shouldSetupNewSchemaWithScopeReferenceSettingsForReflectedReferences() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithScopeReflectedReference.Brand.class);
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithScopeReflectedReference.BrandGroup.class);
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithScopeReflectedReference.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("GetterBasedEntityWithScopeReflectedReference").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
				assertNotNull(references);
				assertEquals(1, references.size());

				// Test marketingBrand - reflected reference with scope-specific settings
				final ReferenceSchemaContract marketingBrand = references.get("marketingBrand");
				assertInstanceOf(ReflectedReferenceSchemaContract.class, marketingBrand);
				final ReflectedReferenceSchemaContract reflectedReference = (ReflectedReferenceSchemaContract) marketingBrand;
				assertNotNull(reflectedReference);
				assertReflectedReferenceWithScopeSettings(
					reflectedReference,
					"marketingBrand",
					null, null,
					Cardinality.ZERO_OR_ONE,
					"Brand",
					true, "BrandGroup",
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					Map.of(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING,
						Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
					)
				);

				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(2, attributes.size());
				assertTrue(attributes.containsKey("brandNote"));
				assertTrue(attributes.containsKey("order"));
			});
	}

	@DisplayName("Debug simple ScopeAttributeSettings")
	@Test
	void shouldSetupSimpleScopeAttributeSettings() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(SimpleEntityWithScopeAttributeSettings.class);

				final SealedEntitySchema entitySchema = session.getEntitySchema("SimpleEntityWithScopeAttributeSettings").orElseThrow();
				assertNotNull(entitySchema);

				// Test name attribute - should be filterable in LIVE scope
				final AttributeSchemaContract nameAttribute = entitySchema.getAttribute("name").orElseThrow();
				assertTrue(nameAttribute.isFilterableInScope(Scope.LIVE));
				assertFalse(nameAttribute.isFilterableInScope(Scope.ARCHIVED));
			});
	}

	@DisplayName("Debug minimal ScopeAttributeSettings with two attributes")
	@Test
	void shouldSetupMinimalScopeAttributeSettings() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(MinimalEntityWithScopeAttributeSettings.class);

				final SealedEntitySchema entitySchema = session.getEntitySchema("MinimalEntityWithScopeAttributeSettings").orElseThrow();
				assertNotNull(entitySchema);

				// Test code attribute - regular attribute, should use defaults (not filterable)
				final AttributeSchemaContract codeAttribute = entitySchema.getAttribute("code").orElseThrow();
				assertFalse(codeAttribute.isFilterableInScope(Scope.LIVE));
				assertFalse(codeAttribute.isFilterableInScope(Scope.ARCHIVED));

				// Test name attribute - should be filterable and sortable in LIVE scope
				final AttributeSchemaContract nameAttribute = entitySchema.getAttribute("name").orElseThrow();
				assertTrue(nameAttribute.isFilterableInScope(Scope.LIVE));
				assertTrue(nameAttribute.isSortableInScope(Scope.LIVE));
				assertFalse(nameAttribute.isFilterableInScope(Scope.ARCHIVED));
				assertFalse(nameAttribute.isSortableInScope(Scope.ARCHIVED));
			});
	}

	@DisplayName("Verify that ScopeAttributeSettings work correctly with getter-based entities")
	@Test
	void shouldSetupNewSchemaWithScopeAttributeSettingsForGetters() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(GetterBasedEntityWithScopeAttributeSettings.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("GetterBasedEntityWithScopeAttributeSettings").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				// Test marketingName - filterable and sortable in LIVE scope only
				assertAttributeWithScopeSettings(
					entitySchema,
					"marketingName",
					null, null,
					String.class,
					false,
					Map.of(),
					Map.of(
						Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE,
						Scope.ARCHIVED, AttributeUniquenessType.NOT_UNIQUE
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					false, false, false
				);

				// Test productCode - different settings for different scopes
				assertAttributeWithScopeSettings(
					entitySchema,
					"productCode",
					null, null,
					String.class,
					false,
					Map.of(),
					Map.of(
						Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE,
						Scope.ARCHIVED, AttributeUniquenessType.NOT_UNIQUE
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, true
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					false, false, false
				);

				// Test defaultAttribute - should use defaults (LIVE scope only)
				assertAttribute(
					entitySchema,
					"defaultAttribute",
					null, null,
					String.class,
					false, false, false,
					true, true,
					false, false, false
				);
			});
	}

	@DisplayName("Verify that ScopeAttributeSettings work correctly with field-based entities")
	@Test
	void shouldSetupNewSchemaWithScopeAttributeSettingsForFields() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(FieldBasedEntityWithScopeAttributeSettings.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("FieldBasedEntityWithScopeAttributeSettings").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				// Test marketingName - filterable and sortable in LIVE scope only
				assertAttributeWithScopeSettings(
					entitySchema,
					"marketingName",
					null, null,
					String.class,
					false,
					Map.of(),
					Map.of(
						Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE,
						Scope.ARCHIVED, AttributeUniquenessType.NOT_UNIQUE
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					false, false, false
				);

				// Test productCode - different settings for different scopes
				assertAttributeWithScopeSettings(
					entitySchema,
					"productCode",
					null, null,
					String.class,
					false,
					Map.of(),
					Map.of(
						Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE,
						Scope.ARCHIVED, AttributeUniquenessType.NOT_UNIQUE
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, true
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					false, false, false
				);

				// Test defaultAttribute - should use defaults (LIVE scope only)
				assertAttribute(
					entitySchema,
					"defaultAttribute",
					null, null,
					String.class,
					false, false, false,
					true, true,
					false, false, false
				);
			});
	}

	@DisplayName("Verify that ScopeAttributeSettings work correctly with record-based entities")
	@Test
	void shouldSetupNewSchemaWithScopeAttributeSettingsForRecords() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(RecordBasedEntityWithScopeAttributeSettings.class);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				final SealedEntitySchema entitySchema = session.getEntitySchema("RecordBasedEntityWithScopeAttributeSettings").orElseThrow();

				assertNotNull(catalogSchema);
				assertNotNull(entitySchema);

				// Test marketingName - filterable and sortable in LIVE scope only
				assertAttributeWithScopeSettings(
					entitySchema,
					"marketingName",
					null, null,
					String.class,
					false,
					Map.of(),
					Map.of(
						Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE,
						Scope.ARCHIVED, AttributeUniquenessType.NOT_UNIQUE
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					false, false, false
				);

				// Test productCode - different settings for different scopes
				assertAttributeWithScopeSettings(
					entitySchema,
					"productCode",
					null, null,
					String.class,
					false,
					Map.of(),
					Map.of(
						Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE,
						Scope.ARCHIVED, AttributeUniquenessType.NOT_UNIQUE
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, true
					),
					Map.of(
						Scope.LIVE, true,
						Scope.ARCHIVED, false
					),
					false, false, false
				);

				// Test defaultAttribute - should use defaults (LIVE scope only)
				assertAttribute(
					entitySchema,
					"defaultAttribute",
					null, null,
					String.class,
					false, false, false,
					true, true,
					false, false, false
				);
			});
	}

}
