/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.virtual;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ReferenceCardinalityViolatedException;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.BRAND_PK;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_BRAND;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_CATEGORY;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_PRODUCT;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_BRAND;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_PRODUCTS;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.category;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.product;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.registryOf;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.updateProduct;
import static io.evitadb.index.virtual.SortValueAndChainFixture.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the sort values and chain predecessors of single-owner reference partitions - registry rows - through their
 * life in one engine, in a catalog warming up and in a live one: every sortable type, compound and chain a reference
 * can carry, through population, replacement, removal, deltas, the fan-out of a new locale, the extraction of a
 * demotion and reload.
 *
 * {@link VirtualPartitionHistory} checks the catalog at every moment of a history, the body oracle (see
 * {@link VirtualReferencePartitionRawRowOracle}) above all: every row must hold exactly the sort values and chain
 * predecessors its owner's body declares, normalized as a sort index normalizes them, and must be ordered within every
 * sort key as those values order under the comparator the schema defines for the key - rows whose values compare equal
 * forming one block. The orders are computed from the bodies, so the registry is held to what the data says rather
 * than to a second representation of it. On top of that every test asserts the keys, values, blocks and scales its
 * history must have produced, so that no test passes by mirroring nothing: the fixture's values tie on purpose (see
 * {@link SortValueAndChainFixture#fillReference}), and the premises of the ties are asserted where they matter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Sort values and chains of single-owner partitions written as registry rows")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class VirtualPartitionSortValueTest implements EvitaTestSupport {
	/**
	 * A product none of the fixtures creates.
	 */
	private static final int NEW_PRODUCT = 9;
	/**
	 * A product of the brand fixture that references no category - the only owner of its own brand, so the catalog
	 * keeps a row whatever the categories do.
	 */
	private static final int BYSTANDER = 50;
	/**
	 * The brand of {@link #BYSTANDER}.
	 */
	private static final int BYSTANDER_BRAND = BRAND_PK + 1;
	/**
	 * Every sort key the fixture's products declare in English.
	 */
	private static final Set<AttributeIndexKey> SORT_KEYS = Set.of(
		key(ATTRIBUTE_ORDER, null), key(ATTRIBUTE_PRICE, null), key(ATTRIBUTE_VALID_FROM, null),
		key(ATTRIBUTE_RELEASED_AT, null), key(ATTRIBUTE_OPENS_AT, null), key(ATTRIBUTE_LABEL, Locale.ENGLISH),
		key(ATTRIBUTE_LANGUAGE, null), key(ATTRIBUTE_CURRENCY, null), key(ATTRIBUTE_SPAN, null),
		key(COMPOUND_LABEL_AND_PRICE, Locale.ENGLISH), key(COMPOUND_TYPED, null)
	);

	@Nullable private VirtualPartitionHistory history;

	@AfterEach
	void tearDown() {
		if (this.history != null) {
			this.history.close();
			this.history = null;
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("every sortable type and compound is written through insert, replace, delta, removal and reload")
	void shouldWriteEverySortableTypeAndCompound(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("types", state, SortValueAndChainFixture::setUpTypes);

		// the population of every single-owner partition wrote its row, keyed exactly as the partition's values
		final VirtualReferencePartitionIndex registry = productRegistry(theHistory.getEvita());
		assertEquals(SORT_KEYS, registry.getDeclaredSortKeys(), "the sortable keys - and only those - are written");
		assertEquals(Set.of(), registry.getDeclaredChainKeys());
		for (final AttributeIndexKey sortKey : SORT_KEYS) {
			assertEquals(
				PRODUCT_COUNT, walkOf(registry, sortKey).records().length,
				"every single-owner category partition has a value of " + sortKey
			);
		}
		// the premises of the order the body oracle checks: equal values of different partitions tie as one block
		assertEquals(
			2, walkOf(registry, key(ATTRIBUTE_ORDER, null)).blocks().size(),
			"two blocks of two partitions each"
		);
		assertEquals(
			3, walkOf(registry, key(ATTRIBUTE_VALID_FROM, null)).blocks().size(),
			"the same instant at two offsets is one block"
		);
		assertEquals(
			3, walkOf(registry, key(ATTRIBUTE_LABEL, Locale.ENGLISH)).blocks().size(),
			"the composed and the decomposed form of a word are one block"
		);

		theHistory.step(
			"every value of the first product's partition is replaced",
			updateProduct(1, it -> it.updateReference(REFERENCE_CATEGORIES, 1, ref -> fillReference(ref, 7)))
		);
		theHistory.step(
			"the order and the price of the third product's partition change by a delta",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 3, EntityExistence.MUST_EXIST,
					new ReferenceAttributeMutation(
						existingReferenceKey(session, 3, 3), new ApplyDeltaAttributeMutation<>(ATTRIBUTE_ORDER, 5)
					),
					new ReferenceAttributeMutation(
						existingReferenceKey(session, 3, 3),
						new ApplyDeltaAttributeMutation<>(ATTRIBUTE_PRICE, new BigDecimal("0.255"))
					)
				)
			)
		);
		theHistory.step(
			"a second locale fans the localized label and compound out to the fourth product's partition",
			updateProduct(
				4,
				it -> it.updateReference(
					REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_LABEL, CZECH, "Česky")
				)
			)
		);
		final int fourthPartition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		assertSortValue(theHistory.getEvita(), fourthPartition, key(COMPOUND_LABEL_AND_PRICE, CZECH), true);

		theHistory.step(
			"every value of the second product's partition is removed",
			updateProduct(2, it -> it.updateReference(REFERENCE_CATEGORIES, 2, SortValueAndChainFixture::clear))
		);
		final int secondPartition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(2), 2);
		final VirtualReferencePartitionIndex afterRemoval = productRegistry(theHistory.getEvita());
		for (final AttributeIndexKey sortKey : SORT_KEYS) {
			assertSortValue(theHistory.getEvita(), secondPartition, sortKey, false);
			assertTrue(afterRemoval.getDeclaredSortKeys().contains(sortKey), "the key stays declared: " + sortKey);
		}

		theHistory.restart();
		theHistory.step(
			"the reloaded registry keeps following the partitions",
			updateProduct(2, it -> it.updateReference(REFERENCE_CATEGORIES, 2, ref -> fillReference(ref, 2)))
		);
		assertEquals(
			PRODUCT_COUNT, walkOf(productRegistry(theHistory.getEvita()), key(COMPOUND_TYPED, null)).records().length
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a range is written at the indexed scale once populated and once updated, plain and in a compound")
	void shouldWriteRangesAtTheirIndexedScale(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("ranges", state, SortValueAndChainFixture::setUpTypes);
		assertNotEquals(
			DECIMAL_PLACES, spanOf(1).getEffectiveRetainedDecimalPlaces(),
			"the premise: the fixture writes ranges at another scale than the schema indexes them at"
		);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(1), 1);

		// population only: the scalar range and the compound element both come from the stored reference
		assertSpanAtIndexedScale(theHistory.getEvita(), partition);

		// an incremental update of the range: the changed element of the compound comes from the mutation
		theHistory.step(
			"the span of the first product's partition is updated",
			updateProduct(
				1,
				it -> it.updateReference(
					REFERENCE_CATEGORIES, 1,
					ref -> ref.setAttribute(
						ATTRIBUTE_SPAN, BigDecimalNumberRange.between(new BigDecimal("-1.5"), new BigDecimal("8.3"))
					)
				)
			)
		);
		assertSpanAtIndexedScale(theHistory.getEvita(), partition);
		theHistory.restart();
		assertSpanAtIndexedScale(theHistory.getEvita(), partition);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("chains are written on both sides of a reflected reference, an emptied chain key stays declared")
	void shouldWriteChainsOnBothSidesOfAReflectedReference(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("chains", state, SortValueAndChainFixture::setUpChains);
		final AttributeIndexKey productOrder = key(ATTRIBUTE_PRODUCT_ORDER, null);
		final AttributeIndexKey categoryOrder = key(ATTRIBUTE_CATEGORY_ORDER, null);
		assertEquals(
			Set.of(productOrder, categoryOrder), productRegistry(theHistory.getEvita()).getDeclaredChainKeys()
		);
		assertEquals(
			Set.of(
				new AttributeIndexKey(REFERENCE_PRODUCTS, ATTRIBUTE_PRODUCT_ORDER, null),
				new AttributeIndexKey(REFERENCE_PRODUCTS, ATTRIBUTE_CATEGORY_ORDER, null)
			),
			categoryRegistry(theHistory.getEvita()).getDeclaredChainKeys(),
			"the reflected reference inherits both chains, its types inverted"
		);
		final int thirdPartition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(3), 3);
		theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(3), 3);
		assertEquals(
			2, productRegistry(theHistory.getEvita()).getChainPredecessor(productOrder, thirdPartition).orElseThrow()
		);

		theHistory.step(
			"the chains of a product change",
			updateProduct(
				3,
				it -> it.updateReference(
					REFERENCE_CATEGORIES, 3,
					ref -> ref.setAttribute(ATTRIBUTE_PRODUCT_ORDER, new Predecessor(1))
						.setAttribute(ATTRIBUTE_CATEGORY_ORDER, new ReferencedEntityPredecessor(1))
				)
			)
		);
		assertEquals(
			1, productRegistry(theHistory.getEvita()).getChainPredecessor(productOrder, thirdPartition).orElseThrow()
		);

		theHistory.transaction(
			"every product drops its product order",
			List.of(dropProductOrder(1), dropProductOrder(2), dropProductOrder(3))
		);
		// the declaration of the key stays while the rows remain - the body oracle found that consistent all along
		final VirtualReferencePartitionIndex registry = productRegistry(theHistory.getEvita());
		assertTrue(registry.getDeclaredChainKeys().contains(productOrder));
		for (int category = 1; category <= 3; category++) {
			final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(category), category);
			assertTrue(registry.getChainPredecessor(productOrder, partition).isEmpty());
		}
		theHistory.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a partition left with one of two owners gets every sortable type and compound by extraction")
	void shouldExtractEverySortableTypeAndCompoundOfADemotedPartition(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("demoted", state, SortValueAndChainFixture::setUpTypes);
		final int shared = theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), 1, 2);
		// the demotion below starts from indexes loaded from disk
		theHistory.restart();

		theHistory.step(
			"the second owner of the shared category leaves it",
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY))
		);
		assertEquals(shared, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), 1));
		for (final AttributeIndexKey sortKey : SORT_KEYS) {
			assertSortValue(theHistory.getEvita(), shared, sortKey, true);
		}

		// the registry removes a value strictly - only the very value it holds - so replacing the extracted values
		// proves each of them is exactly the value the partition held
		theHistory.step(
			"the remaining owner rewrites every value of the shared category",
			updateProduct(
				1, it -> it.updateReference(REFERENCE_CATEGORIES, SHARED_CATEGORY, ref -> fillReference(ref, 8))
			)
		);
		theHistory.restart();
		theHistory.step(
			"the remaining owner leaves the shared category too",
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY))
		);
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a demoted partition gets its ranges at the indexed scale once populated and once updated")
	void shouldExtractRangesAtTheirIndexedScale(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"demotedRanges", state, SortValueAndChainFixture::setUpTypes
		);
		assertNotEquals(
			DECIMAL_PLACES, spanOf(sharedVariantOf(1)).getEffectiveRetainedDecimalPlaces(),
			"the premise: the fixture writes ranges at another scale than the schema indexes them at"
		);
		final int shared = theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), 1, 2);

		// population only: the plain range and the compound element both came from the stored reference
		theHistory.step(
			"the second owner of the shared category leaves it",
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY))
		);
		assertEquals(shared, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), 1));
		assertSpanAtIndexedScale(theHistory.getEvita(), shared);

		// an incremental update of the range while the partition has two owners: the changed element of the compound
		// comes from the mutation, and only the extraction can bring it into the row
		theHistory.step(
			"the second owner joins the shared category again",
			updateProduct(
				2,
				it -> it.setReference(
					REFERENCE_CATEGORIES, SHARED_CATEGORY, ref -> fillReference(ref, sharedVariantOf(2))
				)
			)
		);
		theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), 1, 2);
		theHistory.step(
			"the span of the first owner in the shared category is updated",
			updateProduct(
				1,
				it -> it.updateReference(
					REFERENCE_CATEGORIES, SHARED_CATEGORY,
					ref -> ref.setAttribute(
						ATTRIBUTE_SPAN, BigDecimalNumberRange.between(new BigDecimal("-2.5"), new BigDecimal("9.1"))
					)
				)
			)
		);
		theHistory.step(
			"the second owner leaves the shared category again",
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY))
		);
		assertEquals(shared, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), 1));
		assertSpanAtIndexedScale(theHistory.getEvita(), shared);
		theHistory.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("the declared keys are retired with the last row, whether it is vacated or shared")
	void shouldRetireEveryKeyWithTheLastRow(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"retire", state, VirtualPartitionSortValueTest::setUpTypesWithMandatoryBrands
		);

		theHistory.transaction(
			"all products but the fourth are removed",
			List.of(
				session -> {
					session.deleteEntity(ENTITY_PRODUCT, 1);
				},
				session -> {
					session.deleteEntity(ENTITY_PRODUCT, 2);
				},
				session -> {
					session.deleteEntity(ENTITY_PRODUCT, 3);
				}
			)
		);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		assertEquals(
			SORT_KEYS, productRegistry(theHistory.getEvita()).getDeclaredSortKeys(), "the fourth product's row remains"
		);

		theHistory.step(
			"a new product joins the fourth product's category, which loses the last row",
			session -> session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, NEW_PRODUCT)
					.setReference(REFERENCE_CATEGORIES, 4, ref -> fillReference(ref, NEW_PRODUCT))
					.setReference(REFERENCE_BRAND, BRAND_PK)
			)
		);
		theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(4), 4, NEW_PRODUCT);
		final VirtualReferencePartitionIndex registry = productRegistry(theHistory.getEvita());
		assertEquals(Set.of(), registry.getDeclaredSortKeys(), "no row is left, so no key may be declared");
		assertTrue(registry.isEmpty(), "the registry without a row holds nothing");

		theHistory.transaction(
			"the remaining products of the categories are removed",
			List.of(
				session -> {
					session.deleteEntity(ENTITY_PRODUCT, 4);
				},
				session -> {
					session.deleteEntity(ENTITY_PRODUCT, NEW_PRODUCT);
				}
			)
		);
		final VirtualReferencePartitionIndex emptied =
			registryOf(theHistory.getEvita(), ENTITY_PRODUCT, Scope.LIVE, REFERENCE_CATEGORIES);
		assertTrue(emptied == null || emptied.isEmpty());
		theHistory.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a population of every sortable type in a mutation failing at its end leaves the registry as it was")
	void shouldRevertThePopulationOfAMutationFailingAtItsEnd(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"failedPopulation", state, VirtualPartitionSortValueTest::setUpTypesWithMandatoryBrands
		);

		// a new product populates the row of a free category with a value of every type and compound, one by one -
		// and then fails the consistency check of its root mutation for want of its mandatory brand
		theHistory.failingStep(
			"a new product without its brand populates a free category",
			session -> session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, NEW_PRODUCT)
					.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY, ref -> fillReference(ref, NEW_PRODUCT))
			),
			ReferenceCardinalityViolatedException.class
		);
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY));

		theHistory.step(
			"the same population succeeds with the brand",
			session -> session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, NEW_PRODUCT)
					.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY, ref -> fillReference(ref, NEW_PRODUCT))
					.setReference(REFERENCE_BRAND, BRAND_PK)
			)
		);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY), NEW_PRODUCT);
		for (final AttributeIndexKey sortKey : SORT_KEYS) {
			assertSortValue(theHistory.getEvita(), partition, sortKey, true);
		}

		// a restored product populates its live row from its stored reference
		theHistory.step("a product moves to the archive", session -> session.archiveEntity(ENTITY_PRODUCT, 4));
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(4));
		theHistory.assertRow(ENTITY_PRODUCT, Scope.ARCHIVED, category(4), 4);
		theHistory.step("the product comes back", session -> session.restoreEntity(ENTITY_PRODUCT, 4));
		final int restored = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		for (final AttributeIndexKey sortKey : SORT_KEYS) {
			assertSortValue(theHistory.getEvita(), restored, sortKey, true);
		}
		theHistory.restart();
	}

	/**
	 * Defines the schema and the data of {@link SortValueAndChainFixture#setUpTypes}, then gives every product a brand
	 * - the first brand for the products of the fixture, a brand of its own to {@link #BYSTANDER}, which references no
	 * category - and makes the brand reference mandatory. A product mutation leaving a product without its brand fails
	 * at the consistency check that ends its root mutation, and the bystander keeps a row in the catalog whatever the
	 * categories do.
	 *
	 * @param session the session to write in
	 */
	private static void setUpTypesWithMandatoryBrands(@Nonnull EvitaSessionContract session) {
		setUpTypes(session);
		session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
		defineBrandReference(session, Cardinality.ZERO_OR_ONE);
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BYSTANDER_BRAND));
		for (int product = 1; product <= PRODUCT_COUNT; product++) {
			updateProduct(product, it -> it.setReference(REFERENCE_BRAND, BRAND_PK)).accept(session);
		}
		session.upsertEntity(
			session.createNewEntity(ENTITY_PRODUCT, BYSTANDER).setReference(REFERENCE_BRAND, BYSTANDER_BRAND)
		);
		// every product has its brand - from now on it must have one
		defineBrandReference(session, Cardinality.EXACTLY_ONE);
	}

	/**
	 * Defines the brand reference of the products, indexed for filtering in both scopes.
	 *
	 * @param session     the session to write in
	 * @param cardinality the cardinality of the reference
	 */
	private static void defineBrandReference(@Nonnull EvitaSessionContract session, @Nonnull Cardinality cardinality) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, cardinality,
				whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
	}

	/**
	 * Creates a step removing the product order from the reference of a product of the chain fixture to the category
	 * of its own primary key.
	 *
	 * @param product the product
	 * @return the step
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> dropProductOrder(int product) {
		return updateProduct(
			product,
			it -> it.updateReference(REFERENCE_CATEGORIES, product, ref -> ref.removeAttribute(ATTRIBUTE_PRODUCT_ORDER))
		);
	}

	/**
	 * Asserts the registry holds the span of a row - plain and as a compound element - at the schema's indexed scale,
	 * whatever scale the value was written at.
	 *
	 * @param evita     the engine
	 * @param partition the primary key of the row
	 */
	private static void assertSpanAtIndexedScale(@Nonnull Evita evita, int partition) {
		final VirtualReferencePartitionIndex registry = productRegistry(evita);
		final SortIndexWalk spans = walkOf(registry, key(ATTRIBUTE_SPAN, null));
		final SortIndexWalk compounds = walkOf(registry, key(COMPOUND_TYPED, null));
		int found = 0;
		for (int i = 0; i < spans.records().length; i++) {
			if (spans.records()[i] == partition) {
				assertEquals(
					DECIMAL_PLACES,
					((BigDecimalNumberRange) spans.values()[i]).getEffectiveRetainedDecimalPlaces(),
					"the plain span of partition " + partition
				);
				found++;
			}
		}
		for (int i = 0; i < compounds.records().length; i++) {
			if (compounds.records()[i] == partition) {
				final Serializable[] elements = ((ComparableArray) compounds.values()[i]).array();
				assertEquals(
					DECIMAL_PLACES,
					((BigDecimalNumberRange) elements[3]).getEffectiveRetainedDecimalPlaces(),
					"the span element of the compound of partition " + partition
				);
				found++;
			}
		}
		assertEquals(2, found, "partition " + partition + " must have a plain span and a compound in the registry");
	}

	/**
	 * Asserts whether the committed live row of a category partition of the products holds a value under a sort key.
	 *
	 * @param evita     the engine
	 * @param partition the primary key of the row
	 * @param sortKey   the sort key
	 * @param expected  TRUE when the row must hold a value
	 */
	private static void assertSortValue(
		@Nonnull Evita evita,
		int partition,
		@Nonnull AttributeIndexKey sortKey,
		boolean expected
	) {
		final int valueId = productRegistry(evita).getSortValueId(sortKey, partition);
		assertEquals(
			expected, valueId != VirtualReferencePartitionIndex.NO_SORT_VALUE_ID,
			"the row " + partition + " must " + (expected ? "" : "not ") + "hold a value of " + sortKey
		);
	}

	/**
	 * Returns the key of a reference attribute or compound of the category reference.
	 *
	 * @param name   the attribute or compound
	 * @param locale the locale, `null` for a language-agnostic one
	 * @return the key
	 */
	@Nonnull
	private static AttributeIndexKey key(@Nonnull String name, @Nullable Locale locale) {
		return new AttributeIndexKey(REFERENCE_CATEGORIES, name, locale);
	}

	/**
	 * Returns the committed live registry of the category reference of the products.
	 *
	 * @param evita the engine
	 * @return the registry
	 */
	@Nonnull
	private static VirtualReferencePartitionIndex productRegistry(@Nonnull Evita evita) {
		final VirtualReferencePartitionIndex registry =
			registryOf(evita, ENTITY_PRODUCT, Scope.LIVE, REFERENCE_CATEGORIES);
		assertNotNull(registry, "the category reference of the products must have its registry");
		return registry;
	}

	/**
	 * Returns the committed live registry of the reflected product reference of the categories.
	 *
	 * @param evita the engine
	 * @return the registry
	 */
	@Nonnull
	private static VirtualReferencePartitionIndex categoryRegistry(@Nonnull Evita evita) {
		final VirtualReferencePartitionIndex registry =
			registryOf(evita, ENTITY_CATEGORY, Scope.LIVE, REFERENCE_PRODUCTS);
		assertNotNull(registry, "the product reference of the categories must have its registry");
		return registry;
	}

	/**
	 * Walks a declared key of the registry through its public reading surface - the values in sort order, each with
	 * the rows holding it (see {@link SortIndexWalk#ofRegistry}).
	 *
	 * @param registry the registry
	 * @param sortKey  the key
	 * @return the content of the key, position by position
	 */
	@Nonnull
	private static SortIndexWalk walkOf(
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull AttributeIndexKey sortKey
	) {
		final SortIndexWalk walk = SortIndexWalk.ofRegistry(registry, sortKey);
		assertNotNull(walk, "the key " + sortKey + " is not declared: " + registry.getDeclaredSortKeys());
		return walk;
	}

	/**
	 * Returns the key, with its internal primary key, of an existing category reference of a product.
	 *
	 * @param session  the session to read in
	 * @param product  the product
	 * @param category the category
	 * @return the reference key
	 */
	@Nonnull
	private static ReferenceKey existingReferenceKey(
		@Nonnull EvitaSessionContract session,
		int product,
		int category
	) {
		return session.getEntity(ENTITY_PRODUCT, product, referenceContentAll())
			.orElseThrow()
			.getReference(REFERENCE_CATEGORIES, category)
			.orElseThrow()
			.getReferenceKey();
	}

	/**
	 * Starts the history in the fixture the setup writes, prepared in the given state.
	 *
	 * @param label a label naming the engine's directories
	 * @param state the catalog state the history should continue in
	 * @param setup the schema and the initial data
	 * @return the history
	 */
	@Nonnull
	private VirtualPartitionHistory start(
		@Nonnull String label,
		@Nonnull CatalogState state,
		@Nonnull Consumer<EvitaSessionContract> setup
	) {
		final VirtualPartitionHistory theHistory = new VirtualPartitionHistory(this, label, null);
		this.history = theHistory;
		theHistory.prepare(state, setup);
		return theHistory;
	}

}
