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
import java.util.List;
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
 * reference, each block sorted by an attribute, a compound, a chain, an exact attribute set or the referenced primary
 * key - order the owners of single-owner partitions kept as registry rows exactly as they order them when every
 * partition is an index object: the planning set of the ordering - the candidates of a `referenceHaving`, or the
 * partitions of the nodes of a hierarchy filter - carries the rows as virtual handles, and every block provider of a
 * row is the singleton read out of the registry.
 *
 * Every catalog is built twice in one engine, as the {@link MaterializedPartitionTwin} describes - the row catalog as
 * the fixture builds it, and the object catalog with every product cloned. The whole order of the object catalog,
 * mapped back to the originals, is the expected order; the row catalog answers real page requests, each of which must
 * be the matching slice of it. The fixtures:
 *
 * - {@link RelatedProductsShapedFixture} - thousands of generated owners mixing single-owner and shared partitions of
 *   `relatedProducts` (a non-hierarchical target, duplicates told apart by their representative attribute, value-less
 *   rows, a plain, a localized, a compound and a localized compound sort value) and of `sections` (a hierarchical
 *   target with single-owner nodes in both trees), both scopes;
 * - {@link ParameterValuesShapedFixture} - the parameter-values shape: nearly every partition single-owner, hundreds
 *   of rows per owner, a hierarchical target, ordered unnarrowed - by the chain the products of a value form, whose
 *   order through the object catalog `VirtualChainParityTest` proves equal to the order the bodies give.
 *
 * Every case compares the whole order and two page windows in both directions and must select a single-owner
 * partition of the row catalog - an equal answer of a run that read no row would compare the index objects with
 * themselves.
 *
 * The stages consuming rows on these routes, and what shows that the class reads each of them: a case goes red when
 * the stage stops reading rows correctly for
 *
 * - the attribute, compound and chain providers of a block (`AttributeNaturalTranslator`);
 * - the referenced primary key providers of a block (`EntityPrimaryKeyNaturalTranslator`);
 * - the partitions of the nodes of a hierarchy option (`ReferencePropertyTranslator`).
 *
 * Exact attribute sets are also resolved block by block, across all representative keys and scopes of each
 * target. The several-block exact witness reads listed values from registry rows and compares every page window
 * with materialized partitions; the narrowed witness retains the released referenceHaving query shape.
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
	 * The twin of {@link #CATALOG_PARAMETERS}, cloning its products.
	 */
	private MaterializedPartitionTwin parameters;

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
		MaterializedPartitionTwin.createRowCatalog(
			this.evita, CATALOG_RELATED, RelatedProductsShapedFixture::setUp, RelatedProductsShapedFixture::archive
		);
		this.related = MaterializedPartitionTwin.create(
			this.evita, CATALOG_RELATED, RelatedProductsShapedFixture::setUp, RelatedProductsShapedFixture::archive,
			RelatedProductsShapedFixture.ENTITY_PRODUCT,
			RelatedProductsShapedFixture.REFERENCE_RELATED, RelatedProductsShapedFixture.REFERENCE_SECTIONS
		);
		MaterializedPartitionTwin.createRowCatalog(
			this.evita, CATALOG_PARAMETERS, ParameterValuesShapedFixture::setUp, session -> {
			}
		);
		this.parameters = MaterializedPartitionTwin.create(
			this.evita, CATALOG_PARAMETERS, ParameterValuesShapedFixture::setUp, session -> {
			},
			ParameterValuesShapedFixture.ENTITY_PRODUCT, ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES
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
	@DisplayName("should traverse the related products from the rows as from the index objects")
	void shouldTraverseRelatedProductsIdentically(@Nonnull OrderingCase orderingCase) {
		final List<String> differences = new ArrayList<>(16);
		for (Selection selection : Selection.values()) {
			for (Scope[] scopes : new Scope[][]{LIVE, ARCHIVED, BOTH}) {
				for (OrderDirection direction : OrderDirection.values()) {
					final OrderConstraint ordering = orderingCase.ordering().apply(direction);
					final List<Integer> expected = this.related.expectedAnswer(
						RelatedProductsShapedFixture.ENTITY_PRODUCT,
						queryPrimaryKeys(
							this.related.objectCatalog(), RelatedProductsShapedFixture.ENTITY_PRODUCT, scopes,
							orderingCase.localized(), selectionOf(selection, orderingCase, true), ordering, WINDOWS[0]
						)
					);
					if (selection == Selection.ALL) {
						assertFalse(expected.isEmpty(), "The selection must not be empty.");
					}
					compareWindows(
						this.related.rowCatalog(), RelatedProductsShapedFixture.ENTITY_PRODUCT, scopes,
						orderingCase.localized(), selectionOf(selection, orderingCase, false), ordering, expected,
						selection + " " + scopes.length + " scope(s) " + direction, differences
					);
				}
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		final String referenceName = orderingCase.name().startsWith("hierarchical target") ?
			RelatedProductsShapedFixture.REFERENCE_SECTIONS : RelatedProductsShapedFixture.REFERENCE_RELATED;
		assertTrue(
			this.related.selectsSingleOwnerRow(
				BOTH, referenceName, null, orderingCase.localized() ? RelatedProductsShapedFixture.LOCALE_EN : null
			),
			"The traversed rows must include a single-owner partition"
		);
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
		for (OrderingCase orderingCase : cases) {
			for (Scope[] scopes : new Scope[][]{LIVE, ARCHIVED, BOTH}) {
				for (OrderDirection direction : OrderDirection.values()) {
					final OrderConstraint ordering = orderingCase.ordering().apply(direction);
					final List<Integer> expected = this.related.expectedAnswer(
						RelatedProductsShapedFixture.ENTITY_PRODUCT,
						queryPrimaryKeys(
							this.related.objectCatalog(), RelatedProductsShapedFixture.ENTITY_PRODUCT, scopes,
							orderingCase.localized(), hierarchy, ordering, WINDOWS[0]
						)
					);
					assertFalse(expected.isEmpty(), "The selection must not be empty.");
					compareWindows(
						this.related.rowCatalog(), RelatedProductsShapedFixture.ENTITY_PRODUCT, scopes,
						orderingCase.localized(), hierarchy, ordering, expected,
						orderingCase + " " + scopes.length + " scope(s) " + direction, differences
					);
				}
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		// the nodes of both trees hold single-owner partitions the hierarchy option hands over as rows
		for (Scope scope : Scope.values()) {
			assertFalse(
				this.related.singleOwnerTargets(RelatedProductsShapedFixture.REFERENCE_SECTIONS, scope).isEmpty(),
				"The sections of " + scope + " must hold single-owner partitions"
			);
		}
	}

	@Test
	@DisplayName("should traverse the parameter values shape by the chain of the products of a value identically")
	void shouldTraverseParameterValuesShapeIdentically() {
		final List<String> differences = new ArrayList<>(16);
		// the parameter values shape is live only - see ParameterValuesShapedFixture
		for (Scope[] scopes : new Scope[][]{LIVE, BOTH}) {
			for (OrderDirection direction : OrderDirection.values()) {
				final OrderConstraint ordering = referenceProperty(
					ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES,
					traverseByEntityProperty(TraversalMode.BREADTH_FIRST, entityPrimaryKeyNatural(direction)),
					attributeNatural(ParameterValuesShapedFixture.ATTRIBUTE_ORDER, direction)
				);
				assertParameterValuesOrder(scopes, ordering, scopes.length + " scope(s) " + direction, differences);
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		assertTrue(
			this.parameters.selectsSingleOwnerRow(
				LIVE, ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES, null, null
			),
			"The traversed rows must include a single-owner partition"
		);
	}

	@Test
	@DisplayName("should order the only partition of a reference block by an exact attribute set identically")
	void shouldOrderSinglePartitionBlockByAttributeSetExactIdentically() {
		final int target = ParameterValuesShapedFixture.LONELY_PARAMETER_VALUE;
		assertTrue(
			this.parameters.singleOwnerTargets(ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES, Scope.LIVE)
				.contains(target),
			"The lonely value must be a single-owner partition"
		);
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
			final FilterConstraint selection = referenceHaving(
				ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES, entityPrimaryKeyInSet(target)
			);
			final List<Integer> expected = this.parameters.expectedAnswer(
				ParameterValuesShapedFixture.ENTITY_PRODUCT,
				queryPrimaryKeys(
					this.parameters.objectCatalog(), ParameterValuesShapedFixture.ENTITY_PRODUCT, LIVE, false,
					selection, ordering, WINDOWS[0]
				)
			);
			assertEquals(List.of(ParameterValuesShapedFixture.LONELY_OWNER), expected);
			assertEquals(
				expected,
				queryPrimaryKeys(
					this.parameters.rowCatalog(), ParameterValuesShapedFixture.ENTITY_PRODUCT, LIVE, false, selection,
					ordering, WINDOWS[0]
				)
			);
		}
	}

	/**
	 * Exact ordering reads virtual rows over several blocks, with unlisted values left for later blocks.
	 */
	@Test
	void shouldTraverseSeveralExactBlocksIdentically() {
		final List<String> differences = new ArrayList<>(8);
		final OrderConstraint ordering = referenceProperty(
			ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES,
			traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
			attributeSetExact(ParameterValuesShapedFixture.ATTRIBUTE_CODE, "c3", "s2", "z")
		);
		assertParameterValuesOrder(LIVE, ordering, "Exact ordering over several blocks", differences);
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		assertTrue(
			this.parameters.selectsSingleOwnerRow(
				LIVE, ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES, null, null
			), "Exact ordering must read virtual partitions"
		);
	}

	/**
	 * Compares the order of every product of the parameter values shape in the scopes with the order the index objects
	 * give, in every page window.
	 *
	 * @param scopes      the scopes
	 * @param ordering    the ordering
	 * @param label       names the comparison in a difference
	 * @param differences receives the differences
	 */
	private void assertParameterValuesOrder(
		@Nonnull Scope[] scopes,
		@Nonnull OrderConstraint ordering,
		@Nonnull String label,
		@Nonnull List<String> differences
	) {
		final List<Integer> expected = this.parameters.expectedAnswer(
			ParameterValuesShapedFixture.ENTITY_PRODUCT,
			queryPrimaryKeys(
				this.parameters.objectCatalog(), ParameterValuesShapedFixture.ENTITY_PRODUCT, scopes, false, null,
				ordering, WINDOWS[0]
			)
		);
		assertFalse(expected.isEmpty(), "The selection must not be empty.");
		compareWindows(
			this.parameters.rowCatalog(), ParameterValuesShapedFixture.ENTITY_PRODUCT, scopes, false, null, ordering,
			expected, ordering + " " + label, differences
		);
	}

	/**
	 * Requests every page window from the row catalog and records each one that is not the matching slice of the
	 * expected order.
	 *
	 * @param catalog     the row catalog
	 * @param entityType  the collection
	 * @param scopes      the scopes
	 * @param localized   TRUE when the query needs a locale
	 * @param selection   the selection, `null` for every entity of the scopes
	 * @param ordering    the ordering
	 * @param expected    the expected whole order
	 * @param label       names the comparison in a difference
	 * @param differences receives the differences
	 */
	private void compareWindows(
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull Scope[] scopes,
		boolean localized,
		@Nullable FilterConstraint selection,
		@Nonnull OrderConstraint ordering,
		@Nonnull List<Integer> expected,
		@Nonnull String label,
		@Nonnull List<String> differences
	) {
		for (int[] window : WINDOWS) {
			final List<Integer> actual = queryPrimaryKeys(
				catalog, entityType, scopes, localized, selection, ordering, window
			);
			final List<Integer> expectedPage = MaterializedPartitionTwin.pageOf(expected, window[0], window[1]);
			if (!expectedPage.equals(actual)) {
				differences.add(
					label + " page " + window[0] + "/" + window[1] + ": " + expectedPage + " vs " + actual
				);
			}
		}
	}

	/**
	 * Returns the selection constraint of a case.
	 *
	 * @param selection    the selection
	 * @param orderingCase the case
	 * @param withClones   TRUE to name the clones next to the owners a small selection names
	 * @return the constraint, `null` for every owner
	 */
	@Nullable
	private static FilterConstraint selectionOf(
		@Nonnull Selection selection,
		@Nonnull OrderingCase orderingCase,
		boolean withClones
	) {
		return switch (selection) {
			case ALL -> null;
			case SMALL -> entityPrimaryKeyInSet(
				withClones ? MaterializedPartitionTwin.withClones(SMALL_SELECTION) : SMALL_SELECTION
			);
			case NARROWED -> orderingCase.narrowing();
		};
	}

	/**
	 * Queries a collection of a catalog in the scopes on the index route and returns the primary keys of the page.
	 *
	 * @param catalog    the catalog
	 * @param entityType the collection
	 * @param scopes     the scopes
	 * @param localized  TRUE when the query needs a locale
	 * @param selection  the selection, `null` for every entity of the scopes
	 * @param ordering   the ordering
	 * @param window     the page number and the page size
	 * @return the primary keys of the page in the order the query returns them
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

}
