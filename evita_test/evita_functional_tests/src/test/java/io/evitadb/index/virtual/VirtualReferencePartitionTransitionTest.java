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
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.virtual.TransitionHistory.Transition;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.index.virtual.DifferentialEngines.CATALOG;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the transitions of the owner count of reference partitions through both engines of
 * {@link DifferentialEngines} - in a catalog warming up and in a live one - and checks that the registry of
 * single-owner partitions follows them: a partition gaining its only owner (0 -> 1) gets its row, gaining a second
 * one (1 -> 2) or losing the last one (1 -> 0) loses it, and one left with one owner by losing one of two (2 -> 1) is
 * demoted - it gets its row back at the end of the mutation that left it so, unless another owner joined it
 * meanwhile.
 *
 * The harness checks the registry against the partitions at every checkpoint - inside the session after every step,
 * after a caught failure, after the session closes and after a restart - and compares the two engines everywhere
 * else. On top of that, every test asserts the rows and the recorded transitions its history must have produced, so
 * no test passes by not exercising what it names.
 *
 * Failures are injected right after a transition in both engines at once (see
 * {@link DifferentialEngines#failingStepAfter}), so the failed mutation must leave both exactly where they were.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition transitions")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class VirtualReferencePartitionTransitionTest implements EvitaTestSupport {
	/**
	 * Categories no product of the fixture references.
	 */
	private static final int FREE_CATEGORY = 11;
	private static final int SECOND_FREE_CATEGORY = 12;
	private static final int THIRD_FREE_CATEGORY = 13;
	private static final int FOURTH_FREE_CATEGORY = 14;
	/**
	 * A product the fixture does not create.
	 */
	private static final int NEW_PRODUCT = 9;

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
	@DisplayName("rows follow a partition from one owner to two, back to one and to none, across a restart")
	void shouldFollowTheOwnerCountOfAPartition(CatalogState state) {
		final DifferentialEngines theEngines = start("ownerCount", state, SingleOwnerPartitionFixture::setUp);

		// the fixture itself: single-owner partitions have their rows, shared ones have none
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(4, ROLE_DETAIL), 4);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(4, ROLE_GALLERY), 4);
		assertNoRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY));
		assertNoRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(SHARED_MEDIA, ROLE_THUMBNAIL));
		assertTrue(
			countOf(theEngines.getMaintainingHistory().getCommitted(), TransitionStage.ENTERED) > 0,
			"the fixture must have registered its single-owner partitions through the probed transitions"
		);
		assertTrue(
			registryOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, REFERENCE_BRAND) == null ||
				registryOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, REFERENCE_BRAND).isEmpty(),
			"a reference indexed for partitioning has no rows"
		);

		theEngines.step(
			"a product joins a free category",
			updateProduct(1, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY))
		);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY), 1);
		final int partition =
			partitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY));

		theEngines.step(
			"a second product joins it", updateProduct(2, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY))
		);
		assertNoRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY));

		theEngines.step(
			"the second product leaves it",
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_CATEGORY))
		);
		// one owner again - the partition is demoted and has its row back, naming the remaining owner
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY), 1);
		assertTrue(
			theEngines.getMaintainingHistory().getCommitted().contains(
				new Transition(TransitionStage.DEMOTED, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, partition)
			),
			"the partition left with one owner by the second leaving must have been demoted"
		);

		theEngines.restart();
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY), 1);

		theEngines.step(
			"the first product leaves it too",
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_CATEGORY))
		);
		assertTrue(
			theEngines.getMaintainingHistory().getCommitted().contains(
				new Transition(TransitionStage.VACATED, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, partition)
			),
			"the partition losing its last owner must have been vacated"
		);
		assertEquals(
			theEngines.getReferenceHistory().getCommitted(),
			theEngines.getMaintainingHistory().getCommitted(),
			"both engines must pass the same transitions, the one maintaining nothing included"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("removing and re-inserting the reference of a single owner in one mutation changes no row")
	void shouldEndAChurnOfASingleOwnerAsIfNothingChanged(CatalogState state) {
		final DifferentialEngines theEngines = start("singleOwnerChurn", state, SingleOwnerPartitionFixture::setUp);
		final int partition = partitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(4));
		final String before = describeRows(theEngines.getMaintaining(), ENTITY_PRODUCT, REFERENCE_CATEGORIES);
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		theEngines.step(
			"the only owner removes and re-inserts its reference in one mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(existingReferenceKey(session, 4, 4)),
					new InsertReferenceMutation(newReferenceKey(4)),
					new ReferenceAttributeMutation(newReferenceKey(4), new UpsertAttributeMutation(ATTRIBUTE_ORDER, 4))
				)
			)
		);

		assertEquals(
			partition, partitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(4)),
			"the partition keeps its primary key"
		);
		assertEquals(
			before, describeRows(theEngines.getMaintaining(), ENTITY_PRODUCT, REFERENCE_CATEGORIES),
			"the registry must end as if nothing had changed"
		);
		final List<Transition> passed = theEngines.getMaintainingHistory().getCommitted()
			.subList(committedBefore, theEngines.getMaintainingHistory().getCommitted().size());
		assertTrue(
			passed.contains(
				new Transition(TransitionStage.VACATED, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, partition)
			) && passed.contains(
				new Transition(TransitionStage.ENTERED, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, partition)
			),
			"the churn must have vacated the partition and entered it again: " + passed
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a second owner joining and the first leaving in one mutation demotes the partition once")
	void shouldDemoteAOneTwoOneChurnOnce(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"oneTwoOneChurn", state, SingleOwnerPartitionFixture::setUpWithReflectedProducts
		);
		// the reflected partition of product 4 on the category side is owned by category 4 alone
		assertRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(4), 4);
		final int partition = partitionOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(4));
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		// one entity cannot take a partition from one owner to two and back on its own: it is a single owner of any
		// partition it is in, a reference it creates is indexed only under its final representative attributes and
		// cannot be removed by the mutation creating it, and moving an existing reference twice indexes only the
		// move from where it was to where it ends. The reflected partitions of the product, owned by categories, can
		// - one product mutation joins a second category to it and takes the first one away, both through the
		// reflected mutations it spawns within the same root mutation
		theEngines.step(
			"a second owner joins and the first leaves in one root mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new InsertReferenceMutation(newReferenceKey(SECOND_FREE_CATEGORY)),
					new RemoveReferenceMutation(existingReferenceKey(session, 4, 4))
				)
			)
		);

		final List<TransitionStage> passed = theEngines.getMaintainingHistory().getCommitted()
			.subList(committedBefore, theEngines.getMaintainingHistory().getCommitted().size())
			.stream()
			.filter(it -> it.entityType().equals(ENTITY_CATEGORY) && it.partitionPrimaryKey() == partition)
			.map(Transition::stage)
			// the order the leaving owner had is removed from the partition, which only the mirror is told about
			.filter(it -> it != TransitionStage.MIRRORED)
			.toList();
		assertEquals(
			List.of(TransitionStage.SHARED, TransitionStage.DEMOTED), passed,
			"the second owner must have joined the partition before the first left it, and the partition must have " +
				"been demoted once, at the end"
		);
		assertEquals(
			List.of(SECOND_FREE_CATEGORY),
			ownersOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(4)),
			"the partition is back to one owner - the second one"
		);
		// the row names the owner that joined, filled with what the partition holds for it
		assertRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(4), SECOND_FREE_CATEGORY);
		theEngines.restart();
		assertRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(4), SECOND_FREE_CATEGORY);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner leaving and another joining in one mutation leaves the shared partition undemoted")
	void shouldNotDemoteATwoOneTwoChurn(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"twoOneTwoChurn", state, SingleOwnerPartitionFixture::setUpWithReflectedProducts
		);
		// the reflected partition of product 1 on the category side is owned by category 1 and the shared one
		assertNoRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(1));
		final int partition = partitionOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(1));
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		theEngines.step(
			"the shared category leaves the partition and a free one joins it in one root mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 1, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(existingReferenceKey(session, 1, SHARED_CATEGORY)),
					new InsertReferenceMutation(newReferenceKey(SECOND_FREE_CATEGORY))
				)
			)
		);

		final List<TransitionStage> passed = theEngines.getMaintainingHistory().getCommitted()
			.subList(committedBefore, theEngines.getMaintainingHistory().getCommitted().size())
			.stream()
			.filter(it -> it.entityType().equals(ENTITY_CATEGORY) && it.partitionPrimaryKey() == partition)
			.map(Transition::stage)
			.filter(it -> it != TransitionStage.MIRRORED)
			.toList();
		assertEquals(
			List.of(TransitionStage.SHARED), passed,
			"the partition must have been shared again by the joining owner and never demoted: " + passed
		);
		assertEquals(
			List.of(1, SECOND_FREE_CATEGORY),
			ownersOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(1)),
			"the partition has two owners again"
		);
		assertNoRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(1));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("reflected partitions follow the mutations a product spawns, two new categories in one included")
	void shouldMaintainReflectedPartitions(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"reflected", state, SingleOwnerPartitionFixture::setUpWithReflectedProducts
		);
		// product 4 is referenced by category 4 alone, product 1 by category 1 and the shared one
		assertRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(4), 4);
		assertNoRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(1));
		final int sharedProductPartition =
			partitionOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(1));

		theEngines.transaction(
			"a genuine two-to-one next to a reflected one-to-zero-to-one and two new categories in one product",
			List.of(
				// category 100 stops referencing product 1: its reflected partition goes from two owners to one
				updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY)),
				// a new product in category 9: its reflected partition gets its only owner
				session -> session.upsertEntity(
					newProduct(session, NEW_PRODUCT)
				),
				// a new product in two categories: its reflected partition goes from none to one to two owners in one
				// root mutation, through two reflected mutations
				session -> session.upsertEntity(
					newProduct(session, NEW_PRODUCT + 1).setReference(REFERENCE_CATEGORIES, FREE_CATEGORY)
				)
			)
		);

		// category 1 is the only owner left, and the partition is demoted
		assertRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(1), 1);
		assertTrue(
			theEngines.getMaintainingHistory().getCommitted().contains(
				new Transition(
					TransitionStage.DEMOTED, ENTITY_CATEGORY, REFERENCE_PRODUCTS, Scope.LIVE, sharedProductPartition
				)
			)
		);
		assertRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), NEW_PRODUCT);
		assertNoRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT + 1));
		final int twoCategoriesPartition =
			partitionOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT + 1));
		final List<TransitionStage> ofTwoCategories = new ArrayList<>(2);
		for (final Transition transition : theEngines.getMaintainingHistory().getCommitted()) {
			// the orders the categories have are written into the partition, which only the mirror is told about
			if (transition.entityType().equals(ENTITY_CATEGORY) &&
				transition.partitionPrimaryKey() == twoCategoriesPartition &&
				transition.stage() != TransitionStage.MIRRORED) {
				ofTwoCategories.add(transition.stage());
			}
		}
		assertEquals(List.of(TransitionStage.ENTERED, TransitionStage.SHARED), ofTwoCategories);
		// the product side registered both new categories
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY), NEW_PRODUCT + 1);

		theEngines.restart();
		assertRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), NEW_PRODUCT);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a relocation moves the row, and moving back moves it back")
	void shouldMoveTheRowWithARelocation(CatalogState state) {
		final DifferentialEngines theEngines = start("relocation", state, SingleOwnerPartitionFixture::setUp);

		theEngines.step(
			"the only owner moves to a free category",
			updateProduct(
				4,
				it -> it.removeReference(REFERENCE_CATEGORIES, 4)
					.setReference(REFERENCE_CATEGORIES, SECOND_FREE_CATEGORY)
			)
		);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(SECOND_FREE_CATEGORY), 4);
		assertNoPartitionOrRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4));

		theEngines.step(
			"and back",
			updateProduct(
				4,
				it -> it.removeReference(REFERENCE_CATEGORIES, SECOND_FREE_CATEGORY)
					.setReference(REFERENCE_CATEGORIES, 4)
			)
		);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		assertNoPartitionOrRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(SECOND_FREE_CATEGORY));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner replaced by another while the partition is empty hands the row over")
	void shouldHandTheRowOverToAReplacement(CatalogState state) {
		final DifferentialEngines theEngines = start("replacement", state, SingleOwnerPartitionFixture::setUp);

		theEngines.transaction(
			"the only owner leaves and another product joins in one session",
			List.of(
				updateProduct(4, it -> it.removeReference(REFERENCE_CATEGORIES, 4)),
				updateProduct(5, it -> it.setReference(REFERENCE_CATEGORIES, 4))
			)
		);

		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4), 5);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a scope move vacates the partitions of one scope and enters those of the other")
	void shouldFollowAScopeMove(CatalogState state) {
		final DifferentialEngines theEngines = start("scopeMove", state, SingleOwnerPartitionFixture::setUp);

		theEngines.step("a product moves to the archive", session -> session.archiveEntity(ENTITY_PRODUCT, 4));
		assertNoPartitionOrRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4));
		assertRow(theEngines, ENTITY_PRODUCT, Scope.ARCHIVED, category(4), 4);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.ARCHIVED, media(4, ROLE_DETAIL), 4);

		theEngines.step("it comes back", session -> session.restoreEntity(ENTITY_PRODUCT, 4));
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(4, ROLE_DETAIL), 4);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("removing the only owner vacates all its partitions")
	void shouldVacateThePartitionsOfARemovedEntity(CatalogState state) {
		final DifferentialEngines theEngines = start("entityRemoval", state, SingleOwnerPartitionFixture::setUp);
		final int partition = partitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(4));

		theEngines.step("a product is removed", session -> session.deleteEntity(ENTITY_PRODUCT, 4));

		assertNoPartitionOrRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4));
		assertNoPartitionOrRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(4, ROLE_DETAIL));
		assertNoPartitionOrRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(4, ROLE_GALLERY));
		assertTrue(
			theEngines.getMaintainingHistory().getCommitted().contains(
				new Transition(TransitionStage.VACATED, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, partition)
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a mutation failing on its own after entering a partition leaves no row behind")
	void shouldRollBackARowOfAMutationFailingOnItsOwn(CatalogState state) {
		final DifferentialEngines theEngines = start("naturalFailure", state, SingleOwnerPartitionFixture::setUp);

		theEngines.failingStep(
			"a product joins a free category and then takes another product's unique code",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new InsertReferenceMutation(newReferenceKey(FREE_CATEGORY)),
					new UpsertAttributeMutation(ATTRIBUTE_CODE, "P1")
				)
			),
			UniqueValueViolationException.class
		);

		assertNoPartitionOrRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY));
		assertTrue(
			theEngines.getMaintainingHistory().getRolledBack().stream().anyMatch(
				it -> it.stage() == TransitionStage.ENTERED && it.referenceName().equals(REFERENCE_CATEGORIES)
			),
			"the failure must have struck after the partition was entered: " +
				theEngines.getMaintainingHistory().getRolledBack()
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a failure injected right after a transition rolls the registry back with everything else")
	void shouldRollBackTheRegistryAfterAnInjectedFailure(CatalogState state) {
		final DifferentialEngines theEngines = start("injectedFailure", state, SingleOwnerPartitionFixture::setUp);
		final String before = describeRows(theEngines.getMaintaining(), ENTITY_PRODUCT, REFERENCE_CATEGORIES);

		theEngines.failingStepAfter(
			"a product joins a free category, failing right after the row is registered",
			TransitionStage.ENTERED, ENTITY_PRODUCT, 0,
			updateProduct(4, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY))
		);
		theEngines.failingStepAfter(
			"a product joins two free categories, failing after the second row, the first partition populated",
			TransitionStage.ENTERED, ENTITY_PRODUCT, 1,
			updateProduct(
				4,
				it -> it.setReference(REFERENCE_CATEGORIES, THIRD_FREE_CATEGORY)
					.setReference(REFERENCE_CATEGORIES, FOURTH_FREE_CATEGORY)
			)
		);
		theEngines.failingStepAfter(
			"a second owner joins a single-owner partition, failing right after the row is dropped",
			TransitionStage.SHARED, ENTITY_PRODUCT, 0,
			updateProduct(5, it -> it.setReference(REFERENCE_CATEGORIES, 4))
		);
		theEngines.failingStepAfter(
			"the only owner leaves, failing right after the row is dropped",
			TransitionStage.VACATED, ENTITY_PRODUCT, 0,
			updateProduct(4, it -> it.removeReference(REFERENCE_CATEGORIES, 4))
		);

		assertEquals(
			before, describeRows(theEngines.getMaintaining(), ENTITY_PRODUCT, REFERENCE_CATEGORIES),
			"every failed mutation must have left the registry as it was"
		);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		assertEquals(
			List.of(
				TransitionStage.ENTERED,
				TransitionStage.ENTERED, TransitionStage.ENTERED,
				TransitionStage.SHARED,
				TransitionStage.VACATED
			),
			theEngines.getMaintainingHistory().getRolledBack().stream()
				.filter(it -> it.referenceName().equals(REFERENCE_CATEGORIES)).map(Transition::stage)
				// the order the leaving owner had is removed from the partition, which only the mirror is told about
				.filter(it -> it != TransitionStage.MIRRORED)
				.toList(),
			"every injected failure must have struck after its transition, the second after two entries"
		);

		theEngines.step(
			"the same transitions succeed afterwards",
			updateProduct(5, it -> it.setReference(REFERENCE_CATEGORIES, 4))
		);
		assertNoRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(4));
		theEngines.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a failure injected right after a demotion rolls the demoted row back with everything else")
	void shouldRollBackADemotionAfterAnInjectedFailure(CatalogState state) {
		final DifferentialEngines theEngines = start("injectedDemotion", state, SingleOwnerPartitionFixture::setUp);
		theEngines.step(
			"two products join a free category",
			session -> {
				updateProduct(1, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY)).accept(session);
				updateProduct(2, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY)).accept(session);
			}
		);
		assertNoRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY));
		final int partition =
			partitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY));
		final String before = describeRows(theEngines.getMaintaining(), ENTITY_PRODUCT, REFERENCE_CATEGORIES);

		theEngines.failingStepAfter(
			"the second product leaves it, failing right after the partition is demoted",
			TransitionStage.DEMOTED, ENTITY_PRODUCT, 0,
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_CATEGORY))
		);
		assertEquals(
			before, describeRows(theEngines.getMaintaining(), ENTITY_PRODUCT, REFERENCE_CATEGORIES),
			"the failed mutation must have left the registry as it was"
		);
		assertNoRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY));
		assertTrue(
			theEngines.getMaintainingHistory().getRolledBack().contains(
				new Transition(TransitionStage.DEMOTED, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, partition)
			),
			"the failure must have struck after the demotion: " + theEngines.getMaintainingHistory().getRolledBack()
		);

		theEngines.step(
			"the same demotion succeeds afterwards",
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_CATEGORY))
		);
		assertRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_CATEGORY), 1);
		theEngines.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a failure injected after a reflected mutation entered a partition rolls the whole root back")
	void shouldRollBackAReflectedRowAfterAnInjectedFailure(CatalogState state) {
		final DifferentialEngines theEngines = start(
			"injectedReflectedFailure", state, SingleOwnerPartitionFixture::setUpWithReflectedProducts
		);

		theEngines.failingStepAfter(
			"a new product's category enters its reflected partition, failing right after",
			TransitionStage.ENTERED, ENTITY_CATEGORY, 0,
			session -> session.upsertEntity(newProduct(session, NEW_PRODUCT))
		);

		assertNoPartitionOrRow(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT));
		assertNoPartitionOrRow(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(NEW_PRODUCT));
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
	 * Returns the key, with its internal primary key, of an existing category reference of a product.
	 *
	 * @param session  the session to read in
	 * @param product  the product
	 * @param category the category
	 * @return the reference key
	 */
	@Nonnull
	private static ReferenceKey existingReferenceKey(@Nonnull EvitaSessionContract session, int product, int category) {
		return session.getEntity(ENTITY_PRODUCT, product, referenceContentAll())
			.orElseThrow()
			.getReference(REFERENCE_CATEGORIES, category)
			.orElseThrow()
			.getReferenceKey();
	}

	/**
	 * Returns the key of a category reference created by the mutation that names it - its internal primary key is a
	 * placeholder the engine replaces by a new one.
	 *
	 * @param category the category
	 * @return the reference key
	 */
	@Nonnull
	private static ReferenceKey newReferenceKey(int category) {
		return new ReferenceKey(REFERENCE_CATEGORIES, category, -1);
	}

	/**
	 * Creates the key of a category partition.
	 *
	 * @param category the category
	 * @return the key
	 */
	@Nonnull
	static RepresentativeReferenceKey category(int category) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, category));
	}

	/**
	 * Creates the key of a gallery partition.
	 *
	 * @param media the media
	 * @param role  the role the media is shown in
	 * @return the key
	 */
	@Nonnull
	static RepresentativeReferenceKey media(int media, @Nonnull String role) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_GALLERY, media), new Serializable[]{role});
	}

	/**
	 * Creates the key of a reflected product partition of the category.
	 *
	 * @param product the product
	 * @return the key
	 */
	@Nonnull
	static RepresentativeReferenceKey product(int product) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_PRODUCTS, product));
	}

	/**
	 * Returns a collection of the committed catalog of an engine.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type
	 * @return the collection
	 */
	@Nonnull
	static EntityCollection collectionOf(@Nonnull Evita evita, @Nonnull String entityType) {
		return (EntityCollection) evita.getCatalogInstance(CATALOG)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElseThrow();
	}

	/**
	 * Returns the primary key of a partition in the committed catalog of an engine.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @return the partition primary key, or `-1` when there is no such partition
	 */
	static int partitionOf(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final EntityIndex partition = collectionOf(evita, entityType).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, key)
		);
		return partition == null ? -1 : partition.getPrimaryKey();
	}

	/**
	 * Returns the owners of a partition in the committed catalog of an engine.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @return the owner primary keys, ascending
	 */
	@Nonnull
	static List<Integer> ownersOf(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final EntityIndex partition = collectionOf(evita, entityType).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, key)
		);
		assertNotNull(partition, "No partition for " + key + " in " + scope);
		return Arrays.stream(partition.getAllPrimaryKeys().getArray()).boxed().toList();
	}

	/**
	 * Returns the registry of a reference in the committed catalog of an engine.
	 *
	 * @param evita         the engine
	 * @param entityType    the entity type owning the reference
	 * @param scope         the scope
	 * @param referenceName the reference
	 * @return the registry, `null` when the reference has no referenced-type entity index in the scope
	 */
	@Nullable
	static VirtualReferencePartitionIndex registryOf(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		final EntityIndex typeIndex = collectionOf(evita, entityType).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName)
		);
		return typeIndex == null ?
			null : assertInstanceOf(ReferencedTypeEntityIndex.class, typeIndex).getVirtualReferencePartitionIndex();
	}

	/**
	 * Asserts the maintaining engine's committed registry holds the row of the partition, naming the owner and the
	 * referenced entity of the key, and that the reference engine's registry holds nothing.
	 *
	 * @param engines    the engines
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @param owner      the expected owner
	 */
	static void assertRow(
		@Nonnull DifferentialEngines engines,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key,
		int owner
	) {
		final int partition = partitionOf(engines.getMaintaining(), entityType, scope, key);
		assertNotEquals(-1, partition, "No partition for " + key + " in " + scope);
		final VirtualReferencePartitionIndex registry =
			registryOf(engines.getMaintaining(), entityType, scope, key.referenceName());
		assertNotNull(registry);
		final OptionalLong row = registry.getPartitionOwner(partition);
		assertTrue(row.isPresent(), "The partition of " + key + " in " + scope + " must have its row");
		assertEquals(owner, NumberUtils.unpackHigh(row.getAsLong()), "owner of " + key);
		assertEquals(key.primaryKey(), NumberUtils.unpackLow(row.getAsLong()), "referenced entity of " + key);
		assertReferenceEngineRegistryEmpty(engines, entityType, scope, key.referenceName());
	}

	/**
	 * Asserts the partition exists and the maintaining engine's committed registry holds no row for it.
	 *
	 * @param engines    the engines
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 */
	static void assertNoRow(
		@Nonnull DifferentialEngines engines,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final int partition = partitionOf(engines.getMaintaining(), entityType, scope, key);
		assertNotEquals(-1, partition, "No partition for " + key + " in " + scope);
		final VirtualReferencePartitionIndex registry =
			registryOf(engines.getMaintaining(), entityType, scope, key.referenceName());
		assertNotNull(registry);
		assertFalse(
			registry.getPartitionOwner(partition).isPresent(),
			"The partition of " + key + " in " + scope + " must have no row"
		);
	}

	/**
	 * Asserts the partition does not exist in the maintaining engine, or exists without owner and row.
	 *
	 * @param engines    the engines
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 */
	static void assertNoPartitionOrRow(
		@Nonnull DifferentialEngines engines,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final int partition = partitionOf(engines.getMaintaining(), entityType, scope, key);
		if (partition != -1) {
			assertEquals(List.of(), ownersOf(engines.getMaintaining(), entityType, scope, key));
			assertNoRow(engines, entityType, scope, key);
		}
	}

	/**
	 * Asserts the reference engine, which maintains nothing, has an empty registry for the reference.
	 *
	 * @param engines       the engines
	 * @param entityType    the entity type owning the reference
	 * @param scope         the scope
	 * @param referenceName the reference
	 */
	private static void assertReferenceEngineRegistryEmpty(
		@Nonnull DifferentialEngines engines,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		final VirtualReferencePartitionIndex registry =
			registryOf(engines.getReference(), entityType, scope, referenceName);
		assertTrue(registry == null || registry.isEmpty(), "the reference engine must write no registry");
	}

	/**
	 * Describes the owner rows and tuples of a reference in both scopes of the committed catalog of an engine.
	 *
	 * @param evita         the engine
	 * @param entityType    the entity type owning the reference
	 * @param referenceName the reference
	 * @return the description
	 */
	@Nonnull
	static String describeRows(@Nonnull Evita evita, @Nonnull String entityType, @Nonnull String referenceName) {
		final StringBuilder description = new StringBuilder(256);
		for (final Scope scope : Scope.values()) {
			final VirtualReferencePartitionIndex registry = registryOf(evita, entityType, scope, referenceName);
			description.append(scope).append(':');
			if (registry != null) {
				registry.forEachEntry(
					VirtualReferencePartitionSlot.PARTITION_OWNER,
					(partition, payload) -> description.append(' ').append(partition).append("->")
						.append(NumberUtils.unpackHigh(payload)).append('/').append(NumberUtils.unpackLow(payload))
				);
				registry.forEachEntry(
					VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE,
					(partition, payload) -> description.append(' ').append(partition).append("#")
						.append(Arrays.toString(registry.getTuple((int) payload)))
				);
				description.append(" entries=").append(registry.getEntryCount());
			}
			description.append('\n');
		}
		return description.toString();
	}

	/**
	 * Counts the transitions of the given kind.
	 *
	 * @param transitions the transitions
	 * @param stage       the kind
	 * @return how many of them are of the kind
	 */
	static long countOf(@Nonnull List<Transition> transitions, @Nonnull TransitionStage stage) {
		return transitions.stream().filter(it -> it.stage() == stage).count();
	}
}
