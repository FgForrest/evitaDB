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

import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.Not;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the hierarchy routes of a reference answer from the registry rows of single-owner partitions exactly
 * what they answer from index objects holding the same data: `hierarchyWithin` / `hierarchyWithinRoot` of a reference
 * - both the branch index selection prepares and the one computed when the constraint is nested where index selection
 * does not reach - and the statistics `hierarchyOfReference` counts per node, to which a single-owner partition of a
 * node contributes its owner read out of the registry.
 *
 * The catalog is {@link RelatedProductsShapedFixture}, whose `sections` reference places products in a live and an
 * archived tree of sections where roughly half of the partitions of the nodes are single-owner - singleton leaves, an
 * inner node and a root held by one product each - next to shared ones. It is built twice in one engine, as the
 * {@link MaterializedPartitionTwin} describes: the row catalog as the fixture builds it, and the object catalog with
 * every product cloned. The products the object catalog answers, mapped back to the originals, are what the row catalog
 * must answer; every statistic counting the products of a node counts twice as many there - the original and its
 * clone - and is compared halved.
 *
 * Every case runs in the scope sets it admits, must match an owner and must reach a single-owner partition in at least
 * one of them: its answer holds a product holding a single-owner partition of a node, its complement does for a negated
 * filter, or its statistics count a single-owner node. An equal answer of a case reaching none would compare the index
 * objects with themselves. A product primary key a case names names the clone of the product too in the object
 * catalog.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Read parity of the hierarchy routes over virtual reference partitions")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class VirtualHierarchyParityTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualHierarchyParity";
	private static final String SECTIONS = RelatedProductsShapedFixture.REFERENCE_SECTIONS;
	private static final Scope[] LIVE = {Scope.LIVE};
	private static final Scope[] ARCHIVED = {Scope.ARCHIVED};
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	private static final Scope[][] SINGLE_SCOPES = {LIVE, ARCHIVED};
	/**
	 * The page large enough to hold every product, clones included.
	 */
	private static final int PAGE_SIZE = 100_000;

	private TestPaths paths;
	private Evita evita;
	/**
	 * The twin of the catalog, cloning its products.
	 */
	private MaterializedPartitionTwin twin;

	/**
	 * A parity case - a filter, optionally a hierarchy requirement, over the products, compared in each of its scope
	 * sets.
	 *
	 * @param name        the name of the case
	 * @param scopeSets   the scope sets the case runs in
	 * @param require     the hierarchy requirement, `null` for a filter-only case
	 * @param constraints the filter constraints following the scope
	 */
	private record ParityCase(
		@Nonnull String name,
		@Nonnull Scope[][] scopeSets,
		@Nullable RequireConstraint require,
		@Nonnull FilterConstraint... constraints
	) {

		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}

	}

	/**
	 * The answer of one query - the matching products and the hierarchy computed for them.
	 *
	 * @param primaryKeys the primary keys of the matching products, in primary key order
	 * @param hierarchy   the hierarchy extra result, `null` when the query asked for none
	 */
	private record Answer(@Nonnull List<Integer> primaryKeys, @Nullable Hierarchy hierarchy) {
	}

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("VirtualHierarchyParityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		MaterializedPartitionTwin.createRowCatalog(
			this.evita, CATALOG, RelatedProductsShapedFixture::setUp, RelatedProductsShapedFixture::archive
		);
		this.twin = MaterializedPartitionTwin.create(
			this.evita, CATALOG, RelatedProductsShapedFixture::setUp, RelatedProductsShapedFixture::archive,
			RelatedProductsShapedFixture.ENTITY_PRODUCT, SECTIONS
		);
	}

	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Returns the cases of `hierarchyWithin` / `hierarchyWithinRoot` in the filter. The parent filters name a node of
	 * each tree, so the case reads the live tree in the live scope and the archived one in the archived scope.
	 *
	 * @return the cases
	 */
	@Nonnull
	static Stream<ParityCase> filterCases() {
		final int sharedLiveInner = RelatedProductsShapedFixture.SHARED_LIVE_INNER_SECTION;
		final int singletonLiveInner = RelatedProductsShapedFixture.SINGLETON_LIVE_INNER_SECTION;
		final int archivedRoot = RelatedProductsShapedFixture.ARCHIVED_SECTION_ROOT;
		final int sharedArchivedInner = RelatedProductsShapedFixture.SHARED_ARCHIVED_INNER_SECTION;
		final int singletonArchivedInner = RelatedProductsShapedFixture.SINGLETON_ARCHIVED_INNER_SECTION;
		return Stream.of(
			new ParityCase(
				"hierarchyWithin a root", SINGLE_SCOPES, null,
				hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(1, archivedRoot))
			),
			new ParityCase(
				"hierarchyWithin a root over both scopes", new Scope[][]{BOTH}, null,
				hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(1, archivedRoot))
			),
			new ParityCase(
				"hierarchyWithin excluding a subtree", SINGLE_SCOPES, null,
				hierarchyWithin(
					SECTIONS, entityPrimaryKeyInSet(1, archivedRoot),
					excluding(entityPrimaryKeyInSet(3, sharedArchivedInner))
				)
			),
			new ParityCase(
				"hierarchyWithin excluding by attribute", SINGLE_SCOPES, null,
				hierarchyWithin(
					SECTIONS, entityPrimaryKeyInSet(1, archivedRoot),
					excluding(
						attributeInSet(RelatedProductsShapedFixture.ATTRIBUTE_CODE, "S9", "S12", "S16", "S54", "S56")
					)
				)
			),
			new ParityCase(
				"hierarchyWithin excluding the root", SINGLE_SCOPES, null,
				hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(2, archivedRoot), excludingRoot())
			),
			new ParityCase(
				"hierarchyWithin directRelation of a singleton inner node", SINGLE_SCOPES, null,
				hierarchyWithin(
					SECTIONS, entityPrimaryKeyInSet(singletonLiveInner, singletonArchivedInner), directRelation()
				)
			),
			new ParityCase(
				"hierarchyWithin directRelation of a shared and a singleton inner node", SINGLE_SCOPES, null,
				hierarchyWithin(
					SECTIONS,
					entityPrimaryKeyInSet(
						sharedLiveInner, singletonLiveInner, sharedArchivedInner, singletonArchivedInner
					),
					directRelation()
				)
			),
			new ParityCase(
				"hierarchyWithinRoot", SINGLE_SCOPES, null,
				hierarchyWithinRoot(SECTIONS)
			),
			new ParityCase(
				"hierarchyWithinRoot over both scopes", new Scope[][]{BOTH}, null,
				hierarchyWithinRoot(SECTIONS)
			),
			new ParityCase(
				"hierarchyWithinRoot excluding a subtree", SINGLE_SCOPES, null,
				hierarchyWithinRoot(SECTIONS, excluding(entityPrimaryKeyInSet(1, sharedArchivedInner)))
			),
			new ParityCase(
				"hierarchyWithin directRelation of the roots", SINGLE_SCOPES, null,
				hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(1, 2, archivedRoot), directRelation())
			),
			new ParityCase(
				"hierarchyWithin and an attribute", SINGLE_SCOPES, null,
				hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(1, archivedRoot)),
				attributeLessThan(RelatedProductsShapedFixture.ATTRIBUTE_RANK, 10_000)
			),
			// nested where index selection does not reach - the computed branch
			new ParityCase(
				"hierarchyWithin inside or", SINGLE_SCOPES, null,
				or(
					hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(3, singletonArchivedInner)),
					entityPrimaryKeyInSet(1, 2)
				)
			),
			new ParityCase(
				"hierarchyWithin directRelation inside or", SINGLE_SCOPES, null,
				or(
					hierarchyWithin(
						SECTIONS, entityPrimaryKeyInSet(singletonLiveInner, singletonArchivedInner), directRelation()
					),
					entityPrimaryKeyInSet(1)
				)
			),
			new ParityCase(
				"hierarchyWithinRoot excluding inside not", SINGLE_SCOPES, null,
				not(hierarchyWithinRoot(SECTIONS, excluding(entityPrimaryKeyInSet(1, sharedArchivedInner))))
			)
		);
	}

	/**
	 * Returns the cases of `hierarchyOfReference` statistics. Each runs in one scope - the requirement admits no other.
	 *
	 * @return the cases
	 */
	@Nonnull
	static Stream<ParityCase> statisticsCases() {
		final int singletonLiveInner = RelatedProductsShapedFixture.SINGLETON_LIVE_INNER_SECTION;
		final int archivedRoot = RelatedProductsShapedFixture.ARCHIVED_SECTION_ROOT;
		final int singletonArchivedInner = RelatedProductsShapedFixture.SINGLETON_ARCHIVED_INNER_SECTION;
		final FilterConstraint withinInnerNode = hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(3, singletonArchivedInner));
		final FilterConstraint lowRank = userFilter(
			attributeLessThan(RelatedProductsShapedFixture.ATTRIBUTE_RANK, 20_000)
		);
		return Stream.of(
			new ParityCase(
				"fromRoot with counts, empty nodes left", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
					fromRoot("tree", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))
				)
			),
			new ParityCase(
				"fromRoot with counts, empty nodes removed", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
					fromRoot("tree", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))
				)
			),
			new ParityCase(
				"fromRoot over a user filter, both statistics bases", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
					fromRoot(
						"complete",
						statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.QUERIED_ENTITY_COUNT)
					),
					fromRoot(
						"withoutUserFilter",
						statistics(StatisticsBase.WITHOUT_USER_FILTER, StatisticsType.QUERIED_ENTITY_COUNT)
					)
				),
				lowRank
			),
			new ParityCase(
				"children, parents and siblings of an inner node", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
					children(
						"children", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
					),
					parents(
						"parents",
						siblings(statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
						statistics(StatisticsType.QUERIED_ENTITY_COUNT)
					),
					siblings("siblings", statistics(StatisticsType.QUERIED_ENTITY_COUNT))
				),
				withinInnerNode
			),
			new ParityCase(
				"children of an inner node over a user filter", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
					children(
						"children",
						statistics(StatisticsBase.WITHOUT_USER_FILTER, StatisticsType.QUERIED_ENTITY_COUNT)
					),
					fromRoot(
						"tree", stopAt(distance(1)),
						statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.QUERIED_ENTITY_COUNT)
					)
				),
				withinInnerNode, lowRank
			),
			new ParityCase(
				"children of a singleton inner node", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
					children("children", statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
					parents("parents", statistics(StatisticsType.QUERIED_ENTITY_COUNT))
				),
				hierarchyWithin(
					SECTIONS, entityPrimaryKeyInSet(singletonLiveInner, archivedRoot)
				)
			),
			new ParityCase(
				"siblings of a singleton inner node", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
					siblings("siblings", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))
				),
				hierarchyWithin(
					SECTIONS,
					entityPrimaryKeyInSet(singletonLiveInner, singletonArchivedInner)
				)
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("filterCases")
	@DisplayName("hierarchyWithin answers from the rows the owners it answers from the index objects")
	void shouldAnswerHierarchyWithinTheSame(@Nonnull ParityCase parityCase) {
		assertParity(parityCase);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("statisticsCases")
	@DisplayName("hierarchyOfReference counts from the rows the owners it counts from the index objects")
	void shouldCountHierarchyStatisticsTheSame(@Nonnull ParityCase parityCase) {
		assertParity(parityCase);
	}

	@Test
	@DisplayName("the statistics alone count the singleton nodes from their rows")
	void shouldReadTheSingletonNodesInTheStatistics() {
		// no hierarchy constraint in the filter, so every row is reached by the statistics alone
		for (final Scope[] scopes : SINGLE_SCOPES) {
			final RequireConstraint statistics = hierarchyOfReference(
				SECTIONS, EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
				fromRoot("tree", statistics(StatisticsType.QUERIED_ENTITY_COUNT))
			);
			final Answer rows = queryProducts(this.twin.rowCatalog(), scopes, statistics);
			assertEquals(expectedAnswer(scopes, statistics), describe(rows, false));
			final Hierarchy hierarchy = rows.hierarchy();
			assertNotNull(hierarchy);
			// the singleton root of each tree holds exactly its one owner and keeps its node only through it
			final int singletonRoot = scopes[0] == Scope.LIVE ?
				RelatedProductsShapedFixture.SINGLETON_LIVE_ROOT : RelatedProductsShapedFixture.ARCHIVED_SECTION_ROOT;
			assertTrue(
				this.twin.singleOwnerTargets(SECTIONS, scopes[0]).contains(singletonRoot),
				"The root " + singletonRoot + " must be a single-owner partition in " + Arrays.toString(scopes)
			);
			assertTrue(
				hierarchy.getReferenceHierarchy(SECTIONS, "tree")
					.stream()
					.anyMatch(it -> it.entity().getPrimaryKey() == singletonRoot &&
						it.queriedEntityCount() != null && it.queriedEntityCount() > 0),
				"The singleton root " + singletonRoot + " must be counted in " + Arrays.toString(scopes)
			);
		}
	}

	/**
	 * Asserts a case answers from the rows what it answers from the index objects in each of its scope sets, that
	 * some scope set matched an owner and that the case reaches a single-owner partition - see the class
	 * documentation.
	 *
	 * @param parityCase the case
	 */
	@SuppressWarnings("NonShortCircuitBooleanExpression")
	private void assertParity(@Nonnull ParityCase parityCase) {
		boolean matchedAny = false;
		boolean reachedRows = false;
		final boolean negated = Arrays.stream(parityCase.constraints()).anyMatch(Not.class::isInstance);
		for (final Scope[] scopes : parityCase.scopeSets()) {
			final Answer rows = queryProducts(
				this.twin.rowCatalog(), scopes, parityCase.require(), parityCase.constraints()
			);
			assertEquals(
				expectedAnswer(scopes, parityCase.require(), parityCase.constraints()), describe(rows, false),
				parityCase + " in " + Arrays.toString(scopes) + " must answer from the rows what it answers from the " +
					"index objects"
			);
			matchedAny |= !rows.primaryKeys().isEmpty();
			final Set<Integer> rowOwners = ownersOfSingleOwnerPartitions(scopes);
			if (negated) {
				final Set<Integer> excluded = new TreeSet<>(rowOwners);
				rows.primaryKeys().forEach(excluded::remove);
				reachedRows |= !excluded.isEmpty();
			} else {
				reachedRows |= rows.primaryKeys().stream().anyMatch(rowOwners::contains);
			}
			reachedRows |= countsSingleOwnerNode(rows.hierarchy(), scopes);
		}
		assertTrue(matchedAny, parityCase + " must match an owner in at least one scope set");
		assertTrue(reachedRows, parityCase + " must reach a single-owner partition in at least one scope set");
	}

	/**
	 * Returns what the row catalog must answer for a query: the answer of the object catalog with every clone mapped
	 * back to its original and every count of products halved, described.
	 *
	 * @param scopes      the scopes
	 * @param require     the hierarchy requirement, `null` for none
	 * @param constraints the filter constraints following the scope
	 * @return the description of the expected answer
	 */
	@Nonnull
	private String expectedAnswer(
		@Nonnull Scope[] scopes,
		@Nullable RequireConstraint require,
		@Nonnull FilterConstraint... constraints
	) {
		final FilterConstraint[] objectConstraints = new FilterConstraint[constraints.length];
		for (int i = 0; i < constraints.length; i++) {
			objectConstraints[i] = withClones(constraints[i]);
		}
		final Answer objects = queryProducts(this.twin.objectCatalog(), scopes, require, objectConstraints);
		return describe(
			new Answer(
				this.twin.expectedAnswer(RelatedProductsShapedFixture.ENTITY_PRODUCT, objects.primaryKeys()),
				objects.hierarchy()
			),
			true
		);
	}

	/**
	 * Returns a filter as the object catalog must read it: a product primary key it names - directly or inside a
	 * logical container - names the clone of the product too. Primary keys inside a hierarchy constraint name sections,
	 * which are not cloned, and stay as they are.
	 *
	 * @param constraint the filter of the row catalog
	 * @return the filter of the object catalog
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	private static FilterConstraint withClones(@Nonnull FilterConstraint constraint) {
		if (constraint instanceof EntityPrimaryKeyInSet primaryKeys) {
			return entityPrimaryKeyInSet(
				MaterializedPartitionTwin.withClones(
					Arrays.stream(primaryKeys.getPrimaryKeys()).boxed().toArray(Integer[]::new)
				)
			);
		} else if (constraint instanceof And || constraint instanceof Or || constraint instanceof Not) {
			final ConstraintContainer<FilterConstraint> container = (ConstraintContainer<FilterConstraint>) constraint;
			final FilterConstraint[] children = container.getChildren();
			final FilterConstraint[] cloned = new FilterConstraint[children.length];
			for (int i = 0; i < children.length; i++) {
				cloned[i] = withClones(children[i]);
			}
			return (FilterConstraint) container.getCopyWithNewChildren(cloned, container.getAdditionalChildren());
		} else {
			return constraint;
		}
	}

	/**
	 * Describes an answer: the matching products and, per output of the hierarchy of `sections`, every node with its
	 * flags and counts, depth first.
	 *
	 * @param answer the answer
	 * @param halve  TRUE to halve every count of products - each must be even
	 * @return the description
	 */
	@Nonnull
	private static String describe(@Nonnull Answer answer, boolean halve) {
		final StringBuilder description = new StringBuilder(1024).append(answer.primaryKeys()).append('\n');
		if (answer.hierarchy() != null) {
			final Map<String, List<LevelInfo>> outputs = new TreeMap<>(
				answer.hierarchy().getReferenceHierarchy(SECTIONS)
			);
			for (final Map.Entry<String, List<LevelInfo>> output : outputs.entrySet()) {
				description.append(output.getKey()).append(":\n");
				describe(output.getValue(), halve, 1, description);
			}
		}
		return description.toString();
	}

	/**
	 * Describes the nodes of one level of a hierarchy output and their children.
	 *
	 * @param nodes       the nodes
	 * @param halve       TRUE to halve every count of products - each must be even
	 * @param depth       the depth of the level, indenting it
	 * @param description receives the description
	 */
	private static void describe(
		@Nonnull List<LevelInfo> nodes,
		boolean halve,
		int depth,
		@Nonnull StringBuilder description
	) {
		for (final LevelInfo node : nodes) {
			final Integer count = node.queriedEntityCount();
			if (halve && count != null) {
				assertEquals(0, count % 2, "The object catalog must count every clone with its original: " + node);
			}
			description.append("  ".repeat(depth))
				.append(node.entity().getPrimaryKey())
				.append(node.requested() ? " requested" : "")
				.append(" products=").append(count == null ? null : halve ? count / 2 : count)
				.append(" children=").append(node.childrenCount())
				.append('\n');
			describe(node.children(), halve, depth + 1, description);
		}
	}

	/**
	 * Returns the products of the scopes that hold a single-owner partition of `sections` - a filter matching such a
	 * product may reach it through a registry row of the row catalog.
	 *
	 * @param scopes the scopes
	 * @return the primary keys of the products
	 */
	@Nonnull
	private Set<Integer> ownersOfSingleOwnerPartitions(@Nonnull Scope[] scopes) {
		final Set<Integer> result = new TreeSet<>();
		for (final Scope scope : scopes) {
			final Set<Integer> singleOwnerSections = this.twin.singleOwnerTargets(SECTIONS, scope);
			for (int product = 1; product <= RelatedProductsShapedFixture.PRODUCT_COUNT; product++) {
				if (
					RelatedProductsShapedFixture.isArchived(product) == (scope == Scope.ARCHIVED) &&
						Arrays.stream(RelatedProductsShapedFixture.sectionsOf(product)).anyMatch(singleOwnerSections::contains)
				) {
					result.add(product);
				}
			}
		}
		return result;
	}

	/**
	 * Tells whether the statistics of an answer count the products of a single-owner node of the scopes.
	 *
	 * @param hierarchy the hierarchy of the answer, `null` when it asked for none
	 * @param scopes    the scopes
	 * @return TRUE when a single-owner node is counted
	 */
	private boolean countsSingleOwnerNode(@Nullable Hierarchy hierarchy, @Nonnull Scope[] scopes) {
		if (hierarchy == null) {
			return false;
		}
		final Set<Integer> singleOwnerSections = new TreeSet<>();
		for (final Scope scope : scopes) {
			singleOwnerSections.addAll(this.twin.singleOwnerTargets(SECTIONS, scope));
		}
		for (final List<LevelInfo> output : hierarchy.getReferenceHierarchy(SECTIONS).values()) {
			if (countsAny(output, singleOwnerSections)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tells whether a level of a hierarchy output or a level below it counts the products of one of the nodes.
	 *
	 * @param nodes    the nodes of the level
	 * @param sections the nodes looked for
	 * @return TRUE when one of them is counted
	 */
	private static boolean countsAny(@Nonnull List<LevelInfo> nodes, @Nonnull Set<Integer> sections) {
		for (final LevelInfo node : nodes) {
			if (
				sections.contains(node.entity().getPrimaryKey()) && node.queriedEntityCount() != null &&
					node.queriedEntityCount() > 0
			) {
				return true;
			}
			if (countsAny(node.children(), sections)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Queries the products of a catalog matching the constraints in the scopes, with the hierarchy requirement when
	 * there is one.
	 *
	 * @param catalog     the catalog
	 * @param scopes      the scopes
	 * @param require     the hierarchy requirement, `null` for none
	 * @param constraints the filter constraints following the scope
	 * @return the matching products and the hierarchy computed for them
	 */
	@Nonnull
	private Answer queryProducts(
		@Nonnull String catalog,
		@Nonnull Scope[] scopes,
		@Nullable RequireConstraint require,
		@Nonnull FilterConstraint... constraints
	) {
		final FilterConstraint[] filter = new FilterConstraint[constraints.length + 1];
		filter[0] = scope(scopes);
		System.arraycopy(constraints, 0, filter, 1, constraints.length);
		return this.evita.queryCatalog(
			catalog,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(RelatedProductsShapedFixture.ENTITY_PRODUCT),
						filterBy(filter),
						// every alternative index set and every cacheable subtree must answer the same
						require == null ?
							require(
								page(1, PAGE_SIZE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							) :
							require(
								page(1, PAGE_SIZE), require,
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
					),
					EntityReference.class
				);
				return new Answer(
					response.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					require == null ? null : response.getExtraResult(Hierarchy.class)
				);
			}
		);
	}

}
