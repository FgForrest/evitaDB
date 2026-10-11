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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.dataType.Scope;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.index.virtual.DifferentialEngines.assertNoRegisteredPartition;
import static io.evitadb.index.virtual.DifferentialEngines.assertRegistered;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO JNO - remove at the end of #1615
/**
 * Drives single-owner reference partitions through their whole life - an owner entering a new partition (0 -> 1),
 * leaving it (1 -> 0), entering it again, moving it by a representative attribute or a scope, and taking it away with
 * the owner's removal - in a catalog that writes them as registry rows without index objects, compared with its twin
 * writing them as index objects next to the registry it maintains (see
 * {@link DifferentialEngines#writtenVirtually}). Every history runs in a catalog warming up and in a live one.
 *
 * The harness compares the two engines at every checkpoint - inside the session after every step, after a caught
 * failure, after the session closes, after a restart and after a crash recovery - registry included, so the rows of
 * the catalog writing virtually must keep the primary keys, owners, sort values and chain predecessors the twin's real
 * single-owner partitions have, and the membership lookup of the reduced indexes must agree before any reload. On top
 * of that, a check run on the catalog writing virtually at every checkpoint refuses any index object with fewer than
 * two owners of a partition of a reference whose partitions may be virtual, and every test asserts what its history
 * must have produced, so that no test passes by not exercising what it names.
 *
 * Every partition of the fixture has a single owner - a partition gaining a second owner is not part of this
 * lifecycle.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Single-owner partitions written as registry rows")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class VirtualPartitionSingletonLifecycleTest implements EvitaTestSupport {
	/**
	 * A product the fixture does not create.
	 */
	private static final int NEW_PRODUCT = 9;
	/**
	 * A category and a media no product of the fixture references.
	 */
	private static final int FREE_TARGET = 11;
	/**
	 * Another category no product of the fixture references.
	 */
	private static final int SECOND_FREE_TARGET = 12;
	/**
	 * A category and a media the fixture does not create.
	 */
	private static final int MISSING_TARGET = REFERENCED_ENTITY_COUNT + 1;
	/**
	 * A checkpoint interval that never elapses within a test, so the committed transactions stay in the write-ahead
	 * log.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;

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
	@DisplayName("a new localized owner enters its partitions as rows the twin's partitions agree with")
	void shouldWriteTheSingleOwnerPartitionsOfANewLocalizedOwnerAsRows(CatalogState state) {
		final DifferentialEngines theEngines = start("newOwner", state);
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		theEngines.step(
			"a new product in two locales enters a free category and a free media",
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true).upsertVia(session)
		);

		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT);
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(FREE_TARGET, ROLE_GALLERY), NEW_PRODUCT);
		assertRegistered(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), FREE_TARGET);
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET)
		);
		assertTrue(
			passedSince(theEngines, committedBefore).contains(
				new Transition(TransitionStage.ENTERED, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, partition)
			),
			"the owner must have entered the partition through the virtual arm"
		);
		assertTrue(
			countSince(theEngines, committedBefore, TransitionStage.MIRRORED) > 0,
			"the owner's values must have been written into the rows"
		);
		assertEquals(
			theEngines.getReferenceHistory().getCommitted(), theEngines.getMaintainingHistory().getCommitted(),
			"an owner entering partitions passes the same transitions in both engines"
		);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner gaining locales and entering a partition in one mutation fills its row from before them")
	void shouldFillTheRowFromTheLocalesTheOwnerHadBeforeTheMutation(CatalogState state) {
		final DifferentialEngines theEngines = start("localesBefore", state);
		final ReferenceKey newCategory = new ReferenceKey(REFERENCE_CATEGORIES, FREE_TARGET, -1);

		// the localized attributes come first, so the owner has its locales by the time its reference is inserted -
		// the reference's localized compound of each new locale is written by the locale finalization of the owner,
		// never by the insertion as well
		theEngines.step(
			"a new product gains two locales and enters a free category in one mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, NEW_PRODUCT, EntityExistence.MUST_NOT_EXIST,
					new UpsertAttributeMutation(ATTRIBUTE_CODE, "P" + NEW_PRODUCT),
					new UpsertAttributeMutation(ATTRIBUTE_NAME, Locale.ENGLISH, "Product"),
					new UpsertAttributeMutation(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt"),
					new InsertReferenceMutation(newCategory),
					new ReferenceAttributeMutation(newCategory, new UpsertAttributeMutation(ATTRIBUTE_ORDER, 11)),
					new ReferenceAttributeMutation(
						newCategory, new UpsertAttributeMutation(ATTRIBUTE_LABEL, Locale.ENGLISH, "Label")
					),
					new ReferenceAttributeMutation(
						newCategory, new UpsertAttributeMutation(ATTRIBUTE_LABEL, Locale.GERMAN, "Etikett")
					)
				)
			)
		);
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner losing a locale and relocating a row in one mutation fills the new row from before the loss")
	void shouldFillTheRelocatedRowWithTheLocaleTheOwnerLosesInTheSameMutation(CatalogState state) {
		final DifferentialEngines theEngines = start("localeLostOnRelocation", state);

		// product 2 has the German locale through its name and the label of its category reference; once both are
		// gone, its gallery row moves to another role, entering the new partition with the values the reference
		// holds - and the locale finalization of the mutation then removes the German compound from every partition
		// of the owner, the new one included, so entering it must have written it there
		theEngines.step(
			"a product loses its German values and shows its media in another role in one mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 2, EntityExistence.MUST_EXIST,
					new RemoveAttributeMutation(ATTRIBUTE_NAME, Locale.GERMAN),
					new ReferenceAttributeMutation(
						existingReferenceKey(session, 2, REFERENCE_CATEGORIES, 2),
						new RemoveAttributeMutation(ATTRIBUTE_LABEL, Locale.GERMAN)
					),
					new ReferenceAttributeMutation(
						existingReferenceKey(session, 2, REFERENCE_GALLERY, 2),
						new UpsertAttributeMutation(ATTRIBUTE_ROLE, ROLE_DETAIL)
					)
				)
			)
		);

		assertNoRegisteredPartition(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(2, ROLE_GALLERY));
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(2, ROLE_DETAIL), 2);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a mutation failing after any stage of entering or leaving a partition leaves both engines unchanged")
	void shouldLeaveNothingBehindAFailureAfterAnyStage(CatalogState state) {
		final DifferentialEngines theEngines = start("failures", state);
		final Consumer<EvitaSessionContract> enter =
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true).upsertVia(session);

		theEngines.failingStepAfter("fail after the row entered", TransitionStage.ENTERED, ENTITY_PRODUCT, 0, enter);
		theEngines.failingStepAfter(
			"fail after the second value was written into the row", TransitionStage.MIRRORED, ENTITY_PRODUCT, 1, enter
		);
		theEngines.failingStepAfter(
			"fail after the reflected row entered", TransitionStage.ENTERED, ENTITY_CATEGORY, 0, enter
		);
		assertNoRegisteredPartition(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET));

		theEngines.step("the owner enters at last", enter);
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET)
		);
		theEngines.failingStepAfter(
			"fail after the row was vacated", TransitionStage.VACATED, ENTITY_PRODUCT, 0,
			updateProduct(NEW_PRODUCT, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_TARGET))
		);
		theEngines.failingStepAfter(
			"fail after the reflected row was vacated", TransitionStage.VACATED, ENTITY_CATEGORY, 0,
			updateProduct(NEW_PRODUCT, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_TARGET))
		);
		assertEquals(
			partition,
			registeredPartitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET)),
			"the failed removals must have left the row where it was"
		);
		assertEquals(5, theEngines.getMaintainingHistory().getInjectedFailures(), "every failure must have fired");
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("the owner removing and re-inserting its reference in one mutation keeps the partition's primary key")
	void shouldKeepThePrimaryKeyOfAPartitionLeftAndEnteredInOneMutation(CatalogState state) {
		final DifferentialEngines theEngines = start("reenter", state);
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(4)
		);
		final int reflectedPartition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(4)
		);
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		theEngines.step(
			"the only owner removes and re-inserts its reference in one mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(existingReferenceKey(session, 4, 4)),
					new InsertReferenceMutation(newReferenceKey(4)),
					new ReferenceAttributeMutation(newReferenceKey(4), new UpsertAttributeMutation(ATTRIBUTE_ORDER, 40))
				)
			)
		);

		assertEquals(
			partition, registeredPartitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(4)),
			"the partition keeps its primary key"
		);
		assertEquals(
			reflectedPartition,
			registeredPartitionOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(4)),
			"the reflected partition keeps its primary key"
		);
		final List<Transition> passed = passedSince(theEngines, committedBefore);
		assertTrue(
			passed.contains(
				new Transition(TransitionStage.VACATED, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, partition)
			) && passed.contains(
				new Transition(TransitionStage.ENTERED, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, partition)
			),
			"the mutation must have vacated the partition and entered it again: " + passed
		);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a partition one owner leaves and another enters in one root mutation keeps its primary key")
	void shouldHandAVacatedPartitionToTheNextOwnerOfTheRootMutation(CatalogState state) {
		final DifferentialEngines theEngines = start("handOver", state);
		// the reflected partition of product 4 on the category side is owned by category 4 alone
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(4)
		);
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		// the reflected mutations follow the order of the product's: category 4 leaves first, the free one enters next
		theEngines.step(
			"category 4 leaves the partition and a free category enters it in one root mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(existingReferenceKey(session, 4, 4)),
					new InsertReferenceMutation(newReferenceKey(SECOND_FREE_TARGET))
				)
			)
		);

		assertRegistered(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(4), SECOND_FREE_TARGET);
		assertEquals(
			partition, registeredPartitionOf(theEngines.getMaintaining(), ENTITY_CATEGORY, Scope.LIVE, product(4)),
			"the next owner must have taken the primary key the partition had"
		);
		final List<Transition> passed = passedSince(theEngines, committedBefore).stream()
			.filter(it -> it.entityType().equals(ENTITY_CATEGORY) && it.partitionPrimaryKey() == partition)
			.filter(it -> it.stage() != TransitionStage.MIRRORED)
			.toList();
		assertEquals(
			List.of(TransitionStage.VACATED, TransitionStage.ENTERED), passed.stream().map(Transition::stage).toList(),
			"the first owner must have left the partition before the next one entered it"
		);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("changing the representative attribute moves the owner from one row to a new one")
	void shouldRelocateAnOwnerByItsRepresentativeAttribute(CatalogState state) {
		final DifferentialEngines theEngines = start("relocation", state);
		final int former = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, media(5, ROLE_GALLERY)
		);

		theEngines.step(
			"the product shows its media in another role",
			updateProduct(
				5,
				it -> it.setOrUpdateReference(
					REFERENCE_GALLERY, 5,
					ref -> ROLE_GALLERY.equals(ref.getAttribute(ATTRIBUTE_ROLE)),
					ref -> ref.setAttribute(ATTRIBUTE_ROLE, ROLE_DETAIL)
				)
			)
		);

		assertNoRegisteredPartition(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(5, ROLE_GALLERY));
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(5, ROLE_DETAIL), 5);
		assertNotEquals(
			former,
			registeredPartitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, media(5, ROLE_DETAIL)),
			"the relocated owner enters a partition of another key, under a primary key of its own"
		);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner moving to the archive and back takes its rows along")
	void shouldMoveTheRowsOfAnOwnerBetweenScopes(CatalogState state) {
		final DifferentialEngines theEngines = start("scopeMove", state);

		theEngines.step("the product is archived", session -> session.archiveEntity(ENTITY_PRODUCT, 3));
		assertNoRegisteredPartition(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(3));
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.ARCHIVED, category(3), 3);
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.ARCHIVED, media(3, ROLE_GALLERY), 3);

		theEngines.step("the product is restored", session -> session.restoreEntity(ENTITY_PRODUCT, 3));
		assertNoRegisteredPartition(theEngines, ENTITY_PRODUCT, Scope.ARCHIVED, category(3));
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(3), 3);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("removing an owner drops its rows and the rows of its reflections")
	void shouldDropTheRowsOfARemovedOwner(CatalogState state) {
		final DifferentialEngines theEngines = start("entityRemoval", state);

		theEngines.step("the product is removed", session -> session.deleteEntity(ENTITY_PRODUCT, 2));

		assertNoRegisteredPartition(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(2));
		assertNoRegisteredPartition(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(2, ROLE_GALLERY));
		assertNoRegisteredPartition(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(2));
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a locale the owner gains and loses moves the localized compound of its row")
	void shouldMoveTheLocalizedCompoundOfARowOnALocaleOnlyChange(CatalogState state) {
		final DifferentialEngines theEngines = start("localeOnly", state);
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(1)
		);
		final int committedBefore = theEngines.getMaintainingHistory().getCommitted().size();

		theEngines.step(
			"the product gains a locale",
			updateProduct(1, it -> it.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt 1"))
		);
		assertTrue(
			passedSince(theEngines, committedBefore).contains(
				new Transition(TransitionStage.MIRRORED, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, partition)
			),
			"the gained locale must have written the localized compound into the row"
		);
		theEngines.step(
			"the product loses the locale again",
			updateProduct(1, it -> it.removeAttribute(ATTRIBUTE_NAME, Locale.GERMAN))
		);
		assertEquals(
			partition, registeredPartitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(1)),
			"a locale change moves values within the row it does not replace"
		);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("the last localized value and the reference removed in one mutation leave no residue in the registry")
	void shouldRemoveTheLastLocalizedValueAndTheReferenceInOneMutation(CatalogState state) {
		final DifferentialEngines theEngines = start("lastLocalizedValue", state);

		// product 2 has the German locale through its name and through the label of its category reference alone
		theEngines.step(
			"the product loses its last German value and the reference in one mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 2, EntityExistence.MUST_EXIST,
					new RemoveAttributeMutation(ATTRIBUTE_NAME, Locale.GERMAN),
					new ReferenceAttributeMutation(
						existingReferenceKey(session, 2, 2),
						new RemoveAttributeMutation(ATTRIBUTE_LABEL, Locale.GERMAN)
					),
					new RemoveReferenceMutation(existingReferenceKey(session, 2, 2))
				)
			)
		);

		assertNoRegisteredPartition(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(2));
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner of a reference without duplicates gaining a locale writes its existing row only")
	void shouldNotBringANewPartitionIntoExistenceWhenAnOwnerGainsALocale(CatalogState state) {
		final DifferentialEngines theEngines = start("noSecondPartition", state);
		final int partition = registeredPartitionOf(
			theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(3)
		);
		final int lastIndexPrimaryKey = lastIndexPrimaryKeyOf(theEngines.getMaintaining(), ENTITY_PRODUCT);

		theEngines.step(
			"the product gains a locale",
			updateProduct(3, it -> it.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt 3"))
		);

		assertEquals(
			partition, registeredPartitionOf(theEngines.getMaintaining(), ENTITY_PRODUCT, Scope.LIVE, category(3)),
			"the owner keeps its partition"
		);
		assertEquals(
			lastIndexPrimaryKey, lastIndexPrimaryKeyOf(theEngines.getMaintaining(), ENTITY_PRODUCT),
			"no primary key of a second partition may have been allocated"
		);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a category created after the product referencing it reflects the product's row")
	void shouldReflectTheRowOfAProductIntoACategoryCreatedAfterIt(CatalogState state) {
		final DifferentialEngines theEngines = start("reflectedOnCreation", state);
		theEngines.step(
			"a new product enters a category that does not exist yet",
			session -> newProduct(session, NEW_PRODUCT, MISSING_TARGET, false).upsertVia(session)
		);
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(MISSING_TARGET), NEW_PRODUCT);
		assertEquals(
			List.of(), reflectedProductsOf(theEngines.getMaintaining(), MISSING_TARGET),
			"a category that does not exist reflects nothing"
		);

		// the new category finds the product it reflects among the owners of the product's partitions of it - the
		// row of the only one, which has no index object
		theEngines.step(
			"the category is created",
			session -> session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, MISSING_TARGET))
		);

		assertEquals(
			List.of(NEW_PRODUCT), reflectedProductsOf(theEngines.getMaintaining(), MISSING_TARGET),
			"the new category must reflect the product read out of its row"
		);
		assertRegistered(theEngines, ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), MISSING_TARGET);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	@Test
	@DisplayName("the rows written virtually are rebuilt by the replay of the write-ahead log and survive a restart")
	void shouldReplayTheWriteAheadLogIntoRows() {
		final DifferentialEngines theEngines = DifferentialEngines.writtenVirtually(
			this, "replay", ENTITY_TYPES,
			TransactionOptions.builder().checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS).build()
		);
		this.engines = theEngines;
		theEngines.addMaintainingEngineCheck(
			VirtualPartitionInvariants::assertNoPartitionObjectBelowTwoOwners
		);
		theEngines.prepare(CatalogState.ALIVE, SingleOwnerPartitionFixture::setUpLocalizedSingleOwners);
		// going live is deferred like any other round; the clean close publishes it
		theEngines.restart();

		theEngines.step(
			"a new product enters free partitions",
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true).upsertVia(session)
		);
		theEngines.step(
			"an owner leaves its partition",
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, 1))
		);
		theEngines.step("an owner is archived", session -> session.archiveEntity(ENTITY_PRODUCT, 3));
		theEngines.step(
			"an owner relocates its gallery row",
			updateProduct(
				5,
				it -> it.setOrUpdateReference(
					REFERENCE_GALLERY, 5,
					ref -> ROLE_GALLERY.equals(ref.getAttribute(ATTRIBUTE_ROLE)),
					ref -> ref.setAttribute(ATTRIBUTE_ROLE, ROLE_DETAIL)
				)
			)
		);

		final DifferentialEngines.Reload recovered = theEngines.crashAndRecover();
		assertTrue(
			recovered.maintaining().loadedVersion() > recovered.maintaining().imageVersion(),
			"the recovery must have replayed the tail of the write-ahead log"
		);
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT);
		assertNoRegisteredPartition(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(1));
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.ARCHIVED, category(3), 3);
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, media(5, ROLE_DETAIL), 5);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());

		theEngines.step(
			"the recovered catalog keeps writing rows",
			session -> newProduct(session, NEW_PRODUCT + 1, SECOND_FREE_TARGET, false).upsertVia(session)
		);
		theEngines.restart();
		assertRegistered(theEngines, ENTITY_PRODUCT, Scope.LIVE, category(SECOND_FREE_TARGET), NEW_PRODUCT + 1);
		assertNoPartitionObjectOfVirtualReference(theEngines.getMaintaining());
	}

	/**
	 * Returns the products a category of the committed catalog of an engine reflects.
	 *
	 * @param evita    the engine
	 * @param category the category
	 * @return the primary keys of the reflected products, ascending, empty when the category does not exist
	 */
	@Nonnull
	private static List<Integer> reflectedProductsOf(@Nonnull Evita evita, int category) {
		return evita.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, List<Integer>>) session -> session.getEntity(
					ENTITY_CATEGORY, category, referenceContentAll()
				)
				.map(
					entity -> entity.getReferences(REFERENCE_PRODUCTS)
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.sorted()
						.toList()
				)
				.orElse(List.of())
		);
	}

	/**
	 * Returns the last primary key the index sequence of a collection of the committed catalog has handed out.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type
	 * @return the last index primary key
	 */
	private static int lastIndexPrimaryKeyOf(@Nonnull Evita evita, @Nonnull String entityType) {
		return evita.management()
			.getEntityCollectionStatistics(
				CATALOG, entityType,
				EnumSet.of(CatalogStatisticsComponent.COLLECTIONS)
			)
			.headerIfPresent()
			.orElseThrow()
			.lastEntityIndexPrimaryKey();
	}

	/**
	 * Returns the transitions the catalog writing virtually committed since the given count of committed transitions.
	 *
	 * @param engines the engines
	 * @param from    the count of committed transitions to start after
	 * @return the transitions
	 */
	@Nonnull
	private static List<Transition> passedSince(@Nonnull DifferentialEngines engines, int from) {
		final List<Transition> committed = engines.getMaintainingHistory().getCommitted();
		return committed.subList(from, committed.size());
	}

	/**
	 * Counts the transitions of the given kind the catalog writing virtually committed since the given count of
	 * committed transitions.
	 *
	 * @param engines the engines
	 * @param from    the count of committed transitions to start after
	 * @param stage   the kind
	 * @return how many of them are of the kind
	 */
	private static long countSince(@Nonnull DifferentialEngines engines, int from, @Nonnull TransitionStage stage) {
		return passedSince(engines, from).stream().filter(it -> it.stage() == stage).count();
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
		return existingReferenceKey(session, product, REFERENCE_CATEGORIES, category);
	}

	/**
	 * Returns the key, with its internal primary key, of the only existing reference of a product to a target.
	 *
	 * @param session       the session to read in
	 * @param product       the product
	 * @param referenceName the reference
	 * @param target        the referenced entity
	 * @return the reference key
	 */
	@Nonnull
	private static ReferenceKey existingReferenceKey(
		@Nonnull EvitaSessionContract session,
		int product,
		@Nonnull String referenceName,
		int target
	) {
		final List<ReferenceKey> keys = session.getEntity(ENTITY_PRODUCT, product, referenceContentAll())
			.orElseThrow()
			.getReferences(referenceName)
			.stream()
			.filter(it -> it.getReferencedPrimaryKey() == target)
			.map(ReferenceContract::getReferenceKey)
			.toList();
		assertEquals(1, keys.size(), "product " + product + " must reference " + target + " once: " + keys);
		return keys.get(0);
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
	 * Starts the engines comparing the catalog writing virtually with its twin, with the check refusing index objects
	 * of single-owner partitions registered, and prepares the fixture in the given state.
	 *
	 * @param label a label naming the engines' directories
	 * @param state the catalog state the engines should end in
	 * @return the engines
	 */
	@Nonnull
	private DifferentialEngines start(@Nonnull String label, @Nonnull CatalogState state) {
		final DifferentialEngines theEngines = DifferentialEngines.writtenVirtually(this, label, ENTITY_TYPES, null);
		this.engines = theEngines;
		theEngines.addMaintainingEngineCheck(
			VirtualPartitionInvariants::assertNoPartitionObjectBelowTwoOwners
		);
		theEngines.prepare(state, SingleOwnerPartitionFixture::setUpLocalizedSingleOwners);
		return theEngines;
	}

}
