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
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.index.virtual.DifferentialEngines.CATALOG;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
	static final String ENTITY_PRODUCT = "product";
	static final String ENTITY_CATEGORY = "category";
	static final String ENTITY_MEDIA = "media";
	static final String ENTITY_BRAND = "brand";
	/**
	 * Every entity type of the fixture.
	 */
	static final Set<String> ENTITY_TYPES = Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY, ENTITY_MEDIA, ENTITY_BRAND);
	/**
	 * A reference indexed for filtering in both scopes, faceted, with a sortable attribute, a localized sortable one,
	 * a chained one and a localized compound of the first two.
	 */
	static final String REFERENCE_CATEGORIES = "categories";
	/**
	 * A reference indexed for filtering in both scopes, allowing duplicates told apart by the representative `role`.
	 */
	static final String REFERENCE_GALLERY = "gallery";
	/**
	 * A reference indexed for partitioning - its partitions are always index objects, shared by every product.
	 */
	static final String REFERENCE_BRAND = "brand";
	/**
	 * The reflection of {@link #REFERENCE_CATEGORIES} on the category, indexed for filtering in both scopes.
	 */
	static final String REFERENCE_PRODUCTS = "products";

	static final String ATTRIBUTE_CODE = "code";
	static final String ATTRIBUTE_NAME = "name";
	static final String ATTRIBUTE_ORDER = "orderInCategory";
	static final String ATTRIBUTE_LABEL = "label";
	static final String ATTRIBUTE_POSITION = "position";
	static final String ATTRIBUTE_ROLE = "role";
	static final String ATTRIBUTE_WEIGHT = "weight";
	static final String COMPOUND_LABEL_AND_ORDER = "labelAndOrder";
	static final String ATTRIBUTE_CAPTION = "caption";
	static final String COMPOUND_CAPTION_AND_WEIGHT = "captionAndWeight";

	static final String ROLE_GALLERY = "gallery";
	static final String ROLE_DETAIL = "detail";
	/**
	 * How many products the fixture starts with - product `n` references category `n` and shows media `n`, so every
	 * partition has a single owner.
	 */
	static final int PRODUCT_COUNT = 6;
	/**
	 * How many categories and media exist - enough for products and references added later.
	 */
	static final int REFERENCED_ENTITY_COUNT = 20;
	/**
	 * The brand every product belongs to.
	 */
	static final int BRAND_PK = 1;
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

	@Test
	@DisplayName("the rows written virtually are rebuilt by the replay of the write-ahead log and survive a restart")
	void shouldReplayTheWriteAheadLogIntoRows() {
		final DifferentialEngines theEngines = DifferentialEngines.writtenVirtually(
			this, "replay", ENTITY_TYPES,
			TransactionOptions.builder().checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS).build()
		);
		this.engines = theEngines;
		theEngines.addMaintainingEngineCheck(
			VirtualPartitionSingletonLifecycleTest::assertNoPartitionObjectBelowTwoOwners
		);
		theEngines.prepare(CatalogState.ALIVE, VirtualPartitionSingletonLifecycleTest::setUp);
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
	 * Defines the schema, the referenced entities and the initial products - see {@link #ENTITY_TYPES} and the
	 * reference constants. Product `n` is in category `n` and shows media `n` in the gallery role; the even products
	 * have a German name and a German label of their category reference besides the English ones.
	 *
	 * @param session the session to write in
	 */
	static void setUp(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(ENTITY_MEDIA).updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique())
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.faceted()
					.withAttribute(
						ATTRIBUTE_ORDER, Integer.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
							.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withAttribute(
						ATTRIBUTE_LABEL, String.class,
						thatIs -> thatIs.localized().sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withAttribute(
						ATTRIBUTE_POSITION, Predecessor.class,
						thatIs -> thatIs.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withSortableAttributeCompound(
						COMPOUND_LABEL_AND_ORDER,
						new AttributeElement[]{
							new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
						},
						thatIs -> thatIs.indexedInScope(Scope.LIVE, Scope.ARCHIVED)
					)
			)
			.withReferenceToEntity(
				REFERENCE_GALLERY, ENTITY_MEDIA, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.withAttribute(ATTRIBUTE_ROLE, String.class, thatIs -> thatIs.filterable().representative())
					.withAttribute(
						ATTRIBUTE_WEIGHT, Integer.class,
						thatIs -> thatIs.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withAttribute(
						ATTRIBUTE_CAPTION, String.class,
						thatIs -> thatIs.localized().sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withSortableAttributeCompound(
						COMPOUND_CAPTION_AND_WEIGHT,
						new AttributeElement[]{
							new AttributeElement(ATTRIBUTE_CAPTION, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_WEIGHT, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
						},
						thatIs -> thatIs.indexedInScope(Scope.LIVE, Scope.ARCHIVED)
					)
			)
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.withAttributesInherited(ATTRIBUTE_ORDER)
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
		for (int pk = 1; pk <= REFERENCED_ENTITY_COUNT; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
			session.upsertEntity(session.createNewEntity(ENTITY_MEDIA, pk));
		}
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			newProduct(session, pk, pk, pk % 2 == 0).upsertVia(session);
		}
	}

	/**
	 * Creates a product in category `target`, showing media `target` in the gallery role, of the fixture's brand.
	 *
	 * @param session    the session to create the entity in
	 * @param primaryKey the product primary key
	 * @param target     the category and the media the product references
	 * @param german     TRUE to give the product a German name and its category reference a German label too
	 * @return the entity builder
	 */
	@Nonnull
	static EntityBuilder newProduct(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		int target,
		boolean german
	) {
		final EntityBuilder product = session
			.createNewEntity(ENTITY_PRODUCT, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, "P" + primaryKey)
			.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Product " + primaryKey)
			.setReference(
				REFERENCE_CATEGORIES, target,
				whichIs -> {
					whichIs.setAttribute(ATTRIBUTE_ORDER, target)
						.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Label " + target)
						.setAttribute(ATTRIBUTE_POSITION, Predecessor.HEAD);
					if (german) {
						whichIs.setAttribute(ATTRIBUTE_LABEL, Locale.GERMAN, "Etikett " + target);
					}
				}
			)
			.setOrUpdateReference(
				REFERENCE_GALLERY, target,
				ref -> ROLE_GALLERY.equals(ref.getAttribute(ATTRIBUTE_ROLE)),
				whichIs -> whichIs.setAttribute(ATTRIBUTE_ROLE, ROLE_GALLERY).setAttribute(ATTRIBUTE_WEIGHT, target)
			)
			.setReference(REFERENCE_BRAND, BRAND_PK);
		if (german) {
			product.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt " + primaryKey);
		}
		return product;
	}

	/**
	 * Creates a step updating one product.
	 *
	 * @param primaryKey the product primary key
	 * @param change     the change to make
	 * @return the step
	 */
	@Nonnull
	static Consumer<EvitaSessionContract> updateProduct(int primaryKey, @Nonnull Consumer<EntityBuilder> change) {
		return session -> {
			final EntityBuilder builder = session
				.getEntity(ENTITY_PRODUCT, primaryKey, entityFetchAllContent())
				.orElseThrow()
				.openForWrite();
			change.accept(builder);
			builder.upsertVia(session);
		};
	}

	/**
	 * Fails when the collection holds an index object of a partition with a single owner, or with none, of a reference
	 * whose partitions may be virtual in the partition's scope - a catalog writing its single-owner partitions
	 * virtually represents every such partition by its registry row alone, and no checkpoint falls inside a mutation,
	 * where a partition object may be left without owners until the mutation sweeps it away.
	 *
	 * @param label      names the checkpoint in the failure message
	 * @param collection the collection of the catalog writing virtually
	 */
	static void assertNoPartitionObjectBelowTwoOwners(@Nonnull String label, @Nonnull EntityCollection collection) {
		final List<String> found = new ArrayList<>(2);
		forEachPartitionObjectOfVirtualReference(
			collection,
			(scope, partition) -> {
				if (partition.getAllPrimaryKeys().size() <= 1) {
					found.add(
						partition.getIndexKey() + " (" + partition.getPrimaryKey() + ", " +
							partition.getAllPrimaryKeys().size() + " owners)"
					);
				}
			}
		);
		if (!found.isEmpty()) {
			fail(
				label + ": collection `" + collection.getEntityType() + "` holds index objects of partitions with " +
					"fewer than two owners it writes virtually: " + found
			);
		}
	}

	/**
	 * Asserts that no collection of the committed catalog of the engine holds an index object of a partition of a
	 * reference whose partitions may be virtual - every partition of the fixture has a single owner, so the catalog
	 * writing virtually represents every one of them by its row.
	 *
	 * @param evita the engine of the catalog writing virtually
	 */
	static void assertNoPartitionObjectOfVirtualReference(@Nonnull Evita evita) {
		for (final String entityType : ENTITY_TYPES) {
			final EntityCollection collection = collectionOf(evita, entityType);
			if (collection == null) {
				continue;
			}
			final List<String> found = new ArrayList<>(2);
			forEachPartitionObjectOfVirtualReference(
				collection,
				(scope, partition) -> found.add(partition.getIndexKey() + " (" + partition.getPrimaryKey() + ")")
			);
			assertTrue(
				found.isEmpty(),
				"collection `" + entityType + "` must hold no partition object of a reference whose partitions " +
					"may be virtual, but holds " + found
			);
		}
	}

	/**
	 * Passes every index object of a partition of a reference whose partitions may be virtual in the partition's scope
	 * the collection holds to the consumer - advertised or not, so an object a write created and left without owners is
	 * found too.
	 *
	 * The browse of the indexes reads the committed state only, while a check inside an open transaction must see the
	 * state of the transaction: so the primary keys the browse lists are joined with those the type index advertises
	 * in the transaction, and each is resolved in the transaction - an index object the transaction installed is
	 * found through its advertisement, and one it retired is not found at all.
	 *
	 * @param collection the collection
	 * @param consumer   receives the scope and the index object
	 */
	private static void forEachPartitionObjectOfVirtualReference(
		@Nonnull EntityCollection collection,
		@Nonnull PartitionObjectConsumer consumer
	) {
		for (final Scope scope : Scope.values()) {
			for (final ReferenceSchemaContract referenceSchema : collection.getSchema().getReferences().values()) {
				if (!VirtualReferencePartitionOracle.isShadowed(referenceSchema, scope)) {
					continue;
				}
				final SortedSet<Integer> primaryKeys = new TreeSet<>();
				int pageNumber = 1;
				IndexBrowseResult page;
				do {
					page = collection.browseIndexes(
						new IndexBrowseCriteria(
							pageNumber++, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.MAP_ORDER,
							OrderDirection.ASC, EnumSet.of(EntityIndexType.REFERENCED_ENTITY), EnumSet.of(scope),
							Set.of(referenceSchema.getName())
						)
					);
					for (final BrowsedIndex browsed : page.indexes()) {
						primaryKeys.add(browsed.indexPrimaryKey());
					}
				} while (page.indexes().length == IndexBrowseCriteria.MAX_PAGE_SIZE);
				if (
					collection.getIndexByKeyIfExists(
						new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceSchema.getName())
					) instanceof final ReferencedTypeEntityIndex typeIndex
				) {
					typeIndex.forEachReferenceIndexPrimaryKey(primaryKeys::add);
				}
				for (final Integer primaryKey : primaryKeys) {
					// a primary key of a row without an object, or of an object the transaction retired
					final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(primaryKey);
					if (partition != null && partition.getIndexKey().type() == EntityIndexType.REFERENCED_ENTITY) {
						consumer.accept(scope, partition);
					}
				}
			}
		}
	}

	/**
	 * Returns a collection of the committed catalog of an engine.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type
	 * @return the collection, `null` when the catalog holds none of the type
	 */
	@Nullable
	static EntityCollection collectionOf(@Nonnull Evita evita, @Nonnull String entityType) {
		return (EntityCollection) evita.getCatalogInstance(CATALOG)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElse(null);
	}

	/**
	 * Returns the primary key of the registered partition of a key in the committed catalog of an engine - the
	 * partition whose registry row names the key's target and representative attribute values.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @return the partition primary key, or `-1` when the registry holds no row of the key
	 */
	static int registeredPartitionOf(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final VirtualReferencePartition partition = findRegistered(evita, entityType, scope, key);
		return partition == null ? -1 : partition.getPrimaryKey();
	}

	/**
	 * Finds the registered partition of a key in the committed catalog of an engine.
	 *
	 * @param evita      the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @return the partition, or `null` when the registry holds no row of the key
	 */
	@Nullable
	private static VirtualReferencePartition findRegistered(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final EntityCollection collection = collectionOf(evita, entityType);
		if (collection == null) {
			return null;
		}
		final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, key.referenceName())
		);
		return typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex ?
			ReferencePartitions.findRegisteredForWrite(referencedTypeIndex, key) : null;
	}

	/**
	 * Asserts that the catalog writing virtually holds the row of the key's partition naming the owner, and that the
	 * twin holds the index object of the partition under the same primary key with the owner as its only member.
	 *
	 * @param engines    the engines
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @param owner      the expected owner
	 */
	static void assertRegistered(
		@Nonnull DifferentialEngines engines,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key,
		int owner
	) {
		final VirtualReferencePartition registered = findRegistered(engines.getMaintaining(), entityType, scope, key);
		assertNotNull(registered, "the catalog writing virtually must hold the row of " + key + " in " + scope);
		assertEquals(owner, registered.ownerPrimaryKey(), "the owner of the row of " + key + " in " + scope);
		final EntityCollection twinCollection = collectionOf(engines.getReference(), entityType);
		assertNotNull(twinCollection);
		final EntityIndex twinPartition = twinCollection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, key)
		);
		assertNotNull(twinPartition, "the twin must hold the index object of " + key + " in " + scope);
		assertEquals(
			twinPartition.getPrimaryKey(), registered.getPrimaryKey(),
			"the row of " + key + " in " + scope + " must carry the primary key of the twin's index object"
		);
		assertEquals(
			List.of(owner),
			Arrays.stream(twinPartition.getAllPrimaryKeys().getArray()).boxed().toList(),
			"the twin's partition of " + key + " in " + scope + " has the owner as its only member"
		);
		final EntityCollection collection = collectionOf(engines.getMaintaining(), entityType);
		assertNotNull(collection);
		assertNull(
			collection.getIndexByPrimaryKeyIfExists(registered.getPrimaryKey()),
			"the partition of " + key + " in " + scope + " must have no index object in the catalog writing virtually"
		);
	}

	/**
	 * Asserts that the catalog writing virtually holds no row of the key's partition.
	 *
	 * @param engines    the engines
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 */
	static void assertNoRegisteredPartition(
		@Nonnull DifferentialEngines engines,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		assertNull(
			findRegistered(engines.getMaintaining(), entityType, scope, key),
			"the catalog writing virtually must hold no row of " + key + " in " + scope
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
			VirtualPartitionSingletonLifecycleTest::assertNoPartitionObjectBelowTwoOwners
		);
		theEngines.prepare(state, VirtualPartitionSingletonLifecycleTest::setUp);
		return theEngines;
	}

	/**
	 * Receives an index object of a partition with its scope.
	 */
	@FunctionalInterface
	private interface PartitionObjectConsumer {

		/**
		 * Receives one index object.
		 *
		 * @param scope     the scope of the partition
		 * @param partition the index object
		 */
		void accept(@Nonnull Scope scope, @Nonnull EntityIndex partition);
	}

}
