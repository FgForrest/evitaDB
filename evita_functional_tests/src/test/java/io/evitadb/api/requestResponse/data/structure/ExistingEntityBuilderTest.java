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
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException;
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
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
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
import java.util.Set;
import java.util.function.UnaryOperator;

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

		final Entity updatedEntity = this.builder
			.toMutation()
			.map(
				it -> it.mutate(
					this.initialEntity.getSchema(),
					this.initialEntity
				)
			)
			.orElse(this.initialEntity);

		assertFalse(updatedEntity.parentAvailable());
		assertThrows(EntityIsNotHierarchicalException.class, updatedEntity::getParent);
		assertThrows(EntityIsNotHierarchicalException.class, updatedEntity::getParentEntity);
		assertEquals(this.initialEntity.version() + 1, updatedEntity.version());
	}

	@Test
	void shouldCollectAllLocalesCorrectly() {
		assertEquals(Set.of(Locale.ENGLISH), this.builder.getAllLocales());
		assertEquals(Set.of(Locale.ENGLISH), this.builder.getLocales());
		assertEquals(Set.of(Locale.ENGLISH), this.builder.toInstance().getAllLocales());
		assertEquals(Set.of(Locale.ENGLISH), this.builder.toInstance().getLocales());

		// add a new localized attribute
		this.builder.setAttribute("newLocalizedAttribute", Locale.GERMAN, "value");
		assertEquals(Set.of(Locale.ENGLISH, Locale.GERMAN), this.builder.getAllLocales());
		assertEquals(Set.of(Locale.ENGLISH, Locale.GERMAN), this.builder.getLocales());
		assertEquals(Set.of(Locale.ENGLISH, Locale.GERMAN), this.builder.toInstance().getAllLocales());
		assertEquals(Set.of(Locale.ENGLISH, Locale.GERMAN), this.builder.toInstance().getLocales());
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

		final Entity updatedEntity = this.builder.toMutation().orElseThrow().mutate(
			this.initialEntity.getSchema(), this.initialEntity);
		assertEquals(PriceInnerRecordHandling.NONE, updatedEntity.getPriceInnerRecordHandling());
	}

	@Test
	void shouldOverwriteParent() {
		this.builder.setParent(78);
		assertEquals(
			Optional.of(new EntityReferenceWithParent(this.initialEntity.getSchema().getName(), 78, null)),
			this.builder.getParentEntity()
		);

		final Entity updatedEntity = this.builder.toMutation().orElseThrow().mutate(
			this.initialEntity.getSchema(), this.initialEntity);
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

		final ExistingEntityBuilder existingEntityBuilder = new ExistingEntityBuilder(
			new InitialEntityBuilder(schema).toInstance());
		assertThrows(
			IllegalArgumentException.class,
			() -> existingEntityBuilder.setAttribute(SORTABLE_ATTRIBUTE, new String[]{"abc", "def"})
		);
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

		updatedInstance.getReference(BRAND_TYPE, 1).ifPresent(reference -> {
			assertEquals(
				"Whatever", reference.getGroup()
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
	void shouldGetReferencesByName() {
		final Entity entityWithRefs = buildInitialEntityWithDuplicatedReferences();
		final ExistingEntityBuilder eb = new ExistingEntityBuilder(entityWithRefs);

		// verify expected references counts
		assertEquals(1, eb.getReferences(BRAND).size());
		assertEquals(1, eb.toInstance().getReferences(BRAND).size());
		assertEquals(2, eb.getReferences(CATEGORY).size());
		assertEquals(2, eb.toInstance().getReferences(CATEGORY).size());
		assertEquals(2, eb.getReferences(PARAMETER).size());
		assertEquals(2, eb.toInstance().getReferences(PARAMETER).size());
		assertEquals(3, eb.getReferences(STORE).size());

		// update some references
		eb.setReference(BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_UPDATED, OffsetDateTime.now()));
		eb.setReference(CATEGORY, 10, whichIs -> whichIs.setGroup(STORE, 15));

		// the count has to remain the same
		assertEquals(1, eb.getReferences(BRAND).size());
		assertEquals(1, eb.toInstance().getReferences(BRAND).size());
		assertEquals(2, eb.getReferences(CATEGORY).size());
		assertEquals(2, eb.toInstance().getReferences(CATEGORY).size());
		assertEquals(2, eb.getReferences(PARAMETER).size());
		assertEquals(2, eb.toInstance().getReferences(PARAMETER).size());
		assertEquals(3, eb.getReferences(STORE).size());

		// add a few references
		eb.setReference(BRAND, 2);
		eb.setReference(CATEGORY, 12);
		eb.setReference(PARAMETER, 101, ref -> false, whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "C"));
		eb.setReference(STORE, 1002, ref -> false, UnaryOperator.identity());

		// the count has to grow
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

		/* cannot change referenced entity type this way */
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setReference(
				BRAND,
				"differentEntityType",
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				2,
				ref -> false,
				UnaryOperator.identity()
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
					UnaryOperator.identity()
				);
			}
		);

		// promote to ZERO_OR_MORE_WITH_DUPLICATES
		builder.setReference(
			BRAND,
			2,
			ref -> false,
			UnaryOperator.identity()
		);
		// new references has automatically elevated cardinality
		for (ReferenceContract reference : builder.getReferences(new ReferenceKey(BRAND, 2))) {
			assertEquals(
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, reference.getReferenceSchemaOrThrow().getCardinality());
		}
		for (ReferenceContract reference : builder.toInstance().getReferences(new ReferenceKey(BRAND, 2))) {
			assertEquals(
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, reference.getReferenceSchemaOrThrow().getCardinality());
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

	@Test
	void shouldModifyEntityViaDirectMutations() {
		final InitialEntityBuilder initialEntityBuilder = new InitialEntityBuilder("product", 100);
		final ExistingEntityBuilder builder = new ExistingEntityBuilder(initialEntityBuilder.toInstance());

		builder.addMutation(new SetParentMutation(10));
		builder.addMutation(new SetParentMutation(20));
		builder.addMutation(new UpsertAttributeMutation("name", "Product Name"));
		builder.addMutation(new UpsertAttributeMutation("name", "Different product Name"));
		builder.addMutation(new UpsertAttributeMutation("description", Locale.ENGLISH, "Product Description"));
		builder.addMutation(new UpsertAttributeMutation("description", Locale.GERMAN, "Produkt Beschreibung"));
		builder.addMutation(new UpsertAssociatedDataMutation("data", "Data"));
		builder.addMutation(new UpsertAssociatedDataMutation("data", "Updated data"));
		builder.addMutation(new UpsertAssociatedDataMutation("url", Locale.ENGLISH, "http://example.com/en"));
		builder.addMutation(new UpsertAssociatedDataMutation("url", Locale.GERMAN, "http://example.com/de"));
		builder.addMutation(new UpsertPriceMutation(
			new PriceKey(1, "basic", CZK), null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true));
		builder.addMutation(new UpsertPriceMutation(
			new PriceKey(1, "basic", CZK), null, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.valueOf(20), null, false));
		builder.addMutation(new UpsertPriceMutation(
			new PriceKey(2, "basic", EUR), null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true));
		builder.addMutation(
			new InsertReferenceMutation(new ReferenceKey(BRAND_TYPE, 1), Cardinality.ZERO_OR_ONE, BRAND_TYPE));
		builder.addMutation(new InsertReferenceMutation(new ReferenceKey(BRAND_TYPE, 2)));
		builder.addMutation(new ReferenceAttributeMutation(
			new ReferenceKey(BRAND_TYPE, 1), new UpsertAttributeMutation("brandName", Locale.ENGLISH, "Brand Name")));
		builder.addMutation(new ReferenceAttributeMutation(
			new ReferenceKey(BRAND_TYPE, 1), new UpsertAttributeMutation(
			"brandName", Locale.ENGLISH,
			"Brand Name updated"
		)
		));
		builder.addMutation(new ReferenceAttributeMutation(
			new ReferenceKey(BRAND_TYPE, 1), new UpsertAttributeMutation(
			"brandName", Locale.GERMAN,
			"Brand Bezeichnung"
		)
		));

		// assert all data is present
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
		// remove it by its key - it includes internal primary key, so it should work now
		eb.removeReference(findRealParameter.getReferenceKey());

		final Entity modifiedInstance = eb.toInstance();

		assertEquals(0, eb.getReferences(BRAND).size());
		assertEquals(0, modifiedInstance.getReferences(BRAND).stream().filter(Droppable::exists).count());
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
	void shouldRemoveReferencesByNameInBulk() {
		final Entity initialEntity = buildInitialEntityWithDuplicatedReferences();
		final ExistingEntityBuilder eb = new ExistingEntityBuilder(initialEntity);

		eb.removeReferences(PARAMETER, 100);
		eb.removeReferences(STORE, ref -> "A".equals(ref.getAttribute(ATTRIBUTE_DISCRIMINATOR, String.class)));

		final Entity modifiedInstance = eb.toInstance();

		assertEquals(1, eb.getReferences(PARAMETER).size());
		assertEquals(1, modifiedInstance.getReferences(PARAMETER).stream().filter(Droppable::exists).count());
		assertEquals(2, eb.getReferences(STORE).size());
		assertEquals(2, modifiedInstance.getReferences(STORE).stream().filter(Droppable::exists).count());
		assertEquals(
			"B", eb.getReferences(STORE)
			       .stream()
			       .filter(Droppable::exists)
			       .findFirst()
			       .orElseThrow()
			       .getAttribute(ATTRIBUTE_DISCRIMINATOR, String.class)
		);
		assertEquals(
			"B", modifiedInstance.getReferences(STORE)
			                     .stream()
			                     .filter(Droppable::exists)
			                     .findFirst()
			                     .orElseThrow()
			                     .getAttribute(ATTRIBUTE_DISCRIMINATOR, String.class)
		);
	}

	@Test
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
			"B", eb.getReferences(STORE)
			       .stream()
			       .filter(Droppable::exists)
			       .findFirst()
			       .orElseThrow()
			       .getAttribute(ATTRIBUTE_DISCRIMINATOR, String.class)
		);
		assertEquals(
			"B", modifiedInstance.getReferences(STORE)
			                     .stream()
			                     .filter(Droppable::exists)
			                     .findFirst()
			                     .orElseThrow()
			                     .getAttribute(ATTRIBUTE_DISCRIMINATOR, String.class)
		);
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

	@Test
	void shouldAllowAddingNewDefinitions() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(EvolutionMode.values())
			.toInstance();

		final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
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

		final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
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

		final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
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
	void shouldDenyAddingParent() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION
			)
			.toInstance();

		final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
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

		final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
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
	void shouldDenyAddingReference() {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			PRODUCT_SCHEMA
		)
			.verifySchemaButAllow(
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION
			)
			.toInstance();

		final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
		assertThrows(
			InvalidMutationException.class,
			() -> builder.setReference(BRAND, 1)
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

		final ExistingEntityBuilder builder = new ExistingEntityBuilder(new Entity(schema, 1));
		builder.setReference(BRAND, BRAND, Cardinality.ZERO_OR_ONE, 1);

		assertThrows(
			InvalidMutationException.class,
			() -> builder.setReference(BRAND, 2)
		);
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
		eb.setReference(
			PARAMETER,
			PARAMETER,
			Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			100,
			ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "A")
		);
		eb.setReference(
			PARAMETER,
			PARAMETER,
			Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			100,
			ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "B")
		);
		// initial: 3x STORE (two share same referencedEntityPrimaryKey)
		eb.setReference(
			STORE,
			STORE,
			Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			1000,
			ref -> false,
			UnaryOperator.identity()
		);
		eb.setReference(
			STORE,
			STORE,
			Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			1001,
			ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "A")
		);
		eb.setReference(
			STORE,
			STORE,
			Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			1001,
			ref -> false,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_DISCRIMINATOR, "B")
		);
		final Entity resultInstance = eb.toInstance();

		assertEquals(1, resultInstance.getReferences(BRAND).size());
		assertEquals(1, resultInstance.getReferences(BRAND).size());
		assertEquals(2, resultInstance.getReferences(CATEGORY).size());
		assertEquals(2, resultInstance.getReferences(CATEGORY).size());
		assertEquals(2, resultInstance.getReferences(PARAMETER).size());
		assertEquals(2, resultInstance.getReferences(PARAMETER).size());
		assertEquals(3, resultInstance.getReferences(STORE).size());
		assertEquals(3, resultInstance.getReferences(STORE).size());

		return resultInstance;
	}

}
