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
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.index.virtual.VirtualPartitionHistory.Recovery;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ATTRIBUTE_LABEL;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ATTRIBUTE_ORDER;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ATTRIBUTE_POSITION;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.BRAND_PK;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.COMPOUND_LABEL_AND_ORDER;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_BRAND;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_CATEGORY;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_PRODUCT;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_BRAND;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_GALLERY;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_PRODUCTS;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ROLE_GALLERY;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.SHARED_CATEGORY;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.assertNoPartitionObjectOfVirtualReference;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.category;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.findCollectionOf;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.newProduct;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.openProductForWrite;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.product;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.registryOf;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.updateProduct;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.withoutTheMandatoryBrand;
import static io.evitadb.index.virtual.SortValueAndChainFixture.ATTRIBUTE_CATEGORY_ORDER;
import static io.evitadb.index.virtual.SortValueAndChainFixture.ATTRIBUTE_PRODUCT_ORDER;
import static io.evitadb.index.virtual.SortValueAndChainFixture.ATTRIBUTE_SPAN;
import static io.evitadb.index.virtual.SortValueAndChainFixture.CZECH;
import static io.evitadb.index.virtual.SortValueAndChainFixture.DECIMAL_PLACES;
import static io.evitadb.index.virtual.SortValueAndChainFixture.fillReference;
import static io.evitadb.index.virtual.SortValueAndChainFixture.spanOf;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives reference partitions across the boundary of their owner count in a catalog writing its single-owner partitions
 * as registry rows: a partition gaining its second owner (1 -> 2) is promoted to an index object populated with its
 * first owner, and a partition left with one owner of two (2 -> 1) is demoted back to a row its remaining owner's
 * values are extracted into, its index object retired. Every history runs in a catalog warming up and in a live one.
 *
 * {@link VirtualPartitionHistory} checks the catalog at every moment of a history - inside the session after every
 * step, after a caught failure, after the session closes, after a restart and after a crash recovery. The body oracle
 * holds every partition to the entity bodies: a promoted index object must hold exactly its owners and, per owner, the
 * sort values and chain predecessors the owner's body declares - so a promotion that populates the first owner wrongly
 * is found without a second representation of the history to compare it with - and a demoted row the values its
 * remaining owner's body declares. The identity ledger holds the primary key of every partition across 1 -> 2 -> 1:
 * the index object of a promotion carries the primary key of the row it replaces, and the row of a demotion the primary
 * key of the object it retires. The invariants refuse an index object below two owners and a partition the
 * reduced-index membership knows that is neither a row nor an index object, and the committed catalog must store what a
 * history-free rebuild stores. On top of that every test asserts what its history must have produced - the index object
 * with its owners and no row after a promotion, the row and no index object after a demotion, the primary key the
 * partition kept - so that no test passes by not exercising what it names.
 *
 * The histories are those that decide whether the promoted object or the demoted row is right: every stage of a
 * partition going from no owner to two and back; a promotion and a departure reverted by a failing root mutation; the
 * owners leaving and entering one partition in one root mutation in every order; the first owner read by a promotion
 * while the same root mutation changes it - its references staged in another executor, its locales pending - or read
 * from storage after a restart; every sortable type, a range compound updated incrementally and both chain types; the
 * remaining owner's values written by another executor of the root mutation before and after the demotion, a whole
 * churn inside one open transaction, the locale data of a departing owner, and the replay of the write-ahead log. One
 * history pins #1691: a row demoted from a partition whose owners shared a `ReferencedEntityPredecessor` record lacks
 * the predecessor its remaining owner's body declares, which is why the body oracle leaves that chain type out.
 *
 * A failing root mutation fails at the consistency check that ends it (see
 * {@link SingleOwnerPartitionFixture#withoutTheMandatoryBrand}): after the owners joined or left the partitions, but
 * before the finalization that demotes - so a failure reverts the departure of an owner, never a demotion.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Single-owner partitions promoted to index objects and demoted to registry rows")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class VirtualPartitionPromotionLifecycleTest implements EvitaTestSupport {
	/**
	 * A product none of the fixtures creates.
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
	 * The reference of the note fixture through which a category gives a product a localized note.
	 */
	private static final String REFERENCE_NOTE_CATEGORY = "noteCategory";
	/**
	 * The reflection of {@link #REFERENCE_NOTE_CATEGORY} on the category.
	 */
	private static final String REFERENCE_NOTE_PRODUCTS = "noteProducts";
	/**
	 * The main category of a product, reflected on the category as {@link #REFERENCE_MAIN_PRODUCTS}.
	 */
	private static final String REFERENCE_MAIN_CATEGORY = "mainCategory";
	/**
	 * The reflection of {@link #REFERENCE_MAIN_CATEGORY} on the category.
	 */
	private static final String REFERENCE_MAIN_PRODUCTS = "mainProducts";
	/**
	 * The localized attribute of {@link #REFERENCE_NOTE_CATEGORY} and {@link #REFERENCE_MAIN_CATEGORY}.
	 */
	private static final String ATTRIBUTE_NOTE = "note";
	/**
	 * The category both products of the note fixture share.
	 */
	private static final int NOTE_SHARED_CATEGORY = 1;
	/**
	 * The category of the note fixture only {@link #LEAVING_OWNER} references.
	 */
	private static final int NOTE_OWN_CATEGORY = 2;
	/**
	 * The owner of the shared category of the note fixture that leaves it.
	 */
	private static final int LEAVING_OWNER = 1;
	/**
	 * The owner of the shared category of the note fixture that stays in it and gains German.
	 */
	private static final int REMAINING_OWNER = 2;
	/**
	 * The second category the products of the fixture of two shared categories share - the first is
	 * {@link SingleOwnerPartitionFixture#SHARED_CATEGORY}.
	 */
	private static final int SECOND_SHARED_CATEGORY = 101;
	/**
	 * A localized entity attribute of the brand fixture - the one value that keeps a product in its locale.
	 */
	private static final String ATTRIBUTE_TITLE = "title";
	/**
	 * Localized, on the brand reference of the brand fixture.
	 */
	private static final String ATTRIBUTE_BRAND_LABEL = "brandLabel";
	/**
	 * On the brand reference of the brand fixture.
	 */
	private static final String ATTRIBUTE_PRIORITY = "priority";
	/**
	 * A localized compound of the brand reference of the brand fixture: the brand label, then the priority.
	 */
	private static final String COMPOUND_BRAND_LABEL_AND_PRIORITY = "brandLabelAndPriority";

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
	@DisplayName("a partition goes from no owner to two and back under one primary key")
	void shouldPromoteAndDemoteAPartitionThroughEveryStage(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("everyStage", state);

		// 0 -> 1
		theHistory.step(
			"the first owner enters",
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true).upsertVia(session)
		);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT);
		final int reflectedPartition = theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(1), 1);

		// 1 -> 2, in the product's partition and in the reflected one of the joining product
		theHistory.step("the second owner joins", joinCategory(1, FREE_TARGET));
		assertEquals(
			partition,
			theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), 1, NEW_PRODUCT),
			"the promoted partition keeps the primary key of its row"
		);
		assertEquals(
			reflectedPartition, theHistory.assertIndexObject(ENTITY_CATEGORY, Scope.LIVE, product(1), 1, FREE_TARGET),
			"the reflected promoted partition keeps the primary key of its row"
		);

		// 2 -> 1
		theHistory.step(
			"the second owner leaves", updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_TARGET))
		);
		assertEquals(
			partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), NEW_PRODUCT),
			"the demoted partition keeps its primary key in its row"
		);
		assertEquals(
			reflectedPartition, theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(1), 1),
			"the reflected demoted partition keeps its primary key in its row"
		);

		// 1 -> 0
		theHistory.step(
			"the first owner leaves",
			updateProduct(NEW_PRODUCT, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_TARGET))
		);
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET));
		assertNull(partitionObjectOf(theHistory.getEvita(), ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET)));
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a promotion in a mutation failing at its end is reverted, the row and the reflected row as they were")
	void shouldRevertAPromotionOfAMutationFailingAtItsEnd(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"failedPromotion", state, SingleOwnerPartitionFixture::setUpLocalizedSingleOwnersWithMandatoryBrand
		);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(2), 2);
		final int reflectedPartition = theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(1), 1);

		// 1 -> 2, in the product's partition and in the reflected one of the joining product
		theHistory.failingStep(
			"product 1 joins category 2 and leaves its mandatory brand",
			withoutTheMandatoryBrand(session -> joined(openProductForWrite(session, 1), 2)),
			ReferenceCardinalityViolatedException.class
		);
		assertEquals(
			partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(2), 2),
			"the failed promotion must have left the row as it was"
		);
		assertEquals(
			reflectedPartition, theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(1), 1),
			"the failed promotion must have left the reflected row as it was"
		);

		theHistory.step("product 1 joins category 2", joinCategory(1, 2));
		assertEquals(partition, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(2), 1, 2));
		assertEquals(reflectedPartition, theHistory.assertIndexObject(ENTITY_CATEGORY, Scope.LIVE, product(1), 1, 2));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner leaving an index object in a mutation failing at its end stays in it")
	void shouldRevertTheDepartureFromAnIndexObjectOfAMutationFailingAtItsEnd(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"failedDeparture", state, SingleOwnerPartitionFixture::setUpLocalizedSingleOwnersWithMandatoryBrand
		);
		theHistory.step("product 1 joins category 2", joinCategory(1, 2));
		final int partition = theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(2), 1, 2);

		// the failure precedes the finalization, so it reverts the departure of the owner from the index object - the
		// demotion it would have led to is never reached
		theHistory.failingStep(
			"product 1 leaves category 2 and its mandatory brand",
			withoutTheMandatoryBrand(
				session -> openProductForWrite(session, 1).removeReference(REFERENCE_CATEGORIES, 2)
			),
			ReferenceCardinalityViolatedException.class
		);
		assertEquals(
			partition, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(2), 1, 2),
			"the failed departure must have left the index object as it was"
		);

		theHistory.step(
			"product 1 leaves category 2", updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, 2))
		);
		assertEquals(partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(2), 2));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a second owner leaving and entering again in one mutation keeps the partition an index object")
	void shouldKeepAPartitionPromotedWhenItsSecondOwnerLeavesAndEntersAgainInOneMutation(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("leaveAndEnterAgain", state);
		theHistory.step("product 1 joins category 2", joinCategory(1, 2));
		final int partition = theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(2), 1, 2);

		theHistory.step(
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

		assertEquals(
			partition, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(2), 1, 2),
			"a partition with two owners when the mutation finishes stays the index object it was"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner leaving a shared partition as another enters it in one root mutation keeps it an object")
	void shouldKeepAPartitionSharedWhenAnotherOwnerEntersItAsOneLeaves(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"anotherOwnerEnters", state, SingleOwnerPartitionFixture::setUpWithReflectedProducts
		);
		// the reflected partition of product 1 on the category side is owned by category 1 and the shared one
		final int partition = theHistory.assertIndexObject(
			ENTITY_CATEGORY, Scope.LIVE, product(1), 1, SHARED_CATEGORY
		);

		// the reflected mutations follow the order of the product's: the shared category leaves the partition first,
		// leaving it with one owner, and the free one enters it next - so when the root mutation finishes the partition
		// has two owners again and must not be demoted
		theHistory.step(
			"the shared category leaves the partition and a free one enters it in one root mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 1, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_CATEGORIES, SHARED_CATEGORY)),
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
		final VirtualPartitionHistory theHistory = start("installAndRetire", state);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(2), 2);

		// the reflected mutations follow the order of the category's: product 1 joins the partition product 2 owns
		// alone, promoting it, and product 2 leaves it next, so the root mutation demotes it to product 1 when it
		// finishes
		theHistory.step(
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
			partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(2), 1),
			"the partition promoted and demoted by the root mutation is the row of the remaining owner, under its " +
				"primary key"
		);
		theHistory.assertIndexObject(ENTITY_CATEGORY, Scope.LIVE, product(1), 1, 2);

		theHistory.restart();
		assertEquals(partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(2), 1));
		theHistory.assertIndexObject(ENTITY_CATEGORY, Scope.LIVE, product(1), 1, 2);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a shared partition both owners leave in one root mutation leaves the reduced-index membership too")
	void shouldDropAPartitionBothOwnersLeaveInOneRootMutationFromTheMembership(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("bothOwnersLeave", state);
		theHistory.step("product 1 joins category 3", joinCategory(1, 3));
		final int partition = theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(3), 1, 3);
		assertCovered(theHistory.getEvita(), partition, true, "the shared partition before both owners leave");

		// the reflected removals: product 1 leaves the shared partition (2 -> 1), product 3 leaves it empty (1 -> 0),
		// which retires its index object while the root mutation still runs - and nothing enters it again
		theHistory.step(
			"category 3 stops listing products 1 and 3 in one root mutation",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, 3, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, 1)),
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, 3))
				)
			)
		);

		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, category(3));
		assertNull(
			partitionObjectOf(theHistory.getEvita(), ENTITY_PRODUCT, Scope.LIVE, category(3)),
			"the partition both owners left must have no index object"
		);
		assertCovered(theHistory.getEvita(), partition, false, "the emptied partition");

		theHistory.restart();
		assertCovered(theHistory.getEvita(), partition, false, "the emptied partition after a reload");
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a partition emptied and entered again before its demotion keeps its row and loses its index object")
	void shouldRetireTheIndexObjectOfAPartitionEnteredAgainBeforeItsDemotion(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("enteredBeforeDemotion", state);
		theHistory.step("product 1 joins category 3", joinCategory(1, 3));
		final int partition = theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(3), 1, 3);

		// the reflected removals run before the reflected insertion: product 1 leaves the shared partition (2 -> 1),
		// product 3 leaves it empty (1 -> 0), which retires its index object and reserves its primary key, and
		// product 2 enters it as its row under that primary key (0 -> 1) - so the demotion of the partition product 1
		// left finds the row in place and nothing left to retire
		theHistory.step(
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

		assertEquals(
			partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(3), 2),
			"the partition entered again within the root mutation keeps its primary key as the row of its new owner"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("one root mutation promotes partitions of several first owners, one of them entering in the same root")
	void shouldPromotePartitionsInTheRootMutationTheirFirstOwnerEntersIn(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("sameRoot", state);
		final int firstCategory = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(1), 1);
		final int secondCategory = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(2), 2);

		// the new product enters two free categories: on the category side the first of them enters the product's
		// reflected partition and the second joins it within the same root mutation, so the promotion reads a first
		// owner that the root mutation is mutating
		theHistory.step(
			"a new product enters two free categories",
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true)
				.setReference(
					REFERENCE_CATEGORIES, SECOND_FREE_TARGET,
					whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, SECOND_FREE_TARGET)
						.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Label " + SECOND_FREE_TARGET)
				)
				.upsertVia(session)
		);
		theHistory.assertIndexObject(
			ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), FREE_TARGET, SECOND_FREE_TARGET
		);

		// another new product joins two partitions of two different first owners at once
		theHistory.step(
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
		assertEquals(
			firstCategory, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(1), 1, NEW_PRODUCT + 1)
		);
		assertEquals(
			secondCategory, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(2), 2, NEW_PRODUCT + 1)
		);
		theHistory.assertIndexObject(ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT + 1), 1, 2);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a promoted partition keeps being written by both owners and is demoted to the remaining one")
	void shouldKeepWritingAPromotedPartitionAndDemoteIt(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("continued", state);
		theHistory.step("product 3 joins category 4", joinCategory(3, 4));
		final int partition = theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(4), 3, 4);

		theHistory.step(
			"the first owner changes its order",
			updateProduct(
				4,
				it -> it.setReference(REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 400))
			)
		);
		theHistory.step(
			"the second owner gains German through its reference",
			updateProduct(
				3,
				it -> it.setReference(
					REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_LABEL, Locale.GERMAN, "Vier")
				)
			)
		);
		theHistory.step(
			"the first owner loses German",
			updateProduct(
				4,
				it -> it.removeAttribute(SingleOwnerPartitionFixture.ATTRIBUTE_NAME, Locale.GERMAN)
					.setReference(
						REFERENCE_CATEGORIES, 4, ref -> ref.removeAttribute(ATTRIBUTE_LABEL, Locale.GERMAN)
					)
			)
		);
		assertEquals(partition, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(4), 3, 4));

		theHistory.step(
			"the first owner leaves", updateProduct(4, it -> it.removeReference(REFERENCE_CATEGORIES, 4))
		);
		assertEquals(partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 3));
		theHistory.step(
			"the remaining owner changes its label",
			updateProduct(
				3,
				it -> it.setReference(
					REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Four")
				)
			)
		);
		assertEquals(partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 3));
		// product 3 stays in categories 3 and 4, so its reflected partition keeps both of them
		theHistory.assertIndexObject(ENTITY_CATEGORY, Scope.LIVE, product(3), 3, 4);
	}

	@Test
	@DisplayName("promotions and demotions are rebuilt by the replay of the write-ahead log and survive a restart")
	void shouldReplayPromotionsAndDemotionsFromTheWriteAheadLog() {
		final VirtualPartitionHistory theHistory = new VirtualPartitionHistory(
			this, "replay", TransactionOptions.builder().checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS).build()
		);
		this.history = theHistory;
		theHistory.prepare(CatalogState.ALIVE, SingleOwnerPartitionFixture::setUpLocalizedSingleOwners);
		// going live is deferred like any other round; the clean close publishes it
		theHistory.restart();
		final int secondCategory = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(2), 2);
		final int fourthCategory = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4);

		theHistory.step("product 1 joins category 2", joinCategory(1, 2));
		theHistory.step("product 3 joins category 4", joinCategory(3, 4));
		theHistory.step(
			"the first owner of category 4 leaves it",
			updateProduct(4, it -> it.removeReference(REFERENCE_CATEGORIES, 4))
		);
		theHistory.step(
			"a new product enters two free categories",
			session -> newProduct(session, NEW_PRODUCT, FREE_TARGET, true)
				.setReference(
					REFERENCE_CATEGORIES, SECOND_FREE_TARGET,
					whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, SECOND_FREE_TARGET)
				)
				.upsertVia(session)
		);

		// the ledger has observed every partition since the catalog was set up; the recovery replays the tail of the
		// write-ahead log, which mints the partitions the tail created anew - the partitions it promoted or demoted
		// from a published row keep their primary key
		final PartitionIdentityLedger ledger = theHistory.getLedger();
		ledger.declareReplayed(ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT));
		ledger.declareReplayed(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET));
		ledger.declareReplayed(ENTITY_PRODUCT, Scope.LIVE, category(SECOND_FREE_TARGET));
		ledger.declareReplayed(
			ENTITY_PRODUCT, Scope.LIVE,
			new RepresentativeReferenceKey(
				new ReferenceKey(REFERENCE_GALLERY, FREE_TARGET), new Serializable[]{ROLE_GALLERY}
			)
		);
		final Recovery recovered = theHistory.crashAndRecover();
		assertTrue(
			recovered.loadedVersion() > recovered.imageVersion(),
			"the recovery must have replayed the tail of the write-ahead log"
		);
		assertEquals(
			secondCategory, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(2), 1, 2),
			"the replayed promotion keeps the primary key of the published row"
		);
		assertEquals(
			fourthCategory, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 3),
			"the replayed promotion and demotion keep the primary key of the published row"
		);
		theHistory.assertIndexObject(
			ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), FREE_TARGET, SECOND_FREE_TARGET
		);

		theHistory.step(
			"the recovered catalog demotes",
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, 2))
		);
		theHistory.restart();
		assertEquals(secondCategory, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(2), 2));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("partitions of every sort type and two compounds are promoted, demoted, and promoted after a restart")
	void shouldPromoteAndDemoteEverySortType(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"sortTypes", state, SortValueAndChainFixture::setUpTypes
		);
		// the fixture's shared category was promoted by its second owner while the fixture was written
		final int sharedPartition = theHistory.assertIndexObject(
			ENTITY_PRODUCT, Scope.LIVE, category(SortValueAndChainFixture.SHARED_CATEGORY), 1, 2
		);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(1), 1);

		theHistory.step(
			"product 3 joins category 1 with values of every type",
			updateProduct(3, it -> it.setReference(REFERENCE_CATEGORIES, 1, ref -> fillReference(ref, 7)))
		);
		assertEquals(partition, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(1), 1, 3));

		theHistory.step(
			"product 1 leaves the shared category",
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, SortValueAndChainFixture.SHARED_CATEGORY))
		);
		assertEquals(
			sharedPartition,
			theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(SortValueAndChainFixture.SHARED_CATEGORY), 2)
		);
		theHistory.step(
			"product 1 leaves category 1",
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, 1))
		);
		assertEquals(partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(1), 3));
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());

		// a first owner the engine loaded is read from storage alone
		theHistory.restart();
		final int fourthCategory = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), 4);
		theHistory.step(
			"product 2 joins category 4 after a restart",
			updateProduct(2, it -> it.setReference(REFERENCE_CATEGORIES, 4, ref -> fillReference(ref, 6)))
		);
		assertEquals(fourthCategory, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(4), 2, 4));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a range compound element updated incrementally is promoted at the scale of the schema")
	void shouldPromoteAnIncrementallyUpdatedRangeCompoundElement(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"ranges", state, SortValueAndChainFixture::setUpTypes
		);
		assertNotEquals(
			DECIMAL_PLACES, spanOf(1).getEffectiveRetainedDecimalPlaces(),
			"the premise: the fixture writes ranges at another scale than the schema indexes them at"
		);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(1), 1);

		// the row gets the changed range element from the mutation, the promoted object from the stored reference -
		// the body oracle holds both to the body at the schema scale
		theHistory.step(
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
		theHistory.step(
			"product 3 joins category 1",
			updateProduct(3, it -> it.setReference(REFERENCE_CATEGORIES, 1, ref -> fillReference(ref, 7)))
		);
		assertEquals(partition, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(1), 1, 3));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("partitions with both chain types are promoted from their first owner and demoted to it")
	void shouldPromoteAndDemoteChains(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"chains", state, SortValueAndChainFixture::setUpChains
		);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(1), 1);
		final int reflectedPartition = theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(2), 2);

		// the joining owner writes no chain value: a chain keyed by the referenced entity would get the same element
		// from both owners, which a partition of either form cannot hold twice
		theHistory.step(
			"product 2 joins category 1",
			updateProduct(
				2,
				it -> it.setReference(
					REFERENCE_CATEGORIES, 1,
					ref -> ref.setAttribute(SortValueAndChainFixture.ATTRIBUTE_ORDER, 20)
				)
			)
		);
		assertEquals(partition, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(1), 1, 2));
		assertEquals(
			reflectedPartition, theHistory.assertIndexObject(ENTITY_CATEGORY, Scope.LIVE, product(2), 1, 2)
		);

		theHistory.step(
			"product 2 leaves category 1",
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, 1))
		);
		assertEquals(partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(1), 1));
		assertEquals(reflectedPartition, theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(2), 2));
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a promotion reads a first owner whose references the same root mutation stages in another executor")
	void shouldPromoteAFirstOwnerStagedInTheSameRootMutation(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"stagedFirstOwner", state, SortValueAndChainFixture::setUpChains
		);

		// one product upsert adds two new categories: each category gets the reflected reference in an executor of
		// its own within the root mutation, so the second one promotes the product's partition on the category side
		// while the first one's reference exists only in its executor
		theHistory.step(
			"a new product joins two categories nobody references in one upsert",
			session -> session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, NEW_PRODUCT)
					.setReference(
						REFERENCE_CATEGORIES, 4,
						ref -> ref.setAttribute(SortValueAndChainFixture.ATTRIBUTE_ORDER, 40)
							.setAttribute(ATTRIBUTE_PRODUCT_ORDER, new Predecessor(3))
							.setAttribute(ATTRIBUTE_CATEGORY_ORDER, new ReferencedEntityPredecessor(3))
					)
					.setReference(
						REFERENCE_CATEGORIES, 5,
						ref -> ref.setAttribute(SortValueAndChainFixture.ATTRIBUTE_ORDER, 50)
							.setAttribute(ATTRIBUTE_CATEGORY_ORDER, new ReferencedEntityPredecessor(4))
					)
			)
		);

		theHistory.assertIndexObject(ENTITY_CATEGORY, Scope.LIVE, product(NEW_PRODUCT), 4, 5);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(4), NEW_PRODUCT);
		theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(5), NEW_PRODUCT);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a demotion before the remaining owner's locale finalization fills the row it moves, kept by a reload")
	void shouldDemoteBeforeTheLocaleFinalizationOfTheRemainingOwner(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"demotionFirst", state, VirtualPartitionPromotionLifecycleTest::setUpLocalesWithNoteCategory
		);
		final int partition = theHistory.assertIndexObject(
			ENTITY_PRODUCT, Scope.LIVE, category(NOTE_SHARED_CATEGORY), LEAVING_OWNER, REMAINING_OWNER
		);

		// the leaving owner's executor demotes the partition when it applies its changes, and only then the remaining
		// owner's finalization writes its German compound into the row
		theHistory.step(
			"the category drops the leaving owner, then gives the remaining one a German note",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, NOTE_SHARED_CATEGORY, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, LEAVING_OWNER)),
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_NOTE_PRODUCTS, REMAINING_OWNER, -1)),
					new ReferenceAttributeMutation(
						new ReferenceKey(REFERENCE_NOTE_PRODUCTS, REMAINING_OWNER, -1),
						new UpsertAttributeMutation(ATTRIBUTE_NOTE, Locale.GERMAN, "Notiz")
					)
				)
			)
		);

		assertEquals(
			partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(NOTE_SHARED_CATEGORY), REMAINING_OWNER)
		);
		assertSortValue(theHistory.getEvita(), REFERENCE_CATEGORIES, partition, labelAndOrderKey(Locale.GERMAN), true);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());

		// the row the finalization moved is what the catalog persisted
		theHistory.restart();
		assertSortValue(theHistory.getEvita(), REFERENCE_CATEGORIES, partition, labelAndOrderKey(Locale.GERMAN), true);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a demotion after the remaining owner's locale finalization extracts what it moved, kept by a reload")
	void shouldDemoteAfterTheLocaleFinalizationOfTheRemainingOwner(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"demotionLast", state, VirtualPartitionPromotionLifecycleTest::setUpLocalesWithNoteCategory
		);
		final int partition = theHistory.assertIndexObject(
			ENTITY_PRODUCT, Scope.LIVE, category(NOTE_SHARED_CATEGORY), LEAVING_OWNER, REMAINING_OWNER
		);

		// the remaining owner's finalization writes its German compound into the index object, and only then the
		// leaving owner's executor demotes the partition, extracting the compound into the row
		theHistory.step(
			"the category gives the remaining owner a German note, then drops the leaving owner",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, NOTE_SHARED_CATEGORY, EntityExistence.MUST_EXIST,
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_NOTE_PRODUCTS, REMAINING_OWNER, -1)),
					new ReferenceAttributeMutation(
						new ReferenceKey(REFERENCE_NOTE_PRODUCTS, REMAINING_OWNER, -1),
						new UpsertAttributeMutation(ATTRIBUTE_NOTE, Locale.GERMAN, "Notiz")
					),
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, LEAVING_OWNER))
				)
			)
		);

		assertEquals(
			partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(NOTE_SHARED_CATEGORY), REMAINING_OWNER)
		);
		assertSortValue(theHistory.getEvita(), REFERENCE_CATEGORIES, partition, labelAndOrderKey(Locale.GERMAN), true);
		assertNoPartitionObjectOfVirtualReference(theHistory.getEvita());

		// the values the demotion extracted are what the catalog persisted
		theHistory.restart();
		assertSortValue(theHistory.getEvita(), REFERENCE_CATEGORIES, partition, labelAndOrderKey(Locale.GERMAN), true);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a promotion reading a first owner whose new locale is pending writes the locales it had before")
	void shouldPromoteFromAFirstOwnerWithAPendingLocale(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"pendingLocale", state, VirtualPartitionPromotionLifecycleTest::setUpLocalesWithNoteCategory
		);
		final int partition = theHistory.assertRow(
			ENTITY_PRODUCT, Scope.LIVE, category(NOTE_OWN_CATEGORY), LEAVING_OWNER
		);

		// the first owner gains German through its new note first, so the promotion that follows reads it with the
		// locale pending, and its own finalization moves the promoted partition to German afterwards
		theHistory.step(
			"the category gives its only owner a German note, then lists another product",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, NOTE_OWN_CATEGORY, EntityExistence.MUST_EXIST,
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
			theHistory.assertIndexObject(
				ENTITY_PRODUCT, Scope.LIVE, category(NOTE_OWN_CATEGORY), LEAVING_OWNER, REMAINING_OWNER
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a promotion reading a first owner the root mutation changes afterwards is moved by its finalization")
	void shouldPromoteFromAFirstOwnerTheRootMutationChangesAfterwards(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"localeAfterwards", state, VirtualPartitionPromotionLifecycleTest::setUpLocalesWithNoteCategory
		);
		final int partition = theHistory.assertRow(
			ENTITY_PRODUCT, Scope.LIVE, category(NOTE_OWN_CATEGORY), LEAVING_OWNER
		);

		theHistory.step(
			"the category lists another product, then gives its first owner a German note",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, NOTE_OWN_CATEGORY, EntityExistence.MUST_EXIST,
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
			theHistory.assertIndexObject(
				ENTITY_PRODUCT, Scope.LIVE, category(NOTE_OWN_CATEGORY), LEAVING_OWNER, REMAINING_OWNER
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a promotion reading a first owner that loses a locale in the same root mutation drops it afterwards")
	void shouldPromoteFromAFirstOwnerLosingALocaleInTheSameRootMutation(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"removedLocale", state, VirtualPartitionPromotionLifecycleTest::setUpLocalesWithMainCategory
		);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(1), 1);

		// the category drops its main product - product 1, which loses its only Czech value with it - before it gets
		// product 2, which joins the partition product 1 owns alone; the collector dispatches the reflected changes in
		// the order the category lists them, so product 1's mutation has run, but not applied its changes, when the
		// partition is promoted - the promotion writes the Czech locale product 1 had, its finalization removes it
		theHistory.step(
			"category 1 drops its main product 1 and gets product 2",
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_CATEGORY, 1, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(REFERENCE_MAIN_PRODUCTS, 1),
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_PRODUCTS, 2))
				)
			)
		);

		assertEquals(partition, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(1), 1, 2));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a promotion reading a first owner gaining a locale in its root mutation leaves it to finalization")
	void shouldPromoteFromAFirstOwnerGainingALocaleInTheSameRootMutation(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"addedLocale", state, VirtualPartitionPromotionLifecycleTest::setUpLocalesWithMainCategory
		);
		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(3), 3);

		// the category makes product 3 its main product with a German note - product 3's first German value - before
		// it gets product 2, which joins the partition product 3 owns alone; the builder lists the main product
		// reference first, as it orders references by name
		theHistory.step(
			"category 3 gets main product 3 with a German note and gets product 2",
			session -> session.getEntity(ENTITY_CATEGORY, 3, referenceContentAll())
				.orElseThrow()
				.openForWrite()
				.setReference(
					REFERENCE_MAIN_PRODUCTS, 3, ref -> ref.setAttribute(ATTRIBUTE_NOTE, Locale.GERMAN, "Haupt")
				)
				.setReference(REFERENCE_PRODUCTS, 2)
				.upsertVia(session)
		);

		assertEquals(partition, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(3), 2, 3));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a transaction goes on after promotions and a demotion and commits, kept by a reload")
	void shouldGoOnAndCommitAfterAPromotion(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"goOn", state, SortValueAndChainFixture::setUpTypes
		);
		final int firstCategory = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(1), 1);
		final int thirdCategory = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(3), 3);

		theHistory.transaction(
			"two partitions are promoted while their owners keep writing, and one of them is demoted",
			List.of(
				updateProduct(2, it -> it.setReference(REFERENCE_CATEGORIES, 1, ref -> fillReference(ref, 6))),
				updateProduct(1, it -> it.updateReference(REFERENCE_CATEGORIES, 1, ref -> fillReference(ref, 8))),
				updateProduct(4, it -> it.setReference(REFERENCE_CATEGORIES, 3, ref -> fillReference(ref, 7))),
				updateProduct(3, it -> it.removeReference(REFERENCE_CATEGORIES, 3))
			)
		);

		assertEquals(firstCategory, theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, category(1), 1, 2));
		assertEquals(thirdCategory, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(3), 4));
		theHistory.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a demotion extracts what another executor wrote before it, the mirror takes what it fans out after")
	void shouldDemoteAcrossExecutors(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"acrossExecutors", state, VirtualPartitionPromotionLifecycleTest::setUpLocalesOfTwoSharedCategories
		);
		final int firstShared = theHistory.assertIndexObject(
			ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), 1, 2
		);
		final int secondShared = theHistory.assertIndexObject(
			ENTITY_PRODUCT, Scope.LIVE, category(SECOND_SHARED_CATEGORY), 1, 2
		);

		// a category mutation spawns one product mutation per reflected reference it changes, each run by an executor
		// of its own within the root mutation. The departing owner's executor demotes the partition when it applies its
		// changes; the remaining owner's executor writes its label in the new locale into the partition while the
		// mutations are dispatched - before any executor applies its changes, so before the demotion, which has to
		// extract it - and fans the new locale out into the partition's localized compound when it applies its own
		// changes. The collector registers the reflected removal before the reflected attribute change whatever order
		// the category lists them in, so the departing owner's executor applies its changes first and the fan-out
		// follows the demotion, which writes the row; the two steps list the mutations in both orders
		theHistory.step(
			"product 2 leaves the first shared category, product 1 gets a Czech label there",
			demotionStep(SHARED_CATEGORY, 2, 1, CZECH, true)
		);
		assertEquals(firstShared, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), 1));
		theHistory.step(
			"product 2 gets a German label in the second shared category, product 1 leaves it",
			demotionStep(SECOND_SHARED_CATEGORY, 1, 2, Locale.GERMAN, false)
		);
		assertEquals(
			secondShared, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(SECOND_SHARED_CATEGORY), 2)
		);

		final Evita evita = theHistory.getEvita();
		assertSortValue(evita, REFERENCE_CATEGORIES, firstShared, labelKey(CZECH), true);
		assertSortValue(evita, REFERENCE_CATEGORIES, secondShared, labelKey(Locale.GERMAN), true);
		assertSortValue(evita, REFERENCE_CATEGORIES, firstShared, labelAndOrderKey(CZECH), true);
		assertSortValue(evita, REFERENCE_CATEGORIES, secondShared, labelAndOrderKey(Locale.GERMAN), true);
		theHistory.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a churn of promotions and demotions inside one open transaction is checked step by step")
	void shouldDemoteInsideAnOpenTransaction(CatalogState state) {
		final VirtualPartitionHistory theHistory = start("transaction", state, SingleOwnerPartitionFixture::setUp);

		theHistory.transaction(
			"two products share a free category, one leaves, the other changes its order there, a third joins and " +
				"leaves again",
			List.of(
				updateProduct(1, it -> it.setReference(REFERENCE_CATEGORIES, FREE_TARGET)),
				updateProduct(
					2,
					it -> it.setReference(
						REFERENCE_CATEGORIES, FREE_TARGET, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 2)
					)
				),
				updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_TARGET)),
				updateProduct(
					2,
					it -> it.updateReference(
						REFERENCE_CATEGORIES, FREE_TARGET, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 7)
					)
				),
				updateProduct(3, it -> it.setReference(REFERENCE_CATEGORIES, FREE_TARGET)),
				updateProduct(3, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_TARGET))
			)
		);

		final int partition = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(FREE_TARGET), 2);
		assertEquals(
			7, sortValueOf(theHistory.getEvita(), REFERENCE_CATEGORIES, partition, orderKey()),
			"the order the remaining owner set after the first demotion, extracted again by the second one"
		);
		theHistory.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("chains are extracted by the owner's record or the referenced entity's, on both sides of a reflection")
	void shouldExtractChainsByTheRecordTheirTypeIsIndexedUnder(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"extractedChains", state, VirtualPartitionPromotionLifecycleTest::setUpChainsOfASharedCategory
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
		theHistory.step(
			"product 2 leaves the shared category",
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, SHARED_CATEGORY))
		);
		final int sharedCategory = theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, category(SHARED_CATEGORY), 1);
		final VirtualReferencePartitionIndex productRegistry = requireRegistry(
			theHistory.getEvita(), ENTITY_PRODUCT, REFERENCE_CATEGORIES
		);
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
		theHistory.step(
			"product 1 stops referencing its own category, which leaves its reflected partition",
			updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, 1))
		);
		final int productPartition = theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(1), SHARED_CATEGORY);
		final VirtualReferencePartitionIndex categoryRegistry = requireRegistry(
			theHistory.getEvita(), ENTITY_CATEGORY, REFERENCE_PRODUCTS
		);
		assertTrue(
			categoryRegistry.getChainPredecessor(productOrderOfProducts, productPartition).isPresent(),
			"the inverted `ReferencedEntityPredecessor` must have been extracted by the referenced entity's record"
		);
		assertTrue(
			categoryRegistry.getChainPredecessor(categoryOrderOfProducts, productPartition).isPresent(),
			"the inverted `Predecessor` must have been extracted by the owner's record"
		);
		theHistory.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("owners dropping their last locale value together with the reference leave the registry exact")
	void shouldStayExactWhenOwnersDropTheirLastLocaleValueWithTheReference(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"departedLocale", state, VirtualPartitionPromotionLifecycleTest::setUpBrandWithLocalizedCompound
		);
		final RepresentativeReferenceKey brand = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_BRAND, BRAND_PK)
		);
		final int partition = theHistory.assertIndexObject(ENTITY_PRODUCT, Scope.LIVE, brand, 1, 2);

		// the departing owner's locale and reference go together: the partition must drop its locale data, and the
		// demotion must read only the remaining owner's - a compound with an absent localized element
		theHistory.step("product 1 drops its title and its brand in one mutation", dropTitleAndBrand(1));
		assertEquals(partition, theHistory.assertRow(ENTITY_PRODUCT, Scope.LIVE, brand, 2));
		assertSortValue(
			theHistory.getEvita(), REFERENCE_BRAND, partition,
			new AttributeIndexKey(REFERENCE_BRAND, COMPOUND_BRAND_LABEL_AND_PRIORITY, Locale.ENGLISH), true
		);

		// the remaining owner does the same: its row goes with every value it held
		theHistory.step("product 2 drops its title and its brand in one mutation", dropTitleAndBrand(2));
		theHistory.assertNoRow(ENTITY_PRODUCT, Scope.LIVE, brand);
		assertNull(
			partitionObjectOf(theHistory.getEvita(), ENTITY_PRODUCT, Scope.LIVE, brand),
			"the partition its last owner left must have no index object"
		);
		theHistory.restart();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a row demoted from a shared ReferencedEntityPredecessor record lacks the predecessor (#1691)")
	void shouldDemoteWithoutTheReferencedEntityPredecessorBothOwnersShared(CatalogState state) {
		final VirtualPartitionHistory theHistory = start(
			"sharedReferencedEntityPredecessor", state,
			VirtualPartitionPromotionLifecycleTest::setUpSharedReferencedEntityPredecessor
		);
		final int partition = theHistory.assertIndexObject(ENTITY_CATEGORY, Scope.LIVE, product(1), 1, 2);

		// the first owner's leaving removes the record both owners share (#1691), which the demotion therefore finds
		// absent - the body oracle leaves `ReferencedEntityPredecessor` out for this reason, this test pins it
		theHistory.step(
			"the first owner of the shared partition is removed",
			session -> {
				session.deleteEntity(ENTITY_CATEGORY, 1);
			}
		);
		assertEquals(partition, theHistory.assertRow(ENTITY_CATEGORY, Scope.LIVE, product(1), 2));
		assertTrue(
			requireRegistry(theHistory.getEvita(), ENTITY_CATEGORY, REFERENCE_PRODUCTS)
				.getChainPredecessor(
					new AttributeIndexKey(REFERENCE_PRODUCTS, ATTRIBUTE_CATEGORY_ORDER, null), partition
				)
				.isEmpty(),
			"#1691: the row of the remaining owner lacks the predecessor its body declares, because the record both " +
				"owners shared left with the first owner - once #1691 is fixed, the row holds it"
		);

		// a row holds no record another owner shares, so the remaining owner leaves it cleanly
		theHistory.step(
			"the second owner of the partition is removed",
			session -> {
				session.deleteEntity(ENTITY_CATEGORY, 2);
			}
		);
		theHistory.assertNoRow(ENTITY_CATEGORY, Scope.LIVE, product(1));
		assertNull(partitionObjectOf(theHistory.getEvita(), ENTITY_CATEGORY, Scope.LIVE, product(1)));
		theHistory.restart();
	}

	/**
	 * Defines products in two locales with a category reference carrying a sortable order, a localized sortable label
	 * and a localized compound of both, and a note category reference carrying a localized note, both reflected on the
	 * category with their attributes inherited; then lets products {@link #LEAVING_OWNER} and {@link #REMAINING_OWNER}
	 * share category {@link #NOTE_SHARED_CATEGORY} with English values only, and lets {@link #LEAVING_OWNER} alone
	 * reference category {@link #NOTE_OWN_CATEGORY}.
	 *
	 * @param session the session to write in
	 */
	private static void setUpLocalesWithNoteCategory(@Nonnull EvitaSessionContract session) {
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
		session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, NOTE_SHARED_CATEGORY));
		session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, NOTE_OWN_CATEGORY));
		for (final int owner : new int[]{LEAVING_OWNER, REMAINING_OWNER}) {
			final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, owner)
				.setReference(
					REFERENCE_CATEGORIES, NOTE_SHARED_CATEGORY,
					ref -> ref.setAttribute(ATTRIBUTE_ORDER, owner)
						.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "label " + owner)
				);
			if (owner == LEAVING_OWNER) {
				product.setReference(
					REFERENCE_CATEGORIES, NOTE_OWN_CATEGORY,
					ref -> ref.setAttribute(ATTRIBUTE_ORDER, NOTE_OWN_CATEGORY)
						.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "own label")
				);
			}
			session.upsertEntity(product);
		}
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
	private static void setUpLocalesWithMainCategory(@Nonnull EvitaSessionContract session) {
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
		openProductForWrite(session, 1)
			.setReference(REFERENCE_MAIN_CATEGORY, 1, ref -> ref.setAttribute(ATTRIBUTE_NOTE, CZECH, "hlavní"))
			.upsertVia(session);
	}

	/**
	 * Defines products in three locales whose category reference carries an order, a localized label and a localized
	 * compound of both, reflected on the category with the two attributes inherited; then creates products 1 and 2,
	 * each the only owner of the category of its own primary key, both sharing
	 * {@link SingleOwnerPartitionFixture#SHARED_CATEGORY} and {@link #SECOND_SHARED_CATEGORY} with an English label.
	 *
	 * The reflected reference does not inherit the compound: a category removing a reflected reference whose inherited
	 * localized compound it carries fails in the reference index mutator, which is not what this fixture is about.
	 *
	 * @param session the session to write in
	 */
	private static void setUpLocalesOfTwoSharedCategories(@Nonnull EvitaSessionContract session) {
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
		for (int pk = 1; pk <= FREE_TARGET; pk++) {
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
	 * its attributes inherited; then creates product 1 in its own category and in
	 * {@link SingleOwnerPartitionFixture#SHARED_CATEGORY}, where it heads both chains, and product 2 in the shared
	 * category after product 1 in the `Predecessor` chain only.
	 *
	 * Only one owner of any partition carries a `ReferencedEntityPredecessor` value, on either side of the reflection:
	 * the partition indexes it under the referenced entity, which several owners would share (#1691).
	 *
	 * @param session the session to write in
	 */
	private static void setUpChainsOfASharedCategory(@Nonnull EvitaSessionContract session) {
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
		for (int pk = 1; pk <= FREE_TARGET; pk++) {
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
	 * used to stay behind in the partition, where it orders before the remaining owner's value. A third product, the
	 * only one of another brand, keeps a row in the catalog whatever the two do.
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
					.withAttribute(ATTRIBUTE_PRIORITY, Integer.class, AttributeSchemaEditor::nullable)
					.withSortableAttributeCompound(
						COMPOUND_BRAND_LABEL_AND_PRIORITY,
						new AttributeElement(ATTRIBUTE_BRAND_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_PRIORITY, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
			)
			.updateVia(session);
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK + 1));
		for (int pk = 1; pk <= 3; pk++) {
			final int priority = pk;
			session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, pk)
					.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "title " + pk)
					.setReference(
						REFERENCE_BRAND, pk <= 2 ? BRAND_PK : BRAND_PK + 1,
						ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, 10 * priority)
					)
			);
		}
	}

	/**
	 * Defines categories with a direct reference to products indexed for filtering, carrying a sortable
	 * `ReferencedEntityPredecessor`; then creates categories 1 and 2 that both list product 1 - each at its own
	 * position in its own order, which their shared partition of product 1 indexes under the one record of product 1
	 * (#1691) - and category 3, the only one to list product 4, whose row stays whatever the other two do.
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
		for (int pk = 1; pk <= 4; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, pk));
		}
		session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 3).setReference(REFERENCE_PRODUCTS, 4));
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
	 * Creates a step letting a product of the singleton fixture join a category - see
	 * {@link #joined(EntityBuilder, int)}.
	 *
	 * @param product  the product joining
	 * @param category the category it joins
	 * @return the step
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> joinCategory(int product, int category) {
		return session -> joined(openProductForWrite(session, product), category).upsertVia(session);
	}

	/**
	 * Lets a product of the singleton fixture join a category, with an order, an English label and a chain position
	 * after the category's first owner of its own.
	 *
	 * @param product  the builder of the product joining
	 * @param category the category it joins
	 * @return the builder
	 */
	@Nonnull
	private static EntityBuilder joined(@Nonnull EntityBuilder product, int category) {
		return product.setReference(
			REFERENCE_CATEGORIES, category,
			ref -> ref.setAttribute(ATTRIBUTE_ORDER, 100 + category)
				.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Joined " + category)
				.setAttribute(ATTRIBUTE_POSITION, new Predecessor(category))
		);
	}

	/**
	 * Creates a step of one category mutation that takes one product out of a shared category and gives the other
	 * product a label in a new locale there - two product mutations of one root mutation, each run by an executor of
	 * its own.
	 *
	 * @param category       the shared category
	 * @param departing      the product leaving the category
	 * @param remaining      the product staying, which gets the label
	 * @param locale         the new locale of the label
	 * @param departureFirst TRUE to put the departure before the label among the category's mutations
	 * @return the step
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> demotionStep(
		int category,
		int departing,
		int remaining,
		@Nonnull Locale locale,
		boolean departureFirst
	) {
		return session -> {
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
		};
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
		return session -> openProductForWrite(session, productPk)
			.removeAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH)
			.removeReference(REFERENCE_BRAND, BRAND_PK)
			.upsertVia(session);
	}

	/**
	 * Returns the sort key of the order of the category reference.
	 *
	 * @return the key
	 */
	@Nonnull
	private static AttributeIndexKey orderKey() {
		return new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_ORDER, null);
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
	 * Returns the sort key of the localized compound of the label and the order of the category reference in a locale.
	 *
	 * @param locale the locale
	 * @return the key
	 */
	@Nonnull
	private static AttributeIndexKey labelAndOrderKey(@Nonnull Locale locale) {
		return new AttributeIndexKey(REFERENCE_CATEGORIES, COMPOUND_LABEL_AND_ORDER, locale);
	}

	/**
	 * Returns the committed live registry of a reference of the products or the categories.
	 *
	 * @param evita         the engine
	 * @param entityType    the entity type owning the reference
	 * @param referenceName the reference
	 * @return the registry
	 */
	@Nonnull
	private static VirtualReferencePartitionIndex requireRegistry(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull String referenceName
	) {
		final VirtualReferencePartitionIndex registry = registryOf(evita, entityType, Scope.LIVE, referenceName);
		assertNotNull(registry, "the reference `" + referenceName + "` of `" + entityType + "` must have its registry");
		return registry;
	}

	/**
	 * Returns the sort value the committed live row of a partition of the products holds under a sort key.
	 *
	 * @param evita         the engine
	 * @param referenceName the reference of the partition
	 * @param partition     the primary key of the row
	 * @param key           the sort key
	 * @return the normalized sort value, `null` when the row holds none
	 */
	@Nullable
	private static Serializable sortValueOf(
		@Nonnull Evita evita,
		@Nonnull String referenceName,
		int partition,
		@Nonnull AttributeIndexKey key
	) {
		final VirtualReferencePartitionIndex registry = requireRegistry(evita, ENTITY_PRODUCT, referenceName);
		final int valueId = registry.getSortValueId(key, partition);
		return valueId == VirtualReferencePartitionIndex.NO_SORT_VALUE_ID ?
			null : registry.getNormalizedSortValue(key, valueId);
	}

	/**
	 * Asserts whether the committed live row of a partition of the products holds a value under a sort key.
	 *
	 * @param evita         the engine
	 * @param referenceName the reference of the partition
	 * @param partition     the primary key of the row
	 * @param key           the sort key
	 * @param expected      TRUE when the row must hold a value
	 */
	private static void assertSortValue(
		@Nonnull Evita evita,
		@Nonnull String referenceName,
		int partition,
		@Nonnull AttributeIndexKey key,
		boolean expected
	) {
		assertEquals(
			expected, sortValueOf(evita, referenceName, partition, key) != null,
			"the row " + partition + " must " + (expected ? "" : "not ") + "hold a value of " + key
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
	 * Starts the history in the fixture of single-owner partitions in two locales (see
	 * {@link SingleOwnerPartitionFixture#setUpLocalizedSingleOwners}), prepared in the given state.
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
