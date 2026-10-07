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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.virtual.VirtualReadParity.Comparison;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a `pickFirst` ordering by a reference - the index route resolving its partitions at execution time and
 * the prefetch route ranking the targets the same way - answers the same with the read switch on as with it off, on the
 * same engine and the same data: with the switch on, the registered single-owner partitions are
 * gathered as virtual handles, ranked among the materialized ones, and claim their owners through the registry.
 *
 * Two catalogs, each maintained from its first write so every single-owner partition has its row:
 *
 * - {@link RelatedProductsShapedFixture} - thousands of generated owners mixing single-owner and shared partitions,
 *   duplicates ordered by their representative attribute, value-less rows, a plain, a localized, a compound and a
 *   localized compound sort value, both scopes;
 * - a hand-built witness catalog: in `owner`, the selected owners 1 and 2 share a materialized partition while the
 *   unselected owner 3 holds the lowest value in a single-owner partition the gather hands over because the selection
 *   covers the whole family; in `tieOwner`, a block of owners far larger than a page, each in a single-owner partition
 *   of its own carrying one shared value, created in reverse owner order.
 *
 * Every case compares whole result lists and two page windows in both directions and must read virtual rows - an
 * equal answer of a run that read nothing virtually would compare the object reading with itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Read parity of pick-first orderings over virtual reference partitions")
@Tag(ENGINE)
@Tag(ORDER)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class VirtualPickFirstParityTest implements EvitaTestSupport {
	private static final String CATALOG_RELATED = "virtualPickFirstParityRelated";
	private static final String CATALOG_WITNESS = "virtualPickFirstParityWitness";
	private static final String ENTITY_TARGET = "target";
	private static final String ENTITY_OWNER = "owner";
	private static final String ENTITY_TIE_OWNER = "tieOwner";
	private static final String REFERENCE_ITEMS = "items";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final Set<String> WITNESS_ENTITY_TYPES = Set.of(ENTITY_TARGET, ENTITY_OWNER, ENTITY_TIE_OWNER);
	/**
	 * The owners of the tie block, each holding one single-owner partition with {@link #TIE_VALUE}.
	 */
	private static final int TIE_OWNER_COUNT = 300;
	/**
	 * The first target of the tie block.
	 */
	private static final int TIE_TARGET_BASE = 2_000;
	/**
	 * The value every row of the tie block carries.
	 */
	private static final int TIE_VALUE = 7;
	/**
	 * The page size of the tie block, far smaller than the block.
	 */
	private static final int TIE_PAGE_SIZE = 20;
	private static final Scope[] LIVE = {Scope.LIVE};
	private static final Scope[] ARCHIVED = {Scope.ARCHIVED};
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	/**
	 * The page large enough to hold every entity of every catalog.
	 */
	private static final int PAGE_SIZE = 100_000;

	private TestPaths paths;
	private Evita evita;

	/**
	 * The route a case forces the engine to take.
	 */
	private enum Route {
		INDEX, PREFETCH
	}

	/**
	 * The owners a case selects.
	 */
	private enum Selection {
		/**
		 * Every owner of the scope - the dense claims.
		 */
		ALL,
		/**
		 * A small set of owners - the sparse claims.
		 */
		SMALL,
		/**
		 * The owners selected by a `referenceHaving` on the ordered reference itself.
		 */
		NARROWED
	}

	/**
	 * One ordering of the related products, run in every selection, scope set and route.
	 *
	 * @param name        the name of the case
	 * @param localized   TRUE when the case needs a locale
	 * @param targetOrder the target order specification, `null` for the default
	 * @param value       creates the value ordering in the direction
	 */
	private record OrderingCase(
		@Nonnull String name,
		boolean localized,
		@Nullable OrderConstraint targetOrder,
		@Nonnull Function<OrderDirection, OrderConstraint> value
	) {

		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}

	}

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("VirtualPickFirstParityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		build(
			CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_TYPES,
			RelatedProductsShapedFixture::setUp, RelatedProductsShapedFixture::archive
		);
		build(CATALOG_WITNESS, WITNESS_ENTITY_TYPES, VirtualPickFirstParityTest::setUpWitness, session -> {
		});
	}

	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Returns the orderings of the related products.
	 *
	 * @return the cases
	 */
	@Nonnull
	static Stream<OrderingCase> orderingCases() {
		final String order = RelatedProductsShapedFixture.ATTRIBUTE_ORDER;
		final String label = RelatedProductsShapedFixture.ATTRIBUTE_LABEL;
		final String typeOrder = RelatedProductsShapedFixture.COMPOUND_TYPE_ORDER;
		final String orderLabel = RelatedProductsShapedFixture.COMPOUND_ORDER_LABEL;
		final String rank = RelatedProductsShapedFixture.ATTRIBUTE_RANK;
		return Stream.of(
			new OrderingCase("order", false, null, direction -> attributeNatural(order, direction)),
			new OrderingCase("localized label", true, null, direction -> attributeNatural(label, direction)),
			new OrderingCase("compound typeOrder", false, null, direction -> attributeNatural(typeOrder, direction)),
			new OrderingCase(
				"localized compound orderLabel", true, null, direction -> attributeNatural(orderLabel, direction)
			),
			new OrderingCase("entityPrimaryKeyNatural", false, null, direction -> entityPrimaryKeyNatural(direction)),
			new OrderingCase(
				"order, targets by primary key descending", false,
				pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.DESC)),
				direction -> attributeNatural(order, direction)
			),
			new OrderingCase(
				"order, targets by rank", false, pickFirstByEntityProperty(attributeNatural(rank)),
				direction -> attributeNatural(order, direction)
			),
			new OrderingCase(
				"order, targets by seeded random", false, pickFirstByEntityProperty(randomWithSeed(42)),
				direction -> attributeNatural(order, direction)
			),
			new OrderingCase(
				"entityPrimaryKeyNatural, targets by seeded random", false,
				pickFirstByEntityProperty(randomWithSeed(7)), direction -> entityPrimaryKeyNatural(direction)
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("orderingCases")
	@DisplayName("should order the related products identically with the read switch on")
	void shouldOrderRelatedProductsIdentically(@Nonnull OrderingCase orderingCase) {
		final List<String> differences = new ArrayList<>(16);
		long indexRouteRowsRead = 0;
		for (Route route : Route.values()) {
			for (Selection selection : Selection.values()) {
				for (Scope[] scopes : new Scope[][]{LIVE, ARCHIVED, BOTH}) {
					for (OrderDirection direction : OrderDirection.values()) {
						final OrderConstraint ordering = referenceProperty(
							RelatedProductsShapedFixture.REFERENCE_RELATED,
							Stream.of(orderingCase.targetOrder(), orderingCase.value().apply(direction))
								.filter(Objects::nonNull)
								.toArray(OrderConstraint[]::new)
						);
						for (int[] window : new int[][]{{1, PAGE_SIZE}, {3, 17}, {1, 5}}) {
							final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
								maintenance(CATALOG_RELATED), RelatedProductsShapedFixture.ENTITY_TYPES,
								() -> queryRelated(route, selection, scopes, orderingCase.localized(), ordering, window)
							);
							if (!comparison.objectReading().equals(comparison.virtualReading().answer())) {
								differences.add(
									route + " " + selection + " " + scopes.length + " scope(s) " + direction +
										" page " + window[0] + "/" + window[1] + ": " + comparison.objectReading() +
										" vs " + comparison.virtualReading().answer()
								);
							}
							if (route == Route.INDEX) {
								indexRouteRowsRead += comparison.virtualReading().virtualRowsRead();
							}
							if (window[1] == PAGE_SIZE && selection == Selection.ALL) {
								assertFalse(comparison.objectReading().isEmpty(), "The selection must not be empty.");
							}
						}
					}
				}
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		assertTrue(indexRouteRowsRead > 0, "The index route must have read virtual rows.");
	}

	@Test
	@DisplayName("should never sort an unselected owner of a single-owner partition the gather handed over")
	void shouldNeverSortUnselectedOwnerOfWholeFamilyFallback() {
		for (OrderConstraint value : new OrderConstraint[]{
			attributeNatural(ATTRIBUTE_ORDER), entityPrimaryKeyNatural(OrderDirection.ASC)
		}) {
			final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
				maintenance(CATALOG_WITNESS), WITNESS_ENTITY_TYPES,
				() -> queryWitness(
					ENTITY_OWNER, entityPrimaryKeyInSet(1, 2), referenceProperty(REFERENCE_ITEMS, value), 1, 2
				)
			);
			assertEquals(comparison.objectReading(), comparison.virtualReading().answer());
			assertEquals(2, comparison.virtualReading().answer().size());
			assertFalse(comparison.virtualReading().answer().contains(3), "The unselected owner must not appear.");
			assertTrue(comparison.virtualReading().virtualRowsRead() > 0, "The virtual row must have been read.");
		}
		// the order of the witness: owner 2 has the smaller value, both share the referenced primary key
		assertEquals(
			List.of(2, 1),
			queryWitness(
				ENTITY_OWNER, entityPrimaryKeyInSet(1, 2),
				referenceProperty(REFERENCE_ITEMS, attributeNatural(ATTRIBUTE_ORDER)), 1, 2
			)
		);
	}

	@Test
	@DisplayName("should break ties of single-owner partitions by owner primary key on every page")
	void shouldBreakTiesByOwnerOnEveryPage() {
		final Integer[] owners = IntStream.rangeClosed(1, TIE_OWNER_COUNT).boxed().toArray(Integer[]::new);
		for (OrderDirection direction : OrderDirection.values()) {
			final List<Integer> expected = new ArrayList<>(IntStream.rangeClosed(1, TIE_OWNER_COUNT).boxed().toList());
			if (direction == OrderDirection.DESC) {
				Collections.reverse(expected);
			}
			final OrderConstraint ordering = referenceProperty(
				REFERENCE_ITEMS, attributeNatural(ATTRIBUTE_ORDER, direction)
			);
			for (int pageNumber = 1; pageNumber <= TIE_OWNER_COUNT / TIE_PAGE_SIZE; pageNumber++) {
				final int thePage = pageNumber;
				final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
					maintenance(CATALOG_WITNESS), WITNESS_ENTITY_TYPES,
					() -> queryWitness(ENTITY_TIE_OWNER, entityPrimaryKeyInSet(owners), ordering, thePage, TIE_PAGE_SIZE)
				);
				assertEquals(
					expected.subList((pageNumber - 1) * TIE_PAGE_SIZE, pageNumber * TIE_PAGE_SIZE),
					comparison.objectReading()
				);
				assertEquals(comparison.objectReading(), comparison.virtualReading().answer());
				assertTrue(comparison.virtualReading().virtualRowsRead() > 0, "The virtual rows must have been read.");
			}
		}
	}

	/**
	 * Fills the witness catalog.
	 *
	 * @param session the session of the catalog in warm-up
	 */
	private static void setUpWitness(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_TARGET).withoutGeneratedPrimaryKey().updateVia(session);
		for (String entityType : new String[]{ENTITY_OWNER, ENTITY_TIE_OWNER}) {
			session.defineEntitySchema(entityType)
				.withoutGeneratedPrimaryKey()
				.withReferenceToEntity(
					REFERENCE_ITEMS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE)
						.withAttribute(
							ATTRIBUTE_ORDER, Integer.class,
							thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE).nullable()
						)
				)
				.updateVia(session);
		}
		for (int target : new int[]{10, 20}) {
			session.createNewEntity(ENTITY_TARGET, target).upsertVia(session);
		}
		session.createNewEntity(ENTITY_OWNER, 1)
			.setReference(REFERENCE_ITEMS, 20, whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, 5))
			.upsertVia(session);
		session.createNewEntity(ENTITY_OWNER, 2)
			.setReference(REFERENCE_ITEMS, 20, whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, 3))
			.upsertVia(session);
		session.createNewEntity(ENTITY_OWNER, 3)
			.setReference(REFERENCE_ITEMS, 10, whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, 1))
			.upsertVia(session);
		// reverse owner order, so the partition primary keys fall as the owner rises
		for (int owner = TIE_OWNER_COUNT; owner >= 1; owner--) {
			final int target = TIE_TARGET_BASE + owner;
			session.createNewEntity(ENTITY_TARGET, target).upsertVia(session);
			session.createNewEntity(ENTITY_TIE_OWNER, owner)
				.setReference(REFERENCE_ITEMS, target, whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, TIE_VALUE))
				.upsertVia(session);
		}
	}

	/**
	 * Queries the related products of the selection in the scopes, forcing the route, and returns the primary keys of
	 * the requested page.
	 */
	@Nonnull
	private List<Integer> queryRelated(
		@Nonnull Route route,
		@Nonnull Selection selection,
		@Nonnull Scope[] scopes,
		boolean localized,
		@Nonnull OrderConstraint ordering,
		@Nonnull int[] window
	) {
		final FilterConstraint selectionConstraint = switch (selection) {
			case ALL -> null;
			case SMALL -> entityPrimaryKeyInSet(
				IntStream.iterate(3, it -> it <= RelatedProductsShapedFixture.PRODUCT_COUNT, it -> it + 97)
					.boxed()
					.toArray(Integer[]::new)
			);
			case NARROWED -> referenceHaving(
				RelatedProductsShapedFixture.REFERENCE_RELATED,
				attributeEquals(
					RelatedProductsShapedFixture.ATTRIBUTE_RELATION_TYPE, RelatedProductsShapedFixture.TYPE_SPARE
				)
			);
		};
		return this.evita.queryCatalog(
			CATALOG_RELATED,
			(Function<EvitaSessionContract, List<Integer>>) session -> session.query(
					query(
						collection(RelatedProductsShapedFixture.ENTITY_PRODUCT),
						filterBy(
							scope(scopes),
							localized ? entityLocaleEquals(RelatedProductsShapedFixture.LOCALE_EN) : null,
							selectionConstraint
						),
						orderBy(ordering),
						require(
							page(window[0], window[1]),
							debug(route == Route.INDEX ? DebugMode.PREFER_INDEX_SCAN : DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				)
				.getRecordData()
				.stream()
				.map(EntityReference::getPrimaryKey)
				.toList()
		);
	}

	/**
	 * Queries a collection of the witness catalog on the index route.
	 */
	@Nonnull
	private List<Integer> queryWitness(
		@Nonnull String entityType,
		@Nonnull FilterConstraint selection,
		@Nonnull OrderConstraint ordering,
		int pageNumber,
		int pageSize
	) {
		return this.evita.queryCatalog(
			CATALOG_WITNESS,
			(Function<EvitaSessionContract, List<Integer>>) session -> session.query(
					query(
						collection(entityType),
						filterBy(selection),
						orderBy(ordering),
						require(page(pageNumber, pageSize), debug(DebugMode.PREFER_INDEX_SCAN))
					),
					EntityReference.class
				)
				.getRecordData()
				.stream()
				.map(EntityReference::getPrimaryKey)
				.toList()
		);
	}

	/**
	 * Creates a catalog maintained from its first write, fills it in warm-up, takes it live and then applies the
	 * changes of the live catalog.
	 *
	 * @param catalog     the catalog
	 * @param entityTypes its collections, all maintained
	 * @param warmUp      fills the catalog in warm-up
	 * @param live        changes the live catalog
	 */
	private void build(
		@Nonnull String catalog,
		@Nonnull Set<String> entityTypes,
		@Nonnull Consumer<EvitaSessionContract> warmUp,
		@Nonnull Consumer<EvitaSessionContract> live
	) {
		// TODO JNO - remove at the end of #1615
		this.evita.createCatalogWithVirtualReferencePartitions(catalog, false);
		this.evita.defineCatalog(catalog).updateViaNewSession(this.evita);
		for (final String entityType : entityTypes) {
			maintenance(catalog).setMaintained(entityType, true);
		}
		this.evita.updateCatalog(catalog, warmUp);
		this.evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(catalog, live);
		for (final String entityType : entityTypes) {
			final EntityCollection collection = (EntityCollection) this.evita.getCatalogInstance(catalog)
				.orElseThrow()
				.getCollectionForEntity(entityType)
				.orElseThrow();
			VirtualReferencePartitionOracle.assertConsistent(catalog, collection);
		}
	}

	/**
	 * Returns the switch holder of a catalog.
	 *
	 * @param catalog the catalog
	 * @return the switch holder
	 */
	@Nonnull
	private VirtualReferencePartitionMaintenance maintenance(@Nonnull String catalog) {
		return ((Catalog) this.evita.getCatalogInstance(catalog).orElseThrow())
			.getVirtualReferencePartitionMaintenance();
	}

}
