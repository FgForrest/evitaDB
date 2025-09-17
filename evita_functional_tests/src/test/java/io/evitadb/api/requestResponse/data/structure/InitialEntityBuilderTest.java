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
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException;
import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
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
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.dataType.Scope;
import io.evitadb.function.Functions;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.test.Entities.STORE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract and behavior of InitialEntityBuilder.
 */
@DisplayName("InitialEntityBuilder tests")
class InitialEntityBuilderTest extends AbstractBuilderTest {
	private static final String SORTABLE_ATTRIBUTE = "toSort";
	private static final String BRAND = Entities.BRAND;
	private static final String GROUP = "group";
	private static final String BRAND_PRIORITY = "brandPriority";
	private static final String COUNTRY = "country";
	private static final String NAME = "name";
	private static final String MANUAL = "manual";

	/**
	 * Validates the created references to ensure they conform to expected invariants such as uniqueness and newness of the references.
	 *
	 * @param expected               the expected number of unique references.
	 * @param updatedUniqueReference the list of references to be validated. Each reference is expected to have a unique
	 */
	private static void assertCreatedReferenceInvariants(
		int expected, @Nonnull Collection<ReferenceContract> updatedUniqueReference) {
		assertEquals(
			expected,
			updatedUniqueReference
				.stream()
				.mapToInt(r -> r.getReferenceKey().internalPrimaryKey())
				.distinct()
				.count()
		);
		assertTrue(updatedUniqueReference.stream().allMatch(r -> r.getReferenceKey().isNewReference()));
	}

	/**
	 * Prepares and returns an {@link InitialEntityBuilder} instance initialized with schema references for store, brand,
	 * and group entities. These references include cardinalities that allow zero or more occurrences, with the ability
	 * to specify duplicate entries for some references. Additionally, the builder is preconfigured with specific reference IDs.
	 *
	 * @return an {@link InitialEntityBuilder} initialized with store, brand, and group entity references and pre-set values.
	 */
	@Nonnull
	private static InitialEntityBuilder prepareBuilderWithStoreBrandGroupReferences() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withReferenceToEntity(STORE, STORE, Cardinality.ZERO_OR_MORE, r -> {})
			.withReferenceToEntity(BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, r -> {})
			.withReferenceToEntity(GROUP, GROUP, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, r -> {})
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);

		builder.setReference(STORE, 1);
		builder.setReference(BRAND, 1, ref -> false, Functions.noOpConsumer());
		builder.setReference(BRAND, 1, ref -> false, Functions.noOpConsumer());
		builder.setReference(GROUP, 1, ref -> false, Functions.noOpConsumer());
		builder.setReference(GROUP, 1, ref -> false, Functions.noOpConsumer());
		builder.setReference(GROUP, 1, ref -> false, Functions.noOpConsumer());

		return builder;
	}

	/**
	 * Asserts that the cardinality of a given reference matches the expected cardinality defined in the schema.
	 *
	 * @param cardinality  the expected cardinality to be checked against
	 * @param builder      the {@link InitialEntityBuilder} instance used to retrieve the reference
	 * @param referenceKey the {@link ReferenceKey} representing the reference for which the cardinality is validated
	 */
	static void assertCardinality(
		@Nonnull Cardinality cardinality,
		@Nonnull EntityBuilder builder,
		@Nonnull ReferenceKey referenceKey
	) {
		final Collection<ReferenceContract> references = builder.getReferences(referenceKey.referenceName());
		assertFalse(references.isEmpty());
		for (ReferenceContract reference : references) {
			assertEquals(
				cardinality,
				reference
					.getReferenceSchemaOrThrow()
					.getCardinality()
			);
		}

		final Collection<ReferenceContract> builtReferences = builder.toInstance().getReferences(
			referenceKey.referenceName());
		for (ReferenceContract reference : builtReferences) {
			assertEquals(
				cardinality,
				reference
					.getReferenceSchemaOrThrow()
					.getCardinality()
			);
		}
	}

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
		assertThrows(
			IllegalArgumentException.class, () -> builder.setAttribute(SORTABLE_ATTRIBUTE, new String[]{"abc", "def"}));
	}

	@Test
	@DisplayName("Includes scope mutation when scope not LIVE")
	void shouldIncludeScopeMutationWhenScopeNotLive() {
		final InitialEntityBuilder builder = new InitialEntityBuilder("product");
		builder.setScope(Scope.ARCHIVED);

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		assertTrue(
			mutation.getLocalMutations()
			        .stream()
			        .anyMatch(m -> m instanceof SetEntityScopeMutation sm && sm.getScope() == Scope.ARCHIVED),
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
			mutation.getLocalMutations()
			        .stream()
			        .anyMatch(m -> m instanceof SetParentMutation spm && spm.getParentPrimaryKey() == 42),
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
			.withReferenceToEntity(
				BRAND, BRAND, Cardinality.ZERO_OR_ONE, ref -> {
					ref.withGroupType(GROUP);
					ref.withAttribute(BRAND_PRIORITY, Long.class, AttributeSchemaEditor::nullable);
					ref.withAttribute(COUNTRY, String.class, AttributeSchemaEditor::localized);
				}
			)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setReference(
			BRAND, 5, rb -> {
				rb.setGroup(GROUP, 10);
				rb.setAttribute(BRAND_PRIORITY, 123L);
				rb.setAttribute(COUNTRY, Locale.ENGLISH, "UK");
			}
		);

		// Accessors
		final Set<String> referenceNames = builder.getReferenceNames();
		assertTrue(referenceNames.contains(BRAND));
		assertEquals(1, builder.getReferences(BRAND).size());
		assertEquals(1, builder.getReferenceChunk(BRAND).getData().size());

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		final ReferenceKey rk = new ReferenceKey(BRAND, 5);
		assertTrue(
			mutation.getLocalMutations()
			        .stream()
			        .anyMatch(m -> m instanceof InsertReferenceMutation irm && irm.getReferenceKey().equals(rk)),
			"Expected InsertReferenceMutation for reference key"
		);
		assertTrue(
			mutation.getLocalMutations()
			        .stream()
			        .anyMatch(m -> m instanceof SetReferenceGroupMutation srgm && srgm.getReferenceKey()
			                                                                          .equals(rk) && GROUP.equals(
				        srgm.getGroupType()) && srgm.getGroupPrimaryKey() == 10),
			"Expected SetReferenceGroupMutation for group"
		);
		assertTrue(
			mutation.getLocalMutations()
			        .stream()
			        .anyMatch(m -> m instanceof ReferenceAttributeMutation ram && ram.getReferenceKey().equals(rk)),
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
			ReferencesBuilder.createImplicitSchema(schema, "brand", "brand", Cardinality.ZERO_OR_ONE, null),
			new ReferenceKey(Entities.BRAND, 7),
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
		assertTrue(builder.getReferenceNames().contains(Entities.BRAND));
		assertTrue(builder.getReference(Entities.BRAND, 7).isPresent());
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
			mutation.getLocalMutations()
			        .stream()
			        .anyMatch(m -> m instanceof SetEntityScopeMutation sm && sm.getScope() == Scope.ARCHIVED),
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
		                                                                                               .stream().filter(
				SetPriceInnerRecordHandlingMutation.class::isInstance).findFirst().orElseThrow();
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
		builder.setPrice(
			1, "basic", usd, new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true);
		builder.setPrice(
			2, "special", usd, new BigDecimal("11.00"), new BigDecimal("21.00"), new BigDecimal("31.00"), true);
		builder.removePrice(1, "basic", usd);

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		final long upserts = mutation.getLocalMutations()
		                             .stream()
		                             .filter(UpsertPriceMutation.class::isInstance)
		                             .count();
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
		builder.setPrice(
			1, "basic", usd, new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true);
		builder.removeAllNonTouchedPrices();

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		final long upserts = mutation.getLocalMutations()
		                             .stream()
		                             .filter(UpsertPriceMutation.class::isInstance)
		                             .count();
		assertEquals(1L, upserts, "Expected price to remain after removeAllNonTouchedPrices()");
	}

	@Test
	@DisplayName("toInstance merges reference names and toggles hierarchy with parent")
	void shouldMergeReferenceNamesAndSetHierarchyWithParent() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withReferenceToEntity(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE, r -> {})
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setReference("customRef", "customRef", Cardinality.ZERO_OR_ONE, 1);

		final Entity entityWithoutParent = builder.toInstance();
		assertFalse(
			entityWithoutParent.parentAvailable(),
			"Parent should not be available without parent and non-hierarchical schema"
		);
		assertTrue(entityWithoutParent.getReferenceNames().contains(Entities.BRAND));
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
			.withReferenceToEntity(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE, r -> {})
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setReference(Entities.BRAND, 8);
		builder.removeReference(Entities.BRAND, 8);

		assertTrue(builder.getReference(Entities.BRAND, 8).isEmpty());

		final EntityUpsertMutation mutation = (EntityUpsertMutation) builder.toMutation().orElseThrow();
		assertFalse(
			mutation.getLocalMutations().stream().anyMatch(InsertReferenceMutation.class::isInstance),
			"Should not contain InsertReferenceMutation after removing reference"
		);
	}

	@Test
	@DisplayName("setReference supports add/update and duplicates via filter and builder")
	void shouldHandleSetReferenceAddUpdateAndDuplicates() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withReferenceToEntity(
				BRAND, BRAND, Cardinality.ZERO_OR_ONE,
				ref -> ref.withAttribute(
					BRAND_PRIORITY, Long.class, AttributeSchemaEditor::nullable
				)
			)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);

		/* cardinality cannot be changed on the fly explicitly */
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setReference(
				BRAND,
				BRAND,
				Cardinality.ZERO_OR_MORE,
				1,
				rb -> rb.setAttribute(BRAND_PRIORITY, 10L)
			)
		);

		// A) add new reference (unique key)
		builder.setReference(
			BRAND,
			1,
			rb -> rb.setAttribute(BRAND_PRIORITY, 10L)
		);
		assertTrue(builder.getReference(BRAND, 1).isPresent(), "Expected single reference present for key (brand,1)");

		final Collection<ReferenceContract> createdUniqueReference = builder.getReferences(BRAND);
		assertEquals(1, createdUniqueReference.size());
		assertCreatedReferenceInvariants(1, createdUniqueReference);

		// B) update the existing single reference (filter matches)
		builder.setReference(
			BRAND,
			1,
			ref -> BRAND.equals(ref.getReferenceName()) && ref.getReferencedPrimaryKey() == 1,
			rb -> {
				rb.setAttribute(BRAND_PRIORITY, 11L);
			}
		);
		final List<ReferenceContract> updatedUniqueReference = builder.getReferences(new ReferenceKey(BRAND, 1));
		assertEquals(1, updatedUniqueReference.size(), "Still single after update");
		assertEquals(11L, updatedUniqueReference.get(0).getAttribute(BRAND_PRIORITY, Long.class).longValue());
		assertCreatedReferenceInvariants(1, updatedUniqueReference);

		// C) add duplicate (filter not matching) -> introduce duplication
		builder.setReference(
			BRAND,
			1,
			ref -> false,
			rb -> {
				rb.setAttribute(BRAND_PRIORITY, 12L);
			}
		);
		final List<ReferenceContract> duplicatedReferences = builder.getReferences(new ReferenceKey(BRAND, 1));
		assertEquals(2, duplicatedReferences.size(), "Expected two duplicates");
		assertCreatedReferenceInvariants(2, duplicatedReferences);

		assertThrows(
			ReferenceAllowsDuplicatesException.class,
			() -> builder.getReference(BRAND, 1),
			"Single-get should throw on duplicates"
		);

		// D) update one of the duplicates using filter (target priority 12 -> 13)
		builder.setReference(
			BRAND,
			1,
			ref -> BRAND.equals(ref.getReferenceName()) && ref.getReferencedPrimaryKey() == 1 &&
				Long.valueOf(12L).equals(ref.getAttribute(BRAND_PRIORITY)),
			rb -> {
				rb.setAttribute(BRAND_PRIORITY, 13L);
			}
		);
		final List<ReferenceContract> afterUpdateOnce = builder.getReferences(new ReferenceKey(BRAND, 1));
		final long count13 = afterUpdateOnce.stream()
		                                    .filter(r -> Long.valueOf(13L).equals(r.getAttribute(BRAND_PRIORITY)))
		                                    .count();
		final long count11 = afterUpdateOnce.stream()
		                                    .filter(r -> Long.valueOf(11L).equals(r.getAttribute(BRAND_PRIORITY)))
		                                    .count();
		assertEquals(1L, count13, "Exactly one duplicate should be updated to 13");
		assertEquals(1L, count11, "The other duplicate remains at 11");
		assertCreatedReferenceInvariants(2, afterUpdateOnce);

		// E) add another duplicate when duplicates already exist (filter not matching)
		builder.setReference(
			BRAND,
			1,
			ref -> false,
			rb -> {
				rb.setAttribute(BRAND_PRIORITY, 14L);
			}
		);
		final List<ReferenceContract> threeDuplicates = builder.getReferences(new ReferenceKey(BRAND, 1));
		assertEquals(3, threeDuplicates.size(), "Expected three duplicates now");
		assertCreatedReferenceInvariants(3, threeDuplicates);

		// F) update an already existing duplicate (target 11 -> 15)
		builder.setReference(
			BRAND,
			1,
			ref -> Long.valueOf(11L).equals(ref.getAttribute(BRAND_PRIORITY)),
			rb -> {
				rb.setAttribute(BRAND_PRIORITY, 15L);
			}
		);
		final List<ReferenceContract> finalRefs = builder.getReferences(new ReferenceKey(BRAND, 1));
		final long count15 = finalRefs.stream()
		                              .filter(r -> Long.valueOf(15L).equals(r.getAttribute(BRAND_PRIORITY)))
		                              .count();
		assertEquals(1L, count15, "Exactly one duplicate should be updated to 15");
		assertEquals(3, finalRefs.size(), "Count unchanged after targeted update");
		assertCreatedReferenceInvariants(3, finalRefs);
	}

	@Test
	@DisplayName("Updates multiple matching references using predicate")
	void shouldUpdateMultipleReferencesByPredicate() {
		final String targetEntityType = "supplier";
		final String attributeLoc = "loc";
		final String attributePriority = "priority";

		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withReferenceToEntity(
				Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE, r -> {
					r.withAttribute(attributePriority, Integer.class, AttributeSchemaEditor::nullable);
					r.withAttribute(attributeLoc, String.class, AttributeSchemaEditor::localized);
				}
			)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		// seed references: two brands and one different ref name
		builder.setReference(
			Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE, 1,
			rb -> rb.setAttribute(attributePriority, 1)
		);
		builder.setReference(
			Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE, 2,
			rb -> rb.setAttribute(attributePriority, 2)
		);
		builder.setReference(targetEntityType, targetEntityType, Cardinality.ZERO_OR_ONE, 5);

		// update only brand references to set localized attribute
		builder.updateReferences(
			ref -> Entities.BRAND.equals(ref.getReferenceName()),
			refb -> {
				refb.setAttribute(attributeLoc, Locale.ENGLISH, "EN");
			}
		);

		// both brand refs should now have localized attribute while supplier remains untouched
		assertEquals(2, builder.getReferences(Entities.BRAND).size());
		for (ReferenceContract rc : builder.getReferences(Entities.BRAND)) {
			assertEquals("EN", rc.getAttribute(attributeLoc, Locale.ENGLISH));
		}
		assertTrue(builder.getReference(targetEntityType, 5).isPresent());

		final ReferenceContract newReferenceWithImplicitSchema = builder.getReference(targetEntityType, 5)
		                                                                .orElseThrow();
		assertNotNull(newReferenceWithImplicitSchema);
		assertThrows(
			AttributeNotFoundException.class,
			() -> newReferenceWithImplicitSchema.getAttribute(attributeLoc, Locale.ENGLISH)
		);
	}

	@Test
	@DisplayName("Updates only references satisfying complex predicate")
	void shouldUpdateOnlySelectedReferences() {
		final String attributePriority = "priority";
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withReferenceToEntity(
				Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE, r -> {
					r.withAttribute(attributePriority, Integer.class, AttributeSchemaEditor::nullable);
				}
			)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setReference(
			Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE, 1,
			rb -> rb.setAttribute(attributePriority, 1)
		);
		builder.setReference(
			Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE, 2,
			rb -> rb.setAttribute(attributePriority, 2)
		);
		builder.setReference(
			Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE, 3,
			rb -> rb.setAttribute(attributePriority, 3)
		);

		// update only those with priority >= 2
		builder.updateReferences(
			ref -> Entities.BRAND.equals(ref.getReferenceName()) && ref.getAttribute(
				attributePriority, Integer.class) >= 2,
			refb -> {
				refb.setAttribute(attributePriority, 100);
			}
		);

		assertEquals(
			100,
			builder.getReference(new ReferenceKey(Entities.BRAND, 2))
			       .orElseThrow()
			       .getAttribute(attributePriority, Integer.class)
			       .intValue()
		);
		assertEquals(
			100,
			builder.getReference(new ReferenceKey(Entities.BRAND, 3))
			       .orElseThrow()
			       .getAttribute(attributePriority, Integer.class)
			       .intValue()
		);
		assertEquals(
			1,
			builder.getReference(new ReferenceKey(Entities.BRAND, 1))
			       .orElseThrow()
			       .getAttribute(attributePriority, Integer.class)
			       .intValue()
		);
	}

	@Test
	@DisplayName("removeReference(ReferenceKey) removes only uniquely identified reference; throws on unknown internal PK when duplicates; no-op when key not known")
	void shouldRemoveReferenceOnlyWhenUniquelyIdentifiedOrThrowOnUnknownInternalPk() {
		final InitialEntityBuilder builder = prepareBuilderWithStoreBrandGroupReferences();

		// remove single non-conflicting reference - should succeed
		assertFalse(builder.getReference(STORE, 1).isEmpty());
		builder.removeReference(new ReferenceKey(STORE, 1));
		assertTrue(builder.getReference(STORE, 1).isEmpty(), "Reference STORE, 1 should be removed");

		// remove one of the duplicates - should succeed when using internal PK
		final List<ReferenceContract> duplicates = builder.getReferences(new ReferenceKey(BRAND, 1));
		assertEquals(2, duplicates.size(), "Expected two duplicates before removals");

		final int firstInternalPk = duplicates.get(0).getReferenceKey().internalPrimaryKey();
		final int secondInternalPk = duplicates.get(1).getReferenceKey().internalPrimaryKey();
		assertNotEquals(firstInternalPk, secondInternalPk, "Internal PKs of duplicates must differ");

		// unknown internal PK (0) with duplicates -> expect exception and no change
		assertThrows(
			ReferenceAllowsDuplicatesException.class,
			() -> builder.removeReference(new ReferenceKey(BRAND, 1)),
			"Should throw when trying to remove by key with unknown internal PK while duplicates exist"
		);
		assertEquals(
			2, builder.getReferences(new ReferenceKey(BRAND, 1)).size(), "State must remain unchanged after exception");

		// non-existent key -> no-op
		builder.removeReference(new ReferenceKey(BRAND, 2));
		assertEquals(
			2, builder.getReferences(new ReferenceKey(BRAND, 1)).size(),
			"No change expected when removing non-existent key"
		);

		// remove one of the duplicates using its internal PK -> should succeed and leave single reference
		builder.removeReference(new ReferenceKey(BRAND, 1, firstInternalPk));
		final List<ReferenceContract> remaining = builder.getReferences(new ReferenceKey(BRAND, 1));
		assertEquals(1, remaining.size(), "Exactly one reference should remain after targeted removal");
		assertEquals(
			secondInternalPk,
			remaining.get(0).getReferenceKey().internalPrimaryKey(),
			"Remaining reference should match the other internal PK"
		);

		// Remove one of three GROUP references using internal PK
		final List<ReferenceContract> groupRefs = builder.getReferences(new ReferenceKey(GROUP, 1));
		assertEquals(3, groupRefs.size(), "Expected three GROUP references initially");
		final int firstGroupInternalPk = groupRefs.get(0).getReferenceKey().internalPrimaryKey();

		builder.removeReference(new ReferenceKey(GROUP, 1, firstGroupInternalPk));
		final List<ReferenceContract> remainingGroups = builder.getReferences(new ReferenceKey(GROUP, 1));
		assertEquals(2, remainingGroups.size(), "Two GROUP references should remain after removal");
		assertFalse(
			remainingGroups.stream()
			               .anyMatch(r -> r.getReferenceKey().internalPrimaryKey() == firstGroupInternalPk),
			"Removed reference should not be present"
		);

		// Single-get should still throw due to remaining duplicates
		assertThrows(
			ReferenceAllowsDuplicatesException.class,
			() -> builder.getReference(GROUP, 1),
			"Single-get should still throw with remaining duplicates"
		);
	}

	@Test
	@DisplayName("removeReferences(name, id) removes all references for unique and duplicate cases")
	void shouldRemoveReferencesByNameAndReferencedIdInAllCases() {
		final InitialEntityBuilder builder = prepareBuilderWithStoreBrandGroupReferences();

		// STORE: single occurrence -> remove all by name+id
		assertTrue(builder.getReference(STORE, 1).isPresent());
		builder.removeReferences(STORE, 1);
		assertTrue(builder.getReference(STORE, 1).isEmpty());
		assertEquals(0, builder.getReferences(new ReferenceKey(STORE, 1)).size());

		// BRAND: two duplicates -> remove all by name+id
		assertEquals(2, builder.getReferences(new ReferenceKey(BRAND, 1)).size());
		builder.removeReferences(BRAND, 1);
		assertTrue(builder.getReference(BRAND, 1).isEmpty());
		assertEquals(0, builder.getReferences(new ReferenceKey(BRAND, 1)).size());

		// GROUP: three duplicates -> remove all by name+id
		assertEquals(3, builder.getReferences(new ReferenceKey(GROUP, 1)).size());
		builder.removeReferences(GROUP, 1);
		assertTrue(builder.getReference(GROUP, 1).isEmpty());
		assertEquals(0, builder.getReferences(new ReferenceKey(GROUP, 1)).size());
	}

	@Test
	@DisplayName("removeReferences(name) removes all references for the given name regardless of id and duplicates")
	void shouldRemoveAllReferencesByNameRegardlessOfId() {
		final InitialEntityBuilder builder = prepareBuilderWithStoreBrandGroupReferences();

		// initial state checks
		assertTrue(builder.getReference(STORE, 1).isPresent());
		assertEquals(2, builder.getReferences(new ReferenceKey(BRAND, 1)).size());
		assertEquals(3, builder.getReferences(new ReferenceKey(GROUP, 1)).size());

		// remove all BRAND references by name
		builder.removeReferences(BRAND);
		assertTrue(builder.getReference(BRAND, 1).isEmpty(), "All BRAND references should be removed");
		assertEquals(0, builder.getReferences(new ReferenceKey(BRAND, 1)).size());
		// other references should remain
		assertTrue(builder.getReference(STORE, 1).isPresent(), "STORE should remain after removing BRAND");
		assertEquals(3, builder.getReferences(new ReferenceKey(GROUP, 1)).size(), "GROUP should remain");

		// remove all GROUP references by name
		builder.removeReferences(GROUP);
		assertTrue(builder.getReference(GROUP, 1).isEmpty(), "All GROUP references should be removed");
		assertEquals(0, builder.getReferences(new ReferenceKey(GROUP, 1)).size());
		// STORE should still remain
		assertTrue(builder.getReference(STORE, 1).isPresent(), "STORE should still remain");

		// finally remove STORE by name
		builder.removeReferences(STORE);
		assertTrue(builder.getReference(STORE, 1).isEmpty(), "STORE should be removed as well");
	}

	@Test
	@DisplayName("removeReferences(predicate) removes exactly one reference by internal id for each name")
	void shouldRemoveExactlyOneReferenceByInternalIdUsingPredicate() {
		final InitialEntityBuilder builder = prepareBuilderWithStoreBrandGroupReferences();

		// STORE: single occurrence -> remove exactly this one by internal id
		final List<ReferenceContract> storeRefsBefore = builder.getReferences(new ReferenceKey(STORE, 1));
		assertEquals(1, storeRefsBefore.size(), "Expected single STORE reference initially");
		final int storeInternalPk = storeRefsBefore.get(0).getReferenceKey().internalPrimaryKey();
		builder.removeReferences(
			ref -> STORE.equals(ref.getReferenceName()) &&
				ref.getReferenceKey().internalPrimaryKey() == storeInternalPk
		);
		assertEquals(
			0,
			builder.getReferences(new ReferenceKey(STORE, 1)).size(),
			"STORE reference should be fully removed"
		);
		assertTrue(
			builder.getReference(STORE, 1).isEmpty(),
			"Single-get should return empty for STORE after removal"
		);

		// BRAND: two duplicates -> remove exactly one by internal id
		final List<ReferenceContract> brandRefsBefore = builder.getReferences(new ReferenceKey(BRAND, 1));
		assertEquals(2, brandRefsBefore.size(), "Expected two BRAND references initially");
		final int brandInternalPkToRemove = brandRefsBefore.get(0).getReferenceKey().internalPrimaryKey();
		final int brandInternalPkToRemain = brandRefsBefore.get(1).getReferenceKey().internalPrimaryKey();
		assertNotEquals(
			brandInternalPkToRemove, brandInternalPkToRemain,
			"Internal PKs of BRAND duplicates must differ"
		);
		builder.removeReferences(
			ref -> BRAND.equals(ref.getReferenceName()) &&
				ref.getReferenceKey().internalPrimaryKey() == brandInternalPkToRemove
		);
		final List<ReferenceContract> brandRefsAfter = builder.getReferences(new ReferenceKey(BRAND, 1));
		assertEquals(1, brandRefsAfter.size(), "Exactly one BRAND reference should remain");
		assertEquals(
			brandInternalPkToRemain,
			brandRefsAfter.get(0).getReferenceKey().internalPrimaryKey(),
			"Remaining BRAND reference should be the other internal PK"
		);

		// GROUP: three duplicates -> remove exactly one by internal id
		final List<ReferenceContract> groupRefsBefore = builder.getReferences(new ReferenceKey(GROUP, 1));
		assertEquals(3, groupRefsBefore.size(), "Expected three GROUP references initially");
		final int groupInternalPkToRemove = groupRefsBefore.get(0).getReferenceKey().internalPrimaryKey();
		builder.removeReferences(
			ref -> GROUP.equals(ref.getReferenceName()) &&
				ref.getReferenceKey().internalPrimaryKey() == groupInternalPkToRemove
		);
		final List<ReferenceContract> groupRefsAfter = builder.getReferences(new ReferenceKey(GROUP, 1));
		assertEquals(2, groupRefsAfter.size(), "Two GROUP references should remain after removal");
		assertFalse(
			groupRefsAfter.stream().anyMatch(
				r -> r.getReferenceKey().internalPrimaryKey() == groupInternalPkToRemove
			), "Removed GROUP reference should not be present"
		);
		// Single-get should still throw due to remaining duplicates
		assertThrows(
			ReferenceAllowsDuplicatesException.class,
			() -> builder.getReference(GROUP, 1),
			"Single-get should still throw with remaining GROUP duplicates"
		);
	}

	@Test
	@DisplayName("gradually promote reference cardinality from unique to duplicates")
	void shouldGraduallyPromoteReferenceCardinality() {
		// default evolution strategy is ALLOW all
		final InitialEntityBuilder builder = new InitialEntityBuilder("product", 100);
		builder.setReference(BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1);
		assertTrue(builder.getReference(new ReferenceKey(BRAND, 1)).isPresent());
		assertCardinality(Cardinality.ZERO_OR_ONE, builder, new ReferenceKey(BRAND, 1));

		// promote to ZERO_OR_MORE
		builder.setReference(BRAND, 2);
		assertTrue(builder.getReference(new ReferenceKey(BRAND, 1)).isPresent());
		assertCardinality(Cardinality.ZERO_OR_MORE, builder, new ReferenceKey(BRAND, 1));

		// promote to ZERO_OR_MORE_WITH_DUPLICATES
		builder.setReference(
			BRAND,
			2,
			ref -> false,
			Functions.noOpConsumer()
		);
		/* cannot change referenced entity type this way */
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setReference(
				BRAND,
				"differentEntityType",
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				2,
				ref -> false,
				Functions.noOpConsumer()
			)
		);
		/* cannot change cardinality this way */
		assertThrows(
			InvalidMutationException.class,
			() -> {
				builder.setReference(
					BRAND,
					BRAND,
					Cardinality.ONE_OR_MORE,
					2,
					ref -> false,
					Functions.noOpConsumer()
				);
			}
		);
		assertThrows(
			ReferenceAllowsDuplicatesException.class,
			() -> builder.getReference(new ReferenceKey(BRAND, 1))
		);
		assertThrows(
			ReferenceAllowsDuplicatesException.class,
			() -> builder.getReference(BRAND, 1)
		);
		assertThrows(
			ReferenceAllowsDuplicatesException.class,
			() -> builder.setReference(BRAND, 5)
		);
		assertCardinality(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, builder, new ReferenceKey(BRAND, 1));

		// add another duplicate
		builder.setReference(
			BRAND,
			3,
			ref -> false,
			Functions.noOpConsumer()
		);

		checkCollectionBrands(builder.getReferences(BRAND), 1, 2, 2, 3);
		checkCollectionBrands(builder.toInstance().getReferences(BRAND), 1, 2, 2, 3);
	}

	@Test
	void shouldAllowAddingNewDefinitions() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(EvolutionMode.values())
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		// add parent
		builder.setParent(1);
		// add attribute
		builder.setAttribute("code", "X");
		// add localized attribute
		builder.setAttribute("loc", Locale.ENGLISH, "EN");
		builder.setAttribute("loc", Locale.FRENCH, "FR");
		// add associated data
		builder.setAssociatedData("manual", Locale.ENGLISH, "Manual EN");
		// add reference with attribute
		builder.setReference(
			BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1,
			whichIs -> whichIs.setAttribute("priority", 1)
		);
		// automatically elevate cardinality to ZERO_OR_MORE
		builder.setReference(
			BRAND, 2,
			whichIs -> whichIs.setAttribute("priority", 2)
		);
		// set price inner record handling
		builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM);
		// add price
		builder.setPrice(1, "basic", Currency.getInstance("USD"), new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true);

		// assert everything is present
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

		// assert everything is present in the built entity
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
	void shouldKeepAttributeSchemaConsistentOverMultipleReferences() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(EvolutionMode.values())
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		// add reference with attribute
		builder.setReference(
			BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1,
			whichIs -> whichIs.setAttribute("priority", 1)
		);
		// attempt to set attribute as localized should fail
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setReference(
				BRAND, 2,
				whichIs -> whichIs.setAttribute("priority", Locale.ENGLISH, 2)
			)
		);
		// attempt to set attribute as different type should fail
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setReference(
				BRAND, 2,
				whichIs -> whichIs.setAttribute("priority", "2")
			)
		);
	}

	@Test
	void shouldDenyExternalPrimaryKey() {

		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaStrictly()
			.withGeneratedPrimaryKey()
			.toInstance();

		assertThrows(
			InvalidMutationException.class,
			() -> new InitialEntityBuilder(schema, 1)
		);
	}

	@Test
	void shouldDenyGeneratedPrimaryKey() {

		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaStrictly()
			.withoutGeneratedPrimaryKey()
			.toInstance();

		assertThrows(
			InvalidMutationException.class,
			() -> new InitialEntityBuilder(schema)
		);
	}

	@Test
	void shouldDenyAddingAttributes() {

		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION,
				EvolutionMode.ADDING_REFERENCES
			)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema, 1);
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setAttribute("code", "X")
		);
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setReference(
				BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1,
				whichIs -> whichIs.setAttribute("priority", 1)
			)
		);
	}

	@Test
	void shouldDenyAddingAssociatedData() {

		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION
			)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema, 1);
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setAssociatedData("key", "value")
		);
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setAssociatedData("key", Locale.ENGLISH, "value")
		);
	}

	@Test
	void shouldDenyAddingLanguage() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION,
				EvolutionMode.ADDING_REFERENCES,
				EvolutionMode.ADDING_ATTRIBUTES,
				EvolutionMode.ADDING_ASSOCIATED_DATA
			)
			.withLocale(Locale.ENGLISH)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema, 1);
		// this should work - adding data in existing language
		builder.setAttribute("code", Locale.ENGLISH, "X");
		builder.setAssociatedData("whatever", Locale.ENGLISH, "X");
		// this should fail - adding data in non-supported language
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setAttribute("code", Locale.GERMAN, "X")
		);
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setAssociatedData("whatever", Locale.GERMAN, "X")
		);
		// this should work - adding data in existing language
		builder.setReference(
			BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1,
			whichIs -> whichIs.setAttribute("priority", Locale.ENGLISH, 1)
		);
		// this should fail - adding data in non-supported language
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setReference(
				BRAND, BRAND, Cardinality.ZERO_OR_ONE, 2,
				whichIs -> whichIs.setAttribute("priority", Locale.GERMAN, 1)
			)
		);
	}

	@Test
	void shouldDenyAddingParent() {

		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION
			)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema, 1);
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setParent(1)
		);
	}

	@Test
	void shouldDenyAddingPrice() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION
			)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema, 1);
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM)
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
	void shouldDenyAddingPriceCurrency() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION
			)
			.withPriceInCurrency(DataGenerator.CURRENCY_CZK)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema, 1);
		// adding price in allowed currency should work
		builder.setPrice(
			1, "basic", DataGenerator.CURRENCY_CZK,
			new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true
		);
		// adding price in non-allowed currency should fail
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setPrice(
				1, "basic", Currency.getInstance("USD"),
				new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), true
			)
		);
	}

	@Test
	void shouldDenyAddingReference() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION
			)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema, 1);
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setReference(BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1)
		);
	}

	@Test
	void shouldDenyElevatingCardinality() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION,
				EvolutionMode.ADDING_REFERENCES
			)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema, 1);
		builder.setReference(BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1);

		assertThrows(
			InvalidMutationException.class,
			() -> builder.setReference(BRAND, 2)
		);
	}

	private static void checkCollectionBrands(Collection<ReferenceContract> references, int... expectedPks) {
		assertEquals(expectedPks.length, references.size());

		int i = 0;
		for (ReferenceContract ref : references) {
			assertEquals(expectedPks[i++], ref.getReferencedPrimaryKey());
		}
	}
}
