/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.model.*;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.test.TestFileSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link ClassSchemaAnalyzer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class ClassSchemaAnalyzerTest implements TestFileSupport {
	private Evita evita;

	@BeforeEach
	void setUp() throws IOException {
		cleanTestDirectory();
		evita = new Evita(
			EvitaConfiguration.builder()
				.storage(
					StorageOptions.builder()
						.storageDirectory(getTestDirectory())
						.build()
				)
				.build()
		);
		evita.defineCatalog(TEST_CATALOG);
	}

	@DisplayName("Verify that interface methods re analyzed and set up with defaults")
	@Test
	void shouldSetupNewSchemaByClassGetters() {
		evita.updateCatalog(
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
				assertEquals(2, entitySchema.getAttributes().size());

				assertAttribute(
					entitySchema, "code", null, null, String.class,
					false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, "years", null, null, Integer[].class,
					false, false, false, false, false, false, false
				);
				assertArrayEquals(
					new Integer[] {1978,2005,2020},
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
				assertEquals(1, brandAttributes.size());

				assertAttribute(
					brand, "market", null, null, String.class,
					false, false, false, false, false, false, false
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
			});
	}

	@DisplayName("Verify that class fields are analyzed and set up with defaults")
	@Test
	void shouldSetupNewSchemaByClassFields() {
		evita.updateCatalog(
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
				assertEquals(2, entitySchema.getAttributes().size());

				assertAttribute(
					entitySchema, "code", null, null, String.class,
					false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, "years", null, null, Integer[].class,
					false, false, false, false, false, false, false
				);
				assertArrayEquals(
					new Integer[] {1978,2005,2020},
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
				assertEquals(1, brandAttributes.size());

				assertAttribute(
					brand, "market", null, null, String.class,
					false, false, false, false, false, false, false
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
			});
	}

	@DisplayName("Verify that record components are analyzed and set up with defaults")
	@Test
	void shouldSetupNewSchemaByRecordComponents() {
		evita.updateCatalog(
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
				assertEquals(2, entitySchema.getAttributes().size());

				assertAttribute(
					entitySchema, "code", null, null, String.class,
					false, false, false, false, false, false, false
				);
				assertAttribute(
					entitySchema, "years", null, null, Integer[].class,
					false, false, false, false, false, false, false
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
				assertEquals(1, brandAttributes.size());

				assertAttribute(
					brand, "market", null, null, String.class,
					false, false, false, false, false, false, false
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
			});
	}

	@DisplayName("Verify that all interface method annotation attributes are recognized and processed")
	@Test
	void shouldSetupNewSchemaByClassGettersWithAttributesDefined() {
		evita.updateCatalog(
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
					true, true, true, false, true, false, false
				);
				assertAttribute(
					entitySchema, "customYears",
					"customYears description", "And already deprecated!",
					Integer[].class,
					false, false, false, true, false, false, true
				);
				assertAttribute(
					entitySchema, "customName",
					"customName description", "And already deprecated!",
					String.class,
					true, false, false, false, false, true, false
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
					false, false, false, true, true, false, false
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
		evita.updateCatalog(
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
					true, true, true, false, true, false, false
				);
				assertAttribute(
					entitySchema, "customYears",
					"customYears description", "And already deprecated!",
					Integer[].class,
					false, false, false, true, false, false, true
				);
				assertArrayEquals(
					new Integer[] {1978,2005,2020},
					(Integer[]) entitySchema.getAttribute("customYears").orElseThrow().getDefaultValue()
				);
				assertAttribute(
					entitySchema, "customName",
					"customName description", "And already deprecated!",
					String.class,
					true, false, false, false, false, true, false
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
					false, false, false, true, true, false, false
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
		evita.updateCatalog(
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
					true, true, true, false, true, false, false
				);
				assertAttribute(
					entitySchema, "customYears",
					"customYears description", "And already deprecated!",
					Integer[].class,
					false, false, false, true, false, false, true
				);
				assertAttribute(
					entitySchema, "customName",
					"customName description", "And already deprecated!",
					String.class,
					true, false, false, false, false, true, false
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
					false, false, false, true, true, false, false
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
		evita.updateCatalog(
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

	@DisplayName("Verify that external entities in fields are recognized and used in references")
	@Test
	void shouldSetupNewSchemaByClassFieldsWithExternalReferences() {
		evita.updateCatalog(
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
		evita.updateCatalog(
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

	@Test
	void shouldFailToSetupAttributeOnTwoPlaces() {
		evita.updateCatalog(
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
		evita.updateCatalog(
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
		evita.updateCatalog(
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
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					SchemaClassInvalidException.class,
					() -> session.defineEntitySchemaFromModelClass(EntityWithReferenceOnTwoPlaces.class)
				);
			});
	}

	private <T> void assertSetEquals(Set<T> actualValues, T... expectedValues) {
		assertEquals(expectedValues.length, actualValues.size());
		for (T expectedValue : expectedValues) {
			assertTrue(actualValues.contains(expectedValue), "Expected value not found: " + expectedValue);
		}
	}

	private void assertAttribute(
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
		boolean nullable
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
			assertTrue(((GlobalAttributeSchema)attributeSchema).isUniqueGlobally());
		}
		assertEquals(unique, attributeSchema.isUnique(), "Attribute `" + attributeName + "` is expected to be " + (unique ? "" : "not") + " unique, but it " + (unique ? "is not" : "is") + ".");
		assertEquals(filterable, attributeSchema.isFilterable(), "Attribute `" + attributeName + "` is expected to be " + (filterable ? "" : "not") + " filterable, but it " + (filterable ? "is not" : "is") + ".");
		assertEquals(sortable, attributeSchema.isSortable(), "Attribute `" + attributeName + "` is expected to be " + (sortable ? "" : "not") + " sortable, but it " + (sortable ? "is not" : "is") + ".");
		assertEquals(localized, attributeSchema.isLocalized(), "Attribute `" + attributeName + "` is expected to be " +  (localized ? "" : "not") +  "localized, but it " + (localized ? "is not" : "is") + ".");
		assertEquals(nullable, attributeSchema.isNullable(), "Attribute `" + attributeName + "` is expected to be " + (nullable ? "" : "not") + " nullable, but it " + (nullable ? "is not" : "is") + ".");
	}

	private void assertAssociatedData(
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
		assertEquals(localized, associatedData.isLocalized(), "Associated data `" + associatedDataName + "` is expected to be " +  (localized ? "" : "not") +  "localized, but it " + (localized ? "is not" : "is") + ".");
		assertEquals(nullable, associatedData.isNullable(), "Associated data `" + associatedDataName + "` is expected to be " + (nullable ? "" : "not") + " nullable, but it " + (nullable ? "is not" : "is") + ".");
	}

	private void assertReference(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecation,
		@Nonnull Cardinality cardinality,
		boolean entityTypeRelatesToEntity,
		@Nonnull String entityType,
		boolean groupTypeRelatesToEntity,
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
		assertEquals(entityTypeRelatesToEntity, referenceSchema.isReferencedEntityTypeManaged());
		assertEquals(groupEntityType, referenceSchema.getReferencedGroupType());
		assertEquals(groupTypeRelatesToEntity, referenceSchema.isReferencedGroupTypeManaged());
		assertEquals(faceted, referenceSchema.isFaceted(), "Attribute `" + name + "` is expected to be " +  (faceted ? "" : "not") + " faceted, but it " + (faceted ? "is not" : "is") + ".");
		assertEquals(indexed, referenceSchema.isFilterable(), "Attribute `" + name + "` is expected to be " +  (indexed ? "" : "not") + " indexed, but it " + (indexed ? "is not" : "is") + ".");
	}

	private void assertEvolutionMode(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EvolutionMode... expectedEvolutionModes
	) {
		final Set<EvolutionMode> evolutionMode = entitySchema.getEvolutionMode();
		assertEquals(expectedEvolutionModes.length, evolutionMode.size());
		for (EvolutionMode expectedEvolutionMode : expectedEvolutionModes) {
			assertTrue(evolutionMode.contains(expectedEvolutionMode));
		}
	}

}