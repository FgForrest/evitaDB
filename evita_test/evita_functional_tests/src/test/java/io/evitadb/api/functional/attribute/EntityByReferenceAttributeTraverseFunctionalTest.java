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
import io.evitadb.api.query.filter.FilterInScope;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.query.indexSelection.TargetIndexes.EligibilityObstacle;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the semantics of `traverseByEntityProperty` ordering by a reference attribute on a small hand-built dataset
 * where every expected order can be derived by reading the fixture. The rules (#1670):
 *
 * 1. the targets are visited in traversal order - a depth-first or breadth-first walk of the target hierarchy with
 *    siblings in the requested order, or the requested order of a flat target - and each target is one **block**;
 * 2. an owner lands in the first block whose row **carries the value**; a value-less row passes the owner on to a
 *    later block, and an owner with no valued row is handed to the next sorter;
 * 3. inside a block owners follow the value, then their primary key, both in the direction of the ordering - owners
 *    of both scopes merged, rows sharing one target represented by the first valued row in representative order;
 * 4. a `hierarchyWithin` / `hierarchyWithinRoot` on the ordered reference that holds for every returned owner (reached
 *    through `filterBy`, `and` and - for the owners of its scope - `inScope`, never through `or` or `not`) bounds the
 *    traversal to its resolved node set, several such constraints to the union of their node sets; a
 *    `referenceHaving` on the ordered reference only selects owners and never changes which rows order them;
 * 5. only the owners of the ordering's scopes are sorted; a target tree is walked per target scope, live tree first;
 *    a chain attribute is the exception to the merge of rule 3 - the live chain of a target precedes its archived
 *    chain;
 * 6. several child sorters apply block by block - inside a block the first sorts the owners carrying its value, the
 *    next the rest - and an owner no block places is handed to the next sorter of the query.
 *
 * Rule 4 applies to `pickFirstByEntityProperty` as well - its witnesses live here, next to the hierarchy they need.
 *
 * Each scenario runs on two routes the engine can take to the same answer: the index route, where the sorted-records
 * providers of the reduced indexes of each block are merged, and the prefetch route, where the fetched references are
 * compared entity by entity. Both routes must agree with each other and with the rules, in both reference indexing
 * levels. **The route is proven, not assumed:** every ordered query asks for `queryTelemetry()` and asserts, as an
 * assertion of its own next to the order, that the `EXECUTION_PREFETCH` step is present on the prefetch route and
 * absent on the index route. A `PREFER_PREFETCHING` query that cannot prefetch (the planner prefetches only when some
 * constraint registered entity content to fetch) is therefore reported as a failed *route check*, never silently
 * run on the index route. The one exception is a filter holding `inScope(...)`: the prefetch route does not support
 * it yet (#1682, an enhancement outside #1670), so such a PREFETCH row asserts that the plan stays on the index route
 * and its order assertion checks the index route under the prefetch preference. Filter-only selection checks force
 * the same preference but do not assert the route: the prefetch of a query without an ordering depends on its filter
 * alone.
 *
 * **Witnesses of the extra defects found by P0 and folded into #1670** - their assertions name them in the failure
 * message, so a red that outlives C1-C6 is not read as a failure of the rule it sits next to:
 *
 * - *extra defect 1* - a traversal ordered by a target attribute throws "node not present in the index" when a row
 *   points to a target of the other scope's tree (`shouldWalkLiveTargetTreeBeforeArchivedOne`);
 * - *extra defect 2* - the prefetch compound comparator has no owner primary key tie-break, owned by P4
 *   (`shouldOrderByCompoundInsideBlock`).
 *
 * Two issues found by P0 are tracked outside #1670: *#1681* - for `scope(LIVE, ARCHIVED), entityLocaleEquals(...),
 * inScope(LIVE, hierarchyWithin(R, ...))` on a partitioned reference the plan built on the `REFERENCED_ENTITY`
 * alternative of the hierarchy constraint returns no archived owner, and the default planner picks it by cost; this
 * fixture's filters carry no locale, so its witnesses live in `EntityByReferenceAttributeTraverseOracleFunctionalTest`
 * - and *#1682* - the prefetch route does not support a filter holding `inScope(...)` (see the route check above).
 *
 * **Targets.** Category (hierarchical, `rank` for a nested target order, `visible` for hierarchy modifiers):
 *
 * ```
 * 1 (rank 10)                 2 (rank 20)
 * ├── 4 (rank 20)             ├── 3 (rank 20)
 * │   ├── 6 (rank 10)         └── 8 (rank 10)
 * │   └── 7 (rank 20, hidden)     └── 9 (no rank)
 * └── 5 (rank 10)
 * 10 (archived root, referenced only by `categoriesAcrossScopes`)
 * ```
 *
 * | traversal                    | block order         |
 * |------------------------------|---------------------|
 * | depth-first, primary key     | 1 4 6 7 5 2 3 8 9   |
 * | breadth-first, primary key   | 1 2 4 5 3 8 6 7 9   |
 * | depth-first, primary key DESC| 2 8 9 3 1 5 4 7 6   |
 * | depth-first, rank            | 1 5 4 6 7 2 8 9 3   |
 * | breadth-first, rank          | 1 2 5 4 8 3 6 7 9   |
 *
 * Brand (flat) 1-9 with `rank` 5, 3, 8, 1, 7, 2, 6, 4 and none for 9: rank order 4 6 2 8 1 7 5 3 9.
 *
 * Scoped category (hierarchical, the target of `scopedCategories` only), whose `rank` is sortable in the live scope
 * only: live root 1 (rank 10); archived root 2 with the archived children 3 (rank 20) and 4 (rank 10) - the rank
 * order of 3 and 4 opposes their primary key order.
 *
 * Tag (flat) 1-20, the target of `tags`, whose counterpart `taggedOwners` on the tag reflects it - the only
 * bidirectional pair of the fixture.
 *
 * **Owners.** Rows are `(target, order[, second])`, `-` for a missing value; every row is written identically into
 * the four faceted references `categoriesPartitioning`, `categoriesFiltering`, `brandsPartitioning` and
 * `brandsFiltering`, with kind `x` unless stated otherwise. Groups are disjoint and every test selects the owners of
 * its group:
 *
 * - **basic 1-11**: 1: (7,10) (3,5) · 2: (4,30) · 3: (4,20) (1,-) · 4: (5,-) · 5: (5,3) (2,1) · 6: (1,50) (5,1) ·
 *   7: (9,40) (8,2) · 8: none · 9: (4,20) · 10: (2,7) (6,7) · 11: (5,9)
 * - **lone 12-15**: 12: (1,-) (4,90) · 13: (4,10) · 14: (2,-) · 15: (3,5)
 * - **hierarchy 20-32**: 20: (1,5) (6,50) · 21: (4,40) · 22: (7,10) · 23: (1,99) (2,1) (7,20) · 24: (6,-) (7,30) ·
 *   25: (4,-) · 26: (5,1) · 27: (4,1) (7,25) · 28: (6,5) (7,35) · 29: (4,-) (7,60) · 30: (1,3) (3,70) ·
 *   31: (8,2) (2,-) · 32: (2,4) (3,10)
 * - **archived 33-34**: 33: (1,2) (6,45) · 34: (7,15) - both archived
 * - **two subtrees 35-37**: 35: (3,5) (6,8) · 36: (1,1) (6,-) (9,3) · 37: (6,-) (8,1)
 * - **kinds 40-44**, rows `(target, order, kind)`: 40: (1,10,x) (6,5,y) · 41: (6,30,y) · 42: (5,1,x) (2,50,y) ·
 *   43: (2,2,y) · 44: (4,3,x) (2,60,y)
 * - **scopes 50-55**: 50: (3,20) · 51: (3,10) archived · 52: (3,30) · 53: (3,30) archived · 54: (5,5) archived ·
 *   55: (8,1)
 * - **across scopes 60-62**, `categoriesAcrossScopes` only: 60: (10,5) · 61: (2,7) · 62: (1,9) (10,1)
 * - **duplicates 70-74**, `variants*` only, rows `(target, variant, order)`: 70: (2,z,1) · 71: (2,a,100) ·
 *   72: (2,a,-) (2,b,50) · 73: (2,b,60) (2,c,5) · 74: (1,a,60) (2,a,3)
 * - **compound 80-86**: 80: (4,10,1) · 81: (4,10,5) · 82: (4,-,3) · 83: (4,10,-) · 84: (4,-,-) (5,2,2) ·
 *   85: (5,1,-) · 86: (4,10,5)
 * - **child chain 90-96**: 90: (4,5,-) · 91: (4,-,7) · 92: (4,-,3) · 93: (1,-,1) (4,1,-) · 94: (5,-,-) ·
 *   95: (5,2,9) · 96: none
 * - **predecessor chains 100-105**, `chain*` only, rows `(target, predecessor)`: 102: (3,head) · 100: (3,102) ·
 *   104: (3,100) · 103: (3,head) archived · 101: (3,103) archived · 105: (8,head) - so target 3 holds the live
 *   chain 102 → 100 → 104 and the archived chain 103 → 101
 * - **rank-ordered pick-first 110-114**: 110: (4,10) (6,50) · 111: (8,1) (4,30) · 112: (6,-) (4,20) · 113: (6,40) ·
 *   114: (5,5) (4,-)
 * - **scoped categories 130-132**, `scopedCategories` only, all live: 130: none · 131: (3,1) · 132: (4,1)
 * - **decimal variants 140-141**, `decimalVariants` only (duplicates told apart by the `BigDecimal` representative
 *   attribute `scale`), rows `(target, scale, order)`: 140: (2,1.0,10) · 141: (2,1.00,5) - the two scales compare
 *   equal but are two different representative keys, so brand 2 has two reduced indexes
 * - **chain variants 150-151**, `chainVariants` only (duplicates told apart by `variant`, a `Predecessor` chain per
 *   representative key), rows `(target, variant)`, every row the head of its chain: 151: (2,b) is written first,
 *   then 150: (2,c) (2,a) - so the reduced indexes of brand 2 are created in the order b, c, a
 * - **tags 120-123**, `tags` only, rows `(target, order, kind)`: 120: (1,50,y) (5,10,x) · 121: (2,30,x) (6,5,y) ·
 *   122: (1,20,y) (3,40,x) · 123: (4,15,x) and (t,t,y) for every tag t from 7 to 20
 *
 * Owner attribute `tail` - the outer fallback sorter - is set on 4: 2 · 8: 1 · 51: 1 · 53: 3 · 54: 2 · 94: 2 ·
 * 96: 1 and missing elsewhere.
 *
 * Kind `y` occurs only in the kinds group, at targets 2 and 6, so a `referenceHaving` on kind `y` leaves every other
 * target out of the candidate reduced indexes. Among the tags, kind `x` occurs only at tags 2, 3, 4 and 5.
 *
 * The tag group is sized for `BidirectionalReferenceRewriter#worthRewriting`: the counterpart visits one reduced index
 * per tagged owner (4), the owner side one per tag (20), and 4 × 4 ≤ 20 - so a `referenceHaving(tags, ...)` of a
 * shape the rewrite reproduces is answered from the counterpart unless the query is ordered by `tags` (guard 4).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Traversal ordering by a reference attribute places each owner in its first block carrying the value")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@Tag(REFERENCE)
@Tag(ORDER)
@Tag(HIERARCHY)
public class EntityByReferenceAttributeTraverseFunctionalTest {
	private static final String TRAVERSE_REFERENCES = "traverseReferences";
	private static final String ENTITY_CATEGORY = "traverseCategory";
	private static final String ENTITY_BRAND = "traverseBrand";
	private static final String ENTITY_OWNER = "traverseOwner";
	private static final String REFERENCE_CATEGORIES_PARTITIONING = "categoriesPartitioning";
	private static final String REFERENCE_CATEGORIES_FILTERING = "categoriesFiltering";
	private static final String REFERENCE_BRANDS_PARTITIONING = "brandsPartitioning";
	private static final String REFERENCE_BRANDS_FILTERING = "brandsFiltering";
	private static final String REFERENCE_CATEGORIES_ACROSS_SCOPES = "categoriesAcrossScopes";
	private static final String REFERENCE_VARIANTS_PARTITIONING = "variantsPartitioning";
	private static final String REFERENCE_VARIANTS_FILTERING = "variantsFiltering";
	private static final String REFERENCE_CHAIN_CATEGORIES_PARTITIONING = "chainCategoriesPartitioning";
	private static final String REFERENCE_CHAIN_CATEGORIES_FILTERING = "chainCategoriesFiltering";
	private static final String REFERENCE_CHAIN_BRANDS_PARTITIONING = "chainBrandsPartitioning";
	private static final String REFERENCE_CHAIN_BRANDS_FILTERING = "chainBrandsFiltering";
	private static final String[] CHAIN_REFERENCES = {
		REFERENCE_CHAIN_CATEGORIES_PARTITIONING, REFERENCE_CHAIN_CATEGORIES_FILTERING,
		REFERENCE_CHAIN_BRANDS_PARTITIONING, REFERENCE_CHAIN_BRANDS_FILTERING
	};
	private static final String ENTITY_SCOPED_CATEGORY = "traverseScopedCategory";
	private static final String REFERENCE_SCOPED_CATEGORIES = "scopedCategories";
	private static final String ENTITY_TAG = "traverseTag";
	private static final String REFERENCE_TAGS = "tags";
	private static final String REFERENCE_TAGGED_OWNERS = "taggedOwners";
	private static final String REFERENCE_DECIMAL_VARIANTS = "decimalVariants";
	private static final String REFERENCE_CHAIN_VARIANTS = "chainVariants";
	private static final String ATTRIBUTE_SCALE = "scale";
	private static final int TAG_COUNT = 20;
	/**
	 * Prefix of the `PLANNING_FILTER_ALTERNATIVE` telemetry argument of the reference index option - absent when the
	 * bidirectional rewrite took the `referenceHaving` over (see `BidirectionalReferenceRewriteFunctionalTest`).
	 */
	private static final String REFERENCE_INDEX_OPTION_PREFIX = "Index type: REFERENCED_ENTITY composed of ";
	/**
	 * Marker of a `PLANNING_FILTER_ALTERNATIVE` argument whose alternative was built into a plan (an ineligible one is
	 * only described).
	 */
	private static final String BUILT_PLAN_MARKER = ", estimated costs ";
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String ATTRIBUTE_VISIBLE = "visible";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_SECOND = "second";
	private static final String ATTRIBUTE_KIND = "kind";
	private static final String ATTRIBUTE_VARIANT = "variant";
	private static final String ATTRIBUTE_CHAIN = "chain";
	private static final String ATTRIBUTE_TAIL = "tail";
	private static final String COMPOUND_ORDER_SECOND = "orderSecond";
	private static final String KIND_X = "x";
	private static final String KIND_Y = "y";
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	private static final String[] MAIN_REFERENCES = {
		REFERENCE_CATEGORIES_PARTITIONING, REFERENCE_CATEGORIES_FILTERING,
		REFERENCE_BRANDS_PARTITIONING, REFERENCE_BRANDS_FILTERING
	};
	private static final Integer[] BASIC_OWNERS = boxedRange(1, 11);
	private static final Integer[] LONE_OWNERS = boxedRange(12, 15);
	private static final Integer[] HIERARCHY_OWNERS = boxedRange(20, 32);
	private static final Integer[] KIND_OWNERS = boxedRange(40, 44);
	private static final Integer[] SCOPE_OWNERS = boxedRange(50, 55);
	private static final Integer[] ACROSS_SCOPES_OWNERS = boxedRange(60, 62);
	private static final Integer[] DUPLICATE_OWNERS = boxedRange(70, 74);
	private static final Integer[] COMPOUND_OWNERS = boxedRange(80, 86);
	private static final Integer[] CHAIN_OWNERS = boxedRange(90, 96);
	private static final Integer[] PREDECESSOR_OWNERS = boxedRange(100, 105);
	private static final Integer[] PICK_FIRST_RANK_OWNERS = boxedRange(110, 114);
	private static final Integer[] TAG_OWNERS = boxedRange(120, 123);
	private static final Integer[] SCOPED_CATEGORY_OWNERS = boxedRange(130, 132);
	private static final Integer[] DECIMAL_VARIANT_OWNERS = boxedRange(140, 141);
	private static final Integer[] CHAIN_VARIANT_OWNERS = boxedRange(150, 151);
	/**
	 * The basic group without owners 7 and 8 - its rows reach brands 1-7 only, see
	 * {@link #shouldKeepSeededTargetOrderWhenIrrelevantOwnerIsAdded(String, SortRoute, EvitaSessionContract)}.
	 */
	private static final Integer[] SEEDED_SELECTION = {1, 2, 3, 4, 5, 6, 9, 10, 11};
	/**
	 * Owner 7 - its rows reach only brands 8 and 9, which no owner of {@link #SEEDED_SELECTION} references.
	 */
	private static final int IRRELEVANT_OWNER = 7;
	private static final long TARGET_ORDER_SEED = 42L;
	/**
	 * The owner attribute `tail`, the outer fallback sorter, by owner primary key.
	 */
	private static final Map<Integer, Integer> TAILS = Map.of(4, 2, 8, 1, 51, 1, 53, 3, 54, 2, 94, 2, 96, 1);
	private static final FilterConstraint[] NO_FILTER = new FilterConstraint[0];
	private static final DebugMode[] NO_EXTRA_DEBUG_MODES = new DebugMode[0];
	/**
	 * The kinds group on a hierarchical reference, whatever `referenceHaving` selects all of it - one owner per block,
	 * so both directions - see
	 * {@link #shouldNotNarrowTraversalByReferenceHaving(String, SortRoute, EvitaSessionContract)}.
	 */
	private static final int[] KINDS_HIERARCHICAL = {40, 44, 41, 42, 43};
	/**
	 * The kinds group on a hierarchical reference under `referenceHaving(kind y)` and `hierarchyWithin(R, 1)`.
	 */
	private static final int[] KIND_Y_WITHIN_1 = {40, 44, 41, 42};
	/**
	 * Owners 23 and 35-37 bounded by the union of two conjunctive hierarchy constraints - see
	 * {@link #shouldBoundTraversalByUnionOfConjunctiveHierarchies(String, SortRoute, EvitaSessionContract)}.
	 */
	private static final int[] UNION_OF_SUBTREES = {35, 23, 37, 36};
	/**
	 * The scopes group over both scopes on a hierarchical reference, ascending - see
	 * {@link #shouldMergeOwnersOfBothScopesInsideBlock(String, SortRoute, EvitaSessionContract)}.
	 */
	private static final int[] BOTH_SCOPES_HIERARCHICAL_ASCENDING = {54, 51, 50, 52, 53, 55};

	/**
	 * One row of an owner's reference, written identically into the four main references.
	 *
	 * @param target the referenced primary key
	 * @param order  the sortable integer, `null` when the row carries none
	 * @param second the second sortable integer, `null` when the row carries none
	 * @param kind   the filterable discriminator
	 */
	private record Row(int target, @Nullable Integer order, @Nullable Integer second, @Nonnull String kind) {
	}

	/**
	 * The owners one ordered query returned, and what its telemetry says about the route it took.
	 *
	 * @param order          owner primary keys in result order
	 * @param routeViolation `null` when the plan took the forced route, otherwise a message saying which route it took
	 *                       instead
	 * @param builtPlans     how many filter alternatives the planner built into a plan (an ineligible alternative is
	 *                       described in the telemetry but never built); summed over pages when several were read
	 * @param alternatives   the arguments of every `PLANNING_FILTER_ALTERNATIVE` telemetry step
	 */
	private record RoutedResult(
		@Nonnull int[] order,
		@Nullable String routeViolation,
		int builtPlans,
		@Nonnull List<String> alternatives
	) {
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
	 * The traversal of the hierarchical target - its `traverseByEntityProperty` specification.
	 */
	private enum Traversal {
		/**
		 * No specification - a hierarchical target defaults to a depth-first walk by primary key.
		 */
		DEFAULT,
		/**
		 * Depth-first, siblings by ascending primary key.
		 */
		DEPTH_FIRST_BY_PRIMARY_KEY,
		/**
		 * Breadth-first, siblings by ascending primary key.
		 */
		BREADTH_FIRST_BY_PRIMARY_KEY,
		/**
		 * Depth-first, siblings by descending primary key.
		 */
		DEPTH_FIRST_BY_DESCENDING_PRIMARY_KEY,
		/**
		 * Depth-first, siblings by the target attribute `rank`, resolved by a nested sorter.
		 */
		DEPTH_FIRST_BY_RANK,
		/**
		 * Breadth-first, siblings by the target attribute `rank`, resolved by a nested sorter.
		 */
		BREADTH_FIRST_BY_RANK
	}

	/**
	 * The order of the flat target - its `traverseByEntityProperty` specification.
	 */
	private enum FlatOrder {
		/**
		 * Ascending primary key.
		 */
		PRIMARY_KEY,
		/**
		 * Descending primary key.
		 */
		DESCENDING_PRIMARY_KEY,
		/**
		 * The target attribute `rank`, resolved by a nested sorter.
		 */
		RANK
	}

	/**
	 * Both hierarchical references, each at a different indexing level, crossed with both sort routes.
	 */
	@Nonnull
	static Stream<Arguments> hierarchicalRoutes() {
		return routesOf(REFERENCE_CATEGORIES_PARTITIONING, REFERENCE_CATEGORIES_FILTERING);
	}

	/**
	 * Both flat references, each at a different indexing level, crossed with both sort routes.
	 */
	@Nonnull
	static Stream<Arguments> flatRoutes() {
		return routesOf(REFERENCE_BRANDS_PARTITIONING, REFERENCE_BRANDS_FILTERING);
	}

	/**
	 * All four main references crossed with both sort routes.
	 */
	@Nonnull
	static Stream<Arguments> allRoutes() {
		return routesOf(MAIN_REFERENCES);
	}

	/**
	 * Both duplicate-allowing references crossed with both sort routes.
	 */
	@Nonnull
	static Stream<Arguments> duplicateRoutes() {
		return routesOf(REFERENCE_VARIANTS_PARTITIONING, REFERENCE_VARIANTS_FILTERING);
	}

	/**
	 * The four references carrying the predecessor chains crossed with both sort routes.
	 */
	@Nonnull
	static Stream<Arguments> chainRoutes() {
		return routesOf(CHAIN_REFERENCES);
	}

	/**
	 * Both sort routes.
	 */
	@Nonnull
	static Stream<Arguments> sortRoutes() {
		return Arrays.stream(SortRoute.values()).map(Arguments::of);
	}

	/**
	 * Crosses the passed references with both sort routes.
	 */
	@Nonnull
	private static Stream<Arguments> routesOf(@Nonnull String... references) {
		return Arrays.stream(references)
			.flatMap(reference -> Arrays.stream(SortRoute.values()).map(route -> Arguments.of(reference, route)));
	}

	/**
	 * Returns the boxed primary keys of the closed range.
	 */
	@Nonnull
	private static Integer[] boxedRange(int from, int to) {
		return IntStream.rangeClosed(from, to).boxed().toArray(Integer[]::new);
	}

	/**
	 * Creates a row of kind `x` without the second value.
	 */
	@Nonnull
	private static Row row(int target, @Nullable Integer order) {
		return new Row(target, order, null, KIND_X);
	}

	/**
	 * Creates a row of kind `x` with both values.
	 */
	@Nonnull
	private static Row row(int target, @Nullable Integer order, @Nullable Integer second) {
		return new Row(target, order, second, KIND_X);
	}

	/**
	 * Creates a row of the passed kind without the second value.
	 */
	@Nonnull
	private static Row kindRow(int target, @Nullable Integer order, @Nonnull String kind) {
		return new Row(target, order, null, kind);
	}

	/**
	 * Tells whether the reference points to the hierarchical category.
	 */
	private static boolean isHierarchical(@Nonnull String referenceName) {
		return REFERENCE_CATEGORIES_PARTITIONING.equals(referenceName) ||
			REFERENCE_CATEGORIES_FILTERING.equals(referenceName) ||
			REFERENCE_CATEGORIES_ACROSS_SCOPES.equals(referenceName) ||
			REFERENCE_CHAIN_CATEGORIES_PARTITIONING.equals(referenceName) ||
			REFERENCE_CHAIN_CATEGORIES_FILTERING.equals(referenceName);
	}

	/**
	 * Returns the `traverseByEntityProperty` specification of the hierarchical traversal, `null` for the default.
	 */
	@Nullable
	private static OrderConstraint traversal(@Nonnull Traversal traversal) {
		return switch (traversal) {
			case DEFAULT -> null;
			case DEPTH_FIRST_BY_PRIMARY_KEY ->
				traverseByEntityProperty(TraversalMode.DEPTH_FIRST, entityPrimaryKeyNatural(OrderDirection.ASC));
			case BREADTH_FIRST_BY_PRIMARY_KEY ->
				traverseByEntityProperty(TraversalMode.BREADTH_FIRST, entityPrimaryKeyNatural(OrderDirection.ASC));
			case DEPTH_FIRST_BY_DESCENDING_PRIMARY_KEY ->
				traverseByEntityProperty(TraversalMode.DEPTH_FIRST, entityPrimaryKeyNatural(OrderDirection.DESC));
			case DEPTH_FIRST_BY_RANK -> traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural(ATTRIBUTE_RANK));
			case BREADTH_FIRST_BY_RANK ->
				traverseByEntityProperty(TraversalMode.BREADTH_FIRST, attributeNatural(ATTRIBUTE_RANK));
		};
	}

	/**
	 * Returns the `traverseByEntityProperty` specification of the flat target order.
	 */
	@Nonnull
	private static OrderConstraint flatOrder(@Nonnull FlatOrder flatOrder) {
		return switch (flatOrder) {
			case PRIMARY_KEY -> traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC));
			case DESCENDING_PRIMARY_KEY -> traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.DESC));
			case RANK -> traverseByEntityProperty(attributeNatural(ATTRIBUTE_RANK));
		};
	}

	/**
	 * Returns the ascending primary key traversal of the reference - depth-first for the hierarchical category,
	 * explicit for the flat brand, whose default would be `pickFirstByEntityProperty`.
	 */
	@Nonnull
	private static OrderConstraint byPrimaryKey(@Nonnull String referenceName) {
		return isHierarchical(referenceName) ?
			traverseByEntityProperty(TraversalMode.DEPTH_FIRST, entityPrimaryKeyNatural(OrderDirection.ASC)) :
			traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC));
	}

	/**
	 * Returns the traversal ordering of the reference by the ascending primary key of its targets and by the passed
	 * values inside each block.
	 */
	@Nonnull
	private static OrderConstraint traverseOrdering(
		@Nonnull String referenceName,
		@Nonnull OrderConstraint... valueOrderings
	) {
		return referenceProperty(
			referenceName, ArrayUtils.mergeArrays(new OrderConstraint[]{byPrimaryKey(referenceName)}, valueOrderings)
		);
	}

	/**
	 * Returns the traversal ordering of the reference by the ascending primary key of its targets and by the
	 * attribute `order` inside each block.
	 */
	@Nonnull
	private static OrderConstraint byOrder(@Nonnull String referenceName, @Nonnull OrderDirection direction) {
		return traverseOrdering(referenceName, attributeNatural(ATTRIBUTE_ORDER, direction));
	}

	/**
	 * Upserts one owner with the passed rows written identically into the four main references, and with its `tail`
	 * when {@link #TAILS} lists one.
	 */
	private static void upsertOwner(@Nonnull EvitaSessionContract session, int primaryKey, @Nonnull Row... rows) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, primaryKey)
			.setAttribute(ATTRIBUTE_ORDER, 30);
		final Integer tail = TAILS.get(primaryKey);
		if (tail != null) {
			builder.setAttribute(ATTRIBUTE_TAIL, tail);
		}
		for (String referenceName : MAIN_REFERENCES) {
			for (Row row : rows) {
				builder.setReference(
					referenceName, row.target(),
					whichIs -> {
						whichIs.setAttribute(ATTRIBUTE_KIND, row.kind());
						if (row.order() != null) {
							whichIs.setAttribute(ATTRIBUTE_ORDER, row.order());
						}
						if (row.second() != null) {
							whichIs.setAttribute(ATTRIBUTE_SECOND, row.second());
						}
					}
				);
			}
		}
		builder.upsertVia(session);
	}

	/**
	 * Upserts one owner with a single row of the four chain references - to the passed target, with the passed
	 * predecessor owner, the head of the chain when `predecessor` is `null`.
	 */
	private static void upsertChainOwner(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		int target,
		@Nullable Integer predecessor
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, primaryKey);
		for (String referenceName : CHAIN_REFERENCES) {
			builder.setReference(
				referenceName, target,
				whichIs -> whichIs.setAttribute(
					ATTRIBUTE_CHAIN, predecessor == null ? Predecessor.HEAD : new Predecessor(predecessor)
				)
			);
		}
		builder.upsertVia(session);
	}

	/**
	 * Upserts one owner with rows of `chainVariants` to brand 2 only, one per passed variant in the passed order, each
	 * row the head of the chain of its variant.
	 */
	private static void upsertChainVariantOwner(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull String... variants
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, primaryKey);
		for (String variant : variants) {
			builder.setOrUpdateReference(
				REFERENCE_CHAIN_VARIANTS, 2, Functions.alwaysFalse(),
				whichIs -> whichIs.setAttribute(ATTRIBUTE_VARIANT, variant).setAttribute(ATTRIBUTE_CHAIN, Predecessor.HEAD)
			);
		}
		builder.upsertVia(session);
	}

	/**
	 * Upserts one owner with rows of `tags` only.
	 */
	private static void upsertTagOwner(@Nonnull EvitaSessionContract session, int primaryKey, @Nonnull Row... rows) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, primaryKey);
		for (Row row : rows) {
			builder.setReference(
				REFERENCE_TAGS, row.target(),
				whichIs -> {
					whichIs.setAttribute(ATTRIBUTE_KIND, row.kind());
					if (row.order() != null) {
						whichIs.setAttribute(ATTRIBUTE_ORDER, row.order());
					}
				}
			);
		}
		builder.upsertVia(session);
	}

	/**
	 * Upserts one owner with rows of `categoriesAcrossScopes` only, each row `{target, order}`.
	 */
	private static void upsertAcrossScopesOwner(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull int[]... rows
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, primaryKey);
		for (int[] row : rows) {
			builder.setReference(
				REFERENCE_CATEGORIES_ACROSS_SCOPES, row[0],
				whichIs -> whichIs.setAttribute(ATTRIBUTE_KIND, KIND_X).setAttribute(ATTRIBUTE_ORDER, row[1])
			);
		}
		builder.upsertVia(session);
	}

	/**
	 * Upserts one owner holding rows of the duplicate-allowing references, each row `{target, variant, order}` with
	 * `order` possibly `null`.
	 */
	private static void upsertDuplicateOwner(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull Object[]... variants
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, primaryKey);
		for (String referenceName : new String[]{REFERENCE_VARIANTS_PARTITIONING, REFERENCE_VARIANTS_FILTERING}) {
			for (Object[] variant : variants) {
				builder.setOrUpdateReference(
					referenceName, (Integer) variant[0], Functions.alwaysFalse(),
					whichIs -> {
						whichIs.setAttribute(ATTRIBUTE_VARIANT, (String) variant[1]);
						if (variant[2] != null) {
							whichIs.setAttribute(ATTRIBUTE_ORDER, (Integer) variant[2]);
						}
					}
				);
			}
		}
		builder.upsertVia(session);
	}

	/**
	 * Declares the sortable attributes, the discriminator and the compound of one plain reference.
	 */
	private static void declareRowAttributes(@Nonnull ReferenceSchemaBuilder reference) {
		reference
			.withAttribute(
				ATTRIBUTE_ORDER, Integer.class,
				thatIs -> thatIs.filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES).nullable()
			)
			.withAttribute(ATTRIBUTE_SECOND, Integer.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable())
			.withAttribute(ATTRIBUTE_KIND, String.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES))
			.withSortableAttributeCompound(
				COMPOUND_ORDER_SECOND,
				new AttributeElement[]{
					new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
					new AttributeElement(ATTRIBUTE_SECOND, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
				},
				thatIs -> thatIs.indexedInScope(BOTH_SCOPES)
			);
	}

	/**
	 * Creates one category.
	 */
	private static void upsertCategory(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parent,
		@Nullable Integer rank,
		boolean visible
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_CATEGORY, primaryKey)
			.setAttribute(ATTRIBUTE_VISIBLE, visible);
		if (parent != null) {
			builder.setParent(parent);
		}
		if (rank != null) {
			builder.setAttribute(ATTRIBUTE_RANK, rank);
		}
		builder.upsertVia(session);
	}

	/**
	 * Creates the targets and owners described in the class documentation and archives the listed ones.
	 */
	@DataSet(value = TRAVERSE_REFERENCES, destroyAfterClass = true)
	void setUp(EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withoutGeneratedPrimaryKey()
			.withHierarchyIndexedInScope(BOTH_SCOPES)
			.withAttribute(ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable())
			.withAttribute(ATTRIBUTE_VISIBLE, Boolean.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES))
			.updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_TAG)
			.withoutGeneratedPrimaryKey()
			.updateVia(session);
		// `rank` is sortable in the live scope only - a nested target order cannot read it in the archived tree
		session.defineEntitySchema(ENTITY_SCOPED_CATEGORY)
			.withoutGeneratedPrimaryKey()
			.withHierarchyIndexedInScope(BOTH_SCOPES)
			.withAttribute(ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.sortableInScope(Scope.LIVE).nullable())
			.updateVia(session);
		final EntitySchemaBuilder owner = session.defineEntitySchema(ENTITY_OWNER)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_TAIL, Integer.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable())
			.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.nullable());
		// the main references are faceted, so that `facetHaving` can be shown not to bound the traversal
		owner.withReferenceToEntity(
			REFERENCE_CATEGORIES_PARTITIONING, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
			whichIs -> declareRowAttributes(
				whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES).facetedInScope(BOTH_SCOPES)
			)
		);
		owner.withReferenceToEntity(
			REFERENCE_CATEGORIES_FILTERING, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
			whichIs -> declareRowAttributes(whichIs.indexedForFilteringInScope(BOTH_SCOPES).facetedInScope(BOTH_SCOPES))
		);
		owner.withReferenceToEntity(
			REFERENCE_BRANDS_PARTITIONING, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
			whichIs -> declareRowAttributes(
				whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES).facetedInScope(BOTH_SCOPES)
			)
		);
		owner.withReferenceToEntity(
			REFERENCE_BRANDS_FILTERING, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
			whichIs -> declareRowAttributes(whichIs.indexedForFilteringInScope(BOTH_SCOPES).facetedInScope(BOTH_SCOPES))
		);
		for (String chainReference : CHAIN_REFERENCES) {
			final boolean partitioning = REFERENCE_CHAIN_CATEGORIES_PARTITIONING.equals(chainReference) ||
				REFERENCE_CHAIN_BRANDS_PARTITIONING.equals(chainReference);
			owner.withReferenceToEntity(
				chainReference, isHierarchical(chainReference) ? ENTITY_CATEGORY : ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE,
				whichIs -> (partitioning ?
					whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES) :
					whichIs.indexedForFilteringInScope(BOTH_SCOPES))
					.withAttribute(
						ATTRIBUTE_CHAIN, Predecessor.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable()
					)
			);
		}
		owner.withReferenceToEntity(
			REFERENCE_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
			whichIs -> declareRowAttributes(whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES))
		);
		// the only reference touching the archived category 10 - a nested order over the other category references
		// must not meet a target missing from one of the two trees
		owner.withReferenceToEntity(
			REFERENCE_CATEGORIES_ACROSS_SCOPES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
			whichIs -> declareRowAttributes(whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES))
		);
		owner.withReferenceToEntity(
			REFERENCE_SCOPED_CATEGORIES, ENTITY_SCOPED_CATEGORY, Cardinality.ZERO_OR_MORE,
			whichIs -> declareRowAttributes(whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES))
		);
		owner.withReferenceToEntity(
			REFERENCE_DECIMAL_VARIANTS, ENTITY_BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES)
				.withAttribute(
					ATTRIBUTE_SCALE, BigDecimal.class,
					thatIs -> thatIs.representative().sortableInScope(BOTH_SCOPES).indexDecimalPlaces(1)
				)
				.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable())
		);
		owner.withReferenceToEntity(
			REFERENCE_CHAIN_VARIANTS, ENTITY_BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
			whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES)
				.withAttribute(ATTRIBUTE_VARIANT, String.class, thatIs -> thatIs.representative())
				.withAttribute(
					ATTRIBUTE_CHAIN, Predecessor.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable()
				)
		);
		final String[][] variantReferences = {{REFERENCE_VARIANTS_PARTITIONING, "P"}, {REFERENCE_VARIANTS_FILTERING, "F"}};
		for (String[] variants : variantReferences) {
			owner.withReferenceToEntity(
				variants[0], ENTITY_BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> ("P".equals(variants[1]) ?
					whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES) :
					whichIs.indexedForFilteringInScope(BOTH_SCOPES))
					.withAttribute(
						ATTRIBUTE_VARIANT, String.class,
						thatIs -> thatIs.filterableInScope(BOTH_SCOPES).representative()
					)
					.withAttribute(
						ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable()
					)
			);
		}
		owner.updateVia(session);
		// the counterpart of `tags`, inheriting `kind` - what makes `referenceHaving(tags, kind)` rewritable
		session.defineEntitySchema(ENTITY_TAG)
			.withReflectedReferenceToEntity(
				REFERENCE_TAGGED_OWNERS, ENTITY_OWNER, REFERENCE_TAGS,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES).withAttributesInherited()
			)
			.updateVia(session);

		// parents first, so that every node is attached to the tree when it is created
		upsertCategory(session, 1, null, 10, true);
		upsertCategory(session, 2, null, 20, true);
		upsertCategory(session, 4, 1, 20, true);
		upsertCategory(session, 5, 1, 10, true);
		upsertCategory(session, 6, 4, 10, true);
		upsertCategory(session, 7, 4, 20, false);
		upsertCategory(session, 3, 2, 20, true);
		upsertCategory(session, 8, 2, 10, true);
		upsertCategory(session, 9, 8, null, true);
		upsertCategory(session, 10, null, 5, true);
		final Integer[] brandRanks = {null, 5, 3, 8, 1, 7, 2, 6, 4, null};
		for (int brand = 1; brand <= 9; brand++) {
			final EntityBuilder builder = session.createNewEntity(ENTITY_BRAND, brand);
			if (brandRanks[brand] != null) {
				builder.setAttribute(ATTRIBUTE_RANK, brandRanks[brand]);
			}
			builder.upsertVia(session);
		}
		for (int tag = 1; tag <= TAG_COUNT; tag++) {
			session.createNewEntity(ENTITY_TAG, tag).upsertVia(session);
		}
		final int[][] scopedCategories = {{1, 0, 10}, {2, 0, 0}, {3, 2, 20}, {4, 2, 10}};
		for (int[] scopedCategory : scopedCategories) {
			final EntityBuilder builder = session.createNewEntity(ENTITY_SCOPED_CATEGORY, scopedCategory[0]);
			if (scopedCategory[1] > 0) {
				builder.setParent(scopedCategory[1]);
			}
			if (scopedCategory[2] > 0) {
				builder.setAttribute(ATTRIBUTE_RANK, scopedCategory[2]);
			}
			builder.upsertVia(session);
		}

		// basic
		upsertOwner(session, 1, row(7, 10), row(3, 5));
		upsertOwner(session, 2, row(4, 30));
		upsertOwner(session, 3, row(4, 20), row(1, null));
		upsertOwner(session, 4, row(5, null));
		upsertOwner(session, 5, row(5, 3), row(2, 1));
		upsertOwner(session, 6, row(1, 50), row(5, 1));
		upsertOwner(session, 7, row(9, 40), row(8, 2));
		upsertOwner(session, 8);
		upsertOwner(session, 9, row(4, 20));
		upsertOwner(session, 10, row(2, 7), row(6, 7));
		upsertOwner(session, 11, row(5, 9));
		// lone value-less owners
		upsertOwner(session, 12, row(1, null), row(4, 90));
		upsertOwner(session, 13, row(4, 10));
		upsertOwner(session, 14, row(2, null));
		upsertOwner(session, 15, row(3, 5));
		// hierarchy
		upsertOwner(session, 20, row(1, 5), row(6, 50));
		upsertOwner(session, 21, row(4, 40));
		upsertOwner(session, 22, row(7, 10));
		upsertOwner(session, 23, row(1, 99), row(2, 1), row(7, 20));
		upsertOwner(session, 24, row(6, null), row(7, 30));
		upsertOwner(session, 25, row(4, null));
		upsertOwner(session, 26, row(5, 1));
		upsertOwner(session, 27, row(4, 1), row(7, 25));
		upsertOwner(session, 28, row(6, 5), row(7, 35));
		upsertOwner(session, 29, row(4, null), row(7, 60));
		upsertOwner(session, 30, row(1, 3), row(3, 70));
		upsertOwner(session, 31, row(8, 2), row(2, null));
		upsertOwner(session, 32, row(2, 4), row(3, 10));
		upsertOwner(session, 33, row(1, 2), row(6, 45));
		upsertOwner(session, 34, row(7, 15));
		// two hierarchy constraints
		upsertOwner(session, 35, row(3, 5), row(6, 8));
		upsertOwner(session, 36, row(1, 1), row(6, null), row(9, 3));
		upsertOwner(session, 37, row(6, null), row(8, 1));
		// kinds
		upsertOwner(session, 40, kindRow(1, 10, KIND_X), kindRow(6, 5, KIND_Y));
		upsertOwner(session, 41, kindRow(6, 30, KIND_Y));
		upsertOwner(session, 42, kindRow(5, 1, KIND_X), kindRow(2, 50, KIND_Y));
		upsertOwner(session, 43, kindRow(2, 2, KIND_Y));
		upsertOwner(session, 44, kindRow(4, 3, KIND_X), kindRow(2, 60, KIND_Y));
		// scopes
		upsertOwner(session, 50, row(3, 20));
		upsertOwner(session, 51, row(3, 10));
		upsertOwner(session, 52, row(3, 30));
		upsertOwner(session, 53, row(3, 30));
		upsertOwner(session, 54, row(5, 5));
		upsertOwner(session, 55, row(8, 1));
		// across scopes
		upsertAcrossScopesOwner(session, 60, new int[]{10, 5});
		upsertAcrossScopesOwner(session, 61, new int[]{2, 7});
		upsertAcrossScopesOwner(session, 62, new int[]{1, 9}, new int[]{10, 1});
		// duplicates - owner 70 comes first, so the reduced index of the (2, z) row exists before the one of (2, a)
		upsertDuplicateOwner(session, 70, new Object[]{2, "z", 1});
		upsertDuplicateOwner(session, 71, new Object[]{2, "a", 100});
		upsertDuplicateOwner(session, 72, new Object[]{2, "a", null}, new Object[]{2, "b", 50});
		upsertDuplicateOwner(session, 73, new Object[]{2, "b", 60}, new Object[]{2, "c", 5});
		upsertDuplicateOwner(session, 74, new Object[]{1, "a", 60}, new Object[]{2, "a", 3});
		// compound
		upsertOwner(session, 80, row(4, 10, 1));
		upsertOwner(session, 81, row(4, 10, 5));
		upsertOwner(session, 82, row(4, null, 3));
		upsertOwner(session, 83, row(4, 10, null));
		upsertOwner(session, 84, row(4, null, null), row(5, 2, 2));
		upsertOwner(session, 85, row(5, 1, null));
		upsertOwner(session, 86, row(4, 10, 5));
		// child chain
		upsertOwner(session, 90, row(4, 5, null));
		upsertOwner(session, 91, row(4, null, 7));
		upsertOwner(session, 92, row(4, null, 3));
		upsertOwner(session, 93, row(1, null, 1), row(4, 1, null));
		upsertOwner(session, 94, row(5, null, null));
		upsertOwner(session, 95, row(5, 2, 9));
		upsertOwner(session, 96);
		// predecessor chains - both chains of target 3 are written live, the archived one then leaves with its owners
		upsertChainOwner(session, 102, 3, null);
		upsertChainOwner(session, 100, 3, 102);
		upsertChainOwner(session, 104, 3, 100);
		upsertChainOwner(session, 103, 3, null);
		upsertChainOwner(session, 101, 3, 103);
		upsertChainOwner(session, 105, 8, null);
		// rank-ordered pick-first
		upsertOwner(session, 110, row(4, 10), row(6, 50));
		upsertOwner(session, 111, row(8, 1), row(4, 30));
		upsertOwner(session, 112, row(6, null), row(4, 20));
		upsertOwner(session, 113, row(6, 40));
		upsertOwner(session, 114, row(5, 5), row(4, null));
		// tags
		upsertTagOwner(session, 120, kindRow(1, 50, KIND_Y), kindRow(5, 10, KIND_X));
		upsertTagOwner(session, 121, kindRow(2, 30, KIND_X), kindRow(6, 5, KIND_Y));
		upsertTagOwner(session, 122, kindRow(1, 20, KIND_Y), kindRow(3, 40, KIND_X));
		final Row[] fillers = IntStream.rangeClosed(7, TAG_COUNT)
			.mapToObj(tag -> kindRow(tag, tag, KIND_Y))
			.toArray(Row[]::new);
		upsertTagOwner(session, 123, ArrayUtils.mergeArrays(new Row[]{kindRow(4, 15, KIND_X)}, fillers));
		// decimal variants - two scales of one representative value
		for (Object[] decimalRow : new Object[][]{{140, new BigDecimal("1.0"), 10}, {141, new BigDecimal("1.00"), 5}}) {
			session.createNewEntity(ENTITY_OWNER, (Integer) decimalRow[0])
				.setOrUpdateReference(
					REFERENCE_DECIMAL_VARIANTS, 2, Functions.alwaysFalse(),
					whichIs -> whichIs.setAttribute(ATTRIBUTE_SCALE, (BigDecimal) decimalRow[1])
						.setAttribute(ATTRIBUTE_ORDER, (Integer) decimalRow[2])
				)
				.upsertVia(session);
		}
		// chain variants - the reduced indexes of brand 2 come to exist in the order b, c, a
		upsertChainVariantOwner(session, 151, "b");
		upsertChainVariantOwner(session, 150, "c", "a");
		// scoped categories
		session.createNewEntity(ENTITY_OWNER, 130).upsertVia(session);
		for (int[] ownerRow : new int[][]{{131, 3}, {132, 4}}) {
			session.createNewEntity(ENTITY_OWNER, ownerRow[0])
				.setReference(
					REFERENCE_SCOPED_CATEGORIES, ownerRow[1],
					whichIs -> whichIs.setAttribute(ATTRIBUTE_KIND, KIND_X).setAttribute(ATTRIBUTE_ORDER, 1)
				)
				.upsertVia(session);
		}

		for (int archived : new int[]{33, 34, 51, 53, 54, 101, 103}) {
			session.archiveEntity(ENTITY_OWNER, archived);
		}
		session.archiveEntity(ENTITY_CATEGORY, 10);
		// the parent first, so that the children attach to it in the archived tree
		for (int archivedScopedCategory : new int[]{2, 3, 4}) {
			session.archiveEntity(ENTITY_SCOPED_CATEGORY, archivedScopedCategory);
		}
	}

	/**
	 * Runs a query with the passed orderings on the requested route and returns the owner primary keys of one page
	 * in result order, together with the route the plan actually took.
	 *
	 * Each route is forced by a debug mode, so that a small dataset cannot answer an index-route assertion from
	 * prefetched bodies - and each is then *proven* from the telemetry: the `EXECUTION_PREFETCH` step is emitted if and
	 * only if the plan carries a prefetcher (see `PreferIndexScanDebugModeFunctionalTest`). A mismatch is not thrown
	 * here but carried in {@link RoutedResult#routeViolation()}, so the caller reports it as an assertion of its own
	 * next to the order, and one run shows both.
	 */
	@Nonnull
	private static RoutedResult queryPage(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nonnull Integer[] owners,
		@Nonnull FilterConstraint[] filters,
		int pageNumber,
		int pageSize,
		@Nonnull DebugMode[] extraDebugModes,
		@Nonnull OrderConstraint... orderings
	) {
		final DebugMode[] debugModes = ArrayUtils.mergeArrays(
			new DebugMode[]{route == SortRoute.PREFETCH ? DebugMode.PREFER_PREFETCHING : DebugMode.PREFER_INDEX_SCAN},
			extraDebugModes
		);
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_OWNER),
				filterBy(ArrayUtils.mergeArrays(new FilterConstraint[]{entityPrimaryKeyInSet(owners)}, filters)),
				orderBy(orderings),
				require(page(pageNumber, pageSize), queryTelemetry(), debug(debugModes))
			),
			EntityReference.class
		);
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		assertNotNull(telemetry, "Query telemetry must be present - it is the only proof of the route taken!");
		final boolean prefetched = containsPhase(telemetry, QueryPhase.EXECUTION_PREFETCH);
		final boolean inScopeFilter = holdsInScope(filters);
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
		final List<String> alternatives = new ArrayList<>(4);
		collectFilterAlternativeArguments(telemetry, alternatives);
		return new RoutedResult(
			response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).toArray(),
			routeViolation,
			(int) alternatives.stream().filter(it -> it.contains(BUILT_PLAN_MARKER)).count(),
			alternatives
		);
	}

	/**
	 * Tells whether the filter holds an `inScope(...)` container. An ordered query with one never prefetches - the
	 * prefetch route does not support it yet (#1682, an enhancement outside #1670) - so a PREFETCH row with such a
	 * filter is expected on the index route: it checks the index route under the prefetch preference.
	 */
	private static boolean holdsInScope(@Nonnull FilterConstraint[] filters) {
		return Arrays.stream(filters).anyMatch(FilterInScope.class::isInstance);
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
	 * Walks the telemetry tree and collects the arguments of every `PLANNING_FILTER_ALTERNATIVE` step - one per filter
	 * alternative (index set) the planner considered.
	 */
	private static void collectFilterAlternativeArguments(
		@Nonnull QueryTelemetry telemetry,
		@Nonnull List<String> target
	) {
		if (telemetry.getOperation() == QueryPhase.PLANNING_FILTER_ALTERNATIVE) {
			target.addAll(Arrays.asList(telemetry.getArguments()));
		}
		for (QueryTelemetry step : telemetry.getSteps()) {
			collectFilterAlternativeArguments(step, target);
		}
	}

	/**
	 * Runs a query with the passed orderings on the requested route and returns all owner primary keys in result
	 * order, together with the route the plan took.
	 */
	@Nonnull
	private static RoutedResult queryOrder(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nonnull Integer[] owners,
		@Nonnull FilterConstraint[] filters,
		@Nonnull OrderConstraint... orderings
	) {
		return queryPage(session, route, owners, filters, 1, 100, NO_EXTRA_DEBUG_MODES, orderings);
	}

	/**
	 * Reads every page of the passed size on the requested route and returns the concatenated owner primary keys; the
	 * route check fails when any page took the other route.
	 */
	@Nonnull
	private static RoutedResult queryAllPages(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nonnull Integer[] owners,
		@Nonnull FilterConstraint[] filters,
		int expectedCount,
		int pageSize,
		@Nonnull OrderConstraint... orderings
	) {
		final List<Integer> result = new ArrayList<>(expectedCount);
		String routeViolation = null;
		int builtPlans = 0;
		final List<String> alternatives = new ArrayList<>(8);
		for (int pageNumber = 1; (pageNumber - 1) * pageSize < expectedCount; pageNumber++) {
			final RoutedResult page = queryPage(
				session, route, owners, filters, pageNumber, pageSize, NO_EXTRA_DEBUG_MODES, orderings
			);
			for (int owner : page.order()) {
				result.add(owner);
			}
			if (routeViolation == null && page.routeViolation() != null) {
				routeViolation = "page " + pageNumber + ": " + page.routeViolation();
			}
			builtPlans += page.builtPlans();
			alternatives.addAll(page.alternatives());
		}
		return new RoutedResult(
			result.stream().mapToInt(Integer::intValue).toArray(), routeViolation, builtPlans, alternatives
		);
	}

	/**
	 * Returns the primary keys of the owners the filter selects, in ascending order - without any ordering, so that a
	 * wrong reading of the filter is told apart from a wrong order. The query carries the debug mode of the route
	 * under test, because a filter can go wrong on one plan only (#1681). The route itself is not asserted,
	 * since without an ordering the prefetch depends on the filter alone; whether it was taken is reported in the
	 * message of the selection assertion instead (a {@link RoutedResult#routeViolation()} of the result).
	 */
	@Nonnull
	private static RoutedResult querySelection(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nonnull Integer[] owners,
		@Nonnull FilterConstraint... filters
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_OWNER),
				filterBy(ArrayUtils.mergeArrays(new FilterConstraint[]{entityPrimaryKeyInSet(owners)}, filters)),
				require(
					page(1, 100),
					queryTelemetry(),
					debug(route == SortRoute.PREFETCH ? DebugMode.PREFER_PREFETCHING : DebugMode.PREFER_INDEX_SCAN)
				)
			),
			EntityReference.class
		);
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		assertNotNull(telemetry, "Query telemetry must be present - it reports the route the selection took!");
		final boolean prefetched = containsPhase(telemetry, QueryPhase.EXECUTION_PREFETCH);
		return new RoutedResult(
			response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray(),
			prefetched == (route == SortRoute.PREFETCH && !holdsInScope(filters)) ?
				null : "the selection ran on the other route",
			0,
			List.of()
		);
	}

	/**
	 * Asserts the owner order and reports both full arrays on a mismatch, so one run shows the complete answer of
	 * every route rather than the first differing position only.
	 */
	private static void assertOrder(@Nonnull int[] expected, @Nonnull int[] actual, @Nonnull String label) {
		assertArrayEquals(
			expected, actual,
			() -> label + ": expected " + Arrays.toString(expected) + " but was " + Arrays.toString(actual)
		);
	}

	/**
	 * Asserts, as two independent assertions, that the query took the forced route and that it returned the owners
	 * in the `expected` order - a failed route check never hides the order, nor the other way round.
	 */
	private static void assertOrder(@Nonnull int[] expected, @Nonnull RoutedResult actual, @Nonnull String label) {
		assertAll(
			() -> assertNull(actual.routeViolation(), () -> label + " - " + actual.routeViolation()),
			() -> assertOrder(expected, actual.order(), label)
		);
	}

	/**
	 * Returns an executable asserting that the filter selects the owners of `expected` (in any order) on the route
	 * under test, and - as a separate assertion - that the ordering returns them in exactly the `expected` order on
	 * that route. A filter defect is therefore reported by the selection assertion and not read as an ordering one.
	 */
	@Nonnull
	private static Executable selectsAndOrders(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nonnull Integer[] owners,
		@Nonnull FilterConstraint[] filters,
		@Nonnull OrderConstraint ordering,
		@Nonnull int[] expected,
		@Nonnull String label
	) {
		final int[] expectedSelection = Arrays.stream(expected).sorted().toArray();
		return () -> assertAll(
			() -> {
				final RoutedResult selection = querySelection(session, route, owners, filters);
				assertOrder(
					expectedSelection, selection.order(),
					label + " - selection (filter only, " + route + " preference, " + (
						selection.routeViolation() == null ? "taken" :
							route == SortRoute.PREFETCH ? "not taken, no prefetch" : "not taken, prefetched"
					) + ")"
				);
			},
			() -> assertOrder(expected, queryOrder(session, route, owners, filters, ordering), label)
		);
	}

	/**
	 * Returns the expected order of the basic group on the hierarchical category, derived block by block in the
	 * comments (`block: members -> order`; a member in brackets was placed by an earlier block, a member with `-`
	 * carries no value there and is passed on).
	 */
	@Nonnull
	private static int[] expectedBasicOrder(@Nonnull Traversal traversal, @Nonnull OrderDirection direction) {
		final boolean ascending = direction == OrderDirection.ASC;
		return switch (traversal) {
			// 1: 3-, 6 -> 6 | 4: 2(30) 3(20) 9(20) -> 3 9 2 | 6: 10 | 7: 1 | 5: 4-, 5(3), 11(9), [6] -> 5 11
			// 2: [5 10] | 3: [1] | 8: 7 | 9: [7] | unplaced 4 8
			case DEFAULT, DEPTH_FIRST_BY_PRIMARY_KEY -> ascending ?
				new int[]{6, 3, 9, 2, 10, 1, 5, 11, 7, 4, 8} : new int[]{6, 2, 9, 3, 10, 1, 11, 5, 7, 4, 8};
			// 1: 6 | 2: 5(1) 10(7) | 4: 3 9 2 | 5: 4-, 11 | 3: 1 | 8: 7 | 6 7 9: [10] [1] [7] | unplaced 4 8
			case BREADTH_FIRST_BY_PRIMARY_KEY -> ascending ?
				new int[]{6, 5, 10, 3, 9, 2, 11, 1, 7, 4, 8} : new int[]{6, 10, 5, 2, 9, 3, 11, 1, 7, 4, 8};
			// 2: 5 10 | 8: 7 | 9: [7] | 3: 1 | 1: 3-, 6 | 5: 4-, 11 | 4: 3 9 2 | 7 6: [1] [10] | unplaced 4 8
			case DEPTH_FIRST_BY_DESCENDING_PRIMARY_KEY -> ascending ?
				new int[]{5, 10, 7, 1, 6, 11, 3, 9, 2, 4, 8} : new int[]{10, 5, 7, 1, 6, 11, 2, 9, 3, 4, 8};
			// 1: 6 | 5: 4-, 5 11 | 4: 3 9 2 | 6: 10 | 7: 1 | 2: [5 10] | 8: 7 | 9 3: [7] [1] | unplaced 4 8
			case DEPTH_FIRST_BY_RANK -> ascending ?
				new int[]{6, 5, 11, 3, 9, 2, 10, 1, 7, 4, 8} : new int[]{6, 11, 5, 2, 9, 3, 10, 1, 7, 4, 8};
			// 1: 6 | 2: 5 10 | 5: 4-, 11 | 4: 3 9 2 | 8: 7 | 3: 1 | 6 7 9: [10] [1] [7] | unplaced 4 8
			case BREADTH_FIRST_BY_RANK -> ascending ?
				new int[]{6, 5, 10, 11, 3, 9, 2, 7, 1, 4, 8} : new int[]{6, 10, 5, 11, 2, 9, 3, 7, 1, 4, 8};
		};
	}

	/**
	 * Returns the expected order of the basic group on the flat brand, derived block by block as in
	 * {@link #expectedBasicOrder(Traversal, OrderDirection)}.
	 */
	@Nonnull
	private static int[] expectedBasicFlatOrder(@Nonnull FlatOrder flatOrder, @Nonnull OrderDirection direction) {
		final boolean ascending = direction == OrderDirection.ASC;
		return switch (flatOrder) {
			// 1: 3-, 6 | 2: 5(1) 10(7) | 3: 1 | 4: 3 9 2 | 5: 4-, 11 | 8: 7 | unplaced 4 8
			case PRIMARY_KEY -> ascending ?
				new int[]{6, 5, 10, 1, 3, 9, 2, 11, 7, 4, 8} : new int[]{6, 10, 5, 1, 2, 9, 3, 11, 7, 4, 8};
			// 9: 7 | 7: 1 | 6: 10 | 5: 4-, 6(1) 5(3) 11(9) | 4: 3 9 2 | 3 2 1: all placed | unplaced 4 8
			case DESCENDING_PRIMARY_KEY -> ascending ?
				new int[]{7, 1, 10, 6, 5, 11, 3, 9, 2, 4, 8} : new int[]{7, 1, 10, 11, 5, 6, 2, 9, 3, 4, 8};
			// rank order 4 6 2 8 1 7 5 3 9 - 4: 3 9 2 | 6: 10 | 2: 5 | 8: 7 | 1: 6 | 7: 1 | 5: 4-, 11 | unplaced 4 8
			case RANK -> ascending ?
				new int[]{3, 9, 2, 10, 5, 7, 6, 1, 11, 4, 8} : new int[]{2, 9, 3, 10, 5, 7, 6, 1, 11, 4, 8};
		};
	}

	/**
	 * Checks the rule "Should place each owner in the first block of the traversal whose row carries the value".
	 */
	@DisplayName("Should place each owner in the first block of the traversal whose row carries the value")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldPlaceOwnerInFirstBlockOfTraversalCarryingValue(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// owner 1 sorts in block 7 although target 3 has a lower primary key (traversal order, not primary key
		// order); owner 3's row in block 1 carries no value, so it moves on to block 4; owners 3 and 9 tie in block 4
		// and follow their primary key in the direction; owners 4 and 8 carry no value anywhere and follow unsorted
		final List<Executable> assertions = new ArrayList<>(Traversal.values().length * 2);
		for (Traversal traversal : Traversal.values()) {
			for (OrderDirection direction : OrderDirection.values()) {
				final OrderConstraint specification = traversal(traversal);
				final OrderConstraint ordering = specification == null ?
					referenceProperty(referenceName, attributeNatural(ATTRIBUTE_ORDER, direction)) :
					referenceProperty(referenceName, specification, attributeNatural(ATTRIBUTE_ORDER, direction));
				final int[] expected = expectedBasicOrder(traversal, direction);
				assertions.add(
					() -> assertOrder(
						expected, queryOrder(session, route, BASIC_OWNERS, NO_FILTER, ordering),
						traversal + " " + direction
					)
				);
			}
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should place each owner in the first block in the order of a flat target whose row carries
	 * the value".
	 */
	@DisplayName("Should place each owner in the first block in the order of a flat target whose row carries the value")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("flatRoutes")
	void shouldPlaceOwnerInFirstBlockOfFlatTargetCarryingValue(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		final List<Executable> assertions = new ArrayList<>(FlatOrder.values().length * 2);
		for (FlatOrder flatOrder : FlatOrder.values()) {
			for (OrderDirection direction : OrderDirection.values()) {
				final int[] expected = expectedBasicFlatOrder(flatOrder, direction);
				final OrderConstraint ordering = referenceProperty(
					referenceName, flatOrder(flatOrder), attributeNatural(ATTRIBUTE_ORDER, direction)
				);
				assertions.add(
					() -> assertOrder(
						expected, queryOrder(session, route, BASIC_OWNERS, NO_FILTER, ordering),
						flatOrder + " " + direction
					)
				);
			}
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should pass an owner on when it is the only member of a block and its row carries no value".
	 */
	@DisplayName("Should pass an owner on when it is the only member of a block and its row carries no value")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("allRoutes")
	void shouldPassOnLoneOwnerWithoutValue(String referenceName, SortRoute route, EvitaSessionContract session) {
		// owner 12 is alone in block 1 and owner 14 alone in block 2, both rows value-less: a block with one member
		// must detect the missing value as reliably as a block with several
		// category (1 4 6 7 5 2 3): 1: 12- | 4: 13(10) 12(90) | 2: 14- | 3: 15 | unplaced 14
		// brand (1 2 3 4): 1: 12- | 2: 14- | 3: 15 | 4: 13(10) 12(90) | unplaced 14
		final boolean hierarchical = isHierarchical(referenceName);
		final int[] ascending = hierarchical ? new int[]{13, 12, 15, 14} : new int[]{15, 13, 12, 14};
		final int[] descending = hierarchical ? new int[]{12, 13, 15, 14} : new int[]{15, 12, 13, 14};
		assertAll(
			() -> assertOrder(
				ascending,
				queryOrder(session, route, LONE_OWNERS, NO_FILTER, byOrder(referenceName, OrderDirection.ASC)),
				"ascending"
			),
			() -> assertOrder(
				descending,
				queryOrder(session, route, LONE_OWNERS, NO_FILTER, byOrder(referenceName, OrderDirection.DESC)),
				"descending"
			)
		);
	}

	/**
	 * Checks the rule "Should bound the traversal by the node set of a hierarchyWithin on the ordered reference".
	 */
	@DisplayName("Should bound the traversal by the node set of a hierarchyWithin on the ordered reference")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldBoundTraversalByHierarchyWithin(String referenceName, SortRoute route, EvitaSessionContract session) {
		// node set {4, 6, 7}, blocks 4 6 7; owners 20 and 23 also sit in category 1, which precedes the subtree - the
		// subtree block must place them, not category 1 (docs: products of the listed category, ordered within it)
		// 4: 21(40) 25- 27(1) 29- -> 27 21 | 6: 20(50) 24- 28(5) -> 28 20 | 7: 22(10) 23(20) 24(30) 29(60) | unplaced 25
		final FilterConstraint[] filter = {hierarchyWithin(referenceName, entityPrimaryKeyInSet(4))};
		assertAll(
			selectsAndOrders(
				session, route, HIERARCHY_OWNERS, filter, byOrder(referenceName, OrderDirection.ASC),
				new int[]{27, 21, 28, 20, 22, 23, 24, 29, 25}, "ascending"
			),
			selectsAndOrders(
				session, route, HIERARCHY_OWNERS, filter, byOrder(referenceName, OrderDirection.DESC),
				new int[]{21, 27, 20, 28, 29, 24, 23, 22, 25}, "descending"
			)
		);
	}

	/**
	 * One hierarchy constraint with its expected node set and order, see
	 * {@link #shouldBoundTraversalByResolvedNodeSet(String, SortRoute, EvitaSessionContract)}.
	 */
	private record HierarchyCase(
		@Nonnull String label,
		@Nonnull FilterConstraint constraint,
		@Nonnull int[] ascending,
		@Nonnull int[] descending
	) {
	}

	/**
	 * Checks the rule "Should bound the traversal by the node set the hierarchy modifiers resolve".
	 */
	@DisplayName("Should bound the traversal by the node set the hierarchy modifiers resolve")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldBoundTraversalByResolvedNodeSet(String referenceName, SortRoute route, EvitaSessionContract session) {
		final HierarchyCase[] cases = {
			// {6, 7}: owner 27's row in the excluded root 4 carries 1, its row in 7 carries 25
			// 6: 20(50) 24- 28(5) -> 28 20 | 7: 22(10) 23(20) 24(30) 27(25) 29(60)
			new HierarchyCase(
				"excludingRoot",
				hierarchyWithin(referenceName, entityPrimaryKeyInSet(4), excludingRoot()),
				new int[]{28, 20, 22, 23, 27, 24, 29}, new int[]{20, 28, 29, 24, 27, 23, 22}
			),
			// {4, 7}: owner 28's row in the excluded 6 carries 5, its row in 7 carries 35
			// 4: 21 25- 27 29- -> 27 21 | 7: 22(10) 23(20) 24(30) 28(35) 29(60) | unplaced 25
			new HierarchyCase(
				"excluding(6)",
				hierarchyWithin(referenceName, entityPrimaryKeyInSet(4), excluding(entityPrimaryKeyInSet(6))),
				new int[]{27, 21, 22, 23, 24, 28, 29, 25}, new int[]{21, 27, 29, 28, 24, 23, 22, 25}
			),
			// {4, 6}: the hidden 7 fails `having`; owners 24 and 29 carry their only value in 7
			// 4: 27 21 | 6: 28(5) 20(50) 24- | unplaced 24 25 29
			new HierarchyCase(
				"having(visible)",
				hierarchyWithin(referenceName, entityPrimaryKeyInSet(4), having(attributeEquals(ATTRIBUTE_VISIBLE, true))),
				new int[]{27, 21, 28, 20, 24, 25, 29}, new int[]{21, 27, 20, 28, 24, 25, 29}
			),
			// {4, 7}: 7 is hidden and 4 is its ancestor, 6 has no hidden node in its subtree - same as excluding(6)
			new HierarchyCase(
				"anyHaving(hidden)",
				hierarchyWithin(
					referenceName, entityPrimaryKeyInSet(4), anyHaving(attributeEquals(ATTRIBUTE_VISIBLE, false))
				),
				new int[]{27, 21, 22, 23, 24, 28, 29, 25}, new int[]{21, 27, 29, 28, 24, 23, 22, 25}
			),
			// {4}: the node itself; owner 29's value sits in 7
			// 4: 27(1) 21(40) 25- 29- | unplaced 25 29
			new HierarchyCase(
				"directRelation",
				hierarchyWithin(referenceName, entityPrimaryKeyInSet(4), directRelation()),
				new int[]{27, 21, 25, 29}, new int[]{21, 27, 25, 29}
			),
			// every node - the same as no bound
			// 1: 20(5) 23(99) 30(3) | 4: 27 21 | 6: 28 24- | 7: 22 24 29 | 5: 26 | 2: 31- 32(4) | 8: 31 | unplaced 25
			new HierarchyCase(
				"hierarchyWithinRoot",
				hierarchyWithinRoot(referenceName),
				new int[]{30, 20, 23, 27, 21, 28, 22, 24, 29, 26, 32, 31, 25},
				new int[]{23, 20, 30, 21, 27, 28, 29, 24, 22, 26, 32, 31, 25}
			),
			// {2, 3, 8, 9}: owners 23 and 30 also sit in category 1, which precedes the bound
			// 2: 23(1) 31- 32(4) -> 23 32 | 3: 30(70) | 8: 31(2)
			new HierarchyCase(
				"hierarchyWithinRoot excluding(1)",
				hierarchyWithinRoot(referenceName, excluding(entityPrimaryKeyInSet(1))),
				new int[]{23, 32, 30, 31}, new int[]{32, 23, 30, 31}
			)
		};
		final List<Executable> assertions = new ArrayList<>(cases.length * 2);
		for (HierarchyCase theCase : cases) {
			final FilterConstraint[] filter = {theCase.constraint()};
			assertions.add(
				selectsAndOrders(
					session, route, HIERARCHY_OWNERS, filter, byOrder(referenceName, OrderDirection.ASC),
					theCase.ascending(), theCase.label() + " ascending"
				)
			);
			assertions.add(
				selectsAndOrders(
					session, route, HIERARCHY_OWNERS, filter, byOrder(referenceName, OrderDirection.DESC),
					theCase.descending(), theCase.label() + " descending"
				)
			);
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should not bound the traversal by a hierarchyWithin under or / not".
	 */
	@DisplayName("Should not bound the traversal by a hierarchyWithin under or / not")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldNotBoundTraversalByHierarchyUnderOrOrNot(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// under `or` the constraint does not hold for owner 26, under `not` it holds for nobody - so neither bounds;
		// every row orders, as without any hierarchy constraint
		// or: 1: 20(5) 23(99) | 4: 27 21 25- 29- | 6: 28 24- | 7: 22 24 29 | 5: 26 | unplaced 25
		// not(within 4) selects 26 30 31 32: 1: 30 | 5: 26 | 2: 31- 32 | 8: 31
		final FilterConstraint[] underOr = {
			or(hierarchyWithin(referenceName, entityPrimaryKeyInSet(4)), entityPrimaryKeyInSet(26))
		};
		final FilterConstraint[] underNot = {not(hierarchyWithin(referenceName, entityPrimaryKeyInSet(4)))};
		assertAll(
			selectsAndOrders(
				session, route, HIERARCHY_OWNERS, underOr, byOrder(referenceName, OrderDirection.ASC),
				new int[]{20, 23, 27, 21, 28, 22, 24, 29, 26, 25}, "or ascending"
			),
			selectsAndOrders(
				session, route, HIERARCHY_OWNERS, underOr, byOrder(referenceName, OrderDirection.DESC),
				new int[]{23, 20, 21, 27, 28, 29, 24, 22, 26, 25}, "or descending"
			),
			selectsAndOrders(
				session, route, HIERARCHY_OWNERS, underNot, byOrder(referenceName, OrderDirection.ASC),
				new int[]{30, 26, 32, 31}, "not ascending"
			)
		);
	}

	/**
	 * Checks the rule "Should bound only the owners of the scope whose inScope holds the hierarchyWithin".
	 *
	 * The PREFETCH rows assert that the plan stays on the index route: with `inScope(LIVE, ...)` in the filter an
	 * ordered query never prefetches (#1682 - `FilterInScopeTranslator` registers the `InScopeFormulaPostProcessor`,
	 * which splits the tree per scope, so the primary key set no longer sits where `PrefetchFormulaVisitor` collects
	 * conjunctive entity ids; by reading). Their order assertion checks the index route under the prefetch
	 * preference.
	 *
	 * #1681 (the `REFERENCED_ENTITY` plan of the `inScope(LIVE, ...)` hierarchy constraint drops every archived owner
	 * once the filter also holds `entityLocaleEquals`) does not reproduce here: this filter has no locale, and the
	 * `{4, 6, 7}` alternative is not even built (HIGH_CARDINALITY). Its witnesses live in the oracle class.
	 */
	@DisplayName("Should bound only the owners of the scope whose inScope holds the hierarchyWithin")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldBoundOnlyOwnersOfScopeHoldingHierarchyConstraint(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// live owners are bounded by {4, 6, 7}, the archived owners 33 and 34 are not constrained at all, so their
		// every row orders - and 34 merges with the live owners of block 7 by value
		// 1: 33(2) | 4: 27 21 | 6: 28(5) 20(50) 24- | 7: 22(10) 34(15) 23(20) 24(30) 29(60) | unplaced 25
		final Integer[] owners = ArrayUtils.mergeArrays(HIERARCHY_OWNERS, new Integer[]{33, 34});
		final FilterConstraint[] filter = {
			scope(BOTH_SCOPES), inScope(Scope.LIVE, hierarchyWithin(referenceName, entityPrimaryKeyInSet(4)))
		};
		assertAll(
			selectsAndOrders(
				session, route, owners, filter, byOrder(referenceName, OrderDirection.ASC),
				new int[]{33, 27, 21, 28, 20, 22, 34, 23, 24, 29, 25}, "ascending"
			),
			selectsAndOrders(
				session, route, owners, filter, byOrder(referenceName, OrderDirection.DESC),
				new int[]{33, 21, 27, 20, 28, 29, 24, 23, 34, 22, 25}, "descending"
			)
		);
	}

	/**
	 * Checks the rule "Should bound the traversal by the union of two conjunctive hierarchy constraints".
	 */
	@DisplayName("Should bound the traversal by the union of two conjunctive hierarchy constraints")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldBoundTraversalByUnionOfConjunctiveHierarchies(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// every selected owner has a row in each subtree; union {4, 6, 7, 2, 3, 8, 9} in either order of the two
		// 6: 35(8) 36- 37- | 7: 23(20) | 8: 37(1) | 9: 36(3)
		// the first constraint alone would leave 36 and 37 unplaced ({4, 6, 7}) or place 23 first ({2, 3, 8, 9}),
		// the intersection is empty and would place nobody
		final Integer[] owners = {23, 35, 36, 37};
		final FilterConstraint within4 = hierarchyWithin(referenceName, entityPrimaryKeyInSet(4));
		final FilterConstraint within2 = hierarchyWithin(referenceName, entityPrimaryKeyInSet(2));
		final int[] expected = UNION_OF_SUBTREES;
		assertAll(
			selectsAndOrders(
				session, route, owners, new FilterConstraint[]{within4, within2},
				byOrder(referenceName, OrderDirection.ASC), expected, "within 4 and within 2"
			),
			selectsAndOrders(
				session, route, owners, new FilterConstraint[]{within2, within4},
				byOrder(referenceName, OrderDirection.ASC), expected, "within 2 and within 4"
			),
			selectsAndOrders(
				session, route, owners, new FilterConstraint[]{within4, within2},
				byOrder(referenceName, OrderDirection.DESC), expected, "within 4 and within 2, descending"
			)
		);
	}

	/**
	 * Checks the rule "Should not change the order when a referenceHaving on the ordered reference selects the owners".
	 */
	@DisplayName("Should not change the order when a referenceHaving on the ordered reference selects the owners")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("allRoutes")
	void shouldNotNarrowTraversalByReferenceHaving(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// kind `y` exists only in targets 2 and 6; every row of a selected owner orders, whatever its kind
		// category (1 4 6 7 5 2): 1: 40(10) | 4: 44(3) | 6: 41(30) | 5: 42(1) | 2: 43(2)
		// brand (1 2 .. 6): 1: 40(10) | 2: 43(2) 42(50) 44(60) | 6: 41(30)
		final boolean hierarchical = isHierarchical(referenceName);
		final FilterConstraint kindX = referenceHaving(referenceName, attributeEquals(ATTRIBUTE_KIND, KIND_X));
		final FilterConstraint kindY = referenceHaving(referenceName, attributeEquals(ATTRIBUTE_KIND, KIND_Y));
		final List<Executable> assertions = new ArrayList<>(12);
		for (OrderDirection direction : OrderDirection.values()) {
			final boolean ascending = direction == OrderDirection.ASC;
			final int[] all = hierarchical ?
				KINDS_HIERARCHICAL :
				(ascending ? new int[]{40, 43, 42, 44, 41} : new int[]{40, 44, 42, 43, 41});
			// owners 40, 42 and 44 hold a row of each kind; each referenceHaving binds its own row
			final int[] bothKinds = hierarchical ?
				new int[]{40, 44, 42} : (ascending ? new int[]{40, 42, 44} : new int[]{40, 44, 42});
			final OrderConstraint ordering = byOrder(referenceName, direction);
			assertions.add(selectsAndOrders(session, route, KIND_OWNERS, NO_FILTER, ordering, all, direction + " plain"));
			assertions.add(
				selectsAndOrders(
					session, route, KIND_OWNERS, new FilterConstraint[]{kindY}, ordering, all, direction + " kind y"
				)
			);
			assertions.add(
				selectsAndOrders(
					session, route, KIND_OWNERS, new FilterConstraint[]{kindX, kindY}, ordering, bothKinds,
					direction + " kind x and kind y"
				)
			);
			assertions.add(
				selectsAndOrders(
					session, route, KIND_OWNERS, new FilterConstraint[]{kindY, kindX}, ordering, bothKinds,
					direction + " kind y and kind x"
				)
			);
			if (hierarchical) {
				// the hierarchy bounds, the referenceHaving before it does not: node set {1, 4, 5, 6, 7}
				// 1: 40(10) | 4: 44(3) | 6: 41(30) | 5: 42(1); owner 43 has no row within 1
				assertions.add(
					selectsAndOrders(
						session, route, KIND_OWNERS,
						new FilterConstraint[]{kindY, hierarchyWithin(referenceName, entityPrimaryKeyInSet(1))},
						ordering, KIND_Y_WITHIN_1, direction + " kind y and within 1"
					)
				);
			}
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should merge the owners of both scopes inside one block by value and primary key".
	 */
	@DisplayName("Should merge the owners of both scopes inside one block by value and primary key")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("allRoutes")
	void shouldMergeOwnersOfBothScopesInsideBlock(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// block 3 holds live 50(20) 52(30) and archived 51(10) 53(30); 52 and 53 tie across scopes
		// category (5 before 3 before 8): 5: 54 | 3: 51 50 52 53 | 8: 55
		// brand (3 5 8): 3: 51 50 52 53 | 5: 54 | 8: 55
		final boolean hierarchical = isHierarchical(referenceName);
		final FilterConstraint[] bothScopes = {scope(BOTH_SCOPES)};
		assertAll(
			() -> assertOrder(
				hierarchical ? BOTH_SCOPES_HIERARCHICAL_ASCENDING : new int[]{51, 50, 52, 53, 54, 55},
				queryOrder(session, route, SCOPE_OWNERS, bothScopes, byOrder(referenceName, OrderDirection.ASC)),
				"ascending"
			),
			() -> assertOrder(
				hierarchical ? new int[]{54, 53, 52, 50, 51, 55} : new int[]{53, 52, 50, 51, 54, 55},
				queryOrder(session, route, SCOPE_OWNERS, bothScopes, byOrder(referenceName, OrderDirection.DESC)),
				"descending"
			)
		);
	}

	/**
	 * Checks the rule "Should hand the owners outside the ordering scope to the next sorter".
	 */
	@DisplayName("Should hand the owners outside the ordering scope to the next sorter")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("allRoutes")
	void shouldHandOwnersOutsideOrderingScopeToNextSorter(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// the filter selects both scopes, the ordering sorts the live owners only: 3: 50 52 | 8: 55, then the archived
		// 51 53 54 unsorted - also when a narrowing constraint on the reference offers archived reduced indexes
		final boolean hierarchical = isHierarchical(referenceName);
		final FilterConstraint narrowing = hierarchical ?
			hierarchyWithin(referenceName, entityPrimaryKeyInSet(2)) :
			referenceHaving(referenceName, attributeEquals(ATTRIBUTE_KIND, KIND_X));
		final List<Executable> assertions = new ArrayList<>(4);
		for (OrderDirection direction : OrderDirection.values()) {
			final boolean ascending = direction == OrderDirection.ASC;
			final OrderConstraint ordering = inScope(Scope.LIVE, byOrder(referenceName, direction));
			final int[] plain = ascending ? new int[]{50, 52, 55, 51, 53, 54} : new int[]{52, 50, 55, 51, 53, 54};
			// within 2 does not select 54 (category 5); kind x selects all
			final int[] narrowed = hierarchical ?
				(ascending ? new int[]{50, 52, 55, 51, 53} : new int[]{52, 50, 55, 51, 53}) : plain;
			assertions.add(
				() -> assertOrder(
					plain,
					queryOrder(session, route, SCOPE_OWNERS, new FilterConstraint[]{scope(BOTH_SCOPES)}, ordering),
					direction + " plain"
				)
			);
			assertions.add(
				() -> assertOrder(
					narrowed,
					queryOrder(
						session, route, SCOPE_OWNERS, new FilterConstraint[]{scope(BOTH_SCOPES), narrowing}, ordering
					),
					direction + " narrowed"
				)
			);
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should traverse the live targets of archived owners".
	 */
	@DisplayName("Should traverse the live targets of archived owners")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("allRoutes")
	void shouldTraverseLiveTargetsOfArchivedOwners(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// archived owners 51 (3,10), 53 (3,30), 54 (5,5) reference live targets; a target is walked in the tree of
		// its own scope, so the live tree orders them
		// category (5 before 3): 5: 54 | 3: 51 53;  brand (3 5): 3: 51 53 | 5: 54
		final boolean hierarchical = isHierarchical(referenceName);
		final FilterConstraint[] archivedOnly = {scope(Scope.ARCHIVED)};
		assertAll(
			() -> assertOrder(
				hierarchical ? new int[]{54, 51, 53} : new int[]{51, 53, 54},
				queryOrder(session, route, SCOPE_OWNERS, archivedOnly, byOrder(referenceName, OrderDirection.ASC)),
				"ascending"
			),
			() -> assertOrder(
				hierarchical ? new int[]{54, 53, 51} : new int[]{53, 51, 54},
				queryOrder(session, route, SCOPE_OWNERS, archivedOnly, byOrder(referenceName, OrderDirection.DESC)),
				"descending"
			)
		);
	}

	/**
	 * Checks the rule "Should walk the live target tree before the archived one".
	 *
	 * The two rank-ordered assertions are the witnesses of *extra defect 1*: a traversal ordered by a target attribute
	 * throws "The node `10` is not present in the index!" as soon as a row reaches the archived category 10 - even for
	 * the live-only query - because every scope's tree is asked for the parents of the whole family's targets. The
	 * defect is fixed inside #1670 (a target is walked in the tree of its own scope); until then a red `by rank`
	 * assertion is defect 1, not a failure of the live-then-archived tree order, which the primary key assertion pins
	 * on its own.
	 */
	@DisplayName("Should walk the live target tree before the archived one")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("sortRoutes")
	void shouldWalkLiveTargetTreeBeforeArchivedOne(SortRoute route, EvitaSessionContract session) {
		// the archived root 10 would precede roots 2 and 1 in one tree walked by descending primary key; walked
		// separately it follows the whole live tree: 2: 61(7) | 1: 62(9) | 10: 60(5); owner 62's row in 10 carries
		// 1 but it is placed in block 1 already
		// by rank the live tree is 1 5 4 6 7 2 8 9 3, then 10: 1: 62 | 2: 61 | 10: 60; a live-only query must not
		// fail on the live owners' rows to the archived target either - owner 60 is last whether or not the archived
		// tree is walked for it
		final FilterConstraint[] bothScopes = {scope(BOTH_SCOPES)};
		final OrderConstraint byRankOrdering = referenceProperty(
			REFERENCE_CATEGORIES_ACROSS_SCOPES,
			traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural(ATTRIBUTE_RANK)),
			attributeNatural(ATTRIBUTE_ORDER)
		);
		final Executable byDescendingPrimaryKey = () -> assertOrder(
			new int[]{61, 62, 60},
			queryOrder(
				session, route, ACROSS_SCOPES_OWNERS, bothScopes,
				referenceProperty(
					REFERENCE_CATEGORIES_ACROSS_SCOPES,
					traverseByEntityProperty(TraversalMode.DEPTH_FIRST, entityPrimaryKeyNatural(OrderDirection.DESC)),
					attributeNatural(ATTRIBUTE_ORDER)
				)
			),
			"by descending primary key"
		);
		final Executable byRank = () -> assertOrder(
			new int[]{62, 61, 60},
			queryOrder(session, route, ACROSS_SCOPES_OWNERS, bothScopes, byRankOrdering),
			"by rank (extra defect 1 witness)"
		);
		final Executable byRankLiveOnly = () -> assertOrder(
			new int[]{62, 61, 60},
			queryOrder(session, route, ACROSS_SCOPES_OWNERS, NO_FILTER, byRankOrdering),
			"by rank, live scope only (extra defect 1 witness)"
		);
		assertAll(byDescendingPrimaryKey, byRank, byRankLiveOnly);
	}

	/**
	 * Checks the rule "Should place a live owner in the block of its archived target, walked in the archived tree".
	 *
	 * The ordering sorts the live scope only, while owners 131 and 132 reference nothing but the archived categories 3
	 * and 4 - a target is walked in the tree of its own scope, so their blocks exist, after the (here empty) live
	 * tree, and both precede owner 130, which has no row and is handed to the next sorter. The `rank` of the scoped
	 * category is sortable in the live scope only, so a nested order cannot read it in the archived tree: the
	 * siblings 3 and 4 keep their primary key order there, although their ranks (20, 10) say the opposite.
	 *
	 * What the rejected readings give: skipping the archived tree hands all three owners to the next sorter
	 * `[130, 131, 132]`; ordering the archived siblings by rank `[132, 131, 130]`.
	 */
	@DisplayName("Should place a live owner in the block of its archived target, walked in the archived tree")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("sortRoutes")
	void shouldPlaceLiveOwnerInBlockOfArchivedTarget(SortRoute route, EvitaSessionContract session) {
		final OrderConstraint byOrder = attributeNatural(ATTRIBUTE_ORDER);
		assertAll(
			() -> assertOrder(
				new int[]{131, 132, 130},
				queryOrder(
					session, route, SCOPED_CATEGORY_OWNERS, NO_FILTER,
					referenceProperty(
						REFERENCE_SCOPED_CATEGORIES,
						traverseByEntityProperty(TraversalMode.DEPTH_FIRST, entityPrimaryKeyNatural(OrderDirection.ASC)),
						byOrder
					)
				),
				"by primary key"
			),
			() -> assertOrder(
				new int[]{132, 131, 130},
				queryOrder(
					session, route, SCOPED_CATEGORY_OWNERS, NO_FILTER,
					referenceProperty(
						REFERENCE_SCOPED_CATEGORIES,
						traverseByEntityProperty(
							TraversalMode.DEPTH_FIRST, entityPrimaryKeyNatural(OrderDirection.DESC)
						),
						byOrder
					)
				),
				"by descending primary key"
			),
			() -> assertOrder(
				new int[]{131, 132, 130},
				queryOrder(
					session, route, SCOPED_CATEGORY_OWNERS, NO_FILTER,
					referenceProperty(
						REFERENCE_SCOPED_CATEGORIES,
						traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural(ATTRIBUTE_RANK)),
						byOrder
					)
				),
				"by rank sortable in the live scope only"
			)
		);
	}

	/**
	 * Checks that the complete node union resolved over the filter scopes bounds every covered owner scope, even
	 * when the ordering covers fewer scopes. A live owner can reference an archived target admitted by that union.
	 */
	@DisplayName("Should retain nodes from every target tree resolved by a conjunctive hierarchy filter")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("sortRoutes")
	void shouldRetainResolvedTargetsOutsideOrderingScope(SortRoute route, EvitaSessionContract session) {
		final OrderConstraint withinArchivedRoot = referenceProperty(
			REFERENCE_CATEGORIES_ACROSS_SCOPES, traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
			attributeNatural(ATTRIBUTE_ORDER)
		);
		final OrderConstraint fromArchivedRoots = referenceProperty(
			REFERENCE_SCOPED_CATEGORIES,
			traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.DESC)), attributeNatural(ATTRIBUTE_ORDER)
		);
		// Owners 62 and 60 carry 1 and 5 in archived target 10; bounding their live partitions by the live tree
		// alone drops both blocks and falls back to [60, 62]. The filter resolves target 10 in the archived tree.
		final FilterConstraint[] within = {
			scope(BOTH_SCOPES), hierarchyWithin(REFERENCE_CATEGORIES_ACROSS_SCOPES, entityPrimaryKeyInSet(10))
		};
		final FilterConstraint[] fromRoot = {scope(BOTH_SCOPES), hierarchyWithinRoot(REFERENCE_SCOPED_CATEGORIES)};
		assertAll(
			selectsAndOrders(
				session, route, ACROSS_SCOPES_OWNERS, within, withinArchivedRoot,
				new int[]{62, 60}, "within archived root over both owner scopes"
			),
			selectsAndOrders(
				session, route, ACROSS_SCOPES_OWNERS, within, inScope(Scope.LIVE, withinArchivedRoot),
				new int[]{62, 60}, "within archived root ordered in the live scope only"
			),
			selectsAndOrders(
				session, route, SCOPED_CATEGORY_OWNERS, fromRoot, fromArchivedRoots,
				new int[]{132, 131}, "from root over both owner scopes"
			),
			selectsAndOrders(
				session, route, SCOPED_CATEGORY_OWNERS, fromRoot, inScope(Scope.LIVE, fromArchivedRoots),
				new int[]{132, 131}, "from root ordered in the live scope only"
			)
		);
	}

	/**
	 * Checks the rule "Should place each owner once among rows sharing one target, by its first valued row".
	 */
	@DisplayName("Should place each owner once among rows sharing one target, by its first valued row")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("duplicateRoutes")
	void shouldPlaceOwnerOnceAmongDuplicateRows(String referenceName, SortRoute route, EvitaSessionContract session) {
		// one block per target; each owner is represented by its first row carrying the value in representative
		// order (a < b < c < z, whatever the direction): 72 by (b, 50) as (a) is value-less, 73 by (b, 60) although
		// (c) carries 5; then owners merge by value - 70 (z, 1) precedes 71 (a, 100) whatever the representatives say
		// 1: 74(60) | 2: 70(1) 72(50) 73(60) 71(100)
		// what each rejected reading would give instead (ascending / descending):
		// - best value across the rows (73 by 5 ascending, 60 descending): [74, 70, 73, 72, 71] / [74, 71, 73, 72, 70]
		// - last valued row in representative order (73 by 5): [74, 70, 73, 72, 71] / [74, 71, 72, 73, 70]
		// - first row whether valued or not (72 by the value-less a): [74, 70, 73, 71, 72] / [74, 71, 73, 70, 72]
		// - one sub-block per representative, a before b before c before z: [74, 71, 72, 73, 70] / [74, 71, 73, 72, 70]
		// - today's engine (C6, only the first representative key of target 2 is sorted): [74, 70, 71, 72, 73]
		final OrderConstraint ascending = referenceProperty(
			referenceName, traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
			attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
		);
		final OrderConstraint descending = referenceProperty(
			referenceName, traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
			attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)
		);
		assertAll(
			() -> assertOrder(
				new int[]{74, 70, 72, 73, 71},
				queryOrder(session, route, DUPLICATE_OWNERS, NO_FILTER, ascending), "ascending"
			),
			() -> assertOrder(
				new int[]{74, 71, 73, 72, 70},
				queryOrder(session, route, DUPLICATE_OWNERS, NO_FILTER, descending), "descending"
			)
		);
	}

	/**
	 * Checks the rule "Should place owners of representative values that compare equal but differ, in one block".
	 *
	 * `BigDecimal` 1.0 and 1.00 compare equal but are two different representative keys - brand 2 holds two reduced
	 * indexes whose keys the representative order cannot tell apart. Planning must not take them for one index
	 * listed twice (it did, with a premise violation, when the duplicate check was made by the order's equality), and
	 * the block of brand 2 orders both owners by value: 141 (5) before 140 (10).
	 */
	@DisplayName("Should place owners of representative values that compare equal but differ, in one block")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("sortRoutes")
	void shouldPlaceOwnersOfEqualComparingRepresentativeValues(SortRoute route, EvitaSessionContract session) {
		assertAll(
			() -> assertOrder(
				new int[]{141, 140},
				queryOrder(
					session, route, DECIMAL_VARIANT_OWNERS, NO_FILTER,
					traverseOrdering(REFERENCE_DECIMAL_VARIANTS, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC))
				),
				"ascending"
			),
			() -> assertOrder(
				new int[]{140, 141},
				queryOrder(
					session, route, DECIMAL_VARIANT_OWNERS, NO_FILTER,
					traverseOrdering(REFERENCE_DECIMAL_VARIANTS, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC))
				),
				"descending"
			)
		);
	}

	/**
	 * Checks the rule "Should keep the index-route order of a chain under an implicit pick-first unchanged by the
	 * traversal".
	 *
	 * A chain under an implicit `pickFirst` (brand is a flat target) is outside #1670 - it walks the planning-time
	 * indexes without blocks. This pins that the representative key order the traversal applies to the indexes of one
	 * target does not leak into it: the indexes of brand 2 stay in the order they were met (b, c, a - created in that
	 * order), so the provider of b claims 151 before the provider of c claims 150. Sorting the keys would give
	 * `[150, 151]` (a claims 150 first).
	 *
	 * The index route only: the prefetch route of this path picks, without any block, the first stored row whose key
	 * has a chain, and on this fixture it returns `[150, 151]` - before the traversal changes and after them alike, so
	 * the two routes of this out-of-scope path disagree here whichever order the planning keeps; that is pinned
	 * neither way.
	 */
	@DisplayName("Should keep the index-route order of a chain under an implicit pick-first unchanged by the traversal")
	@UseDataSet(TRAVERSE_REFERENCES)
	@Test
	void shouldKeepImplicitPickFirstChainOrderOnIndexRoute(EvitaSessionContract session) {
		assertOrder(
			new int[]{151, 150},
			queryOrder(
				session, SortRoute.INDEX, CHAIN_VARIANT_OWNERS, NO_FILTER,
				referenceProperty(REFERENCE_CHAIN_VARIANTS, attributeNatural(ATTRIBUTE_CHAIN))
			),
			"implicit pick-first chain"
		);
	}

	/**
	 * Checks the rule "Should order a block by owner primary key when ordering by the referenced primary key".
	 *
	 * The PREFETCH rows do prefetch today (the route is taken) and then fail with `ContextMissingException`: the
	 * traverse branch of `entityPrimaryKeyNatural` never registers `referenceContent(R)` (C3a), so the comparator reads
	 * a reference that was not fetched. The INDEX rows fail on the descending ties (C3c). The subset row was built for
	 * C3b (a strict subset of a provider through the array-backed merge walk); whether its phantom match shows depends
	 * on the contents of pooled buffers, so from run to run the subset row is red for C3b, red for C3c, or green on a
	 * lucky tie - the oracle reproduces C3b steadily, and P2 owns a deterministic unit test of the merge walk.
	 */
	@DisplayName("Should order a block by owner primary key when ordering by the referenced primary key")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("allRoutes")
	void shouldOrderBlockByOwnerPrimaryKeyWhenOrderingByReferencedPrimaryKey(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// every row carries the referenced primary key, so an owner lands in the block of its first row and all
		// owners of a block tie - they follow their own primary key in the direction of the ordering
		// category (1 4 6 7 5 2 3 8 9): 1: 3 6 | 4: 2 9 | 6: 10 | 7: 1 | 5: 4 5 11 | 8: 7 | unplaced 8
		// brand (1 .. 9): 1: 3 6 | 2: 5 10 | 3: 1 | 4: 2 9 | 5: 4 11 | 8: 7 | unplaced 8
		// the subset 2 9 11 fills block 4 with two of its three owners and block 5 with one of three
		final boolean hierarchical = isHierarchical(referenceName);
		final Integer[] subset = {2, 9, 11};
		final List<Executable> assertions = new ArrayList<>(4);
		for (OrderDirection direction : OrderDirection.values()) {
			final boolean ascending = direction == OrderDirection.ASC;
			final int[] expected = hierarchical ?
				(ascending ? new int[]{3, 6, 2, 9, 10, 1, 4, 5, 11, 7, 8} : new int[]{6, 3, 9, 2, 10, 1, 11, 5, 4, 7, 8}) :
				(ascending ? new int[]{3, 6, 5, 10, 1, 2, 9, 4, 11, 7, 8} : new int[]{6, 3, 10, 5, 1, 9, 2, 11, 4, 7, 8});
			final int[] expectedSubset = ascending ? new int[]{2, 9, 11} : new int[]{9, 2, 11};
			final OrderConstraint ordering = traverseOrdering(referenceName, entityPrimaryKeyNatural(direction));
			assertions.add(
				() -> assertOrder(
					expected, queryOrder(session, route, BASIC_OWNERS, NO_FILTER, ordering), direction + " all"
				)
			);
			assertions.add(
				() -> assertOrder(
					expectedSubset, queryOrder(session, route, subset, NO_FILTER, ordering), direction + " subset"
				)
			);
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should place owners that are alone in their blocks when ordering by the referenced primary key".
	 *
	 * Every block of the selection holds a single owner, so sorting a block compares nothing - the prefetch route
	 * then still has to read each owner's row of the block to tell whether the owner belongs there, which needs the
	 * references prefetched although the query fetches no entity body. The ordering is right whatever the tie-break,
	 * so this row isolates the prefetch requirement of the traverse branch from C3b/C3c.
	 */
	@DisplayName("Should place owners alone in their blocks when ordering by the referenced primary key")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("allRoutes")
	void shouldPlaceLoneOwnersWhenOrderingByReferencedPrimaryKey(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// category (1 4 6 7 5 2 3 8 9): 6: 10 | 7: 1 | 8: 7 | unplaced 8
		// brand (1 .. 9): 2: 10 | 3: 1 | 8: 7 | unplaced 8
		final Integer[] loneOwners = {1, 7, 8, 10};
		final List<Executable> assertions = new ArrayList<>(2);
		for (OrderDirection direction : OrderDirection.values()) {
			assertions.add(
				() -> assertOrder(
					new int[]{10, 1, 7, 8},
					queryOrder(
						session, route, loneOwners, NO_FILTER,
						traverseOrdering(referenceName, entityPrimaryKeyNatural(direction))
					),
					direction.toString()
				)
			);
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should order by a sortable attribute compound inside each block".
	 *
	 * Owners 81 and 86 carry the identical tuple, so the descending assertion on the prefetch route is the witness of
	 * *extra defect 2*: the prefetch compound comparator has no owner primary key tie-break and keeps tied owners in
	 * input order. The tie-break is owned by P4, next to the one of `entityPrimaryKeyNatural` (C3c).
	 */
	@DisplayName("Should order by a sortable attribute compound inside each block")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("allRoutes")
	void shouldOrderByCompoundInsideBlock(String referenceName, SortRoute route, EvitaSessionContract session) {
		// compound (order ASC nulls last, second DESC nulls first); a tuple with both elements missing carries no
		// value, a partial one does; owners 81 and 86 carry the same tuple and follow their primary key in the
		// direction of the ordering
		// 4: 83(10,-) 81(10,5) 86(10,5) 80(10,1) 82(-,3), 84(-,-) passes on | 5: 85(1,-) 84(2,2); descending
		// reverses both
		assertAll(
			() -> assertOrder(
				new int[]{83, 81, 86, 80, 82, 85, 84},
				queryOrder(
					session, route, COMPOUND_OWNERS, NO_FILTER,
					traverseOrdering(referenceName, attributeNatural(COMPOUND_ORDER_SECOND, OrderDirection.ASC))
				),
				"ascending"
			),
			() -> assertOrder(
				new int[]{82, 80, 86, 81, 83, 84, 85},
				queryOrder(
					session, route, COMPOUND_OWNERS, NO_FILTER,
					traverseOrdering(referenceName, attributeNatural(COMPOUND_ORDER_SECOND, OrderDirection.DESC))
				),
				"descending (the 81/86 tie: extra defect 2 on the prefetch route)"
			)
		);
	}

	/**
	 * Checks the rule "Should apply several child sorters block by block and hand the rest to the outer sorter".
	 *
	 * The outer sorter is the owner attribute `tail` rather than `entityPrimaryKeyNatural(DESC)`, whose reversed
	 * primary key sorter mis-slices a page with an offset (#1671, fixed by PR #1672) - a red caused there would be
	 * misattributed to this rule.
	 *
	 * On the prefetch route owner 94 is the lone member the second child sorter receives in block 5 - a value-less
	 * owner alone in a sort that never compares it, so the block claims it (C2) and it precedes owner 96.
	 */
	@DisplayName("Should apply several child sorters block by block and hand the rest to the outer sorter")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("allRoutes")
	void shouldApplyChildSortersBlockByBlock(String referenceName, SortRoute route, EvitaSessionContract session) {
		// inside a block the first child sorts the owners carrying `order`, the second the rest carrying `second`;
		// an owner carrying neither moves on - so owner 93 is placed in block 1 by `second` although its row in
		// block 4 carries `order`; owners 94 and 96 carry nothing and follow by their `tail` (96: 1, 94: 2)
		// category (1 4 6 7 5): 1: 93(s1) | 4: 90(o5), 92(s3) 91(s7) | 5: 95(o2), 94 passes | unplaced 96 94
		// brand (1 4 5): the same blocks
		final List<Executable> assertions = new ArrayList<>(2);
		for (OrderDirection direction : OrderDirection.values()) {
			final int[] expected = direction == OrderDirection.ASC ?
				new int[]{93, 90, 92, 91, 95, 96, 94} : new int[]{93, 90, 91, 92, 95, 96, 94};
			final OrderConstraint ordering = traverseOrdering(
				referenceName, attributeNatural(ATTRIBUTE_ORDER, direction), attributeNatural(ATTRIBUTE_SECOND, direction)
			);
			assertions.add(
				() -> assertOrder(
					expected,
					queryOrder(
						session, route, CHAIN_OWNERS, NO_FILTER, ordering, attributeNatural(ATTRIBUTE_TAIL)
					),
					direction.toString()
				)
			);
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should return consistent pages across block, scope and fallback boundaries".
	 */
	@DisplayName("Should return consistent pages across block, scope and fallback boundaries")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldReturnConsistentPagesAcrossBoundaries(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// the basic group whose owners without a value fall through to the outer sorter `tail` (8: 1, 4: 2), the scopes
		// group merged across scopes (every owner placed, the outer sorter has nothing to do), and the live-only
		// ordering whose archived owners fall through to the outer sorter (51: 1, 54: 2, 53: 3); the outer sorter is an
		// owner attribute - `entityPrimaryKeyNatural(DESC)` there mis-slices a page with an offset (#1671)
		final FilterConstraint[] bothScopes = {scope(BOTH_SCOPES)};
		final OrderConstraint fallback = attributeNatural(ATTRIBUTE_TAIL);
		final List<Executable> assertions = new ArrayList<>(15);
		for (int pageSize : new int[]{1, 2, 3, 4, 5}) {
			assertions.add(
				() -> assertOrder(
					new int[]{6, 3, 9, 2, 10, 1, 5, 11, 7, 8, 4},
					queryAllPages(
						session, route, BASIC_OWNERS, NO_FILTER, BASIC_OWNERS.length, pageSize,
						byOrder(referenceName, OrderDirection.ASC), fallback
					),
					"basic by pages of " + pageSize
				)
			);
			assertions.add(
				() -> assertOrder(
					BOTH_SCOPES_HIERARCHICAL_ASCENDING,
					queryAllPages(
						session, route, SCOPE_OWNERS, bothScopes, SCOPE_OWNERS.length, pageSize,
						byOrder(referenceName, OrderDirection.ASC), fallback
					),
					"both scopes by pages of " + pageSize
				)
			);
			assertions.add(
				() -> assertOrder(
					new int[]{50, 52, 55, 51, 54, 53},
					queryAllPages(
						session, route, SCOPE_OWNERS, bothScopes, SCOPE_OWNERS.length, pageSize,
						inScope(Scope.LIVE, byOrder(referenceName, OrderDirection.ASC)), fallback
					),
					"live ordering by pages of " + pageSize
				)
			);
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should bound a pickFirst ordering by the node set of a hierarchyWithin on the ordered reference".
	 */
	@DisplayName("Should bound a pickFirst ordering by the node set of a hierarchyWithin on the ordered reference")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldBoundPickFirstByHierarchyWithin(String referenceName, SortRoute route, EvitaSessionContract session) {
		// pickFirst takes each owner's first valued row in target order (primary key) among the rows the hierarchy
		// admits, then merges all owners by value: owner 20's first row (1, 5) and owner 23's (1, 99) lie outside
		// the subtree of 4, so they sort on (6, 50) and (7, 20); under excludingRoot owner 27 loses (4, 1) to (7, 25)
		// within 4: 27(1) 28(5) 22(10) 23(20) 24(30) 21(40) 20(50) 29(60), unplaced 25
		// within 4 excluding root: 28(5) 22(10) 23(20) 27(25) 24(30) 20(50) 29(60)
		final List<Executable> assertions = new ArrayList<>(4);
		for (OrderDirection direction : OrderDirection.values()) {
			final boolean ascending = direction == OrderDirection.ASC;
			final OrderConstraint ordering = referenceProperty(
				referenceName, pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
				attributeNatural(ATTRIBUTE_ORDER, direction)
			);
			assertions.add(
				selectsAndOrders(
					session, route, HIERARCHY_OWNERS,
					new FilterConstraint[]{hierarchyWithin(referenceName, entityPrimaryKeyInSet(4))}, ordering,
					ascending ?
						new int[]{27, 28, 22, 23, 24, 21, 20, 29, 25} : new int[]{29, 20, 21, 24, 23, 22, 28, 27, 25},
					direction + " within 4"
				)
			);
			assertions.add(
				selectsAndOrders(
					session, route, HIERARCHY_OWNERS,
					new FilterConstraint[]{hierarchyWithin(referenceName, entityPrimaryKeyInSet(4), excludingRoot())},
					ordering,
					ascending ? new int[]{28, 22, 23, 27, 24, 20, 29} : new int[]{29, 20, 24, 27, 23, 22, 28},
					direction + " within 4 excluding root"
				)
			);
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should place the live chain of a target before its archived chain".
	 *
	 * A chain attribute carries no value two owners could be merged by, so D3 makes it the exception to the in-block
	 * merge of the scopes: the live chain of a target, then its archived chain. P3 replaces the concatenation of the
	 * block's providers by a merge and must keep it for chains - this is the witness it depends on. Only the ascending
	 * direction is pinned; D3 does not say whether a descending chain order also reverses the scope precedence.
	 *
	 * The archived-scope assertion also needs the live target of an archived owner to be walked in the live tree (see
	 * {@link #shouldTraverseLiveTargetsOfArchivedOwners(String, SortRoute, EvitaSessionContract)}); on the category
	 * references it is red today for that reason alone - today the archived tree, which lacks category 3, is walked.
	 */
	@DisplayName("Should place the live chain of a target before its archived chain")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("chainRoutes")
	void shouldPlaceLiveChainBeforeArchivedChainInsideBlock(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// target 3 holds the live chain 102 -> 100 -> 104 and the archived chain 103 -> 101; target 8 follows target 3
		// both in the category walk (... 2 3 8 9) and in the brand primary key order
		// both scopes: 3: 102 100 104, then 103 101 | 8: 105
		// what each rejected reading would give instead: a merge by chain position [102, 103, 100, 101, 104, 105],
		// the archived chain first [103, 101, 102, 100, 104, 105], primary key order [100, 101, 102, 103, 104, 105]
		final OrderConstraint ordering = traverseOrdering(
			referenceName, attributeNatural(ATTRIBUTE_CHAIN, OrderDirection.ASC)
		);
		assertAll(
			() -> assertOrder(
				new int[]{102, 100, 104, 103, 101, 105},
				queryOrder(session, route, PREDECESSOR_OWNERS, new FilterConstraint[]{scope(BOTH_SCOPES)}, ordering),
				"both scopes"
			),
			() -> assertOrder(
				new int[]{102, 100, 104, 105},
				queryOrder(session, route, PREDECESSOR_OWNERS, NO_FILTER, ordering),
				"live scope"
			),
			() -> assertOrder(
				new int[]{103, 101},
				queryOrder(session, route, PREDECESSOR_OWNERS, new FilterConstraint[]{scope(Scope.ARCHIVED)}, ordering),
				"archived scope"
			)
		);
	}

	/**
	 * Checks the rule "Should bound a pickFirst ordering by target rank to the node set of a hierarchyWithin" - the
	 * path of D6 that orders the targets by a nested sorter (`getTargetRank`, the nested-order probe), which the
	 * primary key witness above does not reach.
	 */
	@DisplayName("Should bound a pickFirst ordering by target rank to the node set of a hierarchyWithin")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldBoundRankOrderedPickFirstByHierarchyWithin(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// ranks: 6 (10) before 4 (20) - the reverse of their primary keys; the bound {4, 6, 7} leaves out 8 and 5 (both
		// rank 10), which would otherwise be the first row of owners 111 and 114; no owner has two rows of one rank
		// 110: 6 before 4 -> 50 | 111: (8, 1) outside -> (4, 30) | 112: (6, -) passes -> (4, 20) | 113: (6, 40) |
		// 114: (5, 5) outside, (4, -) -> unplaced
		// what each rejected reading would give ascending: every row (today) [111, 114, 112, 113, 110], the bound
		// but targets by primary key [110, 112, 111, 113, 114]
		final FilterConstraint[] filter = {hierarchyWithin(referenceName, entityPrimaryKeyInSet(4))};
		final List<Executable> assertions = new ArrayList<>(2);
		for (OrderDirection direction : OrderDirection.values()) {
			final OrderConstraint ordering = referenceProperty(
				referenceName, pickFirstByEntityProperty(attributeNatural(ATTRIBUTE_RANK)),
				attributeNatural(ATTRIBUTE_ORDER, direction)
			);
			assertions.add(
				selectsAndOrders(
					session, route, PICK_FIRST_RANK_OWNERS, filter, ordering,
					direction == OrderDirection.ASC ?
						new int[]{112, 111, 113, 110, 114} : new int[]{110, 113, 111, 112, 114},
					direction + " within 4"
				)
			);
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should not bound the traversal by a facetHaving on the ordered reference" - E4 names
	 * `facetHaving` explicitly: it selects owners by their targets like a hierarchy constraint does, but it never
	 * bounds the traversal, whether it stands in `filterBy` or in `userFilter`.
	 */
	@DisplayName("Should not bound the traversal by a facetHaving on the ordered reference")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("allRoutes")
	void shouldNotBoundTraversalByFacetHaving(String referenceName, SortRoute route, EvitaSessionContract session) {
		// facetHaving(R, 7) selects the owners with a row in target 7 - 22 23 24 27 28 29 - and every row of each of
		// them orders; category (1 4 6 7 ...) and brand (1 2 ... 7) give the same blocks here:
		// 1: 23(99) | 4: 27(1), 29- | 6: 28(5), 24- | 7: 22(10) 24(30) 29(60)
		// the rejected bound to {7} would give [22, 23, 27, 24, 28, 29] ascending
		final FilterConstraint facet = facetHaving(referenceName, entityPrimaryKeyInSet(7));
		final List<Executable> assertions = new ArrayList<>(4);
		for (OrderDirection direction : OrderDirection.values()) {
			final int[] expected = direction == OrderDirection.ASC ?
				new int[]{23, 27, 28, 22, 24, 29} : new int[]{23, 27, 28, 29, 24, 22};
			assertions.add(
				selectsAndOrders(
					session, route, HIERARCHY_OWNERS, new FilterConstraint[]{facet}, byOrder(referenceName, direction),
					expected, direction + " facetHaving"
				)
			);
			assertions.add(
				selectsAndOrders(
					session, route, HIERARCHY_OWNERS, new FilterConstraint[]{userFilter(facet)},
					byOrder(referenceName, direction), expected, direction + " userFilter(facetHaving)"
				)
			);
		}
		assertAll(assertions);
	}

	/**
	 * Returns `referenceHaving(tags, kind x)` - a shape the bidirectional rewrite answers from the counterpart.
	 */
	@Nonnull
	private static FilterConstraint rewritableKindX() {
		return referenceHaving(REFERENCE_TAGS, attributeEquals(ATTRIBUTE_KIND, KIND_X));
	}

	/**
	 * Returns the same condition as {@link #rewritableKindX()} in a shape the rewrite declines: the added
	 * `entityPrimaryKeyInSet` over every tag holds for every row, so it changes no answer, but a child that is neither
	 * `entityHaving` nor a reference-attribute constraint makes `BidirectionalReferenceRewriter#splitChildren` fall
	 * through to the owner-side evaluation.
	 */
	@Nonnull
	private static FilterConstraint notRewritableKindX() {
		return referenceHaving(
			REFERENCE_TAGS,
			attributeEquals(ATTRIBUTE_KIND, KIND_X),
			entityPrimaryKeyInSet(IntStream.rangeClosed(1, TAG_COUNT).boxed().toArray(Integer[]::new))
		);
	}

	/**
	 * Tells whether index selection registered the owner-side reference index option - i.e. whether the
	 * bidirectional rewrite declined the `referenceHaving` (its early return in
	 * `IndexSelectionVisitor#addReferenceIndexOption` is the only thing that removes the option).
	 */
	private static boolean referenceIndexOptionRegistered(@Nonnull RoutedResult result) {
		return result.alternatives().stream().anyMatch(it -> it.startsWith(REFERENCE_INDEX_OPTION_PREFIX));
	}

	/**
	 * Checks the rule "Should order identically whether the bidirectional rewrite answers the referenceHaving or not".
	 *
	 * The rewrite is not switched by any public setting - it is taken whenever the shape and the index sizes allow it
	 * (`BidirectionalReferenceRewriter#preparePlan`), and it removes the reduced-index candidates a traverse ordering
	 * reads today. So the switch here is the filter shape: {@link #rewritableKindX()} is taken over,
	 * {@link #notRewritableKindX()} is not, and both mean the same. The channel assertions prove the toggle on a query
	 * that does not order by `tags` (guard 4 declines the rewrite for any query ordered by the same reference). Today
	 * both ordered queries therefore run without the rewrite; P5 drops guard 4 for traverse orderings, and then this
	 * witness compares a rewritten query with an unrewritten one, which is the gate P5 names.
	 */
	@DisplayName("Should order identically whether the bidirectional rewrite answers the referenceHaving or not")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("sortRoutes")
	void shouldOrderIdenticallyWhetherBidirectionalRewriteApplies(SortRoute route, EvitaSessionContract session) {
		// every tagged owner has a kind x row, so both shapes select all four; referenceHaving never bounds, so every
		// row orders: 1: 122(20) 120(50) | 2: 121(30) | 4: 123(15)
		// today's partition-level narrowing (C4) keeps only the tags holding a kind x row - 2 3 4 5 - and gives
		// [121, 122, 123, 120] in both directions
		final OrderConstraint byTail = attributeNatural(ATTRIBUTE_TAIL);
		final List<Executable> assertions = new ArrayList<>(6);
		assertions.add(
			() -> assertFalse(
				referenceIndexOptionRegistered(
					queryOrder(session, route, TAG_OWNERS, new FilterConstraint[]{rewritableKindX()}, byTail)
				),
				"channel: the rewritable shape must be answered from the counterpart when the query does not order " +
					"by `tags` - otherwise this test toggles nothing"
			)
		);
		assertions.add(
			() -> assertTrue(
				referenceIndexOptionRegistered(
					queryOrder(session, route, TAG_OWNERS, new FilterConstraint[]{notRewritableKindX()}, byTail)
				),
				"channel: the non-rewritable shape must be answered by the owner-side reduced indexes"
			)
		);
		for (OrderDirection direction : OrderDirection.values()) {
			final int[] expected = direction == OrderDirection.ASC ?
				new int[]{122, 120, 121, 123} : new int[]{120, 122, 121, 123};
			assertions.add(
				selectsAndOrders(
					session, route, TAG_OWNERS, new FilterConstraint[]{rewritableKindX()},
					byOrder(REFERENCE_TAGS, direction), expected, direction + " rewritable shape"
				)
			);
			assertions.add(
				selectsAndOrders(
					session, route, TAG_OWNERS, new FilterConstraint[]{notRewritableKindX()},
					byOrder(REFERENCE_TAGS, direction), expected, direction + " non-rewritable shape"
				)
			);
		}
		assertAll(assertions);
	}

	/**
	 * Checks the rule "Should answer the referenceHaving from the counterpart also when ordering by the reference" -
	 * a witness of plan step P5, not of a semantic rule: once the filtered hierarchy alone bounds a traversal (E),
	 * the reduced-index candidates of a `referenceHaving` no longer matter to it, and P5 drops guard 4 of the rewrite
	 * for traverse orderings. Red until then; the ordering it produces is pinned by
	 * {@link #shouldOrderIdenticallyWhetherBidirectionalRewriteApplies(SortRoute, EvitaSessionContract)}.
	 */
	@DisplayName("Should answer the referenceHaving from the counterpart also when ordering by the reference")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("sortRoutes")
	void shouldTakeBidirectionalRewriteUnderTraverseOrdering(SortRoute route, EvitaSessionContract session) {
		final RoutedResult result = queryOrder(
			session, route, TAG_OWNERS, new FilterConstraint[]{rewritableKindX()},
			byOrder(REFERENCE_TAGS, OrderDirection.ASC)
		);
		assertFalse(
			referenceIndexOptionRegistered(result),
			() -> "P5 witness: guard 4 still declines the bidirectional rewrite for a traverse ordering by the same " +
				"reference; registered planning alternatives: " + result.alternatives()
		);
	}

	/**
	 * Checks the rule "Should keep a seeded target order when an owner with an irrelevant target is added" (E5): the
	 * universe of the nested target order is the set of targets the traversal visits - here every brand - and not the
	 * targets of the selected owners. Adding an owner whose rows reach only targets no other selected owner references
	 * must not reorder the others.
	 *
	 * The assertion is a relation between two answers, not a pinned order: a seeded shuffle cannot be derived by hand.
	 * Under the rejected universe - the selected owners' targets - the selection shuffles brands 1-7 without owner 7
	 * and brands 1-9 with it. The Fisher-Yates shuffle `RandomSorter` runs, seeded with 42 over the ascending keys,
	 * gives 2 5 6 4 1 7 3 for brands 1-7 and 9 2 1 6 5 3 4 7 8 for brands 1-9, so the owners of the selection would
	 * come back as [5, 10, 6, 11, 3, 9, 2, 1, 4] without owner 7 and as [5, 10, 6, 11, 1, 3, 9, 2, 4] with it -
	 * owner 1 (brand 3) jumps ahead of owners 3, 9 and 2 (brand 4) and the relation breaks. Under E5 both queries
	 * shuffle brands 1-9 and the second order is the answer to both. Only the flat brand is used: every sibling set of
	 * the category tree has at most two members, and no selection of this fixture puts the two members of one on the
	 * two sides of the relation, so a tree variant could not tell the universes apart.
	 */
	@DisplayName("Should keep a seeded target order when an owner with an irrelevant target is added")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("flatRoutes")
	void shouldKeepSeededTargetOrderWhenIrrelevantOwnerIsAdded(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		final OrderConstraint ordering = referenceProperty(
			referenceName, traverseByEntityProperty(randomWithSeed(TARGET_ORDER_SEED)),
			attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
		);
		final RoutedResult selection = queryOrder(session, route, SEEDED_SELECTION, NO_FILTER, ordering);
		final RoutedResult extended = queryOrder(
			session, route, ArrayUtils.mergeArrays(SEEDED_SELECTION, new Integer[]{IRRELEVANT_OWNER}), NO_FILTER,
			ordering
		);
		final int[] extendedWithoutIrrelevant = Arrays.stream(extended.order())
			.filter(owner -> owner != IRRELEVANT_OWNER)
			.toArray();
		assertAll(
			() -> assertNull(selection.routeViolation(), () -> "selection - " + selection.routeViolation()),
			() -> assertNull(extended.routeViolation(), () -> "extended - " + extended.routeViolation()),
			() -> assertEquals(
				SEEDED_SELECTION.length + 1, extended.order().length,
				() -> "the extended selection must return every owner, including " + IRRELEVANT_OWNER + ": " +
					Arrays.toString(extended.order())
			),
			() -> assertOrder(
				selection.order(), extendedWithoutIrrelevant,
				"the order of the selection with owner " + IRRELEVANT_OWNER + " added and removed again"
			)
		);
	}

	/**
	 * Both sort routes on the partitioned hierarchical reference - the only hierarchical reference whose reduced
	 * indexes are eligible for a query plan of their own, so the only one where index selection offers alternatives.
	 */
	@Nonnull
	static Stream<Arguments> partitionedHierarchicalRoutes() {
		return routesOf(REFERENCE_CATEGORIES_PARTITIONING);
	}

	/**
	 * Checks the rule "Should order identically on every alternative plan": the bound, the selection and therefore
	 * the order must not depend on which index set the planner picks. `VERIFY_ALTERNATIVE_INDEX_RESULTS` executes every
	 * eligible alternative and fails the query when two of them answer differently; each scenario also asserts that at
	 * least two plans were built, or the verification would have compared nothing - except a scenario whose alternative
	 * is not eligible for a plan of its own, which asserts the single plan and the reason instead. This is the
	 * plan-choice counterfactual of P5: the hierarchy bound must come from the resolved node set, not from whichever
	 * `TargetIndexes` the chosen plan happened to register.
	 */
	@DisplayName("Should order identically on every alternative plan")
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("partitionedHierarchicalRoutes")
	void shouldOrderIdenticallyOnEveryAlternativePlan(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		// the subtree of 2 is used rather than the one of 4: the partitions of {4, 6, 7} hold more rows than half of
		// the live owners, so index selection marks that alternative HIGH_CARDINALITY and builds no second plan
		// within 2 = {2, 3, 8, 9}: 2: 23(1), 31- passes, 32(4) | 3: 30(70) | 8: 31(2) -> [23, 32, 30, 31]
		// excluding root = {3, 8, 9}, 23 is not selected: 3: 32(10) 30(70) | 8: 31(2) -> [32, 30, 31]
		// inScope(LIVE, within 2) over both scopes: the live owners as above, the archived 33 and 34 unbounded -
		// 1: 33(2) | 7: 34(15) | 2: 23 32 | 3: 30 | 8: 31 -> [33, 34, 23, 32, 30, 31]
		final OrderConstraint ascending = byOrder(referenceName, OrderDirection.ASC);
		final FilterConstraint within2 = hierarchyWithin(referenceName, entityPrimaryKeyInSet(2));
		final FilterConstraint kindY = referenceHaving(referenceName, attributeEquals(ATTRIBUTE_KIND, KIND_Y));
		final Object[][] scenarios = {
			{"within 2", HIERARCHY_OWNERS, new FilterConstraint[]{within2}, ascending, new int[]{23, 32, 30, 31}},
			{
				"within 2 descending", HIERARCHY_OWNERS, new FilterConstraint[]{within2},
				byOrder(referenceName, OrderDirection.DESC), new int[]{32, 23, 30, 31}
			},
			{
				"within 2 excluding root", HIERARCHY_OWNERS,
				new FilterConstraint[]{hierarchyWithin(referenceName, entityPrimaryKeyInSet(2), excludingRoot())},
				ascending, new int[]{32, 30, 31}
			},
			{"kind y", KIND_OWNERS, new FilterConstraint[]{kindY}, ascending, KINDS_HIERARCHICAL},
			{
				"kind y and within 1", KIND_OWNERS,
				new FilterConstraint[]{kindY, hierarchyWithin(referenceName, entityPrimaryKeyInSet(1))}, ascending,
				KIND_Y_WITHIN_1
			},
			{
				"within 4 and within 2", new Integer[]{23, 35, 36, 37},
				new FilterConstraint[]{hierarchyWithin(referenceName, entityPrimaryKeyInSet(4)), within2},
				ascending, UNION_OF_SUBTREES
			},
			{
				"inScope(LIVE, within 2) over both scopes",
				ArrayUtils.mergeArrays(HIERARCHY_OWNERS, new Integer[]{33, 34}),
				new FilterConstraint[]{scope(BOTH_SCOPES), inScope(Scope.LIVE, within2)}, ascending,
				new int[]{33, 34, 23, 32, 30, 31},
				// the candidate of a constraint inside `inScope` covers one of the two queried scopes, so #1681 makes
				// it ineligible for a plan of its own and the global plan is the only one built - this scenario pins
				// the order of that plan. Do not demand a second plan here until such a candidate can be planned:
				// a correct engine fails that assertion
				EligibilityObstacle.PARTIAL_SCOPE_COVERAGE
			}
		};
		final List<Executable> assertions = new ArrayList<>(scenarios.length);
		for (Object[] scenario : scenarios) {
			final String label = (String) scenario[0] + " (VERIFY_ALTERNATIVE_INDEX_RESULTS)";
			final EligibilityObstacle singlePlanReason = scenario.length > 5 ? (EligibilityObstacle) scenario[5] : null;
			assertions.add(
				() -> {
					final RoutedResult result = queryPage(
						session, route, (Integer[]) scenario[1], (FilterConstraint[]) scenario[2], 1, 100,
						new DebugMode[]{DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS}, (OrderConstraint) scenario[3]
					);
					final Executable planCount = singlePlanReason == null ?
						() -> assertTrue(
							result.builtPlans() >= 2,
							() -> label + ": only " + result.builtPlans() + " plan(s) built, the verification " +
								"compared nothing; alternatives: " + result.alternatives()
						) :
						() -> assertTrue(
							result.builtPlans() == 1 &&
								result.alternatives().stream().anyMatch(it -> it.contains(singlePlanReason.name())),
							() -> label + ": expected the single plan " + singlePlanReason + " leaves, but " +
								result.builtPlans() + " plan(s) were built; alternatives: " + result.alternatives()
						);
					assertAll(
						planCount,
						() -> assertOrder((int[]) scenario[4], result, label)
					);
				}
			);
		}
		assertAll(assertions);
	}

	/**
	 * Listed values claim owners block by block; unlisted and missing values reach later blocks and the tail.
	 * The owner's own `order` is deliberately 30, distinct from the representing reference value.
	 */
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldOrderExactValuesBlockByBlock(String referenceName, SortRoute route, EvitaSessionContract session) {
		final OrderConstraint ordering = traverseOrdering(
			referenceName, attributeSetExact(ATTRIBUTE_ORDER, 20, 30, 7, 1, 3, 2)
		);
		for (final int pageSize : new int[]{2, 4}) {
			assertOrder(
				new int[]{3, 9, 2, 10, 6, 5, 7, 8, 4, 1, 11},
				queryAllPages(
					session, route, BASIC_OWNERS, NO_FILTER, BASIC_OWNERS.length, pageSize,
					ordering, attributeNatural(ATTRIBUTE_TAIL)
				), "Exact blocks, page size " + pageSize
			);
		}
	}

	/**
	 * referenceHaving selects owners of target 5, but owner 6 is placed by its earlier row at target 1.
	 */
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldKeepEarlierExactBlockUnderReferenceHaving(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		assertOrder(
			new int[]{6, 5, 11, 4},
			queryOrder(
				session, route, BASIC_OWNERS,
				new FilterConstraint[]{referenceHaving(referenceName, entityPrimaryKeyInSet(5))},
				traverseOrdering(referenceName, attributeSetExact(ATTRIBUTE_ORDER, 3, 9, 1, 50))
			), "referenceHaving must not narrow exact blocks"
		);
	}

	/**
	 * Owner 73's first valued row is b=60 (unlisted), so its later c=5 must never claim it.
	 * Owner 72 skips its value-less a row; owner 74's unlisted target 1 row leaves it for target 2 or a child sorter.
	 */
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("duplicateRoutes")
	void shouldUseFirstValuedRowForExactOrder(String referenceName, SortRoute route, EvitaSessionContract session) {
		assertOrder(
			new int[]{70, 71, 72, 74, 73},
			queryOrder(
				session, route, DUPLICATE_OWNERS, NO_FILTER,
				traverseOrdering(referenceName, attributeSetExact(ATTRIBUTE_ORDER, 5, 1, 100, 50, 3))
			), "An unlisted first valued row blocks a later listed row"
		);
		assertOrder(
			new int[]{74, 70, 71, 72, 73},
			queryOrder(
				session, route, DUPLICATE_OWNERS, NO_FILTER,
				traverseOrdering(
					referenceName, attributeSetExact(ATTRIBUTE_ORDER, 5, 1, 100, 50, 3),
					entityPrimaryKeyNatural(OrderDirection.DESC)
				)
			), "Unclaimed owners reach the next child sorter in the same block"
		);
	}

	/**
	 * Both scopes merge by listed position and ascending primary key for ties, including every page boundary.
	 */
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldMergeExactValuesAcrossScopes(String referenceName, SortRoute route, EvitaSessionContract session) {
		for (final int pageSize : new int[]{2, 4}) {
			assertOrder(
				new int[]{54, 52, 53, 50, 51, 55},
				queryAllPages(
					session, route, SCOPE_OWNERS, new FilterConstraint[]{scope(BOTH_SCOPES)}, SCOPE_OWNERS.length,
					pageSize, traverseOrdering(referenceName, attributeSetExact(ATTRIBUTE_ORDER, 30, 20, 10, 5, 1))
				), "Exact ordering across both scopes, page size " + pageSize
			);
		}
	}

	/**
	 * The hierarchy bound excludes earlier rows outside the subtree, including an otherwise listed value.
	 */
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldBoundExactBlocksByHierarchy(String referenceName, SortRoute route, EvitaSessionContract session) {
		assertOrder(
			new int[]{21, 27, 20, 28, 29, 24, 23, 22, 25},
			queryOrder(
				session, route, HIERARCHY_OWNERS,
				new FilterConstraint[]{hierarchyWithin(referenceName, entityPrimaryKeyInSet(4))},
				traverseOrdering(referenceName, attributeSetExact(ATTRIBUTE_ORDER, 60, 50, 40, 30, 20, 10, 5, 1, 99))
			), "Exact blocks bounded to targets 4, 6, 7"
		);
	}

	/**
	 * Target 2 has no sort index of second; its only selected owner must fall through without failing.
	 */
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "{0} via {1}")
	@MethodSource("hierarchicalRoutes")
	void shouldPassOnExactBlockWithoutSortIndex(
		String referenceName, SortRoute route, EvitaSessionContract session
	) {
		assertOrder(
			new int[]{14},
			queryOrder(
				session, route, LONE_OWNERS,
				new FilterConstraint[]{hierarchyWithin(referenceName, entityPrimaryKeyInSet(2), directRelation())},
				traverseOrdering(referenceName, attributeSetExact(ATTRIBUTE_SECOND, 7))
			), "An exact block without a sort index claims nothing"
		);
	}

	/**
	 * Listed values and representing rows use the index's decimal scale on prefetch as on the index route.
	 * A descending child sorter makes a missed exact match observable even when both owners share the value.
	 */
	@UseDataSet(TRAVERSE_REFERENCES)
	@ParameterizedTest(name = "via {0}")
	@MethodSource("sortRoutes")
	void shouldNormalizeExactReferenceValues(SortRoute route, EvitaSessionContract session) {
		assertOrder(
			new int[]{140, 141},
			queryOrder(
				session, route, DECIMAL_VARIANT_OWNERS, NO_FILTER,
				traverseOrdering(
					REFERENCE_DECIMAL_VARIANTS, attributeSetExact(ATTRIBUTE_SCALE, new BigDecimal("1.04")),
					entityPrimaryKeyNatural(OrderDirection.DESC)
				)
			), "Exact values compare at the attribute's indexed decimal scale"
		);
	}

}
