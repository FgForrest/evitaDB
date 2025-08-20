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

import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;

import static java.util.OptionalInt.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link ExistingEntityBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ExistingEntityBuilderTest extends AbstractBuilderTest {
	public static final Currency CZK = Currency.getInstance("CZK");
	public static final Currency EUR = Currency.getInstance("EUR");
	private static final String SORTABLE_ATTRIBUTE = "toSort";
	private static final String BRAND_TYPE = "BRAND";
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
	void shouldRemoveAddedReference() {
		final Entity entityWithBrand = setupEntityWithBrand();

		final SealedEntity updatedInstance = new ExistingEntityBuilder(entityWithBrand)
			.setReference(
				BRAND_TYPE, BRAND_TYPE, Cardinality.ZERO_OR_MORE, 2,
				whichIs -> whichIs.setAttribute("newAttribute", "someValue")
			)
			.removeReference(BRAND_TYPE, 2)
			.toInstance();

		assertEquals(1, updatedInstance.getReferences(BRAND_TYPE).size());
	}

	@Test
	void shouldRemoveExistingReferenceAndAddAgain() {
		final Entity entityWithBrand = setupEntityWithBrand();

		final SealedEntity updatedInstance = new ExistingEntityBuilder(entityWithBrand)
			.removeReference(BRAND_TYPE, 1)
			.setReference(
				BRAND_TYPE, 1,
				whichIs -> whichIs
					.setGroup("Whatever", 8)
					.setAttribute("newAttribute", "someValue")
			)
			.toInstance();

		assertEquals(1, updatedInstance.getReferences(BRAND_TYPE).size());

		updatedInstance.getReference(BRAND_TYPE, 1).ifPresent(reference -> {
			assertEquals("Whatever", reference.getGroup().map(GroupEntityReference::getType).orElse(null));
			assertEquals(8, reference.getGroup().map(GroupEntityReference::getPrimaryKey).orElse(null));
			assertEquals("someValue", reference.getAttribute("newAttribute"));
		});
	}

	@Nonnull
	private Entity setupEntityWithBrand() {
		this.builder.setReference(BRAND_TYPE, BRAND_TYPE, Cardinality.ZERO_OR_ONE, 1, whichIs -> whichIs.setGroup("Whatever", 8));

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
}
