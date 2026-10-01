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
import io.evitadb.api.query.filter.FilterInScope;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
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
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.children;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfReference;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithin;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinRoot;
import static io.evitadb.api.query.QueryConstraints.inScope;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.priceInCurrency;
import static io.evitadb.api.query.QueryConstraints.priceInPriceLists;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.statistics;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins queries over two scopes whose reference constraint is restricted to one of them by `inScope(...)` (#1681).
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
 * scope it is evaluated in** (`HierarchyWithinTranslator#createFormulaFromHierarchyIndex`, the live one first when
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
 * Every expectation is therefore "the products the scoped reference constraint admits, minus one row of the
 * table above", which is how the rows spell it: `inScope(LIVE, hierarchyWithin(categories, 1))` admits 1-8 and every
 * archived product, `inScope(ARCHIVED, hierarchyWithin(categories, 11))` admits every live product and 49-56. Because live **and** archived products fail every outer constraint,
 * a fix that appends the other scope's owners wholesale, or that answers the other scope without applying the outer
 * constraint, returns a product the row does not expect. Because archived products outside the subtree pass the
 * outer constraints, a symmetric `inScope(ARCHIVED, ...)` row catches a fix that admits every archived product.
 *
 * The cardinalities keep the narrowed plan under the cardinality limit: 8 subtree owners per scope against half the
 * queried global indexes (36 for both scopes, 24 for live only, 12 for archived only).
 *
 * ## What the result rows cannot see
 *
 * A fix that leaks the other scope's owners into the **narrowed** branch (option B of the design) is invisible in
 * a result set: `InScopeFormulaPostProcessor` answers the other scope's branch with every entity of that scope that
 * passes the outer constraints, so the leaked owners are expected anyway; and whenever that branch is restricted by
 * an `inScope` container of its own, the narrowed branch is intersected with the narrowed scope's superset, which
 * drops them again. The telemetry-pinned eligibility rows and the design review are what guard against it.
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
	private static final String ENTITY_CATEGORY = "inScopePlanCategory";
	private static final String ENTITY_BRAND = "inScopePlanBrand";
	private static final String ENTITY_TAG = "inScopePlanTag";
	private static final String ENTITY_PRODUCT = "inScopePlanProduct";
	private static final String REF_CATEGORIES = "categories";
	private static final String REF_BRAND = "brand";
	private static final String REF_TAGS = "tags";
	private static final String ATTR_NAME = "name";
	private static final String ATTR_VISIBLE = "visible";
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
	private static final int TAG = 1;
	private static final int PRODUCT_COUNT = 72;
	/**
	 * Output name of the hierarchy statistics computed by the statistics witness.
	 */
	private static final String HIERARCHY_OUTPUT = "children";
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
	 * Builds the fixture described on the class: the entities are written live, then categories 11, 12 and 15 and
	 * products {@link #FIRST_ARCHIVED}-{@link #PRODUCT_COUNT} are archived.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = IN_SCOPE_REDUCED_INDEX_PLAN, destroyAfterClass = true)
	void setUp(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchyIndexedInScope(BOTH_SCOPES)
					.withLocale(LOCALE)
					.withAttribute(ATTR_NAME, String.class, thatIs -> thatIs.localized())
					.updateVia(session);
				session.defineEntitySchema(ENTITY_BRAND)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(ENTITY_TAG)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
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
					session.createNewEntity(ENTITY_CATEGORY, ROOT_CATEGORY).setAttribute(ATTR_NAME, LOCALE, "root")
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_CATEGORY, SUBTREE_CATEGORY)
						.setParent(ROOT_CATEGORY)
						.setAttribute(ATTR_NAME, LOCALE, "child")
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_CATEGORY, OTHER_CATEGORY).setAttribute(ATTR_NAME, LOCALE, "other")
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_CATEGORY, LIVE_NODE_WITH_ARCHIVED_OWNERS)
						.setAttribute(ATTR_NAME, LOCALE, "archived owners only")
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_CATEGORY, ARCHIVED_ROOT_CATEGORY)
						.setAttribute(ATTR_NAME, LOCALE, "archived root")
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
				session.upsertEntity(session.createNewEntity(ENTITY_TAG, TAG));
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
	}

	/**
	 * Returns the rows placing `inScope(S, <constraint on the subtree of category 1>)` next to an outer constraint,
	 * where the narrowed plan is non-empty. Each row is a label, the constraints placed next to
	 * `scope(LIVE, ARCHIVED)`, and the expected primary keys; each runs without a debug mode and with
	 * `VERIFY_ALTERNATIVE_INDEX_RESULTS`.
	 *
	 * The verify arm (plans as the query telemetry names them): the narrowed `REFERENCED_ENTITY` plan is ineligible
	 * and the both-scope candidates some rows register besides - `referenceHaving(brand)` and `hierarchyWithinRoot` -
	 * are `HIGH_CARDINALITY`, so every row runs the global plan (`Live index: GLOBAL, Archived index: GLOBAL`) alone
	 * and the arm passes vacuously. It stays as the tripwire: were the narrowed plan made eligible without being made
	 * correct, the rows whose outer constraint reads the plan's indexes would disagree with the global plan and the
	 * arm would throw `InconsistentResultsException`.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> narrowedReferenceConstraintRows() {
		final Integer[] allProducts = IntStream.rangeClosed(1, PRODUCT_COUNT).boxed().toArray(Integer[]::new);
		return Stream.of(
				// --- inScope(LIVE, hierarchyWithin(categories, 1)) ---
				// nothing outside the container reads the plan's indexes; catches a planner that stops honouring the
				// container (every live product would come back)
				row("LIVE hierarchy alone",
					LIVE_NARROWED, liveHierarchy(ROOT_CATEGORY)),
				// the constant is matched per scope by SuperSetMatchingPostProcessor; catches the same over-reach as
				// above
				row("LIVE hierarchy + primary keys",
					LIVE_NARROWED, entityPrimaryKeyInSet(allProducts), liveHierarchy(ROOT_CATEGORY)),
				// referenceHaving(brand) answers from its own both-scope index set; catches a planner that appends
				// every archived owner (52, 60, 68 have no brand)
				row("LIVE hierarchy + referenceHaving(brand)",
					without(LIVE_NARROWED, UNBRANDED), referenceHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND)),
					liveHierarchy(ROOT_CATEGORY)),
				// every product references a category of the live tree; catches a lookup that resolves the hierarchy
				// candidate by reference name instead of constraint identity - the both-scope
				// hierarchyWithinRoot candidate would then admit every live product
				row("LIVE hierarchy + hierarchyWithinRoot",
					LIVE_NARROWED, hierarchyWithinRoot(REF_CATEGORIES), liveHierarchy(ROOT_CATEGORY)),
				// the locale must not be read from live partitions only; catches "append all archived" (49, 57, 65
				// lack `en`) and dropping the locale in the archived branch
				row("LIVE hierarchy + locale",
					without(LIVE_NARROWED, WITHOUT_LOCALE), entityLocaleEquals(LOCALE), liveHierarchy(ROOT_CATEGORY)),
				// the attribute must not be read from live partitions only; catches "append all archived" (50, 58, 66 invisible)
				row("LIVE hierarchy + attribute",
					without(LIVE_NARROWED, INVISIBLE), attributeEquals(ATTR_VISIBLE, true),
					liveHierarchy(ROOT_CATEGORY)),
				// the price must not be read from live partitions only; catches "append all archived" (51, 59, 67 unpriced)
				row("LIVE hierarchy + price",
					without(LIVE_NARROWED, UNPRICED), priceInCurrency(CZK), priceInPriceLists(PRICE_LIST),
					liveHierarchy(ROOT_CATEGORY)),
				// the facet must not be read from live partitions only; catches "append all archived" (52, 60, 68 unbranded)
				row("LIVE hierarchy + facet",
					without(LIVE_NARROWED, UNBRANDED), userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND))),
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
				// hierarchyWithinRoot shares the hierarchy producer: the live tree (1-4) is referenced by live 1-8 and
				// 17-48 (9-16 reference archived category 15 only); catches a guard applied to hierarchyWithin only
				row("LIVE hierarchyWithinRoot + locale",
					without(union(LIVE_SUBTREE, range(17, 48), ARCHIVED_ALL), WITHOUT_LOCALE), entityLocaleEquals(LOCALE),
					inScope(Scope.LIVE, hierarchyWithinRoot(REF_CATEGORIES))),

				// --- inScope(ARCHIVED, hierarchyWithin(categories, 11)) - the symmetric shape, on the archived tree ---
				// catches a planner that admits every archived product (57-72 pass all outer constraints and must not
				// appear) or that stops honouring the container
				row("ARCHIVED hierarchy alone",
					ARCHIVED_NARROWED, archivedHierarchy(ARCHIVED_ROOT_CATEGORY)),
				// a locale read from archived partitions only would lose every live product; catches a guard keyed to
				// Scope.LIVE, "append all live" (1, 9, 17 lack `en`) and "append all archived" (58-64 would appear)
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
					without(ARCHIVED_NARROWED, UNBRANDED), userFilter(facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND))),
					archivedHierarchy(ARCHIVED_ROOT_CATEGORY)),
				// a LIVE-scoped constraint next to the ARCHIVED-narrowed plan; catches a planner that ignores the
				// LIVE container (2, 10, 18 must go, archived invisible 50 stays)
				row("ARCHIVED hierarchy + inScope(LIVE, attribute)",
					union(without(LIVE_ALL, INVISIBLE), ARCHIVED_SUBTREE),
					inScope(Scope.LIVE, attributeEquals(ATTR_VISIBLE, true)), archivedHierarchy(ARCHIVED_ROOT_CATEGORY)),
				// the referenceHaving producer in the symmetric scope; catches a guard applied to one producer or one
				// scope only
				row("ARCHIVED referenceHaving + locale",
					without(ARCHIVED_NARROWED, WITHOUT_LOCALE), entityLocaleEquals(LOCALE),
					inScope(Scope.ARCHIVED, referenceHaving(REF_CATEGORIES, entityPrimaryKeyInSet(ARCHIVED_SUBTREE_CATEGORY))))
			)
			.flatMap(InScopeReducedIndexPlanFunctionalTest::withBothVerifyArms);
	}

	/**
	 * Returns the rows where the narrowed plan is **empty** - the second shape of #1681. Each row is a label, the
	 * constraints placed next to `scope(LIVE, ARCHIVED)`, and the expected primary keys; each runs without a debug
	 * mode and with `VERIFY_ALTERNATIVE_INDEX_RESULTS`.
	 *
	 * Two producers of an empty candidate exist in the hierarchy branch of index selection: the `TargetIndexes.EMPTY`
	 * sentinel when no node of the subtree exists in the narrowed scope's hierarchy (a missing category, or a node of
	 * the other scope's tree), and a candidate with no index when the nodes exist but no owner of the narrowed scope
	 * references them (live category 4 has archived owners only, archived category 15 live owners only).
	 * `IndexSelectionResult#isEmpty` treats either as "the whole query matches nothing", so a registered empty narrowed
	 * candidate would return `[]` before any plan is built; neither producer registers one.
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
					without(ARCHIVED_ALL, WITHOUT_LOCALE), entityLocaleEquals(LOCALE), liveHierarchy(MISSING_CATEGORY)),
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
					without(LIVE_ALL, INVISIBLE), attributeEquals(ATTR_VISIBLE, true), archivedHierarchy(ROOT_CATEGORY)),
				// symmetric 0-index candidate + outer constraint; catches a guard keyed to Scope.LIVE and
				// "append all live" (1, 9, 17)
				row("ARCHIVED hierarchy without archived owners + locale",
					without(LIVE_ALL, WITHOUT_LOCALE), entityLocaleEquals(LOCALE),
					archivedHierarchy(ARCHIVED_NODE_WITH_LIVE_OWNERS)),
				// the referenceHaving producer skips an empty narrowed candidate; catches turning that skip into
				// "registered but ineligible" without changing the empty-result short-circuit
				row("LIVE referenceHaving without live owners + locale",
					without(ARCHIVED_ALL, WITHOUT_LOCALE), entityLocaleEquals(LOCALE),
					inScope(Scope.LIVE, referenceHaving(REF_CATEGORIES, entityPrimaryKeyInSet(LIVE_NODE_WITH_ARCHIVED_OWNERS))))
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
						inScope(Scope.LIVE, referenceHaving(REF_CATEGORIES, entityPrimaryKeyInSet(SUBTREE_CATEGORY)))
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
	 * Returns the rows of the reused-instance witness: the same `referenceHaving` placed in `inScope(LIVE, ...)` and
	 * in `inScope(ARCHIVED, ...)`, either as one Java object or as two equal objects. Each row is a label, the
	 * reference name, and whether the instance is reused.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> reusedConstraintInstanceRows() {
		return Stream.of(
			// tags are not partitioned, so every REFERENCED_ENTITY candidate is ineligible and the global plan
			// answers - plan choice is out of play; catches an identity-only lookup of the candidate (#1686)
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
	 * Returns the rows of the nested-`inScope` rejection: a label, the scope of the outer container and the scope of
	 * the container nested in it.
	 *
	 * `inScope(S, P)` applies `P` only when entities of scope `S` are searched. Nesting two opposite containers would
	 * apply `P` when searching LIVE **and** ARCHIVED at once - never - and nesting two equal containers is redundant,
	 * so both are refused when the outer container is created.
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
	 * Checks that a reference constraint narrowed to one scope by `inScope(...)` keeps every product of the other
	 * scope that satisfies the outer constraints, whichever plan answers it.
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
		assertQueryReturns(session, BOTH_SCOPES, constraints, verifyAlternatives, expected);
	}

	/**
	 * Checks that a narrowed reference constraint that matches nothing in its own scope does not empty the query - the
	 * other scope's products that satisfy the outer constraints must come back.
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
		assertQueryReturns(session, BOTH_SCOPES, constraints, verifyAlternatives, expected);
	}

	/**
	 * Guards the fix against over-correction: when the `REFERENCED_ENTITY` plan covers every queried scope, it must
	 * stay registered, eligible and - in this fixture, where it is the cheaper one - selected. The answer is checked
	 * too, so a guard cannot pass on a wrong result.
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
			"#1681: the REFERENCED_ENTITY alternative must stay registered"
		);
		for (final String alternative : outcome.reducedIndexAlternatives()) {
			assertFalse(
				alternative.contains("not eligible"),
				() -> "#1681: the REFERENCED_ENTITY alternative covers every queried scope and must stay eligible, "
					+ "got: " + alternative
			);
		}
		assertNotNull(outcome.selectedIndex(), "#1681: the telemetry must name the selected index");
		assertTrue(
			outcome.selectedIndex().contains("REFERENCED_ENTITY"),
			() -> "#1681: the cheaper REFERENCED_ENTITY plan must be selected, got: " + outcome.selectedIndex()
		);
	}

	/**
	 * Checks that one `referenceHaving` instance placed both in `inScope(LIVE, ...)` and in `inScope(ARCHIVED, ...)`
	 * is answered per scope (#1686) - plan choice is not involved. Index selection registers one candidate per
	 * container, and the translator must look up the candidate built for the scope it is translating
	 * (`FilterByVisitor#findTargetIndexSet` matches the instance together with the processing scopes); matching the
	 * instance alone would hand the LIVE candidate to the ARCHIVED translation and lose the archived owners of tag 1 /
	 * category 2. Two equal but distinct instances are the control.
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
			() -> "#1681: the rejection must name both scopes, got: " + exception.getMessage()
		);
	}

	/**
	 * Control of {@link #shouldRejectInScopeNestedInAnotherInScope}: `inScope` containers placed side by side, and an
	 * `inScope` restricting another entity inside an `inScope`, are accepted and answered.
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
	 * Checks that a query with nested `inScope` survives the storage round trip unchanged - a traffic recording made
	 * before the nesting was refused deserializes every stored query up front, and one such query must not make the
	 * whole recording unreadable - and that executing the read-back query is refused like any other.
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
	 * Checks that hierarchy statistics computed for one scope use the hierarchy roots resolved in that scope when one
	 * `hierarchyWithin` instance is placed both in `inScope(LIVE, ...)` and in `inScope(ARCHIVED, ...)` (#1686).
	 *
	 * The parent filter `entityPrimaryKeyInSet(1, 11)` matches root 1 in the live tree and root 11 in the archived
	 * tree. The archived `children` statistics must describe the archived root 11 - requested, with its 8 archived
	 * owners 49-56 below it - rather than look for the live root 1, which the archived tree does not contain and which
	 * would leave the statistics empty.
	 *
	 * @param reused  whether both containers hold the same instance
	 * @param session the session provided by the test extension
	 */
	@DisplayName("Should compute hierarchy statistics of a scope from the roots resolved in that scope")
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
			"#1681: the alternative plans of the filter must agree"
		);
		assertArrayEquals(
			expected, outcome.primaryKeys(),
			() -> "#1681: expected " + Arrays.toString(expected) + " but got " + Arrays.toString(outcome.primaryKeys())
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
	private static Arguments row(@Nonnull String label, @Nonnull int[] expected, @Nonnull FilterConstraint... constraints) {
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
