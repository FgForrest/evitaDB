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
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
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
 * the prefetch route ranking the targets the same way - orders the owners of single-owner partitions kept as registry
 * rows exactly as it orders them when every partition is an index object: the rows are gathered as virtual handles,
 * ranked among the index objects, and claim their owners through the registry.
 *
 * Every catalog is built twice in one engine, as the {@link MaterializedPartitionTwin} describes - the row catalog as
 * the fixture builds it, and the object catalog with every owner of the ordered collection cloned. The whole order of
 * the object catalog, mapped back to the originals, is the expected order; the row catalog answers real page requests,
 * each of which must be the matching slice of it. The fixtures:
 *
 * - {@link RelatedProductsShapedFixture} - thousands of generated owners mixing single-owner and shared partitions,
 *   duplicates ordered by their representative attribute, value-less rows, a plain, a localized, a compound and a
 *   localized compound sort value, both scopes;
 * - a hand-built witness catalog: in `owner`, the selected owners 1 and 2 share a partition while the unselected owner
 *   3 holds the lowest value in a single-owner partition the gather hands over because the selection covers the whole
 *   family; in `tieOwner`, a block of owners far larger than a page, each in a single-owner partition of its own
 *   carrying one shared value, created in reverse owner order.
 *
 * Every case compares the whole order and two page windows in both directions and must select a single-owner
 * partition of the row catalog - an equal answer of a run that read no row would compare the index objects with
 * themselves.
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
	 * The page large enough to hold every entity of every catalog, clones included.
	 */
	private static final int PAGE_SIZE = 100_000;
	/**
	 * The page windows every case requests from the row catalog - the whole order, a page with an offset and a short
	 * first page.
	 */
	private static final int[][] WINDOWS = {{1, PAGE_SIZE}, {3, 17}, {1, 5}};
	/**
	 * The owners the small selection names.
	 */
	private static final Integer[] SMALL_SELECTION = IntStream.iterate(
		3, it -> it <= RelatedProductsShapedFixture.PRODUCT_COUNT, it -> it + 97
	).boxed().toArray(Integer[]::new);

	private TestPaths paths;
	private Evita evita;
	/**
	 * The twin of {@link #CATALOG_RELATED}, cloning its products.
	 */
	private MaterializedPartitionTwin related;
	/**
	 * The twin of {@link #CATALOG_WITNESS}, cloning its owners.
	 */
	private MaterializedPartitionTwin witnessOwners;
	/**
	 * The twin of {@link #CATALOG_WITNESS}, cloning its tie owners.
	 */
	private MaterializedPartitionTwin witnessTieOwners;

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
		MaterializedPartitionTwin.createRowCatalog(
			this.evita, CATALOG_RELATED, RelatedProductsShapedFixture::setUp, RelatedProductsShapedFixture::archive
		);
		this.related = MaterializedPartitionTwin.create(
			this.evita, CATALOG_RELATED, RelatedProductsShapedFixture::setUp, RelatedProductsShapedFixture::archive,
			RelatedProductsShapedFixture.ENTITY_PRODUCT, RelatedProductsShapedFixture.REFERENCE_RELATED
		);
		MaterializedPartitionTwin.createRowCatalog(
			this.evita, CATALOG_WITNESS, VirtualPickFirstParityTest::setUpWitness, session -> {
			}
		);
		this.witnessOwners = MaterializedPartitionTwin.create(
			this.evita, CATALOG_WITNESS, VirtualPickFirstParityTest::setUpWitness, session -> {
			},
			ENTITY_OWNER, REFERENCE_ITEMS
		);
		this.witnessTieOwners = MaterializedPartitionTwin.create(
			this.evita, CATALOG_WITNESS, VirtualPickFirstParityTest::setUpWitness, session -> {
			},
			ENTITY_TIE_OWNER, REFERENCE_ITEMS
		);
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
			new OrderingCase("entityPrimaryKeyNatural", false, null, QueryConstraints::entityPrimaryKeyNatural),
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
				pickFirstByEntityProperty(randomWithSeed(7)), QueryConstraints::entityPrimaryKeyNatural
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("orderingCases")
	@DisplayName("should order the related products from the rows as from the index objects")
	void shouldOrderRelatedProductsIdentically(@Nonnull OrderingCase orderingCase) {
		final List<String> differences = new ArrayList<>(16);
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
						// the whole order of the index objects, obtained without a page offset
						final List<Integer> expected = this.related.expectedAnswer(
							RelatedProductsShapedFixture.ENTITY_PRODUCT,
							queryRelated(
								this.related.objectCatalog(), route, selection, true, scopes,
								orderingCase.localized(), ordering, WINDOWS[0]
							)
						);
						if (selection == Selection.ALL) {
							assertFalse(expected.isEmpty(), "The selection must not be empty.");
						}
						for (int[] window : WINDOWS) {
							final List<Integer> actual = queryRelated(
								this.related.rowCatalog(), route, selection, false, scopes, orderingCase.localized(),
								ordering, window
							);
							final List<Integer> expectedPage = MaterializedPartitionTwin.pageOf(
								expected, window[0], window[1]
							);
							if (!expectedPage.equals(actual)) {
								differences.add(
									route + " " + selection + " " + scopes.length + " scope(s) " + direction +
										" page " + window[0] + "/" + window[1] + ": " + expectedPage + " vs " + actual
								);
							}
						}
					}
				}
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		assertTrue(
			this.related.selectsSingleOwnerRow(
				BOTH, RelatedProductsShapedFixture.REFERENCE_RELATED, null,
				orderingCase.localized() ? RelatedProductsShapedFixture.LOCALE_EN : null
			),
			"The ordered rows must include a single-owner partition"
		);
	}

	@Test
	@DisplayName("should never sort an unselected owner of a single-owner partition the gather handed over")
	void shouldNeverSortUnselectedOwnerOfWholeFamilyFallback() {
		// owner 3 holds target 10 alone - a registry row the gather of the selected family hands over
		assertTrue(
			this.witnessOwners.singleOwnerTargets(REFERENCE_ITEMS, Scope.LIVE).contains(10),
			"The partition of target 10 must be single-owner"
		);
		for (OrderConstraint value : new OrderConstraint[]{
			attributeNatural(ATTRIBUTE_ORDER), entityPrimaryKeyNatural(OrderDirection.ASC)
		}) {
			final OrderConstraint ordering = referenceProperty(REFERENCE_ITEMS, value);
			final List<Integer> expected = this.witnessOwners.expectedAnswer(
				ENTITY_OWNER,
				queryWitness(
					this.witnessOwners.objectCatalog(), ENTITY_OWNER,
					entityPrimaryKeyInSet(MaterializedPartitionTwin.withClones(1, 2)), ordering, 1, PAGE_SIZE
				)
			);
			final List<Integer> actual = queryWitness(
				this.witnessOwners.rowCatalog(), ENTITY_OWNER, entityPrimaryKeyInSet(1, 2), ordering, 1, 2
			);
			assertEquals(MaterializedPartitionTwin.pageOf(expected, 1, 2), actual);
			assertEquals(2, actual.size());
			assertFalse(actual.contains(3), "The unselected owner must not appear.");
		}
		// the order of the witness: owner 2 has the smaller value, both share the referenced primary key
		assertEquals(
			List.of(2, 1),
			queryWitness(
				this.witnessOwners.rowCatalog(), ENTITY_OWNER, entityPrimaryKeyInSet(1, 2),
				referenceProperty(REFERENCE_ITEMS, attributeNatural(ATTRIBUTE_ORDER)), 1, 2
			)
		);
	}

	@Test
	@DisplayName("should break ties of single-owner partitions by owner primary key on every page")
	void shouldBreakTiesByOwnerOnEveryPage() {
		final Integer[] owners = IntStream.rangeClosed(1, TIE_OWNER_COUNT).boxed().toArray(Integer[]::new);
		// every row of the tie block is a single-owner partition of its own
		assertTrue(
			this.witnessTieOwners.singleOwnerTargets(REFERENCE_ITEMS, Scope.LIVE).containsAll(
				IntStream.rangeClosed(TIE_TARGET_BASE + 1, TIE_TARGET_BASE + TIE_OWNER_COUNT).boxed().toList()
			),
			"Every partition of the tie block must be single-owner"
		);
		for (OrderDirection direction : OrderDirection.values()) {
			final List<Integer> expected = new ArrayList<>(IntStream.rangeClosed(1, TIE_OWNER_COUNT).boxed().toList());
			if (direction == OrderDirection.DESC) {
				Collections.reverse(expected);
			}
			final OrderConstraint ordering = referenceProperty(
				REFERENCE_ITEMS, attributeNatural(ATTRIBUTE_ORDER, direction)
			);
			assertEquals(
				expected,
				this.witnessTieOwners.expectedAnswer(
					ENTITY_TIE_OWNER,
					queryWitness(
						this.witnessTieOwners.objectCatalog(), ENTITY_TIE_OWNER,
						entityPrimaryKeyInSet(MaterializedPartitionTwin.withClones(owners)), ordering, 1, PAGE_SIZE
					)
				),
				"The index objects must break the ties by owner primary key"
			);
			MaterializedPartitionTwin.assertPages(
				"tie block " + direction, expected, TIE_PAGE_SIZE,
				pageNumber -> queryWitness(
					this.witnessTieOwners.rowCatalog(), ENTITY_TIE_OWNER, entityPrimaryKeyInSet(owners), ordering,
					pageNumber, TIE_PAGE_SIZE
				)
			);
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
	 * Queries the related products of the selection in the scopes of a catalog, forcing the route, and returns the
	 * primary keys of the requested page.
	 *
	 * @param catalog      the catalog
	 * @param route        the route the query is forced to take
	 * @param selection    the owners the query selects
	 * @param withClones   TRUE to name the clones next to the owners a small selection names
	 * @param scopes       the scopes
	 * @param localized    TRUE when the query needs a locale
	 * @param ordering     the ordering
	 * @param window       the page number and the page size
	 * @return the primary keys of the page in the order the query returns them
	 */
	@Nonnull
	private List<Integer> queryRelated(
		@Nonnull String catalog,
		@Nonnull Route route,
		@Nonnull Selection selection,
		boolean withClones,
		@Nonnull Scope[] scopes,
		boolean localized,
		@Nonnull OrderConstraint ordering,
		@Nonnull int[] window
	) {
		final FilterConstraint selectionConstraint = switch (selection) {
			case ALL -> null;
			case SMALL -> entityPrimaryKeyInSet(
				withClones ? MaterializedPartitionTwin.withClones(SMALL_SELECTION) : SMALL_SELECTION
			);
			case NARROWED -> referenceHaving(
				RelatedProductsShapedFixture.REFERENCE_RELATED,
				attributeEquals(
					RelatedProductsShapedFixture.ATTRIBUTE_RELATION_TYPE, RelatedProductsShapedFixture.TYPE_SPARE
				)
			);
		};
		return this.evita.queryCatalog(
			catalog,
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
	 * Queries a collection of a witness catalog on the index route.
	 *
	 * @param catalog    the catalog
	 * @param entityType the collection
	 * @param selection  the owners the query selects
	 * @param ordering   the ordering
	 * @param pageNumber the page, counted from one
	 * @param pageSize   the size of the page
	 * @return the primary keys of the page in the order the query returns them
	 */
	@Nonnull
	private List<Integer> queryWitness(
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull FilterConstraint selection,
		@Nonnull OrderConstraint ordering,
		int pageNumber,
		int pageSize
	) {
		return this.evita.queryCatalog(
			catalog,
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

}
