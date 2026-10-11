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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.collection.EntityCollectionTestAccess;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link VirtualReferencePartitionRawRowOracle} reports what it claims to. A catalog born with its
 * single-owner partitions as registry rows - the representation every new catalog has - must pass it with every kind
 * of row entry and an index object of a shared partition engaged; a disagreement is then planted straight into the
 * indexes of the warming-up catalog - which the bodies know nothing of - and must be reported. The identity ledger is
 * proven against the transitions the engine makes and against a reallocation planted consistently into the registry,
 * which nothing but the ledger can see.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition raw-row oracle")
@Tag(TEST_HARNESS)
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionRawRowOracleTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualReferencePartitionRawRowOracleTest";
	/**
	 * A localized sortable category reference attribute.
	 */
	private static final String ATTRIBUTE_LABEL = "label";
	/**
	 * A chained category reference attribute.
	 */
	private static final String ATTRIBUTE_CHAIN = "chainOrder";
	/**
	 * A sortable compound of the category reference over the localized label and the order.
	 */
	private static final String COMPOUND_LABEL_AND_ORDER = "labelAndOrder";
	/**
	 * The sort key of the order of a category reference.
	 */
	private static final AttributeIndexKey ORDER_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_ORDER, null);
	/**
	 * The sort key of the English label of a category reference.
	 */
	private static final AttributeIndexKey LABEL_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_LABEL, Locale.ENGLISH);
	/**
	 * The sort key of the English compound of the label and the order.
	 */
	private static final AttributeIndexKey COMPOUND_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, COMPOUND_LABEL_AND_ORDER, Locale.ENGLISH);
	/**
	 * The chain key of the chained attribute of a category reference.
	 */
	private static final AttributeIndexKey CHAIN_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_CHAIN, null);
	/**
	 * The products the catalog holds: the fixture's and the two this test adds.
	 */
	private static final int PRODUCTS = PRODUCT_COUNT + 2;
	/**
	 * A primary key no index of the catalog has.
	 */
	private static final int UNUSED_PRIMARY_KEY = 9_999;
	/**
	 * A category no product references.
	 */
	private static final int UNREFERENCED_CATEGORY = 19;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualReferencePartitionRawRowOracleTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
				.build()
		);
		// a catalog the engine is told nothing about is born with its single-owner partitions as registry rows
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				SingleOwnerPartitionFixture.setUpExtendingCategories(
					session,
					categories -> categories
						.withAttribute(
							ATTRIBUTE_LABEL, String.class,
							thatIs -> thatIs.localized().sortableInScope(Scope.LIVE).nullable()
						)
						.withAttribute(
							ATTRIBUTE_CHAIN, Predecessor.class,
							thatIs -> thatIs.sortableInScope(Scope.LIVE).nullable()
						)
						.withSortableAttributeCompound(
							COMPOUND_LABEL_AND_ORDER,
							new AttributeElement[]{
								new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
							},
							thatIs -> thatIs.indexedInScope(Scope.LIVE)
						)
				);
				addProduct(session, 7, Predecessor.HEAD);
				addProduct(session, 8, new Predecessor(7));
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("A catalog its writers maintain")
	class Accepted {

		@Test
		@DisplayName("passes, with rows, sort values, compounds, chains and index objects of shared partitions engaged")
		void shouldAcceptThePartitionsTheWritersMaintain() {
			assertEquals(List.of(), inspect());

			// not vacuous: the single-owner partitions are rows only, with every kind of entry the oracle compares
			for (int category = 1; category <= PRODUCTS; category++) {
				assertNull(objectOf(category), "the single-owner partition of category " + category + " is a row only");
			}
			final VirtualReferencePartitionIndex registry = registry();
			assertEquals(PRODUCTS, registry.getSortIndex(ORDER_KEY).size());
			assertEquals(2, registry.getSortIndex(LABEL_KEY).size());
			assertEquals(2, registry.getSortIndex(COMPOUND_KEY).size());
			assertEquals(-1, registry.getChainPredecessor(CHAIN_KEY, rowOf(7)).orElseThrow());
			assertEquals(7, registry.getChainPredecessor(CHAIN_KEY, rowOf(8)).orElseThrow());
			// ... and the shared category is an index object whose values the oracle compares owner by owner
			final EntityIndex shared = objectOf(SHARED_CATEGORY);
			assertNotNull(shared, "the shared category must be an index object");
			assertArrayEquals(new int[]{1, 2, 3}, shared.getAllPrimaryKeys().getArray());
			assertEquals(3, shared.getSortIndex(ORDER_KEY).size());
		}

		@Test
		@DisplayName("reads the bodies page by page to the last one")
		void shouldReadEveryPageOfTheBodies() {
			// three products a page: the closed world would report the rows of every product past the first page
			assertEquals(List.of(), inspect(null, 3));
		}

		@Test
		@DisplayName("keeps the identity of a partition the engine promotes and demotes again")
		void shouldAcceptThePrimaryKeysThroughAPromotionAndADemotion() {
			final PartitionIdentityLedger ledger = new PartitionIdentityLedger();
			assertEquals(List.of(), inspect(ledger));
			final int row = rowOf(4);

			joinOrLeave(3, 4, true);
			assertEquals(List.of(), inspect(ledger));
			final EntityIndex promoted = objectOf(4);
			assertNotNull(promoted, "product 3 joining category 4 must have promoted its partition");
			assertArrayEquals(new int[]{3, 4}, promoted.getAllPrimaryKeys().getArray());
			assertEquals(row, promoted.getPrimaryKey(), "the promotion keeps the primary key of the row");

			joinOrLeave(3, 4, false);
			assertEquals(List.of(), inspect(ledger));
			assertNull(objectOf(4), "product 3 leaving category 4 must have demoted its partition");
			assertEquals(row, rowOf(4), "the demotion keeps the primary key of the index object");
			assertTrue(
				ledger.getComparedObservations() > 2 * PRODUCTS,
				"the ledger must have compared the partitions at every checkpoint: " +
					ledger.getComparedObservations()
			);
		}

		/**
		 * Lets a product join or leave a category, in a root mutation of its own.
		 *
		 * @param product  the product
		 * @param category the category
		 * @param join     TRUE to join the category, FALSE to leave it
		 */
		private void joinOrLeave(int product, int category, boolean join) {
			VirtualReferencePartitionRawRowOracleTest.this.evita.updateCatalog(
				CATALOG,
				SingleOwnerPartitionFixture.updateProduct(
					product,
					it -> {
						if (join) {
							it.setReference(
								REFERENCE_CATEGORIES, category,
								ref -> ref.setAttribute(ATTRIBUTE_ORDER, 10 * product + category)
							);
						} else {
							it.removeReference(REFERENCE_CATEGORIES, category);
						}
					}
				)
			);
		}
	}

	@Nested
	@DisplayName("A disagreement planted into the rows")
	class PlantedIntoRows {

		@Test
		@DisplayName("a sort value missing from the registry is reported")
		void shouldReportAMissingSortValue() {
			registry().removeSortValue(ORDER_KEY, rowOf(4));

			assertFindings("sort key " + ORDER_KEY + " of partition " + rowOf(4) + ": the registry holds absent");
		}

		@Test
		@DisplayName("a sort value other than the body's is reported")
		void shouldReportAnotherSortValue() {
			registry().putSortValue(ORDER_KEY, rowOf(4), 44);

			assertFindings("sort key " + ORDER_KEY + " of partition " + rowOf(4) + ": the registry holds Integer(44)");
		}

		@Test
		@DisplayName("a localized compound value missing from the registry is reported")
		void shouldReportAMissingCompoundValue() {
			registry().removeSortValue(COMPOUND_KEY, rowOf(8));

			assertFindings("sort key " + COMPOUND_KEY + " of partition " + rowOf(8) + ": the registry holds absent");
		}

		@Test
		@DisplayName("a chain predecessor missing from the registry is reported")
		void shouldReportAMissingChainPredecessor() {
			registry().removeChainPredecessor(CHAIN_KEY, rowOf(8));

			assertFindings(
				"chain key " + CHAIN_KEY + " of partition " + rowOf(8) + ": the registry holds OptionalInt.empty"
			);
		}

		@Test
		@DisplayName("a single owner without its row is reported, and so are the values left under its primary key")
		void shouldReportAMissingRow() {
			final int row = rowOf(4);
			registry().removePartitionOwner(row);

			assertFindings(
				"the single owner 4 of " + category(4) + " has no row",
				"sort key " + ORDER_KEY + " of partition " + row + ": the registry holds Integer(4), the body of its " +
					"owner holds absent"
			);
		}

		@Test
		@DisplayName("a row of a partition with several owners in the bodies is reported")
		void shouldReportARowOfASharedPartition() {
			final EntityIndex shared = objectOf(SHARED_CATEGORY);
			assertNotNull(shared);
			registry().putPartitionOwner(shared.getPrimaryKey(), 1, SHARED_CATEGORY);

			assertFindings(
				"the partition " + category(SHARED_CATEGORY) + " with the owners [1, 2, 3] has the row " +
					shared.getPrimaryKey() + " (owner 1 -> " + SHARED_CATEGORY + ")"
			);
		}

		@Test
		@DisplayName("a row of a partition no body produces is reported")
		void shouldReportAnExtraRow() {
			registry().putPartitionOwner(UNUSED_PRIMARY_KEY, 1, UNREFERENCED_CATEGORY);

			assertFindings(
				"row " + UNUSED_PRIMARY_KEY + " (owner 1 -> " + UNREFERENCED_CATEGORY + ") is a partition no body " +
					"produces"
			);
		}
	}

	@Nested
	@DisplayName("A disagreement planted into the index objects")
	class PlantedIntoObjects {

		@Test
		@DisplayName("a partition with several owners in the bodies and no index object is reported")
		void shouldReportAMissingSharedObject() {
			EntityCollectionTestAccess.removeIndex(
				products(), new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, category(SHARED_CATEGORY))
			);

			assertFindings(
				"the partition " + category(SHARED_CATEGORY) + " with the owners [1, 2, 3] has no index object"
			);
		}
	}

	@Nested
	@DisplayName("The identity of a partition")
	class Identity {

		@Test
		@DisplayName("a primary key reallocated consistently is reported by the ledger alone")
		void shouldReportAReallocatedPrimaryKey() {
			final PartitionIdentityLedger ledger = new PartitionIdentityLedger();
			assertEquals(List.of(), inspect(ledger));
			final int row = rowOf(4);

			// the row of category 4 moves to another primary key with everything it holds
			final VirtualReferencePartitionIndex registry = registry();
			final Serializable order = registry.getSortIndex(ORDER_KEY).getValueOfRecord(row).orElseThrow();
			registry.removeSortValue(ORDER_KEY, row);
			registry.removePartitionOwner(row);
			registry.putPartitionOwner(UNUSED_PRIMARY_KEY, 4, 4);
			registry.putSortValue(ORDER_KEY, UNUSED_PRIMARY_KEY, order);

			// the partitions agree with the bodies as well as with each other - the identity is the only change
			assertEquals(List.of(), inspect());
			assertFindings(
				inspect(ledger),
				identityOfCategoryFour() +
					": primary key " + row + " at the previous checkpoint, " + UNUSED_PRIMARY_KEY + " now"
			);
		}

		@Test
		@DisplayName("a partition emptied and entered again between two checkpoints is reported unless declared")
		void shouldReportAPartitionEnteredAgainUnderAnotherPrimaryKeyUnlessDeclared() {
			final PartitionIdentityLedger ledger = new PartitionIdentityLedger();
			assertEquals(List.of(), inspect(ledger));
			final int row = rowOf(4);

			moveCategoryFour(4, 5);
			final int entered = rowOf(4);
			assertNotEquals(row, entered, "the owner entering the emptied partition later must have minted a new row");
			assertFindings(
				inspect(ledger),
				identityOfCategoryFour() +
					": primary key " + row + " at the previous checkpoint, " + entered + " now"
			);

			// declared, the same history passes - the ledger has recorded the new primary key meanwhile
			moveCategoryFour(5, 4);
			ledger.declareEmptied(ENTITY_PRODUCT, Scope.LIVE, category(4));
			assertEquals(List.of(), inspect(ledger));
			assertNotEquals(entered, rowOf(4), "the history must have minted another row again");
		}

		/**
		 * Lets the only owner of category 4 leave it and another product enter it afterwards, in root mutations of
		 * their own - which empties the partition and mints it anew.
		 *
		 * @param leaving  the only owner of category 4
		 * @param entering the product entering category 4 then
		 */
		private void moveCategoryFour(int leaving, int entering) {
			VirtualReferencePartitionRawRowOracleTest.this.evita.updateCatalog(
				CATALOG, updateProduct(leaving, it -> it.removeReference(REFERENCE_CATEGORIES, 4))
			);
			VirtualReferencePartitionRawRowOracleTest.this.evita.updateCatalog(
				CATALOG,
				updateProduct(
					entering,
					it -> it.setReference(REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 4))
				)
			);
		}
	}

	/**
	 * Returns how a finding of the identity ledger names the live partition of category 4 of the products.
	 *
	 * @return the start of the finding
	 */
	@Nonnull
	private static String identityOfCategoryFour() {
		return "identity of `" + ENTITY_PRODUCT + "` " + PartitionIdentityLedger.identityKeyOf(Scope.LIVE, category(4));
	}

	@Nested
	@DisplayName("A disagreement planted beyond the first page of bodies")
	class PlantedBeyondTheFirstPage {

		@Test
		@DisplayName("is reported")
		void shouldReportADisagreementInABodyBeyondTheFirstPage() {
			registry().removeChainPredecessor(CHAIN_KEY, rowOf(8));

			// product 8 is on the third page of three products each
			assertFindings(
				inspect(null, 3),
				"chain key " + CHAIN_KEY + " of partition " + rowOf(8) + ": the registry holds OptionalInt.empty"
			);
		}
	}

	/**
	 * Adds a product as the fixture creates it, its category reference also carrying an English label and the chained
	 * attribute.
	 *
	 * @param session           the session to write in
	 * @param productPrimaryKey the product, whose category reference has the same primary key
	 * @param predecessor       the chained attribute
	 */
	private static void addProduct(
		@Nonnull EvitaSessionContract session,
		int productPrimaryKey,
		@Nonnull Predecessor predecessor
	) {
		session.upsertEntity(
			newProduct(session, productPrimaryKey).updateReference(
				REFERENCE_CATEGORIES, productPrimaryKey,
				ref -> ref
					.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "label " + productPrimaryKey)
					.setAttribute(ATTRIBUTE_CHAIN, predecessor)
			)
		);
	}

	/**
	 * Asserts the oracle finds exactly the planted disagreements, each finding containing one of the fragments.
	 *
	 * @param fragments the fragments the findings must contain, one per finding
	 */
	private void assertFindings(@Nonnull String... fragments) {
		assertFindings(inspect(), fragments);
	}

	/**
	 * Asserts the findings are exactly the planted disagreements, each finding containing one of the fragments.
	 *
	 * @param findings  the findings
	 * @param fragments the fragments the findings must contain, one per finding
	 */
	private static void assertFindings(@Nonnull List<String> findings, @Nonnull String... fragments) {
		assertEquals(fragments.length, findings.size(), findings::toString);
		final List<String> unmatched = new ArrayList<>(findings);
		for (final String fragment : fragments) {
			assertTrue(
				unmatched.removeIf(it -> it.contains(fragment)),
				() -> "no finding contains `" + fragment + "`: " + findings + " (fragments " +
					Arrays.toString(fragments) + ")"
			);
		}
	}

	/**
	 * Inspects the products in a read-only session.
	 *
	 * @return the findings
	 */
	@Nonnull
	private List<String> inspect() {
		return inspect(null);
	}

	/**
	 * Inspects the products in a read-only session, checking the identity of their partitions against the ledger.
	 *
	 * @param ledger the ledger, `null` to leave the identity unchecked
	 * @return the findings
	 */
	@Nonnull
	private List<String> inspect(@Nullable PartitionIdentityLedger ledger) {
		return this.evita.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, List<String>>) session ->
				VirtualReferencePartitionRawRowOracle.inspect(session, products(), ledger)
		);
	}

	/**
	 * Inspects the products in a read-only session, reading the bodies in pages of the given size.
	 *
	 * @param ledger   the ledger, `null` to leave the identity unchecked
	 * @param pageSize how many bodies one query reads
	 * @return the findings
	 */
	@Nonnull
	private List<String> inspect(@Nullable PartitionIdentityLedger ledger, int pageSize) {
		return this.evita.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, List<String>>) session ->
				VirtualReferencePartitionRawRowOracle.inspect(session, products(), ledger, pageSize)
		);
	}

	/**
	 * Resolves the row of the live partition of the category reference to one category.
	 *
	 * @param category the category
	 * @return the primary key of the partition
	 */
	private int rowOf(int category) {
		final EntityIndex typeIndex = products().getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_CATEGORIES)
		);
		final VirtualReferencePartition partition = ReferencePartitions.findRegisteredForWrite(
			assertInstanceOf(ReferencedTypeEntityIndex.class, typeIndex), category(category)
		);
		assertNotNull(partition, "No row for category " + category);
		return partition.getPrimaryKey();
	}

	/**
	 * Resolves the index object of the live partition of the category reference to one category.
	 *
	 * @param category the category
	 * @return the index object, `null` when the partition has none
	 */
	@Nullable
	private EntityIndex objectOf(int category) {
		return products().getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, category))
			)
		);
	}

	/**
	 * Returns the live registry of the category reference - written in place, as the catalog is warming up.
	 *
	 * @return the registry
	 */
	@Nonnull
	private VirtualReferencePartitionIndex registry() {
		final EntityIndex typeIndex = products().getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_CATEGORIES)
		);
		return assertInstanceOf(ReferencedTypeEntityIndex.class, typeIndex).getVirtualReferencePartitionIndex();
	}

	/**
	 * Returns the product collection.
	 *
	 * @return the product collection
	 */
	@Nonnull
	private EntityCollection products() {
		return (EntityCollection) this.evita.getCatalogInstance(CATALOG)
			.orElseThrow()
			.getCollectionForEntity(ENTITY_PRODUCT)
			.orElseThrow();
	}
}
