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
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.virtual.PromotionRehearsalInspector.Outcome;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.category;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.partitionOf;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.product;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.updateProduct;
import static io.evitadb.index.virtual.SortValueAndChainFixture.ATTRIBUTE_CATEGORY_ORDER;
import static io.evitadb.index.virtual.SortValueAndChainFixture.ATTRIBUTE_PRODUCT_ORDER;
import static io.evitadb.index.virtual.SortValueAndChainFixture.ATTRIBUTE_SPAN;
import static io.evitadb.index.virtual.SortValueAndChainFixture.DECIMAL_PLACES;
import static io.evitadb.index.virtual.SortValueAndChainFixture.fillReference;
import static io.evitadb.index.virtual.SortValueAndChainFixture.spanOf;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rehearses the promotion of single-owner reference partitions - a virtual partition gaining its second owner has to
 * be materialized from its first owner's data - at every point a partition gains its second owner (1 -> 2), in the
 * maintaining engine of {@link DifferentialEngines}, in a catalog warming up and in a live one. The live partitions
 * are still real, so each rehearsal writes the first owner into a partition no collection knows, through the
 * production write a materialization uses, and compares it with the live partition on the spot (see
 * {@link PromotionRehearsalInspector}).
 *
 * The histories are the ones that decide whether the rebuilt partition is the live one: every sortable type and
 * compound, a range compound element updated incrementally before the promotion, a partition reached again after a
 * restart, a first owner mutated by the very root mutation that promotes its partition (a product joining two new
 * categories at once, and a category dropping or gaining the first owner as its main product before it adds a second
 * owner - the first owner's pending locale changes), a promotion inside a mutation that fails afterwards, and a
 * transaction going on after a promotion before it commits.
 *
 * The rehearsal runs in the maintaining engine only, so the comparison of the two engines at every checkpoint is also
 * the proof that it changes nothing either engine can be asked about - inside the open session, after a caught
 * failure, after the commit and after a restart. In `ALIVE` every promotion point is passed twice - in the session
 * and once more when the commit replays the transaction onto the trunk - and both are rehearsed, so the commit's
 * check that every transactional layer of the transaction was swept covers the rebuilt partitions too.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition promotion rehearsal")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class VirtualReferencePartitionPromotionRehearsalTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = SingleOwnerPartitionFixture.ENTITY_PRODUCT;
	private static final String ENTITY_CATEGORY = SingleOwnerPartitionFixture.ENTITY_CATEGORY;
	private static final Set<String> ENTITY_TYPES = Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY);
	private static final String REFERENCE_CATEGORIES = SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
	private static final String REFERENCE_PRODUCTS = SingleOwnerPartitionFixture.REFERENCE_PRODUCTS;
	/**
	 * Filterable and sortable.
	 */
	private static final String ATTRIBUTE_ORDER = SortValueAndChainFixture.ATTRIBUTE_ORDER;
	/**
	 * Localized and sortable.
	 */
	private static final String ATTRIBUTE_LABEL = SortValueAndChainFixture.ATTRIBUTE_LABEL;
	/**
	 * The main category of a product, reflected on the category as {@link #REFERENCE_MAIN_PRODUCTS}.
	 */
	private static final String REFERENCE_MAIN_CATEGORY = "mainCategory";
	private static final String REFERENCE_MAIN_PRODUCTS = "mainProducts";
	/**
	 * Localized, on the main category reference.
	 */
	private static final String ATTRIBUTE_NOTE = "note";
	/**
	 * A localized compound of the locale fixture: the label, then the order descending with nulls first.
	 */
	private static final String COMPOUND_LABEL_AND_ORDER = "labelAndOrder";
	/**
	 * A product none of the fixtures creates.
	 */
	private static final int NEW_PRODUCT = 9;
	private static final Locale CZECH = SortValueAndChainFixture.CZECH;

	@Nullable private DifferentialEngines engines;
	@Nullable private PromotionRehearsalInspector inspector;

	@AfterEach
	void tearDown() {
		if (this.engines != null) {
			this.engines.close();
			this.engines = null;
		}
		this.inspector = null;
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("every sortable type and compound of the first owner is rebuilt exactly, before and after a restart")
	void shouldRebuildEverySortableTypeOfTheFirstOwnerWhenASecondOwnerJoins(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"types", state, SortValueAndChainFixture::setUpTypes
		);

		theEngines.step(
			"product 2 joins the single-owner partition of category 1",
			updateProduct(2, it -> it.setReference(REFERENCE_CATEGORIES, 1, ref -> fillReference(ref, 6)))
		);
		final List<Outcome> joined = assertRehearsed(state, "product 2 joins category 1", 0, 1);
		final Outcome outcome = joined.get(joined.size() - 1);
		assertEquals(
			partitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(1)), outcome.partition()
		);
		assertEquals(1, outcome.owner(), "the first owner is rebuilt");
		assertEquals(2, outcome.joiningOwner());
		// the comparison is not vacuous: the first owner's value of every sortable type and compound, its filter-only
		// and unique-only values and its membership are all part of it
		for (final String line : List.of(
			"members", "sort/categories.order", "sort/categories.price", "sort/categories.validFrom",
			"sort/categories.releasedAt", "sort/categories.opensAt", "sort/categories.label@en",
			"sort/categories.language", "sort/categories.currency", "sort/categories.span",
			"sort/categories.labelAndPrice@en", "sort/categories.typed", "filter/categories.order",
			"filter/categories.filtered", "unique/categories.uniqueCode"
		)) {
			assertTrue(outcome.expected().containsKey(line), "the line " + line + " must be compared: " + outcome);
		}

		// a reloaded first owner is read from storage alone
		theEngines.restart();
		final int beforeRestartJoin = requireInspector().getOutcomes().size();
		theEngines.step(
			"product 3 joins the single-owner partition of category 4 after a restart",
			updateProduct(3, it -> it.setReference(REFERENCE_CATEGORIES, 4, ref -> fillReference(ref, 7)))
		);
		final Outcome afterRestart = assertRehearsed(
			state, "product 3 joins category 4", beforeRestartJoin, 1
		).get(0);
		assertEquals(4, afterRestart.owner());
		assertEquals(3, afterRestart.joiningOwner());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a range compound element updated incrementally is rebuilt from storage at the same scale")
	void shouldRebuildAnIncrementallyUpdatedRangeCompoundElement(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"ranges", state, SortValueAndChainFixture::setUpTypes
		);
		assertNotEquals(
			DECIMAL_PLACES, spanOf(1).getEffectiveRetainedDecimalPlaces(),
			"the premise: the fixture writes ranges at another scale than the schema indexes them at"
		);

		// the live partition gets the changed range element from the mutation, the rebuilt one from the stored
		// reference - both must hold it at the schema scale
		theEngines.step(
			"the span of product 1 in category 1 is updated",
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
		final int beforeJoin = requireInspector().getOutcomes().size();
		theEngines.step(
			"product 3 joins the single-owner partition of category 1",
			updateProduct(3, it -> it.setReference(REFERENCE_CATEGORIES, 1, ref -> fillReference(ref, 7)))
		);
		final Outcome outcome = assertRehearsed(state, "product 3 joins category 1", beforeJoin, 1).get(0);
		assertEquals(1, outcome.owner());
		assertTrue(
			outcome.expected().get("sort/categories.typed")
				.contains("encodedFrom=-150, encodedTo=830, decimalPlaces=2"),
			"the updated span must be part of the compared compound, at the schema scale: " + outcome.expected()
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a first owner mutated by the same root mutation is rebuilt from its staged reference")
	void shouldRebuildAFirstOwnerStagedInTheSameRootMutation(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"sameRoot", state, SortValueAndChainFixture::setUpChains
		);

		// one product upsert adds two new categories: each category gets the reflected reference in an executor of
		// its own within the root mutation, so the second one promotes the product's partition on the category side
		// while the first one's reference exists only in its executor
		theEngines.step(
			"a new product joins two categories nobody references in one upsert",
			session -> session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, NEW_PRODUCT)
					.setReference(
						REFERENCE_CATEGORIES, 4,
						ref -> ref.setAttribute(ATTRIBUTE_ORDER, 40)
							.setAttribute(ATTRIBUTE_PRODUCT_ORDER, new Predecessor(3))
							.setAttribute(ATTRIBUTE_CATEGORY_ORDER, new ReferencedEntityPredecessor(3))
					)
					.setReference(
						REFERENCE_CATEGORIES, 5,
						ref -> ref.setAttribute(ATTRIBUTE_ORDER, 50)
							.setAttribute(ATTRIBUTE_CATEGORY_ORDER, new ReferencedEntityPredecessor(4))
					)
			)
		);
		final List<Outcome> outcomes = assertRehearsed(state, "the new product joins two categories", 0, 1);
		final Outcome outcome = outcomes.get(outcomes.size() - 1);
		assertEquals(ENTITY_CATEGORY, outcome.entityType());
		assertEquals(
			partitionOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT)),
			outcome.partition()
		);
		assertTrue(
			outcome.expected().containsKey("sort/products.order") &&
				outcome.expected().containsKey("chain/products.categoryOrder"),
			"the staged reference of the first owner must be part of the comparison: " + outcome.expected()
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a locale the first owner loses in the promoting root mutation is kept until its finalization")
	void shouldKeepALocaleTheFirstOwnerLosesInTheSameRootMutation(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"removedLocale", state, VirtualReferencePartitionPromotionRehearsalTest::setUpLocales
		);

		// the category drops its main product - product 1, which loses its only Czech value with it - before it gets
		// product 2, which joins the partition product 1 owns alone; the collector dispatches the reflected changes in
		// the order the category lists them, so product 1's mutation has run, but not applied its changes, when the
		// partition is promoted
		theEngines.step(
			"category 1 drops its main product 1 and gets product 2",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, 1, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(REFERENCE_MAIN_PRODUCTS, 1),
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, 2))
				)
			)
		);
		final Outcome productSide = productSideOf(
			assertRehearsed(state, "category 1 drops its main product", 0, 2)
		);
		assertEquals(1, productSide.owner());
		assertTrue(
			productSide.pendingLocales().stream().anyMatch(it -> it.startsWith("-cs")),
			"the first owner must have had the Czech locale pending for removal: " + productSide
		);
		// the rebuilt partition holds the locales before the root mutation, as the live one does until the first
		// owner's mutation applies its changes and moves both
		assertTrue(
			productSide.rebuilt().containsKey("language/cs") &&
				productSide.rebuilt().containsKey("sort/categories." + COMPOUND_LABEL_AND_ORDER + "@cs"),
			"the rebuilt partition must still know the Czech locale: " + productSide.rebuilt()
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a locale the first owner gains in the promoting root mutation is left to its finalization")
	void shouldLeaveALocaleTheFirstOwnerGainsInTheSameRootMutationToItsFinalization(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"addedLocale", state, VirtualReferencePartitionPromotionRehearsalTest::setUpLocales
		);

		// the category makes product 3 its main product with a German note - product 3's first German value - before
		// it gets product 2, which joins the partition product 3 owns alone; the builder lists the main product
		// reference first, as it orders references by name
		theEngines.step(
			"category 3 gets main product 3 with a German note and gets product 2",
			session -> session.getEntity(ENTITY_CATEGORY, 3, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(
					REFERENCE_MAIN_PRODUCTS, 3, ref -> ref.setAttribute(ATTRIBUTE_NOTE, Locale.GERMAN, "Haupt")
				)
				.setReference(REFERENCE_PRODUCTS, 2)
				.upsertVia(session)
		);
		final List<Outcome> outcomes = assertRehearsed(state, "category 3 gets a main product", 0, 2);
		final Outcome productSide = productSideOf(outcomes);
		assertEquals(3, productSide.owner());
		assertTrue(
			productSide.pendingLocales().stream().anyMatch(it -> it.startsWith("+de")),
			"the first owner must have had the German locale pending for addition: " + productSide
		);
		// the rebuilt partition holds the locales before the root mutation, as the live one does until the first
		// owner's mutation applies its changes and moves both
		assertTrue(
			productSide.rebuilt().keySet().stream().noneMatch(it -> it.endsWith("de")),
			"the rebuilt partition must not know the German locale yet: " + productSide.rebuilt()
		);
		assertTrue(
			productSide.rebuilt().containsKey("sort/categories." + COMPOUND_LABEL_AND_ORDER + "@en"),
			"the comparison must cover the localized compound of a locale the first owner had: " + productSide
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a rehearsal inside a mutation that fails afterwards leaves both engines alike, a retry rehearses")
	void shouldRehearseInsideAMutationThatFailsAfterwards(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"failure", state, SortValueAndChainFixture::setUpTypes
		);
		final Consumer<EvitaSessionContract> join = updateProduct(
			2, it -> it.setReference(REFERENCE_CATEGORIES, 1, ref -> fillReference(ref, 6))
		);

		theEngines.failingStepAfter(
			"product 2 joins category 1 and fails right after the row is dropped",
			TransitionStage.SHARED, ENTITY_PRODUCT, 0, join
		);
		// the failed root mutation is rolled back in the session and never reaches the trunk
		assertEquals(1, requireInspector().assertAllMatched("the failed join", 0).size());

		theEngines.step("product 2 joins category 1 again", join);
		assertRehearsed(state, "the retried join", 1, 1);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a transaction goes on after a rehearsal and commits, the trunk replay rehearses it again")
	void shouldGoOnAndCommitAfterARehearsal(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"goOn", state, SortValueAndChainFixture::setUpTypes
		);

		theEngines.transaction(
			"two partitions are promoted while their owners keep writing",
			List.of(
				updateProduct(2, it -> it.setReference(REFERENCE_CATEGORIES, 1, ref -> fillReference(ref, 6))),
				updateProduct(1, it -> it.updateReference(REFERENCE_CATEGORIES, 1, ref -> fillReference(ref, 8))),
				updateProduct(4, it -> it.setReference(REFERENCE_CATEGORIES, 3, ref -> fillReference(ref, 7))),
				updateProduct(3, it -> it.removeReference(REFERENCE_CATEGORIES, 3))
			)
		);
		final List<Outcome> outcomes = assertRehearsed(state, "the transaction", 0, 2);
		assertEquals(
			Set.of(1, 3), Set.copyOf(outcomes.stream().map(Outcome::owner).toList()),
			"the first owners of both promoted partitions are rebuilt"
		);
		theEngines.restart();
	}

	/**
	 * Asserts the rehearsals since the given position: each matched, and there is one per promotion point the step
	 * passed - two in `ALIVE`, where the commit's replay onto the trunk passes it again.
	 *
	 * @param state      the catalog state
	 * @param label      names the step
	 * @param from       the number of outcomes before the step
	 * @param promotions how many promotion points the step passed
	 * @return the outcomes of the step, the session's first
	 */
	@Nonnull
	private List<Outcome> assertRehearsed(
		@Nonnull CatalogState state,
		@Nonnull String label,
		int from,
		int promotions
	) {
		final List<Outcome> outcomes = requireInspector().assertAllMatched(label, from);
		assertEquals(
			state == CatalogState.ALIVE ? 2 * promotions : promotions, outcomes.size(),
			label + ": one rehearsal per promotion point, passed in the session and in the trunk replay alike: " +
				outcomes
		);
		return outcomes;
	}

	/**
	 * Returns the rehearsal of the product's partition of category 1 among the outcomes of a step of the locale
	 * fixture - the first of them, which is the session's in `ALIVE`.
	 *
	 * @param outcomes the outcomes of the step
	 * @return the outcome of the product side
	 */
	@Nonnull
	private static Outcome productSideOf(@Nonnull List<Outcome> outcomes) {
		return outcomes.stream()
			.filter(it -> ENTITY_PRODUCT.equals(it.entityType()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("the product side was not rehearsed: " + outcomes));
	}

	/**
	 * Starts the engines, prepares the catalog in the given state, installs a rehearsal inspector in the maintaining
	 * engine only and remembers both for the tear-down.
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
		final PromotionRehearsalInspector theInspector = new PromotionRehearsalInspector();
		this.inspector = theInspector;
		theEngines.getMaintainingHistory().setPromotionInspector(theInspector);
		return theEngines;
	}

	/**
	 * Returns the inspector of the running test.
	 *
	 * @return the inspector
	 */
	@Nonnull
	private PromotionRehearsalInspector requireInspector() {
		assertNotNull(this.inspector, "the engines were not started");
		return this.inspector;
	}

	/**
	 * Defines products and categories in three locales. The products' category reference carries a filterable order,
	 * a localized label and a localized compound of both; their main category reference carries a localized note. Both
	 * are reflected on the category with their attributes inherited. Product `n` (1 to 3) is the only owner of
	 * category `n` with an English label; product 1 is also the main product of category 1 with a Czech note - its
	 * only Czech value.
	 *
	 * @param session the session to write in
	 */
	private static void setUpLocales(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY).withLocale(Locale.ENGLISH, CZECH, Locale.GERMAN).updateVia(session);
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
			.withReferenceToEntity(
				REFERENCE_MAIN_CATEGORY, ENTITY_CATEGORY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.withAttribute(ATTRIBUTE_NOTE, String.class, thatIs -> thatIs.localized().nullable())
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
			.withReflectedReferenceToEntity(
				REFERENCE_MAIN_PRODUCTS, ENTITY_PRODUCT, REFERENCE_MAIN_CATEGORY,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.withAttributesInherited()
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
		for (int pk = 1; pk <= 3; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
		}
		for (int pk = 1; pk <= 3; pk++) {
			final int productPrimaryKey = pk;
			session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, pk)
					.setReference(
						REFERENCE_CATEGORIES, pk,
						ref -> ref.setAttribute(ATTRIBUTE_ORDER, productPrimaryKey)
							.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "label " + productPrimaryKey)
					)
			);
		}
		session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(REFERENCE_MAIN_CATEGORY, 1, ref -> ref.setAttribute(ATTRIBUTE_NOTE, CZECH, "hlavní"))
			.upsertVia(session);
	}
}
