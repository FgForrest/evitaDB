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

import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract and behavior of InitialEntityBuilder.
 */
@DisplayName("InitialEntityBuilder tests")
class InitialEntityBuilderTest extends AbstractBuilderTest {
	private static final String SORTABLE_ATTRIBUTE = "toSort";
	private static final String BRAND = "brand";
	private static final String GROUP = "group";
	private static final String BRAND_PRIORITY = "brandPriority";
	private static final String COUNTRY = "country";
	private static final String NAME = "name";
	private static final String MANUAL = "manual";

	@Test
	@DisplayName("Creates new entity with type and no primary key")
	void shouldCreateNewEntity() {
		final InitialEntityBuilder builder = new InitialEntityBuilder("product");
		final Entity product = builder.toMutation().orElseThrow().mutate(builder.getSchema(), null);
		assertNotNull(product);
		assertEquals("product", product.getType());
		// no one has an opportunity to set the primary key (yet)
		assertNull(product.getPrimaryKey());
	}

	@Test
	@DisplayName("Throws on adding array as value for sortable attribute")
	void shouldFailToAddArrayAsSortableAttribute() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAttribute(SORTABLE_ATTRIBUTE, String.class, AttributeSchemaEditor::sortable)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		assertThrows(IllegalArgumentException.class, () -> builder.setAttribute(SORTABLE_ATTRIBUTE, new String[]{"abc", "def"}));
	}

	@Test
	@DisplayName("Includes scope mutation when scope not LIVE")
	void shouldIncludeScopeMutationWhenScopeNotLive() {
		final InitialEntityBuilder builder = new InitialEntityBuilder("product");
		builder.setScope(Scope.ARCHIVED);

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		assertTrue(
			mutation.getLocalMutations().stream().anyMatch(m -> m instanceof SetEntityScopeMutation sm && sm.getScope() == Scope.ARCHIVED),
			"Expected scope mutation ARCHIVED to be present"
		);
	}

	@Test
	@DisplayName("Does not include scope mutation when scope is LIVE")
	void shouldNotIncludeScopeMutationWhenScopeLive() {
		final InitialEntityBuilder builder = new InitialEntityBuilder("product");
		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		assertFalse(
			mutation.getLocalMutations().stream().anyMatch(SetEntityScopeMutation.class::isInstance),
			"Did not expect scope mutation for LIVE scope"
		);
	}

	@Test
	@DisplayName("Includes parent mutation and exposes parent entity when parent is set")
	void shouldIncludeParentMutationAndExposeParentEntityWhenSet() {
		final InitialEntityBuilder builder = new InitialEntityBuilder("product");
		builder.setParent(42);
		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		assertTrue(
			mutation.getLocalMutations().stream().anyMatch(m -> m instanceof SetParentMutation spm && spm.getParentPrimaryKey() == 42),
			"Expected SetParentMutation with PK 42"
		);
		assertTrue(builder.getParentEntity().isPresent());
		assertEquals(42, builder.getParentEntity().orElseThrow().getPrimaryKey());
	}

	@Test
	@DisplayName("Manages references with group and attributes and exposes accessors")
	void shouldManageReferencesWithGroupAndAttributesAndExposeAccessors() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withReferenceToEntity(BRAND, BRAND, Cardinality.ZERO_OR_ONE, ref -> {
				ref.withGroupType(GROUP);
				ref.withAttribute(BRAND_PRIORITY, Long.class, AttributeSchemaEditor::nullable);
				ref.withAttribute(COUNTRY, String.class, AttributeSchemaEditor::localized);
			})
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setReference(BRAND, 5, rb -> {
			rb.setGroup(GROUP, 10);
			rb.setAttribute(BRAND_PRIORITY, 123L);
			rb.setAttribute(COUNTRY, Locale.ENGLISH, "UK");
		});

		// Accessors
		final Set<String> referenceNames = builder.getReferenceNames();
		assertTrue(referenceNames.contains(BRAND));
		assertEquals(1, builder.getReferences(BRAND).size());
		assertEquals(1, builder.getReferenceChunk(BRAND).getData().size());

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		final ReferenceKey rk = new ReferenceKey(BRAND, 5);
		assertTrue(
			mutation.getLocalMutations().stream().anyMatch(m -> m instanceof InsertReferenceMutation irm && irm.getReferenceKey().equals(rk)),
			"Expected InsertReferenceMutation for reference key"
		);
		assertTrue(
			mutation.getLocalMutations().stream().anyMatch(m -> m instanceof SetReferenceGroupMutation srgm && srgm.getReferenceKey().equals(rk) && GROUP.equals(srgm.getGroupType()) && srgm.getGroupPrimaryKey() == 10),
			"Expected SetReferenceGroupMutation for group"
		);
		assertTrue(
			mutation.getLocalMutations().stream().anyMatch(m -> m instanceof ReferenceAttributeMutation ram && ram.getReferenceKey().equals(rk)),
			"Expected at least one ReferenceAttributeMutation"
		);
	}

	@Test
	@DisplayName("Throws when setting unknown reference")
	void shouldThrowWhenSettingUnknownReference() {
		final InitialEntityBuilder builder = new InitialEntityBuilder("product");
		assertThrows(
			ReferenceNotKnownException.class,
			() -> builder.setReference(BRAND, 7)
		);
	}

	@Test
	@DisplayName("Aggregates locales from attributes and associated data")
	void shouldAggregateLocalesFromAttributesAndAssociatedData() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAttribute(NAME, String.class, AttributeSchemaEditor::localized)
			.withAssociatedData(MANUAL, String.class, AssociatedDataSchemaEditor::localized)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setAttribute(NAME, Locale.ENGLISH, "Name EN");
		builder.setAssociatedData(MANUAL, Locale.GERMAN, "Manual DE");

		assertTrue(builder.getAllLocales().contains(Locale.ENGLISH));
		assertTrue(builder.getAllLocales().contains(Locale.GERMAN));
	}

	@Test
	@DisplayName("Includes price mutations when price is set")
	void shouldIncludePriceMutationsWhenPriceSet() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withPrice()
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setPrice(
			1,
			"basic",
			Currency.getInstance("USD"),
			new BigDecimal("10.00"),
			new BigDecimal("20.00"),
			new BigDecimal("30.00"),
			true
		);

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		assertTrue(
			mutation.getLocalMutations().stream().anyMatch(SetPriceInnerRecordHandlingMutation.class::isInstance),
			"Expected SetPriceInnerRecordHandlingMutation to be present"
		);
		assertTrue(
			mutation.getLocalMutations().stream().anyMatch(UpsertPriceMutation.class::isInstance),
			"Expected UpsertPriceMutation to be present"
		);
	}

	@Test
	@DisplayName("Filters null attribute values from attribute upserts")
	void shouldNotUpsertNullAttributeValues() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAttribute("nullableAttr", String.class, AttributeSchemaEditor::nullable)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setAttribute("nullableAttr", (String) null);
		builder.setAttribute("anotherAttr", "value");

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		final long attributeUpserts = mutation.getLocalMutations().stream().filter(
			UpsertAttributeMutation.class::isInstance).count();
		assertEquals(1L, attributeUpserts, "Only non-null attribute values should be upserted");
	}

	@Test
	@DisplayName("Constructs from schema with default scope")
	void shouldConstructFromSchemaWithDefaultScope() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		).toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		assertEquals(schema.getName(), builder.getType());
		assertEquals(schema.getName(), builder.getSchema().getName());
		assertNull(builder.getPrimaryKey());
		assertEquals(Scope.LIVE, builder.getScope());
	}

	@Test
	@DisplayName("Constructs from type and primary key")
	void shouldConstructFromTypeAndPrimaryKey() {
		final InitialEntityBuilder builder = new InitialEntityBuilder("product", 123);
		assertEquals("product", builder.getType());
		assertEquals(123, builder.getPrimaryKey());
		assertEquals(Scope.LIVE, builder.getScope());
	}

	@Test
	@DisplayName("Constructs from schema and primary key")
	void shouldConstructFromSchemaAndPrimaryKey() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		).toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema, 321);
		assertEquals(schema.getName(), builder.getType());
		assertEquals(schema.getName(), builder.getSchema().getName());
		assertEquals(321, builder.getPrimaryKey());
		assertEquals(Scope.LIVE, builder.getScope());
	}

	@Test
	@DisplayName("Preloads state via full constructor")
	void shouldPreloadStateWithFullConstructor() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAttribute("code", String.class)
			.withAttribute("loc", String.class, AttributeSchemaEditor::localized)
			.withAssociatedData("manual", String.class, AssociatedDataSchemaEditor::localized)
			.toInstance();

		final AttributeValue a1 =
			new AttributeValue(
				new AttributeKey("code"),
				"X"
			);
		final AttributeValue a2 =
			new AttributeValue(
				new AttributeKey("loc", Locale.ENGLISH),
				"EN"
			);

		final AssociatedDataValue d1 =
			new AssociatedDataValue(
				new AssociatedDataKey("manual", Locale.ENGLISH),
				"Man EN"
			);

		final Reference ref = new Reference(
			schema,
			"brand",
			7,
			"brand",
			Cardinality.ZERO_OR_ONE,
			null
		);

		final Price price = new Price(
			1,
			"basic",
			Currency.getInstance("USD"),
			null,
			new BigDecimal("10.00"),
			new BigDecimal("20.00"),
			new BigDecimal("30.00"),
			null,
			true
		);

		final InitialEntityBuilder builder = new InitialEntityBuilder(
			schema,
			555,
			Scope.ARCHIVED,
			List.of(a1, a2),
			List.of(d1),
			List.of(ref),
			PriceInnerRecordHandling.NONE,
			List.of(price)
		);

		assertEquals(schema.getName(), builder.getType());
		assertEquals(555, builder.getPrimaryKey());
		assertEquals(Scope.ARCHIVED, builder.getScope());
		assertEquals("X", builder.getAttribute("code"));
		assertEquals("EN", builder.getAttribute("loc", Locale.ENGLISH));
		assertEquals("Man EN", builder.getAssociatedData("manual", Locale.ENGLISH));
		assertTrue(builder.getReferenceNames().contains("brand"));
		assertTrue(builder.getReference("brand", 7).isPresent());
		assertTrue(builder.getAllLocales().contains(Locale.ENGLISH));

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		assertTrue(
			mutation.getLocalMutations().stream().anyMatch(SetPriceInnerRecordHandlingMutation.class::isInstance),
			"Expected SetPriceInnerRecordHandlingMutation to be present"
		);
		assertTrue(
			mutation.getLocalMutations().stream().anyMatch(UpsertPriceMutation.class::isInstance),
			"Expected UpsertPriceMutation to be present"
		);
		assertTrue(
			mutation.getLocalMutations().stream().anyMatch(m -> m instanceof SetEntityScopeMutation sm && sm.getScope() == Scope.ARCHIVED),
			"Expected scope mutation ARCHIVED to be present"
		);
	}
 	@Test
	@DisplayName("Removes parent and does not emit parent mutation")
	void shouldRemoveParentAndNotEmitMutation() {
		final InitialEntityBuilder builder = new InitialEntityBuilder("product");
		builder.setParent(101);
		builder.removeParent();

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		assertFalse(
			mutation.getLocalMutations().stream().anyMatch(SetParentMutation.class::isInstance),
			"Should not emit SetParentMutation after removeParent()"
		);
		assertTrue(builder.getParentEntity().isEmpty());
	}

	@Test
	@DisplayName("Toggles price inner record handling to NONE after remove")
	void shouldHaveNonePriceHandlingAfterRemove() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withPrice()
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM);
		builder.removePriceInnerRecordHandling();

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		final SetPriceInnerRecordHandlingMutation pirh = (SetPriceInnerRecordHandlingMutation) mutation.getLocalMutations()
			.stream().filter(SetPriceInnerRecordHandlingMutation.class::isInstance).findFirst().orElseThrow();
		assertEquals(PriceInnerRecordHandling.NONE, pirh.getPriceInnerRecordHandling());
	}

	@Test
	@DisplayName("Removes one of two prices and keeps the other")
	void shouldRemoveOnlyOnePrice() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withPrice()
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		final Currency usd = Currency.getInstance("USD");
		builder.setPrice(1, "basic", usd, new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true);
		builder.setPrice(2, "special", usd, new BigDecimal("11.00"), new BigDecimal("21.00"), new BigDecimal("31.00"), true);
		builder.removePrice(1, "basic", usd);

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		final long upserts = mutation.getLocalMutations().stream().filter(UpsertPriceMutation.class::isInstance).count();
		assertEquals(1L, upserts, "Expected only one remaining UpsertPriceMutation");
	}

	@Test
	@DisplayName("removeAllNonTouchedPrices keeps all prices in InitialEntityBuilder")
	void shouldKeepAllPricesAfterRemoveAllNonTouched() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withPrice()
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		final Currency usd = Currency.getInstance("USD");
		builder.setPrice(1, "basic", usd, new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true);
		builder.removeAllNonTouchedPrices();

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		final long upserts = mutation.getLocalMutations().stream().filter(UpsertPriceMutation.class::isInstance).count();
		assertEquals(1L, upserts, "Expected price to remain after removeAllNonTouchedPrices()");
	}

	@Test
	@DisplayName("toInstance merges reference names and toggles hierarchy with parent")
	void shouldMergeReferenceNamesAndSetHierarchyWithParent() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_ONE, r -> {})
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setReference("customRef", "customRef", Cardinality.ZERO_OR_ONE, 1);

		final Entity entityWithoutParent = builder.toInstance();
		assertFalse(entityWithoutParent.parentAvailable(), "Parent should not be available without parent and non-hierarchical schema");
		assertTrue(entityWithoutParent.getReferenceNames().contains("brand"));
		assertTrue(entityWithoutParent.getReferenceNames().contains("customRef"));

		builder.setParent(5);
		final Entity entityWithParent = builder.toInstance();
		assertTrue(entityWithParent.parentAvailable(), "Parent should be available when parent is set");
	}

	@Test
	@DisplayName("removeReference removes queued reference and related mutations")
	void shouldRemoveReferenceAndMutations() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_ONE, r -> {})
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setReference("brand", 8);
		builder.removeReference("brand", 8);

		assertTrue(builder.getReference("brand", 8).isEmpty());

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		assertFalse(
			mutation.getLocalMutations().stream().anyMatch(InsertReferenceMutation.class::isInstance),
			"Should not contain InsertReferenceMutation after removing reference"
		);
	}
}
