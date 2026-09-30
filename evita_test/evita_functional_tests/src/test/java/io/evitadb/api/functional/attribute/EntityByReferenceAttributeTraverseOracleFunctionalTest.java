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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks `traverseByEntityProperty` ordering by a reference attribute against an oracle computed only from the rows
 * the test itself wrote, on a randomized dataset. The rules being checked are the ones pinned on a hand-built fixture
 * by {@link EntityByReferenceAttributeTraverseFunctionalTest}:
 *
 * - blocks are the targets in traversal order - a depth-first or breadth-first walk of the target tree with siblings
 *   in the requested order, or the requested order of a flat target;
 * - an owner lands in the first block whose row carries the value, among the rows the hierarchy constraints on the
 *   ordered reference admit - a `hierarchyWithin` reached through `filterBy`/`and` for every owner, one inside
 *   `inScope(S, ...)` for the owners of scope `S`, several of them as the union of their node sets, and none under
 *   `or` or `not`; a `referenceHaving` on the ordered reference never changes which rows order;
 * - rows of one target are taken in representative order (`variant`), the first one carrying the value represents
 *   the owner in the block;
 * - inside a block owners follow the value, then their primary key, both in the direction of the ordering, owners of
 *   both scopes merged;
 * - owners without a placed row, and owners outside the ordering scope, follow in ascending primary key order.
 *
 * What the dataset is shaped to reach:
 *
 * - 40 targets exist twice: as a hierarchical collection (a random tree whose every parent has a lower primary key
 *   than its children, so a depth-first walk differs from primary key order) and as a flat collection;
 * - both are referenced at both indexing levels, every row written identically into all four plain references;
 * - two more references - `treeDuplicates` (partitioning) and `flatDuplicates` (filtering) - allow several rows to one
 *   target, told apart by the representative attribute `variant`: they carry every plain row as variant `m` and, for
 *   about a third of the owners, one more row to one of the owner's targets as variant `a` (before `m`) or `z` (after
 *   it), with values of its own; the extra rows are drawn from a second random stream, so the plain rows are exactly
 *   those of the plain references;
 * - targets 1 and 2 are referenced by 150 and 80 owners, the others by a few each;
 * - every seventh owner is archived;
 * - hierarchy nodes carry a title in the query locale: `hierarchyWithin` looks its parent node up in that locale, and
 *   a node without it would select nothing;
 * - targets are walked by primary key ascending, descending, and by the target attribute `rank` through a nested
 *   sorter;
 * - the sorted value is a plain attribute (20 % missing), a localized attribute (25 % missing), the referenced primary
 *   key (never missing, so an owner lands in the block of its first row), and a compound (never missing either).
 *
 * Conventions the oracle takes from the engine rather than from a decision - none decides an outcome in dispute:
 *
 * - breadth-first lists, for every node in the order it was listed, its children in sibling order - as
 *   `HierarchyIndex#listHierarchyNodesFromRoot` and any standard breadth-first walk do;
 * - the nested sorter of `rank` appends every ninth (unranked) target after the ranked ones in ascending primary key
 *   order - its tail convention;
 * - owners no block places follow in ascending primary key order whatever the direction - the default tail when the
 *   query has no next sorter, compatible with D1;
 * - `label` is compared by `String` natural order while the engine uses a collator; the two agree for the single
 *   letters `a`-`j` the dataset writes, which is why the alphabet stays that small.
 *
 * Each test is one combination of reference, route, selection and sorted value, so a red names the combination; the
 * target orders, walks and directions are asserted inside it, each message naming its own. Every combination runs on
 * the index route and on the prefetch route, forced by `PREFER_INDEX_SCAN` and `PREFER_PREFETCHING` - and **proven**:
 * every ordered query asks for `queryTelemetry()` and asserts, next to the order, that the `EXECUTION_PREFETCH` step is
 * present on the prefetch route and absent on the index route.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Traversal ordering by a reference attribute agrees with the block rule on a randomized dataset")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@Tag(REFERENCE)
@Tag(ORDER)
@Tag(HIERARCHY)
public class EntityByReferenceAttributeTraverseOracleFunctionalTest {
	private static final String TRAVERSE_ORACLE = "traverseOracle";
	private static final String ENTITY_NODE = "traverseOracleNode";
	private static final String ENTITY_FLAT = "traverseOracleFlat";
	private static final String ENTITY_OWNER = "traverseOracleOwner";
	private static final String REFERENCE_TREE_PARTITIONING = "treePartitioning";
	private static final String REFERENCE_TREE_FILTERING = "treeFiltering";
	private static final String REFERENCE_FLAT_PARTITIONING = "flatPartitioning";
	private static final String REFERENCE_FLAT_FILTERING = "flatFiltering";
	private static final String REFERENCE_TREE_DUPLICATES = "treeDuplicates";
	private static final String REFERENCE_FLAT_DUPLICATES = "flatDuplicates";
	/**
	 * The references every plain row is written into.
	 */
	private static final String[] PLAIN_REFERENCES = {
		REFERENCE_TREE_PARTITIONING, REFERENCE_TREE_FILTERING, REFERENCE_FLAT_PARTITIONING, REFERENCE_FLAT_FILTERING
	};
	/**
	 * The references allowing several rows to one target.
	 */
	private static final String[] DUPLICATE_REFERENCES = {REFERENCE_TREE_DUPLICATES, REFERENCE_FLAT_DUPLICATES};
	private static final String[] ALL_REFERENCES = ArrayUtils.mergeArrays(PLAIN_REFERENCES, DUPLICATE_REFERENCES);
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String ATTRIBUTE_KIND = "kind";
	private static final String ATTRIBUTE_VARIANT = "variant";
	private static final String COMPOUND_ORDER_KIND = "orderKind";
	private static final String KIND_X = "x";
	/**
	 * The variant of every plain row in the duplicate-allowing references.
	 */
	private static final String VARIANT_PLAIN = "m";
	private static final int TARGET_COUNT = 40;
	/**
	 * Targets 1 to this value are roots of the tree, every other target gets a random parent with a lower primary key.
	 */
	private static final int ROOT_COUNT = 4;
	/**
	 * Targets whose primary key is a multiple of this value have no `rank`.
	 */
	private static final int UNRANKED_TARGET_MODULO = 9;
	private static final int OWNER_COUNT = 300;
	private static final int ARCHIVED_OWNER_MODULO = 7;
	private static final long SEED = 42;
	/**
	 * Seed of the second random stream, which draws the extra rows of the duplicate-allowing references.
	 */
	private static final long DUPLICATE_SEED = SEED + 1;
	/**
	 * Owners up to this primary key are the second branch of {@link Selection#HIERARCHY_UNDER_OR}.
	 */
	private static final int OR_OWNER_LIMIT = 40;
	private static final Locale LOCALE = Locale.ENGLISH;
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	/**
	 * A handful of live owners. For some target orders owner 11 holds value-less rows in targets reached before its
	 * valued one, which makes it a lone value-less member of a block (C2) - a property of seed 42 and of the target
	 * order, not of every combination; the lone-owner case is pinned on its own by the hand-built class.
	 */
	private static final int[] SMALL_SELECTION = {3, 11, 150, 151, 152, 290};
	/**
	 * The compound: `order` descending with missing values first, then `kind` ascending.
	 */
	private static final Comparator<Row> COMPOUND_COMPARATOR = Comparator
		.comparing(Row::order, Comparator.nullsFirst(Comparator.<Integer>reverseOrder()))
		.thenComparing(Row::kind, Comparator.nullsLast(Comparator.<String>naturalOrder()));

	/**
	 * One row of an owner's reference.
	 *
	 * @param target  the referenced primary key
	 * @param order   the sortable integer, `null` when the row carries none
	 * @param label   the localized sortable string, `null` when the row carries none
	 * @param kind    the filterable discriminator used by `referenceHaving`
	 * @param variant the representative attribute, written into the duplicate-allowing references only
	 */
	private record Row(
		int target,
		@Nullable Integer order,
		@Nullable String label,
		@Nonnull String kind,
		@Nonnull String variant
	) {
	}

	/**
	 * What the test wrote, the oracle's only input.
	 *
	 * @param rowsByOwner          rows of every owner in the plain references, including owners with none
	 * @param duplicateRowsByOwner rows of every owner in the duplicate-allowing references - the plain rows plus the
	 *                             extra ones
	 * @param parent               parent of each target in the tree, indexed by the target primary key, `0` for a root
	 * @param targetRank           value of the target attribute `rank`, indexed by the target primary key, `null` for
	 *                             a target without one
	 * @param archived             whether the owner, indexed by its primary key, was archived
	 * @param hierarchyNode        the node `hierarchyWithin` selections are rooted at
	 * @param secondHierarchyNode  the root of a subtree disjoint from the one of `hierarchyNode`, the second constraint
	 *                             of {@link Selection#UNION_OF_TWO_SUBTREES}
	 */
	private record OracleFixture(
		@Nonnull Map<Integer, List<Row>> rowsByOwner,
		@Nonnull Map<Integer, List<Row>> duplicateRowsByOwner,
		@Nonnull int[] parent,
		@Nonnull Integer[] targetRank,
		@Nonnull boolean[] archived,
		int hierarchyNode,
		int secondHierarchyNode
	) {
	}

	/**
	 * The owners one query returned, and what its telemetry says about the route it took.
	 *
	 * @param order          owner primary keys in result order
	 * @param routeViolation `null` when the plan took the forced route, otherwise a message saying which route it took
	 *                       instead
	 */
	private record OracleResult(@Nonnull int[] order, @Nullable String routeViolation) {
	}

	/**
	 * The route the engine takes to order the owners.
	 */
	private enum SortRoute {
		/**
		 * Sorted-records providers of the reduced indexes of each block, merged by the index sorter.
		 */
		INDEX,
		/**
		 * Entities are prefetched and ordered by the entity comparators.
		 */
		PREFETCH
	}

	/**
	 * The order of targets - of siblings in the tree, of all targets of the flat collection.
	 */
	private enum TargetOrder {
		/**
		 * `entityPrimaryKeyNatural(ASC)`.
		 */
		PRIMARY_KEY,
		/**
		 * `entityPrimaryKeyNatural(DESC)`.
		 */
		DESCENDING_PRIMARY_KEY,
		/**
		 * `attributeNatural(rank)` of the target entity, resolved by a nested sorter.
		 */
		RANK
	}

	/**
	 * The value the owners are sorted on inside a block.
	 */
	private enum SortedValue {
		/**
		 * The integer reference attribute.
		 */
		ORDER,
		/**
		 * The localized string reference attribute.
		 */
		LABEL,
		/**
		 * The referenced primary key - every row carries it.
		 */
		REFERENCED_PRIMARY_KEY,
		/**
		 * The compound `orderKind` - every row carries it, `kind` is never missing.
		 */
		COMPOUND
	}

	/**
	 * Which owners are selected, and how.
	 */
	private enum Selection {
		/**
		 * Every live owner.
		 */
		ALL_LIVE(false),
		/**
		 * {@link #SMALL_SELECTION}.
		 */
		SMALL(false),
		/**
		 * Every live owner with a row of kind `x`, selected by a `referenceHaving` on the ordered reference.
		 */
		NARROWED(false),
		/**
		 * Every live owner with a row in the subtree of the fixture's hierarchy node.
		 */
		HIERARCHY_WITHIN(true),
		/**
		 * Every live owner with a row in the subtree of the fixture's hierarchy node, the node itself excluded.
		 */
		HIERARCHY_WITHIN_EXCLUDING_ROOT(true),
		/**
		 * Every owner, live and archived.
		 */
		BOTH_SCOPES(false),
		/**
		 * Live owners with a row in the subtree of the fixture's hierarchy node - `inScope(LIVE, hierarchyWithin)` -
		 * and every archived owner, which the hierarchy constraint neither selects nor bounds. On the prefetch route of
		 * a partitioned tree reference this selection is a witness of *#1681*, a plan-choice defect of the filter
		 * fixed on its own branch before #1670: the plan built on the `REFERENCED_ENTITY` alternative of the
		 * `inScope(LIVE, ...)` hierarchy constraint loses every archived owner when `entityLocaleEquals` is in the
		 * filter, and the default planner and `PREFER_PREFETCHING` pick it by cost (`PREFER_INDEX_SCAN` picks the global
		 * one) - see {@link #shouldSelectEveryOwnerOnEveryPlan(String, boolean, EvitaSessionContract, OracleFixture)}.
		 * With `inScope` in the filter an ordered query never prefetches (#1682, outside #1670), so the PREFETCH rows
		 * of this selection assert the index route and check it under the prefetch preference.
		 */
		LIVE_HIERARCHY_WITHIN_BOTH_SCOPES(true),
		/**
		 * Every owner, live and archived, ordered by `inScope(LIVE, ...)` - archived owners follow unsorted.
		 */
		LIVE_ORDERING_BOTH_SCOPES(false),
		/**
		 * Live owners with a row in each of two disjoint subtrees, selected by two conjunctive `hierarchyWithin` on the
		 * ordered reference - the traversal is bounded by the union of the two node sets (E3).
		 */
		UNION_OF_TWO_SUBTREES(true),
		/**
		 * Live owners with a row in the subtree of the fixture's hierarchy node, or with a primary key up to
		 * {@link #OR_OWNER_LIMIT} - the hierarchy constraint sits under `or`, so it never bounds (E4).
		 */
		HIERARCHY_UNDER_OR(true),
		/**
		 * Live owners without any row in the subtree of the fixture's hierarchy node - the constraint sits under `not`,
		 * so it never bounds (E4).
		 */
		HIERARCHY_UNDER_NOT(true);

		/**
		 * Whether the selection needs a hierarchical target.
		 */
		private final boolean hierarchical;

		/**
		 * Creates the selection.
		 */
		Selection(boolean hierarchical) {
			this.hierarchical = hierarchical;
		}
	}

	/**
	 * Every reference crossed with both routes, every selection its target supports and every sorted value.
	 */
	@Nonnull
	static Stream<Arguments> oracleCombinations() {
		return Arrays.stream(ALL_REFERENCES)
			.flatMap(
				reference -> Arrays.stream(SortRoute.values())
					.flatMap(
						route -> Arrays.stream(Selection.values())
							.filter(selection -> isTree(reference) || !selection.hierarchical)
							.flatMap(
								selection -> Arrays.stream(SortedValue.values())
									.map(value -> Arguments.of(reference, route, selection, value))
							)
					)
			);
	}

	/**
	 * Every tree reference crossed with both routes and every selection that constrains the hierarchy.
	 */
	@Nonnull
	static Stream<Arguments> hierarchySelections() {
		return Stream.of(REFERENCE_TREE_PARTITIONING, REFERENCE_TREE_FILTERING, REFERENCE_TREE_DUPLICATES)
			.flatMap(
				reference -> Arrays.stream(SortRoute.values())
					.flatMap(
						route -> Arrays.stream(Selection.values())
							.filter(selection -> selection.hierarchical)
							.map(selection -> Arguments.of(reference, route, selection))
					)
			);
	}

	/**
	 * Tells whether the reference points to the hierarchical target collection.
	 */
	private static boolean isTree(@Nonnull String referenceName) {
		return REFERENCE_TREE_PARTITIONING.equals(referenceName) ||
			REFERENCE_TREE_FILTERING.equals(referenceName) ||
			REFERENCE_TREE_DUPLICATES.equals(referenceName);
	}

	/**
	 * Tells whether the reference allows several rows to one target.
	 */
	private static boolean allowsDuplicates(@Nonnull String referenceName) {
		return REFERENCE_TREE_DUPLICATES.equals(referenceName) || REFERENCE_FLAT_DUPLICATES.equals(referenceName);
	}

	/**
	 * Declares the attributes and the compound of one reference.
	 */
	private static void declareRowAttributes(@Nonnull ReferenceSchemaBuilder reference) {
		reference
			.withAttribute(
				ATTRIBUTE_ORDER, Integer.class,
				thatIs -> thatIs.filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES).nullable()
			)
			.withAttribute(
				ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().sortableInScope(BOTH_SCOPES).nullable()
			)
			.withAttribute(
				ATTRIBUTE_KIND, String.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES)
			)
			.withSortableAttributeCompound(
				COMPOUND_ORDER_KIND,
				new AttributeElement[]{
					new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
					new AttributeElement(ATTRIBUTE_KIND, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				},
				thatIs -> thatIs.indexedInScope(BOTH_SCOPES)
			);
	}

	/**
	 * Writes the values of the row into the reference being built.
	 */
	private static void writeRowValues(@Nonnull Row row, @Nonnull ReferenceBuilder whichIs) {
		whichIs.setAttribute(ATTRIBUTE_KIND, row.kind());
		if (row.order() != null) {
			whichIs.setAttribute(ATTRIBUTE_ORDER, row.order());
		}
		if (row.label() != null) {
			whichIs.setAttribute(ATTRIBUTE_LABEL, LOCALE, row.label());
		}
	}

	/**
	 * Creates the randomized targets and owners and returns what was written as the oracle's input.
	 */
	@DataSet(value = TRAVERSE_ORACLE, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_NODE)
					.withoutGeneratedPrimaryKey()
					.withHierarchyIndexedInScope(BOTH_SCOPES)
					.withLocale(LOCALE)
					.withAttribute(ATTRIBUTE_TITLE, String.class, thatIs -> thatIs.localized())
					.withAttribute(
						ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable()
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_FLAT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(
						ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable()
					)
					.updateVia(session);
				final EntitySchemaBuilder owner = session.defineEntitySchema(ENTITY_OWNER)
					.withoutGeneratedPrimaryKey()
					.withLocale(LOCALE)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized());
				owner.withReferenceToEntity(
					REFERENCE_TREE_PARTITIONING, ENTITY_NODE, Cardinality.ZERO_OR_MORE,
					whichIs -> declareRowAttributes(whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES))
				);
				owner.withReferenceToEntity(
					REFERENCE_TREE_FILTERING, ENTITY_NODE, Cardinality.ZERO_OR_MORE,
					whichIs -> declareRowAttributes(whichIs.indexedForFilteringInScope(BOTH_SCOPES))
				);
				owner.withReferenceToEntity(
					REFERENCE_FLAT_PARTITIONING, ENTITY_FLAT, Cardinality.ZERO_OR_MORE,
					whichIs -> declareRowAttributes(whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES))
				);
				owner.withReferenceToEntity(
					REFERENCE_FLAT_FILTERING, ENTITY_FLAT, Cardinality.ZERO_OR_MORE,
					whichIs -> declareRowAttributes(whichIs.indexedForFilteringInScope(BOTH_SCOPES))
				);
				owner.withReferenceToEntity(
					REFERENCE_TREE_DUPLICATES, ENTITY_NODE, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
					whichIs -> {
						declareRowAttributes(whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES));
						whichIs.withAttribute(
							ATTRIBUTE_VARIANT, String.class,
							thatIs -> thatIs.filterableInScope(BOTH_SCOPES).representative()
						);
					}
				);
				owner.withReferenceToEntity(
					REFERENCE_FLAT_DUPLICATES, ENTITY_FLAT, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
					whichIs -> {
						declareRowAttributes(whichIs.indexedForFilteringInScope(BOTH_SCOPES));
						whichIs.withAttribute(
							ATTRIBUTE_VARIANT, String.class,
							thatIs -> thatIs.filterableInScope(BOTH_SCOPES).representative()
						);
					}
				);
				owner.updateVia(session);

				final Random random = new Random(SEED);
				final int[] parent = new int[TARGET_COUNT + 1];
				final Integer[] targetRank = new Integer[TARGET_COUNT + 1];
				final List<Integer> ranks = new ArrayList<>(IntStream.rangeClosed(1, TARGET_COUNT).boxed().toList());
				Collections.shuffle(ranks, random);
				for (int target = 1; target <= TARGET_COUNT; target++) {
					final EntityBuilder node = session.createNewEntity(ENTITY_NODE, target)
						.setAttribute(ATTRIBUTE_TITLE, LOCALE, "node " + target);
					final EntityBuilder flat = session.createNewEntity(ENTITY_FLAT, target);
					if (target > ROOT_COUNT) {
						// a lower primary key, so the parent always exists already
						parent[target] = 1 + random.nextInt(target - 1);
						node.setParent(parent[target]);
					}
					if (target % UNRANKED_TARGET_MODULO != 0) {
						targetRank[target] = ranks.get(target - 1);
						node.setAttribute(ATTRIBUTE_RANK, targetRank[target]);
						flat.setAttribute(ATTRIBUTE_RANK, targetRank[target]);
					}
					node.upsertVia(session);
					flat.upsertVia(session);
				}

				// the extra rows come from a stream of their own, so the plain rows stay those of the plain references
				final Random duplicateRandom = new Random(DUPLICATE_SEED);
				final Map<Integer, List<Row>> rowsByOwner = CollectionUtils.createHashMap(OWNER_COUNT);
				final Map<Integer, List<Row>> duplicateRowsByOwner = CollectionUtils.createHashMap(OWNER_COUNT);
				final boolean[] archived = new boolean[OWNER_COUNT + 1];
				for (int ownerPk = 1; ownerPk <= OWNER_COUNT; ownerPk++) {
					final List<Row> rows = new ArrayList<>(6);
					if (ownerPk % 10 != 0) {
						// targets 1 and 2 hold many owners
						if (ownerPk <= 150) {
							rows.add(randomRow(random, 1, VARIANT_PLAIN));
						}
						if (ownerPk <= 80) {
							rows.add(randomRow(random, 2, VARIANT_PLAIN));
						}
						final int extra = random.nextInt(4);
						for (int i = 0; i < extra; i++) {
							final int target = 3 + random.nextInt(TARGET_COUNT - 2);
							if (rows.stream().noneMatch(it -> it.target() == target)) {
								rows.add(randomRow(random, target, VARIANT_PLAIN));
							}
						}
					}
					rowsByOwner.put(ownerPk, rows);
					final List<Row> duplicateRows = new ArrayList<>(rows);
					if (!rows.isEmpty() && duplicateRandom.nextInt(3) == 0) {
						final int target = rows.get(duplicateRandom.nextInt(rows.size())).target();
						duplicateRows.add(
							randomRow(duplicateRandom, target, duplicateRandom.nextBoolean() ? "a" : "z")
						);
					}
					duplicateRowsByOwner.put(ownerPk, duplicateRows);

					final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, ownerPk)
						.setAttribute(ATTRIBUTE_NAME, LOCALE, "owner " + ownerPk);
					for (String referenceName : PLAIN_REFERENCES) {
						for (Row row : rows) {
							builder.setReference(referenceName, row.target(), whichIs -> writeRowValues(row, whichIs));
						}
					}
					for (String referenceName : DUPLICATE_REFERENCES) {
						for (Row row : duplicateRows) {
							builder.setOrUpdateReference(
								referenceName, row.target(), Functions.alwaysFalse(),
								whichIs -> {
									whichIs.setAttribute(ATTRIBUTE_VARIANT, row.variant());
									writeRowValues(row, whichIs);
								}
							);
						}
					}
					builder.upsertVia(session);
				}
				for (int ownerPk = ARCHIVED_OWNER_MODULO; ownerPk <= OWNER_COUNT; ownerPk += ARCHIVED_OWNER_MODULO) {
					session.archiveEntity(ENTITY_OWNER, ownerPk);
					archived[ownerPk] = true;
				}
				final int hierarchyNode = pickHierarchyNode(parent);
				return new DataCarrier(
					"traverseOracleFixture",
					new OracleFixture(
						rowsByOwner, duplicateRowsByOwner, parent, targetRank, archived, hierarchyNode,
						pickSecondHierarchyNode(parent, hierarchyNode)
					)
				);
			}
		);
	}

	/**
	 * Creates a row with random values; small value ranges make ties frequent.
	 */
	@Nonnull
	private static Row randomRow(@Nonnull Random random, int target, @Nonnull String variant) {
		return new Row(
			target,
			random.nextInt(5) == 0 ? null : random.nextInt(12),
			random.nextInt(4) == 0 ? null : String.valueOf((char) ('a' + random.nextInt(10))),
			random.nextBoolean() ? KIND_X : "y",
			variant
		);
	}

	/**
	 * Picks the non-root node with the largest subtree of fewer than 15 nodes - large enough to hold several blocks,
	 * small enough to leave most rows outside it.
	 */
	private static int pickHierarchyNode(@Nonnull int[] parent) {
		int best = ROOT_COUNT + 1;
		int bestSize = 0;
		for (int target = ROOT_COUNT + 1; target <= TARGET_COUNT; target++) {
			final int size = subtree(parent, target).size();
			if (size > bestSize && size < 15) {
				best = target;
				bestSize = size;
			}
		}
		return best;
	}

	/**
	 * Picks the root with the lowest primary key whose subtree does not contain the passed node - a subtree disjoint
	 * from the node's one, and one holding target 1 or 2, which most owners reference.
	 */
	private static int pickSecondHierarchyNode(@Nonnull int[] parent, int hierarchyNode) {
		for (int root = 1; root <= ROOT_COUNT; root++) {
			if (!subtree(parent, root).contains(hierarchyNode)) {
				return root;
			}
		}
		throw new IllegalStateException("Every root contains node " + hierarchyNode + "!");
	}

	/**
	 * Returns the node and all its descendants.
	 */
	@Nonnull
	private static Set<Integer> subtree(@Nonnull int[] parent, int node) {
		final Set<Integer> result = CollectionUtils.createHashSet(TARGET_COUNT);
		result.add(node);
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int target = 1; target <= TARGET_COUNT; target++) {
				if (!result.contains(target) && result.contains(parent[target])) {
					result.add(target);
					changed = true;
				}
			}
		}
		return result;
	}

	/**
	 * Checks the rule "Should select the owners the oracle selects with a hierarchy constraint".
	 */
	@DisplayName("Should select the owners the oracle selects with a hierarchy constraint")
	@UseDataSet(TRAVERSE_ORACLE)
	@ParameterizedTest(name = "{0} via {1}, {2}")
	@MethodSource("hierarchySelections")
	void shouldSelectOwnersAsTheOracleDoes(
		String referenceName,
		SortRoute route,
		Selection selection,
		EvitaSessionContract session,
		OracleFixture traverseOracleFixture
	) {
		// guards the fixture: a hierarchy filter selecting nothing (a node without the query locale, say) would make
		// every ordering assertion below trivially agree; no ordering is involved, so a difference between the routes
		// here is a filtering defect, not an ordering one
		final int[] expected = Arrays.stream(
				oracle(
					traverseOracleFixture, referenceName, selection, TargetOrder.PRIMARY_KEY, TraversalMode.DEPTH_FIRST,
					SortedValue.REFERENCED_PRIMARY_KEY, OrderDirection.ASC
				)
			)
			.sorted()
			.toArray();
		final OracleResult result = queryOrder(session, route, selection, referenceName, traverseOracleFixture, null);
		final int[] actual = Arrays.stream(result.order()).sorted().toArray();
		// the route is reported, not asserted: without an ordering the prefetch depends on the filter alone
		final String routeTaken = result.routeViolation() == null ?
			"the forced route was taken" : "the forced route was NOT taken (" + result.routeViolation() + ")";
		assertAll(
			() -> assertTrue(
				expected.length >= 10, () -> "the oracle selects too few owners: " + Arrays.toString(expected)
			),
			() -> assertArrayEquals(
				expected, actual, () -> "selected " + Arrays.toString(actual) + "; " + routeTaken
			)
		);
	}

	/**
	 * Both partitioned tree references crossed with the default planner and with every alternative plan verified.
	 */
	@Nonnull
	static Stream<Arguments> partitionedTreePlans() {
		return Stream.of(REFERENCE_TREE_PARTITIONING, REFERENCE_TREE_DUPLICATES)
			.flatMap(reference -> Stream.of(Arguments.of(reference, false), Arguments.of(reference, true)));
	}

	/**
	 * Checks the rule "Should select every owner of the inScope hierarchy filter on every plan" - the witness of #1681.
	 *
	 * The filter of {@link Selection#LIVE_HIERARCHY_WITHIN_BOTH_SCOPES} - `scope(LIVE, ARCHIVED)`,
	 * `entityLocaleEquals`, `inScope(LIVE, hierarchyWithin(R, node))` - must select the live owners with a row in the
	 * subtree and every archived owner (122 owners, 42 of them archived, on seed 42). Today the plan built on the
	 * `REFERENCED_ENTITY` alternative of the hierarchy constraint - which holds the live partitions only - loses the
	 * archived owners (80 owners on `treePartitioning`, 84 on `treeDuplicates`), and the default planner picks that
	 * plan because it is cheaper; the plan on the global indexes is right. So the witness runs without any debug mode
	 * (what a production query gets) and with `VERIFY_ALTERNATIVE_INDEX_RESULTS`, which executes every alternative and
	 * must not report them as inconsistent. Without `entityLocaleEquals` both plans agree today.
	 */
	@DisplayName("Should select every owner of the inScope hierarchy filter on every plan (#1681)")
	@UseDataSet(TRAVERSE_ORACLE)
	@ParameterizedTest(name = "{0}, verify alternatives: {1}")
	@MethodSource("partitionedTreePlans")
	void shouldSelectEveryOwnerOnEveryPlan(
		String referenceName,
		boolean verifyAlternatives,
		EvitaSessionContract session,
		OracleFixture traverseOracleFixture
	) {
		final int[] expected = Arrays.stream(
				oracle(
					traverseOracleFixture, referenceName, Selection.LIVE_HIERARCHY_WITHIN_BOTH_SCOPES,
					TargetOrder.PRIMARY_KEY, TraversalMode.DEPTH_FIRST, SortedValue.REFERENCED_PRIMARY_KEY,
					OrderDirection.ASC
				)
			)
			.sorted()
			.toArray();
		final FilterConstraint[] filter = ArrayUtils.mergeArrays(
			new FilterConstraint[]{
				entityLocaleEquals(LOCALE),
				entityPrimaryKeyInSet(IntStream.rangeClosed(1, OWNER_COUNT).boxed().toArray(Integer[]::new))
			},
			selectionFilter(Selection.LIVE_HIERARCHY_WITHIN_BOTH_SCOPES, referenceName, traverseOracleFixture)
		);
		final int[] actual = assertDoesNotThrow(
			() -> session.query(
					query(
						collection(ENTITY_OWNER),
						filterBy(filter),
						require(
							page(1, OWNER_COUNT),
							verifyAlternatives ? debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS) : null
						)
					),
					EntityReference.class
				)
				.getRecordData()
				.stream()
				.mapToInt(EntityReference::getPrimaryKey)
				.sorted()
				.toArray(),
			"#1681: the alternative plans of the filter must agree"
		);
		final long expectedArchived = Arrays.stream(expected).filter(it -> traverseOracleFixture.archived()[it]).count();
		final long actualArchived = Arrays.stream(actual).filter(it -> traverseOracleFixture.archived()[it]).count();
		assertAll(
			() -> assertTrue(
				expected.length >= 100 && expectedArchived >= 40,
				() -> "the oracle must select the archived owners too, but selects " + expected.length +
					" owners, " + expectedArchived + " of them archived"
			),
			() -> assertArrayEquals(
				expected, actual,
				() -> "#1681: expected " + expected.length + " owners (" + expectedArchived + " archived) but got " +
					actual.length + " (" + actualArchived + " archived)"
			)
		);
	}

	/**
	 * Checks the rule "Should order owners as the oracle does".
	 */
	@DisplayName("Should order owners as the oracle does")
	@UseDataSet(TRAVERSE_ORACLE)
	@ParameterizedTest(name = "{0} via {1}, {2}, value {3}")
	@MethodSource("oracleCombinations")
	void shouldOrderOwnersAsTheOracleDoes(
		String referenceName,
		SortRoute route,
		Selection selection,
		SortedValue sortedValue,
		EvitaSessionContract session,
		OracleFixture traverseOracleFixture
	) {
		final TraversalMode[] walks = isTree(referenceName) ?
			TraversalMode.values() : new TraversalMode[]{TraversalMode.DEPTH_FIRST};
		final List<Executable> assertions = new ArrayList<>(TargetOrder.values().length * walks.length * 2);
		for (TargetOrder targetOrder : TargetOrder.values()) {
			for (TraversalMode walk : walks) {
				for (OrderDirection direction : OrderDirection.values()) {
					final String label = "targets by " + targetOrder + (isTree(referenceName) ? " " + walk : "") +
						", " + direction;
					final int[] expected = oracle(
						traverseOracleFixture, referenceName, selection, targetOrder, walk, sortedValue, direction
					);
					final OrderConstraint ordering = createOrdering(
						referenceName, targetOrder, walk, sortedValue, direction
					);
					assertions.add(
						() -> {
							final OracleResult actual = queryOrder(
								session, route, selection, referenceName, traverseOracleFixture, ordering
							);
							assertAll(
								() -> assertNull(
									actual.routeViolation(), () -> label + " - " + actual.routeViolation()
								),
								() -> assertArrayEquals(
									expected, actual.order(),
									() -> label + ": expected " + Arrays.toString(expected) + " but was " +
										Arrays.toString(actual.order())
								)
							);
						}
					);
				}
			}
		}
		assertAll(assertions);
	}

	/**
	 * Creates the `referenceProperty` ordering: the traversal specification, then the value.
	 */
	@Nonnull
	private static OrderConstraint createOrdering(
		@Nonnull String referenceName,
		@Nonnull TargetOrder targetOrder,
		@Nonnull TraversalMode walk,
		@Nonnull SortedValue sortedValue,
		@Nonnull OrderDirection direction
	) {
		final OrderConstraint targetOrdering = switch (targetOrder) {
			case PRIMARY_KEY -> entityPrimaryKeyNatural(OrderDirection.ASC);
			case DESCENDING_PRIMARY_KEY -> entityPrimaryKeyNatural(OrderDirection.DESC);
			case RANK -> attributeNatural(ATTRIBUTE_RANK);
		};
		final OrderConstraint valueOrdering = switch (sortedValue) {
			case ORDER -> attributeNatural(ATTRIBUTE_ORDER, direction);
			case LABEL -> attributeNatural(ATTRIBUTE_LABEL, direction);
			case REFERENCED_PRIMARY_KEY -> entityPrimaryKeyNatural(direction);
			case COMPOUND -> attributeNatural(COMPOUND_ORDER_KIND, direction);
		};
		return referenceProperty(referenceName, traverseByEntityProperty(walk, targetOrdering), valueOrdering);
	}

	/**
	 * Returns the filter of the selection - the part after the locale and the primary key set.
	 */
	@Nonnull
	private static FilterConstraint[] selectionFilter(
		@Nonnull Selection selection,
		@Nonnull String referenceName,
		@Nonnull OracleFixture fixture
	) {
		final FilterConstraint withinNode = hierarchyWithin(
			referenceName, entityPrimaryKeyInSet(fixture.hierarchyNode())
		);
		return switch (selection) {
			case ALL_LIVE, SMALL -> new FilterConstraint[0];
			case NARROWED -> new FilterConstraint[]{
				referenceHaving(referenceName, attributeEquals(ATTRIBUTE_KIND, KIND_X))
			};
			case HIERARCHY_WITHIN -> new FilterConstraint[]{withinNode};
			case HIERARCHY_WITHIN_EXCLUDING_ROOT -> new FilterConstraint[]{
				hierarchyWithin(referenceName, entityPrimaryKeyInSet(fixture.hierarchyNode()), excludingRoot())
			};
			case BOTH_SCOPES, LIVE_ORDERING_BOTH_SCOPES -> new FilterConstraint[]{scope(BOTH_SCOPES)};
			case LIVE_HIERARCHY_WITHIN_BOTH_SCOPES -> new FilterConstraint[]{
				scope(BOTH_SCOPES), inScope(Scope.LIVE, withinNode)
			};
			case UNION_OF_TWO_SUBTREES -> new FilterConstraint[]{
				withinNode, hierarchyWithin(referenceName, entityPrimaryKeyInSet(fixture.secondHierarchyNode()))
			};
			case HIERARCHY_UNDER_OR -> new FilterConstraint[]{
				or(
					withinNode,
					entityPrimaryKeyInSet(IntStream.rangeClosed(1, OR_OWNER_LIMIT).boxed().toArray(Integer[]::new))
				)
			};
			case HIERARCHY_UNDER_NOT -> new FilterConstraint[]{not(withinNode)};
		};
	}

	/**
	 * Runs the query of the selection on the route and returns the owner primary keys in result order, together with
	 * the route the plan took (proven from the `EXECUTION_PREFETCH` telemetry step); a `null` ordering leaves the
	 * result in the engine's default order.
	 */
	@Nonnull
	private static OracleResult queryOrder(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nonnull Selection selection,
		@Nonnull String referenceName,
		@Nonnull OracleFixture fixture,
		@Nullable OrderConstraint ordering
	) {
		final Integer[] owners = selection == Selection.SMALL ?
			Arrays.stream(SMALL_SELECTION).boxed().toArray(Integer[]::new) :
			IntStream.rangeClosed(1, OWNER_COUNT).boxed().toArray(Integer[]::new);
		final FilterConstraint[] filter = ArrayUtils.mergeArrays(
			new FilterConstraint[]{entityLocaleEquals(LOCALE), entityPrimaryKeyInSet(owners)},
			selectionFilter(selection, referenceName, fixture)
		);
		final OrderConstraint theOrdering = ordering != null && selection == Selection.LIVE_ORDERING_BOTH_SCOPES ?
			inScope(Scope.LIVE, ordering) : ordering;
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_OWNER),
				filterBy(filter),
				theOrdering == null ? null : orderBy(theOrdering),
				require(
					page(1, OWNER_COUNT),
					queryTelemetry(),
					debug(route == SortRoute.INDEX ? DebugMode.PREFER_INDEX_SCAN : DebugMode.PREFER_PREFETCHING)
				)
			),
			EntityReference.class
		);
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		assertNotNull(telemetry, "Query telemetry must be present - it is the only proof of the route taken!");
		final boolean prefetched = containsPhase(telemetry, QueryPhase.EXECUTION_PREFETCH);
		// a filter holding inScope(...) never prefetches - #1682, an enhancement outside #1670 - so its PREFETCH rows
		// are expected on the index route and check that route under the prefetch preference
		final boolean inScopeFilter = selection == Selection.LIVE_HIERARCHY_WITHIN_BOTH_SCOPES;
		final String routeViolation;
		if (route == SortRoute.PREFETCH && inScopeFilter && prefetched) {
			routeViolation = "route check: a filter holding inScope(...) was expected to stay on the index route " +
				"(#1682) but the plan prefetched - update the rows #1682 pins";
		} else if (route == SortRoute.PREFETCH && !inScopeFilter && !prefetched) {
			routeViolation = "route check: PREFER_PREFETCHING was forced but the plan did not prefetch (no " +
				"EXECUTION_PREFETCH step) - this case ran on the index route";
		} else if (route == SortRoute.INDEX && prefetched) {
			routeViolation = "route check: PREFER_INDEX_SCAN was forced but the plan prefetched (EXECUTION_PREFETCH " +
				"step present) - this case ran on the prefetch route";
		} else {
			routeViolation = null;
		}
		return new OracleResult(
			response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).toArray(),
			routeViolation
		);
	}

	/**
	 * Walks the telemetry tree looking for a step of the given phase.
	 */
	private static boolean containsPhase(@Nonnull QueryTelemetry telemetry, @Nonnull QueryPhase phase) {
		if (telemetry.getOperation() == phase) {
			return true;
		}
		for (QueryTelemetry step : telemetry.getSteps()) {
			if (containsPhase(step, phase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the comparator of the targets - of siblings in the tree, of all targets of the flat collection. The
	 * nested sorter of `rank` appends the unranked targets after the ranked ones in ascending primary key order.
	 */
	@Nonnull
	private static Comparator<Integer> targetComparator(@Nonnull OracleFixture fixture, @Nonnull TargetOrder order) {
		return switch (order) {
			case PRIMARY_KEY -> Comparator.naturalOrder();
			case DESCENDING_PRIMARY_KEY -> Comparator.reverseOrder();
			case RANK -> Comparator.comparingInt(
				target -> fixture.targetRank()[target] == null ? TARGET_COUNT + target : fixture.targetRank()[target]
			);
		};
	}

	/**
	 * Returns the children of the node in sibling order.
	 */
	@Nonnull
	private static List<Integer> children(
		@Nonnull OracleFixture fixture,
		int node,
		@Nonnull Comparator<Integer> siblingOrder
	) {
		return IntStream.rangeClosed(1, TARGET_COUNT)
			.filter(target -> fixture.parent()[target] == node)
			.boxed()
			.sorted(siblingOrder)
			.toList();
	}

	/**
	 * Returns the targets in block order. A tree is walked from its roots: depth-first visits each node's subtree
	 * before its next sibling; breadth-first appends, for every node in the order it was listed, its children in
	 * sibling order - one level after another, as `HierarchyIndex#listHierarchyNodesFromRoot` does.
	 */
	@Nonnull
	private static List<Integer> blockOrder(
		@Nonnull OracleFixture fixture,
		boolean tree,
		@Nonnull TargetOrder targetOrder,
		@Nonnull TraversalMode walk
	) {
		final Comparator<Integer> siblingOrder = targetComparator(fixture, targetOrder);
		if (!tree) {
			return IntStream.rangeClosed(1, TARGET_COUNT).boxed().sorted(siblingOrder).toList();
		}
		final List<Integer> roots = children(fixture, 0, siblingOrder);
		final List<Integer> result = new ArrayList<>(TARGET_COUNT);
		if (walk == TraversalMode.DEPTH_FIRST) {
			for (int root : roots) {
				depthFirst(fixture, root, siblingOrder, result);
			}
		} else {
			result.addAll(roots);
			for (int i = 0; i < result.size(); i++) {
				result.addAll(children(fixture, result.get(i), siblingOrder));
			}
		}
		return result;
	}

	/**
	 * Lists the node and then its subtree depth-first.
	 */
	private static void depthFirst(
		@Nonnull OracleFixture fixture,
		int node,
		@Nonnull Comparator<Integer> siblingOrder,
		@Nonnull List<Integer> result
	) {
		result.add(node);
		for (int child : children(fixture, node, siblingOrder)) {
			depthFirst(fixture, child, siblingOrder, result);
		}
	}

	/**
	 * Returns the predicate telling whether a row carries the sorted value.
	 */
	@Nonnull
	private static Predicate<Row> carriesValue(@Nonnull SortedValue sortedValue) {
		return switch (sortedValue) {
			case ORDER -> row -> row.order() != null;
			case LABEL -> row -> row.label() != null;
			case REFERENCED_PRIMARY_KEY, COMPOUND -> row -> true;
		};
	}

	/**
	 * Returns the ascending comparator of rows carrying the sorted value.
	 */
	@Nonnull
	private static Comparator<Row> valueComparator(@Nonnull SortedValue sortedValue) {
		return switch (sortedValue) {
			case ORDER -> Comparator.comparing(Row::order);
			case LABEL -> Comparator.comparing(Row::label);
			case REFERENCED_PRIMARY_KEY -> Comparator.comparingInt(Row::target);
			case COMPOUND -> COMPOUND_COMPARATOR;
		};
	}

	/**
	 * An owner placed in a block by one of its rows.
	 *
	 * @param owner the owner primary key
	 * @param block the position of the block in block order
	 * @param row   the row that placed the owner
	 */
	private record Placement(int owner, int block, @Nonnull Row row) {
	}

	/**
	 * Tells whether the owner is selected by the filter of the selection - which rows the traversal may use is a
	 * separate question, answered by the bound.
	 */
	private static boolean isSelected(
		@Nonnull Selection selection,
		int owner,
		boolean archived,
		@Nonnull List<Row> rows,
		@Nullable Set<Integer> nodeSet,
		@Nonnull Set<Integer> subtree,
		@Nonnull Set<Integer> secondSubtree
	) {
		final Predicate<Set<Integer>> hasRowIn = nodes -> rows.stream().anyMatch(it -> nodes.contains(it.target()));
		return switch (selection) {
			case ALL_LIVE, SMALL, BOTH_SCOPES, LIVE_ORDERING_BOTH_SCOPES -> true;
			case NARROWED -> rows.stream().anyMatch(it -> KIND_X.equals(it.kind()));
			case HIERARCHY_WITHIN, HIERARCHY_WITHIN_EXCLUDING_ROOT -> hasRowIn.test(nodeSet);
			// the hierarchy constraint holds for live owners only when it sits in `inScope(LIVE, ...)`
			case LIVE_HIERARCHY_WITHIN_BOTH_SCOPES -> archived || hasRowIn.test(nodeSet);
			// each constraint is its own existential - one row in each subtree
			case UNION_OF_TWO_SUBTREES -> hasRowIn.test(subtree) && hasRowIn.test(secondSubtree);
			case HIERARCHY_UNDER_OR -> hasRowIn.test(subtree) || owner <= OR_OWNER_LIMIT;
			case HIERARCHY_UNDER_NOT -> !hasRowIn.test(subtree);
		};
	}

	/**
	 * Computes the expected order from the rows the test wrote.
	 */
	@Nonnull
	private static int[] oracle(
		@Nonnull OracleFixture fixture,
		@Nonnull String referenceName,
		@Nonnull Selection selection,
		@Nonnull TargetOrder targetOrder,
		@Nonnull TraversalMode walk,
		@Nonnull SortedValue sortedValue,
		@Nonnull OrderDirection direction
	) {
		final List<Integer> blocks = blockOrder(fixture, isTree(referenceName), targetOrder, walk);
		final int[] blockPosition = new int[TARGET_COUNT + 1];
		for (int i = 0; i < blocks.size(); i++) {
			blockPosition[blocks.get(i)] = i;
		}
		final Set<Integer> subtree = subtree(fixture.parent(), fixture.hierarchyNode());
		final Set<Integer> secondSubtree = subtree(fixture.parent(), fixture.secondHierarchyNode());
		// the node set bounding the traversal - null where no hierarchy constraint holds for every owner (E4)
		final Set<Integer> nodeSet = switch (selection) {
			case HIERARCHY_WITHIN, LIVE_HIERARCHY_WITHIN_BOTH_SCOPES -> subtree;
			case HIERARCHY_WITHIN_EXCLUDING_ROOT -> {
				final Set<Integer> withoutRoot = CollectionUtils.createHashSet(subtree.size());
				withoutRoot.addAll(subtree);
				withoutRoot.remove(fixture.hierarchyNode());
				yield withoutRoot;
			}
			case UNION_OF_TWO_SUBTREES -> {
				final Set<Integer> union = CollectionUtils.createHashSet(subtree.size() + secondSubtree.size());
				union.addAll(subtree);
				union.addAll(secondSubtree);
				yield union;
			}
			case ALL_LIVE, SMALL, NARROWED, BOTH_SCOPES, LIVE_ORDERING_BOTH_SCOPES, HIERARCHY_UNDER_OR,
				HIERARCHY_UNDER_NOT -> null;
		};
		final boolean bothScopes = selection == Selection.BOTH_SCOPES ||
			selection == Selection.LIVE_HIERARCHY_WITHIN_BOTH_SCOPES ||
			selection == Selection.LIVE_ORDERING_BOTH_SCOPES;
		final Predicate<Row> carriesValue = carriesValue(sortedValue);
		final int[] candidates = selection == Selection.SMALL ?
			SMALL_SELECTION : IntStream.rangeClosed(1, OWNER_COUNT).toArray();
		final Map<Integer, List<Row>> rowsByOwner = allowsDuplicates(referenceName) ?
			fixture.duplicateRowsByOwner() : fixture.rowsByOwner();

		final List<Placement> placed = new ArrayList<>(candidates.length);
		final List<Integer> unplaced = new ArrayList<>(candidates.length);
		for (int owner : candidates) {
			final boolean archived = fixture.archived()[owner];
			final List<Row> rows = rowsByOwner.get(owner);
			if (
				!(bothScopes || !archived) ||
					!isSelected(selection, owner, archived, rows, nodeSet, subtree, secondSubtree)
			) {
				continue;
			}
			final Set<Integer> bound = selection == Selection.LIVE_HIERARCHY_WITHIN_BOTH_SCOPES && archived ?
				null : nodeSet;
			final boolean ordered = !(selection == Selection.LIVE_ORDERING_BOTH_SCOPES && archived);
			// rows in representative order; among the rows of one target the first valued one represents the owner
			Placement placement = null;
			if (ordered) {
				final List<Row> rowsInVariantOrder = rows.stream()
					.sorted(Comparator.comparing(Row::variant))
					.toList();
				for (Row row : rowsInVariantOrder) {
					if (carriesValue.test(row) && (bound == null || bound.contains(row.target()))) {
						final int block = blockPosition[row.target()];
						if (placement == null || block < placement.block()) {
							placement = new Placement(owner, block, row);
						}
					}
				}
			}
			if (placement == null) {
				unplaced.add(owner);
			} else {
				placed.add(placement);
			}
		}

		final Comparator<Placement> byValue = Comparator.comparing(Placement::row, valueComparator(sortedValue));
		final Comparator<Placement> byOwner = Comparator.comparingInt(Placement::owner);
		placed.sort(
			Comparator.comparingInt(Placement::block)
				.thenComparing(direction == OrderDirection.ASC ? byValue : byValue.reversed())
				.thenComparing(direction == OrderDirection.ASC ? byOwner : byOwner.reversed())
		);
		return IntStream.concat(
				placed.stream().mapToInt(Placement::owner),
				unplaced.stream().mapToInt(Integer::intValue).sorted()
			)
			.toArray();
	}

}
