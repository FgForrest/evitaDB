/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.fromRoot;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfReference;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithin;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinSelf;
import static io.evitadb.api.query.QueryConstraints.or;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceSummary;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.statistics;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the correctness defects an adversarial review confirmed in the bidirectional reference rewrite
 * (`BidirectionalReferenceRewriter`, `ReferencedOwnerExistenceFormula`, and the hook in
 * `ReferenceHavingTranslator#translate`).
 *
 * **Most rows in this class are expected to fail on today's code.** They are regression tests for defects that
 * are deliberately unfixed; a green run on one of them means the test is wrong, not that the engine is right.
 * Two rows are exceptions and must pass: `shouldAnswerIdenticallyWithAndWithoutTheDeclineKnob`, which validates
 * the paired-query oracle the other rows are built on, and
 * `shouldKeepTheResultInsideTheSubtreeWhenTheReferenceIsRewritten`, which pins a property that holds today and
 * must keep holding.
 *
 * Two mechanisms produce every row here:
 *
 * 1. **Which scope the counterpart is read in.** The owner-side evaluation reads the owner's own reference
 *    family, which carries cross-scope rows; the rewrite collects candidate owners from the *target* type index
 *    resolved with the **owner's** requested scope. A relation spanning two scopes is therefore visible to one
 *    path and invisible to the other. `shouldAgreeWithTheOwnerSidePathWhenCounterpartRowsLiveInAnotherScope` is
 *    the confirmed instance of this, and the row that validates the fix.
 *    `shouldNotLoseArchivedOwnersWhoseTargetsLiveInAnotherScope` looks like its twin but is **not** a rewrite
 *    defect at all - under `scope(ARCHIVED)` the rewrite cannot even fire, because a reflected reference gets no
 *    ARCHIVED type index; see that row's own documentation.
 * 2. **What the wrapper exposes as a visible child.** The ordinary path hides the nested `entityHaving` plan
 *    behind a terminal `DeferredFormula` whose child array is empty; the rewrite installs it as the visible inner
 *    formula of `ReferencedOwnerExistenceFormula`, so every consumer that walks the planned filter tree now
 *    reaches formulas from a foreign namespace.
 *
 * Every paired row uses the same decline knob: an `entityPrimaryKeyInSet` listing **every** primary key of the
 * referenced collection, including the archived ones. `BidirectionalReferenceRewriter#splitChildren` rejects an
 * `entityPrimaryKeyInSet` child outright, so the constraint falls through to the owner-side path, while
 * `ReferencedEntityIndexPrimaryKeyTranslatingFormula` intersects the key list with the referenced collection's
 * GLOBAL indexes across **all** scopes - which makes a full key list a semantic no-op. A LIVE-only key list would
 * silently drop owners whose only rows point at archived targets and break the pairing.
 *
 * **Deliberately absent: the R9 hierarchy-statistics row.** It is not constructible on this fixture. The
 * formula-tree strip (`ExtraResultPlanningVisitor#stripHierarchyMatching`) that would reach a nested
 * `HierarchyFormula` exposed by the rewrite runs only on the *shortcut* path, and
 * `ExtraResultPlanningVisitor#canUseShortcut` takes the fallback whenever the statistics reference is
 * `FOR_FILTERING_AND_PARTITIONING` in every requested scope - which `CATEGORY.taxonomy` is, by fixture contract,
 * precisely so the hierarchy alternative stays eligible for the row below. The fallback re-plans from the
 * constraint tree instead, and strips the nested `hierarchyWithin` identically in the rewritten and the declined
 * variant, so the pairing would agree for a reason that has nothing to do with the rewrite.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Bidirectional reference rewrite - confirmed correctness regressions")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(FILTER)
public class BidirectionalReferenceRewriteCorrectnessFunctionalTest
	extends AbstractBidirectionalReferenceRewriteFunctionalTest {

	/**
	 * Mirrors the private `BidirectionalReferenceRewriter#MINIMAL_GAIN`. The rewrite is taken only when
	 * `candidates * MINIMAL_GAIN <= buckets`; the decline-knob row asserts the strict opposite, to prove the shape
	 * it measures on cannot be rewritten with or without the knob.
	 */
	private static final int REWRITE_MINIMAL_GAIN = 4;
	/**
	 * Value of the inherited `relevance` reference attribute the decline-knob row filters on. The fixture sets it
	 * to `p % 5` on every `PRODUCT.categories` row, so this selects roughly a fifth of the collection - enough for
	 * the comparison to be non-vacuous, few enough that a knob which narrowed anything would show.
	 */
	private static final long PINNED_RELEVANCE = 2L;
	/**
	 * Root of the taxonomy subtree the hierarchy row filters on. The fixture declares `1 -> {2, 3}`, so the
	 * subtree covers taxonomy `{1, 2, 3}`.
	 */
	private static final int TAXONOMY_SUBTREE_ROOT_PK = 1;
	/**
	 * Output name the hierarchy-statistics row reads its `fromRoot` tree back under. Arbitrary, but it has to match
	 * between the query and the assertion.
	 */
	private static final String TAXONOMY_STATS_OUTPUT_NAME = "taxonomyStatsMenu";
	/**
	 * Categories the `node(1)` taxonomy subtree reaches, derived from the fixture contract's assignment:
	 * categories 1 and 2 point at taxonomy 2, categories 3 and 4 at taxonomy 3, and taxonomy `{2, 3}` are the
	 * children of taxonomy 1. Everything else points at taxonomy 5 or 6, which sit under taxonomy 4.
	 */
	private static final Set<Integer> NODE_ONE_CATEGORY_PKS = Set.of(1, 2, 3, 4);

	/**
	 * Oracle validation - not a test of the rewrite at all, but of the technique every paired row here rests on.
	 *
	 * `shouldAgreeWithTheOwnerSidePathWhenCounterpartRowsLiveInAnotherScope` and both facet rows compare a
	 * rewritten query against "the same question written so the rewrite declines", and the knob that makes it
	 * decline is an `entityPrimaryKeyInSet` listing **every** primary key of the referenced collection. If that
	 * knob narrowed, widened or reordered the answer, those rows would silently compare the wrong things and a
	 * green result would mean nothing.
	 *
	 * The shape below is chosen so the knob cannot be changing the *plan*. Asked from the product end the rewrite
	 * declines on the cost gate alone - the counterpart `CATEGORY.products` type index announces one candidate
	 * owner per referenced product, against the eleven reduced indexes `PRODUCT.categories` would visit, and
	 * `candidates * 4 <= buckets` is nowhere near true. Both variants therefore run the ordinary owner-side path,
	 * and any difference in the answer is the knob changing the **semantics**.
	 *
	 * Four sub-shapes:
	 *
	 * - a bare `referenceHaving`, where the ordinary path falls back to the reduced-index superset;
	 * - one carrying a reference-attribute constraint, so a real constraint formula is collected beside the knob;
	 * - `scope(LIVE, ARCHIVED)`, where the key list has to span both scopes;
	 * - `scope(ARCHIVED)`, which is the shape that actually bites: the archived products' only `categories` rows
	 *   point at category 11, which is **LIVE**, so a key list covering only the archived category would drop
	 *   every one of them. `scope(LIVE, ARCHIVED)` alone cannot show this, because there every owner is reachable
	 *   in some requested scope.
	 *
	 * The knob is reached twice inside a `referenceHaving` and only one of the two ignores it. During reduced-index
	 * evaluation it is genuinely inert - `ReferenceHavingTranslator` passes `EntityPrimaryKeyInSet.class` as
	 * a suppressed constraint and `FilterByVisitor#visit` returns before translating it. During index *discovery*
	 * it is translated normally, and is a no-op only because `ReferencedEntityIndexPrimaryKeyTranslatingFormula`
	 * intersects it with the referenced collection's GLOBAL indexes over `Scope.values()` - every scope, not the
	 * query's. Completeness of the key list is what makes it inert there, which is why the helper enumerates
	 * `1..CATEGORY_COUNT` rather than the live subset.
	 */
	@DisplayName("Should answer identically with and without the decline knob")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldAnswerIdenticallyWithAndWithoutTheDeclineKnob(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		// guard - the rewrite has to decline on this shape for reasons unrelated to the knob, or the row is not
		// isolating the knob at all but comparing two different plans
		assertRewriteDeclinesOnTheProductEnd(originalCategories, originalProducts, Scope.LIVE);
		assertRewriteDeclinesOnTheProductEnd(originalCategories, originalProducts, Scope.ARCHIVED);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertDeclineKnobIsInert(
					session, "bare referenceHaving, scope(LIVE)",
					new Scope[]{Scope.LIVE}, null
				);
				assertDeclineKnobIsInert(
					session, "referenceHaving with a reference-attribute constraint, scope(LIVE)",
					new Scope[]{Scope.LIVE}, attributeEquals(REF_ATTR_RELEVANCE, PINNED_RELEVANCE)
				);
				assertDeclineKnobIsInert(
					session, "bare referenceHaving, scope(LIVE, ARCHIVED)",
					new Scope[]{Scope.LIVE, Scope.ARCHIVED}, null
				);
				final List<Integer> archivedAnswer = assertDeclineKnobIsInert(
					session, "bare referenceHaving, scope(ARCHIVED)",
					new Scope[]{Scope.ARCHIVED}, null
				);

				// every archived product carries exactly one `categories` row and it points at a LIVE category, so
				// the archived answer is the whole archived block - a key list that failed to span the LIVE scope
				// would return nothing at all here
				assertEquals(
					entityPrimaryKeys(inScope(originalProducts, Scope.ARCHIVED)),
					new TreeSet<>(archivedAnswer),
					"Every archived product holds a `" + REF_PRODUCT_CATEGORIES + "` row, so scope(ARCHIVED) must " +
						"return the whole archived block"
				);
				return null;
			}
		);
	}

	/**
	 * An archived owner whose only reference rows point at targets living in the LIVE scope must still be returned
	 * by a `scope(ARCHIVED)` query.
	 *
	 * **This row does not test the bidirectional rewrite, and no fix to the rewriter will turn it green.** It pins
	 * a *pre-existing* reflected-reference indexing gap that predates this optimisation and is tracked separately.
	 * It is kept here because it is the archived-scope half of the cross-scope question the row below answers, and
	 * because the two rows have to move together when either bug is fixed.
	 *
	 * A census of the built catalog established that **a reflected reference gets no ARCHIVED type index at all**,
	 * in either collection, even when an archived entity carries its rows in its body - confirmed on
	 * `CATEGORY.products`, `PRODUCT.curatedBy` and `PRODUCT.crossScopeCuratedBy`, while every *original* reference
	 * on the very same archived entities does have one. Every rewritable relation has a reflected end, so under
	 * `scope(ARCHIVED)` the gate declines in both orientations: with the reflected end as counterpart
	 * `worthRewriting` returns on `counterpartTypeIndex.isEmpty()`, and with it as owner `ownerSideBuckets` stays
	 * NULL. The empty answer this row observes therefore comes out of the **ordinary owner-side path**, not out of
	 * `tryRewrite`.
	 *
	 * Expected result, derived from the fixture's data assignment rather than pinned:
	 *
	 * - category {@link #ARCHIVED_CATEGORY_PK} is ARCHIVED and products 1-10 reference it **in addition** to their
	 *   round-robin category, and all ten of those products are LIVE;
	 * - the archived products reference the cross-scope category and nothing else, so no archived product reaches
	 *   the archived category.
	 *
	 * The archived category therefore holds ten `products` rows, and a childless `referenceHaving` - which means
	 * nothing but "holds at least one row" - must match it. The guards below derive the expectation as "every
	 * ARCHIVED category some product points at", so the row keeps working if further archived categories are added
	 * to the fixture, and reports it as a fixture change rather than a failure if one of them gains rows.
	 */
	@Disabled(
		"Pins the correct behaviour of a pre-existing engine defect: a reflected reference on an archived owner gets no " +
		"ARCHIVED type index at all, so the rows this query needs were never built. Re-enable when issue #1583 is fixed " +
		"- the expectation here is already the right one."
	)
	@DisplayName("Should not lose an archived owner whose referenced targets live in another scope")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotLoseArchivedOwnersWhoseTargetsLiveInAnotherScope(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		// guard - the row is only meaningful while the fixture really is shaped this way
		final Set<Integer> archivedCategoryPks = entityPrimaryKeys(inScope(originalCategories, Scope.ARCHIVED));
		assertTrue(
			archivedCategoryPks.contains(ARCHIVED_CATEGORY_PK),
			"Fixture guard: category " + ARCHIVED_CATEGORY_PK + " must be ARCHIVED"
		);
		// the answer is "every archived category holding at least one `products` row" - derived, not pinned, so
		// that adding further archived categories to the fixture cannot silently change what this row asserts
		final Set<Integer> expectedPks = new TreeSet<>();
		for (final Integer archivedCategoryPk : archivedCategoryPks) {
			if (!primaryKeysOfProductsReferencing(originalProducts, archivedCategoryPk).isEmpty()) {
				expectedPks.add(archivedCategoryPk);
			}
		}
		assertEquals(
			Set.of(ARCHIVED_CATEGORY_PK), expectedPks,
			"Fixture guard: category " + ARCHIVED_CATEGORY_PK + " must be the only ARCHIVED category carrying a `" +
				REF_CATEGORY_PRODUCTS + "` row - any other archived category with rows changes what this row means"
		);
		final Set<Integer> productsReferencingArchivedCategory = primaryKeysOfProductsReferencing(
			originalProducts, ARCHIVED_CATEGORY_PK
		);
		assertFalse(
			productsReferencingArchivedCategory.isEmpty(),
			"Fixture guard: some product must reference category " + ARCHIVED_CATEGORY_PK
		);
		for (final Integer productPk : productsReferencingArchivedCategory) {
			assertTrue(
				productPk < FIRST_ARCHIVED_PRODUCT_PK,
				"Fixture guard: no ARCHIVED product may reference category " + ARCHIVED_CATEGORY_PK +
					" - product " + productPk + " does, which destroys the cross-scope asymmetry this row pins"
			);
		}

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					categoriesHoldingAnyProductQuery(Scope.ARCHIVED, false),
					EntityReference.class
				);
				assertEquals(
					expectedPks, resultPrimaryKeys(result.getRecordData()),
					"An archived owner whose reference rows all point at LIVE targets must still be returned - " +
						"the reflected reference has no ARCHIVED type index, which is a pre-existing indexing gap " +
						"and not something the counterpart rewrite can fix"
				);
				return null;
			}
		);
	}

	/**
	 * **The confirmed cross-scope defect of the rewrite, and the row that validates its fix.**
	 *
	 * Unlike the archived-scope row above - which observes a pre-existing indexing gap the rewriter cannot reach -
	 * this one runs where the rewrite genuinely fires, where the ordinary path is measurably *correct*, and where
	 * the rewritten answer is measurably wrong.
	 *
	 * The cross-scope category is LIVE and its only `products` rows come from the archived products, which makes it
	 * the mirror image of the archived category. Measured on the built catalog:
	 *
	 * - `PRODUCT.categories [LIVE]` announces the round-robin categories and the archived one, but **not** the
	 *   cross-scope category - its only owning products are archived, so they contribute nothing to the LIVE type
	 *   index;
	 * - `CATEGORY.products [LIVE]` carries 240 buckets, so the gate fires comfortably at `11 * 4 <= 240`;
	 * - the correct answer is every LIVE category holding at least one `products` row, the cross-scope category
	 *   included, because the reflected rows are maintained on the LIVE owner regardless of the targets' scope -
	 *   and the rewrite returns that set **minus the cross-scope category**.
	 *
	 * The row therefore states what it believes rather than only that two things agree: it asserts the derived
	 * expectation against the declined path (which must hold today) and against the rewritten path (which must not,
	 * until the rewrite resolves counterpart candidates across scopes), and keeps the paired comparison as a third
	 * assertion so the technique itself stays exercised.
	 *
	 * The archived category must be absent from every answer - it is an archived owner and the query asks for
	 * `scope(LIVE)`.
	 */
	@DisplayName("Should agree with the owner-side path when counterpart rows live in another scope")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldAgreeWithTheOwnerSidePathWhenCounterpartRowsLiveInAnotherScope(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		// guard - the cross-scope category must be reachable *only* through archived products, else the row pins
		// nothing
		final Set<Integer> productsReferencingCategoryEleven = primaryKeysOfProductsReferencing(
			originalProducts, CROSS_SCOPE_CATEGORY_PK
		);
		assertFalse(
			productsReferencingCategoryEleven.isEmpty(),
			"Fixture guard: some product must reference category " + CROSS_SCOPE_CATEGORY_PK
		);
		for (final Integer productPk : productsReferencingCategoryEleven) {
			assertTrue(
				productPk >= FIRST_ARCHIVED_PRODUCT_PK,
				"Fixture guard: only ARCHIVED products may reference category " + CROSS_SCOPE_CATEGORY_PK +
					" - product " + productPk + " is LIVE, which destroys the mirror this row pins"
			);
		}

		// the correct answer is every LIVE category holding at least one `products` row, whatever scope the owning
		// products live in - derived from the originals rather than pinned
		final Set<Integer> expectedPks = new TreeSet<>();
		for (final Integer liveCategoryPk : entityPrimaryKeys(inScope(originalCategories, Scope.LIVE))) {
			if (!primaryKeysOfProductsReferencing(originalProducts, liveCategoryPk).isEmpty()) {
				expectedPks.add(liveCategoryPk);
			}
		}
		assertTrue(
			expectedPks.contains(CROSS_SCOPE_CATEGORY_PK),
			"Fixture guard: the expectation must contain category " + CROSS_SCOPE_CATEGORY_PK +
				", which is the single key this row turns on"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> rewritten = resultPrimaryKeys(
					session.query(
						categoriesHoldingAnyProductQuery(Scope.LIVE, false),
						EntityReference.class
					).getRecordData()
				);
				final Set<Integer> declined = resultPrimaryKeys(
					session.query(
						categoriesHoldingAnyProductQuery(Scope.LIVE, true),
						EntityReference.class
					).getRecordData()
				);

				assertFalse(
					declined.contains(ARCHIVED_CATEGORY_PK),
					"An archived owner must never appear in a scope(LIVE) result"
				);
				assertFalse(
					rewritten.contains(ARCHIVED_CATEGORY_PK),
					"An archived owner must never appear in a scope(LIVE) result"
				);
				assertEquals(
					expectedPks, declined,
					"The ordinary owner-side path is the correct one here - if this fails, the defect is not the one " +
						"this row was written for and the pairing below proves nothing"
				);
				assertEquals(
					expectedPks, rewritten,
					"The rewrite drops category " + CROSS_SCOPE_CATEGORY_PK + ": it collects candidate owners from the `" +
						REF_PRODUCT_CATEGORIES + "` type index resolved in the *owner's* requested scope, where a category " +
						"whose only owning products are archived never appears"
				);
				assertEquals(
					declined, rewritten,
					"The rewritten path and the owner-side path must answer the same question identically"
				);
				return null;
			}
		);
	}

	/**
	 * R2 - a facet selected inside a nested `entityHaving` belongs to the **target** collection's namespace and
	 * must not be reported as requested on the identically named reference of the **owner**.
	 *
	 * The query selects `PRODUCT.brand#7`. The summary for `CATEGORY.brand` shares both the reference name
	 * `brand` and the facet primary key 7 with it, and nothing else - the two are unrelated selections in
	 * unrelated namespaces.
	 *
	 * The rewrite installs the nested plan as the visible inner formula of `ReferencedOwnerExistenceFormula`;
	 * `ReferenceSummaryOfReferenceTranslator#findOrCreateProducer` walks the whole filtering formula with
	 * `FormulaFinder` and groups every `FacetGroupFormula` it reaches **by reference name only**, so the nested
	 * `brand` selection is attributed to the owner's `brand` reference. The declined path hides the same plan
	 * behind a terminal `DeferredFormula`, which `FormulaFinder` cannot descend into.
	 *
	 * The oracle compares the `requested` flags only, never the entity results. The two paths may legitimately
	 * disagree on the entity result here for the *cross-scope* reason pinned by
	 * `shouldAgreeWithTheOwnerSidePathWhenCounterpartRowsLiveInAnotherScope` - archived products carry brand 7 as
	 * well - and folding both defects into one row would make neither of them diagnosable. The `requested` flags
	 * are read off the planned filtering formula, not off the result, so they are unaffected by that.
	 */
	@DisplayName("Should not report a nested target facet as requested on the owner")
	@UseDataSet(BIDI_REWRITE)
	@Tag(FACET)
	@Test
	void shouldNotReportANestedTargetFacetAsRequestedOnTheOwner(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertNestedFacetSelectionDoesNotLeakOntoTheOwner(
			evita, originalCategories, FacetStatisticsDepth.COUNTS
		);
	}

	/**
	 * R2 at `IMPACT` depth - `ReferenceSummaryProducer#isRequested` feeds the impact computation as well as the
	 * `requested` flag, so the same leak has to be pinned once more with impact statistics switched on.
	 */
	@DisplayName("Should not report a nested target facet as requested on the owner at impact depth")
	@UseDataSet(BIDI_REWRITE)
	@Tag(FACET)
	@Test
	void shouldNotReportANestedTargetFacetAsRequestedOnTheOwnerAtImpactDepth(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertNestedFacetSelectionDoesNotLeakOntoTheOwner(
			evita, originalCategories, FacetStatisticsDepth.IMPACT
		);
	}

	/**
	 * R9 - a hierarchy constraint nested inside `entityHaving` belongs to the **target** collection's plan and must
	 * not be stripped out of it by the owner's hierarchy statistics.
	 *
	 * `ExtraResultPlanningVisitor#stripHierarchyMatching*` erases every `HierarchyFormula` whose reference name
	 * matches the reference the statistics are computed for. That is correct for the owner's own hierarchy anchor,
	 * which the statistics have to be computed *without*; it is wrong for a `HierarchyFormula` belonging to the
	 * referenced collection that merely shares the name. The ordinary path never has to make the distinction,
	 * because it hides the nested plan behind a terminal `DeferredFormula`; the rewrite installs it as the visible
	 * inner formula of `ReferencedOwnerExistenceFormula`, and `FormulaCloner` walks straight into it.
	 *
	 * `CATEGORY.taxonomyStats` and `PRODUCT.taxonomyStats` share nothing but a name - neither is reflected, neither
	 * is the counterpart of the other. That is the whole point.
	 *
	 * **This row only works while `CATEGORY.taxonomyStats` is `FOR_FILTERING`.**
	 * `ExtraResultPlanningVisitor#canUseShortcut` runs the formula-tree strip only when the statistics reference is
	 * *not* `FOR_FILTERING_AND_PARTITIONING` in every requested scope; otherwise it takes the constraint-tree
	 * fallback, which rewrites the nested `hierarchyWithin` identically in both variants and makes the pairing agree
	 * for a reason unrelated to the rewrite. The first guard below reads that off the live schema and fails loudly
	 * if it ever changes - which is also why this row cannot be written against `CATEGORY.taxonomy`, whose
	 * partitioning R4 depends on.
	 *
	 * **The nested subtree has to narrow the reachable categories.** If every category holds at least one product
	 * inside the subtree, stripping the nested constraint changes nothing and the row passes whether or not the
	 * defect is present. The second guard asserts the narrowing and names the fixture property that provides it:
	 * a product assignment that keeps each category's product block inside one subtree. With categories 1-5 under
	 * `node(1)` and 6-10 under `node(4)`, the declined variant reaches categories 1-5 while the rewritten one
	 * reaches 1-10, so the per-node counts differ at every taxonomy node carrying one of categories 6-10.
	 *
	 * Depending on the shape of the nested plan the strip either widens the referenced set - the inflation the
	 * review predicted - or removes the wrapper's only inner formula, in which case
	 * `ReferencedOwnerExistenceFormula#getCloneWithInnerFormulas` hands back its absorbing instance
	 * (`FormulaCloner:227-237` keeps it, because it does not answer with `EmptyFormula`) and every count collapses
	 * to zero. Both are divergences from the declined pairing and both fail this row; the assertion does not care
	 * which.
	 */
	@DisplayName("Should not inflate hierarchy statistics from a nested hierarchy constraint")
	@UseDataSet(BIDI_REWRITE)
	@Tag(HIERARCHY)
	@Test
	void shouldNotInflateHierarchyStatisticsFromANestedHierarchyConstraint(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		final Set<Integer> liveCategoryPks = entityPrimaryKeys(inScope(originalCategories, Scope.LIVE));

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// guard 1 - the formula-tree strip only runs while the statistics reference is not partitioned in
				// every requested scope; partition it and this row silently stops testing anything
				final Optional<ReferenceSchemaContract> statisticsReference = session
					.getEntitySchemaOrThrowException(Entities.CATEGORY)
					.getReference(REF_TAXONOMY_STATS);
				assertTrue(
					statisticsReference.isPresent(),
					"Fixture guard: `" + REF_TAXONOMY_STATS + "` must be declared on " + Entities.CATEGORY
				);
				assertEquals(
					ReferenceIndexType.FOR_FILTERING,
					statisticsReference.get().getReferenceIndexType(Scope.LIVE),
					"Fixture guard: `" + Entities.CATEGORY + "." + REF_TAXONOMY_STATS + "` must stay FOR_FILTERING " +
						"- at FOR_FILTERING_AND_PARTITIONING `canUseShortcut` takes the constraint-tree fallback, " +
						"which strips the nested constraint in both variants and makes this row vacuous"
				);

				// resolve the subtree through the engine rather than restating the fixture's parent links
				final Set<Integer> subtreeTaxonomyPks = resultPrimaryKeys(
					session.query(
						query(
							collection(ENTITY_TAXONOMY),
							filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(TAXONOMY_SUBTREE_ROOT_PK))),
							require(page(1, Integer.MAX_VALUE))
						),
						EntityReference.class
					).getRecordData()
				);
				assertFalse(subtreeTaxonomyPks.isEmpty(), "Fixture guard: the taxonomy subtree must not be empty");

				// guard 2 - the nested constraint has to make a difference to the categories the filter reaches
				final Set<Integer> reachableWithinSubtree = new TreeSet<>();
				final Set<Integer> reachableAtAll = new TreeSet<>();
				for (final SealedEntity product : inScope(originalProducts, Scope.LIVE)) {
					boolean withinSubtree = false;
					for (final ReferenceContract statsReference : product.getReferences(REF_TAXONOMY_STATS)) {
						if (subtreeTaxonomyPks.contains(statsReference.getReferencedPrimaryKey())) {
							withinSubtree = true;
							break;
						}
					}
					for (final ReferenceContract categoryReference : product.getReferences(REF_PRODUCT_CATEGORIES)) {
						final int categoryPk = categoryReference.getReferencedPrimaryKey();
						if (!liveCategoryPks.contains(categoryPk)) {
							continue;
						}
						reachableAtAll.add(categoryPk);
						if (withinSubtree) {
							reachableWithinSubtree.add(categoryPk);
						}
					}
				}
				assertFalse(
					reachableWithinSubtree.isEmpty(),
					"Fixture guard: the nested subtree must reach at least one category"
				);
				assertTrue(
					reachableAtAll.containsAll(reachableWithinSubtree),
					"Fixture guard: the subtree-restricted categories must be a subset of the unrestricted ones"
				);
				assertNotEquals(
					reachableAtAll, reachableWithinSubtree,
					"Fixture guard: the nested `" + REF_TAXONOMY_STATS + "` subtree must narrow the categories the " +
						"reference filter reaches - both sides are " + reachableAtAll + ", so stripping the nested " +
						"constraint out of the exposed plan changes nothing and this row would pass whether or not " +
						"the defect is present. This is what happens when `" + Entities.PRODUCT + "." +
						REF_TAXONOMY_STATS + "` spreads each category's product block across the whole taxonomy; " +
						"the assignment has to keep a category's products inside a single subtree"
				);

				final Hierarchy rewrittenHierarchy = session.query(
					nestedHierarchyStatisticsQuery(false), EntityReference.class
				).getExtraResult(Hierarchy.class);
				final Hierarchy declinedHierarchy = session.query(
					nestedHierarchyStatisticsQuery(true), EntityReference.class
				).getExtraResult(Hierarchy.class);

				assertNotNull(rewrittenHierarchy, "The rewritten query must produce hierarchy statistics");
				assertNotNull(declinedHierarchy, "The declined query must produce hierarchy statistics");

				final Map<Integer, Integer> declinedCounts = queriedEntityCountByNode(declinedHierarchy);
				final Map<Integer, Integer> rewrittenCounts = queriedEntityCountByNode(rewrittenHierarchy);
				assertFalse(
					declinedCounts.isEmpty(),
					"Oracle guard: the declined pairing must report some hierarchy node, else the comparison is vacuous"
				);
				assertEquals(
					declinedCounts, rewrittenCounts,
					"A hierarchy constraint nested inside `entityHaving` belongs to " + Entities.PRODUCT +
						" - the owner's `" + REF_TAXONOMY_STATS + "` statistics must not strip it out of the " +
						"referenced collection's plan just because the two references share a name"
				);
				return null;
			}
		);
	}

	/**
	 * R4 - the one pin in this class rather than a regression.
	 *
	 * When the rewrite takes over a `referenceHaving`, `IndexSelectionVisitor#addReferenceIndexOption` returns
	 * before registering the reference `TargetIndexes`. `FilterByVisitor#isReferenceNotQueriedByOtherConstraints`
	 * consequently flips to TRUE and arms `HierarchyOptimizingPostProcessor`, whose mutator deletes every
	 * conjunctive `HierarchyFormula` once a flag-setting attribute predicate is seen. The result must stay inside
	 * the subtree regardless.
	 *
	 * Expected result, derived from the fixture contract's data assignment (§5.2, §5.3 and §5.4):
	 *
	 * - taxonomy `1 -> {2, 3}`, and categories 1, 2 point at taxonomy 2 while categories 3, 4 point at taxonomy 3,
	 *   so `hierarchyWithin(taxonomy, node(1))` reaches categories `{1, 2, 3, 4}`;
	 * - `active` is TRUE on all twelve categories, so the attribute predicate removes nothing;
	 * - `productActive` is `p % 2 == 1`, and `PRODUCT.categories` assigns `((p - 1) % 10) + 1`, so an **odd**
	 *   product always lands on an **odd** category - categories `{1, 3, 5, 7, 9}` are the only ones holding a row
	 *   to an active product.
	 *
	 * The intersection is `{1, 3}`, and `{1, 3}` is a proper subset of the subtree - a widening bug would surface
	 * categories 5, 7 or 9, all of which match the reference and the attribute but sit outside `node(1)`.
	 *
	 * Two observation traps, both already adjudicated:
	 *
	 * - the telemetry channel cannot see which plan was taken here, because the hierarchy option and the reference
	 *   option publish the identical `Index type: REFERENCED_ENTITY composed of N indexes` step description;
	 * - `DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS` sidesteps that entirely: with the hierarchy alternative
	 *   eligible the planner executes **every** plan and throws on any mismatch, whichever one it would have
	 *   selected.
	 *
	 * The hierarchy alternative is only eligible while its accumulated cardinality stays at or below half the
	 * LIVE global cardinality. The fixture's taxonomy assignment exists solely to satisfy that - four categories
	 * out of eleven LIVE ones, against a threshold of `11 / 2 = 5`. The guard below asserts that shape; **if it
	 * moves, this row goes vacuous** because the hierarchy plan is skipped and there is no second plan to compare
	 * against.
	 */
	@DisplayName("Should keep the result inside the subtree when the reference is rewritten")
	@UseDataSet(BIDI_REWRITE)
	@Tag(HIERARCHY)
	@Test
	void shouldKeepTheResultInsideTheSubtreeWhenTheReferenceIsRewritten(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		final Set<Integer> liveCategoryPks = entityPrimaryKeys(inScope(originalCategories, Scope.LIVE));

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// resolve the subtree through the engine rather than restating the fixture's parent links
				final Set<Integer> subtreeTaxonomyPks = resultPrimaryKeys(
					session.query(
						query(
							collection(ENTITY_TAXONOMY),
							filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(TAXONOMY_SUBTREE_ROOT_PK))),
							require(page(1, Integer.MAX_VALUE))
						),
						EntityReference.class
					).getRecordData()
				);
				assertEquals(
					TAXONOMY_SUBTREE_OF_NODE_1, subtreeTaxonomyPks,
					"Fixture guard: the taxonomy hierarchy must place exactly " + TAXONOMY_SUBTREE_OF_NODE_1 +
						" under node(" + TAXONOMY_SUBTREE_ROOT_PK + ")"
				);

				final Set<Integer> subtreeCategoryPks = new TreeSet<>();
				for (final SealedEntity category : originalCategories) {
					if (category.getScope() != Scope.LIVE) {
						continue;
					}
					for (final ReferenceContract taxonomyReference : category.getReferences(REF_CATEGORY_TAXONOMY)) {
						if (subtreeTaxonomyPks.contains(taxonomyReference.getReferencedPrimaryKey())) {
							subtreeCategoryPks.add(category.getPrimaryKeyOrThrowException());
							break;
						}
					}
				}

				// guard - the eligibility arithmetic the whole row rests on
				assertEquals(
					NODE_ONE_CATEGORY_PKS, subtreeCategoryPks,
					"Fixture guard: the taxonomy assignment must place exactly categories " +
						NODE_ONE_CATEGORY_PKS + " under node(" + TAXONOMY_SUBTREE_ROOT_PK + ")"
				);
				assertTrue(
					subtreeCategoryPks.size() <= liveCategoryPks.size() / 2,
					"Fixture guard: the hierarchy alternative needs at most half the LIVE global cardinality (" +
						subtreeCategoryPks.size() + " of " + liveCategoryPks.size() + ") or it is skipped as " +
						"HIGH_CARDINALITY and this row compares a single plan against itself"
				);

				final Set<Integer> expectedPks = new TreeSet<>();
				for (final SealedEntity category : originalCategories) {
					if (!subtreeCategoryPks.contains(category.getPrimaryKeyOrThrowException())) {
						continue;
					}
					if (!Boolean.TRUE.equals(category.getAttribute(ATTR_ACTIVE, Boolean.class))) {
						continue;
					}
					if (holdsRowToAnActiveLiveProduct(originalProducts, category.getPrimaryKeyOrThrowException())) {
						expectedPks.add(category.getPrimaryKeyOrThrowException());
					}
				}
				assertFalse(
					expectedPks.isEmpty(),
					"Fixture guard: the row must expect a non-empty result or it proves nothing"
				);

				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								hierarchyWithin(
									REF_CATEGORY_TAXONOMY, entityPrimaryKeyInSet(TAXONOMY_SUBTREE_ROOT_PK)
								),
								attributeEquals(ATTR_ACTIVE, true),
								referenceHaving(
									REF_CATEGORY_PRODUCTS,
									entityHaving(attributeEquals(ATTR_PRODUCT_ACTIVE, true))
								)
							)
						),
						require(
							debug(
								DebugMode.VERIFY_POSSIBLE_CACHING_TREES,
								DebugMode.PREFER_INDEX_SCAN,
								DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS
							),
							page(1, Integer.MAX_VALUE)
						)
					),
					EntityReference.class
				);

				final Set<Integer> actualPks = resultPrimaryKeys(result.getRecordData());
				assertTrue(
					subtreeCategoryPks.containsAll(actualPks),
					"A rewritten reference must not widen the result past the hierarchy subtree " +
						subtreeCategoryPks + " - got " + actualPks
				);
				assertEquals(
					expectedPks, actualPks,
					"The rewritten plan must answer the subtree question exactly"
				);
				return null;
			}
		);
	}

	/**
	 * The duplicate-cardinality rewrite must answer exactly what the ordinary owner-side path answers.
	 *
	 * Duplicates were a hard decline until the by-primary-key index lookup was scoped to the collection the keys
	 * came from. The concern that kept them declined was never the cost model but whether the *two ends line up* -
	 * whether the reflected side mirrors every duplicate row with its own per-row reference attributes. That is not
	 * a question a plan-shape assertion can answer, so this row answers it by comparison: the same question asked
	 * twice, once rewritten and once forced onto the owner-side path by the decline knob, has to come back with the
	 * same owners.
	 *
	 * The knob is an `entityPrimaryKeyInSet` over the whole referenced collection, which `splitChildren` refuses
	 * outright - see {@link #shouldAnswerIdenticallyWithAndWithoutTheDeclineKnob} for why a *complete* key list is
	 * the only inert one.
	 *
	 * Three sub-shapes, all on `CATEGORY.variantProducts`: each of the two tags the fixture writes, and the pure
	 * `Or` of both. Tag `a` is the one that reaches an owner through several duplicate rows at once; tag `b`
	 * reaches a single category; the disjunction spans two duplicate rows of one `(owner, referenced)` pair.
	 *
	 * **What the cost-gate guard below cannot catch.** It proves the shape is economically *worth* rewriting, not
	 * that the rewrite was actually taken - re-introducing a decline for some unrelated reason would leave this row
	 * comparing the owner-side path against itself, green and vacuous. Measured: with the duplicate gate restored,
	 * every assertion here still passes. That is tolerable only because it cannot happen quietly - the same
	 * condition turns four rows of `BidirectionalReferenceRewriteFunctionalTest` red, starting with
	 * `shouldRewriteWhenTheReferenceCardinalityAllowsDuplicates`, which reads the planner's own alternative list.
	 * Those rows own the "did it rewrite" question; this one owns "did it rewrite *correctly*".
	 */
	@DisplayName("Should answer a duplicate reference identically with and without the decline knob")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldAnswerADuplicateReferenceIdenticallyWithAndWithoutTheDeclineKnob(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts
	) {
		// guard - the plain variant has to clear the cost gate, or both variants run the same owner-side plan and
		// the comparison proves nothing. `candidates` are the distinct categories `PRODUCT.variants` announces;
		// `buckets` counts the distinct products `CATEGORY.variantProducts` points at, which under duplicates is a
		// LOWER bound on the reduced indexes the owner side would visit (one per duplicate row, not one per
		// referenced entity) - so a guard that holds on this count holds on the real one too.
		final int candidates = referencedPrimaryKeys(
			inScope(originalProducts, Scope.LIVE), REF_PRODUCT_VARIANTS
		).size();
		final int buckets = referencedPrimaryKeys(
			inScope(originalCategories, Scope.LIVE), REF_CATEGORY_VARIANT_PRODUCTS
		).size();
		assertTrue(
			(long) candidates * REWRITE_MINIMAL_GAIN <= buckets,
			"Fixture guard: the duplicate shape must clear the cost gate (" + candidates + " candidates * " +
				REWRITE_MINIMAL_GAIN + " <= " + buckets + " buckets), otherwise the rewritten variant is not " +
				"rewritten at all and this row compares a plan against itself"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertDuplicateDeclineKnobIsInert(
					session, "single tag reaching one owner through several duplicate rows",
					attributeEquals(REF_ATTR_VARIANT_TAG, "a")
				);
				assertDuplicateDeclineKnobIsInert(
					session, "single tag reaching exactly one owner",
					attributeEquals(REF_ATTR_VARIANT_TAG, "b")
				);
				assertDuplicateDeclineKnobIsInert(
					session, "pure Or spanning two duplicate rows of one pair",
					or(
						attributeEquals(REF_ATTR_VARIANT_TAG, "a"),
						attributeEquals(REF_ATTR_VARIANT_TAG, "b")
					)
				);
				return null;
			}
		);
	}

	/**
	 * Runs one duplicate-reference sub-shape twice - once rewritten, once with the decline knob - and asserts the
	 * two answers are identical down to their order.
	 *
	 * @param session                      open read session
	 * @param shapeDescription             readable name of the sub-shape, used in the failure message
	 * @param referenceAttributeConstraint constraint carried inside the `referenceHaving`
	 */
	private static void assertDuplicateDeclineKnobIsInert(
		@Nonnull EvitaSessionContract session,
		@Nonnull String shapeDescription,
		@Nonnull FilterConstraint referenceAttributeConstraint
	) {
		final List<Integer> rewritten = resultPrimaryKeyOrder(
			session.query(
				categoriesHoldingVariantRowQuery(referenceAttributeConstraint, false),
				EntityReference.class
			).getRecordData()
		);
		final List<Integer> declined = resultPrimaryKeyOrder(
			session.query(
				categoriesHoldingVariantRowQuery(referenceAttributeConstraint, true),
				EntityReference.class
			).getRecordData()
		);
		assertFalse(
			rewritten.isEmpty(),
			"Oracle guard: `" + shapeDescription + "` must answer something, otherwise the comparison is vacuous"
		);
		assertEquals(
			declined, rewritten,
			"The duplicate-cardinality rewrite answered `" + shapeDescription + "` differently from the ordinary " +
				"owner-side path - the two ends of the pair do not line up row for row"
		);
	}

	/**
	 * Builds `collection(CATEGORY) + referenceHaving(variantProducts, ...)`, optionally carrying the decline knob.
	 *
	 * @param referenceAttributeConstraint constraint carried inside the `referenceHaving`
	 * @param withDeclineKnob              whether to add the `entityPrimaryKeyInSet` that forces the decline
	 * @return the query
	 */
	@Nonnull
	private static Query categoriesHoldingVariantRowQuery(
		@Nonnull FilterConstraint referenceAttributeConstraint,
		boolean withDeclineKnob
	) {
		final List<FilterConstraint> children = new ArrayList<>(2);
		children.add(referenceAttributeConstraint);
		if (withDeclineKnob) {
			children.add(declineKnobOverEveryKeyUpTo(PRODUCT_COUNT));
		}
		return query(
			collection(Entities.CATEGORY),
			filterBy(
				referenceHaving(REF_CATEGORY_VARIANT_PRODUCTS, children.toArray(FilterConstraint[]::new))
			),
			require(
				debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.PREFER_INDEX_SCAN),
				page(1, Integer.MAX_VALUE)
			)
		);
	}

	/**
	 * Runs the nested facet selection at the requested statistics depth through both the rewritten and the
	 * declined plan, and asserts that neither the whole set of `requested` flags nor the single flag the defect
	 * flips differs between them.
	 *
	 * @param evita              embedded database the fixture was built in
	 * @param originalCategories every category, both scopes, fully fetched
	 * @param depth              statistics depth the summary is computed at
	 */
	private static void assertNestedFacetSelectionDoesNotLeakOntoTheOwner(
		@Nonnull Evita evita,
		@Nonnull List<SealedEntity> originalCategories,
		@Nonnull FacetStatisticsDepth depth
	) {
		// guard - facet 7 has to exist in the owner's namespace, else its `requested` flag is unobservable
		assertTrue(
			referencedPrimaryKeys(originalCategories, REF_CATEGORY_BRAND).contains(SHARED_BRAND_PK),
			"Fixture guard: at least one category must reference brand " + SHARED_BRAND_PK +
				" or the facet never appears in the " + REF_CATEGORY_BRAND + " summary"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> rewrittenResult = session.query(
					nestedFacetSelectionQuery(depth, false), EntityReference.class
				);
				final EvitaResponse<EntityReference> declinedResult = session.query(
					nestedFacetSelectionQuery(depth, true), EntityReference.class
				);
				final ReferenceSummary rewrittenSummary = rewrittenResult.getExtraResult(ReferenceSummary.class);
				final ReferenceSummary declinedSummary = declinedResult.getExtraResult(ReferenceSummary.class);

				assertNotNull(rewrittenSummary, "The rewritten query must produce a facet summary");
				assertNotNull(declinedSummary, "The declined query must produce a facet summary");

				final FacetStatistics ownerFacet = facetStatisticsOf(
					rewrittenSummary, REF_CATEGORY_BRAND, SHARED_BRAND_PK
				);
				assertNotNull(
					ownerFacet,
					"Fixture guard: facet " + SHARED_BRAND_PK + " must appear in the " + REF_CATEGORY_BRAND +
						" summary or this row asserts nothing"
				);
				assertFalse(
					ownerFacet.isRequested(),
					"Facet " + SHARED_BRAND_PK + " of " + REF_CATEGORY_BRAND + " was never selected - the query " +
						"selected " + REF_PRODUCT_BRAND + " #" + SHARED_BRAND_PK + " of the referenced collection"
				);
				assertEquals(
					requestedFacetsByReference(declinedSummary),
					requestedFacetsByReference(rewrittenSummary),
					"The rewritten plan must report exactly the facet selections the owner-side plan reports"
				);
				return null;
			}
		);
	}

	/**
	 * Builds the question "which categories hold at least one `products` row", optionally written so the rewrite
	 * declines and the owner-side path answers instead.
	 *
	 * @param requestedScope scope the owners are searched in
	 * @param declineRewrite when TRUE the decline knob is added, making the constraint shape unsupported
	 * @return the query
	 */
	@Nonnull
	private static Query categoriesHoldingAnyProductQuery(
		@Nonnull Scope requestedScope,
		boolean declineRewrite
	) {
		final ReferenceHaving reference = declineRewrite ?
			referenceHaving(REF_CATEGORY_PRODUCTS, everyProductKeyDeclineKnob()) :
			referenceHaving(REF_CATEGORY_PRODUCTS);
		return query(
			collection(Entities.CATEGORY),
			filterBy(
				scope(requestedScope),
				reference
			),
			require(
				debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.PREFER_INDEX_SCAN),
				page(1, Integer.MAX_VALUE)
			)
		);
	}

	/**
	 * Builds the question "which categories hold a `products` row to a product carrying brand 7", with a reference
	 * summary requested for every faceted reference of the **owner**, optionally written so the rewrite declines.
	 *
	 * `referenceSummary` is used rather than the deprecated `facetSummary`: the two differ only in which DTO the
	 * result adapter constructs - `FacetSummaryTranslator#createProducer` delegates straight to
	 * `ReferenceSummaryTranslator#createProducerInternal` with `FacetSummaryAdapter.INSTANCE` in place of
	 * `ReferenceSummaryAdapter.INSTANCE` - so both reach the identical `ReferenceSummaryProducer` and the identical
	 * `requested` flag. Pinning the flag through the constraint scheduled for removal would delete this regression
	 * test along with the constraint.
	 *
	 * @param depth          statistics depth the summary is computed at
	 * @param declineRewrite when TRUE the decline knob is added, making the constraint shape unsupported
	 * @return the query
	 */
	@Nonnull
	private static Query nestedFacetSelectionQuery(
		@Nonnull FacetStatisticsDepth depth,
		boolean declineRewrite
	) {
		final FilterConstraint nestedSelection = entityHaving(
			facetHaving(REF_PRODUCT_BRAND, entityHaving(entityPrimaryKeyInSet(SHARED_BRAND_PK)))
		);
		final ReferenceHaving reference = declineRewrite ?
			referenceHaving(REF_CATEGORY_PRODUCTS, nestedSelection, everyProductKeyDeclineKnob()) :
			referenceHaving(REF_CATEGORY_PRODUCTS, nestedSelection);
		return query(
			collection(Entities.CATEGORY),
			filterBy(reference),
			require(
				referenceSummary(depth),
				debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.PREFER_INDEX_SCAN),
				page(1, Integer.MAX_VALUE)
			)
		);
	}

	/**
	 * Builds the question "which products hold a `categories` row", asked from the end where the rewrite declines
	 * on the cost gate alone, optionally with the decline knob added on top.
	 *
	 * @param requestedScopes              scopes the owners are searched in
	 * @param referenceAttributeConstraint reference-attribute constraint carried inside the `referenceHaving`, or
	 *                                     NULL for the bare form
	 * @param withDeclineKnob              when TRUE the knob is appended as a further child
	 * @return the query
	 */
	@Nonnull
	private static Query productsHoldingCategoryRowQuery(
		@Nonnull Scope[] requestedScopes,
		@Nullable FilterConstraint referenceAttributeConstraint,
		boolean withDeclineKnob
	) {
		final List<FilterConstraint> children = new ArrayList<>(2);
		if (referenceAttributeConstraint != null) {
			children.add(referenceAttributeConstraint);
		}
		if (withDeclineKnob) {
			children.add(everyCategoryKeyDeclineKnob());
		}
		return query(
			collection(Entities.PRODUCT),
			filterBy(
				scope(requestedScopes),
				referenceHaving(REF_PRODUCT_CATEGORIES, children.toArray(FilterConstraint[]::new))
			),
			require(
				debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.PREFER_INDEX_SCAN),
				page(1, Integer.MAX_VALUE)
			)
		);
	}

	/**
	 * Runs one sub-shape twice - once plain, once with the decline knob - and asserts the two answers are identical
	 * down to their order.
	 *
	 * @param session                      open read session
	 * @param shapeDescription             readable name of the sub-shape, used in the failure message
	 * @param requestedScopes              scopes the owners are searched in
	 * @param referenceAttributeConstraint reference-attribute constraint carried inside the `referenceHaving`, or
	 *                                     NULL for the bare form
	 * @return the primary keys the plain variant answered, in result order
	 */
	@Nonnull
	private static List<Integer> assertDeclineKnobIsInert(
		@Nonnull EvitaSessionContract session,
		@Nonnull String shapeDescription,
		@Nonnull Scope[] requestedScopes,
		@Nullable FilterConstraint referenceAttributeConstraint
	) {
		final List<Integer> withoutKnob = resultPrimaryKeyOrder(
			session.query(
				productsHoldingCategoryRowQuery(requestedScopes, referenceAttributeConstraint, false),
				EntityReference.class
			).getRecordData()
		);
		final List<Integer> withKnob = resultPrimaryKeyOrder(
			session.query(
				productsHoldingCategoryRowQuery(requestedScopes, referenceAttributeConstraint, true),
				EntityReference.class
			).getRecordData()
		);
		assertFalse(
			withoutKnob.isEmpty(),
			"Oracle guard: `" + shapeDescription + "` must answer something, otherwise the comparison is vacuous"
		);
		assertEquals(
			withoutKnob, withKnob,
			"The decline knob changed the answer of `" + shapeDescription + "` - every paired row in this class " +
				"compares a rewritten query against a knobbed one, so this invalidates the oracle rather than the " +
				"code under test"
		);
		return withoutKnob;
	}

	/**
	 * Asserts the cost gate cannot fire for `collection(PRODUCT)` + `referenceHaving(categories)` in the passed
	 * scope, so that both variants of the knob comparison run the ordinary owner-side path.
	 *
	 * Under `scope(ARCHIVED)` the rewrite additionally cannot fire at all, because the reflected counterpart has no
	 * ARCHIVED type index and `worthRewriting` returns on `counterpartTypeIndex.isEmpty()`. This arithmetic guard is
	 * therefore conservative there rather than wrong - it holds for a weaker reason than the one that actually
	 * applies, and keeps holding if the indexing gap is ever closed.
	 *
	 * Candidates are the distinct product primary keys the counterpart `CATEGORY.products` type index announces;
	 * buckets are the reduced indexes `PRODUCT.categories` would visit, one per referenced category. Both counts
	 * are readable off the originals - the indexes hold different *values* (internal index keys) but the same
	 * cardinality.
	 *
	 * @param originalCategories every category, both scopes, fully fetched
	 * @param originalProducts   every product, both scopes, fully fetched
	 * @param requestedScope     scope to check the arithmetic in
	 */
	private static void assertRewriteDeclinesOnTheProductEnd(
		@Nonnull List<SealedEntity> originalCategories,
		@Nonnull List<SealedEntity> originalProducts,
		@Nonnull Scope requestedScope
	) {
		final int candidates = referencedPrimaryKeys(
			inScope(originalCategories, requestedScope), REF_CATEGORY_PRODUCTS
		).size();
		final int buckets = referencedPrimaryKeys(
			inScope(originalProducts, requestedScope), REF_PRODUCT_CATEGORIES
		).size();
		assertTrue(
			(long) candidates * REWRITE_MINIMAL_GAIN > buckets,
			"Fixture guard: the rewrite must decline on the product end in " + requestedScope + " regardless of " +
				"the knob - " + candidates + " candidates against " + buckets + " buckets would otherwise pass the " +
				"gate and this row would be comparing two different plans"
		);
	}

	/**
	 * Builds the question "which categories hold a `products` row to a product inside the `taxonomyStats` subtree",
	 * with hierarchy statistics requested for the owner's identically named reference, optionally written so the
	 * rewrite declines.
	 *
	 * @param declineRewrite when TRUE the decline knob is added, making the constraint shape unsupported
	 * @return the query
	 */
	@Nonnull
	private static Query nestedHierarchyStatisticsQuery(boolean declineRewrite) {
		final FilterConstraint nestedSubtree = entityHaving(
			hierarchyWithin(REF_TAXONOMY_STATS, entityPrimaryKeyInSet(TAXONOMY_SUBTREE_ROOT_PK))
		);
		final ReferenceHaving reference = declineRewrite ?
			referenceHaving(REF_CATEGORY_PRODUCTS, nestedSubtree, everyProductKeyDeclineKnob()) :
			referenceHaving(REF_CATEGORY_PRODUCTS, nestedSubtree);
		return query(
			collection(Entities.CATEGORY),
			filterBy(reference),
			require(
				hierarchyOfReference(
					REF_TAXONOMY_STATS,
					EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
					fromRoot(
						TAXONOMY_STATS_OUTPUT_NAME,
						statistics(StatisticsType.QUERIED_ENTITY_COUNT)
					)
				),
				debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.PREFER_INDEX_SCAN),
				page(1, Integer.MAX_VALUE)
			)
		);
	}

	/**
	 * Flattens the `taxonomyStats` hierarchy tree into node primary key to queried entity count. `LEAVE_EMPTY` keeps
	 * the nodes whose count is zero, so the two variants are always compared over the same key set and a node that
	 * appears in one and not the other shows up as a difference rather than being silently skipped.
	 *
	 * @param hierarchy hierarchy extra result to read
	 * @return node primary key to queried entity count, ascending by node
	 */
	@Nonnull
	private static Map<Integer, Integer> queriedEntityCountByNode(@Nonnull Hierarchy hierarchy) {
		final Map<Integer, Integer> result = new TreeMap<>();
		for (final LevelInfo root : hierarchy.getReferenceHierarchy(REF_TAXONOMY_STATS, TAXONOMY_STATS_OUTPUT_NAME)) {
			collectQueriedEntityCounts(root, result);
		}
		return result;
	}

	/**
	 * Adds the passed level and every level below it to the accumulator.
	 *
	 * @param levelInfo level to record
	 * @param target    accumulator keyed by hierarchy node primary key
	 */
	private static void collectQueriedEntityCounts(
		@Nonnull LevelInfo levelInfo,
		@Nonnull Map<Integer, Integer> target
	) {
		target.put(levelInfo.entity().getPrimaryKeyOrThrowException(), levelInfo.queriedEntityCount());
		for (final LevelInfo child : levelInfo.children()) {
			collectQueriedEntityCounts(child, target);
		}
	}

	/**
	 * The decline knob over **every** product primary key, the archived ten included - the form the rows asking
	 * from the category end need, because `CATEGORY.products` points at the product collection.
	 *
	 * `BidirectionalReferenceRewriter#splitChildren` rejects the constraint shape outright, so the owner-side path
	 * answers; and because the key set spans the whole referenced collection across both scopes, the constraint
	 * narrows nothing and the two paths are asked the same question.
	 *
	 * @return the knob
	 */
	@Nonnull
	private static EntityPrimaryKeyInSet everyProductKeyDeclineKnob() {
		return declineKnobOverEveryKeyUpTo(PRODUCT_COUNT);
	}

	/**
	 * The decline knob over **every** category primary key, the archived one included - the form the rows asking
	 * from the product end need, because `PRODUCT.categories` points at the category collection.
	 *
	 * @return the knob
	 */
	@Nonnull
	private static EntityPrimaryKeyInSet everyCategoryKeyDeclineKnob() {
		return declineKnobOverEveryKeyUpTo(CATEGORY_COUNT);
	}

	/**
	 * Enumerates `1..highestPrimaryKey` into an `entityPrimaryKeyInSet`. The fixture assigns primary keys densely
	 * from 1, so this is the whole referenced collection **in every scope** - and only a complete list is inert,
	 * because index discovery translates the knob against the referenced collection's GLOBAL indexes over
	 * `Scope.values()` rather than suppressing it.
	 *
	 * @param highestPrimaryKey highest primary key of the referenced collection
	 * @return the knob
	 */
	@Nonnull
	private static EntityPrimaryKeyInSet declineKnobOverEveryKeyUpTo(int highestPrimaryKey) {
		final int[] everyKey = new int[highestPrimaryKey];
		for (int i = 0; i < highestPrimaryKey; i++) {
			everyKey[i] = i + 1;
		}
		return entityPrimaryKeyInSet(everyKey);
	}

	/**
	 * Collects the primary keys of the returned entity references.
	 *
	 * @param records records of an `EntityReference` response
	 * @return ascending primary keys
	 */
	@Nonnull
	private static Set<Integer> resultPrimaryKeys(@Nonnull List<EntityReference> records) {
		final Set<Integer> result = new TreeSet<>();
		for (final EntityReference record : records) {
			result.add(record.getPrimaryKey());
		}
		return result;
	}

	/**
	 * Collects the primary keys of the returned entity references **in result order**, so a comparison made with
	 * this cannot be fooled by a reordering the way a set comparison could.
	 *
	 * @param records records of an `EntityReference` response
	 * @return primary keys in result order
	 */
	@Nonnull
	private static List<Integer> resultPrimaryKeyOrder(@Nonnull List<EntityReference> records) {
		final List<Integer> result = new ArrayList<>(records.size());
		for (final EntityReference record : records) {
			result.add(record.getPrimaryKey());
		}
		return result;
	}

	/**
	 * Collects the primary keys of the passed entities. Pair it with the fixture's `inScope` helper to narrow the
	 * originals to a single scope first.
	 *
	 * @param entities fully fetched originals
	 * @return ascending primary keys
	 */
	@Nonnull
	private static Set<Integer> entityPrimaryKeys(@Nonnull List<SealedEntity> entities) {
		final Set<Integer> result = new TreeSet<>();
		for (final SealedEntity entity : entities) {
			result.add(entity.getPrimaryKeyOrThrowException());
		}
		return result;
	}

	/**
	 * Collects the primary keys of the products carrying a `categories` row to the passed category.
	 *
	 * @param originalProducts fully fetched product originals spanning both scopes
	 * @param categoryPk       category the row has to point at
	 * @return ascending product primary keys
	 */
	@Nonnull
	private static Set<Integer> primaryKeysOfProductsReferencing(
		@Nonnull List<SealedEntity> originalProducts,
		int categoryPk
	) {
		final Set<Integer> result = new TreeSet<>();
		for (final SealedEntity product : originalProducts) {
			for (final ReferenceContract categoryReference : product.getReferences(REF_PRODUCT_CATEGORIES)) {
				if (categoryReference.getReferencedPrimaryKey() == categoryPk) {
					result.add(product.getPrimaryKeyOrThrowException());
					break;
				}
			}
		}
		return result;
	}

	/**
	 * Tells whether the passed category is referenced by at least one LIVE product whose `productActive` attribute
	 * is TRUE. The LIVE restriction mirrors the engine: the nested query planned for `entityHaving` runs against
	 * the referenced collection's GLOBAL indexes for the **processing** scopes, which is LIVE only here.
	 *
	 * @param originalProducts fully fetched product originals spanning both scopes
	 * @param categoryPk       category the row has to point at
	 * @return TRUE when such a product exists
	 */
	private static boolean holdsRowToAnActiveLiveProduct(
		@Nonnull List<SealedEntity> originalProducts,
		int categoryPk
	) {
		for (final SealedEntity product : originalProducts) {
			if (product.getScope() != Scope.LIVE) {
				continue;
			}
			if (!Boolean.TRUE.equals(product.getAttribute(ATTR_PRODUCT_ACTIVE, Boolean.class))) {
				continue;
			}
			for (final ReferenceContract categoryReference : product.getReferences(REF_PRODUCT_CATEGORIES)) {
				if (categoryReference.getReferencedPrimaryKey() == categoryPk) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Collects, per reference name, the facet primary keys the summary marks as requested.
	 *
	 * @param summary summary to read
	 * @return reference name to ascending requested facet primary keys; a reference with no requested facet maps
	 *         to an empty set rather than being absent, so the comparison of two summaries stays symmetric
	 */
	@Nonnull
	private static Map<String, Set<Integer>> requestedFacetsByReference(@Nonnull ReferenceSummary summary) {
		final Map<String, Set<Integer>> result = new TreeMap<>();
		for (final ReferenceGroupStatistics group : summary.getReferenceStatistics()) {
			final Set<Integer> requestedFacets = result.computeIfAbsent(
				group.getReferenceName(), referenceName -> new TreeSet<>()
			);
			for (final FacetStatistics facet : group.getFacetStatistics()) {
				if (facet.isRequested()) {
					requestedFacets.add(facet.getFacetEntity().getPrimaryKey());
				}
			}
		}
		return result;
	}

	/**
	 * Locates the statistics of a single facet of a single reference.
	 *
	 * @param summary       summary to read
	 * @param referenceName reference the facet belongs to
	 * @param facetPk       primary key of the facet entity
	 * @return the statistics, or NULL when the summary does not carry that facet at all
	 */
	@Nullable
	private static FacetStatistics facetStatisticsOf(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName,
		int facetPk
	) {
		for (final ReferenceGroupStatistics group : summary.getReferenceStatistics()) {
			if (!referenceName.equals(group.getReferenceName())) {
				continue;
			}
			final FacetStatistics facet = group.getFacetStatistics(facetPk);
			if (facet != null) {
				return facet;
			}
		}
		return null;
	}

}
