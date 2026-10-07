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
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.VirtualRowConsumer;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
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
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * Verifies that the block-by-block orderings by a reference - `traverseByEntityProperty` over the partitions of the
 * reference, each block sorted by an attribute, a compound or the referenced primary key - answer the same with the
 * read switch on as with it off, on the same engine and the same data: with the switch on, the planning set of the
 * ordering - the candidates of a `referenceHaving`, or the partitions of the nodes of a hierarchy filter - carries the
 * registered single-owner partitions as virtual handles and every block provider of a virtual partition is the
 * singleton read out of its registry row.
 *
 * Two catalogs, each maintained from its first write so every single-owner partition has its row:
 *
 * - {@link RelatedProductsShapedFixture} - thousands of generated owners mixing single-owner and shared partitions of
 *   `relatedProducts` (a non-hierarchical target, duplicates told apart by their representative attribute, value-less
 *   rows, a plain, a localized, a compound and a localized compound sort value) and of `sections` (a hierarchical
 *   target with single-owner nodes in both trees), both scopes;
 * - {@link ParameterValuesShapedFixture} - the parameter-values shape: nearly every partition single-owner, hundreds
 *   of rows per owner, a hierarchical target, ordered unnarrowed.
 *
 * Every case compares whole result lists and two page windows in both directions and must read virtual rows - an
 * equal answer of a run that read nothing virtually would compare the object reading with itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Read parity of traverse orderings over virtual reference partitions")
@Tag(ENGINE)
@Tag(ORDER)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class VirtualTraverseParityTest implements EvitaTestSupport {
	private static final String CATALOG_RELATED = "virtualTraverseParityRelated";
	private static final String CATALOG_PARAMETERS = "virtualTraverseParityParameters";
	private static final Scope[] LIVE = {Scope.LIVE};
	private static final Scope[] ARCHIVED = {Scope.ARCHIVED};
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	/**
	 * The page large enough to hold every entity of every catalog.
	 */
	private static final int PAGE_SIZE = 100_000;
	/**
	 * The page windows every case compares.
	 */
	private static final int[][] WINDOWS = {{1, PAGE_SIZE}, {3, 17}, {1, 5}};

	private TestPaths paths;
	private Evita evita;

	/**
	 * The owners a case selects.
	 */
	private enum Selection {
		/**
		 * Every owner of the scope - the whole family of the reference is the planning set.
		 */
		ALL,
		/**
		 * A small set of owners.
		 */
		SMALL,
		/**
		 * The owners selected by a constraint on the ordered reference itself - its candidates are the planning set.
		 */
		NARROWED
	}

	/**
	 * One block-by-block ordering of the products, run in every selection, scope set and direction.
	 *
	 * @param name      the name of the case
	 * @param localized TRUE when the case needs a locale
	 * @param narrowing the constraint narrowing the planning set of the ordered reference
	 * @param ordering  creates the ordering in the direction
	 */
	private record OrderingCase(
		@Nonnull String name,
		boolean localized,
		@Nonnull FilterConstraint narrowing,
		@Nonnull Function<OrderDirection, OrderConstraint> ordering
	) {

		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}

	}

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("VirtualTraverseParityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		build(
			CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_TYPES,
			RelatedProductsShapedFixture::setUp, RelatedProductsShapedFixture::archive
		);
		build(
			CATALOG_PARAMETERS, ParameterValuesShapedFixture.ENTITY_TYPES,
			ParameterValuesShapedFixture::setUp, session -> {
			}
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
		final String related = RelatedProductsShapedFixture.REFERENCE_RELATED;
		final String sections = RelatedProductsShapedFixture.REFERENCE_SECTIONS;
		final String order = RelatedProductsShapedFixture.ATTRIBUTE_ORDER;
		final String label = RelatedProductsShapedFixture.ATTRIBUTE_LABEL;
		final String typeOrder = RelatedProductsShapedFixture.COMPOUND_TYPE_ORDER;
		final String orderLabel = RelatedProductsShapedFixture.COMPOUND_ORDER_LABEL;
		final String rank = RelatedProductsShapedFixture.ATTRIBUTE_RANK;
		final FilterConstraint spareRows = referenceHaving(
			related,
			attributeEquals(RelatedProductsShapedFixture.ATTRIBUTE_RELATION_TYPE, RelatedProductsShapedFixture.TYPE_SPARE)
		);
		final FilterConstraint sectionRows = referenceHaving(
			sections, entityPrimaryKeyInSet(IntStream.rangeClosed(9, 62).boxed().toArray(Integer[]::new))
		);
		return Stream.of(
			new OrderingCase(
				"attribute order, targets by primary key", false, spareRows,
				direction -> referenceProperty(
					related, traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
					attributeNatural(order, direction)
				)
			),
			new OrderingCase(
				"localized attribute label, targets by primary key descending", true, spareRows,
				direction -> referenceProperty(
					related, traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.DESC)),
					attributeNatural(label, direction)
				)
			),
			new OrderingCase(
				"compound typeOrder, targets by rank", false, spareRows,
				direction -> referenceProperty(
					related, traverseByEntityProperty(attributeNatural(rank, OrderDirection.DESC)),
					attributeNatural(typeOrder, direction)
				)
			),
			new OrderingCase(
				"localized compound orderLabel, targets by primary key", true, spareRows,
				direction -> referenceProperty(
					related, traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
					attributeNatural(orderLabel, direction)
				)
			),
			new OrderingCase(
				"hierarchical target, default traversal", false, sectionRows,
				direction -> referenceProperty(sections, entityPrimaryKeyNatural(direction))
			),
			new OrderingCase(
				"hierarchical target, breadth first descending", false, sectionRows,
				direction -> referenceProperty(
					sections,
					traverseByEntityProperty(TraversalMode.BREADTH_FIRST, entityPrimaryKeyNatural(OrderDirection.DESC)),
					entityPrimaryKeyNatural(direction)
				)
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("orderingCases")
	@DisplayName("should traverse the related products identically with the read switch on")
	void shouldTraverseRelatedProductsIdentically(@Nonnull OrderingCase orderingCase) {
		final List<String> differences = new ArrayList<>(16);
		long virtualRowsRead = 0;
		for (Selection selection : Selection.values()) {
			for (Scope[] scopes : new Scope[][]{LIVE, ARCHIVED, BOTH}) {
				for (OrderDirection direction : OrderDirection.values()) {
					final OrderConstraint ordering = orderingCase.ordering().apply(direction);
					final FilterConstraint selectionConstraint = switch (selection) {
						case ALL -> null;
						case SMALL -> entityPrimaryKeyInSet(
							IntStream.iterate(3, it -> it <= RelatedProductsShapedFixture.PRODUCT_COUNT, it -> it + 97)
								.boxed()
								.toArray(Integer[]::new)
						);
						case NARROWED -> orderingCase.narrowing();
					};
					for (int[] window : WINDOWS) {
						final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
							maintenance(CATALOG_RELATED), RelatedProductsShapedFixture.ENTITY_TYPES,
							() -> queryPrimaryKeys(
								CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_PRODUCT, scopes,
								orderingCase.localized(), selectionConstraint, ordering, window
							)
						);
						if (!comparison.objectReading().equals(comparison.virtualReading().answer())) {
							differences.add(
								selection + " " + scopes.length + " scope(s) " + direction + " page " + window[0] +
									"/" + window[1] + ": " + comparison.objectReading() + " vs " +
									comparison.virtualReading().answer()
							);
						}
						virtualRowsRead += comparison.virtualReading().virtualRowsRead();
						if (window[1] == PAGE_SIZE && selection == Selection.ALL) {
							assertFalse(comparison.objectReading().isEmpty(), "The selection must not be empty.");
						}
					}
				}
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		assertTrue(virtualRowsRead > 0, "The ordering must have read virtual rows.");
	}

	@Test
	@DisplayName("should traverse identically when a hierarchy filter supplies the planning set")
	void shouldTraverseIdenticallyWithHierarchyOptionPlanningSet() {
		final FilterConstraint hierarchy = hierarchyWithinRoot(RelatedProductsShapedFixture.REFERENCE_SECTIONS);
		final List<OrderingCase> cases = orderingCases()
			.filter(it -> it.name().startsWith("hierarchical target"))
			.collect(Collectors.toCollection(ArrayList::new));
		// an ordering by another reference than the hierarchy filter names finds no planning set of its own
		cases.add(orderingCases().findFirst().orElseThrow());
		final List<String> differences = new ArrayList<>(16);
		long virtualRowsRead = 0;
		for (OrderingCase orderingCase : cases) {
			for (Scope[] scopes : new Scope[][]{LIVE, ARCHIVED, BOTH}) {
				final long consumedBefore = maintenance(CATALOG_RELATED).getVirtualRowsConsumed(
					VirtualRowConsumer.HIERARCHY_OPTION_PARTITIONS
				);
				for (OrderDirection direction : OrderDirection.values()) {
					final OrderConstraint ordering = orderingCase.ordering().apply(direction);
					for (int[] window : WINDOWS) {
						final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
							maintenance(CATALOG_RELATED), RelatedProductsShapedFixture.ENTITY_TYPES,
							() -> queryPrimaryKeys(
								CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_PRODUCT, scopes,
								orderingCase.localized(), hierarchy, ordering, window
							)
						);
						if (!comparison.objectReading().equals(comparison.virtualReading().answer())) {
							differences.add(
								orderingCase + " " + scopes.length + " scope(s) " + direction + " page " + window[0] +
									"/" + window[1] + ": " + comparison.objectReading() + " vs " +
									comparison.virtualReading().answer()
							);
						}
						virtualRowsRead += comparison.virtualReading().virtualRowsRead();
						if (window[1] == PAGE_SIZE) {
							assertFalse(comparison.objectReading().isEmpty(), "The selection must not be empty.");
						}
					}
				}
				if (scopes == BOTH && orderingCase.name().startsWith("hierarchical target")) {
					// the partitions of both scopes reach the ordering through the hierarchy option - the one route
					// on which the splits of two scopes are merged into one list
					assertTrue(
						maintenance(CATALOG_RELATED).getVirtualRowsConsumed(VirtualRowConsumer.HIERARCHY_OPTION_PARTITIONS) >
							consumedBefore,
						orderingCase + " over both scopes must hand the virtual partitions of the hierarchy option over"
					);
				}
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		assertTrue(virtualRowsRead > 0, "The ordering must have read virtual rows.");
	}

	@Test
	@DisplayName("should traverse the all-virtual parameter values shape identically, unnarrowed")
	void shouldTraverseParameterValuesShapeIdentically() {
		final List<String> differences = new ArrayList<>(16);
		long virtualRowsRead = 0;
		// the parameter values shape is live only - see ParameterValuesShapedFixture
		for (Scope[] scopes : new Scope[][]{LIVE, BOTH}) {
			for (OrderDirection direction : OrderDirection.values()) {
				for (OrderConstraint ordering : new OrderConstraint[]{
					referenceProperty(
						ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES,
						traverseByEntityProperty(TraversalMode.BREADTH_FIRST, entityPrimaryKeyNatural(direction)),
						attributeNatural(ParameterValuesShapedFixture.ATTRIBUTE_ORDER, direction)
					)
				}) {
					for (int[] window : WINDOWS) {
						final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
							maintenance(CATALOG_PARAMETERS), ParameterValuesShapedFixture.ENTITY_TYPES,
							() -> queryPrimaryKeys(
								CATALOG_PARAMETERS, ParameterValuesShapedFixture.ENTITY_PRODUCT, scopes, false, null,
								ordering, window
							)
						);
						if (!comparison.objectReading().equals(comparison.virtualReading().answer())) {
							differences.add(
								ordering + " " + scopes.length + " scope(s) page " + window[0] + "/" + window[1] +
									": " + comparison.objectReading() + " vs " + comparison.virtualReading().answer()
							);
						}
						virtualRowsRead += comparison.virtualReading().virtualRowsRead();
						if (window[1] == PAGE_SIZE) {
							assertFalse(comparison.objectReading().isEmpty(), "The selection must not be empty.");
						}
					}
				}
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		assertTrue(virtualRowsRead > 0, "The ordering must have read virtual rows.");
	}

	@Test
	@DisplayName("should never walk the registry's sort index of a key on a block route")
	void shouldNeverWalkRegistrySortIndexOnBlockRoute() {
		final int walksBefore = getSortValueWalkCount();
		final long virtualRowsRead = orderingCases()
			.filter(it -> it.name().contains("attribute") || it.name().contains("compound"))
			.mapToLong(
				orderingCase -> VirtualReadParity.readVirtually(
					maintenance(CATALOG_RELATED), RelatedProductsShapedFixture.ENTITY_TYPES,
					() -> queryPrimaryKeys(
						CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_PRODUCT, BOTH, orderingCase.localized(),
						null, orderingCase.ordering().apply(OrderDirection.ASC), WINDOWS[0]
					)
				).virtualRowsRead()
			)
			.sum();
		assertTrue(virtualRowsRead > 0, "The block routes must have read virtual rows.");
		assertEquals(walksBefore, getSortValueWalkCount(), "No block route may walk the sort index of a key.");
	}

	@Test
	@DisplayName("should order the only partition of a reference block by an exact attribute set identically")
	void shouldOrderSinglePartitionBlockByAttributeSetExactIdentically() {
		final int target = ParameterValuesShapedFixture.LONELY_PARAMETER_VALUE;
		for (OrderConstraint ordering : new OrderConstraint[]{
			referenceProperty(
				ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES,
				attributeSetExact(ParameterValuesShapedFixture.ATTRIBUTE_CODE, "z", "c0", "b")
			),
			referenceProperty(
				ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES,
				attributeSetExact(ParameterValuesShapedFixture.ATTRIBUTE_CODE, "z")
			)
		}) {
			final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
				maintenance(CATALOG_PARAMETERS), ParameterValuesShapedFixture.ENTITY_TYPES,
				() -> queryPrimaryKeys(
					CATALOG_PARAMETERS, ParameterValuesShapedFixture.ENTITY_PRODUCT, LIVE, false,
					referenceHaving(ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES, entityPrimaryKeyInSet(target)),
					ordering, WINDOWS[0]
				)
			);
			assertEquals(List.of(ParameterValuesShapedFixture.LONELY_OWNER), comparison.objectReading());
			assertEquals(comparison.objectReading(), comparison.virtualReading().answer());
			assertTrue(comparison.virtualReading().virtualRowsRead() > 0, "The virtual row must have been read.");
		}
	}

	/**
	 * Queries a collection of a catalog in the scopes on the index route and returns the primary keys of the page.
	 */
	@Nonnull
	private List<Integer> queryPrimaryKeys(
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull Scope[] scopes,
		boolean localized,
		@Nullable FilterConstraint selection,
		@Nonnull OrderConstraint ordering,
		@Nonnull int[] window
	) {
		return this.evita.queryCatalog(
			catalog,
			(Function<EvitaSessionContract, List<Integer>>) session -> session.query(
					query(
						collection(entityType),
						filterBy(
							scope(scopes),
							localized ? entityLocaleEquals(RelatedProductsShapedFixture.LOCALE_EN) : null,
							selection
						),
						orderBy(ordering),
						require(page(window[0], window[1]), debug(DebugMode.PREFER_INDEX_SCAN))
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
	 * Returns how many walks over the values of the registry's sort indexes of `relatedProducts` the registries of both
	 * scopes have started.
	 *
	 * @return the number of walks
	 */
	private int getSortValueWalkCount() {
		final EntityCollection collection = (EntityCollection) this.evita.getCatalogInstance(CATALOG_RELATED)
			.orElseThrow()
			.getCollectionForEntity(RelatedProductsShapedFixture.ENTITY_PRODUCT)
			.orElseThrow();
		int walks = 0;
		for (Scope scope : Scope.values()) {
			final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) collection.getIndexByKeyIfExists(
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY_TYPE, scope, RelatedProductsShapedFixture.REFERENCE_RELATED
				)
			);
			if (typeIndex != null) {
				walks += typeIndex.getVirtualReferencePartitionIndex().getSortValueWalkCount();
			}
		}
		return walks;
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
