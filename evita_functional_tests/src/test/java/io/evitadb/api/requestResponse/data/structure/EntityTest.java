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

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
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
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Entity class: internal builders and mutateEntity behavior.
 */
@DisplayName("Entity tests")
class EntityTest extends AbstractBuilderTest {
	private static final String BRAND = "brand";
	private static final String GROUP = "group";
	private static final String BRAND_PRIORITY = "brandPriority";
	private static final String COUNTRY = "country";
	private static final String NAME = "name";
	private static final String QTY = "qty";
	private static final String MANUAL = "manual";

	@Test
	@DisplayName("marker should have proper toString implementation")
	void shouldRenderToString() {
		assertEquals("DUPLICATE_REFERENCE_MARKER", Entity.DUPLICATE_REFERENCE.toString());
	}

	@Test
	@DisplayName("_internalBuild overload with nullable version and scope")
	void shouldBuildEntityWithInternalBuildOverload1() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withReferenceToEntity(BRAND, BRAND, Cardinality.ZERO_OR_ONE, r -> {})
			.toInstance();

		final EntityAttributes attributes = new EntityAttributes(schema);
		final AssociatedData associatedData = new AssociatedData(schema);
		final Prices prices = new Prices(schema, 1, Collections.emptyList(), PriceInnerRecordHandling.NONE, false);
		final ReferenceContract ref = new Reference(
			schema,
			Reference.createImplicitSchema(BRAND, BRAND, Cardinality.ZERO_OR_ONE, null),
			new ReferenceKey(BRAND, 5), null
		);
		final Set<Locale> locales = Set.of(Locale.ENGLISH);

		final Entity.ChunkTransformerAccessor accessor = Entity.DEFAULT_CHUNK_TRANSFORMER;

		final Entity e = Entity._internalBuild(
			123,
			null,
			schema,
			null,
			List.of(ref),
			attributes,
			associatedData,
			prices,
			locales,
			Scope.LIVE,
			accessor
		);

		assertEquals(1, e.version());
		assertEquals(schema.getName(), e.getType());
		assertEquals(123, e.getPrimaryKey());
		assertEquals(Scope.LIVE, e.getScope());
		assertEquals(locales, e.getAllLocales());
		assertTrue(e.getReferenceNames().contains(BRAND));
		assertEquals(1, e.getReferences().size());
		assertTrue(e.getReference(BRAND, 5).isPresent());
	}

	@Test
	@DisplayName("_internalBuild overload with explicit referencesDefined, hierarchy and dropped")
	void shouldBuildEntityWithInternalBuildOverload2() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.toInstance();

		final ReferenceContract ref = new Reference(
			schema,
			Reference.createImplicitSchema(BRAND, BRAND, Cardinality.ZERO_OR_ONE, null),
			new ReferenceKey(BRAND, 5),
			null
		);
		final EntityAttributes attributes = new EntityAttributes(schema);
		final AssociatedData associatedData = new AssociatedData(schema);
		final Prices prices = new Prices(schema, 1, Collections.emptyList(), PriceInnerRecordHandling.NONE, false);
		final Set<Locale> locales = Set.of(Locale.ENGLISH);

		final Set<String> referencesDefined = new HashSet<>(Arrays.asList(BRAND, "customRef"));

		final Entity e = Entity._internalBuild(
			321,
			2,
			schema,
			42,
			List.of(ref),
			attributes,
			associatedData,
			prices,
			locales,
			referencesDefined,
			true,
			true
		);

		assertEquals(2, e.version());
		assertEquals(321, e.getPrimaryKey());
		assertTrue(e.getReferenceNames().contains(BRAND));
		assertTrue(e.getReferenceNames().contains("customRef"));
		assertTrue(e.parentAvailable());
		assertTrue(e.dropped());
	}

	@Test
	@DisplayName("_internalBuild overload with primitive version/pk and scope+chunk transformer")
	void shouldBuildEntityWithInternalBuildOverload3() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withReferenceToEntity(BRAND, BRAND, Cardinality.ZERO_OR_ONE)
			.toInstance();

		final ReferenceContract ref = new Reference(
			schema,
			Reference.createImplicitSchema(BRAND, BRAND, Cardinality.ZERO_OR_ONE, null),
			new ReferenceKey(BRAND, 6),
			null
		);
		final EntityAttributes attributes = new EntityAttributes(schema);
		final AssociatedData associatedData = new AssociatedData(schema);
		final Prices prices = new Prices(schema, 1, Collections.emptyList(), PriceInnerRecordHandling.NONE, false);
		final Set<Locale> locales = Set.of(Locale.ENGLISH);

		final Entity.ChunkTransformerAccessor accessor = Entity.DEFAULT_CHUNK_TRANSFORMER;

		final Entity e = Entity._internalBuild(
			3,
			777,
			schema,
			null,
			List.of(ref),
			attributes,
			associatedData,
			prices,
			locales,
			Scope.ARCHIVED,
			false,
			accessor
		);

		assertEquals(3, e.version());
		assertEquals(777, e.getPrimaryKey());
		assertEquals(Scope.ARCHIVED, e.getScope());
		assertTrue(e.getReference(BRAND, 6).isPresent());
	}

	@Test
	@DisplayName("_internalBuild overload copying from existing entity with overrides")
	void shouldBuildEntityWithInternalBuildOverload4() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withHierarchy()
			.withReferenceToEntity(BRAND, BRAND, Cardinality.ZERO_OR_ONE, r -> {})
			.toInstance();

		final Entity base = new InitialEntityBuilder(schema).toInstance();
		final Entity.ChunkTransformerAccessor accessor = Entity.DEFAULT_CHUNK_TRANSFORMER;

		final Entity e = Entity._internalBuild(
			base,
			base.version() + 5,
			888,
			schema,
			99,
			Collections.emptyList(),
			null,
			null,
			null,
			null,
			Scope.LIVE,
			false,
			accessor
		);

		assertEquals(base.version() + 5, e.version());
		assertEquals(888, e.getPrimaryKey());
		assertEquals(99, e.getParent().orElseThrow());
	}

	@Test
	@DisplayName("mutateEntity applies all mutation types and updates state")
	void shouldMutateEntityWithAllMutationTypes() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withHierarchy()
			.withAttribute(QTY, Integer.class)
			.withAttribute(NAME, String.class, AttributeSchemaEditor::localized)
			.withAssociatedData(MANUAL, String.class, AssociatedDataSchemaEditor::localized)
			.withReferenceToEntity(
				BRAND, BRAND, Cardinality.ZERO_OR_ONE, ref -> {
					ref.withGroupType(GROUP);
					ref.withAttribute(BRAND_PRIORITY, Long.class, AttributeSchemaEditor::nullable);
					ref.withAttribute(COUNTRY, String.class, AttributeSchemaEditor::localized);
				}
			)
			.withPrice()
			.toInstance();

		final Entity base = new InitialEntityBuilder(schema).toInstance();

		final List<LocalMutation<?, ?>> mutations = new ArrayList<>();
		// attributes
		mutations.add(new UpsertAttributeMutation(QTY, 10));
		mutations.add(new ApplyDeltaAttributeMutation<>(QTY, 5)); // -> 15
		mutations.add(new UpsertAttributeMutation(NAME, Locale.ENGLISH, "Phone"));
		// associated data
		mutations.add(new UpsertAssociatedDataMutation(new AssociatedDataKey(MANUAL, Locale.ENGLISH), "Manual EN"));
		// references
		final ReferenceKey rk = new ReferenceKey(BRAND, 7);
		mutations.add(new InsertReferenceMutation(rk, Cardinality.ZERO_OR_ONE, BRAND));
		mutations.add(new SetReferenceGroupMutation(rk, GROUP, 3));
		mutations.add(new ReferenceAttributeMutation(rk, new UpsertAttributeMutation(BRAND_PRIORITY, 123L)));
		mutations.add(new ReferenceAttributeMutation(rk, new UpsertAttributeMutation(COUNTRY, Locale.ENGLISH, "UK")));
		// prices
		mutations.add(new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.SUM));
		mutations.add(new UpsertPriceMutation(
			1, "basic", Currency.getInstance("USD"), null,
			new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"), null, true
		));
		// parent + scope
		mutations.add(new SetParentMutation(5));
		mutations.add(new SetEntityScopeMutation(Scope.ARCHIVED));

		final Entity mutated = Entity.mutateEntity(schema, base, mutations);

		assertEquals(base.version() + 1, mutated.version());
		assertEquals(15, mutated.getAttribute(QTY, Integer.class));
		assertEquals("Phone", mutated.getAttribute(NAME, Locale.ENGLISH));
		assertEquals("Manual EN", mutated.getAssociatedData(MANUAL, Locale.ENGLISH));
		assertTrue(mutated.getReferenceNames().contains(BRAND));
		assertTrue(mutated.getReference(rk).isPresent());
		final ReferenceContract mref = mutated.getReference(rk).orElseThrow();
		assertTrue(mref.getGroup().isPresent());
		assertEquals(3, mref.getGroup().orElseThrow().primaryKey());
		assertEquals(123L, ((Number) mref.getAttribute(BRAND_PRIORITY)).longValue());
		assertEquals("UK", mref.getAttribute(COUNTRY, Locale.ENGLISH));
		final Optional<PriceContract> price = mutated.getPrice(1, "basic", Currency.getInstance("USD"));
		assertTrue(price.isPresent());
		assertEquals(PriceInnerRecordHandling.SUM, mutated.getPriceInnerRecordHandling());
		assertTrue(mutated.getAllLocales().contains(Locale.ENGLISH));
		assertEquals(Scope.ARCHIVED, mutated.getScope());
		assertTrue(mutated.getParent().isPresent());
		assertEquals(5, mutated.getParent().orElseThrow());
	}

	@Test
	@DisplayName("mutateEntity removes data with remove mutations")
	void shouldRemoveDataWithRemoveMutations() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withHierarchy()
			.withAttribute(NAME, String.class, AttributeSchemaEditor::localized)
			.withAssociatedData(
				MANUAL, String.class, AssociatedDataSchemaEditor::localized)
			.withReferenceToEntity(BRAND, BRAND, Cardinality.ZERO_OR_ONE, ref -> ref.withGroupType(GROUP))
			.withPrice()
			.toInstance();

		// prepare entity with data
		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setAttribute(NAME, Locale.ENGLISH, "X");
		builder.setAssociatedData(MANUAL, Locale.ENGLISH, "Y");
		builder.setReference(BRAND, 7, rb -> rb.setGroup(GROUP, 3));
		builder.setPrice(
			1, "basic", Currency.getInstance("USD"), new BigDecimal("10.00"), new BigDecimal("20.00"),
			new BigDecimal("30.00"), true
		);
		builder.setParent(8);
		final Entity prepared = builder.toMutation().orElseThrow().mutate(schema, null);

		final List<LocalMutation<?, ?>> removals = List.of(
			new RemoveAttributeMutation(new AttributeKey(NAME, Locale.ENGLISH)),
			new RemoveAssociatedDataMutation(new AssociatedDataKey(MANUAL, Locale.ENGLISH)),
			new RemoveReferenceGroupMutation(new ReferenceKey(BRAND, 7)),
			new RemoveReferenceMutation(new ReferenceKey(BRAND, 7)),
			new RemovePriceMutation(1, "basic", Currency.getInstance("USD")),
			new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.NONE),
			new RemoveParentMutation(),
			new SetEntityScopeMutation(Scope.LIVE)
		);

		final Entity mutated = Entity.mutateEntity(schema, prepared, removals);

		assertTrue(mutated.getReference(new ReferenceKey(BRAND, 7)).filter(Droppable::exists).isEmpty());
		assertTrue(mutated.getAssociatedDataValue(new AssociatedDataKey(MANUAL, Locale.ENGLISH)).filter(Droppable::exists).isEmpty());
		assertTrue(mutated.getAttributeValue(new AttributeKey(NAME, Locale.ENGLISH)).filter(Droppable::exists).isEmpty());
		assertTrue(mutated.getPrice(1, "basic", Currency.getInstance("USD")).filter(Droppable::exists).isEmpty());
		assertEquals(PriceInnerRecordHandling.NONE, mutated.getPriceInnerRecordHandling());
		assertTrue(mutated.getParent().isEmpty());
		assertEquals(Scope.LIVE, mutated.getScope());
	}

	@Test
	@DisplayName("mutateEntity returns same instance when no changes are produced")
	void shouldReturnSameEntityWhenNoChanges() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.withAttribute(NAME, String.class, AttributeSchemaEditor::localized)
			.toInstance();

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema);
		builder.setAttribute(NAME, Locale.ENGLISH, "Name");
		final Entity entity = builder.toMutation().orElseThrow().mutate(schema, null);

		// upsert same value again -> no change
		final List<LocalMutation<?, ?>> noops = List.of(new UpsertAttributeMutation(NAME, Locale.ENGLISH, "Name"));
		final Entity result = Entity.mutateEntity(schema, entity, noops);
		assertSame(entity, result, "Expected same entity instance to be returned on no-op mutations");
	}
}
