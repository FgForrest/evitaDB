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
import io.evitadb.api.exception.ReferenceCardinalityViolatedException;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
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
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.virtual.VirtualPartitionHistory.Recovery;
import io.evitadb.index.virtual.VirtualPartitionInvariants.RegisteredRow;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
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
import java.util.SortedMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives single-owner reference partitions through their whole life - an owner entering a new partition (0 -> 1),
 * leaving it (1 -> 0), entering it again, handing it over to another owner, moving it by its target, by a
 * representative attribute or by a scope, and taking it away with the owner's removal - in a catalog that writes them
 * as registry rows without index objects. Every history runs in a catalog warming up and in a live one.
 *
 * {@link VirtualPartitionHistory} checks the catalog at every moment of a history - inside the session after every
 * step, after a caught failure, after the session closes, after a restart and after a crash recovery: every partition
 * against the entity bodies - its row, owner, sort values and chain predecessors, and no row or index object the bodies
 * do not produce - its primary key against the one it was observed under before, the invariants of the representation
 * and, once committed, what the catalog stores. On top of that every test asserts the rows its history must have
 * produced - which partitions are rows, under which primary keys, naming which owners, and that no partition of a
 * reference whose partitions may be virtual is an index object - so that no test passes by not exercising what it
 * names.
 *
 * A root mutation failing at its end must leave every row, index object, primary key and the reduced-index membership
 * as they were (see {@link VirtualPartitionHistory#failingStep(String, Consumer, Class)}). Its failure is a mandatory
 * reference left missing (see {@link SingleOwnerPartitionFixture#withoutTheMandatoryBrand}), which the consistency
 * check of the root mutation finds after every reference write of the mutation - the reflected ones included - so the
 * rollback has everything the mutation wrote of the partitions to revert.
 *
 * Every partition of the fixture has a single owner - a partition gaining a second owner is not part of this
 * lifecycle, except inside a failing root mutation whose rollback reverts it.
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
	 * Yet another category no product of the fixture references.
	 */
	private static final int THIRD_FREE_TARGET = 13;
	/**
	 * A category and a media the fixture does not create.
	 */
	private static final int MISSING_TARGET = REFERENCED_ENTITY_COUNT + 1;
	/**
	 * A checkpoint interval that never elapses within a test, so the committed transactions stay in the write-ahead
	 * log.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;
	/**
	 * The product showing {@link #MANY_PARTITIONS} media of its own.
	 */
	private static final int OWNER_OF_MANY = 50;
	/**
	 * How many single-owner partitions {@link #OWNER_OF_MANY} has.
	 */
	private static final int MANY_PARTITIONS = 300;
	/**
	 * The first media {@link #OWNER_OF_MANY} shows.
	 */
	private static final int FIRST_OF_MANY_MEDIA = 1_001;

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
	@DisplayName("a new localized owner enters its partitions as rows holding its values")
	void shouldWriteTheSingleOwnerPartitionsOfANewLocalizedOwnerAsRows(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("newOwner", state);

		theHistory.step(
			"a new product in two locales enters a free category and a free media",
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true).upsertVia(session)
		);

		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, media(FREE_TARGET, ROLE_GALLERY), NEW_PRODUCT);
		theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), FREE_TARGET);
		// the body oracle holds every value of the row to the body; these show the row holds values at all
		final Evita evita = theHistory.getEvita();
		assertSortValue(evita, REFERENCE_CATEGORIES, partition, ATTRIBUTE_ORDER, null, true);
		assertSortValue(evita, REFERENCE_CATEGORIES, partition, COMPOUND_LABEL_AND_ORDER, Locale.ENGLISH, true);
		assertSortValue(evita, REFERENCE_CATEGORIES, partition, COMPOUND_LABEL_AND_ORDER, Locale.GERMAN, true);
		assertTrue(
			VirtualPartitionInvariants.registeredRowsOf(collectionOf(evita, ENTITY_PRODUCT)).values().stream()
				.noneMatch(it -> it.referenceName().equals(REFERENCE_BRAND)),
			"a reference indexed for partitioning has no rows"
		);
		assertNoPartitionObjectOfVirtualReference(evita);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner gaining locales and entering a partition in one mutation fills its row from before them")
	void shouldFillTheRowFromTheLocalesTheOwnerHadBeforeTheMutation(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("localesBefore", state);
		final ReferenceKey newCategory = new ReferenceKey(REFERENCE_CATEGORIES, FREE_TARGET, -1);

		// the localized attributes come first, so the owner has its locales by the time its reference is inserted -
		// the reference's localized compound of each new locale is written by the locale finalization of the owner,
		// never by the insertion as well
		theHistory.step(
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

		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT);
		assertSortValue(
			theHistory.getEvita(), REFERENCE_CATEGORIES, partition, COMPOUND_LABEL_AND_ORDER, Locale.GERMAN, true
		);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner losing a locale and relocating a row in one mutation fills the new row from before the loss")
	void shouldFillTheRelocatedRowWithTheLocaleTheOwnerLosesInTheSameMutation(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("localeLostOnRelocation", state);

		// product 2 has the German locale through its name and the label of its category reference; once both are
		// gone, its gallery row moves to another role, entering the new partition with the values the reference
		// holds - and the locale finalization of the mutation then removes the German compound from every partition
		// of the owner, the new one included, so entering it must have written it there
		theHistory.step(
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

		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, media(2, ROLE_GALLERY));
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, media(2, ROLE_DETAIL), 2);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a new owner failing at the end of its mutation leaves no row behind, reflected ones included")
	void shouldLeaveNoRowBehindANewOwnerFailingAtTheEnd(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"newOwnerFailure", state, SingleOwnerPartitionFixture::setUpLocalizedSingleOwnersWithMandatoryBrand
		);

		// 0 -> 1, in the product's partitions and in the reflected one of the category
		theHistory.failingStep(
			"a new product without its mandatory brand enters free partitions",
			withoutTheMandatoryBrand(session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true)),
			ReferenceCardinalityViolatedException.class
		);
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET));
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, media(FREE_TARGET, ROLE_GALLERY));
		theHistory.assertNoRow(ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT));

		theHistory.step(
			"the product enters with its brand afterwards",
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true).upsertVia(session)
		);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT);
		theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), FREE_TARGET);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner failing to leave at the end of its mutation keeps its row, the reflected one included")
	void shouldKeepTheRowsAnOwnerFailsToLeaveAtTheEnd(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"leavingOwnerFailure", state, SingleOwnerPartitionFixture::setUpLocalizedSingleOwnersWithMandatoryBrand
		);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		final int reflectedPartition = theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(4), 4);

		// 1 -> 0, in the product's partition and in the reflected one
		theHistory.failingStep(
			"the product leaves its category and its mandatory brand",
			withoutTheMandatoryBrand(
				session -> openProductForWrite(session, 4).removeReference(REFERENCE_CATEGORIES, 4)
			),
			ReferenceCardinalityViolatedException.class
		);

		assertEquals(
			partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4),
			"the failed removal must have left the row where it was"
		);
		assertEquals(
			reflectedPartition, theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(4), 4),
			"the failed removal must have left the reflected row where it was"
		);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner failing at the end of entering two partitions leaves neither row behind")
	void shouldLeaveNoRowBehindAnOwnerFailingToEnterTwoPartitions(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"twoPartitionsFailure", state, SingleOwnerPartitionFixture::setUpLocalizedSingleOwnersWithMandatoryBrand
		);
		final int reflectedPartition = theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(4), 4);

		// 0 -> 1 twice, the first partition entered and populated before the second - and the product's reflected
		// partition on the category side gaining two more owners, a promotion the rollback reverts as well
		theHistory.failingStep(
			"a product enters two free categories and leaves its mandatory brand",
			withoutTheMandatoryBrand(
				session -> openProductForWrite(session, 4)
					.setReference(REFERENCE_CATEGORIES, SECOND_FREE_TARGET, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 1))
					.setReference(REFERENCE_CATEGORIES, THIRD_FREE_TARGET, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 2))
			),
			ReferenceCardinalityViolatedException.class
		);

		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(SECOND_FREE_TARGET));
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(THIRD_FREE_TARGET));
		assertEquals(
			reflectedPartition, theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(4), 4),
			"the reflected partition must be the row of its only owner again"
		);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a mutation failing on a unique value after entering a partition leaves no row behind")
	void shouldLeaveNoRowBehindAMutationFailingOnAUniqueValue(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("uniqueFailure", state);

		theHistory.failingStep(
			"a product enters a free category and then takes another product's unique code",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new InsertReferenceMutation(newReferenceKey(FREE_TARGET)),
					new UpsertAttributeMutation(ATTRIBUTE_CODE, "P1")
				)
			),
			UniqueValueViolationException.class
		);

		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET));
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("the owner removing and re-inserting its reference in one mutation keeps the partition's primary key")
	void shouldKeepThePrimaryKeyOfAPartitionLeftAndEnteredInOneMutation(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("reenter", state);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		final int reflectedPartition = theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(4), 4);

		theHistory.step(
			"the only owner removes and re-inserts its reference in one mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(existingReferenceKey(session, 4, REFERENCE_CATEGORIES, 4)),
					new InsertReferenceMutation(newReferenceKey(4)),
					new ReferenceAttributeMutation(newReferenceKey(4), new UpsertAttributeMutation(ATTRIBUTE_ORDER, 40))
				)
			)
		);

		assertEquals(
			partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4),
			"the partition keeps its primary key"
		);
		assertEquals(
			reflectedPartition, theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(4), 4),
			"the reflected partition keeps its primary key"
		);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("the owner re-inserting its reference with the same values in one mutation leaves the indexes alone")
	void shouldEndAChurnOfTheOnlyOwnerAsIfNothingChanged(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("unchangedChurn", state);
		final SortedMap<String, String> before =
			VirtualPartitionInvariants.describeIndexes(theHistory.getEvita(), CATALOG);

		// product 4 references category 4 with its order, an English and a German label and the head of the chain
		theHistory.step(
			"the only owner removes and re-inserts its reference with the very same values in one mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(existingReferenceKey(session, 4, REFERENCE_CATEGORIES, 4)),
					new InsertReferenceMutation(newReferenceKey(4)),
					new ReferenceAttributeMutation(newReferenceKey(4), new UpsertAttributeMutation(ATTRIBUTE_ORDER, 4)),
					new ReferenceAttributeMutation(
						newReferenceKey(4), new UpsertAttributeMutation(ATTRIBUTE_LABEL, Locale.ENGLISH, "Label 4")
					),
					new ReferenceAttributeMutation(
						newReferenceKey(4), new UpsertAttributeMutation(ATTRIBUTE_LABEL, Locale.GERMAN, "Etikett 4")
					),
					new ReferenceAttributeMutation(
						newReferenceKey(4), new UpsertAttributeMutation(ATTRIBUTE_POSITION, Predecessor.HEAD)
					)
				)
			)
		);

		VirtualPartitionInvariants.assertUnchanged(
			"the churn of the only owner", before,
			VirtualPartitionInvariants.describeIndexes(theHistory.getEvita(), CATALOG)
		);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a partition one owner leaves and another enters in one root mutation keeps its primary key")
	void shouldHandAVacatedPartitionToTheNextOwnerOfTheRootMutation(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("handOver", state);
		// the reflected partition of product 4 on the category side is owned by category 4 alone
		final int partition = theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(4), 4);

		// the reflected mutations follow the order of the product's: category 4 leaves first, the free one enters next
		theHistory.step(
			"category 4 leaves the partition and a free category enters it in one root mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(existingReferenceKey(session, 4, REFERENCE_CATEGORIES, 4)),
					new InsertReferenceMutation(newReferenceKey(SECOND_FREE_TARGET))
				)
			)
		);

		assertEquals(
			partition, theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(4), SECOND_FREE_TARGET),
			"the next owner must have taken the primary key the partition had"
		);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a partition its only owner leaves is entered by another owner in a later mutation of the session")
	void shouldHandAVacatedPartitionToAnOwnerOfALaterRootMutation(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("replacement", state);
		// the reservation of a vacated primary key lasts one root mutation, so the new owner may get another one
		theHistory.getLedger().declareEmptied(ENTITY_PRODUCT, Scope.LIVE, category(4));

		theHistory.transaction(
			"the only owner leaves the partition and a new product enters it in one session",
			List.of(
				updateProduct(4, it -> it.removeReference(REFERENCE_CATEGORIES, 4)),
				session -> session.createNewEntity(ENTITY_PRODUCT, NEW_PRODUCT)
					.setAttribute(ATTRIBUTE_CODE, "P" + NEW_PRODUCT)
					.setReference(REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 44))
					.upsertVia(session)
			)
		);

		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), NEW_PRODUCT);
		theHistory.assertNoRow(ENTITY_CATEGORY, Scope.LIVE, product(4));
		theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), 4);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("changing the representative attribute moves the owner from one row to a new one")
	void shouldRelocateAnOwnerByItsRepresentativeAttribute(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("relocation", state);
		final int former = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, media(5, ROLE_GALLERY), 5);

		theHistory.step(
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

		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, media(5, ROLE_GALLERY));
		assertNotEquals(
			former, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, media(5, ROLE_DETAIL), 5),
			"the relocated owner enters a partition of another key, under a primary key of its own"
		);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner moving to another target and back moves its row there and back")
	void shouldMoveTheRowOfAnOwnerToAnotherTargetAndBack(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("targetMove", state);
		final int reflectedPartition = theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(4), 4);

		theHistory.step(
			"the only owner moves to a free category",
			updateProduct(
				4,
				it -> it.removeReference(REFERENCE_CATEGORIES, 4).setReference(REFERENCE_CATEGORIES, SECOND_FREE_TARGET)
			)
		);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(SECOND_FREE_TARGET), 4);
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(4));
		// on the category side the product's partition changes its owner within the root mutation
		assertEquals(
			reflectedPartition, theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(4), SECOND_FREE_TARGET),
			"the reflected partition handed over within the root mutation keeps its primary key"
		);

		theHistory.step(
			"and back",
			updateProduct(
				4,
				it -> it.removeReference(REFERENCE_CATEGORIES, SECOND_FREE_TARGET).setReference(REFERENCE_CATEGORIES, 4)
			)
		);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(SECOND_FREE_TARGET));
		theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(4), 4);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner moving to the archive and back takes its rows along")
	void shouldMoveTheRowsOfAnOwnerBetweenScopes(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("scopeMove", state);

		theHistory.step("the product is archived", session -> session.archiveEntity(ENTITY_PRODUCT, 3));
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(3));
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, media(3, ROLE_GALLERY));
		theHistory.assertRow(ENTITY_PRODUCT, Scope.ARCHIVED, category(3), 3);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.ARCHIVED, media(3, ROLE_GALLERY), 3);

		theHistory.step("the product is restored", session -> session.restoreEntity(ENTITY_PRODUCT, 3));
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.ARCHIVED, category(3));
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.ARCHIVED, media(3, ROLE_GALLERY));
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(3), 3);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, media(3, ROLE_GALLERY), 3);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("removing an owner drops its rows and the rows of its reflections")
	void shouldDropTheRowsOfARemovedOwner(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("entityRemoval", state);

		theHistory.step("the product is removed", session -> session.deleteEntity(ENTITY_PRODUCT, 2));

		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(2));
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, media(2, ROLE_GALLERY));
		theHistory.assertNoRow(ENTITY_CATEGORY, Scope.LIVE, product(2));
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a locale the owner gains and loses moves the localized compound of its row")
	void shouldMoveTheLocalizedCompoundOfARowOnALocaleOnlyChange(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("localeOnly", state);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(1), 1);
		assertSortValue(
			theHistory.getEvita(), REFERENCE_CATEGORIES, partition, COMPOUND_LABEL_AND_ORDER, Locale.GERMAN, false
		);

		theHistory.step(
			"the product gains a locale",
			updateProduct(1, it -> it.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt 1"))
		);
		assertSortValue(
			theHistory.getEvita(), REFERENCE_CATEGORIES, partition, COMPOUND_LABEL_AND_ORDER, Locale.GERMAN, true
		);

		theHistory.step(
			"the product loses the locale again",
			updateProduct(1, it -> it.removeAttribute(ATTRIBUTE_NAME, Locale.GERMAN))
		);
		assertSortValue(
			theHistory.getEvita(), REFERENCE_CATEGORIES, partition, COMPOUND_LABEL_AND_ORDER, Locale.GERMAN, false
		);
		assertEquals(
			partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(1), 1),
			"a locale change moves values within the row it does not replace"
		);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("the last localized value and the reference removed in one mutation leave no residue in the registry")
	void shouldRemoveTheLastLocalizedValueAndTheReferenceInOneMutation(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("lastLocalizedValue", state);

		// product 2 has the German locale through its name and through the label of its category reference alone
		theHistory.step(
			"the product loses its last German value and the reference in one mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 2, EntityExistence.MUST_EXIST,
					new RemoveAttributeMutation(ATTRIBUTE_NAME, Locale.GERMAN),
					new ReferenceAttributeMutation(
						existingReferenceKey(session, 2, REFERENCE_CATEGORIES, 2),
						new RemoveAttributeMutation(ATTRIBUTE_LABEL, Locale.GERMAN)
					),
					new RemoveReferenceMutation(existingReferenceKey(session, 2, REFERENCE_CATEGORIES, 2))
				)
			)
		);

		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(2));
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner of a reference without duplicates gaining a locale writes its existing row only")
	void shouldNotBringANewPartitionIntoExistenceWhenAnOwnerGainsALocale(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("noSecondPartition", state);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(3), 3);
		final int lastIndexPrimaryKey = lastIndexPrimaryKeyOf(theHistory.getEvita(), ENTITY_PRODUCT);

		theHistory.step(
			"the product gains a locale",
			updateProduct(3, it -> it.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt 3"))
		);

		assertEquals(
			partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(3), 3), "the owner keeps its partition"
		);
		assertEquals(
			lastIndexPrimaryKey, lastIndexPrimaryKeyOf(theHistory.getEvita(), ENTITY_PRODUCT),
			"no primary key of a second partition may have been allocated"
		);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a category created after the product referencing it reflects the product's row")
	void shouldReflectTheRowOfAProductIntoACategoryCreatedAfterIt(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("reflectedOnCreation", state);
		theHistory.step(
			"a new product enters a category that does not exist yet",
			session -> newProduct(session, NEW_PRODUCT, MISSING_TARGET, false).upsertVia(session)
		);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(MISSING_TARGET), NEW_PRODUCT);
		assertEquals(
			List.of(), reflectedProductsOf(theHistory.getEvita(), MISSING_TARGET),
			"a category that does not exist reflects nothing"
		);

		// the new category finds the product it reflects among the owners of the product's partitions of it - the
		// row of the only one, which has no index object
		theHistory.step(
			"the category is created",
			session -> session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, MISSING_TARGET))
		);

		assertEquals(
			List.of(NEW_PRODUCT), reflectedProductsOf(theHistory.getEvita(), MISSING_TARGET),
			"the new category must reflect the product read out of its row"
		);
		theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), MISSING_TARGET);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner of many partitions gaining a locale, archived and removed keeps them rows, never objects")
	void shouldKeepEveryPartitionOfAnOwnerOfManyARow(CatalogState state) {
		final VirtualPartitionHistory theHistory = new VirtualPartitionHistory(this, "ownerOfMany", null);
		this.history = theHistory;
		theHistory.prepare(
			state,
			session -> {
				setUpLocalizedSingleOwners(session);
				final EntityBuilder owner = session.createNewEntity(ENTITY_PRODUCT, OWNER_OF_MANY)
					.setAttribute(ATTRIBUTE_CODE, "P" + OWNER_OF_MANY)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Owner of many");
				for (int media = FIRST_OF_MANY_MEDIA; media < FIRST_OF_MANY_MEDIA + MANY_PARTITIONS; media++) {
					session.upsertEntity(session.createNewEntity(ENTITY_MEDIA, media));
					final int weight = media;
					owner.setOrUpdateReference(
						REFERENCE_GALLERY, media,
						ref -> ROLE_GALLERY.equals(ref.getAttribute(ATTRIBUTE_ROLE)),
						ref -> ref.setAttribute(ATTRIBUTE_ROLE, ROLE_GALLERY)
							.setAttribute(ATTRIBUTE_WEIGHT, weight)
							.setAttribute(ATTRIBUTE_CAPTION, Locale.ENGLISH, "Caption " + weight)
					);
				}
				owner.upsertVia(session);
			}
		);
		final Evita evita = theHistory.getEvita();
		assertEquals(
			MANY_PARTITIONS, rowsOf(evita, OWNER_OF_MANY, Scope.LIVE), "every partition of the owner is a row"
		);
		final int firstPartition = theHistory.assertRow(
			ENTITY_PRODUCT, Scope.LIVE, media(FIRST_OF_MANY_MEDIA, ROLE_GALLERY), OWNER_OF_MANY
		);
		assertNoPartitionObjectOfVirtualReference(evita);

		// a locale gained writes the localized compound of the new locale into every row
		theHistory.step(
			"the owner gains a locale",
			updateProduct(OWNER_OF_MANY, it -> it.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Besitzer"))
		);
		assertEquals(MANY_PARTITIONS, rowsOf(evita, OWNER_OF_MANY, Scope.LIVE), "the locale change keeps every row");
		assertSortValue(evita, REFERENCE_GALLERY, firstPartition, COMPOUND_CAPTION_AND_WEIGHT, Locale.GERMAN, true);
		assertNoPartitionObjectOfVirtualReference(evita);

		// the scope move takes every row into the archive
		theHistory.step("the owner is archived", session -> session.archiveEntity(ENTITY_PRODUCT, OWNER_OF_MANY));
		assertEquals(0, rowsOf(evita, OWNER_OF_MANY, Scope.LIVE), "no row of the owner stays live");
		assertEquals(MANY_PARTITIONS, rowsOf(evita, OWNER_OF_MANY, Scope.ARCHIVED), "every row moves to the archive");
		assertNoPartitionObjectOfVirtualReference(evita);

		// the removal drops every row
		theHistory.step("the owner is removed", session -> session.deleteEntity(ENTITY_PRODUCT, OWNER_OF_MANY));
		assertEquals(0, rowsOf(evita, OWNER_OF_MANY, Scope.ARCHIVED), "the removal drops every row of the owner");
		assertNoPartitionObjectOfVirtualReference(evita);
	}

	@Test
	@DisplayName("the rows written virtually are rebuilt by the replay of the write-ahead log and survive a restart")
	void shouldReplayTheWriteAheadLogIntoRows() {
		final VirtualPartitionHistory theHistory = new VirtualPartitionHistory(
			this, "replay", TransactionOptions.builder().checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS).build()
		);
		this.history = theHistory;
		theHistory.prepare(CatalogState.ALIVE, SingleOwnerPartitionFixture::setUpLocalizedSingleOwners);
		// going live is deferred like any other round; the clean close publishes it
		theHistory.restart();

		theHistory.step(
			"a new product enters free partitions",
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true).upsertVia(session)
		);
		theHistory.step(
			"an owner leaves its partition",
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, 1))
		);
		theHistory.step("an owner is archived", session -> session.archiveEntity(ENTITY_PRODUCT, 3));
		theHistory.step(
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

		// the recovery replays the tail of the write-ahead log, which mints the partitions the tail created anew
		final PartitionIdentityLedger ledger = theHistory.getLedger();
		ledger.declareReplayed(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET));
		ledger.declareReplayed(ENTITY_PRODUCT, Scope.LIVE, media(FREE_TARGET, ROLE_GALLERY));
		ledger.declareReplayed(ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT));
		ledger.declareReplayed(ENTITY_PRODUCT, Scope.ARCHIVED, category(3));
		ledger.declareReplayed(ENTITY_PRODUCT, Scope.ARCHIVED, media(3, ROLE_GALLERY));
		ledger.declareReplayed(ENTITY_PRODUCT, Scope.LIVE, media(5, ROLE_DETAIL));
		final Recovery recovered = theHistory.crashAndRecover();
		assertTrue(
			recovered.loadedVersion() > recovered.imageVersion(),
			"the recovery must have replayed the tail of the write-ahead log"
		);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT);
		theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), FREE_TARGET);
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(1));
		theHistory.assertRow(ENTITY_PRODUCT, Scope.ARCHIVED, category(3), 3);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, media(5, ROLE_DETAIL), 5);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());

		theHistory.step(
			"the recovered catalog keeps writing rows",
			session -> newProduct(session, NEW_PRODUCT + 1, SECOND_FREE_TARGET, false).upsertVia(session)
		);
		theHistory.restart();
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(SECOND_FREE_TARGET), NEW_PRODUCT + 1);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	/**
	 * Asserts whether the committed row of a partition of the products holds a value of a sortable attribute or
	 * compound of its reference.
	 *
	 * @param evita         the engine
	 * @param referenceName the reference of the partition
	 * @param partition     the primary key of the row
	 * @param name          the sortable attribute or compound
	 * @param locale        the locale of the value, `null` for a value without a locale
	 * @param expected      TRUE when the row must hold a value
	 */
	private static void assertSortValue(
		@Nonnull Evita evita,
		@Nonnull String referenceName,
		int partition,
		@Nonnull String name,
		@Nullable Locale locale,
		boolean expected
	) {
		final VirtualReferencePartitionIndex registry = registryOf(evita, ENTITY_PRODUCT, Scope.LIVE, referenceName);
		assertNotNull(registry, "the reference `" + referenceName + "` of the products must have its registry");
		assertEquals(
			expected,
			registry.getSortValueId(new AttributeIndexKey(referenceName, name, locale), partition) !=
				VirtualReferencePartitionIndex.NO_SORT_VALUE_ID,
			"the row " + partition + " must " + (expected ? "" : "not ") + "hold a value of `" + name + "` in " + locale
		);
	}

	/**
	 * Counts the committed gallery rows of a product in a scope.
	 *
	 * @param evita the engine
	 * @param owner the product
	 * @param scope the scope
	 * @return the count of rows naming the product
	 */
	private static int rowsOf(@Nonnull Evita evita, int owner, @Nonnull Scope scope) {
		int rows = 0;
		for (final RegisteredRow row :
			VirtualPartitionInvariants.registeredRowsOf(collectionOf(evita, ENTITY_PRODUCT)).values()) {
			if (row.owner() == owner && row.scope() == scope && row.referenceName().equals(REFERENCE_GALLERY)) {
				rows++;
			}
		}
		return rows;
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
	private static RepresentativeReferenceKey media(int media, @Nonnull String role) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_GALLERY, media), new Serializable[]{role});
	}

	/**
	 * Starts the history in the fixture of single-owner partitions in two locales, prepared in the given state.
	 *
	 * @param label a label naming the engine's directories
	 * @param state the catalog state the history should continue in
	 * @return the history
	 */
	@Nonnull
	private VirtualPartitionHistory start(@Nonnull String label, @Nonnull CatalogState state) {
		return start(label, state, SingleOwnerPartitionFixture::setUpLocalizedSingleOwners);
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
