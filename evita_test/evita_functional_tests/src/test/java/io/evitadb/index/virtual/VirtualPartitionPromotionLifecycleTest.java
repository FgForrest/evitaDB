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
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.index.virtual.TransitionHistory.Transition;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static io.evitadb.index.virtual.DifferentialEngines.assertNoRegisteredPartition;
import static io.evitadb.index.virtual.DifferentialEngines.assertRegistered;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ATTRIBUTE_LABEL;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ATTRIBUTE_ORDER;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ATTRIBUTE_POSITION;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_CATEGORY;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_PRODUCT;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_TYPES;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_PRODUCTS;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.assertNoPartitionObjectOfVirtualReference;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.category;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.findCollectionOf;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.newProduct;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.product;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.registeredPartitionOf;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.updateProduct;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO JNO - remove at the end of #1615
/**
 * Drives reference partitions written as registry rows across the boundary of their owner count: a single-owner
 * partition gaining its second owner (1 -> 2) becomes an index object holding both owners, and a partition left with
 * one owner of two (2 -> 1) becomes a row again, its index object retired. The catalog writing virtually is compared
 * with its twin writing every partition as an index object next to the registry it maintains (see
 * {@link DifferentialEngines#writtenVirtually}). Every history runs in a catalog warming up and in a live one.
 *
 * The harness compares the two engines at every checkpoint - inside the session after every step, after a caught
 * failure, after the session closes, after a restart and after a crash recovery - so a promoted partition must hold
 * exactly what the twin's partition holds of both owners, and a demoted one must have the row the twin keeps next to
 * its object. Checks run on the catalog writing virtually at every checkpoint refuse any index object with fewer than
 * two owners of a partition of a reference whose partitions may be virtual, and any partition the reduced-index
 * membership knows that is neither a row nor an index object; the body oracle of the harness holds the primary key of
 * every partition across the checkpoints (see {@link PartitionIdentityLedger}). What a history left on disk is checked
 * once it ends (see {@link VirtualPartitionInvariants#assertStoredParts}). Every test asserts what its history must
 * have produced - an index object with both owners and no row after a promotion, a row and no index object after a
 * demotion, the primary key the partition had all along - so that no test passes by not exercising what it names.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Single-owner partitions promoted to index objects and demoted to registry rows")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class VirtualPartitionPromotionLifecycleTest implements EvitaTestSupport {
	/**
	 * A product the singleton fixture does not create.
	 */
	private static final int NEW_PRODUCT = 9;
	/**
	 * A category no product of the singleton fixture references.
	 */
	private static final int FREE_TARGET = 11;
	/**
	 * Another category no product of the singleton fixture references.
	 */
	private static final int SECOND_FREE_TARGET = 12;
	/**
	 * A checkpoint interval that never elapses within a test, so the committed transactions stay in the write-ahead
	 * log.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;
	/**
	 * The reference of the locale fixture through which a category gives a product a localized note.
	 */
	private static final String REFERENCE_NOTE_CATEGORY = "noteCategory";
	/**
	 * The reflection of {@link #REFERENCE_NOTE_CATEGORY} on the category.
	 */
	private static final String REFERENCE_NOTE_PRODUCTS = "noteProducts";
	/**
	 * The localized attribute of {@link #REFERENCE_NOTE_CATEGORY}.
	 */
	private static final String ATTRIBUTE_NOTE = "note";
	/**
	 * The localized compound of the category reference of the locale fixture.
	 */
	private static final String COMPOUND_LABEL_AND_ORDER = "labelAndOrder";
	/**
	 * The category both products of the locale fixture share.
	 */
	private static final int SHARED_CATEGORY = 1;
	/**
	 * The category of the locale fixture only {@link #LEAVING_OWNER} references.
	 */
	private static final int OWN_CATEGORY = 2;
	/**
	 * The owner of the shared category of the locale fixture that leaves it.
	 */
	private static final int LEAVING_OWNER = 1;
	/**
	 * The owner of the shared category of the locale fixture that stays in it and gains German.
	 */
	private static final int REMAINING_OWNER = 2;

	@Nullable private DifferentialEngines engines;
	@Nullable private VirtualPartitionHistory history;

	@AfterEach
	void tearDown() {
		if (this.history != null) {
			this.history.close();
			this.history = null;
		}
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
	@DisplayName("a partition goes from no owner to two and back, failing after every stage, under one primary key")
	void shouldPromoteAndDemoteAPartitionThroughEveryStage(CatalogState state) {
		final DifferentialEngines theEngines = start("everyStage", state);
		final Consumer<EvitaSessionContract> enter =
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true).upsertVia(session);
		final Consumer<EvitaSessionContract> join = joinCategory(1, FREE_TARGET);
		final Consumer<EvitaSessionContract> leave =
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_TARGET));
		final Consumer<EvitaSessionContract> vacate =
			updateProduct(NEW_PRODUCT, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_TARGET));

		// 0 -> 1
		theEngines.failingStepAfter("fail after the row entered", TransitionStage.ENTERED, ENTITY_PRODUCT, 0, enter);
		theEngines.step("the first owner enters", enter);
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET)
		);
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT);

		// 1 -> 2
		theEngines.failingStepAfter("fail after the promotion", TransitionStage.SHARED, ENTITY_PRODUCT, 0, join);
		theEngines.failingStepAfter(
			"fail after the second owner's first value was written", TransitionStage.MIRRORED, ENTITY_PRODUCT, 0, join
		);
		theEngines.failingStepAfter(
			"fail after the reflected promotion", TransitionStage.SHARED, ENTITY_CATEGORY, 0, join
		);
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT);
		theEngines.step("the second owner joins", join);
		assertEquals(
			partition,
			assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), 1, NEW_PRODUCT),
			"the promoted partition keeps the primary key of its row"
		);
		assertPromoted(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(1), 1, FREE_TARGET);

		// 2 -> 1
		theEngines.failingStepAfter("fail after the demotion", TransitionStage.DEMOTED, ENTITY_PRODUCT, 0, leave);
		theEngines.failingStepAfter(
			"fail after the reflected demotion", TransitionStage.DEMOTED, ENTITY_CATEGORY, 0, leave
		);
		assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), 1, NEW_PRODUCT);
		theEngines.step("the second owner leaves", leave);
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT);
		assertRegistered(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(1), 1);
		assertEquals(
			partition,
			registeredPartitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET)),
			"the demoted partition keeps its primary key in its row"
		);

		// 1 -> 0
		theEngines.failingStepAfter("fail after the row was vacated", TransitionStage.VACATED, ENTITY_PRODUCT, 0, vacate);
		theEngines.step("the first owner leaves", vacate);
		assertNoRegisteredPartition(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET));
		assertEquals(7, theEngines.getMaintainingHistory().getInjectedFailures(), "every failure must have fired");
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a second owner leaving and entering again in one mutation keeps the partition an index object")
	void shouldKeepAPartitionPromotedWhenItsSecondOwnerLeavesAndEntersAgainInOneMutation(CatalogState state) {
		final DifferentialEngines theEngines = start("leaveAndEnterAgain", state);
		theEngines.step("product 1 joins category 2", joinCategory(1, 2));
		final int partition = assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(2), 1, 2);
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		theEngines.step(
			"product 1 removes and inserts its reference to category 2 in one mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 1, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_CATEGORIES, 2)),
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_CATEGORIES, 2, -1)),
					new ReferenceAttributeMutation(
						new ReferenceKey(REFERENCE_CATEGORIES, 2, -1), new UpsertAttributeMutation(ATTRIBUTE_ORDER, 21)
					)
				)
			)
		);

		assertEquals(partition, assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(2), 1, 2));
		final List<TransitionStage> stages = stagesOfPartitionSince(
			theEngines, committedBefore, ENTITY_PRODUCT, partition
		);
		assertTrue(stages.contains(TransitionStage.SHARED), "the owner must have joined the partition again: " + stages);
		assertFalse(
			stages.contains(TransitionStage.DEMOTED),
			"a partition with two owners when the mutation finishes is not demoted: " + stages
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner leaving a shared partition as another enters it in one root mutation keeps it an object")
	void shouldKeepAPartitionSharedWhenAnotherOwnerEntersItAsOneLeaves(CatalogState state) {
		// one engine, checked at every moment of the history by the body oracle, the identity ledger, the invariants
		// and the stored parts - no twin
		final VirtualPartitionHistory theHistory = new VirtualPartitionHistory(this, "anotherOwnerEnters", null);
		this.history = theHistory;
		theHistory.prepare(state, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
		// the reflected partition of product 1 on the category side is owned by category 1 and the shared one
		final int partition = theHistory.assertIndexObject(
			ENTITY_CATEGORY, Scope.LIVE, product(1), 1, SingleOwnerPartitionFixture.SHARED_CATEGORY
		);

		// the reflected mutations follow the order of the product's: the shared category leaves the partition first,
		// leaving it with one owner, and the free one enters it next - so when the root mutation finishes the partition
		// has two owners again and must not be demoted
		theHistory.step(
			"the shared category leaves the partition and a free one enters it in one root mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 1, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(
						new ReferenceKey(REFERENCE_CATEGORIES, SingleOwnerPartitionFixture.SHARED_CATEGORY)
					),
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_CATEGORIES, SECOND_FREE_TARGET, -1))
				)
			)
		);

		assertEquals(
			partition,
			theHistory.assertIndexObject(ENTITY_CATEGORY, Scope.LIVE, product(1), 1, SECOND_FREE_TARGET),
			"the partition shared all along keeps its primary key"
		);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(SECOND_FREE_TARGET), 1);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a partition promoted and demoted in one root mutation is a row again, also after a reload")
	void shouldInstallAndRetireAnIndexObjectInOneRootMutation(CatalogState state) {
		final DifferentialEngines theEngines = start("installAndRetire", state);
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(2)
		);
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		// the reflected mutations follow the order of the category's: product 1 joins the partition product 2 owns
		// alone, promoting it, and product 2 leaves it next, so the root mutation demotes it to product 1 when it
		// finishes
		theEngines.step(
			"category 2 lists product 1 and stops listing product 2 in one root mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, 2, EntityExistence.MUST_EXIST,
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, 1, -1)),
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, 2))
				)
			)
		);

		assertEquals(
			List.of(TransitionStage.SHARED, TransitionStage.DEMOTED),
			stagesOfPartitionSince(theEngines, committedBefore, ENTITY_PRODUCT, partition).stream()
				.filter(it -> it != TransitionStage.MIRRORED)
				.toList(),
			"the partition must have been promoted and demoted by the one root mutation"
		);
		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(2), 1, partition);
		assertPromoted(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(1), 1, 2);

		theEngines.restart();
		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(2), 1, partition);
		assertPromoted(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(1), 1, 2);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a shared partition both owners leave in one root mutation leaves the reduced-index membership too")
	void shouldDropAPartitionBothOwnersLeaveInOneRootMutationFromTheMembership(CatalogState state) {
		final DifferentialEngines theEngines = start("bothOwnersLeave", state);
		theEngines.step("product 1 joins category 3", joinCategory(1, 3));
		final int partition = assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(3), 1, 3);
		assertCovered(theEngines.getMaintaining(), partition, true, "the shared partition before both owners leave");
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		// the reflected removals: product 1 leaves the shared partition (2 -> 1), product 3 leaves it empty (1 -> 0),
		// which retires its index object while the root mutation still runs - and nothing enters it again
		theEngines.step(
			"category 3 stops listing products 1 and 3 in one root mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, 3, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, 1)),
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, 3))
				)
			)
		);

		assertTrue(
			stagesOfPartitionSince(theEngines, committedBefore, ENTITY_PRODUCT, partition)
				.contains(TransitionStage.VACATED),
			"the root mutation must have emptied the partition"
		);
		assertNoRegisteredPartition(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(3));
		assertCovered(theEngines.getMaintaining(), partition, false, "the emptied partition");
		assertCovered(theEngines.getReference(), partition, false, "the emptied partition of the twin");

		theEngines.restart();
		assertCovered(theEngines.getMaintaining(), partition, false, "the emptied partition after a reload");
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a partition emptied and entered again before its demotion keeps its row and loses its index object")
	void shouldRetireTheIndexObjectOfAPartitionEnteredAgainBeforeItsDemotion(CatalogState state) {
		final DifferentialEngines theEngines = start("enteredBeforeDemotion", state);
		theEngines.step("product 1 joins category 3", joinCategory(1, 3));
		final int partition = assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(3), 1, 3);
		final int referenceBefore = theEngines.getReferenceHistory().getCommitted().size();
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		// the reflected removals run before the reflected insertion: product 1 leaves the shared partition (2 -> 1),
		// product 3 leaves it empty (1 -> 0), which retires its index object and reserves its primary key, and product 2
		// enters it as its row under that primary key (0 -> 1) - so the demotion of the partition product 1 left finds
		// the row in place and nothing left to retire
		theEngines.step(
			"category 3 stops listing products 1 and 3 and lists product 2 in one root mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, 3, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, 1)),
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, 2, -1)),
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, 3))
				)
			)
		);

		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(3), 2, partition);
		assertEquals(
			List.of(TransitionStage.VACATED, TransitionStage.ENTERED, TransitionStage.DEMOTED),
			stagesOfPartitionSince(theEngines, committedBefore, ENTITY_PRODUCT, partition).stream()
				.filter(it -> it != TransitionStage.MIRRORED)
				.toList(),
			"the partition must have been emptied, entered again and demoted"
		);
		// a row vacated without an index object reports no removal of its values, so those are left out
		final List<Transition> twin = theEngines.getReferenceHistory().getCommitted();
		final List<Transition> maintaining = theEngines.getMaintainingHistory().getCommitted();
		assertEquals(
			twin.subList(referenceBefore, twin.size()).stream()
				.filter(it -> it.stage() != TransitionStage.MIRRORED)
				.toList(),
			maintaining.subList(committedBefore, maintaining.size()).stream()
				.filter(it -> it.stage() != TransitionStage.MIRRORED)
				.toList(),
			"both engines pass the same owner transitions"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("one root mutation promotes partitions of several first owners, one of them entering in the same root")
	void shouldPromotePartitionsInTheRootMutationTheirFirstOwnerEntersIn(CatalogState state) {
		final DifferentialEngines theEngines = start("sameRoot", state);
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		// the new product enters two free categories: on the category side the first of them enters the product's
		// reflected partition and the second joins it within the same root mutation, so the promotion reads a first
		// owner that the root mutation is mutating
		theEngines.step(
			"a new product enters two free categories",
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true)
				.setReference(
					REFERENCE_CATEGORIES, SECOND_FREE_TARGET,
					whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, SECOND_FREE_TARGET)
						.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Label " + SECOND_FREE_TARGET)
				)
				.upsertVia(session)
		);
		final int reflected = assertPromoted(
			theEngines, ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), FREE_TARGET, SECOND_FREE_TARGET
		);
		assertEquals(
			List.of(TransitionStage.ENTERED, TransitionStage.SHARED),
			stagesOfPartitionSince(theEngines, committedBefore, ENTITY_CATEGORY, reflected).stream()
				.filter(it -> it != TransitionStage.MIRRORED)
				.toList(),
			"the reflected partition must have been entered and promoted by the one root mutation"
		);

		// another new product joins two partitions of two different first owners at once
		theEngines.step(
			"another new product joins categories 1 and 2",
			session -> session.createNewEntity(ENTITY_PRODUCT, NEW_PRODUCT + 1)
				.setAttribute(SingleOwnerPartitionFixture.ATTRIBUTE_CODE, "P" + (NEW_PRODUCT + 1))
				.setAttribute(SingleOwnerPartitionFixture.ATTRIBUTE_NAME, Locale.GERMAN, "Neu")
				.setReference(
					REFERENCE_CATEGORIES, 1,
					whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, 101)
						.setAttribute(ATTRIBUTE_LABEL, Locale.GERMAN, "Eins")
				)
				.setReference(
					REFERENCE_CATEGORIES, 2,
					whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, 102)
						.setAttribute(ATTRIBUTE_POSITION, new Predecessor(2))
				)
				.upsertVia(session)
		);
		assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(1), 1, NEW_PRODUCT + 1);
		assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(2), 2, NEW_PRODUCT + 1);
		assertPromoted(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT + 1), 1, 2);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a promoted partition keeps being written by both owners and is demoted to the remaining one")
	void shouldKeepWritingAPromotedPartitionAndDemoteIt(CatalogState state) {
		final DifferentialEngines theEngines = start("continued", state);
		theEngines.step("product 3 joins category 4", joinCategory(3, 4));
		final int partition = assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4), 3, 4);

		theEngines.step(
			"the first owner changes its order",
			updateProduct(
				4,
				it -> it.setReference(REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 400))
			)
		);
		theEngines.step(
			"the second owner gains German through its reference",
			updateProduct(
				3,
				it -> it.setReference(
					REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_LABEL, Locale.GERMAN, "Vier")
				)
			)
		);
		theEngines.step(
			"the first owner loses German",
			updateProduct(
				4,
				it -> it.removeAttribute(SingleOwnerPartitionFixture.ATTRIBUTE_NAME, Locale.GERMAN)
					.setReference(
						REFERENCE_CATEGORIES, 4, ref -> ref.removeAttribute(ATTRIBUTE_LABEL, Locale.GERMAN)
					)
			)
		);
		assertEquals(partition, assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4), 3, 4));

		theEngines.step(
			"the first owner leaves", updateProduct(4, it -> it.removeReference(REFERENCE_CATEGORIES, 4))
		);
		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(4), 3, partition);
		theEngines.step(
			"the remaining owner changes its label",
			updateProduct(
				3,
				it -> it.setReference(
					REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Four")
				)
			)
		);
		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(4), 3, partition);
		// product 3 stays in categories 3 and 4, so its reflected partition keeps both of them
		assertPromoted(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(3), 3, 4);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a partition of every sort type and a range compound is promoted and demoted exactly")
	void shouldPromoteAndDemoteEverySortType(CatalogState state) {
		final DifferentialEngines theEngines = startWith(
			"sortTypes", state, Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY), SortValueAndChainFixture::setUpTypes
		);
		final int shared = SortValueAndChainFixture.SHARED_CATEGORY;
		// the fixture's shared category was promoted by its second owner while the fixture was written
		final int sharedPartition = assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(shared), 1, 2);
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(1)
		);

		theEngines.step(
			"product 3 joins category 1 with values of every type",
			SingleOwnerPartitionFixture.updateProduct(
				3,
				it -> it.setReference(
					REFERENCE_CATEGORIES, 1, ref -> SortValueAndChainFixture.fillReference(ref, 7)
				)
			)
		);
		assertEquals(partition, assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(1), 1, 3));

		theEngines.step(
			"product 1 leaves the shared category",
			SingleOwnerPartitionFixture.updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, shared))
		);
		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(shared), 2, sharedPartition);
		theEngines.step(
			"product 1 leaves category 1",
			SingleOwnerPartitionFixture.updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, 1))
		);
		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(1), 3, partition);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("partitions with both chain types are promoted from their first owner and demoted to it")
	void shouldPromoteAndDemoteChains(CatalogState state) {
		final DifferentialEngines theEngines = startWith(
			"chains", state, Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY), SortValueAndChainFixture::setUpChains
		);
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(1)
		);
		final int reflectedPartition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(2)
		);

		// the joining owner writes no chain value: a chain keyed by the referenced entity would get the same element
		// from both owners, which a partition of either form cannot hold twice
		theEngines.step(
			"product 2 joins category 1",
			SingleOwnerPartitionFixture.updateProduct(
				2,
				it -> it.setReference(
					REFERENCE_CATEGORIES, 1,
					ref -> ref.setAttribute(SortValueAndChainFixture.ATTRIBUTE_ORDER, 20)
				)
			)
		);
		assertEquals(partition, assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(1), 1, 2));
		assertEquals(
			reflectedPartition, assertPromoted(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(2), 1, 2)
		);

		theEngines.step(
			"product 2 leaves category 1",
			SingleOwnerPartitionFixture.updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, 1))
		);
		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(1), 1, partition);
		assertRegisteredUnder(theEngines, ENTITY_CATEGORY, product(2), 2, reflectedPartition);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a demotion before the remaining owner's locale finalization fills the row it moves, kept by a reload")
	void shouldDemoteBeforeTheLocaleFinalizationOfTheRemainingOwner(CatalogState state) {
		final DifferentialEngines theEngines = startWith(
			"demotionFirst", state, Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY),
			VirtualPartitionPromotionLifecycleTest::setUpLocales
		);
		final int partition = assertPromoted(
			theEngines, ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), LEAVING_OWNER, REMAINING_OWNER
		);
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		theEngines.step(
			"the category drops the leaving owner, then gives the remaining one a German note",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, SHARED_CATEGORY, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, LEAVING_OWNER)),
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_NOTE_PRODUCTS, REMAINING_OWNER, -1)),
					new ReferenceAttributeMutation(
						new ReferenceKey(REFERENCE_NOTE_PRODUCTS, REMAINING_OWNER, -1),
						new UpsertAttributeMutation(ATTRIBUTE_NOTE, Locale.GERMAN, "Notiz")
					)
				)
			)
		);

		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(SHARED_CATEGORY), REMAINING_OWNER, partition);
		final List<TransitionStage> stages = stagesOfPartitionSince(
			theEngines, committedBefore, ENTITY_PRODUCT, partition
		);
		// the leaving owner's three values are removed from the object, the leaving owner's executor demotes the
		// partition, and only then the remaining owner's finalization writes its German compound into the row
		assertEquals(
			List.of(
				TransitionStage.MIRRORED, TransitionStage.MIRRORED, TransitionStage.MIRRORED,
				TransitionStage.DEMOTED, TransitionStage.MIRRORED
			),
			stages,
			"the demotion must have preceded the finalization of the remaining owner"
		);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());

		// the row the finalization moved is what the catalog persisted - the reload compares it with the twin again
		theEngines.restart();
		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(SHARED_CATEGORY), REMAINING_OWNER, partition);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a demotion after the remaining owner's locale finalization extracts what it moved, kept by a reload")
	void shouldDemoteAfterTheLocaleFinalizationOfTheRemainingOwner(CatalogState state) {
		final DifferentialEngines theEngines = startWith(
			"demotionLast", state, Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY),
			VirtualPartitionPromotionLifecycleTest::setUpLocales
		);
		final int partition = assertPromoted(
			theEngines, ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), LEAVING_OWNER, REMAINING_OWNER
		);
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		theEngines.step(
			"the category gives the remaining owner a German note, then drops the leaving owner",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, SHARED_CATEGORY, EntityExistence.MUST_EXIST,
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_NOTE_PRODUCTS, REMAINING_OWNER, -1)),
					new ReferenceAttributeMutation(
						new ReferenceKey(REFERENCE_NOTE_PRODUCTS, REMAINING_OWNER, -1),
						new UpsertAttributeMutation(ATTRIBUTE_NOTE, Locale.GERMAN, "Notiz")
					),
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, LEAVING_OWNER))
				)
			)
		);

		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(SHARED_CATEGORY), REMAINING_OWNER, partition);
		final List<TransitionStage> stages = stagesOfPartitionSince(
			theEngines, committedBefore, ENTITY_PRODUCT, partition
		);
		// the leaving owner's three values are removed from the object, the remaining owner's finalization writes its
		// German compound into the object, and only then the leaving owner's executor demotes the partition
		assertEquals(
			List.of(
				TransitionStage.MIRRORED, TransitionStage.MIRRORED, TransitionStage.MIRRORED,
				TransitionStage.MIRRORED, TransitionStage.DEMOTED
			),
			stages,
			"the finalization of the remaining owner must have preceded the demotion"
		);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());

		// the values the demotion extracted are what the catalog persisted - the reload compares them with the twin
		theEngines.restart();
		assertRegisteredUnder(theEngines, ENTITY_PRODUCT, category(SHARED_CATEGORY), REMAINING_OWNER, partition);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a promotion reading a first owner whose new locale is pending writes the locales it had before")
	void shouldPromoteFromAFirstOwnerWithAPendingLocale(CatalogState state) {
		final DifferentialEngines theEngines = startWith(
			"pendingLocale", state, Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY),
			VirtualPartitionPromotionLifecycleTest::setUpLocales
		);
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(OWN_CATEGORY)
		);

		// the first owner gains German through its new note first, so the promotion that follows reads it with the
		// locale pending, and its own finalization moves the promoted partition to German afterwards
		theEngines.step(
			"the category gives its only owner a German note, then lists another product",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, OWN_CATEGORY, EntityExistence.MUST_EXIST,
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_NOTE_PRODUCTS, LEAVING_OWNER, -1)),
					new ReferenceAttributeMutation(
						new ReferenceKey(REFERENCE_NOTE_PRODUCTS, LEAVING_OWNER, -1),
						new UpsertAttributeMutation(ATTRIBUTE_NOTE, Locale.GERMAN, "Notiz")
					),
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, REMAINING_OWNER, -1))
				)
			)
		);

		assertEquals(
			partition,
			assertPromoted(
				theEngines, ENTITY_PRODUCT, Scope.LIVE, category(OWN_CATEGORY), LEAVING_OWNER, REMAINING_OWNER
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a promotion reading a first owner the root mutation changes only afterwards is moved by its finalization")
	void shouldPromoteFromAFirstOwnerTheRootMutationChangesAfterwards(CatalogState state) {
		final DifferentialEngines theEngines = startWith(
			"localeAfterwards", state, Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY),
			VirtualPartitionPromotionLifecycleTest::setUpLocales
		);
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(OWN_CATEGORY)
		);

		theEngines.step(
			"the category lists another product, then gives its first owner a German note",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, OWN_CATEGORY, EntityExistence.MUST_EXIST,
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, REMAINING_OWNER, -1)),
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_NOTE_PRODUCTS, LEAVING_OWNER, -1)),
					new ReferenceAttributeMutation(
						new ReferenceKey(REFERENCE_NOTE_PRODUCTS, LEAVING_OWNER, -1),
						new UpsertAttributeMutation(ATTRIBUTE_NOTE, Locale.GERMAN, "Notiz")
					)
				)
			)
		);

		assertEquals(
			partition,
			assertPromoted(
				theEngines, ENTITY_PRODUCT, Scope.LIVE, category(OWN_CATEGORY), LEAVING_OWNER, REMAINING_OWNER
			)
		);
	}

	@Test
	@DisplayName("promotions and demotions are rebuilt by the replay of the write-ahead log and survive a restart")
	void shouldReplayPromotionsAndDemotionsFromTheWriteAheadLog() {
		final DifferentialEngines theEngines = DifferentialEngines.writtenVirtually(
			this, "replay", ENTITY_TYPES,
			TransactionOptions.builder().checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS).build()
		);
		this.engines = theEngines;
		watchPartitions(theEngines);
		theEngines.prepare(CatalogState.ALIVE, SingleOwnerPartitionFixture::setUpLocalizedSingleOwners);
		// going live is deferred like any other round; the clean close publishes it
		theEngines.restart();

		theEngines.step("product 1 joins category 2", joinCategory(1, 2));
		theEngines.step("product 3 joins category 4", joinCategory(3, 4));
		theEngines.step(
			"the first owner of category 4 leaves it",
			updateProduct(4, it -> it.removeReference(REFERENCE_CATEGORIES, 4))
		);
		theEngines.step(
			"a new product enters two free categories",
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true)
				.setReference(
					REFERENCE_CATEGORIES, SECOND_FREE_TARGET,
					whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, SECOND_FREE_TARGET)
				)
				.upsertVia(session)
		);

		// the recovery replays the tail of the write-ahead log, which mints the partitions the tail created anew
		final PartitionIdentityLedger ledger = theEngines.trackPartitionIdentity();
		ledger.declareReplayed(ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT));
		ledger.declareReplayed(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET));
		ledger.declareReplayed(ENTITY_PRODUCT, Scope.LIVE, category(SECOND_FREE_TARGET));
		ledger.declareReplayed(
			ENTITY_PRODUCT, Scope.LIVE,
			new RepresentativeReferenceKey(
				new ReferenceKey(SingleOwnerPartitionFixture.REFERENCE_GALLERY, FREE_TARGET),
				new Serializable[]{SingleOwnerPartitionFixture.ROLE_GALLERY}
			)
		);
		final DifferentialEngines.Reload recovered = theEngines.crashAndRecover();
		assertTrue(
			recovered.maintaining().loadedVersion() > recovered.maintaining().imageVersion(),
			"the recovery must have replayed the tail of the write-ahead log"
		);
		assertPromoted(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(2), 1, 2);
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4), 3);
		assertPromoted(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), FREE_TARGET, SECOND_FREE_TARGET);

		theEngines.step(
			"the recovered catalog demotes",
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, 2))
		);
		theEngines.restart();
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(2), 2);
	}

	/**
	 * Defines products in two locales with a category reference carrying a sortable order, a localized sortable label
	 * and a localized compound of both, and a note category reference carrying a localized note, both reflected on the
	 * category with their attributes inherited; then lets products {@link #LEAVING_OWNER} and {@link #REMAINING_OWNER}
	 * share category {@link #SHARED_CATEGORY} with English values only, and lets {@link #LEAVING_OWNER} alone
	 * reference category {@link #OWN_CATEGORY}.
	 *
	 * @param session the session to write in
	 */
	private static void setUpLocales(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY).withLocale(Locale.ENGLISH, Locale.GERMAN).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
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
				REFERENCE_NOTE_CATEGORY, ENTITY_CATEGORY, Cardinality.ZERO_OR_ONE,
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
					.withAttributesInherited()
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.withReflectedReferenceToEntity(
				REFERENCE_NOTE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_NOTE_CATEGORY,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.withAttributesInherited()
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
		session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, SHARED_CATEGORY));
		session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, OWN_CATEGORY));
		for (final int owner : new int[]{LEAVING_OWNER, REMAINING_OWNER}) {
			final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, owner)
				.setReference(
					REFERENCE_CATEGORIES, SHARED_CATEGORY,
					ref -> ref.setAttribute(ATTRIBUTE_ORDER, owner)
						.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "label " + owner)
				);
			if (owner == LEAVING_OWNER) {
				product.setReference(
					REFERENCE_CATEGORIES, OWN_CATEGORY,
					ref -> ref.setAttribute(ATTRIBUTE_ORDER, OWN_CATEGORY)
						.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "own label")
				);
			}
			session.upsertEntity(product);
		}
	}

	/**
	 * Creates a step letting a product of the singleton fixture join a category, with an order, an English label and a
	 * chain position after the category's first owner of its own.
	 *
	 * @param product  the product joining
	 * @param category the category it joins
	 * @return the step
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> joinCategory(int product, int category) {
		return updateProduct(
			product,
			it -> it.setReference(
				REFERENCE_CATEGORIES, category,
				ref -> ref.setAttribute(ATTRIBUTE_ORDER, 100 + category)
					.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Joined " + category)
					.setAttribute(ATTRIBUTE_POSITION, new Predecessor(category))
			)
		);
	}

	/**
	 * Asserts that a partition is promoted in the catalog writing virtually: its index object holds exactly the given
	 * owners and the registry holds no row of it - and that the twin's index object of the partition has the same
	 * primary key and the same owners.
	 *
	 * @param engines    the engines
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @param owners     the expected owners, in ascending order
	 * @return the primary key of the partition
	 */
	private static int assertPromoted(
		@Nonnull DifferentialEngines engines,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key,
		int... owners
	) {
		final EntityIndex promoted = partitionObjectOf(engines.getMaintaining(), entityType, scope, key);
		assertNotNull(promoted, "the catalog writing virtually must hold the index object of " + key + " in " + scope);
		assertEquals(
			Arrays.stream(owners).boxed().toList(),
			Arrays.stream(promoted.getAllPrimaryKeys().getArray()).boxed().toList(),
			"the owners of the promoted partition of " + key + " in " + scope
		);
		assertEquals(
			-1, registeredPartitionOf(engines.getMaintaining(), entityType, scope, key),
			"the promoted partition of " + key + " in " + scope + " must have no row"
		);
		final EntityIndex twin = partitionObjectOf(engines.getReference(), entityType, scope, key);
		assertNotNull(twin, "the twin must hold the index object of " + key + " in " + scope);
		assertEquals(
			twin.getPrimaryKey(), promoted.getPrimaryKey(),
			"the promoted partition of " + key + " in " + scope + " must carry the primary key of the twin's"
		);
		assertEquals(
			Arrays.stream(owners).boxed().toList(),
			Arrays.stream(twin.getAllPrimaryKeys().getArray()).boxed().toList(),
			"the owners of the twin's partition of " + key + " in " + scope
		);
		return promoted.getPrimaryKey();
	}

	/**
	 * Asserts that a partition of the live scope is demoted to - or kept as - the row of its only owner under the given
	 * primary key, with no index object (see {@link DifferentialEngines#assertRegistered}).
	 *
	 * @param engines    the engines
	 * @param entityType the entity type owning the reference
	 * @param key        the key of the partition
	 * @param owner      the expected owner
	 * @param primaryKey the primary key the partition must have kept
	 */
	private static void assertRegisteredUnder(
		@Nonnull DifferentialEngines engines,
		@Nonnull String entityType,
		@Nonnull RepresentativeReferenceKey key,
		int owner,
		int primaryKey
	) {
		assertRegistered(engines, entityType, Scope.LIVE, key, owner);
		assertEquals(
			primaryKey, registeredPartitionOf(engines.getMaintaining(), entityType, Scope.LIVE, key),
			"the partition of " + key + " must have kept its primary key"
		);
	}

	/**
	 * Returns the index object of a partition in the committed catalog of an engine.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @return the index object, or `null` when the collection holds none of the key
	 */
	@Nullable
	private static EntityIndex partitionObjectOf(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final EntityCollection collection = findCollectionOf(evita, entityType);
		return collection == null ?
			null :
			collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, key));
	}

	/**
	 * Asserts whether the reduced-index membership of the product's category reference in the live scope of the
	 * committed catalog of an engine covers a partition.
	 *
	 * @param evita               the engine
	 * @param partitionPrimaryKey the primary key of the partition
	 * @param expected            TRUE when the membership must cover the partition
	 * @param label               names the partition in the failure message
	 */
	private static void assertCovered(
		@Nonnull Evita evita,
		int partitionPrimaryKey,
		boolean expected,
		@Nonnull String label
	) {
		final EntityCollection collection = findCollectionOf(evita, ENTITY_PRODUCT);
		assertNotNull(collection, label + ": the product collection must exist");
		final EntityIndex globalIndex =
			collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE));
		final ReducedIndexMembership membership = assertInstanceOf(GlobalEntityIndex.class, globalIndex)
			.getReducedIndexMembership(REFERENCE_CATEGORIES);
		assertNotNull(membership, label + ": the category reference must have its membership lookup");
		assertEquals(
			expected, membership.getCoveredIndexPrimaryKeys().contains(partitionPrimaryKey),
			label + " " + partitionPrimaryKey + (expected ? " must" : " must not") + " be covered by the membership, " +
				"covered: " + membership.getCoveredIndexPrimaryKeys()
		);
	}

	/**
	 * Returns the stages of the transitions of one partition the catalog writing virtually committed since the given
	 * count of committed transitions, in the order they passed.
	 *
	 * @param engines             the engines
	 * @param from                the count of committed transitions to start after
	 * @param entityType          the entity type owning the reference of the partition
	 * @param partitionPrimaryKey the primary key of the partition
	 * @return the stages
	 */
	@Nonnull
	private static List<TransitionStage> stagesOfPartitionSince(
		@Nonnull DifferentialEngines engines,
		int from,
		@Nonnull String entityType,
		int partitionPrimaryKey
	) {
		final List<Transition> committed = engines.getMaintainingHistory().getCommitted();
		return committed.subList(from, committed.size()).stream()
			.filter(it -> it.entityType().equals(entityType) && it.partitionPrimaryKey() == partitionPrimaryKey)
			.map(Transition::stage)
			.toList();
	}

	/**
	 * Starts the engines comparing the catalog writing virtually with its twin over the singleton fixture (see
	 * {@link SingleOwnerPartitionFixture#setUpLocalizedSingleOwners}).
	 *
	 * @param label a label naming the engines' directories
	 * @param state the catalog state the engines should end in
	 * @return the engines
	 */
	@Nonnull
	private DifferentialEngines start(@Nonnull String label, @Nonnull CatalogState state) {
		return startWith(label, state, ENTITY_TYPES, SingleOwnerPartitionFixture::setUpLocalizedSingleOwners);
	}

	/**
	 * Starts the engines comparing the catalog writing virtually with its twin, with the check refusing index objects
	 * of partitions with fewer than two owners registered, and prepares the fixture in the given state.
	 *
	 * @param label       a label naming the engines' directories
	 * @param state       the catalog state the engines should end in
	 * @param entityTypes the entity types of the fixture
	 * @param setup       the schema and the initial data
	 * @return the engines
	 */
	@Nonnull
	private DifferentialEngines startWith(
		@Nonnull String label,
		@Nonnull CatalogState state,
		@Nonnull Set<String> entityTypes,
		@Nonnull Consumer<EvitaSessionContract> setup
	) {
		final DifferentialEngines theEngines = DifferentialEngines.writtenVirtually(this, label, entityTypes, null);
		this.engines = theEngines;
		watchPartitions(theEngines);
		theEngines.prepare(state, setup);
		return theEngines;
	}

	/**
	 * Adds the checks every history of this class runs on the catalog writing virtually at every checkpoint: no index
	 * object of a partition with fewer than two owners, no partition the reduced-index membership knows that is
	 * neither a row nor an index object, and the primary key of every partition held across the checkpoints by the
	 * body oracle.
	 *
	 * @param engines the engines, not prepared yet
	 */
	private static void watchPartitions(@Nonnull DifferentialEngines engines) {
		engines.addMaintainingEngineCheck(VirtualPartitionInvariants::assertNoPartitionObjectBelowTwoOwners);
		engines.addMaintainingEngineCheck(VirtualPartitionInvariants::assertMembershipKnowsOnlyExistingPartitions);
		engines.trackPartitionIdentity();
	}

}
