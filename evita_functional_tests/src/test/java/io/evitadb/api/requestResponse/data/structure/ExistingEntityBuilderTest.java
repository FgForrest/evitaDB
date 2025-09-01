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
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static io.evitadb.api.requestResponse.data.structure.InitialEntityBuilderTest.assertCardinality;
import static io.evitadb.test.Entities.BRAND;
import static java.util.OptionalInt.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link ExistingEntityBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ExistingEntityBuilderTest extends AbstractBuilderTest {
	private static final Currency CZK = Currency.getInstance("CZK");
	private static final Currency EUR = Currency.getInstance("EUR");
	private static final String SORTABLE_ATTRIBUTE = "toSort";
	private static final String BRAND_TYPE = "BRAND";
	private static final String ATTRIBUTE_DISCRIMINATOR = "discriminator";
	private static final String ATTRIBUTE_UPDATED = "updated";
	private Entity initialEntity;
	private ExistingEntityBuilder builder;

	public static void assertPrice(SealedEntity updatedInstance, int priceId, String priceList, Currency currency, BigDecimal priceWithoutTax, BigDecimal taxRate, BigDecimal priceWithTax, boolean indexed) {
		final PriceContract price = updatedInstance.getPrice(priceId, priceList, currency).orElseGet(() -> fail("Price not found!"));
		assertEquals(priceWithoutTax, price.priceWithoutTax());
		assertEquals(taxRate, price.taxRate());
		assertEquals(priceWithTax, price.priceWithTax());
		assertEquals(indexed, price.indexed());
	}

	@BeforeEach
	void setUp() {
		final SealedEntity sealedEntity = new InitialEntityBuilder("product", 1)
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

	@Test
	void shouldSkipMutationsThatMeansNoChange() {
		this.builder
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

		assertTrue(this.builder.toMutation().isEmpty());
	}

	@Test
	void shouldRemoveParent() {
		assertFalse(this.builder.getParentEntity().isEmpty());
		this.builder.removeParent();
		assertTrue(this.builder.getParentEntity().isEmpty());

		final Entity updatedEntity = this.builder.toMutation()
			.map(it -> it.mutate(this.initialEntity.getSchema(), this.initialEntity))
			.orElse(this.initialEntity);

		assertFalse(updatedEntity.parentAvailable());
		assertThrows(EntityIsNotHierarchicalException.class, updatedEntity::getParent);
		assertThrows(EntityIsNotHierarchicalException.class, updatedEntity::getParentEntity);
		assertEquals(this.initialEntity.version() + 1, updatedEntity.version());
	}

	@Test
	void shouldDefineReferenceGroup() {
		final Entity updatedEntity = setupEntityWithBrand();
		final ReferenceContract reference = updatedEntity.getReference(BRAND_TYPE, 1).orElseThrow();
		assertEquals(new GroupEntityReference("Whatever", 8, 1, false), reference.getGroup().orElse(null));
	}

	@Test
	void shouldRemovePriceInnerRecordHandling() {
		this.builder.removePriceInnerRecordHandling();
		assertEquals(PriceInnerRecordHandling.NONE, this.builder.getPriceInnerRecordHandling());

		final Entity updatedEntity = this.builder.toMutation().orElseThrow().mutate(this.initialEntity.getSchema(), this.initialEntity);
		assertEquals(PriceInnerRecordHandling.NONE, updatedEntity.getPriceInnerRecordHandling());
	}

	@Test
	void shouldOverwriteParent() {
		this.builder.setParent(78);
		assertEquals(
			Optional.of(new EntityReferenceWithParent(this.initialEntity.getSchema().getName(), 78, null)),
			this.builder.getParentEntity()
		);

		final Entity updatedEntity = this.builder.toMutation().orElseThrow().mutate(this.initialEntity.getSchema(), this.initialEntity);
		assertEquals(this.initialEntity.version() + 1, updatedEntity.version());
		assertEquals(of(78), updatedEntity.getParent());
	}

	@Test
	void shouldAddNewAttributes() {
		final SealedEntity updatedInstance = this.builder
			.setAttribute("newAttribute", "someValue")
			.toInstance();

		assertEquals("someValue", updatedInstance.getAttribute("newAttribute"));
	}

	@Test
	void shouldAddNewAssociatedData() {
		final SealedEntity updatedInstance = this.builder
			.setAssociatedData("newAttribute", "someValue")
			.toInstance();

		assertEquals("someValue", updatedInstance.getAssociatedData("newAttribute"));
	}

	@Test
	void shouldSetNewParent() {
		final SealedEntity updatedInstance = this.builder
			.setParent(2)
			.toInstance();

		assertEquals(2, updatedInstance.getParentEntity().orElseThrow().getPrimaryKey());
	}

	@Test
	void shouldSetNewReference() {
		final SealedEntity updatedInstance = this.builder
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
	void shouldOverwritePrices() {
		final SealedEntity updatedInstance = this.builder
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
	void shouldFailToAddArrayAsSortableAttribute() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			EntitySchema._internalBuild("product")
		)
			.withAttribute(SORTABLE_ATTRIBUTE, String.class, AttributeSchemaEditor::sortable)
			.toInstance();

		final ExistingEntityBuilder existingEntityBuilder = new ExistingEntityBuilder(new InitialEntityBuilder(schema).toInstance());
		assertThrows(IllegalArgumentException.class, () -> existingEntityBuilder.setAttribute(SORTABLE_ATTRIBUTE, new String[]{"abc", "def"}));
	}

	@Test
	void shouldReturnOriginalEntityInstanceWhenNothingHasChanged() {
		final SealedEntity newEntity = new ExistingEntityBuilder(this.initialEntity)
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

		assertSame(this.initialEntity, newEntity);
	}

	@Test
	void shouldChangeScopeAndReflectInGetter() {
		this.builder.setScope(Scope.ARCHIVED);
		assertEquals(Scope.ARCHIVED, this.builder.getScope());

		final Entity updatedEntity = this.builder.toMutation().orElseThrow()
			.mutate(this.initialEntity.getSchema(), this.initialEntity);

		assertEquals(Scope.ARCHIVED, updatedEntity.getScope());
		assertEquals(this.initialEntity.version() + 1, updatedEntity.version());
	}

	@Test
	void shouldNotCreateScopeMutationWhenScopeUnchanged() {
		this.builder.setScope(this.initialEntity.getScope());
		assertEquals(this.initialEntity.getScope(), this.builder.getScope());
		assertTrue(this.builder.toMutation().isEmpty());
	}

	@Test
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
	void shouldRemoveExistingReferenceAndAddAgain() {
		final Entity entityWithBrand = setupEntityWithBrand();

		final EntityBuilder entityBuilder = new ExistingEntityBuilder(entityWithBrand)
			.removeReference(BRAND_TYPE, 1)
			.setReference(
				BRAND_TYPE, 1,
				whichIs -> whichIs
					.setGroup("Whatever", 8)
					.setAttribute("newAttribute", "someValue")
			);

		final SealedEntity updatedInstance = entityBuilder.toInstance();
		assertEquals(1, updatedInstance.getReferences(BRAND_TYPE).size());

		updatedInstance.getReference(BRAND_TYPE, 1).ifPresent(reference -> {
			assertEquals("Whatever", reference.getGroup().map(GroupEntityReference::getType).orElse(null));
			assertEquals(8, reference.getGroup().map(GroupEntityReference::getPrimaryKey).orElse(null));
			assertEquals("someValue", reference.getAttribute("newAttribute"));
		});
	}

	@Nonnull
	private Entity setupEntityWithBrand() {
		this.builder.setReference(
			BRAND_TYPE,
			BRAND_TYPE,
			Cardinality.ZERO_OR_ONE,
			1,
			whichIs -> whichIs.setGroup("Whatever", 8)
		);

		final EntityMutation entityMutation = this.builder.toMutation().orElseThrow();
		final Collection<? extends LocalMutation<?, ?>> localMutations = entityMutation.getLocalMutations();
		assertEquals(2, localMutations.size());

		final SealedEntitySchema sealedEntitySchema = new EntitySchemaDecorator(() -> CATALOG_SCHEMA, (EntitySchema) this.initialEntity.getSchema());
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

	@Test
	void shouldGetReferencesHandleRemovalsUpdatesAndConflicts() {
		final Entity entityWithRefs = buildInitialEntityWithDuplicatedReferences();
		final ExistingEntityBuilder eb = new ExistingEntityBuilder(entityWithRefs);

		eb.updateReferences(
			ref -> ref.getReferenceName().equals(Entities.CATEGORY),
			ref -> ref.setAttribute(ATTRIBUTE_UPDATED, OffsetDateTime.now())
		);

		assertAllCategoriesHasUpdatedAttributeButNothingElseHas(eb);

		// we can set attribute this way, because there are not duplicates
		eb.setReference(BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_UPDATED, OffsetDateTime.now()));

		// but we cannot do that on references that allow duplicates
		assertThrows(
			ReferenceAllowsDuplicatesException.class,
			() -> eb.setReference(Entities.PARAMETER, 100, whichIs -> whichIs.setAttribute(ATTRIBUTE_UPDATED, OffsetDateTime.now()))
		);
		assertThrows(
			ReferenceAllowsDuplicatesException.class,
			() -> eb.setReference(Entities.STORE, 1000, whichIs -> whichIs.setAttribute(ATTRIBUTE_UPDATED, OffsetDateTime.now()))
		);
	}

	private static void assertAllCategoriesHasUpdatedAttributeButNothingElseHas(ExistingEntityBuilder builder) {
		for (ReferenceContract reference : builder.getReferences()) {
			if (reference.getReferenceName().equals(Entities.CATEGORY)) {
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
			if (reference.getReferenceName().equals(Entities.CATEGORY)) {
				assertNotNull(reference.getAttribute(ATTRIBUTE_UPDATED));
			} else {
				assertThrows(
					AttributeNotFoundException.class,
					() -> reference.getAttribute(ATTRIBUTE_UPDATED)
				);
			}
		}
	}

	@Test
	@DisplayName("gradually promote reference cardinality from unique to duplicates")
	void shouldGraduallyPromoteReferenceCardinality() {
		// default evolution strategy is ALLOW all
		final InitialEntityBuilder initialEntityBuilder = new InitialEntityBuilder("product", 100);
		initialEntityBuilder.setReference(BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1);
		assertTrue(initialEntityBuilder.getReference(new ReferenceKey(BRAND, 1)).isPresent());
		assertCardinality(Cardinality.ZERO_OR_ONE, initialEntityBuilder, new ReferenceKey(BRAND, 1));

		// create builder from initial entity
		final ExistingEntityBuilder builder = new ExistingEntityBuilder(initialEntityBuilder.toInstance());

		// promote to ZERO_OR_MORE
		builder.setReference(BRAND, 2);
		assertTrue(builder.getReference(new ReferenceKey(BRAND, 1)).isPresent());

		// new reference has automatically elevated cardinality
		assertEquals(Cardinality.ZERO_OR_MORE, builder.getReference(new ReferenceKey(BRAND, 2)).orElseThrow().getReferenceSchemaOrThrow().getCardinality());
		assertEquals(Cardinality.ZERO_OR_MORE, builder.toInstance().getReference(new ReferenceKey(BRAND, 2)).orElseThrow().getReferenceSchemaOrThrow().getCardinality());

		// promote to ZERO_OR_MORE_WITH_DUPLICATES
		builder.setReference(
			BRAND,
			2,
			ref -> false,
			UnaryOperator.identity()
		);
		// new references has automatically elevated cardinality
		for (ReferenceContract reference : builder.getReferences(new ReferenceKey(BRAND, 2))) {
			assertEquals(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, reference.getReferenceSchemaOrThrow().getCardinality());
		}
		for (ReferenceContract reference : builder.toInstance().getReferences(new ReferenceKey(BRAND, 2))) {
			assertEquals(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, reference.getReferenceSchemaOrThrow().getCardinality());
		}
		assertThrows(
			ReferenceAllowsDuplicatesException.class,
			() -> builder.getReference(new ReferenceKey(BRAND, 1)).isPresent()
		);

		// add another duplicate
		builder.setReference(
			BRAND,
			3,
			ref -> false,
			UnaryOperator.identity()
		);

		checkCollectionBrands(builder.getReferences(BRAND), 1, 2, 2, 3);
		checkCollectionBrands(builder.toInstance().getReferences(BRAND), 1, 2, 2, 3);
	}

	private static void checkCollectionBrands(Collection<ReferenceContract> references, int... expectedPks) {
		assertEquals(expectedPks.length, references.size());

		int i = 0;
		for (ReferenceContract ref : references) {
			assertEquals(expectedPks[i++], ref.getReferencedPrimaryKey());
		}
	}

	@Nonnull
	private Entity buildInitialEntityWithDuplicatedReferences() {
		final ExistingEntityBuilder eb = new ExistingEntityBuilder(this.initialEntity);
		// initial: 1x BRAND
		eb.setReference(BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1);
		// initial: 2x CATEGORY (different PKs)
		eb.setReference(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE, 10);
		eb.setReference(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE, 11);
		// initial: 2x PARAMETER (same PKs)
		eb.setReference(
			Entities.PARAMETER,
			Entities.PARAMETER,
			Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			100,
			ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "A")
		);
		eb.setReference(
			Entities.PARAMETER,
			Entities.PARAMETER,
			Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			100,
			ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "B")
		);
		// initial: 3x STORE (two share same referencedEntityPrimaryKey)
		eb.setReference(
			Entities.STORE,
			Entities.STORE,
			Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			1000,
			ref -> false,
			UnaryOperator.identity()
		);
		eb.setReference(
			Entities.STORE,
			Entities.STORE,
			Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			1001,
			ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "A")
		);
		eb.setReference(
			Entities.STORE,
			Entities.STORE,
			Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			1001,
			ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "B")
		);
		return eb.toInstance();
	}

}
