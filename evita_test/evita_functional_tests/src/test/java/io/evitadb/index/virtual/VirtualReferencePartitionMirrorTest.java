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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.virtual.TransitionHistory.Transition;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.updateProduct;
import static io.evitadb.index.virtual.VirtualReferencePartitionTransitionTest.assertNoRow;
import static io.evitadb.index.virtual.VirtualReferencePartitionTransitionTest.assertRow;
import static io.evitadb.index.virtual.VirtualReferencePartitionTransitionTest.partitionOf;
import static io.evitadb.index.virtual.VirtualReferencePartitionTransitionTest.registryOf;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the sort values and chain predecessors of reference partitions through both engines of
 * {@link DifferentialEngines} - in a catalog warming up and in a live one - and checks that the registry of
 * single-owner partitions mirrors them: every value a partition with a row orders its only owner by is the value the
 * registry orders the partition by, in the normalized space of the sort index, for every sortable type, compound and
 * chain the reference can carry, through population, replacement, removal, deltas, locale fan-out and reload.
 *
 * The harness checks the registry closed-world against the partitions at every checkpoint - the sort values, their
 * order and blocks, the chain predecessors and the entry count - and compares the two engines everywhere else (see
 * {@link VirtualReferencePartitionOracle}). On top of that, every test asserts the keys and values its history must
 * have mirrored, so no test passes by mirroring nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition mirror of sort values and chains")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class VirtualReferencePartitionMirrorTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = SingleOwnerPartitionFixture.ENTITY_PRODUCT;
	private static final String ENTITY_CATEGORY = SingleOwnerPartitionFixture.ENTITY_CATEGORY;
	private static final Set<String> ENTITY_TYPES = Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY);
	private static final String REFERENCE_CATEGORIES = SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
	private static final String REFERENCE_PRODUCTS = SingleOwnerPartitionFixture.REFERENCE_PRODUCTS;
	/**
	 * Filterable and sortable - the partition's sort index is a view over its filter index.
	 */
	private static final String ATTRIBUTE_ORDER = "order";
	/**
	 * Sortable only, scaled to two decimal places in the sort index.
	 */
	private static final String ATTRIBUTE_PRICE = "price";
	private static final String ATTRIBUTE_VALID_FROM = "validFrom";
	/**
	 * Filterable and sortable.
	 */
	private static final String ATTRIBUTE_RELEASED_AT = "releasedAt";
	private static final String ATTRIBUTE_OPENS_AT = "opensAt";
	/**
	 * Localized.
	 */
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String ATTRIBUTE_LANGUAGE = "language";
	private static final String ATTRIBUTE_CURRENCY = "currency";
	/**
	 * Sortable, two indexed decimal places, written at an intrinsic scale of one.
	 */
	private static final String ATTRIBUTE_SPAN = "span";
	/**
	 * Filterable only - never in a sort index, never mirrored.
	 */
	private static final String ATTRIBUTE_FILTERED = "filtered";
	/**
	 * Unique only - never in a sort index, never mirrored.
	 */
	private static final String ATTRIBUTE_UNIQUE_CODE = "uniqueCode";
	/**
	 * A `Predecessor` - the reflected reference inherits it as a `ReferencedEntityPredecessor`.
	 */
	private static final String ATTRIBUTE_PRODUCT_ORDER = "productOrder";
	/**
	 * A `ReferencedEntityPredecessor` - the reflected reference inherits it as a `Predecessor`.
	 */
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	/**
	 * A localized compound: the label, then the price descending with nulls first.
	 */
	private static final String COMPOUND_LABEL_AND_PRICE = "labelAndPrice";
	/**
	 * A compound of the types a sort index wraps or rescales: the language, the currency descending with nulls first,
	 * the validity, the span and the exact price.
	 */
	private static final String COMPOUND_TYPED = "typed";
	/**
	 * The category the first two products share - a partition with two owners and therefore no row.
	 */
	private static final int SHARED_CATEGORY = 100;
	/**
	 * Categories no product of the fixture references.
	 */
	private static final int FREE_CATEGORY = 11;
	/**
	 * How many products the fixture starts with, each the only owner of the category of its own primary key.
	 */
	private static final int PRODUCT_COUNT = 4;
	/**
	 * A product the fixture does not create.
	 */
	private static final int NEW_PRODUCT = 9;
	/**
	 * The schema scale of the price and the span.
	 */
	private static final int DECIMAL_PLACES = 2;
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	/**
	 * A Czech word written precomposed - the decomposed form of {@link #DECOMPOSED_LABEL} is the same string to a sort
	 * index.
	 */
	private static final String COMPOSED_LABEL = "Žluťoučký";
	private static final String DECOMPOSED_LABEL = Normalizer.normalize(COMPOSED_LABEL, Normalizer.Form.NFD);
	/**
	 * Every sort key the fixture's products declare in English.
	 */
	private static final Set<AttributeIndexKey> SORT_KEYS = Set.of(
		key(ATTRIBUTE_ORDER, null), key(ATTRIBUTE_PRICE, null), key(ATTRIBUTE_VALID_FROM, null),
		key(ATTRIBUTE_RELEASED_AT, null), key(ATTRIBUTE_OPENS_AT, null), key(ATTRIBUTE_LABEL, Locale.ENGLISH),
		key(ATTRIBUTE_LANGUAGE, null), key(ATTRIBUTE_CURRENCY, null), key(ATTRIBUTE_SPAN, null),
		key(COMPOUND_LABEL_AND_PRICE, Locale.ENGLISH), key(COMPOUND_TYPED, null)
	);

	@Nullable private DifferentialEngines engines;

	@AfterEach
	void tearDown() {
		if (this.engines != null) {
			this.engines.close();
			this.engines = null;
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("every sortable type and compound is mirrored through insert, replace, delta, removal and reload")
	void shouldMirrorEverySortableTypeAndCompound(CatalogState state) {
		final DifferentialEngines theEngines = start("types", state, VirtualReferencePartitionMirrorTest::setUpTypes);

		// the population of every single-owner partition went through the mirror, keyed exactly as the partition
		final VirtualReferencePartitionIndex registry = productRegistry(theEngines);
		assertEquals(SORT_KEYS, registry.getDeclaredSortKeys(), "the sortable keys - and only those - are mirrored");
		assertEquals(Set.of(), registry.getDeclaredChainKeys());
		for (final AttributeIndexKey sortKey : SORT_KEYS) {
			assertEquals(
				PRODUCT_COUNT, sortIndexOf(registry, sortKey).size(),
				"every single-owner category partition has a value of " + sortKey
			);
		}
		// the premises of the order check: equal values of different partitions tie as one block
		assertEquals(
			2, SortIndexWalk.of(sortIndexOf(registry, key(ATTRIBUTE_ORDER, null))).blocks().size(),
			"two blocks of two partitions each"
		);
		assertEquals(
			3, SortIndexWalk.of(sortIndexOf(registry, key(ATTRIBUTE_VALID_FROM, null))).blocks().size(),
			"the same instant at two offsets is one block"
		);
		assertEquals(
			3, SortIndexWalk.of(sortIndexOf(registry, key(ATTRIBUTE_LABEL, Locale.ENGLISH))).blocks().size(),
			"the composed and the decomposed form of a word are one block"
		);

		theEngines.step(
			"every value of the first product's partition is replaced",
			updateProduct(1, it -> it.updateReference(REFERENCE_CATEGORIES, 1, ref -> fillReference(ref, 7)))
		);
		theEngines.step(
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
		theEngines.step(
			"a second locale fans the localized label and compound out to the fourth product's partition",
			updateProduct(
				4,
				it -> it.updateReference(
					REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_LABEL, CZECH, "Česky")
				)
			)
		);
		assertTrue(
			productRegistry(theEngines).hasSortValue(key(COMPOUND_LABEL_AND_PRICE, CZECH), partitionOfCategory(4)),
			"the localized compound of the new locale must have been mirrored: " +
				productRegistry(theEngines).getDeclaredSortKeys()
		);
		theEngines.step(
			"every value of the second product's partition is removed",
			updateProduct(
				2, it -> it.updateReference(REFERENCE_CATEGORIES, 2, VirtualReferencePartitionMirrorTest::clear)
			)
		);
		final VirtualReferencePartitionIndex afterRemoval = productRegistry(theEngines);
		for (final AttributeIndexKey sortKey : SORT_KEYS) {
			assertFalse(
				afterRemoval.hasSortValue(sortKey, partitionOfCategory(2)),
				"the removed value of " + sortKey + " must be gone"
			);
			assertTrue(afterRemoval.getDeclaredSortKeys().contains(sortKey), "the key stays declared: " + sortKey);
		}
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, categoryKey(2), 2);

		theEngines.restart();
		theEngines.step(
			"the reloaded registry keeps following the partitions",
			updateProduct(
				2, it -> it.updateReference(REFERENCE_CATEGORIES, 2, ref -> fillReference(ref, 2))
			)
		);
		assertEquals(PRODUCT_COUNT, sortIndexOf(productRegistry(theEngines), key(COMPOUND_TYPED, null)).size());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a range is mirrored at the indexed scale once populated and once updated, plain and in a compound")
	void shouldMirrorRangesAtTheirIndexedScale(CatalogState state) {
		final DifferentialEngines theEngines = start("ranges", state, VirtualReferencePartitionMirrorTest::setUpTypes);
		final BigDecimalNumberRange written = spanOf(1);
		assertNotEquals(
			DECIMAL_PLACES, written.getEffectiveRetainedDecimalPlaces(),
			"the premise: the fixture writes ranges at another scale than the schema indexes them at"
		);

		// population only: the scalar range and the compound element both come from the stored reference
		assertSpanAtIndexedScale(theEngines, 1);

		// an incremental update of the range: the changed element of the compound comes from the mutation
		theEngines.step(
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
		assertSpanAtIndexedScale(theEngines, 1);
		theEngines.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("chains are mirrored on both sides of a reflected reference, an emptied chain key stays declared")
	void shouldMirrorChainsOnBothSidesOfAReflectedReference(CatalogState state) {
		final DifferentialEngines theEngines = start("chains", state, VirtualReferencePartitionMirrorTest::setUpChains);
		final AttributeIndexKey productOrder = key(ATTRIBUTE_PRODUCT_ORDER, null);
		final AttributeIndexKey categoryOrder = key(ATTRIBUTE_CATEGORY_ORDER, null);
		assertEquals(Set.of(productOrder, categoryOrder), productRegistry(theEngines).getDeclaredChainKeys());
		assertEquals(
			Set.of(
				new AttributeIndexKey(REFERENCE_PRODUCTS, ATTRIBUTE_PRODUCT_ORDER, null),
				new AttributeIndexKey(REFERENCE_PRODUCTS, ATTRIBUTE_CATEGORY_ORDER, null)
			),
			categoryRegistry(theEngines).getDeclaredChainKeys(),
			"the reflected reference inherits both chains, its types inverted"
		);
		assertEquals(
			2, productRegistry(theEngines).getChainPredecessor(productOrder, partitionOfCategory(3)).orElseThrow()
		);

		theEngines.step(
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
			1, productRegistry(theEngines).getChainPredecessor(productOrder, partitionOfCategory(3)).orElseThrow()
		);

		theEngines.transaction(
			"every product drops its product order",
			List.of(
				updateProduct(
					1,
					it -> it.updateReference(
						REFERENCE_CATEGORIES, 1, ref -> ref.removeAttribute(ATTRIBUTE_PRODUCT_ORDER)
					)
				),
				updateProduct(
					2,
					it -> it.updateReference(
						REFERENCE_CATEGORIES, 2, ref -> ref.removeAttribute(ATTRIBUTE_PRODUCT_ORDER)
					)
				),
				updateProduct(
					3,
					it -> it.updateReference(
						REFERENCE_CATEGORIES, 3, ref -> ref.removeAttribute(ATTRIBUTE_PRODUCT_ORDER)
					)
				)
			)
		);
		// the partitions' chain indexes of the key are gone, the registry's declaration stays while the rows remain -
		// and the harness found that consistent at every checkpoint
		final VirtualReferencePartitionIndex registry = productRegistry(theEngines);
		assertTrue(registry.getDeclaredChainKeys().contains(productOrder));
		for (int category = 1; category <= 3; category++) {
			assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, categoryKey(category), category);
			assertTrue(registry.getChainPredecessor(productOrder, partitionOfCategory(category)).isEmpty());
		}
		theEngines.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a partition left with one of two owners gets every sortable type and compound by extraction")
	void shouldExtractEverySortableTypeAndCompoundOfADemotedPartition(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"demoted", state, VirtualReferencePartitionMirrorTest::setUpTypes
		);
		final int shared = partitionOfCategory(SHARED_CATEGORY);
		// the demotion below starts from a registry loaded from disk
		theEngines.restart();

		theEngines.step(
			"the second owner of the shared category leaves it",
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY))
		);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, categoryKey(SHARED_CATEGORY), 1);
		final VirtualReferencePartitionIndex registry = productRegistry(theEngines);
		for (final AttributeIndexKey sortKey : SORT_KEYS) {
			assertTrue(
				registry.hasSortValue(sortKey, shared),
				"the remaining owner's value of " + sortKey + " must have been extracted"
			);
		}
		assertEquals(
			1,
			theEngines.getMaintainingHistory().getCommitted().stream()
				.filter(it -> it.partitionPrimaryKey() == shared && it.stage() == TransitionStage.DEMOTED)
				.count(),
			"the partition must have been demoted once"
		);

		// the mirror removes a value strictly - only the very value the registry holds - so replacing the extracted
		// values proves each of them is exactly the value the partition holds
		theEngines.step(
			"the remaining owner rewrites every value of the shared category",
			updateProduct(
				1, it -> it.updateReference(REFERENCE_CATEGORIES, SHARED_CATEGORY, ref -> fillReference(ref, 8))
			)
		);
		theEngines.restart();
		theEngines.step(
			"the remaining owner leaves the shared category too",
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY))
		);
		assertTrue(
			theEngines.getMaintainingHistory().getCommitted().contains(
				new Transition(TransitionStage.VACATED, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, shared)
			),
			"the demoted partition must have been vacated"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a demoted partition gets its ranges at the indexed scale once populated and once updated")
	void shouldExtractRangesAtTheirIndexedScale(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"demotedRanges", state, VirtualReferencePartitionMirrorTest::setUpTypes
		);
		assertNotEquals(
			DECIMAL_PLACES, spanOf(sharedVariantOf(1)).getEffectiveRetainedDecimalPlaces(),
			"the premise: the fixture writes ranges at another scale than the schema indexes them at"
		);

		// population only: the plain range and the compound element both came from the stored reference
		theEngines.step(
			"the second owner of the shared category leaves it",
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY))
		);
		assertSpanAtIndexedScale(theEngines, SHARED_CATEGORY);

		// an incremental update of the range while the partition has two owners: the changed element of the compound
		// comes from the mutation, and only the extraction can bring it into the registry
		theEngines.step(
			"the second owner joins the shared category again",
			updateProduct(
				2,
				it -> it.setReference(
					REFERENCE_CATEGORIES, SHARED_CATEGORY, ref -> fillReference(ref, sharedVariantOf(2))
				)
			)
		);
		assertNoRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, categoryKey(SHARED_CATEGORY));
		theEngines.step(
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
		theEngines.step(
			"the second owner leaves the shared category again",
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY))
		);
		assertSpanAtIndexedScale(theEngines, SHARED_CATEGORY);
		assertEquals(
			2,
			theEngines.getMaintainingHistory().getCommitted().stream()
				.filter(
					it -> it.partitionPrimaryKey() == partitionOfCategory(SHARED_CATEGORY) &&
						it.stage() == TransitionStage.DEMOTED
				)
				.count(),
			"the shared partition must have been demoted twice"
		);
		theEngines.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("the declared keys are retired with the last row, whether it is vacated or shared")
	void shouldRetireEveryKeyWithTheLastRow(CatalogState state) {
		final DifferentialEngines theEngines = start("retire", state, VirtualReferencePartitionMirrorTest::setUpTypes);

		theEngines.transaction(
			"all products but the fourth are removed",
			List.of(
				session -> session.deleteEntity(ENTITY_PRODUCT, 1),
				session -> session.deleteEntity(ENTITY_PRODUCT, 2),
				session -> session.deleteEntity(ENTITY_PRODUCT, 3)
			)
		);
		assertEquals(SORT_KEYS, productRegistry(theEngines).getDeclaredSortKeys(), "the fourth product's row remains");

		theEngines.step(
			"a new product joins the fourth product's category, which loses the last row",
			session -> session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, NEW_PRODUCT)
					.setReference(REFERENCE_CATEGORIES, 4, ref -> fillReference(ref, NEW_PRODUCT))
			)
		);
		final VirtualReferencePartitionIndex registry = productRegistry(theEngines);
		assertEquals(Set.of(), registry.getDeclaredSortKeys(), "no row is left, so no key may be declared");
		assertEquals(0, registry.getEntryCount());

		theEngines.transaction(
			"the remaining products are removed",
			List.of(
				session -> session.deleteEntity(ENTITY_PRODUCT, 4),
				session -> session.deleteEntity(ENTITY_PRODUCT, NEW_PRODUCT)
			)
		);
		final VirtualReferencePartitionIndex emptied =
			registryOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, REFERENCE_CATEGORIES);
		assertTrue(emptied == null || emptied.isEmpty());
		theEngines.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a failure injected in the middle of a population rolls the mirrored values back with the partition")
	void shouldRollBackTheMirroredValuesOfAFailedPopulation(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"failedMirror", state, VirtualReferencePartitionMirrorTest::setUpTypes
		);
		theEngines.step("a product moves to the archive", session -> session.archiveEntity(ENTITY_PRODUCT, 4));
		final String before = describeRegistry(productRegistry(theEngines));

		// restoring the product populates its live category partition from the stored reference - several sort values
		// and compounds mirrored one by one, the failure strikes after the fourth
		theEngines.failingStepAfter(
			"the product comes back, failing in the middle of the population of its category partition",
			TransitionStage.MIRRORED, ENTITY_PRODUCT, 3,
			session -> session.restoreEntity(ENTITY_PRODUCT, 4)
		);
		assertEquals(
			before, describeRegistry(productRegistry(theEngines)),
			"the failed population must have left the registry as it was"
		);
		final List<TransitionStage> rolledBack = theEngines.getMaintainingHistory().getRolledBack().stream()
			.filter(it -> it.referenceName().equals(REFERENCE_CATEGORIES))
			.map(Transition::stage)
			.toList();
		assertEquals(
			List.of(
				TransitionStage.ENTERED,
				TransitionStage.MIRRORED, TransitionStage.MIRRORED, TransitionStage.MIRRORED, TransitionStage.MIRRORED
			),
			rolledBack.subList(rolledBack.indexOf(TransitionStage.ENTERED), rolledBack.size()),
			"the failure must have struck after the row was registered and four values were mirrored: " + rolledBack
		);

		theEngines.step(
			"the same population succeeds afterwards", session -> session.restoreEntity(ENTITY_PRODUCT, 4)
		);
		assertEquals(PRODUCT_COUNT, sortIndexOf(productRegistry(theEngines), key(COMPOUND_TYPED, null)).size());
		theEngines.restart();
	}

	/**
	 * Starts the engines, prepares the catalog in the given state and remembers the engines for the tear-down.
	 *
	 * @param label a label naming the engines' directories
	 * @param state the catalog state
	 * @param setup the schema and the initial data
	 * @return the engines
	 */
	@Nonnull
	private DifferentialEngines start(
		@Nonnull String label,
		@Nonnull CatalogState state,
		@Nonnull Consumer<EvitaSessionContract> setup
	) {
		final DifferentialEngines theEngines = new DifferentialEngines(this, label, ENTITY_TYPES);
		this.engines = theEngines;
		theEngines.prepare(state, setup);
		return theEngines;
	}

	/**
	 * Defines products in two locales with a category reference indexed for filtering that carries a sortable
	 * attribute of every type a sort index normalizes in its own way, two compounds, and a filterable and a unique
	 * attribute that are not sortable; then creates product `n` (1 to {@link #PRODUCT_COUNT}) as the only owner of
	 * category `n`, the first two also sharing {@link #SHARED_CATEGORY} with values of every type (see
	 * {@link #sharedVariantOf(int)}).
	 *
	 * @param session the session to write in
	 */
	private static void setUpTypes(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(Locale.ENGLISH, CZECH)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterable().sortable().nullable())
					.withAttribute(
						ATTRIBUTE_PRICE, BigDecimal.class,
						thatIs -> thatIs.sortable().indexDecimalPlaces(DECIMAL_PLACES).nullable()
					)
					.withAttribute(ATTRIBUTE_VALID_FROM, OffsetDateTime.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(
						ATTRIBUTE_RELEASED_AT, LocalDateTime.class, thatIs -> thatIs.filterable().sortable().nullable()
					)
					.withAttribute(ATTRIBUTE_OPENS_AT, LocalTime.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().sortable().nullable())
					.withAttribute(ATTRIBUTE_LANGUAGE, Locale.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(ATTRIBUTE_CURRENCY, Currency.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(
						ATTRIBUTE_SPAN, BigDecimalNumberRange.class,
						thatIs -> thatIs.sortable().indexDecimalPlaces(DECIMAL_PLACES).nullable()
					)
					.withAttribute(ATTRIBUTE_FILTERED, Integer.class, thatIs -> thatIs.filterable().nullable())
					.withAttribute(ATTRIBUTE_UNIQUE_CODE, String.class, thatIs -> thatIs.unique().nullable())
					.withSortableAttributeCompound(
						COMPOUND_LABEL_AND_PRICE,
						new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_PRICE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					)
					.withSortableAttributeCompound(
						COMPOUND_TYPED,
						new AttributeElement(ATTRIBUTE_LANGUAGE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_CURRENCY, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement(ATTRIBUTE_VALID_FROM, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_SPAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_PRICE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
			)
			.updateVia(session);
		for (int pk = 1; pk <= FREE_CATEGORY; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
		}
		session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, SHARED_CATEGORY));
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			final int productPrimaryKey = pk;
			final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, pk)
				.setReference(REFERENCE_CATEGORIES, pk, ref -> fillReference(ref, productPrimaryKey));
			if (pk <= 2) {
				product.setReference(
					REFERENCE_CATEGORIES, SHARED_CATEGORY, ref -> fillReference(ref, sharedVariantOf(productPrimaryKey))
				);
			}
			session.upsertEntity(product);
		}
	}

	/**
	 * Defines products whose category reference carries an order and both chain types, reflected on the category with
	 * its attributes inherited; then creates product `n` (1 to 3) as the only owner of category `n`, chained after
	 * product `n - 1` and after category `n - 1`.
	 *
	 * @param session the session to write in
	 */
	private static void setUpChains(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterable().sortable().nullable())
					.withAttribute(ATTRIBUTE_PRODUCT_ORDER, Predecessor.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(
						ATTRIBUTE_CATEGORY_ORDER, ReferencedEntityPredecessor.class,
						thatIs -> thatIs.sortable().nullable()
					)
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.withAttributesInherited()
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
		for (int pk = 1; pk <= FREE_CATEGORY; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
		}
		for (int pk = 1; pk <= 3; pk++) {
			final int productPrimaryKey = pk;
			session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, pk)
					.setReference(
						REFERENCE_CATEGORIES, pk,
						ref -> ref.setAttribute(ATTRIBUTE_ORDER, productPrimaryKey)
							.setAttribute(
								ATTRIBUTE_PRODUCT_ORDER,
								productPrimaryKey == 1 ? Predecessor.HEAD : new Predecessor(productPrimaryKey - 1)
							)
							.setAttribute(
								ATTRIBUTE_CATEGORY_ORDER,
								productPrimaryKey == 1 ?
									ReferencedEntityPredecessor.HEAD :
									new ReferencedEntityPredecessor(productPrimaryKey - 1)
							)
					)
			);
		}
	}

	/**
	 * Returns the variant of the values product `n` has in {@link #SHARED_CATEGORY} - one no single-owner partition of
	 * the fixture uses.
	 *
	 * @param product the product, 1 or 2
	 * @return the variant
	 */
	private static int sharedVariantOf(int product) {
		return PRODUCT_COUNT + product;
	}

	/**
	 * Sets every attribute of a category reference to the variant `n` of its values. The variants tie on purpose:
	 * variants 1 and 3, and 2 and 4, share their order; 1 and 2 share their price and their instant of validity at
	 * different offsets; 3 and 4 share their label, once composed and once decomposed.
	 *
	 * @param reference the reference to fill
	 * @param variant   the variant of the values
	 * @return the reference
	 */
	@Nonnull
	private static ReferenceBuilder fillReference(@Nonnull ReferenceBuilder reference, int variant) {
		return reference
			.setAttribute(ATTRIBUTE_ORDER, variant % 2 + 1)
			.setAttribute(ATTRIBUTE_PRICE, priceOf(variant))
			.setAttribute(ATTRIBUTE_VALID_FROM, validFromOf(variant))
			.setAttribute(
				ATTRIBUTE_RELEASED_AT, LocalDateTime.of(2026, 3, 29, 2, 30, 0, 123_456_789).plusDays(variant)
			)
			.setAttribute(ATTRIBUTE_OPENS_AT, LocalTime.of(8, variant, 0, 999_999_999))
			.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, labelOf(variant))
			.setAttribute(ATTRIBUTE_LANGUAGE, variant % 2 == 0 ? Locale.ENGLISH : CZECH)
			.setAttribute(ATTRIBUTE_CURRENCY, Currency.getInstance(variant % 2 == 0 ? "CZK" : "EUR"))
			.setAttribute(ATTRIBUTE_SPAN, spanOf(variant))
			.setAttribute(ATTRIBUTE_FILTERED, variant)
			.setAttribute(ATTRIBUTE_UNIQUE_CODE, "U" + variant + "-" + reference.getReferencedPrimaryKey());
	}

	/**
	 * Removes every attribute of a category reference.
	 *
	 * @param reference the reference to clear
	 */
	private static void clear(@Nonnull ReferenceBuilder reference) {
		reference
			.removeAttribute(ATTRIBUTE_ORDER)
			.removeAttribute(ATTRIBUTE_PRICE)
			.removeAttribute(ATTRIBUTE_VALID_FROM)
			.removeAttribute(ATTRIBUTE_RELEASED_AT)
			.removeAttribute(ATTRIBUTE_OPENS_AT)
			.removeAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH)
			.removeAttribute(ATTRIBUTE_LANGUAGE)
			.removeAttribute(ATTRIBUTE_CURRENCY)
			.removeAttribute(ATTRIBUTE_SPAN)
			.removeAttribute(ATTRIBUTE_FILTERED)
			.removeAttribute(ATTRIBUTE_UNIQUE_CODE);
	}

	/**
	 * Returns the price of a variant - variants 1 and 2 have the same price at different scales, variant 3 a negative
	 * one rounded at the schema scale.
	 *
	 * @param variant the variant
	 * @return the price
	 */
	@Nonnull
	private static BigDecimal priceOf(int variant) {
		return switch (variant) {
			case 1 -> new BigDecimal("1.25");
			case 2 -> new BigDecimal("1.250");
			case 3 -> new BigDecimal("-1.235");
			default -> new BigDecimal(variant).movePointLeft(1);
		};
	}

	/**
	 * Returns the validity of a variant - variants 1 and 2 are the same instant at different offsets, variant 3 is
	 * before 1970 and finer than a millisecond.
	 *
	 * @param variant the variant
	 * @return the validity
	 */
	@Nonnull
	private static OffsetDateTime validFromOf(int variant) {
		return switch (variant) {
			case 1 -> OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(2));
			case 2 -> OffsetDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZoneOffset.UTC);
			case 3 -> OffsetDateTime.of(1969, 12, 31, 23, 59, 59, 999_999, ZoneOffset.UTC);
			default -> OffsetDateTime.of(2026, 6, variant % 28 + 1, 0, 0, 0, 123_456_789, ZoneOffset.ofHours(-5));
		};
	}

	/**
	 * Returns the English label of a variant - variants 3 and 4 are the same word, composed and decomposed.
	 *
	 * @param variant the variant
	 * @return the label
	 */
	@Nonnull
	private static String labelOf(int variant) {
		return switch (variant) {
			case 3 -> COMPOSED_LABEL;
			case 4 -> DECOMPOSED_LABEL;
			default -> "label " + variant;
		};
	}

	/**
	 * Returns the span of a variant, written at an intrinsic scale of one decimal place.
	 *
	 * @param variant the variant
	 * @return the span
	 */
	@Nonnull
	private static BigDecimalNumberRange spanOf(int variant) {
		return BigDecimalNumberRange.between(
			new BigDecimal(variant).add(new BigDecimal("0.5")), new BigDecimal(variant).add(new BigDecimal("3.4"))
		);
	}

	/**
	 * Asserts the registry holds the span of the category's partition - plain and as a compound element - at the
	 * schema's indexed scale, whatever scale the value was written at.
	 *
	 * @param engines  the engines
	 * @param category the category, whose partition has a row
	 */
	private void assertSpanAtIndexedScale(@Nonnull DifferentialEngines engines, int category) {
		final VirtualReferencePartitionIndex registry = productRegistry(engines);
		final int partition = partitionOfCategory(category);
		final SortIndexWalk spans = SortIndexWalk.of(sortIndexOf(registry, key(ATTRIBUTE_SPAN, null)));
		final SortIndexWalk compounds = SortIndexWalk.of(sortIndexOf(registry, key(COMPOUND_TYPED, null)));
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
	 * Creates the key of a category partition.
	 *
	 * @param category the category
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey categoryKey(int category) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, category));
	}

	/**
	 * Returns the primary key of the live partition of a category in the maintaining engine.
	 *
	 * @param category the category
	 * @return the partition primary key
	 */
	private int partitionOfCategory(int category) {
		final int partition = partitionOf(
			requireEngines().getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, categoryKey(category)
		);
		assertNotEquals(-1, partition, "no partition of category " + category);
		return partition;
	}

	/**
	 * Returns the committed live registry of the category reference of the maintaining engine.
	 *
	 * @param engines the engines
	 * @return the registry
	 */
	@Nonnull
	private static VirtualReferencePartitionIndex productRegistry(@Nonnull DifferentialEngines engines) {
		final VirtualReferencePartitionIndex registry =
			registryOf(engines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, REFERENCE_CATEGORIES);
		assertNotNull(registry);
		return registry;
	}

	/**
	 * Returns the committed live registry of the reflected product reference of the maintaining engine.
	 *
	 * @param engines the engines
	 * @return the registry
	 */
	@Nonnull
	private static VirtualReferencePartitionIndex categoryRegistry(@Nonnull DifferentialEngines engines) {
		final VirtualReferencePartitionIndex registry =
			registryOf(engines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, REFERENCE_PRODUCTS);
		assertNotNull(registry);
		return registry;
	}

	/**
	 * Returns the sort index of a declared key of the registry.
	 *
	 * @param registry the registry
	 * @param sortKey  the key
	 * @return the sort index
	 */
	@Nonnull
	private static OwnerSortIndex sortIndexOf(
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull AttributeIndexKey sortKey
	) {
		final OwnerSortIndex sortIndex = registry.getSortIndex(sortKey);
		assertNotNull(sortIndex, "the key " + sortKey + " is not declared: " + registry.getDeclaredSortKeys());
		return sortIndex;
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
	 * Describes everything the registry holds: its rows and tuples, the blocks of every declared sort key, the
	 * predecessor of every partition with a row in every declared chain, and the entry count.
	 *
	 * @param registry the registry
	 * @return the description
	 */
	@Nonnull
	private static String describeRegistry(@Nonnull VirtualReferencePartitionIndex registry) {
		final StringBuilder description = new StringBuilder(1024);
		final Set<Integer> rows = new TreeSet<>();
		registry.forEachEntry(
			VirtualReferencePartitionSlot.PARTITION_OWNER,
			(partition, payload) -> {
				rows.add(partition);
				description.append("row ").append(partition).append("->").append(NumberUtils.unpackHigh(payload))
					.append('/').append(NumberUtils.unpackLow(payload)).append('\n');
			}
		);
		registry.forEachEntry(
			VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE,
			(partition, payload) -> description.append("tuple ").append(partition).append('#').append(payload)
				.append('\n')
		);
		for (final AttributeIndexKey sortKey : new TreeSet<>(registry.getDeclaredSortKeys())) {
			description.append("sort ").append(sortKey).append(": ")
				.append(SortIndexWalk.of(sortIndexOf(registry, sortKey)).describe()).append('\n');
		}
		for (final AttributeIndexKey chainKey : new TreeSet<>(registry.getDeclaredChainKeys())) {
			description.append("chain ").append(chainKey).append(':');
			for (final Integer partition : rows) {
				description.append(' ').append(partition).append("<-")
					.append(registry.getChainPredecessor(chainKey, partition));
			}
			description.append('\n');
		}
		return description.append("entries ").append(registry.getEntryCount()).toString();
	}

	/**
	 * Returns the engines of the running test.
	 *
	 * @return the engines
	 */
	@Nonnull
	private DifferentialEngines requireEngines() {
		assertNotNull(this.engines);
		return this.engines;
	}
}
