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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.Locale;

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

	public static void assertPrice(Entity updatedInstance, int priceId, String priceList, Currency currency, BigDecimal priceWithoutTax, BigDecimal taxRate, BigDecimal priceWithTax, boolean indexed) {
		final PriceContract price = updatedInstance.getPrice(priceId, priceList, currency).orElseGet(() -> fail("Price not found!"));
		assertEquals(priceWithoutTax, price.getPriceWithoutTax());
		assertEquals(taxRate, price.getTaxRate());
		assertEquals(priceWithTax, price.getPriceWithTax());
		assertEquals(indexed, price.isSellable());
	}

	@BeforeEach
	void setUp() {
		initialEntity = new InitialEntityBuilder("product", 1)
			.setParent(5)
			.setPriceInnerRecordHandling(PriceInnerRecordHandling.FIRST_OCCURRENCE)
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
		this.builder = new ExistingEntityBuilder(initialEntity);
	}

	@Test
	void shouldSkipMutationsThatMeansNoChange() {
		builder
			.setParent(5)
			.setPriceInnerRecordHandling(PriceInnerRecordHandling.FIRST_OCCURRENCE)
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

		assertTrue(builder.toMutation().isEmpty());
	}

	@Test
	void shouldRemoveParent() {
		assertFalse(builder.getParent().isEmpty());
		builder.removeParent();
		assertTrue(builder.getParent().isEmpty());

		final Entity updatedEntity = builder.toMutation()
			.map(it -> it.mutate(initialEntity.getSchema(), initialEntity))
			.orElse(initialEntity);
		assertNotNull(updatedEntity.getParent());
		assertEquals(initialEntity.getVersion() + 1, updatedEntity.getVersion());
		assertTrue(updatedEntity.getParent().isEmpty());
	}

	@Test
	void shouldDefineFacetGroup() {
		builder.setReference(BRAND_TYPE, BRAND_TYPE, Cardinality.ZERO_OR_ONE, 1, whichIs -> whichIs.setGroup("Whatever", 8));

		final EntityMutation entityMutation = builder.toMutation().orElseThrow();
		final Collection<? extends LocalMutation<?, ?>> localMutations = entityMutation.getLocalMutations();
		assertEquals(2, localMutations.size());

		final SealedEntitySchema sealedEntitySchema = new EntitySchemaDecorator(() -> CATALOG_SCHEMA, (EntitySchema) initialEntity.getSchema());
		final EntitySchemaMutation[] schemaMutations = EntityMutation.verifyOrEvolveSchema(
			CATALOG_SCHEMA,
			sealedEntitySchema,
			localMutations
		).orElseThrow();

		final EntitySchemaContract updatedSchema = sealedEntitySchema
			.withMutations(schemaMutations)
			.toInstance();

		final Entity updatedEntity = entityMutation.mutate(updatedSchema, initialEntity);
		final ReferenceContract reference = updatedEntity.getReference(BRAND_TYPE, 1).orElseThrow();
		assertEquals(new GroupEntityReference("Whatever", 8, 1, false), reference.getGroup().orElse(null));
	}

	@Test
	void shouldRemovePriceInnerRecordHandling() {
		builder.removePriceInnerRecordHandling();
		assertEquals(PriceInnerRecordHandling.NONE, builder.getPriceInnerRecordHandling());

		final Entity updatedEntity = builder.toMutation().orElseThrow().mutate(initialEntity.getSchema(), initialEntity);
		assertEquals(PriceInnerRecordHandling.NONE, updatedEntity.getPriceInnerRecordHandling());
	}

	@Test
	void shouldOverwriteParent() {
		builder.setParent(78);
		assertEquals(of(78), builder.getParent());

		final Entity updatedEntity = builder.toMutation().orElseThrow().mutate(initialEntity.getSchema(), initialEntity);
		assertEquals(initialEntity.getVersion() + 1, updatedEntity.getVersion());
		assertEquals(of(78), updatedEntity.getParent());
	}

	@Test
	void shouldOverwritePrices() {
		final Entity updatedInstance = builder
			.setPrice(1, "basic", CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
			.removePrice(2, "reference", CZK)
			.setPrice(5, "vip", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
			.toInstance();

		assertEquals(5, updatedInstance.getPrices().size());
		assertTrue(updatedInstance.getPrice(2, "reference", CZK).map(Droppable::isDropped).orElse(false));
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
		final Entity newEntity = new ExistingEntityBuilder(initialEntity)
			.setParent(5)
			.setPriceInnerRecordHandling(PriceInnerRecordHandling.FIRST_OCCURRENCE)
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

		assertSame(initialEntity, newEntity);
	}

}