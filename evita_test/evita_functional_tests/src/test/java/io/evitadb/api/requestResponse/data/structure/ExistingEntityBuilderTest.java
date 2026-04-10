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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.exception.InvalidDataTypeMutationException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.function.Functions;
import io.evitadb.test.generator.DataGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.requestResponse.data.structure.InitialEntityBuilderTest.assertCardinality;
import static io.evitadb.test.Entities.BRAND;
import static io.evitadb.test.Entities.CATEGORY;
import static io.evitadb.test.Entities.PARAMETER;
import static io.evitadb.test.Entities.STORE;
import static java.util.OptionalInt.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link ExistingEntityBuilder}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ExistingEntityBuilder")
class ExistingEntityBuilderTest extends AbstractBuilderTest {
	private static final Currency CZK = Currency.getInstance("CZK");
	private static final Currency EUR = Currency.getInstance("EUR");
	private static final String SORTABLE_ATTRIBUTE = "toSort";
	private static final String BRAND_TYPE = "BRAND";
	private static final String ATTRIBUTE_DISCRIMINATOR = "discriminator";
	private static final String ATTRIBUTE_UPDATED = "updated";
	private static final String BRAND_PRIORITY = "brandPriority";
	private static final String CATEGORY_PRIORITY = "categoryPriority";
	private Entity initialEntity;
	private ExistingEntityBuilder builder;

	public static void assertPrice(
		@Nonnull PricesContract updatedInstance,
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		boolean indexed
	) {
		final PriceContract price = updatedInstance.getPrice(priceId, priceList, currency).orElseGet(
			() -> fail("Price not found!"));
		assertEquals(priceWithoutTax, price.priceWithoutTax());
		assertEquals(taxRate, price.taxRate());
		assertEquals(priceWithTax, price.priceWithTax());
		assertEquals(indexed, price.indexed());
	}

	private static void assertAllCategoriesHasUpdatedAttributeButNothingElseHas(
		@Nonnull ExistingEntityBuilder builder
	) {
		for (ReferenceContract reference : builder.getReferences()) {
			if (reference.getReferenceName().equals(CATEGORY)) {
				assertNotNull(reference.getAttribute(ATTRIBUTE_UPDATED));
			} else {
				assertThrows(
					AttributeNotFoundException.class,
					() -> reference.getAttribute(ATTRIBUTE_UPDATED)
				);
			}
		}
		final Entity instance = builder.toInstance();
		for (ReferenceContract reference : instance.getReferences()) {
			if (reference.getReferenceName().equals(CATEGORY)) {
				assertNotNull(reference.getAttribute(ATTRIBUTE_UPDATED));
			} else {
				assertThrows(
					AttributeNotFoundException.class,
					() -> reference.getAttribute(ATTRIBUTE_UPDATED)
				);
			}
		}
	}

	private static void checkCollectionBrands(
		@Nonnull Collection<ReferenceContract> references,
		int... expectedPks
	) {
		assertEquals(expectedPks.length, references.size());

		int i = 0;
		for (ReferenceContract ref : references) {
			assertEquals(expectedPks[i++], ref.getReferencedPrimaryKey());
		}
	}

	@BeforeEach
	void setUp() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAttribute("string", String.class, whichIs -> whichIs.filterable().withDefaultValue("defaultValue"))
			.withReferenceToEntity(
				PARAMETER,
				PARAMETER,
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				r -> r.withAttribute(ATTRIBUTE_DISCRIMINATOR, String.class, AttributeSchemaEditor::representative)
			)
			.withReferenceToEntity(
				STORE,
				STORE,
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				r -> r.withAttribute(ATTRIBUTE_DISCRIMINATOR, String.class, AttributeSchemaEditor::representative)
			)
			.toInstance();

		final SealedEntity sealedEntity = new InitialEntityBuilder(schema, 1)
			.setParent(5)
			.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE)
			.setPrice(1, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
			.setPrice(2, "reference", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
			.setPrice(3, "basic", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
			.setPrice(4, "reference", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
			.setAttribute("string", "string")
			.setAttribute("int", Locale.ENGLISH, 1)
			.setAttribute("bigDecimal", Locale.ENGLISH, BigDecimal.ONE)
			.setAssociatedData("string", "string")
			.setAssociatedData("int", Locale.ENGLISH, 1)
			.setAssociatedData("bigDecimal", Locale.ENGLISH, BigDecimal.ONE)
			.toInstance();
		this.initialEntity = (Entity) sealedEntity;
		this.builder = new ExistingEntityBuilder(this.initialEntity);
	}

	@Nonnull
	private Entity setupEntityWithBrand() {
		this.builder.setReference(
			BRAND_TYPE,
			BRAND_TYPE,
			Cardinality.ZERO_OR_ONE,
			1,
			whichIs -> whichIs.setGroup("Whatever", 8)
			                  .setAttribute("brandName", "Brand A")
			                  .setAttribute("brandCode", "007")
			                  .setAttribute("brandCountry", "CZ")
		);

		final EntityMutation entityMutation = this.builder.toMutation().orElseThrow();
		final Collection<? extends LocalMutation<?, ?>> localMutations = entityMutation.getLocalMutations();
		assertEquals(5, localMutations.size());

		final SealedEntitySchema sealedEntitySchema = new EntitySchemaDecorator(
			() -> CATALOG_SCHEMA, (EntitySchema) this.initialEntity.getSchema());
		final LocalEntitySchemaMutation[] schemaMutations = EntityMutation.verifyOrEvolveSchema(
			CATALOG_SCHEMA,
			sealedEntitySchema,
			localMutations
		).orElseThrow();

		final EntitySchemaContract updatedSchema = sealedEntitySchema
			.withMutations(schemaMutations)
			.toInstance();

		return entityMutation.mutate(updatedSchema, this.initialEntity);
	}

	@Nonnull
	private Entity buildInitialEntityWithDuplicatedReferences() {
		final ExistingEntityBuilder eb = new ExistingEntityBuilder(this.initialEntity);
		// initial: 1x BRAND
		eb.setReference(BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1);
		// initial: 2x CATEGORY (different PKs)
		eb.setReference(CATEGORY, CATEGORY, Cardinality.ZERO_OR_MORE, 10);
		eb.setReference(CATEGORY, CATEGORY, Cardinality.ZERO_OR_MORE, 11);
		// initial: 2x PARAMETER (same PKs)
		eb.setOrUpdateReference(
			PARAMETER, 100, ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "A")
		);
		eb.setOrUpdateReference(
			PARAMETER, 100, ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "B")
		);
		// initial: 3x STORE (two share same referencedEntityPrimaryKey)
		eb.setOrUpdateReference(STORE, 1000, ref -> false, Functions.noOpConsumer());
		eb.setOrUpdateReference(
			STORE, 1001, ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "A")
		);
		eb.setOrUpdateReference(
			STORE, 1001, ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "B")
		);
		final Entity resultInstance = eb.toInstance();

		assertEquals(1, resultInstance.getReferences(BRAND).size());
		assertEquals(2, resultInstance.getReferences(CATEGORY).size());
		assertEquals(2, resultInstance.getReferences(PARAMETER).size());
		assertEquals(3, resultInstance.getReferences(STORE).size());

		return resultInstance;
	}

	@Nested
	@DisplayName("Parent management")
	class ParentManagementTest {

		@Test
		@DisplayName("should skip mutations that mean no change")
		void shouldSkipMutationsThatMeansNoChange() {
			ExistingEntityBuilderTest.this.builder
				.setParent(5)
				.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE)
				.setPrice(1, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(2, "reference", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
				.setPrice(3, "basic", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(4, "reference", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
				.setAttribute("string", "string")
				.setAttribute("int", Locale.ENGLISH, 1)
				.setAttribute("bigDecimal", Locale.ENGLISH, BigDecimal.ONE)
				.setAssociatedData("string", "string")
				.setAssociatedData("int", Locale.ENGLISH, 1)
				.setAssociatedData("bigDecimal", Locale.ENGLISH, BigDecimal.ONE);

			assertTrue(ExistingEntityBuilderTest.this.builder.toMutation().isEmpty());
		}

		@Test
		@DisplayName("should remove parent")
		void shouldRemoveParent() {
			assertFalse(ExistingEntityBuilderTest.this.builder.getParentEntity().isEmpty());
			ExistingEntityBuilderTest.this.builder.removeParent();
			assertTrue(ExistingEntityBuilderTest.this.builder.getParentEntity().isEmpty());

			final Entity updatedEntity = ExistingEntityBuilderTest.this.builder
				.toMutation()
				.map(it -> it.mutate(
					ExistingEntityBuilderTest.this.initialEntity.getSchema(),
					ExistingEntityBuilderTest.this.initialEntity
				))
				.orElse(ExistingEntityBuilderTest.this.initialEntity);

			assertFalse(updatedEntity.parentAvailable());
			assertThrows(EntityIsNotHierarchicalException.class, updatedEntity::getParent);
			assertThrows(EntityIsNotHierarchicalException.class, updatedEntity::getParentEntity);
			assertEquals(ExistingEntityBuilderTest.this.initialEntity.version() + 1, updatedEntity.version());
		}

		@Test
		@DisplayName("should overwrite parent")
		void shouldOverwriteParent() {
			ExistingEntityBuilderTest.this.builder.setParent(78);
			assertEquals(
				Optional.of(new EntityReferenceWithParent(
					ExistingEntityBuilderTest.this.initialEntity.getSchema().getName(), 78, null)),
				ExistingEntityBuilderTest.this.builder.getParentEntity()
			);

			final Entity updatedEntity = ExistingEntityBuilderTest.this.builder.toMutation().orElseThrow().mutate(
				ExistingEntityBuilderTest.this.initialEntity.getSchema(),
				ExistingEntityBuilderTest.this.initialEntity);
			assertEquals(ExistingEntityBuilderTest.this.initialEntity.version() + 1, updatedEntity.version());
			assertEquals(of(78), updatedEntity.getParent());
		}

		@Test
		@DisplayName("should set new parent")
		void shouldSetNewParent() {
			final SealedEntity updatedInstance = ExistingEntityBuilderTest.this.builder
				.setParent(2)
				.toInstance();

			assertEquals(2, updatedInstance.getParentEntity().orElseThrow().getPrimaryKey());
		}

		@Test
		@DisplayName("should get parent after modification")
		void shouldGetParentAfterModification() {
			ExistingEntityBuilderTest.this.builder.setParent(42);

			assertEquals(
				42,
				ExistingEntityBuilderTest.this.builder.getParentEntity().orElseThrow().getPrimaryKey()
			);

			final SealedEntity updatedInstance = ExistingEntityBuilderTest.this.builder.toInstance();
			assertEquals(42, updatedInstance.getParentEntity().orElseThrow().getPrimaryKey());
		}
	}

	@Nested
	@DisplayName("Attribute management")
	class AttributeManagementTest {

		@Test
		@DisplayName("should add new attributes")
		void shouldAddNewAttributes() {
			final SealedEntity updatedInstance = ExistingEntityBuilderTest.this.builder
				.setAttribute("newAttribute", "someValue")
				.toInstance();

			assertEquals("someValue", updatedInstance.getAttribute("newAttribute"));
		}

		@Test
		@DisplayName("should add new attributes via mutations in constructor")
		void shouldAddNewAttributesViaMutationsInConstructor() {
			final SealedEntity brand = new ExistingEntityBuilder(
				ExistingEntityBuilderTest.this.initialEntity,
				Arrays.asList(
					new UpsertAttributeMutation("code", "siemens"),
					new UpsertAttributeMutation("name", Locale.ENGLISH, "Siemens"),
					new UpsertAttributeMutation("logo", "https://www.siemens.com/logo.png"),
					new UpsertAttributeMutation("productCount", 1),
					new UpsertPriceMutation(
						new PriceKey(1, "basic", Currency.getInstance("CZK")),
						BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true
					)
				)
			).toInstance();
			assertEquals("siemens", brand.getAttribute("code"));
			assertEquals("Siemens", brand.getAttribute("name", Locale.ENGLISH));
			assertEquals("https://www.siemens.com/logo.png", brand.getAttribute("logo"));
			assertEquals(Integer.valueOf(1), brand.getAttribute("productCount"));
			assertPrice(brand, 1, "basic", Currency.getInstance("CZK"), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true);
		}

		@Test
		@DisplayName("should set default value instead of removing")
		void shouldSetDefaultValueInsteadOfRemovingAttribute() {
			final SealedEntity updatedInstance = ExistingEntityBuilderTest.this.builder
				.removeAttribute("string")
				.toInstance();

			assertEquals("defaultValue", updatedInstance.getAttribute("string"));
		}

		@Test
		@DisplayName("should generate no mutation for default value removal")
		void shouldSetDefaultValueInsteadOfRemovingAttributeAndGenerateNoMutation() {
			final SealedEntity updatedInstance = ExistingEntityBuilderTest.this.builder
				.removeAttribute("string")
				.toInstance();

			assertEquals("defaultValue", updatedInstance.getAttribute("string"));

			// no mutation is generated, because attribute is set to default value
			// and will remain the same even if removal is applied
			assertTrue(
				updatedInstance
					.openForWrite()
					.removeAttribute("string")
					.toMutation().isEmpty()
			);
		}

		@Test
		@DisplayName("should fail to add array as sortable attribute")
		void shouldFailToAddArrayAsSortableAttribute() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA,
				EntitySchema._internalBuild("product")
			)
				.withAttribute(SORTABLE_ATTRIBUTE, String.class, AttributeSchemaEditor::sortable)
				.toInstance();

			final ExistingEntityBuilder existingEntityBuilder = new ExistingEntityBuilder(
				new InitialEntityBuilder(schema).toInstance());
			assertThrows(
				IllegalArgumentException.class,
				() -> existingEntityBuilder.setAttribute(SORTABLE_ATTRIBUTE, new String[]{"abc", "def"})
			);
		}

		@Test
		@DisplayName("should return attribute locales after modification")
		void shouldReturnAttributeLocalesAfterModification() {
			ExistingEntityBuilderTest.this.builder.setAttribute("newLocalized", Locale.GERMAN, "Hallo");

			final SealedEntity updatedInstance = ExistingEntityBuilderTest.this.builder.toInstance();
			assertTrue(updatedInstance.getAllLocales().contains(Locale.GERMAN));
			assertTrue(updatedInstance.getAllLocales().contains(Locale.ENGLISH));
		}
	}

	@Nested
	@DisplayName("Associated data management")
	class AssociatedDataManagementTest {

		@Test
		@DisplayName("should add new associated data")
		void shouldAddNewAssociatedData() {
			final SealedEntity updatedInstance = ExistingEntityBuilderTest.this.builder
				.setAssociatedData("newAttribute", "someValue")
				.toInstance();

			assertEquals("someValue", updatedInstance.getAssociatedData("newAttribute"));
		}

		@Test
		@DisplayName("should return associated data keys after modification")
		void shouldReturnAssociatedDataKeysAfterModification() {
			ExistingEntityBuilderTest.this.builder.setAssociatedData("extra", "value");

			final SealedEntity updatedInstance = ExistingEntityBuilderTest.this.builder.toInstance();
			assertTrue(
				updatedInstance.getAssociatedDataKeys()
					.contains(new AssociatedDataKey("extra"))
			);
		}
	}

	@Nested
	@DisplayName("Price management")
	class PriceManagementTest {

		@Test
		@DisplayName("should overwrite prices")
		void shouldOverwritePrices() {
			final SealedEntity updatedInstance = ExistingEntityBuilderTest.this.builder
				.setPrice(1, "basic", CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
				.removePrice(2, "reference", CZK)
				.setPrice(5, "vip", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.toInstance();

			assertEquals(5, updatedInstance.getPrices().size());
			assertTrue(updatedInstance.getPrice(2, "reference", CZK).map(Droppable::dropped).orElse(false));
			assertPrice(updatedInstance, 1, "basic", CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true);
			assertPrice(updatedInstance, 5, "vip", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true);
		}

		@Test
		@DisplayName("should remove price inner record handling")
		void shouldRemovePriceInnerRecordHandling() {
			ExistingEntityBuilderTest.this.builder.removePriceInnerRecordHandling();
			assertEquals(PriceInnerRecordHandling.NONE, ExistingEntityBuilderTest.this.builder.getPriceInnerRecordHandling());

			final Entity updatedEntity = ExistingEntityBuilderTest.this.builder.toMutation().orElseThrow().mutate(
				ExistingEntityBuilderTest.this.initialEntity.getSchema(),
				ExistingEntityBuilderTest.this.initialEntity);
			assertEquals(PriceInnerRecordHandling.NONE, updatedEntity.getPriceInnerRecordHandling());
		}
	}

	@Nested
	@DisplayName("Reference management")
	class ReferenceManagementTest {

		@Test
		@DisplayName("should define reference group")
		void shouldDefineReferenceGroup() {
			final Entity updatedEntity = setupEntityWithBrand();
			final ReferenceContract reference = updatedEntity.getReference(BRAND_TYPE, 1).orElseThrow();
			assertEquals(new GroupEntityReference("Whatever", 8, 1, false), reference.getGroup().orElse(null));
		}

		@Test
		@DisplayName("should set new reference")
		void shouldSetNewReference() {
			final SealedEntity updatedInstance = ExistingEntityBuilderTest.this.builder
				.setReference(
					"stock", "stock", Cardinality.ZERO_OR_MORE, 2,
					whichIs -> whichIs.setAttribute("newAttribute", "someValue")
				)
				.toInstance();

			final Collection<ReferenceContract> references = updatedInstance.getReferences("stock");
			assertEquals(1, references.size());

			final ReferenceContract theStockReference = references.stream().iterator().next();
			assertNotNull(theStockReference);
			assertEquals("someValue", theStockReference.getAttribute("newAttribute"));
		}

		@Test
		@DisplayName("should remove added reference")
		void shouldRemoveAddedReference() {
			final Entity entityWithBrand = setupEntityWithBrand();

			final SealedEntity updatedInstance = new ExistingEntityBuilder(entityWithBrand)
				.setReference(
					BRAND_TYPE, 2,
					whichIs -> whichIs.setAttribute("newAttribute", "someValue")
				)
				.removeReference(BRAND_TYPE, 2)
				.toInstance();

			assertEquals(1, updatedInstance.getReferences(BRAND_TYPE).size());
		}

		@Test
		@DisplayName("should remove existing reference and add again")
		void shouldRemoveExistingReferenceAndAddAgain() {
			final Entity entityWithBrand = setupEntityWithBrand();

			final EntityBuilder entityBuilder = new ExistingEntityBuilder(entityWithBrand)
				.removeReference(BRAND_TYPE, 1)
				.setReference(
					BRAND_TYPE, 1,
					whichIs -> whichIs
						.setGroup("Whatever", 8)
						.setAttribute("brandName", "Brand A")
						.setAttribute("brandCode", "008")
						.setAttribute("newAttribute", "someValue")
				);

			assertEquals(
				3, entityBuilder.toMutation().orElseThrow().getLocalMutations().size()
			);

			final SealedEntity updatedInstance = entityBuilder.toInstance();
			assertEquals(1, updatedInstance.getReferences(BRAND_TYPE).size());

			updatedInstance.getReference(BRAND_TYPE, 1).ifPresent(reference -> {
				assertEquals(
					"Whatever", reference.getGroup()
					                     .filter(Droppable::exists)
					                     .map(GroupEntityReference::getType)
					                     .orElse(null)
				);
				assertEquals(8, reference.getGroup().map(GroupEntityReference::getPrimaryKey).orElse(null));
				assertEquals("someValue", reference.getAttribute("newAttribute"));
				assertEquals("Brand A", reference.getAttribute("brandName"));
				assertEquals("008", reference.getAttribute("brandCode"));
				assertTrue(reference.getAttributeValue("brandCountry").filter(Droppable::exists).isEmpty());
			});
		}

		@Test
		@DisplayName("should remove existing reference and add again changing group")
		void shouldRemoveExistingReferenceAndAddAgainChangingGroup() {
			final Entity entityWithBrand = setupEntityWithBrand();

			final EntityBuilder entityBuilder = new ExistingEntityBuilder(entityWithBrand)
				.removeReference(BRAND_TYPE, 1)
				.setReference(
					BRAND_TYPE, 1,
					whichIs -> whichIs
						.setGroup("Whatever", 9)
						.setAttribute("brandName", "Brand A")
						.setAttribute("brandCode", "008")
						.setAttribute("newAttribute", "someValue")
				);

			assertEquals(
				4, entityBuilder.toMutation().orElseThrow().getLocalMutations().size()
			);

			final SealedEntity updatedInstance = entityBuilder.toInstance();
			assertEquals(1, updatedInstance.getReferences(BRAND_TYPE).size());

			updatedInstance.getReference(BRAND_TYPE, 1)
				.ifPresent(
					reference -> {
						assertEquals(
							"Whatever",
							reference.getGroup()
								.filter(Droppable::exists)
								.map(GroupEntityReference::getType)
								.orElse(null)
						);
						assertEquals(9, reference.getGroup().map(GroupEntityReference::getPrimaryKey).orElse(null));
						assertEquals("someValue", reference.getAttribute("newAttribute"));
						assertEquals("Brand A", reference.getAttribute("brandName"));
						assertEquals("008", reference.getAttribute("brandCode"));
						assertTrue(reference.getAttributeValue("brandCountry").filter(Droppable::exists).isEmpty());
					});
		}

		@Test
		@DisplayName("should remove existing reference and add again removing group")
		void shouldRemoveExistingReferenceAndAddAgainRemovingGroup() {
			final Entity entityWithBrand = setupEntityWithBrand();

			final EntityBuilder entityBuilder = new ExistingEntityBuilder(entityWithBrand)
				.removeReference(BRAND_TYPE, 1)
				.setReference(
					BRAND_TYPE, 1,
					whichIs -> whichIs
						.removeGroup()
						.setAttribute("brandName", "Brand A")
						.removeAttribute("brandCode")
						.setAttribute("newAttribute", "someValue")
						.removeAttribute("newAttribute")
				);

			assertEquals(
				3, entityBuilder.toMutation().orElseThrow().getLocalMutations().size()
			);

			final SealedEntity updatedInstance = entityBuilder.toInstance();
			assertEquals(1, updatedInstance.getReferences(BRAND_TYPE).size());

			updatedInstance.getReference(BRAND_TYPE, 1).ifPresent(reference -> {
				assertTrue(reference.getGroup().filter(Droppable::exists).isEmpty());
				assertEquals("Brand A", reference.getAttribute("brandName"));
				assertTrue(reference.getAttributeValue("brandCode").filter(Droppable::exists).isEmpty());
				assertTrue(reference.getAttributeValue("brandCountry").filter(Droppable::exists).isEmpty());
			});
		}

		@Test
		@DisplayName("should get references handle removals updates and conflicts")
		void shouldGetReferencesHandleRemovalsUpdatesAndConflicts() {
			final Entity entityWithRefs = buildInitialEntityWithDuplicatedReferences();
			final ExistingEntityBuilder eb = new ExistingEntityBuilder(entityWithRefs);

			eb.updateReferences(
				ref -> ref.getReferenceName().equals(CATEGORY),
				ref -> ref.setAttribute(ATTRIBUTE_UPDATED, OffsetDateTime.now())
			);

			assertAllCategoriesHasUpdatedAttributeButNothingElseHas(eb);

			// we can set attribute this way, because there are not duplicates
			eb.setReference(BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_UPDATED, OffsetDateTime.now()));

			// but we cannot do that on references that allow duplicates
			assertThrows(
				ReferenceAllowsDuplicatesException.class,
				() -> eb.setReference(
					PARAMETER, 100, whichIs -> whichIs.setAttribute(ATTRIBUTE_UPDATED, OffsetDateTime.now()))
			);
			assertThrows(
				ReferenceAllowsDuplicatesException.class,
				() -> eb.setReference(
					STORE, 1000, whichIs -> whichIs.setAttribute(ATTRIBUTE_UPDATED, OffsetDateTime.now()))
			);

			final Set<String> expectedReferences = Set.of(BRAND, CATEGORY, PARAMETER, STORE);
			assertEquals(expectedReferences, eb.getReferenceNames());
			assertEquals(expectedReferences, eb.toInstance().getReferenceNames());
		}

		@Test
		@DisplayName("reference builders internal state must be distinguished for insertion and update")
		void shouldCorrectlyInitializeReferenceBuilder() {
			// Setup schema with BRAND (0..1) and CATEGORY (0..*:N) references
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA,
				PRODUCT_SCHEMA
			)
				.withoutGeneratedPrimaryKey()
				.withReferenceToEntity(
					BRAND, BRAND, Cardinality.ZERO_OR_ONE,
					ref -> ref.withAttribute(
						BRAND_PRIORITY, Long.class,
						thatIs -> thatIs.nullable().representative()
					).withGroupType(STORE)
				)
				.withReferenceToEntity(
					CATEGORY, CATEGORY, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
					ref -> ref.withAttribute(
						CATEGORY_PRIORITY, Long.class,
						thatIs -> thatIs.nullable().representative()
					).withGroupType(STORE)
				)
				.verifySchemaStrictly()
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setReference(
					BRAND, 1,
					rb -> rb.setAttribute(BRAND_PRIORITY, 10L).setGroup(STORE, 1000)
				)
				.setOrUpdateReference(
					CATEGORY, 1, ref -> false,
					rb -> {
						rb.setAttribute(CATEGORY_PRIORITY, 10L);
						rb.setGroup(STORE, 1000);
					}
				)
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			assertInstanceOf(ExistingEntityBuilder.class, builder);

			builder.setReference(
				BRAND, 1,
				rb -> {
					assertNull(rb.getAttribute(BRAND_PRIORITY, Long.class));
					assertTrue(rb.getGroup().isEmpty());
					rb.setAttribute(BRAND_PRIORITY, 20L);
					rb.setGroup(STORE, 2000);
					assertEquals(20L, rb.getAttribute(BRAND_PRIORITY, Long.class));
					assertEquals(2000, rb.getGroup().orElseThrow().getPrimaryKey());
				}
			);

			builder.setOrUpdateReference(
				CATEGORY, 1, ref -> false,
				rb -> {
					assertNull(rb.getAttribute(CATEGORY_PRIORITY));
					assertTrue(rb.getGroup().isEmpty());
					rb.setAttribute(CATEGORY_PRIORITY, 100L);
					rb.setGroup(STORE, 10000);
					assertEquals(100L, rb.getAttribute(CATEGORY_PRIORITY, Long.class));
					assertEquals(10000, rb.getGroup().orElseThrow().getPrimaryKey());
				}
			);

			builder.setOrUpdateReference(
				CATEGORY, 1,
				ref -> ref.getAttribute(CATEGORY_PRIORITY, Long.class) == 10L,
				rb -> {
					assertEquals(10L, rb.getAttribute(CATEGORY_PRIORITY, Long.class));
					assertEquals(new GroupEntityReference(STORE, 1000, 1, false), rb.getGroup().orElse(null));
					rb.setAttribute(CATEGORY_PRIORITY, 200L);
					rb.setGroup(STORE, 20000);
					assertEquals(200L, rb.getAttribute(CATEGORY_PRIORITY, Long.class));
					assertEquals(20000, rb.getGroup().orElseThrow().getPrimaryKey());
				}
			);

			builder.setOrUpdateReference(
				CATEGORY, 1, ref -> false,
				rb -> {
					assertNull(rb.getAttribute(CATEGORY_PRIORITY));
					assertTrue(rb.getGroup().isEmpty());
					rb.setAttribute(CATEGORY_PRIORITY, 110L);
					rb.setGroup(STORE, 11000);
					assertEquals(110L, rb.getAttribute(CATEGORY_PRIORITY, Long.class));
					assertEquals(11000, rb.getGroup().orElseThrow().getPrimaryKey());
				}
			);

			builder.setOrUpdateReference(
				CATEGORY, 1,
				ref -> ref.getAttribute(CATEGORY_PRIORITY, Long.class) == 200L,
				rb -> {
					assertEquals(200L, rb.getAttribute(CATEGORY_PRIORITY, Long.class));
					assertEquals(20000, rb.getGroup().orElseThrow().getPrimaryKey());
					rb.setAttribute(CATEGORY_PRIORITY, 210L);
					rb.setGroup(STORE, 21000);
					assertEquals(210L, rb.getAttribute(CATEGORY_PRIORITY, Long.class));
					assertEquals(21000, rb.getGroup().orElseThrow().getPrimaryKey());
				}
			);

			builder.setOrUpdateReference(
				CATEGORY, 1,
				ref -> ref.getAttribute(CATEGORY_PRIORITY, Long.class) == 110L,
				rb -> {
					assertEquals(110L, rb.getAttribute(CATEGORY_PRIORITY, Long.class));
					assertEquals(11000, rb.getGroup().orElseThrow().getPrimaryKey());
					rb.setAttribute(CATEGORY_PRIORITY, 120L);
					rb.setGroup(STORE, 12000);
					assertEquals(120L, rb.getAttribute(CATEGORY_PRIORITY, Long.class));
					assertEquals(12000, rb.getGroup().orElseThrow().getPrimaryKey());
				}
			);

			final SealedEntity builtInstance = builder.toInstance();

			final ReferenceContract brandReference = builtInstance.getReference(BRAND, 1).orElseThrow();
			assertEquals(20L, brandReference.getAttribute(BRAND_PRIORITY, Long.class));
			assertEquals(2000, brandReference.getGroup().orElseThrow().getPrimaryKey());

			final List<ReferenceContract> categoryReferences = builtInstance.getReferences(CATEGORY, 1);
			assertEquals(3, categoryReferences.size());

			final Set<Long> categoryPriorities = categoryReferences.stream()
				.map(r -> r.getAttribute(CATEGORY_PRIORITY, Long.class))
				.collect(Collectors.toSet());
			assertTrue(categoryPriorities.contains(100L));
			assertTrue(categoryPriorities.contains(120L));
			assertTrue(categoryPriorities.contains(210L));

			final Set<Integer> categoryGroups = categoryReferences.stream()
				.map(r -> r.getGroup().orElseThrow().getPrimaryKey())
				.collect(Collectors.toSet());
			assertEquals(3, categoryGroups.size());
			assertTrue(categoryGroups.contains(10000));
			assertTrue(categoryGroups.contains(12000));
			assertTrue(categoryGroups.contains(21000));
		}

		@Test
		@DisplayName("should get references by name")
		void shouldGetReferencesByName() {
			final Entity entityWithRefs = buildInitialEntityWithDuplicatedReferences();
			final ExistingEntityBuilder eb = new ExistingEntityBuilder(entityWithRefs);

			assertEquals(1, eb.getReferences(BRAND).size());
			assertEquals(1, eb.toInstance().getReferences(BRAND).size());
			assertEquals(2, eb.getReferences(CATEGORY).size());
			assertEquals(2, eb.toInstance().getReferences(CATEGORY).size());
			assertEquals(2, eb.getReferences(PARAMETER).size());
			assertEquals(2, eb.toInstance().getReferences(PARAMETER).size());
			assertEquals(3, eb.getReferences(STORE).size());

			eb.setReference(BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_UPDATED, OffsetDateTime.now()));
			eb.setReference(CATEGORY, 10, whichIs -> whichIs.setGroup(STORE, 15));

			assertEquals(1, eb.getReferences(BRAND).size());
			assertEquals(1, eb.toInstance().getReferences(BRAND).size());
			assertEquals(2, eb.getReferences(CATEGORY).size());
			assertEquals(2, eb.toInstance().getReferences(CATEGORY).size());
			assertEquals(2, eb.getReferences(PARAMETER).size());
			assertEquals(2, eb.toInstance().getReferences(PARAMETER).size());
			assertEquals(3, eb.getReferences(STORE).size());

			eb.setReference(BRAND, 2);
			eb.setReference(CATEGORY, 12);
			eb.setOrUpdateReference(PARAMETER, 101, ref -> false, whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "C"));
			eb.setOrUpdateReference(STORE, 1002, ref -> false, Functions.noOpConsumer());

			assertEquals(2, eb.getReferences(BRAND).size());
			assertEquals(2, eb.toInstance().getReferences(BRAND).size());
			assertEquals(3, eb.getReferences(CATEGORY).size());
			assertEquals(3, eb.toInstance().getReferences(CATEGORY).size());
			assertEquals(3, eb.getReferences(PARAMETER).size());
			assertEquals(3, eb.toInstance().getReferences(PARAMETER).size());
			assertEquals(4, eb.getReferences(STORE).size());
			assertEquals(4, eb.toInstance().getReferences(STORE).size());
		}

		@Test
		@DisplayName("gradually promote reference cardinality from unique to duplicates")
		void shouldGraduallyPromoteReferenceCardinality() {
			final InitialEntityBuilder initialEntityBuilder = new InitialEntityBuilder("product", 100);
			initialEntityBuilder.setReference(BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1);
			assertTrue(initialEntityBuilder.getReference(new ReferenceKey(BRAND, 1)).isPresent());
			assertCardinality(Cardinality.ZERO_OR_ONE, initialEntityBuilder, new ReferenceKey(BRAND, 1));

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(initialEntityBuilder.toInstance());

			builder.setReference(BRAND, 2);
			assertTrue(builder.getReference(new ReferenceKey(BRAND, 1)).isPresent());

			assertEquals(
				Cardinality.ZERO_OR_MORE,
				builder.getReference(new ReferenceKey(BRAND, 2))
				       .orElseThrow()
				       .getReferenceSchemaOrThrow()
				       .getCardinality()
			);
			assertEquals(
				Cardinality.ZERO_OR_MORE,
				builder.toInstance()
				       .getReference(new ReferenceKey(BRAND, 2))
				       .orElseThrow()
				       .getReferenceSchemaOrThrow()
				       .getCardinality()
			);

			assertThrows(
				InvalidMutationException.class,
				() -> builder.setOrUpdateReference(
					BRAND, "differentEntityType", Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
					2, ref -> false, Functions.noOpConsumer()
				)
			);
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setOrUpdateReference(
					BRAND, BRAND, Cardinality.ONE_OR_MORE,
					2, ref -> false, Functions.noOpConsumer()
				)
			);

			assertThrows(
				InvalidMutationException.class,
				() -> builder.setOrUpdateReference(
					BRAND, 2, ref -> false, Functions.noOpConsumer()
				)
			);

			for (ReferenceContract reference : builder.getReferences(new ReferenceKey(BRAND, 2))) {
				assertEquals(Cardinality.ZERO_OR_MORE, reference.getReferenceSchemaOrThrow().getCardinality());
			}
			for (ReferenceContract reference : builder.toInstance().getReferences(new ReferenceKey(BRAND, 2))) {
				assertEquals(Cardinality.ZERO_OR_MORE, reference.getReferenceSchemaOrThrow().getCardinality());
			}
			assertTrue(builder.getReference(new ReferenceKey(BRAND, 1)).isPresent());

			builder.setOrUpdateReference(BRAND, 3, ref -> false, Functions.noOpConsumer());

			checkCollectionBrands(builder.getReferences(BRAND), 1, 2, 3);
			checkCollectionBrands(builder.toInstance().getReferences(BRAND), 1, 2, 3);
		}

		@Test
		@DisplayName("should modify entity via direct mutations")
		void shouldModifyEntityViaDirectMutations() {
			final InitialEntityBuilder initialEntityBuilder = new InitialEntityBuilder("product", 100);
			final ExistingEntityBuilder builder = new ExistingEntityBuilder(initialEntityBuilder.toInstance());

			builder.mutate(
				new SetParentMutation(10),
				new SetParentMutation(20),
				new UpsertAttributeMutation("name", "Product Name"),
				new UpsertAttributeMutation("name", "Different product Name"),
				new UpsertAttributeMutation("description", Locale.ENGLISH, "Product Description"),
				new UpsertAttributeMutation("description", Locale.GERMAN, "Produkt Beschreibung"),
				new UpsertAssociatedDataMutation("data", "Data"),
				new UpsertAssociatedDataMutation("data", "Updated data"),
				new UpsertAssociatedDataMutation("url", Locale.ENGLISH, "http://example.com/en"),
				new UpsertAssociatedDataMutation("url", Locale.GERMAN, "http://example.com/de"),
				new UpsertPriceMutation(
					new PriceKey(1, "basic", CZK), null,
					BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true
				),
				new UpsertPriceMutation(
					new PriceKey(1, "basic", CZK), null,
					BigDecimal.TEN, BigDecimal.TEN, BigDecimal.valueOf(20), null, false
				),
				new UpsertPriceMutation(
					new PriceKey(2, "basic", EUR), null,
					BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true
				),
				new InsertReferenceMutation(new ReferenceKey(BRAND_TYPE, 1), Cardinality.ZERO_OR_ONE, BRAND_TYPE),
				new InsertReferenceMutation(new ReferenceKey(BRAND_TYPE, 2)),
				new ReferenceAttributeMutation(
					new ReferenceKey(BRAND_TYPE, 1),
					new UpsertAttributeMutation("brandName", Locale.ENGLISH, "Brand Name")
				),
				new ReferenceAttributeMutation(
					new ReferenceKey(BRAND_TYPE, 1),
					new UpsertAttributeMutation("brandName", Locale.ENGLISH, "Brand Name updated")
				),
				new ReferenceAttributeMutation(
					new ReferenceKey(BRAND_TYPE, 1),
					new UpsertAttributeMutation("brandName", Locale.GERMAN, "Brand Bezeichnung")
				)
			);

			final Entity entity = builder.toInstance();
			assertEquals(20, builder.getParentEntity().orElseThrow().getPrimaryKeyOrThrowException());
			assertEquals(20, entity.getParent().orElseThrow());
			assertEquals("Different product Name", builder.getAttribute("name"));
			assertEquals("Different product Name", entity.getAttribute("name"));
			assertEquals("Product Description", builder.getAttribute("description", Locale.ENGLISH));
			assertEquals("Product Description", entity.getAttribute("description", Locale.ENGLISH));
			assertEquals("Produkt Beschreibung", builder.getAttribute("description", Locale.GERMAN));
			assertEquals("Produkt Beschreibung", entity.getAttribute("description", Locale.GERMAN));
			assertEquals("Updated data", builder.getAssociatedData("data"));
			assertEquals("Updated data", entity.getAssociatedData("data"));
			assertEquals("http://example.com/en", builder.getAssociatedData("url", Locale.ENGLISH));
			assertEquals("http://example.com/en", entity.getAssociatedData("url", Locale.ENGLISH));
			assertEquals("http://example.com/de", builder.getAssociatedData("url", Locale.GERMAN));
			assertEquals("http://example.com/de", entity.getAssociatedData("url", Locale.GERMAN));
			assertPrice(builder, 1, "basic", CZK, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.valueOf(20), false);
			assertPrice(entity, 1, "basic", CZK, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.valueOf(20), false);
			assertPrice(builder, 2, "basic", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true);
			assertPrice(entity, 2, "basic", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true);
			assertEquals(2, builder.getReferences(BRAND_TYPE).size());
			assertEquals(2, entity.getReferences(BRAND_TYPE).size());
			entity.getReference(BRAND_TYPE, 1)
			      .ifPresent(ref -> {
				      assertEquals("Brand Name updated", ref.getAttribute("brandName", Locale.ENGLISH));
				      assertEquals(
					      "Brand Name updated",
					      builder.getReference(BRAND_TYPE, 1)
					             .orElseThrow()
					             .getAttribute("brandName", Locale.ENGLISH)
				      );
				      assertEquals("Brand Bezeichnung", ref.getAttribute("brandName", Locale.GERMAN));
				      assertEquals(
					      "Brand Bezeichnung",
					      builder.getReference(BRAND_TYPE, 1)
					             .orElseThrow()
					             .getAttribute("brandName", Locale.GERMAN)
				      );
			      });
		}

		@Test
		@DisplayName("should remove references")
		void shouldRemoveReferences() {
			final Entity initialEntity = buildInitialEntityWithDuplicatedReferences();
			final ExistingEntityBuilder eb = new ExistingEntityBuilder(initialEntity);

			eb.removeReference(BRAND, 1);
			assertThrows(
				ReferenceAllowsDuplicatesException.class,
				() -> eb.removeReference(PARAMETER, 100)
			);

			final ReferenceContract findRealParameter = eb.getReferences(new ReferenceKey(PARAMETER, 100))
			                                              .iterator()
			                                              .next();
			eb.removeReference(findRealParameter.getReferenceKey());

			final Entity modifiedInstance = eb.toInstance();

			assertEquals(0, eb.getReferences(BRAND).size());
			assertThrows(
				ReferenceNotFoundException.class,
				() -> modifiedInstance.getReferences(BRAND)
			);
			assertEquals(1, eb.getReferences(PARAMETER).size());
			assertNotEquals(
				findRealParameter.getAttribute(ATTRIBUTE_DISCRIMINATOR, String.class),
				eb.getReferences(PARAMETER).iterator().next().getAttribute(ATTRIBUTE_DISCRIMINATOR, String.class)
			);
			assertEquals(1, modifiedInstance.getReferences(PARAMETER).stream().filter(Droppable::exists).count());
			assertNotEquals(
				findRealParameter.getAttribute(ATTRIBUTE_DISCRIMINATOR, String.class),
				modifiedInstance.getReferences(PARAMETER)
				                .stream()
				                .filter(Droppable::exists)
				                .findFirst()
				                .orElseThrow()
				                .getAttribute(ATTRIBUTE_DISCRIMINATOR, String.class)
			);
		}

		@Test
		@DisplayName("should remove references by name in bulk")
		void shouldRemoveReferencesByNameInBulk() {
			final Entity initialEntity = buildInitialEntityWithDuplicatedReferences();
			final ExistingEntityBuilder eb = new ExistingEntityBuilder(initialEntity);

			eb.removeReferences(PARAMETER, 100);
			eb.removeReferences(STORE, ref -> "A".equals(ref.getAttribute(ATTRIBUTE_DISCRIMINATOR, String.class)));

			final Entity modifiedInstance = eb.toInstance();

			assertEquals(0, eb.getReferences(PARAMETER).size());
			assertEquals(2, eb.getReferences(STORE).size());
			assertEquals(2, modifiedInstance.getReferences(STORE).stream().filter(Droppable::exists).count());
			assertEquals(
				Set.of("B"),
				eb.getReferences(STORE).stream()
				  .filter(Droppable::exists)
				  .flatMap(it -> it.getAttributeValue(ATTRIBUTE_DISCRIMINATOR).stream())
				  .map(AttributeValue::value)
				  .collect(Collectors.toSet())
			);
			assertEquals(
				Set.of("B"),
				modifiedInstance.getReferences(STORE).stream()
				  .filter(Droppable::exists)
				  .flatMap(it -> it.getAttributeValue(ATTRIBUTE_DISCRIMINATOR).stream())
				  .map(AttributeValue::value)
				  .collect(Collectors.toSet())
			);
		}

		@Test
		@DisplayName("should remove references in bulk")
		void shouldRemoveReferencesInBulk() {
			final Entity initialEntity = buildInitialEntityWithDuplicatedReferences();
			final ExistingEntityBuilder eb = new ExistingEntityBuilder(initialEntity);

			final Set<String> referencesForRemoval = Set.of(PARAMETER, STORE);
			eb.removeReferences(
				ref -> referencesForRemoval.contains(ref.getReferenceName()) &&
					"A".equals(ref.getAttribute(ATTRIBUTE_DISCRIMINATOR, String.class))
			);

			final Entity modifiedInstance = eb.toInstance();

			assertEquals(1, eb.getReferences(PARAMETER).size());
			assertEquals(1, modifiedInstance.getReferences(PARAMETER).stream().filter(Droppable::exists).count());
			assertEquals(2, eb.getReferences(STORE).size());
			assertEquals(2, modifiedInstance.getReferences(STORE).stream().filter(Droppable::exists).count());
			assertEquals(
				Set.of("B"),
				eb.getReferences(STORE).stream()
				  .filter(Droppable::exists)
				  .flatMap(it -> it.getAttributeValue(ATTRIBUTE_DISCRIMINATOR).stream())
				  .map(AttributeValue::value)
				  .collect(Collectors.toSet())
			);
			assertEquals(
				Set.of("B"),
				modifiedInstance.getReferences(STORE).stream()
				  .filter(Droppable::exists)
				  .flatMap(it -> it.getAttributeValue(ATTRIBUTE_DISCRIMINATOR).stream())
				  .map(AttributeValue::value)
				  .collect(Collectors.toSet())
			);
		}

		@Test
		@DisplayName("should allow adding new definitions")
		void shouldAllowAddingNewDefinitions() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.verifySchemaButAllow(EvolutionMode.values())
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			builder.setParent(1);
			builder.setAttribute("code", "X");
			builder.setAttribute("loc", Locale.ENGLISH, "EN");
			builder.setAttribute("loc", Locale.FRENCH, "FR");
			builder.setAssociatedData("manual", Locale.ENGLISH, "Manual EN");
			builder.setReference(
				BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1,
				whichIs -> whichIs.setAttribute("priority", 1)
			);
			builder.setReference(
				BRAND, 2,
				whichIs -> whichIs.setAttribute("priority", 2)
			);
			builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM);
			builder.setPrice(1, "basic", Currency.getInstance("USD"), new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true);

			assertEquals(1, builder.getParentEntity().orElseThrow().getPrimaryKeyOrThrowException());
			assertEquals("X", builder.getAttribute("code"));
			assertEquals("EN", builder.getAttribute("loc", Locale.ENGLISH));
			assertEquals("FR", builder.getAttribute("loc", Locale.FRENCH));
			assertEquals("Manual EN", builder.getAssociatedData("manual", Locale.ENGLISH));
			assertEquals(2, builder.getReferences(BRAND).size());
			assertEquals(1, builder.getReference(BRAND, 1).orElseThrow().getAttribute("priority", Integer.class).intValue());
			assertEquals(2, builder.getReference(BRAND, 2).orElseThrow().getAttribute("priority", Integer.class).intValue());
			assertEquals(PriceInnerRecordHandling.SUM, builder.getPriceInnerRecordHandling());
			assertEquals(1, builder.getPrices().size());
			assertNotNull(builder.getPrice(new PriceKey(1, "basic", Currency.getInstance("USD"))).orElseThrow());

			final Entity entity = builder.toInstance();
			assertEquals(1, entity.getParentEntity().orElseThrow().getPrimaryKeyOrThrowException());
			assertEquals("X", entity.getAttribute("code"));
			assertEquals("EN", entity.getAttribute("loc", Locale.ENGLISH));
			assertEquals("FR", entity.getAttribute("loc", Locale.FRENCH));
			assertEquals("Manual EN", entity.getAssociatedData("manual", Locale.ENGLISH));
			assertEquals(2, entity.getReferences(BRAND).size());
			assertEquals(1, entity.getReference(BRAND, 1).orElseThrow().getAttribute("priority", Integer.class).intValue());
			assertEquals(2, entity.getReference(BRAND, 2).orElseThrow().getAttribute("priority", Integer.class).intValue());
			assertEquals(PriceInnerRecordHandling.SUM, entity.getPriceInnerRecordHandling());
			assertEquals(1, entity.getPrices().size());
			assertNotNull(entity.getPrice(new PriceKey(1, "basic", Currency.getInstance("USD"))).orElseThrow());
		}

		@Test
		@DisplayName("should keep previous data on multiple edits of new reference")
		void shouldKeepPreviousDataOnMultipleEditsOfNewReference() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceTo(BRAND, BRAND, Cardinality.ZERO_OR_MORE)
				.toInstance();

			final ReferenceSchemaContract referenceSchema = schema.getReferenceOrThrowException(BRAND);
			final ExistingEntityBuilder builder = new ExistingEntityBuilder(
				Entity._internalBuild(
					1, 1, schema, null,
					new References(
						schema,
						new ReferenceContract[]{
							new Reference(
								referenceSchema,
								new ReferenceKey(BRAND, 1, 1),
								null,
								new ReferenceAttributes(
									schema, referenceSchema,
									Map.of(
										new AttributeKey("priority"),
										new AttributeValue(new AttributeKey("priority"), 10)
									),
									Map.of()
								)
							)
						},
						Set.of(BRAND),
						References.DEFAULT_CHUNK_TRANSFORMER
					),
					new EntityAttributes(schema),
					new AssociatedData(schema),
					new Prices(schema, PriceInnerRecordHandling.NONE),
					new HashSet<>(0),
					Scope.LIVE
				)
			);
			builder.setReference(
				BRAND, 1,
				whichIs -> {
					assertEquals(1, whichIs.getReferenceKey().internalPrimaryKey());
					assertEquals(BRAND, whichIs.getReferencedEntityType());
					assertEquals(BRAND, whichIs.getReferenceSchemaOrThrow().getReferencedEntityType());
					assertEquals(Cardinality.ZERO_OR_MORE, whichIs.getReferenceCardinality());
					assertEquals(Cardinality.ZERO_OR_MORE, whichIs.getReferenceSchemaOrThrow().getCardinality());
					assertNull(whichIs.getAttribute("priority", Integer.class));
					whichIs.setAttribute("priority", 11);
				}
			);
			builder.setReference(
				BRAND, 1,
				whichIs -> {
					assertEquals(1, whichIs.getReferenceKey().internalPrimaryKey());
					assertEquals(BRAND, whichIs.getReferencedEntityType());
					assertEquals(BRAND, whichIs.getReferenceSchemaOrThrow().getReferencedEntityType());
					assertEquals(Cardinality.ZERO_OR_MORE, whichIs.getReferenceCardinality());
					assertEquals(Cardinality.ZERO_OR_MORE, whichIs.getReferenceSchemaOrThrow().getCardinality());
					assertNull(whichIs.getAttribute("priority", Integer.class));
					whichIs.setAttribute("someOtherAttribute", "Y");
				}
			);
			builder.setReference(
				BRAND, 2,
				whichIs -> whichIs.setAttribute("priority", 1)
			);
			builder.setReference(
				BRAND, 2,
				whichIs -> {
					assertEquals(-1, whichIs.getReferenceKey().internalPrimaryKey());
					assertEquals(BRAND, whichIs.getReferencedEntityType());
					assertEquals(BRAND, whichIs.getReferenceSchemaOrThrow().getReferencedEntityType());
					assertEquals(Cardinality.ZERO_OR_MORE, whichIs.getReferenceCardinality());
					assertEquals(Cardinality.ZERO_OR_MORE, whichIs.getReferenceSchemaOrThrow().getCardinality());
					assertNull(whichIs.getAttribute("priority", Integer.class));
					whichIs.setAttribute("someOtherAttribute", "X");
				}
			);
			assertThrows(
				InvalidDataTypeMutationException.class,
				() -> builder.setReference(
					BRAND, 2,
					whichIs -> whichIs.setAttribute("someOtherAttribute", 1)
				)
			);
			final List<? extends LocalMutation<?, ?>> localMutations = builder
				.toMutation()
				.orElseThrow()
				.getLocalMutations();
			assertEquals(4, localMutations.size());
		}

		@Test
		@DisplayName("updateReference(name, id, consumer) updates existing or does nothing")
		void shouldUpdateExistingReferenceOrDoNothingWhenNotFound() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceToEntity(
					BRAND, BRAND, Cardinality.ZERO_OR_ONE,
					ref -> ref.withAttribute(BRAND_PRIORITY, Long.class, thatIs -> thatIs.nullable().representative())
				)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setReference(BRAND, 1, rb -> rb.setAttribute(BRAND_PRIORITY, 10L))
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();

			assertTrue(builder.getReference(BRAND, 1).isPresent());
			assertEquals(10L, builder.getReference(BRAND, 1).orElseThrow().getAttribute(BRAND_PRIORITY, Long.class));

			builder.updateReference(BRAND, 1, rb -> rb.setAttribute(BRAND_PRIORITY, 20L));
			assertEquals(20L, builder.getReference(BRAND, 1).orElseThrow().getAttribute(BRAND_PRIORITY, Long.class));

			final SealedEntity entity = builder.toInstance();
			assertEquals(1, entity.getReferences(BRAND).size());
			assertEquals(20L, entity.getReference(BRAND, 1).orElseThrow().getAttribute(BRAND_PRIORITY, Long.class));

			builder.updateReference(BRAND, 2, rb -> rb.setAttribute(BRAND_PRIORITY, 30L));
			assertEquals(1, builder.getReferences(BRAND).size(), "No new reference should be created");
			assertEquals(20L, builder.getReference(BRAND, 1).orElseThrow().getAttribute(BRAND_PRIORITY, Long.class));
		}

		@Test
		@DisplayName("updateReference(name, entityType, cardinality, id, consumer) updates existing or does nothing")
		void shouldUpdateExistingReferenceWithCardinalityOrDoNothingWhenNotFound() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceToEntity(
					BRAND, BRAND, Cardinality.ZERO_OR_ONE,
					ref -> ref.withAttribute(BRAND_PRIORITY, Long.class, thatIs -> thatIs.nullable().representative())
				)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setReference(
					BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1,
					rb -> rb.setAttribute(BRAND_PRIORITY, 10L)
				)
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();

			assertTrue(builder.getReference(BRAND, 1).isPresent());
			assertEquals(10L, builder.getReference(BRAND, 1).orElseThrow().getAttribute(BRAND_PRIORITY, Long.class));

			builder.updateReference(
				BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1,
				rb -> rb.setAttribute(BRAND_PRIORITY, 25L)
			);
			assertEquals(25L, builder.getReference(BRAND, 1).orElseThrow().getAttribute(BRAND_PRIORITY, Long.class));

			final SealedEntity entity = builder.toInstance();
			assertEquals(1, entity.getReferences(BRAND).size());
			assertEquals(25L, entity.getReference(BRAND, 1).orElseThrow().getAttribute(BRAND_PRIORITY, Long.class));

			builder.updateReference(
				BRAND, BRAND, Cardinality.ZERO_OR_ONE, 2,
				rb -> rb.setAttribute(BRAND_PRIORITY, 30L)
			);
			assertEquals(1, builder.getReferences(BRAND).size(), "No new reference should be created");
			assertEquals(25L, builder.getReference(BRAND, 1).orElseThrow().getAttribute(BRAND_PRIORITY, Long.class));
		}

		@Test
		@DisplayName("updateReference methods work correctly with multiple references of same type")
		void shouldUpdateOnlyTargetedReferenceAmongMultiple() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceToEntity(
					BRAND, BRAND, Cardinality.ZERO_OR_MORE,
					ref -> ref.withAttribute(BRAND_PRIORITY, Long.class, thatIs -> thatIs.nullable().representative())
				)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setReference(BRAND, 1, rb -> rb.setAttribute(BRAND_PRIORITY, 10L))
				.setReference(BRAND, 2, rb -> rb.setAttribute(BRAND_PRIORITY, 20L))
				.setReference(BRAND, 3, rb -> rb.setAttribute(BRAND_PRIORITY, 30L))
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();

			assertEquals(3, builder.getReferences(BRAND).size());

			builder.updateReference(BRAND, 2, rb -> rb.setAttribute(BRAND_PRIORITY, 200L));

			assertEquals(10L, builder.getReference(BRAND, 1).orElseThrow().getAttribute(BRAND_PRIORITY, Long.class));
			assertEquals(200L, builder.getReference(BRAND, 2).orElseThrow().getAttribute(BRAND_PRIORITY, Long.class));
			assertEquals(30L, builder.getReference(BRAND, 3).orElseThrow().getAttribute(BRAND_PRIORITY, Long.class));

			builder.updateReference(BRAND, 4, rb -> rb.setAttribute(BRAND_PRIORITY, 400L));
			assertEquals(3, builder.getReferences(BRAND).size(), "No new reference should be created");
		}

		@Test
		@DisplayName("updateReference methods respect duplicates with ZERO_OR_MORE_WITH_DUPLICATES")
		void shouldUpdateCorrectDuplicateReferenceWhenMultipleSameIdExist() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceToEntity(
					BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
					ref -> ref.withAttribute(
						BRAND_PRIORITY, Long.class, thatIs -> thatIs.nullable().representative()
					).withAttribute(
						ATTRIBUTE_DISCRIMINATOR, String.class, AttributeSchemaEditor::representative
					)
				)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setOrUpdateReference(
					BRAND, 1, ref -> false,
					rb -> { rb.setAttribute(BRAND_PRIORITY, 10L); rb.setAttribute(ATTRIBUTE_DISCRIMINATOR, "CZ"); }
				)
				.setOrUpdateReference(
					BRAND, 1, ref -> false,
					rb -> { rb.setAttribute(BRAND_PRIORITY, 20L); rb.setAttribute(ATTRIBUTE_DISCRIMINATOR, "DE"); }
				)
				.setOrUpdateReference(
					BRAND, 1, ref -> false,
					rb -> { rb.setAttribute(BRAND_PRIORITY, 30L); rb.setAttribute(ATTRIBUTE_DISCRIMINATOR, "FR"); }
				)
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();

			assertEquals(3, builder.getReferences(new ReferenceKey(BRAND, 1)).size());

			assertThrows(
				ReferenceAllowsDuplicatesException.class,
				() -> builder.updateReference(BRAND, 1, rb -> rb.setAttribute(BRAND_PRIORITY, 11L))
			);
		}

		@Test
		@DisplayName("should keep attribute schema consistent over multiple references")
		void shouldKeepAttributeSchemaConsistentOverMultipleReferences() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceToEntity(
					CATEGORY, CATEGORY, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.withAttribute("code", String.class)
				)
				.verifySchemaButAllow(EvolutionMode.values())
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			builder.setReference(
				BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1,
				whichIs -> whichIs.setAttribute("priority", 1)
			);
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setReference(
					BRAND, 2,
					whichIs -> whichIs.setAttribute("priority", Locale.ENGLISH, 2)
				)
			);
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setReference(
					BRAND, 2,
					whichIs -> whichIs.setAttribute("priority", "2")
				)
			);
			builder.setReference(
				CATEGORY, 1,
				whichIs -> whichIs.setAttribute("code", "X")
			);
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setReference(
					CATEGORY, 2,
					whichIs -> whichIs.setAttribute("code", Locale.ENGLISH, "Y")
				)
			);
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setReference(
					CATEGORY, 2,
					whichIs -> whichIs.setAttribute("code", 2)
				)
			);
		}

		@Test
		@DisplayName("should fail to insert duplicated reference sharing representative attribute values")
		void failToInsertDuplicatedReferenceSharingRepresentativeAttributeValues() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.verifySchemaStrictly()
				.withReferenceTo(
					BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
					ref -> ref.indexedForFiltering()
						.withAttribute(ATTRIBUTE_DISCRIMINATOR, String.class, thatIs -> thatIs.filterable().representative())
				)
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			builder.setOrUpdateReference(BRAND, 1, filter -> false, rb -> rb.setAttribute(ATTRIBUTE_DISCRIMINATOR, "CZ"));
			builder.setOrUpdateReference(BRAND, 1, filter -> false, rb -> rb.setAttribute(ATTRIBUTE_DISCRIMINATOR, "DE"));
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setOrUpdateReference(BRAND, 1, filter -> false, rb -> rb.setAttribute(ATTRIBUTE_DISCRIMINATOR, "CZ"))
			);

			final Collection<ReferenceContract> references = builder.toInstance().getReferences(BRAND);
			assertEquals(2, references.size());
		}

		@Test
		@DisplayName("allows reusing freed representative values in duplicated references")
		void allowReusingFreedRepresentativeAssociatedValuesInDuplicatedReference() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.verifySchemaStrictly()
				.withReferenceTo(
					BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
					ref -> ref.indexedForFiltering()
						.withAttribute(ATTRIBUTE_DISCRIMINATOR, String.class, thatIs -> thatIs.filterable().representative())
				)
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			builder.setOrUpdateReference(BRAND, 1, ref -> false, rb -> rb.setAttribute(ATTRIBUTE_DISCRIMINATOR, "CZ"));
			builder.setOrUpdateReference(BRAND, 1, ref -> false, rb -> rb.setAttribute(ATTRIBUTE_DISCRIMINATOR, "DE"));
			builder.setOrUpdateReference(
				BRAND, 1,
				ref -> "DE".equals(ref.getAttribute(ATTRIBUTE_DISCRIMINATOR)),
				rb -> rb.setAttribute(ATTRIBUTE_DISCRIMINATOR, "FR")
			);
			builder.setOrUpdateReference(BRAND, 1, ref -> false, rb -> rb.setAttribute(ATTRIBUTE_DISCRIMINATOR, "DE"));

			final Collection<ReferenceContract> references = builder.toInstance().getReferences(BRAND);
			assertEquals(3, references.size());
		}
	}

	@Nested
	@DisplayName("Scope management")
	class ScopeManagementTest {

		@Test
		@DisplayName("should change scope and reflect in getter")
		void shouldChangeScopeAndReflectInGetter() {
			ExistingEntityBuilderTest.this.builder.setScope(Scope.ARCHIVED);
			assertEquals(Scope.ARCHIVED, ExistingEntityBuilderTest.this.builder.getScope());

			final Entity updatedEntity = ExistingEntityBuilderTest.this.builder.toMutation().orElseThrow()
				.mutate(ExistingEntityBuilderTest.this.initialEntity.getSchema(),
					ExistingEntityBuilderTest.this.initialEntity);

			assertEquals(Scope.ARCHIVED, updatedEntity.getScope());
			assertEquals(ExistingEntityBuilderTest.this.initialEntity.version() + 1, updatedEntity.version());
		}

		@Test
		@DisplayName("should not create scope mutation when unchanged")
		void shouldNotCreateScopeMutationWhenScopeUnchanged() {
			ExistingEntityBuilderTest.this.builder.setScope(
				ExistingEntityBuilderTest.this.initialEntity.getScope());
			assertEquals(
				ExistingEntityBuilderTest.this.initialEntity.getScope(),
				ExistingEntityBuilderTest.this.builder.getScope()
			);
			assertTrue(ExistingEntityBuilderTest.this.builder.toMutation().isEmpty());
		}
	}

	@Nested
	@DisplayName("Locale management")
	class LocaleManagementTest {

		@Test
		@DisplayName("should collect all locales correctly")
		void shouldCollectAllLocalesCorrectly() {
			assertEquals(Set.of(Locale.ENGLISH), ExistingEntityBuilderTest.this.builder.getAllLocales());
			assertEquals(Set.of(Locale.ENGLISH), ExistingEntityBuilderTest.this.builder.getLocales());
			assertEquals(Set.of(Locale.ENGLISH), ExistingEntityBuilderTest.this.builder.toInstance().getAllLocales());
			assertEquals(Set.of(Locale.ENGLISH), ExistingEntityBuilderTest.this.builder.toInstance().getLocales());

			ExistingEntityBuilderTest.this.builder.setAttribute("newLocalizedAttribute", Locale.GERMAN, "value");
			assertEquals(Set.of(Locale.ENGLISH, Locale.GERMAN), ExistingEntityBuilderTest.this.builder.getAllLocales());
			assertEquals(Set.of(Locale.ENGLISH, Locale.GERMAN), ExistingEntityBuilderTest.this.builder.getLocales());
			assertEquals(Set.of(Locale.ENGLISH, Locale.GERMAN), ExistingEntityBuilderTest.this.builder.toInstance().getAllLocales());
			assertEquals(Set.of(Locale.ENGLISH, Locale.GERMAN), ExistingEntityBuilderTest.this.builder.toInstance().getLocales());
		}
	}

	@Nested
	@DisplayName("Mutation and change set")
	class MutationAndChangeSetTest {

		@Test
		@DisplayName("should build empty change set when no modifications")
		void shouldBuildEmptyChangeSetWhenNoModifications() {
			final ExistingEntityBuilder freshBuilder = new ExistingEntityBuilder(
				ExistingEntityBuilderTest.this.initialEntity
			);
			assertTrue(freshBuilder.toMutation().isEmpty());
		}

		@Test
		@DisplayName("mutate with RemoveAssociatedDataMutation removes associated data")
		void shouldMutateWithRemoveAssociatedDataMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withAssociatedData("manual", String.class)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setAssociatedData("manual", "User Manual")
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			assertEquals("User Manual", builder.getAssociatedData("manual"));

			builder.mutate(new RemoveAssociatedDataMutation(new AssociatedDataKey("manual")));

			assertNull(builder.getAssociatedData("manual"));
		}

		@Test
		@DisplayName("mutate with UpsertAssociatedDataMutation upserts associated data")
		void shouldMutateWithUpsertAssociatedDataMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withAssociatedData("manual", String.class, io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor::localized)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setAssociatedData("manual", Locale.ENGLISH, "Old Manual")
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			builder.mutate(new UpsertAssociatedDataMutation(new AssociatedDataKey("manual", Locale.ENGLISH), "Updated Manual"));

			assertEquals("Updated Manual", builder.getAssociatedData("manual", Locale.ENGLISH));
		}

		@Test
		@DisplayName("mutate with ApplyDeltaAttributeMutation applies delta to numeric attribute")
		void shouldMutateWithApplyDeltaAttributeMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withAttribute("quantity", Integer.class)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setAttribute("quantity", 100)
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			builder.mutate(new ApplyDeltaAttributeMutation<>(new AttributeKey("quantity"), 10));

			assertEquals(110, builder.getAttribute("quantity", Integer.class));
		}

		@Test
		@DisplayName("mutate with RemoveAttributeMutation removes attribute")
		void shouldMutateWithRemoveAttributeMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withAttribute("name", String.class, AttributeSchemaEditor::localized)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setAttribute("name", Locale.ENGLISH, "Product Name")
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			assertEquals("Product Name", builder.getAttribute("name", Locale.ENGLISH));

			builder.mutate(new RemoveAttributeMutation(new AttributeKey("name", Locale.ENGLISH)));

			assertNull(builder.getAttribute("name", Locale.ENGLISH));
		}

		@Test
		@DisplayName("mutate with UpsertAttributeMutation upserts attribute")
		void shouldMutateWithUpsertAttributeMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withAttribute("code", String.class)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setAttribute("code", "OLD-CODE")
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			builder.mutate(new UpsertAttributeMutation(new AttributeKey("code"), "NEW-CODE"));

			assertEquals("NEW-CODE", builder.getAttribute("code"));
		}

		@Test
		@DisplayName("mutate with RemoveParentMutation removes parent")
		void shouldMutateWithRemoveParentMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withHierarchy()
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setParent(42)
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			assertTrue(builder.getParentEntity().isPresent());

			builder.mutate(new RemoveParentMutation());

			assertTrue(builder.getParentEntity().isEmpty());
		}

		@Test
		@DisplayName("mutate with SetParentMutation sets parent")
		void shouldMutateWithSetParentMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withHierarchy()
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setParent(42)
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			assertEquals(42, builder.getParentEntity().orElseThrow().getPrimaryKey());

			builder.mutate(new SetParentMutation(99));

			assertEquals(99, builder.getParentEntity().orElseThrow().getPrimaryKey());
		}

		@Test
		@DisplayName("mutate with RemovePriceMutation removes price")
		void shouldMutateWithRemovePriceMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withPrice()
				.toInstance();

			final Currency usd = Currency.getInstance("USD");
			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setPrice(1, "basic", usd, new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true)
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			assertTrue(builder.getPrice(1, "basic", usd).isPresent());

			builder.mutate(new RemovePriceMutation(new PriceKey(1, "basic", usd)));

			assertTrue(builder.getPrice(1, "basic", usd).isEmpty());
		}

		@Test
		@DisplayName("mutate with SetPriceInnerRecordHandlingMutation sets price inner record handling")
		void shouldMutateWithSetPriceInnerRecordHandlingMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withPrice()
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			assertEquals(PriceInnerRecordHandling.NONE, builder.getPriceInnerRecordHandling());

			builder.mutate(new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.SUM));

			assertEquals(PriceInnerRecordHandling.SUM, builder.getPriceInnerRecordHandling());
		}

		@Test
		@DisplayName("mutate with UpsertPriceMutation upserts price")
		void shouldMutateWithUpsertPriceMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withPrice()
				.toInstance();

			final Currency eur = Currency.getInstance("EUR");
			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setPrice(2, "wholesale", eur, new BigDecimal("50.00"), new BigDecimal("10.00"), new BigDecimal("55.00"), true)
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			builder.mutate(
				new UpsertPriceMutation(
					new PriceKey(2, "wholesale", eur), null,
					new BigDecimal("60.00"), new BigDecimal("10.00"), new BigDecimal("66.00"), null, true
				)
			);

			assertTrue(builder.getPrice(2, "wholesale", eur).isPresent());
			assertEquals(new BigDecimal("60.00"), builder.getPrice(2, "wholesale", eur).orElseThrow().priceWithoutTax());
			assertEquals(new BigDecimal("66.00"), builder.getPrice(2, "wholesale", eur).orElseThrow().priceWithTax());
		}

		@Test
		@DisplayName("mutate with InsertReferenceMutation inserts reference")
		void shouldMutateWithInsertReferenceMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceToEntity(BRAND, BRAND, Cardinality.ZERO_OR_MORE, r -> {})
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1).toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			builder.mutate(new InsertReferenceMutation(new ReferenceKey(BRAND, 5), Cardinality.ZERO_OR_MORE, BRAND));

			assertTrue(builder.getReference(BRAND, 5).isPresent());
		}

		@Test
		@DisplayName("mutate with ReferenceAttributeMutation modifies reference attribute")
		void shouldMutateWithReferenceAttributeMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceToEntity(
					BRAND, BRAND, Cardinality.ZERO_OR_MORE,
					r -> r.withAttribute(BRAND_PRIORITY, Long.class, AttributeSchemaEditor::nullable)
				)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setReference(BRAND, 10, rb -> rb.setAttribute(BRAND_PRIORITY, 50L))
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			builder.mutate(
				new ReferenceAttributeMutation(
					new ReferenceKey(BRAND, 10),
					new UpsertAttributeMutation(new AttributeKey(BRAND_PRIORITY), 100L)
				)
			);

			final ReferenceContract ref = builder.getReference(BRAND, 10).orElseThrow();
			assertEquals(100L, ref.getAttribute(BRAND_PRIORITY, Long.class));
		}

		@Test
		@DisplayName("mutate with RemoveReferenceGroupMutation removes reference group")
		void shouldMutateWithRemoveReferenceGroupMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceToEntity(
					BRAND, BRAND, Cardinality.ZERO_OR_MORE,
					r -> r.withGroupType("group")
				)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setReference(BRAND, 15, rb -> rb.setGroup("group", 20))
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			assertTrue(builder.getReference(BRAND, 15).orElseThrow().getGroup().isPresent());

			builder.mutate(new RemoveReferenceGroupMutation(new ReferenceKey(BRAND, 15)));

			assertTrue(builder.getReference(BRAND, 15).orElseThrow().getGroup().isEmpty());
		}

		@Test
		@DisplayName("mutate with RemoveReferenceMutation removes reference")
		void shouldMutateWithRemoveReferenceMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceToEntity(BRAND, BRAND, Cardinality.ZERO_OR_MORE, r -> {})
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setReference(BRAND, 25)
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			assertTrue(builder.getReference(BRAND, 25).isPresent());

			builder.mutate(new RemoveReferenceMutation(new ReferenceKey(BRAND, 25)));

			assertTrue(builder.getReference(BRAND, 25).isEmpty());
		}

		@Test
		@DisplayName("mutate with SetReferenceGroupMutation sets reference group")
		void shouldMutateWithSetReferenceGroupMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceToEntity(
					BRAND, BRAND, Cardinality.ZERO_OR_MORE,
					r -> r.withGroupType("group")
				)
				.toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1)
				.setReference(BRAND, 30)
				.toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			assertTrue(builder.getReference(BRAND, 30).orElseThrow().getGroup().isEmpty());

			builder.mutate(new SetReferenceGroupMutation(new ReferenceKey(BRAND, 30), "group", 50));

			final ReferenceContract ref = builder.getReference(BRAND, 30).orElseThrow();
			assertTrue(ref.getGroup().isPresent());
			assertEquals(50, ref.getGroup().orElseThrow().getPrimaryKey());
		}

		@Test
		@DisplayName("mutate with SetEntityScopeMutation sets entity scope")
		void shouldMutateWithSetEntityScopeMutation() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			).toInstance();

			final SealedEntity initialEntity = new InitialEntityBuilder(schema, 1).toInstance();

			final EntityBuilder builder = initialEntity.openForWrite();
			assertEquals(Scope.LIVE, builder.getScope());

			builder.mutate(new SetEntityScopeMutation(Scope.ARCHIVED));

			assertEquals(Scope.ARCHIVED, builder.getScope());
		}
	}

	@Nested
	@DisplayName("Schema enforcement")
	class SchemaEnforcementTest {

		@Test
		@DisplayName("should deny adding attributes")
		void shouldDenyAddingAttributes() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.verifySchemaButAllow(
					EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION,
					EvolutionMode.ADDING_REFERENCES
				)
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			assertThrows(InvalidMutationException.class, () -> builder.setAttribute("code", "X"));
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setReference(
					BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1,
					whichIs -> whichIs.setAttribute("priority", 1)
				)
			);
		}

		@Test
		@DisplayName("should deny adding associated data")
		void shouldDenyAddingAssociatedData() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.verifySchemaButAllow(EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION)
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			assertThrows(InvalidMutationException.class, () -> builder.setAssociatedData("key", "value"));
			assertThrows(InvalidMutationException.class, () -> builder.setAssociatedData("key", Locale.ENGLISH, "value"));
		}

		@Test
		@DisplayName("should deny adding language")
		void shouldDenyAddingLanguage() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.verifySchemaButAllow(
					EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION,
					EvolutionMode.ADDING_REFERENCES,
					EvolutionMode.ADDING_ATTRIBUTES,
					EvolutionMode.ADDING_ASSOCIATED_DATA
				)
				.withLocale(Locale.ENGLISH)
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			builder.setAttribute("code", Locale.ENGLISH, "X");
			builder.setAssociatedData("whatever", Locale.ENGLISH, "X");
			assertThrows(InvalidMutationException.class, () -> builder.setAttribute("code", Locale.GERMAN, "X"));
			assertThrows(InvalidMutationException.class, () -> builder.setAssociatedData("whatever", Locale.GERMAN, "X"));
			builder.setReference(
				BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1,
				whichIs -> whichIs.setAttribute("priority", Locale.ENGLISH, 1)
			);
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setReference(
					BRAND, BRAND, Cardinality.ZERO_OR_ONE, 2,
					whichIs -> whichIs.setAttribute("priority", Locale.GERMAN, 1)
				)
			);
		}

		@Test
		@DisplayName("should deny adding parent")
		void shouldDenyAddingParent() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.verifySchemaButAllow(EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION)
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			assertThrows(InvalidMutationException.class, () -> builder.setParent(1));
		}

		@Test
		@DisplayName("should deny adding price")
		void shouldDenyAddingPrice() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.verifySchemaButAllow(EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION)
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			assertThrows(InvalidMutationException.class, () -> builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM));
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setPrice(
					1, "basic", Currency.getInstance("USD"),
					new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true
				)
			);
		}

		@Test
		@DisplayName("should deny adding price currency")
		void shouldDenyAddingPriceCurrency() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.verifySchemaButAllow(EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION)
				.withPriceInCurrency(DataGenerator.CURRENCY_CZK)
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			builder.setPrice(
				1, "basic", DataGenerator.CURRENCY_CZK,
				new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true
			);
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setPrice(
					1, "basic", Currency.getInstance("USD"),
					new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true
				)
			);
		}

		@Test
		@DisplayName("should deny adding reference")
		void shouldDenyAddingReference() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.verifySchemaButAllow(EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION)
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setReference(BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1)
			);
		}

		@Test
		@DisplayName("should deny elevating cardinality")
		void shouldDenyElevatingCardinality() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.verifySchemaButAllow(
					EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION,
					EvolutionMode.ADDING_REFERENCES
				)
				.toInstance();

			final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
			builder.setReference(BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1);

			assertThrows(InvalidMutationException.class, () -> builder.setReference(BRAND, 2));
		}
	}

	@Nested
	@DisplayName("Identity and versioning")
	class IdentityAndVersioningTest {

		@Test
		@DisplayName("should return original when nothing changed")
		void shouldReturnOriginalEntityInstanceWhenNothingHasChanged() {
			final SealedEntity newEntity = new ExistingEntityBuilder(
				ExistingEntityBuilderTest.this.initialEntity
			)
				.setParent(5)
				.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE)
				.setPrice(1, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(2, "reference", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
				.setPrice(3, "basic", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(4, "reference", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
				.setAttribute("string", "string")
				.setAttribute("int", Locale.ENGLISH, 1)
				.setAttribute("bigDecimal", Locale.ENGLISH, BigDecimal.ONE)
				.setAssociatedData("string", "string")
				.setAssociatedData("int", Locale.ENGLISH, 1)
				.setAssociatedData("bigDecimal", Locale.ENGLISH, BigDecimal.ONE)
				.toInstance();

			assertSame(ExistingEntityBuilderTest.this.initialEntity, newEntity);
		}

		@Test
		@DisplayName("should return new entity when changes exist")
		void shouldReturnNewEntityWhenChangesExist() {
			final SealedEntity newEntity = new ExistingEntityBuilder(
				ExistingEntityBuilderTest.this.initialEntity
			)
				.setAttribute("string", "different")
				.toInstance();

			assertNotSame(ExistingEntityBuilderTest.this.initialEntity, newEntity);
			assertEquals("different", newEntity.getAttribute("string"));
		}
	}

}
