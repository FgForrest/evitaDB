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
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.virtual.TransitionHistory.Transition;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.index.virtual.DifferentialEngines.assertNoRow;
import static io.evitadb.index.virtual.DifferentialEngines.assertRow;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.category;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.partitionOf;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.product;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.registryOf;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.updateProduct;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the demotion of reference partitions - a partition left with one owner of two (2 -> 1) getting its row back,
 * filled by extraction with what the partition holds for the remaining owner - through both engines of
 * {@link DifferentialEngines}, in a catalog warming up and in a live one, in the histories that decide whether the
 * extraction reads the right thing: the remaining owner's values written by another executor of the same root
 * mutation before the demotion and after it, a whole churn inside one open transaction, chains read by the owner's
 * record and by the referenced entity's record on both sides of a reflected reference, the locale data of a departing
 * owner, and the one history in which a partition loses a `ReferencedEntityPredecessor` record its remaining owner
 * still carries (#1691).
 *
 * The harness checks the registry exactly against the partitions at every checkpoint - every single-owner partition
 * must have its row, with every sort value and chain predecessor the partition holds for its owner - checks the rows
 * and the index objects against the entity bodies, with the primary key of every partition held across the
 * checkpoints (see {@link PartitionIdentityLedger}), and compares the two engines everywhere else. What the history
 * left on disk is checked once it ends (see {@link VirtualPartitionInvariants#assertStoredParts}). On top of that,
 * every test asserts the demotions and values its history must have produced, so no test passes by demoting nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition demotion by extraction")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class VirtualReferencePartitionDemotionTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = SingleOwnerPartitionFixture.ENTITY_PRODUCT;
	private static final String ENTITY_CATEGORY = SingleOwnerPartitionFixture.ENTITY_CATEGORY;
	private static final String ENTITY_BRAND = SingleOwnerPartitionFixture.ENTITY_BRAND;
	/**
	 * Every entity type of every fixture - the ones of {@link SingleOwnerPartitionFixture} included.
	 */
	private static final Set<String> ENTITY_TYPES = SingleOwnerPartitionFixture.ENTITY_TYPES;
	private static final String REFERENCE_CATEGORIES = SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
	private static final String REFERENCE_PRODUCTS = SingleOwnerPartitionFixture.REFERENCE_PRODUCTS;
	private static final String REFERENCE_BRAND = SingleOwnerPartitionFixture.REFERENCE_BRAND;
	/**
	 * Filterable and sortable.
	 */
	private static final String ATTRIBUTE_ORDER = "order";
	/**
	 * Localized and sortable.
	 */
	private static final String ATTRIBUTE_LABEL = "label";
	/**
	 * A localized compound: the label, then the order descending with nulls first.
	 */
	private static final String COMPOUND_LABEL_AND_ORDER = "labelAndOrder";
	/**
	 * A `Predecessor` - the partition indexes it under the owner, and the reflected reference inherits it as a
	 * `ReferencedEntityPredecessor`.
	 */
	private static final String ATTRIBUTE_PRODUCT_ORDER = "productOrder";
	/**
	 * A `ReferencedEntityPredecessor` - the partition indexes it under the referenced entity, and the reflected
	 * reference inherits it as a `Predecessor`.
	 */
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	/**
	 * A localized entity attribute - the one value that keeps a product in its locale.
	 */
	private static final String ATTRIBUTE_TITLE = "title";
	/**
	 * Localized, on the brand reference.
	 */
	private static final String ATTRIBUTE_BRAND_LABEL = "brandLabel";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	/**
	 * A localized compound of the brand reference: the brand label, then the priority.
	 */
	private static final String COMPOUND_BRAND_LABEL_AND_PRIORITY = "brandLabelAndPriority";
	/**
	 * Categories the first two products share - partitions with two owners and therefore no row.
	 */
	private static final int SHARED_CATEGORY = 100;
	private static final int SECOND_SHARED_CATEGORY = 101;
	/**
	 * Categories no product of the fixture references.
	 */
	private static final int FREE_CATEGORY = 11;
	private static final int BRAND_PK = 1;
	private static final Locale CZECH = Locale.forLanguageTag("cs");

	@Nullable private DifferentialEngines engines;

	@AfterEach
	void tearDown() {
		if (this.engines != null) {
			try {
				// what the history left on disk - against the indexes it holds and a history-free rebuild
				VirtualPartitionInvariants.assertStoredParts(
					"at the end of the history", this.engines.getMaintaining(), SingleOwnerPartitionFixture.CATALOG
				);
			} finally {
				this.engines.close();
				this.engines = null;
			}
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a demotion extracts what another executor wrote before it, the mirror takes what it fans out after")
	void shouldDemoteAcrossExecutors(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"acrossExecutors", state, VirtualReferencePartitionDemotionTest::setUpLocales
		);
		final int firstShared = partitionOfCategory(theEngines, SHARED_CATEGORY);
		final int secondShared = partitionOfCategory(theEngines, SECOND_SHARED_CATEGORY);

		// a category mutation spawns one product mutation per reflected reference it changes, each run by an executor
		// of its own within the root mutation. The departing owner's executor demotes the partition when it applies its
		// changes; the remaining owner's executor writes its label in the new locale into the partition while the
		// mutations are dispatched - before any executor applies its changes, so before the demotion, which has to
		// extract it - and fans the new locale out into the partition's localized compound when it applies its own
		// changes. The collector registers the reflected removal before the reflected attribute change whatever order
		// the category lists them in, so the departing owner's executor applies its changes first and the fan-out
		// follows the demotion, which the mirror has to take; the two steps list the mutations in both orders
		final List<Transition> ofFirst = demotionStep(
			theEngines, "product 2 leaves the first shared category, product 1 gets a Czech label there",
			SHARED_CATEGORY, 2, 1, CZECH, true
		);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), 1);
		final List<Transition> ofSecond = demotionStep(
			theEngines, "product 2 gets a German label in the second shared category, product 1 leaves it",
			SECOND_SHARED_CATEGORY, 1, 2, Locale.GERMAN, false
		);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(SECOND_SHARED_CATEGORY), 2);

		for (final List<Transition> passed : List.of(ofFirst, ofSecond)) {
			assertEquals(
				List.of(TransitionStage.DEMOTED, TransitionStage.MIRRORED),
				passed.subList(passed.size() - 2, passed.size()).stream().map(Transition::stage).toList(),
				"the new locale must have been fanned out into the partition right after its demotion: " + passed
			);
		}
		final VirtualReferencePartitionIndex registry = productRegistry(theEngines);
		assertTrue(registry.hasSortValue(labelKey(CZECH), firstShared), "the Czech label must have been extracted");
		assertTrue(registry.hasSortValue(labelKey(Locale.GERMAN), secondShared), "the German label too");
		assertTrue(registry.hasSortValue(compoundKey(CZECH), firstShared), "the Czech compound must be mirrored");
		assertTrue(registry.hasSortValue(compoundKey(Locale.GERMAN), secondShared), "the German compound too");
		theEngines.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a churn of demotions inside one open transaction is checked step by step and survives the commit")
	void shouldDemoteInsideAnOpenTransaction(CatalogState state) {
		final DifferentialEngines theEngines = start("transaction", state, SingleOwnerPartitionFixture::setUp);
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		theEngines.transaction(
			"two products share a free category, one leaves, the other changes its order there, a third joins and " +
				"leaves again",
			List.of(
				updateProduct(1, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY)),
				updateProduct(
					2,
					it -> it.setReference(
						REFERENCE_CATEGORIES, FREE_CATEGORY,
						ref -> ref.setAttribute(SingleOwnerPartitionFixture.ATTRIBUTE_ORDER, 2)
					)
				),
				updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_CATEGORY)),
				updateProduct(
					2,
					it -> it.updateReference(
						REFERENCE_CATEGORIES, FREE_CATEGORY,
						ref -> ref.setAttribute(SingleOwnerPartitionFixture.ATTRIBUTE_ORDER, 7)
					)
				),
				updateProduct(3, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY)),
				updateProduct(3, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_CATEGORY))
			)
		);

		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY), 2);
		final int partition = partitionOfCategory(theEngines, FREE_CATEGORY);
		assertEquals(
			List.of(
				TransitionStage.ENTERED, TransitionStage.SHARED, TransitionStage.DEMOTED,
				TransitionStage.SHARED, TransitionStage.DEMOTED
			),
			stagesOf(theEngines.getMaintainingHistory().getCommitted(), committedBefore, ENTITY_PRODUCT, partition),
			"the committed history must hold both demotions of the partition"
		);
		assertEquals(
			7,
			registryOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, REFERENCE_CATEGORIES)
				.getSortIndex(
					new AttributeIndexKey(REFERENCE_CATEGORIES, SingleOwnerPartitionFixture.ATTRIBUTE_ORDER, null)
				)
				.getValueOfRecord(partition)
				.orElseThrow(),
			"the order the remaining owner set after the first demotion, extracted again by the second one"
		);
		theEngines.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("chains are extracted by the owner's record or the referenced entity's, on both sides of a reflection")
	void shouldExtractChainsByTheRecordTheirTypeIsIndexedUnder(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"chains", state, VirtualReferencePartitionDemotionTest::setUpChains
		);
		final AttributeIndexKey productOrderOfCategories =
			new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_PRODUCT_ORDER, null);
		final AttributeIndexKey categoryOrderOfCategories =
			new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_CATEGORY_ORDER, null);
		final AttributeIndexKey productOrderOfProducts =
			new AttributeIndexKey(REFERENCE_PRODUCTS, ATTRIBUTE_PRODUCT_ORDER, null);
		final AttributeIndexKey categoryOrderOfProducts =
			new AttributeIndexKey(REFERENCE_PRODUCTS, ATTRIBUTE_CATEGORY_ORDER, null);

		// the product side: the shared category keeps product 1's `Predecessor` under product 1 and its
		// `ReferencedEntityPredecessor` under the shared category
		theEngines.step(
			"product 2 leaves the shared category",
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY))
		);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), 1);
		final int sharedCategory = partitionOfCategory(theEngines, SHARED_CATEGORY);
		final VirtualReferencePartitionIndex productRegistry = productRegistry(theEngines);
		assertTrue(
			productRegistry.getChainPredecessor(productOrderOfCategories, sharedCategory).isPresent(),
			"the `Predecessor` of the remaining owner must have been extracted by the owner's record"
		);
		assertTrue(
			productRegistry.getChainPredecessor(categoryOrderOfCategories, sharedCategory).isPresent(),
			"the `ReferencedEntityPredecessor` of the remaining owner must have been extracted by the referenced " +
				"entity's record"
		);

		// the category side, where reflection inverted both types: the partition of product 1 keeps the shared
		// category's inherited `ReferencedEntityPredecessor` under product 1, its `Predecessor` under the category
		theEngines.step(
			"product 1 stops referencing its own category, which leaves its reflected partition",
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, 1))
		);
		assertRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(1), SHARED_CATEGORY);
		final int productPartition = partitionOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(1));
		final VirtualReferencePartitionIndex categoryRegistry =
			registryOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, REFERENCE_PRODUCTS);
		assertNotNull(categoryRegistry);
		assertTrue(
			categoryRegistry.getChainPredecessor(productOrderOfProducts, productPartition).isPresent(),
			"the inverted `ReferencedEntityPredecessor` must have been extracted by the referenced entity's record"
		);
		assertTrue(
			categoryRegistry.getChainPredecessor(categoryOrderOfProducts, productPartition).isPresent(),
			"the inverted `Predecessor` must have been extracted by the owner's record"
		);
		theEngines.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("owners dropping their last locale value together with the reference leave the registry exact")
	void shouldStayExactWhenOwnersDropTheirLastLocaleValueWithTheReference(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"departedLocale", state, VirtualReferencePartitionDemotionTest::setUpBrandWithLocalizedCompound
		);
		final RepresentativeReferenceKey brand = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_BRAND, BRAND_PK)
		);
		final int partition = partitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, brand);
		final AttributeIndexKey compound = new AttributeIndexKey(
			REFERENCE_BRAND, COMPOUND_BRAND_LABEL_AND_PRIORITY, Locale.ENGLISH
		);

		// the departing owner's locale and reference go together: the partition must drop its locale data, and the
		// demotion must read only the remaining owner's - a compound with an absent localized element
		theEngines.step("product 1 drops its title and its brand in one mutation", dropTitleAndBrand(1));
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, brand, 2);
		final VirtualReferencePartitionIndex registry =
			registryOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, REFERENCE_BRAND);
		assertNotNull(registry);
		assertTrue(
			registry.hasSortValue(compound, partition),
			"the remaining owner's localized compound must have been extracted: " + registry.getDeclaredSortKeys()
		);

		// the remaining owner does the same: the strict mirror removes exactly the extracted compound
		theEngines.step("product 2 drops its title and its brand in one mutation", dropTitleAndBrand(2));
		assertEquals(
			-1, partitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, brand),
			"the partition its last owner left must be swept"
		);
		theEngines.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("both owners of a partition sharing a ReferencedEntityPredecessor record fail alike (#1691)")
	void shouldFailAlikeWhenBothOwnersOfASharedReferencedEntityPredecessorLeave(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"sharedReferencedEntityPredecessor", state,
			VirtualReferencePartitionDemotionTest::setUpSharedReferencedEntityPredecessor
		);
		assertNoRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(1));

		// the first owner's leaving removes the record both owners share (#1691), which the demotion therefore finds
		// absent; the second owner's leaving then fails removing it again - in both engines alike
		theEngines.failingStep(
			"both owners of the shared partition leave it, one after another",
			session -> {
				session.deleteEntity(ENTITY_CATEGORY, 1);
				session.deleteEntity(ENTITY_CATEGORY, 2);
			},
			EvitaInvalidUsageException.class
		);
		assertRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(1), 2);
		final int partition = partitionOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(1));
		assertTrue(
			theEngines.getMaintainingHistory().getCommitted().contains(
				new Transition(TransitionStage.DEMOTED, ENTITY_CATEGORY, REFERENCE_PRODUCTS, Scope.LIVE, partition)
			),
			"the first owner's leaving must have demoted the partition"
		);
		theEngines.restart();
	}

	/**
	 * Applies one category mutation that takes one product out of a shared category and gives the other product a
	 * label in a new locale there - two product mutations of one root mutation, each run by an executor of its own.
	 *
	 * @param engines        the engines
	 * @param label          names the step
	 * @param category       the shared category
	 * @param departing      the product leaving the category
	 * @param remaining      the product staying, which gets the label
	 * @param locale         the new locale of the label
	 * @param departureFirst TRUE to put the departure before the label among the category's mutations
	 * @return the transitions of the category's partition the step passed, mirror points included
	 */
	@Nonnull
	private static List<Transition> demotionStep(
		@Nonnull DifferentialEngines engines,
		@Nonnull String label,
		int category,
		int departing,
		int remaining,
		@Nonnull Locale locale,
		boolean departureFirst
	) {
		final int partition = partitionOfCategory(engines, category);
		final int committedBefore = engines.getMaintainingHistory().getCommitted().size();
		engines.step(
			label,
			session -> {
				final ReferenceKey remainingKey = session.getEntity(ENTITY_CATEGORY, category, referenceContentAll())
					.orElseThrow()
					.getReference(REFERENCE_PRODUCTS, remaining)
					.orElseThrow()
					.getReferenceKey();
				final LocalMutation<?, ?> departure = new RemoveReferenceMutation(REFERENCE_PRODUCTS, departing);
				final LocalMutation<?, ?> newLocale = new ReferenceAttributeMutation(
					remainingKey, new UpsertAttributeMutation(ATTRIBUTE_LABEL, locale, "label " + locale)
				);
				session.upsertEntity(
					new EntityUpsertMutation(
						ENTITY_CATEGORY, category, EntityExistence.MUST_EXIST,
						departureFirst ? List.of(departure, newLocale) : List.of(newLocale, departure)
					)
				);
			}
		);
		final List<Transition> passed = new ArrayList<>(16);
		final List<Transition> committed = engines.getMaintainingHistory().getCommitted();
		for (final Transition transition : committed.subList(committedBefore, committed.size())) {
			if (transition.entityType().equals(ENTITY_PRODUCT) && transition.partitionPrimaryKey() == partition) {
				passed.add(transition);
			}
		}
		assertEquals(
			1, passed.stream().filter(it -> it.stage() == TransitionStage.DEMOTED).count(),
			label + ": the partition must have been demoted once: " + passed
		);
		return passed;
	}

	/**
	 * Returns the stages one partition passed in a history from the given position on, mirror points excluded.
	 *
	 * @param history    the history
	 * @param from       the position to start at
	 * @param entityType the entity type owning the reference
	 * @param partition  the partition
	 * @return the stages in the order they were passed
	 */
	@Nonnull
	private static List<TransitionStage> stagesOf(
		@Nonnull List<Transition> history,
		int from,
		@Nonnull String entityType,
		int partition
	) {
		return history.subList(from, history.size())
			.stream()
			.filter(
				it -> it.entityType().equals(entityType) && it.partitionPrimaryKey() == partition &&
					it.stage() != TransitionStage.MIRRORED
			)
			.map(Transition::stage)
			.toList();
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
		theEngines.trackPartitionIdentity();
		theEngines.prepare(state, setup);
		return theEngines;
	}

	/**
	 * Defines products in three locales whose category reference carries an order, a localized label and a localized
	 * compound of both, reflected on the category with the two attributes inherited; then creates products 1 and 2,
	 * each the only owner of the category of its own primary key, both sharing {@link #SHARED_CATEGORY} and
	 * {@link #SECOND_SHARED_CATEGORY} with an English label.
	 *
	 * The reflected reference does not inherit the compound: a category removing a reflected reference whose inherited
	 * localized compound it carries fails in the reference index mutator whether or not the registry is maintained,
	 * which is not what this fixture is about.
	 *
	 * @param session the session to write in
	 */
	private static void setUpLocales(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY).withLocale(Locale.ENGLISH, CZECH, Locale.GERMAN).updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(Locale.ENGLISH, CZECH, Locale.GERMAN)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterable().sortable().nullable())
					.withAttribute(ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().sortable().nullable())
					.withSortableAttributeCompound(
						COMPOUND_LABEL_AND_ORDER,
						new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					)
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.withAttributesInherited(ATTRIBUTE_ORDER, ATTRIBUTE_LABEL)
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
		for (int pk = 1; pk <= FREE_CATEGORY; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
		}
		session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, SHARED_CATEGORY));
		session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, SECOND_SHARED_CATEGORY));
		for (int pk = 1; pk <= 2; pk++) {
			final int productPrimaryKey = pk;
			session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, pk)
					.setReference(
						REFERENCE_CATEGORIES, pk,
						ref -> ref.setAttribute(ATTRIBUTE_ORDER, productPrimaryKey)
							.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "own " + productPrimaryKey)
					)
					.setReference(
						REFERENCE_CATEGORIES, SHARED_CATEGORY,
						ref -> ref.setAttribute(ATTRIBUTE_ORDER, 10 * productPrimaryKey)
							.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "shared " + productPrimaryKey)
					)
					.setReference(
						REFERENCE_CATEGORIES, SECOND_SHARED_CATEGORY,
						ref -> ref.setAttribute(ATTRIBUTE_ORDER, 20 * productPrimaryKey)
							.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "second " + productPrimaryKey)
					)
			);
		}
	}

	/**
	 * Defines products whose category reference carries an order and both chain types, reflected on the category with
	 * its attributes inherited; then creates product 1 in its own category and in {@link #SHARED_CATEGORY}, where it
	 * heads both chains, and product 2 in the shared category after product 1 in the `Predecessor` chain only.
	 *
	 * Only one owner of any partition carries a `ReferencedEntityPredecessor` value, on either side of the reflection:
	 * the partition indexes it under the referenced entity, which several owners would share (#1691).
	 *
	 * @param session the session to write in
	 */
	private static void setUpChains(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
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
		session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, SHARED_CATEGORY));
		session.upsertEntity(
			session.createNewEntity(ENTITY_PRODUCT, 1)
				.setReference(REFERENCE_CATEGORIES, 1, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 1))
				.setReference(
					REFERENCE_CATEGORIES, SHARED_CATEGORY,
					ref -> ref.setAttribute(ATTRIBUTE_ORDER, 1)
						.setAttribute(ATTRIBUTE_PRODUCT_ORDER, Predecessor.HEAD)
						.setAttribute(ATTRIBUTE_CATEGORY_ORDER, ReferencedEntityPredecessor.HEAD)
				)
		);
		session.upsertEntity(
			session.createNewEntity(ENTITY_PRODUCT, 2)
				.setReference(
					REFERENCE_CATEGORIES, SHARED_CATEGORY,
					ref -> ref.setAttribute(ATTRIBUTE_ORDER, 2)
						.setAttribute(ATTRIBUTE_PRODUCT_ORDER, new Predecessor(1))
				)
		);
	}

	/**
	 * Defines products in two locales with a localized title, and a brand reference indexed for filtering whose
	 * localized compound pairs a localized label nobody sets with a priority; then creates two products of the same
	 * brand, each with an English title only and a priority of its own. The compound is indexed in English for both
	 * owners merely because their titles put them in the locale - the shape in which a departing owner's locale data
	 * used to stay behind in the partition, where it orders before the remaining owner's value.
	 *
	 * @param session the session to write in
	 */
	private static void setUpBrandWithLocalizedCompound(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(ATTRIBUTE_TITLE, String.class, thatIs -> thatIs.localized().nullable())
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.withAttribute(ATTRIBUTE_BRAND_LABEL, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTRIBUTE_PRIORITY, Integer.class, thatIs -> thatIs.nullable())
					.withSortableAttributeCompound(
						COMPOUND_BRAND_LABEL_AND_PRIORITY,
						new AttributeElement(ATTRIBUTE_BRAND_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_PRIORITY, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
			)
			.updateVia(session);
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
		for (int pk = 1; pk <= 2; pk++) {
			final int priority = pk;
			session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, pk)
					.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "title " + pk)
					.setReference(REFERENCE_BRAND, BRAND_PK, ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, 10 * priority))
			);
		}
	}

	/**
	 * Defines categories with a direct reference to products indexed for filtering, carrying a sortable
	 * `ReferencedEntityPredecessor`; then creates categories 1 and 2 that both list product 1 - each at its own
	 * position in its own order, which their shared partition of product 1 indexes under the one record of product 1
	 * (#1691).
	 *
	 * @param session the session to write in
	 */
	private static void setUpSharedReferencedEntityPredecessor(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT).updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withReferenceToEntity(
				REFERENCE_PRODUCTS, ENTITY_PRODUCT, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.withAttribute(
						ATTRIBUTE_CATEGORY_ORDER, ReferencedEntityPredecessor.class,
						thatIs -> thatIs.sortable().nullable()
					)
			)
			.updateVia(session);
		for (int pk = 1; pk <= 3; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, pk));
		}
		session.upsertEntity(
			session.createNewEntity(ENTITY_CATEGORY, 1)
				.setReference(
					REFERENCE_PRODUCTS, 1,
					ref -> ref.setAttribute(ATTRIBUTE_CATEGORY_ORDER, new ReferencedEntityPredecessor(2))
				)
				.setReference(
					REFERENCE_PRODUCTS, 2,
					ref -> ref.setAttribute(ATTRIBUTE_CATEGORY_ORDER, ReferencedEntityPredecessor.HEAD)
				)
		);
		session.upsertEntity(
			session.createNewEntity(ENTITY_CATEGORY, 2)
				.setReference(
					REFERENCE_PRODUCTS, 1,
					ref -> ref.setAttribute(ATTRIBUTE_CATEGORY_ORDER, ReferencedEntityPredecessor.HEAD)
				)
				.setReference(
					REFERENCE_PRODUCTS, 3,
					ref -> ref.setAttribute(ATTRIBUTE_CATEGORY_ORDER, new ReferencedEntityPredecessor(1))
				)
		);
	}

	/**
	 * Creates a step removing a product's only localized entity attribute - the one value that keeps it in English -
	 * together with its brand, in a single entity mutation.
	 *
	 * @param productPk the product
	 * @return the step
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> dropTitleAndBrand(int productPk) {
		return session -> session.getEntity(ENTITY_PRODUCT, productPk, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.removeAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH)
			.removeReference(REFERENCE_BRAND, BRAND_PK)
			.upsertVia(session);
	}

	/**
	 * Returns the sort key of the localized label of the category reference in a locale.
	 *
	 * @param locale the locale
	 * @return the key
	 */
	@Nonnull
	private static AttributeIndexKey labelKey(@Nonnull Locale locale) {
		return new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_LABEL, locale);
	}

	/**
	 * Returns the sort key of the localized compound of the category reference in a locale.
	 *
	 * @param locale the locale
	 * @return the key
	 */
	@Nonnull
	private static AttributeIndexKey compoundKey(@Nonnull Locale locale) {
		return new AttributeIndexKey(REFERENCE_CATEGORIES, COMPOUND_LABEL_AND_ORDER, locale);
	}

	/**
	 * Returns the primary key of the live partition of a category in the maintaining engine.
	 *
	 * @param engines  the engines
	 * @param category the category
	 * @return the partition primary key
	 */
	private static int partitionOfCategory(@Nonnull DifferentialEngines engines, int category) {
		final int partition = partitionOf(engines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(category));
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
}
