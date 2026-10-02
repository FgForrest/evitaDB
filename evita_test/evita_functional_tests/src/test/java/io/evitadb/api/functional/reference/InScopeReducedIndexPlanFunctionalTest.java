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

package io.evitadb.api.functional.reference;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterInScope;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.FacetGroupRelationLevel;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HierarchyRequireConstraint;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.query.indexSelection.TargetIndexes.EligibilityObstacle;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.anyHaving;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.children;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.excluding;
import static io.evitadb.api.query.QueryConstraints.facetGroupsConjunction;
import static io.evitadb.api.query.QueryConstraints.facetGroupsDisjunction;
import static io.evitadb.api.query.QueryConstraints.facetGroupsExclusivity;
import static io.evitadb.api.query.QueryConstraints.facetGroupsNegation;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.facetSummaryOfReference;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.fromNode;
import static io.evitadb.api.query.QueryConstraints.fromRoot;
import static io.evitadb.api.query.QueryConstraints.having;
import static io.evitadb.api.query.QueryConstraints.directRelation;
import static io.evitadb.api.query.QueryConstraints.excludingRoot;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfReference;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfSelf;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinSelf;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithin;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinRoot;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinRootSelf;
import static io.evitadb.api.query.QueryConstraints.inScope;
import static io.evitadb.api.query.QueryConstraints.node;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.or;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.parents;
import static io.evitadb.api.query.QueryConstraints.priceInCurrency;
import static io.evitadb.api.query.QueryConstraints.priceInPriceLists;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.siblings;
import static io.evitadb.api.query.QueryConstraints.statistics;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins queries over two scopes whose reference constraint is restricted to one of them by `inScope(...)`.
 *
 * Index selection offers a `hierarchyWithin` / `referenceHaving` a `REFERENCED_ENTITY` plan built from the reduced
 * indexes of its partitioned reference. Inside `inScope(S, ...)` it collects the partitions of scope `S` only, so the
 * plan is no answer for a query over `scope(LIVE, ARCHIVED)`: were it used for the whole query, every constraint
 * outside the container that reads the plan's indexes - the entity locale, an entity attribute, a price, a facet,
 * a negation - would be answered from scope `S` alone and every entity of the other scope would be lost. Such a plan
 * is registered as ineligible (`EligibilityObstacle.PARTIAL_SCOPE_COVERAGE`), and when it would be **empty** (no node
 * of the subtree exists in `S`, or no owner of `S` references it) it is not registered at all, because an empty
 * candidate short-circuits the whole query to an empty result although the other scope's entities match.
 *
 * ## The fixture
 *
 * `inScopePlanCategory` is a hierarchy indexed in both scopes, every node carrying the `en` locale because the
 * parent lookup of `hierarchyWithin` honours the query locale. **The parent lookup reads the hierarchy of the
 * scope it is evaluated in** (`HierarchyWithinTranslator#createFormulaFromHierarchyIndex`, each scope's own tree when
 * both are queried), so `inScope(ARCHIVED, hierarchyWithin(...))` needs archived category nodes - the fixture
 * keeps a live tree and an archived tree:
 *
 * | category | scope | parent | referenced by |
 * |---|---|---|---|
 * | 1 | LIVE | - (root) | nobody directly |
 * | 2 | LIVE | 1 | products 1-8 (live) and 49-56 (archived) - the live subtree of 1 |
 * | 3 | LIVE | - (root) | products 17-48 (live) and 57-64 (archived) |
 * | 4 | LIVE | - (root) | products 65-72 - archived owners only |
 * | 11 | ARCHIVED | - (root) | nobody directly |
 * | 12 | ARCHIVED | 11 | products 1-8 (live) and 49-56 (archived) - the archived subtree of 11 |
 * | 15 | ARCHIVED | - (root) | products 9-16 - live owners only |
 *
 * Categories 1 and 11 share the `code` value `root`, which is unique within each scope: category 11 receives it while
 * every category is live, category 1 only after 11 has been archived. The same holds for the catalog attribute
 * `globalCode`, which is unique globally within each scope. Both of them, and no other category, reference tag 1
 * through the `tags` reference of the categories. The `liveOnly` attribute of the categories is filterable in the live
 * scope only and is true on categories 1, 2 and 3.
 *
 * A second data set, {@link #IN_SCOPE_REDUCED_INDEX_PLAN_ARCHIVED_SUBTREE_OWNER}, adds product
 * {@link #ARCHIVED_SUBTREE_ONLY_PRODUCT} to the fixture above: created referencing only category 12, the archived
 * subtree, and archived afterwards, so it has no owner anywhere in the live tree. The `HierarchyWithinOverBothScopes`
 * tests use it to pin that a product reachable through the archived tree alone is neither lost nor duplicated when
 * the hierarchy filter spans both scopes.
 *
 * A third data set, {@link #IN_SCOPE_REDUCED_INDEX_PLAN_SECOND_BRAND}, adds brand {@link #OTHER_BRAND} to the fixture
 * above: the live unbranded products 4, 12 and 20 hold it, and so does product {@link #ARCHIVED_OTHER_BRAND_PRODUCT},
 * archived and without a category, so the `brand` reference has two facets whose owners live in both scopes. The
 * `FacetSummaryOverScopeContainers` tests use it to pin the facet summary of a facet computed after another one.
 *
 * `inScopePlanProduct` supports the `en` and `de` locales and has a `visible` attribute filterable in both scopes,
 * a CZK price in price list `basic`, a partitioned `categories` reference, a partitioned and faceted `brand`
 * reference, and a `tags` reference indexed for filtering only (no partitions) - all indexed in both scopes. The
 * 72 products fall into six groups of consecutive primary keys:
 *
 * | products | scope | categories | tag 1 |
 * |---|---|---|---|
 * | 1-8 | LIVE | 2 and 12 (both subtrees) | yes |
 * | 9-16 | LIVE | 15 | no |
 * | 17-48 | LIVE | 3 | no |
 * | 49-56 | ARCHIVED | 2 and 12 (both subtrees) | yes |
 * | 57-64 | ARCHIVED | 3 | no |
 * | 65-72 | ARCHIVED | 4 | no |
 *
 * **The first four products of every group each fail exactly one outer constraint**, the rest pass all of them:
 *
 * | position in group | products | fails |
 * |---|---|---|
 * | 1st | 1, 9, 17, 49, 57, 65 | `entityLocaleEquals(en)` - localized in `de` only |
 * | 2nd | 2, 10, 18, 50, 58, 66 | `attributeEquals(visible, true)` - `visible` is false |
 * | 3rd | 3, 11, 19, 51, 59, 67 | `priceInCurrency(CZK)` + `priceInPriceLists(basic)` - has no price |
 * | 4th | 4, 12, 20, 52, 60, 68 | `referenceHaving(brand, 1)` / `facetHaving(brand, 1)` - has no brand |
 *
 * Every expectation is therefore "the products the scoped reference constraint admits, minus one row of the table
 * above", which is how the rows spell it: `inScope(LIVE, hierarchyWithin(categories, 1))` admits 1-8 and every archived
 * product, `inScope(ARCHIVED, hierarchyWithin(categories, 11))` admits every live product and 49-56. Because live
 * **and** archived products fail every outer constraint, a fix that appends the other scope's owners wholesale, or that
 * answers the other scope without applying the outer constraint, returns a product the row does not expect. Because
 * archived products outside the subtree pass the outer constraints, a symmetric `inScope(ARCHIVED, ...)` row catches a
 * fix that admits every archived product.
 *
 * The cardinalities keep the narrowed plan under the cardinality limit: 8 subtree owners per scope against half the
 * queried global indexes (36 for both scopes, 24 for live only, 12 for archived only).
 *
 * ## What the result rows cannot see
 *
 * A fix that keeps the narrowed candidate eligible by leaking the other scope's owners into the **narrowed** branch
 * is invisible in a result set: `InScopeFormulaPostProcessor` answers the other scope's branch with every entity of
 * that scope that passes the outer constraints, so the leaked owners are expected anyway; and whenever that branch is
 * restricted by an `inScope` container of its own, the narrowed branch is intersected with the narrowed scope's
 * superset, which drops them again. What guards against it is the assertion that the narrowed candidate stays
 * registered with `PARTIAL_SCOPE_COVERAGE`, next to the telemetry-pinned eligibility rows.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference constraint narrowed by inScope(...) in a query over both scopes")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(REFERENCE)
@Tag(HIERARCHY)
public class InScopeReducedIndexPlanFunctionalTest {
	private static final String IN_SCOPE_REDUCED_INDEX_PLAN = "inScopeReducedIndexPlan";
	/**
	 * A writable copy of the fixture, for the tests that try to change it.
	 */
	private static final String IN_SCOPE_REDUCED_INDEX_PLAN_WRITABLE = "inScopeReducedIndexPlanWritable";
	/**
	 * The fixture with {@link #ARCHIVED_SUBTREE_ONLY_PRODUCT} added.
	 */
	private static final String IN_SCOPE_REDUCED_INDEX_PLAN_ARCHIVED_SUBTREE_OWNER =
		"inScopeReducedIndexPlanArchivedSubtreeOwner";
	/**
	 * The fixture with brand {@link #OTHER_BRAND} held by live products 4, 12, 20 and archived product
	 * {@link #ARCHIVED_OTHER_BRAND_PRODUCT}.
	 */
	private static final String IN_SCOPE_REDUCED_INDEX_PLAN_SECOND_BRAND = "inScopeReducedIndexPlanSecondBrand";
	private static final String ENTITY_CATEGORY = "inScopePlanCategory";
	private static final String ENTITY_BRAND = "inScopePlanBrand";
	private static final String ENTITY_TAG = "inScopePlanTag";
	private static final String ENTITY_PRODUCT = "inScopePlanProduct";
	private static final String REF_CATEGORIES = "categories";
	private static final String REF_BRAND = "brand";
	private static final String REF_TAGS = "tags";
	private static final String ATTR_NAME = "name";
	private static final String ATTR_VISIBLE = "visible";
	private static final String ATTR_CODE = "code";
	/**
	 * A catalog attribute unique globally in both scopes, held by categories 1 and 11 with the value {@link #ROOT_CODE}.
	 */
	private static final String ATTR_GLOBAL_CODE = "globalCode";
	/**
	 * A category attribute filterable in the live scope only, true on categories 1, 2 and 3.
	 */
	private static final String ATTR_LIVE_ONLY = "liveOnly";
	private static final String ROOT_CODE = "root";
	private static final String PRICE_LIST = "basic";
	private static final Currency CZK = Currency.getInstance("CZK");
	private static final Locale LOCALE = Locale.ENGLISH;
	private static final Locale OTHER_LOCALE = Locale.GERMAN;
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	private static final Scope[] LIVE_ONLY = {Scope.LIVE};
	private static final Scope[] ARCHIVED_ONLY = {Scope.ARCHIVED};
	private static final int ROOT_CATEGORY = 1;
	private static final int SUBTREE_CATEGORY = 2;
	private static final int OTHER_CATEGORY = 3;
	/**
	 * A live category referenced by archived products only.
	 */
	private static final int LIVE_NODE_WITH_ARCHIVED_OWNERS = 4;
	private static final int ARCHIVED_ROOT_CATEGORY = 11;
	private static final int ARCHIVED_SUBTREE_CATEGORY = 12;
	/**
	 * An archived category referenced by live products only.
	 */
	private static final int ARCHIVED_NODE_WITH_LIVE_OWNERS = 15;
	/**
	 * A category primary key that does not exist in any scope.
	 */
	private static final int MISSING_CATEGORY = 999;
	private static final int BRAND = 1;
	/**
	 * A second brand, present in the {@link #IN_SCOPE_REDUCED_INDEX_PLAN_SECOND_BRAND} data set only.
	 */
	private static final int OTHER_BRAND = 2;
	/**
	 * A live product referencing the tag, which passes every outer constraint.
	 */
	private static final int LIVE_TAGGED_PRODUCT = 8;
	/**
	 * An archived product referencing the archived category 12 only - none of the live tree.
	 */
	private static final int ARCHIVED_SUBTREE_ONLY_PRODUCT = 100;
	/**
	 * An archived product holding brand {@link #OTHER_BRAND} and no category, present in the
	 * {@link #IN_SCOPE_REDUCED_INDEX_PLAN_SECOND_BRAND} data set only.
	 */
	private static final int ARCHIVED_OTHER_BRAND_PRODUCT = 101;
	/**
	 * A product primary key that does not exist in any scope.
	 */
	private static final int MISSING_PRODUCT = 999;
	/**
	 * Every primary key the fixture uses, products and categories alike.
	 */
	private static final int[] ALL_KEYS = range(1, ARCHIVED_SUBTREE_ONLY_PRODUCT);
	/**
	 * An archived product referencing the tag, which passes every outer constraint.
	 */
	private static final int ARCHIVED_TAGGED_PRODUCT = 56;
	private static final int TAG = 1;
	private static final int PRODUCT_COUNT = 72;
	/**
	 * Output name of the hierarchy statistics computed by the statistics witness.
	 */
	private static final String HIERARCHY_OUTPUT = "children";
	/**
	 * The eligibility obstacle of a reduced-index candidate built inside an `inScope` container, as the query
	 * telemetry names it.
	 */
	private static final String PARTIAL_SCOPE_COVERAGE = EligibilityObstacle.PARTIAL_SCOPE_COVERAGE.name();
	/**
	 * Products from this value to {@link #PRODUCT_COUNT} are archived.
	 */
	private static final int FIRST_ARCHIVED = 49;
	/**
	 * The first primary key of every group of the fixture table, in ascending order.
	 */
	private static final int[] GROUP_STARTS = {1, 9, 17, 49, 57, 65};
	/**
	 * The categories each group of {@link #GROUP_STARTS} references, by the same position.
	 */
	private static final int[][] GROUP_CATEGORIES = {
		{SUBTREE_CATEGORY, ARCHIVED_SUBTREE_CATEGORY},
		{ARCHIVED_NODE_WITH_LIVE_OWNERS},
		{OTHER_CATEGORY},
		{SUBTREE_CATEGORY, ARCHIVED_SUBTREE_CATEGORY},
		{OTHER_CATEGORY},
		{LIVE_NODE_WITH_ARCHIVED_OWNERS}
	};
	private static final int POSITION_WITHOUT_LOCALE = 0;
	private static final int POSITION_INVISIBLE = 1;
	private static final int POSITION_UNPRICED = 2;
	private static final int POSITION_UNBRANDED = 3;

	private static final int[] LIVE_SUBTREE = range(1, 8);
	private static final int[] LIVE_ALL = range(1, 48);
	private static final int[] ARCHIVED_SUBTREE = range(49, 56);
	private static final int[] ARCHIVED_ALL = range(49, PRODUCT_COUNT);
	private static final int[] ALL_PRODUCTS = range(1, PRODUCT_COUNT);
	/**
	 * Products localized in `de` only - they fail `entityLocaleEquals(en)`.
	 */
	private static final int[] WITHOUT_LOCALE = {1, 9, 17, 49, 57, 65};
	/**
	 * Products with `visible` false - they fail `attributeEquals(visible, true)`.
	 */
	private static final int[] INVISIBLE = {2, 10, 18, 50, 58, 66};
	/**
	 * Products without a price - they fail `priceInCurrency(CZK)` + `priceInPriceLists(basic)`.
	 */
	private static final int[] UNPRICED = {3, 11, 19, 51, 59, 67};
	/**
	 * Products without the brand - they fail `referenceHaving(brand, 1)` and `facetHaving(brand, 1)`.
	 */
	private static final int[] UNBRANDED = {4, 12, 20, 52, 60, 68};
	/**
	 * What `inScope(LIVE, <subtree of 1>)` admits over both scopes: the live subtree and every archived product.
	 */
	private static final int[] LIVE_NARROWED = union(LIVE_SUBTREE, ARCHIVED_ALL);
	/**
	 * What `inScope(ARCHIVED, <subtree of 11>)` admits over both scopes: every live product and the archived subtree.
	 */
	private static final int[] ARCHIVED_NARROWED = union(LIVE_ALL, ARCHIVED_SUBTREE);

	/**
	 * Builds the read-only fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = IN_SCOPE_REDUCED_INDEX_PLAN, destroyAfterClass = true)
	void setUp(@Nonnull Evita evita) {
		buildFixture(evita);
	}

	/**
	 * Builds the read-only fixture described on the class with {@link #ARCHIVED_SUBTREE_ONLY_PRODUCT} added.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = IN_SCOPE_REDUCED_INDEX_PLAN_ARCHIVED_SUBTREE_OWNER, destroyAfterClass = true)
	void setUpWithArchivedSubtreeOwner(@Nonnull Evita evita) {
		buildFixture(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, ARCHIVED_SUBTREE_ONLY_PRODUCT)
						.setAttribute(ATTR_NAME, LOCALE, "archived subtree only")
						.setAttribute(ATTR_VISIBLE, true)
						.setReference(REF_CATEGORIES, ARCHIVED_SUBTREE_CATEGORY)
				);
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(ENTITY_PRODUCT, ARCHIVED_SUBTREE_ONLY_PRODUCT);
			}
		);
	}

	/**
	 * Builds the read-only fixture described on the class with brand {@link #OTHER_BRAND} added: the live unbranded
	 * products 4, 12 and 20 receive it, and so does {@link #ARCHIVED_OTHER_BRAND_PRODUCT}, created live and archived
	 * afterwards. The archived unbranded products 52, 60 and 68 stay without a brand.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = IN_SCOPE_REDUCED_INDEX_PLAN_SECOND_BRAND, destroyAfterClass = true)
	void setUpWithSecondBrand(@Nonnull Evita evita) {
		buildFixture(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_BRAND, OTHER_BRAND));
				for (final int pk : UNBRANDED) {
					if (pk < FIRST_ARCHIVED) {
						session.getEntity(ENTITY_PRODUCT, pk, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setReference(REF_BRAND, OTHER_BRAND)
							.upsertVia(session);
					}
				}
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, ARCHIVED_OTHER_BRAND_PRODUCT)
						.setAttribute(ATTR_NAME, LOCALE, "archived other brand")
						.setAttribute(ATTR_VISIBLE, true)
						.setReference(REF_BRAND, OTHER_BRAND)
				);
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(ENTITY_PRODUCT, ARCHIVED_OTHER_BRAND_PRODUCT);
			}
		);
	}

	/**
	 * Builds a writable copy of the fixture described on the class. Its categories carry the `tags` reference like the
	 * products do, so that one reference content requirement applies to a product and to a category alike.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = IN_SCOPE_REDUCED_INDEX_PLAN_WRITABLE, readOnly = false, destroyAfterClass = true)
	void setUpWritable(@Nonnull Evita evita) {
		buildFixture(evita);
	}

	/**
	 * Builds the fixture described on the class: the entities are written live, then categories 11, 12 and 15 and
	 * products {@link #FIRST_ARCHIVED}-{@link #PRODUCT_COUNT} are archived.
	 *
	 * @param evita the engine instance to build the fixture in
	 */
	private static void buildFixture(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(
						ATTR_GLOBAL_CODE, String.class, thatIs -> thatIs.nullable().uniqueGloballyInScope(BOTH_SCOPES)
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchyIndexedInScope(BOTH_SCOPES)
					.withLocale(LOCALE)
					.withAttribute(ATTR_NAME, String.class, thatIs -> thatIs.localized())
					.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.nullable().uniqueInScope(BOTH_SCOPES))
					.withGlobalAttribute(ATTR_GLOBAL_CODE)
					.withAttribute(
						ATTR_LIVE_ONLY, Boolean.class, thatIs -> thatIs.nullable().filterableInScope(Scope.LIVE)
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_BRAND)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(ENTITY_TAG)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withReferenceToEntity(
						REF_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringInScope(BOTH_SCOPES)
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(ENTITY_TAG, TAG));
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(LOCALE, OTHER_LOCALE)
					.withAttribute(ATTR_NAME, String.class, thatIs -> thatIs.localized())
					.withAttribute(ATTR_VISIBLE, Boolean.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES))
					.withPriceInCurrencyIndexedInScope(2, new Currency[]{CZK}, BOTH_SCOPES)
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES)
					)
					.withReferenceToEntity(
						REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES)
							.facetedInScope(BOTH_SCOPES)
					)
					.withReferenceToEntity(
						REF_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringInScope(BOTH_SCOPES)
					)
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(ENTITY_CATEGORY, ROOT_CATEGORY)
						.setAttribute(ATTR_NAME, LOCALE, "root")
						.setAttribute(ATTR_LIVE_ONLY, true)
						.setReference(REF_TAGS, TAG)
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_CATEGORY, SUBTREE_CATEGORY)
						.setParent(ROOT_CATEGORY)
						.setAttribute(ATTR_NAME, LOCALE, "child")
						.setAttribute(ATTR_LIVE_ONLY, true)
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_CATEGORY, OTHER_CATEGORY)
						.setAttribute(ATTR_NAME, LOCALE, "other")
						.setAttribute(ATTR_LIVE_ONLY, true)
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_CATEGORY, LIVE_NODE_WITH_ARCHIVED_OWNERS)
						.setAttribute(ATTR_NAME, LOCALE, "archived owners only")
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_CATEGORY, ARCHIVED_ROOT_CATEGORY)
						.setAttribute(ATTR_NAME, LOCALE, "archived root")
						.setAttribute(ATTR_CODE, ROOT_CODE)
						.setAttribute(ATTR_GLOBAL_CODE, ROOT_CODE)
						.setReference(REF_TAGS, TAG)
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_CATEGORY, ARCHIVED_SUBTREE_CATEGORY)
						.setParent(ARCHIVED_ROOT_CATEGORY)
						.setAttribute(ATTR_NAME, LOCALE, "archived child")
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_CATEGORY, ARCHIVED_NODE_WITH_LIVE_OWNERS)
						.setAttribute(ATTR_NAME, LOCALE, "live owners only")
				);
				session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND));
				for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
					session.upsertEntity(createProduct(session, pk));
				}
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(ENTITY_CATEGORY, ARCHIVED_ROOT_CATEGORY);
				session.archiveEntity(ENTITY_CATEGORY, ARCHIVED_SUBTREE_CATEGORY);
				session.archiveEntity(ENTITY_CATEGORY, ARCHIVED_NODE_WITH_LIVE_OWNERS);
				for (int pk = FIRST_ARCHIVED; pk <= PRODUCT_COUNT; pk++) {
					session.archiveEntity(ENTITY_PRODUCT, pk);
				}
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(ENTITY_CATEGORY, ROOT_CATEGORY, attributeContentAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTR_CODE, ROOT_CODE)
					.setAttribute(ATTR_GLOBAL_CODE, ROOT_CODE)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Reference constraints narrowed to one scope by `inScope(...)` next to constraints answered for both scopes: the
	 * narrowed candidate must never answer for the whole query, and a candidate covering every queried scope must stay
	 * the cheaper plan it is.
	 */
	@Nested
	@DisplayName("Narrowed reference plan")
	class NarrowedReferencePlan {

		/**
		 * Returns the rows placing `inScope(S, <constraint on the subtree of category 1>)` next to an outer constraint,
		 * where the narrowed plan is non-empty. Each row is a label, the constraints placed next to
		 * `scope(LIVE, ARCHIVED)`, and the expected primary keys; each runs without a debug mode and with
		 * `VERIFY_ALTERNATIVE_INDEX_RESULTS`.
		 *
		 * The verify arm (plans as the query telemetry names them): the narrowed `REFERENCED_ENTITY` plan is ineligible
		 * and the both-scope candidates some rows register besides - `referenceHaving(brand)` and
		 * `hierarchyWithinRoot` - are `HIGH_CARDINALITY`, so every row runs the global plan
		 * (`Live index: GLOBAL, Archived index: GLOBAL`) alone and the arm passes vacuously. It stays as the tripwire:
		 * were the narrowed plan made eligible without being made correct, the rows whose outer constraint reads the
		 * plan's indexes would disagree with the global plan and the arm would throw `InconsistentResultsException`.
		 * The test asserts besides that the narrowed candidate stays registered with `PARTIAL_SCOPE_COVERAGE`, which
		 * the result rows cannot see.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> narrowedReferenceConstraintRows() {
			final Integer[] allProducts = IntStream.rangeClosed(1, PRODUCT_COUNT).boxed().toArray(Integer[]::new);
			return Stream.of(
					// --- inScope(LIVE, hierarchyWithin(categories, 1)) --- nothing outside the container reads the
					// plan's indexes; catches a planner that stops honouring the container (every live product would
					// come back)
					row("LIVE hierarchy alone",
						LIVE_NARROWED, liveHierarchy(ROOT_CATEGORY)),
					// the constant is matched per scope by SuperSetMatchingPostProcessor; catches the same over-reach
					// as above
					row("LIVE hierarchy + primary keys",
						LIVE_NARROWED, entityPrimaryKeyInSet(allProducts), liveHierarchy(ROOT_CATEGORY)),
					// referenceHaving(brand) answers from its own both-scope index set; catches a planner that appends
					// every archived owner (52, 60, 68 have no brand)
					row("LIVE hierarchy + referenceHaving(brand)",
						without(LIVE_NARROWED, UNBRANDED), referenceHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND)),
						liveHierarchy(ROOT_CATEGORY)),
					// every product references a category of the live tree; catches a lookup that resolves the
					// hierarchy candidate by reference name instead of constraint identity - the both-scope
					// hierarchyWithinRoot candidate would then admit every live product
					row("LIVE hierarchy + hierarchyWithinRoot",
						LIVE_NARROWED, hierarchyWithinRoot(REF_CATEGORIES), liveHierarchy(ROOT_CATEGORY)),
					// the locale must not be read from live partitions only; catches "append all archived" (49, 57, 65
					// lack `en`) and dropping the locale in the archived branch
					row("LIVE hierarchy + locale",
						without(LIVE_NARROWED, WITHOUT_LOCALE), entityLocaleEquals(LOCALE),
						liveHierarchy(ROOT_CATEGORY)),
					// the attribute must not be read from live partitions only; catches "append all archived" (50, 58,
					// 66 invisible)
					row("LIVE hierarchy + attribute",
						without(LIVE_NARROWED, INVISIBLE), attributeEquals(ATTR_VISIBLE, true),
						liveHierarchy(ROOT_CATEGORY)),
					// the price must not be read from live partitions only; catches "append all archived" (51, 59, 67
					// unpriced)
					row("LIVE hierarchy + price",
						without(LIVE_NARROWED, UNPRICED), priceInCurrency(CZK), priceInPriceLists(PRICE_LIST),
						liveHierarchy(ROOT_CATEGORY)),
					// the facet must not be read from live partitions only; catches "append all archived" (52, 60, 68
					// unbranded)
					row("LIVE hierarchy + facet",
						without(LIVE_NARROWED, UNBRANDED),
						userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND))),
						liveHierarchy(ROOT_CATEGORY)),
					// the complement of `not` must not be taken against live partitions only; catches a repair of the
					// positive translators one by one that forgets the negation family
					row("LIVE hierarchy + not(invisible)",
						without(LIVE_NARROWED, INVISIBLE), not(attributeEquals(ATTR_VISIBLE, false)),
						liveHierarchy(ROOT_CATEGORY)),
					// a constraint scoped to ARCHIVED must read archived data; catches a planner that ignores the
					// ARCHIVED container (50, 58, 66 must go, the live subtree keeps invisible product 2)
					row("LIVE hierarchy + inScope(ARCHIVED, attribute)",
						union(LIVE_SUBTREE, without(ARCHIVED_ALL, INVISIBLE)),
						inScope(Scope.ARCHIVED, attributeEquals(ATTR_VISIBLE, true)), liveHierarchy(ROOT_CATEGORY)),
					// the referenceHaving producer: the locale must not be read from the live partitions of category 2;
					// catches a guard applied to the hierarchy producer only
					row("LIVE referenceHaving + locale",
						without(LIVE_NARROWED, WITHOUT_LOCALE), entityLocaleEquals(LOCALE),
						inScope(Scope.LIVE, referenceHaving(REF_CATEGORIES, entityPrimaryKeyInSet(SUBTREE_CATEGORY)))),
					// hierarchyWithinRoot shares the hierarchy producer: the live tree (1-4) is referenced by live 1-8
					// and 17-48 (9-16 reference archived category 15 only); catches a guard applied to hierarchyWithin
					// only
					row("LIVE hierarchyWithinRoot + locale",
						without(union(LIVE_SUBTREE, range(17, 48), ARCHIVED_ALL), WITHOUT_LOCALE),
						entityLocaleEquals(LOCALE),
						inScope(Scope.LIVE, hierarchyWithinRoot(REF_CATEGORIES))),

					// --- inScope(ARCHIVED, hierarchyWithin(categories, 11)) - the symmetric shape, on the archived
					// tree --- catches a planner that admits every archived product (57-72 pass all outer constraints
					// and must not appear) or that stops honouring the container
					row("ARCHIVED hierarchy alone",
						ARCHIVED_NARROWED, archivedHierarchy(ARCHIVED_ROOT_CATEGORY)),
					// a locale read from archived partitions only would lose every live product; catches a guard keyed
					// to Scope.LIVE, "append all live" (1, 9, 17 lack `en`) and "append all archived" (58-64 would
					// appear)
					row("ARCHIVED hierarchy + locale",
						without(ARCHIVED_NARROWED, WITHOUT_LOCALE), entityLocaleEquals(LOCALE),
						archivedHierarchy(ARCHIVED_ROOT_CATEGORY)),
					// attribute; catches "append all live" (2, 10, 18 invisible)
					row("ARCHIVED hierarchy + attribute",
						without(ARCHIVED_NARROWED, INVISIBLE), attributeEquals(ATTR_VISIBLE, true),
						archivedHierarchy(ARCHIVED_ROOT_CATEGORY)),
					// price; catches "append all live" (3, 11, 19 unpriced)
					row("ARCHIVED hierarchy + price",
						without(ARCHIVED_NARROWED, UNPRICED), priceInCurrency(CZK), priceInPriceLists(PRICE_LIST),
						archivedHierarchy(ARCHIVED_ROOT_CATEGORY)),
					// facet; catches "append all live" (4, 12, 20 unbranded)
					row("ARCHIVED hierarchy + facet",
						without(ARCHIVED_NARROWED, UNBRANDED),
						userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND))),
						archivedHierarchy(ARCHIVED_ROOT_CATEGORY)),
					// a LIVE-scoped constraint next to the ARCHIVED-narrowed plan; catches a planner that ignores the
					// LIVE container (2, 10, 18 must go, archived invisible 50 stays)
					row("ARCHIVED hierarchy + inScope(LIVE, attribute)",
						union(without(LIVE_ALL, INVISIBLE), ARCHIVED_SUBTREE),
						inScope(Scope.LIVE, attributeEquals(ATTR_VISIBLE, true)),
						archivedHierarchy(ARCHIVED_ROOT_CATEGORY)),
					// the referenceHaving producer in the symmetric scope; catches a guard applied to one producer or
					// one scope only
					row("ARCHIVED referenceHaving + locale",
						without(ARCHIVED_NARROWED, WITHOUT_LOCALE), entityLocaleEquals(LOCALE),
						inScope(
							Scope.ARCHIVED,
							referenceHaving(REF_CATEGORIES, entityPrimaryKeyInSet(ARCHIVED_SUBTREE_CATEGORY))
						))
				)
				.flatMap(InScopeReducedIndexPlanFunctionalTest::withBothVerifyArms);
		}

		/**
		 * Returns the rows where the narrowed plan is **empty** - the second way a narrowed plan loses the other scope.
		 * Each row is a label, the constraints placed next to `scope(LIVE, ARCHIVED)`, and the expected primary keys;
		 * each runs without a debug mode and with `VERIFY_ALTERNATIVE_INDEX_RESULTS`.
		 *
		 * Two producers of an empty candidate exist in the hierarchy branch of index selection: the
		 * `TargetIndexes.EMPTY` sentinel when no node of the subtree exists in the narrowed scope's hierarchy (a
		 * missing category, or a node of the other scope's tree), and a candidate with no index when the nodes exist
		 * but no owner of the narrowed scope references them (live category 4 has archived owners only, archived
		 * category 15 live owners only). `IndexSelectionResult#isEmpty` treats either as "the whole query matches
		 * nothing", so a registered empty narrowed candidate would return `[]` before any plan is built; neither
		 * producer registers one.
		 *
		 * The verify arm: with no narrowed candidate registered, every row runs the global plan alone.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> emptyNarrowedPlanRows() {
			return Stream.of(
					// TargetIndexes.EMPTY shape: catches an obstacle-only guard that leaves the sentinel registered
					row("LIVE hierarchy of a missing node",
						ARCHIVED_ALL, liveHierarchy(MISSING_CATEGORY)),
					// TargetIndexes.EMPTY shape + outer constraint; catches "append all archived" (49, 57, 65)
					row("LIVE hierarchy of a missing node + locale",
						without(ARCHIVED_ALL, WITHOUT_LOCALE), entityLocaleEquals(LOCALE),
						liveHierarchy(MISSING_CATEGORY)),
					// TargetIndexes.EMPTY shape: an archived node is not in the live tree; catches the same guard as
					// above on the shape a user writes by mistake rather than by typo
					row("LIVE hierarchy of an archived node",
						ARCHIVED_ALL, liveHierarchy(ARCHIVED_ROOT_CATEGORY)),
					// 0-index candidate shape: catches an obstacle-only guard that keeps the empty candidate registered
					row("LIVE hierarchy without live owners",
						ARCHIVED_ALL, liveHierarchy(LIVE_NODE_WITH_ARCHIVED_OWNERS)),
					// 0-index candidate shape + outer constraint; catches "append all archived" (50, 58, 66)
					row("LIVE hierarchy without live owners + attribute",
						without(ARCHIVED_ALL, INVISIBLE), attributeEquals(ATTR_VISIBLE, true),
						liveHierarchy(LIVE_NODE_WITH_ARCHIVED_OWNERS)),
					// symmetric sentinel: catches a guard keyed to Scope.LIVE
					row("ARCHIVED hierarchy of a missing node",
						LIVE_ALL, archivedHierarchy(MISSING_CATEGORY)),
					// symmetric sentinel: a live node is not in the archived tree; catches a guard keyed to
					// Scope.LIVE and "append all archived" (49-72 must not appear)
					row("ARCHIVED hierarchy of a live node + attribute",
						without(LIVE_ALL, INVISIBLE), attributeEquals(ATTR_VISIBLE, true),
						archivedHierarchy(ROOT_CATEGORY)),
					// symmetric 0-index candidate + outer constraint; catches a guard keyed to Scope.LIVE and
					// "append all live" (1, 9, 17)
					row("ARCHIVED hierarchy without archived owners + locale",
						without(LIVE_ALL, WITHOUT_LOCALE), entityLocaleEquals(LOCALE),
						archivedHierarchy(ARCHIVED_NODE_WITH_LIVE_OWNERS)),
					// the referenceHaving producer skips an empty narrowed candidate; catches turning that skip into
					// "registered but ineligible" without changing the empty-result short-circuit
					row("LIVE referenceHaving without live owners + locale",
						without(ARCHIVED_ALL, WITHOUT_LOCALE), entityLocaleEquals(LOCALE),
						inScope(
							Scope.LIVE,
							referenceHaving(REF_CATEGORIES, entityPrimaryKeyInSet(LIVE_NODE_WITH_ARCHIVED_OWNERS))
						))
				)
				.flatMap(InScopeReducedIndexPlanFunctionalTest::withBothVerifyArms);
		}

		/**
		 * Returns the eligibility rows: queries in which a `REFERENCED_ENTITY` plan covers **every** queried scope and
		 * must therefore stay eligible and be chosen. Each row is a label, the queried scopes, the constraints placed
		 * next to `scope(...)`, and the expected primary keys.
		 *
		 * The verify arm compares the global plan with the `REFERENCED_ENTITY` plan - these rows are where the reduced
		 * plan keeps an independent oracle, so they must keep two plans to compare.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> reducedPlanCoveringEveryScopeRows() {
			return Stream.of(
					// catches "disable every reduced plan inside any inScope": the plan covers the only queried scope -
					// on a default schema (DEFAULT_SCOPES = {LIVE}) this is the shape of every inScope query
					Arguments.of(
						"scope(LIVE) + inScope(LIVE, hierarchy) + locale", LIVE_ONLY,
						new FilterConstraint[]{entityLocaleEquals(LOCALE), liveHierarchy(ROOT_CATEGORY)},
						without(LIVE_SUBTREE, WITHOUT_LOCALE)
					),
					// catches a fix keyed to "inScope(LIVE)" only or to "ARCHIVED is queried"
					Arguments.of(
						"scope(ARCHIVED) + inScope(ARCHIVED, hierarchy) + locale", ARCHIVED_ONLY,
						new FilterConstraint[]{entityLocaleEquals(LOCALE), archivedHierarchy(ARCHIVED_ROOT_CATEGORY)},
						without(ARCHIVED_SUBTREE, WITHOUT_LOCALE)
					),
					// catches the same over-correction in the referenceHaving producer
					Arguments.of(
						"scope(LIVE) + inScope(LIVE, referenceHaving) + locale", LIVE_ONLY,
						new FilterConstraint[]{
							entityLocaleEquals(LOCALE),
							inScope(
								Scope.LIVE, referenceHaving(REF_CATEGORIES, entityPrimaryKeyInSet(SUBTREE_CATEGORY))
							)
						},
						without(LIVE_SUBTREE, WITHOUT_LOCALE)
					),
					// catches "disable every reduced plan when several scopes are queried": a top-level hierarchyWithin
					// builds its plan from the partitions of both scopes, so its union is the answer set
					Arguments.of(
						"scope(LIVE, ARCHIVED) + top-level hierarchy + locale", BOTH_SCOPES,
						new FilterConstraint[]{
							entityLocaleEquals(LOCALE),
							hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY))
						},
						without(union(LIVE_SUBTREE, ARCHIVED_SUBTREE), WITHOUT_LOCALE)
					)
				)
				.flatMap(
					row -> Stream.of(false, true)
						.map(verify -> Arguments.of(row.get()[0], row.get()[1], row.get()[2], row.get()[3], verify))
				);
		}

		/**
		 * Checks that a reference constraint narrowed to one scope by `inScope(...)` keeps every product of the other
		 * scope that satisfies the outer constraints, whichever plan answers it, and that the narrowed candidate stays
		 * registered but ineligible (`PARTIAL_SCOPE_COVERAGE`) rather than being dropped - reference ordering relies on
		 * finding it - so the global plan answers.
		 *
		 * @param label              the row label, used in the test name only
		 * @param constraints        the constraints placed next to `scope(LIVE, ARCHIVED)`
		 * @param expected           the expected primary keys, ascending
		 * @param verifyAlternatives whether every eligible plan is executed and compared
		 * @param session            the session provided by the test extension
		 */
		@DisplayName("Should keep the other scope's matches next to a non-empty narrowed plan")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}, verify alternatives: {3}")
		@MethodSource("narrowedReferenceConstraintRows")
		void shouldKeepOtherScopeMatchesNextToNarrowedReferenceConstraint(
			@Nonnull String label,
			@Nonnull FilterConstraint[] constraints,
			@Nonnull int[] expected,
			boolean verifyAlternatives,
			@Nonnull EvitaSessionContract session
		) {
			final QueryOutcome outcome = assertQueryReturns(
				session, BOTH_SCOPES, constraints, verifyAlternatives, expected
			);
			assertTrue(
				outcome.reducedIndexAlternatives().stream().anyMatch(it -> it.contains(PARTIAL_SCOPE_COVERAGE)),
				() -> "the narrowed REFERENCED_ENTITY candidate must stay registered as ineligible, got: "
					+ outcome.reducedIndexAlternatives()
			);
			assertNotNull(outcome.selectedIndex(), "the telemetry must name the selected index");
			assertFalse(
				outcome.selectedIndex().contains("REFERENCED_ENTITY"),
				() -> "no REFERENCED_ENTITY plan may answer the query, got: " + outcome.selectedIndex()
			);
		}

		/**
		 * Checks that a narrowed reference constraint that matches nothing in its own scope does not empty the query -
		 * the other scope's products that satisfy the outer constraints must come back - and that no narrowed candidate
		 * is registered for it at all, because a registered empty candidate empties the whole query.
		 *
		 * @param label              the row label, used in the test name only
		 * @param constraints        the constraints placed next to `scope(LIVE, ARCHIVED)`
		 * @param expected           the expected primary keys, ascending
		 * @param verifyAlternatives whether every eligible plan is executed and compared
		 * @param session            the session provided by the test extension
		 */
		@DisplayName("Should keep the other scope's matches when the narrowed plan is empty")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}, verify alternatives: {3}")
		@MethodSource("emptyNarrowedPlanRows")
		void shouldKeepOtherScopeMatchesWhenNarrowedPlanIsEmpty(
			@Nonnull String label,
			@Nonnull FilterConstraint[] constraints,
			@Nonnull int[] expected,
			boolean verifyAlternatives,
			@Nonnull EvitaSessionContract session
		) {
			final QueryOutcome outcome = assertQueryReturns(
				session, BOTH_SCOPES, constraints, verifyAlternatives, expected
			);
			assertTrue(
				outcome.reducedIndexAlternatives().stream().noneMatch(it -> it.contains(PARTIAL_SCOPE_COVERAGE)),
				() -> "an empty narrowed REFERENCED_ENTITY candidate must not be registered, got: "
					+ outcome.reducedIndexAlternatives()
			);
		}

		/**
		 * Guards the fix against over-correction: when the `REFERENCED_ENTITY` plan covers every queried scope, it must
		 * stay registered, eligible and - in this fixture, where it is the cheaper one - selected. The answer is
		 * checked too, so a guard cannot pass on a wrong result.
		 *
		 * @param label              the row label, used in the test name only
		 * @param scopes             the scopes of `scope(...)`
		 * @param constraints        the constraints placed next to `scope(...)`
		 * @param expected           the expected primary keys, ascending
		 * @param verifyAlternatives whether every eligible plan is executed and compared
		 * @param session            the session provided by the test extension
		 */
		@DisplayName("Should keep choosing the reduced-index plan when it covers every queried scope")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}, verify alternatives: {4}")
		@MethodSource("reducedPlanCoveringEveryScopeRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldKeepChoosingReducedIndexPlanWhenItCoversEveryQueriedScope(
			@Nonnull String label,
			@Nonnull Scope[] scopes,
			@Nonnull FilterConstraint[] constraints,
			@Nonnull int[] expected,
			boolean verifyAlternatives,
			@Nonnull EvitaSessionContract session
		) {
			final QueryOutcome outcome = assertQueryReturns(session, scopes, constraints, verifyAlternatives, expected);
			assertFalse(
				outcome.reducedIndexAlternatives().isEmpty(),
				"the REFERENCED_ENTITY alternative must stay registered"
			);
			for (final String alternative : outcome.reducedIndexAlternatives()) {
				assertFalse(
					alternative.contains("not eligible"),
					() -> "the REFERENCED_ENTITY alternative covers every queried scope and must stay eligible, "
						+ "got: " + alternative
				);
			}
			assertNotNull(outcome.selectedIndex(), "the telemetry must name the selected index");
			assertTrue(
				outcome.selectedIndex().contains("REFERENCED_ENTITY"),
				() -> "the cheaper REFERENCED_ENTITY plan must be selected, got: " + outcome.selectedIndex()
			);
		}

	}

	/**
	 * One constraint instance placed in several `inScope` containers, resolved per container.
	 */
	@Nested
	@DisplayName("Constraint instance reused in several inScope containers")
	class ReusedConstraintInstance {

		/**
		 * Returns the rows of the reused-instance witness: the same `referenceHaving` placed in `inScope(LIVE, ...)`
		 * and in `inScope(ARCHIVED, ...)`, either as one Java object or as two equal objects. Each row is a label, the
		 * reference name, and whether the instance is reused.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> reusedConstraintInstanceRows() {
			return Stream.of(
				// tags are not partitioned, so every REFERENCED_ENTITY candidate is ineligible and the global plan
				// answers - plan choice is out of play; catches an identity-only lookup of the candidate
				Arguments.of("tags (not partitioned), one instance", REF_TAGS, true),
				// control: two equal but distinct instances find their own candidates
				Arguments.of("tags (not partitioned), two instances", REF_TAGS, false),
				// the same on a partitioned reference, where the narrowed candidates exist but are ineligible - only
				// the lookup decides the answer
				Arguments.of("categories (partitioned), one instance", REF_CATEGORIES, true),
				// control
				Arguments.of("categories (partitioned), two instances", REF_CATEGORIES, false)
			);
		}

		/**
		 * Checks that one `referenceHaving` instance placed both in `inScope(LIVE, ...)` and in `inScope(ARCHIVED,
		 * ...)` is answered per scope - plan choice is not involved. Index selection registers one candidate per
		 * container, and the translator must look up the candidate built for the scope it is translating
		 * (`FilterByVisitor#findTargetIndexSet` matches the instance together with the processing scopes); matching the
		 * instance alone would hand the LIVE candidate to the ARCHIVED translation and lose the archived owners of tag
		 * 1 / category 2. Two equal but distinct instances are the control.
		 *
		 * The expected answer is the subtree in both scopes: tag 1 is set exactly on products 1-8 and 49-56, the same
		 * products that reference category 2.
		 *
		 * @param label         the row label, used in the test name only
		 * @param referenceName the reference the constraint targets
		 * @param reused        whether both containers hold the same instance
		 * @param session       the session provided by the test extension
		 */
		@DisplayName("Should resolve a constraint instance reused in two inScope containers per scope")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}")
		@MethodSource("reusedConstraintInstanceRows")
		void shouldResolveReusedConstraintInstancePerScope(
			@Nonnull String label,
			@Nonnull String referenceName,
			boolean reused,
			@Nonnull EvitaSessionContract session
		) {
			final int referencedPk = REF_TAGS.equals(referenceName) ? TAG : SUBTREE_CATEGORY;
			final FilterConstraint liveConstraint = referenceHaving(referenceName, entityPrimaryKeyInSet(referencedPk));
			final FilterConstraint archivedConstraint = reused ?
				liveConstraint : referenceHaving(referenceName, entityPrimaryKeyInSet(referencedPk));
			assertQueryReturns(
				session, BOTH_SCOPES,
				new FilterConstraint[]{
					inScope(Scope.LIVE, liveConstraint),
					inScope(Scope.ARCHIVED, archivedConstraint)
				},
				false,
				union(LIVE_SUBTREE, ARCHIVED_SUBTREE)
			);
		}

	}

	/**
	 * The rule that an `inScope` container must not be nested in another one, at every point where a query or a
	 * fetch requirement is admitted, together with the shapes that are accepted.
	 */
	@Nested
	@DisplayName("Nested inScope rejection")
	class NestedInScopeRejection {

		/**
		 * Returns the rows of the nested-`inScope` rejection: a label, the scope of the outer container and the scope
		 * of the container nested in it.
		 *
		 * `inScope(S, P)` applies `P` only when entities of scope `S` are searched. Nesting two opposite containers
		 * would apply `P` when searching LIVE **and** ARCHIVED at once - never - and nesting two equal containers is
		 * redundant, so both are refused when the query is executed.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> nestedInScopeRows() {
			return Stream.of(
				// contradictory: the scope-container rewrite has no sound meaning for it (it would keep the inner
				// container's archived formula inside the LIVE branch and lose every live product)
				Arguments.of("inScope(LIVE, inScope(ARCHIVED, attribute))", Scope.LIVE, Scope.ARCHIVED),
				// mirror image
				Arguments.of("inScope(ARCHIVED, inScope(LIVE, attribute))", Scope.ARCHIVED, Scope.LIVE),
				// redundant
				Arguments.of("inScope(LIVE, inScope(LIVE, attribute))", Scope.LIVE, Scope.LIVE)
			);
		}

		/**
		 * Returns the rows of the controls of the nested-`inScope` rejection - shapes that are accepted and answered:
		 * a label, the constraints placed next to `scope(LIVE, ARCHIVED)` and the expected primary keys.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> acceptedInScopeRows() {
			return Stream.of(
				// invisible live 2, 10, 18 go, every archived product stays
				row(
					"inScope(LIVE, attribute)",
					union(without(LIVE_ALL, INVISIBLE), ARCHIVED_ALL),
					inScope(Scope.LIVE, attributeEquals(ATTR_VISIBLE, true))
				),
				// the invisible products of both scopes go
				row(
					"inScope(LIVE, attribute), inScope(ARCHIVED, attribute)",
					without(ALL_PRODUCTS, INVISIBLE),
					inScope(Scope.LIVE, attributeEquals(ATTR_VISIBLE, true)),
					inScope(Scope.ARCHIVED, attributeEquals(ATTR_VISIBLE, true))
				),
				// an inScope restricting the brand entity in its own nested query is not nested in the outer one: the
				// live products with the brand (unbranded 4, 12, 20 go) and every archived product
				row(
					"inScope(LIVE, referenceHaving(brand, entityHaving(inScope(LIVE, pk))))",
					union(without(LIVE_ALL, UNBRANDED), ARCHIVED_ALL),
					inScope(
						Scope.LIVE,
						referenceHaving(REF_BRAND, entityHaving(inScope(Scope.LIVE, entityPrimaryKeyInSet(BRAND))))
					)
				)
			);
		}

		/**
		 * Checks that a query with `inScope` nested in another `inScope` is refused when executed, with an error naming
		 * both scopes - while the constraint itself can still be built, so that a stored query of this shape stays
		 * readable. The opposite nesting is contradictory, the same-scope nesting redundant.
		 *
		 * @param label      the row label, used in the test name only
		 * @param outerScope the scope of the outer container
		 * @param innerScope the scope of the container nested in it
		 * @param session    the session provided by the test extension
		 */
		@DisplayName("Should reject inScope nested in another inScope")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}")
		@MethodSource("nestedInScopeRows")
		void shouldRejectInScopeNestedInAnotherInScope(
			@Nonnull String label,
			@Nonnull Scope outerScope,
			@Nonnull Scope innerScope,
			@Nonnull EvitaSessionContract session
		) {
			final FilterConstraint nested = assertDoesNotThrow(
				() -> inScope(outerScope, inScope(innerScope, attributeEquals(ATTR_VISIBLE, true)))
			);
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> runQuery(session, BOTH_SCOPES, new FilterConstraint[]{nested}, false)
			);
			assertTrue(
				exception.getMessage().contains("inScope(" + innerScope.name()) &&
					exception.getMessage().contains("inScope(" + outerScope.name()),
				() -> "the rejection must name both scopes, got: " + exception.getMessage()
			);
		}

		/**
		 * Control of {@link #shouldRejectInScopeNestedInAnotherInScope}: `inScope` containers placed side by side, and
		 * an `inScope` restricting another entity inside an `inScope`, are accepted and answered.
		 *
		 * @param label              the row label, used in the test name only
		 * @param constraints        the constraints placed next to `scope(LIVE, ARCHIVED)`
		 * @param expected           the expected primary keys, ascending
		 * @param session            the session provided by the test extension
		 */
		@DisplayName("Should apply inScope containers that are not nested in one another")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}")
		@MethodSource("acceptedInScopeRows")
		void shouldApplyInScopeContainersNotNestedInOneAnother(
			@Nonnull String label,
			@Nonnull FilterConstraint[] constraints,
			@Nonnull int[] expected,
			@Nonnull EvitaSessionContract session
		) {
			assertQueryReturns(session, BOTH_SCOPES, constraints, false, expected);
		}

		/**
		 * Checks that a query with nested `inScope` survives the storage round trip unchanged - a traffic recording
		 * made before the nesting was refused deserializes every stored query up front, and one such query must not
		 * make the whole recording unreadable - and that executing the read-back query is refused like any other.
		 *
		 * @param session the session provided by the test extension
		 */
		@DisplayName("Should read a stored query with nested inScope back and reject its execution")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@Test
		@Tag(STORAGE)
		void shouldReadStoredQueryWithNestedInScopeBackAndRejectItsExecution(@Nonnull EvitaSessionContract session) {
			final Query stored = query(
				collection(ENTITY_PRODUCT),
				filterBy(
					scope(BOTH_SCOPES),
					inScope(Scope.LIVE, inScope(Scope.ARCHIVED, attributeEquals(ATTR_VISIBLE, true)))
				),
				orderBy(inScope(Scope.LIVE, inScope(Scope.LIVE, attributeNatural(ATTR_VISIBLE)))),
				require(page(1, PRODUCT_COUNT))
			);
			final Kryo kryo = KryoFactory.createKryo(QuerySerializationKryoConfigurer.INSTANCE);
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1_024);
			try (final Output output = new Output(bytes, 1_024)) {
				kryo.writeObject(output, stored);
			}
			final Query readBack;
			try (final Input input = new Input(bytes.toByteArray())) {
				readBack = assertDoesNotThrow(() -> kryo.readObject(input, Query.class));
			}
			assertEquals(stored, readBack);

			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> session.query(readBack, EntityReference.class)
			);
			assertTrue(
				exception.getMessage().contains("inScope(ARCHIVED") && exception.getMessage().contains("inScope(LIVE"),
				() -> "the rejection must name both scopes, got: " + exception.getMessage()
			);
		}

		/**
		 * Checks that a nesting introduced by copying a valid container with new children - which no constructor sees -
		 * is refused when the query is executed.
		 *
		 * @param session the session provided by the test extension
		 */
		@DisplayName("Should reject inScope nesting introduced by copying a container with new children")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@Test
		void shouldRejectInScopeNestingIntroducedByCopyingContainer(@Nonnull EvitaSessionContract session) {
			final FilterInScope valid = inScope(Scope.LIVE, attributeEquals(ATTR_VISIBLE, true));
			final FilterConstraint copy = valid.getCopyWithNewChildren(
				new FilterConstraint[]{inScope(Scope.ARCHIVED, attributeEquals(ATTR_VISIBLE, true))},
				new Constraint<?>[0]
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> runQuery(session, BOTH_SCOPES, new FilterConstraint[]{copy}, false)
			);
		}

		/**
		 * Checks that a nested `inScope` in the reference content filter of a direct fetch or an enrichment is refused
		 * like in a query - neither is planned, the reference fetcher evaluates the filter directly, so the rule is
		 * checked where the fetch and the enrichment are admitted. A filter whose nesting was introduced by copying a
		 * container with new children is refused too, and the same filter without the nesting is answered.
		 *
		 * @param session the session provided by the test extension
		 */
		@DisplayName("Should reject nested inScope in the reference content of a fetch and an enrichment")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@Test
		void shouldRejectNestedInScopeInReferenceContentOfFetchAndEnrichment(@Nonnull EvitaSessionContract session) {
			final FilterConstraint nested = inScope(
				Scope.LIVE, inScope(Scope.LIVE, entityPrimaryKeyInSet(SUBTREE_CATEGORY))
			);
			final FilterConstraint copiedNested = inScope(Scope.LIVE, entityPrimaryKeyInSet(SUBTREE_CATEGORY))
				.getCopyWithNewChildren(
					new FilterConstraint[]{inScope(Scope.LIVE, entityPrimaryKeyInSet(SUBTREE_CATEGORY))},
					new Constraint<?>[0]
				);
			for (final FilterConstraint filter : new FilterConstraint[]{nested, copiedNested}) {
				assertThrows(
					EvitaInvalidUsageException.class,
					() -> session.getEntity(ENTITY_PRODUCT, 1, referenceContent(REF_CATEGORIES, filterBy(filter))),
					() -> "a direct fetch must refuse " + filter
				);
				final SealedEntity bare = session.getEntity(ENTITY_PRODUCT, 1).orElseThrow();
				assertThrows(
					EvitaInvalidUsageException.class,
					() -> session.enrichEntity(bare, referenceContent(REF_CATEGORIES, filterBy(filter))),
					() -> "an enrichment must refuse " + filter
				);
			}

			final SealedEntity fetched = session.getEntity(
				ENTITY_PRODUCT, 1,
				referenceContent(REF_CATEGORIES, filterBy(inScope(Scope.LIVE, entityPrimaryKeyInSet(SUBTREE_CATEGORY))))
			).orElseThrow();
			assertEquals(
				List.of(SUBTREE_CATEGORY),
				fetched.getReferences(REF_CATEGORIES).stream().map(ReferenceContract::getReferencedPrimaryKey).toList()
			);
		}

		/**
		 * Returns the rows of the mutation-fetch rejection: a label, the entity type and primary key of the changed
		 * entity, and the call that changes it and fetches it with the passed requirement.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> mutationFetchRows() {
			return Stream.of(
				Arguments.of(
					"upsertAndFetchEntity", ENTITY_PRODUCT, LIVE_TAGGED_PRODUCT,
					(MutationFetch) (session, require) -> session.upsertAndFetchEntity(
						session.getEntity(ENTITY_PRODUCT, LIVE_TAGGED_PRODUCT, attributeContentAll())
							.orElseThrow()
							.openForWrite()
							.setAttribute(ATTR_VISIBLE, false),
						require
					)
				),
				Arguments.of(
					"deleteEntity", ENTITY_PRODUCT, LIVE_TAGGED_PRODUCT,
					(MutationFetch) (session, require) -> session.deleteEntity(
						ENTITY_PRODUCT, LIVE_TAGGED_PRODUCT, require
					)
				),
				Arguments.of(
					"deleteEntityAndItsHierarchy", ENTITY_CATEGORY, OTHER_CATEGORY,
					(MutationFetch) (session, require) -> session.deleteEntityAndItsHierarchy(
						ENTITY_CATEGORY, OTHER_CATEGORY, require
					)
				),
				Arguments.of(
					"archiveEntity", ENTITY_PRODUCT, LIVE_TAGGED_PRODUCT,
					(MutationFetch) (session, require) -> session.archiveEntity(
						ENTITY_PRODUCT, LIVE_TAGGED_PRODUCT, require
					)
				),
				Arguments.of(
					"restoreEntity", ENTITY_PRODUCT, ARCHIVED_TAGGED_PRODUCT,
					(MutationFetch) (session, require) -> session.restoreEntity(
						ENTITY_PRODUCT, ARCHIVED_TAGGED_PRODUCT, require
					)
				)
			);
		}

		/**
		 * Checks that a call that changes an entity and returns it refuses a reference content requirement with
		 * `inScope` nested in another `inScope` - and refuses it before the change, so the entity is left exactly as
		 * it was. The state is read in the same session right after the refusal, before anything could roll the change
		 * back.
		 *
		 * @param label      the row label, used in the test name only
		 * @param entityType the type of the changed entity
		 * @param primaryKey the primary key of the changed entity
		 * @param call       the call that changes the entity and fetches it
		 * @param evita      the engine instance provided by the test extension
		 */
		@DisplayName("Should reject nested inScope in the reference content of a mutation that fetches the entity")
		@UseDataSet(value = IN_SCOPE_REDUCED_INDEX_PLAN_WRITABLE, destroyAfterTest = true)
		@ParameterizedTest(name = "{0}")
		@MethodSource("mutationFetchRows")
		void shouldRejectNestedInScopeInReferenceContentOfMutationFetch(
			@Nonnull String label,
			@Nonnull String entityType,
			int primaryKey,
			@Nonnull MutationFetch call,
			@Nonnull Evita evita
		) {
			final EntityContentRequire nested = referenceContent(
				REF_TAGS, filterBy(inScope(Scope.LIVE, inScope(Scope.LIVE, entityPrimaryKeyInSet(TAG))))
			);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final String before = describeState(session, entityType, primaryKey);
					assertThrows(
						EvitaInvalidUsageException.class,
						() -> call.apply(session, nested),
						() -> label + " must refuse " + nested
					);
					assertEquals(
						before,
						describeState(session, entityType, primaryKey),
						() -> label + " must leave the entity unchanged"
					);
				}
			);
		}

		/**
		 * Describes the state of an entity a mutation could change: its presence, scope and version.
		 *
		 * @param session    the session to read in
		 * @param entityType the entity type
		 * @param primaryKey the entity primary key
		 * @return the description
		 */
		@Nonnull
		private static String describeState(
			@Nonnull EvitaSessionContract session,
			@Nonnull String entityType,
			int primaryKey
		) {
			return session.getEntity(entityType, primaryKey, BOTH_SCOPES)
				.map(it -> it.getScope() + " version " + it.version())
				.orElse("absent");
		}

		/**
		 * A call that changes an entity and fetches it with the passed requirement.
		 */
		@FunctionalInterface
		interface MutationFetch {

			/**
			 * Changes the entity and fetches it.
			 *
			 * @param session the read-write session
			 * @param require the requirement the entity is fetched with
			 */
			void apply(@Nonnull EvitaSessionContract session, @Nonnull EntityContentRequire require);

		}

	}

	/**
	 * Hierarchy statistics of one scope computed from what an occurrence of the hierarchy filter covering that scope
	 * resolved - its roots and its node visibility - and never from an occurrence restricted to another scope.
	 */
	@Nested
	@DisplayName("Hierarchy statistics per scope")
	class HierarchyStatisticsPerScope {

		/**
		 * Returns the rows of the mixed scoped / top-level hierarchy witnesses: whether the scoped constraint comes
		 * first, and whether both places hold the same instance.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> mixedHierarchyOccurrenceRows() {
			return Stream.of(
				Arguments.of(true, true),
				Arguments.of(true, false),
				Arguments.of(false, true),
				Arguments.of(false, false)
			);
		}

		/**
		 * Checks that hierarchy statistics of a scope use the roots resolved by an occurrence of the constraint that
		 * covers that scope when an equal `hierarchyWithin` sits both in `inScope(ARCHIVED, ...)` and at the top level
		 * of a query over both scopes.
		 *
		 * The parent filter `entityPrimaryKeyInSet(1)` resolves to root 1 for the top-level occurrence (category 1 is
		 * live) and to no root at all for the occurrence scoped to ARCHIVED (the archived tree does not contain 1). The
		 * live `children` statistics must describe root 1 with its 8 live owners, whichever occurrence is translated
		 * first and whether they are one instance or two - the archived occurrence does not apply to the live scope.
		 *
		 * @param scopedFirst whether the scoped constraint precedes the top-level one
		 * @param reused      whether both places hold the same instance
		 * @param session     the session provided by the test extension
		 */
		@DisplayName("Should use the roots covering their scope when scoped and top-level occurrences mix")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "scoped first: {0}, reused instance: {1}")
		@MethodSource("mixedHierarchyOccurrenceRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldComputeHierarchyStatisticsFromRootsCoveringTheirScope(
			boolean scopedFirst,
			boolean reused,
			@Nonnull EvitaSessionContract session
		) {
			final FilterConstraint scoped = hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY));
			final FilterConstraint topLevel = reused ?
				scoped : hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY));
			final List<LevelInfo> levels = queryLiveHierarchyStatistics(
				session,
				scopedFirst ?
					new FilterConstraint[]{inScope(Scope.ARCHIVED, scoped), topLevel} :
					new FilterConstraint[]{topLevel, inScope(Scope.ARCHIVED, scoped)},
				children(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT))
			);
			assertEquals(
				List.of(ROOT_CATEGORY),
				levels.stream().map(it -> it.entity().getPrimaryKey()).toList(),
				() -> "the live statistics must describe the live root 1, got: " + levels
			);
			assertEquals(Integer.valueOf(LIVE_SUBTREE.length), levels.get(0).queriedEntityCount());
		}

		/**
		 * Checks that hierarchy statistics of a scope use the node visibility the filter resolved for that scope when
		 * an equal `hierarchyWithinRoot(..., excluding(...))` sits both in `inScope(ARCHIVED, ...)` and at the top
		 * level of a query over both scopes.
		 *
		 * The exclusion of category 3 resolved in the archived scope sees no live node at all; resolved for the
		 * top-level constraint it hides the subtree of 3 only. The live `fromRoot` statistics must therefore list root
		 * 1 - the only live root with live owners once 3 is hidden (4 has archived owners only) - whichever constraint
		 * comes first.
		 *
		 * @param scopedFirst whether the scoped constraint precedes the top-level one
		 * @param reused      whether both places hold the same instance
		 * @param session     the session provided by the test extension
		 */
		@DisplayName("Should use the visibility covering their scope when scoped and top-level occurrences mix")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "scoped first: {0}, reused instance: {1}")
		@MethodSource("mixedHierarchyOccurrenceRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldComputeHierarchyStatisticsWithVisibilityCoveringTheirScope(
			boolean scopedFirst,
			boolean reused,
			@Nonnull EvitaSessionContract session
		) {
			final FilterConstraint scoped = hierarchyWithinRoot(
				REF_CATEGORIES, excluding(entityPrimaryKeyInSet(OTHER_CATEGORY))
			);
			final FilterConstraint topLevel = reused ?
				scoped : hierarchyWithinRoot(REF_CATEGORIES, excluding(entityPrimaryKeyInSet(OTHER_CATEGORY)));
			final List<LevelInfo> levels = queryLiveHierarchyStatistics(
				session,
				scopedFirst ?
					new FilterConstraint[]{inScope(Scope.ARCHIVED, scoped), topLevel} :
					new FilterConstraint[]{topLevel, inScope(Scope.ARCHIVED, scoped)},
				fromRoot(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT))
			);
			assertEquals(
				List.of(ROOT_CATEGORY),
				levels.stream().map(it -> it.entity().getPrimaryKey()).toList(),
				() -> "the live statistics must list root 1 only, got: " + levels
			);
		}

		/**
		 * Checks that hierarchy statistics computed for one scope use the hierarchy roots resolved in that scope when
		 * one `hierarchyWithin` instance is placed both in `inScope(LIVE, ...)` and in `inScope(ARCHIVED, ...)`.
		 *
		 * The parent filter `entityPrimaryKeyInSet(1, 11)` matches root 1 in the live tree and root 11 in the archived
		 * tree. The archived `children` statistics must describe the archived root 11 - requested, with its 8 archived
		 * owners 49-56 below it - rather than look for the live root 1, which the archived tree does not contain and
		 * which would leave the statistics empty.
		 *
		 * @param reused  whether both containers hold the same instance
		 * @param session the session provided by the test extension
		 */
		@DisplayName("Should use the roots resolved in the scope the statistics are computed for")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "reused instance: {0}")
		@ValueSource(booleans = {true, false})
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldComputeHierarchyStatisticsOfScopeFromRootsResolvedInThatScope(
			boolean reused,
			@Nonnull EvitaSessionContract session
		) {
			final FilterConstraint liveHierarchy = hierarchyWithin(
				REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY)
			);
			final FilterConstraint archivedHierarchy = reused ?
				liveHierarchy :
				hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY));
			final EvitaResponse<EntityReference> response = session.query(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(
						scope(BOTH_SCOPES),
						inScope(Scope.LIVE, liveHierarchy),
						inScope(Scope.ARCHIVED, archivedHierarchy)
					),
					require(
						page(1, PRODUCT_COUNT),
						inScope(
							Scope.ARCHIVED,
							hierarchyOfReference(
								REF_CATEGORIES,
								children(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT))
							)
						)
					)
				),
				EntityReference.class
			);
			assertArrayEquals(
				union(LIVE_SUBTREE, ARCHIVED_SUBTREE),
				response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
			);
			final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
			assertNotNull(hierarchy, "the hierarchy statistics must be computed");
			final List<LevelInfo> children = hierarchy.getReferenceHierarchy(REF_CATEGORIES, HIERARCHY_OUTPUT);
			assertEquals(
				List.of(ARCHIVED_ROOT_CATEGORY),
				children.stream().map(it -> it.entity().getPrimaryKey()).toList(),
				() -> "the archived statistics must describe the archived root 11, got: " + children
			);
			assertTrue(children.get(0).requested());
			assertEquals(Integer.valueOf(ARCHIVED_SUBTREE.length), children.get(0).queriedEntityCount());
		}

		/**
		 * Returns the rows of the witness of a scope no occurrence of the hierarchy filter covers: a label, the
		 * hierarchy filter restricted to ARCHIVED, the live statistics requirement, the expected primary keys, and
		 * the expected description of the live statistics - NULL when they must equal those of the same query
		 * without the hierarchy filter.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> scopeNotCoveredByHierarchyFilterRows() {
			// the archived occurrence resolves root 11, which the live tree does not contain
			final FilterConstraint archivedRoots = inScope(
				Scope.ARCHIVED,
				hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY))
			);
			return Stream.of(
				// catches the live statistics reading the roots of an occurrence that does not cover the live scope
				Arguments.of(
					"roots of inScope(ARCHIVED, hierarchyWithin), children",
					archivedRoots,
					children(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
					ARCHIVED_NARROWED,
					null
				),
				// the parents describe the path to a node the filter selects - the live scope has none, and the same
				// query without any hierarchyWithin refuses `parents` outright, so the statistics are empty
				Arguments.of(
					"roots of inScope(ARCHIVED, hierarchyWithin), parents",
					archivedRoots,
					parents(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
					ARCHIVED_NARROWED,
					List.of()
				),
				// without a selected node the siblings are those of the live roots
				Arguments.of(
					"roots of inScope(ARCHIVED, hierarchyWithin), siblings",
					archivedRoots,
					siblings(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
					ARCHIVED_NARROWED,
					null
				),
				// the predicate resolved in the archived scope admits archived nodes only; catches the live statistics
				// observing the node visibility of an occurrence that does not cover the live scope
				Arguments.of(
					"visibility of inScope(ARCHIVED, hierarchyWithinRoot(having)), fromRoot",
					inScope(
						Scope.ARCHIVED,
						hierarchyWithinRoot(
							REF_CATEGORIES,
							having(
								entityPrimaryKeyInSet(
									ARCHIVED_ROOT_CATEGORY, ARCHIVED_SUBTREE_CATEGORY, ARCHIVED_NODE_WITH_LIVE_OWNERS
								)
							)
						)
					),
					fromRoot(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
					ARCHIVED_NARROWED,
					null
				)
			);
		}

		/**
		 * Checks that a hierarchy filter placed only in `inScope(ARCHIVED, ...)` does not restrict the live hierarchy
		 * statistics: the live entities are not filtered by it, so the live statistics must equal those of the same
		 * query without it. The live record set is the same in both queries, so the counts must match too. The
		 * `parents` row is the exception: the same query without a `hierarchyWithin` refuses `parents`, and with no
		 * node selected in the live scope there is no path to describe, so its statistics are empty.
		 *
		 * @param label              the row label, used in the test name only
		 * @param scopedFilter       the hierarchy filter restricted to ARCHIVED
		 * @param requirement        the live statistics requirement, named {@link #HIERARCHY_OUTPUT}
		 * @param expectedRecords    the expected primary keys, ascending
		 * @param expectedStatistics the expected description of the live statistics, NULL when they must equal those
		 *                           of the query without the hierarchy filter
		 * @param session            the session provided by the test extension
		 */
		@DisplayName("Should compute the statistics of a scope no occurrence covers as if unfiltered")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}")
		@MethodSource("scopeNotCoveredByHierarchyFilterRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldComputeStatisticsOfScopeNotCoveredByHierarchyFilterAsIfUnfiltered(
			@Nonnull String label,
			@Nonnull FilterConstraint scopedFilter,
			@Nonnull HierarchyRequireConstraint requirement,
			@Nonnull int[] expectedRecords,
			@Nullable List<String> expectedStatistics,
			@Nonnull EvitaSessionContract session
		) {
			final List<String> expected;
			if (expectedStatistics == null) {
				expected = describe(queryLiveHierarchyStatistics(session, new FilterConstraint[0], requirement));
				assertFalse(expected.isEmpty(), "the live statistics of the unfiltered query must not be empty");
			} else {
				expected = expectedStatistics;
			}

			final EvitaResponse<EntityReference> response = queryLiveHierarchy(
				session, BOTH_SCOPES, new FilterConstraint[]{scopedFilter}, requirement
			);
			assertArrayEquals(expectedRecords, sortedPrimaryKeys(response));
			assertEquals(expected, describe(liveStatistics(response)));
		}

		/**
		 * Returns the statistics requirements of the empty-root witness, each named {@link #HIERARCHY_OUTPUT}.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> statisticsOfSelectedNodeRows() {
			return Stream.of(
				Arguments.of("children", children(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT))),
				Arguments.of("parents", parents(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT))),
				Arguments.of("siblings", siblings(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT)))
			);
		}

		/**
		 * Checks the statistics of a scope whose own `hierarchyWithin` occurrence selects no node:
		 * `inScope(LIVE, hierarchyWithin(categories, 11))` covers the live scope, but category 11 exists in the
		 * archived tree only, so the live statistics have no node to describe and are empty, while every archived
		 * product is returned. A query over the live scope alone with the same `hierarchyWithin` - the control -
		 * answers the same way: it returns no product and is short-circuited before any statistics are computed.
		 *
		 * @param label       the row label, used in the test name only
		 * @param requirement the live statistics requirement
		 * @param session     the session provided by the test extension
		 */
		@DisplayName("Should compute empty node-relative statistics of a scope whose occurrence selects no node")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}")
		@MethodSource("statisticsOfSelectedNodeRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldComputeStatisticsOfScopeWhoseCoveringOccurrenceSelectsNoNode(
			@Nonnull String label,
			@Nonnull HierarchyRequireConstraint requirement,
			@Nonnull EvitaSessionContract session
		) {
			final EvitaResponse<EntityReference> control = queryLiveHierarchy(
				session,
				LIVE_ONLY,
				new FilterConstraint[]{hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(ARCHIVED_ROOT_CATEGORY))},
				requirement
			);
			assertArrayEquals(new int[0], sortedPrimaryKeys(control), "the live-only control must return nothing");
			assertNull(control.getExtraResult(Hierarchy.class), "the live-only control must compute no statistics");

			final EvitaResponse<EntityReference> response = queryLiveHierarchy(
				session, BOTH_SCOPES, new FilterConstraint[]{liveHierarchy(ARCHIVED_ROOT_CATEGORY)}, requirement
			);
			assertArrayEquals(ARCHIVED_ALL, sortedPrimaryKeys(response));
			assertEquals(List.of(), liveStatistics(response), "the live statistics must be empty");
		}

		/**
		 * Returns the rows of the witness of a top-level `hierarchyWithin` whose parent filter selects a node in each
		 * scope: the scope the statistics are computed for and the statistics requirement.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> parentSelectedInEveryScopeRows() {
			return Stream.of(Scope.LIVE, Scope.ARCHIVED)
				.flatMap(
					scope -> statisticsOfSelectedNodeRows()
						.map(row -> Arguments.of(scope, row.get()[0], row.get()[1]))
				);
		}

		/**
		 * Checks the statistics of each scope when a top-level `hierarchyWithin` of a query over both scopes selects
		 * a node in each of them: `entityPrimaryKeyInSet(1, 11)` matches root 1 in the live tree and root 11 in the
		 * archived tree. The statistics of a scope describe the node selected in that scope's own tree - the node of
		 * the other scope is not part of it - so they must equal those of the same query over that scope alone, and
		 * the records must be those of the two single-scope queries together.
		 *
		 * @param statisticsScope the scope the statistics are computed for
		 * @param label           the row label, used in the test name only
		 * @param requirement     the statistics requirement, named {@link #HIERARCHY_OUTPUT}
		 * @param session         the session provided by the test extension
		 */
		@DisplayName("Should compute the statistics of a scope from the node selected in its own tree")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0} {1}")
		@MethodSource("parentSelectedInEveryScopeRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldComputeStatisticsOfScopeFromNodeSelectedInItsOwnTree(
			@Nonnull Scope statisticsScope,
			@Nonnull String label,
			@Nonnull HierarchyRequireConstraint requirement,
			@Nonnull EvitaSessionContract session
		) {
			final FilterConstraint[] hierarchyFilter = {
				hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY))
			};
			final EvitaResponse<EntityReference> control = queryHierarchy(
				session, new Scope[]{statisticsScope}, statisticsScope, hierarchyFilter, requirement
			);
			// queried for its records only
			final Scope otherScope = statisticsScope == Scope.LIVE ? Scope.ARCHIVED : Scope.LIVE;
			final EvitaResponse<EntityReference> otherScopeControl = queryHierarchy(
				session, new Scope[]{otherScope}, otherScope, hierarchyFilter, requirement
			);
			final List<String> expected = describe(computedStatistics(control));
			assertFalse(expected.isEmpty(), "the statistics of the single-scope control must not be empty");

			final EvitaResponse<EntityReference> response = queryHierarchy(
				session, BOTH_SCOPES, statisticsScope, hierarchyFilter, requirement
			);
			assertArrayEquals(
				union(sortedPrimaryKeys(control), sortedPrimaryKeys(otherScopeControl)), sortedPrimaryKeys(response)
			);
			assertEquals(expected, describe(computedStatistics(response)));
		}

		/**
		 * Checks that the statistics of a scope use the roots resolved in that scope when an equal `hierarchyWithin`
		 * selecting its parent by a unique attribute sits both at the top level of a query over both scopes and in
		 * `inScope(ARCHIVED, ...)`, whichever of the two is translated first.
		 *
		 * Category 1 holds the unique value in the live scope and category 11 in the archived one. Both occurrences
		 * look the value up in the archived scope alone for the archived scope, so the archived `children` statistics
		 * must describe category 11 and its child 12, each with the 8 archived owners 49-56 - not the live category 1,
		 * which a lookup over both scopes would answer with.
		 *
		 * @param scopedFirst whether the scoped constraint precedes the top-level one
		 * @param reused      whether both places hold the same instance
		 * @param session     the session provided by the test extension
		 */
		@DisplayName("Should resolve a unique parent in the scope of the statistics whichever occurrence comes first")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "scoped first: {0}, reused instance: {1}")
		@MethodSource("mixedHierarchyOccurrenceRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldResolveUniqueParentInScopeOfStatisticsWhicheverOccurrenceComesFirst(
			boolean scopedFirst,
			boolean reused,
			@Nonnull EvitaSessionContract session
		) {
			final FilterConstraint scoped = hierarchyWithin(REF_CATEGORIES, attributeEquals(ATTR_CODE, ROOT_CODE));
			final FilterConstraint topLevel = reused ?
				scoped : hierarchyWithin(REF_CATEGORIES, attributeEquals(ATTR_CODE, ROOT_CODE));
			final EvitaResponse<EntityReference> response = queryHierarchy(
				session,
				BOTH_SCOPES,
				Scope.ARCHIVED,
				scopedFirst ?
					new FilterConstraint[]{inScope(Scope.ARCHIVED, scoped), topLevel} :
					new FilterConstraint[]{topLevel, inScope(Scope.ARCHIVED, scoped)},
				children(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT))
			);
			assertArrayEquals(union(LIVE_SUBTREE, ARCHIVED_SUBTREE), sortedPrimaryKeys(response));
			assertEquals(
				List.of(
					ARCHIVED_ROOT_CATEGORY + ": " + ARCHIVED_SUBTREE.length,
					"  " + ARCHIVED_SUBTREE_CATEGORY + ": " + ARCHIVED_SUBTREE.length
				),
				describe(computedStatistics(response))
			);
		}

		/**
		 * Runs a query over the passed scopes with the passed filter constraints, requesting the hierarchy statistics
		 * of the `categories` reference in the passed scope.
		 *
		 * @param session         the session to query
		 * @param scopes          the scopes of `scope(...)`
		 * @param statisticsScope the scope the statistics are computed for
		 * @param constraints     the constraints placed next to `scope(...)`
		 * @param requirement     the statistics requirement, named {@link #HIERARCHY_OUTPUT}
		 * @return the response
		 */
		@Nonnull
		private static EvitaResponse<EntityReference> queryHierarchy(
			@Nonnull EvitaSessionContract session,
			@Nonnull Scope[] scopes,
			@Nonnull Scope statisticsScope,
			@Nonnull FilterConstraint[] constraints,
			@Nonnull HierarchyRequireConstraint requirement
		) {
			return session.query(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(ArrayUtils.mergeArrays(new FilterConstraint[]{scope(scopes)}, constraints)),
					require(
						page(1, PRODUCT_COUNT),
						inScope(statisticsScope, hierarchyOfReference(REF_CATEGORIES, requirement))
					)
				),
				EntityReference.class
			);
		}

		/**
		 * Returns the hierarchy statistics of the `categories` reference named {@link #HIERARCHY_OUTPUT} that the
		 * response carries, or an empty list when the query computed none.
		 *
		 * @param response the response of a query requesting the statistics
		 * @return the statistics
		 */
		@Nonnull
		private static List<LevelInfo> computedStatistics(@Nonnull EvitaResponse<EntityReference> response) {
			final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
			return hierarchy == null ? List.of() : hierarchy.getReferenceHierarchy(REF_CATEGORIES, HIERARCHY_OUTPUT);
		}

		/**
		 * Returns the rows of the pivot-independent statistics witness: the label and the requirement, each named
		 * {@link #HIERARCHY_OUTPUT}.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> pivotIndependentStatisticsRows() {
			return Stream.of(
				Arguments.of("fromRoot", fromRoot(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT))),
				Arguments.of(
					"fromNode",
					fromNode(
						HIERARCHY_OUTPUT,
						node(filterBy(entityPrimaryKeyInSet(ROOT_CATEGORY))),
						statistics(StatisticsType.QUERIED_ENTITY_COUNT)
					)
				)
			);
		}

		/**
		 * Checks that the `fromRoot` and `fromNode` statistics of a scope whose covering `hierarchyWithin` selects no
		 * node are computed as for any other pivot: they are not affected by the pivot, so the live statistics under
		 * `inScope(LIVE, hierarchyWithin(categories, 999))` - a category that exists nowhere - equal those of the same
		 * query without that container, where the same live products are counted.
		 *
		 * @param label       the row label, used in the test name only
		 * @param requirement the live statistics requirement
		 * @param session     the session provided by the test extension
		 */
		@DisplayName("Should compute pivot-independent statistics of a scope whose covering occurrence selects no node")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}")
		@MethodSource("pivotIndependentStatisticsRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldComputePivotIndependentStatisticsOfScopeWhoseCoveringOccurrenceSelectsNoNode(
			@Nonnull String label,
			@Nonnull HierarchyRequireConstraint requirement,
			@Nonnull EvitaSessionContract session
		) {
			final List<String> expected = describe(
				queryLiveHierarchyStatistics(session, new FilterConstraint[0], requirement)
			);
			assertFalse(expected.isEmpty(), "the live statistics of the query without the pivot must not be empty");
			assertEquals(
				expected,
				describe(
					queryLiveHierarchyStatistics(
						session,
						new FilterConstraint[]{
							inScope(
								Scope.LIVE,
								hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(MISSING_CATEGORY))
							)
						},
						requirement
					)
				)
			);
		}

		/**
		 * Returns the rows of the own-hierarchy witness: the label, the requirement named {@link #HIERARCHY_OUTPUT}
		 * and the pivot of the `hierarchyWithinSelf` placed in `inScope(LIVE, ...)` - category 999 exists nowhere,
		 * category 1 is the live root.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> ownStatisticsOfScopedPivotRows() {
			return Stream.concat(
				pivotIndependentStatisticsRows()
					.flatMap(
						row -> Stream.of(MISSING_CATEGORY, ROOT_CATEGORY)
							.map(pivot -> Arguments.of(row.get()[0] + ", pivot " + pivot, row.get()[1], pivot))
					),
				Stream.of(
					Arguments.of(
						"children, pivot " + ROOT_CATEGORY,
						children(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
						ROOT_CATEGORY
					)
				)
			);
		}

		/**
		 * Checks that the statistics of the categories' own hierarchy in a scope whose `hierarchyWithinSelf` sits in
		 * `inScope(LIVE, ...)` of a query over both scopes equal those of a query over the live scope alone with the
		 * same `hierarchyWithinSelf` - whether it selects a node or not. The statistics count the queried entities
		 * with the hierarchy filter removed, and removing the only constraint of a scope leaves the scope
		 * unrestricted, not empty.
		 *
		 * @param label       the row label, used in the test name only
		 * @param requirement the live statistics requirement
		 * @param pivot       the primary key the `hierarchyWithinSelf` selects its parent by
		 * @param session     the session provided by the test extension
		 */
		@DisplayName("Should compute own statistics of a scope whose occurrence sits in inScope like that scope alone")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}")
		@MethodSource("ownStatisticsOfScopedPivotRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldComputeOwnStatisticsOfScopeWhoseOccurrenceSitsInScopeContainerLikeThatScopeAlone(
			@Nonnull String label,
			@Nonnull HierarchyRequireConstraint requirement,
			int pivot,
			@Nonnull EvitaSessionContract session
		) {
			final List<String> expected = describe(
				queryStatistics(
					session, LIVE_ONLY, Scope.LIVE, true,
					new FilterConstraint[]{hierarchyWithinSelf(entityPrimaryKeyInSet(pivot))},
					requirement
				)
			);
			assertFalse(expected.isEmpty(), "the statistics of the single-scope control must not be empty");
			assertEquals(
				expected,
				describe(
					queryStatistics(
						session, BOTH_SCOPES, Scope.LIVE, true,
						new FilterConstraint[]{inScope(Scope.LIVE, hierarchyWithinSelf(entityPrimaryKeyInSet(pivot)))},
						requirement
					)
				)
			);
		}

		/**
		 * Describes the statistics as a depth-first list of `primary key: queried entity count` entries, indented by
		 * level, so that two statistics compare by structure and counts alone.
		 *
		 * @param levels the statistics to describe
		 * @return the description
		 */
		@Nonnull
		private static List<String> describe(@Nonnull List<LevelInfo> levels) {
			final List<String> description = new ArrayList<>(levels.size());
			describe(levels, "", description);
			return description;
		}

		/**
		 * Appends the description of the statistics on one level and of their children.
		 *
		 * @param levels      the statistics of the level
		 * @param indent      the indentation of the level
		 * @param description the sink of the description
		 */
		private static void describe(
			@Nonnull List<LevelInfo> levels,
			@Nonnull String indent,
			@Nonnull List<String> description
		) {
			for (final LevelInfo level : levels) {
				description.add(indent + level.entity().getPrimaryKey() + ": " + level.queriedEntityCount());
				describe(level.children(), indent + "  ", description);
			}
		}

	}

	/**
	 * A hierarchy filter placed outside any `inScope(...)` container of a query over both scopes: the parent nodes are
	 * resolved in each scope's own tree, exactly as in a query over that scope alone, and the entities of every queried
	 * scope that reference a node of any of those trees match - so the query returns everything each scope returns
	 * alone, plus the entities of one scope referencing a node of the other scope's tree.
	 */
	@Nested
	@DisplayName("Hierarchy filter over both scopes")
	class HierarchyWithinOverBothScopes {

		/**
		 * Returns the rows of the record witness: a label, the queried entity type, the hierarchy filter, and the
		 * primary keys expected over both scopes, over LIVE only and over ARCHIVED only. Over both scopes, the
		 * products 49-56 reference the live node 2 as well as the archived node 12, products 9-16 reference the
		 * archived node 15 only and products 65-72 the live node 4 only.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> hierarchyFilterRows() {
			final int[] bothSubtrees = union(LIVE_SUBTREE, ARCHIVED_SUBTREE, new int[]{ARCHIVED_SUBTREE_ONLY_PRODUCT});
			final int[] archivedSubtree = union(ARCHIVED_SUBTREE, new int[]{ARCHIVED_SUBTREE_ONLY_PRODUCT});
			final int[] bothTrees = {
				ROOT_CATEGORY, SUBTREE_CATEGORY, ARCHIVED_ROOT_CATEGORY, ARCHIVED_SUBTREE_CATEGORY
			};
			final int[] none = new int[0];
			final int[] liveCategories = {
				ROOT_CATEGORY, SUBTREE_CATEGORY, OTHER_CATEGORY, LIVE_NODE_WITH_ARCHIVED_OWNERS
			};
			final int[] archivedCategories = {
				ARCHIVED_ROOT_CATEGORY, ARCHIVED_SUBTREE_CATEGORY, ARCHIVED_NODE_WITH_LIVE_OWNERS
			};
			return Stream.of(
				Arguments.of(
					"roots by primary key", ENTITY_PRODUCT,
					hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY)),
					bothSubtrees, LIVE_SUBTREE, archivedSubtree
				),
				// the unique value is held by category 1 in the live scope and by category 11 in the archived one
				Arguments.of(
					"roots by unique attribute", ENTITY_PRODUCT,
					hierarchyWithin(REF_CATEGORIES, attributeEquals(ATTR_CODE, ROOT_CODE)),
					bothSubtrees, LIVE_SUBTREE, archivedSubtree
				),
				Arguments.of(
					"roots by primary key, excluding root", ENTITY_PRODUCT,
					hierarchyWithin(
						REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY), excludingRoot()
					),
					bothSubtrees, LIVE_SUBTREE, archivedSubtree
				),
				Arguments.of(
					"roots by primary key, having both children", ENTITY_PRODUCT,
					hierarchyWithin(
						REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY),
						having(entityPrimaryKeyInSet(SUBTREE_CATEGORY, ARCHIVED_SUBTREE_CATEGORY))
					),
					bothSubtrees, LIVE_SUBTREE, archivedSubtree
				),
				// the archived child is hidden - product 100 goes, products 49-56 stay through the live node 2
				Arguments.of(
					"roots by primary key, excluding the archived child", ENTITY_PRODUCT,
					hierarchyWithin(
						REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY),
						excluding(entityPrimaryKeyInSet(ARCHIVED_SUBTREE_CATEGORY))
					),
					union(LIVE_SUBTREE, ARCHIVED_SUBTREE), LIVE_SUBTREE, none
				),
				// an archived node with live owners only: each scope alone returns nothing, both scopes the live owners
				Arguments.of(
					"archived node with live owners only", ENTITY_PRODUCT,
					hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(ARCHIVED_NODE_WITH_LIVE_OWNERS)),
					range(9, 16), none, none
				),
				// a live node with archived owners only: each scope alone returns nothing, both scopes its archived
				// owners
				Arguments.of(
					"live node with archived owners only", ENTITY_PRODUCT,
					hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(LIVE_NODE_WITH_ARCHIVED_OWNERS)),
					range(65, 72), none, none
				),
				// every node of both trees: each product references one
				Arguments.of(
					"whole hierarchy", ENTITY_PRODUCT,
					hierarchyWithinRoot(REF_CATEGORIES),
					union(ALL_PRODUCTS, new int[]{ARCHIVED_SUBTREE_ONLY_PRODUCT}),
					union(LIVE_SUBTREE, range(17, 48)),
					archivedSubtree
				),
				// the archived child is hidden - product 100 goes, every other product references another node
				Arguments.of(
					"whole hierarchy, excluding the archived child", ENTITY_PRODUCT,
					hierarchyWithinRoot(REF_CATEGORIES, excluding(entityPrimaryKeyInSet(ARCHIVED_SUBTREE_CATEGORY))),
					ALL_PRODUCTS,
					union(LIVE_SUBTREE, range(17, 48)),
					none
				),
				// inside a disjunction the index selection registers no target indexes for the hierarchy filter, so
				// the translator resolves the owners on its own - they must be the same as in a conjunction
				Arguments.of(
					"roots by primary key in or", ENTITY_PRODUCT,
					or(
						hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY)),
						entityPrimaryKeyInSet(MISSING_PRODUCT)
					),
					bothSubtrees, LIVE_SUBTREE, archivedSubtree
				),
				// a live node with archived owners and an archived node with live owners: each scope alone returns
				// nothing, both scopes return the owners of the other scope's node
				Arguments.of(
					"nodes owned across scopes in or", ENTITY_PRODUCT,
					or(
						hierarchyWithin(
							REF_CATEGORIES,
							entityPrimaryKeyInSet(LIVE_NODE_WITH_ARCHIVED_OWNERS, ARCHIVED_NODE_WITH_LIVE_OWNERS)
						),
						entityPrimaryKeyInSet(MISSING_PRODUCT)
					),
					union(range(9, 16), range(65, 72)), none, none
				),
				Arguments.of(
					"whole hierarchy in or", ENTITY_PRODUCT,
					or(hierarchyWithinRoot(REF_CATEGORIES), entityPrimaryKeyInSet(MISSING_PRODUCT)),
					union(ALL_PRODUCTS, new int[]{ARCHIVED_SUBTREE_ONLY_PRODUCT}),
					union(LIVE_SUBTREE, range(17, 48)),
					archivedSubtree
				),
				// categories 1 and 11 reference tag 1, each in its own scope: the parent filter reaches the reference
				// indexes of the scope it is resolved in
				Arguments.of(
					"roots by reference", ENTITY_PRODUCT,
					hierarchyWithin(REF_CATEGORIES, referenceHaving(REF_TAGS, entityPrimaryKeyInSet(TAG))),
					bothSubtrees, LIVE_SUBTREE, archivedSubtree
				),
				// nobody references categories 1 and 11 directly
				Arguments.of(
					"roots by reference, direct relation", ENTITY_PRODUCT,
					hierarchyWithin(
						REF_CATEGORIES, referenceHaving(REF_TAGS, entityPrimaryKeyInSet(TAG)), directRelation()
					),
					none, none, none
				),
				Arguments.of(
					"own roots by primary key", ENTITY_CATEGORY,
					hierarchyWithinSelf(entityPrimaryKeyInSet(ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY)),
					bothTrees,
					new int[]{ROOT_CATEGORY, SUBTREE_CATEGORY},
					new int[]{ARCHIVED_ROOT_CATEGORY, ARCHIVED_SUBTREE_CATEGORY}
				),
				Arguments.of(
					"own roots by unique attribute", ENTITY_CATEGORY,
					hierarchyWithinSelf(attributeEquals(ATTR_CODE, ROOT_CODE)),
					bothTrees,
					new int[]{ROOT_CATEGORY, SUBTREE_CATEGORY},
					new int[]{ARCHIVED_ROOT_CATEGORY, ARCHIVED_SUBTREE_CATEGORY}
				),
				Arguments.of(
					"own whole hierarchy", ENTITY_CATEGORY,
					hierarchyWithinRootSelf(),
					union(liveCategories, archivedCategories), liveCategories, archivedCategories
				),
				// the globally unique value is held by category 1 in the live scope and by category 11 in the archived
				// one: each tree hides the root of its own scope together with its child
				Arguments.of(
					"own whole hierarchy, excluding by global unique attribute", ENTITY_CATEGORY,
					hierarchyWithinRootSelf(excluding(attributeEquals(ATTR_GLOBAL_CODE, ROOT_CODE))),
					new int[]{OTHER_CATEGORY, LIVE_NODE_WITH_ARCHIVED_OWNERS, ARCHIVED_NODE_WITH_LIVE_OWNERS},
					new int[]{OTHER_CATEGORY, LIVE_NODE_WITH_ARCHIVED_OWNERS},
					new int[]{ARCHIVED_NODE_WITH_LIVE_OWNERS}
				),
				// only the root holding the globally unique value in its scope satisfies the filter
				Arguments.of(
					"own whole hierarchy, having by global unique attribute", ENTITY_CATEGORY,
					hierarchyWithinRootSelf(having(attributeEquals(ATTR_GLOBAL_CODE, ROOT_CODE))),
					new int[]{ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY},
					new int[]{ROOT_CATEGORY},
					new int[]{ARCHIVED_ROOT_CATEGORY}
				),
				// the any-child filter resolves the globally unique value in each tree's own scope: only the roots
				// have it in their subtree
				Arguments.of(
					"own whole hierarchy, any having by global unique attribute", ENTITY_CATEGORY,
					hierarchyWithinRootSelf(anyHaving(attributeEquals(ATTR_GLOBAL_CODE, ROOT_CODE))),
					new int[]{ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY},
					new int[]{ROOT_CATEGORY},
					new int[]{ARCHIVED_ROOT_CATEGORY}
				),
				// the referenced counterpart: nodes 3, 4 and 15 stay, so do their owners of both scopes
				Arguments.of(
					"whole hierarchy, excluding by global unique attribute", ENTITY_PRODUCT,
					hierarchyWithinRoot(REF_CATEGORIES, excluding(attributeEquals(ATTR_GLOBAL_CODE, ROOT_CODE))),
					union(range(9, 48), range(57, 72)),
					range(17, 48),
					none
				)
			);
		}

		/**
		 * Checks that the hierarchy filter returns, over both scopes, everything it returns over each scope alone plus
		 * the entities referencing a node of the other scope's tree, and that the single-scope answers are the
		 * expected ones.
		 *
		 * @param label            the row label, used in the test name only
		 * @param entityType       the queried entity type
		 * @param hierarchyFilter  the hierarchy filter
		 * @param expectedBoth     the primary keys expected over both scopes
		 * @param expectedLive     the primary keys expected over LIVE only
		 * @param expectedArchived the primary keys expected over ARCHIVED only
		 * @param session          the session provided by the test extension
		 */
		@DisplayName("Should return what each scope returns alone and the references across scopes")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN_ARCHIVED_SUBTREE_OWNER)
		@ParameterizedTest(name = "{0}")
		@MethodSource("hierarchyFilterRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldReturnWhatEachScopeReturnsAloneAndReferencesAcrossScopes(
			@Nonnull String label,
			@Nonnull String entityType,
			@Nonnull FilterConstraint hierarchyFilter,
			@Nonnull int[] expectedBoth,
			@Nonnull int[] expectedLive,
			@Nonnull int[] expectedArchived,
			@Nonnull EvitaSessionContract session
		) {
			final int[] live = queryPrimaryKeys(session, entityType, LIVE_ONLY, hierarchyFilter);
			final int[] archived = queryPrimaryKeys(session, entityType, ARCHIVED_ONLY, hierarchyFilter);
			assertArrayEquals(expectedLive, live, "the LIVE-only control");
			assertArrayEquals(expectedArchived, archived, "the ARCHIVED-only control");
			final int[] both = queryPrimaryKeys(session, entityType, BOTH_SCOPES, hierarchyFilter);
			assertArrayEquals(expectedBoth, both, "the query over both scopes");
			assertArrayEquals(
				both, union(both, live, archived), "the query over both scopes must contain the controls"
			);
		}

		/**
		 * Checks that the hierarchy filter of each row of {@link #hierarchyFilterRows()} selects the same entities in
		 * every query shape, both over both scopes and over each scope alone: placed in a disjunction with a primary key
		 * nobody has, it selects what it selects on its own, and its negation selects exactly the entities it does not
		 * select. Inside `or` and `not` the index selection registers no target indexes, so these shapes exercise the
		 * owner lookup of the translator itself.
		 *
		 * @param label           the row label, used in the test name only
		 * @param entityType      the queried entity type
		 * @param hierarchyFilter the hierarchy filter
		 * @param session         the session provided by the test extension
		 */
		@DisplayName("Should select the same entities in a disjunction and the rest in a negation")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN_ARCHIVED_SUBTREE_OWNER)
		@ParameterizedTest(name = "{0}")
		@MethodSource("hierarchyFilterRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldSelectSameEntitiesInDisjunctionAndRestInNegation(
			@Nonnull String label,
			@Nonnull String entityType,
			@Nonnull FilterConstraint hierarchyFilter,
			@Nonnull int[] expectedBoth,
			@Nonnull int[] expectedLive,
			@Nonnull int[] expectedArchived,
			@Nonnull EvitaSessionContract session
		) {
			for (final Scope[] scopes : new Scope[][]{BOTH_SCOPES, LIVE_ONLY, ARCHIVED_ONLY}) {
				final String scopeLabel = Arrays.toString(scopes);
				final int[] selected = queryPrimaryKeys(session, entityType, scopes, hierarchyFilter);
				final int[] universe = queryPrimaryKeys(session, entityType, scopes, entityPrimaryKeyInSet(ALL_KEYS));
				assertArrayEquals(
					selected,
					queryPrimaryKeys(
						session, entityType, scopes, or(hierarchyFilter, entityPrimaryKeyInSet(MISSING_PRODUCT))
					),
					() -> "the disjunction over " + scopeLabel
				);
				assertArrayEquals(
					without(universe, selected),
					queryPrimaryKeys(session, entityType, scopes, not(hierarchyFilter)),
					() -> "the negation over " + scopeLabel
				);
			}
		}

		/**
		 * Returns the rows of the own-hierarchy witness inside `inScope(...)`: a label, the scope of the container, the
		 * hierarchy filter of the categories' own hierarchy, and the categories it selects over that scope alone.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> ownHierarchyInScopeRows() {
			return Stream.of(
				// the attribute is filterable in the live scope only, the only scope its tree belongs to
				Arguments.of(
					"having by attribute filterable in live scope only", Scope.LIVE,
					hierarchyWithinRootSelf(having(attributeEquals(ATTR_LIVE_ONLY, true))),
					new int[]{ROOT_CATEGORY, SUBTREE_CATEGORY, OTHER_CATEGORY}
				),
				// the any-child filter reads the same live-only attribute, again in the live tree alone
				Arguments.of(
					"any having by attribute filterable in live scope only", Scope.LIVE,
					hierarchyWithinRootSelf(anyHaving(attributeEquals(ATTR_LIVE_ONLY, true))),
					new int[]{ROOT_CATEGORY, SUBTREE_CATEGORY, OTHER_CATEGORY}
				),
				// the archived tree hides category 11, which holds the globally unique value in the archived scope
				Arguments.of(
					"excluding by global unique attribute", Scope.ARCHIVED,
					hierarchyWithinRootSelf(excluding(attributeEquals(ATTR_GLOBAL_CODE, ROOT_CODE))),
					new int[]{ARCHIVED_NODE_WITH_LIVE_OWNERS}
				)
			);
		}

		/**
		 * Checks that a hierarchy filter of the categories' own hierarchy placed in `inScope(S, ...)` of a query over
		 * both scopes resolves its node filter in the tree of scope `S` only: it selects in scope `S` what it selects
		 * over that scope alone, and the other scope stays unrestricted.
		 *
		 * @param label            the row label, used in the test name only
		 * @param scope            the scope of the `inScope` container
		 * @param hierarchyFilter  the hierarchy filter of the categories' own hierarchy
		 * @param expectedInScope  the categories the filter selects over scope `S` alone
		 * @param session          the session provided by the test extension
		 */
		@DisplayName("Should resolve the node filter of an own hierarchy in inScope in the tree of that scope")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN_ARCHIVED_SUBTREE_OWNER)
		@ParameterizedTest(name = "{0}")
		@MethodSource("ownHierarchyInScopeRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldResolveNodeFilterOfOwnHierarchyInScopeInTreeOfThatScope(
			@Nonnull String label,
			@Nonnull Scope scope,
			@Nonnull FilterConstraint hierarchyFilter,
			@Nonnull int[] expectedInScope,
			@Nonnull EvitaSessionContract session
		) {
			final Scope otherScope = scope == Scope.LIVE ? Scope.ARCHIVED : Scope.LIVE;
			assertArrayEquals(
				expectedInScope,
				queryPrimaryKeys(session, ENTITY_CATEGORY, new Scope[]{scope}, hierarchyFilter),
				"the single-scope control"
			);
			final int[] otherScopeCategories = queryPrimaryKeys(
				session, ENTITY_CATEGORY, new Scope[]{otherScope}, entityPrimaryKeyInSet(ALL_KEYS)
			);
			assertTrue(otherScopeCategories.length > 0, "the other scope must hold categories");
			assertArrayEquals(
				union(expectedInScope, otherScopeCategories),
				queryPrimaryKeys(session, ENTITY_CATEGORY, BOTH_SCOPES, inScope(scope, hierarchyFilter))
			);
		}

		/**
		 * Returns the rows of the `fromNode` witness: the scope of the statistics and whether they describe the queried
		 * entity's own hierarchy.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> fromNodeRows() {
			return Stream.of(Scope.LIVE, Scope.ARCHIVED)
				.flatMap(scope -> Stream.of(false, true).map(self -> Arguments.of(scope, self)));
		}

		/**
		 * Checks that the `fromNode` statistics of each scope, computed in a query over both scopes, equal those of the
		 * same query over that scope alone when the node is selected by a reference of the categories: categories 1 and
		 * 11 both reference tag 1, and the node of a scope is the one in that scope's own tree.
		 *
		 * @param statisticsScope the scope the statistics are computed for
		 * @param self            whether the statistics describe the queried entity's own hierarchy
		 * @param session         the session provided by the test extension
		 */
		@DisplayName("Should anchor the fromNode statistics of a scope at the node of its own tree")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN_ARCHIVED_SUBTREE_OWNER)
		@ParameterizedTest(name = "{0} self: {1}")
		@MethodSource("fromNodeRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldAnchorFromNodeStatisticsOfScopeAtNodeOfItsOwnTree(
			@Nonnull Scope statisticsScope,
			boolean self,
			@Nonnull EvitaSessionContract session
		) {
			final FilterConstraint[] wholeHierarchy = {
				self ? hierarchyWithinRootSelf() : hierarchyWithinRoot(REF_CATEGORIES)
			};
			final HierarchyRequireConstraint requirement = fromNode(
				HIERARCHY_OUTPUT,
				node(filterBy(referenceHaving(REF_TAGS, entityPrimaryKeyInSet(TAG)))),
				statistics(StatisticsType.QUERIED_ENTITY_COUNT)
			);
			final List<String> expected = HierarchyStatisticsPerScope.describe(
				queryStatistics(
					session, new Scope[]{statisticsScope}, statisticsScope, self, wholeHierarchy, requirement
				)
			);
			assertFalse(expected.isEmpty(), "the statistics of the single-scope control must not be empty");
			assertEquals(
				expected,
				HierarchyStatisticsPerScope.describe(
					queryStatistics(session, BOTH_SCOPES, statisticsScope, self, wholeHierarchy, requirement)
				)
			);
		}

		/**
		 * Returns the rows of the statistics witness: the scope of the statistics, whether they describe the queried
		 * entity's own hierarchy, and how the filter selects its parent - by primary key, by the unique attribute, by
		 * a reference of the categories (`referenceHaving`, which discovers its indexes by the processing scopes), or
		 * not at all (`hierarchyWithinRoot`).
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> statisticsRows() {
			return Stream.of(Scope.LIVE, Scope.ARCHIVED)
				.flatMap(
					scope -> Stream.of(false, true)
						.flatMap(
							self -> Stream.of("primary key", "unique attribute", "reference", "whole hierarchy")
								.map(parent -> Arguments.of(scope, self, parent))
						)
				);
		}

		/**
		 * Checks that the `children` statistics of each scope, computed in a query over both scopes, equal those of the
		 * same query over that scope alone - for a referenced hierarchy and for the queried entity's own hierarchy,
		 * with the parent selected by primary key, by the unique attribute, by a reference, or not at all.
		 *
		 * @param statisticsScope the scope the statistics are computed for
		 * @param self            whether the statistics describe the queried entity's own hierarchy
		 * @param parent          how the filter selects its parent
		 * @param session         the session provided by the test extension
		 */
		@DisplayName("Should compute the statistics of a scope like the query over that scope alone")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN_ARCHIVED_SUBTREE_OWNER)
		@ParameterizedTest(name = "{0} self: {1} parent: {2}")
		@MethodSource("statisticsRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldComputeStatisticsOfScopeLikeQueryOverThatScopeAlone(
			@Nonnull Scope statisticsScope,
			boolean self,
			@Nonnull String parent,
			@Nonnull EvitaSessionContract session
		) {
			final FilterConstraint hierarchyFilter = switch (parent) {
				case "primary key" -> hierarchyWithinOf(
					self, entityPrimaryKeyInSet(ROOT_CATEGORY, ARCHIVED_ROOT_CATEGORY)
				);
				case "unique attribute" -> hierarchyWithinOf(self, attributeEquals(ATTR_CODE, ROOT_CODE));
				case "reference" -> hierarchyWithinOf(
					self, referenceHaving(REF_TAGS, entityPrimaryKeyInSet(TAG))
				);
				default -> self ? hierarchyWithinRootSelf() : hierarchyWithinRoot(REF_CATEGORIES);
			};
			final List<String> expected = HierarchyStatisticsPerScope.describe(
				queryChildrenStatistics(session, new Scope[]{statisticsScope}, statisticsScope, self, hierarchyFilter)
			);
			assertFalse(expected.isEmpty(), "the statistics of the single-scope control must not be empty");
			assertEquals(
				expected,
				HierarchyStatisticsPerScope.describe(
					queryChildrenStatistics(session, BOTH_SCOPES, statisticsScope, self, hierarchyFilter)
				)
			);
		}

		/**
		 * Returns the ascending primary keys the passed hierarchy filter selects among the entities of the passed
		 * type in the passed scopes.
		 *
		 * @param session         the session to query
		 * @param entityType      the queried entity type
		 * @param scopes          the scopes of `scope(...)`
		 * @param hierarchyFilter the hierarchy filter
		 * @return the ascending primary keys
		 */
		@Nonnull
		private static int[] queryPrimaryKeys(
			@Nonnull EvitaSessionContract session,
			@Nonnull String entityType,
			@Nonnull Scope[] scopes,
			@Nonnull FilterConstraint hierarchyFilter
		) {
			return sortedPrimaryKeys(
				session.query(
					query(
						collection(entityType),
						filterBy(scope(scopes), hierarchyFilter),
						require(page(1, PRODUCT_COUNT * 2))
					),
					EntityReference.class
				)
			);
		}

		/**
		 * Returns a `hierarchyWithin` of the passed parent: of the queried entity's own hierarchy, or of the
		 * `categories` reference.
		 *
		 * @param self   whether the constraint targets the queried entity's own hierarchy
		 * @param parent the parent filter
		 * @return the constraint
		 */
		@Nonnull
		private static FilterConstraint hierarchyWithinOf(boolean self, @Nonnull FilterConstraint parent) {
			return self ? hierarchyWithinSelf(parent) : hierarchyWithin(REF_CATEGORIES, parent);
		}

		/**
		 * Runs a query filtering by the passed hierarchy filter and returns the `children` statistics it computes in
		 * the passed scope.
		 *
		 * @param session         the session to query
		 * @param scopes          the scopes of `scope(...)`
		 * @param statisticsScope the scope the statistics are computed for
		 * @param self            whether the categories are queried for their own hierarchy, or the products for
		 *                        the `categories` reference
		 * @param hierarchyFilter the hierarchy filter
		 * @return the statistics
		 */
		@Nonnull
		private static List<LevelInfo> queryChildrenStatistics(
			@Nonnull EvitaSessionContract session,
			@Nonnull Scope[] scopes,
			@Nonnull Scope statisticsScope,
			boolean self,
			@Nonnull FilterConstraint hierarchyFilter
		) {
			return queryStatistics(
				session, scopes, statisticsScope, self, new FilterConstraint[]{hierarchyFilter},
				children(HIERARCHY_OUTPUT, statistics(StatisticsType.QUERIED_ENTITY_COUNT))
			);
		}

	}

	/**
	 * An extra result computed without the user filter over a query whose only constraint of one scope is
	 * `inScope(S, userFilter(...))`: removing the user filter from the planned formula leaves that scope unrestricted,
	 * so the extra result counts every entity of the scope - it must never lose the scope as if it selected nothing.
	 */
	@Nested
	@DisplayName("Statistics base of a scope restricted by a user filter alone")
	class UserFilterStrippedFromScope {

		/**
		 * Returns the rows of the own-hierarchy statistics: the label and the requirement named
		 * {@link #HIERARCHY_OUTPUT}, both counting the queried entities without the user filter.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> statisticsWithoutUserFilterRows() {
			return Stream.of(
				Arguments.of(
					"fromRoot",
					fromRoot(
						HIERARCHY_OUTPUT,
						statistics(StatisticsBase.WITHOUT_USER_FILTER, StatisticsType.QUERIED_ENTITY_COUNT)
					)
				),
				Arguments.of(
					"children",
					children(
						HIERARCHY_OUTPUT,
						statistics(StatisticsBase.WITHOUT_USER_FILTER, StatisticsType.QUERIED_ENTITY_COUNT)
					)
				)
			);
		}

		/**
		 * Checks that the live statistics of the categories' own hierarchy, computed without the user filter in a
		 * query over both scopes whose only live constraint is `inScope(LIVE, userFilter(...))`, equal those of the
		 * query over the live scope alone with the same user filter - both count every live category.
		 *
		 * @param label       the row label, used in the test name only
		 * @param requirement the live statistics requirement
		 * @param session     the session provided by the test extension
		 */
		@DisplayName("Should compute own statistics without user filter of a scope restricted by a user filter alone")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}")
		@MethodSource("statisticsWithoutUserFilterRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		void shouldComputeOwnStatisticsWithoutUserFilterOfScopeRestrictedByUserFilterAlone(
			@Nonnull String label,
			@Nonnull HierarchyRequireConstraint requirement,
			@Nonnull EvitaSessionContract session
		) {
			final FilterConstraint rootByCode = userFilter(attributeEquals(ATTR_CODE, ROOT_CODE));
			final List<String> expected = HierarchyStatisticsPerScope.describe(
				queryStatistics(session, LIVE_ONLY, Scope.LIVE, true, new FilterConstraint[]{rootByCode}, requirement)
			);
			assertFalse(expected.isEmpty(), "the statistics of the single-scope control must not be empty");
			assertEquals(
				expected,
				HierarchyStatisticsPerScope.describe(
					queryStatistics(
						session, BOTH_SCOPES, Scope.LIVE, true,
						new FilterConstraint[]{inScope(Scope.LIVE, rootByCode)},
						requirement
					)
				)
			);
		}

		/**
		 * Checks that the brand group of the facet summary of a query over both scopes whose only live constraint is
		 * `inScope(LIVE, userFilter(facetHaving(...)))` counts every branded product of both scopes, the same as the
		 * query without any filter: the group count is computed without the user filter.
		 *
		 * @param session the session provided by the test extension
		 */
		@Test
		@DisplayName("Should count the facet group of a scope restricted by a user filter alone over the whole scope")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@Tag(ENGINE)
		@Tag(QUERY)
		@Tag(FACET)
		void shouldCountFacetGroupOfScopeRestrictedByUserFilterAlone(@Nonnull EvitaSessionContract session) {
			final int expected = queryBrandStatistics(
				session, FacetStatisticsDepth.COUNTS, new FilterConstraint[0], null
			).getCount();
			assertEquals(ALL_PRODUCTS.length - UNBRANDED.length, expected, "every branded product counts in the group");
			assertEquals(
				expected,
				queryBrandStatistics(
					session,
					FacetStatisticsDepth.COUNTS,
					new FilterConstraint[]{
						inScope(Scope.LIVE, userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND))))
					},
					null
				).getCount()
			);
		}

		/**
		 * Returns the rows of the facet relation settings: the label, the relation requirement of the brand reference
		 * (NULL for the default relations) and whether the requirement negates the facet, which flips its count to the
		 * entities without it.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> facetRelationRows() {
			return Stream.of(
				Arguments.of("default", null, false),
				Arguments.of("facetGroupsConjunction", facetGroupsConjunction(REF_BRAND), false),
				Arguments.of(
					"facetGroupsDisjunction between groups",
					facetGroupsDisjunction(REF_BRAND, FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS), false
				),
				Arguments.of("facetGroupsNegation", facetGroupsNegation(REF_BRAND), true),
				Arguments.of(
					"facetGroupsNegation between groups",
					facetGroupsNegation(REF_BRAND, FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS), true
				),
				Arguments.of("facetGroupsExclusivity", facetGroupsExclusivity(REF_BRAND), false)
			);
		}

		/**
		 * Checks that the count of the brand facet in the facet summary of a query over both scopes whose only live
		 * constraint is `inScope(LIVE, userFilter(facetHaving(...)))` is the number of products of both scopes the
		 * query returns with the facet selected (or, for a negated facet, the products it leaves out) - the count drops
		 * the whole user filter, so the archived scope is restricted by the facet as much as the live one, and must
		 * not count its unbranded products.
		 *
		 * @param label    the row label, used in the test name only
		 * @param relation the relation requirement of the brand reference, NULL for the default relations
		 * @param negated  whether the relation requirement negates the facet
		 * @param session  the session provided by the test extension
		 */
		@DisplayName("Should count the facet of a scope restricted by a user filter alone in every scope")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN)
		@ParameterizedTest(name = "{0}")
		@MethodSource("facetRelationRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		@Tag(FACET)
		void shouldCountFacetOfScopeRestrictedByUserFilterAloneInEveryScope(
			@Nonnull String label,
			@Nullable RequireConstraint relation,
			boolean negated,
			@Nonnull EvitaSessionContract session
		) {
			final FilterConstraint brandSelected = userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND)));
			final int selected = queryProductCount(
				session, BOTH_SCOPES, referenceHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND))
			);
			final int expected = negated ? ALL_PRODUCTS.length - selected : selected;
			assertEquals(
				expected, queryBrandFacetCount(session, new FilterConstraint[0], relation, BRAND),
				"the facet count without any user filter must match the query with the facet selected"
			);
			assertEquals(
				expected,
				queryBrandFacetCount(
					session, new FilterConstraint[]{inScope(Scope.LIVE, brandSelected)}, relation, BRAND
				)
			);
		}

	}

	/**
	 * The facet summary of a query over both scopes whose user filter ends up in the scope containers - either because
	 * it sits in `inScope(...)` itself, or because it sits next to an `inScope(...)` and the scope post-processing
	 * copies the whole filter into the container of every scope. A facet COUNT drops the whole user filter and keeps
	 * every mandatory constraint, those inside `inScope(...)` included; a facet IMPACT keeps the user filter and adds
	 * the facet to it in every scope.
	 *
	 * Every test reads two facets of the brand reference, because only the first facet of a relation type gets a
	 * formula of its own: each later one is re-targeted from the formula cached for the first, so a re-targeting that
	 * misses a scope container is visible on the second facet only. The facets are computed in ascending order of
	 * their primary keys, so {@link #BRAND} is always the first and {@link #OTHER_BRAND} the cached one.
	 */
	@Nested
	@DisplayName("Facet summary of a query whose user filter sits in scope containers")
	class FacetSummaryOverScopeContainers {

		/**
		 * Returns the rows of the facet count witness, the cross product of two query shapes and three relation
		 * settings (one per distinct shape of the count formula). Each row is a label, the constraints placed next to
		 * `scope(LIVE, ARCHIVED)`, the same constraints without the user filter, the relation requirement of the brand
		 * reference (NULL for the default relations) and whether the requirement negates the facet.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> facetCountOverScopeContainersRows() {
			final FilterConstraint brandSelected = userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND)));
			final FilterConstraint[] mandatory = {inScope(Scope.LIVE, attributeEquals(ATTR_VISIBLE, true))};
			final List<Arguments> shapes = List.of(
				// the scope post-processing copies the user filter into the containers of both scopes
				Arguments.of(
					"user filter outside inScope",
					new FilterConstraint[]{brandSelected, inScope(Scope.LIVE, attributeEquals(ATTR_VISIBLE, true))}
				),
				// the count must keep the mandatory constraint of the live scope while dropping the user filter
				Arguments.of(
					"user filter next to a mandatory constraint in inScope",
					new FilterConstraint[]{
						inScope(Scope.LIVE, attributeEquals(ATTR_VISIBLE, true), brandSelected)
					}
				)
			);
			final List<Arguments> relations = List.of(
				Arguments.of("default", null, false),
				Arguments.of("facetGroupsConjunction", facetGroupsConjunction(REF_BRAND), false),
				Arguments.of("facetGroupsNegation", facetGroupsNegation(REF_BRAND), true)
			);
			return shapes.stream()
				.flatMap(
					shape -> relations.stream()
						.map(
							relation -> Arguments.of(
								shape.get()[0] + ", " + relation.get()[0],
								shape.get()[1],
								mandatory,
								relation.get()[1],
								relation.get()[2]
							)
						)
				);
		}

		/**
		 * Checks that the count of each brand facet - the first one and the one re-targeted from the cache - is the
		 * number of products of both scopes the mandatory constraints admit with the facet selected (or, for a
		 * negated facet, the products they admit without it): the user filter is dropped in every scope container,
		 * the mandatory constraint of the live scope is kept.
		 *
		 * @param label       the row label, used in the test name only
		 * @param constraints the constraints placed next to `scope(LIVE, ARCHIVED)`
		 * @param mandatory   the same constraints without the user filter
		 * @param relation    the relation requirement of the brand reference, NULL for the default relations
		 * @param negated     whether the relation requirement negates the facet
		 * @param session     the session provided by the test extension
		 */
		@DisplayName("Should count every facet of the reference in every scope, the cached ones too")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN_SECOND_BRAND)
		@ParameterizedTest(name = "{0}")
		@MethodSource("facetCountOverScopeContainersRows")
		@Tag(ENGINE)
		@Tag(QUERY)
		@Tag(FACET)
		void shouldCountEveryFacetInEveryScopeWhenTheUserFilterSitsInScopeContainers(
			@Nonnull String label,
			@Nonnull FilterConstraint[] constraints,
			@Nonnull FilterConstraint[] mandatory,
			@Nullable RequireConstraint relation,
			boolean negated,
			@Nonnull EvitaSessionContract session
		) {
			final int universe = queryProductCount(session, BOTH_SCOPES, mandatory);
			final int[] facets = {BRAND, OTHER_BRAND};
			final int[] expected = new int[facets.length];
			for (int i = 0; i < facets.length; i++) {
				final int selected = queryProductCount(
					session,
					BOTH_SCOPES,
					ArrayUtils.mergeArrays(
						mandatory,
						new FilterConstraint[]{referenceHaving(REF_BRAND, entityPrimaryKeyInSet(facets[i]))}
					)
				);
				expected[i] = negated ? universe - selected : selected;
			}
			assertNotEquals(
				expected[0], expected[1], "the facets must differ, or a facet never re-targeted would go unseen"
			);
			assertTrue(
				queryProductCount(
					session, ARCHIVED_ONLY, referenceHaving(REF_BRAND, entityPrimaryKeyInSet(OTHER_BRAND))
				) > 0,
				"the cached facet must have an owner in the archived scope"
			);
			for (int i = 0; i < facets.length; i++) {
				assertEquals(
					expected[i],
					queryBrandFacetCount(session, constraints, relation, facets[i]),
					"facet " + facets[i]
				);
			}
		}

		/**
		 * Checks that the impact of the brand facet re-targeted from the cache, in a query over both scopes with
		 * `userFilter(facetHaving(brand, 1))` next to an `inScope(LIVE, ...)` constraint, is the number of products the
		 * same query returns with both brands selected, and its difference is the number of products that adds: under
		 * the default relation the impact adds the facet to the user filter in every scope. The impact of the first facet
		 * is checked as a premise - it selects nothing new, so it is the count of the query as it stands.
		 *
		 * @param session the session provided by the test extension
		 */
		@Test
		@DisplayName("Should compute the impact of every facet when the user filter sits outside inScope")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN_SECOND_BRAND)
		@Tag(ENGINE)
		@Tag(QUERY)
		@Tag(FACET)
		void shouldComputeImpactOfEveryFacetWhenUserFilterSitsOutsideInScope(@Nonnull EvitaSessionContract session) {
			final FilterConstraint visibleInLive = inScope(Scope.LIVE, attributeEquals(ATTR_VISIBLE, true));
			final int firstSelected = queryProductCount(
				session, BOTH_SCOPES, userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND))), visibleInLive
			);
			final int bothSelected = queryProductCount(
				session,
				BOTH_SCOPES,
				userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND, OTHER_BRAND))),
				visibleInLive
			);
			assertNotEquals(firstSelected, bothSelected, "selecting the cached facet must change the result");

			final FacetGroupStatistics statistics = queryBrandStatistics(
				session,
				FacetStatisticsDepth.IMPACT,
				new FilterConstraint[]{userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND))), visibleInLive},
				null
			);

			assertEquals(firstSelected, impactOf(statistics, BRAND).matchCount(), "the impact of the first facet");
			assertEquals(
				bothSelected, impactOf(statistics, OTHER_BRAND).matchCount(), "the impact of the cached facet"
			);
			assertEquals(
				bothSelected - firstSelected,
				impactOf(statistics, OTHER_BRAND).difference(),
				"the difference of the cached facet"
			);
		}

		/**
		 * Checks that a cached brand facet that selects nothing on its own makes no sense, in a query over both scopes
		 * whose user filter - `facetHaving(brand, 1)` plus an exclusion of every owner of brand 2 - sits next to an
		 * `inScope(LIVE, ...)` constraint. The facet is listed, because its count drops the user filter, and it adds no
		 * product, so its sense is judged on the facet alone: the selected brand 1 must be left out of the container of
		 * every scope, not only of the first one, or the archived owners of brand 1 make the facet look sensible.
		 *
		 * @param session the session provided by the test extension
		 */
		@Test
		@DisplayName("Should judge the sense of every facet alone in every scope, user filter outside inScope")
		@UseDataSet(IN_SCOPE_REDUCED_INDEX_PLAN_SECOND_BRAND)
		@Tag(ENGINE)
		@Tag(QUERY)
		@Tag(FACET)
		void shouldJudgeSenseOfEveryFacetAloneInEveryScopeWhenUserFilterSitsOutsideInScope(
			@Nonnull EvitaSessionContract session
		) {
			// excludes every owner of the cached brand: the live products 4, 12 and 20, and the archived one
			final FilterConstraint withoutOtherBrand = not(
				entityPrimaryKeyInSet(4, 12, 20, ARCHIVED_OTHER_BRAND_PRODUCT)
			);
			final FilterConstraint visibleInLive = inScope(Scope.LIVE, attributeEquals(ATTR_VISIBLE, true));
			assertEquals(
				0,
				queryProductCount(
					session,
					BOTH_SCOPES,
					userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(OTHER_BRAND)), withoutOtherBrand),
					visibleInLive
				),
				"the cached facet alone must select no product"
			);

			final FacetGroupStatistics statistics = queryBrandStatistics(
				session,
				FacetStatisticsDepth.IMPACT,
				new FilterConstraint[]{
					userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND)), withoutOtherBrand), visibleInLive
				},
				null
			);

			final RequestImpact impact = impactOf(statistics, OTHER_BRAND);
			assertEquals(0, impact.difference(), "the cached facet must add no product");
			assertTrue(impact.matchCount() > 0, "the query must return products of brand 1");
			assertTrue(
				queryProductCount(
					session,
					ARCHIVED_ONLY,
					userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND)), withoutOtherBrand)
				) > 0,
				"brand 1 must have an owner in the archived scope"
			);
			assertFalse(impact.hasSense(), "the cached facet selects nothing on its own");
		}

		/**
		 * Returns the impact of the passed brand facet.
		 *
		 * @param statistics the statistics of the brand reference, computed with the impact
		 * @param facetId    the primary key of the brand
		 * @return the impact of the facet
		 */
		@Nonnull
		private static RequestImpact impactOf(@Nonnull FacetGroupStatistics statistics, int facetId) {
			final RequestImpact impact = brandFacet(statistics, facetId).getImpact();
			assertNotNull(impact, "brand " + facetId + " must have an impact");
			return impact;
		}

	}

	/**
	 * Returns the number of products a query over the passed scopes with the passed filter constraints returns.
	 *
	 * @param session     the session to query
	 * @param scopes      the scopes of `scope(...)`
	 * @param constraints the constraints placed next to `scope(...)`
	 * @return the total record count
	 */
	private static int queryProductCount(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint... constraints
	) {
		return session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(ArrayUtils.mergeArrays(new FilterConstraint[]{scope(scopes)}, constraints)),
				require(page(1, 0))
			),
			EntityReference.class
		).getTotalRecordCount();
	}

	/**
	 * Runs a product query over both scopes with the passed filter constraints and returns the statistics of the
	 * brand reference in its facet summary.
	 *
	 * @param session     the session to query
	 * @param depth       the depth of the facet summary
	 * @param constraints the constraints placed next to `scope(LIVE, ARCHIVED)`
	 * @param relation    the relation requirement of the brand reference, NULL for the default relations
	 * @return the statistics of the brand reference
	 */
	@Nonnull
	private static FacetGroupStatistics queryBrandStatistics(
		@Nonnull EvitaSessionContract session,
		@Nonnull FacetStatisticsDepth depth,
		@Nonnull FilterConstraint[] constraints,
		@Nullable RequireConstraint relation
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(ArrayUtils.mergeArrays(new FilterConstraint[]{scope(BOTH_SCOPES)}, constraints)),
				require(
					page(1, PRODUCT_COUNT),
					facetSummaryOfReference(REF_BRAND, depth),
					relation
				)
			),
			EntityReference.class
		);
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);
		assertNotNull(facetSummary, "the facet summary must be computed");
		final FacetGroupStatistics brandStatistics = facetSummary.getFacetGroupStatistics(REF_BRAND);
		assertNotNull(brandStatistics, "the brand reference must have facet statistics");
		return brandStatistics;
	}

	/**
	 * Runs a product query over both scopes with the passed filter constraints and returns the count of the passed
	 * brand facet in its facet summary.
	 *
	 * @param session     the session to query
	 * @param constraints the constraints placed next to `scope(LIVE, ARCHIVED)`
	 * @param relation    the relation requirement of the brand reference, NULL for the default relations
	 * @param facetId     the primary key of the brand
	 * @return the count of the brand facet
	 */
	private static int queryBrandFacetCount(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint[] constraints,
		@Nullable RequireConstraint relation,
		int facetId
	) {
		return brandFacet(
			queryBrandStatistics(session, FacetStatisticsDepth.COUNTS, constraints, relation), facetId
		).getCount();
	}

	/**
	 * Returns the statistics of the passed brand facet.
	 *
	 * @param statistics the statistics of the brand reference
	 * @param facetId    the primary key of the brand
	 * @return the statistics of the facet
	 */
	@Nonnull
	private static FacetStatistics brandFacet(@Nonnull FacetGroupStatistics statistics, int facetId) {
		final FacetStatistics facetStatistics = statistics.getFacetStatistics(facetId);
		assertNotNull(facetStatistics, "brand " + facetId + " must have facet statistics");
		return facetStatistics;
	}

	/**
	 * Runs a query filtering by the passed constraints and returns the statistics the passed requirement computes in
	 * the passed scope.
	 *
	 * @param session         the session to query
	 * @param scopes          the scopes of `scope(...)`
	 * @param statisticsScope the scope the statistics are computed for
	 * @param self            whether the categories are queried for their own hierarchy, or the products for the
	 *                        `categories` reference
	 * @param constraints     the constraints placed next to `scope(...)`
	 * @param requirement     the statistics requirement, named {@link #HIERARCHY_OUTPUT}
	 * @return the statistics
	 */
	@Nonnull
	private static List<LevelInfo> queryStatistics(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope[] scopes,
		@Nonnull Scope statisticsScope,
		boolean self,
		@Nonnull FilterConstraint[] constraints,
		@Nonnull HierarchyRequireConstraint requirement
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(self ? ENTITY_CATEGORY : ENTITY_PRODUCT),
				filterBy(ArrayUtils.mergeArrays(new FilterConstraint[]{scope(scopes)}, constraints)),
				require(
					page(1, PRODUCT_COUNT * 2),
					inScope(
						statisticsScope,
						self ? hierarchyOfSelf(requirement) : hierarchyOfReference(REF_CATEGORIES, requirement)
					)
				)
			),
			EntityReference.class
		);
		final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
		assertNotNull(hierarchy, "the hierarchy statistics must be computed");
		return self ?
			hierarchy.getSelfHierarchy(HIERARCHY_OUTPUT) :
			hierarchy.getReferenceHierarchy(REF_CATEGORIES, HIERARCHY_OUTPUT);
	}

	/**
	 * Runs a query over both scopes with the passed filter constraints and returns the live hierarchy statistics of
	 * the `categories` reference computed by the passed requirement.
	 *
	 * @param session     the session to query
	 * @param constraints the constraints placed next to `scope(LIVE, ARCHIVED)`
	 * @param requirement the statistics requirement, named {@link #HIERARCHY_OUTPUT}
	 * @return the live statistics
	 */
	@Nonnull
	private static List<LevelInfo> queryLiveHierarchyStatistics(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint[] constraints,
		@Nonnull HierarchyRequireConstraint requirement
	) {
		return liveStatistics(queryLiveHierarchy(session, BOTH_SCOPES, constraints, requirement));
	}

	/**
	 * Runs a query over the passed scopes with the passed filter constraints, requesting the live hierarchy
	 * statistics of the `categories` reference computed by the passed requirement.
	 *
	 * @param session     the session to query
	 * @param scopes      the scopes of `scope(...)`
	 * @param constraints the constraints placed next to `scope(...)`
	 * @param requirement the statistics requirement, named {@link #HIERARCHY_OUTPUT}
	 * @return the response
	 */
	@Nonnull
	private static EvitaResponse<EntityReference> queryLiveHierarchy(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint[] constraints,
		@Nonnull HierarchyRequireConstraint requirement
	) {
		return session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(ArrayUtils.mergeArrays(new FilterConstraint[]{scope(scopes)}, constraints)),
				require(
					page(1, PRODUCT_COUNT),
					inScope(Scope.LIVE, hierarchyOfReference(REF_CATEGORIES, requirement))
				)
			),
			EntityReference.class
		);
	}

	/**
	 * Returns the live hierarchy statistics of the `categories` reference named {@link #HIERARCHY_OUTPUT} that the
	 * response carries.
	 *
	 * @param response the response of a query requesting the live statistics
	 * @return the live statistics
	 */
	@Nonnull
	private static List<LevelInfo> liveStatistics(@Nonnull EvitaResponse<EntityReference> response) {
		final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
		assertNotNull(hierarchy, "the hierarchy statistics must be computed");
		return hierarchy.getReferenceHierarchy(REF_CATEGORIES, HIERARCHY_OUTPUT);
	}

	/**
	 * Returns the primary keys the response carries, ascending.
	 *
	 * @param response the response
	 * @return the ascending primary keys
	 */
	@Nonnull
	private static int[] sortedPrimaryKeys(@Nonnull EvitaResponse<EntityReference> response) {
		return response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray();
	}

	/**
	 * Creates product `pk` as the fixture table on the class describes it: the categories and tag follow its group,
	 * and its position in the group decides which outer constraint it fails.
	 *
	 * @param session the session writing the fixture
	 * @param pk      the product primary key
	 * @return the product builder, ready to be upserted
	 */
	@Nonnull
	private static EntityBuilder createProduct(@Nonnull EvitaSessionContract session, int pk) {
		int group = GROUP_STARTS.length - 1;
		while (GROUP_STARTS[group] > pk) {
			group--;
		}
		final int position = pk - GROUP_STARTS[group];
		final int[] categories = GROUP_CATEGORIES[group];
		final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, pk)
			.setAttribute(ATTR_NAME, position == POSITION_WITHOUT_LOCALE ? OTHER_LOCALE : LOCALE, "product " + pk)
			.setAttribute(ATTR_VISIBLE, position != POSITION_INVISIBLE);
		for (final int category : categories) {
			product.setReference(REF_CATEGORIES, category);
		}
		if (position != POSITION_UNPRICED) {
			product.setPrice(pk, PRICE_LIST, CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true);
		}
		if (position != POSITION_UNBRANDED) {
			product.setReference(REF_BRAND, BRAND);
		}
		if (categories[0] == SUBTREE_CATEGORY) {
			product.setReference(REF_TAGS, TAG);
		}
		return product;
	}

	/**
	 * Runs the query, asserts its answer and returns it together with what the planning telemetry says.
	 *
	 * @param session            the session to query
	 * @param scopes             the scopes of `scope(...)`
	 * @param constraints        the constraints placed next to `scope(...)`
	 * @param verifyAlternatives whether every eligible plan is executed and compared
	 * @param expected           the expected primary keys, ascending
	 * @return the query outcome
	 */
	@Nonnull
	private static QueryOutcome assertQueryReturns(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint[] constraints,
		boolean verifyAlternatives,
		@Nonnull int[] expected
	) {
		final QueryOutcome outcome = assertDoesNotThrow(
			() -> runQuery(session, scopes, constraints, verifyAlternatives),
			"the alternative plans of the filter must agree"
		);
		assertArrayEquals(
			expected, outcome.primaryKeys(),
			() -> "expected " + Arrays.toString(expected) + " but got " + Arrays.toString(outcome.primaryKeys())
				+ "; selected: " + outcome.selectedIndex()
				+ "; REFERENCED_ENTITY alternatives: " + outcome.reducedIndexAlternatives()
		);
		return outcome;
	}

	/**
	 * Runs the query with telemetry and collects the sorted primary keys, the selected index and the
	 * `REFERENCED_ENTITY` alternatives.
	 *
	 * @param session            the session to query
	 * @param scopes             the scopes of `scope(...)`
	 * @param constraints        the constraints placed next to `scope(...)`
	 * @param verifyAlternatives whether every eligible plan is executed and compared
	 * @return the query outcome
	 */
	@Nonnull
	private static QueryOutcome runQuery(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint[] constraints,
		boolean verifyAlternatives
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(ArrayUtils.mergeArrays(new FilterConstraint[]{scope(scopes)}, constraints)),
				require(
					page(1, PRODUCT_COUNT),
					queryTelemetry(),
					verifyAlternatives ? debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS) : null
				)
			),
			EntityReference.class
		);
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		final List<String> alternatives = new ArrayList<>();
		final List<String> selected = new ArrayList<>();
		if (telemetry != null) {
			collectPlanningArguments(telemetry, alternatives, selected);
		}
		return new QueryOutcome(
			response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray(),
			selected.isEmpty() ? null : selected.getFirst(),
			alternatives
		);
	}

	/**
	 * Walks a telemetry tree depth-first, collecting the descriptions of `REFERENCED_ENTITY` alternatives and of
	 * the selected index (the outer planning step comes first).
	 *
	 * @param node         the node to walk
	 * @param alternatives sink of the `REFERENCED_ENTITY` alternative descriptions
	 * @param selected     sink of the selected-index descriptions
	 */
	private static void collectPlanningArguments(
		@Nonnull QueryTelemetry node,
		@Nonnull List<String> alternatives,
		@Nonnull List<String> selected
	) {
		for (final String argument : node.getArguments()) {
			if (argument.startsWith("Selected index:")) {
				selected.add(argument);
			} else if (argument.startsWith("Index type: REFERENCED_ENTITY")) {
				alternatives.add(argument);
			}
		}
		for (final QueryTelemetry step : node.getSteps()) {
			collectPlanningArguments(step, alternatives, selected);
		}
	}

	/**
	 * Builds `inScope(LIVE, hierarchyWithin(categories, <node>))`.
	 *
	 * @param node the parent category
	 * @return the constraint
	 */
	@Nonnull
	private static FilterConstraint liveHierarchy(int node) {
		return inScope(Scope.LIVE, hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(node)));
	}

	/**
	 * Builds `inScope(ARCHIVED, hierarchyWithin(categories, <node>))`.
	 *
	 * @param node the parent category
	 * @return the constraint
	 */
	@Nonnull
	private static FilterConstraint archivedHierarchy(int node) {
		return inScope(Scope.ARCHIVED, hierarchyWithin(REF_CATEGORIES, entityPrimaryKeyInSet(node)));
	}

	/**
	 * Builds a result row: a label, the constraints and the expected primary keys.
	 *
	 * @param label       the row label
	 * @param expected    the expected primary keys, ascending
	 * @param constraints the constraints placed next to `scope(...)`
	 * @return the row
	 */
	@Nonnull
	private static Arguments row(
		@Nonnull String label,
		@Nonnull int[] expected,
		@Nonnull FilterConstraint... constraints
	) {
		return Arguments.of(label, constraints, expected);
	}

	/**
	 * Expands a result row into its two arms - without a debug mode and with `VERIFY_ALTERNATIVE_INDEX_RESULTS`.
	 *
	 * @param row the row to expand
	 * @return the two arms
	 */
	@Nonnull
	private static Stream<Arguments> withBothVerifyArms(@Nonnull Arguments row) {
		return Stream.of(false, true)
			.map(verify -> Arguments.of(row.get()[0], row.get()[1], row.get()[2], verify));
	}

	/**
	 * Returns the primary keys from `from` to `to`, both inclusive.
	 *
	 * @param from the first primary key
	 * @param to   the last primary key
	 * @return the ascending range
	 */
	@Nonnull
	private static int[] range(int from, int to) {
		return IntStream.rangeClosed(from, to).toArray();
	}

	/**
	 * Returns the ascending union of the sets.
	 *
	 * @param sets the sets to unite
	 * @return the ascending union without duplicates
	 */
	@Nonnull
	private static int[] union(@Nonnull int[]... sets) {
		return Arrays.stream(sets).flatMapToInt(Arrays::stream).distinct().sorted().toArray();
	}

	/**
	 * Returns the set without the excluded primary keys.
	 *
	 * @param set      the ascending set
	 * @param excluded the primary keys to drop
	 * @return the ascending remainder
	 */
	@Nonnull
	private static int[] without(@Nonnull int[] set, @Nonnull int[] excluded) {
		return Arrays.stream(set).filter(pk -> Arrays.stream(excluded).noneMatch(it -> it == pk)).toArray();
	}

	/**
	 * What a query returned and how it was planned.
	 *
	 * @param primaryKeys              the returned primary keys, ascending
	 * @param selectedIndex            the telemetry's description of the selected index, NULL when none was reported
	 *                                 (the query was short-circuited before any plan was built)
	 * @param reducedIndexAlternatives the telemetry's descriptions of the `REFERENCED_ENTITY` alternatives
	 */
	private record QueryOutcome(
		@Nonnull int[] primaryKeys,
		@Nullable String selectedIndex,
		@Nonnull List<String> reducedIndexAlternatives
	) {
	}

}
