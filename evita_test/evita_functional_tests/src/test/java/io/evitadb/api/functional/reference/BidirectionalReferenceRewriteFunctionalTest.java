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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.Evita;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.AssertionUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeInSet;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.or;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins **which constraint shapes the bidirectional-reference rewrite takes over, and which it must leave alone**.
 *
 * `BidirectionalReferenceRewriter` re-expresses a `referenceHaving` against the other end of a reflected reference
 * pair: instead of one reduced index per *referenced entity* that survives the filter, the very same question is
 * answered by visiting one reduced index per *candidate owner*. On the production catalogue that replaces 44 390
 * index visits with 588. Both halves of that trade need pinning, and for opposite reasons:
 *
 * - a rewrite that fires on a shape it cannot faithfully reproduce returns a **silently wrong set** - no exception,
 *   just different entities (`shouldNotRewriteWhenTwoAttributeSiblingsFormAnImplicitConjunction` and
 *   `shouldRewriteALocalizedReferenceAttribute` are the two sharpest examples: both would answer a different,
 *   entirely plausible-looking set if the per-row or the locale half of the equivalence broke);
 * - a rewrite that stops firing on the shape the production win runs on costs the measured 75x
 *   (`shouldRewriteCorrectlyWhenBothEndsAreFilteringOnly` pins exactly that shape).
 *
 * ## How a row observes whether the rewrite fired
 *
 * The rewrite's decision is taken twice from one shared `preparePlan`: once by
 * `BidirectionalReferenceRewriter#isApplicable` inside `IndexSelectionVisitor#addReferenceIndexOption`, which returns
 * early and therefore never registers the reference `TargetIndexes` option, and once by
 * `BidirectionalReferenceRewriter#tryRewrite` inside `ReferenceHavingTranslator#translate`, which produces the
 * formula. The first of those is observable from outside the engine: `QueryPlanner#createFilterFormula` pushes one
 * `PLANNING_FILTER_ALTERNATIVE` telemetry step per registered option and pops it with the option's description, and
 * the reference option's description is `Index type: REFERENCED_ENTITY composed of N indexes`. When the rewrite
 * fires that argument is simply absent. `assertReferenceIndexOptionRegistered` is the single place that reads it.
 *
 * Three limits of that channel are load-bearing and are respected throughout this class:
 *
 * 1. the **hierarchy** index option uses the identical description prefix, so no row here carries
 *    `hierarchyWithin(<referenceName>, ...)`;
 * 2. `IndexSelectionVisitor#visit` descends only through `and`, `filterBy`, `filterInScope`, hierarchy constraints
 *    and `referenceHaving`, so a `referenceHaving` under an `or`, under a `not` or inside `entityHaving` registers
 *    no option whether the rewrite fired or not - the channel would report a false "fired". The one row of that
 *    shape (`shouldRewriteUnderNotWithoutWideningTheResult`) therefore asserts the result only, and says so;
 * 3. the assertion is made over the *set of alternative step arguments*, never over `PLANNING_FILTER`'s single
 *    `Selected index: ...` argument, which names only the cheapest plan and would read as "fired" whenever the
 *    planner preferred the global index.
 *
 * Every row asserts **both** the result set - computed from the injected originals by predicate, never hard-coded -
 * **and** the channel, because a row that only checks the result cannot tell a working rewrite from a rewrite that
 * never ran.
 *
 * ## Why the expectations are derived from entity bodies, and never from the other path
 *
 * A measured census of the built catalog established a pre-existing evitaDB behaviour that neither optimisation
 * touches: **a reflected reference on an *archived* entity gets no `REFERENCED_ENTITY_TYPE` index at all**, although
 * its schema declares it indexed in that scope and the entity body still carries the rows. Original references on the
 * very same archived entity do get one. A reflected row on a *live* entity pointing at an *archived* target is
 * indexed normally - `CATEGORY.products [LIVE]` is keyed by all 240 products, the archived ten included.
 *
 * That asymmetry makes the two evaluation paths disagree on archived data, in **both** directions:
 *
 * - under `scope(LIVE)`, the rewrite collects candidates from the counterpart's live type index, which never
 *   announces an owner whose only rows come from archived counterparts - so it silently drops category 11, while the
 *   ordinary path returns it correctly from the buckets keyed by the archived products;
 * - under `scope(LIVE, ARCHIVED)`, the ordinary path reads the owner's own reflected family, whose archived half does
 *   not exist - so it misses category 12, while the rewrite returns it correctly.
 *
 * So neither path may be used as the oracle for the other, and a paired comparison between them would fail for
 * reasons that have nothing to do with the rewrite. Every expectation here is therefore computed from the **entity
 * bodies** through {@link #ownerWithRow} - the one description of the relation both paths are supposed to answer
 * about. Rows whose answer straddles the first divergence are marked EXPECTED-FAIL in their own JavaDoc and assert
 * the correct set rather than the one today's rewrite produces; do not "fix" them by relaxing the expectation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Bidirectional reference rewrite fires on exactly the shapes it can reproduce")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(FILTER)
public class BidirectionalReferenceRewriteFunctionalTest
	extends AbstractBidirectionalReferenceRewriteFunctionalTest {

	/**
	 * Prefix of the `PLANNING_FILTER_ALTERNATIVE` step argument that reveals the reference index option was
	 * registered - i.e. that the rewrite declined. Built by `IndexSelectionVisitor#addReferenceIndexOption` as
	 * `EntityIndexType.REFERENCED_ENTITY.name() + " composed of " + N + " indexes"` and rendered by
	 * `TargetIndexes#toString` as `"Index type: " + description`.
	 */
	private static final String REFERENCE_INDEX_OPTION_PREFIX = "Index type: REFERENCED_ENTITY composed of ";
	/**
	 * Scope set of a query that names no `scope` constraint - the engine default.
	 */
	private static final Set<Scope> LIVE_ONLY = Set.of(Scope.LIVE);
	/**
	 * Scope set of a query carrying `scope(LIVE, ARCHIVED)`.
	 */
	private static final Set<Scope> BOTH_SCOPES = Set.of(Scope.LIVE, Scope.ARCHIVED);
	/**
	 * Reference-row `relevance` value the positive rows filter on. `relevance` is `productPk % 5`, so only 0..4 ever
	 * match - the design's `7L` was written against a different fixture and would make every row vacuous.
	 */
	private static final long MATCHED_RELEVANCE = 1L;
	/**
	 * Reference-row `rank` value on `CATEGORY.curated`, which is `categoryPk % 4` - so only 0..3 ever match.
	 */
	private static final long MATCHED_RANK = 3L;
	/**
	 * The category whose reflected `products` rows carry the `ownNote` value the one-ended-attribute row filters on.
	 * That attribute is declared on the reflected end only, so its values never coincide with the counterpart's.
	 */
	private static final int TRAP_CATEGORY_PK = 3;
	/**
	 * Product whose German `label` the localisation row filters on. It has to be **even**: `label`'s German variant
	 * is written only for even products, so a locale mix-up returns a visibly wrong set instead of an exception.
	 */
	private static final int GERMAN_LABELLED_PRODUCT_PK = 6;
	/**
	 * A `variantTag` value the fixture never writes. The duplicate rows carry only `a` and `b`, so this selects
	 * nothing - which is the point: it makes the rewrite reach an empty answer by itself rather than by declining.
	 */
	private static final String ABSENT_VARIANT_TAG = "no-such-variant";

	/* --------------------------------------------------------------------------------------------------------- */
	/* A-positive - the rewrite fires                                                                              */
	/* --------------------------------------------------------------------------------------------------------- */

	/**
	 * **Declines today, and by design rather than by defect.** `FilterByVisitor#getEntityIndexStream` narrows the
	 * indexes a nested constraint may see to the processing scope's own scopes. A per-owner reduced index living in
	 * another scope - precisely the index the cross-scope fix exists to reach - is therefore filtered out before a
	 * reference-attribute constraint is evaluated, and its owner would be dropped. Widening the visitor's scope set
	 * changes how every nested constraint resolves, so instead the rewrite declines whenever attribute constraints
	 * are present **and** an out-of-scope index actually announced an owner. The owner-side path answers correctly,
	 * which is why the result assertion below is unchanged and only the channel moved.
	 *
	 * **On a default schema this row would fire.** `Scope.DEFAULT_SCOPES` is `{LIVE}`, so the two scope sets coincide
	 * and the narrowing is unreachable; it is reachable here only because the fixture declares every reference in
	 * both scopes. Do not read this row as "the rewrite stopped working on attribute filters" and do not delete the
	 * guard that produces it.
	 */
	@DisplayName("Should rewrite when the queried reference is the reflected end of the pair")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteWhenOwnerReferenceIsTheReflectedEnd(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								attributeEquals(REF_ATTR_RELEVANCE, MATCHED_RELEVANCE)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				// `CATEGORY.products` *is* the reflected reference, so `findCounterpart` takes the named branch and
				// resolves `PRODUCT.categories` outright
				AssertionUtils.assertResultIs(
					originalCategories,
					ownerWithRow(
						LIVE_ONLY, REF_CATEGORY_PRODUCTS, row -> relevanceIs(row, MATCHED_RELEVANCE)
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * The same question as {@link #shouldRewriteWhenOwnerReferenceIsTheReflectedEnd}, minus
	 * `DebugMode#PREFER_INDEX_SCAN`. On a 258-entity fixture the planner answers from prefetched entity bodies, so
	 * this is the only variant in which the prefetch path runs **with a rewritten formula present**. The channel
	 * assertion still holds: index selection - and therefore the rewrite's early return - happens either way.
	 *
	 * **Declines today, and by design rather than by defect.** `FilterByVisitor#getEntityIndexStream` narrows the
	 * indexes a nested constraint may see to the processing scope's own scopes. A per-owner reduced index living in
	 * another scope - precisely the index the cross-scope fix exists to reach - is therefore filtered out before a
	 * reference-attribute constraint is evaluated, and its owner would be dropped. Widening the visitor's scope set
	 * changes how every nested constraint resolves, so instead the rewrite declines whenever attribute constraints
	 * are present **and** an out-of-scope index actually announced an owner. The owner-side path answers correctly,
	 * which is why the result assertion below is unchanged and only the channel moved.
	 *
	 * **On a default schema this row would fire.** `Scope.DEFAULT_SCOPES` is `{LIVE}`, so the two scope sets coincide
	 * and the narrowing is unreachable; it is reachable here only because the fixture declares every reference in
	 * both scopes. Do not read this row as "the rewrite stopped working on attribute filters" and do not delete the
	 * guard that produces it.
	 */
	@DisplayName("Should rewrite the reflected end and agree with itself when the prefetch is allowed")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteWhenOwnerReferenceIsTheReflectedEndWithoutIndexScanPreference(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								attributeEquals(REF_ATTR_RELEVANCE, MATCHED_RELEVANCE)
							)
						),
						observingRequirements(DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					ownerWithRow(
						LIVE_ONLY, REF_CATEGORY_PRODUCTS, row -> relevanceIs(row, MATCHED_RELEVANCE)
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	@DisplayName("Should rewrite when the queried reference is the original end of the pair")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteWhenOwnerReferenceIsTheOriginalEnd(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(REF_CATEGORY_CURATED, attributeEquals(REF_ATTR_RANK, MATCHED_RANK))
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				// `CATEGORY.curated` is the original end, so `findCounterpart` has to scan `PRODUCT`'s references for
				// a reflection pointing back at it - `PRODUCT.curatedBy`. Every curated product of a live category is
				// itself live, so the owner's own rows are a faithful oracle here.
				AssertionUtils.assertResultIs(
					originalCategories,
					liveOwnerWithRow(REF_CATEGORY_CURATED, row -> longAttributeIs(row, REF_ATTR_RANK, MATCHED_RANK)),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, false);
			}
		);
	}

	/**
	 * The prefetch-allowed twin of {@link #shouldRewriteWhenOwnerReferenceIsTheOriginalEnd} - see that method's twin
	 * for why the pair exists.
	 */
	@DisplayName("Should rewrite the original end and agree with itself when the prefetch is allowed")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteWhenOwnerReferenceIsTheOriginalEndWithoutIndexScanPreference(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(REF_CATEGORY_CURATED, attributeEquals(REF_ATTR_RANK, MATCHED_RANK))
						),
						observingRequirements(DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					liveOwnerWithRow(REF_CATEGORY_CURATED, row -> longAttributeIs(row, REF_ATTR_RANK, MATCHED_RANK)),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, false);
			}
		);
	}

	/**
	 * `splitChildren` on zero children yields an empty split, and `createReferencedEntityFormula` then takes the
	 * `entityHavingChild == null` branch - the whole in-scope referenced set, with **no attribute translation at
	 * all**. Neither of the two rows above reaches that branch.
	 */
	@DisplayName("Should rewrite a bare referenceHaving carrying no children")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteBareReferenceHavingWithNoChildren(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(referenceHaving(REF_CATEGORY_PRODUCTS)),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					ownerWithRow(LIVE_ONLY, REF_CATEGORY_PRODUCTS, row -> true),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, false);
			}
		);
	}

	/**
	 * Exercises `HavingTranslatorHelper#planNestedQuery` **inside** the rewrite rather than the all-in-scope
	 * shortcut: the nested query narrows the referenced entities before the per-owner formulas intersect with it.
	 */
	@DisplayName("Should rewrite a referenceHaving carrying only an entityHaving")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteEntityHavingOnly(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								entityHaving(attributeEquals(ATTR_PRODUCT_ACTIVE, true))
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					categoryReachedByLiveProduct(
						originalProducts,
						product -> Boolean.TRUE.equals(product.getAttribute(ATTR_PRODUCT_ACTIVE)),
						row -> true
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, false);
			}
		);
	}

	/**
	 * The documented equivalence argument - `branch1` (the attribute constraints OR-ed across the selected reduced
	 * indexes) is a subset of `branch2` (the owner translation of the nested query), so the owner-side conjunction
	 * collapses to `branch1` - is **only** exercised when both halves are present. Without this row that whole
	 * paragraph of `BidirectionalReferenceRewriter`'s class JavaDoc is unverified.
	 *
	 * **Declines today, and by design rather than by defect.** `FilterByVisitor#getEntityIndexStream` narrows the
	 * indexes a nested constraint may see to the processing scope's own scopes. A per-owner reduced index living in
	 * another scope - precisely the index the cross-scope fix exists to reach - is therefore filtered out before a
	 * reference-attribute constraint is evaluated, and its owner would be dropped. Widening the visitor's scope set
	 * changes how every nested constraint resolves, so instead the rewrite declines whenever attribute constraints
	 * are present **and** an out-of-scope index actually announced an owner. The owner-side path answers correctly,
	 * which is why the result assertion below is unchanged and only the channel moved.
	 *
	 * **On a default schema this row would fire.** `Scope.DEFAULT_SCOPES` is `{LIVE}`, so the two scope sets coincide
	 * and the narrowing is unreachable; it is reachable here only because the fixture declares every reference in
	 * both scopes. Do not read this row as "the rewrite stopped working on attribute filters" and do not delete the
	 * guard that produces it.
	 */
	@DisplayName("Should rewrite an entityHaving combined with a reference attribute")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteEntityHavingCombinedWithReferenceAttribute(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								entityHaving(attributeEquals(ATTR_PRODUCT_ACTIVE, true)),
								attributeEquals(REF_ATTR_RELEVANCE, MATCHED_RELEVANCE)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					categoryReachedByLiveProduct(
						originalProducts,
						product -> Boolean.TRUE.equals(product.getAttribute(ATTR_PRODUCT_ACTIVE)),
						row -> relevanceIs(row, MATCHED_RELEVANCE)
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * The one row that runs the **owner prefetch** with a rewritten formula present.
	 *
	 * `PrefetchFormulaVisitor` harvests `RequirementsDefiner#getEntityRequire` from every visited node regardless of
	 * scope, so the nested `AttributeFormula` built for the *reference* attribute `relevance` - which lives on the
	 * counterpart `PRODUCT.categories`, not on the queried `CATEGORY` - contributes an `entityFetch` requirement
	 * into the **owner's** prefetch requirements and into the prefetch cost estimate. That no attribute-name
	 * validation rejects such a foreign requirement was reasoned about but never executed; this row executes it.
	 * Note also that `getEstimatedCardinality` of the rewritten formula reports the *candidate owner* count, a gross
	 * over-estimate of the result, and that number is what feeds the prefetch decision.
	 *
	 * **Declines today, and by design rather than by defect.** `FilterByVisitor#getEntityIndexStream` narrows the
	 * indexes a nested constraint may see to the processing scope's own scopes. A per-owner reduced index living in
	 * another scope - precisely the index the cross-scope fix exists to reach - is therefore filtered out before a
	 * reference-attribute constraint is evaluated, and its owner would be dropped. Widening the visitor's scope set
	 * changes how every nested constraint resolves, so instead the rewrite declines whenever attribute constraints
	 * are present **and** an out-of-scope index actually announced an owner. The owner-side path answers correctly,
	 * which is why the result assertion below is unchanged and only the channel moved.
	 *
	 * **On a default schema this row would fire.** `Scope.DEFAULT_SCOPES` is `{LIVE}`, so the two scope sets coincide
	 * and the narrowing is unreachable; it is reachable here only because the fixture declares every reference in
	 * both scopes. Do not read this row as "the rewrite stopped working on attribute filters" and do not delete the
	 * guard that produces it.
	 */
	@DisplayName("Should rewrite and still answer correctly when the prefetch is forced")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteEntityHavingCombinedWithReferenceAttributeWhenPrefetchIsPreferred(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								entityHaving(attributeEquals(ATTR_PRODUCT_ACTIVE, true)),
								attributeEquals(REF_ATTR_RELEVANCE, MATCHED_RELEVANCE)
							)
						),
						observingRequirements(
							DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.PREFER_PREFETCHING
						)
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					categoryReachedByLiveProduct(
						originalProducts,
						product -> Boolean.TRUE.equals(product.getAttribute(ATTR_PRODUCT_ACTIVE)),
						row -> relevanceIs(row, MATCHED_RELEVANCE)
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * A pure disjunction of attribute leaves is the **only** container `collectAttributeNames` accepts.
	 *
	 * **Declines today, and by design rather than by defect.** `FilterByVisitor#getEntityIndexStream` narrows the
	 * indexes a nested constraint may see to the processing scope's own scopes. A per-owner reduced index living in
	 * another scope - precisely the index the cross-scope fix exists to reach - is therefore filtered out before a
	 * reference-attribute constraint is evaluated, and its owner would be dropped. Widening the visitor's scope set
	 * changes how every nested constraint resolves, so instead the rewrite declines whenever attribute constraints
	 * are present **and** an out-of-scope index actually announced an owner. The owner-side path answers correctly,
	 * which is why the result assertion below is unchanged and only the channel moved.
	 *
	 * **On a default schema this row would fire.** `Scope.DEFAULT_SCOPES` is `{LIVE}`, so the two scope sets coincide
	 * and the narrowing is unreachable; it is reachable here only because the fixture declares every reference in
	 * both scopes. Do not read this row as "the rewrite stopped working on attribute filters" and do not delete the
	 * guard that produces it.
	 */
	@DisplayName("Should rewrite a pure or() of reference attribute constraints")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewritePureOrOfReferenceAttributes(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		final long otherRelevance = MATCHED_RELEVANCE + 1L;
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								or(
									attributeEquals(REF_ATTR_RELEVANCE, MATCHED_RELEVANCE),
									attributeEquals(REF_ATTR_RELEVANCE, otherRelevance)
								)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					ownerWithRow(
						LIVE_ONLY,
						REF_CATEGORY_PRODUCTS,
						row -> relevanceIs(row, MATCHED_RELEVANCE) || relevanceIs(row, otherRelevance)
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * Pins `flattenConjunction`: a **single**-child `and` flattens away and the rewrite still fires, while two
	 * attribute siblings do not - see `shouldNotRewriteWhenTwoAttributeSiblingsFormAnImplicitConjunction`. The two
	 * rows together are what make the `and` rule legible.
	 *
	 * **Declines today, and by design rather than by defect.** `FilterByVisitor#getEntityIndexStream` narrows the
	 * indexes a nested constraint may see to the processing scope's own scopes. A per-owner reduced index living in
	 * another scope - precisely the index the cross-scope fix exists to reach - is therefore filtered out before a
	 * reference-attribute constraint is evaluated, and its owner would be dropped. Widening the visitor's scope set
	 * changes how every nested constraint resolves, so instead the rewrite declines whenever attribute constraints
	 * are present **and** an out-of-scope index actually announced an owner. The owner-side path answers correctly,
	 * which is why the result assertion below is unchanged and only the channel moved.
	 *
	 * **On a default schema this row would fire.** `Scope.DEFAULT_SCOPES` is `{LIVE}`, so the two scope sets coincide
	 * and the narrowing is unreachable; it is reachable here only because the fixture declares every reference in
	 * both scopes. Do not read this row as "the rewrite stopped working on attribute filters" and do not delete the
	 * guard that produces it.
	 */
	@DisplayName("Should rewrite through a single-child and() container")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteThroughFlattenedAndContainer(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								and(attributeEquals(REF_ATTR_RELEVANCE, MATCHED_RELEVANCE))
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					ownerWithRow(
						LIVE_ONLY, REF_CATEGORY_PRODUCTS, row -> relevanceIs(row, MATCHED_RELEVANCE)
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * Originally written as the empty-candidate row (`collectCandidateOwners` returning `int[0]` and `tryRewrite`
	 * folding to `EmptyFormula`), it does **not** test that branch and has been relabelled accordingly: with zero
	 * `PRODUCT.orphanCategories` rows ever written, no counterpart type index is created at all, so `worthRewriting`
	 * declines on `counterpartTypeIndex.isEmpty()` long before the candidate array is built. What the row does pin
	 * is that the rewriter **declines cleanly** when the counterpart index is absent - the old owner-side path runs,
	 * registers its (zero-index) option, and answers empty rather than throwing or widening.
	 *
	 * The empty-candidate branch itself is covered by the archived-scope regression row in the correctness class.
	 */
	@DisplayName("Should decline cleanly and answer empty when the counterpart type index does not exist")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldDeclineCleanlyWhenTheCounterpartTypeIndexIsAbsent(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		// guard first - an empty result must not be able to pass for the wrong reason
		assertTrue(
			originalProducts.stream().allMatch(it -> it.getReferences(REF_PRODUCT_ORPHAN_CATEGORIES).isEmpty()),
			"The fixture must not write a single `" + REF_PRODUCT_ORPHAN_CATEGORIES + "` row - otherwise this row " +
				"no longer exercises a missing counterpart type index!"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(referenceHaving(REF_CATEGORY_ORPHAN_PRODUCTS)),
						indexScanRequirements()
					),
					EntityReference.class
				);

				// `AssertionUtils#assertResultIs` refuses an empty expectation by design, so assert directly
				assertTrue(
					response.getRecordData().isEmpty(),
					"No category references anything through `" + REF_CATEGORY_ORPHAN_PRODUCTS + "`, so the result " +
						"must be empty but was " + response.getRecordData() + "!"
				);
				// the channel cannot be read on this shape - see `assertPlanningShortCircuited`
				assertPlanningShortCircuited(response);
			}
		);
	}

	/**
	 * `IndexSelectionVisitor` descends into `and`, so the channel stays valid here. The row's own value is that a
	 * rewritten formula must **narrow** inside a conjunction rather than widen - the concrete failure mode
	 * `ReferencedOwnerExistenceFormula#getCloneWithInnerFormulas` guards against. The entity-attribute half of the
	 * conjunction is deliberately narrower than the reference half, so a widening bug shows up as the wrong set
	 * rather than as the same set.
	 *
	 * **Declines today, and by design rather than by defect.** `FilterByVisitor#getEntityIndexStream` narrows the
	 * indexes a nested constraint may see to the processing scope's own scopes. A per-owner reduced index living in
	 * another scope - precisely the index the cross-scope fix exists to reach - is therefore filtered out before a
	 * reference-attribute constraint is evaluated, and its owner would be dropped. Widening the visitor's scope set
	 * changes how every nested constraint resolves, so instead the rewrite declines whenever attribute constraints
	 * are present **and** an out-of-scope index actually announced an owner. The owner-side path answers correctly,
	 * which is why the result assertion below is unchanged and only the channel moved.
	 *
	 * **On a default schema this row would fire.** `Scope.DEFAULT_SCOPES` is `{LIVE}`, so the two scope sets coincide
	 * and the narrowing is unreachable; it is reachable here only because the fixture declares every reference in
	 * both scopes. Do not read this row as "the rewrite stopped working on attribute filters" and do not delete the
	 * guard that produces it.
	 */
	@DisplayName("Should rewrite inside a conjunction with another filter and still narrow")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteInsideConjunctionWithAnotherFilter(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		final Set<String> allowedCodes = Set.of("category-1", "category-2", "category-3");
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								attributeInSet(ATTR_CODE, allowedCodes.toArray(String[]::new)),
								referenceHaving(
									REF_CATEGORY_PRODUCTS,
									attributeEquals(REF_ATTR_RELEVANCE, MATCHED_RELEVANCE)
								)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					ownerWithRow(
						LIVE_ONLY, REF_CATEGORY_PRODUCTS, row -> relevanceIs(row, MATCHED_RELEVANCE)
					).and(category -> allowedCodes.contains(category.getAttribute(ATTR_CODE))),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * The `#1025` universe assertion, re-run against a rewritten formula: `or(P, not(P))` must cover the entire live
	 * collection. High value because `ReferencedOwnerExistenceFormula` is deliberately **not** a
	 * `ChildrenDependentFormula` (`FormulaOptimizer`), and this is the shape where that matters end to end.
	 *
	 * The telemetry channel is deliberately **not** asserted here: `IndexSelectionVisitor#visit` never descends into
	 * `or` or `not`, so no reference option is registered whether the rewrite fired or not, and asserting its
	 * absence would read as a "fired" that proves nothing. The rewrite is still taken - `tryRewrite` is driven from
	 * `ReferenceHavingTranslator#translate`, which is reached regardless of index selection.
	 */
	@DisplayName("Should keep or(referenceHaving, not(referenceHaving)) covering the whole live collection")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteUnderNotWithoutWideningTheResult(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							or(
								referenceHaving(REF_CATEGORY_PRODUCTS),
								not(referenceHaving(REF_CATEGORY_PRODUCTS))
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					category -> category.getScope() == Scope.LIVE,
					response.getRecordData()
				);
			}
		);
	}

	/**
	 * Multi-scope union paths in `collectCandidateOwners` and `counterpartTypeIndexId`. Category 11's `products`
	 * rows come only from archived products and category 12 is archived itself, so both appear in the answer only
	 * when both scopes are genuinely unioned on both sides of the pair.
	 *
	 * **This is the row where the rewrite is right and the ordinary path is wrong**, and it is the clearest evidence
	 * we have that the two are not interchangeable on archived data. The gate fires: the counterpart
	 * `PRODUCT.categories` exists in both scopes, candidates union to `{1..12}`, owner buckets are 240, and
	 * `12 * 4 <= 240`. The rewrite then answers `{1, 6, 11, 12}` - the set the entity bodies describe. The ordinary
	 * path would answer `{1, 6, 11}`, because it reads the owner's own `CATEGORY.products` family and the archived
	 * half of that family does not exist, so category 12's rows are invisible to it.
	 *
	 * The consequence for anyone maintaining this row: **never pair it against the declined variant.** A paired
	 * oracle is the natural instinct here and it would fail - not because the rewrite is wrong, but because the path
	 * it would be compared against is. The expectation is derived from `originalCategories` and `originalProducts`
	 * and must stay that way. (Read the other way round, this also means the rewrite *hides* the pre-existing
	 * indexing defect on this query by removing the alternative plan that `VERIFY_ALTERNATIVE_INDEX_RESULTS` would
	 * have compared against - while on the live-scope query it *exposes* one. Same defect, opposite sign.)
	 *
	 * **EXPECTED-FAIL, and now attributable.** The answer is `{1, 6, 11}` - category 12 absent, which is the
	 * *ordinary* answer rather than the rewrite's. The two mechanisms that could produce it are no longer
	 * indistinguishable: this row carries a reference-attribute constraint and spans both scopes, which is exactly
	 * the shape the cross-scope narrowing declines, so the rewrite does not fire and the owner-side path answers.
	 * That path reads the owner's own `CATEGORY.products` family, whose archived half does not exist, so category
	 * 12's rows are invisible to it and it under-reports by precisely that one category.
	 *
	 * So the red belongs to the **pre-existing missing archived reflected index**, not to the rewrite - the same
	 * defect the class JavaDoc describes, reached from the other side. It is also the sharpest statement of why the
	 * two paths are not interchangeable: on this query the rewrite would have been right and the path that replaced
	 * it is wrong. Assert the derived expectation and never a paired comparison; pairing would compare a correct
	 * answer against an incorrect one and call the correct one broken.
	 */
	@Disabled(
		"Pins the correct behaviour of a pre-existing engine defect: a reflected reference on an archived owner gets no " +
		"ARCHIVED type index at all, so the cross-scope half of this rewrite has nothing to read. Re-enable when issue " +
		"#1583 is fixed."
	)
	@DisplayName("Should rewrite across both scopes when both ends are indexed there")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteAcrossBothScopesWhenBothEndsAreIndexedThere(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							scope(Scope.LIVE, Scope.ARCHIVED),
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								attributeEquals(REF_ATTR_RELEVANCE, MATCHED_RELEVANCE)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					ownerWithRow(
						BOTH_SCOPES, REF_CATEGORY_PRODUCTS, row -> relevanceIs(row, MATCHED_RELEVANCE)
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * `createPerOwnerFormulas` resolves the reference attribute through the **counterpart's** schema accessor, and
	 * the query locale has to survive `executeInContextAndIsolatedFormulaStack` for that lookup to land in the right
	 * localised attribute index. `label`'s German variant exists only on even products, so a locale mix-up returns a
	 * visibly wrong set rather than an exception.
	 *
	 * **Declines today, and by design rather than by defect.** `FilterByVisitor#getEntityIndexStream` narrows the
	 * indexes a nested constraint may see to the processing scope's own scopes. A per-owner reduced index living in
	 * another scope - precisely the index the cross-scope fix exists to reach - is therefore filtered out before a
	 * reference-attribute constraint is evaluated, and its owner would be dropped. Widening the visitor's scope set
	 * changes how every nested constraint resolves, so instead the rewrite declines whenever attribute constraints
	 * are present **and** an out-of-scope index actually announced an owner. The owner-side path answers correctly,
	 * which is why the result assertion below is unchanged and only the channel moved.
	 *
	 * **On a default schema this row would fire.** `Scope.DEFAULT_SCOPES` is `{LIVE}`, so the two scope sets coincide
	 * and the narrowing is unreachable; it is reachable here only because the fixture declares every reference in
	 * both scopes. Do not read this row as "the rewrite stopped working on attribute filters" and do not delete the
	 * guard that produces it.
	 */
	@DisplayName("Should rewrite a localized reference attribute in the requested locale")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteALocalizedReferenceAttribute(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		final String germanLabel = "lab-de-" + GERMAN_LABELLED_PRODUCT_PK;
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityLocaleEquals(Locale.GERMAN),
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								attributeEquals(REF_ATTR_LABEL, germanLabel)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					ownerWithRow(
						LIVE_ONLY,
						REF_CATEGORY_PRODUCTS,
						row -> germanLabel.equals(row.getAttribute(REF_ATTR_LABEL, Locale.GERMAN))
					).and(category -> category.getAllLocales().contains(Locale.GERMAN)),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/* --------------------------------------------------------------------------------------------------------- */
	/* A-negative - one precondition per row, each must decline                                                     */
	/* --------------------------------------------------------------------------------------------------------- */

	/**
	 * Precondition 9 - the cost gate. The *same* pair asked from the other side: 240 candidate products against 11
	 * owner-side buckets fails `worthRewriting`'s `candidates * MINIMAL_GAIN <= buckets`, so the standard owner-side
	 * evaluation runs and registers its reference option. Free - no extra schema, and it is also the path on which
	 * the `attributeIs(NULL)` planning skip is exercised.
	 */
	@DisplayName("Should not rewrite when the cost gate declines the trade")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotRewriteWhenCostGateDeclines(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								REF_PRODUCT_CATEGORIES,
								attributeEquals(REF_ATTR_RELEVANCE, MATCHED_RELEVANCE)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalProducts,
					liveOwnerWithRow(REF_PRODUCT_CATEGORIES, row -> relevanceIs(row, MATCHED_RELEVANCE)),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * Precondition 8 - an attribute that exists on **one end of the pair only**.
	 *
	 * `CATEGORY.products` declares its own filterable `ownNote` carrying `"r" + categoryPk`; its original
	 * `PRODUCT.categories` has no attribute of that name at all (it carries `note`, holding `"o" + productPk`,
	 * which the reflected end excludes from inheritance). `attributesMirrored` therefore declines on
	 * `counterpart.getAttribute("ownNote")` being empty, and the answer comes from the reflected end's own values -
	 * `{3}` - rather than from anything on the product side.
	 *
	 * **What this row does *not* test, and why.** The design wanted the sharper trap: the *same* attribute name
	 * declared independently on both ends with unrelated values, where a rewrite that fired would silently answer
	 * from the wrong end - no exception, just a wrong set. **That schema is unconstructible through the API.**
	 * Excluding a name from inheritance does not free the name, and declaring it on the reflected end is rejected
	 * outright:
	 *
	 * ```
	 * InvalidSchemaMutationException: Attribute inherited from original reference `categories` in entity type
	 * `PRODUCT` cannot be modified directly via. reflected reference schema!
	 * ```
	 *
	 * The consequence is worth stating plainly rather than leaving for the next reader to re-derive:
	 * `attributesMirrored`'s **`isInherited` check is defensive, not load-bearing, at the functional level** -
	 * `isInherited("ownNote")` actually returns `true` here (the inheritance filter lists only `note`, and the
	 * behaviour is `INHERIT_ALL_EXCEPT`), so the decline happens one check later. It is not dead code: the
	 * inheritance matrix is exercised directly, against mocked schemas, by `BidirectionalReferenceRewriterTest`,
	 * which can build the pairs the schema API refuses. Do not delete the check on the strength of this row alone.
	 *
	 * Had the rewrite fired here it would have resolved `ownNote` through the counterpart's schema accessor, where
	 * no such attribute exists - so the likely symptom is a failed query rather than a wrong set. That is a weaker
	 * failure mode than the one the design was aiming at, and it is the strongest one the schema permits.
	 */
	@DisplayName("Should not rewrite when the filtered attribute exists only on the reflected end")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotRewriteWhenTheAttributeExistsOnlyOnTheReflectedEnd(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		final String reflectedNote = "r" + TRAP_CATEGORY_PK;
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								attributeEquals(REF_ATTR_OWN_NOTE, reflectedNote)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				// computed from the reflected end's own values - never from the counterpart's `note`
				AssertionUtils.assertResultIs(
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_PRODUCTS, row -> reflectedNote.equals(row.getAttribute(REF_ATTR_OWN_NOTE))
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * Precondition 7a - **a positive row, not a decline, and the one that pins the production win.**
	 *
	 * `PRODUCT.weakTags` and its reflection `CATEGORY.weakProducts` are declared `indexedForFiltering()` only. That
	 * level still maintains the per-referenced-entity reduced indexes this rewrite walks - what
	 * `indexedForFilteringAndPartitioning()` adds on top is copying the *entity's own* attributes and prices into
	 * them, which the rewrite never reads. So `referenceUsableInScopes` passes on its
	 * `ReferenceIndexedComponents.REFERENCED_ENTITY` check and the rewrite **must fire**.
	 *
	 * This is the exact index level the measured 75x runs on in production, where the corresponding reference is
	 * filtering-only in the live scope. A careless tightening of the scope or index-level precondition - demanding
	 * `FOR_FILTERING_AND_PARTITIONING` where `FOR_FILTERING` suffices - would leave every other row in this class
	 * green while silently switching the headline case back to the slow path. That is why this row sits at the top
	 * of the ranking and asserts the channel as firmly as it asserts the set.
	 */
	@DisplayName("Should rewrite when both ends of the pair are indexed for filtering only")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteCorrectlyWhenBothEndsAreFilteringOnly(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(REF_CATEGORY_WEAK_PRODUCTS, attributeEquals(REF_ATTR_WEAK, 1L))
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_WEAK_PRODUCTS, row -> longAttributeIs(row, REF_ATTR_WEAK, 1L)
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, false);
			}
		);
	}

	/**
	 * Precondition 4a - `splitChildren` falls through on an `entityPrimaryKeyInSet` child. This is also the decline
	 * knob the paired-query oracle uses elsewhere, so it has to be pinned in its own right.
	 */
	@DisplayName("Should not rewrite when entityPrimaryKeyInSet sits inside the referenceHaving")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotRewriteWhenEntityPrimaryKeyInSetIsPresent(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		// products 11 and 12 are live, sit outside the 1..10 band that also references the archived category, and
		// land on two different categories - so the expected set is two entries and carries no scope subtlety
		final Set<Integer> pickedProducts = Set.of(11, 12);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								entityPrimaryKeyInSet(pickedProducts.toArray(Integer[]::new))
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					categoryReachedByLiveProduct(
						originalProducts,
						product -> pickedProducts.contains(product.getPrimaryKey()),
						row -> true
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * Precondition 4b - two attribute siblings are an implicit conjunction, and `splitChildren` refuses it.
	 *
	 * **The owner-side path matches both siblings within a single reference row**, measured by
	 * {@link #shouldMatchTwoAttributeSiblingsWithinOneRowRatherThanAcrossRows} on a reference whose attribute index
	 * is independently proven to answer. The values here therefore name one row and one row only: product 21 carries
	 * both `refAlwaysSet = "ra-21"` and `refSometimesSet = 21`, and it belongs to category 1.
	 *
	 * Note that `collectAttributeNames`' JavaDoc in `src/main` describes the opposite - each leaf OR-ed across every
	 * selected reduced index before the conjunction, so that two *different* rows could satisfy the two leaves
	 * between them - and uses that to justify excluding `and` from the rewrite. The measurement says otherwise. The
	 * exclusion may still be right for other reasons, but the stated reason is not what the engine does; see the
	 * measuring row's JavaDoc.
	 */
	@DisplayName("Should not rewrite two attribute siblings and keep their cross-row meaning")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotRewriteWhenTwoAttributeSiblingsFormAnImplicitConjunction(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		final String alwaysSetOfProduct21 = "ra-21";
		final long sometimesSetOfProduct21 = 21L;
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								attributeEquals(REF_ATTR_ALWAYS_SET, alwaysSetOfProduct21),
								attributeEquals(REF_ATTR_SOMETIMES_SET, sometimesSetOfProduct21)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				// one row satisfying both leaves - the per-row reading, not one row each
				AssertionUtils.assertResultIs(
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_PRODUCTS,
						row -> alwaysSetOfProduct21.equals(row.getAttribute(REF_ATTR_ALWAYS_SET)) &&
							longAttributeIs(row, REF_ATTR_SOMETIMES_SET, sometimesSetOfProduct21)
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * Declines - but no longer because the shape is unsupported. `splitChildren` accepts a single top-level `not`
	 * over a reference attribute, so what stops this pair is the *cross-scope* guard: `CATEGORY.products` and its
	 * counterpart are indexed in both scopes, an archived index announces owners, and `preparePlanInternal`
	 * declines every body carrying an attribute constraint in that situation - exactly as it does for the positive
	 * form in {@link #shouldRewriteWhenOwnerReferenceIsTheReflectedEnd}. The rewritten path for a negated body is
	 * pinned by {@link #shouldRewriteANegatedReferenceAttribute} instead, on the one reference whose owner end is
	 * LIVE-only.
	 *
	 *
	 * The pair also pins the **row-scoped** reading of a negated body, which is what issue #1585 turned out to be
	 * about. `referenceHaving` is documented as the SQL `EXISTS` operator - its constraints must be "satisfied by
	 * one of the entity references" - so `referenceHaving(R, not(x))` selects owners holding a row on which `x`
	 * does *not* hold. That is deliberately **not** the complement of `referenceHaving(R, x)`: an owner may hold
	 * one row matching `x` and another failing it, and the two readings then disagree about it. Were they the same
	 * question, the inner `not` would be redundant with an outer one.
	 *
	 * The two queries exist to make that falsifiable, and it takes both:
	 *
	 * 1. `not(refAlwaysSet == "ra-11")`. `refAlwaysSet` is `"ra-" + productPk`, so exactly one row in the whole
	 *    fixture matches it, and it belongs to category 1 - which owns two hundred further rows that fail it. So
	 *    category 1 is *in*, and a reading that excluded it would be the per-owner one.
	 * 2. `not(relevance == 1)`. Within a category `relevance` is `categoryPk % 5` on **every** row, so categories
	 *    1 and 6 hold no row failing the constraint at all and must be the only two absent.
	 *
	 * Query 2 is what makes the pair decisive: an owner all of whose rows satisfy the negated constraint cannot
	 * appear under either reading, so its presence proves the constraint was not applied at all. That is precisely
	 * what both queries used to show - they answered with the entire live collection, whatever was negated,
	 * because index selection resolved the negation against the reference *type* index and emptied the family.
	 */
	@DisplayName("Should not rewrite when a not() is nested inside the referenceHaving")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotRewriteWhenNotIsNestedInsideReferenceHaving(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		final String alwaysSetOfProduct11 = "ra-11";
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// 1 - one matching row in the whole fixture, and its owner holds plenty of rows that fail it
				final EvitaResponse<EntityReference> byPerProductValue = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								not(attributeEquals(REF_ATTR_ALWAYS_SET, alwaysSetOfProduct11))
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					"`not` inside a referenceHaving selects owners holding a row that FAILS the constraint - the " +
						"owner of the single matching row holds two hundred others and must therefore be present!",
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_PRODUCTS,
						row -> !alwaysSetOfProduct11.equals(row.getAttribute(REF_ATTR_ALWAYS_SET))
					),
					byPerProductValue.getRecordData()
				);
				assertReferenceIndexOptionRegistered(byPerProductValue, true);

				// 2 - the decisive one: categories whose EVERY row matches must be absent under any reading
				final EvitaResponse<EntityReference> byPerCategoryValue = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								not(attributeEquals(REF_ATTR_RELEVANCE, MATCHED_RELEVANCE))
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					"Every row of these categories satisfies the negated constraint, so no reading of `not` can admit " +
						"them - their presence is what proves the constraint is ignored rather than misread!",
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_PRODUCTS, row -> !relevanceIs(row, MATCHED_RELEVANCE)
					),
					byPerCategoryValue.getRecordData()
				);
				assertReferenceIndexOptionRegistered(byPerCategoryValue, true);
			}
		);
	}

	/**
	 * The rewrite answers a **negated** reference attribute from the counterpart end, and returns the same owners the
	 * owner-side path does.
	 *
	 * `CATEGORY.scopedProducts` is the only reference in the fixture this can be asked on: every other one is indexed
	 * in both scopes, and `preparePlanInternal` declines any body carrying an attribute constraint when an index
	 * outside the requested scopes announced an owner. Here the owner end is LIVE-only, so `counterpartScopes`
	 * collapses to the requested scope and the guard cannot fire - see {@code REF_ATTR_SCOPED_GRADE}.
	 *
	 * Why the rewrite may do this at all: the counterpart's reduced indexes for one owner hold **only** that owner's
	 * rows, and a referenced entity appears in exactly one of them. So subtracting the matching set from the owner's
	 * own rows yields precisely the referenced entities reached through a row that *fails* the constraint - the
	 * row-scoped reading, computed without leaving the owner and without any structure the engine does not already
	 * maintain.
	 *
	 * Three queries, and it takes all three:
	 *
	 * 1. `scopedGrade == 0` - the positive control. Without it a decline would leave the two negated rows asserting
	 *    against a path that was never exercised, and the registration assertion is the only thing that would notice.
	 * 2. `not(scopedGrade == 0)`. `scopedGrade` is `categoryPk % 3` on **every** row of a category, so categories 3,
	 *    6 and 9 hold no row failing the constraint at all and must be the only ones absent. An owner all of whose
	 *    rows satisfy the negated constraint cannot appear under any reading of `not`, so its presence would prove
	 *    the constraint was dropped rather than misread.
	 * 3. `not(scopedGrade == 9)`. Exactly one row in the whole fixture carries that value, and its owner holds
	 *    twenty-two others that fail it - so a per-owner reading would drop that owner and the row-scoped one keeps
	 *    it. This is the query that separates `∃r ¬A(r)` from `¬∃r A(r)`.
	 */
	@DisplayName("Should rewrite a negated reference attribute against the owner's own rows")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteANegatedReferenceAttribute(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		final long absentFromThreeCategories = 0L;
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// 1 - the positive control: the rewrite has to fire on this reference at all
				final EvitaResponse<EntityReference> positive = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_SCOPED_PRODUCTS,
								attributeEquals(REF_ATTR_SCOPED_GRADE, absentFromThreeCategories)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_SCOPED_PRODUCTS, row -> scopedGradeIs(row, absentFromThreeCategories)
					),
					positive.getRecordData()
				);
				assertReferenceIndexOptionRegistered(positive, false);

				// 2 - the decisive one: categories whose EVERY row matches must be absent under any reading
				final EvitaResponse<EntityReference> byPerCategoryValue = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_SCOPED_PRODUCTS,
								not(attributeEquals(REF_ATTR_SCOPED_GRADE, absentFromThreeCategories))
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					"Every row of these categories satisfies the negated constraint, so no reading of `not` can admit " +
						"them - their presence would prove the constraint was dropped rather than complemented!",
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_SCOPED_PRODUCTS, row -> !scopedGradeIs(row, absentFromThreeCategories)
					),
					byPerCategoryValue.getRecordData()
				);
				assertReferenceIndexOptionRegistered(byPerCategoryValue, false);

				// 3 - one matching row in the whole fixture, and its owner holds plenty of rows that fail it
				final EvitaResponse<EntityReference> byUniqueValue = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_SCOPED_PRODUCTS,
								not(attributeEquals(REF_ATTR_SCOPED_GRADE, UNIQUE_SCOPED_GRADE))
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					"`not` inside a referenceHaving selects owners holding a row that FAILS the constraint - the " +
						"owner of the single matching row holds twenty-two others and must therefore be present!",
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_SCOPED_PRODUCTS, row -> !scopedGradeIs(row, UNIQUE_SCOPED_GRADE)
					),
					byUniqueValue.getRecordData()
				);
				assertReferenceIndexOptionRegistered(byUniqueValue, false);
			}
		);
	}

	/**
	 * Precondition 5 - `orderBy(referenceProperty(R, ...))` sorts owners by the position of their reference row
	 * among the reduced indexes index selection picked for `R`. Taking the rewrite removes that entry and the sorter
	 * silently falls back to *every* reduced index of `R`, ordering owners by their first reference row rather than
	 * their first *matching* one. So the rewrite leaves these queries alone.
	 *
	 * The `entityHaving` narrowing is not decoration: it restricts the selected reduced indexes to *live* products,
	 * which both keeps category 11 - whose only rows point at archived products - out of the answer under either
	 * reading of cross-scope row visibility, and leaves five categories whose rows carry five **distinct**
	 * `relevance` values, so ascending order is fully determined and can be asserted as strictly increasing rather
	 * than as a tie-break-dependent permutation.
	 */
	@DisplayName("Should not rewrite when the query is ordered by the very same reference")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotRewriteWhenOrderedByTheSameReference(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								entityHaving(attributeEquals(ATTR_PRODUCT_ACTIVE, true))
							)
						),
						orderBy(
							referenceProperty(
								REF_CATEGORY_PRODUCTS,
								attributeNatural(REF_ATTR_RELEVANCE, OrderDirection.ASC)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					categoryReachedByLiveProduct(
						originalProducts,
						product -> Boolean.TRUE.equals(product.getAttribute(ATTR_PRODUCT_ACTIVE)),
						row -> true
					),
					response.getRecordData()
				);
				assertRelevanceStrictlyIncreasing(response, originalProducts);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * Precondition 5b - the guard is by reference **name**, not "any `referenceProperty` anywhere in the order by".
	 * Without this row an over-broad guard passes
	 * {@link #shouldNotRewriteWhenOrderedByTheSameReference} and nobody notices that the rewrite has been switched
	 * off for every sorted query.
	 *
	 * The filter names `curated` and the order names `products`, rather than the other way round, for two reasons
	 * the baseline run established. `rank` is declared filterable but **not** sortable, so ordering by it raises
	 * `AttributeNotSortableException` before any of this is reached; `relevance` is the fixture's only sortable
	 * reference attribute. And filtering on `curated` keeps the row clear of the archived-scope divergence the
	 * class JavaDoc describes, so it pins the name check and nothing else.
	 *
	 * **No ordering assertion, deliberately.** Category 11's `products` rows point at the archived products
	 * 231-240, whose `relevance` values run 0..4, so "category 11's relevance" is not a single number and any
	 * pinned sequence would be asserting a tie-break. Ordering correctness is
	 * {@link #shouldNotRewriteWhenOrderedByTheSameReference}'s job; this row's claim is only that an `orderBy`
	 * naming a *different* reference leaves the rewrite enabled, which the result set and the channel pin between
	 * them.
	 */
	@DisplayName("Should rewrite when the query is ordered by a different reference")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteWhenOrderedByADifferentReference(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(REF_CATEGORY_CURATED, attributeEquals(REF_ATTR_RANK, MATCHED_RANK))
						),
						orderBy(
							referenceProperty(
								REF_CATEGORY_PRODUCTS,
								attributeNatural(REF_ATTR_RELEVANCE, OrderDirection.ASC)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					liveOwnerWithRow(REF_CATEGORY_CURATED, row -> longAttributeIs(row, REF_ATTR_RANK, MATCHED_RANK)),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, false);
			}
		);
	}

	/**
	 * A reference allowing duplicate rows per `(owner, referenced)` pair used to be a hard decline, on the grounds
	 * that its reduced-index family is addressed by index primary key rather than by a fully qualified key. That was
	 * an *addressing* problem, not a semantic one, and it has been fixed in
	 * {@link io.evitadb.core.query.QueryPlanningContext#getEntityIndexByPrimaryKey(String, int, Class)} by naming the
	 * collection the keys came from. The rewrite is now taken here.
	 *
	 * The fixture writes three `PRODUCT.variants` rows per product 1..{@link #LAST_VARIANT_PRODUCT_PK}: two pointing
	 * at category 1 (tags `a` and `b`) and one at category 2 (tag `a`). Tag `b` therefore reaches exactly one
	 * category, which is what makes this row discriminating - a rewrite resolving its per-owner indexes against the
	 * wrong collection answers with whichever categories the colliding index happened to hold.
	 */
	@DisplayName("Should rewrite when the reference cardinality allows duplicate rows")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteWhenTheReferenceCardinalityAllowsDuplicates(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		// guard - tag `b` must separate the categories, or a wrong answer could still look right
		assertTrue(
			originalCategories.stream().anyMatch(carriesVariantTag("b")) &&
				originalCategories.stream().anyMatch(carriesVariantTag("b").negate()),
			"Tag `b` must be carried by some categories and not by others, or this row cannot tell a correct " +
				"rewrite from one answering with the whole announced candidate set!"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_VARIANT_PRODUCTS,
								attributeEquals(REF_ATTR_VARIANT_TAG, "b")
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					carriesVariantTag("b"),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, false);
			}
		);
	}

	/**
	 * An owner whose qualifying rows are spread over many products must still be returned exactly once.
	 *
	 * Note what this row does **not** prove, and why the discriminating case is a separate row below. A reduced index
	 * is keyed by `RepresentativeReferenceKey`, so all thirty `(category 1, a)` rows collapse into a *single* index
	 * holding products 1-30 - not thirty indexes. Two indexes are resolved for category 1 (`a` and `b`), so the OR in
	 * `createPerOwnerFormulas` does run with more than one input, but only one of them qualifies for tag `a`, and the
	 * two carry identical bitmaps anyway. An implementation that kept only the first non-empty qualifying index would
	 * pass this row.
	 */
	@DisplayName("Should return an owner once when many products contribute the same qualifying tag")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldReturnAnOwnerOnceWhenManyProductsContributeTheSameTag(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		// guard - the row is only about de-duplicating an owner if several rows really do contribute it
		assertTrue(
			originalCategories.stream().anyMatch(category -> variantRowsWithTag(category, "a") > 1),
			"Some category must be reached by more than one `" + REF_CATEGORY_VARIANT_PRODUCTS + "` row carrying " +
				"tag `a`, or there is nothing to de-duplicate!"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_VARIANT_PRODUCTS,
								attributeEquals(REF_ATTR_VARIANT_TAG, "a")
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					carriesVariantTag("a"),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, false);
			}
		);
	}

	/**
	 * The discriminating duplicate row: the answer depends on a **specific** one of an owner's reduced indexes being
	 * kept, so an implementation that retained only the first qualifying partition would return the wrong set.
	 *
	 * Category {@link #DISJOINT_VARIANT_CATEGORY_PK} is the only owner in the fixture whose two partitions hold
	 * disjoint products - `x` holds product {@link #DISJOINT_VARIANT_X_PRODUCT_PK} alone and `y` holds product
	 * {@link #DISJOINT_VARIANT_Y_PRODUCT_PK} alone. Pairing the `Or` over both tags with an `entityHaving` that admits
	 * only one of those products makes exactly one partition decisive: drop it and the category disappears from the
	 * answer, keep only it and the category still appears. Everywhere else in this fixture the partitions carry
	 * identical bitmaps, which is why no other row can make this distinction.
	 */
	@DisplayName("Should keep the duplicate partition the answer actually depends on")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldKeepTheDuplicatePartitionTheAnswerDependsOn(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		// guard - the two partitions must genuinely be disjoint, or nothing here is decisive
		final Predicate<SealedEntity> disjointCategory =
			category -> category.getPrimaryKeyOrThrowException() == DISJOINT_VARIANT_CATEGORY_PK;
		assertTrue(
			originalCategories.stream()
				.filter(disjointCategory)
				.anyMatch(category -> variantRowsWithTag(category, "x") == 1 && variantRowsWithTag(category, "y") == 1),
			"Category " + DISJOINT_VARIANT_CATEGORY_PK + " must hold exactly one `x` row and one `y` row, or the " +
				"two partitions are not disjoint and this row proves nothing!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// the `y` partition is the only one admitting product DISJOINT_VARIANT_Y_PRODUCT_PK
				final EvitaResponse<EntityReference> viaY = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_VARIANT_PRODUCTS,
								entityHaving(entityPrimaryKeyInSet(DISJOINT_VARIANT_Y_PRODUCT_PK)),
								or(
									attributeEquals(REF_ATTR_VARIANT_TAG, "x"),
									attributeEquals(REF_ATTR_VARIANT_TAG, "y")
								)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					"Only the `y` partition holds product " + DISJOINT_VARIANT_Y_PRODUCT_PK + " - an implementation " +
						"keeping just the first qualifying reduced index would lose category " +
						DISJOINT_VARIANT_CATEGORY_PK + " entirely!",
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_VARIANT_PRODUCTS,
						row -> row.getReferencedPrimaryKey() == DISJOINT_VARIANT_Y_PRODUCT_PK &&
							("x".equals(row.getAttribute(REF_ATTR_VARIANT_TAG)) ||
								"y".equals(row.getAttribute(REF_ATTR_VARIANT_TAG)))
					),
					viaY.getRecordData()
				);
				assertReferenceIndexOptionRegistered(viaY, false);

				// and the mirror image, so the row cannot pass by always answering with the same partition
				final EvitaResponse<EntityReference> viaX = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_VARIANT_PRODUCTS,
								entityHaving(entityPrimaryKeyInSet(DISJOINT_VARIANT_X_PRODUCT_PK)),
								or(
									attributeEquals(REF_ATTR_VARIANT_TAG, "x"),
									attributeEquals(REF_ATTR_VARIANT_TAG, "y")
								)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					"The mirror image must resolve through the `x` partition instead",
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_VARIANT_PRODUCTS,
						row -> row.getReferencedPrimaryKey() == DISJOINT_VARIANT_X_PRODUCT_PK &&
							("x".equals(row.getAttribute(REF_ATTR_VARIANT_TAG)) ||
								"y".equals(row.getAttribute(REF_ATTR_VARIANT_TAG)))
					),
					viaX.getRecordData()
				);
				assertReferenceIndexOptionRegistered(viaX, false);
			}
		);
	}

	/**
	 * A value no duplicate row carries must come back empty **through the rewrite**, not through the planner giving
	 * up earlier.
	 *
	 * `IndexSelectionVisitor#addReferenceIndexOption` returns before touching the type index whenever the rewrite is
	 * applicable, and applicability is decided from the candidate owners rather than from the attribute value - so
	 * index selection cannot short-circuit here the way it does on a declined shape. Reading the absent reference
	 * option is therefore still meaningful, and it is what separates "the rewrite computed nothing" from "the
	 * planner never reached the filter".
	 *
	 * **That is the whole of what these two assertions prove.** They say nothing about *which* reduced indexes were
	 * read: an implementation resolving the primary keys against the wrong collection would also answer empty here,
	 * because no index anywhere carries this tag. The addressing is pinned by the positive rows, and decisively by
	 * {@link #shouldKeepTheDuplicatePartitionTheAnswerDependsOn}; this row only rules out the planner short circuit.
	 */
	@DisplayName("Should answer empty through the rewrite when no duplicate row carries the value")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldAnswerEmptyThroughTheRewriteWhenNoDuplicateRowCarriesTheValue(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		// guard - an empty answer must not be able to pass for the wrong reason
		assertTrue(
			originalCategories.stream().noneMatch(carriesVariantTag(ABSENT_VARIANT_TAG)),
			"No category may carry tag `" + ABSENT_VARIANT_TAG + "`, or this row is asserting emptiness against a " +
				"fixture that could legitimately answer!"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_VARIANT_PRODUCTS,
								attributeEquals(REF_ATTR_VARIANT_TAG, ABSENT_VARIANT_TAG)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				// `AssertionUtils#assertResultIs` refuses an empty expectation by design, so assert directly
				assertTrue(
					response.getRecordData().isEmpty(),
					"No `" + REF_CATEGORY_VARIANT_PRODUCTS + "` row carries tag `" + ABSENT_VARIANT_TAG + "`, so " +
						"the result must be empty but was " + response.getRecordData() + "!"
				);
				assertReferenceIndexOptionRegistered(response, false);
				// without this, an absent reference option would be equally consistent with the planner never
				// reaching the filter at all - which is the *other* way to answer empty, and not the one claimed
				assertPlanningReachedTheFilter(response);
			}
		);
	}

	/**
	 * A pure `Or` of attribute leaves is the one multi-leaf shape `collectAttributeNames` admits, and duplicates are
	 * what make it interesting: the two disjuncts are satisfied by **different rows of the same owner**, so the
	 * answer is only right if the disjunction is evaluated inside each duplicate index and the results unioned
	 * afterwards - which is exactly the order `createPerOwnerFormulas` produces.
	 */
	@DisplayName("Should rewrite a disjunction whose branches land in different duplicate rows")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldRewriteADisjunctionAcrossDuplicateRows(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		// guard - the two disjuncts must genuinely be split across two rows of one owner, otherwise the row degrades
		// into the single-leaf case already covered above
		assertTrue(
			originalCategories.stream().anyMatch(carriesVariantTag("a").and(carriesVariantTag("b"))),
			"Some category must hold one `" + REF_CATEGORY_VARIANT_PRODUCTS + "` row tagged `a` and another tagged " +
				"`b`, or the disjunction never spans two duplicate rows!"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_VARIANT_PRODUCTS,
								or(
									attributeEquals(REF_ATTR_VARIANT_TAG, "a"),
									attributeEquals(REF_ATTR_VARIANT_TAG, "b")
								)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					carriesVariantTag("a").or(carriesVariantTag("b")),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, false);
			}
		);
	}

	/**
	 * Two attribute siblings stay a hard decline on a duplicate reference, and the answer stays per-row.
	 *
	 * This is the invariant that makes duplicates safe to rewrite at all. `splitChildren` refuses more than one
	 * attribute child because a conjunction is not reproducible from the counterpart end, and duplicates are the
	 * sharpest form of the question: category 1 holds a row tagged `a` **and** a row tagged `b` for the very same
	 * product, so a cross-row reading would answer with it while the per-row reading answers nothing. The
	 * non-duplicate twin of this row - {@link #shouldMatchTwoAttributeSiblingsWithinOneRowRatherThanAcrossRows} -
	 * can only split the two values across two different referenced entities; here they sit on one
	 * `(owner, referenced)` pair, which no reference without duplicates can express.
	 */
	@DisplayName("Should still decline two attribute siblings on a duplicate reference")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldStillDeclineTwoAttributeSiblingsOnADuplicateReference(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		// guard - the cross-row reading would have answered, so the empty result below is a real signal
		assertTrue(
			originalCategories.stream().anyMatch(carriesVariantTag("a").and(carriesVariantTag("b"))),
			"Some category must hold the two tags on two duplicate rows of one pair, or this row cannot tell the " +
				"per-row reading from the cross-row one!"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_VARIANT_PRODUCTS,
								attributeEquals(REF_ATTR_VARIANT_TAG, "a"),
								attributeEquals(REF_ATTR_VARIANT_TAG, "b")
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				assertTrue(
					response.getRecordData().isEmpty(),
					"Two attribute siblings are matched within a single reference row, and no row can hold two " +
						"different values of one attribute - so the answer must be empty but was " +
						response.getRecordData() + "!"
				);
				// the rewrite declined, so index selection evaluated the type index itself, matched no reduced index
				// and returned an empty plan before filter planning - the same channel the non-duplicate twin reads
				assertPlanningShortCircuited(response);
			}
		);
	}

	/**
	 * Precondition 6 - `CATEGORY.plainProducts` points at `PRODUCT` and nothing on `PRODUCT` reflects it back, so
	 * `findCounterpart`'s scan finds nothing and there is no other end to ask.
	 */
	@DisplayName("Should not rewrite a reference that has no reflected counterpart")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotRewriteWhenReferenceHasNoReflectedCounterpart(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(referenceHaving(REF_CATEGORY_PLAIN_PRODUCTS, attributeEquals(REF_ATTR_PLAIN, 3L))),
						indexScanRequirements()
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_PLAIN_PRODUCTS, row -> longAttributeIs(row, REF_ATTR_PLAIN, 3L)
					),
					response.getRecordData()
				);
				assertReferenceIndexOptionRegistered(response, true);
			}
		);
	}

	/**
	 * Precondition 7b - the reason `preparePlan` checks `referenceUsableInScopes` on the **owner** end and not only
	 * on the counterpart.
	 *
	 * Asking for a scope a reference is not indexed in is a usage error the caller is supposed to see: the standard
	 * path substitutes a throwing stub for the absent type index and raises `ReferenceNotIndexedException` naming
	 * the reference and the scope. An optimisation is allowed to change how a question is answered, never whether
	 * asking it is legal - so the rewrite has to decline here rather than quietly produce a formula, which is
	 * precisely what the owner half of the check buys.
	 *
	 * **Why the fixture pair is oriented the way it is.** `CATEGORY.scopedProducts` is the *reflected* end and is
	 * declared live-only, while its original `PRODUCT.scopedCategories` is indexed in both scopes and carries rows
	 * on the archived products too. That orientation is load-bearing: were the live-only reference the original
	 * instead, its mirror rows on the archived products would be dropped, no archived counterpart type index would
	 * exist, and `worthRewriting` would decline on its own - leaving this row green even with the owner check
	 * deleted. As written, everything ahead of and behind the check succeeds, so the check is the only thing
	 * standing between the caller and a swallowed exception.
	 *
	 * The two assertions pull in opposite directions on purpose. The message check pins that the error names the
	 * **owner** reference: an `assertThrows` on the class alone would also pass if the rewrite swallowed the owner
	 * error and the counterpart raised one of its own. The live-scope companion pins that the reference works at
	 * all - without it a change that broke `scopedProducts` outright would keep this row green for the wrong
	 * reason.
	 */
	@DisplayName("Should still throw when the owner reference is not indexed in a requested scope")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldStillThrowReferenceNotIndexedWhenOwnerReferenceIsNotIndexedInScope(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ReferenceNotIndexedException exception = assertThrows(
					ReferenceNotIndexedException.class,
					() -> session.query(
						query(
							collection(Entities.CATEGORY),
							filterBy(
								scope(Scope.LIVE, Scope.ARCHIVED),
								referenceHaving(REF_CATEGORY_SCOPED_PRODUCTS)
							),
							indexScanRequirements()
						),
						EntityReference.class
					),
					"Asking for a scope `" + REF_CATEGORY_SCOPED_PRODUCTS + "` is not indexed in must fail - the " +
						"rewrite may change how the question is answered, never whether asking it is legal!"
				);
				final String message = exception.getMessage();
				assertTrue(
					message.contains(REF_CATEGORY_SCOPED_PRODUCTS),
					"The error must name the owner reference `" + REF_CATEGORY_SCOPED_PRODUCTS + "` but was `" +
						message + "`!"
				);
				assertFalse(
					message.contains(REF_PRODUCT_SCOPED_CATEGORIES),
					"The error must name the owner reference, not the counterpart `" +
						REF_PRODUCT_SCOPED_CATEGORIES + "` - a message naming the counterpart means the owner error " +
						"was swallowed and the counterpart raised one of its own instead: `" + message + "`!"
				);
				assertTrue(
					message.contains(Scope.ARCHIVED.name()),
					"The error must name the scope the reference is not indexed in but was `" + message + "`!"
				);

				// the companion half - the very same question asked only in the scope the reference *is* indexed in
				// must succeed, so that a `scopedProducts` broken outright cannot keep the row above green
				final EvitaResponse<EntityReference> liveResponse = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							scope(Scope.LIVE),
							referenceHaving(REF_CATEGORY_SCOPED_PRODUCTS)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					originalCategories,
					ownerWithRow(
						LIVE_ONLY, REF_CATEGORY_SCOPED_PRODUCTS, row -> true
					),
					liveResponse.getRecordData()
				);
				// in the live scope alone every precondition holds and the gate passes, so the rewrite takes over
				assertReferenceIndexOptionRegistered(liveResponse, false);
			}
		);
	}

	/* --------------------------------------------------------------------------------------------------------- */
	/* Pre-existing engine behaviour these rows measure rather than assume                                         */
	/* --------------------------------------------------------------------------------------------------------- */

	/**
	 * **MEASURED, and the suspicion it was built to confirm is refuted: inherited reference attributes filter
	 * correctly from the reflected end.** All three queries answer. Keep the row anyway - it is the control that
	 * settles the question everyone will ask first the next time a reflected-end filter looks wrong, and it cost
	 * nothing to keep once written.
	 *
	 * It asks one question from both ends of the same pair, with the rewrite declined on both sides, and compares each
	 * answer against the entity bodies. All three queries filter on a value that identifies exactly one reference
	 * row, so each correct answer is a single entity and an empty result cannot be mistaken for a correct one:
	 *
	 * 1. the **original** end - `PRODUCT.categories` filtered by the inherited-from-nobody `refAlwaysSet`. The cost
	 *    gate declines here (240 candidates against 11 buckets), so this is the ordinary owner-side path on an
	 *    original reference. Expected `{11}`: `refAlwaysSet` is `"ra-" + productPk`.
	 * 2. the **reflected** end - `CATEGORY.products` filtered by the *same* attribute, which the reflected end
	 *    inherits. `entityPrimaryKeyInSet` over every product is the decline knob: `splitChildren` refuses it, so the
	 *    rewrite cannot take over, and listing all 240 keys - the archived ten included - means it narrows nothing.
	 *    Expected `{1}`, the category product 11 belongs to.
	 * 3. the reflected end again, same shape and same decline knob, but filtered by `ownNote` - an attribute the
	 *    reflected end **declares itself** rather than inherits. Expected `{1}` as well.
	 *
	 * Query 1 is the control that says `refAlwaysSet` is filterable at all; query 3 is the control that says the
	 * reflected end's indexes are reachable at all. Query 2 was written expecting the empty set - the hypothesis
	 * being that inherited attributes reach the entity body but not the reflected end's filter index. **It answers
	 * correctly.** Inherited attributes are indexed on both ends, the rewrite masks nothing here, and the reds that
	 * prompted the suspicion had other causes entirely.
	 */
	@DisplayName("Should filter by an inherited reference attribute from the reflected end as well as the original")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldFilterByAnInheritedAttributeFromEitherEndOfThePair(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		final String alwaysSetOfProduct11 = "ra-11";
		final String ownNoteOfCategory1 = "r1";
		// the decline knob - every product key, archived ten included, so it suppresses the rewrite without narrowing
		final Integer[] everyProduct = originalProducts.stream()
			.map(SealedEntity::getPrimaryKey)
			.toArray(Integer[]::new);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// 1 - control: the original end answers
				final EvitaResponse<EntityReference> fromOriginalEnd = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								REF_PRODUCT_CATEGORIES,
								attributeEquals(REF_ATTR_ALWAYS_SET, alwaysSetOfProduct11)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					"The original end must answer - if this is empty the attribute is not filterable anywhere and the " +
						"rest of this row proves nothing!",
					originalProducts,
					liveOwnerWithRow(
						REF_PRODUCT_CATEGORIES,
						row -> alwaysSetOfProduct11.equals(row.getAttribute(REF_ATTR_ALWAYS_SET))
					),
					fromOriginalEnd.getRecordData()
				);

				// 3 - control: the reflected end's own attribute answers
				final EvitaResponse<EntityReference> byOwnAttribute = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								attributeEquals(REF_ATTR_OWN_NOTE, ownNoteOfCategory1),
								entityPrimaryKeyInSet(everyProduct)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					"The reflected end's own attribute must answer - if this is empty the reflected end's indexes are " +
						"unreachable for a different reason and the comparison below is meaningless!",
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_PRODUCTS,
						row -> ownNoteOfCategory1.equals(row.getAttribute(REF_ATTR_OWN_NOTE))
					),
					byOwnAttribute.getRecordData()
				);

				// 2 - the measurement: the same attribute as query 1, asked from the end that inherits it
				final EvitaResponse<EntityReference> fromReflectedEnd = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PRODUCTS,
								attributeEquals(REF_ATTR_ALWAYS_SET, alwaysSetOfProduct11),
								entityPrimaryKeyInSet(everyProduct)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					"An inherited reference attribute must be filterable from the reflected end exactly as it is from " +
						"the original - the rows are the same rows and the values are the same values!",
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_PRODUCTS,
						row -> alwaysSetOfProduct11.equals(row.getAttribute(REF_ATTR_ALWAYS_SET))
					),
					fromReflectedEnd.getRecordData()
				);
			}
		);
	}

	/**
	 * **MEASURED, and the answer contradicts the engine's own documentation: the semantics are per-row.**
	 *
	 * Settles what two attribute siblings inside one `referenceHaving` actually mean, on a reference whose attribute
	 * index is known to work. `CATEGORY.plainProducts` is an original reference with no reflected counterpart, so the
	 * rewrite always declines on it and
	 * {@link #shouldNotRewriteWhenReferenceHasNoReflectedCounterpart} already proves its `plainAttr` index answers.
	 *
	 * Category 1 claims products 1-5, whose `plainAttr` values (`productPk % 7`) are 1, 2, 3, 4 and 5. So `plainAttr`
	 * equal to 1 and `plainAttr` equal to 2 are carried by two **different** rows of the same category, and no single
	 * row carries both. The two readings of the constraint therefore give different answers, and the answer says
	 * which one the engine implements:
	 *
	 * - **cross-row** (each leaf OR-ed across every selected reduced index, conjuncted only afterwards) - six
	 *   categories, `{1, 2, 5, 6, 8, 9}`;
	 * - **per-row** (leaves conjuncted inside each referenced entity's index) - the empty set, since no row can hold
	 *   two different values of one attribute.
	 *
	 * **The measured answer is the empty set.** The control query below proves that is a semantic result and not an
	 * unpopulated index: the same attribute, same reference, one leaf instead of two, answers normally.
	 *
	 * Two consequences. `collectAttributeNames`' JavaDoc in `src/main` states the cross-row reading and uses it to
	 * justify excluding `and` from the rewrite - that justification is not what the engine does. The exclusion may
	 * still be correct on other grounds, but a reader will otherwise inherit a confident claim that measurement
	 * contradicts. And {@link #shouldNotRewriteWhenTwoAttributeSiblingsFormAnImplicitConjunction} is a MUST-PASS row
	 * again, once its expectation is derived per row rather than across rows.
	 *
	 * Keep the empty assertion explicit rather than routing it through `AssertionUtils#assertResultIs`, which
	 * refuses an empty expectation by design - and keep the control, because without it an empty answer and a broken
	 * index are the same observation.
	 *
	 * The channel is read through {@link #assertPlanningShortCircuited} rather than
	 * {@link #assertReferenceIndexOptionRegistered}, for the same reason as the absent-counterpart row: with no
	 * referenced entity satisfying both leaves, index selection registers an **empty** reference option,
	 * `IndexSelectionResult#isEmpty` becomes true and the planner returns before pushing a single
	 * `PLANNING_FILTER_ALTERNATIVE` step. That short circuit is the per-row answer showing up one layer earlier
	 * than the result does - the type-level pass already found no row carrying both values.
	 */
	@DisplayName("Should match two attribute siblings within one reference row rather than across rows")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldMatchTwoAttributeSiblingsWithinOneRowRatherThanAcrossRows(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		final long firstPlainValue = 1L;
		final long secondPlainValue = 2L;
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PLAIN_PRODUCTS,
								attributeEquals(REF_ATTR_PLAIN, firstPlainValue),
								attributeEquals(REF_ATTR_PLAIN, secondPlainValue)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);

				// the control - one leaf, same attribute, same reference: proves the index answers at all
				final EvitaResponse<EntityReference> control = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							referenceHaving(
								REF_CATEGORY_PLAIN_PRODUCTS,
								attributeEquals(REF_ATTR_PLAIN, firstPlainValue)
							)
						),
						indexScanRequirements()
					),
					EntityReference.class
				);
				AssertionUtils.assertResultIs(
					"The single-leaf control must answer - if it does not, the empty result below says nothing about " +
						"sibling semantics and only says the attribute index is unreachable!",
					originalCategories,
					liveOwnerWithRow(
						REF_CATEGORY_PLAIN_PRODUCTS, row -> longAttributeIs(row, REF_ATTR_PLAIN, firstPlainValue)
					),
					control.getRecordData()
				);

				// the cross-row reading would have answered - assert that, so the empty result is a real signal
				final Predicate<SealedEntity> carriesFirstValue = liveOwnerWithRow(
					REF_CATEGORY_PLAIN_PRODUCTS, row -> longAttributeIs(row, REF_ATTR_PLAIN, firstPlainValue)
				);
				final Predicate<SealedEntity> carriesSecondValue = liveOwnerWithRow(
					REF_CATEGORY_PLAIN_PRODUCTS, row -> longAttributeIs(row, REF_ATTR_PLAIN, secondPlainValue)
				);
				assertTrue(
					originalCategories.stream().anyMatch(carriesFirstValue.and(carriesSecondValue)),
					"The fixture must contain a category carrying the two values on two different rows, or this row " +
						"cannot tell the two readings apart!"
				);
				assertTrue(
					response.getRecordData().isEmpty(),
					"Two attribute siblings are matched within a single reference row, and no row can hold two " +
						"different values of one attribute - so the answer must be empty but was " +
						response.getRecordData() + "!"
				);
				// no reference option exists to read: the type-level evaluation matched no referenced entity, so index
				// selection registered an empty option and the planner returned before planning a filter at all -
				// which is itself the per-row result, observed one layer earlier
				assertPlanningShortCircuited(response);
			}
		);
	}

	/* --------------------------------------------------------------------------------------------------------- */
	/* Observation channel                                                                                         */
	/* --------------------------------------------------------------------------------------------------------- */

	/**
	 * Reads the single channel this whole class depends on: whether index selection registered the reference
	 * `TargetIndexes` option for the query's `referenceHaving`.
	 *
	 * `BidirectionalReferenceRewriter#isApplicable` is consulted at the top of
	 * `IndexSelectionVisitor#addReferenceIndexOption`; when it says yes the method returns before registering
	 * anything, so the option - and with it exactly one `PLANNING_FILTER_ALTERNATIVE` telemetry step argument
	 * beginning with {@link #REFERENCE_INDEX_OPTION_PREFIX} - disappears from the plan.
	 *
	 * The whole argument *set* is collected, never `PLANNING_FILTER`'s single `Selected index: ...` argument, which
	 * names only the cheapest plan. Do not call this for a query carrying `hierarchyWithin(<referenceName>, ...)`:
	 * the hierarchy option is described with the identical prefix and would be indistinguishable.
	 *
	 * @param response the response whose `queryTelemetry()` extra result is read
	 * @param expected `true` when the reference option must be present (the rewrite declined), `false` when it must
	 *                 be absent (the rewrite fired)
	 */
	private static void assertReferenceIndexOptionRegistered(
		@Nonnull EvitaResponse<EntityReference> response,
		boolean expected
	) {
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		assertNotNull(
			telemetry,
			"Query telemetry must be present - it is the only channel this test class can read the planner from!"
		);
		final List<String> alternatives = new ArrayList<>(8);
		collectFilterAlternativeArguments(telemetry, alternatives);
		assertFalse(
			alternatives.isEmpty(),
			"The planner must register at least the global index option - an empty alternative list means the " +
				"telemetry shape changed and this channel no longer observes anything!"
		);
		final boolean registered = alternatives.stream().anyMatch(it -> it.startsWith(REFERENCE_INDEX_OPTION_PREFIX));
		assertEquals(
			expected, registered,
			(expected ?
				"The reference index option must be registered - the rewrite was expected to decline this shape!" :
				"The reference index option must be absent - the rewrite was expected to take this shape over!") +
				"\nRegistered planning alternatives:\n - " + String.join("\n - ", alternatives)
		);
	}

	/**
	 * Asserts the planner really did plan a filter, i.e. that index selection handed it a non-empty plan.
	 *
	 * This is the positive counterpart of {@link #assertPlanningShortCircuited} and it exists for one shape only:
	 * a query whose answer is empty. There, "no reference index option was registered" is ambiguous on its own -
	 * it is what a taken rewrite looks like, and equally what a short circuit looks like. Pairing the two readings
	 * is what pins the empty answer on the rewrite rather than on the planner giving up before it.
	 *
	 * @param response response whose telemetry is inspected
	 */
	private static void assertPlanningReachedTheFilter(@Nonnull EvitaResponse<EntityReference> response) {
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		assertNotNull(telemetry, "Query telemetry must be present - it is the only channel this class can read!");
		assertNotNull(
			findPhase(telemetry, QueryPhase.PLANNING_FILTER),
			"The planner must have reached filter planning - without a `PLANNING_FILTER` step the empty answer " +
				"came out of index selection, and the rewrite never ran at all!"
		);
	}

	/**
	 * Asserts the planner never reached filter planning at all, because index selection had already proved the answer
	 * empty.
	 *
	 * This is the observation that replaces the usual channel on a query whose owner-side discovery finds nothing.
	 * `IndexSelectionResult#isEmpty` is true when **any** registered `TargetIndexes` is empty, and `QueryPlanner`
	 * returns an empty plan on that condition *before* `createFilterFormula` runs - so no `PLANNING_FILTER` step and
	 * no `PLANNING_FILTER_ALTERNATIVE` steps are ever pushed, and
	 * {@link #assertReferenceIndexOptionRegistered} has nothing to read. Its own guard fires instead, which is what
	 * it is for.
	 *
	 * Asserting the short circuit is strictly stronger than asserting the option was registered and empty: it can
	 * only happen when `addReferenceIndexOption` registered a zero-index reference option, which in turn can only
	 * happen when the rewrite declined **and** the owner-side discovery came back empty - exactly the pair of facts
	 * this row exists to pin.
	 *
	 * @param response the response whose `queryTelemetry()` extra result is read
	 */
	private static void assertPlanningShortCircuited(@Nonnull EvitaResponse<EntityReference> response) {
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		assertNotNull(telemetry, "Query telemetry must be present - it is the only channel this class can read!");
		assertNotNull(
			findPhase(telemetry, QueryPhase.PLANNING_INDEX_USAGE),
			"Index selection must still have run - without it there is no short circuit to observe!"
		);
		assertNull(
			findPhase(telemetry, QueryPhase.PLANNING_FILTER),
			"The planner must have returned an empty plan straight from index selection, so no `PLANNING_FILTER` step " +
				"may exist - its presence means a non-empty reference option was registered and the row no longer " +
				"exercises a missing counterpart type index!"
		);
	}

	/**
	 * Walks the telemetry tree and returns the first step of the given phase, or `null` when the phase is absent.
	 *
	 * @param telemetry node to descend from
	 * @param phase     phase to look for
	 * @return the first matching step, or `null`
	 */
	@Nullable
	private static QueryTelemetry findPhase(@Nonnull QueryTelemetry telemetry, @Nonnull QueryPhase phase) {
		if (telemetry.getOperation() == phase) {
			return telemetry;
		}
		for (final QueryTelemetry step : telemetry.getSteps()) {
			final QueryTelemetry found = findPhase(step, phase);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	/**
	 * Walks the whole telemetry tree and collects the arguments of every `PLANNING_FILTER_ALTERNATIVE` step. The
	 * walk is deliberately not limited to the outermost `PLANNING_FILTER`: a nested query plans its own filter and
	 * its options belong in the same set, so a reference option appearing there would be seen rather than missed.
	 *
	 * @param telemetry node to descend from
	 * @param target    collecting list, appended to in place
	 */
	private static void collectFilterAlternativeArguments(
		@Nonnull QueryTelemetry telemetry,
		@Nonnull List<String> target
	) {
		if (telemetry.getOperation() == QueryPhase.PLANNING_FILTER_ALTERNATIVE) {
			Collections.addAll(target, telemetry.getArguments());
		}
		for (final QueryTelemetry step : telemetry.getSteps()) {
			collectFilterAlternativeArguments(step, target);
		}
	}

	/* --------------------------------------------------------------------------------------------------------- */
	/* Requirement blocks                                                                                          */
	/* --------------------------------------------------------------------------------------------------------- */

	/**
	 * The requirement block of every row that claims to exercise an index path: telemetry so the channel can be
	 * read, `VERIFY_POSSIBLE_CACHING_TREES` so the cacheable variants of the rewritten tree are executed and
	 * compared, `PREFER_INDEX_SCAN` because a 258-entity fixture is otherwise answered from prefetched entity bodies
	 * and neither the rewrite nor the owner-side translator runs, and an unbounded page so the result is the whole
	 * answer rather than its first page.
	 *
	 * @return the shared requirement block
	 */
	@Nonnull
	private static Require indexScanRequirements() {
		return observingRequirements(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.PREFER_INDEX_SCAN);
	}

	/**
	 * Variant of {@link #indexScanRequirements()} for the rows that deliberately choose their own debug modes.
	 *
	 * @param debugModes debug modes to enable for the query
	 * @return the requirement block
	 */
	@Nonnull
	private static Require observingRequirements(@Nonnull DebugMode... debugModes) {
		return require(
			queryTelemetry(),
			debug(debugModes),
			page(1, Integer.MAX_VALUE)
		);
	}

	/* --------------------------------------------------------------------------------------------------------- */
	/* Expected-set predicates                                                                                     */
	/* --------------------------------------------------------------------------------------------------------- */

	/**
	 * Variant of {@link #ownerWithRow} that also constrains the *product* the row belongs to - used by the rows
	 * carrying an `entityHaving` or an `entityPrimaryKeyInSet`. Always evaluated in the live scope, which is the
	 * only scope those rows query.
	 *
	 * Those rows need a product-driven oracle rather than the body-driven one, because a nested query or an
	 * `entityPrimaryKeyInSet` narrows the answer by the *referenced* entity: an owner whose only matching rows point
	 * at archived products is correctly absent from a live query, and only walking the products can see that.
	 *
	 * @param originalProducts   all products of the fixture, both scopes, fully fetched
	 * @param productFilter      condition the referencing product has to satisfy
	 * @param referenceRowFilter condition the `PRODUCT.categories` row has to satisfy
	 * @return predicate over the original categories
	 */
	@Nonnull
	private static Predicate<SealedEntity> categoryReachedByLiveProduct(
		@Nonnull List<SealedEntity> originalProducts,
		@Nonnull Predicate<SealedEntity> productFilter,
		@Nonnull Predicate<ReferenceContract> referenceRowFilter
	) {
		return category -> category.getScope() == Scope.LIVE &&
			originalProducts.stream()
				.filter(product -> product.getScope() == Scope.LIVE)
				.filter(productFilter)
				.anyMatch(
					product -> product.getReferences(REF_PRODUCT_CATEGORIES)
						.stream()
						.anyMatch(
							row -> row.getReferencedPrimaryKey() == category.getPrimaryKeyOrThrowException() &&
								referenceRowFilter.test(row)
						)
				);
	}

	/**
	 * The primary expected-set oracle of this class: an owner in one of the requested scopes whose **own entity
	 * body** carries a matching row of the named reference.
	 *
	 * This is deliberately derived from the data rather than from either evaluation path, because on archived data
	 * the two paths do not agree and neither can be used to check the other - see the class JavaDoc. The body is the
	 * only description of the relation that both paths are supposed to answer about, so it is what the expectations
	 * are computed from.
	 *
	 * @param scopes             scopes the query asks for; the owner must live in one of them
	 * @param referenceName      reference whose rows are inspected on the owner entity
	 * @param referenceRowFilter condition the row has to satisfy
	 * @return predicate over the original owner entities
	 */
	@Nonnull
	private static Predicate<SealedEntity> ownerWithRow(
		@Nonnull Set<Scope> scopes,
		@Nonnull String referenceName,
		@Nonnull Predicate<ReferenceContract> referenceRowFilter
	) {
		return owner -> scopes.contains(owner.getScope()) &&
			owner.getReferences(referenceName).stream().anyMatch(referenceRowFilter);
	}

	/**
	 * The complement of {@link #liveOwnerWithRow}: a live owner **none** of whose rows of the named reference match.
	 *
	 * This is the documented meaning of a `not` nested inside a `referenceHaving` - "no discovered row satisfies the
	 * constraint" - and it is deliberately expressed as its own helper rather than as a negated lambda, so that the
	 * quantifier being asserted is visible at the call site.
	 *
	 * @param referenceName      reference whose rows are inspected on the owner entity
	 * @param referenceRowFilter condition **no** row may satisfy
	 * @return predicate over the original owner entities
	 */
	@Nonnull
	private static Predicate<SealedEntity> liveOwnerWithoutRow(
		@Nonnull String referenceName,
		@Nonnull Predicate<ReferenceContract> referenceRowFilter
	) {
		return owner -> owner.getScope() == Scope.LIVE &&
			owner.getReferences(referenceName).stream().noneMatch(referenceRowFilter);
	}

	/**
	 * {@link #ownerWithRow} restricted to the live scope - the scope every row that names no `scope` constraint
	 * runs in.
	 *
	 * @param referenceName      reference whose rows are inspected on the owner entity
	 * @param referenceRowFilter condition the row has to satisfy
	 * @return predicate over the original owner entities
	 */
	@Nonnull
	private static Predicate<SealedEntity> liveOwnerWithRow(
		@Nonnull String referenceName,
		@Nonnull Predicate<ReferenceContract> referenceRowFilter
	) {
		return ownerWithRow(LIVE_ONLY, referenceName, referenceRowFilter);
	}

	/**
	 * Tells whether the reference row carries the expected `relevance` value.
	 *
	 * @param row      reference row to inspect
	 * @param expected expected value
	 * @return true when the row's `relevance` equals `expected`
	 */
	private static boolean relevanceIs(@Nonnull ReferenceContract row, long expected) {
		return longAttributeIs(row, REF_ATTR_RELEVANCE, expected);
	}

	/**
	 * Tells whether the reference row carries the expected `scopedGrade` value.
	 *
	 * @param row      reference row to inspect
	 * @param expected expected value
	 * @return true when the row's `scopedGrade` equals `expected`
	 */
	private static boolean scopedGradeIs(@Nonnull ReferenceContract row, long expected) {
		return longAttributeIs(row, REF_ATTR_SCOPED_GRADE, expected);
	}

	/**
	 * Tells whether the reference row carries the expected value in the named `Long` attribute. A row that does not
	 * carry the attribute at all (`refSometimesSet` is written for every third product only) simply does not match.
	 *
	 * @param row           reference row to inspect
	 * @param attributeName attribute to read
	 * @param expected      expected value
	 * @return true when the attribute is present and equal to `expected`
	 */
	private static boolean longAttributeIs(
		@Nonnull ReferenceContract row,
		@Nonnull String attributeName,
		long expected
	) {
		final Long value = row.getAttribute(attributeName);
		return value != null && value == expected;
	}

	/**
	 * Oracle for the duplicate-cardinality rows: a LIVE category holding at least one `variantProducts` row tagged
	 * `expected` in its own entity body.
	 *
	 * @param expected value of the representative `variantTag` attribute
	 * @return predicate over the original categories
	 */
	@Nonnull
	private static Predicate<SealedEntity> carriesVariantTag(@Nonnull String expected) {
		return liveOwnerWithRow(
			REF_CATEGORY_VARIANT_PRODUCTS, row -> expected.equals(row.getAttribute(REF_ATTR_VARIANT_TAG))
		);
	}

	/**
	 * Counts the category's `variantProducts` rows carrying the given tag. Used only by fixture guards, to assert
	 * that an owner really is reached through more than one duplicate row before a row claims to exercise that.
	 *
	 * @param category category to inspect
	 * @param expected value of the representative `variantTag` attribute
	 * @return number of matching rows
	 */
	private static long variantRowsWithTag(@Nonnull SealedEntity category, @Nonnull String expected) {
		return category.getReferences(REF_CATEGORY_VARIANT_PRODUCTS).stream()
			.filter(row -> expected.equals(row.getAttribute(REF_ATTR_VARIANT_TAG)))
			.count();
	}

	/* --------------------------------------------------------------------------------------------------------- */
	/* Ordering assertions                                                                                         */
	/* --------------------------------------------------------------------------------------------------------- */

	/**
	 * Asserts the returned categories are ordered by the `relevance` of their `PRODUCT.categories` rows, strictly
	 * ascending. All rows of one category carry the same `relevance` in this fixture, so the value per category is
	 * unambiguous, and the caller's filter leaves a set whose values are pairwise distinct - which is what makes
	 * "strictly increasing" a complete pin of the permutation rather than a tie-break-dependent guess.
	 *
	 * @param response         response whose record order is checked
	 * @param originalProducts all products of the fixture, used to resolve each category's row value
	 */
	private static void assertRelevanceStrictlyIncreasing(
		@Nonnull EvitaResponse<EntityReference> response,
		@Nonnull List<SealedEntity> originalProducts
	) {
		long previous = Long.MIN_VALUE;
		for (final EntityReference record : response.getRecordData()) {
			final long current = rowAttributeOfCategory(
				originalProducts, record.getPrimaryKeyOrThrowException(), REF_ATTR_RELEVANCE
			);
			assertTrue(
				current > previous,
				"Categories must be ordered by the `" + REF_ATTR_RELEVANCE + "` of their `" +
					REF_CATEGORY_PRODUCTS + "` rows and the five values in this answer are pairwise distinct, but " +
					previous + " was followed by " + current + "!"
			);
			previous = current;
		}
	}

	/**
	 * Resolves the value a category's `PRODUCT.categories` rows carry in the named `Long` attribute, reading it off
	 * the live products that reference it.
	 *
	 * @param originalProducts all products of the fixture
	 * @param categoryPk       category to resolve the value for
	 * @param attributeName    reference attribute to read
	 * @return the smallest value found, which in this fixture is also the only one
	 */
	private static long rowAttributeOfCategory(
		@Nonnull List<SealedEntity> originalProducts,
		int categoryPk,
		@Nonnull String attributeName
	) {
		return originalProducts.stream()
			.filter(product -> product.getScope() == Scope.LIVE)
			.flatMap(product -> product.getReferences(REF_PRODUCT_CATEGORIES).stream())
			.filter(row -> row.getReferencedPrimaryKey() == categoryPk)
			.map(row -> row.<Long>getAttribute(attributeName))
			.filter(Objects::nonNull)
			.mapToLong(Long::longValue)
			.min()
			.orElseThrow(
				() -> new GenericEvitaInternalError(
					"Category " + categoryPk + " carries no live `" + REF_PRODUCT_CATEGORIES + "` row holding `" +
						attributeName + "` - the fixture changed under this test!"
				)
			);
	}

}
